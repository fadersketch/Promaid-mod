package com.maidsmart.guide;

/**
 * 「详细介绍」内容定义（v1.5.252h 收官；v1.5.304 全面重写为保姆式教学）。
 *
 * 与 Promaid 手册（BlueprintBookScreen）的「建筑页面」同构：
 * 章节目录（分页按钮列表）→ 点章节 → 正文分页阅读（< 上一页 / 下一页 >）。
 *
 * v1.5.304 重写原则（反馈："重新整改 + 更详细 + 实操例子，手把手保姆式教学"）：
 * - 每章：先一句话讲清"这是什么"，再给分步操作，关键步骤附【举例】；
 * - 事实全部对齐当前版本（v1.5.303）：投喂直接喂（不再塞背包）、手持食物可喂、
 *   被动技能独立成栏、红石机器只剩自动熔炉组、门落地修复、阈值 4-20 真实生效、
 *   区块坐标显示、名单页跳转、进度条位置、输出语言选项选择等。
 *
 * 【实测八百四十四】手册不再有「更新日志」章：开发者决定撤掉游戏内更新日志（连资源文件一起删），
 * 以后的版本更新内容写在桌面 `Promaid文本` 目录里，release 说明从那里取素材（见 WORKFLOW §6.1）。
 */
public final class GuideContent {
    private GuideContent() {
    }

    /** 章节：标题 + 正文段落（每段自动换行渲染，段间空一行） */
    public static final class Chapter {
        public final String title;
        public final String[] paras;

        public Chapter(String title, String... paras) {
            this.title = title;
            this.paras = paras;
        }
    }

    /** 取长章节的段落区间 [from, to) 另起一章——超过十页的章节按语义拆短。 */
    public static Chapter slice(String title, Chapter src, int from, int to) {
        int a = Math.max(0, Math.min(from, src.paras.length));
        int b = Math.max(a, Math.min(to, src.paras.length));
        String[] part = new String[b - a];
        System.arraycopy(src.paras, a, part, 0, part.length);
        return new Chapter(title, part);
    }

