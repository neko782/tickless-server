#!/usr/bin/env python3
"""Finite, empty-start free-for-all with random chest/floor looting and exact audits."""
import argparse
import collections
import hashlib
import json
import os
from pathlib import Path
import random
import re
import subprocess
import sys
from pvp_arena import ROOT, percentile
from pvp_chaos import snbt, process_cpu
from soundness import Snbt
from gen_soundness import signature


def generate(directory, players, seconds):
    directory.mkdir(parents=True,exist_ok=True)
    pack=directory/'randomarena'; functions=pack/'data/randomarena/function';functions.mkdir(parents=True,exist_ok=True)
    (pack/'pack.mcmeta').write_text((ROOT/'tools/stress/datapack/tickstress/pack.mcmeta').read_text())
    rng=random.Random(719); points=[]
    while len(points)<20:
        p=(rng.randrange(3,29),rng.randrange(3,29))
        if all(abs(p[0]-x)+abs(p[1]-z)>=4 for x,z in points): points.append(p)
    chests=points[:16]; floors=points[16:]
    (directory/'loot-points.csv').write_text('\n'.join(f'{x},{z}'+(',chest' if (x,z) in chests else '') for x,z in points)+'\n')
    expected=collections.Counter(); supplies=[]
    types=[f'{tier}_{part}' for tier in ('leather','golden','chainmail','iron','diamond') for part in ('helmet','chestplate','leggings','boots')]+['iron_sword','diamond_sword','golden_apple','slime_block']
    for i in range(max(players*5,160)):
        item=types[i%len(types)]
        value={'id':'minecraft:'+item,'count':16 if item=='slime_block' else 4 if item=='golden_apple' else 1}
        if item not in ('slime_block','golden_apple'): value['components']={'minecraft:unbreakable':{},'minecraft:custom_data':{'random_arena':1}}
        expected[signature(value)]+=value['count'];supplies.append(value)
    containers={p:[] for p in chests}; ground=[]
    for i,item in enumerate(supplies):
        # Reserve one quarter for actual floor pickups; overflow cannot exceed chest capacity.
        c=chests[i%len(chests)]
        if rng.random()<.25 or len(containers[c])>=27: ground.append((floors[i%len(floors)],item))
        else: containers[c].append(dict(item,Slot=len(containers[c])))
    lines=['gamerule spawn_mobs false','gamerule advance_time false','gamerule random_tick_speed 0',
           'gamerule keep_inventory false','gamerule respawn_radius 0','gamerule max_entity_cramming 0',
           'gamerule show_death_messages false','gamerule send_command_feedback false',
           'forceload add -16 -16 47 47','sleep 5','fill 0 199 0 31 199 31 minecraft:stone',
           'setworldspawn 16 200 16','kill @e[type=!minecraft:player]',
           'scoreboard objectives add random_blocks dummy']
    for name,criterion in [('ra_damage','minecraft.custom:minecraft.damage_dealt'),('ra_kills','playerKillCount'),('ra_deaths','deathCount'),('ra_build','minecraft.used:minecraft.slime_block'),('ra_chests','minecraft.custom:minecraft.open_chest'),('ra_pickups','minecraft.picked_up:minecraft.golden_apple')]:
        lines.append(f'scoreboard objectives add {name} {criterion}')
    for (x,z),items in containers.items(): lines.append(f'setblock {x} 200 {z} minecraft:chest'+snbt({'Items':items}))
    # Four hopper catchers demonstrate the finite gear falling from the sky too.
    hoppers=[]
    for i,(x,z) in enumerate(chests[:4]):
        lines.append(f'setblock {x} 201 {z} minecraft:hopper[facing=down]');hoppers.append((x,201,z))
    drops=[]
    for i,((x,z),item) in enumerate(ground):
        if i<4: x,z=chests[i]; y=216
        else:y=200.4
        drops.append(f'summon minecraft:item {x+.5} {y} {z+.5} '+snbt({'Item':item,'PickupDelay':0}))
    lines += [f'bots {players} {seconds+300}','arena_ready','tag @a add random_fighter','gamemode survival @a[tag=random_fighter]','clear @a[tag=random_fighter]']
    for i in range(players):
        x,z=2+rng.random()*27,2+rng.random()*27
        lines += [f'tp Bot{i} {x:.3f} 200 {z:.3f}',f'spawnpoint Bot{i} 16 200 16',f'attribute Bot{i} minecraft:knockback_resistance base set 1',f'attribute Bot{i} minecraft:max_health base set 6']
    # Frozen before the start audit: nobody can collect supplies before proving empty inventories.
    # Bots remain idle until arena measure; no warmup consumes any finite stock.
    lines += ['say RANDOM_EMPTY_BEGIN']
    for i in range(players): lines += [f'data get entity Bot{i}']
    lines += ['say RANDOM_EMPTY_END']
    lines += drops
    for name in ('ra_damage','ra_kills','ra_deaths','ra_build','ra_chests','ra_pickups'):
        lines += [f'scoreboard players set Bot{i} {name} 0' for i in range(players)]
    lines += ['say BENCH_START','time query gametime','sleep 1','arena warmup','arena measure',f'sleep {seconds}',
              'arena end','say BENCH_END','time query gametime','arena_done',
              'poll 120 execute unless entity @a[tag=random_fighter,nbt={Health:0.0f}]','sleep 3','tick freeze','sleep 2','say RANDOM_AUDIT_BEGIN']
    for i in range(players):
        lines += [f'say holder Bot{i}',f'data get entity Bot{i}']
        for name in ('ra_damage','ra_kills','ra_deaths','ra_build','ra_chests','ra_pickups'):lines += [f'scoreboard players get Bot{i} {name}']
    holders=[]
    for x,z in chests:holders.append((x,200,z))
    holders+=hoppers
    for x,y,z in holders: lines += [f'say holder {x},{y},{z}',f'data get block {x} {y} {z} Items']
    lines += ['say ground begin','execute as @e[type=minecraft:item] run data get entity @s','say ground end', 'scoreboard players set #placed random_blocks 0','function randomarena:count_blocks','scoreboard players get #placed random_blocks','say RANDOM_SOUNDNESS_COMPLETE']
    (functions/'count_blocks.mcfunction').write_text('\n'.join(f'execute if block {x} 200 {z} minecraft:slime_block run scoreboard players add #placed random_blocks 1' for x in range(32) for z in range(32))+'\n')
    manifest={'players':players,'seconds':seconds,'expected':dict(expected),'holders':[f'{x},{y},{z}' for x,y,z in holders]}
    (directory/'commands.txt').write_text('\n'.join(lines)+'\n');(directory/'manifest.json').write_text(json.dumps(manifest,indent=2)+'\n')
    return pack,manifest


