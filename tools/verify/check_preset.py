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
BASELINE_FLIP = {'promaid_src': 151, 'promaid_src_neo': 155}
# 默认 false 的布尔白名单：默认就已经"没介入"，DIY 不必再关（新增项不在此表 → 会 WARN 提醒复核）
OFF_ACK = set("""
accessory.daYunWallPhase airRaid.diveBoostFan bombing.breakBlocks bombing.hurtFriendly build.turbo
combat.crossbowPlainFirework dialogue.proactiveKill flightFollow.enabled memory.vectorEnable
mine.autoCollect mine.chainFullDurability mine.seekThroughWalls misc.autoCollect misc.bdStorage
misc.cookBurnWood voice.freeFlight voice.glideElytraAnimation misc.freeFlight misc.glideElytraAnimation
wood.autoCollect
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
}
GATES_EXTRA = {'TLM_TELEPORT_EXEMPT': 'FollowPreserveMixin.java'}
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
    for name in ('SKIP', 'KEEP', 'FORCE_ON', 'RESET_KEEP'):
        b = block(src, name)
        out[name] = set(literals(b)) if b is not None else None
    vb = block(src, 'VALUES')
    out['VALUES'] = set(literals(vb)) if vb is not None else set()
    out['src'] = src
    return out


def path_like(s):
    return bool(re.match(r'^[a-z][A-Za-z0-9]*(\.[A-Za-z0-9]+)+$', s))


def check_tree(tree, label):
    cfg = parse_config(tree)
    pre = parse_preset(tree)
    print('--- %s (%s)：配置项 %d 个，布尔 %d 个' % (tree, label, len(cfg),
                                                  sum(1 for v in cfg.values() if v[0] == 'bool')))
    for name in ('SKIP', 'KEEP', 'FORCE_ON', 'RESET_KEEP'):
        if pre[name] is None:
            fail('%s: MaidConfigPreset 里找不到 %s 表（脚本与源码对不上了）' % (tree, name))
            pre[name] = set()

    # 1) 路径存在性
    for name in ('SKIP', 'KEEP', 'FORCE_ON', 'RESET_KEEP'):
        for p in sorted(pre[name]):
            if p not in cfg:
                fail('%s: %s 里的路径在配置里不存在：%s' % (tree, name, p))
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
