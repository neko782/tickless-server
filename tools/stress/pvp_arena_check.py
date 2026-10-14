#!/usr/bin/env python3
"""Negative controls for the arena auditor; no Minecraft process needed."""
import copy
import json
import tempfile
import unittest
from pathlib import Path
from pvp_arena import check


class ArenaAuditCheck(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.directory = Path(self.temp.name)
        self.rows = [dict(name=f'Bot{i}', attacks=10, places=20, breaks=20, updates=40,
                          pending_acks=0, hit_ns=[1000] * 10, ack_ns=[1000] * 40,
                          update_ns=[1000] * 40) for i in range(2)]
        lines = ['[00:00:00] [Server] BENCH_START', '[00:00:00] The game time is 100 tick(s)',
                 '[00:00:10] [Server] BENCH_END', '[00:00:10] The game time is 300 tick(s)']
        for i in range(2):
            for kind in ('damage', 'placement', 'alive'):
                lines += [f'[00:00:11] [Server] check {kind}-Bot{i}', '[00:00:11] Test passed']
            lines += [f'[00:00:11] [Server] inventory Bot{i}',
                      f'[00:00:11] Bot{i} has 26 [arena_total]',
                      f'[00:00:11] Bot{i} has 20 [arena_build]',
                      f'[00:00:11] Bot{i} has the following entity data: [{{id: "minecraft:slime_block", count: 102, Slot: 0b}}]']
        lines += ['[00:00:12] [Server] ARENA_SOUNDNESS_COMPLETE', '[00:00:12] Stopping server']
        self.log = '\n'.join(lines)
        (self.directory / 'exit-code.txt').write_text('0')

    def audit(self, rows=None, log=None, before="", after=""):
        (self.directory / 'out.log').write_text(self.log if log is None else log)
        (self.directory / 'bots.log').write_text('\n'.join('ARENA_RESULT ' + json.dumps(row) for row in (self.rows if rows is None else rows)) + before + '\nARENA_COMPLETE seconds=10.0\n' + after)
        return check(self.directory, 2)

    def test_valid(self):
        result = self.audit()
        self.assertEqual(result['simulation_rate'], 20)
        self.assertEqual(result['inventory_audits'], 2)

    def test_missing_or_failed_audit(self):
        for log in (self.log.replace('check alive-Bot1', 'check wrong-Bot1'),
                    self.log.replace('Test passed', 'Test failed', 1),
                    self.log.replace('ARENA_SOUNDNESS_COMPLETE', 'INCOMPLETE')):
            with self.subTest(log=log), self.assertRaises(ValueError): self.audit(log=log)

    def test_duplication_or_loss(self):
        for remaining in (101, 103):
            with self.subTest(remaining=remaining), self.assertRaises(ValueError):
                self.audit(log=self.log.replace('count: 102', f'count: {remaining}', 1))

    def test_missing_duplicate_or_rejected_feedback(self):
        for key, value in (('pending_acks', 1), ('hit_ns', []), ('hit_ns', [1000] * 11),
                           ('ack_ns', [1000] * 39), ('update_ns', [1000] * 30)):
            rows = copy.deepcopy(self.rows)
            rows[0][key] = value
            with self.subTest(key=key), self.assertRaises(ValueError): self.audit(rows=rows)
        with self.assertRaises(ValueError):
            self.audit(log=self.log.replace('20 [arena_build]', '1 [arena_build]', 1))

    def test_missing_duplicate_bots(self):
        for rows in (self.rows[:1], self.rows + self.rows[:1]):
            with self.assertRaises(ValueError): self.audit(rows=rows)

    def test_shutdown_disconnect_is_allowed(self):
        self.audit(after='BOT Bot0 disconnected: Server closed\n')
        with self.assertRaises(ValueError):
            self.audit(before='\nBOT Bot0 disconnected: unexpected\n')

    def test_harness_failure(self):
        (self.directory / 'failure.txt').write_text('timeout')
        with self.assertRaises(ValueError): self.audit()

    def test_player_counter_excludes_nonplayer_population(self):
        stats = '\n'.join(f'[00:00:{t:02}] World workers: 10 events/s, chunk step rate 20.00/s, entity step rate 20.00/s, ticking entities 3, completed entity steps 999, online players 2, completed player steps {n}'
                          for t, n in ((6, 240), (9, 360)))
        result = self.audit(log=self.log.replace('[00:00:10] [Server] BENCH_END', stats + '\n[00:00:10] [Server] BENCH_END'))
        self.assertEqual(result['completed_player_tps'], 20)

    def test_wrong_completed_step_population(self):
        stats = '\n'.join(f'[00:00:{t:02}] World workers: 10 events/s, chunk step rate 20.00/s, entity step rate 20.00/s, ticking entities 3, completed entity steps {n}, online players 3, completed player steps {n}'
                          for t, n in ((6, 240), (9, 360)))
        with self.assertRaises(ValueError):
            self.audit(log=self.log.replace('[00:00:10] [Server] BENCH_END', stats + '\n[00:00:10] [Server] BENCH_END'))


if __name__ == '__main__': unittest.main()
