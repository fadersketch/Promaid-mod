# -*- coding: utf-8 -*-
# 实测六十七：补 COMBAT_UNARMED_SKIP 字段声明 + AutoCombatSwitch 行为 + 面板行 + 手册
import io

# 1. 字段声明
f = r'promaid_src\com\maidsmart\config\MaidSmartConfig.java'
t = io.open(f, encoding='utf-8').read()
old = '    public static final ForgeConfigSpec.IntValue COMBAT_REVERSE_COOLDOWN_TICKS;'
new = old + '\n    // v1.1.0 实测六十七：空手（无任何攻击物品）不参战\n    public static final ForgeConfigSpec.BooleanValue COMBAT_UNARMED_SKIP;'
if 'COMBAT_UNARMED_SKIP;' not in t:
    print('field hits:', t.count(old))
    t = t.replace(old, new)
    io.open(f, 'w', encoding='utf-8').write(t)
else:
    print('field already present')

# 2. pickCombatTask 兜底改为可配置
f = r'promaid_src\com\maidsmart\combat\AutoCombatSwitch.java'
t = io.open(f, encoding='utf-8').read()
old = ('        // 全都匹配不上 → 近战兜底（空手也上）\n'
       '        return TaskManager.findTask(ResourceLocation.parse("touhou_little_maid:attack")).orElse(null);')
new = ('        // v1.1.0 实测六十七（用户："手上完全没有攻击性物品的女仆，就不应该触发自主\n'
       '        // 战斗，应该维持原任务"）：两池全空 = 主手/背包没有任何攻击任务认的武器\n'
       '        // → 不参战（返回 null，tryEngageMaid 跳过、维持原任务）；\n'
       '        // 开关关闭时保留旧行为（空手近战兜底）\n'
       '        if (!MaidSmartConfig.COMBAT_UNARMED_SKIP.get()) {\n'
       '            return TaskManager.findTask(ResourceLocation.parse("touhou_little_maid:attack")).orElse(null);\n'
       '        }\n'
       '        return null;')
print('fallback hits:', t.count(old))
t = t.replace(old, new)

# 3. hasWeaponForTask 移除"全 false = 不限武器"放行（会让空手女仆进池）
old3 = ('            return true; // 任务对所有物品都返回 false = 不限武器（如部分模组任务）→ 视为可参战')
new3 = ('            // v1.1.0 实测六十七：移除"全 false = 不限武器 → 视为可参战"的保守放行——\n'
        '            // 它会让完全没有攻击物品的女仆也进战斗池，与「空手不参战」直接矛盾\n'
        '            return false;')
print('heuristic hits:', t.count(old3))
t = t.replace(old3, new3)
io.open(f, 'w', encoding='utf-8').write(t)

# 4. 类头文档同步
old4 = ' * - 候选池加权随机：模组任务权重 2.0、原版五件套（近战/弓/弩/三叉戟/弹幕）\n *   权重 1.0（模组武器普遍更强，降半权但不绝对排除）\n * - 全都匹配不上 → 近战（空手也上）'
new4 = ' * - 候选池加权随机：模组任务权重 2.0、原版五件套（近战/弓/弩/三叉戟/弹幕）\n *   权重 1.0（模组武器普遍更强，降半权但不绝对排除）\n * - 全都匹配不上（无任何攻击物品）→ 不参战维持原任务（实测六十七；\n *   「空手不参战」开关可关回旧的空手近战兜底）'
print('doc hits:', t.count(old4))
t = t.replace(old4, new4)
io.open(f, 'w', encoding='utf-8').write(t)

# 5. 配置面板 BoolRow（放在反向切换冷却之后）
f = r'promaid_src\com\maidsmart\config\PromaidConfigScreen.java'
t = io.open(f, encoding='utf-8').read()
old5 = '                s -> setInt(MaidSmartConfig.COMBAT_REVERSE_COOLDOWN_TICKS, s), "反向切换冷却（tick，默认 200=10 秒）：横跳被判定后进入冷却，期间不再换战术（保持当前战术硬打）——0 = 关闭反向抑制"));'
new5 = (old5 + '\n'
        '        // v1.1.0 实测六十七：空手不参战\n'
        '        this.rows.add(new BoolRow("空手不参战", MaidSmartConfig.COMBAT_UNARMED_SKIP.get(),\n'
        '                v -> MaidSmartConfig.COMBAT_UNARMED_SKIP.set(v), "空手不参战（默认开）：背包和主手都没有任何攻击任务认可的武器（剑/弓/枪械/模组武器等）的女仆，不触发自主战斗、维持原任务继续干活；关闭恢复旧行为（没有武器也空手近战兜底）"));')
print('screen hits:', t.count(old5))
t = t.replace(old5, new5)
io.open(f, 'w', encoding='utf-8').write(t)

# 6. 手册文案
f = r'promaid_src\com\maidsmart\guide\GuideContent.java'
t = io.open(f, encoding='utf-8').read()
old6 = '（模组武器普遍更强；全都匹配不上就空手近战）。'
new6 = '（模组武器普遍更强；全都匹配不上=背包没有任何攻击物品，就\u4e0d\u53c2\u6218、维持原任务——「空手不参战」开关可改回空手近战兜底）。'
print('guide hits:', t.count(old6))
t = t.replace(old6, new6)
io.open(f, 'w', encoding='utf-8').write(t)
print('ALL DONE')
