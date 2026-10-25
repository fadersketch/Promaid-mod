# R1047 女仆搭方块的金色描边与方块同生死（两树同步）

## 排班（开工前先填，硬门禁 —— `WORKFLOW.md` §1）

- **跑道**：A 修 bug（1.21.1 NeoForge + 1.20.1 Forge 同步；不改玩法，只改描边生命周期）。
- **开工前读过**：`rounds/R1014`、两树 `PlacedMarkNetworking` / `PlacedMarkClient` /
  `PlacedBlockTracker`、最新实机描边日志。
- **工作队列**：

| # | 步 | 完成判据 | 状态 |
|---|---|---|---|
| 0 | 定位 | 1.20.1 仍是 `OUTLINE_TICKS = 60`（3 秒视觉 TTL），客户端完全不查方块；Neo 客户端只认空气，且看到空气就永久删条目（预测/回滚后框再也回不来），也不认"被替换成别的方块" | ☑ |
| 1 | 服务端随包下发预期方块 id | `PlacedBlockTracker.track` 把登记时的注册名交给 `PlacedMarkNetworking`；增删包 `addIds` 与 `add` 等长（协议 v2） | ☑ |
| 2 | 服务端不再按 TTL 撤框 | Forge 的 `OUTLINE_TICKS` 改为 `OUTLINE_ALIVE = Long.MAX_VALUE`；两树每 10 tick 对账"当前方块 id == 登记 id" | ☑ |
| 3 | 客户端逐帧硬绑定 | 每帧比对空气 + 方块 id；不一致只**跳过绘制**，不删条目；删除只由服务端撤框包触发 | ☑ |
| 4 | 两树编译 | Forge / Neo 均 `EXITCODE=0` | ☑ |
| 5 | 护栏与专服回归 | 14 护栏 `FAIL 0 / WARN 0`；`test_server` / `test_maid_load` 两套均 PASS | ☑ |
| 6 | 部署 | 4 个实例逐处核对字节数一致 | ☑ |

## 需求 / 来源

- 开发者原话：「女仆搭出来的方块的黄色边框渲染……他跟搭建的方块是同生死共存亡的。被渲染出来以后，
  只要那个方块一消失。就立刻消失。否则不消失。」

## 根因

- **1.20.1 Forge 树漏镜像**：仍是 `OUTLINE_TICKS = 60` 的视觉 TTL，3 秒后无条件撤框；
  客户端 `PlacedMarkClient` 只在收到服务端增删包时改表，从不检查方块是否还在。
  于是：方块还在 → 框先没了；方块被拆 → 框可能留到下一次服务端消息。
- **Neo 树部分正确但不完整**：`tick` 里已有每 10 tick 对账、客户端每帧也查空气，但：
  1) 客户端只认空气，方块被换成别的方块（水、雪、蓝图目标块等）时仍继续画；
  2) 客户端看到空气就 `it.remove()` 永久删条目——如果那一帧是客户端预测/回滚，方块随后还在，
     服务端又不会重发 add，框就永久消失（违反"否则不消失"）。

## 修法

- **服务端是唯一生命周期来源**：
  - 登记时记录"预期方块注册名"，`MarksPacket` 的 `addIds` 与 `add` 一一对应；
  - 不再按视觉 TTL 撤框（Forge 与 Neo 统一 `OUTLINE_ALIVE = Long.MAX_VALUE`）；
  - 每 10 tick 对账：当前方块为空气或 id 与登记不符 → `untrackAny` + 撤框；
  - 反向补齐：仍登记但没有描边的补一帧（保留）。
- **客户端逐帧判定，只隐藏不删条目**：
  - 区块已加载时，空气或 id 不符 → 本帧不画，但保留位置表；
  - 区块未加载 → 无法判断，先照画（避免"方块还在、框却没了"）；
  - 真正删除只由服务端 `remove` 包触发；因此预测帧造成的瞬时不一致不会永久抹掉框。
- **协议版本**：Forge `PROTOCOL_VERSION` 与 Neo payload registrar 均升到 `2`（`addIds` 是新增字段，
  旧客户端必须一起更新）。

## 改动文件

- `promaid_src_neo/com/maidsmart/client/PlacedMarkClient.java`
- `promaid_src_neo/com/maidsmart/task/PlacedMarkNetworking.java`
- `promaid_src_neo/com/maidsmart/task/PlacedBlockTracker.java`
- `promaid_src/com/maidsmart/client/PlacedMarkClient.java`
- `promaid_src/com/maidsmart/task/PlacedMarkNetworking.java`
- `promaid_src/com/maidsmart/task/PlacedBlockTracker.java`

架构模块登记沿用既有 `promaid.work.placed-block` 条目（`PlacedMarkClient` 在条目里以 `None` 结尾，
不绑死行号），本轮未新增模块。

## 验证

- 两树编译：Forge / Neo 均 `EXITCODE=0`。
- 全部护栏：`FAIL 0 / WARN 0`。
- 专服：`test_server.py 1201` PASS、`test_maid_load.py 1201` PASS；
  `test_server.py neoforge1211` PASS、`test_maid_load.py neoforge1211` PASS（spearcore 临时挂载后移除）。
- 包体：
  - Forge 18,059,171 字节，SHA256
    `8062172BCDC3DDC4C8D321F9E8F75C7EC133C205C6A6B0AA3108C0EB6DF3B5D3`；
  - Neo 18,151,764 字节，SHA256
    `B4A1C2181388301CC0DA9C206147C6F356D9D311A665A74616F885EBB86FD28C`。
- 已部署 4 个实例（1.21.1、1.20.1 测试、龙之冒险主整合包、server pack1201），逐处 `match=True`。
- 已发 GitHub 预发布 `v1.3.3-beta`（两个附件下载回读哈希一致）。

## 验收

1. 女仆搭一块方块，金框出现后**一直存在**，不再 3 秒自动消失。
2. 把该方块拆掉（自己拆/爆炸/活塞/别的模组都算），金框在**当帧**消失，不等服务端包。
3. 把该方块换成另一种方块（放水、雪、蓝图目标块等），金框同样当帧消失。
4. 伺服端对账日志不再出现「对账撤框」把还在的方块撤掉；客户端日志出现
   「客户端跳过已消失/已替换的描边 N 格（等待服务端撤框）」时，画面应当已经看不到框。
5. 退出重进 / 换维度后，仍存在的女仆搭方块金框照常恢复。

## 边界

- 区块未加载时无法判断方块是否存在，客户端先照画；服务端撤框包到达后移除。
- 金框只服务"女仆搭方块追踪表"（搭路/垫脚/挖矿垫块/战斗垫块），蓝图与指标石预览不走这条。
- 版本号仍为 1.3.3（同号重打）。
