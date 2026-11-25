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
     * Replaces [targetDb] with [tmpDb] atomically: remove the target's stale `-wal`/`-shm`
     * first (they belong to the DB being replaced), rename the temp DB
     * straight over the target — an atomic POSIX replace, so the target path
     * holds either the old DB or the new one and never nothing — then delete
     * the temp DB's own leftover `-wal`/`-shm` defensively.
     *
     * The rename goes over the target directly on purpose: deleting the target
     * first would open a crash window in which the live path holds NO database,
     * on exactly the crash-prone device this restore targets. Only when
     * [renameOp] reports failure (a filesystem/JVM that refuses to replace an
     * existing file) does the swap fall back to delete-then-rename once, and
     * throw when that fails too.
     *
     * The temp DB must already be checkpointed and closed
     * ([RestoreStatementSink.finishImport] does both), otherwise data still in
     * its `-wal` would be dropped by the sidecar cleanup.
     *
     * @param renameOp the rename primitive, injectable so the fallback branch is
     *   unit-testable; production always uses [File.renameTo].
     * @throws IllegalArgumentException when [tmpDb] is not a regular file.
     * @throws IOException when the target cannot be deleted (fallback path) or
     *   the rename fails — the caller must then treat the restore as failed (the
     *   temp DB is left in place for the next attempt).
     */
    fun swapIn(
        tmpDb: File,
        targetDb: File,
        renameOp: (File, File) -> Boolean = { from, to -> from.renameTo(to) },
    ) {
        require(tmpDb.isFile) { "temp db missing: ${tmpDb.path}" }
        deleteDbSidecars(targetDb)
        if (!renameOp(tmpDb, targetDb)) {
            // Fallback for a filesystem that refuses to replace an existing
            // file: delete the target and retry the rename once. This is the
            // only path that ever leaves the target missing.
            if (targetDb.exists() && !targetDb.delete()) {
                throw IOException("failed to delete target db: ${targetDb.path}")
            }
            if (!renameOp(tmpDb, targetDb)) {
                throw IOException("temp db rename failed: ${tmpDb.path} -> ${targetDb.path}")
            }
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
