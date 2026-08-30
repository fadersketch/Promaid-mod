package com.maidsmart.config;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraftforge.common.ForgeConfigSpec;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Promaid 模组配置面板（v1.5.100 重构——手册式跳转 + 翻页，取代 v1.5.99 的
 * 横排 Tab + 滚轮方案：横排 Tab 在窄屏（GUI 缩放大）会超出屏幕，滚轮滚动有 bug）。
 *
 * 交互与Promaid 手册完全一致：
 * - 目录页：两列竖排板块按钮，点击跳转对应板块页
 * - 板块页：内容行按页翻页（"< 上一页 / 下一页 >" 按钮 + 页码），无滚轮
 * - 挖矿板块：参数行之外，第一行是"可挖掘方块表 →"入口——点进矿表子页
 *   （矿表整页显示，列表内自带滚动），"← 返回参数"回到参数页
 *
 * 所有控件 setResponder/onChange 即时写入 ForgeConfigSpec（内存热更新），
 * 关闭时 SPEC.save() 持久化 + loadCustomOres() 刷新挖矿矿表。
 */
public class PromaidConfigScreen extends Screen {
    private static final int LABEL_COLOR = 0xFFAAAAAA;
    private static final int HELP_COLOR = 0xFF777777;
    private static final int PANEL_BG = 0xC0101010;
    /** 行高（v1.5.110 起 44：标签 + 控件 22 + 注释行，防注释与下行重叠） */
    private static final int ROW_H = 44;
    /** 内容区顶部（标题之下） */
    private static final int CONTENT_TOP = 52;

    private final Screen parent;
    /** true = 目录页；false = 板块页 */
    private boolean inHome = true;
    private Section section = Section.BUILD;
    /** 板块页内页码（每页行数按可用高度自适应） */
    private int pageIndex = 0;
    /** v1.1.0 实测四十五：布局结果字段——init 侧算好的【当前页】每行 y 坐标，
     *  渲染侧直接用（旧版渲染侧用 start..end 下标去查【全表】rowY：rowY 是从
     *  第 0 行开始累加的全表坐标，第二页的第一行拿到的是它在第一页时的 y，
     *  行 y 起点整体错位 → 文本与控件/注释互相重叠 = "第 2 页起排版全错"根源） */
    private int[] pageRowY = new int[0];
    private int pagePerRow = 0;
    /** v1.1.0 实测一百七十七：按行真实高度【逐页装填】的分页模型——pageStarts[p] =
     *  第 p 页首行下标。旧版按"第一页能装几行"得到全局固定 perPage 再均摊到所有页，
     *  行高不均的板块（被动技能页搭路段注释长、单行 74px）在第 4+ 页会整体溢出：
     *  末行输入框落到 h-68 翻页按钮行内，EditBox 先于按钮注册、点击被输入框吃掉
     *  → "第 4 页翻不到第 5 页"。每页独立装填后任何页都保证不超 contentBottom。 */
    private final java.util.List<Integer> pageStarts = new java.util.ArrayList<>();
    /** 挖矿板块：矿表子页 */
    private boolean mineTable = false;
    /** 当前板块的行定义（分页只实例化当前页的行） */
    private final List<RowDef> rows = new ArrayList<>();
    /**
     * v1.5.124：延迟提交——NumRow 输入时【只做格式校验、不写配置】
     * （照 MC 内部搜索框交互：输入流畅，保存/完成时才生效）。旧版每按键调
     * ForgeConfigSpec.set()，输入路径上的任何异常/重载都会卡住输入
     * （"卡住电脑并不能实际输入文本"）。
     * 用【行标签】做键：分页重建后输入不丢（重建时按标签恢复文本）。
     */
    private final java.util.Map<String, String> pendingText = new java.util.HashMap<>();
    /** 行标签 → 配置写入函数（创建 NumRow 时登记，保存时统一调用） */
    private final java.util.Map<String, Function<String, Boolean>> numSetters = new java.util.HashMap<>();
    /** v1.5.127：文本行写入函数（无数字校验，保存时统一调用） */
    private final java.util.Map<String, Function<String, Boolean>> textSetters = new java.util.HashMap<>();

    /**
     * v1.5.198：允许空值写入的文本行 setter（记忆 API 字段——清空 = 回退 TLM）。
     * 默认 TextRow 提交时跳过空文本（保留旧值）；这几个字段"清空"是有意义的操作，
     * 用 IdentityHashMap 按函数身份标记放行。静态单例防分页重建时集合膨胀。
     */
    private static final java.util.Set<Function<String, Boolean>> EMPTY_ALLOWED =
            java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
    private static final Function<String, Boolean> MEMORY_API_URL_SETTER = s -> {
        MaidSmartConfig.MEMORY_API_URL.set(s.trim());
        return true;
    };
    private static final Function<String, Boolean> MEMORY_API_KEY_SETTER = s -> {
        MaidSmartConfig.MEMORY_API_KEY.set(s.trim());
        return true;
    };
    private static final Function<String, Boolean> MEMORY_API_MODEL_SETTER = s -> {
        MaidSmartConfig.MEMORY_API_MODEL.set(s.trim());
        return true;
    };
    static {
        EMPTY_ALLOWED.add(MEMORY_API_URL_SETTER);
        EMPTY_ALLOWED.add(MEMORY_API_KEY_SETTER);
        EMPTY_ALLOWED.add(MEMORY_API_MODEL_SETTER);
    }

    /** v1.5.124：纯格式校验（与 setInt/setDouble 同一解析规则，但不写配置） */
    private static boolean validNumText(String s) {
        if (s == null || s.trim().isEmpty()) {
            return false;
        }
        try {
            Double.parseDouble(s.trim());
            return true;
        } catch (Exception e) {
            return false;
        }
    }
    private MinableList minableList;
    /** v1.5.101b：恢复输入添加框（矿物 "id=value"，障碍物 "id"） */
    private EditBox minableInput;
    /**
     * v1.0.4：锁定的网格方块 id——点击图标后黄框固定在该方块上，输入框下方出现
     * 闪烁红字「已锁定（名称），请为其赋予一个值」；输入数值点添加后解锁。
     */
    private String lockedOreId = null;
    /**
     * v1.5.126：当前激活的输入框（照搬原版创造搜索框交互——CreativeModeInventoryScreen
     * 的 charTyped/keyPressed 直接转发给自己持有的 searchBox 字段，不依赖 getFocused()
     * 焦点链）。1.20.1 的键盘输入链是 KeyboardHandler → screen.m_5534_(char,int)
     * （GuiEventListener 接口方法，ContainerEventHandler 默认实现走 getFocused()）；
     * 旧版重写的 m_96583_(String,char,int) 在 1.20.1 是无人调用的死代码（其字节码是
     * 一个字符合法性过滤器），导致"点进输入框但打不了字"。这里自跟踪点击的输入框，
     * 在真正被调用的 m_5534_/m_7933_ 里直接转发给它，与 MC 原版搜索框完全同构。
     */
    private EditBox activeBox = null;
    /** v1.5.111：当前编辑的名单——0=目标矿物/木材，1=障碍物（共用创造面板交互） */
    private int mineTableMode = 0;
    /** v1.1.0：伐木板块的名单子页（0=木材，1=障碍物共享挖矿同两名单）——与矿表子页互斥复用同一套交互 */
    private boolean woodTable = false;
    /** v1.5.254：替代品名单子页（建造板块）——0=半格高 1=一格高 2=两格高（共用创造面板交互） */
    private boolean altTable = false;
    private int altTableMode = 0;
    private EditBox altInput;
    private AltList altList;
    /** v1.5.100b：创造物品面板（矿表子页）——搜索框 + 物品网格，点击方块图标添加 */
    private EditBox creativeInput;
    private String creativeQuery = "";
    private int creativePage = 0;
    private final List<net.minecraft.world.item.ItemStack> creativeItems = new ArrayList<>();
    /** 网格列数/格距（格 18px 图标 + 2px 间距）——v1.0.4：8→16，每页 24→48 个
     *  （矿表 184 页减半到 92；右侧仍留 ~210px 给悬停提示） */
    private static final int GRID_COLS = 16;
    private static final int GRID_CELL = 20;
    /** v1.5.101b：网格固定 3 行（24 格/页，分页；小窗口也不挤） */
    private static final int GRID_ROWS = 3;
    private static final int GRID_TOP = 66;
    /** 点击添加的默认价值 */
    private static final int CREATIVE_ADD_VALUE = 300;

    /**
     * v1.5.123：创造物品候选缓存（{物品, id, 中文名}）——旧版 rebuildCreative 每次
     * 按键都遍历 ForgeRegistries.ITEMS 全部条目并调 m_41786_().getString()（本地化
     * 查找），约上万物品 × 每次按键 = 每敲一个字游戏卡顿一下（"输入了会卡一下"）。
     * 改为懒构建一次缓存，之后按键只做内存过滤（id/中文名 contains），零注册表
     * 遍历、零本地化调用。
     */
    private static java.util.List<String[]> creativeCache = null; // {id, cnName}
    private static long creativeCacheBuilt = -1;

    private static void ensureCreativeCache() {
        long now = System.currentTimeMillis();
        if (creativeCache != null && now - creativeCacheBuilt < 60_000L) {
            return; // 1 分钟缓存（模组运行时注册表不会变）
        }
        creativeCache = new java.util.ArrayList<>();
        for (net.minecraft.world.item.Item item : net.minecraftforge.registries.ForgeRegistries.ITEMS) {
            if (!(item instanceof net.minecraft.world.item.BlockItem bi)) {
                continue;
            }
            net.minecraft.world.level.block.Block block = bi.m_40614_();
            if (block == null || block == net.minecraft.world.level.block.Blocks.f_50016_) {
                continue;
            }
            net.minecraft.resources.ResourceLocation key = net.minecraftforge.registries.ForgeRegistries.BLOCKS.getKey(block);
            String id = key == null ? "" : key.toString();
            String cn = "";
            try {
                cn = new net.minecraft.world.item.ItemStack(item).m_41786_().getString();
            } catch (Exception ignored) {
            }
            creativeCache.add(new String[]{id, cn == null ? "" : cn});
        }
        creativeCacheBuilt = now;
    }

    private enum Section {
        BUILD("建造"), MINE("挖矿"), WOOD("伐木"), MEMORY("AI 记忆"), DIALOGUE("对话提示"),
        COMBAT("战斗自保"), PASSIVE("被动技能"), MISC("杂项"), PERCEPTION("感知"), AFFECT("情绪"), AITOOLS("AI 工具"),
        VOICE("语音"), LOVELOATHE("爱憎分明模组调试"), HEARTFELT("heartfelt 联动");
        final String title;

        Section(String title) {
            this.title = title;
        }
    }

    /** 行定义（延迟实例化——分页时只创建当前页的行控件） */
    private sealed interface RowDef permits SectionRow, NumRow, BoolRow, BtnRow, CycleRow, TextRow, InfoRow {
    }

    /** v1.5.127：字符串输入行（英文 id 列表等，无数字校验；保存时统一写入） */
    private record TextRow(String label, String value, Function<String, Boolean> onChange,
                           String comment) implements RowDef {
    }

    /** v1.5.310：只读信息行（调试状态显示，无输入控件——仅渲染阶段画文本） */
    private record InfoRow(String label, String value, String comment) implements RowDef {
    }

    /** 板块小节标题（sub=true 用"—— 标题 ——"样式） */
    private record SectionRow(String text, boolean sub) implements RowDef {
    }

    /** v1.5.122：枚举循环行（多选一循环按钮——非数字配置不该用数字输入框，
     *  如建造速度档位 x1/x1.5/x3；旧版用 NumRow 显示 "x1.5"，EditBox 的数字
     *  过滤/输入法兼容差 → "无法输入"） */
    private record CycleRow(String label, String[] options, String current,
                            Consumer<String> onChange, String comment) implements RowDef {
    }

    /** 数值输入行（数字过滤，即时写入配置；v1.5.103：onChange 返回 Boolean = 是否设置成功，
     *  失败（非数字/越界）时输入框红字提示；v1.5.110：末位 comment = 该项说明注释） */
    private record NumRow(String label, String value, Function<String, Boolean> onChange,
                          String comment) implements RowDef {
    }

    /** 开关行（开/关循环按钮） */
    private record BoolRow(String label, boolean value, Consumer<Boolean> onChange,
                           String comment) implements RowDef {
    }

    /** 按钮行（标签 + 右侧按钮，跳转用） */
    private record BtnRow(String label, String btnText, Runnable onClick,
                          String comment) implements RowDef {
    }

    public PromaidConfigScreen(Screen parent) {
        super(Component.m_237113_("Promaid 模组详细配置"));
        this.parent = parent;
    }

    // ---------- init ----------

    @Override
    protected void m_7856_() {
        this.m_169413_(); // clearWidgets
        this.minableList = null;
        this.activeBox = null; // v1.5.126：分页重建后控件实例已更换，旧 activeBox 作废
        int w = this.f_96543_;
        int h = this.f_96544_;
        int cx = w / 2;
        if (this.inHome) {
            this.homeButtons(w, h, cx);
            return;
        }
        if (this.mineTable || this.woodTable) {
            this.mineTableButtons(w, h, cx);
            return;
        }
        if (this.altTable) {
            this.altTableButtons(w, h, cx);
            return;
        }
        this.sectionButtons(w, h, cx);
    }

    /** 目录页：两列竖排板块按钮（手册式跳转；窄屏不溢出）。
     *  v1.5.137：修复与"保存并返回"重叠——v1.5.133 加 WOODCUT 后左列 6 个按钮，
     *  末位 COMBAT（战斗自保）y=211 压住了底部保存按钮（h-34=206，240 默认高）。
     *  重排为 5+5 两列（COMBAT 移到右列顶部）+ 按钮压缩（26→22、间距 5→4）：
     *  末位按钮底 = 56+4*26+22 = 182，与保存按钮（h-34）间距 ≥ 24，永不相交。
     *  v1.5.190 修复：左列末位残留 COMBAT 与右列顶部 COMBAT 重复（共 10 个按钮、
     *  只有 9 个板块——左列第 5 个是多余副本，点它等于点右列顶部同款）。
     *  左列改 {BUILD, MINE, MEMORY, DIALOGUE}，右列 {COMBAT, MISC, PERCEPTION,
     *  AFFECT, AITOOLS}——9 板块各出现一次；同时主页按可用高度自适应：
     *  高度不足时压缩行距（h<216 时 22+4→18+3，h<190 再压 16+3），
     *  永远不与"保存并返回"（h-34）相交。 */
    private void homeButtons(int w, int h, int cx) {
        int bw = Math.min(170, (w - 56) / 2);
        // v1.1.0 实测一百七十八【目录页按钮数自适应】：行距按【实际按钮数】反推，
        // 保证末位按钮底 ≤ "保存并返回"上缘（h-34）-2——旧版固定 rowH=21/20 只按
        // "6 按钮"校准，装了爱憎分明/heartfelt 后左列 7~8 个按钮，末位按钮
        // （y0+7×20+17=213）压进保存按钮（h-34=206，默认 240 高）= 目录页 UI 重叠。
        // 现在行数越多行距自动压缩（最低 14），任何板块组合/窗口高度都不相交。
        boolean ll = loveloatheLoaded();
        boolean hf = heartfeltLoaded();
        int rowH = 21;
        int bh = 22;
        if (rowH < 22) {
            bh = rowH - 3;
        }
        int y0Min = 50; // "选择要调整的板块"说明文字（36..45）之下
        int availBottom = h - 36; // 保存按钮上缘（h-34）再留 2px
        int leftCount = 6 + (ll ? 1 : 0) + (hf ? 1 : 0);
        int rows = Math.max(leftCount, 6); // 右列恒 6 个板块
        if (rows > 1) {
            int fit = (availBottom - y0Min - bh) / (rows - 1);
            rowH = Math.min(rowH, Math.max(14, fit));
        }
        if (rowH < 22) {
            bh = rowH - 3;
        }
        int gap = rowH - bh;
        int x1 = (w - bw * 2 - 16) / 2;
        int x2 = x1 + bw + 16;
        int contentH = (rows - 1) * rowH + bh;
        // 内容装得下时在 50~56 间垂直居中一点，装不下时顶到 y0Min（不再下移）
        int y0 = y0Min + Math.max(0, Math.min(6, (availBottom - y0Min - contentH) / 2));
        java.util.List<Section> leftList = new java.util.ArrayList<>(java.util.List.of(
                Section.BUILD, Section.MINE, Section.WOOD, Section.MEMORY, Section.DIALOGUE, Section.VOICE));
        if (ll) {
            leftList.add(Section.LOVELOATHE);
        }
        if (hf) {
            leftList.add(Section.HEARTFELT);
        }
        Section[] left = leftList.toArray(new Section[0]);
        // v1.5.294：被动技能独立成栏（用户："被动技能要单拉出来一栏放在 Promaid 模组
        // 详细配置里面，而不是放在战斗自保里面"）——右列 COMBAT 正下方
        Section[] right = {Section.COMBAT, Section.PASSIVE, Section.MISC, Section.PERCEPTION, Section.AFFECT, Section.AITOOLS};
        for (int i = 0; i < left.length; i++) {
            this.addSectionButton(x1, y0 + i * rowH, bw, bh, left[i]);
        }
        for (int i = 0; i < right.length; i++) {
            this.addSectionButton(x2, y0 + i * rowH, bw, bh, right[i]);
        }
        this.bottomButtons(w, h, cx);
    }

    private void addSectionButton(int x, int y, int bw, int bh, Section s) {
        this.m_142416_(Button.m_253074_(
                        Component.m_237113_("\u00a7e" + s.title),
                        b -> {
                            this.section = s;
                            this.pageIndex = 0;
                            this.mineTable = false;
                            this.woodTable = false; // v1.1.0：子页互斥复位
                            this.altTable = false; // v1.5.254：子页互斥复位
                            this.inHome = false;
                            this.m_7856_();
                        })
                .m_252987_(x, y, bw, bh).m_253136_());
    }

    /** 板块页：行定义 → 分页实例化 → 翻页按钮 + 底部按钮 */
    private void sectionButtons(int w, int h, int cx) {
        int panelLeft = Math.max(8, cx - 280);
        int panelWidth = Math.min(560, w - 16);
        int labelWidth = 200;
        int inputWidth = panelWidth - labelWidth - 20;
        int left = panelLeft + 10;
        int contentBottom = h - 76; // 底部留翻页按钮（h-68）与保存按钮（h-34）
        this.rows.clear();
        switch (this.section) {
            case BUILD -> this.buildRows();
            case MINE -> this.mineRows();
            case WOOD -> this.woodRows();
            case MEMORY -> this.memoryRows();
            case DIALOGUE -> this.dialogueRows();
            case COMBAT -> this.combatRows();
            case PASSIVE -> this.passiveRows();
            case MISC -> this.miscRows();
            case PERCEPTION -> this.perceptionRows();
            case AFFECT -> this.affectRows();
            case AITOOLS -> this.aiToolsRows();
            case VOICE -> this.voiceRows();
            case LOVELOATHE -> this.loveloathRows();
        case HEARTFELT -> this.heartfeltRows();
        }
        // v1.1.0 实测二十二：perPage 按动态行高累加计算——每行高度 = rowHeight(def)
        // （注释折行多则高、SectionRow 紧凑），从 CONTENT_TOP 起逐行累加、超出
        // contentBottom 停止；页面内行位置同样累加（不再 ×ROW_H 匀质假设），
        // 任何分辨率/缩放下注释与下一行控件像素级不重叠。
        // v1.1.0 实测四十五：按【页】逐页累加——每页都从 CONTENT_TOP 重新起算，
        // 只把当前页的行 y 存进 pageRowY 给渲染侧用。旧版算的是全表绝对 y、
        // 渲染侧又只画 start..end 行，第二页第一行拿到全表坐标（比正确值大
        // 一整页），且行高分布随 pageIndex 偏移错位 → "第 2 页起排版全错"
        // v1.1.0 实测一百七十七【分页溢出根治】：分页模型从"全局固定 perPage 均摊"
        // 改为"逐页装填"——逐行累加真实高度，当前页装不下就开新页（pageStarts 记录
        // 每页首行下标）。旧版的 perPage 是按【第一页】行数标定的全局常数，行高不均
        // 的板块（被动技能页搭路段注释长、单行 74px vs 短行 44px）在第 4+ 页按第一页
        // 的行数硬装 → 累计 y 超过 contentBottom，末行"空中搭桥距离"的输入框落到
        // h-68 翻页按钮行内——EditBox 先于按钮注册、几何重叠处点击被输入框吃掉 =
        // "第 4 页翻不到第 5 页"。逐页装填后每页都保证最后一行不超 contentBottom。
        this.pageStarts.clear();
        this.pageStarts.add(0);
        {
            int yAcc = CONTENT_TOP;
            for (int i = 0; i < this.rows.size(); i++) {
                int rh = this.rowHeight(this.rows.get(i));
                if (yAcc + rh > contentBottom && i > this.pageStarts.get(this.pageStarts.size() - 1)) {
                    this.pageStarts.add(i); // 当前行装不下 → 新页从 i 开始
                    yAcc = CONTENT_TOP;
                }
                yAcc += rh;
            }
        }
        int totalPages = Math.max(1, this.pageStarts.size());
        this.pageIndex = Math.min(Math.max(this.pageIndex, 0), totalPages - 1);
        int start = this.pageStarts.get(this.pageIndex);
        int end = (this.pageIndex + 1 < totalPages)
                ? this.pageStarts.get(this.pageIndex + 1) : this.rows.size();
        // 当前页行 y：从 CONTENT_TOP 起累加（页面内相对布局，任何页都正确）
        this.pageRowY = new int[this.rows.size()];
        int yCursor = CONTENT_TOP;
        for (int i = start; i < end; i++) {
            this.pageRowY[i] = yCursor;
            yCursor += this.rowHeight(this.rows.get(i));
        }
        this.pagePerRow = end - start; // 诊断用：当前页实际行数（分页模型已改逐页装填）
        for (int i = start; i < end; i++) {
            RowDef def = this.rows.get(i);
            int y = this.pageRowY[i];
            if (def instanceof NumRow nr) {
                EditBox box = new EditBox(this.f_96547_, left + labelWidth + 8, y,
                        inputWidth - 60, 22, Component.m_237113_(nr.label()));
                box.m_94199_(32);
                // v1.5.124：分页重建时恢复未保存的输入（按"板块:行标签"——
                // 挖矿页/战斗页存在同名行，必须带板块前缀防串值）
                String rowKey = this.section.name() + ":" + nr.label();
                String pending = this.pendingText.get(rowKey);
                box.m_94144_(pending != null ? pending : (nr.value() == null ? "" : nr.value()));
                // v1.5.122：去掉数字过滤（m_94153_）——旧版 filter "[0-9.]*" 会把
                // 非数字初始值（如 "x1.5"）的输入框彻底锁死（过滤测试含 x 的完整串
                // 恒失败 → 任何输入都被拒）；改为不限输入、由 onChange 校验（非法
                // 红字），所有输入框都能输入
                // v1.5.124：延迟提交（照 MC 搜索框交互）——输入时只做格式校验
                // （非法红字、合法白字），【不写配置】；保存并返回/完成时统一写入
                // （m_7379_）。旧版每按键调 ForgeConfigSpec.set()，输入路径上任何
                // 异常/重载都会卡住输入（"卡住电脑并不能实际输入文本"）
                // v1.5.128：颜色必须用 m_94202_（=setTextColor，纯字段写入）——
                // 旧版误用 m_94192_（=setCursorPosition，字节码实证其方法体会
                // m_94174_ 触发 responder）→ responder 里再调 m_94192_ →
                // setCursorPosition→responder 无限递归 → StackOverflowError
                // （被 KeyboardHandler 包装捕获 → "输入卡一下、永远打不进字"，
                // 崩溃日志实证 EditBox.m_94192_↔lambda$sectionButtons$1 循环）
                box.m_94151_(s -> {
                    this.pendingText.put(rowKey, s);
                    box.m_94202_(validNumText(s) ? 0xFFFFFF : 0xFFFF5555);
                });
                this.numSetters.put(rowKey, nr.onChange());
                this.m_142416_(box);
            } else if (def instanceof TextRow tr) {
                // v1.5.127：字符串输入行（保留/垃圾物品 id 列表）——与 NumRow 同款
                // 延迟提交交互，但不做数字校验（白字恒显）
                EditBox box = new EditBox(this.f_96547_, left + labelWidth + 8, y,
                        inputWidth - 60, 22, Component.m_237113_(tr.label()));
                box.m_94199_(256);
                String rowKey = this.section.name() + ":" + tr.label();
                String pending = this.pendingText.get(rowKey);
                box.m_94144_(pending != null ? pending : (tr.value() == null ? "" : tr.value()));
                box.m_94151_(s -> {
                    this.pendingText.put(rowKey, s);
                    box.m_94202_(0xFFFFFF); // v1.5.128：m_94202_ = setTextColor（m_94192_ 是 setCursorPosition，会触发 responder 死循环）
                });
                this.textSetters.put(rowKey, tr.onChange());
                this.m_142416_(box);
            } else if (def instanceof CycleRow cr) {
                // v1.5.122：多选一循环按钮（速度档位等非数字配置）
                // v1.5.252h：修"玩家对女仆伤害显示成 x1/x1.5/x3"——旧版渲染硬编码
                // m_168961_("x1","x1.5","x3")，不管行定义传什么选项；改为用
                // cr.options()（行定义自己的选项）。current 不在选项里时回退
                // 第一个（防外部配置值越界导致 CycleButton 下标 -1）
                String cur = cr.current();
                boolean curFound = false;
                for (String o : cr.options()) {
                    if (o.equals(cur)) {
                        curFound = true;
                        break;
                    }
                }
                if (!curFound) {
                    cur = cr.options()[0];
                }
                this.m_142416_(CycleButton.<String>m_168894_(
                                v -> net.minecraft.network.chat.Component.m_237113_(v))
                        .m_168961_(cr.options())
                        .m_168948_(cur)
                        .m_168936_(left + labelWidth + 8, y, inputWidth - 60, 22,
                                Component.m_237113_(cr.label()),
                                (b, v) -> cr.onChange().accept(v)));
            } else if (def instanceof BoolRow br) {
                // v1.5.100b：开关按钮加宽（60→130）——CycleButton 显示"标签：值"，
                // 窄按钮长文本（情绪总开关/挖矿中禁止拾取）会被截断滚动
                this.m_142416_(CycleButton.m_168896_(
                                Component.m_237113_("\u00a7a开"), Component.m_237113_("\u00a77关"))
                        .m_168948_(br.value())
                        .m_168936_(left + labelWidth + 8, y, 130, 22,
                                Component.m_237113_(br.label()), (b, v) -> br.onChange().accept(v)));
            } else if (def instanceof BtnRow btnr) {
                this.m_142416_(Button.m_253074_(
                                Component.m_237113_(btnr.btnText()), b -> btnr.onClick().run())
                        .m_252987_(left + labelWidth + 8, y, inputWidth - 60, 22).m_253136_());
            }
            // SectionRow：纯标签，渲染阶段画
        }
        // 翻页按钮（v1.1.0 实测二十五：80 宽"上一页/下一页"会盖住内容末行注释——
        // 改 20 宽纯箭头 ◀/▶，页码画在两箭头之间（渲染层 h-62 行）零重叠）
        // v1.1.0 实测一百七十八：箭头外移 12px（cx±(32..52)）——页码"第 10/10 页"
        // 约 56px 宽，旧版两箭头内净宽仅 40px（cx±20），多页数时页码两端压进箭头
        // （UI 与页码重叠）；外移后内净宽 64px，任意页码宽度都不接触。
        if (totalPages > 1) {
            int py = h - 68;
            if (this.pageIndex > 0) {
                this.m_142416_(Button.m_253074_(Component.m_237113_("\u00a77◀"),
                                b -> {
                                    this.pageIndex--;
                                    this.m_7856_();
                                })
                        .m_252987_(cx - 52, py, 20, 18).m_253136_());
            }
            if (this.pageIndex < totalPages - 1) {
                this.m_142416_(Button.m_253074_(Component.m_237113_("\u00a77▶"),
                                b -> {
                                    this.pageIndex++;
                                    this.m_7856_();
                                })
                        .m_252987_(cx + 32, py, 20, 18).m_253136_());
            }
        }
        // 返回目录（底部左侧，手册同款）
        this.m_142416_(Button.m_253074_(Component.m_237113_("← 返回目录"),
                        b -> {
                            this.inHome = true;
                            this.m_7856_();
                        })
                .m_252987_(12, h - 34, 100, 20).m_253136_());
        this.bottomButtons(w, h, cx);
    }

