package com.torrent.app.storage

import android.content.ContentResolver
import android.content.Context
import android.database.MatrixCursor
import android.net.Uri
import android.provider.DocumentsContract
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.any
import org.mockito.MockedStatic
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockStatic
import org.mockito.Mockito.`when`
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SafStorageGatewayAvailableBytesTest {

    private lateinit var mockedContract: MockedStatic<DocumentsContract>
    private lateinit var resolver: ContentResolver

    @Before fun setUp() {
        mockedContract = mockStatic(DocumentsContract::class.java)
        resolver = mock(ContentResolver::class.java)
    }

    @After fun tearDown() {
        mockedContract.close()
    }

    @Test fun availableBytes_returnsMatchingRootNotFirstRow() {
        val authority = "com.android.externalstorage.documents"
        val treeRootId = "6E6B-757C:"
        val treeUri = Uri.parse("content://$authority/tree/$treeRootId")

        mockedContract.`when`<String> { DocumentsContract.getTreeDocumentId(any()) }
            .thenReturn(treeRootId)
        mockedContract.`when`<Uri> { DocumentsContract.buildDocumentUriUsingTree(any(), any()) }
            .thenReturn(Uri.parse("content://$authority/doc/root"))
        mockedContract.`when`<Uri> { DocumentsContract.buildRootsUri(any()) }
            .thenReturn(Uri.parse("content://$authority/roots"))

        val cursor = MatrixCursor(
            arrayOf(
                DocumentsContract.Root.COLUMN_DOCUMENT_ID,
                DocumentsContract.Root.COLUMN_AVAILABLE_BYTES))
        cursor.newRow().add("primary").add(50_000_000_000L)
        cursor.newRow().add("6E6B-757C").add(7_800_000_000_000L)
        cursor.newRow().add("1234-ABCD").add(1_000_000_000_000L)

        `when`(resolver.query(any<Uri>(), any<Array<String>>(), any(), any(), any()))
            .thenReturn(cursor)

        val ctx = mock(Context::class.java)
        `when`(ctx.contentResolver).thenReturn(resolver)

        val gateway = SafStorageGateway(ctx, treeUri)
        assertEquals(7_800_000_000_000L, gateway.availableBytes())
        cursor.close()
    }

    @Test fun availableBytes_returnsSentinelWhenTreeRootNotInCursor() {
        val authority = "com.android.externalstorage.documents"
        val treeRootId = "6E6B-757C:"
        val treeUri = Uri.parse("content://$authority/tree/$treeRootId")

        mockedContract.`when`<String> { DocumentsContract.getTreeDocumentId(any()) }
            .thenReturn(treeRootId)
        mockedContract.`when`<Uri> { DocumentsContract.buildDocumentUriUsingTree(any(), any()) }
            .thenReturn(Uri.parse("content://$authority/doc/root"))
        mockedContract.`when`<Uri> { DocumentsContract.buildRootsUri(any()) }
            .thenReturn(Uri.parse("content://$authority/roots"))

        val cursor = MatrixCursor(
            arrayOf(
                DocumentsContract.Root.COLUMN_DOCUMENT_ID,
                DocumentsContract.Root.COLUMN_AVAILABLE_BYTES))
        cursor.newRow().add("primary").add(50_000_000_000L)

        `when`(resolver.query(any<Uri>(), any<Array<String>>(), any(), any(), any()))
            .thenReturn(cursor)

        val ctx = mock(Context::class.java)
        `when`(ctx.contentResolver).thenReturn(resolver)

        val gateway = SafStorageGateway(ctx, treeUri)
        assertEquals(Long.MAX_VALUE, gateway.availableBytes())
        cursor.close()
    }

    @Test fun availableBytes_returnsSentinelWhenMatchedRowIsNull() {
        val authority = "com.android.externalstorage.documents"
        val treeRootId = "6E6B-757C:"
        val treeUri = Uri.parse("content://$authority/tree/$treeRootId")

        mockedContract.`when`<String> { DocumentsContract.getTreeDocumentId(any()) }
            .thenReturn(treeRootId)
        mockedContract.`when`<Uri> { DocumentsContract.buildDocumentUriUsingTree(any(), any()) }
            .thenReturn(Uri.parse("content://$authority/doc/root"))
        mockedContract.`when`<Uri> { DocumentsContract.buildRootsUri(any()) }
            .thenReturn(Uri.parse("content://$authority/roots"))

        val cursor = MatrixCursor(
            arrayOf(
                DocumentsContract.Root.COLUMN_DOCUMENT_ID,
                DocumentsContract.Root.COLUMN_AVAILABLE_BYTES))
        cursor.newRow().add("6E6B-757C").add(null)

        `when`(resolver.query(any<Uri>(), any<Array<String>>(), any(), any(), any()))
            .thenReturn(cursor)

        val ctx = mock(Context::class.java)
        `when`(ctx.contentResolver).thenReturn(resolver)

        val gateway = SafStorageGateway(ctx, treeUri)
        assertEquals(Long.MAX_VALUE, gateway.availableBytes())
        cursor.close()
    }
}
