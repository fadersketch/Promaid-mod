# 实测一千〇九 issue #40 新日志分析 + 收符事务化 + 手册「特别鸣谢」

## 排班（开工前先填，硬门禁 —— `WORKFLOW.md` §1）
- **跑道**：A 修 bug（issue #40 新日志暴露的"收符一半成功"）+ B 内容（手册致谢板块，不改行为）。
- **开工前读过**：`ARCHITECTURE-SWITCHES.md`（无相关开关）、`DEBT.md`（无等价条目）、`rounds/RELEASES.tsv`（当前 1.3.3 第 141 次重打）、`R1000`/`R1001` —— 结论：R1000 已修"空快照发废符"，但新日志显示"符发出了、女仆没被移除"这一段仍未闭环；致谢板与代码无耦合。
- **工作队列**（逐步，做完就勾；每步写"完成判据"）：
- **边做边记**：每完成一步，把结论写进下方对应小节（不要留到最后补）。

| # | 步 | 完成判据 | 状态 |
|---|---|---|---|
| 0 | 读新日志/崩溃报告/存档证据 | 崩溃栈 = 客户端预判；`maid_info={}` + Promaid 三标记；HUD 04:52:58 出现两条 `soul:圣女酒狐:60/60`，到 04:53:48 仍 10/60 | ☑ |
| 1 | 根因 + 反证 | 收符路径"先放符/发提示、后 discard"；旧日志证明女仆与符并存约 50 秒；TLM 新建符无默认组件（javap），"空组件导致跳过"不成立 | ☑ |
| 2 | 两树改 + 编译 | 收符事务化；`EXITCODE=0`（逐行核对批处理输出的 EXITCODE） | ☑ |
| 3 | 手册致谢板块 | 「详细介绍」新增「特别鸣谢」章；两树 + 双语；79 套口径修正 | ☑ |
| 4 | 打包装机 + 回归 | 4 个目标 `match=True`；专服回归三件 PASS | ☑ |
| 5 | 交开发者验收 | 开发者实测后给出结论 | ☐ |

- **谁验收 / 怎么验**：验收人 = 开发者；验法 = ① 用旧存档里那枚 `maid_info={}` 的废符：更新后进游戏 5 秒内应被换成空魂符并收到提示，右键不再崩；② 触发一次自动收符：`promaid.log` 应有 `[promaid/回魂符]` 成功路径，且女仆从世界里消失；③ 若人为让删除失败（或看日志 `收符未完成`），背包里应是空魂符、女仆仍在，且**不取消伤害**，不会出现"符 + 活女仆"两份；④ 打开手册「详细介绍」最后一章「特别鸣谢」，三条署名与 73 个建筑的口径应完整显示。

## 需求 / 来源
- 来源 1：开发者转来 `https://github.com/fadersketch/Promaid-mod/issues/40` 的补充材料（2026-10-17 04:42 评论）：crash `7bPeGnJ`、promaid 04:48–04:54 `Xv7wQZP`、latest `NVMoV4E`、Fork P0 跟踪 issue。
- 来源 2：开发者口头需求：在「详细介绍」新增「特别鸣谢」板块，把随包蓝图的原作者写进目录（此前只在公开视频里说过）。
- 一句话需求：① 新日志要能解释"空快照 + 女仆还在战斗"并给出闭环修法；② 手册里明确三位 B 站 UP 主的署名与 73 个建筑的口径。

## 评估

