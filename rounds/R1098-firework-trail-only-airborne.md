# R1098 尾迹只在离地后发（对齐远程空袭）

## 排班

- **跑道**：C 功能（两树镜像）。
- **开工前读过**：`rounds/R1096`、`R1097`；`MaidVirtualFirework`、`MaidFlightCombatBehavior`
  （`jumpForLaunch` / `tryLaunch` / `LAUNCH_LEFT` / `tickRangedAir`）、`SpearAirRaidBehavior`；
  实机日志 `logs/promaid.log`（"通用空袭已点火，等待 10 tick（0.5 秒）后切换幻翼态"）。
- **工作队列**：

| # | 步 | 完成判据 | 状态 |
|---|---|---|---|
| 0 | 确认"粒子比飞行先出现"的时点 | 起飞有 10 tick 等待段，期间人还站在起跳点 | ☑ |
| 1 | 尾迹改为离地后才发 | `onGround()` 时不发粒子，只算推力 | ☑ |
| 2 | 两树镜像 | Forge `m_20096_()` 同判据 | ☑ |
| 3 | 编译与护栏 | 两树编译 `EXITCODE=0`；14 护栏 `FAIL 0` | ☑ |
| 4 | 测试与部署 | 四套专服测试 PASS；双 jar；4 实例 `match=True` | ☑ |

- **谁验收 / 怎么验**：验收人 = 开发者。验法见文末「验收」。

## 需求 / 来源

1. 改用远程空袭同款即可；近战/长矛起飞有前置（10 tick 等待），粒子位置比女仆飞行先出现。

## 评估

近战与长矛起飞走 `jumpForLaunch`：先置滑翔位、点火，然后**原地等 10 tick**（日志原文
"通用空袭已点火，等待 10 tick（0.5 秒）后切换幻翼态"）才真正进入幻翼态。这段"前置"里她已经
被算作滑翔，`MaidVirtualFirework.tick` 的尾迹照发——人还站在起跳点，粒子先冒出来，看起来就是
"粒子比女仆先飞"。远程空袭从不落地起跳（`tickRangedAir` 只在空中补推），所以它没有这一段。

## 改动

- `MaidVirtualFirework.tick`（两树）：尾迹增加 `!onGround()` 门。推力递推与窗口计时不变，
  只是她脚踩实地的那几拍不画粒子；离地后立刻恢复每 tick 1 粒。

## 验证

- Neo 编译：`NEO_OK`；Forge 编译：`FORGE_OK`。
- 14 项护栏：`FAIL 0 / WARN 0`。
- 专服回归：`test_server.py neoforge1211` PASS、`test_maid_load.py neoforge1211` PASS、
  `test_server.py 1201` PASS、`test_maid_load.py 1201` PASS。
- Neo jar：18,210,255 字节，SHA256
  `5183C84ACEBD78F0B84FD4D4ED27052C973A26489050AF517A7272C48062131E`。
- Forge jar：18,148,964 字节，SHA256
  `2729F46D77BB0D1A272CEA0DB0034DDBDAF6D83950A80BBC6F4E0E418E53B310`。
- 已部署 4 实例，源包/目标包字节数一致（`match=True`）。

## 边界

- 只拦"脚在实地上"的粒子；离地（含贴地起跳的第一拍之后）照常发。
- 推力、窗口时长、燃料消耗、冷却与上一轮完全一致。
- 远程空袭、飞行跟随本来就只在空中补推，表现不变。

## 验收

1. **近战/重锤起飞**：10 tick 等待期间起跳点不再冒粒子；离地那一刻尾迹才接上。
2. **长矛起飞**：同上；"冲锋前补一记"仍是同一条细流。
3. **远程空袭**：与改动前一致。
