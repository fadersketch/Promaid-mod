package com.maidsmart.client;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.maidsmart.combat.MaidFlightKit;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * v1.3.0(beta) 实测七百一十一【鞘翅自有外观】——把女仆背上那对翅膀画成**那件鞘翅自己的样子**，
 * 而不是一律原版 {@code textures/entity/elytra.png}。
 *
 * <p>【玩家原话】「目前鞘翅的渲染只在空袭模式下会被渲染出来。而且渲染出来的全都是原版鞘翅，
 * 能不能调用那个鞘翅自己的外观呢？同时在所有模式下渲染。」
 *
 * <h2>为什么原来"全是原版鞘翅"</h2>
 * 我们的图层（{@link LayerMaidElytra} / {@link LayerMaidElytraGecko}）一直用原版
 * {@code ElytraModel} + 原版贴图——它只管"给她画一对翅膀"，完全不知道模组鞘翅长什么样。
 * 实测六百八十四 当时写明了理由：「模组装备没有通用 API 告诉别人"我的翅膀长什么样"」——
 * 那句话对**通用**判据是成立的，但对**已知的几件**不成立：它们的贴图路径就写在那几个模组的
 * 图层类里，逐条反编译抄过来即可（本类就是那份抄写）。
 *
 * <h2>"所有模式下渲染"与"外观"是两件事</h2>
 * <ul>
 *   <li><b>所有模式</b>：图层原来的闸门是 {@link MaidFlightKit#isFlightVisual}（飞行任务 **或**
 *       正在滑翔）——所以空袭/飞行跟随看得见，而"攻击模式 + 穿着鞘翅站着"看不见。现在改成
 *       **只要胸甲槽穿着可渲染的鞘翅就画**（折叠态），与原版玩家"穿着鞘翅背上就有一对翅膀"
 *       完全同款；张合仍由滑翔位决定（飞起来才张开）。见各图层的 gate。</li>
 *   <li><b>外观</b>：本类按物品 id 给出那件鞘翅自己的贴图路径。</li>
 * </ul>
 *
 * <h2>已收录的贴图（逐条反编译核实）</h2>
 * <b>伊卡洛斯之翼</b>（{@code locusazzurro_icaruswings}，namespace 内 {@code textures/entity/}
 * 下的 {@code <名字>_wings.png}）：羽毛系 feather / colored_feather / golden_feather_wings、
 * 纸翼 paper_wings、魔法翼 magic_wings、贤者之石翼（flandre_magic_wings →
 * {@code philosopher_stone_wings.png}）；**空域系 6 件**（ikaros/nymph/astraea/chaos/hiyori/
 * melan_wings）另有一份 {@code _reversed} 变体——模组在"正在滑翔"时改用反向贴图
 * （{@code WingsLayer.getElytraTexture}：{@code isFallFlying && item instanceof IWingsExpandable}
 * → {@code getTextureReversed()}），本类照抄这条（判据换成我们的滑翔位）。
 * <br><b>神秘遗物+</b>（{@code enigmaticlegacyplus}，{@code textures/models/misc/} 下）：
 * {@code majestic_elytra.png} / {@code chaos_elytra.png}（模组自己的
 * {@code EnigmaticElytraLayer.TEXTURE_MAP} 就是这两条）。
 *
 * <h2>认不出型号时</h2>
 * 一律退回原版 {@code textures/entity/elytra.png}——所以以后再加同类鞘翅，不写这里也不会画错，
 * 只是"外观还是原版的"；要它自己的样子就往 {@link #TEXTURE} 里补一行 id → 贴图路径。
 * 这张表与"自推鞘翅资格表"（{@code combat.selfWingsItems}）**互相独立**：能不能飞由那张表管，
 * 长什么样由这张表管——一件普通鞘翅（羽毛系）也能有自己的外观，只是它不会自推。
 *
 * <p>【两树同一份】只用到加载器共有的 {@code BuiltInRegistries.ITEM.getKey} 与
 * {@code ResourceLocation}，本文件在 SRG 树与 Mojmap 树里逐字相同。
 */
public final class MaidWingSkins {

    /** namespace 前缀：伊卡洛斯的翅膀贴图都在 {@code textures/entity/} 下 */
    private static final String IW = "locusazzurro_icaruswings";
    /** namespace 前缀：神秘遗物+ 的翅膀贴图都在 {@code textures/models/misc/} 下 */
    private static final String EL = "enigmaticlegacyplus";

    /** 认不出型号时的默认（与原版 {@code ElytraLayer.getElytraTexture} 的默认同文件） */
    private static final ResourceLocation VANILLA =
            ResourceLocation.withDefaultNamespace("textures/entity/elytra.png");

    /** 物品 id → 翅膀贴图（静态表，逐条反编译核实；见类注释） */
    private static final Map<String, ResourceLocation> TEXTURE = new HashMap<>();
    /** "有反向贴图"的物品 id（伊卡洛斯空域系 6 件：滑翔时用 {@code _reversed}） */
    private static final Set<String> REVERSED = new HashSet<>();

    static {
        // ── 伊卡洛斯之翼：羽毛系 / 纸翼 / 魔法翼 / 贤者之石翼（普通鞘翅，也各自有外观）──
        TEXTURE.put(IW + ":feather_wings", rl(IW, "textures/entity/feather_wings.png"));
        TEXTURE.put(IW + ":colored_feather_wings", rl(IW, "textures/entity/colored_feather_wings.png"));
        TEXTURE.put(IW + ":golden_feather_wings", rl(IW, "textures/entity/golden_feather_wings.png"));
        TEXTURE.put(IW + ":paper_wings", rl(IW, "textures/entity/paper_wings.png"));
        TEXTURE.put(IW + ":magic_wings", rl(IW, "textures/entity/magic_wings.png"));
        TEXTURE.put(IW + ":flandre_magic_wings", rl(IW, "textures/entity/philosopher_stone_wings.png"));
        // ── 伊卡洛斯之翼：空域系 6 件（自推 + 反向贴图）──
        synapse(IW + ":ikaros_wings", "synapse_alpha");
        synapse(IW + ":nymph_wings", "synapse_beta");
        synapse(IW + ":astraea_wings", "synapse_delta");
        synapse(IW + ":chaos_wings", "synapse_epsilon");
        synapse(IW + ":hiyori_wings", "synapse_zeta");
        synapse(IW + ":melan_wings", "synapse_theta");
        // ── 神秘遗物+：壮丽鞘翅 / 混沌之傲 ──
        TEXTURE.put(EL + ":majestic_elytra", rl(EL, "textures/models/misc/majestic_elytra.png"));
        TEXTURE.put(EL + ":chaos_elytra", rl(EL, "textures/models/misc/chaos_elytra.png"));
    }

    private MaidWingSkins() {
    }

    /**
     * 【实测七百一十一】"所有模式下渲染 + 用自有外观"总开关。
     *
     * <p>开 = 各图层不再要求 {@code isFlightVisual}（只要胸甲槽穿着可渲染的鞘翅就画，
     * 折叠态），且贴图走 {@link #textureFor}；关 = 完全退回旧行为。
     */
    public static boolean enabled() {
        try {
            return com.maidsmart.config.MaidSmartConfig.COMBAT_WING_RENDER.get();
        } catch (Throwable t) {
            return false; // 配置没加载等异常：按旧行为（宁可不画错）
        }
    }

    private static void synapse(String id, String type) {
        TEXTURE.put(id, rl(IW, "textures/entity/" + type + "_wings.png"));
        REVERSED.add(id);
    }

    private static ResourceLocation rl(String namespace, String path) {
        return ResourceLocation.fromNamespaceAndPath(namespace, path);
    }

    /**
     * 胸甲槽这件鞘翅该用哪张贴图。
     *
     * @param chest 胸甲槽物品栈（调用方已确认它能渲染）
     * @param maid  穿着者（判"正在滑翔"决定要不要用反向贴图）；null 时不取反向
     * @return 那件鞘翅自己的贴图；认不出型号 → 原版鞘翅贴图
     */
    public static ResourceLocation textureFor(ItemStack chest, EntityMaid maid) {
        try {
            if (chest == null || chest.isEmpty()) {
                return VANILLA;
            }
            String id = itemId(chest);
            if (id == null) {
                return VANILLA;
            }
            if (REVERSED.contains(id) && gliding(maid)) {
                // 伊卡洛斯空域系：模组自己在滑翔时用反向贴图（见类注释），照抄这一条
                ResourceLocation base = TEXTURE.get(id);
                if (base != null) {
                    return rl(base.getNamespace(), base.getPath().replace("_wings.png", "_wings_reversed.png"));
                }
            }
            ResourceLocation tex = TEXTURE.get(id);
            return tex != null ? tex : VANILLA;
        } catch (Throwable ignored) {
            return VANILLA; // 认不出来就用原版贴图——绝不能因为贴图查表而让整层渲染抛掉
        }
    }

    /** 她正在滑翔吗（共享标志位 7，服务端/客户端一致） */
    private static boolean gliding(EntityMaid maid) {
        try {
            return MaidFlightKit.isGliding(maid);
        } catch (Throwable t) {
            return false;
        }
    }

    /** 物品注册名（modid:item）；取不到返回 null */
    private static String itemId(ItemStack stack) {
        try {
            ResourceLocation key = BuiltInRegistries.ITEM.getKey(stack.getItem());
            return key == null ? null : key.toString();
        } catch (Throwable t) {
            return null;
        }
    }
}
