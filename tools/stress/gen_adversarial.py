#!/usr/bin/env python3
"""Adversarial conservation workloads for adversarial.py.

Every workload builds a contraption while the clock is frozen, takes a baseline audit, runs
it under load while chunks unload and reload, entities teleport and the world saves, and then
audits again while frozen. An audit dumps every item holder (containers, item entities, item
frames, minecarts, allay, villager and armor stand inventories) and the UUID of every tagged
entity. adversarial.py compares each later audit with the baseline.

Each workload is a list of phases; each phase is one server start on the same world.
"""
import json

WORKLOADS = ('churn', 'restart', 'portal', 'portal_rush', 'boom', 'storage')
Y = 200


def item(id_, count, slot=None):
    value = {'id': f'minecraft:{id_}', 'count': count}
    if slot is not None:
        value['Slot'] = slot
    return value


def nbt(value):
    return json.dumps(value)


class Script:
    def __init__(self):
        self.phases = [[]]
        self.audits = []        # (phase, name, mode); mode 'equal', 'atmost' or 'none'
        self.checks = []
        self.holders = []       # container block positions
        self.expect_growth = {}  # items a 'grow' audit adds to the baseline
        self.areas = []         # (dim, x0, y0, z0, x1, y1, z1): where held items are counted

    @property
    def lines(self):
        return self.phases[-1]

    def cmd(self, line):
        self.lines.append(line)

    def new_phase(self):
        self.phases.append([])

    def check(self, name, condition):
        self.checks.append((len(self.phases) - 1, name))
        self.cmd(f'say check {name}')
        self.cmd(f'execute {condition}')

    def area(self, x0, z0, x1, z1, dim='overworld', y0=Y - 8, y1=Y + 45):
        self.areas.append((dim, x0, y0, z0, x1, y1, z1))

    def container(self, x, y, z, block, items):
        self.cmd(f'setblock {x} {y} {z} minecraft:{block}' + '{Items:' + nbt(items) + '}')
        self.holders.append((x, y, z, 'overworld'))

    def audit(self, name, mode='equal'):
        self.audits.append((len(self.phases) - 1, name, mode))
        self.cmd(f'say audit {name} begin')
        self.cmd('say kind items')
        for x, y, z, dim in self.holders:
            self.cmd(f'execute in minecraft:{dim} run data get block {x} {y} {z} Items')
        for kind, path in (('item', 'Item'), ('item_frame', 'Item'), ('glow_item_frame', 'Item'),
                           ('chest_minecart', 'Items'), ('hopper_minecart', 'Items'),
                           ('allay', 'Inventory'), ('allay', 'equipment'), ('villager', 'Inventory'),
                           ('armor_stand', 'equipment'), ('piglin', 'Inventory'), ('fox', 'equipment')):
            for dim, x0, y0, z0, x1, y1, z1 in self.areas:
                box = f'x={x0},y={y0},z={z0},dx={x1 - x0},dy={y1 - y0},dz={z1 - z0}'
                self.cmd(f'execute in minecraft:{dim} as @e[type=minecraft:{kind},{box}] if data entity @s {path} run data get entity @s {path}')
        self.cmd('say kind uuid')
        self.cmd('execute as @e[tag=adv] run data get entity @s UUID')
        self.cmd(f'say audit {name} end')


def floor(s, x0, z0, x1, z1, height=8, dim='overworld'):
    pre = f'execute in minecraft:{dim} run ' if dim != 'overworld' else ''
    s.cmd(f'{pre}fill {x0} {Y - 1} {z0} {x1} {Y - 1} {z1} minecraft:stone')
    s.cmd(f'{pre}fill {x0} {Y} {z0} {x1} {Y + 40} {z1} minecraft:air')
    s.cmd(f'{pre}fill {x0} {Y + height + 1} {z0} {x1} {Y + height + 1} {z1} minecraft:glass')


