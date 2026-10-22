package com.torrent.app.backup

import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.util.zip.GZIPInputStream

/**
 * Category the crawler assigns to a torrent it could not classify
 * (`category_filter_cleanup.c`: "An unclassified item carries category 0
 * (unknown)"). A torrent with no `torrent_class` row — or a range whose class
 * index has no line for it — is therefore judged as [UNKNOWN_CATEGORY], which
 * is exactly how the crawler's own ingest treats it.
 */
internal const val UNKNOWN_CATEGORY: Int = 0

private const val INSERT_KEYWORD = "insert"
private const val INTO_KEYWORD = "into"
private const val VALUES_KEYWORD = "values"

/**
 * The key column (0-based index into a statement's `VALUES(...)` list) that
 * carries the TORRENT ID of each dumped table, in the dump's declared column
 * order (see [CrawlerDbSchema.tableStatements]):
 * - `torrents`: #0 = `id` (`CrawlerDbSchema.kt:37-46`);
 * - `torrent_files`: #1 = `torrent_id` (`CrawlerDbSchema.kt:52-62`);
 * - `torrent_class`: #0 = `torrent_id` PRIMARY KEY (`CrawlerDbSchema.kt:63-77`).
 *
 * Tables absent from this map (`path_prefixes`, anything unknown) are ALWAYS
 * kept — see [RestoreCategoryFilter.keep].
 */
private val ID_COLUMN: Map<String, Int> = mapOf(
    TABLE_TORRENTS to 0,
    TABLE_TORRENT_FILES to 1,
    TABLE_TORRENT_CLASS to 0,
)

/**
 * Parses the decompressed content of a dump range's `.idx.gz` class-index
 * sidecar into `torrent_id → category`.
 *
 * The sidecar is one `"<torrent_id> <category>"` line per classified torrent
 * (the inverse of [renderClassIndex]), gzip-compressed exactly like the SQL
 * chunk itself. The text is streamed line by line through
 * [GZIPInputStream] so a range's index is never materialised as one String —
 * only the resulting map is held, which is the minimum the filter needs.
 *
 * Blank lines are skipped (a trailing newline, or an empty index which renders
 * as the empty string: "this range is fully covered, nothing classified").
 * Every other line MUST be two decimal tokens; anything else throws
 * [IllegalArgumentException] quoting the offending line, so the restore fails
 * cleanly instead of silently importing an unfiltered or wrongly filtered
 * range. The caller maps that to a FAILED restore exactly like the existing
 * "corrupt gzip" handling (`DbRestoreManager.kt:422-425`).
 *
 * A corrupt gzip stream surfaces as the [java.util.zip.ZipException] /
 * `IOException` thrown by [GZIPInputStream] itself — deliberately not wrapped,
 * so the caller's existing corrupt-chunk handling covers this object too.
 *
 * No Android imports — plain JUnit4 testable.
 *
 * @throws IllegalArgumentException on a malformed (non-blank) line.
 * @throws java.io.IOException on a corrupt gzip stream.
 */
internal fun parseClassIndex(gzBytes: ByteArray): Map<Long, Int> {
    val index = HashMap<Long, Int>()
    GZIPInputStream(ByteArrayInputStream(gzBytes)).use { gz ->
        val lines = BufferedReader(InputStreamReader(gz, StandardCharsets.UTF_8))
        while (true) {
            val line = lines.readLine() ?: break
            if (line.isBlank()) continue
            val parts = line.trim().split(WHITESPACE_RUN)
            val id = parts.getOrNull(0)?.let { parseDecimalLong(it) }
            val category = if (parts.size == 2) parseDecimalInt(parts[1]) else null
            if (parts.size != 2 || id == null || category == null) {
                throw IllegalArgumentException(
                    "malformed class-index line: \"$line\" " +
                        "(expected \"<torrent_id> <category>\")",
                )
            }
            index[id] = category
        }
    }
    return index
}

private val WHITESPACE_RUN = Regex("[ \\t\\r]+")

