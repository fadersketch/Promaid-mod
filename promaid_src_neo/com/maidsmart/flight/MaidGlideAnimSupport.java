package com.maidsmart.flight;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.github.tartaricacid.touhoulittlemaid.geckolib3.file.AnimationFile;
import com.github.tartaricacid.touhoulittlemaid.geckolib3.resource.GeckoLibCache;
import net.minecraft.resources.ResourceLocation;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@code elytra_fly} 动画时才生效**，模型没做这条动画就自动退回游泳动作。
 * <p>【为什么必须兜底（玩家实测现场）】
 * <p>根因不在姿势代码，而在**配置与模型不匹配**：他的 {@code promaid-common.toml} 里
 * {@code glideElytraAnimation = true}（该开关源码默认 {@code false}），而他的模型包里
 * **一条 {@code elytra_fly} 都没有**（扫遍 63 个动画 json，命中 0；全盘扫描同样为 0）。
 * 于是一条链全错：
 * <ol>
 *   <li>{@code MaidSwimGlideMixin} 因为「开关开着」而**不再顶游泳位**；</li>
 *   <li>{@code elytra_fly} 状态（priority 1）被选中，TLM 去模型的动画表里按名字取
 *       ——取不到，模型于是**掉回绑定姿势**（各骨骼零旋转）；</li>
 *   <li>而背后展翅与「跟随俯角的前倾」照旧生效（它们只看 {@code isFlightVisual}，
 *       与这个开关无关），俯冲时前倾可达 70 度。</li>
 * </ol> 三层叠在一起，就是玩家看到的"半立着、又被硬掰了一个俯角、还背着展翅"的破碎姿势。 这也解释了为什么 实测八百一十一 改姿势判据毫无作用——他的配置**根本走不进**那一支。 <p>【兜底口径】滑翔动画的二选一从「开关开着就用鞘翅动画」改成 「开关开着 **且这个模型真有这条动画** 才用鞘翅动画，否则用游泳动作」。判据取自 TLM 自己的动画表（{@code GeckoLibCache.getAnimations()}），**与 TLM 取动画是同一张表、 同一个键**——我们说是"有"，TLM 就一定取得出；说"没有"就绝不会去取。 <p>【为什么键取 {@code maid.getModelId()}】TLM 的 {@code GeckoMaidEntity.getAnimationFileLocation()} 就是拿模型 id 去这张表里查 （字节码实证：{@code maidInfo.getModelId()} 命中就直接用，未命中才退回默认 gecko 模型）。 而模型 id 走 {@code DATA_MODEL_ID} 同步，客户端一定拿得到。**查不到 = 不是 Gecko 模型** （Bedrock 模型不在 gecko 动画表里），而 {@code elytra_fly} 这个状态本来就只注册在 Gecko 的 {@code AnimationRegister} 上、Bedrock 那条链永远走不到它——所以"查不到"时 返回"没有"正是我们要的答案，Bedrock 模型也会正确地退回游泳动作。 <p>【为什么不做缓存】查询是「一次 field 读 + 两次 HashMap 查」，比同一个谓词里已有的 工作量小得多；而不缓存就没有"资源包重载后答案过期"的隐患。 <p>【服务端安全】本类引用的是客户端类，所以**每个入口都先看 {@code level().isClientSide()}**， 外加 {@code catch (Throwable)} 双保险——专用服务器上这个分支一次都不会执行， 相关的类也就永远不会被加载（JVM 惰性解析），不会把客户端类拖到服务端。 <p>本类两树各一份：仅 {@code ResourceLocation} 的取法不同（Neo 树 {@code tryParse}， Forge 树没有该方法、手写解析）。
 */
public final class MaidGlideAnimSupport {

    /** 滑翔专属的模型动画名（TLM 的 {@code AnimationRegister} 里没有这一档，由本模组注册）。 */
    private static final String ELYTRA_FLY = "elytra_fly";

    /** 已经报过"该模型没有 elytra_fly，退回游泳动作"的模型 id（每模型一条，免得刷屏）。 */
    private static final Set<String> LOGGED =
            Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());

    private MaidGlideAnimSupport() {
    }

    /**
     * 这个女仆当前用的模型，**有没有** {@code elytra_fly} 这条动画？
     *
     * <p>false 的含义是"用鞘翅动画这条选择对她无效"——调用方应当退回游泳动作。
     * 非客户端、模型 id 为空、TLM 动画表里查不到这个模型、或查得到但没有这条动画，
     * 一律返回 false（失败安全：宁可退回游泳动作，也不让她掉进绑定姿势）。
     */
    public static boolean hasElytraFly(EntityMaid maid) {
        try {
            if (maid == null || !maid.level().isClientSide()) {
                return false; // 专用服务器：一次都不碰客户端类
            }
            String modelId = maid.getModelId();
            if (modelId == null || modelId.isEmpty()) {
                return false;
            }
            ResourceLocation key = parse(modelId);
            if (key == null) {
                return false;
            }
            Map<ResourceLocation, AnimationFile> files =
                    GeckoLibCache.getInstance().getAnimations();
            if (files == null) {
                return false;
            }
            AnimationFile file = files.get(key);
            if (file == null) {
                return false; // 不是 Gecko 模型（Bedrock 模型不在这张表里）
            }
            Map<String, ?> anims = file.animations();
            boolean has = anims != null && anims.containsKey(ELYTRA_FLY);
            if (!has) {
                noteMissing(modelId);
            }
            return has;
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("MaidGlideAnimSupport", ignored);
            return false;
        }
    }

    /** 每模型报一次：开关开着但这个模型没做 elytra_fly，已自动退回游泳动作。 */
    private static void noteMissing(String modelId) {
        if (LOGGED.add(modelId)) {
            com.maidsmart.tool.PromaidLog.log("滑翔动画",
                    "模型 " + modelId + " 没有 elytra_fly 动画 → 该模型滑翔时自动退回游泳动作"
                            + "（配置项 glideElytraAnimation 对它不生效；想用鞘翅动画，"
                            + "需要模型包里做出同名动画）");
        }
    }

    /** 解析「命名空间:路径」形式的模型 id（非法字符由构造器抛异常，这里兜住返回 null）。 */
    private static ResourceLocation parse(String s) {
        try {
            ResourceLocation rl = ResourceLocation.tryParse(s);
            return rl;
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("MaidGlideAnimSupport", ignored);
            return null;
        }
    }
}
