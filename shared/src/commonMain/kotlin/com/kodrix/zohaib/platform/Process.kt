package com.kodrix.zohaib.platform

data class ProcessResult(val exitCode: Int, val stdout: String, val stderr: String)

expect fun executeCommand(
    command: List<String>,
    workingDir: String? = null,
    env: Map<String, String> = emptyMap()
): ProcessResult
