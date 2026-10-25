# -*- coding: utf-8 -*-
"""收集一个版本的全部轮次，生成 / 校验发布声明覆盖表（规则见 WORKFLOW.md §6.4）。

数据源是 rounds/RELEASES.tsv 里该版本的所有行：每行开头的轮次标签（R####）就是
"这一版必须交代的内容"的最小单位。脚本把它和 rounds/R####-*.md 对上，打印需求来源
与改动摘要，避免写发布声明时漏掉某一轮。

用法:
  python tools/release/collect_release_rounds.py                # 版本取 mods.toml
  python tools/release/collect_release_rounds.py 1.3.3
  python tools/release/collect_release_rounds.py --write rounds/RELEASE-1.3.3-coverage.md
  python tools/release/collect_release_rounds.py --check rounds/RELEASE-1.3.3-coverage.md

退出码:
  0  清单完整（--check 时：覆盖表里每个轮次都在，且没有 TODO）
  2  有轮次既无记录、备注也说不清改动；或覆盖表漏项 / 仍有 TODO
"""

import glob
import io
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
REGISTRY = os.path.join(ROOT, 'rounds', 'RELEASES.tsv')
ROUND_GLOB = os.path.join(ROOT, 'rounds', '%s-*.md')
ROUND_TAG = re.compile(r'^R\d{4}$')
TAG_IN_NOTE = re.compile(r'\b(R\d{4})\b')
TODO = re.compile(r'TODO|待补|待写')


def read(path):
    return io.open(path, encoding='utf-8', errors='replace').read()


def current_version():
    t = read(os.path.join(ROOT, 'promaid_src', 'META-INF', 'mods.toml'))
    m = re.search(r'(?m)^\s*version\s*=\s*"([^"]+)"', t)
    return m.group(1) if m else None


def registry_rows(version):
    rows = []
    for line in read(REGISTRY).splitlines():
        if not line.strip() or line.startswith(('#', '>')):
            continue
        f = line.split('\t')
        if len(f) < 7 or f[0] != version:
            continue
        rows.append({'ver': f[0], 'loader': f[1], 'file': f[2], 'sha': f[3],
                     'date': f[4], 'status': f[5], 'note': f[6]})
    return rows


def round_tag(note, fallback):
    m = TAG_IN_NOTE.search(note)
    return m.group(1) if m else fallback


CN_DIGITS = {'零': 0, '一': 1, '二': 2, '两': 2, '三': 3, '四': 4, '五': 5,
             '六': 6, '七': 7, '八': 8, '九': 9}
CN_UNITS = {'十': 10, '百': 100, '千': 1000, '万': 10000}
MEASURED = re.compile(r'实测([零一二三四五六七八九十百千万\d]+)')


def cn_number(s):
    if re.fullmatch(r'\d+', s):
        return int(s)
    total, section, num = 0, 0, 0
    for ch in s:
        if ch in CN_DIGITS:
            num = CN_DIGITS[ch]
        elif ch in CN_UNITS:
            u = CN_UNITS[ch]
            if u == 10000:
                section = (section + num) * u
                total += section
                section, num = 0, 0
            else:
                section += (num or 1) * u
                num = 0
        else:
            return None
    return total + section + num


def legacy_doc(note, docs):
    """老登记行没有 R####，常写「实测NNN」；用它反查轮次记录（记录里引用同一实测号）。"""
    m = MEASURED.search(note)
    if not m:
        return None
    want = m.group(0)
    for p in docs:
        if want in read(p):
            return os.path.basename(p)
    return None


def section(text, *names):
    """取 `## <name>` 到下一个 `##` 之间的正文（首个命中）。"""
    lines = text.splitlines()
    for i, line in enumerate(lines):
        if any(line.startswith('## ' + n) for n in names):
            body = []
            for nxt in lines[i + 1:]:
                if nxt.startswith('## '):
                    break
                body.append(nxt)
            return '\n'.join(body).strip()
    return ''


def bullets(body, limit=6):
    out = []
    for line in body.splitlines():
        s = line.strip()
        if not s:
            continue
        if s[0] in '-*' or re.match(r'^\d+[.、]', s):
            s = re.sub(r'^([-*]|\d+[.、])\s*', '', s)
            out.append(re.sub(r'\s+', ' ', s))
        if len(out) >= limit:
            break
    return out


def cell(s, limit=160):
    s = re.sub(r'\s+', ' ', s).replace('|', '\\|')
    return s if len(s) <= limit else s[:limit - 1] + '…'


