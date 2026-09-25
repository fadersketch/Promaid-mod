# -*- coding: utf-8 -*-
"""Deploy the fixed jars to all local instances.

1.21.1 NeoForge 21.1.250  : patched/promaid-1.3.0-neoforge-1.21.1.jar
1.20.1 Forge 47.4.21      : patched/promaid-1.3.0-forge-1.20.1.jar   (user's main modpack)
1.20.1 server pack1201    : patched/promaid-1.3.0-forge-1.20.1.jar
Old jars are backed up under patched/backup_old/ first.

v1.3.0 实测六百五十四（扫帚模式）+ 实测六百五十三（烧制卡死）+ v1.3.1 实测六百五十五（扫帚实测六条）
+ v1.3.2 实测六百五十六（烧制清单网格乱列 / 骑扫帚反复被拉回 / 移动照搬原版 / 起飞 1 格与接敌升 8 格 / 跟随同款）
+ v1.3.3 实测六百五十八（排班表列表行改显示状态 / 女仆配置"打开即关"预检 / 扫帚原地打转与爬升高度 /
  找扫帚优先 / 防刷怪插火把）
+ v1.3.4 实测六百五十九（找扫帚改成先去骑世界里放着的扫帚实体 / 女仆配置「真正的远程开界面」）：
+ v1.3.5 实测六百六十（女仆配置改「强制同步」：区块加载解决不了客户端同步，新增 ChunkMap$TrackedEntity 强制配对 + 每 tick 同步泵，距离上限取消）：
+ v1.3.6 实测六百六十一（扫帚模式收尾：牵引绳「连人带扫帚」传送、守家时绕工作范围盘旋、独占配置板块、骑乘限制只留主手武器、烹饪清单面板收成一个勾）：
+ v1.3.7 实测六百六十二（修死机：RemoteTrackBridge 这个「鸭子接口」躺在 mixin 包里又没登记，玩家一进世界服务端就崩 IllegalClassLoadError——搬到 com.maidsmart.schedule）：
+ v1.3.8 实测六百六十三（借自别人改过的 TLM 1.5.3：援护主人的仇人 + 目标跑远就撒手，新配置「援护半径」combat.assistRadius 默认 16）：
+ v1.3.0(beta) 实测六百六十四（玩家七条反馈：扫帚+home 不受排班传送 / 扫帚撞墙先飘到最近空气格 / 扫帚跟随起手收手距离与卡墙脱困三条新档 / 守家圈心兜底+诊断日志 / 刷怪笼插火把的走位所有权 / 烧制清单写清食物在同一格；版本号按玩家要求回到 v1.3.0 beta 口径）：
+ v1.3.0(beta) 实测六百六十六（枪械"打几发就哑火"双成因修复 + 面板越界值红字/钳位/跳转前保存）：
+ v1.3.0(beta) 实测六百六十七（粉丝点单：新道具「武装拴绳」——右击飞行女仆把自己挂到她下方，武装直升机二号位，再右击解除；合成拴绳+铁锭×2；面板扫帚模式板块末尾三行）：
+ v1.3.0(beta) 实测六百六十八【紧急修复】（667 的武装拴绳让游戏开不起来：mixin 的 @Inject 只匹配目标类【自己声明】的方法，
  而 positionRider 只在原版 Entity 声明、EntityMaid 只是继承 → 启动期 InvalidInjectionException 崩在 Bootstrap。
  改成 @Mixin(Entity.class) + instanceof EntityMaid 守卫；两树名字口径写死并带完整描述符；新增 _mixchk.py 全量注入点审计闸门）：
+ v1.3.0(beta) 实测六百六十九（武装拴绳五条实测反馈：门槛改两档【扫帚需空中/空袭随时】+ 下方没空间不把人按进地面；
  右击优先绑定【目标是扫帚→取背上女仆】；绑定后不再跟随盘旋、改成离地 3 格定点悬停【扫帚与空袭共用一处口径】；
  有语音的系统气泡 青§b→粉§d；贴图换原版拴绳重上色为白；9 条拴绳气泡进语音包 manifest 124→133）：
+ v1.3.0(beta) 实测六百七十（魂符提示语纠正：生命值过低 → 受到致命伤害；末地水晶底座回收修"飞远了收不回来"：条目不再先删后做、区块没加载就留着下一 tick 再试【不再同步强制加载/抛异常丢条目】、按 UUID 重解析回收对象、回收表单列 1024 且溢出会说话、三条"这一发作废"的路也把底座还她）：
先试【直接复制】（不需要 UAC）；只有直接复制被拒（权限不够）时
才退回原来的提权 PowerShell 通道。旧版是无条件提权 → 会弹 UAC 卡住等人点。
"""
import os
import shutil
import subprocess
import sys
import tempfile

sys.stdout.reconfigure(encoding='utf-8')
BASE = os.path.dirname(os.path.abspath(__file__))

