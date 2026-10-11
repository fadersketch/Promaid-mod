# -*- coding: utf-8 -*-
"""配置项清单生成器（实测八百三十六，WORKFLOW.md §3.1③）。

把两树的 MaidSmartConfig 解析成一份清单：**按节分组** + **按主题索引**，用来回答两件事：
  1. 某个行为到底有几个开关（#37 第 3 条：搭路 9 条路径、砍树/战斗/跟随各有搭路）；
  2. 新加开关时先查有没有等价开关（避免同一行为散在多处）。

用法:
  python tools/verify/gen_switch_inventory.py            # 打印（用于对比是否过期）
  python tools/verify/gen_switch_inventory.py --write     # 写入 ARCHITECTURE-SWITCHES.md
  python tools/verify/gen_switch_inventory.py --grep 搭路  # 查主题（注释或路径命中）
"""
import io
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
TREES = [('promaid_src', 'Forge 1.20.1'), ('promaid_src_neo', 'NeoForge 1.21.1')]
OUT = 'ARCHITECTURE-SWITCHES.md'

# 主题索引：主题名 → 命中关键词（路径或注释里出现即归入）。改行为前先看这里。
TOPICS = [
    ('搭路 / 垫脚方块', ['bridge.', '搭路', '垫脚']),
    ('拾取 / 掉落物', ['pickup', '拾取', '掉落']),
    ('传送 / 召回', ['teleport', 'recall', '传送', '召回']),
    ('排班 / 作息', ['schedule', 'shift', '排班', '作息']),
    ('战斗 / 自保', ['combat.', 'selfPreserve', '战斗', '自保']),
    ('飞行 / 空袭', ['flight', 'airRaid', 'elytra', '飞行', '空袭', '鞘翅']),
    ('扫帚 / 载具', ['broom', 'vehicle', '扫帚', '载具']),
    ('挖矿 / 伐木', ['mine.', 'wood.', '挖矿', '伐木']),
    ('建造 / 蓝图', ['build.', 'blueprint', '建造', '蓝图']),
    ('防刷怪', ['spawner', 'torch', '刷怪笼', '火把']),
    ('大运 / 饰品', ['daYun', 'accessory', '大运']),
    ('压缩盒 / 背包', ['compressionBox', 'inventory', '压缩盒', '背包']),
    ('记忆 / 对话 / 语音', ['memory.', 'dialogue.', 'voice.', '记忆', '对话', '语音']),
    ('危险方块 / 避让', ['danger', 'hazard', '危险', '避让']),
    ('预设 / 调试', ['presetMode', '预设']),
]


def strip_comments(t):
    t = re.sub(r'/\*.*?\*/', ' ', t, flags=re.S)
    return re.sub(r'//[^\n]*', ' ', t)


def unesc(s):
    s = re.sub(r'\\u([0-9a-fA-F]{4})', lambda m: chr(int(m.group(1), 16)), s)
    s = s.replace('\\n', '\n').replace('\\t', '\t').replace('\\"', '"')
    return s.replace('\\\\', '\\')


def first_sentence(s, width=58):
    s = s.strip().split('\n')[0]
    for sep in ('：', '。', '；'):
        i = s.find(sep)
        if 0 < i <= width:
            return s[:i]
    return s[:width]


def parse_tree(tree):
    src = io.open(os.path.join(ROOT, tree, 'com/maidsmart/config/MaidSmartConfig.java'),
                  encoding='utf-8').read()
    body = strip_comments(src)
    pat = re.compile(r'\.push\s*\(\s*"([^"]+)"\s*\)|\.pop\s*\(\s*\)'
                     r'|([A-Z][A-Z0-9_]*)\s*=\s*BUILDER\b(.*?);', re.S)
    stack, rows = [], []
    for m in pat.finditer(body):
        if m.group(1) is not None:
            stack.append(m.group(1))
            continue
        if m.group(2) is None:
            if stack:
                stack.pop()
            continue
        stmt = m.group(3)
        d = re.search(r'\.define(List|InRange|Enum)?\s*\(\s*"([^"]+)"\s*,\s*(.*)', stmt, re.S)
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
        cm = re.search(r'\.comment\(\s*"((?:[^"\\]|\\.)*)"', stmt, re.S)
        note = first_sentence(unesc(cm.group(1))) if cm else ''
        path = ('.'.join(stack) + '.' + key) if stack else key
        rows.append({'path': path, 'key': key, 'field': m.group(2), 'type': typ,
                     'default': default, 'section': stack[0] if stack else '(root)',
                     'note': note})
    # 同一路径被定义两次的（如 broom.enable）按后定义者呈现，并单列出来
    seen, dups, uniq = {}, [], []
    for r in rows:
        if r['path'] in seen:
            dups.append(r['path'])
            uniq[seen[r['path']]] = r
        else:
            seen[r['path']] = len(uniq)
            uniq.append(r)
    return uniq, sorted(set(dups))


