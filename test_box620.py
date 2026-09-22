# -*- coding: utf-8 -*-
"""实测六百二十（其二）压缩盒禁入清单：附魔书/附魔武器与压缩盒放不进盒子、鼠标选不中压缩盒。

用法:
    python test_box620.py [1201|neoforge1211] [jar路径]

需求原文（用户）:
    "2.加一个黑名单，在压缩盒界面内无法放入附魔书/附魔武器和压缩盒，压缩盒在这个界面内无法
     被鼠标选中，并再次提示玩家不能把压缩盒放进压缩袋里。"

【为什么这条跑的是"自检"而不是"真去点界面"】界面每一次点击真正执行的就是
CompressionBoxService.handle —— 界面那一层（光标画在哪、点了哪个像素）在专用服务器上
根本跑不起来（没有客户端），所以照 六百一十七/六百一十八 的先例：用**假玩家**把这条路
原样走一遍，把判据的取值打日志，这里逐条断言（细则见 CompressionBoxCheck.refuseList）。

验到的（每条都配反向对照）：
  · 判据层：附魔书 / 压缩盒 → 拒绝（文案对得上）；石头 → 放行（对照）；
  · 数据层：mergeInto / merge 收附魔书 → 原样退回（界面、女仆、溢出回退三条路的总闸）；
  · 界面那条路：Shift+左键点背包里的附魔书 → 被拒 + 书还在 + 服务端回了一句提示；
  · 配置清单：当场加 minecraft:cobblestone → 立刻拒；清空 → 又放行（对照）；
  · 鼠标选不中压缩盒：左键点它拿不起来；鼠标上挂着东西去点它也不交换（两条路都要堵）；
  · 对照：同一叠圆石放进盒子格 → 必须成功（别为了拦它把正常搬运一起堵死）；
  · 女仆那一侧：往她背包里的盒子插附魔书 → 原样退回（isItemValid 也判 false）。
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
CHECK_CAT = '压缩盒自检'
BOX_ID = 'maid_smart:compression_box'
RUN_TAG = '620'

which = sys.argv[1] if len(sys.argv) > 1 else '1201'
cfg = TARGETS.get(which)
if not cfg:
    print('usage: python test_box620.py [1201|neoforge1211] [jar路径]')
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


stop_server()
# 【老档迁移的端到端验证】六百二十 把散步速度的默认从 0.7 降到 0.4：只改默认值对老档**毫无作用**
# （老 toml 里已经写着 0.7），所以加了"一次性标记"迁移（ProMaidMod.runConfigMigration）。
# 这里把配置写成**老档的样子**（strollSpeed = 0.7、没有 strollSpeedMigrated 这一行），
# 起来之后必须变成 0.4——这一条就是用户那句"调不了"的最后一环（他们档里那个 0.7 得自己动）。
if os.path.exists(CONFIG):
    raw = open(CONFIG, encoding='utf-8', errors='replace').read()
    raw = re.sub(r'^\s*strollSpeedMigrated\s*=.*$\n?', '', raw, flags=re.M)
    if re.search(r'^\s*strollSpeed\s*=', raw, re.M):
        raw = re.sub(r'^(\s*)strollSpeed\s*=.*$', r'\1strollSpeed = 0.7', raw, flags=re.M)
    else:
        raw = raw.rstrip('\n') + '\n\tstrollSpeed = 0.7\n'
    open(CONFIG, 'w', encoding='utf-8').write(raw)
    print('把配置写成老档的样子：strollSpeed = 0.7、无 strollSpeedMigrated')
mods_dir = os.path.join(server, 'mods')
for old in os.listdir(mods_dir):
    if old.startswith('promaid-') and old != cfg['modname']:
        os.remove(os.path.join(mods_dir, old))
shutil.copyfile(cfg['jar'], os.path.join(mods_dir, cfg['modname']))
print('jar copied:', cfg['modname'], os.path.getsize(cfg['jar']),
      'md5', hashlib.md5(open(cfg['jar'], 'rb').read()).hexdigest()[:12])

log_path = os.path.join(server, 'console_box620.log')
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
    NAME = 'Box620Maid' + RUN_TAG
    ANCHOR_TAG = 'box620anchor' + RUN_TAG
    MAID_TAG = 'box620maid' + RUN_TAG
    send('gamerule doMobSpawning false', wait=False)
    send('difficulty easy', wait=False)
    for t in (MAID_TAG, ANCHOR_TAG):
        send('kill @e[tag=%s]' % t)
    send('kill @e[type=minecraft:item]')
    send('summon minecraft:armor_stand ~ ~ ~ {Marker:1b,NoGravity:1b,Invisible:1b,'
         'Tags:["%s"]}' % ANCHOR_TAG)
    A = '@e[tag=%s,limit=1]' % ANCHOR_TAG
    send('execute at %s run fill ~-3 ~-1 ~-3 ~3 ~-1 ~3 minecraft:stone' % A)
    send('execute at %s run fill ~-3 ~ ~-3 ~3 ~3 ~3 minecraft:air' % A)
    # 她背包第 0 格 = **空的**压缩盒：女仆那一条需要一个空格子，盒子空着最省事
    # （计数写 Count:1b 两棵树都认——1 个的堆不管哪套字段名读出来都是 1）
    maid_nbt = ('{MaidScheduleMode:"ALL",HandItems:[{},{}],ArmorItems:[{},{},{},{}],'
                'MaidInventory:{Size:36,Items:[{Slot:0b,id:"%s",Count:1b}]},'
                'Owner:[I;1,2,3,4],Tags:["%s"],'
                'CustomName:"\\"%s\\"",PersistenceRequired:1b}'
                % (BOX_ID, MAID_TAG, NAME))
    send('execute at %s run summon touhou_little_maid:maid ~ ~ ~ %s' % (A, maid_nbt),
         tag=NAME)
    MA = '@e[tag=%s,limit=1]' % MAID_TAG
    send('maid_smart box %s' % MA, tag='背包视图：')      # 先确认盒子确实在她背包里
    send('maid_smart box check %s' % MA, tag='收尾：那一格已清回空')  # ★ 自检本体

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
check_lines = [l.split('] ', 1)[-1].strip()
               for l in flat.splitlines() if CHECK_CAT in l]
check_lines = [l for l in check_lines if l.strip()]

print()
print('=' * 74)
print('自检输出（%d 行）:' % len(check_lines))
for l in check_lines:
    print('    ', l[:230])
print('=' * 74)

if not check_lines:
    fails.append('没看到自检输出（命令没生效？）')
else:
    for l in check_lines:
        if '[FAIL]' in l:
            fails.append('自检里有 FAIL：%s' % l[:220])
    skips = [l for l in check_lines if '[SKIP]' in l]
    if skips:
        fails.append('自检里有 SKIP：%s' % skips[0][:220])
    need = [
        ('禁入清单①：附魔书', '附魔书被拒（判据层）'),
        ('禁入清单①：压缩盒 → 拒绝', '压缩盒被拒（判据层）'),
        ('禁入清单①：对照——普通物品（石头）放行', '石头必须放行（对照）'),
        ('禁入清单②：数据层 mergeInto 收附魔书 → 原样退回', '数据层 mergeInto 退回附魔书'),
        ('禁入清单②对照：同一方法收石头 → 正常进格', '数据层对照：石头正常进格'),
        ('禁入清单②：数据层 merge 收附魔书 → 一件都没进', '数据层 merge 退回附魔书'),
        ('禁入清单③（界面那条路）：Shift+左键点背包里的附魔书 → 被拒', '界面那条路拒绝附魔书'),
        ('禁入清单④：配置清单里加上 minecraft:cobblestone → 拒绝', '配置禁入清单立刻生效'),
        ('禁入清单⑤（女仆那一侧）：往她盒子第', '女仆那一侧拒绝附魔书'),
        ('鼠标取放⑤（六百二十，用户要的那条）', '鼠标选不中压缩盒（拿起那条路）'),
        ('鼠标取放⑤b：鼠标上挂着 5 个圆石再去点压缩盒那一格 → 也不换', '鼠标选不中压缩盒（交换那条路）'),
        ('对照：同一叠圆石点盒子第 3 格 → 正常放下', '对照：普通物品照旧放得进盒子'),
        ('鼠标取放⑥：挂着一叠时收手', '手上那一叠收手不丢'),
    ]
    for key, desc in need:
        if not any(key in l for l in check_lines):
            fails.append('缺少 %s' % desc)
    # 提示文案也要钉住：用户要的就是"再提示一句"
    joined = '\n'.join(check_lines)
    if not re.search(r'服务端回了一句「压缩盒不能装进压缩盒」', joined):
        fails.append('提示文案不对：拿压缩盒那句应当是「压缩盒不能装进压缩盒」')
    if not re.search(r'服务端回了「带附魔的物品不能放进压缩盒」', joined):
        fails.append('提示文案不对：附魔物品那句应当是「带附魔的物品不能放进压缩盒」')

# 配置文件：两条新选项的默认值必须落下来；自检跑完禁入清单必须是空的（它临时加过一条）
try:
    cfg_text = open(CONFIG, encoding='utf-8', errors='replace').read()
    if not re.search(r'^\s*refuseEnchanted\s*=\s*true\s*$', cfg_text, re.M):
        fails.append('配置里没有 refuseEnchanted = true（默认值没落盘）')
    if not re.search(r'^\s*refuseList\s*=\s*\[\s*\]\s*$', cfg_text, re.M):
        fails.append('配置里 refuseList 不是空的（自检临时加的那条没还原？）')
    if not re.search(r'^\s*strollSpeed\s*=\s*0\.4\s*$', cfg_text, re.M):
        fails.append('老档的 strollSpeed 没有被迁到 0.4（默认值改动对老档无效？）')
    if not re.search(r'^\s*strollSpeedMigrated\s*=\s*true\s*$', cfg_text, re.M):
        fails.append('strollSpeedMigrated 没有落盘为 true（一次性标记没写下去 → 下次启动还会再迁）')
except Exception as e:
    fails.append('读配置失败：%s' % e)

for pat in FAIL_PATTERNS:
    if pat in flat:
        fails.append('服务端报错: %s' % pat)

if fails:
    print('VERDICT: FAIL')
    for f in fails:
        print('  -', f)
    raise SystemExit(1)
print('VERDICT: PASS —— 附魔书/附魔武器与压缩盒都放不进盒子（判据/数据层/界面/配置清单/女仆五条路），'
      '鼠标在界面里选不中压缩盒（拿起与交换两条路都堵），提示文案与之一致；'
      '对照：普通物品照旧正常搬运')
raise SystemExit(0)
