package com.kodrix.zohaib.model

import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient

class TerminalInstance(
    val id: Int, 
    executablePath: String, 
    cwd: String, 
    args: Array<String>, 
    env: Array<String>, 
    client: TerminalSessionClient
) {
    val session: TerminalSession = TerminalSession(
        executablePath,
        cwd,
        args,
        env,
        10000,
        client
    )

    // Holds a reference to the TerminalView currently displaying this instance
    var boundView: com.termux.view.TerminalView? = null

    fun sendInput(text: String) {
        session.write(text)
    }

    fun resize(columns: Int, rows: Int) {
        session.updateSize(columns, rows)
    }

    fun getAllOutput(): String {
        return session.emulator?.screen?.getTranscriptText() ?: ""
    }
}