def count_dev_talk(tree):
    """可见文案里带轮次编号（实测NNN）的条数——TD-03 的进度表（只减不增）。"""
    total = 0
    for rel in ('com/maidsmart/config/MaidSmartConfig.java',
                'com/maidsmart/config/PromaidConfigScreen.java'):
        t = strip_comments(io.open(os.path.join(ROOT, tree, rel), encoding='utf-8').read())
        t = re.sub(r'"\s*\+\s*"', '', t)
        for lit in re.findall(r'"((?:[^"\\]|\\.)*)"', t):
            if re.search(r'实测\s*[零一二三四五六七八九十百千万0-9]', unesc(lit)):
                total += 1
    return total


def render():
    out = []
    out.append('# 配置项清单 / Switch Inventory（自动生成，勿手改）')
    out.append('')
    out.append('> 生成：`python tools/verify/gen_switch_inventory.py --write`　'
               '校验是否过期：`check_workflow.py`（会与重新生成的结果比对）')
    out.append('> 用途（`WORKFLOW.md` §3.1③）：**动手改一个行为之前**，先用 §2 主题索引看这个行为有几处开关、'
               '谁管谁、优先级如何；新加开关必须进这份清单。')
    out.append('')
    trees = {}
    for tree, label in TREES:
        trees[tree] = parse_tree(tree)[0]
    a, b = trees['promaid_src'], trees['promaid_src_neo']
    dups = parse_tree('promaid_src')[1]
    out.append('## 1. 规模')
    out.append('')
    out.append('| 树 | 配置项 | 布尔 | 节数 |')
    out.append('|---|---|---|---|')
    for tree, label in TREES:
        rows = trees[tree]
        out.append('| %s（%s） | %d | %d | %d |' % (
            tree, label, len(rows), sum(1 for r in rows if r['type'] == 'bool'),
            len(set(r['section'] for r in rows))))
    extra = sorted(set(r['path'] for r in b) - set(r['path'] for r in a))
    out.append('')
    out.append('同一路径被定义两次的（TOML 后者覆盖前者，见 `DEBT.md` TD-08）：%s' % (
        '、'.join('`%s`' % d for d in dups) if dups else '（无）'))
    out.append('Neo 树比 Forge 树多出的配置项（%d 个）：%s' % (
        len(extra), '、'.join('`%s`' % p for p in extra) if extra else '（无）'))
    out.append('')

    out.append('## 2. 主题索引（按行为聚合——改行为先看这里）')
    out.append('')
    for topic, keys in TOPICS:
        hits = [r for r in a if any(k in r['path'] or k in r['note'] for k in keys)]
        out.append('### %s　（%d 项）' % (topic, len(hits)))
        out.append('')
        if not hits:
            out.append('（无命中）')
            out.append('')
            continue
        out.append('| 配置键 | 类型 | 默认 | 作用（注释首句） |')
        out.append('|---|---|---|---|')
        for r in hits:
            out.append('| `%s` | %s | %s | %s |' % (r['path'], r['type'], r['default'],
                                                   r['note'].replace('|', '/')))
        out.append('')

    out.append('## 3. 按节分组（Forge 树全量）')
    out.append('')
    by_sec = {}
    for r in a:
        by_sec.setdefault(r['section'], []).append(r)
    for sec in sorted(by_sec):
        rows = by_sec[sec]
        out.append('### %s　（%d 项）' % (sec, len(rows)))
        out.append('')
        out.append('| 配置键 | 类型 | 默认 | 作用（注释首句） |')
        out.append('|---|---|---|---|')
        for r in rows:
            out.append('| `%s` | %s | %s | %s |' % (r['path'], r['type'], r['default'],
                                                   r['note'].replace('|', '/')))
        out.append('')

    out.append('## 4. 文案存量（`WORKFLOW.md` §6：只减不增）')
    out.append('')
    out.append('| 树 | 可见文案里带轮次编号（`实测NNN`）的条数 |')
    out.append('|---|---|')
    for tree, label in TREES:
        out.append('| %s | %d |' % (tree, count_dev_talk(tree)))
    out.append('')
    out.append('改动任何一条相关文案后，这个数只能变小或不变；新写的文案一律不带轮次编号。')
    out.append('')
    return '\n'.join(out)


def main():
    if '--write' in sys.argv:
        text = render()
        p = os.path.join(ROOT, OUT)
        io.open(p, 'w', encoding='utf-8', newline='\n').write(text)
        print('written %s（%d 行）' % (OUT, text.count('\n') + 1))
        return
    if '--grep' in sys.argv:
        kw = sys.argv[sys.argv.index('--grep') + 1]
        hits = [r for r in parse_tree('promaid_src')[0] if kw in r['path'] or kw in r['note']]
        print('命中 %d 项（关键词：%s）' % (len(hits), kw))
        for r in hits:
            print('  %-42s %-6s %-8s %s' % (r['path'], r['type'], r['default'], r['note'][:46]))
        return
    sys.stdout.write(render())


if __name__ == '__main__':
    main()
