package com.maidsmart.brew;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.alchemy.Potion;
import net.minecraft.world.item.alchemy.PotionUtils;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.List;

/**
 * 女仆药剂手册配置界面（v1.1.0 实测二百七十七）——手持手册右键女仆打开。
 *
 * 布局（v1.1.0 实测二百七十九重排——实测二百七十八的说明文字压按钮、定向模式
 * 强化行照常显示造成"反人类认知"，重排为：模式行 → 说明行（独立一行）→
 * 形态行（两种模式同一位置）→ 批量=强化行 / 定向=搜索框+网格）：
 * - 顶部：标题 + 模式切换（批量酿造 / 定向酿造）
 * - 说明行：当前模式一句话说明（独立行，不与任何按钮重叠）
 * - 形态行（饮用/喷溅/滞留）两种模式同一位置
 * - 批量区：强化路线行（无/红石/萤石，定向模式隐藏——定向的强化走 long_/strong_
 *   变体药水，不使用此参数）
 * - 定向区：搜索框 + 16×N 药水网格 + 翻页；点击图标选中/取消（单选），悬停
 *   显示本地化名 + 配方链预览
 * - 底部：✓ 说明 + 保存并返回（发 C2S 保存包）
 *
 * v1.1.0 实测二百七十九核心修复：选中/点击判定此前用 ITEMS.getKey(stack.getItem())
 * ——所有药水瓶物品注册名都是 minecraft:potion，点一个等于全选、保存的目标也错。
 * 改为按药水注册名（如 minecraft:healing）判定；悬停名改用药水本地化名
 * （stack hoverName，走客户端语言文件）而非注册名。
 *
 * 布局坐标统一口径：所有 y 坐标在 m_7856_ 里算好存字段，渲染与点击共用同一份。
 */
public class BrewManualScreen extends Screen {
    private static final int LABEL_COLOR = 0xFFAAAAAA;
    private static final int PANEL_BG = 0xC0101010;
    private static final int GRID_COLS = 16;
    private static final int GRID_CELL = 20;
    private static final int BTN_W = 110;
    private static final int BTN_H = 18;
    private static final int BTN_GAP = 8;

    private final String maidUuid;
    private final BrewConfig cfg;

    /** 定向模式：药水选择面板状态 */
    private EditBox creativeInput;
    private String creativeQuery = "";
    private int creativePage = 0;
    private final List<GridEntry> potionEntries = new ArrayList<>();
    private static int gridRows = 3;

    /** 布局坐标（m_7856_ 统一计算，渲染/点击共用） */
    private int searchY = 0;
    private int gridTop = 0;
    private int gridBottom = 0;
    private int left = 0;
    private int panelWidth = 0;

    /** 药水候选缓存（{id, path}）——注册表遍历一次，之后按键只做内存过滤 */
    private static List<String[]> potionCache = null;
    private static long potionCacheBuilt = -1;

    /** 药水网格条目：药水注册名 + 图标 stack（id 用于选中/保存判定） */
    private static final class GridEntry {
        final String id;
        final ItemStack stack;

        GridEntry(String id, ItemStack stack) {
            this.id = id;
            this.stack = stack;
        }
    }

    public BrewManualScreen(String maidUuid, BrewConfig cfg) {
        super(Component.m_237113_("女仆药剂手册"));
        this.maidUuid = maidUuid;
        this.cfg = cfg;
    }

    /** 客户端：打开界面（S2C 包到达时调用） */
    public static void open(String maidUuid, BrewConfig cfg) {
        Minecraft.m_91087_().m_91152_(new BrewManualScreen(maidUuid, cfg));
    }

    // ---------- init ----------

