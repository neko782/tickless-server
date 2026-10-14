#!/usr/bin/env python3
"""Summarizes a run of a gen_heavy.py or gen_lag.py script.

Reports the wall time from BENCH_START until the first passing poll (finite workloads) or
BENCH_END, the game ticks that passed meanwhile, and averages of the world stats lines and
container CPU samples inside that window. Named checks ("say check <name>") are listed too.

usage: bench_heavy.py <run-dir>
"""
import re
import sys
from pathlib import Path
from bench_entities import read_cpu_windows

run_dir = sys.argv[1]
directory = Path(run_dir)
if (directory / "failure.txt").exists():
    sys.exit("run failed: " + (directory / "failure.txt").read_text().strip())
if (directory / "exit-code.txt").exists() and (directory / "exit-code.txt").read_text().strip() != "0":
    sys.exit("server exited unsuccessfully")
log = open(f"{run_dir}/out.log", errors="replace").read().splitlines()
TIME = re.compile(r"^\[(\d\d:\d\d:\d\d)[\] ]")  # vanilla "[hh:mm:ss]", Paper "[hh:mm:ss INFO]"
previous = day = 0


def when(line):
    global previous, day
    m = TIME.match(line)
    if not m:
        return None
    h, minute, second = map(int, m.group(1).split(":"))
    current = h * 3600 + minute * 60 + second
    if previous - current > 43200:
        day += 86400
    previous = current
    return current + day


start = end = done = None
done_gametime_index = None
gametimes = []
checks = []
stats = []
polling = False
pending_check = None
for line in log:
    t = when(line)
    if "BENCH_START" in line:
        start = t
    elif "BENCH_END" in line:
        end = t
    elif "The game time is" in line and start is not None:
        gametimes.append(int(re.search(r"The game time is (\d+)", line).group(1)))
    elif "[Server] poll" in line:
        polling = True
    elif (polling or (start is not None and end is None)) and ("Test passed" in line or "Test failed" in line):
        # Only polls run between the markers, and Paper may log a poll's result before its
        # "say poll" marker, so the first passing result in the window is the finish.
        polling = False
        if "Test passed" in line and done is None:
            done = t
            done_gametime_index = len(gametimes)
    elif "[Server] check " in line:
        pending_check = line.split("[Server] check ", 1)[1].strip()
    elif pending_check and ("Test passed" in line or "Test failed" in line):
        checks.append((pending_check, "Test passed" in line))
        pending_check = None
    m = re.search(
        r"World workers: (\d+) events/s, chunk step rate ([\d.]+)/s, entity step rate ([\d.]+)/s, "
        r"server thread (\d+)% busy, pending tasks (\d+), ticking entities (\d+), loaded chunks (\d+)",
        line,
    )
    if m and t is not None:
        stats.append((t,) + tuple(float(g) for g in m.groups()))

if start is None:
    sys.exit("no BENCH_START in log")
finish = done if done is not None else end
if finish is None:
    sys.exit("run ended before BENCH_END")
window = [s for s in stats if start + 5 < s[0] < finish]
cpu = read_cpu_windows(directory / "out.log", start=start, end=finish, skip_seconds=0)


def avg(values):
    return sum(values) / len(values) if values else float("nan")


seconds = finish - start
# Polls query the game time right after their test, so the one after the passing test ends the run.
last = done_gametime_index if done_gametime_index is not None and done_gametime_index < len(gametimes) else -1
ticks = gametimes[last] - gametimes[0] if len(gametimes) >= 2 else None
label = "finished in" if done is not None else "window"
print(
    f"{label} {seconds:.0f}s, game ticks {ticks}, "
    f"events/s {avg([s[1] for s in window]):.0f}, "
    f"chunk steps/s {avg([s[2] for s in window]):.1f}, entity steps/s {avg([s[3] for s in window]):.1f}, "
    f"server thread {avg([s[4] for s in window]):.0f}% busy, "
    f"cpu {avg(cpu):.0f}% (max {max(cpu, default=float('nan')):.0f}%), "
    f"loaded chunks {int(window[-1][7]) if window else '?'}"
)
for name, passed in checks:
    print(f"check {name}: {'PASS' if passed else 'FAIL'}")
