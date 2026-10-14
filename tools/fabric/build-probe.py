#!/usr/bin/env python3
"""Compile the executable parallel probe against the actual installed game and pinned API."""
import os
import subprocess
import sys
import shutil
import zipfile
from pathlib import Path

root = Path(__file__).resolve().parents[2]
install = root / 'build/install/fabric'
mods = root / 'build/fabric-test-mods'
work = root / 'build/fabric-probe'
api = work / 'api'
classes = work / 'classes'
api.mkdir(parents=True, exist_ok=True)
if classes.exists(): shutil.rmtree(classes)
classes.mkdir(parents=True)
with zipfile.ZipFile(mods / 'fabric-api-0.162.0+26.3.jar') as jar:
    for name in jar.namelist():
        if name.endswith('.jar'):
            (api / Path(name).name).write_bytes(jar.read(name))
java_home = os.environ.get('JAVA_HOME')
if java_home:
    javac = str(Path(java_home) / 'bin/javac')
else:
    cache = Path(os.environ.get('GRADLE_USER_HOME', Path.home()/'.gradle')) / 'jdks'
    candidates = list(cache.glob('*/bin/javac'))
    javac = str(candidates[0]) if candidates else 'javac'
classpath = os.pathsep.join(str(f) for p in [install / 'lib', api] for f in p.glob('*.jar'))
source = root / 'tools/fabric/probe'
subprocess.run([javac, '-proc:none', '-cp', classpath, '-d', str(classes), *map(str, (source/'src').rglob('*.java'))], check=True)
with zipfile.ZipFile(mods / 'tickless-probe.jar', 'w', zipfile.ZIP_DEFLATED) as out:
    for directory in [classes, source/'resources']:
        for f in directory.rglob('*'):
            if f.is_file(): out.write(f, str(f.relative_to(directory)))
print('Built executable Tickless parallel probe: ' + str(mods / 'tickless-probe.jar'))

for kind in ['optional', 'overwrite']:
    source = root / 'tools/fabric/conflicts' / kind
    output = work / ('conflict-' + kind)
    if output.exists(): shutil.rmtree(output)
    output.mkdir(parents=True)
    subprocess.run([javac, '-proc:none', '-cp', classpath, '-d', str(output), *map(str, (source/'src').rglob('*.java'))], check=True)
    destination = root / 'build/fabric-conflicts' / kind
    destination.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(destination / 'conflict.jar', 'w', zipfile.ZIP_DEFLATED) as out:
        for directory in [output, source/'resources']:
            for f in directory.rglob('*'):
                if f.is_file(): out.write(f, str(f.relative_to(directory)))
print('Built executable optional-injection and overwrite conflict fixtures')
