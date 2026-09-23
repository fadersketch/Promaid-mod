# -*- coding: utf-8 -*-
"""功能回归：激流旋转冲击的**空中**那一记（实测五百四十四）。

为什么必须另开一个 harness：test_maid_load.py 只证明"女仆能被召唤出来、mixin 没炸"；
test_server.py 只等 `Done (`。这两条**结构上**都覆盖不到本次改动 —— 而本次改的恰恰是
实测五百四十一 出过事的链路（"突然不会起飞了"）。所以这条回归直接测行为：

  1. 起服务器 → Done；
  2. summon 一个**空袭任务**的女仆（NBT 指定 MaidTask），主手给**激流三叉戟**，
     背包塞鞘翅 + 烟花火箭（三件套齐 → 模式激活）；
  3. 她附近 summon 一只怪 → 触发空袭链路；
  4. 等待 TICK 秒，读日志：
     - 必须出现「放烟花起飞」= 起飞没被破坏（541 的护栏还在）；
     - 必须出现「旋转冲击（空中起手」= 本次新放行的窗口真的触发了；
     - 不许出现 mixin/FATAL 错误。
  5. stop。

用法: python test_spin_air.py [1201|neoforge1211]
"""
import os
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
        'task': 'maid_smart:flight_combat',
        # 1.20.1：附魔写在物品的 tag.Enchantments（老格式）。
        # 【只写键值体、不写外层花括号】由调用处包一层 —— 因为同一个物品既要塞进
        # HandItems:[{…}] / ArmorItems:[{…}]，也要塞进 MaidInventory 的
        # {Slot:0b,…} 里。首轮 neo 实测就是这里拼成了 `{Slot:0b,{id:…}}`（双层括号）
        # → 整条 summon 的 NBT 解析失败 → 女仆根本没生成，看起来却像"功能没生效"。
        'trident': 'id:"minecraft:trident",Count:1b,tag:{Enchantments:[{id:"minecraft:riptide",lvl:3s}]}',
        'elytra': 'id:"minecraft:elytra",Count:1b',
        'firework': 'id:"minecraft:firework_rocket",Count:64b',
        'maxhp_attr': 'generic.max_health',
    },
    'neoforge1211': {
        'dir': r'C:/Users/Sketch/mc_server_test/neoforge1211',
        'java': r'C:/Users/Sketch/AppData/Roaming/.minecraft/runtime/java-runtime-delta/bin/java.exe',
        'args': ['@user_jvm_args.txt',
                 '@libraries/net/neoforged/neoforge/21.1.250/win_args.txt', 'nogui'],
        'jar': r'C:/Users/Sketch/.zcode/workspace/default/promaid-mod/patched/promaid-1.2.4-neoforge-1.21.1.jar',
        'modname': 'promaid-1.2.4-neoforge-1.21.1.jar',
        'task': 'maid_smart:flight_combat',
        # 1.21.1：附魔是**数据组件**，而且 `ItemEnchantments.CODEC` 是
        #   `FULL_CODEC = RecordCodecBuilder…LEVELS_CODEC.fieldOf("levels")`（带
        #   `withAlternative(FULL_CODEC, LEVELS_CODEC, …)`）——也就是**优先认
        #   `{"levels":{"minecraft:riptide":3}}`** 这种带 levels 层的形式。
        # 【实测踩过两道】①照抄 1.20.1 的 `tag:{Enchantments:[…]}` 会被数据修复器直接丢掉；
        #   ②写成不带 levels 的扁平形式虽然理论上走 alternative 分支，但实测没生效
        #   （三叉戟没有激流 → replacesMelee 恒 false → 空中旋转永不触发，现象像"功能没做"）。
        'trident': 'id:"minecraft:trident",count:1,components:{"minecraft:enchantments":{levels:{"minecraft:riptide":3}}}',
        'elytra': 'id:"minecraft:elytra",count:1',
        'firework': 'id:"minecraft:firework_rocket",count:64',
        'maxhp_attr': 'minecraft:generic.max_health',
    },
}
WAIT = 180
TICK = 45           # 观察窗（秒）——够走完 起飞→爬升→俯冲→至少一次空袭近战
FAIL_PATTERNS = ('Mixin apply for mod promaid failed', 'InvalidInjectionException',
                 'MixinTransformerError', 'MixinApplyError', 'Failed to create brain',
                 'OutOfMemoryError')
# 裸 'FATAL' 不能直接当失败判据：本测试服装着 ProjectE，它自己的 UUID Checker 线程
# 每次启动都会打一条与本模组无关的 FATAL（实测踩过）。只认"行内同时提到 promaid/mixin"的。
FATAL_HINTS = ('promaid', 'mixin')