    /** 矿表子页（挖矿板块专用）：名单切换（目标矿物/障碍物）+ 创造面板（搜索+网格点击
     *  toggle 添加/取消）+ 输入添加 + 当前名单列表（v1.5.101b）
     *  v1.5.190：矮窗口自适应——网格行数 3→2（h<215 时），输入/列表位置随之下移，
     *  列表高按剩余空间算，不再被"保存并返回"盖住。 */
    private void mineTableButtons(int w, int h, int cx) {
        int panelLeft = Math.max(8, cx - 280);
        int panelWidth = Math.min(560, w - 16);
        int left = panelLeft + 10;
        // v1.5.190：矮窗口压缩网格（默认 3 行，h<215 用 2 行）
        int gridRowsNow = h < 215 ? 2 : GRID_ROWS;
        // 名单切换（目标矿物/木材 / 障碍物，共用一套创造面板交互）
        // v1.5.111：珍稀标记矿物名单已移除（掉落物回收子系统整体删除，见 MaidMineBehavior）
        int tgY = 24;
        String[] modeNames = this.woodTable ? new String[]{"木材", "障碍物"} : new String[]{"目标矿物", "障碍物"};
        for (int i = 0; i < modeNames.length; i++) {
            final int mi = i;
            this.m_142416_(Button.m_253074_(
                            Component.m_237113_((this.mineTableMode == mi ? "\u00a7e\u25cf " : "\u00a77") + modeNames[i]),
                            b -> {
                                this.mineTableMode = mi;
                                this.lockedOreId = null; // v1.0.4：切名单清锁定
                                this.m_7856_();
                            })
                    .m_252987_(left + i * 100, tgY, 96, 18).m_253136_());
        }
        // 搜索框（创造物品面板过滤）
        this.creativeInput = new EditBox(this.f_96547_, left, 46, panelWidth - 20, 18,
                Component.m_237113_("创造物品栏搜索"));
        this.creativeInput.m_94199_(64);
        this.creativeInput.m_94144_(this.creativeQuery == null ? "" : this.creativeQuery);
        this.creativeInput.m_94151_(s -> {
            this.creativeQuery = s;
            this.rebuildCreative();
        });
        this.m_142416_(this.creativeInput);
        // 物品网格（行数自适应，分页）
        int gridTop = GRID_TOP;
        int gridBottom = gridTop + gridRowsNow * GRID_CELL;
        this.gridRows = gridRowsNow;
        this.rebuildCreative();
        // 网格翻页（v1.1.0 实测二十五：80 宽按钮盖住网格底部图标——改 20 宽纯箭头，
        // 页码本就画在网格右侧空白，不受影响）
        int py = gridBottom + 2;
        if (this.creativePage > 0) {
            this.m_142416_(Button.m_253074_(Component.m_237113_("\u00a77◀"),
                            b -> {
                                this.creativePage--;
                                this.m_7856_();
                            })
                    .m_252987_(cx - 40, py, 20, 16).m_253136_());
        }
        if (this.creativePage < this.creativePages() - 1) {
            this.m_142416_(Button.m_253074_(Component.m_237113_("\u00a77▶"),
                            b -> {
                                this.creativePage++;
                                this.m_7856_();
                            })
                    .m_252987_(cx + 20, py, 20, 16).m_253136_());
        }
        // 输入添加（v1.5.101b 恢复：矿物 "id=value"，障碍物 "id"）
        // v1.0.4：目标矿物模式输入框只在锁定方块后出现（防误认为物品添加框）；
        // 障碍物模式无输入框（网格搜索点击已完整覆盖添加/取消，手动输 id 冗余）
        int inputY = gridBottom + 24;
        if (this.mineTableMode == 0) {
            if (this.lockedOreId == null) {
                this.minableInput = null; // 未锁定：无输入框/添加按钮
            } else {
                this.minableInput = new EditBox(this.f_96547_, left, inputY, panelWidth - 116, 18,
                        Component.m_237113_("添加"));
                this.minableInput.m_94199_(64);
                this.minableInput.m_257771_(Component.m_237113_("输入优先级数值（留空=默认价值）"));
                this.m_142416_(this.minableInput);
                this.m_142416_(Button.m_253074_(Component.m_237113_("添加"), b -> this.addMinable())
                        .m_252987_(left + panelWidth - 96, inputY, 80, 18).m_253136_());
            }
        } else {
            this.minableInput = null; // v1.0.4：障碍物模式无手动输入框
        }
        // 当前名单列表（v1.5.190：矮窗口时压缩，防止盖住底部按钮）
        // v1.1.0 实测一百七十八：矿物模式锁定方块时，锁定红字画在 gridBottom+46
        // （输入框下方）——旧版列表顶固定 gridBottom+48，红字（46..55）压进列表首行；
        // 锁定时列表整体下移 12px，红字独占一行（列表高度公式按 listTop 自动收缩）。
        int listTop = inputY + 24;
        if (this.mineTableMode == 0 && this.lockedOreId != null) {
            listTop += 12;
        }
        int listH = Math.max(24, Math.min((h - 78) - listTop - 4, h - listTop - 36));
        this.minableList = new MinableList(this.f_96547_, panelLeft + 10, listTop,
                panelWidth - 20, listH);
        this.minableList.m_93507_(panelLeft + 10);
        this.m_142416_(this.minableList);
        this.m_142416_(Button.m_253074_(Component.m_237113_("← 返回参数"),
                        b -> {
                            this.mineTable = false;
                            this.woodTable = false;
                            this.m_7856_();
                        })
                .m_252987_(12, h - 34, 100, 20).m_253136_());
        this.bottomButtons(w, h, cx);
    }

    /** v1.5.254：替代品名单子页（建造板块）——三张按高度分类的名单（半格/一格/两格），
     *  交互与矿表子页同款（名单切换 + 创造面板搜索/网格点击 toggle + 输入添加 + 列表）。 */
    private void altTableButtons(int w, int h, int cx) {
        int panelLeft = Math.max(8, cx - 280);
        int panelWidth = Math.min(560, w - 16);
        int left = panelLeft + 10;
        int gridRowsNow = h < 215 ? 2 : GRID_ROWS;
        int tgY = 24;
        // v1.5.275：五个分类（半格/一格/竖两格/横两格/无碰撞）
        String[] modeNames = {"半格高", "一格高", "竖两格", "横两格", "无碰撞"};
        for (int i = 0; i < modeNames.length; i++) {
            final int mi = i;
            this.m_142416_(Button.m_253074_(
                            Component.m_237113_((this.altTableMode == mi ? "\u00a7e\u25cf " : "\u00a77") + modeNames[i]),
                            b -> {
                                this.altTableMode = mi;
                                this.m_7856_();
                            })
                    .m_252987_(left + i * 100, tgY, 96, 18).m_253136_());
        }
        // 搜索框（创造物品面板过滤，与矿表共用）
        this.creativeInput = new EditBox(this.f_96547_, left, 46, panelWidth - 20, 18,
                Component.m_237113_("创造物品栏搜索"));
        this.creativeInput.m_94199_(64);
        this.creativeInput.m_94144_(this.creativeQuery == null ? "" : this.creativeQuery);
        this.creativeInput.m_94151_(s -> {
            this.creativeQuery = s;
            this.rebuildCreative();
        });
        this.m_142416_(this.creativeInput);
        int gridTop = GRID_TOP;
        int gridBottom = gridTop + gridRowsNow * GRID_CELL;
        this.gridRows = gridRowsNow;
        this.rebuildCreative();
        int py = gridBottom + 2;
        if (this.creativePage > 0) {
            this.m_142416_(Button.m_253074_(Component.m_237113_("\u00a77◀"),
                            b -> {
                                this.creativePage--;
                                this.m_7856_();
                            })
                    .m_252987_(cx - 40, py, 20, 16).m_253136_());
        }
        if (this.creativePage < this.creativePages() - 1) {
            this.m_142416_(Button.m_253074_(Component.m_237113_("\u00a77▶"),
                            b -> {
                                this.creativePage++;
                                this.m_7856_();
                            })
                    .m_252987_(cx + 20, py, 20, 16).m_253136_());
        }
        // 输入添加（完整注册名，无 namespace 自动补 minecraft:）
        int inputY = gridBottom + 24;
        this.altInput = new EditBox(this.f_96547_, left, inputY, panelWidth - 116, 18,
                Component.m_237113_("添加"));
        this.altInput.m_94199_(64);
        this.altInput.m_257771_(Component.m_237113_("minecraft:oak_slab"));
        this.m_142416_(this.altInput);
        this.m_142416_(Button.m_253074_(Component.m_237113_("添加"), b -> this.addAlt())
                .m_252987_(left + panelWidth - 96, inputY, 80, 18).m_253136_());
        int listTop = inputY + 24;
        int listH = Math.max(24, Math.min((h - 78) - listTop - 4, h - listTop - 36));
        this.altList = new AltList(this.f_96547_, panelLeft + 10, listTop, panelWidth - 20, listH);
        this.altList.m_93507_(panelLeft + 10);
        this.m_142416_(this.altList);
        this.m_142416_(Button.m_253074_(Component.m_237113_("← 返回参数"),
                        b -> {
                            this.altTable = false;
                            this.m_7856_();
                        })
                .m_252987_(12, h - 34, 100, 20).m_253136_());
        // v1.5.275：跳转女仆管理（发请求包 → 服务端重新下发手册（女仆管理页））
        this.m_142416_(Button.m_253074_(Component.m_237113_("📋 女仆管理"),
                        b -> {
                            com.maidsmart.build.BlueprintBookNetworking.CHANNEL.sendToServer(
                                    new com.maidsmart.build.BlueprintBookNetworking.OpenBookRequestPacket(2));
                            this.m_7379_(); // 关配置面板（手册包到达后自动打开）
                        })
                .m_252987_(w - 148, h - 34, 132, 20).m_253136_());
        this.bottomButtons(w, h, cx);
    }

    /** 当前替代品名单（按 altTableMode）
     *  v1.5.275：0 半格 / 1 一格 / 2 竖两格 / 3 横两格 / 4 无碰撞 */
    private List<String> altListFor(int mode) {
        return switch (mode) {
            case 0 -> new ArrayList<>(MaidSmartConfig.BUILD_ALT_SLABS.get());
            case 1 -> new ArrayList<>(MaidSmartConfig.BUILD_ALT_BLOCKS.get());
            case 2 -> new ArrayList<>(MaidSmartConfig.BUILD_ALT_TALLS.get());
            case 3 -> new ArrayList<>(MaidSmartConfig.BUILD_ALT_WIDES.get());
            default -> new ArrayList<>(MaidSmartConfig.BUILD_ALT_NOCLIPS.get());
        };
    }

    /** 写回当前替代品名单 */
    private void altListSet(int mode, List<String> list) {
        switch (mode) {
            case 0 -> MaidSmartConfig.BUILD_ALT_SLABS.set(list);
            case 1 -> MaidSmartConfig.BUILD_ALT_BLOCKS.set(list);
            case 2 -> MaidSmartConfig.BUILD_ALT_TALLS.set(list);
            case 3 -> MaidSmartConfig.BUILD_ALT_WIDES.set(list);
            default -> MaidSmartConfig.BUILD_ALT_NOCLIPS.set(list);
        }
    }

    /** 规范化替代品 id（无 namespace 补 minecraft:）；无效（无对应方块）返回 null */
    private String normAltId(String text) {
        String t = text.trim();
        if (t.isEmpty()) {
            return null;
        }
        if (!t.contains(":")) {
            t = "minecraft:" + t;
        }
        net.minecraft.world.item.Item it = net.minecraftforge.registries.ForgeRegistries.ITEMS
                .getValue(net.minecraft.resources.ResourceLocation.parse(t));
        if (it == null) {
            return null;
        }
        net.minecraft.resources.ResourceLocation iid = net.minecraftforge.registries.ForgeRegistries.ITEMS.getKey(it);
        net.minecraft.world.level.block.Block blk = iid != null
                ? net.minecraftforge.registries.ForgeRegistries.BLOCKS.getValue(iid) : null;
        if (blk == null || blk == net.minecraft.world.level.block.Blocks.f_50016_) {
            return null; // 物品无对应方块（剑/工具等）→ 拒绝
        }
        return iid.toString();
    }

    /** 该方块的高度类别是否与当前替代表匹配（v1.5.261：1 格只能用 1 格替换，
     *  半格/两格同理——防止半格位置被一格方块顶坏建筑）
     *  v1.5.275：两格再分竖/横（门/高植物 ↔ 床），无碰撞方块单独区 */
    private static boolean altTypeMatches(int mode, net.minecraft.world.level.block.Block blk) {
        return switch (mode) {
            case 0 -> com.maidsmart.build.BlueprintLib.isSlabHeight(blk);        // 半格表
            case 1 -> !com.maidsmart.build.BlueprintLib.isSlabHeight(blk)
                    && !com.maidsmart.build.BlueprintLib.isTallHeight(blk)
                    && !com.maidsmart.build.BlueprintLib.isNoClip(blk);           // 一格表（完整方块）
            case 2 -> com.maidsmart.build.BlueprintLib.isTallVertical(blk);      // 竖两格表
            case 3 -> com.maidsmart.build.BlueprintLib.isWideHeight(blk);        // 横两格表（床）
            default -> com.maidsmart.build.BlueprintLib.isNoClip(blk);           // 无碰撞表
        };
    }

    /** 类别不匹配的提示（客户端消息，玩家可见） */
    private void warnAltType() {
        String msg = switch (this.altTableMode) {
            case 0 -> "\u00a7c半格表只能添加半格方块（台阶类）——1 格方块请加到一格表";
            case 1 -> "\u00a7c一格表只能添加整方块（半格/两格高/无碰撞请加到对应表）";
            case 2 -> "\u00a7c竖两格表只能添加两格高方块（门/高植物/甘蔗/竹子）";
            case 3 -> "\u00a7c横两格表只能添加横向两格方块（床）";
            default -> "\u00a7c无碰撞表只能添加无碰撞箱方块（花/火把/地毯等）";
        };
        if (this.f_96541_.f_91074_ != null) {
            this.f_96541_.f_91074_.m_213846_(net.minecraft.network.chat.Component.m_237113_(msg));
        }
    }

    /** 输入框添加替代品（校验有效方块 + 高度类别匹配） */
    private void addAlt() {
        if (this.altInput == null) {
            return;
        }
        String id = normAltId(this.altInput.m_94155_());
        if (id == null) {
            return;
        }
        net.minecraft.world.level.block.Block blk = net.minecraftforge.registries.ForgeRegistries.BLOCKS
                .getValue(net.minecraft.resources.ResourceLocation.parse(id));
        // v1.5.261：类别严格匹配——不匹配拒绝添加并提示
        if (blk == null || !altTypeMatches(this.altTableMode, blk)) {
            this.warnAltType();
            return;
        }
        List<String> cur = altListFor(this.altTableMode);
        if (!cur.contains(id)) {
            cur.add(id);
            altListSet(this.altTableMode, cur);
        }
        this.altInput.m_94144_("");
        if (this.altList != null) {
            this.altList.rebuild();
        }
    }

    /** 列表删除替代品 */
    private void removeAlt(String id) {
        List<String> cur = altListFor(this.altTableMode);
        cur.remove(id);
        altListSet(this.altTableMode, cur);
        if (this.altList != null) {
            this.altList.rebuild();
        }
    }

    /** 该方块是否已在当前替代品名单 */
    private boolean isInAlt(String id) {
        return altListFor(this.altTableMode).contains(id);
    }

    /** 点击方块图标 → 加入/取消当前替代品名单（toggle；v1.5.261：类别匹配校验） */
    private void toggleAltCreative(String id) {
        String norm = normAltId(id);
        if (norm == null) {
            return;
        }
        List<String> cur = altListFor(this.altTableMode);
        if (cur.contains(norm)) {
            cur.remove(norm);
        } else {
            net.minecraft.world.level.block.Block blk = net.minecraftforge.registries.ForgeRegistries.BLOCKS
                    .getValue(net.minecraft.resources.ResourceLocation.parse(norm));
            // v1.5.261：类别严格匹配——不匹配拒绝加入并提示
            if (blk == null || !altTypeMatches(this.altTableMode, blk)) {
                this.warnAltType();
                return;
            }
            cur.add(norm);
        }
        altListSet(this.altTableMode, cur);
        if (this.altList != null) {
            this.altList.rebuild();
        }
    }

    private class AltList extends ObjectSelectionList<AltList.AltEntry> {
        private final List<String> entries = new ArrayList<>();

        AltList(net.minecraft.client.gui.Font font, int x, int top, int width, int height) {
            super(Minecraft.m_91087_(), width, height, top, top + height, 22);
            this.m_93507_(x);
            this.m_93488_(false);
            this.m_93496_(false);
            this.f_93390_ = width; // v1.0.4：行宽=列表宽（旧版默认 0，删除文本被条目盖住）
            this.rebuild();
        }

        void rebuild() {
            this.m_93516_();
            this.entries.clear();
            this.entries.addAll(altListFor(PromaidConfigScreen.this.altTableMode));
            for (String e : this.entries) {
                this.m_7085_(new AltEntry(e));
            }
        }

        @Override
        public int m_5759_() {
            return Math.max(this.f_93390_, 120); // rowWidth（构造时已设为列表宽）
        }

        /** v1.0.4：同 MinableList——默认滚动条覆盖为低调样式（见 MinableList.m_238964_） */
        @Override
        protected void m_238964_(GuiGraphics g, int mx, int my, float pt,
                                 int a, int b, int c, int d, int e) {
            super.m_238964_(g, mx, my, pt, a, b, c, d, e);
            int sx = this.f_93389_ + this.m_5759_() - 6;
            g.m_280509_(sx, this.f_93392_, sx + 6, this.f_93393_, 0xFF101010);
            int maxScroll = this.m_93518_();
            if (maxScroll > 0) {
                int area = this.f_93393_ - this.f_93392_;
                int sh = Math.max(32, area * area / maxScroll);
                sh = Math.min(sh, area - 8);
                int sy = (int) (this.m_93517_() * (double) (area - sh)) + this.f_93392_;
                g.m_280509_(sx, sy, sx + 4, sy + sh, 0x40FFFFFF);
            }
        }

        private class AltEntry extends ObjectSelectionList.Entry<AltList.AltEntry> {
            private final String id;
            private final net.minecraft.client.gui.components.Button delButton;

            AltEntry(String id) {
                this.id = id;
                this.delButton = Button.m_253074_(
                                Component.m_237113_("删除"),
                                b -> PromaidConfigScreen.this.removeAlt(this.id))
                        .m_252987_(0, 0, 56, 18).m_253136_();
            }

            @Override
            public void m_6311_(GuiGraphics g, int index, int top, int left, int width, int height,
                                int mouseX, int mouseY, boolean hovered, float partialTick) {
                int x = left + 4;
                int y = top + 4;
                net.minecraft.world.item.Item it = net.minecraftforge.registries.ForgeRegistries.ITEMS
                        .getValue(net.minecraft.resources.ResourceLocation.parse(this.id));
                if (it != null) {
                    g.m_280480_(new net.minecraft.world.item.ItemStack(it), x, y - 2);
                    x += 20;
                }
                // v1.5.279：多维标记前缀【材质族·功能】（如「木·结构」「石·装饰」）——
                // 用户："自定义方块的种类需要根据多方面维度进行新的划分，仅仅一格高、
                // 半格高不够"；形态/碰撞由所在分类标签体现，这里补族与功能两维
                String tag = "";
                try {
                    net.minecraft.world.level.block.Block blk = net.minecraftforge.registries.ForgeRegistries.BLOCKS
                            .getValue(net.minecraft.resources.ResourceLocation.parse(this.id));
                    if (blk != null && blk != net.minecraft.world.level.block.Blocks.f_50016_) {
                        tag = "\u00a77[" + com.maidsmart.build.BlueprintLib.materialFamily(blk)
                                + "\u00b7" + com.maidsmart.build.BlueprintLib.blockFunction(blk) + "] \u00a7f";
                    }
                } catch (Exception ignored) {
                }
                g.m_280614_(PromaidConfigScreen.this.f_96547_,
                        Component.m_237113_(tag + com.maidsmart.build.BlueprintLib.cnName(this.id)),
                        x, y, LABEL_COLOR, false);
                // v1.0.4：标准删除按钮贴右缘（m_252865_=setX、m_253211_=setY）
                this.delButton.m_252865_(left + AltList.this.m_5759_() - 62);
                this.delButton.m_253211_(top + 1);
                this.delButton.m_88315_(g, mouseX, mouseY, partialTick);
            }

            @Override
            public boolean m_6375_(double mouseX, double mouseY, int button) {
                if (button == 0 && this.delButton.m_5953_(mouseX, mouseY)) {
                    this.delButton.m_6375_(mouseX, mouseY, 0);
                    return true;
                }
                return false;
            }

            @Override
            public Component m_142172_() {
                return Component.m_237113_(this.id);
            }
        }
    }

    /** 网格行数（按可用高度自适应） */
    private int gridRows = 4;

    /** 网格总页数 */
    private int creativePages() {
        int per = GRID_COLS * this.gridRows;
        return Math.max(1, (this.creativeItems.size() + per - 1) / per);
    }

    /** 按搜索词刷新创造物品列表（不重建 widget——搜索框焦点保持；v1.5.123 走缓存过滤）
     *  v1.5.262：替代品面板按当前表类别过滤显示——半格表只显示台阶类、一格表只显示
     *  整方块、两格表只显示两格高（类别不匹配的方块根本不出现，无需点击再拒绝） */
    private void rebuildCreative() {
        this.creativeItems.clear();
        ensureCreativeCache();
        String q = this.creativeQuery == null ? "" : this.creativeQuery.trim().toLowerCase(java.util.Locale.ROOT);
        for (String[] entry : creativeCache) {
            String id = entry[0];
            String cn = entry[1];
            if (!q.isEmpty()) {
                boolean hit = id.contains(q) || (cn != null && cn.contains(q));
                if (!hit) {
                    continue;
                }
            }
            // v1.5.262：替代品面板类别过滤（矿表面板不过滤，保持全物品）
            if (this.altTable) {
                net.minecraft.world.level.block.Block blk = net.minecraftforge.registries.ForgeRegistries.BLOCKS
                        .getValue(net.minecraft.resources.ResourceLocation.parse(id));
                if (blk == null || blk == net.minecraft.world.level.block.Blocks.f_50016_
                        || !altTypeMatches(this.altTableMode, blk)) {
                    continue;
                }
            }
            // v1.1.0：木材名单模式——网格只列木质类产品（原版木质 tag 并集，含模组木材）
            if (this.woodTable && this.mineTableMode == 0) {
                net.minecraft.world.level.block.Block blk = net.minecraftforge.registries.ForgeRegistries.BLOCKS
                        .getValue(net.minecraft.resources.ResourceLocation.parse(id));
                if (blk == null || blk == net.minecraft.world.level.block.Blocks.f_50016_
                        || !isWoodProduct(blk.m_49966_())) {
                    continue;
                }
            }
            net.minecraft.world.item.Item item = net.minecraftforge.registries.ForgeRegistries.ITEMS
                    .getValue(net.minecraft.resources.ResourceLocation.parse(id));
            if (item != null) {
                this.creativeItems.add(new net.minecraft.world.item.ItemStack(item));
            }
        }
        this.creativePage = Math.min(this.creativePage, Math.max(0, this.creativePages() - 1));
    }

    /** 底部按钮（所有视图共用）——v1.5.164：只保留"保存并返回"（"完成"与其定位重合已删） */
    private void bottomButtons(int w, int h, int cx) {
        int btnY = h - 34;
        // v1.1.0 实测一百七十八：保存按钮右对齐（w-112）——旧版居中（cx-50），
        // 窄窗口（w<324）时与左侧"← 返回目录/← 返回参数"（12..112）水平重叠。
        // 右对齐后两按钮分居两端，任何窗口宽度都不相交。
        this.m_142416_(Button.m_253074_(Component.m_237113_("\u00a7a保存并返回"),
                        b -> this.m_7379_())
                .m_252987_(Math.max(120, w - 112), btnY, 100, 20).m_253136_());
    }

    // ---------- 各板块行定义 ----------

    private void buildRows() {
        this.rows.add(new SectionRow("建造", false));
        // v1.5.122：速度档位改循环按钮（x1 → x1.5 → x3）——旧版 NumRow 输入框
        // 显示 "x1.5" 无法输入数字（非数字配置不该用数字输入框）
        this.rows.add(new CycleRow("建造速度档位", new String[]{"x1", "x1.5", "x3"},
                MaidSmartConfig.BUILD_SPEED_TIER.get(),
                v -> MaidSmartConfig.BUILD_SPEED_TIER.set(v), "建造速度档位：x1 / x1.5 / x3（点击循环切换）"));
        this.rows.add(new BoolRow("极速模式", MaidSmartConfig.BUILD_TURBO.get(),
                v -> MaidSmartConfig.BUILD_TURBO.set(v), "极速模式（吃满服务器上限，性能风险）"));
        // v1.5.254：缺料自动替代（开关 + 三张自定义替代品名单，同挖矿矿物/障碍物面板）
        this.rows.add(new BoolRow("缺料自动替代", MaidSmartConfig.BUILD_ALT_ENABLED.get(),
                v -> MaidSmartConfig.BUILD_ALT_ENABLED.set(v),
                "缺料自动替代：目标方块没有时，先找同族（木板/原木/石砖/台阶/楼梯等等价族），再按高度分类用自定义替代表（半格/一格/两格）"));
        int altSlab = MaidSmartConfig.BUILD_ALT_SLABS.get().size();
        int altBlock = MaidSmartConfig.BUILD_ALT_BLOCKS.get().size();
        int altTall = MaidSmartConfig.BUILD_ALT_TALLS.get().size();
        this.rows.add(new BtnRow("替代品名单", "管理 →（半格 " + altSlab + " · 一格 " + altBlock + " · 两格 " + altTall + "）",
                () -> {
                    this.altTable = true;
                    this.altTableMode = 0;
                    this.m_7856_();
                },
                "管理三张替代品表（点击方块图标加入，再点取消）：半格高=台阶类、一格高=整方块、两格高=门/双植物等——缺料时按序使用"));
        this.rows.add(new NumRow("全局放置配额", String.valueOf(MaidSmartConfig.BUILD_GLOBAL_QUOTA.get()),
                s -> setInt(MaidSmartConfig.BUILD_GLOBAL_QUOTA, s), "全局放置配额（每秒方块数上限，性能敏感）"));
        this.rows.add(new NumRow("强制加载区块上限", String.valueOf(MaidSmartConfig.BUILD_MAX_FORCE_CHUNKS.get()),
                s -> setInt(MaidSmartConfig.BUILD_MAX_FORCE_CHUNKS, s), "强制加载区块上限：蓝图面积折算成区块数，超过此值停止整区强制加载（超大蓝图只建玩家附近）；调大远端也能同时建，但服务器内存/CPU 占用上升"));
        this.rows.add(new NumRow("LLM 蓝图最大方块", String.valueOf(MaidSmartConfig.BUILD_MAX_BLOCKS.get()),
                s -> setInt(MaidSmartConfig.BUILD_MAX_BLOCKS, s), "LLM 蓝图最大方块数：AI 现场生成蓝图的方块上限，超出会被拒绝——保护服务器不被一次性大量放块拖垮；想盖大房子可调高"));
        this.rows.add(new NumRow("LLM 蓝图平面范围", String.valueOf(MaidSmartConfig.BUILD_MAX_RANGE.get()),
                s -> setInt(MaidSmartConfig.BUILD_MAX_RANGE, s), "LLM 蓝图平面范围（±x/z，左右/前后各多少格）：限制 AI 生成建筑的占地大小，防一次性铺太远"));
        this.rows.add(new NumRow("LLM 蓝图高度", String.valueOf(MaidSmartConfig.BUILD_MAX_HEIGHT.get()),
                s -> setInt(MaidSmartConfig.BUILD_MAX_HEIGHT, s), "LLM 蓝图高度上限（y 层数）：限制 AI 生成建筑的高度，防盖出通天塔"));
        this.rows.add(new NumRow("AI 设计蓝图上限", String.valueOf(MaidSmartConfig.BUILD_DESIGN_MAX_BLOCKS.get()),
                s -> setInt(MaidSmartConfig.BUILD_DESIGN_MAX_BLOCKS, s), "AI 子 Agent 设计蓝图方块上限（v1.5.222：默认 50 万，范围 100~50 万——想设计多大填多少）"));
        this.rows.add(new NumRow("结构蓝图上限", String.valueOf(MaidSmartConfig.BUILD_STRUCTURE_MAX_BLOCKS.get()),
                s -> setInt(MaidSmartConfig.BUILD_STRUCTURE_MAX_BLOCKS, s), "结构文件蓝图方块上限（默认 100 万，50 万级建筑无压力；数值越高服务器负担越重）"));
        this.rows.add(new NumRow("女仆管理上限", String.valueOf(MaidSmartConfig.BUILD_MAX_MAIDS.get()),
                s -> setInt(MaidSmartConfig.BUILD_MAX_MAIDS, s), "女仆管理上限：128 格内参与建造的女仆超过此数时手册列表截断（只影响显示，不影响实际建造）"));
        this.rows.add(new BoolRow("建造地点=玩家脚下", MaidSmartConfig.BUILD_ORIGIN_PLAYER.get(),
                v -> MaidSmartConfig.BUILD_ORIGIN_PLAYER.set(v), "建造地点基准：true=玩家脚下（默认），false=女仆脚下"));
        // v1.5.316：红石机器改革开关（专属顺序+活建造+自动放矿车）
        this.rows.add(new BoolRow("红石机器专属搭建", MaidSmartConfig.BUILD_MACHINE_SMART.get(),
                v -> MaidSmartConfig.BUILD_MACHINE_SMART.set(v), "红石机器专属搭建（v1.5.316 改革）：机器按红石拓扑分层放置（结构→机构→活动件→传感→动力源→TNT）+ 活建造，建好即自然运行；轰炸机类完工自动放矿车启动。关 = 回退旧行为（常规顺序+静默+完工唤醒）"));
        // v1.5.331：TNT 点火保护期——防"刚建好炸膛"（天机屠龙炮等观察者→活塞推 TNT 机器）
        this.rows.add(new NumRow("TNT 点火保护期（秒）", String.valueOf(MaidSmartConfig.BUILD_TNT_IGNITION_GRACE.get()),
                s -> setInt(MaidSmartConfig.BUILD_TNT_IGNITION_GRACE, s), "TNT 点火保护期（秒，默认 120）：建造期+完工激活期+宽限期内压制一切 TNT 点火（放置/活塞推动/邻居更新），防机器'刚建好炸膛'；完工点火结算只点燃邻接带电的 TNT（轰炸机当场启动），期满后机器按正常红石逻辑点火。0 = 关闭保护"));
        this.rows.add(new SectionRow("建造引擎细节", true));
        this.rows.add(new NumRow("卡住重试间隔（tick）", String.valueOf(MaidSmartConfig.BUILD_STALL_INTERVAL.get()),
                s -> setInt(MaidSmartConfig.BUILD_STALL_INTERVAL, s), "卡住重试间隔（tick，20=1 秒）：建造卡住（缺料/障碍/区块未加载）时多久重试一次"));
        this.rows.add(new NumRow("单轮扫描步数上限", String.valueOf(MaidSmartConfig.BUILD_LOOKAHEAD.get()),
                s -> setInt(MaidSmartConfig.BUILD_LOOKAHEAD, s), "单轮扫描步数上限：建造计划每轮最多推进的步骤数，调大单 tick 处理更多、服务器压力上升"));
        this.rows.add(new NumRow("延后步骤轮询上限", String.valueOf(MaidSmartConfig.BUILD_DEFERRED_SCAN_CAP.get()),
                s -> setInt(MaidSmartConfig.BUILD_DEFERRED_SCAN_CAP, s), "延后步骤轮询上限：每轮补建检查的延后步骤（缺料/障碍暂缓的）数量"));
        this.rows.add(new NumRow("结构文件体积上限", String.valueOf(MaidSmartConfig.BUILD_STRUCTURE_MAX_VOLUME.get()),
                s -> setInt(MaidSmartConfig.BUILD_STRUCTURE_MAX_VOLUME, s), "结构文件体积上限（宽×高×长）：外部 .nbt/.litematic/.schem 蓝图超过此体积拒绝加载（防超大文件拖垮加载）"));
    }

