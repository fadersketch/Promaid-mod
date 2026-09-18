#!/usr/bin/env python3
"""从零构建 Promaid（NeoForge 1.21.1）——CI 与本地通用。

与仓库里那套 Windows 专用脚本（gen_compile_neo.py 等，路径写死 D:\\.minecraft）的区别：
本脚本**自己准备依赖**，不依赖任何本机 Minecraft 安装，可在干净的 CI runner 上跑：

  1. NeoForge 安装器 --installClient → 生成 libraries/（含 Mojang 官方映射的
     neoforge-<ver>-client.jar 与 client-<mc>-srg.jar，以及 lwjgl / asm / guava / mixin 等）；
  2. Touhou Little Maid 前置 jar（Modrinth API 取 1.21.1-neoforge 版本）；
  3. MixinExtras（Maven Central）；
  4. javac 编译 promaid_src_neo → out_promaid_neo（249+ 源文件）；
  5. 调 build_promaid_neo.py 打包成 patched/promaid-<ver>-neoforge-1.21.1.jar。

用法：
    python3 tools/ci_build_neoforge.py [--cache DIR] [--neoforge 21.1.250] [--tlm-version <id或auto>]

依赖：Java 21（java/javac 在 PATH 上）、python3（仅标准库 + httpx 可选，缺失时用 urllib）。
"""
import argparse
import json
import os
import pathlib
import shutil
import subprocess
import sys
import urllib.request
import zipfile

NEOFORGE_DEFAULT = "21.1.250"
MC_VERSION = "1.21.1"
TLM_MODRINTH_SLUG = "touhou-little-maid"
MIXINEXTRAS_URL = ("https://repo1.maven.org/maven2/io/github/llamalad7/"
                   "mixinextras-forge/0.5.4/mixinextras-forge-0.5.4.jar")
REPO = pathlib.Path(__file__).resolve().parent.parent


def log(msg):
    print("[ci] " + msg, flush=True)


def download(url, dst: pathlib.Path):
    """下载（三级回退：httpx → curl → urllib）。

    实测某些站点的 TLS 握手对老 urllib 不友好（`SSL: UNEXPECTED_EOF_WHILE_READING`），
    而 curl/httpx 正常——CI runner 与开发机都必然有 curl，所以按"能用的先上"来。
    """
    if dst.exists() and dst.stat().st_size > 1000:
        log("cached  %s" % dst.name)
        return dst
    dst.parent.mkdir(parents=True, exist_ok=True)
    log("fetch   %s" % url)

    def via_httpx():
        import httpx
        with httpx.Client(follow_redirects=True, timeout=300) as c:
            r = c.get(url, headers={"User-Agent": "promaid-ci"})
            r.raise_for_status()
            dst.write_bytes(r.content)

    def via_curl():
        p = subprocess.run(["curl", "-fsSL", "--retry", "3", "-A", "promaid-ci", "-o", str(dst), url])
        if p.returncode != 0:
            raise RuntimeError("curl rc=%d" % p.returncode)

    def via_urllib():
        req = urllib.request.Request(url, headers={"User-Agent": "promaid-ci"})
        with urllib.request.urlopen(req, timeout=300) as r, open(dst, "wb") as f:
            shutil.copyfileobj(r, f)

    last = None
    for fn in (via_httpx, via_curl, via_urllib):
        try:
            fn()
            if dst.exists() and dst.stat().st_size > 1000:
                log("        -> %s (%d bytes) via %s" % (dst.name, dst.stat().st_size, fn.__name__))
                return dst
            raise RuntimeError("文件过小")
        except Exception as e:  # noqa: BLE001
            last = e
            log("        %s 失败：%s" % (fn.__name__, e))
    sys.exit("FATAL: 下载失败 %s（最后一次错误：%s）" % (url, last))


def run(cmd, cwd=None):
    log("run     " + " ".join(str(c) for c in cmd))
    p = subprocess.run([str(c) for c in cmd], cwd=cwd)
    if p.returncode != 0:
        sys.exit("FATAL: command failed (%d): %s" % (p.returncode, " ".join(str(c) for c in cmd)))


