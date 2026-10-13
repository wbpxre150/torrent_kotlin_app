package com.torrent.app.backup

import com.torrent.app.config.BackupSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.GZIPOutputStream

/**
 * Host-JVM tests (plain JUnit4 + TemporaryFolder, no Robolectric) for
 * [Bep51BootstrapDownloader]: a fake [HttpURLConnection] is injected via the
 * opener seam (the AGP unit-test classpath has no `com.sun.net.httpserver`,
 * but `java.net.*` works — see S3ClientTest), and the valid cache payloads
 * are built with the shared [buildBep51Cache] helper.
 */
class Bep51BootstrapDownloaderTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val openerCalls = AtomicInteger()

    /** Scripted [HttpURLConnection]: fixed response code + body, optional connect failure. */
    private class FakeHttpConnection(
        private val fakeUrl: URL,
        private val code: Int,
        private val body: ByteArray,
        private val failConnect: Boolean = false,
    ) : HttpURLConnection(fakeUrl) {
        override fun connect() {
            if (failConnect) throw IOException("connection refused (test)")
        }

        override fun getResponseCode(): Int {
            if (failConnect) throw IOException("connection refused (test)")
            return code
        }

        override fun getInputStream(): InputStream {
            if (failConnect) throw IOException("connection refused (test)")
            return if (code in 200..299) ByteArrayInputStream(body)
            else throw IOException("unexpected response code $code")
        }

        override fun usingProxy(): Boolean = false

        override fun disconnect() { /* nothing to release in the fake */ }
    }

    private fun downloaderFor(
        code: Int = 200,
        body: ByteArray = ByteArray(0),
        failConnect: Boolean = false,
        maxBytes: Long = 16L * 1024 * 1024,
    ): Bep51BootstrapDownloader = Bep51BootstrapDownloader(
        opener = { url ->
            openerCalls.incrementAndGet()
            FakeHttpConnection(url, code, body, failConnect)
        },
        maxBytes = maxBytes,
    )

    private fun dest(): File = File(tmp.root, "data/bep51_cache.dat")

    private fun tempDir(): File = File(tmp.root, "data")

    private fun gzip(bytes: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(bytes) }
        return out.toByteArray()
    }

    private fun assertNoTempFilesLeft() {
        val leftovers = tempDir().listFiles()?.filter {
            it.isFile && it.name != "bep51_cache.dat"
        } ?: emptyList()
        assertTrue("leftover temp files: $leftovers", leftovers.isEmpty())
    }

    @Test fun validGzipPayload_placed() {
        val cache = buildBep51Cache(nodeCount = 4, withTrailer = true)
        val result = downloaderFor(code = 200, body = gzip(cache))
            .bootstrap("https://example.com/cache.gz", dest(), tempDir())
        assertEquals(BootstrapResult.Placed(4, cache.size.toLong()), result)
        assertTrue(dest().exists())
        assertEquals(cache.toList(), dest().readBytes().toList())
        assertNoTempFilesLeft()
    }

    @Test fun validRawPayload_placed() {
        val cache = buildBep51Cache(nodeCount = 2)
        val result = downloaderFor(code = 200, body = cache)
            .bootstrap("https://example.com/cache.dat", dest(), tempDir())
        assertEquals(BootstrapResult.Placed(2, cache.size.toLong()), result)
        assertTrue(dest().exists())
        assertEquals(cache.toList(), dest().readBytes().toList())
        assertNoTempFilesLeft()
    }

    @Test fun corruptChecksumPayload_failedNothingOnDisk() {
        val bad = buildBep51Cache(nodeCount = 2, withTrailer = true, corruptTrailer = true)
        val result = downloaderFor(code = 200, body = bad)
            .bootstrap("https://example.com/cache.dat", dest(), tempDir())
        assertTrue(result is BootstrapResult.Failed)
        assertTrue((result as BootstrapResult.Failed).reason.contains("invalid"))
        assertTrue(!dest().exists())
        assertNoTempFilesLeft()
    }

    @Test fun garbageBadMagic_failedNothingOnDisk() {
        val bad = buildBep51Cache(nodeCount = 1, magic = "XEP1")
        val result = downloaderFor(code = 200, body = bad)
            .bootstrap("https://example.com/cache.dat", dest(), tempDir())
        assertTrue(result is BootstrapResult.Failed)
        assertTrue((result as BootstrapResult.Failed).reason.contains("invalid"))
        assertTrue(!dest().exists())
        assertNoTempFilesLeft()
    }

    @Test fun emptyCacheZeroNodes_failed() {
        val empty = buildBep51Cache(nodeCount = 0)
        val result = downloaderFor(code = 200, body = empty)
            .bootstrap("https://example.com/cache.dat", dest(), tempDir())
        assertTrue(result is BootstrapResult.Failed)
        assertTrue((result as BootstrapResult.Failed).reason.contains("empty"))
        assertTrue(!dest().exists())
    }

    @Test fun http404_failed() {
        val result = downloaderFor(code = 404, body = "not found".encodeToByteArray())
            .bootstrap("https://example.com/missing", dest(), tempDir())
        assertTrue(result is BootstrapResult.Failed)
        assertTrue((result as BootstrapResult.Failed).reason.contains("404"))
        assertTrue(!dest().exists())
    }

    @Test fun ioException_failed() {
        val result = downloaderFor(failConnect = true)
            .bootstrap("https://example.com/cache.dat", dest(), tempDir())
        assertTrue(result is BootstrapResult.Failed)
        assertTrue(!dest().exists())
    }

    @Test fun destAlreadyExists_skippedAndOpenerNeverCalled() {
        dest().parentFile?.mkdirs()
        dest().writeBytes(ByteArray(16))
        val result = downloaderFor(code = 200, body = buildBep51Cache(nodeCount = 1))
            .bootstrap("https://example.com/cache.dat", dest(), tempDir())
        assertEquals(BootstrapResult.SkippedExists, result)
        assertEquals(0, openerCalls.get())
        assertEquals(16, dest().readBytes().size) // untouched
    }

    @Test fun httpUrl_failedAndOpenerNeverCalled() {
        val result = downloaderFor(code = 200, body = buildBep51Cache(nodeCount = 1))
            .bootstrap("http://example.com/cache.dat", dest(), tempDir())
        assertTrue(result is BootstrapResult.Failed)
        assertEquals(0, openerCalls.get())
        assertTrue(!dest().exists())
    }

    @Test fun oversizePayload_failed() {
        // 64-byte cap; the 200-byte body exceeds it before validation.
        val result = downloaderFor(code = 200, body = ByteArray(200), maxBytes = 64)
            .bootstrap("https://example.com/cache.dat", dest(), tempDir())
        assertTrue(result is BootstrapResult.Failed)
        assertTrue((result as BootstrapResult.Failed).reason.contains("cap"))
        assertTrue(!dest().exists())
        assertNoTempFilesLeft()
    }

    // ── shouldBootstrap gate (pure) ─────────────────────────────────────────

    private val goodSettings = BackupSettings(
        bootstrapEnabled = true,
        bootstrapUrl = "https://example.com/bep51_cache.dat.gz",
    )

    private val now = 1_000_000_000L

    @Test fun shouldBootstrap_allGood_true() {
        assertTrue(Bep51BootstrapDownloader.shouldBootstrap(
            goodSettings, cacheExists = false, migrationPending = false,
            lastFailureAt = null, now = now))
    }

    @Test fun shouldBootstrap_disabled_false() {
        assertFalse(Bep51BootstrapDownloader.shouldBootstrap(
            goodSettings.copy(bootstrapEnabled = false), false, false, null, now))
    }

    @Test fun shouldBootstrap_blankUrl_false() {
        assertFalse(Bep51BootstrapDownloader.shouldBootstrap(
            goodSettings.copy(bootstrapUrl = "  "), false, false, null, now))
    }

    @Test fun shouldBootstrap_httpUrl_false() {
        assertFalse(Bep51BootstrapDownloader.shouldBootstrap(
            goodSettings.copy(bootstrapUrl = "http://example.com/cache"), false, false, null, now))
    }

    @Test fun shouldBootstrap_cacheExists_false() {
        assertFalse(Bep51BootstrapDownloader.shouldBootstrap(
            goodSettings, cacheExists = true, migrationPending = false, lastFailureAt = null,
            now = now))
    }

    @Test fun shouldBootstrap_migrationPending_false() {
        assertFalse(Bep51BootstrapDownloader.shouldBootstrap(
            goodSettings, cacheExists = false, migrationPending = true, lastFailureAt = null,
            now = now))
    }

    @Test fun shouldBootstrap_recentFailure_rateLimited() {
        // 30 minutes after the last failure: inside the 1-hour window.
        assertFalse(Bep51BootstrapDownloader.shouldBootstrap(
            goodSettings, false, false, lastFailureAt = now - 30L * 60 * 1000, now = now))
    }

    @Test fun shouldBootstrap_oldFailure_retryAllowed() {
        // 2 hours after the last failure: outside the 1-hour window.
        assertTrue(Bep51BootstrapDownloader.shouldBootstrap(
            goodSettings, false, false, lastFailureAt = now - 2L * 60 * 60 * 1000, now = now))
    }

}
