# -*- coding: utf-8 -*-
"""实测六百二十（其一）空闲散步速度可调：倍率乘在女仆基础移速 0.7 上，下限放到 0.05。

用法:
    python test_stroll620.py [1201|neoforge1211] [jar路径]

需求原文（用户）:
    "1.女仆空闲散步移动速度调不了，0.1倍速都跟快步跑一样"

【根因（javap 实证）】散步的 walkTarget 速度倍率从来没写错，它乘在**女仆的基础移动速度
属性**上：MaidMoveControl.tick 走的就是原版 setSpeed(speedModifier × MOVEMENT_SPEED)，
而 TLM 没动过这个属性 —— 它是 LivingEntity.createLivingAttributes 带进来的原版默认值 0.7
（玩家 0.1、僵尸 0.23、村民 0.5）。所以 0.7 倍率 = 玩家走路约 5 倍，而老配置的下限卡在 0.3
（= 玩家走路 2 倍）——**想调慢的人怎么写都慢不下来**。

【这条用例怎么证】不看代码看读数：
  0. 先试跑一次（0.5 档）：她真会走才继续——走不起来就把现场（Pos/Motion/脚下方块/身边实体）打出来
  1. /maid_smart stroll check  → 基础移速属性、实测参考表、口诀
  2. 三个档位各走一次（0.15 / 0.4 / 0.7）：起步 1.5 秒取一次全程均值 = 她此刻的格/秒
     （不取"巡航窗口"：快档她 2 秒走完 20 格就停住等目标更新，停住那段会把窗口读数拉低）
  3. 三个读数必须随倍率单调上升、0.15 真的慢、0.7 真的快、0.7/0.15 ≥ 3
     = 这个旋钮真的接在移动上（没接上三次会一样快）
  4. speed 0.005 → 必须夹到 0.05 并写进配置文件（老下限 0.3 是"调不了"的另一半原因）
  5. 收尾把 strollSpeed 还原成原值（用例不留痕）
"""
import hashlib
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
        'args': ['@user_jvmargs_placeholder.txt',
                 '@libraries/net/minecraftforge/forge/1.20.1-47.4.23/win_args.txt', 'nogui'],
        'jar': r'C:/Users/Sketch/.zcode/workspace/default/promaid-mod/patched/promaid-1.2.3.jar',
        'modname': 'promaid-1.2.3.jar',
    },
    'neoforge1211': {
        'dir': r'C:/Users/Sketch/mc_server_test/neoforge1211',
        'java': r'C:/Users/Sketch/AppData/Roaming/.minecraft/runtime/java-runtime-delta/bin/java.exe',
        'args': ['@user_jvm_args.txt',
                 '@libraries/net/neoforged/neoforge/21.1.250/win_args.txt', 'nogui'],
        'jar': r'C:/Users/Sketch/.zcode/workspace/default/promaid-mod/patched/'
               r'promaid-1.2.3-neoforge-1.21.1.jar',
        'modname': 'promaid-1.2.3-neoforge-1.21.1.jar',
    },
}
# 1.20.1 那台的 jvm args 文件名与 neo 不同（沿用 test_target619 的写法）
TARGETS['1201']['args'][0] = '@user_jvm_args.txt'

WAIT = 240
FAIL_PATTERNS = ('Mixin apply for mod promaid failed', 'InvalidInjectionException',
                 'MixinTransformerError', 'MixinApplyError', 'Failed to create brain',
                 'OutOfMemoryError', 'NoSuchMethodError', 'NoClassDefFoundError')
CAT = '散步速度自检'
MEASURE_RE = re.compile(r'上次测量：倍率 ([\d.]+)（生效 ([\d.]+)）、全程均值 ([\d.]+) 格 / (\d+) tick = ([\d.]+) 格/秒')
CRUISE_RE = re.compile(r'巡航速度（起步 \d+ tick 后取样、(\d+) tick）：([\d.]+) 格/秒')
# 实测（六百二十，同一台专用服务器）：0.15 → ≈0.5、0.4 → ≈3.4、0.7 → ≈6~8 格/秒
# 断言用的是"档位越大越快 + 慢档真的能慢下来"，不钉死绝对值（不同机器/地形会有差异）
SLOW_MAX = 1.0            # 0.15 档的读数上限（"能调到很慢"）
FAST_MIN = 4.0            # 0.7 档的读数下限（实测约 8~9.5）
RATIO_MIN = 3.0           # 0.7 / 0.15 至少要有这么大差距（倍率之比 4.67）
MULTS = (0.15, 0.4, 0.7)
RUN_TAG = '620'

