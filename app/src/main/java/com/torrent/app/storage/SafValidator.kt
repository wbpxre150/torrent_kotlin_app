package com.torrent.app.storage

import android.os.ParcelFileDescriptor
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Writes 512 MiB sequentially to a test file on the USB volume via the SAF gateway,
 * measures throughput, then removes the file. The drive is always exFAT; no sparse-file
 * or FAT32 tests are needed. Safe to call on a coroutine; does disk I/O on Dispatchers.IO.
 */
class SafValidator(private val bridge: UsbFdBridge) {

    suspend fun run(): SafValidationResult = withContext(Dispatchers.IO) {
        SafBridgeTest.ensureLoaded()
        val testRelPath = "saftest/saftest.bin"
        val throughputBytes = 512L * 1024 * 1024   // 512 MiB

        if (!bridge.mkdirs("saftest")) {
            return@withContext SafValidationResult(
                ok = false, message = "mkdirs(\"saftest\") failed",
                throughputMBs = null)
        }

        val fd = bridge.openFd(testRelPath, "rw")
        if (fd < 0) {
            return@withContext SafValidationResult(
                ok = false, message = "openFd failed (fd=$fd)",
                throughputMBs = null)
        }

        val pfd = ParcelFileDescriptor.adoptFd(fd)
        try {
            val ns = SafBridgeTest.nativeThroughput(pfd.fd, throughputBytes)
            if (ns < 0) {
                return@withContext SafValidationResult(
                    ok = false, message = "throughput write failed (errno=${-ns})",
                    throughputMBs = null)
            }
            val mbps = if (ns > 0) {
                (throughputBytes.toDouble() / (1024.0 * 1024.0)) / (ns / 1_000_000_000.0)
            } else null

            SafValidationResult(
                ok = true,
                message = "OK: 512 MiB sequential write",
                throughputMBs = mbps)
        } catch (e: Exception) {
            Log.e(TAG, "validation threw", e)
            SafValidationResult(
                ok = false, message = "exception: ${e.message}",
                throughputMBs = null)
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
