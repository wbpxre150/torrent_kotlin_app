package com.torrent.app

import com.torrent.app.transmission.PendingSelection
import com.torrent.app.transmission.PendingSelectionStore
import com.torrent.app.transmission.SelectionWatcher
import com.torrent.app.transmission.TransmissionRpcClient

/**
 * An inert [SelectionWatcher] for tests that only need to CONSTRUCT a
 * `ControlServer` (torrent-page stage 5 made it a required ctor param).
 *
 * Nothing here starts a thread, opens a socket or touches Android: the
 * `selection-status` route reads `status()`, which answers
 * [com.torrent.app.transmission.SelectionStatus.NONE] for every hash until a
 * watch is registered, and no test registers one. The real watcher's behaviour
 * is pinned by `SelectionWatcherTest`.
 */
internal fun inertSelectionWatcher(): SelectionWatcher = SelectionWatcher(
    rpcFactory = { TransmissionRpcClient("user", "pass") },
    store = EmptySelectionStore(),
    clock = { 0L },
    sleeper = { },
)

/** A [PendingSelectionStore] that keeps nothing — these tests never register a watch. */
internal class EmptySelectionStore : PendingSelectionStore {
    override fun all(): List<PendingSelection> = emptyList()
    override fun put(entry: PendingSelection) = Unit
    override fun remove(hashHex: String) = Unit
}