which = sys.argv[1] if len(sys.argv) > 1 else '1201'
cfg = TARGETS.get(which)
if not cfg:
    print('usage: python test_stroll620.py [1201|neoforge1211] [jar路径]')
    sys.exit(2)
if len(sys.argv) > 2:
    cfg = dict(cfg)
    cfg['jar'] = sys.argv[2]
    print('用指定的 jar:', cfg['jar'])

server = cfg['dir']
pid_file = os.path.join(server, 'server.pid')
CONFIG = os.path.join(server, 'config', 'promaid-common.toml')


def stop_server():
    if os.path.exists(pid_file):
        pid = open(pid_file).read().strip()
        subprocess.run(['taskkill', '/PID', pid, '/T', '/F'], capture_output=True)
        time.sleep(3)


def cfg_value(key):
    try:
        t = open(CONFIG, encoding='utf-8', errors='replace').read()
    except Exception:
        return None
    m = re.search(r'^\s*%s\s*=\s*(\S+)\s*$' % re.escape(key), t, re.M)
    return m.group(1) if m else None


stop_server()
ORIG_SPEED = cfg_value('strollSpeed')
print('原配置 strollSpeed = %s（用例收尾会还原成它）' % ORIG_SPEED)
mods_dir = os.path.join(server, 'mods')
for old in os.listdir(mods_dir):
    if old.startswith('promaid-') and old != cfg['modname']:
        os.remove(os.path.join(mods_dir, old))
shutil.copyfile(cfg['jar'], os.path.join(mods_dir, cfg['modname']))
print('jar copied:', cfg['modname'], os.path.getsize(cfg['jar']),
      'md5', hashlib.md5(open(cfg['jar'], 'rb').read()).hexdigest()[:12])

log_path = os.path.join(server, 'console_stroll620.log')
plog = os.path.join(server, 'logs', 'promaid.log')
_plog0 = os.path.getsize(plog) if os.path.exists(plog) else 0
log = open(log_path, 'wb')
p = subprocess.Popen([cfg['java'], '-Xmx3G', '-Dfile.encoding=UTF-8'] + cfg['args'],
                     cwd=server, stdout=log, stderr=subprocess.STDOUT,
                     stdin=subprocess.PIPE,
                     creationflags=subprocess.CREATE_NEW_PROCESS_GROUP | 0x00000008)
open(pid_file, 'w').write(str(p.pid))
print('server pid', p.pid)


def send(cmd, wait=True, timeout=120, tag=None):
    print('   >', cmd[:180])
    try:
        p.stdin.write((cmd + '\n').encode('utf-8'))
        p.stdin.flush()
    except Exception as e:
        print('   (stdin failed: %s)' % e)
        return
    if not wait or tag is None:
        time.sleep(0.6)
        return
    t0 = time.time()
    while time.time() - t0 < timeout:
        time.sleep(0.5)
        if p.poll() is not None:
            return
        if tag in readlog():
            time.sleep(0.3)
            return
    print('   (等日志超时: %s)' % tag)


def _read(path, off=0):
    try:
        with open(path, 'rb') as f:
            f.seek(off)
            raw = f.read()
    except Exception:
        return ''
    return raw.decode('utf-8', 'replace')


def readlog():
    t = _read(plog, _plog0)
    return t if t else _read(log_path)


def settle(quiet=6, cap=200):
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


def cat_lines():
    flat = re.sub(r'\u00a7.', '', readlog())
    return [l.split('] ', 1)[-1].strip() for l in flat.splitlines() if CAT in l
            and l.split('] ', 1)[-1].strip()]


