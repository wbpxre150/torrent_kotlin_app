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
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger

// No manifest is visible to unit tests, so Robolectric would fall back to its
// default (SDK 21), which lacks DocumentsContract.moveDocument (API 26). The app
// minSdk is 26, so pin this class to SDK 35.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SafStorageGatewayRenameTest {

    private lateinit var treeUri: Uri
    private lateinit var mockedContract: MockedStatic<DocumentsContract>
    private lateinit var resolver: ContentResolver
    private lateinit var gateway: SafStorageGateway

    private val renamedUri = Uri.parse("content://x/renamed")
    private val renameCalls = AtomicInteger(0)
    private val deleteCalls = AtomicInteger(0)

    // Fake SAF tree. resolveUri walks by matching COLUMN_DISPLAY_NAME against the
    // requested leaf; we control which documents "exist" under which parent via
    // [names] (docId -> display name) and [childrenOf] (parent docId -> child
    // docIds, "root" = the tree root). Reconfigured per test.
    private var names: Map<String, String> = mapOf("d" to "dir")
    private var childrenOf: Map<String, List<String>> = mapOf("root" to emptyList())

    @Before fun setUp() {
        treeUri = Uri.parse("content://com.android.externalstorage.documents/tree/primary%3A")
        val ctx = mock(Context::class.java)
        resolver = mock(ContentResolver::class.java)
        `when`(ctx.contentResolver).thenReturn(resolver)
        renameCalls.set(0); deleteCalls.set(0)

        mockedContract = mockStatic(DocumentsContract::class.java)
        mockedContract.`when`<String> { DocumentsContract.getTreeDocumentId(any()) }
            .thenReturn("root")
        // Distinct uri per docId so parent lookups can be told apart.
        mockedContract.`when`<Uri> { DocumentsContract.buildDocumentUriUsingTree(any(), any()) }
            .thenAnswer { inv -> Uri.parse("content://x/doc/${inv.arguments[1]}") }
        mockedContract.`when`<String> { DocumentsContract.getDocumentId(any()) }
            .thenAnswer { inv -> (inv.arguments[0] as Uri).lastPathSegment ?: "root" }
        mockedContract.`when`<Uri> { DocumentsContract.buildChildDocumentsUriUsingTree(any(), any()) }
            .thenAnswer { inv -> Uri.parse("content://x/children/${inv.arguments[1]}") }
        // Count calls via thenAnswer so assertions don't depend on Mockito verify(times).
        mockedContract.`when`<Uri> { DocumentsContract.renameDocument(any(), any(), any()) }
            .thenAnswer { renameCalls.incrementAndGet(); renamedUri }
        mockedContract.`when`<Boolean> { DocumentsContract.deleteDocument(any(), any()) }
            .thenAnswer { deleteCalls.incrementAndGet(); true }

        // Every children query returns a fresh MatrixCursor over the rows for the
        // parent encoded in the children uri. Fresh-per-call matters because
        // resolveUri is called several times and each must scan from 0.
        `when`<android.database.Cursor>(
            resolver.query(any<Uri>(), any<Array<String>>(), any(), any(), any())
        ).thenAnswer { inv ->
            val parentDocId = (inv.arguments[0] as Uri).lastPathSegment ?: "root"
            MatrixCursor(arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME
            )).apply {
                (childrenOf[parentDocId] ?: emptyList()).forEach { id ->
                    addRow(arrayOf<Any?>(id, names[id]))
                }
            }
        }

        gateway = SafStorageGateway(ctx, treeUri)
    }

    @After fun tearDown() { mockedContract.close() }

    @Test fun rename_noTarget_renamesAndDoesNotDelete() {
        names = mapOf("d" to "dir", "s" to "file.mp4.part")   // no "file.mp4" -> no target
        childrenOf = mapOf("root" to listOf("d"), "d" to listOf("s"))
        val ok = gateway.renameDoc("dir/file.mp4.part", "file.mp4")
        assertTrue(ok)
        assertEquals(1, renameCalls.get())
        assertEquals(0, deleteCalls.get())   // nothing to overwrite
    }

    @Test fun rename_targetExists_deletesTargetThenRenames() {
        names = mapOf("d" to "dir", "s" to "file.mp4.part", "t" to "file.mp4")
        childrenOf = mapOf("root" to listOf("d"), "d" to listOf("s", "t"))
        val ok = gateway.renameDoc("dir/file.mp4.part", "file.mp4")
        assertTrue(ok)
        // POSIX overwrite: existing "file.mp4" is removed BEFORE the rename, so SAF
        // cannot append " (1)" and transmission does not get a phantom name.
        assertEquals(1, deleteCalls.get())
        assertEquals(1, renameCalls.get())
    }

    @Test fun rename_missingSource_returnsFalseAndDoesNothing() {
        names = mapOf("d" to "dir")   // no "file.mp4.part" -> resolveUri(src) null
        childrenOf = mapOf("root" to listOf("d"), "d" to emptyList())
        val ok = gateway.renameDoc("dir/file.mp4.part", "file.mp4")
        assertFalse(ok)
        assertEquals(0, renameCalls.get())
        assertEquals(0, deleteCalls.get())
    }

    @Test fun rename_targetDeleteFails_abortsBeforeRename() {
        names = mapOf("d" to "dir", "s" to "file.mp4.part", "t" to "file.mp4")
        childrenOf = mapOf("root" to listOf("d"), "d" to listOf("s", "t"))
        mockedContract.`when`<Boolean> { DocumentsContract.deleteDocument(any(), any()) }
            .thenThrow(RuntimeException("denied"))
        val ok = gateway.renameDoc("dir/file.mp4.part", "file.mp4")
        assertFalse(ok)
        assertEquals(0, renameCalls.get())   // never reached the rename
    }

    @Test fun rename_topLevel_targetPathKeyIsNewNameNotRoot() {
        // Regression for the cache-key bug: a top-level rename must key the cache on
        // `newName`, not on "" (the tree root).
        names = mapOf("s" to "old.mkv", "t" to "new.mkv")
        childrenOf = mapOf("root" to listOf("s", "t"))
        assertTrue(gateway.renameDoc("old.mkv", "new.mkv"))
        assertEquals(1, deleteCalls.get())
        assertEquals(1, renameCalls.get())
    }

    @Test fun rename_samePath_isNoOpAndNeverDeletes() {
        names = mapOf("d" to "dir", "s" to "file.mp4")
        childrenOf = mapOf("root" to listOf("d"), "d" to listOf("s"))
        val ok = gateway.renameDoc("dir/file.mp4", "dir/file.mp4")
        assertTrue(ok)
        assertEquals(0, deleteCalls.get())   // NEVER delete on a no-op
        assertEquals(0, renameCalls.get())
    }

    @Test fun rename_samePathBasenameForm_isNoOpAndNeverDeletes() {
        // Data-loss regression: the old code expanded a basename dst to the SOURCE
        // path, deleted the source as "existing target", then failed the rename.
        // A basename dst that lands on the same path must be a no-op.
        names = mapOf("d" to "dir", "s" to "file.mp4")
        childrenOf = mapOf("root" to listOf("d"), "d" to listOf("s"))
        val ok = gateway.renameDoc("dir/file.mp4", "file.mp4")
        assertTrue(ok)
        assertEquals(0, deleteCalls.get())
        assertEquals(0, renameCalls.get())
    }

    @Test fun rename_crossParent_sameLeaf_usesMoveDocument() {
        // otherdir is EMPTY: no "file.mp4" under it, so no target to delete.
        names = mapOf("d" to "dir", "e" to "otherdir", "s" to "file.mp4")
        childrenOf = mapOf("root" to listOf("d", "e"), "d" to listOf("s"), "e" to emptyList())
        mockedContract.`when`<Uri> { DocumentsContract.moveDocument(any(), any(), any(), any()) }
            .thenReturn(Uri.parse("content://x/moved"))
        val ok = gateway.renameDoc("dir/file.mp4", "otherdir/file.mp4")
        assertTrue(ok)
        assertEquals(0, deleteCalls.get())   // no target at dst
        assertEquals(0, renameCalls.get())   // same leaf name: move alone suffices
    }

    @Test fun rename_crossParent_differentLeaf_movesThenRenames() {
        names = mapOf("d" to "dir", "e" to "otherdir", "s" to "file.mp4.part")
        childrenOf = mapOf("root" to listOf("d", "e"), "d" to listOf("s"), "e" to emptyList())
        mockedContract.`when`<Uri> { DocumentsContract.moveDocument(any(), any(), any(), any()) }
            .thenReturn(Uri.parse("content://x/moved"))
        val ok = gateway.renameDoc("dir/file.mp4.part", "otherdir/file.mp4")
        assertTrue(ok)
        assertEquals(0, deleteCalls.get())
        assertEquals(1, renameCalls.get())    // leaf renamed after the move
    }

    @Test fun rename_crossParent_moveUnsupported_failsWithoutDeletingSource() {
        names = mapOf("d" to "dir", "e" to "otherdir", "s" to "file.mp4")
        childrenOf = mapOf("root" to listOf("d", "e"), "d" to listOf("s"), "e" to emptyList())
        // moveDocument returns null: provider without FLAG_SUPPORTS_MOVE.
        mockedContract.`when`<Uri> { DocumentsContract.moveDocument(any(), any(), any(), any()) }
            .thenReturn(null)
        val ok = gateway.renameDoc("dir/file.mp4", "otherdir/file.mp4")
        assertFalse(ok)
        assertEquals(0, deleteCalls.get())   // source must survive for the copy fallback
        assertEquals(0, renameCalls.get())
    }

    @Test fun rename_crossParent_existingTargetDeletedBeforeMove() {
        names = mapOf("d" to "dir", "e" to "otherdir", "s" to "file.mp4", "t" to "file.mp4")
        childrenOf = mapOf("root" to listOf("d", "e"), "d" to listOf("s"), "e" to listOf("t"))
        mockedContract.`when`<Uri> { DocumentsContract.moveDocument(any(), any(), any(), any()) }
            .thenReturn(Uri.parse("content://x/moved"))
        val ok = gateway.renameDoc("dir/file.mp4", "otherdir/file.mp4")
        assertTrue(ok)
        assertEquals(1, deleteCalls.get())   // POSIX replace: target removed first
        assertEquals(0, renameCalls.get())
    }
}
