#!/usr/bin/env python3
"""Isolated before/after POI query benchmark; this measures lookup cost, not world FPS."""
import argparse
import hashlib
import os
import json
import re
import statistics
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


def summarize(log, queries, rounds):
    rows = re.findall(r'(empty|populated) round (\d+): ([\d.]+) ns/query, checksum (\d+)', log)
    result = {}
    for name, expected in (('empty', 0), ('populated', 25 * queries)):
        selected = [(int(n), float(ns), int(count)) for label, n, ns, count in rows if label == name]
        if [n for n, _, _ in selected] != list(range(rounds)) or any(count != expected or ns <= 0 for _, ns, count in selected):
            raise ValueError(f'Incomplete or incorrect {name} query results')
        # Discard compilation/warmup rounds; each retained round repeats the same real query.
        times = [ns for _, ns, _ in selected[rounds // 2:]]
        result[name] = {'ns_per_query': statistics.median(times), 'samples': times}
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('before', type=Path)
    parser.add_argument('after', type=Path)
    parser.add_argument('--label', default='poi-query-final')
    parser.add_argument('--queries', type=int, default=100000)
    parser.add_argument('--rounds', type=int, default=16)
    parser.add_argument('--repeats', type=int, default=2)
    parser.add_argument('--require-speedup', type=float, default=3)
    args = parser.parse_args()
    if not re.fullmatch(r'[A-Za-z0-9_-]+', args.label) or args.queries < 1000 or args.rounds < 8 or args.repeats < 1:
        parser.error('Invalid label or insufficient benchmark samples')
    directory = ROOT / 'build/extended' / args.label
    directory.mkdir(parents=True, exist_ok=True)
    mount = ['docker', 'run', '--rm', '--user', f'{os.getuid()}:{os.getgid()}', '-v', f'{ROOT}:/workspace', '-w', f'/workspace/build/extended/{args.label}', 'eclipse-temurin:25-jdk']
    installations = [path.resolve().relative_to(ROOT) for path in (args.before, args.after)]
    subprocess.run(mount + ['javac', '-cp', f'/workspace/{installations[0]}/lib/*', '-d', '.', '/workspace/tools/SectionStorageCheck.java', '/workspace/tools/PoiChunkCacheCheck.java', '/workspace/tools/PoiChunkCacheBench.java'], check=True)
    (directory / 'build/checks').mkdir(parents=True, exist_ok=True)
    results = []
    for repeat in range(args.repeats):
        pair = []
        for name, install in zip(('before', 'after'), installations):
            jar = next(j for pattern in ('lib/tickless-server*.jar', 'lib/minecraft-server-*.jar') for j in (ROOT / install).glob(pattern))
            process = subprocess.run(mount + ['java', '-Xmx2g', '-XX:+UseG1GC', '-cp', f'.:/workspace/{install}/lib/*', 'PoiChunkCacheBench', str(args.queries), str(args.rounds)], stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
            log = process.stdout
            (directory / f'{name}-{repeat}.log').write_text(log)
            process.check_returncode()
            result = summarize(log, args.queries, args.rounds)
            result['engine_sha256'] = hashlib.sha256(jar.read_bytes()).hexdigest()
            pair.append(result)
        gain = pair[0]['populated']['ns_per_query'] / pair[1]['populated']['ns_per_query']
        results.append({'before': pair[0], 'after': pair[1], 'populated_speedup': gain})
        print(f'Repeat {repeat + 1}: populated POI searches {gain:.2f}x faster; checksums match', flush=True)
    output = {'queries_per_round': args.queries, 'rounds': args.rounds, 'heap': '2g', 'gc': 'g1', 'repeats': results}
    (directory / 'results.json').write_text(json.dumps(output, indent=2) + '\n')
    if any(pair['populated_speedup'] < args.require_speedup for pair in results):
        raise ValueError('Requested POI lookup speedup was not reached in every repeat')


if __name__ == '__main__':
    main()
