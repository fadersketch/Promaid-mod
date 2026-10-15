# 实测九百二十五 自动复活"永远剩 5 秒"的真根因（卡住的死体占着 UUID）+ 按开发者要求回退 home 门禁

> 跑道：**B 功能抢修 + 口径回退**。开发者上一轮的四个现象里，③ 被开发者当场判为"没修好"，本轮重查重改；② 的两处放宽之一是开发者明确要求撤销的。
> 开发者指令（原话）：「Home模式下，传统的门禁还是调回去吧。那是我自己的疏忽。如果让位，会出很多的问题。自动复活方面，你明明说成功复活了，但是他并没有成功复活呀，就只是在一直重新读条而已。」
> 上一轮：`rounds/R924-four-live-bugs-deploy.md`（其中 ③ 的根因判断**已作废**，本文件取代它）。

## 排班

- **本轮主项**：① 自动复活真正修好（九百二十四 只治了"她已经活着"这一半）；② 撤销九百二十四 给骑乘者的 home 门禁让位。功能面（数值/矿表/建筑/对话/战斗）一律不动。
- 步骤与完成判据：
  1. 用**上一局的实机日志**（`1.21.1-NeoForge_21.1.250/logs/`）把"倒计时结束又刷回 5 秒"整条链钉死，并反编译对照原版实体登记表（`PersistentEntitySectionManager`）确认"被拒"的判据；判据 = 每个结论都能引到**具体日志行或字节码**。
  2. 改 Forge 树 → 3. 镜像 Neo 树；判据：两树 `javac` `EXITCODE=0`。
  4. 护栏 → 打 jar → 部署 4 实例；判据：全护栏 `FAIL 0`；4 目标 `match=True`。
- 谁验收：开发者（本轮只部署、不跑回归；`## 验收` 记 `未验收`，并给出实机复现步骤）。

## 需求 / 来源

- ③ 开发者口径：「你明明说成功复活了，但是他并没有成功复活呀，就只是在一直重新读条而已。」——上一轮我把"她其实已经活了"当成根因，**漏了另一种占位者**。
- ② 开发者口径：「传统的门禁还是调回去吧……如果让位，会出很多的问题。」——九百二十四 让骑乘者绕过守家，实测牵连太多，撤销。

## 定位与改法（含日志/字节码铁证）

### ① 自动复活：倒计时走完 → 刷回 5 秒 → 反复刷新 → 永远回不来（**真根因**）

**日志**（`1.21.1-NeoForge_21.1.250/logs/`，同一局；下面时间戳都是同一秒内的连续事件）：

```
[02:16:22.742] Khragg被杀死了
[02:16:22.768] [maidhurt] 大正女仆酒狐: 类型=genericKill 来源=无来源实体 伤害=1.02e38
[02:16:22.786] [CHAT] 大正女仆酒狐被杀死了
[02:16:22.811] [Khragg: 杀死了6个实体]
[02:16:22.822] [装备离开] 大正女仆酒狐 槽=MAINHAND 失去 1xminecraft:mace | 她背包[] 头=空 主手=空
[02:16:23.076] [冷却HUD] revive:大正女仆酒狐:60/60          ← 死亡 → 墓碑 → 待复活登记（60 秒）
[02:16:23.792] [离场] 大正女仆酒狐 离开世界（id 1631，reason=null）   ← 她那格区块掉出 entity-ticking
[02:16:27.547] [重同步] 大正女仆酒狐 补包跳过：她是新实体（id 1631→3949）  ← 区块重新加载，她从磁盘回来（新 id）
[02:17:22.688] WARN UUID of added entity already exists: EntityMaid['大正女仆酒狐'/5910, ...]  ← 第一次复活尝试，被拒
[02:17:22 … 02:18:17] 每 5 秒一次：墓碑「既不在实体表、也不在 TLM 登记表里」+ 同一行 WARN（id 5911…5922 每次 +1）
```

**定位（反编译实证，NeoForge 21.1.250 客户端 jar）**：

