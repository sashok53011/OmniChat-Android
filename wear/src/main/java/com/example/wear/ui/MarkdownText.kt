package com.example.wear.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text

@Composable
fun MarkdownText(text: String, modifier: Modifier = Modifier, baseFontSize: TextUnit = 13.sp) {
    val lines = text.split("\n")
    var inCodeBlock = false
    val codeBuffer = StringBuilder()

    Column(modifier = modifier) {
        for (line in lines) {
            val trimmed = line.trim()

            if (trimmed.startsWith("```")) {
                if (inCodeBlock) {
                    CodeBlockItem(codeBuffer.toString().trimEnd(), baseFontSize)
                    codeBuffer.clear()
                    inCodeBlock = false
                } else {
                    inCodeBlock = true
                }
                continue
            }

            if (inCodeBlock) {
                codeBuffer.appendLine(line)
                continue
            }

            if (trimmed.isEmpty()) {
                Spacer(modifier = Modifier.height(4.dp))
                continue
            }

            when {
                trimmed.startsWith("### ") -> {
                    Text(
                        text = trimmed.removePrefix("### "),
                        fontSize = baseFontSize * 1.3f,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colors.onSurface,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
                trimmed.startsWith("## ") -> {
                    Text(
                        text = trimmed.removePrefix("## "),
                        fontSize = baseFontSize * 1.5f,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colors.onSurface,
                        modifier = Modifier.padding(top = 10.dp)
                    )
                }
                trimmed.startsWith("# ") -> {
                    Text(
                        text = trimmed.removePrefix("# "),
                        fontSize = baseFontSize * 1.7f,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colors.onSurface,
                        modifier = Modifier.padding(top = 12.dp)
                    )
                }
                trimmed.startsWith("- ") || trimmed.startsWith("* ") -> {
                    Row(modifier = Modifier.padding(start = 8.dp)) {
                        Text("• ", fontSize = baseFontSize, color = MaterialTheme.colors.onSurface)
                        RichText(trimmed.removePrefix("- ").removePrefix("* "), baseFontSize)
                    }
                }
                trimmed.startsWith("> ") -> {
                    Box(
                        modifier = Modifier
                            .padding(start = 8.dp, top = 4.dp, bottom = 4.dp)
                            .background(MaterialTheme.colors.primary.copy(alpha = 0.1f))
                            .padding(start = 12.dp, top = 4.dp, bottom = 4.dp, end = 4.dp)
                    ) {
                        Text(
                            text = trimmed.removePrefix("> "),
                            fontSize = baseFontSize * 0.9f,
                            fontStyle = FontStyle.Italic,
                            color = MaterialTheme.colors.onSurface.copy(alpha = 0.7f)
                        )
                    }
                }
                else -> {
                    RichText(trimmed, baseFontSize)
                }
            }
        }

        if (inCodeBlock && codeBuffer.isNotEmpty()) {
            CodeBlockItem(codeBuffer.toString().trimEnd(), baseFontSize)
        }
    }
}

@Composable
private fun RichText(text: String, baseFontSize: TextUnit = 13.sp) {
    val annotatedString = buildAnnotatedString {
        var remaining = text
        while (remaining.isNotEmpty()) {
            val boldStart = remaining.indexOf("**")
            if (boldStart >= 0) {
                val boldEnd = remaining.indexOf("**", boldStart + 2)
                if (boldEnd > boldStart) {
                    if (boldStart > 0) {
                        append(remaining.substring(0, boldStart))
                    }
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                        append(remaining.substring(boldStart + 2, boldEnd))
                    }
                    remaining = remaining.substring(boldEnd + 2)
                    continue
                }
            }

            val italicStart = remaining.indexOf("*")
            if (italicStart >= 0 && (italicStart == 0 || remaining[italicStart - 1] != '*')) {
                val italicEnd = remaining.indexOf("*", italicStart + 1)
                if (italicEnd > italicStart) {
                    if (italicStart > 0) {
                        append(remaining.substring(0, italicStart))
                    }
                    withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                        append(remaining.substring(italicStart + 1, italicEnd))
                    }
                    remaining = remaining.substring(italicEnd + 1)
                    continue
                }
            }

            val codeStart = remaining.indexOf("`")
            if (codeStart >= 0) {
                val codeEnd = remaining.indexOf("`", codeStart + 1)
                if (codeEnd > codeStart) {
                    if (codeStart > 0) {
                        append(remaining.substring(0, codeStart))
                    }
                    withStyle(SpanStyle(fontFamily = FontFamily.Monospace, fontSize = baseFontSize * 0.9f)) {
                        append(remaining.substring(codeStart + 1, codeEnd))
                    }
                    remaining = remaining.substring(codeEnd + 1)
                    continue
                }
            }

            append(remaining)
            break
        }
    }

    Text(
        text = annotatedString,
        fontSize = baseFontSize,
        color = MaterialTheme.colors.onSurface
    )
}

@Composable
private fun CodeBlockItem(code: String, baseFontSize: TextUnit = 13.sp) {
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
