package com.torrent.app.backup

import java.io.File
import java.io.IOException

/**
 * The file-system half of the Kotlin-side crawler DB restore (plan 3, Stage 2):
 * reaping the stale `.sql` dumps the old concat-then-replay flow left behind,
 * and swapping the freshly imported temp DB over the live one.
 *
 * Pure `java.io` — no Android imports, so it is plain-JUnit testable (see
 * `RestoreFileOpsTest`). Every helper is a best-effort delete except the one
 * step that must not be silent: [swapIn]'s rename, which throws when it fails
 * so a restore can never report success while the live DB is unchanged.
 */
object RestoreFileOps {

    /** File-name prefix of the (now obsolete) concatenated dump temp files. */
    const val STALE_DUMP_PREFIX = "restore-"

    /** File-name suffix of the (now obsolete) concatenated dump temp files. */
    const val STALE_DUMP_SUFFIX = ".sql"

    /**
     * Deletes every `restore-*.sql` file directly inside [dir] — the dumps the
     * previous restore flow concatenated into the app's tmp dir and never
     * reaped (two ~33 GB files on-device). Unrelated files, dotfiles,
     * subdirectories and files that merely share the prefix or suffix
     * (`restore-1.sql.tmp`, `other.sql`) are left alone.
     *
     * A missing/unreadable [dir] reaps nothing. Returns the number of files
     * that were actually deleted (a file that refuses to be deleted is
     * skipped, not thrown on: reaping is housekeeping, never a restore
     * precondition).
     */
    fun reapStaleSqlDumps(dir: File): Int {
        val files = dir.listFiles() ?: return 0
        var reaped = 0
        for (f in files) {
            if (!f.isFile) continue
            val name = f.name
            if (!name.startsWith(STALE_DUMP_PREFIX)) continue
            if (!name.endsWith(STALE_DUMP_SUFFIX)) continue
            if (f.delete()) reaped++
        }
        return reaped
    }

    /**
     * Deletes the `-wal` and `-shm` sidecars SQLite keeps next to [db], if any.
     * Best effort: a missing sidecar (or one that refuses to be deleted) is
     * not an error.
     */
    fun deleteDbSidecars(db: File) {
        File(db.path + "-wal").delete()
        File(db.path + "-shm").delete()
    }

    /**
     * Replaces [targetDb] with [tmpDb], mirroring the native order
     * (`restore-database.patch:73-92`): remove the target's stale `-wal`/`-shm`
     * first (they belong to the DB being replaced), delete the target, rename
     * the temp DB into place, then delete the temp DB's own leftover
     * `-wal`/`-shm` defensively.
     *
     * The temp DB must already be checkpointed and closed
     * ([RestoreStatementSink.finishImport] does both), otherwise data still in
     * its `-wal` would be dropped by the sidecar cleanup.
     *
     * @throws IllegalArgumentException when [tmpDb] is not a regular file.
     * @throws IOException when the target cannot be deleted or the rename fails
     *   — the caller must then treat the restore as failed (the temp DB is left
     *   in place for the next attempt).
     */
    fun swapIn(tmpDb: File, targetDb: File) {
        require(tmpDb.isFile) { "temp db missing: ${tmpDb.path}" }
        deleteDbSidecars(targetDb)
        if (targetDb.exists() && !targetDb.delete()) {
            throw IOException("failed to delete target db: ${targetDb.path}")
        }
        if (!tmpDb.renameTo(targetDb)) {
            throw IOException("temp db rename failed: ${tmpDb.path} -> ${targetDb.path}")
        }
        deleteDbSidecars(tmpDb)
    }

    /**
     * `true` when [db] is a regular, non-empty file — i.e. an actual database
     * rather than a missing path or the zero-length file a failed create
     * leaves behind. Used to decide whether a restored database is present.
     */
    fun targetDbExists(db: File): Boolean = db.isFile && db.length() > 0L
}
