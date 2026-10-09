package com.torrent.app.ftp

import org.apache.ftpserver.ftplet.AuthenticationFailedException
import org.apache.ftpserver.ftplet.User
import org.apache.ftpserver.ftplet.UserManager
import org.apache.ftpserver.usermanager.UsernamePasswordAuthentication
import org.apache.ftpserver.usermanager.impl.BaseUser
import org.apache.ftpserver.usermanager.impl.ConcurrentLoginPermission
import org.apache.ftpserver.usermanager.impl.TransferRatePermission

class FtpUserManager(
    private val username: String,
    private val password: String,
    private val maxDownloadKiBps: Int = 0,   // 0 = unlimited (plan 07 §2.4)
) : UserManager {
    private val user: BaseUser = BaseUser().apply {
        name = username
        this.password = this@FtpUserManager.password
        homeDirectory = "/"
        setEnabled(true)
        maxIdleTime = 0
        val authorities = mutableListOf<org.apache.ftpserver.ftplet.Authority>(
            ConcurrentLoginPermission(10, 5))
        if (maxDownloadKiBps > 0) {
            // Apache TransferRatePermission takes bytes/sec; upload cap stays 0.
            authorities.add(TransferRatePermission(maxDownloadKiBps * 1024, 0))
        }
        setAuthorities(authorities)
    }
    override fun getUserByName(name: String): User? =
        if (name == username) user else null
    override fun getAllUserNames(): Array<String> = arrayOf(username)
    override fun delete(name: String) =
        throw UnsupportedOperationException("user manager is read-only")
    override fun save(user: User) =
        throw UnsupportedOperationException("user manager is read-only")
    override fun doesExist(name: String): Boolean = name == username
    override fun authenticate(authentication: org.apache.ftpserver.ftplet.Authentication): User {
        if (authentication is UsernamePasswordAuthentication) {
            if (authentication.username == username && authentication.password == password) {
                return user
            }
        }
        throw AuthenticationFailedException("invalid credentials")
    }
    override fun getAdminName(): String = username
    override fun isAdmin(name: String): Boolean = name == username
}