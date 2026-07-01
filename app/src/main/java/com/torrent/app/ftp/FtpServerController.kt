package com.torrent.app.ftp

import android.util.Log
import com.torrent.app.storage.SafStorageGateway
import org.apache.ftpserver.FtpServer
import org.apache.ftpserver.FtpServerFactory
import org.apache.ftpserver.DataConnectionConfigurationFactory
import org.apache.ftpserver.command.CommandFactory
import org.apache.ftpserver.listener.ListenerFactory
import java.io.IOException
import java.io.InputStream

enum class FtpState { STOPPED, STARTING, RUNNING }

/**
 * Owns the embedded Apache FtpServer. Read-only, wifi-only: the listener is
 * always bound to a specific wifi IPv4 (caller passes it), never 0.0.0.0.
 * Stop before rebind with a new IP.
 */
class FtpServerController(
    private val gateway: SafStorageGateway,
    private val creds: FtpCredentials,
    private val port: Int = 2121,
    private val passivePorts: String = "30000-30009",
    private val rootRelPath: String = "",
) {
    private var server: FtpServer? = null

    @Volatile private var currentState: FtpState = FtpState.STOPPED
    @Volatile private var currentIp: String? = null

    val state: FtpState get() = currentState
    val ip: String? get() = currentIp

    private val fileSource = SafFtpFileSource(gateway)

    // Opens a seekable read stream for [relPath] starting at [offset]. Closes the
    // underlying SAF pfd when the stream is closed.
    private val streamProvider: (String, Long) -> InputStream = { relPath, offset ->
        val pfd = fileSource.openReadPfd(relPath)
            ?: throw IOException("file not found: $relPath")
        // Detach the raw fd from the pfd so the FileInputStream owns it and closes it
        // exactly once. The previous code called both fis.close() (via
        // FilterInputStream) and pfd.close(); the second close hit an already-closed
        // fd, and if the kernel had recycled that fd number into a concurrent SAF
        // operation (transmission IPC openFd), it corrupted the other operation.
        val fd = pfd.fileDescriptor
        pfd.detachFd()
        val fis = java.io.FileInputStream(fd)
        if (offset > 0) {
            try { fis.channel.position(offset) }
            catch (e: IOException) { fis.close(); throw e }
        }
        fis
    }

    @Synchronized
    fun start(ip: String) {
        if (currentState == FtpState.RUNNING || currentState == FtpState.STARTING) return
        currentState = FtpState.STARTING
        currentIp = ip
        try {
            val dataFactory = DataConnectionConfigurationFactory().apply {
                setActiveEnabled(false)                       // passive only (Kodi + LAN safety)
                setPassiveAddress(ip)
                setPassiveExternalAddress(ip)                 // what PASV reports to clients
                setPassivePorts(this@FtpServerController.passivePorts)
                setIdleTime(300)
            }
            val listener = ListenerFactory().apply {
                setServerAddress(ip)                          // bind wifi interface only
                setPort(this@FtpServerController.port)
                setDataConnectionConfiguration(dataFactory.createDataConnectionConfiguration())
                setIdleTimeout(300)
            }.createListener()

            val factory = FtpServerFactory().apply {
                addListener("default", listener)
                setUserManager(FtpUserManager(creds.username(), creds.password()))
                setFileSystem(SafFtpFileSystemFactory(fileSource, streamProvider, rootRelPath))
                val defaultCmds = commandFactory
                setCommandFactory(CommandFactory { name ->
                    val stock = defaultCmds.getCommand(name)
                    when (name?.uppercase()) {
                        "LIST", "NLST", "MLSD" ->
                            LiteralPathListingCommand(stock!!)
                        else -> stock
                    }
                })
            }
            val s = factory.createServer()
            s.start()
            server = s
            currentState = FtpState.RUNNING
        } catch (e: Exception) {
            Log.e(TAG, "start failed: ${e.message}")
            currentState = FtpState.STOPPED
            currentIp = null
            throw e
        }
    }

    @Synchronized
    fun stop() {
        val s = server ?: run { currentState = FtpState.STOPPED; currentIp = null; return }
        currentState = FtpState.STOPPED
        currentIp = null
        try { s.stop() } catch (e: Exception) { Log.w(TAG, "stop: ${e.message}") }
        server = null
    }

    /** Rebind to a new wifi IP; no-op if ip unchanged and running. */
    @Synchronized
    fun rebind(newIp: String) {
        if (currentState == FtpState.RUNNING && currentIp == newIp) return
        stop()
        start(newIp)
    }

    companion object { private const val TAG = "FtpServerController" }
}