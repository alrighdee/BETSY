package org.betsy.elm

/** Adapter error text must be classified before extracting hex, even after positive data. */
internal object AdapterStatus {
    private val TEXT =
        listOf("NO DATA", "CAN ERROR", "BUS ERROR", "UNABLE TO CONNECT", "STOPPED", "BUFFER FULL", "ERROR")

    fun find(raw: String): String? {
        if (raw.contains('?')) return "?"
        return TEXT.firstOrNull { raw.contains(it, ignoreCase = true) }
    }
}
