package com.kodrix.zohaib.platform

actual fun executeCommand(
    command: List<String>,
    workingDir: String?,
    env: Map<String, String>
): ProcessResult {
    val processBuilder = ProcessBuilder(command)
    workingDir?.let { processBuilder.directory(java.io.File(it)) }
    processBuilder.environment().putAll(env)
    processBuilder.redirectErrorStream(false)

    val process = processBuilder.start()
    val stdout = process.inputStream.bufferedReader().readText()
    val stderr = process.errorStream.bufferedReader().readText()
    val exitCode = process.waitFor()

    return ProcessResult(exitCode, stdout, stderr)
}
