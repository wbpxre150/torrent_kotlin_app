package com.torrent.app.transmission

import com.torrent.app.SelectionRegistrar
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Plain-JVM tests for [SelectionWatcher]: a scripted [SelectionRpc], an in-memory
 * [PendingSelectionStore] and a fake clock/sleeper, so no test ever waits for a real poll
 * interval. The watch loops run on their own daemon threads (the production topology), and
 * every assertion waits for a terminal status with a bounded [awaitState].
 */
class SelectionWatcherTest {

    private val hash = "307e4f912af34aa46e1b76ec9a2a23b94345e2fe"
    private val name = "VideoHive - Slideshow 40426237"
    private val now = AtomicLong(1_700_000_000_000L)
    private val store = InMemoryStore()
    private var watcher: SelectionWatcher? = null

    @After fun tearDown() {
        watcher?.stop()
    }

    // ------------------------------------------------------------------ fakes

    /** In-memory [PendingSelectionStore] (the plan's test-source impl). */
    private class InMemoryStore : PendingSelectionStore {
        private val entries = LinkedHashMap<String, PendingSelection>()

        @Synchronized override fun all(): List<PendingSelection> = entries.values.toList()
        @Synchronized override fun put(entry: PendingSelection) { entries[entry.hashHex] = entry }
        @Synchronized override fun remove(hashHex: String) { entries.remove(hashHex) }
    }

    /** Per-hash scripted `files[]` answers; an exhausted script falls back to [sticky]. */
    private class Script {
        private val queue = ArrayDeque<TorrentFilesSnapshot?>()
        @Synchronized fun reset(items: List<TorrentFilesSnapshot?>) {
            queue.clear()
            queue.addAll(items)
        }
        @Synchronized fun next(fallback: TorrentFilesSnapshot?): TorrentFilesSnapshot? =
            if (queue.isEmpty()) fallback else queue.removeFirst()
    }

    private class FakeRpc : SelectionRpc {
        private val scripts = ConcurrentHashMap<String, Script>()
        val getCalls = AtomicInteger()
        val unwantedCalls = CopyOnWriteArrayList<Pair<String, List<Int>>>()
        val pauseCalls = CopyOnWriteArrayList<Pair<String, Boolean>>()

        @Volatile var sticky: TorrentFilesSnapshot? = TorrentFilesSnapshot("", emptyList())
        @Volatile var unwantedResult: String? = "success"
        @Volatile var pauseResult = true

        /** Runs inside `torrent-set paused`, so a test can observe the state at pause time. */
        @Volatile var onPause: (() -> Unit)? = null

        /** When set, `torrent-get` parks until it is released (deterministic supersede). */
        @Volatile var gate: CountDownLatch? = null

        fun script(hash: String, vararg snapshots: TorrentFilesSnapshot?) {
            scripts.getOrPut(hash) { Script() }.reset(snapshots.toList())
        }

        override fun torrentGetFiles(hashString: String): TorrentFilesSnapshot? {
            getCalls.incrementAndGet()
            gate?.await()
            return scripts[hashString]?.next(sticky) ?: sticky
        }

        override fun torrentSetFilesUnwanted(hashString: String, indices: List<Int>): String? {
            unwantedCalls.add(hashString to indices)
            return unwantedResult
        }

        override fun torrentSetPaused(hashString: String, paused: Boolean): Boolean {
            pauseCalls.add(hashString to paused)
            onPause?.invoke()
            return pauseResult
        }
    }

    // ------------------------------------------------------------------ helpers

    private fun watcher(
        rpc: FakeRpc,
        pollMs: Long = 5_000L,
        pauseAttempts: Int = 3,
        sleeper: (Long) -> Unit = { ms -> now.addAndGet(ms) },
    ): SelectionWatcher = SelectionWatcher(
        rpcFactory = { rpc },
        store = store,
        clock = { now.get() },
        sleeper = sleeper,
        pollMs = pollMs,
        pauseAttempts = pauseAttempts,
    ).also { watcher = it }

    private fun snapshot(vararg files: String): TorrentFilesSnapshot =
        TorrentFilesSnapshot(name, files.mapIndexed { i, path -> TorrentFileEntry("$name/$path", 1_000L + i, 0L) })

