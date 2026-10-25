package com.maidsmart.client;

import com.github.tartaricacid.touhoulittlemaid.client.renderer.entity.EntityMaidRenderer;
import com.github.tartaricacid.touhoulittlemaid.entity.item.EntityBroom;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.util.IdentityHashMap;
import java.util.Map;

/**
 * 武装拴绳·半透明：吊在女仆下面 / 跟她同乘或驾驶一把扫帚时，把她的模型与扫帚本体一并画成半透明，而且只
 * 对"那位玩家的第一人称"生效。
 *
 * 【为什么必须换渲染类型】女仆模型走 {@code RenderType.entityCutoutNoCull}，透明态是 {@code NO_TRANSPARENCY}
 * ——**混合根本没开**，只压顶点 alpha 不会透明。原版"幽灵渲染"用 {@code itemEntityTranslucentCull}（透明态
 * TRANSLUCENT）+ 顶点 alpha 0.15；本类两件都做：① 换成同贴图的 itemEntityTranslucentCull；② 顶点色 alpha 乘配置值。
 * 【何时幽灵化】只认**武装拴绳**（{@code GunnerTetherManager#isGunner}，服务端 S2C 状态）：女仆档 = 你骑着她
 * 或与她同骑一把扫帚；扫帚档 = 她骑着它且被拴绳，或你骑的扫帚上坐着被拴绳的她。正常骑扫帚（没拴绳）
 * 一律不透明。三硬门对所有档成立：**总开关开着、透明度 &lt; 1、相机第一人称**——第三人称/别的玩家一律照旧。
 * 【贴图怎么来】反射读 RenderType 内部的 {@code CompositeState → EmptyTextureStateShard → Optional<ResourceLocation>}
 * （**按字段类型找、不按字段名**，两版通用）→ 换成同贴图的 itemEntityTranslucentCull；本就半透明的只压 alpha。
 * 反射失灵时女仆档退化成"只换当前贴图那一档"、扫帚档只压 alpha（视觉不变但绝不崩）；**绝不因渲染抛异常**（全包 try）。
 */
@OnlyIn(Dist.CLIENT)
public final class MaidGhostRender {

    /** 我们自己发起的那一遍重绘：非 0 = 正在画（值 = 画的是哪一类），原方法体不再被拦 */
    private static final int KIND_NONE = 0;
    private static final int KIND_MAID = 1;
    private static final int KIND_BROOM = 2;
    private static int activeKind = KIND_NONE;

    /** RenderType（按贴图记忆化的实例）→ 它的半透明孪生；值是它自己 = "这一档不用换" */
    private static final Map<RenderType, RenderType> TWIN = new IdentityHashMap<>();
    /** 反射字段句柄缓存：**字段类型名** → 字段（按类型找，不看名字，两版通吃） */
    private static final Map<String, java.lang.reflect.Field> FIELDS = new java.util.HashMap<>();
    /** "已经开了混合"的那一档透明态实例（判某个类型要不要换类型）：从原版幽灵类型身上**现取**——
     *  不直接引用 {@code RenderStateShard} 的常量（那一档是 protected 访问），也不写死名字。 */
    private static final Object MISS = new Object();
    private static Object translucentRef = null;
    /** 取参考透明态用的假贴图（RenderType 只是把它存进状态里，不会去读文件） */
    private static final ResourceLocation DUMMY_TEX = new ResourceLocation("minecraft", "textures/misc/white.png");

    private MaidGhostRender() {
    }

    /** 半透明强度（0 = 全透明，1 = 不透明 = 等于关掉这个功能） */
    public static double configAlpha() {
        try {
            return com.maidsmart.config.MaidSmartConfig.COMBAT_TETHER_GHOST_ALPHA.get();
        } catch (Throwable t) {
             com.maidsmart.diag.Diag.swallow("MaidGhostRender", t); return 1.0;
        }
    }

    /**
     * 这一遍渲染要不要幽灵化（女仆）。
     *
     * @return true = 调用方必须 finally 里调 {@link #end()}，并把 {@link #wrap} 过的 buffer 传下去
     */
    public static boolean begin(EntityMaid maid) {
        try {
            if (activeKind != KIND_NONE) {
                return false; // 我们自己发起的那一遍：放行到原版
            }
            if (maid == null || !isGhostTarget(maid)) {
                return false;
            }
            activeKind = KIND_MAID;
            return true;
        } catch (Throwable t) {
             com.maidsmart.diag.Diag.swallow("MaidGhostRender", t); return false;
        }
    }

