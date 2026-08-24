# -*- coding: utf-8 -*-
# 实测六十九补遗：GuideContent（§ 是字面 \u00a7 转义序列）+ changelog.txt
import io
import sys

BASE = r"C:\Users\Sketch\.zcode\workspace\default\promaid-mod"
sys.stdout.reconfigure(encoding="utf-8")


def rd(p):
    return io.open(BASE + "\\" + p, encoding="utf-8").read()


def wr(p, s):
    io.open(BASE + "\\" + p, "w", encoding="utf-8").write(s)


def rep(s, old, new, tag, path, cnt=1):
    n = s.count(old)
    if n != cnt:
        print("FAIL[%s/%s] hits=%d expect=%d : %s" % (path, tag, n, cnt, ascii(old[:90])))
        sys.exit(1)
    return s.replace(old, new)


GC = r"promaid_src\com\maidsmart\guide\GuideContent.java"
g = rd(GC)

S = "\\u00a7"  # 源码里的字面转义序列

old_mine = ('                "\xb7 ' + S + 'e废石保留量' + S
            + 'r：背包里圆石等留多少格，防塞满。",\n')
new_mine = (old_mine
            + '                "\xb7 ' + S + 'e发呆看门狗' + S + 'r（默认开，45 秒）：挖矿时长时间既没挖到东西也没挪窝（原地发呆/状态卡死）"\n'
            + '                        + "会自动整体重置她的挖矿状态再重新找矿——不用收回魂符重放救她；判定时长在配置面板「mine.stuckResetSeconds」。",\n')
g = rep(g, old_mine, new_mine, "guide-mine", GC)

old_wood = ('                "\xb7 ' + S + 'e废石保留量' + S
            + 'r：砍树途中挖穿泥土/石头产生的废石限量保留，防背包塞满。",\n')
new_wood = (old_wood
            + '                "\xb7 ' + S + 'e发呆看门狗' + S + 'r（默认开，30 秒）：伐木时长时间既没砍到木头也没挪窝（典型如站进树洞里对着头顶树干发呆）"\n'
            + '                        + "会自动整体重置她的伐木状态再重新找树——不用收回魂符重放救她；判定时长在配置面板「wood.stuckResetSeconds」。",\n')
g = rep(g, old_wood, new_wood, "guide-wood", GC)

wr(GC, g)
print("guide OK")

CL = r"promaid_src\assets\promaid\guide\changelog.txt"
c = rd(CL)
entry = ("[05:30:0] v1.1.0 实测六十九（用户反馈：伐木站坑发呆仍要收回魂符重放才恢复）："
         "伐木/挖矿新增「发呆看门狗」——连续 N 秒（wood 默认 30/mine 默认 45，配置面板可调）既没挖掉任何方块、"
         "位置也没挪动时，自动整体重置该女仆的全部行为状态（锚点/扫描缓存/弃置排除表/目标/连锁队列/走路记忆），"
         "等效收回魂符再放下去，不再需要玩家手动救；走路赶路与垫方块都算进展不会误触发，重置时气泡说一声（限频）。"
         "顺带修复两处会喂出「发呆」的漏洞：关闭透视时被挡目标从「丢弃但不记录」改为进 30 秒短排（不再无限"
         "「选中→看不见→丢弃」循环）；伐木抬头兜底不再回选已被硬挡路弃置的木材。开关与时长都在配置面板挖矿/伐木小节。")
if entry[:24] not in c:
    lines = c.split("\n")
    lines.insert(1, entry)
    wr(CL, "\n".join(lines))
    print("changelog OK")
else:
    print("changelog already patched")
print("ALL DONE")