    /** Waits for one of [states], failing the test after [timeoutMs] of real time. */
    private fun awaitState(
        w: SelectionWatcher,
        target: String,
        vararg states: String,
        timeoutMs: Long = 5_000L,
    ): SelectionStatus {
        val end = System.nanoTime() + timeoutMs * 1_000_000
        while (System.nanoTime() < end) {
            val status = w.status(target)
            if (status.state in states) return status
            Thread.sleep(1)
        }
        throw AssertionError(
            "state stayed '${w.status(target).state}', expected one of ${states.toList()}",
        )
    }

    private fun awaitCall(rpc: FakeRpc) {
        val end = System.nanoTime() + 5_000L * 1_000_000
        while (System.nanoTime() < end) {
            if (rpc.getCalls.get() > 0) return
            Thread.sleep(1)
        }
        throw AssertionError("the watcher never polled torrent-get")
    }

    // ------------------------------------------------------------------ tests

    @Test fun applied_sendsThePlannerIndicesAndClearsTheIntent() {
        val rpc = FakeRpc()
        rpc.sticky = snapshot("a.mkv", "b.mkv", "c.mkv")
        val w = watcher(rpc)

        w.register(hash, name, setOf("b.mkv"), 600_000L)

        val status = awaitState(w, hash, SelectionStatus.STATE_APPLIED)
        assertEquals(listOf(hash to listOf(1)), rpc.unwantedCalls.toList())
        assertEquals(emptyList<PendingSelection>(), store.all())
        assertEquals(1, status.unselectedCount)
        assertEquals(now.get(), status.updatedAt)
        assertTrue(rpc.pauseCalls.isEmpty())
    }

    @Test fun timeout_pausesTheTorrentAndReportsMetadataTimeout() {
        val rpc = FakeRpc() // sticky: a metadata-less snapshot, files[] empty forever
        val w = watcher(rpc)

        w.register(hash, name, setOf("b.mkv"), 15_000L)

        val status = awaitState(w, hash, SelectionStatus.STATE_PAUSED_METADATA_TIMEOUT)
        assertEquals(listOf(hash to true), rpc.pauseCalls.toList())
        assertEquals(emptyList<PendingSelection>(), store.all())
        assertTrue(status.detail.contains("15000"))
        assertTrue(rpc.unwantedCalls.isEmpty())
    }

    @Test fun missingPath_pausesTheTorrentAndNamesThePaths() {
        val rpc = FakeRpc()
        rpc.sticky = snapshot("a.mkv")
        val w = watcher(rpc)

        w.register(hash, name, setOf("gone.mkv"), 600_000L)

        val status = awaitState(w, hash, SelectionStatus.STATE_PAUSED_MISMATCH)
        assertTrue(status.detail.contains("gone.mkv"))
        assertEquals(listOf(hash to true), rpc.pauseCalls.toList())
        assertTrue(rpc.unwantedCalls.isEmpty())
        assertEquals(emptyList<PendingSelection>(), store.all())
    }

    @Test fun divergingTorrentName_pausesAsMismatch() {
        val rpc = FakeRpc()
        rpc.sticky = TorrentFilesSnapshot("Some Other Name", listOf(TorrentFileEntry("a.mkv", 1L, 0L)))
        val w = watcher(rpc)

        w.register(hash, name, setOf("a.mkv"), 600_000L)

        val status = awaitState(w, hash, SelectionStatus.STATE_PAUSED_MISMATCH)
        assertTrue(status.detail.contains("Some Other Name"))
        assertTrue(rpc.unwantedCalls.isEmpty())
    }

    @Test fun rejectedIndexList_pausesTheTorrentAsApplyFailed() {
        val rpc = FakeRpc()
        rpc.sticky = snapshot("a.mkv", "b.mkv")
        rpc.unwantedResult = "file index out of range"
        val w = watcher(rpc)

        w.register(hash, name, setOf("b.mkv"), 600_000L)

        val status = awaitState(w, hash, SelectionStatus.STATE_PAUSED_APPLY_FAILED)
        assertTrue(status.detail.contains("file index out of range"))
        assertEquals(listOf(hash to true), rpc.pauseCalls.toList())
        assertEquals(emptyList<PendingSelection>(), store.all())
    }

