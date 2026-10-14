#!/usr/bin/env python3
"""Launch an isolated vanilla 26.3 client using cached Prism libraries/assets.
No existing instance, account or options are modified; username/token are local offline fixtures.
python3 tools/stress/arena_viewer.py HOST:PORT CAPTURE_DIR
Touch CAPTURE_DIR/capture-start after the observer is positioned to record 30s at up to 8 fps.
"""
import argparse
import json
from pathlib import Path
import subprocess
import uuid
import hashlib
import urllib.request
from pvp_arena import ROOT


def classpath(cache):
    result=[]
    for resource in ('net.minecraft/26.3.json','org.lwjgl3/3.4.3.json'):
        meta=json.loads((cache/'meta'/resource).read_text())
        for lib in meta['libraries']:
            allowed=True
            if 'rules' in lib:
                allowed=False
                for rule in lib['rules']:
                    os_name=rule.get('os',{}).get('name')
                    if os_name is None or os_name=='linux': allowed=rule['action']=='allow'
            if not allowed: continue
            # Cached coordinates can rename native modules; the artifact URL is canonical.
            url=lib.get('downloads',{}).get('artifact',{}).get('url')
            if not url: continue
            relative=url.split('libraries.minecraft.net/',1)[-1]
            path=cache/'libraries'/relative
            if not path.exists():
                path=ROOT/'build/arena-viewer-libraries'/relative
                path.parent.mkdir(parents=True,exist_ok=True)
                if not path.exists():
                    with urllib.request.urlopen(url) as response: path.write_bytes(response.read())
                expected=lib['downloads']['artifact']['sha1']
                if hashlib.sha1(path.read_bytes()).hexdigest()!=expected: raise ValueError('client library digest mismatch')
            result.append(str(path))
    result.append(str(cache/'libraries/com/mojang/minecraft/26.3/minecraft-26.3-client.jar'))
    return ':'.join(dict.fromkeys(result))


if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('server');p.add_argument('capture_dir',type=Path)
    p.add_argument('--cache',type=Path,default=Path.home()/'.local/share/PrismLauncher')
    a=p.parse_args();a.capture_dir=a.capture_dir.resolve();a.capture_dir.mkdir(parents=True,exist_ok=True)
    game=a.capture_dir/'client';game.mkdir(exist_ok=True)
    (game/'options.txt').write_text('version:4842\nrenderDistance:8\nsimulationDistance:5\nmaxFps:30\nfov:0.25\npauseOnLostFocus:false\ntutorialStep:none\nonboardAccessibility:true\nsoundCategory_master:0.0\nguiScale:2\n')
    cp=classpath(a.cache)
    classes=ROOT/'build/arena-viewer-classes';classes.mkdir(exist_ok=True)
    java=a.cache/'java/java-runtime-epsilon/bin/java'
    javac=a.cache/'java/java-runtime-epsilon/bin/javac'
    subprocess.run([str(javac),'-cp',cp,'-d',str(classes),str(ROOT/'tools/ArenaViewer.java')],check=True)
    args=[str(java),'-Xmx3g','-XX:+UseG1GC',f'-Darena.captureDir={a.capture_dir}',f'-Darena.server={a.server}',
          '-cp',str(classes)+':'+cp,'ArenaViewer','--username','ArenaCam','--version','26.3',
          '--gameDir',str(game),'--assetsDir',str(a.cache/'assets'),'--assetIndex','34',
          '--uuid',str(uuid.UUID(bytes=__import__('hashlib').md5(b'OfflinePlayer:ArenaCam').digest(),version=3)),
          '--accessToken','0','--versionType','release','--width','1280','--height','720',
          '--quickPlayMultiplayer',a.server]
    print('Launching isolated real Minecraft 26.3 spectator client',flush=True)
    subprocess.run(args,check=True)
