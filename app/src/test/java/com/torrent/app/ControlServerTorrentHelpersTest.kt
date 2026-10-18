package com.torrent.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import com.torrent.app.ControlServer.Companion.TorrentPathResult
import org.junit.Test

/**
 * Pure tests for the torrent-detail path/validation helpers (torrent-page
 * stage 2). These are the whole validation surface of the three new forward
 * routes: the hash rule mirrors the crawler's C `tp_get_hash_param`, the
 * offset/limit clamping mirrors its `/torrent/files` handler, and the read
 * timeout rides on the result so the slow /refresh fetch is observable without
 * a server.
 */
class ControlServerTorrentHelpersTest {

    private val hash = "abcdef0123456789abcdef0123456789abcdef01"
    private val upper = hash.uppercase()

    private fun ok(result: TorrentPathResult): TorrentPathResult.Ok =
        result as? TorrentPathResult.Ok
            ?: throw AssertionError("expected Ok, got $result")

    private fun bad(result: TorrentPathResult): TorrentPathResult.Bad =
        result as? TorrentPathResult.Bad
            ?: throw AssertionError("expected Bad, got $result")

    @Test fun hash_acceptsExactlyFortyHexChars() {
        assertEquals(hash, ControlServer.torrentHash(hash))
        assertEquals(
            "00112233445566778899aabbccddeeff00112233",
            ControlServer.torrentHash("00112233445566778899aabbccddeeff00112233"),
        )
    }

    @Test fun hash_lowercasesUppercaseInput() {
        assertEquals(hash, ControlServer.torrentHash(upper))
        assertEquals(hash, ControlServer.torrentHash("ABCDEF0123456789ABCDEF0123456789ABCDEF01"))
    }

    @Test fun hash_rejectsWrongLengthEmptyAndNonHex() {
        assertNull(ControlServer.torrentHash(hash.dropLast(1)))   // 39
        assertNull(ControlServer.torrentHash(hash + "a"))         // 41
        assertNull(ControlServer.torrentHash("g" + hash.drop(1))) // non-hex
        assertNull(ControlServer.torrentHash(""))
        assertNull(ControlServer.torrentHash(null))
    }

    @Test fun infoPath_forwardsTheLowercasedHash() {
        val r = ok(ControlServer.buildTorrentInfoPath(upper))
        assertEquals("/torrent/info?hash=$hash", r.path)
        assertEquals(ControlServer.TORRENT_READ_TIMEOUT_MS, r.readTimeoutMs)
    }

    @Test fun infoPath_reportsAnInvalidHashAsTheOnlyBadField() {
        assertEquals("hash", bad(ControlServer.buildTorrentInfoPath("xyz")).field)
        assertEquals("hash", bad(ControlServer.buildTorrentInfoPath(null)).field)
    }

    @Test fun filesPath_defaultsToTheFirstBatchOfFifty() {
        val r = ok(ControlServer.buildTorrentFilesPath(hash))
        assertEquals("/torrent/files?hash=$hash&offset=0&limit=50", r.path)
        assertEquals(ControlServer.TORRENT_READ_TIMEOUT_MS, r.readTimeoutMs)
    }

    @Test fun filesPath_clampsANegativeOffsetToZero() {
        assertEquals(
            "/torrent/files?hash=$hash&offset=0&limit=50",
            ok(ControlServer.buildTorrentFilesPath(hash, offset = -7)).path,
        )
    }

    @Test fun filesPath_clampsAnOutOfRangeLimitToFifty() {
        for (limit in listOf(0, -1, 201, 10_000)) {
            assertEquals(
                "limit $limit must fall back to 50",
                "/torrent/files?hash=$hash&offset=0&limit=50",
                ok(ControlServer.buildTorrentFilesPath(hash, limit = limit)).path,
            )
        }
        assertEquals(
            "/torrent/files?hash=$hash&offset=0&limit=200",
            ok(ControlServer.buildTorrentFilesPath(hash, limit = 200)).path,
        )
        assertEquals(
            "/torrent/files?hash=$hash&offset=0&limit=7",
            ok(ControlServer.buildTorrentFilesPath(hash, limit = 7)).path,
        )
    }

    @Test fun filesPath_encodesAndDropsTheFilter() {
        assertEquals(
            "/torrent/files?hash=$hash&offset=0&limit=50&q=a+b%26c",
            ok(ControlServer.buildTorrentFilesPath(hash, q = "a b&c")).path,
        )
        // Filters shorter than the crawler's trigram minimum are dropped, not
        // rejected — a 2-char box still returns the unfiltered first batch.
        assertEquals(
            "/torrent/files?hash=$hash&offset=50&limit=50",
            ok(ControlServer.buildTorrentFilesPath(hash, offset = 50, q = "ab")).path,
        )
    }

    @Test fun filesPath_onlyTheHashIsAValidationFailure() {
        // A negative offset / silly limit / short filter are clamped, not errors.
        val r = ControlServer.buildTorrentFilesPath(hash, offset = -1, limit = 0, q = "x")
        assertTrue(r is TorrentPathResult.Ok)
        assertEquals("hash", bad(ControlServer.buildTorrentFilesPath("nope")).field)
    }

    @Test fun refreshPath_selectsTheSlowFetchVariant() {
        val r = ok(ControlServer.buildTorrentRefreshPath(upper))
        assertEquals("/refresh?hash=$hash", r.path)
        assertEquals(30_000, r.readTimeoutMs)
        assertEquals(ControlServer.TORRENT_REFRESH_TIMEOUT_MS, r.readTimeoutMs)
        assertNotEquals(ControlServer.TORRENT_READ_TIMEOUT_MS, r.readTimeoutMs)
        assertEquals("hash", bad(ControlServer.buildTorrentRefreshPath("")).field)
    }
}
