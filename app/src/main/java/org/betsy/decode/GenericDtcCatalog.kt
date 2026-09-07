package org.betsy.decode

import java.util.Locale

/**
 * Generic OBD-II DTC titles for codes this project has not written an explanation for.
 *
 * Titles are the GENERIC English rows from
 * [Wal33D/dtc-database](https://github.com/Wal33D/dtc-database) (MIT License,
 * Copyright (c) 2024 Wal33D (Waleed Judah)). They name the system, not a repair.
 *
 * [DtcMeaning] always wins: if this project has a hand-written paragraph for a code, the
 * catalog title is not shown. There is no severity and no "usually" here, because those
 * sentences are the authored table's job.
 */
object GenericDtcCatalog {
    fun title(code: String): String? = table[code.uppercase(Locale.US)]

    val size: Int get() = table.size

    private val table: Map<String, String> by lazy { load() }

    private fun load(): Map<String, String> {
        val stream =
            GenericDtcCatalog::class.java.getResourceAsStream("generic_dtc.tsv")
                ?: error("missing org/betsy/decode/generic_dtc.tsv")
        stream.bufferedReader().use { reader ->
            val out = HashMap<String, String>(10_000)
            for (line in reader.lineSequence()) {
                if (line.isEmpty() || line.startsWith("#")) continue
                val tab = line.indexOf('\t')
                if (tab <= 0) continue
                val code = line.substring(0, tab).uppercase(Locale.US)
                val title = line.substring(tab + 1).trim()
                if (title.isNotEmpty()) out[code] = title
            }
            return out
        }
    }
}
