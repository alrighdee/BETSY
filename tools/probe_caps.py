#!/usr/bin/env python3
"""Read-only capability probe for the Gen2 HV ECU (7E2) and ECM (7E0).

Answers two questions that do NOT need a stored fault, and so can be run on a healthy car:

  1. Does 7E2 answer SAE generic mode 01 (`0100`)? BETSY's liveness probe falls back to `21C6`
     because nothing had ever tested this. A positive answer deletes the fallback.
  2. Does 7E2 implement a freeze-frame service at all? The factory manual puts the INF detail
     codes in freeze-frame items "INFORMATION 1..5" attached to a DTC, which is a different
     read from the `21C6`..`21CA` tables BETSY currently uses. A *refusal* (`7F <svc> <nrc>`)
     is the informative answer here: it proves the service exists and merely has nothing to
     report on a clean car. Silence proves nothing.

Design notes, because this gets run sitting in a car:

  - **Read-only, enforced.** Every command is checked against SAFE_SERVICES before it is sent.
    Clear-DTC (`04`, `14`), ECU reset (`11`), security access (`27`) and every write service are
    rejected by the script itself, not merely absent from the plan. Clearing would destroy the
    freeze frame and operation history, so a typo must not be able to do it.
  - **Hard gate.** Step 1 must answer or the run aborts. A sleeping bus returns NO DATA to
    everything, which is indistinguishable from "not supported" and is how an earlier probe run
    produced a page of results that proved nothing.
  - **Bounded.** Per-command deadline, consecutive-timeout abort, and a whole-run budget. The
    worst case is stated up front rather than discovered while sitting in the driver's seat.
  - **Observable.** Every step prints what it is asking, what came back, what that means, and
    the running ETA.

Usage:
    python3 tools/probe_caps.py                     # real car
    python3 tools/probe_caps.py --dry-run answers   # rehearse, no hardware
"""

import argparse
import json
import os
import re
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from probe_inf import Elm, norm  # noqa: E402  (shares the adapter driver)

# Service bytes this script is permitted to send. Diagnostic *reads* only. Anything that
# clears, resets, writes, unlocks or reprograms is absent by construction, and send() refuses
# any command whose service byte is not here even if a plan entry asks for it.
SAFE_SERVICES = {
    "01",  # generic: current data / supported PIDs
    "02",  # generic: freeze frame data
    "03",  # generic: stored DTCs
    "07",  # generic: pending DTCs
    "09",  # generic: vehicle info (VIN)
    "12",  # KWP2000: readFreezeFrameData
    "13",  # KWP2000: readDiagnosticTroubleCodesByStatus
    "17",  # KWP2000: readStatusOfDTC
    "18",  # KWP2000: readDTCByStatus
    "1A",  # KWP2000: readEcuIdentification
    "21",  # KWP2000: readDataByLocalIdentifier
    "22",  # KWP2000: readDataByCommonIdentifier
}

# Never sendable, listed so the intent is explicit to a reader in a hurry.
FORBIDDEN = {
    "04": "clear DTCs (destroys freeze frame + INF)",
    "14": "clear diagnostic information",
    "11": "ECU reset",
    "27": "security access",
    "2E": "write data by identifier",
    "2F": "input/output control",
    "31": "routine control",
    "34": "request download",
    "35": "request upload",
    "36": "transfer data",
    "3B": "write data by local identifier",
}


class Step:
    def __init__(self, header, cmd, why, interpret, gate=False):
        self.header = header
        self.cmd = cmd
        self.why = why
        self.interpret = interpret
        self.gate = gate


def service_of(cmd: str) -> str:
    return cmd[:2].upper()


def check_safe(cmd: str) -> None:
    svc = service_of(cmd)
    if svc in FORBIDDEN:
        raise SystemExit(f"REFUSED: {cmd} is service {svc}, {FORBIDDEN[svc]}")
    if svc not in SAFE_SERVICES:
        raise SystemExit(f"REFUSED: {cmd} is service {svc}, not in the read-only allowlist")


