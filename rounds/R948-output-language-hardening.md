# R948 对话语言「偶尔还是中文」治根：输出语言硬指令 + 按目标语言检测 + 气泡文案查漏

> 跑道：承接 `rounds/R947-*.md`（五条需求 + 部署）。本轮来源是**玩家反馈**（英文版使用者）：
> 输出语言设成英文后，女仆的对话**偶尔**还是中文。排查发现这不是一个 bug，而是三条互不相干的原因叠在一起。

## 排班

- 单轮：先诊断（找出"哪条路径会把中文送到玩家眼前"）→ 再逐条修 → 两树镜像 → 12 护栏 → 双 jar → 部署 4/4 → 台账/轮次 → 清临时。
- 谁验收：开发者（本轮**已部署**、不跑回归；`## 验收` 记 `未验收`）。

## 需求 / 来源

玩家反馈原文（英文，@fadersketch）：

> "Thank you!, absolutely love the new update translation, such an amazing mod!. By the way, I change the dialogue output to English, but sometimes the dialogue output still in Chinese"

## 诊断（三条根因，都有代码/字节码证据）

**① 系统提示词只有"语言变量"，没有硬要求。** TLM 的 system 提示词按 `${chat_language}` 要求输出语言，我们的
`MaidChatLanguageMixin` 只把 `ChatClientInfo.language` 换成配置值。当人设、记忆、上下文全是中文时，
模型仍会整段飘回中文——这是"偶尔"的主要来源。

**② 输出检测对"非中文目标"整个关掉了。** `MaidChatLanguageMixin` 的二次检测（收到回复→不合设定语言就
内嵌翻译）第一句就是：
```java
if (!target.startsWith("zh")) { return; }   // 非中文目标暂不检测
```
设成英文的玩家，这条**唯一的兜底机制**根本不跑；再加上翻译指令里目标语言写死"翻译成简体中文"，
即使打开也不是翻成英文。

**③ 我们自己的固定台词气泡是写死的中文。** 全模组 78 个 `addTextChatBubble` 调用点、
29 个文件；另有 50 条排班闲聊池、约 25 条拴绳/骑乘拒绝原因等**间接来源**（合计 136 条中文）。
这些文案**不经 LLM**，靠显示层翻译（`PromaidI18n` + `assets/promaid/i18n/en_us.json` 词条表，
客户端 Font/GuiGraphics/ChatComponent 三处 mixin 兜住渲染出口）翻成英文。
实测：词条表 6992 条已覆盖 159/167 条，**剩 8 条没进表**——这 8 条就是气泡里"偶尔"蹦出的中文。
（其中 2 条在"主人聊天栏"那行还会被名字连累：整行是 `§d[名字] §d台词` 一个字面量，
名字里带汉字时译文层的硬守卫会**整句放弃**，台词的英文也跟着没了。）

## 改法（五处）

- **`com/maidsmart/dialogue/OutputLanguage.java`（新增，两树）**：输出语言的**唯一口径**——
  `current()` / `displayName()` / `nativeName()` / `directive()` / `matches(text[,code])`。
  提示词与检测共用它，避免"提示词说英文、检测按中文"。
- **提示词硬指令（`PromaidPromptMixin`）**：在 `LLMMessage.systemChat` 注入点再追加一段
  `## Reply Language (Promaid)`（英中日韩俄各自的语言名，含"不因主人换语言/人设是中文而改"），
  与原有"坐姿说明"各自带 MARKER，幂等且互不干扰。
- **输出检测按目标语言走（`MaidChatLanguageMixin`）**：删掉"只查中文"的分支，
  改用 `OutputLanguage.matches`——中文=汉字占比 ≥40% 且假名 <10%（原判据不变）；日文=有假名或有汉字；
  韩文=有谚文或有汉字；英/俄等=**出现 CJK 即算不符**。翻译指令里的目标语言跟着设定走
  （中文提示 + 英文提示双写）。原来的"翻译一次仍不符就放行原文"不变。
- **气泡文案查漏（两树 `assets/promaid/i18n/en_us.json`）**：补 8 条（自保"这里烫"/没镐子/
  挖不过去与"进矿表"提示/"去目标（坐标 …）的路上有岩浆"/载具拒绝两条），另把配置面板
  "输出语言"那条词条的说明一起改（见下）。
- **聊天栏同步行拆段（`ChatBubbleLimitMixin` + `PromaidI18n`）**：主人聊天栏那行改成
  `Component.empty()` 下挂四段（前缀 / **名字** / 分隔 / 台词），`PromaidI18n.tr(Component)`
  新增"根本没有正文、只有兄弟段 → 逐段翻译"的路径——名字（玩家自己起的，不在词条表里）原样保留，
  台词那段单独查表。**中文玩家一字不变**（`isChinese()` 直接原样返回）。
- **配置面板说明修正（两树 `PromaidConfigScreen`）**：`输出语言` 的说明原来写"跟随 = 用客户端当前语言"，
  但 v1.5.228 起代码是**留空 = 强制中文**；改为如实描述，词条表那条也同步改名+改英文。

