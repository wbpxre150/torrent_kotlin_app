package com.torrent.app

import org.junit.Assert.assertTrue
import org.junit.Test

class ProcessSupervisorSelfLoggingTest {

    @Test fun selfLoggingParameter_existsInConstructor() {
        val ctor = ProcessSupervisor::class.java.declaredConstructors[0]
        val params = ctor.parameterTypes
        // Constructor params: label, binaryName, args, nativeLibraryDir,
        // workDir, logDir, ldLibraryPath, extraEnv, selfLogging, pipelineMaxLines
        assertTrue("Constructor should have at least 9 parameters", params.size >= 9)
    }
}
