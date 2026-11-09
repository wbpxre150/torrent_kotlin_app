package com.torrent.app

/**
 * One parsed `[MPROG]` maintenance progress line.
 *
 * The crawler emits its machine-readable progress through
 * `log_msg(LOG_INFO, ...)` — the only channel that flushes per line — so the
 * `[MPROG]` token sits mid-line, behind `log_msg`'s `[YYYY-MM-DD HH:MM:SS]
 * [LEVEL] ` prefix:
 *
 * ```
 * [MPROG] action=<route> phase=<slug> [done=<n> total=<n> pct=<n>] [k=v ...]
 * ```
 *
 * The wire rules this class mirrors (fixed by the crawler-side patch):
 *  - [action] is the flag name without `--`, matching [MaintenanceAction.route];
 *  - [done]/[total]/[percent] are omitted when unknown, and [percent] is
 *    omitted entirely when there is no denominator (never `pct=-1`) — a
 *    phase-only action reports a phase and nothing else;
 *  - the last line an action emits is `phase=done rc=<n>` or
 *    `phase=failed rc=<n>`.
 *
 * [extras] keeps every other `k=v` pair (`rc=0` on a terminal line, `removed=1`
 * for the failure-filter purge, …) so action-specific detail survives without
 * widening this class.
 *
 * Pure Kotlin, no Android imports: it is parsed on the one-shot's line callback
 * ([CrawlerMaintenance.runAction]) and unit-tested as plain JUnit.
 */
data class MaintenanceProgress(
    val action: String,
    val phase: String,
    val done: Long? = null,
    val total: Long? = null,
    val percent: Int? = null,
    val extras: Map<String, String> = emptyMap(),
) {
    companion object {
        /** Literal token every progress line carries. */
        const val TOKEN = "[MPROG]"

        /** Terminal phase of a successful action (always paired with `rc`). */
        const val PHASE_DONE = "done"

        /** Terminal phase of a failed action (always paired with `rc`). */
        const val PHASE_FAILED = "failed"
    }
}

/** The `[MPROG]` token plus its whitespace-separated `k=v` tail. */
private val PROGRESS_LINE = Regex("""\[MPROG]\s+(.*)$""")

/** Field separator: any run of whitespace (the emitter uses single spaces). */
private val FIELD_SEPARATOR = Regex("""\s+""")

/** Keys with a dedicated field on [MaintenanceProgress]. */
private val KNOWN_KEYS = setOf("action", "phase", "done", "total", "pct")

/**
 * Parses one output line into a [MaintenanceProgress], or null when the line is
 * not a progress line (or is too damaged to be one).
 *
 * Tolerant by design — the line comes from a child process that can be killed
 * mid-run, so nothing here may throw: an unknown `k=v` key, a bare word, a
 * non-numeric count or an absent `pct` all degrade instead of failing. Only
 * `action` and `phase` are required, because a line without them carries no
 * displayable progress.
 */
fun parseMaintenanceProgress(line: String): MaintenanceProgress? {
    val tail = PROGRESS_LINE.find(line.trim())?.groupValues?.get(1) ?: return null
    val fields = LinkedHashMap<String, String>()
    for (token in tail.split(FIELD_SEPARATOR)) {
        if (token.isEmpty()) continue
        val eq = token.indexOf('=')
        if (eq <= 0) continue // a bare word, or `=value`: tolerated, ignored
        fields[token.substring(0, eq)] = token.substring(eq + 1)
    }
    val action = fields["action"]?.takeIf { it.isNotBlank() } ?: return null
    val phase = fields["phase"]?.takeIf { it.isNotBlank() } ?: return null
    val done = parseCount(fields["done"])
    val total = parseCount(fields["total"])
    // A done beyond the total means the denominator went stale (rows were added
    // mid-run): keep done and drop the denominator rather than report a percent
    // that cannot be right.
    val usableTotal = total?.takeIf { done == null || done <= it }
    // Percent exists only where a denominator does: prefer the emitter's own
    // `pct` (clamped), else derive it from done/total, else stay phase-only.
    val percent = if (usableTotal == null) {
        null
    } else {
        parsePercent(fields["pct"])
            ?: done?.let { ((it * 100) / usableTotal).coerceIn(0, 100).toInt() }
    }
    return MaintenanceProgress(
        action = action,
        phase = phase,
        done = done,
        total = usableTotal,
        percent = percent,
        extras = fields.filterKeys { it !in KNOWN_KEYS },
    )
}

/** A non-negative count, or null when absent/unparseable/negative. */
private fun parseCount(raw: String?): Long? = raw?.toLongOrNull()?.takeIf { it >= 0 }

/** A percentage clamped to 0-100, or null when absent/unparseable. */
private fun parsePercent(raw: String?): Int? = raw?.toLongOrNull()?.coerceIn(0, 100)?.toInt()
