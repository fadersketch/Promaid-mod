# -*- coding: utf-8 -*-
"""桌面「Promaid_本体当前」快照同步器（常驻工具）。

每次出包（build_promaid*.py 末尾会自动调用）把桌面快照刷成与仓库完全一致：
  · source/           = promaid_src / promaid_src_neo / libs_* / tools / 根文档 的工作树镜像
                        （跳过一切以 `_` 开头的路径段 = tools/_*.py 之类临时物）
  · 01_jar/           = patched/ 里刚打出的两个本体现 jar + RELEASE_NOTES
  · 说明.txt          = 按当前 RELEASE_NOTES 标题与 jar 哈希**重新生成**（不再手写）

用法：
    python tools/deploy/sync_desktop_snapshot.py            # 详细输出
    python tools/deploy/sync_desktop_snapshot.py --quiet    # 出包时调用，只打一行

幂等：重复跑不会改变结果；快照里多出来的旧文件会被删掉（只限镜像范围之内）。
"""
import hashlib
import io
import os
import re
import shutil
import sys
import time

sys.stdout.reconfigure(encoding='utf-8')


def _repo_root(start):
    d = os.path.dirname(os.path.abspath(start))
    while True:
        if os.path.isdir(os.path.join(d, '.git')) or os.path.isfile(os.path.join(d, '.git')):
            return d
        parent = os.path.dirname(d)
        if parent == d:
            return os.path.dirname(os.path.abspath(start))
        d = parent


BASE = _repo_root(__file__)
DESK = os.path.join(os.path.expanduser('~'), 'Desktop')
DST = os.path.join(DESK, 'Promaid_本体当前')
QUIET = '--quiet' in sys.argv[1:]

DIRS = ['promaid_src', 'promaid_src_neo', 'libs_forge', 'libs_neo', 'tools']
DOCS = ['ARCHITECTURE-DEBT.md', 'ARCHITECTURE.md', 'DEDICATED_SERVER_NOTES.md',
        'GIT_SYNC.md', 'HOW-TO-PUSH-CLEANED-HISTORY.md', 'PUBLISH_GUIDE.md', 'README.md',
        'release_body.md', 'release_body_neo.md', 'DEV_GUIDE.md', 'RELEASE_NOTES_v1.3.1.md']
JARS = ['promaid-1.3.2-neoforge-1.21.1.jar', 'promaid-1.3.2-forge-1.20.1.jar']
TARGETS = [r'D:\.minecraft\versions\1.21.1-NeoForge_21.1.250\mods',
           r'D:\.minecraft\versions\1.20.1-Forge_47.4.10\mods',
           r'D:\.minecraft\versions\龙之冒险：新征程v2.4a\mods',
           r'C:\Users\Sketch\mc_server_test\pack1201\mods']


def log(*a):
    if not QUIET:
        print(*a)


def sha(p):
    h = hashlib.sha256()
    with open(p, 'rb') as f:
        for b in iter(lambda: f.read(1 << 20), b''):
            h.update(b)
    return h.hexdigest()


def skip(rel):
    return any(p.startswith('_') for p in rel.replace('\\', '/').split('/'))


def migrate_legacy():
    """老的带日期快照（Promaid_本体当前_YYYYMMDD）→ 改成不带日期的固定名，避免每次留一堆。"""
    if os.path.isdir(DST):
        return None
    cands = []
    if os.path.isdir(DESK):
        for n in os.listdir(DESK):
            if n.startswith('Promaid_本体当前_') and os.path.isdir(os.path.join(DESK, n)):
                cands.append(n)
    if not cands:
        return None
    cands.sort()
    old = os.path.join(DESK, cands[-1])
    os.rename(old, DST)
    return os.path.join(DESK, cands[-1])


