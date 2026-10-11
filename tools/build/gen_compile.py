# Generate compile_promaid.txt / compile_heartfelt.txt from compile_addon.txt
# - classpath: smart_tlm.jar -> original_tlm.jar (原版 TLM，脱离补丁 jar)
# - sources: promaid_src / heartfelt_src
import os

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

with open(os.path.join(BASE, 'compile_addon.txt'), 'r', encoding='utf-8-sig') as f:
    lines = f.read().splitlines()

# parse: --release / 17 / -proc:none / -classpath / <cp> / -d / <out> / sources...
assert lines[0].strip() == '--release' and lines[2].strip() == '-proc:none' and lines[3].strip() == '-classpath'
cp = lines[4].strip()
out = lines[6].strip()

# switch to original (vanilla) TLM jar
cp_new = cp.replace('smart_tlm.jar', 'original_tlm.jar')
assert 'original_tlm.jar' in cp_new and 'smart_tlm.jar' not in cp_new, cp_new

# --- 路径可配置（TD-13）：compile_addon.txt 里写死的是本机绝对路径，换台机器 / 换仓库位置就编译不了。
# 默认值与当前本机一致，因此不设环境变量时输出逐字节不变；设了就把前缀换掉：
#   PROMAID_LIB      <Minecraft 根>/libraries（默认 D:/.minecraft/libraries）
#   PROMAID_MC_HOME  Minecraft 根（默认 D:/.minecraft），用于 <MC_HOME>/versions/... 里的模组 jar
#   （仓库自身位置自动按本文件的真实路径重算，clone 到别处也不用手改）
_MC = os.environ.get('PROMAID_MC_HOME') or 'D:/.minecraft'
_LIB = os.environ.get('PROMAID_LIB') or (_MC.rstrip('/') + '/libraries')
_cp_before = cp_new
cp_new = cp_new.replace('D:/.minecraft/libraries', _LIB.rstrip('/'))
cp_new = cp_new.replace('D:/.minecraft/versions', _MC.rstrip('/') + '/versions')
BASE_FWD = BASE.replace('\\', '/')
cp_new = cp_new.replace('C:/Users/Sketch/.zcode/workspace/default/promaid-mod', BASE_FWD)
if (cp_new != _cp_before or _LIB != 'D:/.minecraft/libraries'):
    print('classpath roots: LIB=%s  MC_HOME=%s  repo=%s' % (_LIB, _MC, BASE_FWD))

_missing = [e for e in cp_new.split(';') if e and not os.path.exists(e)]
if _missing:
    # 编译不受影响（javac 忽略不存在的 classpath 项），但换机器时多半就是这里要调——
    # 按 BUILD.md 设 PROMAID_LIB / PROMAID_MC_HOME 指到自己的 Minecraft 根即可。
    print('WARN classpath 有 %d 项不存在（换机器请设 PROMAID_LIB / PROMAID_MC_HOME）：%s%s'
          % (len(_missing), _missing[0], ' …' if len(_missing) > 1 else ''))

def gen(name, src_dir, out_dir):
    src_root = os.path.join(BASE, src_dir)
    sources = []
    for root, dirs, files in os.walk(src_root):
        for fn in sorted(files):
            if fn.endswith('.java'):
                sources.append(os.path.join(root, fn).replace('\\', '/'))
    argfile = os.path.join(BASE, name)
    with open(argfile, 'w', encoding='utf-8', newline='\n') as f:
        # -encoding UTF-8：源文件为 UTF-8（含中文注释），防止 javac 按平台 GBK 误读
        f.write('--release\n17\n-proc:none\n-encoding\nUTF-8\n-classpath\n%s\n-d\n%s\n' % (cp_new, os.path.join(BASE, out_dir).replace('\\', '/')))
        for s in sources:
            f.write(s + '\n')
    print('%s: %d sources' % (argfile, len(sources)))

gen('compile_promaid.txt', 'promaid_src', 'out_promaid')
gen('compile_heartfelt.txt', 'heartfelt_src', 'out_heartfelt')
print('DONE')
