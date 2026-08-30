package com.example.bluetooth

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint

object BtImageRenderer {

    data class RenderLine(
        val text: String,
        val isBold: Boolean = false,
        val isCode: Boolean = false,
        val isHeader: Boolean = false,
        val isBullet: Boolean = false,
        val indentLevel: Int = 0
    )

    fun renderPages(
        markdownText: String,
        config: BtDeviceConfig
    ): List<Bitmap> {
        val res = BtDeviceConfig.parseResolution(config.resolution)
        val width = res.first
        val height = res.second

        val bgColor = Color.parseColor(config.bgColor)
        val textColor = Color.parseColor(config.textColor)
        val codeBg = Color.parseColor(config.codeBgColor)
        val codeText = Color.parseColor(config.codeTextColor)

        val lines = parseMarkdownToLines(markdownText)

        val padding = 16
        val lineHeight = (config.fontSize * 1.4).toInt()
        val maxLinesPerPage = (height - padding * 2) / lineHeight

        val pages = mutableListOf<Bitmap>()
        var currentLineIndex = 0

        while (currentLineIndex < lines.size) {
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            canvas.drawColor(bgColor)

            val textPaint = TextPaint().apply {
                color = textColor
                textSize = config.fontSize.toFloat()
                isAntiAlias = true
                typeface = Typeface.DEFAULT
            }

            val boldPaint = TextPaint(textPaint).apply {
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            }

            val codePaint = TextPaint(textPaint).apply {
                typeface = Typeface.MONOSPACE
                color = codeText
                textSize = (config.fontSize * 0.9).toFloat()
            }

            val headerPaint = TextPaint(boldPaint).apply {
                textSize = (config.fontSize * 1.5).toFloat()
            }

            var y = padding.toFloat()
            var linesOnThisPage = 0

            while (currentLineIndex < lines.size && linesOnThisPage < maxLinesPerPage) {
                val line = lines[currentLineIndex]

                when {
                    line.isHeader -> {
                        y += 8
                        canvas.drawText(line.text, padding.toFloat(), y + headerPaint.textSize, headerPaint)
                        y += headerPaint.textSize + 8
                        linesOnThisPage += 2
                    }
                    line.isCode -> {
                        val codeBgPaint = Paint().apply { color = codeBg }
                        val codeLines = line.text.split("\n")
                        val codeHeight = codeLines.size * lineHeight + 12
                        canvas.drawRect(
                            padding.toFloat() - 4,
                            y,
                            (width - padding).toFloat(),
                            y + codeHeight,
                            codeBgPaint
                        )
                        y += 6
                        for (codeLine in codeLines) {
                            canvas.drawText(codeLine, padding.toFloat(), y + codePaint.textSize, codePaint)
                            y += lineHeight
                        }
                        y += 6
                        linesOnThisPage += codeLines.size + 2
                    }
                    line.isBullet -> {
                        val bulletText = "  " + line.text
                        canvas.drawText("\u2022", padding.toFloat(), y + textPaint.textSize, textPaint)
                        canvas.drawText(bulletText, padding.toFloat() + 20, y + textPaint.textSize, textPaint)
                        y += lineHeight
                        linesOnThisPage++
                    }
                    line.isBold -> {
                        canvas.drawText(line.text, padding.toFloat(), y + boldPaint.textSize, boldPaint)
                        y += lineHeight
                        linesOnThisPage++
                    }
                    else -> {
                        val indent = padding + line.indentLevel * 20
                        canvas.drawText(line.text, indent.toFloat(), y + textPaint.textSize, textPaint)
                        y += lineHeight
                        linesOnThisPage++
                    }
                }

                currentLineIndex++
            }

            // Draw page number
            val pagePaint = TextPaint().apply {
                color = textColor
                textSize = (config.fontSize * 0.7).toFloat()
                alpha = 128
            }
            val pageInfo = "${(pages.size + 1)}"
            canvas.drawText(pageInfo, (width - padding - 30).toFloat(), (height - 8).toFloat(), pagePaint)

            pages.add(bitmap)
        }

        return if (pages.isEmpty()) {
            val emptyBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val emptyCanvas = Canvas(emptyBitmap)
            emptyCanvas.drawColor(bgColor)
            val emptyPaint = TextPaint().apply {
                color = textColor
                textSize = config.fontSize.toFloat()
                isAntiAlias = true
            }
            emptyCanvas.drawText("(empty)", padding.toFloat(), (height / 2).toFloat(), emptyPaint)
            listOf(emptyBitmap)
        } else {
            pages
        }
    }

    private fun parseMarkdownToLines(markdown: String): List<RenderLine> {
        val lines = mutableListOf<RenderLine>()
        val inputLines = markdown.split("\n")
        var inCodeBlock = false
        val codeBuffer = StringBuilder()

        for (line in inputLines) {
            if (line.trimStart().startsWith("```")) {
                if (inCodeBlock) {
                    lines.add(RenderLine(text = codeBuffer.toString().trimEnd(), isCode = true))
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

            val trimmed = line.trim()
            if (trimmed.isEmpty()) {
                lines.add(RenderLine(text = ""))
                continue
            }

            when {
                trimmed.startsWith("### ") -> {
                    lines.add(RenderLine(text = trimmed.removePrefix("### "), isHeader = true))
                }
                trimmed.startsWith("## ") -> {
                    lines.add(RenderLine(text = trimmed.removePrefix("## "), isHeader = true))
                }
                trimmed.startsWith("# ") -> {
                    lines.add(RenderLine(text = trimmed.removePrefix("# "), isHeader = true))
                }
                trimmed.startsWith("- ") || trimmed.startsWith("* ") -> {
                    val indent = (line.length - line.trimStart().length) / 2
                    lines.add(RenderLine(text = trimmed.removePrefix("- ").removePrefix("* "), isBullet = true, indentLevel = indent))
                }
                trimmed.startsWith("> ") -> {
                    lines.add(RenderLine(text = "\u00AB ${trimmed.removePrefix("> ")} \u00BB", indentLevel = 1))
                }
                trimmed.startsWith("**") && trimmed.endsWith("**") -> {
                    lines.add(RenderLine(text = trimmed.removeSurrounding("**"), isBold = true))
                }
                else -> {
                    val cleanLine = trimmed
                        .replace(Regex("""\*\*(.+?)\*\*"""), "$1")
                        .replace(Regex("""\*(.+?)\*"""), "$1")
                        .replace(Regex("""`(.+?)`"""), "$1")
                    lines.add(RenderLine(text = cleanLine))
                }
            }
        }

        if (inCodeBlock && codeBuffer.isNotEmpty()) {
            lines.add(RenderLine(text = codeBuffer.toString().trimEnd(), isCode = true))
        }

        return lines
    }
}