done = False
for _ in range(WAIT):
    time.sleep(1)
    if p.poll() is not None:
        break
    if 'Done (' in _read(log_path):
        done = True
        break

fails = []
measure = {}
if not done:
    fails.append('服务端没起来')
else:
    print('server done, settling ...')
    print('   settled:', settle())
    NAME = 'Stroll620Maid' + RUN_TAG
    MAID_TAG = 'stroll620maid' + RUN_TAG
    send('gamerule doMobSpawning false', wait=False)
    send('difficulty easy', wait=False)
    send('gamerule randomTickSpeed 0', wait=False)
    send('gamerule doWeatherCycle false', wait=False)
    send('time set noon', wait=False)
    send('weather clear', wait=False)
    send('kill @e[tag=%s]' % MAID_TAG)
    send('kill @e[type=minecraft:item]')
    # 【为什么在天上、又为什么必须在出生点附近】实测六百二十 在 1.21.1 那台上连着踩了两次坑：
    # ① 在**世界出生点的地形上**搭平台量出"全程均值 0.000 格"（她不动、动量却是常数）——换到
    #    空中一块干净平台上立刻走起来了，是场地/遗留实体的问题；
    # ② 想搬到 x=z=400 的远处（`forceload` + `fill` + `summon`）——**forceload 还没生效**，
    #    `summon` 落在未加载的区块里直接没了，后面每条命令都回 "No entity was found"。
    # 所以：在**出生点区块**（默认常加载）上方 y=120 搭平台，不碰 forceload。
    send('gamerule doWeatherCycle false', wait=False)
    send('time set noon', wait=False)
    send('weather clear', wait=False)
    send('fill -30 119 -30 30 119 30 minecraft:stone')
    send('fill -30 120 -30 30 132 30 minecraft:air')
    maid_nbt = ('{MaidScheduleMode:"ALL",HandItems:[{},{}],ArmorItems:[{},{},{},{}],'
                'MaidInventory:{Size:36,Items:[]},Owner:[I;1,2,3,4],Tags:["%s"],'
                'CustomName:"\\"%s\\"",PersistenceRequired:1b}' % (MAID_TAG, NAME))
    send('summon touhou_little_maid:maid 0.5 120 0.5 %s' % maid_nbt)
    MA = '@e[tag=%s,limit=1]' % MAID_TAG
    # 【必须确认她真的在】summon 失败是不报错的（命令只回一行"Summoned new ..."，落空则什么都没有），
    # 而后面每条命令都会回 "No entity was found"——这里当场查一次，省得几百行日志里翻。
    _before = readlog()
    send('data get entity %s Pos' % MA, wait=False)
    time.sleep(1.0)
    if 'No entity was found' in readlog()[len(_before):]:
        fails.append('女仆没召出来（summon 落在未加载区块/地形里？）——后面所有读数都会是空的')
    else:
        print('女仆已就位（Pos 读到了）')

    # ==================== 0) 试跑一次：她到底会不会走 ====================
    # 【为什么先试跑】上一次 1.21.1 那台跑出"全程均值 0.000 格"（她不动、动量是常数），
    # 排查下来是场地/遗留实体问题。与其让后面三条读数一起红、还得翻日志猜，不如先走一次：
    # 走起来了才继续，没走起来就把现场（Pos / Motion / 脚下方块 / 身边 8 格的实体）打出来并判红。
    send('maid_smart stroll speed 0.5', tag='实际生效为')
    send('maid_smart stroll go %s' % MA, tag='开始测量')
    time.sleep(2.0)
    send('maid_smart stroll check %s' % MA, tag='上次测量')
    pre = None
    for l in cat_lines():
        m = MEASURE_RE.search(l)
        if m and abs(float(m.group(1)) - 0.5) < 1e-6:
            pre = (float(m.group(3)), int(m.group(4)), float(m.group(5)))
    if pre is None or pre[0] < 1.0:
        send('data get entity %s Pos' % MA)
        send('data get entity %s Motion' % MA)
        send('execute at %s run data get block ~ ~-1 ~' % MA)
        send('execute as @e[distance=..8,limit=6] run data get entity @s Pos')
        diag = readlog()
        fails.append('试跑没走起来（%s）——现场：%s'
                     % (pre, ' / '.join(l.strip()[-160:] for l in diag.splitlines()[-8:])))
    else:
        print('   试跑：走了 %.2f 格 / %d tick = %.3f 格/秒（她会走，继续）' % pre)

    # ==================== 1) 换算表：她要写的那个数说清楚 ====================
    send('maid_smart stroll check %s' % MA, tag='倍率→实际速度')
    first = cat_lines()
    print('   第一份 check：')
    for l in first:
        print('      ', l[:200])

    # ==================== 2/3) 三个倍率各走一次，比实测速度 ====================
    # 【取哪一拍的读数】实测六百二十 踩到的坑：0.7 档她 20 格只要 2 秒出头，走到路径末端就
    # 停住了（站在原地等目标更新），所以"起步 1 秒后的巡航窗口"和"全程均值"在快档上都会被
    # **停住的那段时间**拉低（neo 那台量出 0.7 → 1.18，比 0.4 还慢，纯粹是停住导致的）。
    # 可靠的读法是**走起来之后立刻取一次全程均值**（第一拍，约 1.5 秒）：三个档位都还在走，
    # 均值也不需要等"到点定格"。巡航窗口那一行照样打出来（当参考），只是不作为判据。
    for mult in MULTS:
        # 每次测量前把她放回平台中心（不重新 summon：实测 kill+同帧 summon 会让
        # @e[tag=...,limit=1] 选到正在消失的那一只，后面的读数全是尸体的"原地不动"）
        send('tp %s 0.5 120 0.5' % MA, wait=False)
        time.sleep(0.5)
        send('maid_smart stroll speed %s' % mult, tag='实际生效为')
        send('maid_smart stroll go %s' % MA, tag='开始测量')
        time.sleep(1.5)
        send('maid_smart stroll check %s' % MA, tag='上次测量')
        mine = None
        for l in cat_lines():
            if '倍率 %s（生效' % mult in l:
                m = MEASURE_RE.search(l)
                if m:
                    mine = {'mean': float(m.group(5)), 'dist': float(m.group(3)),
                            'ticks': int(m.group(4)), 'line': l}
        measure[mult] = mine
        print('   倍率 %s：%s' % (mult, '（没读到）' if mine is None else
                                '走了 %.2f 格 / %d tick = %.3f 格/秒' %
                                (mine['dist'], mine['ticks'], mine['mean'])))

    # ==================== 4) 下限：越界夹到 0.05 并落盘 ====================
    send('maid_smart stroll speed 0.005', tag='越界')
    clamp_lines = [l for l in cat_lines() if '越界' in l]
    clamp_file = cfg_value('strollSpeed')

    # ==================== 5) 收尾：还原原值 ====================
    send('maid_smart stroll speed %s' % (ORIG_SPEED or '0.4'), tag='实际生效为')
    restore_file = cfg_value('strollSpeed')

