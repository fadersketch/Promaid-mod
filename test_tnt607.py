# -*- coding: utf-8 -*-
"""实测六百〇七 的场景验证：模组 TNT 那一炸也受「不破坏方块」管辖。

用法:
    python test_tnt607.py 1201

（只跑 1201：等价交换装在那台上；neoforge1211 的 mods 里没有任何 TntBlock 子类，六百〇五 已扫过。）

需求原文："mod的TNT也受我们的不破坏方块管辖。"

场景：先在女仆脚下那片地面 `fill` 出一块 **石头台**（5x5），把木桩放在台的**正上方**——
她扔的等价交换新星（威力 16、原版会拆地形）会落在台子附近，于是"台子还在不在"就是
"这一炸有没有动地形"的直接证据。同一场跑两轮（改服务端配置后重启）：

  A 轮（默认：不破坏方块）：
    ① 出现守卫日志「模组 TNT 那一炸按「不破坏方块」处理」
    ② 石头台采样格 **全部还在**
    ③ 木桩**掉了血** —— 只收地形权限、伤害照旧（这一条是"没管过头"的证明）
    ④ 顺带确认她扔的是它自己那一枚（沿用六百〇五 的探针口径）
  B 轮（服务端 breakBlocks 改 true 后重启）：
    ⑤ 守卫日志 0 行（开关管得住）
    ⑥ 石头台采样格 **至少一个被拆** —— 证明"是这道开关在管"，不是我们碰巧没炸到
"""
import glob
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
        'bow': 'id:"minecraft:bow",Count:1b',
        'elytra': 'id:"minecraft:elytra",Count:1b',
        'arrow': 'id:"minecraft:arrow",Count:64b',
        'firework': 'id:"minecraft:firework_rocket",Count:64b',
        'novas': 'id:"projecte:nova_catalyst",Count:8b',
        'flint': 'id:"minecraft:flint_and_steel",Count:1b',
        'maxhp_attr': 'generic.max_health',
    },
}
WAIT = 180
TICK = 70
FAIL_PATTERNS = ('Mixin apply for mod promaid failed', 'InvalidInjectionException',
                 'MixinTransformerError', 'MixinApplyError', 'Failed to create brain',
                 'OutOfMemoryError')
FATAL_HINTS = ('promaid', 'mixin')

NAME = 'Tnt607Maid'
GUARD_MARK = '按「不破坏方块」处理'

which = sys.argv[1] if len(sys.argv) > 1 else '1201'
cfg = TARGETS.get(which)
if not cfg:
    print('usage: python test_tnt607.py [1201]')
    print('（neoforge1211 那台没装等价交换、也没有任何 TntBlock 子类，跑不了本用例）')
    sys.exit(2)

server = cfg['dir']
pid_file = os.path.join(server, 'server.pid')


def stop_server():
    if os.path.exists(pid_file):
        pid = open(pid_file).read().strip()
        subprocess.run(['taskkill', '/PID', pid, '/T', '/F'], capture_output=True)
        time.sleep(3)


def set_break_blocks(enabled):
    """改服务端配置的「轰炸破坏方块」开关（跑两轮用）。返回是否改到了。"""
    p = os.path.join(server, 'config', 'promaid-common.toml')
    if not os.path.exists(p):
        return False
    raw = open(p, 'rb').read()
    try:
        t = raw.decode('utf-8')
    except Exception:
        t = raw.decode('gbk', errors='replace')
    m = re.search(r'^(\s*breakBlocks\s*=\s*)(\w+)\s*$', t, re.M)
    if not m:
        return False
    new = t[:m.start()] + m.group(1) + ('true' if enabled else 'false') + t[m.end():]
    open(p, 'wb').write(new.encode('utf-8'))
    return True