- `PersistentEntitySectionManager.addEntityUuid`：`knownUuids.add(uuid)` 返回 false 就 **warn「UUID of added entity already exists」并让整个 add 失败**（`addEntityWithoutEvent` 第一句就是它，返回 0）。⇒ 那 12 次复活**一次都没造出人来**，`resurrect()` 恒 false，旧版于是每 5 秒把 `dueTick` 推回 `now+100`，HUD 按 `ceil((dueTick-now)/20)` 就永远显示"剩 5 秒"。
- 谁占着这个 UUID：`Level.getEntity(UUID)`（= `LevelEntityGetterAdapter.get → EntityLookup`，只覆盖**正在被追踪**的实体）在复活那一刻**查不到任何人**——所以不是"她还活着"那么简单。而 `Callback.onRemove` 是**唯一**摘 UUID 的地方，它只在实体真正离场时跑。⇒ 结论：**有一具"死了却没被清掉"的死体，卡在一个"已加载但不 tick"的区块里**：她死的那一刻（02:16:22）玩家同一瞬也被 `/kill` 杀了，她那格区块随即掉出 entity-ticking（只剩 FULL，`reason=null` 的离场就是这个），于是原版的死体清理 `LivingEntity.tickDeath → remove(KILLED)` 永远跑不到（尸体不被 tick），那个 UUID 就一直挂在登记表上，而"可见实体索引"里又没有它。
- 九百二十四 的查重只认 `m_6084_()`（isAlive）——尸体不是"活的"，照样漏过；这就是开发者说"并没有成功复活"的原因。

**改法**（`MaidAutoResurrect`，两树镜像）：

- `findMaidByUuid` → `findMaidAnywhere(server, id, exclude, deathX, deathY, deathZ)`：**活的、死的、以及不在追踪表里的都要找到**。快路径仍是 `getEntity(UUID)`；再补一发 `getEntitiesOfClass(EntityMaid.class, box)`——它走的是 **AABB → `sectionStorage`** 那条链（`LevelEntityGetterAdapter.get(type, aabb, consumer)` 反编译实证），连"区块已加载但没被追踪"的实体也捞得到；盒子取她**死亡坐标**（`load(p.maidNbt)` 出来的 NBT 就带着 Pos，`setPos` 之前抓下来）附近 128 格，并扫全部维度（防她死在别的维度）。
- 在 `addFreshEntity` **之前**分流：
  - 找到的是**活的** → 她其实已经在世界里 = 这次本就是成功的 → 补血 + 净化 + **补一枪客户端重建（`scheduleForcedResync`）+ 脚下补票（`ensureTicketNow`）** → 摘掉登记（不重复造人）。
  - 找到的是**死的/已被移除的** → 那就是卡住的死体：当场 `discard()`（同步走 `Callback.onRemove` → UUID 立刻从登记表摘出）→ 日志留痕 → **本拍继续放人**，她这才真回来。
  - 还是被拒 → 打一行精确诊断（"既不在追踪表、也不在死亡点附近，多半在没加载的区块里"）→ 走既有封顶重试。
- 失败到上限（`MAX_RESURRECT_RETRIES = 12`，≈60 秒）的播报改为如实说清"她的 UUID 一直被占用 / 重进存档后再让她死一次通常就正常了"，不再让玩家去右键墓碑（碑可能早没了）。

### ② home 门禁回退（开发者点名）

**改法**（`MaidChunkLoadManager`，两树各 6 处）：撤销九百二十四 的 `&& !isPassenger()` / `&& !m_20159_()`——`summonOne` / `summonNamed` / `summonMaidTo` / `summonAll` / `tickPending`（`homeKept`）/ `queuePendingSummon`（`homeOnly`）全部恢复到"守家 = 钉死"。九百二十四 的另一半（「一键集合」对特殊载具 / 自坐模组载具走 `recallRideRider` 连人带载具）**保留不动**。

## 触及文件

- `promaid_src` / `promaid_src_neo` × `com/maidsmart/combat/MaidAutoResurrect.java`（① 查重口径 + 清死体 + 播报）
- `…/follow/MaidChunkLoadManager.java`（② home 门禁 ×6 处回退）
- `rounds/RELEASES.tsv`（两条 1.3.2 `workspace` 行 sha256 + 第六十四次同号重打）
- `rounds/R925-resurrect-stuck-corpse-and-home-gate-revert.md`（本文件）

