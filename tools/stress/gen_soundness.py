#!/usr/bin/env python3
"""Five deterministic world lifecycle tests, with exact audit manifests."""
import json

WORKLOADS = ('components', 'container_moves', 'frozen_entities', 'scheduled_moves', 'chunk_reload')


def stack(item, count, variant, slot=None):
    value = {'id': f'minecraft:{item}', 'count': count, 'components': {
        'minecraft:custom_data': {'variant': variant, 'payload': {'text': 'brace } quote " slash \\', 'values': [1, 2, 3]}}
    }}
    if item == 'diamond' and variant == 'small':
        value['components']['minecraft:max_stack_size'] = 16
    if item == 'iron_sword':
        value['components']['minecraft:damage'] = 7
    if slot is not None:
        value['Slot'] = slot
    return value


def typed_nbt(value):
    """NBT numeric widths and array/list types participate in item equality."""
    if isinstance(value, dict):
        return {key: typed_nbt(part) for key, part in value.items()}
    if isinstance(value, list):
        return {'nbt_type': 'list', 'value': [typed_nbt(part) for part in value]}
    if isinstance(value, (bool, int, float)):
        kind = 'byte' if isinstance(value, bool) else 'int' if isinstance(value, int) else 'double'
        return {'nbt_type': kind, 'value': int(value) if isinstance(value, bool) else value}
    return value


def signature(value, decoded=False):
    components = dict(value.get('components', {}))
    if not decoded and 'minecraft:custom_data' in components:
        components['minecraft:custom_data'] = typed_nbt(components['minecraft:custom_data'])
    return json.dumps({'id': value['id'], 'components': components}, sort_keys=True, separators=(',', ':'))


