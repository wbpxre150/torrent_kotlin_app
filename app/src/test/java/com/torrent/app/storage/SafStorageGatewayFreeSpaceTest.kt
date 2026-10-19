package com.torrent.app.storage

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.system.Os
import android.system.StructStatVfs
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.eq
import org.mockito.MockedStatic
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockStatic
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.robolectric.RobolectricTestRunner
import java.io.FileDescriptor
import java.util.concurrent.atomic.AtomicInteger

/**
 * Free-space probe behaviour.
 *
 * NOTE on technique: Mockito's static mocks are thread-local, so [Os] and
 * [DocumentsContract] stubs are only effective on the thread that created them.
 * The gateway probes free space on its own `saf-freespace` executor, where those
 * stubs are inert (the intercepted static call yields null), and Robolectric has
 * no shadow for `Os.fstatvfs`, so the real native call is unavailable too. The
 * probe-specific tests therefore drive the private probe directly on the test
 * thread; the TTL/caching tests go through [SafStorageGateway.availableBytes]
 * and observe the (thread-safe) [ContentResolver] mock instead.
 */
@RunWith(RobolectricTestRunner::class)
class SafStorageGatewayFreeSpaceTest {

    private lateinit var treeUri: Uri
    private lateinit var mockedContract: MockedStatic<DocumentsContract>
    private lateinit var resolver: ContentResolver
    private lateinit var gateway: SafStorageGateway

    // Tree-root document URI: what the gateway seeds its cache[""] with (see setUp stub).
    private val rootDocUri: Uri = Uri.parse("content://x/doc")

    @Before
    fun setUp() {
        treeUri = Uri.parse("content://com.android.externalstorage.documents/tree/primary%3A")
        val ctx = mock(Context::class.java)
        resolver = mock(ContentResolver::class.java)
        `when`(ctx.contentResolver).thenReturn(resolver)

        mockedContract = mockStatic(DocumentsContract::class.java)
        mockedContract.`when`<String> {
            DocumentsContract.getTreeDocumentId(any())
        }.thenReturn("primary:")
        mockedContract.`when`<String> {
            DocumentsContract.getDocumentId(any())
        }.thenReturn("docid")
        mockedContract.`when`<Uri> {
            DocumentsContract.buildDocumentUriUsingTree(any(), any())
        }.thenReturn(rootDocUri)
        mockedContract.`when`<Uri> {
            DocumentsContract.buildChildDocumentsUriUsingTree(any(), any())
        }.thenReturn(Uri.parse("content://x/children"))

        gateway = SafStorageGateway(ctx, treeUri)
    }

    @After
    fun tearDown() {
        gateway.shutdown()
        mockedContract.close()
    }

    /** A ParcelFileDescriptor mock whose [ParcelFileDescriptor.getFileDescriptor] is a real fd. */
    private fun mockPfd(): ParcelFileDescriptor {
        val pfd = mock(ParcelFileDescriptor::class.java)
        `when`(pfd.fileDescriptor).thenReturn(FileDescriptor())
        return pfd
    }

    // android.system.StructStatVfs(f_bsize, f_frsize, f_blocks, f_bfree, f_bavail,
    //                               f_files, f_ffree, f_favail, f_fsid, f_flag, f_namemax)
    private fun statvfs(frsize: Long, bavail: Long): StructStatVfs =
        StructStatVfs(frsize, frsize, 0L, 0L, bavail, 0L, 0L, 0L, 0L, 0L, 0L)

    /** Invoke the private probe on THIS thread so per-thread static stubs apply. */
    private fun probeFreeSpace(): Long {
        val m = SafStorageGateway::class.java.getDeclaredMethod("probeFreeSpaceViaFd")
        m.isAccessible = true
        return m.invoke(gateway) as Long
    }

    @Test
    fun coldCache_returnsMaxValueImmediately() {
        mockedContract.`when`<Uri> {
            DocumentsContract.createDocument(any(), any(), any(), any())
        }.thenReturn(null)

        val result = gateway.availableBytes()
        assertEquals(Long.MAX_VALUE, result)
    }

