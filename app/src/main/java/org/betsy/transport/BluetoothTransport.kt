package org.betsy.transport

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

/**
 * Bluetooth ELM327 adapter over RFCOMM SPP (PROTOCOL.md §1).
 * The constructor blocks in [BluetoothSocket.connect], create it off the main thread.
 */
@SuppressLint("MissingPermission") // BLUETOOTH_CONNECT is requested in ConnectActivity (API 31+)
class BluetoothTransport(
    device: BluetoothDevice,
) : StreamTransport() {
    private val socket: BluetoothSocket = device.createRfcommSocketToServiceRecord(SPP_UUID)
    override val input: InputStream
    override val output: OutputStream

    init {
        socket.connect()
        input = socket.inputStream
        output = socket.outputStream
    }

    override fun applyTimeout(ms: Int) {
        // BluetoothSocket streams have no read timeout; a stuck read unblocks when close()
        // tears down the socket (BatteryActivity.onDestroy does exactly that).
    }

    override fun close() {
        try {
            socket.close()
        } catch (_: Exception) {
        }
    }

    companion object {
        /** RFCOMM Serial Port Profile UUID (§1). */
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    }
}
