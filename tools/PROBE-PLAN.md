# In-car probe plan, MIN / MAX

Read this in the car instead of asking. All commands read-only: no `14`/`04` clear,
no `10 xx` session control, no `2F`/`31` routine control.

**Setup:** dongle in, IG-ON (Power twice, no brake), Android app closed, adapter paired
in System Settings → Bluetooth (PIN `1234` or `0000`).

```sh
python3 tools/probe_inf.py --list                      # find the port
python3 tools/probe_inf.py --port /dev/cu.XXX --quick  # MIN
```

---

## MIN, 5 exchanges, ~40 seconds

The only run that must happen. Works on a fault-free car.

| # | Header | Send | Testing for |
|---|---|---|---|
| 1 | `7E3` | `21CE` | Adapter, bus and service 21 all alive. If this fails, nothing else means anything |
| 2 | `7E2` | `13B0` | Is a fault stored? Determines what the rest can prove |
| 3 | `7E2` | `2101` | **Does `7E2` implement service 21?** The load-bearing unknown |
| 4 | `7E2` | `21CA` | Detail table 5. **The payload length is the falsifiable bit** |
| 5 | `7E2` | `2205CA` | Control. Expect `7F 22 11` |

### What each outcome means

**#3 `2101`**, the question the whole spec rests on:

| Reply | Meaning | Next |
|---|---|---|
| `61 01 …` | Service 21 works on `7E2` | Continue to #4, spec is on track |
| `7F 21 31` | Service 21 right, identifier wrong | **Run MAX sweep**, the table is findable |
| `7F 21 11` | Service 21 absent on `7E2` | **Spec §6 is wrong.** Do not publish. Rewrite |
| `NO DATA` | Silence, discriminates nothing | Continue to #4 anyway; `2101` may just be an unsupported LID |

**#4 `21CA`**, the length test, which is why a healthy car is still worth driving to:

| Reply | Meaning |
|---|---|
| `61 CA` + **57 bytes** | **Best case.** Car corroborates a layout derived only from a binary. All-zero payload is expected and fine |
| `61 CA` + other length | Tables are real but this is a **different generation**. Layout needs regenerating, not discarding |
| `7F 21 31` | `CA` wrong identifier → MAX sweep |
| `7F 21 11` | As #3 `7F 21 11` |

**#5 `2205CA`** must reproduce `7F 22 11`. If it *succeeds*, everything we concluded is
wrong and the original published page was right, stop and re-think.

### MIN success criterion

We can publish honestly if #3 or #4 returns **any positive `61` response**. Length 57 is a
bonus that upgrades §6 from "specified" to "specified and shape-confirmed on-car".

---

## MAX, if time, battery and patience allow

Run in this order; each step is conditional on the one before.

```sh
python3 tools/probe_inf.py --port /dev/cu.XXX             # full 14-exchange probe
python3 tools/probe_inf.py --port /dev/cu.XXX --sweep 7E2 # 21C0..21DF, 32 exchanges
python3 tools/probe_inf.py --port /dev/cu.XXX --sweep 7E3 --range C0:E5
```

| Step | Testing for | Run it when |
|---|---|---|
| Full probe (14) | `21C9` too, all five in one request, `22F186` control, `7E3` tables, masks `1381`/`1382` | Always, if MIN was not disastrous |
| `--sweep 7E2` | Which identifiers `7E2` answers at all | Any `7F 21 31`, or to map the ECU |
| `--sweep 7E3 --range C0:E5` | Whether detail tables sit on the battery ECU | `7E2` refuses everything |
| Re-run MIN in **READY** | Whether any refusal was `conditionsNotCorrect` | Any `7F xx 22` |

### Specific MAX questions

1. **Does `21C6C7C8C9CA` work as one request?** If yes, a five-table read is one round
   trip and the app's poll design gets simpler.
2. **Do `C9` and `CA` return the same length?** They should, same 63-slot shape. A
   difference means the parallel-table model is wrong.
3. **Does `7E2` answer `21CE`/`21D0`?** Would show the battery identifiers are not
   `7E3`-exclusive, and tells us how ECU-specific the identifier space is.
4. **Do masks `1381`/`1382` return anything `13B0` doesn't?** Free, and relevant to
   whether we are seeing all stored codes.

---

## What NONE of this can settle

Whether ordinal 11 really means INF **611** rather than 612 or 613. That needs a car with
a stored fault whose sub-code is independently known, a repair shop with a live `P0AA6`,
cross-checked against the published `526-611` / `526-612` pairs.

Everything else in §6 is reachable from your own trouble-free car.

---

## Traffic discipline

On hotspot: no web access, batched commands, short replies. The script prints a live
`[n/5]` line per exchange, colour-codes each verdict, and ends with a plain-English
**WHAT THIS MEANS** block, so the terminal answers most questions without a model call.

Two files land in `scratch/probe-logs/`:

- `probe-<stamp>.log`, human-readable transcript, every `>>` and `<<`
- `probe-<stamp>.json`, structured: per exchange the header, request, raw reply,
  normalised hex, byte count and verdict

**Bring the `.json` home.** Back on wifi it supports re-analysis without re-running
anything: byte-level diffing between tables, checking `C9` and `CA` agree on length,
cross-referencing non-zero slots against the layout, and regenerating the spec's §6 from
measured rather than predicted values.
