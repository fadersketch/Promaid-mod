# -*- coding: utf-8 -*-
# 实测六十五文案同步：配置面板 tooltip + 手册（"任意来源" -> "有来源的攻击"）
import io

f = r'promaid_src\com\maidsmart\config\PromaidConfigScreen.java'
t = io.open(f, encoding='utf-8').read()
old = '主人被攻击（任意来源）或主人攻击了别的生物时'
new = '主人被有来源的攻击（怪/玩家/弹射物；摔落岩浆等环境伤害不算）或主人攻击了别的生物时'
print('screen hits:', t.count(old))
t = t.replace(old, new)
io.open(f, 'w', encoding='utf-8').write(t)

g = r'promaid_src\com\maidsmart\guide\GuideContent.java'
t = io.open(g, encoding='utf-8').read()
old2 = '你被攻击（任意来源——怪物/玩家/弹射物都算）'
print('guide hits:', t.count(old2))
t = t.replace(old2, '你被有来源的攻击打（怪物/玩家/弹射物；摔落、岩浆等环境伤害不算，免得女仆被误叫参战）')
io.open(g, 'w', encoding='utf-8').write(t)
print('done')
