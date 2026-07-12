package com.kodrix.zohaib.platform

expect class PlatformTerminal {
    fun startShell(shell: String, homeDir: String, rows: Int, cols: Int, env: Map<String, String> = emptyMap())
    fun write(data: ByteArray)
    fun read(): ByteArray?
    fun resize(rows: Int, cols: Int)
    fun close()
    fun isRunning(): Boolean
}
