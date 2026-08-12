#!/usr/bin/env python3
"""Find out which ECUs on the bus will actually answer, by asking all of them.

BETSY talks to three addresses: the engine ECU, the HV control ECU and the battery ECU. The bus
has more, and every address beyond those three is a guess drawn from documentation rather than a
measurement. This replaces the guesses.

The method is blunt: set each candidate header in turn and ask a question any diagnostic ECU
should be able to answer. Present ECUs reply, with data or with a refusal; an address with nothing
behind it stays silent. **A refusal counts as present**, and is often the more useful answer:
`7F 13 11` means something is there that does not implement that service, which is a different
fact from nobody being home.

Three probes per address, tried in order, stopping at the first reply. All reads:

  0100    SAE generic mode 01. Emissions-related ECUs answer; body and chassis ones often do not.
  13B0    Toyota stored-DTC read, the one BETSY already relies on.
  1A87    KWP read-ECU-identification, answered by many that ignore both of the above.

Designed around the fact that most addresses are empty, because that is what wastes time:

  - **Short adapter timeout.** A dead address costs three full waits. `ATST` is set low for the
    sweep, so silence returns in a fraction of a second instead of seconds. It is restored after.
  - **Resumable.** Every result is appended immediately, and `--resume` skips addresses already
    answered in a previous run. A dropped link costs the current address, not the run.
  - **Never quiet.** Progress, percentage and ETA on every line, including the silent stretches,
    so it is always obvious whether it is working or hung.

Usage, from `public/`:

    python3 tools/probe_ecus.py                     # standard diagnostic range
    python3 tools/probe_ecus.py --wide              # every 11-bit address 0x700..0x7FF
    python3 tools/probe_ecus.py --resume            # continue the most recent run
    tail -f captures/ecus-<timestamp>.log           # watch from another terminal
"""

import argparse
import glob
import json
import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from probe_harvest import Session, check_safe, scrub  # noqa: E402
from probe_inf import norm  # noqa: E402

PROBES = ["0100", "13B0", "1A87"]

# Where diagnostic ECUs live on this platform. 7Ex is the ISO 15765-4 powertrain range; the rest
# are where documentation places chassis, body and instrument modules, which is the claim this
# tool exists to check.
STANDARD = (
    [0x7E0 + i for i in range(8)]
    + [0x7B0 + i for i in range(8)]
    + [0x7C0 + i for i in range(8)]
    + [0x7A0 + i for i in range(8)]
    + [0x7D0 + i for i in range(8)]
)

KNOWN = {
    0x7E0: "engine ECU (BETSY reads this)",
    0x7E2: "HV control ECU (BETSY reads this)",
    0x7E3: "battery ECU (BETSY reads this)",
}

# ELM327 timeout in 4 ms units. 0x19 is ~100 ms: long enough for a real ECU on a 500 kbit/s bus,
# short enough that an empty address does not cost a full second per probe. With ~40 addresses
# mostly empty this is the difference between a two-minute sweep and a ten-minute one.
FAST_TIMEOUT = "ATST19"
RESTORE_TIMEOUT = "ATSTFF"