def ensure_neoforge(cache: pathlib.Path, nfver: str):
    """NeoForge 安装器 --installClient：产出 libraries/ 全套（含补丁版 client jar）。"""
    root = cache / ("neoforge-" + nfver)
    marker = root / "libraries" / "net" / "neoforged" / "neoforge" / nfver / ("neoforge-%s-client.jar" % nfver)
    if marker.exists():
        log("cached  NeoForge %s client install" % nfver)
        return root
    root.mkdir(parents=True, exist_ok=True)
    installer = cache / ("neoforge-%s-installer.jar" % nfver)
    download("https://maven.neoforged.net/releases/net/neoforged/neoforge/%s/neoforge-%s-installer.jar"
             % (nfver, nfver), installer)
    # 【为什么用假 HOME】安装器把"游戏目录"固定解析成 <HOME>/.minecraft（实测它无视 cwd），
    # 所以把 HOME 指到一个临时目录：产物 → <fakehome>/.minecraft/libraries/…，
    # 既不污染构建者/CI 的真实 ~/.minecraft，也保证可重复。
    fakehome = root / "fakehome"
    mcdir = fakehome / ".minecraft"
    mcdir.mkdir(parents=True, exist_ok=True)
    # 客户端安装要求存在 launcher_profiles.json（内容无关紧要）
    lp = mcdir / "launcher_profiles.json"
    if not lp.exists():
        lp.write_text(json.dumps({"profiles": {}, "settings": {}, "version": 3}))
    env = dict(os.environ, HOME=str(fakehome), USERPROFILE=str(fakehome))
    p = subprocess.run(["java", "-Duser.home=" + str(fakehome), "-jar", str(installer), "--installClient"],
                       cwd=root, env=env)
    if p.returncode != 0:
        sys.exit("FATAL: NeoForge 客户端安装失败（rc=%d）" % p.returncode)
    # 安装器把 libraries 放在 .minecraft/libraries → 软链到 root/libraries，后续路径统一
    if not (root / "libraries").exists():
        (root / "libraries").symlink_to(mcdir / "libraries", target_is_directory=True)
    if not marker.exists():
        sys.exit("FATAL: NeoForge client jar 未生成：%s" % marker)
    return root


def ensure_tlm(cache: pathlib.Path, want_version: str):
    """Touhou Little Maid（Modrinth）：取 1.21.1 + neoforge 的最新文件。"""
    dst = cache / "tlm"
    dst.mkdir(parents=True, exist_ok=True)
    have = sorted(dst.glob("touhoulittlemaid-*.jar"))
    if have and want_version == "auto":
        log("cached  %s" % have[-1].name)
        return have[-1]
    from urllib.parse import quote
    url = ("https://api.modrinth.com/v2/project/%s/version?loaders=%s&game_versions=%s"
           % (TLM_MODRINTH_SLUG, quote('[\"neoforge\"]'), quote('[\"%s\"]' % MC_VERSION)))
    req = urllib.request.Request(url, headers={"User-Agent": "promaid-ci"})
    with urllib.request.urlopen(req, timeout=120) as r:
        versions = json.load(r)
    if not versions:
        sys.exit("FATAL: Modrinth 上没有 %s 的 %s/neoforge 版本" % (TLM_MODRINTH_SLUG, MC_VERSION))
    v = versions[0] if want_version == "auto" else next(
        (x for x in versions if want_version in x["version_number"]), versions[0])
    f = v["files"][0]
    log("tlm     %s (%s)" % (v["version_number"], f["filename"]))
    return download(f["url"], dst / f["filename"])


