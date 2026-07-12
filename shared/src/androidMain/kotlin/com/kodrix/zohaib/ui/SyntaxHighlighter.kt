package com.kodrix.zohaib.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import com.kodrix.zohaib.lsp.Diagnostic

class SyntaxHighlighter(val extension: String) {
    fun format(text: String): AnnotatedString {
        return when (extension.lowercase()) {
            "md", "markdown" -> formatMarkdown(text)
            "kt", "kts", "java" -> formatCode(text, KOTLIN_KEYWORDS)
            "js", "ts", "json" -> formatCode(text, JS_KEYWORDS)
            "c", "h", "cpp", "cc", "cxx", "hpp" -> formatCode(text, C_CPP_KEYWORDS)
            "py", "pyw" -> formatPython(text)
            else -> AnnotatedString(text)
        }
    }

    private fun formatMarkdown(text: String): AnnotatedString {
        val builder = AnnotatedString.Builder(text)
        
        var lineStart = 0
        val lines = text.split("\n")
        
        val boldRegex = Regex("\\*\\*(.*?)\\*\\*")
        val codeRegex = Regex("`(.*?)`")
        val linkRegex = Regex("\\[(.*?)\\]\\((.*?)\\)")
        
        for (line in lines) {
            val lineLength = line.length
            
            if (line.startsWith("#")) {
                val hashes = line.takeWhile { it == '#' }.length
                val color = when(hashes) {
                    1 -> Color(0xFF58A6FF) // Blue
                    2 -> Color(0xFF79C0FF) // Light Blue
                    else -> Color(0xFFA5D6FF)
                }
                builder.addStyle(SpanStyle(color = color, fontWeight = FontWeight.Bold), lineStart, lineStart + lineLength)
            } else if (line.startsWith(">")) {
                builder.addStyle(SpanStyle(color = Color(0xFF8B949E)), lineStart, lineStart + lineLength)
            } else if (line.trim().startsWith("-") || line.trim().startsWith("*")) {
                builder.addStyle(SpanStyle(color = Color(0xFFFFA657)), lineStart, lineStart + lineLength)
            } else {
                boldRegex.findAll(line).forEach { 
                    builder.addStyle(SpanStyle(fontWeight = FontWeight.Bold, color = Color.White), lineStart + it.range.first, lineStart + it.range.last + 1)
                }
                codeRegex.findAll(line).forEach { 
                    builder.addStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = Color(0xFF30363D), color = Color(0xFFE6EDF3)), lineStart + it.range.first, lineStart + it.range.last + 1)
                }
                linkRegex.findAll(line).forEach {
                    builder.addStyle(SpanStyle(color = Color(0xFF58A6FF)), lineStart + it.range.first, lineStart + it.range.last + 1)
                }
            }
            lineStart += lineLength + 1 // +1 for the newline
        }
        
        return builder.toAnnotatedString()
    }

    private fun formatCode(text: String, keywords: Set<String>): AnnotatedString {
        val builder = AnnotatedString.Builder(text)
        
        // Keywords
        val wordRegex = Regex("\\b(\\w+)\\b")
        wordRegex.findAll(text).forEach { 
            if (it.value in keywords) {
                builder.addStyle(SpanStyle(color = Color(0xFFFF7B72)), it.range.first, it.range.last + 1)
            }
        }
        
        // Strings
        val stringRegex = Regex("\"(.*?)\"|'(.*?)'")
        stringRegex.findAll(text).forEach {
            builder.addStyle(SpanStyle(color = Color(0xFFA5D6FF)), it.range.first, it.range.last + 1)
        }
        
        // Comments
        val commentRegex = Regex("//.*|/\\*[\\s\\S]*?\\*/")
        commentRegex.findAll(text).forEach {
            builder.addStyle(SpanStyle(color = Color(0xFF8B949E)), it.range.first, it.range.last + 1)
        }
        
        // Numbers
        val numRegex = Regex("\\b\\d+\\b")
        numRegex.findAll(text).forEach {
            builder.addStyle(SpanStyle(color = Color(0xFF79C0FF)), it.range.first, it.range.last + 1)
        }

        // Functions
        val funcRegex = Regex("\\b(\\w+)(?=\\s*\\()")
        funcRegex.findAll(text).forEach {
            builder.addStyle(SpanStyle(color = Color(0xFFD2A8FF)), it.range.first, it.range.last + 1)
        }

        return builder.toAnnotatedString()
    }

    private fun formatPython(text: String): AnnotatedString {
        val builder = AnnotatedString.Builder(text)
        // Keywords
        val wordRegex = Regex("""\b(\w+)\b""")
        wordRegex.findAll(text).forEach {
            if (it.value in PYTHON_KEYWORDS)
                builder.addStyle(SpanStyle(color = Color(0xFFFF7B72)), it.range.first, it.range.last + 1)
        }
        // Strings (single + double quoted, single line)
        val stringRegex = Regex("\"(.*?)\"|'(.*?)'")
        stringRegex.findAll(text).forEach {
            builder.addStyle(SpanStyle(color = Color(0xFFA5D6FF)), it.range.first, it.range.last + 1)
        }
        // Triple-quoted strings
        val tripleRegex = Regex("\"\"\"[\\s\\S]*?\"\"\"|'''[\\s\\S]*?'''")
        tripleRegex.findAll(text).forEach {
            builder.addStyle(SpanStyle(color = Color(0xFFA5D6FF)), it.range.first, it.range.last + 1)
        }
        // Comments (#)
        val commentRegex = Regex("#.*")
        commentRegex.findAll(text).forEach {
            builder.addStyle(SpanStyle(color = Color(0xFF8B949E)), it.range.first, it.range.last + 1)
        }
        // Numbers
        val numRegex = Regex("""\b\d+\.?\d*\b""")
        numRegex.findAll(text).forEach {
            builder.addStyle(SpanStyle(color = Color(0xFF79C0FF)), it.range.first, it.range.last + 1)
        }
        // Function calls
        val funcRegex = Regex("""\b(\w+)(?=\s*\()""")
        funcRegex.findAll(text).forEach {
            builder.addStyle(SpanStyle(color = Color(0xFFD2A8FF)), it.range.first, it.range.last + 1)
        }
        return builder.toAnnotatedString()
    }

    companion object {
        val KOTLIN_KEYWORDS = setOf(
            "package", "import", "class", "interface", "fun", "val", "var", "if", "else", "for", "while", "when", 
            "return", "try", "catch", "finally", "throw", "object", "companion", "private", "public", "protected", 
            "internal", "override", "data", "sealed", "enum", "typealias", "it", "this", "super", "null", "true", "false"
        )
        val JS_KEYWORDS = setOf(
            "import", "from", "export", "default", "class", "function", "const", "let", "var", "if", "else", "for", 
            "while", "switch", "case", "break", "continue", "return", "try", "catch", "finally", "throw", "new", 
            "this", "super", "null", "true", "false", "undefined", "async", "await", "yield", "static"
        )
        val C_CPP_KEYWORDS = setOf(
            "auto", "break", "case", "char", "const", "continue", "default", "do", "double", "else",
            "enum", "extern", "float", "for", "goto", "if", "inline", "int", "long", "register",
            "return", "short", "signed", "sizeof", "static", "struct", "switch", "typedef", "union",
            "unsigned", "void", "volatile", "while",
            // C++ extras
            "class", "namespace", "template", "typename", "public", "private", "protected",
            "virtual", "override", "final", "new", "delete", "this", "true", "false", "nullptr",
            "bool", "catch", "throw", "try", "using", "operator", "friend", "explicit",
            "constexpr", "noexcept", "decltype", "auto", "static_cast", "dynamic_cast",
            "reinterpret_cast", "const_cast", "include", "define", "ifdef", "ifndef", "endif",
            "pragma", "std", "string", "vector", "map", "set", "list", "pair", "cout", "cin", "endl"
        )
        val PYTHON_KEYWORDS = setOf(
            "False", "None", "True", "and", "as", "assert", "async", "await", "break", "class",
            "continue", "def", "del", "elif", "else", "except", "finally", "for", "from",
            "global", "if", "import", "in", "is", "lambda", "nonlocal", "not", "or", "pass",
            "raise", "return", "try", "while", "with", "yield",
            "print", "len", "range", "type", "isinstance", "str", "int", "float", "list",
            "dict", "set", "tuple", "bool", "open", "super", "self", "cls", "object"
        )
    }
}