## 验证

- **两树编译**：`compile_promaid.bat` → `EXITCODE=0`；`compile_neo.bat` → `EXITCODE=0`。
- **全护栏**：`check_comment_style` **PASS**（931 文件 / 0 处）；`check_arch` / `check_catch_swallow` / `check_comment_dup` / `check_dev_voice` / `check_i18n_keys` / `check_preset` / `check_reflection` **FAIL 0 / WARN 0**；`check_workflow` **FAIL 0 / WARN 3**（历史遗留：PUBLISH_GUIDE 版本、1.2.4 归档哈希、13 个更早 jar 未登记——与本轮无关）。
- **两个 jar**：forge **17583942** 字节（sha256 `8196EF03…23C828`）；neoforge **17620369** 字节（`B3E7C5FD…3AA2BF`）。
- **装机**：`deploy_all.py` → 4 个本机实例全部 **`match=True`**（1.21.1-NeoForge_21.1.250 / 1.20.1-Forge_47.4.10 / 龙之冒险：新征程v2.4a / mc_server_test pack1201）；其中 2 个 forge 实例走了提权复制（首次直拷 Permission denied），最终落地字节一致。
- **留痕**：`rounds/RELEASES.tsv` 两条 1.3.2 `workspace` 行 sha256 更新为新字节，并追加「第六十四次同号重打」；九百二十四 的旧字节 `598DE622…CDD622`（forge）/ `F01A21F1…DD41DE`（neo）作废。
- **未跑实机回归**（先停在这，等开发者指挥）。

## 验收

- 状态：**未验收**（已部署：本机 4 实例与 `patched/` 逐字节一致；未回归、未 commit、未 push）。
  - ① **自动复活真的回来**：让她死一次（最好在**没有别的玩家/区块被拆**的地方，例如站在家里）→ 倒计时走完 → 她应**当场出现在落点并可见**（HUD 那条 `revive:` 消失）。日志按情形应出现其中一条：`其实已经在世界里（同 UUID）`（她已被别的路径复活）/ `有一具死体卡住了她的 UUID……已清掉，本拍继续放人`（就是这次修的卡死体）/ 正常线 `已在主人重生点复活` 或 `已在主人所在位置强制复活`。
  - ② **复现老 bug 的判定**：若日志里仍出现 `造人被拒：同 UUID 还挂在这一维度的登记表上`，请把那几行连同前后 20 行贴给我——那是占位者所在区块都没加载出来（盒子也够不到）的极端情形，需要另想办法。
  - ③ **home 门禁回退**：让一只女仆开**在家模式**并骑/坐在载具上 → 排班表点「召她过来」/「一键集合」→ 应**被她顶回、纹丝不动**（"守家优先于骑乘例外"）；想召回先解除在家模式。「一键集合」对**不在守家**的特殊载具/自坐载具女仆仍是连人带载具。
  - 判定：逐条按上面现象观察即可；不通过就贴 `logs/promaid.log` + `logs/latest.log` 对应几行。

## 边界（没动什么）

- **没动**：矿表/数值/建筑/对话/战斗；墓碑右键的取消登记逻辑；死亡传送/自动救援的既有 home 豁免（只有排班表那两个**手动**入口的让位被撤销）；重建点解析与落点算法。
- **口径变更（有意，非回归）**：守家对"骑/坐在载具上"的她重新"钉死"（撤销九百二十四 的让位）——这正是开发者本轮点名的回退。
- **不新增开关、不改默认值**；版本号仍 **1.3.2**（`WORKFLOW.md` §5 "验收前不升号"）。
- 临时脚本（`tools/_r925_gate_revert.py`、`tools/_r925_comment_trim.py`）**已删**；仓库根目录既有的 `_*` 文件与预置目录未动。

## 台账

- `rounds/RELEASES.tsv`：两条 1.3.2 `workspace` 行更新 sha256 并列第六十四次同号重打。
- `DEBT.md`：本轮不新增条目——两件都是"把已有的静默失败/死循环改成正确行为"，不引入新的未验证功能面。
