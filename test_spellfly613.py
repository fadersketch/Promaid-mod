# -*- coding: utf-8 -*-
"""实测六百一十三 的场景验证：飞行跟随的"启动"并入位移法术（门禁口径 + 第三条腿的接线）。

用法:
    python test_spellfly613.py 1201

需求原文（用户原话）：
    "位移法术也可以加入到飞行跟随的启动中"

【本批改了什么（要被这条用例证到的）】
    · 启动门禁的"能飞的道具"由两选一（烟花 / 羽扇）扩成三选一（加"能上天的位移法术"）——
      判据与空袭三件套的第三条同一份定义（MaidFlightKit.hasFlightPropellant）；
    · 那一口推进的顺序 = 扇子 → 烟花 → 位移法术（法术只在烟花真拿不出来时接手）；
    · 起飞那行日志多一个字段「位移法术=开/关」，法术补推单独一行「放位移法术追主人（…）」；
    · 三种道具都没有时，跳过日志（「飞行跟随跳过」）写成
      「背包里没有「可以飞行的道具」（烟花火箭 / 孔雀羽扇 / 能上天的位移法术，三选一）」。

【这条用例能验到哪一层（务必如实看）】
    测试服务器里**没有装《车万女仆：万法皆通》+ 铁魔法**，所以"她真的放出了升腾/烈焰冲锋"
    这一步在本机**结构上验不了**（法术链路是反射软依赖，模组不在场时 findClimbSpell 恒 null）。
    本用例证的是三件能用真服务端证到的事：
      ① **门禁口径真的改了**：只带鞘翅、不带任何飞行动具的女仆，跳过日志是新文案
         （旧文案是"背包里既没有烟花火箭、也没有孔雀羽扇"），而且她**没有起飞**；
      ② **老链路一字没坏**：同一轮里另一只带鞘翅 + 64 烟花的**必须照常起飞并补烟花**
         （boost 被重写过——扇子 → 烟花 → 法术三级，烟花那一级必须原样）；
      ③ **新增的日志字段真的打出来了**：起飞行带「位移法术=开」。
    "法术那一口"本身的实机验收入口写在手册里（日志搜「放位移法术追主人」），
    与"空袭·位移"那一套同源，属于同一类"要装第三方模组才能端到端触发"的功能。

场景（与 test_flyfollow608.py 同一套脚手架，只把一只女仆拆成两只）：
    · 锚点 + 5x29 石头台 + 台面上方 5 格清空 → "女仆↔目标之间没有方块阻拦"必然成立；
    · 女仆 A（无任何飞行动具）：胸甲槽空、背包里**只有鞘翅** → 期望"不起飞 + 跳过文案"；
    · 女仆 B（带烟花）：胸甲槽空、背包里鞘翅 + 64 烟花 → 期望"照常起飞 + 补烟花"；
    · 两只各配一尊 NoAI 无重力村民当"替代主人"（22 / 24 格外 > 默认触发距离 5），
      用 /maid_smart flyfollow <目标> <女仆> 点名指定（不给女仆参数会挂到"最近的"那只）。
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
        'elytra': 'id:"minecraft:elytra",Count:1b',
        'firework': 'id:"minecraft:firework_rocket",Count:64b',
        'maxhp_attr': 'generic.max_health',
    },
}

WAIT = 180       # 启动等待上限（秒）
MID_PROBE = 1    # 起飞后多少秒探一次
ARRIVE_WAIT = 6
OBSERVE = 50     # 总观察窗口（秒）
FAIL_PATTERNS = ('Mixin apply for mod promaid failed', 'InvalidInjectionException',
                 'MixinTransformerError', 'MixinApplyError', 'Failed to create brain',
                 'OutOfMemoryError')
FATAL_HINTS = ('promaid', 'mixin')

NAME_A = 'Fly613NoFuel'      # 只带鞘翅：该飞不起来
NAME_B = 'Fly613Fuel'        # 鞘翅 + 烟花：该照常飞
TARGET_NAME = 'Fly613Target'
MARK_TAKEOFF = '背上鞘翅追过去'
MARK_BOOST = '补一枚烟花追主人'
MARK_SKIP = '飞行跟随跳过'
MARK_NOFUEL = '背包里没有「可以飞行的道具」'
CAT = '飞行跟随'

which = sys.argv[1] if len(sys.argv) > 1 else '1201'
cfg = TARGETS.get(which)
if not cfg:
    print('usage: python test_spellfly613.py [1201]')
    sys.exit(2)

server = cfg['dir']
pid_file = os.path.join(server, 'server.pid')
CONFIG = os.path.join(server, 'config', 'promaid-common.toml')

MAID_A_OFF = '~ ~ ~'       # 女仆 A 站位（锚点）
MAID_B_OFF = '~ ~ ~3'      # 女仆 B 站位（同一台面）
TARGET_A_OFF = '~ ~ ~22'   # A 的目标（22 格外）
TARGET_B_OFF = '~ ~ ~27'   # B 的目标（24 格外）


def stop_server():
    if os.path.exists(pid_file):
        pid = open(pid_file).read().strip()
        subprocess.run(['taskkill', '/PID', pid, '/T', '/F'], capture_output=True)
        time.sleep(3)


def set_bridge_flags(**kv):
    """改 [flightFollow] 小节里的开关。**六百一十五 起它从 [bridge] 搬出来了**，键名也换了
    （flightFollow→enabled、flightFollowDist→dist、flightFollowFirework→firework、
    flightFollowElytra→elytra）；键不存在就插在该小节标题那一行后面。"""
    raw = open(CONFIG, 'rb').read()
    try:
        t = raw.decode('utf-8')
    except Exception:
        t = raw.decode('gbk', errors='replace')
    lines = t.split('\n')
    out = []
    toc = None
    hit = set()
    for line in lines:
        stripped = line.strip()
        if stripped == '[flightFollow]':
            toc = len(out)
        out.append(line)
        for k, v in kv.items():
            m = re.match(r'^(\s*)%s\s*=\s*\S+\s*$' % re.escape(k), line)
            if m:
                out[-1] = '%s%s = %s' % (m.group(1), k, v)
                hit.add(k)
    if toc is not None:
        missing = [(k, v) for k, v in kv.items() if k not in hit]
        for k, v in missing:
            out.insert(toc + 1, '\t%s = %s' % (k, v))
            hit.add(k)
    open(CONFIG, 'wb').write('\n'.join(out).encode('utf-8'))
    return sorted(hit)


def run_round():
    log_path = os.path.join(server, 'console_spell613.log')
    log = open(log_path, 'wb')
    p = subprocess.Popen([cfg['java'], '-Xmx3G', '-Dfile.encoding=UTF-8'] + cfg['args'],
                         cwd=server, stdout=log, stderr=subprocess.STDOUT,
                         stdin=subprocess.PIPE,
                         creationflags=subprocess.CREATE_NEW_PROCESS_GROUP | 0x00000008)
    open(pid_file, 'w').write(str(p.pid))
    print('--- round: pid %d' % p.pid)

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
        d = {e: raw.decode(e, 'replace') for e in ('utf-8', 'gbk', 'cp936', 'latin-1')}
        for t in d.values():
            if MARK_SKIP in t or CAT in t:
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
    if not done:
        send('stop')
        for _ in range(40):
            time.sleep(1)
            if p.poll() is not None:
                break
        if p.poll() is None:
            subprocess.run(['taskkill', '/PID', str(p.pid), '/T', '/F'], capture_output=True)
        return {'notes': ['FAIL(server never reached Done)'], 'data': ''}

    send('gamerule doMobSpawning false')
    send('difficulty easy')
    # 【清场】前几批测试留下的同名女仆/敌对生物都要扫掉：同名女仆会抢 flyfollow 的"最近一只"，
    # 敌对生物会落在"威胁半径 8 格"里让她永远不起飞（608 那批实测踩过两次）。
    for _ in range(2):
        for base in ('fly613maida', 'fly613maidb', 'fly613targeta', 'fly613targetb',
                     'fly613anchor', 'fly608maid', 'fly608target', 'fly608anchor'):
            for suf in ('', 'a', 'b', 'c'):
                send('kill @e[tag=%s%s]' % (base, suf))
        time.sleep(1)
    for mob in ('zombie', 'husk', 'drowned', 'zombie_villager', 'skeleton', 'stray',
                'creeper', 'spider', 'cave_spider', 'witch', 'slime', 'phantom'):
        send('kill @e[type=minecraft:%s]' % mob)
    time.sleep(2)

    # ① 锚点 + 台面（锚点下方 1 格 = 地表那一层）5x29 + 上方 5 格清成空气
    send('summon minecraft:armor_stand ~ ~ ~ {Marker:1b,NoGravity:1b,Invisible:1b,'
         'Tags:["fly613anchor"]}')
    time.sleep(2)
    A = '@e[tag=fly613anchor,limit=1]'
    send('execute at %s run fill ~-2 ~-1 ~-4 ~2 ~-1 ~30 minecraft:stone' % A)
    time.sleep(1)
    send('execute at %s run fill ~-2 ~ ~-4 ~2 ~4 ~30 minecraft:air' % A)
    time.sleep(2)

    # ② 女仆 A：**只有鞘翅**（没有任何飞行动具）→ 该"不起飞 + 新跳过文案"
    maid_a = ('{MaidScheduleMode:"ALL",HandItems:[{},{}],ArmorItems:[{},{},{},{}],'
              'MaidInventory:{Size:36,Items:[{Slot:0b,%s}]},'
              'Owner:[I;1,2,3,4],Tags:["fly613maida"],CustomName:"\\"%s\\"",PersistenceRequired:1b}'
              % (cfg['elytra'], NAME_A))
    send('execute at %s run summon touhou_little_maid:maid %s %s' % (A, MAID_A_OFF, maid_a))
    time.sleep(2)
    # ③ 女仆 B：鞘翅 + 64 烟花（旧链路回归）
    maid_b = ('{MaidScheduleMode:"ALL",HandItems:[{},{}],ArmorItems:[{},{},{},{}],'
              'MaidInventory:{Size:36,Items:[{Slot:0b,%s},{Slot:1b,%s}]},'
              'Owner:[I;1,2,3,4],Tags:["fly613maidb"],CustomName:"\\"%s\\"",PersistenceRequired:1b}'
              % (cfg['elytra'], cfg['firework'], NAME_B))
    send('execute at %s run summon touhou_little_maid:maid %s %s' % (A, MAID_B_OFF, maid_b))
    time.sleep(3)

    MA = '@e[tag=fly613maida,limit=1]'
    MB = '@e[tag=fly613maidb,limit=1]'

    # ④ 两尊替代主人（NoAI 无重力村民：非敌对——她的"附近有怪就不飞"会把敌对目标当威胁）
    for ttag, pos, name in (('fly613targeta', TARGET_A_OFF, TARGET_NAME + 'A'),
                            ('fly613targetb', TARGET_B_OFF, TARGET_NAME + 'B')):
        send('execute at %s run summon minecraft:villager %s {NoAI:1b,NoGravity:1b,Silent:1b,'
             'Invulnerable:1b,PersistenceRequired:1b,Health:2000f,'
             'Attributes:[{Name:"%s",Base:2000}],Tags:["%s"],'
             'CustomName:"\\"%s\\""}' % (A, pos, cfg['maxhp_attr'], ttag, name))
        time.sleep(2)

    # ⑤ 脚手架自检
    send('execute at %s run execute if entity @e[tag=fly613targeta,limit=1] run say SETUP_A' % A)
    send('execute at %s run execute if entity @e[tag=fly613targetb,limit=1] run say SETUP_B' % A)
    time.sleep(2)

    # ⑥ 各挂各的"替代主人"（点名指定女仆，见 608 的注释）
    send('maid_smart flyfollow @e[tag=fly613targeta,limit=1] %s' % MA)
    send('maid_smart flyfollow @e[tag=fly613targetb,limit=1] %s' % MB)
    time.sleep(MID_PROBE)
    # ⑦ 飞行中探针（A 该原地不动、B 该已经在飞）
    send('data get entity %s Pos' % MA)
    send('data get entity %s Pos' % MB)
    time.sleep(max(1, OBSERVE - MID_PROBE - ARRIVE_WAIT))
    send('execute at %s run execute if entity @e[tag=fly613targetb,limit=1,distance=..12] run say ARRIVED_B' % MB)
    send('execute at %s run execute if entity @e[tag=fly613targeta,limit=1,distance=..12] run say ARRIVED_A' % MA)
    time.sleep(3)

    data = readlog()
    notes = []
    for pat in FAIL_PATTERNS:
        if pat in data:
            notes.append('FAIL(error line: %s)' % pat)
    for l in data.splitlines():
        if 'FATAL' in l and any(h in l.lower() for h in FATAL_HINTS):
            notes.append('FATAL %s' % l[:140])
            break

    lines = data.splitlines()
    mine_a = [l for l in lines if (CAT in l or MARK_SKIP in l) and NAME_A in l]
    mine_b = [l for l in lines if CAT in l and NAME_B in l]
    skip_a = [l for l in mine_a if MARK_SKIP in l]
    takeoff_a = [l for l in mine_a if MARK_TAKEOFF in l]
    takeoff_b = [l for l in mine_b if MARK_TAKEOFF in l]
    boost_b = [l for l in mine_b if MARK_BOOST in l]
    arrived_b = 'ARRIVED_B' in data

    notes.append('A（无飞行动具）跳过行 %d 条 / 起飞 %d 条 ; B（带烟花）起飞 %d 条 / 补烟花 %d 条'
                 % (len(skip_a), len(takeoff_a), len(takeoff_b), len(boost_b)))
    for label, lst in (('A 跳过', skip_a), ('A 起飞', takeoff_a),
                       ('B 起飞', takeoff_b), ('B 补烟花', boost_b)):
        if lst:
            notes.append('%s 示例: %s' % (label, lst[0][-200:]))

    send('stop')
    for _ in range(40):
        time.sleep(1)
        if p.poll() is not None:
            break
    if p.poll() is None:
        subprocess.run(['taskkill', '/PID', str(p.pid), '/T', '/F'], capture_output=True)

    return {'notes': notes, 'data': data,
            'skip_a': skip_a, 'takeoff_a': takeoff_a, 'takeoff_b': takeoff_b,
            'boost_b': boost_b, 'arrived_b': arrived_b}


stop_server()
for old in [f for f in os.listdir(os.path.join(server, 'mods')) if f.startswith('promaid-')]:
    if old != cfg['modname']:
        os.remove(os.path.join(server, 'mods', old))
shutil.copyfile(cfg['jar'], os.path.join(server, 'mods', cfg['modname']))
print('jar copied:', cfg['modname'], os.path.getsize(cfg['jar']))

print('config:', set_bridge_flags(enabled='true', firework='true',
                                  elytra='true', dist='16.0'))
r = run_round()

verdict = 'PASS'
if not r['data']:
    verdict = 'FAIL(服务端没起来)'
elif not r['skip_a']:
    verdict = 'FAIL(A 女仆（只带鞘翅）没有跳过日志 —— 门禁没走到"没有可以飞行的道具"这一条)'
elif not any(MARK_NOFUEL in l for l in r['skip_a']):
    verdict = 'FAIL(A 跳过文案不是本批的新文案（应含「背包里没有「可以飞行的道具」」)：%s' % (
        r['skip_a'][0][-200:])
elif r['takeoff_a']:
    verdict = 'FAIL(A 女仆（不带任何飞行动具）居然起飞了 —— 门禁被放宽过头)'
elif not r['takeoff_b']:
    verdict = 'FAIL(B 女仆（鞘翅 + 烟花）没有起飞 —— 重写后的 boost 把烟花那一支弄坏了)'
elif '位移法术=开' not in r['takeoff_b'][0]:
    verdict = 'FAIL(B 起飞行没有本批新增的「位移法术=开」字段：%s' % r['takeoff_b'][0][-200:]
elif not r['boost_b']:
    verdict = 'FAIL(B 起飞了但没有补烟花日志 —— 烟花推进链路没走通)'

print('verdict:', verdict)
for n in r['notes']:
    print('note:', n)
print('--- 关键日志行 ---')
shown = 0
for l in r['data'].splitlines():
    if any(k in l for k in (CAT, MARK_SKIP, 'entity data:', 'ARRIVED_', 'SETUP_', 'flyfollow',
                            'Mixin apply', 'FATAL')):
        print('  ', l[:230])
        shown += 1
        if shown > 45:
            print('   ...(truncated)')
            break
if shown == 0:
    print('   (nothing matched)')

sys.exit(0 if verdict == 'PASS' else 1)