# 【为什么要一个"锚点"】第一版把 fill 与采样都挂在**女仆**身上（`execute at @e[tag=女仆]`），
# 而她一进世界就起飞、几秒后就飘到几十格高空——采样命令于是全在**半空**里执行，
# `if block ~ ~ ~ minecraft:stone` 永远不成立，日志里一条 BASE_/KEEP_ 都没有
# （看起来像"台子被拆光了"，其实是量错了地方）。现在改用一尊 **Marker 盔甲架**当锚点：
# 它不落、不动、不可见，台子/木桩/采样全部以它为参照系。
# 采样格 = 以锚点为原点、台面（锚点下方 1 格）的四角 + 中心：只要这些都还在，
# 就说明这块 5x5 石头台没被那一炸动过。
SAMPLES = ['~-2 ~-1 ~-2', '~2 ~-1 ~-2', '~-2 ~-1 ~2', '~2 ~-1 ~2', '~ ~-1 ~']


def summon_anchor(send, suffix):
    send('summon minecraft:armor_stand ~ ~ ~ {Marker:1b,NoGravity:1b,Invisible:1b,'
         'Tags:["tnt607anchor%s"],CustomName:"\\"Tnt607Anchor%s\\""}' % (suffix, suffix))


def run_round(suffix, expect_guard):
    log_path = os.path.join(server, 'console_tnt607_%s.log' % suffix)
    log = open(log_path, 'wb')
    p = subprocess.Popen([cfg['java'], '-Xmx3G', '-Dfile.encoding=UTF-8'] + cfg['args'],
                         cwd=server, stdout=log, stderr=subprocess.STDOUT,
                         stdin=subprocess.PIPE,
                         creationflags=subprocess.CREATE_NEW_PROCESS_GROUP | 0x00000008)
    open(pid_file, 'w').write(str(p.pid))
    print('--- round %s: pid %d (expect_guard=%s)' % (suffix, p.pid, expect_guard))

    def send(cmd):
        print('   >', cmd[:180])
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
        d = {e: raw.decode(e, 'replace') for e in ('utf-8', 'gbk', 'cp936', 'latin-1')}
        for e, t in d.items():
            if '不破坏方块」处理' in t or '投掷 TNT' in t or '放烟花起飞' in t:
                return t
        for e in ('utf-8', 'gbk'):
            if 'Done (' in d[e]:
                return d[e]
        return d['utf-8']

    done = False
    for _ in range(WAIT):
        time.sleep(1)
        if p.poll() is not None:
            break
        if 'Done (' in readlog():
            done = True
            break

    notes = []
    guard_lines = []
    platform_alive = []
    if not done:
        notes.append('%s: FAIL(server never reached Done)' % suffix)
    else:
        send('gamerule doMobSpawning false')
        send('difficulty easy')
        # 清两轮各自的女仆/木桩/锚点（tag 是精确匹配，两个后缀都点名）
        for _ in range(2):
            for t in ('tnt607maidon', 'tnt607maidoff', 'tnt607maid',
                      'tnt607targeton', 'tnt607targetoff', 'tnt607target',
                      'tnt607anchoron', 'tnt607anchoroff', 'tnt607anchor'):
                send('kill @e[tag=%s]' % t)
            time.sleep(1)

        tag = 'tnt607maid' + suffix
        ttag = 'tnt607target' + suffix
        atag = 'tnt607anchor' + suffix

        # ① 锚点（盔甲架）先立在出生点，之后台子/木桩/采样一律以它为参照
        summon_anchor(send, suffix)
        time.sleep(2)
        A = '@e[tag=%s,limit=1]' % atag
        # 台面：锚点下方 1 格铺 5x5；锚点抬到台面上方 1 格，木桩再高 1 格
        send('execute at %s run fill ~-2 ~-1 ~-2 ~2 ~-1 ~2 minecraft:stone' % A)
        time.sleep(2)
        send('data get entity %s Pos' % A)
        time.sleep(1)
        # 女仆：站在台子上（锚点旁边 4 格，别把木桩挡住）
        maid_nbt = (
            '{MaidTask:"maid_smart:flight_ranged",MaidScheduleMode:"ALL",'
            'HandItems:[{%s},{}],'
            'ArmorItems:[{},{},{%s},{}],'
            'MaidInventory:{Size:36,Items:[{Slot:0b,%s},{Slot:1b,%s},{Slot:2b,%s},{Slot:3b,%s}]},'
            'Owner:[I;1,2,3,4],Tags:["%s"],'
            'CustomName:"\\"%s\\"",PersistenceRequired:1b}'
        ) % (cfg['bow'], cfg['elytra'], cfg['novas'], cfg['flint'], cfg['arrow'],
             cfg['firework'], tag, NAME + suffix)
        send('execute at %s run summon touhou_little_maid:maid ~4 ~1 ~ %s' % (A, maid_nbt))
        time.sleep(3)
        M = '@e[tag=%s,limit=1]' % tag
        # 木桩：锚点正上方 1 格（台面上一格），不吃重力、整场打不死
        send('execute at %s run summon minecraft:zombie ~ ~1 ~ {NoAI:1b,NoGravity:1b,'
             'PersistenceRequired:1b,Health:2000f,Attributes:[{Name:"%s",Base:2000}],'
             'ActiveEffects:[{Id:11,Amplifier:9,Duration:999999}],Tags:["%s"],'
             'CustomName:"\\"Tnt607Target%s\\""}' % (A, cfg['maxhp_attr'], ttag, suffix))
        time.sleep(3)
        # 脚手架自检：任务 / 背包（新星 + 打火石）读回来
        send('data get entity %s MaidTask' % M)
        send('data get entity %s MaidInventory' % M)
        time.sleep(2)
        # 基线采样：以锚点为参照（台面在锚点下方 1 格 = ~ ~-1 ~）
        for i, rel in enumerate(SAMPLES):
            send('execute at %s run execute if block %s minecraft:stone run say BASE_%d' % (A, rel, i))
        time.sleep(3)
        print('observing for %ds ...' % TICK)
        time.sleep(TICK)
        # 战后采样：还是以锚点为参照（她不在了也不影响量台子）
        for i, rel in enumerate(SAMPLES):
            send('execute at %s run execute if block %s minecraft:stone run say KEEP_%d' % (A, rel, i))
        time.sleep(3)
        # 木桩血量（"伤害照旧"的证据）
        send('data get entity @e[tag=%s,limit=1] Health' % ttag)
        send('data get entity %s MaidInventory' % M)
        time.sleep(3)

    data = readlog()
    for pat in FAIL_PATTERNS:
        if pat in data:
            notes.append('%s: FAIL(error line: %s)' % (suffix, pat))
    for l in data.splitlines():
        if 'FATAL' in l and any(h in l.lower() for h in FATAL_HINTS):
            notes.append('%s: FATAL %s' % (suffix, l[:140]))
            break

    guard_lines = [l for l in data.splitlines() if GUARD_MARK in l and NAME + suffix in l]
    throws = [l for l in data.splitlines() if '投掷 TNT' in l and NAME + suffix in l]
    keep = sorted({int(m.group(1)) for l in data.splitlines()
                   for m in [re.search(r'KEEP_(\d+)', l)] if m})
    base = sorted({int(m.group(1)) for l in data.splitlines()
                   for m in [re.search(r'BASE_(\d+)', l)] if m})
    health = ''
    for l in data.splitlines():
        if 'Health' in l and ('Tnt607Target' + suffix) in l:
            health = l
    notes.append('%s: 守卫日志 %d 行 / 投掷行 %d 行 / 基线台 %s / 战后台 %s'
                 % (suffix, len(guard_lines), len(throws), base, keep))
    if throws:
        notes.append('%s 投掷示例: %s' % (suffix, throws[0][:200]))
    if guard_lines:
        notes.append('%s 守卫示例: %s' % (suffix, guard_lines[0][:200]))
    if health:
        notes.append('%s 木桩血量: %s' % (suffix, health[-140:]))

    send('stop')
    for _ in range(40):
        time.sleep(1)
        if p.poll() is not None:
            break
    if p.poll() is None:
        subprocess.run(['taskkill', '/PID', str(p.pid), '/T', '/F'], capture_output=True)
    return {'guard': len(guard_lines), 'throws': len(throws), 'keep': keep, 'base': base,
            'data': data, 'notes': notes}


