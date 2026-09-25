package com.maidsmart.client;

import com.github.tartaricacid.touhoulittlemaid.client.renderer.entity.EntityMaidRenderer;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import java.util.IdentityHashMap;
import java.util.Map;

/**
 * v1.3.0 实测六百七十三【武装拴绳三期·半透明】：挂在女仆下面时，**把她的模型画成半透明**，
 * 而且**只对"挂着的那位玩家的第一人称"生效**。（1.21.1 NeoForge 版，与 forge 树同源同口径。）
 *
 * 【玩家原话】"在绑定飞行的时候能不能改一下渲染，有的时候还是会被女仆的模型挡到视野。而如果继续
 * 降低模型高度会导致玩家的高度太低容易被打中。渲染的话最好是改成半透明状态。而且仅限绑定玩家的
 * 第 1 视角会展示半透明状态。"
 *
 * ── 为什么必须"换渲染类型"，只压 alpha 不够（javap 实证，两版一致）──
 * 女仆模型走的是 {@code RenderType.entityCutoutNoCull(贴图)}，而它带的透明态是
 * {@code NO_TRANSPARENCY}——**混合根本没开**，顶点 alpha 压到 0.35 也照样不透明。
 * 原版自己的"幽灵渲染"（隐形实体对旁观者可见那一档）用的是
 * {@code RenderType.itemEntityTranslucentCull(贴图)}（{@code LivingEntityRenderer} 的 translucent
 * 分支；它的透明态才是 TRANSLUCENT）**配上顶点 alpha = 0.15**。
 * 本类把这两件事都做上：① 换成同贴图的 itemEntityTranslucentCull；② 把顶点色里的 alpha 乘上配置值。
 *
 * ── 怎么做到"只影响她、只影响这一个视角"──
 * 拦在 {@code EntityMaidRenderer.render(Mob, …)} 的 HEAD（TLM 三条渲染分支——YSM / Gecko / Bedrock——
 * 唯一的入口，实证过）：判定成立时**取消原调用**，用**包了一层的 MultiBufferSource** 重进一次。
 * 原方法体因此只执行一遍（没有 RenderMaidEvent 重发、patpat 重画这类副作用），
 * 她这一遍画出来的所有几何（模型本体 + 各图层）都是半透明的。
 * 判定三条：① 总开关开着且透明度 < 1；② 相机是第一人称；③ 本地玩家正骑着她、且是**拴绳挂上去的**
 * （{@link com.maidsmart.combat.GunnerTetherManager#isGunner}，客户端读 S2C 的挂载表）。
 *
 * ── 贴图怎么来（不写死名单、也不猜名字）──
 * 反射读 RenderType 内部的 {@code CompositeState → EmptyTextureStateShard → Optional<ResourceLocation>}
 * （两版字段布局都实证过；**按字段类型找、不按字段名找**，所以 SRG / 官方名两版通用）。
 * 读出贴图 → 换成 itemEntityTranslucentCull(同一贴图)；本来就是半透明的（模型包自带 alpha 的那种）
 * → 类型原样留着、只压 alpha。反射万一失灵 → 退化成"只把她当前这张贴图的那一档换掉"
 * （贴图由渲染器 getTextureLocation 给出，Gecko 路径实证就是同一个值）；再不行就只压 alpha。
 * **绝不因为渲染而抛异常**：全部包在 try 里，异常时按不透明走。
 */
@OnlyIn(Dist.CLIENT)
public final class MaidGhostRender {

    /** 我们自己发起的那一遍重绘：置位后原方法体不再被拦（递归一趟就够） */
    private static boolean active = false;
    /** RenderType（按贴图记忆化的实例）→ 它的半透明孪生；值是它自己 = "这一档不用换" */
    private static final Map<RenderType, RenderType> TWIN = new IdentityHashMap<>();
    /** 反射字段句柄缓存：**字段类型名** → 字段（按类型找，不看名字，两版通吃） */
    private static final Map<String, java.lang.reflect.Field> FIELDS = new java.util.HashMap<>();
    /** "已经开了混合"的那一档透明态实例（判某个类型要不要换类型）：从原版幽灵类型身上**现取**——
     *  不直接引用 {@code RenderStateShard} 的常量（那一档是 protected 访问），也不写死名字。 */
    private static final Object MISS = new Object();
    private static Object translucentRef = null;
    /** 取参考透明态用的假贴图（RenderType 只是把它存进状态里，不会去读文件） */
    private static final ResourceLocation DUMMY_TEX =
            ResourceLocation.parse("minecraft:textures/misc/white.png");

