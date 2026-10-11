package com.maidsmart.mixin;

import com.github.tartaricacid.touhoulittlemaid.client.resource.models.DefaultPackConstant;
import com.github.tartaricacid.touhoulittlemaid.client.resource.models.MaidModels;
import com.github.tartaricacid.touhoulittlemaid.client.resource.pojo.CustomModelPack;
import com.github.tartaricacid.touhoulittlemaid.client.resource.pojo.MaidModelInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 修复「同 domain 的模型包只活下来一个，其余整包的模型全部从模型选择界面消失」。
 * 【根因（TLM 1.5.3 字节码实证，两树同一份代码）】模型包 id 就是 {@code assets/} 下的**域名目录名**
 * （{@code CustomPackLoader.loadMaidModelPack} → {@code pack.decorate(domain)}），而 TLM 给内置包留了排位
 * 名单 {@code DefaultPackConstant.MAID_SORT}。{@code MaidModels.sortPackList()} 先对 MAID_SORT 每个 id 用
 * {@code findFirst()} 各收**一个**包，再把"id 在 MAID_SORT 里"的包**全部排除**后追加其余包。两者相加 ⇒
 * **同 domain 的第二个及以后的包，既不在前段也不在后段，直接从 packList 里消失**；而 GUI 是按包遍历
 * {@code getModelList()} 的 ⇒ 整包模型一起"被隐藏"。
 * 【为什么只有 1.21.1 出问题】用户 1.21.1 实例里装了一个域名也叫 {@code geckolib} 的模型包（与 TLM 内置的
 * {@code touhou_little_maid-1.0.0/assets/geckolib} 撞名），该目录在 {@code listFiles()} 顺序里排在前面，于是
 * **内置 geckolib 包（27 个声明模型）被丢掉**，界面只剩新包的 3 个；1.20.1 没有第二个 geckolib 包，对照组正常。
 * 【改法】只把"排序"改回排序语义、不再丢包：MAID_SORT 里的 id 仍按名单顺序优先（同 id 的多个包按加载顺序
 * 依次排在一起），其余包按 id 排序跟在后面。**唯一的语义变化是"不再丢弃"**，所有包相对顺序与 TLM 原版一致。
 * 注：椅子模型（{@code ChairModels.sortPackList} + {@code CHAIR_SORT}）是同一份逻辑、同样的坑，但用户包内没有第二份椅子域名，故本次不动、避免扩大改动面。
 */
@Mixin(value = MaidModels.class, remap = false)
public abstract class MaidModelPackSortMixin {

    @Inject(method = "sortPackList", at = @At("HEAD"), cancellable = true)
    private void promaid$sortPackListKeepAllPacks(CallbackInfo ci) {
        MaidModels self = (MaidModels) (Object) this;
        List<CustomModelPack<MaidModelInfo>> all = new ArrayList<>(self.getPackList());
        if (all.isEmpty()) {
            ci.cancel();
            return;
        }

        Set<CustomModelPack<MaidModelInfo>> placed = new HashSet<>();
        List<CustomModelPack<MaidModelInfo>> ordered = new ArrayList<>(all.size());
        for (String id : DefaultPackConstant.MAID_SORT) {
            for (CustomModelPack<MaidModelInfo> pack : all) {
                if (id.equals(pack.getId()) && placed.add(pack)) {
                    ordered.add(pack);
                }
            }
        }

        List<CustomModelPack<MaidModelInfo>> rest = new ArrayList<>();
        for (CustomModelPack<MaidModelInfo> pack : all) {
            if (placed.add(pack)) {
                rest.add(pack);
            }
        }
        rest.sort(Comparator.comparing(pack -> pack.getId()));
        ordered.addAll(rest);

        List<CustomModelPack<MaidModelInfo>> live = self.getPackList();
        live.clear();
        live.addAll(ordered);
        ci.cancel();
    }
}
