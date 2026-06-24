package com.torrent.app.storage

import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class UsbPermissionManagerTest {

    private lateinit var mgr: UsbPermissionManager

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        ctx.getSharedPreferences("usb_storage", android.content.Context.MODE_PRIVATE)
            .edit().clear().commit()
        mgr = UsbPermissionManager(ctx)
    }

    @Test fun treeUri_nullWhenNothingStored() {
        assertNull(mgr.treeUri())
    }

    @Test fun clear_whenNothingStored_doesNotThrow() {
        // Previously this path was safe, but ensure no recursion regression.
        mgr.clear()
        assertNull(mgr.treeUri())
    }

    @Test fun clear_withStaleUriEntryOnly_doesNotThrowAndRemovesEntry() {
        // Store a URI string in prefs WITHOUT taking a persistable permission,
        // so isStillGranted() returns false. clear() must not recurse.
        val uri = Uri.parse("content://com.android.externalstorage.documents/tree/primary%3AFoo")
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        ctx.getSharedPreferences("usb_storage", android.content.Context.MODE_PRIVATE)
            .edit().putString("tree_uri", uri.toString()).commit()

        mgr.clear() // must not StackOverflowError

        assertNull(mgr.treeUri())
        assertEquals(false,
            ctx.getSharedPreferences("usb_storage", android.content.Context.MODE_PRIVATE)
                .contains("tree_uri"))
    }

    @Test fun treeUri_withStaleUriEntryOnly_returnsNullAndRemovesEntry() {
        val uri = Uri.parse("content://com.android.externalstorage.documents/tree/primary%3ABar")
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        ctx.getSharedPreferences("usb_storage", android.content.Context.MODE_PRIVATE)
            .edit().putString("tree_uri", uri.toString()).commit()

        assertNull(mgr.treeUri())
        // Entry cleaned up inline so subsequent calls are cheap.
        assertNull(mgr.treeUri())
        assertEquals(false,
            ctx.getSharedPreferences("usb_storage", android.content.Context.MODE_PRIVATE)
                .contains("tree_uri"))
    }
}
