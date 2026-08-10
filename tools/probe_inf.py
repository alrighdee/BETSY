#!/usr/bin/env python3
"""Read-only INF sub-code probe over a Bluetooth SPP ELM327, driven from macOS.

Answers the open question in the INF spec: does the HV ECU (7E2) implement KWP2000
service 0x21, and does local identifier CA/C9 reach the detail tables?

Every command here is a read. Nothing writes, clears, or changes diagnostic session
state, no 14/04 clear, no 10 xx session control, no 2F/31 routine control.

    python3 tools/probe_inf.py --list
    python3 tools/probe_inf.py --port /dev/cu.OBDII

Stdlib only (termios); no pip install, so it works with no internet in the car.
"""

from __future__ import annotations

import argparse
import glob
import json
import os
import re
import sys
import termios
import time

PROMPT = b">"


class Elm:
    def __init__(self, port: str, log, timeout: float = 6.0):
        self.log = log
        self.timeout = timeout
        self.fd = os.open(port, os.O_RDWR | os.O_NOCTTY | os.O_NONBLOCK)
        a = termios.tcgetattr(self.fd)
        # raw 8N1; baud is nominal over RFCOMM but must be set to something valid
        a[0] = a[1] = a[3] = 0
        a[2] = termios.CS8 | termios.CREAD | termios.CLOCAL
        a[4] = a[5] = termios.B115200
        a[6][termios.VMIN] = 0
        a[6][termios.VTIME] = 0
        termios.tcsetattr(self.fd, termios.TCSANOW, a)
        termios.tcflush(self.fd, termios.TCIOFLUSH)

    def close(self):
        os.close(self.fd)

    def wake(self, tries: int = 8) -> bool:
        """Nudge the adapter until it answers.

        macOS brings the RFCOMM link up lazily on first open, so an ATZ written
        immediately after open can be swallowed with no reply and no error. Retry
        with a short timeout rather than treating the first silence as a verdict.
        """
        saved, self.timeout = self.timeout, 2.5
        try:
            for i in range(tries):
                if "ELM" in self.send("ATZ").upper():
                    self.log(f"   adapter awake after {i + 1} attempt(s)")
                    return True
                time.sleep(1.0)
            return False
        finally:
            self.timeout = saved

    def send(self, cmd: str) -> str:
        """Write one command, read to the ELM prompt, return the raw reply."""
        termios.tcflush(self.fd, termios.TCIFLUSH)
        os.write(self.fd, (cmd + "\r").encode())
        self.log(f">> {cmd}")
        buf, deadline = b"", time.time() + self.timeout
        while time.time() < deadline:
            try:
                chunk = os.read(self.fd, 512)
            except BlockingIOError:
                chunk = b""
            if chunk:
                buf += chunk
                if PROMPT in buf:
                    break
            else:
                time.sleep(0.02)
        raw = buf.split(PROMPT)[0].decode(errors="replace")
        clean = raw.replace("\r", " ").replace("\n", " ").strip()
        self.log(f"<< {clean}")
        return clean


# ELM327 status strings that are NOT data. Must be caught before hex-stripping:
# "NO DATA" would otherwise reduce to the hex-looking "DAA".
ELM_ERRORS = (
    "NODATA", "ERROR", "STOPPED", "UNABLE", "BUFFERFULL", "BUSBUSY", "?",
)


def norm(s: str) -> str:
    """Reduce an ELM reply to bare response hex, or "" if it carried no data.

    Multi-frame replies look like `0210:61CE...1:F085...`, a 3-char ISO-TP byte
    count followed by `N:` line indices. Single-frame replies have neither, so the
    count must only be stripped when indices are actually present.
    """
    u = re.sub(r"\s+", "", s.upper())
    u = u.replace("SEARCHING...", "").replace("SEARCHING", "")
    if any(e in u for e in ELM_ERRORS):
        return ""
    declared = None
    if re.search(r"\d:", u):  # multi-frame
        m = re.match(r"^([0-9A-F]{3})", u)
        if m:
            declared = int(m.group(1), 16)  # ISO-TP total byte count
            u = u[3:]
        u = re.sub(r"\d:", "", u)  # line indices
    u = re.sub(r"[^0-9A-F]", "", u)
    # the ELM pads the final consecutive frame; the declared count is authoritative
    if declared is not None:
        u = u[: declared * 2]
    return u


NRC = {
    "11": "serviceNotSupported",
    "12": "subFunctionNotSupported",
    "13": "incorrectMessageLength",
    "22": "conditionsNotCorrect",
    "31": "requestOutOfRange",
    "78": "responsePending",
    "7F": "serviceNotSupportedInSession (UDS)",
    "80": "serviceNotSupportedInSession (KWP2000)",
}

