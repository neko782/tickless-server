#!/usr/bin/env python3
"""Item conservation soundness test: a random graph of containers moving random items.

Two 32x32 layers of containers inside a glass box:
  top (Y):      chests, barrels, waxed copper chests, hoppers pointing sideways or down, and
                dispensers firing items up into the box
  bottom (Y-1): hoppers pointing at random neighbours, droppers pushing up into the top layer
                (observer clocks below), and observer clocks driving the dispensers above
Above the top layer, hopper minecarts ride a powered rail loop (hoppers under the rails drain
them, and they pick up dispensed items), and waxed copper golems carry items from copper chests
to chests. Items are only ones dispensers drop as items (no special dispense behaviour), in
stacks of mixed sizes, including unstackable and renamed ones.

At the end the world is frozen and every container, dropped item, minecart and golem hand is
read back; check_graph.py compares the per-item totals with the ones placed here.

usage: gen_graph.py OUT_COMMANDS OUT_EXPECTED_JSON [SECONDS] [SEED]
"""
import json
import random
import sys

out = sys.argv[1]
expected_out = sys.argv[2]
run_seconds = int(sys.argv[3]) if len(sys.argv) > 3 else 12
rng = random.Random(int(sys.argv[4]) if len(sys.argv) > 4 else 20260930)

N = 32
Y = 120
X0 = 0
Z0 = 0
# Items without dispenser behaviour: a dispenser drops them as item entities, never uses them.
STACKABLE = ["cobblestone", "dirt", "oak_planks", "stick", "iron_ingot", "gold_ingot", "diamond",
             "emerald", "coal", "redstone", "string", "feather", "paper", "sugar", "wheat", "apple",
             "clay_ball", "flint", "leather", "glass", "white_wool", "book", "quartz", "lapis_lazuli",
             "amethyst_shard", "slime_ball", "copper_ingot"]
SIXTEEN = ["oak_sign", "white_banner"]
SINGLE = ["iron_sword", "name_tag"]

lines = []
expected = {}


def cmd(c):
    lines.append(c)


def random_stack():
    r = rng.random()
    if r < 0.08:
        item, count = rng.choice(SINGLE), 1
        if item == "name_tag":
            count = rng.randint(1, 8)
    elif r < 0.15:
        item, count = rng.choice(SIXTEEN), rng.randint(1, 16)
    else:
        item, count = rng.choice(STACKABLE), rng.randint(1, 64)
    extra = ""
    if rng.random() < 0.1:
        # Renamed stacks do not merge with plain ones.
        extra = ',components:{"minecraft:custom_name":"weird"}'
    expected[item] = expected.get(item, 0) + count
    return item, count, extra


def items_nbt(slots, fill):
    chosen = rng.sample(range(slots), min(slots, fill))
    parts = []
    for slot in chosen:
        item, count, extra = random_stack()
        parts.append(f'{{Slot:{slot}b,id:"minecraft:{item}",count:{count}{extra}}}')
    return "[" + ",".join(parts) + "]"


def inside(x, z):
    return 0 <= x < N and 0 <= z < N


DIRS = {"north": (0, -1), "south": (0, 1), "west": (-1, 0), "east": (1, 0)}

# Rail loop over the top layer, with a barrel under each redstone block that powers it.
loop = []
for x in range(4, N - 4):
    loop.append((x, 4))
for z in range(5, N - 4):
    loop.append((N - 5, z))
for x in range(N - 6, 3, -1):
    loop.append((x, N - 5))
for z in range(N - 6, 4, -1):
    loop.append((4, z))
powered = set(loop[::6])
redstone = {}
for x, z in powered:
    # The outer side of the loop.
    ox = x - 1 if x == 4 else x + 1 if x == N - 5 else x
    oz = z - 1 if z == 4 else z + 1 if z == N - 5 else z
    if (ox, oz) == (x, z):
        continue
    redstone[(x, z)] = (ox, oz)
under_redstone = set(redstone.values())
loop_cells = set(loop)

# Bottom layer first: which cells drive a dispenser above, push items up, or are hoppers.
bottom = {}
for x in range(N):
    for z in range(N):
        r = rng.random()
        bottom[(x, z)] = "driver" if r < 0.06 else "dropper" if r < 0.2 else "hopper"
top = {}
for x in range(N):
    for z in range(N):
        if (x, z) in under_redstone:
            top[(x, z)] = "barrel"
        elif (x, z) in loop_cells:
            # Rails need a full top face: chests are too short.
            top[(x, z)] = "hopper" if bottom[(x, z)] != "driver" and rng.random() < 0.7 else "barrel"
        elif bottom[(x, z)] == "driver":
            top[(x, z)] = "dispenser"
        else:
            top[(x, z)] = rng.choices(["chest", "barrel", "copper", "hopper"], [3, 2, 2, 5])[0]


def bottom_target_ok(x, z):
    return inside(x, z) and bottom[(x, z)] in ("hopper", "dropper")


def top_target_ok(x, z):
    return inside(x, z)


cmd("gamerule spawn_mobs false")
cmd("gamerule advance_time false")
cmd("gamerule advance_weather false")
cmd("gamerule random_tick_speed 0")
cmd(f"forceload add {X0 - 16} {Z0 - 16} {X0 + N + 15} {Z0 + N + 15}")
cmd(f"poll 60 execute if loaded {X0 - 1} 0 {Z0 - 1} if loaded {X0 + N} 0 {Z0 + N}")
# Glass box: walls from below the clocks to the roof.
cmd(f"fill {X0 - 1} {Y - 4} {Z0 - 1} {X0 + N} {Y + 5} {Z0 + N} minecraft:glass hollow")

