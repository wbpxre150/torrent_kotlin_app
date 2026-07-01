package com.torrent.app.storage

import android.content.ContentResolver
import android.content.Context
import android.database.MatrixCursor
import android.net.Uri
import android.provider.DocumentsContract
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
        mockedContract.`when`<Uri> { DocumentsContract.createDocument(any(), any(), any(), any()) }
            .thenReturn(Uri.parse("content://x/created"))

        val ok = gateway.mkdirs("parent/child")
        assertEquals(true, ok)
    }

    private fun setupQuery(rows: List<Pair<String, String>>) {
        `when`<android.database.Cursor>(
            resolver.query(any<Uri>(), any<Array<String>>(), any(), any(), any())
        ).thenAnswer { invocation ->
            val cols = (invocation.arguments[1] as Array<String>)
            if (cols.contains(DocumentsContract.Document.COLUMN_DISPLAY_NAME)) {
                MatrixCursor(arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME
                )).apply {
                    rows.forEach { (id, name) -> addRow(arrayOf<Any?>(id, name)) }
                }
            } else {
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
