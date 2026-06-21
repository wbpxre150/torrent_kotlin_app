package com.torrent.app.ftp

import org.apache.ftpserver.command.AbstractCommand
import org.apache.ftpserver.command.Command
import org.apache.ftpserver.ftplet.FileSystemView
import org.apache.ftpserver.ftplet.FtpRequest
import org.apache.ftpserver.impl.FtpIoSession
import org.apache.ftpserver.impl.FtpServerContext

internal sealed class ListingDecision {
    data object DelegateUnchanged : ListingDecision()
    data class CwdAndDelegate(val path: String, val optsArg: String?) : ListingDecision()
}

internal fun splitOptsPath(raw: String?): Pair<String, String> {
    if (raw.isNullOrBlank()) return "" to ""
    val tokens = raw.trim().split(' ').filter { it.isNotEmpty() }
    val opts = StringBuilder()
    var i = 0
    while (i < tokens.size && tokens[i].startsWith('-') && tokens[i].length > 1) {
        opts.append(tokens[i].substring(1))
        i++
    }
    return opts.toString() to tokens.drop(i).joinToString(" ")
}

internal fun containsGlob(path: String): Boolean =
    path.indexOfAny(charArrayOf('*', '?', '[')) >= 0

internal fun decideListing(fsView: FileSystemView, rawArg: String?): ListingDecision {
    val (opts, path) = splitOptsPath(rawArg)
    if (path.isEmpty() || !containsGlob(path)) return ListingDecision.DelegateUnchanged
    val file = fsView.getFile(path)
    if (!file.doesExist() || !file.isDirectory()) return ListingDecision.DelegateUnchanged
    val optsArg = if (opts.isEmpty()) null else "-$opts"
    return ListingDecision.CwdAndDelegate(path, optsArg)
}

internal class OptsOnlyRequest(
    private val delegate: FtpRequest,
    private val opts: String?,
) : FtpRequest {
    override fun getRequestLine(): String = delegate.getRequestLine()
    override fun getCommand(): String = delegate.getCommand()
    override fun getArgument(): String? = opts
    override fun hasArgument(): Boolean = opts != null
    override fun getReceivedTime(): Long = delegate.getReceivedTime()
}

class LiteralPathListingCommand(private val delegate: Command) : AbstractCommand() {
    override fun execute(
        session: FtpIoSession,
        context: FtpServerContext,
        request: FtpRequest,
    ) {
        val fs = session.getFileSystemView()
        when (val d = decideListing(fs, request.getArgument())) {
            is ListingDecision.DelegateUnchanged -> {
                delegate.execute(session, context, request)
            }
            is ListingDecision.CwdAndDelegate -> {
                val savedCwd = fs.getWorkingDirectory().getAbsolutePath()
                fs.changeWorkingDirectory(d.path)
                try {
                    delegate.execute(session, context, OptsOnlyRequest(request, d.optsArg))
                } finally {
                    fs.changeWorkingDirectory(savedCwd)
                }
            }
        }
    }
}