    @Override
    protected void m_7856_() {
        this.m_169413_(); // clearWidgets
        int w = this.f_96543_;
        int h = this.f_96544_;
        int cx = w / 2;
        int panelLeft = Math.max(8, cx - 280);
        this.panelWidth = Math.min(560, w - 16);
        this.left = panelLeft + 10;

        // 顶部：模式切换（y=26）
        int tgY = 26;
        String[] modeNames = {"批量酿造", "定向酿造"};
        for (int i = 0; i < modeNames.length; i++) {
            final int mi = i;
            this.m_142416_(Button.m_253074_(
                            Component.m_237113_((this.cfg.mode == mi ? "\u00a7e\u25cf " : "\u00a77") + modeNames[i]),
                            b -> {
                                this.cfg.mode = mi;
                                this.m_7856_();
                            })
                    .m_252987_(this.left + i * (BTN_W + BTN_GAP), tgY, BTN_W, BTN_H).m_253136_());
        }

        // 说明行（y=50，独立一行不压按钮）；批量模式强化行（y=68）
        boolean batch = this.cfg.mode == BrewConfig.MODE_BATCH;
        if (batch) {
            String[] enhanceNames = {"无强化", "红石延长", "萤石强化"};
            for (int i = 0; i < enhanceNames.length; i++) {
                final int ei = i;
                this.m_142416_(Button.m_253074_(
                                Component.m_237113_((this.cfg.enhance == ei ? "\u00a7e\u25cf " : "\u00a77") + enhanceNames[i]),
                                b -> {
                                    this.cfg.enhance = ei;
                                    this.m_7856_();
                                })
                        .m_252987_(this.left + i * (BTN_W + BTN_GAP), 68, BTN_W, BTN_H).m_253136_());
            }
        }

        // 形态行（y=90，两种模式同一位置）
        // v1.1.0 实测二百八十二：切形态清除已选目标——旧版绿框只比药水 id 不比
        // 形态，选中"跳跃药水"后切喷溅/滞留，绿框恒亮（targetPotion 不变），玩家
        // 看到三种形态的药水"同时被勾选"（重开 GUI 依次点三形态也是全部显示
        // 选中），完全无法分辨当前到底要酿哪种形态。清空后需在当前形态下重新
        // 点选，绿框=「该形态下的目标」语义唯一
        int formY = 90;
        String[] formNames = {"饮用", "喷溅", "滞留"};
        for (int i = 0; i < formNames.length; i++) {
            final int fi = i;
            this.m_142416_(Button.m_253074_(
                            Component.m_237113_((this.cfg.form == fi ? "\u00a7e\u25cf " : "\u00a77") + formNames[i]),
                            b -> {
                                if (this.cfg.form != fi) {
                                    this.cfg.form = fi;
                                    this.cfg.targetPotion = ""; // 切形态=改目标形态，需重新点选
                                    this.m_7856_();
                                }
                            })
                    .m_252987_(this.left + i * (BTN_W + BTN_GAP), formY, BTN_W, BTN_H).m_253136_());
        }

        // 定向区：搜索框（y=118）+ 网格
        if (!batch) {
            this.searchY = 118;
            this.creativeInput = new EditBox(this.f_96547_, this.left, this.searchY, this.panelWidth - 20, 18,
                    Component.m_237113_("药水搜索"));
            this.creativeInput.m_94199_(64);
            this.creativeInput.m_94144_(this.creativeQuery == null ? "" : this.creativeQuery);
            this.creativeInput.m_94151_(s -> {
                this.creativeQuery = s;
                this.rebuildPotionGrid();
            });
            this.m_142416_(this.creativeInput);

            // 矮窗口兜底：3 行（≥300px）→ 2 行（≥230px）→ 1 行，网格不越过保存按钮
            gridRows = h >= 300 ? 3 : (h >= 230 ? 2 : 1);
            this.gridTop = this.searchY + 22;
            this.gridBottom = this.gridTop + gridRows * GRID_CELL;
            this.rebuildPotionGrid();
            // v1.1.0 实测二百八十三：箭头间距拉开（旧版 cx±34/-14 与居中页码
            // "第 1/2 页"重叠）
            int py = this.gridBottom + 3;
            if (this.creativePage > 0) {
                this.m_142416_(Button.m_253074_(Component.m_237113_("\u00a77◀"),
                                b -> {
                                    this.creativePage--;
                                    this.m_7856_();
                                })
                        .m_252987_(cx - 70, py, 20, 16).m_253136_());
            }
            if (this.creativePage < this.potionPages() - 1) {
                this.m_142416_(Button.m_253074_(Component.m_237113_("\u00a77▶"),
                                b -> {
                                    this.creativePage++;
                                    this.m_7856_();
                                })
                        .m_252987_(cx + 50, py, 20, 16).m_253136_());
            }
        }

        // 底部：保存并返回
        this.m_142416_(Button.m_253074_(Component.m_237113_("保存并返回"),
                        b -> this.saveAndClose())
                .m_252987_(w - 148, h - 34, 132, 20).m_253136_());
    }

