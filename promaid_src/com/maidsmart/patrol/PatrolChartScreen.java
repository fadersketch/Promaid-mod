package com.maidsmart.patrol;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

/**
 * <p>──
 * 「手持这个航图，右击进入大界面。如果玩家是第1次进入，那么此界面仅存在最上方一个按钮，叫做创建一个
 * 轨道。创建完轨道之后，下面就会多一个轨道的管理选项。玩家也可以为这个轨道进行重命名。随后点击这个
 * 新创建轨道的管理界面。玩家可以选择将哪个女仆绑定到这个轨道上。（跟建造模式的女仆管理同款……）以及
 * 在这个轨道上面，可以点击按钮"开始标记"。开始标记之后玩家会暂时退出那个界面，系统也会提示玩家，
 * 这个时候可以开始进行标记了，点击鼠标中键进行标记。……如果玩家觉得标记好了，那么再次通过右击可以
 * 回到刚才那个界面，点击连接。如果成功则显示成功。失败则失败。」
 * <p>⇒ 界面结构照这条规格做：
 * <ol>
 *   <li><b>轨道列表</b>（{@link #VIEW_LIST}）：空书时**只有**顶部一个「＋ 创建一个轨道」；
 *       有轨道后每行一条（名字、标记数、闭环与否、几只女仆在用）+「管理 / 改名 / 删除」。</li>
 *   <li><b>轨道管理</b>（{@link #VIEW_MANAGE}）：这条轨道的状态 + 一组动作
 *       （返回 / 改名 / 开始标记 / 连接 / 标记管理 / 删除）+ 女仆名单（每行「绑定 / 解绑」， 跟建造模式同款）。</li> <li><b>标记管理</b>（{@link #VIEW_MARKS}，v1.3.9.2）：把这条轨道上的**每一个标记**按顺序 列出来（序号 + 坐标），可逐条删、删最后一个、清空。 </ol> <p>── 为什么所有改动都发服务端 ── 与压缩盒/手册同一条铁律：客户端只收集意图，真身（物品 NBT）在服务端改。唯一的本地状态是 "当前看哪一页 / 改名输入框 / 预览开关"。
 */
public class PatrolChartScreen extends Screen {

    private static final int C_TEXT = 0xFFE8E8E8;
    private static final int C_DIM = 0xFF9A9A9A;
    private static final int C_WARN = 0xFFFFD24A;

    private static final int VIEW_LIST = 0;
    private static final int VIEW_MANAGE = 1;
    /** v1.3.9.2：「标记管理」——单开一页列这条轨道上的全部标记（ */
    private static final int VIEW_MARKS = 2;
    /** 独立目标点列表 */
    private static final int VIEW_TARGETS = 3;
    /** 单个目标点的管理页（改名 / 删除 / 飞过去） */
    private static final int VIEW_TARGET_MANAGE = 4;
    private static final int ROW_H = 22;

    /** 刷新节流（tick）：每 {@link #REFRESH_TICKS} 拍问一次服务端 */
    private static final int REFRESH_TICKS = 10;

    private final boolean offHand;
    /** 本地镜像：整本书（服务端下发的样子） */
    private PatrolBook book;
    /** 管理页看的是哪条（id） */
    private String viewingId = "";
    /** 目标点管理页看的是哪个（id） */
    private String viewingTargetId = "";
    private int view = VIEW_LIST;
    /** 服务端判定：玩家此刻是否处于可执行「飞过去」的同行/拴绳状态 */
    private boolean canFlyToTarget;
    /** 玩家所在维度（与目标点维度比较，跨维度时按钮变暗） */
    private String playerDim = "";
    /** 女仆名单：{uuid, 名字, 绑定的轨道id} */
    private List<String[]> maids = new ArrayList<>();
    private List<String> problems = new ArrayList<>();
    private List<String> notes = new ArrayList<>();
    private double length;
    private double seconds;

    /** 服务端下发的标记态（预览据此高亮；界面里只用来改按钮文字/提示） */
    private boolean marking;
    private String markingId = "";

    private String hint;
    private long hintUntil;
    private int refreshTimer;
    private int listPage;
    private int maidPage;
    /** 「标记管理」页翻页 */
    private int markPage;
    /** 目标点列表翻页 */
    private int targetPage;

    /** 改名：一个内联输入框 + 确定/取消（照 {@code ScheduleBookScreen} 的 activeBox 转发口径） */
    private boolean renaming;
    /** 这次改名改的是目标点（而不是轨道） */
    private boolean renamingTarget;
    private EditBox activeBox;

    /** 上一次 rebuild 时"界面所依据的数据"的指纹——{@link #tick} 每拍对一次，对不上就自愈重建 */
    private String builtSig = "";

    /** 上一次从物品读到的书的内容键（判"物品那一版换过了没有"，见 {@link #syncBookFromItem}） */
    private String lastItemSig = "";
    /** 物品同步的分频计数（每两拍读一次物品，省点开销） */
    private int syncTimer;

    public PatrolChartScreen(boolean offHand, PatrolBook book, List<String> problems,
                             List<String> notes, double length, double seconds) {
        super(Component.m_237113_("巡逻航图"));
        this.offHand = offHand;
        this.book = book == null ? new PatrolBook() : book;
        this.problems = problems == null ? new ArrayList<>() : problems;
        this.notes = notes == null ? new ArrayList<>() : notes;
        this.length = length;
        this.seconds = seconds;
        PatrolRoute sel = this.book.selected();
        this.viewingId = sel == null ? "" : sel.id();
    }

    /* ==================== S2C 入口 ==================== */

    /** 诊断日志（日志搜「巡逻航图」）——客户端这条链路看不见摸不着，出问题时全靠它 */
    private static void diag(String msg) {
        try {
            com.maidsmart.tool.PromaidLog.log("巡逻航图", msg);
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("PatrolChartScreen", ignored);
        }
    }