def mirror(sub):
    s = os.path.join(BASE, sub)
    d = os.path.join(DST, 'source', sub)
    if not os.path.isdir(s):
        log('   !! 缺目录 %s' % sub)
        return 0, 0, 0
    cop = dele = same = 0
    seen = set()
    for r, _, fs in os.walk(s):
        for fn in fs:
            sp = os.path.join(r, fn)
            rel = os.path.relpath(sp, s)
            if skip(rel):
                continue
            seen.add(rel)
            dp = os.path.join(d, rel)
            if os.path.isfile(dp) and os.path.getsize(dp) == os.path.getsize(sp) and sha(dp) == sha(sp):
                same += 1
                continue
            os.makedirs(os.path.dirname(dp), exist_ok=True)
            shutil.copy2(sp, dp)
            cop += 1
    if os.path.isdir(d):
        for r, ds, fs in os.walk(d, topdown=False):
            for fn in fs:
                rel = os.path.relpath(os.path.join(r, fn), d)
                if rel not in seen:
                    os.remove(os.path.join(r, fn))
                    dele += 1
            for x in ds:
                try:
                    os.rmdir(os.path.join(r, x))
                except OSError:
                    pass
    log('   %-18s 复制 %-5d 删除 %-5d 未变 %-5d' % (sub, cop, dele, same))
    return cop, dele, same


def round_title():
    """本版标题：取 RELEASE_NOTES_v1.3.1.md 首行（`# Promaid v1.3.1 …`）；读不到时退回 mods.toml 版本号。
    （仓库 changelog 已于实测八百五十六 撤掉，不再有 `CHANGELOG.md` 首行可读。）"""
    p = os.path.join(BASE, 'RELEASE_NOTES_v1.3.1.md')
    if os.path.isfile(p):
        first = io.open(p, encoding='utf-8').readline().strip()
        if first:
            return first.lstrip('# ').strip()
    p = os.path.join(BASE, 'promaid_src', 'META-INF', 'mods.toml')
    if os.path.isfile(p):
        m = re.search(r'(?m)^\s*version\s*=\s*"([^"]+)"',
                      io.open(p, encoding='utf-8', errors='ignore').read())
        if m:
            return 'Promaid v%s' % m.group(1)
    return '(未找到版本信息)'


def write_readme(jars):
    now = time.strftime('%Y-%m-%d %H:%M')
    L = []
    L.append('Promaid 本体当前版（自动同步快照 · 快照时间 %s）' % now)
    L.append('=' * 60)
    L.append('【定位】本体（正式线）当前版本的源码 + 构建产物，由 tools/deploy/sync_desktop_snapshot.py')
    L.append('  在每次出包后**自动刷新**（也可手动跑同一脚本），不需要再手动镜像。')
    L.append('  source/ = 工作树镜像（不含 tools/_* 这类临时脚本）；01_jar/ = 刚打出的两个 jar。')
    L.append('')
    L.append('【本版包含的更新】')
    L.append('  %s' % round_title())
    L.append('  （版本号 1.3.2：三份 mods.toml 与 jar 文件名、README.md 已统一。）')
    L.append('')
    L.append('【里面有什么】')
    L.append('  source/promaid_src/       Forge 1.20.1 树（SRG 名）')
    L.append('  source/promaid_src_neo/   NeoForge 1.21.1 树（Mojmap 名）')
    L.append('  source/tools/             构建 / 部署 / 校验 / 测试脚本')
    L.append('  source/libs_forge, libs_neo  编译所需的 TLM 前置 jar')
    L.append('  01_jar/                   两个本体现 jar + RELEASE_NOTES_v1.3.1.md')
    L.append('')
    L.append('【jar 是哪一份】')
    if jars:
        for fn, size, h in jars:
            L.append('  %-38s sha256[:16] = %s   大小 = %d' % (fn, h, size))
    else:
        L.append('  （patched/ 下暂无双包——先跑 tools/build/build_promaid*.py）')
    L.append('')
    L.append('【部署到哪儿】把 01_jar 里的同名文件覆盖到各实例 mods/（先删同名旧 jar），重启游戏。')
    for t in TARGETS:
        L.append('  ' + t)
    L.append('  或直接跑 source/tools/deploy/deploy_all.py 一次全推（自带备份、旧包清理与 SHA 核对）。')
    L.append('')
    L.append('【重新编译】')
    L.append('  cd <这个文件夹>\\source')
    L.append('  python tools/build/build_promaid_neo.py    (NeoForge 1.21.1)')
    L.append('  python tools/build/build_promaid.py        (Forge 1.20.1)')
    L.append('  两个脚本跑完会自动把本快照刷成最新。')
    L.append('')
    L.append('【边界】仓库没有 commit / push；快照不含构建中间产物（out_*/、staging_*/）与 tools/_* 临时脚本。')
    io.open(os.path.join(DST, '说明.txt'), 'w', encoding='utf-8', newline='').write('\n'.join(L) + '\n')


