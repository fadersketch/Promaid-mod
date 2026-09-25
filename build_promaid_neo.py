# Build promaid neoforge 1.21.1 jar from compiled classes + assets + data
import os, shutil, zipfile, re, pathlib, json

BASE = os.path.dirname(os.path.abspath(__file__))
STAGING = os.path.join(BASE, 'staging_promaid_neo')
OUT = os.path.join(BASE, 'out_promaid_neo')
SRC = os.path.join(BASE, 'promaid_src_neo')
JAR_OUT = os.path.join(BASE, 'patched', 'promaid-1.3.0-neoforge-1.21.1.jar')

# 实测六百六十八：mixin 注入点审计闸门（只读）——javac 只保证注解字符串是合法 Java，**不保证
# "目标类真的自己声明了这个方法"**；那种错要等游戏启动时 Mixin 才报，直接崩在 Bootstrap
# （667 的武装拴绳就是这么把游戏搞开不起来的）。审计脚本 _mixchk.py 按本仓库惯例不入库
# （helper 一律 _* ），所以缺了只告警不拦路。
_mixchk = os.path.join(BASE, '_mixchk.py')
if os.path.isfile(_mixchk):
    import subprocess as _sp
    import sys as _sys
    if _sp.call([_sys.executable, _mixchk]) != 0:
        raise SystemExit('FATAL: mixin 注入点审计不通过（详见 _out_mixchk.txt）——拒绝打包')
else:
    print('WARN: 未找到 _mixchk.py，跳过 mixin 注入点审计（该脚本不入库）')

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
            z.write(full, rel)

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
rc = subprocess.call([sys.executable, os.path.join(BASE, 'verify_jar_classes.py'), JAR_OUT, OUT])
if rc != 0:
    raise SystemExit('FATAL: jar 与 out 目录内容不一致（缺 class，发布前必须修）')