# ---------------------------------------------------------------- interpretations


def i_gate(raw, h):
    if not h:
        return "FAIL", "no answer. Bus asleep or adapter not talking to the car."
    if h.startswith("53"):
        n = int(h[2:4], 16) if len(h) >= 4 else 0
        return "OK", f"positive 53, {n} stored DTC(s) on 7E2. Bus alive."
    if h.startswith("7F"):
        return "OK", f"negative response {h}, but the ECU answered. Bus alive."
    return "WARN", f"unexpected {h}. Treating bus as alive."


def i_mode01(raw, h):
    if not h:
        return "NO", (
            "silent. 7E2 does not answer generic mode 01, or did not this time. "
            "The 21C6 liveness fallback stays."
        )
    if h.startswith("41"):
        return "YES", (
            f"positive 41, supported-PID mask {h[4:]}. 7E2 DOES answer generic mode 01. "
            "The 21C6 fallback can be deleted."
        )
    if h.startswith("7F"):
        return "NO", (
            f"negative response {h}. The ECU answered but refuses mode 01, so it is alive "
            "but generic mode 01 is not a usable liveness probe. Fallback stays."
        )
    return "WARN", f"unexpected {h}"


# `0902` returns the VIN. The probe below asks `0902` deliberately, because whether the HV ECU
# answers mode 09 at all is a real question about the ECU. The answer is therefore PII and the
# written log must be scrubbed before it is committed: see the redaction in probe_harvest.scrub
# and the note in CaptureData. Never widen this to print the payload.
def i_mode09(raw, h):
    if not h:
        return "NO", "silent. Mode 09 is not a usable liveness probe on 7E2 either."
    if h.startswith("49"):
        return "YES", f"positive 49. Mode 09 works on 7E2: {h}"
    if h.startswith("7F"):
        return "NO", f"negative response {h}. Service exists, refused here."
    return "WARN", f"unexpected {h}"


def i_freeze_kwp(raw, h):
    if not h:
        return "SILENT", (
            "silent. Proves nothing: could be unsupported, could be a clean car with no frame. "
            "This is the ambiguous outcome."
        )
    if h.startswith("7F"):
        nrc = h[4:6] if len(h) >= 6 else "??"
        meaning = {
            "11": "service not supported -> service 12 is NOT implemented, look elsewhere",
            "12": "subfunction not supported -> service 12 EXISTS, wrong parameters",
            "22": "conditions not correct -> service 12 EXISTS, nothing stored to report",
            "31": "request out of range -> service 12 EXISTS, wrong record number",
        }.get(nrc, "unmapped NRC")
        verdict = "NO" if nrc == "11" else "YES"
        return verdict, f"negative response, NRC {nrc}: {meaning}"
    if h.startswith("52"):
        return "YES", f"POSITIVE 52. Freeze frame data returned on a clean car: {h}"
    return "WARN", f"unexpected {h}"


def i_freeze_generic(raw, h):
    if not h:
        return "SILENT", "silent. Expected on a car with no stored emissions DTC. Proves nothing."
    if h.startswith("42"):
        return "YES", f"POSITIVE 42, generic freeze frame exists: {h}"
    if h.startswith("7F"):
        nrc = h[4:6] if len(h) >= 6 else "??"
        return "YES", f"negative response NRC {nrc}. Mode 02 is implemented and refused."
    return "WARN", f"unexpected {h}"


def i_ecm_dtc(raw, h):
    if not h:
        return "SILENT", "no answer from the ECM."
    if h.startswith("53"):
        n = int(h[2:4], 16) if len(h) >= 4 else 0
        codes = [h[4 + i * 4 : 8 + i * 4] for i in range(n)] if len(h) >= 4 + n * 4 else []
        if n == 0:
            return "CLEAN", "positive 53, 0 stored DTC(s). U0293 is gone, self-cleared or cleared."
        return "STORED", f"positive 53, {n} stored DTC(s), raw {codes}. A real DTC to probe against."
    return "WARN", f"unexpected {h}"


