# -*- coding: utf-8 -*-
"""配置注释照抄棘轮护栏（TD-02）：把"复制出来但没改口径"的 `.comment()` 正文钉在基线上。

用法: python tools/verify/check_comment_dup.py [--warn] [--dump]

判定：
  - 从两树全部 `.java` 剥注释后抓 `.comment("…")` 正文，去掉所有空白后归一化；
  - 归一化正文相同的算一组"重复"；
  - 白名单 = TD-02 已裁定的 4 条**共用机制**（挖矿/伐木两套本就同口径，有意保留）；
  - 出现**不在白名单**的重复组 → FAIL（新复制了一对没改口径的注释）。

修法见 TECH_DEBT TD-02：逐对改写成各自口径。
"""
import io
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
TREES = ['promaid_src', 'promaid_src_neo']

# TD-02 裁定为"有意保留"的共用机制（MINE_/WOOD_ 同后缀、本就同一套口径）。
WHITELIST = {
    u'搭方块防掉落（潜行效果，速度不变）',
    u'重定位节流（tick，防边界抖动）',
    u'搭方块冷却（tick，垫脚下/搭路节奏）',
    u'废石清理检查间隔（tick）',
}

FAILS, WARNS = [], []


def strip_comments(t):
    t = re.sub(r'/\*.*?\*/', ' ', t, flags=re.S)
    return re.sub(r'//[^\n]*', ' ', t)


def comments(tree):
    out = []
    for dp, dn, fn in os.walk(os.path.join(ROOT, tree)):
        for f in sorted(fn):
            if not f.endswith('.java'):
                continue
            t = strip_comments(io.open(os.path.join(dp, f), encoding='utf-8', errors='ignore').read())
            for c in re.findall(r'\.comment\("((?:[^"\\]|\\.)*)"\)', t):
                out.append(c)
    return out


def main():
    dump = '--dump' in sys.argv
    for tree in TREES:
        norms = {}
        for c in comments(tree):
            norms.setdefault(re.sub(r'\s+', '', c), []).append(c)
        dups = {k: v for k, v in norms.items() if len(v) > 1}
        bad = [k for k in dups if k not in WHITELIST]
        print('--- %s：comment %d 条 / 重复组 %d / 其中非白名单 %d' % (tree, len(comments(tree)), len(dups), len(bad)))
        for k in bad:
            FAILS.append('%s: 非白名单重复注释（x%d）：%s' % (tree, len(dups[k]), k[:60]))
        if len(dups) < len(WHITELIST):
            WARNS.append('%s: 重复组 %d < 白名单 %d —— 有白名单注释已被去重，可下调白名单'
                         % (tree, len(dups), len(WHITELIST)))
        if dump:
            for k, v in dups.items():
                print('      x%d %s%s' % (len(v), '' if k in WHITELIST else '[NEW] ', k[:60]))
    print()
    for w in WARNS:
        print('WARN ' + w)
    for f in FAILS:
        print('FAIL ' + f)
    print('check_comment_dup: FAIL %d / WARN %d' % (len(FAILS), len(WARNS)))
    if FAILS and '--warn' not in sys.argv:
        sys.exit(1)


if __name__ == '__main__':
    main()
