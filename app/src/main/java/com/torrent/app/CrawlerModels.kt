package com.torrent.app

import java.io.File

/**
 * Lifecycle state of the classifier-weights download (the crawler's own
 * `--fetch-models` one-shot). The weights are **not** shipped in the APK any
 * more, so a fresh install starts without them and this is what the dashboard
 * reports while they arrive.
 */
enum class ModelFetchState { IDLE, RUNNING, OK, FAILED }

/**
 * Observable status of the classifier-weights download.
 *
 * Honest by construction: the download itself happens inside the C crawler, so
 * this carries the state, the crawler's last progress line, the exit code and
 * the generation presence the app can see — never an invented percentage.
 *
 * Memory-only: an app restart resets it to [ModelFetchState.IDLE] (the next
 * crawler start re-runs the fetch, which is a no-op when the files are already
 * present and correct).
 */
data class ModelFetchStatus(
    val state: ModelFetchState = ModelFetchState.IDLE,
    val message: String = "",
    val startedAt: Long? = null,
    val finishedAt: Long? = null,
    /** True only while the one-shot is still running. */
    val running: Boolean = false,
    /** Exit code of the last completed run; null when it never completed. */
    val exitCode: Int? = null,
    /** Last progress line the crawler printed on stderr (truncated for display). */
    val lastLine: String = "",
    /**
     * Epoch millis of the last failed attempt. This doubles as the in-memory
     * retry gate ([CrawlerModels.shouldFetch]): a failure is not retried for at
     * least [CrawlerModels.FAILURE_RETRY_INTERVAL_MS].
     */
    val lastFailureAt: Long? = null,
    /** Generations whose pinned files are all on disk (`v15`, `v13`). */
    val presentGenerations: List<String> = emptyList(),
    /** Generations with at least one missing file. */
    val missingGenerations: List<String> = emptyList(),
)

/**
 * The single mutable holder of the weights download's [ModelFetchStatus].
 * Owned and wired by `DaemonService` (AGENTS.md invariant 12), which calls the
 * `mark*` methods around the one fetch it performs per crawler start and hands
 * [status] to `ControlServer` for the crawler card's models line.
 *
 * Plain Kotlin with no Android dependency (plain-JUnit testable); [clock] is
 * injectable so the timestamps are deterministic in tests. Every mutation is a
 * whole-snapshot [ModelFetchStatus.copy] under `@Volatile`.
 */
class ModelFetchStatusHolder(
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    @Volatile
    var status: ModelFetchStatus = ModelFetchStatus()
        private set

    /** Records the on-disk presence without claiming an attempt (the idle line). */
    fun notePresence(present: List<String>, missing: List<String>): ModelFetchStatus {
        val st = status.copy(presentGenerations = present, missingGenerations = missing)
        status = st
        return st
    }

    /** An attempt starts: RUNNING, with a fresh start stamp. */
    fun markRunning(present: List<String>, missing: List<String>): ModelFetchStatus {
        val st = status.copy(
            state = ModelFetchState.RUNNING,
            message = "downloading",
            running = true,
            startedAt = clock(),
            finishedAt = null,
            exitCode = null,
            lastLine = "",
            presentGenerations = present,
            missingGenerations = missing,
        )
        status = st
        return st
    }

    /** One crawler progress line. Ignored unless an attempt is running. */
    fun markProgress(line: String): ModelFetchStatus {
        val current = status
        if (!current.running) return current
        val st = current.copy(lastLine = line.trim())
        status = st
        return st
    }

    /** The one-shot exited 0: every generation is on disk and verified. */
    fun markOk(present: List<String>, missing: List<String>): ModelFetchStatus {
        val message = if (missing.isEmpty()) "downloaded" else "incomplete: " + missing.joinToString(",")
        val st = status.copy(
            state = ModelFetchState.OK,
            message = message,
            running = false,
            finishedAt = clock(),
            presentGenerations = present,
            missingGenerations = missing,
        )
        status = st
        return st
    }

    /**
     * The attempt failed (non-zero exit, launch failure or the hang guard):
     * terminal FAILED, and the attempt stamp doubles as the retry gate. The
     * crawler starts anyway — it runs with both engines inert, which is the
     * documented degraded mode.
     */
    fun markFailed(
        message: String,
        present: List<String>,
        missing: List<String>,
        exitCode: Int? = null,
    ): ModelFetchStatus {
        val now = clock()
        val st = status.copy(
            state = ModelFetchState.FAILED,
            message = message,
            running = false,
            finishedAt = now,
            exitCode = exitCode,
            lastFailureAt = now,
            presentGenerations = present,
            missingGenerations = missing,
        )
        status = st
        return st
    }
}

