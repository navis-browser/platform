/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.navis.browser.engine

import android.Manifest
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Looper
import org.navis.browser.R
import org.navis.browser.WindowAttentionActivity
import java.lang.ref.WeakReference
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage

/** Product-window attention, not a website notification or an extension notification API.
 *
 * The Runtime supplies the current Activity and a closure that revalidates its captured lease,
 * extension and private-window authority before focusing that same task. Nothing in an Intent
 * grants window authority. Tokens are single-use and disappear with this Runtime/process.
 * All entry points run on the Android main thread.
 */
internal class AndroidWindowAttention(context: Context) : AutoCloseable {
    private val context = context.applicationContext
    private val profileNamespace = checkNotNull((this.context as org.navis.browser.NavisApplication)
        .profileScope).currentId
    private val manager = checkNotNull(this.context.getSystemService(NotificationManager::class.java))
    private val pending = mutableMapOf<Long, Attention>()
    private var closed = false

    private class Attention(
        val windowId: Long,
        val token: String,
        val activity: WeakReference<Activity>,
        val taskId: Int,
        val intent: PendingIntent,
        val onActivate: (Activity) -> CompletionStage<Unit>,
    )

    fun show(
        windowId: Long,
        privateMode: Boolean,
        extensionId: String,
        extensionName: String,
        activity: Activity,
        onActivate: (Activity) -> CompletionStage<Unit>,
    ) {
        requireMainThread()
        check(!closed) { "Window attention is closed" }
        require(windowId > 0 && extensionId.isNotBlank()) { "Invalid window attention owner" }
        check(!activity.isFinishing && !activity.isDestroyed && activity.taskId >= 0) {
            "The requested Navis window is unavailable"
        }
        if (activity.hasWindowFocus()) {
            clear(windowId)
            return
        }
        // Background extension calls must not trigger an unsolicited permission dialog.
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) throw SecurityException("Android notification permission is required for window attention")
        check(manager.areNotificationsEnabled()) { "Navis notifications are disabled in Android settings" }
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(NotificationChannel(
                CHANNEL_ID, context.getString(R.string.window_attention_channel), NotificationManager.IMPORTANCE_DEFAULT,
            ).apply { lockscreenVisibility = Notification.VISIBILITY_PRIVATE })
            check(manager.getNotificationChannel(CHANNEL_ID)?.importance != NotificationManager.IMPORTANCE_NONE) {
                "Window attention notifications are blocked in Android settings"
            }
        }

        val token = UUID.randomUUID().toString()
        val activation = PendingIntent.getActivity(context, 0,
            org.navis.browser.ProfileWindows.intent(context, "Attention")
                .setAction(ACTION)
                .setData(Uri.parse(TOKEN_PREFIX + token))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_HISTORY or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_ONE_SHOT,
        )
        try {
            val generic = context.getString(R.string.window_attention_generic)
            val text = if (privateMode) generic else context.getString(
                R.string.window_attention_extension,
                safeExtensionName(extensionName).ifEmpty { context.getString(R.string.window_attention_extension_fallback) },
            )
            val publicVersion = builder().setContentText(generic).setVisibility(Notification.VISIBILITY_PUBLIC).build()
            val notification = builder()
                .setContentText(text)
                .setContentIntent(activation)
                .setPublicVersion(publicVersion)
                .setVisibility(if (privateMode) Notification.VISIBILITY_SECRET else Notification.VISIBILITY_PRIVATE)
                .build()
            // Publish first: a failed replacement must not silently discard an existing reminder.
            manager.notify(notificationTag(windowId), NOTIFICATION_ID, notification)
            pending.remove(windowId)?.let(::revoke)
            pending[windowId] = Attention(windowId, token, WeakReference(activity), activity.taskId, activation, onActivate)
            owners[token] = WeakReference(this)
        } catch (error: Throwable) {
            activation.cancel()
            throw error
        }
    }

    fun clear(windowId: Long) {
        requireMainThread()
        pending.remove(windowId)?.let(::revoke)
        // A stale lock-screen notification after process loss can also be cancelled by window id.
        manager.cancel(notificationTag(windowId), NOTIFICATION_ID)
    }

    override fun close() {
        requireMainThread()
        if (closed) return
        closed = true
        pending.keys.toList().forEach(::clear)
    }

    private fun revoke(attention: Attention) {
        owners.remove(attention.token)
        attention.intent.cancel()
    }

    private fun notificationTag(windowId: Long) = "navis-window-attention:$profileNamespace:$windowId"

    private fun activate(token: String, router: Activity): CompletionStage<Unit> {
        val attention = pending.values.firstOrNull { it.token == token }
            ?: return CompletableFuture.completedFuture(Unit)
        clear(attention.windowId)
        val original = attention.activity.get()
        if (closed || original == null || original.isFinishing || original.isDestroyed || original.taskId != attention.taskId) {
            return CompletableFuture.completedFuture(Unit)
        }
        return try {
            attention.onActivate(router)
        } catch (error: Throwable) {
            CompletableFuture<Unit>().apply { completeExceptionally(error) }
        }
    }

    @Suppress("DEPRECATION")
    private fun builder(): Notification.Builder =
        (if (Build.VERSION.SDK_INT >= 26) Notification.Builder(context, CHANNEL_ID) else Notification.Builder(context))
            .setSmallIcon(R.drawable.ic_navis_notification)
            .setContentTitle(context.getString(R.string.app_name))
            .setCategory(Notification.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setLocalOnly(true)

    private companion object {
        const val CHANNEL_ID = "navis-window-attention"
        const val NOTIFICATION_ID = 1 // Namespaced by notification tag, separate from downloads/sites.
        const val ACTION = "org.navis.browser.action.WINDOW_ATTENTION"
        const val TOKEN_PREFIX = "navis-window-attention://activate/"
        val owners = mutableMapOf<String, WeakReference<AndroidWindowAttention>>()
        fun requireMainThread() = check(Looper.myLooper() == Looper.getMainLooper()) {
            "Window attention requires the main thread"
        }
        fun safeExtensionName(name: String): String = name
            .filterNot { Character.isISOControl(it) || Character.getType(it) == Character.FORMAT.toInt() }
            .trim().take(96)
    }

    internal object Activation {
        /** A separate, non-exported Activity routes only live process-memory capabilities. */
        fun dispatch(router: Activity, intent: Intent?): CompletionStage<Unit> {
            requireMainThread()
            if (intent?.action != ACTION) return CompletableFuture.completedFuture(Unit)
            val data = intent.dataString ?: return CompletableFuture.completedFuture(Unit)
            if (!data.startsWith(TOKEN_PREFIX)) return CompletableFuture.completedFuture(Unit)
            val token = data.removePrefix(TOKEN_PREFIX)
            val owner = owners.remove(token)?.get() ?: return CompletableFuture.completedFuture(Unit)
            return owner.activate(token, router)
        }
    }
}
