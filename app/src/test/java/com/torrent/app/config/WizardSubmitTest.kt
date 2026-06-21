package com.torrent.app.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WizardSubmitTest {
    private fun validForm() = WizardSubmit.Form(
        txUsername = "torrentapp", txPassword = "secret123", peerPort = 51413, downloadSubpath = "Downloads",
        ftpUsername = "kodi", ftpPassword = "kodiallday", ftpPort = 2121, ftpWifiOnly = true, ftpExposedRoot = "Media",
    )

    @Test fun validForm_producesSettings() {
        val o = WizardSubmit.submit(validForm())
        assertTrue(o.ok)
        assertTrue(o.errors.isEmpty())
        assertNotNull(o.transmission)
        assertNotNull(o.ftp)
        assertEquals("torrentapp", o.transmission!!.username)
        assertEquals("Media", o.ftp!!.exposedRoot)
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

    @Test fun blankDownloadSubpath_passes() {
        assertTrue(WizardSubmit.submit(validForm().copy(downloadSubpath = "")).ok)
    }

    @Test fun zeroPortFailsValidation() {
        val o = WizardSubmit.submit(validForm().copy(ftpPort = 0))
        assertFalse(o.ok)
        assertTrue(o.errors.any { it.contains("port", true) })
    }
}