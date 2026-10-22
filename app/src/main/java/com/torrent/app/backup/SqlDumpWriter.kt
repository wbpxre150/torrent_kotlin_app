package com.torrent.app.backup

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * One consistent read of the crawler database for a dump (the seam the
 * pure core is tested against with fakes). The implementation MUST read
 * the max ids and all gated rows inside ONE read transaction so the
 * cursor and the rows can never disagree.
 */
interface CrawlerDbSource {
    fun snapshot(bookmark: DbBookmark): DbSnapshot
}

/**
 * Pure incremental SQL dump emitter (plan 2, Stage 1+2): a port of the
 * crawler's `batch_writer.c` dump pipeline (sqlite3 `.mode insert` +
 * `sed 's/^INSERT INTO /INSERT OR IGNORE INTO /'`), minus the shell.
 *
 * Format (crawler-compatible statement shape):
 * - table order `path_prefixes` → `torrents` → `torrent_files`
 *   (base tables only — no FTS5, no schema/DDL);
 * - one `INSERT OR IGNORE INTO <table> VALUES(...);` per row, NO column
 *   list (values in declared/`SELECT *` column order);
 * - literals: `NULL`, bare decimal integers, single-quoted text with
 *   standard SQL escaping (`'` → `''`), `x'...'` lowercase-hex blobs.
 *
 * Decided (plan 2): standard SQL escaping, NOT byte-compatible with
 * `sqlite3 .mode insert` output — we control both writer and reader.
 *
 * [buildDump] gates on [DbBookmark]: `max_torrent_id <= last_torrent_id`
 * (incl. an empty table) → [DbDumpOutcome.Skipped] with no artifact at
 * all; otherwise the assembled SQL is gzip-compressed and named
 * `<prefix>/incremental_<YYYYMMDD (UTC)>_<start>-<end>.sql.gz`. The
 * snapshot may cover only a capped CHUNK of the backlog (source-side cap):
 * the object key and the cursor then end at the chunk boundary, and
 * [DbDumpOutcome.Dump.capped] tells the manager that more rows remain.
 *
 * No Android imports — plain JUnit4 testable with a fake [CrawlerDbSource].
 */
class SqlDumpWriter(
    private val source: CrawlerDbSource,
    private val keyPrefix: String = DbObjectKey.DEFAULT_PREFIX,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    /**
     * Assembles one dump for [bookmark]. [DbDumpOutcome.Dump.newCursor]
     * carries the max ids of THIS snapshot — the upload stage must
     * persist it only after a successful upload.
     */
    fun buildDump(bookmark: DbBookmark): DbDumpOutcome {
        val snapshot = source.snapshot(bookmark)
        if (snapshot.maxTorrentId == 0L || snapshot.maxTorrentId <= bookmark.lastTorrentId) {
            // Empty range (incl. an empty table, even on the first run):
            // no artifact, mirrors the crawler's "no new torrents" skip.
            // With a source-side chunk cap `maxTorrentId` is the chunk end,
            // so this stays "no new torrents in this range".
            return DbDumpOutcome.Skipped(
                maxTorrentId = snapshot.maxTorrentId,
                maxPrefixId = snapshot.maxPrefixId,
                bookmark = bookmark,
            )
        }
        val sql = renderSql(snapshot)
        val objectKey = DbObjectKey.build(
            keyPrefix, utcDateString(), bookmark.lastTorrentId + 1, snapshot.maxTorrentId
        )
        return DbDumpOutcome.Dump(
            sqlBytes = gzipBytes(sql.encodeToByteArray()),
            statementCount = snapshot.statementCount,
            newCursor = DbBookmark(snapshot.maxTorrentId, snapshot.maxPrefixId),
            objectKey = objectKey.key,
            newTorrentCount = snapshot.torrents.size.toLong(),
            capped = snapshot.capped,
            // ALWAYS emitted, even for zero entries: an empty index asserts
            // "this range is fully covered, unclassified torrents are
            // unknown", which is different from "no index → unfiltered".
            sidecarBytes = gzipBytes(renderClassIndex(snapshot.classEntries).encodeToByteArray()),
            sidecarObjectKey = objectKey.sidecarKey,
        )
    }

    /** UTC `YYYYMMDD` of the dump (per [clock]). */
    private fun utcDateString(): String =
        Instant.ofEpochMilli(clock())
            .atZone(ZoneOffset.UTC)
            .toLocalDate()
            .format(DateTimeFormatter.BASIC_ISO_DATE)
}

