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
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
class SafStorageGatewayFreeSpaceTest {

    private lateinit var treeUri: Uri
    private lateinit var mockedContract: MockedStatic<DocumentsContract>
    private lateinit var resolver: ContentResolver
    private lateinit var gateway: SafStorageGateway

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
        }.thenReturn(Uri.parse("content://x/doc"))
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
}
