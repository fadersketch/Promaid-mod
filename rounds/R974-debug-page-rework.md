# R974 · 链路调试页重做（选中身份 / 恢复 / 削弱 / 停用关系 / 刷新可见 / 中文化）

## 排班

1. 反查六条现象 → 代码根因（见「需求 / 来源」表）。
2. `AiMemoryStore` 按键 API（含 `allParagraphs` 含已作废 / `setRelationInactive` 单条关系）。
3. `collectDebugRows` 改五行结构 + 中文 info；`sendDebugResponse` 带回执参数。
4. 响应包 `note` + 第五字段；动作包换按键制 + `weaken` / `activate_rel`；删聊天栏回执。
5. 客户端选中键化 + 动作按钮两行八键 + 中文摘要 + 回执与刷新时刻可见 + 空状态提示自绘。
6. 三处「清空调试态」（返回女仆列表 / 切 tab / 换女仆）同步改键。
7. 两树编译、14 护栏、双包、部署 4 实例、四件回归、台账与文案。

## 需求 / 来源

手册「女仆记忆 → 链路调试」页被判为不符合使用、繁琐无用。逐条对现象查代码，六条里五条都有明确根因：

| 现象 | 真实根因 |
| --- | --- |
| 点上面那条改重要度，下面那条也跟着变 | 选中项记的是**列表下标**；动作成功后服务端按重要度**重排**再回发，下标就指到了隔壁那条 |
| 「恢复」按键一点用都没有 | 列表走 `store.paragraphs()`，它**过滤已作废**——删掉的行直接消失，根本选不中，恢复永远返回失败 |
| 「停用关系」与「删除」看不出区别 | 关系行的定位键只带**谓词**，停用 = 按谓词**批量**停用同谓词的所有关系 |
| 「刷新」刷了跟没刷一样 | 请求发出去了，但界面**没有任何可见反馈**（无时刻、无回执） |
| 只有强化，没有削弱 | 确实没做——只有 `reinforceByHash` |
| 一堆英文与内部字段突脸、说明看不懂 | 列表直接拼内容 + `重要度5·访问0·world:minecraft_overworld`，关系拼 `主体+谓词+客体`；标题只罗列动作名，没说这页怎么用 |

顺带发现：调试页的 `graphicsHint` 文案从未被绘制（`renderMemory` 在调试页提前 return），空状态一直看不见。

## 改法

### `memory/AiMemoryStore`

新增**按稳定键**的调试 API（旧 `*ByHash` 全保留，其它调用点不动）：

- `debugKey(Paragraph)` = `hash@createdAt`。内容相同的两条段落 `hash` 相同（`hashOf(content)` 是内容归一化哈希），
  只按 hash 定位会把两条同内容段落当成同一条改，所以键里必须带创建时刻。
- `paragraphByKey` / `allParagraphs()`（**含已作废**）/ `markDeletedByKey` / `restoreByKey` /
  `adjustSalienceByKey` / `reinforceByKey` / `weakenByKey`。
- `setRelationInactive(三元组 hash, boolean)`——按**单条**关系停用或恢复（旧 `deactivateRelationsByPredicate`
  保留给撤婚流程）。

### `build/BlueprintBookNetworking`

- `collectDebugRows` 行结构改为 **5 字段** `{type, key, info, text, state}`：段落键 = `hash@createdAt`
  （含已作废，`state=deleted`），关系键 = 三元组 hash（含已停用，`state=inactive`）；`info` 全中文
  （`重要度 5 · 想起 0 次 · 永久` / `把握 80%`），关系 `text` 用 `主体 → 谓词 → 客体`。
- `sendDebugResponse(..., note)` 多一个回执参数。

### `build/BlueprintBookEntityPackets`

- 响应包加 `note` 字段（回执画在面板里）＋ 行第五字段入出包。
- 动作包改按键定位，新增 `weaken` / `activate_rel`；**删掉全部聊天栏回执**（原来每点一次往聊天栏刷一行，
  就是那串重复提示的来源），回执改由 `note` 随快照回到面板。

### `build/BlueprintBookScreen`

- 选中项由**下标**改为**稳定键** `debugSelKey`（`debugSelIndex()` 每次按键回查），刷新后按新列表重新定位，
  那条真没了才清空选择。
