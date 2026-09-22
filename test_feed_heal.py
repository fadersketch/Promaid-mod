# -*- coding: utf-8 -*-
"""行为级回归：投喂回血是否与"女仆自己吃"同口径（实测五百四十五）。

【为什么必须单独一条 harness】
"女仆喂另一个女仆"发生在两个实体之间、不产生任何原版事件，而且互助链要求
"被喂的那只有主人、且主人在线"——专用服务器上没有玩家，这条链**结构上
无法端到端触发**（前两轮尝试都卡在这里，见 changelog 544 的脚手架记录）。
所以本次改动同时留了一条控制台可用的调试入口：

    /maid_smart feedtest <itemId>

它走的是与 `MaidAidOwnerBehavior.feedSisterFood` **完全相同的调用序列**
（eat 快照 → 原版 eat → MaidMealBridge.applySelfEatingEffect），
所以它能证明"这条路径在实机上确实按 TLM 餐食口径回血"。

【判据】
1. 必须出现 `按 TLM 餐食口径结算`（= Bridge 真的匹配上了 HEAL_MEAL 实现）；
2. 血量必须**真的上升**（胡萝卜 total=6.6 → heal(1.32)）；
3. 不许出现 mixin/FATAL 报错。

用法: python test_feed_heal.py [1201|neoforge1211]
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
        'jar': r'C:/Users/Sketch/.zcode/workspace/default/promaid-mod/patched/promaid-1.2.3.jar',
        'modname': 'promaid-1.2.3.jar',
        # 1.20.1：ArmorItems 顺序 脚/腿/胸/头；附魔写在 tag.Enchantments
        'maid_task': 'touhou_little_maid:attack',
    },
    'neoforge1211': {
        'dir': r'C:/Users/Sketch/mc_server_test/neoforge1211',
        'java': r'C:/Users/Sketch/AppData/Roaming/.minecraft/runtime/java-runtime-delta/bin/java.exe',
        'args': ['@user_jvm_args.txt',
                 '@libraries/net/neoforged/neoforge/21.1.250/win_args.txt', 'nogui'],
        'jar': r'C:/Users/Sketch/.zcode/workspace/default/promaid-mod/patched/promaid-1.2.3-neoforge-1.21.1.jar',
        'modname': 'promaid-1.2.3-neoforge-1.21.1.jar',
        'maid_task': 'touhou_little_maid:attack',
    },
}
WAIT = 180
FAIL_PATTERNS = ('Mixin apply for mod promaid failed', 'InvalidInjectionException',
                 'MixinTransformerError', 'MixinApplyError', 'Failed to create brain',
                 'OutOfMemoryError')
FATAL_HINTS = ('promaid', 'mixin')

which = sys.argv[1] if len(sys.argv) > 1 else '1201'
cfg = TARGETS.get(which)
if not cfg:
    print('usage: python test_feed_heal.py [1201|neoforge1211]')
    sys.exit(2)

server = cfg['dir']
pid_file = os.path.join(server, 'server.pid')
if os.path.exists(pid_file):
    pid = open(pid_file).read().strip()
    subprocess.run(['taskkill', '/PID', pid, '/T', '/F'], capture_output=True)
    time.sleep(3)

shutil.copyfile(cfg['jar'], os.path.join(server, 'mods', cfg['modname']))
print('jar copied:', cfg['modname'], os.path.getsize(cfg['jar']))

log_path = os.path.join(server, 'console_feedheal.log')
log = open(log_path, 'wb')
p = subprocess.Popen([cfg['java'], '-Xmx3G', '-Dfile.encoding=UTF-8'] + cfg['args'],
                     cwd=server, stdout=log, stderr=subprocess.STDOUT,
                     stdin=subprocess.PIPE,
                     creationflags=subprocess.CREATE_NEW_PROCESS_GROUP | 0x00000008)
open(pid_file, 'w').write(str(p.pid))
print('server started pid', p.pid)


def send(cmd):
    print('   >', cmd[:170])
    try:
        p.stdin.write((cmd + '\n').encode('utf-8'))
        p.stdin.flush()
    except Exception as e:
        print('   (stdin failed: %s)' % e)


def readlog():
    """编码无关读取（两版日志编码不同：1.20.1 UTF-8 / neo GBK 夹非法字节）。

    【必须 errors='replace'】neo 那份是 GBK 里夹少量非法字节，严格解码抛异常 → 候选被跳过。
    【哨兵不能带 ASCII】'Done (' 在任何编码下都命中，会提前选中 utf-8 让中文判据全假失败。
    """
    try:
        raw = open(log_path, 'rb').read()
    except Exception:
        return ''
    decoded = {enc: raw.decode(enc, 'replace')
               for enc in ('utf-8', 'gbk', 'cp936', 'latin-1')}
    for enc, t in decoded.items():
        if '投喂回血' in t or '餐食口径结算' in t:
            return t
    for enc in ('utf-8', 'gbk'):
        if 'Done (' in decoded[enc]:
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
    verdict = 'FAIL(server never reached DONE)'
else:
    send('gamerule doMobSpawning false')
    send('difficulty easy')
    # 清掉本 harness 往轮留下的命名实体（这个测试世界是持久化的）：
    # 【为什么必须清】前几轮 summon 的 FeedTestMaid/SpinTestMaid 会跨轮活下来，
    # 而且会有**已死未消解的尸体**（0 血）——按血量升序选目标时它们会抢先被选中，
    # 得到 0.00 → 0.00 的假失败（实测踩过两轮）。只按名字清，不动其它测试资产。
    send('kill @e[type=touhou_little_maid:maid,name=FeedTestMaid]')
    send('kill @e[type=touhou_little_maid:maid,name=SpinTestMaid]')
    send('kill @e[type=minecraft:zombie,name=SpinTestTarget]')
    # 残留尸体（0 血）不会被 kill 选中，但会随 chunk 卸载/时间自然消失；
    # 命令侧已加"只取存活"过滤，双保险。
    time.sleep(1)
    # 造一只攻击任务的女仆。
    # 【为什么不需要 tp】feedtest 在无执行者（控制台）时按"主世界出生点 ±64 格"找女仆，
    # 而这个世界是持久化的、留有上一轮的 SpinTestMaid 等残留。命令已改为**按血量升序**选，
    # 而下面会把本轮的 FeedTestMaid 打到 12 血 → 她必定是最低血、必定被选中
    # （neo 首轮撞上的正是"满血残留女仆 → heal() 被上限吃掉 → 假失败"）。
    send('summon touhou_little_maid:maid ~ ~2 ~ '
         '{MaidTask:"%s",MaidScheduleMode:"ALL",PersistenceRequired:1b,'
         'CustomName:"\\"FeedTestMaid\\""}' % cfg['maid_task'])
    time.sleep(3)
    # 【先把血打下去】不然满血 heal() 会被原版上限吃掉、看不到变化。
    # 用伤害指令把她打到半血以下（不致死：8 点伤害，女仆 20 血）。
    send('damage @e[type=touhou_little_maid:maid,name=FeedTestMaid,limit=1] 8 minecraft:generic')
    time.sleep(2)
    send('data get entity @e[type=touhou_little_maid:maid,name=FeedTestMaid,limit=1] Health')
    time.sleep(2)
    # 核心：走 feedtest（= feedSisterFood 的同一序列）。
    # 【不要套 execute at/run】那样执行者仍是控制台，`source.getEntity()`=null；
    # 命令本身已经支持"无执行者 → 退回主世界出生点附近找女仆"（专用服务器场景）。
    send('maid_smart feedtest minecraft:carrot')
    time.sleep(3)
    send('data get entity @e[type=touhou_little_maid:maid,name=FeedTestMaid,limit=1] Health')
    time.sleep(2)

    data = readlog()
    for pat in FAIL_PATTERNS:
        if pat in data:
            verdict = 'FAIL(error line: %s)' % pat
            notes.append(pat)
    for l in data.splitlines():
        if 'FATAL' in l and any(h in l.lower() for h in FATAL_HINTS):
            verdict = 'FAIL(FATAL involving promaid/mixin): %s' % l[:150]
            notes.append('FATAL:' + l[:110])
            break

    bridged = '按 TLM 餐食口径结算' in data
    notes.append('Bridge 命中 HEAL_MEAL=%s' % bridged)
    if not bridged and verdict == 'PASS':
        verdict = 'FAIL(没有出现 TLM 餐食口径结算 —— 投喂回血没生效)'

    # 解析 feedtest 打出的血量变化
    import re
    m = re.findall(r'血量 ([\d.]+) → ([\d.]+)', data)
    if m:
        before, after = float(m[-1][0]), float(m[-1][1])
        notes.append('血量 %.2f → %.2f（+%.2f）' % (before, after, after - before))
        if after <= before:
            if verdict == 'PASS':
                verdict = 'FAIL(血量没有上升：%.2f → %.2f)' % (before, after)
        else:
            notes.append('回血幅度 = %.2f（胡萝卜 total=6.6 → heal(1.32) 的 TLM 口径）'
                         % (after - before))
    else:
        notes.append('（没解析到"血量 x → y"行）')
        if verdict == 'PASS':
            verdict = 'FAIL(feedtest 没有输出血量变化 —— 命令没执行成功)'
    send('say FEEDHEAL_CHECK_DONE')

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
    if any(k in l for k in ('投喂回血', '血量', 'Summoned new', 'Mixin apply', 'FATAL',
                            'feedtest', '餐食')):
        print('  ', l[:230])
        shown += 1
        if shown > 40:
            print('   ...(truncated)')
            break
if shown == 0:
    print('   (nothing matched — inspect %s)' % log_path)
sys.exit(0 if verdict == 'PASS' else 1)
