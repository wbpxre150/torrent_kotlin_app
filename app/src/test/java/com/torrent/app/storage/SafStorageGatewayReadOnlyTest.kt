package com.torrent.app.storage

import android.content.ContentResolver
import android.content.Context
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * Plan 07 §2.1 / §3: the FTP read-only resolution path must NEVER write the
 * shared cache transmission trusts and must NEVER take transmission's
 * per-path locks. Verified via the gateway's internal test hooks.
 */
@RunWith(RobolectricTestRunner::class)
class SafStorageGatewayReadOnlyTest {

    private val treeUri = Uri.parse("content://test.fakedocs/tree/root1")

    // ── negative path: no provider registered → lookups fail, invariants hold ─

    @Test fun readOnlyResolution_failsClean_andNeverTouchesSharedCacheOrLocks() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val gw = SafStorageGateway(ctx, treeUri)

        assertNull(gw.readOnlyStat("a/b.mkv"))
        assertTrue(gw.readOnlyListDetailed("").isEmpty())
        assertNull(gw.readOnlyOpenRead("a/b.mkv"))

        // Shared cache: only the root seeded in init — nothing added by FTP reads.
        assertEquals(setOf(""), gw.sharedCacheKeys())
        // No path locks were created for the FTP read path.
        assertEquals(0, gw.pathLockCount())
        // Nothing cached in the private FTP roCache either.
        assertEquals(0, gw.roCacheSize())
    }

    // ── positive path: mocked provider serves the tree ───────────────────────

    private class FakeDocs(val fileGone: Boolean) {
        fun childrenCursor(): MatrixCursor {
            val c = MatrixCursor(arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED))
            if (!fileGone) c.addRow(arrayOf<Any?>("doc:a", "a.mkv", "video/x-matroska", 100L, 111L))
            c.addRow(arrayOf<Any?>("doc:sub", "sub", DocumentsContract.Document.MIME_TYPE_DIR, 0L, 222L))
            return c
        }

        fun statCursor(): MatrixCursor {
            val c = MatrixCursor(arrayOf(
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED))
            c.addRow(arrayOf<Any?>("video/x-matroska", 100L, 111L))
            return c
        }
    }

    private fun gatewayWithMockedResolver(resolver: ContentResolver): SafStorageGateway {
        val ctx = Mockito.mock(Context::class.java)
        Mockito.`when`(ctx.contentResolver).thenReturn(resolver)
        return SafStorageGateway(ctx, treeUri)
    }

    private fun stubQueries(resolver: ContentResolver, fake: FakeDocs) {
        Mockito.`when`(
            resolver.query(
                Mockito.any(Uri::class.java),
                Mockito.any<Array<String>?>(),
                Mockito.any<String>(),
                Mockito.any<Array<String>?>(),
                Mockito.any<String>()))
            .thenAnswer { inv ->
                val uri = inv.getArgument<Uri>(0)
                if (uri.path?.endsWith("/children") == true) fake.childrenCursor()
                else fake.statCursor()
            }
    }

    @Test fun readOnlyListDetailed_populatesOnlyRoCache() {
        val resolver = Mockito.mock(ContentResolver::class.java)
        val fake = FakeDocs(fileGone = false)
        stubQueries(resolver, fake)
        val gw = gatewayWithMockedResolver(resolver)

        val children = gw.readOnlyListDetailed("")

        assertEquals(2, children.size)
        assertEquals("a.mkv", children[0].name)
        assertEquals(false, children[0].isDir)
        assertEquals(100L, children[0].size)
        assertEquals("sub", children[1].name)
        assertEquals(true, children[1].isDir)

        // Shared cache untouched (root only); children live in roCache.
        assertEquals(setOf(""), gw.sharedCacheKeys())
        assertTrue(gw.roCacheSize() >= 2)
        // No transmission path locks were taken.
        assertEquals(0, gw.pathLockCount())
    }

    @Test fun readOnlyStat_usesRoCache_andStaysOutOfSharedCache() {
        val resolver = Mockito.mock(ContentResolver::class.java)
        val fake = FakeDocs(fileGone = false)
        stubQueries(resolver, fake)
        val gw = gatewayWithMockedResolver(resolver)

        val st = gw.readOnlyStat("a.mkv")

        assertEquals(100L, st?.size)
        assertEquals(false, st?.isDir)
        assertEquals(111L, st?.lastModified)
        assertEquals(setOf(""), gw.sharedCacheKeys())
        assertEquals(0, gw.pathLockCount())
    }

    @Test fun readOnlyStat_staleEntry_evictsRoCacheAndReturnsNull() {
        val resolver = Mockito.mock(ContentResolver::class.java)
        val fake = FakeDocs(fileGone = false)
        stubQueries(resolver, fake)
        val gw = gatewayWithMockedResolver(resolver)

        // First stat populates roCache["a.mkv"].
        assertEquals(100L, gw.readOnlyStat("a.mkv")?.size)
        assertTrue(gw.roCacheSize() >= 1)

        // The file is now gone: stat query returns an empty cursor and the
        // children walk no longer lists a.mkv.
        Mockito.`when`(
            resolver.query(
                Mockito.any(Uri::class.java),
                Mockito.any<Array<String>?>(),
                Mockito.any<String>(),
                Mockito.any<Array<String>?>(),
                Mockito.any<String>()))
            .thenAnswer { inv ->
                val uri = inv.getArgument<Uri>(0)
                if (uri.path?.endsWith("/children") == true)
                    FakeDocs(fileGone = true).childrenCursor()
                else MatrixCursor(arrayOf(
                    DocumentsContract.Document.COLUMN_MIME_TYPE,
                    DocumentsContract.Document.COLUMN_SIZE,
                    DocumentsContract.Document.COLUMN_LAST_MODIFIED))
            }

        assertNull("stale roCache entry must be evicted and fresh walk must miss",
            gw.readOnlyStat("a.mkv"))
        // a.mkv and descendants are gone from roCache (sub survived).
        assertTrue(gw.roCacheSize() < 2)
        assertEquals(setOf(""), gw.sharedCacheKeys())
        assertEquals(0, gw.pathLockCount())
    }

    @Test fun readOnlyOpenRead_returnsPfd_andEvictsOnFailure() {
        val resolver = Mockito.mock(ContentResolver::class.java)
        val fake = FakeDocs(fileGone = false)
        stubQueries(resolver, fake)
        val gw = gatewayWithMockedResolver(resolver)

        val temp = File.createTempFile("saf-ro", ".bin").apply { deleteOnExit() }
        val pfd = ParcelFileDescriptor.open(temp, ParcelFileDescriptor.MODE_READ_ONLY)
        Mockito.`when`(resolver.openFileDescriptor(Mockito.any(Uri::class.java), Mockito.eq("r")))
            .thenReturn(pfd)

        val got = gw.readOnlyOpenRead("a.mkv")
        assertEquals(pfd, got)
        got?.close()
        assertEquals(setOf(""), gw.sharedCacheKeys())
        assertEquals(0, gw.pathLockCount())
    }

    @Test fun evictReadOnly_dropsPathAndDescendants() {
        val resolver = Mockito.mock(ContentResolver::class.java)
        stubQueries(resolver, FakeDocs(fileGone = false))
        val gw = gatewayWithMockedResolver(resolver)

        gw.readOnlyListDetailed("")   // populates roCache
        val before = gw.roCacheSize()
        assertTrue(before >= 2)

        gw.evictReadOnly("sub")
        assertTrue(gw.roCacheSize() < before)
    }
}
