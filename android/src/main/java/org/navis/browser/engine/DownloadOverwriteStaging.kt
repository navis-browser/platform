/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.navis.browser.engine

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Overwrite never writes the destination while receiving network bytes.
 * App-owned files commit with atomic replacement only. Document providers have
 * no atomic replacement contract: a complete durable backup permits rollback
 * after an ordinary I/O error, but cannot guarantee recovery from process death
 * during provider writes. A failed rollback retains the backup for recovery.
 */
internal class DownloadOverwriteStaging private constructor(
    private val stage: File,
    private val recoveryDirectory: File,
    private val validate: () -> Unit,
    private val checkActive: () -> Unit,
    private val atomicFile: File?,
    private val readTarget: (() -> InputStream)?,
    private val writeTarget: (() -> OutputStream)?,
    private val move: (File, File) -> Unit,
) {
    private var ready = false
    private var committed = false
    private var backup: File? = null
    private var preserveBackup = false

    fun writeFrom(input: InputStream, onProgress: (Long) -> Unit): Long {
        check(!ready && !committed) { "Overwrite stream was already consumed" }
        checkActive()
        val count = writeLocal(input, stage) { count -> checkActive(); onProgress(count) }
        checkActive()
        ready = true
        return count
    }

    fun commit() {
        check(ready && !committed) { "Overwrite has no complete staged download" }
        validate()
        checkActive()
        if (atomicFile != null) {
            // Do not fall back to a non-atomic move or delete the original first.
            move(stage, atomicFile)
            committed = true
            return
        }
        val saved = File.createTempFile(".navis-overwrite-", ".backup", recoveryDirectory)
        backup = saved
        requireNotNull(readTarget).invoke().use { original -> writeLocal(original, saved) { checkActive() } }
        validate()
        checkActive()
        try {
            // Opening rwt may already truncate, even if opening/copying throws.
            replaceFrom(stage, checkActive)
            verifyTarget(stage, checkActive)
            checkActive()
        } catch (failure: Throwable) {
            try {
                validate()
                // Cancellation must not interrupt restoring the old contents.
                replaceFrom(saved) {}
                verifyTarget(saved) {}
            } catch (recoveryFailure: Throwable) {
                preserveBackup = true
                val error = IOException("Download overwrite failed; original backup retained at ${saved.absolutePath}", failure)
                error.addSuppressed(recoveryFailure)
                throw error
            }
            throw failure
        }
        committed = true
        cleanup()
    }

    /** Only private temporary files; never deletes the user's destination. */
    fun abort() = cleanup()

    private fun replaceFrom(file: File, checkpoint: () -> Unit) {
        checkpoint()
        requireNotNull(writeTarget).invoke().use { output ->
            file.inputStream().use { input ->
                DownloadStreamCopier.copy(input, output) { checkpoint() }
            }
            output.flush()
            if (output is FileOutputStream) output.fd.sync()
        }
    }

    private fun verifyTarget(file: File, checkpoint: () -> Unit) {
        file.inputStream().buffered().use { expected ->
            requireNotNull(readTarget).invoke().buffered().use { actual ->
                val left = ByteArray(DownloadStreamCopier.BUFFER_BYTES)
                val right = ByteArray(left.size)
                while (true) {
                    checkpoint()
                    val count = expected.read(left)
                    if (count < 0) {
                        if (actual.read() != -1) throw IOException("Document provider did not truncate the overwritten file")
                        break
                    }
                    var received = 0
                    var emptyReads = 0
                    while (received < count) {
                        checkpoint()
                        val next = actual.read(right, received, count - received)
                        if (next < 0) throw IOException("Overwritten document is incomplete")
                        if (next == 0) {
                            if (++emptyReads > MAX_EMPTY_READS) throw IOException("Document provider made no read progress")
                            continue
                        }
                        emptyReads = 0
                        received += next
                    }
                    for (index in 0 until count) {
                        if (left[index] != right[index]) throw IOException("Document provider did not persist the replacement bytes")
                    }
                }
            }
        }
    }

    private fun cleanup() {
        var failure: IOException? = null
        for (file in listOfNotNull(stage, backup.takeUnless { preserveBackup })) {
            if (file.exists() && !file.delete()) {
                val error = IOException("Could not remove private overwrite temporary file ${file.name}")
                if (failure == null) failure = error else failure.addSuppressed(error)
            }
        }
        failure?.let { throw it }
    }

    companion object {
        private const val SPACE_RESERVE = 1024L * 1024L
        private const val MAX_EMPTY_READS = 16

        fun create(
            stagingDirectory: File,
            recoveryDirectory: File,
            expectedBytes: Long?,
            validate: () -> Unit,
            checkActive: () -> Unit,
            atomicFile: File? = null,
            readTarget: (() -> InputStream)? = null,
            writeTarget: (() -> OutputStream)? = null,
            move: (File, File) -> Unit = { from, to ->
                Files.move(from.toPath(), to.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            },
        ): DownloadOverwriteStaging {
            validate()
            checkActive()
            require(atomicFile != null || (readTarget != null && writeTarget != null))
            for (directory in listOf(stagingDirectory, recoveryDirectory)) {
                if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Overwrite staging storage is unavailable")
            }
            val directory = atomicFile?.parentFile ?: stagingDirectory
            requireNotNull(directory)
            checkSpace(directory, expectedBytes?.coerceAtLeast(0) ?: 0)
            val stage = File.createTempFile(".navis-overwrite-", ".part", directory)
            return DownloadOverwriteStaging(stage, recoveryDirectory, validate, checkActive, atomicFile, readTarget, writeTarget, move)
        }

        private fun checkSpace(directory: File, bytes: Long) {
            val available = directory.usableSpace
            if (available <= SPACE_RESERVE || bytes > available - SPACE_RESERVE) {
                throw IOException("Insufficient storage for safe download overwrite")
            }
        }

        private fun writeLocal(input: InputStream, file: File, onProgress: (Long) -> Unit): Long {
            var total = 0L
            FileOutputStream(file).use { raw ->
                BufferedOutputStream(raw, DownloadStreamCopier.BUFFER_BYTES).use { output ->
                    val buffer = ByteArray(DownloadStreamCopier.BUFFER_BYTES)
                    var emptyReads = 0
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (count == 0) {
                            onProgress(total)
                            if (++emptyReads > MAX_EMPTY_READS) throw IOException("Download stream made no read progress")
                            continue
                        }
                        emptyReads = 0
                        checkSpace(requireNotNull(file.parentFile), count.toLong())
                        output.write(buffer, 0, count)
                        total = Math.addExact(total, count.toLong())
                        onProgress(total)
                    }
                    output.flush()
                    raw.fd.sync()
                }
            }
            return total
        }
    }
}
