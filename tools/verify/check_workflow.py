# -*- coding: utf-8 -*-
"""工作流护栏（实测八百三十六，规则见 WORKFLOW.md §5/§8/§9）。

检查项（FAIL 以退出码 1 结束；--warn 时只打印）：
  1. 版本纪律：两树 mods.toml 必须同号（FAIL）；jar 文件名 / README / PUBLISH_GUIDE
     与 mods.toml 不一致（WARN）——#37 第 4 条。
     （仓库 changelog 已于实测八百五十六 撤掉，版本位不再有 `CHANGELOG.md` 首行这一处。）
  2. 发布登记：rounds/RELEASES.tsv 里 published 行与 patched/ 里同号 jar 哈希不符（FAIL，= 同号覆盖）；
     当前版本的 jar 未登记（WARN）；更早的 jar 未登记（聚合 WARN，能归就归——补登记只记本地哈希，
     不主张是否上过平台）。已交付但归档里没有文件的历史发布件不告警（实测九百五十七，拉倒）。
  3. 轮次记录：rounds/ 里最新的 RNNN-*.md 必须含 ## 排班 / ## 验证 / ## 验收 / ## 边界。
  4. 台账：DEBT.md 存在；BD-*/TD-* 行有级别与状态。
  5. 配额（§8）：最新轮次记录标为 FEATURE 轮时，不得存在未清的 P0/P1 债。
  6. 配置清单不过期：ARCHITECTURE-SWITCHES.md 与重新生成的内容一致。

用法: python tools/verify/check_workflow.py [--warn]
"""
import glob
import hashlib
import io
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
FAILS, WARNS = [], []


def fail(m):
    FAILS.append(m)


def warn(m):
    WARNS.append(m)


def read(rel):
    p = os.path.join(ROOT, rel)
    if not os.path.isfile(p):
        return None
    return io.open(p, encoding='utf-8', errors='ignore').read()


def mod_version(rel):
    t = read(rel)
    if t is None:
        return None
    m = re.search(r'(?m)^\s*version\s*=\s*"([^"]+)"', t)
    return m.group(1) if m else None


def jar_version(name):
    m = re.match(r'promaid-([0-9][^-]*?)(?:-forge|-neoforge|\.jar)', name)
    return m.group(1) if m else None


def jar_loader(name):
    if '-neoforge-' in name:
        return 'neoforge'
    if '-forge-' in name:
        return 'forge'
    return 'forge'


def sha256(path):
    return hashlib.sha256(io.open(path, 'rb').read()).hexdigest()


CN_DIGITS = {'零': 0, '一': 1, '二': 2, '两': 2, '三': 3, '四': 4, '五': 5,
             '六': 6, '七': 7, '八': 8, '九': 9}
CN_UNITS = {'十': 10, '百': 100, '千': 1000, '万': 10000}


def cn_number(s):
    """中文数字 → int（实测NNN 里的轮次号是中文数字，如「八百三十六」= 836）；不认识返回 None。"""
    if re.fullmatch(r'\d+', s):
        return int(s)
    total, section, num = 0, 0, 0
    for ch in s:
        if ch in CN_DIGITS:
            num = CN_DIGITS[ch]
        elif ch in CN_UNITS:
            u = CN_UNITS[ch]
            if u == 10000:
                section = (section + num) * u
                total += section
                section, num = 0, 0
            else:
                section += (num or 1) * u
                num = 0
        else:
            return None
    return total + section + num


def check_versions():
    vf = mod_version('promaid_src/META-INF/mods.toml')
    vn = mod_version('promaid_src_neo/META-INF/neoforge.mods.toml')
    print('--- 版本号')
    print('    promaid_src/META-INF/mods.toml                = %s' % vf)
    print('    promaid_src_neo/META-INF/neoforge.mods.toml   = %s' % vn)
    if vf is None or vn is None:
        fail('读不到两树 mods.toml 的 version（路径或格式变了？）')
        return None
    if vf != vn:
        fail('两树版本号不一致：Forge=%s / Neo=%s（跨加载器必须同号）' % (vf, vn))

    jars = sorted(os.path.basename(p) for p in glob.glob(os.path.join(ROOT, 'patched', '*.jar')))
    vjar = jar_version(jars[-1]) if jars else None
    rdm = re.search(r'版本\s*\*\*v?([0-9.]+)\*\*', read('README.md') or '')
    vreadme = rdm.group(1) if rdm else None
    pgm = re.search(r'版本：v?([0-9.]+)', read('PUBLISH_GUIDE.md') or '')
    vguide = pgm.group(1) if pgm else None
    print('    jar 文件名 = %s ｜ README = v%s ｜ PUBLISH_GUIDE = v%s'
          % (vjar, vreadme, vguide))
    for label, v in (('patched/ jar 文件名', vjar),
                     ('README.md', vreadme), ('PUBLISH_GUIDE.md', vguide)):
        if v is None:
            warn('%s 里读不到版本号（格式变了？）' % label)
        elif v != vf:
            warn('%s 的版本 %s ≠ mods.toml 的 %s（发布前按 WORKFLOW.md §5 一次对齐四处）'
                 % (label, v, vf))
    return vf


