package com.torrent.app.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure tests for the [DbObjectKey] convention: build/parse round-trip,
 * first-run START/END, and rejection of malformed keys (what Stage 4
 * restore ordering relies on).
 */
class DbObjectKeyTest {

    @Test fun buildProducesConventionalKey() {
        assertEquals(
            "db/incremental_20260102_0-57.sql.gz",
            DbObjectKey.build("db", "20260102", 0, 57).key,
        )
        assertEquals(
            "custom/incremental_19991231_58-1000000.sql.gz",
            DbObjectKey.build("custom", "19991231", 58, 1000000).key,
        )
    }

    @Test fun parseRoundTrips() {
        val key = DbObjectKey.build("db", "20260102", 0, 57)
        assertEquals(key, DbObjectKey.parse(key.key))
        val big = DbObjectKey.build("db", "20380119", 9_007_199_254_740_993, 9_223_372_036_854_775_807L)
        assertEquals(big, DbObjectKey.parse(big.key))
    }

    /**
     * The class-index sidecar key of a range: same prefix/date/range, `.idx.gz`
     * instead of `.sql.gz` — and invisible to [DbObjectKey.parse], so sidecars
     * sharing the prefix never disturb the dump chain's gap/overlap/sort rules.
     */
    @Test fun sidecarKeyNamesTheClassIndexNextToTheDump() {
        val key = DbObjectKey.build("db", "20260102", 0, 57)
        assertEquals("db/incremental_20260102_0-57.idx.gz", key.sidecarKey)
        assertEquals("db/incremental_20260102_0-57.sql.gz", key.key)
        // Same prefix and range, only the suffix differs.
        assertEquals(
            key.sidecarKey,
            key.key.removeSuffix(".sql.gz") + ".idx.gz",
        )
        assertNull(DbObjectKey.parse(key.sidecarKey))
        assertEquals(
            "custom/incremental_19991231_58-1000000.idx.gz",
            DbObjectKey.build("custom", "19991231", 58, 1000000).sidecarKey,
        )
    }

    @Test fun firstRunKeyHasZeroStart() {
        // last_torrent_id = -1 → START = 0, END = max.
        val key = DbObjectKey.parse("db/incremental_20260102_0-3.sql.gz")!!
        assertEquals(0L, key.start)
        assertEquals(3L, key.end)
        assertEquals("20260102", key.date)
        assertEquals("db", key.prefix)
    }

    @Test fun parseRejectsMalformedKeys() {
        assertNull(DbObjectKey.parse(""))
        assertNull(DbObjectKey.parse("incremental_20260102_0-57.sql.gz")) // no prefix/
        assertNull(DbObjectKey.parse("a/b/incremental_20260102_0-57.sql.gz")) // nested prefix
        assertNull(DbObjectKey.parse("db/incremental_20260102_0-57.sql.zst")) // wrong suffix
        assertNull(DbObjectKey.parse("db/incremental_20260102_0-57.sql")) // missing .gz
        assertNull(DbObjectKey.parse("db/incremental_20260102_0-57.sql.gz.bak")) // trailing junk
        assertNull(DbObjectKey.parse("db/incremental_2026012_0-57.sql.gz")) // 7-digit date
        assertNull(DbObjectKey.parse("db/incremental_202601022_0-57.sql.gz")) // 9-digit date
        assertNull(DbObjectKey.parse("db/incremental_20260102_0.sql.gz")) // missing end
        assertNull(DbObjectKey.parse("db/incremental_20260102__57.sql.gz")) // missing start
        assertNull(DbObjectKey.parse("db/incremental_20260102_-1-57.sql.gz")) // negative
        assertNull(DbObjectKey.parse("db/incremental_20260102_0--1.sql.gz")) // negative end
        assertNull(DbObjectKey.parse("db/incremental_20260102_01-57.sql.gz")) // leading zero
        assertNull(DbObjectKey.parse("db/incremental_20260102_10-5.sql.gz")) // start > end
        assertNull(DbObjectKey.parse("db/incremental_20260102_a-57.sql.gz")) // non-numeric
        // 'DB/...' is a VALID key (any non-slash prefix); only shape breaks reject.
        assertNull(DbObjectKey.parse("db/ incremental_20260102_0-57.sql.gz")) // space in prefix
    }

    @Test fun parseAcceptsSingleDigitAndLargeIds() {
        assertEquals(
            DbObjectKey("db", "20260102", 5, 6),
            DbObjectKey.parse("db/incremental_20260102_5-6.sql.gz"),
        )
    }

    @Test fun buildRejectsBadPrefixDateOrRange() {
        assertTrue(
            runCatching { DbObjectKey.build("", "20260102", 0, 1) }.isFailure
        )
        assertTrue(
            runCatching { DbObjectKey.build("a/b", "20260102", 0, 1) }.isFailure
        )
        assertTrue(
            runCatching { DbObjectKey.build("db", "2026-01-02", 0, 1) }.isFailure
        )
        assertTrue(
            runCatching { DbObjectKey.build("db", "20260102", -1, 1) }.isFailure
        )
        assertTrue(
            runCatching { DbObjectKey.build("db", "20260102", 2, 1) }.isFailure
        )
    }
}
