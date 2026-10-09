package com.torrent.app.ftp

import android.os.Process

/**
 * Lowers the CPU priority of threads that execute FTP entry points (file-source
 * metadata/open calls, stream reads). These threads are owned by the FTP server
 * only, so this can never affect transmission's IPC disk thread.
 *
 * Applied once per thread (ThreadLocal flag). [priorityFn] is injectable so JVM
 * unit tests can assert the call without a real Android runtime; all failures
 * are swallowed so plain (Robolectric-less) test environments keep working.
 */
object FtpThreads {
    private val lowered = ThreadLocal.withInitial { false }

    var priorityFn: (Int) -> Unit = { p ->
        try { Process.setThreadPriority(p) } catch (_: Throwable) { /* JVM unit test */ }
    }

    fun lowerPriority() {
        if (lowered.get()) return
        lowered.set(true)
        try { priorityFn(Process.THREAD_PRIORITY_BACKGROUND) } catch (_: Throwable) { }
    }
}
