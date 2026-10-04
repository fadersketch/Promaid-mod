package com.maidsmart.patrol;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * v1.3.9【巡逻航图】航迹预览（纯客户端世界渲染）。
 *
 * <p>── 玩家原话（这一版的两条约束）──
 * 「玩家只有在手持航图的时候可以看到这个轨道，不手持的时候看不到。」
 * 「标记成功之后，玩家根本就看不到这个标记长什么样子。能不能采用 replay mod 同款的渲染，
 * 把这个标记渲染出来呢？」
 *
 * <p>⇒ ① 可见性判据 = **主手或副手拿着巡逻航图**（第一句就查），不拿着一条线都不画；
 * ② 每个标记画成**菱形核 + 地面光环 + 竖针**（v1.3.9.2 改版，原来只是一个立方体轮廓——
 * 玩家原话「仅仅是锁一个方块吗？这样一点都不好」），并给首/尾**异色**：
 * <b>首点青绿、末点品红、中间淡紫</b>——首尾本来就是"连接"要接上的两个点，颜色分开才看得清。
 * ③ 曲线画的是**抬升后**那条（见 {@link PatrolAdapt}）：有方块挡着的地方会拱起一道缓坡，
 * 所以你看到的线就是她实际会飞的线。
 *
 * <p>── v1.3.9.2：为什么全都改成"面片"而不是线 ──
 * 玩家原话：「这个标记的线太细了。看着不明显。以及菱形的大小太小。」而 {@code RenderType.lines()}
 * 的线宽是 GL 的 {@code glLineWidth}——现代驱动普遍把它钳到 1px，调不了。所以标记与"选中那条
 * 曲线"改用 {@link #ribbon 面向相机的细长四边形}（宽度是真实世界尺寸），走
 * {@code RenderType.debugQuads()}（javap 实证：POSITION_COLOR + QUADS + 半透明 + 不剔除 + 正常
 * 深度测试）。尺寸也一并放大到 0.48~0.58 格半径。
 *
 * <p>── 还画什么 ──
 * <ul>
 *   <li>这本航图里**全部**轨道（不只选中那条）：选中/正在标记那条亮天蓝（粗带），
 *       别的暗石板蓝（细线，只作背景）；</li>
 *   <li>净空走廊（旧版沿整条轨道在 ±净空 各画一条半透明琥珀虚线）<b>v1.3.9.5 已删</b>——
 *       玩家原话「为什么轨道下面会渲染出影子呢？这不对吧。」：下半截那条就在轨道正下方，
 *       看着正是这条轨道的"影子"；净空值在轨道说明里本来就有，不在地面上再画一份；</li>
 *   <li>正在标记时，从**最后一个标记**到你**当前位置**画一条牵引线（"橡皮筋"），
 *       让你知道下一个点会落在哪、跟上一个点怎么连。</li>
 *   <li>【v1.3.9.3】每个菱形**头上写一行字**：「轨道N 第M点」——玩家原话「每个悬浮的菱形那边
 *       显示的时候都写一下自己是哪个轨道的几号标记」。<b>v1.3.9.7 起改用建造模式同款的世界空间
 *       广告牌字</b>（TLM {@code RenderHelper.renderFloatingText}，见下）。</li>
 * </ul>
 *
 * <p>── 数据从哪来 ──
 * 客户端每 tick 自己读手上的物品（{@link PatrolChartData#readBook}）——物品数据组件是
 * 同步到客户端的，不需要额外发包。缓存按"内容签名"判重，内容没变就不重算样条。
 * 标记态走 S2C 包（{@link #setMarking}）。
 */
public final class PatrolPreviewClient {

    private static boolean registered = false;
    /** 内容签名 → 已算好的折线（避免每帧重算样条） */
    private static final Map<String, List<Vec3>> CACHE = new HashMap<>();

    /**
     * 【v1.3.9.7 标记文字——照搬建造模式那条路】
     *
     * <p>玩家原话：「每个菱形节点在它自己上面渲染出自己属于哪号轨道的第几个节点。具体的渲染方式
     * 就跟建造模式下，创建区块，在区块上面显示自己是位于哪个坐标进行创建的，这个区块建造的名字
     * 是什么一样。」
     *
     * <p>建造模式区块上那行「（建造中）」+「创建于 x, y, z」是 {@code BlueprintAreaPreview}
     * 调 <b>TLM 的 {@code RenderHelper.renderFloatingText(pose, 文本, 世界X, 世界Y, 世界Z, 颜色,
     * 字号, 居中, 纵向偏移, 透视可见)}</b> 画的——javap 实证：那是 <b>世界空间的广告牌字</b>，
     * 传进去的是 <b>绝对世界坐标</b>，方法内部自己减相机位置、并把字面转正对着镜头。它不走 GUI
     * 层、也不做屏幕投影。
     *
     * <p>此前几版我自作聪明改成"投影到屏幕、再用 {@code GuiGraphics.drawString} 画"，还叠了
     * 一套屏幕矩形防重叠——那根本不是建造模式的路子，位置也就对不上（玩家截图里字全飘了）。
     * 现在 <b>全删</b>，一字不差照抄建造模式：同一个 API、同样的参数、同样的"名字一行 + 坐标
     * 一行"两行结构，钉在每个菱形头顶。
     *
     * <p>两个上限：{@link #LABEL_MAX_DIST} 超过这个距离（格）就不画字——一本航图十几条 × 几十个
     * 标记，远景全画会把字糊成一团；{@link #LABEL_MAX} 同一帧最多画这么多条（离相机近的先画）。
     */
    private static final double LABEL_MAX_DIST = 128.0;
    private static final int LABEL_MAX = 64;
    /**
     * 【v1.3.9.8 字号缩小】玩家原话：「渲染出的轨道+序号字体过大（这个好像是两个版本都有的问题）」。
     *
     * <p>建造模式用的是 0.15 / 0.12 —— 那是给**一整片区块**（几十格宽）用的；我们的菱形只有
     * 约 1 格半径，同样字号时字比菱形还大。这里**整体减半**：首行 0.075、次行 0.06
     * （原版一个字高 9px × 0.075 ≈ 0.68 格，仍清晰可读、又不再压住菱形）。
     *
     * <p>行距也随之收：原来是 1.4 格（配 0.15 号字的一行高），现在两行相差约 0.68 格
     * （= 0.50 → −0.175，锚点相对<b>菱形顶点</b>），正好是 0.075 号字的一行高、不叠字。
     */
    private static final float LABEL_SCALE_TITLE = 0.075f;
    private static final float LABEL_SCALE_COORD = 0.060f;
    private static final double LABEL_LINE1_UP = 0.50;
    private static final double LABEL_LINE2_UP = -0.175;

    /** 标记态（S2C 下发）：正在给哪条轨道打点 */
    private static boolean marking = false;
    private static String markingRouteId = "";

    private PatrolPreviewClient() {
    }

    public static void ensureRegistered() {
        if (!registered) {
            registered = true;
            net.neoforged.neoforge.common.NeoForge.EVENT_BUS.register(PatrolPreviewClient.class);
        }
    }

    /** 标记态（服务端 S2C）——预览据此高亮 + 画"橡皮筋" */
    public static void setMarking(boolean on, String routeId) {
        marking = on;
        markingRouteId = routeId == null ? "" : routeId;
    }

    public static boolean marking() {
        return marking;
    }

    public static String markingRouteId() {
        return markingRouteId;
    }

    public static void clear() {
        // 不再持有状态：可见性每帧由"手上拿没拿航图"决定，这里只清缓存（**不**清标记态——
        // 「开始标记」正是先设标记态再关界面，关界面会走到这里）
        CACHE.clear();
    }

    @net.neoforged.bus.api.SubscribeEvent
    public static void onLoggingOut(
            net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent.LoggingOut event) {
        CACHE.clear();
        marking = false;
        markingRouteId = "";
    }

    @net.neoforged.bus.api.SubscribeEvent
    public static void onRender(net.neoforged.neoforge.client.event.RenderLevelStageEvent event) {
        if (event.getStage()
                != net.neoforged.neoforge.client.event.RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) {
            return;
        }
        // 【v1.3.9.7】不再有跨拍状态：文字就在本帧这一拍里画完（照搬建造模式那条路）
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            return;
        }
        // 【可见性：手持航图才画】——玩家原话见类注释
        net.minecraft.world.item.ItemStack main = mc.player.getMainHandItem();
        net.minecraft.world.item.ItemStack off = mc.player.getOffhandItem();
        boolean holding = PatrolChartKit.isChart(main) || PatrolChartKit.isChart(off);
        if (!holding) {
            return;
        }
        net.minecraft.world.item.ItemStack stack = PatrolChartKit.isChart(main) ? main : off;
        PatrolBook book = PatrolChartData.readBook(stack);
        if (book.isEmpty()) {
            return;
        }
        try {
            Vec3 cam = event.getCamera().getPosition();
            PoseStack pose = event.getPoseStack();
            // 【v1.3.9.7】菱形头顶的字：先收集、后统一画（画字要用**没被相机平移过**的
            // 原始 pose——renderFloatingText 内部自己减相机，这点与建造模式完全一致）。
            // 每条 = {x, y(首行锚), z, 距离平方, 首行文本, 次行文本}。声明在 try 外，
            // 因为下面画字的 pose 是 pop 之后那份（不能带着 -cam 平移）。
            List<Object[]> labels = new ArrayList<>();
            pose.pushPose();
            try {
                pose.translate(-cam.x, -cam.y, -cam.z);
                MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
                VertexConsumer vc = buffers.getBuffer(RenderType.lines());
                // 【v1.3.9.2 粗线】玩家原话：「这个标记的线太细了。看着不明显。」
                // lines() 的线宽是 GL 的 glLineWidth，现代驱动普遍钳成 1px，改不了；所以标记
                // 与"选中那条曲线"改用 **面向相机的细长四边形**（ribbon，真·世界宽度）。
                // RenderType 用 debugQuads()：POSITION_COLOR + QUADS + 半透明 + 不剔除 + 正常
                // 深度测试（javap 实证），正好能画带颜色的小面片。
                VertexConsumer quad = buffers.getBuffer(RenderType.debugQuads());
                Matrix4f mat = pose.last().pose();
                String selId = book.selectedId();
                int routeNo = 0;
                for (PatrolRoute r : book.routes()) {
                    routeNo++;
                    boolean sel = r.id().equals(selId);
                    boolean markThis = marking && r.id().equals(markingRouteId);
                    boolean bright = sel || markThis;
                    List<Vec3> poly = polyFor(r, mc.level);
                    if (poly.size() >= 2) {
                        // 曲线本体：选中/正在标记那条画**一条**粗带；其它轨道画细线（只作背景）。
                        // 【v1.3.9.5 去掉"影子"】玩家原话：「为什么轨道下面会渲染出影子呢？这不对吧。」
                        // 两个来源一起删：
                        //   ① 净空走廊——旧版沿整条轨道在 ±净空（默认 1.5 格）各画一条半透明琥珀
                        //      虚线，下半截就在轨道正下方，看上去正是"这条轨道的影子"；净空值在
                        //      轨道说明/管理页本来就有，不需要在地面上再画一份。
                        //   ② 选中那条原先是"粗带 + 1px 细线"叠着画的，细线永远从带子边缘漏出来
                        //      一道浅影；现在亮的那条只画带子，细线只留给没选中的轨道。
                        if (bright) {
                            ribbonPolyline(quad, mat, cam, poly, 0.075, 0.45f, 0.85f, 1.00f, 0.95f);
                        } else {
                            for (int i = 1; i < poly.size(); i++) {
                                line(vc, mat, poly.get(i - 1), poly.get(i), 0.22f, 0.34f, 0.50f, 0.6f);
                            }
                        }
                    }
                    // 【v1.3.9.2 标记渲染】菱形核 + 地面光环 + 竖针；线是 ribbon（真宽度）。
                    // 首点§b青绿、末点§d品红、中间§7淡紫（亮）/灰紫（暗），随时间轻微呼吸。
                    int n = r.size();
                    long gt = mc.level.getGameTime();
                    for (int i = 0; i < n; i++) {
                        Vec3 p = r.points().get(i);
                        boolean first = i == 0;
                        boolean last = i == n - 1;
                        float[] c;
                        if (first) {
                            c = new float[]{0.22f, 0.94f, 0.85f};   // 首点：青绿
                        } else if (last) {
                            c = new float[]{1.00f, 0.37f, 0.66f};   // 末点：品红
                        } else if (bright) {
                            c = new float[]{0.80f, 0.74f, 1.00f};   // 中间（选中）：淡紫
                        } else {
                            c = new float[]{0.52f, 0.48f, 0.68f};   // 中间（其它轨道）：灰紫
                        }
                        float a = bright ? 1.0f : 0.72f;
                        float pulse = 0.86f + 0.14f * (float) Math.sin((gt + i * 7L) * 0.18);
                        // 尺寸：v1.3.9.2 起明显放大（原来 0.24~0.30 太小，玩家反馈"菱形大小太小"）
                        double h = (first || last ? 0.58 : 0.48) * pulse;
                        marker(vc, quad, mat, cam, p, h, c[0], c[1], c[2], a, bright, first, last, gt, i);
                        // 【v1.3.9.7】文字标签：「轨道N · 第M点」+ 坐标——只收近处的，最后统一画。
                        // 文本口径完全按玩家这句来：「自己属于哪号轨道的第几个节点」。
                        double d2 = p.distanceToSqr(cam);
                        if (d2 <= LABEL_MAX_DIST * LABEL_MAX_DIST) {
                            labels.add(new Object[]{p.x, p.y + h + LABEL_LINE1_UP, p.z, d2,
                                    labelTitle(routeNo, r, i, first, last),
                                    labelCoord(i + 1, p)});
                        }
                    }
                    // 正在标记：从最后一个标记到玩家当前位置的"橡皮筋"，末端一个小菱形
                    if (markThis && n > 0) {
                        Vec3 lastPt = r.points().get(n - 1);
                        Vec3 me = new Vec3(mc.player.getX(), mc.player.getY() + 0.2, mc.player.getZ());
                        line(vc, mat, lastPt, me, 0.45f, 0.90f, 1.00f, 0.7f);
                        ribbon(quad, mat, cam, lastPt, me, 0.06f, 0.45f, 0.90f, 1.00f, 0.75f);
                        diamond(quad, mat, cam, me, 0.26, 0.06f, 0.45f, 0.90f, 1.00f, 0.95f);
                    }
                }
                // 立刻把这批面片刷出去（不留给这一帧的批次时机去猜）
                buffers.endBatch(RenderType.debugQuads());
            } finally {
                pose.popPose();
            }
            // 【v1.3.9.7 菱形头顶的字——照搬建造模式那条路】
            // 上面 pose 已经 push→translate(-cam)→…→pop 完，这里拿到的是**没被相机平移过**的
            // 原始 pose；TLM 的 renderFloatingText 内部自己减相机位置、并把字面转正对着镜头
            //（与 BlueprintAreaPreview 画「建造中 / 创建于 x,y,z」用的是同一个方法、同一套参数）。
            // 所以这里传**绝对世界坐标**，与建造模式一字不差。
            if (!labels.isEmpty()) {
                // 离相机近的先画（远的先让位，最多 LABEL_MAX 条）——与旧版同一优先级口径
                labels.sort(java.util.Comparator.comparingDouble(o -> (double) o[3]));
                int shown = 0;
                for (Object[] L : labels) {
                    if (shown >= LABEL_MAX) {
                        break;
                    }
                    shown++;
                    double lx = (double) L[0];
                    double ly = (double) L[1];
                    double lz = (double) L[2];
                    // 首行：哪号轨道的第几个节点（亮白；首/末点另加字）
                    com.github.tartaricacid.touhoulittlemaid.util.RenderHelper.renderFloatingText(
                            pose, (String) L[4], lx, ly, lz, 0xFFFFFF, LABEL_SCALE_TITLE, true, -5.0f, true);
                    // 次行：坐标（暗灰，比首行低约一行高——照建造模式"名字 / 创建于 x,y,z"两行结构，字号更小）
                    com.github.tartaricacid.touhoulittlemaid.util.RenderHelper.renderFloatingText(
                            pose, (String) L[5], lx, ly + LABEL_LINE2_UP - LABEL_LINE1_UP, lz,
                            0xAAAAAA, LABEL_SCALE_COORD, true, -5.0f, true);
                }
            }
            // 缓存别长起来（一本航图几条，签名不多）
            if (CACHE.size() > 32) {
                CACHE.clear();
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * 【v1.3.9.7】首行文本：「轨道N 第M点」——玩家要的就是"自己属于哪号轨道的第几个节点"。
     *
     * <p>{@code routeNo} 按这本航图里 {@link PatrolBook#routes()} 的次序（1 起数，与
     * {@link PatrolChartScreen} 列表页逐行一致——玩家在列表里看到"第 2 条"，世界里那条轨道的
     * 每个菱形就都顶着"轨道2"）。首/末点另缀一个字，连接前一眼能认出接哪两头。
     */
    private static String labelTitle(int routeNo, PatrolRoute r, int i, boolean first, boolean last) {
        String tag = first ? " §a首" : (last ? " §6末" : "");
        return "§b轨道" + routeNo + " §f第" + (i + 1) + "点" + tag;
    }

    /**
     * 【v1.3.9.7】次行文本：坐标——完全照建造模式的「创建于 x, y, z」那行（整数、逗号分隔）。
     * 玩家原话是"显示出自己是位于哪个坐标进行创建的"，所以这里给的是**这个标记自己的**世界
     * 整数坐标（菱形就钉在这个格子顶上）。
     */
    private static String labelCoord(int pointNo, Vec3 p) {
        return "§7#" + pointNo + "  "
                + (int) Math.floor(p.x) + ", " + (int) Math.floor(p.y) + ", " + (int) Math.floor(p.z);
    }

    /**
     * 这条航迹的密采样（按内容签名缓存；0.8 格步长足够画得顺，也不必和运行时那份同精度）。
     *
     * <p>【v1.3.9.2】画的是**抬升后**的那条线：{@link PatrolAdapt#liftAll} 遇到挡路方块会把它
     * 拱起一道缓坡——所以玩家在预览里看到的就是她实际会飞的那条曲线（抬升不改存档，
     * 玩家打的那几颗标记仍在原地）。
     *
     * <p>【v1.3.9.3】点「连接」之后改用**连接那一刻烘好的抬升线**
     * （{@link PatrolRoute#flightPoints()}，见 {@link PatrolAdapt#refine}）——
     * 与运行时飞的是同一条，菱形记号仍画在玩家打的原地。
     */
    private static List<Vec3> polyFor(PatrolRoute r, net.minecraft.world.level.Level level) {
        String sig = r.id() + "|" + r.closed() + "|" + r.size() + "|" + r.points().hashCode()
                + "|" + java.util.Arrays.hashCode(r.lift());
        List<Vec3> hit = CACHE.get(sig);
        if (hit != null) {
            return hit;
        }
        List<Vec3> built = PatrolCurve.polyline(r.flightPoints(), r.closed(), 0.8);
        built = PatrolAdapt.liftAll(level, built, PatrolAdapt.MAX_LIFT);
        CACHE.put(sig, built);
        return built;
    }

    /**
     * 一个标记的完整记号：粗带（ribbon 面片）为主 + 细线线框为**兜底与描边**。
     *
     * <p>【为什么两套都画】ribbon 那套走 {@code RenderType.debugQuads()}（新用的渲染管线），
     * 万一在某些驱动/批次下不出现，至少还有旧版那条 1px 线框顶底——而且它现在是"更粗的带子
     * 里面套一根细线"，同时画不会看着乱。两套都在，最坏情况 = 和上一版一样（但菱形更大），
     * 最好情况 = 明显加粗。
     */
    private static void marker(VertexConsumer lines, VertexConsumer quad, Matrix4f mat, Vec3 cam,
                               Vec3 c, double h, float r, float g, float b, float alpha,
                               boolean bright, boolean first, boolean last, long gt, int i) {
        double w = bright ? 0.115 : 0.085;   // ribbon 的世界宽度（格）
        double radius = (first || last ? 0.95 : 0.78);
        // ── ① 粗带（主） ──
        diamond(quad, mat, cam, c, h, w, r, g, b, alpha);
        ribbon(quad, mat, cam, c.add(0, -1.1, 0), c.add(0, -h * 0.6, 0), w * 0.8f, r, g, b, alpha * 0.8f);
        if (bright) {
            int seg = 10;
            double phase = gt * 0.02 + i;
            Vec3 prev = null;
            for (int k = 0; k <= seg; k++) {
                double ang = phase + (Math.PI * 2.0 * k) / seg;
                Vec3 q = new Vec3(c.x + Math.cos(ang) * radius, c.y - h * 0.15,
                        c.z + Math.sin(ang) * radius);
                if (prev != null) {
                    ribbon(quad, mat, cam, prev, q, w * 0.85f, r, g, b, alpha * 0.7f);
                }
                prev = q;
            }
        }
        // ── ② 细线兜底／描边 ──
        wire(lines, mat, c, h, radius, r, g, b, alpha, bright, gt, i);
    }

    /** 线框版记号（1px 线，走 {@code RenderType.lines()}）——ribbon 那套万一不显示时的顶底 */
    private static void wire(VertexConsumer vc, Matrix4f mat, Vec3 c, double h, double radius,
                             float r, float g, float b, float alpha, boolean bright, long gt, int i) {
        Vec3 top = c.add(0, h, 0);
        Vec3 bot = c.add(0, -h, 0);
        Vec3[] eq = {c.add(h, 0, 0), c.add(0, 0, h), c.add(-h, 0, 0), c.add(0, 0, -h)};
        for (int k = 0; k < 4; k++) {
            Vec3 a = eq[k];
            Vec3 next = eq[(k + 1) % 4];
            line(vc, mat, top, a, r, g, b, alpha);
            line(vc, mat, bot, a, r, g, b, alpha);
            line(vc, mat, a, next, r, g, b, alpha);
        }
        line(vc, mat, c.add(0, -1.1, 0), c.add(0, -h * 0.6, 0), r, g, b, alpha * 0.8f);
        if (bright) {
            int seg = 10;
            double phase = gt * 0.02 + i;
            Vec3 prev = null;
            for (int k = 0; k <= seg; k++) {
                double ang = phase + (Math.PI * 2.0 * k) / seg;
                Vec3 q = new Vec3(c.x + Math.cos(ang) * radius, c.y - h * 0.15,
                        c.z + Math.sin(ang) * radius);
                if (prev != null) {
                    line(vc, mat, prev, q, r, g, b, alpha * 0.6f);
                }
                prev = q;
            }
        }
    }

    /** 菱形核（八面体线框，六个顶点十二条棱）——每条棱都是一个面向相机的细长四边形 */
    private static void diamond(VertexConsumer vc, Matrix4f mat, Vec3 cam, Vec3 c, double h,
                               double width, float r, float g, float b, float alpha) {
        Vec3 top = c.add(0, h, 0);
        Vec3 bot = c.add(0, -h, 0);
        Vec3[] eq = {c.add(h, 0, 0), c.add(0, 0, h), c.add(-h, 0, 0), c.add(0, 0, -h)};
        for (int i = 0; i < 4; i++) {
            Vec3 a = eq[i];
            Vec3 next = eq[(i + 1) % 4];
            ribbon(vc, mat, cam, top, a, width, r, g, b, alpha);
            ribbon(vc, mat, cam, bot, a, width, r, g, b, alpha);
            ribbon(vc, mat, cam, a, next, width * 0.85f, r, g, b, alpha * 0.9f);
        }
    }

    /** 一条折线整体画成粗带（只给"选中/正在标记"那条用；其它轨道仍走 1px 的 lines） */
    private static void ribbonPolyline(VertexConsumer vc, Matrix4f mat, Vec3 cam, List<Vec3> poly,
                                       double width, float r, float g, float b, float alpha) {
        for (int i = 1; i < poly.size(); i++) {
            ribbon(vc, mat, cam, poly.get(i - 1), poly.get(i), width, r, g, b, alpha);
        }
    }

    /**
     * 把一条线段画成**面向相机的细长四边形**（ribbon）——宽度是真实世界尺寸，
     * 所以不管驱动把 glLineWidth 钳成几像素、也不管离多远，它都看得见。
     *
     * <p>做法：取线段方向 × 指向相机的方向，得到"屏幕横向"的垂直向量，把线段往两侧各撑开
     * {@code width/2}，四个角拼成一个四边形。线段几乎与视线平行（叉积退化为 0）时换参考向量兜底。
     */
    private static void ribbon(VertexConsumer vc, Matrix4f mat, Vec3 cam, Vec3 a, Vec3 b,
                               double width, float r, float g, float b2, float alpha) {
        Vec3 dir = b.subtract(a);
        double len = dir.length();
        if (len < 1.0E-5 || width <= 0) {
            return;
        }
        dir = dir.scale(1.0 / len);
        Vec3 mid = a.add(b).scale(0.5);
        Vec3 side = dir.cross(cam.subtract(mid));
        if (side.lengthSqr() < 1.0E-8) {
            side = dir.cross(new Vec3(0, 1, 0));
            if (side.lengthSqr() < 1.0E-8) {
                side = dir.cross(new Vec3(1, 0, 0));
            }
        }
        side = side.normalize().scale(width * 0.5);
        Vec3 p0 = a.add(side);
        Vec3 p1 = a.subtract(side);
        Vec3 p2 = b.subtract(side);
        Vec3 p3 = b.add(side);
        vtx(vc, mat, p0, r, g, b2, alpha);
        vtx(vc, mat, p1, r, g, b2, alpha);
        vtx(vc, mat, p2, r, g, b2, alpha);
        vtx(vc, mat, p3, r, g, b2, alpha);
    }

    /** QUADS + POSITION_COLOR 的顶点（没有法线这一项，所以不能走 {@link #line}） */
    private static void vtx(VertexConsumer vc, Matrix4f mat, Vec3 p,
                            float r, float g, float b, float alpha) {
        vc.addVertex(mat, (float) p.x, (float) p.y, (float) p.z).setColor(r, g, b, alpha);
    }

    private static void line(VertexConsumer vc, Matrix4f mat, Vec3 a, Vec3 b,
                             float r, float g, float bl, float alpha) {
        float nx = (float) (b.x - a.x);
        float ny = (float) (b.y - a.y);
        float nz = (float) (b.z - a.z);
        double len = Math.sqrt((double) nx * nx + (double) ny * ny + (double) nz * nz);
        if (len < 1.0E-6) {
            return;
        }
        float ix = (float) (nx / len);
        float iy = (float) (ny / len);
        float iz = (float) (nz / len);
        vc.addVertex(mat, (float) a.x, (float) a.y, (float) a.z)
                .setColor(r, g, bl, alpha).setNormal(ix, iy, iz);
        vc.addVertex(mat, (float) b.x, (float) b.y, (float) b.z)
                .setColor(r, g, bl, alpha).setNormal(ix, iy, iz);
    }
}
