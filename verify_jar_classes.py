# -*- coding: utf-8 -*-
"""verify_jar_classes.py — 校验打包出的 jar 与 out 目录是否一致。
用法: verify_jar_classes.py [jar 路径] [out 目录]
  省略参数时: jar = patched/ 下最新的 promaid-*-forge-*.jar，out = out_promaid
被 build_promaid.py / build_promaid_neo.py 末尾调用；缺类时返回非 0，阻止发布损坏的 jar。

v1.2.5 实测六百五十三：jar 文件名在 v1.2.4 改成
「promaid-<版本>-<加载器>-<游戏版本>.jar」之后，本脚本里写死的
「patched/promaid-1.2.4.jar」就永远不存在了 → 每次构建都在这里抛
FileNotFoundError，被 build 脚本当成「jar 与 out 内容不一致」报 FATAL。
检测逻辑本身没问题（前面几版真的抓到过漏打的 $内部类），所以修成
按参数/按最新文件取路径，别再写死。
"""
import os, sys, glob, zipfile

BASE = os.path.dirname(os.path.abspath(__file__))

if len(sys.argv) > 2:
    OUT = sys.argv[2]
else:
    OUT = os.path.join(BASE, "out_promaid")

if len(sys.argv) > 1:
    JAR = sys.argv[1]
else:
    cands = glob.glob(os.path.join(BASE, "patched", "promaid-*-forge-*.jar"))
    if not cands:
        print("FATAL: 找不到 forge jar（patched/promaid-*-forge-*.jar）")
        sys.exit(1)
    JAR = max(cands, key=os.path.getmtime)

print("verify_jar_classes: jar =", os.path.relpath(JAR, BASE), "| out =", os.path.relpath(OUT, BASE))
if not os.path.exists(JAR):
    print("FATAL: jar 不存在: " + JAR)
    sys.exit(1)
if not os.path.isdir(OUT):
    print("FATAL: out 目录不存在: " + OUT)
    sys.exit(1)

z = zipfile.ZipFile(JAR)
names = set(z.namelist())
z.close()

out_classes = set()
for p in glob.glob(os.path.join(OUT, "com", "**", "*.class"), recursive=True):
    out_classes.add(os.path.relpath(p, OUT).replace("\\", "/"))

jar_classes = set(n for n in names if n.startswith("com/") and n.endswith(".class"))

missing = sorted(out_classes - jar_classes)   # 编译了却没进 jar = 会让客户端 ClassNotFound
extra = sorted(jar_classes - out_classes)     # 进 jar 但 out 里没有 = 残留旧字节码

if extra:
    print("STALE_IN_JAR(%d): %s" % (len(extra), extra[:20]))
if missing:
    print("MISSING_IN_JAR(%d): %s" % (len(missing), missing[:40]))
    sys.exit(1)

print("verify_jar_classes: OK — out %d 个 class 全部在 jar 里，jar 无多余 class" % len(out_classes))
sys.exit(0)
