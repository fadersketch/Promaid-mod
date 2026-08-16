package com.maidsmart.persona;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.maidsmart.memory.AiMemoryStore;
import com.maidsmart.soul.SoulBindingService;
import net.minecraft.server.level.ServerLevel;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * 人设同步（v1.2.x）——处理「玩家中途修改 TLM 人设提示词」的语义。
 *
 * 背景:TLM AI 聊天配置里的人设提示词是"快系统"(玩家可随时改写),promaid 沉淀的
 * 身份性核心记忆是"慢系统"(宪法级 salience 9-10,每轮注入)。玩家把人设改成与旧
 * 记忆冲突的内容时,宪法级记忆会与新设定打架,且 LLM 不知道"设定被主人改过"。
 *
 * 处理(人设 = 稳定内核 + 成长外壳;中途修改 = 一次"人格转折"):
 * 1. 指纹检测:记录上次见过的 customSetting 内容指纹(hash+长度),周期比较;
 * 2. 变化时:
 *    a. 沉淀演化记忆(宪法级 salience 8):「主人重新设定了我的性格与形象——按新期待来活」,
 *       LLM 明确"设定变了";
 *    b. 身份性核心记忆降级(type=relationship/anniversary 的 salience>3 → 3):
 *       新设定不再被旧宪法级记忆压制;若关系事实未变(仍是妻子),后续结婚纪念日等
 *       事件沉淀会重新升回 9-10——自愈;
 *    c. 同一天只处理一次(防周期性重写人设的模组刷记忆),但指纹照常更新;
 * 3. 玩家清空人设 → 指纹变化同样触发(演化记忆),且 tlmHasPersona 自动回退完整模式。
 *
 * 指纹文件:persona_sync.properties(灵魂目录内,跟随灵魂绑定)。
 */
public final class PersonaSyncManager {
    private static final String SYNC_FILE = "persona_sync.properties";
    private static final String KEY_HASH = "tlmPersonaHash";
    private static final String KEY_LAST_CHANGE_DAY = "lastChangeDay";

    private PersonaSyncManager() {
    }

    /** 周期性调用(关系扫描每轮);无人设变化或 IO 失败静默 */
    public static void checkPersonaChange(EntityMaid maid, ServerLevel level) {
        try {
            String setting = currentSetting(maid);
            String hash = fingerprint(setting);
            AiMemoryStore store = SoulBindingService.storeFor(maid, level);
            if (store == null) {
                return;
            }
            Path dir = store.dir();
            Path f = dir.resolve(SYNC_FILE);
            Properties props = new Properties();
            if (Files.exists(f)) {
                try (var in = Files.newInputStream(f)) {
                    props.load(in);
                }
            }
            String oldHash = props.getProperty(KEY_HASH);
            if (hash.equals(oldHash)) {
                return; // 无人设变化
            }
            long day = level.m_46467_() / 24000L;
            if (oldHash == null) {
                // 首次见到人设:只记录,不打扰(玩家正在写/刚写完设定)
                save(f, props, hash, day);
                return;
            }
            // 同一天已处理过:只更新指纹,不重复沉淀(防周期性重写人设的模组刷记忆)
            String lastDay = props.getProperty(KEY_LAST_CHANGE_DAY);
            if (lastDay != null && Long.parseLong(lastDay) == day) {
                save(f, props, hash, day);
                return;
            }
            // 人设确实变了:沉淀演化记忆 + 降级旧身份核心记忆
            String summary = setting.isBlank() ? "（主人收回了自定义设定）"
                    : "「" + (setting.length() > 40 ? setting.substring(0, 40) + "…" : setting) + "」";
            PersonaPackage.appendCoreMemory(dir, "persona_update",
                    "主人重新设定了我的性格与形象——" + summary + "。我会按照主人新的期待来活",
                    8, "persona_update,relationship,daily");
            demoteIdentityCoreMemories(dir);
            save(f, props, hash, day);
        } catch (Exception ignored) {
            // 记忆系统不能影响游戏运行
        }
    }

    /** 当前 TLM 人设内容(TLM 界面保存的 customSetting;null 视为空=未自定义) */
    private static String currentSetting(EntityMaid maid) {
        try {
            com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.MaidAIChatManager cm = maid.getAiChatManager();
            if (cm != null && cm.customSetting != null) {
                return cm.customSetting;
            }
        } catch (Exception ignored) {
        }
        return "";
    }

    /** 内容指纹:hashCode + 长度(足够检测"改过",不追求抗碰撞) */
    private static String fingerprint(String setting) {
        return Integer.toHexString(setting.hashCode()) + ":" + setting.length();
    }

    /**
     * 身份性核心记忆降级:type=relationship/anniversary 且 salience>3 的条目降到 3——
     * 新设定不再被旧"宪法级"记忆压制;关系事实未变时,后续关系事件沉淀会重新升回。
     */
    private static void demoteIdentityCoreMemories(Path maidDir) {
        Path f = maidDir.resolve("core_memories.jsonl");
        if (!Files.exists(f)) {
            return;
        }
        try {
            List<String> lines = new ArrayList<>();
            for (String line : Files.readAllLines(f, StandardCharsets.UTF_8)) {
                if (line.isBlank()) {
                    continue;
                }
                PersonaPackage.CoreMemory c = PersonaPackage.CoreMemory.fromJsonLine(line);
                if (c != null && c.salience() > 3
                        && ("relationship".equals(c.type()) || "anniversary".equals(c.type()))) {
                    // record 无 withX 方法,手工重建降 salience
                    c = new PersonaPackage.CoreMemory(c.type(), c.text(), 3, c.tags());
                    lines.add(com.maidsmart.memory.AiMemoryModels.GSON.toJson(c));
                } else {
                    lines.add(line);
                }
            }
            StringBuilder sb = new StringBuilder();
            for (String l : lines) {
                sb.append(l).append('\n');
            }
            Files.writeString(f, sb.toString(), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
    }

    private static void save(Path f, Properties props, String hash, long day) {
        try {
            props.setProperty(KEY_HASH, hash);
            props.setProperty(KEY_LAST_CHANGE_DAY, String.valueOf(day));
            try (var out = Files.newOutputStream(f)) {
                props.store(out, "TLM persona sync state");
            }
        } catch (Exception ignored) {
        }
    }
}
