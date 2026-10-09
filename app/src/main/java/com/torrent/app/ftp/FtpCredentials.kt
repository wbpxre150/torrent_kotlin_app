package com.torrent.app.ftp

import com.torrent.app.config.ConfigStore

class FtpCredentials(private val store: ConfigStore) {
    fun username(): String = store.load().ftp.username
    fun password(): String = store.ftpPassword()
}