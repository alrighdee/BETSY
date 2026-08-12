package org.betsy.decode

/**
 * What a trouble code actually means, written for the person who owns the car.
 *
 * Every line here is written from scratch for this project, and is the author's own wording.
 *
 * A trouble code names a system, not a part, and on its own it tells an owner almost nothing. The
 * point of BETSY is to be understood by the person holding the phone, so each entry says what
 * broke, what usually causes it, and how worried to be, in that order.
 *
 * Deliberately incomplete. A car can store hundreds of codes and this covers the ones a hybrid
 * battery scanner actually surfaces. An unknown code is shown as a bare code rather than guessed
 * at, because a confident wrong explanation is worse than none: someone might replace a battery on
 * the strength of it.
 *
 * Nothing here is medical-grade. [Severity] is a hint about urgency, not a diagnosis, and the
 * wording says so where it matters.
 */
object DtcMeaning {
    /**
     * How much the owner should worry, which is not the same as how expensive it is.
     *
     * [advice] closes the explanation rather than opening it. The fault itself leads, the cause
     * follows, and what to do about it comes last: someone reading their own car's fault wants to
     * know what broke before being told how to feel about it.
     */
    enum class Severity(
        val advice: String,
    ) {
        /** Safe to drive. Worth mentioning, not worth worrying about. */
        MINOR("No hurry. Mention it at the next service."),

        /** Something is degraded. Drive gently and get it diagnosed. */
        SERIOUS("Get this looked at soon."),

        /** Possible danger to people. Stop using the car. */
        URGENT("Stop driving and get this checked before using the car again."),
    }

    data class Meaning(
        /** One sentence: what has actually gone wrong, in plain words. */
        val what: String,
        /** What usually causes it. Honest about uncertainty; most faults have several causes. */
        val usually: String,
        val severity: Severity,
    )

    /**
     * "Battery block N becomes weak", `P3011` upward, one code per block.
     *
     * **This is the family BETSY exists for.** A weak block is the repairable case: one pair of
     * cells has aged faster than its neighbours, and the code names which. Replacing that module
     * costs a fraction of a whole pack, and the difference between those two outcomes is most of
     * the value this app can offer an owner.
     *
     * Generated rather than written out twenty times, because only the number changes. A Gen2 has
     * 14 blocks; the higher codes exist for packs with more, and cost nothing to carry.
     */
    private val blockCodes: Map<Int, Meaning> =
        (1..20).associate { block ->
            (0x3010 + block) to
                Meaning(
                    what = "Battery block $block is weaker than the rest of the pack.",
                    usually =
                        "One pair of cells has aged faster than its neighbours, so the car keeps " +
                            "charging and discharging around it. This is usually a single failing " +
                            "module rather than a dead battery, and the block number tells a " +
                            "specialist exactly which one to look at.",
                    severity = Severity.SERIOUS,
                )
        }

