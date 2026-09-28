package com.maidsmart.client;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.maidsmart.combat.MaidFlightKit;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.registries.ForgeRegistries;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * v1.3.0(beta)【鞘翅自有外观·三段式解析】——把女仆背上那对翅膀画成**那件鞘翅自己的样子**，
 * 而不是一律原版 {@code textures/entity/elytra.png}。
 *
 * <p>【玩家原话】「目前鞘翅的渲染只在空袭模式下会被渲染出来。而且渲染出来的全都是原版鞘翅，
 * 能不能调用那个鞘翅自己的外观呢？同时在所有模式下渲染。」后来又追加了一句更关键的：
 * 「我希望这种渲染是一种**通解通法**，而不是一些专门的适配。尽可能规避去专门适配的情况。」
 *
 * <h2>为什么不能"完全不认识任何模组也画对"</h2>
 * 因为没有共享 API——Forge/NeoForge 的 {@code IClientItemExtensions} 里**根本没有**
 * "告诉我你的鞘翅贴图"这个钩子（javap 实证：1.20.1 只有 getFont/getArmPose/
 * applyForgeHandTransform/getHumanoidArmorModel/getGenericArmorModel/renderHelmetOverlay/
 * getCustomRenderer；1.21.1 加了 setupModelAnimations/getArmorLayerTintColor/… 仍然没有）。
 * 原版 1.20.1 的 {@code ElytraLayer} 更是把 {@code Items.ELYTRA} 与贴图常量写死，
 * 直到 1.21.1 才加了可覆写的 {@code getElytraTexture}。所以"万能解"不存在。
 *
 * <h2>能做到的最通用形态：三段式解析（本类）</h2>
 * <ol>
 *   <li><b>先问模组自己</b>——反射它**已经公开**的取值口。伊卡洛斯之翼就把答案摆在外面：
 *       {@code AbstractWings.getType()} → {@code IWingsType/WingsType.getTexture()} /
 *       {@code getTextureReversed()}（javap 实证两树都是 public、都返回 {@code ResourceLocation}）。
 *       本类用**鸭子类型**探测：只要这件物品有一个无参 {@code getType()}，且其返回对象有一个
 *       返回 {@code ResourceLocation} 的无参 {@code getTexture()}，就采信它。**不写死任何类名**——
 *       所以该模组日后再加翅膀物品，我们零改动即可画对。另探测物品自身可能公开的
 *       {@code getElytraTexture(ItemStack[, LivingEntity])}（同样是"作者主动告诉我们"的口径）。</li>
 *   <li><b>问不到再查静态表</b>（{@link #TEXTURE}）——只为"贴图藏在私有图层里、没有公开取值口"
 *       的模组保留（神秘遗物系：它的 {@code TEXTURE_MAP} 是 private static，反射它等于抄实现，
 *       太脆）。这张表已降级为**覆盖/兜底**，不再是主要来源。</li>
 *   <li><b>仍认不出退回原版</b> {@code textures/entity/elytra.png}——绝不画错，只是外观是原版的。</li>
 * </ol>
 * 效果：已支持模组以后新增翅膀零代码；换一个全新模组仍需加一条解析规则（或它恰好符合第 1 档的
 * 鸭子类型就也不用加）。在没有共享 API 的前提下，这是能达到的最通用的程度。
 *
 * <h2>已收录（逐条反编译核实）</h2>
 * <b>伊卡洛斯之翼</b>（{@code locusazzurro_icaruswings}）：第 1 档反射即可全覆盖
 * （{@code textures/entity/<名字>_wings.png}，空域系另有 {@code _reversed}）；下面静态表里那几条
 * **是冗余的安全网**（万一某版本反射失手仍能画对），不是必须。
 * <br><b>神秘遗物系</b>：第 2 档静态表（无公开取值口，见上）。两树命名空间不同——
 * 1.20.1 是 {@code enigmaticlegacy}（壮丽鞘翅 {@code textures/models/misc/elytra.png}）+
 * {@code enigmaticaddons}（混沌之傲 {@code textures/item/3d/chaos_elytra.png}）；
 * 1.21.1 统一成 {@code enigmaticlegacyplus}（{@code textures/models/misc/majestic_elytra.png} /
 * {@code chaos_elytra.png}）。
 *
 * <h2>这张表与"自推鞘翅资格表"互相独立</h2>
 * 能不能自己飞由 {@code combat.selfWingsItems} 管；长什么样由本类管——一件普通鞘翅（羽毛系）
 * 也能有自己的外观，只是它不会自推。
 */
@OnlyIn(Dist.CLIENT)
public final class MaidWingSkins {

    /** namespace 前缀：伊卡洛斯的翅膀贴图都在 {@code textures/entity/} 下 */
    private static final String IW = "locusazzurro_icaruswings";
    /** 1.20.1 神秘遗物本体（壮丽鞘翅）——贴图在 {@code textures/models/misc/} 下 */
    private static final String EL = "enigmaticlegacy";
    /** 1.20.1 神秘遗物扩展（混沌之傲）——贴图在 {@code textures/item/3d/} 下 */
    private static final String EA = "enigmaticaddons";

    /** 认不出型号时的默认（与原版 {@code ElytraLayer.getElytraTexture} 的默认同文件） */
    private static final ResourceLocation VANILLA =
            new ResourceLocation("textures/entity/elytra.png");

    /** 【第 2 档】物品 id → 翅膀贴图（只收"没有公开取值口"的模组；见类注释） */
    private static final Map<String, ResourceLocation> TEXTURE = new HashMap<>();
    /** "有反向贴图"的物品 id（伊卡洛斯空域系 6 件：滑翔时用 {@code _reversed}） */
    private static final Set<String> REVERSED = new HashSet<>();

    // ── 【第 1 档】反射解析的缓存（避免每帧重扫方法）──
    /** 物品类 → 已解析出的取值口；只在第一次见到该类时解析一次 */
    private static final Map<Class<?>, Accessor> ACCESSOR = new ConcurrentHashMap<>();
    /** 已确认"没有可取值的公开口"的物品类（负缓存，避免每帧重试反射） */
    private static final Set<Class<?>> NO_ACCESSOR =
            Collections.newSetFromMap(new ConcurrentHashMap<>());

    static {
        // ── 伊卡洛斯之翼：静态表是第 1 档反射的**冗余安全网**（见类注释）──
        // 羽毛系 / 纸翼 / 魔法翼 / 贤者之石翼（普通鞘翅，也各自有外观）
        TEXTURE.put(IW + ":feather_wings", rl(IW, "textures/entity/feather_wings.png"));
        TEXTURE.put(IW + ":colored_feather_wings", rl(IW, "textures/entity/colored_feather_wings.png"));
        TEXTURE.put(IW + ":golden_feather_wings", rl(IW, "textures/entity/golden_feather_wings.png"));
        TEXTURE.put(IW + ":paper_wings", rl(IW, "textures/entity/paper_wings.png"));
        TEXTURE.put(IW + ":magic_wings", rl(IW, "textures/entity/magic_wings.png"));
        TEXTURE.put(IW + ":flandre_magic_wings", rl(IW, "textures/entity/philosopher_stone_wings.png"));
        // 空域系 6 件（自推 + 反向贴图）
        synapse(IW + ":ikaros_wings", "synapse_alpha");
        synapse(IW + ":nymph_wings", "synapse_beta");
        synapse(IW + ":astraea_wings", "synapse_delta");
        synapse(IW + ":chaos_wings", "synapse_epsilon");
        synapse(IW + ":hiyori_wings", "synapse_zeta");
        synapse(IW + ":melan_wings", "synapse_theta");
        // ── 神秘遗物（1.20.1）：无公开取值口，只能静态收录 ──
        TEXTURE.put(EL + ":enigmatic_elytra", rl(EL, "textures/models/misc/elytra.png"));
        TEXTURE.put(EA + ":chaos_elytra", rl(EA, "textures/item/3d/chaos_elytra.png"));
    }

    private MaidWingSkins() {
    }

    /** "所有模式下渲染 + 用自有外观"总开关。开 = 各图层不要求 {@code isFlightVisual}；关 = 退回旧行为。 */
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
        return new ResourceLocation(namespace, path);
    }

    /**
     * 胸甲槽这件鞘翅该用哪张贴图——三段式：先问模组自己（反射）→ 再查静态表 → 退回原版。
     *
     * <p>注意：**没有**对第 1 档结果做缓存——{@code getElytraTexture(ItemStack,...)} 这类口
     * 允许按 ItemStack 的 NBT 给不同贴图（同一物品类不同皮肤），按类缓存会画错。每帧反射调用
     * 一次的成本极低（方法引用早已解析），静态表那一路本来就不缓存。
     *
     * @param chest 胸甲槽物品栈（调用方已确认它能渲染）
     * @param maid  穿着者（判"正在滑翔"决定要不要用反向贴图）；null 时不取反向
     * @return 那件鞘翅自己的贴图；认不出型号 → 原版鞘翅贴图
     */
    public static ResourceLocation textureFor(ItemStack chest, EntityMaid maid) {
        try {
            if (chest == null || chest.m_41619_()) {
                return VANILLA;
            }
            boolean gliding = gliding(maid);
            // 第 1 档：问模组自己（作者主动公开的取值口）
            ResourceLocation reflected = reflect(chest, maid, gliding);
            if (reflected != null) {
                return reflected;
            }
            // 第 2 档：静态表（只有"没有公开取值口"的模组才落在这里）
            String id = itemId(chest);
            if (id == null) {
                return VANILLA;
            }
            ResourceLocation t = table(id, gliding);
            // 第 3 档：原版
            return t != null ? t : VANILLA;
        } catch (Throwable ignored) {
            return VANILLA; // 认不出来就用原版贴图——绝不能因为贴图查表而让整层渲染抛掉
        }
    }

    /* ---------------- 第 1 档：反射模组自己的公开取值口 ---------------- */

    private static ResourceLocation reflect(ItemStack chest, EntityMaid maid, boolean gliding) {
        Item item = chest.m_41720_();
        Class<?> c = item.getClass();
        if (NO_ACCESSOR.contains(c)) {
            return null; // 已知没有公开口，别再扫
        }
        Accessor acc = ACCESSOR.get(c);
        if (acc == null) {
            acc = resolveAccessor(item);
            if (acc == null) {
                NO_ACCESSOR.add(c);
                return null;
            }
            ACCESSOR.put(c, acc);
        }
        // 每次实调：允许按 ItemStack 的 NBT 给出不同贴图（见 textureFor 的说明）
        return acc.texture(chest, maid, gliding);
    }

    /**
     * 给这件物品找一个"自己会告诉你贴图"的公开口。全部用**方法名鸭子类型**，不写死类名。
     *
     * @return 找到的取值口；没有 → null（调用方会走静态表）
     */
    private static Accessor resolveAccessor(Item item) {
        Class<?> c = item.getClass();
        // 档 1a：getType() → getTexture() / getTextureReversed()（伊卡洛斯之翼的形态）
        try {
            Method getType = c.getMethod("getType");
            if (getType.getParameterCount() == 0) {
                Object type = getType.invoke(item);
                if (type != null) {
                    Method gt = findRLMethod(type.getClass(), "getTexture");
                    if (gt != null) {
                        Method gr = findRLMethod(type.getClass(), "getTextureReversed");
                        return new TypeAccessor(type, gt, gr);
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        // 档 1b：物品自身公开的 getElytraTexture(ItemStack[, LivingEntity])
        Method e1 = findMethod(c, "getElytraTexture",
                new Class<?>[]{ItemStack.class});
        if (e1 != null) {
            return new ItemAccessor(e1);
        }
        Method e2 = findMethod(c, "getElytraTexture",
                new Class<?>[]{ItemStack.class, net.minecraft.world.entity.LivingEntity.class});
        if (e2 != null) {
            return new ItemAccessor(e2);
        }
        return null;
    }

    /** 无参、返回 {@link ResourceLocation} 的公开方法；找不到 → null */
    private static Method findRLMethod(Class<?> c, String name) {
        return findMethod(c, name, new Class<?>[0]);
    }

    /** 指定参数、返回 {@link ResourceLocation} 的公开方法；找不到 → null */
    private static Method findMethod(Class<?> c, String name, Class<?>[] params) {
        try {
            Method m = c.getMethod(name, params);
            if (!ResourceLocation.class.isAssignableFrom(m.getReturnType())) {
                return null;
            }
            try {
                m.setAccessible(true); // 实现类可能不是 public；能开就开
            } catch (Throwable ignored) {
            }
            return m;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 一个"能问出贴图"的口 */
    private interface Accessor {
        /** @return 该物品想要的翅膀贴图；问不出来 → null */
        ResourceLocation texture(ItemStack chest, EntityMaid maid, boolean gliding);
    }

    /** 形态：{@code item.getType().getTexture()/getTextureReversed()}（伊卡洛斯之翼等） */
    private static final class TypeAccessor implements Accessor {
        private final Object type;
        private final Method texture;
        private final Method reversed; // 可为 null（该模组没有反向贴图）

        private TypeAccessor(Object type, Method texture, Method reversed) {
            this.type = type;
            this.texture = texture;
            this.reversed = reversed;
        }

        @Override
        public ResourceLocation texture(ItemStack chest, EntityMaid maid, boolean gliding) {
            try {
                // 滑翔时优先反向贴图（模组自己的 {@code getTextureReversed} 在无反向时会返回正向，
                // 所以这里直接调它是安全的）
                Object r = (gliding && reversed != null) ? reversed.invoke(type) : texture.invoke(type);
                return r instanceof ResourceLocation rl ? rl : null;
            } catch (Throwable ignored) {
                return null;
            }
        }
    }

    /** 形态：物品自身的 {@code getElytraTexture(ItemStack[, LivingEntity])} */
    private static final class ItemAccessor implements Accessor {
        private final Method method;

        private ItemAccessor(Method method) {
            this.method = method;
        }

        @Override
        public ResourceLocation texture(ItemStack chest, EntityMaid maid, boolean gliding) {
            try {
                Object r = (method.getParameterCount() == 1)
                        ? method.invoke(chest.m_41720_(), chest)
                        : method.invoke(chest.m_41720_(), chest, maid);
                return r instanceof ResourceLocation rl ? rl : null;
            } catch (Throwable ignored) {
                return null;
            }
        }
    }

    /**
     * 这件鞘翅有没有"它自己的贴图"（三段式里前两档命中）。
     *
     * <p>给图层用：返回 false 时说明"它只是能滑翔、但外观只能退回原版"——图层据此留一行痕
     * （{@code MaidFlightKit.noteWingVanillaFallback}），方便实测时区分"没画错、只是外观是原版的"
     * 与"渲染链路趴了"。**原版鞘翅本身也返回 false**（它用原版贴图天经地义），要留痕请配合
     * {@link #isVanillaElytra} 一起判。
     */
    public static boolean hasOwnTexture(ItemStack chest, EntityMaid maid) {
        return textureFor(chest, maid) != VANILLA;
    }

    /** 这就是原版鞘翅吗（它的原版贴图是"应有外观"，不是"退而求其次"，不该报"没能查到自有贴图"）。
     *  按注册名判（{@code minecraft:elytra}），不写死 SRG 字段名。 */
    public static boolean isVanillaElytra(ItemStack chest) {
        return "minecraft:elytra".equals(itemId(chest));
    }

    /* ---------------- 第 2 档：静态表 ---------------- */

    private static ResourceLocation table(String id, boolean gliding) {
        if (gliding && REVERSED.contains(id)) {
            ResourceLocation base = TEXTURE.get(id);
            if (base != null) {
                return rl(base.m_135827_(), base.m_135815_().replace("_wings.png", "_wings_reversed.png"));
            }
        }
        return TEXTURE.get(id);
    }

    /* ---------------- 工具 ---------------- */

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
            ResourceLocation key = ForgeRegistries.ITEMS.getKey(stack.m_41720_());
            return key == null ? null : key.toString();
        } catch (Throwable t) {
            return null;
        }
    }
}