def tick_rate(log, players):
    start=end=None; game=[]; steps=[]; previous=day=0
    for line in log.splitlines():
        m=re.match(r'\[(\d\d):(\d\d):(\d\d)',line)
        if not m:continue
        now=sum(int(v)*f for v,f in zip(m.groups(),(3600,60,1)))
        if previous-now>43200:day+=86400
        previous=now;now+=day
        if '[Server] BENCH_START' in line:start=now
        if '[Server] BENCH_END' in line:end=now
        if start is not None and len(game)<2 and (m:=re.search(r'The game time is (\d+)',line)):game.append((now,int(m[1])))
        if start is not None and end is None and now>start+5 and (m:=re.search(r'online players (\d+), completed player steps (\d+)',line)):steps.append((now,int(m[1]),int(m[2]),int(t[1]) if (t:=re.search(r'stats time ns (-?\d+)',line)) else None))
    if start is None or end is None:raise ValueError('missing measurement markers')
    if 'World workers:' in log:
        if len(steps)<2 or any(r[1]!=players for r in steps):raise ValueError('missing committed player steps')
        precise = all(s[3] is not None for s in steps)
        if any(s[3] is not None for s in steps) and not precise:raise ValueError('mixed player step clocks')
        elapsed = (steps[-1][3]-steps[0][3])/1e9 if precise else steps[-1][0]-steps[0][0]
        if elapsed <= 0:raise ValueError('nonpositive player step interval')
        return {'player_tps':(steps[-1][2]-steps[0][2])/elapsed/players,
                'player_tps_clock':'monotonic_ns' if precise else 'log_seconds'}
    if len(game)!=2:raise ValueError('missing vanilla tick count')
    return {'tps':(game[1][1]-game[0][1])/(game[1][0]-game[0][0])}


