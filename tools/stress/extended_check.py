#!/usr/bin/env python3
"""Negative controls for the extended conservation/performance harness."""
import json
import tempfile
import subprocess
import sys
from pathlib import Path
from extended import ROOT, check_run
from gen_extended import NOVEL, generate


def fixture(directory, metadata, *, changed_log=None):
    meta={'workload':'crowd','seconds':40,'warmup':0,'scale':1,'entities':100,'checks':['population','blocks'],'items':{},'workers':16}
    metadata.write_text(json.dumps(meta))
    (directory/'exit-code.txt').write_text('0\n')
    for name in ('failure.txt','extended-result.json'):
        (directory/name).unlink(missing_ok=True)
    lines=['[12:00:00] [Server thread/INFO]: [Server] BENCH_START']
    for t in (5,10,15,20,25,30,35):
        lines.append(f'[12:00:{t:02}] [Server thread/INFO]: World workers: 2000 events/s, chunk step rate 20.00/s, entity step rate 20.00/s, server thread 5% busy, pending tasks 0, ticking entities 100, loaded chunks 100, rollbacks 0 (0 for the server thread), failures 0, completed entity steps {t*2000}')
    lines += ['[12:00:40] [Server thread/INFO]: [Server] BENCH_END','[12:00:41] [Server thread/INFO]: [Server] check population','[12:00:41] [Server thread/INFO]: Test passed. Count: 100','[12:00:41] [Server thread/INFO]: [Server] check blocks','[12:00:41] [Server thread/INFO]: Test passed','[12:00:42] [Server thread/INFO]: [Server] EXTENDED_COMPLETE','[12:00:43] [Server thread/INFO]: Stopping server']
    log='\n'.join(lines)+'\n'
    (directory/'out.log').write_text(changed_log(log) if changed_log else log)
    return meta


def reject(directory,metadata,description):
    try:
        check_run(directory,metadata)
    except (ValueError, subprocess.CalledProcessError, SystemExit):
        assert not (directory/'extended-result.json').exists(), 'Retained stale PASS after a failed recheck'
        return
    raise AssertionError(f'Accepted {description}')