    /** 全部章节 */
    public static Chapter[] chapters() {
        return new Chapter[]{
                GuideChaptersBasics.foreword(),
                GuideChaptersBasics.featureOverviewGuide(),
                GuideChaptersBasics.intro(),
                GuideChaptersBasics.llmSetup(),
                GuideChaptersBasics.bookGuide(),
                slice("建造系统教程 · 六步上手", GuideChaptersBasics.buildGuide(), 0, 14),
                slice("建造系统教程 · 蓝图与机械图", GuideChaptersBasics.buildGuide(), 14, 26),
                slice("建造系统教程 · 细则与取材", GuideChaptersBasics.buildGuide(), 26, 32),
                GuideChaptersBasics.indexStoneGuide(),
                slice("压缩盒 · 入门与操作", GuideChaptersBasics.compressionBoxGuide(), 0, 14),
                slice("压缩盒 · 女仆使用与爆炸物", GuideChaptersBasics.compressionBoxGuide(), 14, 22),
                slice("压缩盒 · 配置与自检", GuideChaptersBasics.compressionBoxGuide(), 22, 24),
                GuideChaptersWork.maidManageGuide(),
                GuideChaptersWork.mineGuide(),
                GuideChaptersWork.woodGuide(),
                GuideChaptersWork.cookGuide(),
                slice("农场与杂项功能 · 农场", GuideChaptersWork.farmMiscGuide(), 0, 17),
                slice("农场与杂项功能 · 宰杀与杂项", GuideChaptersWork.farmMiscGuide(), 17, 25),
                slice("排班表 · 列表与传送", GuideChaptersWork.scheduleGuide(), 0, 10),
                slice("排班表 · 日程设置", GuideChaptersWork.scheduleGuide(), 10, 22),
                slice("排班表 · 规则与工作区", GuideChaptersWork.scheduleGuide(), 22, 29),
                slice("排班表 · 示例与总开关", GuideChaptersWork.scheduleGuide(), 29, 32),
                slice("排班表 · 气泡与语音", GuideChaptersWork.scheduleGuide(), 32, 36),
                slice("自保与保命 · 原理与逃生", GuideChaptersCombat.combatGuide(), 0, 9),
                slice("自保与保命 · 魂符与搭高", GuideChaptersCombat.combatGuide(), 9, 20),
                slice("自保与保命 · 实战举例", GuideChaptersCombat.combatGuide(), 28, 30),
                slice("主动参战 · 选模式与还原", GuideChaptersCombat.combatGuide(), 20, 26),
                slice("主动参战 · 威胁判定与免伤", GuideChaptersCombat.combatGuide(), 26, 28),
                slice("空袭与飞行作战 · 入门与三件套", GuideChaptersFlight.flightGuide(), 0, 16),
                slice("空袭与飞行作战 · 弹药与首次起飞", GuideChaptersFlight.flightGuide(), 16, 22),
                slice("空袭与飞行作战 · 战斗链路与索敌", GuideChaptersFlight.flightGuide(), 22, 51),
                slice("空袭与飞行作战 · 开关与法术", GuideChaptersFlight.flightGuide(), 51, 72),
                slice("空袭与飞行作战 · 数值面板", GuideChaptersFlight.flightGuide(), 72, 78),
                slice("空袭与飞行作战 · 空袭轰炸（上）", GuideChaptersFlight.flightGuide(), 78, 91),
                slice("空袭与飞行作战 · 空袭轰炸（下）", GuideChaptersFlight.flightGuide(), 91, 104),
                slice("空袭与飞行作战 · 第三方玩法与排查", GuideChaptersFlight.flightGuide(), 104, 117),
                slice("仿创造飞行", GuideChaptersFlight.flightGuide(), 117, 127),
                slice("诡厄巫法飞行聚晶", GuideChaptersFlight.flightGuide(), 127, 135),
                GuideChaptersFlight.spearGuide(),
                slice("被动技能 · 落地与死亡", GuideChaptersFlight.passiveGuide(), 0, 5),
                slice("被动技能 · 搭路与垫脚回收", GuideChaptersFlight.passiveGuide(), 5, 13),
                slice("被动技能 · 飞行跟随", GuideChaptersFlight.passiveGuide(), 13, 24),
                slice("女仆自动复活 · 机制", GuideChaptersFlight.autoResurrectGuide(), 0, 11),
                slice("女仆自动复活 · 示例与设置", GuideChaptersFlight.autoResurrectGuide(), 11, 17),
                GuideChaptersFlight.bedInteropGuide(),
                slice("扫帚模式 · 入门与飞行", GuideChaptersFlight.broomGuide(), 0, 19),
                slice("扫帚模式 · 限制与参数", GuideChaptersFlight.broomGuide(), 19, 30),
                slice("扫帚模式 · 随机环绕与弹药", GuideChaptersFlight.broomGuide(), 30, 47),
                slice("扫帚模式 · 换枪与排查", GuideChaptersFlight.broomGuide(), 47, 59),
                GuideChaptersFlight.patrolGuide(),
                GuideChaptersCombat.tetherGuide(),
                slice("骑乘指挥棒 · 绑定与解除", GuideChaptersCombat.rideGuide(), 0, 12),
                slice("骑乘指挥棒 · 走位与索敌", GuideChaptersCombat.rideGuide(), 12, 25),
                slice("骑乘指挥棒 · 载具与龙", GuideChaptersCombat.rideGuide(), 25, 38),
                slice("骑乘指挥棒 · 固定翼", GuideChaptersCombat.rideGuide(), 38, 53),
                slice("骑乘指挥棒 · 投弹、数值与排查", GuideChaptersCombat.rideGuide(), 53, 66),
                slice("饰品「大运」 · 规则与效果", GuideChaptersCombat.daYunGuide(), 0, 10),
                slice("饰品「大运」 · 配置与排错", GuideChaptersCombat.daYunGuide(), 10, 18),
                slice("贴身辅助 · 功能总览", GuideChaptersCombat.aidGuide(), 0, 11),
                slice("贴身辅助 · 投喂与喂水", GuideChaptersCombat.aidGuide(), 11, 22),
                GuideChaptersCombat.tacticsGuide(),
                GuideChaptersCombat.weaponEquipGuide(),
                GuideChaptersSystem.memoryGuide(),
                GuideChaptersSystem.dialogueGuide(),
                GuideChaptersSystem.perceptionAffectGuide(),
                GuideChaptersSystem.toolsGuide(),
                GuideChaptersSystem.voiceGuide(),
                GuideChaptersSystem.configPanelGuide(),
                slice("专业术语解释 · 基础与建造", GuideChaptersSystem.termsGuide(), 0, 19),
                slice("专业术语解释 · 生存与飞行", GuideChaptersSystem.termsGuide(), 19, 30),
                slice("专业术语解释 · 配置与速度", GuideChaptersSystem.termsGuide(), 30, 38),
                GuideChaptersSystem.potionRules(),
                GuideChaptersSystem.buildInternals(),
                GuideChaptersSystem.costRules(),
                GuideChaptersBasics.specialThanks(),
        };

    }

    // ================= 〇、功能总览（一键跳转配置） =================

    // ================= 一、开发者寄语 =================

    // ================= 二、这是什么 =================

    // ================= 三、配置 LLM（最重要的一步） =================

    // ================= 四、手册使用指南 =================

    // ================= 五、建造系统教程 =================

    // ================= 五之二、指标石（独立章节） =================

    // ================= 五之三、压缩盒（v1.2.2 实测六百一十六，六百一十七 补外观与界面，六百一十八 补交互与食物/附魔） =================

    // ================= 六、女仆管理与区块 =================

    // ================= 七、挖矿系统教程 =================

    // ================= 八、伐木系统教程 =================