    @Test fun pauseIsRetriedWhileTheRpcIsDownThenReportedAsError() {
        val rpc = FakeRpc()
        rpc.pauseResult = false
        val w = watcher(rpc, pauseAttempts = 3)

        w.register(hash, name, setOf("b.mkv"), 15_000L)

        val status = awaitState(w, hash, SelectionStatus.STATE_ERROR)
        assertEquals(3, rpc.pauseCalls.size)
        assertTrue(status.detail.contains("not confirmed"))
        assertEquals(emptyList<PendingSelection>(), store.all())
    }

    @Test fun resume_withAnExpiredDeadlinePausesWithoutPolling() {
        store.put(
            PendingSelection(
                hashHex = hash,
                name = name,
                unselectedPaths = listOf("b.mkv"),
                addedAt = now.get() - 1_000_000L,
                timeoutMs = 1_000L,
            ),
        )
        val rpc = FakeRpc()
        val w = watcher(rpc)

        w.resumePending()

        val status = awaitState(w, hash, SelectionStatus.STATE_PAUSED_METADATA_TIMEOUT)
        assertEquals(0, rpc.getCalls.get())
        assertEquals(listOf(hash to true), rpc.pauseCalls.toList())
        assertEquals(emptyList<PendingSelection>(), store.all())
        assertEquals(now.get() - 1_000_000L, status.addedAt)
    }

    @Test fun resume_appliesAPersistedIntentThatStillHasTime() {
        store.put(
            PendingSelection(hash, name, listOf("b.mkv"), addedAt = now.get(), timeoutMs = 600_000L),
        )
        val rpc = FakeRpc()
        rpc.sticky = snapshot("a.mkv", "b.mkv")
        val w = watcher(rpc)

        w.resumePending()

        awaitState(w, hash, SelectionStatus.STATE_APPLIED)
        assertEquals(listOf(hash to listOf(1)), rpc.unwantedCalls.toList())
        assertEquals(emptyList<PendingSelection>(), store.all())
    }

    @Test fun register_persistsTheIntentBeforeTheFirstPoll() {
        val rpc = FakeRpc()
        rpc.gate = CountDownLatch(1)
        val w = watcher(rpc)

        w.register(hash, name, setOf("a.mkv", "b.mkv"), 600_000L)

        awaitCall(rpc)
        val entry = store.all().single()
        assertEquals(hash, entry.hashHex)
        assertEquals(name, entry.name)
        assertEquals(setOf("a.mkv", "b.mkv"), entry.unselectedPaths.toSet())
        assertEquals(600_000L, entry.timeoutMs)
        assertEquals(SelectionStatus.STATE_PENDING, w.status(hash).state)
        rpc.gate?.countDown()
    }

    @Test fun duplicateRegister_supersedesThePreviousWatch() {
        val rpc = FakeRpc()
        rpc.gate = CountDownLatch(1)
        val w = watcher(rpc)
        w.register(hash, name, setOf("a.mkv"), 600_000L)
        awaitCall(rpc)

        // Re-register while the first loop is parked inside torrent-get: the first loop must
        // neither apply its stale path list nor clear the new intent.
        rpc.sticky = snapshot("a.mkv", "b.mkv")
        w.register(hash, name, setOf("b.mkv"), 600_000L)
        rpc.gate?.countDown()

        val status = awaitState(w, hash, SelectionStatus.STATE_APPLIED)
        assertEquals(SelectionStatus.STATE_APPLIED, status.state)
        assertEquals(listOf(hash to listOf(1)), rpc.unwantedCalls.toList())
        assertEquals(emptyList<PendingSelection>(), store.all())
    }

    @Test fun stop_keepsThePersistedIntentForTheNextStart() {
        val rpc = FakeRpc()
        rpc.gate = CountDownLatch(1)
        val w = watcher(rpc)
        w.register(hash, name, setOf("a.mkv"), 600_000L)
        awaitCall(rpc)

        w.stop()
        rpc.gate?.countDown()

        assertEquals(1, store.all().size)
        assertEquals(SelectionStatus.STATE_PENDING, w.status(hash).state)
        assertTrue(rpc.unwantedCalls.isEmpty())
    }

