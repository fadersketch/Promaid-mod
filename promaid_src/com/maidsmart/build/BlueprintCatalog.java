package com.maidsmart.build;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.ItemHandlerHelper;
import net.minecraftforge.registries.ForgeRegistries;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 蓝图目录与描述文本（v1.2.4 从 BlueprintLib 拆出）。
 * 
 * 手册/工具看到的目录条目、名字、材料需求描述与 JSON 蓝图解析。
 */
public final class BlueprintCatalog {
    private BlueprintCatalog() {
    }

    public static List<String> getBuiltIn(String id) {
        return com.maidsmart.build.BuiltinHouses.get(id);
    }

    public static String buildCatalog() {
        StringBuilder sb = new StringBuilder();
        BlueprintFileIo.scanExternalBlueprints();
        if (!BlueprintFileIo.EXTERNAL.isEmpty()) {
            sb.append("外部蓝图（config/maid_smart/blueprints 或 存档 schematics/，支持 .json/.nbt/.snbt/.litematic/.schem）：\n");
            for (Map.Entry<String, List<String>> entry : BlueprintFileIo.EXTERNAL.entrySet()) {
                sb.append(entry.getKey()).append(" — ")
                        .append(BlueprintFileIo.EXTERNAL_NAMES.getOrDefault(entry.getKey(), entry.getKey()))
                        .append("（").append(describe(entry.getKey(), entry.getValue())).append("）\n");
            }
        } else {
            sb.append("（当前没有蓝图——把 .nbt/.snbt/.schem 等蓝图文件放进 config/maid_smart/blueprints 即可）");
        }
        return sb.toString();
    }

    static final Map<String, String> BUILT_IN_NAMES = new HashMap<>();

    static {
        // v1.5.387：清空——不再注册任何内置预设（原 25 个：3 小屋 + 12 结构 + 3 别墅 +
        // 熔炉 + 6 农场）。手册"建造"目录只显示外部蓝图（玩家导入 + LLM/AI 生成）。
    }

    static final Map<String, String> EXT_CN_NAMES = new HashMap<>();

