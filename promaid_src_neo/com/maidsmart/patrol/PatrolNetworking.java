package com.maidsmart.patrol;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

import java.util.ArrayList;
import java.util.List;

/**
 * v1.3.8【巡逻航图】网络层（1.21.1）；v1.3.9 起是"书 + 标记态 + 界面"这一整套的收口。
 *
 * <p>包一览（**数据读写全在服务端**，客户端 Screen 只管显示与收集操作）：
 * <ul>
 *   <li>{@link OpenPatrolPacket}（S2C）：打开/刷新界面——带上整本书 + 服务端算好的几何校验。</li>
 *   <li>{@link MaidListPacket}（S2C）：附近自家女仆名单（{uuid, 名字, 绑定的轨道id}）。</li>
 *   <li>{@link MarkingStatePacket}（S2C）：标记态（预览据此高亮 + 画橡皮筋牵引线）。</li>
 *   <li>{@link BookOpPacket}（C2S）：新建/删除/改名/选中/删末点/清空轨道（**所有改书动作**）。</li>
 *   <li>{@link BindMaidPacket}（C2S）：把某只女仆绑到某条轨道（routeId 空 = 解绑）。</li>
 *   <li>{@link StartMarkingPacket}（C2S）：开始/结束标记（中键打点的总闸）。</li>
 *   <li>{@link MarkPatrolPointPacket}（C2S）：标记态下打一个标记（**坐标由服务端按玩家自己的
 *       位置取**，不信客户端发来的坐标——这样"打点=你站的地方"这条规格不可能被伪造）。</li>
 *   <li>{@link ConnectPatrolPacket}（C2S）：把首尾接成闭环（真查一遍净空，失败就报原因）。</li>
 *   <li>{@link RequestPatrolPacket}（C2S）：界面开着时定时拉一版（只回几何，不重跑净空）。</li>
 * </ul>
 *
 * <p>【安全】【服务端铁律】带 {@code Screen} 的代码一律在客户端专类（{@code PatrolChartScreen}），
 * 这里的 handle 里只做 {@code ctx.enqueueWork} + 数据读写；开屏由一个纯客户端 lambda 完成。
 *
 * <p>只对**自己的**物品生效（校验玩家手上那一格就是巡逻航图）。标记坐标由**服务端**取玩家
 * 自己的位置（空中/地面都行），客户端从不发坐标；打点与改书都**不**跑净空校验，
 * 「连接」也只卡几何——挡路方块降级为提示（v1.3.9.2，见 {@link PatrolValidation}）。
 */
@EventBusSubscriber(modid = "promaid", bus = EventBusSubscriber.Bus.MOD)
public final class PatrolNetworking {

    private PatrolNetworking() {
    }

