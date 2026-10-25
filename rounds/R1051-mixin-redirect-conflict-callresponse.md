# R1051 修复与 callresponse 的 @Redirect 冲突（女仆生成崩溃）

## 排班（开工前先填，硬门槛 —— `WORKFLOW.md` §1）

- **跑道**：A 修 bug / 调行为 —— 1.21.1 NeoForge + 1.20.1 Forge 同规。
- **开工前读过**：`rounds/R1050`；`MaidChatLanguageMixin`（两树）；TLM
  `MaidAIChatManager` / `MaidAIChatData` / `LLMCallback` 字节码；2026-10-19 22:40 客户端崩溃日志。
- **工作队列**：

| # | 步 | 完成判据 | 状态 |
|---|---|---|---|
| 1 | 定位冲突 | 崩溃栈指向 `TalkMaidAIChatManagerMixin` 的 Redirect 失败（0/1） | ★ |
| 2 | 换注入点 | 不再 Redirect `normalChat` 的调用点，改为在 `MaidAIChatData.addUserHistory` HEAD 取消 | ★ |
| 3 | 修嵌套混入 | 日志确认顶层 `MaidLangCheckMixin` / `MaidInternalHistoryMixin` 真正应用到目标类 | ★ |
| 4 | 两树编译 | Forge / Neo `EXITCODE=0`（先重跑 `gen_compile*.py`，确保新文件进编译清单） | ★ |
| 5 | 护栏与专服 | 14 护栏 `FAIL 0`；`test_server` / `test_maid_load` 两套 PASS | ★ |
| 6 | 打包装机 | 4 个目标与 `patched/` 同字节 `match=True` | ★ |
| 7 | 交开发者验收 | 在装有 callresponse 的客户端生成女仆不再崩 | ☐ |

## 需求 / 来源

- 2026-10-19 22:40 客户端崩溃日志：生成女仆时 `MixinTransformerError`，根因是
  `callresponse` 的 `TalkMaidAIChatManagerMixin` 与 `promaid` 的 `MaidChatLanguageMixin`
  对 `MaidAIChatManager.addUserHistory(String)` 同一个调用点各做了一次 `@Redirect`。
- 日志原文：`@Redirect conflict. Skipping ... already redirected by ...MaidChatLanguageMixin`，
  随后 `InjectionError: ... failed injection check, (0/1) succeeded. Scanned 0 target(s).`

## 评估

- **级别**：P0——任何女仆生成都会触发（`EntityMaid` 构造时加载 `MaidAIChatManager`），
  进世界后放女仆、刷怪蛋、旧档加载女仆全部崩服。
- **根因**：Mixin 对同一条 `INVOKE` 指令只允许一个 `@Redirect`。promaid 先注册，callresponse
  的重定向找不到目标；它的注解默认 `require = 1`，于是抛 `InjectionError` 并把
  `MixinTransformerError` 抛进游戏线程。
- **同类点（本轮实机日志暴露）**：原来挂在 `MaidChatLanguageMixin` 里的两个嵌套 `@Mixin`
  类（`MaidLangCheckMixin`、本轮新增的历史跳过）**并不会被 Mixin 单独注册**；日志只有
  `Inner class ... gets unique name`，没有独立的 `Mixing ... into <目标>`。因此输出语言检测/
  翻译一直是死代码，本轮一并提成顶层混入并登记。
- 已查 `ARCHITECTURE-SWITCHES.md` / `DEBT.md`：无等价开关，也非已登记的债。

## 改法

- 删除 `@Redirect(method = "normalChat", target = "...addUserHistory(String)V")`。
- 新增顶层混入 `MaidInternalHistoryMixin`（`@Mixin(MaidAIChatData.class)`），在
  `addUserHistory(String)` 的 `HEAD` 判断 `ChatInfoUtil.isInternal(prompt)`，是则 `ci.cancel()`。
- 把原嵌套 `MaidLangCheckMixin` 提成顶层类（`@Mixin(LLMCallback.class)`），代码与判据不变。
- 两个新混入登记进两树 `mixins.promaid.json`。
- 行为不变：内部触发（主动对话/自主决策）仍按 system 消息发送，且不写入主人历史；
  callresponse 的调用点重定向可以正常注入。被否方案：给两边调优先级只会把失败转给另一方，
  且 callresponse 的重定向是它自己的功能，不能替它降级。

## 触及文件

- 新增（两树各 2 个）：
  - `com/maidsmart/mixin/MaidInternalHistoryMixin.java`
  - `com/maidsmart/mixin/MaidLangCheckMixin.java`
- 修改（两树各 3 个）：
  - `com/maidsmart/mixin/MaidChatLanguageMixin.java`（移除嵌套混入与旧的 addUserHistory Redirect）
  - `mixins.promaid.json`（登记两个新混入）
- 架构清单：`tools/arch/arch_modules.py` 登记新文件并重生成 `normify-promaid/`。
- 无新配置项、无新 i18n key。

## 验证

- Forge `compile_promaid`：`EXITCODE=0`；Neo `compile_neo`：`EXITCODE=0`。
- 14 护栏全部 `FAIL 0`（`check_mixin_targets` 校验注入点 283，下限 200；`check_arch` 覆盖率回到基线）。
- `test_server 1201`：PASS；`test_server neoforge1211`：PASS。
- `test_maid_load 1201`：PASS；`test_maid_load neoforge1211`：PASS——专服内
  `summon touhou_little_maid:maid` 成功。
- 日志确认顶层混入真正生效（Forge / Neo 两套专服）：
  - `Mixing MaidLangCheckMixin ... into ...LLMCallback`
  - `Mixing MaidInternalHistoryMixin ... into ...MaidAIChatData`
- **反证**：修复前，同一崩溃栈在客户端生成女仆时稳定复现
  （`InjectionError` + `MixinTransformerError`）；修复后，专服女仆生成用例通过。
  专服未安装 callresponse，因此“与 callresponse 共存”这一条以静态注入点分析 +
  日志中的冲突消失为准，最终需开发者在实机客户端复验。
- 产物：
  - Forge `patched/promaid-1.3.3-forge-1.20.1.jar` 18,081,886 字节，
    SHA256 `427DC2CED8469D33D400385E04B023275449FA51CC8DB2280BC8D5315BCD6465`
  - Neo `patched/promaid-1.3.3-neoforge-1.21.1.jar` 18,174,633 字节，
    SHA256 `E6B20D8E0448FCCC35461D973E751D849DCD7826D3AE78D31805452C5B574DFF`
- `deploy_all.py`：4 个实例全部 `match=True`；部署前确认无 java 进程，未杀进程、未绕过守卫。

## 验收

- 状态：`未验收`。验收人 = 开发者；验法 = 在安装 callresponse 的客户端进入世界、
  生成一只女仆、进行一次普通对话和一次主动对话，确认不再崩溃、主动对话仍不进历史；
  再让模型输出一次非目标语言，确认 `logs/promaid.log` 出现 `lang translate`。

## 边界（没动什么）

- 不修改 callresponse；不改 TLM 对话流程；不改内部标记的生成与识别规则。
- `tryToChat` 的语言强制未动。
- 输出语言检测/翻译从“未注册的死代码”变为真正生效——这是本轮顺带修复，首次生效后
  遇到不符目标语言的回复会多一次翻译请求（受 AI 总开关、语言检测开关、单女仆 LLM 门禁约束）。
- 未处理本轮日志中其它环境噪音（蓝图加载提示、配方分类警告、Curios 重复包、Sodium/Iris 启动崩溃等）。

## 台账

- 本轮不清债；未新增债。
