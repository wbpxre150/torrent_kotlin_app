package com.torrent.app

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.io.File
import java.io.InputStream
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

/**
 * Owns the dht_crawler's working dir under internal storage: `filesDir/crawler/`
 * (contains `config.ini`, `data/`, and the keyword files). Stage 09 (internal
 * storage only); the crawler process itself is started by a later supervisor step.
 */
class CrawlerConfig(
    private val context: Context,
    /** Asset source; overridable in unit tests (Robolectric cannot see the generated assets). */
    private val openAsset: (String) -> InputStream = { context.assets.open(it) },
) {

    val workDir: File = File(context.filesDir, "crawler")
    val configFile: File = File(workDir, "config.ini")
    val dataDir: File = File(workDir, "data")

    /** Creates the crawler layout and seeds the keyword assets + config.ini. */
    fun ensureLayout() {
        workDir.mkdirs()
        dataDir.mkdirs()
        ensureAssets()
        ensureConfigIni()
    }

    /**
     * Copies the keyword files from `assets/dht_crawler/` into [workDir] when missing
     * or when the app version code differs from the `.assets_version` stamp.
     */
    fun ensureAssets() {
        val version = currentVersionCode()
        val stamp = File(workDir, STAMP_FILE)
        val stampMatches = stamp.exists() && stamp.readText().trim() == version.toString()
        for (name in KEYWORD_FILES) {
            val target = File(workDir, name)
            if (stampMatches && target.exists()) continue
            val text = openAsset("$ASSET_DIR/$name").use { it.readBytes().toString(Charsets.UTF_8) }
            writeAtomically(target, text)
        }
        if (!stampMatches) stamp.writeText(version.toString())
    }

    /**
     * Seeds `config.ini` from `assets/dht_crawler/config.ini.default` when missing;
     * re-applies the pinned keys when present (rewrites only if the content changes).
     */
    fun ensureConfigIni() {
        val text = if (configFile.exists()) {
            configFile.readText(Charsets.UTF_8)
        } else {
            openAsset("$ASSET_DIR/$CONFIG_INI_DEFAULT").use { it.readBytes().toString(Charsets.UTF_8) }
        }
        writeConfig(text)
    }

    /** Writes [text] as `config.ini`, always forcing the pinned backup keys to 0. */
    fun writeConfig(text: String) {
        val pinned = applyPinnedKeys(text)
        if (configFile.exists() && configFile.readText(Charsets.UTF_8) == pinned) return
        writeAtomically(configFile, pinned)
    }

    private fun currentVersionCode(): Long =
        try {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode
            else info.versionCode.toLong()
        } catch (e: PackageManager.NameNotFoundException) {
            -1L
        }

    companion object {
        const val ASSET_DIR = "dht_crawler"
        const val CONFIG_INI_DEFAULT = "config.ini.default"
        const val STAMP_FILE = ".assets_version"

        /** Keyword files shipped under `assets/dht_crawler/` and kept in the work dir. */
        val KEYWORD_FILES = listOf("porn_filter_keywords.txt", "torrent_search_keywords.txt")

        /** Keys the config writer always forces to `0` (backups are off on Android). */
        val PINNED_KEYS = linkedMapOf(
            "backup_enabled" to "0",
            "ssh_backup_enabled" to "0",
            "rclone_backup_enabled" to "0",
        )

        /**
         * Force the pinned keys to their required values, line by line, preserving
         * comments and ordering. Existing assignments (with or without spaces, with
         * leading whitespace) are replaced; keys absent from the input are appended
         * at the end. Same-prefix keys (e.g. `backup_path`) are left untouched.
         */
        fun applyPinnedKeys(ini: String): String {
            val out = ArrayList<String>()
            val seen = HashSet<String>()
            for (line in ini.split("\n")) {
                val trimmed = line.trim()
                val isKeyLine = trimmed.isNotEmpty() &&
                    !trimmed.startsWith("#") && !trimmed.startsWith(";")
                val eq = if (isKeyLine) line.indexOf('=') else -1
                if (eq > 0) {
                    val key = line.substring(0, eq).trim()
                    val pinned = PINNED_KEYS[key]
                    if (pinned != null) {
                        seen.add(key)
                        out.add("$key = $pinned")
                        continue
                    }
                }
                out.add(line)
            }
            for ((key, value) in PINNED_KEYS) {
                if (key !in seen) out.add("$key = $value")
            }
            return out.joinToString("\n")
        }

        /**
         * Minimal INI parser: `key=value` lines, `#`/`;` comments and blank lines
         * ignored, keys and values trimmed. Later duplicates win.
         */
        fun parseIni(text: String): Map<String, String> {
            val result = LinkedHashMap<String, String>()
            for (raw in text.split("\n")) {
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith("#") || line.startsWith(";")) continue
                val eq = line.indexOf('=')
                if (eq <= 0) continue
                result[line.substring(0, eq).trim()] = line.substring(eq + 1).trim()
            }
            return result
        }

        /**
         * Writes [text] to [file] atomically: temp file in the same directory,
         * fsync, then rename (atomic move, falling back to a plain replace).
         */
        fun writeAtomically(file: File, text: String) {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, ".${file.name}.tmp")
            tmp.writeText(text, Charsets.UTF_8)
            FileChannel.open(tmp.toPath(), StandardOpenOption.WRITE).use { it.force(true) }
            if (!tmp.renameTo(file)) {
                try {
                    Files.move(
                        tmp.toPath(), file.toPath(),
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING
                    )
                } catch (e: AtomicMoveNotSupportedException) {
                    Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
                }
            }
        }
    }
}