    @Test fun exceptionWhileCurrent_attemptsThePauseBeforeDroppingTheIntent() {
        // The reachable trigger: an exception escapes a live loop (the stop()/start() race) while
        // the generation is unchanged, so the intent must be dropped only after a pause.
        val rpc = FakeRpc() // sticky metadata-less snapshot: the loop parks in the sleeper
        val storeSizesAtPause = CopyOnWriteArrayList<Int>()
        rpc.onPause = { storeSizesAtPause.add(store.all().size) }
        val w = watcher(rpc, sleeper = { throw IllegalStateException("sleeper exploded") })

        w.register(hash, name, setOf("b.mkv"), 600_000L)

        val status = awaitState(w, hash, SelectionStatus.STATE_ERROR)
        assertEquals("sleeper exploded", status.detail)
        assertEquals(listOf(hash to true), rpc.pauseCalls.toList())
        assertEquals(listOf(1), storeSizesAtPause.toList())
        assertEquals(emptyList<PendingSelection>(), store.all())
    }

    @Test fun exceptionAfterStop_pausesNothingAndKeepsThePersistedIntent() {
        val rpc = FakeRpc()
        val watchThreads = CopyOnWriteArrayList<Thread>()
        val w = watcher(
            rpc,
            sleeper = {
                // Genuinely stopped before the throw: pauseTorrent's isCurrent guard must make
                // the pause a no-op, so the intent survives for resumePending().
                watchThreads.add(Thread.currentThread())
                watcher!!.stop()
                throw IllegalStateException("interrupted")
            },
        )

        w.register(hash, name, setOf("b.mkv"), 600_000L)

        val end = System.nanoTime() + 5_000L * 1_000_000
        while (watchThreads.isEmpty() && System.nanoTime() < end) Thread.sleep(1)
        watchThreads.single().join(5_000L)

        assertTrue(rpc.pauseCalls.isEmpty())
        assertEquals(1, store.all().size)
        assertEquals(SelectionStatus.STATE_PENDING, w.status(hash).state)
    }

    @Test fun start_reArmsTheWatcherAfterAGracefulStop() {
        val rpc = FakeRpc()
        rpc.sticky = snapshot("a.mkv", "b.mkv")
        val w = watcher(rpc)

        // A graceful stop/start cycle keeps the service instance, so only start() can make a
        // later register live; without it the subset add would download the whole torrent.
        w.stop()
        w.start()
        w.register(hash, name, setOf("b.mkv"), 600_000L)

        awaitState(w, hash, SelectionStatus.STATE_APPLIED)
        assertEquals(listOf(hash to listOf(1)), rpc.unwantedCalls.toList())
        assertEquals(emptyList<PendingSelection>(), store.all())
    }

    @Test fun status_ofAnUnwatchedHashIsNone() {
        val status = watcher(FakeRpc()).status(hash)
        assertEquals(SelectionStatus.STATE_NONE, status.state)
        assertEquals(SelectionStatus.NONE, status)
    }

    @Test fun clearStatus_dropsTheDisplayButNeverThePersistedIntent() {
        val rpc = FakeRpc()
        rpc.sticky = snapshot("a.mkv", "b.mkv")
        rpc.gate = CountDownLatch(1)
        val w = watcher(rpc)

        w.register(hash, name, setOf("b.mkv"), 600_000L)
        awaitCall(rpc)

        // The clear is a display-only operation: a persisted intent is not its business,
        // and the parked loop is left to re-publish its own state.
        w.clearStatus(hash)
        assertEquals(SelectionStatus.STATE_NONE, w.status(hash).state)
        assertEquals(1, store.all().size)

        // The criterion-5 shape: once a subset add of this hash has reported `applied`, a
        // whole-torrent re-add in the SAME session must be able to report `none` — the
        // status map is never pruned on a timer, so this route call is the only clear.
        rpc.gate?.countDown()
        awaitState(w, hash, SelectionStatus.STATE_APPLIED)
        w.clearStatus(hash)
        assertEquals(SelectionStatus.NONE, w.status(hash))
        assertEquals(emptyList<PendingSelection>(), store.all())
    }

    @Test fun watcherServesAsTheAddHandlersSelectionRegistrar() {
        val rpc = FakeRpc()
        rpc.sticky = snapshot("a.mkv", "b.mkv")
        val w = watcher(rpc)
        val registrar: SelectionRegistrar = w

        registrar.register(hash, name, setOf("a.mkv"), 600_000L)

        awaitState(w, hash, SelectionStatus.STATE_APPLIED)
        assertEquals(listOf(hash to listOf(0)), rpc.unwantedCalls.toList())
    }
}
