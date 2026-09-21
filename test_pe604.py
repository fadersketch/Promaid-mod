# -*- coding: utf-8 -*-
"""实测六百〇四 追问：等价交换（ProjectE）的「炸药」到底算不算 TNT？

只跑 1.20.1 那台（ProjectE 只装在 forge 服上）。同一场两台女仆，装备完全一样，只差背包里那一件：
  A = projecte:nova_catalyst（中文「爆破新星」，jar 里实证：ProjectETNT extends TntBlock）
  B = minecraft:tnt（对照：必须有投掷行，证明这套脚手架是活的）

判据（这是一把"尺子"，不是"必须成立"的断言——两种结果都算 PASS，结论打在 note 里）：
 ① B 有「投掷 TNT」行 —— 脚手架成立的**前提**（没有它就是 FAIL，A 的结论不算数）
 ② A 有没有投掷行 —— 这就是答案本身：
      · 有 → 当时的判据认得出等价交换的炸药（例如判据已扩到"方块是 TntBlock"）
      · 没有 → 当时的判据认不出（只有"注册名里带 tnt"这一条时就是这个结果）
 ③ 两台女仆的背包回读里都能看到各自的物品 id（证明 projecte:nova_catalyst 是真实存在的物品，
    不是从 jar 的 assets 文件名猜的）
"""
import os
import shutil
import subprocess
import sys
import time

sys.stdout.reconfigure(encoding='utf-8')

CFG = {
    'dir': r'C:/Users/Sketch/mc_server_test/1201',
    'java': r'C:/Users/Sketch/AppData/Roaming/.minecraft/runtime/java-runtime-beta/bin/java.exe',
    'args': ['@user_jvm_args.txt',
             '@libraries/net/minecraftforge/forge/1.20.1-47.4.23/win_args.txt', 'nogui'],
    'jar': r'C:/Users/Sketch/.zcode/workspace/default/promaid-mod/patched/promaid-1.2.2.jar',
    'modname': 'promaid-1.2.2.jar',
    'bow': 'id:"minecraft:bow",Count:1b',
    'elytra': 'id:"minecraft:elytra",Count:1b',
    'arrow': 'id:"minecraft:arrow",Count:64b',
    'firework': 'id:"minecraft:firework_rocket",Count:64b',
    'flint': 'id:"minecraft:flint_and_steel",Count:1b',
    'nova': 'id:"projecte:nova_catalyst",Count:2b',
    'tnt': 'id:"minecraft:tnt",Count:2b',
    'maxhp_attr': 'generic.max_health',
}
WAIT = 180
TICK = 60
FAIL_PATTERNS = ('Mixin apply for mod promaid failed', 'InvalidInjectionException',
                 'MixinTransformerError', 'MixinApplyError', 'Failed to create brain',
                 'OutOfMemoryError')
NAME_A = 'PE604Nova'
NAME_B = 'PE604Tnt'

server = CFG['dir']
pid_file = os.path.join(server, 'server.pid')
if os.path.exists(pid_file):
    pid = open(pid_file).read().strip()
    subprocess.run(['taskkill', '/PID', pid, '/T', '/F'], capture_output=True)
    time.sleep(3)

mods_dir = os.path.join(server, 'mods')
import glob
for old in glob.glob(os.path.join(mods_dir, 'promaid-*.jar')):
    if os.path.basename(old) != CFG['modname']:
        os.remove(old)
        print('removed stale jar:', os.path.basename(old))
shutil.copyfile(CFG['jar'], os.path.join(mods_dir, CFG['modname']))
print('jar copied:', CFG['modname'], os.path.getsize(CFG['jar']))

log_path = os.path.join(server, 'console_pe604.log')
log = open(log_path, 'wb')
p = subprocess.Popen([CFG['java'], '-Xmx3G', '-Dfile.encoding=UTF-8'] + CFG['args'],
                     cwd=server, stdout=log, stderr=subprocess.STDOUT,
                     stdin=subprocess.PIPE,
                     creationflags=subprocess.CREATE_NEW_PROCESS_GROUP | 0x00000008)
open(pid_file, 'w').write(str(p.pid))
print('server started pid', p.pid)


def send(cmd):
    print('   >', cmd[:200])
    try:
        p.stdin.write((cmd + '\n').encode('utf-8'))
        p.stdin.flush()
    except Exception as e:
        print('   (stdin failed: %s)' % e)


def readlog():
    try:
        raw = open(log_path, 'rb').read()
    except Exception:
        return ''
    decoded = {enc: raw.decode(enc, 'replace') for enc in ('utf-8', 'gbk', 'cp936', 'latin-1')}
    for enc, t in decoded.items():
        if '投掷 TNT' in t or '空袭轰炸' in t or '放烟花起飞' in t:
            return t
    for enc in ('utf-8', 'gbk'):
        if 'Done (' in decoded[enc] or 'Starting minecraft server' in decoded[enc]:
            return decoded[enc]
    return decoded['utf-8']


done = False
for _ in range(WAIT):
    time.sleep(1)
    if p.poll() is not None:
        break
    if 'Done (' in readlog():
        done = True
        break

