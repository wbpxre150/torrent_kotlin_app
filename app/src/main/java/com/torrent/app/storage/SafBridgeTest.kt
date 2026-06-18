package com.torrent.app.storage

/**
 * JNI surface for the native SAF fd validation + throughput measurement (libsaftest.so).
 *
 * Returns: 0 on success, negative errno on failure. Kotlin owns all display/logging.
 */
object SafBridgeTest {
    fun ensureLoaded() { loaded }   // touch to trigger <init>

    private object loaded {
        init { System.loadLibrary("saftest") }
    }

    /**
     * Validate [fd] is seekable and supports pwrite/pread/ftruncate/fsync at [offset].
     * @return 0 on success, negative errno on failure.
     */
    external fun nativeValidateFd(fd: Int, offset: Long): Long

    /**
     * Write [bytes] (1 MiB chunks) sequentially to [fd], return elapsed nanoseconds,
     * or negative errno on failure.
     */
    external fun nativeThroughput(fd: Int, bytes: Long): Long
}
