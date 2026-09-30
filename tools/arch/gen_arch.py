# -*- coding: utf-8 -*-
"""Generate a normify-style normalized fractal architecture tree for this repo.

Output (all under ./normify-promaid/, which is committed as architecture data):
  modules/<tree>/<segments...>/index.md   container
  modules/<tree>/<segments...>/<leaf>.md  leaf
  renders/<tree>/<segments...>.json       one layout per container
  policy.yml                              architecture rules
  tree.json                               compiled: modules + api index + edges + stats
  outline.md                              human-readable outline
  api-index.json                          api -> module index
  receipt.json                            SHA-256 freeze receipt
  normify.html                            single-file interactive diagram

Three-layer validation, fail-closed: L1 at write, L2 project-wide, L3 build.
Any L1/L2 error aborts with no artifacts written.
"""
import os, sys, re, json, hashlib, shutil, datetime, collections

# --- repo root resolution (this file lives in tools/arch/) ---
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

sys.stdout.reconfigure(encoding="utf-8", errors="replace")
ROOT = _find_repo_root(__file__)
OUT  = os.path.join(ROOT, "normify-promaid")
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from arch_modules import MODULES, MODULES_FORGE

def _norm(rec):
    """Records are (id, zh, en, dzh, den, src, tags, apis, deps).
    A few authored entries carry a stray empty list before deps; collapse it."""
    if len(rec) == 10 and rec[8] == []:
        rec = rec[:8] + (rec[9],)
    assert len(rec) == 9, (rec[0], len(rec))
    return rec

MODULES = [_norm(r) for r in MODULES]
MODULES_FORGE = [_norm(r) for r in MODULES_FORGE]

TREES = {"promaid": MODULES, "promaid-forge": MODULES_FORGE}
REVISION = None
def git_rev():
    """The revision being documented = last commit that touched the SOURCE trees.

    Deliberately NOT HEAD: using HEAD would make every rebuild produce different
    fingerprints just because documentation/arch commits landed, so `receipt.json`
    would never verify. Deriving it from the source means the arch data only changes
    when the code it describes changes.
    """
    import subprocess
    for paths in (["promaid_src_neo", "promaid_src"], ["promaid_src_neo"], []):
        args = ["git", "log", "-1", "--format=%H"] + (["--"] + paths if paths else [])
        out = subprocess.run(args, capture_output=True, text=True,
                             encoding="utf-8", errors="replace").stdout.strip()
        if out:
            return out
    return "0" * 40

REVISION = git_rev()
NOW = "2026-10-01T00:00:00Z"

