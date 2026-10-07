package com.torrent.app.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigValidatorTest {
    private fun validTx() = TransmissionSettings(
        username = "torrentapp", password = "secret123",
        peerPort = 51413,
        speedLimitDown = 0, speedLimitUp = 0,
        altSpeedDown = 50, altSpeedUp = 10,
        downloadSubpath = "Downloads",
    )
    @Test fun validTransmission_passes() {
        assertTrue(ConfigValidator.validateTransmission(validTx()).ok)
    }
    @Test fun emptyUsername_fails() {
        val r = ConfigValidator.validateTransmission(validTx().copy(username = ""))
        assertFalse(r.ok); assertTrue(r.errors.any { it.contains("username", true) })
    }
    @Test fun shortPassword_fails() {
        val r = ConfigValidator.validateTransmission(validTx().copy(password = "ab"))
        assertFalse(r.ok); assertTrue(r.errors.any { it.contains("password", true) })
    }
    @Test fun outOfRangePeerPort_fails() {
        val r = ConfigValidator.validateTransmission(validTx().copy(peerPort = 70000))
        assertFalse(r.ok); assertTrue(r.errors.any { it.contains("peer", true) })
    }
    @Test fun negativeSpeedLimit_fails() {
        val r = ConfigValidator.validateTransmission(validTx().copy(speedLimitDown = -1))
        assertFalse(r.ok)
    }
    @Test fun downloadSubpathWithDotDot_fails() {
        val r = ConfigValidator.validateTransmission(validTx().copy(downloadSubpath = "../etc"))
        assertFalse(r.ok); assertTrue(r.errors.any { it.contains("download", true) })
    }
    @Test fun leadingSlashSubpath_fails() {
        assertFalse(ConfigValidator.validateTransmission(validTx().copy(downloadSubpath = "/Downloads")).ok)
    }
    @Test fun emptySubpath_passes() {
        assertTrue(ConfigValidator.validateTransmission(validTx().copy(downloadSubpath = "")).ok)
    }
}