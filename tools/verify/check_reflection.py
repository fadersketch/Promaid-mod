# -*- coding: utf-8 -*-
"""反射用量棘轮护栏（TD-15）：把"探针式反射"的存量钉在基线上，只减不增。

用法: python tools/verify/check_reflection.py [--warn] [--measure]

判定：剥注释后按树统计四处探针标记的出现次数——`Class.forName(` / `getMethod(` /
`getDeclaredMethod(` / `getDeclaredField(`。
  - 高于基线 → FAIL（新写的探针式反射会静默堆积；要新增就先把别处收敛掉，或显式上调基线并留痕）；
  - 低于基线 → WARN（可顺势下调，棘轮只往一个方向走）。

口径见 DEBT.md TD-15：按模组把探针式反射收敛成"兼容类 + ModList 在场判断"，每 tick 路径优先。
基线 = 实测八百八十八 首量（注释剥离后）。
"""
import io
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
TREES = ['promaid_src', 'promaid_src_neo']
MARKS = ['Class.forName(', 'getMethod(', 'getDeclaredMethod(', 'getDeclaredField(']

BASELINE = {
    # 实测九百九十八：TNT 矿车引信直写（MinecartTNT 的私有 fuse 字段，两版都没有写口）
    # 各新增 1 处 getDeclaredField → 7/7 上调为 8/8（显式上调并留痕，见 TD-15）。
    'promaid_src': {'Class.forName(': 86, 'getMethod(': 204, 'getDeclaredMethod(': 6, 'getDeclaredField(': 8},
    'promaid_src_neo': {'Class.forName(': 82, 'getMethod(': 203, 'getDeclaredMethod(': 5, 'getDeclaredField(': 8},
}

FAILS, WARNS = [], []


def strip_comments(t):
    t = re.sub(r'/\*.*?\*/', ' ', t, flags=re.S)
    return re.sub(r'//[^\n]*', ' ', t)


def counts(tree):
    c = {m: 0 for m in MARKS}
    for dp, dn, fn in os.walk(os.path.join(ROOT, tree)):
        for f in fn:
            if not f.endswith('.java'):
                continue
            t = strip_comments(io.open(os.path.join(dp, f), encoding='utf-8', errors='ignore').read())
            for m in MARKS:
                c[m] += t.count(m)
    return c


def main():
    measure = '--measure' in sys.argv
    for tree in TREES:
        c = counts(tree)
        print('--- %s：%s' % (tree, {k: c[k] for k in MARKS}))
        if measure:
            continue
        base = BASELINE[tree]
        for m in MARKS:
            if c[m] > base[m]:
                FAILS.append('%s: %s %d > 基线 %d —— 新增了探针式反射，请收敛或上调基线（见 TD-15）'
                             % (tree, m, c[m], base[m]))
            elif c[m] < base[m]:
                WARNS.append('%s: %s %d < 基线 %d —— 可把基线往下调（棘轮只减不增）'
                             % (tree, m, c[m], base[m]))
    if measure:
        return
    print()
    for w in WARNS:
        print('WARN ' + w)
    for f in FAILS:
        print('FAIL ' + f)
    print('check_reflection: FAIL %d / WARN %d' % (len(FAILS), len(WARNS)))
    if FAILS and '--warn' not in sys.argv:
        sys.exit(1)


if __name__ == '__main__':
    main()