which = sys.argv[1] if len(sys.argv) > 1 else '1201'
cfg = TARGETS.get(which)
if not cfg:
    print('usage: python test_spin_air.py [1201|neoforge1211]')
    sys.exit(2)

server = cfg['dir']
pid_file = os.path.join(server, 'server.pid')
if os.path.exists(pid_file):
    pid = open(pid_file).read().strip()
    subprocess.run(['taskkill', '/PID', pid, '/T', '/F'], capture_output=True)
    time.sleep(3)

shutil.copyfile(cfg['jar'], os.path.join(server, 'mods', cfg['modname']))
print('jar copied:', cfg['modname'], os.path.getsize(cfg['jar']))

log_path = os.path.join(server, 'console_spinair.log')
log = open(log_path, 'wb')
p = subprocess.Popen([cfg['java'], '-Xmx3G', '-Dfile.encoding=UTF-8'] + cfg['args'],
                     cwd=server, stdout=log, stderr=subprocess.STDOUT,
                     stdin=subprocess.PIPE,
                     creationflags=subprocess.CREATE_NEW_PROCESS_GROUP | 0x00000008)
open(pid_file, 'w').write(str(p.pid))
print('server started pid', p.pid)


def send(cmd):
    print('   >', cmd[:180])
    try:
        p.stdin.write((cmd + '\n').encode('utf-8'))
        p.stdin.flush()
    except Exception as e:
        print('   (stdin failed: %s)' % e)


def readlog():
    """读日志并按 **编码无关** 的方式还原成文本。

    【为什么不能直接 utf-8】两版服务端 JVM 写出 stdout 的编码不一样：1.20.1 是 UTF-8，
    NeoForge 那台是 GBK（即使命令行传了 -Dfile.encoding=UTF-8）。用单一编码读，另一台就会
    整段中文变乱码 → 本 harness 里所有中文判据（"放烟花起飞" 等）全部假失败，
    而日志里那些事其实都发生了（实测踩过：neo 首轮"没起飞"其实是编码问题）。
    这里按 utf-8 → gbk → cp936 → latin-1 依次试，取**能解出哨兵中文**的那一次。
    【必须 errors='replace'】neo 那台的日志是 GBK 里夹着少量非法字节（模组混打的 UTF-8），
    严格解码会直接抛 UnicodeDecodeError —— 那样整个候选都被跳过、判据永远不成立。
    【为什么哨兵里不能带 'Done ('】它是 ASCII，在任何编码下都会命中 —— 第一轮候选 utf-8
    就会因此被选中，neo 那份 GBK 日志的中文全是乱码，所有中文判据假失败（实测又踩一次：
    现象是"起飞=False"而日志里明明有"放烟花起飞"）。所以只在**中文哨兵**里挑编码。
    """
    try:
        raw = open(log_path, 'rb').read()
    except Exception:
        return ''
    decoded = {enc: raw.decode(enc, 'replace')
               for enc in ('utf-8', 'gbk', 'cp936', 'latin-1')}
    for enc, t in decoded.items():
        if '放烟花起飞' in t or '旋转冲击' in t:
            return t
    # 还没有任何中文输出（比如还在启动）：挑一个 ASCII 能读的就行
    for enc in ('utf-8', 'gbk'):
        if 'Done (' in decoded[enc] or 'Starting minecraft server' in decoded[enc]:
            return decoded[enc]
    return decoded['utf-8']


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
if not done:
    verdict = 'FAIL(server never reached Done)'
