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
    )

    @Test fun validForm_producesSettings() {
        val o = WizardSubmit.submit(validForm())
        assertTrue(o.ok)
        assertTrue(o.errors.isEmpty())
        assertNotNull(o.transmission)
        assertEquals("torrentapp", o.transmission!!.username)
    }

    @Test fun aggregatesTransmissionErrors() {
        val f = validForm().copy(txPassword = "x")
        val o = WizardSubmit.submit(f)
        assertFalse(o.ok)
        assertNull(o.transmission)
        assertTrue(o.errors.any { it.contains("password", true) })
    }

    @Test fun blankDownloadSubpath_passes() {
        assertTrue(WizardSubmit.submit(validForm().copy(downloadSubpath = "")).ok)
    }

    @Test fun dotDotSubpathFailsValidation() {
        val o = WizardSubmit.submit(validForm().copy(downloadSubpath = "../etc"))
        assertFalse(o.ok)
        assertTrue(o.errors.any { it.contains("download", true) })
    }
}
