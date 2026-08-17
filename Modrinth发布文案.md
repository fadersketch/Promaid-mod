# Promaid · Modrinth 发布文案(v1.0.2)

> 使用方法:打开 modrinth.com → 登录 → Dashboard → 项目 `promaid` → Versions → Create new version,按下面逐项填写。

---

## 一、版本上传(Create version)

| 字段 | 内容(直接复制) |
|---|---|
| **Version number** | `1.0.2` |
| **Game versions** | 勾 `1.20.1` |
| **Loaders** | 勾 `Forge` |
| **File** | 上传 `promaid-1.0.2.jar`(SHA256 `c88e7b2d574cd2397585c8854298dd3ce3a78fda7e27df20d5d4817c0089d766`,与 GitHub release v1.0.2 附件一致) |
| **File name** | `promaid-1.0.2.jar`(与 jar 内 mods.toml 的 version 一致) |
| **Release type** | `release` |
| **Dependencies** | 搜索 **Touhou Little Maid**(slug: `touhou-little-maid`)→ 选它 → 依赖类型 **Required** |
| **Changelog** | 粘贴下面的版本 changelog |

### 版本 Changelog(复制)

```
Promaid v1.0.2 —— 正式发布

⚠️ 重要:v1.0.0 与 v1.0.1 已删除,原因:内置建筑蓝图未获原作者授权。本版本已全部移除未授权内置蓝图,建造目录只保留玩家自己导入/生成的外部蓝图(建筑均自备授权)。

本次更新:
- 移除全部内置蓝图(原 25 套程序化预设 + 8 个自动复制大预制),启动时自动清理残留;
- 修复 callresponse 2.0.4 兼容性(移除失效 mixin,消除启动崩溃);
- 修复 mixin 类被业务代码引用导致的 NoClassDefFoundError;
- 心契誓约对话/告白面板中文换行与居中重做(不再依赖 Font.split);
- 玩家告白文案方向修正(玩家对女仆告白);
- 女仆主动告白概率大幅提高(满好感 ≈0.95);
- maidmarriage 调试面板新增红字免责声明。

测试版本声明:经测试者验证,在 TLM 1.5.3 / maidmarriage 2.3.0 / callresponse 2.0.4 / Heartfelt-connection 1.0.0 下可正常运行;其他版本未必兼容。

完整日志:https://github.com/fadersketch/Promaid-mod/releases/tag/v1.0.2
```

---

## 二、项目正文(Body,粘贴下面全部内容)

# Promaid —— 更智能的车万女仆

《车万女仆》(Touhou Little Maid,TLM)的 Forge 扩展模组,让女仆真正"活"起来:自主建造、LLM AI 对话、长期记忆、战斗自保、自动装备武器与工具。

- **Minecraft 1.20.1 / Forge 47.4.21**
- **前置**:Touhou Little Maid ≥ 1.5.0(推荐 1.5.3)
- **许可证**:MIT,开源
- **当前版本**:v1.0.2
- 开发过程中使用了 AI 辅助编程;作者并非专业程序员,可能有缺陷,欢迎反馈

## 功能特性

### 🏗️ 建造系统
- **Promaid 手册(蓝图书)**:书 + 纸合成,建造目录显示你导入/生成的外部蓝图(config/maid_smart/blueprints 或存档 schematics/,支持 .schem/.litematic/.nbt/.snbt/.json),女仆自动施工;
- **LLM 现场生成 / AI 设计器**:对话下达「造一个 5x5 现代小屋」AI 直接出蓝图;「设计一个中式庭院」子 AI 设计完存进手册再建造;
- **工头模式**:一区块一工头,缺料/跳过/完成汇报统一由工头发送;多区块共存互不挤占;
- **建造 HUD + 进度持久化**:实时速度与预计完成时间;计划随女仆存档,重启继续建;
- **缺料自动替代**:先同族后自定义的替代方块系统;材料等价族互通;
- **机械蓝图**:自动熔炉组等,六层自下而上搭建,动力源最后落地;
- 区块冻结防崩溃、拟人化建造排序、极速模式、悬空方块强制补支撑、强制加载配额兜底。

