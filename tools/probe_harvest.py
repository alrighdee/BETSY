#!/usr/bin/env python3
"""One-session harvest of everything read-only worth knowing, from a car that is present.

Access to the car is the scarce resource, not time or tokens. This asks every read-only question
we currently have, ordered so that if the session dies partway through we have lost the least
valuable questions rather than the most valuable ones.

Three properties the earlier probes lacked, each of which cost a run tonight:

  - **Self-healing sessions.** This adapter gives one working serial session per *pairing*, not
    per connection: the successful run came straight after an unpair/re-pair, and
    every attempt afterwards was silent through six link cycles. So when the link goes quiet
    mid-run, this unpairs, re-pairs, reconnects and carries on from the next unanswered command.
  - **Live output.** Every line is flushed as it happens. Sitting in a car watching a blank
    terminal is not observability.
  - **Incremental saves.** Each answer is appended to a JSONL file the moment it arrives. A crash
    at question 80 keeps the first 79.

Read-only is enforced against an allowlist before anything is transmitted, so nothing here can
clear a DTC, reset an ECU, or write. Clearing would destroy the freeze frame and operation
history that this whole exercise is trying to characterise.
"""

import argparse
import json
import os
import re
import subprocess
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from probe_inf import Elm, norm  # noqa: E402

MAC = "00-1D-A5-68-F4-56"
PORT = "/dev/cu.OBDII"

SAFE_SERVICES = {"01", "02", "03", "07", "09", "12", "13", "17", "18", "19", "1A", "21", "22"}
FORBIDDEN = {
    "04": "clear DTCs", "14": "clear diagnostic information", "11": "ECU reset",
    "27": "security access", "2E": "write data", "2F": "I/O control",
    "31": "routine control", "34": "download", "35": "upload", "36": "transfer",
    "3B": "write by local id", "10": "session control", "85": "control DTC setting",
}

# Mode 09 PID 02 returns the VIN, which identifies the vehicle and often its owner, and these
# files are committed to a repository with a public remote. `check_safe` refuses to send `0902`
# at all; `scrub` is the second line of defence for a VIN arriving some other way.
#
# CARE: the single-frame pattern below is NOT sufficient on its own. An ELM327 returns a long
# reply split across "N:" line indices, e.g. `014 0:490201<6 bytes> 1:<7 bytes> 2:<7 bytes>`, so
# a regex anchored on `490201` matches only the first frame and silently leaves the rest of the
# VIN in the file. That mistake was made once here and caught only by re-checking the written
# output, with the last seven characters, the serial portion, still present. Redact whole lines,
# and verify by grepping the file afterwards rather than trusting the substitution.
VIN_RE = re.compile(r"(4902 ?01)((?:[0-9A-F]{2}){6,})", re.I)
VIN_FRAMES_RE = re.compile(r"(4902 ?01).*$", re.I | re.M)


def scrub(s):
    """Remove any mode-09 VIN payload, including its continuation frames."""
    return VIN_FRAMES_RE.sub(r"\1<VIN-REDACTED>", VIN_RE.sub(r"\1<VIN-REDACTED>", s))


