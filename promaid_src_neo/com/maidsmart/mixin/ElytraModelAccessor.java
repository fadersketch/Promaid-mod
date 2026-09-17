package com.maidsmart.mixin;

import net.minecraft.client.model.ElytraModel;
import net.minecraft.client.model.geom.ModelPart;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * v1.2.0（1.21.1，客户端）：暴露 `ElytraModel` 的左右翅膀部件。
 *
 * 用途见 {@link com.maidsmart.client.ElytraSpread}：滑翔中强制完全展翅。
 * 这两个字段是 private 的，只能经 @Accessor 读。
 */
@Mixin(ElytraModel.class)
public interface ElytraModelAccessor {

    @Accessor("leftWing")
    ModelPart promaid$leftWing();

    @Accessor("rightWing")
    ModelPart promaid$rightWing();
}