    // ---------- 药水网格 ----------

    private static void ensurePotionCache() {
        long now = System.currentTimeMillis();
        if (potionCache != null && now - potionCacheBuilt < 60_000L) {
            return;
        }
        potionCache = new ArrayList<>();
        boolean tableUsable = BrewRecipeResolver.isTableUsable();
        for (Potion p : ForgeRegistries.POTIONS) {
            if (p == null) {
                continue;
            }
            net.minecraft.resources.ResourceLocation key = ForgeRegistries.POTIONS.getKey(p);
            if (key == null) {
                continue;
            }
            String path = key.m_135815_();
            // 基底/无效果药水不作为目标（water 是酿造起点，mundane/thick 无效果）
            if (path.equals("water") || path.equals("mundane") || path.equals("thick") || path.equals("empty")) {
                continue;
            }
            // 只列可酿造的药水（有链可达）；配方表不可用时降级列出全部（界面永远可用）
            if (tableUsable && !BrewRecipeResolver.isBrewable(p)) {
                continue;
            }
            // v1.1.0 实测二百八十一：加本地化名（"跳跃药水"）——搜索框支持中文
            String name = makePotionStack(key.toString(), 0).m_41611_().getString();
            // 搜索词 = id + path + 本地化名
            potionCache.add(new String[]{key.toString(), path, name});
        }
        potionCacheBuilt = now;
    }

    private void rebuildPotionGrid() {
        this.potionEntries.clear();
        ensurePotionCache();
        String q = this.creativeQuery == null ? "" : this.creativeQuery.trim().toLowerCase();
        for (String[] e : potionCache) {
            // v1.1.0 实测二百八十一：中文本地化名也可搜索（旧版只匹配注册名，
            // 输"跳跃药水"搜不到任何药水）
            if (q.isEmpty() || e[0].toLowerCase().contains(q) || e[1].toLowerCase().contains(q)
                    || (e.length > 2 && e[2] != null && e[2].toLowerCase().contains(q))) {
                // v1.1.0 实测二百八十：图标随形态联动（喷溅/滞留用对应容器物品渲染）
                this.potionEntries.add(new GridEntry(e[0], makePotionStack(e[0], this.cfg.form)));
            }
        }
        int perPage = GRID_COLS * gridRows;
        int pages = Math.max(1, (this.potionEntries.size() + perPage - 1) / perPage);
        this.creativePage = Math.min(Math.max(this.creativePage, 0), pages - 1);
    }

    /** 构造指定药水的药水瓶 ItemStack（图标渲染 + 本地化名用）。
     *  v1.1.0 实测二百八十：form 决定容器物品（0=水瓶/1=喷溅/2=滞留），图标随之变化 */
    private static ItemStack makePotionStack(String potionId, int form) {
        String itemId = form == BrewConfig.FORM_SPLASH ? "minecraft:splash_potion"
                : form == BrewConfig.FORM_LINGERING ? "minecraft:lingering_potion"
                : "minecraft:potion";
        ItemStack stack = new ItemStack(com.maidsmart.brew.BrewRecipeResolver.item(itemId));
        try {
            Potion p = ForgeRegistries.POTIONS.getValue(new net.minecraft.resources.ResourceLocation(potionId));
            if (p != null) {
                PotionUtils.m_43549_(stack, p);
            }
        } catch (Throwable ignored) {
        }
        return stack;
    }

