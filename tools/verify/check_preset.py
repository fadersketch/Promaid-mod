# -*- coding: utf-8 -*-
"""
预设模式护栏（实测八百三十五）：校验 MaidConfigPreset 的分类表与 MaidSmartConfig 的真实配置项对得上。

用法: python tools/verify/check_preset.py [--warn]

检查项（FAIL 会以退出码 1 结束；--warn 时只打印）：
  1. KEEP / SKIP / FORCE_ON / VALUES 里的每个路径都真实存在（防手误、防改配置后失效）；
  2. KEEP 与 SKIP / FORCE_ON 不重叠；
  3. 全部布尔都被分类（关 / 保留 / 跳过 / 默认 false 不动），条数与基线一致（新增配置会显眼）；
  4. 列出"默认 false 且不在 FORCE_ON"的布尔——反语义项必须逐个确认；
  5. 三道 mixin 闸引用到对应配置项（闸真的接上了，不是写了表没人用）；
  6. 两树的预设表逐项一致（镜像合规）。
"""
import io
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
TREES = [('promaid_src', 'Forge'), ('promaid_src_neo', 'Neo')]
# 每个树的 DIY 会关掉的布尔条数基线（新增配置项后要一起改，见 DEV_GUIDE §7）
# 实测八百八十二·TD-08：巡逻总开关的 TOML 路径原与扫帚总开关撞键（broom.enable），
# 撞键时 parse 只会算到一个 → 拆开后两条都独立计数，基线 173/177 → 174/178。
# 实测九百〇六：新增 accessory.daYunSmash（撞击，默认开、随 DIY 关）→ 174/178 → 175/179。
# 实测九百一十六：misc.pickupPriority（挖矿中禁止拾取）默认由 true 翻成 false = 默认不再介入
# （干活时照常捡掉落物）→ 不计入 DIY 关闭条数，基线 175/179 → 174/178，并列入 OFF_ACK。
# 实测九百二十：新增 mine.tagAuto（矿表标签自动识别，默认开、与伐木 tagAuto 同口径）→
# DIY 会关掉它（回到"只认矿表"）→ 基线 174/178 → 175/179。
# 实测九百二十七：删掉 mine.repick / mine.stuckWatchdog / mine.stuckResetSeconds /
# wood.stuckWatchdog / wood.stuckResetSeconds 五只开关（"发呆看门狗"整段不要了）→
# DIY 会关掉的布尔少 3 条（两只 stuckWatchdog + mine.repick；两个 stuckResetSeconds 是数值项）
# → 175/179 → 172/176。
# 实测九百四十二：mine.seekThroughWalls（已改名「智能挖矿」）默认由 false 翻成 true =
# 默认即"介入"（女仆自己隔墙找矿），DIY 要把它关掉 → 172/176 → 173/177。
# 实测九百四十七【DIY 新规】：新任务（挖矿/伐木/建造/扫帚/空袭）与新物品（压缩盒/武装拴绳/
# 骑乘指挥棒/饰品大运）整块不再吃 DIY（见 MaidConfigPreset.DIY_KEEP_PREFIX / DIY_KEEP_EXACT），
# 于是 DIY 关闭条数大幅下降 → 173/177 → 105/108。
# 实测九百九十一：新增 combat.engageChain（接敌才接管走位，默认开、随 DIY 关——DIY 下战斗
# 链路本来就不接管）→ 105/108 → 106/109。
# 实测九百九十三：combat.engageChain 改名为 combat.takeOverMovement（语义改为「是否接管走位」，
# 默认开、随 DIY 关，条数不变）+ 新增 misc.takeOverFishing / misc.takeOverFarm（钓鱼 / 农场接管
# 总闸，默认开、随 DIY 关）→ 106/109 → 108/111。
# v1.3.3：1.20.1 移植 1.21.1 的重锤猛击（combat.maceSmash / maceWindCharge 两条默认开、
# 随 DIY 关；Neo 树本来就有这两条，基线不变）→ Forge 108 → 110。
# R1018（Neo 专属）：新增 combat.spearCharge（长矛核心攻击模式冲锋，默认开、随 DIY 关）
# → Neo 111 → 112；Forge 尚未镜像，基线不动。
# R1022（Neo 专属）：新增 combat.spearAirRaid（长矛空袭独立链路，默认开、随 DIY 关）
# → Neo 112 → 113；Forge 尚未镜像，基线不动。
# R1048：低血自保默认开（+1）；轰炸拆成水晶/重生锚/床/TNT（melee -1、三个新子项 +3）；
# 任务是玩家主动选择的模式，建造/挖矿/伐木/烧制/酿造不设总闸；DIY 基线回到 119 / 122。
# R1059：飞行跟随的「消耗烟花 / 消耗鞘翅 / 消耗三叉戟」三条合并成「是否消耗飞行物」一条，
# 原三条都随 DIY 关闭、新一条同样随 DIY 关闭 → 119/122 减 2 = 117/120。
# R1083：鞘翅长矛链路镜像到 Forge——combat.spearAirRaid（默认开、随 DIY 关）
# → Forge 117 → 118；Neo 早在 R1022 计过，基线不动。
# R1105：营火烹饪与飞行跟随改为默认开（玩家定调）→ 两项从 OFF_ACK 移出、计入 DIY 关闭基线。
BASELINE_FLIP = {'promaid_src': 120, 'promaid_src_neo': 122}
# 默认 false 的布尔白名单：默认就已经"没介入"，DIY 不必再关（新增项不在此表 → 会 WARN 提醒复核）
OFF_ACK = set("""
accessory.daYunWallPhase airRaid.diveBoostFan bombing.breakBlocks bombing.hurtFriendly build.turbo
combat.crossbowPlainFirework dialogue.proactiveKill memory.vectorEnable
mine.autoCollect mine.chainFullDurability mine.seekThroughWalls misc.autoCollect misc.bdStorage
misc.cookBurnWood voice.freeFlight voice.glideElytraAnimation misc.freeFlight misc.glideElytraAnimation
wood.autoCollect misc.pickupPriority misc.serverForcedConfig
combat.selfPreserveLowHpPillar combat.selfPreserveLowHpFlee combat.selfPreserveLowHpTeleport
combat.selfPreserveEnvFirePanic
misc.cookFurnaceFamily
""".split())
# 三道闸：配置字段名 → 必须引用它的 mixin 文件
GATES = {
    'MISC_PICKUP_VALUE_REORDER': 'MaidPickupPriorityMixin.java',
    'TLM_TELEPORT_EXEMPT': 'MaidTeleportPreserveMixin.java',
    'MISC_SCHEDULE_RANGE_FLOOR': 'ScheduleRangeMixin.java',
    # 实测八百四十：这两处原来"没有配置项、只在代码里硬问 isDiy"，现已落成真开关
    # （默认开、DIY 由预设自动置 false）——纳入本表后，检查强度比原来更高：
    # 要求 mixin 引用的必须是【自己那一条】开关，而不只是"引用了预设类"。
    'COMBAT_SWEEP_ALWAYS': 'MaidSweepMixin.java',
    'COMBAT_ATTACK_THOSE_ATTACKING_US': 'MaidCanAttackDefenseMixin.java',
    # 实测八百五十九：以下 17 处原为"改行为但无开关"的介入，本轮落成真开关（默认开、随 DIY 关）。
    # 先在 mixin 目录能覆盖到的部分纳入 GATES，其余（非 mixin 常驻）纳入 GATES_ELSEWHERE。
    'COMBAT_HOE_NOT_WEAPON': 'TaskAttackWeaponMixin.java',
    'BUILD_AREA_PROTECTION': 'ChunkFreezeMixin.java',
    'BUILD_FORCE_WORK_ACTIVITY': 'BuildActivityForceMixin.java',
    'MISC_STATIONARY_LOCK': 'MaidStationaryMixin.java',
    'MISC_DRIVEN_NO_STROLL': 'RandomStrollGoalRiddenMixin.java',
    'DIALOGUE_PROMPT_SITTING_WORK': 'PromaidPromptMixin.java',
    'MISC_BAUBLE_TOTEM': 'MaidBaubleTotemMixin.java',
    # 实测八百六十七：干活时（挖矿/伐木且已盯上目标）暂不执行 TLM 远距离传送召回
    'MISC_WORK_TELEPORT_HOLD': 'MaidTeleportPreserveMixin.java',
    # 实测九百九十三：农场接管总闸（农场与宰杀页第一条）——连收连种 / 批量种植都经本 mixin
    'MISC_TAKE_OVER_FARM': 'FarmSweepMixin.java',
}
GATES_EXTRA = {'TLM_TELEPORT_EXEMPT': 'FollowPreserveMixin.java'}
# 同上，但接点在非 mixin 的常驻类里（值 = 相对树根的文件路径）
GATES_ELSEWHERE = {
    'COMBAT_FRIENDLY_FIRE_GUARD': 'com/maidsmart/combat/FriendlyFireGuard.java',
    'COMBAT_PET_IMMUNITY': 'com/maidsmart/combat/PetImmunityGuard.java',
    'COMBAT_BUILD_SHIELD': 'com/maidsmart/combat/BuildShieldGuard.java',
    'MISC_FARMLAND_GUARD': 'com/maidsmart/combat/FarmlandGuard.java',
    'MISC_TOOL_AUTO_EQUIP': 'com/maidsmart/task/MaidToolAutoEquipBehavior.java',
    'MISC_HOME_MOVEMENT': 'com/maidsmart/follow/HomePatrolHandler.java',
    'MISC_FISHING_CHAIR': 'com/maidsmart/fishing/FishingChairService.java',
    'MISC_ORB_JOIN': 'com/maidsmart/ProMaidExtension.java',
    'MISC_EQUIP_RESCUE': 'com/maidsmart/ProMaidExtension.java',
    # 实测八百六十二：骑马追击目标
    'COMBAT_RIDE_PURSUE_TARGET': 'com/maidsmart/combat/RideBindManager.java',
    # 实测八百六十三：绑定飞行载具后的启动时间
    'COMBAT_RIDE_STARTUP_SECONDS': 'com/maidsmart/combat/RideBindManager.java',
    # 实测八百六十九：挖矿入口闸（没镐不启动）——接点在任务类里
    #（同批的 MINE_REPICK「锁定期间持续重挑」已在实测九百二十七删掉：锁上就一路推进，
    #  不再每 5 秒换目标，本表随之少一项。）
    'MINE_REQUIRE_PICK': 'com/maidsmart/task/MaidMineBehavior.java',
    # 实测九百〇六：「大运」撞击（破坏范围内按速度的伤害+击飞）——接点在饰品侧
    'DAYUN_SMASH': 'com/maidsmart/accessory/DaYunSmash.java',
    # 实测九百一十一：干活时（挖矿/伐木已盯上目标）工作圈临时扩圈——接点在任务侧读数类里
    'MISC_WORK_TELEPORT_HOLD': 'com/maidsmart/task/MaidWorkRangeExpand.java',
    # 实测九百九十三：接管走位（战斗走位总闸）/ 钓鱼接管总闸——接点在常驻类里
    'COMBAT_TAKE_OVER_MOVEMENT': 'com/maidsmart/task/MaidWorkTags.java',
    'MISC_TAKE_OVER_FISHING': 'com/maidsmart/fishing/FishingChairService.java',
}
# 「干预型 mixin 必须有闸」的兜底名单：这几处改的是女仆行为、但**没有自己的配置项**
# （表驱动管不到），所以在代码里直接问 MaidConfigPreset.isDiy()。少一处 = DIY 下仍然生效。
# 实测八百四十后本表为空：原先那两处（平A横扫、反击类型判不出的攻击者）已有真配置项，
# 已移入上面的 GATES。机制与这条注释保留，供将来再出现"无配置项的介入"时使用。
PRESET_GATES = []

