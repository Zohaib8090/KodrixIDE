package com.kodrix.zohaib.ui

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.TextUnit

@Composable
fun MarkdownText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    fontSize: TextUnit = TextUnit.Unspecified,
    fontFamily: FontFamily? = null
) {
    val annotatedString = parseMarkdown(text)
    Text(
        text = annotatedString,
        modifier = modifier,
        color = color,
        fontSize = fontSize,
        fontFamily = fontFamily
    )
}

fun parseMarkdown(text: String): AnnotatedString {
    return buildAnnotatedString {
        var currentIndex = 0
        // Match multiline code blocks, then bold, then italic, then inline code
        val pattern = Regex("```([\\s\\S]*?)```|\\*\\*(.*?)\\*\\*|\\*(.*?)\\*|`(.*?)`")
        
        val matches = pattern.findAll(text)
        for (match in matches) {
            val start = match.range.first
            if (start > currentIndex) {
                append(text.substring(currentIndex, start))
            }
            
            val value = match.value
            when {
                value.startsWith("```") -> {
                    // Remove "```language" if language is provided
                    var codeContent = value.removeSurrounding("```")
                    val firstNewline = codeContent.indexOf('\n')
                    if (firstNewline != -1 && firstNewline < 20 && !codeContent.substring(0, firstNewline).contains(" ")) {
                        codeContent = codeContent.substring(firstNewline + 1)
                    }
                    withStyle(SpanStyle(background = Color(0xFF0D1117), fontFamily = FontFamily.Monospace, color = Color(0xFF58A6FF))) {
                        append("\n" + codeContent.trimEnd() + "\n")
                    }
                }
                value.startsWith("**") -> {
                    val boldContent = value.removeSurrounding("**")
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                        append(boldContent)
                    }
                }
                value.startsWith("*") -> {
                    val italicContent = value.removeSurrounding("*")
                    withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                        append(italicContent)
                    }
                }
                value.startsWith("`") -> {
                    val inlineCodeContent = value.removeSurrounding("`")
                    withStyle(SpanStyle(background = Color(0xFF0D1117), fontFamily = FontFamily.Monospace, color = Color(0xFF58A6FF))) {
                        append(inlineCodeContent)
                    }
                }
            }
            currentIndex = match.range.last + 1
        }
        if (currentIndex < text.length) {
            append(text.substring(currentIndex))
        }
    }
}
