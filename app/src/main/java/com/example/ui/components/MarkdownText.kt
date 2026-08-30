package com.example.ui.components

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
sealed class MarkdownElement {
    data class TextBlock(val content: String) : MarkdownElement()
    data class CodeBlock(val code: String, val language: String?) : MarkdownElement()
}

/**
 * Enhanced custom inline markdown parser that translates inline symbols like **bold**, *italic*, and `code`
 * into Jetpack Compose AnnotatedStrings with precise Material 3 styles.
 */
fun renderMarkdownText(text: String, baseColor: Color, primaryColor: Color): AnnotatedString {
    return buildAnnotatedString {
        var i = 0
        while (i < text.length) {
            // Bold **text**
            if (text.startsWith("**", i)) {
                val end = text.indexOf("**", i + 2)
                if (end != -1) {
                    pushStyle(SpanStyle(fontWeight = FontWeight.Bold, color = baseColor))
                    append(text.substring(i + 2, end))
                    pop()
                    i = end + 2
                    continue
                }
            }
            // Italic *text*
            if (text.startsWith("*", i) && !text.startsWith("**", i)) {
                val end = text.indexOf("*", i + 1)
                if (end != -1) {
                    pushStyle(SpanStyle(fontStyle = FontStyle.Italic, color = baseColor))
                    append(text.substring(i + 1, end))
                    pop()
                    i = end + 1
                    continue
                }
            }
            // Inline code `code`
            if (text.startsWith("`", i)) {
                val end = text.indexOf("`", i + 1)
                if (end != -1) {
                    pushStyle(
                        SpanStyle(
                            fontFamily = FontFamily.Monospace,
                            background = baseColor.copy(alpha = 0.08f),
                            color = primaryColor,
                            fontSize = 13.sp
                        )
                    )
                    append(text.substring(i + 1, end))
                    pop()
                    i = end + 1
                    continue
                }
            }
            // Normal character
            append(text[i].toString())
            i++
        }
    }
}

/**
 * Beautiful line-by-line renderer supporting headers, lists, blockquotes and spacing.
 */
@Composable
fun MarkdownTextBlock(text: String, baseColor: Color) {
    val lines = text.split("\n")
    val primaryColor = MaterialTheme.colorScheme.primary
    Column(modifier = Modifier.fillMaxWidth()) {
        lines.forEach { line ->
            val trimmed = line.trim()
            when {
                trimmed.startsWith("###### ") -> {
                    Text(
                        text = renderMarkdownText(trimmed.removePrefix("###### "), baseColor, primaryColor),
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold, color = primaryColor),
                        modifier = Modifier.padding(vertical = 4.dp)
                    )
                }
                trimmed.startsWith("##### ") -> {
                    Text(
                        text = renderMarkdownText(trimmed.removePrefix("##### "), baseColor, primaryColor),
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold, color = primaryColor),
                        modifier = Modifier.padding(vertical = 4.dp)
                    )
                }
                trimmed.startsWith("#### ") -> {
                    Text(
                        text = renderMarkdownText(trimmed.removePrefix("#### "), baseColor, primaryColor),
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold, color = primaryColor),
                        modifier = Modifier.padding(vertical = 4.dp)
                    )
                }
                trimmed.startsWith("### ") -> {
                    Text(
                        text = renderMarkdownText(trimmed.removePrefix("### "), baseColor, primaryColor),
                        style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold, color = primaryColor),
                        modifier = Modifier.padding(vertical = 4.dp)
                    )
                }
                trimmed.startsWith("## ") -> {
                    Text(
                        text = renderMarkdownText(trimmed.removePrefix("## "), baseColor, primaryColor),
                        style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold, color = primaryColor),
                        modifier = Modifier.padding(vertical = 6.dp)
                    )
                }
                trimmed.startsWith("# ") -> {
                    Text(
                        text = renderMarkdownText(trimmed.removePrefix("# "), baseColor, primaryColor),
                        style = MaterialTheme.typography.headlineLarge.copy(fontWeight = FontWeight.Bold, color = primaryColor),
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                }
                trimmed.startsWith("> ") -> {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .width(4.dp)
                                .height(IntrinsicSize.Min)
                                .background(primaryColor.copy(alpha = 0.5f))
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = renderMarkdownText(trimmed.removePrefix("> "), baseColor, primaryColor),
                            style = MaterialTheme.typography.bodyMedium.copy(fontStyle = FontStyle.Italic, color = baseColor.copy(alpha = 0.8f))
                        )
                    }
                }
                trimmed.startsWith("- ") || trimmed.startsWith("* ") || trimmed.startsWith("• ") -> {
                    val content = trimmed.removePrefix("- ").removePrefix("* ").removePrefix("• ")
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 8.dp, top = 2.dp, bottom = 2.dp)
                    ) {
                        Text(
                            text = "• ",
                            style = MaterialTheme.typography.bodyMedium.copy(color = primaryColor, fontWeight = FontWeight.Bold)
                        )
                        Text(
                            text = renderMarkdownText(content, baseColor, primaryColor),
                            style = MaterialTheme.typography.bodyMedium.copy(color = baseColor)
                        )
                    }
                }
                trimmed.isNotEmpty() -> {
                    Text(
                        text = renderMarkdownText(line, baseColor, primaryColor),
                        style = MaterialTheme.typography.bodyMedium.copy(color = baseColor, lineHeight = 20.sp),
                        modifier = Modifier.padding(vertical = 2.dp)
                    )
                }
                else -> {
                    Spacer(modifier = Modifier.height(4.dp))
                }
            }
        }
    }
}

