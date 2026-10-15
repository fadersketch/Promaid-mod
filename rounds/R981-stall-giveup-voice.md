# R981 · 卡死脱身气泡补语音

## 排班

1. 开发者：「你现在去训练一下新文本语音，然后回答我一个问题。」→ 本回合只做「训练新文本语音」
   这一半；问题那一半另附（见文末「附带问题」）。
2. 全量清单先点一遍：`gen_voice_pack.py check` = 178 条目 / JP 179 行 / missing none，
   唯一没配音的就是上一轮新增的卡死脱身气泡「这块我够不到，先换个地方找找～」。
3. 登记（两树 manifest + JP 台词 + 为难档）→ 起 GPT-SoVITS 服务 → 合成 → 归一 → 装包 →
   重打包 → 部署 → 护栏 / 回归 → 文档台账。
4. 未改任何 Java、未增删任何配置项：本轮只换音频与包内一条索引。

## 需求 / 来源

- 原话：「你现在去训练一下新文本语音，然后回答我一个问题。」
- 上轮遗留：R979 的看门狗气泡只有文字，jar 内语音包（`assets/promaid/voice/manifest.json`）
  最后一条仍是 `wood_area_cleared.ogg`，没有这条。
- 训练素材现状：`gen_voice_pack.py` 的 JP 台词表此前 178 行（含一条历史遗留 `work_distracted.ogg`）。

## 改法

| 环节 | 动作 |
|---|---|
| 入口 | 两树 `assets/promaid/voice/manifest.json` 末尾追加 `{"text":"这块我够不到","file":"work_stall_giveup.ogg","mode":"contains"}`——`contains` 覆盖完整气泡（含尾巴「先换个地方找找～」与前面的频道前缀） |
| 档位 | `tools/voice/gen_voice_pack.py` 的 `STYLE_OF['plead']` 加 `work_stall_giveup`（为难档：REF_B 参考音、speed 1.02，与「够不着高处的矿」`mine_no_block_high` 同调） |
| 台词 | JP 表补 `'work_stall_giveup.ogg': 'ここは手が届かないの、ちょっと別の場所を探してくるね～'` |
| 合成 | GPT-SoVITS API（`127.0.0.1:9880`，权重 `小酒狐-e15.ckpt` / `小酒狐_e8_s184.pth`）→ `_voice_new2/work_stall_giveup.raw.wav` 358,444 B |
| 归一 | `norm_voice_pack.py`：峰值 −7.30 dB → 增益 +5.80 dB、目标 −1.5 dB、libvorbis q4 → `47,874 B` |
| 装包 | `install_voice_pack.py`：两树各 `copied=1`、`missing=none` |
| 打包 | Java 未动 → 不重编译，只重打两树 jar（`assets` 变化） |

## 触及文件

- 两树：`assets/promaid/voice/manifest.json`、`assets/promaid/voice/work_stall_giveup.ogg`（新增）。
- 工具：`tools/voice/gen_voice_pack.py`（STYLE_OF + JP 各一处）。
- 文档：`rounds/R981-stall-giveup-voice.md`、`rounds/RELEASES.tsv`、`DEV_GUIDE.md`、`机制详解.md`、
  `release_body.md` / `release_body_neo.md` / `RELEASE_NOTES_v1.3.2.md` / `store/release_notes_1.3.2.md`。

## 验证

- `gen_voice_pack.py check`：`entries: 178` / `JP lines: 179` / `missing JP: none` /
  `extra JP: ['work_distracted.ogg']`（历史既有，向来在列）/ `plead` 档 20 条。
- 两树 ogg 字节一致：`47874 B`，md5 `417ea60b…34d522`。
- 14 护栏全 `FAIL 0`（`check_tree_mirror` 为报告制）。
- 包体：forge **17,818,663** 字节 `144a210a…999c3a`；neo **17,866,501** 字节 `952ef734…9910e0`。
- 部署：`deploy_all.py` 4 个目标全 `match=True`（`elevated rc: 0`）。
- 回归：`test_server.py` / `test_maid_load.py` 两加载器各两件，四件全 `verdict: PASS`。

## 验收

- 她撞上挖不动的方块、八秒后脱身时，那句「这块我够不到，先换个地方找找～」会被念出来，
  不再只有文字气泡。

## 边界

- 只加音频 + 包内一条索引：不改任何判据、配置项、面板行；不放这条 ogg 的老 jar 行为一字不变。
- 包内匹配是 `contains` 且**自上而下取第一条命中**：新条目排在末尾，不会抢在更具体的旧条目前面；
  当前也没有别的气泡包含「这块我够不到」这句。
- 语音来源优先级不变：jar 内包（本包）→ 磁盘包 `config/maid_smart/system_voice/` → TTS 缓存 → 现场合成。
  玩家自己那份磁盘包照旧只在 jar 包没命中时生效。

## 台账

- `rounds/RELEASES.tsv` 两条 1.3.2 行 **第一百一十七次** 同号重打；旧字节
  `d3ad00ba…33fe84`（forge）/ `8512ff0b…a821b2`（neo）（第一百一十六次 R980）作废。
- `DEV_GUIDE.md` 发布区间 → 八百五十九 ~ 九百八十一。
- 四份发布文案各追加一条（「够不到」那句提醒有声了）。
- `机制详解.md` §十九 语音：`SystemVoicePack` 条目数说明补 177 → 178。

## 附带问题（本回合第二件事）

开发者另问：**不在挖矿时**被不可破坏的方块挡住、又绕不开，女仆最终怎么处理；伐木模式呢？
—— 结论与代码出处见当次回答（`MaidWorkBehavior.stallWatchdog` + `MaidMineBehavior` /
`MaidWoodBehavior` 的挡路块与弃置链）。
