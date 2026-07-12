#!/bin/bash
# Fix LSP autocomplete not showing in KodrixIDE
# Run this from your Kodrix project root: bash fix-lsp.sh

set -e

VM="app/src/main/kotlin/com/kodrix/zohaib/viewmodel/TerminalViewModel.kt"
LSP="app/src/main/kotlin/com/kodrix/zohaib/lsp/LspClient.kt"

echo "=== KodrixIDE LSP Completion Fix ==="
echo ""

# ── Backup ──
cp "$VM" "${VM}.bak"
cp "$LSP" "${LSP}.bak"
echo "[1/6] Backed up original files (.bak)"

# ── Fix 1: LSP timeout 5000 -> 15000 ──
sed -i 's/withTimeoutOrNull(5000)/withTimeoutOrNull(15000)/' "$LSP"
echo "[2/6] LSP timeout: 5s -> 15s"

# ── Fix 2: Add lspOpenFiles after isInstallingCpp ──
sed -i '/private var isInstallingCpp = false/a\    /** Tracks which file URI is currently open in each LSP group. */\n    private val lspOpenFiles = mutableMapOf<String, String>() // langGroup -> file URI' "$VM"
echo "[3/6] Added lspOpenFiles tracking map"

# ── Fix 3: Add getLanguageGroup() helper before isLspSupported ──
sed -i '/private fun isLspSupported/i\    /** Map a file extension to its LSP language group key. */\n    private fun getLanguageGroup(extension: String): String? = when (extension.lowercase()) {\n        "html", "htm" -> "html"\n        "css" -> "css"\n        "json" -> "json"\n        "js", "javascript", "jsx", "mjs" -> "javascript"\n        "ts", "typescript", "tsx", "mts" -> "typescript"\n        "c", "cpp", "cc", "cxx", "h", "hpp" -> "cpp"\n        "py" -> "python"\n        else -> null\n    }\n' "$VM"
echo "[4/6] Added getLanguageGroup() helper"

# ── Fix 4: isLspSupported use getLanguageGroup ──
sed -i 's/private fun isLspSupported(extension: String) = getLanguageId(extension)/private fun isLspSupported(extension: String) = getLanguageGroup(extension)/' "$VM"
echo "[4/6] Updated isLspSupported"

# ── Fix 5: startLsp - replace the early return with didOpen logic ──
# This is the critical fix. We replace:
#   if (activeLSPs.containsKey(ext)) return // already running
# With code that sends didOpen for the new file if LSP is already running.
python3 << 'PYEOF'
import re

with open("app/src/main/kotlin/com/kodrix/zohaib/viewmodel/TerminalViewModel.kt", "r") as f:
    content = f.read()

old = '''    private fun startLsp(file: java.io.File) {
        val ext = file.extension.lowercase()
        val langId = getLanguageId(ext) ?: return
        if (activeLSPs.containsKey(ext)) return // already running'''

new = '''    private fun startLsp(file: java.io.File) {
        val ext = file.extension.lowercase()
        val langGroup = getLanguageGroup(ext) ?: return
        val langId = getLanguageId(ext) ?: return
        val fileUri = "file://${file.absolutePath}"

        // If LSP is already running for this language group, just send didOpen for the new file
        val existingClient = activeLSPs[langGroup]
        if (existingClient != null) {
            if (lspOpenFiles[langGroup] != fileUri) {
                val prevUri = lspOpenFiles[langGroup]
                if (prevUri != null) {
                    existingClient.notify("textDocument/didClose",
                        mapOf("textDocument" to mapOf("uri" to prevUri)))
                }
                viewModelScope.launch(Dispatchers.IO) {
                    try {
                        val text = file.readText()
                        val openParams = com.kodrix.zohaib.lsp.DidOpenTextDocumentParams(
                            textDocument = com.kodrix.zohaib.lsp.TextDocumentItem(
                                uri = fileUri, languageId = langId,
                                version = ++lspDocVersion, text = text
                            )
                        )
                        existingClient.notify("textDocument/didOpen", openParams)
                        lspOpenFiles[langGroup] = fileUri
                        Log.d("Kodrix", "LSP: Sent didOpen for ${file.name} (reusing $langGroup server)")
                    } catch (e: Exception) {
                        Log.e("Kodrix", "LSP: Failed to send didOpen for ${file.name}", e)
                    }
                }
            }
            return
        }'''

if old in content:
    content = content.replace(old, new, 1)
    print("[5/6] Fixed startLsp() - sends didOpen for new files")
else:
    print("[5/6] WARNING: Could not find startLsp pattern (already patched?)")

with open("app/src/main/kotlin/com/kodrix/zohaib/viewmodel/TerminalViewModel.kt", "w") as f:
    f.write(content)
PYEOF

# ── Fix 6: Replace all remaining activeLSPs[ext] with langGroup lookups ──
python3 << 'PYEOF'
with open("app/src/main/kotlin/com/kodrix/zohaib/viewmodel/TerminalViewModel.kt", "r") as f:
    content = f.read()

