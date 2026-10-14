#!/usr/bin/env python3
"""Run adversarial conservation workloads (gen_adversarial.py) or audit their logs.

  adversarial.py run LABEL [WORKLOAD...] [--install build/install-dev] [--seconds 10] [--rounds 3]
  adversarial.py check RUN_DIRECTORY MANIFEST

Each later audit must hold exactly the baseline's items (per id and components) and the same
set of tagged entity UUIDs, with no UUID twice. 'atmost' audits (explosions) may lose items
and entities but must not gain any. Every "say check" result must pass.
"""
import argparse
import json
import os
import re
import subprocess
import sys
from collections import Counter
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from gen_adversarial import PAYLOAD, WORKLOADS, generate  # noqa: E402
from soundness import Snbt  # noqa: E402

ROOT = Path(__file__).resolve().parents[2]
ERRORS = re.compile(r'/ERROR\]|Exception in world event|Encountered an unexpected exception|has waited 10s for a world lock|'
                    r'OUTSIDE THE WAIT GRAPH|World wait outside the lock graph|<--\[HERE\]|Unknown or incomplete command')


def item_key(value):
    components = value.get('components', {})
    return value['id'] + (json.dumps(components, sort_keys=True) if components else '')


def collect_items(value, out):
    if isinstance(value, list):
        for part in value:
            collect_items(part, out)
    elif isinstance(value, dict):
        if isinstance(value.get('id'), str):
            count = value.get('count', 1)
            if type(count) is not int or count < 1:
                raise ValueError(f'Invalid item count in {value}')
            out[item_key(value)] += count
        else:  # equipment: slot -> item
            for part in value.values():
                collect_items(part, out)


def parse_logs(logs):
    audits, checks, pending, active, kind = {}, [], None, None, None
    for phase, text in enumerate(logs):
        for line in text.splitlines():
            m = re.search(r'\[Server\] check (\S+)$', line)
            if m:
                if pending:
                    checks.append((pending, 'missing'))
                pending = m[1]
                continue
            if pending and ('Test passed' in line or 'Test failed' in line):
                checks.append((pending, 'passed' if 'Test passed' in line else 'failed'))
                pending = None
                continue
            m = re.search(r'\[Server\] audit (\S+) (begin|end)$', line)
            if m:
                if m[2] == 'begin':
                    active = m[1]
                    audits[active] = {'items': Counter(), 'uuids': Counter(), 'phase': phase}
                else:
                    active = None
                continue
            m = re.search(r'\[Server\] kind (\S+)$', line)
            if m:
                kind = m[1]
                continue
            if active and 'has the following' in line:
                head, data = line.split(' data: ', 1)
                if kind == 'items':
                    collect_items(Snbt(data).parse(), audits[active]['items'])
                elif kind == 'uuid':
                    name = head.split(': ', 1)[-1].rsplit(' has the following', 1)[0]
                    ints = re.findall(r'-?\d+', data)
                    audits[active]['uuids'][f'{name}/' + ','.join(ints)] += 1
    if pending:
        checks.append((pending, 'missing'))
    return audits, checks


