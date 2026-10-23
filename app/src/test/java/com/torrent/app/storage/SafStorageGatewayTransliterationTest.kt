package com.torrent.app.storage

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
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
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.mockito.stubbing.Answer
import org.robolectric.RobolectricTestRunner

/**
 * FAT-illegal filenames: the exFAT volume's ExternalStorageProvider rewrites
 * `" * / : < > ? \ |` and control chars to `_` on create, so a torrent file named
 * `Canadian | SDH.eng.HI.srt` comes back as `Canadian _ SDH.eng.HI.srt`.
 *
 * Regression under test (device symptom: torrent permanently paused with
 * "Couldn't save '.../Canadian | SDH.eng.HI.srt': No such file or directory (2)"):
 * the gateway used to treat that transliteration as a duplicate-name collision,
 * delete the document and fail the open forever. It must instead alias the
 * requested path to the transliterated document, while a genuine `" (1)"` dedup
 * collision still fails loudly (see [SafStorageGatewayCollisionTest]).
 */
@RunWith(RobolectricTestRunner::class)
class SafStorageGatewayTransliterationTest {

    private companion object {
        /** Stand-in for a detached fd; openFd() returns whatever detachFd() yielded. */
        const val TEST_FD = 4242
    }

    private lateinit var treeUri: Uri
    private lateinit var mockedContract: MockedStatic<DocumentsContract>
    private lateinit var resolver: ContentResolver
    private lateinit var gateway: SafStorageGateway
    private lateinit var createdUri: Uri
    private lateinit var pfd: ParcelFileDescriptor

    private val requested = "Canadian | SDH.eng.HI.srt"
    private val onDisk = "Canadian _ SDH.eng.HI.srt"

    @Before fun setUp() {
        treeUri = Uri.parse("content://com.android.externalstorage.documents/tree/7BFD-F8E4%3A")
        val ctx = mock(Context::class.java)
        resolver = mock(ContentResolver::class.java)
        `when`(ctx.contentResolver).thenReturn(resolver)

        createdUri = Uri.parse("content://com.android.externalstorage.documents/document/doc-created")
        // Robolectric cannot detach a real fd (FileDescriptor.getOwnerId$ is missing from
        // its libcore shim), so the bridge's fd is mocked. openFd() only calls detachFd().
        pfd = mock(ParcelFileDescriptor::class.java)
        `when`(pfd.detachFd()).thenReturn(TEST_FD)

        mockedContract = mockStatic(DocumentsContract::class.java)
        mockedContract.`when`<String> { DocumentsContract.getTreeDocumentId(any()) }
            .thenReturn("7BFD-F8E4:")
        mockedContract.`when`<Uri> { DocumentsContract.buildDocumentUriUsingTree(any(), any()) }
            .thenAnswer { inv ->
                Uri.parse("content://com.android.externalstorage.documents/document/" +
                    (inv.getArgument<Any?>(1) ?: "root"))
            }
        mockedContract.`when`<Uri> { DocumentsContract.buildChildDocumentsUriUsingTree(any(), any()) }
            .thenReturn(Uri.parse("content://com.android.externalstorage.documents/children"))
        mockedContract.`when`<Boolean> { DocumentsContract.deleteDocument(any(), any()) }
            .thenReturn(true)

        gateway = SafStorageGateway(ctx, treeUri)
    }

    @After fun tearDown() {
        mockedContract.close()
    }

