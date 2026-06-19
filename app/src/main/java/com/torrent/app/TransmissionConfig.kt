package com.torrent.app

import android.content.Context
import java.io.File
import java.security.SecureRandom

/**
 * Owns transmission-daemon's config dir under internal storage and generates settings.json
 * with RPC auth on first run. Stage 04 (internal storage only); Stage 05 redirects payload
 * I/O to USB via the SAF fork.
 */
class TransmissionConfig(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    val configDir: File = File(context.filesDir, "transmission")
    val downloadDir: File = File(configDir, "downloads")
    private val settingsFile = File(configDir, "settings.json")

    init {
        configDir.mkdirs()
        downloadDir.mkdirs()
    }

    fun username(): String = USERNAME

    fun password(): String {
        prefs.getString(KEY_PASSWORD, null)?.let { return it }
        val generated = randomString(PASSWORD_LEN)
        prefs.edit().putString(KEY_PASSWORD, generated).apply()
        return generated
    }

    /** Writes settings.json only if absent, so transmission's own rewrites are preserved. */
    fun ensureSettingsJson() {
        if (settingsFile.exists()) return
        settingsFile.writeText(
            buildSettingsJson(username(), password(), downloadDir.absolutePath)
        )
    }

    companion object {
        private const val PREFS = "transmission_creds"
        private const val KEY_PASSWORD = "rpc_password"
        private const val USERNAME = "torrentapp"
        private const val PASSWORD_LEN = 24
        private val PASSWORD_CHARS =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789".toCharArray()

        /**
         * Pure builder for transmission's settings.json. Exposed for unit testing (no Android deps).
         * rpc-password is written plaintext; transmission hashes it on first run and rewrites the file.
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
                append("  \"incomplete-dir\": ").append(jsonStr(downloadDir)).append(",\n")
                append("  \"incomplete-dir-enabled\": true,\n")
                append("  \"watch-dir-enabled\": false,\n")
                append("  \"message-level\": 3\n")
                append("}\n")
            }

        private fun jsonStr(s: String): String =
            "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

        private fun randomString(len: Int): String {
            val rnd = SecureRandom()
            val sb = StringBuilder(len)
            repeat(len) { sb.append(PASSWORD_CHARS[rnd.nextInt(PASSWORD_CHARS.size)]) }
            return sb.toString()
        }
    }
}