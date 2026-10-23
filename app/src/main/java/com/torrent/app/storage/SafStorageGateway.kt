package com.torrent.app.storage

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.storage.StorageManager
import android.provider.DocumentsContract
import android.system.Os
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock

/**
 * The single SAF choke point. All USB I/O in the app goes through here.
 *
 * Thread-safety: path->URI cache is ConcurrentHashMap; cache misses resolved under
 * per-path ReentrantLock (see [lockFor]). Lock acquisition always descendant -> ancestor
 * (thread resolving descendant may wait ancestor's lock, never reverse), so lock graph
 * acyclic and deadlock-free. All public methods synchronous and safe.
 */
class SafStorageGateway(
    private val context: Context,
    private val treeUri: Uri,
) : UsbFdBridge {

    private val resolver = context.contentResolver
    private val cache = ConcurrentHashMap<String, Uri>()
    private val pathLocks = ConcurrentHashMap<String, ReentrantLock>()
    // FTP-only read cache (plan 07 §2.1). Never written by the transmission path
    // and never read as authoritative: a miss here just re-walks the tree.
    private val roCache = ConcurrentHashMap<String, Uri>()
    @Volatile private var cachedFreeSpace: Pair<Long, Long>? = null
    @Volatile private var freeSpaceRefreshInFlight = false
    private val freeSpaceExecutor =
        java.util.concurrent.Executors.newSingleThreadExecutor { r ->
            Thread(r, "saf-freespace").apply { isDaemon = true }
        }
    private val freeSpaceLock = Any()

    init {
        // Seed the cache with the tree root.
        cache[""] = DocumentsContract.buildDocumentUriUsingTree(
            treeUri,
            DocumentsContract.getTreeDocumentId(treeUri),
        )
    }

    // UsbFdBridge

    override fun openFd(relPath: String, mode: String): Int {
        val n = SafPaths.normalize(relPath)
        val m = SafPaths.mapMode(mode)
        val uri = ensureDocument(n) ?: run {
            Log.e(TAG, "openFd: ensureDocument failed for '$n'")
            return -1
        }
        return try {
            val pfd = resolver.openFileDescriptor(uri, m)
                ?: run { Log.e(TAG, "openFd: openFileDescriptor returned null"); return -1 }
            pfd.detachFd()   // native owns; caller MUST close
        } catch (e: Exception) {
            Log.e(TAG, "openFd failed: ${e.message}")
            -1
        }
    }

    override fun truncate(relPath: String, len: Long): Boolean = withPfd(relPath, "rw") { pfd ->
        try { Os.ftruncate(pfd.fileDescriptor, len); true }
        catch (e: Exception) { Log.e(TAG, "truncate: ${e.message}"); false }
    } ?: false

    override fun statSize(relPath: String): Long {
        val uri = resolveUri(relPath) ?: return -1
        return withPfd(relPath, "r") { pfd ->
            try { Os.fstat(pfd.fileDescriptor).st_size }
            catch (e: Exception) { Log.e(TAG, "statSize: ${e.message}"); -1L }
        } ?: -1L
    }

    override fun renameDoc(relPath: String, dstRelPath: String): Boolean {
        val n = SafPaths.normalize(relPath)
        val srcSplit = SafPaths.splitParent(n) ?: return false
        // Backward compat: a dst without '/' means "basename in the same parent as src".
        val d0 = SafPaths.normalize(dstRelPath)
        val d = if (d0.contains('/')) d0
            else if (srcSplit.first.isEmpty()) d0
            else "${srcSplit.first}/$d0"
        // Same normalized path: no-op. NEVER delete in this case.
        if (n == d) return true
        val dstSplit = SafPaths.splitParent(d) ?: return false
        // Acquire source and target locks in lexicographic order to prevent deadlock.
        val (firstLock, secondLock) = if (n < d) lockFor(n) to lockFor(d) else lockFor(d) to lockFor(n)
        firstLock.lock()
        var result = false
        try {
            secondLock.lock()
            try {
                val srcUri = resolveUri(n) ?: return false
                // SAF's renameDocument auto-appends " (1)" on a name collision instead of
                // overwriting (POSIX rename replaces the target). Emulate POSIX: remove an
                // existing target first so the rename lands on the exact requested name and
                // transmission does not silently end up referencing a phantom " (1)" file.
                resolveUri(d)?.let { existing ->
                    try {
                        DocumentsContract.deleteDocument(resolver, existing)
                    } catch (e: Exception) {
                        Log.e(TAG, "renameDoc: cannot remove existing target '$d': ${e.message}")
                        return false
                    }
                    cache.keys.filter { it == d || it.startsWith("$d/") }
                        .forEach { cache.remove(it) }
                }
                val (srcParent, srcLeaf) = srcSplit
                val (dstParent, dstLeaf) = dstSplit
                val finalUri: Uri
                if (srcParent == dstParent) {
                    finalUri = DocumentsContract.renameDocument(resolver, srcUri, dstLeaf)
                        ?: return false
                } else {
                    // Cross-parent: SAF renameDocument cannot change the parent, so move
                    // the document to the destination parent (created if missing), then
                    // rename the leaf there if the name changed. ExternalStorageProvider
                    // moves within the same volume via File.renameTo, so this is cheap.
                    val srcParentUri = resolveUri(srcParent) ?: return false
                    val dstParentUri =
                        (if (dstParent.isEmpty()) resolveUri(dstParent) else resolveOrCreateDir(dstParent))
                            ?: return false
                    val moved = try {
                        DocumentsContract.moveDocument(resolver, srcUri, srcParentUri, dstParentUri)
                    } catch (e: Exception) {
                        // Provider without FLAG_SUPPORTS_MOVE: fail WITHOUT deleting the
                        // source so transmission falls back to copy+remove.
                        Log.e(TAG, "renameDoc: moveDocument '$n' -> '$d' failed: ${e.message}")
                        return false
                    }
                    if (moved == null) return false
                    finalUri = if (dstLeaf != srcLeaf) {
                        DocumentsContract.renameDocument(resolver, moved, dstLeaf) ?: return false
                    } else {
                        moved
                    }
                }
                // src may be a directory: drop src and any cached descendants, then
                // record the new location.
                cache.keys.filter { it == n || it.startsWith("$n/") }
                    .forEach { cache.remove(it) }
                evictReadOnly(n)   // private FTP state; keep FTP's view consistent with renames
                cache[d] = finalUri
                result = true
            } finally {
                secondLock.unlock()
            }
        } catch (e: Exception) { Log.e(TAG, "renameDoc: ${e.message}") }
        finally {
            firstLock.unlock()
        }
        return result
    }

    override fun removeDoc(relPath: String): Boolean {
        val n = SafPaths.normalize(relPath)
        val uri = resolveUri(n) ?: return false
        // invalidate this node and any cached descendants
        cache.keys.filter { it == n || it.startsWith("$n/") }.forEach { cache.remove(it) }
        evictReadOnly(n)   // private FTP state; keep FTP's view consistent with deletes
        return try {
            DocumentsContract.deleteDocument(resolver, uri)
        } catch (e: Exception) { Log.e(TAG, "removeDoc: ${e.message}"); false }
    }

    override fun mkdirs(relPath: String): Boolean {
        val n = SafPaths.normalize(relPath)
        if (n.isEmpty()) return true
        return resolveOrCreateDir(n) != null
    }

    override fun availableBytes(): Long {
        val now = System.currentTimeMillis()
        cachedFreeSpace?.let { (value, ts) ->
            if (now - ts < FREE_SPACE_TTL_MS) return value
        }
        // Stale or missing. Trigger background refresh.
        // Return last known value (or Long.MAX_VALUE if never probed)
        // WITHOUT blocking the caller. This keeps HTTP handler threads
        // and transmission's IPC disk thread off the FUSE daemon.
        triggerFreeSpaceRefresh()
        return cachedFreeSpace?.first ?: Long.MAX_VALUE
    }

    private fun triggerFreeSpaceRefresh() {
        if (freeSpaceRefreshInFlight) return
        synchronized(freeSpaceLock) {
            if (freeSpaceRefreshInFlight) return
            freeSpaceRefreshInFlight = true
        }
        freeSpaceExecutor.execute {
            try {
                val free = probeFreeSpaceViaFd()
                // Cache failures too (Long.MAX_VALUE): the TTL then throttles
                // re-probes on the failure path exactly like the success path.
                cachedFreeSpace = free to System.currentTimeMillis()
            } catch (e: Exception) {
                Log.w(TAG, "free-space refresh failed: ${e.message}")
            } finally {
                freeSpaceRefreshInFlight = false
            }
        }
    }

    /**
     * Read free bytes via Os.fstatvfs on a SAF-granted file descriptor.
     *
     * COLUMN_AVAILABLE_BYTES is unpopulated for removable volumes on AOSP
     * (reportAvailableBytes stays false), and StorageStatsManager has no UUID
     * for portable volumes. [Os.fstatvfs] only needs *any* fd on the target
     * filesystem, so the primary path opens the cached tree-root document
     * read-only: zero writes to the USB tree. Only if that open (or the
     * fstatvfs on it) fails do we fall back to the older create-probe-document
     * path, which is deleted in [finally].
     */
    private fun probeFreeSpaceViaFd(): Long {
        val rootUri = cache[""] ?: return Long.MAX_VALUE

        // Primary: read-only fd on the tree root. Zero outbound writes.
        var rootPfd: ParcelFileDescriptor? = null
        try {
            rootPfd = resolver.openFileDescriptor(rootUri, "r")
            if (rootPfd != null) return fstatvfsFree(rootPfd)
        } catch (e: Exception) {
            Log.w(TAG, "availableBytes root fstatvfs failed: ${e.message}")
        } finally {
            try { rootPfd?.close() } catch (_: Exception) {}
        }

        // Fallback: create+delete a probe document (two directory-entry writes).
        var pfd: ParcelFileDescriptor? = null
        var probeUri: Uri? = null
        return try {
            probeUri = DocumentsContract.createDocument(
                resolver, rootUri, "application/octet-stream", FREE_SPACE_PROBE_NAME)
                ?: return Long.MAX_VALUE
            pfd = resolver.openFileDescriptor(probeUri, "r")
                ?: return Long.MAX_VALUE
            fstatvfsFree(pfd)
        } catch (e: Exception) {
            Log.w(TAG, "availableBytes fstatvfs failed: ${e.message}")
            Long.MAX_VALUE
        } finally {
            try { pfd?.close() } catch (_: Exception) {}
            probeUri?.let { u ->
                try { DocumentsContract.deleteDocument(resolver, u) }
                catch (e: Exception) { Log.w(TAG, "availableBytes: could not delete probe: ${e.message}") }
            }
        }
    }

    /** fstatvfs on [pfd] and return free bytes; [Long.MAX_VALUE] if unavailable/negative. */
    private fun fstatvfsFree(pfd: ParcelFileDescriptor): Long {
        val st = Os.fstatvfs(pfd.fileDescriptor)
        val free = st.f_bavail * st.f_frsize
        return if (free < 0) Long.MAX_VALUE else free
    }

    override fun exists(relPath: String): Boolean = resolveUri(relPath) != null

    override fun stat(relPath: String): UsbFdBridge.PathInfo? {
        val uri = resolveUri(relPath) ?: return null
        return try {
            resolver.query(
                uri,
                arrayOf(
                    DocumentsContract.Document.COLUMN_MIME_TYPE,
                    DocumentsContract.Document.COLUMN_SIZE,
                    DocumentsContract.Document.COLUMN_LAST_MODIFIED),
                null, null, null)?.use { c ->
                if (!c.moveToFirst()) return null
                val mime = c.getString(0)
                val isDir = mime == DocumentsContract.Document.MIME_TYPE_DIR
                val size = if (c.isNull(1)) 0L else c.getLong(1)
                val mtime = if (c.isNull(2)) 0L else c.getLong(2)
                UsbFdBridge.PathInfo(isDir, size, mtime)
            } ?: null
        } catch (e: Exception) {
            Log.e(TAG, "stat: ${e.message}"); null
        }
    }

    fun openReadPfd(relPath: String): ParcelFileDescriptor? {
        val uri = resolveUri(relPath) ?: return null
        return try {
            resolver.openFileDescriptor(uri, "r")
        } catch (e: Exception) {
            Log.e(TAG, "openReadPfd: ${e.message}")
            null
        }
    }

    // ── FTP-only read-only API (plan 07 §2.1) ─────────────────────────────────
    //
    // These methods never take a pathLock and never put/remove entries in the
    // shared [cache], so FTP can neither block transmission's resolveUri walks
    // nor poison the cache transmission trusts. Metadata/open calls resolve via
    // [resolveUriReadOnly], which falls back to a walk that only populates the
    // private [roCache]. Stale roCache entries are evicted on FileNotFound / empty
    // cursor and the lookup retried once with a fresh walk.

    /** Read-only stat for FTP. Null when the document does not exist. */
    fun readOnlyStat(relPath: String): UsbFdBridge.PathInfo? {
        val n = SafPaths.normalize(relPath)
        val uri = resolveUriReadOnly(n) ?: return null
        queryStatQuietly(uri)?.let { return it }
        // Stale entry (or a doc that vanished): drop FTP's view of it and retry once.
        evictReadOnly(n)
        val fresh = resolveUriReadOnlyFresh(n) ?: return null
        return queryStatQuietly(fresh)
    }

    /** Read-only single-query detailed listing for FTP; populates only [roCache]. */
    fun readOnlyListDetailed(relPath: String): List<ChildEntry> {
        val n = SafPaths.normalize(relPath)
        val parentUri = resolveUriReadOnly(n) ?: return emptyList()
        val out = queryChildrenDetailed(n, parentUri)
        if (out == null) {
            // Children query failed (e.g. parent URI gone): retry once with a fresh walk.
            evictReadOnly(n)
            val fresh = resolveUriReadOnlyFresh(n) ?: return emptyList()
            return queryChildrenDetailed(n, fresh) ?: emptyList()
        }
        return out
    }

    /**
     * Read-only open for FTP streaming. Returns a pfd the caller owns (and
     * typically detaches). Streaming reads on the returned fd are NOT part of
     * this call. Null on miss.
     */
    fun readOnlyOpenRead(relPath: String): ParcelFileDescriptor? {
        val n = SafPaths.normalize(relPath)
        val uri = resolveUriReadOnly(n) ?: return null
        var pfd = try { resolver.openFileDescriptor(uri, "r") }
        catch (e: Exception) { Log.e(TAG, "readOnlyOpenRead: ${e.message}"); null }
        if (pfd == null) {
            evictReadOnly(n)
            val fresh = resolveUriReadOnlyFresh(n) ?: return null
            pfd = try { resolver.openFileDescriptor(fresh, "r") }
            catch (e: Exception) { Log.e(TAG, "readOnlyOpenRead retry: ${e.message}"); null }
        }
        return pfd
    }

    /** Drop [relPath] and any cached descendants from the private FTP [roCache]. */
    fun evictReadOnly(relPath: String) {
        val n = SafPaths.normalize(relPath)
        roCache.keys.filter { it == n || it.startsWith("$n/") }.forEach { roCache.remove(it) }
    }

    private fun queryStatQuietly(uri: Uri): UsbFdBridge.PathInfo? = try {
        resolver.query(
            uri,
            arrayOf(
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED),
            null, null, null)?.use { c ->
            if (!c.moveToFirst()) return null
            val mime = c.getString(0)
            val isDir = mime == DocumentsContract.Document.MIME_TYPE_DIR
            val size = if (c.isNull(1)) 0L else c.getLong(1)
            val mtime = if (c.isNull(2)) 0L else c.getLong(2)
            UsbFdBridge.PathInfo(isDir, size, mtime)
        } ?: null
    } catch (e: Exception) {
        Log.e(TAG, "readOnlyStat: ${e.message}")
        null
    }

    /** Returns null when the children query itself failed (distinct from an empty dir). */
    private fun queryChildrenDetailed(relPath: String, parentUri: Uri): List<ChildEntry>? {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
            treeUri, DocumentsContract.getDocumentId(parentUri))
        return try {
            resolver.query(childrenUri,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE,
                    DocumentsContract.Document.COLUMN_SIZE,
                    DocumentsContract.Document.COLUMN_LAST_MODIFIED),
                null, null, null)?.use { c ->
                val idxId = c.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val idxName = c.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val idxMime = c.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
                val idxSize = c.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
                val idxMtime = c.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                val out = mutableListOf<ChildEntry>()
                while (c.moveToNext()) {
                    val docId = if (idxId >= 0) c.getString(idxId) else null
                    val name = if (idxName >= 0) c.getString(idxName) ?: continue else continue
                    val mime = if (idxMime >= 0) c.getString(idxMime) else null
                    val isDir = mime == DocumentsContract.Document.MIME_TYPE_DIR
                    val size = if (idxSize >= 0 && !c.isNull(idxSize)) c.getLong(idxSize) else 0L
                    val mtime = if (idxMtime >= 0 && !c.isNull(idxMtime)) c.getLong(idxMtime) else 0L
                    if (docId != null) {
                        val childPath = if (relPath.isEmpty()) name else "$relPath/$name"
                        roCache[childPath] =
                            DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
                    }
                    out.add(ChildEntry(name, isDir, size, mtime))
                }
                out
            }
        } catch (e: Exception) { Log.e(TAG, "readOnlyListDetailed: ${e.message}"); null }
    }

    /**
     * Resolve [relPath] (normalized) to a document URI for the FTP read path.
     * Reads the shared [cache] without writing it; on miss walks the tree using
     * only the private [roCache]. No pathLock acquisition at all — duplicate
     * concurrent walks are acceptable for FTP.
     */
    private fun resolveUriReadOnly(relPath: String): Uri? =
        resolveUriReadOnlyWalk(relPath, fresh = false)

    /** Like [resolveUriReadOnly] but bypasses [roCache] (stale-entry retry). */
    private fun resolveUriReadOnlyFresh(relPath: String): Uri? =
        resolveUriReadOnlyWalk(relPath, fresh = true)

    private fun resolveUriReadOnlyWalk(n: String, fresh: Boolean): Uri? {
        if (!fresh) {
            cache[n]?.let { return it }          // read-only peek at shared cache
            roCache[n]?.let { return it }
        }
        if (n.isEmpty()) return cache[""]
        if (roCache.size > RO_CACHE_MAX_ENTRIES) roCache.clear()  // bound FTP memory
        val parent = SafPaths.splitParent(n) ?: return null
        val parentUri = resolveUriReadOnlyWalk(parent.first, fresh) ?: return null
        if (!fresh) roCache[n]?.let { return it }
        val leaf = parent.second
        // The provider transliterates FAT-illegal characters on create (SafNames), so a
        // child matching the sanitized leaf is this file. Cache keys are the requested
        // path, so record the alias under [n] too — the FTP cache is walk-rebuilt.
        val onDiskLeaf = SafNames.sanitize(leaf)
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
            treeUri, DocumentsContract.getDocumentId(parentUri))
        return try {
            resolver.query(childrenUri,
                arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val docId = c.getString(0)
                    val name = c.getString(1) ?: continue
                    val childPath = if (parent.first.isEmpty()) name else "${parent.first}/$name"
                    val childUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
                    roCache[childPath] = childUri
                    if (name == onDiskLeaf) {
                        roCache[n] = childUri   // requested path -> transliterated doc
                        return childUri
                    }
                }
                null
            }
        } catch (e: Exception) { Log.e(TAG, "resolveUriReadOnly walk: ${e.message}"); null }
    }

    // ── internal test hooks (plan 07 §3) ──────────────────────────────────────
    internal fun sharedCacheKeys(): Set<String> = cache.keys.toSet()
    internal fun sharedCacheSize(): Int = cache.size
    internal fun pathLockCount(): Int = pathLocks.size
    internal fun roCacheSize(): Int = roCache.size

    override fun list(relPath: String): List<String> {
        val parentUri = resolveUri(relPath) ?: return emptyList()
        val out = mutableListOf<String>()
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
            treeUri, DocumentsContract.getDocumentId(parentUri))
        return try {
            resolver.query(childrenUri,
                arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                null, null, null)?.use { c ->
                while (c.moveToNext()) out.add(c.getString(0) ?: "")
                // cache children
                out.forEach { name ->
                    val childPath = if (relPath.isEmpty()) name else "$relPath/$name"
                    // don't overwrite; full child URI not needed for list callers
                }
                out
            } ?: emptyList()
        } catch (e: Exception) { Log.e(TAG, "list: ${e.message}"); emptyList() }
    }
    data class ChildEntry(val name: String, val isDir: Boolean, val size: Long, val lastModified: Long)

    /**
     * List children of [relPath] with full metadata (name, isDir, size, mtime) in a
     * single SAF children query. Replaces the N+1 pattern of [list] + per-child
     * [stat] that amplified lock contention under FTP directory listings.
     */
    fun listDetailed(relPath: String): List<ChildEntry> {
        val parentUri = resolveUri(relPath) ?: return emptyList()
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
            treeUri, DocumentsContract.getDocumentId(parentUri))
        val out = mutableListOf<ChildEntry>()
        return try {
            resolver.query(childrenUri,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE,
                    DocumentsContract.Document.COLUMN_SIZE,
                    DocumentsContract.Document.COLUMN_LAST_MODIFIED),
                null, null, null)?.use { c ->
                val idxId = c.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val idxName = c.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val idxMime = c.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
                val idxSize = c.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
                val idxMtime = c.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                while (c.moveToNext()) {
                    val docId = if (idxId >= 0) c.getString(idxId) else null
                    val name = if (idxName >= 0) c.getString(idxName) ?: continue else continue
                    val mime = if (idxMime >= 0) c.getString(idxMime) else null
                    val isDir = mime == DocumentsContract.Document.MIME_TYPE_DIR
                    val size = if (idxSize >= 0 && !c.isNull(idxSize)) c.getLong(idxSize) else 0L
                    val mtime = if (idxMtime >= 0 && !c.isNull(idxMtime)) c.getLong(idxMtime) else 0L
                    if (docId != null) {
                        val childPath = if (relPath.isEmpty()) name else "$relPath/$name"
                        val childUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
                        cache[childPath] = childUri
                    }
                    out.add(ChildEntry(name, isDir, size, mtime))
                }
                out
            } ?: emptyList()
        } catch (e: Exception) { Log.e(TAG, "listDetailed: ${e.message}"); emptyList() }
    }

    // internals

    /** Open a pfd WITHOUT detaching; caller's block closes it. Returns null on miss. */
    private inline fun <T> withPfd(relPath: String, mode: String, block: (ParcelFileDescriptor) -> T): T? {
        val uri = resolveUri(relPath) ?: return null
        return try {
            resolver.openFileDescriptor(uri, mode)?.use { block(it) }
        } catch (e: Exception) { Log.e(TAG, "withPfd: ${e.message}"); null }
    }

    /** Return or create a per-path ReentrantLock for [path]. */
    private fun lockFor(path: String): ReentrantLock =
        pathLocks.computeIfAbsent(path) { ReentrantLock() }

    /**
     * Resolve a normalized relPath to a document URI, walking + caching the tree on miss.
     * Does NOT create documents. Uses per-path lock for the final leaf only; recursion
     * is lock-free (parent locks are acquired by parent calls).
     */
    private fun resolveUri(relPath: String): Uri? {
        val n = SafPaths.normalize(relPath)
        cache[n]?.let { return it }
        if (n.isEmpty()) return cache[""]
        val parent = SafPaths.splitParent(n) ?: return null
        val parentUri = resolveUri(parent.first) ?: return null
        val leaf = parent.second
        // A FAT-illegal leaf is stored under the provider's transliteration (SafNames),
        // so match either form; cache[n] then aliases the requested path to it.
        val onDiskLeaf = SafNames.sanitize(leaf)
        // double-checked: another thread may have populated cache while we waited
        cache[n]?.let { return it }
        val lock = lockFor(n)
        lock.lock()
        try {
            cache[n]?.let { return it }
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
                treeUri, DocumentsContract.getDocumentId(parentUri))
            try {
                resolver.query(childrenUri,
                    arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                    null, null, null)?.use { c ->
                    while (c.moveToNext()) {
                        val docId = c.getString(0)
                        val name = c.getString(1) ?: continue
                        val childPath = if (parent.first.isEmpty()) name else "${parent.first}/$name"
                        val childUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
                        cache[childPath] = childUri
                        if (name == onDiskLeaf) return childUri.also { cache[n] = it }
                    }
                }
            } catch (e: Exception) { Log.e(TAG, "resolveUri walk: ${e.message}"); return null }
            return null
        } finally {
            lock.unlock()
        }
    }
    /** Ensure [relPath] exists as a regular document, creating parents + the file. */
    private fun ensureDocument(relPath: String): Uri? {
        val n = SafPaths.normalize(relPath)
        if (n.isEmpty()) return cache[""]
        cache[n]?.let { return it }
        val parent = SafPaths.splitParent(n) ?: return null
        val parentUri = resolveOrCreateDir(parent.first) ?: return null
        val leaf = parent.second
        // does it already exist?
        resolveUri(n)?.let { return it }
        val lock = lockFor(n)
        var result: Uri? = null
        lock.lock()
        try {
            // double-check after acquiring lock
            cache[n]?.let { return it }
            resolveUri(n)?.let { return it }
            // create a regular document
            val mimeType = "application/octet-stream"
            val created = DocumentsContract.createDocument(resolver, parentUri, mimeType, leaf)
                ?: return null
            // SAF providers can answer a create with a different name. Two cases:
            //
            //  1. Transliteration (SafNames): the volume is exFAT served by
            //     ExternalStorageProvider, which rewrites FAT-illegal characters
            //     ('|', ':', '?', ...) to '_' on create. Torrent metadata may contain
            //     them (e.g. 'Subs/Canadian | SDH.eng.HI.srt'). The document is the one
            //     we asked for, just under the storable spelling — accept it and alias
            //     the requested path to it. Rejecting this makes the file permanently
            //     unopenable and the torrent unsaveable.
            //  2. Deduplication: a colliding name gets a " (1)" suffix (Bug 4).
            //     Transmission's resume file references the exact requested name, so a
            //     deduped file is unrecoverable. Delete the duplicate and fail loudly so
            //     the caller sees a clean error rather than a phantom file.
            val actualName = resolver.query(
                created,
                arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                null, null, null)?.use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getString(0) else null }
            if (actualName != null && actualName != leaf) {
                if (SafNames.isAliasFor(leaf, actualName)) {
                    Log.w(TAG, "ensureDocument: '$leaf' stored as '$actualName' (FAT-illegal char transliterated; path '$n')")
                } else {
                    Log.e(TAG, "ensureDocument: name collision; requested '$leaf' got '$actualName' (path '$n')")
                    try { DocumentsContract.deleteDocument(resolver, created) } catch (_: Exception) {}
                    cache.remove(n)
                    return null
                }
            }
            // Aliased or exact: cache under the *requested* path so the daemon's view of
            // the tree stays in its own namespace. The on-disk name may differ; the walks
            // in resolveUri/resolveUriReadOnlyWalk match the sanitized leaf too, so the
            // alias survives a cache reset or process restart.
            cache[n] = created
            result = created
        } catch (e: Exception) { Log.e(TAG, "ensureDocument create: ${e.message}") }
        finally {
            lock.unlock()
        }
        return result
    }

    /** Resolve or create [relPath] as a directory, walking+creating ancestors. */
    private fun resolveOrCreateDir(relPath: String): Uri? {
        val n = SafPaths.normalize(relPath)
        if (n.isEmpty()) return cache[""]
        cache[n]?.let { return it }
        val parent = SafPaths.splitParent(n) ?: return null
        val parentUri = if (parent.first.isEmpty()) cache[""]!! else resolveOrCreateDir(parent.first) ?: return null
        val leaf = parent.second
        resolveUri(n)?.let { return it }   // existing dir
        val lock = lockFor(n)
        var result: Uri? = null
        lock.lock()
        try {
            // double-check after acquiring lock
            cache[n]?.let { return it }
            resolveUri(n)?.let { return it }
            val created = DocumentsContract.createDocument(
                resolver, parentUri, DocumentsContract.Document.MIME_TYPE_DIR, leaf)
            if (created != null) { cache[n] = created; result = created }
        } catch (e: Exception) { Log.e(TAG, "mkdir create: ${e.message}") }
        finally {
            lock.unlock()
        }
        return result
    }

    fun shutdown() {
        freeSpaceExecutor.shutdown()
    }
    companion object {
        private const val TAG = "SafStorageGateway"
        private const val FREE_SPACE_TTL_MS = 30_000L
        private const val FREE_SPACE_PROBE_NAME = ".tr-freespace-probe"
        private const val RO_CACHE_MAX_ENTRIES = 4096
    }
}