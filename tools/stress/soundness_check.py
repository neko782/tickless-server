#!/usr/bin/env python3
"""Negative controls for the extended soundness audit, using synthetic logs."""
import copy
import json
import tempfile
from pathlib import Path

from gen_soundness import WORKLOADS, generate, signature, stack
from soundness import Snbt, check_run


def main():
    value = stack('diamond', 2, 'large', 0)
    meta = {'workload': 'components', 'checks': ['population', 'progress'], 'audits': {
        'final': {'items': {signature(value): 2}, 'blocks': 1, 'holders': ['15, 200, -17']}
    }}
    prefix = '[12:00:00] [Server thread/INFO]: '
    log = '\n'.join(prefix + line for line in (
        '[Server] check population', 'Test passed. Count: 1',
        '[Server] check progress', 'Test passed',
        '[Server] inventory final begin',
        '15, 200, -17 has the following block data: ' + json.dumps([value]),
        '[Server] inventory final end',
        '[Server] SOUNDNESS_COMPLETE components', 'Stopping server',
    )) + '\n'
    with tempfile.TemporaryDirectory() as tmp:
        directory = Path(tmp)
        manifest = directory / 'manifest.json'

        def fixture(changed_log=log, changed_meta=meta):
            manifest.write_text(json.dumps(changed_meta))
            (directory / 'out.log').write_text(changed_log)
            (directory / 'exit-code.txt').write_text('0\n')
            (directory / 'failure.txt').unlink(missing_ok=True)

        def reject(description):
            # A prior success must not survive a failed recheck.
            (directory / 'soundness-result.json').write_text('{"status":"PASS"}')
            try:
                check_run(directory, manifest)
            except ValueError:
                assert not (directory / 'soundness-result.json').exists(), description
                return
            raise AssertionError('Accepted ' + description)

        fixture()
        assert check_run(directory, manifest)['status'] == 'PASS'
        cases = (
            ('missing check', log.replace('[Server] check progress', '[Server] check other')),
            ('missing check result', log.replace(prefix + 'Test passed\n', '')),
            ('failed required check', log.replace('Test passed. Count: 1', 'Test failed')),
            ('missing completion', log.replace('SOUNDNESS_COMPLETE', 'incomplete')),
            ('duplicate completion', log.replace('[Server] SOUNDNESS_COMPLETE components', '[Server] SOUNDNESS_COMPLETE components\n' + prefix + '[Server] SOUNDNESS_COMPLETE components')),
            ('completion before checks', prefix + '[Server] SOUNDNESS_COMPLETE components\n' + log.replace(prefix + '[Server] SOUNDNESS_COMPLETE components\n', '')),
            ('missing shutdown', log.replace('Stopping server', 'not stopped')),
            ('worker failure counter', log + prefix + 'World workers: failures 1\n'),
            ('logged exception', log + prefix + 'Exception in world event\n'),
            ('command syntax error', log + prefix + 'bad command<--[HERE]\n'),
            ('world lock stall', log + prefix + 'has waited 10s for a world lock\n'),
            ('outside wait graph', log + prefix + 'World wait outside the lock graph\n'),
            ('missing begin marker', log.replace('[Server] inventory final begin', 'omitted')),
            ('missing end marker', log.replace('[Server] inventory final end', 'omitted')),
            ('reversed markers', log.replace('final begin', 'final TEMP').replace('final end', 'final begin').replace('final TEMP', 'final end')),
            ('missing dump', log.replace(prefix + '15, 200, -17 has the following block data: ' + json.dumps([value]) + '\n', '')),
            ('wrong container', log.replace('15, 200, -17 has', '16, 200, -17 has')),
            ('malformed dump', log.replace(json.dumps([value]), '[{')),
            ('nested duplicate inventory', log.replace('[Server] inventory final end', '[Server] inventory final begin')),
            ('lost components', log.replace(json.dumps([value]), json.dumps([{'Slot': 0, 'id': value['id'], 'count': 2}]))),
            ('equal total with different components', log.replace('large', 'small')),
            ('NBT numeric type mutation', log.replace(json.dumps([value]), json.dumps([value]).replace('[1, 2, 3]', '[1b, 2, 3]'))),
            ('NBT array/list mutation', log.replace(json.dumps([value]), json.dumps([value]).replace('[1, 2, 3]', '[I;1, 2, 3]'))),
            ('lost item', log.replace('"count": 2', '"count": 1')),
            ('duplicated item', log.replace('"count": 2', '"count": 3')),
            ('overfull stack', log.replace('"count": 2', '"count": 65')),
            ('zero stack', log.replace('"count": 2', '"count": 0')),
            ('duplicate slot', log.replace(json.dumps([value]), json.dumps([value, value]))),
            ('unexpected dropped stack', log.replace(prefix + '[Server] inventory final end', prefix + 'Item has the following entity data: ' + json.dumps(value) + '\n' + prefix + '[Server] inventory final end')),
        )
        for description, changed in cases:
            fixture(changed)
            reject(description)
        for status in ('1', ''):
            fixture()
            (directory / 'exit-code.txt').write_text(status)
            reject('unsuccessful/empty exit status')
        fixture()
        (directory / 'exit-code.txt').unlink()
        reject('missing exit status')
        fixture()
        (directory / 'failure.txt').write_text('poll deadline expired')
        reject('watchdog failure with successful-looking log')
        fixture()
        manifest.write_text('{')
        reject('malformed manifest with a stale success result')

        # A stack split into two different slots retains the same exact identity.
        halves = [dict(value, count=1, Slot=0), dict(value, count=1, Slot=1)]
        fixture(log.replace(json.dumps([value]), json.dumps(halves)))
        check_run(directory, manifest)
        # Default count=1 is legitimate for singleton stacks.
        singleton = copy.deepcopy(value)
        singleton.pop('count')
        singleton_meta = copy.deepcopy(meta)
        singleton_meta['audits']['final']['items'][signature(value)] = 1
        fixture(log.replace(json.dumps([value]), json.dumps([singleton])), singleton_meta)
        check_run(directory, manifest)

    assert Snbt('{values:[I;1,2,3],byte:1b,long:2L,flag:true,text:\'quoted " }\'}').parse() == {
        'values': [1, 2, 3], 'byte': 1, 'long': 2, 'flag': True, 'text': 'quoted " }'
    }
    for name in WORKLOADS:
        script, generated = generate(name, seconds=1, rounds=1)
        assert generated['checks'] and len(generated['checks']) == len(set(generated['checks']))
        assert script.count('SOUNDNESS_COMPLETE ' + name) == 1
        assert script == generate(name, seconds=1, rounds=1)[0]
    print(f'Soundness harness passed: {len(cases)+5} negative controls, nested/escaped components, split/singleton stacks and five deterministic manifests')


if __name__ == '__main__':
    main()