def check(directory):
    directory=Path(directory); manifest=json.loads((directory/'random-manifest.json').read_text());n=manifest['players']
    if (directory/'failure.txt').exists() or (directory/'exit-code.txt').read_text().strip()!='0':raise ValueError('runner failed')
    log=(directory/'out.log').read_text(); bots=(directory/'bots.log').read_text()
    # Socket-close callbacks may report pending chat writes during normal shutdown.
    # Permit only this exact exception after both the completed audit and the actual
    # server-thread stop message; keep gameplay errors and other shutdown failures fatal.
    error_log=log;shutdown_closed=0
    seal=log.find('[Server] RANDOM_SOUNDNESS_COMPLETE')
    stop=next((m for m in re.finditer(r'^\[\d\d:\d\d:\d\d\] \[Server thread/INFO\]: Stopping server$',log,re.M) if seal>=0 and m.start()>seal),None)
    if stop is not None:
        tail,shutdown_closed=re.subn(r'^io\.netty\.channel\.StacklessClosedChannelException\r?$', '',log[stop.start():],flags=re.M)
        error_log=log[:stop.start()]+tail
    if re.search(r'\bERROR\b|Exception|<--\[HERE\]|has waited 10s',error_log):raise ValueError('server error')
    if log.count('[Server] RANDOM_SOUNDNESS_COMPLETE')!=1:raise ValueError('incomplete audit')
    empty=log.split('[Server] RANDOM_EMPTY_BEGIN',1)[1].split('[Server] RANDOM_EMPTY_END',1)[0]
    lists=re.findall(r'has the following entity data: (.+)',empty)
    if len(lists)!=n or len(set(re.findall(r'(Bot\d+) has the following entity data:',empty)))!=n or any((v:=Snbt(s).parse()).get('Inventory',[]) or v.get('equipment',{}) for s in lists):raise ValueError('players did not start empty')
    actual=collections.Counter();holders={};ground_ids=set();ambient=[];active=None;ground=False;scores={};placed=None
    def add(items):
        for item in items:
            key=signature(item,decoded=True)
            if key not in manifest['expected']:raise ValueError('unknown/modified item '+key)
            actual[key]+=item['count']
    for line in log.split('[Server] RANDOM_AUDIT_BEGIN',1)[1].splitlines():
        m=re.search(r'\[Server\] holder (.+)$',line)
        if m:
            if active is not None:raise ValueError('missing holder')
            active=m[1]
            if active not in {f'Bot{i}' for i in range(n)}|set(manifest['holders']):raise ValueError('unknown holder')
            if active in holders:raise ValueError('duplicate holder')
        if active and 'has the following' in line:
            raw=line.split('data: ',1)[1];v=Snbt(raw).parse()
            if active.startswith('Bot'):
                if v.get('Health',0)<=0:raise ValueError('dead player at audit')
                inventory=v.get('Inventory',[])
                selected=next((item for item in inventory if item.get('Slot')==v.get('SelectedItemSlot')),None)
                view=v.get('SelectedItem')
                if bool(selected)!=bool(view) or (selected and (signature(selected,decoded=True)!=signature(view,decoded=True) or selected['count']!=view['count'])):raise ValueError('selected item disagrees with inventory')
                add(inventory);add(list(v.get('equipment',{}).values()))
            else:add(v)
            holders[active]=v;active=None
        if '[Server] ground begin' in line:ground=True
        elif '[Server] ground end' in line:ground=False
        elif ground and 'entity data: ' in line:
            v=Snbt(line.split('entity data: ',1)[1]).parse();uuid=tuple(v['UUID'])
            if uuid in ground_ids:raise ValueError('duplicate floor entity')
            ground_ids.add(uuid)
            item=v['Item'];ids={json.loads(k)['id'] for k in manifest['expected']}
            x,y,z=v['Pos']
            if item['id'] not in ids and not (0<=x<=32 and 199<=y<=232 and 0<=z<=32):
                ambient.append({'id':item['id'],'position':v['Pos'],'count':item['count']})
            else:add([item])
        m=re.search(r'#placed has (\d+) \[random_blocks\]',line)
        if m:placed=int(m[1])
        m=re.search(r'(Bot\d+) has (-?\d+) \[(ra_\w+)\]',line)
        if m:
            if (m[1],m[3]) in scores:raise ValueError('duplicate gameplay stat')
            scores[(m[1],m[3])]=int(m[2])
    if active or ground or set(holders)!={f'Bot{i}' for i in range(n)}|set(manifest['holders']) or placed is None:raise ValueError('missing holders/blocks')
    actual[signature({'id':'minecraft:slime_block'})]+=placed
    if dict(actual)!=manifest['expected']:raise ValueError(f'conservation mismatch {dict(actual)} != {manifest["expected"]}')
    totals={key:sum(scores.get((f'Bot{i}',key),0) for i in range(n)) for key in ('ra_damage','ra_kills','ra_deaths','ra_build','ra_chests','ra_pickups')}
    if len(scores)!=6*n or min(totals.values())<=0:raise ValueError('missing combat, death, building, chest or floor apple pickup workload '+str(totals))
    rows=[json.loads(s.split('ARENA_RESULT ',1)[1]) for s in bots.splitlines() if 'ARENA_RESULT ' in s]
    if 'ARMOR_DOWNGRADE' in bots:raise ValueError('armor downgrade')
    if [r['name'] for r in rows]!=[f'Bot{i}' for i in range(n)] or 'ARENA_COMPLETE' not in bots or 'decoder failed' in bots:raise ValueError('incomplete clients')
    for r in rows:
        if len(r['ack_ns'])+r['pending_acks']!=r['places']+r['breaks']+r['chest_opens']:raise ValueError('ack accounting mismatch')
        if r['moves']<=0:raise ValueError('inactive bot')
    if sum(r['upgrades'] for r in rows)<=0:raise ValueError('no armor upgrades')
    sent=collections.Counter(re.findall(r'ATTACK_SENT (Bot\d+) (\d+)',bots)); audit=re.findall(r'ATTACK_AUDIT (Bot\d+) (\d+) (\d+) (\S+)',log)
    outcomes=collections.Counter(row[3] for row in audit)
    if audit:
        received=collections.Counter((a,t) for a,seq,t,reason in audit)
        if sent!=received:raise ValueError('unaccounted attack packets '+str(sent-received))
        seen=set()
        for a,seq,t,reason in audit:
            if (a,seq) in seen:raise ValueError('duplicate attack outcome')
            seen.add((a,seq))
        allowed={'accepted','damage_cooldown','attack_cooldown','client_not_loaded','spectator','target_missing','world_border','out_of_range','piercing_weapon','invalid_target','disabled_item','unattackable','invulnerable','creative_invulnerable','target_dead','zero_damage','fire_resistance','fully_blocked','invulnerable_or_not_loaded','pvp_disabled_or_team'}
        if set(outcomes)-allowed or not outcomes['accepted']:raise ValueError('unexplained rejection/no accepted hit')
    elif 'World workers:' in log:raise ValueError('tickless attack audit missing')
    latency={key:{'samples':len(v),**{f'p{p}_ms':percentile(v,p) for p in (50,95,99)}} for key in ('ack_ns','chest_ns','update_ns') if (v:=[t for r in rows for t in r[key]])}
    result={'status':'PASS','shutdown_closed_channel_warnings':shutdown_closed,'run':directory.name,'players':n,'items_conserved':sum(actual.values()),'placed_blocks':placed,'ground_entities':len(ground_ids)-len(ambient),'unrelated_natural_drops':ambient,'gameplay':totals,'armor_upgrades':sum(r['upgrades'] for r in rows),'attacks_sent':sum(sent.values()),'attack_outcomes':dict(outcomes),'hit_audit':'exact server outcomes' if audit else 'vanilla has no internal tracing','latency':latency,'cpu':process_cpu(directory),**tick_rate(log,n)}
    (directory/'random-result.json').write_text(json.dumps(result,indent=2)+'\n');return result


