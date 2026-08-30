package com.example.companion

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun MarkdownText(text: String, modifier: Modifier = Modifier, baseFontSize: TextUnit = 13.sp) {
    val lines = text.split("\n")
    var inCodeBlock = false
    val codeBuffer = StringBuilder()

    Column(modifier = modifier) {
        for (line in lines) {
            val trimmed = line.trim()

            // Code block detection
            if (trimmed.startsWith("```")) {
                if (inCodeBlock) {
                    // End of code block
                    CodeBlock(codeBuffer.toString().trimEnd(), baseFontSize)
                    codeBuffer.clear()
                    inCodeBlock = false
                } else {
                    // Start of code block
                    inCodeBlock = true
                }
                continue
            }

            if (inCodeBlock) {
                codeBuffer.appendLine(line)
                continue
            }

            // Skip empty lines
            if (trimmed.isEmpty()) {
                Spacer(modifier = Modifier.height(4.dp))
                continue
            }

            // Headers
            when {
                trimmed.startsWith("### ") -> {
                    Text(
                        text = trimmed.removePrefix("### "),
                        fontSize = baseFontSize * 1.3f,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
                trimmed.startsWith("## ") -> {
                    Text(
                        text = trimmed.removePrefix("## "),
                        fontSize = baseFontSize * 1.5f,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(top = 10.dp)
                    )
                }
                trimmed.startsWith("# ") -> {
                    Text(
                        text = trimmed.removePrefix("# "),
                        fontSize = baseFontSize * 1.7f,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(top = 12.dp)
                    )
                }
                // Bullet lists
                trimmed.startsWith("- ") || trimmed.startsWith("* ") -> {
                    Row(modifier = Modifier.padding(start = 8.dp)) {
                        Text("• ", fontSize = baseFontSize, color = MaterialTheme.colorScheme.onSurface)
                        RichText(trimmed.removePrefix("- ").removePrefix("* "), baseFontSize)
                    }
                }
                // Blockquotes
                trimmed.startsWith("> ") -> {
                    Box(
                        modifier = Modifier
                            .padding(start = 8.dp, top = 4.dp, bottom = 4.dp)
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f))
                            .padding(start = 12.dp, top = 4.dp, bottom = 4.dp, end = 4.dp)
                    ) {
                        Text(
                            text = trimmed.removePrefix("> "),
                            fontSize = baseFontSize * 0.9f,
                            fontStyle = FontStyle.Italic,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                        )
                    }
                }
                // Normal text with inline formatting
                else -> {
                    RichText(trimmed, baseFontSize)
                }
            }
        }

        // Handle unclosed code block
        if (inCodeBlock && codeBuffer.isNotEmpty()) {
            CodeBlock(codeBuffer.toString().trimEnd(), baseFontSize)
        }
    }
}

@Composable
fun RichText(text: String, baseFontSize: TextUnit = 13.sp) {
    val annotatedString = buildAnnotatedString {
        var remaining = text
        while (remaining.isNotEmpty()) {
            // Bold: **text**
            val boldStart = remaining.indexOf("**")
            if (boldStart >= 0) {
                val boldEnd = remaining.indexOf("**", boldStart + 2)
                if (boldEnd > boldStart) {
                    // Text before bold
                    if (boldStart > 0) {
                        append(remaining.substring(0, boldStart))
                    }
                    // Bold text
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                        append(remaining.substring(boldStart + 2, boldEnd))
                    }
                    remaining = remaining.substring(boldEnd + 2)
                    continue
                }
            }

            // Italic: *text*
            val italicStart = remaining.indexOf("*")
            if (italicStart >= 0 && (italicStart == 0 || remaining[italicStart - 1] != '*')) {
                val italicEnd = remaining.indexOf("*", italicStart + 1)
                if (italicEnd > italicStart) {
                    // Text before italic
                    if (italicStart > 0) {
                        append(remaining.substring(0, italicStart))
                    }
                    // Italic text
                    withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                        append(remaining.substring(italicStart + 1, italicEnd))
                    }
                    remaining = remaining.substring(italicEnd + 1)
                    continue
                }
            }

            // Inline code: `text`
            val codeStart = remaining.indexOf("`")
            if (codeStart >= 0) {
                val codeEnd = remaining.indexOf("`", codeStart + 1)
                if (codeEnd > codeStart) {
                    // Text before code
                    if (codeStart > 0) {
                        append(remaining.substring(0, codeStart))
                    }
                    // Code text
                    withStyle(SpanStyle(fontFamily = FontFamily.Monospace, fontSize = baseFontSize * 0.9f)) {
                        append(remaining.substring(codeStart + 1, codeEnd))
                    }
                    remaining = remaining.substring(codeEnd + 1)
                    continue
                }
            }

            // No more formatting found
            append(remaining)
            break
        }
    }

    Text(
        text = annotatedString,
        fontSize = baseFontSize,
        color = MaterialTheme.colorScheme.onSurface
    )
}

@Composable
fun CodeBlock(code: String, baseFontSize: TextUnit = 13.sp) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .background(Color(0xFF1E1E1E))
            .padding(8.dp)
    ) {
        Text(
            text = code,
            fontSize = baseFontSize * 0.85f,
            fontFamily = FontFamily.Monospace,
            color = Color(0xFFD4D4D4)
        )
    }
}
