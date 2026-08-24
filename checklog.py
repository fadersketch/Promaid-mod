# -*- coding: utf-8 -*-
import io
import sys
sys.stdout.reconfigure(encoding="utf-8")
t = io.open(r"C:\Users\Sketch\.zcode\workspace\default\promaid-mod\compile_out.txt",
            encoding="utf-8", errors="replace").read().splitlines()
for l in t:
    print(ascii(l[:160]))
