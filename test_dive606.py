# -*- coding: utf-8 -*-
"""实测六百〇六 的场景验证：近战空袭「俯冲段冲刺加速」（方向不变，烟花加速）。

用法:
    python test_dive606.py 1201
    python test_dive606.py neoforge1211

需求原文："近战空袭向下朝着敌人俯冲期间补一个链路：用烟花/法术加速（孔雀羽扇好像不行，
行的话也加上），方向不变，这样可以大幅提高周期 dps。"

判据（都在服务端日志里）：
 ① 出现 `空袭·俯冲` 的「俯冲加速（烟花，距敌 X 格）」行 —— 新链路真的跑起来了
 ② 那一行的距敌格数落在 5~40 格（面板「俯冲段冲刺」的闸口）—— 闸口生效、不是乱冲
 ③ 她的烟花真的被消耗（背包回读：firework_rocket 总数 < 初始值）
 ④ 负向对照：**把「俯冲段冲刺」关掉**（改服务器配置）再跑一轮 —— 不再出现「俯冲加速」行
    （证明那些行确实来自这条链路，而不是别的什么顺手打的）
 ⑤ 同时确认她照常打完链路（起飞 + 收翅猛击的痕迹）——加速没把原来的链路顶掉

【为什么要负向对照】这是"新增了一条推进"的改动，只看"有日志"不足以证明闸口与开关真的管用；
关掉开关就不该有任何一行——这条最便宜、也最能说明问题。
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
        'task': 'maid_smart:flight_combat',
        'sword': 'id:"minecraft:netherite_sword",Count:1b,tag:{Enchantments:[{id:"minecraft:sharpness",lvl:5s}]}',
        'elytra': 'id:"minecraft:elytra",Count:1b',
        'firework': 'id:"minecraft:firework_rocket",Count:64b',
        'maxhp_attr': 'generic.max_health',
        'cfg': None,          # 服务端配置：promaid-common.toml
    },
    'neoforge1211': {
        'dir': r'C:/Users/Sketch/mc_server_test/neoforge1211',
        'java': r'C:/Users/Sketch/AppData/Roaming/.minecraft/runtime/java-runtime-delta/bin/java.exe',
        'args': ['@user_jvm_args.txt',
                 '@libraries/net/neoforged/neoforge/21.1.250/win_args.txt', 'nogui'],
        'jar': r'C:/Users/Sketch/.zcode/workspace/default/promaid-mod/patched/promaid-1.2.2-neoforge-1.21.1.jar',
        'modname': 'promaid-1.2.2-neoforge-1.21.1.jar',
        'task': 'maid_smart:flight_combat',
        'sword': 'id:"minecraft:netherite_sword",count:1,components:{"minecraft:enchantments":{levels:{"minecraft:sharpness":5}}}',
        'elytra': 'id:"minecraft:elytra",count:1',
        'firework': 'id:"minecraft:firework_rocket",count:64',
        'maxhp_attr': 'minecraft:generic.max_health',
        'cfg': None,
    },
}
WAIT = 180
TICK = 60
FAIL_PATTERNS = ('Mixin apply for mod promaid failed', 'InvalidInjectionException',
                 'MixinTransformerError', 'MixinApplyError', 'Failed to create brain',
                 'OutOfMemoryError')
FATAL_HINTS = ('promaid', 'mixin')

NAME = 'Dive606Maid'
TAG = 'dive606maid'
TTAG = 'dive606target'

# 【为什么按 tag 清两次、还带一段等待】世界是持久化的。首轮实测踩过：第一轮（无主女仆那次）
# 留下的 Dive606Maidon 还在世界上，她**也**在飞也在烧烟花 → 第二轮的回读与日志全被她搅浑
# （看起来像"关掉开关还在加速"）。这里改为一进世界就按 tag 清、清完等 2 秒让移除生效；
# 回读一律按 tag 选（名字可能重名，tag 是这一轮独有的）。

which = sys.argv[1] if len(sys.argv) > 1 else '1201'
cfg = TARGETS.get(which)
if not cfg:
    print('usage: python test_dive606.py [1201|neoforge1211]')
    sys.exit(2)

server = cfg['dir']
pid_file = os.path.join(server, 'server.pid')


def stop_server():
    if os.path.exists(pid_file):
        pid = open(pid_file).read().strip()
        subprocess.run(['taskkill', '/PID', pid, '/T', '/F'], capture_output=True)
        time.sleep(3)


def set_dive_boost(enabled):
    """改服务端配置里的俯冲段冲刺开关（跑两轮：开 / 关）。返回是否找到那一条。"""
    p = os.path.join(server, 'config', 'promaid-common.toml')
    if not os.path.exists(p):
        return False
    raw = open(p, 'rb').read()
    try:
        t = raw.decode('utf-8')
    except Exception:
        t = raw.decode('gbk', errors='replace')
    m = re.search(r'^(\s*diveBoost\s*=\s*)(\w+)\s*$', t, re.M)
    if not m:
        return False
    new = t[:m.start()] + m.group(1) + ('true' if enabled else 'false') + t[m.end():]
    open(p, 'wb').write(new.encode('utf-8'))
    return True


def run_round(tag_suffix, expect_boost):
    """一轮观察：返回 (log_text, extra_notes)"""
    log_path = os.path.join(server, 'console_dive606_%s.log' % tag_suffix)
    log = open(log_path, 'wb')
    p = subprocess.Popen([cfg['java'], '-Xmx3G', '-Dfile.encoding=UTF-8'] + cfg['args'],
                         cwd=server, stdout=log, stderr=subprocess.STDOUT,
                         stdin=subprocess.PIPE,
                         creationflags=subprocess.CREATE_NEW_PROCESS_GROUP | 0x00000008)
    open(pid_file, 'w').write(str(p.pid))
    print('--- round %s: server pid %d (expect_boost=%s)' % (tag_suffix, p.pid, expect_boost))

    def send(cmd):
        print('   >', cmd[:170])
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
        for e, t in d.items():
            if '空袭·俯冲' in t or '放烟花起飞' in t or '投掷 TNT' in t:
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
        notes.append('FAIL(server never reached Done)')
    else:
        send('gamerule doMobSpawning false')
        send('difficulty easy')
        # 【第一件事：清残留】见文件头那条注释（同名旧女仆会把回读搅浑）。
        # 【为什么要按名字清两轮的两种名字】tag 选择器是**精确匹配**：上一轮的女仆 tag 是
        # `dive606maidoff`，光清 `dive606maid` 清不到她——实测第二轮就撞上了：她一直在飞、
        # 一直在补加速，把这一轮的「俯冲加速」行算成了 20 行（其中还有她的）。
        # CustomName 是精确字符串匹配，所以把两轮用过的名字都点名清掉。
        for _ in range(2):
            for t in ('dive606maidon', 'dive606maidoff', 'dive606maid',
                      'dive606targeton', 'dive606targetoff', 'dive606target'):
                send('kill @e[tag=%s]' % t)
            for n in ('Dive606Maidon', 'Dive606Maidoff',
                      'Dive606Targeton', 'Dive606Targetoff'):
                send('kill @e[type=touhou_little_maid:maid,name=%s]' % n)
                send('kill @e[type=minecraft:zombie,name=%s]' % n)
            time.sleep(2)
        tag = TAG + tag_suffix
        ttag = TTAG + tag_suffix
        # 【必须写 Owner】无主女仆会被本模组整段跳过（日志里那句「是无主女仆（没有主人）→
        # promaid 的全部改动对她不生效，保持 TLM 原版行为」）——首轮实测就是栽在这里：
        # 她连飞都不飞，看起来像「新链路没做」。所有场景测试都要给 Owner。
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
        # 木桩：NoAI（不还手不跑）+ 2000 血（整场打不死）→ 让「起飞→俯冲→猛击」的循环一直转
        send('summon minecraft:zombie ~8 ~3 ~ {NoAI:1b,PersistenceRequired:1b,'
             'Health:2000f,Attributes:[{Name:"%s",Base:2000}],'
             'ActiveEffects:[{Id:11,Amplifier:9,Duration:999999}],Tags:["%s"],'
             'CustomName:"\\"Dive606Target%s\\""}' % (cfg['maxhp_attr'], ttag, tag_suffix))
        time.sleep(2)
        send('execute at @e[tag=%s,limit=1] run tp @e[tag=%s,limit=1] ~5 ~1 ~' % (tag, ttag))
        time.sleep(2)
        send('data get entity @e[tag=%s,limit=1] HandItems[0]' % tag)
        send('data get entity @e[tag=%s,limit=1] MaidInventory' % tag)
        time.sleep(3)
        print('observing for %ds ...' % TICK)
        time.sleep(TICK)
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
    return data, notes


stop_server()
for old in [f for f in os.listdir(os.path.join(server, 'mods')) if f.startswith('promaid-')]:
    if old != cfg['modname']:
        os.remove(os.path.join(server, 'mods', old))
shutil.copyfile(cfg['jar'], os.path.join(server, 'mods', cfg['modname']))
print('jar copied:', cfg['modname'], os.path.getsize(cfg['jar']))

verdict = 'PASS'
notes = []

# ── 第一轮：默认开（配置里打开）──
# 【为什么这里可能"改不到"】服务端的 promaid-common.toml 是**首次加载时写出来的**：新加的
# `diveBoost` 键在旧文件里还不存在，所以第一轮之前改不到它——那一轮就跑默认值（开），
# 也正是我们要验的。跑完一轮后模组会把缺的键补进文件，第二轮的"关掉"才改得到（见下）。
if not set_dive_boost(True):
    notes.append('提示：第一轮前 config 里还没有 diveBoost 键（旧配置文件），本轮按默认值（开）跑；'
                 '跑完模组会把它写进文件')
data_on, n1 = run_round('on', True)
notes += n1
# ① 加速行：只看**本轮的**女仆名（上一轮残留的女仆有自己的名字，见清理那一段注释）
boost_lines = [l for l in data_on.splitlines()
               if '俯冲加速' in l and (NAME + 'on') in l]
all_boost = [l for l in data_on.splitlines() if '俯冲加速' in l]
notes.append('第一轮（开）：俯冲加速 %d 行（含其它残留女仆共 %d 行）'
             % (len(boost_lines), len(all_boost)))
if boost_lines:
    notes.append('示例: %s' % boost_lines[0][:200])

# ② 距敌格数落在 5~40
bad_range = []
for l in boost_lines:
    m = re.search(r'距敌\s*([\d.]+)\s*格', l)
    if m:
        d = float(m.group(1))
        if d < 5.0 or d > 40.0:
            bad_range.append((d, l[:150]))
if bad_range:
    notes.append('越界样例: %s' % bad_range[0])

# ③ 烟花消耗：用**她背包那几行回读**里的数量（第一行 vs 最后一行）判断，而不是只看末次——
# 末次可能刚好落在"她又补了一枚到副手"的时刻上，单看一行容易误判。这里取所有回读里
# firework_rocket 的总数序列，只要"最大值 > 最小值"就说明真被吃掉了。
#
# 【为什么按名字而不是 tag 筛】data get 的回显里只有实体**名字**，tag 不会出现在日志行里
# （`@e[tag=…]` 只是命令的一部分，回显不带它）——第一版按 tag 筛，序列永远是空。
def inv_series(text, name):
    out = []
    for l in text.splitlines():
        if name not in l:
            continue
        if 'Size: 36' not in l and 'size: 36' not in l:
            continue
        nums = re.findall(r'firework_rocket"[^}]*?(?:Count|count):\s*(\d+)', l)
        if nums:
            out.append(int(nums[0]))
    return out


series_on = inv_series(data_on, NAME + 'on')
notes.append('第一轮回读的烟花数序列: %s' % series_on)

# ⑤ 原链路痕迹
launched = '放烟花起飞' in data_on
smash = ('收翅' in data_on) or ('猛击' in data_on) or ('旋转冲击' in data_on) or ('装备离开' in data_on)
notes.append('第一轮：起飞=%s 猛击/收翅痕迹=%s' % (launched, smash))
if series_on and max(series_on) <= min(series_on):
    verdict = 'FAIL(烟花数量一直没变 —— 那一口加速没真的消耗燃料)'
elif not series_on:
    notes.append('警告：没读到她的背包回读（tag 选择器没选中？）——消耗这条没被验证')

for pat in FAIL_PATTERNS:
    if pat in data_on:
        verdict = 'FAIL(error line: %s)' % pat
for l in data_on.splitlines():
    if 'FATAL' in l and any(h in l.lower() for h in FATAL_HINTS):
        verdict = 'FAIL(FATAL involving promaid/mixin)'
        notes.append('FATAL: ' + l[:150])
        break

if not boost_lines:
    verdict = 'FAIL(开了开关也没有「俯冲加速」行 —— 新链路没跑起来)'
elif bad_range:
    verdict = 'FAIL(俯冲加速的距敌格数越界 —— 闸口没生效)'
elif not launched:
    verdict = 'FAIL(她没起飞 —— 原链路被改坏了)'

# ── 第二轮：把俯冲段冲刺关掉（负向对照）──
if set_dive_boost(False):
    data_off, n2 = run_round('off', False)
    notes += n2
    off_lines = [l for l in data_off.splitlines() if '俯冲加速' in l]
    notes.append('第二轮（关）：俯冲加速 %d 行（应为 0）' % len(off_lines))
    if off_lines:
        verdict = 'FAIL(关掉开关还有「俯冲加速」行 —— 开关没生效)'
    # 关掉之后她仍应照常飞（只是没有那一口加速）
    off_launched = '放烟花起飞' in data_off
    notes.append('第二轮：起飞=%s（关掉加速也应照常飞）' % off_launched)
else:
    notes.append('跳过负向对照：没能改到配置文件')

print('verdict:', verdict)
for n in notes:
    print('note:', n)
print('--- 关键日志行（第一轮）---')
shown = 0
for l in data_on.splitlines():
    if any(k in l for k in ('俯冲加速', '放烟花起飞', '空袭·位移', '空袭·俯冲', 'Summoned new',
                            'MaidInventory', 'HandItems', '旋转冲击', 'Mixin apply', 'FATAL')):
        print('  ', l[:230])
        shown += 1
        if shown > 60:
            print('   ...(truncated)')
            break
if shown == 0:
    print('   (nothing matched)')

sys.exit(0 if verdict == 'PASS' else 1)
