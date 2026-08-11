# Build promaid-1.0.0.jar from compiled classes + assets + data
import os, shutil, zipfile

BASE = os.path.dirname(os.path.abspath(__file__))
STAGING = os.path.join(BASE, 'staging_promaid')
OUT = os.path.join(BASE, 'out_promaid')
SRC = os.path.join(BASE, 'promaid_src')
JAR_OUT = os.path.join(BASE, 'patched', 'promaid-1.0.0.jar')

# 1. clean staging
for d in ['com', 'assets', 'data']:
    p = os.path.join(STAGING, d)
    if os.path.isdir(p):
        shutil.rmtree(p)

# 1b. (re)create META-INF with manifest + mods.toml (二进制 \r\n 防 v1.5.24 的 \r\r\n bug)
meta = os.path.join(STAGING, 'META-INF')
if os.path.isdir(meta):
    shutil.rmtree(meta)
os.makedirs(meta)
with open(os.path.join(meta, 'MANIFEST.MF'), 'wb') as f:
    f.write(b'Manifest-Version: 1.0\r\nMixinConfigs: mixins.promaid.json\r\nCreated-By: 21.0.7 (Microsoft)\r\n\r\n')
shutil.copy2(os.path.join(SRC, 'META-INF', 'mods.toml'), os.path.join(meta, 'mods.toml'))

# 2. copy compiled classes
shutil.copytree(os.path.join(OUT, 'com'), os.path.join(STAGING, 'com'))

# 3. copy assets (lang/models/builtin blueprints) + data (recipes)
shutil.copytree(os.path.join(SRC, 'assets'), os.path.join(STAGING, 'assets'))
shutil.copytree(os.path.join(SRC, 'data'), os.path.join(STAGING, 'data'))

# 4. mixins + pack.mcmeta
shutil.copy2(os.path.join(SRC, 'mixins.promaid.json'), os.path.join(STAGING, 'mixins.promaid.json'))
shutil.copy2(os.path.join(SRC, 'pack.mcmeta'), os.path.join(STAGING, 'pack.mcmeta'))

# 4b. LICENSE (MIT) 打进 jar 根目录
lic = os.path.join(SRC, 'LICENSE')
if os.path.isfile(lic):
    shutil.copy2(lic, os.path.join(STAGING, 'LICENSE'))

# 5. zip everything (jar)
if os.path.exists(JAR_OUT):
    os.remove(JAR_OUT)
with zipfile.ZipFile(JAR_OUT, 'w', zipfile.ZIP_DEFLATED) as z:
    for root, dirs, files in os.walk(STAGING):
        for f in sorted(files):
            full = os.path.join(root, f)
            rel = os.path.relpath(full, STAGING).replace('\\', '/')
            if rel == 'mods.toml':  # 只保留 META-INF/mods.toml
                continue
            z.write(full, rel)

# 6. verify
with zipfile.ZipFile(JAR_OUT) as z:
    names = z.namelist()
    required = ['META-INF/mods.toml', 'META-INF/MANIFEST.MF', 'mixins.promaid.json',
                'com/maidsmart/ProMaidMod.class', 'com/maidsmart/ProMaidExtension.class',
                'com/maidsmart/build/BlueprintBookItem.class', 'com/maidsmart/build/BlueprintBuildExecutor.class',
                'assets/maid_smart/models/item/blueprint_book.json',
                'assets/maid_smart/lang/zh_cn.json']
    missing = [r for r in required if r not in names]
    print('MISSING:', missing if missing else 'none')
    print('TOTAL entries:', len(names))
print('BUILT:', JAR_OUT, os.path.getsize(JAR_OUT), 'bytes')