def i_generic_dtc(raw, h):
    if not h:
        return "SILENT", "no answer."
    if h.startswith("43"):
        return "OK", f"positive 43: {h}"
    if h.startswith("7F"):
        return "OK", f"negative response {h}"
    return "WARN", f"unexpected {h}"


PLAN = [
    Step("7E2", "13B0", "GATE: bus alive, and is 7E2 clean?", i_gate, gate=True),
    Step("7E2", "0100", "does 7E2 answer generic mode 01?", i_mode01),
    Step("7E2", "0902", "does 7E2 answer generic mode 09 (VIN)?", i_mode09),
    Step("7E2", "1200", "KWP readFreezeFrameData: does the service exist?", i_freeze_kwp),
    Step("7E2", "12", "same, bare form, in case 1200 was a parameter error", i_freeze_kwp),
    Step("7E2", "020200", "generic mode 02 freeze frame, PID 02 frame 0", i_freeze_generic),
    Step("7E0", "13B0", "is U0293 still stored on the ECM?", i_ecm_dtc),
    Step("7E0", "03", "generic stored DTCs on the ECM", i_generic_dtc),
    Step("7E0", "020200", "freeze frame on the ECM, against a real DTC if one is stored", i_freeze_generic),
]

INIT = ["ATE0", "ATL0", "ATS0", "ATH0", "ATSP0", "ATST32"]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", default="/dev/cu.OBDII")
    ap.add_argument("--out", default=None)
    ap.add_argument("--timeout", type=float, default=4.0, help="per-command deadline, seconds")
    ap.add_argument("--budget", type=float, default=150.0, help="whole-run wall-clock budget")
    ap.add_argument("--max-silent", type=int, default=4, help="abort after N consecutive silences")
    ap.add_argument("--mac", default="00-1D-A5-68-F4-56", help="dongle address for link cycling")
    ap.add_argument("--link-tries", type=int, default=6, help="full Bluetooth link cycles to try")
    ap.add_argument("--link-settle", type=float, default=4.0, help="seconds to wait per link step")
    ap.add_argument("--dry-run", choices=["answers", "silent", "dead"], default=None)
    args = ap.parse_args()

    for s in PLAN:
        check_safe(s.cmd)

    t0 = time.time()
    lines = []

    def log(msg=""):
        stamp = f"[{time.time() - t0:6.1f}s] "
        text = stamp + msg
        print(text, flush=True)
        lines.append(text)

    log(f"BETSY capability probe. READ-ONLY. {len(PLAN)} steps.")
    log(f"budget {args.budget:.0f}s, per-command deadline {args.timeout:.1f}s")
    log(f"worst case if the car is asleep: aborts after step 1, about {args.timeout + 20:.0f}s")
    log("")

    elm = _open(args, log, t0)
    if elm is None:
        _write(args, lines, [], t0, "ADAPTER_UNREACHABLE")
        return 2

    results, silent_streak, aborted = [], 0, None
    durations = []

    for i, step in enumerate(PLAN, 1):
        if time.time() - t0 > args.budget:
            aborted = "BUDGET_EXCEEDED"
            log(f"!! budget of {args.budget:.0f}s exhausted, stopping")
            break

        eta = ""
        if durations:
            per = sorted(durations)[len(durations) // 2]
            eta = f"  eta ~{per * (len(PLAN) - i + 1):.0f}s"
        log(f"[{i}/{len(PLAN)}] {step.header} {step.cmd} - {step.why}{eta}")

        c0 = time.time()
        raw = _exchange(elm, step.header, step.cmd, args)
        dt = time.time() - c0
        durations.append(dt)
        h = norm(raw)
        verdict, text = step.interpret(raw, h)
        log(f"        raw: {raw.strip() or '(silence)'}")
        log(f"        => {verdict}: {text}  [{dt:.1f}s]")
        log("")

        results.append(
            {
                "step": i,
                "header": step.header,
                "cmd": step.cmd,
                "why": step.why,
                "raw": raw.strip(),
                "hex": h,
                "verdict": verdict,
                "note": text,
                "seconds": round(dt, 2),
            }
        )

        if step.gate and verdict == "FAIL":
            aborted = "GATE_FAILED"
            log("!! GATE FAILED. The car is not answering, so nothing below would mean anything.")
            log("!! Check: is the car at IG-ON or READY? Is the dongle seated? Is BETSY holding")
            log("!! the Bluetooth link on the phone? Fix and rerun. Stopping now rather than")
            log("!! collecting a page of NO DATA that proves nothing.")
            break

        silent_streak = silent_streak + 1 if not h else 0
        if silent_streak >= args.max_silent:
            aborted = "BUS_WENT_AWAY"
            log(f"!! {silent_streak} silent replies in a row. The bus went away mid-run. Stopping.")
            break

    elm.close()
    _summary(log, results, aborted, t0)
    _write(args, lines, results, t0, aborted)
    return 0


def _bt(action, mac):
    """Drive the Bluetooth link with blueutil. Returns True if the call succeeded."""
    import subprocess

    try:
        subprocess.run(
            ["blueutil", f"--{action}", mac], capture_output=True, timeout=20, check=False
        )
        return True
    except (OSError, subprocess.SubprocessError):
        return False


def _bt_connected(mac):
    import subprocess

    try:
        out = subprocess.run(
            ["blueutil", "--connected"], capture_output=True, text=True, timeout=15
        ).stdout
    except (OSError, subprocess.SubprocessError):
        return False
    return mac.lower().replace(":", "-") in out.lower()


def _open(args, log, t0):
    """Get a talking adapter, rebuilding the Bluetooth link as often as it takes.

    This adapter answers ATZ on a *freshly established* RFCOMM link and goes mute on
    subsequent opens until the link is torn down and rebuilt. On an on-car read, eight of eleven
    probe runs died on a silent ATZ for this reason and were got through by retrying. So a
    silent ATZ is not a verdict, it is a prompt to cycle the link and try again.
    """
    if args.dry_run:
        log(f"DRY RUN ({args.dry_run}), no hardware touched")
        return _Fake(args.dry_run, log)

    for attempt in range(1, args.link_tries + 1):
        if attempt > 1 or not _bt_connected(args.mac):
            log(f"link cycle {attempt}/{args.link_tries}: disconnect, settle, reconnect ...")
            _bt("disconnect", args.mac)
            time.sleep(args.link_settle)
            _bt("connect", args.mac)
            # the /dev node is recreated when the link comes up; wait for it rather than guess
            for _ in range(int(args.link_settle * 10)):
                if os.path.exists(args.port):
                    break
                time.sleep(0.1)
            time.sleep(args.link_settle)
            if not _bt_connected(args.mac):
                log("   link did not come up")
                continue
            log("   link up")

        try:
            elm = Elm(args.port, lambda m: None, timeout=args.timeout)
        except OSError as e:
            log(f"   cannot open {args.port}: {e}")
            continue

        if elm.wake():
            log(f"adapter awake on link attempt {attempt}")
            for c in INIT:
                elm.send(c)
            log(f"initialised ({len(INIT)} AT commands)")
            log("")
            return elm

        log(f"   silent ATZ on attempt {attempt}, cycling the link")
        elm.close()

    log(f"!! adapter stayed silent across {args.link_tries} full link cycles.")
    log("!! On 8 Aug this adapter needed several tries before it answered, so this is its")
    log("!! normal behaviour rather than a fault, but it is not answering today.")
    log("!! Last resort: unplug the dongle from the OBD port, wait for the LEDs to go dark,")
    log("!! plug it back in, and rerun.")
    return None


def _exchange(elm, header, cmd, args):
    elm.send(f"ATSH{header}")
    return elm.send(cmd)


def _summary(log, results, aborted, t0):
    log("=" * 70)
    log("SUMMARY")
    for r in results:
        log(f"  {r['step']}. {r['header']} {r['cmd']:<8} {r['verdict']:<7} {r['note'][:70]}")
    log("=" * 70)
    log("WHAT THIS SETTLES")

    def find(cmd, header="7E2"):
        return next((r for r in results if r["cmd"] == cmd and r["header"] == header), None)

    m01 = find("0100")
    if m01 is None:
        log("  ? mode 01 on 7E2: not reached")
    elif m01["verdict"] == "YES":
        log("  + 7E2 answers 0100. DELETE the 21C6 liveness fallback in DtcReader.")
    else:
        log("  + 7E2 does NOT answer 0100. The 21C6 fallback was necessary. Keep it.")

    fz = [find("1200"), find("12"), find("020200")]
    fz = [r for r in fz if r]
    if any(r["verdict"] == "YES" for r in fz):
        log("  + a freeze-frame service EXISTS on this car. Worth building the read.")
    elif fz and all(r["verdict"] in ("SILENT", "NO") for r in fz):
        log("  ? freeze frame: no positive evidence. Silence is ambiguous, not a refutation.")

    ecm = find("13B0", "7E0")
    if ecm:
        log(f"  + ECM (7E0): {ecm['note'][:70]}")
    ecm_frame = find("020200", "7E0")
    if ecm and ecm["verdict"] == "STORED" and ecm_frame:
        if ecm_frame["verdict"] == "YES":
            log("  + freeze frame READ BACK against a real stored DTC. The mechanism works.")
        else:
            log("  ? a DTC is stored on the ECM yet its freeze frame did not read. Worth a look.")

    log("=" * 70)
    if aborted:
        log(f"RUN INCOMPLETE: {aborted}")
    else:
        log(f"RUN COMPLETE in {time.time() - t0:.0f}s")


def _write(args, lines, results, t0, aborted):
    stamp = time.strftime("%Y%m%d-%H%M%S")
    base = args.out or os.path.join(
        os.path.dirname(os.path.abspath(__file__)), "..", "captures", f"caps-{stamp}"
    )
    with open(base + ".log", "w") as f:
        f.write("\n".join(lines) + "\n")
    with open(base + ".json", "w") as f:
        json.dump(
            {"seconds": round(time.time() - t0, 1), "aborted": aborted, "steps": results}, f, indent=2
        )
    print(f"\nwrote {base}.log and {base}.json")


class _Fake:
    """Canned adapter for rehearsing the script with no car attached."""

    def __init__(self, scenario, log):
        self.scenario = scenario
        self.header = ""

    def send(self, cmd):
        time.sleep(0.05)
        if cmd.startswith("ATSH"):
            self.header = cmd[4:]
            return "OK"
        if cmd.startswith("AT"):
            return "OK"
        if self.scenario == "dead":
            return "NO DATA"
        table = {
            ("7E2", "13B0"): "5300",
            ("7E2", "0902"): "NO DATA",
            ("7E2", "1200"): "7F1222",
            ("7E2", "12"): "7F1212",
            ("7E2", "020200"): "NO DATA",
            ("7E0", "13B0"): "5301C293",
            ("7E0", "03"): "430100",
            ("7E0", "020200"): "4202C293",
        }
        if self.scenario == "answers":
            table[("7E2", "0100")] = "4100FFE0FFE0"
        else:
            table[("7E2", "0100")] = "NO DATA"
        return table.get((self.header, cmd), "NO DATA")

    def wake(self):
        return True

    def close(self):
        pass


if __name__ == "__main__":
    sys.exit(main())