    private int potionPages() {
        int perPage = GRID_COLS * gridRows;
        return Math.max(1, (this.potionEntries.size() + perPage - 1) / perPage);
    }

    // ---------- 保存 ----------

    private void saveAndClose() {
        com.maidsmart.brew.BrewManualNetworking.CHANNEL.sendToServer(
                new com.maidsmart.brew.BrewManualNetworking.SaveBrewConfigPacket(this.maidUuid, this.cfg));
        this.m_7379_();
    }

    // ---------- 渲染 ----------

    @Override
    public void m_88315_(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        this.m_280039_(g); // renderBackground
        int w = this.f_96543_;
        int h = this.f_96544_;
        int cx = w / 2;
        int panelLeft = Math.max(8, cx - 290);
        int panelRight = Math.min(w - 8, cx + 290);
        g.m_280509_(panelLeft, 8, panelRight, h - 8, PANEL_BG);
        g.m_280653_(this.f_96547_, Component.m_237113_("\u00a7e女仆药剂手册——酿造配置"), cx, 10, 0xFFFFD700);

        boolean batch = this.cfg.mode == BrewConfig.MODE_BATCH;

        // 说明行（y=50，独立一行）
        String modeHint = batch
                ? "\u00a77批量酿造：背包里有什么正向材料就酿什么，按下方强化/形态统一处理"
                : "\u00a77定向酿造：先选形态，再点击下方药水图标选定目标（切形态会清除已选，需重新点选）；缺料会立即报告";
        g.m_280614_(this.f_96547_, Component.m_237113_(modeHint), this.left, 50, 0x888888, false);

        // 定向模式：药水网格（坐标与 init 侧同源）
        if (!batch) {
            g.m_280509_(this.left - 6, this.gridTop - 4, this.left + GRID_COLS * GRID_CELL + 6,
                    this.gridBottom, 0x80101010);
            int perPage = GRID_COLS * gridRows;
            int start = this.creativePage * perPage;
            int end = Math.min(this.potionEntries.size(), start + perPage);
            int hoverIdx = -1;
            for (int i = start; i < end; i++) {
                GridEntry entry = this.potionEntries.get(i);
                int col = (i - start) % GRID_COLS;
                int row = (i - start) / GRID_COLS;
                int x = this.left + col * GRID_CELL;
                int y = this.gridTop + row * GRID_CELL;
                if (this.cfg.targetPotion != null && this.cfg.targetPotion.equals(entry.id)) {
                    // 已选 → 绿色框 + ✓
                    g.m_280509_(x - 1, y - 1, x + 17, y + 17, 0x8022CC22);
                    g.m_280653_(this.f_96547_, Component.m_237113_("\u2714"), x + 12, y + 12, 0xFFFFFF);
                }
                g.m_280480_(entry.stack, x, y); // 药水图标（带颜色）
                if (mouseX >= x && mouseX < x + GRID_CELL && mouseY >= y && mouseY < y + GRID_CELL) {
                    hoverIdx = i;
                    g.m_280509_(x - 2, y - 2, x + 18, y + 18, 0x80FFD700);
                }
            }
            // 悬停信息 / 页码（网格右侧空白）
            int infoX = this.left + GRID_COLS * GRID_CELL + 14;
            int infoY = this.gridTop + 4;
            if (hoverIdx >= 0 && hoverIdx < this.potionEntries.size()) {
                GridEntry entry = this.potionEntries.get(hoverIdx);
                // 本地化药水名（"治疗药水"/"喷溅型夜视药水"……走客户端语言文件）
                String name = entry.stack.m_41611_().getString();
                g.m_280614_(this.f_96547_, Component.m_237113_("\u00a7f" + name),
                        infoX, infoY, 0xFFFFFF, false);
                // v1.1.0 实测二百八十一：链 + 材料清单改为跟随鼠标的 tooltip
                //（仿 EMC 面板悬停样式——旧版画在网格右侧固定区，链 wrap 行数多时
                // 把"每批 3 瓶材料"推到面板底缘，材料图标行被矮窗口保护线裁掉，
                // 玩家根本看不见；tooltip 跟随鼠标永远在视野内）
                g.m_280677_(this.f_96547_, tooltipLines(entry), java.util.Optional.empty(),
                        mouseX, mouseY);
            } else {
                int pages = this.potionPages();
                if (pages > 1) {
                    String pg = "第 " + (this.creativePage + 1) + "/" + pages + " 页";
                    g.m_280614_(this.f_96547_, Component.m_237113_(pg),
                            cx - this.f_96547_.m_92895_(pg) / 2, this.gridBottom + 6, 0x888888, false);
                }
            }
        } else {
            // 批量模式：强化说明
            String[] enh = {"无强化", "红石延长（时效 ×8/3）", "萤石强化（效果等级 II）"};
            g.m_280614_(this.f_96547_, Component.m_237113_("\u00a77已选强化：" + enh[this.cfg.enhance]),
                    this.left, 118, 0x888888, false);
        }

        // 底部：✓ 说明（仅定向模式）
        if (!batch) {
            String chkHint = "\u00a77✓ = 当前形态下已选定的目标药水；女仆按配方链+形态酿造，缺什么会主动报告";
            g.m_280614_(this.f_96547_, Component.m_237113_(chkHint),
                    this.left, this.gridBottom + 24, 0x888888, false);
        }
        super.m_88315_(g, mouseX, mouseY, partialTick);
    }