    static {
        EXT_CN_NAMES.put("ancient_tree", "远古巨树");
        EXT_CN_NAMES.put("barn", "谷仓");
        EXT_CN_NAMES.put("castle_keep", "城堡主楼");
        EXT_CN_NAMES.put("chapel", "礼拜堂");
        EXT_CN_NAMES.put("chinese_courtyard", "中式庭院");
        EXT_CN_NAMES.put("cliff_camp", "悬崖营地");
        EXT_CN_NAMES.put("colossal_knight", "巨型骑士雕像");
        EXT_CN_NAMES.put("command_center", "指挥中心");
        EXT_CN_NAMES.put("cottage", "乡间小屋");
        EXT_CN_NAMES.put("crystal_palace", "水晶宫殿");
        EXT_CN_NAMES.put("data_center", "数据中心");
        EXT_CN_NAMES.put("desert_hut", "沙漠小屋");
        EXT_CN_NAMES.put("enchanted_manor", "魔法庄园");
        EXT_CN_NAMES.put("farm_cabin", "农舍");
        EXT_CN_NAMES.put("fisherman_hut", "渔夫小屋");
        EXT_CN_NAMES.put("forge_shed", "锻造工棚");
        EXT_CN_NAMES.put("gatehouse", "门楼");
        EXT_CN_NAMES.put("giant_cat_statue", "巨型猫咪雕像");
        EXT_CN_NAMES.put("golden_temple", "黄金神庙");
        EXT_CN_NAMES.put("gothic_chapel", "哥特教堂");
        EXT_CN_NAMES.put("high_tech_lab", "高科技实验室");
        EXT_CN_NAMES.put("jade_garden_house", "翡翠花园宅邸");
        EXT_CN_NAMES.put("lighthouse", "灯塔");
        EXT_CN_NAMES.put("lumberjack_cabin", "伐木小屋");
        EXT_CN_NAMES.put("luxury_villa", "豪华别墅");
        EXT_CN_NAMES.put("marble_villa", "大理石别墅");
        EXT_CN_NAMES.put("miner_shack", "矿工棚屋");
        EXT_CN_NAMES.put("modern_villa", "现代别墅");
        EXT_CN_NAMES.put("nomad_tent", "游牧帐篷");
        EXT_CN_NAMES.put("observatory", "天文台");
        EXT_CN_NAMES.put("oriental_dragon", "东方巨龙");
        EXT_CN_NAMES.put("oriental_palace", "东方宫殿");
        EXT_CN_NAMES.put("reactor_core", "反应堆核心");
        EXT_CN_NAMES.put("renaissance_mansion", "文艺复兴府邸");
        EXT_CN_NAMES.put("root_cellar", "酒窖");
        EXT_CN_NAMES.put("round_tower", "圆塔");
        EXT_CN_NAMES.put("royal_palace", "皇家宫殿");
        EXT_CN_NAMES.put("sci_fi_bunker", "科幻地堡");
        EXT_CN_NAMES.put("seaside_house", "海景小屋");
        EXT_CN_NAMES.put("snow_cabin", "雪屋");
        EXT_CN_NAMES.put("solar_energy_tower", "太阳能塔");
        EXT_CN_NAMES.put("space_rocket", "太空火箭");
        EXT_CN_NAMES.put("survival_hut", "生存小屋");
        EXT_CN_NAMES.put("teleporter_hub", "传送枢纽");
        EXT_CN_NAMES.put("treehouse", "树屋");
        EXT_CN_NAMES.put("watchtower", "瞭望塔");
        EXT_CN_NAMES.put("wizard_tower", "巫师塔");
        // v1.5.30：预制大型蓝图（内置资源，首次启动自动复制到 blueprints 目录）
        EXT_CN_NAMES.put("high_tech_villa", "高科技别墅");
        EXT_CN_NAMES.put("seaside_villa", "海景别墅");
        EXT_CN_NAMES.put("grand_palace", "大型宫殿");
        EXT_CN_NAMES.put("skyscraper", "摩天大楼");
        // v1.5.31：大型狸花猫雕像
        EXT_CN_NAMES.put("tabby_cat_statue", "大型狸花猫雕像");
        // v1.5.34：超大规模预制
        EXT_CN_NAMES.put("mega_castle", "巨型城堡");
        EXT_CN_NAMES.put("mega_pyramid", "巨型金字塔");
        EXT_CN_NAMES.put("mega_colosseum", "巨型竞技场");
        // v1.5.35：巨型骑士雕像
        EXT_CN_NAMES.put("mega_knight_statue", "巨型骑士雕像");
        // v1.5.38：生存实用房（材料简单/建造快速/多层隐蔽/光照/机动性）
        EXT_CN_NAMES.put("survival_woodcabin", "林间隐舍");
        EXT_CN_NAMES.put("survival_bunker", "地下避难所");
        EXT_CN_NAMES.put("survival_watchtower", "哨塔居");
        // v1.5.39：PM 下载的现代红石智能住宅（547686 块，上限提升后入库）
        EXT_CN_NAMES.put("modernredstonesmarthouse8649399", "现代红石智能住宅");
        // v1.5.252k：挖空版已删除——挖空设计导致内部大量附着物悬空，触发强制补支撑
        // 垫的支撑块把下部红石设施卡死（实测），仅保留原版
    }

    public static String getBlueprintName(String id) {
        String name = BUILT_IN_NAMES.get(id);
        if (name != null) {
            return name;
        }
        BlueprintFileIo.scanExternalBlueprints();
        return BlueprintFileIo.EXTERNAL_NAMES.getOrDefault(id, id);
    }

