package com.maidsmart.mixin;

import net.minecraft.client.model.ElytraModel;
import net.minecraft.client.model.geom.ModelPart;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * v1.2.0（1.20.1，客户端）：暴露 `ElytraModel` 的左右翅膀部件。
 *
 * 字段名用 SRG（反编译实证：1.20.1 的 ElytraModel 构造里
 * `this.f_102533_ = root.getChild("left_wing")` / `this.f_102532_ = ...("right_wing")`）。
 * 用途见 {@link com.maidsmart.client.ElytraSpread}：滑翔中强制完全展翅。
 */
@Mixin(ElytraModel.class)
public interface ElytraModelAccessor {

    @Accessor("f_102533_") // left_wing
    ModelPart promaid$leftWing();

    @Accessor("f_102532_") // right_wing
    ModelPart promaid$rightWing();
}