/**
 * Parses a standard markdown string into a list of regular inline texts and code blocks.
 */
fun parseMarkdown(text: String): List<MarkdownElement> {
    val elements = mutableListOf<MarkdownElement>()
    val parts = text.split("```")
    for (i in parts.indices) {
        val part = parts[i]
        if (i % 2 == 1) {
            val lines = part.split("\n", limit = 2)
            val lang = lines.firstOrNull()?.trim()?.lowercase()
            val code = if (lines.size > 1) lines[1] else ""
            elements.add(
                MarkdownElement.CodeBlock(
                    code = code.trimEnd('\n'),
                    language = if (lang.isNullOrBlank()) null else lang
                )
            )
        } else {
            if (part.isNotEmpty()) {
                elements.add(MarkdownElement.TextBlock(part))
            }
        }
    }
    return elements
}

/**
 * Enhanced Markdown renderer using a robust self-contained parsing layout.
 */
@Composable
fun MarkdownContent(
    text: String,
    modifier: Modifier = Modifier,
    baseColor: Color = MaterialTheme.colorScheme.onSurface
) {
    val elements = parseMarkdown(text)
    Column(modifier = modifier) {
        elements.forEach { element ->
            when (element) {
                is MarkdownElement.TextBlock -> {
                    // Pre-process math symbols for better visibility
                    val processedText = processMathSymbols(element.content)
                    MarkdownTextBlock(text = processedText, baseColor = baseColor)
                }
                is MarkdownElement.CodeBlock -> {
                    Spacer(modifier = Modifier.height(8.dp))
                    CodeBlockItem(code = element.code, language = element.language)
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }
        }
    }
}

/**
 * Basic preprocessing of math symbols to make them stand out in the text
 * as KaTeX/LaTeX rendering is not natively available in this Compose library.
 */
fun processMathSymbols(text: String): String {
    // 1. Detect block math $$ ... $$
    var result = text.replace(Regex("\\$\\$(.*?)\\$\\$", RegexOption.DOT_MATCHES_ALL)) { match ->
        val content = match.groupValues[1].trim()
        "\n> **Math Block:**\n> *${content}*\n"
    }
    
    // 2. Detect inline math $ ... $
    result = result.replace(Regex("\\$(.*?)\\$")) { match ->
        " *${match.groupValues[1]}* "
    }
    
    // 3. Handle common LaTeX commands by making them readable
    val mathCommands = mapOf(
        "\\alpha" to "α",
        "\\beta" to "β",
        "\\gamma" to "γ",
        "\\delta" to "δ",
        "\\epsilon" to "ε",
        "\\zeta" to "ζ",
        "\\eta" to "η",
        "\\theta" to "θ",
        "\\iota" to "ι",
        "\\kappa" to "κ",
        "\\lambda" to "λ",
        "\\mu" to "μ",
        "\\nu" to "ν",
        "\\xi" to "ξ",
        "\\pi" to "π",
        "\\rho" to "ρ",
        "\\sigma" to "σ",
        "\\tau" to "τ",
        "\\phi" to "φ",
        "\\chi" to "χ",
        "\\psi" to "ψ",
        "\\omega" to "ω",
        "\\sum" to "∑",
        "\\int" to "∫",
        "\\sqrt" to "√",
        "\\infty" to "∞",
        "\\neq" to "≠",
        "\\approx" to "≈",
        "\\le" to "≤",
        "\\ge" to "≥",
        "\\pm" to "±",
        "\\times" to "×",
        "\\div" to "÷",
        "\\partial" to "∂",
        "\\nabla" to "∇",
        "\\in" to "∈",
        "\\forall" to "∀",
        "\\exists" to "∃"
    )
    
    mathCommands.forEach { (cmd, replacement) ->
        result = result.replace(cmd, replacement)
    }
    
    return result
}

/**
 * High-fidelity, direct token highlighter for code snippets.
 */
fun highlightCode(code: String): AnnotatedString {
    return buildAnnotatedString {
        var i = 0
        while (i < code.length) {
            // 1. Single-line & Multi-line Comments
            if (code.startsWith("//", i)) {
                val end = code.indexOf("\n", i)
                val commentText = if (end != -1) code.substring(i, end) else code.substring(i)
                pushStyle(SpanStyle(color = Color(0xFF7F8C8D), fontStyle = FontStyle.Italic))
                append(commentText)
                pop()
                i += commentText.length
                continue
            }
            if (code.startsWith("/*", i)) {
                val end = code.indexOf("*/", i + 2)
                val commentText = if (end != -1) code.substring(i, end + 2) else code.substring(i)
                pushStyle(SpanStyle(color = Color(0xFF7F8C8D), fontStyle = FontStyle.Italic))
                append(commentText)
                pop()
                i += commentText.length
                continue
            }
            if (code.startsWith("#", i)) {
                val end = code.indexOf("\n", i)
                val commentText = if (end != -1) code.substring(i, end) else code.substring(i)
                pushStyle(SpanStyle(color = Color(0xFF7F8C8D), fontStyle = FontStyle.Italic))
                append(commentText)
                pop()
                i += commentText.length
                continue
            }

            // 2. String literals
            if (code[i] == '"') {
                var end = i + 1
                var foundEnd = false
                while (end < code.length) {
                    if (code[end] == '"' && code[end - 1] != '\\') {
                        foundEnd = true
                        break
                    }
                    end++
                }
                val stringText = if (foundEnd) code.substring(i, end + 1) else code.substring(i)
                pushStyle(SpanStyle(color = Color(0xFF48C9B0))) // Green-ish/Cyan
                append(stringText)
                pop()
                i += stringText.length
                continue
            }
            if (code[i] == '\'') {
                var end = i + 1
                var foundEnd = false
                while (end < code.length) {
                    if (code[end] == '\'' && code[end - 1] != '\\') {
                        foundEnd = true
                        break
                    }
                    end++
                }
                val stringText = if (foundEnd) code.substring(i, end + 1) else code.substring(i)
                pushStyle(SpanStyle(color = Color(0xFF48C9B0))) // Green-ish/Cyan
                append(stringText)
                pop()
                i += stringText.length
                continue
            }

            // 3. Numbers
            if (code[i].isDigit()) {
                var end = i
                while (end < code.length && code[end].isDigit()) {
                    end++
                }
                val numText = code.substring(i, end)
                pushStyle(SpanStyle(color = Color(0xFFE59866))) // Orange-ish
                append(numText)
                pop()
                i = end
                continue
            }

            // 4. Keywords & Types
            if (code[i].isLetter() || code[i] == '_') {
                var end = i
                while (end < code.length && (code[end].isLetterOrDigit() || code[end] == '_')) {
                    end++
                }
                val wordText = code.substring(i, end)
                val keywords = setOf(
                    "fun", "val", "var", "class", "interface", "object", "import", "package", "return", "if", "else", "while", "for",
                    "when", "in", "try", "catch", "finally", "throw", "null", "true", "false", "const", "let", "function", "export",
                    "from", "def", "as", "with", "self", "public", "private", "protected", "void", "static", "new", "case", "break",
                    "continue", "switch", "default", "struct", "enum", "type", "func"
                )
                if (keywords.contains(wordText)) {
                    pushStyle(SpanStyle(color = Color(0xFFEC7063), fontWeight = FontWeight.Bold)) // Coral Red
                    append(wordText)
                    pop()
                } else if (wordText.firstOrNull()?.isUpperCase() == true) {
                    pushStyle(SpanStyle(color = Color(0xFFF4D03F))) // Soft Yellow
                    append(wordText)
                    pop()
                } else {
                    append(wordText)
                }
                i = end
                continue
            }

            // 5. Default styling
            append(code[i].toString())
            i++
        }
    }
}

@Composable
fun CodeBlockItem(code: String, language: String?) {
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current
    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF1E1E1E))
            .padding(bottom = 8.dp)
    ) {
        // Header Row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF2D2D2D))
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = (language ?: "code").uppercase(),
                color = Color(0xFFAAAAAA),
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )
            IconButton(
                onClick = {
                    clipboardManager.setText(AnnotatedString(code))
                    Toast.makeText(context, "Code copied to clipboard", Toast.LENGTH_SHORT).show()
                },
                modifier = Modifier.size(24.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.ContentCopy,
                    contentDescription = "Copy Code",
                    tint = Color(0xFFCCCCCC),
                    modifier = Modifier.size(14.dp)
                )
            }
        }

        // Code Area
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(scrollState)
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Text(
                text = highlightCode(code),
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp,
                lineHeight = 18.sp,
                color = Color(0xFFD4D4D4)
            )
        }
    }
}
