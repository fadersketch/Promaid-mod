# -*- coding: utf-8 -*-
"""Build the architecture deliverable in three ordered steps.

    python tools/arch/build_arch.py

Steps (order matters: the freeze receipt must be computed last, after the HTML):
    1. gen_arch.py       write modules/renders/tree.json  (L1+L2 gate, fail-closed)
    2. render_arch.py    render normify.html
    3. finalize_arch.py  recompute receipt.json over the final artifacts
"""
import os, subprocess, sys

REPO = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
HERE = os.path.dirname(os.path.abspath(__file__))
STEPS = ["gen_arch.py", "render_arch.py", "finalize_arch.py"]

rc_all = 0
for i, step in enumerate(STEPS, 1):
    print(f"\n=== [{i}/{len(STEPS)}] {step} ===", flush=True)
    rc = subprocess.call([sys.executable, os.path.join(HERE, step)], cwd=REPO)
    if rc != 0:
        print(f"FAILED at {step} (rc={rc})", flush=True)
        rc_all = rc
        break
print("\ndone" if rc_all == 0 else "\nFAILED")
sys.exit(rc_all)