### A. issue #40 新日志
- **崩溃发生在客户端预判**：crash report 的线程是 `Render thread`，栈为 `Minecraft.handleKeybinds → MultiPlayerGameMode.useOn → ItemStack.useOn → ItemSmartSlab.useOn → AbstractStoreMaidItem.spawnFromStore → CompoundTag.getUUID → NbtUtils.loadUUID`。右键那一瞬客户端先在本地跑 `useOn`，读到空 `MaidInfo` 就 NPE——不是服务端结算后才崩。
- **空快照的物证**：存档槽位 0 是 `touhou_little_maid:smart_slab_has_maid`，`components.touhou_little_maid:maid_info = {}`；`custom_data` 有 `maid_hp_low_protect_auto_saved / auto_name / cooldown_until`——确认是 Promaid 自动收符的产物，不是 TLM 手动收符。
- **提供者的"空组件"假说被 javap 推翻**：TLM 1.5.3 的 `ItemSmartSlab` 构造器只设 `stacksTo(1).rarity(RARE)`，`InitItems` 注册也没有 `.component(MAID_INFO, …)`；`AbstractStoreMaidItem.storeMaidData` 只在 `stack.get(MAID_INFO) != null` 时跳过。新建 `SMART_SLAB_HAS_MAID` 本身不带组件，所以"新符自带空组件 → 静默跳过"不成立。更可能是 `maid.saveWithoutId(tag)` 在那一刻产出空标签（或被某个模组改写/清空），但**无论哪种**，R1000 的"自己写 + 读回校验 Owner + 不通过就不发符"都已覆盖；本次不再赌上游。
- **"提示了却没被收走"的现场证据**：`promaid.log` 04:52:58 起 `[冷却HUD]` 连续出现 **两条** `soul:圣女酒狐:60/60`，随后 54/49/44/38/33/27/21/15/10 一路倒数到 04:53:48。两条 = 女仆身上的冷却（旧代码在 `storeMaidData` 前就写入）+ 背包里的自动符；如果 `maid.discard()` 真的成功，女仆那条不可能连续存在 50 秒。也就是说旧链路是"符放进了背包、提示也发了、冷却也写了，但人没删掉"——这与提供者"她还在继续战斗"完全一致。
- **04:53:52 的离场不是复活**：`[离场] … reason=null` 后紧跟 `[重同步] … 复活后补包已登记`，这行来自 `ProMaidExtension.onMaidLeaveLevel` 的"存活离场"分支（`scheduleForcedResync`），不是 `MaidAutoResurrect.resurrect()`；日志末尾没有 `[自动复活] 已…复活`。结合 04:53:48 的 10/60 冷却（到 04:53:58 才结束），04:53:52 不可能再触发一次自动收符。
- **根因（本轮补的那一段）**：旧顺序是 `slot.set(符) → 提示 → 药水/音效 → maid.discard()`，任何一步在 `discard()` 之前失败（或 `discard()` 被别的模组拦下）都会留下"可释放的符 + 活着的那只"；`tryReturnToSoulSpell` 的 `catch (Throwable)` 还会把异常吞成 `return false`，伤害不取消，女仆继续战斗。R1000 只解决了"符里没 Owner"，没有解决"人没删掉"。
- **同类点**：成功提示、冷却、药水、音效都在删除之前提交；判定只看"代码走到了 return true"，不看 `maid.isRemoved()`。

### B. 手册「特别鸣谢」
- 随包 79 个 `.litematic` 的 `Metadata` 已逐张读取：
  - `唯一.litematic`、`《Mistry Memory》.litematic` → `Author=yuncaifly`（B站 UP 主 YunCaiFLY）；
  - `协议源石01（已授权）.litematic`、`小蛋挞01（已授权）.litematic` → `Author=PtAr01`（B站 UP 主 PtAr伯亚）；"终末地那个"的准确名字是 **协议源石01**；
  - 其余 75 张的 `Name` 都带 `合集原作者b站[天才_派大星-]`；其中 `地形__MC版 总统山`、`地形__加冕圣地` 是地形投影，不是建筑 → **73 个建筑**，与开发者口径一致。
- 旧手册写"81 套已授权蓝图"已过期（实际 79 张），一并修正为 79，避免与致谢页的 73 对不上。

## 改法
- **收符事务化（两树）** `MaidSoulSpellGuard.tryReturnToSoulSpell`：
  - `slot.set(符)` 后单独 `try { maid.discard(); }`，再看 `maid.isRemoved()`；
  - 未移除 → 把槽位换回 `SMART_SLAB_EMPTY`、清掉女仆身上的 `COOLDOWN_UNTIL_TAG`、写 `[promaid/回魂符] 收符未完成（女仆未被移除）→ 已退回空魂符、不取消伤害：<名字>`，`return false`（伤害照常结算）；
  - 只有确认移除成功，才发"已回到魂符"提示、再生药水与音效；
  - 快照校验失败的日志区分"快照为空"与"缺失 Owner"，下次一眼定位。
  - 旧废符清扫改为**首次进服立刻扫一次**，之后每 5 秒节流（`LAST_SWEEP`：UUID → gameTime），避免玩家进服头 5 秒内右键旧符仍撞上客户端崩溃。