JOBS = [
    (os.path.join(BASE, 'patched', 'promaid-1.3.0-neoforge-1.21.1.jar'),
     r'D:\.minecraft\versions\1.21.1-NeoForge_21.1.250\mods\promaid-1.3.0-neoforge-1.21.1.jar'),
    (os.path.join(BASE, 'patched', 'promaid-1.3.0-forge-1.20.1.jar'),
     r'D:\.minecraft\versions\1.20.1-Forge_47.4.21\mods\promaid-1.3.0-forge-1.20.1.jar'),
    (os.path.join(BASE, 'patched', 'promaid-1.3.0-forge-1.20.1.jar'),
     r'C:\Users\Sketch\mc_server_test\pack1201\mods\promaid-1.3.0-forge-1.20.1.jar'),
]

# refuse while any java/javaw runs (game or server open)
probe = subprocess.run(['powershell', '-NoProfile', '-Command',
                        "(Get-Process -ErrorAction SilentlyContinue | "
                        "Where-Object { $_.ProcessName -match '^(java|javaw)$' }).Count"],
                       capture_output=True, text=True)
try:
    running = int((probe.stdout or '0').strip() or '0')
except Exception:
    running = 0
if running > 0:
    print('DEPLOY REFUSED: %d 个 java/javaw 进程在运行（游戏或服务器）——先全部退出。' % running)
    sys.exit(5)

for src, dst in JOBS:
    if not os.path.isfile(src):
        print('FATAL: jar 不存在:', src)
        sys.exit(6)

# backup old jars
backup = os.path.join(BASE, 'patched', 'backup_old')
os.makedirs(backup, exist_ok=True)
for src, dst in JOBS:
    if os.path.exists(dst):
        tag = os.path.basename(os.path.dirname(os.path.dirname(dst)))  # instance dir name
        bdst = os.path.join(backup, tag + '__' + os.path.basename(dst))
        try:
            shutil.copyfile(dst, bdst)
            print('backup:', bdst, os.path.getsize(bdst))
        except Exception as e:
            print('backup failed (continuing):', e)


def drop_other_versions(dst):
    """同一 modId 的旧包留在 mods 目录会与新包冲突导致启动失败 → 只删本模组的旧包
    （保留本次要写的那一个文件名），不动别人的 jar。"""
    d, keep = os.path.dirname(dst), os.path.basename(dst)
    for f in os.listdir(d):
        if f.startswith('promaid-') and f.endswith('.jar') and f != keep:
            try:
                os.remove(os.path.join(d, f))
                print('  removed old jar:', os.path.join(d, f))
            except Exception as e:
                print('  remove failed:', f, e)


# stage to temp (ASCII path) then copy
staged = []
for src, dst in JOBS:
    tmp = os.path.join(tempfile.gettempdir(), 'promaid_deploy_' + os.path.basename(dst))
    shutil.copyfile(src, tmp)
    staged.append((tmp, dst, os.path.getsize(src)))
    print('staged:', tmp, os.path.getsize(tmp))

need_elevation = []
for tmp, dst, size in staged:
    try:
        shutil.copyfile(tmp, dst)
        print('copied (no UAC):', dst)
    except Exception as e:
        print('direct copy failed, will try elevated:', dst, e)
        need_elevation.append((tmp, dst))

if need_elevation:
    inner_parts = ["Copy-Item -LiteralPath '%s' -Destination '%s' -Force" % (t, d)
                   for t, d in need_elevation]
    for t, d in need_elevation:
        inner_parts.append("Get-ChildItem -LiteralPath '%s' -Filter 'promaid-*.jar' "
                           "-ErrorAction SilentlyContinue | Where-Object { $_.Name -ne '%s' } "
                           "| Remove-Item -Force" % (os.path.dirname(d), os.path.basename(d)))
    inner = '; '.join(inner_parts)
    cmd = ['powershell', '-NoProfile', '-Command',
           "Start-Process powershell -Verb RunAs -Wait -ArgumentList '-NoProfile','-Command',"
           "'__INNER__'".replace('__INNER__', inner.replace("'", "''"))]
    r = subprocess.run(cmd, capture_output=True, text=True)
    print('elevated rc:', r.returncode, (r.stdout or '').strip()[:200], (r.stderr or '').strip()[:200])

for src, dst in JOBS:
    if os.path.isdir(os.path.dirname(dst)):
        drop_other_versions(dst)

ok = True
for tmp, dst, size in staged:
    if os.path.exists(dst):
        dsz = os.path.getsize(dst)
        match = dsz == size
        ok = ok and match
        print('deployed: %s  %d  match=%s' % (dst, dsz, match))
    else:
        ok = False
        print('MISSING after deploy:', dst)
    try:
        os.remove(tmp)
    except Exception:
        pass
sys.exit(0 if ok else 3)
