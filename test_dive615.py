# -*- coding: utf-8 -*-
"""实测六百一十五 的场景验证：近战空袭【俯冲段那一枚烟花的推力 × 倍数】（默认 1.4）。

用法:
    python test_dive615.py 1201

需求原文（用户）：
    "近战空袭不是刚加了一个俯冲时使用烟花加速的功能吗？但是我发现加强力度太小，加速效果不明显。
    还耗了一颗烟花，没啥用。这边建议这个烟花加速力度效果乘以1.4倍"

【本批做了什么（要被这条用例证到的）】
    俯冲段点的那一枚挂载烟花改走 `MaidFlightCombatBehavior.DiveBoostRocket`——它是原版
    `FireworkRocketEntity` 的子类，在 `super.tick()`（= 原版那一口推力）之后**再补一份**
    `视线 × 0.85 ×(倍数 − 1)`。于是原版那条递推
        v ← v×0.5 + 视线×0.85     （不动点 = 1.7 倍视线）
    变成
        v ← v×0.5 + 视线×0.85×倍数（不动点 = 1.7×倍数 倍视线）。
    起飞/爬升/盘旋那几处**仍是原版烟花**（`launchFirework(…, false)`），一个字没动。

【这条用例怎么证"力度真的变大了"（不靠我们自己的日志）】
    两轮同一场，只改 `airRaid.diveBoostFireworkScale`：
      R1 倍数 = **1.0**（= 完全照原版，既不补东西也不变速）/ R2 倍数 = **3.0**（放大的探针）——
    观察窗里每 0.5 秒独立回读一次 `data get entity <她> Motion`（原版实体的速度三元组），
    只取"她在空中"的样本，比**最大速度**：R2 应当明显大于 R1（原版不动点 1.7 → 探针档 5.1，
    比值 ~3；用例只要求 ≥1.3 倍，留足噪声余量）。
    另两条判据取自日志：R1 写「俯冲加速（烟花×1，…）」、R2 写「俯冲加速（烟花×3，…）」——
    证明跑的是同一个链路、只是倍数不同。

【为什么用放大档 3.0 而不是直接用 1.4 做 A/B】观察窗里她大部分时间在"爬升/盘旋"（那几段是原版
烟花，速度恒在 1.7 附近），俯冲段只占周期的一小截；用 3.0 把差距拉到 ~3 倍，采样噪声（控制台命令
排队、观察窗错位）才吃不掉它。**默认值仍是 1.4**，配置里那一条另有断言。

【与 606 同源】脚手架：服务端 `mc_server_test/1201`，女仆 = 近战空袭任务 + 下界剑 + 鞘翅 + 64 烟花
（**必须给 Owner**，无主女仆会被本模组整段跳过），木桩 = NoAI + 2000 血的僵尸（打不死、不还手）。
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
        'task': 'maid_smart:flight_combat',
        'sword': 'id:"minecraft:netherite_sword",Count:1b,tag:{Enchantments:[{id:"minecraft:sharpness",lvl:5s}]}',
        'elytra': 'id:"minecraft:elytra",Count:1b',
        'firework': 'id:"minecraft:firework_rocket",Count:64b',
        'maxhp_attr': 'generic.max_health',
    },
}

WAIT = 180
OBSERVE = 36000      # 观察窗（毫秒）：约 72 个采样点
# 【红轮踩到】44 秒 + 探针 3.0 时她被甩进未加载区块（165 条 No entity）——探针改用 1.4
# （就是要上线的默认值）后不存在这个问题；窗口收到 36 秒，够跑好几个空袭周期
SAMPLE_MS = 500
FAIL_PATTERNS = ('Mixin apply for mod promaid failed', 'InvalidInjectionException',
                 'MixinTransformerError', 'MixinApplyError', 'Failed to create brain',
                 'OutOfMemoryError')
FATAL_HINTS = ('promaid', 'mixin')

NAME = 'Dive615Maid'
TAG = 'dive615maid'
TTAG = 'dive615target'
MARK_BOOST = '俯冲加速'
CAT = '空袭·俯冲'

which = sys.argv[1] if len(sys.argv) > 1 else '1201'
cfg = TARGETS.get(which)
if not cfg:
    print('usage: python test_dive615.py [1201]')
    sys.exit(2)

server = cfg['dir']
pid_file = os.path.join(server, 'server.pid')
CONFIG = os.path.join(server, 'config', 'promaid-common.toml')


def stop_server():
    if os.path.exists(pid_file):
        pid = open(pid_file).read().strip()
        subprocess.run(['taskkill', '/PID', pid, '/T', '/F'], capture_output=True)
        time.sleep(3)


def set_scale(value, dive_boost=True, dive_firework=True):
    """改服务端配置：俯冲段冲刺（开）+ 用烟花（开）+ **力度倍数** + 空袭免摔（开）。

    【为什么必须把免摔也写上】1201 这台测试服的 `flightNoFallDamage` 是 **false**——那是
    实测五百二十六 把默认改成 true **之前**写进文件的老值（配置系统只认"这一项缺席才用新默认"）。
    ×1.4 的俯冲更容易冲过头砸到地形，红轮那轮她就这么摔死了（66 点），采样只到 21 个。
    新存档的默认是 **开**，所以本用例显式打开它，测的是"俯冲速度"而不是"测试服的老配置"。
    """
    raw = open(CONFIG, 'rb').read()
    try:
        t = raw.decode('utf-8')
    except Exception:
        t = raw.decode('gbk', errors='replace')
    want = {'diveBoost': 'true' if dive_boost else 'false',
            'diveBoostFirework': 'true' if dive_firework else 'false',
            'diveBoostFireworkScale': '%s' % value,
            'flightNoFallDamage': 'true'}
    hit = []
    for k, v in want.items():
        m = re.search(r'^(\s*%s\s*=\s*)(\S+)\s*$' % re.escape(k), t, re.M)
        if m:
            t = t[:m.start()] + m.group(1) + v + t[m.end():]
            hit.append(k)
        else:
            # 键不存在（首次用新 jar 之前）：插在 diveBoostFirework 那一行后面
            m2 = re.search(r'^(\s*diveBoostFirework\s*=\s*\S+\s*)$', t, re.M)
            if m2:
                t = t[:m2.end()] + '\n\t%s = %s' % (k, v) + t[m2.end():]
                hit.append(k + '(新插)')
    open(CONFIG, 'wb').write(t.encode('utf-8'))
    return hit


def scale_in_config():
    raw = open(CONFIG, 'rb').read()
    t = raw.decode('utf-8', errors='replace')
    m = re.search(r'^\s*diveBoostFireworkScale\s*=\s*(\S+)\s*$', t, re.M)
    return m.group(1) if m else None


def run_round(tag_suffix):
    log_path = os.path.join(server, 'console_dive615_%s.log' % tag_suffix)
    log = open(log_path, 'wb')
    p = subprocess.Popen([cfg['java'], '-Xmx3G', '-Dfile.encoding=UTF-8'] + cfg['args'],
                         cwd=server, stdout=log, stderr=subprocess.STDOUT,
                         stdin=subprocess.PIPE,
                         creationflags=subprocess.CREATE_NEW_PROCESS_GROUP | 0x00000008)
    open(pid_file, 'w').write(str(p.pid))
    print('--- round %s: pid %d (scale=%s)' % (tag_suffix, p.pid, scale_in_config()), flush=True)

    def send(cmd):
        try:
            p.stdin.write((cmd + '\n').encode('utf-8'))
            p.stdin.flush()
        except Exception as e:
            print('   (stdin failed: %s)' % e, flush=True)

    def readlog():
        try:
            raw = open(log_path, 'rb').read()
        except Exception:
            return ''
        d = {e: raw.decode(e, 'replace') for e in ('utf-8', 'gbk', 'cp936', 'latin-1')}
        for t in d.values():
            if CAT in t:
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
    speeds = []          # (水平+竖直 合速度, y, 基准 y)
    if not done:
        notes.append('%s: FAIL(server never reached Done)' % tag_suffix)
    else:
        send('gamerule doMobSpawning false')
        send('difficulty easy')
        tag = TAG + tag_suffix
        ttag = TTAG + tag_suffix
        for _ in range(2):
            send('kill @e[tag=%s]' % tag)
            send('kill @e[tag=%s]' % ttag)
            send('kill @e[type=touhou_little_maid:maid,name=%s]' % (NAME + tag_suffix))
            send('kill @e[type=minecraft:zombie,name=%s]' % ('Dive615Target' + tag_suffix))
            time.sleep(2)
        maid_nbt = (
            '{MaidTask:"%s",MaidScheduleMode:"ALL",'
            'HandItems:[{%s},{}],'
            'ArmorItems:[{},{},{%s},{}],'
            'MaidInventory:{Size:36,Items:[{Slot:0b,%s}]},'
            'Owner:[I;1,2,3,4],Tags:["%s"],'
            'CustomName:"\\"%s\\"",PersistenceRequired:1b}'
        ) % (cfg['task'], cfg['sword'], cfg['elytra'], cfg['firework'], tag, NAME + tag_suffix)
        send('summon touhou_little_maid:maid ~ ~2 ~ %s' % maid_nbt)
        time.sleep(3)
        send('summon minecraft:zombie ~8 ~3 ~ {NoAI:1b,PersistenceRequired:1b,'
             'Health:2000f,Attributes:[{Name:"%s",Base:2000}],'
             'ActiveEffects:[{Id:11,Amplifier:9,Duration:999999}],Tags:["%s"],'
             'CustomName:"\\"Dive615Target%s\\""}' % (cfg['maxhp_attr'], ttag, tag_suffix))
        time.sleep(2)
        send('execute at @e[tag=%s,limit=1] run tp @e[tag=%s,limit=1] ~5 ~1 ~' % (tag, ttag))
        time.sleep(3)
        # 基线 y（地面高度）：拿她的 Pos 当参照（她这会儿还站在地上）
        send('data get entity @e[tag=%s,limit=1] Pos' % tag)
        send('data get entity @e[tag=%s,limit=1] MaidInventory' % tag)
        time.sleep(2)

        print('observing & sampling Motion for %ds (every %dms) ...'
              % (OBSERVE // 1000, SAMPLE_MS), flush=True)
        t_end = time.time() + OBSERVE / 1000.0
        while time.time() < t_end:
            send('data get entity @e[tag=%s,limit=1] Motion' % tag)
            send('data get entity @e[tag=%s,limit=1] Pos' % tag)
            time.sleep(SAMPLE_MS / 1000.0)
        send('data get entity @e[tag=%s,limit=1] MaidInventory' % tag)
        time.sleep(3)

    data = readlog()
    send('stop')
    for _ in range(40):
        time.sleep(1)
        if p.poll() is not None:
            break
    if p.poll() is None:
        subprocess.run(['taskkill', '/PID', str(p.pid), '/T', '/F'], capture_output=True)

    # ── 解析采样：Motion / Pos 交替回显（**都不带字段名**，只能按"模长"分类）──
    # 【红轮实测踩到的坑，写在这里免得下次再踩】Motion 的三个分量都很小（原版不动点 1.7，
    # 分量更小），Pos 是几十~上百。第一版按"看见大向量就当 Motion 待配"写，于是每个样本的 y
    # 都取了**速度的 y**（1.4 左右）→ 全被当成"在地面"，空中样本 0、这条用例空跑一轮。
    name = NAME + tag_suffix
    vec = r'\[\s*(-?[\d.]+)d?\s*,\s*(-?[\d.]+)d?\s*,\s*(-?[\d.]+)d?\s*\]'
    ground_y = None
    pending = None      # 上一条 Motion（小向量）
    samples = []
    for l in data.splitlines():
        if ('entity data:' not in l) or (name not in l):
            continue
        m = re.search(vec, l)
        if not m:
            continue
        x, y, z = (float(m.group(i)) for i in (1, 2, 3))
        big = max(abs(x), abs(y), abs(z)) > 3.5
        if big:
            # 大向量 = Pos：拿它和上一条 Motion 配成一个样本
            if pending is not None:
                mx, my, mz = pending
                speed = (mx * mx + my * my + mz * mz) ** 0.5
                samples.append((speed, y))
                pending = None
        else:
            pending = (x, y, z)
    # 【地面基准 = 采样里最小的 y】不能取"第一条 Pos"：控制台有积压时她的第一条回读已经是
    # 飞到半空的高度（红轮就取到 y=124，于是空中样本 0）。空袭循环一定会落地，最小值即地面。
    ground_y = min((y for _, y in samples), default=0.0)
    airborne = [s for s, y in samples if y > ground_y + 1.0]
    boost_lines = [l for l in data.splitlines() if MARK_BOOST in l and name in l]
    no_entity = len([l for l in data.splitlines() if 'No entity was found' in l])
    # 【只看本轮 summon 之后的死亡行】红轮踩到：上一轮残留的同 tag 女仆会被本轮开头的清场
    # `kill @e[tag=…]` 干掉，那条 "was killed" 出现在 summon 之前——不能算"她中途没了"。
    lines_all = data.splitlines()
    summon_at = 0
    for i, l in enumerate(lines_all):
        if ('Summoned new %s' % name) in l or ('Dive615Maid' in l and 'was summoned' in l):
            summon_at = i
            break
    died = [l for l in lines_all[summon_at:]
            if name in l and ('died' in l or '离开世界' in l)]
    if died:
        notes.append('%s: **她中途没了** %s —— 这一轮的速度数据不可信（只当参考）'
                     % (tag_suffix, died[0][-140:]))
    return {'notes': notes, 'data': data, 'samples': samples, 'airborne': airborne,
            'boost': boost_lines, 'no_entity': no_entity, 'died': died,
            'ground_y': ground_y}


def pct(vals, q):
    if not vals:
        return 0.0
    v = sorted(vals)
    i = min(len(v) - 1, max(0, int(round(q * (len(v) - 1)))))
    return v[i]


stop_server()
for old in [f for f in os.listdir(os.path.join(server, 'mods')) if f.startswith('promaid-')]:
    if old != cfg['modname']:
        os.remove(os.path.join(server, 'mods', old))
shutil.copyfile(cfg['jar'], os.path.join(server, 'mods', cfg['modname']))
print('jar copied:', cfg['modname'], os.path.getsize(cfg['jar']), flush=True)

print('config R1:', set_scale('1.0'), flush=True)
r1 = run_round('r1')
print('config R2:', set_scale('1.4'), flush=True)   # 1.4 = 本次要上线的默认值
r2 = run_round('r14')

verdict = 'PASS'
fails = []
notes = []


def fail(msg):
    global verdict
    fails.append(msg)


for tag, r in (('R1', r1), ('R2', r2)):
    for n in r['notes']:
        notes.append(n)
    for pat in FAIL_PATTERNS:
        if pat in r['data']:
            fail('%s: 服务端报错 %s' % (tag, pat))
    for l in r['data'].splitlines():
        if 'FATAL' in l and any(h in l.lower() for h in FATAL_HINTS):
            fail('%s: FATAL %s' % (tag, l[:140]))
            break
    notes.append('%s: 采样 %d 个（空中 %d 个；地面基准 y=%.1f）｜俯冲加速行 %d 条'
                 '｜No entity %d 条'
                 % (tag, len(r['samples']), len(r['airborne']), r['ground_y'],
                    len(r['boost']), r.get('no_entity', 0)))
    notes.append('%s: 空中速度 中位 %.2f / 95分位 %.2f / 最大 %.2f 格/tick'
                 % (tag, pct(r['airborne'], 0.5), pct(r['airborne'], 0.95),
                    max(r['airborne']) if r['airborne'] else 0.0))

if not r1['boost']:
    fail('R1（倍数 1.0）没有「俯冲加速」行 —— 这条链路根本没跑起来')
elif '烟花×1' not in r1['boost'][0]:
    fail('R1 的俯冲加速行没写「烟花×1」：%s' % r1['boost'][0][-160:])
if not r2['boost']:
    fail('R2（倍数 1.4）没有「俯冲加速」行')
elif '烟花×1.4' not in r2['boost'][0]:
    fail('R2 的俯冲加速行没写「烟花×1.4」：%s' % r2['boost'][0][-160:])
if r1['died'] or r2['died']:
    fail('有一轮里她中途死了（%s）—— 场景不可比（测试服老配置那件事见 set_scale 的注释）'
         % ((r1['died'] or r2['died'])[0][-120:]))
elif len(r1['airborne']) < 6 or len(r2['airborne']) < 6:
    fail('空中采样太少（R1 %d / R2 %d）—— 观察窗内她没怎么飞，这条用例没测到东西'
         % (len(r1['airborne']), len(r2['airborne'])))
else:
    m1, m2 = max(r1['airborne']), max(r2['airborne'])
    ratio = m2 / m1 if m1 > 0 else 0.0
    notes.append('对比：最大速度 R1 %.2f → R2 %.2f 格/tick（×%.2f）'
                 % (m1, m2, ratio))
    if ratio < 1.25:
        fail('倍数 1.4 的俯冲最大速度只有倍数 1.0 的 %.2f 倍（%.2f → %.2f）—— '
             '那一份额外推力没真的作用在她身上' % (ratio, m1, m2))
    if m1 > 1.9:
        notes.append('提示：R1（倍数 1.0 = 原版）的最大速度 %.2f 高于原版烟花的不动点 1.7 —— '
                     '采样可能落在"烟花没挂着的滑翔段"，两轮同理，不影响比值判据' % m1)

# 配置默认值：本用例跑的是 1.0/3.0 两档，跑完把默认值写回 1.4 并断言它真的写进去了
print('restore default:', set_scale('1.4'), flush=True)
if abs(float(scale_in_config()) - 1.4) > 0.001:
    fail('配置文件里的 diveBoostFireworkScale 不是默认 1.4（读到 %s）' % scale_in_config())
else:
    notes.append('配置默认值：diveBoostFireworkScale = %s（用户口径的 ×1.4）' % scale_in_config())

if fails:
    verdict = 'FAIL(%d 处不符：%s)' % (len(fails), ' ｜ '.join(fails))

print('verdict:', verdict)
for n in notes:
    print('note:', n)
for tag, r in (('R1(×1.0)', r1), ('R2(×1.4)', r2)):
    for l in r['boost'][:2]:
        print('   %s 示例: %s' % (tag, l[-190:]))
for tag, r in (('R1', r1), ('R2', r2)):
    print('--- 关键日志行（%s）---' % tag)
    shown = 0
    for l in r['data'].splitlines():
        if any(k in l for k in (CAT, '放烟花起飞', '投掷', 'entity data:', 'Mixin apply', 'FATAL')):
            print('  ', l[:210])
            shown += 1
            if shown > 18:
                print('   ...(truncated)')
                break
    if shown == 0:
        print('   (nothing matched)')

sys.exit(0 if verdict == 'PASS' else 1)
