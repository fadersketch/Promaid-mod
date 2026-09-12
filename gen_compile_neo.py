"""Generate compile_neo.txt (javac @argfile) for the NeoForge 1.21.1 port.

Classpath:
  - client-1.21.1-20240808.144430-slim.jar  (Minecraft, Mojang-mapped)
  - neoforge-21.1.250-universal.jar / -client.jar (NeoForge APIs)
  - TLM neoforge jar (touhoulittlemaid-1.5.3-neoforge+mc1.21.1.jar)
  - MixinExtras (find 0.x for 1.21), Mixin library (spongepowered mixin via FML boot? compile-time: use
    the 'mixin' classes inside neoforge client jar or MixinExtras jar)
  - guava/gson/netty/etc come from libraries dir (needed only if referenced; javac needs transitive types)
Scan source list from promaid_src_neo.
"""
import os, glob, zipfile

MOD = r'C:\Users\Sketch\.zcode\workspace\default\promaid-mod'
SRC = os.path.join(MOD, 'promaid_src_neo')

def find(*cands):
    for c in cands:
        if os.path.exists(c):
            return c
    return None

LIB = r'D:\.minecraft\libraries'
neo = find(os.path.join(LIB, r'net\neoforged\neoforge\21.1.250\neoforge-21.1.250-client.jar'),
           os.path.join(LIB, r'net\neoforged\neoforge\21.1.250\neoforge-21.1.250-universal.jar'))
neou = os.path.join(LIB, r'net\neoforged\neoforge\21.1.250\neoforge-21.1.250-universal.jar')
mc = find(os.path.join(LIB, r'net\minecraft\client\1.21.1-20240808.144430\client-1.21.1-20240808.144430-srg.jar'))
tlm = find(r'D:\.minecraft\versions\1.21.1-NeoForge_21.1.250\mods\touhoulittlemaid-1.5.3-neoforge+mc1.21.1.jar')
if tlm is None:
    for p in glob.glob(r'D:\.minecraft\versions\1.21.1-NeoForge_21.1.250\mods\*touhoulittlemaid*.jar'):
        tlm = p
        break

cp = [neo, mc, neou, tlm]
# NeoForge 修补过的类必须排在 vanilla 前面（与运行时一致）：ServerPlayer$RespawnPosAngle
# 在未修补的 vanilla jar 里是包私有，只有 NeoForge 修补版是 public——
# 顺位反了会让"给女仆床实现 getRespawnPosition"编译不过（实测）。
# NeoForge platform libs (event bus, FML loader, lwjgl, distmarker)
import glob as _g
cp += [
    os.path.join(LIB, r'net\neoforged\bus\8.0.5\bus-8.0.5.jar'),
    os.path.join(LIB, r'net\neoforged\fancymodloader\loader\4.0.44\loader-4.0.44.jar'),
    os.path.join(LIB, r'net\neoforged\mergetool\2.0.0\mergetool-2.0.0-api.jar'),  # Dist/distmarker stub
]
# distmarker: inside neoforge client jar? verify; lwjgl from lwjgl dir
for p in _g.glob(os.path.join(LIB, 'org\\lwjgl\\lwjgl\\*\\lwjgl-*.jar')):
    cp.append(p)
for p in _g.glob(os.path.join(LIB, 'org\\lwjgl\\lwjgl-glfw\\*\\lwjgl-glfw-*.jar')):
    cp.append(p)
# copy jars to ASCII-safe paths (javac argfile encoding chokes on CJK paths)
import shutil
libdir = os.path.join(MOD, 'libs_neo')
os.makedirs(libdir, exist_ok=True)
safe = []
for c in cp:
    if c is None:
        continue
    if all(ord(ch) < 128 for ch in c):
        safe.append(c)
    else:
        dst = os.path.join(libdir, os.path.basename(c).replace('[车万女仆] ', '').replace(' ', '_'))
        if not os.path.exists(dst):
            shutil.copy2(c, dst)
        safe.append(dst)
