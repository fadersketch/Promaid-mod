# -*- coding: utf-8 -*-
"""实测六百一十 的场景验证：她扔的模组 TNT（等价交换爆破新星）那一炸，
**主人/友军也不掉血**（六百〇七 只收走了地形权限，伤害那一半留到这批）。

用法:
    python test_blast610.py 1201

（只跑 1201：等价交换装在那台；neoforge1211 的 mods 里没有任何 TntBlock 子类。）

场景（一尊 Marker 盔甲架当锚点，全部以它为准）：
  · **对照女仆**（不入队、主人 UUID 与轰炸女仆不同）站在 -2 = **阳性对照**：她必须掉血，
    否则说明这一炸压根没落到采样点上（女仆这一类真的会吃这一炸，是她证明了这件事）；
  · **友军女仆**站在 +2（与轰炸女仆同一个计分板队伍 → FriendlyFireGuard.isFriendly 为真）：
  她**一格血都不许掉**，这是本批要的结果；
  · 台上一尊 2000 血木桩（僵尸，抗性 5）当轰炸目标——注意**它带抗性 5 = 免伤，本来就不会掉血**，
    所以它只当靶子、不当判据（六百〇七 那句"木桩 2000 → 1024"其实是它落地时的血量，
    不是被炸掉的；这套 NBT 落到世界里的血量就是 1024，受害女仆也一样——见下面的基线说明）。

【基线为什么取"第一次采样"】`Health:2000f` + `Attributes:[{max_health:2000}]` 这套 NBT 落地的
实际血量是 **1024**（僵尸/女仆都一样）。所以三条判据一律跟各自的第一个采样值比，"掉没掉血"
只看相对自己开头降了多少。

【为什么要两个女仆当对照】只放一个友军女仆的话，"她没掉血"有两种解释：本模组护住了，
或者这一炸本来就打不到女仆。对照女仆就是用来排除后者的。
【为什么对照女仆的主人 UUID 要跟轰炸女仆不一样】`PetImmunityGuard` 有一条
「可驯服且持有者 = 攻击女仆的主人 → 视为主人的宠物 → 伤害取消」；两个女仆要是同一个
主人 UUID，对照女仆会被那条护住、于是对照失效（javap 实证那条要求 maid.getOwner()
非空——测试服没有真玩家，主人解析出来是 null，所以这条本来就不会命中；但仍按不同
UUID 摆，免得日后有人加个假主人进来把这层激活）。
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
        'bow': 'id:"minecraft:bow",Count:1b',
        'elytra': 'id:"minecraft:elytra",Count:1b',
        'arrow': 'id:"minecraft:arrow",Count:64b',
        'firework': 'id:"minecraft:firework_rocket",Count:64b',
        'novas': 'id:"projecte:nova_catalyst",Count:16b',
        'flint': 'id:"minecraft:flint_and_steel",Count:1b',
        'maxhp_attr': 'generic.max_health',
    },
}
WAIT = 180
OBSERVE = 130          # 观测总时长（秒）
SAMPLE_EVERY = 5       # 采样间隔（秒）：爆炸伤害是脉冲式的，间隔太大就只看到一个平值
FAIL_PATTERNS = ('Mixin apply for mod promaid failed', 'InvalidInjectionException',
                 'MixinTransformerError', 'MixinApplyError', 'Failed to create brain',
                 'OutOfMemoryError')
FATAL_HINTS = ('promaid', 'mixin')

TEAM = 't610'
GUARD_MARK = '按「不破坏方块」处理'
THROW_MARK = '投掷 TNT'
HP_FULL = 2000.0
TAGS = ['tnt610b', 'tnt610a', 'tnt610c', 'tnt610z', 'tnt610anchor']
SAMPLES = ['~-2 ~-1 ~-2', '~2 ~-1 ~-2', '~-2 ~-1 ~2', '~2 ~-1 ~2', '~ ~-1 ~']

which = sys.argv[1] if len(sys.argv) > 1 else '1201'
cfg = TARGETS.get(which)
if not cfg:
    print('usage: python test_blast610.py [1201]')
    sys.exit(2)

server = cfg['dir']
pid_file = os.path.join(server, 'server.pid')


def stop_server():
    if os.path.exists(pid_file):
        pid = open(pid_file).read().strip()
        subprocess.run(['taskkill', '/PID', pid, '/T', '/F'], capture_output=True)
        time.sleep(3)


def set_cfg(key, value):
    """改服务端 promaid-common.toml 里 [bombing] 的某个开关（跑之前先摆好）。"""
    p = os.path.join(server, 'config', 'promaid-common.toml')
    if not os.path.exists(p):
        return False
    raw = open(p, 'rb').read()
    try:
        t = raw.decode('utf-8')
    except Exception:
        t = raw.decode('gbk', errors='replace')
    m = re.search(r'^(\s*%s\s*=\s*)(\w+)\s*$' % re.escape(key), t, re.M)
    if not m:
        return False
    new = t[:m.start()] + m.group(1) + value + t[m.end():]
    open(p, 'wb').write(new.encode('utf-8'))
    return True


def maid_nbt(tag, name, owner, extra=''):
    return ('{MaidScheduleMode:"ALL",%sOwner:[I;%s],Tags:["%s"],'
            'CustomName:"\\"%s\\"",PersistenceRequired:1b}'
            % (extra, owner, tag, name))


def run_round():
    log_path = os.path.join(server, 'console_blast610.log')
    log = open(log_path, 'wb')
    p = subprocess.Popen([cfg['java'], '-Xmx3G', '-Dfile.encoding=UTF-8'] + cfg['args'],
                         cwd=server, stdout=log, stderr=subprocess.STDOUT,
                         stdin=subprocess.PIPE,
                         creationflags=subprocess.CREATE_NEW_PROCESS_GROUP | 0x00000008)
    open(pid_file, 'w').write(str(p.pid))
    print('--- pid %d' % p.pid)

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
        for e, t in d.items():
            if '投掷 TNT' in t or '不破坏方块」处理' in t or 'Tnt610Ally' in t:
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
    if not done:
        notes.append('FAIL(server never reached Done)')
    else:
        send('gamerule doMobSpawning false')
        send('gamerule doFireTick false')
        send('difficulty easy')
        send('team remove %s' % TEAM)
        time.sleep(1)
        for _ in range(2):
            for t in TAGS:
                send('kill @e[tag=%s]' % t)
            time.sleep(1)
        # 把历次测试留下的实体一并清掉：留着别的女仆的话，她会把她们当敌人去追，
        # 炸弹落点就跑偏了（第一版实测：她在追一个六〇七 留下的女仆，木桩只被炸了一次）
        for t in ('touhou_little_maid:maid', 'minecraft:zombie', 'minecraft:villager',
                  'projecte:nova_catalyst_primed', 'minecraft:tnt'):
            send('kill @e[type=%s]' % t)
        time.sleep(2)

        A = '@e[tag=tnt610anchor,limit=1]'
        send('summon minecraft:armor_stand ~ ~ ~ {Marker:1b,NoGravity:1b,Invisible:1b,'
             'Tags:["tnt610anchor"],CustomName:"\\"Tnt610Anchor\\""}')
        time.sleep(2)
        send('execute at %s run fill ~-3 ~-1 ~-3 ~3 ~-1 ~3 minecraft:stone' % A)
        time.sleep(2)
        send('data get entity %s Pos' % A)
        time.sleep(1)

        # ① 友军女仆（+2）与 ② 对照女仆（-2）：都别动、都硬；两者主人 UUID 不同（免得走
        #    PetImmunityGuard 那条"主人的宠物"）
        vic = ('NoAI:1b,NoGravity:1b,'
               'Health:2000f,Attributes:[{Name:"%s",Base:2000}],' % cfg['maxhp_attr'])
        send('execute at %s run summon touhou_little_maid:maid ~2 ~1 ~ %s'
             % (A, maid_nbt('tnt610a', 'Tnt610Ally', '9,9,9,9', vic)))
        time.sleep(1)
        send('execute at %s run summon touhou_little_maid:maid ~-2 ~1 ~ %s'
             % (A, maid_nbt('tnt610c', 'Tnt610Control', '8,8,8,8', vic)))
        time.sleep(1)
        # ③ 木桩：抗性 5、整场不死（这一炸的阳性对照）
        send('execute at %s run summon minecraft:zombie ~ ~1 ~ {NoAI:1b,NoGravity:1b,'
             'PersistenceRequired:1b,Health:2000f,Attributes:[{Name:"%s",Base:2000}],'
             'ActiveEffects:[{Id:11,Amplifier:9,Duration:999999}],Tags:["tnt610z"],'
             'CustomName:"\\"Tnt610Target\\""}' % (A, cfg['maxhp_attr']))
        time.sleep(2)
        # ④ 队伍：**先把友军入队，再放轰炸女仆**——她一起手就是一枚新星（引信只有 2 秒），
        #    晚一步入队的话，第一炸就会落在"还不是友军"的她身上（第一版实测就是这样：
        #    她掉到 974 血，之后才被护住）
        send('team add %s' % TEAM)
        time.sleep(1)
        send('team join %s @e[tag=tnt610a]' % TEAM)
        time.sleep(1)
        # ⑤ 轰炸女仆（+5）最后放：远程空袭 + 新星 + 点火料 + 弓/鞘翅（与六百〇七 同一套 NBT）
        send('execute at %s run summon touhou_little_maid:maid ~5 ~1 ~ %s'
             % (A, maid_nbt(
                 'tnt610b', 'Tnt610Bomber', '1,2,3,4',
                 'MaidTask:"maid_smart:flight_ranged",'
                 'HandItems:[{%s},{}],ArmorItems:[{},{},{%s},{}],'
                 'MaidInventory:{Size:36,Items:[{Slot:0b,%s},{Slot:1b,%s},{Slot:2b,%s},{Slot:3b,%s}]},'
                 'Health:2000f,Attributes:[{Name:"%s",Base:2000}],'
                 % (cfg['bow'], cfg['elytra'], cfg['novas'], cfg['flint'], cfg['arrow'],
                    cfg['firework'], cfg['maxhp_attr']))))
        # 她入队这一步必须紧跟召唤（同一秒内），别让她在"还没入队"的那一两秒里扔出第一枚
        send('team join %s @e[tag=tnt610b]' % TEAM)
        time.sleep(2)
        send('team list %s' % TEAM)
        send('data get entity @e[tag=tnt610b,limit=1] MaidTask')
        send('data get entity @e[tag=tnt610b,limit=1] MaidInventory')
        time.sleep(2)
        for i, rel in enumerate(SAMPLES):
            send('execute at %s run execute if block %s minecraft:stone run say BASE_%d' % (A, rel, i))
        time.sleep(3)

        # ── 观测：每 10 秒把四条命 + 两个位置读一遍（爆炸伤害是脉冲式的，只读首尾会漏）──
        print('observing for %ds ...' % OBSERVE)
        for k in range(OBSERVE // SAMPLE_EVERY):
            for t in ('tnt610z', 'tnt610a', 'tnt610c', 'tnt610b'):
                send('data get entity @e[tag=%s,limit=1] Health' % t)
            send('data get entity @e[tag=tnt610a,limit=1] Pos')
            send('data get entity @e[tag=tnt610c,limit=1] Pos')
            time.sleep(SAMPLE_EVERY)
        for i, rel in enumerate(SAMPLES):
            send('execute at %s run execute if block %s minecraft:stone run say KEEP_%d' % (A, rel, i))
        time.sleep(3)

    data = readlog()
    for pat in FAIL_PATTERNS:
        if pat in data:
            notes.append('FAIL(error line: %s)' % pat)
    for l in data.splitlines():
        if 'FATAL' in l and any(h in l.lower() for h in FATAL_HINTS):
            notes.append('FATAL %s' % l[:140])
            break

    res = {'notes': notes, 'data': data}
    res['guard'] = [l for l in data.splitlines() if GUARD_MARK in l]
    res['throws'] = [l for l in data.splitlines() if THROW_MARK in l]
    res['keep'] = sorted({int(m.group(1)) for l in data.splitlines()
                          for m in [re.search(r'KEEP_(\d+)', l)] if m})
    res['base'] = sorted({int(m.group(1)) for l in data.splitlines()
                          for m in [re.search(r'BASE_(\d+)', l)] if m})

    # 血量序列：`data get entity … Health` 的**回显不带 "Health" 这个词**（六百〇七 踩过），
    # 只能按自定义名筛行 + 抓 "entity data: <数>f"。
    kinds = {'Tnt610Target': 'zombie', 'Tnt610Ally': 'ally',
             'Tnt610Control': 'control', 'Tnt610Bomber': 'bomber'}
    hp = {v: [] for v in kinds.values()}
    pos = {v: [] for v in ('ally', 'control')}
    for l in data.splitlines():
        for name, kind in kinds.items():
            if name in l and 'entity data:' in l:
                m = re.search(r'entity data:\s*([\d.]+)f', l)
                if m:
                    hp[kind].append(float(m.group(1)))
                break
        for name, kind in (('Tnt610Ally', 'ally'), ('Tnt610Control', 'control')):
            if name in l and 'Pos' not in l:
                continue
            if name in l and re.search(r'\[[-0-9.]+d?,\s*[-0-9.]+d?,\s*[-0-9.]+d?\]', l):
                m = re.search(r'\[([-0-9.]+)d?,\s*([-0-9.]+)d?,\s*([-0-9.]+)d?\]', l)
                if m:
                    pos[kind].append(tuple(float(x) for x in m.groups()))
    res['hp'] = hp
    res['pos'] = pos

    for k, v in hp.items():
        if v:
            notes.append('%s: 血量 %d 次采样 %s' % (k, len(v), v))
        else:
            notes.append('%s: 一次血都没读到（名字筛漏了？）' % k)
    for k, v in pos.items():
        if len(v) >= 2:
            d = max(abs(a - b) for a, b in zip(v[0], v[-1]))
            notes.append('%s: 位移 %.2f 格（首→尾）' % (k, d))
    notes.append('守卫日志（模组 TNT 那一炸）%d 行 / 投掷行 %d 行 / 基线台 %s / 战后台 %s'
                 % (len(res['guard']), len(res['throws']), res['base'], res['keep']))
    for l in res['data'].splitlines():
        if 'members' in l and TEAM in l:
            notes.append('队伍: %s' % l.split('] ')[-1][:160])
            break
    if res['throws']:
        notes.append('投掷示例: %s' % res['throws'][0][:200])
    if res['guard']:
        notes.append('守卫示例: %s' % res['guard'][0][:200])

    send('stop')
    for _ in range(40):
        time.sleep(1)
        if p.poll() is not None:
            break
    if p.poll() is None:
        subprocess.run(['taskkill', '/PID', str(p.pid), '/T', '/F'], capture_output=True)
    return res


stop_server()
for old in [f for f in os.listdir(os.path.join(server, 'mods')) if f.startswith('promaid-')]:
    if old != cfg['modname']:
        os.remove(os.path.join(server, 'mods', old))
shutil.copyfile(cfg['jar'], os.path.join(server, 'mods', cfg['modname']))
print('jar copied:', cfg['modname'], os.path.getsize(cfg['jar']))

# 摆开关：TNT 投掷开、不破坏方块（地形不掺和）、不伤主人/友军（默认口径）
for k, v in (('tnt', 'true'), ('breakBlocks', 'false'), ('hurtFriendly', 'false')):
    print('config %s=%s ->' % (k, v), set_cfg(k, v))

r = run_round()
notes = r['notes']
verdict = 'PASS'

hp = r['hp']
# 【基线取"第一次采样"，不是 NBT 里写的 2000】实测踩到：`Health:2000f` + `Attributes:[{max_health:2000}]`
# 这套 NBT 落到世界里的血量是 **1024**（木桩僵尸与受害女仆都是——六百〇七 那句"木桩 2000 → 1024"
# 其实也把这个当成了伤害）。所以三条判据一律跟**各自的第一个采样值**比：
# 掉没掉血只看"相对她自己开头降了多少"，跟 NBT 里写了多少无关。
def first(k):
    return hp[k][0] if hp[k] else None


def lowest(k):
    return min(hp[k]) if hp[k] else None


if not r['throws']:
    verdict = 'FAIL(她一次都没扔出新星 —— 场景没搭起来)'
elif first('control') is None or first('ally') is None:
    verdict = 'FAIL(没读到受害女仆的血量 —— 判据不成立)'
elif lowest('control') >= first('control') - 0.5:
    verdict = ('FAIL(对照女仆一格血没掉（%s）—— 这一炸没有打到这两个采样点上，判据不成立)'
               % hp['control'])
    notes.append('木桩血量（仅供参考）%s' % hp['zombie'])
else:
    notes.append('阳性对照（对照女仆，未入队）：%.1f → %.1f 血 —— 女仆这类实体确实会吃这一炸，'
                 '所以友军"不掉血"是护住的、不是打不到' % (first('control'), lowest('control')))
    notes.append('木桩血量（仅供参考，它带抗性 5 = 免伤，本来就不该掉血）：%s' % hp['zombie'])
    if first('ally') is not None:
        if lowest('ally') < first('ally') - 0.5:
            verdict = 'FAIL(友军女仆掉血：%.1f → %.1f —— 主人/友军没被护住)' % (first('ally'), lowest('ally'))
        else:
            notes.append('友军女仆（已入队）：全程 %.1f 血，一格没掉' % first('ally'))
    if hp['bomber']:
        notes.append('轰炸女仆自己：%.1f → %.1f 血（她连炸十几发，基本没被自己的新星伤到；'
                     '偶尔掉个位数是别处来的伤害）' % (first('bomber'), lowest('bomber')))

print('verdict:', verdict)
for n in notes:
    print('note:', n)
print('--- 关键日志行 ---')
shown = 0
for l in r['data'].splitlines():
    if any(k in l for k in ('不破坏方块」处理', '投掷 TNT', '放烟花起飞', 'BASE_', 'KEEP_',
                            'entity data:', 'Added Tnt610', 'Joined', 'Mixin apply', 'FATAL',
                            'probe610', 'members')):
        print('  ', l[:230])
        shown += 1
        if shown > 80:
            print('   ...(truncated)')
            break
if shown == 0:
    print('   (nothing matched)')

sys.exit(0 if verdict == 'PASS' else 1)
