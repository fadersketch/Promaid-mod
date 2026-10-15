# -*- coding: utf-8 -*-
"""mixin 注入点审计（补回构建脚本一直在找、但已从工作区消失的 _mixchk.py 的角色）。

用法: python tools/verify/check_mixin_targets.py [--warn] [--measure] [--tree promaid_src]

为什么需要它：
  javac 只保证 @Inject(method="…") 里的字符串是合法 Java；**不保证目标类真的有这个方法、
  更不保证签名对得上**。没有 refmap 的手工编译里，method 字符串按运行时名逐字匹配，
  名字对不上/签名对不上都要等游戏启动、那个类第一次被加载时才炸（女仆一生成就崩溃）。

判定（FAIL 退出码 1）：
  B. 写全签名的注入点，签名必须真实存在于目标类或其父类链上（用 javap -s 的真实 JVM 描述符
     比对——不能比 javap 的显示形态：那是 `.` 分隔、带泛型、写 `int` 的，跟描述符永远对不上）。
     同名候选签名不同 → FAIL 并列出。
  C. @Inject 的处理方法参数类型必须 = 目标方法参数 + 回调参数（回调之外一个不能多、不能少）。
  D. 裸名字注入点若在目标类链上有 **多个不同签名** 的同名方法 → FAIL。这正是「女仆一生成就崩」
     那类事故：Forge 会在重映射后的类里留一个协变桥接（参数是父类型、名字被重映射成同一个
     SRG 名），裸名字会命中桥接而不是自己那个重载。
"""
import io
import os
import re
import subprocess
import sys

sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8', errors='replace')
ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
TREES = ['promaid_src', 'promaid_src_neo']

INJECTIONS = ['Inject', 'Redirect', 'ModifyArg', 'ModifyVariable', 'ModifyConstant',
              'ModifyArgs', 'ModifyExpressionValue', 'WrapOperation', 'WrapWithCondition']
PRIM = {'int': 'I', 'long': 'J', 'boolean': 'Z', 'float': 'F', 'double': 'D',
        'byte': 'B', 'char': 'C', 'short': 'S', 'void': 'V'}
CB_INFO = 'Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;'
CB_RET = 'CALLBACK_RET'

FAILS, WARNS, UNVERIFIED, BARENAMES = [], [], [], []
CHECKED = []  # 真正比对过（签名存在 + 处理方法参数一致）的注入点——防止"审计空跑却报 FAIL 0"
_javap_cache = {}
MIN_PER_TREE = 100  # 单棵树低于这个数就是漏扫（forge 133 / neo 132），宁可炸也别静默放过
TREES_DONE = 0


def javap_bin():
    jh = os.environ.get('JAVA_HOME')
    cands = []
    if jh:
        cands.append(os.path.join(jh, 'bin', 'javap.exe'))
    cands.append(os.path.join(os.environ.get('APPDATA', ''), '.minecraft', 'runtime',
                              'java-runtime-delta', 'bin', 'javap.exe'))
    for c in cands:
        if os.path.isfile(c):
            return c
    return 'javap'


def classpath_of(tree):
    """从 compile_<tree>.txt（javac @argfile）里抠出 -classpath 的那一串 jar"""
    name = 'compile_neo.txt' if tree.endswith('_neo') else 'compile_promaid.txt'
    path = os.path.join(ROOT, name)
    if not os.path.isfile(path):
        return ''
    toks = [t.strip('"') for t in io.open(path, encoding='utf-8', errors='ignore').read().split()]
    if '-classpath' not in toks:
        return ''
    i = toks.index('-classpath')
    out = []
    for t in toks[i + 1:]:
        if t.startswith('-'):
            break
        t = t.strip(';')
        if t.endswith('.jar'):
            out.append(t)
    return ';'.join(out)


def javap(fqcn, cp):
    """→ (list[(name, jvm_descriptor)], superclass or None)；-s 给出的才是真实描述符。"""
    key = (fqcn, cp)
    if key in _javap_cache:
        return _javap_cache[key]
    methods, sup = [], None
    try:
        out = subprocess.run([javap_bin(), '-p', '-s', '-classpath', cp, fqcn],
                             capture_output=True, text=True, timeout=60).stdout
    except Exception as e:
        WARNS.append('javap 失败 %s: %s' % (fqcn, e))
        out = ''
    simple = fqcn.split('.')[-1]
    pending = None
    for ln in out.splitlines():
        s = ln.strip()
        if not s or s.startswith('Compiled from') or s.startswith('}'):
            continue
        if sup is None:
            h = re.search(r'\bclass\s+[\w.$]+(?:<[^>]*>)?\s+extends\s+([\w.$]+)', s)
            if h:
                sup = h.group(1)
        if s.startswith('descriptor:'):
            if pending is not None:
                methods.append((pending, s.split('descriptor:', 1)[1].strip()))
            pending = None
            continue
        # 成员声明行：… 名字(参数);
        if s.endswith(';') and '(' in s and ')' in s:
            head = s[:s.index('(')].strip()
            nm = head.split()[-1].split('<')[0] if head.split() else ''
            if nm == simple or nm.endswith('.' + simple):
                nm = '<init>'
            pending = nm if nm else None
        else:
            pending = None
    _javap_cache[key] = (methods, sup)
    return methods, sup


