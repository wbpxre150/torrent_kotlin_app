package com.torrent.app.storage

import android.content.ContentResolver
import android.content.Context
import android.database.MatrixCursor
import android.net.Uri
import android.provider.DocumentsContract
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.any
import org.mockito.MockedStatic
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockStatic
import org.mockito.Mockito.`when`
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
class SafStorageGatewayRenameTest {

    private lateinit var treeUri: Uri
    private lateinit var mockedContract: MockedStatic<DocumentsContract>
    private lateinit var resolver: ContentResolver
    private lateinit var gateway: SafStorageGateway

    private val docUri = Uri.parse("content://x/doc")
    private val renamedUri = Uri.parse("content://x/renamed")
    private val renameCalls = AtomicInteger(0)
    private val deleteCalls = AtomicInteger(0)

    // Rows every children-query cursor returns. resolveUri walks the SAF tree by
    // matching COLUMN_DISPLAY_NAME against the requested leaf; we control which
    // documents "exist" by which (docId, name) rows we expose. Reconfigured per test.
    private var rows: List<Pair<String, String>> = listOf("d" to "dir")

    @Before fun setUp() {
        treeUri = Uri.parse("content://com.android.externalstorage.documents/tree/primary%3A")
        val ctx = mock(Context::class.java)
        resolver = mock(ContentResolver::class.java)
        `when`(ctx.contentResolver).thenReturn(resolver)
        renameCalls.set(0); deleteCalls.set(0)

        mockedContract = mockStatic(DocumentsContract::class.java)
        mockedContract.`when`<String> { DocumentsContract.getTreeDocumentId(any()) }
            .thenReturn("primary:")
        mockedContract.`when`<String> { DocumentsContract.getDocumentId(any()) }
            .thenReturn("docid")
        mockedContract.`when`<Uri> { DocumentsContract.buildDocumentUriUsingTree(any(), any()) }
            .thenReturn(docUri)
        mockedContract.`when`<Uri> { DocumentsContract.buildChildDocumentsUriUsingTree(any(), any()) }
            .thenReturn(Uri.parse("content://x/children"))
        // Count calls via thenAnswer so assertions don't depend on Mockito verify(times).
        mockedContract.`when`<Uri> { DocumentsContract.renameDocument(any(), any(), any()) }
            .thenAnswer { renameCalls.incrementAndGet(); renamedUri }
        mockedContract.`when`<Boolean> { DocumentsContract.deleteDocument(any(), any()) }
            .thenAnswer { deleteCalls.incrementAndGet(); true }

        // Every children query returns a fresh MatrixCursor over [rows]; fresh-per-call
        // matters because resolveUri is called several times and each must scan from 0.
        `when`<android.database.Cursor>(
            resolver.query(any<Uri>(), any<Array<String>>(), any(), any(), any())
        ).thenAnswer {
            MatrixCursor(arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME
            )).apply {
                rows.forEach { (docId, name) -> addRow(arrayOf<Any?>(docId, name)) }
            }
        }

        gateway = SafStorageGateway(ctx, treeUri)
    }

    @After fun tearDown() { mockedContract.close() }

    @Test fun rename_noTarget_renamesAndDoesNotDelete() {
        rows = listOf("d" to "dir", "s" to "file.mp4.part")   // no "file.mp4" row -> no target
        val ok = gateway.renameDoc("dir/file.mp4.part", "file.mp4")
        assertTrue(ok)
        assertEquals(1, renameCalls.get())
        assertEquals(0, deleteCalls.get())   // nothing to overwrite
    }

    @Test fun rename_targetExists_deletesTargetThenRenames() {
        rows = listOf(
            "d" to "dir",
            "s" to "file.mp4.part",
            "t" to "file.mp4",
        )
        val ok = gateway.renameDoc("dir/file.mp4.part", "file.mp4")
        assertTrue(ok)
        // POSIX overwrite: existing "file.mp4" is removed BEFORE the rename, so SAF
        // cannot append " (1)" and transmission does not get a phantom name.
        assertEquals(1, deleteCalls.get())
        assertEquals(1, renameCalls.get())
    }

    @Test fun rename_missingSource_returnsFalseAndDoesNothing() {
        rows = listOf("d" to "dir")   // no "file.mp4.part" row -> resolveUri(src) null
        val ok = gateway.renameDoc("dir/file.mp4.part", "file.mp4")
        assertFalse(ok)
        assertEquals(0, renameCalls.get())
        assertEquals(0, deleteCalls.get())
    }

    @Test fun rename_targetDeleteFails_abortsBeforeRename() {
        rows = listOf("d" to "dir", "s" to "file.mp4.part", "t" to "file.mp4")
        mockedContract.`when`<Boolean> { DocumentsContract.deleteDocument(any(), any()) }
            .thenThrow(RuntimeException("denied"))
        val ok = gateway.renameDoc("dir/file.mp4.part", "file.mp4")
        assertFalse(ok)
        assertEquals(0, renameCalls.get())   // never reached the rename
    }

    @Test fun rename_topLevel_targetPathKeyIsNewNameNotRoot() {
        // Regression for the cache-key bug: a top-level rename must key the cache on
        // `newName`, not on "" (the tree root).
        rows = listOf("s" to "old.mkv", "t" to "new.mkv")
        assertTrue(gateway.renameDoc("old.mkv", "new.mkv"))
        assertEquals(1, deleteCalls.get())
        assertEquals(1, renameCalls.get())
    }
}