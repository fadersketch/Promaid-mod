# -*- coding: utf-8 -*-
import io
import os
import sys
sys.stdout.reconfigure(encoding="utf-8")
BASE = r"C:\Users\Sketch\.zcode\workspace\default\promaid-mod\promaid_src"
print("=== follow uid refs ===")
for root, dirs, files in os.walk(BASE):
    for f in files:
        if f.endswith(".java"):
            p = os.path.join(root, f)
            for i, l in enumerate(io.open(p, encoding="utf-8").read().splitlines(), 1):
                if ":follow" in l or ("follow" in l.lower() and "getTask" in l):
                    print(os.path.relpath(p, BASE), i, ascii(l.strip()[:110]))
print("=== guide 跨维度跟随 mentions ===")
g = io.open(BASE + r"\com\maidsmart\guide\GuideContent.java", encoding="utf-8").read().splitlines()
for i, l in enumerate(g, 1):
    if "跨维度跟随" in l or ("跨维度" in l and "跟随" in l):
        print(i, ascii(l[:120]))
