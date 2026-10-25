# -*- coding: utf-8 -*-
"""内置语音包【情绪化重制】的台词表 + 合成脚本。

背景：manifest 只存中文 key，旧版的日语台词没有落盘。本次按中文 key 重新撰写
日语台词，并按情境分情绪档（参考音频 A/B + 语速 + 温度/top_k）合成。

用法：
  python _voice_emo2.py check    只校验覆盖度/参考音频存在
  python _voice_emo2.py run     合成到 _voice_new2/
  python _voice_emo2.py run a.ogg b.ogg   只补合成这两个文件（新增台词时不必全量重跑）
"""
import io
import json
import os
import sys

# --- repo root resolution (this file lives in tools/<group>/) ---
def _find_repo_root(start):
    d = os.path.dirname(os.path.abspath(start))
    while True:
        if os.path.isdir(os.path.join(d, ".git")) or os.path.isfile(os.path.join(d, ".git")):
            return d
        parent = os.path.dirname(d)
        if parent == d:
            return os.path.dirname(os.path.abspath(start))
        d = parent
REPO = _find_repo_root(__file__)

ROOT = _find_repo_root(__file__)
MANIFEST = os.path.join(ROOT, 'promaid_src_neo', 'assets', 'promaid', 'voice', 'manifest.json')
OUT = os.path.join(ROOT, '_voice_new2')

REF_A = r'D:\GPT-SoVITS-v2pro-20250604-nvidia50\小酒狐语音\この命ご主人様のために捧げますね.wav'
TEXT_A = 'この命ご主人様のために捧げますね'
REF_B = (r'C:\Users\Sketch\Documents\TTS_Work\小酒狐语音包-精简版'
         r'\塞到GAG里的参考语音与文本\idle50.wav')
TEXT_B = 'どうしました私があまり可愛いからびっくりしちゃったんですか'

STYLES = {
    # 实测四百四十五：采样参数【必须保守】——用 ASR 客观对照试出来的结论：
    # temperature 1.15 / top_k 20 / top_p 0.95 这组会把短句合成糊掉
    # （「敵が来たよ、気をつけて！」被转写成「てちがちだよ 塩つけて」），
    # 而 speed 1.08 + 默认采样（temp 1.0 / k15 / p1.0）反而清清楚楚。
    # 所以情绪差异靠【参考音频 A/B + 语速】来做，采样一律用上一版验证过的默认值。
    # 战斗·紧张：短促、语速快一点
    'battle': dict(ref=REF_A, prompt=TEXT_A, speed=1.08, temp=1.0, top_k=15, top_p=1.0),
    # 关心·温柔：放慢、柔和
    'care':   dict(ref=REF_A, prompt=TEXT_A, speed=0.95, temp=1.0, top_k=15, top_p=1.0),
    # 俏皮·日常：用俏皮参考，稍快
    'cheer':  dict(ref=REF_B, prompt=TEXT_B, speed=1.05, temp=1.0, top_k=15, top_p=1.0),
    # 干活·汇报：干脆利落
    'work':   dict(ref=REF_A, prompt=TEXT_A, speed=1.03, temp=1.0, top_k=15, top_p=1.0),
    # 请求·为难：俏皮里带点撒娇
    'plead': dict(ref=REF_B, prompt=TEXT_B, speed=1.02, temp=1.0, top_k=15, top_p=1.0),
    # 骑乘指挥棒·俏皮：轻快应答（配对成功 / 出发）——用俏皮参考、稍快
    'cheer_ride': dict(ref=REF_B, prompt=TEXT_B, speed=1.05, temp=1.0, top_k=15, top_p=1.0),
    # 骑乘指挥棒·为难：各种「现在还不行」——俏皮参考、原速偏慢
    'plead_ride': dict(ref=REF_B, prompt=TEXT_B, speed=1.0, temp=1.0, top_k=15, top_p=1.0),
}

