# -*- coding: utf-8 -*-
"""吞异常棘轮护栏（实测八百七十一）：把"静默吞异常"的存量钉在基线上，只减不增。

用法: python tools/verify/check_catch_swallow.py [--warn] [--dump]

判定（只数注释之外的**未留痕**吞异常）：
  - 计数 = 两种都算：
      A) `catch (Throwable|Exception ignored) {` 且紧跟的不是 `com.maidsmart.diag.Diag.swallow(`；
      B) **具名**且体是 `return <值>;` / 纯注释 / 空的：`catch (… <名字>) { return false; }` 这类把异常
         直接翻译成正常值的写法（WORKFLOW §2.4 反面样例），名字不是 ignored 也照样是"日志一片空白"。
  - 高于基线 → FAIL（新增了静默吞异常 —— 正是 issue #37 / #38 点名的"日志一片空白"）；
  - 低于基线 → WARN（可以顺势把基线往下调，棘轮只往一个方向走）；
  - --dump 打印每文件计数，便于下一轮挑最肥的先烧。

修法见 TECH_DEBT TD-14 / TD-18：热点路径改成 `Diag.swallow("类名", ignored)`（保留 catch，只加一条留痕）。
"""
import io
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
TREES = ['promaid_src', 'promaid_src_neo']
# 未留痕的静默吞异常（注释先剔除，避免 javadoc 里的示例被算进去）
SILENT = re.compile(
    r'catch\s*\(\s*(?:Throwable|Exception)\s+ignored\s*\)\s*\{(?!\s*com\.maidsmart\.diag\.Diag\.swallow\()')
# 具名静默（TD-18）：体是 `return <值>;` / 纯注释 / 空 —— 名字不是 ignored 也照样把异常翻译成正常值。
SILENT_NAMED = re.compile(
    r'catch\s*\(\s*(?:Throwable|Exception)\s+(?!ignored\b)\w+\s*\)\s*\{\s*'
    r'(?:return\s+[^;{}]*;|//[^\n]*|\})')
# 基线 = 已烧掉的族之后的存量（只减不增）。实测八百七十一 烧骑乘/载具探针一族（412/树）→ 1469/1477；
# 实测八百七十二 烧 >=20 档热点族（442/439）→ 1027/1038；实测八百七十三 烧 10..19 档（334/333）→ 693/705；
# 实测八百七十四 清尾（>0 档全烧，1398 处）→ A 归零；实测八百七十五 烧具名静默（B 档，191 文件 · 483 处）
# → **0/0**：此后新代码不许再出现裸的 `catch (... ignored)` 或具名 `{ return …; }` 静默
# （要吞就写 `Diag.swallow("类名", 变量)`，正则把紧跟其后的算作已留痕）。
BASELINE = {'promaid_src': 0, 'promaid_src_neo': 0}

FAILS, WARNS = [], []


def strip_comments(text):
    text = re.sub(r'/\*.*?\*/', ' ', text, flags=re.S)
    return re.sub(r'//[^\n]*', ' ', text)


def scan(tree):
    per_file = {}
    total = 0
    named = 0
    for r, _, fs in os.walk(os.path.join(ROOT, tree)):
        for f in fs:
            if not f.endswith('.java'):
                continue
            p = os.path.join(r, f)
            t = strip_comments(io.open(p, encoding='utf-8', errors='ignore').read())
            n = len(SILENT.findall(t))
            nn = len(SILENT_NAMED.findall(t))
            if n or nn:
                rel = os.path.relpath(p, os.path.join(ROOT, tree)).replace('\\', '/')
                per_file[rel] = n + nn
                total += n + nn
                named += nn
    return total, per_file, named


def main():
    dump = '--dump' in sys.argv
    for tree in TREES:
        total, per_file, named = scan(tree)
        print('--- %s：未留痕吞异常 %d 处（%d 个文件；其中具名静默 %d 处）'
              % (tree, total, len(per_file), named))
        base = BASELINE.get(tree)
        if base is None:
            WARNS.append('%s: 没有基线（脚本与源码对不上了）' % tree)
        elif total > base:
            FAILS.append('%s: 未留痕吞异常 %d > 基线 %d —— 新增了静默吞异常，请改用 Diag.swallow'
                         '（见 TECH_DEBT TD-14）' % (tree, total, base))
        elif total < base:
            WARNS.append('%s: 未留痕吞异常 %d < 基线 %d —— 可以把基线往下调（棘轮只减不增）'
                         % (tree, total, base))
        if dump:
            for p, n in sorted(per_file.items(), key=lambda kv: -kv[1])[:25]:
                print('    %4d  %s' % (n, p))
    print()
    for w in WARNS:
        print('WARN ' + w)
    for f in FAILS:
        print('FAIL ' + f)
    print('check_catch_swallow: FAIL %d / WARN %d' % (len(FAILS), len(WARNS)))
    if FAILS and '--warn' not in sys.argv:
        sys.exit(1)


if __name__ == '__main__':
    main()
