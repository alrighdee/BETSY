package org.betsy.ui.connect

/**
 * How much the adapter's firmware can be trusted with Toyota battery blocks. The mockup renders one
 * of three tones on each card, jade / amber / gray.
 */
enum class FirmwareTone {
    GOOD,
    WEAK,
    UNKNOWN,
}

/** One selectable adapter row on the connect screen. */
data class AdapterCandidate(
    /** Bluetooth MAC, or `host:port` for the Wi-Fi endpoint; also the selection key. */
    val id: String,
    val name: String,
    val address: String,
    /** ATZ banner cached from a previous successful connect; null until one has happened. */
    val firmware: String?,
    val lastUsed: Boolean,
) {
    val firmwareLabel: String get() = FirmwareGrade.label(firmware)
    val firmwareTone: FirmwareTone get() = FirmwareGrade.tone(firmware)
}

/**
 * Grades an ELM327 banner. Clones reporting below v1.5 cannot read Toyota battery blocks, and a v1.x
 * banner is the usual clone tell, so only v2.0 and up grade as good. An adapter we have never
 * connected to has no banner at all and stays [FirmwareTone.UNKNOWN] rather than being guessed at.
 */
object FirmwareGrade {
    private val VERSION = Regex("""v(\d+)\.(\d+)""", RegexOption.IGNORE_CASE)

    fun label(firmware: String?): String = firmware?.takeIf { it.isNotBlank() } ?: "Firmware unknown"

    fun tone(firmware: String?): FirmwareTone {
        val match = firmware?.let { VERSION.find(it) } ?: return FirmwareTone.UNKNOWN
        val major = match.groupValues[1].toIntOrNull() ?: return FirmwareTone.UNKNOWN
        return if (major >= 2) FirmwareTone.GOOD else FirmwareTone.WEAK
    }
}