    public static void accept(boolean offHand, net.minecraft.nbt.CompoundTag bookTag,
                              List<String> problems, List<String> notes,
                              double length, double seconds, boolean open,
                              boolean canFlyToTarget, String playerDim) {
        try {
            Minecraft mc = Minecraft.m_91087_();
            if (mc == null) {
                return;
            }
            // 【v1.3.9.2：这里必须用 loadBare】包载荷里传的是**书本体**（book.save() 那一份），
            // 不是物品的 CUSTOM_DATA。v1.3.9 拿 load() 读它——load 找的是 CUSTOM_DATA 下的
            // TAG_ROOT，找不到就返回**空书**：服务端明明发了轨道，界面一直收到 0 条（当时靠
            // syncBookFromItem 从手上物品兜着才没露馅，管理页也才会"点进去就被踢回列表"）。
            PatrolBook parsed = PatrolBook.loadBare(bookTag);
            Screen cur = mc.f_91080_;
            // 【只在开屏时留痕】刷新包每 0.5 秒一次，每拍都记会把 latest.log 刷满
            if (open) {
                diag("accept open=true offHand=" + offHand + " routes=" + parsed.size()
                        + " bookTagNull=" + (bookTag == null)
                        + " screen=" + (cur == null ? "null" : cur.getClass().getSimpleName())
                        + (cur instanceof PatrolChartScreen ps ? (" scrOff=" + ps.offHand) : ""));
            }
            if (cur instanceof PatrolChartScreen s) {
                // 【v1.3.9.1：包里的书**不再**直接覆盖镜像】书以手上物品为准（见 syncBookFromItem）。
                // 玩家实测里 S2C 那一版可能是空的，一旦拿它盖镜像：列表被清空 → 每 0.5 秒闪一次，
                // 而且"当前看的那条"在空书里找不到 → 管理页被踢回列表（看着就是"点进去就闪回"）。
                // 这里只更新校验信息；书只在镜像还空着、而包里确实有内容时兜一次。
                if (s.book.isEmpty() && !parsed.isEmpty()) {
                    s.book = parsed;
                }
                s.problems = problems == null ? new ArrayList<>() : problems;
                s.notes = notes == null ? new ArrayList<>() : notes;
                s.length = length;
                s.seconds = seconds;
                s.canFlyToTarget = canFlyToTarget;
                s.playerDim = playerDim == null ? "" : playerDim;
                // 管理页/标记页看的那条被删了 → 回列表（**只在包里有内容时**判，空包不作数）
                if (!parsed.isEmpty() && s.view != VIEW_LIST
                        && s.view != VIEW_TARGETS && s.view != VIEW_TARGET_MANAGE
                        && parsed.byId(s.viewingId) == null) {
                    s.view = VIEW_LIST;
                }
                // 目标点页/管理页是合法空页（可以在那里点「标记目标点」），刷新包不再把玩家踢回列表
                if (s.view == VIEW_TARGET_MANAGE && parsed.targetById(s.viewingTargetId) == null) {
                    s.view = VIEW_TARGETS;
                }
                // 改名过程中不要重建控件（否则输入框会被服务端那一版旧名字冲掉）
                if (!s.renaming) {
                    s.rebuild();
                }
                return;
            }
            // 【只有"开屏"才新建界面】刷新包在界面已经关掉时**必须丢弃**——否则玩家按 ESC
            // 之后，上一次请求的响应（1~2 拍后到）会把界面重新弹出来（"关不掉"）。
            if (open) {
                PatrolChartScreen fresh = new PatrolChartScreen(
                        offHand, parsed, problems, notes, length, seconds);
                fresh.canFlyToTarget = canFlyToTarget;
                fresh.playerDim = playerDim == null ? "" : playerDim;
                // 【书以手上物品为准】包里那一版可能是空的（玩家实测就是），开屏时就地把物品读回来，
                // 免得先闪一下空界面。
                fresh.syncBookFromItem();
                // 【右键"回到刚才那个界面"】进来之前正在标记（开始标记 → 退出界面 → 中键打点），
                // 那么这次开屏直接落在**那条轨道的管理页**，玩家抬手就能点「连接」。
                // 这个判断必须在下面 MarkingStatePacket(false) 到达之前做，所以服务端是先发书、
                // 后发"标记已关"（见 PatrolNetworking.openFor）。
                String markId = PatrolPreviewClient.markingRouteId();
                if (PatrolMarking.TARGET_MODE.equals(markId)) {
                    fresh.view = VIEW_TARGETS;
                } else if (!markId.isEmpty() && fresh.book.byId(markId) != null) {
                    fresh.view = VIEW_MANAGE;
                    fresh.viewingId = markId;
                }
                mc.m_91152_(fresh);
            }
        } catch (Throwable t) {
            com.maidsmart.tool.PromaidLog.log("巡逻航图", "accept 异常: " + t);
        }
    }

    /** 女仆名单（S2C） */
    public static void acceptMaids(boolean offHand, List<String[]> rows) {
        try {
            Minecraft mc = Minecraft.m_91087_();
            diag("acceptMaids rows=" + (rows == null ? -1 : rows.size()));
            if (mc != null && mc.f_91080_ instanceof PatrolChartScreen s) {
                s.maids = rows == null ? new ArrayList<>() : rows;
                if (!s.renaming) {
                    s.rebuild();
                }
            }
        } catch (Throwable t) {
            com.maidsmart.tool.PromaidLog.log("巡逻航图", "acceptMaids 异常: " + t);
        }
    }

    /** 标记态（S2C）——同时更新预览（高亮 + 牵引线）与界面（按钮文字） */
    public static void acceptMarking(boolean marking, String routeId) {
        try {
            PatrolPreviewClient.setMarking(marking, routeId);
            Minecraft mc = Minecraft.m_91087_();
            if (mc != null && mc.f_91080_ instanceof PatrolChartScreen s) {
                s.marking = marking;
                s.markingId = routeId == null ? "" : routeId;
                if (!s.renaming) {
                    s.rebuild();
                }
            }
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("PatrolChartScreen", ignored);
        }
    }

    /* ==================== 当前正在看的那条 ==================== */

    private PatrolRoute viewingRoute() {
        PatrolRoute r = book.byId(viewingId);
        return r != null ? r : book.selected();
    }

    /* ==================== 控件 ==================== */

    @Override
    protected void m_7856_() {
        rebuild();
    }

    private void rebuild() {
        try {
            this.m_169413_();
            this.activeBox = null;
            if (renaming) {
                renameWidgets();
            } else if (view == VIEW_MARKS) {
                marksButtons();
            } else if (view == VIEW_TARGET_MANAGE) {
                targetManageButtons();
            } else if (view == VIEW_TARGETS) {
                targetListButtons();
            } else if (view == VIEW_MANAGE) {
                manageButtons();
            } else {
                listButtons();
            }
            builtSig = contentSig();
            diag("rebuild view=" + view + " routes=" + book.size()
                    + " widgets=" + this.m_6702_().size() + " maids=" + maids.size());
        } catch (Throwable t) {
            com.maidsmart.tool.PromaidLog.log("巡逻航图", "rebuild 失败（界面会停在旧样子）: " + t);
        }
    }