# (header, request, why), read-only, ordered cheapest-signal-first
PROBE = [
    ("7E2", "13B0", "baseline: DTC count on the HV ECU"),
    ("7E3", "1380", "baseline: DTC count on the battery ECU"),
    ("7E3", "21CE", "sanity: service 21 known-good on 7E3"),
    ("7E2", "2101", "THE load-bearing unknown: does 7E2 answer service 21?"),
    ("7E2", "21CA", "detail table 5 (INF 6xx), the headline read"),
    ("7E2", "21C9", "detail table 4 (INF 5xx), other half of a P0AA6 pair"),
    ("7E2", "21C6C7C8C9CA", "all five tables in one request"),
    ("7E2", "2105CA", "variant: service 21 with a two-byte identifier"),
    ("7E2", "2205CA", "control: expect 7F 22 11"),
    ("7E2", "22F186", "control: any UDS DID at all"),
    ("7E3", "21CA", "in case the tables sit on the battery ECU"),
    ("7E3", "21C9", "likewise"),
    ("7E2", "1381", "additional DTC status mask"),
    ("7E2", "1382", "additional DTC status mask"),
]


def interpret(req: str, hexs: str) -> str:
    svc = req[:2]
    pos = f"{int(svc, 16) + 0x40:02X}"
    if not hexs:
        return "NO DATA / silence, discriminates nothing"
    if hexs.startswith("7F"):
        code = hexs[4:6]
        name = NRC.get(code, "unknown NRC")
        if svc == "21" and code == "31":
            return f"NRC {code} {name}, SERVICE 21 IS RIGHT, identifier wrong. Sweep 21C6..21CA"
        if svc == "21" and code == "11":
            return f"NRC {code} {name}, service 21 absent here. Genuinely new; report it"
        return f"NRC {code} {name}"
    if hexs.startswith(pos):
        payload = hexs[len(pos) :]
        if svc == "21":
            body = payload[2:] if len(payload) >= 2 else ""
            n = len(body) // 2
            note = f"POSITIVE {pos}. payload {n} bytes"
            if n == 57:
                note += "  *** 57 BYTES, MATCHES THE LAYOUT ***"
            elif n:
                note += f"  (layout predicts 57, {n} means a different generation/table)"
            if body and set(body) == {"0"}:
                note += "; all zero (expected on a fault-free car)"
            return note
        if svc == "13":
            cnt = int(payload[:2], 16) if len(payload) >= 2 else 0
            return f"POSITIVE {pos}. {cnt} stored DTC(s)" + (
                "  *** STORED FAULT, this car can validate the layout ***" if cnt else ""
            )
        return f"POSITIVE {pos}. payload {len(payload) // 2} bytes"
    return "unrecognised reply"


"""MIN plan: the five exchanges that settle the load-bearing unknown. ~40 s."""
QUICK = [
    ("7E3", "21CE", "sanity: adapter+bus alive, service 21 known-good on 7E3"),
    ("7E2", "13B0", "baseline: is a fault stored? changes what the rest can prove"),
    ("7E2", "2101", "THE unknown: does 7E2 answer service 21?"),
    ("7E2", "21CA", "detail table 5, payload LENGTH is the falsifiable bit"),
    ("7E2", "2205CA", "control: expect 7F 22 11"),
]