STYLE_OF = {
    'battle': ['enemy_near', 'enemy_clear', 'owner_hurt', 'owner_low_hp',
             'tool_takeoff',
             'tool_air_wait',
             'tool_roger',
               'shield_share', 'preserve_wary', 'preserve_no_block', 'preserve_no_escape',
               'preserve_return', 'preserve_lava_fall', 'preserve_drown', 'preserve_stuck',
               'preserve_lava_near', 'preserve_no_heal', 'clutch_snow', 'clutch_water',
               # v1.3.0 实测六百七十四：武装拴绳·新加气泡（battle 档）
             'leash_liftoff',
             'ready_dual',
               'totem_peer', 'totem_self', 'totem_give', 'attack_ok',
             'heat_away'],
    'care': ['aid_food', 'aid_sister_help', 'aid_ally_help', 'aid_undead_help', 'aid_potion_incoming', 'aid_potion_given',
             'aid_drink',
             'aid_bag_full', 'aid_eat_something', 'aid_honey_poison', 'aid_milk_debuff',
             'resurrect_back', 'death_teleport', 'owner_looking',
             'aid_golden_apple', 'aid_enchanted_apple',
             # v1.3.0 实测六百五十五：投喂蛋糕（玩家主动投喂的温柔场合）
             'cake_eat',
             # v1.3.0 实测六百六十九：武装拴绳·解除（「到站啦」——放下人时温柔叮嘱）
             # v1.3.0 实测六百七十四：武装拴绳·新加气泡（care 档）
             'leash_landed',
             'leash_off',
             # 骑乘指挥棒（实测七百一十四 / 七百一十六 / 七百一十八 新增，本批补语音）
             # 「好，我自己走～」= 解除时的日常应答，归关心档
             'ride_release',
             # R1110：被叫醒时的抱怨（半梦半醒、撒娇）归关心档
             'wake_complain'],
    # 骑乘指挥棒——俏皮档（配对/出发这类轻快应答）
    'cheer_ride': ['ride_pick', 'ride_ready', 'ride_dragon_ok', 'ride_dragon_here'],
    # 骑乘指挥棒——为难档（各种「现在还不行」的提醒）
    'plead_ride': ['ride_not_owner', 'ride_broom_mode', 'ride_fail',
             'ride_baton_first',
             'ride_no_copilot',
                   'ride_bad_mount', 'ride_furniture', 'ride_broom_rule',
                   'ride_mount_gone', 'ride_not_saddleable',
                   'ride_need_saddle', 'ride_mount_busy',
                   'ride_seat_fail', 'ride_occupied', 'ride_occupied_mine', 'ride_dragon_busy', 'ride_wreck', 'ride_no_seat', 'ride_too_small'],
    'plead': ['build_missing_material', 'bridge_no_block', 'brew_missing',
             'fish_no_water_area',
             'build_no_blocks',
              # 实测一千〇一十一：指标石缺料（文本已有、语音缺——同为为难档）
              'index_no_blocks',
              'mine_no_block_high', 'wood_no_block_high', 'wood_no_axe', 'no_food_left',
              'work_no_pickaxe', 'work_ridden',
              # v1.3.0 实测六百五十五：扫帚模式缺件（"还差…先待着不动"，为难）
              'broom_not_ready',
              # v1.3.0 实测六百六十九：武装拴绳·各种「现在还不行」的为难场合
              'leash_not_owner',
              'leash_busy',
              'leash_dismount_first',
              'leash_need_fly',
              'leash_need_mode',
              'leash_fail',
              'leash_denied',
              # 实测九百八十：卡死自愈的「够不到」（为难档，与 mine_no_block_high 同调）
              'work_stall_giveup',
              # R1110：烧制缺燃料/缺料时的求助（为难档）
              'cook_no_fuel', 'cook_no_food'],
    'work': ['build_done', 'build_substitute', 'build_skip_bedrock', 'build_skip_unloaded',
             'tool_land',
             'tool_go',
             'build_skip_noitem', 'build_skip_float', 'build_blocked', 'schedule_locked',
             'bridge_cant_climb', 'bridge_cant_place', 'work_distracted', 'mine_blocked_area', 'wood_blocked_area',
             'blocked_ore_report', 'found_something', 'ahead_of_you',
             'fallback_missing', 'fallback_something',
             # v1.3.0 实测六百七十四：武装拴绳·新加气泡（work 档）
             'leash_seatback',
             'mine_no_ore', 'farm_no_work',
             'mine_area_cleared', 'wood_area_cleared',
             # 实测九百八十二：目标路径上有岩浆（工作档）
             'lava_path_ahead'],
}

