package com.torrent.app

import android.content.Context
import kotlin.random.Random

class TokenManager(context: Context) {

    private val prefs = context.getSharedPreferences("control_auth", Context.MODE_PRIVATE)

    /** Returns the current token, generating one on first access. */
    fun getToken(): String {
        prefs.getString(KEY_TOKEN, null)?.let { return it }
        val token = generateToken()
        prefs.edit().putString(KEY_TOKEN, token).apply()
        return token
    }

    /** Returns true if [candidate] matches the stored token. */
    fun validate(candidate: String): Boolean {
        val stored = prefs.getString(KEY_TOKEN, null) ?: return false
        // Constant-time comparison to avoid timing side-channels on localhost
        return stored.length == candidate.length &&
            stored.foldIndexed(0) { i, acc, c -> acc or (c.code xor candidate[i].code) } == 0
    }

    private fun generateToken(): String {
        val bytes = Random.nextBytes(32)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val KEY_TOKEN = "auth_token"
    }
}
