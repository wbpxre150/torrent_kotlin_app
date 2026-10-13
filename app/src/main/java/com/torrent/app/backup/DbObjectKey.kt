package com.torrent.app.backup

/**
 * One S3 object key of the incremental dump chain (plan 2, Stage 2):
 *
 * ```
 * <prefix>/incremental_<YYYYMMDD>_<START>-<END>.sql.gz
 * ```
 *
 * `YYYYMMDD` is the UTC date of the dump; `START` = `last_torrent_id + 1`
 * (first run → 0); `END` = `max_torrent_id` dumped. [parse] is the
 * inverse of [key] (Stage 4 restore ordering) and returns `null` for
 * anything that does not match the convention exactly.
 */
data class DbObjectKey(val prefix: String, val date: String, val start: Long, val end: Long) {

    init {
        require(start >= 0L) { "start must be >= 0, was $start" }
        require(end >= start) { "end ($end) must be >= start ($start)" }
    }

    /** The full object key string. */
    val key: String
        get() = "$prefix/incremental_${date}_$start-$end.sql.gz"

    companion object {
        /** Default key prefix (plan 2: `db/...` in the shared bucket). */
        const val DEFAULT_PREFIX = "db"

        private const val DATE_LENGTH = 8

        // prefix: no '/', non-empty; date: exactly 8 digits; ids: >= 0, no
        // leading zeros; fixed .sql.gz suffix.
        private val KEY_PATTERN =
            Regex("""^([^/]+)/incremental_(\d{8})_(0|[1-9]\d*)-(0|[1-9]\d*)\.sql\.gz$""")

        /**
         * Builds a key. Fails fast (require) on a bad prefix (blank or
         * containing `/`), a non-8-digit date, or an inverted/empty range.
         */
        fun build(prefix: String, date: String, start: Long, end: Long): DbObjectKey {
            require(prefix.isNotBlank() && !prefix.contains('/')) {
                "bad key prefix: '$prefix'"
            }
            require(date.length == DATE_LENGTH && date.all { it.isDigit() }) {
                "bad key date: '$date'"
            }
            return DbObjectKey(prefix, date, start, end)
        }

        /**
         * Parses [key] back into (prefix, date, start, end). `null` for
         * malformed keys: wrong/missing suffix, nested prefix, missing
         * range, non-numeric or leading-zero ids, negative ids, or
         * `start > end`.
         */
        fun parse(key: String): DbObjectKey? {
            val m = KEY_PATTERN.matchEntire(key) ?: return null
            val (prefix, date, start, end) = m.destructured
            val s = start.toLong()
            val e = end.toLong()
            if (s > e) return null
            return DbObjectKey(prefix, date, s, e)
        }
    }
}