# ---------------------------------------------------------------- API table
# keyed by module id; only valid on leaves
APIS = {
"promaid.entry.mod": [
  ("event","ProMaidMod()","模组构造：注册物品与配置。","Mod constructor: registers items and config."),
  ("event","runConfigMigration()","历史默认值迁移链（幂等，靠 *_MIGRATED 标记）。","Historical default migration chain (idempotent via *_MIGRATED flags)."),
],
"promaid.entry.extension": [
  ("rpc","addMaidTask(TaskManager)","向 TLM 登记本模组的全部任务。","Registers all of this mod's tasks with TLM."),
  ("rpc","addExtraMaidBrain()","登记 core/rest 大脑行为与优先级。","Registers core/rest brain behaviors and priorities."),
  ("rpc","registerAITool(...)","登记供 LLM 调用的工具。","Registers LLM-callable tools."),
  ("event","onServerTick(...)","驱动全部服务端每-tick 模块。","Drives every server tick module."),
],
"promaid.mixin.core-tlm-maid": [
  ("rpc","EntityMaid.travel(...)","注入：站桩期接管移动。","Injection: takes over movement while standing."),
  ("rpc","EntityMaid.teleportToOwner()","注入：停放/空袭期豁免传送。","Injection: exempts teleport while parked/airborne."),
],
"promaid.combat.auto-switch.targeting": [
  ("event","onLivingHurt(LivingIncomingDamageEvent)","主人/女仆受击 → 威胁评分。","Owner/maid hurt -> threat scoring."),
  ("event","onLivingDamage(...)","主人出手 → 记敌对目标。","Owner attacks -> record hostile."),
],
"promaid.combat.flight-raid.air-combat": [
  ("rpc","MaidFlightCombatBehavior.tick(...)","空袭主循环。","The air-raid main loop."),
  ("rpc","MaidFlightKit.canFly(maid)","三件套校验。","Validates the three-piece kit."),
],
"promaid.combat.broom": [
  ("rpc","MaidBroomDrive.takeThrust(...)","每拍推进量（被扫帚实体 travel 注入读取）。","Per-tick thrust, read by the broom entity travel injection."),
  ("rpc","MaidBroomKit.isBroomTask(...)","任务判定契约（多处方引用）。","Task predicate contract (referenced from several mixins)."),
],
"promaid.combat.bombing.blast": [
  ("rpc","MaidBombing.onAttackLanded(...)","打完一记 → 顺带投弹。","After a landed hit -> drop a bomb."),
],
"promaid.combat.ride": [
  ("rpc","RideBindManager.bind(...)","绑定语义：一车一仆；甲的车不受乙影响。","Bind semantics: one mount one maid; maid A's mount is unaffected by B."),
  ("rpc","RideBindManager.handleSwapSeatRequest(...)","左键换座的服务端落点。","Server side of the left-click seat swap."),
],
"promaid.combat.mount-compat": [
  ("rpc","MaidMountCompat.stopVehicle/followDist(...)","停车判据按车体尺寸。","Stop criteria derived from vehicle size."),
  ("rpc","MaidMountCompat.applyVehicleAiTargets(...)","把她的目标写进炮塔/武器位 AI 目标 UUID。","Writes her target into the turret/weapon-station AI target UUID."),
],
"promaid.work.build-exec": [
  ("rpc","BlueprintBuildExecutor.execute(...)","解析并建立/续接建造计划。","Parses and creates or resumes a build plan."),
  ("rpc","MaidBuildBehavior.doPlace(...)","放下一块并扣真实材料。","Places one block and consumes real material."),
],
"promaid.work.blueprint-lib.math": [
  ("rpc","BlueprintStepMath.rotateSteps(...)","按 quarter 旋转步骤。","Rotates steps by quarter turns."),
  ("rpc","BlueprintStepMath.centerSteps(...)","把步骤居中到原点。","Centres steps on the origin."),
],
"promaid.work.tags": [
  ("dataflow","MaidWorkTags.WORK_STILL_TAG","站桩契约标记。","The work-still contract tag."),
  ("dataflow","MaidWorkTags.BUILD_SIT_TAG","建造期就座契约标记。","The build-sit contract tag."),
],
"promaid.work.placed-block": [
  ("rpc","PlacedBlockTracker.place(...)","登记放置方块与主人 UUID。","Registers a placed block and its owner UUID."),
  ("rpc","PlacedBlockTracker.expirePlaced(...)","到期回收。","Reclaims on expiry."),
],
"promaid.system.work-area": [
  ("rpc","WorkAreaClamp.clamp(...)","把目标钳进工作圆。","Clamps a target into the work circle."),
],
"promaid.social.memory.index": [
  ("file","<world>/promaid_memory/<uuid>/paragraphs.jsonl","段落记忆表。","Paragraph memory table."),
  ("file","<world>/promaid_memory/<uuid>/relations.jsonl","关系表。","Relations table."),
  ("rpc","AiMemoryStore.append(...)","写入一条记忆。","Appends one memory entry."),
],
"promaid.social.voice": [
  ("file","voice_cache/<sha256>.ogg","合成语音缓存键。","Synthesised-voice cache key."),
],
"promaid.system.box": [
  ("dataflow","CompressionBoxData NBT","自定义压缩盒 NBT。","Custom compression-box NBT."),
],
"promaid.system.brew": [
  ("rpc","BrewRecipeResolver.resolve(...)","按目标药水回溯配方链。","Walks the recipe chain back from a target potion."),
],
"promaid.system.goety": [
  ("rpc","MaidGoetyCompat.ensureHooked()","探测并挂接 Goety（两包根）。","Probes and hooks Goety (two package roots)."),
],
"promaid.system.follow": [
  ("rpc","MaidChunkLoadManager.tick(...)","每 5 秒续票。","Refreshes tickets every 5s."),
  ("rpc","MaidChunkLoadManager.followIfCrossDimension(...)","跨维跟随。","Cross-dimension follow."),
],
"promaid.system.protect": [
  ("event","onLivingDeath(...)","主人死亡 → 全员立即传送。","Owner death -> teleport everyone immediately."),
],
"promaid.core.config-panel": [
  ("rpc","PromaidConfigScreen.openAt(...)","从手册章节跳转到指定配置行。","Jumps from a guide chapter to a config row."),
],
"promaid.guide": [
  ("rpc","GuideContent.chapters()","36 章注册表。","The 36-chapter registry."),
  ("rpc","GuideScreen.buildLines(...)","按像素宽度换行分页。","Pixel-width wrapping and paging."),
],
}

