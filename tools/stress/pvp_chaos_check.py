#!/usr/bin/env python3
"""Replay a real small chaos run with deliberate audit corruption (no server needed)."""
import json
import shutil
import tempfile
import unittest
from pathlib import Path
from pvp_chaos import check, ROOT


class ChaosAuditCheck(unittest.TestCase):
    def setUp(self):
        source=ROOT/'build/stress-chaos-smoke3-vanilla-4'
        if not source.exists(): self.skipTest('run the four-player chaos smoke fixture first')
        temporary=tempfile.TemporaryDirectory();self.addCleanup(temporary.cleanup)
        self.directory=Path(temporary.name)
        for name in ('out.log','bots.log','exit-code.txt','chaos-manifest.json','process-cpu.jsonl','arena-measure','arena-end'):
            shutil.copy2(source/name,self.directory/name)

    def mutate(self,name,operation):
        p=self.directory/name;p.write_text(operation(p.read_text()))

    def test_valid(self): self.assertEqual(check(self.directory)['gear_items_conserved'],32)

    def test_missing_holder(self):
        self.mutate('out.log',lambda s:s.replace('[Server] player Bot0','[Server] player Wrong0',1))
        with self.assertRaises(ValueError): check(self.directory)

    def test_gear_loss(self):
        self.mutate('out.log',lambda s:s.replace('chaos_gear: 1','chaos_gear: 2',1))
        with self.assertRaises(ValueError): check(self.directory)

    def test_missing_completion(self):
        self.mutate('out.log',lambda s:s.replace('CHAOS_SOUNDNESS_COMPLETE','INCOMPLETE'))
        with self.assertRaises(ValueError): check(self.directory)

    def test_bad_cpu_sampling(self):
        self.mutate('process-cpu.jsonl',lambda s:'')
        with self.assertRaises(ValueError): check(self.directory)

    def test_duplicate_bot(self):
        self.mutate('bots.log',lambda s:s+'\n'+next(line for line in s.splitlines() if 'ARENA_RESULT ' in line))
        with self.assertRaises(ValueError): check(self.directory)

    def test_harness_failure(self):
        (self.directory/'failure.txt').write_text('timeout')
        with self.assertRaises(ValueError): check(self.directory)


if __name__=='__main__': unittest.main()
