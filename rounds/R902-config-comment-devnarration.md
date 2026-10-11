# 实测九百〇二 配置面板 `.comment(...)` 与 i18n 去开发叙事（v 号 / 旧版对比 / 需求原话 / 实证引用）

> 跑道：玩家可见文案（`.comment()` 进 TOML + 面板、i18n/lang）。承接 `R901`，回应开发者「配置面板的 `.comment(...)` 与 i18n 文案里仍留着 v1.5.222：… 这类开发叙事，需要删除」。

## 排班
- —（连续跑批；② 已收口，此为 ② 的**文案延伸面**：② 只覆盖代码注释，`R879/R884/R885` 覆盖过 i18n 键与面板标签，本次专清 `.comment()` 正文本身。）

## 需求 / 来源
- 开发者：配置面板 `.comment(...)` 与 i18n 里 `v1.5.222：…` 这类开发叙事需删除。
- 范围锁定为**玩家可见文案**：两树 `MaidSmartConfig` 的 `.comment("…")` 正文 + `assets/**/lang/*.json`。**不含**源码 javadoc/`//` 注释（那属 `DEV_GUIDE.md` §1 / ② 的领地，见「边界」）。

## 做了什么
- 两树 `config/MaidSmartConfig.java`：清扫 `.comment("…")` 正文里的四类开发叙事，**保留全部可执行信息**（数值、默认、关掉后行为、命令、日志分类名、判据）：
  1. **版本号**：`（v1.5.222：…）` / `某句 v1.5.111 曾 0.4：…` / `v1.2.2 新增` / `v1.3.9.5 按要求从 3 提高到 5` 等 → 去掉版本戳与其历史对照，留下当前事实（如「0.6 = TLM 伐木任务同款移速」「范围下限 4」）。
  2. **旧版对比**：`关 = 回退旧行为（…）` / `与旧版逐字节一致` / `旧默认 5 太灵敏` / `旧版只压水平…` 等 → 改写为**当下行为**（如「关 = 常规顺序 + 静默放置 + 完工唤醒」）。
  3. **需求原话 / 玩家反馈**：`需求：…` / `需求补充：…` / `原话：「…」` / `【玩家点名要的那个数】` / `反馈：…` → 删除出处引文，保留其后的行为描述。
  4. **实现过程引用**：`（字节码实证…）` / `（javap 实证…）` / `（反编译实证…）` / `（旧档案：…）` → 删除。
- 处理量：按「版本戳」「历史/引文」两个阶段清扫，逐条改写长文案（如 `broom.climb` / `tether.enable` / `nonCombatSpeedCap` / `airRaid.minAboveHeight` 等多段说明改为**当下口径**），共落笔 ~100+ 条 `.comment()` 正文（两树合计；其中 Neo 独有的 7 项——`patrol.*`、`airSeparation`、`tether.enable`、`tether.maceSmash`、`maceWindCharge`——同步清理）。
- **护栏加固**：`tools/verify/check_dev_voice.py` 增第 ④ 项棘轮——扫两树全部 `.java` 的 `.comment("…")` 正文（剥注释后）里的**开发叙事**（版本戳 `v\d+\.\d+` / `旧版|旧行为|旧默认|旧值|旧的语义` / `需求：|需求补充|原话：|玩家点名|反馈：` / `字节码实证|javap 实证|反编译实证` / `TD-\d|BD-\d`），基线 **0**，>0 即 FAIL。据此再清掉 3 条残留的「（javap 实证…）/（字节码实证）/（反编译实证）」句。
- **副产品（镜像一致性）**：`tether.enable`（Neo 侧曾有两段重复的「已落地的三条/四点优化」）合并为一节；两树 `.comment()` 正文从**存在漂移**收敛为**603 个同名键逐字节一致**（脚本核对 `differing 0`）。
- i18n：`zh_cn/en_us` 与 `promaid/i18n` 经扫**零处**开发叙事（`check_dev_voice` ③ 亦为 0），无需改动；`lang` 里唯一疑似词「登出会话收尾归档」是正常功能标签，保留。

## 验证
- 文案面自检脚本：两树 `.comment()` 正文对 `v\d+\.\d+` / `旧版|旧行为|旧默认|旧值|旧的语义` / `需求：|需求补充|原话：|玩家点名|反馈：` / `TD-|BD-` 命中 **0**（改动前 forge 106 / neo 108 处）。
- `check_dev_voice`：`.comment` 正文 0 处、i18n 键与可见中文 0 处、**新增 ④ 叙事棘轮 0 处**（改前 3/3；本项原就在基线上，本次清的是它**未覆盖**的叙事类）。`FAIL 0`。
- 全护栏：`check_comment_style` PASS 0、`check_catch_swallow` / `check_comment_dup` / `check_i18n_keys` / `check_panel_coverage` / `check_preset` / `check_reflection` 全 **FAIL 0**。
- 两树 `compile_promaid.bat` / `compile_neo.bat` → **EXITCODE=0**（改写后的字符串字面量合法，仅有既存 `@Deprecated` 警告）。
- `gen_switch_inventory.py --write` 重生成 `ARCHITECTURE-SWITCHES.md`（配置注释文本变化使原清单判为过期）；`check_workflow` 回到 **FAIL 0 / WARN 3**（三条均为既存发布件登记项）。
- 未部署、未跑专服回归。

## 验收
- 状态：`未验收`（面板文案为可见 UI，宜由开发者实机扫一眼观感）。

## 边界（没动什么）
- 未改任何配置**键名/默认值/取值范围/开关语义**；只改 `.comment()` 的**说明文字**。
- **未动源码 javadoc / `//` 注释**（含其中的 `实测NNN` 出处行——`DEV_GUIDE.md` §1.2 要求保留一行出处；`check_dev_voice` 只扫 `.comment()` 正文，不涉注释）。
- 未动 `patched/`、`store/`、`_tmp_pub/`；未 commit / push。

## 台账
- 配置文案去叙事完成。后续：④ 抽 `MaidWorkBehavior` 基类；⑤ 拆 `PromaidConfigScreen`（需实机 UI 验收）；⑥ 两树共享源目录（架构级，最高风险）；并「规范化开发流程」（提交规范 + 记忆索引 + 叙事棘轮）。
