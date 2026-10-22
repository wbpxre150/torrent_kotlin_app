package com.torrent.app.backup

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import java.io.File

/**
 * Android adapter over the pure [SqlDumpWriter] (plan 2, Stage 1+2):
 * opens the crawler database (`filesDir/crawler/data/torrents.db`) on a
 * dedicated READ-ONLY connection — never shared with the crawler
 * process — and reads the max ids plus all bookmark-gated rows inside
 * ONE non-exclusive read transaction (a WAL snapshot), so the cursor and
 * the rows can never disagree.
 *
 * One snapshot is bounded on TWO levels. The torrent range is CAPPED to
 * at most [maxNewTorrentIdsPerDump] new torrent ids (see
 * [DEFAULT_MAX_NEW_TORRENT_IDS_PER_DUMP]): on the on-device DB (hundreds of
 * thousands of torrents with a bookmark far behind) materializing the whole
 * backlog in RAM OOMs the app. The torrent-id cap ALONE is not enough,
 * though: `torrent_files` rows per torrent are heavy-tailed (on-device, one
 * 2,000-id chunk carried ~1.5M file rows and a ~136 MB SQL text) and
 * `path_prefixes` is unbounded too (on-device, with 17M file rows the prefix
 * table had grown ~1M new long-string rows since the bookmark, so the prefix
 * read alone rendered a ~136 MB SQL text and OOM'd). One byte budget
 * ([maxChunkEstimatedBytes]) is therefore SHARED by both tables:
 * `path_prefixes` is read first in [prefixSubRangeIds]-wide id sub-ranges and
 * `torrent_files` after it in [fileSubRangeTorrentIds]-wide torrent-id
 * sub-ranges, each cut at its OWN id sub-range boundary — never inside one
 * torrent's files and never mid-prefix-range.
 *
 * Each dump is therefore one bounded chunk whose cursor ([DbBookmark])
 * carries BOTH cuts: `newCursor.lastTorrentId` is the file cut and
 * `newCursor.lastPrefixId` is the prefix cut, so successive dumps from that
 * cursor drain both tables. [DbSnapshot.capped] is `true` when EITHER table
 * still has rows beyond the chunk, and the manager keeps draining (and does
 * not persist the daily date) until both are drained.
 *
 * Known accepted edge (prefix backlog with no new torrents): when no NEW
 * torrents exist but undumped prefixes remain, the chunk is still `Skipped`
 * by [SqlDumpWriter.buildDump] (the artifact is keyed by the torrent range),
 * so the pending prefixes wait for the next chunk that carries new torrents.
 * They are dumped exactly once when that happens — the crawler adds torrents
 * continuously, so this is a short delay, never data loss: every prefix id is
 * covered by exactly one `(lastPrefixId, cut]` range as the cursor advances.
 *
 * Forward progress on BOTH tables: the first prefix batch and the first file
 * batch are always included, even when one alone exceeds the budget.
 *
 * The result is the pure core's [DbDumpOutcome]: gzip-compressed SQL
 * bytes, statement count, next cursor values and the S3 object key.
 * Persisting the cursor is the upload stage's job (after a successful
 * upload only).
 */