    /** Keyed by the two-byte value the car transmits, which is what the reads return. */
    private val byWire: Map<Int, Meaning> =
        blockCodes +
            mapOf(
                0x0A80 to
                    Meaning(
                        what = "The car has decided the high-voltage battery is worn out.",
                        usually =
                            "Age. One or more cell blocks can no longer hold charge like the others, " +
                                "so the car keeps rebalancing them. Often a single failing module rather " +
                                "than the whole pack, which is worth checking before paying for a full " +
                                "replacement.",
                        severity = Severity.SERIOUS,
                    ),
                0x0A7F to
                    Meaning(
                        what = "The high-voltage battery is losing capacity.",
                        usually =
                            "Normal ageing, but far enough along that the car has noticed. Expect worse " +
                                "fuel economy and the engine running more often.",
                        severity = Severity.SERIOUS,
                    ),
                0x0AA6 to
                    Meaning(
                        what =
                            "High voltage is leaking to the car's bodywork instead of staying inside " +
                                "the system.",
                        usually =
                            "Water in the battery pack, a failed module, or a damaged high-voltage " +
                                "cable. Do not touch anything with orange cabling.",
                        severity = Severity.URGENT,
                    ),
                0x3000 to
                    Meaning(
                        what = "The computer that looks after the battery has faulted.",
                        usually =
                            "Often the battery ECU itself or its wiring rather than the cells. The " +
                                "sub-code narrows down which.",
                        severity = Severity.SERIOUS,
                    ),
                0x0A0F to
                    Meaning(
                        what = "The engine will not produce the power the hybrid system asked for.",
                        usually =
                            "An engine fault rather than a hybrid one. Look for engine codes stored " +
                                "alongside this.",
                        severity = Severity.SERIOUS,
                    ),
                0x0A1D to
                    Meaning(
                        what = "The computer that runs the hybrid system has faulted.",
                        usually = "The control unit or its wiring. The sub-code says which area.",
                        severity = Severity.SERIOUS,
                    ),
                0x0A93 to
                    Meaning(
                        what = "The electronics that drive the motors are running hot.",
                        usually =
                            "Low coolant, an air lock, or a failed inverter coolant pump. Cheap to " +
                                "check and expensive to ignore, because the inverter overheats.",
                        severity = Severity.SERIOUS,
                    ),
                0x0A94 to
                    Meaning(
                        what = "The car has stopped charging its ordinary 12 V battery.",
                        usually =
                            "This is the part that charges the ordinary 12 V battery from the big one. " +
                                "When it fails the car will eventually stop, because the 12 V side runs " +
                                "flat.",
                        severity = Severity.URGENT,
                    ),
                0x0571 to
                    Meaning(
                        what = "The car cannot tell whether the brake pedal is pressed.",
                        usually =
                            "The brake light switch or its fuse. Brake lights may not work, and cruise " +
                                "control will be disabled.",
                        severity = Severity.SERIOUS,
                    ),
                0xC293 to
                    Meaning(
                        what = "The engine computer has lost contact with the hybrid computer.",
                        usually =
                            "A wiring or power problem rather than a broken part. Often appears " +
                                "alongside whatever actually caused the hybrid side to go quiet.",
                        severity = Severity.SERIOUS,
                    ),
                0x0A82 to
                    Meaning(
                        what = "The battery's cooling fan is not moving air properly.",
                        usually =
                            "Almost always the intake blocked with dust or pet hair, or something " +
                                "stacked against the vent behind the rear seat. Worth fixing quickly and " +
                                "cheaply: a hot pack ages fast, and this is one of the few faults that " +
                                "actively destroys a battery while you ignore it.",
                        severity = Severity.SERIOUS,
                    ),
                0x0A0D to
                    Meaning(
                        what = "The safety interlock on the high-voltage system is open.",
                        usually =
                            "The orange service plug is not seated, or a high-voltage cover is loose " +
                                "after work on the car. The interlock exists to stop anyone touching " +
                                "live parts, so treat it as a real warning rather than a nuisance.",
                        severity = Severity.URGENT,
                    ),
                0x3004 to
                    Meaning(
                        what = "The high-voltage supply is not behaving as the car expects.",
                        usually =
                            "Often a failing block, a poor connection inside the pack, or a relay. The " +
                                "sub-code narrows it down.",
                        severity = Severity.SERIOUS,
                    ),
                0x3009 to
                    Meaning(
                        what = "Something in the high-voltage system is touching where it should not.",
                        usually =
                            "A damaged cable or a failed component. Do not touch anything with orange " +
                                "cabling.",
                        severity = Severity.URGENT,
                    ),
                0x0AF0 to
                    Meaning(
                        what = "The inverter's temperature sensor is reading implausibly.",
                        usually =
                            "The sensor or its wiring rather than an overheating inverter, though the " +
                                "car cannot tell the difference and will protect itself either way.",
                        severity = Severity.SERIOUS,
                    ),
                // --- Motor and generator: temperature ---
                0x0A2B to
                    Meaning(
                        what = "The drive motor is too hot.",
                        usually =
                            "The motor and its electronics share a coolant loop of their own, separate " +
                                "from the engine's. Low coolant, an air lock, or a failed pump in that " +
                                "loop, and only rarely the motor itself.",
                        severity = Severity.SERIOUS,
                    ),
                0x0A2C to
                    Meaning(
                        what = "The drive motor's temperature reading cannot be trusted.",
                        usually =
                            "The sensor or its wiring rather than a hot motor. The car cannot tell the " +
                                "difference, so it will limit power to be safe.",
                        severity = Severity.SERIOUS,
                    ),
                0x0A2D to
                    Meaning(
                        what = "The drive motor's temperature reading cannot be trusted.",
                        usually =
                            "As above: the sensor or its wiring. The two codes differ in which way the " +
                                "reading went wrong, which matters to whoever repairs it and not to you.",
                        severity = Severity.SERIOUS,
                    ),
                0x0A37 to
                    Meaning(
                        what = "The generator is too hot.",
                        usually =
                            "Same cooling loop as the drive motor, so suspect coolant level, an air " +
                                "lock, or the pump before suspecting the generator.",
                        severity = Severity.SERIOUS,
                    ),
                0x0A38 to
                    Meaning(
                        what = "The generator's temperature reading cannot be trusted.",
                        usually = "The sensor or its wiring rather than a hot generator.",
                        severity = Severity.SERIOUS,
                    ),
                0x0A39 to
                    Meaning(
                        what = "The generator's temperature reading cannot be trusted.",
                        usually =
                            "As above. The pair of codes distinguish which direction the reading " +
                                "failed in.",
                        severity = Severity.SERIOUS,
                    ),
                // --- Motor and generator: position sensors (resolvers) ---
                0x0A3F to
                    Meaning(
                        what = "The car cannot reliably tell where the drive motor is positioned.",
                        usually =
                            "The position sensor inside the transaxle, or its wiring and connector. " +
                                "The car needs this to drive the motor at all, so it will refuse or " +
                                "severely limit hybrid drive.",
                        severity = Severity.SERIOUS,
                    ),
                0x0A41 to
                    Meaning(
                        what = "The drive motor's position sensor is giving implausible readings.",
                        usually =
                            "Often the connector or a damaged loom rather than the sensor itself. " +
                                "Worth checking the wiring before anyone opens the transaxle.",
                        severity = Severity.SERIOUS,
                    ),
                0x0A4B to
                    Meaning(
                        what = "The car cannot reliably tell where the generator is positioned.",
                        usually =
                            "The generator's position sensor or its wiring. The generator starts the " +
                                "engine, so this can leave the car unable to start properly.",
                        severity = Severity.SERIOUS,
                    ),
                0x0A4C to
                    Meaning(
                        what = "The generator's position sensor signal is wrong or missing.",
                        usually = "Connector, wiring, or the sensor. Check the loom first.",
                        severity = Severity.SERIOUS,
                    ),
                0x0A4D to
                    Meaning(
                        what = "The generator's position sensor signal is wrong or missing.",
                        usually =
                            "As above. This code and the one before it differ in how the signal " +
                                "failed, not in what to inspect.",
                        severity = Severity.SERIOUS,
                    ),
                // --- Motor and generator: phase currents ---
                0x0A60 to
                    Meaning(
                        what =
                            "One of the three power feeds to the drive motor is not carrying the " +
                                "current it should.",
                        usually =
                            "The inverter, the thick cables between it and the transaxle, or a winding " +
                                "inside the motor. This one is usually expensive.",
                        severity = Severity.SERIOUS,
                    ),
                0x0A63 to
                    Meaning(
                        what =
                            "One of the three power feeds to the drive motor is not carrying the " +
                                "current it should.",
                        usually =
                            "As above, a different phase. Inverter, cables, or motor winding.",
                        severity = Severity.SERIOUS,
                    ),
                0x0A72 to
                    Meaning(
                        what =
                            "One of the three power feeds to the generator is not carrying the " +
                                "current it should.",
                        usually = "The inverter, the cables to the transaxle, or a generator winding.",
                        severity = Severity.SERIOUS,
                    ),
                0x0A75 to
                    Meaning(
                        what =
                            "One of the three power feeds to the generator is not carrying the " +
                                "current it should.",
                        usually = "As above, a different phase.",
                        severity = Severity.SERIOUS,
                    ),
                // --- Inverter and motor performance ---
                0x0A78 to
                    Meaning(
                        what = "The electronics driving the motor are not doing what they are told.",
                        usually =
                            "Usually the inverter assembly itself. Check its cooling first, since an " +
                                "inverter that has been overheating often fails this way and the pump " +
                                "is a fraction of the cost.",
                        severity = Severity.SERIOUS,
                    ),
                0x0A7A to
                    Meaning(
                        what = "The electronics driving the generator are not doing what they are told.",
                        usually =
                            "The inverter assembly. As above, rule out the cooling loop before " +
                                "condemning it.",
                        severity = Severity.SERIOUS,
                    ),
                0x0A90 to
                    Meaning(
                        what = "The drive motor is not producing the effort the car asked for.",
                        usually =
                            "Can be the motor, the inverter or the high-voltage supply feeding them. " +
                                "Expect stored codes alongside this one that narrow it down.",
                        severity = Severity.SERIOUS,
                    ),
                0x0A92 to
                    Meaning(
                        what = "The generator is not producing the effort the car asked for.",
                        usually =
                            "The generator, the inverter, or the high-voltage supply. Look at what " +
                                "else is stored before deciding.",
                        severity = Severity.SERIOUS,
                    ),
                0x0560 to
                    Meaning(
                        what = "The hybrid computer lost its permanent power supply.",
                        usually =
                            "A fuse or a battery disconnection. Note the car may have forgotten other " +
                                "stored faults while the power was off.",
                        severity = Severity.MINOR,
                    ),
            )

    /** The meaning of [wireValue], or null when this project has nothing honest to say about it. */
    fun forWire(wireValue: Int): Meaning? = byWire[wireValue]

    /** How many codes carry an explanation, for tests and for honesty about coverage. */
    val explainedCount: Int get() = byWire.size
}