    /**
     * v1.1.0 实测二百八十一：悬停 tooltip 行（跟随鼠标，仿 EMC 面板悬停样式）——
     * 药水名（白）→ 配方链（灰，尾部含形态材料步，超宽换行）→ "每批 3 瓶材料："
     * （黄）→ 每条材料一行"物品名 ×数量"（女仆一批下料 3 瓶的真实消耗：水瓶 3 +
     * 每步材料 1 + 形态材料 1/2），随当前形态实时变化。
     */
    private List<Component> tooltipLines(GridEntry entry) {
        List<Component> out = new ArrayList<>();
        try {
            out.add(Component.m_237113_("\u00a7f" + entry.stack.m_41611_().getString()));
            // v1.1.0 实测二百八十三：药水效果行（名称+等级+时长）——旧版只有名字，
            // 玩家看不到具体效果。喷溅/滞留的实际时长酿造时才折算，此处按原版
            // tooltip 口径显示满时长
            // v1.1.0 实测二百九十三：按当前配置形态折算时长（喷溅 ×3/4、滞留 ×1/4）
            for (net.minecraft.world.effect.MobEffectInstance eff :
                    net.minecraft.world.item.alchemy.PotionUtils.m_43547_(entry.stack)) {
                out.add(Component.m_237113_("\u00a78" + effectLine(eff, this.cfg.form)));
            }
            BrewRecipeResolver.Chain chain = BrewRecipeResolver.chainFor(entry.id);
            if (chain == null || chain.isEmpty()) {
                if (!BrewRecipeResolver.isTableUsable()) {
                    out.add(Component.m_237113_("\u00a7c配方表加载失败：" + BrewRecipeResolver.lastError));
                } else {
                    out.add(Component.m_237113_("\u00a77无法酿造（注册表中无此配方）"));
                }
                return out;
            }
            StringBuilder sb = new StringBuilder("配方链：");
            sb.append(potionName(chain.base()));
            for (BrewRecipeResolver.Step s : chain.steps()) {
                sb.append(" → ").append(itemName(s.reagent()));
            }
            // 形态步：饮用→喷溅=火药，再→滞留=龙息
            if (this.cfg.form == BrewConfig.FORM_SPLASH) {
                sb.append(" → ").append(itemName(BrewRecipeResolver.formReagent(BrewConfig.FORM_SPLASH)));
            } else if (this.cfg.form == BrewConfig.FORM_LINGERING) {
                sb.append(" → ").append(itemName(BrewRecipeResolver.formReagent(BrewConfig.FORM_SPLASH)))
                        .append(" → ").append(itemName(BrewRecipeResolver.formReagent(BrewConfig.FORM_LINGERING)));
            }
            // 逐字符按像素宽切行（tooltip 不会自动 wrap）
            int avail = 165;
            StringBuilder cur = new StringBuilder();
            int curW = 0;
            for (int i = 0; i < sb.length(); i++) {
                char c = sb.charAt(i);
                int cw = this.f_96547_.m_92895_(String.valueOf(c)); // width(String)
                if (curW + cw > avail && cur.length() > 0) {
                    out.add(Component.m_237113_("\u00a77" + cur));
                    cur = new StringBuilder();
                    curW = 0;
                }
                cur.append(c);
                curW += cw;
            }
            if (cur.length() > 0) {
                out.add(Component.m_237113_("\u00a77" + cur));
            }
            // 材料清单（文字行，tooltip 内不放图标——数量用 §e 黄色显眼）
            out.add(Component.m_237113_(""));
            out.add(Component.m_237113_("\u00a7e每批 3 瓶材料："));
            for (Object[] m : materialList(entry.id, this.cfg.form)) {
                ItemStack ms = (ItemStack) m[0];
                int cnt = (Integer) m[1];
                out.add(Component.m_237113_("\u00a77" + ms.m_41611_().getString()
                        + " \u00a7e\u00d7" + cnt));
            }
        } catch (Throwable t) {
            out.add(Component.m_237113_("\u00a77"));
        }
        return out;
    }

