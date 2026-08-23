# -*- coding: utf-8 -*-
"""verify_jar_classes.py — 校验打包出的 jar 是否包含 out_promaid 下所有 .class。
被 build_promaid.py 末尾调用;缺失时返回非 0,阻止发布损坏的 jar。"""
import os, sys, glob, zipfile

BASE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(BASE, "out_promaid")
JAR = os.path.join(BASE, "patched", "promaid-1.1.0.jar")

z = zipfile.ZipFile(JAR)
names = set(z.namelist())
missing = []
for p in glob.glob(os.path.join(OUT, "com", "**", "*.class"), recursive=True):
    rel = os.path.relpath(p, OUT).replace("\\", "/")
    if rel not in names:
        missing.append(rel)
if missing:
    print("MISSING_IN_JAR:", missing[:40])
    sys.exit(1)
print("verify_jar_classes: OK, all out_promaid classes present in jar")
sys.exit(0)