def sweep_plan(hdr: str, lo: int, hi: int):
    return [(hdr, f"21{b:02X}", f"identifier sweep {b:#04x}") for b in range(lo, hi + 1)]


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--port")
    ap.add_argument("--list", action="store_true")
    ap.add_argument("--out", default=None)
    ap.add_argument("--quick", action="store_true", help="MIN plan: 5 exchanges")
    ap.add_argument("--sweep", metavar="HDR", help="sweep 21C0..21DF on this header")
    ap.add_argument("--range", default="C0:DF", help="sweep range, e.g. C0:DF")
    args = ap.parse_args()

    ports = [p for p in glob.glob("/dev/cu.*") if "wlan-debug" not in p and "Bluetooth-Inc" not in p]
    if args.list or not args.port:
        print("candidate serial ports:")
        for p in ports:
            print("   ", p)
        if not ports:
            print("    (none, pair the adapter in System Settings > Bluetooth first)")
        if not args.port:
            return 0 if args.list else 1

    stamp = time.strftime("%Y%m%d-%H%M%S")
    if args.out:
        path = args.out
    else:
        d = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
                         "scratch", "probe-logs")
        os.makedirs(d, exist_ok=True)
        path = os.path.join(d, f"probe-{stamp}.log")
    jpath = os.path.splitext(path)[0] + ".json"
    fh = open(path, "w")
    record = {
        "started": stamp, "port": args.port,
        "mode": "sweep" if args.sweep else "quick" if args.quick else "full",
        "exchanges": [],
    }
    t0 = time.time()
    tty = sys.stdout.isatty()
    C = {
        "step": "\033[1;36m", "ok": "\033[1;32m", "bad": "\033[1;31m",
        "warn": "\033[1;33m", "dim": "\033[2m", "off": "\033[0m",
    }
    if not tty:
        C = dict.fromkeys(C, "")

    def log(msg, style="dim"):
        stamp = f"[{time.time() - t0:6.1f}s]"
        print(f"{C['dim']}{stamp}{C['off']} {C[style]}{msg}{C['off']}", flush=True)
        fh.write(f"{stamp} {msg}\n")
        fh.flush()

    def style_for(verdict: str) -> str:
        if "***" in verdict or verdict.startswith("POSITIVE"):
            return "ok"
        if "absent" in verdict or "NRC 11" in verdict:
            return "bad"
        if verdict.startswith("NRC") or "NO DATA" in verdict:
            return "warn"
        return "dim"

    log(f"# read-only INF probe on {args.port}")
    elm = Elm(args.port, log)
    try:
        if not elm.wake():
            log("  ! adapter never answered ATZ, link is down, not an ECU result.", "bad")
            log("    unplug the dongle, wait 5s, replug, then re-run.", "bad")
            return 2
        for c in ("ATE0", "ATL0", "ATS0", "ATH0", "ATSP0", "ATSTFF"):
            elm.send(c)
            time.sleep(0.15)

        if args.sweep:
            lo, hi = (int(x, 16) for x in args.range.split(":"))
            plan = sweep_plan(args.sweep, lo, hi)
        elif args.quick:
            plan = QUICK
        else:
            plan = PROBE
        log(f"# plan: {'sweep' if args.sweep else 'quick' if args.quick else 'full'}"
            f", {len(plan)} exchanges")

        results = []
        n = len(plan)
        for idx, (hdr, req, why) in enumerate(plan, 1):
            log(f"[{idx}/{n}] {hdr} {req}, {why}", "step")
            elm.send(f"ATSH{hdr}")
            raw = elm.send(req)
            hexs = norm(raw)
            verdict = interpret(req, hexs)
            log(f"      => {verdict}", style_for(verdict))
            results.append((hdr, req, hexs, verdict))
            record["exchanges"].append({
                "t": round(time.time() - t0, 3), "header": hdr, "request": req,
                "why": why, "raw": raw, "hex": hexs,
                "bytes": max(0, len(hexs) // 2), "verdict": verdict,
            })
            time.sleep(0.2)

        log("")
        log("=" * 72, "step")
        log("SUMMARY", "step")
        for hdr, req, hexs, verdict in results:
            log(f"  {hdr} {req:<14} {verdict}", style_for(verdict))
        log("=" * 72, "step")

        # plain-English decision, mirroring tools/PROBE-PLAN.md
        by = {r[1]: r for r in results}
        def reply(req):
            return by[req][2] if req in by else None

        log("WHAT THIS MEANS", "step")
        sanity = reply("21CE")
        if sanity is not None and not sanity.startswith("61"):
            log("  ! 21CE on 7E3 did not answer, adapter/bus problem, not an ECU "
                "result. Nothing below is trustworthy.", "bad")
        for req, label in (("2101", "service 21 on 7E2"), ("21CA", "table 5 (INF 6xx)")):
            h = reply(req)
            if h is None:
                continue
            if h.startswith("61"):
                nbytes = (len(h) - 4) // 2
                log(f"  + {label}: ANSWERS. payload {nbytes} bytes"
                    + ("  -> layout shape CONFIRMED on-car" if nbytes == 57 else ""), "ok")
            elif h.startswith("7F") and h[4:6] == "31":
                log(f"  ~ {label}: service 21 accepted, identifier rejected "
                    f"-> run  --sweep {by[req][0]}", "warn")
            elif h.startswith("7F") and h[4:6] == "11":
                log(f"  - {label}: service 21 NOT IMPLEMENTED. Spec section 6 is "
                    "wrong, do not publish as-is.", "bad")
            else:
                log(f"  ? {label}: {h or 'silence'}, inconclusive", "warn")
        ctl = reply("2205CA")
        if ctl and ctl.startswith("62"):
            log("  ! 2205CA SUCCEEDED, the mode-22 framing was right after all. "
                "Stop and re-think.", "bad")
        dtc = reply("13B0")
        if dtc and dtc.startswith("53") and len(dtc) >= 4 and int(dtc[2:4], 16):
            log(f"  * {int(dtc[2:4], 16)} stored DTC(s), this car CAN validate the "
                "INF mapping. Capture everything.", "ok")
        log("=" * 72, "step")
        log(f"log:  {path}")
        log(f"json: {jpath}   <- bring this home for deeper analysis")
    finally:
        elm.close()
        with open(jpath, "w") as jf:
            json.dump(record, jf, indent=1)
        fh.close()
    return 0


if __name__ == "__main__":
    sys.exit(main())
