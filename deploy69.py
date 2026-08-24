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