def generate(workload, seconds=15, rounds=8):
    if workload not in WORKLOADS or seconds < 1 or rounds < 1:
        raise ValueError('Unknown workload or nonpositive duration/rounds')
    lines, checks, audits, expected = [], [], {}, {}
    cmd = lines.append

    def check(name, condition):
        checks.append(name)
        cmd(f'say check {name}')
        cmd(f'execute {condition}')

    def population(name, selector, count):
        cmd(f'execute store result score #count soundness if entity {selector}')
        check(name, f'if score #count soundness matches {count}')

    def add_stack(value):
        key = signature(value)
        expected[key] = expected.get(key, 0) + value['count']

    def audit(name, holders):
        audits[name] = {'items': dict(expected), 'blocks': len(holders), 'holders': [f'{x}, {y}, {z}' for x, y, z in holders]}
        cmd(f'say inventory {name} begin')
        for x, y, z in holders:
            cmd(f'data get block {x} {y} {z} Items')
        cmd('execute as @e[type=minecraft:item] run data get entity @s Item')
        cmd(f'say inventory {name} end')

    cmd('gamerule spawn_mobs false')
    cmd('gamerule advance_time false')
    cmd('gamerule advance_weather false')
    cmd('gamerule random_tick_speed 0')
    cmd('gamerule max_entity_cramming 0')
    cmd('forceload add -48 -48 47 47')
    cmd('poll 90 execute if loaded -48 0 -48 if loaded 47 0 47')
    cmd('kill @e')
    cmd('sleep 1')
    cmd('kill @e[type=minecraft:item]')
    cmd('tick freeze')
    cmd('scoreboard objectives add soundness dummy')
    for x in (-48, -16, 16):
        cmd(f'fill {x} 199 -48 {x+31} 199 47 minecraft:stone')
        cmd(f'fill {x} 200 -48 {x+31} 207 47 minecraft:air')

    if workload == 'components':
        holders = []
        # Both intake hoppers can contend for the same dropped stacks. A full
        # output creates backpressure before its slots are opened by commands.
        for i in range(16):
            x, z = (-17 if i % 2 else 15), -44 + (i // 2) * 5
            fillers = [{'Slot': slot, 'id': 'minecraft:cobblestone', 'count': 64} for slot in range(27)]
            cmd(f'setblock {x} 200 {z} minecraft:chest' + '{Items:' + json.dumps(fillers) + '}')
            cmd(f'setblock {x} 201 {z} minecraft:hopper[facing=down]')
            values = [stack('diamond', 64, 'large', 0), stack('diamond', 16, 'small', 1), stack('iron_sword', 1, 'tool', 2)]
            for value in values:
                add_stack(value)
            cmd(f'setblock {x-1} 201 {z} minecraft:hopper[facing=east]' + '{Items:' + json.dumps(values) + '}')
            for variant, count in (('large', 63), ('large', 1), ('small', 15), ('small', 1), ('tool', 1)):
                value = stack('iron_sword' if variant == 'tool' else 'diamond', count, variant)
                add_stack(value)
                cmd(f'summon minecraft:item {x}.0 202.1 {z+0.5} ' + '{PickupDelay:0s,Age:-32768s,Item:' + json.dumps(value) + '}')
            holders.extend(((x, 200, z), (x, 201, z), (x-1, 201, z)))
        cmd('tick unfreeze')
        cmd('sleep 2')
        cmd('poll 30 execute if data block 15 201 -44 Items[{id:"minecraft:diamond"}]')
        check('backpressure-output-full', 'if data block 15 200 -44 Items[{Slot:26b,id:"minecraft:cobblestone",count:64}]')
        for x, y, z in holders[::3]:
            for slot in range(27):
                cmd(f'item replace block {x} {y} {z} container.{slot} with minecraft:air')
        cmd('poll 30 execute if data block 15 200 -44 Items[{id:"minecraft:diamond"}]')
        cmd(f'sleep {seconds}')
        cmd('tick freeze')
        audit('components-final', holders)

    elif workload == 'container_moves':
        origins = [(-17, -17), (15, 15), (31, -17)]
        for i, (dx, dz, facing) in enumerate(((0, 0, 'east'), (1, 0, 'south'), (1, 1, 'west'), (0, 1, 'north'))):
            value = stack('diamond', 64, f'moving-{i}', 0)
            add_stack(value)
            cmd(f'setblock {-17+dx} 200 {-17+dz} minecraft:hopper[facing={facing}]' + '{Items:' + json.dumps([value]) + '}')
        cmd('tick unfreeze')
        cmd('poll 30 execute if data block -17 200 -17 Items[{Slot:1b}]')
        current = origins[0]
        for i in range(rounds):
            target = origins[(i+1) % len(origins)]
            x, z = current
            tx, tz = target
            cmd(f'clone {x} 200 {z} {x+1} 200 {z+1} {tx} 200 {tz} replace move')
            check(f'move-{i}-old-timers-detached', f'if block {x} 200 {z} minecraft:air if block {x+1} 200 {z+1} minecraft:air')
            cmd('sleep 0.25')
            current = target
        x, z = current
        # Prove the new tickers work, rather than only preserving frozen NBT.
        wake = stack('diamond', 1, 'wake', 0)
        add_stack(wake)
        cmd(f'setblock {x} 201 {z} minecraft:chest' + '{Items:' + json.dumps([wake]) + '}')
        cmd(f'poll 30 execute unless data block {x} 201 {z} Items[0]')
        cmd(f'sleep {seconds}')
        cmd('tick freeze')
        audit('moves-final', [(x+dx, 200, z+dz) for dx, dz in ((0, 0), (1, 0), (1, 1), (0, 1))] + [(x, 201, z)])
        check('no-container-drops', 'unless entity @e[type=minecraft:item]')

    elif workload == 'frozen_entities':
        # Warm the spatial cache, then edit entity membership while the clock
        # stays frozen. Straddle positive and negative section boundaries.
        for i in range(192):
            cmd(f'summon minecraft:cow -16.5 200 -16.5 {{NoAI:1b,NoGravity:1b,PersistenceRequired:1b,Tags:["probe","probe_{i}"]}}')
        cmd('execute store result score #before soundness run time query gametime')
        for i in range(rounds):
            x, z = (31.5, 31.5) if i % 2 == 0 else (-16.5, -16.5)
            old_x, old_z = (-16.5, -16.5) if i % 2 == 0 else (31.5, 31.5)
            cmd(f'tp @e[tag=probe] {x} 200 {z}')
            population(f'teleport-{i}-new-section', f'@e[tag=probe,x={x-1},y=199,z={z-1},dx=2,dy=3,dz=2]', 192)
            population(f'teleport-{i}-old-section-empty', f'@e[tag=probe,x={old_x-1},y=199,z={old_z-1},dx=2,dy=3,dz=2]', 0)
        cmd('summon minecraft:armor_stand 0 200 0 {NoGravity:1b,Tags:["vehicle"]}')
        for i in range(rounds):
            cmd('ride @e[tag=probe_0,limit=1] mount @e[tag=vehicle,limit=1]')
            check(f'ride-{i}-passenger-attached', 'if data entity @e[tag=vehicle,limit=1] Passengers[{Tags:["probe_0"]}]')
            cmd('ride @e[tag=probe_0,limit=1] dismount')
            check(f'ride-{i}-passenger-detached', 'unless data entity @e[tag=vehicle,limit=1] Passengers')
        cmd('kill @e[tag=probe_0]')
        cmd('summon minecraft:cow 0 200 0 {NoAI:1b,NoGravity:1b,PersistenceRequired:1b,Tags:["probe","replacement"]}')
        population('removed-member-invisible', '@e[tag=probe_0]', 0)
        population('replacement-population', '@e[tag=probe]', 192)
        for i in range(1, 192):
            population(f'unique-member-{i}', f'@e[tag=probe_{i}]', 1)
        cmd('sleep 1')
        cmd('execute store result score #after soundness run time query gametime')
        check('clock-stayed-frozen', 'if score #before soundness = #after soundness')
        cmd('tick unfreeze')
        cmd(f'sleep {seconds}')
        cmd('tick freeze')
        population('population-after-resume', '@e[tag=probe]', 192)

    elif workload == 'scheduled_moves':
        # Unsupported sand has queued block ticks but cannot fall while frozen.
        # Moving it copies those ticks; obsolete source ticks must do no work.
        cmd('fill -18 204 -18 -15 207 -15 minecraft:sand')
        cmd('clone -18 204 -18 -15 207 -15 14 204 14 replace move')
        cmd('fill -18 204 -18 -15 207 -15 minecraft:diamond_block')
        cmd('tick step 1')
        cmd('sleep 1')
        cmd('tick rate 100')
        cmd('tick unfreeze')
        cmd('poll 60 execute if block 14 203 14 minecraft:sand if block 17 203 17 minecraft:sand unless entity @e[type=minecraft:falling_block]')
        cmd('tick rate 20')
        # A queued water tick must not resurrect water after replacing its source.
        cmd('tick freeze')
        cmd('setblock -16 202 15 minecraft:water')
        cmd('setblock -16 202 15 minecraft:gold_block')
        cmd('tick step 5')
        cmd('sleep 1')
        cmd('tick unfreeze')
        cmd(f'sleep {seconds}')
        cmd('tick freeze')
        cmd('execute store result score #blocks soundness run fill 14 200 14 17 207 17 minecraft:air replace minecraft:sand strict')
        check('all-moved-sand-landed-once', 'if score #blocks soundness matches 64')
        cmd('execute store result score #blocks soundness run fill -18 204 -18 -15 207 -15 minecraft:air replace minecraft:diamond_block strict')
        check('stale-source-ticks-did-not-mutate-replacements', 'if score #blocks soundness matches 64')
        check('stale-fluid-source-stays-replaced', 'if block -16 202 15 minecraft:gold_block')
        check('stale-fluid-tick-did-not-spread', 'if block -16 201 15 minecraft:air if block -15 202 15 minecraft:air')
        check('no-falling-blocks', 'unless entity @e[type=minecraft:falling_block]')
        check('no-sand-drops', 'unless entity @e[type=minecraft:item]')

    elif workload == 'chunk_reload':
        # This is far from spawn and the common forced area so removing its
        # only ticket really unloads it. Every cycle proves unload AND reload.
        cmd('forceload add 8192 8192 8223 8207')
        cmd('poll 90 execute if loaded 8192 0 8192 if loaded 8223 0 8207')
        cmd('fill 8192 199 8192 8223 199 8207 minecraft:stone')
        cmd('fill 8192 200 8192 8223 207 8207 minecraft:air')
        holders = []
        for i, (dx, dz, facing) in enumerate(((0, 0, 'east'), (1, 0, 'south'), (1, 1, 'west'), (0, 1, 'north'))):
            value = stack('diamond', 64, f'saved-{i}', 0)
            add_stack(value)
            cmd(f'setblock {8207+dx} 200 {8199+dz} minecraft:hopper[facing={facing}]' + '{Items:' + json.dumps([value]) + '}')
            holders.append((8207+dx, 200, 8199+dz))
        feeders = [(x, y+1, z) for x, y, z in holders]
        initial = stack('diamond', 1, 'saved-wake', 0)
        for x, y, z in feeders:
            add_stack(initial)
            cmd(f'setblock {x} {y} {z} minecraft:chest' + '{Items:' + json.dumps([initial]) + '}')
        holders.extend(feeders)
        cmd('summon minecraft:armor_stand 8205.5 201 8199.5 {NoGravity:1b,Invulnerable:1b,Tags:["saved_vehicle"],Passengers:[{id:"minecraft:cow",NoAI:1b,Invulnerable:1b,PersistenceRequired:1b,Tags:["saved_passenger"]}]}')
        cmd('tick unfreeze')
        for x, y, z in feeders:
            cmd(f'poll 30 execute unless data block {x} {y} {z} Items[0]')
        for i in range(rounds):
            cmd('save-all flush')
            cmd('forceload remove 8192 8192 8223 8207')
            cmd('poll 90 execute unless loaded 8192 0 8192 unless loaded 8223 0 8207')
            population(f'unload-{i}-entity-storage-empty', '@e[tag=saved_passenger]', 0)
            cmd('forceload add 8192 8192 8223 8207')
            cmd('poll 90 execute if loaded 8192 0 8192 if loaded 8223 0 8207 if entity @e[tag=saved_passenger]')
            cmd('tick freeze')
            population(f'reload-{i}-one-passenger', '@e[tag=saved_passenger]', 1)
            population(f'reload-{i}-one-vehicle', '@e[tag=saved_vehicle]', 1)
            check(f'reload-{i}-passenger-link', 'if data entity @e[tag=saved_vehicle,limit=1] Passengers[{Tags:["saved_passenger"]}]')
            audit(f'reload-{i}', holders)
            wake = stack('diamond', 1, 'saved-wake', 0)
            for x, y, z in feeders:
                add_stack(wake)
                cmd(f'item replace block {x} {y} {z} container.0 with minecraft:diamond[minecraft:custom_data=' + json.dumps(wake['components']['minecraft:custom_data']) + ']')
            cmd('tick unfreeze')
            for x, y, z in feeders:
                cmd(f'poll 30 execute unless data block {x} {y} {z} Items[0]')
            cmd(f'sleep {seconds}')
        cmd('tick freeze')
        audit('reload-final', holders)

    cmd(f'say SOUNDNESS_COMPLETE {workload}')
    return '\n'.join(lines) + '\n', {'workload': workload, 'seconds': seconds, 'rounds': rounds, 'checks': checks, 'audits': audits}
