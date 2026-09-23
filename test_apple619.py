# -*- coding: utf-8 -*-
"""实测六百一十九（其一）金苹果：着火时她会不会把整叠金苹果一口气吃光。

用法:
    python test_apple619.py [1201|neoforge1211]

反馈原文（GitHub issue #17）:
    "[1.2.2] 女仆着火时会把背包里的金苹果瞬间全部吃完（eatGoldenAppleForFire 的冷却对
     普通食物失效）…… 给她塞一组金苹果，点燃她，几秒后背包里一个不剩"

【这条用例要的是"行为面"证据，不是内部状态】把一整叠金苹果放进她的背包、点着她，
然后**只数背包里还剩几个**：
  · 修好前：几秒内归零（用户报的现象）；
  · 修好后：只吃掉 1 个（冷却按它自己给的效果时长记，2 分钟内不再吃），剩下的 15 个还在。
对照组（同一套命令、同一段时间）是**旁边一只同样揣着 16 个金苹果但不点着的女仆**
—— 她的 16 个必须一个不少，用来证明"数出来的就是背包里真实的数量、也没被别的东西吃掉"。

【她会不会真的被点着】`doFireTick false` 让火方块不灭也不蔓延，测试期间每 3 秒在她
脚下补一次火（她会走位，火方块留在原地就烧不到她了——第一版就是这么"没着火"的）。
血量给 200：火每秒 1 点，20 秒的观察窗口打不死她（不给就"她死了→背包清零"，
看着像 bug 其实不是）。
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
    },
    'neoforge1211': {
        'dir': r'C:/Users/Sketch/mc_server_test/neoforge1211',
        'java': r'C:/Users/Sketch/AppData/Roaming/.minecraft/runtime/java-runtime-delta/bin/java.exe',
        'args': ['@user_jvm_args.txt',
                 '@libraries/net/neoforged/neoforge/21.1.250/win_args.txt', 'nogui'],
        'jar': r'C:/Users/Sketch/.zcode/workspace/default/promaid-mod/patched/'
               r'promaid-1.2.4-neoforge-1.21.1.jar',
        'modname': 'promaid-1.2.4-neoforge-1.21.1.jar',
    },
}

WAIT = 240
FAIL_PATTERNS = ('Mixin apply for mod promaid failed', 'InvalidInjectionException',
                 'MixinTransformerError', 'MixinApplyError', 'Failed to create brain',
                 'OutOfMemoryError', 'NoSuchMethodError', 'NoClassDefFoundError')

RUN_TAG = '619'
APPLES = 16          # 放进她背包的金苹果数量（本用例自己写的，是硬判据）
WINDOW = 12          # 观察窗口（秒）——修好前 16 个在 1 秒内就没了，12 秒足够看清两边
IGNITE_EVERY = 3     # 每几秒给她脚下补一把火
STRICT = True        # 两台服都当硬判据（吃的这条路与平台无关）

which = sys.argv[1] if len(sys.argv) > 1 else '1201'
cfg = TARGETS.get(which)
if not cfg:
    print('usage: python test_apple619.py [1201|neoforge1211] [jar路径]')
    sys.exit(2)
if len(sys.argv) > 2:
    # 可选的第三个参数：指定用哪个 jar（复现"修之前"那版时用——本仓的 patched/ 每次构建都覆盖）
    cfg = dict(cfg)
    cfg['jar'] = sys.argv[2]
    print('用指定的 jar:', cfg['jar'])

server = cfg['dir']
pid_file = os.path.join(server, 'server.pid')


def stop_server():
    if os.path.exists(pid_file):
        pid = open(pid_file).read().strip()
        subprocess.run(['taskkill', '/PID', pid, '/T', '/F'], capture_output=True)
        time.sleep(3)


CONFIG = os.path.join(server, 'config', 'promaid-common.toml')
# TLM 自己的三张"餐食黑名单"——测试期间把金苹果塞进去。
# 【为什么必须这么做】TLM 的进餐任务（MaidHealSelfTask / MaidWorkMealTask /
# MaidHomeMealTask，javap 实证都调 setItemInHand）会把背包里那**一整叠**食物拿到主手
# 再吃（实测：召出来 2 秒内 16 个金苹果就整叠跑到主手、还少了一个）——而
# `eatGoldenAppleForFire` 只看背包（getMaidInv，36 格，不含手）。两条路混在一起，
# "背包里还剩几个"就说明不了任何事。把金苹果从 TLM 的餐食里禁掉之后，**唯一的消费者
# 就是我们要验的那条着火链路**。测完原样还原（别的用例还指望默认配置）。
TLM_CONFIG = os.path.join(server, 'config', 'touhou_little_maid-common.toml')
TLM_LISTS = ('MaidWorkMealsBlockList', 'MaidHomeMealsBlockList', 'MaidHealMealsBlockList')
_tlm_backup = None


def block_golden_apple_in_tlm_meals():
    """把 minecraft:golden_apple 加进 TLM 三张餐食黑名单（返回是否动过文件）

    只动**精确等于**这三个键的行——TLM 配置里还有 MaidWorkMealsBlockListRegEx 这类
    前缀相同的邻居，第一版按前缀匹配把它们也改了（那些是正则表，塞进去的是非法 TOML），
    服务端直接起不来。
    """
    global _tlm_backup
    if not os.path.exists(TLM_CONFIG):
        print('   (没有 TLM 配置文件 %s，跳过)' % TLM_CONFIG)
        return False
    _tlm_backup = open(TLM_CONFIG, 'rb').read()
    text = _tlm_backup.decode('utf-8', 'replace')
    pat = re.compile(r'^(\s*)(%s)\s*=\s*\[(.*)\]\s*$' % '|'.join(TLM_LISTS))
    out = []
    for line in text.split('\n'):
        m = pat.match(line)
        if m and 'golden_apple' not in m.group(3):
            body = m.group(3).rstrip().rstrip(',')
            line = '%s%s = [%s, "minecraft:golden_apple", "minecraft:enchanted_golden_apple"]' % (
                m.group(1), m.group(2), body)
        out.append(line)
    open(TLM_CONFIG, 'wb').write('\n'.join(out).encode('utf-8'))
    print('TLM 餐食黑名单已加金苹果:')
    for line in out:
        if pat.match(line):
            print('   ', line.strip()[:170])
    return True


def restore_tlm_config():
    if _tlm_backup is not None:
        open(TLM_CONFIG, 'wb').write(_tlm_backup)
        print('TLM 配置已还原')


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
# 自保行为必须是开的（吃金苹果这条路挂在自保的着火分支里）
print('config:', set_section('combat', selfPreserve='true'))
block_golden_apple_in_tlm_meals()
mods_dir = os.path.join(server, 'mods')
for old in os.listdir(mods_dir):
    if old.startswith('promaid-') and old != cfg['modname']:
        os.remove(os.path.join(mods_dir, old))
shutil.copyfile(cfg['jar'], os.path.join(mods_dir, cfg['modname']))
print('jar copied:', cfg['modname'], os.path.getsize(cfg['jar']), '(md5 %s)'
      % __import__('hashlib').md5(open(cfg['jar'], 'rb').read()).hexdigest()[:12])

log_path = os.path.join(server, 'console_apple619.log')
plog = os.path.join(server, 'logs', 'promaid.log')
_plog0 = os.path.getsize(plog) if os.path.exists(plog) else 0
log = open(log_path, 'wb')
p = subprocess.Popen([cfg['java'], '-Xmx3G', '-Dfile.encoding=UTF-8'] + cfg['args'],
                     cwd=server, stdout=log, stderr=subprocess.STDOUT,
                     stdin=subprocess.PIPE,
                     creationflags=subprocess.CREATE_NEW_PROCESS_GROUP | 0x00000008)
open(pid_file, 'w').write(str(p.pid))
print('server pid', p.pid)


def send(cmd, wait=True, timeout=60, tag=None):
    """发一条命令并等它跑完（等的是命令自己的日志，控制台不回显——见 test_box618 的说明）。
    这里多数是 data get / setblock，不打印日志，所以 tag=None 时给一觉。"""
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


done = False
for _ in range(WAIT):
    time.sleep(1)
    if p.poll() is not None:
        break
    if 'Done (' in _read(log_path):
        done = True
        break

# 每次采样都从"命令执行之后新写出来的那段控制台日志"里找答案：data get 的回显在控制台
# （不是 promaid.log），所以这里单独按偏移切段读。
_c0 = 0
notes = []
if not done:
    notes.append('服务端没到 Done（启动失败）')
else:
    print('server done, settling ...')
    print('   settled:', settle())

    def console_new():
        """本次 run 之后控制台日志的新增部分"""
        return _read(log_path, _c0)

    def marker():
        _c0 = os.path.getsize(log_path)
        return _c0

    A_TAG = 'apple619ignite' + RUN_TAG
    B_TAG = 'apple619control' + RUN_TAG
    MA_TAG = 'apple619maid' + RUN_TAG
    MB_TAG = 'apple619ctl' + RUN_TAG
    ANCHOR = 'apple619anchor' + RUN_TAG
    send('gamerule doMobSpawning false')
    send('gamerule doFireTick false')     # 火不灭也不蔓延：测试期间她一直烧着
    send('gamerule doWeatherCycle false')
    # 【必须把天锁成晴】下雨会**当场把她身上的火浇灭**（原版 `Entity.tick`：头顶下雨就灭火，
    # 连带把 fire 方块冲掉）——实测六百一十九在 NeoForge 那台上就是这么"点了两次火、
    # Fire 全程 0"的（同一套命令在 1201 那台不下雨的存档里一次就点着）。
    send('weather clear 1000000')
    send('difficulty easy')
    for t in (MA_TAG, MB_TAG, ANCHOR):
        send('kill @e[tag=%s]' % t)
    send('summon minecraft:armor_stand ~ ~ ~ {Marker:1b,NoGravity:1b,Invisible:1b,'
         'Tags:["%s"]}' % ANCHOR)
    A = '@e[tag=%s,limit=1]' % ANCHOR
    send('execute at %s run fill ~-6 ~-1 ~-6 ~6 ~-1 ~6 minecraft:stone' % A)
    send('execute at %s run fill ~-6 ~ ~-6 ~6 ~4 ~6 minecraft:air' % A)
    # 两只女仆各揣 16 个金苹果（第 0 格）。
    # 【血量用默认 20，不写 Attributes】写 max_health=200 + Health:200f 会踩原版的读取顺序
    # （LivingEntity 先读 Health 再读 Attributes → 200 被夹到 20，属性随后才抬到 200，
    #  于是她一出生就是 10% 血；实测探针里"召出来 2 秒就少一个苹果"就是这么来的）。
    # 默认 20 血 + 12 秒火（每秒 1 点）她扛得住，也不影响"着火吃金苹果"这条链路（它不看血量）。
    #
    # 【物品 NBT 两版写法不同】1.20.1 是 `Count:16b`（大写、byte）；1.21 之后物品改数据组件，
    # 序列化出来是 `count: 16`（小写、int）——写错不报错，只是**静默按 1 个**装载
    #（实测：neo 那台上 data get 读回来是 `{count: 1, ...}`，整条用例会变成"她只吃 1 个"的
    #  假绿）。所以这里按版本给两种写法。
    item_apple = ('{Slot:0b,id:"minecraft:golden_apple",Count:%db}' % APPLES
                  if which != 'neoforge1211'
                  else '{Slot:0b,id:"minecraft:golden_apple",count:%d}' % APPLES)

    def maid_nbt(name, tag):
        return ('{HandItems:[{},{}],ArmorItems:[{},{},{},{}],'
                'MaidInventory:{Size:36,Items:[%s]},Owner:[I;1,2,3,4],Tags:["%s"],'
                'CustomName:"\\"%s\\"",PersistenceRequired:1b}' % (item_apple, tag, name))
    send('execute at %s run summon touhou_little_maid:maid ~ ~ ~ %s'
         % (A, maid_nbt('Apple619Fire' + RUN_TAG, MA_TAG)), wait=False)
    send('execute at %s run summon touhou_little_maid:maid ~4 ~ ~2 %s'
         % (A, maid_nbt('Apple619Ctrl' + RUN_TAG, MB_TAG)), wait=False)
    time.sleep(2)
    MA = '@e[tag=%s,limit=1]' % MA_TAG
    MB = '@e[tag=%s,limit=1]' % MB_TAG

    # 【NBT 里 Count 的位置】物品回显是 {Slot: 0, id: "minecraft:golden_apple", Count: 16b}
    # —— **Count 在 id 后面**（不是字母序！第一版按 {Count:..…golden_apple 匹配，
    # 一条都匹配不上，于是"两边都是 0 个"，把"她吃光了"和"数不出来"混成了一种现象）。
    # 现在先按 {…golden_apple…} 切出物品那一层，再从里面取 Count。
    ITEM_RE = re.compile(r'\{[^{}]*golden_apple[^{}]*\}')
    # 【两版的键名不同】1.20.1 = `Count`（大写），1.21+ = `count`（小写，数据组件时代）
    COUNT_RE = re.compile(r'[Cc]ount:\s*(\d+)')

    def apples_in(txt):
        n = 0
        for m in ITEM_RE.finditer(txt):
            c = COUNT_RE.search(m.group(0))
            if c:
                n += int(c.group(1))
        return n

    def count_apples(sel):
        """数她**全身**的金苹果：背包（MaidInventory）+ 双手（HandItems）。
        【为什么要加双手】TLM 的进餐任务会把整叠食物拿到主手（实测），只看背包会误判。"""
        n = 0
        for key in ('MaidInventory', 'HandItems'):
            mark = os.path.getsize(log_path)
            send('data get entity %s %s' % (sel, key))
            time.sleep(0.4)
            n += apples_in(_read(log_path, mark))
        return n, None

    def hand_apples(sel):
        """主手那一叠（用来分辨"整叠被 TLM 拿到手里"和"真被吃掉了"）"""
        mark = os.path.getsize(log_path)
        send('data get entity %s HandItems' % sel)
        time.sleep(0.4)
        return apples_in(_read(log_path, mark))

    def fire_ticks(sel):
        mark = os.path.getsize(log_path)
        send('data get entity %s Fire' % sel)
        time.sleep(0.8)
        txt = _read(log_path, mark)
        m = re.search(r'entity data:\s*(\d+)s', txt)
        return int(m.group(1)) if m else None

    print('点火前：着火那只 %s 个金苹果；对照那只 %s 个'
          % (count_apples(MA)[0], count_apples(MB)[0]))
    # 【她不能烧死】默认 20 血、火每秒 1 点：15 秒窗口烧掉 15 点，再被残留的火烧几秒就死了
    # ——第一版就是这么翻车的：她 09:58:42 "went up in flames"，随后的自检命令
    # "No entity was found"（人没了），冷却读数自然读不到。给着火那只挂 60 秒再生 IV
    # （只影响血量，不影响"着火吃金苹果"这条链路；对照那只一抹不动，保持干净）。
    send('effect give %s minecraft:regeneration 60 4' % MA)
    # 点火：火方块摆在她脚下（先站定再点，避免"点完她已经走开"）
    send('execute at %s run setblock ~ ~ ~ minecraft:fire keep' % MA)
    samples = []
    t0 = time.time()
    next_ignite = 0.0
    while time.time() - t0 < WINDOW:
        if time.time() - t0 >= next_ignite:
            send('execute at %s run setblock ~ ~ ~ minecraft:fire keep' % MA)
            next_ignite += IGNITE_EVERY
        time.sleep(1.0)
        samples.append((int(time.time() - t0), count_apples(MA)[0], count_apples(MB)[0],
                        fire_ticks(MA), hand_apples(MA)))
    # 窗口结束就把火清掉（她脚下那块），别让她在收尾阶段继续烧
    send('execute at %s run setblock ~ ~ ~ minecraft:air' % MA)
    fire_evidence = [s[3] for s in samples]
    print('采样（秒, 着火那只金苹果, 对照那只金苹果, 着火剩余 tick, 其中主手那一叠）:')
    for s in samples:
        print('   ', s)

fire_ok = [f for f in fire_evidence if f] if done else []
cd_left = None
if done:
    # 顺手把"冷却确实记上了"这条内部读数取出来（/maid_smart combat check 的最后一段）：
    # 修好前那个持久键根本不存在，永远是 0；修好后应当是 2400 − 已过去的 tick。
    # 【别用 tag 等日志再读一次】第一版就是"等 tag → 再从控制台文件里搜"两步走，
    # 在 neo 那台出现过"tag 等到了、行却没搜到"的时序抖动；这里改成**发完就轮询**，
    # 两份日志（promaid.log 的新增段 + 控制台日志）一起看，最多等 25 秒。
    send('maid_smart combat check %s' % MA, wait=False)
    for _ in range(50):
        time.sleep(0.5)
        for l in (_read(plog, _plog0) + _read(log_path)).splitlines():
            if '金苹果那条：她现在金苹果冷却剩余' in l:
                m = re.search(r'冷却剩余 (\d+) tick', re.sub(r'\u00a7.', '', l))
                if m:
                    cd_left = int(m.group(1))
                    break
        if cd_left is not None:
            break
    print('冷却剩余读数:', cd_left)

data = _read(log_path)
send('stop')
for _ in range(60):
    time.sleep(1)
    if p.poll() is not None:
        break
if p.poll() is None:
    subprocess.run(['taskkill', '/PID', str(p.pid), '/T', '/F'], capture_output=True)
time.sleep(2)
data = _read(log_path)

fails = []
if not done:
    fails.append('服务端没起来')
for pat in FAIL_PATTERNS:
    if pat in data:
        fails.append('服务端报错: %s' % pat)
if done:
    if not fire_ok:
        fails.append('她压根没被点着（整个窗口 Fire 都是 0/读不到）——这一轮什么都没验到')
    burning = len(fire_ok)
    last_a, last_b = samples[-1][1], samples[-1][2]
    if last_b != APPLES:
        fails.append('对照那只（没点着的）金苹果从 %d 变成 %d —— 数法或场景有问题，'
                     '这一轮的结论不能采信' % (APPLES, last_b))
    if last_a >= APPLES:
        fails.append('着火那只一个金苹果都没吃（%d → %d）—— 着火吃金苹果这条链路'
                     '整个没跑起来，不能算"修好了"' % (APPLES, last_a))
    elif last_a < APPLES - 1:
        problems = ('着火那只的金苹果从 %d 变成 %d（吃掉 %d 个；%d 次采样里读到她还烧着，'
                    '最后一次采样主手那一叠 %d 个）——用户报的「瞬间吃完」还在'
                    % (APPLES, last_a, APPLES - last_a, burning,
                       samples[-1][4] if samples else -1))
        if STRICT:
            fails.append(problems)
        else:
            print('  (参考：%s)' % problems)
    else:
        print('  冷却生效：只吃掉 1 个（%d → %d），窗口内没再吃' % (APPLES, last_a))
    # 内部读数：冷却必须真的记上了（> 0）。这是"她为什么停下来"的直接证据：
    # 修好前这条链路**没有**这个键（自检里恒 0），靠的是"每 tick 都能吃"。
    if cd_left is None:
        fails.append('没读到金苹果冷却读数（/maid_smart combat check 的自检输出没拿到）')
    elif cd_left <= 0:
        fails.append('金苹果冷却读数是 %d（应当 > 0）—— 冷却没记上，「她只吃一个」就'
                     '解释不通了' % cd_left)
    else:
        print('  冷却读数为 %d tick（普通金苹果 CD 2400，已过去 %d）'
              % (cd_left, 2400 - cd_left))

restore_tlm_config()

if fails:
    print('VERDICT: FAIL')
    for f in fails:
        print('  -', f)
    raise SystemExit(1)
print('VERDICT: PASS —— 着火时她只吃 1 个金苹果（冷却 = 它自己给的效果时长），'
      '剩下的 %d 个还在；对照那只 %d 个一个不少' % (samples[-1][1], samples[-1][2]))
raise SystemExit(0)
