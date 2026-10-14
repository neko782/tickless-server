#!/usr/bin/env python3
"""Compare simulation rate across servers over the BENCH_START..BENCH_END window.

Vanilla and Paper: game ticks completed in the window (from `time query gametime`) / seconds.
Tickless: its game clock always advances at 20/s, so the rate is the mean of the complete
five-second worker-stat windows: min(entity, chunk) steps/s, the share of requested per-entity
and per-chunk steps that actually ran.
CPU is the mean Docker sample after the first five seconds of the window (100% = one core).

usage: bench_compare.py build/stress-<name>/out.log [...]
"""
import re
import statistics
import sys
from pathlib import Path

stamp = re.compile(r"\[(\d\d):(\d\d):(\d\d)[\] ]")
gametime = re.compile(r"The game time is (\d+) tick")
stats = re.compile(r"World workers: (\d+) events/s, chunk step rate ([\d.]+)/s, entity step rate ([\d.]+)/s")


def summarize(path):
    path = Path(path)
    start = end = first = None
    marks = {}
    times = []  # (line index, timestamp, game time)
    rows = []
    previous = day = 0
    for index, line in enumerate(path.read_text(errors="replace").splitlines()):
        m = stamp.match(line)
        if not m:
            continue
        h, mi, s = map(int, m.groups())
        now = h * 3600 + mi * 60 + s
        if previous - now > 43200:
            day += 86400
        previous = now
        now += day
        first = now if first is None else first
        if line.endswith("] BENCH_START"):
            start = now
            marks["start"] = index
        elif line.endswith("] BENCH_END"):
            end = now
            marks["end"] = index
        elif g := gametime.search(line):
            times.append((index, now, int(g[1])))
        elif (w := stats.search(line)) and start is not None and end is None and now > start + 5:
            rows.append(tuple(map(float, w.groups())))
    if start is None or end is None:
        raise SystemExit(f"{path}: missing BENCH_START or BENCH_END")
    seconds = end - start
    if rows:
        events, chunks, entities = map(statistics.mean, zip(*rows))
        rate = min(chunks, entities)
        how = f"{entities:.2f} entity / {chunks:.2f} chunk steps/s"
    else:
        # Paper logs `say` asynchronously, so a marker can land on either side of the synchronous
        # game time line. Use the first and last game time line around the markers, with their own
        # timestamps.
        inside = [t for t in times if marks["start"] - 5 <= t[0] <= marks["end"] + 5]
        (_, t0, g0), (_, t1, g1) = inside[0], inside[-1]
        rate = (g1 - g0) / (t1 - t0)
        how = f"{g1 - g0} ticks / {t1 - t0} s"
    cpu = []
    cpu_path = path.parent / "cpu.log"
    if cpu_path.exists():
        samples = [(int(a) * 3600 + int(b) * 60 + int(c), float(v))
                   for a, b, c, v in re.findall(r"(\d\d):(\d\d):(\d\d) ([\d.]+)", cpu_path.read_text())]
        if samples:
            offset = round((samples[0][0] - first % 86400) / 3600) * 3600
            for local, value in samples:
                t = (local - offset) % 86400
                if (start + 5) % 86400 < t < end % 86400:
                    cpu.append(value)
    return rate, how, statistics.mean(cpu) / 100 if cpu else float("nan")


if __name__ == "__main__":
    results = [(Path(a).parent.name, *summarize(a)) for a in sys.argv[1:]]
    base = results[0][1]
    print(f"| Run | Simulation rate | Measured as | vs {results[0][0]} | CPU cores |")
    print("| --- | ---: | --- | ---: | ---: |")
    for name, rate, how, cpu in results:
        print(f"| {name} | {rate:.2f}/s | {how} | {rate / base:.1f}x | {cpu:.1f} |")