for (x, z), kind in bottom.items():
    px, pz = X0 + x, Z0 + z
    if kind == "driver":
        # Observer clock whose top observer fires into the dispenser above.
        cmd(f"setblock {px} {Y - 2} {pz} minecraft:observer[facing=up]")
        cmd(f"setblock {px} {Y - 1} {pz} minecraft:observer[facing=down]")
    elif kind == "dropper":
        cmd(f"setblock {px} {Y - 3} {pz} minecraft:observer[facing=up]")
        cmd(f"setblock {px} {Y - 2} {pz} minecraft:observer[facing=down]")
        cmd(f"setblock {px} {Y - 1} {pz} minecraft:dropper[facing=up]{{Items:{items_nbt(9, rng.randint(1, 5))}}}")
    else:
        options = [d for d, (dx, dz) in DIRS.items() if bottom_target_ok(x + dx, z + dz)]
        if not options:
            bottom[(x, z)] = "dropper"
            cmd(f"setblock {px} {Y - 3} {pz} minecraft:observer[facing=up]")
            cmd(f"setblock {px} {Y - 2} {pz} minecraft:observer[facing=down]")
            cmd(f"setblock {px} {Y - 1} {pz} minecraft:dropper[facing=up]{{Items:{items_nbt(9, rng.randint(1, 5))}}}")
            continue
        facing = rng.choice(options)
        cmd(f"setblock {px} {Y - 1} {pz} minecraft:hopper[facing={facing}]{{Items:{items_nbt(5, rng.randint(0, 3))}}}")

for (x, z), kind in top.items():
    px, pz = X0 + x, Z0 + z
    if kind == "dispenser":
        cmd(f"setblock {px} {Y} {pz} minecraft:dispenser[facing=up]{{Items:{items_nbt(9, rng.randint(1, 5))}}}")
    elif kind == "chest":
        facing = rng.choice(list(DIRS))
        cmd(f"setblock {px} {Y} {pz} minecraft:chest[facing={facing},type=single]{{Items:{items_nbt(27, rng.randint(3, 12))}}}")
    elif kind == "copper":
        facing = rng.choice(list(DIRS))
        cmd(f"setblock {px} {Y} {pz} minecraft:waxed_copper_chest[facing={facing},type=single]{{Items:{items_nbt(27, rng.randint(3, 12))}}}")
    elif kind == "barrel":
        cmd(f"setblock {px} {Y} {pz} minecraft:barrel{{Items:{items_nbt(27, rng.randint(3, 12))}}}")
    else:
        options = [d for d, (dx, dz) in DIRS.items() if top_target_ok(x + dx, z + dz)]
        if bottom[(x, z)] in ("hopper", "dropper"):
            options += ["down"] * 2
        facing = rng.choice(options)
        cmd(f"setblock {px} {Y} {pz} minecraft:hopper[facing={facing}]{{Items:{items_nbt(5, rng.randint(0, 3))}}}")

for (x, z) in loop:
    rail = "powered_rail[powered=true]" if (x, z) in redstone else "rail"
    cmd(f"setblock {X0 + x} {Y + 1} {Z0 + z} minecraft:{rail}")
for (ox, oz) in under_redstone:
    cmd(f"setblock {X0 + ox} {Y + 1} {Z0 + oz} minecraft:redstone_block")

# Hopper minecarts on the loop, pushed along its first side.
for i in range(10):
    x, z = loop[i * (len(loop) // 10)]
    cmd(f"summon minecraft:hopper_minecart {X0 + x + 0.5} {Y + 1.1} {Z0 + z + 0.5} "
        f"{{Items:{items_nbt(5, rng.randint(1, 4))},Motion:[{rng.choice([-0.6, 0.6])}d,0d,{rng.choice([-0.6, 0.6])}d]}}")
# Waxed copper golems (they would turn into statues as they oxidize) walking the top layer.
for i in range(12):
    x, z = rng.randrange(1, N - 1), rng.randrange(1, N - 1)
    cmd(f"summon minecraft:copper_golem {X0 + x + 0.5} {Y + 1} {Z0 + z + 0.5} {{next_weather_age:-2L,PersistenceRequired:1b}}")

cmd(f"sleep {run_seconds}")
cmd("tick query")
cmd("tick freeze")
cmd("sleep 1")
cmd("say graph-count-begin")
for (x, z), kind in bottom.items():
    if kind != "driver":
        cmd(f"data get block {X0 + x} {Y - 1} {Z0 + z} Items")
for (x, z) in top:
    cmd(f"data get block {X0 + x} {Y} {Z0 + z} Items")
cmd("execute as @e[type=minecraft:item] run data get entity @s Item")
cmd("execute as @e[type=minecraft:hopper_minecart] run data get entity @s Items")
cmd("execute as @e[type=minecraft:copper_golem] run data get entity @s equipment")
cmd("say graph-count-end")
cmd("say check graph-no-other-item-holders")
cmd("execute unless entity @e[type=!minecraft:item,type=!minecraft:hopper_minecart,type=!minecraft:copper_golem,x=%d,y=%d,z=%d,dx=%d,dy=10,dz=%d]" % (X0 - 1, Y - 5, Z0 - 1, N + 2, N + 2))
cmd("tick unfreeze")

with open(out, "w") as f:
    f.write("\n".join(lines) + "\n")
with open(expected_out, "w") as f:
    json.dump(expected, f, sort_keys=True)
print(f"{len(lines)} commands, {sum(expected.values())} items of {len(expected)} kinds")