def check_run(directory, manifest):
    directory = Path(directory)
    meta = json.loads(Path(manifest).read_text())
    problems = []
    logs = []
    for phase in range(meta['phases']):
        path = directory / (f'out.log.{phase + 1}' if phase < meta['phases'] - 1 else 'out.log')
        if not path.exists():
            raise ValueError(f'missing log for phase {phase}: {path}')
        text = path.read_text(errors='replace')
        logs.append(text)
        for line in text.splitlines():
            if ERRORS.search(line):
                problems.append(f'phase {phase}: {line[:300]}')
                break
    failure = directory / 'failure.txt'
    if failure.exists():
        problems.append('harness: ' + failure.read_text().strip())
    exit_file = directory / 'exit-code.txt'
    if not exit_file.exists() or exit_file.read_text().strip() != '0':
        problems.append('final phase did not exit cleanly')
    complete = f'[Server] ADVERSARIAL_COMPLETE {meta["workload"]}'
    if complete not in logs[-1]:
        problems.append('run did not reach the end')
    audits, checks = parse_logs(logs)
    for name, result in checks:
        if result != 'passed':
            problems.append(f'check {name}: {result}')
    expected_checks = [name for _, name in meta['checks']]
    if [name for name, _ in checks] != expected_checks:
        problems.append(f'checks missing or out of order: {len(checks)} of {len(expected_checks)}')
    names = [a[1] for a in meta['audits']]
    if list(audits) != names:
        problems.append(f'audits missing or out of order: {list(audits)} vs {names}')
    summary = []
    if names and names[0] in audits:
        base = audits[names[0]]
        for _, name, mode in meta['audits']:
            if name not in audits:
                continue
            current = audits[name]
            dups = [u for u, n in current['uuids'].items() if n > 1]
            if dups:
                problems.append(f'{name}: {len(dups)} duplicated entity UUIDs, e.g. {dups[:3]}')
            summary.append(f'{name}: {sum(current["items"].values())} items, {len(current["uuids"])} entities')
            if mode in ('equal', 'grow'):
                growth = Counter(meta.get('growth', {})) if mode == 'grow' else Counter()
                for key in sorted(set(base['items']) | set(current['items']) | set(growth)):
                    want = base['items'][key] + growth[key]
                    if want != current['items'][key]:
                        problems.append(f'{name}: {key} expected {want}, found {current["items"][key]} ({current["items"][key] - want:+d})')
                lost = set(base['uuids']) - set(current['uuids'])
                gained = set(current['uuids']) - set(base['uuids'])
                if lost:
                    problems.append(f'{name}: {len(lost)} entities lost, e.g. {sorted(lost)[:5]}')
                if gained:
                    problems.append(f'{name}: {len(gained)} entities appeared, e.g. {sorted(gained)[:5]}')
            elif mode == 'atmost':
                for p in PAYLOAD:
                    key = f'minecraft:{p}'
                    if current['items'][key] > base['items'][key]:
                        problems.append(f'{name}: {key} duplicated {base["items"][key]} -> {current["items"][key]}')
                gained = set(current['uuids']) - set(base['uuids'])
                if gained:
                    problems.append(f'{name}: {len(gained)} entities appeared, e.g. {sorted(gained)[:5]}')
    return problems, summary


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest='command', required=True)
    run = sub.add_parser('run')
    run.add_argument('label')
    run.add_argument('workloads', nargs='*', choices=WORKLOADS)
    run.add_argument('--install', default='build/install-dev')
    run.add_argument('--seconds', type=int, default=10)
    run.add_argument('--rounds', type=int, default=3)
    run.add_argument('--workers', type=int, default=0, help='carrier threads, 0 for the default')
    run.add_argument('--timeout', type=int, default=600)
    chk = sub.add_parser('check')
    chk.add_argument('directory', type=Path)
    chk.add_argument('manifest', type=Path)
    args = parser.parse_args()
    os.chdir(ROOT)
    if args.command == 'check':
        problems, summary = check_run(args.directory, args.manifest)
        print('\n'.join(summary))
        print('\n'.join(problems) or 'PASS')
        sys.exit(1 if problems else 0)
    out = ROOT / 'build/adversarial' / args.label
    out.mkdir(parents=True, exist_ok=True)
    failed = []
    for workload in args.workloads or WORKLOADS:
        script = generate(workload, args.seconds, args.rounds)
        meta = {'workload': workload, 'phases': len(script.phases), 'audits': script.audits, 'checks': script.checks,
                'growth': script.expect_growth}
        manifest = out / f'{workload}.json'
        manifest.write_text(json.dumps(meta, indent=1) + '\n')
        name = f'adv-{args.label}-{workload}'
        print(f'Running {name} ({len(script.phases)} phase(s))', flush=True)
        java = f'-Dtickless.workers={args.workers}' if args.workers else ''
        for phase, lines in enumerate(script.phases):
            commands = out / f'{workload}-{phase}.txt'
            commands.write_text('\n'.join(lines) + '\n')
            env = dict(os.environ, SERVER_INSTALL=args.install, TIMEOUT=str(args.timeout),
                       KEEP_WORLD='1' if phase else '0', STRESS_HEAP=os.environ.get('STRESS_HEAP', '4g'))
            crash = 'crash' in lines
            with (out / f'{workload}-{phase}-harness.log').open('w') as log:
                status = subprocess.run(['tools/stress/run.sh', name, str(commands), java], env=env,
                                        stdout=log, stderr=subprocess.STDOUT).returncode
            if (status != 0) != crash or (ROOT / 'build' / f'stress-{name}' / 'failure.txt').exists():
                print(f'  phase {phase} exited {status}', flush=True)
                if not crash:
                    break
        problems, summary = check_run(ROOT / 'build' / f'stress-{name}', manifest)
        for line in summary:
            print('  ' + line)
        if problems:
            failed.append(workload)
            print(f'FAIL {workload}:')
            for p in problems[:40]:
                print('  ' + p)
        else:
            print(f'PASS {workload}', flush=True)
    if failed:
        sys.exit('FAILED: ' + ' '.join(failed))
    print('ALL ADVERSARIAL WORKLOADS PASSED')


if __name__ == '__main__':
    main()
