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
    /** 实测四百二十三：是否处于"大类页"（首页 inHome → 大类页 inGroup → 参数页） */
    private boolean inGroup = false;
    /** 当前大类（大类页/参数页返回时定位用） */
    private Group group = Group.WORK;
    /** 实测四百二十四：手册链接跳转的目标行标签（null = 不定位） */
    private String focusLabel = null;
    /** 目标行在当前页的下标（-1 = 无），渲染时黄框高亮 */
    private int focusRow = -1;
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
    /**
     * v1.3.0 实测六百六十六：保存时的提示（越界被钳 / 没写成功）——画在底部按钮行上方。
     * ：旧版越界值被**静默**
     * 写进文件（下次加载才钳）或静默丢弃，面板上一个字都不提示。
     */
    private String saveHint = null;
    /** v1.3.0 实测六百六十六：被钳到边界的那些行（"散步间隔 超出 20~1728000，已按 1728000 生效"） */
    private final java.util.List<String> numNotices = new java.util.ArrayList<>();
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
             com.maidsmart.diag.Diag.swallow("PromaidConfigScreen", e); return false;
        }
    }
    private MinableList minableList;
    /** v1.5.101b：恢复输入添加框（矿物 "id=value"，障碍物 "id"） */
    private EditBox minableInput;
    /** 回退历史深度（实测八百二十九b：由 1 步放宽到 3 步——单步容错率太低，手滑连删两下就回不去了）。 */
    private static final int UNDO_DEPTH = 3;
    /** 实测八百二十九【回退】：矿表/木材表页的回退历史——最近 {@link #UNDO_DEPTH} 次改动前的三张列表快照（见 {@link MineSnap}）。 */
    private final java.util.ArrayDeque<MineSnap> undoStack = new java.util.ArrayDeque<>();
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
    /** v1.2.0 实测五百一十九：投喂食物勾选子页（食物黑名单图形化勾选，与矿表/替代品子页同款交互） */
    private boolean foodTable = false;
    private EditBox foodInput;
    private FoodList foodList;
    /** 实测五百七十三：喂水白名单子页（口渴软联动）——与投喂食物勾选子页同款交互 */
    private boolean waterTable = false;
    private EditBox waterInput;
    private WaterList waterList;
    /**
     * 实测八百一十七：支援物品分类子页（通用 / 亡灵两张表）。
     * <p>{@code supportMode}：0 = 通用（非亡灵目标），1 = 亡灵。点图标循环切换
     * 自动·对策·增益·回复·禁用（写回 aidSupportCategory / aidSupportCategoryUndead）。
     */
    private boolean supportTable = false;
    private int supportMode = 0;
    private EditBox supportInput;
    private SupportList supportList;
    /** 支援物品候选缓存（{裸物品id, 中文名, 记账键, 后缀, 药水id}）——懒构建 1 分钟 */
    private static java.util.List<String[]> supportCache = null;
    private static long supportCacheBuilt = -1;
    /** v1.2.5 实测六百五十二：烧制清单子页（四张名单共用一套「模式按钮 + 搜索网格 + 清单」交互） */
    private boolean cookTable = false;
    private int cookTableMode = 0;
    /** v1.3.0(beta) 实测六百六十五：烧制物品列表的筛选（0 = 全部 / 1 = 只看食物 / 2 = 只看矿物）。
     *  ——判定没错（日志实证 beef/potato 都是"真"），是 148 个候选要翻
     *  4 页又没有分类入口；这一档只作用于「烧制物品」，燃料那一档不看它。 */
    private int cookGridFilter = 0;
    private EditBox cookInput;
    private CookList cookList;
    /** v1.3.0(beta) 实测六百八十：搭方块禁用名单子页（搭路板块）——点一下切换"禁止/允许" */
    private boolean buildBlackTable = false;
    private BuildBlackList buildBlackList;
    /**
     * v1.5.392 实测七百七十二：超越维度（BeyondDimensions）规则名单子页。
     * <p>{@code bdRuleMode}：0=一定搬 1=一定不搬 2=保留N个 3=至少留N个。
     * 本子页改的是**服务端** config/promaid_bd_rules.json，全程经
     * {@link com.maidsmart.bd.MaidBdNetworking} 发 C2S 包，面板只读客户端缓存渲染。
     */
    private boolean bdRules = false;
    private int bdRuleMode = 0;
    private EditBox bdInput;
    private EditBox bdNInput;
    private BdRuleList bdRuleList;
    /** v1.5.100b：创造物品面板（矿表子页）——搜索框 + 物品网格，点击方块图标添加 */
    private EditBox creativeInput;
    private String creativeQuery = "";
    private int creativePage = 0;
    private final List<net.minecraft.world.item.ItemStack> creativeItems = new ArrayList<>();
    /** 网格列数/格距（格 18px 图标 + 2px 间距）——v1.0.4：8→16，每页 24→48 个
     *  （矿表 184 页减半到 92；右侧仍留 ~210px 给悬停提示） */
    private static final int GRID_COLS = 16;
    private static final int GRID_CELL = 20;
    /**
     * 实测八百一十七【字压图标】自适应列数——面板窄的时候，固定 16 列（320px）会把右侧
     * 「悬停信息列」挤掉：drawHoverInfo 为防右溢出把起点往左收，收着收着就压到第 12~16 列
     * 的图标上了（玩家截图：绿色文字盖在物品图标与英文注册名上）。
     * <p>列数按「面板可用宽 − 信息列宽（约 178px）」动态算，最小 6 列；再把
     * {@link #gridInfoX} 记下来给悬停信息用。宽面板照旧 16 列，行为不变。
     */
    private int gridCols = GRID_COLS;
    /** 当前这一屏网格左缘 + 实际占用宽（悬停信息列起点 = 这里） */
    private int gridInfoX = 0;
    /** 实测八百一十七续：面板内容右缘——悬停文字越界即折行（旧版只按屏幕宽判，会横冲出面板） */
    private int gridPanelRight = 0;
    /** 实测八百一十七续：悬停文字下界（网格页 = 底部列表上沿）——画到界外即截断，防“字压 UI”；0=不限 */
    private int hoverBottom = 0;

    /** 按面板宽算列数并返回（同时更新 {@link #gridCols}） */
    private int computeGridCols(int panelWidth) {
        int avail = panelWidth - 20 - 178; // 每格 20px；右侧留 178px 给悬停信息
        int cols = Math.max(6, Math.min(GRID_COLS, avail / GRID_CELL));
        this.gridCols = cols;
        return cols;
    }

    /**
     * 实测八百一十七续：网格行数按**窗口高自适应**。
     *
     * <p>旧版固定三元式（h&lt;215 则 2 行，否则 3 行）在矮窗口把底部的
     * 「搜索/翻页 + 手填输入行 + 已分类名单」整条挤没：名单只剩 0~8px，
     * 汇总文字还会直接压在名单那一行上（玩家截图）。
     *
     * <p>反解底部预算：网格底下还要放
     * 翻页(18) + 输入(24+18) + 名单(22) + 汇总(9) + 间距(约 37) ≈ 128px，
     * 故要求 {@code 66 + rows*20 <= h - 128} → {@code rows <= (h-194)/20}，夹在 [1, {@link #GRID_ROWS}]。
     * 窗口够高时仍是 3 行，行为不变。
     */
    private int gridRowsFor(int h) {
        int byHeight = (h - 194) / GRID_CELL;
        return Math.max(1, Math.min(GRID_ROWS, byHeight));
    }

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
    /** v1.2.0 实测五百一十九：食物缓存（{id, 中文名}）——与 creativeCache 同款懒构建 */
    private static java.util.List<String[]> foodCache = null;
    private static long foodCacheBuilt = -1;
    /** 实测五百七十三：喂水候选缓存（{id, 中文名}）——与 foodCache 同款懒构建 */
    private static java.util.List<String[]> waterCache = null;
    private static long waterCacheBuilt = -1;

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
            } catch (Exception ignored) { com.maidsmart.diag.Diag.swallow("PromaidConfigScreen", ignored);
            }
            creativeCache.add(new String[]{id, cn == null ? "" : cn});
        }
        creativeCacheBuilt = now;
    }

    /**
     * 实测五百八十：把一个候选物品按"**剩余次数**"铺成若干行（用户需求："能够喝 4/4 次的
     * 和喝 3/4 次的在面板里面属于两种不同的物品"）。
     *
     * 行格式统一为 4 元：{裸 id, 中文名, 配置项键, 剩余次数说明}——
     * 裸 id 用于解析图标/搜索，**配置项键**才是写进白/黑名单的值（{@code id#剩余次数}）。
     * 耐久物品（水壶这类能喝好几次的）每档一行；普通物品只有一行（键 = 裸 id，与旧版一致）。
     */
    private static void addVariantRows(java.util.List<String[]> out,
                                       net.minecraft.world.item.Item item,
                                       String id, String cn, java.util.Set<String> seen) {
        net.minecraft.world.item.ItemStack probe;
        try {
            probe = new net.minecraft.world.item.ItemStack(item);
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("PromaidConfigScreen", ignored);
            return;
        }
        int max = com.maidsmart.action.ItemUses.total(probe);
        if (max <= 0) {
            if (seen.add(id)) {
                out.add(new String[]{id, cn == null ? "" : cn, id, ""});
            }
            return;
        }
        for (int rem = max; rem >= 1; rem--) {
            String k = id + com.maidsmart.action.ItemUses.SEP + rem;
            if (seen.add(k)) {
                out.add(new String[]{id, cn == null ? "" : cn, k, "剩余 " + rem + "/" + max + " 次"});
            }
        }
    }

    /**
     * 实测五百一十九：食物候选缓存（{id, 中文名}）——与 ensureCreativeCache 同款懒构建、
     * 1 分钟缓存。候选口径 = **带食物属性**的任意物品（含模组食物），与 TLM 自己吃食
     * 同一判据（有 `m_41473_()` 即可，不看是不是原版）。
     */
    private static void ensureFoodCache() {
        long now = System.currentTimeMillis();
        if (foodCache != null && now - foodCacheBuilt < 60_000L) {
            return; // 1 分钟缓存（模组运行时注册表不会变）
        }
        foodCache = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (net.minecraft.world.item.Item item : net.minecraftforge.registries.ForgeRegistries.ITEMS) {
            net.minecraft.world.item.ItemStack stack;
            try {
                stack = new net.minecraft.world.item.ItemStack(item);
                if (stack.m_41619_()) {
                    continue;
                }
                if (item.m_41473_() == null) {
                    continue; // 没有食物属性 → 不是食物
                }
            } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("PromaidConfigScreen", ignored);
                continue;
            }
            net.minecraft.resources.ResourceLocation key =
                    net.minecraftforge.registries.ForgeRegistries.ITEMS.getKey(item);
            if (key == null) {
                continue;
            }
            String cn = "";
            try {
                cn = stack.m_41786_().getString();
            } catch (Exception ignored) { com.maidsmart.diag.Diag.swallow("PromaidConfigScreen", ignored);
            }
            // 实测五百八十：耐久物品（"能喂好几次"的）按剩余次数各占一行
            addVariantRows(foodCache, item, key.toString(), cn, seen);
        }
        foodCacheBuilt = now;
    }

    /**
     * 实测五百七十三：喂水候选缓存（{id, 中文名}）——与 ensureFoodCache 同款懒构建、1 分钟缓存。
     * 候选口径 = **能恢复口渴的物品**（口渴软联动的 ThirstHelper.itemRestoresThirst）∪ 水瓶
     * ∪ 白名单里已有的条目（物品被移除/改名时也要能在列表里取消勾选）。
     * 没装「口渴」时本页不会打开（入口行本身按模组在场条件注册）。
     */
    private static void ensureWaterCache() {
        long now = System.currentTimeMillis();
        if (waterCache != null && now - waterCacheBuilt < 60_000L) {
            return;
        }
        waterCache = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (net.minecraft.world.item.Item item : net.minecraftforge.registries.ForgeRegistries.ITEMS) {
            net.minecraft.world.item.ItemStack stack;
            try {
                stack = new net.minecraft.world.item.ItemStack(item);
                if (stack.m_41619_()) {
                    continue;
                }
                if (!com.maidsmart.action.ThirstCompat.itemRestoresThirst(stack)
                        && !com.maidsmart.action.ThirstCompat.isWaterPotion(stack)) {
                    continue; // 既不恢复口渴也不是水瓶 → 不是候选
                }
            } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("PromaidConfigScreen", ignored);
                continue;
            }
            net.minecraft.resources.ResourceLocation key =
                    net.minecraftforge.registries.ForgeRegistries.ITEMS.getKey(item);
            if (key == null || !seen.add(key.toString())) {
                continue;
            }
            String cn = "";
            try {
                cn = stack.m_41786_().getString();
            } catch (Exception ignored) { com.maidsmart.diag.Diag.swallow("PromaidConfigScreen", ignored);
            }
            // 实测五百八十：同 ensureFoodCache——耐久容器（水壶）按剩余次数各占一行
            addVariantRows(waterCache, item, key.toString(), cn, seen);
        }
        try {
            for (String id : MaidSmartConfig.AID_DRINK_WHITELIST.get()) {
                if (id == null || id.isEmpty() || !seen.add(id)) {
                    continue;
                }
                String base = com.maidsmart.action.ItemUses.baseId(id);
                String cn = "";
                try {
                    net.minecraft.world.item.Item it = net.minecraftforge.registries.ForgeRegistries.ITEMS
                            .getValue(new net.minecraft.resources.ResourceLocation(base));
                    if (it != null) {
                        // 耐久物品：注册表循环已按剩余次数铺好行，这里补一行会与"满次数"那行长得一样
                        //（都是满耐久图标）→ 跳过；裸条目仍留在底部列表里可点「不喂了」移除
                        if (com.maidsmart.action.ItemUses.variant(
                                new net.minecraft.world.item.ItemStack(it))) {
                            continue;
                        }
                        cn = new net.minecraft.world.item.ItemStack(it).m_41786_().getString();
                    }
                } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("PromaidConfigScreen", ignored);
                }
                waterCache.add(new String[]{base, cn == null ? "" : cn, id,
                        com.maidsmart.action.ItemUses.labelOfKey(id)});
            }
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("PromaidConfigScreen", ignored);
        }
        waterCacheBuilt = now;
    }

    /** 实测四百二十三【配置面板重组】：大类（Group）——首页只列大类，进入后列小类（Section）。 */
    private enum Group {
        WORK("\u00a7e生产与工作"), AI("\u00a7eAI 与对话"), COMBAT("\u00a7e战斗与自保"),
        SURVIVAL("\u00a7e生存与复活"), MOVE("\u00a7e移动与行为"), UI("\u00a7e语音与显示"),
        SYSTEM("\u00a7e系统与杂项");
        final String title;

        Group(String title) {
            this.title = title;
        }
    }

    /** 实测四百二十三：小类（每个小类一页参数行；同一大类下的小类在二级页列出）。 */
    private enum Section {
        BUILD("建造", Group.WORK), MINE("挖矿", Group.WORK), WOOD("伐木", Group.WORK),
        COOK_BREW("烹饪与酿造", Group.WORK), FARM("农场与宰杀", Group.WORK),
        MEMORY("AI 记忆", Group.AI), DIALOGUE("对话与自主", Group.AI), PERCEPTION("感知", Group.AI),
        AFFECT("情绪", Group.AI), AITOOLS("AI 工具", Group.AI),
        SELF_PRESERVE("自保与保命", Group.COMBAT), SELF_TACTICS("自保战术", Group.COMBAT),
        TACTICS("单兵战术", Group.COMBAT), AUTO_COMBAT("主动参战", Group.COMBAT),
        AID("贴身辅助", Group.COMBAT), PLAYER_DAMAGE("玩家伤害策略", Group.COMBAT),
        AIR_RAID("空袭数值", Group.COMBAT),
        FALL_GUARD("落地缓冲", Group.SURVIVAL),
        // 【实测六百七十五】搭路从「移动与行为」那一段的开头挪到末尾：让"飞行三件套"
        //  （飞行跟随 / 扫帚模式 / 武装拴绳）连着排在最前面——
        //
        // v1.2.2 实测六百一十五：飞行跟随从「搭路」里拎出来，与搭路平级（用户
        //
        FLIGHT_FOLLOW("飞行跟随", Group.MOVE),
        // v1.3.6 实测六百六十一：扫帚模式自己的数值板块（用户
        //
        // 现在与「飞行跟随」平级、位置就在它后面（同一个「移动与行为」大类）。
        BROOM("扫帚模式", Group.MOVE),
        // v1.3.7 实测六百六十七 / v1.3.0(beta) 实测六百七十五：武装拴绳**独立成板块**。
        //
        //
        //
        // 再往下滚才看得到），现在与扫帚平级、紧跟在它后面，页内第一条就是"这个功能是什么"的详解。
        TETHER("武装拴绳（二号位）", Group.MOVE),
        // v1.3.0(beta)：骑乘指挥棒（原版生物骑乘）——与"飞行三件套"同族（都是"她怎么移动"），
        // 紧跟在武装拴绳之后（顺序 = 本枚举声明顺序）。
        RIDE("骑乘指挥棒", Group.MOVE),
        // v1.5.393 实测八百零四：饰品「大运」——与"飞行三件套"/骑乘指挥棒同族（都是"她怎么动"），
        // 且本身就是一件可佩戴的道具，故与它们并排挂在「移动与行为」下（顺序 = 本枚举声明顺序）。
        DA_YUN("饰品「大运」", Group.MOVE),
        // v1.3.0(beta) 实测七百〇三：接敌机动——**两条链路共有**的一件事（扫帚接敌 + 鞘翅空袭），
        // 所以单独成板而不是塞进任一边（塞进去另一边的人会找不到）。挂在「战斗与自保」下、
        // 紧跟在「空袭数值」之后（顺序 = 本枚举声明顺序）。
        MANEUVER("接敌机动", Group.COMBAT),
        REVIVE("死亡与复活", Group.SURVIVAL), ESCAPE("传送与逃生", Group.SURVIVAL),
        SAFETY("女仆安全与区块", Group.SURVIVAL),
        FOLLOW("移动与跟随", Group.MOVE), IDLE("空闲与流畅", Group.MOVE),
        BRIDGE("搭路", Group.MOVE), // 【实测六百七十五】移到这里，见上面 FALL_GUARD 那一行的说明
        SCHEDULE("排班表", Group.WORK), // 实测五百七十五：手册一直写「生产与工作 → 排班表」，面板却挂在移动与行为——按手册归位
        VOICE("语音与 TTS", Group.UI), HUD("显示与提示", Group.UI),
        UTILITY("交互与杂项", Group.SYSTEM), LOG("运行日志", Group.SYSTEM),
        // v1.2.2 实测六百一十六：压缩盒（新道具/新方块）——自己的两条参数，挂在系统与杂项下
        COMPRESSION_BOX("压缩盒", Group.SYSTEM),
        // 实测八百六十五：自动补全小类——把 toml 里「手工面板没收录」的项反射列出来
        // （见 extraRows），兑现「所有 toml 都在手册里有对应开关」。
        EXTRA("其余开关（自动）", Group.SYSTEM);
        final String title;
        final Group group;

        Section(String title, Group group) {
            this.title = title;
            this.group = group;
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

    /**
     * 数值输入行（v1.5.103：onChange 返回 Boolean = 是否设置成功，失败时输入框红字提示；
     * v1.5.110：末位 comment = 该项说明注释）。
     *
     * v1.3.0 实测六百六十六【可选范围】：min/max 非空时，输入越界**当场红字**，保存时钳到
     * 边界并提示（"已按 X 生效"）。为什么必须由面板自己判范围——见 {@code setIntInRange}
     * 的注释（Forge 的 set() 不校验范围，越界值要等下次加载才被静默钳掉）。
     * 四参构造器保持原样：绝大多数行不声明范围，行为与旧版一字不差。
     */
    private record NumRow(String label, String value, Function<String, Boolean> onChange,
                          String comment, Double min, Double max) implements RowDef {
        NumRow(String label, String value, Function<String, Boolean> onChange, String comment) {
            this(label, value, onChange, comment, null, null);
        }
    }

    /** 开关行（开/关循环按钮） */
    private record BoolRow(String label, boolean value, Consumer<Boolean> onChange,
                           String comment) implements RowDef {
    }

    /** 按钮行（标签 + 右侧按钮，跳转用） */
    private record BtnRow(String label, String btnText, Runnable onClick,
                          String comment) implements RowDef {
    }

    /**
     * 实测四百二十四：手册内链接跳转入口——按【大类名/小类名（枚举名或中文标题）】
     * 直接打开模组详细配置的对应页；rowLabel 非空时再翻到该行所在页并高亮它。
     * 只给到小类名就进小类参数页，只给到大类名就进大类页，都没解析到则回首页。
     */
    public static void openAt(net.minecraft.client.gui.screens.Screen parent,
                              String groupName, String sectionName, String rowLabel) {
        PromaidConfigScreen scr = new PromaidConfigScreen(parent);
        Group g = null;
        for (Group x : Group.values()) {
            if (matches(x.name(), x.title, groupName)) {
                g = x;
                break;
            }
        }
        Section s = null;
        for (Section x : Section.values()) {
            if (matches(x.name(), x.title, sectionName)) {
                s = x;
                break;
            }
        }
        if (g != null) {
            scr.group = g;
        }
        if (s != null) {
            scr.section = s;
            scr.group = s.group;
            scr.inHome = false;
            scr.inGroup = false;
        } else if (g != null) {
            scr.inHome = false;
            scr.inGroup = true;
        }
        scr.focusLabel = (rowLabel == null || rowLabel.isEmpty()) ? null : rowLabel;
        // v1.3.0 实测六百六十六：跳转前先把**当前面板**里未提交的输入写进配置。
        // 旧版只有「保存并返回」/Esc 会走 m_7379_（延迟提交的统一入口），而用目录/板块/
        // 手册跳转离开是直接 setScreen——输入框里刚填的数整段丢掉，玩家看到的还是"填了没用"。
        try {
            if (net.minecraft.client.Minecraft.m_91087_().f_91080_ instanceof PromaidConfigScreen cur) {
                cur.applyPending();
            }
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("PromaidConfigScreen", ignored);
        }
        net.minecraft.client.Minecraft.m_91087_().m_91152_(scr);
    }

    private static boolean matches(String enumName, String title, String wanted) {
        if (wanted == null || wanted.isEmpty()) {
            return false;
        }
        return enumName.equalsIgnoreCase(wanted)
                || title.equals(wanted)
                || title.contains(wanted)
                || wanted.contains(title);
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
        // 实测八百一十七：网格列数按窗口宽自适应（窄屏不再让悬停文字压到图标上）
        this.computeGridCols(Math.min(560, w - 16));
        // 实测八百一十七续：悬停信息右缘=面板内容右缘；下界=网格页底部列表上沿
        this.gridPanelRight = Math.max(8, cx - 280) + Math.min(560, w - 16) - 14;
        this.hoverBottom = (this.mineTable || this.woodTable || this.altTable || this.foodTable
                || this.waterTable || this.supportTable || this.cookTable || this.buildBlackTable
                || this.bdRules)
                ? 66 + (this.gridRowsFor(h)) * GRID_CELL + 46 : 0;
        if (this.inHome) {
            this.homeButtons(w, h, cx);
            return;
        }
        if (this.inGroup) {
            this.groupButtons(w, h, cx);
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
        if (this.foodTable) {
            this.foodTableButtons(w, h, cx);
            return;
        }
        if (this.waterTable) {
            this.waterTableButtons(w, h, cx);
            return;
        }
        if (this.supportTable) {
            this.supportTableButtons(w, h, cx);
            return;
        }
        if (this.cookTable) {
            this.cookTableButtons(w, h, cx);
            return;
        }
        if (this.buildBlackTable) {
            this.buildBlackTableButtons(w, h, cx);
            return;
        }
        if (this.bdRules) {
            this.bdRulesButtons(w, h, cx);
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
    /** 实测四百二十三【配置面板重组】：首页只列 7 个大类，进入大类后列小类。
     *  两级菜单共用 menuGrid（两列竖排、行距按数量/高度自适应），不与底部按钮相交。 */
    private void homeButtons(int w, int h, int cx) {
        Group[] gs = Group.values();
        String[] labels = new String[gs.length];
        Runnable[] actions = new Runnable[gs.length];
        for (int i = 0; i < gs.length; i++) {
            final Group g = gs[i];
            labels[i] = g.title;
            actions[i] = () -> {
                this.group = g;
                this.inHome = false;
                this.inGroup = true;
                this.m_7856_();
            };
        }
        this.menuGrid(w, h, labels, actions);
        this.bottomButtons(w, h, cx);
    }

    /** 大类页：列出该大类下的小类（点击进入参数页）。 */
    private void groupButtons(int w, int h, int cx) {
        java.util.List<Section> list = new java.util.ArrayList<>();
        for (Section s : Section.values()) {
            if (s.group == this.group) {
                list.add(s);
            }
        }
        String[] labels = new String[list.size()];
        Runnable[] actions = new Runnable[list.size()];
        for (int i = 0; i < list.size(); i++) {
            final Section s = list.get(i);
            labels[i] = "\u00a7e" + s.title;
            actions[i] = () -> {
                this.section = s;
                this.pageIndex = 0;
                this.mineTable = false;
                this.woodTable = false;
                this.altTable = false;
                this.foodTable = false;
                this.waterTable = false;
                this.inGroup = false;
                this.buildBlackTable = false;
                this.bdRules = false;
                this.m_7856_();
            };
        }
        this.menuGrid(w, h, labels, actions);
        this.m_142416_(Button.m_253074_(Component.m_237113_("\u2190 \u8fd4\u56de\u5927\u7c7b"),
                        b -> {
                            this.inGroup = false;
                            this.inHome = true;
                            this.m_7856_();
                        })
                .m_252987_(12, h - 34, 100, 20).m_253136_());
        this.bottomButtons(w, h, cx);
    }

    /** 两级菜单通用按钮网格：两列竖排；行距按按钮数/可用高度自适应压缩，
     *  保证末位按钮底 ≤ 底部按钮上缘（h-34）-2，任何数量/窗口高度都不重叠。 */
    private void menuGrid(int w, int h, String[] labels, Runnable[] actions) {
        int n = labels.length;
        int cols = 2;
        int rows = Math.max(1, (n + cols - 1) / cols);
        int bw = Math.min(190, (w - 56) / 2);
        int bh = 22;
        int rowH = 24;
        int y0Min = 50;
        int availBottom = h - 36;
        if (rows > 1) {
            int fit = (availBottom - y0Min - bh) / (rows - 1);
            rowH = Math.min(rowH, Math.max(14, fit));
        }
        if (rowH < 22) {
            bh = Math.max(11, rowH - 3);
        }
        int x1 = (w - bw * 2 - 16) / 2;
        int x2 = x1 + bw + 16;
        int contentH = (rows - 1) * rowH + bh;
        int y0 = y0Min + Math.max(0, Math.min(6, (availBottom - y0Min - contentH) / 2));
        for (int i = 0; i < n; i++) {
            final Runnable act = actions[i];
            int col = i % cols;
            int row = i / cols;
            this.m_142416_(Button.m_253074_(Component.m_237113_(labels[i]), b -> act.run())
                    .m_252987_(col == 0 ? x1 : x2, y0 + row * rowH, bw, bh).m_253136_());
        }
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
            case COOK_BREW -> this.cookBrewRows();
            case FARM -> this.farmRows();
            case MEMORY -> this.memoryRows();
            case DIALOGUE -> this.dialogueRows();
            case PERCEPTION -> this.perceptionRows();
            case AFFECT -> this.affectRows();
            case AITOOLS -> this.aiToolsRows();
            case SELF_PRESERVE -> this.selfPreserveRows();
            case SELF_TACTICS -> this.selfTacticsRows();
            case TACTICS -> this.tacticsRows();
            case AUTO_COMBAT -> this.autoCombatRows();
            case AID -> this.aidRows();
            case PLAYER_DAMAGE -> this.playerDamageRows();
            case AIR_RAID -> this.airRaidRows();
            case MANEUVER -> this.maneuverRows();
            case FALL_GUARD -> this.fallGuardRows();
            case BRIDGE -> this.bridgeRows();
            case FLIGHT_FOLLOW -> this.flightFollowRows();
            case BROOM -> this.broomRows();
            case TETHER -> this.tetherRows();
            case RIDE -> this.rideRows();
            case DA_YUN -> this.daYunRows();
            case REVIVE -> this.reviveRows();
            case ESCAPE -> this.escapeRows();
            case SAFETY -> this.safetyRows();
            case FOLLOW -> this.followRows();
            case IDLE -> this.idleRows();
            case SCHEDULE -> this.scheduleRows();
            case VOICE -> this.voiceRows();
            case HUD -> this.hudRows();
            case UTILITY -> this.utilityRows();
            case LOG -> this.logRows();
            case COMPRESSION_BOX -> this.compressionBoxRows();
            case EXTRA -> this.extraRows();
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
        // 实测四百二十四：手册链接跳转过来的高亮行——
        // 先找到标签匹配的行，再把 pageIndex 调到它所在页（必须在下方
        // 计算 start/end 之前完成）。
        this.focusRow = -1;
        if (this.focusLabel != null) {
            for (int i = 0; i < this.rows.size(); i++) {
                String lb = rowLabel(this.rows.get(i));
                if (lb != null && (lb.equals(this.focusLabel)
                        || lb.startsWith(this.focusLabel) || this.focusLabel.startsWith(lb))) {
                    this.focusRow = i;
                    break;
                }
            }
            if (this.focusRow >= 0) {
                for (int p = this.pageStarts.size() - 1; p >= 0; p--) {
                    if (this.pageStarts.get(p) <= this.focusRow) {
                        this.pageIndex = p;
                        break;
                    }
                }
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
                // v1.3.0 实测六百六十六：范围也当场判（旧版只判"是不是数字"，越界还是白字
                // ——玩家填 4000000 看着完全正常，保存后却什么也没发生，这就是"不可调"的观感）
                final Double rMin = nr.min();
                final Double rMax = nr.max();
                box.m_94151_(s -> {
                    this.pendingText.put(rowKey, s);
                    box.m_94202_(validNumText(s) && inRangeOrUnbounded(s, rMin, rMax)
                            ? 0xFFFFFF : 0xFFFF5555);
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
        this.m_142416_(Button.m_253074_(Component.m_237113_("\u2190 \u8fd4\u56de\u5206\u7c7b"),
                        b -> {
                            this.inGroup = true;
                            this.inHome = false;
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
        int gridRowsNow = this.gridRowsFor(h);
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
        // 实测八百二十九【回退】：回退键（最多 3 步）——把上一步改动（点图标 / 一键全部加入 /
        // 输入添加 / 列表删除 / 赋优先级）之前的三张列表整张还原，可连点回退逐步往回退。放在
        // 名单切换键右侧（tgY 行本来就空着，两个切换键止于 left+196，这里从 left+202 起，任何
        // 窗口宽度都不重叠）；键面在还剩多步时缀「×N」；没有可回退的操作时灰显（点击给一行灰字
        // 提示，不静默失效）。
        int undoDepth = this.undoStack.size();
        this.m_142416_(Button.m_253074_(
                        Component.m_237113_((undoDepth > 0 ? "\u00a7e" : "\u00a77") + "\u21a9 回退"
                                + (undoDepth > 1 ? " \u00d7" + undoDepth : "")),
                        b -> this.performUndo())
                .m_252987_(left + 202, tgY, 88, 18).m_253136_());
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
            // 实测八百二十八：障碍物模式加「一键全部加入」按钮（
            //
            //
            // 可挖穿（障碍物）——搜索框过滤后只处理筛出来的那批，天然支持"先搜再一键加"。
            // 按钮放在障碍物模式本就空着的输入框行（gridBottom+24，矿物模式那行被
            // 赋值输入框占用，所以只在障碍物模式显示）。
            this.m_142416_(Button.m_253074_(
                            Component.m_237113_("\u00a7b\u2714 一键全部加入"),
                            b -> this.addAllObstacles())
                    .m_252987_(left, inputY, 160, 18).m_253136_());
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
    /**
     * v1.2.0 实测五百一十九：投喂食物勾选子页——顶部搜索框 + 物品网格（点击图标切换"能不能吃"）
     * + 底部黑名单列表（每行「允许吃」一键移出）。交互与矿表/替代品子页同款。
     */
    private void foodTableButtons(int w, int h, int cx) {
        int panelLeft = Math.max(8, cx - 280);
        int panelWidth = Math.min(560, w - 16);
        int left = panelLeft + 10;
        int gridRowsNow = this.gridRowsFor(h);
        this.creativeInput = new EditBox(this.f_96547_, left, 46, panelWidth - 20, 18,
                Component.m_237113_("搜索食物（中英文皆可）"));
        this.creativeInput.m_94199_(64);
        this.creativeInput.m_94144_(this.creativeQuery == null ? "" : this.creativeQuery);
        this.creativeInput.m_94151_(s -> {
            this.creativeQuery = s;
            this.rebuildFoodCreative();
        });
        this.m_142416_(this.creativeInput);
        int gridTop = 66;
        int gridBottom = gridTop + gridRowsNow * GRID_CELL;
        this.gridRows = gridRowsNow;
        this.rebuildFoodCreative();
        int pageY = gridBottom + 2;
        if (this.creativePage > 0) {
            this.m_142416_(Button.m_253074_(Component.m_237113_("\u00a77\u25c0"), b -> {
                this.creativePage--;
                this.m_7856_();
            }).m_252987_(left, pageY, 20, 16).m_253136_());
        }
        if (this.creativePage < this.creativePages() - 1) {
            this.m_142416_(Button.m_253074_(Component.m_237113_("\u00a77\u25b6"), b -> {
                this.creativePage++;
                this.m_7856_();
            }).m_252987_(left + 24, pageY, 20, 16).m_253136_());
        }
        int inputY = gridBottom + 24;
        this.foodInput = new EditBox(this.f_96547_, left, inputY, panelWidth - 116, 18,
                Component.m_237113_("列为不能吃"));
        this.foodInput.m_94199_(64);
        this.foodInput.m_257771_(Component.m_237113_("minecraft:rotten_flesh"));
        this.m_142416_(this.foodInput);
        this.m_142416_(Button.m_253074_(Component.m_237113_("列为不能吃"), b -> this.addFoodBlacklist())
                .m_252987_(left + panelWidth - 96, inputY, 80, 18).m_253136_());
        int listTop = inputY + 24;
            // 实测八百一十七续：列表下界封在「底部汇总行上沿 h-58」之上——旧版 h-36 会让列表底
            // 压住画在 h-50 的汇总行（玩家截图：汇总文字压在「移出」那一行上）
            int listH = Math.max(0, Math.min(h - 58 - listTop, h - listTop - 36));
        this.foodList = new FoodList(this.f_96547_, left, listTop, panelWidth - 20, listH);
        this.foodList.m_93507_(left);
        this.m_142416_(this.foodList);
        this.m_142416_(Button.m_253074_(Component.m_237113_("\u2190 返回参数"), b -> {
            this.foodTable = false;
                this.waterTable = false;
            this.m_7856_();
        }).m_252987_(12, h - 34, 100, 20).m_253136_());
        this.bottomButtons(w, h, cx);
    }

    /**
     * 实测五百七十三：喂水白名单子页——顶部搜索框 + 物品网格（点击图标切换"能不能喂"）
     * + 底部白名单列表（每行「不喂了」一键移出）。交互与投喂食物勾选子页同款。
     */
    private void waterTableButtons(int w, int h, int cx) {
        int panelLeft = Math.max(8, cx - 280);
        int panelWidth = Math.min(560, w - 16);
        int left = panelLeft + 10;
        int gridRowsNow = this.gridRowsFor(h);
        this.creativeInput = new EditBox(this.f_96547_, left, 46, panelWidth - 20, 18,
                Component.m_237113_("搜索饮品（中英文皆可）"));
        this.creativeInput.m_94199_(64);
        this.creativeInput.m_94144_(this.creativeQuery == null ? "" : this.creativeQuery);
        this.creativeInput.m_94151_(s -> {
            this.creativeQuery = s;
            this.rebuildWaterCreative();
        });
        this.m_142416_(this.creativeInput);
        int gridTop = 66;
        int gridBottom = gridTop + gridRowsNow * GRID_CELL;
        this.gridRows = gridRowsNow;
        this.rebuildWaterCreative();
        int pageY = gridBottom + 2;
        if (this.creativePage > 0) {
            this.m_142416_(Button.m_253074_(Component.m_237113_("\u00a77\u25c0"), b -> {
                this.creativePage--;
                this.m_7856_();
            }).m_252987_(left, pageY, 20, 16).m_253136_());
        }
        if (this.creativePage < this.creativePages() - 1) {
            this.m_142416_(Button.m_253074_(Component.m_237113_("\u00a77\u25b6"), b -> {
                this.creativePage++;
                this.m_7856_();
            }).m_252987_(left + 24, pageY, 20, 16).m_253136_());
        }
        int inputY = gridBottom + 24;
        this.waterInput = new EditBox(this.f_96547_, left, inputY, panelWidth - 116, 18,
                Component.m_237113_("填注册名加进白名单"));
        this.waterInput.m_94199_(64);
        this.waterInput.m_257771_(Component.m_237113_("thirst:terracotta_water_bowl"));
        this.m_142416_(this.waterInput);
        this.m_142416_(Button.m_253074_(Component.m_237113_("加进白名单"), b -> this.addWaterWhitelist())
                .m_252987_(left + panelWidth - 96, inputY, 80, 18).m_253136_());
        int listTop = inputY + 24;
            // 实测八百一十七续：列表下界封在「底部汇总行上沿 h-58」之上——旧版 h-36 会让列表底
            // 压住画在 h-50 的汇总行（玩家截图：汇总文字压在「移出」那一行上）
            int listH = Math.max(0, Math.min(h - 58 - listTop, h - listTop - 36));
        this.waterList = new WaterList(this.f_96547_, left, listTop, panelWidth - 20, listH);
        this.waterList.m_93507_(left);
        this.m_142416_(this.waterList);
        this.m_142416_(Button.m_253074_(Component.m_237113_("\u2190 返回参数"), b -> {
            this.waterTable = false;
            this.m_7856_();
        }).m_252987_(12, h - 34, 100, 20).m_253136_());
        this.bottomButtons(w, h, cx);
    }

    /**
     * 实测八百一十七：支援物品分类子页——顶部「通用 / 亡灵」两档切换 + 搜索框 + 物品网格
     * （点图标循环切换 自动·对策·增益·回复·禁用）+ 底部已分类名单。与「投喂食物勾选」同款。
     */
    private void supportTableButtons(int w, int h, int cx) {
        int panelLeft = Math.max(8, cx - 280);
        int panelWidth = Math.min(560, w - 16);
        int left = panelLeft + 10;
        int gridRowsNow = this.gridRowsFor(h);
        int tgY = 24;
        String[] modeNames = {"通用支援", "亡灵支援"};
        for (int i = 0; i < modeNames.length; i++) {
            final int mi = i;
            this.m_142416_(Button.m_253074_(
                            Component.m_237113_((this.supportMode == mi ? "\u00a7e\u25cf " : "\u00a77")
                                    + modeNames[i]),
                            b -> {
                                this.supportMode = mi;
                                this.creativePage = 0;
                                this.m_7856_();
                            })
                    .m_252987_(left + i * 100, tgY, 96, 18).m_253136_());
        }
        this.creativeInput = new EditBox(this.f_96547_, left, 46, panelWidth - 20, 18,
                Component.m_237113_("搜索支援物品（中英文皆可）"));
        this.creativeInput.m_94199_(64);
        this.creativeInput.m_94144_(this.creativeQuery == null ? "" : this.creativeQuery);
        this.creativeInput.m_94151_(s -> {
            this.creativeQuery = s;
            this.rebuildSupportCreative();
        });
        this.m_142416_(this.creativeInput);
        int gridTop = 66;
        int gridBottom = gridTop + gridRowsNow * GRID_CELL;
        this.gridRows = gridRowsNow;
        this.rebuildSupportCreative();
        int pageY = gridBottom + 2;
        if (this.creativePage > 0) {
            this.m_142416_(Button.m_253074_(Component.m_237113_("\u00a77\u25c0"), b -> {
                this.creativePage--;
                this.m_7856_();
            }).m_252987_(left, pageY, 20, 16).m_253136_());
        }
        if (this.creativePage < this.creativePages() - 1) {
            this.m_142416_(Button.m_253074_(Component.m_237113_("\u00a77\u25b6"), b -> {
                this.creativePage++;
                this.m_7856_();
            }).m_252987_(left + 24, pageY, 20, 16).m_253136_());
        }
        int inputY = gridBottom + 24;
        this.supportInput = new EditBox(this.f_96547_, left, inputY, panelWidth - 116, 18,
                Component.m_237113_("填注册名加进当前档（默认禁用）"));
        this.supportInput.m_94199_(64);
        this.supportInput.m_257771_(Component.m_237113_("minecraft:splash_potion|minecraft:healing"));
        this.m_142416_(this.supportInput);
        this.m_142416_(Button.m_253074_(Component.m_237113_("加进当前档"), b -> this.addSupportEntry())
                .m_252987_(left + panelWidth - 96, inputY, 80, 18).m_253136_());
        int listTop = inputY + 24;
            // 实测八百一十七续：列表下界封在「底部汇总行上沿 h-58」之上——旧版 h-36 会让列表底
            // 压住画在 h-50 的汇总行（玩家截图：汇总文字压在「移出」那一行上）
            // 实测八百一十七续：支援页多一行固定图例（h-62）→ 名单下界再上提到 h-74
            int listH = Math.max(0, Math.min(h - 74 - listTop, h - listTop - 36));
        this.supportList = new SupportList(this.f_96547_, left, listTop, panelWidth - 20, listH);
        this.supportList.m_93507_(left);
        this.m_142416_(this.supportList);
        this.m_142416_(Button.m_253074_(Component.m_237113_("\u2190 返回参数"), b -> {
            this.supportTable = false;
            this.m_7856_();
        }).m_252987_(12, h - 34, 100, 20).m_253136_());
        this.bottomButtons(w, h, cx);
    }

    /** 实测五百七十三：是否"可以喂"（在白名单里 = 可以喂） */
    private boolean isWaterChecked(String key) {
        List<? extends String> wl = MaidSmartConfig.AID_DRINK_WHITELIST.get();
        if (wl.contains(key)) {
            return true;
        }
        return wl.contains(com.maidsmart.action.ItemUses.baseId(key));
    }

    /** 当前白名单数量（入口按钮上的「可喂 N / 候选 M」用） */
    private int countDrinkables() {
        try {
            return MaidSmartConfig.AID_DRINK_WHITELIST.get().size();
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("PromaidConfigScreen", ignored);
            return 0;
        }
    }

    /** 候选总数（网格里的饮品件数） */
    private int countWaterCandidates() {
        try {
            ensureWaterCache();
            return waterCache.size();
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("PromaidConfigScreen", ignored);
            return 0;
        }
    }

    /** 切换某物品"能不能喂"（写回 aidDrinkWhitelist 并刷新底部列表） */
    private void toggleWaterChecked(String key) {
        List<String> list = new ArrayList<>(MaidSmartConfig.AID_DRINK_WHITELIST.get());
        if (this.isWaterChecked(key)) {
            // 当前可以喂 → 连同可能存在的"裸 id（任意次数）"条目一起移出白名单
            list.remove(key);
            list.remove(com.maidsmart.action.ItemUses.baseId(key));
        } else {
            // 只把**这一档**加进白名单（例如"只喂满的水壶"）
            list.add(key);
        }
        MaidSmartConfig.AID_DRINK_WHITELIST.set(list);
        if (this.waterList != null) {
            this.waterList.rebuild();
        }
    }

    /** 手动输入 id 加进白名单（支持省略 minecraft: 前缀） */
    private void addWaterWhitelist() {
        if (this.waterInput == null) {
            return;
        }
        String text = this.waterInput.m_94155_().trim();
        if (text.isEmpty()) {
            return;
        }
        if (!text.contains(":")) {
            text = "minecraft:" + text;
        }
        net.minecraft.world.item.Item item = net.minecraftforge.registries.ForgeRegistries.ITEMS
                .getValue(net.minecraft.resources.ResourceLocation.parse(text));
        if (item == null) {
            return;
        }
        net.minecraft.resources.ResourceLocation key =
                net.minecraftforge.registries.ForgeRegistries.ITEMS.getKey(item);
        if (key == null) {
            return;
        }
        String id = key.toString();
        List<String> list = new ArrayList<>(MaidSmartConfig.AID_DRINK_WHITELIST.get());
        if (!list.contains(id)) {
            list.add(id);
            MaidSmartConfig.AID_DRINK_WHITELIST.set(list);
        }
        this.waterInput.m_94144_("");
        if (this.waterList != null) {
            this.waterList.rebuild();
        }
    }

    /** 从白名单移除（底部列表「不喂了」按钮） */
    private void removeWaterWhitelist(String id) {
        List<String> list = new ArrayList<>(MaidSmartConfig.AID_DRINK_WHITELIST.get());
        list.remove(id);
        MaidSmartConfig.AID_DRINK_WHITELIST.set(list);
        if (this.waterList != null) {
            this.waterList.rebuild();
        }
    }

    /** 重建饮品网格（搜索过滤；与 rebuildFoodCreative 同款，只过滤内存缓存） */
    private void rebuildWaterCreative() {
        this.creativeItems.clear();
        ensureWaterCache();
        String q = this.creativeQuery == null ? "" : this.creativeQuery.trim().toLowerCase(java.util.Locale.ROOT);
        for (String[] e : waterCache) {
            String id = e[0];
            String cn = e[1];
            String label = e.length > 3 ? e[3] : "";
            if (!q.isEmpty() && !(id.contains(q) || (cn != null && cn.contains(q))
                    || (label != null && label.contains(q)))) {
                continue;
            }
            net.minecraft.world.item.Item item = net.minecraftforge.registries.ForgeRegistries.ITEMS
                    .getValue(new net.minecraft.resources.ResourceLocation(id));
            if (item == null) {
                continue;
            }
            // 实测五百八十：该行是"剩余 N 次"那一档 → 造一个损耗对得上的栈（耐久条一眼可辨）
            int rem = com.maidsmart.action.ItemUses.usesOfKey(e.length > 2 ? e[2] : id);
            this.creativeItems.add(rem < 0
                    ? new net.minecraft.world.item.ItemStack(item)
                    : com.maidsmart.action.ItemUses.stackFor(item, rem));
        }
        this.creativePage = Math.min(this.creativePage, Math.max(0, this.creativePages() - 1));
    }

    /** 是否"能吃"（不在黑名单里即能吃） */
    private boolean isFoodChecked(String key) {
        List<? extends String> bl = MaidSmartConfig.AID_FOOD_BLACKLIST.get();
        if (bl.contains(key)) {
            return false;
        }
        // 裸 id 条目（旧配置 / 手动输入）= 该物品任意剩余次数都按条目处理
        return !bl.contains(com.maidsmart.action.ItemUses.baseId(key));
    }

    /** 当前可喂食物总数（子页按钮上的 "(可喂 N / 不能吃 M)" 用） */
    private int countFeedableFoods() {
        try {
            ensureFoodCache();
            int n = 0;
            for (String[] e : foodCache) {
                if (!this.isFoodChecked(e.length > 2 ? e[2] : e[0])) {
                    continue;
                }
                n++;
            }
            return n;
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("PromaidConfigScreen", ignored);
            return 0;
        }
    }

    /** 切换某物品"能不能吃"（写回 aidFoodBlacklist 并刷新底部列表） */
    private void toggleFoodChecked(String key) {
        List<String> list = new ArrayList<>(MaidSmartConfig.AID_FOOD_BLACKLIST.get());
        if (this.isFoodChecked(key)) {
            // 当前能吃 → 把**这一档**列入"不能吃"（其余档位照旧能吃）
            list.add(key);
        } else {
            // 当前不能吃 → 一次点回能吃：连同可能存在的"裸 id（任意次数）"条目一起清掉，
            // 否则点了没反应（裸条目仍然盖着这一档）
            list.remove(key);
            list.remove(com.maidsmart.action.ItemUses.baseId(key));
        }
        MaidSmartConfig.AID_FOOD_BLACKLIST.set(list);
        if (this.foodList != null) {
            this.foodList.rebuild();
        }
    }

    /** 手动输入 id 列入"不能吃"（支持省略 minecraft: 前缀） */
    private void addFoodBlacklist() {
        if (this.foodInput == null) {
            return;
        }
        String text = this.foodInput.m_94155_().trim();
        if (text.isEmpty()) {
            return;
        }
        if (!text.contains(":")) {
            text = "minecraft:" + text;
        }
        net.minecraft.world.item.Item item = net.minecraftforge.registries.ForgeRegistries.ITEMS
                .getValue(net.minecraft.resources.ResourceLocation.parse(text));
        if (item == null) {
            return;
        }
        net.minecraft.resources.ResourceLocation key =
                net.minecraftforge.registries.ForgeRegistries.ITEMS.getKey(item);
        if (key == null) {
            return;
        }
        String id = key.toString();
        List<String> list = new ArrayList<>(MaidSmartConfig.AID_FOOD_BLACKLIST.get());
        if (!list.contains(id)) {
            list.add(id);
            MaidSmartConfig.AID_FOOD_BLACKLIST.set(list);
        }
        this.foodInput.m_94144_("");
        if (this.foodList != null) {
            this.foodList.rebuild();
        }
    }

    /** 从"不能吃"移回能吃（底部列表每行的「允许吃」按钮） */
    private void removeFoodBlacklist(String id) {
        List<String> list = new ArrayList<>(MaidSmartConfig.AID_FOOD_BLACKLIST.get());
        list.remove(id);
        MaidSmartConfig.AID_FOOD_BLACKLIST.set(list);
        if (this.foodList != null) {
            this.foodList.rebuild();
        }
    }

    /** 重建食物网格（搜索过滤；与 rebuildCreative 同款，只过滤内存缓存） */
    private void rebuildFoodCreative() {
        this.creativeItems.clear();
        ensureFoodCache();
        String q = this.creativeQuery == null ? "" : this.creativeQuery.trim().toLowerCase(java.util.Locale.ROOT);
        for (String[] e : foodCache) {
            String id = e[0];
            String cn = e[1];
            String label = e.length > 3 ? e[3] : "";
            if (!q.isEmpty() && !(id.contains(q) || (cn != null && cn.contains(q))
                    || (label != null && label.contains(q)))) {
                continue;
            }
            net.minecraft.world.item.Item item = net.minecraftforge.registries.ForgeRegistries.ITEMS
                    .getValue(new net.minecraft.resources.ResourceLocation(id));
            if (item == null) {
                continue;
            }
            // 实测五百八十：该行是"剩余 N 次"那一档 → 造一个损耗对得上的栈
            int rem = com.maidsmart.action.ItemUses.usesOfKey(e.length > 2 ? e[2] : id);
            this.creativeItems.add(rem < 0
                    ? new net.minecraft.world.item.ItemStack(item)
                    : com.maidsmart.action.ItemUses.stackFor(item, rem));
        }
        this.creativePage = Math.min(this.creativePage, Math.max(0, this.creativePages() - 1));
    }

    // ================= 实测八百一十七：支援物品分类子页（通用 / 亡灵） =================

    /**
     * 支援品候选缓存：{裸物品id, 中文名, 记账键, 后缀, 药水id}。
     * <p>候选 = {@link com.maidsmart.combat.MaidSupportPolicy#candidate}：药水（**每个注册药水
     * 各一行**：喷溅/滞留/饮用 × healing/regeneration/…）、金苹果/附魔金苹果、牛奶桶、蜂蜜瓶、
     * 自带效果的普通食物（耐久容器按剩余次数分行）。药水一行 = 一个"物品栈 + Potion 效果"，
     * 所以要把所有注册药水铺开。
     */
    private static void ensureSupportCache() {
        long now = System.currentTimeMillis();
        if (supportCache != null && now - supportCacheBuilt < 60_000L) {
            return;
        }
        supportCache = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        // ① 药水：三个形态 × 每个注册药水（物品按注册名取，避免写死 SRG 字段名）
        String[] potionItemIds = {"minecraft:potion", "minecraft:splash_potion",
                "minecraft:lingering_potion"};
        for (String pii : potionItemIds) {
            net.minecraft.world.item.Item pi = net.minecraftforge.registries.ForgeRegistries.ITEMS
                    .getValue(new net.minecraft.resources.ResourceLocation(pii));
            if (pi == null) {
                continue;
            }
            for (net.minecraft.world.item.alchemy.Potion potion :
                    net.minecraftforge.registries.ForgeRegistries.POTIONS) {
                try {
                    net.minecraft.world.item.ItemStack stack = new net.minecraft.world.item.ItemStack(pi);
                    net.minecraft.world.item.alchemy.PotionUtils.m_43549_(stack, potion);
                    if (!com.maidsmart.combat.MaidSupportPolicy.potionItem(stack)) {
                        continue;
                    }
                    // 只列真有效果的药水（water/awkward/mundane/thick 这类空药水不上表）
                    if (net.minecraft.world.item.alchemy.PotionUtils.m_43579_(stack) != null
                            && net.minecraft.world.item.alchemy.PotionUtils.m_43579_(stack)
                                    .m_43488_().isEmpty()) {
                        continue;
                    }
                    String key = com.maidsmart.combat.MaidSupportPolicy.key(stack);
                    if (key.isEmpty() || !seen.add(key)) {
                        continue;
                    }
                    String cn = stack.m_41786_().getString();
                    String eff = com.maidsmart.combat.MaidSupportPolicy.potionId(stack);
                    supportCache.add(new String[]{
                            com.maidsmart.combat.MaidSupportPolicy.baseId(stack), cn, key, "", eff});
                } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("PromaidConfigScreen", ignored);
                }
            }
        }
        // ② 其它候选（金苹果/牛奶/蜂蜜/带效果食物）
        for (net.minecraft.world.item.Item item : net.minecraftforge.registries.ForgeRegistries.ITEMS) {
            net.minecraft.world.item.ItemStack stack;
            try {
                stack = new net.minecraft.world.item.ItemStack(item);
                if (stack.m_41619_()) {
                    continue;
                }
                if (!com.maidsmart.combat.MaidSupportPolicy.candidate(stack)
                        || com.maidsmart.combat.MaidSupportPolicy.potionItem(stack)) {
                    continue; // 药水已在上一步按效果铺开
                }
            } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("PromaidConfigScreen", ignored);
                continue;
            }
            String cn = "";
            try {
                cn = stack.m_41786_().getString();
            } catch (Exception ignored) { com.maidsmart.diag.Diag.swallow("PromaidConfigScreen", ignored);
            }
            // 耐久容器按剩余次数分行（与投喂食物同口径）
            net.minecraft.world.item.ItemStack probe = stack;
            int max = com.maidsmart.action.ItemUses.total(probe);
            if (max <= 0) {
                String key = com.maidsmart.combat.MaidSupportPolicy.key(stack);
                if (seen.add(key)) {
                    supportCache.add(new String[]{com.maidsmart.combat.MaidSupportPolicy.baseId(stack),
                            cn, key, "", ""});
                }
                continue;
            }
            for (int rem = max; rem >= 1; rem--) {
                String k = com.maidsmart.combat.MaidSupportPolicy.baseId(stack)
                        + com.maidsmart.action.ItemUses.SEP + rem;
                if (seen.add(k)) {
                    supportCache.add(new String[]{com.maidsmart.combat.MaidSupportPolicy.baseId(stack),
                            cn, k, "剩余 " + rem + "/" + max + " 次", ""});
                }
            }
        }
        // ③ 表里已有的条目兜底（物品被移除/改名时也要能取消）
        try {
            for (String e : com.maidsmart.combat.MaidSupportPolicy.allConfiguredEntries()) {
                String lhs = com.maidsmart.combat.MaidSupportPolicy.entryLhs(e);
                if (lhs == null || lhs.isEmpty() || !seen.add(lhs)) {
                    continue;
                }
                String base = com.maidsmart.action.ItemUses.baseId(lhs);
                supportCache.add(new String[]{base, "", lhs, "", ""});
            }
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("PromaidConfigScreen", ignored);
        }
        supportCacheBuilt = now;
    }

    /** 当前这一档（通用/亡灵）表里的条目 */
    private List<String> supportEntriesNow() {
        try {
            return this.supportMode == 1
                    ? new ArrayList<>(MaidSmartConfig.AID_SUPPORT_CATEGORY_UNDEAD.get())
                    : new ArrayList<>(MaidSmartConfig.AID_SUPPORT_CATEGORY.get());
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("PromaidConfigScreen", ignored);
            return new ArrayList<>();
        }
    }

    /** 该栈在当前档的有效分类 */
    private com.maidsmart.combat.MaidSupportPolicy.Cat supportCat(String key) {
        net.minecraft.world.item.ItemStack stack = supportStackOf(key);
        if (stack == null) {
            return com.maidsmart.combat.MaidSupportPolicy.Cat.DEFAULT;
        }
        return com.maidsmart.combat.MaidSupportPolicy.category(stack, this.supportMode == 1);
    }

    /** 由记账键还原一个可判定的栈（药水键 = 物品id|药水id） */
    private net.minecraft.world.item.ItemStack supportStackOf(String key) {
        try {
            String lhs = com.maidsmart.combat.MaidSupportPolicy.entryLhs(key);
            int bar = lhs == null ? -1 : lhs.indexOf(com.maidsmart.combat.MaidSupportPolicy.POTION_SEP);
            String base = bar >= 0 ? lhs.substring(0, bar) : com.maidsmart.action.ItemUses.baseId(lhs == null ? key : lhs);
            net.minecraft.world.item.Item item = net.minecraftforge.registries.ForgeRegistries.ITEMS
                    .getValue(new net.minecraft.resources.ResourceLocation(base));
            if (item == null) {
                return null;
            }
            net.minecraft.world.item.ItemStack stack = new net.minecraft.world.item.ItemStack(item);
            if (bar >= 0) {
                net.minecraft.world.item.alchemy.Potion potion =
                        net.minecraftforge.registries.ForgeRegistries.POTIONS
                                .getValue(new net.minecraft.resources.ResourceLocation(lhs.substring(bar + 1)));
                if (potion == null) {
                    return null;
                }
                net.minecraft.world.item.alchemy.PotionUtils.m_43549_(stack, potion);
            }
            return stack;
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("PromaidConfigScreen", ignored);
            return null;
        }
    }

    /** 写回分类（自动 = 删条目；其它 = 覆盖该键的条目） */
    private void setSupportCat(String key, com.maidsmart.combat.MaidSupportPolicy.Cat cat) {
        List<String> list = this.supportEntriesNow();
        list.removeIf(e -> {
            String lhs = com.maidsmart.combat.MaidSupportPolicy.entryLhs(e);
            return lhs != null && lhs.equalsIgnoreCase(key);
        });
        if (cat != com.maidsmart.combat.MaidSupportPolicy.Cat.DEFAULT) {
            list.add(key + "=" + com.maidsmart.combat.MaidSupportPolicy.catName(cat));
        }
        if (this.supportMode == 1) {
            MaidSmartConfig.AID_SUPPORT_CATEGORY_UNDEAD.set(list);
        } else {
            MaidSmartConfig.AID_SUPPORT_CATEGORY.set(list);
        }
        if (this.supportList != null) {
            this.supportList.rebuild();
        }
    }

    /** 点一格 → 切到下一个分类（自动·对策·增益·回复·禁用·自动…） */
    private void cycleSupportCat(String key) {
        com.maidsmart.combat.MaidSupportPolicy.Cat cur =
                com.maidsmart.combat.MaidSupportPolicy.override(supportStackOf(key), this.supportMode == 1);
        if (cur == null) {
            cur = com.maidsmart.combat.MaidSupportPolicy.Cat.DEFAULT;
        }
        this.setSupportCat(key, com.maidsmart.combat.MaidSupportPolicy.next(cur));
    }

    /** 从表里整条移除（底部名单行的按钮） */
    private void removeSupportEntry(String key) {
        this.setSupportCat(key, com.maidsmart.combat.MaidSupportPolicy.Cat.DEFAULT);
    }

    /** 手动输入 id 写进当前档（默认"禁用"，玩家再点图标改类） */
    private void addSupportEntry() {
        if (this.supportInput == null) {
            return;
        }
        String text = this.supportInput.m_94155_().trim();
        if (text.isEmpty()) {
            return;
        }
        if (!text.contains(":")) {
            text = "minecraft:" + text;
        }
        // 药水可写 物品id|药水id；非药水就写物品 id
        String key = text.contains(com.maidsmart.combat.MaidSupportPolicy.POTION_SEP + "")
                ? text : text;
        List<String> list = this.supportEntriesNow();
        list.add(key + "=" + com.maidsmart.combat.MaidSupportPolicy.catName(
                com.maidsmart.combat.MaidSupportPolicy.Cat.OFF));
        if (this.supportMode == 1) {
            MaidSmartConfig.AID_SUPPORT_CATEGORY_UNDEAD.set(list);
        } else {
            MaidSmartConfig.AID_SUPPORT_CATEGORY.set(list);
        }
        this.supportInput.m_94144_("");
        if (this.supportList != null) {
            this.supportList.rebuild();
        }
    }

    /** 重建支援品网格（搜索过滤内存缓存） */
    private void rebuildSupportCreative() {
        this.creativeItems.clear();
        ensureSupportCache();
        String q = this.creativeQuery == null ? ""
                : this.creativeQuery.trim().toLowerCase(java.util.Locale.ROOT);
        for (String[] e : supportCache) {
            String id = e[0];
            String cn = e[1];
            String key = e[2];
            String suffix = e.length > 3 ? e[3] : "";
            String eff = e.length > 4 ? e[4] : "";
            if (!q.isEmpty() && !((id != null && id.contains(q)) || (cn != null && cn.contains(q))
                    || (key != null && key.contains(q)) || (eff != null && eff.contains(q)))) {
                continue;
            }
            net.minecraft.world.item.ItemStack stack = this.supportStackOf(key);
            if (stack == null) {
                continue;
            }
            this.creativeItems.add(stack);
        }
        this.creativePage = Math.min(this.creativePage, Math.max(0, this.creativePages() - 1));
    }

    /** 当前档的表里已分类条目数（入口行摘要用） */
    private int countSupportEntries(int mode) {
        try {
            return (mode == 1 ? MaidSmartConfig.AID_SUPPORT_CATEGORY_UNDEAD.get()
                    : MaidSmartConfig.AID_SUPPORT_CATEGORY.get()).size();
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("PromaidConfigScreen", ignored);
            return 0;
        }
    }

    private void altTableButtons(int w, int h, int cx) {
        int panelLeft = Math.max(8, cx - 280);
        int panelWidth = Math.min(560, w - 16);
        int left = panelLeft + 10;
        int gridRowsNow = this.gridRowsFor(h);
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
            // 实测八百一十七续：列表下界封在「底部汇总行上沿 h-58」之上——旧版 h-36 会让列表底
            // 压住画在 h-50 的汇总行（玩家截图：汇总文字压在「移出」那一行上）
            int listH = Math.max(0, Math.min(h - 58 - listTop, h - listTop - 36));
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
                                    new com.maidsmart.build.BlueprintBookBuildPackets.OpenBookRequestPacket(2));
                            this.m_7379_(); // 关配置面板（手册包到达后自动打开）
                        })
                .m_252987_(w - 148, h - 34, 132, 20).m_253136_());
        this.bottomButtons(w, h, cx);
    }

    /**
     * v1.3.0(beta) 实测六百八十：搭方块禁用名单子页（搭路板块）。
     *
     * 布局与交互照搬替代品/投喂子页（同一套 creativeInput 搜索框 + creativeItems 网格 + 翻页）：
     * 顶部一行是【默认规则开关】与【清空名单】，中间网格点一下切换该方块的禁止/允许，底部列表只列
     * 【玩家自己改过的】条目（点「移除」回到默认规则）。渲染与点击两处状态都问
     * {@link com.maidsmart.tool.MaidBuildBlockFilter#isBlacklistedBuildBlock}——**与女仆实际取材
     * 用的是同一个方法**，所以面板上看到的红绿框就是她真会/真不会用的，不会出现"面板说允许、
     * 她仍然不搭"这种口径分裂。
     */
    private void buildBlackTableButtons(int w, int h, int cx) {
        int panelLeft = Math.max(8, cx - 280);
        int panelWidth = Math.min(560, w - 16);
        int left = panelLeft + 10;
        int gridRowsNow = this.gridRowsFor(h);
        int tgY = 24;
        // 默认规则开关（本页的主开关；与其它板块的 BoolRow 同义，只是这里用按钮切换）
        boolean onlyNatural = MaidSmartConfig.BRIDGE_BUILD_ONLY_NATURAL.get();
        this.m_142416_(Button.m_253074_(Component.m_237113_((onlyNatural ? "\u00a7a\u25cf " : "\u00a78\u25cb ")
                                + "默认只许原版天然方块"),
                        b -> {
                            MaidSmartConfig.BRIDGE_BUILD_ONLY_NATURAL.set(
                                    !MaidSmartConfig.BRIDGE_BUILD_ONLY_NATURAL.get());
                            this.m_7856_();
                        })
                .m_252987_(left, tgY, 210, 18).m_253136_());
        // 清空两张显式名单（回到"完全按默认规则"的状态）
        this.m_142416_(Button.m_253074_(Component.m_237113_("清空名单"),
                        b -> {
                            MaidSmartConfig.BRIDGE_BUILD_FORBIDDEN.set(new ArrayList<String>());
                            MaidSmartConfig.BRIDGE_BUILD_ALLOWED.set(new ArrayList<String>());
                            this.m_7856_();
                        })
                .m_252987_(left + 218, tgY, 80, 18).m_253136_());
        this.creativeInput = new EditBox(this.f_96547_, left, 46, panelWidth - 20, 18,
                Component.m_237113_("搜索方块（中英文皆可）"));
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
            this.m_142416_(Button.m_253074_(Component.m_237113_("\u00a77\u25c0"),
                            b -> {
                                this.creativePage--;
                                this.m_7856_();
                            })
                    .m_252987_(cx - 40, py, 20, 16).m_253136_());
        }
        if (this.creativePage < this.creativePages() - 1) {
            this.m_142416_(Button.m_253074_(Component.m_237113_("\u00a77\u25b6"),
                            b -> {
                                this.creativePage++;
                                this.m_7856_();
                            })
                    .m_252987_(cx + 20, py, 20, 16).m_253136_());
        }
        int listTop = gridBottom + 24;
            // 实测八百一十七续：列表下界封在「底部汇总行上沿 h-58」之上——旧版 h-36 会让列表底
            // 压住画在 h-50 的汇总行（玩家截图：汇总文字压在「移出」那一行上）
            int listH = Math.max(0, Math.min(h - 58 - listTop, h - listTop - 36));
        this.buildBlackList = new BuildBlackList(this.f_96547_, panelLeft + 10, listTop,
                panelWidth - 20, listH);
        this.buildBlackList.m_93507_(panelLeft + 10);
        this.m_142416_(this.buildBlackList);
        this.m_142416_(Button.m_253074_(Component.m_237113_("\u2190 返回参数"),
                        b -> {
                            this.buildBlackTable = false;
                            this.m_7856_();
                        })
                .m_252987_(12, h - 34, 100, 20).m_253136_());
        this.bottomButtons(w, h, cx);
    }

    /* ==================== 实测七百七十二：超越维度规则名单子页 ==================== */

    /**
     * 超越维度（BeyondDimensions）规则名单子页。
     *
     * <p>布局与交互照搬替代品/喂水子页（同一套 creativeInput 搜索框 + creativeItems 网格 + 翻页）：
     * 顶部四个模式按钮（一定搬 / 一定不搬 / 保留N个 / 至少留N个），中间点物品图标 = 在当前模式下
     * 加入/移出，下方一个 EditBox 填 N（并可直接手输 {@code tag:c:ores} 这类标签写法，网格选不到标签），
     * 底部列表列出**当前模式**的规则（每行「移除」）。
     *
     * <p>【为什么全程走网络】本子页改的是**服务端**的 {@code config/promaid_bd_rules.json}；面板是
     * 纯客户端 Screen，直接写只会写到客户端自己的目录。所以每次改动都发 C2S 包
     * （{@link com.maidsmart.bd.MaidBdNetworking}），服务端 {@code hasPermission(2)} 落盘后回推状态，
     * 本子页只渲染客户端缓存。命令 {@code /maid_smart bd_rule *} 与本子页共用同一套落盘。
     */
    private void bdRulesButtons(int w, int h, int cx) {
        int panelLeft = Math.max(8, cx - 280);
        int panelWidth = Math.min(560, w - 16);
        int left = panelLeft + 10;
        int gridRowsNow = this.gridRowsFor(h);
        int tgY = 24;
        String[] modeNames = {"一定搬", "一定不搬", "保留N个", "至少留N个"};
        for (int i = 0; i < modeNames.length; i++) {
            final int mi = i;
            this.m_142416_(Button.m_253074_(
                            Component.m_237113_((this.bdRuleMode == mi ? "\u00a7e\u25cf " : "\u00a77") + modeNames[i]),
                            b -> {
                                this.bdRuleMode = mi;
                                this.creativePage = 0;
                                com.maidsmart.bd.MaidBdNetworking.requestRuleState();
                                this.m_7856_();
                            })
                    .m_252987_(left + i * 104, tgY, 100, 18).m_253136_());
        }
        // 注意：这里**不**发 requestRuleState——否则"回推状态→重建→再请求"会变成包循环。
        // 请求只在进入子页与切换模式两处发（见调用点）。
        // 搜索框（复用创造物品面板过滤）
        this.creativeInput = new EditBox(this.f_96547_, left, 46, panelWidth - 20, 18,
                Component.m_237113_("搜索物品（中英文皆可）"));
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
            this.m_142416_(Button.m_253074_(Component.m_237113_("\u00a77\u25c0"),
                            b -> {
                                this.creativePage--;
                                this.m_7856_();
                            })
                    .m_252987_(cx - 40, py, 20, 16).m_253136_());
        }
        if (this.creativePage < this.creativePages() - 1) {
            this.m_142416_(Button.m_253074_(Component.m_237113_("\u00a77\u25b6"),
                            b -> {
                                this.creativePage++;
                                this.m_7856_();
                            })
                    .m_252987_(cx + 20, py, 20, 16).m_253136_());
        }
        // 手输添加（支持裸 id / item: / tag:）；"保留N个 / 至少留N个"模式下旁边多一个 N 输入框
        boolean hasN = this.bdRuleMode >= 2;
        int inputY = gridBottom + 24;
        int nBoxW = hasN ? 46 : 0;
        this.bdInput = new EditBox(this.f_96547_, left, inputY, panelWidth - 116 - nBoxW, 18,
                Component.m_237113_("条目"));
        this.bdInput.m_94199_(96);
        this.bdInput.m_257771_(Component.m_237113_("minecraft:coal 或 tag:c:ores"));
        this.m_142416_(this.bdInput);
        if (hasN) {
            this.bdNInput = new EditBox(this.f_96547_, left + panelWidth - 116 - nBoxW + 2, inputY, nBoxW - 2, 18,
                    Component.m_237113_("N"));
            this.bdNInput.m_94199_(7);
            this.bdNInput.m_94144_("16");
            this.bdNInput.m_257771_(Component.m_237113_("16"));
            this.m_142416_(this.bdNInput);
        } else {
            this.bdNInput = null;
        }
        this.m_142416_(Button.m_253074_(
                        Component.m_237113_(hasN ? "加进规则" : "加入"),
                        b -> this.addBdRuleFromInput())
                .m_252987_(left + panelWidth - 106, inputY, 90, 18).m_253136_());
        int listTop = inputY + 24;
        int listH = Math.max(24, Math.min((h - 78) - listTop - 4, h - listTop - 36));
        this.bdRuleList = new BdRuleList(this.f_96547_, left, listTop, panelWidth - 20, listH);
        this.bdRuleList.m_93507_(left);
        this.m_142416_(this.bdRuleList);
        this.m_142416_(Button.m_253074_(Component.m_237113_("\u2190 返回参数"),
                        b -> {
                            this.bdRules = false;
                            this.m_7856_();
                        })
                .m_252987_(12, h - 34, 100, 20).m_253136_());
        this.bottomButtons(w, h, cx);
    }

    /**
     * 当前模式下，某物品是否已在名单里（图标高亮用）。
     *
     * <p>【只读客户端缓存，绝不碰 MaidBdRules】面板是客户端；{@code MaidBdRules} 读的是
     * **客户端自己目录**里的 json（与服务端那份可能不同）。高亮必须反映"服务端真会用的那份"，
     * 所以一律查 {@link com.maidsmart.bd.MaidBdNetworking} 下推的缓存。
     * 标签条目（{@code tag:...}）看不出单个物品是否被覆盖，这类就按"不在此名单"画（可接受）。
     */
    private boolean isInBdRule(String id) {
        if (id == null || id.isEmpty()) {
            return false;
        }
        switch (this.bdRuleMode) {
            case 1:
                return com.maidsmart.bd.MaidBdNetworking.KEEP.contains(id);
            case 2:
                return bdCacheHasMatch(com.maidsmart.bd.MaidBdNetworking.KEEP_N, id);
            case 3:
                return bdCacheHasMatch(com.maidsmart.bd.MaidBdNetworking.AT_LEAST, id);
            default:
                return com.maidsmart.bd.MaidBdNetworking.MOVE.contains(id);
        }
    }

    /** 缓存里 "match=count" 形式的条目是否命中该 id。 */
    private static boolean bdCacheHasMatch(List<String> cache, String id) {
        for (String e : cache) {
            int eq = e.indexOf('=');
            String match = eq > 0 ? e.substring(0, eq) : e;
            if (match.equals(id)) {
                return true;
            }
        }
        return false;
    }

    /** 渲染超越维度规则网格（与替代品子页同款：加入=绿框+勾，点击切换）。 */
    private void renderBdRulesGrid(net.minecraft.client.gui.GuiGraphics g, int mouseX, int mouseY,
                                   int w, int h, int cx) {
        String[] modeNames = {"一定搬（永远搬走）", "一定不搬（永远留着）",
                "保留 N 个（多了搬走）", "至少留 N 个（少了从网络取）"};
        String title = "\u00a7e超越维度规则——" + modeNames[Math.min(this.bdRuleMode, 3)]
                + "——点击物品图标加入/移出（再点取消）";
        g.m_280653_(this.f_96547_, Component.m_237113_(title), cx, 10, 0xFFFFFF);
        int panelLeft = Math.max(8, cx - 280);
        int panelWidth = Math.min(560, w - 16);
        int left = panelLeft + 10;
        int gridTop = GRID_TOP;
        int gridRowsNow = this.gridRowsFor(h);
        int gridBottom = gridTop + gridRowsNow * GRID_CELL;
        g.m_280509_(panelLeft + 8, gridTop - 4, panelLeft + panelWidth - 8, gridBottom, 0x80101010);
        int perPage = this.gridCols * this.gridRows;
        int start = this.creativePage * perPage;
        int end = Math.min(this.creativeItems.size(), start + perPage);
        int hoverIdx = -1;
        for (int i = start; i < end; i++) {
            int col = (i - start) % this.gridCols;
            int row = (i - start) / this.gridCols;
            int x = left + col * GRID_CELL;
            int y = gridTop + row * GRID_CELL;
            net.minecraft.world.item.ItemStack stack = this.creativeItems.get(i);
            net.minecraft.resources.ResourceLocation key =
                    net.minecraftforge.registries.ForgeRegistries.ITEMS.getKey(stack.m_41720_());
            String id = key == null ? "" : key.toString();
            if (!id.isEmpty() && this.isInBdRule(id)) {
                g.m_280509_(x - 1, y - 1, x + 17, y + 17, 0x8022CC22); // 绿框 = 已在当前名单
                g.m_280653_(this.f_96547_, Component.m_237113_("\u2714"),
                        x + 12, y + 12, 0xFFFFFF);
            }
            g.m_280480_(stack, x, y); // 物品图标
            if (mouseX >= x && mouseX < x + GRID_CELL && mouseY >= y && mouseY < y + GRID_CELL) {
                hoverIdx = i;
            }
        }
        int infoX = left + this.gridCols * GRID_CELL + 12;
        this.gridInfoX = left + this.gridCols * GRID_CELL + 12;
        int infoY = gridTop + 2;
        if (hoverIdx >= 0 && hoverIdx < this.creativeItems.size()) {
            net.minecraft.world.item.ItemStack stack = this.creativeItems.get(hoverIdx);
            net.minecraft.resources.ResourceLocation key =
                    net.minecraftforge.registries.ForgeRegistries.ITEMS.getKey(stack.m_41720_());
            String hover = key == null ? "?" : key.toString();
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
                g.m_280614_(this.f_96547_, Component.m_237113_(pg), infoX, infoY, 0x888888, false);
            }
        }
        int move = com.maidsmart.bd.MaidBdNetworking.MOVE.size();
        int keep = com.maidsmart.bd.MaidBdNetworking.KEEP.size();
        int keepN = com.maidsmart.bd.MaidBdNetworking.KEEP_N.size();
        int atLeast = com.maidsmart.bd.MaidBdNetworking.AT_LEAST.size();
        String hint = "\u00a77当前：一定搬 " + move + " · 一定不搬 " + keep + " · 保留N " + keepN
                + " · 至少留N " + atLeast + "（改的是服务端 config/promaid_bd_rules.json，需 OP）";
        g.m_280653_(this.f_96547_, Component.m_237113_(hint),
                this.clampCenterX(hint, cx), this.f_96544_ - 50, 0x888888);
    }

    /** 网格点击：在当前模式下切换该物品的规则。返回 true = 已消费。 */
    private boolean clickBdRuleGrid(double mouseX, double mouseY) {
        int cx = this.f_96543_ / 2;
        int panelLeft = Math.max(8, cx - 280);
        int left = panelLeft + 10;
        int gridTop = GRID_TOP;
        int gridRowsNow = this.gridRowsFor(this.f_96544_);
        int gridBottom = gridTop + gridRowsNow * GRID_CELL;
        if (mouseX < left || mouseX >= left + this.gridCols * GRID_CELL
                || mouseY < gridTop || mouseY >= gridBottom) {
            return false;
        }
        int perPage = this.gridCols * this.gridRows;
        int start = this.creativePage * perPage;
        int col = (int) ((mouseX - left) / GRID_CELL);
        int row = (int) ((mouseY - gridTop) / GRID_CELL);
        int idx = start + row * this.gridCols + col;
        if (idx < 0 || idx >= this.creativeItems.size()) {
            return false;
        }
        net.minecraft.resources.ResourceLocation key = net.minecraftforge.registries.ForgeRegistries.ITEMS
                .getKey(this.creativeItems.get(idx).m_41720_());
        if (key == null) {
            return false;
        }
        this.toggleBdRule(itemEntry(key.toString()));
        return true;
    }

    /** 把物品 id 包成规则写法（裸 id）。 */
    private static String itemEntry(String id) {
        return id;
    }

    /**
     * 在当前模式下切换某条目的规则：已在 → 移除；不在 → 加入（保留N个/至少留N个用输入框的 N，默认 16）。
     * 经网络由服务端落盘。
     */
    private void toggleBdRule(String entry) {
        if (entry == null || entry.isEmpty()) {
            return;
        }
        if (this.isInBdRule(entry)) {
            com.maidsmart.bd.MaidBdNetworking.sendRule((byte) 5, entry, 0);
        } else {
            switch (this.bdRuleMode) {
                case 1 -> com.maidsmart.bd.MaidBdNetworking.sendRule((byte) 2, entry, 0); // keep
                case 2 -> com.maidsmart.bd.MaidBdNetworking.sendRule((byte) 3, entry, this.bdNValue()); // keepN
                case 3 -> com.maidsmart.bd.MaidBdNetworking.sendRule((byte) 4, entry, this.bdNValue()); // keepAtLeast
                default -> com.maidsmart.bd.MaidBdNetworking.sendRule((byte) 1, entry, 0); // move
            }
        }
    }

    private int bdNValue() {
        try {
            if (this.bdNInput != null && !this.bdNInput.m_94155_().trim().isEmpty()) {
                return Math.max(0, Integer.parseInt(this.bdNInput.m_94155_().trim()));
            }
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("PromaidConfigScreen", ignored);
        }
        return 16;
    }

    /** 手输框「加入」：条目走原样（支持 tag:），保留N/至少留N 用旁边的 N。 */
    private void addBdRuleFromInput() {
        if (this.bdInput == null) {
            return;
        }
        String text = this.bdInput.m_94155_().trim();
        if (text.isEmpty()) {
            return;
        }
        // 裸 id 且不是 tag:/item: 前缀 → 补 minecraft: 命名空间（与喂水白名单同款便利）
        if (!text.contains(":") || (text.startsWith("item:") && !text.substring(5).contains(":"))) {
            String bare = text.startsWith("item:") ? text.substring(5) : text;
            text = (text.startsWith("item:") ? "item:" : "") + "minecraft:" + bare;
        }
        switch (this.bdRuleMode) {
            case 1 -> com.maidsmart.bd.MaidBdNetworking.sendRule((byte) 2, text, 0);
            case 2 -> com.maidsmart.bd.MaidBdNetworking.sendRule((byte) 3, text, this.bdNValue());
            case 3 -> com.maidsmart.bd.MaidBdNetworking.sendRule((byte) 4, text, this.bdNValue());
            default -> com.maidsmart.bd.MaidBdNetworking.sendRule((byte) 1, text, 0);
        }
        this.bdInput.m_94144_("");
    }

    /** 从名单移除（底部列表「移除」按钮） → 发 remove。 */
    private void removeBdRule(String entry) {
        com.maidsmart.bd.MaidBdNetworking.sendRule((byte) 5, entry, 0);
    }

    /**
     * 服务端回推了最新规则状态 → 就地刷新（子页列表/高亮、入口行摘要）。
     * 由 {@link com.maidsmart.bd.MaidBdNetworking.BdRulesStatePacket#handle} 在客户端主线程调用。
     * 【只刷新不重发请求】否则"回推→重建→再请求"会变成包循环。
     */
    public void onBdRulesState() {
        try {
            if (this.bdRules && this.bdRuleList != null) {
                this.bdRuleList.rebuild();
            }
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("PromaidConfigScreen", ignored);
        }
    }

    /** 服务端回推了"最近女仆·产出回收"状态 → 重建入口行，让按钮文案立刻变。 */
    public void onBdPerMaidState() {
        try {
            if (!this.inHome && !this.inGroup && !this.mineTable && !this.woodTable
                    && !this.altTable && !this.foodTable && !this.waterTable
                    && !this.cookTable && !this.buildBlackTable && !this.bdRules) {
                this.m_7856_();
            }
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("PromaidConfigScreen", ignored);
        }
    }

    /** 当前模式下要显示的规则（来自客户端缓存）。 */
    private List<String> bdCurrentRules() {
        return switch (this.bdRuleMode) {
            case 1 -> new ArrayList<>(com.maidsmart.bd.MaidBdNetworking.KEEP);
            case 2 -> new ArrayList<>(com.maidsmart.bd.MaidBdNetworking.KEEP_N);
            case 3 -> new ArrayList<>(com.maidsmart.bd.MaidBdNetworking.AT_LEAST);
            default -> new ArrayList<>(com.maidsmart.bd.MaidBdNetworking.MOVE);
        };
    }

    /** 超越维度规则列表（底部）：每行规则写法 + 「移除」。 */
    private class BdRuleList extends ObjectSelectionList<BdRuleList.BdRuleEntry> {
        private final List<String> entries = new ArrayList<>();

        BdRuleList(net.minecraft.client.gui.Font font, int x, int top, int width, int height) {
            super(Minecraft.m_91087_(), width, height, top, top + height, 22);
            this.m_93507_(x);
            this.m_93488_(false);
            this.m_93496_(false);
            this.f_93390_ = width;
            this.rebuild();
        }

        void rebuild() {
            this.m_93516_();
            this.entries.clear();
            try {
                this.entries.addAll(bdCurrentRules());
            } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("PromaidConfigScreen", ignored);
            }
            for (String e : this.entries) {
                this.m_7085_(new BdRuleEntry(e));
            }
        }

        @Override
        public int m_5759_() {
            return Math.max(this.f_93390_, 120);
        }

        @Override
        protected void m_238964_(net.minecraft.client.gui.GuiGraphics g, int mx, int my, float pt,
                                 int a, int b, int c, int d, int e) {
            super.m_238964_(g, mx, my, pt, a, b, c, d, e);
            int sx = this.f_93389_ + this.m_5759_() - 6;
            g.m_280509_(sx, this.f_93392_, sx + 6, this.f_93393_, 0xFF101010);
            int maxScroll = this.m_93518_();
            int area = this.f_93393_ - this.f_93392_;
            if (maxScroll > 0 && area > 8) {
                int sh = Math.max(32, area * area / maxScroll);
                sh = Math.min(sh, area - 8);
                int sy = (int) (this.m_93517_() * (double) (area - sh)) + this.f_93392_;
                g.m_280509_(sx, sy, sx + 4, sy + sh, 0x40FFFFFF);
            }
        }

        private class BdRuleEntry extends ObjectSelectionList.Entry<BdRuleList.BdRuleEntry> {
            private final String entry;
            private final net.minecraft.client.gui.components.Button delButton;

            BdRuleEntry(String entry) {
                this.entry = entry;
                this.delButton = Button.m_253074_(Component.m_237113_("移除"),
                                b -> PromaidConfigScreen.this.removeBdRule(this.entry))
                        .m_252987_(0, 0, 56, 18).m_253136_();
            }

            @Override
            public void m_6311_(net.minecraft.client.gui.GuiGraphics g, int index, int top,
                               int left, int width, int height, int mouseX, int mouseY,
                               boolean hovered, float partialTick) {
                int x = left + 4;
                int y = top + 4;
                // 保留N / 至少留N 的写法是 "match=count"，把 match 拆出来画图标与名称
                String match = this.entry;
                String suffix = "";
                int eq = this.entry.indexOf('=');
                if (eq > 0) {
                    match = this.entry.substring(0, eq);
                    suffix = " \u00a7b× " + this.entry.substring(eq + 1);
                }
                if (!match.startsWith("tag:")) {
                    net.minecraft.world.item.Item item = null;
                    try {
                        item = net.minecraftforge.registries.ForgeRegistries.ITEMS.getValue(
                                net.minecraft.resources.ResourceLocation.parse(match));
                    } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("PromaidConfigScreen", ignored);
                    }
                    if (item != null) {
                        g.m_280480_(new net.minecraft.world.item.ItemStack(item), x, y - 2);
                        x += 20;
                    }
                }
                g.m_280614_(PromaidConfigScreen.this.f_96547_,
                        Component.m_237113_("\u00a7a\u2714 \u00a7f"
                                + (match.startsWith("tag:") ? match : com.maidsmart.build.BlueprintLib.cnName(match))
                                + suffix),
                        x, y, 0xFFAAAAAA, false);
                this.delButton.m_252865_(left + BdRuleList.this.m_5759_() - 62);
                this.delButton.m_253211_(top + 1);
                this.delButton.m_88315_(g, mouseX, mouseY, partialTick);
            }

            @Override
            public boolean m_6375_(double mx, double my, int button) {
                if (button == 0 && this.delButton.m_5953_(mx, my)) {
                    this.delButton.m_6375_(mx, my, 0);
                    return true;
                }
                return false;
            }

            @Override
            public Component m_142172_() {
                return Component.m_237113_(this.entry);
            }
        }
    }

    /** 该方块（物品栈）此刻是否被玩家名单禁止——渲染与点击**共用这一处**，口径 = 女仆取材那一处。 */
    private boolean isBuildBlockForbidden(net.minecraft.world.item.ItemStack stack) {
        try {
            if (stack.m_41720_() instanceof net.minecraft.world.item.BlockItem bi) {
                return com.maidsmart.tool.MaidBuildBlockFilter.isBlacklistedBuildBlock(bi.m_40614_());
            }
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("PromaidConfigScreen", ignored);
        }
        return true; // 认不出对应方块 → 按"禁止"画（别让玩家以为她能用）
    }

    /** 点一下切换：当前禁止 → 记进「放宽名单」；当前允许 → 记进「禁用名单」。
     *  两份名单都按**规范化完整注册名**存，与 MaidBuildBlockFilter.containsId 的匹配口径一致。 */
    private void toggleBuildBlack(net.minecraft.world.item.ItemStack stack) {
        try {
            if (!(stack.m_41720_() instanceof net.minecraft.world.item.BlockItem bi)) {
                return;
            }
            net.minecraft.resources.ResourceLocation key = net.minecraftforge.registries.ForgeRegistries.BLOCKS.getKey(bi.m_40614_());
            if (key == null) {
                return;
            }
            String norm = com.maidsmart.tool.NaturalBlocks.normalize(key.toString());
            boolean forbidden =
                    com.maidsmart.tool.MaidBuildBlockFilter.isBlacklistedBuildBlock(bi.m_40614_());
            List<String> allow = new ArrayList<>(MaidSmartConfig.BRIDGE_BUILD_ALLOWED.get());
            List<String> forbid = new ArrayList<>(MaidSmartConfig.BRIDGE_BUILD_FORBIDDEN.get());
            allow.removeIf(s -> com.maidsmart.tool.NaturalBlocks.normalize(s).equals(norm));
            forbid.removeIf(s -> com.maidsmart.tool.NaturalBlocks.normalize(s).equals(norm));
            if (forbidden) {
                allow.add(norm); // 面板上原本"红框✖" → 放开
            } else {
                forbid.add(norm); // 面板上原本"绿框✔" → 禁止
            }
            MaidSmartConfig.BRIDGE_BUILD_ALLOWED.set(allow);
            MaidSmartConfig.BRIDGE_BUILD_FORBIDDEN.set(forbid);
            if (this.buildBlackList != null) {
                this.buildBlackList.rebuild();
            }
        } catch (Throwable t) {
             com.maidsmart.diag.Diag.swallow("PromaidConfigScreen", t); // 写配置失败不能静默：面板上会"点了没反应"，日志里必须留痕（本工程惯例）
            com.maidsmart.tool.PromaidLog.log("搭方块名单", "写名单失败：" + t);
        }
    }

    /** 从名单里移掉一条（列表上的「移除」）——回到该方块的默认规则状态。 */
    private void removeBuildBlack(String id) {
        try {
            String norm = com.maidsmart.tool.NaturalBlocks.normalize(id);
            List<String> allow = new ArrayList<>(MaidSmartConfig.BRIDGE_BUILD_ALLOWED.get());
            List<String> forbid = new ArrayList<>(MaidSmartConfig.BRIDGE_BUILD_FORBIDDEN.get());
            allow.removeIf(s -> com.maidsmart.tool.NaturalBlocks.normalize(s).equals(norm));
            forbid.removeIf(s -> com.maidsmart.tool.NaturalBlocks.normalize(s).equals(norm));
            MaidSmartConfig.BRIDGE_BUILD_ALLOWED.set(allow);
            MaidSmartConfig.BRIDGE_BUILD_FORBIDDEN.set(forbid);
            if (this.buildBlackList != null) {
                this.buildBlackList.rebuild();
            }
        } catch (Throwable t) {
             com.maidsmart.diag.Diag.swallow("PromaidConfigScreen", t); // 写配置失败不能静默：面板上会"点了没反应"，日志里必须留痕（本工程惯例）
            com.maidsmart.tool.PromaidLog.log("搭方块名单", "写名单失败：" + t);
        }
    }

    /** 玩家改过的条目（{id, "allow"/"forbid"}）——列表先列禁用、再列放宽。 */
    private List<String[]> buildBlackEntries() {
        List<String[]> out = new ArrayList<>();
        try {
            for (String s : MaidSmartConfig.BRIDGE_BUILD_FORBIDDEN.get()) {
                out.add(new String[]{s, "forbid"});
            }
            for (String s : MaidSmartConfig.BRIDGE_BUILD_ALLOWED.get()) {
                out.add(new String[]{s, "allow"});
            }
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("PromaidConfigScreen", ignored);
        }
        return out;
    }

    /** v1.3.0(beta) 实测六百八十：搭方块禁用名单子页的底部列表（只列玩家自己改过的条目）。 */
    private class BuildBlackList extends ObjectSelectionList<BuildBlackList.BuildBlackEntry> {
        private final List<String[]> entries = new ArrayList<>();

        BuildBlackList(net.minecraft.client.gui.Font font, int x, int top, int width, int height) {
            super(Minecraft.m_91087_(), width, height, top, top + height, 22);
            this.m_93507_(x);
            this.m_93488_(false);
            this.m_93496_(false);
            this.f_93390_ = width; // 行宽=列表宽（同 MinableList / AltList）
            this.rebuild();
        }

        void rebuild() {
            this.m_93516_();
            this.entries.clear();
            this.entries.addAll(buildBlackEntries());
            for (String[] e : this.entries) {
                this.m_7085_(new BuildBlackEntry(e[0], "allow".equals(e[1])));
            }
        }

        @Override
        public int m_5759_() {
            return Math.max(this.f_93390_, 120); // rowWidth（构造时已设为列表宽）
        }

        /** 同 MinableList / AltList：默认滚动条覆盖为低调样式 */
        @Override
        protected void m_238964_(net.minecraft.client.gui.GuiGraphics g, int mx, int my, float pt,
                                 int a, int b, int c, int d, int e) {
            super.m_238964_(g, mx, my, pt, a, b, c, d, e);
            int sx = this.f_93389_ + this.m_5759_() - 6;
            g.m_280509_(sx, this.f_93392_, sx + 6, this.f_93393_, 0xFF101010);
            int maxScroll = this.m_93518_();
            int area = this.f_93393_ - this.f_93392_;
            if (maxScroll > 0 && area > 8) {
                int sh = Math.max(32, area * area / maxScroll);
                sh = Math.min(sh, area - 8);
                int sy = (int) (this.m_93517_() * (double) (area - sh)) + this.f_93392_;
                g.m_280509_(sx, sy, sx + 4, sy + sh, 0x40FFFFFF);
            }
        }

        private class BuildBlackEntry extends ObjectSelectionList.Entry<BuildBlackList.BuildBlackEntry> {
            private final String id;
            private final boolean allow;
            private final net.minecraft.client.gui.components.Button delButton;

            BuildBlackEntry(String id, boolean allow) {
                this.id = id;
                this.allow = allow;
                this.delButton = Button.m_253074_(Component.m_237113_("移除"),
                                b -> PromaidConfigScreen.this.removeBuildBlack(this.id))
                        .m_252987_(0, 0, 56, 18).m_253136_();
            }

            @Override
            public void m_6311_(net.minecraft.client.gui.GuiGraphics g, int index, int top,
                                      int left, int width, int height, int mouseX, int mouseY,
                                      boolean hovered, float partialTick) {
                int x = left + 4;
                int y = top + 4;
                net.minecraft.world.item.Item it = net.minecraftforge.registries.ForgeRegistries.ITEMS.getValue(net.minecraft.resources.ResourceLocation.parse(this.id));
                if (it != null) {
                    g.m_280480_(new net.minecraft.world.item.ItemStack(it), x, y - 2);
                    x += 20;
                }
                String mark = this.allow ? "\u00a7a\u2714 \u00a7f" : "\u00a7c\u2716 \u00a77";
                g.m_280614_(PromaidConfigScreen.this.f_96547_,
                        Component.m_237113_(mark + com.maidsmart.build.BlueprintLib.cnName(this.id)),
                        x, y, LABEL_COLOR, false);
                this.delButton.m_252865_(left + BuildBlackList.this.m_5759_() - 62);
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
            int area = this.f_93393_ - this.f_93392_;
            if (maxScroll > 0 && area > 8) {
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
                // 反馈："自定义方块的种类需要根据多方面维度进行新的划分，仅仅一格高、
                // 半格高不够"；形态/碰撞由所在分类标签体现，这里补族与功能两维
                String tag = "";
                try {
                    net.minecraft.world.level.block.Block blk = net.minecraftforge.registries.ForgeRegistries.BLOCKS
                            .getValue(net.minecraft.resources.ResourceLocation.parse(this.id));
                    if (blk != null && blk != net.minecraft.world.level.block.Blocks.f_50016_) {
                        tag = "\u00a77[" + com.maidsmart.build.BlueprintLib.materialFamily(blk)
                                + "\u00b7" + com.maidsmart.build.BlueprintLib.blockFunction(blk) + "] \u00a7f";
                    }
                } catch (Exception ignored) { com.maidsmart.diag.Diag.swallow("PromaidConfigScreen", ignored);
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
        int per = this.gridCols * this.gridRows;
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
        // 【实测七百九十七】幽灵方块渲染开关（弱机）：
        // 复用已有的 BUILD_PROJECTION（v1.1.0 实测八十二 立的"投影总开关"，此前只被
        // BlueprintAreaPreview.drawGhost 的门读、配置界面上从未露出）——这里把它摆上台面。
        // 关掉后：橙影/青影幽灵方块不再渲染，蓝图点云也不再向服务端请求（省渲染 + 省带宽）；
        // 红框/金框线框保留（线框极轻，且没了框就看不出建造范围）；指标石临时蓝图的橙影同关。
        this.rows.add(new BoolRow("幽灵方块投影（性能）", MaidSmartConfig.BUILD_PROJECTION.get(),
                v -> MaidSmartConfig.BUILD_PROJECTION.set(v),
                "幽灵方块投影（性能）：建造模式叠加的半透明方块轮廓（蓝图橙影/预览青影、指标石临时蓝图橙影）——关掉后只保留红框/金框线框，显著省渲染与带宽（弱机建议关）；框还在，不影响选址与建造"));
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
                s -> setInt(MaidSmartConfig.BUILD_MAX_HEIGHT, s),"LLM 蓝图高度上限（y 层数，默认 32）：只约束 smart_build 的现场 JSON（防模型喷巨量参数、盖出通天塔）；smart_design 自绘蓝图与外部蓝图文件不受此限。"));
        this.rows.add(new NumRow("AI 设计蓝图上限", String.valueOf(MaidSmartConfig.BUILD_DESIGN_MAX_BLOCKS.get()),
                s -> setInt(MaidSmartConfig.BUILD_DESIGN_MAX_BLOCKS, s), "AI 子 Agent 设计蓝图方块上限（v1.5.222：默认 50 万，范围 100~50 万——想设计多大填多少）"));
        this.rows.add(new NumRow("结构蓝图上限", String.valueOf(MaidSmartConfig.BUILD_STRUCTURE_MAX_BLOCKS.get()),
                s -> setInt(MaidSmartConfig.BUILD_STRUCTURE_MAX_BLOCKS, s), "结构文件蓝图方块上限（默认 100 万，50 万级建筑无压力；数值越高服务器负担越重）"));
        this.rows.add(new NumRow("女仆管理上限", String.valueOf(MaidSmartConfig.BUILD_MAX_MAIDS.get()),
                s -> setInt(MaidSmartConfig.BUILD_MAX_MAIDS, s), "女仆管理上限：128 格内参与建造的女仆超过此数时手册列表截断（只影响显示，不影响实际建造）"));
        this.rows.add(new BoolRow("建造地点=玩家脚下", MaidSmartConfig.BUILD_ORIGIN_PLAYER.get(),
                v -> MaidSmartConfig.BUILD_ORIGIN_PLAYER.set(v), "建造地点基准：true=玩家脚下（默认），false=女仆脚下"));
        this.rows.add(new BoolRow("指标石",
                MaidSmartConfig.BUILD_INDEX_STONE.get(),
                v -> MaidSmartConfig.BUILD_INDEX_STONE.set(v),"指标石（默认开）：手持右击方块锁定（绿→红，可锁很远）→ 右击女仆绑定 → 从女仆所在格到锁定格之间的空气格组成临时蓝图，她立刻从背包（不够从你背包）取材料逐格填充。关 = 退化为普通物品。"));
        // v1.5.393 实测八百零三：建造不强制 home + 禁非玩家意愿的自动传送
        this.rows.add(new BoolRow("建造不强制 home",
                MaidSmartConfig.BUILD_NO_FORCE_HOME.get(),
                v -> MaidSmartConfig.BUILD_NO_FORCE_HOME.set(v),"建造不强制 home（默认开）：建造/指标石搭建期间不再把她切成守家，改为从源头拦下各处非玩家意愿的自动传送（跟随瞬移/远距拉回/跨维跟随/死亡归位/自保归位），她留在工地。关 = 旧行为。"));
        // v1.5.316：红石机器改革开关（专属顺序+活建造+自动放矿车）
        this.rows.add(new BoolRow("红石机器专属搭建", MaidSmartConfig.BUILD_MACHINE_SMART.get(),
                v -> MaidSmartConfig.BUILD_MACHINE_SMART.set(v),"红石机器专属搭建：机器按红石拓扑分层放置（结构→机构→活动件→传感→动力源→TNT）+ 活建造，建好即自然运行；轰炸机类完工自动放矿车启动。关 = 常规顺序+静默+完工唤醒。"));
        // v1.2.2 实测五百八十五（issue #15）：流体保留判据（原文件名关键词 → 可配置 + 看图内容）
        this.rows.add(new CycleRow("图纸流体保留",
                new String[]{"auto", "always", "never"},
                MaidSmartConfig.BUILD_KEEP_FLUIDS.get(),
                v -> MaidSmartConfig.BUILD_KEEP_FLUIDS.set(v),"图纸里的水/岩浆怎么办：auto（默认）= 机器图纸保留（文件名含机器关键词或 ≥3 种红石机器件）、普通建筑剥离；always = 都照建；never = 一律剥离。剥离会写日志。"));
        // v1.2.2 实测五百八十六（issue #14）：缺料同类宽松（建筑严格 / 机器放宽）
        this.rows.add(new CycleRow("缺料同类宽松",
                new String[]{"off", "machine", "always"},
                MaidSmartConfig.BUILD_LOOSE_MATERIALS.get(),
                v -> MaidSmartConfig.BUILD_LOOSE_MATERIALS.set(v),"缺料时能不能用同类方块顶替：off = 只认同族表（外观优先）；machine（默认）= 只对机器蓝图放宽；always = 所有图纸都放宽。范围：任意告示牌/树叶互认、同类型羊毛地毯与染色玻璃板。"));
        // v1.5.331：TNT 点火保护期——防"刚建好炸膛"（天机屠龙炮等观察者→活塞推 TNT 机器）
        this.rows.add(new NumRow("TNT 点火保护期（秒）", String.valueOf(MaidSmartConfig.BUILD_TNT_IGNITION_GRACE.get()),
                s -> setInt(MaidSmartConfig.BUILD_TNT_IGNITION_GRACE, s),"TNT 点火保护期（秒，默认 120）：建造期+完工激活期+宽限期内压制一切 TNT 点火，防机器刚建好炸膛；完工结算只点燃邻接带电的 TNT，期满后按正常红石逻辑。0 = 关闭保护。"));
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
        // v1.0.4：透视感知开关——v1.1.0 实测二百二十七：默认关（开启=隔墙找矿；关闭=女仆像玩家一样只发现视线无阻的矿）
        this.rows.add(new BoolRow("透视感知（隔墙找矿）", MaidSmartConfig.MINE_SEEK_THROUGH_WALLS.get(),
                v -> MaidSmartConfig.MINE_SEEK_THROUGH_WALLS.set(v),"透视感知：开启后她能发现视线被方块挡住的矿物并挖通开路（隔墙找矿）；关闭（默认）则只能发现视线无阻的矿——除水/岩浆外都挡视线，被挡的不报点、不隔墙挖；看得见的矿身前有障碍照常挖穿。"));
        this.rows.add(new NumRow("检索半径", String.valueOf(MaidSmartConfig.MINE_SEARCH_RADIUS.get()),
                s -> setInt(MaidSmartConfig.MINE_SEARCH_RADIUS, s), "矿物检索半径（水平格）：女仆以锚点为中心扫描正方形区域找矿——调大更早发现远处矿，扫描更耗时；调小专注身边"));
        // v1.1.0 实测六十一（借鉴 TLM-Sincerely 预算制探测）：全量扫描分帧执行
        this.rows.add(new NumRow("扫描预算（格/tick）", String.valueOf(MaidSmartConfig.MINE_SCAN_BUDGET.get()),
                s -> setInt(MaidSmartConfig.MINE_SCAN_BUDGET, s),"挖矿扫描预算（格/tick，默认 4096）：全量扫描矿框分帧执行——每 tick 最多检查这么多格，剩余下 tick 继续（扫完前她短暂无目标）；调小更不卡服但找矿变慢，调大反之。"));
        this.rows.add(new NumRow("垂直向下范围", String.valueOf(MaidSmartConfig.MINE_DOWN_RANGE.get()),
                s -> setInt(MaidSmartConfig.MINE_DOWN_RANGE, s), "垂直向下范围（格）：找脚下多深的矿；调大能发现深层矿脉，扫描开销上升"));
        this.rows.add(new NumRow("垂直向上范围", String.valueOf(MaidSmartConfig.MINE_UP_RANGE.get()),
                s -> setInt(MaidSmartConfig.MINE_UP_RANGE, s), "垂直向上范围（格）：找头顶多高的矿（悬崖/天花板矿脉）；调大能发现高处矿"));
        this.rows.add(new NumRow("穿透预算", String.valueOf(MaidSmartConfig.MINE_BREAK_BUDGET.get()),
                s -> setInt(MaidSmartConfig.MINE_BREAK_BUDGET, s), "穿透预算（默认 22）：选矿时统计到矿之间要穿过的实心方块层数（含石头/泥土），超过预算的矿不选、走近再看；调大=爱穿墙打隧道，调小=只挑暴露的矿"));
        this.rows.add(new NumRow("价值权重", String.valueOf(MaidSmartConfig.MINE_VALUE_WEIGHT.get()),
                s -> setDouble(MaidSmartConfig.MINE_VALUE_WEIGHT, s), "价值权重：矿石价值对选矿的加成——钻石/绿宝石 500 分、铁/金 250、煤 100；权重越高高价值矿越优先（哪怕更远）"));
        this.rows.add(new NumRow("深度惩罚", String.valueOf(MaidSmartConfig.MINE_DEPTH_PENALTY.get()),
                s -> setDouble(MaidSmartConfig.MINE_DEPTH_PENALTY, s), "深度惩罚（每格扣分）：矿越深选矿成本越高——想先挖浅处就调大，想下深层挖矿就调小"));
        this.rows.add(new NumRow("挖矿速度系数", String.valueOf(MaidSmartConfig.MINE_SPEED_FACTOR.get()),
                s -> setDouble(MaidSmartConfig.MINE_SPEED_FACTOR, s), "挖矿速度系数（1.0=玩家速度，1.2=快20%）"));
        this.rows.add(new NumRow("接近矿速度", String.valueOf(MaidSmartConfig.MINE_MOVE_SPEED.get()),
                s -> setDouble(MaidSmartConfig.MINE_MOVE_SPEED, s), "接近矿速度倍率：0.4 = 正常步行（搭高不漂移）；调大可跑更快接近矿石，但搭高时容易冲过头"));
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
        // v1.3.0(beta) 实测七百一十七【issue #29】
        this.rows.add(new BoolRow("连锁每块耗耐久", MaidSmartConfig.MINE_CHAIN_FULL_DURABILITY.get(),
                v -> MaidSmartConfig.MINE_CHAIN_FULL_DURABILITY.set(v), "连锁采集每块都消耗耐久：关闭（默认）时整串连锁只扣 1 点耐久（历史行为）；开启后连锁破坏的每一块都扣 1 点，更贴近手工挖矿，但镐子会明显更快磨损"));
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
        this.rows.add(new BoolRow("骑乘/坐下中禁止搭方块", MaidSmartConfig.MINE_RIDE_NO_PILLAR.get(),
                v -> MaidSmartConfig.MINE_RIDE_NO_PILLAR.set(v), "骑乘（扫帚等载具/TLM 椅子）或坐下时，挖矿垫脚 / 伐木垫脚 / 搭路 / 自保搭高全部不再放方块——这两种形态下她挪不动，垫方块只会留残渣；关闭 = 旧行为。蓝图/指标石建造不受影响"));
        this.rows.add(new NumRow("废石清理间隔（tick）", String.valueOf(MaidSmartConfig.MINE_JUNK_CHECK_INTERVAL.get()),
                s -> setInt(MaidSmartConfig.MINE_JUNK_CHECK_INTERVAL, s), "废石清理间隔（tick，20=1 秒）：多久检查一次背包废石是否超量，调小清理更及时、略耗性能"));
        this.rows.add(new NumRow("播报限频（tick）", String.valueOf(MaidSmartConfig.MINE_SKIP_REPORT_INTERVAL.get()),
                s -> setInt(MaidSmartConfig.MINE_SKIP_REPORT_INTERVAL, s), "播报限频（tick）：'镐子挖不动/矿物被挡住'等提示的最短间隔，防刷屏"));
        this.rows.add(new NumRow("创造面板默认价值", String.valueOf(MaidSmartConfig.MINE_CREATIVE_DEFAULT_VALUE.get()),
                s -> setInt(MaidSmartConfig.MINE_CREATIVE_DEFAULT_VALUE, s), "创造面板默认价值：矿表页锁定方块后，输入框留空直接点「添加」时用的分数（快捷赋值）；想自定义就输数值再点添加，或输入框填 方块id=分数 更新"));
        this.rows.add(new BoolRow("挖矿中禁止拾取", MaidSmartConfig.MISC_PICKUP_PRIORITY.get(),
                v -> MaidSmartConfig.MISC_PICKUP_PRIORITY.set(v), "挖矿中禁止拾取（捡掉落物最低优先级）"));
        this.rows.add(new BoolRow("拾取按价值重排", MaidSmartConfig.MISC_PICKUP_VALUE_REORDER.get(),
                v -> MaidSmartConfig.MISC_PICKUP_VALUE_REORDER.set(v),"拾取按价值重排（默认开）：一地掉落物时，原版低价值物（废石/泥土/沙子/粘土/地狱岩/凝灰岩等）优先级降到最低，矿石与模组物品排前面。关掉 = TLM 原版顺序（就近优先）。"));
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
                v -> MaidSmartConfig.WOOD_TAG_AUTO.set(v),"自动识别模组原木：开启（默认）时凡带原版 #logs / #bamboo_blocks 标签的方块都自动视为可砍木材（价值 300，无需进名单）；关闭则只认木材名单（名单可精确控制砍什么与价值权重）。"));
        this.rows.add(new BoolRow("透视感知（隔墙找木材）", MaidSmartConfig.WOOD_SEEK_THROUGH_WALLS.get(),
                v -> MaidSmartConfig.WOOD_SEEK_THROUGH_WALLS.set(v), "透视感知：开启（默认）后女仆能发现视线被挡住的木材并挖通开路；关闭则像玩家一样只发现视线无阻的木材——树叶不挡视线，水/岩浆外任何方块都挡"));
        this.rows.add(new NumRow("检索半径", String.valueOf(MaidSmartConfig.WOOD_SEARCH_RADIUS.get()),
                s -> setInt(MaidSmartConfig.WOOD_SEARCH_RADIUS, s), "木材检索半径（水平格）：以锚点为中心扫描正方形区域找树"));
        // v1.1.0 实测六十一（借鉴 TLM-Sincerely 预算制探测）：全量扫描分帧执行
        this.rows.add(new NumRow("扫描预算（格/tick）", String.valueOf(MaidSmartConfig.WOOD_SCAN_BUDGET.get()),
                s -> setInt(MaidSmartConfig.WOOD_SCAN_BUDGET, s),"伐木扫描预算（格/tick，默认 4096）：全量扫描木材框分帧执行——每 tick 最多检查这么多格，剩余下 tick 继续（扫完前她短暂无目标）；调小更不卡服但找树变慢，调大反之。"));
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
        // v1.1.0 实测二百二十八/二百二十九：随手种树开关 + 冷却（反馈：CD ~5 秒、面板可调、开关默认开）
        this.rows.add(new BoolRow("随手种树", MaidSmartConfig.WOOD_PLANT_SAPLING_ENABLED.get(),
                v -> MaidSmartConfig.WOOD_PLANT_SAPLING_ENABLED.set(v), "随手种树（默认开）：她手上有树苗、附近半径 6 格有可种土块时随手种一棵（触发 = 伐木模式，独立模块）；关闭 = 只砍树不种树"));
        this.rows.add(new NumRow("补种树苗冷却（tick）", String.valueOf(MaidSmartConfig.WOOD_PLANT_SAPLING_COOLDOWN.get()),
                s -> setInt(MaidSmartConfig.WOOD_PLANT_SAPLING_COOLDOWN, s), "补种树苗冷却（tick，默认 100≈5 秒）：她手上有树苗、附近半径 6 格有可种土块时随手种一棵（伐木模式下触发，独立模块）；调小种得更勤、树苗消耗更快"));
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
        // v1.3.0(beta) 实测七百一十五：未完成的事（移植自 Sphantosis 的事件「终止:0」标记）
        this.rows.add(new BoolRow("未完成的事注入（移植自 Sphantosis）", MaidSmartConfig.MEMORY_OPEN_EVENTS.get(),
                v -> MaidSmartConfig.MEMORY_OPEN_EVENTS.set(v),"未完成的事注入（默认开，移植自 Sphantosis）：给「要去做某事 / 在等结果 / 计划没落地」这类事件打 open=1 标记，对话时单列成「她记挂着的事」（最近 2 条）。关掉 = 不再单列。"));
        // v1.5.190：新记忆开关（防抖写盘）
        this.rows.add(new BoolRow("防抖写盘", MaidSmartConfig.MEMORY_LAZY_SAVE.get(),
                v -> MaidSmartConfig.MEMORY_LAZY_SAVE.set(v), "记忆防抖写盘（内存累积后按扫描间隔批量落盘——减少磁盘 IO，多女仆时防止服务端卡顿；关闭=每次写入立即落盘，可靠性优先）"));
        // v1.1.0：记忆升级（情绪快照 / 人格种子 / 每日关心点 / 双 agent 提取）
        this.rows.add(new SectionRow("人格与情绪", true));
        this.rows.add(new BoolRow("情绪快照入记忆", MaidSmartConfig.MEMORY_AFFECT_SNAPSHOT.get(),
                v -> MaidSmartConfig.MEMORY_AFFECT_SNAPSHOT.set(v), "每条记忆写入时附带当时的情绪状态（PAD：愉悦/唤醒/支配/亲密/冲突/思念/受伤债/修复债）——旧记忆不受影响，仅新写入生效"));
        this.rows.add(new BoolRow("人格种子注入", MaidSmartConfig.MEMORY_PERSONA.get(),
                v -> MaidSmartConfig.MEMORY_PERSONA.set(v),"从女仆记忆目录的 persona / traits / core_memories.jsonl 只读投影人格——人设与聊天记忆分离，聊天不改写人格；首次生成默认模板（可手改）。"));
        this.rows.add(new BoolRow("人设统一", MaidSmartConfig.MEMORY_PERSONA_UNIFY.get(),
                v -> MaidSmartConfig.MEMORY_PERSONA_UNIFY.set(v), "TLM 原版已有人设时，人格种子块降级为补充（只补 TLM 没有的人格参数/核心记忆，不再重复身份，冲突以 TLM 设定为准）；关=双人设并存旧行为"));
        this.rows.add(new BoolRow("每日关心点", MaidSmartConfig.MEMORY_CARE_POINTS.get(),
                v -> MaidSmartConfig.MEMORY_CARE_POINTS.set(v), "每日回顾附加'下次该怎么对主人'的行动建议（从情绪残留/边界/偏好/风格推导）——主动会话会自动复用当话题"));
        this.rows.add(new BoolRow("双 agent 提取", MaidSmartConfig.MEMORY_DUAL_AGENT.get(),
                v -> MaidSmartConfig.MEMORY_DUAL_AGENT.set(v), "摘要与事实/事件分两次独立 LLM 调用（更聚焦、互不阻塞；关=单次合并提取省 token）"));
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
        this.rows.add(new NumRow("注视播报间隔（秒）", String.valueOf(MaidSmartConfig.PERCEPTION_LOOK_COOLDOWN.get()),
                s -> setInt(MaidSmartConfig.PERCEPTION_LOOK_COOLDOWN, s), "「主人一直在看着我…」触发间隔（秒，每只女仆各自计时；默认 1800 = 30 分钟）——这条带语音，间隔比其它播报长得多"));
    }

    /** 情绪页（PAD 情绪层） */
    private void affectRows() {
        this.rows.add(new SectionRow("情绪（PAD 层，独立于 TLM 好感等既有数值）", false));
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
                v -> MaidSmartConfig.TOOL_PERCEPTION.set(v),"perception_query 工具（look_around/terrain/build_site/inspect/scanblock/scanentity）：LLM 建造前探查环境，减少超时重试。"));
        this.rows.add(new BoolRow("work_list（任务清单/缺料查询）", MaidSmartConfig.TOOL_WORK_LIST.get(),
                v -> MaidSmartConfig.TOOL_WORK_LIST.set(v), "work_list 工具（query_todo/build_need——当前任务清单与建造材料缺口查询，杜绝重复轮次与\"先生成清单再开工\"的超时）"));
        // v1.5.287：查看主人物品栏工具（只读查询主人背包内容）
        this.rows.add(new BoolRow("smart_owner_inventory（查看主人背包）", MaidSmartConfig.TOOL_OWNER_INVENTORY.get(),
                v -> MaidSmartConfig.TOOL_OWNER_INVENTORY.set(v), "smart_owner_inventory 工具（只读查询主人背包里有什么——LLM 需要确认主人持有某材料/装备时调用，不修改任何物品）"));
        // v1.2.2 实测五百七十八：指挥三件套 + 状态自检（此前模型只能打怪，别的模式够不到）
        this.rows.add(new BoolRow("smart_switch_task（换任务/模式）", MaidSmartConfig.TOOL_SWITCH_TASK.get(),
                v -> MaidSmartConfig.TOOL_SWITCH_TASK.set(v),"smart_switch_task 工具：切换任务/工作模式（TLM 原生 + 本模组任务全可切，支持空袭/挖矿/砍树/建造等中文别名；认不出会把可用任务列表回给模型；排班中的女仆拒绝外部指派）。"));
        this.rows.add(new BoolRow("smart_air_raid（起飞/停止空袭）", MaidSmartConfig.TOOL_AIR_RAID.get(),
                v -> MaidSmartConfig.TOOL_AIR_RAID.set(v), "smart_air_raid 工具（起飞空袭——近战/远程可选、顺手锁定目标，并如实回报\"缺不缺件\"；停止=清目标清本轮状态并切回地面攻击）"));
        this.rows.add(new BoolRow("smart_work_area（定工作区/驻守）", MaidSmartConfig.TOOL_WORK_AREA.get(),
                v -> MaidSmartConfig.TOOL_WORK_AREA.set(v),"smart_work_area 工具：把工作/休闲锚点设到主人脚下或她自己所在处并开启驻守，也可解除驻守恢复跟随、或只查看锚点——写的是与潜行+中键、河童罗盘同一份 SchedulePos 数据。"));
        this.rows.add(new BoolRow("smart_readiness（状态自检）", MaidSmartConfig.TOOL_READINESS.get(),
                v -> MaidSmartConfig.TOOL_READINESS.set(v), "smart_readiness 工具（只读自检：空袭三件套/是否缺件/远程弹药/驻守与排班状态/工作锚点/血量/主人饥饿——让模型\"先查后做\"，不改任何状态）"));
        // v1.5.250：每日主动对话次数上限（复用 dialogue.proactiveDaily——主动对话
        // 区已有同配置，这里按要求放到 AI 工具设置，两处改同一个值）
        this.rows.add(new NumRow("每日主动对话上限（次/女仆）", String.valueOf(MaidSmartConfig.DIALOGUE_PROACTIVE_DAILY.get()),
                s -> setInt(MaidSmartConfig.DIALOGUE_PROACTIVE_DAILY, s), "每日主动对话上限（次/女仆）：女仆一天内主动开口说话（关心/夜晚/沉默找话题/事件感慨）的总次数上限；超过后不再发言，并在系统消息里提示\"已达上限\"。控 LLM token 成本"));
        // v1.1.0 实测一百九十四：击杀邀功对话开关（默认关——击杀日志时刻刷屏）
        this.rows.add(new BoolRow("击杀邀功对话", MaidSmartConfig.DIALOGUE_PROACTIVE_KILL.get(),
                v -> MaidSmartConfig.DIALOGUE_PROACTIVE_KILL.set(v), "女仆击杀敌人（主人 16 格内）后主动邀功的 LLM 对话气泡（默认关——战斗频繁时击杀就冒一次=时刻刷屏；想保留开启）"));
    }

    private void dialogueRows() {
        // v1.5.356：API 日配额提到对话提示区第一行——反馈"手册里 LLM 调用次数限制的
        // 设置选项没了"：配置一直都在,但排在区第 5 行,窗口高度/GUI 缩放较小时被分页藏到
        // 第 2+ 页(同 v1.5.293/295 的可见性修复模式)。任何窗口高度打开对话提示第一屏即可见。
        this.rows.add(new NumRow("API 日配额", String.valueOf(MaidSmartConfig.DIALOGUE_API_DAILY_LIMIT.get()),
                s -> setInt(MaidSmartConfig.DIALOGUE_API_DAILY_LIMIT, s), "所有女仆每日主动 LLM 调用总量上限（token 成本；默认 40，填 0 = 不限——旧版 0 是永远禁言的 bug）"));
        // v1.5.293：自主决策提到对话页第一屏——旧版在「主动对话」之后（本页第 14 行），
        // 窗口高度/GUI 缩放较小时被分页藏到第 2+ 页（反馈"详细设置里自主决策按键
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
        // v1.5.303：手填文本框改为【选项选择】（反馈："设计成选择项目吧，让人对
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
                },"对话输出语言：控制 LLM 回复的语言。跟随 = 用客户端当前语言（客户端被改过时女仆会跟着变）；选具体语言 = 强制该语言，无论客户端是什么。"));
    }

    /** v1.5.198：语音页——TTS 音量倍率 / 系统消息朗读 / 系统语音包导入 / 语音缓存 */
    private void voiceRows() {
        this.rows.add(new SectionRow("TTS 播放", false));
        this.rows.add(new NumRow("音量倍率", String.valueOf(MaidSmartConfig.TTS_VOLUME_MULTIPLIER.get()),
                s -> setDouble(MaidSmartConfig.TTS_VOLUME_MULTIPLIER, s),"TTS 音量倍率（与伤害无关！）：TLM 的 TTS 原始音量偏小，此值直接乘上去——1.5 = +50%，2.0 = 一倍。默认 2.0，范围 0.1-5.0；作用于 LLM 对话与系统消息。"));
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
                        new com.maidsmart.build.BlueprintBookEntityPackets.VoicePackImportPacket(s.trim()));
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
                                        new com.maidsmart.build.BlueprintBookEntityPackets.VoicePackImportPacket(path));
                            });
                        } catch (Exception ignored) { com.maidsmart.diag.Diag.swallow("PromaidConfigScreen", ignored);
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
                            new com.maidsmart.build.BlueprintBookEntityPackets.VoicePackQueryPacket("reload"));
                },
                "从磁盘重新读取 manifest（手动改文件后点此生效）"));
        this.rows.add(new BtnRow("查看语音包状态", "查看状态", () -> {
                    net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.m_91087_();
                    if (mc.f_91074_ != null) {
                        mc.f_91074_.m_213846_(net.minecraft.network.chat.Component.m_237113_(
                                "\u00a7e[maid_smart] 已请求查看语音包状态，结果请看聊天框"));
                    }
                    com.maidsmart.build.BlueprintBookNetworking.CHANNEL.sendToServer(
                            new com.maidsmart.build.BlueprintBookEntityPackets.VoicePackQueryPacket("status"));
                },
                "查看当前已加载的文本映射条数与 TTS 语音缓存文件数"));
        this.rows.add(new NumRow("缓存上限（个）", String.valueOf(MaidSmartConfig.TTS_CACHE_MAX_FILES.get()),
                s -> setInt(MaidSmartConfig.TTS_CACHE_MAX_FILES, s),
                "TTS 语音缓存上限（voice_cache/，训练一次保存后复用；超出删最旧）"));
        // v1.1.0 实测四百二十：内置日语语音包（随 jar 分发，最高优先级；可调音量/间隔/压原生）
        this.rows.add(new SectionRow("内置日语语音包（随 mod 附带，v1.1.0）", true));
        this.rows.add(new BoolRow("启用内置语音包", MaidSmartConfig.TTS_JAR_PACK_ENABLED.get(),
                v -> MaidSmartConfig.TTS_JAR_PACK_ENABLED.set(v),"内置日语语音包：随 mod 附带的 122 条日语语音（67 条系统消息 + 49 条排班气泡 + 6 条拥抱/摸头台词）；触发时自动播放，优先级高于 TLM 原生语音包与 TTS 合成。"));
        this.rows.add(new NumRow("内置语音包音量", String.valueOf(MaidSmartConfig.TTS_JAR_PACK_VOLUME.get()),
                s -> setDouble(MaidSmartConfig.TTS_JAR_PACK_VOLUME, s),"内置语音包音量倍率（默认 1.0，0.1-20.0）：只作用于内置日语语音，与 TTS 音量相乘。素材已做峰值归一化（响度约 +11 dB），一般 1.0~2.0 就够，仍嫌小可继续调大。"));
        this.rows.add(new NumRow("内置语音最小间隔（秒）", String.valueOf(MaidSmartConfig.TTS_JAR_PACK_MIN_INTERVAL_S.get()),
                s -> setInt(MaidSmartConfig.TTS_JAR_PACK_MIN_INTERVAL_S, s),
                "内置语音最小间隔（秒，默认 8）：同一女仆两次播放内置语音之间的最小间隔，防连续系统消息刷屏轰炸"));
        this.rows.add(new BoolRow("播放时暂压原生语音包", MaidSmartConfig.TTS_JAR_PACK_MUTE_NATIVE.get(),
                v -> MaidSmartConfig.TTS_JAR_PACK_MUTE_NATIVE.set(v),
                "播放时暂压原生语音包（默认开）：内置语音播放期间，TLM 原生语音包（女仆音效/语音）暂时静音，播放结束自动解除——避免两套语音重叠"));
    }

    /** v1.5.294：被动技能独立栏（反馈："被动技能要单拉出来一栏放在 Promaid 模组详细
     *  配置里面，而不是放在战斗自保里面"）——落地水/岩浆逃生放水/主人死亡传送，
     *  全是被动保命动作，与战斗自保页的主动行为（自保策略/贴身辅助/单兵战术）分离 */
    /**
     * v1.2.2 实测五百八十一【空袭数值】：原本硬编码在 {@code MaidFlightCombatBehavior} 里的
     * 空袭数值全部改为配置项，集中到这一页（需求原文：「关于空袭等各项数值也要有一个详细的
     * 配置面板，在模组详细配置。」）。**默认值与原常量一字不差**——不改任何行为，只是把
     * 原来只能改代码的旋钮交给玩家；飞行链路本身的取舍（为什么背离起飞、为什么收翅才吃加成）
     * 仍写在各个数值的注释里，出处见该类同名访问器的 javadoc。
     */
    private void airRaidRows() {
        // v1.5.397 实测八百五十四：空袭总开关挪到本页第一条。需求：「开启 DIY 之后会直接导致空袭
        // 彻底无法启动。配置项目里面有没有相关开关……扫帚模式好歹还有个开机开关呢。」——扫帚页的
        // 第一条就是「扫帚模式·总开关」，空袭却把总开关放在「主动参战」页、还叫「飞行作战」，
        // 按“空袭”找不到。这里与扫帚页同款：分区标题点名 TLM 任务，紧跟着就是总开关。
        this.rows.add(new SectionRow("—— 空袭（TLM 任务：maid_smart:flight_combat / maid_smart:flight_ranged）——", false));
        this.rows.add(new BoolRow("空袭·总开关（飞行作战）",
                MaidSmartConfig.COMBAT_FLIGHT_MODE.get(),
                v -> MaidSmartConfig.COMBAT_FLIGHT_MODE.set(v),"空袭·总开关（飞行作战，1.20.1）：近战/远程空袭的总闸；关掉 = 两个任务都不工作。三件套 = 鞘翅 + 任意近战武器 + 烟花火箭，缺件等同普通攻击。滑翔中禁止传送。"));
        this.rows.add(new SectionRow("空袭数值（原硬编码常量，默认值未改）", false));
        this.rows.add(new SectionRow("① 起飞与爬升", true));
        this.rows.add(new NumRow("起飞段时长·近战（tick）", String.valueOf(MaidSmartConfig.AIR_RAID_LAUNCH_TICKS_MELEE.get()),
                s -> setInt(MaidSmartConfig.AIR_RAID_LAUNCH_TICKS_MELEE, s), "起飞段时长·近战（tick，默认 30 = 1.5 秒）：放烟花后维持「背离敌人 + 抬头」的时长——近战必须爬够高度才维持得住滑翔，调短 = 更早转向敌人但爬得矮、贴地风险大"));
        this.rows.add(new NumRow("起飞段时长·远程（tick）", String.valueOf(MaidSmartConfig.AIR_RAID_LAUNCH_TICKS_RANGED.get()),
                s -> setInt(MaidSmartConfig.AIR_RAID_LAUNCH_TICKS_RANGED, s), "起飞段时长·远程（tick，默认 20 = 1 秒）：远战只求盘旋高度、不吃俯冲——调大 = 爬得更高、更安全但起手更慢"));
        this.rows.add(new NumRow("起飞仰角·近战（正切值）", String.valueOf(MaidSmartConfig.AIR_RAID_LAUNCH_CLIMB_TAN_MELEE.get()),
                s -> setDouble(MaidSmartConfig.AIR_RAID_LAUNCH_CLIMB_TAN_MELEE, s), "起飞仰角正切·近战（默认 1.88 ≈ 62°）：1 = 45°、2.75 ≈ 70°。越低越平飞、越高越直上；太低会「一放烟花就往敌人方向压头」然后贴地"));
        this.rows.add(new NumRow("起飞仰角·远程（正切值）", String.valueOf(MaidSmartConfig.AIR_RAID_LAUNCH_CLIMB_TAN_RANGED.get()),
                s -> setDouble(MaidSmartConfig.AIR_RAID_LAUNCH_CLIMB_TAN_RANGED, s), "起飞仰角正切·远程（默认 1.0 = 45°）：远战起飞只要够悬停高度，压低仰角能更早进入盘旋开火"));
        this.rows.add(new NumRow("起飞触发距离（格）", String.valueOf(MaidSmartConfig.AIR_RAID_LAUNCH_RANGE.get()),
                s -> setDouble(MaidSmartConfig.AIR_RAID_LAUNCH_RANGE, s), "地面重新起飞的最大水平距离（格，默认 20）：超出先跑近再起飞（防越炸越远）。只算水平距离——敌人站在高处不影响这条"));
        this.rows.add(new NumRow("占位高度容差（格）", String.valueOf(MaidSmartConfig.AIR_RAID_ALTITUDE_TOLERANCE.get()),
                s -> setDouble(MaidSmartConfig.AIR_RAID_ALTITUDE_TOLERANCE, s), "占位高度容差（格，默认 10）：她比目标低不超过这么多格就算「已经到位」，直接开打不再爬高。这个值同时也是起飞朝向的判据"));
        this.rows.add(new NumRow("起跳等待上限（tick）", String.valueOf(MaidSmartConfig.AIR_RAID_JUMP_TICKS.get()),
                s -> setInt(MaidSmartConfig.AIR_RAID_JUMP_TICKS, s), "起跳等待上限（tick，默认 3）：起跳失败（头顶有方块 / 低矮空间）超过这么久就放弃本轮空袭"));
        this.rows.add(new NumRow("烟花最小间隔（tick）", String.valueOf(MaidSmartConfig.AIR_RAID_FIREWORK_COOLDOWN.get()),
                s -> setInt(MaidSmartConfig.AIR_RAID_FIREWORK_COOLDOWN, s), "烟花最小间隔（tick，默认 30 = 1.5 秒）：调小 = 更频繁点火（燃料消耗快）、调大 = 更省烟花但掉速掉高更明显"));
        this.rows.add(new NumRow("羽扇最小间隔（tick）", String.valueOf(MaidSmartConfig.AIR_RAID_FAN_COOLDOWN.get()),
                s -> setInt(MaidSmartConfig.AIR_RAID_FAN_COOLDOWN, s), "羽扇最小间隔（tick，默认 20 = 1 秒，与原版挥扇动作时长一致）：扇子不消耗、只受这条限制"));
        this.rows.add(new SectionRow("② 收翅俯冲（近战空袭）", true));
        this.rows.add(new NumRow("收翅俯冲触发距离（格）", String.valueOf(MaidSmartConfig.AIR_RAID_SMASH_RANGE.get()),
                s -> setDouble(MaidSmartConfig.AIR_RAID_SMASH_RANGE, s), "收翅俯冲触发距离（格，默认 3.5）：进到这么近就收翅砸下去。调大 = 更早收翅（砸得更重但更容易砸空）"));
        this.rows.add(new NumRow("猛击命中判定距离（格）", String.valueOf(MaidSmartConfig.AIR_RAID_SMASH_HIT_RANGE.get()),
                s -> setDouble(MaidSmartConfig.AIR_RAID_SMASH_HIT_RANGE, s), "猛击命中判定距离（格，默认 4.0）：按本 tick 的位移线段判定（不是瞬时点），擦身而过也算命中；调大 = 更容易打中"));
        this.rows.add(new NumRow("范围强制命中半径（格）", String.valueOf(MaidSmartConfig.AIR_RAID_FORCED_HIT_RADIUS.get()),
                s -> setDouble(MaidSmartConfig.AIR_RAID_FORCED_HIT_RADIUS, s), "范围强制命中半径（格，默认 2.5）：身边这个范围内的其它敌对目标也一起吃一下猛击（防贴身小怪判不到）"));
        this.rows.add(new NumRow("猛击段最长（tick）", String.valueOf(MaidSmartConfig.AIR_RAID_SMASH_MAX_TICKS.get()),
                s -> setInt(MaidSmartConfig.AIR_RAID_SMASH_MAX_TICKS, s), "猛击段最长（tick，默认 20 = 1 秒）：这么久还没打到人就按打空收尾、切回滑翔继续盘旋"));
        this.rows.add(new NumRow("地面近战触及距离（格）", String.valueOf(MaidSmartConfig.AIR_RAID_MELEE_REACH.get()),
                s -> setDouble(MaidSmartConfig.AIR_RAID_MELEE_REACH, s), "地面近战触及距离（格，默认 3.0）：地面退路用的近战判定距离，超出先走位靠近"));
        this.rows.add(new NumRow("俯仰限幅·抬头（度）", String.valueOf(MaidSmartConfig.AIR_RAID_MAX_PITCH_UP.get()),
                s -> setDouble(MaidSmartConfig.AIR_RAID_MAX_PITCH_UP, s), "俯仰限幅·抬头（度，默认 55）：飞向目标时最多抬头多少——调大 = 爬升更陡"));
        this.rows.add(new NumRow("俯仰限幅·低头（度）", String.valueOf(MaidSmartConfig.AIR_RAID_MAX_PITCH_DOWN.get()),
                s -> setDouble(MaidSmartConfig.AIR_RAID_MAX_PITCH_DOWN, s), "俯仰限幅·低头（度，默认 70）：最多低头多少，接近 90 = 允许垂直扎下去"));
        this.rows.add(new SectionRow("③ 盘旋与补高（远程空袭）", true));
        this.rows.add(new NumRow("盘旋半径（格）", String.valueOf(MaidSmartConfig.AIR_RAID_ORBIT_RADIUS.get()),
                s -> setDouble(MaidSmartConfig.AIR_RAID_ORBIT_RADIUS, s),"远程空袭的盘旋半径（格，默认 10）：以目标为圆心保持的距离，也是高度修正环的基准圈。它现在是随机区间的近端——半径在它与「离敌最远距离」之间缓动，每只不一样。"));
        this.rows.add(new NumRow("离敌最远距离（格）", String.valueOf(MaidSmartConfig.AIR_RAID_ORBIT_MAX.get()),
                s -> setDouble(MaidSmartConfig.AIR_RAID_ORBIT_MAX, s),"离敌最远距离（默认 12，2~64）：锁敌后盘旋半径在 [min(本值, 盘旋半径×0.75), 本值] 内随机缓动、每 4 秒重掷，越界加倍往回带。它不是「有效开火距离」，一般应 ≤ 开火距离。"));
        this.rows.add(new NumRow("离敌最近距离（格）", String.valueOf(MaidSmartConfig.AIR_RAID_MIN_STANDOFF.get()),
                this.setDoubleInRange(MaidSmartConfig.AIR_RAID_MIN_STANDOFF, "离敌最近距离", 0.0, 32.0),"离敌最近距离（默认 6.0，0~32，0 = 关闭）：盘旋半径近端取 max(盘旋半径×0.75, 本值)，绝不会更近；近战空袭「先拉开再俯冲」也用本值（按 3D 距离、一轮只拉开一次）。"));
        this.rows.add(new NumRow("离敌最低高度（格）", String.valueOf(MaidSmartConfig.AIR_RAID_MIN_ABOVE_HEIGHT.get()),
                this.setDoubleInRange(MaidSmartConfig.AIR_RAID_MIN_ABOVE_HEIGHT, "离敌最低高度", 0.0, 64.0),"离敌最低高度（格，默认 8.0，0~64，0 = 关闭）：远程空袭盘旋的硬地板——与目标的高度差任何时候都不低于本值；低于地板时俯仰直接钉成最大抬头、并把「掉高补推」门槛提到本值。调大更安全更费燃料。"));
        this.rows.add(new NumRow("期望盘旋高度（目标上方格数）", String.valueOf(MaidSmartConfig.AIR_RAID_RANGED_HOLD_HEIGHT.get()),
                s -> setDouble(MaidSmartConfig.AIR_RAID_RANGED_HOLD_HEIGHT, s), "期望盘旋高度（目标上方格数，默认 10）：低于这条带就补高度——远程空袭要的就是脚不沾地，掉下去就是被贴脸"));
        this.rows.add(new NumRow("高度修正增益", String.valueOf(MaidSmartConfig.AIR_RAID_RANGED_HOLD_GAIN.get()),
                s -> setDouble(MaidSmartConfig.AIR_RAID_RANGED_HOLD_GAIN, s), "高度修正增益（默认 5.0）：高度误差换算成俯仰角的比例——调大 = 更急着回到期望高度，容易起伏"));
        this.rows.add(new NumRow("高度修正偏置（格）", String.valueOf(MaidSmartConfig.AIR_RAID_RANGED_HOLD_BIAS.get()),
                s -> setDouble(MaidSmartConfig.AIR_RAID_RANGED_HOLD_BIAS, s), "高度修正偏置（格，默认 1.0）：给误差加一点正偏置，默认略偏高于期望高度、留安全余量"));
        this.rows.add(new NumRow("掉高容差（格）", String.valueOf(MaidSmartConfig.AIR_RAID_RANGED_BOOST_DROP.get()),
                s -> setDouble(MaidSmartConfig.AIR_RAID_RANGED_BOOST_DROP, s), "掉高容差（格，默认 0.5）：掉出期望高度带这么多格就补推。调大 = 更省燃料但高度起伏更大"));
        this.rows.add(new NumRow("盘旋抬头上限（度）", String.valueOf(MaidSmartConfig.AIR_RAID_RANGED_ORBIT_UP_MAX.get()),
                s -> setDouble(MaidSmartConfig.AIR_RAID_RANGED_ORBIT_UP_MAX, s), "盘旋抬头上限（度，默认 45）：高度修正最多抬头多少"));
        this.rows.add(new NumRow("盘旋低头上限（度）", String.valueOf(MaidSmartConfig.AIR_RAID_RANGED_ORBIT_DOWN_MAX.get()),
                s -> setDouble(MaidSmartConfig.AIR_RAID_RANGED_ORBIT_DOWN_MAX, s), "盘旋低头上限（度，默认 35）：高度修正最多低头多少——低头会掉速，所以默认比抬头上限小"));
        this.rows.add(new NumRow("掉高补推间隔（tick）", String.valueOf(MaidSmartConfig.AIR_RAID_RANGED_BOOST_INTERVAL.get()),
                s -> setInt(MaidSmartConfig.AIR_RAID_RANGED_BOOST_INTERVAL, s), "掉高补推间隔（tick，默认 100 = 5 秒）：两次补推之间的最短间隔，调小 = 高度更稳但更耗燃料/法术"));
        this.rows.add(new NumRow("补推抬头窗口（tick）", String.valueOf(MaidSmartConfig.AIR_RAID_RANGED_BOOST_AIM_TICKS.get()),
                s -> setInt(MaidSmartConfig.AIR_RAID_RANGED_BOOST_AIM_TICKS, s), "补推抬头窗口（tick，默认 10 = 0.5 秒）：补推后维持抬头朝目标这么久，推力吃完才回盘旋朝向"));
        this.rows.add(new NumRow("补推仰角（度）", String.valueOf(MaidSmartConfig.AIR_RAID_RANGED_BOOST_PITCH.get()),
                s -> setDouble(MaidSmartConfig.AIR_RAID_RANGED_BOOST_PITCH, s), "补推仰角（度，默认 -45 = 抬头 45°）：掉高时朝目标抬头多少——负数表示抬头"));
        this.rows.add(new BoolRow("掉高补推·用激流三叉戟", MaidSmartConfig.AIR_RAID_RANGED_BOOST_RIPTIDE.get(),
                v -> MaidSmartConfig.AIR_RAID_RANGED_BOOST_RIPTIDE.set(v),"掉高补推·用激流三叉戟（默认开）：盘旋掉出高度带时，先把机头抬到「补推仰角」再沿视线推原版那一口。只扣 1 点耐久、不消耗物资，排在烟花之前；总开关是「激流三叉戟旋转突进」。"));
        this.rows.add(new SectionRow("④ 远程开火", true));
        this.rows.add(new NumRow("远程开火基础间隔（tick）", String.valueOf(MaidSmartConfig.AIR_RAID_RANGED_SHOT_COOLDOWN.get()),
                s -> setInt(MaidSmartConfig.AIR_RAID_RANGED_SHOT_COOLDOWN, s), "远程开火基础间隔（tick，默认 20 = 1 秒）：弓弩的基础射速（快速装填会按比例缩短）；枪械用枪械模组自己的射速"));
        this.rows.add(new NumRow("远程射程（格，弓弩）", String.valueOf(MaidSmartConfig.AIR_RAID_RANGED_ATTACK_RANGE.get()),
                s -> setDouble(MaidSmartConfig.AIR_RAID_RANGED_ATTACK_RANGE, s), "远程射程（格，默认 24）：弓弩的射程与锁敌上限（枪械走枪械模组自己的射程）"));
        this.rows.add(new NumRow("有效开火距离（格）", String.valueOf(MaidSmartConfig.AIR_RAID_RANGED_FIRE_RANGE.get()),
                s -> setDouble(MaidSmartConfig.AIR_RAID_RANGED_FIRE_RANGE, s),"有效开火距离（格，默认 24，0 = 不限）：到她目标的 3D 距离小于这一条才扣扳机，超出只盘旋不开火。枪械模组射程比弓弩大一倍，旧版 40 格外一路点射基本打不中；锁敌 50 格不变。"));
        this.rows.add(new SectionRow("⑤ 近身弹开", true));
        this.rows.add(new NumRow("弹开触发半径（格）", String.valueOf(MaidSmartConfig.AIR_RAID_RANGED_PUSH_RADIUS.get()),
                s -> setDouble(MaidSmartConfig.AIR_RAID_RANGED_PUSH_RADIUS, s), "弹开触发半径（格，默认 3）：怪物贴到这么近就触发近身弹开（总开关在「落地缓冲」页）"));
        this.rows.add(new NumRow("弹开水平速度", String.valueOf(MaidSmartConfig.AIR_RAID_RANGED_PUSH_SPEED.get()),
                s -> setDouble(MaidSmartConfig.AIR_RAID_RANGED_PUSH_SPEED, s), "弹开水平速度（默认 0.55）：弹开瞬间给她的水平速度，调大 = 脱得更远"));
        this.rows.add(new NumRow("弹开抬升速度", String.valueOf(MaidSmartConfig.AIR_RAID_RANGED_PUSH_UP.get()),
                s -> setDouble(MaidSmartConfig.AIR_RAID_RANGED_PUSH_UP, s), "弹开抬升速度（默认 0.25）：弹开时同时抬一点高度，防弹开途中继续掉"));
        this.rows.add(new NumRow("弹开保持（tick）", String.valueOf(MaidSmartConfig.AIR_RAID_RANGED_PUSH_TICKS.get()),
                s -> setInt(MaidSmartConfig.AIR_RAID_RANGED_PUSH_TICKS, s), "弹开保持（tick，默认 30 = 1.5 秒）：这段时间内持续施加弹开速度"));
        this.rows.add(new SectionRow("⑥ 位移法术（需装《车万女仆：万法皆通》）", true));
        this.rows.add(new NumRow("冲刺最小距离（格）", String.valueOf(MaidSmartConfig.AIR_RAID_DASH_BOOST_MIN_RANGE.get()),
                s -> setDouble(MaidSmartConfig.AIR_RAID_DASH_BOOST_MIN_RANGE, s), "【提供速度】法术的最小施放距离（格，默认 6）：太近不冲，防冲过头扎进敌人身上"));
        this.rows.add(new NumRow("冲刺最大距离（格）", String.valueOf(MaidSmartConfig.AIR_RAID_DASH_BOOST_MAX_RANGE.get()),
                s -> setDouble(MaidSmartConfig.AIR_RAID_DASH_BOOST_MAX_RANGE, s), "【提供速度】法术的最大施放距离（格，默认 28）：太远不冲——冲刺是续速手段，不是追击手段"));
        this.rows.add(new SectionRow("⑥.5 俯冲段冲刺加速", true));
        this.rows.add(new BoolRow("俯冲段冲刺加速", MaidSmartConfig.AIR_RAID_DIVE_BOOST.get(),
                v -> MaidSmartConfig.AIR_RAID_DIVE_BOOST.set(v),"俯冲段冲刺加速（默认开）：近战空袭俯冲那一段按节奏补一口推进、方向不变（朝敌人），缩短一轮「起飞→俯冲」的周期。滑翔中点烟花给原版推力；羽扇只借动作（自带升力会把俯冲顶平）。"));
        this.rows.add(new NumRow("俯冲段冲刺间隔（tick）", String.valueOf(MaidSmartConfig.AIR_RAID_DIVE_BOOST_INTERVAL.get()),
                s -> setInt(MaidSmartConfig.AIR_RAID_DIVE_BOOST_INTERVAL, s), "俯冲段冲刺间隔（tick，默认 30 = 1.5 秒）：两次冲刺之间的最短间隔。俯冲段本身只有 1 秒上下，所以一轮通常吃得到一口；调小 = 一轮能吃几口、冲得更猛（更费烟花）"));
        this.rows.add(new NumRow("俯冲段冲刺·最近距离（格）", String.valueOf(MaidSmartConfig.AIR_RAID_DIVE_BOOST_MIN_RANGE.get()),
                s -> setDouble(MaidSmartConfig.AIR_RAID_DIVE_BOOST_MIN_RANGE, s), "俯冲段冲刺·最近距离（格，默认 5）：比这更近就不冲——已经贴脸了，再冲会直接穿过目标（而且再两 tick 就进收翅猛击段了）"));
        this.rows.add(new NumRow("俯冲段冲刺·最远距离（格）", String.valueOf(MaidSmartConfig.AIR_RAID_DIVE_BOOST_MAX_RANGE.get()),
                s -> setDouble(MaidSmartConfig.AIR_RAID_DIVE_BOOST_MAX_RANGE, s), "俯冲段冲刺·最远距离（格，默认 40）：比这更远就不冲（那是「还没到位」，该走的链路是爬升/盘旋）。默认 40 覆盖「从高空扑到地面」的常见落差"));
        this.rows.add(new NumRow("俯冲段冲刺·一口速度", String.valueOf(MaidSmartConfig.AIR_RAID_DIVE_BOOST_IMPULSE.get()),
                s -> setDouble(MaidSmartConfig.AIR_RAID_DIVE_BOOST_IMPULSE, s), "俯冲段冲刺·一口补多少速度（格/tick，默认 0.55）：只在羽扇那一路用到——烟花与法术各有自己的冲量，这里是借扇子动作时由本模组补的那一口（方向不变、只加大小、不含竖直升力）"));
        this.rows.add(new BoolRow("俯冲段冲刺·用烟花", MaidSmartConfig.AIR_RAID_DIVE_BOOST_FIREWORK.get(),
                v -> MaidSmartConfig.AIR_RAID_DIVE_BOOST_FIREWORK.set(v),
                "俯冲段冲刺·用烟花（默认开）：俯冲途中真的点一枚挂载烟花——消耗 1 枚，推力由原版给（滑翔中生效、沿视线 = 朝着敌人，方向不变），并照旧让副手亮一下烟花模型、放点火音效"));
        this.rows.add(new BoolRow("俯冲段冲刺·用羽扇", MaidSmartConfig.AIR_RAID_DIVE_BOOST_FAN.get(),
                v -> MaidSmartConfig.AIR_RAID_DIVE_BOOST_FAN.set(v),"俯冲段冲刺·用羽扇（默认关）：挥扇换一口加速——挥臂/音效/推开贴脸怪/扣耐久照旧，但不用它那一式推力（自带升力会把俯冲顶成平飞），速度由本模组按「一口速度」给。优先级：法术→激流→烟花→羽扇。"));
        this.rows.add(new BoolRow("俯冲段冲刺·用激流三叉戟", MaidSmartConfig.AIR_RAID_DIVE_BOOST_RIPTIDE.get(),
                v -> MaidSmartConfig.AIR_RAID_DIVE_BOOST_RIPTIDE.set(v),"俯冲段冲刺·用激流三叉戟（默认开）：俯冲途中挥三叉戟换一口加速，方向不变（朝下扎更快），力度 = 原版那一记 ×1.3，动作同原版；只扣 1 点耐久，排在烟花之前。总开关「激流三叉戟旋转突进」。"));
        this.rows.add(new SectionRow("⑦ 空袭轰炸", true));
        this.rows.add(new SectionRow("近战空袭：打完那一记之后、再次起飞之前放炸弹", false));
        this.rows.add(new BoolRow("战斗模式轰炸", MaidSmartConfig.COMBAT_BOMBING_MELEE.get(),
                v -> MaidSmartConfig.COMBAT_BOMBING_MELEE.set(v),"战斗模式轰炸（默认开）：所有攻击模式打完一记就按包里材料放炸弹——① 黑曜石/基岩 + 末地水晶（威力 6）② 重生锚 + 萤石（下界不生效）③ 床（主世界不生效）。"));
        this.rows.add(new NumRow("轰炸最短间隔（tick）", String.valueOf(MaidSmartConfig.COMBAT_BOMBING_BOMB_INTERVAL.get()),
                s -> setInt(MaidSmartConfig.COMBAT_BOMBING_BOMB_INTERVAL, s), "轰炸最短间隔（tick，默认 200 = 10 秒）：整条轰炸链路两次之间的下限（打完一记才放，这条只当下限）——不加下限会在几秒内烧光她的黑曜石/水晶；缺料那一下不占用间隔"));
        this.rows.add(new NumRow("起爆延迟（tick）", String.valueOf(MaidSmartConfig.COMBAT_BOMBING_FUSE.get()),
                s -> setInt(MaidSmartConfig.COMBAT_BOMBING_FUSE, s), "起爆延迟（tick，默认 10 = 0.5 秒）：放下之后多久响；这半秒正好够她重新起飞"));
        this.rows.add(new NumRow("放置间隔（tick）", String.valueOf(MaidSmartConfig.COMBAT_BOMBING_PLACE_GAP.get()),
                s -> setInt(MaidSmartConfig.COMBAT_BOMBING_PLACE_GAP, s), "放置间隔（tick，默认 10 = 0.5 秒）：先放下方块、停这么久再挂水晶/充能——不然两步同一瞬间完成，看不出中间有过动作"));
        this.rows.add(new BoolRow("副手动作表现", MaidSmartConfig.COMBAT_BOMBING_POSE.get(),
                v -> MaidSmartConfig.COMBAT_BOMBING_POSE.set(v),"副手动作表现（默认开）：放置/充能/投掷/起爆，以及搭路/火把/建造/种植/酿造/喂主人时，副手短暂举起她正在用的那一件（扔 TNT 时举的是打火石）；关 = 副手全程不被换，只剩挥臂/音效/爆炸。"));
        this.rows.add(new BoolRow("爆炸火焰改粉色", MaidSmartConfig.COMBAT_BOMBING_PINK_FIRE.get(),
                v -> MaidSmartConfig.COMBAT_BOMBING_PINK_FIRE.set(v),"爆炸火焰改粉色（默认开）：重生锚 / 床那一炸按原版口径留下的火换成粉色火（粉色贴图 + 火星，不蔓延、几秒自灭）；关 = 保持原版橙色火。末地水晶与 TNT 原版就不留火。"));
        this.rows.add(new BoolRow("粉色火焰渲染", MaidSmartConfig.COMBAT_BOMBING_FIRE_RENDER.get(),
                v -> MaidSmartConfig.COMBAT_BOMBING_FIRE_RENDER.set(v),
                "粉色火焰渲染（默认开）：关掉之后那团火还在原地（熄灭与伤害判定照旧），只是不画出来；想彻底不要火请关上面那条『爆炸火焰改粉色』"));
        this.rows.add(new BoolRow("火焰伤害保护", MaidSmartConfig.COMBAT_BOMBING_FIRE_PROTECT.get(),
                v -> MaidSmartConfig.COMBAT_BOMBING_FIRE_PROTECT.set(v),
                "火焰伤害保护（默认开）：女仆炸出来的粉色火对玩家与女仆完全无效（既不点燃也不掉血），其它生物照常被烧；关掉 = 照原版口径烧人"));
        this.rows.add(new SectionRow("所有战斗模式：TNT 投掷（推广自「女仆生存」那套）", false));
        this.rows.add(new BoolRow("战斗模式投掷 TNT", MaidSmartConfig.COMBAT_BOMBING_TNT.get(),
                v -> MaidSmartConfig.COMBAT_BOMBING_TNT.set(v),"战斗模式的 TNT 投掷（默认开）：各战斗模式朝最近敌人扔 TNT，需同时有 TNT 与点火料；注册名带 tnt 的、方块继承 TntBlock 的都算，认哪件就放它那一枚；对主人与友军不生效。"));
        this.rows.add(new NumRow("投掷间隔（tick）", String.valueOf(MaidSmartConfig.COMBAT_BOMBING_TNT_INTERVAL.get()),
                s -> setInt(MaidSmartConfig.COMBAT_BOMBING_TNT_INTERVAL, s), "投掷最短间隔（tick，默认 200 = 10 秒）：TNT 已改为【攻击链路末段】投放（打完一记之后才扔），这条只是两次投放之间的下限；调小 = 更凶更费 TNT"));
        this.rows.add(new NumRow("TNT 引信（tick）", String.valueOf(MaidSmartConfig.COMBAT_BOMBING_TNT_FUSE.get()),
                s -> setInt(MaidSmartConfig.COMBAT_BOMBING_TNT_FUSE, s), "投掷 TNT 的引信（tick，默认 40 = 2 秒）：扔出去到爆炸的时间"));
        this.rows.add(new NumRow("投掷初速（格/tick）", String.valueOf(MaidSmartConfig.COMBAT_BOMBING_TNT_SPEED.get()),
                s -> setDouble(MaidSmartConfig.COMBAT_BOMBING_TNT_SPEED, s), "投掷初速（格/tick，默认 0.9）：调大飞得更快更直，调小抛物线更明显"));
        this.rows.add(new BoolRow("TNT 追踪飞行", MaidSmartConfig.COMBAT_BOMBING_TNT_TRACK.get(),
                v -> MaidSmartConfig.COMBAT_BOMBING_TNT_TRACK.set(v),
                "TNT 追踪飞行（默认开）：离手后一小段时间里朝目标拐一点弯——只改方向、速度不变；关 = 纯抛物线"));
        this.rows.add(new NumRow("TNT 追踪时长（tick）", String.valueOf(MaidSmartConfig.COMBAT_BOMBING_TNT_TRACK_TICKS.get()),
                s -> setInt(MaidSmartConfig.COMBAT_BOMBING_TNT_TRACK_TICKS, s), "TNT 追踪时长（tick，默认 10 = 0.5 秒，0 = 不追踪）：只在这段时间里修正方向，之后直飞；每 tick 最多转 5 度，所以是平滑小弧线（旧版全程硬掰，显得鬼畜）"));
        this.rows.add(new NumRow("投掷索敌半径（格）", String.valueOf(MaidSmartConfig.COMBAT_BOMBING_TNT_RANGE.get()),
                s -> setDouble(MaidSmartConfig.COMBAT_BOMBING_TNT_RANGE, s), "投掷索敌半径（格，默认 12）：战斗任务下自动找这么近的敌人扔 TNT（照《女仆生存》的 12 格）"));
        this.rows.add(new NumRow("残血连投阈值（0-1）", String.valueOf(MaidSmartConfig.COMBAT_BOMBING_TNT_BURST_RATIO.get()),
                s -> setDouble(MaidSmartConfig.COMBAT_BOMBING_TNT_BURST_RATIO, s), "残血连投阈值（默认 0.7 = 七成血以下）：血量比例低于它就一次连投数发"));
        this.rows.add(new NumRow("连投最多（发）", String.valueOf(MaidSmartConfig.COMBAT_BOMBING_TNT_BURST_COUNT.get()),
                s -> setInt(MaidSmartConfig.COMBAT_BOMBING_TNT_BURST_COUNT, s), "连投最多几发（默认 3，1 = 关掉连投）：每发各消耗 1 个 TNT 与 1 份点火料（打火石掉 1 点耐久 / 烈焰弹消耗 1 个）"));
        this.rows.add(new NumRow("炸弹底座回收延迟（秒）", String.valueOf(MaidSmartConfig.COMBAT_BOMBING_RECLAIM_SECONDS.get()),
                s -> setInt(MaidSmartConfig.COMBAT_BOMBING_RECLAIM_SECONDS, s),"炸弹底座回收延迟（秒，默认 10，0 = 起爆即回收）：水晶链路那块黑曜石/基岩起爆后留在原地这么久，再收进她背包——背包满则掉在脚下；重生锚/床由自己那一炸消耗，不进这张表。"));
        this.rows.add(new SectionRow("放置与材料", false));
        this.rows.add(new BoolRow("重生锚需要萤石", MaidSmartConfig.COMBAT_BOMBING_ANCHOR_NEEDS_GLOWSTONE.get(),
                v -> MaidSmartConfig.COMBAT_BOMBING_ANCHOR_NEEDS_GLOWSTONE.set(v),"重生锚需要萤石（默认开 = 原版口径）：0 级充能不炸，炸前要 1 颗萤石顶到 1 级。链路是「放锚→副手换萤石→充能→0.5 秒后挥臂」；关掉 = 不消耗萤石，她直接补上那 1 级。"));
        this.rows.add(new BoolRow("维度闸（原版会炸才开）", MaidSmartConfig.COMBAT_BOMBING_DIMENSION_GUARD.get(),
                v -> MaidSmartConfig.COMBAT_BOMBING_DIMENSION_GUARD.set(v),"维度闸（默认开）：只在「原版会炸」的维度开放重生锚/床链路——重生锚看 respawnAnchorWorks（下界不炸）、床看 bedWorks（主世界不炸），模组维度按自己设定。关 = 只看材料。"));
        this.rows.add(new BoolRow("空中强制放置", MaidSmartConfig.COMBAT_BOMBING_AIR_PLACE.get(),
                v -> MaidSmartConfig.COMBAT_BOMBING_AIR_PLACE.set(v),
                "空中强制放置（默认开）：空袭时先找目标脚边、再找她正下方；都没有支撑面就直接悬空放下（原版放置本身允许，玩家手点不到而已）；关 = 找不到带支撑的落点就整段跳过"));
        this.rows.add(new BoolRow("空中悬空投弹（走后门）", MaidSmartConfig.COMBAT_BOMBING_AIR_DROP.get(),
                v -> MaidSmartConfig.COMBAT_BOMBING_AIR_DROP.set(v),"空中悬空投弹（走后门，默认开）：落点的最后一级——目标悬空时，把落点直接取在目标头顶那一格/目标那一格，不要求支撑面，0.5 秒后开花；那格站着别人则跳过。关 = 只按原版规则落点。"));
        this.rows.add(new SectionRow("爆炸口径（四类炸弹共用）", false));
        this.rows.add(new BoolRow("破坏方块", MaidSmartConfig.COMBAT_BOMBING_BREAK_BLOCKS.get(),
                v -> MaidSmartConfig.COMBAT_BOMBING_BREAK_BLOCKS.set(v),"轰炸破坏方块（默认关）：关 = 只炸伤害与击退、不动地形；开 = 原版爆炸照原样炸坑。她自己扔的模组 TNT 也归这条管（威力与带不带火仍归它自己）；女仆自己放的方块无论开关都由她回收。"));
        this.rows.add(new BoolRow("伤到主人/友军", MaidSmartConfig.COMBAT_BOMBING_HURT_FRIENDLY.get(),
                v -> MaidSmartConfig.COMBAT_BOMBING_HURT_FRIENDLY.set(v),"轰炸伤到主人/友军（默认关）：关 = 主人与同主女仆不掉血也不被震（归因给女仆，走友伤守卫 + 风免），她自己扔的模组 TNT 那一炸也算在内；开 = 原版爆炸，主人照掉血照被炸飞。"));
        this.rows.add(new BoolRow("淡粉色标记（客户端）", MaidSmartConfig.COMBAT_BOMBING_PINK_MARK.get(),
                v -> MaidSmartConfig.COMBAT_BOMBING_PINK_MARK.set(v),
                "女仆放置物的淡粉色标记（默认开）：她刚放下的黑曜石/重生锚/床、刚挂上的末地水晶、刚扔出的 TNT 会套一层很淡的粉色描边与填充（纯客户端渲染）"));
        this.rows.add(new SectionRow("第三方玩法模式", false));
        this.rows.add(new BoolRow("傀儡模式黑名单", MaidSmartConfig.COMPAT_PUPPET_BLACKLIST.get(),
                v -> MaidSmartConfig.COMPAT_PUPPET_BLACKLIST.set(v),"傀儡模式黑名单（默认开）：检测到《傀儡装配》的女仆模式「傀儡师」时自动列入——自主参战与 LLM 自主切换永不切进去；手动切进去后本模组战术全体让位，只留那个模组自己的玩法，切回来即恢复。"));

    }

    /**
     * 【实测七百〇三】接敌机动——**扫帚接敌 + 鞘翅空袭两条链路共有**的一件事，所以单独成板。
     *
     * <p>【为什么单独一页】
     *
     *
     * [空袭数值] 页都会让另一边的人找不到（面板改革时玩家抱怨过"很难精准定位到哪个功能在哪配置"）。
     */
    private void maneuverRows() {
        this.rows.add(new SectionRow("—— 接敌机动（扫帚接敌 + 鞘翅空袭 共用）——", false));
        this.rows.add(new InfoRow("接敌机动 · 是什么", "\u00a7a每只女仆每场遭遇各抽一种飞行方式，且每 20 秒换一种\u00a7r","五种机动：环绕 24%、蛇形 24%、高悠悠 18%、脱离再进 17%、8 字横切 17%。抽签由 UUID+遭遇+段次 派生——一场仗超 20 秒会换一种（换到不一样的），同场多只各抽各的。"));
        this.rows.add(new InfoRow("环绕 · 方向会掉头", "\u00a78 秒一个周期，随机顺/逆\u00a7r","每 8 秒对半概率决定「继续原方向」还是「翻过来」——她的旋向因此一段一段的，敌人没法按一个方向外推。掉头只改角速度符号、位置连续（不是瞬移到对面），扫帚与远程空袭两条链路共用这一处口径。"));
        this.rows.add(new InfoRow("接敌机动 · 两条硬保证", "\u00a7e高度只升不降 / 半径仍被硬上界夹住\u00a7r","两条硬保证：① 任何机动都不会把她压到「你设的基础盘旋高度」以下；② 倍率都在 ±20% 内，最终半径仍夹在「离敌最近距离」与「离敌最远距离」之间——随机不会越飞越远或贴脸。"));
        this.rows.add(new BoolRow("接敌机动·总开关", MaidSmartConfig.COMBAT_MANEUVER_ENABLE.get(),
                v -> MaidSmartConfig.COMBAT_MANEUVER_ENABLE.set(v),"接敌机动总开关（默认开）：接敌后每只女仆每场遭遇各抽一种飞行方式（每 20 秒换一段），而不是所有女仆都只会绕圈。关掉 = 退回旧行为（永远环绕；半径 / 旋向 / 快慢的随机照旧保留）。"));
        this.rows.add(new NumRow("接敌机动·高悠悠幅度（格）", String.valueOf(MaidSmartConfig.COMBAT_MANEUVER_YOYO_AMP.get()),
                this.setDoubleInRange(MaidSmartConfig.COMBAT_MANEUVER_YOYO_AMP, "接敌机动·高悠悠幅度", 0.0, 16.0),"只给「高悠悠」这一种机动用（默认 4，0~16）：在基础盘旋高度之上再多爬这么多格（0~幅度 格的 8 秒慢波），高处转得慢、低处转得快。它只会把高度加上去、不会压到基准以下。0 = 退化成普通环绕。",
                0.0, 16.0));
        this.rows.add(new BoolRow("空袭·空中防叠罗汉", MaidSmartConfig.COMBAT_AIR_SEPARATION.get(),
                v -> MaidSmartConfig.COMBAT_AIR_SEPARATION.set(v),"空袭·空中防叠罗汉（默认开）：多只女仆接同一个敌人时，远程盘旋起点与近战爬升路线原本重合。打开后：盘旋半径各带稳定偏置，近战爬升方位各偏 ±12°（俯冲瞄准不改）。关掉 = 旧行为。"));
        this.rows.add(new NumRow("空袭·半径偏置强度（格）", String.valueOf(MaidSmartConfig.COMBAT_AIR_SEPARATION_RADIUS.get()),
                this.setDoubleInRange(MaidSmartConfig.COMBAT_AIR_SEPARATION_RADIUS, "空袭·半径偏置强度", 0.0, 8.0),"给每只女仆的盘旋半径各自加减这么多格（默认 2.0，0~8）：只把她们的圈叉开，最终半径仍夹在「离敌最近距离」与「离敌最远距离」之间。0 = 不错开（只保留近战爬升方位的错开）。",
                0.0, 8.0));
    }

    private void fallGuardRows() {
        this.rows.add(new BoolRow("落地水", MaidSmartConfig.COMBAT_WATER_CLUTCH.get(),
                v -> MaidSmartConfig.COMBAT_WATER_CLUTCH.set(v), "落地水（有水桶+坠落自动放水缓冲）"));
        this.rows.add(new NumRow("落地水触发高度", String.valueOf(MaidSmartConfig.COMBAT_WATER_FALL_DISTANCE.get()),
                s -> setDouble(MaidSmartConfig.COMBAT_WATER_FALL_DISTANCE, s), "落地水触发高度（格）：坠落超过此值才放水（这是最小高度闸，防小跳/走路误放）；放水时机是即将落地那一瞬（与重锤/空袭同款）"));
        this.rows.add(new NumRow("落地水保持（tick）", String.valueOf(MaidSmartConfig.COMBAT_WATER_HOLD.get()),
                s -> setInt(MaidSmartConfig.COMBAT_WATER_HOLD, s), "落地水保持（tick）：放出的水保留多久后收回（防留一滩水）"));
        this.rows.add(new NumRow("落地水下探格数", String.valueOf(MaidSmartConfig.COMBAT_WATER_LANDING_SCAN.get()),
                s -> setInt(MaidSmartConfig.COMBAT_WATER_LANDING_SCAN, s), "落地水下探格数：提前向下探测几格判断要不要放水（防高空误放）"));
        // v1.1.0：落地雪——细雪桶版落地水（下界水会蒸发、细雪不会）
        this.rows.add(new BoolRow("落地雪", MaidSmartConfig.COMBAT_SNOW_CLUTCH.get(),
                v -> MaidSmartConfig.COMBAT_SNOW_CLUTCH.set(v),"落地雪（默认开）：高空坠落时在落点平面铺 1×1 细雪垫接住她并收回（桶不消耗）——逐 tick 跟着落点补垫、偏一格即空摔（求稳用水桶）；下界也能用；与落地水共用参数，都有桶时优先水。"));
        this.rows.add(new NumRow("落地雪触发高度",
                String.valueOf(MaidSmartConfig.COMBAT_SNOW_FALL_DISTANCE.get()),
                v -> PromaidConfigScreen.setDouble(MaidSmartConfig.COMBAT_SNOW_FALL_DISTANCE, v),
                "落地雪触发高度（格，默认 4）：累计坠落超过此值才铺雪垫（这是最小高度闸，防小跳/走路误放）；铺雪时机是即将落地那一瞬（与重锤/空袭同款）"));
        this.rows.add(new NumRow("落地雪保持（tick）",
                String.valueOf(MaidSmartConfig.COMBAT_SNOW_HOLD.get()),
                v -> PromaidConfigScreen.setInt(MaidSmartConfig.COMBAT_SNOW_HOLD, v),
                "落地雪保持（tick，默认 5）：铺出的细雪保留多久后收回（上限 100 tick = 5 秒 < 细雪冻伤线 140 tick）"));
        this.rows.add(new NumRow("落地雪下探格数",
                String.valueOf(MaidSmartConfig.COMBAT_SNOW_LANDING_SCAN.get()),
                v -> PromaidConfigScreen.setInt(MaidSmartConfig.COMBAT_SNOW_LANDING_SCAN, v),
                "落地雪下探格数（默认 2）：提前向下探测几格判断要不要铺雪垫（防高空误放）"));
        // v1.5.199：水桶垫水（岩浆灭火，1 秒后收回，水桶不消耗；击退搭高垫水
        // v1.5.250 已删除）
        this.rows.add(new BoolRow("岩浆逃生放水", MaidSmartConfig.COMBAT_WATER_BUCKET_LAVA.get(),
                v -> MaidSmartConfig.COMBAT_WATER_BUCKET_LAVA.set(v), "岩浆逃生放水：垫高后周围没有水源且包里有水桶 → 在自己垫的方块上放水灭火（1 秒后收回；接触的岩浆源可能变黑曜石）"));
        this.rows.add(new BoolRow("免疫鞘翅撞击伤害",
                MaidSmartConfig.COMBAT_FLIGHT_NO_WALL_DAMAGE.get(),
                v -> MaidSmartConfig.COMBAT_FLIGHT_NO_WALL_DAMAGE.set(v),"飞行作战免疫鞘翅撞击伤害（默认开）：滑翔中撞到方块不再受 fly_into_wall 伤害——高速撞墙很容易发生，一撞就掉血会打断连招；关闭 = 恢复原版。只作用于飞行作战任务。"));
        this.rows.add(new BoolRow("空袭免疫摔落伤害",
                MaidSmartConfig.COMBAT_FLIGHT_NO_FALL_DAMAGE.get(),
                v -> MaidSmartConfig.COMBAT_FLIGHT_NO_FALL_DAMAGE.set(v),"空袭免疫摔落伤害（默认开）：两种空袭模式下的女仆完全不受摔落伤害——空袭常态是高空盘旋与收翅俯冲，落地水/雪没接住就是十几点伤害甚至摔死。关闭 = 恢复按落地水/雪保护。"));
        this.rows.add(new BoolRow("飞行危险环境避让",
                MaidSmartConfig.COMBAT_FLIGHT_DANGER_AVOID.get(),
                v -> MaidSmartConfig.COMBAT_FLIGHT_DANGER_AVOID.set(v),"飞行危险环境避让（默认开）：飞行途中把危险表（misc.dangerBlocks）当禁区——要穿进去的航段自动绕开、绕不开抬高爬过；下沉到危险格上方时抬平；攻击动作不受影响。"));
        this.rows.add(new BoolRow("烫伤脱困",
                MaidSmartConfig.COMBAT_HEAT_ESCAPE.get(),
                v -> MaidSmartConfig.COMBAT_HEAT_ESCAPE.set(v),"烫伤脱困（默认开）：飞行途中真的泡进岩浆或被点着时立刻传到最近的空气格（骑扫帚时连扫帚一起搬）；泡水 / 抗火不算，1 秒冷却防抖。关闭 = 飞行中不再有这道保命传送。"));
        this.rows.add(new BoolRow("鞘翅外观",
                MaidSmartConfig.COMBAT_WING_RENDER.get(),
                v -> MaidSmartConfig.COMBAT_WING_RENDER.set(v),"鞘翅外观（默认开）：穿鞘翅（含模组滑翔装备）就画翅膀——站着折叠、滑翔张开，用那件自己的贴图（收录伊卡洛斯之翼与神秘遗物+）；认不出型号退回原版贴图。关闭 = 只在飞行/滑翔时画。"));
        this.rows.add(new BoolRow("鞘翅外观·YSM 让位",
                MaidSmartConfig.COMBAT_WING_YSM_YIELD.get(),
                v -> MaidSmartConfig.COMBAT_WING_YSM_YIELD.set(v),"鞘翅外观·YSM 让位（默认开）：用 YSM 模型时滑翔期间不再叠画我们的翅膀、让位给模型自带那一对（只对原版鞘翅需要；站着走路那档照旧由我们画）。关闭 = 滑翔时两对翅膀叠画。"));
        this.rows.add(new BoolRow("激流三叉戟旋转冲击",
                MaidSmartConfig.RIPTIDE_DASH_ENABLE.get(),
                v -> MaidSmartConfig.RIPTIDE_DASH_ENABLE.set(v),"激流三叉戟旋转冲击（默认开）：攻击模式/空袭下主手拿【激流】三叉戟时，近身 4 格内的普通挥砍换成旋转冲击（旋转 16 tick、撞击结算一次伤害；空中旋转不自我摔伤）。关 = 只当普通三叉戟挥砍。"));
        this.rows.add(new NumRow("激流推进·力度倍数", String.valueOf(MaidSmartConfig.COMBAT_RIPTIDE_FLIGHT_SCALE.get()),
                s -> setDouble(MaidSmartConfig.COMBAT_RIPTIDE_FLIGHT_SCALE, s),"用激流三叉戟当推进剂时的力度倍数：起飞/跟随补推/掉高抬升/俯冲冲刺共用（默认 1.0 = 原版那一记×1.3，范围 0.1~2.0）；近战「旋转冲击」照旧原版矢量，不受本项影响。"));
        // v1.3.0(beta) 实测七百一十【自推鞘翅】：三件套的"推进剂"第五条腿
        this.rows.add(new BoolRow("自推鞘翅", MaidSmartConfig.COMBAT_SELF_WINGS.get(),
                v -> MaidSmartConfig.COMBAT_SELF_WINGS.set(v),"自推鞘翅（默认开）：穿这类模组鞘翅（伊卡洛斯空域系 6 件 / 神秘遗物+ 两件）时不用烟花也能起飞巡航、同时算作推进剂，推力由本模组照该件自己的数值施加。关闭 = 只当会滑翔的胸甲。"));
        this.rows.add(new TextRow("自推鞘翅·资格物品表", String.join(",", (List<String>) MaidSmartConfig.COMBAT_SELF_WINGS_ITEMS.get()),
                s -> setStringList(MaidSmartConfig.COMBAT_SELF_WINGS_ITEMS, s),"自推鞘翅的资格物品表（默认见上）：一行/逗号一条 id（modid:item），也认 #命名空间:标签。只认胸甲槽；认不出型号的走通用推力模型，以后加同类鞘翅填一行 id 即可。"));
        this.rows.add(new NumRow("自推鞘翅·推力倍数", String.valueOf(MaidSmartConfig.COMBAT_SELF_WINGS_SCALE.get()),
                s -> setDouble(MaidSmartConfig.COMBAT_SELF_WINGS_SCALE, s),"自推鞘翅的推力倍数（默认 1.0，0.2~3.0）：乘在「沿视线那一份」上，1.0 = 照抄各件自己的数值（伊卡洛斯空域系 6 件、神秘遗物+ 两件）；只管这一条腿，其余推进手段不受影响。"));
        this.rows.add(new BoolRow("远程空袭近身弹开",
                MaidSmartConfig.COMBAT_FLIGHT_RANGED_PUSH.get(),
                v -> MaidSmartConfig.COMBAT_FLIGHT_RANGED_PUSH.set(v),"远程空袭近身弹开（默认开）：怪物贴到 3 格内时给她一个远离怪物的速度矢量、保持 1.5 秒，防她在远程攻击时往敌人身上飞。只弹开她自己；狭小空间跑不掉，敌人仍有命中机会。关闭 = 贴着怪物盘旋。"));
        // v1.2.0 实测五百四十七：空袭牵引绳（用户指定半径 100 格，0 = 关闭）
        this.rows.add(new NumRow("空袭牵引绳（格）",
                String.valueOf(MaidSmartConfig.COMBAT_FLIGHT_RECALL_DISTANCE.get()),
                s -> setInt(MaidSmartConfig.COMBAT_FLIGHT_RECALL_DISTANCE, s),"空袭牵引绳（格，默认 100，0 = 关闭）：空袭期间她离参照点超过这么多格（3D）就立刻传回——参照点：非守家 = 主人，守家 = 工作区圈心。只在她在空中时生效；触发会给主人发一条消息。"));
        this.rows.add(new BoolRow("弩用普通烟花当弹药",
                MaidSmartConfig.COMBAT_CROSSBOW_PLAIN_FIREWORK.get(),
                v -> MaidSmartConfig.COMBAT_CROSSBOW_PLAIN_FIREWORK.set(v),"弩用普通烟花当弹药（默认关）：普通烟花在原版是 0 伤害，拿它当弩弹药等于白烧一枚燃料；只有带烟火之星的攻击性烟花才当弹药，普通烟花留给飞行推进。打开 = 与原版玩家一致。"));
        // 实测五百六十七（PR #8 移植）：空袭·法术层（需装《车万女仆：万法皆通》）
        this.rows.add(new BoolRow("空袭顺带施法",
                MaidSmartConfig.COMBAT_FLIGHT_SPELL_CAST.get(),
                v -> MaidSmartConfig.COMBAT_FLIGHT_SPELL_CAST.set(v),"空袭顺带施法（默认开，需装法术模组）：空袭途中会朝当前目标顺带施法（近战=鞘翅+近战武器、远程=鞘翅+弓/枪械；法术书放背包/饰品栏即可）；只在远战开火前与俯冲准备两个相位放，不抢飞向。"));
        this.rows.add(new NumRow("空袭施法间隔（tick）",
                String.valueOf(MaidSmartConfig.COMBAT_FLIGHT_SPELL_CAST_INTERVAL.get()),
                s -> setInt(MaidSmartConfig.COMBAT_FLIGHT_SPELL_CAST_INTERVAL, s),"空袭施法间隔（tick，默认 20 = 1 秒）：两次发起施法之间的最短间隔。吟唱时长、法术冷却与放哪个法术由法术模组自己管，本项只管发起节奏——调小 = 法术更密，调大 = 武器为主。"));
        // v1.2.0 实测五百七十二：空袭·位移法术——分"提供高度"（起飞/补高）与"提供速度"（飞行加速）
        this.rows.add(new BoolRow("位移法术·起飞/补高",
                MaidSmartConfig.COMBAT_FLIGHT_DASH_CLIMB.get(),
                v -> MaidSmartConfig.COMBAT_FLIGHT_DASH_CLIMB.set(v),"启用【提供高度】那一类位移法术：烟花/羽扇不可用时平地起飞、空中补高、盘旋掉高维持（窗口内优先于烟花、不耗燃料）。朝向照抄烟花：地面 = 背离+抬头，头顶 = 朝目标+抬头，掉高 = 45° 抬头。"));
        this.rows.add(new BoolRow("位移法术·飞行加速",
                MaidSmartConfig.COMBAT_FLIGHT_DASH_BOOST.get(),
                v -> MaidSmartConfig.COMBAT_FLIGHT_DASH_BOOST.set(v),"启用【提供速度】那一类位移法术：在盘旋的掉高窗口里（与烟花同窗口、同朝向：抬头 45° 朝目标）冲刺续速——鞘翅掉速就是掉高度。窗口外不放，不会周期性从圈上切进去冲敌人。"));
        this.rows.add(new TextRow("提供高度的法术表", String.join("、", MaidSmartConfig.COMBAT_FLIGHT_DASH_CLIMB_SPELLS.get()),
                s -> setStringList(MaidSmartConfig.COMBAT_FLIGHT_DASH_CLIMB_SPELLS, s),"【提供高度】的法术 id（顿号/逗号分隔）：用于起飞与补高，默认 ascension（升腾）+ burning_dash（烈焰冲锋）。id 写错 = 表里排第一的法术永不生效，认不出的日志会报一条。"));
        this.rows.add(new TextRow("提供速度的法术表", String.join("、", MaidSmartConfig.COMBAT_FLIGHT_DASH_BOOST_SPELLS.get()),
                s -> setStringList(MaidSmartConfig.COMBAT_FLIGHT_DASH_BOOST_SPELLS, s),"【提供速度】的法术 id（顿号/逗号分隔）：用于飞行加速。默认只有 burning_dash（烈焰冲锋）；要加别的冲刺类法术填这里。id 写错的诊断与上面那张表同款（会报一条「法术兼容」日志）。"));
        this.rows.add(new NumRow("位移法术间隔（tick）",
                String.valueOf(MaidSmartConfig.COMBAT_FLIGHT_DASH_INTERVAL.get()),
                s -> setInt(MaidSmartConfig.COMBAT_FLIGHT_DASH_INTERVAL, s),
                "两次起飞/冲刺/补高之间的最短间隔（tick，默认 40 = 2 秒）"));
        this.rows.add(new BoolRow("飞行加速·尊重法术冷却",
                MaidSmartConfig.COMBAT_FLIGHT_DASH_BOOST_RESPECT_COOLDOWN.get(),
                v -> MaidSmartConfig.COMBAT_FLIGHT_DASH_BOOST_RESPECT_COOLDOWN.set(v),"飞行加速·尊重法术冷却（默认开）：只对【提供速度】那类法术——写回冷却取 max(空袭位移间隔, 法术自身冷却)（如烈焰冲锋 10 秒）。【提供高度】不受法术冷却约束，只按间隔放。"));
        this.rows.add(new NumRow("空袭施法距离（格）",
                String.valueOf(MaidSmartConfig.COMBAT_FLIGHT_SPELL_CAST_RANGE.get()),
                s -> setDouble(MaidSmartConfig.COMBAT_FLIGHT_SPELL_CAST_RANGE, s),"空袭施法距离（格，默认 24）：只在目标进入这个 3D 距离内才发起施法；默认与法术模组自己的 maxSpellRange 一致。调大 = 更早就起手（法术飞过去还能命中），调小 = 贴近才放。"));
    }

    private void bridgeRows() {
        this.rows.add(new BoolRow("搭路", MaidSmartConfig.BRIDGE_ENABLED.get(),
                v -> MaidSmartConfig.BRIDGE_ENABLED.set(v),"搭路：周围无威胁、背包有方块时，她垫方块靠近主人——主人不低于她时水平多远都启动平桥追逐（前方悬空铺桥、实心地面走路）；主人更高时垂直搭高（搭的方块 N 秒后回收）；默认开。"));
        this.rows.add(new NumRow("搭路触发距离（格）", String.valueOf(MaidSmartConfig.BRIDGE_MAX_DIST.get()),
                s -> setInt(MaidSmartConfig.BRIDGE_MAX_DIST, s),"搭路触发距离（格，默认 32）：主人高于女仆需垂直搭高时的启动上限——超过交给传送/跟随；平路/低高差追逐不受此限，水平多远都启动平桥追逐。"));
        this.rows.add(new NumRow("空中搭桥距离（格）", String.valueOf(MaidSmartConfig.BRIDGE_AIR_MAX_DIST.get()),
                s -> setInt(MaidSmartConfig.BRIDGE_AIR_MAX_DIST, s),"空中搭桥距离（格，默认 50）：主人高于她需爬高、或她已在空中时，你离得多远她都直接铺桥走过来——空中没有「走路过去」的选项；设 0 关闭远距。平路/低高差追逐不受任何距离上限约束。"));
        this.rows.add(new NumRow("最小高差（格）", String.valueOf(MaidSmartConfig.BRIDGE_MIN_DY.get()),
                s -> setInt(MaidSmartConfig.BRIDGE_MIN_DY, s), "最小高差（格，默认 3）：你至少高于女仆这么多格才走垂直搭高（平路走路/铺桥处理）——3 格 = 玩家手长：她搭到只差 3 格内你就能近身收回/互动，隔得更远你伸手够不到她"));
        this.rows.add(new NumRow("最小球面半径（格）", String.valueOf(MaidSmartConfig.BRIDGE_MIN_RADIUS.get()),
                s -> setInt(MaidSmartConfig.BRIDGE_MIN_RADIUS, s),"最小球面半径（格，默认 3）：以她为圆心的 3D 半径——你在球面内不启桥、靠跟随走路；球面外高差够就垂直搭高，竖直差不多+水平远+脚下悬空就平铺搭桥；实心平路纯走导航不启桥（防抖动）。"));
        // v1.1.0 实测一百八十七：平桥启动水平距离（反馈："水平距离搭建方块有没有启动要求呢？加个启动要求"）
        this.rows.add(new NumRow("平桥启动距离（格）", String.valueOf(MaidSmartConfig.BRIDGE_START_H_DIST.get()),
                s -> setDouble(MaidSmartConfig.BRIDGE_START_H_DIST, s),"平桥启动水平距离（格，默认 6）：女仆与你水平距离达到此值、且朝你方向前方脚下悬空才启动水平搭桥——小于此值只走路跟随；范围 3~64（3 = 最灵敏）。竖直搭高不受影响。"));
        this.rows.add(new NumRow("威胁半径（格）", String.valueOf(MaidSmartConfig.BRIDGE_THREAT_DIST.get()),
                s -> setInt(MaidSmartConfig.BRIDGE_THREAT_DIST, s), "威胁半径（格，默认 8）：周围此范围内有敌对生物时不搭路（搭一半挨打）；刷怪频繁的包里可再调小，过大会导致搭路几乎永不触发"));
        this.rows.add(new NumRow("搭路节奏（tick/块）", String.valueOf(MaidSmartConfig.BRIDGE_STEP_COOLDOWN.get()),
                s -> setInt(MaidSmartConfig.BRIDGE_STEP_COOLDOWN, s), "搭路节奏（tick/块）：每垫一块方块的最短间隔，调大更从容"));
        this.rows.add(new NumRow("搭路方块清理（秒）", String.valueOf(MaidSmartConfig.BRIDGE_PLACED_LIFETIME.get()),
                s -> setInt(MaidSmartConfig.BRIDGE_PLACED_LIFETIME, s), "搭路方块清理时间（秒）：垫的方块 N 秒后自动变掉落物回收（女仆站在上面时会刷新计时，走开后才开始倒数）"));
        this.rows.add(new NumRow("战斗搭方块清理（秒）", String.valueOf(MaidSmartConfig.COMBAT_PLACED_LIFETIME.get()),
                s -> setInt(MaidSmartConfig.COMBAT_PLACED_LIFETIME, s), "战斗搭方块清理时间（秒，默认 10）：自保行为（搭高/翻墙/搭桥/封头盖帽）搭的方块 N 秒后自动回收；女仆踩着时刷新计时，不会把她摔下去"));
        this.rows.add(new BoolRow("垫脚方块回收进背包", MaidSmartConfig.BRIDGE_RECLAIM_TO_MAID.get(),
                v -> MaidSmartConfig.BRIDGE_RECLAIM_TO_MAID.set(v),"垫脚方块回收进背包（默认开，全局：搭路/挖矿/伐木/战斗搭的一切垫脚方块）：到期或被摧毁的垫脚方块不掉地上，同维度内无论多远都直接塞回【搭它的那只女仆】背包；只有她死亡、跨维度或背包满才落地。"));

        // v1.3.0(beta) 实测六百八十【搭方块禁用名单】（
        //
        //
        // com.maidsmart.tool.MaidBuildBlockFilter#isBlacklistedBuildBlock **一处**。
        this.rows.add(new BtnRow("搭方块禁用名单",
                "管理 →（禁 " + MaidSmartConfig.BRIDGE_BUILD_FORBIDDEN.get().size()
                        + " · 放 " + MaidSmartConfig.BRIDGE_BUILD_ALLOWED.get().size() + "）",
                () -> {
                    this.buildBlackTable = true;
                    this.creativeQuery = "";
                    this.creativePage = 0;
                    this.m_7856_();
                },"搭方块禁用名单（默认只许原版天然方块）：点开是一整页方块面板——红框✖ 禁止她垫脚/搭高/搭桥，绿框✔ 允许。对自保搭高/挖矿/伐木/搭路同时生效；下落与伤害方块本来就不许搭。"));
    }

    /**
     * 【v1.2.2 实测六百一十五】飞行跟随**从「搭路」里搬出来**，成为与搭路平级的一个小节
     * （用户
     *
     */
    private void flightFollowRows() {
        // v1.2.2 实测六百〇八【飞行跟随】（玩家建议："女仆跟随能不能给她整个使用鞘翅一起飞呢"）
        // v1.2.2 实测六百一十一：收手 4→15 格且不再抬头泄速（自然滑翔）、燃料并入羽扇、外观与空袭同款
        // v1.2.2 实测六百一十二：进半径解除推进矢量、所有任务模式通用（空袭未接敌也照飞）
        // v1.2.2 实测六百一十三：启动的"能飞的道具"两选一 → 三选一（加能上天的位移法术，与空袭三件套同一口径）
        // v1.2.2 实测六百一十五：起手球 25 / 收手球 5 拆成两个配置 + 整块搬到本小节
        this.rows.add(new BoolRow("飞行跟随（鞘翅追主人）", MaidSmartConfig.FLIGHT_FOLLOW_ENABLED.get(),
                v -> MaidSmartConfig.FLIGHT_FOLLOW_ENABLED.set(v),"飞行跟随（默认关）：主人离她超过起手距离、视线无阻挡、包里有能飞的道具（烟花/羽扇/位移法术）且周围无怪时，她背上鞘翅飞过来，进到收手距离交回普通跟随并消掉烟花推力；会烧烟花、啃鞘翅耐久。"));
        this.rows.add(new NumRow("起手距离（格）", String.valueOf(MaidSmartConfig.FLIGHT_FOLLOW_DIST.get()),
                s -> setDouble(MaidSmartConfig.FLIGHT_FOLLOW_DIST, s),"飞行跟随·起手距离（格，默认 25，3~128）：主人与她 3D 距离超过这个值才起飞追；与收手距离之差就是迟滞带。想更早起飞就调小。老配置写 dist = 5 的要手动改。"));
        this.rows.add(new NumRow("收手距离（格）", String.valueOf(MaidSmartConfig.FLIGHT_FOLLOW_END_DIST.get()),
                s -> setDouble(MaidSmartConfig.FLIGHT_FOLLOW_END_DIST, s),"飞行跟随·收手距离（格，默认 5，范围 1~64）：主人进到这么近就中断本趟、交回普通跟随，并消掉烟花的推进矢量（收掉助推火箭 + 速度归零）。必须比起手距离小，模组会自动压到「起手距离 − 1」。"));
        this.rows.add(new BoolRow("飞行跟随·消耗烟花", MaidSmartConfig.FLIGHT_FOLLOW_FIREWORK.get(),
                v -> MaidSmartConfig.FLIGHT_FOLLOW_FIREWORK.set(v),"飞行跟随消耗烟花（默认开 = 真消耗）：关掉后仍要求背包里有能飞的道具（烟花/羽扇/位移法术，它是「她能飞」的凭证），但补推不再扣那一枚——纯观赏档。这条只管烟花。"));
        this.rows.add(new BoolRow("飞行跟随·消耗鞘翅耐久", MaidSmartConfig.FLIGHT_FOLLOW_ELYTRA.get(),
                v -> MaidSmartConfig.FLIGHT_FOLLOW_ELYTRA.set(v), "飞行跟随消耗鞘翅耐久（默认开 = 照原版每 20 tick 扣 1 点）：关掉之后这一趟飞行不啃鞘翅耐久（只认原版鞘翅及其子类；模组那种自带滑翔钩子的护甲走它自己的实现，这里拦不到）"));
        this.rows.add(new BoolRow("飞行跟随·消耗三叉戟耐久", MaidSmartConfig.FLIGHT_FOLLOW_TRIDENT.get(),
                v -> MaidSmartConfig.FLIGHT_FOLLOW_TRIDENT.set(v),"飞行跟随消耗激流三叉戟耐久（默认开）：关掉后这一趟用三叉戟追你不再扣耐久，与消耗烟花/鞘翅耐久同档。只管飞行跟随；空袭的推进照旧扣。背包有烟花时仍先烧烟花。"));
    }

    /**
     * v1.3.6 实测六百六十一【扫帚模式自己的数值板块】——原先这五行散在「战斗与自保 → 单兵战术」
     * 的尾部，玩家要求把它们挪到「飞行跟随」后面单独成板（见 {@code Section.BROOM}）。
     * 另外补上这一批新增的「牵引绳距离」。
     */
    private void broomRows() {
        this.rows.add(new SectionRow("—— 扫帚模式（TLM 任务：maid_smart:broom）——", false));
        this.rows.add(new BoolRow("扫帚模式·总开关", MaidSmartConfig.COMBAT_BROOM_ENABLE.get(),
                v -> MaidSmartConfig.COMBAT_BROOM_ENABLE.set(v),"扫帚模式（默认开）：装上扫帚 + 任意远程武器（弓/弩/御币/三叉戟/枪械）后任务切成扫帚模式，她取出扫帚、在脚下放一把骑上去飞起来打；缺件时原地待命并报缺什么。不参与自主切换。"));
        this.rows.add(new NumRow("扫帚·盘旋距离（格）", String.valueOf(MaidSmartConfig.COMBAT_BROOM_RANGE.get()),
                s -> setDouble(MaidSmartConfig.COMBAT_BROOM_RANGE, s),"战斗盘旋距离（格，默认 8，1~32）：绕目标转圈时保持的水平距离。近战 boss 只有碰撞箱距离，拿远程武器必须拉开才有输出窗口。它现在是随机区间的近端：接敌后在它与「离敌最远距离」之间缓动。"));
        this.rows.add(new NumRow("扫帚·离敌最远距离（格）", String.valueOf(MaidSmartConfig.COMBAT_BROOM_ORBIT_MAX.get()),
                s -> setDouble(MaidSmartConfig.COMBAT_BROOM_ORBIT_MAX, s),"锁敌后离敌最远距离（默认 10，1~48）：盘旋半径在 min(本值, 盘旋距离×0.75)~本值 间随机缓动、每 4 秒重掷，越界加倍往回带；绕圈速度每 3 秒漂一次、间距放宽到 4 格。"));
        this.rows.add(new NumRow("扫帚·离敌最近距离（格）", String.valueOf(MaidSmartConfig.COMBAT_BROOM_MIN_STANDOFF.get()),
                this.setDoubleInRange(MaidSmartConfig.COMBAT_BROOM_MIN_STANDOFF, "扫帚·离敌最近距离", 1.0, 32.0),"离敌最近距离（默认 6，1~32）：接敌绕圈时绝不会比这条线更近，盘旋半径近端取 max(盘旋距离×0.75, 本值)；调大更安全、调小更贴脸。顶到超过「离敌最远距离」时以后者为准。",
                1.0, 32.0));
        this.rows.add(new NumRow("扫帚·悬停高度（格）", String.valueOf(MaidSmartConfig.COMBAT_BROOM_HOVER.get()),
                s -> setDouble(MaidSmartConfig.COMBAT_BROOM_HOVER, s),
                "悬停高度（格，默认 2，相对目标脚底）：她比目标高出的格数。调太高会够不到地面怪"
                        + "（弹道与射程都会跟着变苛刻），0 = 与目标同高（0~16）"));
        this.rows.add(new NumRow("扫帚·接敌爬升高度（格）", String.valueOf(MaidSmartConfig.COMBAT_BROOM_CLIMB.get()),
                this.setDoubleInRange(MaidSmartConfig.COMBAT_BROOM_CLIMB, "扫帚·接敌爬升高度", 2.0, 32.0),"接敌爬升高度（默认 15，2~32）：遇到敌人先爬到她上方这么多格再开始盘旋，这一个数同时是本场遭遇的盘旋高度（打完/丢目标才作废）；头顶被顶住时按实际抬到的高度记，绝不会为了够数硬顶。",
                2.0, 32.0));
        this.rows.add(new NumRow("扫帚·牵引绳距离（格）", String.valueOf(MaidSmartConfig.COMBAT_BROOM_RECALL_DISTANCE.get()),
                s -> setInt(MaidSmartConfig.COMBAT_BROOM_RECALL_DISTANCE, s),"扫帚牵引绳（格，默认 100，0=关闭）：她骑扫帚离参照点超过这么多格（3D，飞太高也算）就连人带扫帚传回；参照点：非守家 = 你，守家 = 工作区圈心。已落地时不管（交给同维度拉回那套）。"));
        this.rows.add(new BoolRow("扫帚·平时跟随主人", MaidSmartConfig.COMBAT_BROOM_FOLLOW.get(),
                v -> MaidSmartConfig.COMBAT_BROOM_FOLLOW.set(v),"平时（没有敌人时）跟随主人（默认开）：她悬停在主人身边（水平约 3.5 格、高 2 格）跟着飞；关掉 = 原地悬停待命，只在接敌时才动。「守家时绕工作范围盘旋」优先于本项。"));
        this.rows.add(new BoolRow("扫帚·守家时绕工作范围盘旋", MaidSmartConfig.COMBAT_BROOM_CLAMP_HOME.get(),
                v -> MaidSmartConfig.COMBAT_BROOM_CLAMP_HOME.set(v),"受「守家/工作区」范围约束 + 沿工作范围盘旋（默认开）：骑扫帚后 TLM 的范围约束失效，改由本模组把关——没敌人时沿工作范围圈边慢速巡逻（高度见下一项），敌在圈外不追。关掉 = 可越界。"));
        this.rows.add(new NumRow("扫帚·守家盘旋高度（格）",
                String.valueOf(MaidSmartConfig.COMBAT_BROOM_HOME_ALT.get()),
                this.setDoubleInRange(MaidSmartConfig.COMBAT_BROOM_HOME_ALT, "扫帚·守家盘旋高度", 1.0, 32.0),"守家盘旋高度（默认 8，1~32）：巡逻目标高度 = 她脚下那块地之上这么多格；只影响守家巡逻（接敌走「接敌爬升高度」）。天花板自动压低，找不到地面时只降不升。关 = 用扫帚当前高度。",
                1.0, 32.0));
        this.rows.add(new NumRow("扫帚·跟随起手距离（格）", String.valueOf(MaidSmartConfig.COMBAT_BROOM_FOLLOW_START.get()),
                this.setDoubleInRange(MaidSmartConfig.COMBAT_BROOM_FOLLOW_START, "扫帚·跟随起手距离", 2.0, 128.0),"扫帚·跟随起手距离（格，默认 6，2~128）：主人远过这个 3D 距离她才飞过去跟，更近原地悬停；只管扫帚模式。刻意不加「被墙挡住就不起飞」——挡住的交给「卡墙脱困」。旧档写死的 25 不会自动变。",
                2.0, 128.0));
        this.rows.add(new NumRow("扫帚·跟随收手距离（格）", String.valueOf(MaidSmartConfig.COMBAT_BROOM_FOLLOW_END.get()),
                this.setDoubleInRange(MaidSmartConfig.COMBAT_BROOM_FOLLOW_END, "扫帚·跟随收手距离", 1.0, 64.0),"主人进到这么近（3D 距离）就中断这一趟、原地悬停。必须小于起手距离，否则自动压到「起手距离 − 1」以内。默认 3：跟随点在你身后 3.5 格、高 2 格，6/3 即「一直贴着你飞」。",
                1.0, 64.0));
        this.rows.add(new BoolRow("扫帚·卡墙脱困", MaidSmartConfig.COMBAT_BROOM_UNSTICK.get(),
                v -> MaidSmartConfig.COMBAT_BROOM_UNSTICK.set(v),"扫帚·卡墙脱困（默认开）：顶住方块 0.6 秒后先飘到最近的空气格——按她身体的碰撞箱逐格判（死洞不选、5 秒内去过的格不进），都不合格就原地不动。关 = 一直顶着墙飞。"));
        // 实测六百九十八：扫帚待命落地（）
        this.rows.add(new BoolRow("扫帚·待命落地", MaidSmartConfig.COMBAT_BROOM_IDLE_LAND.get(),
                v -> MaidSmartConfig.COMBAT_BROOM_IDLE_LAND.set(v),"扫帚模式「没敌人、也没在跟主人」那一档（起飞后 / 打完仗 / 主人在别维度）降回地面待命：顺脚下探地最多 24 格，找到就降到地面之上 1.6 格；探不到就照旧悬停、绝不往下扎。"));
    }

    /**
     * v1.3.7 实测六百六十七【武装拴绳】——粉丝点单的"直升机二号位"。
     *
     * <p>【实测六百七十五：从「扫帚模式」页里独立出来】
     *
     *
     * {@link #broomRows()} 的末尾（要先点进「扫帚模式」、再往下滚才看得到），现在它是自己的板块
     * （{@link Section#TETHER}，就排在「扫帚模式」后面），而且**第一条就是这个功能是什么**的详解。
     *
     * <p>【排版口径】每一行脚下的注释（{@code comment}）就是这个面板的"详细介绍"——面板会把
     * 注释折行画在输入框下面，所以这里把"怎么用 / 什么规则 / 日志搜什么"都写进各行。
     * <p>【实测六百八十九：分段】这里以前是"一整段 1000+ 字"的详解，窄窗口（GUI scale 4）下
     * 要 22 行、比一页可用高还高——面板分页把"一行"当原子，于是它独占一页，还把文字画到翻页/
     * 保存按钮上（玩家截图：第 1 页只剩板块标题、第 2 页正文压住按钮）。现在按【是什么 /
     * 什么时候能绑 / 怎么解除 / 画面上的三件事（+ 1.21.1 专属的重锤）】拆成 4~5 条短行，
     * 每条注释不超过 3 行；完整细节留在手册那一章（见 {@code GuideContent}），面板只留
     * "看一行就够用"的部分。
     */
    private void tetherRows() {
        this.rows.add(new SectionRow("—— 武装拴绳（TLM：拴住飞行女仆 = 直升机二号位）——", false));
        // 实测六百八十九：这一条以前是「一整段 1000+ 字」的长注释（窄窗口下 22 行、
        // 比一页可用高还高）——面板分页把「一行」当原子，于是它独占一页，还把文字画到
        // 翻页/保存按钮上（玩家截图）。现在按【是什么 / 什么时候能绑 / 怎么解除 /
        // 画面上的三件事】拆成 4~5 条短行（每条注释 ≤3 行）；完整细节留在手册那一章。
        this.rows.add(new InfoRow("武装拴绳 · 是什么", "\u00a7a右击飞行中的女仆 = 挂到她身下（直升机二号位）\u00a7r",
                "她当一号位（她飞、她开火），你当二号位（挂在她身下开火）。"
                        + "合成：拴绳 + 铁锭×2；日志搜「武装拴绳」；完整说明见手册的「武装拴绳」章（末尾有跳回本页的入口）。"));
        this.rows.add(new InfoRow("武装拴绳 · 什么时候能绑", "\u00a7e扫帚档：她已在扫帚上 / 空袭档：随时\u00a7r",
                "扫帚档要她已经骑在扫帚上（地面挂着会把人拖进地里）；空袭档随时能绑，"
                        + "她还没起飞时先「牵绳」（你自由活动、她跟着走）。只认主人；一只女仆只挂一人，你同时只能牵一只。"));
        this.rows.add(new InfoRow("武装拴绳 · 怎么解除", "\u00a7e再右击一次 / 潜跳下鞍\u00a7r",
                "绑定态右击她 = 解除并坐回扫帚驾驶位；在驾驶位上右击她 = 换到二号位（两半互逆）。"
                        + "绳子不会自己断（只有落水超过 0.6 秒会放你下来）；解除瞬间给 5 秒摔伤豁免。"));
        this.rows.add(new InfoRow("武装拴绳 · 画面上的三件事", "\u00a7e半透明 / 金色描边 / 原版丝带\u00a7r",
                "① 挡视野时只对你 + 第一人称把她和扫帚画半透明（下面那行调透明度）；"
                        + "② 被绳子选中的她打金色描边；③ 绳子是原版拴绳同款的白色丝带。"
                        + "挂着时你和她同款免伤：卡墙 / 挤墙 / 摔落 / 撞墙。"));
        this.rows.add(new BoolRow("武装拴绳·总开关", MaidSmartConfig.COMBAT_TETHER_ENABLE.get(),
                v -> MaidSmartConfig.COMBAT_TETHER_ENABLE.set(v),
                "总的开关（默认开）。关掉 = 手持拴绳右击女仆不再挂载、绳子也不画（合成表还在，"
                        + "只是不生效）；已经挂着的会在下一次校验时松开。"));
        this.rows.add(new NumRow("武装拴绳·悬挂距离（格）", String.valueOf(MaidSmartConfig.COMBAT_TETHER_HANG.get()),
                this.setDoubleInRange(MaidSmartConfig.COMBAT_TETHER_HANG, "武装拴绳·悬挂距离", 0.5, 6.0),"玩家脚底到她脚底的垂直距离，即那根「不会断」的绳子长度（默认 2.6，0.5~6.0）。她站在地上时改成水平拉开这么远（并排站、不重叠）。2.6 = 她的脚底高过你的视线；调大 = 吊得更低。",
                0.5, 6.0));
        this.rows.add(new NumRow("武装拴绳·第一人称半透明（0~1）",
                String.valueOf(MaidSmartConfig.COMBAT_TETHER_GHOST_ALPHA.get()),
                this.setDoubleInRange(MaidSmartConfig.COMBAT_TETHER_GHOST_ALPHA, "武装拴绳·第一人称半透明（女仆+扫帚）", 0.0, 1.0),"绑定玩家第一人称下看挂着自己的那只女仆（以及自己骑的扫帚）的模型半透明程度（默认 0.1；填 1.0 = 关掉、照旧不透明）。只对你的第一人称生效（第三人称、别的玩家不动）；纯客户端，服务端不用改。",
                0.0, 1.0));
        this.rows.add(new NumRow("武装拴绳·扫帚额外下沉（格）",
                String.valueOf(MaidSmartConfig.COMBAT_TETHER_BROOM_EXTRA.get()),
                this.setDoubleInRange(MaidSmartConfig.COMBAT_TETHER_BROOM_EXTRA, "武装拴绳·扫帚额外下沉", 0.0, 2.0),"扫帚档下她骑在扫帚上，扫帚模型比她的脚底还低——只按「悬挂距离」吊着会和你叠在一起。这一项在悬挂距离之上再往下让这么多（默认 0.3，0~2.0），只在扫帚档生效；0 = 关掉这个补偿。",
                0.0, 2.0));
        this.rows.add(new BoolRow("武装拴绳·金色描边标记", MaidSmartConfig.COMBAT_TETHER_GLOW_MARK.get(),
                v -> MaidSmartConfig.COMBAT_TETHER_GLOW_MARK.set(v),"被拴绳选中、还没正式起飞的那只女仆打一层金色描边（原版发光标记、光边改金色；穿墙可见），她正式起飞（牵绳 → 二号位）那刻解除。关掉 = 完全不发光，挂载照旧。"));
        // 实测六百八十七：二号位·拴绳操控方向（需求方：丢锁敌后玩家一点办法都没有）
        this.rows.add(new BoolRow("武装拴绳·悬挂时用拴绳操控方向", MaidSmartConfig.COMBAT_TETHER_LEASH_STEER.get(),
                v -> MaidSmartConfig.COMBAT_TETHER_LEASH_STEER.set(v),"默认开（仅空袭档）：挂在她下方时若她没有目标，把拴绳举在手里——你看哪儿她就往哪儿飞（水平跟朝向、高低跟俯仰，抬头爬升、低头下降）。有敌人时方向归空袭链路；不拿在手上 = 只悬停。"));
        this.rows.add(new BoolRow("武装拴绳·拉扯（像原版拴绳一样）", MaidSmartConfig.COMBAT_TETHER_PULL.get(),
                v -> MaidSmartConfig.COMBAT_TETHER_PULL.set(v),"绳子绷紧时真的拉她（默认开），分档照搬原版拴绳：> 6 格用原版 0.4·方向² 的冲量把你俩拽回去；2~6 格补「朝主人的速度上限」；原版 > 10 格会撒手，我们的绳子不会断。只在牵绳档生效。"));
    }

    /**
     * v1.3.0(beta)【骑乘指挥棒·原版生物骑乘】参数页。
     *
     * <p>面板归位口径与武装拴绳同款（实测六百七十五：）：
     * 自己一页、紧跟武装拴绳之后，第一条就是"这个功能是什么"的详解。
     */
    private void rideRows() {
        this.rows.add(new SectionRow("骑乘指挥棒（原版生物骑乘）", false));
        this.rows.add(new InfoRow("这是什么",
                "让女仆骑上原版坐骑跟着你走","手持骑乘指挥棒右击一只已上鞍的坐骑（马/骡/猪等），再右击自己的女仆即可配对（顺序随意），选中的亮金色描边。绑定后她坐上去跟着你走；潜行 + 右击让她下来。合成：拴绳 + 木棍。"));
        this.rows.add(new BoolRow("骑乘指挥棒·总开关", MaidSmartConfig.COMBAT_RIDE_ENABLE.get(),
                v -> MaidSmartConfig.COMBAT_RIDE_ENABLE.set(v),"默认开。上鞍走 startRiding(force)，驱动走坐骑自己的寻路（只把目的地喂进它的 PathNavigation）。可骑乘 = 能力探测（不写死 id）。关掉 = 指挥棒不再绑定。"));
        this.rows.add(new NumRow("骑乘·跟随停下距离（格）", String.valueOf(MaidSmartConfig.COMBAT_RIDE_FOLLOW_DIST.get()),
                this.setDoubleInRange(MaidSmartConfig.COMBAT_RIDE_FOLLOW_DIST, "骑乘·跟随停下距离", 1.0, 16.0),"默认 5.0。骑坐骑跟主人走时，离主人水平距离在这个数以内就不再给它下移动目标——不然它会顶着主人来回蹭（转身半径大，贴太近会打转）。2~3 = 几乎贴着走；8 以上 = 远远跟着（适合大坐骑）。"));
        this.rows.add(new NumRow("骑乘·速度总倍率", String.valueOf(MaidSmartConfig.COMBAT_RIDE_SPEED_SCALE.get()),
                this.setDoubleInRange(MaidSmartConfig.COMBAT_RIDE_SPEED_SCALE, "骑乘·速度总倍率", 0.2, 3.0),"默认 1.0，范围 0.2~3.0。乘在「坐骑速度与女仆速度取最大」之上（1.0 = 严格取最大值）。嫌慢（骑驴/猪跟不上）调到 1.5~2.0；嫌快往 0.5 调。不会突破载具自己的寻路安全上限。"));
        // v1.3.0(beta) 实测七百一十七【家具类坐骑黑名单】
        this.rows.add(new TextRow("骑乘·家具类坐骑黑名单", String.join(",", (List<String>) MaidSmartConfig.COMBAT_RIDE_FURNITURE_BLACKLIST.get()),
                s -> setStringList(MaidSmartConfig.COMBAT_RIDE_FURNITURE_BLACKLIST, s),"一行或逗号一条实体 id。列这里的实体当「家具」：坐上去时所有骑乘改动不生效（不绑、不喂目标、传送不连坐骑）。默认 TLM 椅子 touhou_little_maid:chair 与坐垫 :sit。"));
        // v1.3.0(beta) 实测七百一十九【模组坐骑通解通法】
        this.rows.add(new BoolRow("模组坐骑·兼容（卓越前线 / 冰火传说）", MaidSmartConfig.COMBAT_RIDE_MOD_MOUNTS.get(),
                v -> MaidSmartConfig.COMBAT_RIDE_MOD_MOUNTS.set(v),"默认开。让女仆能骑并驾驶第三方载具/坐骑：卓越前线的载具（坦克/直升机等）与冰火传说的龙。用可插拔驱动翻译成对方 API；全程反射，没装或换版本不激活。关掉 = 只认原版 Saddleable 兽。"));
        this.rows.add(new BoolRow("模组坐骑·代她开火", MaidSmartConfig.COMBAT_RIDE_MOD_MOUNT_FIRE.get(),
                v -> MaidSmartConfig.COMBAT_RIDE_MOD_MOUNT_FIRE.set(v),"默认开。骑卓越前线载具或冰火传说龙时，把她 brain 里的攻击目标交给坐骑打——载具走自带的「乘客有目标就自动瞄准开火」链路，龙走吐息。关闭 = 坐骑不开火（你自己开）。"));
        // 【无鞍可骑仆从后门】
        //
        //
        this.rows.add(new BoolRow("无鞍可骑仆从（诡厄巫法等）", MaidSmartConfig.COMBAT_RIDE_NO_SADDLE_PETS.get(),
                v -> MaidSmartConfig.COMBAT_RIDE_NO_SADDLE_PETS.set(v),"默认开。让女仆能骑那些不要鞍、靠「空手右击」上去的模组仆从。判据：Mob + 原版 PlayerRideable 或诡厄 IAutoRideable + 不能装鞍。关闭 = 只认已上鞍的坐骑。"));
        // v1.3.0(beta) 实测七百七十【模组仆从坐骑：单独一个区间】
        this.rows.add(new BoolRow("模组仆从坐骑·单独区间（只赋速度）", MaidSmartConfig.COMBAT_RIDE_SERVANT_AUTO.get(),
                v -> MaidSmartConfig.COMBAT_RIDE_SERVANT_AUTO.set(v),"默认开。对无鞍可骑的模组仆从：女仆坐上去只赋予它自己的速度，其余行动全换成仆从自己的 AI（锁敌与各类 goal 自己跑），本模组不再喂走位、不写目标、不替它出招。home 模式例外。"));
        this.rows.add(new BoolRow("模组仆从坐骑·伤害转移给坐骑", MaidSmartConfig.COMBAT_RIDE_SERVANT_TRANSFER.get(),
                v -> MaidSmartConfig.COMBAT_RIDE_SERVANT_TRANSFER.set(v),"默认开。骑着无鞍可骑模组仆从时，她受到的伤害转给身下的仆从（同源打到它身上，仇恨顺势过去）。不转移：环境自伤（虚空/卡墙类）；其余一律转移。只在棍子绑定的那一对上生效。"));
        // v1.3.0(beta) 实测七百二十【点2：骑乘指挥棒独占右击】
        this.rows.add(new BoolRow("骑乘指挥棒·独占右击", MaidSmartConfig.COMBAT_RIDE_BATON_EXCLUSIVE.get(),
                v -> MaidSmartConfig.COMBAT_RIDE_BATON_EXCLUSIVE.set(v),"默认开。手持骑乘指挥棒右击任何实体都由棍子吃掉，不触发它原本的右击效果——「拿棍子骑不上龙/车子」。冰火传说龙是多部件实体，准星常打中翅膀/尾巴并转发右击；也管 interactAt。"));
        // v1.3.0(beta) 实测七百二十七·点1 + 七百二十八【骑飞行载具：跟随离地悬停、接敌升到敌上】
        this.rows.add(new BoolRow("骑飞行载具·悬停与盘旋（卓越前线直升机/固定翼）",
                MaidSmartConfig.COMBAT_RIDE_AIR_COMBAT.get(),
                v -> MaidSmartConfig.COMBAT_RIDE_AIR_COMBAT.set(v),"默认开。驾驶飞行载具（卓越前线直升机/固定翼）：没敌人时悬停，机身稳在「脚下地面 + 下一项格数」；遇敌升到「敌人上方接敌高度」俯射并绕圈。关掉 = 旧行为。"));
        this.rows.add(new NumRow("悬停高度（跟随·离地格数）",
                String.valueOf(MaidSmartConfig.COMBAT_RIDE_AIR_ALT.get()),
                s -> setDouble(MaidSmartConfig.COMBAT_RIDE_AIR_ALT, s),"默认 3（1~20）。只作用于跟随（没有敌人时）：机身稳定在「她脚下的地面 + 这么多格」。有敌人时改看「接敌高度」。头顶被方块顶住时按实际能到的高度悬停，绝不硬顶。"));
        this.rows.add(new NumRow("接敌高度（比敌人高几格）",
                String.valueOf(MaidSmartConfig.COMBAT_RIDE_FIGHT_ALT.get()),
                s -> setDouble(MaidSmartConfig.COMBAT_RIDE_FIGHT_ALT, s),"接敌高度（默认 15，3~40）：只作用于接敌——机身升到敌人上方这么多格再盘旋俯射；数值写进目标点高度、用总距升到位，所以能真正稳在敌上方（旧版写进俯仰，机头会被压向地面）。"));
        this.rows.add(new NumRow("盘旋半径（格）",
                String.valueOf(MaidSmartConfig.COMBAT_RIDE_ORBIT_RADIUS.get()),
                s -> setDouble(MaidSmartConfig.COMBAT_RIDE_ORBIT_RADIUS, s),"盘旋半径（默认 4，2~24）：骑飞行载具接敌时绕敌的圈有多大。独立于扫帚那套，也不被接敌机动放大。越小 = 贴得越近、绕得越紧（命中率高但更容易挨打）。"));
        // v1.3.0(beta) 实测七百二十六·点6【女仆自己往载具装弹】
        this.rows.add(new BoolRow("骑载具·替她装弹（卓越前线）",
                MaidSmartConfig.COMBAT_RIDE_AMMO_FEED.get(),
                v -> MaidSmartConfig.COMBAT_RIDE_AMMO_FEED.set(v),"默认开。驾驶卓越前线载具时，若她背包里有这辆车要的子弹，她会搬进载具自己的弹药容器（车的枪弹从车容器取、看不见她背包）。每 0.5 秒按需搬一次，不会一次全倒进去。"));
        // v1.3.0(beta) 实测七百四十七【投弹安全高度：基洛夫先爬升再投弹】
        this.rows.add(new NumRow("投弹安全高度（比目标高几格）",
                String.valueOf(MaidSmartConfig.COMBAT_RIDE_BOMB_STANDOFF.get()),
                s -> setDouble(MaidSmartConfig.COMBAT_RIDE_BOMB_STANDOFF, s),"投弹安全高度（默认 20，0~64）：驾驶飞行载具（尤其基洛夫空艇）对地投弹前先升到比目标高这么多格再松弹——炸弹爆炸半径大，贴地会把自己圈进爆心。0 = 关掉；只作用于飞行载具。"));
        // v1.3.0(beta) 实测七百一十八【issue #31：坐下/蹲下的女仆不被自保传送拉走】
        this.rows.add(new BoolRow("坐下的女仆不被自保传送拉走", MaidSmartConfig.COMBAT_TELEPORT_EXEMPT_SITTING.get(),
                v -> MaidSmartConfig.COMBAT_TELEPORT_EXEMPT_SITTING.set(v),"默认开。她坐下（TLM 坐姿）或蹲下（Shift）时，自保的「回到主人身边」传送不再把她拉走——这两种姿势是玩家明确把她停在那儿的动作。关掉 = 坐着的残血女仆也会被传回去。站起来后恢复。"));
    }

    private void reviveRows() {
        this.rows.add(new BoolRow("主人死亡传送", MaidSmartConfig.COMBAT_MASTER_DEATH_TELEPORT.get(),
                v -> MaidSmartConfig.COMBAT_MASTER_DEATH_TELEPORT.set(v), "主人死亡强制传送（无视战斗/距离）"));
        this.rows.add(new BoolRow("致死伤害自动回魂符", MaidSmartConfig.SOUL_SPELL_ENABLE.get(),
                v -> MaidSmartConfig.SOUL_SPELL_ENABLE.set(v),"致死伤害自动回魂符：她受到一击必杀的伤害且没有保命物品（绀珠之药/不死图腾）时，自动收进主人背包里的空魂符（TLM 魂符）——免去神龛复活。主人需同维度且在半径内；收符后进入冷却（默认 60 秒）。"));
        this.rows.add(new BoolRow("致死伤害保护", MaidSmartConfig.SOUL_SPELL_LETHAL_GUARD.get(),
                v -> MaidSmartConfig.SOUL_SPELL_LETHAL_GUARD.set(v), "致死伤害保护：受到一击必杀的伤害时立即尝试收魂符（成功则取消伤害）——比死亡强；有保命物品时让保命物品生效，不抢收"));
        this.rows.add(new NumRow("主人收符半径（格）", String.valueOf(MaidSmartConfig.SOUL_SPELL_OWNER_RADIUS.get()),
                s -> setDouble(MaidSmartConfig.SOUL_SPELL_OWNER_RADIUS, s), "主人收符半径：女仆与主人距离超过此值不自动收符（魂符在主人背包，太远收不了）"));
        this.rows.add(new BoolRow("女仆自动复活", MaidSmartConfig.AUTO_RESURRECT_ENABLE.get(),
                v -> MaidSmartConfig.AUTO_RESURRECT_ENABLE.set(v),"女仆自动复活：死亡后墓碑在延迟到期时自动消失，她在主人重生点（床/重生锚）按比例复活；重生点不可用时直接在主人所在位置复活（强制、不看地形）；关掉恢复 TLM 原版死亡流程。"));
        this.rows.add(new NumRow("复活延迟（秒）", String.valueOf(MaidSmartConfig.AUTO_RESURRECT_DELAY_SECONDS.get()),
                s -> setInt(MaidSmartConfig.AUTO_RESURRECT_DELAY_SECONDS, s), "复活延迟（秒）：死亡后墓碑存在这么久才自动消失并复活女仆（默认 60，也是墓碑存在的时长）"));
        this.rows.add(new NumRow("复活血量比", String.valueOf(MaidSmartConfig.AUTO_RESURRECT_HEALTH_RATIO.get()),
                s -> setDouble(MaidSmartConfig.AUTO_RESURRECT_HEALTH_RATIO, s), "复活血量比：复活时女仆恢复的血量比例（1.0 = 满血，0.35 = 35%）"));
        // 实测四百二十六：复活时机（照驯养革新宠物床：可选次日黎明；右键墓碑随时可立即复活）
        String[] reviveTiming = {"延迟 N 秒", "次日黎明"};
        int reviveTimingIdx = Math.max(0, Math.min(1, MaidSmartConfig.AUTO_RESURRECT_TIMING.get()));
        this.rows.add(new CycleRow("复活时机", reviveTiming, reviveTiming[reviveTimingIdx],
                v -> {
                    int idx = java.util.Arrays.asList(reviveTiming).indexOf(v);
                    MaidSmartConfig.AUTO_RESURRECT_TIMING.set(idx >= 0 ? idx : 0);
                },
                "复活时机：延迟 N 秒 = 死后等「复活延迟（秒）」到期复活；次日黎明 = 照驯养革新宠物床，等到游戏时间 dayTime 到 1（黎明）才复活。两种方式下右键墓碑都能立即复活"));
        // 实测四百零五：收符冷却（秒）——从"致死伤害自动回魂符"子分区移到这里，
        // 与传送/珍珠/治疗冷却等自保参数并列；0 = 彻底关闭防抖振
        this.rows.add(new NumRow("收符冷却（秒）", String.valueOf(MaidSmartConfig.SOUL_SPELL_COOLDOWN_SECONDS.get()),
                s -> setInt(MaidSmartConfig.SOUL_SPELL_COOLDOWN_SECONDS, s), "回魂符冷却：收符后冷却期内不再触发（防\"放出即死→又收又放\"抖振）；从释放时刻起算，过了就能再收；0 = 无冷却"));
    }

    private void selfPreserveRows() {
        this.rows.add(new BoolRow("自保行为", MaidSmartConfig.COMBAT_SELF_PRESERVE.get(),
                v -> MaidSmartConfig.COMBAT_SELF_PRESERVE.set(v), "自保行为（轻量被动：环境危险/低血时插保命动作——喝药/垫高/逃跑/传送；平时零干预，与战斗/战术并行不冲突）"));
        // v1.1.0 实测一百五十三/一百五十四：TLM 保护饰品识别（火焰/溺水）
        this.rows.add(new BoolRow("火焰保护饰品识别", MaidSmartConfig.COMBAT_FIRE_PROTECT_BAUBLE.get(),
                v -> MaidSmartConfig.COMBAT_FIRE_PROTECT_BAUBLE.set(v), "佩戴 TLM 火焰保护饰品（火焰伤害免疫+受伤时给 15 秒抗火并喷灭火剂）时，着火/泡岩浆不再惊慌灭火/找水/往主人身边跑——饰品自己会处理；关闭 = 旧行为"));
        this.rows.add(new BoolRow("溺水保护饰品识别", MaidSmartConfig.COMBAT_DROWN_PROTECT_BAUBLE.get(),
                v -> MaidSmartConfig.COMBAT_DROWN_PROTECT_BAUBLE.set(v), "佩戴 TLM 溺水保护饰品（溺水伤害免疫+空气自动补满）时，泡水不再喊\"溺水\"上浮找空气/喝水肺；关闭 = 旧行为"));
        // v1.1.0 实测一百五十五：保命物品下保留逃跑（实测三百六十三：自保逃跑
        // 已删除，本项现只管 TLM 原生惊慌逃跑与"情况不妙"播报）
        this.rows.add(new BoolRow("保命物品下允许惊慌", MaidSmartConfig.COMBAT_FLEE_WITH_SAVE_ITEM.get(),
                v -> MaidSmartConfig.COMBAT_FLEE_WITH_SAVE_ITEM.set(v),"携带保命物品（绀珠之药 / 不死图腾）时是否还惊慌逃跑：默认关 = 有保命物品就不惊慌、不喊「情况不妙」（她死不了，继续战斗/垫高/治疗）；开 = 照常惊慌。自保走位不受此开关影响。"));
        this.rows.add(new NumRow("触发血量（0-1）", String.valueOf(MaidSmartConfig.COMBAT_ENTER_RATIO.get()),
                s -> setDouble(MaidSmartConfig.COMBAT_ENTER_RATIO, s), "触发血量（0-1，0.3=30%）：血量低于此值进入保命（逃跑/搭高/喝药）；危机解除线见「安全回归血量」"));
        this.rows.add(new NumRow("绝对解除血量（0-1）", String.valueOf(MaidSmartConfig.COMBAT_EXIT_RATIO.get()),
                s -> setDouble(MaidSmartConfig.COMBAT_EXIT_RATIO, s), "绝对解除血量（0-1，默认 0.7）：血量到此无条件结束保命（威胁还在也退，战斗交还战术）；更常用的解除线 = 威胁消失+安全回归血量"));
        this.rows.add(new NumRow("解除血量（0-1）", String.valueOf(MaidSmartConfig.COMBAT_SAFE_RETURN_RATIO.get()),
                s -> setDouble(MaidSmartConfig.COMBAT_SAFE_RETURN_RATIO, s),"解除血量（0-1，默认 0.7）：血量恢复到此线即解除自保、回归工作/战斗（威胁还在也解除）；0.3 触发线与本线之间是防抖滞回带。塔顶被围困 10 秒会传送回家养伤。"));
        this.rows.add(new NumRow("威胁距离", String.valueOf(MaidSmartConfig.COMBAT_THREAT_DISTANCE.get()),
                s -> setInt(MaidSmartConfig.COMBAT_THREAT_DISTANCE, s), "威胁距离（格）：怪物进入此距离才算威胁——调大女仆更早警觉、更容易进自保"));
        this.rows.add(new NumRow("威胁扫描间隔（tick）", String.valueOf(MaidSmartConfig.COMBAT_THREAT_SCAN.get()),
                s -> setInt(MaidSmartConfig.COMBAT_THREAT_SCAN, s), "威胁扫描间隔（tick，20=1 秒）：寻找威胁的轮询周期，调小反应快、略耗性能"));
        this.rows.add(new NumRow("威胁消失退出（tick）", String.valueOf(MaidSmartConfig.COMBAT_THREAT_GONE_EXIT.get()),
                s -> setInt(MaidSmartConfig.COMBAT_THREAT_GONE_EXIT, s), "安全解除时限（tick，400=20 秒）：完全安全（无威胁+无环境危险）持续此时长即解除自保【无论血量】——危机结束就该回归工作，带伤回家/干活，再被打回触发线会重新进入"));
        this.rows.add(new NumRow("贴身距离", String.valueOf(MaidSmartConfig.COMBAT_CLOSE_DISTANCE.get()),
                s -> setDouble(MaidSmartConfig.COMBAT_CLOSE_DISTANCE, s), "贴身距离（格）：怪物低于此距离判定被近身（濒死时触发击退+搭高）"));
    }

    private void aidRows() {
        // v1.1.0 实测一百五十二：有增益也喂牛奶（女仆自己喝 + 给主人喂两处共用）
        this.rows.add(new BoolRow("有增益也喂牛奶", MaidSmartConfig.MISC_MILK_FEED_WITH_BUFF.get(),
                v -> MaidSmartConfig.MISC_MILK_FEED_WITH_BUFF.set(v),"女仆自己喝牛奶解负面 / 给主人喂牛奶解负面时，身上有增益效果也照喂——很多装备/饰品带永久增益，旧版「无增益才喂」导致中毒/凋零也不解（牛奶会连增益一起清掉）；关闭 = 有增益不喂牛奶。"));
        this.rows.add(new BoolRow("自动投喂/治疗主人", MaidSmartConfig.AID_OWNER_ENABLE.get(),
                v -> MaidSmartConfig.AID_OWNER_ENABLE.set(v), "自动投喂/治疗：主人饿/血低自动喂熟食或投掷治疗药水（被动技能，非工作状态）"));
        // v1.1.0：女仆互助开关（只影响女仆↔女仆，主人链不受影响）
        this.rows.add(new BoolRow("女仆之间互相支援", MaidSmartConfig.AID_MAID_MUTUAL.get(),
                v -> MaidSmartConfig.AID_MAID_MUTUAL.set(v), "女仆之间互相支援（默认开）：同主人、16 格内的其他女仆低血/着火/中毒时，自动投药水/金苹果/喂食支援她（与支援主人同一套方案，主人优先）；关闭 = 女仆只照顾主人、不互相支援"));
        // v1.3.0(beta) 实测七百七十一：支援范围扩大到其他友方单位（主人的其他宠物 / 同主人的模组仆从）
        this.rows.add(new BoolRow("支援其他友方单位", MaidSmartConfig.AID_FRIENDLY_UNITS.get(),
                v -> MaidSmartConfig.AID_FRIENDLY_UNITS.set(v),"支援其他友方单位（默认开）：主人的其他宠物（原版狼猫马）与同主人的模组仆从低血/着火/中毒时自动投药水、金苹果、牛奶蜂蜜。判据 = 归属同一主人；不给它们食物或图腾。关闭 = 只支援主人与女仆。"));
        // 实测八百一十七（续）：
        this.rows.add(new BoolRow("支援亡灵单位", MaidSmartConfig.AID_UNDEAD_UNITS.get(),
                v -> MaidSmartConfig.AID_UNDEAD_UNITS.set(v),"支援亡灵单位（默认开）：对亡灵类友军/同主女仆（治疗药水对它们反而扣血）换用亡灵专属池（伤害药水 + 走后门金苹果）并播专属语音；关 = 支援链跳过亡灵目标。"));
        // 实测八百一十七（续）：女仆自己喝药水/吃东西也走支援表
        this.rows.add(new BoolRow("自用也走支援表", MaidSmartConfig.AID_SELF_USE.get(),
                v -> MaidSmartConfig.AID_SELF_USE.set(v),"自用也走支援表（默认开）：她自己喝药水/吃食物时也按两张支援表判定——标「禁用」的绝不喝、「回复」只在低血、「对策」只在对应处境、「增益」只在助战时。关闭 = 退回旧行为。"));
        this.rows.add(new NumRow("投喂触发饱食度", String.valueOf(MaidSmartConfig.AID_FOOD_THRESHOLD.get()),
                s -> setInt(MaidSmartConfig.AID_FOOD_THRESHOLD, s), "投喂触发饱食度（4-20，20=只要不满就喂）：主人饱食度低于此值自动喂食（默认 12）——v1.5.301 起填 20 真实生效（旧版范围上限 18，填 20 被静默钳回 18）"));
        this.rows.add(new BtnRow("投喂食物勾选",
                "打开 →（可喂 " + this.countFeedableFoods() + " / 不能吃 " + ((List)MaidSmartConfig.AID_FOOD_BLACKLIST.get()).size() + "）",
                () -> {
            this.foodTable = true;
            this.creativePage = 0;
            this.m_7856_();
        },"列出全部带食物属性的物品（含模组食物），点图标切换能不能喂——打勾 = 可以喂，取消 = 列入「不能吃」（写进 aidFoodBlacklist）；留空则所有食物都能喂。与 TLM 自己吃食同一判据。"));
        // 实测八百一十七：与「投喂食物勾选」同款的支援品分类子页
        this.rows.add(new BtnRow("支援物品分类",
                "打开 →（通用 " + this.countSupportEntries(0) + " / 亡灵 "
                        + this.countSupportEntries(1) + "）",
                () -> {
                    this.supportTable = true;
                    this.creativePage = 0;
                    this.m_7856_();
                },"把女仆对非亡灵目标投喂/投掷的消耗品分四类：对策（抗火/水肺/牛奶蜂蜜，仅对应处境/debuff 时用）、增益（力量/迅捷等，开局可用）、回复（治疗/再生/金苹果，仅低于「治疗触发血量」时用）、禁用。"));
        // 实测五百七十一：喂水（软联动「口渴」Thirst Was Taken）——模组不在场时这两项不出现
        if (com.maidsmart.action.ThirstCompat.available() && MaidSmartConfig.AID_THIRST_THRESHOLD != null) {
            this.rows.add(new NumRow("投喂触发口渴度", String.valueOf(MaidSmartConfig.AID_THIRST_THRESHOLD.get()),
                    s -> setInt(MaidSmartConfig.AID_THIRST_THRESHOLD, s),"投喂触发口渴度（4-20）：主人口渴值（0-20）低于此值自动喂水（默认 15）；结算与玩家自己喝一致（口渴/纯度与玻璃瓶返还走原版路径）。未装口渴模组时本页没有这两项。"));
            // 实测五百七十三：与「投喂食物勾选」同款的图形化勾选子页（用户要求照搬喂食那套）
            this.rows.add(new NumRow("喂水最低水质（0-3）",
                    String.valueOf(MaidSmartConfig.AID_DRINK_MIN_PURITY.get()),
                    s -> setInt(MaidSmartConfig.AID_DRINK_MIN_PURITY, s),"喂水最低水质（0-3，默认 2 = 可接受的）：装水容器要达到这个等级才喂——0 肮脏/1 有点脏/2 可接受/3 纯净；只对装水容器生效；填 0 = 脏水也喂。白名单里每杯水都标水质。"));
            this.rows.add(new BtnRow("喂水白名单",
                    "打开 →（可喂 " + this.countDrinkables() + " / 候选 " + this.countWaterCandidates() + "）",
                    () -> {
                        this.waterTable = true;
                        this.creativePage = 0;
                        this.m_7856_();
                    },"喂水白名单：全部「能恢复口渴」的饮品（含模组容器），点图标切换喂不喂（aidDrinkWhitelist）：绿框✔ 可以、红框✖ 不喂；potion 只认水瓶；留空 = 不喂水。"));
        }
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
    }

    private void playerDamageRows() {
        String[] dmgModes = {"TLM原版(÷5封顶2)", "完全免疫", "无限制", "有上限(比例)", "仅一点伤害(上限1)"};
        int dmgMode = Math.max(0, Math.min(dmgModes.length - 1, MaidSmartConfig.PLAYER_DAMAGE_MODE.get()));
        // v1.5.207：玩家对女仆伤害策略（TLM 原版 = 主人攻击 ÷5 封顶 2 点——原版剑
        // 看起来打不到、高伤武器（更好的战斗等）能打出 2 点；这里给玩家自选）
        // v1.5.252h：current 改用【选项文字】——旧版传数字 "0"~"4" 与文字选项永不
        // 匹配（CycleButton 显示错位），onChange 按文字下标回写配置
        this.rows.add(new CycleRow("玩家对女仆伤害", dmgModes,
                dmgModes[dmgMode],
                v -> {
                    int idx = java.util.Arrays.asList(dmgModes).indexOf(v);
                    MaidSmartConfig.PLAYER_DAMAGE_MODE.set(idx >= 0 ? idx : 0);
                },"玩家对女仆伤害模式：TLM 原版 = 主人攻击 ÷5 封顶 2 点；完全免疫 = 任何玩家都打不到；无限制 = 像打普通生物；有上限 = 单次不超过最大生命 × 比例；仅一点 = 单次上限 1 点。"));
        this.rows.add(new NumRow("玩家伤害上限比例（0-1）", String.valueOf(MaidSmartConfig.PLAYER_DAMAGE_MAID_CAP.get()),
                s -> setDouble(MaidSmartConfig.PLAYER_DAMAGE_MAID_CAP, s), "玩家伤害上限比例（0-1，模式=有上限时生效）：单次伤害 = 女仆最大生命 × 此比例（默认 0.1 = 10%，20 血女仆单次最多 2 点）"));
    }

    private void tacticsRows() {
        this.rows.add(new BoolRow("单兵作战战术", MaidSmartConfig.COMBAT_TACTICS.get(),
                v -> MaidSmartConfig.COMBAT_TACTICS.set(v), "单兵作战战术总开关：绕圈走位/打退拉扯/距离控制/时机举盾（PVP 式战斗，战斗女仆单打独斗）"));
        this.rows.add(new BoolRow("近战战术", MaidSmartConfig.COMBAT_TACTICS_MELEE.get(),
                v -> MaidSmartConfig.COMBAT_TACTICS_MELEE.set(v), "近战战术：贴脸绕圈侧移（少正面挨刀）、打一刀退一步（hit&run 拉扯）、接近时跳劈"));
        // v1.5.280：近战贴脸后退（默认开——女仆手长 3 格，拉开后照样砍得到）
        this.rows.add(new BoolRow("近战贴脸后退", MaidSmartConfig.COMBAT_TACTICS_MELEE_KITE.get(),
                v -> MaidSmartConfig.COMBAT_TACTICS_MELEE_KITE.set(v), "近战贴脸后退：敌人贴进 2 格内主动后退拉开距离（不再贴身互搏白挨刀；女仆手长 3 格退开后照样砍得到，与打一刀退一步/跳劈节奏互补）"));
        this.rows.add(new BoolRow("远程战术", MaidSmartConfig.COMBAT_TACTICS_RANGED.get(),
                v -> MaidSmartConfig.COMBAT_TACTICS_RANGED.set(v), "远程战术：保持理想射程（原版会走到怪脸上射）、横移绕圈放风筝"));
        // 实测四百零一：高地狙击已整体移除（定夺）——配置行一并删除
        this.rows.add(new BoolRow("时机举盾", MaidSmartConfig.COMBAT_TACTICS_SHIELD.get(),
                v -> MaidSmartConfig.COMBAT_TACTICS_SHIELD.set(v), "时机举盾：攻击冷却间隙举盾格挡、冷却满放盾攻击（攻防交替；替代原版 8 格内一直举盾）"));
        this.rows.add(new NumRow("绕圈半径（格）", String.valueOf(MaidSmartConfig.COMBAT_TACTICS_ORBIT_RADIUS.get()),
                s -> setDouble(MaidSmartConfig.COMBAT_TACTICS_ORBIT_RADIUS, s), "绕圈半径（格）：近战贴脸绕圈 / 远程横移的圆周半径，越小打得越密、越大越飘"));
        // v1.2.2 实测五百六十：友军风免（玩家/同主女仆不被女仆的法术·风弹震开）
        this.rows.add(new BoolRow("友军风免",
                MaidSmartConfig.COMBAT_FRIENDLY_WIND_IMMUNE.get(),
                v -> MaidSmartConfig.COMBAT_FRIENDLY_WIND_IMMUNE.set(v),"友军风免（默认开）：女仆放出的风暴/火球/风弹不再把主人和同主女仆震开——伤害本就免疫，漏的是击退（原版爆炸与呼啸之风直接改速度）。只拦外力位移，她自己的烟花推进不受影响。"));
        this.rows.add(new NumRow("远程理想射程倍率", String.valueOf(MaidSmartConfig.COMBAT_TACTICS_KITE_RANGE.get()),
                s -> setDouble(MaidSmartConfig.COMBAT_TACTICS_KITE_RANGE, s), "远程理想射程倍率：0.6 = 保持在武器最大射程 60% 的距离放风筝（远了追、近了退）；适用弓（射程15）/弩（射程8）/三叉戟（搜索半径）/枪械（TLM 枪械中距离配置）"));
    }

    private void autoCombatRows() {
        this.rows.add(new BoolRow("主动切换战斗模式", MaidSmartConfig.COMBAT_AUTO_SWITCH.get(),
                v -> MaidSmartConfig.COMBAT_AUTO_SWITCH.set(v),"主动切换战斗模式：主人被有来源的攻击（怪/玩家/弹射物；环境伤害不算）或主人攻击了别的生物时，附近女仆无论在干什么都立即切战斗模式护主；她自己被怪攻击也会让她与周围姐妹参战。默认开。"));
        // v1.5.397 实测八百五十四：原「飞行作战」总开关已挪到「空袭数值」页第一条
        // （改名「空袭·总开关（飞行作战）」）——需求：DIY 一开空袭就彻底起不来，而按“空袭”
        // 在配置里找不到总开关（扫帚页第一条就是总开关）。本页只剩自主切换相关项。
        this.rows.add(new NumRow("响应半径（格）", String.valueOf(MaidSmartConfig.COMBAT_AUTO_SWITCH_RADIUS.get()),
                s -> setInt(MaidSmartConfig.COMBAT_AUTO_SWITCH_RADIUS, s), "响应半径（格）：主人受伤或开火时，此半径内的女仆才会响应切换"));
        this.rows.add(new NumRow("援护半径（格）", String.valueOf(MaidSmartConfig.COMBAT_ASSIST_RADIUS.get()),
                s -> setInt(MaidSmartConfig.COMBAT_ASSIST_RADIUS, s),"援护半径（格，默认 16，0 = 关闭）：她没有目标时优先打「主人最近的仇人」（5 秒窗口）；目标离她与主人都超过这个半径就松手，不再追跑掉的怪。对象还要看得见、在工作圈内、非友军。"));
        // v1.1.0 实测二十一：武器权重可配置（选任务时加权随机——模组/原版各一条）
        this.rows.add(new NumRow("模组武器权重", String.valueOf(MaidSmartConfig.COMBAT_AUTO_SWITCH_MOD_WEIGHT.get()),
                s -> setDouble(MaidSmartConfig.COMBAT_AUTO_SWITCH_MOD_WEIGHT, s), "模组武器权重（默认 2.0）：万法皆通/史诗战斗/真正的力量/枪械等模组攻击任务的加权随机权重——模组武器普遍更强故默认优先（2:1 约被选 67%）"));
        this.rows.add(new NumRow("原版武器权重", String.valueOf(MaidSmartConfig.COMBAT_AUTO_SWITCH_VANILLA_WEIGHT.get()),
                s -> setDouble(MaidSmartConfig.COMBAT_AUTO_SWITCH_VANILLA_WEIGHT, s), "原版武器权重（默认 1.0）：原版五件套（近战/弓/弩/三叉戟/弹幕）的加权随机权重——设 0.5=更少选原版，设 2=与模组平起平坐"));
        // v1.1.0 实测三百七十九：模组任务参与自主切换（模组物品背书 / 可全关）
        this.rows.add(new BoolRow("模组任务参与切换", MaidSmartConfig.COMBAT_AUTO_SWITCH_ALLOW_MOD_TASKS.get(),
                v -> MaidSmartConfig.COMBAT_AUTO_SWITCH_ALLOW_MOD_TASKS.set(v),"模组任务参与自主切换（默认开）：模组攻击任务（万法皆通/史诗战斗/拔刀剑等）只在她持有【非原版物品】时才参与切换——万法皆通「万物皆武器」不再把只拿原版武器的女仆切进魔法任务；关 = 只用原版任务。"));
        // v1.2.2 实测六百二十一（反馈原文："可以配置哪些模式属于近战或者远程，然后确认
        // 这些模式哪些参与自主切换，目前是默认都能参与，模组优先。有人认为这个逻辑太
        // 笼统了"）：把「谁能参与、算近战还是远程」从写死的推断改成逐任务的表
        this.rows.add(new BoolRow("模组任务优先让位", MaidSmartConfig.COMBAT_VANILLA_YIELD_TO_MOD.get(),
                v -> MaidSmartConfig.COMBAT_VANILLA_YIELD_TO_MOD.set(v),
                "模组任务优先让位（默认开）：候选池里有模组专属攻击任务时，原版通用五件套整体让位（旧行为）；关掉 = 原版与模组同池纯按权重随机（两条权重照旧生效）"));
        this.rows.add(new TextRow("模式分类表", com.maidsmart.combat.CombatModeTable.prettyAll(),
                s -> {
                    java.util.List<String> out = com.maidsmart.combat.CombatModeTable.normalizeAll(s);
                    if (out == null) {
                        return false; // 有非法行（缺 =/缺命名空间/取值不认）：整串拒收，保留旧值
                    }
                    MaidSmartConfig.COMBAT_TASK_MODES.set(out);
                    return true;
                },"战斗模式分类表（默认空）：一行一条「任务UID=近战/远程/不参与」；「不参与」= 自主参战与换战术都不选它，近战/远程 = 以表为准。UID 见 /maid_smart combat modes。"));
        // v1.2.4 实测六百二十五：法术装备忽略表（默认排掉拔刀剑/弹幕这类"本身就是武器"的 provider）
        this.rows.add(new TextRow("法术装备忽略表",
                com.maidsmart.combat.MaidSpellCompat.spellGearIgnorePretty(),
                s -> {
                    MaidSmartConfig.COMBAT_SPELL_GEAR_IGNORE.set(
                            com.maidsmart.combat.MaidSpellCompat.normalizeIgnoreList(s));
                    return true;
                },"法术装备忽略表（默认 slashblade+youkaishomecoming）：这两个附属认的「法术装备」本身是武器、已有专属战斗模式，列在这里就不再算「会用法术」。留空 = 旧口径。"));
        // v1.1.0 实测五十八：近战/远程偏好权重（两者皆可用时选池倾向 + 战中换战术开关量）
        this.rows.add(new NumRow("近战偏好权重", String.valueOf(MaidSmartConfig.COMBAT_PREF_MELEE_WEIGHT.get()),
                s -> setInt(MaidSmartConfig.COMBAT_PREF_MELEE_WEIGHT, s),"近战偏好权重（默认 3）：近战远程武器都有、敌人在 ≤5 格时按 近战:远程 权重随机——3 配远程 1 ≈ 75% 选近战；设 0 = 永不主动选近战（近身只靠反击击退）。"));
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
        this.rows.add(new BoolRow("参战时临时把作息切「全天」", MaidSmartConfig.COMBAT_FORCE_ALL_SCHEDULE.get(),
                v -> MaidSmartConfig.COMBAT_FORCE_ALL_SCHEDULE.set(v),"参战时把作息临时切「全天」（默认开）：TLM 的攻击行为挂在 WORK 活动下，夜里白班女仆处于 REST 会站着不还手；打开后参战瞬间切全天、威胁消失后复原（10 秒安全期）。关 = 回到旧行为。"));
        this.rows.add(new NumRow("还原威胁半径（格）", String.valueOf(MaidSmartConfig.COMBAT_AUTO_SWITCH_RESTORE_THREAT_DIST.get()),
                s -> setInt(MaidSmartConfig.COMBAT_AUTO_SWITCH_RESTORE_THREAT_DIST, s), "还原威胁半径（格，默认 8）：女仆周围此范围内无敌对生物才算威胁消失、开始还原计时——比响应半径小（远处怪不该让她一直卡在战斗里回不了岗）；战斗中玩家手动给她换的任务不会被还原翻回去"));
        // v1.2.2 实测六百一十九：战斗时临时扩圈（home 模式工作范围圈，见 CombatWorkRange）
        this.rows.add(new NumRow("战斗时临时扩圈（格）", String.valueOf(MaidSmartConfig.COMBAT_WORK_RANGE.get()),
                s -> setInt(MaidSmartConfig.COMBAT_WORK_RANGE, s),"战斗时临时扩圈（格，默认 15，0 = 关闭）：排班/在家模式下接战时把工作圈放大到这个半径——否则原版每 40 tick 会把追怪的近战女仆拽回工位。取 max(本值, 当前半径)。"));
        // v1.3.3 防刷怪：发现刷怪笼就去插火把（玩家建议；home 工作区里不执行）
        this.rows.add(new BoolRow("防刷怪·发现刷怪笼就去插火把", MaidSmartConfig.COMBAT_SPAWNER_TORCH_ENABLE.get(),
                v -> MaidSmartConfig.COMBAT_SPAWNER_TORCH_ENABLE.set(v),"总开关（默认开）：扫到刷怪笼且身上有发光方块（放下后亮度 ≥ 8：火把/灯笼/萤石…；红石火把不够）就去插一个把它哑掉，优先笼顶、再同层四邻；在家/工作区圈里的笼子一律不碰。"));
        this.rows.add(new NumRow("防刷怪·搜索半径（格）", String.valueOf(MaidSmartConfig.COMBAT_SPAWNER_TORCH_RADIUS.get()),
                s -> setDouble(MaidSmartConfig.COMBAT_SPAWNER_TORCH_RADIUS, s),"搜索半径（格，默认 12，4~32）：她每 4 秒在周围这个水平半径、上下各 4 格里找一次刷怪笼（只处理最近那个）。调大能提前发现远处，但扫描成本按半径平方涨，12 已够一般矿道/地牢。"));
        this.rows.add(new BoolRow("防刷怪·优先去插（打断当前活儿）", MaidSmartConfig.COMBAT_SPAWNER_TORCH_PRIORITY.get(),
                v -> MaidSmartConfig.COMBAT_SPAWNER_TORCH_PRIORITY.set(v),"发现刷怪笼后放下手上的活先把它哑掉：这段时间她独占走位（取消 WALK_TARGET + 掐掉挖矿/伐木/农活驱动的直连寻路），插上再回去。关掉 = 照旧会去插，但干活时会被驱动按在工位上。"));
        // 实测八百二十二：模组刷怪笼无视光照 → 直接拆（默认开，火把降为可选）
        this.rows.add(new BoolRow("防刷怪·发现刷怪笼直接拆掉", MaidSmartConfig.COMBAT_SPAWNER_BREAK.get(),
                v -> MaidSmartConfig.COMBAT_SPAWNER_BREAK.set(v),"默认开。整合包里绝大多数刷怪笼无视光照，插火把压不住——走过去用镐挖掉才真正断刷怪。正在拆的女仆不被自动参战切走（钉住），够得着的怪原地反击、不追怪；血量跌破自保线时自保接管。"));
        this.rows.add(new BoolRow("防刷怪·拆刷怪笼要求带镐", MaidSmartConfig.COMBAT_SPAWNER_BREAK_PICKAXE.get(),
                v -> MaidSmartConfig.COMBAT_SPAWNER_BREAK_PICKAXE.set(v),"默认开：只影响速度——正确的镐约 4~15 刻（0.2~0.75 秒）拆完；徒手慢 3.3 倍、约 25 秒，中途极易被打断。没镐时会从背包/精妙背包自动换一把；一件都没有就先放过。"));
        // 实测八百三十：只在真在刷怪时才拆（+ 隔墙不拆）
        this.rows.add(new BoolRow("防刷怪·只在真在刷怪时才拆", MaidSmartConfig.COMBAT_SPAWNER_BREAK_ACTIVE_ONLY.get(),
                v -> MaidSmartConfig.COMBAT_SPAWNER_BREAK_ACTIVE_ONLY.set(v),"只在刷怪笼真的在刷怪时才拆（默认开）：现在没在刷的（空笼子 / 只刷和平生物 / 被光照压住）一律不碰，留给你收走或改造；只刷敌对且此刻确实在出货才动手。隔墙不拆恒生效；关 = 见到就拆。"));
        // 实测八百四十五：拆除黑名单——写进去的笼子任何情况下都不拆（与上面那项正交）
        this.rows.add(new TextRow("防刷怪·拆除黑名单（永不拆）",
                String.join(",", (List<String>) MaidSmartConfig.COMBAT_SPAWNER_BREAK_BLACKLIST.get()),
                s -> setStringList(MaidSmartConfig.COMBAT_SPAWNER_BREAK_BLACKLIST, s),"写进去的刷怪笼\u00a7e任何情况下都不拆\u00a7r：empty = 空笼子 / benign = 只刷和平生物 / unreadable = 读不出数据 / 或实体注册名。默认 empty,benign。"));
    }

    private void selfTacticsRows() {
        // v1.5.186：原"近战搭高上限/远程搭高上限 + 近战/远程搭方块冷却"合并为唯一
        // 控制项"至多向上搭多少个方块"（默认 30，不再按敌人近战/远程划分）
        this.rows.add(new NumRow("至多向上搭多少个方块", String.valueOf(MaidSmartConfig.COMBAT_PILLAR_MAX.get()),
                s -> setInt(MaidSmartConfig.COMBAT_PILLAR_MAX, s), "至多向上搭多少个方块（格，默认 30）：濒死被怪物围攻时垫高躲开的上限（够不着就行）"));
        // v1.5.203：搭高安全高度（与落地水触发高度配对）
        this.rows.add(new NumRow("搭高安全高度（格）", String.valueOf(MaidSmartConfig.COMBAT_PILLAR_SAFE_HEIGHT.get()),
                s -> setInt(MaidSmartConfig.COMBAT_PILLAR_SAFE_HEIGHT, s),"搭高安全高度（默认 5）：无威胁时搭高回落到的绝对兜底高度（有威胁时改用「高于敌方格数」）。与「落地水触发高度」（默认 3.0）配对：垫到 5 格跳下，落到 3.0 时离地约 2 格，正好放水。"));
        // v1.5.394 实测八百二十：高于敌方格数——对策监守者/远程 Boss
        this.rows.add(new NumRow("高于敌方格数", String.valueOf(MaidSmartConfig.COMBAT_PILLAR_ABOVE_ENEMY.get()),
                s -> setInt(MaidSmartConfig.COMBAT_PILLAR_ABOVE_ENEMY, s),"高于敌方格数（默认 30）：自保搭高时爬到她比敌人高这么多格——对策监守者/远程 Boss（旧版只垫 5 格，出不了射程仍挨打）。上限受「至多向上搭多少格」封顶；无威胁时回落「搭高安全高度」。"));
        this.rows.add(new NumRow("治疗冷却（tick）", String.valueOf(MaidSmartConfig.COMBAT_HEAL_COOLDOWN.get()),
                s -> setInt(MaidSmartConfig.COMBAT_HEAL_COOLDOWN, s), "治疗食物冷却（tick）：吃食物回血的最短间隔，防狂吃"));
        this.rows.add(new NumRow("药水尝试间隔（tick）", String.valueOf(MaidSmartConfig.COMBAT_POTION_COOLDOWN.get()),
                s -> setInt(MaidSmartConfig.COMBAT_POTION_COOLDOWN, s), "药水尝试间隔（tick）：尝试喝增益药水（再生/迅捷）的最短间隔"));
        this.rows.add(new NumRow("卡住判定窗口（tick）", String.valueOf(MaidSmartConfig.COMBAT_STUCK_WINDOW.get()),
                s -> setInt(MaidSmartConfig.COMBAT_STUCK_WINDOW, s), "卡住判定窗口（tick）：逃跑中 N 秒没位移判定为卡住（然后垫台阶翻越）"));
        this.rows.add(new NumRow("卡住位移阈值", String.valueOf(MaidSmartConfig.COMBAT_STUCK_THRESHOLD.get()),
                s -> setDouble(MaidSmartConfig.COMBAT_STUCK_THRESHOLD, s), "卡住位移阈值（格）：窗口内位移小于此值算卡住"));
        this.rows.add(new NumRow("走位速度", String.valueOf(MaidSmartConfig.COMBAT_FLEE_SPEED.get()),
                s -> setDouble(MaidSmartConfig.COMBAT_FLEE_SPEED, s), "走位速度倍率：自保小幅走位（拉开身位）时的移动加成（1.0=正常）——调大更容易脱离贴身但可能撞墙/钻死角"));
        this.rows.add(new NumRow("警示粒子间隔（tick）", String.valueOf(MaidSmartConfig.COMBAT_ALERT_COOLDOWN.get()),
                s -> setInt(MaidSmartConfig.COMBAT_ALERT_COOLDOWN, s), "警示粒子间隔（tick）：女仆头顶危险警示粒子的刷新间隔"));
        this.rows.add(new NumRow("策略播报间隔（tick）", String.valueOf(MaidSmartConfig.COMBAT_ANNOUNCE_COOLDOWN.get()),
                s -> setInt(MaidSmartConfig.COMBAT_ANNOUNCE_COOLDOWN, s), "策略播报间隔（tick，防刷屏）"));
    }

    private void escapeRows() {
        this.rows.add(new BoolRow("TLM 瞬移豁免", MaidSmartConfig.TLM_TELEPORT_EXEMPT.get(),
                v -> MaidSmartConfig.TLM_TELEPORT_EXEMPT.set(v),"TLM 瞬移/跟随豁免（默认开）：她在【逃跑/蹲下或坐下/骑乘或坐载具/滑翔/骑扫帚腾空/建造/搭路/主人已死亡】时，TLM 的「离主人太远自动传送」与跟随启动一律不生效。关 = 完全回到原版。"));
        this.rows.add(new NumRow("传送成功冷却（tick）", String.valueOf(MaidSmartConfig.COMBAT_TELEPORT_COOLDOWN.get()),
                s -> setInt(MaidSmartConfig.COMBAT_TELEPORT_COOLDOWN, s), "传送成功冷却（tick，默认 600=30 秒）：成功传送回主人身边后此冷却内不再传——一场遭遇战最多被接走一次；传送失败（主人身边有怪）5 秒后即重试"));
        this.rows.add(new NumRow("传送安全判定半径", String.valueOf(MaidSmartConfig.COMBAT_TELEPORT_SAFE_RADIUS.get()),
                s -> setDouble(MaidSmartConfig.COMBAT_TELEPORT_SAFE_RADIUS, s), "传送安全判定半径（格）：主人身边此半径内【无可见怪物】才传送回主人（v1.5.150 起只判主人身边；默认 5 格防远程怪，调小更容易传回家）"));
        this.rows.add(new NumRow("珍珠逃生冷却（tick）", String.valueOf(MaidSmartConfig.COMBAT_PEARL_COOLDOWN.get()),
                s -> setInt(MaidSmartConfig.COMBAT_PEARL_COOLDOWN, s), "珍珠逃生冷却（tick，20=1 秒）：扔末影珍珠脱身的最短间隔，防连扔"));
        this.rows.add(new NumRow("珍珠逃生触发血量（0-1）", String.valueOf(MaidSmartConfig.COMBAT_PEARL_RATIO.get()),
                s -> setDouble(MaidSmartConfig.COMBAT_PEARL_RATIO, s), "末影珍珠逃生触发血量（0-1，低于此值且威胁贴身才扔）"));
        this.rows.add(new NumRow("珍珠逃生威胁距离", String.valueOf(MaidSmartConfig.COMBAT_PEARL_DIST.get()),
                s -> setDouble(MaidSmartConfig.COMBAT_PEARL_DIST, s), "末影珍珠逃生威胁距离（威胁小于此格数才扔珍珠）"));
    }

    private void cookBrewRows() {
        this.rows.add(new NumRow("烧制搜索范围", String.valueOf(MaidSmartConfig.MISC_COOK_RADIUS.get()),
                s -> setInt(MaidSmartConfig.MISC_COOK_RADIUS, s),"烧制搜索范围（格）：烧制任务在这个半径内找熔炉 / 高炉 / 烟熏炉（兼容矿石与高炉、烟熏炉）。"));
        this.rows.add(new NumRow("酿造搜索范围", String.valueOf(MaidSmartConfig.MISC_BREW_RADIUS.get()),
                s -> setInt(MaidSmartConfig.MISC_BREW_RADIUS, s), "酿造搜索范围（格）：酿造任务在这个半径内找酿造台"));
        this.rows.add(new NumRow("处理间隔（tick）", String.valueOf(MaidSmartConfig.MISC_PROCESS_COOLDOWN.get()),
                s -> setInt(MaidSmartConfig.MISC_PROCESS_COOLDOWN, s), "处理间隔（tick，20=1 秒）：烹饪/酿造每处理一批的间隔"));
        // v1.1.0 实测一百五十七：熔炉兼容矿物类可烧制物
        this.rows.add(new BoolRow("熔炉烧矿物", MaidSmartConfig.MISC_COOK_SMELT_ORES.get(),
                v -> MaidSmartConfig.MISC_COOK_SMELT_ORES.set(v),"烧制任务背包没有食材时，兼容带矿物/原料标签（forge:ores、raw_materials 等）且当前世界有熔炉配方的物品——铁矿石/粗铁/远古残骸等照常进炉；关闭 = 只烧食材白名单。"));
        // v1.1.0 实测一百八十二：通用可烧制物回退——治"只投燃料不投烧制物"
        this.rows.add(new BoolRow("烧任何可烧制物", MaidSmartConfig.MISC_COOK_SMELT_ANY.get(),
                v -> MaidSmartConfig.MISC_COOK_SMELT_ANY.set(v),"背包没有食材/矿物标签物品时，回退喂任何【有熔炉配方且非装备类】的物品——沙子→玻璃、圆石→石头、原木→木炭等（装备类永不熔：铁金钻石工具盔甲有烧粒配方会被排除）；关闭 = 只按前面两条喂。"));
        // v1.1.0 实测一百五十八：兼容高炉/烟熏炉
        this.rows.add(new BoolRow("兼容高炉/烟熏炉", MaidSmartConfig.MISC_COOK_SMOKER_BLAST.get(),
                v -> MaidSmartConfig.MISC_COOK_SMOKER_BLAST.set(v), "烧制任务不只操作熔炉：高炉按高炉配方喂料（矿石/粗金属）、烟熏炉按烟熏配方喂料（生食），成品/燃料照常；高炉喂料受「熔炉烧矿物」开关约束（高炉只烧矿物）；关闭 = 只操作熔炉"));
        this.rows.add(new NumRow("任务垂直范围", String.valueOf(MaidSmartConfig.MISC_VERTICAL_RANGE.get()),
                s -> setInt(MaidSmartConfig.MISC_VERTICAL_RANGE, s), "任务垂直范围（格）：烹饪/酿造在上/下多少格内搜索容器"));
        // v1.2.5 实测六百五十二 / v1.3.6 实测六百六十一：烧制与燃料勾选（两个科目、每项一个勾）
        this.rows.add(new BtnRow("烧制/燃料勾选",
                "打开 →（烧制 " + cookSummary(0) + " · 燃料 " + cookSummary(1) + "）",
                () -> {
                    this.cookTable = true;
                    this.cookTableMode = 0;
                    this.m_7856_();
                },"两个科目各一页，每个物品一个勾：勾上 = 可以（当原料进炉子 / 当柴烧），取消 = 不可以；两张表默认都空 = 保持自动判定。点图标即切换，也可手填注册名；老名单沿用原来那四个键、不会丢。"));
        // v1.2.5 实测六百五十二：顺手补上「烧木材」开关——它一直只写在 toml 里，面板漏了这一行
        this.rows.add(new BoolRow("烧木材", MaidSmartConfig.MISC_COOK_BURN_WOOD.get(),
                v -> MaidSmartConfig.MISC_COOK_BURN_WOOD.set(v),"烧木材（默认关）：木材类（原木/木板/树苗/竹等）默认当不了原料（避免用木头烧木头）；勾选后照常可烧（仍受「烧任何可烧制物」与清单约束）。这只是原料黑名单——当燃料不受它管。"));
    }

    private void farmRows() {
        // v1.1.0 实测三百一十一：宰杀任务阈值
        this.rows.add(new NumRow("宰杀数量阈值", String.valueOf(MaidSmartConfig.MISC_SLAUGHTER_COUNT.get()),
                s -> setInt(MaidSmartConfig.MISC_SLAUGHTER_COUNT, s), "宰杀任务：女仆周围 5×5 内同种牲畜（按类型分组）超过此数 → 每 3 秒随机宰杀一只该组牲畜；≤ 阈值不动"));
        // v1.5.130：产出型任务专项增强
        this.rows.add(new BoolRow("产出任务增强", MaidSmartConfig.MISC_PRODUCE_TASK_ENHANCE.get(),
                v -> MaidSmartConfig.MISC_PRODUCE_TASK_ENHANCE.set(v), "农场：一次收割/补种目标周围 3x3 整片作物（来回跑减少到约 1/8）；钓鱼：附近没椅子/船时主动找开阔水域，自带坐垫生成在岸边"));
        // v1.5.161：农场连锁收获 / 收获物自动收集（v1.5.189：连锁默认开启）
        this.rows.add(new BoolRow("农场连锁收获", MaidSmartConfig.MISC_CHAIN_HARVEST.get(),
                v -> MaidSmartConfig.MISC_CHAIN_HARVEST.set(v), "农场连锁收获：收割时以目标格为中心蔓延连锁收割相连农田里的成熟作物（大农田多轮清完）；默认开启"));
        // v1.2.4 实测六百四十九（issue #22 第三条）：这一行的旧名"收获物自动收集"会让人
        // 以为"关掉 = 产物留在地上等人捡"，而 TLM 本体的 TaskNormalFarm.harvest 走的是
        // EntityMaid.dropResourcesToMaidInv——**收割产物本来就进女仆背包**（javap 实证）。
        // 本开关真正管的是"我们的连锁/整片收割要不要顺手把地上的掉落物直接拾取掉"，
        // 所以改成如实描述，避免与本体行为对不上。
        this.rows.add(new BoolRow("农场掉落物立即拾取", MaidSmartConfig.MISC_AUTO_COLLECT.get(),
                v -> MaidSmartConfig.MISC_AUTO_COLLECT.set(v),"我们做连锁/整片收割时，顺手把收割点附近的掉落物直接拾取进她背包（默认关闭）。TLM 本体收割本来就把产物直接放进背包（不落地），这一行真正的差别是把散落的掉落物也立即捡掉。"));
        this.rows.add(new BoolRow("背包满时装进精妙背包", MaidSmartConfig.MISC_BACKPACK_OVERFLOW.get(),
                v -> MaidSmartConfig.MISC_BACKPACK_OVERFLOW.set(v),"背包满时装进精妙背包（默认开）：她自己背包塞不下时，把溢出的那一份再试一次身上的「额外容器」。需要 Curios 在场 + TLM「女仆饰品」开启 + 真的戴着；额外容器再塞不下才落地（绝不吞物品）。"));
        // 实测七百七十二：超越维度存储联动总开关（默认关）——两树面板对齐
        this.rows.add(new BoolRow("超越维度存储联动", MaidSmartConfig.MISC_BD_STORAGE.get(),
                v -> MaidSmartConfig.MISC_BD_STORAGE.set(v),"超越维度存储联动（默认关）：产物自动存进主人的主网络，并按规则从网络取货（规则文件在 config/ 下）。总开关，每只女仆还要单独开 /maid_smart bd_deposit true。"));
        // 实测七百七十二：规则名单进面板子页（点物品图标加/减，不加新贴图）
        this.rows.add(new BtnRow("超越维度规则名单",
                "管理 →（搬 " + com.maidsmart.bd.MaidBdNetworking.MOVE.size()
                        + " · 禁 " + com.maidsmart.bd.MaidBdNetworking.KEEP.size()
                        + " · 保留N " + com.maidsmart.bd.MaidBdNetworking.KEEP_N.size()
                        + " · 至少留N " + com.maidsmart.bd.MaidBdNetworking.AT_LEAST.size() + "）",
                () -> {
                    this.bdRules = true;
                    this.bdRuleMode = 0;
                    this.creativePage = 0;
                    com.maidsmart.bd.MaidBdNetworking.requestRuleState();
                    this.m_7856_();
                },"管理超越维度的自定义规则名单（一定搬 / 一定不搬 / 保留N个 / 至少留N个）：点图标加入/取消；保留N/至少留N 用数字框填；标签手输 tag:c:ores。改服务端规则文件（需要 OP）。"));
        // 实测七百七十二：每女仆"产出回收"开关——面板一行，发 C2S 由服务端取 64 格内最近一只自己的女仆
        this.rows.add(new BtnRow("产出回收（最近女仆）",
                com.maidsmart.bd.MaidBdNetworking.PER_MAID_ON ? "\u00a7a已开" : "\u00a78已关",
                () -> {
                    com.maidsmart.bd.MaidBdNetworking.togglePerMaid(
                            "", !com.maidsmart.bd.MaidBdNetworking.PER_MAID_ON);
                },"开/关「产出回收」——作用于 64 格内最近的一只你自己的女仆（每只女仆各一份，随魂符/存档走）。开了以后产物才会进主人的主网络。等于 /maid_smart bd_deposit true。"));
        // v1.5.163：农场连锁收获上限可自定义
        this.rows.add(new NumRow("连锁收获上限（格）", String.valueOf(MaidSmartConfig.MISC_CHAIN_HARVEST_LIMIT.get()),
                s -> setInt(MaidSmartConfig.MISC_CHAIN_HARVEST_LIMIT, s), "农场连锁收获上限（格）：一次连锁收割的最大格数（4~96，默认 24）"));
        // v1.5.236：农场批量种植（与连锁收获同格式）
        this.rows.add(new BoolRow("批量种植", MaidSmartConfig.MISC_BATCH_PLANT.get(),
                v -> MaidSmartConfig.MISC_BATCH_PLANT.set(v), "农场批量种植：种植时以当前格为中心蔓延，把相连农田里的空耕地一次全种上（种子真实消耗）；默认开启"));
        this.rows.add(new NumRow("批量种植上限（格）", String.valueOf(MaidSmartConfig.MISC_BATCH_PLANT_LIMIT.get()),
                s -> setInt(MaidSmartConfig.MISC_BATCH_PLANT_LIMIT, s), "农场批量种植上限（格）：一次批量种植的最大格数（4~96，默认 24）"));
        // v1.1.0 实测三百五十二：树苗骨粉催熟（三百五十三：0.5 秒一株 + 粒子反馈）
        this.rows.add(new BoolRow("树苗骨粉催熟", MaidSmartConfig.MISC_MAID_BONEMEAL_SAPLING.get(),
                v -> MaidSmartConfig.MISC_MAID_BONEMEAL_SAPLING.set(v),"伐木模式的女仆背包有骨粉时，对身边 6 格内的树苗催熟——每 0.5 秒一次，优先催已种下的；骨粉不受光照限制，但树上方被实心方块挡死的不浪费；深色橡树苗不催（单株永不生长）；默认开。"));
        // v1.1.0 实测三百五十五：农场作物骨粉催熟（与树苗同款逻辑）
        this.rows.add(new BoolRow("农场作物骨粉催熟", MaidSmartConfig.MISC_MAID_BONEMEAL_FARM.get(),
                v -> MaidSmartConfig.MISC_MAID_BONEMEAL_FARM.set(v),"农场模式的女仆背包里有骨粉时，对身边 16 格内未成熟的作物用骨粉催熟——每 0.5 秒一株；只催当前世界有骨粉配方的作物，成熟的不催；施肥时主手临时换骨粉、停 1 秒还原；默认开。"));
    }

    private void idleRows() {
        // v1.1.0 实测一百八十三：空闲散步——治 TLM 原生散步又少又慢又近
        this.rows.add(new BoolRow("空闲散步", MaidSmartConfig.MISC_STROLL_ENABLED.get(),
                v -> MaidSmartConfig.MISC_STROLL_ENABLED.set(v), "女仆空闲时按间隔主动散步（默认开）——替代 TLM 原生散步（原生只有 0.3 倍速、5 格半径、平均一两小时才走一次）；战斗/自保/站桩工作/有移动目标时不打扰"));
        // v1.3.0 实测六百六十六：这三行补上**合法范围**（旧版一个字都没写，玩家当然不知道
        // 有上限——填了 4000000 就是越界，然后被静默吞掉），并改用带范围的 setter：
        // 越界当场红字、保存时钳到边界并提示，不再出现"填了没反应"
        this.rows.add(new NumRow("散步间隔（tick）", String.valueOf(MaidSmartConfig.MISC_STROLL_INTERVAL.get()),
                this.setIntInRange(MaidSmartConfig.MISC_STROLL_INTERVAL, "散步间隔", 20, 1728000),"空闲女仆每隔这么久散步一次（默认 200 = 10 秒，范围 20~1728000；TLM 原生平均一两小时才走一次）；找得到落点就走，找不到顺延。想让她基本别乱跑就填大值，或关掉「空闲散步」开关。",
                20.0, 1728000.0));
        this.rows.add(new NumRow("散步半径（格）", String.valueOf(MaidSmartConfig.MISC_STROLL_RADIUS.get()),
                this.setIntInRange(MaidSmartConfig.MISC_STROLL_RADIUS, "散步半径", 4, 128),
                "每次散步在周围这个半径内随机选点（默认 16，范围 4~128；排班/在家模式下不会超出「排班活动半径」）",
                4.0, 128.0));
        this.rows.add(new NumRow("散步速度倍率", String.valueOf(MaidSmartConfig.MISC_STROLL_SPEED.get()),
                this.setDoubleInRange(MaidSmartConfig.MISC_STROLL_SPEED, "散步速度倍率", 0.05, 2.5),"散步移动速度倍率（默认 0.4，0.05~2.5）：乘在她 0.7 的基础移速上；格/秒非线性，0.2 以下几乎不走。命令 /maid_smart stroll speed 当场改。",
                0.05, 2.5));
        // v1.5.129：原生任务呆滞修复 + 干活不被打断
        this.rows.add(new BoolRow("原生任务流畅化", MaidSmartConfig.MISC_NATIVE_TASK_SMOOTH.get(),
                v -> MaidSmartConfig.MISC_NATIVE_TASK_SMOOTH.set(v), "TLM 原生任务（种田/挤奶/钓鱼等）呆滞修复：任务行为不再每 3 秒重启、随机散步不再覆盖任务目标、走路少刹车、检查节流减半"));
        this.rows.add(new BoolRow("干活不被打断", MaidSmartConfig.MISC_WORK_UNINTERRUPTED.get(),
                v -> MaidSmartConfig.MISC_WORK_UNINTERRUPTED.set(v), "干活中跳过：吃饭（刷好感餐）、偷吃（拆浆果丛）、小伤恐慌逃跑（血量<30% 仍会跑）、切班时被拽回工位"));
    }

    private void hudRows() {
        // 实测四百二十一：冷却可视化 HUD（复活倒计时 / 回魂符冷却显示在屏幕上）
        this.rows.add(new BoolRow("冷却可视化 HUD", MaidSmartConfig.MISC_COOLDOWN_HUD.get(),
                v -> MaidSmartConfig.MISC_COOLDOWN_HUD.set(v), "冷却可视化 HUD（默认开）：屏幕左上角实时显示本人女仆的自动复活倒计时与回魂符冷却倒计时（女仆死亡等待复活、或放出后处于回魂符冷却窗口时显示）；关掉不显示也不发同步包"));
        // 实测五百七十三：中键工位标记开关（与 TLM 自带的「河童的罗盘」写同一份排班锚点）
        this.rows.add(new BoolRow("中键工位标记", MaidSmartConfig.MISC_WORK_POS_MARKER.get(),
                v -> MaidSmartConfig.MISC_WORK_POS_MARKER.set(v),"潜行+中键方块 = 把身边在家/排班女仆的工位锚点标到那个方块（范围 = 排班活动半径）。与 TLM 河童的罗盘写同一份锚点（后写者生效）——手持罗盘时自动让位；关 = 中键交还原版取方块。"));
        this.rows.add(new NumRow("气泡限频（毫秒）", String.valueOf(MaidSmartConfig.MISC_BUBBLE_LIMIT_MS.get()),
                s -> setInt(MaidSmartConfig.MISC_BUBBLE_LIMIT_MS, s), "气泡限频（毫秒）：对话气泡的最短显示间隔，防连续说话刷屏"));
    }

    private void utilityRows() {
        // v1.5.396 实测八百三十五：预设模式只读一行——切换动作在手册首页「DIY / 默认模式」页做
        this.rows.add(new InfoRow("当前模式", com.maidsmart.config.MaidConfigPreset.isDiy() ? "DIY 模式" : "默认模式",
                "模式（只读）：在手册首页的「DIY / 默认模式」页一键切换——DIY 模式 = 关掉全部「介入女仆行为」的开关（像 TLM 原版），默认模式 = 恢复出厂推荐配置。这里只显示当前状态"));
        this.rows.add(new BoolRow("床铺互通", MaidSmartConfig.MISC_BED_INTEROP.get(),
                v -> MaidSmartConfig.MISC_BED_INTEROP.set(v), "床铺互通（默认开）：女仆能睡原版床（16 色床——TLM 原生只认女仆床），玩家也能睡女仆床（并把女仆床设为重生点）——两个方向互开；关掉恢复 TLM 原版行为。玩家潜行右键女仆床仍是只染色不躺下"));
        // 实测四百四十三：悬空禁搭方块（反馈："悬空状态应禁止搭建方块——挖矿/伐木也通用"）
        this.rows.add(new BoolRow("悬空禁搭方块", MaidSmartConfig.MISC_NO_PLACE_IN_AIR.get(),
                v -> MaidSmartConfig.MISC_NO_PLACE_IN_AIR.set(v),"悬空禁搭方块（默认开）：她未落地时不再搭方块（自保搭高/搭路/挖矿垫脚/伐木垫脚）。触发：坠落距离达到「落地水触发高度」时禁（落地水此时接管，搭方块会挡住它）。水/岩浆、骑乘、滑翔不算悬空。"));
        // 实测五百三十六：不得搭在主人身上（目标格被主人碰撞箱占着就不搭）
        this.rows.add(new BoolRow("不得搭在主人身上", MaidSmartConfig.MISC_NO_PLACE_ON_OWNER.get(),
                v -> MaidSmartConfig.MISC_NO_PLACE_ON_OWNER.set(v),"不得搭在主人身上（默认开）：目标格被主人碰撞箱占着时不搭方块——覆盖自保搭高/搭路/挖矿垫脚/伐木垫脚/插火把/蓝图建造；蓝图类是延后而非跳过，你让开后自动续建。关 = 旧行为。"));
        // 实测四百四十八：蛋糕可食用（反馈建议的兜底逃生通道）
        this.rows.add(new BoolRow("蛋糕可食用", MaidSmartConfig.MISC_CAKE_EDIBLE.get(),
                v -> MaidSmartConfig.MISC_CAKE_EDIBLE.set(v),"蛋糕可食用（默认开）：女仆把蛋糕当食物——吃整块回 14 点生命并 +10 好感，玩家用蛋糕右击也会投喂。关闭后恢复原版（只能放置）——这是与第三方模组冲突时的逃生通道。"));
        // v1.1.0 实测二百三十四：手持光源发实光（隐藏光块跟随；不影响插火把）
        this.rows.add(new BoolRow("手持光源发实光", MaidSmartConfig.MISC_HELD_LIGHT_ENABLED.get(),
                v -> MaidSmartConfig.MISC_HELD_LIGHT_ENABLED.set(v), "手持光源发实光（默认开）：她主/副手拿火把/灯笼/萤石等光源时，脚底自动跟随一个隐形光块（亮度与该光源一致），周围被真实照亮；不拿光源自动熄灭；与其他环境光源同待遇，不影响插火把判定逻辑本身"));
        // v1.5.394 实测八百零五：玩家点名的「大运」飞行 BGM 选曲——放在这里（系统与杂项）
        // 实测八百一十七续：改成**点击循环**的 CycleRow（旧版是数字输入框，），与「建造速度档位」同款。
        String[] daYunBgmOpts = {"关", "1 · 天真的橡皮DJ", "2 · Tom Tom"};
        int daYunBgmNow = Math.max(0, Math.min(2, MaidSmartConfig.DAYUN_BGM.get()));
        this.rows.add(new CycleRow("大运飞行 BGM", daYunBgmOpts, daYunBgmOpts[daYunBgmNow],
                v -> {
                    for (int i = 0; i < daYunBgmOpts.length; i++) {
                        if (daYunBgmOpts[i].equals(v)) {
                            MaidSmartConfig.DAYUN_BGM.set(i);
                            return;
                        }
                    }
                },"默认 1，可选 1 / 2。戴着「大运」鞘翅滑翔时播放；第 1 首 = 天真的橡皮DJ，第 2 首 = Tom Tom。200 格内听得见；自己的曲子优先，非自己放时只留最后响起的那条。"));
    }

    private void followRows() {
        // v1.5.142：跨维度跟随
        this.rows.add(new BoolRow("跨维度跟随", MaidSmartConfig.MISC_DIMENSION_FOLLOW.get(),
                v -> MaidSmartConfig.MISC_DIMENSION_FOLLOW.set(v),"主人换维度后，女仆自动传送到主人身边（约 5 秒扫描一轮）；坐着的/骑乘的/主人身边无可站立点时不拉。守家（home）模式也照常跟随跨维度。"));
        // v1.1.0 实测一百三十四：同维度远距拉回（跨区块传送兜底）
        this.rows.add(new BoolRow("同维度远距拉回", MaidSmartConfig.MISC_MAID_SAME_DIM_PULL.get(),
                v -> MaidSmartConfig.MISC_MAID_SAME_DIM_PULL.set(v),"女仆与主人同维度但距离超过阈值时自动传送到主人身边（跨区块传送兜底——TLM 自带的过远传送只对非 home 非工作的跟随女仆生效、还可能静默失败）。守家/坐姿/骑乘/干活中不拉。"));
        // v1.1.0 实测一百五十一：跟随收紧（参考改版 TLM jar——每 tick 重断言跟随目标）
        this.rows.add(new BoolRow("跟随收紧", MaidSmartConfig.MISC_FOLLOW_TIGHTEN.get(),
                v -> MaidSmartConfig.MISC_FOLLOW_TIGHTEN.set(v),"跟随模式的女仆每 tick 重新断言跟随目标——平常跟随在 4 格以内，被其他行为/寻路干扰走远时立即拉回，不再走走停停（照改版 TLM jar 的设计）；关闭 = 官方 1.5.3 行为。"));
        // 实测八百一十四：干活时把移速压回 0 好感档
        this.rows.add(new BoolRow("干活时移速压回 0 好感档", MaidSmartConfig.MISC_WORK_SPEED_LEVEL0.get(),
                v -> MaidSmartConfig.MISC_WORK_SPEED_LEVEL0.set(v),"默认开：工作/生产任务的女仆不论好感多高，移动速度都按「0 好感档」走（钳到基础移速 0.7）；战斗/空闲/跟随/骑乘保持原样。注：TLM 本身没有「好感越高越快」这条加成，显快的是饰品栏与散步倍率。"));
        // 实测八百一十五：干活时不许出圈
        this.rows.add(new BoolRow("干活时不许出圈", MaidSmartConfig.MISC_WORK_STAY_IN_CIRCLE.get(),
                v -> MaidSmartConfig.MISC_WORK_STAY_IN_CIRCLE.set(v),"默认开：烧制/酿造/宰杀没活可干时的闲逛，取点锚点改为固定圆心（home = 玩家画的圈心 / 跟随 = 主人身边），跟随模式也必须落在圈内。半径 = 「排班活动半径」；关掉 = 跟随模式完全不限。"));
        // 实测八百一十五：罗盘圈优先
        this.rows.add(new BoolRow("罗盘圈优先", MaidSmartConfig.MISC_COMPASS_PRIORITY.get(),
                v -> MaidSmartConfig.MISC_COMPASS_PRIORITY.set(v),"默认开：玩家用河童的罗盘 / 潜行+中键画的圈会记一个「玩家圈」标记——排班表与建造的自动锚定一律让位，不再顶掉玩家画的圈；跟随中干活选点也被钳在圈里。罗盘与中键后写者生效；关 = 旧行为。"));
        // 实测八百一十六：非战斗模式下女仆移速硬上限
        this.rows.add(new BoolRow("非战斗移速上限", MaidSmartConfig.MISC_NONCOMBAT_SPEED_CAP.get(),
                v -> MaidSmartConfig.MISC_NONCOMBAT_SPEED_CAP.set(v),"非战斗移速上限（默认开）：非战斗时她靠腿走的速度（导航/闲逛/农活/跟随/赶路）都钳在一个口子上——默认倍率会飙到 8~14 格/秒，钳到 0.56 ≈ 玩家奔跑；战斗/逃跑/飞行/骑乘不钳。"));
        this.rows.add(new NumRow("非战斗移速上限", String.valueOf(MaidSmartConfig.MISC_NONCOMBAT_MAX_SPEED.get()),
                this.setDoubleInRange(MaidSmartConfig.MISC_NONCOMBAT_MAX_SPEED, "非战斗移速上限", 0.05, 1.5),"默认 0.56（同「散步速度倍率」单位）。对照：0.4→3.3、0.6→6、1.0→14 格/秒；玩家奔跑 5.61，0.56 ≈ 持平。填大放宽、填小更老实（0.2 以下几乎不走）。"));
        this.rows.add(new NumRow("同维度拉回距离（格）", String.valueOf(MaidSmartConfig.MISC_MAID_SAME_DIM_DIST.get()),
                s -> setInt(MaidSmartConfig.MISC_MAID_SAME_DIM_DIST, s), "女仆与主人同维度且距离超过此值才拉回（默认 48 格）：低于此值靠走路/跟随，不打扰她"));
        // v1.1.0 实测一百八十八：Y 轴拉回门槛（反馈："传送机制不检测 Y 轴"）
        this.rows.add(new NumRow("Y 轴拉回门槛（格）", String.valueOf(MaidSmartConfig.MISC_MAID_SAME_DIM_VERTICAL.get()),
                s -> setInt(MaidSmartConfig.MISC_MAID_SAME_DIM_VERTICAL, s),"女仆与主人同维度、距离没超上一条但【垂直高度差】超本值时——主人旁边 16 格内有安全落点就传送过来，没有则不传（默认 16；旧版只按 48 格 3D 距离判，竖直搭高 30 格的女仆永不触发）。"));
        // v1.3.0(beta) 实测七百〇二：仿创造飞行（1.20.1 精简版——资格两路：物品表 + 效果表）
        this.rows.add(new BoolRow("仿创造飞行", MaidSmartConfig.MISC_FREE_FLIGHT.get(),
                v -> MaidSmartConfig.MISC_FREE_FLIGHT.set(v),"默认关：打开后有资格的女仆会悬浮并自由升降（创造飞行手感）。资格两路：物品表/效果表。收工若在半空会先软着陆再交还重力。1.20.1 精简版不含重力归零与数据组件判据。"));
        this.rows.add(new BoolRow("滑翔时用鞘翅动画", MaidSmartConfig.MISC_GLIDE_ELYTRA_ANIM.get(),
                v -> MaidSmartConfig.MISC_GLIDE_ELYTRA_ANIM.set(v),"默认关 = 沿用游泳动作（兼容性最好，官方包与第三方包普遍都有 swim）。打开后改用模型包里同名的 elytra_fly——做了这条动画的模型滑翔时播它；没做的会落到站立姿态，确认有再开。"));
        this.rows.add(new BoolRow("仿创造飞行·智能待命", MaidSmartConfig.MISC_FREE_FLIGHT_IDLE.get(),
                v -> MaidSmartConfig.MISC_FREE_FLIGHT_IDLE.set(v),"默认开：主人停下不动满下面的秒数后，她软着陆到你脚边站好（同高度、约 2 格），你再一动自动重新起飞。原版交互距离只有 3 格，一直悬停在身后 3.5 格会让喂食/摸头够不到。关 = 始终悬停。"));
        this.rows.add(new NumRow("仿创造飞行·静止多久落地（秒）", String.valueOf(MaidSmartConfig.MISC_FREE_FLIGHT_IDLE_SECONDS.get()),
                s -> setInt(MaidSmartConfig.MISC_FREE_FLIGHT_IDLE_SECONDS, s), "默认 3 秒：主人的水平移动速度低于阈值并持续这么久 → 软着陆；期间主人一动就取消"));
        this.rows.add(new NumRow("仿创造飞行·落地贴近距离（格）", String.valueOf(MaidSmartConfig.MISC_FREE_FLIGHT_NEAR_DIST.get()),
                s -> setInt(MaidSmartConfig.MISC_FREE_FLIGHT_NEAR_DIST, s), "默认 2 格：软着陆时朝主人漂过去，最终停在他身边这个距离内（留 1 格余量给原版 3 格交互距离）"));
        this.rows.add(new BoolRow("仿创造飞行·走路的活也交给飞", MaidSmartConfig.MISC_FREE_FLIGHT_TRAVEL.get(),
                v -> MaidSmartConfig.MISC_FREE_FLIGHT_TRAVEL.set(v),"默认开：本来要走过去的活（挖矿/伐木/农活这些直连寻路目标）够远或要上下就直接飞过去，落地干活——不搭路也不绕路。近处挪一步照旧走路；自保/战斗走位/插火把/站桩/跟随不接管。"));
        this.rows.add(new NumRow("仿创造飞行·超过多远就改飞（格）", String.valueOf(MaidSmartConfig.MISC_FREE_FLIGHT_TRAVEL_DIST.get()),
                s -> setDouble(MaidSmartConfig.MISC_FREE_FLIGHT_TRAVEL_DIST, s), "默认 8 格：直连寻路的目标与她水平距离超过它就起飞；调大 = 更多路用走的（32 = 只有跨半个工作区才飞）"));
        this.rows.add(new TextRow("仿创造飞行·资格物品表", String.join(",", (List<String>) MaidSmartConfig.MISC_FREE_FLIGHT_ITEMS.get()),
                s -> setStringList(MaidSmartConfig.MISC_FREE_FLIGHT_ITEMS, s),"命中的物品让她获得飞行资格。两种写法：①物品 id（modid:item）②#命名空间:标签。扫描双手/护甲/背包/饰品栏/额外容器。（@组件 两路要数据组件，1.20.1 不支持）"));
        this.rows.add(new TextRow("仿创造飞行·资格效果表", String.join(",", (List<String>) MaidSmartConfig.MISC_FREE_FLIGHT_EFFECTS.get()),
                s -> setStringList(MaidSmartConfig.MISC_FREE_FLIGHT_EFFECTS, s), "命中的药水效果让她获得飞行资格（效果挂在实体上，这一路对女仆天然有效）"));
    }

    private void safetyRows() {
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
    }

    private void scheduleRows() {
        this.rows.add(new BoolRow("排班活动半径下限生效", MaidSmartConfig.MISC_SCHEDULE_RANGE_FLOOR.get(),
                v -> MaidSmartConfig.MISC_SCHEDULE_RANGE_FLOOR.set(v),"排班活动半径下限生效（默认开）：开 = 工作区域半径取 max(排班活动半径, TLM 设置)，本模组那个数只是下限；关 = 完全用 TLM 自己的设置，本模组一个数都不掺。"));
        this.rows.add(new BoolRow("排班表系统", MaidSmartConfig.MISC_SCHEDULE_ENABLED.get(),
                v -> MaidSmartConfig.MISC_SCHEDULE_ENABLED.set(v), "排班表系统（默认开）：按游戏内时间自动应用女仆的排班日程；关闭后排班调度停摆（每只女仆已保存的日程不丢，重新打开即恢复），女仆保持当前任务——单只女仆的排班开关在排班表物品里（快捷设置）"));
        // v1.1.0 实测六十一：战斗还原后排班宽限
        this.rows.add(new NumRow("战斗还原宽限（tick）", String.valueOf(MaidSmartConfig.MISC_SCHEDULE_RESTORE_GRACE.get()),
                s -> setInt(MaidSmartConfig.MISC_SCHEDULE_RESTORE_GRACE, s), "战斗还原后排班宽限（tick，默认 60=3 秒）：主动战斗结束还原原任务后，排班调度等待这么久才接管（期间她继续干战斗前的任务）——防威胁闪烁导致战斗/还原/排班反复拉扯；0 = 还原立即交排班"));
        // v1.1.0 实测一百三十三：排班切换三件套
        this.rows.add(new BoolRow("切换前可用性检测", MaidSmartConfig.MISC_SCHEDULE_AVAILABILITY_CHECK.get(),
                v -> MaidSmartConfig.MISC_SCHEDULE_AVAILABILITY_CHECK.set(v),"排班切任务时的完整可用性检测（默认关）：开启时额外检查目标任务附近有没有活干（矿/树/炉子/酿造台/作物）——没活不切、保持当前任务；关闭 = 只查任务自己的开关，任务跟着时间段真实切换。"));
        this.rows.add(new NumRow("反向切换窗口（tick）", String.valueOf(MaidSmartConfig.MISC_SCHEDULE_REVERSE_WINDOW_TICKS.get()),
                s -> setInt(MaidSmartConfig.MISC_SCHEDULE_REVERSE_WINDOW_TICKS, s), "两次任务切换间隔在此窗口内才可能被判为 A→B→A 反向横跳（默认 200=10 秒）；正常时段切换相隔约 2000 tick，不会被误判"));
        this.rows.add(new NumRow("反向切换阈值", String.valueOf(MaidSmartConfig.MISC_SCHEDULE_REVERSE_THRESHOLD.get()),
                s -> setInt(MaidSmartConfig.MISC_SCHEDULE_REVERSE_THRESHOLD, s), "窗口内累计反向次数达到该值即压制本次切换（默认 2）"));
        this.rows.add(new NumRow("反向切换冷却（tick）", String.valueOf(MaidSmartConfig.MISC_SCHEDULE_REVERSE_COOLDOWN_TICKS.get()),
                s -> setInt(MaidSmartConfig.MISC_SCHEDULE_REVERSE_COOLDOWN_TICKS, s), "压制反向切换后保持多久不再反向切（默认 200=10 秒）"));
        // v1.1.0 实测一百七十六（移植 TLM-Sincerely）：排班最短持有期 + 切段后大脑自愈
        this.rows.add(new NumRow("最短持有期（tick）", String.valueOf(MaidSmartConfig.MISC_SCHEDULE_MIN_HOLD_TICKS.get()),
                s -> setInt(MaidSmartConfig.MISC_SCHEDULE_MIN_HOLD_TICKS, s),"任何一次排班切换后此期间内不允许再切换（默认 60 = 3 秒，借鉴 TLM-Sincerely）——防段边界秒切与战斗还原连切；正常时段切换相隔约 2000 tick 不受影响；0 = 关闭。"));
        this.rows.add(new BoolRow("切段后大脑自愈", MaidSmartConfig.MISC_SCHEDULE_FORCE_BRAIN_REFRESH.get(),
                v -> MaidSmartConfig.MISC_SCHEDULE_FORCE_BRAIN_REFRESH.set(v),"段任务应用成功后 3 秒，若任务仍是段任务但脑内无工作记忆（站桩可能被 TLM 脑活动卡住），强制 refreshBrain 重建一次 AI（默认开）；关 = 完全信任 TLM。"));
        // v1.1.0 实测一百八十三：排班/home 模式活动半径下限
        // v1.2.2 实测五百六十二：默认回归 12（TLM 原版量级），语义改为"工作区域"圈
        this.rows.add(new NumRow("排班活动半径（格）", String.valueOf(MaidSmartConfig.SCHEDULE_ACTIVITY_RANGE.get()),
                s -> setInt(MaidSmartConfig.SCHEDULE_ACTIVITY_RANGE, s),"在家模式下工作区域的半径下限（默认 12）：任务选点/散步/巡逻都钳在工位锚点的这个圈内，出圈会被送回。想大范围干活就调大（8~512），或潜行+中键把工位标过去。取 max(本值, TLM 设置)。"));
    }

    private void logRows() {
        this.rows.add(new BoolRow("运行日志记录", MaidSmartConfig.MISC_LOG_ENABLED.get(),
                v -> MaidSmartConfig.MISC_LOG_ENABLED.set(v),"运行日志（默认开）：排班应用、参战与还原、险境脱离、跨维跟随、自保自愈等状态变化写入 logs/promaid.log（满 4MB 轮换）并镜像到 latest.log；关 = 完全静默。"));
        this.rows.add(new InfoRow("日志文件位置", "\u00a7a<游戏目录>/logs/promaid.log\u00a7r",
                "任意文本编辑器打开；每行格式 [真实时间] [分类] 内容（分类：排班/战斗/险境脱离/跨维/自保）。只记低频状态迁移，巡检空转不落盘"));
    }

    /**
     * v1.5.393 实测八百零四【饰品「大运」】——鞘翅飞行开路的全部参数。
     *
     * <p>需求原文："加入一个新饰品，可以由玩家和女仆进行佩戴。佩戴的效果是使用鞘翅飞行的过程中
     * 可以直接击毁自身周边 5×5 范围内除硬度大于等于黑曜石以及硬度&lt;0 的一切方块。配置选项里面
     * 也可以写。这个效果在女仆空袭的时候生效。"
     *
     * <p>面板上的顺序刻意与玩家心里的问题一致：**这是什么 → 谁能戴 → 清哪些 → 怎么清**。
     */
    private void daYunRows() {
        this.rows.add(new SectionRow("饰品「大运」（鞘翅飞行开路）", false));
        this.rows.add(new InfoRow("这是什么",
                "戴着它滑翔，自身 5×5×5 内的软方块直接被击毁","新增饰品「大运」。鞘翅飞行中以自身为中心、各方向 2 格 = 5×5×5 内击毁符合条件的方块；硬度 ≥ 黑曜石（50）与 < 0（基岩/屏障）的不清。合成：铁块×4、钻石块×2、红石块×2、矿车×1。"));
        this.rows.add(new SectionRow("佩戴方式", true));
        this.rows.add(new InfoRow("女仆怎么戴",
                "放进她自己的饰品栏；只在**空袭模式**生效","把「大运」放进女仆的饰品栏（TLM 饰品格）。她戴着它时，只有处于空袭模式（飞行作战 / 飞行远战）且真的在滑翔才会开路；扫帚模式、仿创造飞行、飞行跟随都不开路。"));
        this.rows.add(new InfoRow("玩家怎么戴",
                "放进 Curios 饰品栏；自己滑翔就生效","把「大运」放进玩家的 Curios 饰品栏（任意接受它的槽位）。自己鞘翅滑翔期间逐 tick 开路，判据与女仆侧完全同一套。需要装 Curios；没装时玩家侧静默不生效，物品本身与女仆侧不受影响。"));
        this.rows.add(new SectionRow("清哪些方块", true));
        this.rows.add(new BoolRow("「大运」总开关", MaidSmartConfig.DAYUN_ENABLE.get(),
                v -> MaidSmartConfig.DAYUN_ENABLE.set(v),
                "默认开。关掉 = 女仆侧与玩家侧一起停（物品还能合成、还能佩戴，只是不再破坏任何方块）。"
                        + "想只停一边，用下面那两条「玩家侧」/保留女仆侧的搭配去调"));
        this.rows.add(new BoolRow("玩家侧开关", MaidSmartConfig.DAYUN_PLAYER_ENABLE.get(),
                v -> MaidSmartConfig.DAYUN_PLAYER_ENABLE.set(v),
                "默认开。只关玩家自己那一侧：女仆戴着照旧开路，玩家戴着不再开路。"
                        + "典型用法：服务器不想要玩家一路飞一路铲，但允许女仆干活时开路"));
        this.rows.add(new NumRow("硬度上限", String.valueOf(MaidSmartConfig.DAYUN_MAX_HARDNESS.get()),
                this.setDoubleInRange(MaidSmartConfig.DAYUN_MAX_HARDNESS, "「大运」硬度上限", 0.0, 100.0),"硬度上限（默认 50 = 黑曜石，0~100）：只清【硬度小于此值】的方块（黑曜石正好 50 不动）；基岩等硬度 -1 的永远不清。51 = 连黑曜石一起铲；3 = 只清深板岩及更软。"));
        this.rows.add(new BoolRow("跳过容器", MaidSmartConfig.DAYUN_SKIP_CONTAINERS.get(),
                v -> MaidSmartConfig.DAYUN_SKIP_CONTAINERS.set(v),"默认开。带方块实体的方块（箱子/熔炉/漏斗/潜影盒/刷怪笼/各类机器…）整个不破坏。关掉 = 容器也照破，里面的东西全掉出来。开着的理由：把自家仓库炸成满地战利品比被墙挡住难受得多。"));
        this.rows.add(new BoolRow("破坏后掉落", MaidSmartConfig.DAYUN_DROP_ITEMS.get(),
                v -> MaidSmartConfig.DAYUN_DROP_ITEMS.set(v),
                "默认开。按原版战利品表正常掉落（与原版挖掘一致）。"
                        + "关掉 = 方块直接消失、不产生任何物品——纯清场，性能最好，但铲掉的资源也就没了"));
        this.rows.add(new BoolRow("破坏粒子", MaidSmartConfig.DAYUN_BREAK_PARTICLES.get(),
                v -> MaidSmartConfig.DAYUN_BREAK_PARTICLES.set(v),"默认开。破坏方块时喷该方块自己的碎屑并播破坏音（与原版手挖一致）。关掉 = 方块静静消失，没有粒子也没有声音——嫌碎屑太多或想省客户端开销时关掉（与「破坏后掉落」互不影响）。"));
        this.rows.add(new NumRow("单 tick 破坏上限", String.valueOf(MaidSmartConfig.DAYUN_MAX_PER_TICK.get()),
                this.setIntInRange(MaidSmartConfig.DAYUN_MAX_PER_TICK, "「大运」单 tick 破坏上限", 1, 512),"默认 64，范围 1~512。每 tick 最多清多少格，超出的下一 tick 继续（不会漏，只是慢一点）。滑翔快时调大能跟上；但每格都要走一遍战利品表，服务器负担随之上升——建议 64~128。"));
        this.rows.add(new NumRow("破坏范围（边长）", String.valueOf(MaidSmartConfig.DAYUN_RANGE.get()),
                this.setIntInRange(MaidSmartConfig.DAYUN_RANGE, "「大运」破坏范围", 1, 15),"默认 5，范围 1~15。以她/他所站那一格为中心清掉「这个数³」的立方体（5 = 各方向 2 格 = 125 格；3 = 27 格，最不容易误伤）。偶数自动进一位（填 4 按 5 生效）。",
                1.0, 15.0));
        // ---- v1.5.394 实测八百零五：穿墙锁敌 ----
        // 【BGM 曲目那一行**不在这里**】玩家点名要放在「系统与杂项」（见 utilityRows）。
        this.rows.add(new SectionRow("飞行索敌", true));
        this.rows.add(new BoolRow("穿墙锁敌", MaidSmartConfig.DAYUN_WALL_PHASE.get(),
                v -> MaidSmartConfig.DAYUN_WALL_PHASE.set(v),"默认关。开 = 女仆戴着「大运」飞行时可以隔着墙锁定敌人——前提是那堵墙她自己铲得掉（硬度在上限内、不是容器）。撞不开的照旧挡住她，不会「锁得到却过不去」。只在女仆侧生效。"));
        // ---- 实测八百五十六：佩戴者的保护（免疫鞘翅冲撞） ----
        this.rows.add(new SectionRow("佩戴者的保护", true));
        this.rows.add(new BoolRow("免疫冲撞伤害", MaidSmartConfig.DAYUN_NO_WALL_DAMAGE.get(),
                v -> MaidSmartConfig.DAYUN_NO_WALL_DAMAGE.set(v),
                "默认开。佩戴者（玩家 / 女仆）鞘翅滑翔时撞到方块不再掉血（原版「撞墙」伤害）。"
                        + "关掉 = 照原版结算，飞得越快撞得越疼。"));
    }

    /**
     * v1.2.2 实测六百一十六【压缩盒】——新道具/新方块那一档的参数。
     *
     * 可调的只有两条（格数固定 5、界面交互固定，不开放）：盒子放进女仆背包算不算
     * 她背包的延伸，以及每格能堆多少（默认 114514）。
     *
     * v1.2.4 实测六百四十五：原来还有两条——「禁入带附魔的物品」开关与「禁入清单」，
     * 都已删除（附魔物品一律禁入，判据写死在 {@code CompressionBoxFilter}）。
     * 这里保留一条**只读**的「禁入规则」说明行，让原位置仍然看得见口径。
     */
    private void compressionBoxRows() {
        this.rows.add(new BoolRow("女仆背包延伸", MaidSmartConfig.COMPRESSION_BOX_MAID_EXTENSION.get(),
                v -> MaidSmartConfig.COMPRESSION_BOX_MAID_EXTENSION.set(v),"压缩盒·女仆背包延伸（默认开）：开 = 盒子被当作背包尾部，找材料/拿食物/取建材都先看盒子里；关 = 只是普通收纳道具。硬边界：一次最多拿 64 个；「背包等级」截断那条路看不见盒子。"));
        this.rows.add(new NumRow("每格上限", String.valueOf(MaidSmartConfig.COMPRESSION_BOX_MAX_STACK.get()),
                s -> setInt(MaidSmartConfig.COMPRESSION_BOX_MAX_STACK, s),"压缩盒·每格上限（默认 114514，范围 64~1000000）：每格能堆多少。写小更直观；往下调不删已有的东西（已存的堆下次写入时被夹到新上限）。放进女仆背包时仍一次只拿 64。"));
        // v1.2.4 实测六百四十五：这一行原来是 BoolRow「禁入带附魔的物品」+ TextRow「禁入清单」，
        // 两个配置项都已删除（附魔物品**一律禁入**）。原位换成一条只读说明——玩家在这一页
        // 还能看到口径是什么、以及为什么不再给开关。
        this.rows.add(new InfoRow("禁入规则", "\u00a7c压缩盒本身 与 带附魔的物品 一律不能放入\u00a7r","「禁入带附魔的物品」与「禁入清单」两项已删除，口径写死、不再提供开关。原因：盒子只有 5 格而附魔物品不可堆叠（不同附魔组合各算一件）很快满；且曾报的「附魔物品存进去会消失」正出在这一类。"));
    }







    /**
     * 实测八百六十五：其余开关（自动补全）——把 toml 里手工面板没收录的配置项反射列出来。
     *
     * <p>已收录的（assets/promaid/panel_coverage.txt，由 gen_panel_coverage.py 从本类源码提取）
     * 跳过、一次性迁移标记 *_MIGRATED 跳过；以后新增配置项会自动出现在本页；标签取翻译键。
     */
    private void extraRows() {
        this.rows.add(new SectionRow("\u00a7e其余开关（自动补全）", false));
        this.rows.add(new InfoRow("这一页是什么",
                "\u00a77上面各页没收录的 toml 配置项，全在这里：每一项都能直接改。",
                "本页由面板自动生成——反射枚举 MaidSmartConfig 的全部配置字段，扣掉上面各页已收录的"
                        + "（assets/promaid/panel_coverage.txt）。以后新加的配置项会自动出现在这里；"
                        + "数值越界会在下次加载时被原版钳回边界。"));
        java.util.Set<String> covered = loadPanelCoverage();
        java.util.List<Object[]> items = new java.util.ArrayList<>();
        for (java.lang.reflect.Field f : MaidSmartConfig.class.getDeclaredFields()) {
            try {
                if (!java.lang.reflect.Modifier.isStatic(f.getModifiers())
                        || !java.lang.reflect.Modifier.isFinal(f.getModifiers())
                        || !ForgeConfigSpec.ConfigValue.class.isAssignableFrom(f.getType())) {
                    continue;
                }
                f.setAccessible(true);
                Object v = f.get(null);
                if (!(v instanceof ForgeConfigSpec.ConfigValue<?> cv)) {
                    continue;
                }
                String name = f.getName();
                if (covered.contains(name) || name.endsWith("_MIGRATED")) {
                    continue;
                }
                java.util.List<String> path = cv.getPath();
                if (path == null || path.isEmpty()) {
                    continue;
                }
                items.add(new Object[]{String.join(".", path), name, cv});
            } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("PromaidConfigScreen", ignored);
            }
        }
        java.util.Set<String> seenPath = new java.util.HashSet<>();
        String curSec = null;
        for (Object[] it : items) {
            String path = (String) it[0];
            if (!seenPath.add(path)) {
                continue; // 同路径去重（如 broom.enable 重复定义）
            }
            String sec = path.contains(".") ? path.substring(0, path.indexOf('.')) : "其他";
            if (!sec.equals(curSec)) {
                curSec = sec;
                this.rows.add(new SectionRow(sec, true));
            }
            if (!(it[2] instanceof ForgeConfigSpec.ConfigValue<?> cv2)) {
                continue;
            }
            String label = net.minecraft.network.chat.Component.m_237110_("config.promaid." + path).getString();
            String tip = "自动收录：" + path + "（config 文件里同名键）";
            try {
                if (cv2 instanceof ForgeConfigSpec.BooleanValue b) {
                    this.rows.add(new BoolRow(label, b.get(), b::set, tip));
                } else if (cv2 instanceof ForgeConfigSpec.IntValue n) {
                    this.rows.add(new NumRow(label, String.valueOf(n.get()), s -> setInt(n, s), tip));
                } else if (cv2 instanceof ForgeConfigSpec.DoubleValue d) {
                    this.rows.add(new NumRow(label, String.valueOf(d.get()), s -> setDouble(d, s), tip));
                } else {
                    Object cur = cv2.get();
                    if (cur instanceof java.util.List<?> lst) {
                        this.rows.add(new TextRow(label, joinList(lst), s -> setListRaw(cv2, s), tip));
                    } else if (cur instanceof String sv) {
                        this.rows.add(new TextRow(label, sv, s -> setStringRaw(cv2, s), tip));
                    } else {
                        this.rows.add(new InfoRow(label, String.valueOf(cur), tip));
                    }
                }
            } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("PromaidConfigScreen", ignored);
            }
        }
    }

    /** 读覆盖清单：面板已手工收录的字段名（资源缺失时返回空集——只是本页会多列已收录项）。 */
    private static java.util.Set<String> loadPanelCoverage() {
        java.util.Set<String> out = new java.util.HashSet<>();
        try (java.io.InputStream in = PromaidConfigScreen.class.getResourceAsStream("/assets/promaid/panel_coverage.txt")) {
            if (in == null) {
                return out;
            }
            java.io.BufferedReader r = new java.io.BufferedReader(
                    new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8));
            String line;
            while ((line = r.readLine()) != null) {
                String t = line.trim();
                if (!t.isEmpty() && !t.startsWith("#")) {
                    out.add(t);
                }
            }
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("PromaidConfigScreen", ignored);
        }
        return out;
    }

    private static String joinList(java.util.List<?> lst) {
        StringBuilder sb = new StringBuilder();
        for (Object o : lst) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(o);
        }
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private static boolean setStringRaw(ForgeConfigSpec.ConfigValue<?> cv, String s) {
        String v = s == null ? "" : s.trim();
        if (v.isEmpty()) {
            return false;
        }
        ((ForgeConfigSpec.ConfigValue<String>) cv).set(v);
        return true;
    }

    @SuppressWarnings("unchecked")
    private static boolean setListRaw(ForgeConfigSpec.ConfigValue<?> cv, String s) {
        try {
            java.util.List<String> out = new java.util.ArrayList<>();
            for (String part : s.split("[、,;\\s]+")) {
                String t = part.trim();
                if (!t.isEmpty()) {
                    out.add(t);
                }
            }
            if (out.isEmpty()) {
                return false;
            }
            ((ForgeConfigSpec.ConfigValue<java.util.List<? extends String>>) cv).set(out);
            return true;
        } catch (Throwable t) {
             com.maidsmart.diag.Diag.swallow("PromaidConfigScreen", t); return false;
        }
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

    /* ==================== 实测八百二十九【回退】 ====================
     *
     *
     * 于是给矿表/木材表这一页加【回退】：把某次改动之前的三张列表整张还原，最多可连续回退 3 步
     * （实测八百二十九b 由 1 步放宽到 3 步——单步容错率太低，手滑连删两下就回不去了）。
     * 为什么只挂在这一页——这页是全局唯一会"批量改一大堆"的地方（一键全部加入 / 搜索后批量
     * 点图标），也只有这页三张表彼此纠缠（障碍物额外名单 / 障碍物排除名单 / 目标矿物或木材
     * 价值表），一次误操作最容易把表刷乱。
     *
     * <p>口径：只有【真的改了配置】才落回退点（快照与当前逐字相同则丢弃），所以"点图标只是
     * 锁定方块"这种不改配置的动作不会占用回退位。回退历史是一个后进先出的栈，最多保留最近
     * {@link #UNDO_DEPTH} 步：连点回退就一步步往回退，退完为止；回退本身不再入栈（不产生
     * "回退的回退"）。回退不落盘、不跨界面存活——关掉界面重开即失效。
     */

    /**
     * 矿表/木材表页三张列表在【某次操作之前】的快照。
     *
     * <p>为什么三张都要拍：这页每个动作可能动到其中任意一张——点图标切换障碍物动的是
     * 额外名单（普通方块）或排除名单（内置自然软方块）；输入框赋优先级 / 点右上小叉动的是
     * 价值表；「一键全部加入」同时动额外名单和排除名单。只拍一张就不够"回退到上一次动作之前"。
     * 两张价值表（矿物 / 木材）各拍一份：模式在快照与回退之间可能被切过，两份都还原才干净。
     */
    private static final class MineSnap {
        final java.util.List<String> brk;
        final java.util.List<String> dis;
        final java.util.List<String> ore;
        final java.util.List<String> wood;

        MineSnap() {
            this.brk = new java.util.ArrayList<>(MaidSmartConfig.MINE_BREAKABLES.get());
            this.dis = new java.util.ArrayList<>(MaidSmartConfig.MINE_DISABLED_BREAKABLES.get());
            this.ore = new java.util.ArrayList<>(MaidSmartConfig.MINE_ORE_VALUES.get());
            this.wood = new java.util.ArrayList<>(MaidSmartConfig.WOOD_VALUES.get());
        }

        /** 快照是否与当前配置逐字相同——相同说明这次动作没真改配置（如只是锁定图标），不该占用回退位。 */
        boolean matchesCurrent() {
            return this.brk.equals(MaidSmartConfig.MINE_BREAKABLES.get())
                    && this.dis.equals(MaidSmartConfig.MINE_DISABLED_BREAKABLES.get())
                    && this.ore.equals(MaidSmartConfig.MINE_ORE_VALUES.get())
                    && this.wood.equals(MaidSmartConfig.WOOD_VALUES.get());
        }
    }

    /** 一次操作结束后调用：真的改了配置 → 压入回退栈；没改（如点图标只是锁定）→ 不占用回退位。 */
    private void commitUndo(MineSnap before) {
        if (before == null || before.matchesCurrent()) {
            return;
        }
        while (this.undoStack.size() >= UNDO_DEPTH) {
            this.undoStack.removeFirst(); // 满了丢最旧的一步——只保留最近 UNDO_DEPTH 步
        }
        this.undoStack.addLast(before);
    }

    /** 回退一步：弹出最近一次改动前的快照，把三张列表整张还原，刷新行为缓存、列表与回退键态、清锁定。 */
    private void performUndo() {
        if (this.undoStack.isEmpty()) {
            this.chatHint("\u00a77没有可回退的操作");
            return;
        }
        MineSnap s = this.undoStack.removeLast();
        MaidSmartConfig.MINE_BREAKABLES.set(new java.util.ArrayList<>(s.brk));
        MaidSmartConfig.MINE_DISABLED_BREAKABLES.set(new java.util.ArrayList<>(s.dis));
        MaidSmartConfig.MINE_ORE_VALUES.set(new java.util.ArrayList<>(s.ore));
        MaidSmartConfig.WOOD_VALUES.set(new java.util.ArrayList<>(s.wood));
        com.maidsmart.task.MaidMineBehavior.loadCustomOres();
        com.maidsmart.task.MaidWoodBehavior.loadCustomWoods();
        this.lockedOreId = null; // 还原后锁定的方块可能已不在表里——解锁，隐藏赋值输入框
        this.m_7856_(); // 重建：列表 / 按钮 / 回退键态全部刷新
        this.chatHint("\u00a7a已回退上一步操作");
    }

    /** 给本地玩家发一条聊天提示（客户端本地反馈，服务端不需要知道）。 */
    private void chatHint(String msg) {
        net.minecraft.client.player.LocalPlayer lp = net.minecraft.client.Minecraft.m_91087_().f_91074_;
        if (lp != null) {
            lp.m_213846_(Component.m_237113_(msg));
        }
    }

    /** 输入框添加（0 矿物 "id=value"；1 障碍物 "id"——规范化去 namespace 存 path） */
    private void addMinable() {
        if (this.minableInput == null) {
            return;
        }
        MineSnap before = new MineSnap(); // 实测八百二十九：回退点
        String text = this.minableInput.m_94155_().trim();
        if (this.mineTableMode == 0) {
            // v1.0.4：锁定状态下输入框留空点添加 = 用「创造面板默认价值」赋值（快捷路径）
            if (text.isEmpty()) {
                if (this.lockedOreId != null) {
                    this.setOreValue(this.lockedOreId, this.creativeDefaultValue());
                    this.lockedOreId = null;
                    this.m_7856_(); // 解锁：隐藏赋值输入框
                    this.commitUndo(before); // 实测八百二十九
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
                    this.commitUndo(before); // 实测八百二十九
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
        this.commitUndo(before); // 实测八百二十九：输入添加（矿物 id=value / 障碍物 id）落了回退点
    }

    /** 方块 id → path（去掉 namespace；"minecraft:oak_log" → "oak_log"） */
    private static String normPath(String id) {
        int idx = id.indexOf(':');
        return idx >= 0 ? id.substring(idx + 1) : id;
    }

    /** 列表删除（按当前名单） */
    private void removeMinable(String entry) {
        MineSnap before = new MineSnap(); // 实测八百二十九：回退点
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
        this.commitUndo(before); // 实测八百二十九
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
        MineSnap before = new MineSnap(); // 实测八百二十九：回退点
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
        this.commitUndo(before); // 实测八百二十九
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
        MineSnap before = new MineSnap(); // 实测八百二十九：回退点
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
        this.commitUndo(before); // 实测八百二十九：真的改了配置才落回退点（基岩/屏障提前 return 不到这里）
    }

    /**
     * 实测八百二十八【障碍物·一键全部加入】：把当前创造网格里【筛出来的全部方块】
     * 一次性设为可挖穿（障碍物）。
     *
     * <p>口径与 {@link #toggleCreative} 的障碍物分支【逐字一致】，只是从"点一个加一个"
     * 变成"这一批全加"：
     * <ul>
     *   <li>基岩/屏障仍不加入（与单点同款保护，防止误加后女仆傻挖）；</li>
     *   <li>内置自然软方块（isBuiltInBreakableBlock）→ 它本来就默认挖穿，一键加入的
     *       语义是"全部可挖穿"，所以要从【排除名单】MINE_DISABLED_BREAKABLES 里移出
     *       （取消之前的排除），而不是加进额外名单；</li>
     *   <li>其余方块 → 加进 MINE_BREAKABLES（去重）。</li>
     * </ul>
     * 搜索框过滤后的网格只含筛出来的方块，所以"先搜再一键加"天然成立。
     */
    private void addAllObstacles() {
        MineSnap before = new MineSnap(); // 实测八百二十九：回退点
        List<String> extra = new ArrayList<>(MaidSmartConfig.MINE_BREAKABLES.get());
        List<String> disabled = new ArrayList<>(MaidSmartConfig.MINE_DISABLED_BREAKABLES.get());
        int addedExtra = 0;
        int unblocked = 0;
        for (net.minecraft.world.item.ItemStack stack : this.creativeItems) {
            net.minecraft.resources.ResourceLocation key =
                    net.minecraftforge.registries.ForgeRegistries.ITEMS.getKey(stack.m_41720_());
            if (key == null) {
                continue;
            }
            String path = normPath(key.toString());
            if ("bedrock".equals(path) || "barrier".equals(path)) {
                continue;
            }
            if (com.maidsmart.task.MaidMineBehavior.isBuiltinBreakableBlock(path)) {
                if (disabled.remove(path)) {
                    unblocked++; // 之前被排除过 → 现在恢复挖穿
                }
            } else if (!extra.contains(path)) {
                extra.add(path);
                addedExtra++;
            }
        }
        MaidSmartConfig.MINE_BREAKABLES.set(extra);
        MaidSmartConfig.MINE_DISABLED_BREAKABLES.set(disabled);
        if (this.minableList != null) {
            this.minableList.rebuild();
        }
        if (this.f_96541_ != null && this.f_96541_.f_91074_ != null) {
            this.f_96541_.f_91074_.m_213846_(Component.m_237113_(
                    "\u00a7a已一键加入障碍物：新增 " + addedExtra + " 项、恢复挖穿 " + unblocked
                            + " 项（当前网格共 " + this.creativeItems.size() + " 个方块）"));
        }
        this.commitUndo(before); // 实测八百二十九：一键全加 = 最该能回退的一步
    }


    /* ==================== v1.2.5 实测六百五十二：烧制清单子页 ====================
     * v1.3.6 实测六百六十一【面板改造】
     *
     *
     * 于是：四张名单（只烧这些 / 禁止烧制 / 只用这些燃料 / 禁用燃料）收成**两个科目**
     * （烧制物品 / 燃料），每个科目一页、每个物品**一个勾**——勾上 = 可以，取消 = 不可以，
     * 交互与「投喂食物勾选」那张面板同款（绿 ✔ / 红 ✖）。
     *
     * <p>底层存储**没换**：仍然是原来那四个配置键，所以老存档里填好的名单一个字都不丢。
     * 折算规则（与 MaidCookBehavior.smeltListed / fuelListed 的判定**逐字一致**）：
     * <ul>
     *   <li>「允许清单」为空（默认、也是绝大多数人的状态）→ 这一页编辑的是**禁止清单**：
     *       勾 = 不在禁止清单里；取消 = 加进禁止清单；</li>
     *   <li>「允许清单」非空（旧版的「只烧这些」模式）→ 这一页编辑的是**允许清单**：
     *       只有清单里的勾着；勾 = 加进允许清单，取消 = 从允许清单移出（于是它就是"不可以"）。</li>
     * </ul>
     * 当前是哪一种，页顶那句话会写明白（黑名单 / 白名单）——不会出现「看到的勾与她真的会做的
     * 不是一回事」。
     */

    /** 两个科目（cookTableMode 0/1，与配置键一一对应：0 = 烧制，1 = 燃料） */
    private static final String[] COOK_LIST_NAMES = {"烧制物品", "燃料"};

    /** 该科目的「允许清单」（旧版「只烧这些」/「只用这些燃料」；返回可改副本） */
    private List<String> cookAllowFor(int mode) {
        return new ArrayList<>(mode == 0
                ? MaidSmartConfig.MISC_COOK_SMELT_ALLOW.get()
                : MaidSmartConfig.MISC_COOK_FUEL_ALLOW.get());
    }

    /** 该科目的「禁止清单」（返回可改副本） */
    private List<String> cookDenyFor(int mode) {
        return new ArrayList<>(mode == 0
                ? MaidSmartConfig.MISC_COOK_SMELT_DENY.get()
                : MaidSmartConfig.MISC_COOK_FUEL_DENY.get());
    }

    private void cookAllowSet(int mode, List<String> list) {
        if (mode == 0) {
            MaidSmartConfig.MISC_COOK_SMELT_ALLOW.set(list);
        } else {
            MaidSmartConfig.MISC_COOK_FUEL_ALLOW.set(list);
        }
    }

    private void cookDenySet(int mode, List<String> list) {
        if (mode == 0) {
            MaidSmartConfig.MISC_COOK_SMELT_DENY.set(list);
        } else {
            MaidSmartConfig.MISC_COOK_FUEL_DENY.set(list);
        }
    }

    /** 这个科目此刻用的是「允许清单」（= 旧版白名单模式；非空即生效） */
    private boolean cookWhitelistMode(int mode) {
        try {
            return !cookAllowFor(mode).isEmpty();
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("PromaidConfigScreen", ignored);
            return false;
        }
    }

    /** 勾选 = 可以（与 MaidCookBehavior 同口径：禁止优先；允许清单非空则只认允许清单） */
    private boolean isCookChecked(int mode, String id) {
        try {
            if (cookDenyFor(mode).contains(id)) {
                return false;
            }
            List<String> allow = cookAllowFor(mode);
            return allow.isEmpty() || allow.contains(id);
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("PromaidConfigScreen", ignored);
            return true;
        }
    }

    /** 底部名单显示哪张表：白名单模式显示允许清单，否则显示禁止清单（表里的都点一下就能改回来） */
    private List<String> cookListFor(int mode) {
        return cookWhitelistMode(mode) ? cookAllowFor(mode) : cookDenyFor(mode);
    }

    /** 页顶那句话：说清这一页此刻是黑名单还是白名单 */
    private String cookModeHint(int mode) {
        return cookWhitelistMode(mode)
                ? "\u00a7e白名单：只有勾上的可以\u00a7r\u00a77（允许清单非空 = 只认这份清单）"
                : "\u00a7e黑名单：勾上的可以、取消就不可以\u00a7r\u00a77（允许清单为空 = 一张禁止清单）";
    }

    /** 入口按钮上的摘要：白名单写「只认 N 项」，否则写「禁止 N 项」 */
    private String cookSummary(int mode) {
        try {
            return cookWhitelistMode(mode)
                    ? "只认 " + cookAllowFor(mode).size() + " 项"
                    : "禁止 " + cookDenyFor(mode).size() + " 项";
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("PromaidConfigScreen", ignored);
            return "?";
        }
    }

    /** 网格点一下 → 在「可以 / 不可以」之间切（照 toggleFoodChecked 写） */
    private void toggleCookChecked(int mode, String id) {
        if (id == null || id.isEmpty()) {
            return;
        }
        List<String> allow = cookAllowFor(mode);
        List<String> deny = cookDenyFor(mode);
        if (cookWhitelistMode(mode)) {
            // 白名单模式：勾 = 进允许清单，取消 = 从允许清单移出（于是它就是"不可以"了）
            if (allow.contains(id)) {
                allow.remove(id);
            } else {
                allow.add(id);
            }
            deny.remove(id);
            cookAllowSet(mode, allow);
            cookDenySet(mode, deny);
        } else {
            // 默认（黑名单）模式：取消 = 进禁止清单，勾上 = 从禁止清单移出
            if (deny.contains(id)) {
                deny.remove(id);
            } else {
                deny.add(id);
            }
            cookDenySet(mode, deny);
        }
        if (this.cookList != null) {
            this.cookList.rebuild();
        }
    }

    /** 手填注册名 → 按当前模式写进对应的那张表（支持省略 minecraft: 前缀） */
    private void addCookList() {
        if (this.cookInput == null) {
            return;
        }
        String text = this.cookInput.m_94155_().trim();
        if (text.isEmpty()) {
            return;
        }
        if (!text.contains(":")) {
            text = "minecraft:" + text;
        }
        net.minecraft.world.item.Item item = net.minecraftforge.registries.ForgeRegistries.ITEMS
                .getValue(net.minecraft.resources.ResourceLocation.parse(text));
        if (item == null) {
            return;
        }
        net.minecraft.resources.ResourceLocation key =
                net.minecraftforge.registries.ForgeRegistries.ITEMS.getKey(item);
        if (key == null) {
            return;
        }
        int mode = this.cookTableMode;
        String id = key.toString();
        // 手填的语义 = 「让它可以」——直接走点一下那条路（该写哪张表由模式决定，一处定义）
        if (!this.isCookChecked(mode, id)) {
            this.toggleCookChecked(mode, id);
        }
        this.cookInput.m_94144_("");
        if (this.cookList != null) {
            this.cookList.rebuild();
        }
    }

    /** 底部清单每行的按钮：黑名单模式 = 「改为可以」，白名单模式 = 「移出」 */
    private void removeCookList(String id) {
        int mode = this.cookTableMode;
        List<String> list = cookListFor(mode);
        list.remove(id);
        if (cookWhitelistMode(mode)) {
            cookAllowSet(mode, list);
        } else {
            cookDenySet(mode, list);
        }
        if (this.cookList != null) {
            this.cookList.rebuild();
        }
    }

    /**
     * 重建烧制清单的候选网格（中英文搜索；与 rebuildCreative 同款）。
     *
     * v1.3.2 实测六百五十六【"这张表乱填"】：旧版这里是**全物品**，玩家打开「只烧这些」
     * 看到的是一整注册表的物品——绝大多数根本没有炉子配方，而真正能烧的生肉/矿石/沙子在
     * 第 N 页。反馈原文「那些不可烧东西都进这张表了，原本可以烧制的食物和物品反而不在里面」。
     * 现在按**当前档位**过滤候选：
     * <ul>
     *   <li>「只烧这些」/「禁止烧制」（0/1）→ 只列当前世界**真的有炉子配方**的物品
     *       （{@link com.maidsmart.task.MaidCookBehavior#smeltableForPicker}）；</li>
     *   <li>「只用这些燃料」/「禁用燃料」（2/3）→ 只列原版**能当柴烧**的物品
     *       （{@link com.maidsmart.task.MaidCookBehavior#fuelForPicker}）。</li>
     * </ul>
     * 判据都在 {@code MaidCookBehavior} 里（一处实现），所以"面板上看得见的"与"她真的会烧的"
     * 不会是两套东西。客户端拿不到世界（理论上不会：面板是游戏内开的）时退回全物品，
     * 至少不把人挡在门外。
     */
    /** 当前筛选取条件是否放行这件候选（只作用于「烧制物品」档；燃料档不看它） */
    private boolean cookFilterKeeps(net.minecraft.world.item.ItemStack probe) {
        if (this.cookGridFilter == 1) {
            return com.maidsmart.task.MaidCookBehavior.isBuiltinFood(probe);
        }
        if (this.cookGridFilter == 2) {
            return com.maidsmart.task.MaidCookBehavior.blastableForPicker(
                    net.minecraft.client.Minecraft.m_91087_().f_91073_, probe);
        }
        return true;
    }

    /**
     * 一键把**内置食材清单**（当前世界真有炉子配方的那几件）填进「烧制物品」名单
     * —— 玩家问「我应该怎么配置怎么烧食物」：点这一下就是"只烧食物"。
     *
     * <p>【说清语义】名单留空 = 自动判定，而自动判定本来就**先食材后矿物**（见
     * {@code pickFurnaceKind} / {@code extractAnySmeltable}），所以这一步是「钉死成只烧食物」，
     * 不是「否则烧不了」。想回到自动就把名单清空。
     */
    private void fillBuiltinFood() {
        try {
            java.util.List<String> ids = com.maidsmart.task.MaidCookBehavior.builtinFoodIds(
                    net.minecraft.client.Minecraft.m_91087_().f_91073_);
            java.util.List<String> cur = new java.util.ArrayList<>(MaidSmartConfig.MISC_COOK_SMELT_ALLOW.get());
            int added = 0;
            for (String id : ids) {
                if (!cur.contains(id)) {
                    cur.add(id);
                    added++;
                }
            }
            MaidSmartConfig.MISC_COOK_SMELT_ALLOW.set(cur);
            this.cookDiagMode = -1; // 名单变了：让「烧制清单」诊断重记一次
            this.rebuildCookCreative();
            com.maidsmart.tool.PromaidLog.log("烧制清单",
                    "已把 " + added + " 件原版食物填进「烧制物品」名单（当前共 " + cur.size()
                            + " 条）——她现在只烧这些；想恢复自动判定就把这张名单清空");
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("PromaidConfigScreen", ignored);
        }
    }

    private void rebuildCookCreative() {
        this.creativeItems.clear();
        ensureCreativeCache();
        boolean fuelMode = this.cookTableMode >= 1;
        net.minecraft.world.level.Level level =
                net.minecraft.client.Minecraft.m_91087_().f_91073_;
        String q = this.creativeQuery == null ? "" : this.creativeQuery.trim().toLowerCase(java.util.Locale.ROOT);
        for (String[] entry : creativeCache) {
            String id = entry[0];
            String cn = entry[1];
            if (!q.isEmpty() && !(id.contains(q) || (cn != null && cn.contains(q)))) {
                continue;
            }
            net.minecraft.world.item.Item item = net.minecraftforge.registries.ForgeRegistries.ITEMS
                    .getValue(new net.minecraft.resources.ResourceLocation(id));
            if (item == null) {
                continue;
            }
            net.minecraft.world.item.ItemStack probe = new net.minecraft.world.item.ItemStack(item);
            if (level != null) {
                boolean keep = fuelMode
                        ? com.maidsmart.task.MaidCookBehavior.fuelForPicker(probe)
                        : com.maidsmart.task.MaidCookBehavior.smeltableForPicker(level, probe);
                if (!keep) {
                    continue;
                }
            }
            if (!fuelMode && !cookFilterKeeps(probe)) {
                continue;
            }
            this.creativeItems.add(probe);
        }
        this.creativePage = Math.min(this.creativePage, Math.max(0, this.creativePages() - 1));
        logCookGridDiag(level, fuelMode);
    }

    /** 本屏已经记过诊断的档位（-1 = 还没记；换档位/重开这一页都会再记一次） */
    private int cookDiagMode = -1;

    /**
     * v1.3.3 实测六百五十七【"烧制清单里为什么没有食物"】——把这一页的**事实**打出来。
     *
     * <p>背景：候选网格从 v1.3.2 起按"当前世界真有炉子配方"过滤。，而按代码
     * 食物**应该**在（生肉/土豆/海带在原版都有 smelting + smoking 配方）——所以要么是这套判据
     * 在你那个环境里拿不到配方（客户端没有配方表 / 整合包把食物配方改掉了），要么是过滤链里
     * 还有别的东西在挡。这两种原因**在游戏里长得一模一样**（都是"这一格里没有食物"），
     * 只能靠事实分辨——所以这里每次进某一档记一条，附一组代表物品的判定结果（真 = 会进网格）。
     * 实测时把这一行发过来即可定位。日志里搜「烧制清单」。
     */
    private void logCookGridDiag(net.minecraft.world.level.Level level, boolean fuelMode) {
        try {
            if (this.cookDiagMode == this.cookTableMode) {
                return; // 同一档不重复记（搜索框每敲一个字都会重建，不挡就是刷屏）
            }
            this.cookDiagMode = this.cookTableMode;
            String[] probes = {"minecraft:beef", "minecraft:porkchop", "minecraft:chicken",
                    "minecraft:potato", "minecraft:kelp", "minecraft:iron_ore",
                    "minecraft:sand", "minecraft:cobblestone", "minecraft:bread"};
            StringBuilder sb = new StringBuilder();
            for (String id : probes) {
                net.minecraft.world.item.Item it = net.minecraftforge.registries.ForgeRegistries.ITEMS
                        .getValue(new net.minecraft.resources.ResourceLocation(id));
                if (it == null) {
                    sb.append(' ').append(id).append("=无此物品");
                    continue;
                }
                net.minecraft.world.item.ItemStack probe = new net.minecraft.world.item.ItemStack(it);
                boolean keep = level != null && (fuelMode
                        ? com.maidsmart.task.MaidCookBehavior.fuelForPicker(probe)
                        : com.maidsmart.task.MaidCookBehavior.smeltableForPicker(level, probe));
                sb.append(' ').append(id).append('=').append(keep ? "真" : "假");
            }
            com.maidsmart.tool.PromaidLog.log("烧制清单",
                    "候选网格 " + this.creativeItems.size() + " 件 | 档位="
                            + COOK_LIST_NAMES[Math.min(Math.max(this.cookTableMode, 0),
                                    COOK_LIST_NAMES.length - 1)]
                            + " | 世界=" + (level == null ? "读不到（网格会退回全物品）" : "有")
                            + " | 代表物品=" + sb);
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("PromaidConfigScreen", ignored);
        }
    }

    /**
     * v1.2.5 实测六百五十二：烧制清单子页。
     *
     * <p>v1.3.6 实测六百六十一：顶部从**四个模式按钮**收成**两个科目按钮**（烧制物品 / 燃料，
     * 当前档黄色 ●），每个科目一页、网格上「一个勾」表达可以/不可以——
     * {@link #COOK_LIST_NAMES} 上方那段。其余（搜索 / 手填 id / 底部名单）与矿表 / 替代品 /
     * 喂食子页同款。
     */
    private void cookTableButtons(int w, int h, int cx) {
        int panelLeft = Math.max(8, cx - 280);
        int panelWidth = Math.min(560, w - 16);
        int left = panelLeft + 10;
        int gridRowsNow = this.gridRowsFor(h);
        int tgY = 24;
        for (int i = 0; i < COOK_LIST_NAMES.length; i++) {
            final int mi = i;
            this.m_142416_(Button.m_253074_(
                            Component.m_237113_((this.cookTableMode == mi ? "\u00a7e\u25cf " : "\u00a77") + COOK_LIST_NAMES[i]),
                            b -> {
                                this.cookTableMode = mi;
                                this.m_7856_();
                            })
                    .m_252987_(left + i * 100, tgY, 96, 18).m_253136_());
        }
        // v1.3.0(beta) 实测六百六十五【面板看不到食物】——只给「烧制物品」档加分类入口与一键填入
        //（燃料那一档的候选语义不同，不加）。三个筛选按钮共用一个 cookGridFilter，点一下重排列表。
        if (this.cookTableMode == 0) {
            String[] filters = {"全部", "只看食物", "只看矿物"};
            for (int k = 0; k < filters.length; k++) {
                final int fk = k;
                this.m_142416_(Button.m_253074_(
                                Component.m_237113_((this.cookGridFilter == fk ? "\u00a7e\u25cf " : "\u00a77")
                                        + filters[k]),
                                b -> {
                                    this.cookGridFilter = fk;
                                    this.creativePage = 0;
                                    this.m_7856_();
                                })
                        .m_252987_(left + 200 + k * 72, tgY, 68, 18).m_253136_());
            }
            this.m_142416_(Button.m_253074_(Component.m_237113_("填入原版食物"),
                            b -> this.fillBuiltinFood())
                    .m_252987_(left + 418, tgY, 118, 18).m_253136_());
        }
        this.creativeInput = new EditBox(this.f_96547_, left, 46, panelWidth - 20, 18,
                Component.m_237113_("搜索物品（中英文皆可）"));
        this.creativeInput.m_94199_(64);
        this.creativeInput.m_94144_(this.creativeQuery == null ? "" : this.creativeQuery);
        this.creativeInput.m_94151_(s -> {
            this.creativeQuery = s;
            this.rebuildCookCreative();
        });
        this.m_142416_(this.creativeInput);
        int gridTop = 66;
        int gridBottom = gridTop + gridRowsNow * GRID_CELL;
        this.gridRows = gridRowsNow;
        this.rebuildCookCreative();
        int pageY = gridBottom + 2;
        if (this.creativePage > 0) {
            this.m_142416_(Button.m_253074_(Component.m_237113_("\u00a77\u25c0"), b -> {
                this.creativePage--;
                this.m_7856_();
            }).m_252987_(left, pageY, 20, 16).m_253136_());
        }
        if (this.creativePage < this.creativePages() - 1) {
            this.m_142416_(Button.m_253074_(Component.m_237113_("\u00a77\u25b6"), b -> {
                this.creativePage++;
                this.m_7856_();
            }).m_252987_(left + 24, pageY, 20, 16).m_253136_());
        }
        int inputY = gridBottom + 24;
        this.cookInput = new EditBox(this.f_96547_, left, inputY, panelWidth - 116, 18,
                Component.m_237113_("填注册名加进当前名单"));
        this.cookInput.m_94199_(64);
        this.cookInput.m_257771_(Component.m_237113_("minecraft:coal"));
        this.m_142416_(this.cookInput);
        this.m_142416_(Button.m_253074_(Component.m_237113_("加进当前名单"), b -> this.addCookList())
                .m_252987_(left + panelWidth - 96, inputY, 80, 18).m_253136_());
        int listTop = inputY + 24;
        int listH = Math.max(24, Math.min(h - 78 - listTop - 4, h - listTop - 36));
        this.cookList = new CookList(this.f_96547_, left, listTop, panelWidth - 20, listH);
        this.cookList.m_93507_(left);
        this.m_142416_(this.cookList);
        this.m_142416_(Button.m_253074_(Component.m_237113_("\u2190 返回参数"), b -> {
            this.cookTable = false;
            this.m_7856_();
        }).m_252987_(12, h - 34, 100, 20).m_253136_());
        this.bottomButtons(w, h, cx);
    }

    /** 烧制清单子页的网格渲染（自绘，与「投喂食物勾选」同款：绿 ✔ = 可以 / 红 ✖ = 不可以） */
    private void renderCookGrid(GuiGraphics g, int mouseX, int mouseY, int w, int h, int cx) {
        int mode = this.cookTableMode;
        boolean fuelMode = mode >= 1;
        String subject = COOK_LIST_NAMES[fuelMode ? 1 : 0];
        // v1.3.2 实测六百五十六：标题里直接写明网格列的是什么——旧版不给任何说明，
        // 玩家看到"一整注册表"只会得出"这张表乱填"的结论（那是旧版真的乱列）。
        // v1.3.0(beta) 实测六百六十四：把第 7 条反馈（「烧制物品面板为什么只有矿物没有食物」）
        // 写进标题——食物与矿物**本来就是同一张清单**（吃的都在里面：beef / porkchop / potato…），
        // 这张表只列"当前世界真有炉子配方"的东西；清单留空 = 自动判定（**先食材后矿物**）。
        String src = fuelMode ? "可当燃料的物品"
                : "烧制物（食物与矿物都在这一格：搜 beef / potato / iron_ore；留空 = 自动，先食材后矿物）";
        String title = "\u00a7e" + subject + "\u00a77（只列" + src + "）\u00a7e——点图标切换「可以 / 不可以」";
        g.m_280653_(this.f_96547_, Component.m_237113_(title), cx, 10, 0xFFFFFF);
        int panelLeft = Math.max(8, cx - 280);
        int panelWidth = Math.min(560, w - 16);
        int left = panelLeft + 10;
        int gridTop = GRID_TOP;
        int gridRowsNow = this.gridRowsFor(h);
        int gridBottom = gridTop + gridRowsNow * GRID_CELL;
        g.m_280509_(panelLeft + 8, gridTop - 4, panelLeft + panelWidth - 8, gridBottom, 0x80101010);
        int perPage = this.gridCols * this.gridRows;
        int start = this.creativePage * perPage;
        int end = Math.min(this.creativeItems.size(), start + perPage);
        int hoverIdx = -1;
        for (int i = start; i < end; i++) {
            int col = (i - start) % this.gridCols;
            int row = (i - start) / this.gridCols;
            int x = left + col * GRID_CELL;
            int y = gridTop + row * GRID_CELL;
            net.minecraft.world.item.ItemStack stack = this.creativeItems.get(i);
            net.minecraft.resources.ResourceLocation key =
                    net.minecraftforge.registries.ForgeRegistries.ITEMS.getKey(stack.m_41720_());
            String id = key == null ? "" : key.toString();
            // v1.3.6：一个勾表达"可以/不可以"——与「投喂食物勾选」的绿勾红叉同款
            if (this.isCookChecked(mode, id)) {
                g.m_280509_(x - 1, y - 1, x + 17, y + 17, 0x8022CC22);
                g.m_280653_(this.f_96547_, Component.m_237113_("\u2714"), x + 12, y + 12, 0xFFFFFF);
            } else {
                g.m_280509_(x - 1, y - 1, x + 17, y + 17, 0x80CC2222);
                g.m_280614_(this.f_96547_, Component.m_237113_("\u00a7c\u2716"),
                        x + 12, y + 12, 0xFF5555, false);
            }
            g.m_280480_(stack, x, y);
            if (mouseX >= x && mouseX < x + GRID_CELL && mouseY >= y && mouseY < y + GRID_CELL) {
                hoverIdx = i;
                g.m_280509_(x - 2, y - 2, x + 18, y + 18, 0x80FFD700);
            }
        }
        int infoX = left + this.gridCols * GRID_CELL + 12;
        this.gridInfoX = left + this.gridCols * GRID_CELL + 12;
        int infoY = gridTop + 2;
        if (hoverIdx >= 0 && hoverIdx < this.creativeItems.size()) {
            net.minecraft.world.item.ItemStack stack = this.creativeItems.get(hoverIdx);
            net.minecraft.resources.ResourceLocation key =
                    net.minecraftforge.registries.ForgeRegistries.ITEMS.getKey(stack.m_41720_());
            String hover = key == null ? "?" : key.toString();
            String hc = com.maidsmart.build.BlueprintLib.cnName(hover);
            g.m_280614_(this.f_96547_,
                    Component.m_237113_("\u00a7f" + (hc.equals(hover) ? hover : hc)),
                    infoX, infoY, 0xFFFFFF, false);
            g.m_280614_(this.f_96547_, Component.m_237113_("\u00a77" + hover),
                    infoX, infoY + 10, 0xAAAAAA, false);
        } else {
            int pages = this.creativePages();
            if (pages > 1) {
                g.m_280614_(this.f_96547_,
                        Component.m_237113_("第 " + (this.creativePage + 1) + "/" + pages + " 页"),
                        infoX, infoY, 0x888888, false);
            }
        }
        String hint = "\u00a77✓ = " + subject + "可以、✖ = 不可以（点一下切换）；" + this.cookModeHint(mode);
        g.m_280653_(this.f_96547_, Component.m_237113_(hint),
                this.clampCenterX(hint, cx), this.f_96544_ - 50, 0x888888);
    }

    /** 烧制清单子页的网格点击 → 在当前那张名单里加入/移出 */
    private boolean clickCookGrid(double mouseX, double mouseY, int button) {
        if (button != 0) {
            return false;
        }
        int cx = this.f_96543_ / 2;
        int left = Math.max(8, cx - 280) + 10;
        int gridTop = GRID_TOP;
        int gridRowsNow = this.gridRowsFor(this.f_96544_);
        if (mouseX < left || mouseX >= left + this.gridCols * GRID_CELL
                || mouseY < gridTop || mouseY >= gridTop + gridRowsNow * GRID_CELL) {
            return false;
        }
        int perPage = this.gridCols * this.gridRows;
        int start = this.creativePage * perPage;
        int col = (int) ((mouseX - left) / GRID_CELL);
        int row = (int) ((mouseY - gridTop) / GRID_CELL);
        int idx = start + row * this.gridCols + col;
        if (idx < 0 || idx >= this.creativeItems.size()) {
            return false;
        }
        net.minecraft.resources.ResourceLocation key =
                net.minecraftforge.registries.ForgeRegistries.ITEMS.getKey(this.creativeItems.get(idx).m_41720_());
        if (key != null) {
            this.toggleCookChecked(this.cookTableMode, key.toString());
        }
        return true;
    }

    /**
     * v1.2.5 实测六百五十二：烧制清单列表（底部）——每行物品图标 + 中文名 + 「移出」按钮。
     * 四张名单共用这一个控件，按 cookTableMode 切表（同 AltList 按 altTableMode 切表）。
     */
    private class CookList extends ObjectSelectionList<CookList.CookEntry> {
        private final List<String> entries = new ArrayList<>();

        CookList(net.minecraft.client.gui.Font font, int x, int top, int width, int height) {
            super(Minecraft.m_91087_(), width, height, top, top + height, 22);
            this.m_93507_(x);
            this.m_93488_(false);
            this.m_93496_(false);
            this.f_93390_ = width; // 行宽 = 列表宽（同 FoodList / WaterList）
            this.rebuild();
        }

        void rebuild() {
            this.m_93516_();
            this.entries.clear();
            try {
                this.entries.addAll(PromaidConfigScreen.this.cookListFor(PromaidConfigScreen.this.cookTableMode));
            } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("PromaidConfigScreen", ignored);
            }
            for (String e : this.entries) {
                this.m_7085_(new CookEntry(e));
            }
        }

        @Override
        public int m_5759_() {
            return Math.max(this.f_93390_, 120);
        }

        /** 同 FoodList / WaterList：滚动条覆盖为低调样式 */
        @Override
        protected void m_238964_(GuiGraphics g, int mx, int my, float pt,
                                 int a, int b, int c, int d, int e) {
            super.m_238964_(g, mx, my, pt, a, b, c, d, e);
            int sx = this.f_93389_ + this.m_5759_() - 6;
            g.m_280509_(sx, this.f_93392_, sx + 6, this.f_93393_, 0xFF101010);
            int maxScroll = this.m_93518_();
            int area = this.f_93393_ - this.f_93392_;
            if (maxScroll > 0 && area > 8) {
                int sh = Math.max(32, area * area / maxScroll);
                sh = Math.min(sh, area - 8);
                int sy = (int) (this.m_93517_() * (double) (area - sh)) + this.f_93392_;
                g.m_280509_(sx, sy, sx + 4, sy + sh, 0x40FFFFFF);
            }
        }

        /** 单行：物品图标 + 中文名 + 「移出」按钮 */
        private class CookEntry extends ObjectSelectionList.Entry<CookList.CookEntry> {
            private final String id;
            private final Button removeButton;

            CookEntry(String id) {
                this.id = id;
                // v1.3.6：黑名单模式这一行是"不可以"的条目 → 按钮叫「改为可以」；
                // 白名单模式显示的是允许清单 → 按钮就是原来的「移出」
                boolean wl = PromaidConfigScreen.this.cookWhitelistMode(
                        PromaidConfigScreen.this.cookTableMode);
                this.removeButton = Button.m_253074_(
                                Component.m_237113_(wl ? "移出" : "改为可以"),
                                b -> PromaidConfigScreen.this.removeCookList(this.id))
                        .m_252987_(0, 0, 56, 18).m_253136_();
            }

            @Override
            public void m_6311_(GuiGraphics g, int index, int top, int left, int width, int height,
                                int mouseX, int mouseY, boolean hovered, float partialTick) {
                int x = left + 4;
                int y = top + 4;
                net.minecraft.world.item.Item item = net.minecraftforge.registries.ForgeRegistries.ITEMS
                        .getValue(net.minecraft.resources.ResourceLocation.parse(this.id));
                if (item != null) {
                    g.m_280480_(new net.minecraft.world.item.ItemStack(item), x, y - 2);
                    x += 20;
                }
                g.m_280614_(PromaidConfigScreen.this.f_96547_,
                        Component.m_237113_("\u00a7f" + com.maidsmart.build.BlueprintLib.cnName(this.id)),
                        x, y, 0xFFAAAAAA, false);
                this.removeButton.m_252865_(left + CookList.this.m_5759_() - 62);
                this.removeButton.m_253211_(top + 1);
                this.removeButton.m_88315_(g, mouseX, mouseY, partialTick);
            }

            @Override
            public boolean m_6375_(double mx, double my, int button) {
                if (button == 0 && this.removeButton.m_5953_(mx, my)) {
                    this.removeButton.m_6375_(mx, my, 0);
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


    /** 实测五百七十三：白名单列表（底部）——每行物品图标 + 中文名 + 「不喂了」按钮 */
    private class WaterList extends ObjectSelectionList<WaterList.WaterEntry> {
        private final List<String> entries = new ArrayList<>();

        WaterList(net.minecraft.client.gui.Font font, int x, int top, int width, int height) {
            super(Minecraft.m_91087_(), width, height, top, top + height, 22);
            this.m_93507_(x);
            this.m_93488_(false);
            this.m_93496_(false);
            this.f_93390_ = width; // 行宽 = 列表宽（同 FoodList）
            this.rebuild();
        }

        void rebuild() {
            this.m_93516_();
            this.entries.clear();
            try {
                this.entries.addAll(MaidSmartConfig.AID_DRINK_WHITELIST.get());
            } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("PromaidConfigScreen", ignored);
            }
            for (String e : this.entries) {
                this.m_7085_(new WaterEntry(e));
            }
        }

        @Override
        public int m_5759_() {
            return Math.max(this.f_93390_, 120);
        }

        /** 同 FoodList：滚动条覆盖为低调样式 */
        @Override
        protected void m_238964_(GuiGraphics g, int mx, int my, float pt,
                                 int a, int b, int c, int d, int e) {
            super.m_238964_(g, mx, my, pt, a, b, c, d, e);
            int sx = this.f_93389_ + this.m_5759_() - 6;
            g.m_280509_(sx, this.f_93392_, sx + 6, this.f_93393_, 0xFF101010);
            int maxScroll = this.m_93518_();
            int area = this.f_93393_ - this.f_93392_;
            if (maxScroll > 0 && area > 8) {
                int sh = Math.max(32, area * area / maxScroll);
                sh = Math.min(sh, area - 8);
                int sy = (int) (this.m_93517_() * (double) (area - sh)) + this.f_93392_;
                g.m_280509_(sx, sy, sx + 4, sy + sh, 0x40FFFFFF);
            }
        }

        /** 单行：物品图标 + 绿色勾 + 中文名 + 「不喂了」按钮（点了移出白名单） */
        private class WaterEntry extends ObjectSelectionList.Entry<WaterList.WaterEntry> {
            private final String id;
            private final Button removeButton;

            WaterEntry(String id) {
                this.id = id;
                this.removeButton = Button.m_253074_(Component.m_237113_("不喂了"),
                                b -> PromaidConfigScreen.this.removeWaterWhitelist(this.id))
                        .m_252987_(0, 0, 56, 18).m_253136_();
            }

            @Override
            public void m_6311_(GuiGraphics g, int index, int top, int left, int width, int height,
                                int mouseX, int mouseY, boolean hovered, float partialTick) {
                int x = left + 4;
                int y = top + 4;
                // 实测五百八十：配置项键可能带 #剩余次数 后缀 → 图标/名字用裸 id，后缀单独显示
                String base = com.maidsmart.action.ItemUses.baseId(this.id);
                String usesLabel = com.maidsmart.action.ItemUses.labelOfKey(this.id);
                net.minecraft.world.item.Item item = net.minecraftforge.registries.ForgeRegistries.ITEMS
                        .getValue(new net.minecraft.resources.ResourceLocation(base));
                if (item != null) {
                    g.m_280480_(new net.minecraft.world.item.ItemStack(item), x, y - 2);
                    x += 20;
                }
                g.m_280614_(PromaidConfigScreen.this.f_96547_,
                        Component.m_237113_("\u00a7a\u2714 \u00a7f"
                                + com.maidsmart.build.BlueprintLib.cnName(base)
                                + (usesLabel.isEmpty() ? "" : " \u00a7b" + usesLabel)),
                        x, y, 0xFFAAAAAA, false);
                this.removeButton.m_252865_(left + WaterList.this.m_5759_() - 62);
                this.removeButton.m_253211_(top + 1);
                this.removeButton.m_88315_(g, mouseX, mouseY, partialTick);
            }

            @Override
            public boolean m_6375_(double mx, double my, int button) {
                if (button == 0 && this.removeButton.m_5953_(mx, my)) {
                    this.removeButton.m_6375_(mx, my, 0);
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

    /** v1.2.0 实测五百一十九：黑名单列表（底部）——每行物品图标 + 中文名 + 「允许吃」按钮 */
    private class FoodList extends ObjectSelectionList<FoodList.FoodEntry> {
        private final List<String> entries = new ArrayList<>();

        FoodList(net.minecraft.client.gui.Font font, int x, int top, int width, int height) {
            super(Minecraft.m_91087_(), width, height, top, top + height, 22);
            this.m_93507_(x);
            this.m_93488_(false);
            this.m_93496_(false);
            this.f_93390_ = width; // 行宽 = 列表宽（同 MinableList）
            this.rebuild();
        }

        void rebuild() {
            this.m_93516_();
            this.entries.clear();
            this.entries.addAll(MaidSmartConfig.AID_FOOD_BLACKLIST.get());
            for (String e : this.entries) {
                this.m_7085_(new FoodEntry(e));
            }
        }

        @Override
        public int m_5759_() {
            return Math.max(this.f_93390_, 120);
        }

        /** 同 MinableList：滚动条覆盖为低调样式 */
        @Override
        protected void m_238964_(GuiGraphics g, int mx, int my, float pt,
                                 int a, int b, int c, int d, int e) {
            super.m_238964_(g, mx, my, pt, a, b, c, d, e);
            int sx = this.f_93389_ + this.m_5759_() - 6;
            g.m_280509_(sx, this.f_93392_, sx + 6, this.f_93393_, 0xFF101010);
            int maxScroll = this.m_93518_();
            int area = this.f_93393_ - this.f_93392_;
            if (maxScroll > 0 && area > 8) {
                int sh = Math.max(32, area * area / maxScroll);
                sh = Math.min(sh, area - 8);
                int sy = (int) (this.m_93517_() * (double) (area - sh)) + this.f_93392_;
                g.m_280509_(sx, sy, sx + 4, sy + sh, 0x40FFFFFF);
            }
        }

        /** 单行：物品图标 + 红色叉 + 中文名 + 「允许吃」按钮（点了移出黑名单） */
        private class FoodEntry extends ObjectSelectionList.Entry<FoodList.FoodEntry> {
            private final String id;
            private final Button allowButton;

            FoodEntry(String id) {
                this.id = id;
                this.allowButton = Button.m_253074_(Component.m_237113_("允许吃"),
                                b -> PromaidConfigScreen.this.removeFoodBlacklist(this.id))
                        .m_252987_(0, 0, 56, 18).m_253136_();
            }

            @Override
            public void m_6311_(GuiGraphics g, int index, int top, int left, int width, int height,
                                int mouseX, int mouseY, boolean hovered, float partialTick) {
                int x = left + 4;
                int y = top + 4;
                // 实测五百八十：配置项键可能带 #剩余次数 后缀 → 图标/名字用裸 id，后缀单独显示
                String base = com.maidsmart.action.ItemUses.baseId(this.id);
                String usesLabel = com.maidsmart.action.ItemUses.labelOfKey(this.id);
                net.minecraft.world.item.Item item = net.minecraftforge.registries.ForgeRegistries.ITEMS
                        .getValue(new net.minecraft.resources.ResourceLocation(base));
                if (item != null) {
                    g.m_280480_(new net.minecraft.world.item.ItemStack(item), x, y - 2);
                    x += 20;
                }
                g.m_280614_(PromaidConfigScreen.this.f_96547_,
                        Component.m_237113_("\u00a7c\u2716 \u00a7f"
                                + com.maidsmart.build.BlueprintLib.cnName(base)
                                + (usesLabel.isEmpty() ? "" : " \u00a7b" + usesLabel)),
                        x, y, 0xFFAAAAAA, false);
                this.allowButton.m_252865_(left + FoodList.this.m_5759_() - 62);
                this.allowButton.m_253211_(top + 1);
                this.allowButton.m_88315_(g, mouseX, mouseY, partialTick);
            }

            @Override
            public boolean m_6375_(double mx, double my, int button) {
                if (button == 0 && this.allowButton.m_5953_(mx, my)) {
                    this.allowButton.m_6375_(mx, my, 0);
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

    /** 实测八百一十七：支援品分类名单（底部）——每行物品图标 + 分类标记 + 「移出」按钮 */
    private class SupportList extends ObjectSelectionList<SupportList.SupportEntry> {
        private final List<String> entries = new ArrayList<>();

        SupportList(net.minecraft.client.gui.Font font, int x, int top, int width, int height) {
            super(Minecraft.m_91087_(), width, height, top, top + height, 22);
            this.m_93507_(x);
            this.m_93488_(false);
            this.m_93496_(false);
            this.f_93390_ = width;
            this.rebuild();
        }

        void rebuild() {
            this.m_93516_();
            this.entries.clear();
            try {
                this.entries.addAll(PromaidConfigScreen.this.supportEntriesNow());
            } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("PromaidConfigScreen", ignored);
            }
            for (String e : this.entries) {
                this.m_7085_(new SupportEntry(e));
            }
        }

        @Override
        public int m_5759_() {
            return Math.max(this.f_93390_, 120);
        }

        /** 同 FoodList：滚动条覆盖为低调样式 */
        @Override
        protected void m_238964_(GuiGraphics g, int mx, int my, float pt,
                                 int a, int b, int c, int d, int e) {
            super.m_238964_(g, mx, my, pt, a, b, c, d, e);
            int sx = this.f_93389_ + this.m_5759_() - 6;
            g.m_280509_(sx, this.f_93392_, sx + 6, this.f_93393_, 0xFF101010);
            int maxScroll = this.m_93518_();
            int area = this.f_93393_ - this.f_93392_;
            if (maxScroll > 0 && area > 8) {
                int sh = Math.max(32, area * area / maxScroll);
                sh = Math.min(sh, area - 8);
                int sy = (int) (this.m_93517_() * (double) (area - sh)) + this.f_93392_;
                g.m_280509_(sx, sy, sx + 4, sy + sh, 0x40FFFFFF);
            }
        }

        /** 单行：物品图标 + 分类标记 + 名字（+药水效果/剩余次数） + 「移出」按钮 */
        private class SupportEntry extends ObjectSelectionList.Entry<SupportList.SupportEntry> {
            private final String raw;   // 配置原条目（键=分类）
            private final String key;   // 左侧键
            private final Button removeButton;

            SupportEntry(String raw) {
                this.raw = raw;
                String lhs = com.maidsmart.combat.MaidSupportPolicy.entryLhs(raw);
                this.key = lhs == null ? raw : lhs;
                this.removeButton = Button.m_253074_(Component.m_237113_("移出"),
                                b -> PromaidConfigScreen.this.removeSupportEntry(this.key))
                        .m_252987_(0, 0, 56, 18).m_253136_();
            }

            @Override
            public void m_6311_(GuiGraphics g, int index, int top, int left, int width, int height,
                                int mouseX, int mouseY, boolean hovered, float partialTick) {
                int x = left + 4;
                int y = top + 4;
                net.minecraft.world.item.ItemStack stack =
                        PromaidConfigScreen.this.supportStackOf(this.key);
                if (stack != null) {
                    g.m_280480_(stack, x, y - 2);
                    x += 20;
                }
                com.maidsmart.combat.MaidSupportPolicy.Cat cat =
                        com.maidsmart.combat.MaidSupportPolicy.override(stack,
                                PromaidConfigScreen.this.supportMode == 1);
                if (cat == null) {
                    cat = com.maidsmart.combat.MaidSupportPolicy.Cat.DEFAULT;
                }
                String name = stack != null
                        ? com.maidsmart.build.BlueprintLib.cnName(
                                com.maidsmart.combat.MaidSupportPolicy.baseId(stack))
                        : com.maidsmart.action.ItemUses.baseId(this.key);
                String extra = stack != null
                        ? com.maidsmart.combat.MaidSupportPolicy.variantLabel(stack) : "";
                g.m_280614_(PromaidConfigScreen.this.f_96547_,
                        Component.m_237113_(com.maidsmart.combat.MaidSupportPolicy.catIcon(cat)
                                + " \u00a7f" + com.maidsmart.combat.MaidSupportPolicy.catLabel(cat)
                                + " \u00a77" + name
                                + (extra.isEmpty() ? "" : " \u00a7b" + extra)),
                        x, y, 0xFFAAAAAA, false);
                this.removeButton.m_252865_(left + SupportList.this.m_5759_() - 62);
                this.removeButton.m_253211_(top + 1);
                this.removeButton.m_88315_(g, mouseX, mouseY, partialTick);
            }

            @Override
            public boolean m_6375_(double mx, double my, int button) {
                if (button == 0 && this.removeButton.m_5953_(mx, my)) {
                    this.removeButton.m_6375_(mx, my, 0);
                    return true;
                }
                return false;
            }

            @Override
            public Component m_142172_() {
                return Component.m_237113_(this.raw);
            }
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
         * （反馈"保存并返回右侧一直有一条竖线"）——覆盖为低调样式：轨道融入面板，
         * 滑块半透明深灰，滚动功能保留。
         */
        @Override
        protected void m_238964_(GuiGraphics g, int mx, int my, float pt,
                                 int a, int b, int c, int d, int e) {
            super.m_238964_(g, mx, my, pt, a, b, c, d, e);
            int sx = this.f_93389_ + this.m_5759_() - 6;
            g.m_280509_(sx, this.f_93392_, sx + 6, this.f_93393_, 0xFF101010);
            int maxScroll = this.m_93518_();
            int area = this.f_93393_ - this.f_93392_;
            if (maxScroll > 0 && area > 8) {
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
        } catch (Exception ignored) { com.maidsmart.diag.Diag.swallow("PromaidConfigScreen", ignored);
            return false;
        }
    }

    private static boolean setDouble(ForgeConfigSpec.DoubleValue value, String s) {
        try {
            value.set(Double.parseDouble(s.trim()));
            return true;
        } catch (Exception ignored) { com.maidsmart.diag.Diag.swallow("PromaidConfigScreen", ignored);
            return false;
        }
    }

    /**
     * v1.3.0 实测六百六十六：**带范围的整数行 setter**——越界钳到边界，并记一条给玩家看的提示。
     *
     * 【为什么范围必须由面板自己判】ForgeConfigSpec.set() 完全不做范围校验：javap 实证它的
     * 方法体就是 {@code Preconditions.checkNotNull(...)} 两行 + {@code childConfig.set(path, value)}
     * + {@code cachedValue = value}——**范围只在"读配置文件"时由 correct() 钳**。
     * 于是旧版"散步间隔填 4000000"会：输入框白字（只校验了"是不是数字"）→ 写进文件 →
     * **下次加载被静默钳成上限（24000）**；玩家看到的现象就是"这个数填了没用、调不动"。
     * 现在：越界当场红字（{@link #inRangeOrUnbounded}）+ 保存时钳到边界 + 明确告诉玩家按多少生效。
     */
    private Function<String, Boolean> setIntInRange(ForgeConfigSpec.IntValue value, String label,
                                                    double min, double max) {
        return s -> {
            try {
                double v = Double.parseDouble(s.trim());
                double c = Math.max(min, Math.min(max, v));
                value.set((int) c);
                if (c != v) {
                    this.numNotices.add(label + "（" + fmtNum(min) + "~" + fmtNum(max)
                            + "）超出范围，已按 " + fmtNum(c) + " 生效");
                }
                return true;
            } catch (Exception ignored) { com.maidsmart.diag.Diag.swallow("PromaidConfigScreen", ignored);
                return false;
            }
        };
    }

    /** 带范围的小数行 setter（同上，见 {@link #setIntInRange} 的注释） */
    private Function<String, Boolean> setDoubleInRange(ForgeConfigSpec.DoubleValue value, String label,
                                                       double min, double max) {
        return s -> {
            try {
                double v = Double.parseDouble(s.trim());
                double c = Math.max(min, Math.min(max, v));
                value.set(c);
                if (c != v) {
                    this.numNotices.add(label + "（" + fmtNum(min) + "~" + fmtNum(max)
                            + "）超出范围，已按 " + fmtNum(c) + " 生效");
                }
                return true;
            } catch (Exception ignored) { com.maidsmart.diag.Diag.swallow("PromaidConfigScreen", ignored);
                return false;
            }
        };
    }

    /** 提示里的数字：整数不带小数点（0.05~2.5 这种照常带） */
    private static String fmtNum(double d) {
        if (!Double.isInfinite(d) && !Double.isNaN(d) && d == Math.floor(d)) {
            return String.valueOf((long) d);
        }
        return String.valueOf(d);
    }

    /** v1.3.0 实测六百六十六：行内范围校验（min/max 都为 null = 不判范围，与旧版一致） */
    private static boolean inRangeOrUnbounded(String s, Double min, Double max) {
        if (min == null && max == null) {
            return true;
        }
        try {
            double v = Double.parseDouble(s.trim());
            return (min == null || v >= min) && (max == null || v <= max);
        } catch (Exception ignored) { com.maidsmart.diag.Diag.swallow("PromaidConfigScreen", ignored);
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

    /** v1.2.0 实测五百七十二：字符串列表配置（用、或逗号分隔；空项丢弃） */
    private static boolean setStringList(ForgeConfigSpec.ConfigValue<List<? extends String>> value, String s) {
        try {
            java.util.List<String> out = new java.util.ArrayList<>();
            for (String part : s.split("[、,;\\s]+")) {
                String t = part.trim();
                if (!t.isEmpty()) {
                    out.add(t);
                }
            }
            if (out.isEmpty()) {
                return false; // 全空 = 不改（与 TextRow 的"跳过空文本"口径一致）
            }
            value.set(out);
            return true;
        } catch (Throwable t) {
             com.maidsmart.diag.Diag.swallow("PromaidConfigScreen", t); return false;
        }
    }


    /**
     * 实测五百七十五【窄窗口溢出】：物品网格右侧那几行悬停信息，按**最宽那行**把起点收进屏幕
     * （`min(期望 x, 屏宽 − 6 − 宽)`，再兜底到 8）。旧版固定画在 `left + 16×20 + 12`，
     * 小窗 / 大 GUI 缩放下右半截被直接切掉（反馈截图：喂食、喂水两个子页都中招）。
     *
     * <p>实测八百一十七【字压图标】再补一刀：上面的"往左收"收到头就会压回网格图标上
     * （玩家截图：绿色文字盖在物品图标与英文注册名上）。这里改成——先试把起点收进屏幕右缘；
     * 若收进来的位置会退到 {@code gridInfoX}（网格右缘）左边，则**就地把过长的行折行**，
     * 宁可多占几行也不压图标。列数自适应（computeGridCols）已让信息列有位置，这里是兜底。
     */
    private void drawHoverInfo(GuiGraphics g, int infoX, int infoY, String[] lines, int[] colors) {
        // 实测八百一十七续：右缘不再只看屏幕，而是**面板内容右缘**（gridPanelRight）——旧版长图例行按屏幕宽判定
        // “放得下”，就横着冲出面板压在别处 UI 上。同时加下界 hoverBottom（网格页 = 底部列表上沿）：画到界外就截断并补一个符号。
        int right = this.gridPanelRight > 0
                ? Math.min(this.gridPanelRight, this.f_96543_ - 6)
                : this.f_96543_ - 6;
        int widest = 0;
        for (String s : lines) {
            widest = Math.max(widest, this.f_96547_.m_92895_(s));
        }
        int floorX = this.gridInfoX > 0 ? Math.max(8, this.gridInfoX) : 8;
        int x = Math.max(8, Math.min(infoX, right - widest));
        if (x < floorX) {
            x = Math.max(8, Math.min(floorX, Math.max(8, right - 40)));
        }
        int avail = Math.max(40, right - x);
        int y = infoY;
        for (int i = 0; i < lines.length; i++) {
            if (lines[i] == null || lines[i].isEmpty()) {
                continue;
            }
            java.util.List<String> pieces = this.f_96547_.m_92895_(lines[i]) > avail
                    ? this.wrapToWidth(lines[i], avail)
                    : java.util.Collections.singletonList(lines[i]);
            for (String piece : pieces) {
                if (this.hoverBottom > 0 && y + 9 > this.hoverBottom) {
                    g.m_280614_(this.f_96547_, Component.m_237113_("\u00a77\u2026"), x, y, 0x888888, false);
                    return;
                }
                g.m_280614_(this.f_96547_, Component.m_237113_(piece), x, y, colors[i], false);
                y += 10;
            }
        }
    }

    /** 按像素宽把一行文本折成多行（供 drawHoverInfo 兜底用）；不折时返回单元素列表 */
    private List<String> wrapToWidth(String s, int maxWidth) {
        List<String> out = new ArrayList<>();
        if (s == null || s.isEmpty() || maxWidth <= 0) {
            out.add(s == null ? "" : s);
            return out;
        }
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (this.f_96547_.m_92895_(cur.toString() + ch) > maxWidth && cur.length() > 0) {
                out.add(cur.toString());
                cur.setLength(0);
            }
            cur.append(ch);
        }
        out.add(cur.toString());
        return out;
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

    /**
     * 实测八百五十七：把一行文本按屏幕可用宽度折好（放得下就原样一行）。
     * 供 {@link #drawCenteredLines} 用——旧版各子页的长提示行直接单行居中画，
     * 文本比屏幕宽时 {@link #clampCenterX} 的区间倒挂（minX &gt; maxX），圆心被夹到
     * {@code 8 + 半宽}：左端贴 8、右端照样冲出屏幕（玩家截图：支援分栏的标题与底部
     * 两行说明左右两端都被裁）。
     */
    private List<String> wrapFit(String text) {
        int avail = Math.max(40, this.f_96543_ - 16);
        return this.f_96547_.m_92895_(text) > avail
                ? this.wrapToWidth(text, avail)
                : java.util.Collections.singletonList(text);
    }

    /**
     * 实测八百五十七：逐行居中画（每行保证落在屏幕内）；{@code lastLineY} 是**最后一行**的 y，
     * 多出来的行往上长——用于底部提示区，折行后只会朝上占位、不会压到底部按钮。
     */
    private void drawCenteredLines(GuiGraphics g, List<String> lines, int lastLineY, int color) {
        int y = lastLineY - (lines.size() - 1) * 10;
        for (String line : lines) {
            if (line != null && !line.isEmpty()) {
                g.m_280614_(this.f_96547_, Component.m_237113_(line),
                        this.clampCenterX(line, this.f_96543_ / 2), y, color, false);
            }
            y += 10;
        }
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
    /**
     * 实测六百八十九：注释里的 markdown 强调标记 {@code **…**} 就地转成金色。
     * 面板不解析 markdown——旧版把星号原样画在界面上（玩家截图里能看到"**已经骑在扫帚上**"），
     * 全面板有 24~25 行注释带这个标记。成对出现 → 前一个换金色、后一个还原（{@code §r} 落回
     * drawComment 的浅蓝）；落单的星号照原样保留（否则一个 §e 会把后面整段染黄）。
     */
    private static String emphasis(String s) {
        int marks = 0;
        for (int i = 0; i + 1 < s.length(); i++) {
            if (s.charAt(i) == '*' && s.charAt(i + 1) == '*') {
                marks++;
                i++;
            }
        }
        if (marks < 2 || (marks % 2) != 0) {
            return s;
        }
        StringBuilder sb = new StringBuilder(s.length() + 8);
        boolean open = false;
        for (int i = 0; i < s.length(); i++) {
            if (i + 1 < s.length() && s.charAt(i) == '*' && s.charAt(i + 1) == '*') {
                sb.append(open ? "\u00a7r" : "\u00a7e");
                open = !open;
                i++;
            } else {
                sb.append(s.charAt(i));
            }
        }
        return sb.toString();
    }

    private List<String> wrapComment(String comment) {
        List<String> lines = new ArrayList<>();
        if (comment == null || comment.isEmpty()) {
            return lines;
        }
        comment = emphasis(comment); // 实测六百八十九：**…** → 金色（面板不解析 markdown）
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
     * 旧版灰 0x888888 与标签 0xAAAAAA 太接近，反馈"注释做了跟没做一样"。
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
        // 实测六百八十九：注释下界——分页把「一行」当原子，单行比一页还高时它会独占一页、
        // 并把文字一路画到翻页/保存按钮上甚至画出屏幕（玩家截图）。这里按分页同一个口径
        // （contentBottom = h-76）截住，真被截断就在末尾补一句指路，避免"文字凭空少一截"。
        int bottom = this.f_96544_ - 76;
        for (int i = 0; i < lines.size(); i++) {
            int ly = y + i * 10;
            if (ly + 9 > bottom) {
                String tail = "\u00a77……（余下见手册）\u00a7r";
                g.m_280614_(this.f_96547_, Component.m_237113_(tail),
                        this.clampLeftX(tail, x), ly, 0xFF7FB2E5, false);
                return;
            }
            g.m_280614_(this.f_96547_, Component.m_237113_(lines.get(i)),
                    this.clampLeftX(lines.get(i), x), ly, 0xFF7FB2E5, false);
        }
    }

    @Override
    public void m_88315_(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        this.m_280039_(g); // renderBackground
        // v1.1.0 实测三百一十七（反馈："UI 美化仅更改了手册第一主界面，其他子界面
        // 一点都没变"）：Promaid 模组详细配置（手册子界面）补上蓝金品牌渐变——与
        // 手册主界面同款（半透明色带叠加 = 渐变，m_280509_ 走 ARGB）
        int w = this.f_96543_;
        int h = this.f_96544_;
        int cx = w / 2;
        // 实测八百一十七：网格列数按窗口宽自适应（窄屏不再让悬停文字压到图标上）
        this.computeGridCols(Math.min(560, w - 16));
        int bandL = Math.max(4, cx - 300);
        int bandR = Math.min(w - 4, cx + 300);
        g.m_280509_(bandL, 4, bandR, h - 4, 0x55122A4E);   // 底层：深海军蓝
        g.m_280509_(bandL, 4, bandR, h - 4, 0x220F3A8C);   // 中层：宝蓝
        g.m_280509_(bandL, 4, bandR, h - 4, 0x1A1B4E8C);   // 高光：亮蓝
        g.m_280509_(bandL, 4, bandR, 14, 0xFF2C5F9E);      // 顶部饰条：靛蓝
        g.m_280509_(bandL, 4 + 10, bandR, 14 + 1, 0x80D4A017); // 金线
        g.m_280509_(Math.max(8, cx - 290), 8, Math.min(w - 8, cx + 290),
                h - 8, PANEL_BG);
        // v1.5.102d：矿表子页顶部已被当前名单标题占用（目标矿物/障碍物/珍稀矿物），
        // 主标题"Promaid 模组详细配置"隐去，否则两行文本重叠（v1.5.254：替代品子页同）
        // 实测八百一十七续：喂水/支援子页也自带标题，品牌标题必须一并隐去
        // （旧版漏了 waterTable / supportTable 两个标志，两行文字叠在 y=10）
        if (!this.mineTable && !this.woodTable && !this.altTable && !this.foodTable && !this.cookTable && !this.buildBlackTable && !this.bdRules
                && !this.waterTable && !this.supportTable) {
            g.m_280653_(this.f_96547_, Component.m_237113_("Promaid 模组详细配置"), cx, 10, 0xFFFFD700);
        }
        if (this.inHome) {
            // 【实测六百七十五】首页加一句"新功能在哪"的路标：
            //
            g.m_280653_(this.f_96547_,
                    Component.m_237113_("\u00a77选择功能大类 \u00a78· 最近新增（飞行跟随 / 扫帚模式 / 武装拴绳（二号位））都在「移动与行为」"),
                    cx, 36, 0x888888);
        } else if (this.inGroup) {
            // 实测四百二十三：大类页——标题 = 大类名，副标题提示选择小类
            g.m_280653_(this.f_96547_,
                    Component.m_237113_(this.group.title + "\u00a77 · 选择小类"),
                    cx, 32, 0xFFFFFF);
            g.m_280653_(this.f_96547_, Component.m_237113_(groupHint(this.group)),
                    cx, 36 + 10, 0x888888);
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
            int gridRowsNow = this.gridRowsFor(h);
            int gridBottom = gridTop + gridRowsNow * GRID_CELL;
            // 网格背景
            g.m_280509_(panelLeft + 8, gridTop - 4, panelLeft + panelWidth - 8, gridBottom, 0x80101010);
            int perPage = this.gridCols * this.gridRows;
            int start = this.creativePage * perPage;
            int end = Math.min(this.creativeItems.size(), start + perPage);
            int hoverIdx = -1;
            for (int i = start; i < end; i++) {
                int col = (i - start) % this.gridCols;
                int row = (i - start) / this.gridCols;
                int x = left + col * GRID_CELL;
                int y = gridTop + row * GRID_CELL;
                net.minecraft.world.item.ItemStack stack = this.creativeItems.get(i);
                net.minecraft.resources.ResourceLocation key =
                        net.minecraftforge.registries.ForgeRegistries.ITEMS.getKey(stack.m_41720_());
                String id = key == null ? "" : key.toString();
                if (this.isInList(id)) {
                    // 已加入当前名单 → 彩色框 + 角标 （矿物绿 / 障碍物青）
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
            int infoX = left + this.gridCols * GRID_CELL + 12;
            this.gridInfoX = left + this.gridCols * GRID_CELL + 12;
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
            // 两张名单各一句，说明方块图标上的对勾 是什么意思
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
        } else if (this.foodTable) {
            // v1.2.0 实测五百一十九：投喂食物勾选子页——网格里绿色勾=能吃 / 红叉=不能吃
            String title = "\u00a7e投喂食物——点图标切换「能不能吃」（\u2714 能吃 / \u2716 不能吃）"
                    + "\u00a77；能喂好几次的物品按「剩余次数」分行";
            g.m_280653_(this.f_96547_, Component.m_237113_(title),
                    this.clampCenterX(title, cx), 10, 0xFFFFFF);
            int panelLeft = Math.max(8, cx - 280);
            int panelWidth = Math.min(560, w - 16);
            int left = panelLeft + 10;
            int gridTop = GRID_TOP;
            int gridRowsNow = this.gridRowsFor(h);
            int gridBottom = gridTop + gridRowsNow * GRID_CELL;
            g.m_280509_(panelLeft + 8, gridTop - 4, panelLeft + panelWidth - 8, gridBottom, 0x80101010);
            int perPage = this.gridCols * this.gridRows;
            int start = this.creativePage * perPage;
            int end = Math.min(this.creativeItems.size(), start + perPage);
            int hoverIdx = -1;
            int totalFoods = 0;
            int feedableFoods = 0;
            ensureFoodCache();
            for (String[] e : foodCache) {
                totalFoods++;
                if (!this.isFoodChecked(e[0])) {
                    continue;
                }
                feedableFoods++;
            }
            for (int i = start; i < end; i++) {
                int col = (i - start) % this.gridCols;
                int row = (i - start) / this.gridCols;
                int x = left + col * GRID_CELL;
                int y = gridTop + row * GRID_CELL;
                net.minecraft.world.item.ItemStack stack = this.creativeItems.get(i);
                // 实测五百八十：这一格可能是"剩余 N 次"的某一档 → 键带上次数
                String id = com.maidsmart.action.ItemUses.key(stack);
                if (this.isFoodChecked(id)) {
                    g.m_280509_(x - 1, y - 1, x + 17, y + 17, 0x8022CC22);
                    g.m_280653_(this.f_96547_, Component.m_237113_("\u2714"), x + 12, y + 12, 0xFFFFFF);
                } else {
                    g.m_280509_(x - 1, y - 1, x + 17, y + 17, 0x80CC2222);
                    g.m_280614_(this.f_96547_, Component.m_237113_("\u00a7c\u2716"),
                            x + 12, y + 12, 0xFF5555, false);
                }
                g.m_280480_(stack, x, y);
                if (mouseX >= x && mouseX < x + GRID_CELL && mouseY >= y && mouseY < y + GRID_CELL) {
                    hoverIdx = i;
                }
            }
            int infoX = left + this.gridCols * GRID_CELL + 12;
            this.gridInfoX = left + this.gridCols * GRID_CELL + 12;
            int infoY = gridTop + 2;
            if (hoverIdx >= 0 && hoverIdx < this.creativeItems.size()) {
                net.minecraft.world.item.ItemStack stack = this.creativeItems.get(hoverIdx);
                net.minecraft.resources.ResourceLocation key =
                        net.minecraftforge.registries.ForgeRegistries.ITEMS.getKey(stack.m_41720_());
                String hover = com.maidsmart.action.ItemUses.key(stack);
                String hoverId = com.maidsmart.action.ItemUses.baseIdOf(stack);
                String hc = com.maidsmart.config.MaidItemNames.name(stack);
                if (hc.isEmpty()) {
                    hc = com.maidsmart.build.BlueprintLib.cnName(hoverId);
                }
                // 实测五百八十：耐久物品每一格是一个"剩余次数档位"，悬停写清楚
                String usesLabel = com.maidsmart.action.ItemUses.label(stack);
                this.drawHoverInfo(g, infoX, infoY, new String[]{
                        "\u00a7f" + (hc.equals(hoverId) ? hoverId : hc),
                        "\u00a77" + hover,
                        usesLabel.isEmpty() ? ""
                                : "\u00a7b" + usesLabel + "\u00a77（同物品的其它次数是独立条目）",
                        this.isFoodChecked(hover)
                                ? "\u00a7a当前：能吃（点击改成「这一档不能吃」）"
                                : "\u00a7c当前：不能吃（点击改回能吃）"},
                        new int[]{0xFFFFFF, 0xAAAAAA, 0x55FFFF, 0xFFFFFF});
            } else {
                int pages = this.creativePages();
                if (pages > 1) {
                    String pg = "第 " + (this.creativePage + 1) + "/" + pages + " 页";
                    g.m_280614_(this.f_96547_, Component.m_237113_(pg),
                            infoX, infoY, 0x888888, false);
                }
            }
            String chkHint = "\u00a77共 " + totalFoods + " 种食物，可喂 " + feedableFoods
                    + " 种（点图标切换；下方列表可恢复）";
            g.m_280653_(this.f_96547_, Component.m_237113_(chkHint),
                    this.clampCenterX(chkHint, cx), this.f_96544_ - 50, 0x888888);
        } else if (this.waterTable) {
            // 实测五百七十三：喂水白名单子页——网格里绿色勾=可以喂 / 红叉=不喂
            String wTitle = "\u00a7e喂水白名单——点图标切换「能不能喂」（\u2714 可以喂 / \u2716 不喂）"
                    + "\u00a77；能喝好几次的容器按「剩余次数」分行";
            g.m_280653_(this.f_96547_, Component.m_237113_(wTitle),
                    this.clampCenterX(wTitle, cx), 10, 0xFFFFFF);
            int wPanelLeft = Math.max(8, cx - 280);
            int wPanelWidth = Math.min(560, w - 16);
            int wLeft = wPanelLeft + 10;
            int wGridTop = GRID_TOP;
            int wGridRowsNow = this.gridRowsFor(h);
            int wGridBottom = wGridTop + wGridRowsNow * GRID_CELL;
            g.m_280509_(wPanelLeft + 8, wGridTop - 4, wPanelLeft + wPanelWidth - 8, wGridBottom, 0x80101010);
            int wPerPage = this.gridCols * this.gridRows;
            int wStart = this.creativePage * wPerPage;
            int wEnd = Math.min(this.creativeItems.size(), wStart + wPerPage);
            int wHoverIdx = -1;
            ensureWaterCache();
            int wTotal = waterCache.size();
            for (int i = wStart; i < wEnd; i++) {
                int col = (i - wStart) % this.gridCols;
                int row = (i - wStart) / this.gridCols;
                int x = wLeft + col * GRID_CELL;
                int y = wGridTop + row * GRID_CELL;
                net.minecraft.world.item.ItemStack stack = this.creativeItems.get(i);
                // 实测五百八十：这一格可能是"剩余 N 次"的某一档 → 键带上次数
                String id = com.maidsmart.action.ItemUses.key(stack);
                if (this.isWaterChecked(id)) {
                    g.m_280509_(x - 1, y - 1, x + 17, y + 17, 0x8022CC22);
                    g.m_280653_(this.f_96547_, Component.m_237113_("\u2714"), x + 12, y + 12, 0xFFFFFF);
                } else {
                    g.m_280509_(x - 1, y - 1, x + 17, y + 17, 0x80CC2222);
                    g.m_280614_(this.f_96547_, Component.m_237113_("\u00a7c\u2716"),
                            x + 12, y + 12, 0xFF5555, false);
                }
                g.m_280480_(stack, x, y);
                if (mouseX >= x && mouseX < x + GRID_CELL && mouseY >= y && mouseY < y + GRID_CELL) {
                    wHoverIdx = i;
                }
            }
            int wInfoX = wLeft + this.gridCols * GRID_CELL + 12;
            this.gridInfoX = wLeft + this.gridCols * GRID_CELL + 12;
            int wInfoY = wGridTop + 2;
            if (wHoverIdx >= 0 && wHoverIdx < this.creativeItems.size()) {
                net.minecraft.world.item.ItemStack stack = this.creativeItems.get(wHoverIdx);
                net.minecraft.resources.ResourceLocation key =
                        net.minecraftforge.registries.ForgeRegistries.ITEMS.getKey(stack.m_41720_());
                String hover = com.maidsmart.action.ItemUses.key(stack);
                String hoverId = com.maidsmart.action.ItemUses.baseIdOf(stack);
                String hc = com.maidsmart.config.MaidItemNames.name(stack);
                if (hc.isEmpty()) {
                    hc = com.maidsmart.build.BlueprintLib.cnName(hoverId);
                }
                                // 实测五百七十六：装水容器额外标一行水质（反馈："把这些水细分"）
                int wPur = com.maidsmart.action.ThirstCompat.waterPurity(stack);
                String wPurName = com.maidsmart.action.ThirstCompat.purityLabel(wPur);
                boolean wShowPur = wPurName != null;
                // 实测五百八十：耐久容器（水壶）再标一行"剩余 N/M 次"
                String wUses = com.maidsmart.action.ItemUses.label(stack);
                boolean wShowUses = !wUses.isEmpty();
                int wCount = 2 + (wShowUses ? 1 : 0) + (wShowPur ? 1 : 0) + 1;
                String[] wLines = new String[wCount];
                int[] wColors = new int[wCount];
                wLines[0] = "\u00a7f" + (hc.equals(hoverId) ? hoverId : hc);
                wColors[0] = 0xFFFFFF;
                wLines[1] = "\u00a77" + hover;
                wColors[1] = 0xAAAAAA;
                int wIdx = 2;
                if (wShowUses) {
                    wLines[wIdx] = "\u00a7b" + wUses + "\u00a77（同物品的其它次数是独立条目）";
                    wColors[wIdx] = 0x55FFFF;
                    wIdx++;
                }
                if (wShowPur) {
                    wLines[wIdx] = "\u00a77水质：" + wPurName;
                    wColors[wIdx] = com.maidsmart.action.ThirstCompat.purityColor(wPur);
                    wIdx++;
                }
                wLines[wIdx] = this.isWaterChecked(hover)
                        ? "\u00a7a当前：可以喂（点击改成不喂）"
                        : "\u00a7c当前：不喂（点击加进白名单）";
                wColors[wIdx] = 0xFFFFFF;
                this.drawHoverInfo(g, wInfoX, wInfoY, wLines, wColors);
            } else {
                int pages = this.creativePages();
                if (pages > 1) {
                    String pg = "第 " + (this.creativePage + 1) + "/" + pages + " 页";
                    g.m_280614_(this.f_96547_, Component.m_237113_(pg),
                            wInfoX, wInfoY, 0x888888, false);
                }
            }
            String wHint = "\u00a77候选 " + wTotal + " 项，白名单 " + this.countDrinkables()
                    + " 项；留空 = 不喂水（potion 只认纯净水）；带「#次数」的条目只认那一档，"
                    + "裸 id 条目 = 该物品任意次数";
            // 实测八百五十七：折行画（同支援分栏），窄屏不越界
            this.drawCenteredLines(g, this.wrapFit(wHint), this.f_96544_ - 44, 0x888888);
        } else if (this.supportTable) {
            // 实测八百一十七：支援物品分类子页——格子里画分类标记（◆对策 ▲增益 ♥回复 ✖禁用 ·自动）
            String sTitle = "\u00a7e" + (this.supportMode == 1 ? "亡灵支援" : "通用支援")
                    + "——点图标循环切换「自动 \u00b7 \u25c6对策 \u00b7 \u25b2增益 \u00b7 \u2665回复 \u00b7 \u2716禁用」";
            // 实测八百五十七：标题折行画（窄屏不越界）；"带效果的食物/药水按效果自动推断"
            // 那句并进下面的汇总行（它本来就说了"留空 = 全部按效果自动推断"）
            this.drawCenteredLines(g, this.wrapFit(sTitle), 10, 0xFFFFFF);
            int sPanelLeft = Math.max(8, cx - 280);
            int sPanelWidth = Math.min(560, w - 16);
            int sLeft = sPanelLeft + 10;
            int sGridTop = GRID_TOP;
            int sGridRowsNow = this.gridRowsFor(h);
            int sGridBottom = sGridTop + sGridRowsNow * GRID_CELL;
            g.m_280509_(sPanelLeft + 8, sGridTop - 4, sPanelLeft + sPanelWidth - 8, sGridBottom, 0x80101010);
            int sPer = this.gridCols * this.gridRows;
            int sStart = this.creativePage * sPer;
            int sEnd = Math.min(this.creativeItems.size(), sStart + sPer);
            int sHover = -1;
            for (int i = sStart; i < sEnd; i++) {
                int col = (i - sStart) % this.gridCols;
                int row = (i - sStart) / this.gridCols;
                int x = sLeft + col * GRID_CELL;
                int y = sGridTop + row * GRID_CELL;
                net.minecraft.world.item.ItemStack stack = this.creativeItems.get(i);
                String key = com.maidsmart.combat.MaidSupportPolicy.key(stack);
                com.maidsmart.combat.MaidSupportPolicy.Cat cat =
                        com.maidsmart.combat.MaidSupportPolicy.category(stack, this.supportMode == 1);
                int bg;
                switch (cat) {
                    case COUNTER: bg = 0x8033BBFF; break; // 对策：蓝
                    case BUFF:    bg = 0x80FFCC22; break; // 增益：金
                    case HEAL:    bg = 0x8022CC22; break; // 回复：绿
                    case OFF:     bg = 0x80CC2222; break; // 禁用：红
                    default:      bg = 0x80606060; break; // 自动：灰
                }
                g.m_280509_(x - 1, y - 1, x + 17, y + 17, bg);
                g.m_280653_(this.f_96547_,
                        Component.m_237113_(com.maidsmart.combat.MaidSupportPolicy.catIcon(cat)),
                        x + 12, y + 12, 0xFFFFFF);
                g.m_280480_(stack, x, y);
                if (mouseX >= x && mouseX < x + GRID_CELL && mouseY >= y && mouseY < y + GRID_CELL) {
                    sHover = i;
                }
            }
            int sInfoX = sLeft + this.gridCols * GRID_CELL + 12;
            this.gridInfoX = sLeft + this.gridCols * GRID_CELL + 12;
            int sInfoY = sGridTop + 2;
            if (sHover >= 0 && sHover < this.creativeItems.size()) {
                net.minecraft.world.item.ItemStack stack = this.creativeItems.get(sHover);
                com.maidsmart.combat.MaidSupportPolicy.Cat cat =
                        com.maidsmart.combat.MaidSupportPolicy.category(stack, this.supportMode == 1);
                // 实测八百一十七续：第一行给**本地化显示名**（中文客户端=中文名）——旧版用 cnName() 手写词表，
                // 模组食物译不出、整行只剩英文注册名；第二行 hk 仍是注册名/键 = 玩家要的「英文字段」。
                String hc = com.maidsmart.config.MaidItemNames.name(stack);
                if (hc.isEmpty()) {
                    hc = com.maidsmart.build.BlueprintLib.cnName(
                            com.maidsmart.combat.MaidSupportPolicy.baseId(stack));
                }
                String hk = com.maidsmart.combat.MaidSupportPolicy.key(stack);
                String extra = com.maidsmart.combat.MaidSupportPolicy.variantLabel(stack);
                String autoTxt = com.maidsmart.combat.MaidSupportPolicy.catLabel(
                        com.maidsmart.combat.MaidSupportPolicy.derive(stack, this.supportMode == 1));
                String[] lines = {
                        "\u00a7f" + hc,
                        "\u00a77" + hk,
                        extra.isEmpty() ? "" : "\u00a7b" + extra,
                        "\u00a7f当前：" + com.maidsmart.combat.MaidSupportPolicy.catLabel(cat)
                                + "\u00a77（自动推断=" + autoTxt + "；点击切换）",
                        this.supportMode == 1 && com.maidsmart.combat.MaidSupportPolicy
                                .harmfulToUndead(stack)
                                ? "\u00a7c注意：治疗药水对亡灵是扣血（伤害药水才是回血）" : "",
                        this.supportMode == 1 && com.maidsmart.combat.MaidSupportPolicy
                                .uselessToUndead(stack)
                                ? "\u00a7c注意：亡灵免疫再生——这瓶对亡灵不生效" : ""
                        // 实测八百一十七续：四类的说明从悬停框挪到底部**固定提示行**（玩家随时可读）
                };
                int[] colors = {0xFFFFFF, 0xAAAAAA, 0x55FFFF, 0xFFFFFF, 0xFF5555, 0xFF5555};
                this.drawHoverInfo(g, sInfoX, sInfoY, lines, colors);
            } else {
                int pages = this.creativePages();
                if (pages > 1) {
                    String pg = "第 " + (this.creativePage + 1) + "/" + pages + " 页";
                    g.m_280614_(this.f_96547_, Component.m_237113_(pg),
                            sInfoX, sInfoY, 0x888888, false);
                }
            }
            String sHint = "\u00a77" + (this.supportMode == 1 ? "亡灵" : "通用")
                    + "支援表已分类 " + this.supportEntriesNow().size()
                    + " 条；留空 = 全部按效果自动推断（与旧版一致）";
            // 实测八百一十七续：四类说明放在这里（固定位置，不占悬停框、不会压图标）
            // 实测八百五十七：图例/汇总改折行画——末行分别钉在 h-58 / h-44，多出来的行往上长，
            // 窄屏（GUI 缩放小 / 窗口模式）下不再左右越界，也不会压到底部按钮
            String sLegend = "\u00a77对策=只在对应处境/debuff 出现时用 · 增益=战局开始就能用 · 回复=血低于「治疗触发血量」才用 · 禁用=完全不用";
            this.drawCenteredLines(g, this.wrapFit(sLegend), this.f_96544_ - 58, 0xAAAAAA);
            this.drawCenteredLines(g, this.wrapFit(sHint), this.f_96544_ - 44, 0x888888);
        } else if (this.altTable) {
            // v1.5.254：替代品名单子页（建造板块）——交互与矿表同款
            String[] modeNames = {"半格高（台阶类）", "一格高（整方块）", "竖两格（门/高植物等）",
                    "横两格（床）", "无碰撞（花/火把/地毯等）"};
            String title = "\u00a7e替代品——" + modeNames[Math.min(this.altTableMode, 4)]
                    + "——点击方块图标加入（再点取消）";
            this.drawCenteredLines(g, this.wrapFit(title), 10, 0xFFFFFF);
            int panelLeft = Math.max(8, cx - 280);
            int panelWidth = Math.min(560, w - 16);
            int left = panelLeft + 10;
            int gridTop = GRID_TOP;
            int gridRowsNow = this.gridRowsFor(h);
            int gridBottom = gridTop + gridRowsNow * GRID_CELL;
            g.m_280509_(panelLeft + 8, gridTop - 4, panelLeft + panelWidth - 8, gridBottom, 0x80101010);
            int perPage = this.gridCols * this.gridRows;
            int start = this.creativePage * perPage;
            int end = Math.min(this.creativeItems.size(), start + perPage);
            int hoverIdx = -1;
            for (int i = start; i < end; i++) {
                int col = (i - start) % this.gridCols;
                int row = (i - start) / this.gridCols;
                int x = left + col * GRID_CELL;
                int y = gridTop + row * GRID_CELL;
                net.minecraft.world.item.ItemStack stack = this.creativeItems.get(i);
                net.minecraft.resources.ResourceLocation key =
                        net.minecraftforge.registries.ForgeRegistries.ITEMS.getKey(stack.m_41720_());
                String id = key == null ? "" : key.toString();
                if (this.isInAlt(id)) {
                    // 已加入当前替代品表 → 蓝色框 + 角标
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
            int infoX = left + this.gridCols * GRID_CELL + 12;
            this.gridInfoX = left + this.gridCols * GRID_CELL + 12;
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
            this.drawCenteredLines(g, this.wrapFit(chkHint), this.f_96544_ - 44, 0x888888);
        } else if (this.buildBlackTable) {
            // v1.3.0(beta) 实测六百八十：搭方块禁用名单子页（搭路板块）
            String title = "\u00a7e搭方块禁用名单——点击方块图标切换（\u00a7c\u2716 禁止\u00a7e / "
                    + "\u00a7a\u2714 允许\u00a7e）";
            this.drawCenteredLines(g, this.wrapFit(title), 10, 0xFFFFFF);
            int panelLeft = Math.max(8, cx - 280);
            int panelWidth = Math.min(560, w - 16);
            int left = panelLeft + 10;
            int gridTop = GRID_TOP;
            int gridRowsNow = this.gridRowsFor(h);
            int gridBottom = gridTop + gridRowsNow * GRID_CELL;
            g.m_280509_(panelLeft + 8, gridTop - 4, panelLeft + panelWidth - 8, gridBottom, 0x80101010);
            int perPage = this.gridCols * this.gridRows;
            int start = this.creativePage * perPage;
            int end = Math.min(this.creativeItems.size(), start + perPage);
            int hoverIdx = -1;
            for (int i = start; i < end; i++) {
                int col = (i - start) % this.gridCols;
                int row = (i - start) / this.gridCols;
                int x = left + col * GRID_CELL;
                int y = gridTop + row * GRID_CELL;
                net.minecraft.world.item.ItemStack stack = this.creativeItems.get(i);
                if (this.isBuildBlockForbidden(stack)) {
                    g.m_280509_(x - 1, y - 1, x + 17, y + 17, 0x80CC2222); // 红框 = 禁止
                    g.m_280653_(this.f_96547_, Component.m_237113_("\u2716"), x + 12, y + 12, 0xFFFF8080);
                } else {
                    g.m_280509_(x - 1, y - 1, x + 17, y + 17, 0x8022CC44); // 绿框 = 允许
                    g.m_280653_(this.f_96547_, Component.m_237113_("\u2714"), x + 12, y + 12, 0xFF80FF80);
                }
                g.m_280480_(stack, x, y); // 物品图标
                if (mouseX >= x && mouseX < x + GRID_CELL && mouseY >= y && mouseY < y + GRID_CELL) {
                    hoverIdx = i;
                }
            }
            // 悬停名/页码放网格右侧空白（同矿表/替代品子页）
            int infoX = left + this.gridCols * GRID_CELL + 12;
            this.gridInfoX = left + this.gridCols * GRID_CELL + 12;
            int infoY = gridTop + 2;
            boolean onlyNat = MaidSmartConfig.BRIDGE_BUILD_ONLY_NATURAL.get();
            if (hoverIdx >= 0 && hoverIdx < this.creativeItems.size()) {
                net.minecraft.world.item.ItemStack stack = this.creativeItems.get(hoverIdx);
                net.minecraft.resources.ResourceLocation key = net.minecraftforge.registries.ForgeRegistries.ITEMS.getKey(stack.m_41720_());
                String hover = key == null ? "?" : key.toString();
                String hc = com.maidsmart.build.BlueprintLib.cnName(hover);
                g.m_280614_(this.f_96547_,
                        Component.m_237113_("\u00a7f" + (hc.equals(hover) ? hover : hc)),
                        infoX, infoY, 0xFFFFFF, false);
                g.m_280614_(this.f_96547_,
                        Component.m_237113_("\u00a77" + hover
                                + (com.maidsmart.tool.NaturalBlocks.contains(hover)
                                        ? " \u00a7a(原版天然)" : " \u00a7c(非天然)")),
                        infoX, infoY + 10, 0xAAAAAA, false);
            } else {
                int pages = this.creativePages();
                if (pages > 1) {
                    String pg = "第 " + (this.creativePage + 1) + "/" + pages + " 页";
                    g.m_280614_(this.f_96547_, Component.m_237113_(pg), infoX, infoY, 0x888888, false);
                }
            }
            String chkHint = "\u00a7c\u2716\u00a77 = 禁止（"
                    + MaidSmartConfig.BRIDGE_BUILD_FORBIDDEN.get().size()
                    + " 项）· \u00a7a\u2714\u00a77 = 允许（"
                    + MaidSmartConfig.BRIDGE_BUILD_ALLOWED.get().size()
                    + " 项）；点一下切换。默认规则（" + (onlyNat ? "\u00a7a开" : "\u00a78关") + "\u00a77）："
                    + (onlyNat ? "只许原版天然方块（共 " + com.maidsmart.tool.NaturalBlocks.size()
                            + " 种），模组方块默认全禁"
                            : "除禁用名单外都放行——想恢复默认把上面那条点回开的");
            this.drawCenteredLines(g, this.wrapFit(chkHint), this.f_96544_ - 44, 0x888888);
        } else if (this.cookTable) {
            this.renderCookGrid(g, mouseX, mouseY, w, h, cx);
        } else if (this.bdRules) {
            this.renderBdRulesGrid(g, mouseX, mouseY, w, h, cx);
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
                if (i == this.focusRow) {
                    // 实测四百二十四：手册链接跳转命中行——金色底边高亮
                    g.m_280509_(6, y - 1, this.f_96543_ - 6, y + 20, 0x33FFD700);
                }
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
                } else if (def instanceof TextRow tr) {
                    // v1.2.2 实测六百二十二【字符串输入行一直没人画标签】——渲染链里
                    // NumRow/CycleRow/BoolRow/BtnRow/InfoRow 都有分支，**唯独漏了
                    // TextRow**：init 侧照常给它建输入框、rowHeight 照常按注释折行留高，
                    // 渲染侧却一个字都不画 → 玩家看到的是"一页上只有一个没有标题的黑框"
                    // （实测六百二十一 新加的「模式分类表」正好落在这种页面上，被当成
                    // "根本没做出来"）。补上与 NumRow 同款的标签+注释。
                    g.m_280614_(this.f_96547_, Component.m_237113_(tr.label()), leftR, y + 5, LABEL_COLOR, false);
                    this.drawComment(g, tr.comment(), y + 25);
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
        // v1.3.0 实测六百六十六：保存提示（越界被钳 / 没写成功）——画在底部按钮行上方，
        // 只在有内容时出现；下一次成功的保存会把它清掉
        if (this.saveHint != null && !this.saveHint.isEmpty()) {
            g.m_280653_(this.f_96547_, Component.m_237113_(this.saveHint), cx, h - 48, 0xFFFF5555);
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
            int gridRowsNow = this.gridRowsFor(this.f_96544_); // v1.5.190：与按钮/渲染同步
            int gridBottom = gridTop + gridRowsNow * GRID_CELL;
            if (mouseX >= left && mouseX < left + this.gridCols * GRID_CELL
                    && mouseY >= gridTop && mouseY < gridBottom) {
                int perPage = this.gridCols * this.gridRows;
                int start = this.creativePage * perPage;
                int col = (int) ((mouseX - left) / GRID_CELL);
                int row = (int) ((mouseY - gridTop) / GRID_CELL);
                int idx = start + row * this.gridCols + col;
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
        // v1.2.0 实测五百一十九：投喂食物子页网格点击 → 切换该物品"能不能吃"
        if (this.foodTable && button == 0) {
            int fcx = this.f_96543_ / 2;
            int fPanelLeft = Math.max(8, fcx - 280);
            int fLeft = fPanelLeft + 10;
            int fGridTop = GRID_TOP;
            int fGridRows = this.gridRowsFor(this.f_96544_);
            int fGridBottom = fGridTop + fGridRows * GRID_CELL;
            if (mouseX >= fLeft && mouseX < fLeft + this.gridCols * GRID_CELL
                    && mouseY >= fGridTop && mouseY < fGridBottom) {
                int perPage = this.gridCols * this.gridRows;
                int start = this.creativePage * perPage;
                int col = (int) ((mouseX - fLeft) / GRID_CELL);
                int row = (int) ((mouseY - fGridTop) / GRID_CELL);
                int idx = start + row * this.gridCols + col;
                if (idx >= 0 && idx < this.creativeItems.size()) {
                    // 实测五百八十：切换的是"这一档剩余次数"（不是整个物品）
                    this.toggleFoodChecked(
                            com.maidsmart.action.ItemUses.key(this.creativeItems.get(idx)));
                    return true;
                }
            }
        }
        // 实测五百七十三：喂水白名单子页网格点击 → 切换该物品"能不能喂"
        if (this.waterTable && button == 0) {
            int wcx = this.f_96543_ / 2;
            int wPanelLeft = Math.max(8, wcx - 280);
            int wLeft = wPanelLeft + 10;
            int wGridTop = GRID_TOP;
            int wGridRows = this.gridRowsFor(this.f_96544_);
            int wGridBottom = wGridTop + wGridRows * GRID_CELL;
            if (mouseX >= wLeft && mouseX < wLeft + this.gridCols * GRID_CELL
                    && mouseY >= wGridTop && mouseY < wGridBottom) {
                int perPage = this.gridCols * this.gridRows;
                int start = this.creativePage * perPage;
                int col = (int) ((mouseX - wLeft) / GRID_CELL);
                int row = (int) ((mouseY - wGridTop) / GRID_CELL);
                int idx = start + row * this.gridCols + col;
                if (idx >= 0 && idx < this.creativeItems.size()) {
                    // 实测五百八十：切换的是"这一档剩余次数"（不是整个物品）
                    this.toggleWaterChecked(
                            com.maidsmart.action.ItemUses.key(this.creativeItems.get(idx)));
                    return true;
                }
            }
        }
        // 实测八百一十七：支援物品分类子页网格点击 → 循环切换该物品的分类
        if (this.supportTable && button == 0) {
            int scx = this.f_96543_ / 2;
            int sLeft = Math.max(8, scx - 280) + 10;
            int sGridTop = GRID_TOP;
            int sGridRows = this.gridRowsFor(this.f_96544_);
            int sGridBottom = sGridTop + sGridRows * GRID_CELL;
            if (mouseX >= sLeft && mouseX < sLeft + this.gridCols * GRID_CELL
                    && mouseY >= sGridTop && mouseY < sGridBottom) {
                int perPage = this.gridCols * this.gridRows;
                int start = this.creativePage * perPage;
                int col = (int) ((mouseX - sLeft) / GRID_CELL);
                int row = (int) ((mouseY - sGridTop) / GRID_CELL);
                int idx = start + row * this.gridCols + col;
                if (idx >= 0 && idx < this.creativeItems.size()) {
                    this.cycleSupportCat(com.maidsmart.combat.MaidSupportPolicy
                            .key(this.creativeItems.get(idx)));
                    return true;
                }
            }
        }
        // v1.3.0(beta) 实测六百八十：搭方块禁用名单子页——点方块图标切换"禁止/允许"
        if (this.buildBlackTable && button == 0) {
            int cx = this.f_96543_ / 2;
            int panelLeft = Math.max(8, cx - 280);
            int left = panelLeft + 10;
            int gridTop = GRID_TOP;
            int gridRowsNow = this.gridRowsFor(this.f_96544_);
            int gridBottom = gridTop + gridRowsNow * GRID_CELL;
            if (mouseX >= left && mouseX < left + this.gridCols * GRID_CELL
                    && mouseY >= gridTop && mouseY < gridBottom) {
                int perPage = this.gridCols * this.gridRows;
                int start = this.creativePage * perPage;
                int col = (int) ((mouseX - left) / GRID_CELL);
                int row = (int) ((mouseY - gridTop) / GRID_CELL);
                int idx = start + row * this.gridCols + col;
                if (idx >= 0 && idx < this.creativeItems.size()) {
                    this.toggleBuildBlack(this.creativeItems.get(idx));
                    return true;
                }
            }
        }
        if (this.altTable && button == 0) {
            int cx = this.f_96543_ / 2;
            int panelLeft = Math.max(8, cx - 280);
            int left = panelLeft + 10;
            int gridTop = GRID_TOP;
            int gridRowsNow = this.gridRowsFor(this.f_96544_);
            int gridBottom = gridTop + gridRowsNow * GRID_CELL;
            if (mouseX >= left && mouseX < left + this.gridCols * GRID_CELL
                    && mouseY >= gridTop && mouseY < gridBottom) {
                int perPage = this.gridCols * this.gridRows;
                int start = this.creativePage * perPage;
                int col = (int) ((mouseX - left) / GRID_CELL);
                int row = (int) ((mouseY - gridTop) / GRID_CELL);
                int idx = start + row * this.gridCols + col;
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
        // v1.2.5 实测六百五十二：烧制清单子页网格点击 → 在当前那张名单里加入/移出
        // v1.3.0 实测六百五十五【子页上所有按钮都是死按键】：
        // 原来这里写的是 `return this.clickCookGrid(...)` —— clickCookGrid 只在命中物品格时
        // 返回 true，其余一律 false，而这个 false **被直接 return 出去**，于是
        // super.mouseClicked 永远不会执行 → 子页上每一个子控件（四张名单的模式按钮、
        // 搜索框、加进当前名单、名单行的「移出」、← 返回参数）全部收不到点击，
        // 看着就是"面板上的按键按了没用"，整个子页只能按 ESC 退出。
        // 对照同机制能用的几个子页（挖矿/伐木、投喂、喂水、替代品）：它们都是
        // "命中网格就 return true，否则**落到** super.m_6375_" —— 这里改成同构写法。
        if (this.cookTable && this.clickCookGrid(mouseX, mouseY, button)) {
            return true;
        }
        // 实测七百七十二：超越维度规则名单子页网格点击 → 在当前模式下加入/移出该物品
        if (this.bdRules && button == 0 && this.clickBdRuleGrid(mouseX, mouseY)) {
            return true;
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

    /**
     * 把输入框里未提交的文本统一写进配置（v1.5.124 延迟提交的**唯一**入口）。
     *
     * v1.3.0 实测六百六十六【不再静默丢输入】——
     *
     * （格式非法 / 写入异常）就一声不响地丢掉，而输入框只标"不是数字"、不标"超出范围"，
     * 玩家完全看不出为什么没生效。现在把没写成功的行名带回去，由 {@link #m_7379_}
     * 画在面板上（并且**不关面板**，让玩家当场改）。
     *
     * @return 没写成功的行标签列表（空 = 全部写成功）
     */
    private java.util.List<String> applyPending() {
        java.util.List<String> failed = new java.util.ArrayList<>();
        for (java.util.Map.Entry<String, String> e : this.pendingText.entrySet()) {
            Function<String, Boolean> setter = this.numSetters.get(e.getKey());
            if (setter == null) {
                continue; // 文本行在下面那一轮
            }
            String text = e.getValue() == null ? "" : e.getValue().trim();
            if (text.isEmpty()) {
                continue; // 空文本 = 没动过这一行（保留原值）
            }
            boolean ok = false;
            try {
                ok = Boolean.TRUE.equals(setter.apply(text));
            } catch (Exception ignored) { com.maidsmart.diag.Diag.swallow("PromaidConfigScreen", ignored);
            }
            if (!ok) {
                failed.add(shortLabel(e.getKey()));
            }
        }
        // v1.5.127：文本行（保留/垃圾物品 id 列表）——非空即写入
        // v1.5.198：记忆 API 字段允许空值写入（清空 = 回退 TLM 配置）
        for (java.util.Map.Entry<String, String> e : this.pendingText.entrySet()) {
            Function<String, Boolean> setter = this.textSetters.get(e.getKey());
            if (setter == null) {
                continue;
            }
            if (e.getValue().trim().isEmpty() && !EMPTY_ALLOWED.contains(setter)) {
                continue;
            }
            // 文本行（名单类）写失败**不拦面板**：它们的口径本来就是"解析为空 = 不改"
            // （见 setStringList），拦住只会让玩家莫名其妙关不掉面板；这里只留一条日志。
            try {
                if (!Boolean.TRUE.equals(setter.apply(e.getValue()))) {
                    com.maidsmart.tool.PromaidLog.log("配置面板",
                            "这一行没改（内容解析为空）：" + shortLabel(e.getKey()));
                }
            } catch (Exception ignored) { com.maidsmart.diag.Diag.swallow("PromaidConfigScreen", ignored);
            }
        }
        return failed;
    }

    /** 行键（"板块:标签"）→ 只留标签（写给玩家看的短名） */
    private static String shortLabel(String rowKey) {
        if (rowKey == null) {
            return "?";
        }
        int i = rowKey.indexOf(':');
        return i < 0 ? rowKey : rowKey.substring(i + 1);
    }

    @Override
    public void m_7379_() {
        this.saveHint = null;
        this.numNotices.clear();
        java.util.List<String> failed = this.applyPending();
        if (!failed.isEmpty()) {
            // 有行没写成功：报出来 + **不关面板**（旧版：静默丢掉然后照样关闭，
            // 玩家只会觉得"面板调不动"）
            this.saveHint = "\u00a7c这几行没保存（不是数字或写不进去）：" + String.join("、", failed)
                    + "（改好后再点「保存并返回」）";
            com.maidsmart.tool.PromaidLog.log("配置面板", "未保存的行：" + String.join("、", failed));
            return;
        }
        if (!this.numNotices.isEmpty()) {
            // 越界被钳到边界：明确告诉玩家按多少生效（旧版是静默钳，重进游戏才发现数变了）
            this.saveHint = "\u00a7e" + String.join("；", this.numNotices);
            com.maidsmart.tool.PromaidLog.log("配置面板", String.join("；", this.numNotices));
        }
        MaidSmartConfig.SPEC.save();
        com.maidsmart.task.MaidMineBehavior.loadCustomOres();
        com.maidsmart.task.MaidWoodBehavior.loadCustomWoods();
        com.maidsmart.task.MaidCookBehavior.loadCookLists();
        Minecraft.m_91087_().m_91152_(this.parent);
    }
    /** 实测四百二十三：大类页的一句话说明。 */
    private static String groupHint(Group g) {
        return switch (g) {
            case WORK -> "\u00a77建造 / 挖矿 / 伐木 / 烹饪与酿造 / 农场宰杀 / 排班表";
            case AI -> "\u00a77记忆 / 对话 / 感知 / 情绪 / AI 工具";
            case COMBAT -> "\u00a77自保 / 战术 / 主动参战 / 贴身辅助 / 玩家伤害 / 空袭数值";
            case SURVIVAL -> "\u00a77落地缓冲 / 死亡复活 / 传送逃生 / 安全保载";
            // 【实测六百七十五】顺序与「移动与行为」页里的小类按钮一致（飞行三件套排在最前）
            case MOVE -> "\u00a77飞行跟随 / 扫帚模式 / 武装拴绳（二号位）/ 移动与跟随 / 空闲流畅 / 搭路";
            case UI -> "\u00a77语音 TTS / 显示与气泡";
            case SYSTEM -> "\u00a77交互杂项 / 运行日志 / 压缩盒";
        };
    }

    /** 实测四百二十四：行标签（供手册链接定位高亮；SectionRow 无标签）。 */
    private static String rowLabel(RowDef def) {
        if (def instanceof NumRow r) {
            return r.label();
        }
        if (def instanceof BoolRow r) {
            return r.label();
        }
        if (def instanceof BtnRow r) {
            return r.label();
        }
        if (def instanceof CycleRow r) {
            return r.label();
        }
        if (def instanceof TextRow r) {
            return r.label();
        }
        if (def instanceof InfoRow r) {
            return r.label();
        }
        return null;
    }


}
