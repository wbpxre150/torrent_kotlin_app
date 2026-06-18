package com.torrent.app.storage

import android.os.ParcelFileDescriptor
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Runs the on-device SAF fd validation: opens a test file under the USB tree via the
 * gateway, hands the raw fd to the native validate+throughput fns, then removes the
 * test file. Safe to run on a coroutine; does disk I/O on Dispatchers.IO.
 */
class SafValidator(private val bridge: UsbFdBridge) {

    suspend fun run(): SafValidationResult = withContext(Dispatchers.IO) {
        SafBridgeTest.ensureLoaded()
        val testRelPath = "saftest/saftest.bin"
        val offset = 5L * 1024 * 1024 * 1024   // 5 GiB > 4 GiB FAT32 cap

        if (!bridge.mkdirs("saftest")) {
            return@withContext SafValidationResult(
                ok = false, message = "mkdirs(\"saftest\") failed",
                throughputMBs = null, offsetBytes = offset, fat32Warning = false)
        }

        val fd = bridge.openFd(testRelPath, "rw")
        if (fd < 0) {
            return@withContext SafValidationResult(
                ok = false, message = "openFd failed (fd=$fd)",
                throughputMBs = null, offsetBytes = offset, fat32Warning = false)
        }

        // Re-adopt the detached fd so Kotlin owns + closes it after the native calls.
        val pfd = ParcelFileDescriptor.adoptFd(fd)
        try {
            val code = SafBridgeTest.nativeValidateFd(pfd.fd, offset)
            if (code != 0L) {
                // EFBIG(27)/ENOSPC(28) near 4GB strongly suggests a FAT32 filesystem.
                val fat32 = (code == -27L || code == -28L)
                return@withContext SafValidationResult(
                    ok = false,
                    message = "native validate failed (errno=${-code})",
                    throughputMBs = null,
                    offsetBytes = offset,
                    fat32Warning = fat32)
            }

            val throughputBytes = 64L * 1024 * 1024   // 64 MiB
            val ns = SafBridgeTest.nativeThroughput(pfd.fd, throughputBytes)
            val mbps = if (ns > 0) {
                (throughputBytes.toDouble() / (1024.0 * 1024.0)) / (ns / 1_000_000_000.0)
            } else null

            SafValidationResult(
                ok = true,
                message = "OK: seekable; pwrite/pread/ftruncate/fsync at 5 GiB offset",
                throughputMBs = mbps,
                offsetBytes = offset,
                fat32Warning = false)
        } catch (e: Exception) {
            Log.e(TAG, "validation threw", e)
            SafValidationResult(
                ok = false, message = "exception: ${e.message}",
                throughputMBs = null, offsetBytes = offset, fat32Warning = false)
        } finally {
            try { pfd.close() } catch (_: Exception) {}
            try { bridge.removeDoc(testRelPath) } catch (_: Exception) {}
            try { bridge.removeDoc("saftest") } catch (_: Exception) {}
        }
    }

    companion object {
        private const val TAG = "SafValidator"
    }
}