def chain_methods(fqcn, cp, depth=8):
    """目标类 + 父类链上的全部方法（name, 描述符, 声明类）"""
    seen = []
    cur = fqcn
    while cur and depth > 0:
        ms, sup = javap(cur, cp)
        if not ms:
            break
        seen.extend((cur, n, d) for n, d in ms)
        cur = sup
        depth -= 1
    return seen


def desc_params(desc):
    """'(Lnet/…;J)V' → ['Lnet/…;', 'J']"""
    inner = desc[1:desc.rindex(')')]
    out, i = [], 0
    while i < len(inner):
        c = inner[i]
        if c == '[':
            j = i
            while inner[j] == '[':
                j += 1
            if inner[j] == 'L':
                k = inner.index(';', j)
                out.append(inner[i:k + 1])
                i = k + 1
            else:
                out.append(inner[i:j + 1])
                i = j + 1
        elif c == 'L':
            k = inner.index(';', i)
            out.append(inner[i:k + 1])
            i = k + 1
        else:
            out.append(c)
            i += 1
    return out


def imports_of(src):
    out = {}
    for m in re.finditer(r'^import\s+(?:static\s+)?([\w.$]+);', src, re.M):
        fq = m.group(1)
        out[fq.split('.')[-1]] = fq
    return out


def type_desc(java_type, imports, pkg):
    """Java 源码类型 → JVM 描述符；解析不了返回 None"""
    t = java_type.strip()
    t = re.sub(r'<.*>', '', t).strip()
    t = re.sub(r'\b(final|@\w+(\([^)]*\))?)\s*', '', t).strip()
    dims = 0
    while t.endswith('[]'):
        dims += 1
        t = t[:-2].strip()
    if t in PRIM:
        d = PRIM[t]
    elif t == 'CallbackInfo':
        d = CB_INFO
    elif t == 'CallbackInfoReturnable':
        return CB_RET
    else:
        fq = None
        segs = t.split('.')
        if len(segs) > 1 and segs[0] and segs[0][0].isupper():
            # 嵌套类写法（Entity.MoveFunction / EntityRendererProvider.Context）：
            # 首段是类名（大写开头），后面每一段都是内嵌一级 → 描述符分隔符是 $ 不是 /
            base = imports.get(segs[0])
            if base is None:
                return None  # 首段没 import：不猜，交给调用方记 WARN 跳过
            fq = '$'.join([base] + segs[1:])
        elif '.' in t:
            fq = t
        elif t in imports:
            fq = imports[t]
        elif t in ('String', 'Object', 'Integer', 'Boolean', 'Long', 'Double', 'Float',
                   'Class', 'Iterable', 'Comparable', 'Runnable', 'Exception', 'Throwable'):
            fq = 'java.lang.' + t
        elif t in ('List', 'Map', 'Set', 'ArrayList', 'Collection', 'Optional'):
            fq = 'java.util.' + t
        if fq is None:
            return None  # 裸短名、且没 import——解析不了，调用方记 WARN 跳过
        d = 'L' + fq.replace('.', '/') + ';'
    return '[' * dims + d


NAME = r'[A-Za-z_$][\w$]*'


def handler_after(src, pos):
    """取注解之后那个方法声明的 (名字, 参数表源码, 返回类型源码)

    名字里的 `$` 必须算进标识符（本库的方法都叫 {@code maidsmart$xxx} / {@code promaid$xxx}，
    早先只写 \\w+ 时一个都匹配不上，等于整条签名比对规则被静默跳过）。"""
    m = re.compile(r'\b(?:private|protected|public)\s+(?:static\s+|final\s+)*'
                   r'[\w.$<>\[\], ?]+\s+(' + NAME + r')\s*\(([^)]*)\)').search(src, pos)
    if not m:
        return None, None, None
    head = src[m.start():m.end()]
    ret = re.match(r'\b(?:private|protected|public)\s+(?:static\s+|final\s+)*'
                   r'([\w.$<>\[\], ?]+?)\s+' + NAME + r'\s*\(', head)
    return m.group(1), m.group(2), (ret.group(1) if ret else None)