with tempfile.TemporaryDirectory() as tmp:
    directory=Path(tmp)
    metadata=directory/'commands.json'
    fixture(directory,metadata)
    result=check_run(directory,metadata)
    assert result['completed_steps_per_second']==2000 and result['entity_steps_per_second']==20
    for description,edit in (
        ('missing result',lambda s:s.replace('[12:00:41] [Server thread/INFO]: Test passed\n','')),
        ('failed block audit',lambda s:s.replace('[12:00:41] [Server thread/INFO]: Test passed\n','[12:00:41] [Server thread/INFO]: Test failed\n')),
        ('missing completion',lambda s:s.replace('EXTENDED_COMPLETE','incomplete')),
        ('worker failure',lambda s:s.replace('failures 0','failures 1')),
        ('logged error',lambda s:s+'[12:00:44] [Worker/ERROR]: broken\n'),
        ('missing required check',lambda s:s.replace('[Server] check blocks','[Server] check other')),
    ):
        fixture(directory,metadata,changed_log=edit)
        reject(directory,metadata,description)
    fixture(directory,metadata)
    (directory/'exit-code.txt').write_text('1')
    reject(directory,metadata,'nonzero exit')
    fixture(directory,metadata)
    (directory/'failure.txt').write_text('poll timed out')
    reject(directory,metadata,'successful-looking watchdog failure')
    fixture(directory,metadata)
    (directory/'exit-code.txt').unlink()
    reject(directory,metadata,'missing exit status')
    stall='[11:59:00] [World Event/WARN]: has waited 10s for a world lock\n      at net.minecraft.Test.run(Test.java:10)\n'
    meta=fixture(directory,metadata,changed_log=lambda s:stall+s)
    reject(directory,metadata,'unapproved startup stall')
    meta['allow_startup_waits']=True
    metadata.write_text(json.dumps(meta))
    assert check_run(directory,metadata)['startup_lock_stalls']==1
    meta=fixture(directory,metadata,changed_log=lambda s:s.replace('[12:00:40]',stall+'[12:00:40]',1))
    meta['allow_startup_waits']=True
    metadata.write_text(json.dumps(meta))
    reject(directory,metadata,'measurement stall with startup exception')
    meta=fixture(directory,metadata)
    meta['minimum_events_per_second']=2001
    metadata.write_text(json.dumps(meta))
    reject(directory,metadata,'idle or insufficient piston activity')
    meta['minimum_events_per_second']=1999
    metadata.write_text(json.dumps(meta))
    check_run(directory,metadata)
    meta['minimum_non_entity_events_per_second']=100
    metadata.write_text(json.dumps(meta))
    reject(directory,metadata,'entity throughput hiding stopped piston clocks')
    fixture(directory,metadata,changed_log=lambda s:s.replace('2000 events/s','4000 events/s'))
    meta['minimum_non_entity_events_per_second']=100
    metadata.write_text(json.dumps(meta))
    check_run(directory,metadata)
    # Exact per-item totals are checked, not just entity presence or a grand total.
    meta=fixture(directory,metadata,changed_log=lambda s:s.replace('[12:00:42]', '[12:00:41] [Server thread/INFO]: [Server] graph-count-begin\n[12:00:41] [Server thread/INFO]: chest has the following block data: [{Slot: 1b, id: "minecraft:diamond", count: 2}]\n[12:00:41] [Server thread/INFO]: [Server] graph-count-end\n[12:00:42]',1))
    meta['items']={'diamond':2}
    metadata.write_text(json.dumps(meta))
    check_run(directory,metadata)
    meta['items']={'iron_ingot':2}
    metadata.write_text(json.dumps(meta))
    reject(directory,metadata,'equal grand total with different items')
    # Each new conservation audit must be required even when all the other audits pass.
    for workload in NOVEL:
        script, meta = generate(workload, seconds=40, warmup=0, scale=0.001)
        if workload=='jockeys':
            meta['entity_step_metric']='committed-entities-and-passengers-v2'
        checks = '\n'.join(
            f'[12:00:41] [Server thread/INFO]: [Server] check {name}\n'
            '[12:00:41] [Server thread/INFO]: Test passed'
            for name in meta['checks']
        ) + '\n'
        def novel_log(log):
            start = log.index('[12:00:41]')
            end = log.index('[12:00:42]')
            return log[:start] + checks + log[end:]
        fixture(directory,metadata,changed_log=novel_log)
        metadata.write_text(json.dumps(meta))
        check_run(directory,metadata)
        valid = (directory/'out.log').read_text()
        for name in meta['checks']:
            marker = f'[Server] check {name}\n[12:00:41] [Server thread/INFO]: Test passed'
            (directory/'out.log').write_text(valid.replace(marker, marker.replace('Test passed','Test failed')))
            reject(directory,metadata,f'{workload}: failed {name}')
        (directory/'out.log').write_text(valid.replace(checks, ''))
        reject(directory,metadata,f'{workload}: omitted all conservation audits')
        if workload=='jockeys':
            (directory/'out.log').write_text(valid)
            meta.pop('entity_step_metric')
            metadata.write_text(json.dumps(meta))
            reject(directory,metadata,'jockeys: missing passenger counter version')
(ROOT/'build').mkdir(exist_ok=True)
with tempfile.TemporaryDirectory(prefix='stress-metric-before-',suffix='-jockeys',dir=ROOT/'build') as before, \
     tempfile.TemporaryDirectory(prefix='stress-metric-after-',suffix='-jockeys',dir=ROOT/'build') as after:
    directories = [Path(before),Path(after)]
    labels = [path.name[len('stress-'):-len('-jockeys')] for path in directories]
    result = {'checks':'PASS','configuration':{'workload':'jockeys','entities':4},
              'entity_steps_per_second':20,'drift':0}
    command = [sys.executable,str(ROOT/'tools/stress/extended.py'),'compare',*labels,'--workloads','jockeys']
    for metric in (None,'legacy-entity-events-v1','committed-entities-and-passengers-v2'):
        if metric is not None:
            result['configuration']['entity_step_metric']=metric
        for path in directories:
            (path/'extended-result.json').write_text(json.dumps(result))
        comparison = subprocess.run(command,cwd=ROOT,capture_output=True,text=True)
        assert (comparison.returncode==0)==(metric=='committed-entities-and-passengers-v2'), comparison
print('Extended harness checks passed: completed work, original/novel audits, per-item conservation, passenger counter versions, stale results, errors, exit/watchdog failures and startup/measurement stalls')
