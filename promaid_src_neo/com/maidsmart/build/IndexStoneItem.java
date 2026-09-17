package com.maidsmart.build;

import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/**
 * 指标石（v1.2.0，1.21.1 NeoForge 版）——临时蓝图制作器。
 *
 * 手持时：
 * - 客户端把视线指向的方块渲染成绿色（跟随指针实时移动）；
 * - 右键 → 锁定（渲染变红、不再移动）；**再右键【同一个锁定方块】** → 取消锁定
 *   （右击别的方块 = 换锁定点；右击空气/超出距离 = 保持锁定不变）；
 * - 右键自己的女仆 → 绑定（须先锁方块；再右击同一只/另一只 = 解绑/换绑）。
 *
 * 【锁定的两条路径（满足"几乎无视距离"）】
 * - 近处方块（原版触及距离内）：原版走 useOn → 服务端直接知道坐标并锁定。
 * - 远处方块（超出触及距离）：原版可能落空 → 【客户端】长射线（512 格）算出真正
 *   指向的方块，经 LockRequestPacket 提交给服务端锁定（见 IndexStonePreviewClient）。
 *
 * 本物品类不引用任何客户端类型（专用服务器安全，遵循项目既有约定：
 * 带 Screen/Minecraft 等客户端类型的代码一律放 dist 守卫类）。
 */
public class IndexStoneItem extends Item {

    public IndexStoneItem(Properties properties) {
        super(properties);
    }

    /** 常驻附魔光效（手册/排班表同款 isFoil） */
    @Override
    public boolean isFoil(ItemStack stack) {
        return true;
    }

    /**
     * 右键【近处方块】：锁定 / 解锁。客户端返回 SUCCESS 让挥手动画正常。
     *
     * v1.2.0 实测四百八十五：锁定期间右击【别的】近处方块会被 `toggleLock` 拒绝并保持原锁定，
     * 旧文案会把被拒的那一格误报成"已锁定 X,Y,Z"。这里据"本次操作后是否仍锁定在原格"分流，
     * 只在【真正锁上新格】时报告坐标。提示本身由 Service 给出（含"锁定中不可改选"）。
     */
    @Override
    public InteractionResult useOn(net.minecraft.world.item.context.UseOnContext ctx) {
        Level level = ctx.getLevel();
        Player player = ctx.getPlayer();
        if (level.isClientSide() || !(player instanceof net.minecraft.server.level.ServerPlayer sp)) {
            return InteractionResult.SUCCESS;
        }
        if (!IndexStoneService.isEnabled()) {
            return InteractionResult.PASS;
        }
        BlockPos pos = ctx.getClickedPos();
        // 操作前的状态：用于区分"这次到底做了什么"
        IndexStoneService.Session before = IndexStoneService.session(sp.getUUID());
        boolean wasLockedOnOther = before != null && before.isLocked()
                && !before.lockedBlock.equals(pos);
        boolean locked = IndexStoneService.toggleLock(sp, pos);
        if (locked && !wasLockedOnOther) {
            // 真正锁上了这一格（首次锁定）
            IndexStoneService.msgPublic(sp, "\u00a7a指标石：已锁定 "
                    + pos.getX() + "," + pos.getY() + "," + pos.getZ()
                    + "（红色）。现在右击你的女仆绑定她。");
        } else if (!locked) {
            IndexStoneService.msgPublic(sp, "\u00a77指标石：已取消锁定。");
        }
        // 其余情况（锁定中被拒："不可改选"提示已由 Service 发出）不重复播报
        IndexStoneNetworking.syncTo(sp);
        return InteractionResult.SUCCESS;
    }

    /**
     * 右键（对着空气 / 超出触及距离）。服务端不做实际事：远处锁定由客户端长射线
     * 算出坐标后经 LockRequestPacket 提交。
     */
    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (!IndexStoneService.isEnabled()) {
            return new InteractionResultHolder<>(InteractionResult.PASS, stack);
        }
        return new InteractionResultHolder<>(InteractionResult.CONSUME, stack);
    }

    /** 用法提示（客户端聊天栏显示；纯字符串，无客户端类型依赖） */
    public static String usageHint() {
        return "\u00a7e指标石用法：右击方块锁定（绿→红，可锁很远）→ 右击你的女仆绑定 → 她开始临时搭建；"
                + "右击【同一个锁定方块】才会解除锁定，锁定期间不能改选别的方块。";
    }

    /**
     * v1.2.0 实测四百八十五：锁定期间的提示——期间不能改选别的方块。
     * 与 {@link #usageHint} 一样是纯字符串，无客户端类型依赖。
     */
    public static String lockedHint() {
        return "\u00a7e指标石：锁定中——右击【那个锁定方块】解除锁定后才能选新的方块。";
    }
}