    public static List<String[]> buildCatalogEntries() {
        List<String[]> out = new ArrayList<>();
        // v1.5.373：玩家导入的地图放【第 1 页】（尊重玩家感受——自己导入的内容优先展示，
        // 旧版内置预设占前几页、导入的地图排在最后）
        BlueprintFileIo.scanExternalBlueprints();
        int externalCount = 0;
        List<String> externalIds = new ArrayList<>(BlueprintFileIo.EXTERNAL.keySet());
        externalIds.sort(String::compareTo);
        for (String id : externalIds) {
            List<String> steps = BlueprintFileIo.EXTERNAL.get(id);
            if (steps == null || steps.isEmpty()) {
                continue;
            }
            // v1.5.25g：手册目录只显示 ≤catalogMaxBlocks() 的蓝图（防右击卡死）；
            // 超大的仍注册在 EXTERNAL，LLM smart_build 可直接下达（不受此限）
            if (steps.size() > BlueprintLib.catalogMaxBlocks()) {
                continue;
            }
            out.add(new String[]{id, getBlueprintName(id), describe(id, steps)});
            externalCount++;
        }
        int builtInCount = 0;
        for (String id : BUILT_IN_NAMES.keySet()) {
            List<String> steps = getBuiltIn(id);
            if (steps != null && !steps.isEmpty()) {
                out.add(new String[]{id, getBlueprintName(id), describe(id, steps)});
                builtInCount++;
            }
        }
        // v1.5.25 诊断：内置/外部各多少（LogUtils → 进 latest.log）
        BlueprintLib.LOGGER.info("buildCatalogEntries: 外部 {} 个, 内置 {} 个, 合计 {} 个", externalCount,
                builtInCount, out.size());
        return out;
    }

