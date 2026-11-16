package com.torrent.app.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudflaredSettingsValidatorTest {
    @Test fun defaults_areDisabledWithEmptyTokenAndHostname() {
        val c = CloudflaredSettings()
        assertFalse(c.enabled)
        assertEquals("", c.tunnelToken)
        assertEquals("", c.publicHostname)
    }

    @Test fun disabled_emptyValues_pass() {
        assertTrue(ConfigValidator.validateCloudflared(CloudflaredSettings()).ok)
    }

    @Test fun enabled_withToken_passes() {
        val r = ConfigValidator.validateCloudflared(
            CloudflaredSettings(enabled = true, tunnelToken = "eyJhIjoiZXhhbXBsZSJ9"))
        assertTrue(r.ok)
        assertTrue(r.errors.isEmpty())
    }

    @Test fun enabled_blankToken_fails() {
        for (token in listOf("", "   ")) {
            val r = ConfigValidator.validateCloudflared(
                CloudflaredSettings(enabled = true, tunnelToken = token))
            assertFalse("blank token \"$token\" must be rejected when enabled", r.ok)
            assertTrue(r.errors.any { it.contains("tunnel token", true) })
        }
    }

    @Test fun disabled_blankToken_passes() {
        // The toggle can be turned off (and the token cleared) without the form erroring.
        assertTrue(ConfigValidator.validateCloudflared(
            CloudflaredSettings(enabled = false, tunnelToken = "")).ok)
    }

    @Test fun validBareHostname_passes() {
        for (host in listOf("tunnel.example.com", "abc-def.example.co.uk", "a1.b2")) {
            val r = ConfigValidator.validateCloudflared(
                CloudflaredSettings(enabled = true, tunnelToken = "tok", publicHostname = host))
            assertTrue("\"$host\" must be accepted", r.ok)
        }
    }

    @Test fun blankHostname_passes_evenWhenEnabled() {
        // The hostname is auto-detected while connected; it may be empty until then.
        assertTrue(ConfigValidator.validateCloudflared(
            CloudflaredSettings(enabled = true, tunnelToken = "tok", publicHostname = "  ")).ok)
    }

    @Test fun hostnameWithScheme_fails() {
        val r = ConfigValidator.validateCloudflared(
            CloudflaredSettings(publicHostname = "https://tunnel.example.com"))
        assertFalse(r.ok)
        assertTrue(r.errors.any { it.contains("public hostname", true) })
    }

    @Test fun hostnameWithPath_fails() {
        assertFalse(ConfigValidator.validateCloudflared(
            CloudflaredSettings(publicHostname = "tunnel.example.com/settings")).ok)
    }

    @Test fun hostnameWithPort_fails() {
        assertFalse(ConfigValidator.validateCloudflared(
            CloudflaredSettings(publicHostname = "tunnel.example.com:443")).ok)
    }

    @Test fun hostnameWithSpace_fails() {
        assertFalse(ConfigValidator.validateCloudflared(
            CloudflaredSettings(publicHostname = "tunnel example.com")).ok)
    }

    @Test fun hostnameWithUnderscoreOrTrailingDot_fails() {
        assertFalse(ConfigValidator.validateCloudflared(
            CloudflaredSettings(publicHostname = "tunnel_example.com")).ok)
        assertFalse(ConfigValidator.validateCloudflared(
            CloudflaredSettings(publicHostname = "tunnel.example.com.")).ok)
        assertFalse(ConfigValidator.validateCloudflared(
            CloudflaredSettings(publicHostname = "-tunnel.example.com")).ok)
    }

    @Test fun badHostnameIsReportedEvenWhenDisabled() {
        // A stored-but-malformed hostname is still surfaced so the form can fix it.
        val r = ConfigValidator.validateCloudflared(
            CloudflaredSettings(enabled = false, publicHostname = "not a host"))
        assertFalse(r.ok)
    }

    @Test fun validHostname_helperMatchesTheValidator() {
        assertTrue(ConfigValidator.validHostname("tunnel.example.com"))
        assertTrue(ConfigValidator.validHostname("  tunnel.example.com  "))
        assertFalse(ConfigValidator.validHostname(""))
        assertFalse(ConfigValidator.validHostname("http://tunnel.example.com"))
        assertFalse(ConfigValidator.validHostname("tunnel.example.com:443"))
    }
}