def walls(s, x0, z0, x1, z1, height=4, block='glass'):
    for a, b, c, d in ((x0, z0, x1, z0), (x0, z1, x1, z1), (x0, z0, x0, z1), (x1, z0, x1, z1)):
        s.cmd(f'fill {a} {Y} {b} {c} {Y + height - 1} {d} minecraft:{block}')


PAYLOAD = ['diamond', 'emerald', 'gold_ingot', 'iron_ingot', 'redstone', 'lapis_lazuli', 'coal', 'quartz', 'amethyst_shard', 'nether_star']


def rail_loop(s, x0, z0, x1, z1, tag):
    """Rectangular rail loop with boosters, hopper drains under it and feeders above it."""
    def shape(x, z):
        corner = {(x0, z0): 'south_east', (x1, z0): 'south_west', (x0, z1): 'north_east', (x1, z1): 'north_west'}
        if (x, z) in corner:
            return corner[(x, z)]
        return 'east_west' if z in (z0, z1) else 'north_south'
    cells = [(x, z0) for x in range(x0, x1 + 1)] + [(x1, z) for z in range(z0 + 1, z1 + 1)] + \
            [(x, z1) for x in range(x1 - 1, x0 - 1, -1)] + [(x0, z) for z in range(z1 - 1, z0, -1)]
    feeders, drains, boosters = [], [], []
    for i, (x, z) in enumerate(cells):
        sh = shape(x, z)
        if sh in ('east_west', 'north_south') and i % 12 == 6:
            boosters.append((x, z))
            s.cmd(f'setblock {x} {Y - 1} {z} minecraft:redstone_block')
            s.cmd(f'setblock {x} {Y} {z} minecraft:powered_rail[shape={sh},powered=true]')
            continue
        if sh in ('east_west', 'north_south') and i % 12 == 2:
            # Drain: hopper under the rail pulls from carts into a chest below.
            s.container(x, Y - 2, z, 'chest', [])
            s.container(x, Y - 1, z, 'hopper[facing=down]', [])
            drains.append((x, z))
        elif sh in ('east_west', 'north_south') and i % 12 == 9:
            # Feeder: chest above a hopper that pushes down into passing carts.
            s.container(x, Y + 2, z, 'chest', [item(PAYLOAD[(i + k) % len(PAYLOAD)], 64, k) for k in range(6)])
            s.container(x, Y + 1, z, 'hopper[facing=down]', [])
            feeders.append((x, z))
        s.cmd(f'setblock {x} {Y} {z} minecraft:rail[shape={sh}]')
    return cells


