# -*- coding: utf-8 -*-
"""Render the normalised tree into a single-file interactive architecture diagram.

Zero external dependencies, no telemetry. Drill-down, breadcrumbs, search,
API browser, dependency arrows with cross-layer aggregation, dark/light theme.
"""
import os, sys, json, html

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
OUT = os.path.join(REPO, "normify-promaid")
tree = json.load(open(os.path.join(OUT, "tree.json"), encoding="utf-8"))

HTML = """<!DOCTYPE html>
<html lang="zh"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>Promaid 架构图 · Architecture</title>
<style>
:root{
  --bg:#0f1420; --panel:#171d2b; --line:#2b3446; --fg:#e6ebf5; --dim:#93a0b8;
  --accent:#5cc8ff; --leaf:#7ee0a8; --cont:#ffcc66; --edge:#5a6a86; --warn:#ffb454;
}
body.light{
  --bg:#f6f8fc; --panel:#ffffff; --line:#d8dfea; --fg:#1a2233; --dim:#5d6b85;
  --accent:#0d7bc4; --leaf:#1d9a5e; --cont:#b8791a; --edge:#93a3bd; --warn:#b8791a;
}
*{box-sizing:border-box}
html,body{margin:0;height:100%;background:var(--bg);color:var(--fg);
  font:14px/1.55 "Segoe UI",system-ui,"Microsoft YaHei",sans-serif}
header{display:flex;gap:10px;align-items:center;padding:10px 14px;border-bottom:1px solid var(--line);
  background:var(--panel);position:sticky;top:0;z-index:20;flex-wrap:wrap}
header h1{font-size:15px;margin:0;font-weight:600;white-space:nowrap}
.crumb{display:flex;gap:6px;align-items:center;flex-wrap:wrap;color:var(--dim);font-size:13px}
.crumb a{color:var(--accent);cursor:pointer;text-decoration:none}
.crumb a:hover{text-decoration:underline}
#q{background:var(--bg);border:1px solid var(--line);color:var(--fg);border-radius:6px;
  padding:5px 9px;min-width:190px;outline:none}
.btn{background:var(--bg);border:1px solid var(--line);color:var(--fg);border-radius:6px;
  padding:5px 10px;cursor:pointer;font-size:13px}
.btn:hover{border-color:var(--accent)}
.btn.on{border-color:var(--accent);color:var(--accent)}
main{display:flex;height:calc(100% - 47px);overflow:hidden}
#canvas{flex:1;overflow:auto;padding:18px}
#side{width:340px;border-left:1px solid var(--line);background:var(--panel);
  overflow:auto;padding:14px;display:none}
#side.show{display:block}
.grid{display:grid;gap:14px;grid-template-columns:repeat(auto-fill,minmax(310px,1fr))}
.group{margin-bottom:22px}
.group>h3{margin:0 0 10px;font-size:13px;color:var(--dim);font-weight:600;
  text-transform:uppercase;letter-spacing:.06em}
.card{background:var(--panel);border:1px solid var(--line);border-radius:9px;padding:11px 12px;
  cursor:pointer;transition:border-color .12s,transform .12s;position:relative}
.card:hover{border-color:var(--accent);transform:translateY(-1px)}
.card.cont{border-left:3px solid var(--cont)}
.card.leaf{border-left:3px solid var(--leaf)}
.card h4{margin:0 0 3px;font-size:14px;display:flex;gap:7px;align-items:baseline;flex-wrap:wrap}
.card h4 .en{color:var(--dim);font-weight:400;font-size:12px}
.card .id{font:11px/1.5 ui-monospace,Consolas,monospace;color:var(--dim);word-break:break-all}
.card p{margin:7px 0 0;color:var(--dim);font-size:12.5px}
.badge{display:inline-block;font-size:10.5px;padding:1px 6px;border-radius:99px;
  border:1px solid var(--line);color:var(--dim);margin-right:4px}
.badge.api{border-color:var(--accent);color:var(--accent)}
.badge.dep{border-color:var(--warn);color:var(--warn)}
.badge.tag{border-color:var(--line)}
.apis{margin:8px 0 0;padding:0;list-style:none;border-top:1px dashed var(--line);padding-top:7px}
.apis li{font:11.5px/1.6 ui-monospace,Consolas,monospace;color:var(--fg);opacity:.92;
  padding:2px 0;border-bottom:1px dotted transparent}
.apis li b{color:var(--accent);font-weight:600}
.apis li span{color:var(--dim);font-family:inherit}
#side h2{font-size:15px;margin:0 0 4px}
#side .meta{color:var(--dim);font-size:12px;margin-bottom:10px}
#side h5{margin:16px 0 6px;font-size:12px;color:var(--dim);text-transform:uppercase;letter-spacing:.05em}
#side ul{margin:0;padding-left:16px}
#side li{margin:3px 0;font-size:12.5px}
#side code{font:11.5px ui-monospace,Consolas,monospace;color:var(--accent);word-break:break-all}
.deplink{cursor:pointer;color:var(--accent)}
.deplink:hover{text-decoration:underline}
.kv{display:flex;justify-content:space-between;gap:10px;font-size:12.5px;
  padding:3px 0;border-bottom:1px solid var(--line)}
.kv b{font-weight:600}
.stat{display:flex;gap:14px;flex-wrap:wrap;color:var(--dim);font-size:12px;margin-left:auto}
.stat b{color:var(--fg)}
</style></head><body>
<header>
  <h1>Promaid 架构图</h1>
  <div class="crumb" id="crumb"></div>
  <input id="q" placeholder="搜索模块 / API…">
  <button class="btn" id="agg">聚合跨层</button>
  <button class="btn" id="theme">明暗</button>
  <button class="btn" id="outline">大纲</button>
  <div class="stat" id="stat"></div>
</header>
<main>
  <div id="canvas"></div>
  <aside id="side"></aside>
</main>
<script>
const T = __TREE__;
const M = T.modules, E = T.edges, API = T.apis;
const kids = {};
for (const id in M) { const p = M[id].parent; if (p) (kids[p] = kids[p] || []).push(id); }
for (const k in kids) kids[k].sort((a,b)=> a.localeCompare(b,'zh'));
let cur = T.trees[0].root, agg = false, sel = null;

const $ = s => document.querySelector(s);
const esc = s => String(s).replace(/[&<>"]/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;'}[c]));
const nm = m => m.name.zh;
const apisOf = id => T.apis_detail && T.apis_detail[id] ? T.apis_detail[id] : [];

function stat(){
  const s = T.stats;
  $('#stat').innerHTML = `模块 <b>${s.modules}</b> · 叶子 <b>${s.leaves}</b> · API <b>${s.apis}</b> · 箭头 <b>${s.edges}</b>`;
}
function crumb(){
  const segs = cur.split('.');
  let acc = [], c = $('#crumb'); c.innerHTML = '';
  segs.forEach((s,i)=>{
    acc.push(s);
    const id = i===segs.length-1 ? cur : acc.join('.');
    const a = document.createElement('a');
    a.textContent = M[id] ? nm(M[id]) : s;
    a.onclick = ()=> go(id);
    c.appendChild(a);
    if (i < segs.length-1) c.appendChild(document.createTextNode(' / '));
  });
}
function go(id){
  if (!(kids[id]||[]).length) { detail(id); return; }   // leaf -> open the detail panel
  cur = id; sel = null; $('#side').classList.remove('show'); draw();
}
window.go = go;

function childList(){
  const ks = kids[cur] || [];
  const order = (T.renders && T.renders[cur] && T.renders[cur].order) || [];
  if (!order.length) return ks;
  const pos = new Map(order.map((x,i)=>[x,i]));
  return ks.slice().sort((a,b)=> (pos.has(a)?pos.get(a):999) - (pos.has(b)?pos.get(b):999));
}
function edgesFor(id){
  const up = E.filter(e => e.from === id);
  const dn = E.filter(e => e.to === id);
  return {up, dn};
}
function card(id, q){
  const m = M[id], {up,dn} = edgesFor(id);
  const el = document.createElement('div');
  el.className = 'card ' + (m.leaf ? 'leaf' : 'cont');
  let h = `<h4>${esc(nm(m))}<span class="en">${esc(m.name.en)}</span></h4>
           <div class="id">${esc(id)}</div>`;
  if (m.tags && m.tags.length) h += m.tags.map(t=>`<span class="badge tag">${esc(t)}</span>`).join('');
  if (m.apis) h += `<span class="badge api">API ${m.apis}</span>`;
  if (up.length) h += `<span class="badge dep">→ ${up.length}</span>`;
  if (dn.length) h += `<span class="badge dep">← ${dn.length}</span>`;
  if (q) h += `<p>${hl(desc(id), q)}</p>`;
  else if (m.leaf) h += `<p>${esc((desc(id)||'').slice(0,150))}${(desc(id)||'').length>150?'…':''}</p>`;
  el.innerHTML = h;
  el.onclick = ()=> m.leaf ? detail(id) : go(id);
  return el;
}
function desc(id){
  const d = T.descs && T.descs[id];
  return d ? d.zh : '';
}
function hl(s, q){
  if (!q) return esc(s);
  const i = s.toLowerCase().indexOf(q.toLowerCase());
  if (i < 0) return esc(s.slice(0,150)) + (s.length>150?'…':'');
  return esc(s.slice(0,Math.max(0,i-40))) + '<b style="color:var(--accent)">' +
         esc(s.substr(i,q.length)) + '</b>' + esc(s.slice(i+q.length, i+q.length+110));
}
function matches(id, q){
  if (!q) return true;
  const m = M[id];
  if (id.toLowerCase().includes(q) || nm(m).includes(q) ||
      (m.name.en||'').toLowerCase().includes(q) || (desc(id)||'').includes(q)) return true;
  // any child matches?
  return (kids[id]||[]).some(c => matches(c, q));
}

function draw(){
  crumb(); stat();
  const q = $('#q').value.trim();
  const c = $('#canvas'); c.innerHTML = '';
  if (q){
    const hit = Object.keys(M).filter(id => matches(id, q));
    c.innerHTML = `<div class="group"><h3>搜索“${esc(q)}” · ${hit.length} 项</h3></div>`;
    const g = document.createElement('div'); g.className = 'grid';
    hit.forEach(id => g.appendChild(card(id, q)));
    c.appendChild(g); return;
  }
  if (agg){
    const ch = childList();
    const g = document.createElement('div'); g.className = 'grid';
    ch.forEach(id => g.appendChild(card(id)));
    c.appendChild(g);
    // aggregated cross-layer arrows
    if (!ch.length) return;
    const inner = new Set();
    const walk = x => { inner.add(x); (kids[x]||[]).forEach(walk); };
    ch.forEach(walk);
    const buckets = {};
    E.forEach(e => { if (inner.has(e.from) && !inner.has(e.to)) {
       const a = topOf(e.from, ch), b = topOf(e.to, ch);
       if (a && b && a!==b) buckets[a+'|'+b] = (buckets[a+'|'+b]||0)+1; } });
    const keys = Object.keys(buckets);
    if (keys.length){
      const d = document.createElement('div'); d.className='group';
      d.innerHTML = '<h3>跨层依赖（聚合）</h3>';
      keys.forEach(k => {
        const [a,b] = k.split('|');
        const row = document.createElement('div'); row.className='kv';
        row.innerHTML = `<span>${esc(nm(M[a]))} → ${esc(nm(M[b]))}</span><b>×${buckets[k]}</b>`;
        d.appendChild(row);
      });
      c.appendChild(d);
    }
    return;
  }
  const ch = childList();
  const grp = (T.renders && T.renders[cur] && T.renders[cur].groups) || null;
  if (grp){
    grp.forEach(g0 => {
      const real = g0.children.filter(id => M[id]);
      if (!real.length) return;
      const g = document.createElement('div'); g.className='group';
      g.innerHTML = `<h3>${esc(g0.title.zh)}</h3>`;
      const grid = document.createElement('div'); grid.className='grid';
      real.forEach(id => grid.appendChild(card(id)));
      g.appendChild(grid); c.appendChild(g);
    });
    const used = new Set(grp.flatMap(g0=>g0.children));
    const rest = ch.filter(id => !used.has(id));
    if (rest.length){
      const g = document.createElement('div'); g.className='group';
      g.innerHTML = '<h3>其他</h3>';
      const grid = document.createElement('div'); grid.className='grid';
      rest.forEach(id => grid.appendChild(card(id)));
      g.appendChild(grid); c.appendChild(g);
    }
  } else {
    const g = document.createElement('div'); g.className='grid';
    ch.forEach(id => g.appendChild(card(id))); c.appendChild(g);
  }
}
function topOf(id, roots){
  for (const r of roots) if (id === r || id.startsWith(r + '.')) return r;
  return null;
}

function detail(id){
  sel = id;
  const m = M[id], s = $('#side'), {up,dn} = edgesFor(id);
  let h = `<h2>${esc(nm(m))}</h2><div class="meta">${esc(m.name.en)}</div>
    <div class="id">${esc(id)}</div>
    <div class="kv"><span>类型</span><b>${m.leaf?'叶子':'容器'}</b></div>
    <div class="kv"><span>标签</span><b>${(m.tags||[]).join(', ')||'—'}</b></div>
    <div class="kv"><span>指纹</span><b>${esc((m.fingerprint||'').slice(0,12))}</b></div>`;
  const d = desc(id);
  if (d) h += `<h5>说明</h5><div style="font-size:13px">${esc(d)}</div>`;
  const ap = (T.apis_detail||{})[id] || [];
  if (ap.length){
    h += `<h5>API（${ap.length}）</h5><ul>`;
    ap.forEach(a => h += `<li><code>${esc(a[0])}:${esc(a[1])}</code><br><span style="color:var(--dim)">${esc(a[2])}</span></li>`);
    h += `</ul>`;
  }
  if (up.length){ h += `<h5>依赖（出 ${up.length}）</h5><ul>`;
    up.forEach(e => h += `<li><span class="deplink" onclick="go('${e.to}')">${esc(M[e.to]?nm(M[e.to]):e.to)}</span>
      <span style="color:var(--dim)"> · ${esc(e.kind)}${e.label?' · '+esc(e.label.zh):''}</span></li>`);
    h += `</ul>`; }
  if (dn.length){ h += `<h5>被依赖（入 ${dn.length}）</h5><ul>`;
    dn.forEach(e => h += `<li><span class="deplink" onclick="go('${e.from}')">${esc(M[e.from]?nm(M[e.from]):e.from)}</span>
      <span style="color:var(--dim)"> · ${esc(e.kind)}${e.label?' · '+esc(e.label.zh):''}</span></li>`);
    h += `</ul>`; }
  const src = (T.sources||{})[id] || [];
  if (src.length){ h += `<h5>代码证据</h5><ul>`;
    src.forEach(s0 => h += `<li><code>${esc(s0[0])}${s0[1]?':'+s0[1]:''}</code></li>`);
    h += `</ul>`; }
  s.innerHTML = h; s.classList.add('show');
}
window.detail = detail;

$('#q').addEventListener('input', draw);
$('#agg').onclick = e => { agg = !agg; e.target.classList.toggle('on', agg); draw(); };
$('#theme').onclick = () => { document.body.classList.toggle('light'); draw(); };
$('#outline').onclick = () => { cur = T.trees[0].root; go(cur); };
draw();
if (location.hash.startsWith('#module=')) go(decodeURIComponent(location.hash.slice(8)));
</script></body></html>
"""

# enrich from the sidecar emitted by the generator (no YAML parsing needed)
side = json.load(open(os.path.join(OUT, "sidecar.json"), encoding="utf-8"))
descs, sources, apis_detail = {}, {}, {}
for mid, v in side.items():
    descs[mid] = {"zh": v["zh"], "en": v["en"]}
    sources[mid] = v["sources"]
    if v["apis"]:
        apis_detail[mid] = v["apis"]

renders = {}
for dp, dn, fn in os.walk(os.path.join(OUT, "renders")):
    for f in fn:
        r = json.load(open(os.path.join(dp, f), encoding="utf-8"))
        renders[r["id"]] = {"order": r.get("order", []), "groups": r.get("groups", [])}

payload = dict(tree)
payload["descs"] = descs
payload["sources"] = sources
payload["apis_detail"] = apis_detail
payload["renders"] = renders

out = HTML.replace("__TREE__", json.dumps(payload, ensure_ascii=False))
html_path = os.path.join(OUT, "normify.html")
open(html_path, "w", encoding="utf-8").write(out)
print("wrote", html_path, len(out), "chars")
