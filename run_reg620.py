# -*- coding: utf-8 -*-
"""六百二十 的回归串（同一台测试服必须串行，一条一条跑）。

用法: python run_reg620.py [起始序号]

与 run_reg619.py 的差别：本批新增四条（压缩盒 box620 / 散步速度 stroll620，各两台服），
六百一十八 的 box618、六百一十九 的 apple619/target619 保留当回归
（本批动了"散步速度默认值 + 配置迁移"与"压缩盒存入口径"，跟这些挨着）。
"""
import subprocess
import sys
import time

ROOT = r'C:/Users/Sketch/.zcode/workspace/default/promaid-mod'
RUNS = [
    ('box620_1201', ['test_box620.py', '1201']),
    ('box620_neo1211', ['test_box620.py', 'neoforge1211']),
    ('stroll620_1201', ['test_stroll620.py', '1201']),
    ('stroll620_neo1211', ['test_stroll620.py', 'neoforge1211']),
    ('box618_1201', ['test_box618.py', '1201']),
    ('box618_neo1211', ['test_box618.py', 'neoforge1211']),
    ('apple619_1201', ['test_apple619.py', '1201']),
    ('apple619_neo1211', ['test_apple619.py', 'neoforge1211']),
    ('target619_1201', ['test_target619.py', '1201']),
    ('target619_neo1211', ['test_target619.py', 'neoforge1211']),
    ('spellfly613_1201', ['test_spellfly613.py', '1201']),
    ('goto614_1201', ['test_goto614.py', '1201']),
    ('flyfollow608_1201', ['test_flyfollow608.py', '1201']),
    ('flight612_1201', ['test_flight612.py', '1201']),
    ('flight611_1201', ['test_flight611.py', '1201']),
    ('server_1201', ['test_server.py', '1201']),
    ('server_neo1211', ['test_server.py', 'neoforge1211']),
    ('maidload_1201', ['test_maid_load.py', '1201']),
    ('maidload_neo1211', ['test_maid_load.py', 'neoforge1211']),
]
# 【别直接 int(sys.argv[1])】cmd.exe 下这个参数会被莫名其妙地传成 ';'（实测：run_reg618.py
# 都能收到一个 ';'，于是 int() 抛异常、整串一条都没跑）。只认纯数字，其余一律当没给。
start = int(sys.argv[1]) if len(sys.argv) > 1 and sys.argv[1].strip().isdigit() else 0
for name, argv in RUNS[start:]:
    log = '%s/_reg620_%s.log' % (ROOT, name)
    print('=== running %s -> %s' % (argv, log), flush=True)
    t0 = time.time()
    with open(log, 'wb') as f:
        rc = subprocess.call([sys.executable, '-u'] + argv, cwd=ROOT,
                             stdout=f, stderr=subprocess.STDOUT)
    print('=== %s rc=%s（%.0f 秒）' % (name, rc, time.time() - t0), flush=True)
print('ALL DONE', flush=True)
