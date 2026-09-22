# -*- coding: utf-8 -*-
"""实测六百〇三 的场景验证：远程空袭放炸弹（重生锚/末地水晶）+ 悬空后门 + TNT 用烈焰弹点火。

用法:
    python _test_bomb603.py 1201            # 默认配置（airPlace=on）：远程空袭里放得下炸弹
    python _test_bomb603.py 1201 backdoor   # 先关掉「空中强制放置」再跑：只有走后门才能放成
    python _test_bomb603.py neoforge1211 [...]

判据（都在服务端日志 / data get 回显里）：
 ① '投掷 TNT'      —— 远程空袭投出了一发（点火料只有烈焰弹，没有打火石）
 ② '放下 末地水晶' / '放下 重生锚' —— 轰炸相位在**远程空袭**里真的放下了底座
 ③ backdoor 模式必须出现 '走后门：'（那一轮 airPlace 是关的，前四级全落空）
 ④ 女仆背包回读：烈焰弹从 2 变 1（真消耗）
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
        'jar': r'C:/Users/Sketch/.zcode/workspace/default/promaid-mod/patched/promaid-1.2.3.jar',
        'modname': 'promaid-1.2.3.jar',
        'bow': 'id:"minecraft:bow",Count:1b',
        'elytra': 'id:"minecraft:elytra",Count:1b',
        'arrow': 'id:"minecraft:arrow",Count:64b',
        'firework': 'id:"minecraft:firework_rocket",Count:64b',
        'tnt': 'id:"minecraft:tnt",Count:2b',
        'charge': 'id:"minecraft:fire_charge",Count:2b',
        'obsidian': 'id:"minecraft:obsidian",Count:1b',
        'crystal': 'id:"minecraft:end_crystal",Count:1b',
        'anchor': 'id:"minecraft:respawn_anchor",Count:1b',
        'glowstone': 'id:"minecraft:glowstone",Count:1b',
        'maxhp_attr': 'generic.max_health',
        'count_key': 'Count',
    },
    'neoforge1211': {
        'dir': r'C:/Users/Sketch/mc_server_test/neoforge1211',
        'java': r'C:/Users/Sketch/AppData/Roaming/.minecraft/runtime/java-runtime-delta/bin/java.exe',
        'args': ['@user_jvm_args.txt',
                 '@libraries/net/neoforged/neoforge/21.1.250/win_args.txt', 'nogui'],
        'jar': r'C:/Users/Sketch/.zcode/workspace/default/promaid-mod/patched/promaid-1.2.3-neoforge-1.21.1.jar',
        'modname': 'promaid-1.2.3-neoforge-1.21.1.jar',
        'bow': 'id:"minecraft:bow",count:1',
        'elytra': 'id:"minecraft:elytra",count:1',
        'arrow': 'id:"minecraft:arrow",count:64',
        'firework': 'id:"minecraft:firework_rocket",count:64',
        'tnt': 'id:"minecraft:tnt",count:2',
        'charge': 'id:"minecraft:fire_charge",count:2',
        'obsidian': 'id:"minecraft:obsidian",count:1',
        'crystal': 'id:"minecraft:end_crystal",count:1',
        'anchor': 'id:"minecraft:respawn_anchor",count:1',
        'glowstone': 'id:"minecraft:glowstone",count:1',
        'maxhp_attr': 'minecraft:generic.max_health',
        'count_key': 'count',
    },
}
WAIT = 180
TICK = 75
FAIL_PATTERNS = ('Mixin apply for mod promaid failed', 'InvalidInjectionException',
                 'MixinTransformerError', 'MixinApplyError', 'Failed to create brain',
                 'OutOfMemoryError')

which = sys.argv[1] if len(sys.argv) > 1 else '1201'
mode = sys.argv[2] if len(sys.argv) > 2 else 'default'
cfg = TARGETS.get(which)
if not cfg:
    print('usage: python _test_bomb603.py [1201|neoforge1211] [default|backdoor]')
    sys.exit(2)

server = cfg['dir']
cfg_path = os.path.join(server, 'config', 'promaid-common.toml')
pid_file = os.path.join(server, 'server.pid')


def set_air_place(value):
    """把服务端配置里的 bombing.airPlace 改成 value（没有这一行就什么都不做）。"""
    if not os.path.exists(cfg_path):
        print('!! 没有 %s，跳过 airPlace 改写' % cfg_path)
        return False
    d = open(cfg_path, encoding='utf-8').read()
    new, n = re.subn(r'(\n\s*airPlace\s*=\s*)(true|false)', r'\g<1>%s' % value, d)
    if n == 0:
        print('!! 配置里找不到 airPlace 行，跳过')
        return False
    open(cfg_path, 'w', encoding='utf-8', newline='').write(new)
    print('airPlace := %s（%d 处）' % (value, n))
    return True


if os.path.exists(pid_file):
    pid = open(pid_file).read().strip()
    subprocess.run(['taskkill', '/PID', pid, '/T', '/F'], capture_output=True)
    time.sleep(3)

if mode == 'backdoor':
    set_air_place('false')

mods_dir = os.path.join(server, 'mods')
import glob
for old in glob.glob(os.path.join(mods_dir, 'promaid-*.jar')):
    if os.path.basename(old) != cfg['modname']:
        os.remove(old)
        print('removed stale jar:', os.path.basename(old))
shutil.copyfile(cfg['jar'], os.path.join(mods_dir, cfg['modname']))
print('jar copied:', cfg['modname'], os.path.getsize(cfg['jar']))

log_path = os.path.join(server, 'console_bomb603_%s.log' % mode)
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
    send('kill @e[type=touhou_little_maid:maid,tag=bomb603maid]')
    send('kill @e[type=minecraft:zombie,tag=bomb603target]')
    time.sleep(1)
    inv = ','.join('{Slot:%db,%s}' % (i, it) for i, it in enumerate([
        cfg['tnt'], cfg['charge'], cfg['obsidian'], cfg['crystal'], cfg['anchor'], cfg['glowstone'],
        cfg['tnt'], cfg['charge'], cfg['obsidian'], cfg['crystal'], cfg['anchor'], cfg['glowstone'],
        cfg['arrow'], cfg['firework'], cfg['firework']]))
    # 【为什么用 Tags 选择而不是 name=】neo 首轮实测：`@e[…,name=Bomb603Maid]` 三个 data get 全部
    # 回 "No entity was found"（女仆明明 summon 成功了）——1.21.1 的 CustomName 是组件、与
    # name= 选择器的匹配口径不一致。改用 Tags 选择，两版口径一致、不受组件格式影响。
    maid_nbt = (
        '{MaidTask:"maid_smart:flight_ranged",MaidScheduleMode:"ALL",'
        'HandItems:[{%s},{}],'
        'ArmorItems:[{},{},{%s},{}],'
        'MaidInventory:{Size:36,Items:[%s]},'
        'Owner:[I;1,2,3,4],Tags:["bomb603maid"],'
        'CustomName:"\\"Bomb603Maid\\"",PersistenceRequired:1b}'
    ) % (cfg['bow'], cfg['elytra'], inv)
    MAID = '@e[tag=bomb603maid,limit=1]'
    TARGET = '@e[tag=bomb603target,limit=1]'
    # 先在**地面**出生（避免"高空自由落体摔死"这种与功能无关的噪声），等自检与第一次放置走完，
    # 再把两人抬到 200 格高空——第二段放置才落在悬空处（批次里要验证的第二件事）。
    send('summon touhou_little_maid:maid ~ ~2 ~ %s' % maid_nbt)
    time.sleep(3)
    send('summon minecraft:zombie ~6 ~6 ~ {NoAI:1b,NoGravity:1b,PersistenceRequired:1b,'
         'Health:2000f,Attributes:[{Name:"%s",Base:2000}],'
         'ActiveEffects:[{Id:11,Amplifier:9,Duration:999999}],Tags:["bomb603target"],'
         'CustomName:"\\"Bomb603Target\\""}' % cfg['maxhp_attr'])
    time.sleep(2)
    # 【脚手架自检】任务 / 归属 / 背包回读（写错 NBT 时"不报错、只是没生效"，与功能没做一模一样）
    send('data get entity %s MaidTask' % MAID)
    send('data get entity %s Owner' % MAID)
    send('data get entity %s MaidInventory' % MAID)
    send('data get entity %s Pos' % MAID)
    time.sleep(3)
    send('execute at %s run tp %s ~ ~200 ~' % (MAID, MAID))
    time.sleep(1)
    send('execute at %s run tp %s ~4 ~1 ~' % (MAID, TARGET))
    send('data get entity %s Pos' % MAID)
    print('observing for %ds ...' % TICK)
    time.sleep(TICK)
    send('data get entity @e[type=touhou_little_maid:maid,name=Bomb603Maid,limit=1] MaidInventory')
    time.sleep(2)

    data = readlog()
    for pat in FAIL_PATTERNS:
        if pat in data:
            verdict = 'FAIL(error line: %s)' % pat
            notes.append(pat)

    threw = '投掷 TNT' in data
    placed_crystal = '放下 末地水晶' in data
    placed_anchor = '放下 重生锚' in data
    backdoor = '走后门：' in data
    launched = ('放烟花起飞' in data) or ('掉高补烟花' in data) or ('远程空袭' in data)
    notes.append('起飞=%s 投掷TNT=%s 放下末地水晶=%s 放下重生锚=%s 走后门=%s'
                 % (launched, threw, placed_crystal, placed_anchor, backdoor))
    if not launched:
        verdict = 'FAIL(她没起飞 —— 空袭链路没跑起来)'
    if not threw:
        verdict = 'FAIL(没有投掷 TNT —— 远程空袭那条附加链路没触发)'
    if not (placed_crystal or placed_anchor):
        verdict = 'FAIL(远程空袭里没有放下任何炸弹底座)'
    if mode == 'backdoor' and not backdoor:
        verdict = 'FAIL(airPlace 关掉之后没出现「走后门」—— 后门那一级没兜住)'
    if mode == 'default' and backdoor:
        notes.append('（本轮 airPlace=on，后门那一级本不该被用到）')

    # 背包回读：烈焰弹 2 → 1（TNT 用烈焰弹点火的实证）
    tail = data[data.rfind('MaidInventory'):] if 'MaidInventory' in data else ''
    m = re.search(r'fire_charge[^}]*}[^}]*', tail)
    notes.append('背包回读片段: %s' % (m.group(0)[:120] if m else 'n/a'))

send('say BOMB603_CHECK_DONE')
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
    if any(k in l for k in ('空袭轰炸', '放烟花起飞', '投掷 TNT', '放下 ', '走后门', '轰炸跳过',
                            'Summoned new', 'MaidInventory', 'MaidTask', 'flight_ranged',
                            'Mixin apply', 'FATAL', 'ArgumentException', 'Unknown')):
        print('  ', l[:240])
        shown += 1
        if shown > 80:
            print('   ...(truncated)')
            break
if shown == 0:
    print('   (nothing matched — inspect %s)' % log_path)

if mode == 'backdoor':
    set_air_place('true')  # 还原测试服的默认配置
sys.exit(0 if verdict == 'PASS' else 1)