def split_params(s):
    out, depth, cur = [], 0, ''
    for ch in s:
        if ch in '<([':
            depth += 1
        elif ch in '>)]':
            depth -= 1
        if ch == ',' and depth == 0:
            out.append(cur)
            cur = ''
        else:
            cur += ch
    if cur.strip():
        out.append(cur)
    return [p.strip() for p in out if p.strip()]


def check_handler(rel, anno, mpos, src, imports, pkg, target, mdesc, mname):
    """规则 C：@Inject 处理方法的参数表 = 目标参数 + 回调"""
    hname, hparams, _hret = handler_after(src, mpos)
    if hparams is None:
        WARNS.append('%s: 取不到 @%s 的处理方法参数表（跳过签名比对）' % (rel, anno))
        return
    want = desc_params(mdesc)
    got, bad = [], None
    for p in split_params(hparams):
        is_local = bool(re.match(r'@\w*Local\b', p))
        pp = re.sub(r'@\w+(\([^)]*\))?\s*', '', p).strip()
        if pp.endswith('...'):
            pp = pp[:-3].strip() + '[]'
        toks = pp.split()
        if len(toks) < 2:
            bad = p
            break
        d = type_desc(' '.join(toks[:-1]), imports, pkg)
        if d is None:
            bad = p
            break
        got.append((d, is_local))
    if bad is not None:
        WARNS.append('%s: 处理方法 %s 的参数类型 %r 解析不了（跳过比对）' % (rel, hname, bad))
        return
    cb_ok = False
    for i in range(len(got) - 1, -1, -1):
        if not got[i][1]:
            if got[i][0] == CB_RET or got[i][0] == CB_INFO:
                got.pop(i)
                cb_ok = True
            break
    if not cb_ok:
        WARNS.append('%s: 处理方法 %s 最后一个非 @Local 参数不是回调（跳过比对）' % (rel, hname))
        return
    nongot = [d for d, loc in got if not loc]
    if nongot != want:
        FAILS.append('%s: @Inject 处理方法 %s 的参数与目标 %s%s 对不上：目标 %s / 处理方法 %s'
                     % (rel, hname, target, mdesc, want, nongot))


def read_string_expr(text, key):
    r"""key = "…" 或 key = "…" + "…"（跨行拼接）→ 拼接后的字符串；找不到返回 None"""
    m = re.search(r'\b' + key + r'\s*=\s*"', text)
    if not m:
        return None
    out, i = '', m.end() - 1
    while i < len(text) and text[i] == '"':
        j = i + 1
        buf = ''
        while j < len(text) and text[j] != '"':
            if text[j] == '\\':
                buf += text[j + 1]
                j += 2
                continue
            buf += text[j]
            j += 1
        out += buf
        i = j + 1
        while i < len(text) and text[i] in ' \t\r\n':
            i += 1
        if i < len(text) and text[i] == '+':
            i += 1
            while i < len(text) and text[i] in ' \t\r\n':
                i += 1
    return out


def mixin_targets(src):
    """@Mixin(...) 里的目标类名（源码写法）"""
    out = []
    for m in re.finditer(r'@Mixin\s*\(([^)]*)\)', src):
        body = m.group(1)
        tm = re.search(r'targets?\s*=\s*"([^"]+)"', body)
        if tm:
            out.append(tm.group(1))
            continue
        for c in re.findall(r'([\w.$]+)\.class', body):
            out.append(c)
    return out


