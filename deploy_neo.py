# -*- coding: utf-8 -*-
# NeoForge 部署：临时目录中转 -> UAC 提权复制到 1.21.1 mods -> 校验字节数（deploy69 同款）
import os
import shutil
import subprocess
import sys
import tempfile

sys.stdout.reconfigure(encoding="utf-8")
BASE = os.path.dirname(os.path.abspath(__file__))
SRC = os.path.join(BASE, "patched", "promaid-1.1.0-neoforge-1.21.1.jar")
DST = r"D:\.minecraft\versions\1.21.1-NeoForge_21.1.250\mods\promaid-1.1.0-neoforge-1.21.1.jar"

# 游戏运行时拒绝部署（java/javaw 进程存在即退出，不弹 UAC）
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

if not os.path.isfile(SRC):
    print("FATAL: jar 不存在:", SRC, "——请先跑 build_promaid_neo.py")
    sys.exit(6)

size = os.path.getsize(SRC)
tmp = os.path.join(tempfile.gettempdir(), "promaid-1.1.0-neoforge-1.21.1.jar")
shutil.copyfile(SRC, tmp)
print("staged:", tmp, os.path.getsize(tmp))

inner = ("Copy-Item -LiteralPath '%s' -Destination '%s' -Force; "
         "if (!(Test-Path '%s')) { exit 2 }" % (tmp, DST, DST))
cmd = ["powershell", "-NoProfile", "-Command",
       "Start-Process powershell -Verb RunAs -Wait -ArgumentList '-NoProfile','-Command',"
       "'__INNER__'".replace("__INNER__", inner.replace("'", "''"))]
r = subprocess.run(cmd, capture_output=True, text=True)
print("elevated rc:", r.returncode, r.stdout.strip(), r.stderr.strip())

if os.path.exists(DST):
    dsz = os.path.getsize(DST)
    print("deployed:", DST, dsz, "match=", dsz == size)
    sys.exit(0 if dsz == size else 3)
print("DEPLOY FAILED: dst missing（UAC 窗口被取消？）")
sys.exit(4)