verdict = 'PASS'
notes = []
if not done:
    verdict = 'FAIL(server never reached Done)'
else:
    send('gamerule doMobSpawning false')
    send('difficulty easy')
    for tag in ('pe604nova', 'pe604tnt', 'pe604target'):
        send('kill @e[tag=%s]' % tag)
    time.sleep(1)

    def inv(*items):
        return ','.join('{Slot:%db,%s}' % (i, it) for i, it in enumerate(items))

    def maid_nbt(name, tag, items):
        return ('{MaidTask:"maid_smart:flight_ranged",MaidScheduleMode:"ALL",'
                'HandItems:[{%s},{}],'
                'ArmorItems:[{},{},{%s},{}],'
                'MaidInventory:{Size:36,Items:[%s]},'
                'Owner:[I;1,2,3,4],Tags:["%s"],'
                'CustomName:"\\"%s\\"",PersistenceRequired:1b}') % (
            CFG['bow'], CFG['elytra'], inv(*items), tag, name)

    A = '@e[tag=pe604nova,limit=1]'
    B = '@e[tag=pe604tnt,limit=1]'
    T = '@e[tag=pe604target,limit=1]'
    kit = (CFG['flint'], CFG['arrow'], CFG['firework'], CFG['firework'])
    send('summon touhou_little_maid:maid ~ ~2 ~ %s' % maid_nbt(NAME_A, 'pe604nova', (CFG['nova'],) + kit))
    time.sleep(2)
    send('summon touhou_little_maid:maid ~ ~2 ~3 %s' % maid_nbt(NAME_B, 'pe604tnt', (CFG['tnt'],) + kit))
    time.sleep(2)
    send('summon minecraft:zombie ~6 ~6 ~ {NoAI:1b,NoGravity:1b,PersistenceRequired:1b,'
         'Health:2000f,Attributes:[{Name:"%s",Base:2000}],'
         'ActiveEffects:[{Id:11,Amplifier:9,Duration:999999}],Tags:["pe604target"],'
         'CustomName:"\\"PE604Target\\""}' % CFG['maxhp_attr'])
    time.sleep(3)
    # 脚手架自检：背包回读里能看见 projecte:nova_catalyst = 这个 id 在注册表里真实存在
    send('data get entity %s MaidInventory' % A)
    send('data get entity %s MaidInventory' % B)
    time.sleep(3)
    print('observing for %ds ...' % TICK)
    time.sleep(TICK)
    send('data get entity %s MaidInventory' % A)
    send('data get entity %s MaidInventory' % B)
    time.sleep(3)

    data = readlog()
    for pat in FAIL_PATTERNS:
        if pat in data:
            verdict = 'FAIL(error line: %s)' % pat
            notes.append(pat)

    lines_a = [l for l in data.splitlines() if '投掷 TNT' in l and NAME_A in l]
    lines_b = [l for l in data.splitlines() if '投掷 TNT' in l and NAME_B in l]
    notes.append('投掷行：A（等价交换 nova_catalyst）=%d，B（原版 tnt）=%d' % (len(lines_a), len(lines_b)))
    for l in lines_b[:3]:
        notes.append('  B: ' + l.strip()[:180])

    invs_a = [l for l in data.splitlines() if NAME_A in l and 'Size: 36' in l]
    invs_b = [l for l in data.splitlines() if NAME_B in l and 'Size: 36' in l]
    nova_read = any('nova_catalyst' in l for l in invs_a)
    tnt_read = any('minecraft:tnt' in l for l in invs_b)
    notes.append('回读：A 里读到 nova_catalyst=%s（%d 次），B 里读到 minecraft:tnt=%s（%d 次）'
                 % (nova_read, len(invs_a), tnt_read, len(invs_b)))
    if invs_a:
        notes.append('  A 末次回读: ' + invs_a[-1].strip()[:200])

    if not lines_b:
        verdict = 'FAIL(对照那台原版 TNT 都没扔 —— 脚手架没成立，A 的结论不算数)'
    if not nova_read:
        verdict = 'FAIL(回读里没有 nova_catalyst —— 这个 id 可能不存在，结论不算数)'
    notes.append('结论：当前判据（注册名里带 tnt）%s等价交换的爆破新星'
                 % ('认不出' if not lines_a else '认得出'))

send('say PE604_CHECK_DONE')
time.sleep(2)
send('stop')
for _ in range(40):
    time.sleep(1)
    if p.poll() is not None:
        break
if p.poll() is None:
    subprocess.run(['taskkill', '/PID', str(p.pid), '/T', '/F'], capture_output=True)

data = readlog()
print('verdict:', verdict)
for n in notes:
    print('note:', n)
print('--- relevant log lines ---')
shown = 0
for l in data.splitlines():
    if any(k in l for k in ('投掷 TNT', 'Summoned new', 'Size: 36', 'Mixin apply', 'FATAL',
                            'ArgumentException', 'Unknown')):
        print('  ', l[:240])
        shown += 1
        if shown > 40:
            print('   ...(truncated)')
            break
if shown == 0:
    print('   (nothing matched — inspect %s)' % log_path)
sys.exit(0 if verdict == 'PASS' else 1)
