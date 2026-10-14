#!/usr/bin/env python3
"""Moving PvP, deaths/respawns, chest looting and finite gear rain; escalate load.

run LABEL --players 256 512 1024 --seconds 30 --attack-every 2 --block-every 2
check RUN_DIR
The finite, unbreakable gear ledger is audited across players, containers and ground
items. Building blocks are a replenished workload fixture and excluded from that ledger.
"""
import argparse
import collections
import hashlib
import json
import math
import os
from pathlib import Path
import re
import statistics
import subprocess
import sys

from pvp_arena import ROOT, percentile
from soundness import Snbt
from gen_soundness import signature

GEAR = ('iron_sword', 'iron_helmet')


def gear(item, slot=None):
    value = {'id': 'minecraft:' + item, 'count': 1,
             'components': {'minecraft:unbreakable': {}, 'minecraft:custom_data': {'chaos_gear': 1}}}
    if slot is not None: value['Slot'] = slot
    return value


def snbt(value):
    # JSON is valid SNBT here: no typed numerical fields are needed for item counts.
    return json.dumps(value, separators=(',', ':'))


def position(i):
    pair = i // 2
    return (pair % 16) * 5 + (i % 2) * 2 + .5, (pair // 16) * 5 + .5


def chest(pair):
    return (pair % 16) * 5 + 1, 200, (pair // 16) * 5 - 2


def generate(directory, players, warmup, seconds):
    directory.mkdir(parents=True, exist_ok=True)
    pack = directory / 'chaos'
    functions = pack / 'data/chaos/function'
    functions.mkdir(parents=True, exist_ok=True)
    (pack / 'pack.mcmeta').write_text(snbt({'pack': {'description': 'PvP chaos fixture', 'min_format': 101, 'max_format': 101}}))
    # Read the current repo's pack format rather than infer it from a release number.
    existing = json.loads((ROOT / 'tools/stress/datapack/tickstress/pack.mcmeta').read_text())
    (pack / 'pack.mcmeta').write_text(json.dumps(existing))
    prototypes = {item: gear(item) for item in GEAR}
    for item in GEAR:
        rain = []
        for pair in range(players // 2):
            x, _, z = chest(pair)
            rain.append(f'summon minecraft:item {x+.5} 218 {z+.5} ' + snbt({'Tags': ['chaos_rain'], 'PickupDelay': 32767, 'Item': gear(item)}))
        rain += [f'scoreboard players add #rain_{item} chaos_ledger {players // 2}']
        (functions / f'rain_{item}.mcfunction').write_text('\n'.join(rain) + '\n')
    (functions / 'refill.mcfunction').write_text('item replace entity @a[tag=chaos_fighter] hotbar.1 with minecraft:slime_block 64\n')
    max_z = position(players - 1)[1] + 4
    lines = ['gamerule spawn_mobs false', 'gamerule advance_time false', 'gamerule advance_weather false',
             'gamerule random_tick_speed 0', 'gamerule block_drops false', 'gamerule mob_drops false',
             'gamerule keep_inventory true', 'gamerule respawn_radius 0', 'gamerule max_entity_cramming 0',
             'gamerule show_death_messages false', 'gamerule send_command_feedback false',
             f'forceload add -16 -16 95 {int(max_z)+16}', 'sleep 5']
    for z in range(-8, int(max_z) + 1, 32):
        lines += [f'fill -8 199 {z} 87 199 {min(z+31,int(max_z))} minecraft:stone']
    lines += ['setworldspawn 0 200 0', 'kill @e[type=!minecraft:player]',
              'scoreboard objectives add chaos_ledger dummy',
              'scoreboard players set #rain_iron_sword chaos_ledger 0',
              'scoreboard players set #rain_iron_helmet chaos_ledger 0']
    for name, criterion in (('chaos_damage', 'minecraft.custom:minecraft.damage_dealt'),
                            ('chaos_deaths', 'deathCount'), ('chaos_kills', 'playerKillCount'),
                            ('chaos_build', 'minecraft.used:minecraft.slime_block'),
                            ('chaos_chests', 'minecraft.custom:minecraft.open_chest')):
        lines.append(f'scoreboard objectives add {name} {criterion}')
    for pair in range(players // 2):
        x, y, z = chest(pair)
        contents = [gear(GEAR[i % 2], i) for i in range(4)]
        lines += [f'setblock {x} {y} {z} minecraft:chest' + snbt({'Items': contents}),
                  f'setblock {x} {y+1} {z} minecraft:hopper[facing=down]']
    lines += [f'bots {players} {warmup+seconds+300}', 'arena_ready', 'tag @a add chaos_fighter',
              'gamemode survival @a[tag=chaos_fighter]']
    for i in range(players):
        x, z = position(i)
        lines += [f'tp Bot{i} {x} 200 {z} {90 if i % 2 else -90} 0',
                  f'spawnpoint Bot{i} {math.floor(x)} 200 {math.floor(z)}',
                  f'attribute Bot{i} minecraft:knockback_resistance base set 1',
                  f'attribute Bot{i} minecraft:max_health base set 6',
                  f'item replace entity Bot{i} hotbar.0 with minecraft:iron_sword[minecraft:unbreakable={{}},minecraft:custom_data={{chaos_gear:1}}]',
                  f'item replace entity Bot{i} armor.head with minecraft:iron_helmet[minecraft:unbreakable={{}},minecraft:custom_data={{chaos_gear:1}}]']
    lines += ['function chaos:refill', 'sleep 3', 'arena warmup']
    for second in range(warmup):
        if second % 4 == 0: lines += ['function chaos:refill']
        lines += ['sleep 1']
    lines += ['kill @e[type=!minecraft:player]', 'sleep 1', 'kill @e[type=minecraft:item]',
              'function chaos:refill']
    for name in ('chaos_damage', 'chaos_deaths', 'chaos_kills', 'chaos_build', 'chaos_chests'):
        lines.append(f'scoreboard players set @a[tag=chaos_fighter] {name} 0')
    lines += ['say BENCH_START', 'time query gametime', 'arena measure']
    rain_counts = collections.Counter()
    for second in range(seconds):
        if second % 2 == 0:
            item = GEAR[(second // 2) % 2]
            rain_counts[item] += players // 2
            lines += [f'function chaos:rain_{item}']
        if second % 4 == 0: lines += ['function chaos:refill']
        if second and second % 10 == 0: lines += ['kill @a[tag=chaos_fighter]']
        lines += ['sleep 1']
    lines += ['arena end', 'say BENCH_END', 'time query gametime', 'arena_done',
              'poll 120 execute unless entity @a[tag=chaos_fighter,nbt={Health:0.0f}]',
              'sleep 5', 'say CHAOS_AUDIT_BEGIN']
    for i in range(players):
        lines += [f'say player Bot{i}', f'execute if entity @a[name=Bot{i}]',
                  f'data get entity Bot{i}']
        for name in ('chaos_damage', 'chaos_deaths', 'chaos_kills', 'chaos_build', 'chaos_chests'):
            lines += [f'scoreboard players get Bot{i} {name}']
    for pair in range(players // 2):
        x, y, z = chest(pair)
        for height in (y, y + 1):
            lines += [f'say container {x},{height},{z}', f'data get block {x} {height} {z} Items']
    for item in GEAR: lines += [f'scoreboard players get #rain_{item} chaos_ledger']
    lines += ['say ground begin', 'execute as @e[type=minecraft:item,tag=chaos_rain] run data get entity @s',
              'say ground end', 'say CHAOS_SOUNDNESS_COMPLETE']
    manifest = {'players': players, 'seconds': seconds, 'warmup': warmup,
                'expected_gear': {signature(prototypes[item]): 2 * players + rain_counts[item] for item in GEAR},
                'rain_counts': dict(rain_counts)}
    (directory / 'commands.txt').write_text('\n'.join(lines) + '\n')
    (directory / 'manifest.json').write_text(json.dumps(manifest, indent=2) + '\n')
    return directory / 'commands.txt', pack, manifest


def process_cpu(directory):
    start = (directory / 'arena-measure').stat().st_mtime_ns
    end = (directory / 'arena-end').stat().st_mtime_ns
    rows = [json.loads(line) for line in (directory / 'process-cpu.jsonl').read_text().splitlines()]
    rows = [r for r in rows if start + 5_000_000_000 < r['time_ns'] < end]
    result = {}
    for name in ('server', 'bots'):
        valid = [r for r in rows if name + '_cpu_percent' in r]
        if not valid: raise ValueError('missing separate CPU samples for ' + name)
        result[name] = {'mean_cores': statistics.mean(r[name + '_cpu_percent'] for r in valid) / 100,
                        'max_cores': max(r[name + '_cpu_percent'] for r in valid) / 100,
                        'max_rss_gib': max(r[name + '_rss_bytes'] for r in valid) / 2**30,
                        'samples': len(valid)}
    return result


def check(directory):
    directory = Path(directory)
    (directory / 'chaos-result.json').unlink(missing_ok=True)
    manifest = json.loads((directory / 'chaos-manifest.json').read_text())
    players = manifest['players']
    if (directory / 'failure.txt').exists() or (directory / 'exit-code.txt').read_text().strip() != '0':
        raise ValueError('harness failed or server did not exit successfully')
    log = (directory / 'out.log').read_text()
    if re.search(r'\b(?:ERROR|Exception|Error:)\b|<--\[HERE\]|failures [1-9]|has waited 10s', log):
        raise ValueError('server/command error or lock stall')
    if log.count('[Server] CHAOS_SOUNDNESS_COMPLETE') != 1 or 'Stopping server' not in log:
        raise ValueError('missing completion/shutdown')
    if log.index('[Server] CHAOS_SOUNDNESS_COMPLETE') > log.index('Stopping server'):
        raise ValueError('completion after shutdown')
    actual = collections.Counter()
    holders, scores, rain = {}, {}, {}
    active, ground = None, False
    ground_ids = set()
    passed_players = set()
    pending_player = None

    def count_items(items):
        for item in items:
            if item['id'] == 'minecraft:slime_block': continue
            key = signature(item, decoded=True)
            if key not in manifest['expected_gear']:
                raise ValueError('unknown/modified gear: ' + key)
            actual[key] += item['count']

    for line in log.splitlines():
        marker = re.search(r'\[Server\] player (Bot\d+)$', line)
        container = re.search(r'\[Server\] container (-?\d+,-?\d+,-?\d+)$', line)
        if marker or container:
            if active is not None: raise ValueError('missing holder dump')
            active = (marker or container)[1]
            if active in holders: raise ValueError('duplicate holder')
            if marker:
                pending_player = active
                scores[active] = {}
        if pending_player and 'Test passed' in line:
            passed_players.add(pending_player)
            pending_player = None
        if active and ('has the following entity data: ' in line or 'has the following block data: ' in line):
            prefix = 'entity data: ' if 'entity data: ' in line else 'block data: '
            items = Snbt(line.split(prefix, 1)[1]).parse()
            if active.startswith('Bot'):
                if items.get('Health', 0) <= 0: raise ValueError('player did not respawn alive: '+active)
                count_items(items.get('Inventory', []))
                count_items(list(items.get('equipment', {}).values()))
            else:
                count_items(items)
            holders[active] = items
            active = None
        score = re.search(r'(Bot\d+) has (-?\d+) \[(chaos_\w+)\]', line)
        if score:
            if score[1] not in scores or score[3] in scores[score[1]]:
                raise ValueError('unexpected or duplicate gameplay score')
            scores[score[1]][score[3]] = int(score[2])
        spawned = re.search(r'#rain_(\w+) has (\d+) \[chaos_ledger\]', line)
        if spawned:
            if spawned[1] in rain: raise ValueError('duplicate rain ledger')
            rain[spawned[1]] = int(spawned[2])
        if '[Server] ground begin' in line: ground = True
        elif '[Server] ground end' in line: ground = False
        elif ground and 'has the following entity data: ' in line:
            entity = Snbt(line.split('entity data: ', 1)[1]).parse()
            uuid = tuple(entity['UUID'])
            if uuid in ground_ids: raise ValueError('duplicate ground item')
            ground_ids.add(uuid)
            count_items([entity['Item']])
    expected_holders = {f'Bot{i}' for i in range(players)} | {f'{x},{y+offset},{z}' for pair in range(players // 2) for x,y,z in [chest(pair)] for offset in (0,1)}
    if active or ground or set(holders) != expected_holders or passed_players != {f'Bot{i}' for i in range(players)}:
        raise ValueError('missing player/container audit or lost player')
    if rain != manifest['rain_counts'] or dict(actual) != manifest['expected_gear']:
        raise ValueError(f'gear conservation failed: expected {manifest["expected_gear"]}, found {dict(actual)}; rain {rain}')
    required = {'chaos_damage', 'chaos_deaths', 'chaos_kills', 'chaos_build', 'chaos_chests'}
    if any(set(values) != required for values in scores.values()): raise ValueError('missing gameplay stats')
    totals = {key: sum(v[key] for v in scores.values()) for key in required}
    if min(totals.values()) <= 0: raise ValueError('combat, death, kill, build or chest workload did not happen')
    bots = (directory / 'bots.log').read_text()
    if 'failed' in bots or 'disconnected:' in bots.split('ARENA_COMPLETE seconds=', 1)[0]:
        raise ValueError('bot failure/premature disconnect')
    rows = [json.loads(line.split('ARENA_RESULT ',1)[1]) for line in bots.splitlines() if 'ARENA_RESULT ' in line]
    if [r['name'] for r in rows] != [f'Bot{i}' for i in range(players)]: raise ValueError('missing/duplicate bot results')
    complete = re.findall(r'ARENA_COMPLETE seconds=([\d.]+)', bots)
    if len(complete) != 1: raise ValueError('missing/duplicate measurement completion')
    seconds = float(complete[0])
    for row in rows:
        if row['moves'] <= 0 or row['attacks'] <= 0 or row['places'] <= 0 or row['chest_opens'] <= 0:
            raise ValueError('inactive bot: ' + row['name'])
        if len(row['ack_ns']) + row['pending_acks'] != row['places'] + row['breaks'] + row['chest_opens']:
            raise ValueError('ack accounting mismatch')
    # Timestamped game-time replies and committed player counter rows inside the markers.
    steady, gametimes = [], []
    start = end = None
    previous = day = 0
    for line in log.splitlines():
        m = re.match(r'\[(\d\d):(\d\d):(\d\d)', line)
        if not m: continue
        now = sum(v*f for v,f in zip(map(int,m.groups()),(3600,60,1)))
        if previous - now > 43200: day += 86400
        previous = now
        now += day
        if '[Server] BENCH_START' in line: start = now
        if '[Server] BENCH_END' in line: end = now
        if start is not None and len(gametimes) < 2 and (m := re.search(r'The game time is (\d+)', line)):
            gametimes.append((now,int(m[1])))
        if start is not None and end is None and now > start + 5 and (m := re.search(r'online players (\d+), completed player steps (\d+)',line)):
            steady.append((now,int(m[1]),int(m[2])))
    if start is None or end is None: raise ValueError('missing server markers')
    tickless = 'World workers:' in log
    if tickless:
        if len(steady) < 2 or any(v[1] != players for v in steady): raise ValueError('missing committed player measurement/population')
        rate = (steady[-1][2] - steady[0][2]) / (steady[-1][0] - steady[0][0]) / players
    else:
        if len(gametimes) != 2: raise ValueError('missing vanilla game times')
        rate = (gametimes[1][1] - gametimes[0][1]) / (gametimes[1][0] - gametimes[0][0])
    latency = {}
    for key in ('hit_ns','ack_ns','update_ns','chest_ns'):
        values = [v for row in rows for v in row[key]]
        if not values: raise ValueError('missing feedback samples: ' + key)
        latency[key] = {'samples': len(values), **{f'p{p}_ms': percentile(values,p) for p in (50,95,99)}}
    result = {'run': directory.name, 'players': players, 'seconds': seconds, 'server_window_seconds': end-start,
              'player_tps' if tickless else 'tps': rate, 'cpu': process_cpu(directory), 'gameplay': totals,
              'deaths': sum(r['deaths'] for r in rows), 'respawns': sum(r['respawns'] for r in rows),
              'moves': sum(r['moves'] for r in rows), 'attacks': sum(r['attacks'] for r in rows),
              'block_actions': sum(r['places']+r['breaks'] for r in rows),
              'pending_acks': sum(r['pending_acks'] for r in rows),
              'block_update_coverage': sum(len(r['update_ns']) for r in rows) / sum(r['places']+r['breaks'] for r in rows),
              'hit_timeouts': sum(r['hit_timeouts'] for r in rows), 'latency': latency,
              'gear_items_conserved': sum(actual.values()), 'gear_holders_audited': len(holders),
              'ground_items_audited': len(ground_ids), 'status': 'PASS'}
    (directory/'chaos-result.json').write_text(json.dumps(result,indent=2)+'\n')
    return result


def run(args):
    if not re.fullmatch(r'[A-Za-z0-9_-]+',args.label): raise ValueError('invalid label')
    if any(n < 2 or n > 4096 or n % 2 for n in args.players): raise ValueError('players must be even, 2..4096')
    if args.seconds < 15 or args.seconds > 60 or args.warmup < 1: raise ValueError('seconds 15..60, warmup >= 1')
    config = dict(vars(args))
    config['git_commit'] = subprocess.check_output(['git','rev-parse','HEAD'],cwd=ROOT,text=True).strip()
    config['environment'] = {key: os.environ.get(key,default) for key,default in
                              [('STRESS_HEAP','6g'),('BOT_HEAP','2g'),('STRESS_GC','zgc'),('STRESS_IMAGE','eclipse-temurin:27-jdk')]}
    artifacts = [ROOT/'tools/BotClient.java',ROOT/'tools/stress/run.sh',ROOT/'tools/stress/cpu_sample.py',
                 ROOT/'build/downloads/server-26.3.jar',*sorted((ROOT/args.install/'lib').glob('tickless-server-*.jar'))]
    config['sha256'] = {str(path.relative_to(ROOT)): hashlib.sha256(path.read_bytes()).hexdigest() for path in artifacts}
    (ROOT/'build'/f'chaos-{args.label}-config.json').write_text(json.dumps(config,indent=2)+'\n')
    aggregate = []
    for players in args.players:
        fixture = ROOT/'build'/f'chaos-{args.label}-{players}'
        commands, pack, manifest = generate(fixture,players,args.warmup,args.seconds)
        manifest.update(attack_every=args.attack_every,block_every=args.block_every)
        for server in args.servers:
            name = f'chaos-{args.label}-{server}-{players}'
            directory = ROOT/'build'/f'stress-{name}'
            env = dict(os.environ, SERVER_INSTALL=args.install if server=='tickless' else 'build/install/vanilla',
                       BOT_INSTALL=args.install, DATAPACK=str(pack), STRESS_MAX_PLAYERS=str(players+4),
                       BOT_OPTS=f'-Dbot.arenaDir=/workspace/build/stress-{name} -Dbot.chaos=true -Dbot.attackEvery={args.attack_every} -Dbot.blockEvery={args.block_every}',
                       TIMEOUT=str(max(600,players//5+args.seconds+args.warmup+300)))
            print(f'Running {server} with {players} moving fighters',flush=True)
            process = subprocess.run(['tools/stress/run.sh',name,str(commands)],cwd=ROOT,env=env)
            (directory/'chaos-manifest.json').write_text(json.dumps(manifest,indent=2)+'\n')
            try:
                if process.returncode: raise ValueError('runner failed')
                result = check(directory)
            except (ValueError,FileNotFoundError) as error:
                result = {'run':directory.name,'players':players,'server':server,'status':'FAIL','reason':str(error)}
            print(json.dumps(result),flush=True)
            aggregate.append(result)
            (ROOT/'build'/f'chaos-{args.label}-results.json').write_text(json.dumps(aggregate,indent=2)+'\n')
            if result['status']=='FAIL' and not args.continue_failures: return 1
    return 0


if __name__ == '__main__':
    p=argparse.ArgumentParser(description=__doc__)
    sub=p.add_subparsers(dest='command',required=True)
    r=sub.add_parser('run');r.add_argument('label');r.add_argument('--players',type=int,nargs='+',default=[256,512,1024])
    r.add_argument('--warmup',type=int,default=10);r.add_argument('--seconds',type=int,default=30)
    r.add_argument('--attack-every',type=int,default=2);r.add_argument('--block-every',type=int,default=2)
    r.add_argument('--servers',nargs='+',choices=['vanilla','tickless'],default=['vanilla','tickless'])
    r.add_argument('--install',default='build/install/minecraft-server');r.add_argument('--continue-failures',action='store_true')
    c=sub.add_parser('check');c.add_argument('directory')
    a=p.parse_args()
    if a.command=='run': sys.exit(run(a))
    print(json.dumps(check(a.directory),indent=2))
