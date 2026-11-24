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
        val stopCalls = CopyOnWriteArrayList<String>()
        val statusCalls = AtomicInteger()

        @Volatile var sticky: TorrentFilesSnapshot? = TorrentFilesSnapshot("", emptyList())
        @Volatile var unwantedResult: String? = "success"

        /** `torrent-stop`'s result. True is the shipped daemon's answer — the effect is unproven. */
        @Volatile var stopResult = true

        /**
         * What the read-back `torrent-get status` reports: 0 is TR_STATUS_STOPPED (a confirmed
         * stop), 4 is TR_STATUS_DOWNLOAD (the lying-daemon shape this fail-safe must catch), and
         * null is an absent torrent or a failed RPC.
         */
        @Volatile var statusAfterStop: Int? = 0

        /** Runs inside `torrent-stop`, so a test can observe the state at stop time. */
        @Volatile var onStop: (() -> Unit)? = null

        /** Runs inside the read-back, so a test can observe the state at confirmation time. */
        @Volatile var onStatus: (() -> Unit)? = null

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

        override fun torrentStop(hashString: String): Boolean {
            stopCalls.add(hashString)
            onStop?.invoke()
            return stopResult
        }

        override fun torrentGetStatus(hashString: String): Int? {
            statusCalls.incrementAndGet()
            onStatus?.invoke()
            return statusAfterStop
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

    /**
     * Runs [body] with a capturing default uncaught-exception handler installed, so anything a
     * watch thread lets escape is observed instead of killing the test JVM silently.
     */
    private fun withUncaughtCapture(body: () -> Unit): List<Throwable> {
        val captured = CopyOnWriteArrayList<Throwable>()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, e -> captured.add(e) }
        try {
            body()
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(previous)
        }
        return captured.toList()
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
        assertTrue(rpc.stopCalls.isEmpty())
    }

    @Test fun timeout_pausesTheTorrentAndReportsMetadataTimeout() {
        val rpc = FakeRpc() // sticky: a metadata-less snapshot, files[] empty forever
        val w = watcher(rpc)

        w.register(hash, name, setOf("b.mkv"), 15_000L)

        val status = awaitState(w, hash, SelectionStatus.STATE_PAUSED_METADATA_TIMEOUT)
        assertEquals(listOf(hash), rpc.stopCalls.toList())
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
        assertEquals(listOf(hash), rpc.stopCalls.toList())
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
        assertEquals(listOf(hash), rpc.stopCalls.toList())
        assertEquals(emptyList<PendingSelection>(), store.all())
    }

    @Test fun pauseIsRetriedWhileTheRpcIsDownThenReportedAsError() {
        val rpc = FakeRpc()
        rpc.stopResult = false
        val w = watcher(rpc, pauseAttempts = 3)

        w.register(hash, name, setOf("b.mkv"), 15_000L)

        val status = awaitState(w, hash, SelectionStatus.STATE_ERROR)
        assertEquals(3, rpc.stopCalls.size)
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
        assertEquals(listOf(hash), rpc.stopCalls.toList())
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
        rpc.onStop = { storeSizesAtPause.add(store.all().size) }
        val w = watcher(rpc, sleeper = { throw IllegalStateException("sleeper exploded") })

        w.register(hash, name, setOf("b.mkv"), 600_000L)

        val status = awaitState(w, hash, SelectionStatus.STATE_ERROR)
        assertEquals("sleeper exploded", status.detail)
        assertEquals(listOf(hash), rpc.stopCalls.toList())
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

        assertTrue(rpc.stopCalls.isEmpty())
        assertEquals(1, store.all().size)
        assertEquals(SelectionStatus.STATE_PENDING, w.status(hash).state)
    }

    @Test fun interruptInThePauseRetrySleep_isSwallowedWhileCurrent() {
        // An already-expired deadline reaches pauseTorrent without any polling sleep, so the
        // only sleeper call here is the pause retry. Before the tolerant sleep this interrupt
        // escaped runWatch's catch body (an exception thrown inside a catch is not caught by
        // it) and died uncaught on the daemon thread — a process kill on Android.
        val rpc = FakeRpc()
        rpc.stopResult = false
        val w = watcher(rpc, sleeper = { throw InterruptedException("pause retry interrupted") })

        val uncaught = withUncaughtCapture {
            w.register(hash, name, setOf("b.mkv"), 0L)

            val status = awaitState(w, hash, SelectionStatus.STATE_ERROR)
            assertTrue(status.detail.contains("pause was not confirmed"))
            assertEquals(listOf(hash), rpc.stopCalls.toList())
            assertEquals(emptyList<PendingSelection>(), store.all())
        }
        assertEquals(emptyList<Throwable>(), uncaught)
    }

    @Test fun interruptInThePauseRetrySleepAfterStop_keepsThePersistedIntent() {
        val rpc = FakeRpc() // metadata-less sticky snapshot: the loop parks in the sleeper
        rpc.stopResult = false
        val sleepThreads = CopyOnWriteArrayList<Thread>()
        val w = watcher(
            rpc,
            sleeper = {
                sleepThreads.add(Thread.currentThread())
                throw InterruptedException("interrupted")
            },
        )
        // The single pause attempt runs while the watcher is still current and stops it; the
        // retry sleep then fails with the watcher genuinely stopped, so the failed attempt must
        // stay the only pause call and the intent must survive for resumePending().
        rpc.onStop = { watcher!!.stop() }

        val uncaught = withUncaughtCapture {
            w.register(hash, name, setOf("b.mkv"), 600_000L)

            val end = System.nanoTime() + 5_000L * 1_000_000
            while (sleepThreads.size < 2 && System.nanoTime() < end) Thread.sleep(1)
            sleepThreads.first().join(5_000L)

            assertEquals(listOf(hash), rpc.stopCalls.toList())
            assertEquals(1, store.all().size)
            assertEquals(SelectionStatus.STATE_PENDING, w.status(hash).state)
        }
        assertEquals(emptyList<Throwable>(), uncaught)
    }

    @Test fun stopConfirmedByReadBack_recordsThePausedStateOnlyAfterStatusIsZero() {
        // `success` is not the confirmation: the paused_* state is recorded only once the scoped
        // read-back reports TR_STATUS_STOPPED, and the intent is still persisted at that moment.
        val rpc = FakeRpc()
        rpc.statusAfterStop = 0
        val storeSizesAtReadBack = CopyOnWriteArrayList<Int>()
        rpc.onStatus = { storeSizesAtReadBack.add(store.all().size) }
        val w = watcher(rpc)

        w.register(hash, name, setOf("b.mkv"), 0L)

        val status = awaitState(w, hash, SelectionStatus.STATE_PAUSED_METADATA_TIMEOUT)
        assertEquals(listOf(hash), rpc.stopCalls.toList())
        assertEquals(1, rpc.statusCalls.get())
        assertEquals(listOf(1), storeSizesAtReadBack.toList())
        assertEquals("metadata did not arrive within 0 ms", status.detail)
        assertEquals(emptyList<PendingSelection>(), store.all())
    }

    @Test fun stopReportsSuccessButNeverStops_landsInTheVisibleErrorState() {
        // The device-measured failure mode: the daemon answers success while the torrent keeps
        // downloading. Every retry is consumed and the terminal state is `error` with the
        // not-confirmed detail — never a paused_* state over a running torrent.
        val rpc = FakeRpc()
        rpc.stopResult = true
        rpc.statusAfterStop = 4 // TR_STATUS_DOWNLOAD: not stopped.
        val w = watcher(rpc, pauseAttempts = 3)

        w.register(hash, name, setOf("b.mkv"), 0L)

        val status = awaitState(w, hash, SelectionStatus.STATE_ERROR)
        assertEquals(3, rpc.stopCalls.size)
        assertEquals(3, rpc.statusCalls.get())
        assertTrue(status.detail.contains("pause was not confirmed"))
        assertTrue(status.detail.contains(SelectionStatus.STATE_PAUSED_METADATA_TIMEOUT))
        assertEquals(emptyList<PendingSelection>(), store.all())
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
