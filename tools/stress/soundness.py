#!/usr/bin/env python3
"""Run five extended correctness tests on fresh worlds, or audit their logs.

  soundness.py run LABEL [WORKLOAD...] --install build/install/minecraft-server
  soundness.py check RUN_DIRECTORY MANIFEST
"""
import argparse
import hashlib
import json
import os
import re
import subprocess
import sys
from pathlib import Path

from gen_soundness import WORKLOADS, generate, signature

ROOT = Path(__file__).resolve().parents[2]


class Snbt:
    """Decode the server's item dumps, preserving nested components and strings.

    Regex-based brace removal would miscount nested items or strings containing
    braces. Reject malformed/truncated dumps rather than producing partial totals.
    """
    def __init__(self, text):
        self.text, self.pos = text, 0
        self.typed = False

    def space(self):
        while self.pos < len(self.text) and self.text[self.pos].isspace():
            self.pos += 1

    def take(self, char):
        self.space()
        if self.text[self.pos:self.pos+1] == char:
            self.pos += 1
            return True
        return False

    def require(self, char):
        if not self.take(char):
            raise ValueError(f'Malformed SNBT: expected {char} at {self.pos}')

    def token(self):
        self.space()
        start = self.pos
        while self.pos < len(self.text) and self.text[self.pos] not in ':,{}[] \t\r\n':
            self.pos += 1
        if self.pos == start:
            raise ValueError(f'Missing SNBT token at {self.pos}')
        return self.text[start:self.pos]

    def string(self):
        self.space()
        quote = self.text[self.pos]
        self.pos += 1
        result = []
        while self.pos < len(self.text):
            char = self.text[self.pos]
            self.pos += 1
            if char == quote:
                return ''.join(result)
            if char == '\\':
                if self.pos == len(self.text):
                    break
                char = self.text[self.pos]
                self.pos += 1
                escapes = {'b': '\b', 'f': '\f', 'n': '\n', 'r': '\r', 't': '\t'}
                if char in escapes:
                    char = escapes[char]
                elif char in ('x', 'u', 'U'):
                    width = {'x': 2, 'u': 4, 'U': 8}[char]
                    digits = self.text[self.pos:self.pos+width]
                    if len(digits) != width or not re.fullmatch('[0-9a-fA-F]+', digits):
                        raise ValueError('Malformed SNBT hex escape')
                    char = chr(int(digits, 16))
                    self.pos += width
                elif char not in (quote, '\\'):
                    raise ValueError('Unsupported escape in SNBT string')
            result.append(char)
        raise ValueError('Unterminated SNBT string')

    def value(self):
        self.space()
        char = self.text[self.pos:self.pos+1]
        if char in ('"', "'"):
            return self.string()
        if self.take('{'):
            result = {}
            if self.take('}'):
                return result
            while True:
                self.space()
                key = self.string() if self.text[self.pos:self.pos+1] in ('"', "'") else self.token()
                if key in result:
                    raise ValueError('Duplicate SNBT key')
                self.require(':')
                previous = self.typed
                self.typed = previous or key == 'minecraft:custom_data'
                result[key] = self.value()
                self.typed = previous
                if self.take('}'):
                    return result
                self.require(',')
        if self.take('['):
            self.space()
            kind = 'list'
            if re.match(r'[BIL];', self.text[self.pos:], re.I):
                kind = {'B': 'byte_array', 'I': 'int_array', 'L': 'long_array'}[self.text[self.pos].upper()]
                self.pos += 2
            result = []
            if self.take(']'):
                return {'nbt_type': kind, 'value': result} if self.typed else result
            while True:
                result.append(self.value())
                if self.take(']'):
                    return {'nbt_type': kind, 'value': result} if self.typed else result
                self.require(',')
        token = self.token()
        if re.fullmatch(r'[+-]?\d+[bBsSlL]?', token):
            value = int(token.rstrip('bBsSlL'))
            kind = {'b': 'byte', 's': 'short', 'l': 'long'}.get(token[-1].lower(), 'int')
            return {'nbt_type': kind, 'value': value} if self.typed else value
        if re.fullmatch(r'[+-]?(?:\d+\.?\d*|\.\d+)(?:[eE][+-]?\d+)?[fFdD]?', token):
            value = float(token.rstrip('fFdD'))
            return {'nbt_type': 'float' if token[-1].lower() == 'f' else 'double', 'value': value} if self.typed else value
        if token in ('true', 'false'):
            return {'nbt_type': 'byte', 'value': int(token == 'true')} if self.typed else token == 'true'
        return token

    def parse(self):
        result = self.value()
        self.space()
        if self.pos != len(self.text):
            raise ValueError('Trailing data in SNBT dump')
        return result


