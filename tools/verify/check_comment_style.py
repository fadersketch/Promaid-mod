# -*- coding: utf-8 -*-
"""注释风格护栏：扫描两棵树，找出不符合 DEV_GUIDE.md 第 1 节的注释。

用法：
    python tools/verify/check_comment_style.py            # 违规即失败
    python tools/verify/check_comment_style.py --warn     # 只报告
    python tools/verify/check_comment_style.py --file <片段>   # 只看匹配的文件

判定（只看注释；**字符串字面量不查**——那是给玩家看的文案）：
  BANNED        注释里出现 玩家原话 / 玩家反馈 / 粉丝原话 / 玩家建议原文 / 玩家追问
  QUOTE-SPEECH  注释里出现引述玩家的措辞（原话 / 反馈 / 建议 / 追问 / 玩家说 …）
  QUOTE-BLOCK   注释里「…」引语超过 QUOTE_MAX 字（逐字照抄的长引语）
  LONG-BLOCK    注释块过长（类头 > CLASS_MAX 行；方法/字段 > MEMBER_MAX 行）
  「大运」这类**当名称用的书名号**不算违规，只有引述玩家的话才算。
"""
import io
import os
import re
import sys

sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8', errors='replace')
ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
TREES = ['promaid_src', 'promaid_src_neo']

BANNED = ['玩家原话', '玩家反馈', '粉丝原话', '玩家建议原文', '玩家追问']
SPEECH = ['原话', '玩家反馈', '用户反馈', '粉丝说', '玩家说', '反馈说', '建议原文', '玩家追问']
# 字符串字面量（玩家可见文案）里同样禁止的「引述玩家」字样
LIT_MARK = ['玩家原话', '粉丝原话', '用户原话', '玩家反馈', '用户反馈', '需求原文', '原话照录', '反馈原话']
CLASS_MAX = 15
MEMBER_MAX = 8
QUOTE_MAX = 30
WARN_ONLY = '--warn' in sys.argv
ONLY = sys.argv[sys.argv.index('--file') + 1].replace('\\', '/') if '--file' in sys.argv else None

problems = []


def comment_flags(src):
    """逐字符扫描，返回与 src 等长的 bool 列表：该字符是否位于注释内。
    正确处理字符串/字符字面量、文本块与转义（字符串里的「」不算注释）。"""
    flags = [False] * len(src)
    i, n = 0, len(src)
    state = 'code'
    while i < n:
        c = src[i]
        if state == 'code':
            if c == '/' and i + 1 < n and src[i + 1] == '/':
                state = 'line'
                flags[i] = flags[i + 1] = True
                i += 2
                continue
            if c == '/' and i + 1 < n and src[i + 1] == '*':
                state = 'block'
                flags[i] = flags[i + 1] = True
                i += 2
                continue
            if c == '"':
                if src[i:i + 3] == '"""':
                    state = 'text'
                    i += 3
                    continue
                state = 'str'
                i += 1
                continue
            if c == "'":
                state = 'chr'
                i += 1
                continue
            i += 1
            continue
        if state in ('line', 'block'):
            if state == 'block' and c == '*' and i + 1 < n and src[i + 1] == '/':
                flags[i] = flags[i + 1] = True
                state = 'code'
                i += 2
                continue
            flags[i] = True
            if state == 'line' and c == '\n':
                state = 'code'
            i += 1
            continue
        # str / chr / text
        if c == '\\':
            i += 2
            continue
        if state == 'text' and src[i:i + 3] == '"""':
            state = 'code'
            i += 3
            continue
        if state == 'str' and c == '"':
            state = 'code'
            i += 1
            continue
        if state == 'chr' and c == "'":
            state = 'code'
            i += 1
            continue
        i += 1
    return flags


def longest_quote(line):
    best = 0
    for m in re.finditer('\u300c([^\u300d]*)\u300d', line):
        best = max(best, len(m.group(1)))
    return best


EN_FP = re.compile(r"\b(I|I'm|I've|my|we|me|us|you)\b")
ZH_FP = ['我', '咱们', '咱', '能不能', '搞一下', '请帮']


