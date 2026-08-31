# -*- coding: utf-8 -*-
# 实测六十九部署：临时目录中转 -> UAC 提权复制到 mods -> 校验字节数
import ctypes
import os
import shutil
import subprocess
import sys
import tempfile

sys.stdout.reconfigure(encoding="utf-8")
SRC = r"C:\Users\Sketch\.zcode\workspace\default\promaid-mod\patched\promaid-1.1.0.jar"
DST = r"D:\.minecraft\versions\1.20.1-Forge_47.4.23\mods\promaid-1.1.0.jar"

# 实测二百二十一：游戏运行时拒绝部署——运行中的 JVM 对已打开的 jar 做懒加载，
# 替换 jar 后新类按旧目录索引读取失败（实测 NoClassDefFoundError:
# MaidBuildBlockFilter，游戏 07:48 启动、部署覆盖后 07:49:48 崩溃）。部署必须
# 在游戏关闭后进行；直接在这里拦截（java/javaw 进程存在即退出，不弹 UAC）。
import subprocess
_probe = subprocess.run(["powershell", "-NoProfile", "-Command",
                         "(Get-Process -ErrorAction SilentlyContinue | "
                         "Where-Object { $_.ProcessName -match '^(java|javaw|javaw.exe)$' }).Count"],
                        capture_output=True, text=True)
try:
    _running = int(_probe.stdout.strip() or "0")
except Exception:
    _running = 0
if _running > 0:
    print("DEPLOY REFUSED: Minecraft/Java 进程正在运行（%d 个）——请先完全退出游戏再部署。"
          % _running)
    sys.exit(5)

size = os.path.getsize(SRC)
tmp = os.path.join(tempfile.gettempdir(), "promaid-1.1.0.jar")
shutil.copyfile(SRC, tmp)
print("staged:", tmp, os.path.getsize(tmp))

inner = "Copy-Item -LiteralPath '%s' -Destination '%s' -Force; if (!(Test-Path '%s')) { exit 2 }" % (
    tmp, DST, DST)
cmd = ["powershell", "-NoProfile", "-Command",
       "Start-Process powershell -Verb RunAs -Wait -ArgumentList '-NoProfile','-Command',"
       "'__INNER__'".replace("__INNER__", inner.replace("'", "''"))]
r = subprocess.run(cmd, capture_output=True, text=True)
print("elevated rc:", r.returncode, r.stdout.strip(), r.stderr.strip())

if os.path.exists(DST):
    dsz = os.path.getsize(DST)
    print("deployed:", DST, dsz, "match=", dsz == size)
    sys.exit(0 if dsz == size else 3)
print("DEPLOY FAILED: dst missing")
sys.exit(4)
