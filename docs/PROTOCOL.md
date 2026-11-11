# BETSY protocol notes

Wire specification for Toyota/Lexus hybrid diagnostics over a plain ELM327 adapter, as used by
this app. Section numbers are stable, the code cites them from KDoc, so they are anchors, not
just headings.

**Status of each claim.** Anything marked *measured* was observed on a stock US-market 2009
Prius (Gen2 XW20) with a `ELM327 v1.5` clone. Anything marked *inferred* has not been confirmed
against a car exhibiting it: it is a working assumption, carried so the code has something to do
when it meets that case, and it may be wrong. The distinction is load-bearing: a battery
diagnostic that presents a guess as a reading is worse than one that says nothing.

---

## 1. The ELM327 link

Commands are ASCII terminated by `\r`. The adapter replies, then emits a `>` prompt, that
prompt, not a newline, delimits a response.

Session setup, in order (*measured*):

```
ATZ           reset; the reply carries the firmware banner
ATE0          echo off
ATL0          linefeeds off
ATS0          spaces off
ATH0          headers off
ATSP0         automatic protocol detection
ATSTFF        maximum timeout
```

`ATH0` matters: with headers on, every response carries a CAN ID prefix and every parser in §2
breaks. `ATSTFF` matters because the first request after protocol detection can exceed a second,
and a short timeout turns that into a spurious `NO DATA`.

After a missing prompt, EOF, read failure, or failed write, a stream transport closes the
connection and rejects further commands before writing or reading. Reconnect with a new
transport instance; retrying ATZ on the failed stream cannot restore a known boundary.
Partial bytes remain evidence for the failed request. This deliberately does not attempt to
drain late responses, and does not change how complete ECU refusals or NO DATA are handled.

Transports are interchangeable above the byte link: classic Bluetooth (RFCOMM/SPP, UUID
`00001101-0000-1000-8000-00805F9B34FB`), BLE (GATT), or Wi-Fi (TCP, commonly
`192.168.0.10:35000`).

### 1.1 Adapter identification and firmware grading

`ATZ` answers with a banner such as `ELM327 v1.5` or `ELM327 v2.2`. Grade on the major version:

| Banner | Grade | Meaning |
|---|---|---|
| `v2.x` | good | reads Toyota battery blocks reliably |
| `v1.x` | weak | may miss blocks on some clones |
| absent / unparsable | unknown | do **not** guess, an adapter that has not answered yet has no grade |

Do not grade on the Bluetooth class of device. Cheap clones report it wrongly, the adapter this
app was developed against advertises itself as a keyboard, so class-based filtering will
confidently mislabel a working adapter. List every paired device and let the user choose.

### 1.2 ECU addressing

Select the target with `ATSH<header>` before each command group.

| Header | ECU | Carries |
|---|---|---|
| `7E2` | HV / power management | DTCs, INF freeze pages; battery data on Gen3 and the `7E2` Gen2 layout |
| `7E3` | HV battery (Gen2) | block voltages, temps, current, SOC, internal resistance |
| `7E0` | Engine (ECM) | engine DTCs via KWP2000 `13B0` / `0A` on Gen2–Gen3 (*inferred*) |
| `7D2` | Hybrid vehicle control (Gen4) | recognised, not yet supported |
| `747` | HV/EV battery, Gen4.5 | recognised, not yet supported |
| `7E7` | GM two-mode | recognised, not yet supported |

On Gen2 the **battery** ECU is `7E3`, not `7E2`, this is stated backwards in a lot of
circulating material. Use physical addressing throughout; never send any of this to the
functional address `7DF`.

For setup commands and `ATSH`, require one standalone `OK` response line (case-insensitive),
allowing surrounding whitespace and an optional leading echo of that same command. Reject
substring matches such as `NOT OK` or `OKAY`, unexpected extra lines, and adapter error text even
when an `OK` line is also present. Keep the existing one retry; if neither reply confirms the
command, stop setup or skip the addressed ECU block and report the failure. The final raw reply
remains available in the session and failure message. The ATZ banner check is unchanged.
This is client-side acknowledgement validation, covered by synthetic tests; no new on-car
verification or change to ECU requests is implied.

**`ATSH` is adapter-global state.** The header and the request that follows must be one atomic
critical section. A background poller that can set its own header between another task's `ATSH`
and its request will eventually do so, and the request then goes to the wrong ECU and returns
plausible garbage. `ElmSession.withEcu` exists for this.

---

## 2. Response normalization

A response is reduced to one continuous uppercase hex string, `R`. Whitespace, echoes and ELM
status words are removed first.

Multi-frame replies arrive as a 3-hex-char ISO-TP byte count followed by indexed continuation
lines (`0:`, `1:`, `2:` …). **The declared count is authoritative**, the adapter pads the final
frame, so concatenating frames over-counts. Single-frame replies carry no count; stripping three
characters unconditionally eats real payload. Both mistakes are silent.