    private void mineRows() {
        // 名单管理入口（目标矿物 / 障碍物，创造面板 toggle 编辑）
        // v1.5.111：珍稀标记矿物名单已移除（掉落物回收子系统整体删除，见 MaidMineBehavior）
        int oreCount = MaidSmartConfig.MINE_ORE_VALUES.get().size();
        int brkCount = MaidSmartConfig.MINE_BREAKABLES.get().size();
        this.rows.add(new BtnRow("矿物 / 障碍物名单", "管理 →（矿 " + oreCount + " · 障 " + brkCount + "）",
                () -> {
                    this.mineTable = true;
                    this.m_7856_();
                }, "管理挖矿两张表：目标矿物（女仆会挖）、障碍物（可挖穿开路）"));
        // v1.0.4：透视感知开关——默认关（关闭=女仆像玩家一样只发现视线无阻的矿；开启=隔墙找矿旧逻辑）
        this.rows.add(new BoolRow("透视感知（隔墙找矿）", MaidSmartConfig.MINE_SEEK_THROUGH_WALLS.get(),
                v -> MaidSmartConfig.MINE_SEEK_THROUGH_WALLS.set(v), "透视感知：开启后女仆能发现视线被方块挡住的矿物并挖通开路（隔墙找矿，等同旧版逻辑）；关闭（默认）则女仆像玩家一样只能发现视线无阻的矿物——除水/岩浆外任何方块（泥土/石头/玻璃/半砖等）都挡视线，被挡的矿不可见也不报点，也不会隔墙挖穿；已经看得见的矿，身前有可挖障碍物照常挖穿开路"));
        this.rows.add(new NumRow("检索半径", String.valueOf(MaidSmartConfig.MINE_SEARCH_RADIUS.get()),
                s -> setInt(MaidSmartConfig.MINE_SEARCH_RADIUS, s), "矿物检索半径（水平格）：女仆以锚点为中心扫描正方形区域找矿——调大更早发现远处矿，扫描更耗时；调小专注身边"));
        // v1.1.0 实测六十一（借鉴 TLM-Sincerely 预算制探测）：全量扫描分帧执行
        this.rows.add(new NumRow("扫描预算（格/tick）", String.valueOf(MaidSmartConfig.MINE_SCAN_BUDGET.get()),
                s -> setInt(MaidSmartConfig.MINE_SCAN_BUDGET, s), "挖矿扫描预算（格/tick，默认 4096）：全量扫描矿框分帧执行——每 tick 最多检查这么多格，剩余下 tick 继续（扫完前女仆短暂无目标）；调小更不卡服但找矿变慢，调大找矿快但单 tick 尖峰高"));
        this.rows.add(new NumRow("垂直向下范围", String.valueOf(MaidSmartConfig.MINE_DOWN_RANGE.get()),
                s -> setInt(MaidSmartConfig.MINE_DOWN_RANGE, s), "垂直向下范围（格）：找脚下多深的矿；调大能发现深层矿脉，扫描开销上升"));
        this.rows.add(new NumRow("垂直向上范围", String.valueOf(MaidSmartConfig.MINE_UP_RANGE.get()),
                s -> setInt(MaidSmartConfig.MINE_UP_RANGE, s), "垂直向上范围（格）：找头顶多高的矿（悬崖/天花板矿脉）；调大能发现高处矿"));
        this.rows.add(new NumRow("穿透预算", String.valueOf(MaidSmartConfig.MINE_BREAK_BUDGET.get()),
                s -> setInt(MaidSmartConfig.MINE_BREAK_BUDGET, s), "穿透预算（默认 6）：选矿时统计到矿之间要穿过的实心方块层数（含石头/泥土），超过预算的矿不选、走近再看；调大=爱穿墙打隧道，调小=只挑暴露的矿"));
        this.rows.add(new NumRow("价值权重", String.valueOf(MaidSmartConfig.MINE_VALUE_WEIGHT.get()),
                s -> setDouble(MaidSmartConfig.MINE_VALUE_WEIGHT, s), "价值权重：矿石价值对选矿的加成——钻石/绿宝石 500 分、铁/金 250、煤 100；权重越高高价值矿越优先（哪怕更远）"));
        this.rows.add(new NumRow("深度惩罚", String.valueOf(MaidSmartConfig.MINE_DEPTH_PENALTY.get()),
                s -> setDouble(MaidSmartConfig.MINE_DEPTH_PENALTY, s), "深度惩罚（每格扣分）：矿越深选矿成本越高——想先挖浅处就调大，想下深层挖矿就调小"));
        this.rows.add(new NumRow("挖矿速度系数", String.valueOf(MaidSmartConfig.MINE_SPEED_FACTOR.get()),
                s -> setDouble(MaidSmartConfig.MINE_SPEED_FACTOR, s), "挖矿速度系数（1.0=玩家速度，1.2=快20%）"));
        this.rows.add(new NumRow("接近矿速度", String.valueOf(MaidSmartConfig.MINE_MOVE_SPEED.get()),
                s -> setDouble(MaidSmartConfig.MINE_MOVE_SPEED, s), "接近矿速度倍率：0.4 = 正常步行（配合爱憎分明饥饿档速度，搭高不再漂移）；调大可跑更快接近矿石，但搭高时容易冲过头"));
        this.rows.add(new NumRow("废石保留量", String.valueOf(MaidSmartConfig.MINE_JUNK_KEEP.get()),
                s -> setInt(MaidSmartConfig.MINE_JUNK_KEEP, s), "废石保留量：圆石/泥土/沙砾等每种最多保留几组，超出直接销毁——防背包被石头塞满挖不了矿"));
        this.rows.add(new NumRow("搭方块清理（秒）", String.valueOf(MaidSmartConfig.MINE_PLACED_LIFETIME.get()),
                s -> setInt(MaidSmartConfig.MINE_PLACED_LIFETIME, s), "搭方块清理时间（秒）：搭高/搭桥的方块放置 N 秒后自动变掉落物回收，走远也不残留"));
        this.rows.add(new BoolRow("软方块不耗耐久", MaidSmartConfig.MINE_SOFT_NO_DURABILITY.get(),
                v -> MaidSmartConfig.MINE_SOFT_NO_DURABILITY.set(v), "软方块（徒手可挖）开路不消耗镐耐久（v1.5.138 起默认关——与伐木一致，每次挖块都扣耐久；如已生成旧配置请在这里关闭）"));
        this.rows.add(new BoolRow("搭方块防掉落", MaidSmartConfig.MINE_PILLAR_GUARD.get(),
                v -> MaidSmartConfig.MINE_PILLAR_GUARD.set(v), "搭方块防掉落（潜行效果，速度不变）"));
        this.rows.add(new BoolRow("硬挡路报点弃置", MaidSmartConfig.MINE_HARD_BLOCK_REPORT.get(),
                v -> MaidSmartConfig.MINE_HARD_BLOCK_REPORT.set(v), "硬挡路（箱子/机器等）报点弃置该矿"));
        // v1.5.161：进阶挖矿——连锁采集 / 自动收集（默认关闭）
        this.rows.add(new BoolRow("连锁采集", MaidSmartConfig.MINE_CHAIN_MINING.get(),
                v -> MaidSmartConfig.MINE_CHAIN_MINING.set(v), "连锁采集：挖矿时自动连锁挖掘相连的同族矿石（矿脉一次挖完）；默认开启"));
        this.rows.add(new BoolRow("自动收集", MaidSmartConfig.MINE_AUTO_COLLECT.get(),
                v -> MaidSmartConfig.MINE_AUTO_COLLECT.set(v), "自动收集：挖掘掉落物直接进女仆背包（不进世界不掉地，放不下才落地）；默认关闭"));
        // v1.5.163：连锁采集上限可自定义
        this.rows.add(new NumRow("连锁采集上限（块）", String.valueOf(MaidSmartConfig.MINE_CHAIN_LIMIT.get()),
                s -> setInt(MaidSmartConfig.MINE_CHAIN_LIMIT, s), "连锁采集上限（块）：一次连锁挖掘的最大方块数（4~64，默认 16）"));
        this.rows.add(new SectionRow("目标与节奏", true));
        this.rows.add(new NumRow("挖掘距离（格）", String.valueOf(MaidSmartConfig.MINE_REACH.get()),
                s -> setDouble(MaidSmartConfig.MINE_REACH, s), "挖掘距离（格）：女仆伸手够得到目标方块的距离，默认 4.5 接近玩家手长；调大能隔空挖更远但观感变怪"));
        this.rows.add(new NumRow("目标超时（tick）", String.valueOf(MaidSmartConfig.MINE_TARGET_TIMEOUT.get()),
                s -> setInt(MaidSmartConfig.MINE_TARGET_TIMEOUT, s), "目标超时（tick，够不到矿超时放弃）"));
        this.rows.add(new NumRow("锚点出框超时（tick）", String.valueOf(MaidSmartConfig.MINE_ANCHOR_TIMEOUT.get()),
                s -> setInt(MaidSmartConfig.MINE_ANCHOR_TIMEOUT, s), "锚点出框超时（tick，出框超过此时长重埋锚点）"));
        this.rows.add(new NumRow("重定位节流（tick）", String.valueOf(MaidSmartConfig.MINE_RELOCATE_THROTTLE.get()),
                s -> setInt(MaidSmartConfig.MINE_RELOCATE_THROTTLE, s), "重定位节流（tick，防边界抖动）"));
        this.rows.add(new NumRow("搭方块冷却（tick）", String.valueOf(MaidSmartConfig.MINE_PILLAR_COOLDOWN.get()),
                s -> setInt(MaidSmartConfig.MINE_PILLAR_COOLDOWN, s), "搭方块冷却（tick，垫脚下/搭路节奏）"));
        // v1.1.0 实测一百五十六：骑乘中禁止搭方块
        this.rows.add(new BoolRow("骑乘中禁止搭方块", MaidSmartConfig.MINE_RIDE_NO_PILLAR.get(),
                v -> MaidSmartConfig.MINE_RIDE_NO_PILLAR.set(v), "骑乘中（扫帚等载具）执行挖矿模式时不再垫方块搭高/搭桥——骑乘移动由载具控制，垫方块只会留残渣；关闭 = 旧行为（骑乘也照常搭）"));
        this.rows.add(new NumRow("废石清理间隔（tick）", String.valueOf(MaidSmartConfig.MINE_JUNK_CHECK_INTERVAL.get()),
                s -> setInt(MaidSmartConfig.MINE_JUNK_CHECK_INTERVAL, s), "废石清理间隔（tick，20=1 秒）：多久检查一次背包废石是否超量，调小清理更及时、略耗性能"));
        this.rows.add(new NumRow("播报限频（tick）", String.valueOf(MaidSmartConfig.MINE_SKIP_REPORT_INTERVAL.get()),
                s -> setInt(MaidSmartConfig.MINE_SKIP_REPORT_INTERVAL, s), "播报限频（tick）：'镐子挖不动/矿物被挡住'等提示的最短间隔，防刷屏"));
        this.rows.add(new NumRow("创造面板默认价值", String.valueOf(MaidSmartConfig.MINE_CREATIVE_DEFAULT_VALUE.get()),
                s -> setInt(MaidSmartConfig.MINE_CREATIVE_DEFAULT_VALUE, s), "创造面板默认价值：矿表页锁定方块后，输入框留空直接点「添加」时用的分数（快捷赋值）；想自定义就输数值再点添加，或输入框填 方块id=分数 更新"));
    }

    // ---------- v1.1.0：伐木板块（克隆挖矿；障碍物两名单与挖矿共享） ----------

    /** 当前是否处于木材名单编辑（woodTable 子页模式 0）——矿表/木材表共用一套交互，
     *  通过本开关决定读写 MINE_ORE_VALUES 还是 WOOD_VALUES */
    private boolean woodListMode() {
        return this.woodTable && this.mineTableMode == 0;
    }

    private List<String> valueListGet() {
        return new ArrayList<>(this.woodListMode()
                ? MaidSmartConfig.WOOD_VALUES.get() : MaidSmartConfig.MINE_ORE_VALUES.get());
    }

    private void valueListSet(List<String> list) {
        if (this.woodListMode()) {
            MaidSmartConfig.WOOD_VALUES.set(list);
        } else {
            MaidSmartConfig.MINE_ORE_VALUES.set(list);
        }
    }

    private void valueListReload() {
        if (this.woodListMode()) {
            com.maidsmart.task.MaidWoodBehavior.loadCustomWoods();
        } else {
            com.maidsmart.task.MaidMineBehavior.loadCustomOres();
        }
    }

    private int creativeDefaultValue() {
        return this.woodListMode()
                ? MaidSmartConfig.WOOD_CREATIVE_DEFAULT_VALUE.get() : MaidSmartConfig.MINE_CREATIVE_DEFAULT_VALUE.get();
    }

    /** v1.1.0：木质类产品判定（木材名单创造网格过滤）——原版木质 tag 并集；
     *  模组通过 #minecraft:logs 等原版 tag 注册的木材自动包含 */
    private static final java.util.List<net.minecraft.tags.TagKey<net.minecraft.world.level.block.Block>> WOOD_PRODUCT_TAGS =
            java.util.List.of(
                    net.minecraft.tags.BlockTags.create(net.minecraft.resources.ResourceLocation.parse("minecraft:logs")),
                    net.minecraft.tags.BlockTags.create(net.minecraft.resources.ResourceLocation.parse("minecraft:planks")),
                    net.minecraft.tags.BlockTags.create(net.minecraft.resources.ResourceLocation.parse("minecraft:wooden_fences")),
                    net.minecraft.tags.BlockTags.create(net.minecraft.resources.ResourceLocation.parse("minecraft:wooden_slabs")),
                    net.minecraft.tags.BlockTags.create(net.minecraft.resources.ResourceLocation.parse("minecraft:wooden_stairs")),
                    net.minecraft.tags.BlockTags.create(net.minecraft.resources.ResourceLocation.parse("minecraft:wooden_doors")),
                    net.minecraft.tags.BlockTags.create(net.minecraft.resources.ResourceLocation.parse("minecraft:wooden_trapdoors")),
                    net.minecraft.tags.BlockTags.create(net.minecraft.resources.ResourceLocation.parse("minecraft:wooden_buttons")),
                    net.minecraft.tags.BlockTags.create(net.minecraft.resources.ResourceLocation.parse("minecraft:wooden_pressure_plates")),
                    net.minecraft.tags.BlockTags.create(net.minecraft.resources.ResourceLocation.parse("minecraft:fence_gates")),
                    net.minecraft.tags.BlockTags.create(net.minecraft.resources.ResourceLocation.parse("minecraft:bamboo_blocks")));

    private static boolean isWoodProduct(net.minecraft.world.level.block.state.BlockState state) {
        for (net.minecraft.tags.TagKey<net.minecraft.world.level.block.Block> tag : WOOD_PRODUCT_TAGS) {
            if (state.m_204336_(tag)) {
                return true;
            }
        }
        return false;
    }

    /** v1.1.0：伐木板块行（照 mineRows 克隆——名单入口 + 参数） */
    private void woodRows() {
        int woodCount = MaidSmartConfig.WOOD_VALUES.get().size();
        int brkCount = MaidSmartConfig.MINE_BREAKABLES.get().size();
        this.rows.add(new BtnRow("木材 / 障碍物名单", "管理 →（木 " + woodCount + " · 障 " + brkCount + "）",
                () -> {
                    this.woodTable = true;
                    this.mineTableMode = 0;
                    this.m_7856_();
                }, "管理伐木两张表：木材（女仆会砍，网格只列木质类产品——含模组）、障碍物（可挖穿开路，与挖矿共享同一名单）"));
        this.rows.add(new BoolRow("自动识别模组原木（标签）", MaidSmartConfig.WOOD_TAG_AUTO.get(),
                v -> MaidSmartConfig.WOOD_TAG_AUTO.set(v), "自动识别模组原木：开启（默认）时凡带原版 #logs / #bamboo_blocks 标签的方块（模组原木）都自动视为可砍木材（价值 300，无需进名单）；关闭则只认木材名单里的方块（名单可精确控制砍什么/价值权重）"));
        this.rows.add(new BoolRow("透视感知（隔墙找木材）", MaidSmartConfig.WOOD_SEEK_THROUGH_WALLS.get(),
                v -> MaidSmartConfig.WOOD_SEEK_THROUGH_WALLS.set(v), "透视感知：开启后女仆能发现视线被挡住的木材并挖通开路；关闭（默认）则像玩家一样只发现视线无阻的木材——树叶不挡视线，水/岩浆外任何方块都挡"));
        this.rows.add(new NumRow("检索半径", String.valueOf(MaidSmartConfig.WOOD_SEARCH_RADIUS.get()),
                s -> setInt(MaidSmartConfig.WOOD_SEARCH_RADIUS, s), "木材检索半径（水平格）：以锚点为中心扫描正方形区域找树"));
        // v1.1.0 实测六十一（借鉴 TLM-Sincerely 预算制探测）：全量扫描分帧执行
        this.rows.add(new NumRow("扫描预算（格/tick）", String.valueOf(MaidSmartConfig.WOOD_SCAN_BUDGET.get()),
                s -> setInt(MaidSmartConfig.WOOD_SCAN_BUDGET, s), "伐木扫描预算（格/tick，默认 4096）：全量扫描木材框分帧执行——每 tick 最多检查这么多格，剩余下 tick 继续（扫完前女仆短暂无目标）；调小更不卡服但找树变慢，调大找树快但单 tick 尖峰高"));
        this.rows.add(new NumRow("垂直向下范围", String.valueOf(MaidSmartConfig.WOOD_DOWN_RANGE.get()),
                s -> setInt(MaidSmartConfig.WOOD_DOWN_RANGE, s), "垂直向下搜索范围（格）——树在地表，默认 4"));
        this.rows.add(new NumRow("垂直向上范围", String.valueOf(MaidSmartConfig.WOOD_UP_RANGE.get()),
                s -> setInt(MaidSmartConfig.WOOD_UP_RANGE, s), "垂直向上搜索范围（格）——树冠/巨型蘑菇很高，默认 24"));
        this.rows.add(new NumRow("穿透预算", String.valueOf(MaidSmartConfig.WOOD_BREAK_BUDGET.get()),
                s -> setInt(MaidSmartConfig.WOOD_BREAK_BUDGET, s), "穿透预算：选材时计算女仆到木材之间的实心挡路方块数，超过不选"));
        this.rows.add(new NumRow("价值权重", String.valueOf(MaidSmartConfig.WOOD_VALUE_WEIGHT.get()),
                s -> setDouble(MaidSmartConfig.WOOD_VALUE_WEIGHT, s), "价值权重：木材价值对选材的加成（默认各木材同价 300，改价后高价值优先）"));
        this.rows.add(new NumRow("深度惩罚", String.valueOf(MaidSmartConfig.WOOD_DEPTH_PENALTY.get()),
                s -> setDouble(MaidSmartConfig.WOOD_DEPTH_PENALTY, s), "深度惩罚（每格扣分）——树在地表，默认 0 不偏好浅层"));
        this.rows.add(new NumRow("砍伐速度系数", String.valueOf(MaidSmartConfig.WOOD_SPEED_FACTOR.get()),
                s -> setDouble(MaidSmartConfig.WOOD_SPEED_FACTOR, s), "砍伐速度系数（1.0=玩家速度，1.2=快20%）"));
        this.rows.add(new NumRow("接近木材速度", String.valueOf(MaidSmartConfig.WOOD_MOVE_SPEED.get()),
                s -> setDouble(MaidSmartConfig.WOOD_MOVE_SPEED, s), "接近木材速度倍率（搭高采高处树冠时调小防冲过头）"));
        this.rows.add(new NumRow("废石保留量", String.valueOf(MaidSmartConfig.WOOD_JUNK_KEEP.get()),
                s -> setInt(MaidSmartConfig.WOOD_JUNK_KEEP, s), "废石保留量：砍树途中挖穿泥土/石头产生的废石每种最多保留几组，超出销毁"));
        this.rows.add(new NumRow("搭方块清理（秒）", String.valueOf(MaidSmartConfig.WOOD_PLACED_LIFETIME.get()),
                s -> setInt(MaidSmartConfig.WOOD_PLACED_LIFETIME, s), "搭方块清理时间（秒）：搭高/搭桥的方块放置 N 秒后自动变掉落物回收"));
        this.rows.add(new BoolRow("软方块不耗耐久", MaidSmartConfig.WOOD_SOFT_NO_DURABILITY.get(),
                v -> MaidSmartConfig.WOOD_SOFT_NO_DURABILITY.set(v), "软方块（徒手可挖）开路不消耗斧耐久（砍原木本体始终扣耐久）"));
        this.rows.add(new BoolRow("搭方块防掉落", MaidSmartConfig.WOOD_PILLAR_GUARD.get(),
                v -> MaidSmartConfig.WOOD_PILLAR_GUARD.set(v), "搭方块防掉落（潜行效果，速度不变）"));
        this.rows.add(new BoolRow("硬挡路报点弃置", MaidSmartConfig.WOOD_HARD_BLOCK_REPORT.get(),
                v -> MaidSmartConfig.WOOD_HARD_BLOCK_REPORT.set(v), "硬挡路（箱子/机器等）报点弃置该木材"));
        this.rows.add(new BoolRow("连锁砍伐", MaidSmartConfig.WOOD_CHAIN_MINING.get(),
                v -> MaidSmartConfig.WOOD_CHAIN_MINING.set(v), "连锁砍伐：砍一棵树的相连木材一次砍完（树干天然相连，默认开启）"));
        this.rows.add(new BoolRow("自动收集", MaidSmartConfig.WOOD_AUTO_COLLECT.get(),
                v -> MaidSmartConfig.WOOD_AUTO_COLLECT.set(v), "自动收集：砍伐掉落物（原木/树苗/苹果）直接进女仆背包，不落地"));
        this.rows.add(new NumRow("连锁砍伐上限（块）", String.valueOf(MaidSmartConfig.WOOD_CHAIN_LIMIT.get()),
                s -> setInt(MaidSmartConfig.WOOD_CHAIN_LIMIT, s), "连锁砍伐上限（块）：一次连锁砍伐的最大方块数"));
        this.rows.add(new BoolRow("树冠清理", MaidSmartConfig.WOOD_LEAVES_CLEAR.get(),
                v -> MaidSmartConfig.WOOD_LEAVES_CLEAR.set(v), "树冠清理（默认开）：树干砍完后顺手清掉上方树冠的树叶（掉落物/树苗直接进背包）；关闭则只砍树干、树叶靠自然衰减"));
        this.rows.add(new SectionRow("目标与节奏", true));
        this.rows.add(new NumRow("砍伐距离（格）", String.valueOf(MaidSmartConfig.WOOD_REACH.get()),
                s -> setDouble(MaidSmartConfig.WOOD_REACH, s), "砍伐距离（格）：女仆伸手够得到木材的距离，默认 4.5 接近玩家手长"));
        this.rows.add(new NumRow("目标超时（tick）", String.valueOf(MaidSmartConfig.WOOD_TARGET_TIMEOUT.get()),
                s -> setInt(MaidSmartConfig.WOOD_TARGET_TIMEOUT, s), "目标超时（tick，够不到木材超时放弃）"));
        this.rows.add(new NumRow("锚点出框超时（tick）", String.valueOf(MaidSmartConfig.WOOD_ANCHOR_TIMEOUT.get()),
                s -> setInt(MaidSmartConfig.WOOD_ANCHOR_TIMEOUT, s), "锚点出框超时（tick，出框超过此时长重埋锚点）"));
        this.rows.add(new NumRow("重定位节流（tick）", String.valueOf(MaidSmartConfig.WOOD_RELOCATE_THROTTLE.get()),
                s -> setInt(MaidSmartConfig.WOOD_RELOCATE_THROTTLE, s), "重定位节流（tick，防边界抖动）"));
        this.rows.add(new NumRow("搭方块冷却（tick）", String.valueOf(MaidSmartConfig.WOOD_PILLAR_COOLDOWN.get()),
                s -> setInt(MaidSmartConfig.WOOD_PILLAR_COOLDOWN, s), "搭方块冷却（tick，垫脚下/搭路节奏）"));
        this.rows.add(new NumRow("废石清理间隔（tick）", String.valueOf(MaidSmartConfig.WOOD_JUNK_CHECK_INTERVAL.get()),
                s -> setInt(MaidSmartConfig.WOOD_JUNK_CHECK_INTERVAL, s), "废石清理间隔（tick，20=1 秒）"));
        this.rows.add(new NumRow("播报限频（tick）", String.valueOf(MaidSmartConfig.WOOD_SKIP_REPORT_INTERVAL.get()),
                s -> setInt(MaidSmartConfig.WOOD_SKIP_REPORT_INTERVAL, s), "播报限频（tick）：'斧子砍不动/木材被挡住'等提示的最短间隔"));
        this.rows.add(new NumRow("创造面板默认价值", String.valueOf(MaidSmartConfig.WOOD_CREATIVE_DEFAULT_VALUE.get()),
                s -> setInt(MaidSmartConfig.WOOD_CREATIVE_DEFAULT_VALUE, s), "创造面板默认价值：木材页锁定方块后，输入框留空直接点「添加」时用的分数"));
    }

