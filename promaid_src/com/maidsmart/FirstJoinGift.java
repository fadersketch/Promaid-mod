package com.maidsmart;

import net.minecraft.advancements.Advancement;
import net.minecraft.advancements.AdvancementProgress;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

/**
 * v1.2.0：首次进入游戏的一次性赠礼——改用【原版进度系统】当作"已领取"记录。
 *
 * 背景（实机日志实证）：旧版在玩家的 persistentData 里手写一个
 * maid_smart_blueprints_given 标记，每次登录查它。日志显示同一个存档里，
 * 10:55 登录发一次、10:59 又登录又发一次——标记拦不住跨会话重发，
 * 玩家背包里也确实多出了书。手搓 NBT 标记这条路不可靠。
 *
 * 参照物：TLM 自己的《记忆中的幻想乡》手册就是用原版进度系统发一次的
 * （GivePatchouliBookConfigTrigger + data/touhou_little_maid/advancements/
 * grant_book_on_first_join.json + 奖励掉落表）。"只发一次"由【原版进度数据】
 * 保证——它由服务端强制持久化、随玩家存档走，且发奖励只在"从未完成 → 完成"
 * 的那一刻触发（PlayerAdvancements.award 字节码：!wasDone && isDone 才
 * AdvancementRewards.grant），重复 award 同一个已完成的进度是空操作。
 *
 * 本类的做法：
 * - data/maid_smart/advancements/first_join_gift.json：一个用
 *   minecraft:impossible 触发器的进度（永远不会自己触发，只由本类显式授予），
 *   奖励指向 data/maid_smart/loot_tables/gift/first_join.json（两本书）。
 * - 玩家登录时调 award()：没领过 → 记录进度 + 原版自动发两本书；领过 → 空操作。
 * - 【老存档迁移】旧版已经拿过书的人（persistentData 标记=1）静默把进度置为
 *   已完成但【不发奖励】（grantProgress 不触发 reward），不会多给一份。
 */
public final class FirstJoinGift {

    /** 一次性赠礼进度（data/maid_smart/advancements/first_join_gift.json） */
    private static final ResourceLocation GIFT_ADVANCEMENT =
            new ResourceLocation("maid_smart", "first_join_gift");
    /** 进度里唯一的判据名（json 的 criteria.given） */
    private static final String CRITERION = "given";
    /** 老存档迁移用的标记键（旧版写入的同一把钥匙） */
    private static final String LEGACY_FLAG = "maid_smart_blueprints_given";

    private FirstJoinGift() {
    }

    /**
     * 玩家登录时调用。首次 → 授予进度（原版按掉落表发两本书）；
     * 已授予过 → 什么都不做；老存档（旧标记已写）→ 静默补记进度、不重复发。
     */
    public static void onLogin(ServerPlayer player) {
        try {
            net.minecraft.server.MinecraftServer server = player.m_9236_().m_7654_();
            if (server == null) {
                return;
            }
            Advancement adv = server.m_129889_().m_136041_(GIFT_ADVANCEMENT);
            if (adv == null) {
                // 数据包没加载到（不该发生）——退回旧逻辑，至少别让功能消失
                fallbackGive(player);
                return;
            }
            net.minecraft.server.PlayerAdvancements pa = player.m_8960_();
            AdvancementProgress progress = pa.m_135996_(adv);

            // 已经领过（进度已完成）→ 直接返回，绝不重复发
            if (progress.m_8193_()) {
                return;
            }

            // 老存档迁移：旧版本用 persistentData 标记发过书的人，这里静默记账
            // —— grantProgress 只记进度、不触发 AdvancementRewards.grant，
            // 所以不会多发一份；下次登录 isDone() 即为 true，走上面的分支。
            if (player.getPersistentData().m_128471_(LEGACY_FLAG)) {
                boolean changed = false;
                for (String c : progress.m_8219_()) { // getRemainingCriteria
                    changed |= progress.m_8196_(c);   // grantProgress
                }
                if (changed) {
                    pa.m_135991_();                   // save
                }
                return;
            }

            // 首次：正常授予 → 记录进度 + 原版自动按掉落表发奖励
            if (pa.m_135988_(adv, CRITERION)) {
                com.maidsmart.tool.PromaidLog.log("赠礼",
                        (player.m_5446_() != null ? player.m_5446_().getString() : player.m_20148_())
                                + " 首次进入，已发放《Promaid 手册》与《排班表》");
                player.m_213846_(net.minecraft.network.chat.Component.m_237113_(
                        "\u00a7a[maid_smart] \u300aPromaid \u624b\u518c\u300b\u4e0e\u300a\u6392\u73ed\u8868\u300b"
                                + "\u5df2\u9001\u5230\u4f60\u80cc\u5305\uff01\u624b\u6301\u53f3\u952e\u6253\u5f00\u5168\u90e8"
                                + "\u56fe\u7eb8\u5217\u8868\uff08\u542b\u7f3a\u6750\u63d0\u793a\uff09\uff0c\u70b9\u51fb\u5373"
                                + "\u8ba9\u5973\u4ec6\u5efa\u9020\uff08\u5973\u4ec6\u9700\u5148\u5207\u5230\u201c\u5efa\u7b51"
                                + "\u201d\u4efb\u52a1\uff09\uff1b\u6392\u73ed\u8868\u6253\u5f00\u5373\u53ef\u7ed9\u5168\u90e8"
                                + "\u5973\u4ec6\u6392\u65e5\u7a0b\u3002\u62df\u91cd\u65b0\u83b7\u53d6\uff1a/give @p "
                                + "maid_smart:blueprint_book\u3002"));
            }
        } catch (Throwable t) {
            // 进度系统出问题不能让玩家登不进来——退回旧发法（可能重发，但优先可用）
            com.maidsmart.tool.PromaidLog.log("赠礼", "进度授予失败，退回旧发法：" + t);
            fallbackGive(player);
        }
    }

    /** 数据包缺失/异常时的兜底：沿用旧的一次性标记 + 直接塞背包。 */
    private static void fallbackGive(ServerPlayer player) {
        try {
            net.minecraft.nbt.CompoundTag data = player.getPersistentData();
            if (data.m_128471_(LEGACY_FLAG)) {
                return;
            }
            data.m_128379_(LEGACY_FLAG, true);
            player.m_150109_().m_36054_(new net.minecraft.world.item.ItemStack(
                    ProMaidMod.BLUEPRINT_BOOK.get()));
            player.m_150109_().m_36054_(new net.minecraft.world.item.ItemStack(
                    ProMaidMod.SCHEDULE_BOOK.get()));
        } catch (Throwable ignored) {
        }
    }
}
