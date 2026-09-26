package com.kodrix.zohaib.ui

import android.content.Context
import android.graphics.Typeface
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.viewinterop.AndroidView
import com.kodrix.zohaib.lsp.Diagnostic
import com.kodrix.zohaib.viewmodel.TerminalViewModel
import io.github.rosemoe.sora.event.ContentChangeEvent
import io.github.rosemoe.sora.event.SelectionChangeEvent
import io.github.rosemoe.sora.lang.EmptyLanguage
import io.github.rosemoe.sora.lang.diagnostic.DiagnosticRegion
import io.github.rosemoe.sora.lang.diagnostic.DiagnosticsContainer
import io.github.rosemoe.sora.langs.textmate.TextMateColorScheme
import io.github.rosemoe.sora.langs.textmate.TextMateLanguage
import io.github.rosemoe.sora.langs.textmate.registry.FileProviderRegistry
import io.github.rosemoe.sora.langs.textmate.registry.GrammarRegistry
import io.github.rosemoe.sora.langs.textmate.registry.ThemeRegistry
import io.github.rosemoe.sora.langs.textmate.registry.model.ThemeModel
import io.github.rosemoe.sora.langs.textmate.registry.provider.AssetsFileResolver
import io.github.rosemoe.sora.widget.CodeEditor as SoraEditor
import io.github.rosemoe.sora.widget.component.EditorAutoCompletion
import org.eclipse.tm4e.core.registry.IThemeSource

/**
 * The experimental editor (Settings → Developer → Experimental editor): Sora Editor with VS
 * Code's TextMate grammars and Dark Modern theme from `assets/textmate/`
 * (scripts/build-textmate-assets.mjs). Only the text area is replaced; tabs, toolbar, the
 * completion dropdown and the problems panel stay in [CodeEditor].
 *
 * The ViewModel's [TerminalViewModel.EditorTab] stays the source of truth: edits are pushed to
 * it through [TerminalViewModel.updateEditorText] (so LSP sync, completion and saving work
 * unchanged), and changes made elsewhere (applying a completion, switching tabs) are pushed
 * back into the view.
 */
@Composable
fun SoraCodeEditor(
    viewModel: TerminalViewModel,
    viewportId: Int,
    tab: TerminalViewModel.EditorTab,
    diagnostics: List<Diagnostic>,
    modifier: Modifier = Modifier,
) {
    val editorFontSize by viewModel.editorFontSize.collectAsState()
    val showLineNumbers by viewModel.showLineNumbers.collectAsState()
    val state = remember { SoraState() }

    AndroidView(
        modifier = modifier,
        factory = { context ->
            TextMateSetup.ensure(context)
            SoraEditor(context).apply {
                setTypefaceText(Typeface.MONOSPACE)
                setTypefaceLineNumber(Typeface.MONOSPACE)
                setColorScheme(TextMateColorScheme.create(ThemeRegistry.getInstance()))
                // Kodrix shows LSP completions in its own dropdown.
                getComponent(EditorAutoCompletion::class.java).setEnabled(false)
                setWordwrap(false)

                subscribeAlways(ContentChangeEvent::class.java) { event ->
                    if (state.applyingExternalText) return@subscribeAlways
                    val cursor = when (event.action) {
                        ContentChangeEvent.ACTION_DELETE -> event.changeStart.index
                        else -> event.changeEnd.index
                    }
                    val text = this.text.toString()
                    state.lastSynced = text
                    viewModel.updateFocusedViewport(viewportId)
                    viewModel.updateEditorText(viewportId, TextFieldValue(text, TextRange(cursor)), smartIndent = false)
                }
                subscribeAlways(SelectionChangeEvent::class.java) { event ->
                    if (state.applyingExternalText) return@subscribeAlways
                    if (event.cause == SelectionChangeEvent.CAUSE_TEXT_MODIFICATION) return@subscribeAlways
                    viewModel.updateFocusedViewport(viewportId)
                    viewModel.updateEditorSelection(viewportId, event.left.index)
                }
            }
        },
        update = { editor ->
            // Only on change: setting the size rebuilds the whole layout, and update runs on
            // every keystroke.
            if (state.fontSize != editorFontSize) {
                state.fontSize = editorFontSize
                editor.setTextSize(editorFontSize.toFloat())
            }
            if (state.lineNumbers != showLineNumbers) {
                state.lineNumbers = showLineNumbers
                editor.setLineNumberEnabled(showLineNumbers)
            }

            // A different file in this viewport: new text and language.
            if (state.filePath != tab.file.absolutePath) {
                state.filePath = tab.file.absolutePath
                editor.setEditorLanguage(TextMateSetup.languageFor(tab.file.extension))
                state.setText(editor, tab.text)
            } else if (tab.text.text != state.lastSynced) {
                // Changed outside the editor (a completion was applied, file reloaded).
                state.applyExternalEdit(editor, tab.text)
            }

            if (state.diagnostics !== diagnostics) {
                state.diagnostics = diagnostics
                editor.setDiagnostics(toDiagnostics(editor, diagnostics))
            }
        },
        onRelease = { it.release() },
    )
}

