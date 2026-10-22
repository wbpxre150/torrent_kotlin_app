package com.torrent.app.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Plain-JUnit tests for [RestoreDiskGuard] (plan 3, Stage 11): the free-space
 * budget of the resumable DB import.
 *
 * `requiredBytes = 2 GiB + 1.5 × (7 × Σ remaining compressed object sizes)` —
 * these pin the floor, the multipliers, the boundary (exactly enough passes, one
 * byte less does not), the remaining-size summation from a start index (which is
 * what a RESUMED run uses), saturation, and the operator-facing message.
 */
class RestoreDiskGuardTest {

    private val GIB = 1024L * 1024 * 1024
    private val MIB = 1024L * 1024

    @Test fun nothingLeftToImport_needsOnlyTheTwoGibFloor() {
        assertEquals(2L * GIB, RestoreDiskGuard.BASE_BYTES)
        assertEquals(2L * GIB, RestoreDiskGuard.requiredBytes(0))
        assertEquals(2L * GIB, RestoreDiskGuard.requiredBytes(-1))
    }

    @Test fun requiredIsTheFloorPlusOneAndAHalfTimesSevenTimesCompressed() {
        // 100 MiB of gzip → 700 MiB of SQL → 1050 MiB with the 50 % headroom.
        assertEquals(2L * GIB + 1050 * MIB, RestoreDiskGuard.requiredBytes(100 * MIB))
        assertEquals(7L, RestoreDiskGuard.SQL_BYTES_PER_COMPRESSED_BYTE)
    }

    @Test fun checkPassesExactlyAtTheBoundaryAndFailsOneByteBelow() {
        val required = RestoreDiskGuard.requiredBytes(10 * MIB)
        val atBoundary = RestoreDiskGuard.check(10 * MIB, required)
        assertTrue(atBoundary.sufficient)
        assertEquals(required, atBoundary.requiredBytes)
        assertEquals(required, atBoundary.freeBytes)

        val oneByteShort = RestoreDiskGuard.check(10 * MIB, required - 1)
        assertFalse(oneByteShort.sufficient)
        assertEquals(required, oneByteShort.requiredBytes)

        assertTrue(RestoreDiskGuard.check(10 * MIB, Long.MAX_VALUE).sufficient)
    }

    @Test fun theFullChainWouldNotFitTheMeasuredFreeVolume() {
        // The measured chain: 36.7 GB of gzip objects → ~257 GB of SQL → ~385 GB
        // required, against ~211 GB free on the volume shared with transmission
        // and FTP. This is the case the guard exists to refuse UP FRONT.
        val chain = 36_700_000_000L
        val decision = RestoreDiskGuard.check(chain, 211_000_000_000L)
        assertFalse(decision.sufficient)
        assertTrue(
            "required=${decision.requiredBytes}",
            decision.requiredBytes > 380_000_000_000L)
    }

    @Test fun remainingSizesSumOnlyTheObjectsFromTheStartIndexOn() {
        val sizes = listOf<Long?>(10L, 20L, null, 30L)
        assertEquals(60L, RestoreDiskGuard.remainingCompressedBytes(sizes, 0))
        // An object the LIST gave no size for contributes nothing.
        assertEquals(50L, RestoreDiskGuard.remainingCompressedBytes(sizes, 1))
        assertEquals(30L, RestoreDiskGuard.remainingCompressedBytes(sizes, 3))
        assertEquals(0L, RestoreDiskGuard.remainingCompressedBytes(sizes, 4))
        assertEquals(0L, RestoreDiskGuard.remainingCompressedBytes(sizes, 99))
        // A negative index (a marker rewound past the chain start) means "all".
        assertEquals(60L, RestoreDiskGuard.remainingCompressedBytes(sizes, -5))
    }

    @Test fun aResumedRunOnlyCountsTheObjectsItStillHasToImport() {
        val sizes = List(10) { 5L * MIB }
        val fresh = RestoreDiskGuard.check(
            RestoreDiskGuard.remainingCompressedBytes(sizes, 0), 100L * GIB)
        val resumed = RestoreDiskGuard.check(
            RestoreDiskGuard.remainingCompressedBytes(sizes, 8), 100L * GIB)

        assertTrue(fresh.sufficient)
        assertTrue(resumed.sufficient)
        assertEquals(RestoreDiskGuard.requiredBytes(50 * MIB), fresh.requiredBytes)
        assertEquals(RestoreDiskGuard.requiredBytes(10 * MIB), resumed.requiredBytes)
        assertTrue(
            "resumed=${resumed.requiredBytes} fresh=${fresh.requiredBytes}",
            resumed.requiredBytes < fresh.requiredBytes)
    }

    @Test fun saturatedInputsNeverWrapAroundIntoSufficient() {
        assertEquals(Long.MAX_VALUE, RestoreDiskGuard.requiredBytes(Long.MAX_VALUE))
        assertEquals(
            Long.MAX_VALUE,
            RestoreDiskGuard.remainingCompressedBytes(listOf(Long.MAX_VALUE, Long.MAX_VALUE), 0))
        assertFalse(RestoreDiskGuard.check(Long.MAX_VALUE, Long.MAX_VALUE / 2).sufficient)
    }

    @Test fun failureMessageNamesBothNumbersAndSaysTheImportResumes() {
        val decision = RestoreDiskGuard.check(0, GIB)
        assertEquals(
            "not enough free space: need ~2.0 GB, have 1.0 GB — free space and retry " +
                "(the import resumes)",
            RestoreDiskGuard.failureMessage(decision))
    }

    @Test fun gigabytesAreReportedInTenthsOfAGib() {
        assertEquals("0.0 GB", RestoreDiskGuard.gigabytes(0))
        assertEquals("1.0 GB", RestoreDiskGuard.gigabytes(GIB))
        assertEquals("1.5 GB", RestoreDiskGuard.gigabytes(GIB + GIB / 2))
        assertEquals("2.0 GB", RestoreDiskGuard.gigabytes(2 * GIB))
        assertEquals("0.0 GB", RestoreDiskGuard.gigabytes(-1))
    }
}
