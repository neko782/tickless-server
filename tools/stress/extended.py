#!/usr/bin/env python3
"""Run/check/summarize the extended workloads sequentially on fresh worlds.

  extended.py run LABEL [WORKLOAD...] --install build/install/minecraft-server
  (default: the original five; name bees, endermen, snowballs, jockeys, aquarium to run those)
  extended.py check RUN_DIR COMMANDS_JSON
  extended.py compare BEFORE_LABEL AFTER_LABEL [--require-speedup 3]

A speedup gate applies to each saturated entity workload (crowd/village/pistons and the novel five), using
committed steps/s. Capped workloads and finite completion times are still reported.
"""
import argparse
import hashlib
import json
import os
import re
import statistics
import subprocess
import sys
import zipfile
from pathlib import Path

from bench_entities import read_run, read_cpu_windows
from gen_extended import ORIGINAL, WORKLOADS, generate

ROOT = Path(__file__).resolve().parents[2]


def entity_step_metric(jar):
    with zipfile.ZipFile(jar) as archive:
        scheduler = archive.read('net/minecraft/server/level/LevelEventScheduler.class')
    return ('committed-entities-and-passengers-v2'
            if b'committed-entities-and-passengers-v2' in scheduler
            else 'legacy-entity-events-v1')


def check_run(directory, metadata):
    directory, metadata = Path(directory), Path(metadata)
    # A failed recheck must not leave a prior PASS available for comparison.
    (directory/'extended-result.json').unlink(missing_ok=True)
    meta = json.loads(metadata.read_text())
    if meta['workload']=='jockeys' and meta.get('entity_step_metric')!='committed-entities-and-passengers-v2':
        raise ValueError('jockeys: manifest lacks the matching passenger counter')
    for filename, value in (('exit-code.txt','0'),):
        path=directory/filename
        if not path.exists() or path.read_text().strip()!=value:
            raise ValueError(f'{directory}: missing or unsuccessful {filename}')
    log=(directory/'out.log').read_text()
    if 'EXTENDED_COMPLETE' not in log:
        raise ValueError(f'{directory}: did not complete the workload audit')
    if re.search(r'failures [1-9]\d*',log):
        raise ValueError(f'{directory}: worker failure counter is nonzero')
    if (directory/'failure.txt').exists():
        raise ValueError(f'{directory}: harness failure: '+(directory/'failure.txt').read_text().strip())
    if re.search(r'\b(?:ERROR|Exception|Error:)\b', log) or 'OUTSIDE THE WAIT GRAPH' in log or 'World wait outside the lock graph' in log:
        raise ValueError(f'{directory}: error or wait outside the resolver graph')
    if 'Stopping server' not in log:
        raise ValueError(f'{directory}: missing graceful shutdown')
    pending=None
    for line in log.splitlines():
        marker=re.search(r'\[Server\] check (\S+)',line)
        if marker:
            if pending is not None:
                raise ValueError(f'Missing result for {pending}')
            pending=marker[1]
        elif pending and ('Test passed' in line or 'Test failed' in line):
            if 'Test failed' in line:
                raise ValueError(f'Audit failed: {pending}')
            pending=None
    if pending is not None:
        raise ValueError(f'Missing result for {pending}')
    # The baseline can spend >10s warming up a contended sensor. This is a liveness
    # diagnostic, distinct from conservation checks. Accept it only when explicitly
    # requested, before BENCH_START; every measured/audit stall remains a failure.
    measured=False
    startup_waits=0
    for line in log.splitlines():
        if 'BENCH_START' in line:
            measured=True
        if 'has waited 10s for a world lock' in line:
            if measured or not meta.get('allow_startup_waits',False):
                raise ValueError(f'{directory}: world lock stall during measurement/audit')
            startup_waits+=1
    if meta['items'] and not re.search(r'Slot: 1b',log):
        raise ValueError(f'{directory}: no observed conveyor/ring transfers')

    named=re.findall(r'\[Server\] check (\S+)',log)
    if named!=meta['checks']:
        raise ValueError(f'{directory}: named audit checks do not match generated manifest')
    if meta['items']:
        if log.count('[Server] graph-count-begin')!=1 or log.count('[Server] graph-count-end')!=1 or log.index('[Server] graph-count-begin')>log.index('[Server] graph-count-end'):
            raise ValueError(f'{directory}: missing or reversed item audit markers')
        expected=directory/'expected-items.json'
        expected.write_text(json.dumps(meta['items']))
        subprocess.run([sys.executable,'tools/stress/check_graph.py',str(directory/'out.log'),str(expected)],cwd=ROOT,check=True,stdout=subprocess.DEVNULL)
    result=summarize(directory,meta)
    result['startup_lock_stalls']=startup_waits
    if 'engine' in meta:
        result['engine']=meta['engine']
    meta.setdefault('heap','6g')
    meta.setdefault('gc','zgc')
    result['configuration']={k:v for k,v in meta.items() if k not in ('items','checks','allow_startup_waits','engine')}
    (directory/'extended-result.json').write_text(json.dumps(result,indent=2,sort_keys=True)+'\n')
    return result


