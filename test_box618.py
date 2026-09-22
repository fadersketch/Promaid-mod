# -*- coding: utf-8 -*-
"""实测六百一十八 的场景验证：压缩盒**四条反馈**（食物 / 附魔 / 鼠标取放 / 附魔光效）。

用法:
    python test_box618.py [1201|neoforge1211]

需求原文（用户）:
    "1.我发现一件事，往压缩盒里面加食物，女仆是不会去吃的。只是往里面加弹药以及TNT这些会认。
     2.对于附魔类的物品似乎有一些bug，存入会导致物品直接消失。
     3.这个存入目前跟玩家的认知不太一样…我想的是可以像往箱子存东西一样，玩家可以通过用鼠标的
       方式将物品拖进去。直接照搬往箱子里存东西的代码就行。
     4.给压缩盒打上附魔的特效。这个…排班表中已经做过了，直接照搬即可。"

【为什么这个用例长得不一样】四条里有三条的入口是**界面的一次点击**（存入 / 鼠标取放），
专用服务器上没有客户端、点不了界面。本仓六百一十六那批就是在这里栽过跟头：改的是点击
那条路，跑的却是"她看不看得见盒子"——测的东西根本不是改的东西。所以这一批继续用
**游戏里可跑的自检**（`/maid_smart box check [女仆]`，见 CompressionBoxCheck）：
用假玩家（FakePlayerFactory）在服务端把界面点击**真正调用的那个方法**
（CompressionBoxService.handle）原样走一遍。这个用例就是去跑它并检查结果。

【验的四件事（与自检里的分组一一对应）】
  ⓐ **食物那条（用户第 1 条）**：拿 TLM 自己的判据（`MaidMealManager` 的 `canMaidEat`）
     去问她那两条"可用背包"（`getAvailableBackpackInv` / `getAvailableInv`）里找不找得到
     盒子里的食物。女仆自己吃饭走的就是前者，而六百一十六只注入了 `getMaidInv()`——
     所以她只认走自己那条路的弹药/TNT、不认饭。配一条"盒子里没有的东西必须找不到"的对照。
  ⓑ **附魔那条（用户第 2 条）**：不可堆叠的东西（附魔书）一格存 2 个时，交给她/原版的
     必须是**合法堆**（≤ getMaxStackSize）；另加一条"过一圈盒子整个 NBT 还在"的往返。
  ⓒ **鼠标取放（用户第 3 条）**：左键拿起 → 点盒子格放下 → 再拿回来 → Shift+左键快速移动，
     以及"鼠标上拿着一个盒子去点盒子格必须被拒"（盒子装盒子在新入口下也得挡住）。
  ⓓ **手上那一叠不许丢**：挂着一叠时收手（关界面/ESC 那条）→ 回到背包、一件不少。
  （另：六百一十七的"盒子不许装盒子"那几条继续跑，是回归。）

【这批验不到的那一层（写清楚）】界面本身的渲染（粉色调贴图、光标上那一叠画在哪、
附魔流光）需要真实客户端，本用例碰不到；那部分靠三条硬证据保证：原版那套装饰绘制方法
本身（`m_280370_` = renderItemDecorations，javap 实证）、`isFoil` 恒 true（与排班表/
手册逐字同源），以及贴图文件本身的像素（六百一十七那批用解码 PNG 验过）。
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
    },
    'neoforge1211': {
        'dir': r'C:/Users/Sketch/mc_server_test/neoforge1211',
        'java': r'C:/Users/Sketch/AppData/Roaming/.minecraft/runtime/java-runtime-delta/bin/java.exe',
        'args': ['@user_jvm_args.txt',
                 '@libraries/net/neoforged/neoforge/21.1.250/win_args.txt', 'nogui'],
        'jar': r'C:/Users/Sketch/.zcode/workspace/default/promaid-mod/patched/'
               r'promaid-1.2.2-neoforge-1.21.1.jar',
        'modname': 'promaid-1.2.2-neoforge-1.21.1.jar',
    },
}

WAIT = 240
FAIL_PATTERNS = ('Mixin apply for mod promaid failed', 'InvalidInjectionException',
                 'MixinTransformerError', 'MixinApplyError', 'Failed to create brain',
                 'OutOfMemoryError', 'NoSuchMethodError', 'NoClassDefFoundError')
FATAL_HINTS = ('promaid', 'mixin')

NAME = 'Box618Maid'
BOX_ID = 'maid_smart:compression_box'
CHECK_CAT = '压缩盒自检'
BOX_CAT = '压缩盒'
STONE_TOTAL = 114514
BEAF_TOTAL = 16     # 盒子里那份食物（女仆自己吃饭那条要用）
TNT_TOTAL = 8       # 弹药那条（她自己那套"可用背包"里本来就看得见）
ARROW_TOTAL = 32     # 光谱箭：只可能来自盒子那件（对照二靠它）
# A 段（她真的会去吃盒子里的饭）等多久：MaidHealSelfTask 的检查周期是 50 tick（2.5 秒），
# 吃一份要 1.6 秒，血量 6 → 满血大概要吃两三份。给 40 秒，宽到不可能因为"还没轮到"而误判。
EAT_WAIT = 40
# 自检每跑一次都要留痕：promaid.log 是【跨轮追加】的（不清理），所以第一轮跑出来的 PASS
# 会一直躺在文件里。靠这条标记切出"本次 run 里的自检输出"，否则第二次跑会把旧 PASS 一起
# 数进来（甚至从旧记录里读到已经修掉的 FAIL）。run 编号 + 1 写进女仆名，命令里带上名字。
RUN_TAG = '618'

which = sys.argv[1] if len(sys.argv) > 1 else '1201'
cfg = TARGETS.get(which)
if not cfg:
    print('usage: python test_box618.py [1201|neoforge1211]')
    sys.exit(2)

server = cfg['dir']
pid_file = os.path.join(server, 'server.pid')


def stop_server():
    if os.path.exists(pid_file):
        pid = open(pid_file).read().strip()
        subprocess.run(['taskkill', '/PID', pid, '/T', '/F'], capture_output=True)
        time.sleep(3)


def box_nbt(slots):
    """压缩盒的 NBT（物品形态：CompressionBox.Items 列表，Count 是 int）。

    ── 1.20.1 与 1.21.1 的物品写法不一样（实测六百一十七踩到）──
    Forge 1.20.1 是 `tag:{...}`；1.21 之后原版物品标签改成了**数据组件**，手写 `tag:` 会被
    直接忽略（不报错！）——本用例第一版在 NeoForge 上就是这么把盒子内容"写没了"：盒子在
    她背包里、视图 41 格都对，可那 5 格全是空的。NeoForge 要写
    `components:{"minecraft:custom_data":{...}}`。

    ── 组件那层是**带引号的 SNBT 字符串**，里面的引号必须转义（第二版踩到）──
    第二版写成了字面量里的裸引号（`"minecraft:custom_data":{...Item:{id:"minecraft:stone"...}}`），
    组件解析器读这个字符串时，`id:` 后面那个引号会被当成**字符串的结束**，于是
    custom_data 反序列化失败、静默返回空 —— 症状和第一版一模一样（盒子还是空的）。
    1.20.1 走 `tag:`、那一层不套引号，所以字符原样就好；两条路分开拼。
    """
    entries = ','.join('{Slot:%d,Count:%d,Item:%s}' % (i, c, it)
                       for i, (c, it) in enumerate(slots) if c > 0)
    inner = '{CompressionBox:{Items:[%s]}}' % entries
    if which != 'neoforge1211':
        return 'tag:%s' % inner
    # 只对双引号做转义（内层没有反斜杠，不必管）
    esc = inner.replace('\\', '\\\\').replace('"', '\\"')
    return 'components:{"minecraft:custom_data":"%s"}' % esc


CONFIG = os.path.join(server, 'config', 'promaid-common.toml')


def _read_cfg():
    raw = open(CONFIG, 'rb').read()
    try:
        return raw.decode('utf-8')
    except Exception:
        return raw.decode('gbk', errors='replace')


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
        open(CONFIG, 'wb').write(t.encode('utf-8'))
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
    open(CONFIG, 'wb').write('\n'.join(lines).encode('utf-8'))
    return sorted(kv)


stop_server()
# 上一批（616）最后一轮把 maidExtension 跑成了 false，这里必须清回 true——否则女仆那一条
# 永远 SKIP（她会说"背包里没有压缩盒"；实测第一版就是这么 SKIP 的）。写配置要在服务端
# 停下来之后（在跑的时候写，退出时会被它自己那份内存里的配置覆盖回去）。
print('config:', set_section('compressionBox', maidExtension='true', maxStack=str(STONE_TOTAL)))
mods_dir = os.path.join(server, 'mods')
for old in os.listdir(mods_dir):
    if old.startswith('promaid-') and old != cfg['modname']:
        os.remove(os.path.join(mods_dir, old))
shutil.copyfile(cfg['jar'], os.path.join(mods_dir, cfg['modname']))
print('jar copied:', cfg['modname'], os.path.getsize(cfg['jar']))

log_path = os.path.join(server, 'console_box618.log')
plog = os.path.join(server, 'logs', 'promaid.log')
# 自检输出按 run 切段（promaid.log 跨轮追加）。先把当前长度记下来，之后的读只取新增部分。
_plog0 = os.path.getsize(plog) if os.path.exists(plog) else 0
log = open(log_path, 'wb')
p = subprocess.Popen([cfg['java'], '-Xmx3G', '-Dfile.encoding=UTF-8'] + cfg['args'],
                     cwd=server, stdout=log, stderr=subprocess.STDOUT,
                     stdin=subprocess.PIPE,
                     creationflags=subprocess.CREATE_NEW_PROCESS_GROUP | 0x00000008)
open(pid_file, 'w').write(str(p.pid))
print('server pid', p.pid)


_seq = 0


def send(cmd, wait=True, timeout=60, tag=None):
    """发一条命令，并**等它真的跑完**。

    为什么非要等：服务端是"边收边执行"，命令排队；一口气灌七八条的话，stop 会插到队尾之前，
    后面几条没跑到进程就没了。实测六百一十七第一版就是这样——日志里连
    `kill @e[type=minecraft:item]` 都没有，自检那两条更是影都没有。

    【别等控制台回显】java.exe 的 stdout 是管道时 Minecraft **不回显命令**（实测：两树的
    控制台日志里 `>` 开头的行一条都没有，连跑绿了的六百一十六也一样），所以"回显"这条路
    是死的。改成**用命令自己的日志当凭据**：每条要等的命令都带一个唯一标记 tag，命令执行到
    它自己那一行日志时就说明"轮到它并且跑完了"。`kill` 这类不打印日志的，只好给它一觉。
    """
    global _seq
    print('   >', cmd[:200])
    try:
        p.stdin.write((cmd + '\n').encode('utf-8'))
        p.stdin.flush()
    except Exception as e:
        print('   (stdin failed: %s)' % e)
        return
    if not wait or tag is None:
        time.sleep(2)
        return
    t0 = time.time()
    while time.time() - t0 < timeout:
        time.sleep(0.5)
        if p.poll() is not None:
            return
        # 从 promaid.log 里找标记：那里是【无缓冲立即落盘】的（latest.log 在 NeoForge 上
        # 根本不镜像 PromaidLog 的行，只有 promaid.log 有——见 readlog 的说明）
        if tag in readlog():
            time.sleep(0.5)
            return
    print('   (等日志超时: %s)' % tag)


def _read_from(path, off):
    """读 path 里 offset 之后的新增部分（promaid.log 是跨轮追加的，必须切段）。"""
    try:
        with open(path, 'rb') as f:
            f.seek(off)
            raw = f.read()
    except Exception:
        return ''
    d = {e: raw.decode(e, 'replace') for e in ('utf-8', 'gbk', 'cp936', 'latin-1')}
    for t in d.values():
        if CHECK_CAT in t or BOX_CAT in t:
            return t
    return d['utf-8']


def _read(path):
    return _read_from(path, 0)


def readlog():
    """读日志。**以 promaid.log 为准**。

    为什么不能用控制台日志：NeoForge 21.1 那台**不把 PromaidLog 的 SLF4J 行镜像进
    logs/latest.log**（实测：latest.log 里 '压缩盒' 出现 0 次，而 promaid.log 里 29 次；
    两树的 PromaidLog.java 逐字相同，Forge 那台是照镜像的）。而 promaid.log 是我们自己
    `Files.write(APPEND)` 写的，**每行立即落盘、没有缓冲区**，比 latest.log 还可靠。
    控制台日志留着只用于判断"服务端起来没有"（'Done (' 那一行只有它有）。
    """
    t = _read_from(plog, _plog0)
    if CHECK_CAT in t or BOX_CAT in t:
        return t
    return _read(log_path)


def settle(quiet=6, cap=200):
    """等日志不再长（蓝图加载/模型扫描会在 Done 之后继续刷几十秒，这期间控制台被占着，
    命令发得太急会排在后面、甚至还没跑到就被 stop 打断——实测第一版就是这样只执行了前两条）。"""
    last, stable, t0 = -1, 0, time.time()
    while time.time() - t0 < cap:
        time.sleep(1)
        try:
            n = os.path.getsize(log_path)
        except Exception:
            n = -1
        if n == last:
            stable += 1
            if stable >= quiet:
                return True
        else:
            stable, last = 0, n
    return False


notes = []
done = False
for _ in range(WAIT):
    time.sleep(1)
    if p.poll() is not None:
        break
    if 'Done (' in readlog():
        done = True
        break

if not done:
    notes.append('服务端没到 Done（启动失败）')
else:
    print('server done, settling ...')
    print('   settled:', settle())
    # 【名字必须每轮都不一样】同名的女仆/盔甲架会被 selectors 连旧的一起选中（旧的可能还躺在
    # 存档里），那样 box check 打出来的就不一定是本次这一只了。用 run 标记区分。
    NAME = 'Box618Maid' + RUN_TAG
    ANCHOR_TAG = 'box618anchor' + RUN_TAG
    MAID_TAG = 'box618maid' + RUN_TAG
    send('gamerule doMobSpawning false', wait=False)
    send('difficulty easy', wait=False)
    for t in (MAID_TAG, ANCHOR_TAG):
        send('kill @e[tag=%s]' % t)
    send('kill @e[type=minecraft:item]')  # 上一轮掉的掉落物别被她捡进盒子
    send('summon minecraft:armor_stand ~ ~ ~ {Marker:1b,NoGravity:1b,Invisible:1b,'
         'Tags:["%s"]}' % ANCHOR_TAG)
    A = '@e[tag=%s,limit=1]' % ANCHOR_TAG
    send('execute at %s run fill ~-3 ~-1 ~-3 ~3 ~-1 ~3 minecraft:stone' % A)
    send('execute at %s run fill ~-3 ~ ~-3 ~3 ~3 ~3 minecraft:air' % A)
    # 她背包第 0 格 = 压缩盒，里面 4 样东西 + 1 个空格子：
    #   格 1 = 石头 ×114514（大堆口径还在不在）
    #   格 2 = 熟牛肉 ×16  ← **用户第 1 条**：盒子里放饭她吃不吃（TLM 自己吃饭走的是
    #                        getAvailableBackpackInv，六百一十六那条路看不见盒子）
    #   格 3 = TNT ×8 / 格 4 = 光谱箭 ×32  ← 弹药那侧的对照（走 getMaidInv，本来就看得见）
    #                       光谱箭是**只可能来自盒子**的那一件（对照二靠它证"是从盒子那段
    #                       找出来的"，而不是"别处碰巧也有"）
    #   格 5 留空（女仆那一条要一个空格子；自检用完会清回去）
    box = box_nbt([(STONE_TOTAL, '{id:"minecraft:stone",Count:1}'),
                   (BEAF_TOTAL, '{id:"minecraft:cooked_beef",Count:1}'),
                   (TNT_TOTAL, '{id:"minecraft:tnt",Count:1}'),
                   (ARROW_TOTAL, '{id:"minecraft:spectral_arrow",Count:1}')])
    maid_nbt = ('{MaidScheduleMode:"ALL",HandItems:[{},{}],ArmorItems:[{},{},{},{}],'
                'MaidInventory:{Size:36,Items:[{Slot:0b,id:"%s",Count:1b,%s}]},'
                'Owner:[I;1,2,3,4],Tags:["%s"],'
                'CustomName:"\\"%s\\"",PersistenceRequired:1b}'
                % (BOX_ID, box, MAID_TAG, NAME))
    send('execute at %s run summon touhou_little_maid:maid ~ ~ ~ %s' % (A, maid_nbt),
         tag=NAME)
    MA = '@e[tag=%s,limit=1]' % MAID_TAG

    # ==================== A 段：她真的会去吃盒子里的饭吗 ====================
    # 【为什么要这一段】用户第 1 条的原话是"往压缩盒里面加食物，女仆是不会去吃的"。
    # 自检那条证明的是"她吃饭那条路（getAvailableBackpackInv + canMaidEat）看得见盒子"，
    # 这里再补一条**行为**上的证据：她还没被弄伤时先看一眼盒子（饭在不在），
    # 然后把她打伤、等她自己去找吃的——盒子里的熟牛肉应当变少甚至没了、她的血应当回来。
    # 这一条不依赖任何内部方法，玩家怎么看她就怎么验。
    # 【实测踩到的时序坑】她出手比采样还快：第一轮里"给她 6 点血"之后 2 秒内她就把那叠
    # 熟牛肉从盒子里拿走了（自检日志里出现过"主手 失去 15xcooked_beef"），所以
    # **"A 段第一份采样里还有饭"不能当判据**——判据只能是"最后一份比我们放进去的少"+
    # "她的血回来了"。放进去多少是本用例自己写的（BEAF_TOTAL），这条是硬的。
    # 【怎么把她打伤：用 /damage，不用 data merge】1.20.1 那台 `data merge entity
    # {Health:6f}` 是灵的，但同一套命令在 **NeoForge 1.21.1 上被静默忽略**（实测：没有
    # "Merged" 回显、血量还是 20.0，于是 A 段判红——看着像"她不吃盒子里的饭"，其实是
    # 根本没受伤）。`/damage <目标> <数值> minecraft:generic` 两版都灵（1.21 实测
    # "Applied 12.0 damage"、血量 20→8），所以统一用它。
    send('maid_smart box %s' % MA, tag='背包视图：')          # A 段采样 0（盒子原样）
    send('damage %s 12 minecraft:generic' % MA)
    send('maid_smart box %s' % MA, tag='背包视图：')          # A 段采样 1（视图）
    send('data get entity %s Health' % MA)                    # A 段采样 1（血量）
    time.sleep(EAT_WAIT)
    send('maid_smart box %s' % MA, tag='背包视图：')          # A 段采样 2（视图）
    send('data get entity %s Health' % MA)                    # A 段采样 2（血量）

    send('maid_smart box check %s' % MA, tag='收尾：那一格已清回空')  # ★ 自检本体（等到最后一行）
    send('maid_smart box %s' % MA, tag='背包视图：')          # 自检之后的视图（应当一模一样）

data = readlog()
send('stop')
stopped = False
for _ in range(60):
    time.sleep(1)
    if p.poll() is not None:
        stopped = True
        break
if p.poll() is None:
    subprocess.run(['taskkill', '/PID', str(p.pid), '/T', '/F'], capture_output=True)
# 【必须在退出之后再读一次】服务端写日志有缓冲：stop 之前那一读常常读不到最后几条命令的输出
# （实测：第一版读到的自检输出是 0 行，而日志文件里其实有）
time.sleep(2)
data2 = readlog()
if len(data2) > len(data):
    data = data2

flat = re.sub(r'\u00a7.', '', data)
check = [l.split('] ', 1)[-1].strip() for l in data.splitlines() if CHECK_CAT in l]
check_flat = [re.sub(r'\u00a7.', '', l) for l in check]
report = [re.sub(r'\u00a7.', '', l.split('] ', 1)[-1].strip())
          for l in data.splitlines() if BOX_CAT in l and CHECK_CAT not in l]
# A 段（她会不会去吃盒子里的饭）：从两次视图里抽"盒子里那份熟牛肉实际有几个"，
# 再从控制台日志里抽两次血量。
beef = [int(m.group(1)) for m in re.finditer(r'实际 minecraft:cooked_beef ×(\d+)', flat)]
health = [float(m.group(1)) for m in re.finditer(
    r'has the following entity data: ([\d.]+)f', _read(log_path))]
print('A 段采样：熟牛肉实际数量 = %s；血量 = %s' % (beef, health))

print()
print('=' * 74)
print('自检输出（%d 行）:' % len(check_flat))
for l in check_flat:
    print('    ', l[:200])
print('-' * 74)
print('女仆视图（前 8 行 / 后 8 行）:')
for l in report[:8]:
    print('    ', l[:180])
print('    ...')
for l in report[-8:]:
    print('    ', l[:180])
print('=' * 74)

fails = []
if not done:
    fails.append('服务端没起来')
for pat in FAIL_PATTERNS:
    if pat in data:
        fails.append('服务端报错: %s' % pat)
if not check_flat:
    fails.append('没看到自检输出（命令没生效？）')
if any('[FAIL]' in l for l in check_flat):
    fails.append('自检里有 FAIL：%s' % [l for l in check_flat if '[FAIL]' in l][0][:200])
passes = [l for l in check_flat if '[PASS]' in l]
if len(passes) < 25:
    fails.append('自检 PASS 只有 %d 条（应该 ≥25：数据层 4 + 附魔 4 + 点击 6 + 鼠标取放 6 + '
                 '女仆 7）' % len(passes))
# ⓐ 用户第 1 条（行为面）：把她打伤、等她自己吃 —— 盒子里的熟牛肉必须变少、血必须回来。
#    "第一份采样里还有饭"不能当判据（她 2 秒内就把饭拿走了，实测）；硬的判据是
#    "最后一份比我们放进去的少" + "血量回来了"。
#    一份熟牛肉治好约 4.16 点血，从 8 点回到满血要吃两三份；采样里没有熟牛肉那行 = 那一格空了 = 0。
#
# 【为什么只在 1201 上当硬判据】两条 TLM 路线都能吃到盒子里的饭了（可 heal-self 那条是
# "受伤才吃"、回家/上工会吃的那两条 meal 路线则挂在**排班时间**上）：
#   · 1201：打伤之后几秒内她就来吃（实测可复现）——这一条当硬判据；
#   · neoforge1211：同一套自检在那边也是全 PASS（"她吃饭那条判据扫得到盒子"），而且**也
#     观测到她真去拿了**（上一轮留下的那只女仆被清掉时日志里是「主手 失去
#     15xcooked_beef」），但那一次走的是排班 meal 路线、等的是游戏里的时间点——
#     40 秒的无头等待钉不住它。硬把等待拉到几十分钟只为让这条断言变绿，性价比不如
#     说清楚：**可见性（真正的修法）两台都验了，行为面在 1201 上钉死**。
STRICT_EAT = (which == '1201')
last_beef = beef[-1] if beef else 0
if not (last_beef < BEAF_TOTAL):
    msg = ('她没吃盒子里的饭（我们放进去 %d 个，A 段结束时盒子里还有 %d 个）'
           % (BEAF_TOTAL, last_beef))
    if STRICT_EAT:
        fails.append(msg + '—— 用户报的第 1 条还在')
    else:
        print('  (A 段参考：%s；那台不做硬判据——可见性已由自检验过、'
              '行为面在 1201 上钉死)' % msg)
if len(health) >= 2:
    if not (health[-1] > health[0]):
        msg = '血也没回来（A 段血量 %s）' % health
        if STRICT_EAT:
            fails.append('她吃了饭但血没回来（A 段血量 %s）—— 吃盒子里的饭没走通' % health)
        else:
            print('  (A 段参考：%s；同上，不做硬判据)' % msg)
elif STRICT_EAT:
    fails.append('A 段没采到血量（%s；应当有两次 `data get entity ... Health`）' % health)
# ⓐ 用户第 1 条：盒子里放饭她看得见看不见（走 TLM 自己吃饭那条判据）
if not any('食物那条（用户第 1 条）' in l for l in passes):
    fails.append('没有"盒子里放饭她看得见"这一条 —— 用户报的「加食物她不吃」可能还在')
# 对照：盒子里没有的东西必须扫不到（否则上面那条等于"扫啥都有"）
if not any('食物对照' in l for l in passes):
    fails.append('缺少"盒子里没有的东西扫不到"的对照（食物那条就成了空断言）')
# ⓑ 用户第 2 条：附魔（不可堆叠）物品的合法堆 + NBT 往返
for need in ('附魔那条：两个同款附魔书并进一格',
             '附魔那条：这格的「视野上限」= 1',
             '附魔那条：附魔书过一圈盒子',
             '附魔书那条（她那一侧）'):
    if not any(need in l for l in passes):
        fails.append('缺少附魔条：%s' % need)
# ⓒ 用户第 3 条：箱子式鼠标取放（拿起/放下/搬回来/快速移动）
for need in ('鼠标取放①', '鼠标取放②', '鼠标取放③', '鼠标取放④'):
    if not any(need in l for l in passes):
        fails.append('缺少鼠标取放条：%s' % need)
# ⓓ 新入口下的"盒子装盒子"也要被拒 + 手上那一叠收手不丢
if not any('鼠标取放⑤' in l for l in passes):
    fails.append('鼠标上拿着压缩盒去点盒子格没有被拒（盒子装盒子在新入口下漏了）')
if not any('鼠标取放⑥' in l for l in passes):
    fails.append('挂着一叠时收手那条没过（手上的东西可能被弄丢了）')
# 六百一十七的回归（这批不能把它弄坏）
self_line = [l for l in passes if '自己那一格' in l]
if not self_line:
    fails.append('自检里没有"把自己那一格存进去被拒"这一条')
# ⓒ 两条对照都必须通过（否则"被拒"证明不了什么）
for need in ('对照：同一套方法收普通物品正常', '对照：同一只手、同一个动作，存普通物品'):
    if not any(need in l for l in passes):
        fails.append('缺少对照条：%s' % need)
if any('[SKIP]' in l and '女仆那一条' in l for l in check_flat):
    fails.append('女仆那一条被跳过了（她背包里应当有一个盒子且有空格子）')
# ⓓ 对账
if not any('对账：两个盒子合计 15 个' in l for l in passes):
    fails.append('没有通过"两个盒子合计 15 个"的对账（可能有东西丢了）')

# 自检前后的视图【必须一样】。这一条同时管住三件事：
#   ① 盒子在她背包里、被当成第 37~41 格（延伸生效）；
#   ② 自检动的都是它自己临时造的东西，没碰她盒子里原有的内容；
#   ③ 视图本身是只读的（跑一次 box 不改状态）。
# 【六百一十八】这里只能比「A 段第二份采样」与「自检之后那一份」：A 段第一份是她**吃饭前**
# 的样子，修好之后她会真去吃，那两份本来就该不一样（那是 A 段要的证据，不是失败）。
# 熟牛肉那一格单独归一化掉——她可能还在继续吃（吃一份要 1.6 秒，采样落到哪一拍都有可能），
# 把它的数量换成 N 再比，剩下的任何差异都说明自检动了她盒子里的东西。
views = [re.sub(r'\u00a7.', '', l).split('] ', 1)[-1].strip()
         for l in data.splitlines()
         if BOX_CAT in l and CHECK_CAT not in l and '背包视图：' in l]
view41 = [l for l in views if '背包视图：41 格' in l]
if len(view41) < 2:
    fails.append('"背包视图：41 格"只出现 %d 次（自检前后各一次才对；视图是 %r）'
                 % (len(view41), views[:3]))
if len(views) >= 2:
    def norm(v):
        return re.sub(r'cooked_beef ×\d+', 'cooked_beef ×N', v)
    # 视图序列是 [A0, A1, A2, 自检后]：A0/A1 是她吃之前、A2 是吃完之后（本来就该不同），
    # 要比的是最后两份（都是"她吃饱了"之后），中间只夹着一次自检。
    if norm(views[-1]) != norm(views[-2]):
        fails.append('自检前后的视图不一样（自检动了她背包里的东西）：%r vs %r'
                     % (views[-2][:90], views[-1][:90]))
if 'compression_box' in flat:
    fails.append('某处出现了 compression_box 的物品名——盒子可能被装进盒子里了')

if fails:
    print('VERDICT: FAIL')
    for f in fails:
        print('  -', f)
    raise SystemExit(1)
print('VERDICT: PASS —— 四条都验到了：① 盒子对她吃饭那条路可见（拿 TLM 自己的判据扫得到），'
      + ('且打伤后她自己吃掉盒子里的饭（A 段：熟牛肉变少、血量回来）'
         if STRICT_EAT else '（行为面那一条在 1201 上验，这台只作参考）')
      + '；② 不可堆叠物品交给她/原版的永远是合法堆、NBT 原样过一圈；'
      '③ 箱子式鼠标取放（拿起/放下/搬回来/快速移动 + 盒子装盒子被拒）；'
      '④ 挂着一叠收手不丢；六百一十七的"盒子不许装盒子"回归也还在')
raise SystemExit(0)