# ---------------------------------------------------------------- merge APIs in
def _with_apis(mods):
    out = []
    for rec in mods:
        a = APIS.get(rec[0])
        if a and not rec[7]:
            rec = rec[:7] + (a,) + rec[8:]
        out.append(rec)
    return out

MODULES[:] = _with_apis(MODULES)
MODULES_FORGE[:] = _with_apis(MODULES_FORGE)
TREES = {"promaid": MODULES, "promaid-forge": MODULES_FORGE}

# ---------------------------------------------------------------- L1 validate
errors, warns = [], []
def err(code, subj, msg, ev=""): errors.append({"severity":"error","code":code,"subject":subj,"message":msg,"evidence":ev,"supportedFixes":["fix-data"]})
def warn(code, subj, msg, ev=""): warns.append({"severity":"warning","code":code,"subject":subj,"message":msg,"evidence":ev})

def seg_of(mid): return mid.split(".")
def parent_of(mid): return ".".join(seg_of(mid)[:-1]) or None

ALL = {}
for tree, mods in TREES.items():
    for rec in mods:
        mid = rec[0]
        if mid in ALL: err("id/duplicate", mid, "模块 id 重复")
        ALL[mid] = {"id":mid,"tree":tree,"rec":rec}

for tree, mods in TREES.items():
    for rec in mods:
        mid, zh, en, dzh, den, src, tags, apis, deps = rec
        if not re.match(r"^[a-z][a-z0-9-]*(\.[a-z][a-z0-9-]*)*$", mid):
            err("id/grammar", mid, "id 文法不合法")
        if seg_of(mid)[0] != tree:
            err("id/tree-prefix", mid, f"首段必须等于树名 {tree}")
        par = parent_of(mid)
        if par is not None and par not in ALL:
            err("structure/parent-missing", mid, f"父模块 {par} 不存在")
        if len(zh) > 60: warn("naming/zh-long", mid, f"中文名 {len(zh)} 字，建议 ≤20")
        if len(en) > 60: warn("naming/en-long", mid, f"英文名 {len(en)} 字")
        if len(dzh) > 900: warn("desc/zh-long", mid, f"中文描述 {len(dzh)} 字")
        if len(den) > 1500: warn("desc/en-long", mid, f"英文描述 {len(den)} 字")
        if len(tags) > 12: err("tags/too-many", mid, "tags ≤12")
        for s in src:
            if len(s) < 2: err("source/shape", mid, "source 需 (path[,line,end])")

# leaf / non-leaf + api rules
children = collections.defaultdict(list)
for mid, m in ALL.items():
    p = parent_of(mid)
    if p: children[p].append(mid)

for mid, m in ALL.items():
    is_leaf = not children[mid]
    apis = m["rec"][7]
    if not is_leaf and apis:
        err("api/non-leaf", mid, "非叶子不能声明 apis")
    if is_leaf and not apis:
        warn("structure/leaf-too-coarse", mid, "叶子未声明 API（建议 3–5 条）")

# dep targets + acyclic + naming
edges = []
for mid, m in ALL.items():
    for d in m["rec"][8]:
        kind, to, lzh, len_ = d
        if to not in ALL:
            err("dep/target-missing", mid, f"依赖目标不存在：{to}", ev=to); continue
        if kind not in ("call","event","dataflow","reference"):
            err("dep/kind", mid, f"未知 kind：{kind}")
        edges.append({"from":mid,"to":to,"kind":kind,"label":{"zh":lzh,"en":len_}})

