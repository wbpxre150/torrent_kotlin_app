package com.torrent.app.ftp

import org.apache.ftpserver.ftplet.AuthenticationFailedException
import org.apache.ftpserver.usermanager.UsernamePasswordAuthentication
import org.apache.ftpserver.usermanager.impl.BaseUser
import org.junit.Assert.*
import org.junit.Test

class FtpUserManagerTest {
    private val um = FtpUserManager("kodi", "secret")

    @Test fun authenticate_correctReturnsUser() {
        val u = um.authenticate(UsernamePasswordAuthentication("kodi", "secret"))
        assertEquals("kodi", u.name)
        assertEquals("/", u.homeDirectory)
        assertTrue(u.enabled)
    }

    @Test fun authenticate_wrongPasswordThrows() {
        assertThrows(AuthenticationFailedException::class.java) {
            um.authenticate(UsernamePasswordAuthentication("kodi", "wrong"))
        }
    }

    @Test fun authenticate_unknownUserThrows() {
        assertThrows(AuthenticationFailedException::class.java) {
            um.authenticate(UsernamePasswordAuthentication("nobody", "secret"))
        }
    }

    @Test fun authenticate_nonUsernamePasswordThrows() {
        val anon = object : org.apache.ftpserver.ftplet.Authentication {}
        assertThrows(AuthenticationFailedException::class.java) { um.authenticate(anon) }
    }

    @Test fun getUserByName_returnsUserOrNull() {
        assertEquals("kodi", um.getUserByName("kodi")?.name)
        assertNull(um.getUserByName("other"))
    }

    @Test fun doesExist_andAllUserNames() {
        assertTrue(um.doesExist("kodi"))
        assertFalse(um.doesExist("other"))
        assertArrayEquals(arrayOf("kodi"), um.allUserNames)
        assertEquals("kodi", um.adminName)
        assertTrue(um.isAdmin("kodi"))
        assertFalse(um.isAdmin("other"))
    }

    @Test fun saveAndDelete_throw() {
        assertThrows(UnsupportedOperationException::class.java) { um.delete("kodi") }
        assertThrows(UnsupportedOperationException::class.java) { um.save(BaseUser()) }
    }
}