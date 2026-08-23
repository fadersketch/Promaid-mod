# -*- coding: utf-8 -*-
# v1.1.0 实测五十：排班表图标改为原版旗帜（banner）样式 16x16 材质
# 参考 minecraft:textures/block/oyzeizmi_16x16 之外的旗帜布局：
#   旗帜悬挂形 = 顶边旗杆横杆 + 左侧立杆 + 垂下的旗面（底部锯齿）
from PIL import Image
import os

F = 16
img = Image.new('RGBA', (F, F), (0, 0, 0, 0))
px = img.load()

# 原版旗帜调色
DARK_WOOD = (61, 40, 22, 255)     # 旗杆深木色
WOOD = (103, 68, 37, 255)         # 旗杆木色
WOOD_HI = (132, 90, 48, 255)      # 旗杆高光
GRAY_D = (93, 93, 93, 255)        # 立杆/铁灰深
GRAY = (125, 125, 125, 255)       # 立杆/铁灰
GRAY_HI = (167, 167, 167, 255)    # 立杆高光
RED_D = (111, 0, 0, 255)          # 旗面暗红
RED = (158, 3, 3, 255)            # 旗面红（接近原版旗帜红）
RED_HI = (199, 22, 22, 255)       # 旗面亮红

def rect(x0, y0, x1, y1, c):
    for y in range(y0, y1 + 1):
        for x in range(x0, x1 + 1):
            px[x, y] = c

# ---- 1. 顶部横杆（悬挂横梁，x=1..14, y=1..2），铁灰渐变 ----
for x in range(1, 15):
    px[x, 1] = GRAY_HI
    px[x, 2] = GRAY
px[1, 1] = GRAY; px[1, 2] = GRAY_D          # 左端接立杆处压暗
px[14, 2] = GRAY_D                          # 右端收暗

# ---- 2. 左侧立杆（木杆，x=1..2, y=1..13），底部小基座球 ----
for y in range(1, 14):
    px[1, y] = DARK_WOOD
    px[2, y] = WOOD
px[1, 1] = WOOD_HI; px[2, 1] = DARK_WOOD     # 顶左上角与横杆衔接
px[2, 12] = WOOD_HI                           # 杆身高光点
px[1, 13] = GRAY_D; px[2, 13] = GRAY          # 底端金属箍

# ---- 3. 旗面（红，x=4..14），y=3 起垂到 y=13，底部两齿锯齿 ----
# 主旗面（右侧到 x=14）
rect(4, 3, 14, 11, RED)
px[4, 3] = RED_D                              # 贴杆侧阴影
for y in range(3, 12):
    px[4, y] = RED_D                          # 左缘暗
    px[14, y] = RED_HI                        # 右缘亮
px[14, 3] = RED                               # 顶行右角收敛
# 旗面中央太阳标记（旗帜图案 circle 感）：3x3 暗红点阵
px[9, 6] = RED_D; px[8, 7] = RED_D; px[9, 7] = RED_D; px[10, 7] = RED_D; px[9, 8] = RED_D

# ---- 4. 底部锯齿（两齿，旗帜轻摆形）----
# 齿 A：x=4..8 垂到 y=13
rect(4, 12, 8, 13, RED)
for y in (12, 13):
    px[4, y] = RED_D
px[8, 13] = RED_HI
# 齿间缺口：x=9..10
# 齿 B：x=11..14 垂到 y=13
rect(11, 12, 14, 13, RED)
px[11, 13] = RED_D
px[14, 12] = RED_HI; px[14, 13] = RED_HI

out = os.path.join(os.path.dirname(os.path.abspath(__file__)),
                   'promaid_src', 'assets', 'maid_smart', 'textures', 'item', 'schedule_book.png')
img.save(out)
print('saved:', out, os.path.getsize(out), 'bytes')

# ---- 验证：输出像素图，肉眼核对 ----
from collections import Counter
c = Counter(img.getdata())
print('colors used:')
for col, n in c.most_common():
    print('  ', col, n)
