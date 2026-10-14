#!/usr/bin/env python3
"""Writes a console script that exercises less common world systems under world workers:
explosions, portals, redstone dust and comparators, minecarts, villages, fire, fluids, sculk,
and a large TNT cube that goes off while everything else runs.

It checks for errors, hangs and entity counts rather than exact outcomes. Lines starting with
"say check <name>" are followed by one command whose "Test passed" or "Test failed" result
check.py reports as that check.

usage: gen_misc.py <commands-out> [run-seconds] [tnt-cube-size]
"""
import sys

out = sys.argv[1]
run_seconds = int(sys.argv[2]) if len(sys.argv) > 2 else 60
cube = int(sys.argv[3]) if len(sys.argv) > 3 else 31
Y = 200
lines = []
cmd = lines.append

cmd("gamerule advance_time true")
cmd("gamerule spawn_mobs false")
cmd("gamerule random_tick_speed 30")
cmd("gamerule fire_spread_radius_around_player 128")
cmd("forceload add -96 -96 95 95")
# Wait until the corner chunks are loaded rather than a fixed 15 s.
cmd("poll 60 execute if loaded -96 0 -96 if loaded 95 0 -96 if loaded -96 0 95 if loaded 95 0 95")
for x0 in (-96, 0):
    for z0 in (-96, 0):
        cmd(f"fill {x0} {Y - 1} {z0} {x0 + 95} {Y - 1} {z0 + 95} minecraft:stone")
        cmd(f"fill {x0} {Y} {z0} {x0 + 95} {Y + 6} {z0 + 95} minecraft:air")

# Redstone dust lines driven by observer clocks, feeding comparators that read hopper fill levels.
for z in range(-94, -40, 3):
    cmd(f"setblock -94 {Y} {z} minecraft:observer[facing=east]")
    cmd(f"setblock -95 {Y} {z} minecraft:observer[facing=west]")
    cmd(f"fill -93 {Y} {z} -60 {Y} {z} minecraft:redstone_wire")
    cmd(f"setblock -59 {Y} {z} minecraft:redstone_lamp")
    cmd(f"setblock -50 {Y} {z} minecraft:hopper")
    cmd(f"item replace block -50 {Y} {z} container.0 with minecraft:dirt 32")
    cmd(f"setblock -49 {Y} {z} minecraft:comparator[facing=west]")
    cmd(f"fill -48 {Y} {z} -40 {Y} {z} minecraft:redstone_wire")

