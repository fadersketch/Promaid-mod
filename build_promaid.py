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

# 1a. v1.5.293：清除 out 中无源码的陈旧 class（删除/重命名源文件后 javac 不会清理
# 旧产物——v1.5.291 删除 MaidFeedAnimalCapMixin 后其 .class 一直被打进 jar）。
# 匹配按"去 $ 后缀"（内部类/匿名类源码名 = 外层类），防误删合法内部类产物。
import re, pathlib
src_bases = set()
for p in pathlib.Path(SRC).rglob('*.java'):
    src_bases.add(str(p.relative_to(SRC)).replace('\\', '/')[:-len('.java')])
for p in pathlib.Path(OUT).rglob('*.class'):
    rel = str(p.relative_to(OUT)).replace('\\', '/')
    base = re.sub(r'\$.*$', '', rel[:-len('.class')])
    if base not in src_bases:
        p.unlink()
        print('purged stale class:', rel)

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
    if missing:
        raise SystemExit('FATAL: jar 缺少必需条目: %s' % missing)
    # v1.5.252q 热修复：mixins.promaid.json 注册的每个 mixin 必须能在 jar 里找到对应
    # class——否则启动即 MixinApplyError 崩溃（ChairNoDropMixin 漏编译事故的教训）
    import json
    mc = json.loads(z.read('mixins.promaid.json'))
    allm = mc['mixins'] + mc.get('client', [])
    no_class = [m for m in allm if ('com/maidsmart/mixin/' + m + '.class') not in names]
    if no_class:
        raise SystemExit('FATAL: jar 缺少 mixin class: %s（先跑 gen_compile.py 再编译）' % no_class)
    print('MISSING: none')
    print('TOTAL entries:', len(names))
print('BUILT:', JAR_OUT, os.path.getsize(JAR_OUT), 'bytes')

# v1.5.283：构建后自动【双向全量】验证 jar vs out（旧版 verify_jar_classes.py 只查
# 6 个指定类 → SelfPreservationBehavior$BlockCheck 缺失从未被发现 → 运行时 findWater
# 懒加载 ClassNotFoundException 崩溃；现在 out 全部 .class 必须存在且哈希一致）
import subprocess, sys
rc = subprocess.call([sys.executable, os.path.join(BASE, 'verify_jar_classes.py')])
if rc != 0:
    raise SystemExit('FATAL: jar 与 out 双向全量验证未通过')