def summarize(directory,meta):
    _,start,end,rows=read_run(directory/'out.log')
    if end<=start:
        raise ValueError('Empty benchmark window')
    steady=[values for t,values in rows if start+5<t<end]
    completed=[]
    previous=day=0
    for line in (directory/'out.log').read_text().splitlines():
        match=re.match(r'\[(\d\d):(\d\d):(\d\d)\].*completed entity steps (\d+)',line)
        if match:
            h,m,s,count=map(int,match.groups())
            t=h*3600+m*60+s
            if previous-t>43200:
                day+=86400
            previous=t
            completed.append((t+day,count))
    intervals=[(a,b) for a,b in zip(completed,completed[1:]) if start<a[0]<b[0]<end]
    rates=[(b[1]-a[1])/(b[0]-a[0]) for a,b in intervals]
    result={'workload':meta['workload'],'seconds':end-start,'checks':'PASS','samples':len(rates)}
    cpu=read_cpu_windows(directory/'out.log')
    result['cpu_cores']=statistics.mean(cpu)/100 if cpu else None
    result['events_per_second']=statistics.mean(row[0] for row in steady) if steady else None
    if 'minimum_events_per_second' in meta and (not steady or min(row[0] for row in steady)<meta['minimum_events_per_second']):
        raise ValueError('Insufficient block activity: piston clocks stopped or fixture is not active')
    if 'minimum_non_entity_events_per_second' in meta:
        if not intervals:
            raise ValueError('No measured entity counters for block activity check')
        span=intervals[-1][1][0]-intervals[0][0][0]
        # Whole-second timestamps: use the shortest possible combined interval so the
        # entity subtraction errs toward rejecting idle clocks, not accepting them.
        entity_upper=(intervals[-1][1][1]-intervals[0][0][1])/max(1,span-1)
        result['non_entity_events_lower_estimate']=result['events_per_second']-entity_upper
        if result['non_entity_events_lower_estimate']<meta['minimum_non_entity_events_per_second']:
            raise ValueError('Piston clocks stopped: entity updates cannot count as block activity')
    if 'entities' in meta:
        if len(rates)<3:
            raise ValueError('Need at least three complete committed-step samples')
        result['completed_steps_per_second']=statistics.mean(rates)
        result['entity_steps_per_second']=result['completed_steps_per_second']/meta['entities']
        third=max(1,len(rates)//3)
        result['drift']=statistics.mean(rates[-third:])/statistics.mean(rates[:third])-1
    return result


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    sub=parser.add_subparsers(dest='command',required=True)
    run=sub.add_parser('run')
    run.add_argument('label')
    run.add_argument('workloads',nargs='*',choices=WORKLOADS)
    run.add_argument('--install',default='build/install/minecraft-server')
    run.add_argument('--seconds',type=int,default=60)
    run.add_argument('--warmup',type=int,default=120)
    run.add_argument('--scale',type=float,default=1)
    run.add_argument('--workers',type=int,default=16)
    run.add_argument('--timeout',type=int,default=1200)
    run.add_argument('--gc',choices=('zgc','g1'),default='g1',help='same garbage collector for each compared engine')
    run.add_argument('--piston-rate',type=int,default=20,help='requested simulation rate for piston workload, identical for both engines')
    run.add_argument('--heap',default='6g',help='same maximum Java heap for each compared engine')
    run.add_argument('--allow-startup-waits',action='store_true',help='record baseline startup lock stalls; reject stalls after BENCH_START')
    check=sub.add_parser('check')
    check.add_argument('directory',type=Path)
    check.add_argument('metadata',type=Path)
    compare=sub.add_parser('compare')
    compare.add_argument('before')
    compare.add_argument('after')
    compare.add_argument('--require-speedup',type=float)
    compare.add_argument('--repeat',nargs=2,action='append',default=[],metavar=('BEFORE','AFTER'),help='include another matched pair in the reported mean; print individual gains too')
    compare.add_argument('--workloads',nargs='+',choices=WORKLOADS,default=ORIGINAL)
    args=parser.parse_args()
    os.chdir(ROOT)
    if args.command=='check':
        print(json.dumps(check_run(args.directory,args.metadata),indent=2))
    elif args.command=='run':
        if not re.fullmatch('[A-Za-z0-9_-]+',args.label):
            parser.error('Invalid label')
        if not re.fullmatch(r'[1-9][0-9]*[gGmM]',args.heap):
            parser.error('heap must be a positive size such as 6g or 12g')
        if args.seconds<25 or args.warmup<0 or args.scale<=0 or args.workers<1 or not 20<=args.piston_rate<=10000:
            parser.error('seconds >=25, warmup >=0, scale >0 and workers >=1 required')
        commands_dir=Path('build/extended')/args.label
        commands_dir.mkdir(parents=True,exist_ok=True)
        for workload in args.workloads or ORIGINAL:
            script,meta=generate(workload,args.seconds,args.warmup,args.scale,args.piston_rate)
            meta['workers']=args.workers
            meta['heap']=args.heap.lower()
            meta['gc']=args.gc
            jar=next(j for pattern in ('lib/tickless-server*.jar','lib/minecraft-server-*.jar') for j in Path(args.install).glob(pattern))
            if 'entities' in meta:
                meta['entity_step_metric']=entity_step_metric(jar)
                if workload=='jockeys' and meta['entity_step_metric']!='committed-entities-and-passengers-v2':
                    parser.error('jockeys requires the matching passenger counter; apply tools/stress/novel-steps.patch to the baseline')
            meta['engine']={'install':args.install,'sha256':hashlib.sha256(jar.read_bytes()).hexdigest()}
            meta['allow_startup_waits']=args.allow_startup_waits
            commands=commands_dir/f'{workload}.txt'
            metadata=commands.with_suffix('.json')
            commands.write_text(script)
            metadata.write_text(json.dumps(meta,indent=2,sort_keys=True)+'\n')
            name=f'{args.label}-{workload}'
            # This driver owns the recorded JVM configuration; ambient harness options
            # must not silently override its carrier count, heap or collector.
            env=dict(os.environ,SERVER_INSTALL=args.install,TIMEOUT=str(args.timeout),STRESS_GC=args.gc,STRESS_JAVA_OPTS='')
            if workload=='pistons':
                env['POLL_INTERVAL']='0.07'
            if args.allow_startup_waits:
                env['LOCK_WAIT_KILL']='0'
            print(f'Running {name}',flush=True)
            with (commands_dir/f'{workload}-harness.log').open('w') as output:
                subprocess.run([os.environ.get('STRESS_RUNNER','tools/stress/run.sh'),name,str(commands),f'-Dtickless.workers={args.workers} -Xmx{args.heap}'],env=env,stdout=output,stderr=subprocess.STDOUT,check=True)
            print(json.dumps(check_run(Path('build')/f'stress-{name}',metadata)),flush=True)
    else:
        print('| Workload | Before | After | Speedup | Audits |')
        print('| --- | ---: | ---: | ---: | --- |')
        failed=False
        entity_rows=0
        for workload in args.workloads:
            pairs=[]
            for labels in [(args.before,args.after)]+args.repeat:
                paths=[Path('build')/f'stress-{label}-{workload}'/'extended-result.json' for label in labels]
                if not all(p.exists() for p in paths):
                    raise ValueError(f'Missing validated results for {workload}: {labels}')
                pair=[json.loads(p.read_text()) for p in paths]
                for result in pair:
                    result['configuration'].setdefault('heap','6g')
                    result['configuration'].setdefault('gc','zgc')
                    if 'entities' in result['configuration']:
                        result['configuration'].setdefault('entity_step_metric','legacy-entity-events-v1')
                        if workload=='jockeys' and result['configuration']['entity_step_metric']!='committed-entities-and-passengers-v2':
                            raise ValueError('jockeys: results lack the matching passenger counter; rerun both engines')
                    if result['checks']!='PASS':
                        raise ValueError('A compared run failed its audits')
                pairs.append(pair)
            configuration=pairs[0][0]['configuration']
            if any(r['configuration']!=configuration for pair in pairs for r in pair):
                raise ValueError(f'{workload}: configurations differ')
            if args.repeat:
                for side in (0,1):
                    hashes={pair[side].get('engine',{}).get('sha256') for pair in pairs}
                    if None in hashes or len(hashes)!=1:
                        raise ValueError(f'{workload}: repeats must use the same recorded engine on each side')
            before,after=pairs[0]
            key='entity_steps_per_second' if 'entity_steps_per_second' in before else 'seconds' if workload=='gravity' else 'cpu_cores'
            if any(r[key] is None or r[key]<=0 for pair in pairs for r in pair):
                raise ValueError(f'Missing metric for {workload}')
            before_value=statistics.mean(pair[0][key] for pair in pairs)
            after_value=statistics.mean(pair[1][key] for pair in pairs)
            gain=after_value/before_value if key=='entity_steps_per_second' else before_value/after_value
            unit='steps/mob/s' if key=='entity_steps_per_second' else 's' if key=='seconds' else 'CPU cores'
            print(f'| {workload} | {before_value:.2f} {unit} | {after_value:.2f} {unit} | {gain:.2f}× | PASS / PASS |')
            if args.repeat:
                individual=[a[key]/b[key] if key=='entity_steps_per_second' else b[key]/a[key] for b,a in pairs]
                print('Individual gains: '+', '.join(f'{g:.3f}×' for g in individual))
            if key=='entity_steps_per_second':
                entity_rows+=1
                if any(abs(r['drift'])>0.15 for pair in pairs for r in pair):
                    print(f'Warning: {workload} throughput drifts >15%; extend warmup before claiming a gain.')
                    if args.require_speedup:
                        failed=True
                if args.require_speedup and gain<args.require_speedup:
                    failed=True
        if args.require_speedup and (failed or entity_rows==0):
            raise ValueError('Requested speedup not established on the selected entity workloads')


if __name__=='__main__':
    try:
        main()
    except (ValueError,subprocess.CalledProcessError) as error:
        sys.exit(str(error))
