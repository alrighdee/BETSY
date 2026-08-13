package org.betsy.decode

/**
 * What an INF sub-code narrows a trouble code down to, written for the person who owns the car.
 *
 * Every line here is written from scratch for this project, and is the author's own wording.
 *
 * A trouble code names a system; the sub-code names the failure inside it. `P0705` on its own is
 * "transmission range sensor circuit" and leaves four circuits to check. `P0705-571` says which
 * one. That narrowing is the whole reason BETSY reads these, and it is what a plain code reader
 * cannot give you.
 *
 * ### Why the key is a pair
 *
 * Sub-code numbers are *nearly* unique on their own, and it is tempting to key on the number
 * alone. They are not: `123` belongs to both `P0A1F` and `P3000`, with different meanings. One
 * collision in 277 known pairs is exactly the kind of near-rule that reads as safe and silently
 * produces a wrong diagnosis on the one case it does not cover, so the key is the pair.
 *
 * ### Deliberately incomplete
 *
 * This covers a fraction of the sub-codes a Toyota hybrid can report, and the gaps are not evenly
 * spread: the codes for a *failing pack* specifically are still missing, which is the family this
 * app most wants to explain. [forCode] returns null for anything not listed and the UI shows the
 * bare number, because a confident wrong explanation is worse than none. Someone might replace a
 * battery on the strength of it.
 *
 * Adding an entry is the whole extension mechanism. There is no fallback that guesses.
 */
object InfMeaning {
    /**
     * A sub-code's meaning, in the order someone actually wants to read it.
     *
     * [narrows] leads because it is the answer to the question being asked. [area] follows as the
     * place to go looking, and is often the more useful half to a garage: it is a component, not a
     * theory.
     */
    data class Detail(
        /** One sentence: what specifically failed, as distinct from the parent code's system. */
        val narrows: String,
        /** Where to look. Empty when the source names no component beyond the code itself. */
        val area: String = "",
    )

    // Shared between the two trouble codes that carry this fault, so the wording cannot drift
    // apart and imply the areas differ between them.
    private const val LEAK_UNLOCATED =
        "Something in the high-voltage system is leaking to the car's body, and the car has not yet worked out where."
    private const val LEAK_AC =
        "The leak is in the air conditioning compressor or its inverter."
    private const val LEAK_BATTERY =
        "The leak is in the hybrid battery, its control unit, the main relays or the main resistor."
    private const val LEAK_TRANSAXLE =
        "The leak is in the transaxle, or in the motor and generator inverters."
    private const val LEAK_INVERTER =
        "The leak is in the inverters, the main relays, the main resistor or the frame wiring."

    /**
     * The hybrid controller failing one of its own internal checks, `P0A1D`.
     *
     * The source's fault table prints the *same* sentence against all eighteen of these sub-codes,
     * so on the table alone the sub-code narrows nothing. The distinction is real but is written
     * elsewhere on the page, beside the monitor description rather than in the fault table, and
     * most of it has now been read across. Nine sub-codes still share the generic sentence,
     * which is all the source says about them.
     *
     * `390` is deliberately absent: it is a `P0A1D` sub-code that means something else entirely,
     * and blanket-applying the family sentence to it would be wrong. It is written out with the
     * other codes.
     */
    private val controllerSelfTest: Map<Pair<String, Int>, Detail> =
        listOf(142, 155, 160, 183, 193, 197, 392, 393, 511)
            .associate {
                ("P0A1D" to it) to
                    Detail("The hybrid controller failed one of its own internal checks.", "Hybrid controller")
            } +
            listOf(134, 135, 570).associate {
                ("P0A1D" to it) to
                    Detail("The hybrid controller's analogue-to-digital conversion has failed.", "Hybrid controller")
            } +
            listOf(144, 145).associate {
                ("P0A1D" to it) to
                    Detail("The hybrid controller failed its start-up self-check.", "Hybrid controller")
            } +
            listOf(148, 149).associate {
                ("P0A1D" to it) to
                    Detail("The hybrid controller's main processor has failed.", "Hybrid controller")
            } +
            listOf(165, 168, 198, 199).associate {
                ("P0A1D" to it) to
                    Detail("The hybrid controller cannot decode the motor's position signal.", "Hybrid controller")
            } +
            listOf(568, 569).associate {
                ("P0A1D" to it) to
                    Detail("The hybrid controller's motor reference signal is faulty.", "Hybrid controller")
            } +
            mapOf(
                ("P0A1D" to 139) to
                    Detail("The hybrid controller has an ignition circuit fault inside it.", "Hybrid controller"),
                ("P0A1D" to 140) to
                    Detail("The hybrid controller's working memory is corrupted.", "Hybrid controller"),
                ("P0A1D" to 141) to
                    Detail("The hybrid controller's stored program is corrupted.", "Hybrid controller"),
                ("P0A1D" to 143) to
                    Detail("The hybrid controller's settings memory has failed.", "Hybrid controller"),
                ("P0A1D" to 187) to
                    Detail("A critical part of the hybrid controller's working memory has failed.", "Hybrid controller"),
                ("P0A1D" to 615) to
                    Detail("The hybrid controller's own network interface has failed.", "Hybrid controller"),
            )

