/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.navis.browser.pages

/** Product entries and indices into one authentic engine snapshot; never Runtime Session IDs. */
internal data class NativePageHistorySnapshot(
    val entries: List<String>,
    val index: Int,
    val engine: EnginePageHistory? = null,
    val engineSlots: List<Int> = List(entries.size) { -1 },
    val suppressedSlots: List<Int> = emptyList(),
)

internal class NativePageHistory {
    private data class Entry(val uri: String, val engineId: String? = null)
    private val entries = mutableListOf<Entry>()
    private var index = -1
    private var engine: EnginePageHistory? = null
    private var restored = false
    private val suppressed = mutableSetOf<String>()
    val current: String? get() = entries.getOrNull(index)?.uri
    val currentEngineEntry: EnginePageEntry? get() = entries.getOrNull(index)?.engineId?.let { id ->
        engine?.entries?.firstOrNull { it.identity == id }
    }
    val enginePosition: Int? get() = currentEngineEntry?.let { entry -> engine?.entries?.indexOf(entry) }
    val precedingEnginePosition: Int? get() {
        for (position in index downTo 0) {
            val id = entries[position].engineId ?: continue
            return engine?.entries?.indexOfFirst { it.identity == id }?.takeIf { it >= 0 }
        }
        return null
    }
    val hasNativeEntries: Boolean get() = entries.any { it.engineId == null }
    val canGoBack: Boolean get() = index > 0
    val canGoForward: Boolean get() = index >= 0 && index < entries.lastIndex

    fun push(uri: String): Boolean {
        val canonical = AndroidInternalPages.resolve(uri)?.uri ?: return false
        if (current == canonical) return true
        while (entries.size > index + 1) entries.removeAt(entries.lastIndex).engineId?.let(suppressed::add)
        entries += Entry(canonical)
        if (entries.count { it.engineId == null } > MAX_ENTRIES) {
            entries.removeAt(entries.indexOfFirst { it.engineId == null })
        }
        index = entries.lastIndex
        return true
    }

    fun back(): String? = if (canGoBack) entries[--index].uri else null
    fun forward(): String? = if (canGoForward) entries[++index].uri else null
    fun clear() { entries.clear(); index = -1; engine = null; restored = false; suppressed.clear() }

    /** Merge engine mutations by SHEntry identity. New loads truncate only the selected branch. */
    fun synchronize(next: EnginePageHistory, selectWeb: Boolean) {
        var selected = entries.getOrNull(index)
        // Gecko regenerates SHEntry IDs on restore. Rebase only the exact saved URL sequence;
        // the persisted metadata was already bound to that complete engine snapshot on open.
        if (restored) {
            val previous = engine
            if (previous != null && previous.entries.map { it.uri } == next.entries.map { it.uri }) {
                val ids = previous.entries.zip(next.entries).associate { it.first.identity to it.second.identity }
                entries.replaceAll { it.copy(engineId = it.engineId?.let(ids::get)) }
                val rebased = suppressed.mapNotNull(ids::get)
                suppressed.clear()
                suppressed.addAll(rebased)
                selected = entries.getOrNull(index)
            } else {
                entries.removeAll { it.engineId != null }
                suppressed.clear()
                selected = selected?.takeIf { it.engineId == null }
            }
            restored = false
        }
        val known = entries.any { it.engineId == next.current?.identity }
        if (selectWeb && !known && engine != null && index >= 0) {
            while (entries.size > index + 1) entries.removeAt(entries.lastIndex)
        }
        val byId = next.entries.associateBy { it.identity }
        suppressed.retainAll(byId.keys)
        if (selectWeb) suppressed.remove(next.current?.identity)
        entries.removeAll { it.engineId != null && it.engineId !in byId }
        entries.replaceAll { entry -> entry.engineId?.let { Entry(byId.getValue(it).uri, it) } ?: entry }
        var previousId: String? = null
        for (web in next.entries) {
            // The hidden browser's initial blank is not a product history entry.
            if (web.uri == "about:blank" || web.identity in suppressed) continue
            if (entries.none { it.engineId == web.identity }) {
                val previous = previousId?.let { id -> entries.indexOfFirst { it.engineId == id } } ?: -1
                val nextWeb = entries.indexOfFirstFrom(previous + 1) { it.engineId != null }
                entries.add(if (nextWeb < 0) entries.size else nextWeb, Entry(web.uri, web.identity))
            }
            previousId = web.identity
        }
        engine = next
        index = if (selectWeb) entries.indexOfFirst { it.engineId == next.current?.identity }
            else entries.indexOfFirst { it == selected }
        if (index < 0 && entries.isNotEmpty()) index = entries.lastIndex
    }

    fun snapshot(): NativePageHistorySnapshot? = current?.let {
        NativePageHistorySnapshot(entries.map { it.uri }, index, engine,
            entries.map { entry -> entry.engineId?.let { id -> engine?.entries?.indexOfFirst { it.identity == id } } ?: -1 },
            engine?.entries?.mapIndexedNotNull { position, entry -> position.takeIf { entry.identity in suppressed } } ?: emptyList())
    }

    fun restore(snapshot: NativePageHistorySnapshot?, selectedUri: String): Boolean {
        val valid = validated(snapshot, selectedUri) ?: return false
        entries.clear()
        entries.addAll(valid.entries.mapIndexed { position, uri ->
            Entry(uri, valid.engine?.entries?.getOrNull(valid.engineSlots[position])?.identity)
        })
        index = valid.index
        engine = valid.engine
        restored = valid.engine != null
        suppressed.clear()
        valid.suppressedSlots.forEach { suppressed += checkNotNull(valid.engine).entries[it].identity }
        return true
    }

    companion object {
        const val MAX_ENTRIES = 64
        fun validated(snapshot: NativePageHistorySnapshot?, selectedUri: String): NativePageHistorySnapshot? {
            if (snapshot == null || snapshot.entries.size !in 1..(MAX_ENTRIES + EnginePageHistory.MAX_ENTRIES) ||
                snapshot.index !in snapshot.entries.indices || snapshot.engineSlots.size != snapshot.entries.size) return null
            if (snapshot.engineSlots.count { it == -1 } > MAX_ENTRIES) return null
            var previousSlot = -1
            val canonical = snapshot.entries.mapIndexed { position, uri ->
                val slot = snapshot.engineSlots[position]
                if (slot == -1) AndroidInternalPages.resolve(uri)?.uri ?: return null
                else {
                    val actual = snapshot.engine?.entries?.getOrNull(slot) ?: return null
                    if (slot <= previousSlot || actual.uri != uri) return null
                    previousSlot = slot
                    uri
                }
            }
            if (canonical[snapshot.index] != (AndroidInternalPages.resolve(selectedUri)?.uri ?: selectedUri)) return null
            val webSlots = snapshot.engineSlots.filter { it >= 0 }.toSet()
            if (snapshot.suppressedSlots.distinct().size != snapshot.suppressedSlots.size ||
                snapshot.suppressedSlots.any { it in webSlots || snapshot.engine?.entries?.getOrNull(it) == null }) return null
            return snapshot.copy(entries = canonical)
        }

        /** A native overlay must not authorize restoring an unrelated old opaque webpage blob. */
        fun boundTo(snapshot: NativePageHistorySnapshot?, serialized: String?): Boolean =
            snapshot?.engine != null && snapshot.engine == EnginePageHistory.parse(serialized)

        private inline fun <T> List<T>.indexOfFirstFrom(start: Int, predicate: (T) -> Boolean): Int {
            for (i in start until size) if (predicate(this[i])) return i
            return -1
        }
    }
}
