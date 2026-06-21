package com.torrent.app.storage

import android.net.LocalServerSocket
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.DataInputStream
import java.io.File

/**
 * Accepts SAF fd-bridge connections from the forked transmission process over a
 * filesystem Unix socket at [socketFile]. One persistent connection per native thread;
 * each connection handles a request/response stream until EOF.
 *
 * Thread-safety: the handler dispatches to [UsbFdBridge] which is itself thread-safe.
 */
class SafIpcServer(
    private val socketFile: File,
    private val handler: SafIpcRequestHandler,
) {
    private var scope: CoroutineScope? = null
    private var serverSocket: LocalServerSocket? = null
    @Volatile private var running = false

    fun start() {
        if (running) return
        // Remove a stale socket file from a previous crashed instance; a leftover
        // file would make bind() fail with EADDRINUSE. Filesystem-namespace only
        // (abstract namespace has no on-disk file). The app-private filesDir has
        // no permission race, so filesystem namespace is safe and debuggable.
        if (socketFile.exists()) socketFile.delete()
        // Filesystem-namespace Unix socket at filesDir/saf-bridge.sock. The
        // native file-saf.cc client connects with a plain sun_path (no null
        // prefix). The app-private filesDir is owned by this UID, so there is no
        // permission race; the on-disk socket file is unlinked before bind and
        // on stop().
        val ls = LocalSocket()
        val addr = LocalSocketAddress(socketFile.absolutePath, LocalSocketAddress.Namespace.FILESYSTEM)
        ls.bind(addr)
        serverSocket = LocalServerSocket(ls.fileDescriptor)
        running = true
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob() + kotlinx.coroutines.CoroutineName("saf-ipc"))
        scope!!.launch { acceptLoop() }
        Log.i(TAG, "listening on ${socketFile.absolutePath}")
    }

    fun stop() {
        running = false
        scope?.cancel()
        scope = null
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
        socketFile.delete()
    }

    private suspend fun acceptLoop() {
        val ss = serverSocket ?: return
        while (running) {
            val client = try { ss.accept() } catch (e: Exception) {
                if (running) Log.e(TAG, "accept failed: ${e.message}")
                break
            }
            // handle each connection on its own job
            scope?.launch { handleConnection(client) }
        }
    }

    private fun handleConnection(socket: LocalSocket) {
        try {
            val input = DataInputStream(socket.inputStream)
            val output = socket.outputStream
            while (running) {
                val header = ByteArray(3)
                if (!readFully(input, header)) break // EOF
                val op = header[0]
                val len = ((header[1].toInt() and 0xff)) or ((header[2].toInt() and 0xff) shl 8)
                val payload = if (len > 0) ByteArray(len).also { readFully(input, it) } else ByteArray(0)
                val req = SafIpcCodec.Request(op, payload)
                val handled = handler.handle(req)
                // Attach the fd (SCM_RIGHTS) to the next write for OPEN success.
                if (handled.fd != null) {
                    socket.setFileDescriptorsForSend(arrayOf(handled.fd.fileDescriptor))
                }
                val frame = SafIpcCodec.encodeResponse(handled.status, handled.payload)
                output.write(frame)
                output.flush()
                handled.fd?.let { pfd -> try { pfd.close() } catch (_: Exception) {} }
            }
        } catch (e: Exception) {
            Log.e(TAG, "connection error: ${e.message}")
        } finally {
            try { socket.close() } catch (_: Exception) {}
        }
    }

    private fun readFully(input: DataInputStream, buf: ByteArray): Boolean {
        var off = 0
        while (off < buf.size) {
            val n = input.read(buf, off, buf.size - off)
            if (n < 0) return false
            off += n
        }
        return true
    }

    companion object { private const val TAG = "SafIpcServer" }
}