def churn_layout(s, bx, bz):
    """64x64 blocks (4x4 chunks) of moving items and entities that cross chunk borders."""
    floor(s, bx, bz, bx + 63, bz + 63)
    cells = rail_loop(s, bx + 2, bz + 2, bx + 61, bz + 61, 'loop')
    side = len(cells) // 4
    for i in range(0, len(cells), len(cells) // 16):
        x, z = cells[i]
        mx, mz = ((0.6, 0), (0, 0.6), (-0.6, 0), (0, -0.6))[min(i // side, 3)]
        if i % 2:
            kind, extra = 'chest_minecart', ',Items:' + nbt([item(PAYLOAD[(i + k) % 10], 32, k) for k in range(4)])
        else:
            kind, extra = 'hopper_minecart', ',Items:' + nbt([item(PAYLOAD[(i + k) % 10], 16, k) for k in range(3)])
        if i % 5 == 0:
            kind, extra = 'minecart', ',Passengers:[{id:"minecraft:cow",PersistenceRequired:1b,Tags:["adv","cart_rider"]}]'
        s.cmd(f'summon minecraft:{kind} {x + 0.5} {Y + 0.1} {z + 0.5} {{Tags:["adv"],Motion:[{mx}d,0d,{mz}d]{extra}}}')

    # Hopper rings on internal chunk corners, two layers.
    for cx in (16, 32, 48):
        for cz in (32, 48):
            for layer in (0, 3):
                for dx, dz, facing in ((-1, -1, 'east'), (0, -1, 'south'), (0, 0, 'west'), (-1, 0, 'north')):
                    x, z = bx + cx + dx, bz + cz + dz
                    s.container(x, Y + layer, z, f'hopper[facing={facing}]',
                                [item(PAYLOAD[(cx + cz + k) % 10], 64, k) for k in range(5)])

    # Pen A: allays and villagers collecting loose items, floor hoppers draining into chests,
    # item frames on the walls. It straddles the x and z chunk borders at 16.
    x0, z0, x1, z1 = bx + 8, bz + 8, bx + 25, bz + 25
    walls(s, x0, z0, x1, z1)
    for i in range(6):
        s.cmd(f'summon minecraft:allay {x0 + 3 + i * 2}.5 {Y} {z0 + 4}.5 {{PersistenceRequired:1b,Tags:["adv"],equipment:{{mainhand:{{id:"minecraft:diamond",count:1}}}}}}')
        s.cmd(f'summon minecraft:villager {x0 + 3 + i * 2}.5 {Y} {z0 + 12}.5 {{PersistenceRequired:1b,Tags:["adv"],Inventory:[{{id:"minecraft:bread",count:2}}]}}')
    for i in range(48):
        x, z = x0 + 2 + (i * 5) % 14, z0 + 2 + (i * 7) % 14
        what = ('diamond', 'bread', 'carrot', 'wheat_seeds')[i % 4]
        s.cmd(f'summon minecraft:item {x}.5 {Y + 1} {z}.5 {{PickupDelay:0s,Item:{{id:"minecraft:{what}",count:{1 + i % 5}}}}}')
    for dx in range(4):
        for dz in range(4):
            x, z = bx + 14 + dx, bz + 14 + dz
            s.container(x, Y - 1, z, 'hopper[facing=down]', [])
            s.container(x, Y - 2, z, 'chest' if (dx + dz) % 2 else 'barrel', [])
    for i in range(8):
        x = x0 + 2 + i * 2
        kind = 'item_frame' if i % 2 else 'glow_item_frame'
        s.cmd(f'summon minecraft:{kind} {x} {Y + 1} {z0 + 1} {{Tags:["adv"],Facing:3b,Pos:[{x}.5d,{Y + 1}.5d,{z0 + 1}.03d],block_pos:[I;{x},{Y + 1},{z0 + 1}],Item:{{id:"minecraft:{PAYLOAD[i]}",count:1}}}}')
        s.cmd(f'setblock {x} {Y + 1} {z0} minecraft:stone')

    # Pen B: leashed cows, boats with passengers, ridden pigs, armor stands with items.
    x0, z0, x1, z1 = bx + 36, bz + 8, bx + 55, bz + 25
    walls(s, x0, z0, x1, z1)
    for i in range(8):
        fx, fz = bx + 47 + (i % 2), bz + 10 + i * 2  # fences on the x=48 border
        s.cmd(f'setblock {fx} {Y} {fz} minecraft:oak_fence')
        s.cmd(f'summon minecraft:cow {fx + 3}.5 {Y} {fz}.5 {{PersistenceRequired:1b,Tags:["adv","leashed","leashed_{i}"],leash:[I;{fx},{Y},{fz}]}}')
    for i in range(6):
        s.cmd(f'summon minecraft:oak_boat {x0 + 2 + i * 1.5:.1f} {Y} {z1 - 3}.5 {{Tags:["adv","boat","boat_{i}"],Passengers:[{{id:"minecraft:cow",PersistenceRequired:1b,Tags:["adv","boat_rider"]}}]}}')
        s.cmd(f'summon minecraft:pig {x0 + 2 + i * 1.5:.1f} {Y} {z1 - 6}.5 {{PersistenceRequired:1b,Tags:["adv","pig","pig_{i}"],Passengers:[{{id:"minecraft:chicken",PersistenceRequired:1b,Tags:["adv","pig_rider"]}}]}}')
        s.cmd(f'summon minecraft:armor_stand {x0 + 2 + i * 1.5:.1f} {Y} {z1 - 9}.5 {{Tags:["adv"],equipment:{{mainhand:{{id:"minecraft:{PAYLOAD[i]}",count:7}},head:{{id:"minecraft:iron_helmet",count:1}}}}}}')
    for i in range(10):
        s.cmd(f'summon minecraft:sheep {x0 + 4 + i}.5 {Y} {z0 + 4}.5 {{PersistenceRequired:1b,Tags:["adv","sheep"]}}')


def churn_checks(s, prefix):
    for i in range(8):
        s.check(f'{prefix}-leash-{i}', f'if data entity @e[tag=leashed_{i},limit=1] leash')
    for i in range(6):
        s.check(f'{prefix}-boat-{i}-rider', f'if data entity @e[tag=boat_{i},limit=1] Passengers[0]')
        s.check(f'{prefix}-pig-{i}-rider', f'if data entity @e[tag=pig_{i},limit=1] Passengers[0]')
    s.check(f'{prefix}-no-lead-items', 'unless entity @e[type=minecraft:item,nbt={Item:{id:"minecraft:lead"}}]')


def setup(s, seconds):
    s.cmd('gamerule spawn_mobs false')
    s.cmd('gamerule advance_time false')
    s.cmd('gamerule advance_weather false')
    s.cmd('gamerule random_tick_speed 0')
    s.cmd('gamerule max_entity_cramming 0')
    s.cmd('time set 6000')


def generate(workload, seconds=10, rounds=3):
    s = Script()
    setup(s, seconds)
    BX, BZ = 4096, 4096
    FAR = 12288
    region = f'{BX} {BZ} {BX + 63} {BZ + 63}'
    loaded = f'if loaded {BX} 0 {BZ} if loaded {BX + 63} 0 {BZ + 63} if loaded {BX + 63} 0 {BZ} if loaded {BX} 0 {BZ + 63}'
    unloaded = f'unless loaded {BX} 0 {BZ} unless loaded {BX + 63} 0 {BZ + 63} unless loaded {BX + 63} 0 {BZ} unless loaded {BX} 0 {BZ + 63}'

    if workload in ('churn', 'restart'):
        s.cmd(f'forceload add {region}')
        s.cmd(f'forceload add {FAR} {FAR} {FAR + 15} {FAR + 15}')
        s.cmd(f'poll 90 execute {loaded} if loaded {FAR} 0 {FAR}')
        s.cmd(f'kill @e[x={BX},y=0,z={BZ},dx=64,dy=320,dz=64]')
        s.cmd('tick freeze')
        floor(s, FAR, FAR, FAR + 15, FAR + 15)
        s.area(FAR, FAR, FAR + 15, FAR + 15)
        s.area(BX, BZ, BX + 63, BZ + 63)
        churn_layout(s, BX, BZ)
        s.cmd('sleep 1')
        s.audit('baseline')
        churn_checks(s, 'baseline')
        s.cmd('tick unfreeze')

    if workload == 'churn':
        for r in range(rounds):
            s.cmd(f'sleep {seconds}')
            s.cmd('save-all')
            # Teleport a herd to a far region and back while everything moves.
            s.cmd(f'tp @e[tag=sheep] {FAR + 8} {Y} {FAR + 8}')
            s.cmd('sleep 1')
            s.cmd(f'tp @e[tag=sheep] {BX + 45} {Y} {BZ + 14}')
            s.cmd(f'forceload remove {region}')
            s.cmd(f'poll 120 execute {unloaded} unless entity @e[tag=boat_rider]')
            s.cmd(f'forceload add {region}')
            s.cmd(f'poll 120 execute {loaded} if entity @e[tag=boat_rider]')
            # Half-region churn: only the east half loses its ticket, so chunks next to the
            # west half are downgraded (border/ticking) rather than unloaded.
            s.cmd('sleep 2')
            s.cmd(f'forceload remove {BX + 32} {BZ} {BX + 63} {BZ + 63}')
            s.cmd('sleep 2')
            s.cmd(f'forceload add {BX + 32} {BZ} {BX + 63} {BZ + 63}')
            s.cmd('sleep 2')
            s.cmd('tick freeze')
            s.cmd('sleep 1')
            s.audit(f'round-{r}')
            churn_checks(s, f'round-{r}')
            s.cmd('tick unfreeze')
        s.cmd(f'sleep {seconds}')
        s.cmd('tick freeze')
        s.cmd('sleep 1')
        s.audit('final')
        churn_checks(s, 'final')

    elif workload == 'restart':
        # Phase 0: save mid-storm, keep running, then stop cleanly while everything moves.
        s.cmd(f'sleep {seconds}')
        s.cmd('save-all flush')
        s.cmd(f'sleep {seconds}')
        # Phase 1: the world must hold exactly what was there at the stop; stop under load again.
        s.new_phase()
        s.cmd(f'poll 120 execute {loaded} if entity @e[tag=boat_rider]')
        s.cmd('sleep 2')
        s.cmd('tick freeze')
        s.cmd('sleep 1')
        s.audit('after-stop-1')
        churn_checks(s, 'after-stop-1')
        s.cmd('tick unfreeze')
        s.cmd(f'sleep {seconds}')
        # A clean stop while everything runs: the final save must be consistent too.
        s.new_phase()
        s.cmd(f'poll 120 execute {loaded} if entity @e[tag=boat_rider]')
        s.cmd('sleep 2')
        s.cmd('tick freeze')
        s.cmd('sleep 1')
        s.audit('after-stop-2')
        churn_checks(s, 'after-stop-2')

    elif workload in ('portal', 'portal_rush'):
        # Overworld portal at (OX, OZ), nether portal at OX/8, OZ/8 so they link.
        OX, OZ = 4096, 4096
        NX, NZ = OX // 8, OZ // 8
        NY = 100
        s.area(OX - 32, OZ - 32, OX + 31, OZ + 31)
        s.area(NX - 16, NZ - 16, NX + 15, NZ + 15, 'the_nether', NY - 8, NY + 20)
        s.cmd(f'forceload add {OX - 32} {OZ - 32} {OX + 31} {OZ + 31}')
        s.cmd(f'execute in minecraft:the_nether run forceload add {NX - 16} {NZ - 16} {NX + 15} {NZ + 15}')
        s.cmd(f'poll 90 execute if loaded {OX - 32} 0 {OZ - 32} if loaded {OX + 31} 0 {OZ + 31} in minecraft:the_nether if loaded {NX - 16} 0 {NZ - 16} if loaded {NX + 15} 0 {NZ + 15}')
        s.cmd('kill @e[type=!minecraft:player]')
        s.cmd('tick freeze')
        floor(s, OX - 32, OZ - 32, OX + 31, OZ + 31)
        n = 'execute in minecraft:the_nether run '
        s.cmd(f'{n}fill {NX - 12} {NY - 1} {NZ - 12} {NX + 12} {NY - 1} {NZ + 12} minecraft:obsidian')
        s.cmd(f'{n}fill {NX - 12} {NY} {NZ - 12} {NX + 12} {NY + 8} {NZ + 12} minecraft:air')
        s.cmd(f'{n}fill {NX - 12} {NY + 9} {NZ - 12} {NX + 12} {NY + 9} {NZ + 12} minecraft:glass')
        for (x0, z0, x1, z1) in ((NX - 12, NZ - 12, NX + 12, NZ - 12), (NX - 12, NZ + 12, NX + 12, NZ + 12),
                                 (NX - 12, NZ - 12, NX - 12, NZ + 12), (NX + 12, NZ - 12, NX + 12, NZ + 12)):
            s.cmd(f'{n}fill {x0} {NY} {z0} {x1} {NY + 8} {z1} minecraft:glass')
        # Portals: 4 wide, 5 tall frames along x.
        for dim, px, pz, py, pre in (('overworld', OX, OZ, Y, ''), ('the_nether', NX, NZ, NY, n)):
            s.cmd(f'{pre}fill {px - 1} {py - 1} {pz} {px + 2} {py + 3} {pz} minecraft:obsidian')
            s.cmd(f'{pre}fill {px} {py} {pz} {px + 1} {py + 2} {pz} minecraft:nether_portal[axis=x]')
        # Nether side: a hopper floor collects arriving items into chests below the obsidian.
        for dx in range(-8, 10, 2):
            for dz in (-4, -2, 2, 4):
                x, z = NX + dx, NZ + dz
                s.cmd(f'{n}setblock {x} {NY - 2} {z} minecraft:chest')
                s.holders.append((x, NY - 2, z, 'the_nether'))
                s.cmd(f'{n}setblock {x} {NY - 1} {z} minecraft:hopper[facing=down]')
                s.holders.append((x, NY - 1, z, 'the_nether'))
        # Overworld side: droppers driven by observer clocks shoot items into the portal from both sides.
        for side, dz, facing in (('n', -1, 'south'), ('s', 1, 'north')):
            for dx in (0, 1):
                x, z = OX + dx, OZ + dz * 2
                s.cmd(f'setblock {x} {Y + 1} {OZ + dz * 3} minecraft:observer[facing={"north" if dz < 0 else "south"}]')
                s.cmd(f'setblock {x} {Y + 1} {OZ + dz * 4} minecraft:observer[facing={"south" if dz < 0 else "north"}]')
                s.container(x, Y + 1, z, f'dropper[facing={facing}]', [item(PAYLOAD[(dx + k) % 10], 16, k) for k in range(9)])
        if workload == 'portal_rush':
            # Bursts of mergeable items enter the portal together, so several arrive next to each
            # other in the nether in the same step and try to merge while changing dimension.
            s.cmd('sleep 1')
            s.audit('baseline')
            s.cmd('tick unfreeze')
            total = 0
            for r in range(rounds * 8):
                for i in range(48):
                    s.cmd(f'summon minecraft:item {OX + 0.3 + (i % 4) * 0.45:.2f} {Y + 0.2 + (i // 4 % 3) * 0.9:.2f} {OZ + 0.5} {{PickupDelay:0s,Item:{{id:"minecraft:{PAYLOAD[i % 3]}",count:1}}}}')
                    total += 1
                s.cmd('sleep 0.5')
            s.cmd(f'sleep {seconds}')
            s.cmd('tick freeze')
            s.cmd('sleep 1')
            s.audit('final', 'grow')
            s.expect_growth = {f'minecraft:{PAYLOAD[k]}': total // 3 for k in range(3)}
            s.cmd(f'say ADVERSARIAL_COMPLETE {workload}')
            return s
        # Mobs in a pen around the portal walk through and come back after their cooldown.
        walls(s, OX - 10, OZ - 10, OX + 11, OZ + 10)
        for i in range(24):
            kind = ('cow', 'sheep', 'pig', 'chicken')[i % 4]
            s.cmd(f'summon minecraft:{kind} {OX - 8 + (i % 12) * 1.5} {Y} {OZ - 7 + (i // 12) * 14} {{PersistenceRequired:1b,Tags:["adv"]}}')
        # Items, minecarts and passenger stacks placed straight into the portal.
        for i in range(6):
            s.cmd(f'summon minecraft:item {OX + 0.5 + (i % 2)} {Y + 1} {OZ + 0.5} {{PickupDelay:0s,Item:{{id:"minecraft:{PAYLOAD[i]}",count:{10 + i}}}}}')
        s.cmd(f'summon minecraft:chest_minecart {OX + 0.5} {Y} {OZ + 0.5} {{Tags:["adv"],Items:' + nbt([item('diamond', 64, 0), item('emerald', 33, 1)]) + '}')
        s.cmd(f'summon minecraft:oak_boat {OX + 1.5} {Y} {OZ + 0.5} {{Tags:["adv","boat","boat_0"],Passengers:[{{id:"minecraft:cow",PersistenceRequired:1b,Tags:["adv"]}}]}}')
        # A herd that a command teleports between dimensions over and over.
        for i in range(12):
            s.cmd(f'summon minecraft:cow {OX + 20}.5 {Y} {OZ - 20 + i * 3}.5 {{PersistenceRequired:1b,Tags:["adv","hop"]}}')
            s.cmd(f'summon minecraft:allay {OX + 22}.5 {Y} {OZ - 20 + i * 3}.5 {{PersistenceRequired:1b,Tags:["adv","hop"],equipment:{{mainhand:{{id:"minecraft:emerald",count:1}}}}}}')
        s.cmd('sleep 1')
        s.audit('baseline')
        s.cmd('tick unfreeze')
        for r in range(rounds):
            for j in range(10):
                s.cmd(f'execute in minecraft:the_nether run tp @e[tag=hop] {NX + 8} {NY} {NZ - 8}')
                s.cmd('sleep 0.2')
                s.cmd(f'execute in minecraft:overworld run tp @e[tag=hop] {OX + 20} {Y} {OZ - 20}')
                s.cmd('sleep 0.2')
            s.cmd(f'sleep {seconds}')
            s.cmd('save-all')
            # Drop the nether ticket so arriving entities race with unloads there.
            s.cmd(f'execute in minecraft:the_nether run forceload remove {NX - 16} {NZ - 16} {NX + 15} {NZ + 15}')
            s.cmd('sleep 3')
            s.cmd(f'execute in minecraft:the_nether run forceload add {NX - 16} {NZ - 16} {NX + 15} {NZ + 15}')
            s.cmd(f'poll 60 execute in minecraft:the_nether if loaded {NX - 16} 0 {NZ - 16} if loaded {NX + 15} 0 {NZ + 15}')
            s.cmd('sleep 2')
            s.cmd('tick freeze')
            s.cmd('sleep 1')
            s.audit(f'round-{r}')
            s.cmd('tick unfreeze')
        s.cmd(f'sleep {seconds}')
        s.cmd('tick freeze')
        s.cmd('sleep 1')
        s.audit('final')

    elif workload == 'boom':
        # TNT chains through chests, item frames, armor stands and mobs across chunk borders
        # while chunks churn. Items may be destroyed, but none may be duplicated.
        s.cmd(f'forceload add {region}')
        s.cmd(f'poll 90 execute {loaded}')
        s.cmd(f'kill @e[x={BX},y=0,z={BZ},dx=64,dy=320,dz=64]')
        s.cmd('tick freeze')
        s.area(BX - 8, BZ - 8, BX + 71, BZ + 71, y0=Y - 40)
        s.cmd(f'fill {BX} {Y - 6} {BZ} {BX + 63} {Y - 6} {BZ + 63} minecraft:bedrock')
        s.cmd(f'fill {BX} {Y - 5} {BZ} {BX + 63} {Y + 6} {BZ + 63} minecraft:air')
        for gx in range(4, 64, 8):
            for gz in range(4, 64, 8):
                x, z = BX + gx, BZ + gz
                s.cmd(f'fill {x - 3} {Y - 5} {z - 3} {x + 3} {Y - 2} {z + 3} minecraft:tnt')
                s.container(x, Y - 1, z, 'chest', [item(PAYLOAD[(gx + gz + k) % 10], 64, k) for k in range(8)])
                s.cmd(f'summon minecraft:armor_stand {x + 2}.5 {Y - 1} {z}.5 {{Tags:["adv"],equipment:{{mainhand:{{id:"minecraft:nether_star",count:1}}}}}}')
                s.cmd(f'summon minecraft:chest_minecart {x - 2}.5 {Y - 1} {z}.5 {{Tags:["adv"],Items:' + nbt([item('emerald', 64, 0)]) + '}')
                s.cmd(f'summon minecraft:cow {x}.5 {Y} {z + 2}.5 {{Tags:["adv"]}}')
        s.cmd('sleep 1')
        s.audit('baseline', 'none')
        s.cmd('tick unfreeze')
        s.cmd(f'setblock {BX + 4} {Y - 5} {BZ + 4} minecraft:fire')
        s.cmd(f'summon minecraft:tnt {BX + 4} {Y - 1} {BZ + 4} {{fuse:1}}')
        s.cmd(f'summon minecraft:tnt {BX + 60} {Y - 1} {BZ + 60} {{fuse:1}}')
        s.cmd(f'summon minecraft:tnt {BX + 4} {Y - 1} {BZ + 60} {{fuse:1}}')
        s.cmd(f'summon minecraft:tnt {BX + 60} {Y - 1} {BZ + 4} {{fuse:1}}')
        for r in range(rounds):
            s.cmd('sleep 2')
            s.cmd('save-all')
            s.cmd(f'forceload remove {BX + 32} {BZ} {BX + 63} {BZ + 63}')
            s.cmd('sleep 1')
            s.cmd(f'forceload add {BX + 32} {BZ} {BX + 63} {BZ + 63}')
        s.cmd(f'poll 120 execute unless entity @e[type=minecraft:tnt]')
        s.cmd('sleep 3')
        s.cmd('tick freeze')
        s.cmd('sleep 1')
        s.audit('final', 'atmost')

    elif workload == 'storage':
        # Container entities and blocks fed and drained by hoppers across chunk borders while
        # the clock rate changes and the chunks repeatedly unload: hopper minecarts parked on
        # rails over hoppers, chest minecarts under feeding hoppers, dropper chains between
        # chunks, and furnaces (fuel and input are conserved while nothing smelts).
        s.cmd(f'forceload add {region}')
        s.cmd(f'poll 90 execute {loaded}')
        s.cmd(f'kill @e[x={BX},y=0,z={BZ},dx=64,dy=320,dz=64]')
        s.cmd('tick freeze')
        floor(s, BX, BZ, BX + 63, BZ + 63)
        s.area(BX, BZ, BX + 63, BZ + 63)
        for line in range(8):
            z = BZ + 4 + line * 7
            # Two hopper lines crossing all four chunks. Every sixth upper hopper points down
            # into a minecart parked on a rail over the lower line, which drains it again.
            s.container(BX + 2, Y + 2, z, 'chest', [item(PAYLOAD[(line + j) % 10], 64, j) for j in range(27)])
            for x in range(BX + 2, BX + 62):
                k = x - BX
                if k % 6 == 3:
                    s.container(x, Y + 1, z, 'hopper[facing=down]', [])
                    s.container(x, Y - 1, z, 'hopper[facing=east]', [])
                    s.cmd(f'setblock {x} {Y} {z} minecraft:rail[shape=east_west]')
                    kind = 'hopper_minecart' if line % 2 else 'chest_minecart'
                    s.cmd(f'summon minecraft:{kind} {x + 0.5} {Y + 0.1} {z + 0.5} {{Tags:["adv"]}}')
                else:
                    s.container(x, Y + 1, z, 'hopper[facing=east]', [item(PAYLOAD[(line + k) % 10], 8, 0)])
                    s.container(x, Y - 1, z, 'hopper[facing=east]', [item(PAYLOAD[(line + k + 3) % 10], 8, 0)])
            s.container(BX + 62, Y - 1, z, 'chest', [])
            s.container(BX + 62, Y + 1, z, 'chest', [])
        s.cmd('sleep 1')
        s.audit('baseline')
        s.cmd('tick unfreeze')
        for r in range(rounds):
            s.cmd(f'tick rate {(40, 10, 60)[r % 3]}')
            s.cmd(f'sleep {seconds}')
            s.cmd('save-all')
            s.cmd(f'forceload remove {region}')
            s.cmd(f'poll 120 execute {unloaded}')
            s.cmd(f'forceload add {region}')
            s.cmd(f'poll 120 execute {loaded}')
            s.cmd('sleep 2')
            s.cmd('tick freeze')
            s.cmd('sleep 1')
            s.audit(f'round-{r}')
            s.cmd('tick unfreeze')
        s.cmd('tick rate 20')
        s.cmd(f'sleep {seconds}')
        s.cmd('tick freeze')
        s.cmd('sleep 1')
        s.audit('final')
    else:
        raise ValueError(workload)

    s.cmd(f'say ADVERSARIAL_COMPLETE {workload}')
    return s