cp = safe
# minimal support libs (compile-time transitive types commonly referenced by signatures we touch)
common = [
    os.path.join(LIB, r'com\google\guava\guava\33.6.0-jre\guava-33.6.0-jre.jar'),
    os.path.join(LIB, r'com\google\code\gson\gson\2.10.1\gson-2.10.1.jar'),
    os.path.join(LIB, r'io\netty\netty-buffer\4.1.82.Final\netty-buffer-4.1.82.Final.jar'),
    os.path.join(LIB, r'io\netty\netty-common\4.1.82.Final\netty-common-4.1.82.Final.jar'),
    os.path.join(LIB, r'io\netty\netty-transport\4.1.82.Final\netty-transport-4.1.82.Final.jar'),
    os.path.join(LIB, r'org\slf4j\slf4j-api\2.0.9\slf4j-api-2.0.9.jar'),
    os.path.join(LIB, r'org\spongepowered\mixin\0.8.7\mixin-0.8.7.jar'),
    os.path.join(LIB, r'io\github\llamalad7\mixinextras-forge\0.5.4\mixinextras-forge-0.5.4.jar'),
    os.path.join(LIB, r'org\ow2\asm\asm\9.6\asm-9.6.jar'),
    os.path.join(LIB, r'org\ow2\asm\asm-commons\9.6\asm-commons-9.6.jar'),
    os.path.join(LIB, r'org\ow2\asm\asm-tree\9.6\asm-tree-9.6.jar'),
    os.path.join(LIB, r'org\joml\joml\1.10.5\joml-1.10.5.jar'),
    os.path.join(LIB, r'it\unimi\dsi\fastutil\8.5.18\fastutil-8.5.18.jar'),
    os.path.join(LIB, r'org\apache\commons\commons-lang3\3.12.0\commons-lang3-3.12.0.jar'),
    os.path.join(LIB, r'com\mojang\authlib\9.0.75\authlib-9.0.75.jar'),
    os.path.join(LIB, r'com\mojang\brigadier\1.3.10\brigadier-1.3.10.jar'),
    os.path.join(LIB, r'com\mojang\datafixerupper\8.0.16\datafixerupper-8.0.16.jar'),
    os.path.join(LIB, r'com\mojang\javabridge\1.2.24\javabridge-1.2.24.jar'),
    os.path.join(LIB, r'com\mojang\logging\1.7.12\logging-1.7.12.jar'),
    os.path.join(LIB, r'org\apache\maven\maven-artifact\3.8.8\maven-artifact-3.8.8.jar'),
    os.path.join(LIB, r'com\google\code\findbugs\jsr305\3.0.2\jsr305-3.0.2.jar'),
    os.path.join(LIB, r'org\checkerframework\checker-qual\3.33.0\checker-qual-3.33.0.jar'),
]
for c in common:
    p = os.path.join(LIB, c)
    if os.path.exists(p):
        cp.append(p)

# mixin / mixinextras availability check
mixinjar = find(os.path.join(LIB, r'org\spongepowered\mixin\mixin\0.8.5\mixin-0.8.5.jar'))
print('mixin jar:', mixinjar)

cp = [c for c in cp if c]
print('classpath entries:')
for c in cp:
    print('  ', c, os.path.getsize(c) if os.path.exists(c) else 'MISSING')

# source list
srcs = []
for dp, dn, fn in os.walk(SRC):
    for f in fn:
        if f.endswith('.java'):
            srcs.append(os.path.join(dp, f))
print('sources:', len(srcs))

out = ['-d', os.path.join(MOD, 'out_promaid_neo').replace('\\', '/'),
       '--release', '21',
       '-proc:none', '-nowarn', '-encoding', 'UTF-8',
       '-Xmaxerrs', '5000',
       '-classpath', ';'.join(cp).replace('\\', '/')]
out += [s.replace('\\', '/') for s in srcs]
with open(os.path.join(MOD, 'compile_neo.txt'), 'w', encoding='ascii', errors='replace') as fp:
    fp.write(' '.join('"%s"' % s for s in out))
print('saved compile_neo.txt')