# cycle check
adj = collections.defaultdict(list)
for e in edges: adj[e["from"]].append(e["to"])
WHITE, GREY, BLACK = 0,1,2
color = collections.defaultdict(int)
def dfs(u, stack):
    color[u]=GREY; stack.append(u)
    for v in adj[u]:
        if color[v]==GREY:
            err("dep/cycle", u, "依赖成环：" + " → ".join(stack[stack.index(v):]+[v]))
        elif color[v]==WHITE: dfs(v, stack)
    stack.pop(); color[u]=BLACK
for n in list(ALL):
    if color[n]==WHITE: dfs(n, [])

# source existence (evidence) — L2 with repoRoot
missing_src = []
for mid, m in ALL.items():
    for s in m["rec"][5]:
        p = os.path.join(ROOT, s[0])
        if not os.path.exists(p): missing_src.append((mid, s[0]))
if missing_src:
    for mid, p in missing_src[:20]:
        warn("evidence/source-missing", mid, f"source 路径不存在：{p}")

print(f"L1/L2 validate: {len(errors)} error, {len(warns)} warning, {len(ALL)} modules, {len(edges)} edges")
for e in errors[:20]: print("  ERR", e["code"], e["subject"], e["message"])
if errors:
    print("fail-closed: no artifacts written")
    open(os.path.join(ROOT,"_tmp_normify_errors.json"),"w",encoding="utf-8").write(json.dumps(errors,ensure_ascii=False,indent=2))
    sys.exit(1)

# ---------------------------------------------------------------- build files
if os.path.isdir(OUT): shutil.rmtree(OUT)
os.makedirs(OUT)

def yamlq(s):
    if s is None: return "null"
    s = str(s)
    if re.search(r'[:#\[\]{}",&*?|<>=!%@`\n]', s) or s.strip()!=s or s=="":
        return json.dumps(s, ensure_ascii=False)
    return s

def write_module(m):
    mid = m["id"]; _, zh, en, dzh, den, src, tags, apis, deps = m["rec"]
    parts = seg_of(mid)[1:]
    is_leaf = not children[mid]
    if not parts:
        path = os.path.join(OUT,"modules",mid,"index.md")
    elif is_leaf:
        path = os.path.join(OUT,"modules",seg_of(mid)[0],*parts[:-1],parts[-1]+".md")
    else:
        path = os.path.join(OUT,"modules",seg_of(mid)[0],*parts,"index.md")
    os.makedirs(os.path.dirname(path), exist_ok=True)
    uid = hashlib.sha1(mid.encode()).hexdigest()[:8]
    L=[]
    L.append("---")
    L.append(f"uid: {uid}")
    L.append(f"id: {mid}")
    L.append(f"parent: {parent_of(mid) if parent_of(mid) else 'null'}")
    L.append(f"name: {{zh: {yamlq(zh)}, en: {yamlq(en)}}}")
    L.append("description:")
    L.append(f"  zh: >")
    for ln in re.findall(r".{1,76}(?:\s|$)", dzh) or [dzh]: L.append("      "+ln.strip())
    L.append(f"  en: >")
    for ln in re.findall(r".{1,76}(?:\s|$)", den) or [den]: L.append("      "+ln.strip())
    L.append("source:")
    for s in src:
        if len(s)>=3 and s[2]:
            L.append(f"  - {{path: {yamlq(s[0])}, line: {s[1]}, end_line: {s[2]}}}")
        else:
            L.append(f"  - {{path: {yamlq(s[0])}, line: {s[1]}}}")
    L.append(f"revision: {REVISION}")
    L.append(f'updated_at: "{NOW}"')
    fp = hashlib.sha256(json.dumps([s[0] for s in src]).encode()).hexdigest()
    L.append(f"fingerprint: {fp}")
    L.append(f"state: active")
    if tags: L.append(f"tags: [{', '.join(tags)}]")
    if apis:
        L.append("apis:")
        for a in apis:
            L.append(f"  - protocol: {a[0]}")
            L.append(f"    path: {yamlq(a[1])}")
            L.append(f"    description: {{zh: {yamlq(a[2])}, en: {yamlq(a[3])}}}")
    if deps:
        L.append("deps:")
        for d in deps:
            L.append(f"  - {{kind: {d[0]}, to: {d[1]}, label: {{zh: {yamlq(d[2])}, en: {yamlq(d[3])}}}}}")
    L.append("---")
    L.append("")
    L.append(f"## {zh} · {en}")
    L.append("")
    L.append(dzh)
    L.append("")
    if src:
        L.append("**代码证据**")
        L.append("")
        for s in src:
            L.append(f"- `{s[0]}`" + (f":{s[1]}" if len(s)>1 and s[1] else ""))
        L.append("")
    with open(path,"w",encoding="utf-8") as f: f.write("\n".join(L))
    m["path"] = os.path.relpath(path, OUT).replace("\\","/")
    m["uid"] = uid
    m["fingerprint"] = fp
    m["is_leaf"] = is_leaf

