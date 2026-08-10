package org.betsy.model

/**
 * One active INF detail code decoded from a `61 <lid>` table response (PROTOCOL.md §9.4.0).
 *
 * A fault reports a code from more than one table, so [table] matters: it says which of the five
 * detail tables this code came from, and pairing across tables is what names the failed
 * component. A code without its table is ambiguous.
 *
 * 0/1 and discards the magnitude (§9.4.0), so treat [value] as diagnostic detail rather than
 * as a number with meaning; presence is the signal.
 */
data class InfDetail(
    /** Local identifier of the table this came from, 0xC6..0xCA. */
    val table: Int,
    /** The 3-digit INF code, e.g. 526 or 611. */
    val code: Int,
    /** Raw extracted bit range; nonzero means active. */
    val value: Int,
) {
    val active: Boolean
        get() = value > 0

    /** "Detail Code 1".."Detail Code 5", the labelling dealer tooling uses. */
    val tableLabel: String
        get() = "Detail Code ${table - 0xC5}"
}
