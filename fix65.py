# -*- coding: utf-8 -*-
# 实测六十五修复脚本：
# 1. 主人侧触发排除无来源实体的环境伤害（摔落/岩浆/火烧/饥饿不再触发全队参战）
# 2. 女仆侧触发 Monster -> Enemy（覆盖史莱姆/岩浆怪等非 Monster 敌对生物）
# 3. 一键集合跳过在家模式女仆（跨维度集合后回不了家）
import io

def patch(path, old, new, expect):
    t = io.open(path, encoding='utf-8').read()
    c = t.count(old)
    if c != expect:
        print('SKIP %s: found %d, expect %d' % (path, c, expect))
        return
    t = t.replace(old, new)
    io.open(path, 'w', encoding='utf-8').write(t)
    print('OK %s: %d replaced' % (path, c))

AC = r'promaid_src\com\maidsmart\combat\AutoCombatSwitch.java'

# 1. 三处主人侧受害者监听（Hurt/Attack/Damage）排除无来源伤害
old1 = 'if (event.getSource() == null || event.getSource().m_7639_() == player) {'
new1 = ('if (event.getSource() == null || event.getSource().m_7639_() == null\n'
        '                || event.getSource().m_7639_() == player) {')
patch(AC, old1, new1, 3)

# 2. 女仆侧触发 Monster -> Enemy（接口名不被 SRG 重命名，直接可用）
old2 = 'return source != null && source.m_7639_() instanceof net.minecraft.world.entity.monster.Monster;'
new2 = ('// v1.1.0 实测六十五：Monster -> Enemy——史莱姆/岩浆怪等敌对生物不实现\n'
        '        // Monster 但实现 Enemy 接口，旧判定被它们打了不参战\n'
        '        return source != null && source.m_7639_() instanceof net.minecraft.world.entity.monster.Enemy;')
patch(AC, old2, new2, 1)

# 3. 一键集合跳过在家模式女仆（与跨维度跟随同口径——在家模式被跨维度集合会回不了家）
SN = r'promaid_src\com\maidsmart\schedule\ScheduleNetworking.java'
old3 = ('// v1.1.0 实测六十二（自查修复）：坐着的女仆不集合——建造强制\n'
        '                        // 坐下 = 玩家明确要她留在原地（与跨维度跟随同口径），拽走会破坏工地\n'
        '                        if (m.isMaidInSittingPose() || m.m_20159_()) {')
new3 = ('// v1.1.0 实测六十二（自查修复）：坐着的女仆不集合——建造强制\n'
        '                        // 坐下 = 玩家明确要她留在原地（与跨维度跟随同口径），拽走会破坏工地\n'
        '                        // v1.1.0 实测六十五：在家模式的女仆也不集合——跨维度集合后\n'
        '                        // 她困在错误维度回不了家（与跟随逻辑同口径）\n'
        '                        if (m.isMaidInSittingPose() || m.m_20159_() || m.isHomeModeEnable()) {')
patch(SN, old3, new3, 1)
print('ALL DONE')