    private void memoryRows() {
        this.rows.add(new SectionRow("AI 记忆", false));
        this.rows.add(new BoolRow("全局开关", MaidSmartConfig.MEMORY_ENABLE.get(),
                v -> MaidSmartConfig.MEMORY_ENABLE.set(v), "AI 记忆系统全局开关（per-maid 可覆盖）"));
        this.rows.add(new NumRow("提取阈值（条）", String.valueOf(MaidSmartConfig.MEMORY_EXTRACT_THRESHOLD.get()),
                s -> setInt(MaidSmartConfig.MEMORY_EXTRACT_THRESHOLD, s), "攒满多少条新对话触发一次 LLM 提取"));
        this.rows.add(new NumRow("条目上限", String.valueOf(MaidSmartConfig.MEMORY_MAX_ENTRIES.get()),
                s -> setInt(MaidSmartConfig.MEMORY_MAX_ENTRIES, s), "记忆段落上限（超出淘汰低重要度）"));
        this.rows.add(new NumRow("注入条数", String.valueOf(MaidSmartConfig.MEMORY_PROMPT_TOP_N.get()),
                s -> setInt(MaidSmartConfig.MEMORY_PROMPT_TOP_N, s), "注入条数：每次对话从记忆中挑最相关的几条注入上下文——越多 LLM 越了解女仆，token 成本越高"));
        this.rows.add(new NumRow("消息截断（字）", String.valueOf(MaidSmartConfig.MEMORY_MAX_MESSAGE_CHARS.get()),
                s -> setInt(MaidSmartConfig.MEMORY_MAX_MESSAGE_CHARS, s), "消息截断（字）：提取记忆时每条对话消息截取的最长长度，防超长对话撑爆 token"));
        this.rows.add(new SectionRow("子功能开关", true));
        this.rows.add(new BoolRow("关系注入", MaidSmartConfig.MEMORY_RELATION_INJECT.get(),
                v -> MaidSmartConfig.MEMORY_RELATION_INJECT.set(v), "关系三元组注入对话（主人-喜欢-红茶）"));
        this.rows.add(new BoolRow("冲突覆盖", MaidSmartConfig.MEMORY_CONFLICT_OVERRIDE.get(),
                v -> MaidSmartConfig.MEMORY_CONFLICT_OVERRIDE.set(v), "冲突覆盖（新记忆高重要度覆盖旧记忆）"));
        this.rows.add(new BoolRow("摘要折叠", MaidSmartConfig.MEMORY_CORE_FOLD.get(),
                v -> MaidSmartConfig.MEMORY_CORE_FOLD.set(v), "摘要折叠（核心记忆常驻+扩展按需）"));
        this.rows.add(new BoolRow("工作笔记注入", MaidSmartConfig.MEMORY_WORKING_NOTE.get(),
                v -> MaidSmartConfig.MEMORY_WORKING_NOTE.set(v), "工作笔记注入：女仆干活时的任务状态（在挖什么/缺什么料）以笔记形式跨对话注入，续上话题"));
        this.rows.add(new BoolRow("关系感知适配", MaidSmartConfig.MEMORY_RELATIONSHIP_ADAPTER.get(),
                v -> MaidSmartConfig.MEMORY_RELATIONSHIP_ADAPTER.set(v), "关系感知适配（软感知 maidmarriage 结婚/告白/父女 + Love Loathe 信任/恐惧 → 写入记忆；不依赖，未装则静默）"));
        // v1.5.190：新记忆开关（防抖写盘）
        this.rows.add(new BoolRow("防抖写盘", MaidSmartConfig.MEMORY_LAZY_SAVE.get(),
                v -> MaidSmartConfig.MEMORY_LAZY_SAVE.set(v), "记忆防抖写盘（内存累积后按扫描间隔批量落盘——减少磁盘 IO，多女仆时防止服务端卡顿；关闭=每次写入立即落盘，可靠性优先）"));
        // v1.1.0：记忆升级（情绪快照 / 人格种子 / 每日关心点 / 双 agent 提取）
        this.rows.add(new SectionRow("人格与情绪", true));
        this.rows.add(new BoolRow("情绪快照入记忆", MaidSmartConfig.MEMORY_AFFECT_SNAPSHOT.get(),
                v -> MaidSmartConfig.MEMORY_AFFECT_SNAPSHOT.set(v), "每条记忆写入时附带当时的情绪状态（PAD：愉悦/唤醒/支配/亲密/冲突/思念/受伤债/修复债）——旧记忆不受影响，仅新写入生效"));
        this.rows.add(new BoolRow("人格种子注入", MaidSmartConfig.MEMORY_PERSONA.get(),
                v -> MaidSmartConfig.MEMORY_PERSONA.set(v), "从女仆记忆目录的 persona.properties/traits.properties/core_memories.jsonl 只读投影人格——人设与聊天记忆分离，聊天不改写人格；首次自动生成默认模板（可手改）"));
        this.rows.add(new BoolRow("人设统一", MaidSmartConfig.MEMORY_PERSONA_UNIFY.get(),
                v -> MaidSmartConfig.MEMORY_PERSONA_UNIFY.set(v), "TLM 原版已有人设时，人格种子块降级为补充（只补 TLM 没有的人格参数/核心记忆，不再重复身份，冲突以 TLM 设定为准）；关=双人设并存旧行为"));
        this.rows.add(new BoolRow("每日关心点", MaidSmartConfig.MEMORY_CARE_POINTS.get(),
                v -> MaidSmartConfig.MEMORY_CARE_POINTS.set(v), "每日回顾附加'下次该怎么对主人'的行动建议（从情绪残留/边界/偏好/风格推导）——主动会话会自动复用当话题"));
        this.rows.add(new BoolRow("双 agent 提取", MaidSmartConfig.MEMORY_DUAL_AGENT.get(),
                v -> MaidSmartConfig.MEMORY_DUAL_AGENT.set(v), "摘要与事实/事件分两次独立 LLM 调用（更聚焦、互不阻塞；关=单次合并提取省 token）"));
        this.rows.add(new BoolRow("纪念日联动", MaidSmartConfig.MEMORY_HEARTFELT_ANNIVERSARY.get(),
                v -> MaidSmartConfig.MEMORY_HEARTFELT_ANNIVERSARY.set(v), "heartfelt 纪念日里程碑（7/30/100/365 天）达成/临近 → 写关系记忆 + 情绪脉冲（纪念日正向情绪、临近期待感）；heartfelt 没触发说话时 promaid 补位主动提起（不依赖，未装 heartfelt 则静默）"));
        this.rows.add(new SectionRow("调度与检索", true));
        this.rows.add(new NumRow("扫描间隔（秒）", String.valueOf(MaidSmartConfig.MEMORY_SCAN_INTERVAL.get()),
                s -> setInt(MaidSmartConfig.MEMORY_SCAN_INTERVAL, s), "扫描间隔（秒）：记忆调度器多久检查一次待提取对话/待衰减条目，调小记忆更新更及时"));
        this.rows.add(new NumRow("投影字符上限", String.valueOf(MaidSmartConfig.MEMORY_PROJECTION_CHARS.get()),
                s -> setInt(MaidSmartConfig.MEMORY_PROJECTION_CHARS, s), "投影字符上限：注入对话时每条记忆投影的字符数上限，控 token 成本"));
        this.rows.add(new NumRow("提取超时（分钟）", String.valueOf(MaidSmartConfig.MEMORY_EXTRACT_TIMEOUT_MIN.get()),
                s -> setInt(MaidSmartConfig.MEMORY_EXTRACT_TIMEOUT_MIN, s), "LLM 提取超时（分钟，超时允许重试）"));
        this.rows.add(new NumRow("检索融合参数", String.valueOf(MaidSmartConfig.MEMORY_RRF_K.get()),
                s -> setDouble(MaidSmartConfig.MEMORY_RRF_K, s), "检索融合参数（RRF k，越大越平均）"));
        this.rows.add(new NumRow("衰减周期（天）", String.valueOf(MaidSmartConfig.MEMORY_DECAY_DAYS.get()),
                s -> setInt(MaidSmartConfig.MEMORY_DECAY_DAYS, s), "记忆衰减周期（天，未访问且重要度低删除）"));
        this.rows.add(new NumRow("衰减保留重要度", String.valueOf(MaidSmartConfig.MEMORY_DECAY_SALIENCE.get()),
                s -> setInt(MaidSmartConfig.MEMORY_DECAY_SALIENCE, s), "衰减保留重要度（低于此值的非永久记忆可能被删）"));
        this.rows.add(new NumRow("关系感知扫描（秒）", String.valueOf(MaidSmartConfig.MEMORY_RELATION_SCAN.get()),
                s -> setInt(MaidSmartConfig.MEMORY_RELATION_SCAN, s), "关系感知扫描间隔（秒）：多久轮询一次 maidmarriage/爱憎分明状态变化写入记忆，调小反应更及时"));
        this.rows.add(new NumRow("信任/恐惧变化阈值", String.valueOf(MaidSmartConfig.MEMORY_TRUST_DELTA.get()),
                s -> setDouble(MaidSmartConfig.MEMORY_TRUST_DELTA, s), "信任/恐惧显著变化阈值（Love Loathe）"));
        // v1.5.191：记忆维护周期（定期固化/衰减/关系置信度衰减/error_mark 传播）
        this.rows.add(new NumRow("维护周期（分钟）", String.valueOf(MaidSmartConfig.MEMORY_MAINTENANCE_MIN.get()),
                s -> setInt(MaidSmartConfig.MEMORY_MAINTENANCE_MIN, s), "记忆维护周期（分钟）：定期固化重要记忆、衰减陈旧记忆、降旧关系置信度、传播被否定的标记——之前只有写入时才维护，老记忆永远不衰减"));
        this.rows.add(new NumRow("关系置信度衰减（天）", String.valueOf(MaidSmartConfig.MEMORY_RELATION_DECAY_DAYS.get()),
                s -> setInt(MaidSmartConfig.MEMORY_RELATION_DECAY_DAYS, s), "关系置信度衰减周期（天）：非永久关系 N 天未被强化则置信度×0.85，低到 0.15 变 inactive（不再注入/检索）"));
        // 多级记忆索引（v1.5.378~381，移植自 Sphantosis）：日/3日/周/月四级日记式摘要，
        // 睡一觉（或服务器登出）自动生成；对话可检索注入，LLM 可用 query_memory_index 翻日记
        this.rows.add(new SectionRow("多级记忆索引（日记式摘要，睡一觉自动整理）", true));
        this.rows.add(new BoolRow("多级记忆索引", MaidSmartConfig.MEMORY_INDEX_ENABLE.get(),
                v -> MaidSmartConfig.MEMORY_INDEX_ENABLE.set(v), "日/3日/周/月四级日记式摘要索引：跨游戏日/周/月边界与收尾时自动生成，永久归档；关闭后不再生成/检索/注入"));
        this.rows.add(new BoolRow("睡一觉自动处理", MaidSmartConfig.MEMORY_INDEX_ON_SLEEP.get(),
                v -> MaidSmartConfig.MEMORY_INDEX_ON_SLEEP.set(v), "玩家真实睡过夜（全员睡眠跳到清晨）时收尾：生成刚结束一天的记忆日记 + 短期记忆沉淀为长期；熬夜过夜不触发"));
        this.rows.add(new BoolRow("登出会话收尾", MaidSmartConfig.MEMORY_INDEX_ON_LOGOUT.get(),
                v -> MaidSmartConfig.MEMORY_INDEX_ON_LOGOUT.set(v), "仅服务器生效：玩家登出=真人结束一天，当日记忆收尾归档（下次进游戏自动补完成）；单机集成服无意义自动跳过"));
        this.rows.add(new NumRow("月索引保留事件数", String.valueOf(MaidSmartConfig.MEMORY_INDEX_MONTH_TOP_N.get()),
                s -> setInt(MaidSmartConfig.MEMORY_INDEX_MONTH_TOP_N, s), "月级索引按重要度保留的最大事件数（月日记只留最重要的事）"));
        this.rows.add(new NumRow("单次索引事件上限", String.valueOf(MaidSmartConfig.MEMORY_INDEX_MAX_EVENTS.get()),
                s -> setInt(MaidSmartConfig.MEMORY_INDEX_MAX_EVENTS, s), "单次生成日记时喂给 LLM 的事件数上限：超出按重要度裁剪——控制摘要上下文长度，忙日不撑爆"));
        this.rows.add(new NumRow("短期→长期阈值（游戏日）", String.valueOf(MaidSmartConfig.MEMORY_SHORT_TERM_DAYS.get()),
                s -> setInt(MaidSmartConfig.MEMORY_SHORT_TERM_DAYS, s), "短期记忆沉淀为长期的年龄阈值（游戏日）：年龄超过且重要度达标的记忆打 long_term 标记，豁免衰减遗忘"));
        // v1.5.198：记忆独立 API 绑定——填写格式同 TLM（OpenAI 兼容 地址/密钥/模型）；
        // 全留空 = 跟随 TLM 女仆当前 LLM 站点；清空某一栏即回退该项到 TLM
        this.rows.add(new SectionRow("记忆 API（留空 = 跟随 TLM）", true));
        this.rows.add(new TextRow("API 地址", MaidSmartConfig.MEMORY_API_URL.get(),
                MEMORY_API_URL_SETTER, "OpenAI 兼容 chat/completions 端点；留空 = 跟随 TLM 女仆当前 LLM 站点"));
        this.rows.add(new TextRow("API 密钥", MaidSmartConfig.MEMORY_API_KEY.get(),
                MEMORY_API_KEY_SETTER, "Bearer 密钥；留空 = 跟随 TLM（明文存 config/promaid-common.toml，与 TLM sites/llm.json 一致）"));
        this.rows.add(new TextRow("API 模型", MaidSmartConfig.MEMORY_API_MODEL.get(),
                MEMORY_API_MODEL_SETTER, "模型名（如 deepseek-chat）；留空 = 跟随 TLM 女仆当前模型"));
    }

    /** 感知页（快照对比检测） */
    private void perceptionRows() {
        this.rows.add(new SectionRow("感知（快照对比，纯规则气泡）", false));
        this.rows.add(new BoolRow("感知总开关", MaidSmartConfig.PERCEPTION_ENABLE.get(),
                v -> MaidSmartConfig.PERCEPTION_ENABLE.set(v), "感知总开关：关闭后女仆不再把环境变化（敌人出现/主人受伤/天气）写入记忆——省 token 但失去情境感知"));
        this.rows.add(new BoolRow("敌对检测", MaidSmartConfig.PERCEPTION_HOSTILE.get(),
                v -> MaidSmartConfig.PERCEPTION_HOSTILE.set(v), "敌对检测：敌人出现/接近/离开时记录到记忆（女仆会记得谁欺负过她）"));
        this.rows.add(new BoolRow("主人检测", MaidSmartConfig.PERCEPTION_OWNER.get(),
                v -> MaidSmartConfig.PERCEPTION_OWNER.set(v), "主人检测（受伤/血量低/看向女仆）"));
        this.rows.add(new BoolRow("天气检测", MaidSmartConfig.PERCEPTION_WEATHER.get(),
                v -> MaidSmartConfig.PERCEPTION_WEATHER.set(v), "天气检测：天气变化（下雨/雷暴）写入记忆，女仆会主动提起"));
        this.rows.add(new SectionRow("数值", true));
        this.rows.add(new NumRow("快照扫描间隔（tick）", String.valueOf(MaidSmartConfig.PERCEPTION_SCAN_INTERVAL.get()),
                s -> setInt(MaidSmartConfig.PERCEPTION_SCAN_INTERVAL, s), "快照扫描间隔（tick，20=1 秒）：环境快照对比的频率，调小检测更灵敏、略耗性能"));
        this.rows.add(new NumRow("同类事件限频（秒）", String.valueOf(MaidSmartConfig.PERCEPTION_EVENT_COOLDOWN.get()),
                s -> setInt(MaidSmartConfig.PERCEPTION_EVENT_COOLDOWN, s), "同类事件限频（秒）：同一类感知事件最短播报间隔，防刷屏"));
        this.rows.add(new NumRow("敌对感知显示限频（秒）", String.valueOf(MaidSmartConfig.PERCEPTION_HOSTILE_SHOW_COOLDOWN.get()),
                s -> setInt(MaidSmartConfig.PERCEPTION_HOSTILE_SHOW_COOLDOWN, s), "敌对感知显示限频（秒）：'发现怪物/怪物靠近/都清掉了'的显示间隔（v1.5.119 默认 300 秒 = 5 分钟）。感知检测照常进行（女仆仍会记住/感知到怪物），只是气泡显示频率大大降低"));
        this.rows.add(new NumRow("主人低血阈值（%）", String.valueOf(MaidSmartConfig.PERCEPTION_OWNER_LOW_HEALTH.get()),
                s -> setInt(MaidSmartConfig.PERCEPTION_OWNER_LOW_HEALTH, s), "主人低血阈值（%）：主人血量低于此值写入记忆并触发关心对话"));
        this.rows.add(new NumRow("持续注视判定（秒）", String.valueOf(MaidSmartConfig.PERCEPTION_LOOK_TICKS.get()),
                s -> setInt(MaidSmartConfig.PERCEPTION_LOOK_TICKS, s), "持续注视判定（秒）：主人盯着女仆看满 N 秒才记为'被注视'，防路过误判"));
        this.rows.add(new NumRow("看向进入角度（度）", String.valueOf(MaidSmartConfig.PERCEPTION_LOOK_ENTER_DEG.get()),
                s -> setDouble(MaidSmartConfig.PERCEPTION_LOOK_ENTER_DEG, s), "看向进入角度（度）：主人视线与女仆方向的夹角低于此值记为'看向女仆'（进入状态）"));
        this.rows.add(new NumRow("看向退出角度（度）", String.valueOf(MaidSmartConfig.PERCEPTION_LOOK_EXIT_DEG.get()),
                s -> setDouble(MaidSmartConfig.PERCEPTION_LOOK_EXIT_DEG, s), "看向退出角度（度）：夹角超过此值记为'不再看向'（退出状态，防抖动）"));
    }

    /** 情绪页（PAD 情绪层） */
    private void affectRows() {
        this.rows.add(new SectionRow("情绪（PAD 层，独立于好感/心契/爱憎）", false));
        this.rows.add(new BoolRow("情绪总开关", MaidSmartConfig.AFFECT_ENABLE.get(),
                v -> MaidSmartConfig.AFFECT_ENABLE.set(v), "PAD 情绪层总开关（事件驱动+落盘）"));
        this.rows.add(new BoolRow("注入对话", MaidSmartConfig.AFFECT_INJECT.get(),
                v -> MaidSmartConfig.AFFECT_INJECT.set(v), "情绪注入对话上下文（ai_affect）"));
        this.rows.add(new NumRow("静默恢复间隔（秒）", String.valueOf(MaidSmartConfig.AFFECT_RECOVER_INTERVAL.get()),
                s -> setInt(MaidSmartConfig.AFFECT_RECOVER_INTERVAL, s), "情绪静默恢复间隔（秒，无事件时情绪值缓慢回归）"));
    }

    /** AI 工具页 */
    private void aiToolsRows() {
        this.rows.add(new SectionRow("AI 工具（LLM 对话可调用）", false));
        this.rows.add(new BoolRow("remember（主动写记忆）", MaidSmartConfig.TOOL_REMEMBER.get(),
                v -> MaidSmartConfig.TOOL_REMEMBER.set(v), "remember 工具（LLM 主动写记忆，\"记住…\"）"));
        this.rows.add(new BoolRow("working_note（工作笔记）", MaidSmartConfig.TOOL_WORKING_NOTE.get(),
                v -> MaidSmartConfig.TOOL_WORKING_NOTE.set(v), "working_note 工具（跨对话任务笔记）"));
        this.rows.add(new BoolRow("smart_craft（帮主人合成）", MaidSmartConfig.TOOL_CRAFT.get(),
                v -> MaidSmartConfig.TOOL_CRAFT.set(v), "smart_craft 工具（按配方自动合成物品——从自己背包取材料，成品交给主人；缺材料时报缺什么）"));
        this.rows.add(new BoolRow("smart_place（帮主人放方块）", MaidSmartConfig.TOOL_PLACE.get(),
                v -> MaidSmartConfig.TOOL_PLACE.set(v), "smart_place 工具（从自己背包取出方块放到指定位置——用于\"帮我把这里填上/建一小段\"类指令，区别于蓝图建造）"));
        // v1.5.196：感知查询 / 工作清单注入工具开关
        this.rows.add(new BoolRow("perception_query（建造前探查）", MaidSmartConfig.TOOL_PERCEPTION.get(),
                v -> MaidSmartConfig.TOOL_PERCEPTION.set(v), "perception_query 工具（look_around/terrain/build_site/inspect/scanblock/scanentity——LLM 建造前先探查环境与地形，减少超时重试）"));
        this.rows.add(new BoolRow("work_list（任务清单/缺料查询）", MaidSmartConfig.TOOL_WORK_LIST.get(),
                v -> MaidSmartConfig.TOOL_WORK_LIST.set(v), "work_list 工具（query_todo/build_need——当前任务清单与建造材料缺口查询，杜绝重复轮次与\"先生成清单再开工\"的超时）"));
        // v1.5.287：查看主人物品栏工具（只读查询主人背包内容）
        this.rows.add(new BoolRow("smart_owner_inventory（查看主人背包）", MaidSmartConfig.TOOL_OWNER_INVENTORY.get(),
                v -> MaidSmartConfig.TOOL_OWNER_INVENTORY.set(v), "smart_owner_inventory 工具（只读查询主人背包里有什么——LLM 需要确认主人持有某材料/装备时调用，不修改任何物品）"));
        // v1.5.250：每日主动对话次数上限（复用 dialogue.proactiveDaily——主动对话
        // 区已有同配置，这里按用户要求放到 AI 工具设置，两处改同一个值）
        this.rows.add(new NumRow("每日主动对话上限（次/女仆）", String.valueOf(MaidSmartConfig.DIALOGUE_PROACTIVE_DAILY.get()),
                s -> setInt(MaidSmartConfig.DIALOGUE_PROACTIVE_DAILY, s), "每日主动对话上限（次/女仆）：女仆一天内主动开口说话（关心/夜晚/沉默找话题/事件感慨）的总次数上限；超过后不再发言，并在系统消息里提示\"已达上限\"。控 LLM token 成本"));
    }

    private void dialogueRows() {
        // v1.5.356：API 日配额提到对话提示区第一行——用户反馈"手册里 LLM 调用次数限制的
        // 设置选项没了"：配置一直都在,但排在区第 5 行,窗口高度/GUI 缩放较小时被分页藏到
        // 第 2+ 页(同 v1.5.293/295 的可见性修复模式)。任何窗口高度打开对话提示第一屏即可见。
        this.rows.add(new NumRow("API 日配额", String.valueOf(MaidSmartConfig.DIALOGUE_API_DAILY_LIMIT.get()),
                s -> setInt(MaidSmartConfig.DIALOGUE_API_DAILY_LIMIT, s), "所有女仆每日主动 LLM 调用总量上限（token 成本；默认 40，填 0 = 不限——旧版 0 是永远禁言的 bug）"));
        // v1.5.293：自主决策提到对话页第一屏——旧版在「主动对话」之后（本页第 14 行），
        // 窗口高度/GUI 缩放较小时被分页藏到第 2+ 页（用户反馈"详细设置里自主决策按键
        // 没了"——分区一直都在，只是第一屏看不到）。现在本区块 5 行全在第 1 页，
        // 任何窗口高度打开对话提示第一页即可见
        this.rows.add(new SectionRow("自主决策", false));
        this.rows.add(new BoolRow("自主决策", MaidSmartConfig.DIALOGUE_AUTONOMOUS.get(),
                v -> MaidSmartConfig.DIALOGUE_AUTONOMOUS.set(v), "自主决策：开启后女仆会根据时间/材料/环境自己换任务干活（去种地/去挖矿），主人可口头干预"));
        this.rows.add(new NumRow("决策冷却（分钟）", String.valueOf(MaidSmartConfig.DIALOGUE_AUTONOMOUS_COOLDOWN.get()),
                s -> setInt(MaidSmartConfig.DIALOGUE_AUTONOMOUS_COOLDOWN, s), "决策冷却（分钟）：两次自主换任务的最短间隔，防反复横跳"));
        this.rows.add(new NumRow("日上限（次）", String.valueOf(MaidSmartConfig.DIALOGUE_AUTONOMOUS_DAILY.get()),
                s -> setInt(MaidSmartConfig.DIALOGUE_AUTONOMOUS_DAILY, s), "日上限（次）：一天最多自主决策几次（控 token 成本）"));
        // v1.5.295：主动对话提到工作播报之前——主动对话是高频开关（旧版在 293 调整后
        // 仍落第 2 页；现在自主决策+主动对话两个主要开关都在第 1 页可见），
        // 工作播报（次要功能）顺延到主动对话之后
        this.rows.add(new SectionRow("主动对话", true));
        this.rows.add(new BoolRow("主动对话", MaidSmartConfig.DIALOGUE_PROACTIVE.get(),
                v -> MaidSmartConfig.DIALOGUE_PROACTIVE.set(v), "主动对话（关心/夜晚/好感等主动开口）"));
        this.rows.add(new NumRow("发言冷却（分钟）", String.valueOf(MaidSmartConfig.DIALOGUE_PROACTIVE_COOLDOWN.get()),
                s -> setInt(MaidSmartConfig.DIALOGUE_PROACTIVE_COOLDOWN, s), "发言冷却（分钟）：两次主动开口的最短间隔，防话痨"));
        this.rows.add(new NumRow("日上限（次）", String.valueOf(MaidSmartConfig.DIALOGUE_PROACTIVE_DAILY.get()),
                s -> setInt(MaidSmartConfig.DIALOGUE_PROACTIVE_DAILY, s), "主动对话日上限（次，控 token 成本；默认 12——7 阶段状态机需要更多发言额度）"));
        // v1.5.191：主动对话 7 阶段状态机配置
        this.rows.add(new NumRow("每轮发言上限（次）", String.valueOf(MaidSmartConfig.DIALOGUE_PROACTIVE_MAX_REPLIES.get()),
                s -> setInt(MaidSmartConfig.DIALOGUE_PROACTIVE_MAX_REPLIES, s), "每轮主动会话最多发言次数（1-7）：主人一次互动周期内女仆最多主动开口几次——7 阶段不会一次性全喷，默认 4 够用"));
        this.rows.add(new NumRow("空闲重启（分钟）", String.valueOf(MaidSmartConfig.DIALOGUE_PROACTIVE_IDLE_MIN.get()),
                s -> setInt(MaidSmartConfig.DIALOGUE_PROACTIVE_IDLE_MIN, s), "主动对话空闲重启（分钟）：一轮跑完/被打断后，主人 N 分钟没互动才重启新周期"));
        this.rows.add(new NumRow("长沉默确认上限（次）", String.valueOf(MaidSmartConfig.DIALOGUE_LONG_SILENCE_MAX.get()),
                s -> setInt(MaidSmartConfig.DIALOGUE_LONG_SILENCE_MAX, s), "长沉默确认每日上限（次）：\"主人还在吗\"这类确认一天最多几次，防烦人（0=彻底不确认）"));
        this.rows.add(new BoolRow("回复反馈学习", MaidSmartConfig.DIALOGUE_REPLY_FEEDBACK.get(),
                v -> MaidSmartConfig.DIALOGUE_REPLY_FEEDBACK.set(v), "回复反馈学习：主人说\"别说了/好烦\"→ 记 error_mark、当天不再提该话题、语气转克制；说\"谢谢/说得对\"→ 强化记忆；真沉默计时（主人多久没说话）也靠它"));
        this.rows.add(new NumRow("话题冷却（分钟）", String.valueOf(MaidSmartConfig.DIALOGUE_TOPIC_BACKOFF_MIN.get()),
                s -> setInt(MaidSmartConfig.DIALOGUE_TOPIC_BACKOFF_MIN, s), "话题冷却（分钟）：被主人否定的主动话题 N 分钟内不再提起"));
        // v1.5.295：工作播报移到主动对话之后（次要功能；v1.5.293 自主决策已上移）
        this.rows.add(new SectionRow("工作播报", true));
        this.rows.add(new BoolRow("工作状态播报", MaidSmartConfig.DIALOGUE_STATUS_REPORTER.get(),
                v -> MaidSmartConfig.DIALOGUE_STATUS_REPORTER.set(v), "工作状态播报（女仆卡住时气泡解释原因）"));
        this.rows.add(new NumRow("播报间隔（秒）", String.valueOf(MaidSmartConfig.DIALOGUE_REPORT_INTERVAL.get()),
                s -> setInt(MaidSmartConfig.DIALOGUE_REPORT_INTERVAL, s), "播报间隔（秒）：女仆工作状态气泡的最短间隔，防一直刷屏"));
        this.rows.add(new NumRow("播报范围", String.valueOf(MaidSmartConfig.DIALOGUE_REPORT_RADIUS.get()),
                s -> setInt(MaidSmartConfig.DIALOGUE_REPORT_RADIUS, s), "播报范围（格）：工作播报只发给这个半径内的主人（远处不打扰）"));
        // v1.5.293：自主决策区块已上移到本页第一屏（见 dialogueRows 头部）
        this.rows.add(new SectionRow("内部节奏", true));
        this.rows.add(new NumRow("播报检查间隔（tick）", String.valueOf(MaidSmartConfig.DIALOGUE_REPORT_CHECK.get()),
                s -> setInt(MaidSmartConfig.DIALOGUE_REPORT_CHECK, s), "播报检查间隔（tick）：工作状态检查/播报的轮询周期"));
        this.rows.add(new NumRow("主动对话扫描间隔（秒）", String.valueOf(MaidSmartConfig.DIALOGUE_PROACTIVE_SCAN.get()),
                s -> setInt(MaidSmartConfig.DIALOGUE_PROACTIVE_SCAN, s), "主动对话扫描间隔（秒）：主动对话触发条件（夜晚/低血/好感）的扫描周期"));
        this.rows.add(new NumRow("主动关心低血阈值（%）", String.valueOf(MaidSmartConfig.DIALOGUE_PROACTIVE_LOW_HP.get()),
                s -> setInt(MaidSmartConfig.DIALOGUE_PROACTIVE_LOW_HP, s), "主动关心低血阈值（%）：主人血量低于此值时女仆主动关心"));
        this.rows.add(new NumRow("事件驱动冷却（秒）", String.valueOf(MaidSmartConfig.DIALOGUE_PROACTIVE_EVENT_CD.get()),
                s -> setInt(MaidSmartConfig.DIALOGUE_PROACTIVE_EVENT_CD, s), "主动对话事件驱动冷却（秒，重伤/死亡等紧急事件）"));
        this.rows.add(new NumRow("自主检查间隔（秒）", String.valueOf(MaidSmartConfig.DIALOGUE_AUTO_SCAN.get()),
                s -> setInt(MaidSmartConfig.DIALOGUE_AUTO_SCAN, s), "自主检查间隔（秒）：自主决策条件（时间/材料）的检查周期"));
        this.rows.add(new NumRow("自主触发主人范围", String.valueOf(MaidSmartConfig.DIALOGUE_AUTO_OWNER_RANGE.get()),
                s -> setInt(MaidSmartConfig.DIALOGUE_AUTO_OWNER_RANGE, s), "自主触发范围（格）：主人距离小于此值时女仆才自主换任务（离太远不瞎折腾）"));
        this.rows.add(new NumRow("自主工作开始时刻", String.valueOf(MaidSmartConfig.DIALOGUE_AUTO_DAY_START.get()),
                s -> setInt(MaidSmartConfig.DIALOGUE_AUTO_DAY_START, s), "自主决策工作开始时刻（游戏 tick）"));
        this.rows.add(new NumRow("自主工作结束时刻", String.valueOf(MaidSmartConfig.DIALOGUE_AUTO_DAY_END.get()),
                s -> setInt(MaidSmartConfig.DIALOGUE_AUTO_DAY_END, s), "自主决策工作结束时刻（游戏 tick）"));
        // v1.5.198：对话输出语言强制（"突然全是日语"修复——原版按客户端游戏语言
        // 要求 LLM 输出，每次对话写入女仆 ChatLanguage）
        // v1.5.303：手填文本框改为【选项选择】（用户："设计成选择项目吧，让人对
        // 着选项选——手填容易填错或无效"）——留空=跟随游戏/客户端语言，选语言=
        // 强制该语言代码（zh_cn/en_us/ja_jp/ko_kr/ru_ru 等），不再有填错风险
        this.rows.add(new SectionRow("输出语言", true));
        String[][] langChoices = {
                {"强制中文（默认）", ""},
                {"中文", "zh_cn"},
                {"英文", "en_us"},
                {"日文", "ja_jp"},
                {"韩文", "ko_kr"},
                {"俄语", "ru_ru"},
        };
        String curLangVal = MaidSmartConfig.DIALOGUE_OUTPUT_LANGUAGE.get();
        String langCurrent = langChoices[0][0];
        for (String[] c : langChoices) {
            if (c[1].equals(curLangVal)) {
                langCurrent = c[0];
                break;
            }
        }
        this.rows.add(new CycleRow("对话输出语言",
                java.util.Arrays.stream(langChoices).map(c -> c[0]).toArray(String[]::new),
                langCurrent,
                v -> {
                    for (String[] c : langChoices) {
                        if (c[0].equals(v)) {
                            MaidSmartConfig.DIALOGUE_OUTPUT_LANGUAGE.set(c[1]);
                            break;
                        }
                    }
                },
                "对话输出语言：控制 LLM 回复的语言（写入女仆 ChatLanguage）。跟随 = 用游戏客户端当前语言（客户端语言被改过时女仆会跟着变，如突然说日语）；选具体语言 = 强制该语言，无论客户端是什么"));
    }

