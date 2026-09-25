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
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import java.util.IdentityHashMap;
import java.util.Map;

/**
 * v1.3.0 实测六百七十三【武装拴绳三期·半透明】+ 实测六百七十四【半透明二期】：吊在女仆下面 /
 * 跟她同乘或驾驶一把扫帚时，**把她的模型与扫帚本体一并画成半透明**，而且**只对"那位玩家的
 * 第一人称"生效**。
 *
 * 【玩家原话】六百七十三："在绑定飞行的时候能不能改一下渲染，有的时候还是会被女仆的模型挡到
 * 视野。而如果继续降低模型高度会导致玩家的高度太低容易被打中。渲染的话最好是改成半透明状态。
 * 而且仅限绑定玩家的第 1 视角会展示半透明状态。" ／ 六百七十四："半透明程度不够，默认应该为
 * 0.1，而且扫帚也应该是半透明的。"
 *
 * ── 为什么必须"换渲染类型"，只压 alpha 不够（javap 实证）──
 * 女仆模型走的是 {@code RenderType.entityCutoutNoCull(贴图)}，而它带的透明态是
 * {@code NO_TRANSPARENCY}——**混合根本没开**，顶点 alpha 压到 0.1 也照样不透明。
 * 原版自己的"幽灵渲染"（隐形实体对旁观者可见那一档）用的是
 * {@code RenderType.itemEntityTranslucentCull(贴图)}（{@code LivingEntityRenderer} 的 translucent
 * 分支；它的透明态才是 TRANSLUCENT）**配上顶点 alpha = 0.15**（同一个类的
 * {@code renderToBuffer(..., flag ? 0.15F : 1.0F)}）。
 * 本类把这两件事都做上：① 换成同贴图的 itemEntityTranslucentCull；② 把顶点色里的 alpha 乘上配置值。
 *
 * ── 什么时候幽灵化（两档，都只看客户端）──
 * <ul>
 *   <li><b>女仆</b>：① 你正骑着她、而且是**拴绳挂上去的**（{@link
 *       com.maidsmart.combat.GunnerTetherManager#isGunner}，客户端读 S2C 的挂载表；空袭档与扫帚档
 *       都算）；② 你坐在一把扫帚上、她也坐在**同一把**上（六百七十二 起的"换座"态：你在驾驶位、
 *       她在第二乘客——这一档没有拴绳链路，判据只能落在"同一载具 + 她是我的人"）。</li>
 *   <li><b>扫帚</b>：① 你正骑着这把扫帚（自己开 / 换座共乘）；② 你吊在骑这把扫帚的女仆下面
 *       （扫帚就在你头顶，同样挡视线）。</li>
 * </ul>
 * 三条硬门对所有档都成立：**总开关开着、透明度 &lt; 1、相机是第一人称**。于是第三人称、别的玩家、
 * 别人骑别的女仆/扫帚，一律照旧不透明。
 *
 * ── 贴图怎么来（不写死名单、也不猜名字）──
 * 反射读 RenderType 内部的 {@code CompositeState → EmptyTextureStateShard → Optional<ResourceLocation>}
 * （1.20.1 与 1.21.1 的字段布局都实证过；**按字段类型找、不按字段名找**，所以 SRG / 官方名两版通用）。
 * 读出贴图 → 换成 itemEntityTranslucentCull(同一贴图)；本来就是半透明的（模型包自带 alpha 的那种）
 * → 类型原样留着、只压 alpha。反射万一失灵 → 女仆那一档退化成"只把她当前这张贴图的那一档换掉"
 * （贴图由渲染器 getTextureLocation 给出，Gecko 路径实证就是同一个值）；扫帚那档没有兜底贴图，
 * 真读不出来就只压 alpha（视觉上等于没变，但绝不崩）。
 * **绝不因为渲染而抛异常**：全部包在 try 里，异常时按不透明走。
 *
 * 【性能】RenderType 是按贴图记忆化的实例（javap 实证走 {@code Util.memoize}），
 * 所以恒等缓存的条目数 = 她/它身上用到的贴图数（个位数）。
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
    private static final ResourceLocation DUMMY_TEX = ResourceLocation.parse("minecraft:textures/misc/white.png");

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
            return false;
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
            return false;
        }
    }

    /** 收尾（必须与 begin / beginBroom 配对，放在 finally 里） */
    public static void end() {
        activeKind = KIND_NONE;
    }

    /** 第一人称下的本地玩家（不满足就 null）——两档判据共用的第一道硬门 */
    private static Player firstPersonPlayer() {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.level == null || mc.player == null) {
            return null;
        }
        if (mc.options.getCameraType() != CameraType.FIRST_PERSON) {
            return null; // 第三人称照旧不透明（玩家原话："仅限绑定玩家的第 1 视角"）
        }
        return mc.player;
    }

    /** 是不是"该幽灵化的那只女仆" */
    private static boolean isGhostTarget(EntityMaid maid) {
        if (configAlpha() >= 0.999) {
            return false;
        }
        Player me = firstPersonPlayer();
        if (me == null) {
            return false;
        }
        // ① 挂着的那只：我正骑着她（空袭档 / 扫帚档都算）。只认拴绳链路——别人骑她一概不动。
        if (me.getVehicle() == maid) {
            return com.maidsmart.combat.GunnerTetherManager.isEnabled()
                    && com.maidsmart.combat.GunnerTetherManager.isGunner(maid, me);
        }
        // ② 同一把扫帚上的那只：我在扫帚上、她也在同一把上（"换座"态 = 我在驾驶位、她在第二乘客）
        if (me.getVehicle() instanceof EntityBroom broom && broom.getPassengers().contains(maid)) {
            return isMyMaid(maid, me);
        }
        return false;
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
        // ① 我正骑着这把扫帚（自己开 / 换座共乘）：扫帚本体就在视野正前方
        if (me.getVehicle() == broom) {
            return true;
        }
        // ② 我吊在她下面、她骑着这把扫帚：扫帚在我头顶，同样挡视线
        if (me.getVehicle() instanceof EntityMaid maid && maid.getVehicle() == broom) {
            return com.maidsmart.combat.GunnerTetherManager.isGunner(maid, me);
        }
        return false;
    }

    /** 她是不是"我的"女仆（同乘扫帚那一档的判据；认主人 UUID，不认实体身份） */
    private static boolean isMyMaid(EntityMaid maid, Player me) {
        try {
            return maid.getOwner() != null && maid.getOwner().getUUID().equals(me.getUUID());
        } catch (Throwable t) {
            return false;
        }
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
        } catch (Throwable ignored) {
        }
        return wrapFor(src, tex);
    }

    /** 扫帚那一档（没有渲染器兜底贴图：扫帚是 Bedrock 模型，走的是同一套 RenderType，反射够用） */
    public static MultiBufferSource wrapBroom(MultiBufferSource src) {
        return wrapFor(src, null);
    }

    private static MultiBufferSource wrapFor(MultiBufferSource src, ResourceLocation tex) {
        final float mul = (float) configAlpha();
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
     * <p>1.21.1 的 {@code VertexConsumer} 一共就 6 个 abstract 方法（javap 实证：
     * addVertex / setColor(int×4) / setUv / setUv1 / setUv2 / setNormal），
     * 便捷重载（setColor(float×4) 等）**都是 default 且回落到这几个上**，
     * 所以模型与图层传下来的颜色一个都跑不掉。
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