`NO DATA`, `CAN ERROR`, `BUS ERROR`, `ERROR`, `STOPPED`, `UNABLE TO CONNECT`,
`BUFFER FULL` and `?` are error status, not data. Classify them case-insensitively before
hex extraction and reject the exchange, even when positive data precedes the error text.
Do not strip an error and decode what remains: `NO DATA` reduces to plausible-looking
`DAA`, and `STOPPED` contributes `ED`, which can supply a missing PID byte.

The same status check applies to normalized session reads, raw generic DTCs, mode-02 freeze
frames, and liveness. Preserve the entire raw response and an error note; a failed INF page
does not increment the responded-page count. An error in a freeze-frame mask stops frame
discovery, while an individual PID error leaves subsequent reads intact. This does not add
retry, recovery, or a rule to scan ordinary hex payloads for embedded negative-response tags.

A normalized unprefixed byte response must contain pairs of hex digits. A dangling nibble
is an incomplete byte, even after otherwise readable data; reject it before updating values.
For length-prefixed messages, the three ELM length digits are metadata and are not subject to
this byte-pair check; the bounded application data is. For ATH1, remove the three-digit CAN ID
before checking the PCI/data byte sequence. Existing declared-boundary padding handling remains
unchanged. Preserve malformed raw evidence and report the failed read.

### 2.1 Indexing

`u8(k)`, `u16(k)` and `bytes(k, n)` are indexed in **hex characters**, not bytes, so offsets read
directly off the wire dumps. `u16` is big-endian. Callers length-guard with `requireLength`.

### 2.2 No silent stale values

A missing response tag raises. A short body is a truncated read, not a zeroed reading. Never
pad, never decode a partial frame, never surface a previous frame as current. On a battery
diagnostic the difference between "no fault" and "we failed to ask" is the whole product.

---

## 3. Vehicle detection

For the Gen2 detection path, probe tags must be at the application-message boundary, optionally
after a complete three-digit ELM length envelope. After `21D0` supplies the block count, the
already received `21CE` sample must contain the full CE fields for that count before live data
is enabled. This validation adds no requests. Transport failures and malformed declared probe
messages abort detection rather than count as evidence for another layout; ordinary unsupported
PID replies still allow fallback. This is BETSY detection policy, not the original OEM model-selection flow.

A fallback chain. Each probe sets a header, issues one command, and looks for the expected
positive tag; a miss falls through. Do not ask the user which car they have.

| Order | Layout | Header | Send | Expect | Notes |
|---|---|---|---|---|---|
| 1 | Gen3 | `7E2` | `2181` | `6181` | |
| 2 | **Gen2, battery on `7E3`** | `7E3` | `21CE` | `61CE` | measured |
| 3 | **Gen2-era, battery on `7E2`** | `7E2` | `21CE` | `61CE` | capture only, never decoded |
| 4 | Gen4 | `7D2` | `221809` | `621809` | not yet supported |
| 5 | Gen4.5 | `747` | `221F9A` | `621F9A` | not yet supported |

Steps 2 and 3 are the entire question for a Gen2-era car: identical command, different header, first to
answer wins. *Measured:* a 2009 Gen2 returns `NO DATA` to `2181` on `7E2`, then `61CE` on `7E3`.

Match the **full positive tag** (service byte + `0x40`, then the identifier), not the bare
identifier. `221809` answers `62 18 09` on success and `7F 22 13` on refusal; matching only
`1809` can be fooled by an echoed request or by a refusal containing those digits.

These two are **protocol layouts, not model generations**, and they are named for the behaviour
that separates them because nothing else reliably predicts it.

The label "the `7E2` Gen2 layout" was used here previously and has been removed. In the Prius
community that term means the 2006-2009 facelift, which is a different claim about a different
thing. *Measured:* the test car, a 2009 and the last Gen2 model year, answers `21CE` on `7E3` and
takes the first branch. Its captures show `ATSH7E3` followed by `21CED0CF` returning `61CE…`, with
DTCs and the INF freeze pages coming separately from `7E2`.

The third branch is **recognised but never decoded**. `21CE` is not guaranteed to be answered by
the battery ECU, so the probe tries the other address rather than giving up, but no car has ever
been observed taking it, which means its payload layout has never been checked against anything.

A car that takes it is therefore admitted for capture only: trouble codes and the five INF pages
are read and recorded verbatim, and no live battery value is decoded or displayed. Producing a
state of charge from an unchecked layout would be a guess presented as a reading, which is the one
thing this project will not do. The capture is what would turn that branch into a supported one.

### 3.2 Gen1 sub-detection

Gen1 uses ISO 9141-2 (`ATSP3`) and is recognised but not yet supported by this build. Block-count
discovery there is *inferred*, unverified, and deliberately not relied on.

### 3.3 Block count and split packs

