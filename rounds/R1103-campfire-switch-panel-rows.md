# R1103 营火烹饪/熔炉等价族两个开关进面板显式行（修复「找不到配置项」）

## 排班

- **跑道**：C 功能（两树镜像）。
- **开工前读过**：`PromaidConfigScreen` 烹饪区（「兼容高炉/烟熏炉」行位置）、
  `gen_panel_coverage.py` / `check_panel_coverage.py`；两个实例的
  `config/promaid-common.toml`（实证新键已落盘、均为默认 false）。
- **工作队列**：

| # | 步 | 完成判据 | 状态 |
|---|---|---|---|
| 0 | 确认 jar 已到实例、键已落盘 | 两实例 toml 均有 cookFurnaceFamily/cookCampfire = false | ☑ |
| 1 | 两个开关加进「烹饪与酿造」显式行 | 紧跟「兼容高炉/烟熏炉」之后，两树同款文案 | ☑ |
| 2 | 面板覆盖重生成 | gen_panel_coverage --write 后 check FAIL 0 | ☑ |
| 3 | 编译/护栏 | 两树 OK；14 护栏 FAIL 0 | ☑ |
| 4 | 测试与部署 | 四套 PASS；4 实例 match=True | ☑ |

- **谁验收 / 怎么验**：验收人 = 开发者。验法见文末「验收」。

## 需求 / 来源

1. "女仆还是不会用营火烧肉，我也没看到配置项目。"

## 评估

R1101 把两个新开关做成了默认关的 toml 键，但没有加面板**显式行**——它们只出现在
「其余开关（自动）」页里，玩家在烹饪设置页里看不到；默认又是关的，于是表现为
"营火不工作 + 找不到配置项"。检查两个实例的 `promaid-common.toml` 证实：jar 已更新
到位、键已落盘（`cookFurnaceFamily = false` / `cookCampfire = false`）——功能没生效
就是因为没开启，而开关藏得太深。

## 改动（两树镜像）

- `PromaidConfigScreen` 烹饪区（「兼容高炉/烟熏炉」行之后）新增两个显式行：
  **熔炉等价族**、**营火烹饪**，描述写明默认关、生效条件与边界（营火：生食材在她
  背包/额外容器 + 烧制搜索范围内的工作区里有点着的营火/灵魂营火；绝不站火格）。
- 重跑 `gen_panel_coverage.py --write`（covered 字段 616/626）。

## 验证

- Neo 编译 `NEO_OK`；Forge 编译 `FORGE_OK`；14 护栏 `FAIL 0 / WARN 0`。
- 四套专服回归 PASS（1201 / neoforge1211 × server / maid_load）。
- Neo jar：18,221,843 字节，SHA256
  `76531AB9B8020455E01A0B52BCB32A9E3C329FB5831DF9B7BD89C2E25C45B15F`。
- Forge jar：18,160,687 字节，SHA256
  `2F1520A62008646FB486C6D31FC8D098AF6B84454BA09BF31856E8911B5F13CE`。
- 已部署 4 实例，`match=True`。

## 边界

- 默认仍为关（新开关惯例，OFF_ACK 已登记）；开启入口即本次新增的面板行，
  或手改 toml `misc.cookCampfire = true`。
- 营火烹饪生效条件不变：烧制任务 + 她身上有营火配方食材 + 工作区内点着的营火。

## 验收

1. 配置面板「生产与工作 → 烹饪与酿造」能看到「熔炉等价族」「营火烹饪」两行，
   点开后营火链路生效。
2. 开启后：她背包放生肉、工作区放点着的营火 → 她走到火旁邻格把肉摆上、
   烤好自动捡回；全程不站火格。
