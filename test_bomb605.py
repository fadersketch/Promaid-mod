# -*- coding: utf-8 -*-
"""实测六百〇五 的场景验证：认出来的 TNT 就放它自己那一枚 + 点火料按「类」认。

用法:
    python test_bomb605.py 1201          # 需要装等价交换（ProjectE）——本机是 1201 那台

三台女仆（同一套飞行战斗任务，各配一个悬空木桩）：
  A Bomb605Nova   : projecte:nova_catalyst ×2 + 打火石（**结构判据** + **放它自己那一枚**）
  B Bomb605Charge : minecraft:tnt ×3 + 火焰弹 ×3（原版那条路没被改坏 + 消耗品真被消耗）
  C Bomb605Stone  : 圆石 ×1 + 打火石（负向对照：方块不是 TntBlock、名字里也没 tnt）

判据（都在服务端日志 / data get 回显里）：
 ① A 有 `投掷 TNT` 行且点名 **projecte:nova_catalyst** —— 结构判据（方块继承 TntBlock）生效。
    旧判据（只认注册名里带 tnt）下她是 0 行：test_pe604.py 已实测记录过。
 ② 同一行里出现 **它自己那一枚 projecte:nova_catalyst_primed** —— 这一串是代码在点火钩子
    跑完之后扫出来的**真实实体注册名**，换句话说：ProjectE 自己的新星实体确实被放进了世界。
 ③ 同一行**不**含「模组方块放的是原版引信 TNT」—— 没有回落到原版那一枚。
 ④ 观察窗里抽样 `@e[type=projecte:nova_catalyst_primed]` 至少命中一次（`say NOVA_ALIVE`）
    —— 独立于我们日志的第二份证据。**为什么要先说清节奏**：上一轮实测发现控制台命令会被
    区块生成顶在后面积压十几秒（新星只活 2 秒 = 引信 40 tick），所以这里①先把女仆放在
    20 格外（超出 12 格索敌 = 一发都不扔）、②等世界稳定下来再开始观察窗、③窗内才把她们
    tp 到木桩旁边，抽样也降到 1.5 秒一轮（约 2 条命令/秒）。
 ⑤ B 有 `投掷 TNT` 行、点名 minecraft:tnt，且**不含**「它自己那一枚」（原版那条路一字未改）。
 ⑥ B 末次回读：火焰弹计数 < 3（消耗品整件消耗）、TNT 计数 < 3（真投出去了）。
 ⑦ C 一行都没有（负向对照：圆石是方块物品但不是 TntBlock）。
 ⑧ A 末次回读：打火石还在、且带 Damage / minecraft:damage ≥ 1（道具走耐久，不被吞）。
"""
import glob
import os
import re
import shutil
import subprocess
import sys
import time

sys.stdout.reconfigure(encoding='utf-8')

TARGETS = {
    # 等价交换只装在 1201 那台（neoforge1211 的 mods 里没有任何 TntBlock 子类，见 _tscan605.py）
    '1201': {
        'dir': r'C:/Users/Sketch/mc_server_test/1201',
        'java': r'C:/Users/Sketch/AppData/Roaming/.minecraft/runtime/java-runtime-beta/bin/java.exe',
        'args': ['@user_jvm_args.txt',
                 '@libraries/net/minecraftforge/forge/1.20.1-47.4.23/win_args.txt', 'nogui'],
        'jar': r'C:/Users/Sketch/.zcode/workspace/default/promaid-mod/patched/promaid-1.2.4.jar',
        'modname': 'promaid-1.2.4.jar',
        'bow': 'id:"minecraft:bow",Count:1b',
        'elytra': 'id:"minecraft:elytra",Count:1b',
        'arrow': 'id:"minecraft:arrow",Count:64b',
        'firework': 'id:"minecraft:firework_rocket",Count:64b',
        'novas': 'id:"projecte:nova_catalyst",Count:6b',
        'tnt': 'id:"minecraft:tnt",Count:4b',
        'charge': 'id:"minecraft:fire_charge",Count:4b',
        'stone': 'id:"minecraft:cobblestone",Count:1b',
        'flint': 'id:"minecraft:flint_and_steel",Count:1b',
        'maxhp_attr': 'generic.max_health',
    },
}
WAIT = 180
TICK = 90                 # 观察窗秒数
SAMPLE_EVERY = 1.5        # 抽样节奏（约 2 条命令/秒：太快会被控制台积压吃掉整个观察窗）
FAIL_PATTERNS = ('Mixin apply for mod promaid failed', 'InvalidInjectionException',
                 'MixinTransformerError', 'MixinApplyError', 'Failed to create brain',
                 'OutOfMemoryError', 'Ticking entity', 'Exception ticking')
