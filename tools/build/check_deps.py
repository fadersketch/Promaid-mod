# -*- coding: utf-8 -*-
"""编译工具链依赖锁的自检 / 获取计划（TD-13，配合 tools/build/deps.lock）。

用法：
  python tools/build/check_deps.py            # 离线核对：本机工具链是否与 deps.lock 逐哈希一致
  python tools/build/check_deps.py --plan     # 只打印"从哪取"的获取计划（不联网）

判定：
  - 锁里 sha256=MISSING 的条目：本机也没有 → 只算"可选缺失"（javac 容忍 classpath 缺项）；
  - 文件在、哈希不符 → FAIL（你的工具链和记录那次构建不是同一份）；
  - 文件不在 → WARN（缺 classpath 项通常不影响编译，但等于没复现那份工具链）。
根目录由环境变量决定（同 gen_compile*.py）：PROMAID_MC_HOME（默认 D:/.minecraft）、
PROMAID_LIB（默认 <MC_HOME>/libraries）、REPO=本仓库根。
"""
import hashlib
import io
import os
import sys

REPO = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
MC = os.environ.get('PROMAID_MC_HOME') or r'D:\.minecraft'
LIB = os.environ.get('PROMAID_LIB') or os.path.join(MC, 'libraries')
ROOTS = {'LIB': LIB, 'MC': MC, 'REPO': REPO}

# maven 前缀 → 仓库根 URL（取不到就落到 Maven Central）
REPOS = [
    ('net/minecraftforge/', 'https://maven.minecraftforge.net/'),
    ('net/neoforged/', 'https://maven.neoforged.net/releases/'),
    ('net/minecraft/', 'https://libraries.minecraft.net/'),
    ('com/mojang/', 'https://libraries.minecraft.net/'),
]
CENTRAL = 'https://repo1.maven.org/maven2/'


def repo_url(rel):
    for pre, url in REPOS:
        if rel.startswith(pre):
            return url + rel
    return CENTRAL + rel


def load():
    p = os.path.join(REPO, 'tools/build/deps.lock')
    rows = []
    for ln in io.open(p, encoding='utf-8').read().splitlines():
        if not ln or ln.startswith('#'):
            continue
        parts = ln.split('\t')
        if len(parts) == 5:
            rows.append(parts)
    return rows


def main():
    rows = load()
    plan = '--plan' in sys.argv
    fails, warns = [], []
    per = {}
    for tag, rel, sha, size, kind in rows:
        root = ROOTS[rel.split('/', 1)[0]]
        path = os.path.join(root, rel.split('/', 1)[1])
        st = per.setdefault(tag, [0, 0, 0, 0])  # ok, mismatch, missing, optional
        if plan:
            if kind == 'maven':
                print('%s  %s' % (repo_url(rel.split('/', 1)[1]), rel))
            else:
                print('%s  [local·需自备]  %s' % (tag, rel))
            continue
        if sha == 'MISSING':
            st[3] += 1
            continue
        if not os.path.exists(path):
            st[2] += 1
            warns.append('%s: 缺 %s（classpath 容忍，但未复现该工具链）' % (tag, rel))
            continue
        cur = hashlib.sha256(io.open(path, 'rb').read()).hexdigest()
        if cur != sha:
            st[1] += 1
            fails.append('%s: 哈希不符 %s' % (tag, rel))
        else:
            st[0] += 1
    if plan:
        return
    for tag in sorted(per):
        ok, mm, mis, opt = per[tag]
        print('--- %s：一致 %d / 不符 %d / 缺失 %d / 可选缺失 %d' % (tag, ok, mm, mis, opt))
    print()
    for w in warns:
        print('WARN ' + w)
    for f in fails:
        print('FAIL ' + f)
    print('check_deps: FAIL %d / WARN %d' % (len(fails), len(warns)))
    if fails and '--warn' not in sys.argv:
        sys.exit(1)


if __name__ == '__main__':
    main()
