# -*- coding: utf-8 -*-
"""i18n 键漂移棘轮护栏（TD-07）：把"源码里有 .translation("k")、lang 表里没有 k"的存量钉在基线上，只减不增。

用法: python tools/verify/check_i18n_keys.py [--warn] [--dump]

判定：
  - 从 `MaidSmartConfig.java` 抓全部 `.translation("k")` 字面量（**先剔注释**，免得 javadoc 里的
    `translation("config.promaid.*")` 这种示例被当成真键）；
  - 逐个查 `assets/maid_smart/lang/{zh_cn,en_us}.json` 里有没有这条 key；
  - 高于基线 → FAIL（新增了"有 key 无译文"的选项——英文界面会回落中文/键名）；
  - 低于基线 → WARN（可以顺势把基线往下调，棘轮只往一个方向走）。

修法见 TECH_DEBT TD-07：按节把缺的 key 补进两树 lang（zh 取同项 `.comment()` 原文，en 写英文）。
"""
import io
import json
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
TREES = ['promaid_src', 'promaid_src_neo']
LANGS = ['zh_cn', 'en_us']
# 基线 = 现存漂移（只减不增）。实测八百八十一 首量：两树 zh 184 / en 182；
# 实测八百八十四 补齐（自动补全页用的 config.promaid.* 短名）→ **0 / 0**：此后新配置项必须带译文。
BASELINE = {
    'promaid_src': {'zh_cn': 0, 'en_us': 0},
    'promaid_src_neo': {'zh_cn': 0, 'en_us': 0},
}

FAILS, WARNS = [], []


def strip_comments(text):
    text = re.sub(r'/\*.*?\*/', ' ', text, flags=re.S)
    return re.sub(r'//[^\n]*', ' ', text)


def config_keys(tree):
    p = os.path.join(ROOT, tree, 'com/maidsmart/config/MaidSmartConfig.java')
    t = strip_comments(io.open(p, encoding='utf-8', errors='ignore').read())
    return sorted(set(re.findall(r'\.translation\("([^"]+)"\)', t)))


def main():
    dump = '--dump' in sys.argv
    for tree in TREES:
        keys = config_keys(tree)
        for lang in LANGS:
            p = os.path.join(ROOT, tree, 'assets/maid_smart/lang/%s.json' % lang)
            d = json.load(io.open(p, encoding='utf-8'))
            miss = [k for k in keys if k not in d]
            n = len(miss)
            base = BASELINE.get(tree, {}).get(lang)
            print('--- %s / %s：有 key 无译文 %d 处（config 键 %d）' % (tree, lang, n, len(keys)))
            if base is None:
                WARNS.append('%s/%s: 没有基线（脚本与源码对不上了）' % (tree, lang))
            elif n > base:
                FAILS.append('%s/%s: 有 key 无译文 %d > 基线 %d —— 新增了缺译文的配置项，请补 lang（见 TD-07）'
                             % (tree, lang, n, base))
            elif n < base:
                WARNS.append('%s/%s: 有 key 无译文 %d < 基线 %d —— 可以把基线往下调（棘轮只减不增）'
                             % (tree, lang, n, base))
            if dump:
                for k in miss[:40]:
                    print('      MISS', k)
    print()
    for w in WARNS:
        print('WARN ' + w)
    for f in FAILS:
        print('FAIL ' + f)
    print('check_i18n_keys: FAIL %d / WARN %d' % (len(FAILS), len(WARNS)))
    if FAILS and '--warn' not in sys.argv:
        sys.exit(1)


if __name__ == '__main__':
    main()