    /**
     * 当前"界面所依据的数据"的指纹——{@link #tick} 每拍拿它对一次，对不上就重建控件。
     *
     * <p>【为什么需要这个】book / 女仆名单都是 S2C 推来的，中间任何一条路径没触发重建
     * （或者重建被异常打断），界面就会永远停在旧样子——玩家看到的就是"建了轨道但列表里没有、
     * 更没有管理按钮"。与其去猜哪条路径漏了，不如让界面每拍自己对一次指纹：**只要数据变了，
     * 下一拍一定重建**。代价是每拍拼一个几十字节的字符串，可以忽略。
     */
    private String contentSig() {
        StringBuilder sb = new StringBuilder(96);
        sb.append(view).append('/').append(renaming ? 'R' : '-').append('/')
                .append(book.selectedId()).append('/').append(book.size()).append('/')
                .append(canFlyToTarget).append('/').append(playerDim).append('/')
                .append(viewingTargetId);
        for (PatrolRoute r : book.routes()) {
            sb.append('/').append(r.id()).append(':').append(r.size()).append(r.closed() ? 'C' : 'O');
        }
        for (PatrolTarget t : book.targets()) {
            sb.append("/T").append(t.id()).append(':').append(t.name())
                    .append('@').append(t.dimension());
        }
        sb.append('/').append(maids.size());
        return sb.toString();
    }

    private Button addButton(String label, int x, int y, int w, int h, Button.OnPress onPress) {
        Button b = Button.m_253074_(Component.m_237113_(label), onPress)
                .m_252987_(x, y, w, h).m_253136_();
        this.m_142416_(b);
        return b;
    }

    /* ---------- 轨道列表 ---------- */

    private void listButtons() {
        int cx = this.f_96543_ / 2;
        // 【最上方那个按钮】空书时界面里只有它——
        addButton("§a＋ 创建一个轨道", cx - 70, 30, 140, 20, b -> {
            sendOp("create", "", "");
            hint("已新建一条轨道——点它的「管理」进去，先「开始标记」再拿中键打点");
        });
        addButton("§c目标点管理 §7(" + book.targets().size() + ")", cx - 170, 30, 96, 20, b -> {
            view = VIEW_TARGETS;
            targetPage = 0;
            rebuild();
        });
        if (book.isEmpty()) {
            return;
        }
        int per = perPage();
        int total = book.size();
        int pages = Math.max(1, (total + per - 1) / per);
        listPage = Math.max(0, Math.min(listPage, pages - 1));
        int from = listPage * per;
        int y = 58;
        for (int i = 0; i < per && from + i < total; i++) {
            PatrolRoute r = book.routes().get(from + i);
            int rowY = y + i * ROW_H;
            final String id = r.id();
            addButton("§b管理", cx - 20, rowY, 46, 18, b -> {
                view = VIEW_MANAGE;
                viewingId = id;
                sendOp("select", id, "");
                rebuild();
            });
            addButton("§7改名", cx + 30, rowY, 44, 18, b -> startRename(id));
            addButton("§c删除", cx + 78, rowY, 44, 18, b -> {
                sendOp("delete", id, "");
                hint("已删除「" + r.displayName() + "」");
            });
        }
        int py = y + per * ROW_H + 2;
        addButton("§7◀ 上一页", cx - 160, py, 66, 18, b -> {
            listPage = Math.max(0, listPage - 1);
            rebuild();
        });
        addButton("§7第 " + (listPage + 1) + "/" + pages + " 页", cx - 88, py, 66, 18, b -> {
        });
        addButton("§7下一页 ▶", cx - 16, py, 66, 18, b -> {
            listPage = Math.min(pages - 1, listPage + 1);
            rebuild();
        });
    }

    /* ---------- 轨道管理 ---------- */

    private void manageButtons() {
        int cx = this.f_96543_ / 2;
        PatrolRoute r = viewingRoute();
        addButton("§7◀ 返回列表", cx - 170, 32, 72, 18, b -> {
            view = VIEW_LIST;
            rebuild();
        });
        addButton("§7改名", cx - 94, 32, 44, 18, b -> {
            if (r != null) {
                startRename(r.id());
            }
        });
        addButton(marking ? "§e结束标记" : "§a开始标记", cx - 46, 32, 74, 18, b -> {
            if (r == null) {
                hint("没有可标记的轨道");
                return;
            }
            if (!marking) {
                if (r.size() >= PatrolRoute.MAX_POINTS) {
                    hint("标记已满（" + PatrolRoute.MAX_POINTS + " 个），去「标记管理」删几个");
                    return;
                }
                send(new PatrolNetworking.StartMarkingPacket(offHand, r.id(), true));
                PatrolPreviewClient.setMarking(true, r.id());
                hint("开始标记：退出界面后按 §e鼠标中键§7 记一个标记（空中/地面都行）");
                this.m_7379_(); //
            } else {
                send(new PatrolNetworking.StartMarkingPacket(offHand, r.id(), false));
                PatrolPreviewClient.setMarking(false, "");
                hint("已结束标记");
                rebuild();
            }
        });
        addButton(r != null && r.closed() ? "§e断开" : "§a✔ 连接", cx + 32, 32, 62, 18, b -> {
            if (r == null) {
                return;
            }
            // 【v1.3.9.3】连接不再有门槛：服务端一律照连，原来那些判据只回一句 ⚠ 提醒
            send(new PatrolNetworking.ConnectPatrolPacket(offHand, r.closed(), r.id()));
            hint(r.closed() ? "已请求断开…" : "已请求连接…（有问题只提醒、不拦）");
        });
        addButton("§c删除", cx + 98, 32, 48, 18, b -> {
            if (r == null) {
                return;
            }
            sendOp("delete", r.id(), "");
            view = VIEW_LIST;
            hint("已删除「" + r.displayName() + "」");
            rebuild();
        });

        // 女仆名单（跟建造模式同款：每行一个绑定/解绑）
        int per = perPage();
        int total = maids.size();
        int pages = Math.max(1, (total + per - 1) / per);
        maidPage = Math.max(0, Math.min(maidPage, pages - 1));
        int from = maidPage * per;
        int y = 92;
        for (int i = 0; i < per && from + i < total; i++) {
            String[] row = maids.get(from + i);
            int rowY = y + i * ROW_H;
            final String uuid = row[0];
            final String boundId = row.length > 2 ? row[2] : "";
            boolean bound = !boundId.isEmpty();
            boolean boundHere = bound && r != null && boundId.equals(r.id());
            addButton(boundHere ? "§e解绑" : (bound ? "§7改绑到这里" : "§a绑定"),
                    cx + 62, rowY, 86, 18, b -> {
                        if (boundHere) {
                            send(new PatrolNetworking.BindMaidPacket(offHand, uuid, ""));
                            hint("已解绑 " + row[1]);
                            return;
                        }
                        if (r == null) {
                            hint("先回列表页新建/选一条轨道");
                            return;
                        }
                        // 【v1.3.9.3】绑定也不再有门槛（
                        //
                        // 服务端会回一句 ⚠。客户端这里不再拦。
                        send(new PatrolNetworking.BindMaidPacket(offHand, uuid, r.id()));
                        hint("已让 " + row[1] + " 巡逻「" + r.displayName() + "」");
                    });
        }
        int py = this.f_96544_ - 34;
        addButton("§7◀", cx - 160, py, 26, 18, b -> {
            maidPage = Math.max(0, maidPage - 1);
            rebuild();
        });
        addButton("§7女仆 " + (maidPage + 1) + "/" + pages, cx - 130, py, 70, 18, b -> {
        });
        addButton("§7▶", cx - 56, py, 26, 18, b -> {
            maidPage = Math.min(pages - 1, maidPage + 1);
            rebuild();
        });
        // 【v1.3.9.2】标记维护收进单独一页（）
        addButton("§b标记管理 §7(" + (r == null ? 0 : r.size()) + ")", cx + 24, py, 146, 18, b -> {
            if (r == null) {
                hint("没有可管理的轨道");
                return;
            }
            view = VIEW_MARKS;
            markPage = 0;
            rebuild();
        });
    }

