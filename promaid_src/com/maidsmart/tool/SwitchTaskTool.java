package com.maidsmart.tool;

import com.github.tartaricacid.touhoulittlemaid.ai.agent.tool.ITool;
import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.LLMCallback;
import com.github.tartaricacid.touhoulittlemaid.ai.service.function.schema.parameter.ObjectParameter;
import com.github.tartaricacid.touhoulittlemaid.ai.service.function.schema.parameter.Parameter;
import com.github.tartaricacid.touhoulittlemaid.ai.service.function.schema.parameter.StringParameter;
import com.github.tartaricacid.touhoulittlemaid.api.task.IMaidTask;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.github.tartaricacid.touhoulittlemaid.entity.task.TaskManager;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * v1.2.2 实测五百七十八：smart_switch_task —— 「换模式 / 换工作」的通用指挥工具。
 *
 * ── 为什么需要它 ──
 * 在此之前 LLM 只能"打怪"（smart_attack 会顺手切攻击任务），别的模式一律够不到：玩家说
 * "你去挖矿吧""切空袭""跟着我别干活了"，模型只能回话、动不了手。本工具把**任务表**交到模型
 * 手里：TLM 原生任务（攻击/弓兵/弩兵/三叉戟/弹幕/农场/钓鱼/喂食/繁殖/采蜜/牛奶/剪毛/火把/
 * 灭火/清雪/甘蔗/可可/瓜类/花草/游戏/空闲）+ 本模组任务（挖矿/砍树/做饭/酿造/宰杀/建造/索引
 * 建造/近战空袭/远程空袭）全部可切。
 *
 * ── 参数写法（模型友好）──
 * {@code task} 三种写法都认：完整 id（{@code maid_smart:mine}）、裸 id（{@code mine}，
 * 缺省命名空间按 TLM 补）、**别名**（中文或英文关键词：空袭 / 远程空袭 / 挖矿 / 砍树 /
 * 建造 / 攻击 / 弓兵 / 待命 …）。写错时不是静默失败，而是把**可用 id 列表**回给模型让它重试
 * （{@link ITool#invalidParam} 同款口径）。
 *
 * ── 两条安全口径（与 smart_attack 一致）──
 * ① 排班中的女仆任务由日程表管理 → 拒绝外部指派（否则日程与指挥互相打架）；
 * ② 切到**空袭**任务时顺手把"缺件"报给模型（鞘翅/武器/燃料三件套），让它能转告玩家
 *    ——"她说能飞"和"她真能飞"是两件事，缺件时空袭会退回地面战斗。
 */
public class SwitchTaskTool implements ITool<SwitchTaskTool.Result> {
    public static final String TOOL_ID = "smart_switch_task";

    private static final String TASK_PARAM = "task";
    private static final String TARGET_PARAM = "target";

    private static final String TOOL_DESC =
            "Use this when the user wants the maid to change her work mode / task, e.g. "
            + "'go mining', 'cut trees', 'start the air raid', 'fight that mob', 'follow me and stop working'.\n"
            + "Pass task as a task id (e.g. 'maid_smart:mine', 'touhou_little_maid:farm') or a keyword alias "
            + "(air_raid / air_raid_ranged / mine / wood / build / cook / brew / slaughter / farm / fishing / "
            + "feed / torch / attack / bow / crossbow / trident / idle).\n"
            + "Optional target (only for combat tasks): nearest = closest hostile creature, "
            + "owner_target = what the user is fighting, attacker = what recently hit the maid.\n"
            + "Returns the task she is now on, plus a warning if a flight task is missing gear.";

    /** 别名表：关键词 → 任务 id（两树共用一份，值就是 ResourceLocation 的 ns:path） */
    private static final Map<String, String> ALIAS = new LinkedHashMap<>();

    static {
        // ---- 本模组：空袭（近战/远程）----
        alias("空袭", "maid_smart:flight_combat");
        alias("近战空袭", "maid_smart:flight_combat");
        alias("air_raid", "maid_smart:flight_combat");
        alias("air_raid_melee", "maid_smart:flight_combat");
        alias("flight", "maid_smart:flight_combat");
        alias("远程空袭", "maid_smart:flight_ranged");
        alias("air_raid_ranged", "maid_smart:flight_ranged");
        alias("flight_ranged", "maid_smart:flight_ranged");
        // ---- 本模组：生产类 ----
        alias("挖矿", "maid_smart:mine");
        alias("mine", "maid_smart:mine");
        alias("挖矿模式", "maid_smart:mine");
        alias("砍树", "maid_smart:woodcut");
        alias("wood", "maid_smart:woodcut");
        alias("woodcut", "maid_smart:woodcut");
        alias("伐木", "maid_smart:woodcut");
        alias("做饭", "maid_smart:cook");
        alias("cook", "maid_smart:cook");
        alias("酿造", "maid_smart:brew");
        alias("brew", "maid_smart:brew");
        alias("宰杀", "maid_smart:slaughter");
        alias("屠宰", "maid_smart:slaughter");
        alias("slaughter", "maid_smart:slaughter");
        alias("建造", "maid_smart:build");
        alias("build", "maid_smart:build");
        alias("索引建造", "maid_smart:index_build");
        alias("index_build", "maid_smart:index_build");
        // ---- TLM 原生：战斗 ----
        alias("攻击", "touhou_little_maid:attack");
        alias("近战", "touhou_little_maid:attack");
        alias("attack", "touhou_little_maid:attack");
        alias("melee", "touhou_little_maid:attack");
        alias("弓兵", "touhou_little_maid:ranged_attack");
        alias("弓", "touhou_little_maid:ranged_attack");
        alias("bow", "touhou_little_maid:ranged_attack");
        alias("弩兵", "touhou_little_maid:crossbow_attack");
        alias("弩", "touhou_little_maid:crossbow_attack");
        alias("crossbow", "touhou_little_maid:crossbow_attack");
        alias("三叉戟", "touhou_little_maid:trident_attack");
        alias("trident", "touhou_little_maid:trident_attack");
        alias("弹幕", "touhou_little_maid:danmaku_attack");
        alias("danmaku", "touhou_little_maid:danmaku_attack");
        alias("枪械", "touhou_little_maid:gun_attack");
        alias("gun", "touhou_little_maid:gun_attack");
        // ---- TLM 原生：生产/生活 ----
        alias("农场", "touhou_little_maid:farm");
        alias("farm", "touhou_little_maid:farm");
        alias("钓鱼", "touhou_little_maid:fishing");
        alias("fishing", "touhou_little_maid:fishing");
        alias("喂食", "touhou_little_maid:feed");
        alias("feed", "touhou_little_maid:feed");
        alias("繁殖", "touhou_little_maid:feed_animal");
        alias("breed", "touhou_little_maid:feed_animal");
        alias("采蜜", "touhou_little_maid:honey");
        alias("honey", "touhou_little_maid:honey");
        alias("牛奶", "touhou_little_maid:milk");
        alias("milk", "touhou_little_maid:milk");
        alias("剪毛", "touhou_little_maid:shears");
        alias("shears", "touhou_little_maid:shears");
        alias("火把", "touhou_little_maid:torch");
        alias("torch", "touhou_little_maid:torch");
        alias("灭火", "touhou_little_maid:extinguishing");
        alias("extinguish", "touhou_little_maid:extinguishing");
        alias("清雪", "touhou_little_maid:snow");
        alias("snow", "touhou_little_maid:snow");
        alias("甘蔗", "touhou_little_maid:sugar_cane");
        alias("sugar_cane", "touhou_little_maid:sugar_cane");
        alias("可可", "touhou_little_maid:cocoa");
        alias("cocoa", "touhou_little_maid:cocoa");
        alias("瓜类", "touhou_little_maid:melon");
        alias("melon", "touhou_little_maid:melon");
        alias("花草", "touhou_little_maid:grass");
        alias("grass", "touhou_little_maid:grass");
        alias("游戏", "touhou_little_maid:board_games");
        alias("games", "touhou_little_maid:board_games");
        // ---- "别干活了"类：空闲 ----
        alias("待命", "touhou_little_maid:idle");
        alias("空闲", "touhou_little_maid:idle");
        alias("休息", "touhou_little_maid:idle");
        alias("idle", "touhou_little_maid:idle");
        alias("stop", "touhou_little_maid:idle");
    }

    private static void alias(String key, String id) {
        ALIAS.put(key, id);
    }

    private static final Codec<Result> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.STRING.fieldOf(TASK_PARAM).forGetter(Result::task),
            Codec.STRING.optionalFieldOf(TARGET_PARAM, "").forGetter(Result::target)
    ).apply(instance, Result::new));

    @Override
    public String id() {
        return TOOL_ID;
    }

    @Override
    public String summary(EntityMaid maid) {
        return TOOL_DESC;
    }

    @Override
    public Parameter parameters(ObjectParameter root, EntityMaid maid) {
        root.addProperties(TASK_PARAM, StringParameter.create()
                .setDescription("要切换到的任务：任务 id（maid_smart:mine / touhou_little_maid:farm）"
                        + "或关键词别名（空袭/远程空袭/挖矿/砍树/做饭/酿造/宰杀/建造/攻击/弓兵/弩兵/"
                        + "三叉戟/弹幕/枪械/农场/钓鱼/喂食/繁殖/采蜜/牛奶/剪毛/火把/灭火/清雪/待命）"));
        root.addProperties(TARGET_PARAM, StringParameter.create()
                .addEnumValues("", "nearest", "owner_target", "attacker")
                .setDescription("可选（仅战斗类任务）：切过去后顺手锁定的目标。"
                        + "nearest=最近的敌对生物 / owner_target=主人正在打的 / attacker=刚打过她的；"
                        + "非战斗任务留空"));
        return root;
    }

    @Override
    public Codec<Result> codec() {
        return CODEC;
    }

    @Override
    public LLMCallback onCall(String toolId, Result result, LLMCallback callback) {
        if (!com.maidsmart.config.MaidSmartConfig.TOOL_SWITCH_TASK.get()) {
            return callback.addToolResult("切换任务工具已被禁用（设置里可开启）。", toolId);
        }
        EntityMaid maid = callback.getMaid();
        if (!(maid.m_9236_() instanceof ServerLevel level)) {
            return callback.addToolResult("切换任务需要在服务端进行", toolId);
        }
        // 安全口径①：排班中的女仆任务归日程表管（与 smart_attack 同一条）
        if (com.maidsmart.schedule.ScheduleData.isOn(maid)) {
            return callback.addToolResult("该女仆正在排班中，任务由日程表管理——"
                    + "请先在她的排班表里关掉排班，或改排班内容", toolId);
        }
        IMaidTask task = resolve(maid, result.task());
        if (task == null) {
            return callback.addToolResult("认不出任务「" + result.task() + "」。可用任务："
                    + availableIds(maid), toolId);
        }
        StringBuilder sb = new StringBuilder();
        try {
            if (maid.getTask() != task) {
                maid.setTask(task);
            }
            sb.append("已切到「").append(taskName(task)).append("」(").append(task.getUid()).append(")");
        } catch (Throwable t) {
            return callback.addToolResult("切换任务失败：" + t, toolId);
        }
        // 安全口径②：空袭任务顺手报"缺件"，让模型能转告玩家
        try {
            if (com.maidsmart.combat.MaidFlightKit.isFlightUid(task.getUid())) {
                String missing = com.maidsmart.combat.MaidFlightKit.missingParts(maid);
                if (missing == null || missing.isEmpty()) {
                    sb.append("；装备齐了，可以起飞");
                } else {
                    sb.append("；注意她缺「").append(missing).append("」，缺件时空袭会退回地面战斗");
                }
            }
        } catch (Throwable ignored) {
        }
        // 可选目标（仅战斗类任务有意义；取不到就如实说明，不假装锁上了）
        String target = result.target() == null ? "" : result.target().trim();
        if (!target.isEmpty()) {
            LivingEntity picked = ToolKit.pick(maid, level, target);
            if (ToolKit.lock(maid, picked)) {
                sb.append("；已锁定目标 ").append(ToolKit.name(picked));
                ToolKit.bubble(maid, "明白，交给我！");
            } else {
                sb.append("；但没找到合法目标（target=").append(target).append("），她会自己选敌");
            }
        }
        ToolKit.bubble(maid, "好，我这就去" + taskName(task));
        return callback.addToolResult(sb.toString(), toolId);
    }

    @Override
    public java.util.concurrent.CompletableFuture<LLMCallback> onCallAsync(
            String toolCallId, Result result, LLMCallback callback,
            com.github.tartaricacid.touhoulittlemaid.ai.service.llm.LLMClient client) {
        EntityMaid maid = callback.getMaid();
        if (maid.m_9236_().m_5776_()) {
            return java.util.concurrent.CompletableFuture.completedFuture(
                    callback.addToolResult("Cannot run on client side", toolCallId));
        }
        ServerLevel level = (ServerLevel) maid.m_9236_();
        java.util.concurrent.CompletableFuture<LLMCallback> future = new java.util.concurrent.CompletableFuture<>();
        level.m_7654_().execute(() -> {
            try {
                future.complete(onCall(toolCallId, result, callback));
            } catch (Throwable t) {
                future.complete(callback.addToolResult("Tool execution failed: " + t, toolCallId));
            }
        });
        return future;
    }

    // ==================== 解析 ====================

    /**
     * 三种写法都认：完整 id → 别名 → 任务显示名（TLM 的中文/英文名，模型常见的中文直译也能命中）。
     * 都认不出返回 null（调用方把可用列表回给模型）。
     */
    static IMaidTask resolve(EntityMaid maid, String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return null;
        }
        String s = raw.trim().toLowerCase(Locale.ROOT);
        // ① 别名（先查，因为别名里既有中文也有裸 id 关键词）
        String aliased = ALIAS.get(s);
        if (aliased != null) {
            IMaidTask t = byId(aliased);
            if (t != null) {
                return t;
            }
        }
        // ② 完整 id / 裸 id
        ResourceLocation rl = ToolKit.rl(s, "touhou_little_maid");
        if (rl != null) {
            java.util.Optional<IMaidTask> t = TaskManager.findTask(rl);
            if (t.isPresent()) {
                return t.get();
            }
        }
        // ③ 任务显示名（精确 → 包含；只查没被隐藏的任务，避免切到隐藏任务上）
        List<IMaidTask> all = TaskManager.getNotHiddenTaskList(maid);
        for (IMaidTask t : all) {
            if (taskName(t).toLowerCase(Locale.ROOT).equals(s)) {
                return t;
            }
        }
        for (IMaidTask t : all) {
            String name = taskName(t).toLowerCase(Locale.ROOT);
            if (!name.isEmpty() && (name.contains(s) || s.contains(name))) {
                return t;
            }
        }
        return null;
    }

    private static IMaidTask byId(String id) {
        ResourceLocation rl = ToolKit.rl(id, "touhou_little_maid");
        if (rl == null) {
            return null;
        }
        return TaskManager.findTask(rl).orElse(null);
    }

    /** 任务显示名（TLM 走 lang 键，取不到就退回 uid） */
    static String taskName(IMaidTask task) {
        try {
            return task.getName().getString();
        } catch (Throwable ignored) {
            return String.valueOf(task.getUid());
        }
    }

    /** 可用任务 id 列表（给模型自我纠正用；限行数防 token 爆表） */
    private static String availableIds(EntityMaid maid) {
        StringBuilder sb = new StringBuilder();
        int n = 0;
        try {
            for (IMaidTask t : TaskManager.getNotHiddenTaskList(maid)) {
                if (n++ >= 40) {
                    sb.append(" …");
                    break;
                }
                if (sb.length() > 0) {
                    sb.append("、");
                }
                sb.append(t.getUid());
            }
        } catch (Throwable ignored) {
        }
        return sb.toString();
    }

    public record Result(String task, String target) {
    }
}
