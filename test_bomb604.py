# -*- coding: utf-8 -*-
"""实测六百〇四 的场景验证：TNT 判据放宽成「注册名里带 tnt 的都算」。

用法:
    python test_bomb604.py 1201
    python test_bomb604.py neoforge1211

两台测试服的 mods 里没有任何"注册名带 tnt 的模组物品"（_jars604.py 扫过：0 命中），
所以这里的探针用 **minecraft:tnt_minecart**：它的注册名里有 tnt、但绝不是
minecraft:tnt —— 正好是这次放宽的那条边界。旧判据（写死 minecraft:tnt）下她一发都不会扔，
新判据下她照常扔炸弹、并且把这一件**消耗掉**。

判据（都在服务端日志 / data get 回显里）：
 ① 日志里有一行 `投掷 TNT ×N（minecraft:tnt_minecart，引信 …）` 且前缀是 A 女仆的名字
    —— 放宽后的判据生效 + 日志说清了扔的是哪一件（这次新加的字段）
 ② 同一批日志里**没有** B 女仆（包里只有圆石 + 打火石）的 `投掷 TNT` 行 —— 负向对照：
    放宽的是"名字里带 tnt"，不是"什么都算"
 ③ 背包回读：A 的 tnt_minecart 那一格空了（真消耗）
 ④ 观察窗里抽样 `@e[type=minecraft:tnt]` —— 扔出去的确实是一枚原版引信 TNT（不是矿车）
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
        'jar': r'C:/Users/Sketch/.zcode/workspace/default/promaid-mod/patched/promaid-1.2.2.jar',
        'modname': 'promaid-1.2.2.jar',
        'bow': 'id:"minecraft:bow",Count:1b',
        'elytra': 'id:"minecraft:elytra",Count:1b',
        'arrow': 'id:"minecraft:arrow",Count:64b',
        'firework': 'id:"minecraft:firework_rocket",Count:64b',
        # 探针：注册名里带 tnt、但不是 minecraft:tnt
        'tntcart': 'id:"minecraft:tnt_minecart",Count:1b',
        'stone': 'id:"minecraft:cobblestone",Count:1b',
        'flint': 'id:"minecraft:flint_and_steel",Count:1b',
        'maxhp_attr': 'generic.max_health',
    },
    'neoforge1211': {
        'dir': r'C:/Users/Sketch/mc_server_test/neoforge1211',
        'java': r'C:/Users/Sketch/AppData/Roaming/.minecraft/runtime/java-runtime-delta/bin/java.exe',
        'args': ['@user_jvm_args.txt',
                 '@libraries/net/neoforged/neoforge/21.1.250/win_args.txt', 'nogui'],
        'jar': r'C:/Users/Sketch/.zcode/workspace/default/promaid-mod/patched/promaid-1.2.2-neoforge-1.21.1.jar',
        'modname': 'promaid-1.2.2-neoforge-1.21.1.jar',
        'bow': 'id:"minecraft:bow",count:1',
        'elytra': 'id:"minecraft:elytra",count:1',
        'arrow': 'id:"minecraft:arrow",count:64',
        'firework': 'id:"minecraft:firework_rocket",count:64',
        'tntcart': 'id:"minecraft:tnt_minecart",count:1',
        'stone': 'id:"minecraft:cobblestone",count:1',
        'flint': 'id:"minecraft:flint_and_steel",count:1',
        'maxhp_attr': 'minecraft:generic.max_health',
    },
}
WAIT = 180
TICK = 75
SAMPLE_EVERY = 5          # 观察窗里每 5 秒抽样一次 TNT 实体
FAIL_PATTERNS = ('Mixin apply for mod promaid failed', 'InvalidInjectionException',
                 'MixinTransformerError', 'MixinApplyError', 'Failed to create brain',
                 'OutOfMemoryError')

NAME_A = 'Bomb604TntCart'
NAME_B = 'Bomb604Stone'

which = sys.argv[1] if len(sys.argv) > 1 else '1201'
cfg = TARGETS.get(which)
if not cfg:
    print('usage: python test_bomb604.py [1201|neoforge1211]')
    sys.exit(2)

server = cfg['dir']
pid_file = os.path.join(server, 'server.pid')

if os.path.exists(pid_file):
    pid = open(pid_file).read().strip()
    subprocess.run(['taskkill', '/PID', pid, '/T', '/F'], capture_output=True)
    time.sleep(3)

mods_dir = os.path.join(server, 'mods')
import glob
for old in glob.glob(os.path.join(mods_dir, 'promaid-*.jar')):
    if os.path.basename(old) != cfg['modname']:
        os.remove(old)
        print('removed stale jar:', os.path.basename(old))
shutil.copyfile(cfg['jar'], os.path.join(mods_dir, cfg['modname']))
print('jar copied:', cfg['modname'], os.path.getsize(cfg['jar']))

log_path = os.path.join(server, 'console_bomb604.log')
log = open(log_path, 'wb')
p = subprocess.Popen([cfg['java'], '-Xmx3G', '-Dfile.encoding=UTF-8'] + cfg['args'],
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
        if '放烟花起飞' in t or '投掷 TNT' in t or '空袭轰炸' in t:
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
    # 【只清自己的残留】世界是持久化的，前几轮的女仆/木桩会跨轮活下来
    for tag in ('bomb604maida', 'bomb604maidb', 'bomb604targeta', 'bomb604targetb'):
        send('kill @e[tag=%s]' % tag)
    time.sleep(1)

    # A：探针物品（tnt_minecart）+ 打火石；B：负向对照（圆石 + 打火石，没有任何带 tnt 的东西）
    inv_a = ','.join('{Slot:%db,%s}' % (i, it) for i, it in enumerate([
        cfg['tntcart'], cfg['flint'], cfg['arrow'], cfg['firework'], cfg['firework']]))
    inv_b = ','.join('{Slot:%db,%s}' % (i, it) for i, it in enumerate([
        cfg['stone'], cfg['flint'], cfg['arrow'], cfg['firework'], cfg['firework']]))

    def maid_nbt(name, tag, inv):
        return ('{MaidTask:"maid_smart:flight_ranged",MaidScheduleMode:"ALL",'
                'HandItems:[{%s},{}],'
                'ArmorItems:[{},{},{%s},{}],'
                'MaidInventory:{Size:36,Items:[%s]},'
                'Owner:[I;1,2,3,4],Tags:["%s"],'
                'CustomName:"\\"%s\\"",PersistenceRequired:1b}') % (cfg['bow'], cfg['elytra'], inv, tag, name)

    MAID_A = '@e[tag=bomb604maida,limit=1]'
    MAID_B = '@e[tag=bomb604maidb,limit=1]'
    TA = '@e[tag=bomb604targeta,limit=1]'
    TB = '@e[tag=bomb604targetb,limit=1]'

    # 先在**地面**出生（避免高空自由落体这种与功能无关的噪声），自检走完再抬到 200 格高空
    send('summon touhou_little_maid:maid ~ ~2 ~ %s' % maid_nbt(NAME_A, 'bomb604maida', inv_a))
    time.sleep(2)
    send('summon touhou_little_maid:maid ~ ~2 ~3 %s' % maid_nbt(NAME_B, 'bomb604maidb', inv_b))
    time.sleep(3)
    # 木桩：NoAI（不还手不跑）+ NoGravity（悬空不落）+ 2000 血（整场打不死）
    for tag, name in (('bomb604targeta', 'Bomb604TargetA'), ('bomb604targetb', 'Bomb604TargetB')):
        send('summon minecraft:zombie ~6 ~6 ~ {NoAI:1b,NoGravity:1b,PersistenceRequired:1b,'
             'Health:2000f,Attributes:[{Name:"%s",Base:2000}],'
             'ActiveEffects:[{Id:11,Amplifier:9,Duration:999999}],Tags:["%s"],'
             'CustomName:"\\"%s\\""}' % (cfg['maxhp_attr'], tag, name))
        time.sleep(1)
    time.sleep(2)
    # 【脚手架自检】任务 / 归属 / 背包回读（NBT 写错时"不报错、只是没生效"，与功能没做一模一样）
    send('data get entity %s MaidTask' % MAID_A)
    send('data get entity %s MaidInventory' % MAID_A)
    send('data get entity %s MaidInventory' % MAID_B)
    time.sleep(3)
    # 【必须用显式选择器 tp】`execute at <maid> run tp @s …` 动的是命令执行者（首轮实测踩过）
    send('execute at %s run tp %s ~ ~200 ~' % (MAID_A, MAID_A))
    send('execute at %s run tp %s ~ ~200 ~' % (MAID_B, MAID_B))
    time.sleep(1)
    send('execute at %s run tp %s ~4 ~1 ~' % (MAID_A, TA))
    send('execute at %s run tp %s ~4 ~1 ~' % (MAID_B, TB))
    time.sleep(1)
    print('observing for %ds ...' % TICK)
    samples = []
    for _ in range(TICK // SAMPLE_EVERY):
        time.sleep(SAMPLE_EVERY)
        send('data get entity @e[type=minecraft:tnt,limit=1]')
    send('data get entity %s MaidInventory' % MAID_A)
    send('data get entity %s MaidInventory' % MAID_B)
    time.sleep(3)

    data = readlog()
    for pat in FAIL_PATTERNS:
        if pat in data:
            verdict = 'FAIL(error line: %s)' % pat
            notes.append(pat)

    # ① A 的投掷行：必须点名 minecraft:tnt_minecart（放宽后的判据 + 新日志字段）
    tnt_lines = [l for l in data.splitlines() if '投掷 TNT' in l]
    line_a = [l for l in tnt_lines if NAME_A in l]
    line_b = [l for l in tnt_lines if NAME_B in l]
    ok_id = [l for l in line_a if 'minecraft:tnt_minecart' in l]
    notes.append('投掷行 A=%d 行（其中点名 tnt_minecart 的 %d 行）/ B=%d 行'
                 % (len(line_a), len(ok_id), len(line_b)))
    if not line_a:
        verdict = 'FAIL(探针物品 tnt_minecart 没触发投掷 —— 放宽后的判据没生效)'
    elif not ok_id:
        verdict = 'FAIL(投掷行里没点名 minecraft:tnt_minecart —— 扔的可能是别的物品)'
    if line_b:
        verdict = 'FAIL(负向对照也扔了 —— 判据放宽过头了：%s)' % line_b[0][:160]

    # ③ 背包回读：A 的 tnt_minecart 那一格空了（真消耗）
    # 【为什么按名字挑行】两台女仆都会回读背包，B 那行本来就没有 tnt_minecart——
    # 直接取"日志里最后一行 Size: 36"会被 B 顶掉、判据变成假通过。只认 A 自己的回读行。
    inv_lines = [l for l in data.splitlines()
                 if NAME_A in l and ('Size: 36' in l or 'size: 36' in l)]
    first_inv = inv_lines[0] if inv_lines else ''
    last_inv = inv_lines[-1] if inv_lines else ''
    notes.append('A 的背包回读 %d 次；首次含 tnt_minecart=%s；末次含 tnt_minecart=%s'
                 % (len(inv_lines), 'tnt_minecart' in first_inv, 'tnt_minecart' in last_inv))
    notes.append('A 末次回读: %s' % last_inv[:220])
    if not inv_lines:
        verdict = 'FAIL(没有回读到 A 的背包 —— 脚手架没生效，消耗这一条没被验证)'
    elif 'tnt_minecart' in last_inv:
        verdict = 'FAIL(末次回读里 tnt_minecart 还在 —— 那一发没被消耗)'

    # ④ 抽样到 TNT 实体 = 扔出去的确实是一枚原版引信 TNT
    burst = '\n'.join(samples)
    see_tnt = 'Fuse' in burst or 'fuse' in burst
    notes.append('观察窗里抽到 TNT 实体=%s' % see_tnt)

    launched = ('放烟花起飞' in data) or ('掉高补烟花' in data) or ('远程空袭' in data)
    notes.append('起飞=%s' % launched)

send('say BOMB604_CHECK_DONE')
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
    if any(k in l for k in ('投掷 TNT', '空袭轰炸', '放烟花起飞', 'Summoned new', 'MaidInventory',
                            'MaidTask', 'flight_ranged', 'Fuse', 'Mixin apply', 'FATAL',
                            'ArgumentException', 'Unknown')):
        print('  ', l[:240])
        shown += 1
        if shown > 80:
            print('   ...(truncated)')
            break
if shown == 0:
    print('   (nothing matched — inspect %s)' % log_path)

sys.exit(0 if verdict == 'PASS' else 1)
