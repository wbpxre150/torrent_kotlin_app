package com.torrent.app.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WizardSubmitTest {
    private fun validForm() = WizardSubmit.Form(
        txUsername = "torrentapp", txPassword = "secret123", peerPort = 51413, dataSubpath = "Downloads",
        ftpUsername = "kodi", ftpPassword = "kodiallday", ftpPort = 2121, ftpWifiOnly = true,
    )

    @Test fun validForm_producesSettings() {
        val o = WizardSubmit.submit(validForm())
        assertTrue(o.ok)
        assertTrue(o.errors.isEmpty())
        assertNotNull(o.transmission)
        assertNotNull(o.ftp)
        assertEquals("torrentapp", o.transmission!!.username)
        assertEquals(2121, o.ftp!!.port)
        assertEquals(0, o.ftp!!.maxDownloadKiBps)
    }

    @Test fun aggregatesTransmissionAndFtpErrors() {
        val f = validForm().copy(txPassword = "x", ftpPort = 0)
        val o = WizardSubmit.submit(f)
        assertFalse(o.ok)
        assertNull(o.transmission)
        assertNull(o.ftp)
        assertTrue(o.errors.any { it.contains("password", true) })
        assertTrue(o.errors.any { it.contains("port", true) })
    }

    @Test fun blankDataDir_passes() {
        assertTrue(WizardSubmit.submit(validForm().copy(dataSubpath = "")).ok)
    }

    @Test fun crawlerEnabledDefaultsToOff() {
        assertFalse(WizardSubmit.submit(validForm()).crawlerEnabled)
    }

    @Test fun crawlerEnabledTruePassedThrough() {
        assertTrue(WizardSubmit.submit(validForm().copy(crawlerEnabled = true)).crawlerEnabled)
    }

    @Test fun zeroPortFailsValidation() {
        val o = WizardSubmit.submit(validForm().copy(ftpPort = 0))
        assertFalse(o.ok)
        assertTrue(o.errors.any { it.contains("port", true) })
    }

    @Test fun dotDotDataDirFailsValidation() {
        val o = WizardSubmit.submit(validForm().copy(dataSubpath = "../etc"))
        assertFalse(o.ok)
        assertTrue(o.errors.any { it.contains("data directory", true) })
    }
}
