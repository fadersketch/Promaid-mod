# Generate compile_promaid.txt / compile_heartfelt.txt from compile_addon.txt
# - classpath: smart_tlm.jar -> original_tlm.jar (原版 TLM，脱离补丁 jar)
# - sources: promaid_src / heartfelt_src
import os

BASE = os.path.dirname(os.path.abspath(__file__))

with open(os.path.join(BASE, 'compile_addon.txt'), 'r', encoding='utf-8-sig') as f:
    lines = f.read().splitlines()

# parse: --release / 17 / -proc:none / -classpath / <cp> / -d / <out> / sources...
assert lines[0].strip() == '--release' and lines[2].strip() == '-proc:none' and lines[3].strip() == '-classpath'
cp = lines[4].strip()
out = lines[6].strip()

# switch to original (vanilla) TLM jar
cp_new = cp.replace('smart_tlm.jar', 'original_tlm.jar')
assert 'original_tlm.jar' in cp_new and 'smart_tlm.jar' not in cp_new, cp_new

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