def plan():
    """(priority, header, command, why). Lower priority number runs first."""
    p = []

    # 1. THE question: what parameter form does KWP readFreezeFrameData want? 7E0 is the place
    #    to learn it, because U0293 is stored there, so a correct request returns real data
    #    rather than another negative response. 7E2 is clean, so it can only ever answer with
    #    an NRC, which tells us the service exists but not how to call it.
    for cmd in ["12", "1200", "1201", "1202", "1203", "1204", "1205",
                "12C293", "12C29300", "12C29301", "120000", "1280", "12FF", "12FFFF00"]:
        p.append((1, "7E0", cmd, "freeze-frame format hunt against a REAL stored DTC"))
    for cmd in ["12", "1200", "1201", "1202", "12C6", "12C7", "1280", "12FF"]:
        p.append((2, "7E2", cmd, "same service on the HV ECU, NRC shape only"))

    # 2. The generic freeze frame on the ECM, which we know returns data. Dump all of it: the
    #    supported-PID mask first, then every PID it claims, then look for further frames.
    p.append((3, "7E0", "020000", "mode 02 supported-PID mask, frame 0"))
    for pid in ["01", "02", "03", "04", "05", "0B", "0C", "0D", "0E", "0F",
                "10", "11", "1C", "1F", "20", "21", "2F", "31", "33", "42", "43", "44"]:
        p.append((4, "7E0", f"02{pid}00", f"freeze frame PID {pid}, frame 0"))
    for frame in ["01", "02"]:
        p.append((5, "7E0", f"0202{frame}", f"is there a second freeze frame? frame {frame}"))

    # 3. What the HV ECU actually exposes. The first mask came back 4100981A8013; the extended
    #    masks say whether there is more beyond PID 0x20.
    for cmd in ["0100", "0120", "0140", "0160", "0900"]:
        p.append((6, "7E2", cmd, "supported-PID / mode-09 masks on the HV ECU"))
    for cmd in ["0100", "0120", "0140"]:
        p.append((6, "7E0", cmd, "same for the ECM"))

    # 4. Other standard routes to stored-DTC detail, in case service 12 is not the one.
    for cmd in ["1900", "1902", "190A", "1800", "18FF00", "17", "1700"]:
        p.append((7, "7E2", cmd, "UDS/KWP DTC-information routes"))
    for cmd in ["1902", "18FF00"]:
        p.append((7, "7E0", cmd, "same against the ECU that has a DTC"))

    # 5. Identifier sweep. 21C6..21CA are the tables BETSY already reads; the neighbours are
    #    worth having on record now that we know the read path works.
    for lid in range(0xC0, 0xE0):
        p.append((8, "7E2", f"21{lid:02X}", f"identifier sweep 0x{lid:02x}"))

    # 6. The battery ECU, for completeness while the car is here.
    for cmd in ["1380", "21C0", "21C1", "21CE"]:
        p.append((9, "7E3", cmd, "battery ECU"))

    p.sort(key=lambda x: x[0])
    return p


def check_safe(cmd):
    svc = cmd[:2].upper()
    if svc in FORBIDDEN:
        raise SystemExit(f"REFUSED: {cmd} is service {svc} ({FORBIDDEN[svc]})")
    if svc not in SAFE_SERVICES:
        raise SystemExit(f"REFUSED: {cmd} is service {svc}, not on the read-only allowlist")
    if cmd.upper().startswith("0902"):
        raise SystemExit("REFUSED: mode 09 PID 02 returns the VIN")


