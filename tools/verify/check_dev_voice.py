# -*- coding: utf-8 -*-
"""开发口吻 / 开发过程棘轮护栏（TD-03）：把"可见文案里残留的开发过程与第一人称复数口吻"钉在基线上，只减不增。

用法: python tools/verify/check_dev_voice.py [--warn] [--dump]

判据（分三个面，全部只减不增）：
  ① 英文 value（两树 `assets/promaid/i18n/en_us.json` 与 `assets/maid_smart/lang/en_us.json` 的 value）：
     第一人称复数 `we/our/us/ours/ourselves`，以及自测轮次号 `实测` / `Test NNN`。
  ② 源码可见正文：两树全部 `.java` **剥注释后**的 `.comment("…")` 正文（进 TOML，玩家可见）。
  ③ 中文键与可见中文：两树 `assets/promaid/i18n/en_us.json` 与 `assets/maid_smart/lang/{zh_cn,en_us}.json`
     的 **key**，以及 `maid_smart` 两语的 **value**（配置标签）。
     ②③ 判据 = 开发者标记 `实测` / `[四五六七八九]百`（中文数字百位轮次号）/ `把 <数字> 误当`。
     **不含** `第N次`——`第一次`/`第二次` 是正常中文（实测有正当用法）。

高于基线 → FAIL；低于基线 → WARN（可顺势下调）。基线全 0。
注：源码注释里的「出处一行」（`// 实测NNN：…`）按 `DEV_GUIDE.md` §1.2 保留——②只扫 `.comment()` 正文、
    已剥掉注释，故不误报。
"""
import io
import json
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
TREES = ['promaid_src', 'promaid_src_neo']
EN_TABLES = ['assets/promaid/i18n/en_us.json', 'assets/maid_smart/lang/en_us.json']
CN_TABLES = ['assets/promaid/i18n/en_us.json', 'assets/maid_smart/lang/zh_cn.json', 'assets/maid_smart/lang/en_us.json']

DEV = re.compile(r'\b(we|our|us|ours|ourselves)\b', re.IGNORECASE)
ROUND = re.compile(u'\u5b9e\u6d4b|(?<![A-Za-z])(?:test|tested)\\s*\\d', re.IGNORECASE)
DEV_CN = re.compile(u'\u5b9e\u6d4b|[\u56db\u4e94\u516d\u4e03\u516b\u4e5d]\u767e|\u628a *\\d+ *\u8bef\u5f53')

# 基线 = 现存残留（只减不增）。实测八百八十五/八百八十八 清零：全部 0。
BASELINE_EN = {
    'promaid_src': {EN_TABLES[0]: 0, EN_TABLES[1]: 0},
    'promaid_src_neo': {EN_TABLES[0]: 0, EN_TABLES[1]: 0},
}
BASELINE_CN = {
    'promaid_src': {'comment': 0, 'keys': 0},
    'promaid_src_neo': {'comment': 0, 'keys': 0},
}

FAILS, WARNS = [], []


def strip_comments(t):
    t = re.sub(r'/\*.*?\*/', ' ', t, flags=re.S)
    return re.sub(r'//[^\n]*', ' ', t)


def cn_comments(tree):
    """两树 .java 里 .comment("…") 正文里的开发标记数。"""
    n = 0
    hits = []
    for dp, dn, fn in os.walk(os.path.join(ROOT, tree)):
        for f in sorted(fn):
            if not f.endswith('.java'):
                continue
            t = strip_comments(io.open(os.path.join(dp, f), encoding='utf-8', errors='ignore').read())
            for c in re.findall(r'\.comment\("((?:[^"\\]|\\.)*)"\)', t):
                m = DEV_CN.search(c)
                if m:
                    n += 1
                    hits.append((f, m.group(0), c[:50]))
    return n, hits


def cn_keys(tree):
    """i18n / lang 的 key 与 maid_smart 两语 value 里的开发标记数。"""
    n = 0
    hits = []
    for rel in CN_TABLES:
        p = os.path.join(ROOT, tree, rel)
        d = json.load(io.open(p, encoding='utf-8'))
        scan_keys = 'i18n' in rel
        for k, v in d.items():
            if scan_keys and isinstance(k, str):
                m = DEV_CN.search(k)
                if m:
                    n += 1
                    hits.append((rel, 'key:' + m.group(0), k[:50]))
            if isinstance(v, str) and 'maid_smart' in rel:
                m = DEV_CN.search(v)
                if m:
                    n += 1
                    hits.append((rel, 'val:' + m.group(0), k[:50]))
    return n, hits


def main():
    dump = '--dump' in sys.argv
    for tree in TREES:
        # ① 英文 value
        for rel in EN_TABLES:
            p = os.path.join(ROOT, tree, rel)
            d = json.load(io.open(p, encoding='utf-8'))
            bad = []
            for k, v in d.items():
                if isinstance(v, str):
                    for name, rx in (('devvoice', DEV), ('round', ROUND)):
                        m = rx.search(v)
                        if m:
                            bad.append((name, m.group(0), k[:50]))
            print('--- %s / %s：英文 value 残留 %d 处' % (tree, rel, len(bad)))
            base = BASELINE_EN.get(tree, {}).get(rel)
            if base is None:
                WARNS.append('%s/%s: 没有基线' % (tree, rel))
            elif len(bad) > base:
                FAILS.append('%s/%s: %d > 基线 %d（见 TD-03）' % (tree, rel, len(bad), base))
            elif len(bad) < base:
                WARNS.append('%s/%s: %d < 基线 %d —— 可下调基线' % (tree, rel, len(bad), base))
            if dump:
                for name, tok, k in bad[:40]:
                    print('      %-8s %-10s %s' % (name, tok, k))
        # ② 源码 .comment 正文
        n, hits = cn_comments(tree)
        print('--- %s / .comment 正文：开发标记 %d 处' % (tree, n))
        if n > BASELINE_CN[tree]['comment']:
            FAILS.append('%s/.comment: %d > 基线 %d（见 TD-03）' % (tree, n, BASELINE_CN[tree]['comment']))
        elif n < BASELINE_CN[tree]['comment']:
            WARNS.append('%s/.comment: %d < 基线 %d —— 可下调基线' % (tree, n, BASELINE_CN[tree]['comment']))
        if dump:
            for f, tok, c in hits[:20]:
                print('      %-10s %s :: %s' % (tok, f, c))
        # ③ 中文键 / 可见中文
        n, hits = cn_keys(tree)
        print('--- %s / i18n+lang 键与可见中文：开发标记 %d 处' % (tree, n))
        if n > BASELINE_CN[tree]['keys']:
            FAILS.append('%s/keys: %d > 基线 %d（见 TD-03）' % (tree, n, BASELINE_CN[tree]['keys']))
        elif n < BASELINE_CN[tree]['keys']:
            WARNS.append('%s/keys: %d < 基线 %d —— 可下调基线' % (tree, n, BASELINE_CN[tree]['keys']))
        if dump:
            for rel, tok, k in hits[:20]:
                print('      %-12s %s :: %s' % (tok, rel, k))
    print()
    for w in WARNS:
        print('WARN ' + w)
    for f in FAILS:
        print('FAIL ' + f)
    print('check_dev_voice: FAIL %d / WARN %d' % (len(FAILS), len(WARNS)))
    if FAILS and '--warn' not in sys.argv:
        sys.exit(1)


if __name__ == '__main__':
    main()
