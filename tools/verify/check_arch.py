# -*- coding: utf-8 -*-
"""架构结构数据门禁：让 tools/arch/ 的产物"只许来自真源"，并盯住它别烂掉。

用法: python tools/verify/check_arch.py [--warn] [--measure]

背景：`normify-promaid/` 里的结构数据（模块树 / API / 依赖箭头 / 交互图）**是一次性生成物**，
唯一真源是 `tools/arch/arch_modules.py`（手写数据）+ `gen_arch.py`（生成器）。
手改产物 = 下一轮 `build_arch.py` 一跑就静默蒸发；改了真源忘记重跑 = 图与代码各说各话。
本闸把这两件事卡住，并把"覆盖缺口"钉在基线上（棘轮只减不增）。

检查项（FAIL 以退出码 1 结束；--warn 只打印不阻断）：
  1. 一致（FAIL）：把真源重新生成到临时目录，与 `normify-promaid/` 逐字节比对。
     只抹平 revision 哈希（它是"最后一次改动源码树的提交"，随提交自然变），其余一个字节都不许差；
     并反向检查产物里有没有生成器根本不会写的文件（手塞进来的）。
  2. 覆盖棘轮（FAIL/WARN）：两棵树里不被任何模块 `source` 覆盖的 .java 数量，钉在基线上。
     整个新包不登记 → 数量上涨 → FAIL。
  3. 陈旧（WARN）：产物冻结的 revision 落后于源码树最后一次提交 → 该重跑了。
  4. 文档漂移（WARN）：ARCHITECTURE.md 的「修订 / 规模」与 tree.json 不一致。

基线口径（实测九百〇八 首量）：`promaid_src` 的根模块 source 是整目录 → 覆盖 100%、基线 0；
`promaid_src_neo` 按包登记"代表文件"（每模块 1~3 个主文件），故存量有 181 个次要文件不在 source 里。
新增文件若落在**已覆盖的包**里通常无害（包已在图上）；本闸真正要拦的是**整包不登记**。
"""
import io
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
ARCH = os.path.join(ROOT, 'normify-promaid')
# 顺序与 gen_arch.py 一致（它取"最后一次改动这些路径的提交"）
TREES = ['promaid_src_neo', 'promaid_src']
# 未被任何模块 source 覆盖的 .java 条数基线（只减不增）
# 实测一千〇一十五：装备消失定位用的四个临时诊断文件已随验收删除 → 185 回到 181。
# R1113：巡逻航图目标点新增 PatrolTarget / MaidTravelTarget 两个同包文件（patrol 包本身
# 未在图上登记为模块，按"代表文件"口径属存量次要文件）→ 181 → 183。
# R1114：新增 /set goal 的 PatrolGoalCommand（command 包同为存量次要文件）→ 183 → 184。
BASELINE_UNCOVERED = {'promaid_src_neo': 184, 'promaid_src': 0}
REV_RE = re.compile(rb'\b[0-9a-f]{40}\b')
MAXSHOW = 8

FAILS, WARNS = [], []


def read(p):
    with open(p, 'rb') as f:
        return f.read()


def norm(b):
    """比对前抹平 revision 哈希：它随"源码树最后一次提交"变，不属于数据内容。"""
    return REV_RE.sub(b'<REV>', b)


def tree_json():
    with io.open(os.path.join(ARCH, 'tree.json'), encoding='utf-8') as f:
        return json.load(f)


# ---------------------------------------------------------------- 1. 一致
def check_consistent():
    tmp = tempfile.mkdtemp(prefix='normify_check_')
    try:
        p = subprocess.run([sys.executable, os.path.join(ROOT, 'tools/arch/gen_arch.py'), '--out', tmp],
                           cwd=ROOT, capture_output=True, text=True, encoding='utf-8', errors='replace')
        if p.returncode != 0:
            tail = ((p.stdout or '') + (p.stderr or '')).strip().splitlines()
            FAILS.append('真源重新生成失败（gen_arch rc=%d）：%s —— 先跑 python tools/arch/build_arch.py 看报错'
                         % (p.returncode, tail[-1][:160] if tail else '(无输出)'))
            return
        gen = set()
        for dp, dn, fn in os.walk(tmp):
            for f in fn:
                gen.add(os.path.relpath(os.path.join(dp, f), tmp).replace('\\', '/'))
        gen.discard('receipt.json')   # finalize_arch.py 在渲染后重算，不参与比对
        bad = []
        for rel in sorted(gen):
            b = os.path.join(ARCH, rel)
            if not os.path.isfile(b):
                bad.append('缺文件 normify-promaid/%s' % rel)
            elif norm(read(os.path.join(tmp, rel))) != norm(read(b)):
                bad.append('内容不一致 normify-promaid/%s' % rel)
        for dp, dn, fn in os.walk(ARCH):
            for f in fn:
                rel = os.path.relpath(os.path.join(dp, f), ARCH).replace('\\', '/')
                if rel in ('receipt.json', 'normify.html'):
                    continue          # normify.html 由 render_arch.py 写、finalize 后冻结
                if rel not in gen:
                    bad.append('多出文件 normify-promaid/%s' % rel)
        print('--- 一致：比对 %d 个产物文件，差异 %d' % (len(gen), len(bad)))
        for d in bad[:MAXSHOW]:
            FAILS.append('%s —— 结构数据只许由 tools/arch/ 生成（手改产物会被下一轮 build_arch.py 抹掉；'
                         '改了 arch_modules.py 就重跑）' % d)
        if len(bad) > MAXSHOW:
            FAILS.append('…… 另有 %d 个文件有差异（跑 --measure 看覆盖，产物差异按上面同类处理）'
                         % (len(bad) - MAXSHOW))
    finally:
        shutil.rmtree(tmp, ignore_errors=True)


