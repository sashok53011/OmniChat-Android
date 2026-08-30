package com.example.bluetooth

import android.graphics.Bitmap
import android.util.Base64
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object BtProtocol {

    const val DELIMITER = "\n---BT_MSG_END---\n"

    data class BtMessage(
        val type: String,           // "text" or "image"
        val content: String,        // text content or base64 image
        val timestamp: String,
        val page: Int = 1,
        val totalPages: Int = 1,
        val width: Int = 0,
        val height: Int = 0,
        val sender: String = "OmniChat AI"
    )

    fun createTextMessage(text: String, config: BtDeviceConfig): BtMessage {
        val ts = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(Date())
        val processedText = formatTextForDevice(text, config)
        return BtMessage(
            type = "text",
            content = config.prefix + processedText,
            timestamp = ts
        )
    }

    fun createImageMessage(bitmap: Bitmap, page: Int, totalPages: Int, config: BtDeviceConfig): BtMessage {
        val ts = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(Date())
        val res = BtDeviceConfig.parseResolution(config.resolution)
        val baos = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 85, baos)
        val base64 = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
        return BtMessage(
            type = "image",
            content = base64,
            timestamp = ts,
            page = page,
            totalPages = totalPages,
            width = res.first,
            height = res.second
        )
    }

    fun serializeTextMessage(msg: BtMessage): ByteArray {
        val header = JSONObject()
        header.put("type", msg.type)
        header.put("timestamp", msg.timestamp)
        header.put("sender", msg.sender)
        header.put("page", msg.page)
        header.put("totalPages", msg.totalPages)
        header.put("width", msg.width)
        header.put("height", msg.height)
        header.put("contentLength", msg.content.length)

        val packet = header.toString() + "\n" + msg.content + DELIMITER
        return packet.toByteArray(Charsets.UTF_8)
    }

    fun serializeImageMessage(msg: BtMessage): ByteArray {
        val header = JSONObject()
        header.put("type", msg.type)
        header.put("timestamp", msg.timestamp)
        header.put("sender", msg.sender)
        header.put("page", msg.page)
        header.put("totalPages", msg.totalPages)
        header.put("width", msg.width)
        header.put("height", msg.height)
        header.put("contentLength", msg.content.length)

        val packet = header.toString() + "\n" + msg.content + DELIMITER
        return packet.toByteArray(Charsets.UTF_8)
    }

    fun parseIncomingMessage(data: String): BtMessage? {
        return try {
            val parts = data.split("\n", limit = 2)
            if (parts.size < 2) return null
            val header = JSONObject(parts[0])
            val content = parts[1].removeSuffix(DELIMITER).trim()

            BtMessage(
                type = header.optString("type", "text"),
                content = content,
                timestamp = header.optString("timestamp", ""),
                page = header.optInt("page", 1),
                totalPages = header.optInt("totalPages", 1),
                width = header.optInt("width", 0),
                height = header.optInt("height", 0),
                sender = header.optString("sender", "Unknown")
            )
        } catch (e: Exception) {
            null
        }
    }

    fun formatTextForDevice(text: String, config: BtDeviceConfig): String {
        if (config.deviceType == "android") return text

        var result = text

        // Strip math formulas
        result = result.replace(Regex("""\$\$[\s\S]*?\$\$"""), "[math]")
        result = result.replace(Regex("""\$[^$]+\$"""), "[math]")

        // Strip headers
        result = result.replace(Regex("""^#{1,6}\s*""", RegexOption.MULTILINE), "")

        // Strip bold
        result = result.replace(Regex("""\*\*(.+?)\*\*"""), "$1")

        // Strip italic
        result = result.replace(Regex("""\*(.+?)\*"""), "$1")

        // Strip inline code
        result = result.replace(Regex("""`(.+?)`"""), "$1")

        // Strip code blocks
        result = result.replace(Regex("""```[\s\S]*?```"""), "[code block]")

        // Strip blockquotes
        result = result.replace(Regex("""^>\s*""", RegexOption.MULTILINE), "")

        // Strip links
        result = result.replace(Regex("""\[([^\]]+)\]\([^)]+\)"""), "$1")

        // Strip images
        result = result.replace(Regex("""!\[([^\]]*)\]\([^)]+\)"""), "[image: $1]")

        // Clean up multiple blank lines
        result = result.replace(Regex("""\n{3,}"""), "\n\n")

        return result.trim()
    }
}
