#!/usr/bin/env python3
"""Check freeze, exact steps, and changed simulation rate in a console run."""
import re
import sys

log = open(sys.argv[1], errors="replace").read()
times = [int(value) for value in re.findall(r"System chat: The game time is (\d+) tick\(s\)", log)]
if len(times) != 6:
    raise SystemExit(f"FAIL: expected 6 game-time queries, got {times}")

changes = [times[index + 1] - times[index] for index in range(5)]
if changes[0:3] != [0, 1, 3]:
    raise SystemExit(f"FAIL: freeze/step changes were {changes[0:3]}, expected [0, 1, 3]")
if not 28 <= changes[4] <= 32:
    raise SystemExit(f"FAIL: 10 Hz advanced {changes[4]} steps in 3 seconds")
print(f"clock changes: {changes}; PASS")
