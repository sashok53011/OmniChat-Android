package com.example.bluetooth

import org.json.JSONObject

data class BtDeviceConfig(
    val deviceType: String = "android",       // "android" (companion) or "display" (image) or "esp32"
    val connectionType: String = "bluetooth", // "bluetooth" or "wifi"
    val ipAddress: String = "",               // IP address for WiFi devices
    val resolution: String = "800x480",       // display resolution WxH
    val fontSize: Int = 14,                   // font size in px for display mode
    val bgColor: String = "#FFFFFF",          // background color for display
    val textColor: String = "#000000",        // text color for display
    val codeBgColor: String = "#1E1E1E",      // code block background for display
    val codeTextColor: String = "#D4D4D4",    // code text color for display
    val prefix: String = "",                  // custom prefix before message
    val maxLength: Int = 4000,                // max characters to send
    val name: String = ""                     // friendly display name
) {
    fun toJson(): String {
        val obj = JSONObject()
        obj.put("deviceType", deviceType)
        obj.put("connectionType", connectionType)
        obj.put("ipAddress", ipAddress)
        obj.put("resolution", resolution)
        obj.put("fontSize", fontSize)
        obj.put("bgColor", bgColor)
        obj.put("textColor", textColor)
        obj.put("codeBgColor", codeBgColor)
        obj.put("codeTextColor", codeTextColor)
        obj.put("prefix", prefix)
        obj.put("maxLength", maxLength)
        obj.put("name", name)
        return obj.toString()
    }

    companion object {
        fun fromJson(json: String): BtDeviceConfig {
            return try {
                val obj = JSONObject(json)
                BtDeviceConfig(
                    deviceType = obj.optString("deviceType", "android"),
                    connectionType = obj.optString("connectionType", "bluetooth"),
                    ipAddress = obj.optString("ipAddress", ""),
                    resolution = obj.optString("resolution", "800x480"),
                    fontSize = obj.optInt("fontSize", 14),
                    bgColor = obj.optString("bgColor", "#FFFFFF"),
                    textColor = obj.optString("textColor", "#000000"),
                    codeBgColor = obj.optString("codeBgColor", "#1E1E1E"),
                    codeTextColor = obj.optString("codeTextColor", "#D4D4D4"),
                    prefix = obj.optString("prefix", ""),
                    maxLength = obj.optInt("maxLength", 4000),
                    name = obj.optString("name", "")
                )
            } catch (e: Exception) {
                BtDeviceConfig()
            }
        }

        fun defaultConfig() = BtDeviceConfig()

        fun parseResolution(resolution: String): Pair<Int, Int> {
            val parts = resolution.split("x")
            val w = parts.getOrNull(0)?.toIntOrNull() ?: 800
            val h = parts.getOrNull(1)?.toIntOrNull() ?: 480
            return Pair(w, h)
        }
    }
}
