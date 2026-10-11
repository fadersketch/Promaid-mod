# -*- coding: utf-8 -*-
"""面板覆盖护栏（实测八百六十五）：保证「所有 toml 项都在手册里有对应开关」。

面板小类「其余开关（自动）」靠 assets/promaid/panel_coverage.txt（= 手工面板已收录的字段名）
把已收录项排除掉，只列其余的。这份清单是**生成物**，改了面板手工行就会陈旧——本脚本负责：

  1. 重生成一遍，与仓库里的文件逐行比对（陈旧即 FAIL，提示重跑 gen_panel_coverage.py）；
  2. 清单里每个名字都必须真的出现在面板源码里（防手改出错项）；
  3. 汇总：声明字段数 / 已收录数 / 未收录数（= 自动页会列出的真实项数，扣掉 *_MIGRATED）。

用法: python tools/verify/check_panel_coverage.py [--warn]
"""
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
TREES = [('promaid_src', 'Forge'), ('promaid_src_neo', 'Neo')]
FAILS = []
WARNS = []


def fail(m):
    FAILS.append(m)
    print('FAIL ' + m)


def warn(m):
    WARNS.append(m)
    print('WARN ' + m)


def covered_from_source(tree):
    panel = os.path.join(ROOT, tree, 'com', 'maidsmart', 'config', 'PromaidConfigScreen.java')
    src = open(panel, encoding='utf-8').read()
    return sorted(set(re.findall(r'MaidSmartConfig\.([A-Z][A-Z0-9_]*)', src)))


def declared(tree):
    cfg = open(os.path.join(ROOT, tree, 'com', 'maidsmart', 'config', 'MaidSmartConfig.java'),
               encoding='utf-8').read()
    # 类型可能带泛型（ConfigValue<List<? extends String>> 等），故泛型段整体跳过
    return sorted(set(re.findall(
        r'public static final [\w.]+(?:<[^;=]*>)?\s+([A-Z][A-Z0-9_]+)\s*;', cfg)))


for tree, label in TREES:
    res = os.path.join(ROOT, tree, 'assets', 'promaid', 'panel_coverage.txt')
    if not os.path.isfile(res):
        fail('%s: 缺少 %s（跑 tools/build/gen_panel_coverage.py）' % (label, res))
        continue
    committed = [l.strip() for l in open(res, encoding='utf-8').read().splitlines()
                 if l.strip() and not l.strip().startswith('#')]
    fresh = covered_from_source(tree)
    if committed != fresh:
        only_old = sorted(set(committed) - set(fresh))
        only_new = sorted(set(fresh) - set(committed))
        fail('%s: panel_coverage.txt 陈旧——重跑 gen_panel_coverage.py（多出 %s / 缺少 %s）'
             % (label, only_old[:5], only_new[:5]))
    declared_set = set(declared(tree))
    bogus = [c for c in committed if c not in declared_set]
    if bogus:
        fail('%s: 覆盖清单里的 %s 不是 MaidSmartConfig 的声明字段' % (label, bogus[:5]))
    uncovered = [d for d in declared(tree) if d not in set(committed) and not d.endswith('_MIGRATED')]
    migrated = [d for d in declared(tree) if d.endswith('_MIGRATED')]
    print('%s: 声明 %d / 已收录 %d / 未收录（自动页列出）%d / 迁移标记跳过 %d'
          % (label, len(declared_set), len(committed), len(uncovered), len(migrated)))

print('check_panel_coverage: FAIL %d / WARN %d' % (len(FAILS), len(WARNS)))
if FAILS and '--warn' not in sys.argv:
    sys.exit(1)
