# -*- coding: utf-8 -*-
"""生成 assets/promaid/panel_coverage.txt：面板源码里已手工收录的配置字段名清单。

「已收录」口径 = 该字段名在 PromaidConfigScreen.java 里出现过（无论 get/set/辅助函数）。
新板块 extraRows() 靠这份清单把「已收录的」排除掉，只列其余项。

改过面板的手工行之后要重跑本脚本（tools/verify/check_panel_coverage.py 会提示陈旧）。
用法: python tools/build/gen_panel_coverage.py
"""
import os
import re
import sys

TREES = ['promaid_src', 'promaid_src_neo']


def repo_root(start):
    d = os.path.dirname(os.path.abspath(start))
    while True:
        if os.path.isdir(os.path.join(d, '.git')) or os.path.isfile(os.path.join(d, '.git')):
            return d
        p = os.path.dirname(d)
        if p == d:
            return os.path.dirname(os.path.abspath(start))
        d = p


ROOT = repo_root(__file__)
for tree in TREES:
    panel = os.path.join(ROOT, tree, 'com', 'maidsmart', 'config', 'PromaidConfigScreen.java')
    src = open(panel, encoding='utf-8').read()
    fields = sorted(set(re.findall(r'MaidSmartConfig\.([A-Z][A-Z0-9_]*)', src)))
    out = os.path.join(ROOT, tree, 'assets', 'promaid', 'panel_coverage.txt')
    os.makedirs(os.path.dirname(out), exist_ok=True)
    with open(out, 'w', encoding='utf-8', newline='\n') as f:
        f.write('# 面板已收录的配置字段（生成物，勿手改；由 tools/build/gen_panel_coverage.py 生成）\n')
        for x in fields:
            f.write(x + '\n')
    print('%s: %d covered fields -> %s' % (tree, len(fields), out))
print('DONE')