class CrawlerDbDumper(
    private val keyPrefix: String = DbObjectKey.DEFAULT_PREFIX,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val maxNewTorrentIdsPerDump: Long = DEFAULT_MAX_NEW_TORRENT_IDS_PER_DUMP,
    private val maxChunkEstimatedBytes: Long = DEFAULT_MAX_CHUNK_ESTIMATED_BYTES,
    private val fileSubRangeTorrentIds: Long = DEFAULT_FILE_SUBRANGE_TORRENT_IDS,
    private val prefixSubRangeIds: Long = DEFAULT_PREFIX_SUBRANGE_IDS,
) : DbDumper {

    init {
        require(maxNewTorrentIdsPerDump > 0L) {
            "maxNewTorrentIdsPerDump must be > 0, was $maxNewTorrentIdsPerDump"
        }
        require(maxChunkEstimatedBytes > 0L) {
            "maxChunkEstimatedBytes must be > 0, was $maxChunkEstimatedBytes"
        }
        require(fileSubRangeTorrentIds > 0L) {
            "fileSubRangeTorrentIds must be > 0, was $fileSubRangeTorrentIds"
        }

        require(prefixSubRangeIds > 0L) {
            "prefixSubRangeIds must be > 0, was $prefixSubRangeIds"
        }
    }

    companion object {
        /**
         * Chunk cap on NEW torrent ids per snapshot. Two-level bounding: this
         * id cap is NOT sufficient on its own — `torrent_files` rows per
         * torrent are heavy-tailed (on-device a 2,000-id chunk carried
         * ~1.5M file rows, i.e. a ~136 MB SQL text), so the file rows read is
         * additionally byte-budgeted by [DEFAULT_MAX_CHUNK_ESTIMATED_BYTES]
         * and cut at a sub-range boundary. Each dump is a bounded chunk, and
         * successive chunks (one per backup check) drain the backlog.
         */
        const val DEFAULT_MAX_NEW_TORRENT_IDS_PER_DUMP = 2_000L

        /**
         * Rough in-memory byte estimate of the rows (`path_prefixes` +
         * `torrent_files`) allowed in ONE snapshot ([estimatedBytes]'s unit).
         * The budget is SHARED by both tables: `path_prefixes` is read first
         * against it and the `torrent_files` read keeps accumulating into the
         * same counter, so a chunk's total peak stays bounded whichever table
         * is the heavy one. At ~600 B/row actual heap this bounds a chunk's
         * peak at roughly 100 MB — safe inside the app's 384 MB heap.
         */
        const val DEFAULT_MAX_CHUNK_ESTIMATED_BYTES = 16L shl 20

        /**
         * The files table is read in gated sub-ranges of this many torrent
         * ids so the byte budget can cut the chunk at a torrent-id boundary;
         * a chunk NEVER splits a torrent's files (the cursor advances past
         * the torrent id, so partial files would be lost forever). The first
         * sub-range is always included even when it alone exceeds the budget
         * (forward progress; a truly enormous sub-range then fails the check
         * cleanly — the manager catches Throwable and marks FAILED instead of
         * crashing).
         */
        const val DEFAULT_FILE_SUBRANGE_TORRENT_IDS = 50L

        /**
         * `path_prefixes` is read in gated sub-ranges of this many prefix ids
         * so the shared byte budget can cut the chunk at a prefix-id
         * boundary; 5,000 ids × ~130 B ≈ 650 KB per batch. Like the file
         * sub-ranges, the first batch is always included even when it alone
         * exceeds the budget (forward progress).
         */
        const val DEFAULT_PREFIX_SUBRANGE_IDS = 5_000L
    }

    override fun dump(dbFile: File, bookmark: DbBookmark): DbDumpOutcome {
        require(dbFile.isFile) { "crawler DB not found: ${dbFile.path}" }
        val db = SQLiteDatabase.openDatabase(dbFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
        try {
            db.beginTransactionNonExclusive()
            try {
                return SqlDumpWriter(
                    SQLiteDbSource(
                        db, maxNewTorrentIdsPerDump,
                        maxChunkEstimatedBytes, fileSubRangeTorrentIds,
                        prefixSubRangeIds,
                    ),
                    keyPrefix, clock
                ).buildDump(bookmark)
            } finally {
                db.endTransaction()
            }
        } finally {
            db.close()
        }
    }

    /**
     * [CrawlerDbSource] over one open [SQLiteDatabase]. The caller holds
     * the read transaction for the whole [snapshot] call.
     *
     * The chunk is bounded on two levels: the torrent range is cut at the id
     * cap [maxNewTorrentIdsPerDump], and — because BOTH `path_prefixes` and
     * `torrent_files` can be huge since the bookmark — the rows read are also
     * byte-budgeted via the SHARED [maxChunkEstimatedBytes], over sub-ranges
     * of [prefixSubRangeIds] prefix ids and [fileSubRangeTorrentIds] torrent
     * ids. This is the single place that bounds one dump's row count (and
     * hence its heap peak).
     */
    private class SQLiteDbSource(
        private val db: SQLiteDatabase,
        private val maxNewTorrentIdsPerDump: Long,
        private val maxChunkEstimatedBytes: Long,
        private val fileSubRangeTorrentIds: Long,
        private val prefixSubRangeIds: Long,
    ) : CrawlerDbSource {

        override fun snapshot(bookmark: DbBookmark): DbSnapshot {
            val trueMaxTorrentId = maxId(TABLE_TORRENTS)
            val trueMaxPrefixId = maxId(TABLE_PATH_PREFIXES)
            // Chunk boundary: at most maxNewTorrentIdsPerDump new torrent ids
            // per snapshot. FIRST_RUN (-1) means "from the beginning" and
            // torrent ids start at 1, so its effective base is 0 — every chunk
            // then carries exactly the cap's worth of new ids
            // (require(cap > 0) in the outer class keeps this strictly
            // forward-progressing). This is the UPPER bound only: the shared
            // byte budget below can cut the chunk earlier, at a prefix-id or a
            // torrent-id sub-range boundary.
            val base = maxOf(bookmark.lastTorrentId, 0L)
            val idCap = minOf(trueMaxTorrentId, base + maxNewTorrentIdsPerDump)

            // ONE byte budget SHARED by BOTH tables: the prefix read below and
            // the file read after it accumulate into the same `est`, so the
            // chunk's heap peak stays bounded whichever table is the heavy one.
            var est = 0L

            // path_prefixes FIRST, in gated id sub-ranges: the table is cheap
            // per row but unbounded since the bookmark (on-device: ~1M new
            // long-string rows → a ~136 MB SQL text and an OOM), so the shared
            // budget cuts HERE — before any file rows are materialized — at a
            // prefix-id sub-range boundary. The first sub-range is always
            // included, even alone over budget, so this stays strictly
            // forward-progressing.
            var plo = bookmark.lastPrefixId
            var prefixCut = bookmark.lastPrefixId
            val prefixesOut = ArrayList<List<SqlValue>>()
            while (plo < trueMaxPrefixId) {
                val phi = minOf(plo + prefixSubRangeIds, trueMaxPrefixId)
                val batch = rows(TABLE_PATH_PREFIXES, "id", plo, phi)
                if (prefixesOut.isNotEmpty() &&
                    est + estimatedBytes(batch) > maxChunkEstimatedBytes
                ) {
                    // Budget reached: cut at the previous prefix sub-range
                    // boundary (the last accepted batch stays in, so
                    // prefixesOut covers exactly (bookmark, prefixCut]).
                    break
                }
                prefixesOut.addAll(batch)
                est += estimatedBytes(batch)
                prefixCut = phi
                plo = phi
            }

            // then torrent_files, in gated sub-ranges (torrent_id, NOT the
            // files' own id, mirroring the crawler's pipeline), so the shared
            // budget can also cut the chunk at a torrent-id boundary: a chunk
            // NEVER splits a torrent's files — the cursor advances past the
            // torrent id, so partially-read files would be lost forever. The
            // first sub-range is always included, even alone over budget, so
            // this stays strictly forward-progressing; `est` already carries
            // the accepted prefix rows.
            var lo = bookmark.lastTorrentId
            var cut = bookmark.lastTorrentId
            val filesOut = ArrayList<List<SqlValue>>()
            while (lo < idCap) {
                val hi = minOf(lo + fileSubRangeTorrentIds, idCap)
                val batch = rows(TABLE_TORRENT_FILES, "torrent_id", lo, hi)
                if (filesOut.isNotEmpty() &&
                    est + estimatedBytes(batch) > maxChunkEstimatedBytes
                ) {
                    // Budget reached: cut at the previous sub-range boundary
                    // (the last accepted batch stays in, so filesOut covers
                    // exactly (bookmark, chunkMax]).
                    break
                }
                filesOut.addAll(batch)
                est += estimatedBytes(batch)
                cut = hi
                lo = hi
            }
            val chunkMax = cut
            // Class index of the SAME window: (bookmark, chunkMax] covers
            // both the `torrents` rows read below and every `torrent_files`
            // row of the chunk (files are gated on torrent_id in
            // (bookmark, cut] with cut == chunkMax), so ONE sidecar resolves
            // every statement of this object. PK order; read inside the same
            // read transaction, so it cannot disagree with the rows.
            val classIndex = classEntries(bookmark.lastTorrentId, chunkMax)
            val hasNewTorrents = chunkMax > bookmark.lastTorrentId
            return DbSnapshot(
                maxTorrentId = chunkMax,
                // Both cuts travel in the cursor, so successive chunks drain
                // BOTH tables.
                maxPrefixId = prefixCut,
                pathPrefixes = prefixesOut,
                torrents = if (hasNewTorrents) {
                    // Read AFTER the cut, so the chunk's torrents never outrun
                    // their rows in torrent_files.
                    rows(TABLE_TORRENTS, "id", bookmark.lastTorrentId, chunkMax)
                } else {
                    emptyList()
                },
                torrentFiles = filesOut,
                capped = chunkMax < trueMaxTorrentId || prefixCut < trueMaxPrefixId,
                classEntries = classIndex,
            )
        }

        /** `SELECT COALESCE(MAX(id), 0)` — empty table → 0. */
        private fun maxId(table: String): Long {
            db.rawQuery("SELECT COALESCE(MAX(id), 0) FROM $table", null).use { c ->
                return if (c.moveToFirst()) c.getLong(0) else 0L
            }
        }

        /** `SELECT *` rows in (exclusiveMin, inclusiveMax] of [gateColumn]. */
        private fun rows(
            table: String,
            gateColumn: String,
            exclusiveMin: Long,
            inclusiveMax: Long,
        ): List<List<SqlValue>> {
            val out = ArrayList<List<SqlValue>>()
            db.rawQuery(
                "SELECT * FROM $table WHERE $gateColumn > ? AND $gateColumn <= ?",
                arrayOf(exclusiveMin.toString(), inclusiveMax.toString()),
            ).use { c ->
                val columnCount = c.columnCount
                while (c.moveToNext()) {
                    val row = ArrayList<SqlValue>(columnCount)
                    for (i in 0 until columnCount) {
                        row += valueAt(c, i)
                    }
                    out += row
                }
            }
            return out
        }

        /**
         * `(torrent_id, category)` of `torrent_class` in (exclusiveMin,
         * inclusiveMax], in PK order — the class index of one dump range.
         */
        private fun classEntries(exclusiveMin: Long, inclusiveMax: Long): List<ClassEntry> {
            val out = ArrayList<ClassEntry>()
            db.rawQuery(
                "SELECT torrent_id, category FROM $TABLE_TORRENT_CLASS " +
                    "WHERE torrent_id > ? AND torrent_id <= ? ORDER BY torrent_id",
                arrayOf(exclusiveMin.toString(), inclusiveMax.toString()),
            ).use { c ->
                while (c.moveToNext()) {
                    out += ClassEntry(c.getLong(0), c.getInt(1))
                }
            }
            return out
        }

        private fun valueAt(c: Cursor, i: Int): SqlValue = when (c.getType(i)) {
            Cursor.FIELD_TYPE_NULL -> SqlValue.Null
            Cursor.FIELD_TYPE_INTEGER -> SqlValue.Integer(c.getLong(i))
            Cursor.FIELD_TYPE_STRING -> SqlValue.Text(c.getString(i))
            Cursor.FIELD_TYPE_BLOB -> SqlValue.Blob(c.getBlob(i))
            else -> throw IllegalStateException(
                "unexpected column type ${c.getType(i)} at column $i"
            )
        }
    }
}

/**
 * Rough heap-size proxy for [rows]: per row a 16-byte base, plus per value
 * Null → 0, Integer → 16, Text → 8 + [String.length] (UTF-16 code units),
 * Blob → 16 + [ByteArray.size]. The rendered SQL literal is within ~2× of
 * this, but precision is not needed — only monotonicity, so the byte budget
 * tracks heap growth without walking the real object graph.
 */
private fun estimatedBytes(rows: List<List<SqlValue>>): Long {
    var total = 0L
    for (row in rows) {
        total += 16L
        for (value in row) {
            total += when (value) {
                SqlValue.Null -> 0L
                is SqlValue.Integer -> 16L
                is SqlValue.Text -> 8L + value.value.length
                is SqlValue.Blob -> 16L + value.bytes.size
            }
        }
    }
    return total
}
