package org.betsy.ui.connect

import org.betsy.transport.WifiTransport

/** A Wi-Fi ELM327 endpoint, `host:port` (PROTOCOL.md §1). */
data class WifiEndpoint(
    val host: String,
    val port: Int,
) {
    override fun toString(): String = "$host:$port"

    companion object {
        /**
         * Parses whatever is in the address field, falling back to the §1 defaults per part so a
         * bare host or an empty field still yields a usable endpoint.
         */
        fun parse(text: String): WifiEndpoint {
            val parts = text.trim().split(":")
            val host = parts.getOrNull(0)?.takeIf { it.isNotEmpty() } ?: WifiTransport.DEFAULT_HOST
            val port = parts.getOrNull(1)?.toIntOrNull() ?: WifiTransport.DEFAULT_PORT
            return WifiEndpoint(host, port)
        }
    }
}
