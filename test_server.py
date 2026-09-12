# -*- coding: utf-8 -*-
"""Reusable dedicated-server regression test for promaid.

Usage:
    python test_server.py 1201        # Forge 1.20.1 server (Java 17)
    python test_server.py neoforge1211  # NeoForge 1.21.1 server (Java 21)

What it does:
  1. stops a previous test server (server.pid)
  2. copies the current patched jar into <server>/mods
  3. starts the server, waits up to WAIT seconds for 'Done (' or a crash
  4. prints the verdict plus any promaid-related failure lines
"""
import os
import re
import shutil
import subprocess
import sys
import time

sys.stdout.reconfigure(encoding='utf-8')

TARGETS = {
    '1201': {
        'dir': r'C:/Users/Sketch/mc_server_test/1201',
        'java': r'C:/Users/Sketch/AppData/Roaming/.minecraft/runtime/java-runtime-beta/bin/java.exe',
        'args': ['@user_jvm_args.txt',
                 '@libraries/net/minecraftforge/forge/1.20.1-47.4.23/win_args.txt', 'nogui'],
        'jar': r'C:/Users/Sketch/.zcode/workspace/default/promaid-mod/patched/promaid-1.1.1.jar',
        'modname': 'promaid-1.1.1.jar',
    },
    'neoforge1211': {
        'dir': r'C:/Users/Sketch/mc_server_test/neoforge1211',
        'java': r'C:/Users/Sketch/AppData/Roaming/.minecraft/runtime/java-runtime-delta/bin/java.exe',
        'args': ['@user_jvm_args.txt',
                 '@libraries/net/neoforged/neoforge/21.1.250/win_args.txt', 'nogui'],
        'jar': r'C:/Users/Sketch/.zcode/workspace/default/promaid-mod/patched/promaid-1.1.0-neoforge-1.21.1.jar',
        'modname': 'promaid-1.1.0-neoforge-1.21.1.jar',
    },
}
WAIT = 90
# 看到 Done 之后再盯这么久，捕捉启动后延迟崩溃（蓝图扫描 OOM / ServerStarted 异常等）
POST_DONE_WATCH = 30

which = sys.argv[1] if len(sys.argv) > 1 else 'neoforge1211'
cfg = TARGETS.get(which)
if not cfg:
    print('usage: python test_server.py [1201|neoforge1211]')
    sys.exit(2)

server = cfg['dir']
pid_file = os.path.join(server, 'server.pid')
if os.path.exists(pid_file):
    pid = open(pid_file).read().strip()
    subprocess.run(['taskkill', '/PID', pid, '/T', '/F'], capture_output=True)
    time.sleep(3)

shutil.copyfile(cfg['jar'], os.path.join(server, 'mods', cfg['modname']))
print('jar copied:', cfg['modname'], os.path.getsize(cfg['jar']))

log_path = os.path.join(server, 'console_test.log')
log = open(log_path, 'wb')
p = subprocess.Popen([cfg['java'], '-Xmx3G', '-Dfile.encoding=UTF-8'] + cfg['args'],
                     cwd=server, stdout=log, stderr=subprocess.STDOUT,
                     stdin=subprocess.DEVNULL,
                     creationflags=subprocess.CREATE_NEW_PROCESS_GROUP | 0x00000008)
open(pid_file, 'w').write(str(p.pid))
print('server started pid', p.pid, '— waiting up to %ds' % WAIT)

verdict = 'TIMEOUT'
for _ in range(WAIT):
    time.sleep(1)
    if p.poll() is not None:
        verdict = 'EXITED (crash)'
        break
    data = open(log_path, 'rb').read().decode('utf-8', 'replace')
    if 'Done (' in data:
        verdict = 'PASS'
        break

if verdict == 'PASS':
    print('Done reached — watching %ds for delayed crashes...' % POST_DONE_WATCH)
    for _ in range(POST_DONE_WATCH):
        time.sleep(1)
        if p.poll() is not None:
            verdict = 'CRASHED AFTER STARTUP'
            break
        data = open(log_path, 'rb').read().decode('utf-8', 'replace')
        if 'OutOfMemoryError' in data or 'Exception caught during firing event' in data \
                or 'Stopping server' in data:
            verdict = 'CRASHED AFTER STARTUP'
            break

data = open(log_path, 'rb').read().decode('utf-8', 'replace')
lines = data.splitlines()
print('verdict:', verdict)
for l in lines:
    if re.search(r'Done \(|promaid.*(failed|error)|Failure message|InjectionError|ModLoadingException|'
                 r'Failed to start|OutOfMemoryError|Exception caught during firing', l):
        print('  ', l[:230])
if verdict != 'PASS':
    print('--- tail 15 ---')
    for l in lines[-15:]:
        print('  ', l[:230])

if p.poll() is None:
    subprocess.run(['taskkill', '/PID', str(p.pid), '/T', '/F'], capture_output=True)
    print('server stopped')
sys.exit(0 if verdict == 'PASS' else 1)