    public static Map<String[], Map<String, int[]>> buildCatalogEntriesWithMaterials(Player player) {
        Map<String[], Map<String, int[]>> out = new java.util.LinkedHashMap<>();
        List<String[]> entries = buildCatalogEntries();
        // v1.5.221：已建统计（活跃区块计划 placedSet）+ 绑定女仆背包统计
        // v1.5.278：已建按【蓝图 id 隔离】——旧版把所有计划的 placedSet 混在一个
        // Map，不同蓝图共用材料时（石质山景别墅 + 现代红石智能住宅都用石头/木板/石砖），
        // 其他计划的已建会把本蓝图"剩余需求"扣成 0 → 材料表只剩 3 种未污染的
        //（截图实证：圆石墙/蓝色床/火把，其余全显示"已建完"）
        java.util.Map<String, java.util.Map<String, Integer>> builtByBlueprint = new java.util.HashMap<>();
        java.util.Map<String, Integer> maidHaveMap = new java.util.HashMap<>();
        if (player.m_9236_() instanceof net.minecraft.server.level.ServerLevel sl) {
            for (BuildPlan.PlanState ps : BuildPlan.getPlans(sl)) {
                BuildPlan.Progress prog = BuildPlan.progress(ps);
                if (prog == null) {
                    continue;
                }
                List<String> plan = ps.steps;
                java.util.Map<String, Integer> builtCur = builtByBlueprint.computeIfAbsent(
                        ps.blueprintId, k -> new java.util.HashMap<>());
                for (int i = 1; i < plan.size(); i++) {
                    String[] parts = BlueprintStepMath.parseStep(plan.get(i));
                    if (parts == null) {
                        continue;
                    }
                    try {
                        long key = (long) (Integer.parseInt(parts[0]) & 0xFFFFF) << 42
                                | (long) (Integer.parseInt(parts[1]) & 0x1FFFFF) << 21
                                | (Integer.parseInt(parts[2]) & 0x1FFFFF);
                        if (prog.placedSet.contains(key)) {
                            builtCur.merge(parts[3], 1, Integer::sum); // 已累计搭建
                        }
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
            // 绑定过区块计划的女仆背包（本维度）
            // 实测五百六十四（PR #9 移植）：改为 getAllEntities() 遍历本维度全部实体
            // ——旧版的原点高度盒扫不到远处的女仆，Sable 也不会拒查它
            // v1.1.0 实测三百三十：EntityMaid.class 全图扫描改用 Entity.class 全量 +
            // instanceof 过滤——ClassInstanceMultiMap 桶 bug（同 FarmTillDriver）
            for (net.minecraft.world.entity.Entity e : com.maidsmart.tool.EntitySnapshot.of(sl)) {
                if (!(e instanceof EntityMaid m) || BuildPlan.getBoundPlanId(m) == null) {
                    continue;
                }
                net.minecraftforge.items.IItemHandler inv = m.getAvailableBackpackInv();
                for (int i = 0; i < inv.getSlots(); i++) {
                    ItemStack s = inv.getStackInSlot(i);
                    if (s.m_41619_()) {
                        continue;
                    }
                    net.minecraft.resources.ResourceLocation key =
                            net.minecraftforge.registries.ForgeRegistries.ITEMS.getKey(s.m_41720_());
                    if (key != null) {
                        maidHaveMap.merge(key.toString(), s.m_41613_(), Integer::sum);
                    }
                }
            }
        }
        for (String[] e : entries) {
            String id = e[0];
            List<String> steps = BlueprintStepMath.getBlueprint(id);
            if (steps == null || steps.isEmpty()) {
                continue;
            }
            // 统计总需求（等价族合并到"主方块"上；v1.5.25d：带缓存，避免每次右击
            // 手册都对 59 个蓝图重新遍历上万步骤 → 服务端主线程卡顿）
            Map<String, Integer> need = BlueprintMaterials.countNeedsCached(id, steps);
            Map<String, int[]> mats = new java.util.LinkedHashMap<>();
            java.util.Map<String, Integer> builtMap = builtByBlueprint.getOrDefault(id,
                    java.util.Collections.emptyMap());
            for (Map.Entry<String, Integer> entry : need.entrySet()) {
                int built = builtMap.getOrDefault(entry.getKey(), 0);           // 已搭建
                int maidHave = maidHaveMap.getOrDefault(entry.getKey(), 0);     // 绑定女仆背包
                int remaining = Math.max(0, entry.getValue() - built);          // 剩余需求
                // v1.5.252p：创造模式"已有"直接=剩余需求（显示 X/X 齐）——旧版
                // built + MAX_VALUE 溢出成 -2147483648（-21 亿,实测）
                int have = BlueprintMaterials.isCreative(player)
                        ? remaining
                        : built + maidHave + BlueprintMaterials.countPlayerMaterial(player, entry.getKey()); // 已建+女仆+玩家
                mats.put(entry.getKey(), new int[]{have, remaining});
            }
            // v1.5.318：液体工具/材料需求（水桶/岩浆桶）补进材料表——水/岩浆被
            // countNeeds 排除（FORBIDDEN），但玩家需要知道要备桶：水=1 水桶作工具、
            // 岩浆=N 岩浆桶（放置后返还空桶）
            Map<String, Integer> fluidNeed = BlueprintMaterials.fluidBucketNeeds(steps);
            for (Map.Entry<String, Integer> fe : fluidNeed.entrySet()) {
                int remaining = fe.getValue();
                int have = BlueprintMaterials.isCreative(player)
                        ? remaining
                        : maidHaveMap.getOrDefault(fe.getKey(), 0)
                                + BlueprintMaterials.countPlayerMaterial(player, fe.getKey());
                mats.put(fe.getKey(), new int[]{have, remaining});
            }
            out.put(e, mats);
        }
        return out;
    }

    static final Map<String, String> DESCRIBE_CACHE = new HashMap<>();

    public static String describe(String id, List<String> steps) {
        if (id != null) {
            String cached = DESCRIBE_CACHE.get(id);
            if (cached != null) {
                return cached;
            }
        }
        if (steps == null || steps.isEmpty()) {
            return "空蓝图";
        }
        StringBuilder sb = new StringBuilder();
        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
        int minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
        for (String step : steps) {
            String[] parts = BlueprintStepMath.parseStep(step);
            if (parts == null) {
                continue;
            }
            int x = Integer.parseInt(parts[0]);
            int y = Integer.parseInt(parts[1]);
            int z = Integer.parseInt(parts[2]);
            minX = Math.min(minX, x);
            maxX = Math.max(maxX, x);
            minY = Math.min(minY, y);
            maxY = Math.max(maxY, y);
            minZ = Math.min(minZ, z);
            maxZ = Math.max(maxZ, z);
        }
        if (minX <= maxX) {
            sb.append(maxX - minX + 1).append('x').append(maxY - minY + 1).append('x').append(maxZ - minZ + 1);
            // v1.5.278：块数用【去重后】计数——与下达建造的 plan 一致（旧版用原始
            // steps.size()，生成器/转换器的同坐标重复步骤导致"共2138块" vs 进度
            // "2024块"不一致，截图实证 114 块差额）
            int n = 0;
            for (String s : BlueprintStepMath.dedupeSteps(steps)) {
                if (BlueprintStepMath.parseStep(s) != null) {
                    n++;
                }
            }
            sb.append("，共 ").append(n).append(" 块");
        } else {
            sb.append("共 ").append(steps.size()).append(" 块");
        }
        // 材料统计 top5
        Map<String, Integer> counts = new HashMap<>();
        for (String step : steps) {
            String[] parts = BlueprintStepMath.parseStep(step);
            if (parts != null) {
                counts.merge(parts[3], 1, Integer::sum);
            }
        }
        List<Map.Entry<String, Integer>> sorted = new ArrayList<>(counts.entrySet());
        sorted.sort((a, b) -> b.getValue() - a.getValue());
        if (!sorted.isEmpty()) {
            sb.append("，材料：");
            int shown = Math.min(5, sorted.size());
            for (int i = 0; i < shown; i++) {
                if (i > 0) {
                    sb.append('、');
                }
                Map.Entry<String, Integer> e = sorted.get(i);
                sb.append(BlueprintNames.cnName(e.getKey())).append('x').append(e.getValue());
            }
            if (sorted.size() > shown) {
                sb.append(" 等 ").append(sorted.size()).append(" 种");
            }
        }
        // v1.5.318：液体工具/材料需求（水桶/岩浆桶）单独提示——水/岩浆不在普通
        // 材料统计（FORBIDDEN 排除），但玩家需要知道要备桶
        String fluidText = BlueprintMaterials.fluidNeedText(steps);
        if (!fluidText.isEmpty()) {
            sb.append('，').append(fluidText);
        }
        String result = sb.toString();
        if (id != null) {
            DESCRIBE_CACHE.put(id, result); // v1.5.25h：缓存，右击手册不再重算
        }
        return result;
    }

    private static String getJsonString(JsonObject obj, String key) {
        try {
            JsonElement el = obj.get(key);
            if (el != null && el.isJsonPrimitive() && el.getAsJsonPrimitive().isString()
                    && !el.getAsString().isEmpty()) {
                return el.getAsString();
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    public static List<String> parseJson(String json) {
        try {
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            JsonArray blocks = root.getAsJsonArray("blocks");
            // v1.5.197：块数硬上限从 maxBlocks()（200）放宽到 structureMaxBlocks()（100 万）——
            // 自绘蓝图（smart_design 子 Agent）大小无限制，落盘后扫描注册也走 parseJson；
            // 尺寸上限由调用方各自校验（smart_build 现场 JSON 仍走 validate() 限 200，
            // smart_design 走 looseValidate() 限 designMaxBlocks）。此处只做空/物理上限兜底。
            if (blocks == null || blocks.size() == 0 || blocks.size() > BlueprintLib.structureMaxBlocks()) {
                return null;
            }
            List<String> steps = new ArrayList<>();
            for (JsonElement element : blocks) {
                JsonObject obj = element.getAsJsonObject();
                int x = obj.get("x").getAsInt();
                int y = obj.get("y").getAsInt();
                int z = obj.get("z").getAsInt();
                String block = obj.get("block").getAsString();
                if (Math.abs(x) > BlueprintLib.maxRange() || Math.abs(z) > BlueprintLib.maxRange() || y < 0 || y > BlueprintLib.maxHeight()) {
                    return null;
                }
                if (!BlueprintBlockData.WHITELIST.contains(block)) {
                    return null;
                }
                // v1.5.311：JSON 蓝图支持可选 state / nbt（SNBT 字符串）——LLM 可以
                // 指定门/楼梯/活塞等定向方块的真实朝向与方块实体数据（旧版只有 id，
                // 一律默认态：门朝北开不了、中继器朝向固定、漏斗方向全错）。
                // 步骤格式不变：x,y,z,blockid|stateSnbt|beSnbt（state 缺省留空段）。
                String state = getJsonString(obj, "state");
                String nbt = getJsonString(obj, "nbt");
                StringBuilder step = new StringBuilder(x + "," + y + "," + z + "," + block);
                if (state != null) {
                    step.append('|').append(state);
                    if (nbt != null) {
                        step.append('|').append(nbt);
                    }
                } else if (nbt != null) {
                    step.append("||").append(nbt);
                }
                steps.add(step.toString());
            }
            return steps;
        } catch (Exception e) {
            return null;
        }
    }

    public static String parseJsonName(String json) {
        try {
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            JsonElement name = root.get("name");
            if (name != null && !name.getAsString().isEmpty()) {
                return name.getAsString();
            }
        } catch (Exception ignored) {
        }
        return null;
    }
}
