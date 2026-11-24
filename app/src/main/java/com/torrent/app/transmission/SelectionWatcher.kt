package com.torrent.app.transmission

import android.util.Log
import com.torrent.app.SelectionRegistrar
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Status of one hash's subset-add watch. [state] is one of [STATE_NONE] (never watched),
 * [STATE_PENDING], [STATE_APPLIED], the three fail-safe pause states, or [STATE_ERROR].
 *
 * [detail] is human-readable (the missing paths for a mismatch, the RPC result for an
 * apply failure); [addedAt] is the persisted add time the deadline is computed from and
 * [updatedAt] the clock reading of the last transition.
 */
data class SelectionStatus(
    val state: String,
    val detail: String = "",
    val unselectedCount: Int = 0,
    val addedAt: Long = 0L,
    val updatedAt: Long = 0L,
) {
    companion object {
        const val STATE_NONE = "none"
        const val STATE_PENDING = "pending"
        const val STATE_APPLIED = "applied"
        const val STATE_PAUSED_METADATA_TIMEOUT = "paused_metadata_timeout"
        const val STATE_PAUSED_MISMATCH = "paused_mismatch"
        const val STATE_PAUSED_APPLY_FAILED = "paused_apply_failed"
        const val STATE_ERROR = "error"

        /** What an unwatched hash reports. */
        val NONE = SelectionStatus(STATE_NONE)
    }
}

/**
 * The three RPC calls the watcher makes, as a seam: `TransmissionRpcClient` is a final
 * class that cannot be faked in a plain-JVM test, and the watcher's whole behaviour is a
 * state machine over scripted RPC answers. Same shape as [TorrentAdder], which is the
 * fake-injected surface `CrawlerAddHandler` already tests through.
 */
internal interface SelectionRpc {
    fun torrentGetFiles(hashString: String): TorrentFilesSnapshot?
    fun torrentSetFilesUnwanted(hashString: String, indices: List<Int>): String?
    fun torrentSetPaused(hashString: String, paused: Boolean): Boolean
}

/** Adapts the production [TransmissionRpcClient] onto [SelectionRpc]. */
internal class TransmissionRpcSelectionRpc(
    private val client: TransmissionRpcClient,
) : SelectionRpc {
    override fun torrentGetFiles(hashString: String): TorrentFilesSnapshot? =
        client.torrentGetFiles(hashString)

    override fun torrentSetFilesUnwanted(hashString: String, indices: List<Int>): String? =
        client.torrentSetFilesUnwanted(hashString, indices)

    override fun torrentSetPaused(hashString: String, paused: Boolean): Boolean =
        client.torrentSetPaused(hashString, paused)
}

/**
 * Watches subset adds until their file selection is applied (plan 12 stage 4).
 *
 * A magnet add is the only add mechanism, so `files-unwanted` can only be sent once
 * transmission reports a non-empty `files[]` — measured latency on device is minutes. The
 * watcher therefore polls, and when the metadata never arrives (or the selection cannot be
 * mapped onto the reported files, or transmission rejects the index list) it **pauses** the
 * torrent instead of letting a whole-torrent download run: never a silent full download.
 *
 * Crash safety: the intent is written to [PendingSelectionStore] before the watch starts and
 * [resumePending] re-arms every persisted intent at service start with its original
 * `addedAt`/`timeoutMs`, so a death mid-watch is recovered rather than forgotten. Every
 * terminal transition clears the intent — the pause itself persists in transmission.
 *
 * Threading: one daemon thread per watched hash (named [THREAD_NAME]); each runs the blocking
 * loop below, so a pending watch never delays another torrent's watch. [clock], [sleeper] and
 * [pollMs] are injectable, so tests drive the loop without sleeping.
 */
