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

`NO DATA`, `ERROR`, `STOPPED`, `UNABLE TO CONNECT`, `BUFFER FULL` and `?` are status, not data.
Strip them before hex extraction or `NO DATA` reduces to the plausible-looking `DAA`.

### 2.1 Indexing

`u8(k)`, `u16(k)` and `bytes(k, n)` are indexed in **hex characters**, not bytes, so offsets read
directly off the wire dumps. `u16` is big-endian. Callers length-guard with `requireLength`.

### 2.2 No silent stale values

A missing response tag raises. A short body is a truncated read, not a zeroed reading. Never
pad, never decode a partial frame, never surface a previous frame as current. On a battery
diagnostic the difference between "no fault" and "we failed to ask" is the whole product.

---

## 3. Vehicle detection

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
u16 offset-binary:  value = u16 / 100 - 327.68      (volts, and °C)
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

**`21CE`**, SOC, current, block voltages:

| Field | Location | Formula |
|---|---|---|
| SOC % | byte 0 | `u8 / 2` |
| Pack current A | bytes 1–2 | `u16 / 100 - 327.68`, negative = discharge |
| Block voltage `[0 .. min(N,17)-1]` | bytes 3+, 2 bytes each | `u16 / 100 - 327.68` |

**`21CF`**, if `N > 17`, blocks 18..N appear first; skip them. From offset `k`:

| Field | Location | Formula |
|---|---|---|
| 12 V auxiliary | `k+3` | `u8 * 0.2 - 25.6` |
| Max charge (hp) | `k+4` | `(u8 / 2 - 64) * 1.34` |
| Max discharge (hp) | `k+5` | `(u8 / 2 - 64) * 1.34` |
| ΔSOC | `k+6` | `u8 * 0.01` |
| Temps TB1–TB3 °C | `k+10`, `k+12`, `k+14` | `u16 / 100 - 327.68` |

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

### 5.6 Speed and RPM

`010C0D` returns both in one reply: `41 0C <rpm u16> 0D <speed u8>`.

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
| Normalized response starts with `41` | yes | Responding |
| Normalized response starts with `7F` | yes | Negative response + NRC meaning |
| `NO DATA` / `CAN ERROR` / `BUS ERROR` | no | No response (NO DATA) |
| Transport timeout | no | No response (timeout) |
| `?` / `STOPPED` / `UNABLE TO CONNECT` | no | Adapter error |
| Other hex | no | Unexpected response |

A clean "no DTCs" result is only meaningful if liveness says the ECU is responding.

#### 7.1.2 Gen2 generic OBD `$03` / `$07` on 7E2 (*experimental*)

After KWP2000 group reads and before INF freeze pages, Gen2 sweeps also:

1. `ATH1` (headers on) so the responder CAN ID is preserved in the raw log  
2. `03` (stored) and `07` (pending)  
3. `ATH0` in a `finally` block  

The raw line containing `7EA` is stripped of CAN ID + ISO-TP PCI before count-byte decode
(tags `43` / `47`). Mode `$07` is **supplemental**: Toyota enhanced / non-emissions faults may
not appear there. A clean `$03`/`$07` result must **not** be interpreted as "HV ECU has no
Toyota DTCs." KWP2000 groups, generic OBD, and INF remain three separate observations.

New raw keys: `7E2/0100`, `7E2/03`, `7E2/07`.

### 7.2 Response layout

`53 <count> <DTC 2B> …`, no per-DTC status byte. `5300` means zero stored codes. Where the count
overstates the body, trust the body length.

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
fit in 8 bits. A page that is entirely zero holds nothing.

**A page is a stored snapshot, not live data.** The 48 bytes remained identical across an ignition
cycle. Live values would have drifted. The remaining bytes are analog readings, offset-binary,
which is why a car at rest shows `80 80 80 80`: those are midpoints, not flags.

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

Raw pages stay beside every interpretation. Page order never selects a parent, and DTCs reported
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

### 7.5 Clearing

Not implemented, deliberately. Everything in this app is a read: nothing clears a code, runs an
actuator test, or writes to an ECU.
