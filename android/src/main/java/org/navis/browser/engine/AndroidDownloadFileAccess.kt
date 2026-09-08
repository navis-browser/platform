/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.navis.browser.engine

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Process
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import org.navis.browser.engine.extensions.EngineExtensionDownloadException

/** Exact-file authority checks. Callers must first authorize the download record/private context. */
internal object AndroidDownloadFileAccess {
    fun requireWritableTarget(context: Context, uri: Uri) {
        when {
            uri.scheme == "file" -> ownedFile(context, uri)
            isMediaDownload(uri) -> requireOwnedMediaItem(context, uri)
            else -> requireDocument(context, uri, DocumentsContract.Document.FLAG_SUPPORTS_WRITE)
        }
    }

    fun remove(context: Context, uri: Uri) {
        val removed = when {
            uri.scheme == "file" -> { Files.delete(ownedFile(context, uri).toPath()); true }
            isMediaDownload(uri) -> {
                requireOwnedMediaItem(context, uri)
                context.contentResolver.delete(uri, null, null) == 1
            }
            else -> {
                requireDocument(context, uri, DocumentsContract.Document.FLAG_SUPPORTS_DELETE)
                DocumentsContract.deleteDocument(context.contentResolver, uri)
            }
        }
        if (!removed) fail("The download file could not be removed")
    }

    fun ownedFile(context: Context, uri: Uri): File {
        if (uri.scheme != "file" || !uri.authority.isNullOrEmpty() ||
            uri.query != null || uri.fragment != null
        ) fail("Invalid local download file")
        // Retain access to records produced by older Navis builds. New API 26–28 downloads
        // use the public directory; a matching authorized download record is still required.
        val roots = listOfNotNull(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)?.canonicalFile,
            if (Build.VERSION.SDK_INT < 29) publicDownloadsRoot() else null)
        val file = File(uri.path ?: fail("Missing local download file"))
        val canonical = file.canonicalFile
        if (canonical.toPath() != file.toPath().toAbsolutePath().normalize() ||
            roots.none { canonical.toPath().startsWith(it.toPath()) && canonical != it } ||
            Files.isSymbolicLink(file.toPath()) || !Files.isRegularFile(file.toPath())
        ) fail("The file is not a Navis-owned download")
        return canonical
    }

    fun isDefaultTarget(context: Context, uri: Uri, name: String, directory: String?): Boolean {
        val relative = relativeDirectory(directory)
        return when {
            isMediaDownload(uri) -> context.contentResolver.query(
                uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.RELATIVE_PATH),
                null, null, null,
            )?.use { cursor ->
                cursor.moveToFirst() && cursor.getString(0) == name && cursor.getString(1) == relative
            } == true
            uri.scheme == "file" -> {
                if (Build.VERSION.SDK_INT >= 29) return false
                val root = publicDownloadsRoot()
                val expected = File(directory?.let { File(root, it) } ?: root, name).canonicalFile
                ownedFile(context, uri) == expected
            }
            else -> false
        }
    }

    fun defaultTargetExists(context: Context, name: String, directory: String?): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            val root = publicDownloadsRoot()
            return Files.exists(File(directory?.let { File(root, it) } ?: root, name).toPath(), LinkOption.NOFOLLOW_LINKS)
        }
        return context.contentResolver.query(
            MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
            arrayOf(MediaStore.MediaColumns._ID),
            "${MediaStore.MediaColumns.DISPLAY_NAME} = ? AND ${MediaStore.MediaColumns.RELATIVE_PATH} = ?",
            arrayOf(name, relativeDirectory(directory)), null,
        )?.use { it.moveToFirst() } ?: fail("Could not inspect the download destination")
    }

    fun containingFolderIntent(context: Context, uri: Uri): Intent {
        val document = when {
            DocumentsContract.isDocumentUri(context, uri) -> uri
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && isMediaDownload(uri) -> {
                requireOwnedMediaItem(context, uri)
                MediaStore.getDocumentUri(context, uri)
            }
            else -> null
        } ?: fail("Android cannot locate this file in a document browser")
        return Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            // The system document browser shows the file's containing folder when supported.
            putExtra(DocumentsContract.EXTRA_INITIAL_URI, document)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    fun defaultFolderIntent(): Intent = Intent(DownloadManager.ACTION_VIEW_DOWNLOADS)

    fun icon(context: Context, mimeType: String, size: Int): String {
        if (size != 16 && size != 32) fail("File icon size must be 16 or 32")
        val handler = context.packageManager.resolveActivity(
            Intent(Intent.ACTION_VIEW).setType(mimeType), PackageManager.MATCH_DEFAULT_ONLY,
        )
        val drawable = handler?.loadIcon(context.packageManager)
            ?: context.getDrawable(android.R.drawable.ic_menu_save)
            ?: fail("Android has no file icon available")
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        try {
            drawable.setBounds(0, 0, size, size)
            drawable.draw(Canvas(bitmap))
            val output = ByteArrayOutputStream()
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, output) || output.size() > 64 * 1024) {
                fail("Android could not render the file icon")
            }
            return "data:image/png;base64," + Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP)
        } finally {
            bitmap.recycle()
        }
    }

    private fun requireOwnedMediaItem(context: Context, uri: Uri) {
        if (!isMediaDownload(uri)) fail("Invalid MediaStore download")
        val owned = context.contentResolver.query(
            uri, arrayOf(MediaStore.MediaColumns.OWNER_PACKAGE_NAME), null, null, null,
        )?.use { it.moveToFirst() && it.getString(0) == context.packageName } == true
        if (!owned) fail("The download is not owned by Navis")
    }

    private fun requireDocument(context: Context, uri: Uri, flag: Int) {
        if (uri.scheme != "content" || !DocumentsContract.isDocumentUri(context, uri) ||
            context.checkUriPermission(uri, Process.myPid(), Process.myUid(),
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION) != PackageManager.PERMISSION_GRANTED
        ) fail("The download document has no current write authorization")
        val allowed = context.contentResolver.query(
            uri, arrayOf(DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_FLAGS),
            null, null, null,
        )?.use {
            it.moveToFirst() && it.getString(0) != DocumentsContract.Document.MIME_TYPE_DIR &&
                it.getInt(1) and flag != 0
        } == true
        if (!allowed) fail("The document provider does not permit this file operation")
    }

    private fun isMediaDownload(uri: Uri): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
        uri.scheme == "content" && uri.authority == MediaStore.AUTHORITY && uri.query == null &&
        uri.fragment == null && uri.pathSegments.let {
            it.size == 3 && it[0] == MediaStore.VOLUME_EXTERNAL_PRIMARY && it[1] == "downloads" &&
                (it[2].toLongOrNull() ?: 0) > 0
        }

    private fun relativeDirectory(directory: String?): String =
        Environment.DIRECTORY_DOWNLOADS + "/" + (directory?.let { "$it/" } ?: "")

    private fun publicDownloadsRoot(): File =
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).canonicalFile

    private fun fail(message: String): Nothing = throw EngineExtensionDownloadException(message)
}