def check_registry(cur_version):
    print('--- 发布登记 rounds/RELEASES.tsv')
    t = read('rounds/RELEASES.tsv')
    if t is None:
        warn('rounds/RELEASES.tsv 不存在（发布后必须登记版本与哈希，WORKFLOW.md §5.3）')
        return
    rows = []
    for i, line in enumerate(t.splitlines(), 1):
        if not line.strip() or line.startswith(('#', '>')):
            continue
        f = line.split('\t')
        if len(f) < 7:
            warn('RELEASES.tsv:%d 列数不足 7（用制表符分隔）' % i)
            continue
        rows.append({'ver': f[0], 'loader': f[1], 'file': f[2], 'sha': f[3],
                     'date': f[4], 'status': f[5], 'note': f[6]})
    print('    共 %d 行：published %d / published-overwritten %d / workspace %d / archived %d'
          % (len(rows), sum(1 for r in rows if r['status'] == 'published'),
             sum(1 for r in rows if r['status'] == 'published-overwritten'),
             sum(1 for r in rows if r['status'] == 'workspace'),
             sum(1 for r in rows if r['status'] == 'archived')))
    known, cur_seen, others = set(), set(), []
    # 同号 jar 可能因命名方案变更而改名（1.2.5 起加 `-forge-1.20.1`）→ 按 (版本, 加载器) 找
    archive = {}
    for p in glob.glob(os.path.join(ROOT, 'patched', '*.jar')):
        n = os.path.basename(p)
        archive.setdefault((jar_version(n), jar_loader(n)), []).append(p)
    for r in rows:
        known.add((r['ver'], r['loader']))
        if r['status'] != 'published':
            continue
        # 真正的危险是「同名文件、内容不同」（玩家下到的是同名包）→ 只对同名文件比对哈希
        fname = os.path.basename(r['file'])
        path = os.path.join(ROOT, 'patched', fname)
        if not os.path.isfile(path):
            same_ver = [os.path.basename(p) for p in archive.get((r['ver'], r['loader']), [])]
            if r['ver'] != cur_version:
                # 历史发布件：文件早就不在归档里了（玩家手上那份仍在平台上）——无从核对，不告警
                print('    （历史件 %s 不在归档，跳过核对；归档里同版本另有 %s）'
                      % (fname, '、'.join(same_ver) if same_ver else '无'))
                continue
            warn('发布件「%s」（%s / %s）不在 patched/ 归档里，无法核对哈希；'
                 '归档里同版本的其它文件（%s）不能代表发布件'
                 % (fname, r['ver'], r['loader'], '、'.join(same_ver) if same_ver else '无'))
            continue
        h = sha256(path)
        ok = (h[:8] == r['sha'][:8] and h[-6:] == r['sha'][-6:]) if '…' in r['sha'] \
            else (h == r['sha'])
        if not ok:
            msg = ('同名不同内容：%s(%s)「%s」发布时登记为 %s，但 patched/ 里同名文件实测 %s…%s'
                   % (r['ver'], r['loader'], fname, r['sha'], h[:8], h[-6:]))
            if r['status'] == 'published-overwritten':
                warn(msg + '（历史遗留：该同名文件已被后来的重打覆盖，见 RELEASES.tsv 备注；'
                           '新发布禁止用这个状态）')
            else:
                fail(msg + '（= #37 第 4 条那个问题，WORKFLOW.md §5.2 禁止：'
                           '已发布的同名包绝不能再改内容）')
    for p in sorted(glob.glob(os.path.join(ROOT, 'patched', '*.jar'))):
        name = os.path.basename(p)
        v, ld = jar_version(name), jar_loader(name)
        if v == cur_version:
            cur_seen.add((v, ld))
        elif (v, ld) not in known:
            others.append('%s(%s)' % (v, ld))
    for v, ld in sorted(cur_seen - known):
        warn('当前版本 %s(%s) 的 jar 未登记（发布/交付后按 §5.3 补一行并冻结哈希）' % (v, ld))
    if others:
        warn('更早的 jar 未登记（%d 个：%s）——归档用，需要时补登记' % (len(others), '、'.join(others)))


# 轮次记录取自 rounds/ 目录本身（不再有 CHANGELOG 可以对照）。
# 旧的「进了 CHANGELOG 就必须已验收」门禁随 changelog 一起撤掉（实测八百五十六）；
# 「## 验收」小节本身仍是必填（WORKFLOW.md §4.1）。


