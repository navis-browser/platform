/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.navis.browser.engine

import android.app.Activity
import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Looper
import android.os.Handler
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import java.lang.ref.WeakReference
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import org.navis.browser.engine.extensions.EngineExtensionDownloadException
import org.navis.browser.R
import android.widget.Toast

internal data class AndroidExtensionDownloadSaveAsRequest(
    val fileName: String,
    val mimeType: String,
    val replaceExisting: Boolean = false,
)

internal data class AndroidDownloadDocumentSelection(val uri: Uri, val flags: Int)

/** Foreground-Activity broker for extension Save As requests; URL data never crosses this seam. */
internal object AndroidExtensionDownloadSaveAs {
    private val bindings = mutableMapOf<Long, Registration>()

    fun register(activity: ComponentActivity): Registration {
        check(Looper.myLooper() == Looper.getMainLooper()) { "Save As must bind on the UI thread" }
        lateinit var created: Registration
        val launcher = activity.registerForActivityResult(SaveAsDocumentContract()) { uri ->
            complete(created, uri)
        }
        val permissionLauncher = activity.registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            completeStoragePermission(created, hasPublicDownloadsPermission(activity))
        }
        created = Registration(launcher, permissionLauncher, WeakReference(activity))
        return created
    }

    fun hasPublicDownloadsPermission(context: Context): Boolean = Build.VERSION.SDK_INT >= 29 ||
        LEGACY_STORAGE_PERMISSIONS.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }

    /** Shares the originating window's existing ActivityResult/lease lifetime, not a new Activity. */
    fun requestPublicDownloadsPermission(windowId: Long?): CompletionStage<Boolean> = onUi {
        val owner = bindings[windowId] ?: return@onUi CompletableFuture.completedFuture(false)
        val activity = owner.activity.get() ?: return@onUi CompletableFuture.completedFuture(false)
        if (hasPublicDownloadsPermission(activity)) return@onUi CompletableFuture.completedFuture(true)
        owner.pendingPermission?.let { return@onUi it }
        if (activity.isFinishing || activity.isDestroyed || owner.pending != null ||
            !activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            return@onUi CompletableFuture.completedFuture(false)
        }
        val result = CompletableFuture<Boolean>()
        owner.pendingPermission = result
        runCatching { owner.permissionLauncher.launch(LEGACY_STORAGE_PERMISSIONS) }
            .onFailure { completeStoragePermission(owner, false) }
        result
    }

    private fun completeStoragePermission(owner: Registration, granted: Boolean) {
        val result = owner.pendingPermission ?: return
        owner.pendingPermission = null
        val activity = owner.activity.get()
        val current = owner.lease?.let { bindings[it.windowId] === owner } == true &&
            activity != null && !activity.isFinishing && !activity.isDestroyed
        if (current && !granted) Toast.makeText(activity, R.string.download_storage_permission_denied,
            Toast.LENGTH_LONG).show()
        result.complete(current && granted)
    }

    fun request(
        fileName: String,
        mimeType: String,
        replaceExisting: Boolean = false,
        windowId: Long,
    ): CompletionStage<String> {
        check(Looper.myLooper() == Looper.getMainLooper()) {
            "Save As requests must originate on the UI thread"
        }
        val active = bindings[windowId]
            ?: return failed("Save As requires a foreground Navis window")
        val activity = active.activity.get()
        if (activity == null || activity.isFinishing || activity.isDestroyed ||
            !activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            return failed("Save As requires its originating window in the foreground")
        }
        if (active.pending != null || active.pendingPermission != null) {
            return failed("Another Save As request is already active")
        }
        val result = CompletableFuture<String>()
        active.pending = result
        try {
            active.launcher.launch(
                AndroidExtensionDownloadSaveAsRequest(
                    DownloadMetadata.sanitizeFileName(fileName),
                    DownloadMetadata.sanitizeMimeType(mimeType),
                    replaceExisting,
                ),
            )
        } catch (error: Throwable) {
            active.pending = null
            result.completeExceptionally(
                EngineExtensionDownloadException("Could not open the Android Save As picker"),
            )
        }
        return result
    }

    private fun complete(owner: Registration, selected: AndroidDownloadDocumentSelection?) {
        if (owner.lease?.let { bindings[it.windowId] !== owner } != false) {
            return
        }
        val result = owner.pending ?: return
        owner.pending = null
        val uri = selected?.uri
        if (uri == null) {
            result.completeExceptionally(EngineExtensionDownloadException("Download cancelled"))
        } else if (uri.scheme != CONTENT_RESOLVER_SCHEME) {
            result.completeExceptionally(
                EngineExtensionDownloadException("Android returned an invalid Save As target"),
            )
        } else {
            val flags = selected.flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            if (selected.flags and Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION != 0) {
                runCatching {
                    owner.activity.get()?.contentResolver?.takePersistableUriPermission(uri, flags)
                }
            }
            result.complete(uri.toString())
        }
    }

    /** Dispatch is the acknowledgement; Android owns the document browser and its navigation. */
    fun launch(intent: Intent, windowId: Long): CompletionStage<Unit> = onUi {
        val activity = bindings[windowId]?.activity?.get()
            ?: throw EngineExtensionDownloadException("A foreground Navis window is required")
        if (activity.isFinishing || activity.isDestroyed ||
            !activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        ) throw EngineExtensionDownloadException("A foreground Navis window is required")
        try {
            activity.startActivity(intent)
        } catch (error: Exception) {
            throw EngineExtensionDownloadException("No Android file manager can handle this request")
        }
        CompletableFuture.completedFuture(Unit)
    }

    fun <T> onUi(operation: () -> CompletionStage<T>): CompletionStage<T> {
        val result = CompletableFuture<T>()
        val action = Runnable {
            try {
                operation().whenComplete { value, error ->
                    if (error != null) result.completeExceptionally(error) else result.complete(value)
                }
            } catch (error: Throwable) { result.completeExceptionally(error) }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) action.run()
        else Handler(Looper.getMainLooper()).post(action)
        return result
    }

    private fun failed(message: String): CompletionStage<String> =
        CompletableFuture<String>().also {
            it.completeExceptionally(EngineExtensionDownloadException(message))
        }

    internal class Registration internal constructor(
        internal val launcher: ActivityResultLauncher<AndroidExtensionDownloadSaveAsRequest>,
        internal val permissionLauncher: ActivityResultLauncher<Array<String>>,
        internal val activity: WeakReference<ComponentActivity>,
    ) : AutoCloseable {
        internal var lease: AndroidWindowLease? = null
            private set
        internal var pending: CompletableFuture<String>? = null
        internal var pendingPermission: CompletableFuture<Boolean>? = null
        private var closed = false

        fun bind(lease: AndroidWindowLease) {
            check(Looper.myLooper() == Looper.getMainLooper() && !closed)
            check(this.lease == null || this.lease == lease) { "Save As registration cannot change Activity ownership" }
            val previous = bindings[lease.windowId]
            check(previous === this || previous?.lease?.generation?.let { it >= lease.generation } != true) { "Stale Save As Activity lease" }
            if (previous === this) return
            this.lease = lease
            bindings[lease.windowId] = this
            previous?.close()
        }

        override fun close() {
            if (closed) return
            closed = true
            lease?.let { if (bindings[it.windowId] === this) bindings.remove(it.windowId) }
            val result = pending
            pending = null
            result?.completeExceptionally(EngineExtensionDownloadException("The Save As window was closed"))
            val permission = pendingPermission
            pendingPermission = null
            permission?.complete(false)
            launcher.unregister()
            permissionLauncher.unregister()
        }
    }

    private const val CONTENT_RESOLVER_SCHEME = "content"
    private val LEGACY_STORAGE_PERMISSIONS = arrayOf(
        Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE,
    )
}

private class SaveAsDocumentContract :
    ActivityResultContract<AndroidExtensionDownloadSaveAsRequest, AndroidDownloadDocumentSelection?>() {
    override fun createIntent(
        context: Context,
        input: AndroidExtensionDownloadSaveAsRequest,
    ): Intent = Intent(if (input.replaceExisting) Intent.ACTION_OPEN_DOCUMENT else Intent.ACTION_CREATE_DOCUMENT).apply {
        addCategory(Intent.CATEGORY_OPENABLE)
        type = input.mimeType
        putExtra(Intent.EXTRA_TITLE, input.fileName)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
            Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
    }

    override fun parseResult(resultCode: Int, intent: Intent?): AndroidDownloadDocumentSelection? =
        intent?.data?.takeIf { resultCode == Activity.RESULT_OK }
            ?.let { AndroidDownloadDocumentSelection(it, intent.flags) }
}