all_lines = cat_lines()
send('stop')
for _ in range(60):
    time.sleep(1)
    if p.poll() is not None:
        break
if p.poll() is None:
    subprocess.run(['taskkill', '/PID', str(p.pid), '/T', '/F'], capture_output=True)
time.sleep(2)
flat = re.sub(r'\u00a7.', '', readlog())
all_lines = [l.split('] ', 1)[-1].strip() for l in flat.splitlines() if CAT in l
             and l.split('] ', 1)[-1].strip()]

print()
print('=' * 74)
print('自检输出（%d 行）:' % len(all_lines))
for l in all_lines:
    print('    ', l[:230])
print('=' * 74)

joined = '\n'.join(all_lines)
if not all_lines:
    fails.append('没看到自检输出（命令没生效？）')

# ① 基础属性 + 实测参考表 + 口诀
if not re.search(r'MOVEMENT_SPEED = 0\.700', joined):
    fails.append('基础移速属性不是 0.700（读数没打出来或女仆属性被改过）')
if '实测参考' not in joined:
    fails.append('没看到「实测参考」那张表')
if not re.search(r'0\.1~0\.2 → 0\.1（几乎不走）、0\.3 → 1\.9、0\.4 → 3\.3、0\.5 → 4\.9、'
                 r'0\.6 → 6、0\.7 → 8、1\.0 → 14', joined):
    fails.append('实测参考表的数值不对（应当与 六百二十 量出来的那组一致）')