## 触及文件

- 两树 × `com/maidsmart/dialogue/OutputLanguage.java`（新增；neo 树同步登记进 `tools/arch/arch_modules.py` 的
  `promaid.social.dialogue` 模块 source，否则 `check_arch` 覆盖棘轮会涨）
- 两树 × `com/maidsmart/mixin/{PromaidPromptMixin,MaidChatLanguageMixin,ChatBubbleLimitMixin}.java`
- 两树 × `com/maidsmart/i18n/PromaidI18n.java`（`tr(Component)` 逐段路径 + `ownTextEmpty`）
- 两树 × `com/maidsmart/config/PromaidConfigScreen.java`（输出语言说明）
- 两树 × `assets/promaid/i18n/en_us.json`（+8 条气泡词条；1 条说明改名）
- `tools/verify/check_bubble_i18n.py`（**新增第 12 个护栏**）+ `tools/arch/arch_modules.py`
- `rounds/RELEASES.tsv`（第八十七次同号重打）+ `rounds/R948-*.md`（本文件）
- **未动**：`MaidSmartConfig`（未新增配置项）；i18n 词条表其余 6900+ 条；`PromaidI18n` 的 `compose` 硬守卫。

## 验证

- **两树编译**：`javac EXITCODE=0`。
- **护栏 12 个**：全 `FAIL 0`。
  - 新增 `check_bubble_i18n.py`：把两树气泡直呼中文 + 间接来源（闲聊池/拒绝原因/播报/工具回复）
    逐条模拟 `PromaidI18n.tr()`（整串命中 / `compose` 逐段拼装）——**两树各 136 条，缺译文 0 条**。
  - 中途两处红灯并修掉：`check_comment_style` 报**新写注释里出现"玩家反馈"叙事**（BANNED/QUOTE-SPEECH，两树各 2 处）
    与 `PromaidPromptMixin` 类头 20 行 > 15 行 → 改写成中性叙述并缩短类头；
    `check_arch` 报 **neo 未覆盖 182 > 基线 181**（新文件没登记）→ 登记后重生成 arch 产物（144 文件冻结）。
- **打包**：forge **17604940** 字节（sha256 `91F64540…0F916C`）；neoforge **17639711** 字节（`2F9982F9…13D458`）。
- **装机**：`deploy_all.py` → 4 个本机实例全部 **`match=True`**。
- **留痕**：`rounds/RELEASES.tsv` 两条 1.3.2 行更新 sha256 并列**第八十七次**同号重打；
  R947 旧字节 `10BA0C12…A09C15`（forge）/ `B3C64A41…96112D`（neo）作废。
- **未跑实机回归**（已部署，等开发者实测）——需实测确认：英文设定下连续对话不再蹦中文；
  中文玩家的气泡/聊天栏**一字不变**；名字带汉字的玩家聊天栏里台词也是英文；俄语等其他语言设定同理。

## 验收

- 状态：**未验收**（已部署：本机 4 实例与 `patched/` 逐字节一致；未回归、未 commit、未 push）。
- **判据**：
  - `输出语言 = 英文` 后连续对话 20 轮以上：回复全英文；若模型偶发中文，**会被内嵌翻译成英文**
    （日志搜 `lang translate` 能看到拦截与被翻的原文）。
  - 自保/投喂/图腾/挖矿伐木/钓鱼/感知/排班闲聊/拴绳骑乘拒绝等**事件台词**全英文；
    聊天栏那行 `[名字]` 后面的台词也是英文（名字本身按玩家起的样子保留）。
  - `输出语言 = 中文`（默认）：气泡与聊天栏一字不变，日志里不出现 `lang translate`。
  - `输出语言 = 日文/韩文/俄文`：同样按该语言判定与翻译。

## 边界（没动什么 / 已知取舍）

- **TTS 音频仍是原来的读法**：气泡朗读按**中文原文**（内置日语语音包与语音缓存都按原文命中，
  行为一字不变）。英文玩家会看到英文文字、听到原来的语音——**文本本地化与音频本地化是两件事**，
  音频要不要跟语言走，等你定。
- **LLM 侧不可能做到 100%**：硬指令 + 检测兜底能压到很低，但模型偶尔整段外语且翻译也失败时，
  仍会放行原文（刻意保留的失败模式：总比不回答好）。
- **AI 设计工具的回复**（`SmartDesignTool.finish(json)`）走的是模型原文，语言跟着模型走，未额外处理。
- **历史 `v1.x.y` 标记**：i18n/配置描述里遗留的约 92 处（非本轮引入）未清，仍待另开一轮。

## 台账

- `rounds/RELEASES.tsv`：两条 1.3.2 `workspace` 行更新 sha256 并列第八十七次同号重打。
- `DEBT.md`：不新增条目（本轮是既有机制的缺陷修复，不是新债）。