def check_run(directory, manifest):
    directory = Path(directory)
    result_path = directory / 'soundness-result.json'
    result_path.unlink(missing_ok=True)
    meta = json.loads(Path(manifest).read_text())
    if not (directory / 'exit-code.txt').exists() or (directory / 'exit-code.txt').read_text().strip() != '0':
        raise ValueError('Missing or unsuccessful server exit')
    if (directory / 'failure.txt').exists():
        raise ValueError('Harness failure: ' + (directory / 'failure.txt').read_text().strip())
    log = (directory / 'out.log').read_text()
    if re.search(r'\b(?:ERROR|Exception|Error:)\b|failures [1-9]\d*|has waited 10s for a world lock|OUTSIDE THE WAIT GRAPH|World wait outside the lock graph|<--\[HERE\]', log):
        raise ValueError('Server error, command syntax error, worker failure or lock stall')
    complete = f'[Server] SOUNDNESS_COMPLETE {meta["workload"]}'
    if log.count(complete) != 1 or 'Stopping server' not in log or log.index(complete) > log.index('Stopping server'):
        raise ValueError('Missing, duplicate or out-of-order completion/shutdown')

    seen, pending, active, inventories, completed = [], None, None, {}, False
    for line in log.splitlines():
        if complete in line:
            if pending is not None or active is not None or seen != meta['checks'] or list(inventories) != list(meta['audits']):
                raise ValueError('Completion appeared before all required audits finished')
            completed = True
        marker = re.search(r'\[Server\] check (\S+)$', line)
        if marker:
            if completed:
                raise ValueError('Check appeared after completion')
            if pending is not None:
                raise ValueError(f'Missing result for {pending}')
            pending = marker[1]
            seen.append(pending)
        elif pending and ('Test passed' in line or 'Test failed' in line):
            if 'Test failed' in line:
                raise ValueError(f'Audit failed: {pending}')
            pending = None
        marker = re.search(r'\[Server\] inventory (\S+) (begin|end)$', line)
        if marker:
            if completed:
                raise ValueError('Inventory audit appeared after completion')
            name, action = marker.groups()
            if action == 'begin':
                if active is not None or name in inventories or name not in meta['audits']:
                    raise ValueError('Nested, duplicate or unexpected inventory audit')
                active = name
                inventories[name] = {'items': {}, 'blocks': 0, 'holders': []}
            else:
                if active != name:
                    raise ValueError('Missing or reversed inventory audit markers')
                active = None
        elif active and 'has the following' in line:
            current = inventories[active]
            if 'has the following block data: ' in line:
                current['blocks'] += 1
                location = re.search(r'(-?\d+, -?\d+, -?\d+) has the following block data:', line)
                if not location or location[1] in current['holders']:
                    raise ValueError('Missing or duplicate container coordinates')
                current['holders'].append(location[1])
                values = Snbt(line.split('block data: ', 1)[1]).parse()
                if not isinstance(values, list) or any(not isinstance(v, dict) for v in values):
                    raise ValueError('Container dump is not a list')
                slots = [v.get('Slot') for v in values]
                if len(set(slots)) != len(slots) or any(not isinstance(s, int) or not 0 <= s < 27 for s in slots):
                    raise ValueError('Invalid or duplicate container slot')
            elif 'has the following entity data: ' in line:
                values = [Snbt(line.split('entity data: ', 1)[1]).parse()]
            else:
                raise ValueError('Unrecognized inventory dump')
            for value in values:
                if not isinstance(value, dict) or not isinstance(value.get('id'), str):
                    raise ValueError('Malformed item stack')
                if not isinstance(value.get('components', {}), dict):
                    raise ValueError('Malformed item components')
                count = value.get('count', 1)
                maximum = value.get('components', {}).get('minecraft:max_stack_size', 1 if value['id'] == 'minecraft:iron_sword' else 64)
                if type(count) is not int or type(maximum) is not int or not 1 <= count <= maximum:
                    raise ValueError('Invalid item stack count')
                key = signature(value, decoded=True)
                current['items'][key] = current['items'].get(key, 0) + count
    if pending is not None or seen != meta['checks']:
        raise ValueError('Missing, reordered or unexpected required checks')
    if active is not None or list(inventories) != list(meta['audits']):
        raise ValueError('Missing or reordered inventory audits')
    for name, actual in inventories.items():
        if actual != meta['audits'][name]:
            raise ValueError(f'{name}: item/component totals or container dump count differ: {actual}')
    result = {'workload': meta['workload'], 'checks': len(seen), 'inventory_audits': len(inventories), 'status': 'PASS', 'engine': meta.get('engine'), 'configuration': meta.get('configuration')}
    result_path.write_text(json.dumps(result, indent=2) + '\n')
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest='command', required=True)
    run = sub.add_parser('run')
    run.add_argument('label')
    run.add_argument('workloads', nargs='*', choices=WORKLOADS)
    run.add_argument('--install', default='build/install/minecraft-server')
    run.add_argument('--seconds', type=int, default=15)
    run.add_argument('--rounds', type=int, default=4)
    run.add_argument('--workers', type=int, default=16)
    run.add_argument('--gc', choices=('g1', 'zgc'), default='g1')
    run.add_argument('--timeout', type=int, default=1200)
    check = sub.add_parser('check')
    check.add_argument('directory', type=Path)
    check.add_argument('manifest', type=Path)
    args = parser.parse_args()
    os.chdir(ROOT)
    if args.command == 'check':
        print(json.dumps(check_run(args.directory, args.manifest), indent=2))
        return
    if not re.fullmatch(r'[A-Za-z0-9_-]+', args.label) or min(args.seconds, args.rounds, args.workers, args.timeout) < 1:
        parser.error('Safe label and positive duration, rounds, workers and timeout required')
    install = Path(args.install).resolve()
    install.relative_to(ROOT)
    jars = [*install.glob('lib/tickless-server*.jar'), *install.glob('lib/minecraft-server-*.jar')]
    if len(jars) != 1:
        parser.error('Install must contain exactly one packaged server jar')
    output = ROOT / 'build/soundness' / args.label
    output.mkdir(parents=True, exist_ok=True)
    failed = []
    for workload in args.workloads or WORKLOADS:
        script, meta = generate(workload, args.seconds, args.rounds)
        meta['engine'] = {'install': str(install.relative_to(ROOT)), 'sha256': hashlib.sha256(jars[0].read_bytes()).hexdigest()}
        meta['configuration'] = {'workers': args.workers, 'gc': args.gc, 'seconds': args.seconds, 'rounds': args.rounds}
        commands, manifest = output / f'{workload}.txt', output / f'{workload}.json'
        commands.write_text(script)
        # Preserve inventory audit order, which is also part of the check.
        manifest.write_text(json.dumps(meta, indent=2) + '\n')
        env = dict(os.environ, SERVER_INSTALL=str(install.relative_to(ROOT)), TIMEOUT=str(args.timeout), STRESS_GC=args.gc, STRESS_JAVA_OPTS='', FAIL_FAST='1', LOCK_WAIT_KILL='1')
        name = f'soundness-{args.label}-{workload}'
        print(f'Running {name}', flush=True)
        try:
            with (output / f'{workload}-harness.log').open('w') as log:
                subprocess.run(['tools/stress/run.sh', name, str(commands), f'-Dtickless.workers={args.workers}'], env=env, stdout=log, stderr=subprocess.STDOUT, check=True)
            print(json.dumps(check_run(ROOT / 'build' / f'stress-{name}', manifest)), flush=True)
        except (ValueError, subprocess.CalledProcessError) as error:
            failed.append(workload)
            print(f'FAIL {workload}: {error}', flush=True)
    if failed:
        raise ValueError('Failed workloads: ' + ', '.join(failed))
    print('ALL FIVE SOUNDNESS WORKLOADS PASSED' if len(args.workloads or WORKLOADS) == 5 else 'ALL SELECTED SOUNDNESS WORKLOADS PASSED')


if __name__ == '__main__':
    try:
        main()
    except (ValueError, subprocess.CalledProcessError) as error:
        sys.exit(str(error))
