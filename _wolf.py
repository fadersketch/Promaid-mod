# -*- coding: utf-8 -*-
"""临时：用 Wolf 的字节码确认 m_21674_/m_6779_ 哪个是 isAngryAt"""
import os
import subprocess
import sys
import zipfile

sys.stdout.reconfigure(encoding="utf-8")

JAR = r"D:\.minecraft\libraries\net\minecraft\client\1.20.1-20230612.114412\client-1.20.1-20230612.114412-srg.jar"
JAVAP = r"C:\Users\Sketch\AppData\Local\Programs\Microsoft\jdk-25.0.4.7-hotspot\bin\javap.exe"

with zipfile.ZipFile(JAR) as z:
    out = os.path.join(os.environ.get("TEMP", "."), "Wolf.class")
    with open(out, "wb") as f:
        f.write(z.read("net/minecraft/world/entity/animal/wolf/Wolf.class"))

txt = subprocess.run([JAVAP, "-p", "-c", out], capture_output=True, text=True,
                     errors="ignore").stdout
lines = txt.split("\n")
# 找 m_21674_ 与 m_6779_ 的定义段并打印前若干行逻辑
for key in ("m_21674_", "m_6779_", "m_21660_"):
    idx = None
    for i, l in enumerate(lines):
        if (" " + key + "(") in l:
            idx = i
            break
    if idx is None:
        print("== %s 未在 Wolf 中出现 ==" % key)
        continue
    print("=" * 30, key)
    for l in lines[idx:idx + 28]:
        print(l[:150])