FAILS = []
WARNS = []


def fail(msg):
    FAILS.append(msg)


def warn(msg):
    WARNS.append(msg)


def read(path):
    return io.open(path, encoding='utf-8', errors='ignore').read()


def parse_config(tree):
    """解析 MaidSmartConfig.java → {path: (type, default)}（type ∈ bool/num/str/list）"""
    src = read(os.path.join(ROOT, tree, 'com/maidsmart/config/MaidSmartConfig.java'))
    pat = re.compile(r'\.push\s*\(\s*"([^"]+)"\s*\)|\.pop\s*\(\s*\)'
                     r'|([A-Z][A-Z0-9_]*)\s*=\s*BUILDER\b(.*?);', re.S)
    stack, out = [], {}
    for m in pat.finditer(src):
        if m.group(1) is not None:
            stack.append(m.group(1))
            continue
        if m.group(2) is None:
            if stack:
                stack.pop()
            continue
        body = m.group(3)
        d = re.search(r'\.define(List|InRange|Enum)?\s*\(\s*"([^"]+)"\s*,\s*(.*)', body, re.S)
        if not d:
            continue
        kind, key, rest = d.group(1) or '', d.group(2), d.group(3)
        default = re.match(r'\s*([^,)]*)', rest).group(1).strip()
        if kind == 'List':
            typ = 'list'
        elif kind == 'Enum':
            typ = 'enum'
        elif kind == 'InRange':
            typ = 'num'
        elif default in ('true', 'false'):
            typ = 'bool'
        else:
            typ = 'str'
        path = ('.'.join(stack) + '.' + key) if stack else key
        if path in out:
            warn('%s: 配置项路径重复定义：%s（后一个会盖住前一个）' % (tree, path))
        out[path] = (typ, default)
    return out


