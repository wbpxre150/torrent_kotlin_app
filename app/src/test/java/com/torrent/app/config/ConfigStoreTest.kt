package com.torrent.app.config

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ConfigStoreTest {
    private fun store() = ConfigStore(ApplicationProvider.getApplicationContext())

    @Test fun load_returnsDefaultsWhenEmpty() {
        val c = store().load()
        assertFalse(c.wizardCompleted)
        assertEquals(ConfigDefaults.TRANSMISSION.username, c.transmission.username)
        assertEquals("test", c.transmission.password)
        assertEquals(51413, c.transmission.peerPort)
    }

    @Test fun saveTransmission_persistsAndReloads() {
        val s = store()
        val tx = TransmissionSettings("user1", "pass1234", 51414, 100, 50, 10, 5, "Dl")
        s.saveTransmission(tx)
        val loaded = s.load().transmission
        assertEquals("user1", loaded.username)
        assertEquals("pass1234", loaded.password)
        assertEquals(51414, loaded.peerPort)
        assertEquals(100, loaded.speedLimitDown)
        assertEquals("Dl", loaded.downloadSubpath)
    }

    @Test fun setWizardCompleted_persists() {
        val s = store()
        assertFalse(s.load().wizardCompleted)
        s.setWizardCompleted()
        assertTrue(s.load().wizardCompleted)
    }

    @Test fun snapshotAndRestoreLastKnownGoodTransmission() {
        val s = store()
        assertNull(s.restoreLastKnownGoodTransmission())
        s.snapshotLastKnownGood(TransmissionSettings("u", "p", 51413, 0, 0, 0, 0, ""))
        val restored = s.restoreLastKnownGoodTransmission()
        assertNotNull(restored)
        assertEquals("u", restored!!.username)
        assertEquals("p", restored.password)
    }

    @Test fun clearLastKnownGood_removesSnapshot() {
        val s = store()
        s.snapshotLastKnownGood(TransmissionSettings("u", "p", 51413, 0, 0, 0, 0, ""))
        s.clearLastKnownGood()
        assertNull(s.restoreLastKnownGoodTransmission())
    }
}