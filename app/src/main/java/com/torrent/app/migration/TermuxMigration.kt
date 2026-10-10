package com.torrent.app.migration

import android.content.ContentResolver
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.provider.DocumentsContract
import com.torrent.app.CrawlerConfig
import com.torrent.app.OneShotRunner
import com.torrent.app.config.ApplyResult
import com.torrent.app.config.CrawlerSettings
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.CancellationException
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Observable status of the Termux migration (plan 10 §6). The control page
 * polls it (step 7); [crawlerRunning] and [statsReachable] are always filled
 * live from the supervisor/HTTP checks. [verifyLines] is the tail of the
 * last one-shot's output (the `--check-database` verify or the
 * `--rebuild-bloom-filter` run).
 */
data class MigrationStatus(
    val phase: MigrationPhase = MigrationPhase.IDLE,
    val message: String = "",
    val treeUri: String? = null,
    val treeAuthority: String? = null,
    /** True when the granted tree is not the Termux provider (generic fallback). */
    val nonTermuxWarning: Boolean = false,
    /** Document id of the `data/` child inside the granted tree (scan). */
    val dataDocId: String? = null,
    val files: List<ScannedFile> = emptyList(),
    val totalBytes: Long = 0,
    val freeSpaceBytes: Long = 0,
    val spaceOk: Boolean = true,
    val currentFile: String? = null,
    val copiedBytes: Long = 0,
    val copyBytesPerSec: Long = 0,
    val verifyExitCode: Int? = null,
    val verifyLines: List<String> = emptyList(),
    val torrentCount: Long? = null,
    val configDiff: List<ConfigDiffEntry> = emptyList(),
    val bloomRebuilt: Boolean = false,
    val crawlerRunning: Boolean = false,
    val statsReachable: Boolean = false,
    val startedAt: Long? = null,
    val finishedAt: Long? = null,
)

/**
 * Runs the Termux migration (plan 10 §6) as background jobs: grant → scan →
 * copy → verify → swap → (config diff) → start → confirm, with a revert at
 * any point before confirmation. All long work runs on [executor] (the
 * crawler-control executor, so the 60 s crawler stop never blocks a request
 * thread and every crawler stop/start is serialized); the control page polls
 * [status].
 *
 * Refusals are recorded in [MigrationStatus.message] and never thrown. The
 * migration is mutually exclusive with the apply job (see
 * [isBusy], which the maintenance coordinator also consults).
 *
 * The phase is persisted to a small JSON state file under the crawler work
 * dir so it survives a service restart; an in-flight job is marked FAILED on
 * restore (the user re-runs the step).
 */