class Session:
    """A talking adapter, re-established by unpair/re-pair whenever it goes quiet."""

    def __init__(self, log, settle):
        self.log = log
        self.settle = settle
        self.elm = None
        self.rebuilds = 0

    def _bt(self, *args):
        try:
            subprocess.run(["blueutil", *args], capture_output=True, timeout=25, check=False)
        except (OSError, subprocess.SubprocessError):
            pass

    def _connected(self):
        try:
            out = subprocess.run(["blueutil", "--connected"], capture_output=True,
                                 text=True, timeout=15).stdout
        except (OSError, subprocess.SubprocessError):
            return False
        return MAC.lower() in out.lower()

    def open(self, repair, tries=4):
        for i in range(1, tries + 1):
            if repair:
                self.log(f"    session: unpair, re-pair (attempt {i}/{tries}) ...")
                self._bt("--unpair", MAC)
                time.sleep(self.settle)
                self._bt("--pair", MAC, "1234")
                time.sleep(self.settle)
            self.log(f"    session: connecting (attempt {i}/{tries}) ...")
            self._bt("--disconnect", MAC)
            time.sleep(2)
            self._bt("--connect", MAC)
            for _ in range(60):
                if os.path.exists(PORT):
                    break
                time.sleep(0.1)
            time.sleep(self.settle)
            if not self._connected():
                self.log("    session: link did not come up")
                continue
            try:
                elm = Elm(PORT, lambda m: None, timeout=3.0)
            except OSError as e:
                self.log(f"    session: cannot open port: {e}")
                continue
            if elm.wake():
                for c in ["ATE0", "ATL0", "ATS0", "ATH0", "ATSP0", "ATST32"]:
                    elm.send(c)
                self.elm = elm
                self.log("    session: UP")
                return True
            elm.close()
            self.log("    session: silent ATZ")
            repair = True  # first failure earns a re-pair on the next go
        return False

    def rebuild(self):
        self.rebuilds += 1
        if self.elm:
            try:
                self.elm.close()
            except OSError:
                pass
            self.elm = None
        self.log(f"    !! link lost, rebuilding session (rebuild #{self.rebuilds})")
        return self.open(repair=True)

    def ask(self, header, cmd):
        self.elm.send(f"ATSH{header}")
        raw = self.elm.send(cmd)
        return raw.strip()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--settle", type=float, default=4.0)
    ap.add_argument("--max-rebuilds", type=int, default=8)
    ap.add_argument("--silent-before-rebuild", type=int, default=4)
    ap.add_argument("--out", default=None)
    ap.add_argument("--limit", type=int, default=0, help="stop after N questions (0 = all)")
    args = ap.parse_args()

    items = plan()
    if args.limit:
        items = items[: args.limit]
    for _, _, cmd, _ in items:
        check_safe(cmd)

    stamp = time.strftime("%Y%m%d-%H%M%S")
    base = args.out or os.path.join(
        os.path.dirname(os.path.abspath(__file__)), "..", "captures", f"harvest-{stamp}"
    )
    t0 = time.time()
    logf = open(base + ".log", "w")
    jsonl = open(base + ".jsonl", "w")

    def log(msg=""):
        line = f"[{time.time() - t0:6.1f}s] {msg}"
        print(line, flush=True)
        logf.write(line + "\n")
        logf.flush()

    log(f"HARVEST. READ-ONLY. {len(items)} questions, ordered most valuable first.")
    log(f"writing {os.path.basename(base)}.jsonl after every single answer")
    log("")

    sess = Session(log, args.settle)
    log("establishing first session ...")
    if not sess.open(repair=True):
        log("!! could not get a session at all. Nothing harvested.")
        return 2
    log("")

    done, silent_run, durations, aborted = 0, 0, [], None
    for i, (prio, hdr, cmd, why) in enumerate(items, 1):
        eta = ""
        if durations:
            per = sorted(durations)[len(durations) // 2]
            eta = f" eta ~{per * (len(items) - i + 1):.0f}s"
        c0 = time.time()
        try:
            raw = sess.ask(hdr, cmd)
        except OSError as e:
            raw = f"(io error {e})"
        dt = time.time() - c0
        durations.append(dt)
        h = norm(raw)
        raw_s = scrub(raw)

        flag = ""
        if h.startswith("7F"):
            nrc = h[4:6]
            flag = {"11": "no such service", "12": "bad subfunction", "13": "bad length",
                    "22": "conditions", "31": "out of range"}.get(nrc, f"NRC {nrc}")
        elif h:
            flag = "*** POSITIVE ***"
        else:
            flag = "silent"

        log(f"[{i}/{len(items)}] p{prio} {hdr} {cmd:<9} {flag:<17} {raw_s[:46]}{eta}")

        jsonl.write(json.dumps({
            "n": i, "priority": prio, "header": hdr, "cmd": cmd, "why": why,
            "raw": raw_s, "hex": scrub(h), "flag": flag, "seconds": round(dt, 2),
        }) + "\n")
        jsonl.flush()
        done += 1

        silent_run = silent_run + 1 if not h else 0
        if silent_run >= args.silent_before_rebuild:
            if sess.rebuilds >= args.max_rebuilds:
                aborted = "REBUILD_LIMIT"
                log(f"!! {sess.rebuilds} rebuilds already, stopping. {done} answers saved.")
                break
            if not sess.rebuild():
                aborted = "SESSION_LOST"
                log(f"!! could not re-establish. {done} answers saved.")
                break
            silent_run = 0

    if sess.elm:
        sess.elm.close()

    log("")
    log("=" * 72)
    log(f"HARVEST {'INCOMPLETE (' + aborted + ')' if aborted else 'COMPLETE'}: "
        f"{done}/{len(items)} answered in {time.time() - t0:.0f}s, {sess.rebuilds} rebuild(s)")
    log("")
    log("POSITIVES (the things that answered with data):")
    jsonl.close()
    for line in open(base + ".jsonl"):
        r = json.loads(line)
        if r["flag"] == "*** POSITIVE ***":
            log(f"  {r['header']} {r['cmd']:<9} {r['hex'][:64]}")
    log("")
    log("SERVICE 12 (freeze frame) RESULTS:")
    for line in open(base + ".jsonl"):
        r = json.loads(line)
        if r["cmd"].startswith("12"):
            log(f"  {r['header']} {r['cmd']:<9} {r['flag']:<17} {r['raw'][:40]}")
    log("=" * 72)
    logf.close()
    print(f"\nsaved {base}.log and {base}.jsonl", flush=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())
