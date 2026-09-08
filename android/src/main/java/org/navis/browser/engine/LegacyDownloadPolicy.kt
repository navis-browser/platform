/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.navis.browser.engine

import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.nio.channels.Channels
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.CompletionStage

/** Public Downloads on API 26–28 needs storage permission; SAF/MediaStore do not. */
internal object LegacyDownloadPolicy {
    fun needsStoragePermission(sdk: Int, contentDestination: Boolean): Boolean = sdk < 29 && !contentDestination

    /** Do not occupy a transfer worker while the foreground user answers Android's dialog. */
    fun authorize(
        required: Boolean,
        hasPermission: Boolean,
        requestPermission: () -> CompletionStage<Boolean>,
        start: () -> Unit,
        reject: () -> Unit,
    ) {
        if (!required || hasPermission) { start(); return }
        val permission = try { requestPermission() } catch (_: Exception) { reject(); return }
        permission.whenComplete { allowed, error ->
            if (error == null && allowed == true) start() else reject()
        }
    }

    /** CREATE_NEW is no-clobber even when another Profile/app wins the filename race.
     * Public emulated storage need not support hard links or no-replace atomic renames.
     * A failed copy checks the created file's identity before cleanup; a failed CREATE_NEW
     * never removes a pre-existing target.
     */
    @Throws(IOException::class)
    fun publish(staging: File, target: File) {
        var created = false
        var ownedKey: Any? = null
        try {
            FileChannel.open(target.toPath(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { output ->
                created = true
                ownedKey = Files.readAttributes(target.toPath(), BasicFileAttributes::class.java,
                    LinkOption.NOFOLLOW_LINKS).fileKey()
                FileInputStream(staging).use { input ->
                    input.copyTo(Channels.newOutputStream(output), 64 * 1024)
                }
                output.force(true)
            }
        } catch (error: Throwable) {
            if (created && ownedKey != null) runCatching {
                val current = Files.readAttributes(target.toPath(), BasicFileAttributes::class.java,
                    LinkOption.NOFOLLOW_LINKS)
                if (current.isRegularFile && current.fileKey() == ownedKey) Files.delete(target.toPath())
            }.exceptionOrNull()?.let(error::addSuppressed)
            throw error
        }
    }
}
