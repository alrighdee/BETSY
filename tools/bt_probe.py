#!/usr/bin/env python3
"""Minimal ELM327-over-Bluetooth opener, for diagnosing link drops.

`probe_inf.Elm` reconfigures the tty on open: it zeroes the flag words, forces CS8/CREAD/CLOCAL,
sets B115200 and flushes both directions. That is correct for a real UART and pointless for an
RFCOMM pseudo-serial port, where there is no physical line to configure. On macOS that
reconfiguration can tear down the RFCOMM link at the moment of open, which looks exactly like an
adapter that will not answer.

This opens the port and does nothing else, so the two cases can be told apart:
  - answers here but not via Elm  -> the termios setup is what kills the link
  - silent here too               -> the adapter or the pairing is the problem, not our code
"""

import argparse
import os
import sys
import time

PROMPT = b">"


def drain(fd, seconds):
    buf, deadline = b"", time.time() + seconds
    while time.time() < deadline:
        try:
            chunk = os.read(fd, 512)
        except BlockingIOError:
            chunk = b""
        except OSError as e:
            return buf, f"read error: {e}"
        if chunk:
            buf += chunk
            if PROMPT in buf:
                break
        else:
            time.sleep(0.02)
    return buf, None


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", default="/dev/cu.OBDII")
    ap.add_argument("--settle", type=float, default=2.0, help="seconds to wait after open")
    ap.add_argument("--tries", type=int, default=5)
    ap.add_argument("--cmd", default="ATZ")
    args = ap.parse_args()

    print(f"opening {args.port} with no termios changes ...", flush=True)
    t0 = time.time()
    try:
        fd = os.open(args.port, os.O_RDWR | os.O_NOCTTY | os.O_NONBLOCK)
    except OSError as e:
        print(f"FAIL open: {e}")
        return 2
    print(f"  opened, fd={fd}  [{time.time() - t0:.1f}s]", flush=True)

    print(f"  settling {args.settle:.1f}s to let RFCOMM come up ...", flush=True)
    time.sleep(args.settle)

    # Anything the adapter volunteered on connect (some clones print a banner unprompted).
    pre, err = drain(fd, 0.5)
    if pre:
        print(f"  unprompted: {pre!r}")
    if err:
        print(f"  {err}")

    for i in range(1, args.tries + 1):
        try:
            os.write(fd, (args.cmd + "\r").encode())
        except OSError as e:
            print(f"  [{i}] write failed: {e}  <-- link is down")
            break
        buf, err = drain(fd, 3.0)
        text = buf.decode(errors="replace").replace("\r", " ").replace("\n", " ").strip()
        print(f"  [{i}] >> {args.cmd}   << {text or '(silence)'}   [{time.time() - t0:.1f}s]", flush=True)
        if err:
            print(f"      {err}")
        if text:
            print("\nADAPTER ANSWERED. The termios setup in Elm is what was killing the link.")
            os.close(fd)
            return 0
        time.sleep(1.0)

    print("\nNo answer. Not a termios problem: the adapter or the pairing is at fault.")
    os.close(fd)
    return 1


if __name__ == "__main__":
    sys.exit(main())
