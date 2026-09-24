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

# v1.3.7 实测六百六十二【本次死机的判据，从此每次打包都挡一道】：
# mixin 配置里 "package" 声明的那个包被 Mixin 整体当成「mixin 包」——包里**没登记在清单里**
# 的类，只要被普通代码加载（JVM 解析接口表也算），运行期就抛
#   IllegalClassLoadError: … is in a defined mixin package com.maidsmart.mixin.*
#   owned by mixins.promaid.json and cannot be referenced directly
# 实测六百六十二就是这么死的：RemoteTrackBridge（一个「鸭子接口」，不是 mixin）躺在那个包里
# 又没登记，玩家一进世界 → ChunkMap.addEntity 造 TrackedEntity → 解析接口表 → 崩。
# 编译器和 javac 都看不见这个错，只有真进游戏才炸——所以判据放在这里。
import glob as _glob, json as _json  # noqa: E402  （本文件其余部分只用 os/sys/glob/zipfile）

_mixin_cfg_name = "mixins.promaid.json"
if _mixin_cfg_name not in names:
    print("FATAL: jar 里没有 %s" % _mixin_cfg_name)
    sys.exit(1)

_z = zipfile.ZipFile(JAR)
_cfg = _json.loads(_z.read(_mixin_cfg_name).decode("utf-8-sig"))
_z.close()

_pkg = _cfg.get("package", "")
if _pkg != "com.maidsmart.mixin":
    print("FATAL: mixin 配置的 package 变了：%r（本判据只认 com.maidsmart.mixin）" % _pkg)
    sys.exit(1)

_declared = set(_cfg.get("mixins", [])) | set(_cfg.get("client", [])) | set(_cfg.get("server", []))
_pkg_dir = _pkg.replace(".", "/") + "/"
_in_pkg = set()
for _n in names:
    if _n.startswith(_pkg_dir) and _n.endswith(".class") and "$" not in _n:
        _in_pkg.add(_n[len(_pkg_dir):-len(".class")])

_unregistered = sorted(_in_pkg - _declared)
_no_source = sorted(_declared - _in_pkg)
if _unregistered:
    print("UNREGISTERED_IN_MIXIN_PACKAGE(%d): %s" % (len(_unregistered), _unregistered))
    print("  这些类住在 mixin 包里却没登记 → 运行期一被加载就 IllegalClassLoadError（实测六百六十二）。")
    print("  要么把它搬出 %s，要么（只有真 mixin/accessor 才能）登记进 mixins.promaid.json。" % _pkg)
    sys.exit(1)
if _no_source:
    print("DECLARED_BUT_NO_CLASS(%d): %s" % (len(_no_source), _no_source))
    sys.exit(1)
print("verify_jar_classes: mixin 包登记 OK — %d 个类全部登记，无未登记的「鸭子接口」"
      % len(_in_pkg))

print("verify_jar_classes: OK — out %d 个 class 全部在 jar 里，jar 无多余 class" % len(out_classes))
sys.exit(0)