for mid,m in ALL.items(): write_module(m)

# renders: one per container
def render_path(tree, mid):
    parts = seg_of(mid)[1:]
    return os.path.join(OUT,"renders",tree,*parts)+".json"

GROUPS = {
 "promaid": [
   ("入口与注入", "Entry & Mixins", ["promaid.entry","promaid.mixin"]),
   ("战斗与载具", "Combat & Vehicles", ["promaid.combat"]),
   ("生产任务与建造", "Work & Building", ["promaid.work"]),
   ("飞行", "Flight", ["promaid.flight"]),
   ("社交与心智", "Social & Mind", ["promaid.social"]),
   ("系统服务", "System Services", ["promaid.system"]),
   ("核心与文档", "Core & Docs", ["promaid.core","promaid.client","promaid.guide"]),
 ],
}

for mid,m in ALL.items():
    if children[mid]:
        # keep declaration order
        order = [r[0] for r in TREES[m["tree"]] if parent_of(r[0])==mid]
        g = []
        for gi, (gzh, gen, roots) in enumerate(GROUPS.get(m["tree"], [])):
            gch = [c for c in order if any(c==r or c.startswith(r+".") for r in roots)]
            if gch: g.append({"id":"g%d"%gi, "title":{"zh":gzh,"en":gen}, "children":gch})
        rd = {"schema_version":1,"id":mid,"updated_at":NOW,"mode":"groups" if g else "grid",
              "max_columns":3,"max_api_rows":0,
              "reading":{"zh":f"本层 {len(order)} 个子模块。","en":f"{len(order)} submodules at this layer."},
              "order":order}
        if g: rd["groups"]=g
        p = render_path(m["tree"], mid)
        os.makedirs(os.path.dirname(p), exist_ok=True)
        with open(p,"w",encoding="utf-8") as f: json.dump(rd,f,ensure_ascii=False,indent=2)

# policy.yml
policy = """# Promaid 架构规则（normify 格式）
rules:
  - id: core-acyclic
    type: acyclic
    severity: error
    includeCrossTree: true
  - id: layer-direction
    type: dependency-direction
    severity: error
    allowSameLayer: true
    layers:
      - {name: entry,   match: ["promaid.entry", "promaid.entry.**"]}
      - {name: mixin,   match: ["promaid.mixin", "promaid.mixin.**"]}
      - {name: feature, match: ["promaid.combat", "promaid.combat.**",
                                "promaid.work", "promaid.work.**",
                                "promaid.flight", "promaid.flight.**",
                                "promaid.social", "promaid.social.**"]}
      - {name: system,  match: ["promaid.system", "promaid.system.**"]}
      - {name: core,    match: ["promaid.core", "promaid.core.**"]}
  - id: no-feature-to-entry
    type: forbid-dependency
    severity: error
    from: ["promaid.combat.**", "promaid.work.**", "promaid.flight.**", "promaid.social.**",
           "promaid.system.**", "promaid.core.**"]
    to:   ["promaid.entry.mod"]
  - id: naming-kebab
    type: naming
    severity: error
    pattern: "^[a-z][a-z0-9-]*$"
    scope: ["promaid.**", "promaid-forge.**"]
"""
open(os.path.join(OUT,"policy.yml"),"w",encoding="utf-8").write(policy)

# tree.json
api_index = {}
for mid,m in ALL.items():
    for a in m["rec"][7]:
        api_index[f"{a[0]}:{a[1].split('(')[0]}"] = mid
