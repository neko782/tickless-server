#!/usr/bin/env python3
"""Ten extended workloads with deterministic setup and destructive end-of-run audits.

Usage: gen_extended.py WORKLOAD OUT [SECONDS=60] [WARMUP=60] [SCALE=1]
Original workloads: crowd, village, sorting, pistons, gravity.
Novel workloads: bees, endermen, snowballs, jockeys, aquarium.
Each run uses a fresh world. Entity benchmarks count committed steps, not clock ticks.
"""
import argparse
import json
import random
from pathlib import Path

ORIGINAL = ('crowd', 'village', 'sorting', 'pistons', 'gravity')
NOVEL = ('bees', 'endermen', 'snowballs', 'jockeys', 'aquarium')
WORKLOADS = ORIGINAL + NOVEL


def generate(workload, seconds=60, warmup=60, scale=1.0, piston_rate=20):
    rng = random.Random(20261001)
    lines = []
    cmd = lines.append
    expected = {}
    holders = []
    checks = []
    meta = {'workload': workload, 'seconds': seconds, 'warmup': warmup, 'scale': scale}
    y = 200
    hives = []
    floor_stone = 192*192

    def fill(x0, y0, z0, x1, y1, z1, block, extra=''):
        step = max(1, 32768 // ((y1-y0+1)*(z1-z0+1)))
        for x in range(x0, x1+1, step):
            cmd(f'fill {x} {y0} {z0} {min(x1,x+step-1)} {y1} {z1} {block}{extra}')

    def check(name, condition):
        cmd(f'say check {name}')
        cmd(f'execute {condition}')
        checks.append(name)

    def audit_blocks(block, count, height=8, base_y=y):
        # Frozen, drained world: count every matching block, including on chunk borders.
        # Strict replacement skips neighbor updates. An ordinary destructive count can
        # start a fresh piston retraction whose block entity cannot tick while frozen.
        cmd('scoreboard players set #blocks extended 0')
        step = max(1, 32768 // (96*height))
        for z0 in (-96, 0):
            for x0 in range(-96,96,step):
                cmd('scoreboard players set #part extended 0')
                cmd(f'execute store result score #part extended run fill {x0} {base_y} {z0} {min(95,x0+step-1)} {base_y+height-1} {z0+95} minecraft:air replace minecraft:{block} strict')
                cmd('scoreboard players operation #blocks extended += #part extended')
        if count is not None:
            check(f'{block}-conserved-{count}', f'if score #blocks extended matches {count}')

    cmd('gamerule advance_time false')
    cmd('gamerule advance_weather false')
    cmd('gamerule spawn_mobs false')
    cmd('gamerule random_tick_speed 0')
    cmd('gamerule max_entity_cramming 0')
    cmd('time set 18000')
    cmd('forceload add -96 -96 95 95')
    cmd('poll 90 execute if loaded -96 0 -96 if loaded 95 0 -96 if loaded -96 0 95 if loaded 95 0 95')
    cmd('kill @e')
    cmd('sleep 1')
    cmd('kill @e[type=minecraft:item]')
    cmd('tick freeze')
    cmd('scoreboard objectives add extended dummy')
    fill(-96, y-1, -96, 95, y-1, 95, 'minecraft:stone')
    fill(-96, y, -96, 95, y+7, 95, 'minecraft:air')

    if workload in ('crowd', 'village'):
        fill(-80, y, -80, 79, y+3, -80, 'minecraft:glass')
        fill(-80, y, 79, 79, y+3, 79, 'minecraft:glass')
        fill(-80, y, -79, -80, y+3, 78, 'minecraft:glass')
        fill(79, y, -79, 79, y+3, 78, 'minecraft:glass')
        count = max(1, int((48000 if workload == 'crowd' else 24000)*scale))
        kinds = ['cow', 'sheep', 'pig', 'villager', 'zombie', 'chicken'] if workload == 'crowd' else ['villager']
        if workload == 'village':
            # POI searches, pathfinding, gossip and cross-brain observations.
            for x in range(-72, 73, 12):
                for z in range(-72, 73, 12):
                    cmd(f'setblock {x} {y} {z} minecraft:composter')
                    cmd(f'setblock {x+2} {y} {z} minecraft:red_bed[facing=south,part=foot]')
                    cmd(f'setblock {x+2} {y} {z+1} minecraft:red_bed[facing=south,part=head]')
        # The crowded pen occupies the same 96x96 area at each scale.
        for i in range(count):
            kind = kinds[i % len(kinds)]
            x, z = rng.uniform(-46, 46), rng.uniform(-46, 46)
            cmd(f'summon minecraft:{kind} {x:.3f} {y} {z:.3f} {{PersistenceRequired:1b,Health:1024f,attributes:[{{id:"minecraft:max_health",base:1024.0d}}],Tags:["extended_mob"]}}')
        cmd('effect give @e[tag=extended_mob] minecraft:resistance infinite 255 true')
        meta['entities'] = count
        # Four independent rings ensure AI speedups retain item transfer soundness.
        for x, z in ((-95,-95),(-81,-81),(80,80),(94,94)):
            for dx,dz,direction in ((0,0,'east'),(1,0,'south'),(1,1,'west'),(0,1,'north')):
                px,pz=x+dx,z+dz
                cmd(f'setblock {px} {y} {pz} minecraft:hopper[facing={direction}]{{Items:[{{Slot:0b,id:"minecraft:cobblestone",count:64}}]}}')
                holders.append((px,y,pz))
                expected['cobblestone'] = expected.get('cobblestone',0)+64
    elif workload == 'sorting':
        # Closed, two-layer conveyor loops; every 16-block boundary is crossed.
        rows = min(92, max(1, int(64*scale)))
        kinds = ['cobblestone','iron_ingot','diamond','oak_sign','iron_sword']
        for z in range(-92,-92+rows):
            for x in range(-92,93):
                for layer,direction in ((0,'east' if x<92 else 'down'),(-1,'west' if x>-92 else 'up')):
                    # Hoppers cannot point up: the west end is a clocked upward dropper.
                    block = 'dropper' if direction=='up' else 'hopper'
                    item = kinds[(x+z+layer)%len(kinds)]
                    count = 1 if item=='iron_sword' else 16 if item=='oak_sign' else 32
                    py = y+layer+1
                    cmd(f'setblock {x} {py} {z} minecraft:{block}[facing={direction}]{{Items:[{{Slot:0b,id:"minecraft:{item}",count:{count}}}]}}')
                    holders.append((x,py,z))
                    expected[item] = expected.get(item,0)+count
            cmd(f'setblock -92 {y-2} {z} minecraft:observer[facing=up]')
            cmd(f'setblock -92 {y-1} {z} minecraft:observer[facing=down]')
        meta['containers'] = len(holders)
    elif workload == 'pistons':
        # Adhesive payloads need an air gap above the floor so they can move.
        y += 2
        gates = min(961, max(1,int(800*scale)))
        payloads = ('slime_block','honey_block','diamond_block')
        coords=[]
        for x in range(-94,91,6):
            for z in range(-94,91,6):
                if len(coords)>=gates:
                    break
                block=payloads[len(coords)%3]
                cmd(f'setblock {x+1} {y} {z-1} minecraft:{block}')
                cmd(f'setblock {x} {y} {z-1} minecraft:sticky_piston[facing=east]')
                cmd(f'setblock {x} {y} {z} minecraft:observer[facing=south]')
                cmd(f'setblock {x} {y} {z+1} minecraft:observer[facing=north]')
                coords.append((x,z))
        # An optional faster clock exposes the cost of notifying idle navigations. NoAI is
        # identical on both engines; bodies still tick and collide.
        bystanders=max(1,int(4000*scale))
        for i in range(bystanders):
            x,z=rng.uniform(-46,46),rng.uniform(-46,46)
            cmd(f'summon minecraft:cow {x:.3f} {y-2} {z:.3f} {{NoAI:1b,PersistenceRequired:1b,Health:1024f,attributes:[{{id:"minecraft:max_health",base:1024.0d}}],Tags:["extended_mob"]}}')
        cmd('effect give @e[tag=extended_mob] minecraft:resistance infinite 255 true')
        for x,z in ((-95,-95),(-81,-81),(80,80),(94,94)):
            for dx,dz,direction in ((0,0,'east'),(1,0,'south'),(1,1,'west'),(0,1,'north')):
                px,pz=x+dx,z+dz
                cmd(f'setblock {px} {y-2} {pz} minecraft:hopper[facing={direction}]{{Items:[{{Slot:0b,id:"minecraft:cobblestone",count:64}}]}}')
                holders.append((px,y-2,pz))
                expected['cobblestone']=expected.get('cobblestone',0)+64
        meta['entities']=bystanders
        meta['tick_rate']=piston_rate
        meta['minimum_non_entity_events_per_second']=gates*12
        meta['gates']=gates
        meta['minimum_events_per_second']=gates*12
    elif workload in NOVEL:
        boosted='PersistenceRequired:1b,Health:1024f,attributes:[{id:"minecraft:max_health",base:1024.0d}],Tags:["extended_mob"]'
        # Each novel workload uses a closed glass pen around its spawn area.
        wall=15 if workload=='endermen' else 7
        for x0,z0,x1,z1 in ((-80,-80,79,-80),(-80,79,79,79),(-80,-79,-80,78),(79,-79,79,78)):
            fill(x0,y,z0,x1,y+wall,z1,'minecraft:glass')
        cmd('weather clear')
        if workload=='bees':
            # Hive slots for a small fraction of the swarm: the rest searches bee_home POIs while
            # residents leave and return across entity/block-entity boundaries.
            cmd('time set 6000')
            fill(-79,y+8,-79,78,y+8,78,'minecraft:glass')
            for x in range(-72,73,8):
                for z in range(-72,73,8):
                    cmd(f'setblock {x} {y} {z} minecraft:beehive[facing=south]')
                    hives.append((x,z))
            for x in range(-76,77,4):
                for z in range(-74,77,4):
                    cmd(f'setblock {x} {y-1} {z} minecraft:grass_block')
                    floor_stone-=1
                    cmd(f'setblock {x} {y} {z} minecraft:{("poppy","dandelion","cornflower","allium")[(x+z)//4%4]}')
            count=max(1,int(60000*scale))
            for i in range(count):
                x,z=rng.uniform(-70,70),rng.uniform(-70,70)
                cmd(f'summon minecraft:bee {x:.3f} {y+rng.uniform(1,6):.3f} {z:.3f} {{{boosted}}}')
        elif workload=='endermen':
            # Endermen pick up and place loose dirt; a block is either in the world or carried.
            cmd('time set 18000')
            cmd('gamerule mob_griefing true')
            fill(-79,y-2,-79,78,y-2,78,'minecraft:stone')
            dirt=0
            for x in range(-45,46,3):
                for z in range(-45,46,3):
                    cmd(f'setblock {x} {y} {z} minecraft:dirt')
                    dirt+=1
            meta['dirt']=dirt
            count=max(1,int(64000*scale))
            for i in range(count):
                x,z=rng.uniform(-70,70),rng.uniform(-70,70)
                cmd(f'summon minecraft:enderman {x:.3f} {y} {z:.3f} {{{boosted}}}')
        elif workload=='snowballs':
            # Snow golems pelt zombies with snowballs; zombies chase them back.
            cmd('time set 18000')
            cmd('gamerule mob_griefing false')
            count=max(2,int(48000*scale))//2*2
            for i in range(count):
                x,z=rng.uniform(-46,46),rng.uniform(-46,46)
                kind='snow_golem' if i%2 else 'zombie'
                cmd(f'summon minecraft:{kind} {x:.3f} {y} {z:.3f} {{{boosted}}}')
        elif workload=='jockeys':
            # Four-high passenger towers: each step moves a vehicle with its riders.
            cmd('time set 18000')
            levels=(('cow','pig','sheep','spider'),('pig','sheep','cow','chicken'),('chicken','sheep','pig'),('zombie','skeleton','villager','chicken'))
            towers=max(1,int(16000*scale))
            for i in range(towers):
                nbt=''
                for depth in reversed(range(4)):
                    kind=levels[depth][i%len(levels[depth])]
                    rider=f',Passengers:[{nbt}]' if nbt else ''
                    if depth:
                        nbt=f'{{id:"minecraft:{kind}",{boosted}{rider}}}'
                x,z=rng.uniform(-46,46),rng.uniform(-46,46)
                cmd(f'summon minecraft:{kind} {x:.3f} {y} {z:.3f} {{{boosted}{rider}}}')
            count=towers*4
            meta['riders']=towers*3
        elif workload=='aquarium':
            # A sealed tank: swimming navigation, schooling and fluid pushing on every fish.
            cmd('time set 18000')
            fill(-79,y,-79,78,y+5,78,'minecraft:water')
            meta['water']=158*158*6
            kinds=['cod','salmon','tropical_fish','squid','glow_squid','cod','salmon','tropical_fish']
            count=max(1,int(64000*scale))
            for i in range(count):
                x,z=rng.uniform(-70,70),rng.uniform(-70,70)
                cmd(f'summon minecraft:{kinds[i%len(kinds)]} {x:.3f} {y+rng.uniform(0.5,4.5):.3f} {z:.3f} {{{boosted}}}')
        cmd('effect give @e[tag=extended_mob] minecraft:resistance infinite 255 true')
        meta['entities']=count
    elif workload == 'gravity':
        edge=min(176,max(4,int(128*scale)))
        x0=-edge//2
        fill(x0,y+24,x0,x0+edge-1,y+24,x0+edge-1,'minecraft:stone')
        fill(x0,y+25,x0,x0+edge-1,y+28,x0+edge-1,'minecraft:sand')
        meta['blocks']=edge*edge*4

    cmd('tick unfreeze')
    if workload=='pistons':
        first_x,first_z=coords[0]
        cmd(f'poll 30 execute if block {first_x} {y} {first_z-1} minecraft:sticky_piston[extended=true]')
        cmd(f'tick rate {piston_rate}')
    cmd(f'sleep {warmup}')
    cmd('say BENCH_START')
    cmd('time query gametime')
    if workload=='gravity':
        fill(x0,y+24,x0,x0+edge-1,y+24,x0+edge-1,'minecraft:air')
        cmd('sleep 1')
        cmd('poll 180 execute unless entity @e[type=minecraft:falling_block,x=-96,y=192,z=-96,dx=191,dy=80,dz=191]')
    else:
        cmd(f'sleep {seconds}')
    cmd('say BENCH_END')
    cmd('time query gametime')
    cmd('tick query')
    if workload=='pistons':
        for x,z in coords:
            cmd(f'setblock {x} {y} {z+1} minecraft:air')
            cmd(f'setblock {x} {y} {z} minecraft:air')
        cmd('sleep 5')
        # A piston base can itself be a moving block during retraction. Require all bases
        # to settle while block entities still tick, rather than miscounting a frozen move.
        for x,z in coords:
            cmd(f'poll 30 execute if block {x} {y} {z-1} minecraft:sticky_piston')
    cmd('tick freeze')
    cmd('sleep 1')
    if workload in ('crowd','village'):
        cmd('execute store result score #mobs extended if entity @e[tag=extended_mob]')
        check('exact-mob-population',f'if score #mobs extended matches {count}')
        # A fixed boundary sentinel for block mutations in a busy entity world.
        audit_blocks('stone',192*192,height=1,base_y=y-1)
    if workload in NOVEL:
        cmd('scoreboard players set #mobs extended 0')
        cmd('execute store result score #mobs extended if entity @e[tag=extended_mob]')
        if workload=='bees':
            # Residents are saved in their hive; a bee is either an entity or an occupant.
            for x,z in hives:
                cmd(f'execute store result score #part extended run data get block {x} {y} {z} bees')
                cmd('scoreboard players operation #mobs extended += #part extended')
            audit_blocks('beehive',len(hives),height=1)
        check('exact-mob-population',f'if score #mobs extended matches {count}')
        check('no-escaped-items','unless entity @e[type=minecraft:item]')
        if workload=='endermen':
            # Carried blocks are not in the entity NBT; each one drops as an item on death.
            cmd('kill @e[type=minecraft:enderman]')
            cmd('execute store result score #carried extended if items entity @e[type=minecraft:item] contents minecraft:dirt')
            cmd('kill @e[type=minecraft:item]')
            audit_blocks('dirt',None,height=16)
            cmd('scoreboard players operation #blocks extended += #carried extended')
            check(f'dirt-conserved-{meta["dirt"]}',f'if score #blocks extended matches {meta["dirt"]}')
        if workload=='jockeys':
            cmd('scoreboard players set #riders extended 0')
            cmd('execute as @e[tag=extended_mob] on vehicle run scoreboard players add #riders extended 1')
            check(f'exact-riders-{meta["riders"]}',f'if score #riders extended matches {meta["riders"]}')
        if workload=='aquarium':
            audit_blocks('water',meta['water'],height=6)
        audit_blocks('stone',floor_stone,height=1,base_y=y-1)
    if holders:
        cmd('say graph-count-begin')
        for x,py,z in holders:
            cmd(f'data get block {x} {py} {z} Items')
        cmd('execute as @e[type=minecraft:item] run data get entity @s Item')
        cmd('say graph-count-end')
        check('no-escaped-items','unless entity @e[type=minecraft:item]')
    if workload=='pistons':
        cmd('execute store result score #mobs extended if entity @e[tag=extended_mob]')
        check('exact-mob-population',f'if score #mobs extended matches {bystanders}')
        for i,block in enumerate(payloads):
            audit_blocks(block,sum(1 for j in range(gates) if j%3==i))
        audit_blocks('sticky_piston',gates)
        check('no-block-drops','unless entity @e[type=minecraft:item]')
    if workload=='gravity':
        audit_blocks('sand',meta['blocks'])
        check('no-falling-blocks','unless entity @e[type=minecraft:falling_block,x=-96,y=192,z=-96,dx=191,dy=80,dz=191]')
        check('no-sand-drops','unless entity @e[type=minecraft:item,x=-96,y=192,z=-96,dx=191,dy=80,dz=191]')
    cmd('say EXTENDED_COMPLETE')
    meta['checks']=checks
    meta['items']=expected
    return '\n'.join(lines)+'\n',meta


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('workload',choices=WORKLOADS)
    parser.add_argument('out',type=Path)
    parser.add_argument('seconds',type=int,nargs='?',default=60)
    parser.add_argument('warmup',type=int,nargs='?',default=60)
    parser.add_argument('scale',type=float,nargs='?',default=1.0)
    parser.add_argument('--piston-rate',type=int,default=20)
    args=parser.parse_args()
    if args.seconds<15 or args.warmup<0 or args.scale<=0 or not 20<=args.piston_rate<=10000:
        parser.error('seconds must be >=15, warmup >=0, scale >0')
    script,meta=generate(args.workload,args.seconds,args.warmup,args.scale,args.piston_rate)
    args.out.write_text(script)
    args.out.with_suffix('.json').write_text(json.dumps(meta,indent=2,sort_keys=True)+'\n')
    print(f'{args.workload}: {len(script.splitlines())} commands')
