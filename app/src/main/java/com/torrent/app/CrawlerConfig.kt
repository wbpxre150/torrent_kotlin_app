package com.torrent.app

import android.content.Context
import com.torrent.app.config.CrawlerKeys
import com.torrent.app.config.CrawlerSettings
import android.content.pm.PackageManager
import android.os.Build
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

/**
 * Owns the dht_crawler's working dir under internal storage: `filesDir/crawler/`
 * (contains `config.ini`, `data/`, the keyword files, and `models/v6/` with the
 * classifier model files). Stage 09 (internal
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
    /** Last-known-good copy of `config.ini` for apply/rollback. */
    val lkgConfigFile: File = File(workDir, "config.ini.lkg")

    /** Classifier model dir (the crawler's `classifier_model_dir` default); holds the `.ftz` files. */
    val modelsDir: File = File(workDir, MODELS_SUBDIR)

    /** Creates the crawler layout and seeds the keyword + model assets and config.ini. */
    fun ensureLayout() {
        workDir.mkdirs()
        dataDir.mkdirs()
        ensureAssets()
        ensureConfigIni()
    }

    /**
     * Copies the keyword files from `assets/dht_crawler/` into [workDir], and the
     * classifier model files from `assets/dht_crawler/models/v6/` into [modelsDir],
     * when missing or when the `"<versionCode>|<MODELS_SUBDIR>"` `.assets_version`
     * stamp differs — so a model-dir bump re-stages even at an unchanged version code.
     * Every call also sweeps `models/` for other generations ([deleteStaleModelDirs]):
     * only immediate child dirs whose name is a `v<digits>` generation other than the
     * active one are deleted, so a stale generation can never survive a stamp state,
     * and non-generation dirs/files under `models/` are never touched. The models are
     * binary (`fastText` `.ftz`); they are streamed byte-for-byte — see
     * [copyBinaryAtomically].
     */
    fun ensureAssets() {
        val version = currentVersionCode()
        // The stamp carries the model dir as well as the version code: a v5 -> v6
        // bump must re-stage even when `versionCode` itself is unchanged.
        val stampValue = "$version|$MODELS_SUBDIR"
        val stamp = File(workDir, STAMP_FILE)
        val stampMatches = stamp.exists() && stamp.readText().trim() == stampValue
        for (name in KEYWORD_FILES) {
            val target = File(workDir, name)
            if (stampMatches && target.exists()) continue
            val text = openAsset("$ASSET_DIR/$name").use { it.readBytes().toString(Charsets.UTF_8) }
            writeAtomically(target, text)
        }
        for (name in MODEL_FILES) {
            val target = File(modelsDir, name)
            if (stampMatches && target.exists()) continue
            openAsset("$ASSET_DIR/$MODELS_SUBDIR/$name").use { copyBinaryAtomically(it, target) }
        }
        // Sweep on every call, not just on a stamp mismatch: "no stale generation
        // dir survives" must not depend on stamp state. Any other `vN` model dir
        // under models/ is a previous generation's (~26 MB of dead weight). The
        // active dir is never a candidate, so a running crawler keeps its files.
        deleteStaleModelDirs()
        if (!stampMatches) {
            stamp.writeText(stampValue)
        }
    }

    /**
     * Deletes every immediate child dir of `workDir/models` that is not the current
     * [MODELS_SUBDIR] generation (e.g. `v5` once the app ships `v6`). Sibling dirs only:
     * the current one is never a candidate, so an in-flight crawler that resolves
     * [modelsDir] keeps its files. Delegates to the pure [deleteStaleModelDirs], which
     * owns the `v<digits>` guard.
     */
    private fun deleteStaleModelDirs() {
        modelsDir.parentFile?.let { deleteStaleModelDirs(it, File(workDir, MODELS_SUBDIR).name) }
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

    /** The `config.ini` keys owned by the settings form, in stable render order. */
    fun managedKeys(s: CrawlerSettings): Map<String, String> {
        val keys = linkedMapOf(
            "dht_port" to s.dhtPort.toString(),
            "log_level" to s.logLevel,
            "nice_level" to s.niceLevel.toString(),
            "num_trees" to s.numTrees.toString(),
            "tree_find_node_workers" to s.treeFindNodeWorkers.toString(),
            "tree_bep51_workers" to s.treeBep51Workers.toString(),
            "tree_get_peers_workers" to s.treeGetPeersWorkers.toString(),
            "tree_metadata_workers" to s.treeMetadataWorkers.toString(),
            "tree_infohash_queue_capacity" to s.treeInfohashQueueCapacity.toString(),
            "tree_peers_queue_capacity" to s.treePeersQueueCapacity.toString(),
            "language_filter_non_latin_threshold" to s.languageFilterNonLatinThreshold.toString(),
            "classifier_enabled" to (if (s.classifierEnabled) "1" else "0"),
            // The C crawler compares adult_mode case-sensitively; normalise here.
            "adult_mode" to s.adultMode.trim().lowercase(),
            "adult_hide_threshold" to s.adultHideThreshold.toString(),
            "bloom_capacity" to s.bloomCapacity.toString(),
            "failure_bloom_capacity" to s.failureBloomCapacity.toString(),
            "bloom_error_rate" to s.bloomErrorRate.toString(),
            "http_port" to (if (s.lanDirect) s.proxyPort.toString() else s.crawlerHttpPort.toString()),
            "http_bind" to (if (s.lanDirect) "0.0.0.0" else "127.0.0.1"),
        )
        // Category selection: an empty list means "save everything", which is the
        // crawler's own default — so the key is omitted rather than written empty.
        taxonomyList(s.saveCategories)?.let { keys[SAVE_CATEGORIES_KEY] = it }
        taxonomyList(s.saveTvKinds)?.let { keys[SAVE_TV_KINDS_KEY] = it }
        return keys
    }

    /**
     * Renders the full `config.ini` text for [s]: the current file (or the seed
     * asset when missing) with the managed keys set in place (preserving
     * comments/order, appending missing keys), then the advanced overrides,
     * then the pinned backup keys forced to 0. Unparseable advanced text is
     * ignored here — validation is the caller's job.
     */
    fun renderManaged(s: CrawlerSettings): String {
        val base = if (configFile.exists()) configFile.readText(Charsets.UTF_8) else seedText()
        var text = setKeys(base, managedKeys(s))
        val advanced = CrawlerKeys.parseAdvanced(s.advancedOverrides)
        if (advanced.ok) text = setKeys(text, advanced.entries)
        // Clearing the selection must also clear the key: leaving a stale
        // `save_categories` behind would keep filtering to the old choice.
        text = unsetKeys(
            text,
            buildList {
                addAll(VESTIGIAL_KEYS)
                if (taxonomyList(s.saveCategories) == null) add(SAVE_CATEGORIES_KEY)
                if (taxonomyList(s.saveTvKinds) == null) add(SAVE_TV_KINDS_KEY)
            },
        )
        return applyPinnedKeys(text)
    }

    /** [renderManaged] followed by an atomic write through [writeConfig]. */
    fun applySettings(s: CrawlerSettings) {
        writeConfig(renderManaged(s))
    }

    /** Current `config.ini` as a key→value map; empty when the file is missing. */
    fun readIni(): Map<String, String> =
        if (configFile.exists()) parseIni(configFile.readText(Charsets.UTF_8)) else emptyMap()

    /** Copies the current `config.ini` to `config.ini.lkg` (atomic). */
    fun snapshotLastKnownGood() {
        if (configFile.exists()) writeAtomically(lkgConfigFile, configFile.readText(Charsets.UTF_8))
    }

    /** Restores `config.ini` from the LKG snapshot. @return true when a snapshot existed. */
    fun restoreLastKnownGood(): Boolean {
        if (!lkgConfigFile.exists()) return false
        writeAtomically(configFile, lkgConfigFile.readText(Charsets.UTF_8))
        return true
    }

    private fun seedText(): String =
        openAsset("$ASSET_DIR/$CONFIG_INI_DEFAULT").use { it.readBytes().toString(Charsets.UTF_8) }

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

        /**
         * Text list files shipped under `assets/dht_crawler/` and kept in the work
         * dir: the torrent-search keyword list plus the always-on hard-block list
         * (Stage 6).
         */
        val KEYWORD_FILES = listOf("torrent_search_keywords.txt", HARD_BLOCK_FILE)

        /** Hard-block list file name (`hard_block_file`), staged in the work dir. */
        const val HARD_BLOCK_FILE = "hard_block.txt"

        /** Classifier model dir, work-dir relative (mirrors crawler `classifier_model_dir`). */
        const val MODELS_SUBDIR = "models/v6"

        /**
         * A classifier model generation dir name: `v` followed by digits (`v3`, `v4`, …).
         * Only names matching this are sweep candidates under `models/` — see
         * [deleteStaleModelDirs].
         */
        private val STALE_MODEL_DIR_NAME = Regex("^v\\d+$")

        /**
         * Deletes every immediate child of [modelsRoot] that is a generation dir (name
         * matching [STALE_MODEL_DIR_NAME]) other than [activeName]. Pure (plain [File]s,
         * no Android deps) so it is unit-testable. Never touches: plain files (even one
         * named `v3`), non-generation dirs (e.g. `backup/`), the active dir, and a
         * missing/unreadable [modelsRoot] ([File.listFiles] returning null).
         */
        internal fun deleteStaleModelDirs(modelsRoot: File, activeName: String) {
            for (child in modelsRoot.listFiles().orEmpty()) {
                if (!child.isDirectory) continue
                if (child.name == activeName) continue
                if (!STALE_MODEL_DIR_NAME.matches(child.name)) continue
                child.deleteRecursively()
            }
        }

        /** `save_categories` — categories to keep; the key is omitted when none is selected. */
        const val SAVE_CATEGORIES_KEY = "save_categories"

        /** `save_tv_kinds` — tv kinds to keep within tv/anime; omitted when none is selected. */
        const val SAVE_TV_KINDS_KEY = "save_tv_kinds"

        /**
         * Keys the app no longer manages and whose stale assignment is dropped from an
         * existing `config.ini` on every render. `adult_drop_threshold` went away with
         * the drop-all-adult-tagged semantics (the crawler only reads
         * `adult_hide_threshold`), so an install upgraded from the old build must not
         * keep the line: the crawler ignores it today, but a submodule rollback could
         * silently reactivate it.
         */
        val VESTIGIAL_KEYS = listOf("adult_drop_threshold")

        /**
         * Comma-joined, lowercased taxonomy selection for [config.ini], or null when
         * nothing is selected — the crawler reads an absent key as "save everything".
         */
        fun taxonomyList(names: Collection<String>): String? {
            val cleaned = names.map { it.trim().lowercase() }.filter { it.isNotEmpty() }
            return if (cleaned.isEmpty()) null else cleaned.joinToString(",")
        }

        /**
         * Binary model assets shipped under `assets/dht_crawler/models/v6/` and staged
         * into `workDir/models/v6/`, where the crawler resolves them via the pinned
         * `classifier_model_dir`.
         */
        val MODEL_FILES = listOf("adult.ftz", "category.ftz", "tv_kind.ftz", "manifest.json")

        /** Buffer size for the binary-safe asset copy (streamed, never decoded). */
        const val BINARY_COPY_BUFFER = 64 * 1024

        /**
         * Keys the config writer always forces to a fixed value: the backup flags
         * (backups are off on Android) and the app-owned path keys pointing at the
         * assets the app itself stages (`classifier_model_dir`, `hard_block_file`).
         */
        val PINNED_KEYS = linkedMapOf(
            "backup_enabled" to "0",
            "ssh_backup_enabled" to "0",
            "rclone_backup_enabled" to "0",
            "classifier_model_dir" to MODELS_SUBDIR,
            "hard_block_file" to HARD_BLOCK_FILE,
        )

        /**
         * Force the pinned keys to their required values, line by line, preserving
         * comments and ordering. Existing assignments (with or without spaces, with
         * leading whitespace) are replaced; keys absent from the input are appended
         * at the end. Same-prefix keys (e.g. `backup_path`) are left untouched.
         */
        fun applyPinnedKeys(ini: String): String = setKeys(ini, PINNED_KEYS)

        /**
         * Sets [keys] line by line, preserving comments and ordering. Existing
         * assignments (with or without spaces, with leading whitespace) are
         * replaced; keys absent from the input are appended at the end. Unlisted
         * keys are left untouched.
         */
        fun setKeys(ini: String, keys: Map<String, String>): String {
            val out = ArrayList<String>()
            val seen = HashSet<String>()
            for (line in ini.split("\n")) {
                val trimmed = line.trim()
                val isKeyLine = trimmed.isNotEmpty() &&
                    !trimmed.startsWith("#") && !trimmed.startsWith(";")
                val eq = if (isKeyLine) line.indexOf('=') else -1
                if (eq > 0) {
                    val key = line.substring(0, eq).trim()
                    val value = keys[key]
                    if (value != null) {
                        seen.add(key)
                        out.add("$key = $value")
                        continue
                    }
                }
                out.add(line)
            }
            for ((key, value) in keys) {
                if (key !in seen) out.add("$key = $value")
            }
            return out.joinToString("\n")
        }

        /**
         * Drops every assignment of [keys] from [ini], preserving the rest of the text
         * (comments, ordering). Same-prefix keys are left untouched.
         */
        fun unsetKeys(ini: String, keys: Collection<String>): String {
            if (keys.isEmpty()) return ini
            return ini.split("\n").filterNot { line ->
                val trimmed = line.trim()
                val isKeyLine = trimmed.isNotEmpty() &&
                    !trimmed.startsWith("#") && !trimmed.startsWith(";")
                val eq = if (isKeyLine) line.indexOf('=') else -1
                eq > 0 && line.substring(0, eq).trim() in keys
            }.joinToString("\n")
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

        /**
         * Streams [input] to [file] without any text decoding: 64 KiB chunks into a
         * temp file in the same directory, fsync, then rename (atomic move, falling
         * back to a plain replace). Model files are binary — a UTF-8 round-trip would
         * corrupt them, so they must never go through [writeAtomically].
         */
        fun copyBinaryAtomically(input: InputStream, file: File) {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, ".${file.name}.tmp")
            FileOutputStream(tmp).use { out ->
                val buf = ByteArray(BINARY_COPY_BUFFER)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                }
                out.flush()
                out.fd.sync()
            }
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