def block(src, name):
    """取 `NAME = java.util.Set.of(` / `Map.of(` 的括号内文本"""
    m = re.search(re.escape(name) + r'\s*=\s*java\.util\.(?:Set|Map)\.of\(', src)
    if not m:
        return None
    i = src.index('(', m.start())
    depth, j = 0, i
    while j < len(src):
        if src[j] == '(':
            depth += 1
        elif src[j] == ')':
            depth -= 1
            if depth == 0:
                return src[i + 1:j]
        j += 1
    return None


def strip_comments(text):
    """去掉 // 行注释与 /* */ 块注释——注释里的中文引号会被当成字面量（曾误报"少管"）"""
    text = re.sub(r'/\*.*?\*/', ' ', text, flags=re.S)
    return re.sub(r'//[^\n]*', ' ', text)


def literals(text):
    return re.findall(r'"((?:[^"\\]|\\.)*)"', strip_comments(text))


def parse_preset(tree):
    src = read(os.path.join(ROOT, tree, 'com/maidsmart/config/MaidConfigPreset.java'))
    out = {}
    for name in ('SKIP', 'KEEP', 'FORCE_ON', 'RESET_KEEP',
                 'DIY_KEEP_PREFIX', 'DIY_KEEP_EXACT'):
        b = block(src, name)
        out[name] = set(literals(b)) if b is not None else None
    vb = block(src, 'VALUES')
    out['VALUES'] = set(literals(vb)) if vb is not None else set()
    out['src'] = src
    return out


