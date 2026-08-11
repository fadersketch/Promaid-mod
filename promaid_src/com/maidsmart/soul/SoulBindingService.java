package com.maidsmart.soul;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.server.level.ServerLevel;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 灵魂记忆路由（v1.5.251 灵魂共享存储的残留部分）。
 *
 * v1.5.251e【灵魂核心功能移除】：制作成本高、暂不考虑跨存档——灵魂核心物品/
 * 开局赠送/绑定/迁移/双分身防护全部删除。此处【只保留路由兼容】：
 * - 旧版本已绑定过灵魂的女仆（persistentData 里仍有 maid_smart_soul_id），
 *   记忆继续读写全局灵魂目录（config/maid_smart/souls/<soulId>/memory/），
 *   已写入的记忆不丢；
 * - 未绑定/新女仆走世界目录（<世界>/promaid_memory/），一切照旧。
 */
public final class SoulBindingService {
    private static final String NBT_KEY = "maid_smart_soul_id";

    private SoulBindingService() {
    }

    /** 女仆当前绑定 soulId（无绑定返回 null）——旧版本数据兼容 */
    public static String getSoulId(EntityMaid maid) {
        String v = maid.getPersistentData().m_128461_(NBT_KEY);
        return v == null || v.isEmpty() ? null : v;
    }

    // ---------- 路径 ----------

    private static Path soulsRoot() {
        return net.minecraftforge.fml.loading.FMLPaths.CONFIGDIR.get()
                .resolve("maid_smart").resolve("souls");
    }

    /** 灵魂女仆的记忆根目录（全局共享；未绑定返回 null） */
    public static Path memoryRootFor(EntityMaid maid) {
        String soulId = getSoulId(maid);
        if (soulId == null || soulId.isEmpty()) {
            return null;
        }
        Path p = soulsRoot().resolve(sanitize(soulId)).resolve("memory");
        try {
            Files.createDirectories(p);
        } catch (Exception ignored) {
        }
        return p;
    }

    /** 女仆记忆存储统一入口——旧版绑定灵魂的女仆 → 全局灵魂目录（兼容）；
     *  其余 → 世界目录。写入自动带来源世界标注（world: 维度名）。 */
    public static com.maidsmart.memory.AiMemoryStore storeFor(EntityMaid maid, ServerLevel level) {
        Path soul = memoryRootFor(maid);
        Path root = soul != null ? soul : com.maidsmart.memory.AiMemoryExtractor.memoryRoot(level.m_7654_());
        return com.maidsmart.memory.AiMemoryStore.of(maid.m_20148_(), root, worldName(level));
    }

    /** 维度名（世界标注用，如 overworld / the_nether） */
    public static String worldName(ServerLevel level) {
        return level.m_46472_().m_135782_().m_135815_();
    }

    private static String sanitize(String id) {
        return id == null ? "soul" : id.replaceAll("[\\\\/:*?\"<>|]", "_");
    }
}