For Gen2, block count `N` is read directly from D0 payload byte 0. The complete 15-byte
preamble and all `N` resistance bytes must be present. The implemented P4 voltage layout
supports 1–20 blocks; counts outside that range are rejected. This is a decoder limit,
not a claim about every Toyota battery. An invalid Gen2 count is never inferred from resistance
values or replaced with 10. Both standalone D0 and combined reads reject a count that differs
from the monitor configuration before updating values.

The counter reports measurement blocks, not electrochemical cells. The application does not
multiply it by two to invent a cell count; the monitor and new capture metadata show blocks only.

Block count `N` comes from the ECU rather than being assumed. The `N == 15` variant additionally
tests for a split pack by comparing the first blocks against the last: a set reading above 18 V
where the tail reads below indicates two differently-scaled halves. *Inferred*, and only
reachable on hardware this build does not otherwise support.

---

## 4. Derived battery values

Pack voltage is the sum of block voltages. Voltage difference is max minus min across blocks,
the single most useful number for spotting a failing module.

### 4.1 Internal resistance

`21D0` returns one byte per block after a 15-byte preamble. **1 raw unit = 1 mΩ.** Display bands
used by the block chart:

| Resistance | Band |
|---|---|
| < 30 mΩ | normal |
| ≥ 30 mΩ | elevated |
| ≥ 40 mΩ | degraded |

### 4.2 Temperature and units

The model stores temperatures in **°C** throughout. Fahrenheit is a display-layer conversion
only, so a unit toggle can never alter a decoded value. Speed follows the same preference.

---

## 5. Per-generation decoding

Two containers recur:

```
u16 offset-binary:  value = (u16 - 32768) / 100      (volts, amps)
u8  half-scale:     value = u8 / 2                  (percent)
```

### 5.1 Gen 3 (`ATSH7E2`)

Block voltages from `2181`, internal resistance from `2195`. Temperatures use `u16 / 256 - 50`
rather than the Gen2 offset-binary form. *Inferred*; this build supports it but it has not been
measured here.

### 5.2 Gen 2, battery on `ATSH7E3` or `ATSH7E2`

Service `0x21` with a **single-byte** local identifier. Several identifiers may be requested in
one message on `7E3`, `21CED0CF` returns all three blocks concatenated, each behind its own
identifier echo, which makes a full refresh one round trip.

Section identifiers are checked at the calculated byte boundaries, never searched inside
sensor data. For the measured 14-block NHW20 reply, `D0` follows the 31-byte CE payload
and `CF` follows the 29-byte D0 payload. A voltage or resistance byte equal to `D0` or `CF`
is a sensor value, not a section marker. Truncated CE data is rejected before updating the model.

**`21CE`**, SOC, current, block voltages:

| Field | Location | Formula |
|---|---|---|
| SOC % | byte 0 | `u8 / 2` |
| Pack current A | bytes 1–2 | `(u16 - 32768) / 100`, negative = discharge |
| Block voltage `[0 .. min(N,17)-1]` | bytes 3+, 2 bytes each | `(u16 - 32768) / 100` |

**`21CF`**, if `N > 17`, blocks 18..N appear first; skip them. From offset `k`:

| Field | Location | Formula |
|---|---|---|
| 12 V auxiliary | `k+3` | `(2 * u8 - 256) / 10` |
| Max charge (hp) | `k+4` | `(u8 / 2 - 64) * 1.34` |
| Max discharge (hp) | `k+5` | `(u8 / 2 - 64) * 1.34` |
| ΔSOC | `k+6` | `u8 * 0.01` |
| Temps TB1–TB3 °C | `k+10`, `k+12`, `k+14` | `u16 / 100 - 327.68` |

For current and block voltages, subtract the integer midpoint before converting to floating
point and dividing by 100. For auxiliary voltage, calculate `2 * raw - 256` as an integer
before dividing by 10. This preserves the source decimal scale without subtracting two
already-rounded floating-point values. It changes numerical rounding, not coefficients or signs.

**`21D0`**, internal resistance, §4.1.

**Acceptance fixture** (*measured*, parked and charging). Reassembled `21CED0CF`:

```
61CE 6D 810B 85ED 85EB 85E2 85DF 85F0 85F6 85F1 85F3 85F4 85F3 85EB 85E5 85E4 85E1
D0   0E 0000000000000000 85DF 0385F605  13 13 13 13 13 13 13 13 13 13 13 13 13 13
CF   8CD1 80 C5 4E AA 00 09 0000 8C49 8C1B 8C54
```

decodes to SOC 54.5 %, current +2.67 A, 14 blocks 15.03–15.26 V, pack 212.15 V, 19 mΩ per block,
aux 13.8 V, temps 31.45 / 30.99 / 31.56 °C. These constants are self-validating, a Gen2 block is
two 7.2 V modules, so a wrong offset yields 340 V or 0.15 V, not a subtly wrong answer.