stop_server()
for old in [f for f in os.listdir(os.path.join(server, 'mods')) if f.startswith('promaid-')]:
    if old != cfg['modname']:
        os.remove(os.path.join(server, 'mods', old))
shutil.copyfile(cfg['jar'], os.path.join(server, 'mods', cfg['modname']))
print('jar copied:', cfg['modname'], os.path.getsize(cfg['jar']))

verdict = 'PASS'
notes = []

# ── A 轮：默认（不破坏方块）──
if not set_break_blocks(False):
    notes.append('提示：没能改到 config 的 breakBlocks（旧配置文件里还没有这个键？）——按默认值跑')
a = run_round('on', True)
notes += a['notes']

if a['throws'] == 0:
    verdict = 'FAIL(她没扔出新星 —— 场景没搭起来，后面的判据都不成立)'
elif a['guard'] == 0:
    verdict = 'FAIL(A 轮没有守卫日志 —— 拦截没生效)'
elif len(a['keep']) != len(SAMPLES):
    verdict = 'FAIL(A 轮石头台被拆了（%s/%s 还在）—— 不破坏方块没管住模组 TNT)' % (len(a['keep']), len(SAMPLES))
elif a['base'] and len(a['base']) != len(SAMPLES):
    notes.append('警告：基线采样就不全（%s）——台子可能没铺好，请人工看日志' % a['base'])