class SyntaxVisualTransformation(
    val extension: String,
    val diagnostics: List<Diagnostic>
) : VisualTransformation {
    private val highlighter = SyntaxHighlighter(extension)
    override fun filter(text: AnnotatedString): TransformedText {
        val formatted = highlighter.format(text.text)
        if (diagnostics.isEmpty()) {
            return TransformedText(formatted, OffsetMapping.Identity)
        }
        val builder = AnnotatedString.Builder(formatted)
        diagnostics.forEach { diag ->
            val start = getOffsetAt(text.text, diag.range.start.line, diag.range.start.character)
            val end = getOffsetAt(text.text, diag.range.end.line, diag.range.end.character)
            if (start < end && start in 0..text.text.length && end in 0..text.text.length) {
                val isError = (diag.severity ?: 1) == 1
                val color = if (isError) Color(0xFFF85149) else Color(0xFFD29922)
                builder.addStyle(
                    SpanStyle(
                        color = color,
                        textDecoration = TextDecoration.Underline,
                        fontWeight = FontWeight.Bold
                    ),
                    start,
                    end
                )
            }
        }
        return TransformedText(builder.toAnnotatedString(), OffsetMapping.Identity)
    }

    private fun getOffsetAt(text: String, line: Int, character: Int): Int {
        var curLine = 0
        var curChar = 0
        for (i in 0 until text.length) {
            if (curLine == line && curChar == character) {
                return i
            }
            if (text[i] == '\n') {
                curLine++
                curChar = 0
            } else {
                curChar++
            }
        }
        return text.length
    }
}
