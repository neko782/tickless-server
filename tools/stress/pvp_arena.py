#!/usr/bin/env python3
"""Real networked survival PvP + building, identical fresh worlds on vanilla/tickless.

python3 tools/stress/pvp_arena.py run LABEL [--players 128 --warmup 15 --seconds 30 --rounds 2]
python3 tools/stress/pvp_arena.py check RUN_DIR PLAYERS

Bots fight fixed partners, with knockback disabled and regeneration to avoid deaths.
Each has a private build spot and slime blocks (instant survival breaking). This is a
controlled combat/build workload, not navigation AI or a model of an entire match.
Latency is client send -> victim hurt feedback, action ack, and matching block update.
Raw nanosecond samples are retained. Missing feedback remains visible as coverage.
Tickless game time is not TPS: its worker simulation step rates are reported separately.
"""
import argparse
import hashlib
import json
import math
import os
import re
import subprocess
from pathlib import Path
from bench_compare import summarize
from soundness import Snbt

ROOT = Path(__file__).resolve().parents[2]


def generate(players, warmup, seconds):
    lines = ["gamerule spawn_mobs false", "gamerule advance_time false",
             "gamerule block_drops false", "gamerule mob_drops false", "gamerule respawn_radius 0",
             "forceload add -32 -32 63 63", "sleep 5",
             "fill -32 199 -32 63 199 63 minecraft:stone", "setworldspawn 0 200 0",
             "scoreboard objectives add arena_hits minecraft.custom:minecraft.damage_dealt",
             "scoreboard objectives add arena_build minecraft.used:minecraft.slime_block",
             "scoreboard objectives add arena_total minecraft.used:minecraft.slime_block",
             f"bots {players} {warmup + seconds + 180}", "arena_ready"]
    for i in range(players):
        pair = i // 2
        x, z = (pair % 8) * 4 + (i % 2) * 2 + .5, (pair // 8) * 4 + .5
        lines += [f"tp Bot{i} {x} 200 {z} {90 if i % 2 else -90} 0",
                  f"attribute Bot{i} minecraft:knockback_resistance base set 1"]
    lines += ["gamemode survival @a", "effect give @a minecraft:regeneration infinite 10 true",
              "effect give @a minecraft:saturation infinite 0 true", "give @a minecraft:slime_block 64",
              "sleep 3", "kill @e[type=!minecraft:player]", "sleep 1", "arena warmup", f"sleep {warmup}",
              # Reset after warmup; checks must establish measured-window work, not just setup.
              "scoreboard players set @a arena_hits 0", "scoreboard players set @a arena_build 0",
              "give @a minecraft:slime_block 64", "kill @e[type=!minecraft:player]", "sleep 2", "say BENCH_START", "time query gametime",
              "arena measure", f"sleep {seconds}", "arena end", "say BENCH_END", "time query gametime",
              "arena_done", "sleep 1"]
    for i in range(players):
        for label, objective in (("damage", "arena_hits"), ("placement", "arena_build")):
            lines += [f"say check {label}-Bot{i}",
                      f"execute if score Bot{i} {objective} matches 1.."]
        lines += [f"say check alive-Bot{i}", f"execute if entity @a[name=Bot{i},nbt={{Health:20.0f}}]"]
        lines += [f"say inventory Bot{i}", f"scoreboard players get Bot{i} arena_total",
                  f"scoreboard players get Bot{i} arena_build",
                  f"data get entity Bot{i} Inventory"]
    lines += ["say ARENA_SOUNDNESS_COMPLETE"]
    return "\n".join(lines) + "\n"


def percentile(samples, percent):
    ordered = sorted(samples)
    return ordered[max(0, math.ceil(len(ordered) * percent / 100) - 1)] / 1e6


def check(directory, players):
    directory = Path(directory)
    (directory / "arena-result.json").unlink(missing_ok=True)
    for file in ("failure.txt",):
        if (directory / file).exists():
            raise ValueError((directory / file).read_text())
    if (directory / "exit-code.txt").read_text().strip() != "0":
        raise ValueError("unsuccessful exit")
    log = (directory / "out.log").read_text()
    if re.search(r'\b(?:ERROR|Exception|Error:)\b|<--\[HERE\]|has waited 10s|failures [1-9]', log):
        raise ValueError("server error, command error, lock stall or worker failure")
    if log.count('[Server] ARENA_SOUNDNESS_COMPLETE') != 1 or 'Stopping server' not in log:
        raise ValueError("missing completion/shutdown")
    if log.index('[Server] ARENA_SOUNDNESS_COMPLETE') > log.index('Stopping server'):
        raise ValueError('completion appeared after shutdown')
    if any(log.count('[Server] ' + marker) != 1 for marker in ('BENCH_START', 'BENCH_END')):
        raise ValueError('missing or duplicate measurement markers')
    expected = [f'{kind}-Bot{i}' for i in range(players) for kind in ('damage', 'placement', 'alive')]
    seen, pending = [], None
    for line in log.splitlines():
        marker = re.search(r'\[Server\] check (\S+)$', line)
        if marker:
            if pending is not None:
                raise ValueError(f'missing check result: {pending}')
            pending = marker[1]
            seen.append(pending)
        elif pending and ('Test passed' in line or 'Test failed' in line):
            if 'Test failed' in line:
                raise ValueError(f'failed check: {pending}')
            pending = None
    if pending or seen != expected:
        raise ValueError('missing, duplicate or reordered soundness checks')
    inventories, builds, active, used = {}, {}, None, None
    for line in log.splitlines():
        marker = re.search(r'\[Server\] inventory (Bot\d+)$', line)
        if marker:
            if active is not None: raise ValueError('missing inventory dump')
            active, used = marker[1], None
        elif active and (score := re.search(r'has (-?\d+) \[arena_total\]', line)):
            used = int(score[1])
        elif active and (score := re.search(r'has (-?\d+) \[arena_build\]', line)):
            builds[active] = int(score[1])
        elif active and 'has the following entity data: ' in line:
            inventory = Snbt(line.split('entity data: ', 1)[1]).parse()
            if any(item['id'] != 'minecraft:slime_block' for item in inventory):
                raise ValueError('unexpected inventory item')
            remaining = sum(item['count'] for item in inventory)
            if used is None or used + remaining != 128 or active in inventories:
                raise ValueError(f'block inventory conservation failed for {active}: used={used}, remaining={remaining}')
            inventories[active] = remaining
            active = None
    if active or len(inventories) != players:
        raise ValueError('missing inventory conservation audits')
    bots = (directory / 'bots.log').read_text()
    before_complete = bots.split('ARENA_COMPLETE seconds=', 1)[0]
    if 'failed' in bots or 'disconnected:' in before_complete:
        raise ValueError('bot failure or premature disconnect')
    rows = [json.loads(line.split('ARENA_RESULT ', 1)[1]) for line in bots.splitlines() if 'ARENA_RESULT ' in line]
    if [r['name'] for r in rows] != [f'Bot{i}' for i in range(players)]:
        raise ValueError('missing/duplicate bot results')
    duration = re.findall(r'ARENA_COMPLETE seconds=([\d.]+)$', bots, re.M)
    if len(duration) != 1:
        raise ValueError('missing/duplicate client measurement completion')
    seconds = float(duration[0])
    if seconds <= 0: raise ValueError('invalid measurement duration')
    for r in rows:
        if r['attacks'] < .9 * seconds or r['places'] + r['breaks'] < 3.6 * seconds:
            raise ValueError(f'client failed to sustain requested action rate for {r["name"]}')
        if any(v <= 0 for key in ('hit_ns', 'ack_ns', 'update_ns') for v in r[key]):
            raise ValueError('invalid latency sample')
        if min(r['attacks'], r['places'], r['breaks'], len(r['hit_ns']), len(r['update_ns'])) <= 0:
            raise ValueError(f'no real combat/build feedback for {r["name"]}')
        if r['pending_acks'] or len(r['ack_ns']) != r['places'] + r['breaks']:
            raise ValueError(f'unacknowledged actions for {r["name"]}')
        if len(r['hit_ns']) < .95 * r['attacks'] or len(r['update_ns']) < .95 * (r['places'] + r['breaks']):
            raise ValueError(f'insufficient gameplay feedback coverage for {r["name"]}')
        if builds.get(r['name'], 0) < .95 * r['places']:
            raise ValueError(f'placements were rejected for {r["name"]}')
        if len(r['hit_ns']) > r['attacks'] or len(r['update_ns']) > r['places'] + r['breaks']:
            raise ValueError('duplicate feedback')
    rate, how, cpu = summarize(directory / 'out.log')
    # Direct committed player work gives an independent check of timer/request estimates.
    # Use only steady five-second rows wholly inside the measured window and require
    # the observed entity population to equal the player count before normalizing.
    start = end = None
    worker_rows = []
    day = previous = 0
    for line in log.splitlines():
        stamp = re.match(r'\[(\d\d):(\d\d):(\d\d)', line)
        if not stamp: continue
        h, m, sec = map(int, stamp.groups())
        now = h * 3600 + m * 60 + sec
        if previous - now > 43200: day += 86400
        previous = now
        now += day
        if '[Server] BENCH_START' in line: start = now
        if '[Server] BENCH_END' in line: end = now
        row = re.search(r'online players (\d+), completed player steps (\d+)', line)
        if row and start is not None and end is None and now > start + 5:
            worker_rows.append((now, int(row[1]), int(row[2])))
    if start is None or end is None or abs((end - start) - seconds) > 1.1:
        raise ValueError('client/server measurement windows disagree')
    completed_tps = None
    if len(worker_rows) >= 2:
        if any(row[1] != players for row in worker_rows):
            raise ValueError('unexpected player population in completed-step measurement')
        first, last = worker_rows[0], worker_rows[-1]
        completed_tps = (last[2] - first[2]) / (last[0] - first[0]) / players
    if 'World workers:' in log and completed_tps is None:
        rate, how = None, 'too short for worker simulation rate (game clock excluded)'
    if 'World workers:' in log and completed_tps is not None:
        rate, how = completed_tps, 'committed player simulation steps / online players / seconds'
    result = {'run': directory.name, 'players': players, 'seconds': float(duration[0]),
              'simulation_rate': rate, 'completed_player_tps': completed_tps, 'rate_basis': how, 'cpu_cores': cpu if math.isfinite(cpu) else None,
              'attacks': sum(r['attacks'] for r in rows),
              'block_actions': sum(r['places'] + r['breaks'] for r in rows),
              'soundness_checks': len(seen), 'inventory_audits': len(inventories), 'latency': {}}
    for key in ('hit_ns', 'ack_ns', 'update_ns'):
        samples = [v for r in rows for v in r[key]]
        result['latency'][key] = {'samples': len(samples), **{f'p{p}_ms': percentile(samples, p) for p in (50, 95, 99)}}
    (directory / 'arena-result.json').write_text(json.dumps(result, indent=2) + '\n')
    return result


def run(args):
    if not re.fullmatch(r'[A-Za-z0-9_-]+', args.label):
        raise ValueError('label must contain only letters, digits, underscores and hyphens')
    if args.players < 2 or args.players > 256 or args.players % 2:
        raise ValueError('players must be even, between 2 and 256')
    if not 1 <= args.warmup <= 30 or not 5 <= args.seconds <= 30 or args.rounds < 1:
        raise ValueError('warmup 1..30, seconds 5..30 (finite block supply), rounds >= 1')
    script = ROOT / 'build' / f'arena-{args.label}.txt'
    script.parent.mkdir(exist_ok=True)
    script.write_text(generate(args.players, args.warmup, args.seconds))
    metadata = {key: value for key, value in vars(args).items()}
    metadata['git_commit'] = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT, text=True).strip()
    metadata['environment'] = {k: os.environ.get(k, default) for k, default in
                               (('STRESS_HEAP', '6g'), ('STRESS_GC', 'zgc'),
                                ('STRESS_IMAGE', 'eclipse-temurin:27-jdk'), ('STRESS_JAVA_OPTS', ''))}
    metadata['artifact_sha256'] = {str(path.relative_to(ROOT)): hashlib.sha256(path.read_bytes()).hexdigest()
                                   for path in [ROOT / 'build/downloads/server-26.3.jar',
                                                *sorted((ROOT / args.install / 'lib').glob('tickless-server-*.jar'))]}
    metadata['bot_sha256'] = hashlib.sha256((ROOT / 'tools/BotClient.java').read_bytes()).hexdigest()
    config = ROOT / 'build' / f'arena-{args.label}-config.json'
    if args.resume:
        previous = json.loads(config.read_text())
        for key, value in metadata.items():
            if key != 'resume' and previous.get(key) != value:
                raise ValueError(f'resume configuration changed: {key}')
    else:
        config.write_text(json.dumps(metadata, indent=2) + '\n')
    results = []
    for round_ in range(1, args.rounds + 1):
        for server in (('vanilla', 'tickless') if round_ % 2 else ('tickless', 'vanilla')):
            name = f'arena-{args.label}-{server}-{round_}'
            directory = ROOT / 'build' / f'stress-{name}'
            install = args.install if server == 'tickless' else 'build/install/vanilla'
            env = dict(os.environ, SERVER_INSTALL=install, BOT_INSTALL=args.install,
                       BOT_OPTS=f'-Dbot.arenaDir=/workspace/build/stress-{name}', TIMEOUT='360')
            print(f'Running {server} round {round_}: {args.players} players', flush=True)
            if not (args.resume and directory.exists()):
                subprocess.run(['tools/stress/run.sh', name, str(script)], cwd=ROOT, env=env, check=True)
            result = check(directory, args.players)
            results.append(result)
            print(json.dumps(result), flush=True)
    (ROOT / 'build' / f'arena-{args.label}-results.json').write_text(json.dumps(results, indent=2) + '\n')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest='command', required=True)
    p = sub.add_parser('run')
    p.add_argument('label')
    p.add_argument('--players', type=int, default=128)
    p.add_argument('--warmup', type=int, default=15)
    p.add_argument('--seconds', type=int, default=30)
    p.add_argument('--rounds', type=int, default=2)
    p.add_argument('--install', default='build/install/minecraft-server')
    p.add_argument('--resume', action='store_true', help='re-audit existing runs and continue, requiring identical configuration and artifacts')
    p = sub.add_parser('check')
    p.add_argument('directory')
    p.add_argument('players', type=int)
    args = parser.parse_args()
    if args.command == 'run':
        run(args)
    else:
        print(json.dumps(check(args.directory, args.players), indent=2))