The Gen2 CE/D0/CF and combined decoders require the expected service/PID at the application
start, optionally after a three-digit ELM application length. Declared bytes must all arrive;
only the declared message is decoded, so trailing padding cannot supply missing fields.
For the supported 14-block compact layout, standalone CF needs the 16 payload bytes actually
used by its scalar and three-temperature fields, not an extra two bytes. This does not establish
a universal CF size for every ECU support map. Numeric formulas are unchanged by these guards.

### 5.6 Speed and RPM

`010C0D` returns both in one reply: `41 0C <rpm u16> 0D <speed u8>`.
The RPM record must start the application response, and speed must follow its complete two-byte
value at the next PID boundary. A repeated `41` before `0D` remains accepted. Arbitrary
substring matches, incomplete values, and missing bytes from an ELM-declared message are
rejected before either model value is assigned. Bytes outside the declared message cannot
supply the speed value; trailing padding after a complete message is ignored.


**RPM is a u16 and must be divided by 4; speed is a single byte.** Reading `0x0D` as a u16 throws
on every poll cycle and takes the whole live screen down with it. There is a regression fixture
for exactly this, the payload `410C00000D00`, captured from a car, ran 172 times without
incident once the width was right.

---

## 6. Timeouts

2500 ms per exchange for Gen2–Gen4.5. Detection uses the same value per probe. The first request
after `ATSP0` may take longer than any later one; do not tighten this to make detection feel
faster.

---

The battery monitor shows a waiting status before the first successful battery cycle. If the
primary read returns NO DATA, fails decoding, or has a connection error, it immediately marks
the displayed values as not updated instead of silently leaving them on screen. Existing values
are retained for reference. The next successful cycle clears that warning and renders fresh
battery values. Existing repeated-failure stop thresholds are unchanged.

The supplemental `010C0D` read is optional. A complete ECU refusal, NO DATA, or a malformed
speed/RPM response records a `010C0D skipped` capture entry and preserves the previous two
values; a successfully decoded battery cycle still completes. Transport/session failures and
invalid primary battery data remain failures. This does not add response-pending retries or
suppress connection errors.

## 7. Trouble codes and INF detail codes

### 7.1 Reading DTCs

Hybrid/HV DTCs on this ECU family are **KWP2000** (ISO 14230), not UDS (ISO 14229). Those come
from service `0x13`, not `0x19`. On Gen2–Gen3 the **engine (ECM)** uses the same KWP2000 services
on a different address (`7E0`). Generic SAE modes `$03`/`$07` on **7E2** are a separate,
experimental observation used for STOP-fuse / liveness work (see below); they are **not** a
substitute for Toyota enhanced DTCs or INF freeze pages.

| Generation | Hybrid / battery reads | Engine (ECM) reads |
|---|---|---|
| Gen2 | `ATSH7E2` `13B0`, then `ATSH7E3` `1380` | `ATSH7E0` `13B0` |
| Gen2, battery on `7E2` | `ATSH7E2` `13B0` and `1380` | `ATSH7E0` `13B0` |
| Gen3 | `ATSH7E2` `0A` (permanent) and `13B0` | `ATSH7E0` `0A` and `13B0` |

*Measured:* `13B0` and `1380` both answer `5300` on a healthy car's hybrid/battery ECUs. Masks
`1381` and `1382` are refused with `7F1312`.

Failed enhanced DTC reads retain their own verbatim ECU refusal or adapter-error output
under the ECU/request raw key, alongside the error note. A timeout with no received bytes
must not copy the preceding ECU's reply or an ATSH acknowledgement into that key. Failed
responses remain errors and are not decoded as an empty successful DTC list. Partial
transport replies remain incomplete evidence, even when their bytes look like a valid list.

Enhanced DTC completeness is tracked separately from whether any codes were found. Only
successful decoding of every planned enhanced request confirms an empty stored-code result.
A header-setup failure, refusal, transport error, or malformed list marks the read incomplete;
already found codes remain visible. The result card then reports unconfirmed stored-code
status, and an explicit incomplete-read note travels in the existing capture notes. Failures
of supplemental generic, freeze-frame, or INF reads do not change enhanced DTC completeness.
`hasStoredDtcs` still means codes were found, not that every ECU was read successfully.

Service `0A` is generic OBD-II permanent-DTC storage and is unrelated to Toyota INF detail codes.

**Engine path (*inferred*; confirm on-car).** Physical ECM header `7E0`,
never functional `7DF` (§1.2). Response tags and the two-byte layout match §7.2–7.3 (`53` for
mode 13, `4A` for mode 0A). A quiet or refusing ECM is recorded as a note and must not hide
hybrid results: hybrid INF empty after an HEV fuse test and an engine U-code such as **U0293**
are not contradictory; they are different ECUs answering different questions.

Capture raw keys are `"<header>/<cmd>"` (e.g. `7E2/13B0` vs `7E0/13B0`) so the same command on
two ECUs stays distinct.

#### 7.1.1 Gen2 7E2 liveness probe (**confirmed on-car**)

`0100` was inferred rather than measured until an on-car read, when a 2009 Gen2 answered it directly:

```
7E2 0100 -> 4100981A8013     7E2 0120 -> 412080018001
7E2 0140 -> 4140C4CC0000     7E2 0160 -> no response
```

So the HV ECU **does** implement SAE generic mode 01, and exposes PIDs up to 0x5F. `0100` is a
sound liveness probe on this generation and needs no fallback. (`0902` also answers, but mode 09
PID 02 returns the VIN, so nothing in this project requests it: a capture is uploaded to a public
repository and a VIN identifies the vehicle and often its owner.)

Before Gen2 / Gen2-on-7E2 DTC or INF reads, the app sends `ATSH7E2` then `0100` and classifies:

| Adapter / ECU output | ECU alive? | UI / result detail |
|---|---|---|
| Complete `4100` + four supported-PID mask bytes | yes | Responding |
| Complete `7F01<NRC>` for the requested service | yes | Negative response + NRC meaning |
| `NO DATA` / `CAN ERROR` / `BUS ERROR` | no | No response (NO DATA) |
| Transport timeout | no | No response (timeout) |
| `?` / `STOPPED` / `UNABLE TO CONNECT` | no | Adapter error |
| Truncated `41`/`7F`, another PID/service, or other hex | no | Unexpected response |

The raw probe response is retained even when it is incomplete or belongs to another PID/service.
This validates the single mode-01 probe; it is not full vehicle identification.

A clean "no DTCs" result is only meaningful if liveness says the ECU is responding.

#### 7.1.2 Gen2 generic OBD `$03` / `$07` on 7E2 (*experimental*)

After KWP2000 group reads and before INF freeze pages, Gen2 sweeps also:

1. `ATH1` (headers on) so the responder CAN ID is preserved in the raw log  
2. `03` (stored) and `07` (pending)  
3. `ATH0` in a `finally` block  

The raw line containing `7EA` is stripped of CAN ID + ISO-TP PCI before count-byte decode
(tags `43` / `47`). The responder must be the CAN ID at the start of the line, not a
matching sequence inside another ECU's data. Only classical-CAN single-frame PCI lengths
1–7 are accepted; every declared byte must be present, and padding beyond that length is
excluded from decoding. Invalid PCI and truncated frames are retained in raw responses with
an error note. Multi-frame replies remain explicitly unparsed. Mode `$07` is **supplemental**: Toyota enhanced / non-emissions faults may
not appear there. A clean `$03`/`$07` result must **not** be interpreted as "HV ECU has no
Toyota DTCs." KWP2000 groups, generic OBD, and INF remain three separate observations.

New raw keys: `7E2/0100`, `7E2/03`, `7E2/07`.

### 7.2 Response layout

`53 <count> <DTC 2B> …`, no per-DTC status byte. `5300` means zero stored codes. The count byte and all counted
2-byte codes must be present. An incomplete list is a failed read, not a shorter or empty
successful list; the reader retains the raw response and records a note. Trailing padding
beyond the counted list is ignored. The positive service must start the application message;
a matching byte sequence inside another response is not a DTC list. An optional three-digit
ELM application-length prefix bounds the message: all declared bytes must arrive, and bytes
outside that length cannot satisfy the count. This validates a single assembled response;
it does not add collection of multiple application responses. After the complete counted
body has been verified, a zero code word (`0000`) terminates the list without producing
`P0000`; earlier codes are retained and later words are not interpreted. Individual zero
bytes inside a nonzero code remain valid. The raw response, including the marker and tail,
is preserved for review. The zero marker does not waive the completeness checks above.

### 7.3 Letter mapping and parsing

Each DTC is two bytes. The high nibble selects the letter class and contributes its low two bits
to the first digit:

```
0x0–0x3 -> P     0x4–0x7 -> C     0x8–0xB -> B     0xC–0xF -> U
code = letter + (nibble & 3) + remaining three hex digits
```

So `3019` → `P3019`, `0A80` → `P0A80`, `C112` → `U0112`.

### 7.4 INF detail codes

Toyota hybrid DTCs carry a 3-digit **INF** (detail) code that narrows a fault to an area. `P0AA6`
alone means a high-voltage isolation fault, which could be the battery, the A/C system, the
transaxle or the HV DC circuitry.

The repair procedure describes a sequence:

- **`526`**, insulation resistance is low somewhere. The general detection code; it does not
  isolate an area.
- **`611` / `612` / `613` / `614`**, stored after the car isolates the area: A/C, HV battery,
  transaxle, HV DC respectively. The later localising value is added on the next trip; `526`
  remains stored, so both values can be present together.

These name an **area, not a component**. `612` spans the battery assembly, battery ECU, system
main relays, resistor, cables, battery plug and junction block.

#### 7.4.1 Reading the freeze pages (**confirmed on-car**)

*Measured.* Service `0x21` with a single-byte local identifier, on `7E2`:

```
ATSH7E2
21C6 -> 61C6 + 48 data bytes     21C9 -> 61C9 + 48 data bytes
21C7 -> 61C7 + 48 data bytes     21CA -> 61CA + 48 data bytes
21C8 -> 61C8 + 48 data bytes
```

These are five per-DTC freeze pages, not bitmap tables partitioned by the hundreds digit. An empty
page is all zero. When a DTC writes a page, its INF value and analog snapshot occupy the 48-byte
payload.

BETSY currently decodes the captured Gen2 compact layout with a 48-byte payload. A shorter
page is a failed read, even if bytes 29–30 are already present or every received byte is zero.
This is a completeness guard for the supported layout, not a universal expanded-data length:
other compact layouts require their own support-map handling before fixed offsets are safe.
When ELM prefixes a response with an application length (for example `032` = 50 bytes,
including `61 C7`), all declared bytes must arrive. Only then may trailing padding be ignored.
A declared shorter page cannot borrow padding to pass the completeness guard. Failed pages
retain their raw responses and an error note, do not increment the completed-page count, and
do not prevent the remaining pages from being read.

Four consequences for any implementation, all *measured*:

1. **Mode 22 does not exist here.** `2205CA` answers `7F2211`, and so does `22F186`, the refusal
   is service-wide, not about identifier choice. A `22 05 CA` recipe does not work on a Gen2.
2. **No batching on `7E2`.** `21C6C7C8C9CA` is refused with `7F2112`, unlike `7E3` where
   `21CED0CF` works. Five separate round trips, sharing one `ATSH`.
3. **Identifiers are one byte.** `2105CA` is parsed as identifier `05`, answering `610579CA…`.
4. **Not on the battery ECU.** `21CA` and `21C9` on `7E3` are silent.

Full identifier map answering on `7E2`:
`C0 C1 C2 C3 C4 C6 C7 C8 C9 CA CB CC D1 D3 E0 E1 E2 E3`.

#### 7.4.2 The sub-code is transmitted, not derived (**confirmed on-car**)

*Measured on a 2009 Gen2 read with `P0571` deliberately stored on `7E2`.*

When the ECU stores a DTC it writes one page, and that page carries the sub-code as a value
alongside analog snapshot data.

```
21C7 with P0571 stored, all 48 payload bytes:

80 80 80 80 00 00 04 9A 41 7E 00 61 5F 5B 5D 70 82 00 00 A0 AF 00 00 00
00 00 00 00 01 00 73 63 6B 4A 02 61 5F 5C 63 9E 6C 66 5D 65 9E 9A 80 1C
                    ^^^^^
              bytes 29-30 = 0x0073 = 115 = P0571's sub-code
```

`21C6`, `21C8`, `21C9`, `21CA` all zero at the same moment.

**Reading the sub-code.** One field, `u16` big-endian at bytes **29-30** of a page's payload. It
is the only 16-bit field in a page; every other field is 8 or 1 bit, and a three-digit code cannot
fit in 8 bits. A page that is entirely zero holds nothing. The detail field itself is
unsigned 16-bit with identity scaling and no unit. Retain any nonzero value, including values
outside 100–999, as raw evidence; decimal digit count is not a validity check. Zero yields no
INF entry even when other snapshot fields are nonzero. Unknown values remain unresolved and
must not acquire a guessed DTC relationship or description.

The detail-ID list is a separate format: it uses service `12 <snapshot index> 01 C5`,
response `52`, a high-bit presence flag and a 15-bit identifier. Its presence mask must not be
applied to the 16-bit Information field inside a `61 C6`–`61 CA` snapshot.

**A page is a stored snapshot, not live data.** The 48 bytes remained identical across an ignition
cycle. Live values would have drifted. Some numeric fields use midpoint encoding, but the
remaining data must not be treated as a uniform array of offset-binary analog values or INF
flags. Shared page definitions also contain enums, individual flags, counters, and optional
fields for other vehicle layouts.

BETSY currently decodes only the Information value from these pages. InfLayout's legacy field
lists are empty; it does not provide names, formulas, or units for additional snapshot values.
Decoding those values requires the selected ECU's byte-support mapping and effective field
metadata. Shared definitions extend through bit 455 of an expanded buffer, whereas the recorded
compact page has 48 payload bytes; expanded offsets are not direct wire offsets. A shared field
name, including rear-motor entries, is not evidence that NHW20 supports it.

**Three things this rules out**, all measured:

1. **The sub-code is not in the mode 13 response.** `13B0` returns exactly one two-byte record per
   DTC (`53 01 0571`) with no status or detail byte.
2. **There are no hidden DTC classes.** Masks `81` and `82` are refused with `7F 13 12`.
3. **Page 1 is not where a single DTC lands.** The only stored DTC owned page 2, `21C7`. So pages
   are not simply filled in order from `21C6`.

**Page assignment is not inferred.** A single-fault observation proves the field but not how five
pages are assigned when several DTCs are stored. BETSY therefore resolves an INF value against the
documented `(DTC, INF)` pairs for the DTCs reported by hybrid control:

1. One matching parent produces an exact explanation.
2. Several matching parents produce one shared explanation only when their full detail is equal.
3. No match, or conflicting matches, remains unresolved.

Raw pages stay beside every interpretation. A transport failure without any received bytes
records an error note and no raw-response entry for that request. Bytes received before a
missing prompt, read failure, or disconnect are retained under the failed request as partial
raw evidence alongside the failure; they are never decoded as a successful result. Session-level raw evidence
is cleared before each exchange, so a timeout cannot copy the preceding page or header reply.
Actual ECU refusals and adapter error text remain available as the current request's raw reply. Page order never selects a parent, and DTCs reported
only by the engine or battery-control observation cannot acquire a hybrid-control INF explanation.
Confirmation against other on-car DTCs and multi-fault combinations remains useful, but is not a
precondition for deterministic pair resolution.

**Correction to earlier revisions of this document.** Two claims here were wrong and are withdrawn.
There is no "coverage ceiling" restricting readable sub-codes to 201-663: that followed from
treating the payload as slot-numbered, and since the code is a transmitted value, any sub-code can
appear, including 1xx. And byte 30 reading `0x73` was previously called a coincidence and warned
against; it was the answer, misread. Slot numbers cannot be 1xx, but a field's value can be
anything.

#### 7.4.3 Generic mode 02 freeze frame is separate

*measured, on a 2009 Gen2 with `U0293` stored on the ECM and nothing on the HV ECU.*

What the car does answer:

| Request | `7E0` (has a stored DTC) | `7E2` (clean) |
|---|---|---|
| `020000` mode 02 supported PIDs | `4200007E1F8803` | — |
| `020200` DTC that caused the frame | `420200C293` (= U0293) | `4202000000` |
| `020201` second frame | `420201C293` | — |
| `020202` third frame | `7F0212` | — |
| `12…` KWP readFreezeFrameData | `7F1212` | `7F1212` |
| `17`, `18`, `19` | `7F..11` | `7F..11` |

Four things follow, and the distinction between the two negative-response codes carries all of
them:

1. **Generic mode 02 freeze frame works, and is richly populated.** On the ECU holding a DTC,
   15 PIDs returned data captured at the moment the fault set, and `020200` correctly names the
   causing DTC. Two frames are retained; a third is refused.
2. **`7E2` implements mode 02 too**, answering `4202000000`: a well-formed empty frame, which is
   what a clean ECU should say. The path is open, there is simply nothing in it yet.
3. **KWP `0x12` exists but no call form has been found.** All 256 single-byte subfunctions were
   swept, plus 22 multi-byte forms, on both ECUs. Every one returned NRC `0x12`
   *subFunctionNotSupported*. Compare services `17`, `18` and `19`, which return NRC `0x11`
   *serviceNotSupported*: the ECU distinguishes "no such service" from "not that argument", so
   `0x12` is recognised and merely never satisfied. Remaining possibilities are a multi-byte
   argument outside the forms tried, or a non-default diagnostic session. Session control
   (`0x10`) is deliberately never sent, see §7.5.
4. **UDS `readDTCInformation` is absent.** This is a KWP2000 car; `19xx` recipes do not apply.

Generic mode 02 is useful parallel evidence, but it is not the Toyota INF carrier. INF extraction
uses the hybrid-control freeze pages described in §7.4.1 and §7.4.2.

Every mode-02 reply, including negative responses and adapter errors, is retained under its
ECU/request raw key before classification. Refusals and transport errors produce a note naming
the ECU, request, and frame index; a failed PID does not stop the remaining PID reads. A
transport exception may retain partial bytes from its own exchange, but must not copy the
preceding command's response or decode incomplete evidence as a successful PID value.
The positive header must echo service, PID, and frame index; the mask requires all four mask
bytes. Individual PID replies require their complete value: two bytes for `02`, `0C`, `1F`,
`31`, `42`, and `4E`; one byte for `04`, `05`, `0D`, `0F`, `11`, and `46`. This guards the
current PID list; it does not decode values or establish a universal transport-message length. A failed mask stops further frame discovery while
subsequent INF reads continue. A refusal is evidence that the request was declined, not proof
that the ECU contains no recorded faults.


The generic DTC reader checks the ATH1 acknowledgement before issuing `$03`/`$07`.
ATH1 and ATH0 each require one standalone `OK` line (case-insensitive), allowing whitespace
and an optional leading echo of the current command, with no additional retries. An unconfirmed ATH1 skips that generic request and creates an error note.
ATH0 restoration is attempted even if enabling headers failed. A rejected restoration or
transport exception is reported separately without hiding the original error or discarding
an already decoded generic result. AT acknowledgements are never stored as ECU replies.
An attempted ATH1, ATH0, or ATZ invalidates the headerless response format before transport I/O.
Until a standalone ATH0 acknowledgement confirms it again, block normalized reads and raw
headerless liveness/freeze reads before transmission. Clear latest-response evidence for a blocked
request so an earlier AT reply cannot be recorded as its ECU response. The check and exchange
share the session lock. Existing application setup remains required; this tracks changes during
the session and does not discover an adapter's initial configuration.

