# -*- coding: utf-8 -*-
"""实测六百一十六 的场景验证：压缩盒**放进女仆背包 = 她背包的延伸**。

用法:
    python test_box616.py 1201 [A|B]

需求原文（用户）：
    "加入一个新道具，压缩盒……整体机制和潜影箱差不多。但不同的是，将这个箱子放进女仆的背包里面，
    女仆可以从这个里面拿东西，这个箱子里面的内容会被视为女仆背包的延伸。而且这个箱子里面物品堆叠
    上限大大增加，也就是说不再是只能堆 64 个，而是可以堆 114514 个。……虽然堆叠上限很高，但它的
    格子数量只有 5 个。"

【验的四件事】
  ⓐ **视图**：`/maid_smart box <女仆>`（本批新增的只读诊断命令）把"她那一侧能看见什么"打出来——
     应当看到「41 格（36 格背包 + 压缩盒 1 个 × 5 格）」以及「第 37 格：看得见 ×64，实际 ×114514」。
     这一条同时证到：延伸生效 + **大堆对她只暴露 64**（1.20.1 的 NBT Count 是 1 字节，
     114514 若整堆漏进她的存档会被截断成 82 = 蒸发，所以视野必须封顶）。
  ⓑ **真用**：她身上**没有**任何散装烟花与鞘翅——"能飞的三件套"只可能来自盒子。她起飞了
     （日志「背上鞘翅追过去（燃料=烟花火箭…）」）就是延伸在真实链路里生效的硬证据。
  ⓒ **不漏**：她整只实体的 dump 里，任何 ItemStack 的 Count 都不超过 64（大堆没有漏出去），
     而盒子那格的 Count 仍是六位数（它走我们自己的 int 字段）。
  ⓓ **对照**：`compressionBox.maidExtension = false` 时，同一个盒子、同一个位置——
     视图退回 36 格、且她**不起飞**（「没有可用鞘翅」）。证明 ⓑ 的起飞确实来自延伸。

脚手架与 608/611/612/615 同源：锚点 + 5x73 石头台 + 台面上方清空；替代主人 = NoAI 无重力村民；
用 `/maid_smart flyfollow <目标> <女仆>` 点名指定。

【这批验不到的那一层（写清楚）】界面本身的点击链路（开屏包 → 点格子 → C2S → 回包）需要
**真实客户端**（专用服务器上没有玩家、更没有鼠标），本用例碰不到；那一层靠"服务端每次操作都
重新确认手里还是压缩盒 + 只搬有实体的东西 + 单次最多 64"这套设计兜底，见 CompressionBoxService。
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
                 '@libraries/net/minecraftforge/forge/1.20.1-47.6.23/win_args.txt', 'nogui'],
        'jar': r'C:/Users/Sketch/.zcode/workspace/default/promaid-mod/patched/promaid-1.2.2.jar',
        'modname': 'promaid-1.2.2.jar',
        'maxhp_attr': 'generic.max_health',
    },
}

# 上一批（615）用的 forge 版本是 47.4.23：本批沿用同一台，修正 args
TARGETS['1201']['args'] = ['@user_jvm_args.txt',
                           '@libraries/net/minecraftforge/forge/1.20.1-47.4.23/win_args.txt', 'nogui']

WAIT = 200
FAR_WATCH = 40
TP_FAR_Z = 50
FAIL_PATTERNS = ('Mixin apply for mod promaid failed', 'InvalidInjectionException',
                 'MixinTransformerError', 'MixinApplyError', 'Failed to create brain',
                 'OutOfMemoryError', 'NoSuchMethodError', 'NoClassDefFoundError')
FATAL_HINTS = ('promaid', 'mixin')

NAME = 'Box616Maid'
MARK_TAKEOFF = '背上鞘翅追过去'
CAT = '飞行跟随'
BOX_CAT = '压缩盒'
BOX_ID = 'maid_smart:compression_box'
FIREWORK_TOTAL = 114514

which = sys.argv[1] if len(sys.argv) > 1 else '1201'
ONLY = sys.argv[2] if len(sys.argv) > 2 else None
cfg = TARGETS.get(which)
if not cfg:
    print('usage: python test_box616.py [1201] [A|B]')
    sys.exit(2)

server = cfg['dir']
pid_file = os.path.join(server, 'server.pid')
CONFIG = os.path.join(server, 'config', 'promaid-common.toml')
TARGET_NEAR = '~ ~8 ~10'


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


def set_section(section, **kv):
    """写某个小节里的键（小节不存在就在文件末尾新建）。"""
    t = _read_cfg()
    head, end = _section_bounds(t, section)
    if head is None:
        t = t.rstrip('\n') + '\n\n[%s]\n' % section + ''.join(
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
    _write_cfg('\n'.join(lines))
    return sorted(kv)


def box_nbt(slots):
    """压缩盒的 NBT（物品形态：CompressionBox.Items 列表，Count 是 int）。"""
    entries = ','.join('{Slot:%d,Count:%d,Item:%s}' % (i, c, it)
                       for i, (c, it) in enumerate(slots) if c > 0)
    return '{CompressionBox:{Items:[%s]}}' % entries


def run_round(suffix, maid_extension=True):
    log_path = os.path.join(server, 'console_box616_%s.log' % suffix)
    log = open(log_path, 'wb')
    p = subprocess.Popen([cfg['java'], '-Xmx3G', '-Dfile.encoding=UTF-8'] + cfg['args'],
                         cwd=server, stdout=log, stderr=subprocess.STDOUT,
                         stdin=subprocess.PIPE,
                         creationflags=subprocess.CREATE_NEW_PROCESS_GROUP | 0x00000008)
    open(pid_file, 'w').write(str(p.pid))
    print('--- round %s: pid %d (maidExtension=%s)' % (suffix, p.pid, maid_extension))

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
            if CAT in t or BOX_CAT in t:
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
            for t in ('box616maid', 'box616target', 'box616anchor'):
                send('kill @e[tag=%s]' % t)
            for n in (NAME, 'Box616Target'):
                send('kill @e[type=touhou_little_maid:maid,name=%s]' % n)
                send('kill @e[type=minecraft:villager,name=%s]' % n)
            # 【必须清地上的掉落物】上一轮打完收工时，她背包里的东西会掉一地；新女仆一出生就会
            # 把它们捡进背包（实测第一版就是这么读到 elytra ×2 / 烟花 ×114515 的——顺带证明了
            # 存入方向也通，但会让"盒子初始内容"这条断言不可复现）
            send('kill @e[type=minecraft:item]')
            time.sleep(1)
        send('summon minecraft:armor_stand ~ ~ ~ {Marker:1b,NoGravity:1b,Invisible:1b,'
             'Tags:["box616anchor"]}')
        time.sleep(2)
        A = '@e[tag=box616anchor,limit=1]'
        send('execute at %s run fill ~-5 ~-1 ~-4 ~5 ~-1 ~68 minecraft:stone' % A)
        time.sleep(1)
        send('execute at %s run fill ~-5 ~ ~-4 ~5 ~4 ~68 minecraft:air' % A)
        time.sleep(2)
        # 背包第 0 格 = 压缩盒：格 0 = 鞘翅 ×1，格 1 = 烟花 ×114514（用户点名的那个数）
        box = box_nbt([(1, '{id:"minecraft:elytra",Count:1b}'),
                       (FIREWORK_TOTAL, '{id:"minecraft:firework_rocket",Count:1}')])
        maid_nbt = ('{MaidScheduleMode:"ALL",HandItems:[{},{}],ArmorItems:[{},{},{},{}],'
                    'MaidInventory:{Size:36,Items:[{Slot:0b,id:"%s",Count:1b,tag:%s}]},'
                    'Owner:[I;1,2,3,4],Tags:["box616maid"],'
                    'CustomName:"\\"%s\\"",PersistenceRequired:1b}' % (BOX_ID, box, NAME))
        send('execute at %s run summon touhou_little_maid:maid ~ ~ ~ %s' % (A, maid_nbt))
        time.sleep(2)
        send('execute at %s run summon minecraft:villager %s {NoAI:1b,NoGravity:1b,Silent:1b,'
             'Invulnerable:1b,PersistenceRequired:1b,Health:2000f,'
             'Attributes:[{Name:"%s",Base:2000}],Tags:["box616target"],'
             'CustomName:"\\"Box616Target\\""}' % (A, TARGET_NEAR, cfg['maxhp_attr']))
        time.sleep(2)
        MA = '@e[tag=box616maid,limit=1]'
        T = '@e[tag=box616target,limit=1]'
        send('maid_smart box %s' % MA)          # ⓐ 她的背包视图（含盒子那 5 格）
        time.sleep(2)
        send('maid_smart flyfollow %s %s' % (T, MA))
        time.sleep(2)
        send('execute as %s run tp @s ~ ~ ~%d' % (T, TP_FAR_Z))
        time.sleep(1)
        print('far phase: watching %ds ...' % FAR_WATCH)
        time.sleep(FAR_WATCH)
        send('maid_smart box %s' % MA)          # 飞行之后再打一次视图
        time.sleep(1)
        send('data get entity %s Pos' % MA)
        time.sleep(2)

    data = readlog()
    if done:
        try:
            p.stdin.write(b'data get entity @e[tag=box616maid,limit=1]\n')
            p.stdin.flush()
            time.sleep(3)
            data = readlog()
        except Exception:
            pass
    send('stop')
    for _ in range(40):
        time.sleep(1)
        if p.poll() is not None:
            break
    if p.poll() is None:
        subprocess.run(['taskkill', '/PID', str(p.pid), '/T', '/F'], capture_output=True)

    lines = [l for l in data.splitlines() if CAT in l]
    takeoff = [l for l in lines if MARK_TAKEOFF in l]
    box_lines = [l.split('] ', 1)[-1].strip() for l in data.splitlines() if BOX_CAT in l]
    return {'notes': notes, 'data': data, 'takeoff': takeoff, 'box': box_lines, 'log': log_path}


def parse_view(box_lines):
    """把「看得见 X ×a，实际 Y ×b」解析成 [(seen_item, seen, real_item, real)]（颜色码已去掉）。"""
    out = []
    for l in box_lines:
        flat = re.sub(r'\u00a7.', '', l)
        m = re.search(r'看得见 (\S+) ×(\d+)，实际 (\S+) ×(\d+)', flat)
        if m:
            out.append((m.group(1), int(m.group(2)), m.group(3), int(m.group(4))))
        elif '看得见 空，实际 空' in flat:
            out.append(('', 0, '', 0))
    return out


def view_slots(box_lines):
    """视图里报的"一共多少格"（取不到返回 None）。"""
    for l in box_lines:
        flat = re.sub(r'\u00a7.', '', l)
        m = re.search(r'背包视图：(\d+) 格', flat)
        if m:
            return int(m.group(1))
    return None


def loose_over_64(data):
    """dump 里所有原版物品堆（Count 带 b 后缀）中 >64 的（不该有）。"""
    return [int(m.group(1)) for m in re.finditer(r'Count: (\d+)b', data) if int(m.group(1)) > 64]


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

a = b = None
if ONLY in (None, 'A'):
    print('config A:', set_section('flightFollow', enabled='true', dist='25.0', endDist='5.0'),
          set_section('compressionBox', maidExtension='true', maxStack=str(FIREWORK_TOTAL)))
    a = run_round('A', True)
    check_no_errors('A', a['data'], a['notes'])
    a['slots'] = view_slots(a['box'])
    a['view'] = parse_view(a['box'])
    a['leak'] = loose_over_64(a['data'])

if ONLY in (None, 'B'):
    print('config B:', set_section('compressionBox', maidExtension='false'))
    b = run_round('B', False)
    check_no_errors('B', b['data'], b['notes'])
    b['slots'] = view_slots(b['box'])

verdict = 'PASS'
fails = []


def fail(msg):
    fails.append(msg)


if a is not None:
    if a['notes']:
        fail('A 轮服务端/初始化有问题：%s' % a['notes'])
    if a['slots'] != 41:
        fail('A 轮她的视图不是 41 格（36 背包 + 5 盒格）而是 %s' % a['slots'])
    fw = [v for v in a['view'] if v[0] == 'minecraft:firework_rocket']
    if not fw:
        fail('A 轮视图里没有烟花那一格（延伸没生效？）')
    else:
        seen, real = fw[0][1], fw[0][3]
        if seen != 64:
            fail('A 轮她对大堆"看得见"的是 ×%d，应为 64（视野封顶没生效）' % seen)
        if real != FIREWORK_TOTAL:
            # 【必须是精确相等，不能写 real < FIREWORK_TOTAL】六百一十七查出 fromTag 的
            # 数量写回用错了方法（m_41769_ 是 grow 不是 setCount），读回来整整多 1 个：
            # 写 114514 读成 114515。这里当时写的是 `<`，多的那 1 个就被放过去了——
            # 松断言等于没断言，这条现在收紧成等号。
            fail('A 轮盒子里实际是 ×%d，应为 ×%d —— 数量读写口径不对（多/少了）'
                 % (real, FIREWORK_TOTAL))
    ely = [v for v in a['view'] if v[0] == 'minecraft:elytra']
    if not ely:
        fail('A 轮视图里没有鞘翅那一格')
    elif not (1 <= ely[0][1] <= 64 and ely[0][1] == ely[0][3]):
        fail('A 轮鞘翅那格"看得见 ×%d / 实际 ×%d"不对（小堆应当原样看得见）'
             % (ely[0][1], ely[0][3]))
    if not a['takeoff']:
        fail('A 轮她**没有起飞**——背包里那个盒子没被当成"能飞的道具/燃料"来源')
    elif '燃料=烟花火箭' not in a['takeoff'][0]:
        fail('A 轮起飞了但不是烟花驱动：%s' % a['takeoff'][0][-160:])
    if a['leak']:
        fail('A 轮她身上出现了 >64 的原版堆：%s（大堆漏进了存档，会被截断）' % a['leak'][:5])

if b is not None:
    if b['notes']:
        fail('B 轮服务端/初始化有问题：%s' % b['notes'])
    if b['slots'] == 41:
        fail('B 轮关掉 maidExtension 后视图仍是 41 格')
    if b['takeoff']:
        fail('B 轮关掉 maidExtension 后她**仍然起飞**了：%s' % b['takeoff'][0][-160:])

print()
print('=' * 74)
if a is not None:
    print('A 轮（延伸开）视图：')
    for l in a['box'][:8]:
        print('    ', re.sub(r'\u00a7.', '', l)[:180])
    print('A 轮 起飞行 %d 条' % len(a['takeoff']))
    for l in a['takeoff'][:2]:
        print('    ', l.strip()[:190])
    print('A 轮 她身上 >64 的堆:', a['leak'][:5])
if b is not None:
    print('B 轮（延伸关）视图：')
    for l in b['box'][:3]:
        print('    ', re.sub(r'\u00a7.', '', l)[:180])
    print('B 轮 起飞行 %d 条（应为 0）' % len(b['takeoff']))
    for l in [x for x in b['data'].splitlines() if CAT in x][-3:]:
        print('    尾巴:', l.strip()[:180])
print('=' * 74)
if fails:
    verdict = 'FAIL'
    print('VERDICT: FAIL')
    for f in fails:
        print('  -', f)
else:
    print('VERDICT: PASS —— 盒子在她背包里就是她背包的一部分：她看得见（视野封顶 64）、'
          '真的用它飞起来、大堆没漏进她的存档；关掉开关则两者都不成立')
raise SystemExit(0 if verdict == 'PASS' else 1)