# 日语台词（按中文 key 的情境重写；sched_* 是排班闲聊，走俏皮档）
JP = {
    'heat_away.ogg': '熱っ、ちょっと離れるね〜',
    # -------- 骑乘指挥棒（实测七百一十四 / 七百一十六 / 七百一十八 新增，本批补语音）--------
    'ride_pick.ogg': 'うん、鞍を付けた乗り物をもう一匹指してね～',
    'ride_ready.ogg': 'しっかり掴まって、出発するよ～',
    'ride_release.ogg': 'うん、自分で歩くね～',
    'ride_not_owner.ogg': 'あなたは私のご主人様じゃないよ～',
    'ride_broom_mode.ogg': 'わたしは箒モードなの',
    'ride_fail.ogg': 'うまく乗れなかった……もう一回やってみる？',
    'ride_bad_mount.ogg': 'これは乗り物にならないよ～',
    'ride_furniture.ogg': 'これは家具で、乗り物じゃないよ～',
    'ride_broom_rule.ogg': '箒には自分の飛び方があるから、棒では扱わないよ～',
    'ride_mount_gone.ogg': 'もういなくなっちゃった……',
    'ride_not_saddleable.ogg': '鞍を付けられる乗り物じゃないよ～',
    'ride_need_saddle.ogg': '先に鞍を付けてから、私に繋いでね～',
    'ride_mount_busy.ogg': 'その背中にはもう誰かが乗ってるよ～',
    'ride_seat_fail.ogg': '鞍に乗れなかった……もう一回いい？',
    'ride_dragon_ok.ogg': 'この子の背中に乗ったよ、ついてくるって～',
    'ride_dragon_here.ogg': 'もうこの竜の背中にいるよ～（スニーク右クリックで降ろしてね）',
    'ride_occupied.ogg': 'この子にはもう別の女僕が乗ってるよ～',
    'ride_occupied_mine.ogg': 'この子にはもう私の妹が乗ってるよ～',
    'ride_dragon_busy.ogg': 'この竜の背中にはもう女僕がいるよ～',
    'ride_wreck.ogg': 'この乗り物、もうボロボロで動かないよ……',
    'ride_no_seat.ogg': 'この乗り物には座れる席がないよ～',
    'ride_too_small.ogg': 'まだ小さすぎるから、大きくなってから乗ろうね～',
    'ride_baton_first.ogg': '先に騎乗棒で乗り物を右クリックしてから、私を右クリックしてね〜',
    'ride_no_copilot.ogg': 'この車には座れる助手席が無いよ〜',
    'tool_takeoff.ogg': '飛ぶよ！',
    'tool_air_wait.ogg': '空で待機してるね',
    'tool_land.ogg': 'うん、降りるね',
    'tool_roger.ogg': '了解、任せて！',
    'tool_go.ogg': 'うん、すぐ行くね',
    'tool_follow.ogg': 'うん、ご主人様について行くね',
    'tool_here.ogg': 'ここは任せて',
    'aid_drink.ogg': 'ご主人様、喉渇いてるでしょ？飲み物持ってきたよ〜',
    'fish_no_water_area.ogg': '活動範囲に座れる水辺が無いの〜ご主人様、河童のコンパスで水辺のブロックを指定して、私の仕事エリアに設定してね',
    'build_no_blocks.ogg': '積むブロックがもう無いの…材料が見つからなかったら、先に止めるね',
    # 实测一千〇一十一：指标石缺料（气泡文本已有，只缺语音；语义是"给点方块，搭完才走"）
    'index_no_blocks.ogg': '積むブロックがもう無いの…ご主人様、ブロックを少し分けてくれる？終わるまで離れないよ',
    # -------- 武装拴绳（实测六百六十九：玩家要求把这些新文本也训练语音）--------
    'leash_on.ogg': 'さあ来て！ロープをしっかり掴んで、一緒に飛ぼう～',
    'leash_off.ogg': '着いたよ、足元に気をつけて降りてね～',
    'leash_not_owner.ogg': 'あなたはご主人様じゃないから、このロープは渡さないよ',
    'leash_busy.ogg': 'もう誰かがぶら下がってるよ～',
    'leash_dismount_first.ogg': 'ご主人様、先に乗り物から降りてからにしてね',
    'leash_need_fly.ogg': '飛んだらまた右クリックしてね。それまでロープを握ってて',
    'leash_need_mode.ogg': 'ほうきか空襲モードのときにロープを掴んでね～',
    'leash_fail.ogg': 'あれ、ロープが掛からなかった……もう一回やってみる？',
    'leash_denied.ogg': 'このロープはご主人様と、ぶら下がってる人の言うことだけ聞くよ～',
    'leash_follow.ogg': 'ご主人様について行くね。飛んだら、ぶら下がるから～',
    'leash_liftoff.ogg': '飛ぶよ！ロープをしっかり掴んでてね～',
    'leash_landed.ogg': 'ちょっと着地して休むね。ロープを握って、飛び立つのを待ってて～',
    'leash_seatback.ogg': '箒に戻って座ってね、このまま飛び続けるよ～',
    'leash_packup.ogg': 'ロープしまったよ～ここで待ってるね',
    'ready_dual.ogg': '空襲も箒も準備できたよ、いつでもいけるよ！',
    # -------- work --------
    'build_done.ogg': 'できたよ！見て見て、私が建てたの！',
    'build_substitute.ogg': '代わりので代用するね',
    'build_skip_bedrock.ogg': '岩盤みたいなので塞がってるから、飛ばすね',
    'build_skip_unloaded.ogg': 'チャンクが読み込まれないから、飛ばすね',
    'build_skip_noitem.ogg': '合うアイテムが無いから、飛ばすね',
    'build_skip_float.ogg': '浮いてる所は置けないから、飛ばすね',
    'build_blocked.ogg': '岩盤みたいなので塞がってるの',
    'schedule_locked.ogg': '今はシフト中、仕事もタスクもスケジュール管理なの',
    'bridge_cant_climb.ogg': '上に積めないの、頭の上が塞がってるから',
    'bridge_cant_place.ogg': 'ここには積めないみたい、先に降りるね',
    'work_distracted.ogg': 'あ、ぼーっとしてた…考え直して続けるね',
    'mine_blocked_area.ogg': '近くの鉱石が硬いブロックで塞がってるの',
    'wood_blocked_area.ogg': '近くの木材が硬いブロックで塞がってるの',
    'blocked_ore_report.ogg': '塞がってるの',
    'found_something.ogg': '見つけたの',
    'ahead_of_you.ogg': '前に',
    'fallback_missing.ogg': '足りないの',
    'fallback_something.ogg': '何かあるの',
    # -------- plead --------
    'build_missing_material.ogg': '材料が足りないの、あと',
    'bridge_no_block.ogg': '積むブロックがもう無いの…',
    'brew_missing.ogg': '醸造の材料が足りないの',
    'mine_no_block_high.ogg': '高い所の鉱石に手が届かないの',
    'wood_no_block_high.ogg': '高い所の木材に手が届かないの',
    'wood_no_axe.ogg': '斧が無いから、手で少しずつ削るね',
    'no_food_left.ogg': 'カバンに食べ物が無くなっちゃった、用意してくれる？',
    # -------- care --------
    'aid_food.ogg': 'ご主人様、お腹空いたでしょ？食べ物持ってきたよ',
    'aid_sister_help.ogg': '姉妹、頑張って、今助けるからね',
    # v1.3.0 实测八百：女仆支援友方（非女仆、非主人）的气泡「我来帮你！」此前只有文本没有语音,
    # 走关心档；manifest 条目排在 aid_sister_help 之后（contains），姐妹那条更长、优先命中
    'aid_ally_help.ogg': '助けるよ、ちょっと待っててね',
    # v1.3.0 实测八百一十三：亡灵友军专属支援气泡「我知道这在对你有用！」——同样是关心档，
    # 语气带一点「我懂你」的笃定（伤害药水/后门金苹果对亡灵才是真回血，见 MaidAidOwnerBehavior）
    'aid_undead_help.ogg': 'うん、これがあなたに効くって知ってるから',
    'aid_potion_incoming.ogg': 'ポーション持ってきたよ',
    'aid_potion_given.ogg': 'ご主人様、お薬は直接飲ませてあげますね～',
    'aid_bag_full.ogg': 'ご主人様のカバンが一杯で、渡せないの',
    'aid_eat_something.ogg': 'ご主人様、何か食べてね',
    'aid_honey_poison.ogg': '蜂蜜を飲んで、中毒を治して',
    'aid_milk_debuff.ogg': '牛乳を飲んで、悪い効果を消してね',
    'resurrect_back.ogg': 'ただいま！心配かけちゃったね',
    'death_teleport.ogg': 'すぐご主人様のところに行くね',
    'owner_looking.ogg': 'ご主人様、ずっと私を見てる…',
    # -------- battle --------
    'enemy_near.ogg': '敵が来たよ、気をつけて！',
    'enemy_clear.ogg': '敵は片付けたよ、もう安心して！',
    'flight_not_ready.ogg': '空戦の装備が足りないの、普通に戦うね',
    'owner_hurt.ogg': 'ご主人様、怪我したの！？',
    'owner_low_hp.ogg': 'ご主人様の体力が危ないよ、心配だよ…',
    'shield_share.ogg': '盾がもう壊れそう、これを使って！',
    'preserve_wary.ogg': 'まずい、慎重に立ち回るね',
    'preserve_no_block.ogg': '積むブロックがもう無いの',
    'preserve_no_escape.ogg': '手が尽きたよ、もう打つ手が無い…',
    'preserve_return.ogg': 'ご主人様のところに戻ったよ、少し休むね',
    'preserve_lava_fall.ogg': '溶岩に落ちた！積み上げて出るね',
    'preserve_drown.ogg': '溺れた！浮上するね',
    'preserve_stuck.ogg': '挟まっちゃった、息が出来ないよ',
    'preserve_lava_near.ogg': '近くに溶岩、避けて通るね',
    'preserve_no_heal.ogg': '回復アイテムが無いの、ご主人様の所に下がるね',
    'clutch_snow.ogg': '着地、雪！',
    'clutch_water.ogg': '着地、水！',
    'totem_peer.ogg': '仲間のトーテムが助けてくれたよ',
    'totem_self.ogg': 'トーテムが私を救ってくれたの',
    'totem_give.ogg': '私のトーテム、ご主人様にあげる',
    'attack_ok.ogg': '了解、倒してくるね！',
    # -------- cheer：日常/钓鱼/天气/撒娇 --------
    'fish_no_water.ogg': '近くに釣りができる水が見つからないの',
    'fish_no_chair.ogg': '椅子が無いから、自分で座布団持ってきたよ',
    'fish_no_water_here.ogg': 'ここに水が無いから、水辺を探して座って釣るね',
    'fish_path_blocked.ogg': '水辺までの道が通れないの、諦めるね',
    'weather_change.ogg': '天気が変わったね',
    'hug_01.ogg': '抱っこ！あったかい〜',
    'hug_02.ogg': 'ご主人様の腕の中が、一番安心するの〜',
    'hug_03.ogg': 'えへへ、抱っこされると動きたくなくなっちゃう〜',
    'pat_01.ogg': 'えへへ、気持ちいい〜',
    'pat_02.ogg': '頭を撫でられると、また元気が出るの！',
    'pat_03.ogg': 'ご主人様になでなでされるのが、一番好き〜',
    # -------- 补漏（实测四百四十七：WorkStatusReporter 规则气泡 + 金苹果投喂） --------
    'mine_area_cleared.ogg': 'この辺りにはもう目当ての鉱石がないみたい、しばらくここらで動いてるね～',
    'wood_area_cleared.ogg': 'この辺りにはもう伐れる木がないみたい、しばらくここらで動いてるね～',
    # 实测九百八十：卡死自愈（这块够不到 → 换个地方找）
    'work_stall_giveup.ogg': 'ここは手が届かないの、ちょっと別の場所を探してくるね～',
    # 实测九百八十二：去目标的路上有岩浆
    'lava_path_ahead.ogg': '目標までの道にマグマがあるの、ちょっと回り道するね～',
    'mine_no_ore.ogg': '近くに掘る価値のある鉱石が無いの',
    'farm_no_work.ogg': '近くに手入れする畑はもう無いの',
    'work_no_pickaxe.ogg': 'ツルハシが無いから、掘れないの…ツルハシをちょうだい',
    'work_ridden.ogg': '誰かに乗られてて、動けないの',
    'aid_golden_apple.ogg': 'ご主人様、金のリンゴをあげる！',
    'aid_enchanted_apple.ogg': 'ご主人様、エンチャントされた金のリンゴをあげる！',
    # -------- 补漏（实测六百五十五：投喂蛋糕 + 扫帚模式缺件） --------
    'cake_eat.ogg': 'ケーキ、すごくおいしい…ご主人様の気持ち、ちゃんと届いたよ〜',
    'broom_not_ready.ogg': '箒モード、まだ足りない物があるみたい…ちょっと待っててね',
    # -------- 排班闲聊 49 条 --------
    'sched_01.ogg': 'あら、ご主人様だ——今お仕事中ですよ、サボってませんからね。',
    'sched_02.ogg': 'ん…この山を運んだら、ちょっと休憩ね。',
    'sched_03.ogg': 'ご主人様、少し離れててください。道具が当たっちゃうので。',
    'sched_04.ogg': '今日はお仕事いっぱい…でも、ゆっくりやれば終わるよね。',
    'sched_05.ogg': 'ふぅ…ちょっと疲れた、もう少しだけ頑張ろ。',
    'sched_06.ogg': 'ご主人様の足音、すぐわかっちゃいます～',
    'sched_07.ogg': 'これは慣れてるやつ、私に任せて！',
    'sched_08.ogg': '待ってて、ここだけ終わったら会いに行きます。',
    'sched_09.ogg': '見に来てくれたんですか？じゃあ、いいところ見せなきゃ～',
    'sched_10.ogg': '私はちゃんと真面目にやるんです…たまにボーッとしますけど。',
    'sched_11.ogg': '手が汚れちゃった、触れないので、洗ってから抱きしめますね。',
    'sched_12.ogg': 'えへへ、頑張ってる姿を見られちゃいました。',
    'sched_13.ogg': 'これ終わったら休む…本当に、休むだけ。',
    'sched_14.ogg': 'ご主人様、お腹すいてません？終わったら何か作りますね～',
    'sched_15.ogg': 'だんだん手際よくなってきた、褒めて褒めて！',
    'sched_16.ogg': 'サボりたいな…でも、ここまでやったし、やめとこ。',
    'sched_17.ogg': 'ご主人様も無理しないで、ちゃんと休んでください。',
    'sched_18.ogg': '心配しないで、私は大丈夫です。',
    'sched_19.ogg': 'あとこの小さい山をやったら、だいたい終わり…',
    'sched_20.ogg': 'ご主人様、日なたに立ってると焼けちゃいますよ。',
    'sched_21.ogg': 'もうちょっと一緒にいたい…でも、まだ仕事が残ってる。',
    'sched_22.ogg': 'ふふん、今日の私はやる気満々です。',
    'sched_23.ogg': '見られると、もっと上手くやらなきゃって思います。',
    'sched_24.ogg': 'この区切りが終われば、ゆっくり休めますね。',
    'sched_25.ogg': 'ご主人様、足元気をつけて。ここ、片付けたばかりなので。',
    'sched_26.ogg': '半分終わった…半分ってことでいいよね？',
    'sched_27.ogg': '見られながらだと、あんまり疲れないかも。',
    'sched_28.ogg': 'なかなか上手くできてる…はず？',
    'sched_29.ogg': 'ご主人様、いってらっしゃい～あとでまた見に来てくださいね。',
    'sched_30.ogg': 'やらなきゃ誰かがやる…のかな？ううん、自分でやろ。',
    'sched_31.ogg': '頑張ってれば、いつか認められる…たぶん。',
    'sched_32.ogg': '疲れた？疲れた。でもご主人様が待ってるから、もうちょっと。',
    'sched_33.ogg': '毎日ご主人様に会えるなら、この仕事も悪くない。',
    'sched_34.ogg': 'ご褒美は…ご主人様に頭を撫でてもらえれば十分かな。',
    'sched_35.ogg': '今日も元気に…うん、まずはお水を一口。',
    'sched_36.ogg': '他の子は休んでるのに、私はお仕事——でも悪くない。',
    'sched_37.ogg': 'この区切りが終われば休める…あれ、まだ山がある。',
    'sched_38.ogg': '頑張れ、一番…一番何だっけ？',
    'sched_39.ogg': '少し多くやっても損はない…たぶん、ないよね。',
    'sched_40.ogg': 'もう少し背が伸びたらな…まずはこの山を運ぼう。',
    'sched_41.ogg': 'ご主人様、こんなに頑張ってるから、見ててくださいね。',
    'sched_42.ogg': '百日続けたらいいことあるって聞いた、もう三十日信じてる。',
    'sched_43.ogg': 'これを終わらせたら、また一歩進んだ。',
    'sched_44.ogg': 'サボる？サボらない…ちょっとだけ休む。',
    'sched_45.ogg': '今日も自分に感動してる——どうしてこんなに働き者なんだろ。',
    'sched_46.ogg': '話が大きすぎる…でも、お腹は本当に空いてる。',
    'sched_47.ogg': '頑張れば、ご主人様は私を見てくれるよね？よね！',
    'sched_48.ogg': 'これが終われば、一息つける…はず。',
    'sched_49.ogg': 'ご主人様、来てくれたんですね？すぐ終わります、ちょっと待ってて～',
    # -------- R1110 叫醒与烧制提醒 --------
    'wake_complain.ogg': 'ん…ご主人様、もう少し寝ていたいんですけど～',
    'cook_no_fuel.ogg': '燃料がもうないの…ご主人様、少しもらえる？',
    'cook_no_food.ogg': '焼ける原料がもうないの…ご主人様、生の食べ物か鉱石を少し分けてくれる？',
    'aid_no_food.ogg': 'ご主人様、お腹すいた？…でも、あげるものがないの…',
}

