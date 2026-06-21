package com.torrent.app

import android.content.Context
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Owns transmission-daemon's config dir under internal storage and generates settings.json
 * with RPC auth on first run. Stage 04 (internal storage only); Stage 05 redirects payload
 * I/O to USB via the SAF fork.
 */
class TransmissionConfig(context: Context) {

    val configDir: File = File(context.filesDir, "transmission")
    val downloadDir: File = File(configDir, "downloads")
    private val settingsFile = File(configDir, "settings.json")

    init {
        configDir.mkdirs()
        downloadDir.mkdirs()
    }

    fun username(): String = USERNAME

    fun password(): String = "test"

    /** Writes settings.json, overwriting any previous so updated password takes effect. */
    fun ensureSettingsJson() {
        settingsFile.writeText(
            buildSettingsJson(username(), ssha1Hash(password()), USB_DOWNLOAD_DIR)
        )
    }

    companion object {
        private const val USERNAME = "torrentapp"
        /** Sentinel download root recognized by the SAF file-backend fork. */
        const val USB_DOWNLOAD_DIR = "/saf/usb"

        /**
         * Compute transmission's SSHA1 password hash: "{" + SHA1_hex(password + salt) + salt.
         * Salt is 8 random chars from the same 64-char alphabet transmission uses.
         * When this format is written to settings.json, transmission detects the "{"
         * prefix and uses the hash directly without re-hashing.
         */
        fun ssha1Hash(password: String): String {
            val salter = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ./"
            val rnd = SecureRandom()
            val salt = StringBuilder(8)
            repeat(8) { salt.append(salter[rnd.nextInt(salter.length)]) }
            val saltStr = salt.toString()
            val sha1hex = MessageDigest.getInstance("SHA-1")
                .digest((password + saltStr).toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
            return "{$sha1hex$saltStr"
        }

        /**
         * Pure builder for transmission's settings.json. Exposed for unit testing (no Android deps).
         * rpc-password should be pre-hashed via [ssha1Hash] before calling; transmission detects the
         * "{" prefix and uses the hash directly without re-hashing or rewriting the file.
         */
        fun buildSettingsJson(username: String, password: String, downloadDir: String): String =
            buildString {
                append("{\n")
                append("  \"rpc-enabled\": true,\n")
                append("  \"rpc-bind-address\": \"0.0.0.0\",\n")
                append("  \"rpc-port\": 9091,\n")
                append("  \"rpc-authentication-required\": true,\n")
                append("  \"rpc-username\": ").append(jsonStr(username)).append(",\n")
                append("  \"rpc-password\": ").append(jsonStr(password)).append(",\n")
                append("  \"rpc-whitelist-enabled\": false,\n")
                append("  \"download-dir\": ").append(jsonStr(downloadDir)).append(",\n")
                append("  \"incomplete-dir-enabled\": false,\n")
                append("  \"rename-partial-files\": false,\n")
                append("  \"watch-dir-enabled\": false,\n")
                append("  \"message-level\": 3,\n")
                append("  \"preallocation\": 0\n")
                append("}\n")
            }

        private fun jsonStr(s: String): String =
            "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
    }
}