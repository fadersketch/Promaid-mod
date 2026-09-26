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
+ v1.3.0(beta) 实测六百七十八（武装拴绳七期 + 扫帚数值：换座不再把她摔下去【玩家原话"不应该把女仆的骑乘状态也解除掉。这样子可能会导致失控"——旧版换座是"先把所有人请下扫帚再逐个放回去"，她真的被下过一次鞍、第二下失败就把她留在空中（她只有 20 血）；现在改用原版 addPassenger 自带的"玩家插队"规则（玩家是 Player 且第一乘客不是 Player → List.add(0, …) 插进驾驶位），她的骑乘关系一个字节都不动；javap 实证 1.20.1 Entity.m_20348_ 与 1.21.1 Entity.addPassenger 逐条同形，且 getPassengers() 返回 ImmutableList 所以只能靠原版那条规则换座】；扫帚接敌爬升高度 8 → 10【玩家原话"考虑到现在加入了这个模式，那么女仆需要飞的再高一点，默认应该是10格的高度"——写死的常量 CLIMB_BLOCKS 删掉，改成配置键 combat.broom.climb（默认 10，2~32）+ 面板行 + 中英翻译键；这一个数字同时决定"这一场遭遇的盘旋高度"】；左上角一行蓝色「绑定中」【玩家原话"最好是在左上角用蓝色字体显示一下玩家现在处于绑定状态。（渲染机制同冷却计时）"——真的复用冷却 HUD 那条链路：同一个包 → CooldownHudRenderer，位置/字体/自动清空/开界面隐藏全套白拿；只是字改成蓝色 §9、永远排最上一行；文案带档位"牵绳 / 二号位"；数据源只有 LINKS 一处；关掉冷却计时不影响它——那个"整段 return"的闸挪进新的 soulScanWanted()，冷却那三段各自判】）：
+ v1.3.0(beta) 实测六百七十九（骑扫帚链路收窄 + 配置默认值同步：**只有「武装拴绳在用」才接管扫帚驱动**【玩家原话"最好不要整体改骑扫帚的链路，防止其他mod对骑扫帚进行改动导致冲突。而是对物品进行判定（即只有玩家手持武装拴绳才走此链路，不拿则走原版）"——本模组改动 TLM 扫帚飞行的唯一地方 EntityBroomMaidTravelMixin 是在 EntityBroom.travel 的 HEAD 直接 cancel 掉原版、整条接管，别的 mod 的改动会被整体绕开；现在新增唯一判据 GunnerTetherManager.leashChainActive(maid)：主人手上握着武装拴绳（主手/副手）**或**她正被拴着（二号位里玩家拿的是枪或重锤，"不拿绳子"是常态，只认字面会把二号位摔下来）——不满足就一个字不改、travel 走原版、并清掉没人消费的推进意图，日志搜「扫帚让位」；注意原版「没有玩家驾驶的扫帚」是自由落体，收绳子前先落地】；配置默认值按玩家 1.21.1 实例同步 12 项【mine.breakBudget 22→6、soulSpellCooldownSeconds 60→1、waterLandingScan 2→3、playerDamageMode 4→2、autoResurrectDelaySeconds 60→10、aidThirstThreshold 15→20、combatWorkRange 15→32、scheduleAvailabilityCheck 开→关、flightFollow.enabled 关→开、firework/elytra/trident 三条省料开关 开→关；只改声明默认值、老 toml 一个字节都不动；面板与手册里写死旧默认的文案一并改】；两条调研结论入库【空袭施法：通用那半确实复用万法皆通 touhou_little_maid_spell 的公开 API，位移法术那半是自己写的反射直连 Iron's Spells（万法皆通没有公开的指定法术 API）；空袭开枪：接的原版 TLM 枪械通道 GunCommonUtil.performGunAttack → TACZ IGunOperator.shoot，promaid 只写视线/冷却/弹药三道门，没有任何碰枪械的 mixin】）：
+ v1.3.0(beta) 实测六百八十（搭方块禁用名单：面板「移动与行为 → 搭路 → 搭方块禁用名单」——全方块网格点一下切「红框✖禁止 / 绿框✔允许」，默认规则「只许原版天然方块」（NaturalBlocks 表 113 项），合成品与全部模组方块默认全禁、面板里点绿即可放开；判定单一口径 MaidBuildBlockFilter.isBlacklistedBuildBlock，覆盖自保搭高/挖矿/伐木/搭路；新键 buildOnlyNatural / buildBlacklist / buildWhitelist；饰品栏（Curios）里的鞘翅也算空袭三件套——全反射软兼容 CuriosElytraCompat，起飞时取出来穿到胸甲槽，耐久照原版扣（等效消耗）；hasElytra/equip/takeElytra/诊断四处接入）：
+ v1.3.0(beta) 实测六百八十一（把 679「按玩家实例同步默认值」整体撤回：12 项声明默认值恢复真默认【穿透预算 22 / 收符冷却 60 / 落地水下探 2 / 玩家伤害模式 4=仅一点 / 复活延迟 60 / 投喂口渴度 15 /战斗扩圈 15 / 排班可用性 开 / 飞行跟随 关 / 消耗烟花·鞘翅·三叉戟 全开】，并删掉 breakBudget 22→6 与 scheduleAvailabilityCheck 无条件→false 两条打架的强制迁移；玩家三个实例的 toml 一并改回默认【两个客户端走管理员通道 + 各留 .p681bak】，另加一次性兜底迁移 DEFAULT_REPAIR_MIGRATED【只搬还停在 679 值上的键】：
+ v1.3.0(beta) 实测六百八十二（扫帚模式失效修复：679 那道「武装拴绳没在用就让位」的闸整段撤掉——原版对"女仆单骑"只有自由落体，不拿绳子时她必然"坐着扫帚掉在地上动也不动"；接敌爬升默认 10→15 + 一次性迁移 broomClimbMigrated + 三个实例 toml 直改；武装拴绳：她换模式（空袭⇄扫帚）时自动解除一次（新去抖 KIND_TICKS/1 秒，日志「自动解除(切模式)」），并顺手修掉落水自动解除那支在迭代器里改 LINKS 的隐患；远程空袭三条：枪械冷却递减搬进 tickGunState（不再被射程/视线门冻住）、起飞段也开火、新增 airRaid.rangedFireRange 有效开火距离（默认 24，0=不限）；绑定态 HUD 多一行「右击她：解除；她骑扫帚时改为主动骑乘」）：
+ v1.3.0(beta) 实测六百八十三（YSM 模型的女仆重新有激流旋转特效：把 548 顺手写下的「LayerMaidSpinAttackGecko 只在 Gecko 渲染器下绘制」那条守卫撤掉——那句限制的理由（挂点依赖 Gecko 骨骼表）只对鞘翅图层成立，激流层一根骨骼都不用，而反编译证实 YSM 的女仆渲染器调用图层的位置与 TLM GeoReplacedEntityRenderer 同构，scale(-1,-1,1) 的换算照样成立；鞘翅那条守卫一字未动。顺带查清"万法皆通的女仆用烈焰冲锋时的模型"其实不是万法皆通的：烈焰冲锋=ISS burning_dash（只置 SpinAttackType.FIRE），火色激流是 ISS mixin 原版 SpinAttackEffectLayer 换贴图，万法皆通只负责把 ISS 的 cast 动画名映射成模型里的 iss:xxx 动画，而那套动画来自 YSM 内置default 包的 iss.animation.json。README/PUBLISH_GUIDE 那句同步改成只留鞘翅）：
+ v1.3.0(beta) 实测六百八十四（三件：① 扫帚盘旋「只比敌人高三格」的两个来源都在 MaidBroomDrive.combatClimbTarget —— 换目标就重爬（相位 key=敌人 UUID，而 TLM 每 tick 重挑 ATTACK_TARGET，两个敌人轮流被选中时高度在 15 格 / 3.66 格之间来回切）+ 被顶住就再也不试（旧版一场遭遇只爬一次，那个 3.66 是终值「以后一直保持」）→ 现在本场只爬一次、换目标只改 key，被顶住的每 5 秒（退避到 60 秒封顶）且头顶探针说"现在是空气"时重试爬升；STALL_TICKS 4→10；「头顶被顶住」那行现在写出头顶三格是什么方块（分清真天花板 vs 驱动故障）。② 悬挂玩家重锤：RIDE_SLOW_MIN 1.0→0.2 格/2tick（扫帚竖直上限 0.30 格/tick，旧门槛下永远被判慢降、累计钳在 1.0 → 挂在扫帚下一锤猛击都砸不出来），并给玩家那一侧补 [重锤门] 留痕（旧日志只有女仆那一侧的判定，1295 行全是"不触发"）；consumeRideFall 不再无条件写 RIDE_LAST_Y。③ 鞘翅：非 Gecko 渲染器（YSM 等）改走固定身体偏移（肩线 +1.86 格，480 那批统计值）不再整层跳过；模组鞘翅走通用判据 ItemStack.canElytraFly（不是护甲才叠翅膀，是护甲的（鞘翅胸甲）自带外观不叠，写一行「鞘翅渲染」日志）。手册两树 + README + PUBLISH_GUIDE 同步）：
+ v1.3.0(beta) 实测六百八十五（扫帚锁敌：她与两种空袭改用同一个索敌器 FlightTargeting——发现 50 格球 / 锁定后 128 格内维持 / 维持不再查视线，不再按 TLM 援护半径 16-8 格与 4 格高的排班盒丢目标；MaidBroomBehavior.aimTarget 先问索敌器、问不到才退回 TLM 链，stop 时 FlightTargeting.forget；「援护·跑远就松手」对扫帚空中让位（主人就挂在下面，前提不成立）；索敌探针（日志搜 空袭索敌）对扫帚也生效；两树手册扫帚节新增「锁敌」一段）
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
