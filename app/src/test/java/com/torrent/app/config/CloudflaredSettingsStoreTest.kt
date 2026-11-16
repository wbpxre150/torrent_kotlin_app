package com.torrent.app.config

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CloudflaredSettingsStoreTest {
    private fun store() = ConfigStore(ApplicationProvider.getApplicationContext())

    private fun prefs() =
        ApplicationProvider.getApplicationContext<Context>()
            .getSharedPreferences("app_config", Context.MODE_PRIVATE)

    @Test fun load_defaultsToDisabledWithEmptyTokenAndHostname() {
        val c = store().load().cloudflared
        assertFalse(c.enabled)
        assertEquals("", c.tunnelToken)
        assertEquals("", c.publicHostname)
    }

    @Test fun saveCloudflared_roundTrips() {
        val s = CloudflaredSettings(
            enabled = true,
            tunnelToken = "eyJhIjoiZXhhbXBsZSJ9.tunnel-token",
            publicHostname = "tunnel.example.com",
        )
        store().saveCloudflared(s)
        assertEquals(s, store().load().cloudflared)
    }

    @Test fun saveCloudflared_writesTheDedicatedPrefsKeys() {
        store().saveCloudflared(
            CloudflaredSettings(enabled = true, tunnelToken = "tok", publicHostname = "tunnel.example.com"))
        val p = prefs()
        assertTrue(p.getBoolean("cloudflared_enabled", false))
        assertEquals("tok", p.getString("cloudflared_tunnel_token", null))
        assertEquals("tunnel.example.com", p.getString("cloudflared_public_hostname", null))
    }

    @Test fun load_readsKeysWrittenDirectly() {
        prefs().edit()
            .putBoolean("cloudflared_enabled", true)
            .putString("cloudflared_tunnel_token", "tok")
            .putString("cloudflared_public_hostname", "tunnel.example.com")
            .apply()
        assertEquals(
            CloudflaredSettings(enabled = true, tunnelToken = "tok", publicHostname = "tunnel.example.com"),
            store().load().cloudflared,
        )
    }

    @Test fun save_persistsTheCloudflaredSectionToo() {
        val cfg = store().load().copy(
            cloudflared = CloudflaredSettings(enabled = true, tunnelToken = "tok", publicHostname = "a.example.com"))
        store().save(cfg)
        assertEquals(cfg.cloudflared, store().load().cloudflared)
    }

    @Test fun saveCloudflared_isSectionTargeted() {
        val before = store().load()
        store().saveCloudflared(CloudflaredSettings(enabled = true, tunnelToken = "tok"))
        val after = store().load()
        assertEquals(before.transmission, after.transmission)
        assertEquals(before.ftp, after.ftp)
        assertEquals(before.crawler, after.crawler)
        assertEquals(before.backup, after.backup)
        assertEquals(before.dataSubpath, after.dataSubpath)
    }

    @Test fun clearByEmptyToken_isPersisted() {
        // Turning the tunnel off (or clearing the token) must round-trip, not fall back to a default.
        store().saveCloudflared(CloudflaredSettings(enabled = true, tunnelToken = "tok"))
        store().saveCloudflared(CloudflaredSettings())
        val c = store().load().cloudflared
        assertFalse(c.enabled)
        assertEquals("", c.tunnelToken)
        // Sanity: the raw key holds an empty string, not a stale token.
        assertEquals("", prefs().getString("cloudflared_tunnel_token", null))
        assertNull(prefs().getString("cloudflared_nonexistent_key", null))
    }
}