# 伤害仍在：木桩血量应该低于 2000（"只收地形权限、伤害照旧"的证据）。
# 【踩过的坑】`data get entity … Health` 的**回显不带 "Health" 这个词**，只回值
# （`Tnt607Targeton has the following entity data: 1024.0f`）——第一版的正则按 "Health"
# 筛行，于是永远读不到、这条判据形同没验。现在改成按**木桩名字**筛。
hp = None
for l in a['data'].splitlines():
    if ('Tnt607Target' + 'on') in l and 'entity data:' in l and 'No entity' not in l:
        m = re.search(r'entity data:\s*([\d.]+)f', l)
        if m:
            hp = float(m.group(1))
if hp is None:
    notes.append('警告：没读到木桩血量 —— "伤害照旧"这条没被验证')
elif hp >= 2000.0:
    verdict = 'FAIL(木桩满血 —— 这一炸连伤害都没了，说明管过头了)'
else:
    notes.append('木桩从 2000 掉到 %.1f 血 → 伤害照旧（只收走了地形权限）' % hp)

# ── B 轮：把 breakBlocks 打开 ──
if set_break_blocks(True):
    b = run_round('off', False)
    notes += b['notes']
    if b['guard'] > 0:
        verdict = 'FAIL(B 轮（开关打开）还有守卫日志 —— 开关没生效)'
    elif b['throws'] > 0 and len(b['keep']) == len(SAMPLES):
        verdict = 'FAIL(B 轮石头台一格没少 —— 开关打开也没让它拆，说明我们误伤了它自己那一炸)'
else:
    notes.append('跳过 B 轮：没能改到 breakBlocks')

print('verdict:', verdict)
for n in notes:
    print('note:', n)
print('--- 关键日志行（A 轮）---')
shown = 0
for l in a['data'].splitlines():
    if any(k in l for k in ('不破坏方块」处理', '投掷 TNT', '放烟花起飞', 'BASE_', 'KEEP_',
                            'Tnt607Target', 'Summoned new', 'MaidInventory', 'Mixin apply',
                            'FATAL')):
        print('  ', l[:230])
        shown += 1
        if shown > 60:
            print('   ...(truncated)')
            break
if shown == 0:
    print('   (nothing matched)')
# B 轮的采样是关键证据（"开关打开时台子被拆了"），单独印一遍
print('--- B 轮（开关打开）---')
n2 = 0
for l in (b['data'].splitlines() if 'b' in dir() else []):
    if 'KEEP_' in l or '不破坏方块」处理' in l or ('Tnt607Targetoff' in l and 'entity data:' in l):
        print('  ', l[:230])
        n2 += 1
        if n2 > 20:
            break
if n2 == 0:
    print('   (B 轮没有 KEEP_ 行 = 采样格全被拆掉，正是预期的结果)')

sys.exit(0 if verdict == 'PASS' else 1)
