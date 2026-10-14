#!/usr/bin/env python3
"""Compare entity throughput on the same workload: bench_scaling.py 1=log 4=log ...

Linear efficiency is relative to N times measured one-worker throughput. It is
not a hardware ceiling: SMT, clock changes and memory bandwidth affect that
ceiling. Runs at the 20-step limit cannot measure saturated throughput.
"""
import statistics
import sys
from bench_entities import read_windows, read_cpu_windows

rows = {}
drift = {}
cpu = {}
for arg in sys.argv[1:]:
    workers, path = arg.split("=", 1)
    workers = int(workers)
    if workers < 1 or workers in rows:
        raise SystemExit("Worker counts must be positive and unique")
    samples = read_windows(path)
    rows[workers] = (len(samples), statistics.mean(row[2] for row in samples))
    usage = read_cpu_windows(path)
    cpu[workers] = f"{statistics.mean(usage) / 100:.2f}" if usage else "—"
    count = max(1, len(samples) // 3)
    early = statistics.mean(row[2] for row in samples[:count])
    late = statistics.mean(row[2] for row in samples[-count:])
    drift[workers] = late / early - 1 if early > 0 else float("inf")
if 1 not in rows or rows[1][1] <= 0:
    raise SystemExit("A nonzero one-worker baseline is required")
baseline = rows[1][1]
print("| Workers | Samples | Entity steps/s | Speedup vs 1 worker | Linear efficiency | CPU / 100% | Rate capped |")
print("| ---: | ---: | ---: | ---: | ---: | ---: | --- |")
for workers, (count, rate) in sorted(rows.items()):
    speedup = rate / baseline
    print(f"| {workers} | {count} | {rate:.2f} | {speedup:.2f}× | {speedup / workers:.1%} | {cpu[workers]} | {'yes' if rate >= 19.8 else 'no'} |")
for workers, change in sorted(drift.items()):
    if abs(change) > 0.1:
        print(f"Warning: {workers}-worker throughput changed {change:+.0%} between early and late windows; extend warmup and repeat before using speedup ratios.")
for workers, (_, rate) in sorted(rows.items()):
    if workers > 1 and rate / baseline > workers * 1.05:
        print(f"Warning: apparent speedup at {workers} workers exceeds the worker count; check work per step and hardware effects before interpreting linear efficiency.")
