# -*- coding: utf-8 -*-
"""实测六百一十四 的场景验证：飞行跟随的「坐标档」（取自粉丝 Roderick32 的「鞘翅赶路」分支）。

用法:
    python test_goto614.py 1201

【本批合并了什么（要被这条用例证到的）】
    · `/maid_smart elytra_goto <x> <y> <z> [女仆]`：让女仆穿鞘翅**飞向一个坐标**——
      它复用飞行跟随那**同一条**链路（判定/起飞/补推/收手），只把"目标"从主人换成一个点；
    · 一次性：到点 / 超时 / 遇敌即收手并**摘掉**那个坐标（之后交回普通跟随）；
    · 命令侧先把几条硬条件判一遍并直接回原因（开关关着 / 目标太近 / 没有可用鞘翅 / 没有能飞的道具）；
    · 两张法术表的"id 认不出来"诊断（同一分支收下的另一件，但要装 ISS 才能端到端看到）。

【这条用例能验到哪一层（务必如实看）】
    测试服务器**没有装**《车万女仆：万法皆通》+ 铁魔法，所以"法术推进"与"法术 id 诊断"
    在本机结构上验不了（反射软依赖不在场时恒 null/静默返回）。本用例证的是真服务端能证的部分：
      ① 命令真的把坐标挂上了（日志有 `elytra_goto … 飞向 x y z`）；
      ② 坐标档真的驱动了**同一条**飞行链路（起飞行是坐标版措辞「飞向指定坐标（N 格）」，
         而不是主人版「主人飞远了（N 格）」）——这一条同时证明"没有主人也能起飞"，
         因为专用服务器上 `getOwner()` 恒为 null，本命令是**唯一**不依赖"替代主人实体"的入口；
      ③ 链会收尾：出现「结束（目标点 N 格，…）」；
      ④ 命令侧的硬条件预检真的会拦人（没燃料 / 目标太近 / 总开关关着），
         而不是回一句"开始赶路"然后什么都不发生。

场景（沿用 test_spellfly613.py 的脚手架）：
    · 锚点 + 5x29 石头台 + 台面上方 5 格清空 → 她到目标点之间必然没有方块阻拦；
    · 女仆 C（鞘翅 + 64 烟花）：`elytra_goto ~ ~ ~25`（25 格 > 默认触发距离 5）→ 期望起飞 + 收尾；
    · 女仆 D（只带鞘翅）：同样的命令 → 期望**被命令拦下**并回「没有「可以飞行的道具」」；
    · 距离预检：对 C 下 `~ ~ ~2`（2 格 ≤ 5）→ 期望回「目标点离她只有 … 格」；
    · 第二轮（把 bridge.flightFollow 改成 false 重启）：命令回「「飞行跟随」开关关着」且**没有**起飞。
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
    },
}

WAIT = 180        # 启动等待上限（秒）
OBSERVE = 60      # 观察窗口（秒）——一趟飞行最长 60 秒（MAX_TICKS），所以留满
FAIL_PATTERNS = ('Mixin apply for mod promaid failed', 'InvalidInjectionException',
                 'MixinTransformerError', 'MixinApplyError', 'Failed to create brain',
                 'OutOfMemoryError')
FATAL_HINTS = ('promaid', 'mixin')

NAME_C = 'Goto614Fuel'       # 鞘翅 + 烟花：该飞过去
NAME_D = 'Goto614NoFuel'     # 只带鞘翅：命令该拦下来
CAT = '飞行跟随'
MARK_GOTO = 'elytra_goto'
MARK_TAKEOFF_COORD = '飞向指定坐标（'
MARK_TAKEOFF_OWNER = '主人飞远了（'
MARK_END_COORD = '结束（目标点'
MARK_NO_FUEL = '没有「可以飞行的道具」'
MARK_TOO_CLOSE = '目标点离她只有'
MARK_SWITCH_OFF = '的「飞行跟随」开关关着'

which = sys.argv[1] if len(sys.argv) > 1 else '1201'
cfg = TARGETS.get(which)
if not cfg:
    print('usage: python test_goto614.py [1201]')
    sys.exit(2)

server = cfg['dir']
pid_file = os.path.join(server, 'server.pid')
CONFIG = os.path.join(server, 'config', 'promaid-common.toml')


def stop_server():
    if os.path.exists(pid_file):
        pid = open(pid_file).read().strip()
        subprocess.run(['taskkill', '/PID', pid, '/T', '/F'], capture_output=True)
        time.sleep(3)


def set_bridge_flags(**kv):
    """改 [flightFollow] 小节里的开关（与 test_spellfly613.py 同一份实现）。
    **六百一十五 起 flightFollow* 从 [bridge] 搬到了 [flightFollow]，键名也短了**
    （flightFollow→enabled、flightFollowDist→dist 等）。"""
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


def readlog_bytes(path):
    try:
        raw = open(path, 'rb').read()
    except Exception:
        return ''
    d = {e: raw.decode(e, 'replace') for e in ('utf-8', 'gbk', 'cp936', 'latin-1')}
    for t in d.values():
        if MARK_GOTO in t or CAT in t:
            return t
    for e in ('utf-8', 'gbk'):
        if 'Done (' in d[e]:
            return d[e]
    return d['utf-8']


def run_round(tag, want_takeoff):
    """一轮服务端：搭台 → 下命令 → 观察。tag 只用于日志文件名与清场标签。"""
    log_path = os.path.join(server, 'console_goto614_%s.log' % tag)
    log = open(log_path, 'wb')
    p = subprocess.Popen([cfg['java'], '-Xmx3G', '-Dfile.encoding=UTF-8'] + cfg['args'],
                         cwd=server, stdout=log, stderr=subprocess.STDOUT,
                         stdin=subprocess.PIPE,
                         creationflags=subprocess.CREATE_NEW_PROCESS_GROUP | 0x00000008)
    open(pid_file, 'w').write(str(p.pid))
    print('--- round %s: pid %d' % (tag, p.pid))

    def send(cmd):
        print('   >', cmd[:200])
        try:
            p.stdin.write((cmd + '\n').encode('utf-8'))
            p.stdin.flush()
        except Exception as e:
            print('   (stdin failed: %s)' % e)

    def readlog():
        return readlog_bytes(log_path)

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
        return {'notes': ['FAIL(server never reached Done in round %s)' % tag], 'data': ''}

    send('gamerule doMobSpawning false')
    send('difficulty easy')
    # 【清场】前几批留下的同名女仆/敌对生物一起扫掉：敌对生物会落进"威胁半径 8 格"里让她永远不起飞，
    # 同名女仆会让"最近的一只"选错（608/613 都踩过）。
    for _ in range(2):
        for base in ('goto614maidc', 'goto614maidd', 'goto614anchor',
                     'fly613maida', 'fly613maidb', 'fly613targeta', 'fly613targetb',
                     'fly613anchor', 'fly608maid', 'fly608target', 'fly608anchor'):
            for suf in ('', 'a', 'b', 'c'):
                send('kill @e[tag=%s%s]' % (base, suf))
        time.sleep(1)
    for mob in ('zombie', 'husk', 'drowned', 'zombie_villager', 'skeleton', 'stray',
                'creeper', 'spider', 'cave_spider', 'witch', 'slime', 'phantom'):
        send('kill @e[type=minecraft:%s]' % mob)
    time.sleep(2)

    # ① 锚点 + 台面 5x29 + 上方 5 格清空（与 608/613 同一套几何）
    send('summon minecraft:armor_stand ~ ~ ~ {Marker:1b,NoGravity:1b,Invisible:1b,'
         'Tags:["goto614anchor"]}')
    time.sleep(2)
    A = '@e[tag=goto614anchor,limit=1]'
    send('execute at %s run fill ~-2 ~-1 ~-4 ~2 ~-1 ~30 minecraft:stone' % A)
    time.sleep(1)
    send('execute at %s run fill ~-2 ~ ~-4 ~2 ~4 ~30 minecraft:air' % A)
    time.sleep(2)

    # ② 女仆 C（鞘翅 + 64 烟花）：该飞过去
    maid_c = ('{MaidScheduleMode:"ALL",HandItems:[{},{}],ArmorItems:[{},{},{},{}],'
              'MaidInventory:{Size:36,Items:[{Slot:0b,%s},{Slot:1b,%s}]},'
              'Owner:[I;1,2,3,4],Tags:["goto614maidc"],CustomName:"\\"%s\\"",'
              'PersistenceRequired:1b}' % (cfg['elytra'], cfg['firework'], NAME_C))
    send('execute at %s run summon touhou_little_maid:maid ~ ~ ~ %s' % (A, maid_c))
    time.sleep(2)
    # ③ 女仆 D（只带鞘翅）：命令该拦下来
    maid_d = ('{MaidScheduleMode:"ALL",HandItems:[{},{}],ArmorItems:[{},{},{},{}],'
              'MaidInventory:{Size:36,Items:[{Slot:0b,%s}]},'
              'Owner:[I;1,2,3,4],Tags:["goto614maidd"],CustomName:"\\"%s\\"",'
              'PersistenceRequired:1b}' % (cfg['elytra'], NAME_D))
    send('execute at %s run summon touhou_little_maid:maid ~ ~ ~3 %s' % (A, maid_d))
    time.sleep(3)

    MC = '@e[tag=goto614maidc,limit=1]'
    MD = '@e[tag=goto614maidd,limit=1]'

    # ④ 先问出锚点（= 控制台所在的世界出生点）的精确坐标：命令要用**绝对坐标**发，
    #    因为 `execute at … run <命令>` 会把被包住那条命令的 sendSuccess/sendFailure 一起吞掉
    #    （本批实测踩到：日志里一个字都没有，看着像"命令没生效"），而绝对坐标可以直接发。
    send('data get entity %s Pos' % A)
    time.sleep(2)
    data = readlog()
    m = None
    for l in data.splitlines():
        if 'has the following entity data' in l:
            m = re.findall(r'(-?\d+\.\d+)d', l)
    if not m or len(m) < 3:
        send('stop')
        for _ in range(40):
            time.sleep(1)
            if p.poll() is not None:
                break
        if p.poll() is None:
            subprocess.run(['taskkill', '/PID', str(p.pid), '/T', '/F'], capture_output=True)
        return {'notes': ['FAIL(拿不到锚点坐标：%s)' % (m,)],
                'data': data, 'gotoCmd': [], 'takeoffCoord': [], 'takeoffOwner': [],
                'endCoord': [], 'noFuel': [], 'tooClose': [], 'switchOff': []}
    ax, ay, az = float(m[0]), float(m[1]), float(m[2])
    print('   anchor = %.3f %.3f %.3f' % (ax, ay, az))

    # ⑤ 脚手架自检
    send('execute at %s run execute if entity %s run say SETUP_C' % (A, MC))
    send('execute at %s run execute if entity %s run say SETUP_D' % (A, MD))
    time.sleep(2)

    # ⑥ 命令预检的两条阴性用例（都不该起飞，回的都是"直接原因"）
    send('maid_smart elytra_goto %f %f %f %s' % (ax, ay, az + 2.0, MC))     # 太近（2 ≤ 5）
    time.sleep(2)
    send('maid_smart elytra_goto %f %f %f %s' % (ax, ay, az + 25.0, MD))    # 没有能飞的道具
    time.sleep(4)
    send('data get entity %s Pos' % MC)

    # ⑦ 正戏：25 格外（> 默认触发距离 5）
    send('maid_smart elytra_goto %f %f %f %s' % (ax, ay, az + 25.0, MC))
    time.sleep(3)
    send('data get entity %s Pos' % MC)
    time.sleep(max(1, OBSERVE - 3))
    send('execute at %s run execute if entity @e[tag=goto614anchor,limit=1] run say OBSERVE_DONE' % MC)

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
    gotoCmd = [l for l in lines if MARK_GOTO in l and '飞向' in l]
    takeoffCoord = [l for l in lines if CAT in l and MARK_TAKEOFF_COORD in l]
    takeoffOwner = [l for l in lines if CAT in l and MARK_TAKEOFF_OWNER in l]
    endCoord = [l for l in lines if CAT in l and MARK_END_COORD in l]
    noFuel = [l for l in lines if MARK_NO_FUEL in l]
    tooClose = [l for l in lines if MARK_TOO_CLOSE in l]
    switchOff = [l for l in lines if MARK_SWITCH_OFF in l]
    # 【绿轮实测踩到的缺陷，固化成断言】解除矢量那行也必须认坐标档：链收手时若先把坐标摘了，
    # 它就只能打「进到主人 ? 格内」（距离取不到 + 措辞退回主人版）。
    releaseBad = [l for l in lines if CAT in l and '解除' in l
                  and ('进到主人' in l or '? 格内' in l)]

    notes.append('命令成功行 %d / 坐标档起飞 %d / 主人版起飞 %d / 坐标档收尾 %d / '
                 '没燃料拒绝 %d / 太近拒绝 %d / 开关关着拒绝 %d / 解除行退回主人版 %d'
                 % (len(gotoCmd), len(takeoffCoord), len(takeoffOwner), len(endCoord),
                    len(noFuel), len(tooClose), len(switchOff), len(releaseBad)))
    for label, lst in (('命令', gotoCmd), ('坐标档起飞', takeoffCoord),
                       ('坐标档收尾', endCoord), ('没燃料', noFuel),
                       ('太近', tooClose), ('开关关着', switchOff)):
        if lst:
            notes.append('%s 示例: %s' % (label, lst[0][-220:]))

    send('stop')
    for _ in range(40):
        time.sleep(1)
        if p.poll() is not None:
            break
    if p.poll() is None:
        subprocess.run(['taskkill', '/PID', str(p.pid), '/T', '/F'], capture_output=True)

    return {'notes': notes, 'data': data, 'gotoCmd': gotoCmd, 'takeoffCoord': takeoffCoord,
            'takeoffOwner': takeoffOwner, 'endCoord': endCoord, 'noFuel': noFuel,
            'tooClose': tooClose, 'switchOff': switchOff, 'releaseBad': releaseBad}


stop_server()
for old in [f for f in os.listdir(os.path.join(server, 'mods')) if f.startswith('promaid-')]:
    if old != cfg['modname']:
        os.remove(os.path.join(server, 'mods', old))
shutil.copyfile(cfg['jar'], os.path.join(server, 'mods', cfg['modname']))
print('jar copied:', cfg['modname'], os.path.getsize(cfg['jar']))

print('config A:', set_bridge_flags(enabled='true', firework='true',
                                    elytra='true', dist='16.0'))
a = run_round('on', True)

verdicts = []
if not a['data']:
    verdicts.append('FAIL(A 轮服务端没起来)')
else:
    if not a['gotoCmd']:
        verdicts.append('FAIL(A 轮命令没有成功（日志里没有 elytra_goto … 飞向 x y z）)')
    if not a['takeoffCoord']:
        verdicts.append('FAIL(A 轮没有坐标档起飞行（应含「飞向指定坐标（N 格）」）——坐标档没驱动起飞行')
    if a['takeoffOwner']:
        verdicts.append('FAIL(A 轮出现了主人版起飞行 —— 坐标档错走了实体分支：%s'
                        % a['takeoffOwner'][0][-200:])
    if not a['endCoord']:
        verdicts.append('FAIL(A 轮没有「结束（目标点 …」——链没有收尾或措辞不对')
    if not a['noFuel']:
        verdicts.append('FAIL(A 轮"没有能飞的道具"没有被命令拦下（应回「没有「可以飞行的道具」」）')
    if not a['tooClose']:
        verdicts.append('FAIL(A 轮"目标太近"没有被命令拦下（应回「目标点离她只有 … 格」）')
    if a['releaseBad']:
        verdicts.append('FAIL(A 轮「解除火箭推进矢量」那行退回了主人版措辞/距离取不到：%s'
                        % a['releaseBad'][0][-200:])
    if len(a['takeoffCoord']) > 8:
        verdicts.append('FAIL(A 轮坐标档起飞行 %d 条 —— 一次性目标不该反复重启'
                        % len(a['takeoffCoord']))

print('config B:', set_bridge_flags(enabled='false', dist='16.0'))
b = run_round('off', False)
if not b['data']:
    verdicts.append('FAIL(B 轮服务端没起来)')
else:
    if not b['switchOff']:
        verdicts.append('FAIL(B 轮总开关关着时命令没有回「「飞行跟随」开关关着」)')
    if b['takeoffCoord']:
        verdicts.append('FAIL(B 轮总开关关着却起飞了：%s' % b['takeoffCoord'][0][-200:])

verdict = 'PASS' if not verdicts else verdicts[0]
print('verdict:', verdict)
for n in a['notes'] + b['notes']:
    print('note:', n)
print('--- 关键日志行 ---')
shown = 0
for l in (a['data'] + '\n' + b['data']).splitlines():
    if any(k in l for k in (CAT, MARK_GOTO, 'entity data:', 'SETUP_', 'OBSERVE_DONE',
                            'Mixin apply', 'FATAL')):
        print('  ', l[:230])
        shown += 1
        if shown > 55:
            print('   ...(truncated)')
            break
if shown == 0:
    print('   (nothing matched)')

sys.exit(0 if verdict == 'PASS' else 1)
