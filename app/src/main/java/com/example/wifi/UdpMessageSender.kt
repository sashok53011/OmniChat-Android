package com.example.wifi

import android.graphics.Bitmap
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.NetworkInterface
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object UdpMessageSender {
    private const val TAG = "UdpMessageSender"
    private const val PORT = 12345
    private const val MAX_PACKET_SIZE = 60000

    suspend fun pingCompanion(ipAddress: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val address = InetAddress.getByName(ipAddress)
            address.isReachable(2000)
        } catch (e: Exception) {
            Log.e(TAG, "Ping failed for $ipAddress", e)
            false
        }
    }

    fun getLocalIpAddress(): String? {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                if (networkInterface.isLoopback || !networkInterface.isUp) continue
                val addresses = networkInterface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()
                    if (!address.isLoopbackAddress && address is java.net.Inet4Address) {
                        return address.hostAddress
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get local IP", e)
        }
        return null
    }

    suspend fun discoverSubnet(
        onProgress: (String) -> Unit
    ): List<String> = withContext(Dispatchers.IO) {
        val foundIps = mutableListOf<String>()

        val localIp = getLocalIpAddress()
        if (localIp == null) {
            onProgress("Cannot determine local IP")
            return@withContext emptyList()
        }

        val subnet = localIp.substringBeforeLast(".")
        onProgress("Scanning $subnet.1-254...")

        val discoveryPacket = JSONObject().apply {
            put("type", "discover")
            put("sender", "OmniChat AI")
            put("timestamp", SimpleDateFormat("HH:mm:ss", Locale.US).format(Date()))
        }.toString().toByteArray(Charsets.UTF_8)

        val semaphore = Semaphore(20)

        coroutineScope {
            val results = (1..254).map { i ->
                async {
                    val ip = "$subnet.$i"
                    if (ip == localIp) return@async null
                    semaphore.withPermit {
                        try {
                            val address = InetAddress.getByName(ip)
                            val reachable = address.isReachable(400)
                            if (reachable) {
                                val socket = DatagramSocket()
                                socket.soTimeout = 500
                                try {
                                    val packet = DatagramPacket(discoveryPacket, discoveryPacket.size, address, PORT)
                                    socket.send(packet)
                                    socket.close()
                                    ip
                                } catch (e: Exception) {
                                    socket.close()
                                    null
                                }
                            } else {
                                null
                            }
                        } catch (e: Exception) {
                            null
                        }
                    }
                }
            }.awaitAll()

            results.filterNotNull().forEach { ip ->
                if (!foundIps.contains(ip)) {
                    foundIps.add(ip)
                    onProgress("Found: $ip")
                }
            }
        }

        onProgress("Scan complete: ${foundIps.size} devices found")
        foundIps
    }

    suspend fun sendMessage(
        ipAddress: String,
        text: String,
        sender: String = "OmniChat AI",
        onResult: (Boolean, String?) -> Unit
    ) = withContext(Dispatchers.IO) {
        try {
            val json = JSONObject()
            json.put("type", "text")
            json.put("content", text)
            json.put("timestamp", SimpleDateFormat("HH:mm:ss", Locale.US).format(Date()))
            json.put("sender", sender)

            val data = json.toString().toByteArray(Charsets.UTF_8)
            val socket = DatagramSocket()
            socket.soTimeout = 3000
            val address = InetAddress.getByName(ipAddress)

            if (data.size <= MAX_PACKET_SIZE) {
                val packet = DatagramPacket(data, data.size, address, PORT)
                socket.send(packet)
            } else {
                val totalChunks = (data.size + MAX_PACKET_SIZE - 1) / MAX_PACKET_SIZE
                for (i in 0 until totalChunks) {
                    val start = i * MAX_PACKET_SIZE
                    val end = minOf(start + MAX_PACKET_SIZE, data.size)
                    val chunk = data.copyOfRange(start, end)
                    val header = "[$i/$totalChunks]:".toByteArray()
                    val packetData = header + chunk
                    val packet = DatagramPacket(packetData, packetData.size, address, PORT)
                    socket.send(packet)
                    kotlinx.coroutines.delay(10)
                }
            }

            socket.close()
            onResult(true, "Sent to $ipAddress")
        } catch (e: Exception) {
            Log.e(TAG, "UDP send failed", e)
            onResult(false, e.message)
        }
    }

    suspend fun sendImagesViaUdp(
        ipAddress: String,
        pages: List<Bitmap>,
        onResult: (Boolean, String?) -> Unit
    ) = withContext(Dispatchers.IO) {
        try {
            val socket = DatagramSocket()
            socket.soTimeout = 5000
            val address = InetAddress.getByName(ipAddress)

            for ((index, page) in pages.withIndex()) {
                // Convert bitmap to JPEG
                val stream = ByteArrayOutputStream()
                page.compress(Bitmap.CompressFormat.JPEG, 85, stream)
                val jpegBytes = stream.toByteArray()
                val base64 = Base64.encodeToString(jpegBytes, Base64.NO_WRAP)

                // Create JSON header
                val json = JSONObject()
                json.put("type", "image")
                json.put("page", index + 1)
                json.put("totalPages", pages.size)
                json.put("timestamp", SimpleDateFormat("HH:mm:ss", Locale.US).format(Date()))
                json.put("sender", "OmniChat AI")
                json.put("width", page.width)
                json.put("height", page.height)

                val headerBytes = json.toString().toByteArray(Charsets.UTF_8)
                val packetData = headerBytes + "\n".toByteArray() + base64.toByteArray(Charsets.UTF_8)

                if (packetData.size <= MAX_PACKET_SIZE) {
                    val packet = DatagramPacket(packetData, packetData.size, address, PORT)
                    socket.send(packet)
                } else {
                    // Send in chunks
                    val totalChunks = (packetData.size + MAX_PACKET_SIZE - 1) / MAX_PACKET_SIZE
                    for (i in 0 until totalChunks) {
                        val start = i * MAX_PACKET_SIZE
                        val end = minOf(start + MAX_PACKET_SIZE, packetData.size)
                        val chunk = packetData.copyOfRange(start, end)
                        val chunkHeader = "[$i/$totalChunks]:".toByteArray()
                        val packetDataChunk = chunkHeader + chunk
                        val packet = DatagramPacket(packetDataChunk, packetDataChunk.size, address, PORT)
                        socket.send(packet)
                        kotlinx.coroutines.delay(10)
                    }
                }

                kotlinx.coroutines.delay(50) // Small delay between pages
            }

            socket.close()
            onResult(true, "Sent ${pages.size} images to $ipAddress")
        } catch (e: Exception) {
            Log.e(TAG, "UDP image send failed", e)
            onResult(false, e.message)
        }
    }
}