    @SubscribeEvent
    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar r = event.registrar("1");
        r.playToClient(OpenPatrolPacket.TYPE,
                StreamCodec.ofMember(OpenPatrolPacket::encode, OpenPatrolPacket::decode),
                OpenPatrolPacket::handle);
        r.playToServer(MarkPatrolPointPacket.TYPE,
                StreamCodec.ofMember(MarkPatrolPointPacket::encode, MarkPatrolPointPacket::decode),
                MarkPatrolPointPacket::handle);
        // v1.3.9：标记态（开始/结束）与它下发到客户端的状态（预览据此高亮 + 画牵引线）
        r.playToServer(StartMarkingPacket.TYPE,
                StreamCodec.ofMember(StartMarkingPacket::encode, StartMarkingPacket::decode),
                StartMarkingPacket::handle);
        r.playToClient(MarkingStatePacket.TYPE,
                StreamCodec.ofMember(MarkingStatePacket::encode, MarkingStatePacket::decode),
                MarkingStatePacket::handle);
        r.playToServer(ConnectPatrolPacket.TYPE,
                StreamCodec.ofMember(ConnectPatrolPacket::encode, ConnectPatrolPacket::decode),
                ConnectPatrolPacket::handle);
        r.playToServer(RequestPatrolPacket.TYPE,
                StreamCodec.ofMember(RequestPatrolPacket::encode, RequestPatrolPacket::decode),
                RequestPatrolPacket::handle);
        // v1.3.9：书操作（新建/删除/改名/选中）+ 女仆名单与绑定/解绑
        r.playToServer(BookOpPacket.TYPE,
                StreamCodec.ofMember(BookOpPacket::encode, BookOpPacket::decode),
                BookOpPacket::handle);
        r.playToServer(BindMaidPacket.TYPE,
                StreamCodec.ofMember(BindMaidPacket::encode, BindMaidPacket::decode),
                BindMaidPacket::handle);
        r.playToClient(MaidListPacket.TYPE,
                StreamCodec.ofMember(MaidListPacket::encode, MaidListPacket::decode),
                MaidListPacket::handle);
    }

    /* ==================== C2S：书操作（新建/删除/改名/选中） ==================== */

    /**
     * v1.3.9【巡逻航图 · 书架操作】玩家原话：「一个航图里面可以保存多个轨道。」
     *
     * <p>动作：{@code create}（新建一条空轨道并选中）/ {@code delete}（删一条）/
     * {@code rename}（改名）/ {@code select}（切换当前编辑的那条）/
     * {@code delpoint}（删这条轨道的最后一个标记）/ {@code delat}（删第 N 个，N 走 text）/
     * {@code clear}（清空这条轨道的全部标记）。
     *
     * <p>【为什么合并成一个包而不是拆开】它们全是"改这本书"的同族小动作、字段也高度重合
     * （都要带 offHand + id），拆开只是多几份样板。动作字符串走 {@code writeUtf}。
     */
    public static class BookOpPacket implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<BookOpPacket> TYPE =
                new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath("maid_smart", "patrol_book_op"));

        public final boolean offHand;
        public final String op;
        public final String id;
        public final String text;

        public BookOpPacket(boolean offHand, String op, String id, String text) {
            this.offHand = offHand;
            this.op = op == null ? "" : op;
            this.id = id == null ? "" : id;
            this.text = text == null ? "" : text;
        }

        public static void encode(BookOpPacket p, FriendlyByteBuf buf) {
            buf.writeBoolean(p.offHand);
            buf.writeUtf(p.op, 16);
            buf.writeUtf(p.id, 64);
            buf.writeUtf(p.text, 256);
        }

        public static BookOpPacket decode(FriendlyByteBuf buf) {
            return new BookOpPacket(buf.readBoolean(), buf.readUtf(16), buf.readUtf(64), buf.readUtf(256));
        }

        public static void handle(BookOpPacket p, IPayloadContext ctx) {
            ctx.enqueueWork(() -> {
                if (!(ctx.player() instanceof ServerPlayer player)) {
                    return;
                }
                ItemStack stack = heldChart(player, p.offHand);
                if (stack == null) {
                    stack = heldChart(player, !p.offHand);
                }
                if (stack == null) {
                    player.sendSystemMessage(Component.literal("§c巡逻航图不在手上"));
                    return;
                }
                PatrolBook book = PatrolChartData.readBook(stack);
                switch (p.op) {
                    case "create" -> {
                        PatrolRoute r = book.create();
                        if (r == null) {
                            player.sendSystemMessage(Component.literal(
                                    "§c一本航图最多 " + PatrolBook.MAX_ROUTES + " 条轨道"));
                            return;
                        }
                        r.setClearance(PatrolCommand.defaultClearance());
                        PatrolChartData.writeBook(stack, book);
                        player.sendSystemMessage(Component.literal("§a已新建一条轨道「"
                                + r.displayName() + "」——进它的管理页点「开始标记」，再拿中键打点"));
                    }
                    case "delete" -> {
                        PatrolRoute gone = book.remove(p.id);
                        if (gone == null) {
                            return;
                        }
                        PatrolChartData.writeBook(stack, book);
                        player.sendSystemMessage(Component.literal("§e已删除轨道「"
                                + gone.displayName() + "」"));
                    }
                    case "rename" -> {
                        PatrolRoute r = book.byId(p.id);
                        if (r == null) {
                            return;
                        }
                        r.setName(p.text);
                        PatrolChartData.writeBook(stack, book);
                        player.sendSystemMessage(Component.literal("§a已改名为「" + r.displayName() + "」"));
                    }
                    case "select" -> {
                        if (!book.select(p.id)) {
                            return;
                        }
                        PatrolChartData.writeBook(stack, book);
                    }
                    // 【v1.3.9.1】删除标记改成服务端动作。
                    // 原来客户端本地改完再整本 SavePatrolPacket 写回——那条路要求客户端那份镜像
                    // 与服务端一致；一旦包的载荷有问题（见 sendBookOnly 的诊断），本地那份就会把
                    // 服务端的数据整本覆盖掉。改成"客户端只发意图"，镜像是从物品读回来的，不会打架。
                    // v1.3.9.2：delpoint = 删最后一个；delat = 删第 N 个（"标记管理"页里逐条删）。
                    case "delpoint" -> {
                        PatrolRoute r = book.byId(p.id);
                        if (r == null || r.size() == 0) {
                            return;
                        }
                        r.removeAt(r.size() - 1);
                        PatrolChartData.writeBook(stack, book);
                        player.sendSystemMessage(Component.literal("§e「" + r.displayName()
                                + "」已删掉最后一个标记（还剩 " + r.size() + " 个）"));
                    }
                    case "delat" -> {
                        PatrolRoute r = book.byId(p.id);
                        if (r == null) {
                            return;
                        }
                        int idx = -1;
                        try {
                            idx = Integer.parseInt(p.text.trim());
                        } catch (Throwable ignored) {
                        }
                        if (idx < 0 || idx >= r.size()) {
                            return;
                        }
                        r.removeAt(idx);
                        PatrolChartData.writeBook(stack, book);
                        player.sendSystemMessage(Component.literal("§e「" + r.displayName()
                                + "」已删掉第 " + (idx + 1) + " 个标记（还剩 " + r.size() + " 个）"));
                    }
                    case "clear" -> {
                        PatrolRoute r = book.byId(p.id);
                        if (r == null) {
                            return;
                        }
                        r.clear();
                        PatrolChartData.writeBook(stack, book);
                        player.sendSystemMessage(Component.literal(
                                "§e已清空「" + r.displayName() + "」的全部标记"));
                    }
                    default -> {
                        return;
                    }
                }
                // 改完统一回推一版（含女仆名单，因为删除/改选中都可能影响"哪些女仆在用"的显示）
                sendBook(player, stack, p.offHand, false);
            });
        }

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /* ==================== S2C：女仆名单 ==================== */

    /**
     * v1.3.9：把"当前有哪些女仆在用这本航图的哪条轨道"发给客户端（界面里的女仆名单用）。
     *
     * <p>只报**这位玩家自己的**女仆（与排班表 {@code ScheduleNetworking.openFor} 同口径），
     * 并带上"她绑的是哪条 id"——界面据此显示"这条轨道被谁用着"，也让玩家在名单里
     * **直接绑定/解绑**。
     *
     * <p>字段：每行 {uuid, 名字, 绑定的轨道id（空=没绑）}，都是字符串。
     */
    public static class MaidListPacket implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<MaidListPacket> TYPE =
                new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath("maid_smart", "patrol_maid_list"));

        public final boolean offHand;
        public final List<String[]> rows;

        public MaidListPacket(boolean offHand, List<String[]> rows) {
            this.offHand = offHand;
            this.rows = rows;
        }

        public static void encode(MaidListPacket p, FriendlyByteBuf buf) {
            buf.writeBoolean(p.offHand);
            buf.writeVarInt(p.rows.size());
            for (String[] row : p.rows) {
                buf.writeUtf(row.length > 0 ? row[0] : "", 64);
                buf.writeUtf(row.length > 1 ? row[1] : "", 128);
                buf.writeUtf(row.length > 2 ? row[2] : "", 64);
            }
        }

        public static MaidListPacket decode(FriendlyByteBuf buf) {
            // 【顺序必须与 encode 一字不差】encode 是 bool → varint → rows；这里上一版写成
            // varint → rows → bool，第一次真发包就报 "found 57 bytes extra whilst reading
            // packet clientbound/minecraft:custom_payload"（字段整体错位 1 字节 + 剩余）。
            boolean offHand = buf.readBoolean();
            int n = buf.readVarInt();
            List<String[]> rows = new ArrayList<>(Math.max(0, Math.min(n, 256)));
            for (int i = 0; i < n && i < 256; i++) {
                rows.add(new String[]{buf.readUtf(64), buf.readUtf(128), buf.readUtf(64)});
            }
            return new MaidListPacket(offHand, rows);
        }

        public static void handle(MaidListPacket p, IPayloadContext ctx) {
            ctx.enqueueWork(() -> com.maidsmart.client.PromaidClientSetup.updatePatrolMaids(p));
        }

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /* ==================== C2S：女仆绑定 / 解绑（界面里点） ==================== */

    /**
     * v1.3.9【界面内绑定/解绑】——玩家原话：「绑定完的女仆如何解绑？」「跟建造模式的女仆管理同款，
     * 可以将女仆绑到某个区块。」
     *
     * <p>旧版解绑只能"潜行 + 右键女仆"，那个手势既不显眼、又踩了两个坑（服务端两条交互入口
     * 只挂了一条；{@code isCrouching} 读的是 Pose、与包里的 shift 位差一拍）。现在改成**在界面里点**：
     * 轨道管理页每行一个「绑定 / 解绑」按钮，动作走这个包——与手册的建造"绑定到区块"完全是同一套。
     *
     * @param maidUuid 目标女仆；{@code routeId} 为空 = 解绑，非空 = 绑到那条轨道（必须是本书里已闭环的那条）
     */
    public static class BindMaidPacket implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<BindMaidPacket> TYPE =
                new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath("maid_smart", "patrol_bind_maid"));

        public final boolean offHand;
        public final String maidUuid;
        public final String routeId;

        public BindMaidPacket(boolean offHand, String maidUuid, String routeId) {
            this.offHand = offHand;
            this.maidUuid = maidUuid == null ? "" : maidUuid;
            this.routeId = routeId == null ? "" : routeId;
        }

        public static void encode(BindMaidPacket p, FriendlyByteBuf buf) {
            buf.writeBoolean(p.offHand);
            buf.writeUtf(p.maidUuid, 64);
            buf.writeUtf(p.routeId, 64);
        }

        public static BindMaidPacket decode(FriendlyByteBuf buf) {
            return new BindMaidPacket(buf.readBoolean(), buf.readUtf(64), buf.readUtf(64));
        }

        public static void handle(BindMaidPacket p, IPayloadContext ctx) {
            ctx.enqueueWork(() -> {
                if (!(ctx.player() instanceof ServerPlayer player)) {
                    return;
                }
                ItemStack stack = heldChart(player, p.offHand);
                if (stack == null) {
                    stack = heldChart(player, !p.offHand);
                }
                if (stack == null) {
                    player.sendSystemMessage(Component.literal("§c巡逻航图不在手上"));
                    return;
                }
                com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid maid =
                        findOwnMaid(player, p.maidUuid);
                if (maid == null) {
                    player.sendSystemMessage(Component.literal("§c找不到那只女仆（不在附近 / 不是你的）"));
                    return;
                }
                // 解绑
                if (p.routeId.isEmpty()) {
                    boolean had = PatrolChartData.bound(maid);
                    PatrolChartData.unbind(maid);
                    PatrolFlight.forget(maid);
                    player.sendSystemMessage(Component.literal(had
                            ? "§e✦ §f已给 §b" + com.maidsmart.tool.PromaidLog.nameOf(maid)
                              + " §f解除巡逻轨道——她回到扫帚模式原本的平时行为"
                            : "§7这只女仆本来就没绑巡逻轨道"));
                    sendBook(player, stack, p.offHand, false);
                    return;
                }
                // 绑定
                PatrolBook book = PatrolChartData.readBook(stack);
                PatrolRoute route = book.byId(p.routeId);
                if (route == null) {
                    player.sendSystemMessage(Component.literal("§c那条轨道已经不在这本航图里了"));
                    return;
                }
                if (route.size() < PatrolRoute.MIN_POINTS || !route.closed()) {
                    // 【v1.3.9.3：强制绑定，门禁降级为提醒】原来这两条是"不绑"的硬拦；按玩家要求
                    // （「连接方面就不要再加入门禁了，强制连接，后果由玩家自己负责。原来那些门禁
                    // 可以作为一个提醒」）改成照绑 + 提醒。注意：她会不会真的飞仍由 PatrolFlight.effective
                    // 判（≥3 点且闭环才生效）——数据先落到她身上，玩家补完点/连接后自然生效。
                    player.sendSystemMessage(Component.literal(route.closed()
                            ? "§e⚠ 「" + route.displayName() + "」只有 " + route.size()
                              + " 个标记（少于 " + PatrolRoute.MIN_POINTS
                              + " 个围不成环）——按你说的先绑上了，但这样她起飞不了"
                            : "§e⚠ 「" + route.displayName() + "」还没连成闭环——按你说的先绑上了，"
                              + "点「连接」之后她才会开始巡逻"));
                }
                PatrolGeometry.Report rep = PatrolValidation.geometryOnly(route);
                if (!PatrolChartData.bind(maid, route)) {
                    player.sendSystemMessage(Component.literal("§c绑定失败（写入实体数据出错，看日志）"));
                    return;
                }
                PatrolFlight.forget(maid);
                player.sendSystemMessage(Component.literal("§a✦ 已把「" + route.displayName() + "」交给 §b"
                        + com.maidsmart.tool.PromaidLog.nameOf(maid) + "§a：" + route.describe()));
                // 原来的几何/半径门禁 → ⚠ 提醒（见 PatrolValidation.warnings）；放在"已交给"之后，
                // 免得玩家把提醒误读成"没绑上"
                for (String line : PatrolValidation.warnings(rep)) {
                    if (route.size() >= PatrolRoute.MIN_POINTS && route.closed()) {
                        player.sendSystemMessage(Component.literal(line));
                    }
                }
                if (!PatrolCommand.radiusAllowed(rep.radius())) {
                    player.sendSystemMessage(Component.literal(String.format(
                            "§e⚠ 轨道半径 %.0f 格超过上限（config: combat.patrol.maxRadius）"
                                    + "——已按你的要求绑上；超远航线她不一定能一直跟得上",
                            rep.radius())));
                }
                String missing = missingFor(maid);
                if (missing != null) {
                    player.sendSystemMessage(Component.literal("§e还差一步才会开始巡逻：" + missing));
                }
                sendBook(player, stack, p.offHand, false);
            });
        }

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * 巡逻要生效还差什么（可读文本；null = 齐了）。只报告、不阻止绑定——
     * 判据与 {@code PatrolFlight.effective} 一一对应。
     */
    private static String missingFor(com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid maid) {
        StringBuilder sb = new StringBuilder();
        if (!com.maidsmart.combat.MaidBroomKit.isBroomTask(maid)) {
            sb.append("把她的任务切成「扫帚模式」");
        }
        if (com.maidsmart.follow.WorkAreaClamp.homeAnchor(maid) == null) {
            if (sb.length() > 0) {
                sb.append("；");
            }
            sb.append("开启她的「在家模式」（巡逻替代的就是守家盘旋）");
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    /** 按 uuid 找**这位玩家的**自家女仆（找不到 / 不是他的 → null） */
    private static com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid findOwnMaid(
            ServerPlayer player, String uuid) {
        try {
            java.util.UUID id = java.util.UUID.fromString(uuid);
            for (var m : ownMaids(player)) {
                if (id.equals(m.getUUID())) {
                    return m;
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** 维度扫描用的巨箱（只取**已加载**区块里的实体，未加载的本来也不该被界面管） */
    private static final net.minecraft.world.phys.AABB WHOLE_DIMENSION =
            new net.minecraft.world.phys.AABB(-3.0E7, -2048.0, -3.0E7, 3.0E7, 2048.0, 3.0E7);

    /**
     * 这位玩家**自己的、活着的**女仆（按"离玩家近→远"排）。
     *
     * <p>【v1.3.9：从 64 格放宽到整个维度】玩家原话：「跟建造模式的女仆管理同款，可以将女仆绑到
     * 某个区块。」手册那套女仆管理扫的就是**全维度**自己的女仆——巡逻这边一开始只扫 64 格，
     * 结果"她在家门口、我站在航图边上"就绑不上。轨道是写在**她身上**的，人在哪都不影响，
     * 所以这里跟手册同口径：全维度、只筛"自己的 + 活着"。
     */
    private static List<com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid> ownMaids(
            ServerPlayer player) {
        try {
            List<com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid> maids =
                    player.level().getEntitiesOfClass(
                            com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid.class,
                            WHOLE_DIMENSION, m -> m.isAlive() && m.getOwner() == player);
            maids.sort(java.util.Comparator
                    .comparingDouble((com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid m)
                            -> m.distanceToSqr(player))
                    .thenComparing(m -> com.maidsmart.tool.PromaidLog.nameOf(m)));
            return maids;
        } catch (Throwable ignored) {
            return new ArrayList<>();
        }
    }

    /* ==================== 服务端：统一的"回推一版界面数据" ==================== */

    /**
     * 把这本书的当前状态 + 女仆名单发给玩家（{@code open=true} 时客户端会开屏）。
     *
     * <p>所有**玩家动作**触发的改书（打开、保存、连接、书操作、打点、绑定）都走这一个出口——
     * 口径只有一处，免得出现"改了数据但界面没刷新"。它带一份女仆名单（那是一次全维度实体扫描，
     * 只在玩家动作时做；0.5 秒一次的定时刷新走 {@link #sendBookOnly}，不带）。
     */
    public static void sendBook(ServerPlayer player, ItemStack stack, boolean offHand, boolean open) {
        try {
            sendBookOnly(player, stack, offHand, open);
            PacketDistributor.sendToPlayer(player, new MaidListPacket(offHand, collectOwnMaids(player)));
        } catch (Throwable ignored) {
        }
    }

    /**
     * 只回"书 + 校验"，**不带女仆名单**——给界面每 0.5 秒的定时刷新用。
     *
     * <p>【为什么单独拆出来】女仆名单要扫全维度自己的女仆（见 {@link #ownMaids}），
     * 每 0.5 秒扫一次没必要：名单只在"玩家自己绑定/解绑"或"女仆进出已加载区块"时才会变，
     * 而前者本来就会推一版带名单的。
     */
    public static void sendBookOnly(ServerPlayer player, ItemStack stack, boolean offHand, boolean open) {
        try {
            // 【只在开屏（右键）时留痕】0.5 秒一次的定时刷新不记，免得把 latest.log 刷满
            if (open) {
                diagSrv(player, stack, "sendBookOnly open=true offHand=" + offHand);
            }
            PatrolBook book = PatrolChartData.readBook(stack);
            PatrolRoute sel = book.selected();
            // 【v1.3.9.2 界面只报几何】净空已不是门槛，界面里也就不再显示"有方块挡路"这类提示
            // （玩家原话："它就一直在显示有方块阻挡"）。净空只在点「连接」那一下回一句黄字提醒，
            // 以及 /maid_smart broom_patrol status 里查。
            PatrolGeometry.Report rep = PatrolValidation.geometryOnly(sel);
            PacketDistributor.sendToPlayer(player, new OpenPatrolPacket(
                    open, offHand, book.save(),
                    new ArrayList<>(rep.problems()), new ArrayList<>(rep.notes()),
                    rep.length(), rep.seconds()));
        } catch (Throwable ignored) {
        }
    }

    /**
     * 这位玩家自己的女仆 → {uuid, 显示名, 绑定的轨道id}。
     *
     * <p>【口径】全维度、自己的、活着的（见 {@link #ownMaids}）——与手册的"建造/女仆管理"那套同源，
     * 因为轨道的正门就是"进轨道管理页把女仆绑上去"。上限 {@code MaidListPacket} 的编码里卡 256 行。
     */
    private static List<String[]> collectOwnMaids(ServerPlayer player) {
        List<String[]> out = new ArrayList<>();
        try {
            for (var m : ownMaids(player)) {
                if (out.size() >= 256) {
                    break;
                }
                PatrolRoute bound = PatrolChartData.readMaid(m);
                out.add(new String[]{m.getUUID().toString(),
                        com.maidsmart.tool.PromaidLog.nameOf(m),
                        bound == null ? "" : bound.id()});
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    /* ==================== S2C：打开界面 ==================== */

    /** 服务端：把整本航图 + 校验发给玩家，客户端据此开屏 */
    public static void openFor(ServerPlayer player, InteractionHand hand) {
        try {
            ItemStack stack = player.getItemInHand(hand);
            boolean offHand = hand == InteractionHand.OFF_HAND;
            // 旧档迁移的落点：如果这本书还是 v1.3.8 的单条结构，readBook 已经把它包成第一条；
            // 这里顺手写回一次，玩家下次打开就不用再迁（幂等）。
            PatrolBook book = PatrolChartData.readBook(stack);
            PatrolChartData.writeBook(stack, book);
            // 【v1.3.9】右键回到界面 = 标记这一段结束（玩家原话："再次通过右击可以回到刚才那个界面，
            // 点击连接"）。先发书、再发"标记已关"——客户端要靠"进来之前还在标记"来决定开在管理页。
            boolean wasMarking = PatrolMarking.active(player);
            String markId = PatrolMarking.routeId(player);
            sendBook(player, stack, offHand, true);
            if (wasMarking) {
                PatrolMarking.stop(player);
                sendMarking(player, false, "");
                if (!markId.isEmpty()) {
                    player.sendSystemMessage(Component.literal(
                            "§7标记已结束——点「§a连接§7」把首尾接成闭环，通过后这条轨道才能用"));
                }
            }
        } catch (Throwable ignored) {
        }
    }

    /** S2C：打开（open=true）或刷新（open=false）轨道编辑界面（带整本书） */
    public static class OpenPatrolPacket implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<OpenPatrolPacket> TYPE =
                new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath("maid_smart", "open_patrol"));

        /**
         * true = 请求开屏（玩家右键物品）；false = 只是刷新（界面开着时定时拉取/书操作后的回推）。
         *
         * <p>【为什么这个字段是必须的】旧版没有它、客户端恒按"开屏"处理，于是玩家按 ESC 关掉
         * 界面之后，**上一次请求的响应（1~2 拍后到）会把编辑器重新弹出来**——新屏又每 10 拍
         * 请求一次，变成"关不掉"。刷新必须与开屏在协议上可区分。
         */
        public final boolean open;
        public final boolean offHand;
        /** 整本书（多条轨道 + 当前选中）—— v1.3.9 起不再只发单条 */
        public final net.minecraft.nbt.CompoundTag book;
        public final List<String> problems;
        public final List<String> notes;
        public final double length;
        public final double seconds;

        public OpenPatrolPacket(boolean open, boolean offHand, net.minecraft.nbt.CompoundTag book,
                                List<String> problems, List<String> notes,
                                double length, double seconds) {
            this.open = open;
            this.offHand = offHand;
            this.book = book;
            this.problems = problems;
            this.notes = notes;
            this.length = length;
            this.seconds = seconds;
        }

        public static void encode(OpenPatrolPacket p, FriendlyByteBuf buf) {
            buf.writeBoolean(p.open);
            buf.writeBoolean(p.offHand);
            buf.writeNbt(p.book);
            writeList(buf, p.problems);
            writeList(buf, p.notes);
            buf.writeDouble(p.length);
            buf.writeDouble(p.seconds);
        }

        public static OpenPatrolPacket decode(FriendlyByteBuf buf) {
            return new OpenPatrolPacket(buf.readBoolean(), buf.readBoolean(), buf.readNbt(),
                    readList(buf), readList(buf), buf.readDouble(), buf.readDouble());
        }

        public static void handle(OpenPatrolPacket p, IPayloadContext ctx) {
            ctx.enqueueWork(() -> com.maidsmart.client.PromaidClientSetup.openPatrolScreen(p));
        }

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /* ==================== C2S：保存（v1.3.9.1 已删除） ==================== */
    //
    // 【为什么删掉 SavePatrolPacket】它原本让客户端"本地改完标记 → 整本写回"。
    // 那条路的前提是"客户端那份镜像和服务端一致"；一旦包的载荷有问题（玩家实测就是这种：
    // 服务端推来的书是空的），客户端就会拿一份**空书**整本覆盖服务端物品，把数据抹掉。
    // 现在删几个标记/清空都走 {@link BookOpPacket} 的 delpoint/clear——客户端只发意图，
    // 数据只由服务端从物品读→改→写，镜像再由物品同步回来。少一条能抹数据的路径。

    /* ==================== C2S：开始 / 结束标记 ==================== */

    /**
     * v1.3.9【开始标记 / 结束标记】——玩家原话：
     * 「在这个轨道上面，可以点击按钮"开始标记"。开始标记之后玩家会暂时退出那个界面，系统也会提示玩家，
     * 这个时候可以开始进行标记了，点击鼠标中键进行标记。注意，如果你没有点击这个开始标记这个方式，
     * 那么你使用中键是没有任何用处的。」
     *
     * <p>所以中键打点的"总闸"在这里：开 = 写 {@link PatrolMarking}，并回一条怎么打点的提示；
     * 关 = 清掉。打点包自己再查一次（见 {@link MarkPatrolPointPacket}），两道判据同源。
     */
    public static class StartMarkingPacket implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<StartMarkingPacket> TYPE =
                new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath("maid_smart", "patrol_marking_start"));

        public final boolean offHand;
        public final String routeId;
        public final boolean start;

        public StartMarkingPacket(boolean offHand, String routeId, boolean start) {
            this.offHand = offHand;
            this.routeId = routeId == null ? "" : routeId;
            this.start = start;
        }

        public static void encode(StartMarkingPacket p, FriendlyByteBuf buf) {
            buf.writeBoolean(p.offHand);
            buf.writeUtf(p.routeId, 64);
            buf.writeBoolean(p.start);
        }

        public static StartMarkingPacket decode(FriendlyByteBuf buf) {
            return new StartMarkingPacket(buf.readBoolean(), buf.readUtf(64), buf.readBoolean());
        }

        public static void handle(StartMarkingPacket p, IPayloadContext ctx) {
            ctx.enqueueWork(() -> {
                if (!(ctx.player() instanceof ServerPlayer player)) {
                    return;
                }
                if (!p.start) {
                    PatrolMarking.stop(player);
                    sendMarking(player, false, "");
                    player.sendSystemMessage(Component.literal("§e已结束标记。"));
                    return;
                }
                Boolean off = chartOffHand(player);
                if (off == null) {
                    PatrolMarking.stop(player);
                    sendMarking(player, false, "");
                    player.sendSystemMessage(Component.literal("§c巡逻航图不在手上——先拿在手里再点「开始标记」"));
                    return;
                }
                ItemStack stack = heldChart(player, off);
                PatrolBook book = PatrolChartData.readBook(stack);
                PatrolRoute route = book.byId(p.routeId);
                if (route == null) {
                    route = book.selected();
                }
                if (route == null) {
                    PatrolMarking.stop(player);
                    sendMarking(player, false, "");
                    player.sendSystemMessage(Component.literal(
                            "§c这本航图里还没有轨道——先在界面里点「＋ 创建一个轨道」"));
                    return;
                }
                // 选中它：之后"当前选中那条"（连接）都跟着它，不会因为别处点过一下就跑掉
                book.select(route.id());
                if (route.size() == 0) {
                    route.setClearance(PatrolCommand.defaultClearance());
                }
                PatrolChartData.writeBook(stack, book);
                PatrolMarking.start(player, route.id());
                sendMarking(player, true, route.id());
                player.sendSystemMessage(Component.literal(
                        "§a✦ 开始标记「" + route.displayName() + "」——你现在可以退出界面去飞了"));
                player.sendSystemMessage(Component.literal(
                        "§f手持航图、飞到位置上，按 §e鼠标中键§f 打一个标记（§7不用潜行§f）"));
                player.sendSystemMessage(Component.literal(
                        "§7打够了：§e再次右键空气§7 回到界面，点「§a连接§7」把首尾接成闭环"));
                sendBook(player, stack, off, false);
            });
        }

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /* ==================== S2C：标记态（客户端预览据此高亮 + 画牵引线） ==================== */

    public static class MarkingStatePacket implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<MarkingStatePacket> TYPE =
                new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath("maid_smart", "patrol_marking_state"));

        public final boolean marking;
        public final String routeId;

        public MarkingStatePacket(boolean marking, String routeId) {
            this.marking = marking;
            this.routeId = routeId == null ? "" : routeId;
        }

        public static void encode(MarkingStatePacket p, FriendlyByteBuf buf) {
            buf.writeBoolean(p.marking);
            buf.writeUtf(p.routeId, 64);
        }

        public static MarkingStatePacket decode(FriendlyByteBuf buf) {
            return new MarkingStatePacket(buf.readBoolean(), buf.readUtf(64));
        }

        public static void handle(MarkingStatePacket p, IPayloadContext ctx) {
            ctx.enqueueWork(() -> com.maidsmart.client.PromaidClientSetup.updatePatrolMarking(p));
        }

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** 服务端：把标记态发给这位玩家（客户端预览用） */
    private static void sendMarking(ServerPlayer player, boolean marking, String routeId) {
        try {
            PacketDistributor.sendToPlayer(player, new MarkingStatePacket(marking, routeId));
        } catch (Throwable ignored) {
        }
    }

    /* ==================== C2S：打点（仅标记态） ==================== */

    /**
     * v1.3.9【中键打点】——玩家原话：
     * 「应该改成直接拿着此物品进行中键的时候就算标记一个标记。」+「如果你没有点击这个开始标记
     * 这个方式，那么你使用中键是没有任何用处的。」
     *
     * <p>v1.3.9.2：一度按玩家意见加过"必须站在地面上"这道闸；随后玩家实测反馈
     * 「玩家在空中使用鼠标中键进行标记还是没用的」——拦住的正是这道闸。现已**撤掉**：
     * 拿着航图 + 处于标记态，空中地面都能记（高度原样记下）。也不再要求潜行，
     * 并且**不看 {@code onGround}**（骑扫帚时那个位本来就不可靠：乘客的 onGround 是从车辆那边
     * 继承/滞后的，站在地上骑扫帚也会读成空中，反之亦然）。
     *
     * <p>⇒ 两道闸：<b>① 必须先"开始标记"</b>（{@link PatrolMarking}）；<b>② 手持航图</b>。
     * 点直接落到"开始标记时那一条"轨道上，不会跑到别的轨道去。坐标由服务端取玩家自己的位置
     * （"打点 = 你此刻的位置"这条规格不可能被伪造）。
     */
    public static class MarkPatrolPointPacket implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<MarkPatrolPointPacket> TYPE =
                new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath("maid_smart", "mark_patrol"));

        public MarkPatrolPointPacket() {
        }

        public static void encode(MarkPatrolPointPacket p, FriendlyByteBuf buf) {
        }

        public static MarkPatrolPointPacket decode(FriendlyByteBuf buf) {
            return new MarkPatrolPointPacket();
        }

        public static void handle(MarkPatrolPointPacket p, IPayloadContext ctx) {
            ctx.enqueueWork(() -> {
                if (!(ctx.player() instanceof ServerPlayer player)) {
                    return;
                }
                // 【第一道闸：标记态】没点过「开始标记」→ 中键没有任何用处（只回一句怎么开始）
                if (!PatrolMarking.active(player)) {
                    player.sendSystemMessage(Component.literal(
                            "§7现在不在标记状态——右键航图进去，在轨道管理里点「§a开始标记§7」后再按中键"));
                    return;
                }
                Boolean off = chartOffHand(player);
                if (off == null) {
                    PatrolMarking.stop(player);
                    sendMarking(player, false, "");
                    player.sendSystemMessage(Component.literal("§c巡逻航图不在手上了，标记已结束"));
                    return;
                }
                // 每次真的收到打点包都留一行（日志搜「巡逻航图」）——空中打点曾被误判过，
                // 有这一行就能一眼看出"包到没到、落点在哪、当时是不是骑着扫帚"。
                try {
                    com.maidsmart.tool.PromaidLog.log("巡逻航图", "srv 收到打点包 off=" + off
                            + " onGround=" + player.onGround()
                            + " riding=" + (player.getVehicle() == null ? "否"
                                    : player.getVehicle().getType().toString())
                            + " pos=" + String.format("%.1f,%.1f,%.1f",
                                    player.getX(), player.getY(), player.getZ()));
                } catch (Throwable ignored) {
                }
                ItemStack stack = heldChart(player, off);
                PatrolBook book = PatrolChartData.readBook(stack);
                String rid = PatrolMarking.routeId(player);
                PatrolRoute route = rid.isEmpty() ? book.selected() : book.byId(rid);
                if (route == null) {
                    // 这条轨道在标记途中被删了 → 干净收尾
                    PatrolMarking.stop(player);
                    sendMarking(player, false, "");
                    player.sendSystemMessage(Component.literal("§c正在标记的那条轨道已经不在这本航图里了，标记已结束"));
                    return;
                }
                // 坐标由服务端取玩家自己的位置——"打点 = 你站的地方"这条规格不可能被伪造
                Vec3 at = new Vec3(player.getX(), player.getY(), player.getZ());
                if (route.size() == 0) {
                    route.setClearance(PatrolCommand.defaultClearance());
                }
                if (!route.add(at)) {
                    player.sendSystemMessage(Component.literal(
                            "§c「" + route.displayName() + "」的标记已满（上限 "
                                    + PatrolRoute.MAX_POINTS + " 个），先删几个或新建一条"));
                    return;
                }
                String rname = route.displayName();
                int n = route.size();
                boolean closedNow = route.closed();
                PatrolChartData.writeItem(stack, route);
                PatrolGeometry.Report rep = PatrolGeometry.geometryReport(route);
                String tail = closedNow ? "" : "§7（加点后闭环失效，打完了记得点「连接」）";
                player.sendSystemMessage(Component.literal(String.format(
                        "§a✦ 「%s」第 %d 个标记已记录 (%.1f, %.1f, %.1f) %s%s",
                        rname, n, at.x, at.y, at.z, tail,
                        rep.problems().isEmpty() ? "" : " §e｜" + rep.problems().get(0))));
                sendBook(player, stack, off, false);
            });
        }

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /* ==================== C2S：连接（闭环 + 障碍校验） ==================== */

    /**
     * 「连接」按钮：把首尾接成闭环。玩家原话——「必须要保证没有障碍物。否则就会提示连接失败。」
     *
     * <p>【v1.3.9.3：强制连接，门禁降级为提醒】玩家原话：「连接方面就不要再加入门禁了，强制连接，
     * 后果由玩家自己负责。原来那些门禁可以作为一个提醒，触犯了以后就提醒一下。」⇒ 这一步**永远**
     * 把 {@code closed} 置真；点数不够 / 坡度太陡 / 自交 / 半径超限都只是事后念一句 ⚠（见 handle）。
     * 净空（挡路方块）从 v1.3.9.2 起就只提示了，飞的时候 {@link PatrolAdapt} 抬一抬、
     * {@code MaidBroomDrive.steerTo} 脱困与危险绕行再兜一道。
     */
    public static class ConnectPatrolPacket implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<ConnectPatrolPacket> TYPE =
                new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath("maid_smart", "connect_patrol"));

        public final boolean offHand;
        public final boolean disconnect;
        /** 哪条轨道（v1.3.9：一本航图里可能有好几条） */
        public final String id;

        public ConnectPatrolPacket(boolean offHand, boolean disconnect, String id) {
            this.offHand = offHand;
            this.disconnect = disconnect;
            this.id = id == null ? "" : id;
        }

        public static void encode(ConnectPatrolPacket p, FriendlyByteBuf buf) {
            buf.writeBoolean(p.offHand);
            buf.writeBoolean(p.disconnect);
            buf.writeUtf(p.id, 64);
        }

        public static ConnectPatrolPacket decode(FriendlyByteBuf buf) {
            return new ConnectPatrolPacket(buf.readBoolean(), buf.readBoolean(), buf.readUtf(64));
        }

        public static void handle(ConnectPatrolPacket p, IPayloadContext ctx) {
            ctx.enqueueWork(() -> {
                if (!(ctx.player() instanceof ServerPlayer player)) {
                    return;
                }
                ItemStack stack = heldChart(player, p.offHand);
                if (stack == null) {
                    stack = heldChart(player, !p.offHand);
                }
                if (stack == null) {
                    player.sendSystemMessage(Component.literal("§c巡逻航图不在手上"));
                    return;
                }
                PatrolBook book = PatrolChartData.readBook(stack);
                PatrolRoute route = book.byId(p.id);
                if (route == null) {
                    player.sendSystemMessage(Component.literal("§c那条轨道已经不在这本航图里了"));
                    return;
                }
                if (p.disconnect) {
                    route.setClosed(false);
                    PatrolChartData.writeBook(stack, book);
                    player.sendSystemMessage(Component.literal("§e已断开「"
                            + route.displayName() + "」的闭环（她不会再按这条轨道巡逻）"));
                    sendBook(player, stack, p.offHand, false);
                    return;
                }
                // 【v1.3.9.3：连接强制，不再有门槛】玩家原话：「连接方面就不要再加入门禁了，
                // 强制连接，后果由玩家自己负责。原来那些门禁可以作为一个提醒，触犯了以后就提醒一下。」
                // ⇒ 标记不够 / 坡度太陡 / 自交 / 半径超限 一律**照连不误**：原来的每一项判据在下面
                //   逐条念成 ⚠ 提醒。能不能飞由她自己兜底（PatrolAdapt 抬升 + steerTo 脱困/绕行）。
                if (!route.viable()) {
                    // 点数不够连"环"都围不出来（PatrolFlight.effective 也会因此不生效），
                    // 但按玩家要求**不拦**——连上、提醒，飞不飞得起来由玩家自己负责。
                    player.sendSystemMessage(Component.literal(
                            "§e⚠ 「" + route.displayName() + "」的标记不够 " + PatrolRoute.MIN_POINTS
                                    + " 个，围不成一个环——按你说的先连上了，但这样她起飞不了"));
                }
                route.setClosed(true);
                // 【v1.3.9.3「连接时让线避开方块」】就地把整条线烘一遍：每个标记该往哪挪算出来
                // 存进 lift（不改 points——玩家打的记号还在原地）。三维避让：先抬、抬不动往
                // 四边挪、四面堵着才往下沉（玩家原话「如果上面被全部封死了，那也可以尝试往左往右
                // 或者往下」）。见 PatrolAdapt.refine / nudge。
                double lifted = PatrolAdapt.refine(player.level(), route, PatrolAdapt.MAX_LIFT);
                PatrolGeometry.Report rep = PatrolValidation.geometryOnly(route);
                PatrolChartData.writeBook(stack, book);
                player.sendSystemMessage(Component.literal(String.format(
                        "§a✔ 「%s」已连接：一圈 %.0f 格、按扫帚全速约 %.0f 秒",
                        route.displayName(), rep.length(), rep.seconds())));
                if (lifted > 0.03) {
                    player.sendSystemMessage(Component.literal(String.format(
                            "§b✦ 已把航线绕开挡路的方块：最多一处挪了 %.0f 格（标记本身没动，"
                                    + "挪的是她实际飞的那条线）", lifted)));
                }
                // 原来的门禁 → 现在的提醒（⚠ 黄色，不拦任何东西）。
                // 点数不足那条上面已经念过（geometryReport 此时只回那一句），不重复。
                if (route.viable()) {
                    for (String line : PatrolValidation.warnings(rep)) {
                        player.sendSystemMessage(Component.literal(line));
                    }
                }
                if (!PatrolCommand.radiusAllowed(rep.radius())) {
                    player.sendSystemMessage(Component.literal(String.format(
                            "§e⚠ 轨道半径 %.0f 格超过上限（config: combat.patrol.maxRadius，0=不限）"
                                    + "——已按你的要求连上；超远航线她不一定能一直跟得上", rep.radius())));
                }
                // 净空**只提示、不拦**：连上了也能飞——PatrolAdapt 抬一抬、她的脱困与危险绕行兜底
                try {
                    List<Vec3> poly = PatrolCurve.polyline(route.points(), true, 0.35);
                    PatrolClearance.Result cres = PatrolClearance.check(player.level(), poly, route.clearance());
                    if (!cres.ok()) {
                        PatrolClearance.Hit h = cres.hits().get(0);
                        player.sendSystemMessage(Component.literal(String.format(
                                "§e⚠ 沿航线查到 %d 处可能有方块挡路（第一处在 %.0f, %.0f, %.0f 是 %s）"
                                        + "——不影响连接，飞的时候她自己会绕开/抬升",
                                cres.hits().size(), h.pos().x, h.pos().y, h.pos().z, h.block())));
                    }
                } catch (Throwable ignored) {
                }
                sendBook(player, stack, p.offHand, false);
            });
        }

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /* ==================== C2S：请求刷新 ==================== */

    /**
     * 编辑界面每 0.5 秒拉一次（见 {@code PatrolChartScreen.tick}）——"打开/保存"时才推是
     * 不够的：玩家可能在界面开着的时候别处改了轨道。这是只读请求，服务端按 {@code offHand}
     * 找手上那格、原样回一版 {@link OpenPatrolPacket}。
     */
    public static class RequestPatrolPacket implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<RequestPatrolPacket> TYPE =
                new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath("maid_smart", "request_patrol"));

        public final boolean offHand;

        public RequestPatrolPacket(boolean offHand) {
            this.offHand = offHand;
        }

        public static void encode(RequestPatrolPacket p, FriendlyByteBuf buf) {
            buf.writeBoolean(p.offHand);
        }

        public static RequestPatrolPacket decode(FriendlyByteBuf buf) {
            return new RequestPatrolPacket(buf.readBoolean());
        }

        public static void handle(RequestPatrolPacket p, IPayloadContext ctx) {
            ctx.enqueueWork(() -> {
                if (!(ctx.player() instanceof ServerPlayer player)) {
                    return;
                }
                ItemStack stack = heldChart(player, p.offHand);
                if (stack == null) {
                    stack = heldChart(player, !p.offHand);
                }
                if (stack == null) {
                    return; // 航图不在手上了（收起来了）→ 不回；界面下一拍自己再问
                }
                // 【只回几何，不重跑净空、也不带女仆名单】净空要逐点扫方块（一条 128 格半径的轨道
                // 是数万次 getCollisionShape），女仆名单要扫全维度实体——而这里每 0.5 秒被问一次，
                // 两样都太贵。定时刷新只要"列表/时长/几何问题"跟着变；真正的净空裁决发生在点「连接」
                // 时与界面里点「绑定」时，名单只在玩家动作时推（见 sendBook vs sendBookOnly）。
                sendBookOnly(player, stack, p.offHand, false);
            });
        }

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /* ==================== 小工具 ==================== */

    /** 玩家手上那一格是不是巡逻航图（不是就返回 null） */
    private static ItemStack heldChart(ServerPlayer player, boolean offHand) {
        try {
            ItemStack s = offHand ? player.getOffhandItem() : player.getMainHandItem();
            return PatrolChartKit.isChart(s) ? s : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * 手上那格航图在不在副手：{@code FALSE} = 主手、{@code TRUE} = 副手、{@code null} = 手上没有。
     * 回推界面/打点都要带上这个位（不然玩家把航图拿在左手时，回推的 offHand 会不对、界面认不出自己那一份）。
     */
    private static Boolean chartOffHand(ServerPlayer player) {
        if (heldChart(player, false) != null) {
            return Boolean.FALSE;
        }
        if (heldChart(player, true) != null) {
            return Boolean.TRUE;
        }
        return null;
    }

    /**
     * 服务端诊断：把"我这次要发出去的那本书到底是从哪件物品、读到什么"记下来。
     *
     * <p>【为什么需要】玩家实测遇到"服务端推过来的书是空的（routes=0），而客户端手上的航图里
     * 明明有 7 条"——两边读的是同一套代码，所以必须把服务端**看到的那件物品**原样记下来
     * （是不是手里那一格、CUSTOM_DATA 里有哪些键、有没有书根标签），才判得出是谁的问题。
     * 日志搜「巡逻航图」。
     */
    private static void diagSrv(ServerPlayer player, ItemStack stack, String where) {
        try {
            net.minecraft.nbt.CompoundTag t = stack.getOrDefault(
                    net.minecraft.core.component.DataComponents.CUSTOM_DATA,
                    net.minecraft.world.item.component.CustomData.EMPTY).copyTag();
            net.minecraft.nbt.CompoundTag mainT = player.getMainHandItem().getOrDefault(
                    net.minecraft.core.component.DataComponents.CUSTOM_DATA,
                    net.minecraft.world.item.component.CustomData.EMPTY).copyTag();
            net.minecraft.nbt.CompoundTag offT = player.getOffhandItem().getOrDefault(
                    net.minecraft.core.component.DataComponents.CUSTOM_DATA,
                    net.minecraft.world.item.component.CustomData.EMPTY).copyTag();
            com.maidsmart.tool.PromaidLog.log("巡逻航图", "srv " + where
                    + " item=" + net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem())
                    + " count=" + stack.getCount()
                    + " isMainHand=" + (stack == player.getMainHandItem())
                    + " isOffHand=" + (stack == player.getOffhandItem())
                    + " keys=" + t.getAllKeys()
                    + " hasBook=" + t.contains(PatrolBook.TAG_ROOT, net.minecraft.nbt.Tag.TAG_COMPOUND)
                    + " hasLegacy=" + t.contains(PatrolBook.TAG_LEGACY_SINGLE, net.minecraft.nbt.Tag.TAG_COMPOUND)
                    + " readRoutes=" + PatrolChartData.readBook(stack).size()
                    + " ｜ mainKeys=" + mainT.getAllKeys()
                    + " mainReadRoutes=" + PatrolChartData.readBook(player.getMainHandItem()).size()
                    + " ｜ offKeys=" + offT.getAllKeys()
                    + " offReadRoutes=" + PatrolChartData.readBook(player.getOffhandItem()).size());
        } catch (Throwable t) {
            com.maidsmart.tool.PromaidLog.log("巡逻航图", "diagSrv 异常: " + t);
        }
    }

    // 【v1.3.9.1 删掉了 sanitize / COORD_LIMIT】那一套是防"客户端伪造坐标/点数"的——但客户端
    // 压根不再往服务端发坐标、也不整本写回了：标记坐标由服务端取玩家自己的位置，点数上限由
    // PatrolRoute.add 挡。留着只会是死代码。

    /** 写字符串列表——**上限与 {@link #readList} 严格一致（都是 64）**，否则超长的一方会多发字节、
     *  对端读不完 → "packet was larger than I expected"。 */
    private static void writeList(FriendlyByteBuf buf, List<String> list) {
        int n = list == null ? 0 : Math.min(list.size(), 64);
        buf.writeVarInt(n);
        for (int i = 0; i < n; i++) {
            String s = list.get(i);
            buf.writeUtf(s == null ? "" : s, 512);
        }
    }

    private static List<String> readList(FriendlyByteBuf buf) {
        int n = buf.readVarInt();
        List<String> out = new ArrayList<>(Math.max(0, Math.min(n, 64)));
        for (int i = 0; i < n && i < 64; i++) {
            out.add(buf.readUtf(512));
        }
        return out;
    }
}
