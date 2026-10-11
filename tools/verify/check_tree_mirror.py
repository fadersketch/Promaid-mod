# -*- coding: utf-8 -*-
"""两树镜像审计（DEBT.md §4.1 步骤 1 / §6 阶段⑥ 第一步）。

两棵源码树 `promaid_src`（Forge 1.20.1，SRG 名）与 `promaid_src_neo`（NeoForge 1.21.1，Mojmap 名）
按仓库铁律「纯 Java/字符串文件除映射名与 loader 包名外应逐字节一致」。

**只读报告**（不 FAIL、不写源码）：
  - 逐字节相同 / 有差异 的个数；
  - 有差异里，「行数一致但内容不同」多为**注释里的映射名引用**（合法差异），
    「行数也不同」则更可能是**真漂移**（DEBT §4.1：Δ 越大越危险）；
  - 按行数差排序列出 Top 漂移文件。

用法: python tools/verify/check_tree_mirror.py [--dump] [--top N]
"""
import io
import os
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
A = 'promaid_src'
B = 'promaid_src_neo'


def read_norm(p):
    with io.open(p, encoding='utf-8', errors='ignore', newline='') as f:
        return f.read().replace('\r\n', '\n').replace('\r', '\n')


def rel_files(tree):
    out = set()
    for dp, dn, fn in os.walk(os.path.join(ROOT, tree)):
        for f in fn:
            if f.endswith('.java'):
                out.add(os.path.relpath(os.path.join(dp, f), os.path.join(ROOT, tree)))
    return out


def main():
    dump = '--dump' in sys.argv
    top = 15
    if '--top' in sys.argv:
        top = int(sys.argv[sys.argv.index('--top') + 1])
    ra, rb = rel_files(A), rel_files(B)
    shared = sorted(ra & rb)
    same, samelen, drift = 0, 0, []
    for rel in shared:
        ta = read_norm(os.path.join(ROOT, A, rel))
        tb = read_norm(os.path.join(ROOT, B, rel))
        if ta == tb:
            same += 1
            continue
        na, nb = ta.count('\n'), tb.count('\n')
        if na == nb:
            samelen += 1
        else:
            drift.append((abs(na - nb), rel, na, nb))
    drift.sort(reverse=True)
    print('--- 同路径 .java：%d 个（A 独有 %d / B 独有 %d）'
          % (len(shared), len(ra - rb), len(rb - ra)))
    print('--- 逐字节相同 %d / 有差异 %d（其中行数一致 %d、行数不同 %d）'
          % (same, len(shared) - same, samelen, len(drift)))
    print('--- 行数差 Top%d（DEBT §4.1：Δ 越大越危险；多为映射名/注释差异，未必功能不同）:' % top)
    for d, rel, na, nb in drift[:top]:
        print('      Δ%-4d A=%-5d B=%-5d %s' % (d, na, nb, rel))
    if dump:
        for d, rel, na, nb in drift:
            print('      DRIFT Δ%d %s' % (d, rel))
    print('check_tree_mirror: 报告完成（只读，不设门槛）')


if __name__ == '__main__':
    main()
