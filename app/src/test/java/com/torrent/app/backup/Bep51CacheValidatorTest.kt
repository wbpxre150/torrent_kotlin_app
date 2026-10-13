package com.torrent.app.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure tests for [Bep51CacheValidator]: the v2 layout (12-byte header +
 * 39-byte records + optional 32-byte SHA-256 trailer), the trailer check,
 * and the legacy/legacy-v1 and size rejections.
 */
class Bep51CacheValidatorTest {

    @Test fun validV2WithoutTrailer() {
        val check = Bep51CacheValidator.validate(buildBep51Cache(nodeCount = 7))
        assertEquals(Bep51CacheCheck.Valid(7, hasTrailer = false), check)
    }

    @Test fun validV2WithTrailer() {
        val check = Bep51CacheValidator.validate(buildBep51Cache(nodeCount = 3, withTrailer = true))
        assertEquals(Bep51CacheCheck.Valid(3, hasTrailer = true), check)
    }

    @Test fun emptyButValid_n0() {
        assertEquals(
            Bep51CacheCheck.Valid(0, hasTrailer = false),
            Bep51CacheValidator.validate(buildBep51Cache(nodeCount = 0)))
        assertEquals(
            Bep51CacheCheck.Valid(0, hasTrailer = true),
            Bep51CacheValidator.validate(buildBep51Cache(nodeCount = 0, withTrailer = true)))
    }

    @Test fun corruptTrailerRejected() {
        val check = Bep51CacheValidator.validate(
            buildBep51Cache(nodeCount = 2, withTrailer = true, corruptTrailer = true))
        assertTrue(check is Bep51CacheCheck.Invalid)
        assertEquals("trailer checksum mismatch", (check as Bep51CacheCheck.Invalid).reason)
    }

    @Test fun truncatedSizeRejected() {
        // Drop 10 bytes off a 5-node file: 12 + 5*39 = 207 -> 197 (no trailer
        // and no valid trailer size).
        val full = buildBep51Cache(nodeCount = 5)
        assertEquals(207, full.size)
        val check = Bep51CacheValidator.validate(full.copyOfRange(0, 197))
        assertTrue(check is Bep51CacheCheck.Invalid)
        assertTrue((check as Bep51CacheCheck.Invalid).reason.startsWith("bad size"))
    }

    @Test fun trailingJunkRejected() {
        val check = Bep51CacheValidator.validate(buildBep51Cache(nodeCount = 1) + byteArrayOf(1, 2, 3))
        assertTrue(check is Bep51CacheCheck.Invalid)
        assertTrue((check as Bep51CacheCheck.Invalid).reason.startsWith("bad size"))
    }

    @Test fun badMagicRejected() {
        val check = Bep51CacheValidator.validate(buildBep51Cache(nodeCount = 1, magic = "XEP1"))
        assertTrue(check is Bep51CacheCheck.Invalid)
        assertTrue((check as Bep51CacheCheck.Invalid).reason.startsWith("bad magic"))
    }

    @Test fun version1LegacyRejected() {
        // Version 1 used 26-byte records; the header alone must fail the check.
        val check = Bep51CacheValidator.validate(
            buildBep51Cache(nodeCount = 4, version = 1, recordSize = 26))
        assertTrue(check is Bep51CacheCheck.Invalid)
        assertTrue(
            (check as Bep51CacheCheck.Invalid).reason.startsWith("unsupported version 1"))
    }

    @Test fun tooSmallRejected() {
        for (n in 0 until 12) {
            val check = Bep51CacheValidator.validate(ByteArray(n))
            assertTrue("size $n", check is Bep51CacheCheck.Invalid)
            assertTrue((check as Bep51CacheCheck.Invalid).reason.startsWith("file is too small"))
        }
    }
}
