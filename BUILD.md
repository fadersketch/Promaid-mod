# 构建说明 / How to build

> 本项目**不使用 Gradle**：构建链是自定义的 `javac @argfile` + 打包脚本（`tools/build/`）。
> 原因与现状见文末「为什么没有 gradle」。

## 一、前置

- **JDK**：Forge 树用 **17**（`--release 17`），Neo 树用 **21**（`--release 21`）。`javac` 在 PATH 上。
- **一份完整的 Minecraft 启动器依赖目录**（含 Forge 47.x / NeoForge 21.1.x、Mixin 0.8.7、ASM、
  MixinExtras、GeckoLib、guava/gson/netty 等）。当前默认路径是 `D:\.minecraft\libraries`，
  但**已可配置**（见下「一.1 换机器：改两个环境变量」）。
- **东方女仆（Touhou Little Maid，TLM）jar**：
  - Forge 1.20.1：`compile_addon.txt` 里指向的那份（脚本会把 `smart_tlm.jar` 换成未打补丁的
    `original_tlm.jar` 作编译目标）。
  - Neo 1.21.1：`<MC 根>\versions\1.21.1-NeoForge_21.1.250\mods\touhoulittlemaid-1.5.3-neoforge+mc1.21.1.jar`
    （脚本按 `*touhoulittlemaid*.jar` 通配找）。

### 一.1 换机器：改两个环境变量（不必再手改源码）

两个生成脚本（`tools/build/gen_compile.py`、`gen_compile_neo.py`）会把 classpath 里写死的
`D:/.minecraft/...` 前缀按下面两个环境变量替换；仓库自身位置自动按脚本真实路径重算，clone 到别处也不用改：

```bat
set PROMAID_MC_HOME=D:\.minecraft      :: Minecraft 根（默认 D:\.minecraft），用于 <根>\versions\... 里的模组 jar
set PROMAID_LIB=D:\.minecraft\libraries :: 库里目录（默认 <MC 根>\libraries）
```

不设这两个变量时**输出与原先逐字节一致**（默认值 = 本机原值）。若某个 classpath 条目找不到，
生成脚本会打一行 `WARN classpath 有 N 项不存在` 提示（javac 会忽略缺失项，不影响编译，多半是换机器没设变量）。

### 一.2 依赖锁：把"用哪个工具链"钉死（TD-13 等价可复现）

`tools/build/deps.lock` 把两树编译 classpath 的**每个 jar** 钉成「根相对路径 + sha256 + 字节数 + maven/local」，
由 `tools/build/gen_compile*.py` 的 classpath 实测生成。两条命令都不联网：

```bat
python tools\build\check_deps.py            :: 离线核对：本机工具链是否与锁逐哈希一致（不符 FAIL、缺失 WARN）
python tools\build\check_deps.py --plan     :: 只打印"从哪取"：maven 条目给出仓库 URL；local 条目注明需自备
```

- `maven` 条目可从公开仓库取（`--plan` 打印 URL）——Forge 走 `maven.minecraftforge.net`、NeoForge 走
  `maven.neoforged.net`、Minecraft/Mojang 库走 `libraries.minecraft.net`、其余走 Maven Central。
- `local` 条目**必须自备**：车万女仆 TLM（`libs_forge/original_tlm.jar`、`libs_neo/touhoulittlemaid-*.jar`）、
  libIPN（本机为可选缺失，`sha256=MISSING`）。所以"完全自包含"仍取决于你手上有没有这两份第三方 jar。
- 校验器是**环境相关工具**（换工具链就该不符），故**不接进打包脚本当常驻护栏**，按需手跑。

## 二、构建（Forge 1.20.1）

```bat
python tools\build\gen_compile.py      :: 扫描 promaid_src → compile_promaid.txt（javac @argfile）
tools\build\compile_promaid.bat        :: javac 编译 → out_promaid\（打印 EXITCODE=0 = 成功）
python tools\build\build_promaid.py    :: 打包 + 校验（mixin 登记 / 注入签名 / lang json / 护栏）
                                       ::   → patched\promaid-1.3.2-forge-1.20.1.jar
```

## 三、构建（Neo 1.21.1）

```bat
python tools\build\gen_compile_neo.py
tools\build\compile_neo.bat
python tools\build\build_promaid_neo.py
```

一键（Forge 全链 + 装到本机 mods 目录）：`tools\build\build_all.bat`；Neo 单独跑上面两条。

## 四、两树镜像约定（重要）

同仓库两棵源码树：`promaid_src`（Forge 1.20.1，SRG 名）与 `promaid_src_neo`（Neo 1.21.1，Mojang 名）。
纯 Java / 字符串文件**逐字节相同**，唯一允许的差异是 SRG↔Mojang 方法名（如 `m_8055_`↔`getBlockState`）。
**任何改动都要同时落两树。**

## 五、护栏（改完必跑；已以 `--warn` 接进打包脚本）

```bat
python tools\verify\check_preset.py          :: DIY / 默认 预设分类表与配置项一致
python tools\verify\check_workflow.py        :: 版本纪律 / 轮次记录 / 台账 / 开关清单
python tools\verify\check_comment_style.py   :: 注释禁引语、禁超长块
python tools\verify\check_catch_swallow.py   :: 静默吞异常棘轮（只减不增）
python tools\verify\check_i18n_keys.py       :: 配置 key 缺译文棘轮（只减不增）
python tools\verify\check_dev_voice.py       :: 可见文案开发口吻棘轮（只减不增）
python tools\verify\check_comment_dup.py     :: 重复 .comment 注释棘轮（只减不增）
python tools\verify\check_reflection.py      :: 探针式反射处数棘轮（只减不增）
python tools\verify\check_panel_coverage.py  :: 面板覆盖清单不过期
python tools\verify\gen_switch_inventory.py --write   :: 重生成 ARCHITECTURE-SWITCHES.md
```

## 六、混入插件开关（排查与别的模组的混入冲突用）

加 JVM 参数即可选择性关闭本模组的混入，**不必改源码重编**：

```bat
-Dpromaid.mixin.disabled=all                          :: 关掉全部混入
-Dpromaid.mixin.disabled=MaidSweepMixin,MaidPickupPriorityMixin   :: 只关指定的（简单名，逗号分隔）
```

命中时启动日志里会打一行 `[promaid]` 前缀的记录。默认（不设此参数）全部混入照常生效。

## 七、为什么没有 gradle（已知限制）

- 构建链是自定义的（javac + argfile + 打包脚本）。`compile_addon.txt` 里原本写死的 classpath
  绝对路径**已改成可配置**（`PROMAID_MC_HOME` / `PROMAID_LIB`，见「一.1」），仓库自身位置也自动重算
  —— 外部 clone 设好这两个变量即可构建。
- 「做一份自包含的 Gradle（ForgeGradle / NeoGradle，依赖走仓库内相对路径 + 官方仓库）」**仍未做**，
  结构债 **TD-13**：一是需联网下载 ForgeGradle/NeoGradle 与 MC 制品（本仓库离线环境做不了、也验不了），
  二是 TLM / libIPN 是第三方 jar、**必须自备**（Gradle 也得靠 `libs/` 引入），三是本模组是"两树镜像 +
  自定义 javac 管道"，套 Gradle 要另建 sourceSet 结构、动的是工程骨架而非文案。
  **等价可复现方案已做**：`tools/build/deps.lock` 钉住每个编译依赖的 sha256（见「一.2」），
  `check_deps.py` 离线核对 / 打印获取计划——外部贡献者据此能拿到与记录那次构建**逐哈希一致**的工具链。