- **手册致谢（两树）**：`GuideChaptersBasics.specialThanks()` + `GuideContent.chapters()` 末尾新增「特别鸣谢」章；三条署名按上面的 Metadata 口径；列出 `天空之城、哈尔的移动城堡、上海中心大厦…… 等共 73 个建筑`，另注明总统山/加冕圣地两张地形投影同样由天才_派大星-提供；末句提醒蓝图仅随模组附赠、请勿转售。中英双语同步。
- **口径修正（两树）**：建造章"81 套已授权蓝图" → "79 套"。
- 被否方案：
  - *只加日志不校验删除* —— 玩家仍然会拿到"符 + 活女仆"，之后右键就是多一只，没闭环。
  - *先 discard 再放符* —— 放符失败就丢女仆，风险更高；现在的"先放符、删不掉就回滚"把两种失败都兜住。
  - *把 73 改成 75* —— 开发者明确要 73 个建筑；两张地形单列，口径自洽。
  - *只写致谢不修 79 口径* —— 手册会说 81 套，和致谢页的 73+4 对不上。

## 触及文件
- `promaid_src{,_neo}/com/maidsmart/combat/MaidSoulSpellGuard.java`（收符事务化 + 失败回滚 + 日志分流 + 废符清扫首 tick 立刻跑）
- `promaid_src{,_neo}/com/maidsmart/guide/GuideChaptersBasics.java`（`specialThanks()`；建造章 79 套）
- `promaid_src{,_neo}/com/maidsmart/guide/GuideContent.java`（章节注册）
- `promaid_src{,_neo}/assets/promaid/i18n/en_us.json`（致谢章 7 条新译文 + 79 套键值）
- 无新配置项 / 无新 mixin。

## 验证
- 两树编译：`compile_promaid.bat` / `compile_neo.bat` 输出 `EXITCODE=0`（首轮 Forge 因新增方法漏了 `compressionBoxGuide` 的收尾 `}` 报"非法的表达式开始"，补上后重编通过）。
- 14 护栏：`FAIL 0 / WARN 0`（`check_guide_i18n` 对新增 7 条中英逐条 PASS）。
- 打包：`patched/` 现字节
  - forge `bcb627cb…60ae`（17939375 字节）
  - neoforge `207d08ae…7cd2`（17987001 字节）
- 装机：`deploy_all.py` 4 个目标 `match=True`。
- 专服回归：`test_server.py 1201` PASS、`test_server.py neoforge1211` PASS、`test_maid_load.py neoforge1211` PASS。
- **反证**：
  - 旧链路在 `discard()` 之前就写冷却/放符/发提示（逐行核对基线 `HEAD:cdb4bd05`）；HUD 两条 60/60 连续 50 秒证明人还在。
  - 新链路 `removed=false` 时先把槽位换回空符、再清冷却，最后才 `return false`；不存在"失败还留符"的分支。
  - TLM 新建符无默认组件已由 `javap -c` 对构造器与 `InitItems` 注册逐条核对；提供者的假说不再作为修法依据。
- 未做的验证：未在开发者存档里实际右键那枚旧废符（旧符应由清扫链在进游戏后 5 秒内换掉）；未跑客户端图形界面检查致谢章排版（只过渲染器/译文护栏）。

## 验收（谁说了算：开发者 —— `WORKFLOW.md` §4.1）
- 状态：`未验收`。
- 未验收之前：不写 changelog、不写 release 说明、不发平台。

## 边界（没动什么）
- 没有在 GitHub 回帖/发补丁（开发者未要求）；没有动 Fork 仓库与任何远端。
- 没有改 TLM 的 `storeMaidData` / `spawnFromStore` 本身；没有加客户端 mixin 去拦旧废符（清扫链覆盖玩家背包，容器里的旧符不在本轮范围）。
- 没有改自动复活的死亡/墓碑/复活链路；04:53:52 那行 `[重同步]` 是离场分支的既有日志，本轮未改文案。
- 致谢只动手册正文与译文，没有改蓝图文件、署名元数据或解压清单。
- 73 个建筑的口径按开发者要求；两张地形投影单列，不并入 73。

## 台账
- 清掉的债：无对应 `BD-` 条目（issue #40 的收尾段在本轮闭环）。
- 新增的债：无。
- 配额：A 类修 bug + B 类内容同包，不触发功能轮配额。