    /* ---------- 标记管理（v1.3.9.2） ---------- */

    /**
     * 这条轨道上保存了哪些标记——按顺序一列（序号 + 坐标，文字在 {@link #renderMarks} 里画），
     * 每条一个「删」。底部是总控：「删除最后一个标记」（玩家指名放在这里）与「清空全部标记」。
     */
    private void marksButtons() {
        int cx = this.f_96543_ / 2;
        PatrolRoute r = viewingRoute();
        addButton("§7◀ 返回管理", cx - 170, 32, 72, 18, b -> {
            view = VIEW_MANAGE;
            rebuild();
        });
        if (r == null) {
            return;
        }
        int per = perPage();
        int total = r.size();
        int pages = Math.max(1, (total + per - 1) / per);
        markPage = Math.max(0, Math.min(markPage, pages - 1));
        int from = markPage * per;
        // 【R1128】按钮与文字同一行：文字画在 72 + i*ROW_H + 5，按钮高 18 要卡在
        // 文字上下各留 4.5 的居中位 → 顶边 = 72 + i*ROW_H。旧版从 58 起，整体高了一行多。
        int y = 72;
        for (int i = 0; i < per && from + i < total; i++) {
            final int idx = from + i;
            addButton(r.isSlow(idx) ? "§6缓" : "§7缓", cx + 56, y + i * ROW_H, 34, 18,
                    b -> toggleSlowAt(idx));
            addButton("§c删", cx + 96, y + i * ROW_H, 34, 18, b -> deleteAt(idx));
        }
        int py = this.f_96544_ - 34;
        addButton("§7◀", cx - 170, py, 26, 18, b -> {
            markPage = Math.max(0, markPage - 1);
            rebuild();
        });
        addButton("§7第 " + (markPage + 1) + "/" + pages + " 页", cx - 140, py, 70, 18, b -> {
        });
        addButton("§7▶", cx - 66, py, 26, 18, b -> {
            markPage = Math.min(pages - 1, markPage + 1);
            rebuild();
        });
        //
        addButton("§e删除最后一个标记", cx - 30, py, 110, 18, b -> deleteLast());
        addButton("§c清空全部标记", cx + 84, py, 92, 18, b -> clearPoints());
    }

    /* ---------- 独立目标点：列表 / 管理 ---------- */

    private PatrolTarget viewingTarget() {
        return book.targetById(viewingTargetId);
    }

    /** 进入"标记目标点"态：退出界面，之后每按一次中键新增一个目标点 */
    private void startTargetMarking() {
        send(new PatrolNetworking.StartMarkingPacket(offHand, PatrolMarking.TARGET_MODE, true));
        PatrolPreviewClient.setMarking(true, PatrolMarking.TARGET_MODE);
        hint("开始标记目标点：退出界面后按 §e鼠标中键§7 每按一次记一个目标点（红色）");
        this.m_7379_();
    }

    private void targetListButtons() {
        int cx = this.f_96543_ / 2;
        addButton("§7◀ 返回航图", cx - 170, 32, 72, 18, b -> {
            view = VIEW_LIST;
            rebuild();
        });
        addButton("§e＋ 标记目标点", cx - 90, 32, 110, 18, b -> startTargetMarking());
        if (book.targets().isEmpty()) {
            return;
        }
        int per = perPage();
        int total = book.targets().size();
        int pages = Math.max(1, (total + per - 1) / per);
        targetPage = Math.max(0, Math.min(targetPage, pages - 1));
        int from = targetPage * per;
        int y = 58;
        for (int i = 0; i < per && from + i < total; i++) {
            PatrolTarget t = book.targets().get(from + i);
            int rowY = y + i * ROW_H;
            final String id = t.id();
            addButton("§b管理", cx - 20, rowY, 46, 18, b -> {
                view = VIEW_TARGET_MANAGE;
                viewingTargetId = id;
                rebuild();
            });
            addButton("§7改名", cx + 30, rowY, 44, 18, b -> startTargetRename(id));
            addButton("§c删除", cx + 78, rowY, 44, 18, b -> {
                sendOp("tdelete", id, "");
                hint("已删除目标点「" + t.displayName() + "」");
            });
        }
        int py = y + per * ROW_H + 2;
        addButton("§7◀ 上一页", cx - 160, py, 66, 18, b -> {
            targetPage = Math.max(0, targetPage - 1);
            rebuild();
        });
        addButton("§7第 " + (targetPage + 1) + "/" + pages + " 页", cx - 88, py, 66, 18, b -> {
        });
        addButton("§7下一页 ▶", cx - 16, py, 66, 18, b -> {
            targetPage = Math.min(pages - 1, targetPage + 1);
            rebuild();
        });
    }

