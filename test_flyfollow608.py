# -*- coding: utf-8 -*-
"""实测六百〇八 的场景验证：飞行跟随（主人/替代目标飞远 → 她背鞘翅追过来）。

用法:
    python test_flyfollow608.py 1201

需求原文（粉丝留言 + 作者回复）：
    粉丝："女仆跟随能不能给她整个使用鞘翅一起飞呢，自己飞了后女仆只能搭着方块干着急了"
    作者："这种观赏类型的东西我可以做一下，但是默认关闭，需要在手册内开启。开启开关之后，
    女仆在判定使用搭路时，发现主人离自己太远且自己跟主人之间没有方块阻拦，自己包里面还鞘翅
    和烟花的时候，target=主人，执行飞行……可以调整这种飞行跟随的时候是否消耗烟花和鞘翅耐久。"

【为什么用 /maid_smart flyfollow】这条链的 target 是**在线主人实体**（getOwner() 走
PlayerList），专用服务器上没有玩家 → 恒为 null，结构上无法端到端触发（与 /maid_smart
feedtest 同一类困境）。所以照那条的先例，用命令给最近的女仆挂一个"替代主人"（本例是一尊
NoAI 木桩僵尸），走的仍是同一套判定与飞行链路。

场景（每轮都在世界出生点铺一条走廊，避免地形挡住视线）：
    · 锚点 = 一尊 Marker 盔甲架（不动、不落、不可见），全部坐标以它为参照系；
    · 石头台 5x29 铺在锚点那一层（台面 = 锚点下方 1 格），台面上方 5 格清成空气 →
      "女仆↔目标之间没有方块阻拦"这条判据必然成立；
    · 女仆站在锚点上（出生点），**胸甲槽空着、背包里放鞘翅 + 64 枚烟花**——
      这样还能顺带验"起飞前替她穿上、收手后原样还回去"这条归还链路；
    · 目标（NoAI 无重力村民，**非敌对**——她自己的"附近有怪就不飞"判据会把敌对目标当威胁，
      拿僵尸当替代主人只会验出"起飞 1 tick 就收手"的假象）站在台子另一端（22 格外 > 默认 16 格）。
    · `/maid_smart flyfollow <目标> <女仆>` 点名指定，不靠"最近的"。

三轮（改服务端 config/promaid-common.toml 的 [flightFollow] 小节后重启）：
  A 轮 enabled=true / Firework=true / Elytra=true（全默认档）
    ① 起飞日志「飞行跟随 … 主人飞远了（N 格），背上鞘翅追过去（烟花=消耗，鞘翅耐久=照原版扣）」
    ② 补烟花日志 ≥1 行「补一枚烟花追主人（烟花=消耗）」
    ③ 结束日志 1 行；且她已经贴到目标（ARRIVED/NEAR）
    ④ **飞行中探针**：胸甲槽里真有鞘翅（证明"替她穿上"这条链路走通了）
    ⑤ 背包烟花 **真的少了几枚**（消耗档确实扣）
    ⑥ 鞘翅 **没丢**（背包 + 胸甲一共 1 件）、落地日志「已落地，把胸甲还回去」、
       战后胸甲槽重新变空（归还链路收尾）
  B 轮 enabled=true / Firework=false / Elytra=false（两个省料档）
    ⑦ 起飞日志写的是「烟花=不消耗，鞘翅耐久=不消耗」
    ⑧ 背包烟花 **一枚不少**
    ⑨ 鞘翅 Damage == 0（A 轮同一趟飞行会扣，这轮不扣 = 免耐久真生效）
  C 轮 enabled=false（默认关）
    ⑩ 日志里「飞行跟随」 **0 行**（开关管得住）

【踩过的坑（写在这里免得下次再踩）】`data get entity … <字段>` 的回显**不带字段名**
（只有值），所以所有判据都按"女仆名字 + entity data:"筛行、再按值的形状分类
（`[数字,数字,数字]` = Pos / 含 `Items:[` = 背包 / 其余中括号 = ArmorItems）。
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
MID_PROBE = 1    # 起飞后多少秒探一次"她穿上了吗"（**必须很小**：加了"近了不再补推"之后
                 # 一趟只剩 3 秒左右，探针晚了就只能看到"已经落地还回去了"——实测踩过）
ARRIVE_WAIT = 6  # 再等多久探一次"她贴到目标了吗"
OBSERVE = 50     # 总观察窗口（秒）
FAIL_PATTERNS = ('Mixin apply for mod promaid failed', 'InvalidInjectionException',
                 'MixinTransformerError', 'MixinApplyError', 'Failed to create brain',
                 'OutOfMemoryError')
FATAL_HINTS = ('promaid', 'mixin')

NAME = 'Fly608Maid'
TARGET_NAME = 'Fly608Target'
MARK_TAKEOFF = '背上鞘翅追过去'
MARK_BOOST = '补一枚烟花追主人'
MARK_END = '结束（'
MARK_RESTORE = '已落地，把胸甲还回去'
CAT = '飞行跟随'

which = sys.argv[1] if len(sys.argv) > 1 else '1201'
cfg = TARGETS.get(which)
if not cfg:
    print('usage: python test_flyfollow608.py [1201]')
    sys.exit(2)

server = cfg['dir']
pid_file = os.path.join(server, 'server.pid')
CONFIG = os.path.join(server, 'config', 'promaid-common.toml')

MAID_OFF = '~ ~ ~'        # 女仆站位（就站在锚点上）
TARGET_OFF = '~ ~ ~22'     # 目标站位（22 格外 > 默认 16 格触发距离）


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


def count_item(text, item_id):
    """数某件物品的总数。
    【两个坑都踩过】① dump 里是 `id: "x"` / `Count: 1b`（**有空格**）——不先去掉空格一条都匹配
    不到；② 不能"在 id 附近开个窗口找第一个 Count"——旁边那件物品的 Count 会先被捡到（本批实测
    把 64 枚烟花读成了 1）。所以必须**贴着 id 匹配它自己的 Count**。
    """
    t = text.replace(' ', '')
    total = 0
    for m in re.finditer(r'id:"%s",Count:(\d+)b' % re.escape(item_id), t):
        total += int(m.group(1))
    for m in re.finditer(r'Count:(\d+)b,[A-Za-z_]+:"[^"]*",id:"%s"' % re.escape(item_id), t):
        total += int(m.group(1))
    return total


def elytra_damage(text):
    """鞘翅自己 tag 里的 Damage（没写就是 0）——同样贴着 id 匹配，不隔着别的物品乱抓。"""
    t = text.replace(' ', '')
    best = 0
    for m in re.finditer(r'id:"minecraft:elytra",Count:\d+b,tag:\{[^}]*?Damage:(\d+)', t):
        best = max(best, int(m.group(1)))
    return best


def maid_dumps(data, name):
    """按"女仆名字 + entity data:"筛出她自己的三次 dump，再按值的形状分类。"""
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


def run_round(suffix):
    log_path = os.path.join(server, 'console_fly608_%s.log' % suffix)
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

    empty = {'notes': [], 'data': '', 'takeoff': 0, 'boost': 0, 'end': 0, 'restore': 0,
             'fw_before': None, 'fw_after': None, 'dmg_mid': 0, 'dmg_after': 0,
             'wore_mid': 0, 'worn_evidence': False, 'elytra_total': 0,
             'arrived': False, 'near': False, 'grounded': False,
             'takeoff_line': '', 'chest_after': ''}

    done = False
    for _ in range(WAIT):
        time.sleep(1)
        if p.poll() is not None:
            break
        if 'Done (' in readlog():
            done = True
            break
    if not done:
        notes = ['%s: FAIL(server never reached Done)' % suffix]
        send('stop')
        for _ in range(40):
            time.sleep(1)
            if p.poll() is not None:
                break
        if p.poll() is None:
            subprocess.run(['taskkill', '/PID', str(p.pid), '/T', '/F'], capture_output=True)
        empty['notes'] = notes
        return empty

    send('gamerule doMobSpawning false')
    send('difficulty easy')
    # 【清场要把**带回合后缀**的 tag 也一起点名】本批实测踩过两次：① 上一轮被中断时留下的
    # 同名女仆没被杀掉，选择器 `@e[tag=fly608maida]` 命中的是她（量出来"距离 3.6 格"、
    # 她压根没飞）；② 前几批测试的血包僵尸就站在出生点，落在"威胁半径 8 格"里 →
    # 判据直接是「附近有敌对生物」，她永远不起飞。所以：tag 清两遍（前缀 + 回合后缀），
    # 敌对生物单独扫一遍。
    for _ in range(2):
        for base in ('fly608maid', 'fly608target', 'fly608anchor',
                     'diag608maid', 'diag608target', 'diag608anchor'):
            for suf in ('', 'a', 'b', 'c'):
                send('kill @e[tag=%s%s]' % (base, suf))
        time.sleep(1)
    for mob in ('zombie', 'husk', 'drowned', 'zombie_villager', 'skeleton', 'stray',
                'creeper', 'spider', 'cave_spider', 'witch', 'slime', 'phantom'):
        send('kill @e[type=minecraft:%s]' % mob)
    time.sleep(2)

    tag = 'fly608maid' + suffix.lower()
    ttag = 'fly608target' + suffix.lower()
    atag = 'fly608anchor' + suffix.lower()

    # ① 锚点
    send('summon minecraft:armor_stand ~ ~ ~ {Marker:1b,NoGravity:1b,Invisible:1b,'
         'Tags:["%s"]}' % atag)
    time.sleep(2)
    A = '@e[tag=%s,limit=1]' % atag
    # ② 台面（锚点下方 1 格 = 地表那一层）5x29 + 上方 5 格清成空气
    send('execute at %s run fill ~-2 ~-1 ~-4 ~2 ~-1 ~24 minecraft:stone' % A)
    time.sleep(1)
    send('execute at %s run fill ~-2 ~ ~-4 ~2 ~4 ~24 minecraft:air' % A)
    time.sleep(2)
    # ③ 女仆：胸甲槽**空着**、背包放鞘翅 + 64 烟花（默认任务 = 空闲，不占用搭路判定）
    maid_nbt = ('{MaidScheduleMode:"ALL",HandItems:[{},{}],ArmorItems:[{},{},{},{}],'
                'MaidInventory:{Size:36,Items:[{Slot:0b,%s},{Slot:1b,%s}]},'
                'Owner:[I;1,2,3,4],Tags:["%s"],CustomName:"\\"%s\\"",PersistenceRequired:1b}'
                % (cfg['elytra'], cfg['firework'], tag, NAME + suffix))
    send('execute at %s run summon touhou_little_maid:maid %s %s' % (A, MAID_OFF, maid_nbt))
    time.sleep(3)
    M = '@e[tag=%s,limit=1]' % tag
    # ④ 目标：台子另一端（22 格外）。
    # 【为什么是村民而不是僵尸】这条链的"附近有敌对生物就不飞"是**她自己的**判据：拿僵尸当
    # 替代主人，她飞到威胁半径（默认 8 格）内就会被自己叫停——起飞 1 tick 就收手（本批实测
    # 就踩了这个：日志里只有「起飞/补烟花/结束」三行、没有一次贴到目标）。真实场景里目标是
    # 主人（玩家，不是 Enemy），所以这里必须换成**非敌对活体**（村民），否则验的是假象。
    send('execute at %s run summon minecraft:villager %s {NoAI:1b,NoGravity:1b,Silent:1b,'
         'Invulnerable:1b,PersistenceRequired:1b,Health:2000f,'
         'Attributes:[{Name:"%s",Base:2000}],Tags:["%s"],'
         'CustomName:"\\"%s\\""}' % (A, TARGET_OFF, cfg['maxhp_attr'], ttag, TARGET_NAME + suffix))
    time.sleep(3)

    # ⑤ 脚手架自检 + 基线（Pos / 背包 / 胸甲）
    send('data get entity %s Pos' % M)
    send('data get entity %s MaidInventory' % M)
    send('data get entity %s ArmorItems' % M)
    send('execute at %s run execute if entity @e[tag=%s,limit=1] run say SETUP_%s' % (A, ttag, suffix))
    time.sleep(2)

    # ⑥ 挂"替代主人"（专服没有在线玩家，getOwner() 恒为 null）。
    # 【第三个参数点名指定女仆】不给的话命令取"离出生点最近的一只"——而测试世界里堆着前几批
    # 留下的女仆（本批实测就撞上了：目标挂到了别人身上，现象是"她怎么不飞"）。
    send('maid_smart flyfollow @e[tag=%s,limit=1] @e[tag=%s,limit=1]' % (ttag, tag))
    time.sleep(MID_PROBE)
    # ⑦ 飞行中探针：胸甲槽里是不是真有鞘翅（"替她穿上"这条链路）
    send('data get entity %s ArmorItems' % M)
    send('data get entity %s Pos' % M)
    time.sleep(ARRIVE_WAIT)
    # ⑦b 贴到目标了吗（此时一趟飞行应当已经结束）
    send('execute at %s run execute if entity @e[tag=%s,limit=1,distance=..12] run say ARRIVED_%s'
         % (M, ttag, suffix))
    time.sleep(max(1, OBSERVE - MID_PROBE - ARRIVE_WAIT))

    # ⑧ 战后取证
    send('execute as %s run execute if entity @s[nbt={OnGround:1b}] run say GROUNDED_%s' % (M, suffix))
    send('execute at %s run execute if entity @e[tag=%s,limit=1,distance=..12] run say NEAR_%s'
         % (M, ttag, suffix))
    send('data get entity %s MaidInventory' % M)
    send('data get entity %s ArmorItems' % M)
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

    mine = [l for l in data.splitlines() if CAT in l and (NAME + suffix) in l]
    takeoff = [l for l in mine if MARK_TAKEOFF in l]
    boost = [l for l in mine if MARK_BOOST in l]
    end = [l for l in mine if MARK_END in l]
    restore = [l for l in mine if MARK_RESTORE in l]
    arrived = ('ARRIVED_%s' % suffix) in data
    near = ('NEAR_%s' % suffix) in data
    grounded = ('GROUNDED_%s' % suffix) in data
    # 【归还证据有两条路】① 收手时她还在空中 → 有一行「已落地，把胸甲还回去」；
    # ② 收手时她已经落地 → 归还就写在 stop() 里，日志只有「结束（已落地）」+
    #    装备追踪那行「槽=CHEST 失去 1xelytra | 她背包[0:1xelytra …]」。两条都算数。
    chest_restore_log = [l for l in data.splitlines()
                         if '槽=CHEST' in l and 'elytra' in l and (NAME + suffix) in l]

    d = maid_dumps(data, NAME + suffix)
    inv = d['inv']
    armor = d['armor']
    fw_before = count_item(inv[0], 'minecraft:firework_rocket') if inv else None
    fw_after = count_item(inv[-1], 'minecraft:firework_rocket') if inv else None
    # 【三份胸甲 dump 的顺序】基线 / 飞行中（+1 秒那一次）/ 战后 → 中间那份才是"她穿着的时候"
    mid_armor = armor[1] if len(armor) > 1 else ''
    wore_mid = count_item(mid_armor, 'minecraft:elytra')
    dmg_mid = elytra_damage(mid_armor)
    chest_after = armor[-1].replace(' ', '') if armor else ''
    elytra_total = (count_item(inv[-1], 'minecraft:elytra') if inv else 0) \
        + (count_item(armor[-1], 'minecraft:elytra') if armor else 0)
    dmg_after = elytra_damage((inv[-1] if inv else '') + (armor[-1] if armor else ''))
    # 穿上过的证据：飞行中探针看到鞘翅，或者装备追踪记下了"胸甲槽失去鞘翅"（后者更可靠——
    # 加了"近了不再补推"之后一趟只剩 3 秒，探针稍晚就只剩后者）
    worn_evidence = wore_mid > 0 or bool(chest_restore_log)

    notes.append('%s: 起飞 %d / 补烟花 %d / 结束 %d / 归还日志 %d | ARRIVED=%s NEAR=%s GROUNDED=%s'
                 % (suffix, len(takeoff), len(boost), len(end), len(restore) + len(chest_restore_log),
                    arrived, near, grounded))
    notes.append('%s: 烟花 基线=%s 战后=%s ; 飞行中胸甲鞘翅=%d(Damage %d) ; 战后鞘翅件数=%d Damage=%d 胸甲槽=%s'
                 % (suffix, fw_before, fw_after, wore_mid, dmg_mid, elytra_total, dmg_after,
                    chest_after or '?'))
    notes.append('%s: dump 计数 Pos=%d 背包=%d 胸甲=%d ; 胸甲槽失去鞘翅的装备日志 %d 行'
                 % (suffix, len(d['pos']), len(inv), len(armor), len(chest_restore_log)))
    for label, lst in (('起飞', takeoff), ('补烟花', boost), ('结束', end), ('归还', restore),
                       ('装备归还', chest_restore_log)):
        if lst:
            notes.append('%s %s示例: %s' % (suffix, label, lst[0][-190:]))

    send('stop')
    for _ in range(40):
        time.sleep(1)
        if p.poll() is not None:
            break
    if p.poll() is None:
        subprocess.run(['taskkill', '/PID', str(p.pid), '/T', '/F'], capture_output=True)

    return {'notes': notes, 'data': data, 'takeoff': len(takeoff), 'boost': len(boost),
            'end': len(end), 'restore': len(restore) + len(chest_restore_log),
            'fw_before': fw_before, 'fw_after': fw_after,
            'dmg_mid': dmg_mid, 'dmg_after': dmg_after, 'wore_mid': wore_mid,
            'worn_evidence': worn_evidence, 'elytra_total': elytra_total,
            'arrived': arrived, 'near': near, 'grounded': grounded,
            'takeoff_line': takeoff[0] if takeoff else '', 'chest_after': chest_after}


stop_server()
for old in [f for f in os.listdir(os.path.join(server, 'mods')) if f.startswith('promaid-')]:
    if old != cfg['modname']:
        os.remove(os.path.join(server, 'mods', old))
shutil.copyfile(cfg['jar'], os.path.join(server, 'mods', cfg['modname']))
print('jar copied:', cfg['modname'], os.path.getsize(cfg['jar']))

verdict = 'PASS'
notes = []

# ── A 轮：全默认档（消耗烟花 / 照原版扣鞘翅耐久）──
print('config A:', set_bridge_flags(enabled='true', firework='true',
                                    elytra='true', dist='16.0'))
a = run_round('A')
notes += a['notes']
if a['takeoff'] == 0:
    verdict = 'FAIL(A 轮没有起飞日志 —— 她没飞起来（判定/接线/场景哪一环不对）)'
elif '烟花=消耗' not in a['takeoff_line'] or '鞘翅耐久=照原版扣' not in a['takeoff_line']:
    verdict = 'FAIL(A 轮起飞日志的两个开关状态不对：%s' % a['takeoff_line'][-160:]
elif a['boost'] == 0:
    verdict = 'FAIL(A 轮没有补烟花日志 —— 起飞了但没推进（烟花链路没走通）)'
elif not a['worn_evidence']:
    verdict = 'FAIL(A 轮没有"她穿上鞘翅"的证据（飞行中探针与装备追踪都没有）)'
elif a['fw_before'] is not None and a['fw_after'] is not None and a['fw_after'] >= a['fw_before']:
    verdict = 'FAIL(A 轮烟花一枚没少（%s→%s）—— 消耗档没扣' % (a['fw_before'], a['fw_after'])
elif a['elytra_total'] != 1:
    verdict = 'FAIL(A 轮鞘翅件数=%d（应为 1：没丢也没多）' % a['elytra_total']
elif a['end'] == 0:
    notes.append('警告：A 轮没有"结束"日志 —— 观察窗口内她可能还在滑翔（%ds）' % OBSERVE)
if a['restore'] == 0:
    notes.append('提示：A 轮没读到归还（「已落地，把胸甲还回去」或「槽=CHEST 失去鞘翅」都没有）')
elif a['chest_after'] and a['chest_after'] != '[{},{},{},{}]' and a['grounded']:
    notes.append('提示：A 轮（她已落地）战后胸甲槽是 %s —— 归还没发生？' % a['chest_after'])
elif not a['grounded']:
    notes.append('提示：A 轮收尾时她还在滑翔（观察窗口 %ds 内又起飞了一趟）——'
                 '胸甲仍在身上是正常的，鞘翅没丢就算过' % OBSERVE)
if a['dmg_after'] == 0 and a['dmg_mid'] == 0:
    notes.append('提示：A 轮鞘翅 Damage=0（这一趟滑翔不足 20 tick，没到扣耐久的点）')
if not a['arrived']:
    notes.append('警告：没读到 ARRIVED_A（飞行结束后 12 格内）—— 她可能没贴到目标')

# ── B 轮：两个省料档（不消耗烟花 / 免鞘翅耐久）──
print('config B:', set_bridge_flags(enabled='true', firework='false',
                                    elytra='false', dist='16.0'))
b = run_round('B')
notes += b['notes']
if b['takeoff'] == 0:
    verdict = 'FAIL(B 轮没有起飞日志 —— 省料档把关卡死了？)'
elif '烟花=不消耗' not in b['takeoff_line'] or '鞘翅耐久=不消耗' not in b['takeoff_line']:
    verdict = 'FAIL(B 轮起飞日志的两个开关状态不对：%s' % b['takeoff_line'][-160:]
elif b['fw_before'] is not None and b['fw_after'] is not None and b['fw_after'] < b['fw_before']:
    verdict = 'FAIL(B 轮烟花被扣了（%s→%s）—— 不消耗档没生效' % (b['fw_before'], b['fw_after'])
elif b['dmg_mid'] != 0 or b['dmg_after'] != 0:
    verdict = 'FAIL(B 轮鞘翅 Damage=%d/%d —— 免耐久档没生效（mixin 没拦住）' % (b['dmg_mid'], b['dmg_after'])
elif b['takeoff'] > 0 and b['boost'] == 0:
    notes.append('警告：B 轮起飞了但没补烟花 —— 推进链路异常（不消耗档不该影响推进）')

# ── C 轮：开关关着（默认）──
print('config C:', set_bridge_flags(enabled='false', firework='true',
                                    elytra='true', dist='16.0'))
c = run_round('C')
notes += c['notes']
if c['takeoff'] or c['boost']:
    verdict = 'FAIL(C 轮开关关着还有飞行跟随日志（起飞 %d / 补烟花 %d）—— 开关没管住' % (
        c['takeoff'], c['boost'])

print('verdict:', verdict)
for n in notes:
    print('note:', n)
for name, r in (('A', a), ('B', b), ('C', c)):
    print('--- 关键日志行（%s 轮）---' % name)
    shown = 0
    for l in r['data'].splitlines():
        if any(k in l for k in (CAT, 'entity data:', 'ARRIVED_', 'NEAR_', 'GROUNDED_', 'SETUP_',
                                'flyfollow', '槽=CHEST', 'Mixin apply', 'FATAL')):
            print('  ', l[:230])
            shown += 1
            if shown > 40:
                print('   ...(truncated)')
                break
    if shown == 0:
        print('   (nothing matched)')

sys.exit(0 if verdict == 'PASS' else 1)
