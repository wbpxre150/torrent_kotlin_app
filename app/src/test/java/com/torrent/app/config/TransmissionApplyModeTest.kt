package com.torrent.app.config

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TransmissionApplyModeTest {
    private fun tx() = TransmissionSettings("u", "password", 51413, 100, 50, 10, 5, "Dl")

    @Test fun speedOnlyChange_doesNotNeedRestart() {
        assertFalse(TransmissionApplyMode.needsRestart(tx(), tx().copy(speedLimitDown = 200)))
    }
    @Test fun altSpeedChange_doesNotNeedRestart() {
        assertFalse(TransmissionApplyMode.needsRestart(tx(), tx().copy(altSpeedUp = 99)))
    }
    @Test fun usernameChange_needsRestart() {
        assertTrue(TransmissionApplyMode.needsRestart(tx(), tx().copy(username = "other")))
    }
    @Test fun passwordChange_needsRestart() {
        assertTrue(TransmissionApplyMode.needsRestart(tx(), tx().copy(password = "newpass")))
    }
    @Test fun peerPortChange_needsRestart() {
        assertTrue(TransmissionApplyMode.needsRestart(tx(), tx().copy(peerPort = 51415)))
    }
    @Test fun downloadSubpathChange_needsRestart() {
        assertTrue(TransmissionApplyMode.needsRestart(tx(), tx().copy(downloadSubpath = "Other")))
    }
    @Test fun identical_needsNoRestart() {
        assertFalse(TransmissionApplyMode.needsRestart(tx(), tx()))
    }
}