private const val HEX_DIGITS = "0123456789abcdef"

/**
 * Renders one column value as a SQL literal: `NULL`, bare decimal,
 * single-quoted text (`'` doubled), or `x'...'` lowercase hex.
 */
fun renderSqlLiteral(value: SqlValue): String = when (value) {
    SqlValue.Null -> "NULL"
    is SqlValue.Integer -> value.value.toString()
    is SqlValue.Text -> "'" + value.value.replace("'", "''") + "'"
    is SqlValue.Blob -> "x'" + hexLower(value.bytes) + "'"
}

/** One crawler-shaped statement for [row] of [table] (no column list). */
fun renderStatement(table: String, row: List<SqlValue>): String =
    "INSERT OR IGNORE INTO $table VALUES(" +
        row.joinToString(", ") { renderSqlLiteral(it) } + ");"

/**
 * Assembles the full dump text for [snapshot]: all `path_prefixes`
 * statements, then all `torrents`, then all `torrent_files`, one per
 * line (the order and shape the crawler's pipeline produces).
 */
fun renderSql(snapshot: DbSnapshot): String {
    val sb = StringBuilder()
    appendTable(sb, TABLE_PATH_PREFIXES, snapshot.pathPrefixes)
    appendTable(sb, TABLE_TORRENTS, snapshot.torrents)
    appendTable(sb, TABLE_TORRENT_FILES, snapshot.torrentFiles)
    return sb.toString()
}

/**
 * Renders the CLASS INDEX text of [entries]: one `"<torrent_id> <category>\n"`
 * line per entry, in the given (PK) order — the decompressed content of a
 * dump's `.idx.gz` sidecar. Zero entries render as the empty string (the
 * "fully covered, nothing classified" index).
 */
fun renderClassIndex(entries: List<ClassEntry>): String {
    val sb = StringBuilder()
    for (entry in entries) {
        sb.append(entry.torrentId).append(' ').append(entry.category).append('\n')
    }
    return sb.toString()
}

private fun appendTable(sb: StringBuilder, table: String, rows: List<List<SqlValue>>) {
    for (row in rows) {
        sb.append(renderStatement(table, row)).append('\n')
    }
}

private fun hexLower(bytes: ByteArray): String {
    val sb = StringBuilder(bytes.size * 2)
    for (b in bytes) {
        sb.append(HEX_DIGITS[(b.toInt() shr 4) and 0x0F])
        sb.append(HEX_DIGITS[b.toInt() and 0x0F])
    }
    return sb.toString()
}

/** Gzip-compresses [bytes] (java.util.zip — zero new dependencies). */
fun gzipBytes(bytes: ByteArray): ByteArray {
    val out = ByteArrayOutputStream()
    GZIPOutputStream(out).use { it.write(bytes) }
    return out.toByteArray()
}

/** Inverse of [gzipBytes]; fails with java.util.zip.ZipException on bad input. */
fun gunzipBytes(bytes: ByteArray): ByteArray =
    GZIPInputStream(ByteArrayInputStream(bytes)).use { it.readBytes() }

internal const val TABLE_PATH_PREFIXES = "path_prefixes"
internal const val TABLE_TORRENTS = "torrents"
internal const val TABLE_TORRENT_FILES = "torrent_files"

/** Never dumped: only read for the class-index sidecar of a range. */
internal const val TABLE_TORRENT_CLASS = "torrent_class"
