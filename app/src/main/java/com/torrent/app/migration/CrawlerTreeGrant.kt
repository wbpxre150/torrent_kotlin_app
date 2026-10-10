package com.torrent.app.migration

import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * Owns the persisted SAF tree URI for the Termux migration source
 * (plan 10 §6.2). `OpenDocumentTree` grants persistable read+write on the
 * picked tree: the read grant is what lets a long copy survive a service
 * restart, and the write grant is only exercised by the separate,
 * explicitly-confirmed "delete source files" action. [clear] releases the
 * grant when the migration is confirmed.
 */
class CrawlerTreeGrant(private val context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** The persisted tree URI, or null when nothing is stored / revoked. */
    fun treeUri(): Uri? {
        val s = prefs.getString(KEY_TREE_URI, null) ?: return null
        val uri = Uri.parse(s)
        if (!isStillGranted(uri)) {
            // Permission was revoked by the system - clean up the stale entry.
            prefs.edit().remove(KEY_TREE_URI).apply()
            return null
        }
        return uri
    }

    fun isGranted(): Boolean = treeUri() != null

    /**
     * Persists the [uri] picked by `OpenDocumentTree` (replaces any previous
     * tree). Call from the activity's picker callback.
     */
    fun grant(uri: Uri) {
        context.contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        prefs.edit().putString(KEY_TREE_URI, uri.toString()).apply()
    }

    /** Releases the persisted permission and forgets the URI. */
    fun clear() {
        val s = prefs.getString(KEY_TREE_URI, null)
        val uri = if (s != null) Uri.parse(s) else null
        if (uri != null) {
            try {
                context.contentResolver.releasePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            } catch (_: SecurityException) {
                // already released
            }
        }
        prefs.edit().remove(KEY_TREE_URI).apply()
    }

    private fun isStillGranted(uri: Uri): Boolean =
        context.contentResolver.persistedUriPermissions.any {
            it.uri == uri && it.isReadPermission
        }

    companion object {
        const val PREFS = "crawler_migration"
        private const val KEY_TREE_URI = "tree_uri"
    }
}
