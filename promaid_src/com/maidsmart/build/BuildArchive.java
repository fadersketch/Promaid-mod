package com.maidsmart.build;

/**
 * 建造档案（v1.5.43）：世界级"女仆工作记忆"——按维度持久化建造上下文。
 * 解决"重启后大部分女仆失联"（之前计划只写内存 GLOBAL_PLAN + 最近一只女仆的
 * TaskData，重启后其余女仆全部站桩）：
 * - 档案存 {origin xyz, blueprintId, name, cursor, paused}（约 100 字节 NBT）
 * - steps 不存（55 万步太大）——重启后由蓝图 id 重新解析，O(N) 仅首次并缓存
 * - 游标持久化：重启后直接从进度处继续，无需全量快进
 * - 暂停状态持久化：重启后保持全局暂停
 *
 * v1.5.180：多区块共存——单计划字段升级为 SavedPlan 列表（每维度可多个区块），
 * 旧单计划存档自动迁移为第一个 SavedPlan（legacy: 前缀 planId）。
 */
public class BuildArchive extends net.minecraft.world.level.saveddata.SavedData {
    public static final String DATA_NAME = "maid_smart_build";

    /** v1.5.180：单个区块计划存档条目 */
    public static class SavedPlan {
        public String planId = "";
        public int ox;
        public int oy;
        public int oz;
        public String blueprintId = "";
        public String name = "";
        public int cursor = 1;
        public boolean paused = false;
        /** v1.5.69：工头女仆 UUID（建造反馈统一由工头发，其他女仆静默；空 = 未设） */
        public String foremanUuid = "";
    }

    /** v1.5.180：本维度所有区块计划存档（多区块共存） */
    public final java.util.List<SavedPlan> plans = new java.util.ArrayList<>();

    public BuildArchive() {
    }

    /**
     * v1.5.180：字符串序列化（避免 ListTag.add 的 SRG 不确定性）——
     * 每计划一行 "planId\u0001ox\u0001oy\u0001oz\u0001blueprintId\u0001name\u0001cursor\u0001paused\u0001foremanUuid"，
     * 行间 \u0002 分隔。
     */
    public String serializePlans() {
        StringBuilder sb = new StringBuilder();
        for (SavedPlan p : plans) {
            if (sb.length() > 0) {
                sb.append('\u0002');
            }
            sb.append(p.planId).append('\u0001').append(p.ox).append('\u0001').append(p.oy)
                    .append('\u0001').append(p.oz).append('\u0001').append(p.blueprintId).append('\u0001')
                    .append(p.name).append('\u0001').append(p.cursor).append('\u0001').append(p.paused)
                    .append('\u0001').append(p.foremanUuid);
        }
        return sb.toString();
    }

    public void deserializePlans(String s) {
        plans.clear();
        if (s == null || s.isEmpty()) {
            return;
        }
        for (String line : s.split("\u0002", -1)) {
            String[] f = line.split("\u0001", -1);
            if (f.length < 9) {
                continue;
            }
            SavedPlan p = new SavedPlan();
            p.planId = f[0];
            try {
                p.ox = Integer.parseInt(f[1]);
                p.oy = Integer.parseInt(f[2]);
                p.oz = Integer.parseInt(f[3]);
            } catch (NumberFormatException e) {
                continue;
            }
            p.blueprintId = f[4];
            p.name = f[5];
            try {
                p.cursor = Integer.parseInt(f[6]);
            } catch (NumberFormatException ignored) {
            }
            p.paused = "true".equals(f[7]);
            p.foremanUuid = f[8];
            if (!p.blueprintId.isEmpty()) {
                plans.add(p);
            }
        }
    }

    /** 存档读取（DimensionDataStorage 的 loader）——v1.5.180：多计划列表 + 旧档迁移 */
    public static BuildArchive load(net.minecraft.nbt.CompoundTag tag) {
        BuildArchive a = new BuildArchive();
        a.deserializePlans(tag.m_128425_("plans", 8) ? tag.m_128461_("plans") : "");
        // 旧档迁移：单计划字段 → 第一个 SavedPlan（legacy: 前缀 planId 保持稳定）
        if (a.plans.isEmpty() && tag.m_128425_("blueprintId", 8)) {
            String oldId = tag.m_128461_("blueprintId");
            if (!oldId.isEmpty()) {
                SavedPlan p = new SavedPlan();
                p.planId = "legacy:" + tag.m_128451_("ox") + "," + tag.m_128451_("oy") + "," + tag.m_128451_("oz");
                p.ox = tag.m_128451_("ox");
                p.oy = tag.m_128451_("oy");
                p.oz = tag.m_128451_("oz");
                p.blueprintId = oldId;
                p.name = tag.m_128461_("name");
                p.cursor = tag.m_128451_("cursor");
                p.paused = tag.m_128435_("paused") != 0; // v1.5.250：m_128441_ 是 containsKey 不是 getBoolean
                p.foremanUuid = tag.m_128425_("foremanUuid", 8) ? tag.m_128461_("foremanUuid") : "";
                a.plans.add(p);
            }
        }
        return a;
    }

    @Override
    public net.minecraft.nbt.CompoundTag m_7176_(net.minecraft.nbt.CompoundTag tag) {
        tag.m_128359_("plans", serializePlans());
        return tag;
    }

    /** 取当前维度的建造档案（懒加载，自动建档） */
    public static BuildArchive get(net.minecraft.server.level.ServerLevel level) {
        return level.m_8895_().m_164861_(BuildArchive::load, BuildArchive::new, DATA_NAME);
    }

    /** 档案有有效建造计划（v1.5.180：多计划） */
    public boolean hasPlan() {
        return !plans.isEmpty();
    }

    /** v1.5.180：按 planId 查存档条目（无返回 null） */
    public SavedPlan find(String planId) {
        for (SavedPlan p : plans) {
            if (p.planId.equals(planId)) {
                return p;
            }
        }
        return null;
    }

    /** v1.5.180：新增/覆盖存档条目 */
    public void upsert(SavedPlan p) {
        SavedPlan ex = find(p.planId);
        if (ex != null) {
            plans.set(plans.indexOf(ex), p);
        } else {
            plans.add(p);
        }
        m_77762_();
    }

    /** v1.5.180：删除存档条目（取消/完成单个区块时） */
    public void remove(String planId) {
        SavedPlan ex = find(planId);
        if (ex != null) {
            plans.remove(ex);
            m_77762_();
        }
    }
}
