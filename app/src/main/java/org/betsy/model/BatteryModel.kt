package org.betsy.model

/**
 * The PROTOCOL.md §4 value slots, the contract between the PID decoders and the UI.
 * Temperatures are stored in Celsius; conversion is a display-layer concern (§5.1 note).
 * [currentAmps] is positive when discharging (§4).
 */
data class BatteryModel(
    var soc: Float = 0f,
    var deltaSoc: Float = 0f,
    var currentAmps: Float = 0f,
    var maxChargeHp: Float = 0f,
    var maxDischargeHp: Float = 0f,
    var aux12V: Float = 0f,
    var speedMph: Float = 0f,
    var rpm: Int = 0,
    var blockVolts: List<Float> = emptyList(),
    var internalResistance: List<Int> = emptyList(), // raw bytes, 1 unit = 1 mΩ (§4.1)
    var temps: List<Float> = emptyList(),
    var blockCount: Int = 0,
    var cellCount: Int = 0,
) {
    /** §4.2 PACK VOLTAGE = Σ block voltages. */
    fun packVolts(): Float = blockVolts.sum()

    /** §4.2 VOLT DIFF = max(block) − min(block). */
    fun voltDiff(): Float = if (blockVolts.isEmpty()) 0f else blockVolts.max() - blockVolts.min()

    fun minBlockIndex(): Int = blockVolts.indices.minByOrNull { blockVolts[it] } ?: -1

    fun maxBlockIndex(): Int = blockVolts.indices.maxByOrNull { blockVolts[it] } ?: -1
}
