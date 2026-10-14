#!/usr/bin/env python3
"""Check benchmark boundaries and CPU alignment across UTC/local midnight."""
from pathlib import Path
from tempfile import TemporaryDirectory
import subprocess
import sys
from bench_entities import read_windows, read_cpu_windows


def stamp(seconds):
    seconds %= 86400
    return f"{seconds // 3600:02}:{seconds // 60 % 60:02}:{seconds % 60:02}"


with TemporaryDirectory() as temporary:
    path = Path(temporary) / "out.log"
    for start in (12 * 3600, 22 * 3600, 86390):
        for offset in (0, 2 * 3600, -7 * 3600):
            lines = [f"[{stamp(start - 10)}] server launch", f"[{stamp(start)}] [Server] BENCH_START"]
            for delta in (0, 5, 10, 15, 20, 25):
                lines.append(f"[{stamp(start + delta)}] World workers: 100 events/s, chunk step rate 20.00/s, entity step rate {delta}.00/s, server thread 10%")
            lines.append(f"[{stamp(start + 30)}] [Server] BENCH_END")
            lines.append(f"[{stamp(start + 30)}] World workers: 100 events/s, chunk step rate 20.00/s, entity step rate 99.00/s, server thread 10%")
            path.write_text("\n".join(lines))
            (path.parent / "cpu.log").write_text("\n".join(
                f"{stamp(start + delta + offset)} {delta + 100}" for delta in (-10, 0, 5, 10, 15, 20, 25, 30)
            ))
            assert [row[2] for row in read_windows(path)] == [10, 15, 20, 25]
            assert read_cpu_windows(path) == [110, 115, 120, 125], (start, offset)
    (path.parent / "cpu.log").unlink()
    assert read_cpu_windows(path) == []

    start = 86390
    lines = [f"[{stamp(start - 10)}] server launch", f"[{stamp(start)}] [Server] BENCH_START",
             f"[{stamp(start)}] The game time is 100"]
    for delta, events in ((10, 100), (15, 200), (25, 999)):
        if delta == 25:
            lines += [f"[{stamp(start + 20)}] [Server] poll", f"[{stamp(start + 20)}] Test passed",
                      f"[{stamp(start + 20)}] The game time is 200"]
        lines.append(f"[{stamp(start + delta)}] World workers: {events} events/s, chunk step rate 20.00/s, entity step rate 20.00/s, server thread 10% busy, pending tasks 0, ticking entities 100, loaded chunks 100")
    lines.append(f"[{stamp(start + 30)}] [Server] BENCH_END")
    path.write_text("\n".join(lines))
    (path.parent / "cpu.log").write_text("\n".join(
        f"{stamp(start + delta + 7200)} {delta + 100}" for delta in (-10, 0, 5, 10, 15, 20, 25, 30)
    ))
    command = [sys.executable, str(Path(__file__).with_name("bench_heavy.py")), str(path.parent)]
    result = subprocess.run(command, capture_output=True, text=True, check=True)
    for expected in ("finished in 20s", "game ticks 100", "events/s 150", "cpu 110%"):
        assert expected in result.stdout, result.stdout
    (path.parent / "failure.txt").write_text("forced failure")
    assert subprocess.run(command, capture_output=True).returncode != 0
print("Benchmark timing checks passed: boundaries, UTC/local midnight, timezone offsets and finite workload summaries")
