package com.torrent.app.storage

import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * Owns the persisted SAF tree URI for the USB drive.
 * Survives reboot via takePersistableUriPermission + SharedPreferences.
 */
class UsbPermissionManager(private val context: Context) {

    private val prefs = context.getSharedPreferences("usb_storage", Context.MODE_PRIVATE)

    /** The persisted tree URI, or null if none / permission was revoked. */
    fun treeUri(): Uri? {
        val s = prefs.getString(KEY_TREE_URI, null) ?: return null
        val uri = Uri.parse(s)
        if (!isStillGranted(uri)) {
            // Permission was revoked by system - clean up the stale entry.
            // Do NOT call clear(): clear() reads via treeUri() and would recurse.
            prefs.edit().remove(KEY_TREE_URI).apply()
            return null
        }
        return uri
    }

    fun isGranted(): Boolean = treeUri() != null

    /**
     * Call from the ActivityResultContracts.OpenDocumentTree callback with the user's
     * chosen tree. Persists the URI permission + the URI string.
     */
    fun grant(treeUri: Uri) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
            Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        context.contentResolver.takePersistableUriPermission(treeUri, flags)
        prefs.edit().putString(KEY_TREE_URI, treeUri.toString()).apply()
    }

    /** Release the persisted permission and forget the URI. */
    fun clear() {
        val s = prefs.getString(KEY_TREE_URI, null)
        val uri = if (s != null) Uri.parse(s) else null
        if (uri != null) {
            try {
                val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                context.contentResolver.releasePersistableUriPermission(uri, flags)
            } catch (_: SecurityException) {
                // already released
            }
        }
        prefs.edit().remove(KEY_TREE_URI).apply()
    }

    private fun isStillGranted(uri: Uri): Boolean {
        val perms = context.contentResolver.persistedUriPermissions
        return perms.any {
            it.uri == uri && it.isReadPermission && it.isWritePermission
        }
    }

    companion object {
        private const val KEY_TREE_URI = "tree_uri"
    }
}