/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.navis.browser.downloads

internal enum class DownloadAction { PAUSE, RESUME, CANCEL, RETRY, REMOVE, OPEN, CLEAR_FINISHED }

internal class DownloadActionTicket internal constructor(
    val action: DownloadAction,
    val id: Long?,
    internal val clearedIds: Set<Long> = emptySet(),
) {
    internal var settled = false
    internal var settledObservation = Long.MAX_VALUE
}

internal data class DownloadActionState(val busyIds: Set<Long>, val clearing: Boolean, val canClear: Boolean)

/** Main-thread presentation gate; the coordinator remains the only download state owner. */
internal class DownloadActionPolicy(private val privateMode: Boolean) {
    private var closed = false
    private var observation = 0L
    private var entries = emptyMap<Long, DownloadEntry>()
    private val pending = linkedMapOf<Long?, DownloadActionTicket>()

    fun observe(values: List<DownloadEntry>): Boolean {
        if (closed) return false
        observation++
        entries = values.filter { it.privateMode == privateMode }.associateBy(DownloadEntry::id)
        pending.entries.removeAll { (_, ticket) ->
            ticket.settled && (acknowledged(ticket) || observation > ticket.settledObservation)
        }
        return true
    }

    fun state(): DownloadActionState = DownloadActionState(
        busyIds = pending.keys.filterNotNull().toSet(),
        clearing = pending.containsKey(null),
        canClear = canBegin(DownloadAction.CLEAR_FINISHED, null),
    )

    fun begin(action: DownloadAction, id: Long? = null): DownloadActionTicket? {
        if (!canBegin(action, id)) return null
        val clearedIds = if (action == DownloadAction.CLEAR_FINISHED) {
            entries.values.filterNot(DownloadEntry::active).mapTo(mutableSetOf(), DownloadEntry::id)
        } else emptySet()
        return DownloadActionTicket(action, id, clearedIds).also { pending[id] = it }
    }

    /** True only for the first completion belonging to this still-visible surface. */
    fun complete(ticket: DownloadActionTicket, succeeded: Boolean): Boolean {
        if (closed || pending[ticket.id] !== ticket || ticket.settled) return false
        ticket.settled = true
        ticket.settledObservation = observation
        if (!succeeded || acknowledged(ticket)) pending.remove(ticket.id)
        return true
    }

    /**
     * A successful future acknowledges the backend command, not an enduring state. Another
     * owner (downloads.*) may already have changed it again before observers run. When the
     * requested state was not observed, refresh and accept the next authoritative snapshot;
     * never require a coalesced intermediate PAUSED/DOWNLOADING state to appear forever.
     */
    fun requiresSnapshot(ticket: DownloadActionTicket): Boolean =
        !closed && pending[ticket.id] === ticket && ticket.settled

    fun close() {
        closed = true
        pending.clear()
        entries = emptyMap()
    }

    private fun canBegin(action: DownloadAction, id: Long?): Boolean {
        if (closed || pending.containsKey(null)) return false
        if (action == DownloadAction.CLEAR_FINISHED) {
            return id == null && pending.isEmpty() && entries.values.any { !it.active }
        }
        if (id == null || pending.containsKey(id)) return false
        val entry = entries[id] ?: return false
        return when (action) {
            DownloadAction.PAUSE -> entry.state == DownloadState.DOWNLOADING && entry.canPause
            DownloadAction.RESUME -> entry.state == DownloadState.PAUSED && entry.canPause
            DownloadAction.CANCEL -> entry.active
            DownloadAction.RETRY -> entry.canRetry
            DownloadAction.OPEN -> entry.canOpen
            DownloadAction.REMOVE -> true
            DownloadAction.CLEAR_FINISHED -> false
        }
    }

    private fun acknowledged(ticket: DownloadActionTicket): Boolean {
        val entry = entries[ticket.id]
        return when (ticket.action) {
            DownloadAction.PAUSE -> entry == null || entry.state == DownloadState.PAUSED || !entry.active
            DownloadAction.RESUME -> entry == null || entry.state == DownloadState.DOWNLOADING || !entry.active
            DownloadAction.CANCEL -> entry == null || !entry.active
            DownloadAction.RETRY, DownloadAction.REMOVE -> entry == null
            // ACTION_VIEW acceptance itself is the result; opening does not mutate download history.
            DownloadAction.OPEN -> true
            DownloadAction.CLEAR_FINISHED -> ticket.clearedIds.none(entries::containsKey)
        }
    }
}