if not re.search(r'口诀：想「像玩家一样走路」→ 0\.4~0\.5', joined):
    fails.append('口诀不对：应当指向 0.4~0.5')
if not re.search(r'玩家走路 4\.317、奔跑 5\.612 格/秒', joined):
    fails.append('缺少玩家走路/奔跑的参照值')

# ② 三个倍率都量到了速度，而且随倍率单调、慢档真的慢、快档真的快
missing = [m for m in MULTS if not measure.get(m) or measure[m].get('mean') is None]
if missing:
    fails.append('这些倍率没读到读数：%s' % missing)
else:
    table = {m: measure[m]['mean'] for m in MULTS}
    print('实测（起步 1.5 秒的均值）：' + '；'.join('%s → %.3f 格/秒' % (m, table[m]) for m in MULTS))
    ratio = table[0.7] / table[0.15] if table[0.15] > 0 else 0
    print('0.7 / 0.15 实测比值 %.2f（倍率之比 %.2f；0.4 档 %.3f）'
          % (ratio, 0.7 / 0.15, table[0.4]))
    if not (table[0.15] < table[0.4] < table[0.7]):
        fails.append('实测速度没有随倍率单调上升：%s' % table)
    if table[0.15] > SLOW_MAX:
        fails.append('0.15 档实测 %.3f 格/秒，没慢下来（应当 ≤ %.1f）' % (table[0.15], SLOW_MAX))
    if table[0.7] < FAST_MIN:
        fails.append('0.7 档实测 %.3f 格/秒，快档没生效（应当 ≥ %.1f）' % (table[0.7], FAST_MIN))
    if ratio and ratio < RATIO_MIN:
        fails.append('0.7 与 0.15 的实测速度之比只有 %.2f（应当 ≥ %.1f，否则说明倍率没接在移动上）'
                     % (ratio, RATIO_MIN))
    if not any('倍率接上了：量出来的速度随倍率变' in l for l in all_lines):
        fails.append('缺少「倍率接上了」那条 PASS')

# ③ 越界夹到 0.05 并写进配置文件
if not clamp_lines:
    fails.append('stroll speed 0.005 没有回「越界，夹到 0.05」')
if clamp_file != '0.05':
    fails.append('越界之后配置文件里 strollSpeed = %s，应为 0.05（下限放到 0.05 没生效？）' % clamp_file)
if restore_file != (ORIG_SPEED or '0.4'):
    fails.append('收尾还原失败：配置文件里 strollSpeed = %s，应为 %s' % (restore_file, ORIG_SPEED))

if '[FAIL]' in joined:
    fails.append('自检里有 FAIL：%s' % [l for l in all_lines if '[FAIL]' in l][0][:220])
# 【注意】开头那次 check（还没 go 过）会**故意**回一条「还没测过」的 SKIP——那是设计，
# 不是漏验；除此之外不许有 SKIP。
other_skips = [l for l in all_lines if '[SKIP]' in l and '还没测过' not in l]
if other_skips:
    fails.append('自检里有 SKIP：%s' % other_skips[0][:220])

for pat in FAIL_PATTERNS:
    if pat in flat:
        fails.append('服务端报错: %s' % pat)

if fails:
    print('VERDICT: FAIL')
    for f in fails:
        print('  -', f)
    raise SystemExit(1)
print('VERDICT: PASS —— 基础移速 0.7 已摆明；实测速度随倍率单调上升（0.15 → %.3f、'
      '0.4 → %.3f、0.7 → %.3f 格/秒，0.7/0.15 比值 %.2f，玩家走路 4.317）；'
      '下限放到 0.05、越界会夹住并落盘；收尾已还原成 %s'
      % (table[0.15], table[0.4], table[0.7], ratio, restore_file))
raise SystemExit(0)