    @Test
    fun rapidCalls_triggerSingleBackgroundRefresh() {
        val createCalls = AtomicInteger(0)
        mockedContract.`when`<Uri> {
            DocumentsContract.createDocument(any(), any(), any(), any())
        }.thenAnswer { createCalls.incrementAndGet(); null }

        gateway.availableBytes()
        Thread.sleep(500)
        val callsAfterFirst = createCalls.get()

        repeat(5) { gateway.availableBytes() }
        Thread.sleep(500)
        assertEquals("no extra probe within TTL", callsAfterFirst, createCalls.get())
    }

    /**
     * A FAILED probe must be negatively cached: repeated availableBytes() calls
     * inside the TTL must not re-probe. Observed through the ContentResolver mock,
     * which (unlike static mocks) is effective on every thread: one primary
     * root-fd open per actual probe attempt.
     */
    @Test
    fun failedProbe_isNotRetriedWithinTtl() {
        // Root open yields null -> probe falls through to the (also failing) fallback.
        `when`(resolver.openFileDescriptor(rootDocUri, "r")).thenReturn(null)

        assertEquals(Long.MAX_VALUE, gateway.availableBytes())
        Thread.sleep(500)
        verify(resolver, times(1)).openFileDescriptor(rootDocUri, "r")

        repeat(5) { gateway.availableBytes() }
        Thread.sleep(500)
        assertEquals(Long.MAX_VALUE, gateway.availableBytes())
        verify(resolver, times(1)).openFileDescriptor(rootDocUri, "r")
    }

    @Test
    fun rootOpenSuccess_usesRootFdAndNeverCreatesProbe() {
        val rootPfd = mockPfd()
        `when`(resolver.openFileDescriptor(rootDocUri, "r")).thenReturn(rootPfd)
        mockedContract.`when`<Uri> {
            DocumentsContract.createDocument(any(), any(), any(), any())
        }.thenReturn(null)

        val osStatic = mockStatic(Os::class.java)
        try {
            osStatic.`when`<StructStatVfs> { Os.fstatvfs(any()) }.thenReturn(statvfs(4096L, 1_000L))

            assertEquals(4096L * 1_000L, probeFreeSpace())

            // Primary path only: one read-only root open, no probe document created.
            verify(resolver, times(1)).openFileDescriptor(rootDocUri, "r")
            mockedContract.verify(
                { DocumentsContract.createDocument(any(), any(), any(), any()) }, never())
            verify(rootPfd, times(1)).close()
        } finally {
            osStatic.close()
        }
    }

    @Test
    fun rootOpenFailure_fallsBackToProbeDocument() {
        val probeUri = Uri.parse("content://x/probe")
        `when`(resolver.openFileDescriptor(rootDocUri, "r"))
            .thenThrow(SecurityException("root fd denied"))
        val probePfd = mockPfd()
        `when`(resolver.openFileDescriptor(probeUri, "r")).thenReturn(probePfd)

        mockedContract.`when`<Uri> {
            DocumentsContract.createDocument(any(), any(), any(), any())
        }.thenReturn(probeUri)
        mockedContract.`when`<Boolean> {
            DocumentsContract.deleteDocument(any(), any())
        }.thenReturn(true)

        val osStatic = mockStatic(Os::class.java)
        try {
            osStatic.`when`<StructStatVfs> { Os.fstatvfs(any()) }.thenReturn(statvfs(512L, 2_000L))

            assertEquals(512L * 2_000L, probeFreeSpace())

            // Exactly one probe document per probe, and it is cleaned up again.
            mockedContract.verify(
                { DocumentsContract.createDocument(any(), any(), any(), any()) }, times(1))
            mockedContract.verify(
                { DocumentsContract.deleteDocument(eq(resolver), eq(probeUri)) }, times(1))
            verify(resolver, times(1)).openFileDescriptor(probeUri, "r")
            verify(probePfd, times(1)).close()
        } finally {
            osStatic.close()
        }
    }
}