    /** v1.5.198：语音页——TTS 音量倍率 / 系统消息朗读 / 系统语音包导入 / 语音缓存 */
    private void voiceRows() {
        this.rows.add(new SectionRow("TTS 播放", false));
        this.rows.add(new NumRow("音量倍率", String.valueOf(MaidSmartConfig.TTS_VOLUME_MULTIPLIER.get()),
                s -> setDouble(MaidSmartConfig.TTS_VOLUME_MULTIPLIER, s),
                "TTS 语音播放音量倍率（与伤害/减伤无关！）：TLM 播放 TTS 语音的原始音量为 1.0（偏小），此值直接乘在播放音量上——1.5 = 音量放大 50%，2.0 = 放大一倍，0.5 = 减半。默认 2.0，范围 0.1-5.0。作用于 LLM 对话 TTS 与系统消息 TTS"));
        this.rows.add(new BoolRow("系统消息朗读", MaidSmartConfig.TTS_SYSTEM_ENABLED.get(),
                v -> MaidSmartConfig.TTS_SYSTEM_ENABLED.set(v),
                "系统消息朗读：感知/工作/自保等规则气泡也播放 TTS 语音（需 TLM 的 TTS 总开关开启）"));
        this.rows.add(new NumRow("朗读冷却（秒）", String.valueOf(MaidSmartConfig.TTS_SYSTEM_COOLDOWN_S.get()),
                s -> setInt(MaidSmartConfig.TTS_SYSTEM_COOLDOWN_S, s),
                "同一女仆两次系统朗读的最小间隔（秒，防连续气泡轰炸 TTS）"));
        this.rows.add(new SectionRow("系统语音包（config/maid_smart/system_voice/）", true));
        this.rows.add(new BoolRow("启用语音包", MaidSmartConfig.TTS_VOICE_PACK_ENABLED.get(),
                v -> MaidSmartConfig.TTS_VOICE_PACK_ENABLED.set(v),
                "系统语音包：manifest.json 把系统消息文本映射到 ogg 音频，命中则免 TTS 直接播放（一次制作永久使用）"));
        this.rows.add(new TextRow("导入路径", "", s -> {
            if (s != null && !s.trim().isEmpty()) {
                com.maidsmart.build.BlueprintBookNetworking.CHANNEL.sendToServer(
                        new com.maidsmart.build.BlueprintBookNetworking.VoicePackImportPacket(s.trim()));
            }
            return true;
        }, "语音包 zip 或文件夹的绝对路径；填写后保存即自动导入并生效"));
        // v1.5.250：文件选择对话框导入——玩家不用手填路径，点按钮选 .zip 即可
        this.rows.add(new BtnRow("导入语音包", "选择文件导入", () -> {
                    // FileDialog setVisible 会阻塞当前线程——放独立线程，避免卡死
                    // 游戏渲染（MC 主线程就是 AWT EDT）；daemon=true 防对话框挂着阻 JVM 退出
                    Thread fileDlg = new Thread(() -> {
                        try {
                            java.awt.FileDialog fd = new java.awt.FileDialog((java.awt.Frame) null,
                                    "\u9009\u62e9\u8bed\u97f3\u5305(\u300czip \u6216\u6587\u4ef6\u5939)",
                                    java.awt.FileDialog.LOAD);
                            fd.setFilenameFilter((d, name) -> name.toLowerCase(
                                    java.util.Locale.ROOT).endsWith(".zip"));
                            fd.setVisible(true);
                            String dir = fd.getDirectory();
                            String file = fd.getFile();
                            fd.dispose();
                            if (dir == null || file == null) {
                                return; // 取消
                            }
                            String path = dir + file;
                            net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.m_91087_();
                            // 审计：AWT 文件对话框线程不能直接操作 MC 客户端对象/网络通道，
                            // 选完后切回 MC 主线程再发消息与发包。
                            mc.m_18707_(() -> {
                                if (mc.f_91074_ != null) {
                                    mc.f_91074_.m_213846_(net.minecraft.network.chat.Component.m_237113_(
                                            "\u00a7e[maid_smart] \u6b63\u5728\u5bfc\u5165\u8bed\u97f3\u5305: " + path));
                                }
                                com.maidsmart.build.BlueprintBookNetworking.CHANNEL.sendToServer(
                                        new com.maidsmart.build.BlueprintBookNetworking.VoicePackImportPacket(path));
                            });
                        } catch (Exception ignored) {
                        }
                    });
                    fileDlg.setDaemon(true);
                    fileDlg.start();
                },
                "打开系统文件选择框选 .zip 语音包自动导入（导入文件夹仍可用上方路径填写）"));
        this.rows.add(new BtnRow("重新加载语音包", "重新加载", () -> {
                    // v1.5.217：点击即时反馈（服务端结果会回聊天框，这里先提示已请求）
                    net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.m_91087_();
                    if (mc.f_91074_ != null) {
                        mc.f_91074_.m_213846_(net.minecraft.network.chat.Component.m_237113_(
                                "\u00a7e[maid_smart] 已请求重新加载语音包，结果请看聊天框"));
                    }
                    com.maidsmart.build.BlueprintBookNetworking.CHANNEL.sendToServer(
                            new com.maidsmart.build.BlueprintBookNetworking.VoicePackQueryPacket("reload"));
                },
                "从磁盘重新读取 manifest（手动改文件后点此生效）"));
        this.rows.add(new BtnRow("查看语音包状态", "查看状态", () -> {
                    net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.m_91087_();
                    if (mc.f_91074_ != null) {
                        mc.f_91074_.m_213846_(net.minecraft.network.chat.Component.m_237113_(
                                "\u00a7e[maid_smart] 已请求查看语音包状态，结果请看聊天框"));
                    }
                    com.maidsmart.build.BlueprintBookNetworking.CHANNEL.sendToServer(
                            new com.maidsmart.build.BlueprintBookNetworking.VoicePackQueryPacket("status"));
                },
                "查看当前已加载的文本映射条数与 TTS 语音缓存文件数"));
        this.rows.add(new NumRow("缓存上限（个）", String.valueOf(MaidSmartConfig.TTS_CACHE_MAX_FILES.get()),
                s -> setInt(MaidSmartConfig.TTS_CACHE_MAX_FILES, s),
                "TTS 语音缓存上限（voice_cache/，训练一次保存后复用；超出删最旧）"));
    }

    /** v1.5.294：被动技能独立栏（用户："被动技能要单拉出来一栏放在 Promaid 模组详细
     *  配置里面，而不是放在战斗自保里面"）——落地水/岩浆逃生放水/主人死亡传送，
     *  全是被动保命动作，与战斗自保页的主动行为（自保策略/贴身辅助/单兵战术）分离 */
    private void passiveRows() {
        this.rows.add(new SectionRow("被动技能", false));
        this.rows.add(new BoolRow("落地水", MaidSmartConfig.COMBAT_WATER_CLUTCH.get(),
                v -> MaidSmartConfig.COMBAT_WATER_CLUTCH.set(v), "落地水（有水桶+坠落自动放水缓冲）"));
        this.rows.add(new NumRow("落地水触发高度", String.valueOf(MaidSmartConfig.COMBAT_WATER_FALL_DISTANCE.get()),
                s -> setDouble(MaidSmartConfig.COMBAT_WATER_FALL_DISTANCE, s), "落地水触发高度（格）：坠落高度超过此值才放水缓冲"));
        this.rows.add(new NumRow("落地水保持（tick）", String.valueOf(MaidSmartConfig.COMBAT_WATER_HOLD.get()),
                s -> setInt(MaidSmartConfig.COMBAT_WATER_HOLD, s), "落地水保持（tick）：放出的水保留多久后收回（防留一滩水）"));
        this.rows.add(new NumRow("落地水下探格数", String.valueOf(MaidSmartConfig.COMBAT_WATER_LANDING_SCAN.get()),
                s -> setInt(MaidSmartConfig.COMBAT_WATER_LANDING_SCAN, s), "落地水下探格数：提前向下探测几格判断要不要放水（防高空误放）"));
        // v1.1.0：落地雪——细雪桶版落地水（下界水会蒸发、细雪不会）
        this.rows.add(new BoolRow("落地雪", MaidSmartConfig.COMBAT_SNOW_CLUTCH.get(),
                v -> MaidSmartConfig.COMBAT_SNOW_CLUTCH.set(v), "落地雪（细雪桶版落地水，默认开）：高空坠落时在落点平面铺 1×1 细雪垫接住她并收回（桶不消耗）——细雪不流动，落点必须正好是雪：1×1 无容错，能否接住全靠坠落途中逐 tick 跟着落点补垫（偏一格即空摔，追求稳请用水桶），且绝不在高处拦她（细雪减速后剩下的路照样摔）；下界也能用（水会蒸发、细雪不会）；细雪接触 7 秒才开始冻伤、收回上限 5 秒在安全线内；与落地水共用触发高度/保持时长/下探格数，两者都有桶时优先用水"));
        // v1.5.199：水桶垫水（岩浆灭火，1 秒后收回，水桶不消耗；击退搭高垫水
        // v1.5.250 已删除）
        this.rows.add(new BoolRow("岩浆逃生放水", MaidSmartConfig.COMBAT_WATER_BUCKET_LAVA.get(),
                v -> MaidSmartConfig.COMBAT_WATER_BUCKET_LAVA.set(v), "岩浆逃生放水：垫高后周围没有水源且包里有水桶 → 在自己垫的方块上放水灭火（1 秒后收回；接触的岩浆源可能变黑曜石）"));
        this.rows.add(new BoolRow("主人死亡传送", MaidSmartConfig.COMBAT_MASTER_DEATH_TELEPORT.get(),
                v -> MaidSmartConfig.COMBAT_MASTER_DEATH_TELEPORT.set(v), "主人死亡强制传送（无视战斗/距离）"));
        // v1.1.0：搭路（主人在上方时垫方块靠近——借鉴僵尸搭方块追人）
        this.rows.add(new SectionRow("搭路（v1.1.0，默认关）", true));
        this.rows.add(new BoolRow("搭路", MaidSmartConfig.BRIDGE_ENABLED.get(),
                v -> MaidSmartConfig.BRIDGE_ENABLED.set(v), "搭路：周围无威胁、女仆背包有方块时，她走过去垫方块靠近主人——主人【不低于女仆】时水平多远都启动平桥追逐（前方悬空铺桥、实心地面走路，参考僵尸搭桥追人，v1.1.0 实测一百六十五）；主人【更高】时垂直搭高靠近（搭的方块 N 秒后自动回收）；默认关闭"));
        this.rows.add(new NumRow("搭路触发距离（格）", String.valueOf(MaidSmartConfig.BRIDGE_MAX_DIST.get()),
                s -> setInt(MaidSmartConfig.BRIDGE_MAX_DIST, s), "搭路触发距离（格，默认 7）：主人【高于女仆】需垂直搭高时的启动上限——超过交给传送/跟随；平路/低高差追逐（主人不低于女仆）不受此限制，水平多远都启动平桥追逐（v1.1.0 实测一百六十五）"));
        this.rows.add(new NumRow("空中搭桥距离（格）", String.valueOf(MaidSmartConfig.BRIDGE_AIR_MAX_DIST.get()),
                s -> setInt(MaidSmartConfig.BRIDGE_AIR_MAX_DIST, s), "空中搭桥距离（格，默认 128）：主人【高于女仆】需爬高/或女仆已在空中时，你离得再远她也直接铺桥走过来——空中没有'走路过去'的选项；设 0 关闭远距（只保留近距逻辑）。平路/低高差追逐已不受任何距离上限约束（v1.1.0 实测一百六十五）"));
        this.rows.add(new NumRow("最小高差（格）", String.valueOf(MaidSmartConfig.BRIDGE_MIN_DY.get()),
                s -> setInt(MaidSmartConfig.BRIDGE_MIN_DY, s), "最小高差（格，默认 2）：你至少高于女仆这么多格才搭路（平路走路处理）"));
        this.rows.add(new NumRow("最小球面半径（格）", String.valueOf(MaidSmartConfig.BRIDGE_MIN_RADIUS.get()),
                s -> setInt(MaidSmartConfig.BRIDGE_MIN_RADIUS, s), "最小球面半径（格，默认 2）：以女仆为圆心的 3D 半径（竖直+水平一起算）——你在球面内不启桥，靠跟随走路；球面外高差够→垂直搭高，竖直差不多+水平远+脚下悬空→平铺搭桥；实心地面平路纯走导航不启桥（防反复启停抖动）"));
        // v1.1.0 实测一百八十七：平桥启动水平距离（用户："水平距离搭建方块有没有启动要求呢？加个启动要求"）
        this.rows.add(new NumRow("平桥启动距离（格）", String.valueOf(MaidSmartConfig.BRIDGE_START_H_DIST.get()),
                s -> setDouble(MaidSmartConfig.BRIDGE_START_H_DIST, s), "平桥启动水平距离（格，默认 6）：女仆与你【水平距离】达到此值、且朝你方向前方脚下悬空才启动水平搭桥（垫块踩过去）——旧版 2.5 格就启动太敏感（你就在沟对面几步远也垫块、来回启停）；设 3 = 最灵敏（接近旧版行为）。竖直搭高（你更高、原地垫柱）不受影响"));
        this.rows.add(new NumRow("威胁半径（格）", String.valueOf(MaidSmartConfig.BRIDGE_THREAT_DIST.get()),
                s -> setInt(MaidSmartConfig.BRIDGE_THREAT_DIST, s), "威胁半径（格，默认 8）：周围此范围内有敌对生物时不搭路（搭一半挨打）；刷怪频繁的包里可再调小，过大会导致搭路几乎永不触发"));
        this.rows.add(new NumRow("搭路节奏（tick/块）", String.valueOf(MaidSmartConfig.BRIDGE_STEP_COOLDOWN.get()),
                s -> setInt(MaidSmartConfig.BRIDGE_STEP_COOLDOWN, s), "搭路节奏（tick/块）：每垫一块方块的最短间隔，调大更从容"));
        this.rows.add(new NumRow("搭路方块清理（秒）", String.valueOf(MaidSmartConfig.BRIDGE_PLACED_LIFETIME.get()),
                s -> setInt(MaidSmartConfig.BRIDGE_PLACED_LIFETIME, s), "搭路方块清理时间（秒）：垫的方块 N 秒后自动变掉落物回收（女仆站在上面时会刷新计时，走开后才开始倒数）"));
        this.rows.add(new NumRow("战斗搭方块清理（秒）", String.valueOf(MaidSmartConfig.COMBAT_PLACED_LIFETIME.get()),
                s -> setInt(MaidSmartConfig.COMBAT_PLACED_LIFETIME, s), "战斗搭方块清理时间（秒，默认 60）：自保行为（搭高/翻墙/搭桥/封头盖帽）搭的方块 N 秒后自动回收——战斗节奏多变比挖矿/搭路的 10 秒长；女仆踩着时刷新计时，不会把她摔下去"));
        this.rows.add(new BoolRow("垫脚方块回收进背包", MaidSmartConfig.BRIDGE_RECLAIM_TO_MAID.get(),
                v -> MaidSmartConfig.BRIDGE_RECLAIM_TO_MAID.set(v), "搭路垫脚方块回收进背包（默认开，全局开关——搭路/挖矿/伐木/战斗搭方块一切女仆搭的垫脚方块都适用）：开启后到期/被摧毁的垫脚方块不掉落地面，直接塞回附近女仆（8 格内最近者）的背包——背包满/附近没女仆才落地成掉落物"));
    }

    private void combatRows() {
        // v1.5.202：自保轻量化（逃生/搭高/逃跑等主动保命行为）；
        // v1.5.294：落地水/岩浆放水/主人死亡传送等【被动技能】已独立成栏（首页被动技能按钮）
        // v1.5.295：本页重排——高频开关（自保行为/贴身辅助/单兵战术）排前面，逃生/
        // 搭高/逃跑的数值参数集中到页尾"自保参数"（旧版 41 行：贴身辅助在第 3 页、
        // 单兵战术在第 4 页——GUI 缩放 2 时"自动投喂/治疗主人"等关键开关被翻页藏住，
        // 与"自主决策按键没了"同类问题；现在所有开关前 2 页内可见）
        this.rows.add(new SectionRow("逃生与自保（被动保命）", false));
        this.rows.add(new BoolRow("自保行为", MaidSmartConfig.COMBAT_SELF_PRESERVE.get(),
                v -> MaidSmartConfig.COMBAT_SELF_PRESERVE.set(v), "自保行为（轻量被动：环境危险/低血时插保命动作——喝药/垫高/逃跑/传送；平时零干预，与战斗/战术并行不冲突）"));
        // v1.1.0 实测一百五十三/一百五十四：TLM 保护饰品识别（火焰/溺水）
        this.rows.add(new BoolRow("火焰保护饰品识别", MaidSmartConfig.COMBAT_FIRE_PROTECT_BAUBLE.get(),
                v -> MaidSmartConfig.COMBAT_FIRE_PROTECT_BAUBLE.set(v), "佩戴 TLM 火焰保护饰品（火焰伤害免疫+受伤时给 15 秒抗火并喷灭火剂）时，着火/泡岩浆不再惊慌灭火/找水/往主人身边跑——饰品自己会处理；关闭 = 旧行为"));
        this.rows.add(new BoolRow("溺水保护饰品识别", MaidSmartConfig.COMBAT_DROWN_PROTECT_BAUBLE.get(),
                v -> MaidSmartConfig.COMBAT_DROWN_PROTECT_BAUBLE.set(v), "佩戴 TLM 溺水保护饰品（溺水伤害免疫+空气自动补满）时，泡水不再喊\"溺水\"上浮找空气/喝水肺；关闭 = 旧行为"));
        // v1.1.0 实测一百五十二：有增益也喂牛奶（女仆自己喝 + 给主人喂两处共用）
        this.rows.add(new BoolRow("有增益也喂牛奶", MaidSmartConfig.MISC_MILK_FEED_WITH_BUFF.get(),
                v -> MaidSmartConfig.MISC_MILK_FEED_WITH_BUFF.set(v), "女仆自己喝牛奶解负面 / 给主人喂牛奶解负面时，身上有增益效果（很多装备/饰品带永久增益，旧版\"无增益才喂\"导致中毒/凋零也不解）也照喂——牛奶会连增益一起清掉；关闭 = 有增益时不喂牛奶（只喂蜂蜜解中毒）"));
        // v1.1.0 实测一百五十五：保命物品下保留逃跑
        this.rows.add(new BoolRow("保命物品下保留逃跑", MaidSmartConfig.COMBAT_FLEE_WITH_SAVE_ITEM.get(),
                v -> MaidSmartConfig.COMBAT_FLEE_WITH_SAVE_ITEM.set(v), "携带保命物品（TLM 绀珠之药 / 不死图腾）时是否还逃跑：默认关 = 有保命物品就不逃跑（她死不了，继续战斗/垫高/治疗，不丢下工作）；开 = 照常逃跑"));
        // v1.5.189：玩家贴身辅助（被动技能，非工作状态）
        this.rows.add(new SectionRow("贴身辅助（v1.5.189）", true));
        this.rows.add(new BoolRow("自动投喂/治疗主人", MaidSmartConfig.AID_OWNER_ENABLE.get(),
                v -> MaidSmartConfig.AID_OWNER_ENABLE.set(v), "自动投喂/治疗：主人饿/血低自动喂熟食或投掷治疗药水（被动技能，非工作状态）"));
        this.rows.add(new NumRow("投喂触发饱食度", String.valueOf(MaidSmartConfig.AID_FOOD_THRESHOLD.get()),
                s -> setInt(MaidSmartConfig.AID_FOOD_THRESHOLD, s), "投喂触发饱食度（4-20，20=只要不满就喂）：主人饱食度低于此值自动喂食（默认 12）——v1.5.301 起填 20 真实生效（旧版范围上限 18，填 20 被静默钳回 18）"));
        this.rows.add(new NumRow("治疗触发血量（0-1）", String.valueOf(MaidSmartConfig.AID_HEALTH_THRESHOLD.get()),
                s -> setDouble(MaidSmartConfig.AID_HEALTH_THRESHOLD, s), "治疗触发血量（0.1-1，1=掉血就治）：主人血量低于此比例自动治疗（默认 0.30）"));
        this.rows.add(new BoolRow("被动插火把", MaidSmartConfig.TORCH_PLACER_ENABLE.get(),
                v -> MaidSmartConfig.TORCH_PLACER_ENABLE.set(v), "被动插火把：主人周围黑暗自动插火把照明（消耗背包火把）"));
        // v1.1.0 实测六十二：女仆着火不传主人
        this.rows.add(new BoolRow("女仆着火不传主人", MaidSmartConfig.MAID_FIRE_GUARD.get(),
                v -> MaidSmartConfig.MAID_FIRE_GUARD.set(v), "女仆着火不传主人（默认开）：燃烧的女仆贴着主人时不会把火烧到主人身上——燃烧女仆对主人的伤害直接取消，接触传火每半秒检查一次自动给主人灭火；主人自己站火里/岩浆里则不干预"));
        this.rows.add(new NumRow("插火把亮度阈值", String.valueOf(MaidSmartConfig.TORCH_DARK_THRESHOLD.get()),
                s -> setInt(MaidSmartConfig.TORCH_DARK_THRESHOLD, s), "插火把亮度阈值（0-15）：主人脚下方块亮度低于此值自动插火把（默认 7）"));
        this.rows.add(new BoolRow("共享盾牌", MaidSmartConfig.SHIELD_SHARE_ENABLE.get(),
                v -> MaidSmartConfig.SHIELD_SHARE_ENABLE.set(v), "共享盾牌：主人盾牌耐久低/空时从女仆背包取盾给主人（不动女仆自己副手）"));
        this.rows.add(new BoolRow("共享不死图腾", MaidSmartConfig.TOTEM_SHARE_ENABLE.get(),
                v -> MaidSmartConfig.TOTEM_SHARE_ENABLE.set(v), "共享不死图腾：主人致命伤时女仆背包/饰品栏的不死图腾优先救主人（特效同原版）"));
        // v1.5.207：玩家对女仆伤害策略（TLM 原版 = 主人攻击 ÷5 封顶 2 点——原版剑
        // 看起来打不到、高伤武器（更好的战斗等）能打出 2 点；这里给玩家自选）
        // v1.5.252h：current 改用【选项文字】——旧版传数字 "0"~"4" 与文字选项永不
        // 匹配（CycleButton 显示错位），onChange 按文字下标回写配置
        String[] dmgModes = {"TLM原版(÷5封顶2)", "完全免疫", "无限制", "有上限(比例)", "仅一点伤害(上限1)"};
        int dmgMode = Math.max(0, Math.min(dmgModes.length - 1, MaidSmartConfig.PLAYER_DAMAGE_MODE.get()));
        this.rows.add(new CycleRow("玩家对女仆伤害", dmgModes,
                dmgModes[dmgMode],
                v -> {
                    int idx = java.util.Arrays.asList(dmgModes).indexOf(v);
                    MaidSmartConfig.PLAYER_DAMAGE_MODE.set(idx >= 0 ? idx : 0);
                },
                "玩家对女仆伤害模式：TLM原版 = 主人攻击 ÷5 封顶 2 点（原版剑基本打不掉血、高伤武器能打出 2 点）；完全免疫 = 任何玩家都打不到女仆（含弓弩）；无限制 = 像打普通生物一样；有上限 = 单次伤害不超过女仆最大生命 × 下方比例；仅一点伤害 = 单次伤害上限 1 点（被打有反馈但不疼）"));
        this.rows.add(new NumRow("玩家伤害上限比例（0-1）", String.valueOf(MaidSmartConfig.PLAYER_DAMAGE_MAID_CAP.get()),
                s -> setDouble(MaidSmartConfig.PLAYER_DAMAGE_MAID_CAP, s), "玩家伤害上限比例（0-1，模式=有上限时生效）：单次伤害 = 女仆最大生命 × 此比例（默认 0.1 = 10%，20 血女仆单次最多 2 点）"));
        // v1.5.134：单兵作战战术（替代已删除的 v1.5.132 战斗协同）
        this.rows.add(new SectionRow("单兵战术（v1.5.134）", true));
        this.rows.add(new BoolRow("单兵作战战术", MaidSmartConfig.COMBAT_TACTICS.get(),
                v -> MaidSmartConfig.COMBAT_TACTICS.set(v), "单兵作战战术总开关：绕圈走位/打退拉扯/距离控制/时机举盾（PVP 式战斗，战斗女仆单打独斗）"));
        this.rows.add(new BoolRow("近战战术", MaidSmartConfig.COMBAT_TACTICS_MELEE.get(),
                v -> MaidSmartConfig.COMBAT_TACTICS_MELEE.set(v), "近战战术：贴脸绕圈侧移（少正面挨刀）、打一刀退一步（hit&run 拉扯）、接近时跳劈"));
        // v1.5.280：近战贴脸后退（默认开——女仆手长 3 格，拉开后照样砍得到）
        this.rows.add(new BoolRow("近战贴脸后退", MaidSmartConfig.COMBAT_TACTICS_MELEE_KITE.get(),
                v -> MaidSmartConfig.COMBAT_TACTICS_MELEE_KITE.set(v), "近战贴脸后退：敌人贴进 2 格内主动后退拉开距离（不再贴身互搏白挨刀；女仆手长 3 格退开后照样砍得到，与打一刀退一步/跳劈节奏互补）"));
        this.rows.add(new BoolRow("远程战术", MaidSmartConfig.COMBAT_TACTICS_RANGED.get(),
                v -> MaidSmartConfig.COMBAT_TACTICS_RANGED.set(v), "远程战术：保持理想射程（原版会走到怪脸上射）、横移绕圈放风筝"));
        this.rows.add(new BoolRow("时机举盾", MaidSmartConfig.COMBAT_TACTICS_SHIELD.get(),
                v -> MaidSmartConfig.COMBAT_TACTICS_SHIELD.set(v), "时机举盾：攻击冷却间隙举盾格挡、冷却满放盾攻击（攻防交替；替代原版 8 格内一直举盾）"));
        this.rows.add(new NumRow("绕圈半径（格）", String.valueOf(MaidSmartConfig.COMBAT_TACTICS_ORBIT_RADIUS.get()),
                s -> setDouble(MaidSmartConfig.COMBAT_TACTICS_ORBIT_RADIUS, s), "绕圈半径（格）：近战贴脸绕圈 / 远程横移的圆周半径，越小打得越密、越大越飘"));
        this.rows.add(new NumRow("远程理想射程倍率", String.valueOf(MaidSmartConfig.COMBAT_TACTICS_KITE_RANGE.get()),
                s -> setDouble(MaidSmartConfig.COMBAT_TACTICS_KITE_RANGE, s), "远程理想射程倍率：0.6 = 保持在武器最大射程 60% 的距离放风筝（远了追、近了退）；适用弓（射程15）/弩（射程8）/三叉戟（搜索半径）/枪械（TLM 枪械中距离配置）"));
        // v1.1.0：主动切换战斗模式（主人受攻击 → 附近女仆切战斗）
        this.rows.add(new SectionRow("主动切换战斗（v1.1.0）", true));
        this.rows.add(new BoolRow("主动切换战斗模式", MaidSmartConfig.COMBAT_AUTO_SWITCH.get(),
                v -> MaidSmartConfig.COMBAT_AUTO_SWITCH.set(v), "主动切换战斗模式：主人被有来源的攻击（怪/玩家/弹射物；摔落岩浆等环境伤害不算）或主人攻击了别的生物时，附近的女仆无论在干什么（挖矿/伐木/烹饪/跟随…）都立即切战斗模式保护主人；女仆自己被怪物攻击也会让她本人+周围姐妹立即参战（实测五十八）；默认开启"));
        this.rows.add(new NumRow("响应半径（格）", String.valueOf(MaidSmartConfig.COMBAT_AUTO_SWITCH_RADIUS.get()),
                s -> setInt(MaidSmartConfig.COMBAT_AUTO_SWITCH_RADIUS, s), "响应半径（格）：主人受伤或开火时，此半径内的女仆才会响应切换"));
        // v1.1.0 实测二十一：武器权重可配置（选任务时加权随机——模组/原版各一条）
        this.rows.add(new NumRow("模组武器权重", String.valueOf(MaidSmartConfig.COMBAT_AUTO_SWITCH_MOD_WEIGHT.get()),
                s -> setDouble(MaidSmartConfig.COMBAT_AUTO_SWITCH_MOD_WEIGHT, s), "模组武器权重（默认 2.0）：万法皆通/史诗战斗/真正的力量/枪械等模组攻击任务的加权随机权重——模组武器普遍更强故默认优先（2:1 约被选 67%）"));
        this.rows.add(new NumRow("原版武器权重", String.valueOf(MaidSmartConfig.COMBAT_AUTO_SWITCH_VANILLA_WEIGHT.get()),
                s -> setDouble(MaidSmartConfig.COMBAT_AUTO_SWITCH_VANILLA_WEIGHT, s), "原版武器权重（默认 1.0）：原版五件套（近战/弓/弩/三叉戟/弹幕）的加权随机权重——设 0.5=更少选原版，设 2=与模组平起平坐"));
        // v1.1.0 实测五十八：近战/远程偏好权重（两者皆可用时选池倾向 + 战中换战术开关量）
        this.rows.add(new NumRow("近战偏好权重", String.valueOf(MaidSmartConfig.COMBAT_PREF_MELEE_WEIGHT.get()),
                s -> setInt(MaidSmartConfig.COMBAT_PREF_MELEE_WEIGHT, s), "近战偏好权重（默认 3）：近战远程武器都有、敌人在近身距离（≤5 格）时按 近战:远程 权重随机选——3 配远程 1 ≈ 75% 选近战；设 0 = 永不主动选近战（战中也不会切近战，近身只靠反击击退）"));
        this.rows.add(new NumRow("远程偏好权重", String.valueOf(MaidSmartConfig.COMBAT_PREF_RANGED_WEIGHT.get()),
                s -> setInt(MaidSmartConfig.COMBAT_PREF_RANGED_WEIGHT, s), "远程偏好权重（默认 1）：近战远程武器都有、敌人在近身距离（≤5 格）时按 近战:远程 权重随机选——调大则近身也更倾向保持远程输出；设 0 = 永不主动选远程（战中也不会切远程，够不着就追）"));
        // v1.1.0 实测六十一（借鉴 TLM-Sincerely 防抖三件套）：战中换战术稳定机制
        this.rows.add(new NumRow("换战术最短持有（tick）", String.valueOf(MaidSmartConfig.COMBAT_TACTIC_HOLD_TICKS.get()),
                s -> setInt(MaidSmartConfig.COMBAT_TACTIC_HOLD_TICKS, s), "战中换战术最短持有（tick，默认 40=2 秒）：近远程切换后至少持有这么久才允许再次评估换战术——防敌人在门槛距离徘徊时频繁换任务重建 brain；0 = 不限制"));
        this.rows.add(new NumRow("反向切换窗口（tick）", String.valueOf(MaidSmartConfig.COMBAT_REVERSE_WINDOW_TICKS.get()),
                s -> setInt(MaidSmartConfig.COMBAT_REVERSE_WINDOW_TICKS, s), "反向切换窗口（tick，默认 100=5 秒）：换战术后在此窗口内又想换回上一个战术，视为来回横跳"));
        this.rows.add(new NumRow("反向切换冷却（tick）", String.valueOf(MaidSmartConfig.COMBAT_REVERSE_COOLDOWN_TICKS.get()),
                s -> setInt(MaidSmartConfig.COMBAT_REVERSE_COOLDOWN_TICKS, s), "反向切换冷却（tick，默认 200=10 秒）：横跳被判定后进入冷却，期间不再换战术（保持当前战术硬打）——0 = 关闭反向抑制"));
        // v1.1.0 实测六十七：空手不参战
        this.rows.add(new BoolRow("空手不参战", MaidSmartConfig.COMBAT_UNARMED_SKIP.get(),
                v -> MaidSmartConfig.COMBAT_UNARMED_SKIP.set(v), "空手不参战（默认开）：背包和主手都没有任何攻击任务认可的武器（剑/弓/枪械/模组武器等）的女仆，不触发自主战斗、维持原任务继续干活；关闭恢复旧行为（没有武器也空手近战兜底）"));
        // v1.1.0 实测二十：枪械优先开关已删除（原版武器降半权、模组攻击任务等权
        // 随机的新选法不需要开关——附属生态的攻击任务与枪械强度等价）
        this.rows.add(new BoolRow("战斗结束自动还原", MaidSmartConfig.COMBAT_AUTO_SWITCH_RESTORE.get(),
                v -> MaidSmartConfig.COMBAT_AUTO_SWITCH_RESTORE.set(v), "战斗结束自动还原：威胁消失一段时间后切回战斗前的原任务；关闭则保持战斗模式直到玩家手动切换"));
        this.rows.add(new NumRow("还原延迟（tick）", String.valueOf(MaidSmartConfig.COMBAT_AUTO_SWITCH_RESTORE_DELAY.get()),
                s -> setInt(MaidSmartConfig.COMBAT_AUTO_SWITCH_RESTORE_DELAY, s), "还原延迟（tick，200=10 秒）：威胁消失后持续安全这么久才切回原任务——期间你手动给她换的任务不会被还原翻回去"));
        this.rows.add(new NumRow("还原威胁半径（格）", String.valueOf(MaidSmartConfig.COMBAT_AUTO_SWITCH_RESTORE_THREAT_DIST.get()),
                s -> setInt(MaidSmartConfig.COMBAT_AUTO_SWITCH_RESTORE_THREAT_DIST, s), "还原威胁半径（格，默认 8）：女仆周围此范围内无敌对生物才算威胁消失、开始还原计时——比响应半径小（远处怪不该让她一直卡在战斗里回不了岗）；战斗中玩家手动给她换的任务不会被还原翻回去"));
        // v1.5.295：逃生/搭高/逃跑数值参数（旧版混在自保行为开关与贴身辅助之间，
        // 把开关区挤到第 3-4 页——集中到页尾，调参才需要翻到这里）
        this.rows.add(new SectionRow("自保参数", true));
        this.rows.add(new NumRow("触发血量（0-1）", String.valueOf(MaidSmartConfig.COMBAT_ENTER_RATIO.get()),
                s -> setDouble(MaidSmartConfig.COMBAT_ENTER_RATIO, s), "触发血量（0-1，0.3=30%）：血量低于此值进入保命（逃跑/搭高/喝药）；30%~70% 期间边打边喝药，70% 以上恢复正常"));
        this.rows.add(new NumRow("解除血量（0-1）", String.valueOf(MaidSmartConfig.COMBAT_EXIT_RATIO.get()),
                s -> setDouble(MaidSmartConfig.COMBAT_EXIT_RATIO, s), "解除血量（0-1）：环境安全且回到此血量以上才结束保命（常驻检查器下无进出抖动）"));
        this.rows.add(new NumRow("安全回归血量（0-1）", String.valueOf(MaidSmartConfig.COMBAT_SAFE_RETURN_RATIO.get()),
                s -> setDouble(MaidSmartConfig.COMBAT_SAFE_RETURN_RATIO, s), "安全回归血量（0-1，威胁消失后回到此血量解除自保）"));
        this.rows.add(new NumRow("威胁距离", String.valueOf(MaidSmartConfig.COMBAT_THREAT_DISTANCE.get()),
                s -> setInt(MaidSmartConfig.COMBAT_THREAT_DISTANCE, s), "威胁距离（格）：怪物进入此距离才算威胁——调大女仆更早警觉、更容易进自保"));
        this.rows.add(new NumRow("威胁扫描间隔（tick）", String.valueOf(MaidSmartConfig.COMBAT_THREAT_SCAN.get()),
                s -> setInt(MaidSmartConfig.COMBAT_THREAT_SCAN, s), "威胁扫描间隔（tick，20=1 秒）：寻找威胁的轮询周期，调小反应快、略耗性能"));
        this.rows.add(new NumRow("威胁消失退出（tick）", String.valueOf(MaidSmartConfig.COMBAT_THREAT_GONE_EXIT.get()),
                s -> setInt(MaidSmartConfig.COMBAT_THREAT_GONE_EXIT, s), "威胁消失退出（tick，400=20 秒）：威胁消失后女仆在安全位置再观察 N 秒，确认安全才结束自保/传回主人身边——调大更谨慎，调小更快回家"));
        this.rows.add(new NumRow("贴身距离", String.valueOf(MaidSmartConfig.COMBAT_CLOSE_DISTANCE.get()),
                s -> setDouble(MaidSmartConfig.COMBAT_CLOSE_DISTANCE, s), "贴身距离（格）：怪物低于此距离判定被近身（濒死时触发击退+搭高）"));
        // v1.5.186：原"近战搭高上限/远程搭高上限 + 近战/远程搭方块冷却"合并为唯一
        // 控制项"至多向上搭多少个方块"（默认 30，不再按敌人近战/远程划分）
        this.rows.add(new NumRow("至多向上搭多少个方块", String.valueOf(MaidSmartConfig.COMBAT_PILLAR_MAX.get()),
                s -> setInt(MaidSmartConfig.COMBAT_PILLAR_MAX, s), "至多向上搭多少个方块（格，默认 30）：濒死被怪物围攻时垫高躲开的上限（够不着就行）"));
        // v1.5.203：搭高安全高度（与落地水触发高度配对）
        this.rows.add(new NumRow("搭高安全高度（格）", String.valueOf(MaidSmartConfig.COMBAT_PILLAR_SAFE_HEIGHT.get()),
                s -> setInt(MaidSmartConfig.COMBAT_PILLAR_SAFE_HEIGHT, s), "搭高安全高度（格，默认 5）：搭高惯性/补完垫到的高度——与\"落地水触发高度\"（默认 3.0）配对：垫到 5 格跳下，下落距离到 3.0 时离地还有约 2 格放水窗口，稳定触发落地水（水减速怪物）；旧写死 4 太临界（触发时已贴近地面放水来不及）"));
        this.rows.add(new NumRow("治疗冷却（tick）", String.valueOf(MaidSmartConfig.COMBAT_HEAL_COOLDOWN.get()),
                s -> setInt(MaidSmartConfig.COMBAT_HEAL_COOLDOWN, s), "治疗食物冷却（tick）：吃食物回血的最短间隔，防狂吃"));
        this.rows.add(new NumRow("药水尝试间隔（tick）", String.valueOf(MaidSmartConfig.COMBAT_POTION_COOLDOWN.get()),
                s -> setInt(MaidSmartConfig.COMBAT_POTION_COOLDOWN, s), "药水尝试间隔（tick）：尝试喝增益药水（再生/迅捷）的最短间隔"));
        this.rows.add(new NumRow("卡住判定窗口（tick）", String.valueOf(MaidSmartConfig.COMBAT_STUCK_WINDOW.get()),
                s -> setInt(MaidSmartConfig.COMBAT_STUCK_WINDOW, s), "卡住判定窗口（tick）：逃跑中 N 秒没位移判定为卡住（然后垫台阶翻越）"));
        this.rows.add(new NumRow("卡住位移阈值", String.valueOf(MaidSmartConfig.COMBAT_STUCK_THRESHOLD.get()),
                s -> setDouble(MaidSmartConfig.COMBAT_STUCK_THRESHOLD, s), "卡住位移阈值（格）：窗口内位移小于此值算卡住"));
        this.rows.add(new NumRow("逃跑速度", String.valueOf(MaidSmartConfig.COMBAT_FLEE_SPEED.get()),
                s -> setDouble(MaidSmartConfig.COMBAT_FLEE_SPEED, s), "逃跑速度倍率：逃跑时的移动加成（1.0=正常）——调大跑更快但更容易撞墙/钻死角"));
        this.rows.add(new NumRow("警示粒子间隔（tick）", String.valueOf(MaidSmartConfig.COMBAT_ALERT_COOLDOWN.get()),
                s -> setInt(MaidSmartConfig.COMBAT_ALERT_COOLDOWN, s), "警示粒子间隔（tick）：女仆头顶危险警示粒子的刷新间隔"));
        this.rows.add(new NumRow("策略播报间隔（tick）", String.valueOf(MaidSmartConfig.COMBAT_ANNOUNCE_COOLDOWN.get()),
                s -> setInt(MaidSmartConfig.COMBAT_ANNOUNCE_COOLDOWN, s), "策略播报间隔（tick，防刷屏）"));
        this.rows.add(new NumRow("传送回家冷却（tick）", String.valueOf(MaidSmartConfig.COMBAT_TELEPORT_COOLDOWN.get()),
                s -> setInt(MaidSmartConfig.COMBAT_TELEPORT_COOLDOWN, s), "传送回家冷却（tick，20=1 秒）：自保结束后传回主人身边的最短间隔，防反复传送"));
        this.rows.add(new NumRow("传送安全判定半径", String.valueOf(MaidSmartConfig.COMBAT_TELEPORT_SAFE_RADIUS.get()),
                s -> setDouble(MaidSmartConfig.COMBAT_TELEPORT_SAFE_RADIUS, s), "传送安全判定半径（格）：主人身边此半径内【无可见怪物】才传送回主人（v1.5.150 起只判主人身边；默认 5 格防远程怪，调小更容易传回家）"));
        this.rows.add(new NumRow("珍珠逃生冷却（tick）", String.valueOf(MaidSmartConfig.COMBAT_PEARL_COOLDOWN.get()),
                s -> setInt(MaidSmartConfig.COMBAT_PEARL_COOLDOWN, s), "珍珠逃生冷却（tick，20=1 秒）：扔末影珍珠脱身的最短间隔，防连扔"));
        this.rows.add(new NumRow("珍珠逃生触发血量（0-1）", String.valueOf(MaidSmartConfig.COMBAT_PEARL_RATIO.get()),
                s -> setDouble(MaidSmartConfig.COMBAT_PEARL_RATIO, s), "末影珍珠逃生触发血量（0-1，低于此值且威胁贴身才扔）"));
        this.rows.add(new NumRow("珍珠逃生威胁距离", String.valueOf(MaidSmartConfig.COMBAT_PEARL_DIST.get()),
                s -> setDouble(MaidSmartConfig.COMBAT_PEARL_DIST, s), "末影珍珠逃生威胁距离（威胁小于此格数才扔珍珠）"));
    }

