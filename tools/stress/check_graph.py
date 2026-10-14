#!/usr/bin/env python3
"""Checks item conservation for gen_graph.py: every item placed is still somewhere.

Reads the container, dropped item, minecart and golem hand dumps between the graph-count
markers in a run log and compares per-item totals with the expected JSON.

usage: check_graph.py OUT_LOG EXPECTED_JSON
"""
import json
import re
import sys

log = open(sys.argv[1], encoding="utf-8", errors="replace").read().splitlines()
expected = json.load(open(sys.argv[2]))


def strip_components(text):
    # Components can hold nested compounds; drop them so each item is one flat {...}.
    out = []
    i = 0
    while i < len(text):
        if text.startswith("components: {", i):
            depth = 0
            j = i + len("components: ")
            while j < len(text):
                if text[j] == "{":
                    depth += 1
                elif text[j] == "}":
                    depth -= 1
                    if depth == 0:
                        break
                j += 1
            i = j + 1
            continue
        out.append(text[i])
        i += 1
    return "".join(out)


found = {}
holders = 0
inside = False
for line in log:
    if "graph-count-begin" in line:
        inside = True
        continue
    if "graph-count-end" in line:
        inside = False
        continue
    if not inside or "has the following" not in line:
        continue
    holders += 1
    data = strip_components(line.split("data: ", 1)[1])
    for compound in re.findall(r"\{([^{}]*)\}", data):
        item = re.search(r'id: "minecraft:([^"]+)"', compound)
        count = re.search(r"count: (\d+)", compound)
        if item:
            found[item.group(1)] = found.get(item.group(1), 0) + (int(count.group(1)) if count else 1)

problems = []
for item in sorted(set(expected) | set(found)):
    e, f = expected.get(item, 0), found.get(item, 0)
    if e != f:
        problems.append(f"  {item}: expected {e}, found {f} ({f - e:+d})")

print(f"holders read: {holders}, items: {sum(found.values())}, expected: {sum(expected.values())}")
if not inside and holders == 0:
    problems.append("  no container dumps between the count markers (run did not reach the end?)")
other = [l for l in log if "graph-no-other-item-holders" in l]
if other:
    idx = log.index(other[-1])
    if not any("Test passed" in l for l in log[idx:idx + 4]):
        problems.append("  entities other than items, hopper minecarts and golems are in the box")
if problems:
    print("FAIL: item totals differ")
    print("\n".join(problems))
    sys.exit(1)
print("PASS: every item accounted for")
