package org.betsy.model

/**
 * One INF sub-code read from a freeze page (PROTOCOL.md §7.4.2).
 *
 * The ECU writes one page per stored DTC and transmits the sub-code inside it as a value, so this
 * is a number the car reported rather than a slot the app decided was set. [table] records which
 * of the five pages carried it, which matters while page-to-DTC assignment is unresolved for cars
 * with several stored faults.
 */
data class InfDetail(
    /** Local identifier of the page this came from, 0xC6..0xCA. */
    val table: Int,
    /** The 3-digit sub-code the car transmitted, e.g. 115 or 612. */
    val code: Int,
) {
    /** "Detail Code 1".."Detail Code 5", the labelling dealer tooling uses. */
    val tableLabel: String
        get() = "Detail Code ${table - 0xC5}"
}
