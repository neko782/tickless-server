#!/usr/bin/env python3
"""Deterministic real-server exercise: parallel callbacks and ordinary-mod main-thread routing."""
from pathlib import Path
import sys

lines = ['gamerule spawn_mobs false', 'gamerule max_entity_cramming 0',
         'forceload add -48 -48 47 47',
         'poll 30 execute if loaded -48 0 -48 if loaded 47 0 47',
         'tick freeze', 'fill -40 199 -40 40 199 40 minecraft:stone']
for i in range(64):
    x, z = -28+(i%8)*8, -28+(i//8)*8
    lines.append(f'summon minecraft:cow {x} 200 {z} {{PersistenceRequired:1b,Tags:["tickless_probe"]}}')
for i in range(16):
    x, z = -38+(i%4)*8, -38+(i//4)*8
    lines.append(f'summon minecraft:cow {x} 200 {z} {{PersistenceRequired:1b,Tags:["tickless_legacy_probe"]}}')
lines += ['tick unfreeze', 'sleep 4',
          'lp group default permission set tickless.integration true', 'lp group default permission info',
          'chunky world minecraft:overworld', 'chunky center 1024 1024', 'chunky radius 16',
          'chunky start', 'sleep 5', 'chunky progress',
          'spark tps', 'forceload add 8192 8192 8223 8207',
          'poll 30 execute if loaded 8192 0 8192 if loaded 8223 0 8207',
          'forceload remove 8192 8192 8223 8207',
          'poll 30 execute unless loaded 8192 0 8192 unless loaded 8223 0 8207',
          'ticklessprobe verify', 'say FABRIC_SMOKE_COMPLETE', 'sleep 1']
Path(sys.argv[1]).write_text('\n'.join(lines)+'\n')