    /**
     * 材料清单（{ItemStack, 数量}）——女仆定向酿造一批下料
     * 3 瓶的真实消耗：水瓶 ×3（酿造台 3 瓶槽同时起步）+ 链上每步材料 ×1（1 个
     * 材料可同时酿 3 瓶）+ 形态材料（喷溅=火药 ×1 / 滞留=火药 + 龙息各 ×1）。
     * 链为空（无法酿造）返回只有水瓶的清单。
     */
    private List<Object[]> materialList(String potionId, int form) {
        List<Object[]> mats = new ArrayList<>();
        try {
            mats.add(new Object[]{makePotionStack("minecraft:water", 0), 3});
            BrewRecipeResolver.Chain chain = BrewRecipeResolver.chainFor(potionId);
            if (chain != null && !chain.isEmpty()) {
                for (BrewRecipeResolver.Step s : chain.steps()) {
                    if (s.reagent() != null) {
                        mats.add(new Object[]{new ItemStack(s.reagent()), 1});
                    }
                }
            }
            if (form == BrewConfig.FORM_SPLASH) {
                mats.add(new Object[]{new ItemStack(BrewRecipeResolver.formReagent(BrewConfig.FORM_SPLASH)), 1});
            } else if (form == BrewConfig.FORM_LINGERING) {
                mats.add(new Object[]{new ItemStack(BrewRecipeResolver.formReagent(BrewConfig.FORM_SPLASH)), 1});
                mats.add(new Object[]{new ItemStack(BrewRecipeResolver.formReagent(BrewConfig.FORM_LINGERING)), 1});
            }
        } catch (Throwable ignored) {
        }
        return mats;
    }

    /** v1.1.0 实测二百八十四：效果行文本（"跳跃提升 I (3:00)"）——
     *  v1.1.0 实测二百八十三把 duration/amplifier 的 SRG 搞反了（截图实证：
     *  显示"跳跃提升 3601"= duration(3600) 被当成等级喂进罗马数字函数）。
     *  字节码字段序号实证：f_19503_（第 1 个 int 字段）= duration → m_19557_，
     *  f_19504_（第 2 个）= amplifier → m_19564_（vanilla 字段顺序 effect/
     *  duration/amplifier/ambient/visible/showIcon）。等级始终显示（I 也显示）
     *  v1.1.0 实测二百九十三：按形态折算时长——原版规则：喷溅 = 饮用 ×3/4、
     *  滞留 = 饮用 ×1/4（Potion 注册表存的是饮用型满时长，实际生效按形态
     *  折算）。旧版直接显示满时长，喷溅/滞留型药水的效果描述与实际不符
     *  （用户："滞留和喷溅型药水直接套用饮用型的时间描述"）。 */
    private String effectLine(net.minecraft.world.effect.MobEffectInstance eff, int form) {
        try {
            String name = eff.m_19544_().m_19482_().getString(); // getDisplayName
            int dur = eff.m_19557_();  // getDuration（饮用型满时长）
            int amp = eff.m_19564_();  // getAmplifier
            if (form == BrewConfig.FORM_SPLASH) {
                dur = dur * 3 / 4;
            } else if (form == BrewConfig.FORM_LINGERING) {
                dur = dur / 4;
            }
            name += " " + roman(amp + 1);
            if (dur > 20) {
                int sec = dur / 20;
                name += " (" + (sec / 60) + ":" + (sec % 60 < 10 ? "0" : "") + (sec % 60) + ")";
            }
            return name;
        } catch (Throwable t) {
            return "";
        }
    }

