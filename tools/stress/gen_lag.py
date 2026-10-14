#!/usr/bin/env python3
"""Writes a console script with classic lag machines, one per zone:

  fluids    water and lava streams gated by fast piston clocks, so they never settle
  items     thousands of dropped items that cannot merge or despawn
  carts     piles of minecarts on single rail blocks (every cart collides with every other)
  redstone  a redstone dust field over a floor of redstone lamps, toggled by clocks
            (neighbour update storms and light updates)
  furnaces  thousands of furnaces smelting

The timed window is marked with "say BENCH_START" / "say BENCH_END", each followed by
"time query gametime", so vanilla and tickless runs can be compared. Named checks
("say check <name>") confirm that items and carts survive.

usage: gen_lag.py <commands-out> [run-seconds] [scale] [zone...]
  scale multiplies the item, cart and furnace counts (default 1).
"""
import random
import sys

out = sys.argv[1]
run_seconds = int(sys.argv[2]) if len(sys.argv) > 2 else 30
scale = float(sys.argv[3]) if len(sys.argv) > 3 else 1.0
zones = set(sys.argv[4:]) or {"fluids", "items", "carts", "redstone", "furnaces"}
Y = 200
lines = []
cmd = lines.append
rng = random.Random(1)

cmd("gamerule advance_time false")
cmd("gamerule advance_weather false")
cmd("gamerule spawn_mobs false")
cmd("gamerule max_entity_cramming 0")
cmd("scoreboard objectives add lag dummy")
cmd("time set 18000")
cmd("forceload add -96 -96 95 95")
cmd("sleep 15")
for x0 in (-96, 0):
    for z0 in (-96, 0):
        cmd(f"fill {x0} {Y - 1} {z0} {x0 + 95} {Y - 1} {z0 + 95} minecraft:stone")
        cmd(f"fill {x0} {Y} {z0} {x0 + 95} {Y + 6} {z0 + 95} minecraft:air")


def walls(x0, z0, x1, z1, height=3):
    cmd(f"fill {x0} {Y} {z0} {x1} {Y + height} {z0} minecraft:glass")
    cmd(f"fill {x0} {Y} {z1} {x1} {Y + height} {z1} minecraft:glass")
    cmd(f"fill {x0} {Y} {z0} {x0} {Y + height} {z1} minecraft:glass")
    cmd(f"fill {x1} {Y} {z0} {x1} {Y + height} {z1} minecraft:glass")


gates = 0
if "fluids" in zones:
    # North-west quadrant. Each gate is an observer clock driving a sticky piston that pushes a
    # stone block in and out of a fluid stream next to a source, so the stream keeps draining and
    # refilling. Water in the north half, lava in the south half, walled apart.
    walls(-96, -96, -1, -1)
    cmd(f"fill -96 {Y} -48 -1 {Y + 3} -48 minecraft:glass")
    for fluid, z_range in (("water", range(-94, -52, 7)), ("lava", range(-44, -4, 7))):
        for z in z_range:
            for x in range(-94, -8, 7):
                cmd(f"setblock {x} {Y} {z} minecraft:observer[facing=north]")
                cmd(f"setblock {x} {Y} {z + 1} minecraft:observer[facing=south]")
                cmd(f"setblock {x + 1} {Y} {z} minecraft:sticky_piston[facing=east]")
                cmd(f"setblock {x + 2} {Y} {z} minecraft:stone")
                cmd(f"setblock {x + 4} {Y} {z} minecraft:{fluid}")
                gates += 1

items = int(6000 * scale)
if "items" in zones:
    # North-east quadrant, west half. Unique custom data keeps items from merging, but each one
    # still searches for merge partners; Age -32768 keeps them from despawning.
    walls(0, -96, 47, -1)
    kinds = ["stone", "dirt", "cobblestone", "oak_planks", "sand", "gravel", "iron_ingot", "gold_ingot"]
    for i in range(items):
        x = rng.uniform(1.5, 46.5)
        z = rng.uniform(-95.5, -1.5)
        kind = kinds[i % len(kinds)]
        cmd(
            f"summon minecraft:item {x:.2f} {Y + 0.5} {z:.2f} "
            f'{{Age:-32768s,PickupDelay:32767s,Item:{{id:"minecraft:{kind}",count:1,components:{{"minecraft:custom_data":{{n:{i}}}}}}}}}'
        )

piles = 0
carts_per_pile = int(60 * scale)
if "carts" in zones:
    # North-east quadrant, east half: minecarts piled on single rail blocks inside glass cells.
    for x in range(52, 94, 8):
        for z in range(-92, -4, 8):
            walls(x - 1, z - 1, x + 1, z + 1, 2)
            cmd(f"setblock {x} {Y} {z} minecraft:rail")
            for _ in range(carts_per_pile):
                cmd(f"summon minecraft:minecart {x + 0.5} {Y} {z + 0.5}")
            piles += 1

clocks = 0
if "redstone" in zones:
    # South-west quadrant: a floor of redstone lamps under a field of redstone dust, with observer
    # clocks every 8 blocks so every wire and lamp toggles.
    cmd(f"fill -94 {Y - 1} 2 -3 {Y - 1} 93 minecraft:redstone_lamp")
    cmd(f"fill -94 {Y} 2 -3 {Y} 93 minecraft:redstone_wire")
    for x in range(-92, -4, 8):
        for z in range(4, 92, 8):
            cmd(f"setblock {x} {Y} {z} minecraft:observer[facing=north]")
            cmd(f"setblock {x} {Y} {z + 1} minecraft:observer[facing=south]")
            clocks += 1

furnaces = 0
if "furnaces" in zones:
    # South-east quadrant: furnaces smelting a full stack of raw iron with coal.
    target = int(3000 * scale)
    for y in range(Y, Y + 6):
        for x in range(1, 95, 2):
            for z in range(1, 95, 2):
                if furnaces >= target:
                    break
                cmd(
                    f"setblock {x} {y} {z} minecraft:furnace{{Items:["
                    f'{{Slot:0b,id:"minecraft:raw_iron",count:64}},{{Slot:1b,id:"minecraft:coal",count:64}}]}}'
                )
                furnaces += 1

cmd("sleep 5")
cmd("say BENCH_START")
cmd("time query gametime")
cmd(f"sleep {run_seconds}")
cmd("say BENCH_END")
cmd("time query gametime")
cmd("tick query")
if "items" in zones:
    # Our items carry custom data; print any other item so its source can be found.
    cmd('execute as @e[type=minecraft:item,nbt=!{Item:{components:{"minecraft:custom_data":{}}}}] run data get entity @s Item')
    cmd('execute store result score #items lag if entity @e[type=minecraft:item,nbt={Item:{components:{"minecraft:custom_data":{}}}}]')
    cmd("say check items-kept")
    cmd(f"execute if score #items lag matches {items}")
if "carts" in zones:
    cmd("execute store result score #carts lag if entity @e[type=minecraft:minecart]")
    cmd("say check carts-kept")
    cmd(f"execute if score #carts lag matches {piles * carts_per_pile}")
if "fluids" in zones:
    cmd("say check water-flowing")
    cmd(f"execute if block -89 {Y} -94 minecraft:water")

with open(out, "w") as f:
    f.write("\n".join(lines) + "\n")

print(f"{len(lines)} commands: {gates} fluid gates, {items if 'items' in zones else 0} items, "
      f"{piles} cart piles of {carts_per_pile}, {clocks} redstone clocks, {furnaces} furnaces")
