# -*- coding: utf-8 -*-
import io
import sys
sys.stdout.reconfigure(encoding="utf-8")
t = io.open(r"C:\Users\Sketch\.zcode\workspace\default\promaid-mod\compile_out.txt",
            encoding="utf-8", errors="replace").read().splitlines()
errs = [l for l in t if "error" in l.lower() or "EXITCODE" in l]
for l in errs[:30]:
    print(ascii(l[:140]))
print("total lines:", len(t))
