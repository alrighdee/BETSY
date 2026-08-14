package org.betsy.model

/**
 * One diagnostic trouble code from the mode-13/0A/03/07 reads (PROTOCOL.md §9.1–9.3).
 * [raw] is the 2-byte J2012 value; [code] is its formatted form (e.g. `P0AA6`).
 * The INF sub-code (e.g. `P0AA6-611`) is carried separately in the enhanced freeze pages
 * (`21C6`..`21CA`, §7.4), not by this record.
 */
data class Dtc(
    val raw: Int,
    val code: String,
)
