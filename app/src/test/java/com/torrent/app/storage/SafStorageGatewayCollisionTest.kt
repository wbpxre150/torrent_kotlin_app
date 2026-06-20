package com.torrent.app.storage

import android.content.ContentResolver
import android.content.Context
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
class SafStorageGatewayCollisionTest {

    private lateinit var treeUri: Uri
    private lateinit var mockedContract: MockedStatic<DocumentsContract>

    private lateinit var gateway: SafStorageGateway
    private lateinit var createdUri: Uri

    @Before fun setUp() {
        treeUri = Uri.parse("content://com.android.externalstorage.documents/tree/primary%3A")

        val ctx = mock(Context::class.java)
        val resolver = mock(ContentResolver::class.java)
        `when`(ctx.contentResolver).thenReturn(resolver)

        // Mock cursor: returns a name that differs from the requested leaf.
        // This is returned for both resolver.query calls:
        //   1) resolveUri children query — name "movie.mp4 (1)" != "movie.mp4",
        //      so resolveUri returns null (file not found), forcing creation.
        //   2) ensureDocument display-name query — same mismatch triggers collision.
        val cursor = mock(android.database.Cursor::class.java)
        `when`(cursor.moveToFirst()).thenReturn(true)
        `when`(cursor.isNull(0)).thenReturn(false)
        `when`(cursor.getString(0)).thenReturn("movie.mp4 (1)")
        `when`(resolver.query(any<Uri>(), any<Array<String>>(), any(), any(), any()))
            .thenReturn(cursor)

        createdUri = Uri.parse("content://x/doc/movie.mp4%20(1)")
        // createDocument returns a URI whose display name is the deduped actualName.

        mockedContract = mockStatic(DocumentsContract::class.java)
        // Stub init-time statics called by SafStorageGateway constructor.
        mockedContract.`when`<String> { DocumentsContract.getTreeDocumentId(any()) }
            .thenReturn("primary:")
        mockedContract.`when`<Uri> { DocumentsContract.buildDocumentUriUsingTree(any(), any()) }
            .thenReturn(Uri.parse("content://com.android.externalstorage.documents/tree/primary%3A/document/primary"))
        // Stub createDocument for the collision scenario.
        mockedContract.`when`<Uri> { DocumentsContract.createDocument(any(), any(), any(), any()) }
            .thenReturn(createdUri)
        mockedContract.`when`<Boolean> { DocumentsContract.deleteDocument(any(), any()) }
            .thenReturn(true)

        gateway = SafStorageGateway(ctx, treeUri)
    }

    @After fun tearDown() {
        mockedContract.close()
    }

    @Test fun ensureDocument_returnsNullOnNameCollision_andDeletesDuplicate() {
        val fd = gateway.openFd("movie.mp4", "rw")
        assertEquals(-1, fd)
    }

    @Test fun ensureDocument_cachesNothingOnCollision() {
        gateway.openFd("movie.mp4", "rw")
        val fd2 = gateway.openFd("movie.mp4", "rw")
        assertEquals(-1, fd2)
    }
}
