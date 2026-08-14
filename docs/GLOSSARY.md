# Glossary

Plain definitions of the terms used across this repository, with real examples from cars this
project has read. If a term here also appears in `PROTOCOL.md`, that document is the technical
authority and this one is the explanation.

---

## The car's computers

### ECU (Electronic Control Unit)
A computer in the car. A Prius has a dozen or more, each responsible for one system and each able
to store its own faults. They talk to each other, and to a scan tool, over a shared bus.

The ones this project reads:

| Address | ECU | What it owns |
|---|---|---|
| `7E0` | Engine control module (ECM) | Petrol engine |
| `7E2` | HV control ECU | The hybrid system, and on a Gen2 also cruise control and the pedal inputs |
| `7E3` | HV battery ECU | The traction battery: block voltages, temperatures, state of charge |

**Why the address matters:** a fault lives in one specific ECU. Ask the wrong one and you get a
clean answer for a broken car. An early version of this app asked only the HV ECU and reported "no
faults" on a car that had `U0293` sitting in the engine ECU the whole time.

### CAN bus
The wiring the ECUs talk over. On a Gen2 Prius it runs at 500 kbit/s on pins 6 and 14 of the
diagnostic socket, using ISO 15765-4. Every ECU hears everything; each one answers only to its own
address.

### DLC3
The diagnostic socket under the dashboard. The trapezoid one every scan tool plugs into. Formally
OBD-II, on a Toyota it is called DLC3.

### ELM327
The chip inside most cheap Bluetooth or USB diagnostic dongles. It translates plain text commands
into CAN frames and back, which is what lets an app like this one talk to a car without any
special hardware. It is a translator, not a diagnostic tool: it has no idea what any of the data
means.

---

## Faults

### DTC (Diagnostic Trouble Code)
The code an ECU stores when it detects a fault. What a garage means by "pulling the codes".

`P0571` breaks down as:

- **`P`** the area. `P` powertrain, `B` body, `C` chassis, `U` network and communication.
- **`0571`** the specific fault within that area.

Real examples from this project:

| Code | Meaning | Stored in |
|---|---|---|
| `P0571` | Brake switch circuit | HV ECU (`7E2`) |
| `U0293` | Lost communication with the HV ECU | Engine ECU (`7E0`) |
| `P0AA6` | High-voltage system isolation fault | HV ECU (`7E2`) |

**A DTC names a system, not a part.** `P0AA6` means "something in the high-voltage system is
leaking to the chassis", which could be the battery, the air conditioning compressor, the
transaxle or the cables. That is a difference of thousands in repair cost, and it is why the
sub-code below matters so much.

### INF code (also: detail code, sub-code)
A three-digit number Toyota attaches to a DTC to narrow it from a system to an area.

`P0AA6` on its own is "the HV system is leaking somewhere". With its sub-code:

- `P0AA6-611` the air conditioning
- `P0AA6-612` the battery
- `P0AA6-613` the transaxle
- `P0AA6-614` the HV DC circuitry

Same code, four completely different repairs. **Almost no affordable scan tool reads these**, which
is the gap this project exists to close.

Sub-codes are not universally available. On the confirmed Gen2 path, BETSY reads the transmitted
value from freeze pages on the hybrid-control ECU. See `PROTOCOL.md` §7.4.

### Freeze frame
A snapshot of sensor readings recorded at the exact moment a DTC set. The car's dashcam for
faults: the DTC says what broke, the freeze frame says what the car was doing when it broke.

A real one, captured with `P0571` live:

```
Caused by        P0571
Engine RPM       0 rpm        engine off
Vehicle speed    0 km/h       stationary
Coolant temp     81 °C        warm
Module voltage   13.69 V      DC/DC converter running
Run time         38 s         since the car was switched on
```

This matters because identical codes mean different things under different conditions. A misfire
at 4000 rpm and 95 °C is a different fault from the same code at idle on a cold morning. A car may
hold more than one frame; this one held two and refused a third.

### Pending, stored, permanent
- **Pending** seen once, not yet confirmed. Often clears itself.
- **Stored** confirmed. This is what "check engine light" usually means.
- **Permanent** stored and not clearable with a scan tool; only the car can clear it, after the
  fault stops recurring.

