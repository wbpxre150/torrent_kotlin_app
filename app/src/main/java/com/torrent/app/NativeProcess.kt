package com.torrent.app

import android.util.Log
import java.io.File

/**
 * Shared exec primitives for native child processes. Both [ProcessSupervisor]
 * (long-running daemons) and [OneShotRunner] (maintenance one-shots) launch and
 * drain children through these, so the app has a single launch path
 * (plan 10 §4: "factor out a one-shot runner rather than a second exec path").
 *
 * The binary is exec'd in place from the caller's nativeLibraryDir: that path
 * carries the SELinux label `apk_data_file`, which `untrusted_app` is permitted
 * to execute. Staging the binary into a work dir (label `app_data_file`) is
 * forbidden by Android W^X enforcement and must not be done.
 */
internal object NativeProcess {

    private const val TAG = "NativeProcess"

    /**
     * Resolves [binaryName] under [nativeLibraryDir] and verifies it is
     * executable (setting the exec bit when missing). Returns null when the
     * binary is absent or cannot be made executable. [label] is only used for
     * log messages.
     */
    internal fun resolveBinary(label: String, nativeLibraryDir: String, binaryName: String): File? {
        val binary = File(nativeLibraryDir, binaryName)
        if (!binary.exists()) {
            Log.e(TAG, "[$label] binary not found: ${binary.absolutePath}")
            return null
        }
        if (!binary.canExecute()) {
            binary.setExecutable(true, false)
            if (!binary.canExecute()) {
                Log.e(TAG, "[$label] binary not executable: ${binary.absolutePath}")
                return null
            }
        }
        return binary
    }

    /** The argv for [binary] + [args] (the binary's absolute path first). */
    internal fun buildCommand(binary: File, args: List<String>): List<String> =
        listOf(binary.absolutePath) + args

    /**
     * Launches [cmd] with [workDir] as the child's cwd, [redirectErrorStream]
     * on (stderr merged into stdout), and the optional [ldLibraryPath] and
     * [extraEnv]. Returns null (and logs) when the exec fails.
     */
    internal fun startProcess(
        cmd: List<String>,
        workDir: File,
        ldLibraryPath: String?,
        extraEnv: Map<String, String>,
    ): Process? {
        // workDir is the child's cwd (ProcessBuilder.directory). It must exist
        // before start(): ProcessBuilder forks and calls chdir(workDir) before
        // execve(); a missing cwd yields ENOENT that Java's IOException reports
        // against the program path, masking the real cause.
        workDir.mkdirs()
        val env = ProcessBuilder().environment().apply {
            if (ldLibraryPath != null) {
                put("LD_LIBRARY_PATH", ldLibraryPath)
            }
            putAll(extraEnv)
        }
        return try {
            ProcessBuilder(cmd)
                .directory(workDir)
                .redirectErrorStream(true)
                .apply { environment().putAll(env) }
                .start()
        } catch (e: Exception) {
            Log.e(TAG, "exec failed: ${e.message}", e)
            null
        }
    }

    /** Best-effort child PID (reflection over the hidden `pid` field). */
    internal fun pidOf(proc: Process): Int? = try {
        val f = proc.javaClass.getDeclaredField("pid")
        f.isAccessible = true
        (f.get(proc) as? Int)
    } catch (_: Exception) {
        null
    }
}