def main():
    moved = migrate_legacy()
    if moved:
        log('把旧快照改名成固定路径：%s -> %s' % (moved, DST))
    os.makedirs(os.path.join(DST, 'source'), exist_ok=True)
    os.makedirs(os.path.join(DST, '01_jar'), exist_ok=True)
    log('== 源码镜像 -> %s' % os.path.join(DST, 'source'))
    tot = [0, 0, 0]
    for sub in DIRS:
        c, dl, sm = mirror(sub)
        tot = [tot[0] + c, tot[1] + dl, tot[2] + sm]
    dc = 0
    for fn in DOCS:
        sp, dp = os.path.join(BASE, fn), os.path.join(DST, 'source', fn)
        if not os.path.isfile(sp):
            log('   !! 缺文档 %s' % fn)
            continue
        shutil.copy2(sp, dp)
        dc += 1
    log('   %-18s 复制 %-5d' % ('(根文档)', dc))
    log('   合计 复制 %d / 删除 %d / 未变 %d' % (tot[0] + dc, tot[1], tot[2]))

    jars = []
    # 01_jar 里只留"当前这一版"的两个 jar：别的版本一并删掉（同目录放着两版，
    # 玩家/开发者手一抖就会拿错包——部署脚本对 mods/ 也是同一口径）。
    jd = os.path.join(DST, '01_jar')
    if os.path.isdir(jd):
        for fn in sorted(os.listdir(jd)):
            if fn.startswith('promaid-') and fn.endswith('.jar') and fn not in JARS:
                try:
                    os.remove(os.path.join(jd, fn))
                    log('   01_jar 删掉旧版本 %s' % fn)
                except OSError:
                    pass
    for fn in JARS:
        sp = os.path.join(BASE, 'patched', fn)
        if not os.path.isfile(sp):
            log('   !! 缺 jar %s' % fn)
            continue
        dp = os.path.join(DST, '01_jar', fn)
        shutil.copy2(sp, dp)
        h = sha(dp)[:16]
        jars.append((fn, os.path.getsize(dp), h))
        log('   %-38s %d bytes  sha256[:16]=%s' % (fn, os.path.getsize(dp), h))
    rn = os.path.join(BASE, 'RELEASE_NOTES_v1.3.1.md')
    if os.path.isfile(rn):
        shutil.copy2(rn, os.path.join(DST, '01_jar', 'RELEASE_NOTES_v1.3.1.md'))
        shutil.copy2(rn, os.path.join(DST, 'RELEASE_NOTES_v1.3.1.md'))
    write_readme(jars)
    log('说明.txt 已按当前 RELEASE_NOTES 标题与 jar 哈希重新生成。')
    if QUIET:
        print('SNAPSHOT: %s  复制 %d / 删除 %d   %s'
              % (DST, tot[0] + dc + len(jars), tot[1],
                 '  '.join('%s=%s' % (f, h) for f, _, h in jars)))
    return 0


if __name__ == '__main__':
    sys.exit(main())
