# Build promaid neoforge 1.21.1 jar from compiled classes + assets + data
import os, shutil, zipfile, re, pathlib, json

BASE = os.path.dirname(os.path.abspath(__file__))
STAGING = os.path.join(BASE, 'staging_promaid_neo')
OUT = os.path.join(BASE, 'out_promaid_neo')
SRC = os.path.join(BASE, 'promaid_src_neo')
JAR_OUT = os.path.join(BASE, 'patched', 'promaid-1.1.0-neoforge-1.21.1.jar')

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