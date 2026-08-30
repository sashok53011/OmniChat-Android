package com.example.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.util.Log
import com.example.wifi.UdpMessageSender
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.OutputStream
import java.util.UUID

data class BtDeviceInfo(
    val name: String,
    val macAddress: String
)

object BtManager {
    private const val TAG = "BtManager"
    private val SERIAL_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

    @SuppressLint("MissingPermission")
    fun getPairedDevices(context: Context): List<BtDeviceInfo> {
        val bluetoothAdapter = BluetoothAdapter.getDefaultAdapter() ?: return emptyList()
        if (!bluetoothAdapter.isEnabled) return emptyList()

        val devices = mutableListOf<BtDeviceInfo>()
        try {
            bluetoothAdapter.bondedDevices?.forEach { device ->
                val name = try {
                    device.name ?: device.alias ?: "Unknown"
                } catch (e: SecurityException) {
                    "Unknown"
                }
                devices.add(BtDeviceInfo(name = name, macAddress = device.address))
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException getting paired devices", e)
        }
        return devices
    }

    fun isBluetoothEnabled(): Boolean {
        val adapter = BluetoothAdapter.getDefaultAdapter() ?: return false
        return adapter.isEnabled
    }

    suspend fun sendViaBluetooth(macAddress: String, data: ByteArray): Result<String> = withContext(Dispatchers.IO) {
        val bluetoothAdapter = BluetoothAdapter.getDefaultAdapter()
            ?: return@withContext Result.failure(Exception("Bluetooth adapter not available"))

        if (!bluetoothAdapter.isEnabled) {
            return@withContext Result.failure(Exception("Bluetooth is disabled"))
        }

        val device: BluetoothDevice = try {
            bluetoothAdapter.getRemoteDevice(macAddress)
        } catch (e: IllegalArgumentException) {
            return@withContext Result.failure(Exception("Invalid MAC address: $macAddress"))
        }

        var socket: BluetoothSocket? = null
        var outputStream: OutputStream? = null

        try {
            socket = device.createRfcommSocketToServiceRecord(SERIAL_UUID)
            socket.connect()
            outputStream = socket.outputStream

            outputStream.write(data)
            outputStream.flush()

            Log.d(TAG, "Successfully sent ${data.size} bytes to $macAddress")
            Result.success("Sent ${data.size} bytes to ${device.name ?: macAddress}")
        } catch (e: IOException) {
            Log.e(TAG, "Failed to send to $macAddress", e)
            Result.failure(Exception("Send failed to ${device.name ?: macAddress}: ${e.message}"))
        } catch (e: SecurityException) {
            Log.e(TAG, "Bluetooth permission denied for $macAddress", e)
            Result.failure(Exception("Permission denied: ${e.message}"))
        } finally {
            try { outputStream?.close() } catch (e: IOException) { /* ignore */ }
            try { socket?.close() } catch (e: IOException) { /* ignore */ }
        }
    }

    suspend fun sendTextToAllDevices(
        macAddresses: List<String>,
        text: String,
        configs: Map<String, BtDeviceConfig>,
        onResult: (String, Boolean, String?) -> Unit
    ) {
        for (mac in macAddresses) {
            val config = configs[mac] ?: BtDeviceConfig.defaultConfig()
            val message = BtProtocol.createTextMessage(text, config)
            val data = BtProtocol.serializeTextMessage(message)
            val result = sendViaBluetooth(mac, data)
            result.fold(
                onSuccess = { msg -> onResult(mac, true, msg) },
                onFailure = { err -> onResult(mac, false, err.message) }
            )
        }
    }

    suspend fun sendImagesToAllDevices(
        macAddresses: List<String>,
        pages: List<android.graphics.Bitmap>,
        configs: Map<String, BtDeviceConfig>,
        onResult: (String, Boolean, String?) -> Unit
    ) {
        for (mac in macAddresses) {
            val config = configs[mac] ?: BtDeviceConfig.defaultConfig()
            try {
                var failed = false
                for ((index, page) in pages.withIndex()) {
                    val message = BtProtocol.createImageMessage(page, index + 1, pages.size, config)
                    val data = BtProtocol.serializeImageMessage(message)
                    val result = sendViaBluetooth(mac, data)
                    result.fold(
                        onSuccess = { },
                        onFailure = { err ->
                            onResult(mac, false, "Page ${index + 1} failed: ${err.message}")
                            failed = true
                        }
                    )
                    if (failed) break
                }
                if (!failed) {
                    onResult(mac, true, "Sent ${pages.size} pages to $mac")
                }
            } catch (e: Exception) {
                onResult(mac, false, e.message)
            }
        }
    }

    suspend fun sendTextToCompanion(
        companionIp: String,
        text: String,
        onResult: (Boolean, String?) -> Unit
    ) {
        UdpMessageSender.sendMessage(companionIp, text, "OmniChat AI", onResult)
    }
}