private class SoraState {
    var filePath: String? = null
    /** The text last exchanged with the ViewModel, to tell our own edits from outside ones. */
    var lastSynced: String? = null
    var applyingExternalText = false
    var diagnostics: List<Diagnostic>? = null
    var fontSize: Int? = null
    var lineNumbers: Boolean? = null

    /** Loads a file's text (new tab or file). Resets scroll and undo, which is right here. */
    fun setText(editor: SoraEditor, value: TextFieldValue) {
        applyingExternalText = true
        try {
            editor.setText(value.text)
            lastSynced = value.text
            moveCursor(editor, value)
        } finally {
            applyingExternalText = false
        }
    }

    /**
     * Applies a change made outside the editor (e.g. an accepted completion) as a single
     * replace of the part that differs, so scroll position and undo history are kept.
     */
    fun applyExternalEdit(editor: SoraEditor, value: TextFieldValue) {
        applyingExternalText = true
        try {
            val old = editor.text.toString()
            val new = value.text
            var prefix = 0
            val maxPrefix = minOf(old.length, new.length)
            while (prefix < maxPrefix && old[prefix] == new[prefix]) prefix++
            var suffix = 0
            val maxSuffix = minOf(old.length, new.length) - prefix
            while (suffix < maxSuffix && old[old.length - 1 - suffix] == new[new.length - 1 - suffix]) suffix++
            editor.text.replace(prefix, old.length - suffix, new.substring(prefix, new.length - suffix))
            lastSynced = new
            moveCursor(editor, value)
        } finally {
            applyingExternalText = false
        }
    }

    private fun moveCursor(editor: SoraEditor, value: TextFieldValue) {
        val offset = value.selection.start.coerceIn(0, value.text.length)
        val pos = editor.text.indexer.getCharPosition(offset)
        editor.setSelection(pos.line, pos.column)
    }
}

private fun toDiagnostics(editor: SoraEditor, diagnostics: List<Diagnostic>): DiagnosticsContainer {
    val container = DiagnosticsContainer()
    val content = editor.text
    for (d in diagnostics) {
        try {
            val lastLine = content.lineCount - 1
            val startLine = d.range.start.line.coerceIn(0, lastLine)
            val endLine = d.range.end.line.coerceIn(0, lastLine)
            val start = content.getCharIndex(startLine, d.range.start.character.coerceIn(0, content.getColumnCount(startLine)))
            var end = content.getCharIndex(endLine, d.range.end.character.coerceIn(0, content.getColumnCount(endLine)))
            if (end <= start) end = (start + 1).coerceAtMost(content.length)
            val severity = when (d.severity ?: 1) {
                1 -> DiagnosticRegion.SEVERITY_ERROR
                2 -> DiagnosticRegion.SEVERITY_WARNING
                else -> DiagnosticRegion.SEVERITY_TYPO
            }
            container.addDiagnostic(DiagnosticRegion(start, end, severity))
        } catch (e: Exception) {
            Log.w("SoraCodeEditor", "Skipping diagnostic outside the text: ${d.message}", e)
        }
    }
    return container
}

/** Loads the bundled grammars and theme once per process. */
private object TextMateSetup {
    private const val THEME = "textmate/themes/dark_modern.json"
    @Volatile private var ready = false

    private val scopes = mapOf(
        "js" to "source.js", "mjs" to "source.js", "cjs" to "source.js", "jsx" to "source.js",
        "ts" to "source.ts", "mts" to "source.ts", "cts" to "source.ts", "tsx" to "source.tsx",
        "html" to "text.html.basic", "htm" to "text.html.basic",
        "css" to "source.css",
        "json" to "source.json", "jsonc" to "source.json",
        "md" to "text.html.markdown", "markdown" to "text.html.markdown",
        "py" to "source.python", "pyw" to "source.python",
        "c" to "source.c", "h" to "source.c",
        "cpp" to "source.cpp", "cc" to "source.cpp", "cxx" to "source.cpp",
        "hpp" to "source.cpp", "hh" to "source.cpp", "hxx" to "source.cpp",
        "rs" to "source.rust",
        "sh" to "source.shell", "bash" to "source.shell", "zsh" to "source.shell",
        "java" to "source.java",
        "yaml" to "source.yaml", "yml" to "source.yaml",
    )

    @Synchronized
    fun ensure(context: Context) {
        if (ready) return
        try {
            FileProviderRegistry.getInstance().addFileProvider(AssetsFileResolver(context.applicationContext.assets))
            val themes = ThemeRegistry.getInstance()
            val stream = FileProviderRegistry.getInstance().tryGetInputStream(THEME)
                ?: error("$THEME missing from assets")
            themes.loadTheme(ThemeModel(IThemeSource.fromInputStream(stream, THEME, null), "dark_modern"))
            themes.setTheme("dark_modern")
            GrammarRegistry.getInstance().loadGrammars("textmate/languages.json")
            ready = true
        } catch (e: Exception) {
            Log.e("SoraCodeEditor", "TextMate setup failed; files open without highlighting", e)
        }
    }

    fun languageFor(extension: String) =
        scopes[extension.lowercase()]?.takeIf { ready }?.let { scope ->
            try {
                TextMateLanguage.create(scope, false)
            } catch (e: Exception) {
                Log.e("SoraCodeEditor", "No grammar for $scope", e)
                null
            }
        } ?: EmptyLanguage()
}
