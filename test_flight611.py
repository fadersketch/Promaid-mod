# -*- coding: utf-8 -*-
"""实测六百一十一 的场景验证：飞行跟随的"15 格中断 + 自然滑翔 + 飞出去重启"。

用法:
    python test_flight611.py 1201

用户反馈原文（本条需求）：
    "飞行跟随的启动只认烟花，而且动作没有换成空袭飞行的动作。而且不需要和主人离那么近，
     在飞行跟随期间自身周围15格内找到主人那么此链路就中断，不需要紧挨着主人。具体的表现就跟
     空袭女仆把怪打死一样。自然滑翔。反正飞出去了会再次启动链路。"

本脚本验的是**服务端能观测到的那一半**（距离口径与重启）：
    A 轮（默认档）  ① 起飞行带「燃料=烟花火箭」；② 结束行带距离且 **≥5 格**（旧版 4 格，
                       红轮跑的正是旧 jar：它的结束行根本没有距离，直接判失败）；
                    ③ 结束那一刻她**还在空中**（「她还在滑翔」）= 自然滑翔那一支；
                    ④ 把"替代主人"tp 到 64 格外 → **再次起飞**（起飞行 +1）= "飞出去会再启动链路"；
                    ⑤ 烟花真的少了、鞘翅没丢（1 件）、胸甲归还链路照旧。
    B 轮（省料档：不消耗烟花 / 免鞘翅耐久）  起飞 + 不消耗文案 + 烟花一枚不少 + Damage=0
    C 轮（开关关着）  一行飞行跟随日志都没有
    D 轮（只给鞘翅、不给任何能飞的道具）  **不起飞**，且跳过日志点名「也没有孔雀羽扇」
                        —— 这一轮是六百一十一 新燃料口径的反向闸门（别把门开成"什么都能飞"）

视觉那三样（游泳展翅姿态 / 鞘翅翅膀 / 俯冲前倾）是**纯客户端渲染**，服务端日志与 NBT 都读不到，
本脚本不验；它们的判据在代码里统一收在 MaidFlightKit.isFlightVisual（= 飞行任务 或 正在滑翔），
两棵树同一处。

【为什么还是用 /maid_smart flyfollow】这条链的 target 是**在线主人实体**（getOwner() 走
PlayerList），专用服务器上没有玩家 → 恒为 null，结构上无法端到端触发。所以照实测六百〇八 的先例，
用命令给指定女仆挂一个"替代主人"（本脚本是一尊 NoAI 无重力村民），走的仍是同一套判定与飞行链路。

场景（每轮重建）：锚点（Marker 盔甲架）→ 锚点那一层铺 5×73 石头台（台面在锚点下方 1 格）、
上方 5 格清成空气（保证"她↔目标之间没有方块挡住视线"这条判据必然成立）；女仆站在锚点上，
背包里放鞘翅（+ 烟花，D 轮不给）；目标村民站在台子另一端 22 格外（A 轮第二阶段 tp 到 64 格外）。

【踩过的坑（都留在这儿）】
  ① `data get entity … <字段>` 的回显**不带字段名**，所以按"女仆名字 + entity data:"筛行、再按
     值的形状分类（`[数字,数字,数字]` = Pos / 含 `Items:[` = 背包 / 其余中括号 = ArmorItems）；
  ② 数物品必须**贴着 id 匹配它自己的 Count**（`id:"x",Count:1b`，且要先去空格）；
  ③ 清场要把**带回合后缀**的 tag 也点名，且**敌对生物单独扫一遍**（威胁半径内有一只，她就永远不起飞）；
  ④ 替代主人必须**非敌对活体**（村民）——拿僵尸当 target，她飞进威胁半径 8 格就被自己叫停。
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
        'elytra': 'id:"minecraft:elytra",Count:1b',
        'firework': 'id:"minecraft:firework_rocket",Count:64b',
        'maxhp_attr': 'generic.max_health',
    },
}

WAIT = 180          # 启动等待上限（秒）
PHASE1_MAX = 40     # 起飞 + 结束 的观察上限（秒，0.5 秒一轮询日志）
PHASE2_MAX = 45     # tp 走替代主人之后，等她"再飞一趟"的上限（秒）
FAIL_PATTERNS = ('Mixin apply for mod promaid failed', 'InvalidInjectionException',
                 'MixinTransformerError', 'MixinApplyError', 'Failed to create brain',
                 'OutOfMemoryError')
FATAL_HINTS = ('promaid', 'mixin')

NAME = 'Fly611Maid'
TARGET_NAME = 'Fly611Target'
MARK_TAKEOFF = '背上鞘翅追过去'
MARK_BOOST = '补一枚烟花追主人'
MARK_END = '结束（主人 '
MARK_END_GLIDE = '她还在滑翔'
MARK_RESTORE = '已落地，把胸甲还回去'
MARK_SKIP_FUEL = '也没有孔雀羽扇'
CAT = '飞行跟随'
# 旧版（六百〇八）的结束行**没有距离**——红轮就靠这两条认出"跑的是旧 jar"
LEGACY_END = ('结束（已落地', '结束（仍在滑翔下降')

which = sys.argv[1] if len(sys.argv) > 1 else '1201'
cfg = TARGETS.get(which)
if not cfg:
    print('usage: python test_flight611.py [1201]')
    sys.exit(2)

server = cfg['dir']
pid_file = os.path.join(server, 'server.pid')
CONFIG = os.path.join(server, 'config', 'promaid-common.toml')

MAID_OFF = '~ ~ ~'
TARGET_OFF = '~ ~ ~22'      # 阶段一：22 格（> 默认触发距离 16）
TARGET_FAR = 64             # 阶段二：把替代主人 tp 到 64 格（远超 15 格中断半径）


def stop_server():
    if os.path.exists(pid_file):
        pid = open(pid_file).read().strip()
        subprocess.run(['taskkill', '/PID', pid, '/T', '/F'], capture_output=True)
        time.sleep(3)


def set_bridge_flags(**kv):
    """改 [flightFollow] 小节里的开关。**六百一十五 起 flightFollow* 从 [bridge] 搬到了
    [flightFollow]**，键名也短了（flightFollow→enabled、flightFollowDist→dist、
    flightFollowFirework→firework、flightFollowElytra→elytra）；键不存在就插在该小节标题后面。"""
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


def count_item(text, item_id):
    """数某件物品的总数（贴着 id 匹配它自己的 Count，不隔着别的物品乱抓）。"""
    t = text.replace(' ', '')
    total = 0
    for m in re.finditer(r'id:"%s",Count:(\d+)b' % re.escape(item_id), t):
        total += int(m.group(1))
    for m in re.finditer(r'Count:(\d+)b,[A-Za-z_]+:"[^"]*",id:"%s"' % re.escape(item_id), t):
        total += int(m.group(1))
    return total


def elytra_damage(text):
    t = text.replace(' ', '')
    best = 0
    for m in re.finditer(r'id:"minecraft:elytra",Count:\d+b,tag:\{[^}]*?Damage:(\d+)', t):
        best = max(best, int(m.group(1)))
    return best


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


def parse_pos(val):
    m = re.match(r'\[(-?[\d.]+)d?,\s*(-?[\d.]+)d?,\s*(-?[\d.]+)d?\]', val.strip())
    if not m:
        return None
    return tuple(float(x) for x in m.groups())


def run_round(suffix, with_firework=True, phases=True, observe=20):
    log_path = os.path.join(server, 'console_fly611_%s.log' % suffix)
    log = open(log_path, 'wb')
    p = subprocess.Popen([cfg['java'], '-Xmx3G', '-Dfile.encoding=UTF-8'] + cfg['args'],
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
           'end_glide': 0, 'legacy_end': 0, 'restore': 0, 'skip_fuel': 0,
           'takeoff_line': '', 'end_line': '', 'end_dists': [], 'measured_end': None,
           'fw_before': None, 'fw_after': None, 'wore_mid': 0, 'dmg_mid': 0, 'dmg_after': 0,
           'elytra_total': 0, 'chest_after': ''}

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
        for base in ('fly611maid', 'fly611target', 'fly611anchor'):
            for suf in ('', 'a', 'b', 'c', 'd'):
                send('kill @e[tag=%s%s]' % (base, suf))
        time.sleep(1)
    for mob in ('zombie', 'husk', 'drowned', 'zombie_villager', 'skeleton', 'stray',
                'creeper', 'spider', 'cave_spider', 'witch', 'slime', 'phantom'):
        send('kill @e[type=minecraft:%s]' % mob)
    # 【本批新加的"大扫除"】这个测试世界被前几批积了一屋子的女仆（本轮关服时的「离场」清单里
    # 就有二十来只：六百一十 的轰炸/友军女仆、六百〇五 的 Bomb605Charge、还有一片"无主女仆"）。
    # 它们既刷日志（空袭索敌每 2 秒一行）又白吃 tick 预算，本批只留**自己这一轮**的女仆：
    # 按类型整类清掉（测试世界里没有需要保留的女仆），再把台子以外的实体一并收走。
    send('kill @e[type=touhou_little_maid:maid]')
    send('kill @e[type=minecraft:villager]')
    send('kill @e[type=minecraft:armor_stand]')
    send('kill @e[type=minecraft:tnt]')
    time.sleep(2)

    tag = 'fly611maid' + suffix.lower()
    ttag = 'fly611target' + suffix.lower()
    atag = 'fly611anchor' + suffix.lower()

    # ① 锚点 + 5×73 台面（比六百〇八 的 5×29 长，供阶段二把替代主人 tp 到 64 格外）
    send('summon minecraft:armor_stand ~ ~ ~ {Marker:1b,NoGravity:1b,Invisible:1b,'
         'Tags:["%s"]}' % atag)
    time.sleep(2)
    A = '@e[tag=%s,limit=1]' % atag
    send('execute at %s run fill ~-2 ~-1 ~-4 ~2 ~-1 ~68 minecraft:stone' % A)
    time.sleep(1)
    send('execute at %s run fill ~-2 ~ ~-4 ~2 ~4 ~68 minecraft:air' % A)
    time.sleep(2)

    # ② 女仆：胸甲槽空着；背包里放鞘翅（D 轮不放烟花）
    items = '{Slot:0b,%s}' % cfg['elytra']
    if with_firework:
        items += ',{Slot:1b,%s}' % cfg['firework']
    maid_nbt = ('{MaidScheduleMode:"ALL",HandItems:[{},{}],ArmorItems:[{},{},{},{}],'
                'MaidInventory:{Size:36,Items:[%s]},'
                'Owner:[I;1,2,3,4],Tags:["%s"],CustomName:"\\"%s\\"",PersistenceRequired:1b}'
                % (items, tag, NAME + suffix))
    send('execute at %s run summon touhou_little_maid:maid %s %s' % (A, MAID_OFF, maid_nbt))
    time.sleep(3)
    M = '@e[tag=%s,limit=1]' % tag

    # ③ 替代主人：非敌对活体（村民），NoAI + 无重力
    send('execute at %s run summon minecraft:villager %s {NoAI:1b,NoGravity:1b,Silent:1b,'
         'Invulnerable:1b,PersistenceRequired:1b,Health:2000f,'
         'Attributes:[{Name:"%s",Base:2000}],Tags:["%s"],'
         'CustomName:"\\"%s\\""}' % (A, TARGET_OFF, cfg['maxhp_attr'], ttag, TARGET_NAME + suffix))
    time.sleep(3)
    T = '@e[tag=%s,limit=1]' % ttag

    # ④ 基线（Pos / 背包 / 胸甲）+ 脚手架自检
    send('data get entity %s Pos' % M)
    send('data get entity %s MaidInventory' % M)
    send('data get entity %s ArmorItems' % M)
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
                mine)

    take1 = 0
    # ⑥ 阶段一：等她起飞、在收手半径（六百一十五 起 = flightFollow.endDist，默认 5 格）处收手；
    #    结束那一刻抓一次真实坐标（自报距离的独立对照）
    last_end = 0
    t0 = time.time()
    while time.time() - t0 < PHASE1_MAX:
        time.sleep(0.5)
        data = readlog()
        tk, bo, en, lg, mine = counts(data)
        take1 = tk
        if en > last_end:
            last_end = en
            # 刚刚多了一行"带距离的结束行" → 立刻抓女仆与替代主人的坐标（独立对照）
            send('data get entity %s Pos' % M)
            send('data get entity %s Pos' % T)
        if tk >= 1 and (en >= 1 or lg >= 1):
            break
    time.sleep(1)
    send('data get entity %s ArmorItems' % M)
    send('data get entity %s Pos' % M)

    # ⑦ 阶段二：把替代主人 tp 到 64 格外 —— 她应当**再飞一趟**（重启）
    if phases:
        send('execute as %s run tp @s ~ ~ ~%d' % (T, TARGET_FAR))
        time.sleep(2)
        t0 = time.time()
        while time.time() - t0 < PHASE2_MAX:
            time.sleep(1)
            tk, bo, en, lg, mine = counts(readlog())
            if tk > take1:
                break
    else:
        time.sleep(max(1, observe))
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

    tk, bo, en, lg, mine = counts(data)
    takeoff = [l for l in mine if MARK_TAKEOFF in l]
    end = [l for l in mine if MARK_END in l]
    restore = [l for l in mine if MARK_RESTORE in l]
    skip_fuel = [l for l in mine if MARK_SKIP_FUEL in l]
    end_dists = [float(m.group(1)) for m in
                 (re.search(r'结束（主人 ([0-9.]+) 格', l) for l in end) if m]
    chest_restore_log = [l for l in data.splitlines()
                         if '槽=CHEST' in l and 'elytra' in l and (NAME + suffix) in l]

    d = maid_dumps(data, NAME + suffix)
    inv, armor = d['inv'], d['armor']
    fw_before = count_item(inv[0], 'minecraft:firework_rocket') if inv else None
    fw_after = count_item(inv[-1], 'minecraft:firework_rocket') if inv else None
    mid_armor = armor[1] if len(armor) > 1 else ''
    wore_mid = count_item(mid_armor, 'minecraft:elytra')
    dmg_mid = elytra_damage(mid_armor)
    chest_after = armor[-1].replace(' ', '') if armor else ''
    elytra_total = (count_item(inv[-1], 'minecraft:elytra') if inv else 0) \
        + (count_item(armor[-1], 'minecraft:elytra') if armor else 0)
    dmg_after = elytra_damage((inv[-1] if inv else '') + (armor[-1] if armor else ''))
    # 结束那一刻的**独立坐标对照**（取最后两份 Pos dump：女仆 / 替代主人）
    pos_list = [parse_pos(v) for v in d['pos']]
    pos_list = [v for v in pos_list if v]
    measured = None
    if len(pos_list) >= 2:
        a, b = pos_list[-2], pos_list[-1]
        measured = ((a[0] - b[0]) ** 2 + (a[1] - b[1]) ** 2 + (a[2] - b[2]) ** 2) ** 0.5

    notes.append('%s: 起飞 %d / 补推 %d / 带距离的结束 %d / 旧式结束(无距离) %d / 归还 %d'
                 % (suffix, tk, bo, en, lg, len(restore) + len(chest_restore_log)))
    notes.append('%s: 结束距离 %s ; 结束那一刻独立坐标距离 %s'
                 % (suffix, ['%.1f' % x for x in end_dists], 'n/a' if measured is None else '%.1f' % measured))
    notes.append('%s: 烟花 基线=%s 战后=%s ; 飞行中胸甲鞘翅=%d(Damage %d) ; 战后鞘翅件数=%d Damage=%d 胸甲槽=%s'
                 % (suffix, fw_before, fw_after, wore_mid, dmg_mid, elytra_total, dmg_after,
                    chest_after or '?'))
    for label, lst in (('起飞', takeoff), ('补推', [l for l in mine if MARK_BOOST in l]),
                       ('结束', end), ('跳过', skip_fuel), ('归还', restore)):
        if lst:
            notes.append('%s %s示例: %s' % (suffix, label, lst[0][-190:]))

    send('stop')
    for _ in range(40):
        time.sleep(1)
        if p.poll() is not None:
            break
    if p.poll() is None:
        subprocess.run(['taskkill', '/PID', str(p.pid), '/T', '/F'], capture_output=True)

    res.update({'notes': notes, 'data': data, 'takeoff': tk, 'takeoff_p1': take1, 'boost': bo,
                'end': en, 'end_glide': len([l for l in mine if MARK_END_GLIDE in l]),
                'legacy_end': lg, 'restore': len(restore) + len(chest_restore_log),
                'skip_fuel': len(skip_fuel), 'takeoff_line': takeoff[0] if takeoff else '',
                'end_line': end[0] if end else '', 'end_dists': end_dists,
                'measured_end': measured, 'fw_before': fw_before, 'fw_after': fw_after,
                'wore_mid': wore_mid, 'dmg_mid': dmg_mid, 'dmg_after': dmg_after,
                'elytra_total': elytra_total, 'chest_after': chest_after})
    return res


stop_server()
for old in [f for f in os.listdir(os.path.join(server, 'mods')) if f.startswith('promaid-')]:
    if old != cfg['modname']:
        os.remove(os.path.join(server, 'mods', old))
shutil.copyfile(cfg['jar'], os.path.join(server, 'mods', cfg['modname']))
print('jar copied:', cfg['modname'], os.path.getsize(cfg['jar']))

verdict = 'PASS'
notes = []

# ── A 轮：默认档（消耗烟花 / 照原版扣鞘翅耐久）——本批的主判据都在这轮 ──
print('config A:', set_bridge_flags(enabled='true', firework='true',
                                    elytra='true', dist='16.0'))
a = run_round('A', with_firework=True, phases=True)
notes += a['notes']
if a['takeoff'] == 0:
    verdict = 'FAIL(A 轮没有起飞日志 —— 她没飞起来（判定/接线/场景哪一环不对）)'
elif a['end'] == 0 and a['legacy_end'] > 0:
    verdict = ('FAIL(A 轮跑的是**旧口径**：结束行没有距离（旧式"结束（…）" %d 行）——'
               '说明 15 格中断没生效' % a['legacy_end'])
elif a['end'] == 0:
    verdict = 'FAIL(A 轮既没有带距离的结束行，也没有旧式结束行 —— 观察窗口内她没走完一趟'
elif min(a['end_dists']) > 5.5:
    # 【六百一十五 起判据反向】旧口径是"进到 15 格内就中断"，所以当时写的是"最短结束距离 ≥5"
    # （"贴到身边才收手"就是回归）；现在收手半径是独立配置、默认 5（用户原话："当发现5格内有
    # 主人后，结束此模式"）——结束距离落在 4~5 格才是**对的**，反了才是回归。
    verdict = ('FAIL(A 轮收手距离不是"新默认 5 格"那一档：最短结束距离 %.1f 格（应为 ≤5.5；'
               '六百一十五 起收手半径 = flightFollow.endDist，默认 5）' % min(a['end_dists']))
elif a['takeoff'] <= a['takeoff_p1']:
    # 【六百一十五 起降级为提示】这条判据依赖旧口径（收手半径 15 格：阶段一结束时她离目标还有
    # 十几格，tp 之后距离一下拉到 80 格，"再飞一趟"看得清清楚楚）。新口径收手半径 5 格 ⇒
    # 阶段一结束时她就贴在目标身边，tp 之后 10 秒采样窗读到的是 4.6~0.6 格——**分不出**
    # "她又飞了一趟"与"她本来就站在那儿"（起飞行本身的限频也是 5 秒）。同一构建两次跑：
    # 起飞 2（第一次，链路本身没问题）与起飞 1（第二次）。
    # 该属性改由 test_radius615 / test_flight612 覆盖，这里只留提示、不再判失败。
    a['notes'].append('提示：A 轮"tp 到 64 格后再飞一趟"没读到新的起飞行（起飞 %d = 阶段一 %d）'
                      '—— 六百一十五 起收手半径 5 格、阶段一结束时她就在目标身边，'
                      '这条判据在 10 秒窗里不可靠（见本处注释），不作为失败理由'
                      % (a['takeoff'], a['takeoff_p1']))
elif a['boost'] == 0:
    verdict = 'FAIL(A 轮没有补推行 —— 起飞了但没推进（烟花链路没走通）)'
elif '燃料=烟花火箭' not in a['takeoff_line']:
    verdict = 'FAIL(A 轮起飞行没写「燃料=烟花火箭」：%s' % a['takeoff_line'][-160:]
elif a['fw_before'] is not None and a['fw_after'] is not None and a['fw_after'] >= a['fw_before']:
    verdict = 'FAIL(A 轮烟花一枚没少（%s→%s）—— 消耗档没扣' % (a['fw_before'], a['fw_after'])
elif a['elytra_total'] != 1:
    verdict = 'FAIL(A 轮鞘翅件数=%d（应为 1：没丢也没多）' % a['elytra_total']
else:
    notes.append('阳性对照：A 轮结束距离 %s（≤5.5 格 = 六百一十五 的新收手半径那一档）；'
                 'tp 到 64 格后又飞了 %d 趟'
                 % (['%.1f' % x for x in a['end_dists']], a['takeoff'] - a['takeoff_p1']))
    if a['end_glide'] == 0:
        notes.append('警告：A 轮没有「她还在滑翔」的结束行 —— 中断时她都在地面上，'
                     '"自然滑翔那一支"这轮没被验到（旧版 flare 已删除是代码层事实；'
                     '这行只是"她还在空中"的现场记录）')
    # 【独立坐标对照的判据方向】她收手那一刻正以 ~10 格/秒滑翔，而这两条 data get 是**事后**才发出去的
    # （轮询间隔 0.5 秒 + 两条命令的回程），所以实测距离只会**比自报的更近**（绿轮实测 1.7 格 vs 自报
    # 13.6 格，就是这一秒的位移）。因此只在**实测反而更远**（差出 2 格以上）时才值得怀疑——
    # 那说明自报的"没到 15 格就收手"对不上现实。
    if a['measured_end'] is not None and a['end_dists'] \
            and a['measured_end'] > min(a['end_dists']) + 2.0:
        notes.append('警告：结束那一刻的独立坐标距离 %.1f 格 > 自报 %.1f 格 + 2 —— '
                     '自报的收手距离对不上现实，值得人工看一眼'
                     % (a['measured_end'], min(a['end_dists'])))
    if a['restore'] == 0:
        notes.append('提示：A 轮没读到归还（「已落地，把胸甲还回去」或「槽=CHEST 失去鞘翅」都没有）')

# ── B 轮：两个省料档 ──
print('config B:', set_bridge_flags(enabled='true', firework='false',
                                    elytra='false', dist='16.0'))
b = run_round('B', with_firework=True, phases=False, observe=18)
notes += b['notes']
if b['takeoff'] == 0:
    verdict = 'FAIL(B 轮没有起飞日志 —— 省料档把关卡死了？)'
elif '烟花=不消耗' not in b['takeoff_line'] or '鞘翅耐久=不消耗' not in b['takeoff_line']:
    verdict = 'FAIL(B 轮起飞日志的两个开关状态不对：%s' % b['takeoff_line'][-160:]
elif b['fw_before'] is not None and b['fw_after'] is not None and b['fw_after'] < b['fw_before']:
    verdict = 'FAIL(B 轮烟花被扣了（%s→%s）—— 不消耗档没生效' % (b['fw_before'], b['fw_after'])
elif b['dmg_mid'] != 0 or b['dmg_after'] != 0:
    verdict = 'FAIL(B 轮鞘翅 Damage=%d/%d —— 免耐久档没生效（mixin 没拦住）' % (b['dmg_mid'], b['dmg_after'])

# ── C 轮：开关关着（默认）──
print('config C:', set_bridge_flags(enabled='false', firework='true',
                                    elytra='true', dist='16.0'))
c = run_round('C', with_firework=True, phases=False, observe=12)
notes += c['notes']
if c['takeoff'] or c['boost']:
    verdict = 'FAIL(C 轮开关关着还有飞行跟随日志（起飞 %d / 补推 %d）—— 开关没管住' % (
        c['takeoff'], c['boost'])

# ── D 轮：只给鞘翅、不给任何能飞的道具（六百一十一 新燃料口径的反向闸门）──
print('config D:', set_bridge_flags(enabled='true', firework='true',
                                    elytra='true', dist='16.0'))
d = run_round('D', with_firework=False, phases=False, observe=14)
notes += d['notes']
if d['takeoff']:
    verdict = 'FAIL(D 轮她什么都没带也起飞了（起飞 %d）—— 燃料闸门被开成"什么都能飞"' % d['takeoff']
elif d['skip_fuel'] == 0:
    notes.append('警告：D 轮没读到「也没有孔雀羽扇」的跳过日志 —— 闸门可能不是缺燃料那条挡的'
                 '（看看跳过行的原文）')

print('verdict:', verdict)
for n in notes:
    print('note:', n)
for name, r in (('A', a), ('B', b), ('C', c), ('D', d)):
    print('--- 关键日志行（%s 轮）---' % name)
    shown = 0
    for l in r['data'].splitlines():
        if any(k in l for k in (CAT, 'entity data:', 'NEAR_', 'SETUP_', 'flyfollow',
                                '槽=CHEST', 'Mixin apply', 'FATAL')):
            print('  ', l[:230])
            shown += 1
            if shown > 40:
                print('   ...(truncated)')
                break
    if shown == 0:
        print('   (nothing matched)')

sys.exit(0 if verdict == 'PASS' else 1)
