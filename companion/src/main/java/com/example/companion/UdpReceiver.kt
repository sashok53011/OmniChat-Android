package com.example.companion

import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress

class UdpReceiver(
    private val preferredPort: Int = 12345,
    private val onMessageReceived: (String) -> Unit,
    private val onStatusChanged: (String) -> Unit
) {
    companion object {
        private const val TAG = "UdpReceiver"
        private val ALTERNATIVE_PORTS = intArrayOf(12345, 12346, 12347, 12348, 12349)
    }

    private var socket: DatagramSocket? = null
    @Volatile
    private var running = false
    private var activePort: Int = preferredPort

    fun start() {
        if (running) return

        // Stop any existing socket first
        stop()
        Thread.sleep(100)

        running = true

        Thread {
            var connected = false
            for (port in ALTERNATIVE_PORTS) {
                try {
                    socket?.close()
                    socket = DatagramSocket(null)
                    socket?.reuseAddress = true
                    socket?.bind(InetSocketAddress(port))
                    socket?.soTimeout = 1000
                    activePort = port
                    connected = true
                    onStatusChanged("Listening on port $port")
                    break
                } catch (e: java.net.BindException) {
                    Log.w(TAG, "Port $port busy, trying next...")
                    continue
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to bind port $port", e)
                    continue
                }
            }

            if (!connected) {
                running = false
                onStatusChanged("ERROR: All ports busy")
                return@Thread
            }

            val buffer = ByteArray(65535)
            while (running) {
                try {
                    val packet = DatagramPacket(buffer, buffer.size)
                    socket?.receive(packet)
                    val message = String(packet.data, 0, packet.length, Charsets.UTF_8)
                    if (message.isNotBlank()) {
                        onMessageReceived(message)
                    }
                } catch (e: java.net.SocketTimeoutException) {
                    // Normal timeout, continue listening
                } catch (e: Exception) {
                    if (running) {
                        onStatusChanged("ERROR: ${e.message}")
                        Log.e(TAG, "UDP error", e)
                    }
                }
            }
        }.start()
    }

    fun stop() {
        running = false
        try {
            socket?.close()
        } catch (e: Exception) { /* ignore */ }
        socket = null
        onStatusChanged("Stopped")
    }

    fun isRunning() = running
    fun getActivePort() = activePort
}
