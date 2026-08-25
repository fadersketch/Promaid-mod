# -*- coding: utf-8 -*-
"""临时校验（终版）：按 unicode 转义形态匹配播报常量"""
import os
import subprocess
import sys
import zipfile

sys.stdout.reconfigure(encoding="utf-8")

JAR = r"D:\.minecraft\versions\1.20.1-Forge_47.4.23\mods\promaid-1.1.0.jar"
JAVAP = r"C:\Users\Sketch\AppData\Local\Programs\Microsoft\jdk-25.0.4.7-hotspot\bin\javap.exe"

# "保持原位" 的 unicode 转义
ESC = "".join("\\u%04x" % ord(c) for c in "保持原位")

with zipfile.ZipFile(JAR) as z:
    ok = True
    out = os.path.join(os.environ.get("TEMP", "."), "mclm2.class")
    with zipfile.ZipFile(JAR) as zz:
        with open(out, "wb") as f:
            f.write(zz.read("com/maidsmart/follow/MaidChunkLoadManager.class"))
    proc = subprocess.run([JAVAP, "-p", "-c", out], capture_output=True)
    txt = proc.stdout.decode("utf-8", errors="ignore")
    # 大小写不敏感（javap 可能输出大写十六进制）
    t_low = txt.lower()
    e_low = ESC.lower()
    hit = e_low in t_low or "保持原位" in txt
    print("[%s] tickPending 豁免播报常量存在（转义形态匹配=%s）" % ("PASS" if hit else "FAIL", hit))
    ok &= hit
    calls = txt.count("stayPut")
    print("[%s] summonAll 过滤 stayPut 引用 1 处（正确）" % ("PASS" if calls == 1 else "FAIL"))
    ok &= calls == 1
print("结论:", "ALL PASS" if ok else "存在 FAIL")