### 🧠 LLM AI 女仆
- 接入任意 **OpenAI 兼容 API**,女仆获得 20+ 工具:挖矿、整理、烹饪、酿造、建造、给物品、移动、拾取、查询记忆、感知环境、**查看主人背包**(smart_owner_inventory)……;
- **主动对话 / 工作播报 / 自主决策**:主动汇报工作、提醒危险、表达情绪;空闲时自己判断该干什么并换任务;
- **长期记忆**:段落 / 关系三元组 / 主人画像 / 工作笔记 / 多级记忆日记(日/3日/周/月),跨存档会话保留,睡一觉自动整理;
- **情绪感知 / 回复反馈学习 / API 配额管理**:语气随心情,被否定的话题不再提,日配额控成本。

### ⚔️ 战斗与自保
- **自保行为**:低血回血、搭方块避险、传送回主人身边、水桶垫水、岩浆逃生、窒息/溺水自救;
- **被动技能**:落地水、岩浆逃生放水、主人死亡传送(独立配置板块);
- **贴身辅助**:自动投喂(直接进饱食度)、治疗链、牛奶/蜂蜜解除负面、共享盾牌/不死图腾;
- **单兵战术**:剑单敌 100% 跳劈、hit&run 拉扯、近战贴脸后退、举盾格挡节奏;
- **自动装备**:按任务切换最佳武器/矿镐/工具,自动装备盾牌,主手快坏提前更换——判定基于攻击力属性,无黑名单,Better Combat 等模组武器天然支持;
- 玩家伤害模式可配置(原版压制/完全免疫/无限制/百分比上限/仅一点)。

### 🛠️ 任务与杂项
- 自动挖矿(矿表白名单/连锁采集/自动收集)、烹饪、酿造、农场连锁收获与批量种植、钓鱼自动坐椅;
- 干活不被打断(吃饭/偷吃/恐慌/切班拉回可关)、跨维度跟随、TTS 语音与系统语音包、聊天气泡上限、拾取优先级等细节优化;
- 全部功能游戏内配置面板逐项开关。

### 📖 内置手册
- 游戏内 Promaid 手册内置 **25 章保姆式教学**(每章先讲「是什么」再手把手教「怎么用」,关键步骤配【举例】)+ 完整逐版本更新日志。

## 测试版本声明

经测试者验证,本版本在以下模组的具体版本下可以正常运行;其他版本(含更新/更旧的 TLM 与各软联动模组)**未必兼容**,请以实际测试为准:

| 模组 | 关系 | 测试通过的具体版本 |
|---|---|---|
| Touhou Little Maid(车万女仆) | 必装前置 | 1.5.3(`touhoulittlemaid-1.5.3-forge+mc1.20.1.jar`) |
| maidmarriage(心契誓约) | 软联动 | 2.3.0(`maidmarriage-2.3.0-forge+mc1.20.1.jar`) |
| callresponse(爱憎分明) | 软联动 | 2.0.4(`Touhou Little Maid-Love  Loathe-1.20.1-forge-2.0.4.jar`) |
| Heartfelt-connection(心契誓约×爱憎分明补丁) | 软联动 | 1.0.0(`heartfelt_connection-1.0.0.jar`) |

## 安装
1. 安装 Minecraft 1.20.1 + Forge 47.4.21,以及 Touhou Little Maid 1.5.3;
2. 下载本页 jar 放入 `mods` 文件夹;
3. (可选)AI 对话:游戏内 TLM 面板 → 模组详细配置 → 填 API 地址与密钥。

旧版 maid_smart 存档数据(物品、任务、蓝图、记忆)完全兼容。

## 反馈
问题与建议欢迎到 GitHub Issues 提交:
https://github.com/fadersketch/Promaid-mod/issues

## 许可证
[MIT](https://github.com/fadersketch/Promaid-mod/blob/main/LICENSE) © 2026
