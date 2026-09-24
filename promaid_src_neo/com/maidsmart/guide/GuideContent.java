package com.maidsmart.guide;

/**
 * 「详细介绍」内容定义（v1.5.252h 收官；v1.5.304 全面重写为保姆式教学）。
 *
 * 与 Promaid 手册（BlueprintBookScreen）的「建筑页面」同构：
 * 章节目录（分页按钮列表）→ 点章节 → 正文分页阅读（< 上一页 / 下一页 >）。
 *
 * v1.5.304 重写原则（反馈："重新整改 + 更详细 + 实操例子，手把手保姆式教学；
 * 更新日志直接沿袭之前的日志更新就可以了"）：
 * - 每章：先一句话讲清"这是什么"，再给分步操作，关键步骤附【举例】；
 * - 事实全部对齐当前版本（v1.5.303）：投喂直接喂（不再塞背包）、手持食物可喂、
 *   被动技能独立成栏、红石机器只剩自动熔炉组、门落地修复、阈值 4-20 真实生效、
 *   区块坐标显示、名单页跳转、进度条位置、输出语言选项选择等；
 * - 更新日志章节保持原样（读 assets/promaid/guide/changelog.txt）。
 */
public final class GuideContent {
    private GuideContent() {
    }

    /** 章节：标题 + 正文段落（每段自动换行渲染，段间空一行） */
    public static final class Chapter {
        public final String title;
        public final String[] paras;
        /** true = 更新日志（按行分页渲染，不从 paras 取） */
        public final boolean changelog;

        public Chapter(String title, String... paras) {
            this.title = title;
            this.paras = paras;
            this.changelog = false;
        }

        Chapter(String title, boolean changelog) {
            this.title = title;
            this.paras = new String[0];
            this.changelog = changelog;
        }
    }

    /** 更新日志章节（内容从资源文件按行读取） */
    public static Chapter changelogChapter() {
        return new Chapter("更新日志", true);
    }

    /** 全部章节（更新日志放最后） */
    public static Chapter[] chapters() {
        return new Chapter[]{
                GuideChaptersBasics.foreword(),
                GuideChaptersBasics.featureOverviewGuide(),
                GuideChaptersBasics.intro(),
                GuideChaptersBasics.llmSetup(),
                GuideChaptersBasics.bookGuide(),
                GuideChaptersBasics.buildGuide(),
                GuideChaptersBasics.indexStoneGuide(),
                GuideChaptersBasics.compressionBoxGuide(),
                GuideChaptersWork.maidManageGuide(),
                GuideChaptersWork.mineGuide(),
                GuideChaptersWork.woodGuide(),
                GuideChaptersWork.cookGuide(),
                GuideChaptersWork.farmMiscGuide(),
                GuideChaptersWork.scheduleGuide(),
                GuideChaptersCombat.combatGuide(),
                GuideChaptersFlight.flightGuide(),
                GuideChaptersFlight.passiveGuide(),
                GuideChaptersFlight.autoResurrectGuide(),
                GuideChaptersFlight.bedInteropGuide(),
                GuideChaptersFlight.broomGuide(),
                GuideChaptersCombat.aidGuide(),
                GuideChaptersCombat.tacticsGuide(),
                GuideChaptersCombat.weaponEquipGuide(),
                GuideChaptersSystem.memoryGuide(),
                GuideChaptersSystem.dialogueGuide(),
                GuideChaptersSystem.perceptionAffectGuide(),
                GuideChaptersSystem.toolsGuide(),
                GuideChaptersSystem.voiceGuide(),
                GuideChaptersSystem.configPanelGuide(),
                GuideChaptersSystem.termsGuide(),
                GuideChaptersSystem.potionRules(),
                GuideChaptersSystem.buildInternals(),
                GuideChaptersSystem.costRules(),
                changelogChapter(),
        };
    }

    // ================= 〇、功能总览（一键跳转配置） =================

    // ================= 一、开发者寄语 =================

    // ================= 二、这是什么 =================

    // ================= 三、配置 LLM（最重要的一步） =================

    // ================= 四、手册使用指南 =================

    // ================= 五、建造系统教程 =================

    // ================= 五之二、指标石（独立章节） =================

    // ================= 五之三、压缩盒（v1.2.2 实测六百一十六，六百一十七 补外观与界面） =================

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
            {"空袭", "COMBAT:AIR_RAID"},
            {"飞行作战", "COMBAT:AUTO_COMBAT"},
            {"伐木", "WORK:WOOD"},
            {"烧制", "WORK:COOK_BREW"},
            {"农场", "WORK:FARM"},
            {"女仆管理", "SURVIVAL:SAFETY"},
            {"排班表", "WORK:SCHEDULE"},
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
            // v1.3.6 实测六百六十一：扫帚模式（配置板块此时在「移动与行为」下）
            {"扫帚", "MOVE:BROOM"},
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