NOVA_TYPE = 'projecte:nova_catalyst_primed'

NAME_A = 'Bomb605Nova'
NAME_B = 'Bomb605Charge'
NAME_C = 'Bomb605Stone'

which = sys.argv[1] if len(sys.argv) > 1 else '1201'
cfg = TARGETS.get(which)
if not cfg:
    print('usage: python test_bomb605.py [1201]')
    print('（neoforge1211 那台没装等价交换、也没有任何 TntBlock 子类，这台跑不了本用例）')
    sys.exit(2)

server = cfg['dir']
pid_file = os.path.join(server, 'server.pid')

if os.path.exists(pid_file):
    pid = open(pid_file).read().strip()
    subprocess.run(['taskkill', '/PID', pid, '/T', '/F'], capture_output=True)
    time.sleep(3)

mods_dir = os.path.join(server, 'mods')
for old in glob.glob(os.path.join(mods_dir, 'promaid-*.jar')):
    if os.path.basename(old) != cfg['modname']:
        os.remove(old)
        print('removed stale jar:', os.path.basename(old))
shutil.copyfile(cfg['jar'], os.path.join(mods_dir, cfg['modname']))
print('jar copied:', cfg['modname'], os.path.getsize(cfg['jar']))

log_path = os.path.join(server, 'console_bomb605.log')
log = open(log_path, 'wb')
p = subprocess.Popen([cfg['java'], '-Xmx3G', '-Dfile.encoding=UTF-8'] + cfg['args'],
                     cwd=server, stdout=log, stderr=subprocess.STDOUT,
                     stdin=subprocess.PIPE,
                     creationflags=subprocess.CREATE_NEW_PROCESS_GROUP | 0x00000008)
open(pid_file, 'w').write(str(p.pid))
print('server started pid', p.pid)


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
    decoded = {enc: raw.decode(enc, 'replace') for enc in ('utf-8', 'gbk', 'cp936', 'latin-1')}
    for enc, t in decoded.items():
        if '放烟花起飞' in t or '投掷 TNT' in t or '空袭轰炸' in t:
            return t
    for enc in ('utf-8', 'gbk'):
        if 'Done (' in decoded[enc] or 'Starting minecraft server' in decoded[enc]:
            return decoded[enc]
    return decoded['utf-8']


def counts_of(line):
    """从背包回读行里抽出 {物品注册名: 数量}（1.20.1 是 Count: 3b，1.21.1 是 count: 3）"""
    out = {}
    for m in re.finditer(r'id:\s*"([^"]+)"\s*,\s*[Cc]ount:\s*(\d+)', line):
        out[m.group(1)] = out.get(m.group(1), 0) + int(m.group(2))
    return out


done = False
for _ in range(WAIT):
    time.sleep(1)
    if p.poll() is not None:
        break
    if 'Done (' in readlog():
        done = True
        break

verdict = 'PASS'
notes = []
samples_nova = 0
samples_tnt = 0
if not done:
    verdict = 'FAIL(server never reached Done)'