    // ================= 九、烧制与酿造 =================

    // ================= 十、农场与杂项 =================

    // ================= 十一、排班表（v1.1.0） =================

    // ================= 十二、战斗与自保 =================

    // ================= 十二b、空袭与飞行作战 =================

    // ================= 十三、被动技能 =================

    // ================= 十四、女仆自动复活 =================

    // ================= 十五、床铺互通（女仆床 ↔ 玩家床） =================

    // ================= 十六、贴身辅助（投喂/治疗/盾牌） =================

    // ================= 十五、单兵战术 =================

    // ================= 十六、武器与工具切换机制 =================

    // ================= 十七、AI 记忆系统 =================

    // ================= 十八、主动对话 / 工作播报 / 自主决策 =================

    // ================= 十九、感知与情绪 =================

    // ================= 二十、AI 工具大全 =================

    // ================= 二十一、语音系统 =================

    // ================= 二十二、模组详细配置面板导航 =================

    // ================= 二十三、专业术语解释 =================

    // ================= 二十四、细则：药水与投喂规则 =================

    // ================= 二十五、细则：建造运作逻辑 =================

    // ================= 二十六、细则：记忆与对话成本控制 =================

    /**
     * 实测四百二十四：章节标题 → 模组详细配置跳转目标（按标题关键词匹配，容忍标点差异）。
     * 目标格式 "GROUP" / "GROUP:SECTION" / "GROUP:SECTION:行标签"；null = 该章不加链接。
     * 章节正文末尾由 GuideScreen 自动追加一行可点击的「配置入口」。
     */
    private static final String[][] CFG_LINK_RULES = {
            {"建造", "WORK:BUILD"},
            {"挖矿", "WORK:MINE"},
            {"空袭", "COMBAT:AIR_RAID:空袭·总开关（飞行作战）"},
            {"仿创造飞行", "MOVE:FLIGHT_FOLLOW"},
            {"飞行作战", "COMBAT:AIR_RAID"},
            {"伐木", "WORK:WOOD"},
            {"烧制", "WORK:COOK_BREW"},
            {"农场", "WORK:FARM"},
            {"钓鱼", "WORK:FISHING"},
            {"女仆管理", "COMBAT:DANGER"},
            {"排班表", "WORK:SCHEDULE"},
            {"自保与保命", "COMBAT:SELF_PRESERVE"},
            {"主动参战", "COMBAT:AUTO_COMBAT"},
            {"战斗与自保", "COMBAT:SELF_PRESERVE"},
            {"被动技能", "SURVIVAL"},
            {"自动复活", "SURVIVAL:REVIVE:女仆自动复活"},
            {"床铺互通", "SYSTEM:UTILITY:床铺互通"},
            {"贴身辅助", "COMBAT:AID"},
            {"药水与投喂", "COMBAT:AID"},
            {"单兵战术", "COMBAT:TACTICS"},
            {"武器与工具", "COMBAT:AUTO_COMBAT"},
            {"AI 记忆", "AI:MEMORY"},
            {"成本控制", "AI:MEMORY"},
            {"主动对话", "AI:DIALOGUE"},
            {"感知与情绪", "AI:PERCEPTION"},
            {"AI 工具", "AI:AITOOLS"},
            {"语音系统", "UI:VOICE"},
            {"压缩盒", "SYSTEM:COMPRESSION_BOX"},
            // v1.3.9【巡逻航图】：它的配置行落在「移动与行为 → 扫帚模式」页内的「巡逻航迹」那一段，
            //  所以直接跳到那一行（带行标签）。**必须排在「扫帚」那条前面**——本章标题里含
            //  「扫帚女仆」，否则会被扫帚那条先命中、只跳到扫帚页顶。
            {"巡逻航图", "MOVE:BROOM:巡逻航迹·总开关"},
            // v1.3.6 实测六百六十一：扫帚模式（配置板块此时在「移动与行为」下）
            {"扫帚", "MOVE:BROOM"},
            // 【实测六百七十五】武装拴绳（自己的板块；带行标签 = 打开就落在那一行上）。
            //  注意顺序：本表按顺序取**第一个**命中的关键词，所以这条要放在不会误伤的后面
            //  （它的标题里不含"扫帚/战斗"等词，放在这里也安全）。
            {"武装拴绳", "MOVE:TETHER:武装拴绳·总开关"},
            // v1.5.398 实测八百一十三：「饰品「大运」」章此前**没有**配置入口（八百零四 加这章时
            //  漏了），补上——它的参数页在「移动与行为 → 饰品「大运」」。
            {"大运", "MOVE:DA_YUN"},
    };

    /** 实测四百二十四：取该章的配置跳转目标；null = 不附加链接。 */
    public static String configLinkFor(String title) {
        if (title == null) {
            return null;
        }
        for (String[] rule : CFG_LINK_RULES) {
            if (title.contains(rule[0])) {
                return rule[1];
            }
        }
        return null;
    }

}