class TermuxMigration(
    private val context: Context,
    private val oneShotRunner: OneShotRunner,
    private val executor: Executor,
    private val crawlerConfig: CrawlerConfig,
    private val grant: CrawlerTreeGrant,
    private val freeSpace: () -> Long,
    private val stopCrawler: () -> Unit,
    private val startCrawlerIfEnabled: () -> Unit,
    private val applyCrawler: (CrawlerSettings) -> ApplyResult,
    private val currentSettings: () -> CrawlerSettings,
    private val crawlerRunning: () -> Boolean,
    private val statsReachable: () -> Boolean,
) {
    private val dirs = MigrationDirs.forWorkDir(crawlerConfig.workDir)
    private val stateFile = File(crawlerConfig.workDir, STATE_FILE)

    @Volatile private var cached = MigrationStatus()

    private val cancelRequested = AtomicBoolean(false)

    init {
        restoreState()
    }

    /** The polled status; crawler running / stats reachable are filled live. */
    val status: MigrationStatus
        get() = cached.copy(crawlerRunning = crawlerRunning(), statsReachable = statsReachable())

    /** True while a migration background job is in flight (mutual exclusion). */
    fun isBusy(): Boolean = cached.phase.isActive

    /**
     * Records the granted tree (plan 10 §6.2). [uri] must already have been
     * persisted with a persistable grant via [CrawlerTreeGrant.grant]. A
     * non-Termux authority is accepted with a warning (generic fallback).
     */
    fun setTree(uri: Uri): String {
        val nonTermux = uri.authority != TERMUX_AUTHORITY
        cached = MigrationStatus(
            phase = MigrationPhase.READY,
            message = if (nonTermux) {
                "tree accepted (not the Termux provider — make sure it is the dht_crawler folder)"
            } else {
                "tree granted; run a scan"
            },
            treeUri = uri.toString(),
            treeAuthority = uri.authority,
            nonTermuxWarning = nonTermux,
        )
        persist()
        return cached.message
    }

    /**
     * Runs a migration action (plan 10 §6): scan, copy, cancel, verify, swap,
     * adopt-config, start, confirm, revert, delete-source. Long work is
     * queued on [executor] and this returns a short message immediately;
     * progress is polled via [status].
     */
    fun action(name: String): String {
        val n = name.trim().lowercase()
        if (n == "cancel") {
            return if (cancelRequested.compareAndSet(false, true)) {
                "cancel requested"
            } else {
                "no copy in progress"
            }
        }
        val activePhase = when (n) {
            "scan" -> MigrationPhase.SCANNING
            "copy" -> MigrationPhase.COPYING
            "verify" -> MigrationPhase.VERIFYING
            "swap" -> MigrationPhase.SWAPPING
            "revert" -> MigrationPhase.SWAPPING
            "delete-source" -> MigrationPhase.SWAPPING
            else -> null
        }
        val body: (() -> Unit)? = when (n) {
            "scan" -> ::doScan
            "copy" -> ::doCopy
            "verify" -> ::doVerify
            "swap" -> ::doSwap
            "adopt-config" -> ::doAdopt
            "start" -> ::doStart
            "confirm" -> ::doConfirm
            "revert" -> ::doRevert
            "delete-source" -> ::doDeleteSource
            else -> null
        }
        if (body == null) return "unknown migration action: $name"
        if (isBusy()) return "refused: a migration job is already running"
        // Mark the active phase optimistically so a concurrent action is
        // refused even before the executor picks the job up.
        if (activePhase != null) {
            cached = cached.copy(phase = activePhase, message = "starting $n")
            persist()
        }
        try {
            executor.execute { safeRun(body) }
        } catch (e: RejectedExecutionException) {
            return "refused: service is shutting down"
        }
        return "started: $n"
    }

    // ── job bodies (all on the crawler-control executor) ────────────────────

    private fun safeRun(job: () -> Unit) {
        cached = cached.copy(startedAt = System.currentTimeMillis())
        try {
            job()
        } catch (e: Exception) {
            android.util.Log.e("TermuxMigration", "migration job failed", e)
            setPhase(MigrationPhase.FAILED, "job failed: ${e.message}")
        } finally {
            cached = cached.copy(finishedAt = System.currentTimeMillis())
            persist()
        }
    }

    /**
     * Scan (plan 10 §6.3): lists `data/` through DocumentsContract,
     * classifies each file, checks for the required `torrents.db` and the
     * free-space headroom.
     */
    private fun doScan() {
        setPhase(MigrationPhase.SCANNING, "scanning the Termux tree")
        val tree = grant.treeUri()
            ?: return setPhase(MigrationPhase.FAILED, "no tree granted; pick the folder again")
        val resolver = context.contentResolver
        val dataDoc = queryChildren(resolver, tree, null)
            .firstOrNull { it.name == "data" && it.isDirectory }
            ?: return setPhase(
                MigrationPhase.FAILED,
                "no data/ folder in the picked tree — pick the dht_crawler folder itself")
        val files = queryChildren(resolver, tree, dataDoc.docId)
            .map { it.toScanned() }
        val importFiles = files.filter { it.role == FileRole.IMPORT }
        if (importFiles.none { isRequiredSourceFile(it.name) }) {
            return setPhase(MigrationPhase.FAILED, "source is missing torrents.db — nothing to migrate")
        }
        val total = importFiles.sumOf { it.size }
        val free = freeSpace()
        val spaceOk = free >= requiredFreeSpace(total)
        val rejected = files.filter { it.role == FileRole.REJECT }.map { it.name }
        cached = cached.copy(
            dataDocId = dataDoc.docId,
            files = files,
            totalBytes = total,
            freeSpaceBytes = free,
            spaceOk = spaceOk,
        )
        val warnings = buildList {
            if (rejected.isNotEmpty()) add("rejected entries: ${rejected.joinToString()}")
            if (!spaceOk) add(
                "not enough free space (need ${formatBytes(requiredFreeSpace(total))}, " +
                    "have ${formatBytes(free)})")
        }
        setPhase(
            MigrationPhase.SCANNED,
            "scan complete: ${importFiles.size} files, ${formatBytes(total)}" +
                if (warnings.isEmpty()) "" else " — " + warnings.joinToString("; "))
    }

    /**
     * Copy (plan 10 §6.4): stops the crawler, copies the allow-listed files
     * into `data.import/` (chunked transferTo + fsync), re-checking each
     * source's size/mtime before and after to detect a live Termux crawler.
     * Cancellable — cancelling deletes `data.import/`.
     */
    private fun doCopy() {
        val st = cached
        if (st.files.isEmpty()) {
            return setPhase(MigrationPhase.FAILED, "nothing to copy — run a scan first")
        }
        if (!st.spaceOk) {
            return setPhase(MigrationPhase.FAILED, "refused: not enough free space (see the scan)")
        }
        val tree = grant.treeUri()
            ?: return setPhase(MigrationPhase.FAILED, "no tree granted")
        val dataDocId = st.dataDocId
            ?: return setPhase(MigrationPhase.FAILED, "no scan on record — run a scan first")
        val importFiles = st.files.filter { it.role == FileRole.IMPORT }
        setPhase(MigrationPhase.COPYING, "stopping crawler, copying ${importFiles.size} files")
        cancelRequested.set(false)
        stopCrawler()
        if (dirs.import.exists()) dirs.import.deleteRecursively()
        dirs.import.mkdirs()
        val resolver = context.contentResolver
        var copied = 0L
        val t0 = System.currentTimeMillis()
        for (f in importFiles) {
            if (cancelRequested.get()) return abortCopy("cancelled — data.import/ removed")
            val pre = requeryEntry(resolver, tree, dataDocId, f.docId)
                ?: return abortCopy("source file disappeared: ${f.name}")
            if (pre.size != f.size || pre.mtime != f.mtime) {
                return abortCopy("the Termux crawler appears to be running — stop it and retry")
            }
            cached = cached.copy(currentFile = f.name)
            val dest = File(dirs.import, f.name)
            try {
                copyDocument(resolver, tree, f.docId, pre.size, dest)
            } catch (e: CancellationException) {
                return abortCopy("cancelled — data.import/ removed", MigrationPhase.CANCELLED)
            } catch (e: Exception) {
                return abortCopy("copy failed for ${f.name}: ${e.message}")
            }
            val post = requeryEntry(resolver, tree, dataDocId, f.docId)
            if (post == null || post.size != f.size || post.mtime != f.mtime) {
                return abortCopy("the Termux crawler appears to be running — stop it and retry")
            }
            if (dest.length() != pre.size) {
                return abortCopy(
                    "size mismatch for ${f.name} (copied ${dest.length()}, source ${pre.size})")
            }
            copied += pre.size
            val elapsedSec = (System.currentTimeMillis() - t0) / 1000.0
            cached = cached.copy(
                copiedBytes = copied,
                copyBytesPerSec = if (elapsedSec > 0) (copied / elapsedSec).toLong() else 0)
        }
        cached = cached.copy(currentFile = null)
        setPhase(MigrationPhase.COPIED, "copy complete: ${formatBytes(copied)} — run verify")
    }

    /**
     * Verify (plan 10 §6.5): runs `libdhtcrawler.so --check-database` as a
     * one-shot with cwd `verify/` (a generated config.ini pointing at the
     * imported DB, backups pinned to 0), then counts the torrents read-only.
     */
    private fun doVerify() {
        if (!File(dirs.import, "torrents.db").exists()) {
            return setPhase(MigrationPhase.FAILED, "nothing to verify — copy the files first")
        }
        setPhase(MigrationPhase.VERIFYING, "running --check-database on the imported data")
        dirs.verify.mkdirs()
        CrawlerConfig.writeAtomically(
            File(dirs.verify, "config.ini"),
            CrawlerConfig.applyPinnedKeys("db_path = ../data.import/torrents.db\n"))
        val lines = ArrayDeque<String>()
        val exit = oneShotRunner.run(listOf("--check-database"), dirs.verify) { line ->
            pushLine(lines, line)
        }
        val count = if (exit == 0) countTorrents(File(dirs.import, "torrents.db")) else null
        cached = cached.copy(verifyExitCode = exit, torrentCount = count)
        if (exit == 0) {
            setPhase(
                MigrationPhase.VERIFIED,
                "check-database passed" + count?.let { " — $it torrents" } + " — run swap")
        } else {
            setPhase(MigrationPhase.FAILED, "--check-database exited with code $exit — see the log")
        }
    }

    /**
     * Swap (plan 10 §6.6): stops the crawler, snapshots the LKG config.ini,
     * moves `data/` → `data.old/` and `data.import/` → `data/` (atomic
     * renames), rebuilds the bloom filters when they were not imported, and
     * computes the Termux config diff.
     */
    private fun doSwap() {
        if (!File(dirs.import, "torrents.db").exists()) {
            return setPhase(MigrationPhase.FAILED, "nothing to swap — verify the copy first")
        }
        setPhase(MigrationPhase.SWAPPING, "stopping crawler, swapping in the imported data")
        stopCrawler()
        crawlerConfig.snapshotLastKnownGood()
        if (dirs.old.exists()) dirs.old.deleteRecursively()
        if (dirs.data.exists() && !dirs.data.renameTo(dirs.old)) {
            return setPhase(MigrationPhase.FAILED, "could not move data/ aside")
        }
        if (!dirs.import.renameTo(dirs.data)) {
            return setPhase(MigrationPhase.FAILED, "could not move data.import/ into place")
        }
        val bloomMissing = !File(dirs.data, "bloom.dat").exists() ||
            !File(dirs.data, "failure_bloom.dat").exists()
        if (bloomMissing) {
            setPhase(MigrationPhase.SWAPPING, "bloom files missing — running --rebuild-bloom-filter")
            val lines = ArrayDeque<String>()
            val exit = oneShotRunner.run(listOf("--rebuild-bloom-filter"), crawlerConfig.workDir) { line ->
                pushLine(lines, line)
            }
            cached = cached.copy(bloomRebuilt = exit == 0)
            if (exit != 0) {
                return setPhase(MigrationPhase.FAILED, "--rebuild-bloom-filter exited with code $exit")
            }
        }
        val diff = readTermuxConfigDiff()
        cached = cached.copy(configDiff = diff)
        setPhase(
            MigrationPhase.SWAPPED,
            "swap complete" + if (diff.isNotEmpty()) " — ${diff.size} tuning keys offered" else "")
    }

    /**
     * Config (plan 10 §6.7): adopts the offered Termux tuning keys by
     * building a [CrawlerSettings] from the diff and routing it through the
     * normal apply job (validation + rollback included).
     */
    private fun doAdopt() {
        val diff = cached.configDiff
        if (diff.isEmpty()) return setPhase(MigrationPhase.SWAPPED, "no tuning keys to adopt")
        val newSettings = applyTuningDiff(currentSettings(), diff)
        val r = applyCrawler(newSettings)
        setPhase(
            MigrationPhase.SWAPPED,
            if (r.ok) "adopting ${diff.size} tuning keys — ${r.message}"
            else "adopt failed: ${r.message}")
    }

    /** Start & confirm (plan 10 §6.8): starts the crawler on the new data. */
    private fun doStart() {
        if (!File(dirs.data, "torrents.db").exists()) {
            return setPhase(MigrationPhase.FAILED, "no data/ to start — run the swap first")
        }
        setPhase(MigrationPhase.STARTED, "starting crawler on the imported data")
        startCrawlerIfEnabled()
    }

    /**
     * Confirm (plan 10 §6.9): deletes `data.old/` and releases the SAF
     * grant. The Termux copy is left untouched (the user removes it, or uses
     * the separate delete-source action).
     */
    private fun doConfirm() {
        if (cached.phase != MigrationPhase.STARTED && cached.phase != MigrationPhase.SWAPPED) {
            return setPhase(
                cached.phase,
                "confirm is only available after the swap (and start)")
        }
        if (dirs.old.exists()) dirs.old.deleteRecursively()
        grant.clear()
        setPhase(MigrationPhase.CONFIRMED, "migration confirmed — data.old/ deleted, tree grant released")
    }

    /**
     * Revert (plan 10 §6 rollback): stops the crawler, moves `data/` →
     * `data.failed/`, `data.old/` → `data/` (or deletes the imported data
     * when there was no previous data) and restores the LKG config.ini.
     */
    private fun doRevert() {
        val p = cached.phase
        if (p == MigrationPhase.IDLE || p == MigrationPhase.READY || p == MigrationPhase.CONFIRMED) {
            return setPhase(p, "nothing to revert")
        }
        setPhase(MigrationPhase.SWAPPING, "stopping crawler, reverting the migration")
        stopCrawler()
        if (dirs.failed.exists()) dirs.failed.deleteRecursively()
        if (dirs.data.exists() && !dirs.data.renameTo(dirs.failed)) {
            return setPhase(MigrationPhase.FAILED, "could not move data/ to data.failed/")
        }
        if (dirs.old.exists()) {
            if (!dirs.old.renameTo(dirs.data)) {
                return setPhase(MigrationPhase.FAILED, "could not restore data.old/")
            }
        } else if (dirs.data.exists()) {
            dirs.data.deleteRecursively()
        }
        crawlerConfig.restoreLastKnownGood()
        setPhase(MigrationPhase.REVERTED, "migration reverted — previous data and config.ini restored")
    }

    /**
     * Optional explicit cleanup (plan 10 §6.9): deletes the allow-listed
     * source files in the Termux tree via `DocumentsContract.deleteDocument`.
     * Never automatic — the UI requires a separate confirmation.
     */
    private fun doDeleteSource() {
        val tree = grant.treeUri()
            ?: return setPhase(cached.phase, "no tree granted")
        val dataDocId = cached.dataDocId
            ?: return setPhase(cached.phase, "no scan on record — run a scan first")
        val files = cached.files.filter { it.role == FileRole.IMPORT }
        if (files.isEmpty()) return setPhase(cached.phase, "no allow-listed source files on record")
        setPhase(MigrationPhase.SWAPPING, "deleting ${files.size} source files via SAF")
        // dataDocId doubles as the "a scan ran" guard; the per-file doc ids
        // are absolute paths, so deleteDocument needs only the tree + file id.
        val resolver = context.contentResolver
        val results = files.map { f ->
            val ok = try {
                DocumentsContract.deleteDocument(
                    resolver, DocumentsContract.buildDocumentUriUsingTree(tree, f.docId))
            } catch (e: Exception) {
                false
            }
            "${f.name}: ${if (ok) "deleted" else "failed"}"
        }
        setPhase(cached.phase, "source delete — " + results.joinToString(", "))
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private fun abortCopy(reason: String, phase: MigrationPhase = MigrationPhase.FAILED) {
        try {
            dirs.import.deleteRecursively()
        } catch (_: Exception) {
            // best effort
        }
        setPhase(phase, reason)
    }

    /** One `data/` directory entry as returned by a DocumentsContract query. */
    private data class RawEntry(
        val name: String,
        val docId: String,
        val size: Long,
        val mtime: Long,
        val isDirectory: Boolean,
    ) {
        fun toScanned(): ScannedFile = ScannedFile(
            name = name,
            docId = docId,
            size = size,
            mtime = mtime,
            // Symlinks are not detectable through DocumentsContract; the
            // allow-list, the size/mtime re-check and --check-database are
            // the real protection against a live/malicious source.
            role = classifySourceFile(name, isDirectory = isDirectory),
        )
    }

    /**
     * Lists the children of [parentDocId] inside [tree] (the tree root when
     * [parentDocId] is null) via a DocumentsContract child query.
     */
    private fun queryChildren(
        resolver: ContentResolver,
        tree: Uri,
        parentDocId: String?,
    ): List<RawEntry> {
        val out = mutableListOf<RawEntry>()
        val docId = parentDocId ?: DocumentsContract.getTreeDocumentId(tree)
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
        resolver.query(uri, null, null, null, null)?.use { c ->
            val idCol = c.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameCol = c.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val sizeCol = c.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
            val mtimeCol = c.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
            val mimeCol = c.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
            while (c.moveToNext()) {
                out.add(
                    RawEntry(
                        name = if (nameCol >= 0) c.getString(nameCol) ?: "" else "",
                        docId = if (idCol >= 0) c.getString(idCol) ?: "" else "",
                        size = if (sizeCol >= 0) c.getLong(sizeCol) else 0L,
                        mtime = if (mtimeCol >= 0) c.getLong(mtimeCol) else 0L,
                        isDirectory = mimeCol >= 0 &&
                            c.getString(mimeCol) == DocumentsContract.Document.MIME_TYPE_DIR,
                    ))
            }
        }
        return out
    }

    private fun requeryEntry(
        resolver: ContentResolver,
        tree: Uri,
        dataDocId: String,
        docId: String,
    ): RawEntry? {
        return queryChildren(resolver, tree, dataDocId).firstOrNull { it.docId == docId }
    }

    /**
     * Copies one document into [dest] with chunked `transferTo` (cancellable
     * between chunks) and an fsync of the destination.
     */
    private fun copyDocument(
        resolver: ContentResolver,
        tree: Uri,
        docId: String,
        expectedSize: Long,
        dest: File,
    ) {
        val docUri = DocumentsContract.buildDocumentUriUsingTree(tree, docId)
        val pfd = resolver.openFileDescriptor(docUri, "r")
            ?: throw java.io.IOException("could not open $docId")
        pfd.use {
            FileInputStream(pfd.fileDescriptor).channel.use { inCh ->
                FileOutputStream(dest).use { fos ->
                    fos.channel.use { outCh ->
                        var off = 0L
                        while (off < expectedSize) {
                            if (cancelRequested.get()) throw CancellationException("copy cancelled")
                            val n = inCh.transferTo(off, minOf(COPY_CHUNK, expectedSize - off), outCh)
                            if (n <= 0) throw java.io.IOException("copy stalled")
                            off += n
                        }
                        outCh.force(true) // fsync
                    }
                }
            }
        }
    }

    private fun pushLine(lines: ArrayDeque<String>, line: String) {
        synchronized(lines) {
            if (lines.size >= MAX_LINES) lines.removeFirst()
            lines.addLast(line)
            cached = cached.copy(verifyLines = lines.toList())
        }
    }

    /** Torrent count via Android SQLite, read-only (plan 10 §6.5). */
    private fun countTorrents(db: File): Long? = try {
        SQLiteDatabase.openDatabase(db.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { sdb ->
            sdb.rawQuery("SELECT COUNT(*) FROM torrents", null).use { c ->
                if (c.moveToFirst()) c.getLong(0) else null
            }
        }
    } catch (e: Exception) {
        android.util.Log.w("TermuxMigration", "torrent count failed: ${e.message}")
        null
    }

    /** Parses Termux's config.ini (tree root) and diffs its tuning keys. */
    private fun readTermuxConfigDiff(): List<ConfigDiffEntry> {
        val tree = grant.treeUri() ?: return emptyList()
        return try {
            val resolver = context.contentResolver
            val cfg = queryChildren(resolver, tree, null)
                .firstOrNull { it.name == "config.ini" && !it.isDirectory }
                ?: return emptyList()
            val docUri = DocumentsContract.buildDocumentUriUsingTree(tree, cfg.docId)
            val text = resolver.openFileDescriptor(docUri, "r")?.use { pfd ->
                FileInputStream(pfd.fileDescriptor).use { it.readBytes().toString(Charsets.UTF_8) }
            } ?: return emptyList()
            computeConfigDiff(CrawlerConfig.parseIni(text), crawlerConfig.readIni())
        } catch (e: Exception) {
            android.util.Log.w("TermuxMigration", "config diff failed: ${e.message}")
            emptyList()
        }
    }

    private fun setPhase(phase: MigrationPhase, message: String) {
        cached = cached.copy(phase = phase, message = message)
        persist()
    }

    private fun persist() {
        try {
            stateFile.parentFile?.mkdirs()
            val j = JSONObject()
            j.put("phase", cached.phase.name)
            cached.torrentCount?.let { j.put("torrentCount", it) }
            j.put("bloomRebuilt", cached.bloomRebuilt)
            CrawlerConfig.writeAtomically(stateFile, j.toString())
        } catch (_: Exception) {
            // persistence is best-effort
        }
    }

    private fun restoreState() {
        val text = try {
            stateFile.readText()
        } catch (_: Exception) {
            null
        } ?: return
        val j = try {
            JSONObject(text)
        } catch (_: Exception) {
            return
        }
        val phase = try {
            MigrationPhase.valueOf(j.optString("phase", MigrationPhase.IDLE.name))
        } catch (_: Exception) {
            MigrationPhase.IDLE
        }
        val tree = grant.treeUri()
        var st = MigrationStatus(
            phase = phase,
            message = "restored: ${phase.name}",
            treeUri = tree?.toString(),
            treeAuthority = tree?.authority,
            nonTermuxWarning = tree != null && tree.authority != TERMUX_AUTHORITY,
            torrentCount = if (j.has("torrentCount")) j.optLong("torrentCount") else null,
            bloomRebuilt = j.optBoolean("bloomRebuilt", false),
        )
        // The in-flight job is gone with the service: mark it failed so the
        // UI can offer to re-run the step.
        if (phase.isActive) {
            st = st.copy(phase = MigrationPhase.FAILED, message = "service restarted during ${phase.name}; re-run the step")
        }
        if (st.phase == MigrationPhase.READY && tree == null) {
            st = st.copy(phase = MigrationPhase.IDLE, message = "pick the Termux dht_crawler folder to start")
        }
        cached = st
    }

    companion object {
        /** The Termux SAF DocumentsProvider authority (plan 10 §6). */
        const val TERMUX_AUTHORITY = "com.termux.documents"

        /** Initial picker location: the default dht_crawler home in Termux. */
        const val TERMUX_INITIAL_DOC_ID = "/data/data/com.termux/files/home/dht_crawler"

        const val STATE_FILE = "migration_state.json"

        /** Ring-buffer cap for the one-shot output tail (same as maintenance). */
        internal const val MAX_LINES = 2000

        /** Copy chunk size; the cancel flag is checked between chunks. */
        private const val COPY_CHUNK = 16L * 1024 * 1024

        internal fun formatBytes(b: Long): String = when {
            b >= 1L shl 30 -> "%.1f GB".format(b.toDouble() / (1L shl 30))
            b >= 1L shl 20 -> "%.1f MB".format(b.toDouble() / (1L shl 20))
            b >= 1L shl 10 -> "%.1f KB".format(b.toDouble() / (1L shl 10))
            else -> "$b B"
        }
    }
}