def previous_results():
    """Addresses already settled by an earlier run, so a resume does not repeat them."""
    runs = sorted(glob.glob(os.path.join(
        os.path.dirname(os.path.abspath(__file__)), "..", "captures", "ecus-*.jsonl")))
    done = {}
    for path in runs:
        with open(path) as fh:
            for line in fh:
                try:
                    r = json.loads(line)
                except ValueError:
                    continue
                done[r["address"]] = r
    return done


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--wide", action="store_true", help="sweep 0x700..0x7FF instead")
    ap.add_argument("--resume", action="store_true", help="skip addresses already probed")
    ap.add_argument("--settle", type=float, default=4.0)
    ap.add_argument("--budget", type=float, default=900.0, help="wall-clock ceiling, seconds")
    ap.add_argument("--out", default=None)
    args = ap.parse_args()

    for p in PROBES:
        check_safe(p)

    addresses = list(range(0x700, 0x800)) if args.wide else STANDARD
    done = previous_results() if args.resume else {}
    todo = [a for a in addresses if f"{a:03X}" not in done]

    stamp = time.strftime("%Y%m%d-%H%M%S")
    base = args.out or os.path.join(
        os.path.dirname(os.path.abspath(__file__)), "..", "captures", f"ecus-{stamp}"
    )
    t0 = time.time()
    logf, jl = open(base + ".log", "w"), open(base + ".jsonl", "w")

    def log(m=""):
        line = f"[{time.time() - t0:6.1f}s] {m}"
        print(line, flush=True)
        logf.write(line + "\n")
        logf.flush()

    log(f"ECU DISCOVERY. READ-ONLY. {len(todo)} addresses to try, up to {len(PROBES)} probes each.")
    if done:
        log(f"resuming: {len(done)} address(es) already settled by earlier runs, skipping them")
        for a, r in sorted(done.items()):
            if r["present"]:
                log(f"    already found: {a} via {r['probe']}")
    log("A reply of any kind means an ECU is there. A refusal counts; silence does not.")
    log(f"budget {args.budget:.0f}s. Empty addresses return fast: adapter timeout set low.")
    log("")

    s = Session(log, args.settle)
    if not s.open(repair=True):
        log("!! no session. This adapter needs an unpair/re-pair, not a reconnect. See AGENTS.md.")
        return 2
    try:
        s.elm.send(FAST_TIMEOUT)
    except OSError:
        pass
    log("")

    found = [r for r in done.values() if r["present"]]
    silent_run, durations, aborted = 0, [], None

    for i, addr in enumerate(todo, 1):
        if time.time() - t0 > args.budget:
            aborted = "BUDGET"
            log(f"!! budget reached with {len(todo) - i + 1} addresses untried. "
                f"Rerun with --resume to continue where this stopped.")
            break

        hdr = f"{addr:03X}"
        c0 = time.time()
        answer = None
        for probe in PROBES:
            try:
                raw = s.ask(hdr, probe)
            except OSError as e:
                raw = f"(io {e})"
            h = norm(raw)
            if h or raw.strip().upper().startswith("7F"):
                answer = (probe, raw.strip(), h)
                break
        durations.append(time.time() - c0)

        rec = {
            "address": hdr,
            "present": answer is not None,
            "probe": answer[0] if answer else None,
            "raw": scrub(answer[1]) if answer else "",
            "hex": scrub(answer[2]) if answer else "",
            "known": KNOWN.get(addr, ""),
        }
        jl.write(json.dumps(rec) + "\n")
        jl.flush()

        per = sorted(durations)[len(durations) // 2]
        eta = per * (len(todo) - i)
        pos = f"[{i}/{len(todo)} {i * 100 // len(todo):3d}%  eta {eta:4.0f}s]"

        if answer:
            found.append(rec)
            tag = KNOWN.get(addr, "NEW, not currently read")
            log(f"{pos} {hdr}  ANSWERS via {answer[0]}: {scrub(answer[1])[:34]}   <- {tag}")
            silent_run = 0
        else:
            silent_run += 1
            # Say something on every line even when nothing is found, so a long empty stretch
            # never looks like a hang. This is the whole point of the progress format.
            log(f"{pos} {hdr}  silent ({silent_run} in a row)")
            if silent_run >= 24:
                log("    !! 24 silent in a row, which may be a dropped link rather than "
                    "empty addresses. Rebuilding the session to find out.")
                if not s.rebuild():
                    aborted = "LINK LOST"
                    log("!! could not rebuild. Rerun with --resume; nothing already found is lost.")
                    break
                try:
                    s.elm.send(FAST_TIMEOUT)
                except OSError:
                    pass
                silent_run = 0

    if s.elm:
        try:
            s.elm.send(RESTORE_TIMEOUT)
        except OSError:
            pass
        s.elm.close()

    log("")
    log("=" * 70)
    log(f"{len(found)} ECU(s) answered" + (f"  [{aborted}, run incomplete]" if aborted else ""))
    for r in sorted(found, key=lambda r: r["address"]):
        log(f"  {r['address']}  via {r['probe']:<5} {r['known'] or 'NOT CURRENTLY READ BY BETSY'}")
    new = [r for r in found if not r["known"]]
    log("")
    if new:
        log(f"{len(new)} address(es) BETSY does not read: "
            f"{', '.join(sorted(r['address'] for r in new))}")
        log("Each is a candidate for a DTC read; the same 13B0 sweep should work.")
    else:
        log("Nothing beyond the three already read. The documented extras do not answer,")
        log("which is itself worth knowing and stops anyone chasing them again.")
    log("=" * 70)
    logf.close()
    jl.close()
    print(f"\nsaved {base}.log and {base}.jsonl", flush=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())