    /**
     * 这一遍渲染要不要幽灵化（扫帚本体）。
     *
     * @return true = 调用方必须 finally 里调 {@link #end()}，并把 {@link #wrapBroom} 过的 buffer 传下去
     */
    public static boolean beginBroom(EntityBroom broom) {
        try {
            if (activeKind != KIND_NONE) {
                return false;
            }
            if (broom == null || !isGhostBroom(broom)) {
                return false;
            }
            activeKind = KIND_BROOM;
            return true;
        } catch (Throwable t) {
             com.maidsmart.diag.Diag.swallow("MaidGhostRender", t); return false;
        }
    }

    /** 收尾（必须与 begin / beginBroom 配对，放在 finally 里） */
    public static void end() {
        activeKind = KIND_NONE;
    }

    /** 第一人称下的本地玩家（不满足就 null）——两档判据共用的第一道硬门 */
    private static Player firstPersonPlayer() {
        Minecraft mc = Minecraft.m_91087_();
        if (mc == null || mc.f_91073_ == null || mc.f_91074_ == null) {
            return null;
        }
        if (mc.f_91066_.m_92176_() != CameraType.FIRST_PERSON) {
            return null; // 第三人称照旧不透明（）
        }
        return mc.f_91074_;
    }

    /** 是不是"该幽灵化的那只女仆" */
    private static boolean isGhostTarget(EntityMaid maid) {
        if (configAlpha() >= 0.999) {
            return false;
        }
        Player me = firstPersonPlayer();
        if (me == null || !tether(maid, me)) {
            return false;
        }
        // 只认武装拴绳：① 我正骑着她；② 我和她同骑一把扫帚。
        // 正常骑扫帚（没拴绳）一律不半透明——玩家口径见 R1127。
        if (me.m_20202_() == maid) {
            return true;
        }
        return me.m_20202_() instanceof EntityBroom broom && broom.m_20197_().contains(maid);
    }

