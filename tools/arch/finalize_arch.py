# -*- coding: utf-8 -*-
"""Recompute receipt.json AFTER rendering, so the SHA-256 freeze covers final artifacts.

Run order:  gen_arch.py  ->  render_arch.py  ->  finalize_arch.py
"""
import os
import sys, os, json, hashlib

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
ROOT = _find_repo_root(__file__)
OUT = os.path.join(REPO, "normify-promaid")

tree = json.load(open(os.path.join(OUT, "tree.json"), encoding="utf-8"))
old = json.load(open(os.path.join(OUT, "receipt.json"), encoding="utf-8"))

files = {}
for dp, dn, fn in os.walk(OUT):
    for f in fn:
        if f == "receipt.json":
            continue
        p = os.path.join(dp, f)
        files[os.path.relpath(p, OUT).replace("\\", "/")] = \
            hashlib.sha256(open(p, "rb").read()).hexdigest()

receipt = {
    "schema_version": 1,
    "revision": tree["revision"],
    "generated_at": old.get("generated_at"),
    "stats": tree["stats"],
    "warnings": tree["diagnostics"]["warnings"],
    "files": files,
}
open(os.path.join(OUT, "receipt.json"), "w", encoding="utf-8").write(
    json.dumps(receipt, ensure_ascii=False, indent=2))

# verify: nothing may be stale now
bad = []
for rel, sha in files.items():
    p = os.path.join(OUT, rel)
    now = hashlib.sha256(open(p, "rb").read()).hexdigest()
    if now != sha:
        bad.append(rel)
print("files frozen:", len(files))
print("stale after freeze:", len(bad), bad[:5])
print("stats:", json.dumps(tree["stats"], ensure_ascii=False))