---

## Talking to a car

### Service (also: mode)
The kind of question being asked. The first byte of a request.

| Service | Asks for |
|---|---|
| `01` | Live data |
| `02` | Freeze frame |
| `03` | Stored DTCs |
| `07` | Pending DTCs |
| `09` | Vehicle information, including the VIN |
| `21` | Toyota-specific data by identifier |
| `13` | Toyota-specific stored DTCs |

`01`, `02`, `03`, `07` and `09` are the legally mandated generic set, which any scan tool can use
on any car. `13` and `21` are manufacturer-specific and are where the useful hybrid data lives.

### Generic OBD vs enhanced
**Generic OBD-II** is the standardised subset, mandated for emissions. Every scan tool reads it,
and it covers almost nothing of the hybrid system.

**Enhanced** (or manufacturer-specific) is everything else: battery block voltages, INF sub-codes,
hybrid faults. It is not standardised, which is why cheap tools cannot read it and why work like
this is needed to use it.

A concrete illustration: with `P0571` stored on the HV ECU, the generic `03` request returned
**zero** codes. The fault is real and the generic view of the car is empty.

### Identifier (DID, or local identifier)
Which particular block of manufacturer-specific data you want. `21C6` means "service `21`,
identifier `C6`". Identifiers `C6` through `CA` on the HV ECU hold detail-code tables; `CE` on the
battery ECU holds block voltages.

### KWP2000, UDS
Two generations of diagnostic protocol. A Gen2 Prius speaks **KWP2000**, which is why it answers
service `13` and `21`. It does not implement **UDS**, so requests like `1902` are refused outright.
Recipes written for newer cars simply do not apply.

### Negative response, NRC
An ECU's way of saying no, and why. A reply beginning `7F` is a refusal, and the third byte is the
**negative response code** explaining it.

| NRC | Means |
|---|---|
| `11` | Service not supported. That door does not exist. |
| `12` | Sub-function not supported. Right service, wrong argument. |
| `22` | Conditions not correct. Right question, wrong moment. |
| `31` | Request out of range. |

**A refusal is often more informative than silence.** `7F 12 11` proves a service is absent;
`7F 12 22` proves it exists and merely has nothing to say right now. Silence proves nothing at all:
it looks the same whether the service is missing, the car is asleep, or the adapter is confused.

### Liveness
Checking an ECU is actually answering before believing a clean result. "No faults found" is only
meaningful if the ECU was listening. Without this check, a sleeping car and a healthy car look
identical.

---

## The battery

### Block, cell, module, pack
A Gen2 traction battery holds **28 cells** in **14 blocks** of two. The car reports voltages per
block, not per cell, so the finest resolution available is a pair. The whole assembly is the
**pack**, nominally around 200 V.

### Block voltage spread (delta)
The gap between the highest and lowest block. The single most useful number for battery health: a
healthy pack stays tight, and a failing module drifts away from its neighbours under load. Absolute
voltage matters far less than how far apart the blocks are.

### SOC (State of Charge)
How full the battery is, as a percentage. A hybrid deliberately holds SOC in a narrow middle band,
roughly 40 to 80 percent, to protect the cells, so a healthy car never shows 100.

---

## This project

### Capture
A saved record of one read: every request sent, every response received verbatim, plus context.
Uploaded so that faults from real cars can be analysed later.

**The bytes always travel beside the interpretation, never instead of it.** If the decoder is
wrong, a capture that stored only the decoded result would destroy the very evidence proving it
wrong. Anything unexplained is kept raw.

### Decoder miss
A capture where the car reported a fault and the decoder produced no sub-code. Flagged
deliberately rather than hidden: it marks exactly the cases worth investigating, and those captures
are how the decoding gets better.

### Generation
- **Gen1** NHW10 and NHW11, 2000 to 2003
- **Gen2** NHW20, 2004 to 2009. 14 blocks, 28 cells
- **Gen3** ZVW30, 2010 to 2015

Each generation uses different addresses and encodings, so a decoder for one is wrong for another.
