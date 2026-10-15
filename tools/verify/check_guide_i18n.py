# -*- coding: utf-8 -*-
"""手册译文对齐护栏（TD 新项）：把手册章节可见正文「按渲染器口径缺译 / 疑似错位 / 带轮次号」钉在基线上。

用法: python tools/verify/check_guide_i18n.py [--warn] [--dump]

为什么需要它（R953/R954 的教训）：手册章节的中文正文就是 i18n 的**键**，英文写在
`assets/promaid/i18n/en_us.json` 的 value 里。正文被改写后旧键变死键、新键没译文，
英文客户端就**静默回落中文**；`check_i18n_keys.py` 只管 `config.promaid.*`（面板），
`check_dev_voice.py` 只扫 i18n **键**、不扫章节正文（`.java` 里那串中文）——两头都不覆盖。

判据（三面，都只减不增；基线 = 现存 0）：
  ① **缺译单元**：复刻 `GuideScreen.translateParagraph` 的口径——
     `[[@目标|标签]]` 只有**标签**过 `tr()`、目标原样；`[[#…]]`（合成表/图标）整块原样不进表；
     其余文字按 `[[` 切段后**逐段**过 `tr()`。逐段查表，查不到 = 缺译。
     （按"整段键"查会误判：带链接的段落本来就不需要整段译文。）
  ② **疑似错位**：单元有译文，但 `len(key) > 150 且 len(value) < 45`——正文很长、英文只剩一截，
     典型症状是英文尾被切到相邻条目上（R953 现场）。
  ③ **轮次号泄漏**：章节可见正文里出现开发者标记 `实测` / `[四五六七八九]百` / `把 <数字> 误当`
     （③ 与 `check_dev_voice.py` 同判据，但那个脚本只扫 i18n 键与 `.comment()`，扫不到章节正文）。
"""
import io
import json
import os
import re
import sys
import glob

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
TREES = ['promaid_src', 'promaid_src_neo']
TABLE = 'assets/promaid/i18n/en_us.json'
BASELINE = {'promaid_src': {'missing': 0, 'shifted': 0, 'round': 0},
            'promaid_src_neo': {'missing': 0, 'shifted': 0, 'round': 0}}

LINK_OPEN, LINK_CLOSE = '[[@', ']]'
SHIFT_KEY_MIN, SHIFT_VAL_MAX = 150, 45
DEV_CN = re.compile(u'\u5b9e\u6d4b|[\u56db\u4e94\u516d\u4e03\u516b\u4e5d]\u767e|\u628a *\\d+ *\u8bef\u5f53')

FAILS, WARNS = [], []


def decode_java(s):
    out = []
    i = 0
    while i < len(s):
        c = s[i]
        if c == '\\' and i + 1 < len(s):
            n = s[i + 1]
            if n == 'u':
                out.append(chr(int(s[i + 2:i + 6], 16))); i += 6; continue
            out.append({'n': '\n', 't': '\t', 'r': '\r', '"': '"', "'": "'", '\\': '\\'}.get(n, n))
            i += 2; continue
        out.append(c); i += 1
    return ''.join(out)


LIT = re.compile(r'"(?:[^"\\]|\\.)*"')


def join_expr(expr):
    return ''.join(decode_java(p[1:-1]) for p in LIT.findall(expr))


def parse_args(s):
    args = []; cur = ''; depth = 0; instr = False; i = 0
    while i < len(s):
        c = s[i]
        if instr:
            if c == '\\':
                cur += s[i:i + 2]; i += 2; continue
            if c == '"':
                instr = False
            cur += c; i += 1; continue
        if c == '"':
            instr = True; cur += c; i += 1; continue
        if c == '(':
            depth += 1; cur += c; i += 1; continue
        if c == ')':
            if depth == 0:
                args.append(cur); return args
            depth -= 1; cur += c; i += 1; continue
        if c == ',' and depth == 0:
            args.append(cur); cur = ''; i += 1; continue
        cur += c; i += 1; continue
    args.append(cur); return args


def chapters(path):
    txt = io.open(path, encoding='utf-8').read()
    out = []; idx = 0; marker = 'new GuideContent.Chapter('
    while True:
        k = txt.find(marker, idx)
        if k < 0:
            break
        args = parse_args(txt[k + len(marker):])
        pre = txt[:k]
        m = re.findall(r'static GuideContent\.Chapter\s+(\w+)\s*\(\s*\)\s*\{', pre)
        out.append((m[-1] if m else '?', [join_expr(a) for a in args]))
        idx = k + len(marker)
    return out


def units(raw):
    """渲染器口径：返回真正会过 tr() 的片段。"""
    if not raw:
        return []
    if '[[' not in raw:
        return [raw]
    out = []; i = 0; n = len(raw)
    while i < n:
        o = raw.find('[[', i)
        if o < 0:
            out.append(raw[i:]); break
        c = raw.find(LINK_CLOSE, o)
        if c < 0:
            out.append(raw[i:]); break
        if o > i:
            out.append(raw[i:o])
        end = c + len(LINK_CLOSE); tok = raw[o:end]
        if tok.startswith(LINK_OPEN):
            bar = tok.find('|')
            if bar > 0:
                out.append(tok[bar + 1:len(tok) - len(LINK_CLOSE)])
        i = end
    return out


def check(tree, dump):
    tab = json.load(io.open(os.path.join(ROOT, tree, TABLE), encoding='utf-8'))
    missing, shifted, rounds = [], [], []
    seen = set()
    for f in sorted(glob.glob(os.path.join(ROOT, tree, 'com/maidsmart/guide/GuideChapters*.java'))):
        base = os.path.basename(f)
        for name, elems in chapters(f):
            for j, k in enumerate(elems):
                m = DEV_CN.search(k)
                if m:
                    rounds.append((base, name, j, m.group(0), k[:60]))
                for u in units(k):
                    if not u or u in seen:
                        continue
                    seen.add(u)
                    v = tab.get(u)
                    if v is None:
                        missing.append((name, j, len(u), u))
                    elif len(u) > SHIFT_KEY_MIN and len(v) < SHIFT_VAL_MAX:
                        shifted.append((name, j, len(u), len(v), u, v))
    for label, hits in (('missing', missing), ('shifted', shifted), ('round', rounds)):
        n = len(hits)
        base = BASELINE[tree][label]
        print('--- %s / %s：%d 处（基线 %d）' % (tree, label, n, base))
        if n > base:
            FAILS.append('%s/%s: %d > 基线 %d' % (tree, label, n, base))
        elif n < base:
            WARNS.append('%s/%s: %d < 基线 %d —— 可下调基线' % (tree, label, n, base))
        if dump:
            for h in hits[:40]:
                print('      %r' % (h,))


def main():
    dump = '--dump' in sys.argv
    for tree in TREES:
        check(tree, dump)
    print()
    for w in WARNS:
        print('WARN ' + w)
    for f in FAILS:
        print('FAIL ' + f)
    print('check_guide_i18n: FAIL %d / WARN %d' % (len(FAILS), len(WARNS)))
    if FAILS and '--warn' not in sys.argv:
        sys.exit(1)


if __name__ == '__main__':
    main()
