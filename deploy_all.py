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
实测六百七十二：武装拴绳三期的两个 jar（扫帚换座 / 拴绳不再定高度 / 上扫帚去抖）。
+ v1.3.0(beta) 实测六百七十一（武装拴绳二期：扫帚模式下右击绑定修可靠性【已经骑在扫帚上就放行 + 认人扫全部乘客 + 拒绝原因写日志】；悬挂定位改"挑目标 + 滑变"修玩家上下横跳；tetherHoldPos 兜底不再自相对【找不到地面就保持她自己现在的高度】修女仆无限爬升；悬挂距离默认 1.8→2.6 且可调 0.5~6.0；空袭绑定不再悬空起程【不再写任何高度】；绳子不再自己断【落地不再自动放人，只留入水】；玩家挂着期间同款免摔 fall+flyIntoWall）：
+ v1.3.0(beta) 实测六百七十三（武装拴绳四期：坐扫帚上右击可立刻切回绑定【换座态准星前方没实体 → 事件是 RightClickItem，补那个入口 + 只认自己扫帚上的女仆】；绑定玩家第一人称下女仆模型半透明【原版幽灵档 itemEntityTranslucentCull + 顶点 alpha，只对他生效，面板可调 0.0~1.0、1.0 关掉】；空袭模式先「牵绳」不挂人、她起飞才挂到二号位、落地满 2 秒放回牵绳）：
  * 实测六百七十四（武装拴绳四期）：半透明默认 0.35→0.1 且扫帚本体也透明；同步包带相位（修空袭档「坐到她头上」）；牵绳档金色描边标记；5 条新台词补语音
+ v1.3.0(beta) 实测六百七十五（武装拴绳五期：绑第二只自动松开第一只【换绑】；魂符收放残留的金色描边在「入世界最后一步」清掉；配置面板改革【武装拴绳独立成板块 + 首条功能详解 + 面板顺序按飞行三件套重排 + 首页路标 + 中英翻译键】+ 手册新增一整章；扫帚跟随起手/收手默认 25/5→6/3【不加鞘翅那条视线阻挡，理由入注释】；扫帚档额外下沉 0.3 消除与扫帚建模的重叠；绳子从一根 LINES 直线换成原版拴绳同款丝带（24 段三角带 + 编织明暗 + 两端光照插值）、配色改白）：
+ v1.3.0(beta) 实测六百七十六（武装拴绳六期：绳子隔远不再消失【自己的 8 格闸门放宽到 64 格】；"直上直下"时原版丝带宽度公式是 0/0 → 近垂直改用三片 0/60/120 围绳轴的带子 + 两端按帧插值 + 新增一层拖曳弯曲【她加速时绳子被拖在后面】，并修正原版两顶点竖直错开顺序；悬挂档右击不再靠准星射线【她就在你头顶，射线打不到 → 补"我骑着的就是她"这条认人规则】，修"绑定态右击坐回扫帚"时灵时不灵；1.21.1 新增二号位重锤猛击【玩家骑乘时 fallDistance 恒 0 砸不出猛击 → 按跟着她俯冲的下降格数替他记一份，攻击瞬间写回，新混入 PlayerMaceRideFallMixin + 新配置键 + 面板行】）：
+ v1.3.0(beta) 实测六百七十七（两套都齐说一句「我已经准备好了」【空袭三件套齐 + 已骑扫帚持远程武器 → 一步气泡 + 语音 ready_dual.ogg，说一次就闩住、扫帚条件解除/空袭条件断开满 2 秒/退出扫帚任务三处才刷新 CD；空袭那一半新写任务无关判据 airAssaultReady——扫帚模式的任务不是飞行任务，用 isModeActive 会误判成"缺武器"】；拴绳的「拉扯」做成真的【六百七十六 那层只是画面弯曲：现在服务端每 tick 按原版拴绳那套分档给力——> 6 格照抄原版 copySign(d²×0.4,d) 冲量、2~6 格补"朝主人的速度上限"代替原版每 tick 的 A* 寻路、> 10 格原版会撒手我们不撒手；挂在 MaidTickEvent 上（她自己的 tick 之前，两版 javap 实证）而不是每 2 tick 的校验；只对牵绳档使劲，悬挂档由骑乘定位刚性控制；新配置键 + 面板行 + 中英翻译键】；绳子弯曲改成"相对速度 × 松弛度"——绷紧时是直线，只有绳松才弯）：
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