else:
    send('gamerule doMobSpawning false')
    send('difficulty easy')
    # 【清理】世界是持久化的：清掉自己的残留，也顺手清掉前几轮（六百〇三 / 六百〇四 / PE604）的
    # 测试女仆与木桩——它们留在世界里会继续打架、继续扔炸弹（上一轮实测里 PE604Nova 就还在扔）。
    stale = ['bomb605maida', 'bomb605maidb', 'bomb605maidc',
             'bomb605targeta', 'bomb605targetb', 'bomb605targetc',
             'bomb604maida', 'bomb604maidb', 'bomb604targeta', 'bomb604targetb',
             'pe604nova', 'pe604tnt', 'pe604targeta', 'pe604targetb']
    for tag in stale:
        send('kill @e[tag=%s]' % tag)
    time.sleep(1)

    inv_a = ','.join('{Slot:%db,%s}' % (i, it) for i, it in enumerate([
        cfg['novas'], cfg['flint'], cfg['arrow'], cfg['firework'], cfg['firework']]))
    inv_b = ','.join('{Slot:%db,%s}' % (i, it) for i, it in enumerate([
        cfg['tnt'], cfg['charge'], cfg['arrow'], cfg['firework'], cfg['firework']]))
    inv_c = ','.join('{Slot:%db,%s}' % (i, it) for i, it in enumerate([
        cfg['stone'], cfg['flint'], cfg['arrow'], cfg['firework'], cfg['firework']]))

    def maid_nbt(name, tag, inv):
        return ('{MaidTask:"maid_smart:flight_ranged",MaidScheduleMode:"ALL",'
                'HandItems:[{%s},{}],'
                'ArmorItems:[{},{},{%s},{}],'
                'MaidInventory:{Size:36,Items:[%s]},'
                'Owner:[I;1,2,3,4],Tags:["%s"],'
                'CustomName:"\\"%s\\"",PersistenceRequired:1b}') % (cfg['bow'], cfg['elytra'], inv, tag, name)

    MAID_A = '@e[tag=bomb605maida,limit=1]'
    MAID_B = '@e[tag=bomb605maidb,limit=1]'
    MAID_C = '@e[tag=bomb605maidc,limit=1]'
    TA = '@e[tag=bomb605targeta,limit=1]'
    TB = '@e[tag=bomb605targetb,limit=1]'
    TC = '@e[tag=bomb605targetc,limit=1]'

    # 【为什么要挪到 30/40/50 格开外】A 扔的是等价交换的新星（威力 16、还会破坏地形）——
    # 落点在出生点附近就会给测试世界留一个十几格宽的大坑，后面几轮测试全在那上面打。
    # 【为什么木桩放在 20 格开外】超出 12 格索敌半径：她们在观察窗开始前**一发都不会扔**
    # （上一轮的教训：投掷全发生在窗口之前，整个观察窗什么都没看到）。
    send('summon touhou_little_maid:maid ~30 ~2 ~30 %s' % maid_nbt(NAME_A, 'bomb605maida', inv_a))
    time.sleep(2)
    send('summon touhou_little_maid:maid ~30 ~2 ~40 %s' % maid_nbt(NAME_B, 'bomb605maidb', inv_b))
    time.sleep(2)
    send('summon touhou_little_maid:maid ~30 ~2 ~50 %s' % maid_nbt(NAME_C, 'bomb605maidc', inv_c))
    time.sleep(3)
    # 木桩：NoAI（不还手不跑）+ NoGravity（悬空不落）+ 2000 血（整场打不死）
    for tag, name, pos in (('bomb605targeta', 'Bomb605TargetA', '~50 ~6 ~30'),
                           ('bomb605targetb', 'Bomb605TargetB', '~50 ~6 ~40'),
                           ('bomb605targetc', 'Bomb605TargetC', '~50 ~6 ~50')):
        send('summon minecraft:zombie %s {NoAI:1b,NoGravity:1b,PersistenceRequired:1b,'
             'Health:2000f,Attributes:[{Name:"%s",Base:2000}],'
             'ActiveEffects:[{Id:11,Amplifier:9,Duration:999999}],Tags:["%s"],'
             'CustomName:"\\"%s\\""}' % (pos, cfg['maxhp_attr'], tag, name))
        time.sleep(1)
    time.sleep(2)
    # 【脚手架自检】任务 / 归属 / 背包回读（NBT 写错时"不报错、只是没生效"，与功能没做一模一样）
    send('data get entity %s MaidTask' % MAID_A)
    send('data get entity %s MaidInventory' % MAID_A)
    send('data get entity %s MaidInventory' % MAID_B)
    send('data get entity %s MaidInventory' % MAID_C)
    time.sleep(3)
    # 【等世界稳定】新区块的生成会把控制台命令顶在后面十几秒（上一轮实测），先干等一轮。
    print('settling 20s (chunk gen) ...')
    time.sleep(20)

    print('observing for %ds ...' % TICK)
    steps = int(TICK / SAMPLE_EVERY)
    for i in range(steps):
        if i == 2:
            # 【观察窗内才把她们放过去】tp 到木桩旁边（target-relative，别用 @s——动的是命令执行者）
            for m, t in ((MAID_A, TA), (MAID_B, TB), (MAID_C, TC)):
                send('execute at %s run tp %s ~4 ~1 ~' % (t, m))
                time.sleep(0.5)
        time.sleep(SAMPLE_EVERY)
        # 【为什么要 say】抽样命令的回显里没有选择器文本（"Test passed" 分不出是哪一条），
        # 所以用 run say <标记>：标记出现 = 那一类实体确实在世界上活着。
        send('execute if entity @e[type=%s] run say NOVA_ALIVE' % NOVA_TYPE)
        send('execute if entity @e[type=minecraft:tnt] run say TNT_ALIVE')
        send('data get entity @e[type=%s,limit=1] Fuse' % NOVA_TYPE)
    for m in (MAID_A, MAID_B, MAID_C):
        send('data get entity %s MaidInventory' % m)
        time.sleep(0.5)
    time.sleep(3)

    data = readlog()
    for pat in FAIL_PATTERNS:
        if pat in data:
            verdict = 'FAIL(error line: %s)' % pat
            notes.append(pat)

    # ── 抽样统计：NOVA_ALIVE / TNT_ALIVE / 引信回读（每样各一行）──
    samples_nova = sum(1 for l in data.splitlines() if 'NOVA_ALIVE' in l)
    samples_tnt = sum(1 for l in data.splitlines() if 'TNT_ALIVE' in l)
    fuse_lines = [l for l in data.splitlines()
                  if re.search(r'entity data: \d+s\s*$', l) and 'No entity' not in l]
    notes.append('观察窗抽样（%.1fs × %ds）：NOVA_ALIVE %d 次 / TNT_ALIVE %d 次 / 引信回读 %d 行'
                 % (SAMPLE_EVERY, TICK, samples_nova, samples_tnt, len(fuse_lines)))
    if fuse_lines:
        notes.append('新星实体引信回读示例: %s' % fuse_lines[0][:150])
    if samples_nova == 0:
        notes.append('警告：抽样一次都没抓到新星实体（引信 2 秒很短 + 控制台可能积压）'
                     '——不过上面那条投掷行本身就点名了它自己的实体注册名，下表也没抓到就另说')
    if samples_nova == 0 and samples_tnt == 0 and not fuse_lines:
        verdict = 'FAIL(两类实体都没抽到过 —— 抽样链路本身没生效，观察窗不算数)'
    elif samples_tnt == 0 and not fuse_lines:
        notes.append('警告：抽样没抓到原版 TNT 实体（B 那几发引信也只有 2 秒）')

    # ── ① ② ③ A 的投掷行 ──
    tnt_lines = [l for l in data.splitlines() if '投掷 TNT' in l]
    line_a = [l for l in tnt_lines if NAME_A in l]
    line_b = [l for l in tnt_lines if NAME_B in l]
    line_c = [l for l in tnt_lines if NAME_C in l]
    ok_a_item = [l for l in line_a if 'projecte:nova_catalyst' in l]
    ok_a_mine = [l for l in line_a if '它自己那一枚' in l and NOVA_TYPE in l]
    bad_a_fallback = [l for l in line_a if '模组方块放的是原版引信 TNT' in l]
    notes.append('投掷行 A=%d（点名 nova_catalyst %d，点名它自己那一枚 %d）/ B=%d / C=%d'
                 % (len(line_a), len(ok_a_item), len(ok_a_mine), len(line_b), len(line_c)))
    if line_a:
        notes.append('A 投掷行示例: %s' % line_a[-1][:240])
    if line_b:
        notes.append('B 投掷行示例: %s' % line_b[-1][:240])
    if not line_a:
        verdict = 'FAIL(新星没触发投掷 —— 结构判据没生效)'
    elif not ok_a_item:
        verdict = 'FAIL(投掷行里没点名 projecte:nova_catalyst —— 扔的可能是别的物品)'
    elif not ok_a_mine:
        verdict = 'FAIL(投掷行里没说放出来的是它自己那一枚 —— 放的可能还是原版 TNT)'
    elif bad_a_fallback:
        verdict = 'FAIL(新星这一发回落成了原版引信 TNT：%s)' % bad_a_fallback[0][:200]
    if line_c:
        verdict = 'FAIL(负向对照（圆石）也扔了 —— 判据放宽过头了：%s)' % line_c[0][:160]

    # ── ⑤ B：原版那条路 ──
    if not line_b:
        verdict = 'FAIL(B 那条原版 TNT 一发都没扔 —— 原版那条路被改坏了)'
    else:
        ok_b_item = [l for l in line_b if 'minecraft:tnt' in l]
        bad_b_hook = [l for l in line_b if '它自己那一枚' in l]
        if not ok_b_item:
            verdict = 'FAIL(B 的投掷行里没点名 minecraft:tnt)'
        elif bad_b_hook:
            verdict = 'FAIL(原版 minecraft:tnt 也走了模组钩子：%s)' % bad_b_hook[0][:200]

    # ── ⑥ ⑦ ⑧ 背包回读（只认"最后一次"那一行，且按名字挑行）──
    def last_inv(name):
        ls = [l for l in data.splitlines()
              if name in l and ('Size: 36' in l or 'size: 36' in l)]
        return ls[-1] if ls else ''

    inv_a_last = last_inv(NAME_A)
    inv_b_last = last_inv(NAME_B)
    inv_c_last = last_inv(NAME_C)
    notes.append('A 末次回读: %s' % inv_a_last[:230])
    notes.append('B 末次回读: %s' % inv_b_last[:230])
    if not inv_a_last:
        verdict = 'FAIL(没有回读到 A 的背包 —— 脚手架没生效，消耗/耐久这两条没被验证)'
    elif not inv_b_last:
        verdict = 'FAIL(没有回读到 B 的背包 —— 脚手架没生效)'
    else:
        ca = counts_of(inv_a_last)
        cb = counts_of(inv_b_last)
        # ⑧ A：打火石还在、且被扣过耐久（道具走耐久，不被吞）
        if 'minecraft:flint_and_steel' not in ca:
            verdict = 'FAIL(A 的打火石不见了 —— 道具被吞了)'
        elif not re.search(r'(Damage|minecraft:damage)"?\s*:\s*[1-9]', inv_a_last):
            verdict = 'FAIL(A 的打火石没被扣耐久（Damage 仍是 0）—— 道具没走耐久机制)'
        if ca.get('projecte:nova_catalyst', 0) > 0:
            notes.append('提示：A 还剩 %d 个新星（她没投完：投掷有 200 tick 最短间隔）'
                         % ca['projecte:nova_catalyst'])
        # ⑥ B：火焰弹被消耗（消耗品整件消耗）+ TNT 被消耗（数量 ≤ 初始值-1；投光了就是"没有这一格"）
        if cb.get('minecraft:tnt', 0) >= 4:
            verdict = 'FAIL(B 的 TNT 数量没减少 —— 那一发没被消耗)'
        elif 'minecraft:tnt' in cb:
            notes.append('提示：B 还剩 %d 个 TNT' % cb['minecraft:tnt'])
        if cb.get('minecraft:fire_charge', 0) >= 4:
            verdict = 'FAIL(B 的火焰弹还是 4 个 —— 消耗品没被消耗)'
        elif 'minecraft:fire_charge' in cb:
            notes.append('提示：B 还剩 %d 个火焰弹（引信 2 秒 × 投掷间隔，没投完正常）'
                         % cb['minecraft:fire_charge'])

    if not line_a and not line_b and not line_c:
        notes.append('（一条投掷行都没有——先看上面的脚手架自检回读）')

send('say BOMB605_CHECK_DONE')
time.sleep(2)
send('stop')
for _ in range(40):
    time.sleep(1)
    if p.poll() is not None:
        break
if p.poll() is None:
    subprocess.run(['taskkill', '/PID', str(p.pid), '/T', '/F'], capture_output=True)

data = readlog()
print('verdict:', verdict)
for n in notes:
    print('note:', n)
print('--- relevant log lines ---')
shown = 0
for l in data.splitlines():
    if any(k in l for k in ('投掷 TNT', '空袭轰炸', '放烟花起飞', 'Summoned new', 'MaidInventory',
                            'MaidTask', 'flight_ranged', 'Fuse', 'NOVA_ALIVE', 'TNT_ALIVE',
                            'Primed Nova', 'Mixin apply', 'FATAL',
                            'ArgumentException', 'Unknown')):
        print('  ', l[:240])
        shown += 1
        if shown > 80:
            print('   ...(truncated)')
            break
if shown == 0:
    print('   (nothing matched — inspect %s)' % log_path)

sys.exit(0 if verdict == 'PASS' else 1)
