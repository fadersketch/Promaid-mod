# -*- coding: utf-8 -*-
"""实测六百〇九 的场景验证：本模组自己的"手上亮一下"不该把空袭就绪判定带偏。

用法:
    python test_offhand609.py 1201         # 两轮（A 假提示判据 / B 阳性对照）
    python test_offhand609.py 1201 red     # 只跑 A 轮，且**预期假提示出现**（证明本脚本抓得住修之前的行为）

需求（粉丝现场原话）："明明拿着孔雀羽扇，但她却还是说缺少飞行道具。执行链路可以执行，
但这个系统消息不停。"

现场定因（实机日志，逐毫秒对得上）：
  玩家的孔雀羽扇挂在女仆**副手**上；本模组自己的副手动作表现（`BombPose`：放置 / 充能 /
  投掷那一刻把"她正在用的那件"放进副手十几 tick，原物快照记在 State.original 里）每十几秒
  把副手借走一次 —— 而就绪判定（`MaidFlightKit.hasFirework/hasFan`）只看**槽位**，
  那十几 tick 里羽扇/烟花"不在任何格子里" → `missingParts` 报「可以飞行的道具」；
  借还之后判定又齐了、把 15 秒播报冷却**清零**，下一次链路过手又是一条 → 假系统消息
  每 10 秒一条、永远不停。（实机证据：12:26:22.021 副手 fan→respawn_anchor，12:26:22.532
  就是那条系统消息；12:26:23.030 副手刚还回 fan，下一轮 12:26:43.322 借走、12:26:43.332
  又报一条。）

本测试用**烟花火箭**顶替孔雀羽扇（测试服没装暮色森林，机制完全相同：燃料只挂在副手），
断言：
  A 轮（副手 64 枚烟花；背包里**没有**烟花）：
    ① 轰炸链路真的起手过（「投掷 TNT」≥1）—— "副手被借走"的窗口真的出现过
    ② 「空袭装备 … 缺可以飞行的道具」**0 行** —— 本批要治的假提示
    ③ 她真的飞起来过（「放烟花起飞」≥1）—— 场景不是"她压根没进入空袭"
  B 轮（副手也不给烟花 = 阳性对照）：
    ④ ≥1 行「缺可以飞行的道具」 —— 证明这条播报/诊断通路在本场景里真的会响，
       否则 A 轮的"0 行"可能只是"根本没量到"
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
        'bow': 'id:"minecraft:bow",Count:1b',
        'elytra': 'id:"minecraft:elytra",Count:1b',
        'arrow': 'id:"minecraft:arrow",Count:64b',
        'firework': 'id:"minecraft:firework_rocket",Count:64b',
        'tnt': 'id:"minecraft:tnt",Count:8b',
        'flint': 'id:"minecraft:flint_and_steel",Count:1b',
        'maxhp_attr': 'generic.max_health',
    },
}
WAIT = 180
OBSERVE_A = 130
OBSERVE_B = 70
FAIL_PATTERNS = ('Mixin apply for mod promaid failed', 'InvalidInjectionException',
                 'MixinTransformerError', 'MixinApplyError', 'Failed to create brain',
                 'OutOfMemoryError')
FATAL_HINTS = ('promaid', 'mixin')

NAME = 'Off609Maid'
MISS_MARK = '缺可以飞行的道具'      # 本批新增的诊断行（与气泡同一处、同一节流）
TAKEOFF_MARKS = ('放烟花起飞', '挥羽扇起飞')
THROW_MARK = '投掷 TNT'

which = sys.argv[1] if len(sys.argv) > 1 else '1201'
RED_ONLY = len(sys.argv) > 2 and sys.argv[2] == 'red'
cfg = TARGETS.get(which)
if not cfg:
    print('usage: python test_offhand609.py [1201] [red]')
    sys.exit(2)

server = cfg['dir']
pid_file = os.path.join(server, 'server.pid')


def stop_server():
    if os.path.exists(pid_file):
        pid = open(pid_file).read().strip()
        subprocess.run(['taskkill', '/PID', pid, '/T', '/F'], capture_output=True)
        time.sleep(3)


def ensure_bombing_cfg():
    """把 [bombing] 段的 tnt / pose 钉成 true（两轮判据都靠它们；默认就是 true，防旧配置文件）。

    返回没改到的键名列表（空 = 全都到位）。
    """
    p = os.path.join(server, 'config', 'promaid-common.toml')
    if not os.path.exists(p):
        return ['<没有 config 文件>']
    raw = open(p, 'rb').read()
    try:
        t = raw.decode('utf-8')
    except Exception:
        t = raw.decode('gbk', errors='replace')
    bad = []
    for key in ('tnt', 'pose'):
        m = re.search(r'^(\s*%s\s*=\s*)(\w+)\s*$' % key, t, re.M)
        if not m:
            bad.append(key + '(缺)')
            continue
        if m.group(2) == 'true':
            continue
        t = t[:m.start()] + m.group(1) + 'true' + t[m.end():]
        bad.append(key + '(已改)')
    open(p, 'wb').write(t.encode('utf-8'))
    return bad


def run_round(suffix, offhand_fw, observe, log_path):
    """一轮：起服 → 搭场景 → 观测 → 停服 → 解析。offhand_fw=False = 副手不给烟花（阳性对照）"""
    log = open(log_path, 'wb')
    p = subprocess.Popen([cfg['java'], '-Xmx3G', '-Dfile.encoding=UTF-8'] + cfg['args'],
                         cwd=server, stdout=log, stderr=subprocess.STDOUT,
                         stdin=subprocess.PIPE,
                         creationflags=subprocess.CREATE_NEW_PROCESS_GROUP | 0x00000008)
    open(pid_file, 'w').write(str(p.pid))
    print('--- round %s: pid %d (offhand_fw=%s)' % (suffix, p.pid, offhand_fw))

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
            if MISS_MARK in t or THROW_MARK in t or '放烟花起飞' in t:
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
        # 清两轮各自的女仆 / 木桩（tag 精确匹配，两个后缀都点名）
        for _ in range(2):
            for t in ('off609maida', 'off609maidb', 'off609targeta', 'off609targetb'):
                send('kill @e[tag=%s]' % t)
            time.sleep(1)

        tag = 'off609maid' + suffix
        ttag = 'off609target' + suffix
        offhand = ('{%s}' % cfg['firework']) if offhand_fw else '{}'
        # 【场景要点】燃料（烟花）**只在副手**；背包里一枚烟花都没有 —— 这正是粉丝现场
        # （羽扇挂副手）。主手 bow + 背包箭 = 远程空袭三件套齐；TNT + 打火石 = 轰炸链路
        # （BombPose 就挂在这条链路上，"副手被借走"的窗口只能由它开）。
        maid_nbt = (
            '{MaidTask:"maid_smart:flight_ranged",MaidScheduleMode:"ALL",'
            'HandItems:[{%s},%s],'
            'ArmorItems:[{},{},{%s},{}],'
            'MaidInventory:{Size:36,Items:[{Slot:0b,%s},{Slot:1b,%s},{Slot:2b,%s}]},'
            'Owner:[I;1,2,3,4],Tags:["%s"],'
            'CustomName:"\\"%s\\"",PersistenceRequired:1b}'
        ) % (cfg['bow'], offhand, cfg['elytra'], cfg['tnt'], cfg['flint'], cfg['arrow'],
             tag, NAME + suffix)
        send('summon touhou_little_maid:maid ~ ~ ~1 %s' % maid_nbt)
        time.sleep(3)
        M = '@e[tag=%s,limit=1]' % tag
        # 木桩：她面前 6 格（远程空袭要有目标才会进入空袭链路）
        send('summon minecraft:zombie ~6 ~1 ~ {NoAI:1b,PersistenceRequired:1b,Health:2000f,'
             'Attributes:[{Name:"%s",Base:2000}],'
             'ActiveEffects:[{Id:11,Amplifier:9,Duration:999999}],Tags:["%s"],'
             'CustomName:"\\"%s\\""}' % (cfg['maxhp_attr'], ttag, 'Off609Target' + suffix))
        time.sleep(3)
        # 场景自检：任务 / 双手（副手那一格是不是烟花）/ 背包
        send('data get entity %s MaidTask' % M)
        send('data get entity %s HandItems' % M)
        send('data get entity %s MaidInventory' % M)
        time.sleep(2)
        print('observing for %ds ...' % observe)
        time.sleep(observe)
        send('data get entity %s HandItems' % M)
        send('data get entity %s MaidInventory' % M)
        time.sleep(3)

    data = readlog()
    for pat in FAIL_PATTERNS:
        if pat in data:
            notes.append('%s: FAIL(error line: %s)' % (suffix, pat))
    for l in data.splitlines():
        if 'FATAL' in l and any(h in l.lower() for h in FATAL_HINTS):
            notes.append('%s: FATAL %s' % (suffix, l[:140]))
            break

    who = NAME + suffix
    miss = [l for l in data.splitlines() if MISS_MARK in l and who in l]
    throws = [l for l in data.splitlines() if THROW_MARK in l and who in l]
    takeoff = [l for l in data.splitlines()
               if who in l and any(k in l for k in TAKEOFF_MARKS)]
    bubbles = [l for l in data.splitlines() if '空战装备不齐' in l and who in l]
    elytra_miss = [l for l in data.splitlines() if '缺鞘翅' in l and who in l]
    notes.append('%s: 缺燃料诊断 %d 行 / 气泡 %d 行 / 投掷 TNT %d 行 / 起飞 %d 行 / 缺鞘翅 %d 行'
                 % (suffix, len(miss), len(bubbles), len(throws), len(takeoff), len(elytra_miss)))
    for label, lst in (('缺燃料', miss), ('投掷', throws), ('起飞', takeoff)):
        if lst:
            notes.append('%s %s示例: %s' % (suffix, label, lst[0][-190:]))

    send('stop')
    for _ in range(40):
        time.sleep(1)
        if p.poll() is not None:
            break
    if p.poll() is None:
        subprocess.run(['taskkill', '/PID', str(p.pid), '/T', '/F'], capture_output=True)
    return {'notes': notes, 'data': data, 'miss': len(miss), 'throws': len(throws),
            'takeoff': len(takeoff), 'bubbles': len(bubbles)}


stop_server()
for old in [f for f in os.listdir(os.path.join(server, 'mods')) if f.startswith('promaid-')]:
    if old != cfg['modname']:
        os.remove(os.path.join(server, 'mods', old))
shutil.copyfile(cfg['jar'], os.path.join(server, 'mods', cfg['modname']))
print('jar copied:', cfg['modname'], os.path.getsize(cfg['jar']))
print('bombing cfg:', ensure_bombing_cfg() or 'tnt/pose 都已是 true')

verdict = 'PASS'
notes = []

# ── A 轮：燃料只在副手（= 粉丝现场）──
a = run_round('a', True, OBSERVE_A, os.path.join(server, 'console_off609_a.log'))
notes += a['notes']
if a['takeoff'] == 0:
    verdict = 'FAIL(A 轮她没起飞 —— 场景没搭起来（副手那枚烟花没被认成燃料？），后面的判据都不成立)'
elif a['throws'] == 0:
    verdict = 'FAIL(A 轮轰炸链路没起手 —— "副手被借走"的窗口没出现过，"0 行假提示"不能证明什么)'
elif a['miss'] > 0:
    verdict = 'FAIL(A 轮出现 %d 行「缺可以飞行的道具」—— 假提示还在（副手被借走那十几 tick 又算成缺件了）' % a['miss']
elif a['bubbles'] > 0:
    verdict = 'FAIL(A 轮出现 %d 行「空战装备不齐」气泡 —— 与诊断行不一致，说明还有别的路径在报' % a['bubbles']

if RED_ONLY:
    # 红轮：跑在**修之前**的 jar 上，期望假提示真的出现（证明这个脚本抓得住旧行为）
    if a['throws'] == 0:
        verdict = 'FAIL(红轮没收住：轰炸链路没起手，窗口没出现，红轮无效)'
    elif a['miss'] == 0 and a['bubbles'] == 0:
        verdict = 'FAIL(红轮没收住：副手被借走了却一条缺件提示都没有 —— 说明本场景复现不出该 bug)'
    else:
        verdict = 'RED-OK(假提示复现：诊断 %d 行 / 气泡 %d 行)' % (a['miss'], a['bubbles'])
    print('verdict:', verdict)
    for n in notes:
        print('note:', n)
    print('--- 关键日志行（A 轮）---')
    shown = 0
    for l in a['data'].splitlines():
        if any(k in l for k in (MISS_MARK, '空战装备不齐', THROW_MARK, '放烟花起飞',
                                'HandItems', 'MaidInventory', 'Summoned new', 'Mixin apply',
                                'FATAL')):
            print('  ', l[:230])
            shown += 1
            if shown > 50:
                print('   ...(truncated)')
                break
    if shown == 0:
        print('   (nothing matched)')
    sys.exit(0 if verdict.startswith('RED-OK') else 1)

# ── B 轮：阳性对照（副手也不给烟花）──
b = run_round('b', False, OBSERVE_B, os.path.join(server, 'console_off609_b.log'))
notes += b['notes']
if b['miss'] == 0 and b['bubbles'] == 0:
    verdict = 'FAIL(B 轮（阳性对照）一条缺件提示都没有 —— 这条播报通路在本场景里没响，A 轮的"0 行"不可信'

print('verdict:', verdict)
for n in notes:
    print('note:', n)
for name, r in (('A（燃料只在副手）', a), ('B（阳性对照）', b)):
    print('--- 关键日志行（%s）---' % name)
    shown = 0
    for l in r['data'].splitlines():
        if any(k in l for k in (MISS_MARK, '空战装备不齐', THROW_MARK, '放烟花起飞',
                                'HandItems', 'MaidInventory', 'Summoned new', 'Mixin apply',
                                'FATAL')):
            print('  ', l[:230])
            shown += 1
            if shown > 40:
                print('   ...(truncated)')
                break
    if shown == 0:
        print('   (nothing matched)')

sys.exit(0 if verdict == 'PASS' else 1)
