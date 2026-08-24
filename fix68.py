# -*- coding: utf-8 -*-
# 实测六十八：自主战斗两症状同根修复
# 1. hasWeaponForTask 异常兜底从"整个方法 return true"改为逐物品安全判定——
#    任一任务 isWeapon 抛异常不再让该任务无凭无据进候选池（斧子女仆被切三叉戟的根因）
# 2. hasThreatNearby / nearestThreatDist 的 Monster -> Enemy（与参战判定同口径，
#    史莱姆等敌对生物也算威胁；否则还原/参战反复横跳）
import io

AC = r'promaid_src\com\maidsmart\combat\AutoCombatSwitch.java'
t = io.open(AC, encoding='utf-8').read()

# 1. hasWeaponForTask 整体重写
old = ('    private static boolean hasWeaponForTask(EntityMaid maid,\n'
       '                                            com.github.tartaricacid.touhoulittlemaid.api.task.IAttackTask task) {\n'
       '        // 1. 任务自己的判定（IAttackTask.isWeapon）\n'
       '        try {\n'
       '            ItemStack main = maid.m_21205_();\n'
       '            if (!main.m_41619_() && task.isWeapon(maid, main)) {\n'
       '                return true;\n'
       '            }\n'
       '            IItemHandler inv = maid.getMaidInv();\n'
       '            for (int i = 0; i < inv.getSlots(); i++) {\n'
       '                ItemStack s = inv.getStackInSlot(i);\n'
       '                if (!s.m_41619_() && task.isWeapon(maid, s)) {\n'
       '                    return true;\n'
       '                }\n'
       '            }\n'
       '            // v1.1.0 实测六十七：移除"全 false = 不限武器 → 视为可参战"的保守放行——\n'
       '            // 它会让完全没有攻击物品的女仆也进战斗池，与「空手不参战」直接矛盾\n'
       '            return false;\n'
       '        } catch (Throwable ignored) {\n'
       '        }\n'
       '        return true; // 判定异常时保守放行（别让模组任务因此选不上）\n'
       '    }')
new = ('    private static boolean hasWeaponForTask(EntityMaid maid,\n'
       '                                            com.github.tartaricacid.touhoulittlemaid.api.task.IAttackTask task) {\n'
       '        // v1.1.0 实测六十八（用户："拿斧子的女仆被切到三叉戟模式无法攻击"）：\n'
       '        // 旧版异常兜底是【整个方法级】的——任何物品的 isWeapon 抛异常就让整个\n'
       '        // 方法 return true，该任务无凭无据进候选池（三叉戟任务就是这样混进去的，\n'
       '        // 没三叉戟的女仆切过去根本无法攻击，怪杀不掉威胁不消失也永远不还原）。\n'
       '        // 改为【逐物品】安全判定：单件物品判定异常只跳过该件，绝不放行整个任务。\n'
       '        try {\n'
       '            ItemStack main = maid.m_21205_();\n'
       '            if (!main.m_41619_() && isWeaponSafe(task, maid, main)) {\n'
       '                return true;\n'
       '            }\n'
       '            IItemHandler inv = maid.getMaidInv();\n'
       '            for (int i = 0; i < inv.getSlots(); i++) {\n'
       '                ItemStack s = inv.getStackInSlot(i);\n'
       '                if (!s.m_41619_() && isWeaponSafe(task, maid, s)) {\n'
       '                    return true;\n'
       '                }\n'
       '            }\n'
       '            return false;\n'
       '        } catch (Throwable ignored) {\n'
       '            return false; // 背包遍历本身异常 → 视为无武器（与「空手不参战」同口径）\n'
       '        }\n'
       '    }\n'
       '\n'
       '    /** 单件物品的 isWeapon 安全判定——模组任务实现抛异常只算这件不匹配 */\n'
       '    private static boolean isWeaponSafe(com.github.tartaricacid.touhoulittlemaid.api.task.IAttackTask task,\n'
       '                                        EntityMaid maid, ItemStack s) {\n'
       '        try {\n'
       '            return task.isWeapon(maid, s);\n'
       '        } catch (Throwable ignored) {\n'
       '            return false;\n'
       '        }\n'
       '    }')
print('hasWeapon hits:', t.count(old))
t = t.replace(old, new)

# 2. 威胁判定 Monster -> Enemy（两处）
old2 = ('        for (net.minecraft.world.entity.monster.Monster e\n'
        '                : maid.m_9236_().m_45976_(net.minecraft.world.entity.monster.Monster.class,\n'
        '                maid.m_20191_().m_82400_(r))) {')
new2 = ('        // v1.1.0 实测六十八：Monster -> Enemy（与参战判定同口径——史莱姆等\n'
        '        // 敌对生物也算威胁，否则还原后立刻被再次触发、反复横跳）\n'
        '        for (net.minecraft.world.entity.monster.Enemy e\n'
        '                : maid.m_9236_().m_45976_(net.minecraft.world.entity.monster.Enemy.class,\n'
        '                maid.m_20191_().m_82400_(r))) {')
print('hasThreat hits:', t.count(old2))
t = t.replace(old2, new2)

old3 = ('            for (net.minecraft.world.entity.monster.Monster e : maid.m_9236_().m_45976_(\n'
        '                    net.minecraft.world.entity.monster.Monster.class,\n'
        '                    maid.m_20191_().m_82400_(24.0))) {')
new3 = ('            // v1.1.0 实测六十八：Monster -> Enemy（同 hasThreatNearby 口径）\n'
        '            for (net.minecraft.world.entity.monster.Enemy e : maid.m_9236_().m_45976_(\n'
        '                    net.minecraft.world.entity.monster.Enemy.class,\n'
        '                    maid.m_20191_().m_82400_(24.0))) {')
print('nearestDist hits:', t.count(old3))
t = t.replace(old3, new3)

io.open(AC, 'w', encoding='utf-8').write(t)
print('ALL DONE')
