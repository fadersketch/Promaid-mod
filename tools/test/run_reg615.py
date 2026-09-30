# -*- coding: utf-8 -*-
"""六百一十五 的回归串：一条一条跑（同一个测试服，必须串行），每条的完整输出落一个日志文件。

用法: python tools/test/run_reg615.py <起始序号>
"""
import os
import subprocess
import sys
import time

# --- repo root resolution (this file lives in tools/<group>/) ---
def _find_repo_root(start):
    d = os.path.dirname(os.path.abspath(start))
    while True:
        if os.path.isdir(os.path.join(d, ".git")) or os.path.isfile(os.path.join(d, ".git")):
            return d
        parent = os.path.dirname(d)
        if parent == d:
            return os.path.dirname(os.path.abspath(start))
        d = parent
REPO = _find_repo_root(__file__)

ROOT = _find_repo_root(__file__)
RUNS = [
    ('spellfly613_1201', ['tools/test/test_spellfly613.py', '1201']),
    ('goto614_1201', ['tools/test/test_goto614.py', '1201']),
    ('flyfollow608_1201', ['tools/test/test_flyfollow608.py', '1201']),
    ('flight612_1201', ['tools/test/test_flight612.py', '1201']),
    ('flight611_1201', ['tools/test/test_flight611.py', '1201']),
    ('server_1201', ['tools/test/test_server.py', '1201']),
    ('server_neo1211', ['tools/test/test_server.py', 'neoforge1211']),
    ('maidload_1201', ['tools/test/test_maid_load.py', '1201']),
    ('maidload_neo1211', ['tools/test/test_maid_load.py', 'neoforge1211']),
]
start = int(sys.argv[1]) if len(sys.argv) > 1 else 0
for name, argv in RUNS[start:]:
    log = '%s/_reg615_%s.log' % (ROOT, name)
    print('=== running %s -> %s' % (argv, log), flush=True)
    t0 = time.time()
    with open(log, 'wb') as f:
        rc = subprocess.call([sys.executable, '-u'] + argv, cwd=ROOT,
                             stdout=f, stderr=subprocess.STDOUT)
    print('=== %s rc=%s（%.0f 秒）' % (name, rc, time.time() - t0), flush=True)
print('ALL DONE', flush=True)
