# -*- coding: utf-8 -*-
"""把 _voice_new2/*.ogg 安装进两棵源码树的 assets/promaid/voice/（文件名不变）。"""
import json
import os
import shutil

# --- repo root resolution (this file lives in tools/<group>/) ---
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

ROOT = _find_repo_root(__file__)
NEW = os.path.join(ROOT, '_voice_new2')
oggs = sorted(f for f in os.listdir(NEW) if f.endswith('.ogg'))
print('new oggs:', len(oggs))

for tree in ['promaid_src', 'promaid_src_neo']:
    vdir = os.path.join(ROOT, tree, 'assets', 'promaid', 'voice')
    entries = json.load(open(os.path.join(vdir, 'manifest.json'), encoding='utf-8'))['entries']
    want = {e['file'] for e in entries}
    copied = 0
    for f in oggs:
        if f in want:
            shutil.copy2(os.path.join(NEW, f), os.path.join(vdir, f))
            copied += 1
    have = {f for f in os.listdir(vdir) if f.endswith('.ogg')}
    print('%s: entries=%d copied=%d missing=%s' % (
        tree, len(entries), copied, sorted(want - have) or 'none'))