    private void miscRows() {
        this.rows.add(new SectionRow("任务范围", false));
        this.rows.add(new NumRow("烧制搜索范围", String.valueOf(MaidSmartConfig.MISC_COOK_RADIUS.get()),
                s -> setInt(MaidSmartConfig.MISC_COOK_RADIUS, s), "烧制搜索范围（格）：烧制任务在这个半径内找熔炉/高炉/烟熏炉（v1.1.0 实测一百六十一：烹饪任务改名烧制——兼容矿石/高炉/烟熏炉）"));
        this.rows.add(new NumRow("酿造搜索范围", String.valueOf(MaidSmartConfig.MISC_BREW_RADIUS.get()),
                s -> setInt(MaidSmartConfig.MISC_BREW_RADIUS, s), "酿造搜索范围（格）：酿造任务在这个半径内找酿造台"));
        this.rows.add(new NumRow("处理间隔（tick）", String.valueOf(MaidSmartConfig.MISC_PROCESS_COOLDOWN.get()),
                s -> setInt(MaidSmartConfig.MISC_PROCESS_COOLDOWN, s), "处理间隔（tick，20=1 秒）：烹饪/酿造每处理一批的间隔"));
        // v1.1.0 实测一百五十七：熔炉兼容矿物类可烧制物
        this.rows.add(new BoolRow("熔炉烧矿物", MaidSmartConfig.MISC_COOK_SMELT_ORES.get(),
                v -> MaidSmartConfig.MISC_COOK_SMELT_ORES.set(v), "烧制任务里背包没有食材时，兼容带矿物/原料标签（forge:ores、minecraft:*_ores、forge:raw_materials 等）且当前世界有熔炉配方的物品——铁矿石/粗铁/金矿石/远古残骸等照常放进熔炉烧；关闭 = 只烧食材白名单"));
        // v1.1.0 实测一百八十二：通用可烧制物回退——治"只投燃料不投烧制物"
        this.rows.add(new BoolRow("烧任何可烧制物", MaidSmartConfig.MISC_COOK_SMELT_ANY.get(),
                v -> MaidSmartConfig.MISC_COOK_SMELT_ANY.set(v), "背包没有食材/矿物标签物品时，回退喂任何【有熔炉配方 且 非装备类】的物品——沙子→玻璃、圆石→石头、原木→木炭、模组食材/模组粗矿等（装备类永不熔，铁金钻石工具盔甲有烧成粒配方会被排除）；关闭 = 只按「熔炉烧矿物」+食材白名单喂"));
        // v1.1.0 实测一百五十八：兼容高炉/烟熏炉
        this.rows.add(new BoolRow("兼容高炉/烟熏炉", MaidSmartConfig.MISC_COOK_SMOKER_BLAST.get(),
                v -> MaidSmartConfig.MISC_COOK_SMOKER_BLAST.set(v), "烧制任务不只操作熔炉：高炉按高炉配方喂料（矿石/粗金属）、烟熏炉按烟熏配方喂料（生食），成品/燃料照常；高炉喂料受「熔炉烧矿物」开关约束（高炉只烧矿物）；关闭 = 只操作熔炉"));
        this.rows.add(new SectionRow("通用", true));
        // v1.1.0 实测一百八十三：空闲散步——治 TLM 原生散步又少又慢又近
        this.rows.add(new BoolRow("空闲散步", MaidSmartConfig.MISC_STROLL_ENABLED.get(),
                v -> MaidSmartConfig.MISC_STROLL_ENABLED.set(v), "女仆空闲时按间隔主动散步（默认开）——替代 TLM 原生散步（原生只有 0.3 倍速、5 格半径、平均一两小时才走一次）；战斗/自保/站桩工作/有移动目标时不打扰"));
        this.rows.add(new NumRow("散步间隔（tick）", String.valueOf(MaidSmartConfig.MISC_STROLL_INTERVAL.get()),
                s -> setInt(MaidSmartConfig.MISC_STROLL_INTERVAL, s), "空闲女仆每隔这么久散步一次（默认 200=10 秒，TLM 原生平均一两小时才走一次）；找得到落点就走，找不到顺延"));
        this.rows.add(new NumRow("散步半径（格）", String.valueOf(MaidSmartConfig.MISC_STROLL_RADIUS.get()),
                s -> setInt(MaidSmartConfig.MISC_STROLL_RADIUS, s), "每次散步在周围这个半径内随机选点（默认 16；排班/在家模式下不会超出「排班活动半径」）"));
        this.rows.add(new NumRow("散步速度倍率", String.valueOf(MaidSmartConfig.MISC_STROLL_SPEED.get()),
                s -> setDouble(MaidSmartConfig.MISC_STROLL_SPEED, s), "散步移动速度倍率（默认 1.0 = 正常走路速度；TLM 原生散步只有 0.3 倍速）"));
        this.rows.add(new NumRow("气泡限频（毫秒）", String.valueOf(MaidSmartConfig.MISC_BUBBLE_LIMIT_MS.get()),
                s -> setInt(MaidSmartConfig.MISC_BUBBLE_LIMIT_MS, s), "气泡限频（毫秒）：对话气泡的最短显示间隔，防连续说话刷屏"));
        this.rows.add(new BoolRow("挖矿中禁止拾取", MaidSmartConfig.MISC_PICKUP_PRIORITY.get(),
                v -> MaidSmartConfig.MISC_PICKUP_PRIORITY.set(v), "挖矿中禁止拾取（捡掉落物最低优先级）"));
        this.rows.add(new NumRow("任务垂直范围", String.valueOf(MaidSmartConfig.MISC_VERTICAL_RANGE.get()),
                s -> setInt(MaidSmartConfig.MISC_VERTICAL_RANGE, s), "任务垂直范围（格）：烹饪/酿造在上/下多少格内搜索容器"));
        // v1.5.129：原生任务呆滞修复 + 干活不被打断
        this.rows.add(new BoolRow("原生任务流畅化", MaidSmartConfig.MISC_NATIVE_TASK_SMOOTH.get(),
                v -> MaidSmartConfig.MISC_NATIVE_TASK_SMOOTH.set(v), "TLM 原生任务（种田/挤奶/钓鱼等）呆滞修复：任务行为不再每 3 秒重启、随机散步不再覆盖任务目标、走路少刹车、检查节流减半"));
        this.rows.add(new BoolRow("干活不被打断", MaidSmartConfig.MISC_WORK_UNINTERRUPTED.get(),
                v -> MaidSmartConfig.MISC_WORK_UNINTERRUPTED.set(v), "干活中跳过：吃饭（刷好感餐）、偷吃（拆浆果丛）、小伤恐慌逃跑（血量<30% 仍会跑）、切班时被拽回工位"));
        // v1.5.130：产出型任务专项增强
        this.rows.add(new BoolRow("产出任务增强", MaidSmartConfig.MISC_PRODUCE_TASK_ENHANCE.get(),
                v -> MaidSmartConfig.MISC_PRODUCE_TASK_ENHANCE.set(v), "农场：一次收割/补种目标周围 3x3 整片作物（来回跑减少到约 1/8）；钓鱼：附近没椅子/船时主动找开阔水域，自带坐垫生成在岸边"));
        // v1.5.142：跨维度跟随
        this.rows.add(new BoolRow("跨维度跟随", MaidSmartConfig.MISC_DIMENSION_FOLLOW.get(),
                v -> MaidSmartConfig.MISC_DIMENSION_FOLLOW.set(v), "主人换维度后，女仆自动传送到主人身边（约 5 秒扫描一轮）；坐着的/骑乘的/主人身边无可站立点时不拉。v1.1.0 实测一百三十一起守家（home）模式也照常跟随跨维度——排班自动 home 的女仆主人过门照样跟过来"));
        // v1.1.0 实测一百三十四：同维度远距拉回（跨区块传送兜底）
        this.rows.add(new BoolRow("同维度远距拉回", MaidSmartConfig.MISC_MAID_SAME_DIM_PULL.get(),
                v -> MaidSmartConfig.MISC_MAID_SAME_DIM_PULL.set(v), "女仆与主人同维度但距离超过阈值时自动传送到主人身边（跨区块传送的兜底——TLM 自带过远传送只对非home非工作的跟随女仆生效且可能静默失败）。守家/坐姿/骑乘/干活中（挖矿/伐木/建造/站桩）不拉，原因会写进 logs/promaid.log（60 秒限频）"));
        // v1.1.0 实测一百五十一：跟随收紧（参考改版 TLM jar——每 tick 重断言跟随目标）
        this.rows.add(new BoolRow("跟随收紧", MaidSmartConfig.MISC_FOLLOW_TIGHTEN.get(),
                v -> MaidSmartConfig.MISC_FOLLOW_TIGHTEN.set(v), "跟随模式的女仆每 tick 重新断言跟随目标——平常跟随在 4 格以内，被其他行为/寻路刹车干扰走远时立即拉回，不再走走停停/乱跑（参考改版 TLM jar 的每 tick 驱动设计；关闭 = 官方 1.5.3 原版行为）"));
        this.rows.add(new NumRow("同维度拉回距离（格）", String.valueOf(MaidSmartConfig.MISC_MAID_SAME_DIM_DIST.get()),
                s -> setInt(MaidSmartConfig.MISC_MAID_SAME_DIM_DIST, s), "女仆与主人同维度且距离超过此值才拉回（默认 48 格）：低于此值靠走路/跟随，不打扰她"));
        // v1.1.0 实测一百八十八：Y 轴拉回门槛（用户："传送机制不检测 Y 轴"）
        this.rows.add(new NumRow("Y 轴拉回门槛（格）", String.valueOf(MaidSmartConfig.MISC_MAID_SAME_DIM_VERTICAL.get()),
                s -> setInt(MaidSmartConfig.MISC_MAID_SAME_DIM_VERTICAL, s), "女仆与主人同维度、距离没超上一条但【垂直高度差】超本值时——主人旁边 16 格内有安全落点就传送过来，没有则不传（默认 16 格；旧版只按 48 格 3D 距离判定，水平贴身、竖直搭高 30 格的女仆永远不触发）"));
        // v1.5.161：农场连锁收获 / 收获物自动收集（v1.5.189：连锁默认开启）
        this.rows.add(new BoolRow("农场连锁收获", MaidSmartConfig.MISC_CHAIN_HARVEST.get(),
                v -> MaidSmartConfig.MISC_CHAIN_HARVEST.set(v), "农场连锁收获：收割时以目标格为中心蔓延连锁收割相连农田里的成熟作物（大农田多轮清完）；默认开启"));
        this.rows.add(new BoolRow("收获物自动收集", MaidSmartConfig.MISC_AUTO_COLLECT.get(),
                v -> MaidSmartConfig.MISC_AUTO_COLLECT.set(v), "收获物自动收集：收割产物（作物/种子等）直接进女仆背包，不落地；默认关闭"));
        // v1.5.163：农场连锁收获上限可自定义
        this.rows.add(new NumRow("连锁收获上限（格）", String.valueOf(MaidSmartConfig.MISC_CHAIN_HARVEST_LIMIT.get()),
                s -> setInt(MaidSmartConfig.MISC_CHAIN_HARVEST_LIMIT, s), "农场连锁收获上限（格）：一次连锁收割的最大格数（4~96，默认 24）"));
        // v1.5.236：农场批量种植（与连锁收获同格式）
        this.rows.add(new BoolRow("批量种植", MaidSmartConfig.MISC_BATCH_PLANT.get(),
                v -> MaidSmartConfig.MISC_BATCH_PLANT.set(v), "农场批量种植：种植时以当前格为中心蔓延，把相连农田里的空耕地一次全种上（种子真实消耗）；默认开启"));
        this.rows.add(new NumRow("批量种植上限（格）", String.valueOf(MaidSmartConfig.MISC_BATCH_PLANT_LIMIT.get()),
                s -> setInt(MaidSmartConfig.MISC_BATCH_PLANT_LIMIT, s), "农场批量种植上限（格）：一次批量种植的最大格数（4~96，默认 24）"));
        // v1.1.0 实测九十二：区块保载/受困救援/危险避让三件套入面板（此前只有 spec 键，
        // 自绘面板没有条目 = 游戏内看不到也改不了）
        this.rows.add(new SectionRow("女仆安全与区块保载", true));
        this.rows.add(new BoolRow("女仆区块持续保载", MaidSmartConfig.MISC_MAID_CHUNK_LOAD.get(),
                v -> MaidSmartConfig.MISC_MAID_CHUNK_LOAD.set(v),
                "所有有主女仆（含在家/坐姿/骑乘）所在区块持续保持实体 ticking（与玩家同级）：跟随落后再远也不冻结失联，随时可传送/召回/救援；关闭后远处女仆所在区块卸载时会冻结失联"));
        this.rows.add(new BoolRow("受困救援", MaidSmartConfig.MISC_MAID_RESCUE.get(),
                v -> MaidSmartConfig.MISC_MAID_RESCUE.set(v),
                "被困下界基岩顶层或掉出虚空的女仆自动传回存活主人身边（跨维度通用；home 女仆也救——基岩顶不是家）"));
        this.rows.add(new BoolRow("寻路危险方块避让", MaidSmartConfig.MISC_DANGER_AVOID.get(),
                v -> MaidSmartConfig.MISC_DANGER_AVOID.set(v),
                "女仆规划路径时绕开危险表中方块（岩浆/火/仙人掌等），宁可停下等过远传送兜底；已身处险境时保留逃出路径"));
        this.rows.add(new BoolRow("险境脱离", MaidSmartConfig.MISC_DANGER_ESCAPE.get(),
                v -> MaidSmartConfig.MISC_DANGER_ESCAPE.set(v),
                "已站在危险方块上的女仆每 0.5 秒巡检并自动挪到最近安全格+应急灭火，不等血量跌破自保线白挨伤害"));
        this.rows.add(new TextRow("危险方块表", String.join(", ", MaidSmartConfig.MISC_DANGER_BLOCKS.get()),
                s -> {
                    java.util.List<String> out = new java.util.ArrayList<>();
                    for (String part : s.split("[,，]")) {
                        String id = part.trim();
                        if (id.isEmpty()) {
                            continue;
                        }
                        if (!id.contains(":")) {
                            return false; // 缺命名空间：拒绝提交，保留旧值
                        }
                        out.add(id);
                    }
                    MaidSmartConfig.MISC_DANGER_BLOCKS.set(out);
                    return true;
                },
                "完整注册名，逗号分隔（如 minecraft:lava, somemod:danger_rock）：命中站立格/脚下即视为危险——寻路绕行、险境脱离、搭块选材排除三系统共用此表"));
        // v1.1.0：排班表系统总开关（玩家可操作原则——排班物品 UI 之外也要有全局开关）
        this.rows.add(new SectionRow("排班表（v1.1.0）", true));
        this.rows.add(new BoolRow("排班表系统", MaidSmartConfig.MISC_SCHEDULE_ENABLED.get(),
                v -> MaidSmartConfig.MISC_SCHEDULE_ENABLED.set(v), "排班表系统（默认开）：按游戏内时间自动应用女仆的排班日程；关闭后排班调度停摆（每只女仆已保存的日程不丢，重新打开即恢复），女仆保持当前任务——单只女仆的排班开关在排班表物品里（快捷设置）"));
        // v1.1.0 实测六十一：战斗还原后排班宽限
        this.rows.add(new NumRow("战斗还原宽限（tick）", String.valueOf(MaidSmartConfig.MISC_SCHEDULE_RESTORE_GRACE.get()),
                s -> setInt(MaidSmartConfig.MISC_SCHEDULE_RESTORE_GRACE, s), "战斗还原后排班宽限（tick，默认 60=3 秒）：主动战斗结束还原原任务后，排班调度等待这么久才接管（期间她继续干战斗前的任务）——防威胁闪烁导致战斗/还原/排班反复拉扯；0 = 还原立即交排班"));
        // v1.1.0 实测一百三十三：排班切换三件套
        this.rows.add(new BoolRow("切换前可用性检测", MaidSmartConfig.MISC_SCHEDULE_AVAILABILITY_CHECK.get(),
                v -> MaidSmartConfig.MISC_SCHEDULE_AVAILABILITY_CHECK.set(v), "排班切任务时的完整可用性检测（默认关）：开启时额外检查目标任务附近有没有活干（矿/树/炉子/酿造台/作物）——没活不切、保持当前任务；关闭（默认）= 只查任务自己的可用开关，任务状态跟着时间段落真实切换（v1.1.0 实测一百七十：旧默认的没活不切把女仆钉死在原地、任务不随段变化）"));
        this.rows.add(new NumRow("反向切换窗口（tick）", String.valueOf(MaidSmartConfig.MISC_SCHEDULE_REVERSE_WINDOW_TICKS.get()),
                s -> setInt(MaidSmartConfig.MISC_SCHEDULE_REVERSE_WINDOW_TICKS, s), "两次任务切换间隔在此窗口内才可能被判为 A→B→A 反向横跳（默认 200=10 秒）；正常时段切换相隔约 2000 tick，不会被误判"));
        this.rows.add(new NumRow("反向切换阈值", String.valueOf(MaidSmartConfig.MISC_SCHEDULE_REVERSE_THRESHOLD.get()),
                s -> setInt(MaidSmartConfig.MISC_SCHEDULE_REVERSE_THRESHOLD, s), "窗口内累计反向次数达到该值即压制本次切换（默认 2）"));
        this.rows.add(new NumRow("反向切换冷却（tick）", String.valueOf(MaidSmartConfig.MISC_SCHEDULE_REVERSE_COOLDOWN_TICKS.get()),
                s -> setInt(MaidSmartConfig.MISC_SCHEDULE_REVERSE_COOLDOWN_TICKS, s), "压制反向切换后保持多久不再反向切（默认 200=10 秒）"));
        // v1.1.0 实测一百七十六（移植 TLM-Sincerely）：排班最短持有期 + 切段后大脑自愈
        this.rows.add(new NumRow("最短持有期（tick）", String.valueOf(MaidSmartConfig.MISC_SCHEDULE_MIN_HOLD_TICKS.get()),
                s -> setInt(MaidSmartConfig.MISC_SCHEDULE_MIN_HOLD_TICKS, s), "任何一次排班切换后此期间内不允许再切换（默认 60=3 秒，借鉴 TLM-Sincerely MINIMUM_TASK_HOLD_TICKS）——防段边界秒切/战斗还原压任务连切；正常时段切换相隔约 2000 tick 不受影响；0 = 关闭"));
        this.rows.add(new BoolRow("切段后大脑自愈", MaidSmartConfig.MISC_SCHEDULE_FORCE_BRAIN_REFRESH.get(),
                v -> MaidSmartConfig.MISC_SCHEDULE_FORCE_BRAIN_REFRESH.set(v), "段任务应用成功后 3 秒，若女仆任务仍是段任务但脑内无任何工作记忆（非坐姿站桩可能被 TLM 脑活动卡住），强制 refreshBrain 一次重建 AI（默认开，借鉴 TLM-Sincerely FORCE_BRAIN_REFRESH_ON_STUCK）；关 = 完全信任 TLM"));
        // v1.1.0 实测一百八十三：排班/home 模式活动半径下限
        this.rows.add(new NumRow("排班活动半径（格）", String.valueOf(MaidSmartConfig.SCHEDULE_ACTIVITY_RANGE.get()),
                s -> setInt(MaidSmartConfig.SCHEDULE_ACTIVITY_RANGE, s), "排班/在家模式下女仆的活动半径下限（默认 32；TLM 原版工作/空闲/睡觉半径只有 8~16 格，稍远就被拉回）——取 max(本值, TLM 设置) 生效，散步/干活都不再被小圈拴住"));
        // v1.1.0 实测九十四：运行日志（logs/promaid.log）——方便日后验查
        this.rows.add(new SectionRow("运行日志（实测九十四）", true));
        this.rows.add(new BoolRow("运行日志记录", MaidSmartConfig.MISC_LOG_ENABLED.get(),
                v -> MaidSmartConfig.MISC_LOG_ENABLED.set(v), "运行日志（默认开）：排班应用、战斗参战与还原、险境脱离、跨维跟随、自保标记自愈等状态变化写入 游戏目录/logs/promaid.log（满 4MB 自动轮换为 promaid.log.old），并镜像到 latest.log——“XX 没生效”类反馈可直接按时间线对账；关闭后完全静默"));
        this.rows.add(new InfoRow("日志文件位置", "\u00a7a<游戏目录>/logs/promaid.log\u00a7r",
                "任意文本编辑器打开；每行格式 [真实时间] [分类] 内容（分类：排班/战斗/险境脱离/跨维/自保）。只记低频状态迁移，巡检空转不落盘"));
        // v1.5.310：爱憎分明相关开关已整体迁到「爱憎分明模组调试」板块页（见 loveloathRows）
    }