    private MaidGhostRender() {
    }

    /** 半透明强度（0 = 全透明，1 = 不透明 = 等于关掉这个功能） */
    public static double configAlpha() {
        try {
            return com.maidsmart.config.MaidSmartConfig.COMBAT_TETHER_GHOST_ALPHA.get();
        } catch (Throwable t) {
            return 1.0;
        }
    }

    /**
     * 这一遍渲染要不要幽灵化。
     *
     * @return true = 调用方必须 finally 里调 {@link #end()}，并把 {@link #wrap} 过的 buffer 传下去
     */
    public static boolean begin(EntityMaid maid) {
        try {
            if (active) {
                return false; // 我们自己发起的那一遍：放行到原版
            }
            if (maid == null || !isGhostTarget(maid)) {
                return false;
            }
            active = true;
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 收尾（必须与 begin 配对，放在 finally 里） */
    public static void end() {
        active = false;
    }

    /** 是不是"挂在拴绳下面、玩家正在第一人称看他"的那只女仆 */
    private static boolean isGhostTarget(EntityMaid maid) {
        if (!com.maidsmart.combat.GunnerTetherManager.isEnabled()) {
            return false;
        }
        if (configAlpha() >= 0.999) {
            return false;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.level == null || mc.player == null) {
            return false;
        }
        if (mc.options.getCameraType() != CameraType.FIRST_PERSON) {
            return false; // 第三人称照旧不透明（玩家原话："仅限绑定玩家的第 1 视角"）
        }
        if (mc.player.getVehicle() != maid) {
            return false; // 他没骑着她 = 没挂在下面（牵绳档不幽灵化）
        }
        return com.maidsmart.combat.GunnerTetherManager.isGunner(maid, mc.player);
    }

    /**
     * 包一层 MultiBufferSource：这一遍请求的每个渲染类型都换成"同贴图的半透明孪生"，
     * 顶点 alpha 全部乘上配置值。
     *
     * @param renderer 女仆渲染器（反射失灵时拿它取当前贴图做兜底；null = 不要兜底）
     * @param maid     这次画的女仆
     */
    public static MultiBufferSource wrap(MultiBufferSource src, EntityMaidRenderer renderer, EntityMaid maid) {
        final float mul = (float) configAlpha();
        ResourceLocation tex = null;
        try {
            if (renderer != null && maid != null) {
                tex = renderer.getTextureLocation(maid);
            }
        } catch (Throwable ignored) {
        }
        final RenderType fallbackKey = tex == null ? null : RenderType.entityCutoutNoCull(tex);
        final RenderType fallbackVal = tex == null ? null : RenderType.itemEntityTranslucentCull(tex);
        return type -> {
            RenderType mapped = type;
            try {
                if (fallbackVal != null && type == fallbackKey) {
                    mapped = fallbackVal;
                } else {
                    RenderType twin = twinOf(type);
                    if (twin != null) {
                        mapped = twin;
                    }
                }
            } catch (Throwable ignored) {
            }
            return new GhostVertex(src.getBuffer(mapped), mul);
        };
    }

    /** 这一档的半透明孪生（null = 不用换：本来就是半透明的，或读不出贴图） */
    private static RenderType twinOf(RenderType type) {
        RenderType cached = TWIN.get(type);
        if (cached != null) {
            return cached == type ? null : cached;
        }
        RenderType twin = type;
        try {
            Object state = fieldValue(type, "net.minecraft.client.renderer.RenderType$CompositeState");
            if (state != null && !isTranslucent(state)) {
                Object shard = fieldValue(state,
                        "net.minecraft.client.renderer.RenderStateShard$EmptyTextureStateShard");
                Object tex = shard == null ? null : textureOf(shard);
                if (tex instanceof ResourceLocation loc) {
                    twin = RenderType.itemEntityTranslucentCull(loc);
                }
            }
        } catch (Throwable ignored) {
        }
        if (TWIN.size() > 512) {
            TWIN.clear();
        }
        TWIN.put(type, twin);
        return twin == type ? null : twin;
    }

    /** 这一档的透明态是不是"已经开了混合"（TRANSLUCENT）——是的话类型不用换，只压 alpha */
    private static boolean isTranslucent(Object state) throws Exception {
        Object want = translucentRef();
        if (want == null) {
            return false; // 取不到参考值：不做这个优化（照旧换类型，效果一样）
        }
        Object shard = fieldValue(state, "net.minecraft.client.renderer.RenderStateShard$TransparencyStateShard");
        return shard != null && shard == want;
    }

    /** 参考用的"已混合"透明态：从 {@code itemEntityTranslucentCull}（原版幽灵渲染那一档，
     *  它的透明态就是 TRANSLUCENT，javap 实证）身上读出来。只算一次。 */
    private static Object translucentRef() {
        if (translucentRef == null) {
            Object got = MISS;
            try {
                Object state = fieldValue(RenderType.itemEntityTranslucentCull(DUMMY_TEX),
                        "net.minecraft.client.renderer.RenderType$CompositeState");
                if (state != null) {
                    Object shard = fieldValue(state,
                            "net.minecraft.client.renderer.RenderStateShard$TransparencyStateShard");
                    if (shard != null) {
                        got = shard;
                    }
                }
            } catch (Throwable ignored) {
            }
            translucentRef = got;
        }
        return translucentRef == MISS ? null : translucentRef;
    }

    /** 贴图：EmptyTextureStateShard 里那个 Optional&lt;ResourceLocation&gt; */
    private static Object textureOf(Object shard) {
        for (Class<?> k = shard.getClass(); k != null && k != Object.class; k = k.getSuperclass()) {
            for (java.lang.reflect.Field f : k.getDeclaredFields()) {
                if (f.getType() == java.util.Optional.class) {
                    try {
                        f.setAccessible(true);
                        Object v = f.get(shard);
                        return v instanceof java.util.Optional<?> o ? o.orElse(null) : null;
                    } catch (Throwable ignored) {
                        return null;
                    }
                }
            }
        }
        return null;
    }

    /** 按**字段类型**取字段值（不看名字：SRG 与官方名两版通吃）；取不到返回 null */
    private static Object fieldValue(Object owner, String typeName) throws Exception {
        java.lang.reflect.Field f = FIELDS.get(typeName);
        if (f == null) {
            for (Class<?> k = owner.getClass(); k != null && k != Object.class; k = k.getSuperclass()) {
                for (java.lang.reflect.Field d : k.getDeclaredFields()) {
                    if (d.getType().getName().equals(typeName)) {
                        d.setAccessible(true);
                        FIELDS.put(typeName, d);
                        f = d;
                        break;
                    }
                }
                if (f != null) {
                    break;
                }
            }
            if (f == null) {
                return null;
            }
        }
        return f.get(owner);
    }

    /**
     * 顶点消费者：把每个顶点的 alpha 乘上系数，其余原样转发。
     *
     * <p>1.21.1 的 {@code VertexConsumer} 只有 6 个 abstract 方法（{@code endVertex()} 与
     * {@code defaultColor} 都已在这一版里删掉了），而 {@code setColor(float×4)} 是 default 且
     * **回落到 {@code setColor(int×4)}**（javap 实证：default 体里就是 `fload × 255.0f; f2i;
     * invokeinterface setColor(IIII)`），所以模型与图层传下来的颜色一个都跑不掉。
     */
    private static final class GhostVertex implements VertexConsumer {
        private final VertexConsumer d;
        private final float mul;

        GhostVertex(VertexConsumer delegate, float mul) {
            this.d = delegate;
            this.mul = mul;
        }

        @Override
        public VertexConsumer addVertex(float x, float y, float z) {
            d.addVertex(x, y, z);
            return this;
        }

        @Override
        public VertexConsumer setColor(int r, int g, int b, int a) {
            d.setColor(r, g, b, Math.max(0, Math.min(255, Math.round(a * mul))));
            return this;
        }

        @Override
        public VertexConsumer setUv(float u, float v) {
            d.setUv(u, v);
            return this;
        }

        @Override
        public VertexConsumer setUv1(int u, int v) {
            d.setUv1(u, v);
            return this;
        }

        @Override
        public VertexConsumer setUv2(int u, int v) {
            d.setUv2(u, v);
            return this;
        }

        @Override
        public VertexConsumer setNormal(float x, float y, float z) {
            d.setNormal(x, y, z);
            return this;
        }
    }
}