/**
 * Statement-level category filter for the incremental restore (plan 3, pure
 * core of the "categories to save" wizard selection).
 *
 * The dump is a stream of trimmed `INSERT OR IGNORE INTO <table> VALUES(v1,
 * v2, ...)` statements with NO column list, values in declared column order
 * ([SqlDumpWriter.renderStatement]). [keep] decides, per statement, whether it
 * belongs to a torrent whose category the user selected:
 *
 * - the table is the case-insensitive token between `INTO` and the following
 *   whitespace; a statement that does not start with `INSERT` is kept (never
 *   expected — the splitter only forwards dump statements — but dropping DDL
 *   would be catastrophic);
 * - `path_prefixes` is always kept: it is a shared lookup table, so orphan
 *   rows are harmless and dropping it would break the rows we do keep;
 * - ANY other unknown table name is kept — never silently drop what we do not
 *   understand;
 * - `torrents` / `torrent_files` / `torrent_class` are keyed on the torrent id
 *   column of [ID_COLUMN];
 * - the category comes from the range's class index (`torrent_id → category`,
 *   see [parseClassIndex]) with [UNKNOWN_CATEGORY] for an id the index does not
 *   mention, and the statement is kept iff `category in selection`.
 *
 * Value extraction is a quote-aware scan of the top-level `VALUES(...)` list:
 * `''` is the only escape inside a single-quoted literal (the same rule as
 * [SqlStatementSplitter]), `x'...'` blob literals are ordinary quoted text,
 * commas / semicolons / parentheses inside a literal are content, and
 * parenthesised groups are skipped as one token.
 *
 * **Deterministic over-inclusion:** when the wanted token cannot be located or
 * does not parse as an (optionally `-`-prefixed) decimal integer — `NULL`, a
 * quoted string, an overflow, a statement shape we do not recognise — the
 * statement is KEPT. Restoring too much is recoverable; silently dropping rows
 * is not.
 *
 * No Android imports — plain JUnit4 testable. Unused by production code until
 * the streaming restore stage wires it in.
 */
internal class RestoreCategoryFilter(
    private val selection: Set<Int>,
    private val index: Map<Long, Int>,
) {

    /** `true` when the row encoded by [sql] is inside the selected categories. */
    fun keep(sql: String): Boolean {
        val table = insertTable(sql) ?: return true
        val idColumn = ID_COLUMN[table] ?: return true
        val values = valuesList(sql) ?: return true
        val token = values.getOrNull(idColumn) ?: return true
        val id = parseDecimalLong(token) ?: return true
        return (index[id] ?: UNKNOWN_CATEGORY) in selection
    }
}

/**
 * Lower-cased table name of an `INSERT ... INTO <table> ...` statement, or
 * `null` when [sql] is not an INSERT / has no `INTO` (callers keep those).
 */
private fun insertTable(sql: String): String? {
    val statement = sql.trimStart()
    if (!statement.regionMatches(0, INSERT_KEYWORD, 0, INSERT_KEYWORD.length, true)) return null
    val into = statement.indexOf(INTO_KEYWORD, ignoreCase = true)
    if (into < 0) return null
    var i = into + INTO_KEYWORD.length
    while (i < statement.length && statement[i].isWhitespace()) i++
    val start = i
    while (i < statement.length && !statement[i].isWhitespace()) i++
    if (i == start) return null
    return statement.substring(start, i).lowercase()
}

/**
 * The top-level comma-separated tokens of the statement's `VALUES(...)` list,
 * trimmed, or `null` when the list cannot be located / is unterminated (the
 * caller then keeps the statement).
 */
private fun valuesList(sql: String): List<String>? {
    val values = sql.indexOf(VALUES_KEYWORD, ignoreCase = true)
    if (values < 0) return null
    var i = values + VALUES_KEYWORD.length
    while (i < sql.length && sql[i].isWhitespace()) i++
    if (i >= sql.length || sql[i] != '(') return null
    i++
    val tokens = ArrayList<String>()
    val current = StringBuilder()
    var depth = 1
    var inQuote = false
    while (i < sql.length) {
        val c = sql[i]
        if (inQuote) {
            if (c == '\'') {
                if (i + 1 < sql.length && sql[i + 1] == '\'') {
                    // Doubled quote: one literal apostrophe, literal stays open.
                    current.append(c).append(c)
                    i += 2
                    continue
                }
                inQuote = false
            }
            current.append(c)
            i++
            continue
        }
        when (c) {
            '\'' -> {
                inQuote = true
                current.append(c)
            }

            '(' -> {
                depth++
                current.append(c)
            }

            ')' -> {
                depth--
                if (depth == 0) {
                    tokens.add(current.toString())
                    return tokens
                }
                current.append(c)
            }

            ',' -> if (depth == 1) {
                tokens.add(current.toString())
                current.setLength(0)
            } else {
                current.append(c)
            }

            else -> current.append(c)
        }
        i++
    }
    return null
}

/**
 * [token] as a decimal [Long] — optional leading `-`, then ASCII digits only —
 * or `null` when it is anything else (including a numeric overflow).
 */
private fun parseDecimalLong(token: String): Long? {
    val trimmed = token.trim()
    val start = if (trimmed.startsWith('-')) 1 else 0
    if (start >= trimmed.length) return null
    for (i in start until trimmed.length) {
        val c = trimmed[i]
        if (c < '0' || c > '9') return null
    }
    return trimmed.toLongOrNull()
}

/** [token] as a decimal [Int] (see [parseDecimalLong]); `null` when out of range. */
private fun parseDecimalInt(token: String): Int? {
    val value = parseDecimalLong(token) ?: return null
    return if (value in Int.MIN_VALUE..Int.MAX_VALUE) value.toInt() else null
}
