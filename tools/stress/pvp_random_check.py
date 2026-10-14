#!/usr/bin/env python3
"""Negative replays of a real free-for-all audit, including missing attack outcomes."""
import json
from pathlib import Path
import re
import shutil
import tempfile
import unittest
from pvp_random import ROOT, check, tick_rate

class RandomAuditCheck(unittest.TestCase):
    def setUp(self):
        source=ROOT/'build/stress-random-verified-tickless-64'
        if not source.exists():self.skipTest('run the 64-player random fixture first')
        temp=tempfile.TemporaryDirectory();self.addCleanup(temp.cleanup);self.directory=Path(temp.name)
        for name in ('out.log','bots.log','exit-code.txt','random-manifest.json','process-cpu.jsonl','arena-measure','arena-end'):
            shutil.copy2(source/name,self.directory/name)

    def mutate(self,name,operation):
        p=self.directory/name;p.write_text(operation(p.read_text()))

    def rejected(self):
        with self.assertRaises(ValueError):check(self.directory)

    def test_valid(self):self.assertEqual(check(self.directory)['items_conserved'],554)
    def test_missing_holder(self):
        self.mutate('out.log',lambda s:s.replace('[Server] holder Bot0','[Server] holder Wrong0',1));self.rejected()
    def test_modified_gear(self):
        self.mutate('out.log',lambda s:s.replace('random_arena: 1','random_arena: 2',1));self.rejected()
    def test_not_empty_start(self):
        self.mutate('out.log',lambda s:s.replace('Inventory: []','Inventory: [{id:"minecraft:diamond",count:1}]',1));self.rejected()
    def test_missing_ground(self):
        def remove(s):
            start=s.index('[Server] ground begin');end=s.index('[Server] ground end',start)
            segment=s[start:end];line=next(l for l in segment.splitlines() if 'entity data:' in l and 'random_arena:' in l)
            return s[:start]+segment.replace(line,'',1)+s[end:]
        self.mutate('out.log',remove);self.rejected()
    def test_bad_block_count(self):
        self.mutate('out.log',lambda s:re.sub(r'#placed has \d+ \[random_blocks\]','#placed has 9999 [random_blocks]',s));self.rejected()
    def test_missing_attack(self):
        self.mutate('out.log',lambda s:re.sub(r'ATTACK_AUDIT Bot\d+ \d+ \d+ \S+','REMOVED',s,count=1));self.rejected()
    def test_unexplained_hit(self):
        self.mutate('out.log',lambda s:s.replace('damage_cooldown','unexplained',1));self.rejected()
    def test_unknown_reason(self):
        self.mutate('out.log',lambda s:s.replace('damage_cooldown','made_up_reason',1));self.rejected()
    def test_duplicate_attack(self):
        self.mutate('out.log',lambda s:s+'\n'+next(l for l in s.splitlines() if 'ATTACK_AUDIT ' in l));self.rejected()
    def test_missing_completion(self):
        self.mutate('out.log',lambda s:s.replace('RANDOM_SOUNDNESS_COMPLETE','INCOMPLETE'));self.rejected()
    def test_no_cpu(self):
        self.mutate('process-cpu.jsonl',lambda s:'');self.rejected()
    def test_duplicate_client(self):
        self.mutate('bots.log',lambda s:s+'\n'+next(l for l in s.splitlines() if 'ARENA_RESULT ' in l));self.rejected()
    def test_shutdown_closed_socket(self):
        self.mutate('out.log',lambda s:s+'\n[00:00:00] [Server thread/INFO]: Stopping server\nio.netty.channel.StacklessClosedChannelException\n')
        self.assertEqual(check(self.directory)['shutdown_closed_channel_warnings'],1)
    def test_closed_socket_before_shutdown(self):
        self.mutate('out.log',lambda s:s.replace('RANDOM_SOUNDNESS_COMPLETE','RANDOM_SOUNDNESS_COMPLETE\nio.netty.channel.StacklessClosedChannelException',1));self.rejected()
    def test_other_shutdown_exception(self):
        self.mutate('out.log',lambda s:s+'\n[00:00:00] [Server thread/INFO]: Stopping server\njava.lang.ArrayIndexOutOfBoundsException\n');self.rejected()
    def test_fake_stop_chat(self):
        self.mutate('out.log',lambda s:s.replace('RANDOM_SOUNDNESS_COMPLETE','RANDOM_SOUNDNESS_COMPLETE\n[00:00:00] [Server thread/INFO]: [Server] Stopping server\nio.netty.channel.StacklessClosedChannelException',1));self.rejected()
    def test_closed_socket_without_stop(self):
        self.mutate('out.log',lambda s:re.sub(r'^\[\d\d:\d\d:\d\d\] \[Server thread/INFO\]: Stopping server$', '',s,flags=re.M)+'\nio.netty.channel.StacklessClosedChannelException\n');self.rejected()
    def test_downgrade(self):
        self.mutate('bots.log',lambda s:s+'\nARMOR_DOWNGRADE Bot0 diamond_helmet leather_helmet\n');self.rejected()

class PlayerStepClockCheck(unittest.TestCase):
    def log(self, second_clock='6250000000', first_clock='1000000000'):
        a='' if first_clock is None else ', stats time ns '+first_clock
        b='' if second_clock is None else ', stats time ns '+second_clock
        return ('[00:00:09] [Server thread/INFO]: [Server] BENCH_START\n'
                '[00:00:15] [Server thread/INFO]: World workers: online players 128, completed player steps 100'+a+'\n'
                '[00:00:20] [Server thread/INFO]: World workers: online players 128, completed player steps 13540'+b+'\n'
                '[00:00:21] [Server thread/INFO]: [Server] BENCH_END\n')
    def test_monotonic_ignores_rounded_seconds(self):
        r=tick_rate(self.log(),128)
        self.assertEqual(r['player_tps'],20)
        self.assertEqual(r['player_tps_clock'],'monotonic_ns')
    def test_legacy_recording(self):
        self.assertEqual(tick_rate(self.log(None,None),128)['player_tps_clock'],'log_seconds')
    def test_mixed_clocks_rejected(self):
        with self.assertRaises(ValueError):tick_rate(self.log(None),128)
    def test_nonpositive_interval_rejected(self):
        with self.assertRaises(ValueError):tick_rate(self.log('1000000000'),128)
    def test_signed_monotonic_clock(self):
        self.assertEqual(tick_rate(self.log('-1000000000','-6250000000'),128)['player_tps'],20)

if __name__=='__main__':unittest.main()