    // ---------- 爱憎分明（Love Loathe）联动调试页（v1.5.310） ----------

    /** 是否安装了爱憎分明（modId=callresponse）——软联动：未装则首页不显示该按钮 */
    private static boolean loveloatheLoaded() {
        try {
            return net.minecraftforge.fml.ModList.get().isLoaded("callresponse");
        } catch (Exception e) {
            return false;
        }
    }

    /** 爱憎分明版本号（未装/异常返回 "?"） */
    private static String loveloatheVersion() {
        try {
            var mods = net.minecraftforge.fml.ModList.get().getModFileById("callresponse").getMods();
            return mods.isEmpty() ? "?" : mods.get(0).getVersion().toString();
        } catch (Exception e) {
            return "?";
        }
    }

    /** 反射探测：依次尝试候选类名，返回第一个可加载的（✓ 前缀）；全失败返回"未找到" */
    private static String probeClass(String... candidates) {
        for (String c : candidates) {
            try {
                Class.forName(c);
                return "\u00a7a\u2713\u00a7r " + c;
            } catch (Throwable ignored) {
            }
        }
        return "\u00a7c未找到\u00a7r";
    }

    /** 爱憎分明联动调试页：状态探测（只读）+ 联动开关 */
    private void loveloathRows() {
        boolean ll = loveloatheLoaded();
        this.rows.add(new SectionRow("联动状态（调试）", false));
        this.rows.add(new InfoRow("爱憎分明模组", ll ? "\u00a7a已安装 v" + loveloatheVersion() + "\u00a7r（modId: callresponse）"
                        : "\u00a7c未安装\u00a7r（本页仅在安装爱憎分明后显示）",
                "爱憎分明（Love Loathe）是车万女仆的附属模组，提供女仆饥饿/撑死与情绪（信任/恐惧）系统；本模组与其为软联动，不装也不影响使用"));
        this.rows.add(new InfoRow("饥饿数据接口 HungerData", probeClass(
                "com.github.JumDa5he.callresponse.compat.hunger.HungerData",
                "com.github.tartaricacid.callresponse.compat.hunger.HungerData"),
                "极端饥饿判定（饥饿值 ≤9）的反射目标；2.0.2 起新包名，旧包名兼容（v1.5.284）"));
        this.rows.add(new InfoRow("情绪数据接口 EmotionData", probeClass(
                "com.github.JumDa5he.callresponse.compat.emotion.EmotionData",
                "com.github.tartaricacid.callresponse.compat.emotion.EmotionData"),
                "情绪投影（信任/恐惧）反射目标，记忆系统感知用（v1.5.284）"));
        this.rows.add(new InfoRow("饥饿门控注入", "\u00a7aLoveLoatheHungerGateMixin\u00a7r（@Pseudo 软注入）",
                "下方「禁用爱憎分明饥饿」开关生效时拦截其饥饿伤害/进食/速度惩罚逻辑"));
        this.rows.add(new BtnRow("重新探测", "刷新 →", () -> this.m_7856_(),
                "重新检测模组与反射接口（打开本页时已自动探测；此按钮仅调试用）"));
        this.rows.add(new SectionRow("联动开关", false));
        this.rows.add(new BoolRow("爱憎分明联动总开关", MaidSmartConfig.MISC_LOVELOATHE_MASTER.get(),
                v -> MaidSmartConfig.MISC_LOVELOATHE_MASTER.set(v), "爱憎分明联动总开关（默认开）：关闭后不再反射读取爱憎分明数据（极端饥饿/情绪投影）；「禁用爱憎分明饥饿」开关独立生效"));
        this.rows.add(new BoolRow("禁用爱憎分明饥饿", MaidSmartConfig.MISC_LOVELOATHE_DISABLE_HUNGER.get(),
                v -> MaidSmartConfig.MISC_LOVELOATHE_DISABLE_HUNGER.set(v), "禁用爱憎分明饥饿/撑死（默认开）：饿死伤害/撑死/自动进食（会吃腐肉→越吃越饿）/速度惩罚全禁；关闭本项恢复爱憎分明原版饥饿行为"));
        this.rows.add(new BoolRow("极端饥饿保命联动", MaidSmartConfig.MISC_LOVELOATHE_EXTREME_HUNGER.get(),
                v -> MaidSmartConfig.MISC_LOVELOATHE_EXTREME_HUNGER.set(v), "极端饥饿保命（默认开）：女仆极端饥饿（爱憎分明饥饿值 ≤9）且无其他治疗食物时，吃金苹果/附魔金苹果保命"));
        this.rows.add(new BoolRow("情绪数据联动", MaidSmartConfig.MISC_LOVELOATHE_EMOTION.get(),
                v -> MaidSmartConfig.MISC_LOVELOATHE_EMOTION.set(v), "情绪数据联动（默认开）：记忆系统感知爱憎分明情绪投影（信任/恐惧），影响关系记忆与 AI 上下文注入"));
    }

    // ==================== v1.5.367:heartfelt_connection 软联动(同爱憎分明模式) ====================

    /** heartfelt_connection 是否安装(软联动:未安装本页不显示) */
    private static boolean heartfeltLoaded() {
        return net.minecraftforge.fml.ModList.get().isLoaded("heartfelt_connection");
    }

    /** heartfelt_connection 版本号(反射 ModList) */
    private static String heartfeltVersion() {
        try {
            var mods = net.minecraftforge.fml.ModList.get().getModFileById("heartfelt_connection").getMods();
            return mods.isEmpty() ? "?" : mods.get(0).getVersion().toString();
        } catch (Exception e) {
            return "?";
        }
    }

    /** 反射读 HeartfeltConfig 静态字段(ForgeConfigSpec 值对象);类/字段不存在返回 null */
    private static Object heartfeltField(String field) {
        try {
            Class<?> cls = Class.forName("com.heartfelt.connection.config.HeartfeltConfig");
            return cls.getField(field).get(null);
        } catch (Exception e) {
            return null;
        }
    }

    /** 读 heartfelt 布尔配置;读取失败返回 false */
    private static boolean heartfeltBoolGet(String field) {
        Object o = heartfeltField(field);
        return o instanceof net.minecraftforge.common.ForgeConfigSpec.BooleanValue b && b.get();
    }

    /** 读 heartfelt 数值配置;读取失败返回 "?" */
    private static String heartfeltNumGet(String field) {
        Object o = heartfeltField(field);
        if (o instanceof net.minecraftforge.common.ForgeConfigSpec.ConfigValue<?> c) {
            return String.valueOf(c.get());
        }
        return "?";
    }

    /** 写 heartfelt 布尔配置;失败返回 false(行不更新) */
    private static boolean heartfeltBoolSet(String field, boolean v) {
        Object o = heartfeltField(field);
        if (o instanceof net.minecraftforge.common.ForgeConfigSpec.BooleanValue b) {
            try {
                b.set(v);
                return true;
            } catch (Exception ignored) {
            }
        }
        return false;
    }

