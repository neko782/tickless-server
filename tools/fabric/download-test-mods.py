#!/usr/bin/env python3
"""Download pinned, unmodified 26.3 Fabric test mods from Modrinth, verifying SHA-512."""
import hashlib
import json
import sys
import urllib.request
from pathlib import Path

out = Path(sys.argv[1] if len(sys.argv) > 1 else 'build/fabric-test-mods')
out.mkdir(parents=True, exist_ok=True)
for mod in json.loads(Path(__file__).with_name('test-mods.lock.json').read_text()):
    target = out / mod['filename']
    if not target.exists() or hashlib.sha512(target.read_bytes()).hexdigest() != mod['sha512']:
        request = urllib.request.Request(mod['url'], headers={'User-Agent': 'Tickless/Fabric-compat-verification'})
        data = urllib.request.urlopen(request, timeout=30).read()
        if hashlib.sha512(data).hexdigest() != mod['sha512']:
            raise SystemExit('Download checksum mismatch: ' + mod['project'])
        target.write_bytes(data)
    print(mod['project'] + ' ' + mod['version'] + ': verified')