# Rails with minecarts, hopper minecarts and chest minecarts on loops.
for z in range(-36, -4, 4):
    cmd(f"fill -94 {Y} {z} -40 {Y} {z} minecraft:powered_rail[powered=true,shape=east_west]")
    cmd(f"fill -94 {Y - 1} {z} -40 {Y - 1} {z} minecraft:redstone_block")
    cmd(f"setblock -95 {Y} {z} minecraft:stone")
    cmd(f"setblock -39 {Y} {z} minecraft:stone")
    for x in range(-90, -44, 6):
        kind = ("minecart", "hopper_minecart", "chest_minecart")[(x // 6) % 3]
        cmd(f"summon minecraft:{kind} {x} {Y} {z} {{Motion:[0.8d,0.0d,0.0d]}}")

# Village: beds, workstations and villagers in a glass pen.
cmd(f"fill 2 {Y} -94 60 {Y + 4} -94 minecraft:glass")
cmd(f"fill 2 {Y} -40 60 {Y + 4} -40 minecraft:glass")
cmd(f"fill 2 {Y} -94 2 {Y + 4} -40 minecraft:glass")
cmd(f"fill 60 {Y} -94 60 {Y + 4} -40 minecraft:glass")
stations = ["composter", "lectern", "barrel", "blast_furnace", "smoker", "cartography_table", "fletching_table", "loom", "stonecutter"]
n = 0
for x in range(6, 58, 5):
    for z in range(-90, -44, 5):
        cmd(f"setblock {x} {Y} {z} minecraft:red_bed[facing=south,part=head]")
        cmd(f"setblock {x} {Y} {z - 1} minecraft:red_bed[facing=south,part=foot]")
        cmd(f"setblock {x + 2} {Y} {z} minecraft:{stations[n % len(stations)]}")
        cmd(f"summon minecraft:villager {x + 1} {Y} {z - 2} {{PersistenceRequired:1b}}")
        n += 1
cmd(f"setblock 30 {Y} -60 minecraft:bell")

# Nether portal with zombified piglins and chickens pushed through it by water.
cmd(f"fill 70 {Y} -80 74 {Y + 4} -80 minecraft:obsidian")
cmd(f"fill 71 {Y + 1} -80 73 {Y + 3} -80 minecraft:air")
cmd(f"setblock 72 {Y + 1} -80 minecraft:fire")
for i in range(30):
    kind = "chicken" if i % 2 else "pig"
    cmd(f"summon minecraft:{kind} {71.5 + (i % 3)} {Y + 1} {-78.5 + (i % 4) * 0.2}")
cmd(f"fill 70 {Y} -75 74 {Y} -75 minecraft:water")

# Fluids: lava and water fountains that meet and make cobblestone and obsidian.
for x in range(4, 90, 12):
    cmd(f"setblock {x} {Y + 5} 10 minecraft:lava")
    cmd(f"setblock {x + 6} {Y + 5} 10 minecraft:water")

# Fire spreading over a wool and log field.
cmd(f"fill 4 {Y} 40 60 {Y} 60 minecraft:oak_log")
cmd(f"fill 4 {Y + 1} 40 60 {Y + 1} 60 minecraft:white_wool")
cmd(f"setblock 30 {Y + 2} 50 minecraft:fire")

# Sculk sensors next to note blocks driven by clocks.
for x in range(-90, -10, 6):
    cmd(f"setblock {x} {Y} 70 minecraft:observer[facing=east]")
    cmd(f"setblock {x - 1} {Y} 70 minecraft:observer[facing=west]")
    cmd(f"setblock {x + 1} {Y} 70 minecraft:note_block")
    cmd(f"setblock {x + 1} {Y} 73 minecraft:sculk_sensor")
    cmd(f"setblock {x + 1} {Y} 74 minecraft:redstone_lamp")

# TNT: chains that detonate while everything else runs, plus mobs nearby to take damage.
for x in range(-90, -10, 10):
    for z in range(20, 60, 10):
        cmd(f"fill {x} {Y} {z} {x + 2} {Y + 2} {z + 2} minecraft:tnt")
        cmd(f"summon minecraft:cow {x + 5} {Y} {z + 5}")

# TNT cube on a floor over the middle of the area. Thousands of explosions overlap the same chunk
# sections and entities, and the debris rains onto everything below. Some TNT is flung out of
# the loaded area and stays there, as in vanilla.
CY = Y + 30
c0 = -(cube // 2)
c1 = c0 + cube - 1
# A floor keeps the first primed TNT from falling away before it sets off the rest.
cmd(f"fill {c0 - 1} {CY - 1} {c0 - 1} {c1 + 1} {CY - 1} {c1 + 1} minecraft:stone")
cmd(f"fill {c0} {CY} {c0} {c1} {CY + cube - 1} {c1} minecraft:tnt")

cmd("sleep 1")
for x in range(-90, -10, 10):
    for z in range(20, 60, 10):
        cmd(f"setblock {x - 1} {Y} {z} minecraft:redstone_block")
cmd(f"setblock {c0 - 1} {CY} {c0} minecraft:redstone_block")

cmd(f"sleep {run_seconds}")
cmd("tick query")
for kind in ("villager", "minecart", "hopper_minecart", "chest_minecart", "pig", "chicken", "cow", "item", "tnt"):
    cmd(f"execute if entity @e[type=minecraft:{kind}]")
cmd("execute in minecraft:the_nether if entity @e[type=minecraft:chicken]")
cmd("execute in minecraft:the_nether if entity @e[type=minecraft:pig]")
# Every TNT that stayed well inside the loaded area has gone off. The cube takes longer than a
# short load window, so wait for it (up to 30 s) instead of sizing the window for it.
cmd("poll 30 execute unless entity @e[type=minecraft:tnt,x=-64,y=-64,z=-64,dx=128,dy=384,dz=128]")
cmd("say check tnt-cube-done")
cmd("execute unless entity @e[type=minecraft:tnt,x=-64,y=-64,z=-64,dx=128,dy=384,dz=128]")
cmd("say check tnt-cube-blocks-gone")
cmd(f"execute unless block {c0 + cube // 2} {CY + cube // 2} {c0 + cube // 2} minecraft:tnt")

with open(out, "w") as f:
    f.write("\n".join(lines) + "\n")

print(f"{len(lines)} commands, {n} villagers")
