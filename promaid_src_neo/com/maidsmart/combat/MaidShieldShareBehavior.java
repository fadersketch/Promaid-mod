package com.maidsmart.combat;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.behavior.Behavior;
import net.minecraft.world.item.ItemStack;

/**
 * v1.5.189：共享盾牌（被动，玩家贴身辅助）——core 行为，非工作状态。
 *
 * 主人主手/副手【正在拿着盾】且耐久低时，从女仆【背包】取一面盾给主人（复用
 * MaidToolAutoEquip 的盾牌选择逻辑：剩余耐久最高者；绝不拿自己副手正在用的盾，
 * 只拿背包里闲置的）。给盾后冷却 5 秒（防反复换）。
 * v1.5.250 修正：① 主人【没拿盾】不补——旧逻辑把"没拿盾"也当"需要补盾"，
 * 女仆会把捡来的盾硬塞给没用盾的主人（"从没拿出过盾却收到盾"的根因）；
 * ② 剩余耐久 <30% 的烂盾不 share（女仆拾取的怪物掉落低耐久盾）。
 * 总开关：combat.shieldShareEnable（默认开）。
 */
public class MaidShieldShareBehavior extends Behavior<EntityMaid> {

    private static final org.slf4j.Logger LOGGER = com.mojang.logging.LogUtils.getLogger();

    private int shareCooldown = 0;
    /** v1.5.227：canUse 首调诊断标记（只打第一条） */
    private boolean canUseLogged = false;

    public MaidShieldShareBehavior() {
        super(java.util.Collections.emptyMap(), Integer.MAX_VALUE, Integer.MAX_VALUE);
        // v1.5.227 诊断：行为构造 = 类被加载 + 实例被创建
        LOGGER.info("shield-share constructed");
    }

    @Override
    protected boolean checkExtraStartConditions(ServerLevel level, EntityMaid maid) {
        // v1.5.227 诊断：canUse 被 Brain 调用过一次后不再刷（只打第一条）
        if (!this.canUseLogged) {
            this.canUseLogged = true;
            LOGGER.info("shield-share canUse first-call: enabled={}",
                    com.maidsmart.config.MaidSmartConfig.SHIELD_SHARE_ENABLE.get());
        }
        return com.maidsmart.config.MaidSmartConfig.SHIELD_SHARE_ENABLE.get();
    }

    /**
     * v1.5.228【重大修复】：canStillUse 必须重写为 true——原版 1.20.1 Behavior 的
     * canStillUse 默认返回【false】！行为 tryStart 后下一 tick 立即被 tickOrStop
     * 停掉，tick() 永远不执行。盾牌共享行为从 v1.5.189 诞生起就没 tick 过。
     */
    @Override
    protected boolean canStillUse(ServerLevel level, EntityMaid maid, long gameTime) {
        return true;
    }

    @Override
    protected void tick(ServerLevel level, EntityMaid maid, long gameTime) {
        if (this.shareCooldown > 0) {
            this.shareCooldown--;
            return;
        }
        if (!(maid.getOwner() instanceof ServerPlayer owner)) {
            return;
        }
        if (!owner.isAlive()) {
            return;
        }
        if (maid.position().distanceTo(owner.position()) > 8.0) {
            return; // 主人不在身边不管
        }
        // 主人手里盾牌是否缺（主手/副手任一是盾且耐久低 → 换；都不是盾 → 补一个）
        boolean ownerHasShield = owner.getMainHandItem().getItem() instanceof net.minecraft.world.item.ShieldItem
                || owner.getOffhandItem().getItem() instanceof net.minecraft.world.item.ShieldItem;
        // v1.5.250【给盾逻辑修正】：主人根本没拿盾 → 不补。旧逻辑把"没拿盾"也
        // 当"需要补盾"，女仆会把背包里的盾（多半是拾取怪物掉落的低耐久盾）硬塞
        // 给没用盾的主人——"从没拿出过盾却收到盾"的根因；且塞完主人手里就有
        // 低耐久盾，下轮又触发"耐久低→再塞"，恶性循环
        if (!ownerHasShield) {
            return;
        }
        // 有盾但耐久还够 → 不管
        ItemStack main = owner.getMainHandItem();
        ItemStack off = owner.getOffhandItem();
        ItemStack shield = main.getItem() instanceof net.minecraft.world.item.ShieldItem ? main : off;
        // getMaxDamage = getMaxDamage（物品最大耐久）；getDamageValue = getDamageValue（已损耗）
        if (shield.getMaxDamage() - shield.getDamageValue() > shield.getMaxDamage() * 0.5f) {
            return; // 剩余耐久 > 50% 不换
        }
        // 从女仆背包选剩余耐久最高的盾（绝不拿副手）——v1.5.250：加质量门槛，
        // 剩余耐久 <30% 的烂盾不给主人（"莫名其妙给我一个耐久耗尽的盾"：女仆
        // 拾取了怪物掉落的低耐久盾（僵尸/骷髅 8.5% 持盾，死亡掉落的盾耐久随机），
        // 旧逻辑只比"相对最高"，烂盾也往主人手里塞——给了等于没给还占背包）
        int bestSlot = -1;
        int bestDura = 0;
        net.neoforged.neoforge.items.IItemHandler inv = maid.getMaidInv();
        for (int i = 0; i < inv.getSlots(); i++) {
            ItemStack stack = inv.getStackInSlot(i);
            if (stack.isEmpty() || !(stack.getItem() instanceof net.minecraft.world.item.ShieldItem)) {
                continue;
            }
            int maxDura = stack.getMaxDamage();
            int dura = maxDura - stack.getDamageValue(); // 剩余耐久
            if (maxDura <= 0 || dura < maxDura * 0.3f) {
                continue; // 烂盾（剩余 <30%）不 share——留背包/自己用，别污染主人
            }
            if (dura > bestDura) {
                bestDura = dura;
                bestSlot = i;
            }
        }
        if (bestSlot < 0) {
            return; // 女仆背包没有闲置盾
        }
        ItemStack toGive = inv.extractItem(bestSlot, 1, false);
        // 主人副手是空气 → 放副手；否则放背包（背包满退回女仆）
        ItemStack remain;
        if (owner.getOffhandItem().isEmpty()) {
            owner.setItemInHand(net.minecraft.world.InteractionHand.OFF_HAND, toGive);
            remain = ItemStack.EMPTY;
        } else {
            remain = net.neoforged.neoforge.items.ItemHandlerHelper.insertItemStacked(
                    new net.neoforged.neoforge.items.wrapper.InvWrapper(owner.getInventory()), toGive, false);
        }
        if (!remain.isEmpty()) {
            net.neoforged.neoforge.items.ItemHandlerHelper.insertItemStacked(inv, remain, false);
            return;
        }
        maid.getChatBubbleManager().addTextChatBubble("主人盾牌要坏了，我这面给你用！");
        this.shareCooldown = 100; // 5 秒
    }

    /** v1.5.217：诊断——女仆背包里的盾牌数量 */
    private static int countShields(EntityMaid maid) {
        int n = 0;
        try {
            net.neoforged.neoforge.items.IItemHandler inv = maid.getMaidInv();
            for (int i = 0; i < inv.getSlots(); i++) {
                ItemStack s = inv.getStackInSlot(i);
                if (!s.isEmpty() && s.getItem() instanceof net.minecraft.world.item.ShieldItem) {
                    n += s.getCount();
                }
            }
        } catch (Exception ignored) {
        }
        return n;
    }
}