    private fun stubQueries(
        children: List<Pair<String, String>>,
        displayName: String?,
        stat: Triple<Long, Long, String>? = null, // size, mtime, mime
    ) {
        `when`(resolver.query(any(Uri::class.java), any(), any(), any(), any()))
            .thenAnswer(
                Answer<Cursor?> { inv ->
                    val projection = inv.getArgument<Array<String>?>(1)
                    when (projection?.size) {
                        1 -> displayName?.let { name ->
                            MatrixCursor(
                                arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                            ).apply { addRow(arrayOf(name)) }
                        }
                        3 -> stat?.let { (size, mtime, mime) ->
                            MatrixCursor(
                                arrayOf(
                                    DocumentsContract.Document.COLUMN_MIME_TYPE,
                                    DocumentsContract.Document.COLUMN_SIZE,
                                    DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                            ).apply { addRow(arrayOf(mime, size, mtime)) }
                        }
                        else -> MatrixCursor(
                            arrayOf(
                                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                                DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                        ).apply { children.forEach { addRow(arrayOf(it.first, it.second)) } }
                    }
                })
    }

    private fun stubCreate() {
        mockedContract.`when`<Uri> { DocumentsContract.createDocument(any(), any(), any(), any()) }
            .thenReturn(createdUri)
        `when`(resolver.openFileDescriptor(any(Uri::class.java), any(String::class.java)))
            .thenReturn(pfd)
    }

    @Test fun openFd_acceptsProviderTransliteration_andAliasesRequestedPath() {
        stubQueries(children = emptyList(), displayName = onDisk)
        stubCreate()

        val fd = gateway.openFd(requested, "rw")

        assertEquals(TEST_FD, fd)
        // The daemon's namespace keeps the requested name...
        assertTrue(gateway.sharedCacheKeys().contains(requested))
        // ...and the document is kept, not deleted as a collision.
        mockedContract.verify({ DocumentsContract.deleteDocument(any(), any()) }, never())
    }

    @Test fun openFd_secondCallReusesAliasWithoutCreatingAgain() {
        stubQueries(children = emptyList(), displayName = onDisk)
        stubCreate()

        assertEquals(TEST_FD, gateway.openFd(requested, "rw"))
        assertEquals(TEST_FD, gateway.openFd(requested, "rw"))

        mockedContract.verify({ DocumentsContract.createDocument(any(), any(), any(), any()) },
            org.mockito.Mockito.times(1))
    }

    @Test fun resolve_findsTransliteratedDocumentAfterCacheLoss() {
        // Simulates a fresh process / evicted cache: nothing is created, the walk must
        // match the on-disk spelling against the requested name.
        stubQueries(children = listOf("doc-1" to onDisk), displayName = null)

        assertTrue(gateway.exists(requested))
        mockedContract.verify({ DocumentsContract.createDocument(any(), any(), any(), any()) },
            never())
        mockedContract.verify({ DocumentsContract.deleteDocument(any(), any()) }, never())
    }

    @Test fun openFd_stillFailsLoudlyOnDedupCollision() {
        // Requested illegal name, but the provider had to dedup: a *different* file owns
        // 'Canadian _ SDH.eng.HI.srt', so this one is unrecoverable and must not be aliased.
        stubQueries(children = emptyList(), displayName = "$onDisk (1)")
        stubCreate()

        assertEquals(-1, gateway.openFd(requested, "rw"))
        mockedContract.verify({ DocumentsContract.deleteDocument(any(), any()) },
            org.mockito.Mockito.times(1))
        assertFalse(gateway.sharedCacheKeys().contains(requested))
    }

    @Test fun openFd_representableNameIsUnchanged() {
        stubQueries(children = emptyList(), displayName = "movie.mp4")
        stubCreate()

        val fd = gateway.openFd("movie.mp4", "rw")

        assertEquals(TEST_FD, fd)
        assertTrue(gateway.sharedCacheKeys().contains("movie.mp4"))
        mockedContract.verify({ DocumentsContract.deleteDocument(any(), any()) }, never())
    }

    @Test fun readOnlyWalk_findsTransliteratedDocument() {
        stubQueries(
            children = listOf("doc-1" to onDisk),
            displayName = null,
            stat = Triple(187_604L, 1_760_000_000_000L, "application/octet-stream"))

        val info = gateway.readOnlyStat(requested)

        assertTrue(info != null)
        assertEquals(187_604L, info!!.size)
        assertFalse(info.isDir)
    }

    @Test fun sanitizeHelperMatchesDeviceObservation() {
        assertEquals(onDisk, SafNames.sanitize(requested))
    }
}
