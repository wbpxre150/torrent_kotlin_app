package com.torrent.app.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RestoreProgressTextTest {
    @Test
    fun `formats done and total`() {
        assertEquals("2 of 3 objects imported", RestoreProgressText.format(2, 3))
    }

    @Test
    fun `no text before the imported count publishes`() {
        assertNull(RestoreProgressText.format(null, 3))
    }

    @Test
    fun `no text before the object count is known`() {
        assertNull(RestoreProgressText.format(2, null))
    }

    @Test
    fun `formats zero done`() {
        assertEquals("0 of 3 objects imported", RestoreProgressText.format(0, 3))
    }
}
