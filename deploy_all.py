# -*- coding: utf-8 -*-
"""Deploy the fixed jars to all local instances.

1.21.1 NeoForge 21.1.250  : patched/promaid-1.3.2-neoforge-1.21.1.jar
1.20.1 Forge 47.4.21      : patched/promaid-1.3.2-forge-1.20.1.jar   (user's main modpack)
1.20.1 server pack1201    : patched/promaid-1.3.2-forge-1.20.1.jar
Old jars are backed up under patched/backup_old/ first.

v1.3.0 实测六百五十四（扫帚模式）+ 实测六百五十三（烧制卡死）+ v1.3.1 实测六百五十五（扫帚实测六条）
+ v1.3.2 实测六百五十六（烧制清单网格乱列 / 骑扫帚反复被拉回 / 移动照搬原版 / 起飞 1 格与接敌升 8 格 / 跟随同款）：
先试【直接复制】（不需要 UAC）；只有直接复制被拒（权限不够）时
才退回原来的提权 PowerShell 通道。旧版是无条件提权 → 会弹 UAC 卡住等人点。
"""
import os
import shutil
import subprocess
import sys
import tempfile

sys.stdout.reconfigure(encoding='utf-8')
BASE = os.path.dirname(os.path.abspath(__file__))

JOBS = [
    (os.path.join(BASE, 'patched', 'promaid-1.3.2-neoforge-1.21.1.jar'),
     r'D:\.minecraft\versions\1.21.1-NeoForge_21.1.250\mods\promaid-1.3.2-neoforge-1.21.1.jar'),
    (os.path.join(BASE, 'patched', 'promaid-1.3.2-forge-1.20.1.jar'),
     r'D:\.minecraft\versions\1.20.1-Forge_47.4.21\mods\promaid-1.3.2-forge-1.20.1.jar'),
    (os.path.join(BASE, 'patched', 'promaid-1.3.2-forge-1.20.1.jar'),
     r'C:\Users\Sketch\mc_server_test\pack1201\mods\promaid-1.3.2-forge-1.20.1.jar'),
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


def drop_other_versions(dst):
    """同一 modId 的旧包留在 mods 目录会与新包冲突导致启动失败 → 只删本模组的旧包
    （保留本次要写的那一个文件名），不动别人的 jar。"""
    d, keep = os.path.dirname(dst), os.path.basename(dst)
    for f in os.listdir(d):
        if f.startswith('promaid-') and f.endswith('.jar') and f != keep:
            try:
                os.remove(os.path.join(d, f))
                print('  removed old jar:', os.path.join(d, f))
            except Exception as e:
                print('  remove failed:', f, e)


# stage to temp (ASCII path) then copy
staged = []
for src, dst in JOBS:
    tmp = os.path.join(tempfile.gettempdir(), 'promaid_deploy_' + os.path.basename(dst))
    shutil.copyfile(src, tmp)
    staged.append((tmp, dst, os.path.getsize(src)))
    print('staged:', tmp, os.path.getsize(tmp))

need_elevation = []
for tmp, dst, size in staged:
    try:
        shutil.copyfile(tmp, dst)
        print('copied (no UAC):', dst)
    except Exception as e:
        print('direct copy failed, will try elevated:', dst, e)
        need_elevation.append((tmp, dst))

if need_elevation:
    inner_parts = ["Copy-Item -LiteralPath '%s' -Destination '%s' -Force" % (t, d)
                   for t, d in need_elevation]
    for t, d in need_elevation:
        inner_parts.append("Get-ChildItem -LiteralPath '%s' -Filter 'promaid-*.jar' "
                           "-ErrorAction SilentlyContinue | Where-Object { $_.Name -ne '%s' } "
                           "| Remove-Item -Force" % (os.path.dirname(d), os.path.basename(d)))
    inner = '; '.join(inner_parts)
    cmd = ['powershell', '-NoProfile', '-Command',
           "Start-Process powershell -Verb RunAs -Wait -ArgumentList '-NoProfile','-Command',"
           "'__INNER__'".replace('__INNER__', inner.replace("'", "''"))]
    r = subprocess.run(cmd, capture_output=True, text=True)
    print('elevated rc:', r.returncode, (r.stdout or '').strip()[:200], (r.stderr or '').strip()[:200])

for src, dst in JOBS:
    if os.path.isdir(os.path.dirname(dst)):
        drop_other_versions(dst)

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
    try:
        os.remove(tmp)
    except Exception:
        pass
sys.exit(0 if ok else 3)