    private void targetManageButtons() {
        int cx = this.f_96543_ / 2;
        PatrolTarget t = viewingTarget();
        addButton("§7◀ 返回目标点", cx - 170, 32, 82, 18, b -> {
            view = VIEW_TARGETS;
            rebuild();
        });
        addButton("§7改名", cx - 82, 32, 44, 18, b -> {
            if (t != null) {
                startTargetRename(t.id());
            }
        });
        addButton("§e标记新目标点", cx - 32, 32, 104, 18, b -> startTargetMarking());
        addButton("§c删除", cx + 78, 32, 48, 18, b -> {
            if (t == null) {
                return;
            }
            sendOp("tdelete", t.id(), "");
            view = VIEW_TARGETS;
            hint("已删除目标点「" + t.displayName() + "」");
            rebuild();
        });
        // 「飞过去」只在"玩家与自己的女仆同行/被拴着"时出现；跨维度只读变暗
        if (t != null && canFlyToTarget) {
            boolean sameDim = t.dimension().isEmpty() || t.dimension().equals(playerDim);
            String label = sameDim ? "§a✈ 飞过去" : "§8飞过去（目标在其他维度）";
            Button fly = addButton(label, cx - 80, this.f_96544_ - 34, 160, 20, b -> {
                if (!sameDim) {
                    hint("目标点在其他维度，飞不过去");
                    return;
                }
                send(new PatrolNetworking.FlyToTargetPacket(offHand, t.id()));
                hint("已让女仆飞往「" + t.displayName() + "」");
                this.m_7379_();
            });
            if (!sameDim) {
                fly.f_93623_ = false; // GUI Button.active
            }
        }
    }

    private void startTargetRename(String id) {
        if (book.targetById(id) == null) {
            return;
        }
        viewingTargetId = id;
        renaming = true;
        renamingTarget = true;
        rebuild();
    }

    /** 删掉这条轨道上第 {@code idx} 个标记（0 基；服务端负责真删，客户端只发意图） */
    private void deleteAt(int idx) {
        PatrolRoute r = viewingRoute();
        if (r == null || idx < 0 || idx >= r.size()) {
            hint("这条轨道上没有这个标记");
            return;
        }
        sendOp("delat", r.id(), Integer.toString(idx));
        hint("已请求删掉第 " + (idx + 1) + " 个标记…");
    }

    /** 【R1127】把第 {@code idx} 个标记设为 / 取消减速点（服务端改书，客户端只发意图）。 */
    private void toggleSlowAt(int idx) {
        PatrolRoute r = viewingRoute();
        if (r == null || idx < 0 || idx >= r.size()) {
            hint("这条轨道上没有这个标记");
            return;
        }
        // 【R1128】本地先翻面：按下就立刻看到按钮/行文字变化，服务端回包后再以物品为准。
        boolean slow = r.toggleSlow(idx);
        sendOp("slowat", r.id(), Integer.toString(idx));
        // 【R1151】范围与倍率文案都跟着配置走：默认 5 → 10×10×10、0.1 → ×0.1
        int size = (int) Math.round(PatrolFlight.slowHalf() * 2.0);
        hint(slow ? "已设为减速点（" + size + "×" + size + "×" + size
                + "，速度 ×" + PatrolFlight.slowScale() + "）" : "已取消减速点");
        rebuild();
    }

    /* ---------- 改名 ---------- */

    private void startRename(String id) {
        PatrolRoute r = book.byId(id);
        if (r == null) {
            return;
        }
        viewingId = id;
        renaming = true;
        renamingTarget = false;
        rebuild();
    }

    private void renameWidgets() {
        int cx = this.f_96543_ / 2;
        PatrolRoute r = renamingTarget ? null : viewingRoute();
        PatrolTarget t = renamingTarget ? viewingTarget() : null;
        if (r == null && t == null) {
            renaming = false;
            renamingTarget = false;
            rebuild();
            return;
        }
        EditBox box = new EditBox(this.f_96547_, cx - 100, this.f_96544_ / 2 - 12, 200, 20,
                Component.m_237113_(renamingTarget ? "目标点名字" : "轨道名"));
        box.m_94199_(32);
        box.m_94144_(renamingTarget ? t.name() : r.name());
        box.m_93692_(true);
        this.activeBox = box;
        this.m_142416_(box);
        addButton("§a确定", cx - 100, this.f_96544_ / 2 + 14, 96, 20, b -> confirmRename());
        addButton("§7取消", cx + 4, this.f_96544_ / 2 + 14, 96, 20, b -> {
            renaming = false;
            renamingTarget = false;
            rebuild();
        });
    }

    private void confirmRename() {
        String text = activeBox == null ? "" : activeBox.m_94155_().trim();
        if (!text.isEmpty()) {
            if (renamingTarget) {
                PatrolTarget t = viewingTarget();
                if (t != null) {
                    sendOp("trename", t.id(), text);
                    hint("已改名为「" + text + "」");
                }
            } else {
                PatrolRoute r = viewingRoute();
                if (r != null) {
                    sendOp("rename", r.id(), text);
                    hint("已改名为「" + text + "」");
                }
            }
        }
        renaming = false;
        renamingTarget = false;
        rebuild();
    }

    /** 一页几行（按窗口高度算；顶部标题+动作、底部按钮各占一块）
     *  【v1.3.9.6】标记管理页顶部多了一行提示（见 {@link #renderMarks}），列表起点随之下移，
     *  否则第一行标记会压在那行提示上。 */
    private int perPage() {
        int top = view == VIEW_MANAGE ? 92
                : (view == VIEW_MARKS ? 74
                : (view == VIEW_TARGETS || view == VIEW_TARGET_MANAGE ? 58 : 58));
        int avail = this.f_96544_ - top - 64;
        return Math.max(2, Math.min(8, avail / ROW_H));
    }

    /* ==================== 动作 ==================== */

    private void sendOp(String op, String id, String text) {
        PatrolNetworking.CHANNEL.sendToServer(
                new PatrolNetworking.BookOpPacket(offHand, op, id, text));
    }

    private static void send(Object pkt) {
        PatrolNetworking.CHANNEL.sendToServer(pkt);
    }

