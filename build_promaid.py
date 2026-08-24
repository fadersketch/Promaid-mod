# Build promaid-1.5.385.jar from compiled classes + assets + data
import os, shutil, zipfile, re, pathlib, json

BASE = os.path.dirname(os.path.abspath(__file__))
STAGING = os.path.join(BASE, 'staging_promaid')
OUT = os.path.join(BASE, 'out_promaid')
SRC = os.path.join(BASE, 'promaid_src')
JAR_OUT = os.path.join(BASE, 'patched', 'promaid-1.1.0.jar')

# 1. clean staging
for d in ['com', 'assets', 'data']:
    p = os.path.join(STAGING, d)
    if os.path.isdir(p):
        shutil.rmtree(p)

# 1a. v1.5.293閿涙碍绔婚梽?out 娑擃厽妫ゅ┃鎰垳閻ㄥ嫰妾查弮?class閿涘牆鍨归梽?闁插秴鎳￠崥宥嗙爱閺傚洣娆㈤崥?javac 娑撳秳绱板〒鍛倞
# 閺冄傞獓閻椻斁鈧柡鈧敊1.5.291 閸掔娀娅?MaidFeedAnimalCapMixin 閸氬骸鍙?.class 娑撯偓閻╃顫﹂幍鎾圭箻 jar閿涘鈧?# 閸栧綊鍘ら幐?閸?$ 閸氬海绱?閿涘牆鍞撮柈銊ц/閸栧灝鎮曠猾缁樼爱閻礁鎮?= 婢舵牕鐪扮猾浼欑礆閿涘矂妲荤拠顖氬灩閸氬牊纭堕崘鍛村劥缁楠囬悧鈹库偓?import re, pathlib
src_bases = set()
for p in pathlib.Path(SRC).rglob('*.java'):
    src_bases.add(str(p.relative_to(SRC)).replace('\\', '/')[:-len('.java')])
for p in pathlib.Path(OUT).rglob('*.class'):
    rel = str(p.relative_to(OUT)).replace('\\', '/')
    base = re.sub(r'\$.*$', '', rel[:-len('.class')])
    if base not in src_bases:
        p.unlink()
        print('purged stale class:', rel)

# v1.1.0 实测六十四：源文件比 class 新 = 上次 javac 失败/未覆盖 → 拒绝打包。
# 【事故背景】实测五十一起 ScheduleBookScreen.java 缺方法导致 javac 每次失败，
# 而 out 目录残留旧 class 被照常打包（verify 只对比 out↔jar 不对比 src）——
# 五十一~六十三的界面改动静默失效了十几个版本。此检查彻底堵死该类事故。
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

# 1b. (re)create META-INF with manifest + mods.toml (娴滃矁绻橀崚?\r\n 闂?v1.5.24 閻?\r\r\n bug)
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
            if rel == 'mods.toml':  # 閸欘亙绻氶悾?META-INF/mods.toml
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
        raise SystemExit('FATAL: jar 缂傚搫鐨箛鍛存付閺夛紕娲? %s' % missing)
    # v1.5.252q 閻戭厺鎱ㄦ径宥忕窗mixins.promaid.json 濞夈劌鍞介惃鍕槨娑?mixin 韫囧懘銆忛懗钘夋躬 jar 闁插本澹橀崚鏉款嚠鎼?    # class閳ユ柡鈧柨鎯侀崚娆忔儙閸斻劌宓?MixinApplyError 瀹曗晜绨濋敍鍦昲airNoDropMixin 濠曞繒绱拠鎴滅皑閺佸懐娈戦弫娆掝唲閿?    import json
    mc = json.loads(z.read('mixins.promaid.json'))
    allm = mc['mixins'] + mc.get('client', [])
    no_class = [m for m in allm if ('com/maidsmart/mixin/' + m + '.class') not in names]
    if no_class:
        raise SystemExit('FATAL: jar 缂傚搫鐨?mixin class: %s閿涘牆鍘涚捄?gen_compile.py 閸愬秶绱拠鎴礆' % no_class)
    print('MISSING: none')
    print('TOTAL entries:', len(names))
print('BUILT:', JAR_OUT, os.path.getsize(JAR_OUT), 'bytes')

# v1.5.283閿涙碍鐎鍝勬倵閼奉亜濮╅妴鎰蓟閸氭垵鍙忛柌蹇嬧偓鎴︾崣鐠?jar vs out閿涘牊妫悧?verify_jar_classes.py 閸欘亝鐓?# 6 娑擃亝瀵氱€规氨琚?閳?SelfPreservationBehavior$BlockCheck 缂傚搫銇戞禒搴㈡弓鐞氼偄褰傞悳?閳?鏉╂劘顢戦弮?findWater
# 閹虫帒濮炴潪?ClassNotFoundException 瀹曗晜绨濋敍娑氬箛閸?out 閸忋劑鍎?.class 韫囧懘銆忕€涙ê婀稉鏂挎惐鐢奔绔撮懛杈剧礆
import subprocess, sys
rc = subprocess.call([sys.executable, os.path.join(BASE, 'verify_jar_classes.py')])
if rc != 0:
    raise SystemExit('FATAL: jar 娑?out 閸欏苯鎮滈崗銊╁櫤妤犲矁鐦夐張顏堚偓姘崇箖')



