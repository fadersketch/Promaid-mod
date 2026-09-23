# -*- coding: utf-8 -*-
"""Maid-brain load check: the ONLY way our brain-task mixins get applied is when
TLM builds a maid's brain, i.e. when a maid entity is created. test_server.py only
waits for 'Done (', so it never loads those classes and never notices a broken
injection (实测五百四十 taught us that the hard way).

This harness: start server -> wait Done -> /summon a maid -> watch the log ->
/stop. Verdict FAIL if any mixin-apply failure or our mixin's target class shows
up in an error line.

Usage: python test_maid_load.py [1201|neoforge1211]
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
        'jar': r'C:/Users/Sketch/.zcode/workspace/default/promaid-mod/patched/promaid-1.2.4.jar',
        'modname': 'promaid-1.2.4.jar',
    },
    'neoforge1211': {
        'dir': r'C:/Users/Sketch/mc_server_test/neoforge1211',
        'java': r'C:/Users/Sketch/AppData/Roaming/.minecraft/runtime/java-runtime-delta/bin/java.exe',
        'args': ['@user_jvm_args.txt',
                 '@libraries/net/neoforged/neoforge/21.1.250/win_args.txt', 'nogui'],
        'jar': r'C:/Users/Sketch/.zcode/workspace/default/promaid-mod/patched/promaid-1.2.4-neoforge-1.21.1.jar',
        'modname': 'promaid-1.2.4-neoforge-1.21.1.jar',
    },
}
WAIT = 180          # 机器忙时 NeoForge 的并行装载会明显变慢，90s 曾误报过两次"没到 Done"
AFTER_SUMMON = 10

FAIL_PATTERNS = ('Mixin apply for mod promaid failed', 'InvalidInjectionException',
                 'MixinTransformerError', 'MixinApplyError', 'MaidMeleeAttack',
                 'Failed to create brain', 'OutOfMemoryError')

which = sys.argv[1] if len(sys.argv) > 1 else 'neoforge1211'
cfg = TARGETS.get(which)
if not cfg:
    print('usage: python test_maid_load.py [1201|neoforge1211]')
    sys.exit(2)

server = cfg['dir']
pid_file = os.path.join(server, 'server.pid')
if os.path.exists(pid_file):
    pid = open(pid_file).read().strip()
    subprocess.run(['taskkill', '/PID', pid, '/T', '/F'], capture_output=True)
    time.sleep(3)

shutil.copyfile(cfg['jar'], os.path.join(server, 'mods', cfg['modname']))
print('jar copied:', cfg['modname'], os.path.getsize(cfg['jar']))

log_path = os.path.join(server, 'console_maidload.log')
log = open(log_path, 'wb')
p = subprocess.Popen([cfg['java'], '-Xmx3G', '-Dfile.encoding=UTF-8'] + cfg['args'],
                     cwd=server, stdout=log, stderr=subprocess.STDOUT,
                     stdin=subprocess.PIPE,
                     creationflags=subprocess.CREATE_NEW_PROCESS_GROUP | 0x00000008)
open(pid_file, 'w').write(str(p.pid))
print('server started pid', p.pid, '— waiting up to %ds for Done' % WAIT)


def send(cmd):
    print('   >', cmd)
    try:
        p.stdin.write((cmd + '\n').encode('utf-8'))
        p.stdin.flush()
    except Exception as e:
        print('   (stdin write failed: %s)' % e)


def readlog():
    try:
        return open(log_path, 'rb').read().decode('utf-8', 'replace')
    except Exception:
        return ''


done = False
for _ in range(WAIT):
    time.sleep(1)
    if p.poll() is not None:
        break
    if 'Done (' in readlog():
        done = True
        break

verdict = 'PASS' if done else 'FAIL(server never reached Done)'
summoned = False
if done:
    print('Done reached — summoning a maid (this is what loads TLM brain task classes)')
    send('summon touhou_little_maid:maid ~ ~2 ~')
    # 【实测六百一十三：固定 10 秒窗口踩过一次"假失败"】首次 summon 要连带加载 TLM 的实体/
    # 大脑类，机器忙时 10 秒不够——现象是判定「maid was not summoned」，可翻同一份
    # console_maidload.log 能看到那条「Summoned new …」就在几秒之后（本批实测：确实召出来了）。
    # 所以改成**轮询**：最多 40 秒，出现就立刻继续（正常情况 2~3 秒就返回，不影响耗时）。
    data = ''
    summoned = False
    for _ in range(AFTER_SUMMON * 4):
        time.sleep(1)
        data = readlog()
        if 'Summoned new' in data:
            summoned = True
            break
    if not summoned:
        verdict = 'FAIL(maid was not summoned - the brain path was never exercised)'
    for pat in FAIL_PATTERNS:
        if pat in data:
            verdict = 'FAIL(mixin/brain failure: %s)' % pat
            break

    send('say MAIDLOAD_CHECK_DONE')

time.sleep(2)
send('stop')
for _ in range(40):
    time.sleep(1)
    if p.poll() is not None:
        break
if p.poll() is None:
    subprocess.run(['taskkill', '/PID', str(p.pid), '/T', '/F'], capture_output=True)

data = readlog()
lines = data.splitlines()
print('verdict:', verdict)
print('--- summon / maid / mixin / error lines ---')
shown = 0
for l in lines:
    if ('Summoned new' in l or 'summon' in l.lower() or 'maid' in l.lower()
            or 'Mixin apply' in l or 'InvalidInjection' in l or 'MixinTransformer' in l
            or 'FATAL' in l or 'ERROR' in l):
        print('  ', l[:220])
        shown += 1
        if shown > 40:
            print('   ...(truncated)')
            break
if shown == 0:
    print('   (nothing matched — inspect %s)' % log_path)
sys.exit(0 if verdict == 'PASS' else 1)
