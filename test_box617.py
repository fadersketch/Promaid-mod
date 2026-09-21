# -*- coding: utf-8 -*-
"""实测六百一十七 的场景验证：压缩盒**不许装压缩盒**（用户报的"把自己装进去被卡掉"）。

用法:
    python test_box617.py [1201|neoforge1211]

需求原文（用户）:
    "3.存在一个bug,就是收纳箱可以把自己装进去，导致被卡掉。"

【为什么这个用例长得不一样】这个 bug 的入口是**界面的一次点击**（Shift+点背包格），
专用服务器上没有客户端、点不了界面。本仓六百一十六那批就是在这里栽过跟头：改的是点击
那条路，跑的却是"她看不看得见盒子"——测的东西根本不是改的东西。所以这一批先把那条路
做成**游戏里可跑的自检**（`/maid_smart box check [女仆]`，见 CompressionBoxCheck）：
用假玩家（FakePlayerFactory）在服务端把界面点击**真正调用的那个方法**
（CompressionBoxService.handle）原样走一遍。这个用例就是去跑它并检查结果。

【验的四件事】
  ⓐ **自己装自己**：手上那个盒子（界面上"打开的"就是它）Shift 存进**它自己那一格** →
     必须被拒、盒子还在手上、里面东西不变。这一条就是用户报的那一下。
  ⓑ **装另一个盒子**：背包里另一个盒子 → 同样被拒，两个盒子都在。
  ⓒ **对照**：同一只手、同一个动作，存普通物品（圆石）→ **必须成功**。六百一十六那批的
     教训：一个"被拒"如果不配一个"会通过"的对照，等于什么都没证明。
  ⓓ **对账 + 女仆那一条**：两个盒子合计件数 = 开始 + 对照存进去的（没有东西凭空消失）；
     她真实的背包视图上，往盒子里插压缩盒被退回、插石头成功、最后那一格清回空。

【这批验不到的那一层（写清楚）】界面本身的渲染（"存活物品栏那样显示数量与耐久条"）
需要真实客户端，本用例碰不到；那部分只能靠原版那套装饰绘制方法本身（`m_280370_` =
renderItemDecorations，javap 实证它就是画数量与耐久条的那个方法）来保证。
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

NAME = 'Box617Maid'
BOX_ID = 'maid_smart:compression_box'
CHECK_CAT = '压缩盒自检'
BOX_CAT = '压缩盒'
STONE_TOTAL = 114514
# 自检每跑一次都要留痕：promaid.log 是【跨轮追加】的（不清理），所以第一轮跑出来的 PASS
# 会一直躺在文件里。靠这条标记切出"本次 run 里的自检输出"，否则第二次跑会把旧 PASS 一起
# 数进来（甚至从旧记录里读到已经修掉的 FAIL）。run 编号 + 1 写进女仆名，命令里带上名字。
RUN_TAG = '617'

which = sys.argv[1] if len(sys.argv) > 1 else '1201'
cfg = TARGETS.get(which)
if not cfg:
    print('usage: python test_box617.py [1201|neoforge1211]')
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

log_path = os.path.join(server, 'console_box617.log')
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
    NAME = 'Box617Maid' + RUN_TAG
    ANCHOR_TAG = 'box617anchor' + RUN_TAG
    MAID_TAG = 'box617maid' + RUN_TAG
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
    # 她背包第 0 格 = 压缩盒：格 0 = 石头 ×114514（顺手证明大堆还在），格 1~4 留空
    # （女仆那一条要一个空格子；自检用完会清回去）
    box = box_nbt([(STONE_TOTAL, '{id:"minecraft:stone",Count:1}')])
    maid_nbt = ('{MaidScheduleMode:"ALL",HandItems:[{},{}],ArmorItems:[{},{},{},{}],'
                'MaidInventory:{Size:36,Items:[{Slot:0b,id:"%s",Count:1b,%s}]},'
                'Owner:[I;1,2,3,4],Tags:["%s"],'
                'CustomName:"\\"%s\\"",PersistenceRequired:1b}'
                % (BOX_ID, box, MAID_TAG, NAME))
    send('execute at %s run summon touhou_little_maid:maid ~ ~ ~ %s' % (A, maid_nbt),
         tag=NAME)
    MA = '@e[tag=%s,limit=1]' % MAID_TAG
    send('maid_smart box %s' % MA, tag='背包视图：')          # 自检之前的视图（对账用）
    send('maid_smart box check %s' % MA, tag='对账：两个盒子合计 15 个')  # ★ 自检本体
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
if len(passes) < 8:
    fails.append('自检 PASS 只有 %d 条（应该 ≥8：数据层 3 + 点击路径 4 + 女仆 3）' % len(passes))
# ⓐ 用户报的那一下：自己那一格
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

# 自检前后的视图【必须一字不差】。这一条同时管住三件事：
#   ① 盒子在她背包里、被当成第 37~41 格（延伸生效）；
#   ② 自检动的都是它自己临时造的东西，没碰她盒子里原有的内容；
#   ③ 视图本身是只读的（跑一次 box 不改状态）。
# 之前这里写的是"石头那格必须是 看得见 ×64 / 实际 ×114514"——那个前提是她盒子里装着
# 114514 个石头。她那一格为什么会是空的，见 box_nbt 的说明（1.20.1 的 tag: 到 1.21 要
# 换成 components:…）。现在改成两条各自成立、互不掩盖的断言：格数必须 41，两份视图必须相同。
views = [re.sub(r'\u00a7.', '', l).split('] ', 1)[-1].strip()
         for l in data.splitlines()
         if BOX_CAT in l and CHECK_CAT not in l and '背包视图：' in l]
view41 = [l for l in views if '背包视图：41 格' in l]
if len(view41) < 2:
    fails.append('"背包视图：41 格"只出现 %d 次（自检前后各一次才对；视图是 %r）'
                 % (len(view41), views[:3]))
if len(views) >= 2 and views[0] != views[-1]:
    fails.append('自检前后的视图不一样（自检动了她背包里的东西）：%r vs %r'
                 % (views[0][:90], views[-1][:90]))
if 'compression_box' in flat:
    fails.append('某处出现了 compression_box 的物品名——盒子可能被装进盒子里了')

if fails:
    print('VERDICT: FAIL')
    for f in fails:
        print('  -', f)
    raise SystemExit(1)
print('VERDICT: PASS —— 压缩盒装不进压缩盒（另一个盒子/自己那一格都被拒）、普通物品照常能存、'
      '两个盒子一件不少；女仆那一侧同样拒收、自检不留痕')
raise SystemExit(0)