STYLE_OF_FILE = {}
for _st, _prefixes in STYLE_OF.items():
    for _p in _prefixes:
        STYLE_OF_FILE[_p] = _st


def style_of(file_name):
    for p, st in STYLE_OF_FILE.items():
        if file_name.startswith(p):
            return st
    return 'cheer'


def main():
    entries = json.load(io.open(MANIFEST, encoding='utf-8'))['entries']
    missing = [e['file'] for e in entries if e['file'] not in JP]
    extra = [f for f in JP if f not in {e['file'] for e in entries}]
    counts = {}
    for e in entries:
        counts[style_of(e['file'])] = counts.get(style_of(e['file']), 0) + 1
    print('entries:', len(entries), 'JP lines:', len(JP))
    print('missing JP:', missing if missing else 'none')
    print('extra JP:', extra if extra else 'none')
    print('buckets:', counts)
    print('REF_A:', os.path.isfile(REF_A), ' REF_B:', os.path.isfile(REF_B))
    if len(sys.argv) < 2 or sys.argv[1] != 'run':
        return
    # v1.3.0 实测六百五十五：可选过滤——`run cake_eat.ogg broom_not_ready.ogg`
    # 只合成列出的这几个文件（补一两句新台词时不必把 124 条全量重跑一遍，省十几分钟）
    only = set(sys.argv[2:])
    import urllib.request
    os.makedirs(OUT, exist_ok=True)
    done = 0
    for e in entries:
        f = e['file']
        if only and f not in only:
            continue
        st = style_of(f)
        s = STYLES[st]
        payload = {
            'text': JP[f], 'text_lang': 'ja',
            'ref_audio_path': s['ref'], 'prompt_text': s['prompt'], 'prompt_lang': 'ja',
            'top_k': s['top_k'], 'top_p': s['top_p'], 'temperature': s['temp'],
            'speed_factor': s['speed'], 'text_split_method': 'cut5', 'batch_size': 1,
            'media_type': 'wav', 'streaming_mode': False,
        }
        try:
            req = urllib.request.Request('http://127.0.0.1:9880/tts',
                                         data=json.dumps(payload).encode('utf-8'),
                                         headers={'Content-Type': 'application/json'})
            with urllib.request.urlopen(req, timeout=600) as r:
                data = r.read()
            if data and len(data) > 1000:
                with open(os.path.join(OUT, f.replace('.ogg', '.raw.wav')), 'wb') as fh:
                    fh.write(data)
                done += 1
                print('OK %-8s %-26s %d B' % (st, f, len(data)))
            else:
                print('SHORT/FAIL %-8s %s' % (st, f))
        except Exception as ex:
            print('ERR %-8s %s -> %r' % (st, f, ex))
    print('generated', done, 'of', (len(only) if only else len(entries)))


if __name__ == '__main__':
    main()
