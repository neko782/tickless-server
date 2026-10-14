#!/usr/bin/env python3
"""Checks a race check run (tools/stress/races.sh): every "Collection race" the server logged
must be listed in race-allowlist.txt, the server must have logged no errors, and it must have
reached its shutdown summary. Exits non-zero otherwise.

Allowlist lines name the two call sites of a race without line numbers, in either order,
separated by " | ", then "#" and why the race is harmless:

    ObjectList.add in Foo.bar | ObjectList.iterator in Foo.baz  # why

usage: check_races.py <out.log> [allowlist]
"""
import re
import sys
from pathlib import Path

RACE = re.compile(r"Collection race: (\S+) (?:changed|used) at (.+?) on .+? while .+? was .+? at (.+)$")


def site(text):
    """'List.add in Foo.bar:12' -> 'List.add in Foo.bar'"""
    return re.sub(r":\d+$", "", text.strip())


def pair(a, b):
    return tuple(sorted((site(a), site(b))))


def load_allowlist(path):
    allowed = set()
    if not Path(path).exists():
        return allowed
    for line in Path(path).read_text().splitlines():
        line = line.split("#", 1)[0].strip()
        if line:
            a, b = line.split(" | ")
            allowed.add(pair(a, b))
    return allowed


def check(log_lines, allowed):
    races = {}
    errors = []
    summary = False
    in_report = False
    for line in log_lines:
        m = RACE.search(line)
        if m:
            key = pair(m.group(2), m.group(3))
            races.setdefault(key, (m.group(1), line))
            in_report = True
            continue
        if line.startswith("      at ") or line.startswith("    "):
            if in_report:
                continue
        else:
            in_report = False
        if "Race check summary:" in line:
            summary = True
        if re.search(r"/ERROR\]|Exception in world event|OUTSIDE THE WAIT GRAPH|World wait outside the lock graph", line):
            errors.append(line)
    unexpected = {k: v for k, v in races.items() if k not in allowed}
    return races, unexpected, errors, summary


def main():
    log = Path(sys.argv[1]).read_text(errors="replace").splitlines()
    allowlist = sys.argv[2] if len(sys.argv) > 2 else Path(__file__).with_name("race-allowlist.txt")
    races, unexpected, errors, summary = check(log, load_allowlist(allowlist))
    print(f"races: {len(races)} site pairs, {len(races) - len(unexpected)} allowed")
    for (a, b), (kind, _) in sorted(unexpected.items()):
        print(f"  RACE {kind}: {a} | {b}")
    print(f"error lines: {len(errors)}")
    for line in errors[:20]:
        print("  " + line[:300])
    if not summary:
        print("  no race check summary: the server did not shut down normally, or was not a race check build")
    ok = not unexpected and not errors and summary
    print("PASS" if ok else "FAIL")
    sys.exit(0 if ok else 1)


if __name__ == "__main__":
    main()
