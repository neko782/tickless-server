#!/usr/bin/env python3
"""Check packets received on joining and rejoining after unobserved state changes."""
import re
import sys
from pathlib import Path

log = Path(sys.argv[1]).read_text()
uuid = "00000001-0000-0002-0000-000300000004"
adds = list(re.finditer(rf"ENTITY_ADD \S+ id=(\d+) uuid={uuid} type=minecraft:armor_stand pos=([^ ]+) rot=([^\n]+)", log))
if len(adds) != 2:
    raise SystemExit(f"Expected two probe spawns, got {adds}")
for index, (spawn, expected_pos, expected_rot, name) in enumerate(zip(
    adds, [(8.25, 200, 8.25), (-8.25, 200, -8.25)], [(90, 30), (180, 0)], ["fresh", "rejoined"]
)):
    entity_id, pos, rot = spawn.groups()
    actual_pos = tuple(map(float, pos.split(",")))
    actual_rot = tuple(map(float, rot.split(",")))
    # Rotation is quantized to 1/256 turn on the wire.
    if actual_pos != expected_pos or any(abs((a-b+180) % 360 - 180) > 1.5 for a,b in zip(actual_rot, expected_rot)):
        raise SystemExit(f"Stale spawn state: position {actual_pos}, rotation {actual_rot}")
    segment = log[spawn.end():adds[index + 1].start() if index + 1 < len(adds) else len(log)]
    first_data = re.search(rf"ENTITY_DATA \S+ id={entity_id} values=([^\n]+)", segment)
    if first_data is None or name not in first_data.group(1):
        raise SystemExit(f"Missing current metadata {name}")
if "tick failed" in log or "failed to connect" in log:
    raise SystemExit("Bot failed")
print("Tracker packets passed: current position, rotation and metadata on join and rejoin")