/**
 * The classifier weights the crawler loads at startup, and the pure rules the
 * app uses around the crawler's `--fetch-models` one-shot.
 *
 * The weights used to be staged from APK assets into `files/crawler/models/`;
 * they are now downloaded by the crawler itself (sha256-verified against each
 * generation's own `manifest.json`, skipping files that are already correct),
 * which is why nothing here ever writes to or deletes from `models/`.
 *
 * Pure (plain [File]s, no Android deps) so it is unit-testable on the host JVM.
 */
object CrawlerModels {
    /** The `--fetch-models` flag the crawler's one-shot mode is selected by. */
    const val FETCH_FLAG = "--fetch-models"

    /**
     * The generations the crawler resolves at startup — work-dir relative dir →
     * the files that generation's manifest pins. Order is the dashboard's.
     */
    val GENERATIONS: Map<String, List<String>> = linkedMapOf(
        CrawlerConfig.MODELS_SUBDIR to listOf("manifest.json", "cram.bin", "cram_vocab.bin"),
        CrawlerConfig.MOVIE_MODEL_SUBDIR to listOf(
            "manifest.json", "movie_kind_backbone_Q4_0.gguf", "movie_kind_head.bin"
        ),
    )

    /** A failed fetch is not retried for at least this long (1 hour). */
    const val FAILURE_RETRY_INTERVAL_MS = 60L * 60L * 1000L

    /**
     * Hang guard for the blocking fetch. The crawler verifies and skips what is
     * already on disk, so a warm start is seconds; a cold 490 MB download is
     * minutes. Past this bound the crawler starts anyway (engines inert) and the
     * download is left to finish in the background.
     */
    const val FETCH_TIMEOUT_MS = 12L * 60L * 1000L

    /** True when every file [GENERATIONS] pins for [subdir] is a non-empty file under [modelsRoot]. */
    fun isGenerationPresent(modelsRoot: File, subdir: String): Boolean {
        val files = GENERATIONS[subdir] ?: return false
        val dir = File(modelsRoot, subdir)
        return files.all { name ->
            val file = File(dir, name)
            file.isFile && file.length() > 0L
        }
    }

    /** Generation labels (`v15`, `v13`) whose pinned files are all present. */
    fun presentGenerations(modelsRoot: File): List<String> =
        GENERATIONS.keys.filter { isGenerationPresent(modelsRoot, it) }.map { generationLabel(it) }

    /** Generation labels with at least one missing/empty pinned file. */
    fun missingGenerations(modelsRoot: File): List<String> =
        GENERATIONS.keys.filterNot { isGenerationPresent(modelsRoot, it) }.map { generationLabel(it) }

    /** True when any generation is incomplete — the crawler would run with an engine inert. */
    fun needsFetch(modelsRoot: File): Boolean =
        GENERATIONS.keys.any { !isGenerationPresent(modelsRoot, it) }

    /**
     * The retry gate: attempt the fetch unless the previous attempt failed less
     * than [FAILURE_RETRY_INTERVAL_MS] ago. A present, verified model set is
     * *not* a reason to skip — the crawler's fetch is documented to be cheap when
     * everything is already there, and running it re-verifies the shas, which is
     * exactly the "manifest sha does not match" repair path.
     */
    fun shouldFetch(lastFailureAt: Long?, now: Long): Boolean =
        lastFailureAt == null || now - lastFailureAt >= FAILURE_RETRY_INTERVAL_MS

    /** `models/v15` → `v15`. */
    fun generationLabel(subdir: String): String = File(subdir).name

    /**
     * The dashboard's models line. Always a sentence: the crawler card renders
     * it verbatim, so it must never be empty.
     */
    fun statusLine(status: ModelFetchStatus?): String {
        val st = status ?: return "not fetched yet"
        val present = st.presentGenerations.joinToString(" + ")
        val missing = st.missingGenerations.joinToString(", ")
        return when (st.state) {
            ModelFetchState.RUNNING -> {
                val target = if (missing.isEmpty()) present else missing
                val detail = st.lastLine.take(80)
                "downloading " + target + " — " + (if (detail.isEmpty()) "fetching" else detail)
            }
            ModelFetchState.OK -> {
                if (st.missingGenerations.isEmpty()) present + " present"
                else "incomplete (" + missing + ") — retry on next start"
            }
            ModelFetchState.FAILED ->
                "download failed: " + st.message + " — engines inert, retry on next start"
            ModelFetchState.IDLE -> {
                when {
                    st.missingGenerations.isEmpty() && st.presentGenerations.isNotEmpty() ->
                        present + " present"
                    st.missingGenerations.isEmpty() -> "not fetched yet"
                    else -> "missing " + missing + " — fetched on the next crawler start"
                }
            }
        }
    }
}