    /**
     * Keyed by (trouble code, sub-code).
     *
     * Grouped by parent code rather than sorted by number, because the reason to read two entries
     * together is almost always that they share a parent and differ in the interesting way.
     */
    private val table: Map<Pair<String, Int>, Detail> =
        mapOf(
            // Gear selector position sensors. Four circuits, two failure modes each: broken or
            // earthed reads as nothing, shorted to battery positive reads as stuck full-scale.
            ("P0705" to 571) to Detail("The main shift sensor's wiring is broken or touching earth.", "Selector lever"),
            ("P0705" to 572) to Detail("The main shift sensor's wiring is touching battery positive.", "Selector lever"),
            ("P0705" to 573) to Detail("The backup shift sensor's wiring is broken or touching earth.", "Selector lever"),
            ("P0705" to 574) to Detail("The backup shift sensor's wiring is touching battery positive.", "Selector lever"),
            ("P0705" to 575) to Detail("The main select sensor's wiring is broken or touching earth.", "Selector lever"),
            ("P0705" to 576) to Detail("The main select sensor's wiring is touching battery positive.", "Selector lever"),
            ("P0705" to 577) to Detail("The backup select sensor's wiring is broken or touching earth.", "Selector lever"),
            ("P0705" to 578) to Detail("The backup select sensor's wiring is touching battery positive.", "Selector lever"),
            ("P0705" to 595) to Detail("The two shift-position sensors disagree by too much.", "Selector lever"),
            ("P0705" to 596) to Detail("The two select-position sensors disagree by too much.", "Selector lever"),
            ("P0851" to 579) to Detail("The park-position switch wiring is touching earth.", "Park position switch"),
            ("P0852" to 580) to
                Detail("The park-position switch wiring is broken or touching battery positive.", "Park position switch"),
            // Park signal as seen by the transmission control unit, rather than at the switch.
            ("P3102" to 581) to Detail("The transmission control unit itself has failed."),
            ("P3102" to 582) to Detail("The park signal contradicts itself, so the car cannot trust which gear it is in."),
            ("P3102" to 597) to Detail("The park signal wire is touching earth."),
            ("P3102" to 598) to Detail("The park signal wire is touching battery positive."),
            // Engine start failures reported to the hybrid side by the engine control unit. The
            // fuel variants matter to an owner far more than the others: nothing is broken.
            ("P0A0F" to 204) to Detail("The engine control unit reports the engine is not producing normal power.", "Engine control unit"),
            ("P0A0F" to 205) to Detail("The engine control unit reports the engine would not start.", "Engine control unit"),
            ("P0A0F" to 238) to Detail("The damper between engine and transaxle is at fault.", "Transmission input damper"),
            ("P0A0F" to 533) to
                Detail(
                    "The engine is not producing normal power, and the car believes it has run out of fuel.",
                    "Fuel level",
                ),
            ("P0A0F" to 534) to
                Detail("The engine would not start, and the car believes it has run out of fuel.", "Fuel level"),
            // Drive motor and generator: the mechanical entries here are the serious ones. A
            // locked generator and a locked gearset present identically to the driver and are
            // completely different repairs, which is this table's best argument for existing.
            ("P0A90" to 239) to Detail("The transaxle input shaft is damaged.", "Transmission input damper"),
            ("P0A90" to 240) to Detail("The generator will not turn.", "Generator"),
            ("P0A90" to 241) to Detail("The damper between engine and transaxle is at fault.", "Transmission input damper"),
            ("P0A90" to 242) to Detail("The planetary gearset will not turn.", "Transaxle"),
            ("P0A90" to 251) to
                Detail(
                    "The drive motor has lost magnet strength, or two windings of one phase are shorted together.",
                    "Drive motor",
                ),
            ("P0A90" to 509) to Detail("The drive motor system has failed.", "Drive motor"),
            ("P0A90" to 602) to Detail("The transaxle output is faulty.", "Transaxle"),
            ("P0A90" to 604) to Detail("The drive motor's power is not balancing as the car expects.", "Drive motor"),
            ("P0A90" to 605) to Detail("The drive motor's power is not balancing as the car expects.", "Drive motor"),
            ("P0A92" to 261) to
                Detail(
                    "The generator has lost magnet strength, or two windings of one phase are shorted together.",
                    "Generator",
                ),
            ("P0A92" to 521) to Detail("The generator system has failed.", "Generator"),
            ("P0A92" to 606) to Detail("The generator's power is not balancing as the car expects.", "Generator"),
            ("P0A92" to 607) to Detail("The generator's power is not balancing as the car expects.", "Generator"),
            // Resolvers: the position sensors the inverter needs to commutate the machines.
            ("P0A3F" to 243) to Detail("Two phases of the drive motor's position sensor are shorted together.", "Drive motor"),
            ("P0A40" to 500) to Detail("The drive motor's position sensor reads outside its normal range.", "Drive motor"),
            ("P0A41" to 245) to Detail("The drive motor's position-sensor wiring is broken or shorted.", "Drive motor"),
            ("P0A4B" to 253) to Detail("Two phases of the generator's position sensor are shorted together.", "Generator"),
            ("P0A4C" to 513) to Detail("The generator's position sensor reads outside its normal range.", "Generator"),
            ("P0A4D" to 255) to Detail("The generator's position-sensor wiring is broken or shorted.", "Generator"),
            // Motor temperature sensing. These are SENSOR faults, not overheating: the car cannot
            // read the temperature. Wording this as "too hot" would be a different fault and a
            // different repair.
            ("P0A2B" to 248) to Detail("Temperature sensor 1 on the drive motor is not working.", "Drive motor"),
            ("P0A2B" to 250) to Detail("Temperature sensor 1 on the drive motor is reading implausibly.", "Drive motor"),
            ("P0A2C" to 247) to Detail("Drive motor temperature sensor 1's wiring is touching earth.", "Drive motor"),
            ("P0A2D" to 249) to
                Detail("Drive motor temperature sensor 1's wiring is broken or touching battery positive.", "Drive motor"),
            ("P0A37" to 258) to Detail("Temperature sensor 2 on the drive motor is not working.", "Drive motor"),
            ("P0A37" to 260) to
                Detail("Temperature sensor 2 on the drive motor is reading implausibly.", "Drive motor, or low transaxle fluid"),
            ("P0A38" to 257) to Detail("Drive motor temperature sensor 2's wiring is touching earth.", "Drive motor"),
            ("P0A39" to 259) to
                Detail("Drive motor temperature sensor 2's wiring is broken or touching battery positive.", "Drive motor"),
            // Main relays, the pair of contactors that connect the pack. Stuck closed is the one
            // that matters: the pack stays live when the car thinks it is isolated.
            ("P0AA1" to 224) to Detail("Main relay 1's circuit is broken or touching battery positive.", "System main relay 1"),
            // High-voltage insulation breakdown: something live is leaking to the car's body.
            //
            // The car stores 526 first, having detected a leak without knowing where. It then runs
            // a sequence to isolate the area and stores one of 611..614 instead, so 526 and the
            // others are not stored together. 526 with nothing else means the isolation step has
            // not run or did not conclude.
            //
            // Carried under two trouble codes. Earlier documentation files this fault as P3009 and
            // later documentation as P0AA6, with the same sub-code numbers and the same areas, so
            // both are answered rather than only the one this project happened to read first.
            ("P3009" to 526) to Detail(LEAK_UNLOCATED, "Anywhere in the high-voltage system"),
            ("P0AA6" to 526) to Detail(LEAK_UNLOCATED, "Anywhere in the high-voltage system"),
            ("P3009" to 611) to Detail(LEAK_AC, "Air conditioning compressor and inverter"),
            ("P0AA6" to 611) to Detail(LEAK_AC, "Air conditioning compressor and inverter"),
            ("P3009" to 612) to Detail(LEAK_BATTERY, "Hybrid battery, main relays, main resistor"),
            ("P0AA6" to 612) to Detail(LEAK_BATTERY, "Hybrid battery, main relays, main resistor"),
            ("P3009" to 613) to Detail(LEAK_TRANSAXLE, "Transaxle and inverter assembly"),
            ("P0AA6" to 613) to Detail(LEAK_TRANSAXLE, "Transaxle and inverter assembly"),
            ("P3009" to 614) to Detail(LEAK_INVERTER, "Inverters, main relays, main resistor, frame wiring"),
            ("P0AA6" to 614) to Detail(LEAK_INVERTER, "Inverters, main relays, main resistor, frame wiring"),
            // 226 and 231 are the same fault described two ways in the source, so they read
            // the same here. Wording them differently would imply a distinction the source
            // does not make.
            ("P0AA1" to 226) to Detail("The pack's positive main relay has welded shut and will not open.", "System main relay 1"),
            ("P0AA1" to 231) to Detail("The pack's positive main relay has welded shut and will not open.", "System main relay 1"),
            ("P0AA1" to 233) to
                Detail("Both pack main relays have welded shut and will not open.", "System main relays"),
            ("P0AA2" to 225) to Detail("Main relay 1's circuit is touching earth.", "System main relay 1"),
            ("P0AA2" to 227) to Detail("The pack's positive main relay will not close.", "System main relay 1"),
            ("P0AA4" to 228) to Detail("The pack's negative main relay has welded shut and will not open.", "System main relay 3"),
            ("P0AA4" to 232) to Detail("The pack's negative main relay has welded shut and will not open.", "System main relay 3"),
            ("P0AA5" to 229) to Detail("The pack's negative main relay will not close.", "System main relay 3"),
            // What the battery ECU is telling the hybrid controller. 123 here is the collision
            // case: the same number under P0A1F means something else.
            ("P3000" to 123) to Detail("The battery unit reports a fault in the high-voltage pack.", "Hybrid battery"),
            ("P3000" to 388) to
                Detail("The battery unit cannot control discharge properly, which can also happen when fuel is low.", "Hybrid battery"),
            ("P3000" to 389) to Detail("The battery unit reports the pack voltage has fallen away.", "Hybrid battery"),
            ("P3000" to 603) to Detail("The battery unit reports the pack's cooling system is not working.", "Hybrid battery cooling"),
            ("P3004" to 132) to Detail("The main battery cable is at fault.", "Main battery cable"),
            ("P3004" to 133) to Detail("The battery unit is reporting a fault.", "Hybrid battery"),
            ("P0A1F" to 123) to Detail("The battery unit's own memory has failed its self-test.", "Battery ECU"),
            ("P0A1F" to 129) to Detail("The high-voltage battery voltage circuit is faulty.", "High-voltage fuse"),
            ("P0A1F" to 593) to Detail("The battery unit's ignition signal circuit is faulty.", "Battery ECU"),
            ("P0560" to 117) to Detail("The 12 volt supply signal is faulty.", "HEV fuse"),
            ("P3000" to 125) to Detail("The computer that looks after the hybrid battery has failed.", "Battery ECU"),
            ("P3004" to 131) to Detail("The high voltage supply side of the hybrid system has failed.", "High voltage supply"),
            // The hybrid controller failing one of its own charge-management checks.
            ("P0A1D" to 390) to
                Detail("The hybrid controller has failed at managing how the high voltage battery is charged.", "Hybrid controller"),
            // DC/DC converter: makes the 12 V supply. When it fails the car eventually stops,
            // which owners usually describe as a flat 12 V battery.
            ("P0A08" to 264) to Detail("The DC/DC converter, which charges the 12 V battery, has failed.", "Inverter assembly"),
            ("P0A10" to 263) to Detail("A DC/DC converter signal wire is touching battery positive.", "Inverter assembly"),
            ("P0A10" to 592) to Detail("A DC/DC converter output signal wire is touching battery positive.", "Inverter assembly"),
            ("P0A94" to 442) to Detail("The boost converter's output voltage is wrong.", "Boost converter"),
            // Corrected after a second reading of the source. The fault table titles this code
            // with the generic label "DC/DC converter", which reads as the unit that charges the
            // 12 V battery. The monitor description names it the BOOST converter, which steps
            // voltage up for the motor: a different unit in the same assembly. The first wording
            // here described the wrong component.
            ("P0A94" to 588) to Detail("The boost converter's switching control has failed.", "Boost converter"),
            ("P0A94" to 589) to Detail("The boost converter's low-side voltage reading is wrong.", "Boost converter"),
            ("P0A94" to 590) to Detail("The boost converter's low-side voltage reading is wrong.", "Boost converter"),
            ("P0A94" to 553) to Detail("The boost converter overheated.", "Boost converter"),
            ("P0A94" to 554) to Detail("Too much current flowed in the boost converter.", "Boost converter"),
            ("P0A94" to 555) to Detail("Too much current flowed in the boost converter.", "Boost converter"),
            ("P0A94" to 556) to Detail("Too much current flowed in the boost converter.", "Boost converter"),
            ("P0A94" to 550) to Detail("The boost converter's over-voltage detection circuit is faulty.", "Boost converter"),
            ("P0A94" to 561) to Detail("The boost converter's shutdown signal line is faulty.", "Boost converter"),
            ("P0A94" to 545) to
                Detail("The boost converter's over-voltage warning line is broken or touching earth.", "Wiring and inverter assembly"),
            ("P0A94" to 546) to
                Detail("The boost converter's over-voltage warning line is touching battery positive.", "Wiring and inverter assembly"),
            ("P0A94" to 551) to
                Detail("The boost converter's fault warning line is broken or touching earth.", "Wiring and inverter assembly"),
            ("P0A94" to 552) to
                Detail("The boost converter's fault warning line is touching battery positive.", "Wiring and inverter assembly"),
            ("P0A94" to 549) to
                Detail("The boost converter saw excessive voltage, caused by the transaxle failing.", "Transaxle and inverter assembly"),
            ("P0A94" to 558) to Detail("The boost converter's shutdown line is touching earth.", "Wiring and inverter assembly"),
            ("P0A94" to 559) to
                Detail("The boost converter's shutdown line is broken or touching battery positive.", "Wiring and inverter assembly"),
            ("P0A94" to 560) to Detail("The boost converter's shutdown line is broken.", "Wiring and inverter assembly"),
            ("P0A94" to 583) to Detail("The boost converter's temperature sensor has failed.", "Boost converter"),
            ("P0A94" to 584) to Detail("The boost converter's temperature sensor has failed.", "Boost converter"),
            ("P0A94" to 585) to Detail("The boost converter's low-side voltage sensor is reading implausibly.", "Boost converter"),
            ("P0A94" to 587) to Detail("One of the boost converter's voltage sensors is reading implausibly.", "Boost converter"),
            // Inverter, drive side.
            ("P0A78" to 267) to Detail("The inverter's voltage sensor wiring is touching battery positive.", "Inverter assembly"),
            ("P0A78" to 272) to Detail("The drive motor's inverter switching circuit is faulty.", "Inverter assembly"),
            ("P0A78" to 306) to
                Detail("The car could not verify the drive motor was producing the torque it asked for.", "Inverter assembly"),
            ("P0A78" to 308) to
                Detail(
                    "The airbag unit or the crash sensor reported an impact, so the high-voltage system shut itself down.",
                    "Airbag system, crash disconnect sensor",
                ),
            ("P0A78" to 507) to Detail("The drive inverter's shutdown signal wire is broken.", "Inverter assembly"),
            ("P0A78" to 508) to Detail("The drive inverter's shutdown signal is faulty.", "Inverter assembly"),
            ("P0A78" to 510) to Detail("The drive inverter's switching stage has failed.", "Inverter assembly"),
            ("P0A78" to 523) to Detail("The inverter's voltage sensor has drifted off zero.", "Inverter assembly"),
            ("P0A78" to 586) to Detail("The inverter's voltage sensor is reading implausibly.", "Inverter assembly"),
            ("P0A78" to 266) to Detail("The inverter's high-side voltage reading is faulty.", "Inverter assembly"),
            ("P0A78" to 279) to Detail("The drive inverter detected excessive voltage.", "Inverter assembly"),
            ("P0A78" to 503) to Detail("The drive inverter detected excessive voltage.", "Inverter assembly"),
            ("P0A78" to 282) to Detail("The drive inverter's over-voltage detection circuit is faulty.", "Inverter assembly"),
            ("P0A78" to 286) to Detail("The drive inverter's fault detection circuit is faulty.", "Inverter assembly"),
            // The inverter reports its own faults to the controller over dedicated warning
            // lines. A short on the line is a wiring fault, not an inverter fault: the part
            // being complained about is probably fine.
            ("P0A78" to 278) to
                Detail("The drive inverter's over-voltage warning line is touching battery positive.", "Wiring and inverter assembly"),
            ("P0A78" to 283) to
                Detail("The drive inverter's fault warning line is touching battery positive.", "Wiring and inverter assembly"),
            // Over-current trips on the drive inverter. The symptom is identical in all three;
            // the sub-code is the only thing naming which component caused it. 284 is a real
            // overheat, unlike the temperature-sensor codes above.
            ("P0A78" to 284) to Detail("The drive motor's inverter overheated.", "Inverter cooling system"),
            ("P0A78" to 287) to
                Detail("Too much current flowed in the drive inverter, because the inverter assembly itself failed.", "Inverter assembly"),
            ("P0A78" to 505) to
                Detail("Too much current flowed in the drive inverter, because the hybrid controller failed.", "Hybrid controller"),
            ("P0A78" to 506) to
                Detail("Too much current flowed in the drive inverter, because the transaxle failed.", "Transaxle"),
            ("P0A78" to 504) to
                Detail(
                    "Voltage inside the inverter climbed too high because the hybrid transaxle, the housing holding the " +
                        "motor and generator, has failed.",
                    "Hybrid transaxle and inverter",
                ),
            ("P3213" to 274) to Detail("The drive inverter's temperature sensor wiring is touching battery positive.", "Inverter assembly"),
            // Sensor-circuit faults: the car cannot read the inverter temperature. Not an
            // overheat.
            ("P3212" to 275) to
                Detail("The drive inverter's temperature sensor wiring is broken or touching earth.", "Wiring and inverter assembly"),
            ("P3222" to 313) to
                Detail("The generator inverter's temperature sensor wiring is broken or touching earth.", "Wiring and inverter assembly"),
            ("P3223" to 312) to
                Detail("The generator inverter's temperature sensor wiring is touching battery positive.", "Wiring and inverter assembly"),
            ("P3211" to 277) to Detail("The drive inverter's temperature sensor readings disagree.", "Inverter assembly"),
            // Inverter, generator side.
            ("P0A7A" to 309) to Detail("The generator's inverter switching circuit is faulty.", "Inverter assembly"),
            ("P0A7A" to 344) to
                Detail(
                    "The car could not verify the generator was producing the torque it asked for.",
                    "Inverter assembly",
                ),
            ("P0A7A" to 522) to Detail("The generator inverter's switching stage has failed.", "Inverter assembly"),
            ("P0A7A" to 324) to Detail("The generator inverter's fault detection circuit is faulty.", "Inverter assembly"),
            ("P0A7A" to 520) to Detail("The generator inverter's shutdown signal line is faulty.", "Inverter assembly"),
            ("P0A7A" to 321) to
                Detail("The generator inverter's fault warning line is touching battery positive.", "Wiring and inverter assembly"),
            ("P0A7A" to 323) to
                Detail("The generator inverter's fault warning line is broken or touching earth.", "Wiring and inverter assembly"),
            ("P0A7A" to 322) to Detail("The generator's inverter overheated.", "Inverter cooling system"),
            ("P0A7A" to 325) to
                Detail(
                    "Too much current flowed in the generator inverter, because the inverter assembly itself failed.",
                    "Inverter assembly",
                ),
            ("P0A7A" to 517) to
                Detail("Too much current flowed in the generator inverter, because the hybrid controller failed.", "Hybrid controller"),
            ("P0A7A" to 518) to
                Detail("Too much current flowed in the generator inverter, because the transaxle failed.", "Transaxle"),
            // Accelerator pedal. Two sensors that must agree; most of these are the pedal unit.
            ("P2120" to 111) to Detail("The pedal's main sensor is not changing while the backup one does.", "Accelerator pedal"),
            ("P2121" to 106) to Detail("The pedal's main sensor has failed internally.", "Accelerator pedal"),
            ("P2121" to 114) to Detail("The accelerator pedal is not springing back cleanly.", "Accelerator pedal"),
            ("P2125" to 112) to Detail("The pedal's backup sensor is not changing while the main one does.", "Accelerator pedal"),
            ("P2126" to 109) to Detail("The pedal's backup sensor has failed internally.", "Accelerator pedal"),
            ("P2138" to 110) to Detail("The pedal's two sensors disagree by too much.", "Accelerator pedal"),
            ("P2122" to 104) to Detail("The pedal's main sensor wiring is broken or touching earth.", "Accelerator pedal"),
            ("P2123" to 105) to Detail("The pedal's main sensor wiring is touching battery positive.", "Accelerator pedal"),
            ("P2127" to 107) to Detail("The pedal's backup sensor wiring is broken or touching earth.", "Accelerator pedal"),
            ("P2128" to 108) to Detail("The pedal's backup sensor wiring is touching battery positive.", "Accelerator pedal"),
            // High-voltage safety interlock. Almost always a service plug or cover left off after
            // work, rather than a failure.
            ("P3140" to 350) to
                Detail(
                    "A high-voltage safety interlock is open, usually a service plug or inverter cover that is not properly fitted.",
                    "Service plug, inverter cover",
                ),
            ("P3137" to 348) to Detail("The crash disconnect sensor's wiring is touching earth.", "Crash disconnect sensor"),
            // Air conditioning, which on this car runs off the high-voltage system.
            ("P3108" to 535) to Detail("The air-conditioning link has a communication fault.", "Air conditioning"),
            ("P3108" to 536) to Detail("The air-conditioning inverter has failed.", "Air conditioning"),
            ("P3108" to 537) to Detail("The air-conditioning control unit has failed.", "Air conditioning"),
            ("P3108" to 538) to Detail("An air-conditioning signal wire is broken.", "Air conditioning"),
            ("P3108" to 594) to Detail("The network link to the air conditioning has dropped.", "Network wiring"),
            // Ignition control relay.
            ("P3110" to 223) to Detail("The ignition control relay is stuck closed.", "Ignition control relay"),
            ("P3110" to 527) to Detail("The ignition signal contradicts itself.", "Ignition control relay"),
            // Network faults. The sub-code says which unit stopped answering, which is why they
            // are worth carrying even though the wording repeats.
            ("U0100" to 211) to Detail("The engine control unit stopped answering on the car's network.", "Network wiring"),
            ("U0100" to 212) to Detail("Messages from the engine control unit are arriving corrupted.", "Network wiring"),
            ("U0111" to 208) to Detail("The battery unit stopped answering on the car's network.", "Network wiring"),
            ("U0129" to 220) to Detail("The brake control unit stopped answering on the car's network.", "Network wiring"),
            ("U0129" to 528) to Detail("The brake control unit's network link is faulty.", "Network wiring"),
            ("U0129" to 222) to
                Detail("The brake computer and the hybrid controller have stopped talking to each other properly.", "Network wiring"),
            ("U0129" to 529) to
                Detail(
                    "A fault with the braking effort recovered by the hybrid system was reported between the brake " +
                        "computer and the hybrid controller.",
                    "Network wiring",
                ),
            ("U0100" to 530) to
                Detail("The network link between the engine computer and the hybrid controller has failed.", "Network wiring"),
            ("U0111" to 531) to
                Detail("The network link between the battery unit and the hybrid controller has failed.", "Network wiring"),
            ("U0131" to 433) to
                Detail("The power steering computer has stopped sending anything to the hybrid controller.", "Network wiring"),
            ("U0131" to 434) to
                Detail("The network link between the power steering computer and the hybrid controller has failed.", "Network wiring"),
            ("U0146" to 435) to
                Detail("The gateway unit that passes data between the car's networks has stopped sending anything.", "Network wiring"),
            // Airbag-to-hybrid link. The car shuts the high-voltage system down on a crash
            // signal, so a fault on this link matters more than a comms fault usually would.
            ("P3107" to 214) to
                Detail(
                    "The wiring carrying messages from the airbag computer to the hybrid controller is touching earth.",
                    "Wiring and connectors",
                ),
            ("P3107" to 215) to
                Detail(
                    "Messages passing from the airbag computer to the hybrid controller are arriving corrupted.",
                    "Wiring and connectors",
                ),
        ) + controllerSelfTest

    /**
     * The meaning of [inf] under [dtc], or null when this project has not documented it.
     *
     * Null is the honest answer and the common one. Callers must show the bare number rather than
     * substituting a guess or the parent code's description, which would read as a diagnosis.
     */
    fun forCode(
        dtc: String,
        inf: Int,
    ): Detail? = table[dtc.uppercase() to inf]

    /** How many pairs are documented. Exposed so a capture can record coverage at read time. */
    val size: Int get() = table.size
}