- 动作按钮改**两行八键**：`↑重要度 / ↓重要度 / 强化 / 削弱` + `标为作废 / 恢复 / 停用关系（已停用则显示恢复关系）/ ↻ 刷新`；
  按选中项能否生效**点亮或置灰**（段落已作废只能用恢复；关系只能用停用或恢复关系；没选中全部置灰）。
- 列表只显示**中文摘要**（`【已作废】` / `【已停用】` + 类型 + info + 40 字预览），全文在上方
  `▷ 选中的这条（info）：` 折行展开。
- 面板内一行回执：`上一步：<服务端回执>`；没有回执时显示 `数据 HH:mm:ss 刷新`。
- 调试页自绘空状态提示（请求中 / 还没有可调试的记忆）。

## 触及文件

- `promaid_src` / `promaid_src_neo`：`com/maidsmart/memory/AiMemoryStore.java`、
  `com/maidsmart/build/BlueprintBookNetworking.java`、`com/maidsmart/build/BlueprintBookEntityPackets.java`、
  `com/maidsmart/build/BlueprintBookScreen.java`（两树同改）。
- 文档：`rounds/R974-debug-page-rework.md`、`rounds/RELEASES.tsv`、`DEV_GUIDE.md`、四份发布文案、`机制详解.md`。

## 验证

- 两树 `javac`：forge `EXITCODE=0`（87 警告，全为既存）；neo `EXITCODE=0`
  （首轮 neo 报两处 `BlueprintBookNetworking.CHANNEL` 找不到符号——neo 走 `PacketDistributor.sendToServer`，已改）。
- 14 护栏全 `FAIL 0`（`check_comment_style` `RESULT: PASS`；`check_tree_mirror` 只报告不判门）。
- 包体：forge **17,654,943** 字节 `2df26e6b…a138c0`；neo **17,702,249** 字节 `34eb8511…b64339`。
- 部署：`deploy_all.py` 4 个目标全 `match=True`（`elevated rc: 0`）。
- 回归：`test_server.py` / `test_maid_load.py` 两加载器各两件，四件全 `verdict: PASS`。

## 验收

进游戏打开手册 → 女仆记忆 → 某个女仆 → 链路调试页，逐项对照：

1. 点一行选中（行首出现 `▶`），上方出现「选中的这条」全文；再点同一行取消选中。
2. 选中一条段落 → ↑↓重要度连点多次：**列表重排后选中的仍是同一条**，隔壁那条不变。
3. 强化 / 削弱各点一次：重要度分别 +1 / -1（1~10 夹取），回执写在面板里，**聊天栏不再刷行**。
4. 标为作废 → 该行留在列表里、带 `【已作废】`；选中它点「恢复」→ 标记消失。
5. 选中一条关系 → 只有「停用关系 / 恢复关系」是亮的；停用后该行带 `【已停用】`，再点「恢复关系」还原。
6. 点「刷新」→ 面板出现「数据 HH:mm:ss 刷新」（每次都会变）。
7. 没选中任何行时，八个动作按钮全部灰掉；点「刷新」仍可用。

## 边界

- 「标为作废 / 恢复」仍是**软删除**语义（`deleted` 标记），与既有存储一致，不做物理删除。
- 关系停用按**三元组**（主体|谓词|客体）粒度；旧「撤婚」流程仍走按谓词批量的老方法，未改动。
- 段落键含 `createdAt`，同一毫秒创建且内容完全相同的两条会共用一键（概率极低，命中时改到其中一条）。
- 调试页权限判定沿用旧逻辑（主人或 OP），未放宽。
- 客户端与服务端需**同版本**：响应包新增了字段（旧客户端连新服务端会解包失败），已按同号重打一起部署。

## 台账

- `rounds/RELEASES.tsv` 两条 1.3.2 行 **第一百一十次**同号重打，旧字节
  `4d1abcc9…009d5e`（forge）/ `b8510224…bde0ec`（neo）（第一百零九次 R973）作废。
- `DEV_GUIDE.md` 发布区间 → 八百五十九 ~ 九百七十四。
- 四份发布文案在 R973 两条之后追加本轮两条。
- `机制详解.md`：`AiMemoryStore` / `BlueprintBookNetworking` / `BlueprintBookScreen` 三条补本轮说明。
