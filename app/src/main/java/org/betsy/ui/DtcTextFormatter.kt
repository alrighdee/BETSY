package org.betsy.ui

import org.betsy.decode.DtcMeaning
import org.betsy.decode.InfMeaning
import org.betsy.dtc.DtcGroup
import org.betsy.model.Dtc
import org.betsy.model.InfDetail

/** Pure text formatting for enhanced DTCs and their retained INF relationships. */
internal object DtcTextFormatter {
    fun formatGroups(
        groups: List<DtcGroup>,
        resolutions: List<InfMeaning.Resolution>,
    ): String {
        if (groups.isEmpty()) return "Toyota enhanced DTCs (KWP2000): none reported\n\n"

        val sb = StringBuilder()
        val exactByParent =
            resolutions
                .filterIsInstance<InfMeaning.Resolution.Exact>()
                .groupBy { it.dtc }

        for (group in groups) {
            sb.append(group.label).append(":\n")
            for (dtc in group.codes) {
                val exact = exactByParent[dtc.code].orEmpty()
                when (exact.size) {
                    0 -> sb.append("  ").append(dtc.code).append("\n")
                    1 -> {
                        val resolution = exact.single()
                        sb
                            .append("  ")
                            .append(dtc.code)
                            .append("-")
                            .append(resolution.inf)
                            .append("\n")
                        appendDetail(sb, resolution.detail, "    ")
                    }
                    else -> {
                        sb.append("  ").append(dtc.code).append("\n")
                        for (resolution in exact) {
                            sb.append("    INF ").append(resolution.inf).append(": ")
                            sb.append(resolution.detail.narrows).append("\n")
                            if (resolution.detail.area.isNotBlank()) {
                                sb.append("      Look at: ").append(resolution.detail.area).append("\n")
                            }
                        }
                    }
                }

                appendParentMeaning(sb, dtc)
                sb.append("\n")
            }
        }

        for (shared in resolutions.filterIsInstance<InfMeaning.Resolution.Shared>()) {
            sb.append("Shared INF ").append(shared.inf).append(" (")
            sb.append(shared.dtcs.joinToString(" / ")).append("):\n")
            appendDetail(sb, shared.detail, "  ")
            sb.append("\n")
        }

        return sb.toString()
    }

    fun formatInfEvidence(
        infCodes: List<InfDetail>,
        tablesResponded: Int,
    ): String {
        val sb = StringBuilder("INF tables: $tablesResponded/5 responded\n\n")
        if (infCodes.isEmpty()) return sb.toString()

        sb.append("INF DETAIL CODES:\n")
        for ((table, codes) in infCodes.groupBy { it.tableLabel }.toSortedMap()) {
            sb.append("  ").append(table).append(": ")
            sb.append(codes.joinToString(", ") { it.code.toString() }).append("\n")
        }
        val pair = infCodes.map { it.code }.sorted()
        if (pair.size > 1) {
            sb.append("  → ").append(pair.joinToString("-")).append("\n")
        }
        sb.append("  (mapping unverified, see docs/PROTOCOL.md §7.4)\n\n")
        return sb.toString()
    }

    private fun appendDetail(
        sb: StringBuilder,
        detail: InfMeaning.Detail,
        indent: String,
    ) {
        sb.append(indent).append(detail.narrows).append("\n")
        if (detail.area.isNotBlank()) {
            sb
                .append(indent)
                .append("Look at: ")
                .append(detail.area)
                .append("\n")
        }
    }

    private fun appendParentMeaning(
        sb: StringBuilder,
        dtc: Dtc,
    ) {
        DtcMeaning.forWire(dtc.raw)?.let { meaning ->
            val urgency =
                when (meaning.severity) {
                    DtcMeaning.Severity.URGENT -> "Stop driving. "
                    DtcMeaning.Severity.SERIOUS -> "Get this looked at soon. "
                    DtcMeaning.Severity.MINOR -> ""
                }
            sb
                .append("    ")
                .append(urgency)
                .append(meaning.what)
                .append("\n")
            sb.append("    ").append(meaning.usually).append("\n")
        }
    }
}
