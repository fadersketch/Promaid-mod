package com.maidsmart;

import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.advancements.AdvancementProgress;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

/**
 * 首次进入游戏的一次性赠礼——用【原版进度系统】当"已领取"记录（不手搓 NBT 标记：那种
 * 标记拦不住跨会话重发，日志里同一存档反复发书）。
 *
 * 做法：数据包里放一个用 {@code minecraft:impossible} 触发器的进度（永不自动触发，只由本类
 * 显式授予）+ 指向两本书的奖励掉落表。玩家登录时调 award()：没领过 → 记录进度 + 原版自动
 * 发书；领过 → 空操作。"只发一次"由原版进度数据保证（服务端强制持久化、随玩家存档走；
 * 发奖励只在"从未完成 → 完成"那一刻触发，重复 award 已完成进度是空操作）。
 * 【老存档迁移】旧版已拿过书的人（persistentData 标记=1）静默把进度置为已完成但**不发奖励**。
 */
public final class FirstJoinGift {

    /** 一次性赠礼进度（data/maid_smart/advancement/first_join_gift.json） */
    private static final ResourceLocation GIFT_ADVANCEMENT =
            ResourceLocation.fromNamespaceAndPath("maid_smart", "first_join_gift");
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
            // 【R1079】整合包开关：进入存档时是否发放开局手册/排班表（默认开）。
            if (!com.maidsmart.config.MaidSmartConfig.MISC_GIVE_STARTER_BOOKS.get()) {
                return;
            }
            net.minecraft.server.MinecraftServer server = player.level().getServer();
            if (server == null) {
                return;
            }
            AdvancementHolder adv = server.getAdvancements().get(GIFT_ADVANCEMENT);
            if (adv == null) {
                fallbackGive(player);
                return;
            }
            net.minecraft.server.PlayerAdvancements pa = player.getAdvancements();
            AdvancementProgress progress = pa.getOrStartProgress(adv);

            // 已经领过（进度已完成）→ 直接返回，绝不重复发
            if (progress.isDone()) {
                return;
            }

            // 老存档迁移：旧版本用 persistentData 标记发过书的人，这里静默记账
            // —— grantProgress 只记进度、不触发 AdvancementRewards.grant，
            // 所以不会多发一份；下次登录 isDone() 即为 true，走上面的分支。
            if (((net.neoforged.neoforge.common.extensions.IEntityExtension) player)
                    .getPersistentData().getBoolean(LEGACY_FLAG)) {
                boolean changed = false;
                for (String c : progress.getRemainingCriteria()) {
                    changed |= progress.grantProgress(c);
                }
                if (changed) {
                    pa.save();
                }
                return;
            }

            // 首次：正常授予 → 记录进度 + 原版自动按掉落表发奖励
            if (pa.award(adv, CRITERION)) {
                com.maidsmart.tool.PromaidLog.log("赠礼",
                        (player.getDisplayName() != null ? player.getDisplayName().getString()
                                : player.getUUID().toString())
                                + " 首次进入，已发放《Promaid 手册》与《排班表》");
                player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                        "\u00a7a[maid_smart] \u300aPromaid \u624b\u518c\u300b\u4e0e\u300a\u6392\u73ed\u8868\u300b"
                                + "\u5df2\u9001\u5230\u4f60\u80cc\u5305\uff01\u624b\u6301\u53f3\u952e\u6253\u5f00\u5168\u90e8"
                                + "\u56fe\u7eb8\u5217\u8868\uff08\u542b\u7f3a\u6750\u63d0\u793a\uff09\uff0c\u70b9\u51fb\u5373"
                                + "\u8ba9\u5973\u4ec6\u5efa\u9020\uff08\u5973\u4ec6\u9700\u5148\u5207\u5230\u201c\u5efa\u7b51"
                                + "\u201d\u4efb\u52a1\uff09\uff1b\u6392\u73ed\u8868\u6253\u5f00\u5373\u53ef\u7ed9\u5168\u90e8"
                                + "\u5973\u4ec6\u6392\u65e5\u7a0b\u3002\u62df\u91cd\u65b0\u83b7\u53d6\uff1a/give @p "
                                + "maid_smart:blueprint_book\u3002"));
            }
        } catch (Throwable t) {
             com.maidsmart.diag.Diag.swallow("FirstJoinGift", t); // 进度系统出问题不能让玩家登不进来——退回旧发法
            com.maidsmart.tool.PromaidLog.log("赠礼", "进度授予失败，退回旧发法：" + t);
            fallbackGive(player);
        }
    }

    /** 数据包缺失/异常时的兜底：沿用旧的一次性标记 + 直接塞背包。 */
    private static void fallbackGive(ServerPlayer player) {
        try {
            net.minecraft.nbt.CompoundTag data =
                    ((net.neoforged.neoforge.common.extensions.IEntityExtension) player).getPersistentData();
            if (data.getBoolean(LEGACY_FLAG)) {
                return;
            }
            data.putBoolean(LEGACY_FLAG, true);
            player.getInventory().add(new net.minecraft.world.item.ItemStack(
                    ProMaidMod.BLUEPRINT_BOOK.get()));
            player.getInventory().add(new net.minecraft.world.item.ItemStack(
                    ProMaidMod.SCHEDULE_BOOK.get()));
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("FirstJoinGift", ignored);
        }
    }
}
