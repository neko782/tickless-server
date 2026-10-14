#!/usr/bin/env python3
"""Sample server and bot process CPU separately (100% = one logical CPU).

Uses host /proc CPU counters, not Docker's combined server/client percentage.
Output is JSONL with epoch timestamps, interval duration, CPU percentages and RSS.
"""
import argparse
import json
import os
import subprocess
import time
from pathlib import Path


def counters(pid):
    parts = Path(f'/proc/{pid}/stat').read_text().rsplit(') ', 1)[1].split()
    return int(parts[11]) + int(parts[12]), int(parts[21]) * os.sysconf('SC_PAGE_SIZE')


def sample(container, output):
    server_pid = int(subprocess.check_output(['docker', 'inspect', '-f', '{{.State.Pid}}', container], text=True))
    hz = os.sysconf('SC_CLK_TCK')
    previous = {}
    previous_time = time.monotonic()
    bot_pid = None
    with Path(output).open('w', buffering=1) as stream:
        while Path(f'/proc/{server_pid}').exists():
            now = time.monotonic()
            if bot_pid is None:
                proc = subprocess.run(['docker', 'top', container, '-eo', 'pid,args'], capture_output=True, text=True)
                for line in proc.stdout.splitlines():
                    if ' BotClient ' in line and 'javac' not in line:
                        bot_pid = int(line.split()[0])
                        break
            row = {'time_ns': time.time_ns(), 'interval_seconds': now - previous_time}
            for name, pid in (('server', server_pid), ('bots', bot_pid)):
                if pid is None: continue
                try:
                    ticks, rss = counters(pid)
                except (FileNotFoundError, ProcessLookupError):
                    continue
                if name in previous and row['interval_seconds'] > 0:
                    row[name + '_cpu_percent'] = (ticks - previous[name]) / hz / row['interval_seconds'] * 100
                row[name + '_rss_bytes'] = rss
                previous[name] = ticks
            stream.write(json.dumps(row) + '\n')
            previous_time = now
            time.sleep(1)


if __name__ == '__main__':
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('container')
    p.add_argument('output')
    args = p.parse_args()
    sample(args.container, args.output)
