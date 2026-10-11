package com.maidsmart.mixin;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.maidsmart.follow.MaidTeleportRebuild;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 【issue #36】传送动作的嗅探点：给女仆打一个"下一次发包时让客户端重建"的标记。
 * （本文件是 neo / 1.21.1 树；forge 版见同名文件，官方名与 SRG 名各写各的。）
 *
 * <p>照搬上游开源项目 2415564388/maidsync（MIT）的同名 mixin。{@code moveTo(double,double,double,
 * float,float)} 是最常见的一条瞬移路径（{@code Entity.teleportTo} 的**同维度分支**正是走它），而正常
 * 行走走的是 {@code move(MoverType, Vec3)} → {@code setPos}（内部另有一条 {@code moveTo(Vec3, float,
 * float)} 重载不经过这个 {@code (DDDFF)} 重载），所以不会误标。
 *
 * <p><b>但它不是唯一的路，这里只当"提前一拍"用。</b>真正的兜底在 {@link ServerEntityRebuildMixin}：
 * 那里盯的是"位置基线出现大跳变"这个**结果**，而不是"谁调用了哪个传送方法"这个**过程**——导致客户端
 * 插值冻结的是那个大跳变本身，跟它怎么来的无关。两条路打同一个标记，即使某个传送路径绕开了
 * {@code moveTo(DDDFF)}，也照样被兜底的基线判定接住。
 */
@Mixin(Entity.class)
public abstract class EntityMoveToMarkMixin {

    @Inject(method = "moveTo(DDDFF)V", at = @At("HEAD"))
    private void promaid$markTeleport(double x, double y, double z, float yRot, float xRot, CallbackInfo ci) {
        Entity self = (Entity) (Object) this;
        // 加载实体时也会走 moveTo，那时还没进世界，不能当传送处理
        if (self.level().isClientSide() || self.isRemoved()) {
            return;
        }
        if (!(self instanceof EntityMaid maid)) {
            return;
        }
        // Sable 物理子关卡：她那儿的每一次位置变化都是 Sable 踢出来的、不是传送（详见 MaidTeleportRebuild.inSubLevel）
        if (MaidTeleportRebuild.inSubLevel(maid)) {
            return;
        }
        if (MaidTeleportRebuild.movedDistance(self, x, y, z) <= MaidTeleportRebuild.REBUILD_DISTANCE) {
            return;
        }
        MaidTeleportRebuild.mark(self.getId());
    }
}