    /**
     * 删除最后一个标记 / 清空全部标记——**都发给服务端做**，客户端本地一个字都不改。
     * （v1.3.9 原来是本地改完整本写回，见 {@code PatrolNetworking} 里删掉 SavePatrolPacket 的说明。）
     */
    private void deleteLast() {
        PatrolRoute r = viewingRoute();
        if (r == null || r.size() == 0) {
            hint("这条轨道还没有标记");
            return;
        }
        sendOp("delpoint", r.id(), "");
        hint("已请求删掉最后一个标记…");
    }

    private void clearPoints() {
        PatrolRoute r = viewingRoute();
        if (r == null) {
            return;
        }
        sendOp("clear", r.id(), "");
        hint("已请求清空这条轨道的全部标记…");
    }

    /**
     * 把整本书从**手上那件航图**读回镜像。
     * <p>【为什么书以物品为准，而不是以 S2C 包为准】物品的 {@code CUSTOM_DATA} 数据组件是同步到
     * 客户端的，它才是权威数据的一份真镜像；而 S2C 那一版在玩家实测里出现过**空载荷**
     * （服务端读到的物品和客户端不一致）——一旦拿空包覆盖镜像，界面就闪、管理页还会被踢回列表。
     * 所以这里每两拍直接读物品，只在**物品那一版真的变了**（按内容签名判）时才更新镜像。
     * <p>成本：一次 {@code copyTag} + 解析，一本几条空轨道就是几百字节；两拍一次，可忽略。
     */
    private void syncBookFromItem() {
        try {
            Minecraft m = Minecraft.m_91087_();
            if (m == null || m.f_91074_ == null) {
                return;
            }
            net.minecraft.world.item.ItemStack held = offHand
                    ? m.f_91074_.m_21206_() : m.f_91074_.m_21205_();
            if (!PatrolChartKit.isChart(held)) {
                held = offHand ? m.f_91074_.m_21205_() : m.f_91074_.m_21206_();
            }
            if (!PatrolChartKit.isChart(held)) {
                return;
            }
            PatrolBook fromItem = PatrolChartData.readBook(held);
            String sig = bookKey(fromItem);
            if (sig.equals(lastItemSig)) {
                return;
            }
            lastItemSig = sig;
            // 【只在"物品这一版变了"时采用它】物品没变就不动镜像——避免把玩家刚点的本地意图冲掉。
            this.book = fromItem;
            diag("syncBookFromItem 采用物品那一版：" + fromItem.size() + " 条");
        } catch (Throwable t) {
            com.maidsmart.tool.PromaidLog.log("巡逻航图", "syncBookFromItem 异常: " + t);
        }
    }

    /**
     * 一本书的"内容键"——判"物品那一版换过了没有"。
     * **刻意不含 id / selected**：老档里的 id 是懒生成的（每次读可能不同），带上它会每拍都判"变了"→ 闪。
     */
    private static String bookKey(PatrolBook b) {
        StringBuilder sb = new StringBuilder(64);
        sb.append(b.size());
        for (PatrolRoute r : b.routes()) {
            sb.append('/').append(r.name()).append(':').append(r.size()).append(r.closed() ? 'C' : 'O');
        }
        for (PatrolTarget t : b.targets()) {
            sb.append("/T").append(t.id()).append(':').append(t.name())
                    .append('@').append(t.dimension());
        }
        return sb.toString();
    }

    @Override
    public void m_86600_() {
        try {
            // 【书以物品为准】每两拍同步一次（见 syncBookFromItem）
            if ((syncTimer++ & 1) == 0) {
                syncBookFromItem();
            }
            // 【自愈】数据变了但控件还没跟上 → 立即重建（见 contentSig 的注释）
            if (!renaming && !builtSig.equals(contentSig())) {
                rebuild();
            }
            if (++this.refreshTimer < REFRESH_TICKS) {
                return;
            }
            this.refreshTimer = 0;
            PatrolNetworking.CHANNEL.sendToServer(
                    new PatrolNetworking.RequestPatrolPacket(offHand));
        } catch (Throwable t) {
            com.maidsmart.tool.PromaidLog.log("巡逻航图", "tick 异常: " + t);
        }
    }

    /* ==================== 输入（照 ScheduleBookScreen：直接转发给 activeBox） ==================== */

    @Override
    public boolean m_6375_(double mouseX, double mouseY, int button) {
        if (button == 0) {
            this.activeBox = null;
            for (net.minecraft.client.gui.components.events.GuiEventListener c : this.m_6702_()) {
                if (c instanceof EditBox eb && eb.m_5953_(mouseX, mouseY)) {
                    eb.m_93692_(true);
                    this.activeBox = eb;
                    return eb.m_6375_(mouseX, mouseY, 0);
                }
            }
        }
        return super.m_6375_(mouseX, mouseY, button);
    }

    @Override
    public boolean m_5534_(char codePoint, int modifiers) {
        if (this.activeBox != null && this.activeBox.m_5534_(codePoint, modifiers)) {
            return true;
        }
        return super.m_5534_(codePoint, modifiers);
    }