    /** 1-10 的罗马数字（效果等级显示用，超出直接数字） */
    private static String roman(int lv) {
        String[] r = {"I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X"};
        return lv >= 1 && lv <= 10 ? r[lv - 1] : String.valueOf(lv);
    }

    /** 药水本地化名（基底药水用，如"水瓶"/"粗制药水"） */
    private String potionName(Potion p) {        try {
            if (p == null) {
                return "?";
            }
            ItemStack stack = makePotionStack(ForgeRegistries.POTIONS.getKey(p).toString(), 0);
            return stack.m_41611_().getString();
        } catch (Throwable t) {
            return "?";
        }
    }

    /** 物品本地化名（链上材料，如"下界疣"/"金西瓜"） */
    private String itemName(net.minecraft.world.item.Item item) {
        try {
            if (item == null) {
                return "?";
            }
            return new ItemStack(item).m_41611_().getString();
        } catch (Throwable t) {
            return "?";
        }
    }

    // ---------- 点击 ----------

    @Override
    public boolean m_6375_(double mouseX, double mouseY, int button) {
        if (button == 0) {
            // 显式聚焦输入框（同 PromaidConfigScreen）
            for (net.minecraft.client.gui.components.events.GuiEventListener c : this.m_6702_()) {
                if (c instanceof EditBox eb && eb.m_5953_(mouseX, mouseY)) {
                    eb.m_93692_(true);
                }
            }
            if (this.cfg.mode == BrewConfig.MODE_TARGETED) {
                if (mouseX >= this.left && mouseX < this.left + GRID_COLS * GRID_CELL
                        && mouseY >= this.gridTop && mouseY < this.gridBottom) {
                    int perPage = GRID_COLS * gridRows;
                    int start = this.creativePage * perPage;
                    int col = (int) ((mouseX - this.left) / GRID_CELL);
                    int row = (int) ((mouseY - this.gridTop) / GRID_CELL);
                    int idx = start + row * GRID_COLS + col;
                    if (idx >= 0 && idx < this.potionEntries.size()) {
                        // 按药水注册名判定选中（修复：此前用物品注册名——所有药水瓶
                        // 都是 minecraft:potion，点一个等于全选、目标也存错）
                        String id = this.potionEntries.get(idx).id;
                        if (this.cfg.targetPotion != null && this.cfg.targetPotion.equals(id)) {
                            this.cfg.targetPotion = ""; // 再点取消
                        } else {
                            this.cfg.targetPotion = id;
                        }
                        return true;
                    }
                }
            }
        }
        return super.m_6375_(mouseX, mouseY, button);
    }

    @Override
    public boolean m_5534_(char codePoint, int modifiers) {
        if (this.creativeInput != null && this.creativeInput.m_5534_(codePoint, modifiers)) {
            return true;
        }
        return super.m_5534_(codePoint, modifiers);
    }

    @Override
    public boolean m_7933_(int key, int scanCode, int modifiers) {
        if (this.creativeInput != null && this.creativeInput.m_7933_(key, scanCode, modifiers)) {
            return true;
        }
        return super.m_7933_(key, scanCode, modifiers);
    }
}
