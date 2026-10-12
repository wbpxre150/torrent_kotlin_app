package com.torrent.app

import android.content.Context
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import com.torrent.app.config.ConfigStore
import org.json.JSONObject

/**
 * Owns transmission-daemon's config dir under internal storage and generates settings.json
 * with RPC auth on first run. Stage 04 (internal storage only); Stage 05 redirects payload
 * I/O to USB via the SAF fork.
 */
class TransmissionConfig(context: Context, private val store: ConfigStore) {

    val configDir: File = File(context.filesDir, "transmission")
    val downloadDir: File = File(configDir, "downloads")
    private val settingsFile = File(configDir, "settings.json")

    init {
        configDir.mkdirs()
        downloadDir.mkdirs()
    }

    fun username(): String = store.load().transmission.username
    fun password(): String = store.load().transmission.password

    /** Writes settings.json from the current ConfigStore settings (shared data dir, Stage 11). */
    fun ensureSettingsJson() {
        val cfg = store.load()
        val s = cfg.transmission
        val downloadDir = if (cfg.dataSubpath.isEmpty()) USB_DOWNLOAD_DIR
                          else "$USB_DOWNLOAD_DIR/${cfg.dataSubpath}"
        val managedJson = buildSettingsJson(
            s.username, ssha1Hash(s.password), downloadDir,
            s.peerPort, s.speedLimitDown, s.speedLimitUp, s.altSpeedDown, s.altSpeedUp,
        )
        val existingText = if (settingsFile.exists()) settingsFile.readText() else null
        settingsFile.writeText(mergeSettingsJson(existingText, managedJson))
    }

    companion object {
        /** Sentinel download root recognized by the SAF file-backend fork. */
        const val USB_DOWNLOAD_DIR = "/saf/usb"

        /**
         * Merge the on-disk settings.json with the managed settings this app emits.
         *
         * The app owns the keys [buildSettingsJson] emits; the daemon owns everything else
         * (session state set via the WebUI/RPC, e.g. alt-speed-enabled, download-queue-size,
         * ratio-limit). A full overwrite on every start would drop the daemon's keys and
         * re-default them, so we start from the existing object and only overwrite the
         * managed keys onto it (existing unmanaged keys are preserved verbatim, managed keys win).
         *
         * [existingText] is the current on-disk text (null if the file is absent). If it is
         * null/blank or does not parse as a JSON object, we fall back to fresh generation and
         * return [managedJson] unchanged.
         *
         * Returns the merged object with 2-space indentation (matching [buildSettingsJson]).
         */
        fun mergeSettingsJson(existingText: String?, managedJson: String): String {
            val existing = parseJsonObject(existingText) ?: return managedJson
            val managed = JSONObject(managedJson)
            val keys = managed.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                existing.put(key, managed.get(key))
            }
            return existing.toString(2)
        }

        private fun parseJsonObject(text: String?): JSONObject? {
            val trimmed = text?.trim()
            if (trimmed.isNullOrEmpty()) return null
            return try {
                JSONObject(trimmed)
            } catch (e: Exception) {
                null
            }
        }

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
         * preallocation: 0=off, 1=fast/sparse (default on; uses fallocate), 2=full.
         */
        fun buildSettingsJson(
            username: String,
            password: String,
            downloadDir: String,
            peerPort: Int = 51413,
            speedLimitDown: Int = 0,
            speedLimitUp: Int = 0,
            altSpeedDown: Int = 0,
            altSpeedUp: Int = 0,
        ): String = buildString {
            append("{\n")
            append("  \"rpc-enabled\": true,\n")
            append("  \"rpc-bind-address\": \"0.0.0.0\",\n")
            append("  \"rpc-port\": 9091,\n")
            append("  \"rpc-authentication-required\": true,\n")
            append("  \"rpc-username\": ").append(jsonStr(username)).append(",\n")
            append("  \"rpc-password\": ").append(jsonStr(password)).append(",\n")
            append("  \"rpc-whitelist-enabled\": false,\n")
            append("  \"peer-port\": ").append(peerPort).append(",\n")
            append("  \"download-dir\": ").append(jsonStr(downloadDir)).append(",\n")
            append("  \"incomplete-dir-enabled\": false,\n")
            append("  \"rename-partial-files\": false,\n")
            append("  \"watch-dir-enabled\": false,\n")
            append("  \"speed-limit-down\": ").append(speedLimitDown).append(",\n")
            append("  \"speed-limit-up\": ").append(speedLimitUp).append(",\n")
            append("  \"alt-speed-down\": ").append(altSpeedDown).append(",\n")
            append("  \"alt-speed-up\": ").append(altSpeedUp).append(",\n")
            append("  \"message-level\": 3,\n")
            append("  \"preallocation\": 1\n") // 0=off, 1=fast/sparse (fallocate, default on), 2=full
            append("}\n")
        }

        private fun jsonStr(s: String): String =
            "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
    }
}