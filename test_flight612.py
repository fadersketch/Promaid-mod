# -*- coding: utf-8 -*-
"""实测六百一十二 的场景验证：飞行跟随的四条（默认距离 5 / 进半径解除推进矢量 /
触发推广到所有模式 / 威胁中断链路）。

用法:
    python test_flight612.py 1201

用户反馈原文（本条需求）：
    "1.飞行跟随配置默认距离从16改为5，同时加一个判定，在距离内检测到主人之后解除烟花带来的
     矢量（之前空袭状态下是不解除，在此模式下改为解除）2.2个空袭状态（未接敌），也可以触发
     飞行跟随，不做额外抑制。简单来说，就是目前飞行跟随触发判定推广到所有模式下。3.跟搭路
     有同款的判定，检测到威胁的时候会解除这个链路（又变成自然滑行）。4.我需要确认一点，在此类
     状态下，女仆在什么时候会使用烟花火箭这类推进呢？"

本脚本验的**全是服务端能观测到的那一半**（第 4 条是提问，答案写在 changelog 与手册里）：
    A 轮（**代码默认档**：先把配置里的 flightFollowDist 那一行删掉，让 612 的新默认生效）
        ① 替代主人只放在 **10 格外** → 她仍然起飞（旧默认 16 时这里**不该**起飞，红轮就是这么失败的）；
        ② 起飞行照旧写「燃料=烟花火箭」；
        ③ **结束行带距离且 ≤5 格**（新收手半径 = 触发距离 - 1 = 4 格；旧版这里是 15 格口径）；
        ④ 多一行「解除火箭推进矢量」；
        ⑤ **独立取证**：结束那一刻抓 `Motion`，水平分量必须 < 0.5 格/tick
           （旧版她带着烟花那 1.7 格/tick 的动量继续冲，这一条会明显不过）；
        ⑥ 把替代主人 tp 到 64 格外 → **再飞一趟**（"飞出去会再次启动链路"不变）；
        ⑦ 烟花真的少了、鞘翅没丢（1 件）。
    B 轮（**空袭任务、未接敌**）  女仆 = 空袭任务（MaidTask:"maid_smart:flight_combat"）、
        背包只有鞘翅+烟花（**没有武器**所以空袭链路自己起不来）、周围没有怪、主人 10 格外 →
        **必须起飞**（旧版那句"空袭任务就不起飞"已删除）。
    C 轮（**威胁中断**）  主人放在 30 格外、台子上她的航线中间站一只 NoAI 无敌僵尸：
        她起飞后飞过僵尸身边（威胁半径 8 格内）→ 链路**当场中断**：结束行的距离必须 ≥8 格
        （证明不是"进 4 格收手"），且**没有**「解除火箭推进矢量」那一行（威胁是"撤"、保持动量；
        进距离才是"收"）。僵尸清掉之后她应当**能再飞**（威胁是原因的证据，软判据）。
    D 轮（开关关着）  一行飞行跟随日志都没有。

视觉那三样（游泳展翅姿态 / 鞘翅翅膀 / 俯冲前倾）是**纯客户端渲染**，服务端日志与 NBT 都读不到，
本脚本不验。

【为什么还是用 /maid_smart flyfollow】这条链的 target 是**在线主人实体**（getOwner() 走
PlayerList），专用服务器上没有玩家 → 恒为 null，结构上无法端到端触发。所以照实测六百〇八 的先例，
用命令给指定女仆挂一个"替代主人"（本脚本是一尊 NoAI 无重力村民），走的仍是同一套判定与飞行链路。

场景（每轮重建）：锚点（Marker 盔甲架）→ 锚点那一层铺 5×73 石头台（台面在锚点下方 1 格）、
上方 5 格清成空气（保证"她↔目标之间没有方块挡住视线"这条判据必然成立）；女仆站在锚点上，
背包里放鞘翅 + 64 枚烟花；替代主人站在台子另一端（A 轮 10 格、C 轮 30 格）。

【踩过的坑（都留在这儿）】
  ① `data get entity … <字段>` 的回显**不带字段名**，所以按"女仆名字 + entity data:"筛行、再按
     值的形状分类（`[数字,数字,数字]` = Pos / 含 `Items:[` = 背包 / 其余中括号 = ArmorItems）；
     **Motion 的形状与 Pos 完全一样**，只能靠"发送顺序"认：A 轮那次取证固定是
     [Pos(M), Motion(M), Pos(T)] 三条，从末尾数第二条就是 Motion；
  ② 数物品必须**贴着 id 匹配它自己的 Count**（`id:"x",Count:1b`，且要先去空格）；
  ③ 清场要把**带回合后缀**的 tag 也点名，且**敌对生物单独扫一遍**（威胁半径内有一只，她就永远不起飞）；
  ④ 替代主人必须**非敌对活体**（村民）——拿僵尸当 target，她飞进威胁半径 8 格就被自己叫停；
  ⑤ C 轮的僵尸必须 NoAI + Invulnerable：她要飞过它身边，但它不能动也不能被打死
     （链路中断之后她还站在它 8 格内，僵尸一死她立刻又起飞，就测不到"中断"了）。
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

WAIT = 180          # 启动等待上限（秒）
PHASE1_MAX = 40     # 起飞 + 结束 的观察上限（秒，0.5 秒一轮询日志）
PHASE2_MAX = 45     # tp 走替代主人之后，等她"再飞一趟"的上限（秒）
THREAT_WATCH = 12   # C 轮：僵尸在场时"不再起飞"的观察窗口（秒）
FAIL_PATTERNS = ('Mixin apply for mod promaid failed', 'InvalidInjectionException',
                 'MixinTransformerError', 'MixinApplyError', 'Failed to create brain',
                 'OutOfMemoryError')
FATAL_HINTS = ('promaid', 'mixin')

NAME = 'Fly612Maid'
TARGET_NAME = 'Fly612Target'
MARK_TAKEOFF = '背上鞘翅追过去'
MARK_BOOST = '补一枚烟花追主人'
MARK_END = '结束（主人 '
MARK_RELEASE = '解除火箭推进矢量'
MARK_SKIP_THREAT = '附近有敌对生物'
CAT = '飞行跟随'
# 旧版（六百〇八 / 六百一十一）的结束行**没有距离**——红轮靠这两条认出"跑的是旧 jar"
LEGACY_END = ('结束（已落地', '结束（仍在滑翔下降')
# 旧版那句"空袭任务一律不起飞"（红轮会看到它）
LEGACY_FLIGHT_SKIP = '空袭任务（它自己有飞行作战链路）'

which = sys.argv[1] if len(sys.argv) > 1 else '1201'
cfg = TARGETS.get(which)
if not cfg:
    print('usage: python test_flight612.py [1201] [--jar <path>]')
    sys.exit(2)
# --jar：红轮要跑**旧 jar**（改造前那份）。不指定就用 patched/promaid-1.2.2.jar（绿轮）。
if '--jar' in sys.argv:
    cfg = dict(cfg)
    cfg['jar'] = sys.argv[sys.argv.index('--jar') + 1]
# --only A2：只跑其中一轮（红轮补单项证据时用，省得整轮重跑）
ONLY = None
if '--only' in sys.argv:
    ONLY = sys.argv[sys.argv.index('--only') + 1].upper()
ROUNDS = [ONLY] if ONLY else ['A', 'A2', 'B', 'C', 'D']

server = cfg['dir']
pid_file = os.path.join(server, 'server.pid')
CONFIG = os.path.join(server, 'config', 'promaid-common.toml')

MAID_OFF = '~ ~ ~'
# A 轮：替代主人放 **10 平 + 8 高**（3D ≈ 12.8 格）——这个数有两层用意：
#   ① 旧默认 16 时她在 12.8 格**不该起飞**（这就是本批"默认 16→5"那一条的红/绿分界），
#      新默认 5 时必须起飞；
#   ② 主人比她高 8 格 → 她收手那一刻还在**离地好几格**的空中，Motion 取样能取到"还在滑翔"的
#      那一份（平着放的话她一秒内就落地了，取到的是她在台面上走路的速度，验不出"矢量解除"）。
TARGET_NEAR = '~ ~8 ~10'
TARGET_FAR_OFF = '~ ~ ~64'  # C 轮：替代主人放 64 格外（长航程，好让"威胁"在途中插进来）
TARGET_FAR = 64             # A 轮阶段二：把替代主人 tp 到 64 格外（tp 指令要的是整数）
FLIGHT_TASK = 'maid_smart:flight_combat'


def stop_server():
    if os.path.exists(pid_file):
        pid = open(pid_file).read().strip()
        subprocess.run(['taskkill', '/PID', pid, '/T', '/F'], capture_output=True)
        time.sleep(3)


def read_config():
    raw = open(CONFIG, 'rb').read()
    try:
        return raw.decode('utf-8')
    except Exception:
        return raw.decode('gbk', errors='replace')


def set_bridge_flags(**kv):
    """改 [flightFollow] 小节里的开关。键不存在就插在 [bridge] 那一行后面。"""
    t = read_config()
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


def drop_bridge_key(key):
    """删掉 [flightFollow] 小节里的某一项——**这样代码里的默认值才真正生效**
    （Forge/NeoForge 只在使用新默认值时才会把文件改写回去；文件里写着旧值就不会动它）。"""
    t = read_config()
    out = [l for l in t.split('\n')
           if not re.match(r'^\s*%s\s*=\s*\S+\s*$' % re.escape(key), l)]
    open(CONFIG, 'wb').write('\n'.join(out).encode('utf-8'))
    return True


def config_value(key):
    m = re.search(r'^\s*%s\s*=\s*(\S+)\s*$' % re.escape(key), read_config(), re.M)
    return m.group(1) if m else None


def count_item(text, item_id):
    """数某件物品的总数（贴着 id 匹配它自己的 Count，不隔着别的物品乱抓）。"""
    t = text.replace(' ', '')
    total = 0
    for m in re.finditer(r'id:"%s",Count:(\d+)b' % re.escape(item_id), t):
        total += int(m.group(1))
    for m in re.finditer(r'Count:(\d+)b,[A-Za-z_]+:"[^"]*",id:"%s"' % re.escape(item_id), t):
        total += int(m.group(1))
    return total


def maid_dumps(data, name):
    res = {'pos': [], 'inv': [], 'armor': []}
    for l in data.splitlines():
        if name not in l or 'entity data:' not in l:
            continue
        val = l.split('entity data:', 1)[1].strip()
        if val.startswith('[') and '{' not in val:
            res['pos'].append(val)
        elif 'Items:' in val:
            res['inv'].append(val)
        elif val.startswith('[') and '{' in val:
            res['armor'].append(val)
    return res


def parse_vec(val):
    m = re.match(r'\[(-?[\d.]+)d?,\s*(-?[\d.]+)d?,\s*(-?[\d.]+)d?\]', val.strip())
    if not m:
        return None
    return tuple(float(x) for x in m.groups())


def probe_marks(M, T, send):
    """发三条带**标记**的取证（`data get entity <字段>` 的回显不带字段名，只能靠标记认）。
    标记用 say 打，读日志时按标记切段，段内最后一条 `entity data:` 就是要的那个值。"""
    send('say PROBE1_POSM')
    send('data get entity %s Pos' % M)
    send('say PROBE2_MOTION')
    send('data get entity %s Motion' % M)
    send('say PROBE3_POST')
    send('data get entity %s Pos' % T)
    send('say PROBE4_END')


def probe_pick(snapshot, name):
    """从标记切段里取出 (女仆 Pos, 女仆 Motion, 目标 Pos)。"""
    segs = {}
    cur = None
    for l in snapshot.splitlines():
        if 'PROBE1_POSM' in l:
            cur = 'pos_m'
        elif 'PROBE2_MOTION' in l:
            cur = 'motion'
        elif 'PROBE3_POST' in l:
            cur = 'pos_t'
        elif 'PROBE4_END' in l:
            cur = None
        elif cur and name in l and 'entity data:' in l:
            segs[cur] = l.split('entity data:', 1)[1].strip()
    return segs.get('pos_m'), segs.get('motion'), segs.get('pos_t')


def run_round(suffix, maid_task=None, target_off=TARGET_NEAR, zombie=False, phases=True,
              release_probe=True):
    log_path = os.path.join(server, 'console_fly612_%s.log' % suffix)
    log = open(log_path, 'wb')
    # 【-Xmx2G 而不是 3G】本批跑红轮时这台机器报过 "insufficient memory ... G1 virtual space"
    # （DOS error 1455：页文件不够），C/D 两轮的服务器压根没起来。测试世界很小，2G 足够。
    p = subprocess.Popen([cfg['java'], '-Xmx2G', '-Dfile.encoding=UTF-8'] + cfg['args'],
                         cwd=server, stdout=log, stderr=subprocess.STDOUT,
                         stdin=subprocess.PIPE,
                         creationflags=subprocess.CREATE_NEW_PROCESS_GROUP | 0x00000008)
    open(pid_file, 'w').write(str(p.pid))
    print('--- round %s: pid %d' % (suffix, p.pid))

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
            if CAT in t or MARK_BOOST in t:
                return t
        for e in ('utf-8', 'gbk'):
            if 'Done (' in d[e]:
                return d[e]
        return d['utf-8']

    res = {'notes': [], 'data': '', 'takeoff': 0, 'takeoff_p1': 0, 'boost': 0, 'end': 0,
           'release': 0, 'legacy_end': 0, 'legacy_flight_skip': 0, 'restore': 0,
           'takeoff_line': '', 'end_line': '', 'end_dists': [], 'release_line': '',
           'measured_end': None, 'motion_h': None, 'motion_raw': '',
           'fw_before': None, 'fw_after': None, 'wore_mid': 0, 'dmg_mid': 0,
           'elytra_total': 0, 'chest_after': '', 'takeoff_after_threat_kill': None}

    done = False
    for _ in range(WAIT):
        time.sleep(1)
        if p.poll() is not None:
            break
        if 'Done (' in readlog():
            done = True
            break
    if not done:
        res['notes'] = ['%s: FAIL(server never reached Done)' % suffix]
        send('stop')
        for _ in range(40):
            time.sleep(1)
            if p.poll() is not None:
                break
        if p.poll() is None:
            subprocess.run(['taskkill', '/PID', str(p.pid), '/T', '/F'], capture_output=True)
        return res

    send('gamerule doMobSpawning false')
    send('difficulty easy')
    for _ in range(2):
        for base in ('fly612maid', 'fly612target', 'fly612anchor', 'fly612zombie'):
            for suf in ('', 'a', 'b', 'c', 'd'):
                send('kill @e[tag=%s%s]' % (base, suf))
        time.sleep(1)
    for mob in ('zombie', 'husk', 'drowned', 'zombie_villager', 'skeleton', 'stray',
                'creeper', 'spider', 'cave_spider', 'witch', 'slime', 'phantom'):
        send('kill @e[type=minecraft:%s]' % mob)
    # 大扫除：测试世界被前几批积了不少女仆，只留**自己这一轮**的
    send('kill @e[type=touhou_little_maid:maid]')
    send('kill @e[type=minecraft:villager]')
    send('kill @e[type=minecraft:armor_stand]')
    send('kill @e[type=minecraft:tnt]')
    time.sleep(2)

    tag = 'fly612maid' + suffix.lower()
    ttag = 'fly612target' + suffix.lower()
    atag = 'fly612anchor' + suffix.lower()
    ztag = 'fly612zombie' + suffix.lower()

    # ① 锚点 + 11×73 台面（台面在锚点下方 1 格）+ 上方 5 格清空。
    # 【为什么从 5 格宽加到 11 格】六百一十一 的台面是 5 格宽：那批的替代主人与她在同一高度，
    # 她直着飞、直着落，落点总在台面上。本批把替代主人抬高 8 格（见 TARGET_NEAR 的注释），
    # 她就**斜着往上飞**，收手那一刻已经在台面边缘外——第一版用例里她直接掉到台面下的地形上，
    # 之后"与目标之间被方块挡住视线"→ 再也起飞不了（重启那一项就是这么挂的）。
    send('summon minecraft:armor_stand ~ ~ ~ {Marker:1b,NoGravity:1b,Invisible:1b,'
         'Tags:["%s"]}' % atag)
    time.sleep(2)
    A = '@e[tag=%s,limit=1]' % atag
    send('execute at %s run fill ~-5 ~-1 ~-4 ~5 ~-1 ~68 minecraft:stone' % A)
    time.sleep(1)
    send('execute at %s run fill ~-5 ~ ~-4 ~5 ~4 ~68 minecraft:air' % A)
    time.sleep(2)

    # ② 女仆：胸甲槽空着；背包里放鞘翅 + 64 枚烟花（**武器一件都不给**）
    items = '{Slot:0b,%s},{Slot:1b,%s}' % (cfg['elytra'], cfg['firework'])
    task_nbt = 'MaidTask:"%s",' % maid_task if maid_task else ''
    maid_nbt = ('{%sMaidScheduleMode:"ALL",HandItems:[{},{}],ArmorItems:[{},{},{},{}],'
                'MaidInventory:{Size:36,Items:[%s]},'
                'Owner:[I;1,2,3,4],Tags:["%s"],CustomName:"\\"%s\\"",PersistenceRequired:1b}'
                % (task_nbt, items, tag, NAME + suffix))
    send('execute at %s run summon touhou_little_maid:maid %s %s' % (A, MAID_OFF, maid_nbt))
    time.sleep(3)
    M = '@e[tag=%s,limit=1]' % tag

    # ③ 替代主人：非敌对活体（村民），NoAI + 无重力
    send('execute at %s run summon minecraft:villager %s {NoAI:1b,NoGravity:1b,Silent:1b,'
         'Invulnerable:1b,PersistenceRequired:1b,Health:2000f,'
         'Attributes:[{Name:"%s",Base:2000}],Tags:["%s"],'
         'CustomName:"\\"%s\\""}' % (A, target_off, cfg['maxhp_attr'], ttag, TARGET_NAME + suffix))
    time.sleep(2)
    T = '@e[tag=%s,limit=1]' % ttag

    # ③b C 轮：**先不刷僵尸**——等她起飞之后再把僵尸刷到她旁边（见阶段一循环里的 inject_threat）。
    # 为什么不一开始就摆在航线上：威胁半径 8 格是**绕着她**判的，摆远了判不到、摆近了
    # （起飞前就在 8 格内）她压根不起飞，都测不到"飞到一半遇到怪 → 链路当场中断"。
    Z = '@e[tag=%s,limit=1]' % ztag

    # ④ 基线（Pos / 背包 / 胸甲 / 任务）+ 脚手架自检
    send('data get entity %s Pos' % M)
    send('data get entity %s MaidInventory' % M)
    send('data get entity %s ArmorItems' % M)
    if maid_task:
        send('data get entity %s MaidTask' % M)
    send('execute at %s run execute if entity %s run say SETUP_%s' % (A, T, suffix))
    time.sleep(2)

    # ⑤ 挂"替代主人"（第三个参数点名女仆，不靠"最近的"）
    send('maid_smart flyfollow %s %s' % (T, M))

    def counts(data):
        mine = [l for l in data.splitlines() if CAT in l and (NAME + suffix) in l]
        return (len([l for l in mine if MARK_TAKEOFF in l]),
                len([l for l in mine if MARK_BOOST in l]),
                len([l for l in mine if MARK_END in l]),
                len([l for l in mine if any(k in l for k in LEGACY_END)]),
                len([l for l in mine if MARK_RELEASE in l]),
                mine)

    # ⑥ 阶段一：等她起飞并在收手半径内收手 + 解除矢量
    take1 = 0
    last_end = 0
    probed = False
    probe_snapshot = ''
    injected = False
    t0 = time.time()
    while time.time() - t0 < PHASE1_MAX:
        time.sleep(0.5)
        data = readlog()
        tk, bo, en, lg, rl, mine = counts(data)
        take1 = tk
        if zombie and tk >= 1 and not injected:
            # C 轮：她已经在飞了 → 把僵尸刷到她旁边（3 格外，不贴脸），威胁半径 8 格内
            injected = True
            send('execute at %s run summon minecraft:zombie ~ ~-1 ~3 '
                 '{NoAI:1b,NoGravity:1b,Invulnerable:1b,Silent:1b,PersistenceRequired:1b,'
                 'Health:2000f,Attributes:[{Name:"%s",Base:2000}],Tags:["%s"]}'
                 % (M, cfg['maxhp_attr'], ztag))
            time.sleep(1)
            send('data get entity %s Pos' % M)
            send('data get entity %s Pos' % T)
            res['injected'] = True
        if en > last_end and not probed:
            last_end = en
            probed = True
            # 【为什么先摘掉调试目标再取样】默认触发距离只有 5 格，她在收手半径里一沉就又超过
            # 5 格 → **一秒内自己又起飞**；那时 Motion 取到的是**新一趟的推进速度**（第一版用例
            # 就是这样：自报 3.8 格收手、样例却是 1.216 格/tick 在爬升）。摘掉目标 = 本趟之后
            # 不会再有新链，取到的才是"解除之后"的真实状态。
            if release_probe:
                send('maid_smart flyfollow clear')
                time.sleep(1)
                probe_marks(M, T, send)
                time.sleep(2)
                probe_snapshot = readlog()
        if tk >= 1 and (en >= 1 or lg >= 1):
            break
    time.sleep(1)
    if not probed:
        # 没等到结束行也抓一次（红轮：她压根没起飞，这里的数字只作记录）
        send('say PROBE1_POSM')
        send('data get entity %s Motion' % M)
        send('say PROBE4_END')
        time.sleep(1)
        probe_snapshot = readlog()
    send('data get entity %s ArmorItems' % M)
    send('data get entity %s Pos' % M)

    # ⑥b C 轮：僵尸在场时**不该再起飞**（观察窗口），随后清掉僵尸看她还飞不飞（= 威胁就是原因）
    if zombie:
        tk_now = counts(readlog())[0]
        t0 = time.time()
        while time.time() - t0 < THREAT_WATCH:
            time.sleep(1)
        tk_watch = counts(readlog())[0]
        send('kill @e[type=minecraft:zombie]')
        send('kill %s' % Z)
        time.sleep(2)
        res['takeoff_while_threat'] = tk_watch
        res['takeoff_after_threat_kill'] = None
        t0 = time.time()
        while time.time() - t0 < PHASE2_MAX:
            time.sleep(1)
            if counts(readlog())[0] > tk_watch:
                res['takeoff_after_threat_kill'] = counts(readlog())[0]
                break
        # 清掉僵尸之后飞的那一趟，把它的结束行也抓一份（能证明"第一趟是威胁断的"）
        if res['takeoff_after_threat_kill'] is not None:
            time.sleep(3)
            res['data'] = readlog()
        _ = tk_now

    # ⑦ 阶段二：把替代主人 tp 到 64 格外**并重新挂上**（阶段一取证时摘掉过）——
    # 她应当**再飞一趟**（重启）。重新挂之前先等 6 秒：起飞行是 5 秒限频的，
    # 不等的话这一趟的起飞日志会被上一趟的吃掉，验收就读不到"又飞了一趟"。
    if phases:
        time.sleep(6)
        send('execute as %s run tp @s ~ ~ ~%d' % (T, TARGET_FAR))
        time.sleep(1)
        send('maid_smart flyfollow %s %s' % (T, M))
        time.sleep(2)
        t0 = time.time()
        while time.time() - t0 < PHASE2_MAX:
            time.sleep(1)
            tk, bo, en, lg, rl, mine2 = counts(readlog())
            # 旧 jar 的"12.8 格不起飞"要到这一阶段才飞起来 —— 它的 Motion 取证也跟着补在这里
            if en > last_end and not probed:
                last_end = en
                probed = True
                if release_probe:
                    send('maid_smart flyfollow clear')
                    time.sleep(1)
                    probe_marks(M, T, send)
                    time.sleep(2)
                    probe_snapshot = readlog()
            if tk > take1:
                break
        # 没再飞起来的话，把"为什么"记进 note（「飞行跟随跳过」那一行会点名原因）
        if tk <= take1:
            skips = [l for l in readlog().splitlines()
                     if CAT + '跳过' in l and (NAME + suffix) in l]
            res['restart_skip'] = skips[-1][-170:] if skips else '(没有跳过日志)'
    elif not zombie:
        time.sleep(max(1, 12))
        send('execute as %s run tp @s ~ ~ ~%d' % (T, TARGET_FAR))
        time.sleep(1)

    # ⑧ 战后取证
    send('data get entity %s MaidInventory' % M)
    send('data get entity %s ArmorItems' % M)
    send('execute at %s run execute if entity %s run say NEAR_%s' % (M, T, suffix))
    time.sleep(3)

    data = readlog()
    notes = []
    for pat in FAIL_PATTERNS:
        if pat in data:
            notes.append('%s: FAIL(error line: %s)' % (suffix, pat))
    for l in data.splitlines():
        if 'FATAL' in l and any(h in l.lower() for h in FATAL_HINTS):
            notes.append('%s: FATAL %s' % (suffix, l[:140]))
            break

    tk, bo, en, lg, rl, mine = counts(data)
    takeoff = [l for l in mine if MARK_TAKEOFF in l]
    end = [l for l in mine if MARK_END in l]
    release = [l for l in mine if MARK_RELEASE in l]
    restore = [l for l in mine if '已落地，把胸甲还回去' in l]
    skip_threat = [l for l in mine if MARK_SKIP_THREAT in l]
    legacy_skip = [l for l in mine if LEGACY_FLIGHT_SKIP in l]
    end_dists = [float(m.group(1)) for m in
                 (re.search(r'结束（主人 ([0-9.]+) 格', l) for l in end) if m]
    release_dists = [float(m.group(1)) for m in
                     (re.search(r'进到主人 ([0-9.]+) 格内，解除火箭推进矢量', l)
                      for l in release) if m]
    chest_restore_log = [l for l in data.splitlines()
                         if '槽=CHEST' in l and 'elytra' in l and (NAME + suffix) in l]

    d = maid_dumps(data, NAME + suffix)
    inv, armor = d['inv'], d['armor']
    fw_before = count_item(inv[0], 'minecraft:firework_rocket') if inv else None
    fw_after = count_item(inv[-1], 'minecraft:firework_rocket') if inv else None
    mid_armor = armor[1] if len(armor) > 1 else ''
    wore_mid = count_item(mid_armor, 'minecraft:elytra')
    dmg_mid = 0
    m = re.search(r'id:"minecraft:elytra",Count:\d+b,tag:\{[^}]*?Damage:(\d+)',
                  mid_armor.replace(' ', ''))
    if m:
        dmg_mid = int(m.group(1))
    chest_after = armor[-1].replace(' ', '') if armor else ''
    elytra_total = (count_item(inv[-1], 'minecraft:elytra') if inv else 0) \
        + (count_item(armor[-1], 'minecraft:elytra') if armor else 0)

    # 解除矢量的**独立取证**：按 say 标记切段取（Pos(女仆) / Motion(女仆) / Pos(目标)）
    motion_raw = ''
    motion_h = None
    measured = None
    probe_air = None
    base_y = None
    if probe_snapshot:
        pm, mv_raw, pt = probe_pick(probe_snapshot, NAME + suffix)
        pd = maid_dumps(probe_snapshot, NAME + suffix)
        bl = parse_vec(pd['pos'][0]) if pd['pos'] else None   # ④ 里那份基线 Pos（起飞前）
        pv = parse_vec(pm or '')
        if bl and pv:
            base_y, probe_air = bl[1], pv[1]
        if mv_raw:
            motion_raw = mv_raw
            mv = parse_vec(mv_raw)
            if mv:
                motion_h = (mv[0] ** 2 + mv[2] ** 2) ** 0.5
        a, b = parse_vec(pm or ''), parse_vec(pt or '')
        if a and b:
            measured = ((a[0] - b[0]) ** 2 + (a[1] - b[1]) ** 2 + (a[2] - b[2]) ** 2) ** 0.5
        res['measured_end'] = measured

    notes.append('%s: 起飞 %d / 补推 %d / 带距离的结束 %d / 旧式结束(无距离) %d / 解除矢量 %d'
                 % (suffix, tk, bo, en, lg, rl))
    notes.append('%s: 结束距离 %s ; 解除行距离 %s ; 解除那一刻 Motion=%s (水平 %.3f) ; '
                 '那一刻她在空中=%s (y=%s vs 基线 %s)'
                 % (suffix, ['%.1f' % x for x in end_dists],
                    ['%.1f' % x for x in release_dists],
                    motion_raw or 'n/a', -1.0 if motion_h is None else motion_h,
                    probe_air, probe_air, base_y))
    notes.append('%s: 烟花 基线=%s 战后=%s ; 飞行中胸甲鞘翅=%d(Damage %d) ; 战后鞘翅件数=%d 胸甲槽=%s'
                 % (suffix, fw_before, fw_after, wore_mid, dmg_mid, elytra_total,
                    chest_after or '?'))
    if legacy_skip:
        notes.append('%s: **旧口径跳过行**（说明跑的是旧 jar）: %s' % (suffix, legacy_skip[0][-150:]))
    for label, lst in (('起飞', takeoff), ('补推', [l for l in mine if MARK_BOOST in l]),
                       ('结束', end), ('解除', release), ('跳过·威胁', skip_threat),
                       ('归还', restore)):
        if lst:
            notes.append('%s %s示例: %s' % (suffix, label, lst[0][-190:]))
    if zombie:
        notes.append('%s: 僵尸在场期间起飞总数=%s；清掉僵尸后飞到=%s'
                     % (suffix, res.get('takeoff_while_threat'), res['takeoff_after_threat_kill']))

    send('stop')
    for _ in range(40):
        time.sleep(1)
        if p.poll() is not None:
            break
    if p.poll() is None:
        subprocess.run(['taskkill', '/PID', str(p.pid), '/T', '/F'], capture_output=True)

    res.update({'notes': notes, 'data': data, 'takeoff': tk, 'takeoff_p1': take1, 'boost': bo,
                'end': en, 'release': rl, 'legacy_end': lg,
                'legacy_flight_skip': len(legacy_skip),
                'restore': len(restore) + len(chest_restore_log),
                'takeoff_line': takeoff[0] if takeoff else '',
                'end_line': end[0] if end else '',
                'release_line': release[0] if release else '',
                'end_dists': end_dists, 'release_dists': release_dists,
                'fw_before': fw_before, 'fw_after': fw_after,
                'wore_mid': wore_mid, 'dmg_mid': dmg_mid,
                'elytra_total': elytra_total, 'chest_after': chest_after,
                'motion_h': motion_h, 'motion_raw': motion_raw,
                'probe_air': probe_air, 'base_y': base_y})
    return res


stop_server()
for old in [f for f in os.listdir(os.path.join(server, 'mods')) if f.startswith('promaid-')]:
    if old != cfg['modname']:
        os.remove(os.path.join(server, 'mods', old))
shutil.copyfile(cfg['jar'], os.path.join(server, 'mods', cfg['modname']))
print('jar copied:', cfg['modname'], os.path.getsize(cfg['jar']))

verdict = 'PASS'
failures = []       # 多条判据一起报——红轮要能一眼看出"差在哪几处"，而不是只报第一条
notes = []


def fail(msg):
    failures.append(msg)


a = a2 = b = c = d = {}   # --only 只跑某一轮时，其余轮次留空（下面的打印循环按空跳过）

if 'A' in ROUNDS:
    # ── A 轮：代码默认档（把 dist / endDist 两行删掉，让**六百一十五 的新默认 25 / 5** 生效）──
    # 【为什么必须删那两行】Forge/NeoForge 只在"使用新默认值"时改写文件——文件里已经写着旧值的存档，
    # 升级后不会自动变（这条写进 changelog 的"升级注意"）。要验默认值就得让那两项缺席。
    # 【六百一十五 起这一轮的期望**反过来**了】旧默认 5 时"12.8 格该起飞"；新默认 25 时
    # "12.8 格**不该起飞**、tp 到 64 格才飞"——判据见下面那段。

    drop_bridge_key('dist')
    print('config A:', set_bridge_flags(enabled='true', firework='true',
                                        elytra='true'),
          '; flightFollowDist ->', config_value('dist'))
    a = run_round('A', phases=True)
    notes += a['notes']
    dist_after = config_value('dist')
    notes.append("A 轮跑完配置文件里的 flightFollowDist = %s（新默认应为 25.0；旧版是 5.0 / 16.0）"
                 % dist_after)
    if dist_after is not None and abs(float(dist_after) - 25.0) > 0.01:
        fail('A 轮配置文件里 dist 被写回 %s —— 起手距离默认值不是 25（六百一十五 的口径）'
             % dist_after)
    if a['takeoff_p1'] > 0:
        fail('A 轮替代主人只在约 12.8 格外（10 平 + 8 高）时她**起飞了** —— 起手距离默认值没收紧'
             '（新默认 25 应当挡住 12.8 格；dist=%s）' % dist_after)
    elif a['takeoff'] == 0:
        fail('A 轮把替代主人 tp 到 64 格外之后她**压根没起飞** —— 判定/接线哪一环不对'
             '（dist=%s）' % dist_after)
    if a['end'] == 0 and a['legacy_end'] > 0:
        fail('A 轮结束行没有距离（旧式"结束（…）" %d 行）—— 旧口径' % a['legacy_end'])
    elif a['end'] == 0:
        fail('A 轮既没有带距离的结束行，也没有旧式结束行 —— 观察窗口内她没走完一趟')
    elif min(a['end_dists']) > 5.5:
        fail('A 轮收手半径不是"新默认 5 格"那一档：最短结束距离 %.1f 格（六百一十五 起收手半径'
             '由 flightFollow.endDist 决定，默认 5）' % min(a['end_dists']))
    if a['release'] == 0:
        fail('A 轮没有「解除火箭推进矢量」那一行 —— 进半径解除矢量没接上（旧 jar 会在这里挂）')
    elif a['release_dists'] and max(a['release_dists']) > 5.5:
        fail('A 轮的解除行出现在 %.1f 格 —— 那是"还没进半径就解除"，判据接错了'
             % max(a['release_dists']))
    if a['motion_h'] is not None and a['motion_h'] >= 0.5:
        # 【不是判据，是参考值】默认起手 25 / 收手 5（六百一十五）：她收手后要飘出去 20 格才会重启，
        # 取样常常落在"新一趟的推进"上（本批实测取到 1.673 = 烟花推力的不动点那一档）。
        # "矢量确实归零"的**硬判据**放在 A2 轮（触发距离 20 → 收手半径 15，收手后不会立刻重启）。
        notes.append('参考值：A 轮 Motion 取样 = %.3f 格/tick（%s）—— 默认 25/5 档收手后要飘 20 格才会重启，'
                     '这个数常常落在新一趟的推进上，不作为判据（硬判据见 A2 轮）'
                     % (a['motion_h'], '在空中' if (a['probe_air'] or 0) > (a['base_y'] or 0) + 1
                        else '在地面'))
    if a['release'] > 0 and a['motion_h'] is not None and a['motion_h'] < 0.5 \
            and a['probe_air'] is not None and a['base_y'] is not None \
            and a['probe_air'] <= a['base_y'] + 1.0:
        notes.append('警告：A 轮的 Motion 取样取在了地面（y=%s ≤ 基线 %s+1）—— "矢量已归零"这一条'
                     '这次不算数（她收手后一秒内就落地了），只有日志那一行是硬证据'
                     % (a['probe_air'], a['base_y']))
    if a['motion_h'] is None:
        notes.append('警告：A 轮没取到 Motion 样本（独立取证缺一条）')
    if a['takeoff'] > 0 and a['takeoff'] <= a['takeoff_p1']:
        fail('A 轮"飞出去会再启动"没生效：把替代主人 tp 到 64 格外之后没有新的起飞行'
             '（起飞 %d = 阶段一的 %d）%s'
             % (a['takeoff'], a['takeoff_p1'],
                ('；当时最后一次跳过：' + a['restart_skip']) if a.get('restart_skip') else ''))
    if a['fw_before'] is not None and a['fw_after'] is not None and a['fw_after'] >= a['fw_before']:
        fail('A 轮烟花一枚没少（%s→%s）—— 消耗档没扣' % (a['fw_before'], a['fw_after']))
    if a['takeoff'] > 0 and a['elytra_total'] != 1:
        fail('A 轮鞘翅件数=%d（应为 1：没丢也没多）' % a['elytra_total'])
    if not failures:
        notes.append('阳性对照：A 轮（tp 到 64 格外的替代主人）起飞成功、结束距离 %s（≤5 = 新收手半径 5 那一档）、'
                     '解除矢量 %d 行、结束那一刻水平 Motion=%.3f、tp 到 64 格后又飞了 %d 趟'
                     % (['%.1f' % x for x in a['end_dists']], a['release'],
                        -1.0 if a['motion_h'] is None else a['motion_h'],
                        a['takeoff'] - a['takeoff_p1']))
        if a['restore'] == 0:
            notes.append('提示：A 轮没读到归还（「已落地，把胸甲还回去」或「槽=CHEST 失去鞘翅」都没有）')

if 'A2' in ROUNDS:
    # ── A2 轮：**"解除推进矢量"的硬判据**（独立取证：结束之后的 Motion）──
    # 为什么单独一轮：默认收手半径只有 5 格，收手后她一沉就可能又超过起手线 → 一秒钟内**自己又
    # 起飞**，Motion 取样必然和"新一趟的推进"打架（A 轮那个 1.673 就是这么来的）。
    # 这一轮把触发距离设成 20（六百一十五 起收手半径与它无关，取 endDist 默认 5）、替代主人放到
    # 约 40.8 格外：她 5 格收手，之后要飘出去 15 格才会重启——取样稳落在"解除之后"的那一刻。
    print('config A2:', set_bridge_flags(enabled='true', firework='true',
                                         elytra='true', dist='20.0'))
    a2 = run_round('A2', target_off='~ ~8 ~40', phases=False, release_probe=True)
    notes += a2['notes']
    if a2['takeoff'] == 0:
        fail('A2 轮（触发距离 20、替代主人约 40.8 格外）没起飞 —— 场景/判据哪一环不对')
    elif a2['release'] == 0:
        fail('A2 轮没有「解除火箭推进矢量」那一行')
    elif not a2['end_dists'] or min(a2['end_dists']) > 5.5:
        fail('A2 轮收手距离不是"endDist 默认 5"那一档：%s（六百一十五 起收手半径**不再**由起手距离'
             '推导，20 那一档也是 5）' % (['%.1f' % x for x in a2['end_dists']] or '无'))
    elif a2['motion_h'] is None:
        notes.append('警告：A2 轮没取到 Motion 样本（独立取证缺一条）')
    elif a2['probe_air'] is not None and a2['base_y'] is not None \
            and a2['probe_air'] <= a2['base_y'] + 1.0:
        notes.append('警告：A2 的 Motion 取样落在了地面（y=%s）—— 这一条这次不算数'
                     % a2['probe_air'])
    elif a2['motion_h'] >= 0.5:
        fail('A2 轮**独立取证**不过：解除之后她仍以 %.3f 格/tick 的水平速度在飞'
             '（解除后应接近 0；烟花推力的不动点是 1.7 格/tick）—— 矢量没被真正解除'
             '（旧 jar 走的正是这条）' % a2['motion_h'])
    else:
        notes.append('阳性对照：A2 轮收手距离 %s（= endDist 默认 5 那一档）、解除行 %d 条，'
                     '**独立取证**：解除之后她还在空中（y=%.1f，基线 %.1f）'
                     '水平速度只有 %.3f 格/tick（旧版是 1.7 那一档）'
                     % (['%.1f' % x for x in a2['end_dists']], a2['release'],
                        a2['probe_air'] if a2['probe_air'] is not None else -1,
                        a2['base_y'] if a2['base_y'] is not None else -1,
                        a2['motion_h']))

if 'B' in ROUNDS:
    # ── B 轮：空袭任务**未接敌**也必须能触发（六百一十二 第 2 条）──
    print('config B:', set_bridge_flags(enabled='true', firework='true',
                                        elytra='true', dist='10.0'))
    b = run_round('B', maid_task=FLIGHT_TASK, phases=False)
    notes += b['notes']
    if b['takeoff'] == 0:
        if b['legacy_flight_skip']:
            fail('B 轮空袭任务被整类跳过（"%s"）—— "未接敌也照飞"没生效' % LEGACY_FLIGHT_SKIP)
        else:
            fail('B 轮空袭任务（未接敌、无武器、周围无怪）没有起飞 —— '
                 '触发判定没推广到所有模式（看「飞行跟随跳过」那一行说了什么）')
    elif b['end'] == 0:
        notes.append('警告：B 轮起飞了但没读到带距离的结束行')
    else:
        notes.append('阳性对照：B 轮空袭任务（未接敌）照样起飞并收手（起飞 %d / 结束 %d），'
                     '旧版那句"空袭任务一律不起飞"确实已删除' % (b['takeoff'], b['end']))

if 'C' in ROUNDS:
    # ── C 轮：威胁出现 → 链路当场中断（与搭路同款判据）──
    # 场景：替代主人摆到 **64 格**外（航程长），她起飞之后**把僵尸刷到她旁边 3 格**——
    # 威胁半径 8 格内出现敌对生物，canContinue 里那条与搭路逐字同源的判据必须当场断链。
    # 她这时离替代主人还有几十格，所以结束距离必然 ≥8：这条正是"威胁断的"而不是"进收手半径收的"。
    print('config C:', set_bridge_flags(enabled='true', firework='true',
                                        elytra='true', dist='5.0'))
    c = run_round('C', target_off=TARGET_FAR_OFF, zombie=True, phases=False, release_probe=False)
    notes += c['notes']
    if c['takeoff'] == 0:
        fail('C 轮（替代主人 64 格外）她压根没起飞 —— 场景/判据哪一环不对'
             '（看「飞行跟随跳过」那一行说了什么）')
    elif c['end'] == 0:
        fail('C 轮没有结束行 —— 飞过威胁半径（8 格）时链路没被中断'
             '（起飞 %d，僵尸在场期间起飞数=%s）' % (c['takeoff'], c.get('takeoff_while_threat')))
    elif c['end_dists'] and c['end_dists'][0] < 8.0:
        fail('C 轮**第一趟**结束距离 %.1f 格 < 8 —— 这一趟不像"遇到怪中断"，更像"进收手半径"'
             '（起飞 64 格外的替代主人，第一趟必须先被威胁掐掉；后面的趟数才是正常收手）'
             % c['end_dists'][0])
    elif c['release_dists'] and any(d > 5.5 for d in c['release_dists']):
        fail('C 轮威胁中断那一趟也打了「解除火箭推进矢量」（解除行出现在 %.1f 格）—— 威胁只该"撤"'
             '（保持动量），解除矢量是"进收手半径"那一支的事'
             % max(d for d in c['release_dists'] if d > 5.5))
    elif c['end_dists'] and c['end_dists'][0] >= 8.0:
        notes.append('阳性对照：C 轮**第一趟**飞过航线上的僵尸（威胁半径 8 格）时链路当场中断，'
                     '结束距离 %.1f 格（远大于收手半径 4），该趟没有解除矢量行（解除行只在 %.1f 格'
                     '那一趟出现 = 清掉僵尸之后她自己飞的那一趟正常收手）；'
                     '僵尸在场期间起飞数 %s，清掉僵尸后飞到 %s'
                     % (c['end_dists'][0], max(c['release_dists']) if c['release_dists'] else -1,
                        c.get('takeoff_while_threat'), c['takeoff_after_threat_kill']))

if 'D' in ROUNDS:
    # ── D 轮：开关关着（默认）──
    print('config D:', set_bridge_flags(enabled='false', firework='true',
                                        elytra='true', dist='5.0'))
    d = run_round('D', phases=False, release_probe=False)
    notes += d['notes']
    if d['takeoff'] or d['boost'] or d['release']:
        fail('D 轮开关关着还有飞行跟随日志（起飞 %d / 补推 %d / 解除 %d）—— 开关没管住'
             % (d['takeoff'], d['boost'], d['release']))

if failures:
    verdict = 'FAIL(%d 处不符：%s)' % (len(failures), ' ｜ '.join(failures))

print('verdict:', verdict)
for n in notes:
    print('note:', n)
for name, r in (('A', a), ('A2', a2), ('B', b), ('C', c), ('D', d)):
    if not r:
        continue
    print('--- 关键日志行（%s 轮）---' % name)
    shown = 0
    for l in r['data'].splitlines():
        if any(k in l for k in (CAT, 'entity data:', 'NEAR_', 'SETUP_', 'flyfollow',
                                'MaidTask', '槽=CHEST', 'Mixin apply', 'FATAL')):
            print('  ', l[:230])
            shown += 1
            if shown > 44:
                print('   ...(truncated)')
                break
    if shown == 0:
        print('   (nothing matched)')

sys.exit(0 if verdict == 'PASS' else 1)