def collect(version):
    rows = registry_rows(version)
    if not rows:
        print('RELEASES.tsv 里没有版本 %s 的登记行。' % version)
        return []

    groups = {}
    all_docs = sorted(glob.glob(os.path.join(ROOT, 'rounds', 'R*.md')))
    for idx, r in enumerate(rows):
        tag = round_tag(r['note'], '(未标轮次#%02d)' % (idx + 1))
        docs = []
        if ROUND_TAG.match(tag):
            docs = sorted(os.path.basename(p) for p in glob.glob(ROUND_GLOB % tag))
        else:
            found = legacy_doc(r['note'], all_docs)  # 老行没写 R####，用「实测NNN」反查
            if found:
                tag = found[:5]
                docs = [found]
        g = groups.setdefault(tag, {'loaders': [], 'notes': [], 'docs': []})
        if r['loader'] not in g['loaders']:
            g['loaders'].append(r['loader'])
        g['notes'].append(r['note'])
        if docs:
            g['docs'] = sorted(set(g['docs'] + docs))

    items = []
    for tag in sorted(groups, key=lambda t: (not ROUND_TAG.match(t), t)):
        g = groups[tag]
        doc = g['docs'][0] if g['docs'] else None
        text = read(os.path.join(ROOT, 'rounds', doc)) if doc else ''
        req = section(text, '需求来源', '需求') or section(text, '来源')
        changes = section(text, '改动')
        note = max(g['notes'], key=len)
        items.append({
            'tag': tag,
            'loaders': g['loaders'],
            'doc': doc,
            'req': bullets(req, 3),
            'changes': bullets(changes, 6),
            'note': re.sub(r'\s+', ' ', note).strip(),
        })
    return items


def print_list(version, items):
    print('# 发布覆盖清单 %s' % version)
    print('轮次数：%d（来源 rounds/RELEASES.tsv 的 %s 行）\n'
          % (len(items), version))
    for it in items:
        print('## %s  [%s]' % (it['tag'], ' / '.join(it['loaders'])))
        print('轮次记录：%s' % (it['doc'] or '（无独立记录，按下方 RELEASES 备注核对）'))
        if it['req']:
            print('需求来源：')
            for b in it['req']:
                print('  - ' + b[:300])
        if it['changes']:
            print('改动：')
            for b in it['changes']:
                print('  - ' + b[:300])
        if not it['doc'] and it['note']:
            print('RELEASES 备注：%s' % it['note'][:500])
        print()


def build_table(version, items):
    out = ['# 发布覆盖表 %s' % version, '',
           '> 由 `python tools/release/collect_release_rounds.py %s --write <本文件>` 生成。' % version,
           '> 每一行都必须处理：写进发布声明（填「落点」），或标为玩家不可感知（落点填「无」）。',
           '> 全部勾选后，用 `--check <本文件>` 放行。', '',
           '| 勾选 | 轮次 | 加载器 | 轮次记录 | 需求来源 | 玩家可感知改动 | 落点 |',
           '| --- | --- | --- | --- | --- | --- | --- |']
    for it in items:
        req = '；'.join(it['req']) if it['req'] else '-'
        chg = '；'.join(it['changes']) if it['changes'] else '-'
        if not it['doc']:
            chg = 'RELEASES 备注：' + it['note']
        out.append('| [ ] | %s | %s | %s | %s | %s | TODO |'
                   % (cell(it['tag'], 20), cell(','.join(it['loaders']), 20),
                      cell(it['doc'] or '无独立记录', 40), cell(req), cell(chg)))
    out.append('')
    return '\n'.join(out)


def check_file(path, items):
    if not os.path.isfile(path):
        print('覆盖表不存在：%s' % path)
        return False
    text = read(path)
    ok = True
    print('--- 覆盖表核对 %s' % path)
    for it in items:
        if it['tag'] not in text:
            print('缺项：%s 没有出现在覆盖表里' % it['tag'])
            ok = False
            continue
        line = next((l for l in text.splitlines() if it['tag'] in l), '')
        if TODO.search(line):
            print('未处理：%s 仍是 TODO/待补' % it['tag'])
            ok = False
    if 'TODO' in text and ok:
        print('覆盖表里仍有 TODO（可能不在轮次行上）——请逐条确认')
        ok = False
    if not ok:
        print('结果：FAIL')
        return False
    print('结果：PASS（%d 个轮次全部有落点）' % len(items))
    return True


def main(argv):
    version, write_path, check_path = None, None, None
    args = list(argv[1:])
    i = 0
    while i < len(args):
        a = args[i]
        if a == '--write' and i + 1 < len(args):
            write_path = args[i + 1]
            i += 2
            continue
        if a == '--check' and i + 1 < len(args):
            check_path = args[i + 1]
            i += 2
            continue
        if not a.startswith('-') and version is None:
            version = a
        i += 1
    version = version or current_version()
    if not version:
        print('读不到版本号：请显式传入，例如 1.3.3')
        return 2

    items = collect(version)
    if not items:
        return 2
    # 新轮次必须有独立记录；老登记行允许退化为 RELEASES 备注，但备注要能说清改了什么
    missing = [it['tag'] for it in items if not it['doc'] and len(it['note']) < 30]

    if write_path:
        path = write_path if os.path.isabs(write_path) else os.path.join(ROOT, write_path)
        io.open(path, 'w', encoding='utf-8', newline='\n').write(build_table(version, items))
        print('已写入覆盖表：%s（%d 个轮次）' % (path, len(items)))
    if check_path:
        path = check_path if os.path.isabs(check_path) else os.path.join(ROOT, check_path)
        if not check_file(path, items):
            return 2
    if not write_path and not check_path:
        print_list(version, items)

    if missing:
        print('\n!! 以下轮次没有 rounds/R####-*.md，先在轮次记录里补「需求来源 / 改动」：')
        for tag in missing:
            print('   ' + tag)
        return 2
    return 0


if __name__ == '__main__':
    sys.exit(main(sys.argv))
