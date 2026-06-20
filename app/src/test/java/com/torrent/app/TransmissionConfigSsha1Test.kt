package com.torrent.app

import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test
import java.security.MessageDigest

class TransmissionConfigSsha1Test {

    @Test fun ssha1Hash_startsBraceAndHasCorrectLength() {
        val hash = TransmissionConfig.ssha1Hash("test")
        assertTrue("hash must start with '{'", hash.startsWith("{"))
        // Format: { + 40 hex chars + 8 salt chars = 49 chars total
        assertEquals(49, hash.length)
    }

    @Test fun ssha1Hash_usesValidHexAndSaltChars() {
        val hash = TransmissionConfig.ssha1Hash("mypassword")
        val hexPart = hash.substring(1, 41) // after '{', 40 chars
        val saltPart = hash.substring(41)   // remaining 8 chars
        assertTrue("hex part must be 40 lowercase hex chars",
            hexPart.matches(Regex("[0-9a-f]{40}")))
        assertTrue("salt part must be 8 chars from transmission's alphabet",
            saltPart.matches(Regex("[0-9a-zA-Z./]{8}")))
    }

    @Test fun ssha1Hash_producesCorrectSha1Digest() {
        // Verify the hash matches the SSHA1 format: SHA1(password + salt) in hex
        val hash = TransmissionConfig.ssha1Hash("test")
        val salt = hash.substring(41) // extract 8-char salt
        val expectedSha1 = MessageDigest.getInstance("SHA-1")
            .digest(("test" + salt).toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        assertEquals("{$expectedSha1$salt", hash)
    }

    @Test fun ssha1Hash_differentCallsProduceDifferentSalts() {
        val hash1 = TransmissionConfig.ssha1Hash("test")
        val hash2 = TransmissionConfig.ssha1Hash("test")
        // Same password, different random salts -> different hashes
        assertTrue("two calls with same password should produce different hashes",
            hash1 != hash2)
        // But both should validate the same password
        val salt1 = hash1.substring(41)
        val salt2 = hash2.substring(41)
        val sha1_1 = MessageDigest.getInstance("SHA-1")
            .digest(("test" + salt1).toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        val sha1_2 = MessageDigest.getInstance("SHA-1")
            .digest(("test" + salt2).toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        assertEquals("{$sha1_1$salt1", hash1)
        assertEquals("{$sha1_2$salt2", hash2)
    }

    @Test fun ensureSettingsJson_writesHashedPasswordNotPlaintext() {
        // Verify that buildSettingsJson when given an ssha1Hash output
        // produces valid JSON with the hashed password
        val hashed = TransmissionConfig.ssha1Hash("test")
        val json = TransmissionConfig.buildSettingsJson("torrentapp", hashed, "/saf/usb")
        assertTrue("json should contain the hashed password with brace prefix",
            json.contains("\"rpc-password\": \"$hashed\""))
        // The hash should NOT contain the plaintext "test" as the password value
    }
}
