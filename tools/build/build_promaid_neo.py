# Build promaid neoforge 1.21.1 jar from compiled classes + assets + data
import os, shutil, zipfile, re, pathlib, json

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

BASE = _find_repo_root(__file__)
STAGING = os.path.join(BASE, 'staging_promaid_neo')
OUT = os.path.join(BASE, 'out_promaid_neo')
SRC = os.path.join(BASE, 'promaid_src_neo')
JAR_OUT = os.path.join(BASE, 'patched', 'promaid-1.3.2-neoforge-1.21.1.jar')

# 实测九百一十六 / v1.5.400：mixin 注入点审计闸门（只读，硬闸门）——javac 只保证注解字符串是
# 合法 Java，**不保证目标类真有这个方法、更不保证签名对得上**；没有 refmap 的手工编译里名字
# 逐字匹配，对不上就等那个类第一次被加载时崩（916：女仆一生成就崩，裸名字命中了协变桥接）。
# 早先这里探的是从不入库的 helper（_mixchk.py），于是常年静默跳过；现在工具入库（tools/verify/），
# 缺了也当 FATAL——宁可不出包，也不出必崩的包。
_audit = os.path.join(BASE, 'tools', 'verify', 'check_mixin_targets.py')
if not os.path.isfile(_audit):
    raise SystemExit('FATAL: 缺 tools/verify/check_mixin_targets.py（mixin 注入点审计）——拒绝打包')
import subprocess as _sp
import sys as _sys
if _sp.call([_sys.executable, _audit, '--tree', 'promaid_src_neo']) != 0:
    raise SystemExit('FATAL: mixin 注入点审计不通过（tools/verify/check_mixin_targets.py）——拒绝打包')

# 1. clean staging
for d in ['com', 'assets', 'data']:
    p = os.path.join(STAGING, d)
    if os.path.isdir(p):
        shutil.rmtree(p)

# 1a. purge stale classes (no matching .java source)
src_bases = set()
for p in pathlib.Path(SRC).rglob('*.java'):
    src_bases.add(str(p.relative_to(SRC)).replace('\\', '/')[:-len('.java')])
purged = 0
for p in pathlib.Path(OUT).rglob('*.class'):
    rel = str(p.relative_to(OUT)).replace('\\', '/')
    base = re.sub(r'\$.*$', '', rel[:-len('.class')])
    if base not in src_bases:
        p.unlink()
        purged += 1
        print('purged stale class:', rel)
print('purged:', purged)

# stale check: source newer than class => refuse
stale = []
for p in pathlib.Path(SRC).rglob('*.java'):
    rel = str(p.relative_to(SRC)).replace('\\', '/')
    base = rel[:-len('.java')]
    cls = pathlib.Path(OUT, base + '.class')
    if not cls.exists():
        stale.append(rel + ' (无编译产物)')
    elif p.stat().st_mtime > cls.stat().st_mtime:
        stale.append(rel + ' (源比 class 新)')
if stale:
    raise SystemExit('FATAL: 以下源文件未成功编译（javac 失败或未重跑），拒绝打包旧字节码:\n  ' + '\n  '.join(stale))

# 1b. META-INF: manifest + neoforge.mods.toml
meta = os.path.join(STAGING, 'META-INF')
if os.path.isdir(meta):
    shutil.rmtree(meta)
os.makedirs(meta)
with open(os.path.join(meta, 'MANIFEST.MF'), 'wb') as f:
    f.write(b'Manifest-Version: 1.0\r\nMixinConfigs: mixins.promaid.json\r\nCreated-By: 21.0.7 (Microsoft)\r\n\r\n')
toml_src = os.path.join(SRC, 'META-INF', 'neoforge.mods.toml')
if not os.path.isfile(toml_src):
    # keep one canonical toml inside the source tree too
    os.makedirs(os.path.join(SRC, 'META-INF'), exist_ok=True)
    shutil.copy2(os.path.join(STAGING, 'META-INF', 'neoforge.mods.toml'), toml_src)
shutil.copy2(toml_src, os.path.join(meta, 'neoforge.mods.toml'))

# 2. compiled classes
shutil.copytree(os.path.join(OUT, 'com'), os.path.join(STAGING, 'com'))

# 3. assets + data
shutil.copytree(os.path.join(SRC, 'assets'), os.path.join(STAGING, 'assets'))
shutil.copytree(os.path.join(SRC, 'data'), os.path.join(STAGING, 'data'))

# 3a. 语言文件语法校验（实测四百六十九：一份 lang 多一个尾逗号 → 整份被客户端跳过 →
#     任务名/配置项全变键名。这里在打包前卡死，避免坏文件再次进 jar）
import json as _json
for _p in pathlib.Path(SRC, 'assets').rglob('lang/*.json'):
    try:
        _json.loads(_p.read_text(encoding='utf-8-sig'))
    except Exception as _e:
        raise SystemExit('FATAL: 语言文件 JSON 非法（会导致整份被客户端跳过）: %s -> %s' % (_p, _e))
