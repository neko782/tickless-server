#!/usr/bin/env python3
"""Writes a console script for one heavy non-mob workload:

  tnt       a solid TNT cube lit at one corner; the chain reaction runs until no TNT is left
            (explosions, block breaking, item drops, light updates)
  sand      a floating sheet of sand that falls at once (falling block entities, landing
            block updates)
  light     piston clocks shoving glowstone back and forth under a roof (block light
            updates, which the light engine processes on one thread per dimension)
  chunkgen  force-loads a square of never-generated chunks far from spawn (world generation,
            lighting and chunk saving); progress shows as "loaded chunks" in the stats lines

Finite workloads (tnt, sand, chunkgen) poll every 2 seconds with a command whose result
bench_heavy.py reads to find when the work finished. Continuous workloads (light) mark a
timed window with "say BENCH_START" / "say BENCH_END".

usage: gen_heavy.py <workload> <commands-out> [size]
  size: tnt cube edge (default 32), sand sheet edge (default 160), light gate count
  (default 1200), chunkgen square edge in chunks (default 32).
"""
import sys

workload = sys.argv[1]
out = sys.argv[2]
size = int(sys.argv[3]) if len(sys.argv) > 3 else None
Y = 200
lines = []
cmd = lines.append


def poll(test, seconds):
    """Runs `test` every 2 s until it passes, for up to `seconds` (a run.sh directive)."""
    cmd(f"poll {seconds} {test}")


def fill(x0, y0, z0, x1, y1, z1, block, extra=""):
    """fill limited to 32768 blocks per command, split along x."""
    per_x = (y1 - y0 + 1) * (z1 - z0 + 1)
    step = max(1, 32768 // per_x)
    x = x0
    while x <= x1:
        cmd(f"fill {x} {y0} {z0} {min(x1, x + step - 1)} {y1} {z1} {block}{extra}")
        x += step


cmd("gamerule advance_time false")
cmd("gamerule advance_weather false")
cmd("gamerule spawn_mobs false")
cmd("time set 18000")

if workload == "chunkgen":
    edge = size or 32
    # Far from spawn so nothing there was generated before. forceload takes 256 chunks at most.
    base = 20000
    # Vanilla loads force-loaded chunks inside the command, so the timer starts before it.
    cmd("say BENCH_START")
    cmd("time query gametime")
    for cx in range(0, edge, 16):
        for cz in range(0, edge, 16):
            x0 = base + cx * 16
            z0 = base + cz * 16
            x1 = x0 + min(16, edge - cx) * 16 - 1
            z1 = z0 + min(16, edge - cz) * 16 - 1
            cmd(f"forceload add {x0} {z0} {x1} {z1}")
    # The far corner chunk is one of the last to be ready in every order.
    far = base + edge * 16 - 8
    poll(f"execute if loaded {far} {Y} {far}", 150)
    cmd("say BENCH_END")
    cmd("time query gametime")
else:
    cmd("forceload add -96 -96 95 95")
    cmd("sleep 15")
    fill(-96, Y - 1, -96, 95, Y - 1, 95, "minecraft:stone")
    fill(-96, Y, -96, 95, Y + 40, 95, "minecraft:air")

    if workload == "tnt":
        edge = size or 32
        x0 = -edge // 2
        fill(x0, Y, x0, x0 + edge - 1, Y + edge - 1, x0 + edge - 1, "minecraft:tnt")
        cmd("sleep 3")
        cmd("say BENCH_START")
        cmd("time query gametime")
        cmd(f"setblock {x0 - 1} {Y} {x0} minecraft:redstone_block")
        cmd("sleep 2")
        # Explosions fling some TNT out of the loaded square, where it freezes as in vanilla.
        poll("execute unless entity @e[type=minecraft:tnt,x=-80,y=0,z=-80,dx=159,dy=400,dz=159]", 120)
        cmd("say BENCH_END")
        cmd("time query gametime")
        cmd("say check tnt-gone")
        cmd(f"execute unless block {x0 + edge // 2} {Y + edge // 2} {x0 + edge // 2} minecraft:tnt")
    elif workload == "sand":
        edge = size or 160
        x0 = -edge // 2
        # A sheet four blocks thick, 30 blocks up. Placing sand next to air schedules its fall.
        cmd("say BENCH_START")
        cmd("time query gametime")
        fill(x0, Y + 30, x0, x0 + edge - 1, Y + 33, x0 + edge - 1, "minecraft:sand")
        cmd("sleep 2")
        poll("execute unless entity @e[type=minecraft:falling_block,x=-80,y=0,z=-80,dx=159,dy=400,dz=159]", 120)
        cmd("say BENCH_END")
        cmd("time query gametime")
        cmd("say check sand-landed")
        cmd(f"execute if block 0 {Y + 3} 0 minecraft:sand")
        # Sand that lands on another falling block's spot drops as an item.
        cmd("say check sand-no-drops")
        cmd("execute unless entity @e[type=minecraft:item]")
    elif workload == "light":
        gates = size or 1200
        # A roof keeps out skylight so every glowstone move changes block light. Each gate is an
        # observer clock driving a sticky piston that pushes glowstone one block and back.
        fill(-96, Y + 4, -96, 95, Y + 4, 95, "minecraft:stone")
        placed = 0
        # Two observers watching each other make a clock; the north one's output (its back) powers a
        # sticky piston that shoves glowstone west and pulls it back.
        for z in range(-93, 94, 4):
            for x in range(-92, 95, 4):
                if placed >= gates:
                    break
                cmd(f"setblock {x - 1} {Y} {z - 1} minecraft:glowstone")
                cmd(f"setblock {x} {Y} {z - 1} minecraft:sticky_piston[facing=west]")
                cmd(f"setblock {x} {Y} {z} minecraft:observer[facing=south]")
                cmd(f"setblock {x} {Y} {z + 1} minecraft:observer[facing=north]")
                placed += 1
        cmd("sleep 5")
        cmd("say BENCH_START")
        cmd("time query gametime")
        cmd("sleep 30")
        cmd("say BENCH_END")
        cmd("time query gametime")
    else:
        sys.exit(f"unknown workload {workload}")

with open(out, "w") as f:
    f.write("\n".join(lines) + "\n")
print(f"{workload}: {len(lines)} commands")