tree_json = {
  "schema_version":1,
  "generated_at":NOW,
  "revision":REVISION,
  "trees":[{"name":t,"root":r[0][0],"modules":sum(1 for x in r if x[0].split('.')[0]==t)} for t,r in TREES.items()],
  "modules":{mid:{"uid":m["uid"],"id":mid,"parent":parent_of(mid),"tree":m["tree"],
                  "name":{"zh":m["rec"][1],"en":m["rec"][2]},
                  "path":m["path"],"leaf":m["is_leaf"],
                  "tags":m["rec"][6],"fingerprint":m["fingerprint"],
                  "apis":len(m["rec"][7]),"deps":len(m["rec"][8])} for mid,m in ALL.items()},
  "apis":api_index,
  "edges":edges,
  "stats":{"modules":len(ALL),"leaves":sum(1 for m in ALL.values() if m["is_leaf"]),
           "containers":sum(1 for m in ALL.values() if not m["is_leaf"]),
           "apis":sum(len(m["rec"][7]) for m in ALL.values()),"edges":len(edges),
           "depth":max(len(seg_of(mid))-1 for mid in ALL)},
  "diagnostics":{"errors":len(errors),"warnings":len(warns),"warning_list":warns},
}
open(os.path.join(OUT,"tree.json"),"w",encoding="utf-8").write(json.dumps(tree_json,ensure_ascii=False,indent=2))
open(os.path.join(OUT,"api-index.json"),"w",encoding="utf-8").write(json.dumps(api_index,ensure_ascii=False,indent=2))

# sidecar: id -> {zh,en} description + sources + apis, so the renderer needs no YAML parsing
side = {}
for mid, m in ALL.items():
    side[mid] = {
        "zh": m["rec"][3], "en": m["rec"][4],
        "name": {"zh": m["rec"][1], "en": m["rec"][2]},
        "sources": [[s0[0], s0[1], (s0[2] if len(s0) > 2 else None)] for s0 in m["rec"][5]],
        "apis": [[a[0], a[1], a[2], a[3]] for a in m["rec"][7]],
    }
open(os.path.join(OUT,"sidecar.json"),"w",encoding="utf-8").write(json.dumps(side,ensure_ascii=False,indent=2))

# outline.md
L=["# Promaid 架构大纲 / Architecture Outline","",
   f"修订 {REVISION} · {len(ALL)} 模块 / {tree_json['stats']['apis']} API / {len(edges)} 箭头 / 最大深度 {tree_json['stats']['depth']}",""]
def emit(mid, ind):
    m=ALL[mid]
    L.append("  "*ind + f"- **{m['rec'][1]}** `{mid}`" + (" *(leaf)*" if m["is_leaf"] else ""))
    if m["is_leaf"]:
        for a in m["rec"][7]:
            L.append("  "*(ind+1) + f"- `{a[0]}:{a[1]}` — {a[2]}")
    for c in [r[0] for r in TREES[m["tree"]] if parent_of(r[0])==mid]:
        emit(c, ind+1)
for t,mods in TREES.items():
    for r in mods:
        if parent_of(r[0]) is None: emit(r[0],0)
open(os.path.join(OUT,"outline.md"),"w",encoding="utf-8").write("\n".join(L))

# receipt (SHA-256 freeze)
receipt={"schema_version":1,"revision":REVISION,"generated_at":NOW,
         "stats":tree_json["stats"],"warnings":len(warns),"files":{}}
for dp,dn,fn in os.walk(OUT):
    for f in fn:
        if f=="receipt.json": continue
        p=os.path.join(dp,f)
        receipt["files"][os.path.relpath(p,OUT).replace("\\","/")]=hashlib.sha256(open(p,"rb").read()).hexdigest()
open(os.path.join(OUT,"receipt.json"),"w",encoding="utf-8").write(json.dumps(receipt,ensure_ascii=False,indent=2))

print(f"wrote {len(receipt['files'])} files under normify-promaid/")
print("stats:", json.dumps(tree_json["stats"],ensure_ascii=False))
print("warnings:", len(warns))
print("  receipt.json written LAST -> excludes itself")
