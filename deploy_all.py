# -*- coding: utf-8 -*-
"""Deploy the fixed jars to all local instances (single UAC elevation).

1.21.1 NeoForge instance  : patched/promaid-1.1.0-neoforge-1.21.1.jar
1.20.1 Forge 47.4.21      : patched/promaid-1.1.1.jar   (user's main modpack)
1.20.1 Forge 47.4.23      : patched/promaid-1.1.1.jar   (test instance)
Old jars are backed up under patched/backup_old/ first.
"""
import os
import shutil
import subprocess
import sys
import tempfile

sys.stdout.reconfigure(encoding='utf-8')
BASE = os.path.dirname(os.path.abspath(__file__))

JOBS = [
    (os.path.join(BASE, 'patched', 'promaid-1.1.0-neoforge-1.21.1.jar'),
     r'D:\.minecraft\versions\1.21.1-NeoForge_21.1.250\mods\promaid-1.1.0-neoforge-1.21.1.jar'),
    (os.path.join(BASE, 'patched', 'promaid-1.1.1.jar'),
     r'D:\.minecraft\versions\1.20.1-Forge_47.4.21\mods\promaid-1.1.1.jar'),
    (os.path.join(BASE, 'patched', 'promaid-1.1.1.jar'),
     r'C:\Users\Sketch\mc_server_test\pack1201\mods\promaid-1.1.1.jar'),
]

# refuse while any java/javaw runs (game or server open)
probe = subprocess.run(['powershell', '-NoProfile', '-Command',
                        "(Get-Process -ErrorAction SilentlyContinue | "
                        "Where-Object { $_.ProcessName -match '^(java|javaw)$' }).Count"],
                       capture_output=True, text=True)
try:
    running = int((probe.stdout or '0').strip() or '0')
except Exception:
    running = 0
if running > 0:
    print('DEPLOY REFUSED: %d 个 java/javaw 进程在运行（游戏或服务器）——先全部退出。' % running)
    sys.exit(5)

for src, dst in JOBS:
    if not os.path.isfile(src):
        print('FATAL: jar 不存在:', src)
        sys.exit(6)

# backup old jars
backup = os.path.join(BASE, 'patched', 'backup_old')
os.makedirs(backup, exist_ok=True)
for src, dst in JOBS:
    if os.path.exists(dst):
        tag = os.path.basename(os.path.dirname(os.path.dirname(dst)))  # instance dir name
        bdst = os.path.join(backup, tag + '__' + os.path.basename(dst))
        try:
            shutil.copyfile(dst, bdst)
            print('backup:', bdst, os.path.getsize(bdst))
        except Exception as e:
            print('backup failed (continuing):', e)

# stage to temp (ASCII paths, elevation copies from there)
staged = []
for src, dst in JOBS:
    tmp = os.path.join(tempfile.gettempdir(), 'promaid_deploy_' + os.path.basename(dst))
    shutil.copyfile(src, tmp)
    staged.append((tmp, dst, os.path.getsize(src)))
    print('staged:', tmp, os.path.getsize(tmp))

inner_parts = []
for tmp, dst, size in staged:
    inner_parts.append("Copy-Item -LiteralPath '%s' -Destination '%s' -Force" % (tmp, dst))
inner = '; '.join(inner_parts)
cmd = ['powershell', '-NoProfile', '-Command',
       "Start-Process powershell -Verb RunAs -Wait -ArgumentList '-NoProfile','-Command',"
       "'__INNER__'".replace('__INNER__', inner.replace("'", "''"))]
r = subprocess.run(cmd, capture_output=True, text=True)
print('elevated rc:', r.returncode, (r.stdout or '').strip()[:200], (r.stderr or '').strip()[:200])

ok = True
for tmp, dst, size in staged:
    if os.path.exists(dst):
        dsz = os.path.getsize(dst)
        match = dsz == size
        ok = ok and match
        print('deployed: %s  %d  match=%s' % (dst, dsz, match))
    else:
        ok = False
        print('MISSING after deploy:', dst)
sys.exit(0 if ok else 3)