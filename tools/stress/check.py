#!/usr/bin/env python3
"""Summarises a stress run log: errors, item conservation in hopper rings, entity counts,
named checks, and a final PASS or FAIL line. Exits non-zero on FAIL.

A run fails on error lines, world lock waits over 10s, a watchdog crash, a hopper item
mismatch or no observed hopper transfers (when items are expected), a failed named
check, or a log that never reached the end of its script.

usage: check.py <out.log> <expected-items>
"""
import re
import sys
from pathlib import Path

log = open(sys.argv[1], errors="replace").read().splitlines()
expected = int(sys.argv[2])

items = 0
hoppers = 0
moved_hoppers = 0
for line in log:
    if "has the following block data" in line:
        hoppers += 1
        items += sum(int(c) for c in re.findall(r"count: (\d+)", line))
        moved_hoppers += "Slot: 1b" in line

errors = [l for l in log if re.search(r"\b(ERROR|Exception|Error:)\b", l) or "at net.minecraft" in l]
counts = [l.split("]: ", 1)[-1] for l in log if "Test passed" in l or "Test failed" in l]
stats = [l.split("]: ", 1)[-1] for l in log if "World workers:" in l]
lag = [l for l in log if "Can't keep up" in l]

# The hopper-ring total only applies to scenarios that pass one (basic); others read containers
# for their own checks (graph uses check_graph.py).
if expected:
    print(f"hoppers read: {hoppers}, items: {items}, expected: {expected}, {'OK' if items == expected else 'MISMATCH'}")
    print(f"hoppers with transferred items: {moved_hoppers}")
print(f"error lines: {len(errors)}")
for l in errors[:40]:
    print("  " + l[:300])
print("entity counts:", counts)
print(f"lag warnings: {len(lag)}")
for l in stats[-6:]:
    print("  " + l)

# Named checks: "say check <name>" is followed by the result of one command.
checks = []
pending = None
for line in log:
    m = re.search(r"\[Server\] check (\S+)", line)
    if m:
        if pending:
            checks.append((pending, False))
        pending = m.group(1)
    elif pending and ("Test passed" in line or "Test failed" in line):
        passed = "Test passed" in line
        if pending.startswith("stable-population-"):
            population = re.search(r"Count: (\d+)", line)
            passed = passed and population is not None and int(population.group(1)) == int(pending.removeprefix("stable-population-"))
        checks.append((pending, passed))
        pending = None
if pending:
    checks.append((pending, False))
for name, ok in checks:
    print(f"check {name}: {'ok' if ok else 'FAILED'}")

problems = []
run_dir = Path(sys.argv[1]).parent
if (run_dir / "failure.txt").exists():
    problems.append("harness failure: " + (run_dir / "failure.txt").read_text().strip())
if (run_dir / "exit-code.txt").exists() and (run_dir / "exit-code.txt").read_text().strip() != "0":
    problems.append("server exited unsuccessfully")
if errors:
    problems.append(f"{len(errors)} error lines")
if any("has waited 10s for a world lock" in l for l in log):
    problems.append("world lock wait over 10s")
if any("A single server tick took" in l for l in log):
    problems.append("watchdog crash")
if expected and items != expected:
    problems.append("hopper item mismatch")
if expected and moved_hoppers == 0:
    problems.append("no hopper transfers observed")
problems += [f"check {name} failed" for name, ok in checks if not ok]
if not any("Stopping server" in l for l in log):
    problems.append("server did not reach the end of the script")
print("PASS" if not problems else "FAIL: " + ", ".join(problems))
sys.exit(1 if problems else 0)