def check_round_record():
    print('--- 轮次记录 rounds/（排班 / 验证 / 验收 / 边界）')
    nums = []
    for p in glob.glob(os.path.join(ROOT, 'rounds', 'R*.md')):
        m = re.match(r'R(\d+)-', os.path.basename(p))
        if m:
            nums.append(int(m.group(1)))
    if not nums:
        warn('rounds/ 下找不到 RNNN-*.md 轮次记录（WORKFLOW.md §9；从 TEMPLATE.md 复制）')
        return None
    latest = max(nums)
    print('    rounds/ 最新轮次 = R%d' % latest)
    cands = [p for p in glob.glob(os.path.join(ROOT, 'rounds', 'R%d-*.md' % latest))
             if os.path.basename(p) != 'TEMPLATE.md']
    if not cands:
        warn('最新轮次 R%d 没有对应记录（WORKFLOW.md §9）' % latest)
        return latest
    text = io.open(cands[0], encoding='utf-8').read()
    # 开轮排班（§1 硬门禁）与验收结论（§4.1）为必填；验证/边界沿用原要求
    for sec in ('## 排班', '## 验证', '## 验收', '## 边界'):
        if sec not in text:
            warn('%s 缺小节「%s」（WORKFLOW.md §1/§4.1/§9）'
                 % (os.path.basename(cands[0]), sec))
    return latest


def check_ledgers():
    print('--- 台账 DEBT.md')
    # 唯一台账 DEBT.md：BD-*（ID | 级别 | 症状 | 证据 | 状态）、TD-*（ID | 主题 | 证据 | 影响 | 建议做法 | 状态）
    t = read('DEBT.md')
    if t is None:
        warn('DEBT.md 不存在（WORKFLOW.md §8；原 BUG_DEBT / TECH_DEBT 已并入）')
        return
    n_ok = n_bad = 0
    for line in t.splitlines():
        is_bd = line.startswith('| BD-')
        if not is_bd and not line.startswith('| TD-'):
            continue
        cells = [c.strip() for c in line.strip('|').split('|')]
        if len(cells) < 4:
            n_bad += 1
            continue
        ok_level = (not is_bd) or any(lv in cells[1] for lv in ('P0', 'P1', 'P2', 'P3'))
        ok_status = any(st in cells[-1] for st in ('未清', '已清', '已确认', '部分'))
        if ok_level and ok_status:
            n_ok += 1
        else:
            n_bad += 1
    print('    DEBT.md：合规行 %d / 不合规 %d' % (n_ok, n_bad))
    if n_bad:
        warn('DEBT.md 有 %d 行的级别或状态列不合格式（BD 级别 P0–P3；状态 未清/已清@轮次/已确认/部分）'
             % n_bad)


def check_quota(latest_round):
    rec = [p for p in glob.glob(os.path.join(ROOT, 'rounds', 'R%d-*.md' % latest_round))
           if os.path.basename(p) != 'TEMPLATE.md']
    if not rec:
        return
    text = io.open(rec[0], encoding='utf-8').read()
    is_feature = bool(re.search(r'跑道\**：\s*B|FEATURE', text))
    if not is_feature:
        return
    debt = read('DEBT.md') or ''
    open_p01 = []
    for line in debt.splitlines():
        if not line.startswith('| BD-'):
            continue
        cells = [c.strip() for c in line.strip('|').split('|')]
        if len(cells) >= 5 and any(lv in cells[1] for lv in ('P0', 'P1')) \
                and '未清' in cells[4]:
            open_p01.append('%s(%s)' % (cells[0], cells[1]))
    if open_p01:
        warn('配额（WORKFLOW.md §8）：最新轮次 实测%d 是功能轮，但仍有未清的 P0/P1 债 %s'
             '——功能轮必须同时清一条 P1+ 债，或在轮次记录里写明「为什么这轮先做功能」'
             % (latest_round, '、'.join(open_p01)))


def check_inventory():
    print('--- 配置清单 ARCHITECTURE-SWITCHES.md')
    p = os.path.join(ROOT, 'ARCHITECTURE-SWITCHES.md')
    if not os.path.isfile(p):
        warn('ARCHITECTURE-SWITCHES.md 不存在（python tools/verify/gen_switch_inventory.py --write）')
        return
    try:
        import importlib.util
        spec = importlib.util.spec_from_file_location(
            'gen_switch_inventory', os.path.join(ROOT, 'tools/verify/gen_switch_inventory.py'))
        mod = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(mod)
        fresh = mod.render()
    except Exception as e:  # noqa: BLE001
        warn('无法重新生成配置清单以比对：%s' % e)
        return
    old = io.open(p, encoding='utf-8').read()
    if old.strip() != fresh.strip():
        warn('ARCHITECTURE-SWITCHES.md 已过期（配置项变了）：重跑 '
             '`python tools/verify/gen_switch_inventory.py --write`')
    else:
        print('    与重新生成的结果一致')


def main():
    warn_only = '--warn' in sys.argv
    cur = check_versions()
    check_registry(cur)
    latest = check_round_record()
    check_ledgers()
    if latest is not None:
        check_quota(latest)
    check_inventory()
    print()
    for w in WARNS:
        print('WARN ' + w)
    for f in FAILS:
        print('FAIL ' + f)
    print('check_workflow: FAIL %d / WARN %d' % (len(FAILS), len(WARNS)))
    if FAILS and not warn_only:
        sys.exit(1)


if __name__ == '__main__':
    main()
