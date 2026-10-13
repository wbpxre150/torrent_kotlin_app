package com.torrent.app.config

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TransmissionApplyModeTest {
    private fun tx() = TransmissionSettings("u", "password", 51413, 100, 50, 10, 5)

    private fun app() = AppConfig(
        wizardCompleted = true,
        transmission = tx(),
        ftp = FtpSettings("u", "password", 2121, true),
        crawler = CrawlerSettings(),
        backup = BackupSettings(),
        dataSubpath = "Dl",
    )

    @Test fun speedOnlyChange_doesNotNeedRestart() {
        val a = app()
        assertFalse(TransmissionApplyMode.needsRestart(a, a.copy(transmission = a.transmission.copy(speedLimitDown = 200))))
    }

    @Test fun altSpeedChange_doesNotNeedRestart() {
        val a = app()
        assertFalse(TransmissionApplyMode.needsRestart(a, a.copy(transmission = a.transmission.copy(altSpeedUp = 99))))
    }

    @Test fun usernameChange_needsRestart() {
        val a = app()
        assertTrue(TransmissionApplyMode.needsRestart(a, a.copy(transmission = a.transmission.copy(username = "other"))))
    }

    @Test fun passwordChange_needsRestart() {
        val a = app()
        assertTrue(TransmissionApplyMode.needsRestart(a, a.copy(transmission = a.transmission.copy(password = "newpass"))))
    }

    @Test fun peerPortChange_needsRestart() {
        val a = app()
        assertTrue(TransmissionApplyMode.needsRestart(a, a.copy(transmission = a.transmission.copy(peerPort = 51415))))
    }

    @Test fun dataSubpathChange_needsRestart() {
        val a = app()
        assertTrue(TransmissionApplyMode.needsRestart(a, a.copy(dataSubpath = "Other")))
    }

    @Test fun identical_needsNoRestart() {
        val a = app()
        assertFalse(TransmissionApplyMode.needsRestart(a, a))
    }
}