    /** 写 heartfelt 数值配置(isDouble:DoubleValue vs IntValue);失败返回 false */
    private static boolean heartfeltNumSet(String field, String s, boolean isDouble) {
        Object o = heartfeltField(field);
        try {
            if (isDouble && o instanceof net.minecraftforge.common.ForgeConfigSpec.DoubleValue d) {
                d.set(Double.parseDouble(s.trim()));
                return true;
            }
            if (!isDouble && o instanceof net.minecraftforge.common.ForgeConfigSpec.IntValue i) {
                i.set(Integer.parseInt(s.trim()));
                return true;
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    /** heartfelt 联动调试页：状态(只读) + 告白/成长/前摇/伤心窗口参数(实时写入该模组配置) */
    private void heartfeltRows() {
        boolean hf = heartfeltLoaded();
        this.rows.add(new SectionRow("联动状态（调试）", false));
        this.rows.add(new InfoRow("heartfelt_connection 模组",
                hf ? "\u00a7a已安装 v" + heartfeltVersion() + "\u00a7r（modId: heartfelt_connection）"
                        : "\u00a7c未安装\u00a7r（本页仅在安装 heartfelt 后显示）",
                "heartfelt_connection（心契×爱憎分明关系补丁：告白/成长/父女/伤心窗口/思慕）与 Promaid 软联动；下方参数实时写入该模组的配置文件，重启不丢"));
        this.rows.add(new SectionRow("告白", false));
        this.rows.add(new BoolRow("玩家告白（方向修正）", heartfeltBoolGet("PLAYER_CONFESSION_ENABLED"),
                v -> heartfeltBoolSet("PLAYER_CONFESSION_ENABLED", v), "玩家主动告白走 heartfelt 告白屏（拦截 maidmarriage 女仆反告白剧本）；关掉回退 maidmarriage 原剧本"));
        this.rows.add(new BoolRow("女仆告白前摇（走向主人）", heartfeltBoolGet("CONFESSION_APPROACH_ENABLED"),
                v -> heartfeltBoolSet("CONFESSION_APPROACH_ENABLED", v), "女仆主动告白前先系统提示并走向玩家，走到身边才拉告白界面"));
        this.rows.add(new NumRow("主动告白尝试间隔（tick）", heartfeltNumGet("CONFESSION_ATTEMPT_INTERVAL"),
                s -> heartfeltNumSet("CONFESSION_ATTEMPT_INTERVAL", s, false), "女仆主动告白尝试的周期（tick）；0 = 不尝试"));
        this.rows.add(new NumRow("告白所需好感", heartfeltNumGet("CONFESSION_REQUIRED_FAVOR"),
                s -> heartfeltNumSet("CONFESSION_REQUIRED_FAVOR", s, false), "好感高于此线才可能主动告白"));
        this.rows.add(new NumRow("告白基础概率", heartfeltNumGet("CONFESSION_BASE_CHANCE"),
                s -> heartfeltNumSet("CONFESSION_BASE_CHANCE", s, true), "每次尝试的基础概率（0-1，随好感线性加成）"));
        this.rows.add(new NumRow("告白失败心情惩罚", heartfeltNumGet("CONFESSION_FAIL_MOOD"),
                s -> heartfeltNumSet("CONFESSION_FAIL_MOOD", s, false), "告白被拒（缓一缓）时的心情惩罚；0 = 不惩罚"));
        this.rows.add(new NumRow("前摇最短等待（tick）", heartfeltNumGet("CONFESSION_APPROACH_MIN_TICKS"),
                s -> heartfeltNumSet("CONFESSION_APPROACH_MIN_TICKS", s, false), "前摇提示后至少等多久才拉告白选项（防秒触发）"));
        this.rows.add(new NumRow("前摇超时（tick）", heartfeltNumGet("CONFESSION_APPROACH_TIMEOUT"),
                s -> heartfeltNumSet("CONFESSION_APPROACH_TIMEOUT", s, false), "女仆走向超时未到则取消本次告白"));
        this.rows.add(new NumRow("前摇走向速度", heartfeltNumGet("CONFESSION_APPROACH_SPEED"),
                s -> heartfeltNumSet("CONFESSION_APPROACH_SPEED", s, true), "告白前摇走向玩家的速度倍率"));
        // v1.5.100:立即触发主动告白(调试/验证用)——跳过概率与冷却,直接对附近
        // 好感最高的资格女仆启动告白前摇;结果由 heartfelt 系统消息反馈
        this.rows.add(new BtnRow("立即触发主动告白", "触发 →", () -> {
            try {
                Class<?> netCls = Class.forName("com.heartfelt.connection.network.HeartfeltNetwork");
                Object channel = netCls.getMethod("channel").invoke(null);
                Class<?> packetCls = Class.forName(
                        "com.heartfelt.connection.network.HeartfeltNetwork$ForceConfessionPacket");
                Object packet = packetCls.getDeclaredConstructor().newInstance();
                channel.getClass().getMethod("sendToServer", Object.class).invoke(channel, packet);
            } catch (Exception ex) {
                // 反射失败(heartfelt 未装/版本不匹配)静默——页签只在安装后显示
            }
        }, "跳过概率与冷却,立即让附近好感最高、符合告白条件的女仆走向你并告白（调试/验证用；结果以系统消息反馈）"));
        this.rows.add(new SectionRow("女儿/成长", false));
        this.rows.add(new BoolRow("成长事件", heartfeltBoolGet("GROWTH_EVENT_ENABLED"),
                v -> heartfeltBoolSet("GROWTH_EVENT_ENABLED", v), "女儿阶段升级事件（消息+站起+旁白）"));
        this.rows.add(new BoolRow("父女互动", heartfeltBoolGet("FATHER_DAUGHTER_ENABLED"),
                v -> heartfeltBoolSet("FATHER_DAUGHTER_ENABLED", v), "父女日常互动（爸爸与女儿的对话）"));
        this.rows.add(new SectionRow("伤心窗口", false));
        this.rows.add(new NumRow("伤心窗口时长（tick）", heartfeltNumGet("HARM_FEELING_TICKS"),
                s -> heartfeltNumSet("HARM_FEELING_TICKS", s, false), "被打伤后赌气坐着的时长；窗口内不播语音包"));
        this.rows.add(new NumRow("伤心心情惩罚", heartfeltNumGet("HARM_MOOD_DROP"),
                s -> heartfeltNumSet("HARM_MOOD_DROP", s, false), "触发伤心窗口时的心情惩罚"));
    }

    /** v1.5.127：逗号分隔的英文 id 列表 → List（去空、去空格） */
    private static List<String> idList(String s) {
        List<String> out = new ArrayList<>();
        if (s == null) {
            return out;
        }
        for (String part : s.split(",")) {
            String t = part.trim();
            if (!t.isEmpty()) {
                out.add(t);
            }
        }
        return out;
    }

    // ---------- 矿表（v1.5.101b：目标矿物 / 障碍物 双名单） ----------

    /** 输入框添加（0 矿物 "id=value"；1 障碍物 "id"——规范化去 namespace 存 path） */
    private void addMinable() {
        if (this.minableInput == null) {
            return;
        }
        String text = this.minableInput.m_94155_().trim();
        if (this.mineTableMode == 0) {
            // v1.0.4：锁定状态下输入框留空点添加 = 用「创造面板默认价值」赋值（快捷路径）
            if (text.isEmpty()) {
                if (this.lockedOreId != null) {
                    this.setOreValue(this.lockedOreId, this.creativeDefaultValue());
                    this.lockedOreId = null;
                    this.m_7856_(); // 解锁：隐藏赋值输入框
                }
                return;
            }
            if (!text.contains("=")) {
                // v1.0.4：纯数字 = 给锁定的方块图标赋值优先级——在表里则更新，不在则
                // 加入；赋值成功后解锁（黄框/红字消失）。没锁定则提示先点图标。
                if (text.chars().allMatch(Character::isDigit) && !text.isEmpty()) {
                    int v;
                    try {
                        v = Integer.parseInt(text);
                    } catch (NumberFormatException ignored) {
                        return;
                    }
                    if (this.lockedOreId == null) {
                        net.minecraft.client.player.LocalPlayer lp = net.minecraft.client.Minecraft.m_91087_().f_91074_;
                        if (lp != null) {
                            lp.m_213846_(
                                    net.minecraft.network.chat.Component.m_237113_(
                                            "\u00a7e【Promaid】先点击一个方块图标锁定它（黄框固定），再输入数值点添加"));
                        }
                        return;
                    }
                    this.setOreValue(this.lockedOreId, v);
                    this.lockedOreId = null; // 赋值完成，解锁
                    this.minableInput.m_94144_("");
                    this.m_7856_(); // 解锁：隐藏赋值输入框
                    return;
                }
                return; // 非法输入（非数字、非 方块id=数值）
            }
            String[] parts = text.split("=", 2);
            String idPart = parts[0].trim();
            int v;
            try {
                v = Integer.parseInt(parts[1].trim());
            } catch (NumberFormatException ignored) {
                return; // 价值必须数字
            }
            List<String> cur = this.valueListGet();
            // v1.0.4：同 id 已存在 → 替换价值（改优先级）；否则新增
            int existing = -1;
            for (int i = 0; i < cur.size(); i++) {
                String e = cur.get(i);
                int eq = e.indexOf('=');
                if (eq > 0 && e.substring(0, eq).trim().equals(idPart)) {
                    existing = i;
                    break;
                }
            }
            String entry = idPart + "=" + v;
            if (existing >= 0) {
                cur.set(existing, entry);
            } else if (!cur.contains(entry)) {
                cur.add(entry);
            }
            this.valueListSet(cur);
            this.valueListReload();
        } else {
            String path = normPath(text);
            if (path.isEmpty() || "bedrock".equals(path) || "barrier".equals(path)) {
                return; // v1.5.102d：基岩/屏障不允许加入
            }
            // v1.0.4：校验方块真实存在（防随手敲的数字/乱码进表）
            net.minecraft.world.level.block.Block blk = net.minecraftforge.registries.ForgeRegistries.BLOCKS
                    .getValue(net.minecraft.resources.ResourceLocation.parse(path));
            if (blk == null || blk == net.minecraft.world.level.block.Blocks.f_50016_) {
                return;
            }
            List<String> cur = new ArrayList<>(MaidSmartConfig.MINE_BREAKABLES.get());
            if (!cur.contains(path)) {
                cur.add(path);
                MaidSmartConfig.MINE_BREAKABLES.set(cur);
            }
        }
        this.minableInput.m_94144_("");
        if (this.minableList != null) {
            this.minableList.rebuild();
        }
    }

    /** 方块 id → path（去掉 namespace；"minecraft:oak_log" → "oak_log"） */
    private static String normPath(String id) {
        int idx = id.indexOf(':');
        return idx >= 0 ? id.substring(idx + 1) : id;
    }

    /** 列表删除（按当前名单） */
    private void removeMinable(String entry) {
        if (this.mineTableMode == 0) {
            List<String> cur = this.valueListGet();
            cur.remove(entry);
            this.valueListSet(cur);
            this.valueListReload();
        } else {
            List<String> cur = new ArrayList<>(MaidSmartConfig.MINE_BREAKABLES.get());
            cur.remove(entry);
            MaidSmartConfig.MINE_BREAKABLES.set(cur);
        }
        if (this.minableList != null) {
            this.minableList.rebuild();
        }
    }

    /**
     * v1.0.4：条目价值输入框实时写配置——同 id 替换 value（改优先级）后重建矿表。
     * 非法/空串忽略（不写配置）；不 rebuild，避免打断输入焦点（列表文本只显示 id）。
     */
    private void updateOreValue(String id, String text) {
        String t = text.trim();
        if (t.isEmpty()) {
            return;
        }
        int v;
        try {
            v = Integer.parseInt(t);
        } catch (NumberFormatException ignored) {
            return;
        }
        List<String> cur = this.valueListGet();
        boolean found = false;
        for (int i = 0; i < cur.size(); i++) {
            String e = cur.get(i);
            int eq = e.indexOf('=');
            if (eq > 0 && e.substring(0, eq).trim().equals(id)) {
                cur.set(i, id + "=" + v);
                found = true;
                break;
            }
        }
        if (found) {
            this.valueListSet(cur);
            this.valueListReload();
        }
    }

    /** v1.0.4：目标矿物/木材表里该方块的当前优先级（价值），不在表里返回 -1（悬停提示用） */
    private int getOreValue(String id) {
        for (String e : this.valueListGet()) {
            int eq = e.indexOf('=');
            if (eq > 0 && e.substring(0, eq).trim().equals(id)) {
                try {
                    return Integer.parseInt(e.substring(eq + 1).trim());
                } catch (NumberFormatException ignored) {
                    return -1;
                }
            }
        }
        return -1;
    }

    /** v1.0.4：给方块赋值优先级——在表里更新价值，不在表里以该价值加入；随后重建表 */
    private void setOreValue(String id, int v) {
        List<String> cur = this.valueListGet();
        boolean found = false;
        for (int i = 0; i < cur.size(); i++) {
            String e = cur.get(i);
            int eq = e.indexOf('=');
            if (eq > 0 && e.substring(0, eq).trim().equals(id)) {
                cur.set(i, id + "=" + v);
                found = true;
                break;
            }
        }
        if (!found) {
            cur.add(id + "=" + v);
        }
        this.valueListSet(cur);
        this.valueListReload();
    }

    /** v1.0.4：按 id 取消添加（移除目标矿物/木材表条目）——网格右上角小叉 / 列表删除共用 */
    private void removeOre(String id) {
        List<String> cur = this.valueListGet();
        cur.removeIf(e -> {
            int eq = e.indexOf('=');
            return eq > 0 && e.substring(0, eq).trim().equals(id);
        });
        this.valueListSet(cur);
        this.valueListReload();
        if (this.lockedOreId != null && this.lockedOreId.equals(id)) {
            this.lockedOreId = null; // 锁定的方块被移除 → 解锁（隐藏输入框）
            this.m_7856_();
        } else if (this.minableList != null) {
            this.minableList.rebuild();
        }
    }

    /** 该方块 id 是否已在当前名单（矿物/木材按 "id=" 前缀，障碍物按 path） */
    private boolean isInList(String id) {
        if (this.mineTableMode == 0) {
            for (String e : this.valueListGet()) {
                int eq = e.indexOf('=');
                if (eq > 0 && e.substring(0, eq).trim().equals(id)) {
                    return true;
                }
            }
            return false;
        }
        String path = normPath(id);
        // v1.5.102d：自然生成的方块内置已勾选（OPEN_BREAKABLE），面板名单是额外项
        return com.maidsmart.task.MaidMineBehavior.isBuiltInBreakable(path)
                || MaidSmartConfig.MINE_BREAKABLES.get().contains(path);
    }

    /** 点击方块图标 → 加入当前名单；已在名单 → 再点取消（toggle） */
    private void toggleCreative(String id) {
        if (this.mineTableMode == 0) {
            List<String> cur = this.valueListGet();
            String entry = id + "=" + this.creativeDefaultValue();
            if (cur.contains(entry)) {
                cur.remove(entry);
            } else {
                cur.add(entry);
            }
            this.valueListSet(cur);
            this.valueListReload();
        } else {
            String path = normPath(id);
            // v1.5.102d：基岩/屏障等不可破坏方块不允许加入（防止误加后女仆傻挖）
            if ("bedrock".equals(path) || "barrier".equals(path)) {
                return;
            }
            boolean builtin = com.maidsmart.task.MaidMineBehavior.isBuiltinBreakableBlock(path);
            // v1.0.4：内置自然方块 → toggle 排除名单（MINE_DISABLED_BREAKABLES），
            // 取消打勾真正生效——不再被 toggleCreative 的 return 拦截
            if (builtin) {
                List<String> dis = new ArrayList<>(MaidSmartConfig.MINE_DISABLED_BREAKABLES.get());
                if (dis.contains(path)) {
                    dis.remove(path);      // 恢复挖穿
                } else {
                    dis.add(path);         // 取消挖穿
                }
                MaidSmartConfig.MINE_DISABLED_BREAKABLES.set(dis);
            } else {
                List<String> cur = new ArrayList<>(MaidSmartConfig.MINE_BREAKABLES.get());
                if (cur.contains(path)) {
                    cur.remove(path);
                } else {
                    cur.add(path);
                }
                MaidSmartConfig.MINE_BREAKABLES.set(cur);
            }
        }
        if (this.minableList != null) {
            this.minableList.rebuild();
        }
    }

    private class MinableList extends ObjectSelectionList<MinableList.MinableEntry> {
        private final List<String> entries = new ArrayList<>();

        MinableList(net.minecraft.client.gui.Font font, int x, int top, int width, int height) {
            super(Minecraft.m_91087_(), width, height, top, top + height, 22);
            this.m_93507_(x);
            this.m_93488_(false);
            this.m_93496_(false);
            // v1.0.4：行宽 = 列表宽——旧版 f_93390_ 默认 0 → m_5759_() 只有 120，
            // 「删除」文本画在 left+68 处，被条目文本（约 190px）盖住 → 改值/删除
            // 重叠且点不中（渲染位置与点击命中区错位）
            this.f_93390_ = width;
            this.rebuild();
        }

        void rebuild() {
            this.m_93516_();
            this.entries.clear();
            if (PromaidConfigScreen.this.mineTableMode == 0) {
                this.entries.addAll(PromaidConfigScreen.this.valueListGet());
            } else {
                this.entries.addAll(MaidSmartConfig.MINE_BREAKABLES.get());
            }
            for (String e : this.entries) {
                this.m_7085_(new MinableEntry(e));
            }
        }

        @Override
        public int m_5759_() {
            return Math.max(this.f_93390_, 120); // rowWidth（构造时已设为列表宽）
        }

        /**
         * v1.0.4：默认滚动条（亮灰滑块 0x808080/0xC0C0C0）在深色面板上像一条突兀的竖线
         * （用户反馈"保存并返回右侧一直有一条竖线"）——覆盖为低调样式：轨道融入面板，
         * 滑块半透明深灰，滚动功能保留。
         */
        @Override
        protected void m_238964_(GuiGraphics g, int mx, int my, float pt,
                                 int a, int b, int c, int d, int e) {
            super.m_238964_(g, mx, my, pt, a, b, c, d, e);
            int sx = this.f_93389_ + this.m_5759_() - 6;
            g.m_280509_(sx, this.f_93392_, sx + 6, this.f_93393_, 0xFF101010);
            int maxScroll = this.m_93518_();
            if (maxScroll > 0) {
                int area = this.f_93393_ - this.f_93392_;
                int sh = Math.max(32, area * area / maxScroll);
                sh = Math.min(sh, area - 8);
                int sy = (int) (this.m_93517_() * (double) (area - sh)) + this.f_93392_;
                g.m_280509_(sx, sy, sx + 4, sy + sh, 0x40FFFFFF);
            }
        }

        /**
         * v1.0.4：列表条目重构——矿物条目 = id 文本 + 价值输入框（直接改优先级）+ 标准
         * 「删除」按钮；障碍物条目 = path 文本 + 标准「删除」按钮。
         * 标准组件不加入 screen children，由条目手动桥接：渲染时同步位置并调用
         * m_88315_，点击时用 m_5953_ 命中后转发 m_6375_；输入框键盘经 activeBox 转发。
         */
        private class MinableEntry extends ObjectSelectionList.Entry<MinableEntry> {
            private final String entry;    // 原始条目（矿物 "id=value" / 障碍物 path）
            private final String idPart;   // 矿物 id / 障碍物 path（去 value）
            private final net.minecraft.client.gui.components.EditBox valueBox; // 仅矿物模式
            private final net.minecraft.client.gui.components.Button delButton;

            MinableEntry(String entry) {
                this.entry = entry;
                if (PromaidConfigScreen.this.mineTableMode == 0) {
                    int eq = entry.indexOf('=');
                    this.idPart = eq > 0 ? entry.substring(0, eq).trim() : entry.trim();
                    String v = eq > 0 ? entry.substring(eq + 1).trim() : "";
                    this.valueBox = new net.minecraft.client.gui.components.EditBox(
                            PromaidConfigScreen.this.f_96547_, 0, 0, 64, 16,
                            Component.m_237113_(this.idPart));
                    this.valueBox.m_94199_(6);
                    this.valueBox.m_94144_(v);
                    // 仅数字可输入（空串允许清空）；改动实时写配置（非法/空串忽略）
                    this.valueBox.m_94153_(s -> s.isEmpty() || s.chars().allMatch(Character::isDigit));
                    this.valueBox.m_94151_(s -> PromaidConfigScreen.this.updateOreValue(this.idPart, s));
                } else {
                    this.idPart = entry.trim();
                    this.valueBox = null;
                }
                this.delButton = Button.m_253074_(
                                Component.m_237113_("删除"),
                                b -> removeMinable(this.entry))
                        .m_252987_(0, 0, 56, 18).m_253136_();
            }

            @Override
            public void m_6311_(GuiGraphics g, int index, int top, int left, int width, int height,
                                int mouseX, int mouseY, boolean hovered, float partialTick) {
                int x = left + 4;
                int y = top + 3;
                // v1.0.4：中文名 + 灰色 id（未收录中文回退 id）
                String cn = com.maidsmart.build.BlueprintLib.cnName(this.idPart);
                String text = cn.equals(this.idPart)
                        ? this.idPart
                        : cn + " \u00a77(" + this.idPart + ")";
                g.m_280614_(PromaidConfigScreen.this.f_96547_,
                        Component.m_237113_(text), x, y + 1, LABEL_COLOR, false);
                if (this.valueBox != null) {
                    // 价值输入框紧跟文本（按实际文本宽度定位，永不重叠；太长时钳制在删除按钮左侧）
                    int boxX = left + 12 + PromaidConfigScreen.this.f_96547_.m_92895_(text);
                    int delX = left + MinableList.this.m_5759_() - 62;
                    if (boxX + 70 > delX) {
                        boxX = delX - 74;
                    }
                    // v1.0.4：m_252865_ = setX、m_253211_ = setY（1.20.1 SRG 实测——
                    // 旧版写反，输入框/按钮被画到列表区外）
                    this.valueBox.m_252865_(boxX);
                    this.valueBox.m_253211_(top + 2);
                    this.valueBox.m_88315_(g, mouseX, mouseY, partialTick);
                }
                // 标准删除按钮贴右缘
                this.delButton.m_252865_(left + MinableList.this.m_5759_() - 62);
                this.delButton.m_253211_(top + 1);
                this.delButton.m_88315_(g, mouseX, mouseY, partialTick);
            }

            @Override
            public boolean m_6375_(double mouseX, double mouseY, int button) {
                if (button == 0) {
                    if (this.valueBox != null && this.valueBox.m_5953_(mouseX, mouseY)) {
                        PromaidConfigScreen.this.activeBox = this.valueBox;
                        this.valueBox.m_93692_(true);
                        this.valueBox.m_6375_(mouseX, mouseY, 0); // 点击定位光标
                        return true;
                    }
                    if (this.delButton.m_5953_(mouseX, mouseY)) {
                        this.delButton.m_6375_(mouseX, mouseY, 0);
                        return true;
                    }
                }
                return false;
            }

            @Override
            public Component m_142172_() {
                return Component.m_237113_(entry);
            }
        }
    }

    // ---------- 工具 ----------

    /** v1.5.103：返回是否设置成功（非数字/越界 → false → 输入框红字） */
    private static boolean setInt(ForgeConfigSpec.IntValue value, String s) {
        try {
            value.set((int) Double.parseDouble(s.trim()));
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private static boolean setDouble(ForgeConfigSpec.DoubleValue value, String s) {
        try {
            value.set(Double.parseDouble(s.trim()));
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private static boolean setString(ForgeConfigSpec.ConfigValue<String> value, String s) {
        String v = s.trim();
        if (v.equals("x1") || v.equals("x1.5") || v.equals("x3")) {
            value.set(v);
            return true;
        }
        return false;
    }

    // ---------- 渲染（标签按行位置画；无滚轮，全部静态布局） ----------

    /**
     * v1.5.110：居中文本的圆心钳制——保证文本完整落在屏幕内 [8, w-8]。
     * 旧版多处用 drawCenteredString 以 left（≈cx-270）为圆心，窄屏时左半部分
     * 裁出屏幕（"文本偏左超出屏幕"）；本方法把圆心夹到 [8+半宽, w-8-半宽]。
     */
    private int clampCenterX(String text, int preferredX) {
        int textW = this.f_96547_.m_92895_(text);
        int minX = 8 + textW / 2;
        int maxX = this.f_96543_ - 8 - textW / 2;
        return Math.max(minX, Math.min(preferredX, maxX));
    }

    /** 左对齐文本：起点向右钳制，右缘不超屏（长文本顶到右缘，短文本保持原位置） */
    private int clampLeftX(String text, int preferredX) {
        int textW = this.f_96547_.m_92895_(text);
        return Math.min(preferredX, Math.max(8, this.f_96543_ - 8 - textW));
    }

    /**
     * v1.1.0 实测二十二【像素级重叠防御】：对注释做像素切割——按【面板实际像素宽】
     * 逐字符累积测量（m_92895_ = width），超宽即折行；返回折行后的行列表。
     * 旧版 ROW_H 固定 44，注释折成 3+ 行时（长说明 + 窄窗口 + GUI 缩放大）
     * 行高不够 → 注释尾部与下一行标签/输入框像素重叠。新版每行动态行高
     * （见 rowHeight()），本方法是高度计算的统一口径（渲染与布局共用）。
     */
    private List<String> wrapComment(String comment) {
        List<String> lines = new ArrayList<>();
        if (comment == null || comment.isEmpty()) {
            return lines;
        }
        int w = this.f_96543_;
        int cx = w / 2;
        int panelLeft = Math.max(8, cx - 280);
        int panelWidth = Math.min(560, w - 16);
        int maxWidth = panelWidth - 16;
        StringBuilder cur = new StringBuilder("\u00bb ");
        for (int i = 0; i < comment.length(); i++) {
            char ch = comment.charAt(i);
            String test = cur.toString() + ch;
            if (this.f_96547_.m_92895_(test) > maxWidth && cur.length() > 0) {
                lines.add(cur.toString());
                cur.setLength(0);
                cur.append("   "); // 续行缩进对齐首行前缀
            }
            cur.append(ch);
        }
        if (cur.length() > 0) {
            lines.add(cur.toString());
        }
        return lines;
    }

    /**
     * v1.1.0 实测二十二：单行动态行高（像素级防重叠）——
     * 标签+控件 22px + 注释行数 × 10px + 上下留白。
     * SectionRow 无注释取紧凑高度。最低 44（与旧版一致），注释长则自动加高，
     * 分页 perPage 同步按此口径计算，任何行都不会与下一行重叠。
     */
    private int rowHeight(RowDef def) {
        if (def instanceof SectionRow) {
            return 18;
        }
        String comment = null;
        if (def instanceof NumRow nr) {
            comment = nr.comment();
        } else if (def instanceof CycleRow cr) {
            comment = cr.comment();
        } else if (def instanceof BoolRow br) {
            comment = br.comment();
        } else if (def instanceof BtnRow btnr) {
            comment = btnr.comment();
        } else if (def instanceof InfoRow ir) {
            comment = ir.comment();
        } else if (def instanceof TextRow tr) {
            comment = tr.comment();
        }
        int commentLines = wrapComment(comment).size();
        // 22（控件）+ 3（间隔）+ 注释行数×10 + 9（行底留白）；最低 44 保旧版观感
        return Math.max(44, 22 + 3 + commentLines * 10 + 9);
    }

    /**
     * v1.5.110：配置项注释绘制——自动换行（面板宽度内）+ 右缘钳制，保证完整可见。
     * 每行从 clampLeftX 起点画（面板内容左缘，比标签 20 更靠右，对齐控件区）。
     * v1.5.112：注释用【浅蓝 + "» " 前缀】渲染，与白色数值/浅灰标签明显区分——
     * 旧版灰 0x888888 与标签 0xAAAAAA 太接近，用户反馈"注释做了跟没做一样"。
     * 首行带前缀，续行缩进对齐（前缀宽度计入折行/钳制，防右缘越界）。
     * v1.1.0 实测二十二：折行改走 wrapComment（像素切割统一口径——布局侧
     * rowHeight 用同一份行数计算行高，渲染与布局永不脱节）。
     */
    private void drawComment(GuiGraphics g, String comment, int y) {
        if (comment == null || comment.isEmpty()) {
            return;
        }
        int cx = this.f_96543_ / 2;
        int panelLeft = Math.max(8, cx - 280);
        int x = panelLeft + 12;
        List<String> lines = this.wrapComment(comment);
        for (int i = 0; i < lines.size(); i++) {
            g.m_280614_(this.f_96547_, Component.m_237113_(lines.get(i)),
                    this.clampLeftX(lines.get(i), x), y + i * 10, 0xFF7FB2E5, false);
        }
    }

    @Override
    public void m_88315_(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        this.m_280039_(g); // renderBackground
        int w = this.f_96543_;
        int h = this.f_96544_;
        int cx = w / 2;
        g.m_280509_(Math.max(8, cx - 290), 8, Math.min(w - 8, cx + 290),
                h - 8, PANEL_BG);
        // v1.5.102d：矿表子页顶部已被当前名单标题占用（目标矿物/障碍物/珍稀矿物），
        // 主标题"Promaid 模组详细配置"隐去，否则两行文本重叠（v1.5.254：替代品子页同）
        if (!this.mineTable && !this.woodTable && !this.altTable) {
            g.m_280653_(this.f_96547_, Component.m_237113_("Promaid 模组详细配置"), cx, 10, 0xFFFFD700);
        }
        if (this.inHome) {
            g.m_280653_(this.f_96547_, Component.m_237113_("\u00a77选择要调整的板块"),
                    cx, 36, 0x888888);
        } else if (this.mineTable || this.woodTable) {
            // 双名单（目标矿物/木材 + 障碍物），标题随当前名单
            String title = this.mineTableMode == 0
                    ? (this.woodTable
                    ? "\u00a7e木材——点击方块图标加入（价值 " + this.creativeDefaultValue() + "，再点取消）"
                    : "\u00a7e目标矿物——点击方块图标加入（价值 " + this.creativeDefaultValue() + "，再点取消）")
                    : "\u00a7e障碍物——点击方块图标设为可挖穿（再点取消）";
            g.m_280653_(this.f_96547_, Component.m_237113_(title), cx, 10, 0xFFFFFF);
            // 创造物品网格（自绘；搜索框在 m_7856_ 创建）
            int panelLeft = Math.max(8, cx - 280);
            int panelWidth = Math.min(560, w - 16);
            int left = panelLeft + 10;
            int gridTop = GRID_TOP;
            // v1.5.190：渲染侧与按钮侧同步网格行数（矮窗口 2 行）
            int gridRowsNow = h < 215 ? 2 : GRID_ROWS;
            int gridBottom = gridTop + gridRowsNow * GRID_CELL;
            // 网格背景
            g.m_280509_(panelLeft + 8, gridTop - 4, panelLeft + panelWidth - 8, gridBottom, 0x80101010);
            int perPage = GRID_COLS * this.gridRows;
            int start = this.creativePage * perPage;
            int end = Math.min(this.creativeItems.size(), start + perPage);
            int hoverIdx = -1;
            for (int i = start; i < end; i++) {
                int col = (i - start) % GRID_COLS;
                int row = (i - start) / GRID_COLS;
                int x = left + col * GRID_CELL;
                int y = gridTop + row * GRID_CELL;
                net.minecraft.world.item.ItemStack stack = this.creativeItems.get(i);
                net.minecraft.resources.ResourceLocation key =
                        net.minecraftforge.registries.ForgeRegistries.ITEMS.getKey(stack.m_41720_());
                String id = key == null ? "" : key.toString();
                if (this.isInList(id)) {
                    // 已加入当前名单 → 彩色框 + 角标 ✓（矿物绿 / 障碍物青）
                    int boxColor = this.mineTableMode == 0 ? 0x8022CC22 : 0x8022CCDD;
                    g.m_280509_(x - 1, y - 1, x + 17, y + 17, boxColor);
                    g.m_280653_(this.f_96547_, Component.m_237113_("\u2714"),
                            x + 12, y + 12, 0xFFFFFF);
                    // v1.0.4：右上角小叉——点击取消添加（仅矿物模式；锁定后点击图标改值）
                    if (this.mineTableMode == 0) {
                        g.m_280614_(this.f_96547_, Component.m_237113_("\u00a7c×"),
                                x + 12, y - 2, 0xFFFF5555, true);
                    }
                }
                g.m_280480_(stack, x, y); // 物品图标
                // v1.0.4：锁定黄框（实色）——点击后固定在该方块上，鼠标移开也不消失，等赋值
                if (this.mineTableMode == 0 && this.lockedOreId != null
                        && this.lockedOreId.equals(id)) {
                    g.m_280509_(x - 2, y - 2, x + 18, y + 18, 0xFFFFD700);
                }
                if (mouseX >= x && mouseX < x + GRID_CELL && mouseY >= y && mouseY < y + GRID_CELL) {
                    hoverIdx = i;
                    // v1.0.4：悬停黄框（淡色）——提示当前指针所在；点击即锁定
                    g.m_280509_(x - 2, y - 2, x + 18, y + 18, 0x80FFD700);
                }
            }
            // 悬停物品名 / 网格页码——画在网格右侧空白（网格只占左侧 8 格，右缘外
            // 约 380px 空区）；v1.0.4 修复：旧版画在 gridBottom-12，正落在底部行
            // 图标（y=106~126）上，文字与图标重叠
            int infoX = left + GRID_COLS * GRID_CELL + 12;
            int infoY = gridTop + 2;
            int pages = this.creativePages();
            if (hoverIdx >= 0 && hoverIdx < this.creativeItems.size()) {
                net.minecraft.world.item.ItemStack stack = this.creativeItems.get(hoverIdx);
                net.minecraft.resources.ResourceLocation key =
                        net.minecraftforge.registries.ForgeRegistries.ITEMS.getKey(stack.m_41720_());
                String hover = key == null ? "?" : key.toString();
                // v1.0.4：悬停三行——中文名 / 英文 id / 优先级（绿色，目标矿物模式）。
                // 优先级直接从当前矿表读，玩家不必去下面列表的输入框里翻
                String hc = com.maidsmart.build.BlueprintLib.cnName(hover);
                g.m_280614_(this.f_96547_,
                        Component.m_237113_("\u00a7f" + (hc.equals(hover) ? hover : hc)),
                        infoX, infoY, 0xFFFFFF, false);
                g.m_280614_(this.f_96547_, Component.m_237113_("\u00a77" + hover),
                        infoX, infoY + 10, 0xAAAAAA, false);
                if (this.mineTableMode == 0) {
                    int ov = getOreValue(hover);
                    if (ov >= 0) {
                        g.m_280614_(this.f_96547_,
                                Component.m_237113_("\u00a7a优先级：" + ov),
                                infoX, infoY + 20, 0x55FF55, false);
                    } else {
                        g.m_280614_(this.f_96547_,
                                Component.m_237113_("\u00a77未添加"),
                                infoX, infoY + 20, 0x888888, false);
                    }
                }
            } else {
                if (pages > 1) {
                    String pg = "第 " + (this.creativePage + 1) + "/" + pages + " 页";
                    g.m_280614_(this.f_96547_, Component.m_237113_(pg),
                            infoX, infoY, 0x888888, false);
                }
            }
            // v1.5.102d：底部按钮（← 返回参数 / 保存并返回）上方一行注释——
            // 两张名单各一句，说明方块图标上的对勾 ✓ 是什么意思
            String chkHint = this.mineTableMode == 0
                    ? (this.woodTable
                    ? "\u00a77✓ = 已加入木材表（价值 " + this.creativeDefaultValue()
                    + "），女仆会把它当木材砍；点一下图标锁定（黄框固定）后输入框出现，输数值点「添加」即赋值/加入（越大越优先）；右上角 × 取消添加"
                    : "\u00a77✓ = 已加入目标矿物表（价值 " + this.creativeDefaultValue()
                    + "），女仆会把它当矿物挖；点一下图标锁定（黄框固定）后输入框出现，输数值点「添加」即赋值/加入（越大越优先）；右上角 × 取消添加")
                    : "\u00a77✓ = 已设为可挖穿（自然方块内置已预勾选），女仆遇到会挖穿开路，再点一次取消";
            // v1.5.110：居中 + 钳制——旧版以 left（cx-270）为圆心居中，窄屏时左半
            // 部分裁出屏幕（"注释太靠左"），改为中心居中且钳制到完整可见
            g.m_280653_(this.f_96547_, Component.m_237113_(chkHint),
                    this.clampCenterX(chkHint, cx), this.f_96544_ - 50, 0x888888);
            // v1.0.4：锁定提示红字（若隐若现闪烁）——显示在优先级输入框下方，
            // 直到输入数值点「添加」赋值后消失
            if (this.mineTableMode == 0 && this.lockedOreId != null) {
                String lockedCn = com.maidsmart.build.BlueprintLib.cnName(this.lockedOreId);
                // v1.0.4：提醒玩家——直接点添加/回车 = 用默认价值（留空快捷赋值）
                String lockTxt = "已锁定（" + lockedCn + "），请为其赋予一个值"
                        + "（直接点添加/回车 = " + this.creativeDefaultValue() + "）";
                boolean blink = (System.currentTimeMillis() / 400) % 2 == 0;
                int lockColor = blink ? 0xFFFF5555 : 0x40FF5555;
                g.m_280614_(this.f_96547_, Component.m_237113_(lockTxt),
                        left, gridBottom + 46, lockColor, false);
            }
        } else if (this.altTable) {
            // v1.5.254：替代品名单子页（建造板块）——交互与矿表同款
            String[] modeNames = {"半格高（台阶类）", "一格高（整方块）", "竖两格（门/高植物等）",
                    "横两格（床）", "无碰撞（花/火把/地毯等）"};
            String title = "\u00a7e替代品——" + modeNames[Math.min(this.altTableMode, 4)]
                    + "——点击方块图标加入（再点取消）";
            g.m_280653_(this.f_96547_, Component.m_237113_(title), cx, 10, 0xFFFFFF);
            int panelLeft = Math.max(8, cx - 280);
            int panelWidth = Math.min(560, w - 16);
            int left = panelLeft + 10;
            int gridTop = GRID_TOP;
            int gridRowsNow = h < 215 ? 2 : GRID_ROWS;
            int gridBottom = gridTop + gridRowsNow * GRID_CELL;
            g.m_280509_(panelLeft + 8, gridTop - 4, panelLeft + panelWidth - 8, gridBottom, 0x80101010);
            int perPage = GRID_COLS * this.gridRows;
            int start = this.creativePage * perPage;
            int end = Math.min(this.creativeItems.size(), start + perPage);
            int hoverIdx = -1;
            for (int i = start; i < end; i++) {
                int col = (i - start) % GRID_COLS;
                int row = (i - start) / GRID_COLS;
                int x = left + col * GRID_CELL;
                int y = gridTop + row * GRID_CELL;
                net.minecraft.world.item.ItemStack stack = this.creativeItems.get(i);
                net.minecraft.resources.ResourceLocation key =
                        net.minecraftforge.registries.ForgeRegistries.ITEMS.getKey(stack.m_41720_());
                String id = key == null ? "" : key.toString();
                if (this.isInAlt(id)) {
                    // 已加入当前替代品表 → 蓝色框 + 角标 ✓
                    g.m_280509_(x - 1, y - 1, x + 17, y + 17, 0x8022AADD);
                    g.m_280653_(this.f_96547_, Component.m_237113_("\u2714"),
                            x + 12, y + 12, 0xFFFFFF);
                }
                g.m_280480_(stack, x, y); // 物品图标
                if (mouseX >= x && mouseX < x + GRID_CELL && mouseY >= y && mouseY < y + GRID_CELL) {
                    hoverIdx = i;
                }
            }
            // v1.0.4：悬停名/页码移到网格右侧空白，不再盖住底部行图标（同矿表）
            int infoX = left + GRID_COLS * GRID_CELL + 12;
            int infoY = gridTop + 2;
            if (hoverIdx >= 0 && hoverIdx < this.creativeItems.size()) {
                net.minecraft.world.item.ItemStack stack = this.creativeItems.get(hoverIdx);
                net.minecraft.resources.ResourceLocation key =
                        net.minecraftforge.registries.ForgeRegistries.ITEMS.getKey(stack.m_41720_());
                String hover = key == null ? "?" : key.toString();
                // v1.0.4：两行——中文名 / 英文 id（与矿表同款，无优先级行）
                String hc = com.maidsmart.build.BlueprintLib.cnName(hover);
                g.m_280614_(this.f_96547_,
                        Component.m_237113_("\u00a7f" + (hc.equals(hover) ? hover : hc)),
                        infoX, infoY, 0xFFFFFF, false);
                g.m_280614_(this.f_96547_, Component.m_237113_("\u00a77" + hover),
                        infoX, infoY + 10, 0xAAAAAA, false);
            } else {
                int pages = this.creativePages();
                if (pages > 1) {
                    String pg = "第 " + (this.creativePage + 1) + "/" + pages + " 页";
                    g.m_280614_(this.f_96547_, Component.m_237113_(pg),
                            infoX, infoY, 0x888888, false);
                }
            }
            String chkHint = "\u00a77✓ = 已加入替代品表（" + modeNames[Math.min(this.altTableMode, 4)]
                    + "），缺料时女仆按序使用，再点一次取消";
            g.m_280653_(this.f_96547_, Component.m_237113_(chkHint),
                    this.clampCenterX(chkHint, cx), this.f_96544_ - 50, 0x888888);
        } else {
            // v1.1.0 实测二十四修复：标签 x 从硬编码 20 改为 panelLeft+10——
            // 旧版标签固定 x=20，面板和控件居中（panelLeft=Math.max(8,cx-280)），
            // 宽屏（GUI 缩放小）时标签在最左、控件在中间，视觉严重偏移
            int panelLeftR = Math.max(8, cx - 280);
            int leftR = panelLeftR + 10;
            g.m_280653_(this.f_96547_,
                    Component.m_237113_("\u00a7e" + this.section.title + " 设置"),
                    cx, 32, 0xFFFFFF);
            // 行标签（按行实际位置画；行数受分页限制不会越界）
            // v1.1.0 实测二十二：渲染侧行位置与布局侧同口径（动态行高累加）——
            // 旧版渲染独立按 ROW_H 匀质计算，与布局侧脱节就是重叠的根源
            // v1.1.0 实测四十五：直接用 init 侧算好的 pageRowY——
            // 渲染侧重算（旧实现）拿 start..end 行查【全表】累加坐标，第二页
            // 起行 y 是第一页的绝对位置（起点偏低/错位）→ 文本重叠排版错乱
            // v1.1.0 实测一百七十七：start/end/totalPages 同步改用 pageStarts
            // （逐页装填分页模型，与 init 侧完全同源——旧版按全局 perPage 均摊，
            // 行高不均的页 start/end 错位、页码总数也算错）
            int totalPagesR = Math.max(1, this.pageStarts.size());
            int pi = Math.min(Math.max(this.pageIndex, 0), totalPagesR - 1);
            int start = this.pageStarts.get(pi);
            int end = (pi + 1 < totalPagesR) ? this.pageStarts.get(pi + 1) : this.rows.size();
            for (int i = start; i < end; i++) {
                RowDef def = this.rows.get(i);
                int y = this.pageRowY[i];
                if (def instanceof SectionRow sr) {
                    String text = sr.sub()
                            ? "\u00a76—— " + sr.text() + " ——\u00a7r"
                            : "\u00a7e" + sr.text() + "\u00a7r";
                    g.m_280614_(this.f_96547_, Component.m_237113_(text), leftR, y, HELP_COLOR, false);
                } else if (def instanceof NumRow nr) {
                    g.m_280614_(this.f_96547_, Component.m_237113_(nr.label()), leftR, y + 4, LABEL_COLOR, false);
                    this.drawComment(g, nr.comment(), y + 25);
                } else if (def instanceof CycleRow cr) {
                    // v1.5.122：循环按钮行（标签与注释同 NumRow 布局）
                    g.m_280614_(this.f_96547_, Component.m_237113_(cr.label()), leftR, y + 4, LABEL_COLOR, false);
                    this.drawComment(g, cr.comment(), y + 25);
                } else if (def instanceof BoolRow br) {
                    g.m_280614_(this.f_96547_, Component.m_237113_(br.label()), leftR, y + 5, LABEL_COLOR, false);
                    this.drawComment(g, br.comment(), y + 25);
                } else if (def instanceof BtnRow btnr) {
                    g.m_280614_(this.f_96547_, Component.m_237113_(btnr.label()), leftR, y + 5, LABEL_COLOR, false);
                    this.drawComment(g, btnr.comment(), y + 25);
                } else if (def instanceof InfoRow ir) {
                    // v1.5.310：只读信息行——"标签：值"（值用青色高亮），无输入控件
                    String irLabel = ir.label() + "：";
                    g.m_280614_(this.f_96547_, Component.m_237113_(irLabel), leftR, y + 5, LABEL_COLOR, false);
                    g.m_280614_(this.f_96547_, Component.m_237113_(ir.value()),
                            leftR + this.f_96547_.m_92895_(irLabel) + 4, y + 5, 0x66CCFF, false);
                    this.drawComment(g, ir.comment(), y + 25);
                }
            }
            // 页码（v1.1.0 实测二十五：画在翻页箭头中间 h-62 行——箭头 20px 在
            // 两侧 cx±(20..40)，页码居中 <60px 宽，任何分辨率下不重叠）
            if (totalPagesR > 1) {
                g.m_280653_(this.f_96547_,
                        Component.m_237113_("第 " + (pi + 1) + "/" + totalPagesR + " 页"),
                        cx, h - 62, 0xAAAAAA);
            }
        }
        super.m_88315_(g, mouseX, mouseY, partialTick);
    }

    /** 矿表子页：点击创造物品网格中的方块图标 → 加入/取消当前名单（toggle） */
    @Override
    public boolean m_6375_(double mouseX, double mouseY, int button) {
        // v1.5.121：显式聚焦输入框——点击任意 EditBox 立即 setFocused（兜底：
        // 防 children 事件顺序/命中区域差异导致"永远点不进输入框"）
        // v1.5.122：加诊断日志（限频 200 tick）——点击时记录命中情况，定位"点不进"
        if (button == 0) {
            boolean hitEdit = false;
            for (net.minecraft.client.gui.components.events.GuiEventListener c : this.m_6702_()) {
                if (c instanceof net.minecraft.client.gui.components.EditBox eb) {
                    if (eb.m_5953_(mouseX, mouseY)) {
                        eb.m_93692_(true);
                        this.activeBox = eb; // v1.5.126：自跟踪焦点（同原版 searchBox 字段）
                        hitEdit = true;
                    }
                }
            }
            if (hitEdit && this.f_96543_ % 200 == 0) {
                com.mojang.logging.LogUtils.getLogger().info(
                        "config screen: 点击聚焦 EditBox @ ({},{})", mouseX, mouseY);
            }
        }
        if ((this.mineTable || this.woodTable) && button == 0) {
            int cx = this.f_96543_ / 2;
            int panelLeft = Math.max(8, cx - 280);
            int left = panelLeft + 10;
            int gridTop = GRID_TOP;
            int gridRowsNow = this.f_96544_ < 215 ? 2 : GRID_ROWS; // v1.5.190：与按钮/渲染同步
            int gridBottom = gridTop + gridRowsNow * GRID_CELL;
            if (mouseX >= left && mouseX < left + GRID_COLS * GRID_CELL
                    && mouseY >= gridTop && mouseY < gridBottom) {
                int perPage = GRID_COLS * this.gridRows;
                int start = this.creativePage * perPage;
                int col = (int) ((mouseX - left) / GRID_CELL);
                int row = (int) ((mouseY - gridTop) / GRID_CELL);
                int idx = start + row * GRID_COLS + col;
                if (idx >= 0 && idx < this.creativeItems.size()) {
                    net.minecraft.resources.ResourceLocation key = net.minecraftforge.registries.ForgeRegistries.ITEMS
                            .getKey(this.creativeItems.get(idx).m_41720_());
                    if (key != null) {
                        String id = key.toString();
                        if (this.mineTableMode == 0 && this.isInList(id)) {
                            // v1.0.4：已加入矿物的右上角小叉（渲染见网格循环）——点击取消添加
                            int gx = left + col * GRID_CELL;
                            int gy = gridTop + row * GRID_CELL;
                            if (mouseX >= gx + 11 && mouseX <= gx + 21
                                    && mouseY >= gy - 3 && mouseY <= gy + 7) {
                                this.removeOre(id);
                                return true;
                            }
                        }
                        if (this.mineTableMode == 0) {
                            // v1.0.4：目标矿物模式点击 = 锁定该方块（黄框固定 + 红字提示 +
                            // 输入框/添加按钮出现），输入数值点「添加」即赋值；取消用右上角小叉
                            this.lockedOreId = id;
                            this.m_7856_(); // 重建显示赋值输入框
                        } else {
                            this.toggleCreative(id);
                        }
                    }
                    return true;
                }
            }
        }
        // v1.5.254：替代品子页网格点击 → 加入/取消当前替代品表
        if (this.altTable && button == 0) {
            int cx = this.f_96543_ / 2;
            int panelLeft = Math.max(8, cx - 280);
            int left = panelLeft + 10;
            int gridTop = GRID_TOP;
            int gridRowsNow = this.f_96544_ < 215 ? 2 : GRID_ROWS;
            int gridBottom = gridTop + gridRowsNow * GRID_CELL;
            if (mouseX >= left && mouseX < left + GRID_COLS * GRID_CELL
                    && mouseY >= gridTop && mouseY < gridBottom) {
                int perPage = GRID_COLS * this.gridRows;
                int start = this.creativePage * perPage;
                int col = (int) ((mouseX - left) / GRID_CELL);
                int row = (int) ((mouseY - gridTop) / GRID_CELL);
                int idx = start + row * GRID_COLS + col;
                if (idx >= 0 && idx < this.creativeItems.size()) {
                    net.minecraft.resources.ResourceLocation key = net.minecraftforge.registries.ForgeRegistries.ITEMS
                            .getKey(this.creativeItems.get(idx).m_41720_());
                    if (key != null) {
                        this.toggleAltCreative(key.toString());
                    }
                    return true;
                }
            }
        }
        return super.m_6375_(mouseX, mouseY, button);
    }

    /**
     * v1.5.126：真正的字符输入入口（照搬 MC 原版创造搜索框）——
     * 1.20.1 的键盘字符链是 KeyboardHandler.charTyped → screen.m_5534_(char,int)
     * （GuiEventListener 接口方法；Screen 自身不实现，走 ContainerEventHandler
     * 默认实现 = 转发给 getFocused()）。原版 CreativeModeInventoryScreen 不依赖
     * getFocused()，而是重写 m_5534_/m_7933_ 直接转发给自己持有的 searchBox 字段
     * ——这里同样直接转发给自跟踪的 activeBox（点击输入框时记录，见 m_6375_），
     * 再兜底 super（getFocused() 链）。旧版 v1.5.123 重写的 m_96583_(String,char,int)
     * 经字节码实证是 1.20.1 无人调用的死代码（方法体是字符合法性过滤器），
     * 这是"点进输入框但打不了字"的根因——重写的方法根本不参与输入分发。
     */
    @Override
    public boolean m_5534_(char codePoint, int modifiers) {
        if (this.activeBox != null && this.activeBox.m_5534_(codePoint, modifiers)) {
            return true;
        }
        return super.m_5534_(codePoint, modifiers);
    }

    @Override
    public boolean m_7933_(int key, int scanCode, int modifiers) {
        // v1.0.4：赋值/添加输入框回车 = 点「添加」（Enter 257 / 小键盘回车 335）
        if (this.minableInput != null && this.activeBox == this.minableInput
                && (key == 257 || key == 335)) {
            this.addMinable();
            return true;
        }
        if (this.activeBox != null && this.activeBox.m_7933_(key, scanCode, modifiers)) {
            return true;
        }
        return super.m_7933_(key, scanCode, modifiers);
    }

    /** 兼容兜底：1.20.1 输入链不调用本方法（保留转发，防其他路径/未来版本调用） */
    @Override
    protected boolean m_96583_(String text, char codePoint, int modifiers) {
        if (this.activeBox != null && this.activeBox.m_5534_(codePoint, modifiers)) {
            return true;
        }
        return super.m_96583_(text, codePoint, modifiers);
    }

    @Override
    public void m_7379_() {
        // v1.5.124：延迟提交——把各输入框当前文本统一写入配置
        //（空文本/非法文本跳过，保留原值；输入过程零配置写入 = 不卡输入）
        for (java.util.Map.Entry<String, String> e : this.pendingText.entrySet()) {
            Function<String, Boolean> setter = this.numSetters.get(e.getKey());
            if (setter == null || !validNumText(e.getValue())) {
                continue;
            }
            try {
                setter.apply(e.getValue());
            } catch (Exception ignored) {
            }
        }
        // v1.5.127：文本行（保留/垃圾物品 id 列表）——非空即写入
        // v1.5.198：记忆 API 字段允许空值写入（清空 = 回退 TLM 配置）
        for (java.util.Map.Entry<String, String> e : this.pendingText.entrySet()) {
            Function<String, Boolean> setter = this.textSetters.get(e.getKey());
            if (setter != null && (!e.getValue().trim().isEmpty() || EMPTY_ALLOWED.contains(setter))) {
                try {
                    setter.apply(e.getValue());
                } catch (Exception ignored) {
                }
            }
        }
        MaidSmartConfig.SPEC.save();
        com.maidsmart.task.MaidMineBehavior.loadCustomOres();
        com.maidsmart.task.MaidWoodBehavior.loadCustomWoods();
        Minecraft.m_91087_().m_91152_(this.parent);
    }
}