def libs_from(neo_root: pathlib.Path, tlm: pathlib.Path, cache: pathlib.Path):
    """按"与运行期一致"的顺序拼 classpath：NeoForge 补丁 jar 在前，未修补 vanilla 在后。"""
    libdir = neo_root / "libraries"
    def pick(*rel):
        """按"完整相对路径"取一个 jar（注意：rel 的每一段都要拼起来，不能逐段判存在）。"""
        p = libdir.joinpath(*rel)
        return p if p.exists() else None
    cp = []
    nfver = neo_root.name.split("-", 1)[1]
    for rel in [("net/neoforged/neoforge", nfver, "neoforge-%s-client.jar" % nfver),
                ("net/neoforged/neoforge", nfver, "neoforge-%s-universal.jar" % nfver)]:
        p = pick(*rel)
        if p:
            cp.append(p)
    # vanilla（Mojang 官方映射，NeoForge 安装器下到 libraries/net/minecraft/client/...）
    mcclient = sorted(libdir.glob("net/minecraft/client/*/client-*-srg.jar"))
    if not mcclient:
        mcclient = sorted(libdir.glob("net/minecraft/client/*/client-*.jar"))
    cp += [p for p in mcclient if "extra" not in p.name]
    cp.append(tlm)
    # 注意：Python 的 glob **不支持 {a,b} 花括号**（写成那样会静默匹配空），一律显式列。
    pats = ["net/neoforged/bus/*/*.jar",
            "net/neoforged/fancymodloader/loader/*/*.jar",
            "net/neoforged/mergetool/*/*api.jar",
            "net/fabricmc/sponge-mixin/*/*.jar",
            "org/spongepowered/mixin/*/*.jar",
            "org/lwjgl/lwjgl/*/*.jar", "org/lwjgl/lwjgl-glfw/*/*.jar",
            "org/lwjgl/lwjgl-opengl/*/*.jar", "org/lwjgl/lwjgl-stb/*/*.jar",
            "org/lwjgl/lwjgl-tinyfd/*/*.jar", "org/lwjgl/lwjgl-jemalloc/*/*.jar",
            "org/lwjgl/lwjgl-openal/*/*.jar", "org/lwjgl/lwjgl-freetype/*/*.jar"]
    import glob
    for pat in pats:
        for g in sorted(glob.glob(str(libdir / pat))):
            p = pathlib.Path(g)
            if "natives" in p.name or p in cp:
                continue
            cp.append(p)
    cp += ensure_extra_libs(cache)                      # 固定版本那批（见 EXTRA_LIBS）
    cp.append(download(MIXINEXTRAS_URL, cache / "mixinextras-forge-0.5.4.jar"))
    return [p for p in cp if p and p.exists()]



# ── v1.2.0：NeoForge 安装器只下"启动客户端所需"的子集；编译还需要一批常被引用的库。
# 这里显式列出**固定版本**（与已验证可编译的那套一致），CI 与本地因此完全确定。
EXTRA_LIBS = [
    ("https://repo1.maven.org/maven2/com/google/guava/guava/33.3.1-jre/guava-33.3.1-jre.jar", "guava-33.3.1-jre.jar"),
    ("https://repo1.maven.org/maven2/com/google/code/gson/gson/2.10.1/gson-2.10.1.jar", "gson-2.10.1.jar"),
    ("https://repo1.maven.org/maven2/it/unimi/dsi/fastutil/8.5.18/fastutil-8.5.18.jar", "fastutil-8.5.18.jar"),
    ("https://repo1.maven.org/maven2/io/netty/netty-buffer/4.1.82.Final/netty-buffer-4.1.82.Final.jar", "netty-buffer-4.1.82.Final.jar"),
    ("https://repo1.maven.org/maven2/io/netty/netty-common/4.1.82.Final/netty-common-4.1.82.Final.jar", "netty-common-4.1.82.Final.jar"),
    ("https://repo1.maven.org/maven2/io/netty/netty-transport/4.1.82.Final/netty-transport-4.1.82.Final.jar", "netty-transport-4.1.82.Final.jar"),
    ("https://repo1.maven.org/maven2/org/slf4j/slf4j-api/2.0.9/slf4j-api-2.0.9.jar", "slf4j-api-2.0.9.jar"),
    ("https://repo1.maven.org/maven2/org/ow2/asm/asm/9.6/asm-9.6.jar", "asm-9.6.jar"),
    ("https://repo1.maven.org/maven2/org/ow2/asm/asm-commons/9.6/asm-commons-9.6.jar", "asm-commons-9.6.jar"),
    ("https://repo1.maven.org/maven2/org/ow2/asm/asm-tree/9.6/asm-tree-9.6.jar", "asm-tree-9.6.jar"),
    ("https://repo1.maven.org/maven2/org/joml/joml/1.10.5/joml-1.10.5.jar", "joml-1.10.5.jar"),
    ("https://repo1.maven.org/maven2/org/apache/commons/commons-lang3/3.12.0/commons-lang3-3.12.0.jar", "commons-lang3-3.12.0.jar"),
    ("https://libraries.minecraft.net/com/mojang/authlib/9.0.75/authlib-9.0.75.jar", "authlib-9.0.75.jar"),
    ("https://libraries.minecraft.net/com/mojang/javabridge/1.2.24/javabridge-1.2.24.jar", "javabridge-1.2.24.jar"),
    ("https://libraries.minecraft.net/com/mojang/logging/1.7.12/logging-1.7.12.jar", "logging-1.7.12.jar"),
    ("https://libraries.minecraft.net/com/mojang/brigadier/1.3.10/brigadier-1.3.10.jar", "brigadier-1.3.10.jar"),
    ("https://libraries.minecraft.net/com/mojang/datafixerupper/8.0.16/datafixerupper-8.0.16.jar", "datafixerupper-8.0.16.jar"),
    ("https://repo1.maven.org/maven2/com/google/code/findbugs/jsr305/3.0.2/jsr305-3.0.2.jar", "jsr305-3.0.2.jar"),
    ("https://repo1.maven.org/maven2/org/checkerframework/checker-qual/3.33.0/checker-qual-3.33.0.jar", "checker-qual-3.33.0.jar"),
    # LWJGL：客户端安装器不下（由启动器按平台补），编译期需要（键位/GLFW 常量）
    ("https://repo1.maven.org/maven2/org/lwjgl/lwjgl/3.3.3/lwjgl-3.3.3.jar", "lwjgl-3.3.3.jar"),
    ("https://repo1.maven.org/maven2/org/lwjgl/lwjgl-glfw/3.3.3/lwjgl-glfw-3.3.3.jar", "lwjgl-glfw-3.3.3.jar"),
    ("https://repo1.maven.org/maven2/org/lwjgl/lwjgl-opengl/3.3.3/lwjgl-opengl-3.3.3.jar", "lwjgl-opengl-3.3.3.jar"),
    ("https://repo1.maven.org/maven2/org/lwjgl/lwjgl-stb/3.3.3/lwjgl-stb-3.3.3.jar", "lwjgl-stb-3.3.3.jar"),
    ("https://repo1.maven.org/maven2/org/lwjgl/lwjgl-tinyfd/3.3.3/lwjgl-tinyfd-3.3.3.jar", "lwjgl-tinyfd-3.3.3.jar"),
    ("https://repo1.maven.org/maven2/org/lwjgl/lwjgl-jemalloc/3.3.3/lwjgl-jemalloc-3.3.3.jar", "lwjgl-jemalloc-3.3.3.jar"),
    ("https://repo1.maven.org/maven2/org/lwjgl/lwjgl-openal/3.3.3/lwjgl-openal-3.3.3.jar", "lwjgl-openal-3.3.3.jar"),
    ("https://repo1.maven.org/maven2/org/lwjgl/lwjgl-freetype/3.3.3/lwjgl-freetype-3.3.3.jar", "lwjgl-freetype-3.3.3.jar"),
]


