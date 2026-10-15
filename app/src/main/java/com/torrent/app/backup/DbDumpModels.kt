package com.torrent.app.backup

/**
 * One SQL column value of the crawler database, in the dump's declared
 * (SELECT *) column order. Mirrors the storage types of the three base
 * tables: INTEGER, TEXT and BLOB only.
 */
sealed class SqlValue {

    /** SQL `NULL`. */
    object Null : SqlValue()

    /** Bare decimal integer literal. */
    data class Integer(val value: Long) : SqlValue()

    /** Single-quoted text literal (`'` escaped as `''`). */
    data class Text(val value: String) : SqlValue()

    /** `x'...'` lowercase-hex blob literal. */
    class Blob(val bytes: ByteArray) : SqlValue() {
        override fun equals(other: Any?): Boolean =
            other is Blob && bytes.contentEquals(other.bytes)

        override fun hashCode(): Int = bytes.contentHashCode()

        override fun toString(): String = "Blob(${bytes.size} bytes)"
    }
}

/**
 * One consistent read of the crawler database: the snapshot max ids plus
 * the rows already gated by [DbBookmark] (path_prefixes/torrents on their
 * own id, torrent_files on torrent_id). The source guarantees all of this
 * is read inside ONE read transaction so the cursor and the rows can
 * never disagree.
 *
 * The torrent range may be a bounded CHUNK of the backlog rather than the
 * whole table — see [capped].
 */
data class DbSnapshot(
    val maxTorrentId: Long,
    val maxPrefixId: Long,
    val pathPrefixes: List<List<SqlValue>>,
    val torrents: List<List<SqlValue>>,
    val torrentFiles: List<List<SqlValue>>,
    /**
     * `true` when the torrent range was truncated by the source's chunk cap:
     * more rows exist beyond [maxTorrentId], so [maxTorrentId] is a chunk
     * boundary, not the table's true max.
     */
    val capped: Boolean = false,
) {
    /** Total number of INSERT statements [renderSql] emits for this snapshot. */
    val statementCount: Int
        get() = pathPrefixes.size + torrents.size + torrentFiles.size
}

/**
 * Outcome of one dump attempt (plan 2, Stage 1+2). [Dump] carries the
 * gzip-compressed SQL artifact plus everything the upload stage needs
 * (object key, next cursor); [Skipped] means the range was empty (no
 * artifact at all — mirrors the crawler's skip).
 */
sealed interface DbDumpOutcome {

    /**
     * A dump artifact was produced. [sqlBytes] is the gzip-compressed SQL
     * text; [newCursor] is the bookmark to persist AFTER a successful
     * upload of [objectKey] (never before).
     *
     * [capped] flows from the [DbSnapshot] it was built from: `true` when the
     * source hit its chunk cap, i.e. rows beyond [newCursor] still have to be
     * dumped by a later run.
     */
    class Dump(
        val sqlBytes: ByteArray,
        val statementCount: Int,
        val newCursor: DbBookmark,
        val objectKey: String,
        val newTorrentCount: Long,
        val capped: Boolean = false,
    ) : DbDumpOutcome

    /** Empty range (`max_torrent_id <= last_torrent_id`): nothing to dump. */
    class Skipped(
        val maxTorrentId: Long,
        val maxPrefixId: Long,
        val bookmark: DbBookmark,
    ) : DbDumpOutcome {
        /** Always 0 — the skip means no new torrents. */
        val newTorrentCount: Long get() = 0
    }
}
