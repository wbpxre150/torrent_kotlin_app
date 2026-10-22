package com.torrent.app.backup

/**
 * The free-space budget of the resumable DB import, as a pure decision (plan 3,
 * Stage 11; no Android types, so it is plain-JUnit testable like
 * [com.torrent.app.RollbackPolicy]).
 *
 * Why it exists: the import writes a whole second copy of the crawler database
 * (~20–40 GB) next to the live one, on a volume that also hosts the
 * transmission downloads and the FTP root. Running out of space halfway
 * through a ~22 h replay would surface as an obscure `SQLITE_FULL` deep inside
 * a chunk, so the budget is decided UP FRONT from numbers the LIST already
 * carries, and re-decided at every WAL checkpoint.
 *
 * The estimate (measured on-device, build `35f4994`):
 *
 * ```
 * requiredBytes = 2 GiB + (3/2) × (7 × Σ remaining compressed object sizes)
 * ```
 *
 * * `7` is the measured gzip ratio of the dump: chunk 1 expanded to ~20 MB of
 *   SQL from a 2.8 MB object, so `7 × compressed` estimates the remaining SQL.
 * * `3/2` is headroom over that SQL volume: SQLite's page images plus the
 *   B-tree pages it rewrites as the tables grow (measured WAL growth was ~5×
 *   the imported SQL before the Stage 11 checkpoints bounded it).
 * * `2 GiB` covers the bounded WAL (~1.2 GB, see [SqliteRestoreSink]) plus the
 *   temp DB's `-shm`, the resume marker and slack for everything else writing
 *   to the same volume during a multi-hour run.
 *
 * Every size is treated as "unknown = 0": a listing that omits an object's
 * Content-Length only ever under-counts that object, and the 2 GiB floor still
 * applies. Arithmetic saturates instead of wrapping, so a pathological input
 * can never turn "obviously not enough space" into "sufficient".
 */
internal object RestoreDiskGuard {

    /** 1 GiB in bytes, spelled out so the constants below read as sizes. */
    private const val GIB: Long = 1024L * 1024 * 1024

    /**
     * The floor of the budget: bounded WAL (~1.2 GB) + `-shm` + marker +
     * slack for the other writers on the same volume.
     */
    const val BASE_BYTES: Long = 2L * GIB

    /**
     * Measured gzip ratio of the dump (compressed object → SQL text). The LIST
     * reports each object's compressed size, so the remaining SQL volume needs
     * no extra I/O and no decompression to estimate.
     */
    const val SQL_BYTES_PER_COMPRESSED_BYTE: Long = 7L

    /** Headroom over the estimated remaining SQL volume (3/2 = +50 %). */
    private const val HEADROOM_DENOMINATOR = 2L

    /**
     * The decision itself: [sufficient] plus the two numbers it was made from,
     * so the caller can log the budget and build the failure message without
     * re-evaluating the estimate (which would invite drift between the check
     * and the message).
     */
    data class Decision(
        val sufficient: Boolean,
        val requiredBytes: Long,
        val freeBytes: Long,
    )

    /**
     * The single entry point: is there room for the rest of the chain?
     *
     * @param remainingCompressedBytes Σ of the not-yet-imported objects'
     *   compressed sizes (see [remainingCompressedBytes]).
     * @param freeSpaceBytes free bytes on the volume that holds the temp DB
     *   (production: `StatFs(filesDir).availableBytes`).
     */
    fun check(remainingCompressedBytes: Long, freeSpaceBytes: Long): Decision {
        val required = requiredBytes(remainingCompressedBytes)
        return Decision(
            sufficient = freeSpaceBytes >= required,
            requiredBytes = required,
            freeBytes = freeSpaceBytes,
        )
    }

    /**
     * Σ of the compressed sizes of the objects from [fromIndex] on — the part
     * of the chain that still has to be downloaded and imported. An object
     * without a listed size counts as 0; an out-of-range index counts as
     * nothing left (the import is at or past the end of the chain).
     */
    fun remainingCompressedBytes(sizes: List<Long?>, fromIndex: Int): Long {
        var total = 0L
        for (i in maxOf(0, fromIndex) until sizes.size) {
            val size = sizes[i] ?: 0L
            if (size <= 0L) continue
            total = saturatedAdd(total, size)
        }
        return total
    }

    /** `2 GiB + 1.5 × (7 × compressed)`, saturating at [Long.MAX_VALUE]. */
    fun requiredBytes(remainingCompressedBytes: Long): Long {
        val sql = saturatedMultiply(
            maxOf(0L, remainingCompressedBytes), SQL_BYTES_PER_COMPRESSED_BYTE)
        // 1.5 × sql without an overflow: `sql + sql / 2` is exactly
        // `(3 × sql) / 2` for integers and saturates instead of wrapping.
        val headroom = saturatedAdd(sql, sql / HEADROOM_DENOMINATOR)
        return saturatedAdd(BASE_BYTES, headroom)
    }

    /**
     * The status message of an insufficient decision — the plan's exact
     * wording, so the dashboard, the wizard and `logcat -s DbRestore` all show
     * the same thing and the tester knows a retry resumes instead of restarting.
     */
    fun failureMessage(decision: Decision): String =
        "not enough free space: need ~${gigabytes(decision.requiredBytes)}, " +
            "have ${gigabytes(decision.freeBytes)} — free space and retry " +
            "(the import resumes)"

    /** `bytes` as `"X.Y GB"` in GiB tenths (integer math: no locale, no rounding drift). */
    fun gigabytes(bytes: Long): String {
        val safe = maxOf(0L, bytes)
        val whole = safe / GIB
        val tenths = (safe % GIB) * 10 / GIB
        return "$whole.$tenths GB"
    }

    private fun saturatedAdd(a: Long, b: Long): Long =
        if (a > Long.MAX_VALUE - b) Long.MAX_VALUE else a + b

    private fun saturatedMultiply(a: Long, b: Long): Long {
        if (a <= 0L || b <= 0L) return 0L
        return if (a > Long.MAX_VALUE / b) Long.MAX_VALUE else a * b
    }
}
