# -*- coding: utf-8 -*-
"""Collect hard evidence for the tech-debt document."""
import os
import sys, os, re, json, subprocess, collections

# --- repo root resolution (this file lives in tools/arch/) ---
def _find_repo_root(start):
    d = os.path.dirname(os.path.abspath(start))
    while True:
        if os.path.isdir(os.path.join(d, ".git")) or os.path.isfile(os.path.join(d, ".git")):
            return d
        parent = os.path.dirname(d)
        if parent == d:
            return os.path.dirname(os.path.abspath(start))
        d = parent
REPO = _find_repo_root(__file__)
sys.stdout.reconfigure(encoding="utf-8", errors="replace")
ROOT = _find_repo_root(__file__); os.chdir(ROOT)

tracked = [l.strip() for l in subprocess.run(
    ["git","ls-files"], capture_output=True, text=True, encoding="utf-8",
    errors="replace").stdout.splitlines() if l.strip()]

neo = [p for p in tracked if p.startswith("promaid_src_neo/com/") and p.endswith(".java")]
forge = [p for p in tracked if p.startswith("promaid_src/com/") and p.endswith(".java")]

def loc(p):
    try:
        with open(p, encoding="utf-8", errors="replace") as f:
            return sum(1 for _ in f)
    except Exception:
        return 0

rows = [(loc(p), p) for p in neo]
rows.sort(reverse=True)

res = []
A = res.append
A("# oversized files (neo, >1200 lines)\n")
for n, p in rows:
    if n > 1200:
        A(f"{n:5d}  {p}")

# marker density: how many historical "实测NNN / v1.x.y" markers per file
A("\n# patch-sediment density (mentions of 实测 or version tags)\n")
dens = []
for n, p in rows[:40]:
    s = open(p, encoding="utf-8", errors="replace").read()
    m = len(re.findall(r"实测[零一二三四五六七八九十百〇0-9]+", s))
    v = len(re.findall(r"v1\.\d+\.\d+", s))
    if m + v:
        dens.append((m + v, m, v, p))
dens.sort(reverse=True)
for tot, m, v, p in dens[:20]:
    A(f"{tot:5d} ({m:4d} 实测 + {v:3d} ver)  {p}")

# dead-code candidates: classes never referenced outside their own file
A("\n# dead / near-dead candidates (no reference from any other tracked file)\n")
names = {}
for p in neo:
    b = os.path.basename(p)[:-5]
    names.setdefault(b, []).append(p)
# build corpus of all java text once (expensive but fine)
corpus = collections.defaultdict(int)
for p in neo:
    base = os.path.basename(p)
    for q in neo:
        if q == p:
            continue
        try:
            s = open(q, encoding="utf-8", errors="replace").read()
        except Exception:
            continue
        if base[:-5] in s:
            corpus[base[:-5]] += 1
never = sorted(k for k in names if corpus.get(k, 0) == 0)
A(f"total classes: {len(names)}; never referenced by name elsewhere: {len(never)}")
for k in never:
    A(f"   {k}   ({names[k][0]})")

# self-declared temporary / removed-feature comments
A("\n# self-declared temporary / removed / unreachable markers\n")
pats = [("可删", r"可删|删掉即可|查清问题后"),
        ("不可达", r"不可达|已停用|不再可达"),
        ("残留/仅保留", r"残留|仅保留|兼容残留|历史遗留"),
        ("临时", r"临时探针|临时方案|临时工具")]
for label, rx in pats:
    hits = []
    for p in neo:
        try:
            s = open(p, encoding="utf-8", errors="replace").read()
        except Exception:
            continue
        c = len(re.findall(rx, s))
        if c:
            hits.append((c, p))
    hits.sort(reverse=True)
    A(f"\n-- {label} --")
    for c, p in hits[:12]:
        A(f"   {c:4d}  {p}")

# duplicated logic: near-identical file sizes in mirrored trees
A("\n# two-tree mirror divergence (same relative path, different content)\n")
mirror = []
for p in neo:
    q = p.replace("promaid_src_neo/", "promaid_src/", 1)
    if q in tracked:
        a, b = loc(p), loc(q)
        if a != b:
            mirror.append((abs(a-b), a, b, p))
mirror.sort(reverse=True)
A(f"mirrored paths: {sum(1 for p in neo if p.replace('promaid_src_neo/','promaid_src/',1) in tracked)}")
A(f"with differing line counts: {len(mirror)}")
for d, a, b, p in mirror[:15]:
    A(f"   Δ{d:4d}  neo={a:5d} forge={b:5d}  {p.replace('promaid_src_neo/com/maidsmart/','')}")

open("_tmp_debt_evidence.txt", "w", encoding="utf-8").write("\n".join(res))
print("\n".join(res))
