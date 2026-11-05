/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */
package org.navis.browser.documents

import android.app.Activity
import android.graphics.pdf.PdfRenderer
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.PrintManager
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.channels.FileLock
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.navis.browser.R
import org.navis.browser.engine.operationFailureDetail
import org.navis.browser.engine.runtime.EnginePrintDocument
import org.navis.browser.engine.runtime.EnginePrintSettings

/** The Android spooler owns preview, destinations and Save as PDF. */
internal class NavisPrintDocumentAdapter private constructor(
    private val activity: Activity,
    private val document: EnginePrintDocument,
    private val directory: File,
    private val lockFile: RandomAccessFile,
    private val lock: FileLock,
) : PrintDocumentAdapter() {
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { action -> Thread(action, "Navis print").apply { isDaemon = true } }
    private val finished = AtomicBoolean(false)
    private val currentStream = AtomicReference<InputStream?>()
    private var attributes: PrintAttributes? = null
    private var preview: File? = null
    private var pageCount = 0
    @Volatile private var generation = 0

    override fun onLayout(oldAttributes: PrintAttributes?, newAttributes: PrintAttributes,
        cancellationSignal: CancellationSignal, callback: LayoutResultCallback, extras: Bundle?) {
        val epoch = ++generation
        currentStream.getAndSet(null)?.runCatching { close() }
        attributes = newAttributes
        val cancelled = { finished.get() || cancellationSignal.isCanceled || epoch != generation }
        val terminal = AtomicBoolean(false)
        val stream = AtomicReference<InputStream?>()
        cancellationSignal.setOnCancelListener { stream.getAndSet(null)?.runCatching { close() } }
        if (finished.get()) { callback.onLayoutCancelled(); return }
        worker.execute {
            var file: File? = null
            try {
                val settings = settings(newAttributes)
                val input = render(settings, cancelled)
                stream.set(input)
                currentStream.set(input)
                if (cancelled()) throw java.util.concurrent.CancellationException()
                file = File.createTempFile("preview-", ".pdf", directory)
                input.use { source -> FileOutputStream(file).use { DocumentStreams.copy(source, it, cancelled) } }
                stream.compareAndSet(input, null)
                currentStream.compareAndSet(input, null)
                val count = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                    PdfRenderer(descriptor).use { it.pageCount }
                }
                check(count > 0) { "Print document has no pages" }
                val completedFile = checkNotNull(file)
                main.post {
                    if (!terminal.compareAndSet(false, true)) { completedFile.delete(); return@post }
                    if (cancelled()) {
                        completedFile.delete()
                        callback.onLayoutCancelled()
                    } else {
                        preview?.delete()
                        preview = completedFile
                        pageCount = count
                        callback.onLayoutFinished(PrintDocumentInfo.Builder(document.title.ifBlank { "Navis" })
                            .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT).setPageCount(count).build(),
                            oldAttributes != newAttributes)
                    }
                }
            } catch (error: Throwable) {
                file?.delete()
                main.post {
                    if (terminal.compareAndSet(false, true)) {
                        if (cancelled()) callback.onLayoutCancelled()
                        else callback.onLayoutFailed(activity.getString(R.string.print_failed) + ": " + operationFailureDetail(error))
                    }
                }
            } finally {
                stream.getAndSet(null)?.let { input ->
                    currentStream.compareAndSet(input, null)
                    input.runCatching { close() }
                }
            }
        }
    }

    override fun onWrite(pages: Array<out PageRange>, destination: ParcelFileDescriptor,
        cancellationSignal: CancellationSignal, callback: WriteResultCallback) {
        val epoch = generation
        val selectedAttributes = attributes
        val selectedPreview = preview
        val count = pageCount
        val cancelled = { finished.get() || cancellationSignal.isCanceled || epoch != generation }
        val stream = AtomicReference<InputStream?>()
        cancellationSignal.setOnCancelListener { stream.getAndSet(null)?.runCatching { close() } }
        if (finished.get()) { destination.close(); callback.onWriteCancelled(); return }
        worker.execute {
            try {
                check(selectedAttributes != null && selectedPreview != null && count > 0)
                val ranges = mergeRanges(pages, count)
                require(ranges.isNotEmpty())
                val all = ranges.size == 1 && ranges.single() == (0 until count)
                val input = if (all) selectedPreview.inputStream()
                    else render(settings(selectedAttributes).copy(ranges = ranges), cancelled)
                stream.set(input)
                currentStream.set(input)
                if (cancelled()) throw java.util.concurrent.CancellationException()
                input.use { source -> FileOutputStream(destination.fileDescriptor).use {
                    DocumentStreams.copy(source, it, cancelled)
                } }
                stream.compareAndSet(input, null)
                currentStream.compareAndSet(input, null)
                main.post {
                    if (cancelled()) callback.onWriteCancelled()
                    else callback.onWriteFinished(ranges.map { PageRange(it.first, it.last) }.toTypedArray())
                }
            } catch (error: Throwable) {
                main.post {
                    if (cancelled()) callback.onWriteCancelled()
                    else callback.onWriteFailed(activity.getString(R.string.print_failed) + ": " + operationFailureDetail(error))
                }
            } finally {
                stream.getAndSet(null)?.let { input ->
                    currentStream.compareAndSet(input, null)
                    input.runCatching { close() }
                }
                runCatching { destination.close() }
            }
        }
    }

    private fun render(settings: EnginePrintSettings, cancelled: () -> Boolean): InputStream {
        val future = CompletableFuture<InputStream>()
        main.post {
            if (cancelled()) future.completeExceptionally(java.util.concurrent.CancellationException())
            else runCatching { document.render(settings) }.fold(onSuccess = { rendering ->
                rendering.whenComplete { input, error ->
                    if (error != null) future.completeExceptionally(error)
                    else if (cancelled()) {
                        input.close()
                        future.completeExceptionally(java.util.concurrent.CancellationException())
                    } else if (!future.complete(input)) input.close()
                }
            }, onFailure = { future.completeExceptionally(it) })
        }
        return try { future.get(60, TimeUnit.SECONDS) }
            catch (error: Throwable) { future.cancel(false); throw error }
    }

    override fun onFinish() {
        if (!finished.compareAndSet(false, true)) return
        generation++
        currentStream.getAndSet(null)?.runCatching { close() }
        runCatching { document.close() }
        // Queued callbacks finish as cancelled before their owned files/lock go away.
        worker.execute {
            removeFiles(directory)
            runCatching { lock.release() }
            runCatching { lockFile.close() }
            File(directory, ".lock").delete()
            directory.delete()
        }
        worker.shutdown()
    }

    companion object {
        fun start(activity: Activity, document: EnginePrintDocument, onFailure: (Throwable) -> Unit) {
            var adapter: NavisPrintDocumentAdapter? = null
            var directory: File? = null
            var lockFile: RandomAccessFile? = null
            var lock: FileLock? = null
            try {
                val root = File(activity.cacheDir, "navis-print")
                check(root.isDirectory || root.mkdir())
                cleanAbandoned(root)
                directory = File(root, UUID.randomUUID().toString())
                check(directory.mkdir())
                lockFile = RandomAccessFile(File(directory, ".lock"), "rw")
                lock = checkNotNull(lockFile.channel.tryLock())
                adapter = NavisPrintDocumentAdapter(activity, document, directory, lockFile, lock)
                val manager = checkNotNull(activity.getSystemService(PrintManager::class.java)) {
                    "Android printing service is unavailable"
                }
                checkNotNull(manager.print(document.title.ifBlank { "Navis" }, adapter, null)) {
                    "Android printing service did not accept this document"
                }
            } catch (error: Throwable) {
                if (adapter != null) adapter.onFinish() else {
                    runCatching { document.close() }
                    runCatching { lock?.release() }
                    runCatching { lockFile?.close() }
                    directory?.let { File(it, ".lock").delete(); it.delete() }
                }
                onFailure(error)
            }
        }

        private fun settings(attributes: PrintAttributes): EnginePrintSettings {
            val media = checkNotNull(attributes.mediaSize)
            val margins = attributes.minMargins ?: PrintAttributes.Margins.NO_MARGINS
            return EnginePrintSettings(media.widthMils, media.heightMils,
                margins.leftMils, margins.topMils, margins.rightMils, margins.bottomMils,
                attributes.colorMode != PrintAttributes.COLOR_MODE_MONOCHROME)
        }

        internal fun mergeRanges(pages: Array<out PageRange>, count: Int): List<IntRange> {
            require(count > 0 && pages.size <= 1024)
            val result = mutableListOf<IntRange>()
            pages.mapNotNull { range ->
                val end = minOf(range.end, count - 1)
                if (range.start > end) null else range.start..end
            }.sortedBy { it.first }.forEach { range ->
                val previous = result.lastOrNull()
                if (previous != null && range.first <= previous.last + 1) {
                    result[result.lastIndex] = previous.first..maxOf(previous.last, range.last)
                } else result += range
            }
            return result
        }

        private fun removeFiles(directory: File) {
            directory.listFiles()?.take(64)?.filter { file ->
                file.name.matches(Regex("preview-[0-9]+\\.pdf")) && !Files.isSymbolicLink(file.toPath())
            }?.forEach(File::delete)
        }

        private fun cleanAbandoned(root: File) {
            root.listFiles()?.take(64)?.filter { directory ->
                directory.name.matches(Regex("[0-9a-f-]{36}")) && directory.isDirectory &&
                    !Files.isSymbolicLink(directory.toPath())
            }?.forEach { directory ->
                runCatching {
                    val file = File(directory, ".lock")
                    if (Files.isSymbolicLink(file.toPath())) return@runCatching
                    var removable = false
                    RandomAccessFile(file, "rw").use { handle ->
                        val lock = handle.channel.tryLock() ?: return@use
                        lock.use { removeFiles(directory); removable = true }
                    }
                    if (removable) {
                        file.delete()
                        directory.delete()
                    }
                }
            }
        }
    }
}