# ------------------------------------------------------------ 2. 覆盖棘轮
def check_coverage(measure):
    with io.open(os.path.join(ARCH, 'sidecar.json'), encoding='utf-8') as f:
        side = json.load(f)
    for tree in TREES:
        srcs = [s[0].rstrip('/') for v in side.values() for s in v.get('sources', [])
                if s[0].split('/')[0] == tree]
        files = []
        for dp, dn, fn in os.walk(os.path.join(ROOT, tree)):
            for f in fn:
                if f.endswith('.java'):
                    files.append(os.path.relpath(os.path.join(dp, f), ROOT).replace('\\', '/'))
        uncov = [f for f in files if not any(f == s or f.startswith(s + '/') for s in srcs)]
        base = BASELINE_UNCOVERED[tree]
        print('--- %s：.java %d，未被任何模块 source 覆盖 %d（基线 %d）' % (tree, len(files), len(uncov), base))
        if measure:
            for f in uncov[:40]:
                print('      %s' % f)
            if len(uncov) > 40:
                print('      …… 另有 %d 个' % (len(uncov) - 40))
            continue
        if len(uncov) > base:
            for f in uncov[:12]:
                print('        %s' % f)
            if len(uncov) > 12:
                print('        …… 另有 %d 个' % (len(uncov) - 12))
            FAILS.append('%s：未覆盖 .java %d > 基线 %d —— 有文件（多半是整个新包）没登记进任何模块的 source；'
                         '把它写进对应模块的 source，或显式上调 BASELINE_UNCOVERED 并留痕（棘轮只减不增）'
                         % (tree, len(uncov), base))
        elif len(uncov) < base:
            WARNS.append('%s：未覆盖 .java %d < 基线 %d —— 可把 BASELINE_UNCOVERED 往下调'
                         % (tree, len(uncov), base))


# -------------------------------------------------------------- 3. 陈旧
def check_stale():
    rev = tree_json().get('revision', '')
    r = subprocess.run(['git', 'log', '-1', '--format=%H', '--'] + TREES, cwd=ROOT,
                       capture_output=True, text=True, encoding='utf-8', errors='replace')
    src = (r.stdout or '').strip()
    print('--- 修订：产物 %s / 源码树最后提交 %s' % (rev[:12], src[:12] or '(取不到)'))
    if src and rev != src:
        WARNS.append('结构数据陈旧：源码树已提交到 %s，结构数据冻结在 %s —— 跑 python tools/arch/build_arch.py 同步'
                     % (src[:12], rev[:12]))


# --------------------------------------------------------- 4. 文档漂移
def check_doc():
    s = tree_json()['stats']
    rev = tree_json().get('revision', '')
    p = os.path.join(ROOT, 'ARCHITECTURE.md')
    if not os.path.isfile(p):
        WARNS.append('找不到 ARCHITECTURE.md（结构数据的入口文档），统计无从核对')
        return
    txt = io.open(p, encoding='utf-8').read()
    m = re.search(r'\*\*规模\*\*：(\d+) 模块 / (\d+) API / (\d+) 依赖箭头 / 最大深度 (\d+)', txt)
    real = (s['modules'], s['apis'], s['edges'], s['depth'])
    if not m:
        WARNS.append('ARCHITECTURE.md 找不到「**规模**：N 模块 / N API / N 依赖箭头 / 最大深度 N」统计行，无法核对')
    else:
        doc = tuple(int(x) for x in m.groups())
        print('--- 文档「规模」：%d 模块 / %d API / %d 箭头 / 深度 %d；tree.json：%d / %d / %d / %d'
              % (doc + real))
        if doc != real:
            WARNS.append('ARCHITECTURE.md 的「规模」是 %d 模块 / %d API / %d 箭头 / 深度 %d，tree.json 是 '
                         '%d 模块 / %d API / %d 箭头 / 深度 %d —— 结构变了就顺手改这两行'
                         % (doc + real))
    mr = re.search(r'\*\*修订\*\*：`([0-9a-f]{7,40})`', txt)
    if mr and not rev.startswith(mr.group(1)):
        WARNS.append('ARCHITECTURE.md 的「修订」%s 与 tree.json 的 %s 不符' % (mr.group(1), rev[:12]))


def main():
    measure = '--measure' in sys.argv
    if not measure and not os.path.isdir(ARCH):
        print('FAIL 找不到 %s —— 先跑 python tools/arch/build_arch.py 生成结构数据' % ARCH)
        sys.exit(1)
    check_coverage(measure)
    if measure:
        return
    check_consistent()
    check_stale()
    check_doc()
    print()
    for w in WARNS:
        print('WARN ' + w)
    for f in FAILS:
        print('FAIL ' + f)
    print('check_arch: FAIL %d / WARN %d' % (len(FAILS), len(WARNS)))
    if FAILS and '--warn' not in sys.argv:
        sys.exit(1)


if __name__ == '__main__':
    main()
