#!/usr/bin/env python3
"""Writes a console script that builds a busy test area and checks item conservation.

The area covers 12x12 force-loaded chunks at y=200 above the spawn:
  - closed hopper rings (items must neither vanish nor duplicate),
  - observer clocks driving sticky pistons (scheduled ticks and block events),
  - a wheat farm under a high random tick speed,
  - a glass pen with animals, villagers and zombies (entity AI, pushing, pathfinding).

usage: gen_world.py <commands-out> [run-seconds] [mobs] [ring-spacing]
"""
import random
import sys

out = sys.argv[1]
run_seconds = int(sys.argv[2]) if len(sys.argv) > 2 else 60
mob_count = int(sys.argv[3]) if len(sys.argv) > 3 else 324
ring_spacing = int(sys.argv[4]) if len(sys.argv) > 4 else 6
Y = 200
lines = []
cmd = lines.append

cmd("gamerule advance_time false")
cmd("gamerule advance_weather false")
cmd("gamerule spawn_mobs false")
cmd("gamerule random_tick_speed 100")
cmd("time set 18000")
cmd("forceload add -96 -96 95 95")
# Wait until the corner chunks are loaded rather than a fixed 15 s.
cmd("poll 60 execute if loaded -96 0 -96 if loaded 95 0 -96 if loaded -96 0 95 if loaded 95 0 95")

# Platform, split to stay under the fill limit.
for x0 in (-96, 0):
    for z0 in (-96, 0):
        cmd(f"fill {x0} {Y - 1} {z0} {x0 + 95} {Y - 1} {z0 + 95} minecraft:stone")
        cmd(f"fill {x0} {Y} {z0} {x0 + 95} {Y + 4} {z0 + 95} minecraft:air")

# Hopper rings in the north-west quadrant. The 6-block grid makes some rings straddle chunk borders.
rings = []
for x in range(-94, -3, ring_spacing):
    for z in range(-94, -3, ring_spacing):
        rings.append((x, z))
        cmd(f"setblock {x} {Y} {z} minecraft:hopper[facing=east]")
        cmd(f"setblock {x + 1} {Y} {z} minecraft:hopper[facing=south]")
        cmd(f"setblock {x + 1} {Y} {z + 1} minecraft:hopper[facing=west]")
        cmd(f"setblock {x} {Y} {z + 1} minecraft:hopper[facing=north]")
        for hx, hz in ((x, z), (x + 1, z), (x + 1, z + 1), (x, z + 1)):
            cmd(f"item replace block {hx} {Y} {hz} container.0 with minecraft:cobblestone 64")

# Observer clocks with sticky pistons in the north-east quadrant.
for x in range(2, 94, 4):
    for z in range(-94, -3, 4):
        cmd(f"setblock {x} {Y} {z} minecraft:observer[facing=north]")
        cmd(f"setblock {x} {Y} {z + 1} minecraft:observer[facing=south]")
        cmd(f"setblock {x + 1} {Y} {z} minecraft:sticky_piston[facing=east]")
        cmd(f"setblock {x + 2} {Y} {z} minecraft:slime_block")

# Wheat farm in the south-west quadrant.
cmd(f"fill -96 {Y - 1} 0 -1 {Y - 1} 95 minecraft:farmland[moisture=7]")
for z in range(4, 96, 9):
    cmd(f"fill -96 {Y - 1} {z} -1 {Y - 1} {z} minecraft:water")
cmd(f"fill -96 {Y} 0 -1 {Y} 95 minecraft:wheat[age=0] replace minecraft:air")
for z in range(4, 96, 9):
    cmd(f"fill -96 {Y} {z} -1 {Y} {z} minecraft:air")

# Animal pen in the south-east quadrant.
cmd(f"fill 0 {Y} 0 95 {Y + 3} 0 minecraft:glass")
cmd(f"fill 0 {Y} 95 95 {Y + 3} 95 minecraft:glass")
cmd(f"fill 0 {Y} 0 0 {Y + 3} 95 minecraft:glass")
cmd(f"fill 95 {Y} 0 95 {Y + 3} 95 minecraft:glass")
kinds = ["cow"] * 4 + ["sheep"] * 3 + ["pig"] * 3 + ["villager"] * 2 + ["zombie"] * 2 + ["chicken"] * 2
rng = random.Random(1)
n = mob_count
for i in range(mob_count):
    kind = kinds[i % len(kinds)]
    x = rng.uniform(2, 93)
    z = rng.uniform(2, 93)
    cmd(f"summon minecraft:{kind} {x:.2f} {Y} {z:.2f} {{PersistenceRequired:1b}}")

cmd(f"sleep {run_seconds}")
cmd("tick query")
cmd("tick freeze")
cmd("sleep 1")
for x, z in rings:
    for hx, hz in ((x, z), (x + 1, z), (x + 1, z + 1), (x, z + 1)):
        cmd(f"data get block {hx} {Y} {hz} Items")

for kind in sorted(set(kinds)):
    cmd(f"execute if entity @e[type=minecraft:{kind}]")
cmd("execute if entity @e[type=minecraft:item]")
cmd("tick unfreeze")

with open(out, "w") as f:
    f.write("\n".join(lines) + "\n")

print(f"{len(lines)} commands, {len(rings)} hopper rings holding {len(rings) * 4 * 64} items, {n} mobs")
