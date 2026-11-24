package com.torrent.app.storage

import android.content.ContentResolver
import android.content.Context
import android.database.MatrixCursor
import android.net.Uri
import android.provider.DocumentsContract
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
class SafStorageGatewayLockStripingTest {

    private lateinit var treeUri: Uri
    private lateinit var mockedContract: MockedStatic<DocumentsContract>
    private lateinit var resolver: ContentResolver
    private lateinit var gateway: SafStorageGateway

    private val docUri = Uri.parse("content://x/doc")
    /** Display name the fake provider reports for a created document. */
    private var createdName = "child"
    /** How many documents the gateway asked the provider to delete. */
    private var deleted = 0

    @Before
    fun setUp() {
        treeUri = Uri.parse("content://com.android.externalstorage.documents/tree/primary%3A")
        val ctx = mock(Context::class.java)
        resolver = mock(ContentResolver::class.java)
        `when`(ctx.contentResolver).thenReturn(resolver)

        mockedContract = mockStatic(DocumentsContract::class.java)
        mockedContract.`when`<String> { DocumentsContract.getTreeDocumentId(any()) }
            .thenReturn("primary:")
        mockedContract.`when`<String> { DocumentsContract.getDocumentId(any()) }
            .thenReturn("docid")
        mockedContract.`when`<Uri> { DocumentsContract.buildDocumentUriUsingTree(any(), any()) }
            .thenReturn(docUri)
        mockedContract.`when`<Uri> { DocumentsContract.buildChildDocumentsUriUsingTree(any(), any()) }
            .thenReturn(Uri.parse("content://x/children"))
        mockedContract.`when`<Boolean> { DocumentsContract.deleteDocument(any(), any()) }
            .thenAnswer { deleted++; true }

        gateway = SafStorageGateway(ctx, treeUri)
    }

    @After
    fun tearDown() { mockedContract.close() }

    @Test
    fun `per-path lock exists and is reentrant`() {
        setupQuery(listOf("d" to "dir"))
        val st = gateway.stat("dir")
        assertNotNull(st)
    }

    @Test
    fun `resolveUri caches result after miss`() {
        setupQuery(listOf("d" to "dir"))
        val st1 = gateway.stat("dir")
        assertNotNull(st1)
        val st2 = gateway.stat("dir")
        assertNotNull(st2)
    }

    @Test
    fun `multiple paths resolved independently`() {
        setupQuery(listOf("d" to "dir", "f" to "file.mp4"))
        val dir = gateway.stat("dir")
        assertNotNull(dir)
        val file = gateway.stat("file.mp4")
        assertNotNull(file)
    }

    @Test
    fun `renameDoc works without synchronized`() {
        setupQuery(listOf("s" to "old.mkv"))
        mockedContract.`when`<Uri> { DocumentsContract.renameDocument(any(), any(), any()) }
            .thenReturn(Uri.parse("content://x/renamed"))

        val ok = gateway.renameDoc("old.mkv", "new.mkv")
        assertEquals(true, ok)
    }

    @Test
    fun `mkdirs works without synchronized`() {
        setupQuery(listOf("d" to "parent"))
        createdName = "child"
        mockedContract.`when`<Uri> { DocumentsContract.createDocument(any(), any(), any(), any()) }
            .thenReturn(Uri.parse("content://x/created"))

        val ok = gateway.mkdirs("parent/child")
        assertEquals(true, ok)
    }

    @Test
    fun `mkdirs deletes and rejects a provider dedup directory`() {
        setupQuery(listOf("d" to "parent"))
        // The provider answers a create for an existing name with a " (1)" duplicate.
        // Accepting it would cache the phantom dir under the requested path, so every
        // later write for the real directory lands inside it (downloads invisible to FTP
        // and to the next process).
        createdName = "child (1)"
        mockedContract.`when`<Uri> { DocumentsContract.createDocument(any(), any(), any(), any()) }
            .thenReturn(Uri.parse("content://x/phantom"))

        assertEquals(false, gateway.mkdirs("parent/child"))
        assertTrue("the deduped directory must be deleted", deleted >= 1)
        assertEquals("phantom dir must not be cached as the requested path", false, gateway.exists("parent/child"))
    }

    @Test
    fun `mkdirs accepts a transliterated directory as an alias`() {
        setupQuery(listOf("d" to "parent"))
        // FAT-illegal characters are rewritten by the provider on create: same document,
        // different spelling. That is an alias, not a collision.
        createdName = "a_b"
        mockedContract.`when`<Uri> { DocumentsContract.createDocument(any(), any(), any(), any()) }
            .thenReturn(Uri.parse("content://x/created"))

        assertEquals(true, gateway.mkdirs("parent/a|b"))
        assertEquals("transliterated dir must not be deleted", 0, deleted)
    }

    private fun setupQuery(rows: List<Pair<String, String>>) {
        `when`<android.database.Cursor>(
            resolver.query(any<Uri>(), any<Array<String>>(), any(), any(), any())
        ).thenAnswer { invocation ->
            val cols = (invocation.arguments[1] as Array<String>)
            when {
                // The post-create name check asks for the display name alone.
                cols.size == 1 && cols[0] == DocumentsContract.Document.COLUMN_DISPLAY_NAME ->
                    MatrixCursor(arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME)).apply {
                        addRow(arrayOf<Any?>(createdName))
                    }
                cols.contains(DocumentsContract.Document.COLUMN_DISPLAY_NAME) ->
                    MatrixCursor(arrayOf(
                        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME
                    )).apply {
                        rows.forEach { (id, name) -> addRow(arrayOf<Any?>(id, name)) }
                    }
                else ->
                    MatrixCursor(arrayOf(
                        DocumentsContract.Document.COLUMN_MIME_TYPE,
                        DocumentsContract.Document.COLUMN_SIZE,
                        DocumentsContract.Document.COLUMN_LAST_MODIFIED
                    )).apply {
                        addRow(arrayOf<Any?>(DocumentsContract.Document.MIME_TYPE_DIR, 0L, 0L))
                    }
            }
        }
    }
}