    /** 是不是"该幽灵化的那把扫帚" */
    private static boolean isGhostBroom(EntityBroom broom) {
        if (configAlpha() >= 0.999) {
            return false;
        }
        Player me = firstPersonPlayer();
        if (me == null) {
            return false;
        }
        // 只认武装拴绳链路：她骑着的扫帚 / 我和拴着的那位同骑的扫帚。
        if (me.m_20202_() instanceof EntityMaid maid && maid.m_20202_() == broom) {
            return tether(maid, me);
        }
        if (me.m_20202_() == broom) {
            for (net.minecraft.world.entity.Entity e : broom.m_20197_()) {
                if (e instanceof EntityMaid maid && tether(maid, me)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 武装拴绳链路（服务端 S2C 状态）——正常骑乘/同乘一律不算。 */
    private static boolean tether(EntityMaid maid, Player me) {
        return com.maidsmart.combat.GunnerTetherManager.isEnabled()
                && com.maidsmart.combat.GunnerTetherManager.isGunner(maid, me);
    }

    /**
     * 包一层 MultiBufferSource：这一遍请求的每个渲染类型都换成"同贴图的半透明孪生"，
     * 顶点 alpha 全部乘上配置值。
     *
     * @param renderer 女仆渲染器（反射失灵时拿它取当前贴图做兜底；null = 不要兜底）
     * @param maid     这次画的女仆
     */
    public static MultiBufferSource wrap(MultiBufferSource src, EntityMaidRenderer renderer, EntityMaid maid) {
        ResourceLocation tex = null;
        try {
            if (renderer != null && maid != null) {
                tex = renderer.getTextureLocation(maid);
            }
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("MaidGhostRender", ignored);
        }
        return wrapFor(src, tex);
    }

    /** 扫帚那一档（没有渲染器兜底贴图：扫帚是 Bedrock 模型，走的是同一套 RenderType，反射够用） */
    public static MultiBufferSource wrapBroom(MultiBufferSource src) {
        return wrapFor(src, null);
    }

    private static MultiBufferSource wrapFor(MultiBufferSource src, ResourceLocation tex) {
        final float mul = (float) configAlpha();
        final RenderType fallbackKey = tex == null ? null : RenderType.m_110458_(tex);
        final RenderType fallbackVal = tex == null ? null : RenderType.m_110467_(tex);
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
            } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("MaidGhostRender", ignored);
            }
            return new GhostVertex(src.m_6299_(mapped), mul);
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
                    RenderType candidate = RenderType.m_110467_(loc);
                    // 【实测八百三十四】只在顶点格式（含图元模式）一致时才换：YSM 那类自带渲染
                    // 类型的模型，格式与 itemEntityTranslucentCull 不同，换了会让模型写顶点时对不上
                    // → 原版渲染中途抛异常 → 姿态栈脏 → 崩游戏（issue #37）。不一致就不换（那一档
                    // 保持原样、不半透明，但绝不崩）。
                    if (samePipeline(candidate, type)) {
                        twin = candidate;
                    }
                }
            }
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("MaidGhostRender", ignored);
        }
        if (TWIN.size() > 512) {
            TWIN.clear();
        }
        TWIN.put(type, twin);
        return twin == type ? null : twin;
    }

    /** 顶点格式与图元模式是否一致——不一致的渲染类型之间**不能**互换（换了会让顶点写不进去） */
    private static boolean samePipeline(RenderType a, RenderType b) {
        try {
            return a.m_110508_() == b.m_110508_() && a.m_173186_() == b.m_173186_();
        } catch (Throwable t) {
             com.maidsmart.diag.Diag.swallow("MaidGhostRender", t); return false;
        }
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
                Object state = fieldValue(RenderType.m_110467_(DUMMY_TEX),
                        "net.minecraft.client.renderer.RenderType$CompositeState");
                if (state != null) {
                    Object shard = fieldValue(state,
                            "net.minecraft.client.renderer.RenderStateShard$TransparencyStateShard");
                    if (shard != null) {
                        got = shard;
                    }
                }
            } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("MaidGhostRender", ignored);
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
                    } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("MaidGhostRender", ignored);
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
     * <p>只重写那几个 **abstract** 方法就够——1.20.1 的 {@code VertexConsumer} 里
     * {@code color(float×4)} / {@code color(int)} / {@code uv2(int)} / {@code overlayCoords(int)}
     * 这些便捷重载**都是 default 且都回落到它们上**（javap 实证：default 体里 invokeinterface
     * 的就是这几个抽象方法），所以模型与图层传下来的颜色一个都跑不掉。
     *
     * <p>【刻意**不**压 {@code defaultColor}】它是"顶点自己没写颜色时才用的默认色"，两版约定
     * 不一致（0~255 / 0~1 各有说法），压错了会把颜色弄脏；而女仆这条链路上没有一个调用点用它
     * （Bedrock 模型走 ModelPart 逐顶点写色、Gecko 走 renderCubesOfBone 逐顶点写色）。
     */
    private static final class GhostVertex implements VertexConsumer {
        private final VertexConsumer d;
        private final float mul;

        GhostVertex(VertexConsumer delegate, float mul) {
            this.d = delegate;
            this.mul = mul;
        }

        @Override
        public VertexConsumer m_5483_(double x, double y, double z) {
            d.m_5483_(x, y, z);
            return this;
        }

        @Override
        public VertexConsumer m_6122_(int r, int g, int b, int a) {
            d.m_6122_(r, g, b, Math.max(0, Math.min(255, Math.round(a * mul))));
            return this;
        }

        @Override
        public VertexConsumer m_7421_(float u, float v) {
            d.m_7421_(u, v);
            return this;
        }

        @Override
        public VertexConsumer m_7122_(int u, int v) {
            d.m_7122_(u, v);
            return this;
        }

        @Override
        public VertexConsumer m_7120_(int u, int v) {
            d.m_7120_(u, v);
            return this;
        }

        @Override
        public VertexConsumer m_5601_(float x, float y, float z) {
            d.m_5601_(x, y, z);
            return this;
        }

        @Override
        public void m_5752_() {
            d.m_5752_();
        }

        @Override
        public void m_7404_(int r, int g, int b, int a) {
            d.m_7404_(r, g, b, a);
        }

        @Override
        public void m_141991_() {
            d.m_141991_();
        }
    }
}
