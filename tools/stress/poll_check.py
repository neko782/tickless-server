#!/usr/bin/env python3
"""Verify poll response parsing with unrelated, reordered and earlier log lines."""
from pathlib import Path
from subprocess import run
from tempfile import TemporaryDirectory

cases = [
    ("[Server] poll\nWorld workers: stats\nTest passed\n", 0, 0),
    ("[Server] poll\nTest failed\nTest passed unrelated\n", 0, 1),
    ("[Server] poll\nTest passed\n[Server] poll\n", 0, 0),
    ("[Server] poll\nTest passed\n", 2, 1),
    ("[Server] poll\nTest passed\n[Server] poll\nTest failed\n", 0, 1),
    ("[Server] poll\nTest failed\n[Server] poll\nOther log\nTest passed\n", 0, 0),
    # Paper: markers and results are logged out of order.
    ("[Server] poll\nTest failed\nTest passed, count: 1\n[Server] poll\n", 0, 0),
    ("[Server] poll\n[Server] poll\nTest passed\nTest failed\n", 0, 1),
    ("Test failed\n[Server] poll\n", 0, 1),
    # Vanilla-based servers: "Test passed. Count: N".
    ("[Server] poll\nSystem chat: Test passed. Count: 1\nThe game time is 5 tick(s)\n", 0, 0),
    ("[Server] poll\nSystem chat: Test failed. Count: 0\n", 0, 1),
]
with TemporaryDirectory() as temporary:
    path = Path(temporary) / "out.log"
    for log, begin, expected in cases:
        path.write_text(log)
        result = run(["awk", "-v", f"begin={begin}", "-f", str(Path(__file__).with_suffix(".awk")), str(path)])
        assert result.returncode == expected, (log, begin, expected)
print("Poll parser checks passed: interleaving, failed/pending results and earlier directives")
