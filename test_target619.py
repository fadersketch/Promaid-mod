# -*- coding: utf-8 -*-
"""实测六百一十九（其二/其三）战斗感知：① 隔着方块的怪不算威胁 ② 接战时工作圈临时放大。

用法:
    python test_target619.py [1201|neoforge1211] [jar路径]

需求原文（用户）:
    "1.用了几天感觉索敌还是有一点点问题 在地面下面有空洞里面有僵尸 酒狐隔着方块就感知到了
      但是因为没有可以下去的入口所以就会开始原地打转 或许改成有方块遮挡的怪不能被感知到
      会好一些？还有近战战斗时由于超出工作范围而被传送回来然后就这么来回循环 感觉可以改成
      战斗状态临时扩圈把范围改大些回到常态在用正常设置的工作范围"

【为什么这条用例跑的是"自检"而不是"看她打不打怪"】两条改的都要求**在线主人在场**
（我们的威胁驱动要主人实体在线），而专用服务器上没有玩家——结构上端到端触发不了
（本仓五百四十五/六百〇八 踩过同一个坑）。所以照先例：把真场景摆出来、调真方法，
把判据的取值打日志，这里逐条断言（细则见 CombatSenseCheck）。

验到的（每条都配了反向对照，防止"判据恒 false/恒 true"也能过）：
  · 遮挡那条：墙在 → 看不见 + 不算威胁 + 扫描不选它；
  · 对照：拆墙 → 同一只僵尸立刻翻成看得见 + 算威胁 + 扫描选中它；
  · 对照：看得见但没锁定我方 → 仍不算威胁（判据是合取）；
  · 对照：锁定了我方但远在 40 格外 → 扫描不选（16 格搜索门没被放宽）；
  · 扩圈那条：常态 8 格 → 接战（脑里真写 ATTACK_TARGET）半径 32、10 格进圈、
    传送阈值 12 → 36 → 清掉目标立刻落回 8（"回到常态用正常的工作范围"）。
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

WAIT = 240
FAIL_PATTERNS = ('Mixin apply for mod promaid failed', 'InvalidInjectionException',
                 'MixinTransformerError', 'MixinApplyError', 'Failed to create brain',
                 'OutOfMemoryError', 'NoSuchMethodError', 'NoClassDefFoundError')
CHECK_CAT = '战斗感知自检'
RUN_TAG = '619'

which = sys.argv[1] if len(sys.argv) > 1 else '1201'
cfg = TARGETS.get(which)
if not cfg:
    print('usage: python test_target619.py [1201|neoforge1211] [jar路径]')
    sys.exit(2)
if len(sys.argv) > 2:
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
# 扩圈那条要读配置值：显式写成默认 32（免得服务器上留着别的值，断言对不上）
print('config:', set_section('combat', selfPreserve='true', combatWorkRange='32'))
mods_dir = os.path.join(server, 'mods')
for old in os.listdir(mods_dir):
    if old.startswith('promaid-') and old != cfg['modname']:
        os.remove(os.path.join(mods_dir, old))
shutil.copyfile(cfg['jar'], os.path.join(mods_dir, cfg['modname']))
import hashlib
print('jar copied:', cfg['modname'], os.path.getsize(cfg['jar']),
      'md5', hashlib.md5(open(cfg['jar'], 'rb').read()).hexdigest()[:12])

log_path = os.path.join(server, 'console_target619.log')
plog = os.path.join(server, 'logs', 'promaid.log')
_plog0 = os.path.getsize(plog) if os.path.exists(plog) else 0
log = open(log_path, 'wb')
p = subprocess.Popen([cfg['java'], '-Xmx3G', '-Dfile.encoding=UTF-8'] + cfg['args'],
                     cwd=server, stdout=log, stderr=subprocess.STDOUT,
                     stdin=subprocess.PIPE,
                     creationflags=subprocess.CREATE_NEW_PROCESS_GROUP | 0x00000008)
open(pid_file, 'w').write(str(p.pid))
print('server pid', p.pid)


def send(cmd, wait=True, timeout=90, tag=None):
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


done = False
for _ in range(WAIT):
    time.sleep(1)
    if p.poll() is not None:
        break
    if 'Done (' in _read(log_path):
        done = True
        break

fails = []
check_lines = []
if not done:
    fails.append('服务端没起来')
else:
    print('server done, settling ...')
    print('   settled:', settle())
    NAME = 'Target619Maid' + RUN_TAG
    TAG = 'target619maid' + RUN_TAG
    ANCHOR = 'target619anchor' + RUN_TAG
    send('gamerule doMobSpawning false')
    send('difficulty easy')
    send('kill @e[tag=%s]' % TAG)
    send('kill @e[tag=%s]' % ANCHOR)
    # 铁砧旁边不要有别的怪：自检里"扫描选中了谁"是按身份比的，但清一下更干净
    send('kill @e[type=minecraft:zombie]')
    send('summon minecraft:armor_stand ~ ~ ~ {Marker:1b,NoGravity:1b,Invisible:1b,'
         'Tags:["%s"]}' % ANCHOR)
    A = '@e[tag=%s,limit=1]' % ANCHOR
    send('execute at %s run fill ~-3 ~-1 ~-3 ~3 ~-1 ~3 minecraft:stone' % A)
    send('execute at %s run fill ~-3 ~ ~-3 ~3 ~4 ~3 minecraft:air' % A)
    maid_nbt = ('{HandItems:[{},{}],ArmorItems:[{},{},{},{}],'
                'MaidInventory:{Size:36,Items:[]},Owner:[I;1,2,3,4],Tags:["%s"],'
                'CustomName:"\\"%s\\"",PersistenceRequired:1b}' % (TAG, NAME))
    send('execute at %s run summon touhou_little_maid:maid ~ ~ ~ %s' % (A, maid_nbt),
         tag=NAME)
    MA = '@e[tag=%s,limit=1]' % TAG
    # ★ 自检本体：等到它最后一行（金苹果那条 = 最后一段）
    send('maid_smart combat check %s' % MA, tag='金苹果那条：')
    lines = [re.sub(r'\u00a7.', '', l.split('] ', 1)[-1].strip())
             for l in readlog().splitlines() if CHECK_CAT in l]
    check_lines = [l for l in lines if l.strip()]

data = readlog()
send('stop')
for _ in range(60):
    time.sleep(1)
    if p.poll() is not None:
        break
if p.poll() is None:
    subprocess.run(['taskkill', '/PID', str(p.pid), '/T', '/F'], capture_output=True)
time.sleep(2)
data2 = readlog()
if len(data2) > len(data):
    data = data2

flat = re.sub(r'\u00a7.', '', data)
check_lines = [re.sub(r'\u00a7.', '', l.split('] ', 1)[-1].strip())
               for l in flat.splitlines() if CHECK_CAT in l]
check_lines = [l for l in check_lines if l.strip()]

print()
print('=' * 74)
print('自检输出（%d 行）:' % len(check_lines))
for l in check_lines:
    print('    ', l[:220])
print('=' * 74)

if not check_lines:
    fails.append('没看到自检输出（命令没生效？）')
else:
    for l in check_lines:
        if '[FAIL]' in l:
            fails.append('自检里有 FAIL：%s' % l[:200])
    if any('[SKIP]' in l for l in check_lines):
        fails.append('自检里有 SKIP：%s'
                     % [l for l in check_lines if '[SKIP]' in l][0][:200])
    need = [
        ('遮挡那条：墙在', '遮挡那条（墙在 → 看不见 + 不算威胁 + 扫描不选它）'),
        ('对照：把墙拆掉', '对照（拆墙 → 立刻看得见 + 算威胁 + 扫描选中它）'),
        ('对照：看得见但没锁定我方', '对照（看得见但没锁定我方 → 仍不算威胁）'),
        ('对照：锁定我方但远在 40 格外', '对照（锁定我方但 40 格外 → 扫描不选它）'),
        ('扩圈那条：常态圈设成 8 格', '扩圈那条（常态圈读数 8）'),
        ('扩圈那条：脑里有活目标时', '扩圈那条（接战时半径 32、10 格进圈）'),
        ('扩圈那条：TLM 的传送阈值', '扩圈那条（传送阈值 12 → 36）'),
        ('扩圈那条：清掉目标（战斗结束）', '扩圈那条（清目标 → 立刻落回 8）'),
        ('金苹果那条：她现在金苹果冷却剩余', '金苹果那条（冷却读数）'),
    ]
    for key, desc in need:
        if not any(key in l for l in check_lines):
            fails.append('缺少 %s' % desc)
    # 具体数值也要钉住：常态 8 → 接战 32 → 回 8；传送阈值 12 → 36
    joined = '\n'.join(check_lines)
    for pat, desc in ((r'常态圈设成 8 格.*半径读数 8\.0', '常态半径读数应为 8.0'),
                      (r'脑里有活目标时.*半径读数 32\.0（= max\(常态 8, 配置 32\)）',
                       '接战半径读数应为 32.0（= max(8, 32)）'),
                      (r'离圈心 10 格进圈（isWithinRestriction=true）', '10 格应当进圈'),
                      (r'传送阈值（\(int\)半径 \+ 4）从 12 格抬到 36 格', '传送阈值应为 12 → 36'),
                      (r'清掉目标（战斗结束）—— 半径立刻落回 8\.0、10 格又出圈',
                       '战斗结束后应当立刻落回 8.0 / 10 格出圈')):
        if not re.search(pat, joined):
            fails.append('数值不对：%s' % desc)

for pat in FAIL_PATTERNS:
    if pat in flat:
        fails.append('服务端报错: %s' % pat)

if fails:
    print('VERDICT: FAIL')
    for f in fails:
        print('  -', f)
    raise SystemExit(1)
print('VERDICT: PASS —— ① 隔着方块的怪不算威胁（拆墙立刻翻转、没锁定我方/太远也都不算）；'
      '② 接战时工作圈从 8 放到 32、传送阈值 12 → 36，清掉目标立刻落回 8')
raise SystemExit(0)