def is_new_feature(pre, path):
    """实测九百四十七 DIY 新规：新任务 / 新物品整块不吃 DIY（前缀表 + 点名表）。"""
    if path in (pre.get('DIY_KEEP_EXACT') or set()):
        return True
    return any(path.startswith(x) for x in (pre.get('DIY_KEEP_PREFIX') or set()))


def path_like(s):
    return bool(re.match(r'^[a-z][A-Za-z0-9]*(\.[A-Za-z0-9]+)+$', s))


def check_tree(tree, label):
    cfg = parse_config(tree)
    pre = parse_preset(tree)
    print('--- %s (%s)：配置项 %d 个，布尔 %d 个' % (tree, label, len(cfg),
                                                  sum(1 for v in cfg.values() if v[0] == 'bool')))
    for name in ('SKIP', 'KEEP', 'FORCE_ON', 'RESET_KEEP',
                 'DIY_KEEP_PREFIX', 'DIY_KEEP_EXACT'):
        if pre.get(name) is None:
            fail('%s: MaidConfigPreset 里找不到 %s 表（脚本与源码对不上了）' % (tree, name))
            pre[name] = set()

    # 1) 路径存在性
    for name in ('SKIP', 'KEEP', 'FORCE_ON', 'RESET_KEEP', 'DIY_KEEP_EXACT'):
        for p in sorted(pre[name]):
            if p not in cfg:
                fail('%s: %s 里的路径在配置里不存在：%s' % (tree, name, p))
    for pfx in sorted(pre['DIY_KEEP_PREFIX']):
        if not any(pp.startswith(pfx) for pp in cfg):
            fail('%s: DIY_KEEP_PREFIX 的前缀在配置里没有任何匹配项（拼错了？）：%s'
                 % (tree, pfx))
    for lit in sorted(pre['VALUES']):
        if path_like(lit) and lit not in cfg:
            fail('%s: VALUES 里的路径在配置里不存在：%s' % (tree, lit))
        elif not path_like(lit) and lit not in ('x1',):
            warn('%s: VALUES 里出现了非路径字面量（确认是有意写的值）：%s' % (tree, lit))
    values_paths = set(l for l in pre['VALUES'] if path_like(l))

    # 2) 表之间不重叠
    for p in sorted(pre['KEEP'] & pre['SKIP']):
        fail('%s: %s 同时在 KEEP 与 SKIP 里' % (tree, p))
    for p in sorted(pre['KEEP'] & pre['FORCE_ON']):
        fail('%s: %s 同时在 KEEP 与 FORCE_ON 里' % (tree, p))

    # 3/4) 布尔分类
    flip, keep, skip, off, force = [], [], [], [], []
    for path, (typ, default) in sorted(cfg.items()):
        if typ != 'bool':
            continue
        if path in pre['SKIP']:
            skip.append(path)
        elif path in pre['FORCE_ON']:
            force.append(path)
        elif path in pre['KEEP']:
            keep.append(path)
        elif is_new_feature(pre, path):
            keep.append(path)  # 实测九百四十七 DIY 新规：新任务 / 新物品与默认模式一致
        elif default == 'true':
            flip.append(path)
        else:
            off.append(path)
    print('    DIY 关闭 %d 项 / 保留 %d / 跳过 %d / 反语义置 true %d / 默认 false 不动 %d'
          % (len(flip), len(keep), len(skip), len(force), len(off)))
    base = BASELINE_FLIP.get(tree)
    if base is not None and len(flip) != base:
        warn('%s: DIY 关闭条数 %d ≠ 基线 %d（改了配置项就同步基线，并确认新项该关还是该留）'
             % (tree, len(flip), base))
    if '--dump' in sys.argv:
        for p in flip:
            print('FLIP %s' % p)
    acked = [p for p in off if p in OFF_ACK]
    print('    默认 false 已确认 %d 项（白名单 OFF_ACK）' % len(acked))
    for p in off:
        if p not in OFF_ACK:
            warn('%s: 默认 false 且未分类（确认它默认就"没介入"；确认后加进 OFF_ACK）：%s' % (tree, p))

    # 5) 闸接上了没
    for field, mixin in GATES.items():
        text = read(os.path.join(ROOT, tree, 'com/maidsmart/mixin', mixin))
        if field not in text:
            fail('%s: %s 没有引用 %s（开关写了没人问）' % (tree, mixin, field))
    for field, mixin in GATES_EXTRA.items():
        text = read(os.path.join(ROOT, tree, 'com/maidsmart/mixin', mixin))
        if field not in text:
            fail('%s: %s 没有引用 %s（开关写了没人问）' % (tree, mixin, field))
    for field, rel in GATES_ELSEWHERE.items():
        text = read(os.path.join(ROOT, tree, rel))
        if field not in text:
            fail('%s: %s 没有引用 %s（开关写了没人问）' % (tree, rel, field))

    # 5b) 预设硬闸：没有配置项的干预型 mixin，必须直接问 MaidConfigPreset
    for mixin in PRESET_GATES:
        text = read(os.path.join(ROOT, tree, 'com/maidsmart/mixin', mixin))
        if 'MaidConfigPreset' not in text:
            fail('%s: %s 是干预型 mixin 但没有配置项、也没问 MaidConfigPreset（DIY 下会残留）'
                 % (tree, mixin))

    # 5c) 残留清单：既没有配置项、也没有预设闸的 mixin（打印给人看，不是门禁）
    if '--dump' in sys.argv:
        print('    残留清单（无配置项、无预设闸的 mixin）：')
        mdir = os.path.join(ROOT, tree, 'com/maidsmart/mixin')
        for fn in sorted(os.listdir(mdir)):
            if not fn.endswith('.java'):
                continue
            text = read(os.path.join(mdir, fn))
            if 'MaidSmartConfig' not in text and 'MaidConfigPreset' not in text:
                print('      无闸 %s' % fn)
    return pre


def main():
    warn_only = '--warn' in sys.argv
    tables = {}
    for tree, label in TREES:
        tables[tree] = check_tree(tree, label)

    # 6) 两树镜像一致
    a, b = tables['promaid_src'], tables['promaid_src_neo']
    for name in ('SKIP', 'KEEP', 'FORCE_ON', 'RESET_KEEP', 'VALUES'):
        if a[name] != b[name]:
            fail('两树的 %s 表不一致：仅在 Forge=%s / 仅在 Neo=%s'
                 % (name, sorted(a[name] - b[name]), sorted(b[name] - a[name])))

    print()
    for w in WARNS:
        print('WARN ' + w)
    for f in FAILS:
        print('FAIL ' + f)
    print('check_preset: FAIL %d / WARN %d' % (len(FAILS), len(WARNS)))
    if FAILS and not warn_only:
        sys.exit(1)


if __name__ == '__main__':
    main()