# In startLsp: startNativeLsp(langId, ext, file) -> startNativeLsp(langId, langGroup, file)
content = content.replace(
    'startNativeLsp(langId, ext, file)',
    'startNativeLsp(langId, langGroup, file)'
)

# activeLSPs[ext] = client -> activeLSPs[langGroup] = client (in startLsp function only)
content = content.replace(
    '        activeLSPs[ext] = client',
    '        activeLSPs[langGroup] = client'
)

# Diagnostics callback - filter by active file
old_diag = '''        client.onDiagnosticsReceived = { params ->
            viewModelScope.launch(Dispatchers.Main) {
                _lspDiagnostics.value = params.diagnostics
                Log.d("Kodrix", "LSP Diagnostics: ${params.diagnostics.size} issues")
            }
        }'''

new_diag = '''        client.onDiagnosticsReceived = { params ->
            val activeFileUri = lspOpenFiles[langGroup]
            if (activeFileUri != null && params.uri == activeFileUri) {
                viewModelScope.launch(Dispatchers.Main) {
                    _lspDiagnostics.value = params.diagnostics
                    Log.d("Kodrix", "LSP Diagnostics: ${params.diagnostics.size} issues for ${file.name}")
                }
            }
        }'''

content = content.replace(old_diag, new_diag, 1)

# After didOpen in startLsp, track the file
content = content.replace(
    '''                client.notify("textDocument/didOpen", openParams)
                Log.d("Kodrix", "LSP started and initialized for .$ext files")''',
    '''                client.notify("textDocument/didOpen", openParams)
                lspOpenFiles[langGroup] = fileUri
                Log.d("Kodrix", "LSP started and initialized for .$ext files")'''
)

# stopAllLsps: add lspOpenFiles.clear()
old_stop = '''    fun stopAllLsps() {
        activeLSPs.values.forEach { it.stop() }
        activeLSPs.clear()
        _lspDiagnostics.value = emptyList()
        _completionItems.value = emptyList() // Clear completions too
    }'''

new_stop = '''    fun stopAllLsps() {
        activeLSPs.values.forEach { it.stop() }
        activeLSPs.clear()
        lspOpenFiles.clear()
        _lspDiagnostics.value = emptyList()
        _completionItems.value = emptyList()
    }'''

content = content.replace(old_stop, new_stop)

# startNativeLsp signature: ext -> langGroup
content = content.replace(
    'private fun startNativeLsp(langId: String, ext: String, file:',
    'private fun startNativeLsp(langId: String, langGroup: String, file:'
)

# launchLspClient calls: ext -> langGroup
content = content.replace('launchLspClient(ext,', 'launchLspClient(langGroup,')

# launchLspClient signature: ext -> langGroup
content = content.replace(
    'private suspend fun launchLspClient(\n        ext: String,',
    'private suspend fun launchLspClient(\n        langGroup: String,'
)

# activeLSPs[ext] -> activeLSPs[langGroup] in launchLspClient
content = content.replace('activeLSPs[ext] = client', 'activeLSPs[langGroup] = client')

# Add lspOpenFiles tracking after didOpen in launchLspClient
old_native_didopen = '''            client.notify("textDocument/didOpen", openParams)
            Log.d("Kodrix", "Native LSP ($langId) started for ${file.name}")'''

new_native_didopen = '''            client.notify("textDocument/didOpen", openParams)
            lspOpenFiles[langGroup] = "file://${file.absolutePath}"
            Log.d("Kodrix", "Native LSP ($langId) started for ${file.name}")'''

content = content.replace(old_native_didopen, new_native_didopen)

# doRequestCompletion: activeLSPs[ext] -> langGroup lookup
old_do_req = '''        val client = activeLSPs[ext] ?: return'''
new_do_req = '''        val langGroup = getLanguageGroup(ext) ?: return
        val client = activeLSPs[langGroup] ?: return'''
content = content.replace(old_do_req, new_do_req)

# updateEditorText: activeLSPs[ext] -> langGroup lookup
old_update = '''        activeLSPs[ext]?.let { client ->'''
new_update = '''        val langGroup = getLanguageGroup(ext) ?: return
        activeLSPs[langGroup]?.let { client ->'''
content = content.replace(old_update, new_update)

# applyCompletion: activeLSPs[ext] -> langGroup lookup
old_apply = '''            activeLSPs[ext]?.let { client ->'''
new_apply = '''            val langGroup = getLanguageGroup(ext) ?: return
            activeLSPs[langGroup]?.let { client ->'''
content = content.replace(old_apply, new_apply)

with open("app/src/main/kotlin/com/kodrix/zohaib/viewmodel/TerminalViewModel.kt", "w") as f:
    f.write(content)

print("[6/6] Applied all langGroup replacements")
PYEOF

echo ""
echo "=== All fixes applied! ==="
echo ""
echo "To undo:  cp ${VM}.bak ${VM} && cp ${LSP}.bak ${LSP}"
echo "To build: ./gradlew assembleDebug"