def is_speech_quote(line):
    """只把「逐字玩家原话」当违规：长引语且内含第一人称（UI 串/功能名不算）。"""
    for m in re.finditer('\u300c([^\u300d]*)\u300d', line):
        q = m.group(1)
        if len(q) > QUOTE_MAX and (any(f in q for f in ZH_FP) or EN_FP.search(q)):
            return True
    return False


def literals_on_line(line, mask):
    """这一行里各字符串字面量的**内容**（不含引号）；mask=True 表示该字符在注释内，跳过。"""
    res, i = [], 0
    while i < len(line):
        if line[i] == '"' and not mask[i]:
            j = i + 1
            while j < len(line):
                if line[j] == '\\':
                    j += 2
                    continue
                if line[j] == '"':
                    break
                j += 1
            if j < len(line):
                res.append(line[i + 1:j])
                i = j + 1
                continue
        i += 1
    return res


def scan_java(path):
    rel = os.path.relpath(path, ROOT)
    src = io.open(path, encoding='utf-8', errors='replace').read()
    flags = comment_flags(src)
    lines = src.split('\n')
    starts, off = [], 0
    for l in lines:
        starts.append(off)
        off += len(l) + 1
    in_comment = [any(flags[s:s + len(l)]) for idx, l in enumerate(lines) for s in [starts[idx]]]

    block, block_start = 0, 0
    for idx, l in enumerate(lines, 1):
        if in_comment[idx - 1]:
            if block == 0:
                block_start = idx
            block += 1
            for bad in BANNED:
                if bad in l:
                    problems.append((rel, idx, 'BANNED', bad + ' :: ' + l.strip()[:60]))
                    break
            if any(s in l for s in SPEECH):
                problems.append((rel, idx, 'QUOTE-SPEECH', l.strip()[:70]))
            elif is_speech_quote(l):
                problems.append((rel, idx, 'QUOTE-BLOCK', '%d 字玩家原话引语 :: %s' % (longest_quote(l), l.strip()[:50])))
        else:
            if block:
                report_block(rel, block_start, block, lines)
            block = 0
            # 字符串字面量（= 玩家可见文案）里也不许有引述玩家的字样
            for lit in literals_on_line(l, flags[starts[idx - 1]:starts[idx - 1] + len(l)]):
                if any(s in lit for s in LIT_MARK):
                    problems.append((rel, idx, 'LITERAL-SPEECH', lit.strip()[:70]))
                    break
    if block:
        report_block(rel, block_start, block, lines)


def report_block(rel, start, n, lines):
    if n <= MEMBER_MAX:
        return
    # 看这一块后面跟的是不是类型声明 → 用类头上限
    kind = 'MEMBER'
    for l in lines[start + n - 1:start + n + 3]:
        if re.search(r'\b(class|interface|enum|record)\b', l):
            kind = 'CLASS'
            break
    limit = CLASS_MAX if kind == 'CLASS' else MEMBER_MAX
    if n > limit:
        problems.append((rel, start, 'LONG-BLOCK', '%s %d 行 > %d' % (kind, n, limit)))


def main():
    n = 0
    for tree in TREES:
        for r, _, fs in os.walk(os.path.join(ROOT, tree)):
            for f in fs:
                if not f.endswith('.java'):
                    continue
                p = os.path.join(r, f)
                if ONLY and ONLY not in p.replace('\\', '/'):
                    continue
                scan_java(p)
                n += 1
    kinds = {}
    for _, _, kind, _ in problems:
        kinds[kind] = kinds.get(kind, 0) + 1
    print('check_comment_style: %d 个文件，%d 处问题 %s' % (n, len(problems), kinds))
    by_file = {}
    for rel, idx, kind, text in problems:
        by_file.setdefault(rel, []).append((idx, kind, text))
    top = sorted(by_file.items(), key=lambda kv: -len(kv[1]))[:12]
    for rel, items in top:
        print('  %-70s %d 处' % (rel, len(items)))
        for idx, kind, text in items[:3]:
            print('      [%s] :%d %s' % (kind, idx, text))
    ok = not problems
    print('RESULT:', 'PASS' if ok else 'FAIL')
    sys.exit(0 if (ok or WARN_ONLY) else 1)


if __name__ == '__main__':
    main()