def ensure_extra_libs(cache: pathlib.Path):
    """下载编译期需要的固定版本库（NeoForge 客户端安装不含它们）。"""
    dst = cache / "libs"
    dst.mkdir(parents=True, exist_ok=True)
    out = []
    for url, name in EXTRA_LIBS:
        out.append(download(url, dst / name))
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--cache", default=os.path.join(os.path.expanduser("~"), ".cache", "promaid-ci"))
    ap.add_argument("--neoforge", default=NEOFORGE_DEFAULT)
    ap.add_argument("--tlm-version", default="auto")
    ap.add_argument("--skip-package", action="store_true")
    args = ap.parse_args()
    cache = pathlib.Path(args.cache)
    cache.mkdir(parents=True, exist_ok=True)

    neo_root = ensure_neoforge(cache, args.neoforge)
    tlm = ensure_tlm(cache, args.tlm_version)
    cp = libs_from(neo_root, tlm, cache)
    log("classpath: %d jars" % len(cp))

    src = REPO / "promaid_src_neo"
    out = REPO / "out_promaid_neo"
    out.mkdir(exist_ok=True)
    sources = sorted(str(p) for p in src.rglob("*.java"))
    log("sources: %d" % len(sources))
    argfile = REPO / "compile_ci.txt"
    jarsep = ";" if os.name == "nt" else ":"
    args_ = ["-d", str(out), "--release", "21", "-proc:none", "-nowarn",
             "-encoding", "UTF-8", "-Xmaxerrs", "5000",
             "-classpath", jarsep.join(str(p) for p in cp)] + sources
    argfile.write_text(" ".join('"%s"' % a for a in args_), encoding="utf-8")
    run(["javac", "@" + str(argfile)])
    classes = len(list(out.rglob("*.class")))
    log("compiled classes: %d" % classes)
    if args.skip_package:
        return
    run([sys.executable, str(REPO / "build_promaid_neo.py")])
    jars = sorted((REPO / "patched").glob("*1.21.1.jar"))
    log("ARTIFACT: %s" % (jars[-1] if jars else "(none)"))


if __name__ == "__main__":
    main()