    @Override
    public boolean m_7933_(int key, int scanCode, int modifiers) {
        if (renaming) {
            if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) {
                confirmRename();
                return true;
            }
            if (key == GLFW.GLFW_KEY_ESCAPE) {
                renaming = false;
                rebuild();
                return true;
            }
        }
        if (this.activeBox != null && this.activeBox.m_7933_(key, scanCode, modifiers)) {
            return true;
        }
        return super.m_7933_(key, scanCode, modifiers);
    }

    /* ==================== 渲染 ==================== */

    @Override
    public void m_280039_(GuiGraphics g) {
        super.m_280039_(g);
        g.m_280509_(0, 0, this.f_96543_, this.f_96544_, 0xB0101018);
    }

    @Override
    public void m_88315_(GuiGraphics g, int mx, int my, float pt) {
        // 【v1.3.9.8 背景补齐——「1.20.1 航图打开之后没有一点背景」】
        // 1.21.1 的 {@code Screen.render()} 开头会自动回调 renderBackground，所以 neo 树
        // 只重写 {@link #m_280039_} 就够了；而 1.20.1 的 {@code Screen.m_88315_} **不调**背景，
        // 必须由子类自己先画一次（与 {@code GuideScreen} 同款：本树手册就是在 render 开头
        // 手动调 {@code m_280039_} 的）。不补这一句，界面就只有按钮和文字浮在世界上——
        // 正是玩家看到的"没有一点背景"。
        this.m_280039_(g);
        super.m_88315_(g, mx, my, pt);
        int cx = this.f_96543_ / 2;
        g.m_280137_(this.f_96547_, (view == VIEW_TARGETS || view == VIEW_TARGET_MANAGE)
                ? "§c巡逻航图 · 目标点" : "§b巡逻航图", cx, 10, C_TEXT);
        if (renaming) {
            g.m_280137_(this.f_96547_, renamingTarget
                            ? "§f重命名目标点（回车确定 / ESC 取消）"
                            : "§f重命名轨道（回车确定 / ESC 取消）",
                    cx, this.f_96544_ / 2 - 30, C_DIM);
        } else if (view == VIEW_TARGET_MANAGE) {
            renderTargetManage(g, cx);
        } else if (view == VIEW_TARGETS) {
            renderTargets(g, cx);
        } else if (view == VIEW_MARKS) {
            renderMarks(g, cx);
        } else if (view == VIEW_MANAGE) {
            renderManage(g, cx);
        } else {
            renderList(g, cx);
        }
        if (hint != null && System.currentTimeMillis() < hintUntil) {
            g.m_280137_(this.f_96547_, "§e" + hint, cx, this.f_96544_ - 13, C_WARN);
        }
    }

    private void renderList(GuiGraphics g, int cx) {
        if (book.isEmpty()) {
            g.m_280137_(this.f_96547_,
                    "§7这本航图还是空的——点上面那个按钮创建第一条轨道",
                    cx, 70, C_DIM);
            g.m_280137_(this.f_96547_,
                    "§8轨道 = 一串标记连成的闭环，扫帚模式 + 在家模式的女仆会沿着它飞",
                    cx, 86, C_DIM);
            return;
        }
        // 【v1.3.9.6】20 而不是 22——22 会和「＋ 创建一个轨道」按钮（y=30）贴住 1px。
        g.m_280137_(this.f_96547_, "§7共 " + book.size() + "/" + PatrolBook.MAX_ROUTES
                + " 条轨道（点「管理」进去标记 / 连接 / 绑定女仆）", cx, 20, C_DIM);
        int per = perPage();
        int from = listPage * per;
        int y = 58;
        for (int i = 0; i < per && from + i < book.size(); i++) {
            PatrolRoute r = book.routes().get(from + i);
            int rowY = y + i * ROW_H + 5;
            boolean cur = r.id().equals(book.selectedId());
            int users = countUsers(r.id());
            String state = r.closed() ? "§a闭环" : (r.size() >= PatrolRoute.MIN_POINTS ? "§e未连接" : "§7点数不足");
            // 【排版】左边名字到 cx-98、状态到 cx-24、按钮从 cx-20 起——三段互不压住，
            // 免得状态文字被画到「管理」按钮上面（那会让按钮看着像普通文字）。
            g.m_280488_(this.f_96547_, (cur ? "§b▸" : "§8 ") + "§f" + trim(r.displayName(), 10),
                    cx - 158, rowY, C_TEXT);
            g.m_280488_(this.f_96547_, trim(state + " §7" + r.size() + "点"
                            + (users > 0 ? " §d" + users + "只" : ""), 12),
                    cx - 96, rowY, C_DIM);
        }
    }

    private void renderManage(GuiGraphics g, int cx) {
        PatrolRoute r = viewingRoute();
        if (r == null) {
            g.m_280137_(this.f_96547_, "§7没有这条轨道（可能刚被删了）", cx, 70, C_DIM);
            return;
        }
        String state = r.closed() ? "§a✔ 已连接（闭环）" : "§e未连接";
        // 【v1.3.9.6】22 而不是 18——18 会和顶部页标题（y=10，字高约 9px）贴住 1px；22 起正好
        // 落在动作按钮带（y 32~50）上方，三者互不压。
        g.m_280137_(this.f_96547_, "§f「" + r.displayName() + "」 " + state
                        + (marking && r.id().equals(markingId) ? " §e｜标记中" : ""),
                cx, 22, C_TEXT);
        g.m_280137_(this.f_96547_, r.closed()
                        ? String.format("§7一圈 %.0f 格 · 约 %.0f 秒 · %d 个标记", length, seconds, r.size())
                        : "§7" + r.size() + "/" + PatrolRoute.MAX_POINTS
                          + " 个标记——「开始标记」后按鼠标中键记一个（空中/地面都行）",
                cx, 54, C_DIM);
        // 校验：一行摘要（第一条问题优先；没有就第一条建议）
        // 【v1.3.9.3】problems 已不再拦任何动作，前缀从 ✘ 改成 ⚠
        String line = null;
        if (!problems.isEmpty()) {
            line = "§e⚠ " + problems.get(0);
        } else if (!notes.isEmpty()) {
            line = "§7· " + notes.get(0);
        } else {
            line = r.size() < PatrolRoute.MIN_POINTS
                    ? "§7标记至少 " + PatrolRoute.MIN_POINTS + " 个才能围出一个环"
                    : "§7点「连接」把首尾接上（没有门槛，有问题只提醒）";
        }
        g.m_280137_(this.f_96547_, trim(line, 56), cx, 68, C_DIM);

        // 女仆名单表头
        g.m_280488_(this.f_96547_, "§8你自己的女仆（本维度已加载范围；点右侧按钮绑定/解绑）",
                cx - 158, 80, C_DIM);
        if (maids.isEmpty()) {
            g.m_280488_(this.f_96547_, "§8这个维度里没有你自己的女仆（先把女仆带过来）", cx - 158, 96, C_DIM);
            return;
        }
        int per = perPage();
        int from = maidPage * per;
        for (int i = 0; i < per && from + i < maids.size(); i++) {
            String[] row = maids.get(from + i);
            String boundId = row.length > 2 ? row[2] : "";
            PatrolRoute bound = boundId.isEmpty() ? null : book.byId(boundId);
            int rowY = 92 + i * ROW_H + 5;
            boolean boundHere = !boundId.isEmpty() && r.id().equals(boundId);
            String tag = boundId.isEmpty() ? "§7未绑定"
                    : (boundHere ? "§a本轨道" : (bound != null ? "§d「" + trim(bound.displayName(), 8) + "」" : "§c（已不在本航图）"));
            g.m_280488_(this.f_96547_, "§b" + trim(row[1], 12) + "  " + tag, cx - 158, rowY, C_TEXT);
        }
    }

    /** 「标记管理」页：按顺序列出这条轨道上的每一个标记（序号 + 坐标） */
    private void renderMarks(GuiGraphics g, int cx) {
        PatrolRoute r = viewingRoute();
        if (r == null) {
            g.m_280137_(this.f_96547_, "§7没有这条轨道（可能刚被删了）", cx, 70, C_DIM);
            return;
        }
        g.m_280137_(this.f_96547_, "§f「" + trim(r.displayName(), 16) + "」的标记 §7共 "
                + r.size() + "/" + PatrolRoute.MAX_POINTS + " 个", cx, 22, C_TEXT);
        // 【v1.3.9.6 排版修正】这行原来是 y=36——正好落在「◀ 返回管理」按钮（y 32~50）那条带上，
        // 玩家截图里就是按钮压着这句话。挪到按钮带下面（54），列表起点同步下移（见 perPage）。
        g.m_280137_(this.f_96547_,
                "§8标记 = 你按中键那一刻站/飞的位置——她的航线一定从这里经过", cx, 54, C_DIM);
        if (r.size() == 0) {
            g.m_280137_(this.f_96547_, "§7这条轨道还没有标记——回管理页点「开始标记」", cx, 78, C_DIM);
            return;
        }
        int per = perPage();
        int from = markPage * per;
        int y = 72;
        for (int i = 0; i < per && from + i < r.size(); i++) {
            int idx = from + i;
            net.minecraft.world.phys.Vec3 p = r.points().get(idx);
            int rowY = y + i * ROW_H + 5;
            String tag = idx == 0 ? "§a首" : (idx == r.size() - 1 ? "§6末" : "§7·");
            if (r.isSlow(idx)) {
                tag += " §6慢";
            }
            g.m_280488_(this.f_96547_, String.format("§7#%d %s§f(%.1f, %.1f, %.1f)",
                    idx + 1, tag, p.f_82479_, p.f_82480_, p.f_82481_), cx - 158, rowY, C_TEXT);
        }
    }

    /** 目标点列表：名字 + 坐标 + 维度 */
    private void renderTargets(GuiGraphics g, int cx) {
        g.m_280137_(this.f_96547_, "§7共 " + book.targets().size() + "/" + PatrolBook.MAX_TARGETS
                + " 个目标点（红色标记；点「管理」看坐标 / 飞过去）", cx, 20, C_DIM);
        if (book.targets().isEmpty()) {
            g.m_280137_(this.f_96547_, "§7还没有目标点——点上面的「＋ 标记目标点」，退出界面后中键记录",
                    cx, 70, C_DIM);
            return;
        }
        int per = perPage();
        int from = targetPage * per;
        int y = 58;
        for (int i = 0; i < per && from + i < book.targets().size(); i++) {
            PatrolTarget t = book.targets().get(from + i);
            int rowY = y + i * ROW_H + 5;
            String dim = shortDim(t.dimension());
            String line = "§c◆ §f" + trim(t.displayName(), 9)
                    + String.format(" §7(%.1f, %.1f, %.1f)", t.x(), t.y(), t.z())
                    + (dim.isEmpty() || t.dimension().equals(playerDim) ? "" : " §c[异界]");
            // 右侧「管理」按钮从 cx-20 起：留给文字 132px，超出按像素截断（R1116 修叠字）
            g.m_280488_(this.f_96547_, trimToWidth(line, 132), cx - 158, rowY, C_TEXT);
        }
    }

    /** 目标点管理：坐标 + 维度 + 「飞过去」的可用性说明 */
    private void renderTargetManage(GuiGraphics g, int cx) {
        PatrolTarget t = viewingTarget();
        if (t == null) {
            g.m_280137_(this.f_96547_, "§7没有这个目标点（可能刚被删了）", cx, 70, C_DIM);
            return;
        }
        g.m_280137_(this.f_96547_, "§f「" + trim(t.displayName(), 16) + "」", cx, 22, C_TEXT);
        g.m_280137_(this.f_96547_, String.format("§c◆ §7坐标 (%.1f, %.1f, %.1f) ｜ 维度 %s",
                        t.x(), t.y(), t.z(), shortDim(t.dimension())),
                cx, 54, C_DIM);
        String line;
        if (!canFlyToTarget) {
            line = "§8只有和女仆同乘扫帚/直升机，或用武装拴绳拴着能飞的女仆时，才能点「飞过去」";
        } else if (!t.dimension().isEmpty() && !t.dimension().equals(playerDim)) {
            line = "§e目标点在其他维度——「飞过去」不可用（只读）";
        } else {
            line = "§7点下方「飞过去」：退出航图，女仆以这里为目的地起飞（跟平时的跟随一样，只是目标变远）";
        }
        g.m_280137_(this.f_96547_, trim(line, 58), cx, 70, C_DIM);
    }

    private static String shortDim(String dim) {
        if (dim == null || dim.isEmpty()) {
            return "";
        }
        int i = dim.indexOf(':');
        return i >= 0 ? dim.substring(i + 1) : dim;
    }

    /** 这条轨道被几只（名单里的）女仆绑着 */
    private int countUsers(String routeId) {
        int n = 0;
        for (String[] row : maids) {
            if (row.length > 2 && routeId.equals(row[2])) {
                n++;
            }
        }
        return n;
    }

    private String trim(String s, int max) {
        String plain = s == null ? "" : s.replaceAll("§.", "");
        if (plain.length() <= max) {
            return s == null ? "" : s;
        }
        return s.substring(0, Math.min(s.length(), max + 2)) + "…";
    }

    /** R1116：按像素宽截断（§ 颜色码不占宽度、成对拷贝），保证行文字不压到右侧按钮上 */
    private String trimToWidth(String s, int maxW) {
        if (s == null || this.f_96547_.m_92895_(s) <= maxW) { // m_92895_ = Font.width(String)
            return s == null ? "" : s;
        }
        StringBuilder sb = new StringBuilder();
        int i = 0;
        while (i < s.length()) {
            int step = (s.charAt(i) == '§' && i + 1 < s.length()) ? 2 : 1;
            String next = sb + s.substring(i, Math.min(i + step, s.length()));
            if (this.f_96547_.m_92895_(next + "…") > maxW) {
                break;
            }
            sb.append(s, i, Math.min(i + step, s.length()));
            i += step;
        }
        return sb + "…";
    }

    private void hint(String text) {
        this.hint = text;
        this.hintUntil = System.currentTimeMillis() + 4000L;
    }

    @Override
    public void m_7379_() {
        PatrolPreviewClient.clear();
        super.m_7379_();
    }

    @Override
    public boolean m_7043_() {
        return false;
    }
}