Already decoded DTCs and format-restoration notes remain available. Generic reads whose parser
explicitly handles CAN headers and AT control exchanges remain possible. If the existing next
generic read restores ATH0 successfully, later freeze/INF reads may proceed; otherwise they
record format errors without transmitting. Shared battery polling also uses the normalized guard.
No automatic recovery, extra retries, or new ECU requests are added. A reset banner alone does
not restore permission for headerless reads; normal configuration must confirm ATH0.

Generic `$03`/`$07` parsing with ATH1 requires exactly one matching `7EA` single-frame
line. If additional `7EA` lines arrive, the reader retains the entire raw exchange and reports
that multiple responses were not decoded; it must not silently present the first line as the
complete result. Repeated identical frames, a trailing refusal, and an orphan consecutive
frame are also ambiguous and are not silently discarded. Lines from other CAN IDs do not count
as additional `7EA` responses. ISO-TP multi-frame reassembly and collection of multiple
application responses remain unimplemented; this guard does not add either capability.

### 7.5 Clearing

Not implemented in this build. The wire command itself would be straightforward to add, but clearing is deliberately omitted for now. Everything in this build is a read: nothing clears a code, runs an actuator test, or writes to an ECU.

### INF display and ECU source

The `21C6`–`21CA` pages read on `7E2` are hybrid-control snapshot evidence. The reader
resolves documented INF candidates against hybrid-control DTCs only. Preserve that source
restriction in both fault cards and text formatting: an identical DTC number reported by
Engine (`7E0`) or HV Battery (`7E3`) must not inherit a hybrid INF value or its explanation.
Use structured `DtcSource`, never a translated ECU label, for this decision. Keep all
original DTC groups and raw snapshot pages. This corrects presentation of existing candidate
resolutions; it does not prove page ownership, add a new matching algorithm, or change any
request, formula, or shared-INF resolution. Covered by synthetic duplicate-code tests;
no new on-car verification.

### Verbatim enhanced DTC and INF capture evidence

For enhanced DTC and INF reads, save the adapter's original response in the capture `raw`
map before decoding. Preserve whitespace, letter case, ELM length prefixes, and indexed
continuation lines, both for valid replies and replies that later fail parsing. Pass the
normalized application string separately to the existing decoder. Reading `lastRawResponse`
and saving it remain inside the same ECU command group lock, so another exchange cannot
replace the evidence between command completion and insertion. ECU refusals, adapter errors,
and transport failures keep their existing evidence and notes handling. The transport's `>`
prompt is consumed as a delimiter and is not part of the returned raw response; existing VIN
redaction still applies during export. Covered by synthetic formatted NHW20 replies and JSON
round-trip checks; no new on-car verification or change to formulas or ECU requests.

### Enhanced DTC completion in shared captures

Export `enhancedDtcReadsComplete` separately from `hasStoredDtcs`. The first reports whether
all planned enhanced DTC reads completed; the second reports whether any stored DTC was found.
Neither supplemental generic reads nor INF decoding determine enhanced-read completion.
A failed scan with no found codes must not be labelled a no-fault or healthy-car baseline.

The capture worker records `enhanced_dtc_reads_complete` and marks incomplete reads explicitly,
while retaining found DTCs, raw responses, and notes. Older payloads without this field remain
accepted with completion `null` (unknown), never assumed complete. The existing `fault` metadata
still means stored DTCs were found, not that absence of faults was confirmed. A completed empty
scan is described only as no stored DTCs from the completed enhanced reads, not a full-vehicle
health check. These are client/export status rules, tested locally with synthetic scans and
mocked uploads; no new ECU requests or on-car verification.

### INF page completion in shared captures

Export `infTablesResponded` separately from decoded INF values. Despite its historical name,
it counts C6-CA pages successfully decoded in the supported compact layout, including pages
with zero Information; transport replies that fail parsing do not increment it. This is
independent of enhanced DTC completion. The worker accepts integers 0-5, records the count,
and marks fewer than five decoded pages as incomplete. Older captures without the count
remain accepted with page completion unknown; absence is not interpreted as five successes.

An empty `codes` list alone establishes only that no INF value was decoded. It must not be
described as proof no page carried an INF, a decoder defect, or a one-to-one DTC/page relation.
With five successfully decoded pages and no decoded values, the narrower conclusion is that
those five compact pages contained no nonzero Information value. Retain partial decoded INF,
raw replies, and notes. The legacy `decoder_miss` metadata remains an advisory indication of
stored DTCs with no decoded INF, not proof of a bug; the capture title now says `no INF decoded`.
This is capture-status handling tested locally, with no new ECU requests or on-car verification.
