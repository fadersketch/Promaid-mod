# -*- coding: utf-8 -*-
# 实测六十八补丁：Enemy 是接口不能直接 getEntitiesOfClass——按 Entity 扫描再过滤
import io

AC = r'promaid_src\com\maidsmart\combat\AutoCombatSwitch.java'
t = io.open(AC, encoding='utf-8').read()

old = ('        // v1.1.0 实测六十八：Monster -> Enemy（与参战判定同口径——史莱姆等\n'
       '        // 敌对生物也算威胁，否则还原后立刻被再次触发、反复横跳）\n'
       '        for (net.minecraft.world.entity.monster.Enemy e\n'
       '                : maid.m_9236_().m_45976_(net.minecraft.world.entity.monster.Enemy.class,\n'
       '                maid.m_20191_().m_82400_(r))) {\n'
       '            if (e.m_6084_()) {\n'
       '                return true;\n'
       '            }\n'
       '        }\n'
       '        return false;')
new = ('        // v1.1.0 实测六十八：Monster -> Enemy（与参战判定同口径——史莱姆等\n'
       '        // 敌对生物也算威胁，否则还原后立刻被再次触发、反复横跳）。\n'
       '        // Enemy 是接口，getEntitiesOfClass 不收——按 Entity 扫描再过滤\n'
       '        for (net.minecraft.world.entity.Entity e : maid.m_9236_().m_45976_(\n'
       '                net.minecraft.world.entity.Entity.class, maid.m_20191_().m_82400_(r))) {\n'
       '            if (e instanceof net.minecraft.world.entity.monster.Enemy && e.m_6084_()) {\n'
       '                return true;\n'
       '            }\n'
       '        }\n'
       '        return false;')
print('hasThreat hits:', t.count(old))
t = t.replace(old, new)

old2 = ('            // v1.1.0 实测六十八：Monster -> Enemy（同 hasThreatNearby 口径）\n'
        '            for (net.minecraft.world.entity.monster.Enemy e : maid.m_9236_().m_45976_(\n'
        '                    net.minecraft.world.entity.monster.Enemy.class,\n'
        '                    maid.m_20191_().m_82400_(24.0))) {\n'
        '                if (!e.m_6084_()) {\n'
        '                    continue;\n'
        '                }\n'
        '                double d = maid.m_20238_(e.m_20182_());\n'
        '                if (best < 0 || d < best) {\n'
        '                    best = d;\n'
        '                }\n'
        '            }')
new2 = ('            // v1.1.0 实测六十八：Monster -> Enemy（同 hasThreatNearby 口径）\n'
        '            for (net.minecraft.world.entity.Entity e : maid.m_9236_().m_45976_(\n'
        '                    net.minecraft.world.entity.Entity.class,\n'
        '                    maid.m_20191_().m_82400_(24.0))) {\n'
        '                if (!(e instanceof net.minecraft.world.entity.monster.Enemy) || !e.m_6084_()) {\n'
        '                    continue;\n'
        '                }\n'
        '                double d = maid.m_20238_(e.m_20182_());\n'
        '                if (best < 0 || d < best) {\n'
        '                    best = d;\n'
        '                }\n'
        '            }')
print('nearestDist hits:', t.count(old2))
t = t.replace(old2, new2)

io.open(AC, 'w', encoding='utf-8').write(t)
print('ALL DONE')