print('lang json: OK')

# 4. mixins + pack.mcmeta + LICENSE
shutil.copy2(os.path.join(SRC, 'mixins.promaid.json'), os.path.join(STAGING, 'mixins.promaid.json'))
shutil.copy2(os.path.join(SRC, 'pack.mcmeta'), os.path.join(STAGING, 'pack.mcmeta'))
lic = os.path.join(BASE, 'LICENSE')
if os.path.isfile(lic):
    shutil.copy2(lic, os.path.join(STAGING, 'LICENSE'))

# 5. zip
os.makedirs(os.path.dirname(JAR_OUT), exist_ok=True)
if os.path.exists(JAR_OUT):
    os.remove(JAR_OUT)
with zipfile.ZipFile(JAR_OUT, 'w', zipfile.ZIP_DEFLATED) as z:
    for root, dirs, files in os.walk(STAGING):
        for f in sorted(files):
            full = os.path.join(root, f)
            rel = os.path.relpath(full, STAGING).replace('\\', '/')
            # 固定归档时间戳：打包时间不再进 jar 字节 → 同一份源码必出同一个 sha256，
            # patched/、桌面快照、四个部署目标的 hash 就永远对得上（不再"重建即漂移"）。
            zi = zipfile.ZipInfo(rel, date_time=(2020, 1, 1, 0, 0, 0))
            zi.compress_type = zipfile.ZIP_DEFLATED
            zi.external_attr = 0o644 << 16
            with open(full, 'rb') as fh:
                z.writestr(zi, fh.read())

# 6. verify
with zipfile.ZipFile(JAR_OUT) as z:
    names = z.namelist()
    required = ['META-INF/neoforge.mods.toml', 'META-INF/MANIFEST.MF', 'mixins.promaid.json',
                'com/maidsmart/ProMaidMod.class', 'com/maidsmart/ProMaidExtension.class',
                'com/maidsmart/build/BlueprintBookItem.class',
                'assets/maid_smart/models/item/blueprint_book.json',
                'assets/maid_smart/lang/zh_cn.json']
    missing = [r for r in required if r not in names]
    if missing:
        raise SystemExit('FATAL: jar 缺少必需条目: %s' % missing)
    mc = json.loads(z.read('mixins.promaid.json'))
    allm = mc['mixins'] + mc.get('client', [])
    no_class = [m for m in allm if ('com/maidsmart/mixin/' + m + '.class') not in names]
    if no_class:
        raise SystemExit('FATAL: jar 缺少 mixin class: %s' % no_class)
    print('MISSING: none')
    print('TOTAL entries:', len(names))
print('BUILT:', JAR_OUT, os.path.getsize(JAR_OUT), 'bytes')

# v1.2.5 实测六百五十三：与 forge 侧同口径的「jar vs out」一致性闸门
# （编译了却没进 jar 的内部类 = 客户端 ClassNotFoundException；这里卡死）
import subprocess, sys

rc = subprocess.call([sys.executable, os.path.join(BASE, 'tools/verify/verify_jar_classes.py'), JAR_OUT, OUT])
if rc != 0:
    raise SystemExit('FATAL: jar 与 out 目录内容不一致（缺 class，发布前必须修）')

# 文案/注释护栏（DEV_GUIDE §6）+ 架构结构数据保鲜（tools/arch/ 的产物只许来自真源，
# 含覆盖棘轮 / 陈旧 / 文档漂移）：当前历史存量未清零，先以告警形式跑，不清零也不阻断出包。
# 存量清零后把 --warn 去掉即可改为阻断。
for _g in ('tools/verify/check_comment_style.py',
           'tools/verify/check_preset.py', 'tools/verify/check_workflow.py',
           'tools/verify/check_catch_swallow.py', 'tools/verify/check_i18n_keys.py',
           'tools/verify/check_dev_voice.py', 'tools/verify/check_comment_dup.py',
           'tools/verify/check_reflection.py', 'tools/verify/check_arch.py',
           'tools/verify/check_guide_i18n.py'):
    _p = os.path.join(BASE, _g)
    if os.path.isfile(_p):
        subprocess.call([sys.executable, _p, '--warn'])

# 桌面快照自动同步（DEV_GUIDE §3 步骤 6）：源码镜像 + 两个 jar + 说明.txt，免手动刷。
_sync = os.path.join(BASE, 'tools/deploy/sync_desktop_snapshot.py')
if os.path.isfile(_sync):
    subprocess.call([sys.executable, _sync, '--quiet'])