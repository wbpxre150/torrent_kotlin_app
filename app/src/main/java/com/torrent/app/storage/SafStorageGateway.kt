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

/**
 * The single SAF choke point. All USB I/O in the app goes through here.
 *
 * Thread-safety: the path->URI cache is a ConcurrentHashMap; cache misses are resolved
 * under a synchronized walk. All public methods are synchronous and safe to call from
 * transmission's disk thread (Stage 05).
 */
class SafStorageGateway(
    private val context: Context,
    private val treeUri: Uri,
) : UsbFdBridge {

    private val resolver = context.contentResolver
    private val cache = ConcurrentHashMap<String, Uri>()
    private var cachedFreeSpace: Pair<Long, Long>? = null

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

    @Synchronized
    override fun renameDoc(relPath: String, newName: String): Boolean {
        val n = SafPaths.normalize(relPath)
        val parent = SafPaths.splitParent(n) ?: return false
        val srcUri = resolveUri(n) ?: return false
        val targetPath = if (parent.first.isEmpty()) newName else "${parent.first}/$newName"
        return try {
            // SAF's renameDocument auto-appends " (1)" on a name collision instead of
            // overwriting (POSIX rename replaces the target). Emulate POSIX: remove an
            // existing target first so the rename lands on the exact requested name and
            // transmission does not silently end up referencing a phantom " (1)" file
            // (mirrors ensureDocument collision handling, Bug 4). @Synchronized keeps the
            // check-delete-rename atomic w.r.t. other gateway calls, so no interleave can
            // resurrect the target between the delete and the rename.
            resolveUri(targetPath)?.let { existing ->
                try {
                    DocumentsContract.deleteDocument(resolver, existing)
                } catch (e: Exception) {
                    Log.e(TAG, "renameDoc: cannot remove existing target '$targetPath': ${e.message}")
                    return false
                }
                cache.keys.filter { it == targetPath || it.startsWith("$targetPath/") }
                    .forEach { cache.remove(it) }
            }
            val renamed = DocumentsContract.renameDocument(resolver, srcUri, newName)
                ?: return false
            cache.remove(n)
            cache[targetPath] = renamed
            true
        } catch (e: Exception) { Log.e(TAG, "renameDoc: ${e.message}"); false }
    }

    override fun removeDoc(relPath: String): Boolean {
        val n = SafPaths.normalize(relPath)
        val uri = resolveUri(n) ?: return false
        // invalidate this node and any cached descendants
        cache.keys.filter { it == n || it.startsWith("$n/") }.forEach { cache.remove(it) }
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
        val free = probeFreeSpaceViaFd()
        if (free != Long.MAX_VALUE) {
            cachedFreeSpace = free to now
        }
        return free
    }

    /**
     * Read free bytes via Os.fstatvfs on a SAF-granted file descriptor.
     *
     * COLUMN_AVAILABLE_BYTES is unpopulated for removable volumes on AOSP
     * (reportAvailableBytes stays false), and StorageStatsManager has no UUID
     * for portable volumes. A probe document is created in the tree root so we
     * can open it and fstatvfs its fd, which returns the free space of the
     * exact filesystem the tree lives on. The probe is deleted in [finally].
     */
    private fun probeFreeSpaceViaFd(): Long {
        val rootUri = cache[""] ?: return Long.MAX_VALUE
        var pfd: ParcelFileDescriptor? = null
        var probeUri: Uri? = null
        return try {
            probeUri = DocumentsContract.createDocument(
                resolver, rootUri, "application/octet-stream", FREE_SPACE_PROBE_NAME)
                ?: return Long.MAX_VALUE
            pfd = resolver.openFileDescriptor(probeUri, "r")
                ?: return Long.MAX_VALUE
            val st = Os.fstatvfs(pfd.fileDescriptor)
            val free = st.f_bavail * st.f_frsize
            if (free < 0) Long.MAX_VALUE else free
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

    // internals

    /** Open a pfd WITHOUT detaching; caller's block closes it. Returns null on miss. */
    private inline fun <T> withPfd(relPath: String, mode: String, block: (ParcelFileDescriptor) -> T): T? {
        val uri = resolveUri(relPath) ?: return null
        return try {
            resolver.openFileDescriptor(uri, mode)?.use { block(it) }
        } catch (e: Exception) { Log.e(TAG, "withPfd: ${e.message}"); null }
    }

    /**
     * Resolve a normalized relPath to a document URI, walking + caching the tree on miss.
     * Does NOT create documents.
     */
    @Synchronized
    private fun resolveUri(relPath: String): Uri? {
        val n = SafPaths.normalize(relPath)
        cache[n]?.let { return it }
        if (n.isEmpty()) return cache[""]
        val parent = SafPaths.splitParent(n) ?: return null
        val parentUri = resolveUri(parent.first) ?: return null
        val leaf = parent.second
        // query children of parentUri for a matching display name
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
                    if (name == leaf) return childUri
                }
            }
        } catch (e: Exception) { Log.e(TAG, "resolveUri walk: ${e.message}"); return null }
        return null
    }

    /** Ensure [relPath] exists as a regular document, creating parents + the file. */
    @Synchronized
    private fun ensureDocument(relPath: String): Uri? {
        val n = SafPaths.normalize(relPath)
        if (n.isEmpty()) return cache[""]
        cache[n]?.let { return it }
        val parent = SafPaths.splitParent(n) ?: return null
        val parentUri = resolveOrCreateDir(parent.first) ?: return null
        val leaf = parent.second
        // does it already exist?
        resolveUri(n)?.let { return it }
        // create a regular document
        return try {
            val mimeType = "application/octet-stream"
            val created = DocumentsContract.createDocument(resolver, parentUri, mimeType, leaf)
                ?: return null
            // SAF providers deduplicate colliding names by appending " (1)" etc.
            // (Bug 4). Transmission's resume file references the exact requested
            // name, so a renamed file is unrecoverable. Detect the collision, delete
            // the duplicate, and fail loudly so the caller sees a clean error rather
            // than a phantom file.
            val actualName = resolver.query(
                created,
                arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                null, null, null)?.use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getString(0) else null }
            if (actualName != null && actualName != leaf) {
                Log.e(TAG, "ensureDocument: name collision; requested '$leaf' got '$actualName' (path '$n')")
                try { DocumentsContract.deleteDocument(resolver, created) } catch (_: Exception) {}
                cache.remove(n)
                return null
            }
            cache[n] = created
            created
        } catch (e: Exception) { Log.e(TAG, "ensureDocument create: ${e.message}"); null }
    }

    /** Resolve or create [relPath] as a directory, walking+creating ancestors. */
    @Synchronized
    private fun resolveOrCreateDir(relPath: String): Uri? {
        val n = SafPaths.normalize(relPath)
        if (n.isEmpty()) return cache[""]
        cache[n]?.let { return it }
        val parent = SafPaths.splitParent(n) ?: return null
        val parentUri = if (parent.first.isEmpty()) cache[""]!! else resolveOrCreateDir(parent.first) ?: return null
        val leaf = parent.second
        resolveUri(n)?.let { return it }   // existing dir
        return try {
            val created = DocumentsContract.createDocument(
                resolver, parentUri, DocumentsContract.Document.MIME_TYPE_DIR, leaf)
            if (created != null) { cache[n] = created; created } else null
        } catch (e: Exception) { Log.e(TAG, "mkdir create: ${e.message}"); null }
    }

    companion object {
        private const val TAG = "SafStorageGateway"
        private const val FREE_SPACE_TTL_MS = 5_000L
        private const val FREE_SPACE_PROBE_NAME = ".tr-freespace-probe"
    }
}