class SelectionWatcher internal constructor(
    private val rpcFactory: () -> SelectionRpc,
    private val store: PendingSelectionStore,
    private val clock: () -> Long,
    private val sleeper: (Long) -> Unit,
    private val pollMs: Long,
    private val pauseAttempts: Int,
) : SelectionRegistrar {

    /**
     * The production shape: a fresh [TransmissionRpcClient] per RPC call, so the watcher
     * picks up credential changes without being rebuilt.
     */
    constructor(
        rpcFactory: () -> TransmissionRpcClient,
        store: PendingSelectionStore,
        clock: () -> Long = System::currentTimeMillis,
        sleeper: (Long) -> Unit = { ms -> Thread.sleep(ms) },
        pollMs: Long = DEFAULT_POLL_MS,
    ) : this(
        rpcFactory = { TransmissionRpcSelectionRpc(rpcFactory()) },
        store = store,
        clock = clock,
        sleeper = sleeper,
        pollMs = pollMs,
        pauseAttempts = DEFAULT_PAUSE_ATTEMPTS,
    )

    private val statuses = ConcurrentHashMap<String, SelectionStatus>()

    /** Per-hash generation: a re-register bumps it, so the superseded loop stops silently. */
    private val generations = ConcurrentHashMap<String, AtomicInteger>()
    private val threads = ConcurrentHashMap<String, Thread>()

    @Volatile private var stopped = false

    /**
     * Schedules the watch for one freshly added torrent ([SelectionRegistrar]).
     *
     * The intent reaches the store before any polling starts; re-registering the same hash
     * supersedes the previous watch and its persisted entry. Never throws — the magnet add
     * has already succeeded by the time this runs.
     */
    override fun register(
        hashHex: String,
        name: String,
        unselectedPaths: Set<String>,
        timeoutMs: Long,
    ) {
        val generation = supersede(hashHex)
        val entry = PendingSelection(
            hashHex = hashHex,
            name = name,
            unselectedPaths = unselectedPaths.toList(),
            addedAt = clock(),
            timeoutMs = timeoutMs,
        )
        try {
            store.put(entry)
        } catch (e: Exception) {
            log("could not persist the selection for $hashHex: ${e.message}")
        }
        publishPending(entry)
        spawn(entry, generation)
    }

    /**
     * Re-arms every persisted intent (service start). The original `addedAt`/`timeoutMs`
     * are kept, so an intent whose deadline passed while the app was down pauses at once —
     * the crash-recovery fail-safe.
     */
    fun resumePending() {
        val entries = try {
            store.all()
        } catch (e: Exception) {
            log("could not read pending selections: ${e.message}")
            emptyList()
        }
        for (entry in entries) {
            val generation = supersede(entry.hashHex)
            publishPending(entry)
            spawn(entry, generation)
        }
    }

    /** Current status; an unwatched hash reports [SelectionStatus.NONE]. */
    fun status(hashHex: String): SelectionStatus = statuses[hashHex] ?: SelectionStatus.NONE

    /**
     * Drops the in-memory status for [hashHex], so the dashboard reports `none` again.
     *
     * The single semantic clear point is a **whole-torrent** add of the same hash: that add
     * supersedes any earlier subset-watch display, so a stale "File selection applied"
     * banner cannot survive it (the status map is otherwise never pruned on a timer).
     * Only [statuses] is touched — the persisted store is deliberately untouched, and a
     * still-running loop is left alone (it re-publishes its own state). Unknown hash: no-op.
     */
    fun clearStatus(hashHex: String) {
        statuses.remove(hashHex)
    }

    /** Stops every watch loop; idempotent. A stopped watcher starts no new loops. */
    fun stop() {
        stopped = true
        for (thread in threads.values) thread.interrupt()
        threads.clear()
    }

    /**
     * Re-arms the watcher after the graceful-stop teardown, the same way
     * `DbRestoreManager`/`Bep51BackupManager` are re-armed on Start: the
     * service INSTANCE survives a graceful stop, so a later Start reaches
     * `onStartCommand` without a second `onCreate` and the `stop()` above would
     * otherwise leave [resumePending] and every later `register` inert — a
     * subset add would then download the whole torrent, the one outcome the
     * fail-safe exists to prevent. Idempotent; [resumePending] re-arms the
     * persisted intents.
     */
    fun start() {
        stopped = false
    }

    // ---------------------------------------------------------------- watch loop

    private fun spawn(entry: PendingSelection, generation: Int) {
        if (stopped) return
        val thread = Thread({ runWatch(entry, generation) }, THREAD_NAME)
        thread.isDaemon = true
        threads[entry.hashHex] = thread
        thread.start()
    }

    private fun runWatch(entry: PendingSelection, generation: Int) {
        try {
            watch(entry, generation)
        } catch (e: Exception) {
            // A broken iteration must still end in a state the dashboard can show, and it must
            // attempt the pause first: the reachable trigger is stop() then start() around a loop
            // parked in the sleeper, where start() has already reset `stopped` and the generation
            // was not bumped, so isCurrent is true and dropping the intent would leave the whole
            // torrent downloading. When the watcher is genuinely stopped, pauseTorrent's
            // isCurrent guard makes this a no-op and the intent survives for resumePending().
            log("watch for ${entry.hashHex} failed: ${e.message}")
            finishAfterPause(
                entry, generation, SelectionStatus.STATE_ERROR,
                e.message ?: e.javaClass.simpleName,
            )
        } finally {
            threads.remove(entry.hashHex, Thread.currentThread())
        }
    }

    /**
     * One hash's poll loop. The metadata-ready signal is `files.length > 0` (measured: a
     * live magnet took minutes), so an empty list simply means "poll again".
     */
    private fun watch(entry: PendingSelection, generation: Int) {
        val hash = entry.hashHex
        val deadline = entry.addedAt + entry.timeoutMs
        val unselected = entry.unselectedPaths.toSet()
        while (isCurrent(hash, generation)) {
            if (clock() >= deadline) {
                finishAfterPause(
                    entry, generation, SelectionStatus.STATE_PAUSED_METADATA_TIMEOUT,
                    "metadata did not arrive within ${entry.timeoutMs} ms",
                )
                return
            }
            val snapshot = try {
                rpcFactory().torrentGetFiles(hash)
            } catch (e: Exception) {
                log("torrent-get files for $hash failed: ${e.message}")
                null
            }
            if (snapshot == null || snapshot.files.isEmpty()) {
                if (!isCurrent(hash, generation)) return
                sleeper(pollMs)
                continue
            }
            if (!SelectionPlanner.namesMatch(entry.name, snapshot.name)) {
                finishAfterPause(
                    entry, generation, SelectionStatus.STATE_PAUSED_MISMATCH,
                    "torrent name mismatch: expected \"${entry.name}\", got \"${snapshot.name}\"",
                )
                return
            }
            val match = SelectionPlanner.planUnwanted(entry.name, snapshot.files, unselected)
            if (match is SelectionPlanner.SelectionMatch.Mismatch) {
                finishAfterPause(
                    entry, generation, SelectionStatus.STATE_PAUSED_MISMATCH,
                    "paths not in the torrent: ${match.missingPaths.joinToString(", ")}",
                )
                return
            }
            val indices = (match as SelectionPlanner.SelectionMatch.Matched).unwantedIndices
            // A superseded loop must not apply its stale path list to the new intent.
            if (!isCurrent(hash, generation)) return
            val result = try {
                rpcFactory().torrentSetFilesUnwanted(hash, indices)
            } catch (e: Exception) {
                log("files-unwanted for $hash failed: ${e.message}")
                null
            }
            if (result == "success") {
                finish(entry, generation, SelectionStatus.STATE_APPLIED, "${indices.size} files marked unwanted")
                return
            }
            finishAfterPause(
                entry, generation, SelectionStatus.STATE_PAUSED_APPLY_FAILED,
                result ?: "no response to files-unwanted",
            )
            return
        }
    }

    /**
     * Pauses the torrent and records [state]; when the pause cannot be confirmed the state
     * becomes [SelectionStatus.STATE_ERROR] instead — a pause failure must be visible.
     */
    private fun finishAfterPause(
        entry: PendingSelection,
        generation: Int,
        state: String,
        detail: String,
    ) {
        if (!pauseTorrent(entry, generation)) {
            if (!isCurrent(entry.hashHex, generation)) return
            finish(
                entry, generation, SelectionStatus.STATE_ERROR,
                "$state, but the pause was not confirmed: $detail",
            )
            return
        }
        finish(entry, generation, state, detail)
    }

    /**
     * `torrent-set {"paused":true}` retried up to [pauseAttempts] times, [pollMs] apart:
     * transmission may still be booting after an app restart.
     */
    private fun pauseTorrent(entry: PendingSelection, generation: Int): Boolean {
        for (attempt in 1..pauseAttempts) {
            if (!isCurrent(entry.hashHex, generation)) return false
            val ok = try {
                rpcFactory().torrentSetPaused(entry.hashHex, true)
            } catch (e: Exception) {
                log("torrent-set paused for ${entry.hashHex} failed: ${e.message}")
                false
            }
            if (ok) return true
            if (attempt < pauseAttempts) sleeper(pollMs)
        }
        log("could not pause ${entry.hashHex} after $pauseAttempts attempts")
        return false
    }

    // ---------------------------------------------------------------- state helpers

    /**
     * Terminal transition: drops the persisted intent (the pause, when there was one,
     * already lives in transmission) and records [state].
     */
    private fun finish(entry: PendingSelection, generation: Int, state: String, detail: String) {
        if (!isCurrent(entry.hashHex, generation)) return
        try {
            store.remove(entry.hashHex)
        } catch (e: Exception) {
            log("could not clear the selection for ${entry.hashHex}: ${e.message}")
        }
        if (!isCurrent(entry.hashHex, generation)) return
        statuses[entry.hashHex] = SelectionStatus(
            state = state,
            detail = detail,
            unselectedCount = entry.unselectedPaths.size,
            addedAt = entry.addedAt,
            updatedAt = clock(),
        )
    }

    private fun publishPending(entry: PendingSelection) {
        statuses[entry.hashHex] = SelectionStatus(
            state = SelectionStatus.STATE_PENDING,
            detail = "",
            unselectedCount = entry.unselectedPaths.size,
            addedAt = entry.addedAt,
            updatedAt = clock(),
        )
    }

    /** Invalidates any running loop for [hashHex] and returns the new generation. */
    private fun supersede(hashHex: String): Int {
        val generation = generations.computeIfAbsent(hashHex) { AtomicInteger() }.incrementAndGet()
        threads.remove(hashHex)?.interrupt()
        return generation
    }

    private fun isCurrent(hashHex: String, generation: Int): Boolean =
        !stopped && generations[hashHex]?.get() == generation

    private fun log(msg: String) {
        Log.w(TAG, msg)
    }

    companion object {
        private const val TAG = "SelectionWatcher"
        private const val THREAD_NAME = "selection-watch"

        /** Poll interval for both the metadata wait and the pause retry spacing. */
        const val DEFAULT_POLL_MS = 5_000L

        /** ~10 min of retries at [DEFAULT_POLL_MS] while transmission boots. */
        const val DEFAULT_PAUSE_ATTEMPTS = 120
    }
}