else:
    # 和平模式：避免她被怪打死/怪打她造成噪声（我们只测"能不能起飞 + 能不能转"）
    send('gamerule doMobSpawning false')
    send('difficulty easy')
    # 【只清自己的残留】这个测试世界是持久化的，前几轮 summon 出来的 SpinTestMaid/SpinTestTarget
    # 会跨轮活下来。实测踩过：`@e[…,limit=1]` 选中了**别的**女仆 → ①data get 回读到 {}
    # （误判"没有激流附魔"）②日志里的"放烟花起飞"可能来自上一轮的女仆，归属不清。
    # 只按我们自己的名字清，**不动**世界里其它女仆（那是别人的测试资产）。
    send('kill @e[type=touhou_little_maid:maid,name=SpinTestMaid]')
    send('kill @e[type=minecraft:zombie,name=SpinTestTarget]')
    time.sleep(1)
    # 女仆：空袭任务 + 主手激流三叉戟(III) + 背包鞘翅/烟花
    #   【ArmorItems 顺序】1.20.1 是 脚/腿/胸/头 → 鞘翅必须放【索引 2】。首轮实测把它写到了
    #   索引 3（头盔），虽然 equip() 会从背包自己穿，但这个坑不该留着误导后续排查。
    maid_nbt = (
        '{MaidTask:"%s",MaidScheduleMode:"ALL",'
        'HandItems:[{%s},{}],'
        'ArmorItems:[{},{},{%s},{}],'
        'MaidInventory:{Size:36,Items:['
        '{Slot:0b,%s},'
        '{Slot:1b,%s}'
        ']},'
        'CustomName:"\\"SpinTestMaid\\"",PersistenceRequired:1b}'
    ) % (cfg['task'], cfg['trident'], cfg['elytra'], cfg['firework'], cfg['elytra'])
    send('summon touhou_little_maid:maid ~ ~2 ~ %s' % maid_nbt)
    time.sleep(3)
    # 目标木桩：NoAI（不还手、不跑）+ 抗性（打不死，链路能一直循环）
    #   【为什么 NoAI】首轮实测她**没起飞**，日志给的答案是"我方有视线=0"——不是机制问题，
    #   是测试场地（世界出生点的地形/树挡住了视线），而空袭的出手前必过 hasSight（实测五百二十七）。
    #   所以这里把木桩**贴到她身边 3 格**，视线必然通；同时 NoAI 让这一场只测我们要测的东西。
    #
    #   【为什么抗性 V 还不够】第二轮 neo 实测：木桩被**普通战术的跳劈** 15 点当场秒了
    #   （`jump dash crit dmg=15.0`），空袭链路根本还没轮上——抗性 V 只减 100%，
    #   而跳劈那一记走的是本模组自己的结算（不走原版减伤），所以这里直接把血堆到 2000，
    #   让它在整个观察窗里都打不死。
    send('summon minecraft:zombie ~8 ~ ~ {NoAI:1b,PersistenceRequired:1b,'
         'Health:2000f,Attributes:[{Name:"%s",Base:2000}],'
         'ActiveEffects:[{Id:11,Amplifier:9,Duration:999999}],'
         'CustomName:"\\"SpinTestTarget\\""}' % cfg['maxhp_attr'])
    time.sleep(2)
    send('execute at @e[type=touhou_little_maid:maid,name=SpinTestMaid,limit=1] run '
         'tp @e[type=minecraft:zombie,name=SpinTestTarget,limit=1] ~3 ~ ~')
    time.sleep(2)
    # 【脚手架自检】把女仆手上的三叉戟回读成 NBT —— 用来确认"激流附魔真的写进去了"。
    # 为什么必须自检：两版的附魔 NBT 格式完全不同（1.20.1 是 tag.Enchantments，
    # 1.21.1 是 components.minecraft:enchantments.levels），写错时**不报错、只是没有附魔**，
    # 现象与"功能没生效"一模一样 —— 我已经在这上面误判过两轮 neo。
    send('data get entity @e[type=touhou_little_maid:maid,name=SpinTestMaid,limit=1] HandItems[0]')
    time.sleep(2)
    print('observing for %ds ...' % TICK)
    time.sleep(TICK)

    data = readlog()
    for pat in FAIL_PATTERNS:
        if pat in data:
            verdict = 'FAIL(error line: %s)' % pat
            notes.append(pat)
    for l in data.splitlines():
        if 'FATAL' in l and any(h in l.lower() for h in FATAL_HINTS):
            verdict = 'FAIL(FATAL involving promaid/mixin): %s' % l[:160]
            notes.append('FATAL:' + l[:120])
            break

    launched = '放烟花起飞' in data
    air_spin = '旋转冲击（空中起手' in data
    ground_spin = '旋转冲击（地面起手' in data
    if not launched:
        verdict = 'FAIL(她没起飞 —— 疑似 541 的"不会起飞"回归)'
    if not air_spin:
        if verdict == 'PASS':
            verdict = 'FAIL(没有出现"空中起手"的旋转冲击 —— 新窗口没触发)'
    notes.append('起飞=%s 空中旋转=%s 地面旋转=%s' % (launched, air_spin, ground_spin))
    # 附魔回读（脚手架自检）：日志里 `data get` 的回显含 riptide 才算"激流真的在武器上"
    ench_ok = 'riptide' in data.lower()
    notes.append('武器上读到 riptide=%s' % ench_ok)
    if not ench_ok and verdict == 'PASS':
        verdict = 'FAIL(三叉戟上没有激流附魔 —— 测试脚手架的 NBT 格式写错了)'
    send('say SPINAIR_CHECK_DONE')

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
    if any(k in l for k in ('放烟花起飞', '旋转冲击', '激流突进', 'Mixin apply', 'FATAL',
                            '飞行作战', '空战装备不齐', 'Summoned new')):
        print('  ', l[:230])
        shown += 1
        if shown > 60:
            print('   ...(truncated)')
            break
if shown == 0:
    print('   (nothing matched — inspect %s)' % log_path)
sys.exit(0 if verdict == 'PASS' else 1)