def check_file(path, tree, cp):
    src = io.open(path, encoding='utf-8', errors='ignore').read()
    if '@Mixin' not in src:
        return
    imports = imports_of(src)
    pkg = ''
    pm = re.search(r'^\s*package\s+([\w.]+)\s*;', src, re.M)
    if pm:
        pkg = pm.group(1)
    # 一个文件里可以有多个 @Mixin（外层类 + 内部类各挂一个）：每个注入点只跟**它上面最近的那个**
    # @Mixin 配对——早先拿全文件的目标类逐个去套每个注入点，会把内部类的注入点拿去查外层类。
    blocks = []
    for m in re.finditer(r'@Mixin\s*\(([^)]*)\)', src):
        body = m.group(1)
        tm = re.search(r'targets?\s*=\s*"([^"]+)"', body)
        raws = [tm.group(1)] if tm else re.findall(r'([\w.$]+)\.class', body)
        res = []
        for t in raws:
            fq = t if '.' in t else imports.get(t) or (pkg + '.' + t if t[0].islower() else None)
            if fq is None:
                WARNS.append('%s: 目标类 %s 解析不出全名（跳过）' % (path, t))
                continue
            res.append(fq)
        blocks.append((m.start(), res))
    if not blocks:
        return
    rel = os.path.relpath(path, ROOT).replace('\\', '/')
    for anno in INJECTIONS:
        for m in re.finditer(r'@' + anno + r'\s*\(', src):
            seg = src[m.end():m.end() + 600]
            spec = read_string_expr(seg, 'method')
            if not spec:
                spec = read_string_expr(seg, 'target')
            if not spec or spec.startswith('*'):
                continue
            owner = None
            for pos, res in blocks:
                if pos < m.start():
                    owner = res
                else:
                    break
            if not owner:
                continue
            for target in owner:
                candidates = chain_methods(target, cp)
                mname = spec[:spec.index('(')] if '(' in spec else spec
                if mname == '<init>':
                    # 构造器不继承：只在目标类自己身上找（父类的同名 <init> 不是它的方法）
                    candidates = [c for c in candidates if c[0] == target]
                if '(' in spec:
                    mdesc = spec[spec.index('('):]
                    exact = [c for c in candidates if c[1] == mname and c[2] == mdesc]
                    if not exact:
                        same = [c for c in candidates if c[1] == mname]
                        if same:
                            FAILS.append('%s: @%s 注入点 %s 在 %s（含父类）里没有这个签名；'
                                         '同名候选：%s'
                                         % (rel, anno, spec, target,
                                            ', '.join('%s%s @%s' % (n, d, w) for w, n, d in same)))
                        else:
                            FAILS.append('%s: @%s 注入点 %s 在 %s（含父类）里根本不存在'
                                         % (rel, anno, spec, target))
                        continue
                else:
                    same = [c for c in candidates if c[1] == mname]
                    if not same:
                        FAILS.append('%s: @%s 注入点 %s 在 %s（含父类）里根本不存在'
                                     % (rel, anno, spec, target))
                        continue
                    distinct = sorted(set(c[2] for c in same))
                    if len(distinct) > 1:
                        FAILS.append('%s: @%s 注入点用裸名字 %s，在 %s（含父类）里有 %d 个不同签名的同名方法'
                                     '（Mixin 只能瞎挑一个，多半命中协变桥接）——候选：%s'
                                     % (rel, anno, spec, target, len(distinct),
                                        ', '.join('%s%s @%s' % (n, d, w)
                                                  for w, n, d in same)))
                        continue
                    mdesc = distinct[0]
                    BARENAMES.append('%s: @%s 裸名字 %s → %s%s（唯一，可校验；建议补全签名）'
                                     % (rel, anno, spec, target, mdesc))
                if anno != 'Inject':
                    CHECKED.append('%s: @%s %s @%s' % (rel, anno, spec, target))
                    continue
                before = len(FAILS)
                check_handler(rel, anno, m.end(), src, imports, pkg, target, mdesc, mname)
                if len(FAILS) == before:
                    CHECKED.append('%s: @%s %s @%s' % (rel, anno, spec, target))


def main():
    global TREES_DONE
    measure = '--measure' in sys.argv
    only = None
    if '--tree' in sys.argv:
        only = sys.argv[sys.argv.index('--tree') + 1]
    for tree in TREES:
        if only and tree != only and not tree.startswith(only):
            continue
        cp = classpath_of(tree)
        if not cp:
            WARNS.append('%s: 取不到 classpath（先跑 gen_compile*.py）' % tree)
            continue
        n = 0
        for dp, dn, fn in os.walk(os.path.join(ROOT, tree)):
            for f in sorted(fn):
                if f.endswith('.java'):
                    check_file(os.path.join(dp, f), tree, cp)
                    n += 1
        TREES_DONE += 1
        print('--- %s：扫描 %d 个 .java' % (tree, n))
    if measure:
        for f in BARENAMES:
            print('MEASURE ' + f)
        for f in FAILS:
            print('MEASURE ' + f)
        return
    print()
    print('（裸名字注入点 %d 个——不是门槛，是建议补全签名的清单；可用 --measure 列全）'
          % len(BARENAMES))
    for w in WARNS:
        print('WARN ' + w)
    for f in FAILS:
        print('FAIL ' + f)
    floor = MIN_PER_TREE * max(1, TREES_DONE)
    print('check_mixin_targets: FAIL %d / WARN %d / 已校验注入点 %d（下限 %d）'
          % (len(FAILS), len(WARNS), len(CHECKED), floor))
    if len(CHECKED) < floor:
        print('FAIL 审计疑似空跑：只校验了 %d 个注入点（下限 %d）——先确认每棵树的 classpath 都能取到'
              % (len(CHECKED), floor))
        FAILS.append('审计疑似空跑')
    if FAILS and '--warn' not in sys.argv:
        sys.exit(1)


if __name__ == '__main__':
    main()
