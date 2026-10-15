# -*- coding: utf-8 -*-
"""气泡文案本地化护栏（R948）：女仆系统气泡的每一条中文都必须能在
`assets/promaid/i18n/en_us.json` 里查到译文（整串命中或可逐段拼装）。

用法: python tools/verify/check_bubble_i18n.py [--warn] [--dump]

为什么需要：气泡文案是**服务端发中文、客户端按玩家语言显示**（显示层翻译见
`com.maidsmart.i18n.PromaidI18n`：整串查表 + `compose` 逐段拼装，硬守卫"还剩汉字就整串放弃"）。
所以只要某条气泡的中文没进词条表，英文/俄文玩家就会在对话框里看到中文——
2026-10 玩家反馈「输出语言设了英文，偶尔还是中文」的一半来源就是它。

判据：把两树所有可能进气泡的中文取出来，逐条模拟 PromaidI18n.tr()：
  EXACT     整串命中 → 通过
  COMPOSED  逐段拼装后无汉字残留 → 通过
  GAP       查不到 / 拼装后仍有汉字没覆盖 → FAIL（列出没被覆盖的汉字）
"""
import io
import json
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
TREES = ['promaid_src', 'promaid_src_neo']

CJK = re.compile('[\u4e00-\u9fff\u3000-\u303f\uff00-\uffef]')
HAN = re.compile('[\u4e00-\u9fff\u3400-\u4dbf]')
MIN_SEGMENT = 2

# 间接进气泡的中文：这些文件里的台词池 / 拒绝原因 / 返回值会被原样传进
# getChatBubbleManager().addTextChatBubble(...)（R948 排查结论，新增来源请补进来）。
PRODUCERS = [
    'com/maidsmart/task/ScheduleBubbleBehavior.java',      # TEXTS 闲聊池（50 条）
    'com/maidsmart/emotion/EmotionNetworking.java',        # 抱抱/摸头台词
    'com/maidsmart/dialogue/WorkStatusReporter.java',      # 卡住原因
    'com/maidsmart/dialogue/PerceptionManager.java',       # 感知播报
    'com/maidsmart/combat/MaidFlightKit.java',             # 缺件清单
    'com/maidsmart/combat/MaidRideKit.java',               # 骑乘拒绝原因
    'com/maidsmart/combat/MaidMountCompat.java',           # 载具拒绝原因
    'com/maidsmart/combat/GunnerTetherManager.java',       # 拴绳对话
    'com/maidsmart/combat/RideBindManager.java',           # 骑乘对话
    'com/maidsmart/task/MaidWorkBehavior.java',            # 需要什么工具
    'com/maidsmart/tool/AirRaidTool.java',
    'com/maidsmart/tool/SwitchTaskTool.java',
    'com/maidsmart/tool/WorkAreaTool.java',
]

FAILS, WARNS = [], []


def unesc(s):
    return re.sub(r'\\u([0-9a-fA-F]{4})', lambda m: chr(int(m.group(1), 16)), s)


class Table(object):
    def __init__(self, path):
        self.raw = json.load(io.open(path, encoding='utf-8'))
        by_first = {}
        for k in self.raw:
            if len(k) < MIN_SEGMENT or not CJK.search(k):
                continue
            by_first.setdefault(k[0], []).append(k)
        for k in by_first:
            by_first[k].sort(key=len, reverse=True)  # 最长匹配
        self.by_first = by_first

    def verdict(self, s):
        if s in self.raw:
            return 'EXACT', ''
        i, covered = 0, set()
        while i < len(s):
            hit = None
            for k in self.by_first.get(s[i], ()):
                if s.startswith(k, i):
                    hit = k
                    break
            if hit:
                covered.update(range(i, i + len(hit)))
                i += len(hit)
            else:
                i += 1
        gap = ''.join(s[j] for j in range(len(s)) if j not in covered and HAN.match(s[j]))
        return ('COMPOSED', '') if not gap else ('GAP', gap)


def strip_comments(text):
    text = re.sub(r'/\*.*?\*/', ' ', text, flags=re.S)
    return re.sub(r'//[^\n]*', ' ', text)


def bubble_strings(tree):
    """(中文串, 出处) —— 直接进气泡的调用参数 + 间接来源里的中文。"""
    out = {}
    for dp, _, fn in os.walk(os.path.join(ROOT, tree)):
        for f in sorted(fn):
            if not f.endswith('.java'):
                continue
            p = os.path.join(dp, f)
            rel = os.path.relpath(p, ROOT).replace(os.sep, '/')
            src = unesc(io.open(p, encoding='utf-8', errors='ignore').read())
            code = strip_comments(src)
            lines = code.split('\n')
            for i, l in enumerate(lines):
                if not re.search(r'\.addTextChatBubble\(|\.addChatBubble\(|\.addLLMChatText\(', l):
                    continue
                buf, depth, j = l, l.count('(') - l.count(')'), i
                while depth > 0 and j - i < 8 and j + 1 < len(lines):
                    j += 1
                    buf += '\n' + lines[j]
                    depth += lines[j].count('(') - lines[j].count(')')
                for lit in re.findall(r'"((?:[^"\\]|\\.)*)"', buf):
                    if CJK.search(lit):
                        out.setdefault(lit, rel)
    for rel0 in PRODUCERS:
        p = os.path.join(ROOT, tree, rel0)
        if not os.path.isfile(p):
            WARNS.append('%s: 缺少间接来源文件 %s' % (tree, rel0))
            continue
        t = strip_comments(unesc(io.open(p, encoding='utf-8', errors='ignore').read()))
        for pat in (r'=\s*\{(.*?)\};', r'(?:return|\?\s*|say\(|deny\()\s*"((?:[^"\\]|\\.)*)"'):
            for m in re.finditer(pat, t, re.S):
                body = m.group(1)
                for lit in re.findall(r'"((?:[^"\\]|\\.)*)"', body):
                    if CJK.search(lit) and len(lit) > 1:
                        out.setdefault(lit, rel0)
    return out


def main():
    dump = '--dump' in sys.argv
    for tree in TREES:
        tbl_path = os.path.join(ROOT, tree, 'assets/promaid/i18n/en_us.json')
        if not os.path.isfile(tbl_path):
            FAILS.append('%s: 找不到词条表 %s' % (tree, tbl_path))
            continue
        tbl = Table(tbl_path)
        items = bubble_strings(tree)
        gaps = []
        for s, where in sorted(items.items()):
            v, gap = tbl.verdict(s)
            if v == 'GAP':
                gaps.append((s, gap, where))
        print('--- %s：气泡中文 %d 条，缺译文 %d 条（词条表 %d 条）'
              % (tree, len(items), len(gaps), len(tbl.raw)))
        for s, gap, where in gaps:
            FAILS.append('%s: 气泡缺译文（没覆盖：%s）:: %s ← %s' % (tree, gap, s[:70], where))
            if dump:
                print('      GAP %s :: %s' % (gap, s[:90]))
    print()
    for w in WARNS:
        print('WARN ' + w)
    for f in FAILS:
        print('FAIL ' + f)
    print('check_bubble_i18n: FAIL %d / WARN %d' % (len(FAILS), len(WARNS)))
    if FAILS and '--warn' not in sys.argv:
        sys.exit(1)


if __name__ == '__main__':
    main()
