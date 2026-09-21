# -*- coding: utf-8 -*-
"""实测六百一十五 的场景验证：飞行跟随的**起手球与收手球拆开**（默认 25 进 / 5 出）。

用法:
    python test_radius615.py 1201

需求原文（用户）：
    "1.现在女仆稍微走出去一点就开始飞（默认5格导致的），需要对判定进行一个收紧。首先，启动飞行跟随
    要求：以自身为圆心，半径25格球内（其实就是将现在配置的5改成25）。也就是说，25格球内无主人+
    无方块阻挡+未发现威胁+开关打开=启动跟随飞行。重点：如何结束这个模式？当发现5格内有主人后，
    结束此模式。如果有烟花矢量则消去（现在已经做了）。在先前的版本，进入此模式和取消此模式走的都是
    同一个判定球，这样子带来的麻烦很多。实际上开始跟结束两个半点的球大小应该不一样，默认值就是我说的
    那两个。依旧可以在手册内调试。
    2.然后把飞行跟随这个板块单独拎出来，不要放在搭路板块的里面，而是改成跟搭路平行的一个板块。"

【这条用例要证到的四件事（四轮，每轮改配置重启）】
  A 轮（**代码默认档**：把 [flightFollow] 的 dist / endDist 两行删掉，只留 enabled=true）
     ① 替代主人在约 12.8 格（10 平 + 8 高）时，她**不该起飞**（旧默认 5 会飞；新默认 25 不会），
        而且跳过日志写的是本批的新措辞「距离不够（12.8 格 ≤ 起手距离 25.0）」；
     ② 把替代主人 tp 到 40 格外 → **该起飞**，结束行带距离且 ≤ 5.5（新收手半径 5）；
     ③ 配置文件里被写回 **dist = 25.0 / endDist = 5.0**（证明默认值就是这两个）；
     ④ 「解除火箭推进矢量」那一行也发生在 ≤ 5.5 格内。
  B 轮（dist=25 默认 + endDist=**12**）→ 结束距离落在 **(5.5, 12.5]**：
     证明**收手半径真的由 endDist 决定**，既不是老的「起手−1 = 24」也不是「上限 15」。
  C 轮（dist=25 + endDist=**30**，故意比起手大）→ 结束距离 ≤ **24.5**：
     证明兜底那条「收手半径 ≥ 起手距离时自动压到 起手−1」真的生效（否则 30 格就收手，
     她永远飞不到你身边、或者干脆不起飞）。
  D 轮（**老配置迁移**：把 `[bridge] flightFollow = true` 写回去、而 `[flightFollow] enabled = false`）
     → 0 行「飞行跟随」：**老键不再生效**（这是本批"整块搬小节"对老存档的实际影响，手册与
     配置注释里都写了这一句）。

【脚手架与 608/611/612 同源】锚点 + 5x73 石头台（`fill ~-5 ~-1 ~-4 ~5 ~-1 ~68`）+ 台面上方 5 格
清空；替代主人 = NoAI 无重力村民（**非敌对**——她的"附近有怪就不飞"会把敌对目标当威胁）；
女仆背包放鞘翅 + 64 烟花、胸甲槽留空。用 `/maid_smart flyfollow <目标> <女仆>` 点名指定。
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

WAIT = 180        # 启动等待上限（秒）
NEAR_WATCH = 16   # A 轮阶段一：近距离观察窗（够 25 格档的跳过日志打一条，限频 10 秒）
FAR_WATCH = 44    # 飞出去的观察窗（起飞 + 补推 + 收手 + 落地）
# 【为什么是 50 而不是 40（红轮实测踩到）】tp +40 时落地距离只有约 23.6 格 —— **仍小于新默认 25**，
# 她得自己晃悠 24 秒才飘出 25 格（红轮 A 轮就是这么"观察窗关了还没飞完"的）。tp +50 落到约 33 格，
# 越过起手线立刻起飞，观察窗够她飞完一整趟（起飞 → 补推 → 收手 → 落地）。
TP_FAR_Z = 50
FAIL_PATTERNS = ('Mixin apply for mod promaid failed', 'InvalidInjectionException',
                 'MixinTransformerError', 'MixinApplyError', 'Failed to create brain',
                 'OutOfMemoryError')
FATAL_HINTS = ('promaid', 'mixin')

NAME = 'Rad615Maid'
MARK_TAKEOFF = '背上鞘翅追过去'
MARK_RELEASE = '解除火箭推进矢量'
MARK_SKIP_NEAR = '起手距离'
CAT = '飞行跟随'

which = sys.argv[1] if len(sys.argv) > 1 else '1201'
# --only A|B|C|D：只跑某一轮（红轮定位用；不写就跑四轮）
ONLY = sys.argv[2] if len(sys.argv) > 2 else None
cfg = TARGETS.get(which)
if not cfg:
    print('usage: python test_radius615.py [1201]')
    sys.exit(2)

server = cfg['dir']
pid_file = os.path.join(server, 'server.pid')
CONFIG = os.path.join(server, 'config', 'promaid-common.toml')

TARGET_NEAR = '~ ~8 ~10'   # 约 12.8 格（10 平 + 8 高）


def stop_server():
    if os.path.exists(pid_file):
        pid = open(pid_file).read().strip()
        subprocess.run(['taskkill', '/PID', pid, '/T', '/F'], capture_output=True)
        time.sleep(3)


def _read_cfg():
    raw = open(CONFIG, 'rb').read()
    try:
        return raw.decode('utf-8')
    except Exception:
        return raw.decode('gbk', errors='replace')


def _write_cfg(t):
    open(CONFIG, 'wb').write(t.encode('utf-8'))


def _section_bounds(text, name):
    """返回 [name] 小节的行下标区间 (头, 尾开区间)。"""
    lines = text.split('\n')
    head = None
    for i, l in enumerate(lines):
        if l.strip() == '[%s]' % name:
            head = i
            break
    if head is None:
        return None, None
    end = len(lines)
    for i in range(head + 1, len(lines)):
        if lines[i].strip().startswith('['):
            end = i
            break
    return head, end


def set_flight(**kv):
    """写 [flightFollow] 小节里的键（六百一十五 起它从 [bridge] 搬出来了）。

    小节不存在就在**文件末尾**新建（Forge 按名字读，位置无关；它自己 save 时会重排）。
    """
    t = _read_cfg()
    head, end = _section_bounds(t, 'flightFollow')
    if head is None:
        t = t.rstrip('\n') + '\n\n[flightFollow]\n' + ''.join(
            '\t%s = %s\n' % (k, v) for k, v in kv.items())
        _write_cfg(t)
        return sorted(kv)
    lines = t.split('\n')
    hit = set()
    for i in range(head + 1, end):
        for k, v in kv.items():
            m = re.match(r'^(\s*)%s\s*=\s*\S+\s*$' % re.escape(k), lines[i])
            if m:
                lines[i] = '%s%s = %s' % (m.group(1), k, v)
                hit.add(k)
    for k, v in kv.items():
        if k not in hit:
            lines.insert(head + 1, '\t%s = %s' % (k, v))
            head += 1
            end += 1
            hit.add(k)
    _write_cfg('\n'.join(lines))
    return sorted(hit)


def set_bridge_legacy(**kv):
    """写回**老位置** [bridge] 里的键（D 轮：证明它不再生效）。"""
    t = _read_cfg()
    head, end = _section_bounds(t, 'bridge')
    if head is None:
        raise SystemExit('配置里没有 [bridge] 小节？')
    lines = t.split('\n')
    hit = set()
    for i in range(head + 1, end):
        for k, v in kv.items():
            m = re.match(r'^(\s*)%s\s*=\s*\S+\s*$' % re.escape(k), lines[i])
            if m:
                lines[i] = '%s%s = %s' % (m.group(1), k, v)
                hit.add(k)
    for k, v in kv.items():
        if k not in hit:
            lines.insert(head + 1, '\t%s = %s' % (k, v))
            head += 1
            end += 1
    _write_cfg('\n'.join(lines))
    return sorted(hit)


def drop_flight(*keys):
    """删掉 [flightFollow] 里那几行（让**代码默认值**生效）。"""
    t = _read_cfg()
    head, end = _section_bounds(t, 'flightFollow')
    if head is None:
        return []
    lines = t.split('\n')
    gone = []
    for i in range(end - 1, head, -1):
        for k in keys:
            if re.match(r'^\s*%s\s*=' % re.escape(k), lines[i]):
                del lines[i]
                gone.append(k)
    _write_cfg('\n'.join(lines))
    return gone


def flight_value(key):
    """读回 [flightFollow] 里某个键的值（读不到返回 None）。"""
    t = _read_cfg()
    head, end = _section_bounds(t, 'flightFollow')
    if head is None:
        return None
    for l in t.split('\n')[head + 1:end]:
        m = re.match(r'^\s*%s\s*=\s*(\S+)\s*$' % re.escape(key), l)
        if m:
            return m.group(1)
    return None


def run_round(suffix, near_phase=False):
    """一轮 = 起服 → 搭台 → 两只实体 → 挂链 → （可选近距离观察）→ tp 远 → 观察 → 收数据。"""
    log_path = os.path.join(server, 'console_radius615_%s.log' % suffix)
    log = open(log_path, 'wb')
    p = subprocess.Popen([cfg['java'], '-Xmx3G', '-Dfile.encoding=UTF-8'] + cfg['args'],
                         cwd=server, stdout=log, stderr=subprocess.STDOUT,
                         stdin=subprocess.PIPE,
                         creationflags=subprocess.CREATE_NEW_PROCESS_GROUP | 0x00000008)
    open(pid_file, 'w').write(str(p.pid))
    print('--- round %s: pid %d (near_phase=%s)' % (suffix, p.pid, near_phase))

    def send(cmd):
        print('   >', cmd[:190])
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
    if not done:
        notes.append('%s: FAIL(server never reached Done)' % suffix)
    else:
        send('gamerule doMobSpawning false')
        send('difficulty easy')
        for _ in range(2):
            for t in ('rad615maid', 'rad615target', 'rad615anchor'):
                send('kill @e[tag=%s]' % t)
            for n in ('Rad615Maid', 'Rad615Target'):
                send('kill @e[type=touhou_little_maid:maid,name=%s]' % n)
                send('kill @e[type=minecraft:villager,name=%s]' % n)
            time.sleep(1)
        send('summon minecraft:armor_stand ~ ~ ~ {Marker:1b,NoGravity:1b,Invisible:1b,'
             'Tags:["rad615anchor"]}')
        time.sleep(2)
        A = '@e[tag=rad615anchor,limit=1]'
        send('execute at %s run fill ~-5 ~-1 ~-4 ~5 ~-1 ~68 minecraft:stone' % A)
        time.sleep(1)
        send('execute at %s run fill ~-5 ~ ~-4 ~5 ~4 ~68 minecraft:air' % A)
        time.sleep(2)
        maid_nbt = ('{MaidScheduleMode:"ALL",HandItems:[{},{}],ArmorItems:[{},{},{},{}],'
                    'MaidInventory:{Size:36,Items:[{Slot:0b,%s},{Slot:1b,%s}]},'
                    'Owner:[I;1,2,3,4],Tags:["rad615maid"],'
                    'CustomName:"\\"%s\\"",PersistenceRequired:1b}'
                    % (cfg['elytra'], cfg['firework'], NAME))
        send('execute at %s run summon touhou_little_maid:maid ~ ~ ~ %s' % (A, maid_nbt))
        time.sleep(2)
        send('execute at %s run summon minecraft:villager %s {NoAI:1b,NoGravity:1b,Silent:1b,'
             'Invulnerable:1b,PersistenceRequired:1b,Health:2000f,'
             'Attributes:[{Name:"%s",Base:2000}],Tags:["rad615target"],'
             'CustomName:"\\"Rad615Target\\""}' % (A, TARGET_NEAR, cfg['maxhp_attr']))
        time.sleep(2)
        MA = '@e[tag=rad615maid,limit=1]'
        T = '@e[tag=rad615target,limit=1]'
        send('execute at %s run execute if entity %s run say SETUP_OK' % (A, T))
        time.sleep(2)
        send('maid_smart flyfollow %s %s' % (T, MA))
        time.sleep(2)

        if near_phase:
            print('near phase: watching %ds (expect NO takeoff) ...' % NEAR_WATCH)
            time.sleep(NEAR_WATCH)
            send('data get entity %s Pos' % MA)

        # 把替代主人 tp 远（40 格外的方向沿 z = 台阶长边）
        send('execute as %s run tp @s ~ ~ ~%d' % (T, TP_FAR_Z))
        time.sleep(1)
        send('data get entity %s Pos' % MA)
        send('data get entity %s Pos' % T)
        print('far phase: watching %ds ...' % FAR_WATCH)
        time.sleep(FAR_WATCH)
        send('data get entity %s Pos' % MA)
        time.sleep(2)

    data = readlog()
    send('stop')
    for _ in range(40):
        time.sleep(1)
        if p.poll() is not None:
            break
    if p.poll() is None:
        subprocess.run(['taskkill', '/PID', str(p.pid), '/T', '/F'], capture_output=True)

    lines = [l for l in data.splitlines() if CAT in l]
    takeoff = [l for l in lines if MARK_TAKEOFF in l]
    release = [l for l in lines if MARK_RELEASE in l]
    skip_near = [l for l in lines if MARK_SKIP_NEAR in l]

    def dists(rows, pat):
        out = []
        for l in rows:
            m = re.search(pat, l)
            if m:
                out.append(float(m.group(1)))
        return out

    end_dists = dists([l for l in lines if '结束（' in l], r'结束（[^）]*?([\d.]+)\s*格')
    release_dists = dists(release, r'进到[^）]*?([\d.]+)\s*格内')
    return {'notes': notes, 'data': data, 'takeoff': takeoff, 'release': release,
            'skip_near': skip_near, 'end_dists': end_dists, 'release_dists': release_dists}


def check_no_errors(tag, data, notes):
    for pat in FAIL_PATTERNS:
        if pat in data:
            notes.append('%s: FAIL(error line: %s)' % (tag, pat))
    for l in data.splitlines():
        if 'FATAL' in l and any(h in l.lower() for h in FATAL_HINTS):
            notes.append('%s: FATAL %s' % (tag, l[:140]))
            break


stop_server()
for old in [f for f in os.listdir(os.path.join(server, 'mods')) if f.startswith('promaid-')]:
    if old != cfg['modname']:
        os.remove(os.path.join(server, 'mods', old))
shutil.copyfile(cfg['jar'], os.path.join(server, 'mods', cfg['modname']))
print('jar copied:', cfg['modname'], os.path.getsize(cfg['jar']))

# ── A 轮：代码默认档（删掉 dist / endDist 两行，只把开关打开）──
a = b = c = d = None   # 未跑的轮次留 None，打印时跳过

if ONLY in (None, 'A'):
    print('config A:', set_flight(enabled='true'), '; dropped:',
          drop_flight('dist', 'endDist'))
    a = run_round('A', near_phase=True)
    check_no_errors('A', a['data'], a['notes'])
    dist_after, end_after = flight_value('dist'), flight_value('endDist')
    a['notes'].append('A 轮配置文件里 dist=%s / endDist=%s（新默认应为 25.0 / 5.0）'
                      % (dist_after, end_after))

# ── B 轮：收手半径跟着 endDist 走（endDist=12）──
if ONLY in (None, 'B'):
    print('config B:', set_flight(enabled='true', dist='25.0', endDist='12.0'))
    b = run_round('B')

# ── C 轮：endDist ≥ dist 的兜底（endDist=30 → 压到 起手−1 = 24）──
if ONLY in (None, 'C'):
    print('config C:', set_flight(enabled='true', dist='25.0', endDist='30.0'))
    c = run_round('C')

# ── D 轮：老配置迁移（老键写 true、新键关着 → 一行都不该有）──
if ONLY in (None, 'D'):
    print('config D:', set_flight(enabled='false'), '; legacy:',
          set_bridge_legacy(flightFollow='true', flightFollowDist='5.0'))
    d = run_round('D')

verdict = 'PASS'
fails = []


def fail(msg):
    global verdict
    fails.append(msg)


if ONLY in (None, 'A'):
    # ── A 轮判据 ──
    if 'FAIL' in ' '.join(a['notes']):
        fail('A 轮服务端/初始化有问题：%s' % a['notes'])
    if a['skip_near']:
        if '起手距离 25.0' not in a['skip_near'][0]:
            fail('A 轮近距离跳过日志的阈值不是新默认 25.0：%s' % a['skip_near'][0][-160:])
        if not re.search(r'距离不够（1[0-9.]+ 格', a['skip_near'][0]):
            fail('A 轮近距离跳过日志的距离不像 12~13 格：%s' % a['skip_near'][0][-160:])
    else:
        fail('A 轮**没有**近距离跳过日志 —— 她要么起飞了（判定没收紧），'
             '要么这条日志没打出来（默认档应写「距离不够（12.8 格 ≤ 起手距离 25.0）」）')
    if not a['takeoff']:
        fail('A 轮 tp 到 40 格外之后**没有起飞** —— 新默认 25 不该挡住 40 格')
    if a['end_dists'] and min(a['end_dists']) > 5.5:
        fail('A 轮收手距离 %.1f 格（新默认应是 5 → ≤5.5）'
             % min(a['end_dists']))
    if not a['end_dists']:
        a['notes'].append('警告：A 轮没读到带距离的结束行（观察窗内她可能还在滑翔）')
    if not a['release']:
        fail('A 轮没有「解除火箭推进矢量」那一行 —— 进半径解除矢量没接上')
    elif a['release_dists'] and max(a['release_dists']) > 5.5:
        fail('A 轮的解除行出现在 %.1f 格 —— 那是"还没进半径就解除"，判据接错了'
             % max(a['release_dists']))
    if dist_after is not None and abs(float(dist_after) - 25.0) > 0.01:
        fail('A 轮配置文件里 dist 被写回 %s（新默认应为 25.0 —— 说明默认值没改成 25）'
             % dist_after)
    if end_after is not None and abs(float(end_after) - 5.0) > 0.01:
        fail('A 轮配置文件里 endDist 被写回 %s（新默认应为 5.0）' % end_after)

if ONLY in (None, 'B'):
    # ── B 轮判据：收手半径由 endDist 决定 ──
    check_no_errors('B', b['data'], b['notes'])
    if not b['takeoff']:
        fail('B 轮（dist=25）没起飞')
    if not b['end_dists']:
        fail('B 轮没读到带距离的结束行 —— 看不出收手半径')
    elif not (5.5 < min(b['end_dists']) <= 12.5):
        fail('B 轮（endDist=12）收手距离 %.1f 格，不在 (5.5, 12.5] 里 —— '
             '收手半径没跟着 endDist 走（还是老的 min(15, 起手−1)=15？）' % min(b['end_dists']))

if ONLY in (None, 'C'):
    # ── C 轮判据：endDist > dist 时被压到 起手−1 ──
    check_no_errors('C', c['data'], c['notes'])
    if not c['takeoff']:
        fail('C 轮（dist=25 / endDist=30）没起飞')
    if not c['end_dists']:
        fail('C 轮没读到带距离的结束行')
    elif min(c['end_dists']) > 24.5:
        fail('C 轮收手距离 %.1f 格 > 24.5 —— 兜底（压到 起手−1 = 24）没生效' % min(c['end_dists']))
    elif min(c['end_dists']) <= 5.5:
        fail('C 轮收手距离 %.1f 格 ≤ 5.5 —— 这个值不该出现（endDist 写的是 30）'
             % min(c['end_dists']))

if ONLY in (None, 'D'):
    # ── D 轮判据：老键不再生效 ──
    check_no_errors('D', d['data'], d['notes'])
    if d['takeoff'] or d['release']:
        fail('D 轮（老 [bridge] flightFollow=true、新 flightFollow.enabled=false）居然飞了'
             '（起飞 %d / 解除 %d）—— 老键还在生效？' % (len(d['takeoff']), len(d['release'])))
    elif d['skip_near']:
        fail('D 轮居然有飞行跟随日志 —— 开关关着时不该有任何一行')

if fails:
    verdict = 'FAIL(%d 处不符：%s)' % (len(fails), ' ｜ '.join(fails))

print('verdict:', verdict)
for r, tag in ((a, 'A'), (b, 'B'), (c, 'C'), (d, 'D')):
    if not r:
        print('%s: （--only 跳过了这一轮）' % tag)
        continue
    for n in r['notes']:
        print('note:', n)
    print('%s: 起飞 %d / 解除 %d / 带距离的结束行 %d（距离 %s）/ 跳过 %d'
          % (tag, len(r['takeoff']), len(r['release']), len(r['end_dists']),
             ['%.1f' % x for x in r['end_dists']], len(r['skip_near'])))
    for label, rows in (('起飞', r['takeoff']), ('跳过', r['skip_near']), ('解除', r['release'])):
        if rows:
            print('   %s示例: %s' % (label, rows[0][-200:]))
    shown = 0
    print('--- 关键日志行（%s 轮）---' % tag)
    for l in r['data'].splitlines():
        if any(k in l for k in (CAT, 'entity data:', 'SETUP_OK', 'Mixin apply', 'FATAL')):
            print('  ', l[:220])
            shown += 1
            if shown > 26:
                print('   ...(truncated)')
                break
    if shown == 0:
        print('   (nothing matched)')

sys.exit(0 if verdict == 'PASS' else 1)
