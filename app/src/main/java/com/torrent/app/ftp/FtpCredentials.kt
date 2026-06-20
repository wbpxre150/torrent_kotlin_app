package com.torrent.app

import android.content.Context
import java.security.SecureRandom

class FtpCredentials(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun username(): String = USERNAME

    fun password(): String =
        prefs.getString(KEY_PASSWORD, null) ?: randomString(PASSWORD_LEN)
            .also { prefs.edit().putString(KEY_PASSWORD, it).apply() }

    companion object {
        private const val PREFS = "ftp_creds"
        private const val KEY_PASSWORD = "password"
        const val USERNAME = "kodi"
        private const val PASSWORD_LEN = 24
        private val PASSWORD_CHARS =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789".toCharArray()

        private fun randomString(len: Int): String {
            val rnd = SecureRandom()
            val sb = StringBuilder(len)
            repeat(len) { sb.append(PASSWORD_CHARS[rnd.nextInt(PASSWORD_CHARS.size)]) }
            return sb.toString()
        }
    }
}