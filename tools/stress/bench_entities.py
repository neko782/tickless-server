#!/usr/bin/env python3
"""Summarize complete worker-stat windows between BENCH_START and BENCH_END.

Usage: bench_entities.py build/stress-<name>/out.log [...]
The run.sh harness sets the worker-stat interval to five seconds.
"""
import re
import statistics
import sys
from pathlib import Path

stats = re.compile(
    r"World workers: (\d+) events/s, chunk step rate ([\d.]+)/s, "
    r"entity step rate ([\d.]+)/s, server thread (\d+)%"
)

def read_run(path):
    """Read the server timeline, unwrapping midnight in log order."""
    path = Path(path)
    start = end = None
    rows = []
    day = 0
    previous = 0
    first = None
    for line in path.read_text().splitlines():
        timestamp = re.match(r"\[(\d\d):(\d\d):(\d\d)\]", line)
        if timestamp is None:
            continue
        h, m, s = map(int, timestamp.groups())
        current = h * 3600 + m * 60 + s
        if previous - current > 43200:
            day += 86400
        previous = current
        current += day
        if first is None:
            first = current
        if line.endswith("] BENCH_START"):
            start = current
        elif line.endswith("] BENCH_END"):
            end = current
        match = stats.search(line)
        if match:
            rows.append((current, tuple(map(float, match.groups()))))
    if start is None or end is None:
        raise SystemExit(f"{path}: missing BENCH_START or BENCH_END")
    return first, start, end, rows


def read_windows(path):
    """Return complete steady windows; reject unfinished or unmarked runs."""
    _, start, end, rows = read_run(path)
    # Logs only have whole-second timestamps. Exclude boundary seconds: a row
    # stamped at BENCH_END can already include draining/freezing after the end
    # marker, which inflated the one-worker rate in a saturation sweep.
    samples = [values for time, values in rows if start + 5 < time < end]
    if not samples:
        raise SystemExit(f"{path}: no complete five-second samples")
    return samples


def read_cpu_windows(path, start=None, end=None, skip_seconds=5):
    """Align local Docker CPU timestamps to UTC logs, including midnight."""
    first, marked_start, marked_end, _ = read_run(path)
    start = marked_start if start is None else start
    end = marked_end if end is None else end
    cpu_path = Path(path).parent / "cpu.log"
    if not cpu_path.exists():
        return []
    samples = []
    for line in cpu_path.read_text().splitlines():
        match = re.fullmatch(r"(\d\d):(\d\d):(\d\d) ([\d.]+)", line)
        if match:
            h, m, s = map(int, match.groups()[:3])
            samples.append((h * 3600 + m * 60 + s, float(match[4])))
    if not samples:
        return []
    # run.sh starts sampling at launch. Infer its whole-hour timezone offset
    # from the first timestamp, then unwrap the aligned sequence independently.
    offset = round((samples[0][0] - first) / 3600) * 3600
    previous = (samples[0][0] - offset) % 86400
    day = round((first - previous) / 86400) * 86400
    values = []
    for local, value in samples:
        current = (local - offset) % 86400
        if previous - current > 43200:
            day += 86400
        previous = current
        if start + skip_seconds < current + day < end:
            values.append(value)
    return values


if __name__ == "__main__":
    print("| Run | Samples | Events/s | Chunk steps/s | Entity steps/s | Server busy |")
    print("| --- | ---: | ---: | ---: | ---: | ---: |")
    for arg in sys.argv[1:]:
        path = Path(arg)
        samples = read_windows(path)
        events, chunks, entities, busy = map(statistics.mean, zip(*samples))
        print(f"| {path.parent.name} | {len(samples)} | {events:,.0f} | {chunks:.2f} | {entities:.2f} | {busy:.1f}% |")