def run(args):
    if not re.fullmatch(r'[\w-]+',args.label):raise ValueError('invalid label')
    if any(n<2 or n>2048 for n in args.players) or not 15<=args.seconds<=90:raise ValueError('players 2..2048, seconds 15..90')
    config=dict(vars(args));config['git_commit']=subprocess.check_output(['git','rev-parse','HEAD'],cwd=ROOT,text=True).strip()
    paths=[ROOT/'tools/BotClient.java',ROOT/'tools/stress/pvp_random.py',ROOT/'tools/stress/run.sh',ROOT/'tools/stress/cpu_sample.py',ROOT/'build/downloads/server-26.3.jar',*sorted((ROOT/args.install/'lib').glob('tickless-server-*.jar'))]
    config['sha256']={str(p.relative_to(ROOT)):hashlib.sha256(p.read_bytes()).hexdigest() for p in paths}
    config['environment']={k:os.environ.get(k,v) for k,v in [('STRESS_HEAP','6g'),('BOT_HEAP','2g'),('STRESS_GC','zgc'),('STRESS_IMAGE','eclipse-temurin:27-jdk')]}
    (ROOT/'build'/f'random-{args.label}-config.json').write_text(json.dumps(config,indent=2)+'\n')
    results=[]
    for n in args.players:
        fixture=ROOT/'build'/f'random-{args.label}-{n}';pack,manifest=generate(fixture,n,args.seconds)
        for server in args.servers:
            name=f'random-{args.label}-{server}-{n}';directory=ROOT/'build'/f'stress-{name}'
            env=dict(os.environ,SERVER_INSTALL=args.install if server=='tickless' else 'build/install/vanilla',BOT_INSTALL=args.install,DATAPACK=str(pack),STRESS_MAX_PLAYERS=str(n+4),TIMEOUT='600',BOT_OPTS=f'-Dbot.chaos=true -Dbot.randomArena=true -Dbot.arenaDir=/workspace/build/stress-{name} -Dbot.lootPoints=/workspace/{fixture.relative_to(ROOT)}/loot-points.csv -Dbot.attackEvery=2 -Dbot.blockEvery=10',STRESS_JAVA_OPTS='-Dtickless.attackAudit=true')
            print('Running',name,flush=True);rc=subprocess.run(['tools/stress/run.sh',name,str(fixture/'commands.txt')],cwd=ROOT,env=env).returncode
            (directory/'random-manifest.json').write_text(json.dumps(manifest,indent=2)+'\n')
            try:
                if rc:raise ValueError('runner failed')
                result=check(directory)
            except (ValueError,FileNotFoundError) as e:result={'status':'FAIL','run':name,'reason':str(e)}
            results.append(result);print(json.dumps(result),flush=True);(ROOT/'build'/f'random-{args.label}-results.json').write_text(json.dumps(results,indent=2)+'\n')
            if result['status']=='FAIL':return 1
    return 0

if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__);sub=p.add_subparsers(dest='command',required=True)
    r=sub.add_parser('run');r.add_argument('label');r.add_argument('--players',type=int,nargs='+',default=[64]);r.add_argument('--seconds',type=int,default=45);r.add_argument('--servers',nargs='+',choices=['tickless','vanilla'],default=['tickless','vanilla']);r.add_argument('--install',default='build/install/random-arena')
    c=sub.add_parser('check');c.add_argument('directory');a=p.parse_args()
    if a.command=='run':sys.exit(run(a))
    print(json.dumps(check(a.directory),indent=2))
