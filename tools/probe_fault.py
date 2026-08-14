#!/usr/bin/env python3
"""Read everything that matters while a DTC is stored on the hybrid-control ECU.

The transmitted INF value is a big-endian field at bytes 29-30 of a populated `21C6`..`21CA`
freeze page. This probe retains all five pages beside the DTC reads so another fault, vehicle or
multi-fault combination can extend the confirmed coverage without inferring ownership from page
order.

Ordering is deliberate. The DTC confirmation runs first, and the INF pages come before exploratory
reads so a mid-run link loss costs the least. Read-only throughout; clearing would destroy the
freeze page and its INF value (§7.5).
"""

import json
import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from probe_harvest import Session, check_safe, scrub  # noqa: E402
from probe_inf import norm  # noqa: E402

BASELINE_PAGES = {  # what these read on the clean car
    "21C6": "0" * 96, "21C7": "0" * 96, "21C8": "0" * 96,
    "21C9": "0" * 96, "21CA": "0" * 96,
}

PLAN = [
    ("7E2", "13B0", "DID IT STORE? HV ECU stored-DTC mask. Everything else depends on this"),
    ("7E0", "13B0", "and the ECM, to see where the fault actually landed"),
    ("7E2", "21C6", "INF freeze page 1, compare against the all-zero baseline"),
    ("7E2", "21C7", "INF freeze page 2"),
    ("7E2", "21C8", "INF freeze page 3"),
    ("7E2", "21C9", "INF freeze page 4"),
    ("7E2", "21CA", "INF freeze page 5"),
    ("7E2", "020200", "mode 02: which DTC caused the freeze frame?"),
    ("7E2", "020000", "mode 02: which freeze-frame PIDs are populated now?"),
    ("7E2", "020201", "second freeze frame?"),
    ("7E2", "03", "generic stored DTCs on the HV ECU"),
    ("7E2", "07", "generic pending DTCs"),
    ("7E2", "12", "does service 12 answer differently now there IS data?"),
    ("7E2", "1200", "same, with a record number"),
    ("7E2", "1201", "same, record 1"),
    ("7E2", "21CB", "neighbouring identifiers, in case INF lands outside C6..CA"),
    ("7E2", "21CC", "neighbouring identifier"),
    ("7E2", "21D1", "identifier that answered on the clean car"),
    ("7E2", "21D3", "identifier that answered on the clean car"),
    ("7E2", "0100", "liveness, for the record"),
]

for _, c, _ in PLAN:
    check_safe(c)


def main():
    stamp = time.strftime("%Y%m%d-%H%M%S")
    base = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "captures", f"fault-{stamp}")
    t0 = time.time()
    logf, jl = open(base + ".log", "w"), open(base + ".jsonl", "w")

    def log(m=""):
        line = f"[{time.time() - t0:6.1f}s] {m}"
        print(line, flush=True)
        logf.write(line + "\n")
        logf.flush()

    log(f"FAULT-ACTIVE READ. READ-ONLY. {len(PLAN)} questions.")
    log("")
    s = Session(log, 4.0)
    if not s.open(repair=True):
        log("!! no session")
        return 2
    log("")

    results, silent = [], 0
    for i, (hdr, cmd, why) in enumerate(PLAN, 1):
        try:
            raw = s.ask(hdr, cmd)
        except OSError as e:
            raw = f"(io {e})"
        h = norm(raw)
        note = ""
        if cmd in BASELINE_PAGES and h:
            payload = h[h.find("61") + 4:] if "61" in h else ""
            if payload and set(payload) != {"0"}:
                note = "  *** CHANGED FROM BASELINE, NON-ZERO ***"
            elif payload:
                note = "  (still all zero)"
        if cmd == "13B0" and h.startswith("53"):
            n = int(h[2:4], 16)
            codes = [h[4 + k * 4:8 + k * 4] for k in range(n)]
            note = f"  <<< {n} DTC(s): {codes} >>>" if n else "  <<< NOTHING STORED >>>"
        log(f"[{i}/{len(PLAN)}] {hdr} {cmd:<8} {scrub(raw)[:50]}{note}")
        results.append({"header": hdr, "cmd": cmd, "why": why, "raw": scrub(raw), "hex": scrub(h)})
        jl.write(json.dumps(results[-1]) + "\n")
        jl.flush()
        silent = silent + 1 if not h else 0
        if silent >= 4 and not s.rebuild():
            log("!! session lost")
            break
        if silent >= 4:
            silent = 0

    if s.elm:
        s.elm.close()
    log("")
    log("=" * 68)
    changed = [r for r in results if r["cmd"] in BASELINE_PAGES
               and r["hex"] and set(r["hex"][r["hex"].find("61") + 4:] or "0") != {"0"}]
    if changed:
        log("*** POPULATED INF FREEZE PAGE(S). ***")
        for r in changed:
            log(f"  {r['cmd']}  {r['hex']}")
    else:
        log("INF freeze pages still all zero.")
        log("If a DTC is stored on 7E2, retain this result: some faults may carry no INF value.")
    log("=" * 68)
    logf.close()
    jl.close()
    print(f"\nsaved {base}.log and {base}.jsonl", flush=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())
