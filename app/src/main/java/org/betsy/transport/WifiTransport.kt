package org.betsy.transport

import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket

/** Wi-Fi ELM327 adapter: plain TCP socket, typically 192.168.0.10:35000 (PROTOCOL.md §1). */
class WifiTransport(
    val host: String = DEFAULT_HOST,
    val port: Int = DEFAULT_PORT,
    connectTimeoutMs: Int = 5000,
) : StreamTransport() {
    private val socket = Socket()
    override val input: InputStream
    override val output: OutputStream

    init {
        socket.connect(InetSocketAddress(host, port), connectTimeoutMs)
        socket.tcpNoDelay = true
        socket.soTimeout = readTimeoutMs
        input = socket.getInputStream()
        output = socket.getOutputStream()
    }

    override fun applyTimeout(ms: Int) {
        try {
            socket.soTimeout = ms
        } catch (_: java.net.SocketException) {
            // Socket already closed; the next read will fail anyway.
        }
    }

    override fun close() {
        try {
            socket.close()
        } catch (_: Exception) {
        }
    }

    companion object {
        const val DEFAULT_HOST = "192.168.0.10" // §1
        const val DEFAULT_PORT = 35000 // §1
    }
}
