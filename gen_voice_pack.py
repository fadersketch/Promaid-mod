# -*- coding: utf-8 -*-
"""内置语音包【情绪化重制】的台词表 + 合成脚本。

背景：manifest 只存中文 key，旧版的日语台词没有落盘。本次按中文 key 重新撰写
日语台词，并按情境分情绪档（参考音频 A/B + 语速 + 温度/top_k）合成。

用法：
  python _voice_emo2.py check    只校验覆盖度/参考音频存在
  python _voice_emo2.py run     合成到 _voice_new2/
"""
import io
import json
import os
import sys

ROOT = r'C:\Users\Sketch\.zcode\workspace\default\promaid-mod'
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
    'plead':  dict(ref=REF_B, prompt=TEXT_B, speed=1.02, temp=1.0, top_k=15, top_p=1.0),
}

STYLE_OF = {
    'battle': ['enemy_near', 'enemy_clear', 'owner_hurt', 'owner_low_hp',
               'shield_share', 'preserve_wary', 'preserve_no_block', 'preserve_no_escape',
               'preserve_return', 'preserve_lava_fall', 'preserve_drown', 'preserve_stuck',
               'preserve_lava_near', 'preserve_no_heal', 'clutch_snow', 'clutch_water',
               'totem_peer', 'totem_self', 'totem_give', 'attack_ok'],
    'care': ['aid_food', 'aid_sister_help', 'aid_potion_incoming', 'aid_potion_given',
             'aid_bag_full', 'aid_eat_something', 'aid_honey_poison', 'aid_milk_debuff',
             'resurrect_back', 'death_teleport', 'owner_looking',
             'aid_golden_apple', 'aid_enchanted_apple'],
    'plead': ['build_missing_material', 'bridge_no_block', 'brew_missing',
              'mine_no_block_high', 'wood_no_block_high', 'wood_no_axe', 'no_food_left',
              'work_no_pickaxe', 'work_ridden'],
    'work': ['build_done', 'build_substitute', 'build_skip_bedrock', 'build_skip_unloaded',
             'build_skip_noitem', 'build_skip_float', 'build_blocked', 'schedule_locked',
             'bridge_cant_climb', 'work_distracted', 'mine_blocked_area', 'wood_blocked_area',
             'blocked_ore_report', 'found_something', 'ahead_of_you',
             'fallback_missing', 'fallback_something',
             'mine_no_ore', 'farm_no_work'],
}

# 日语台词（按中文 key 的情境重写；sched_* 是排班闲聊，走俏皮档）
JP = {
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
    'aid_potion_incoming.ogg': 'ポーション持ってきたよ',
    'aid_potion_given.ogg': 'ポーション、カバンに入れたよ、飲んでね',
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
    'mine_no_ore.ogg': '近くに掘る価値のある鉱石が無いの',
    'farm_no_work.ogg': '近くに手入れする畑はもう無いの',
    'work_no_pickaxe.ogg': 'ツルハシが無いから、掘れないの…ツルハシをちょうだい',
    'work_ridden.ogg': '誰かに乗られてて、動けないの',
    'aid_golden_apple.ogg': 'ご主人様、金のリンゴをあげる！',
    'aid_enchanted_apple.ogg': 'ご主人様、エンチャントされた金のリンゴをあげる！',
    # -------- 排班闲聊 49 条 --------
    'sched_01.ogg': 'あら、ご主人様来たの？ちゃんとシフト通り働いてるよ、サボってないの！',
    'sched_02.ogg': 'うんうん、この時間はこれって決まってるの、すぐ終わるよ〜',
    'sched_03.ogg': 'ご主人様、少し離れててね？道具が当たっちゃうから',
    'sched_04.ogg': '今日の予定、もう半分終わったよ、やる気満々なの！',
    'sched_05.ogg': 'ふぅ…この作業はちょっと疲れるけど、シフトに書いてあるから頑張るの',
    'sched_06.ogg': 'ご主人様の足音、聞いただけで分かるよ〜',
    'sched_07.ogg': 'ここの仕事は慣れてるの、任せて！',
    'sched_08.ogg': '待ってて、この部分だけ終わらせてから行くね！',
    'sched_09.ogg': 'ご主人様、視察に来たの？いい子にしてたら…ご褒美ある？',
    'sched_10.ogg': '私はシフト通りに動く、模範的なメイドなんだよ',
    'sched_11.ogg': 'ん、手が汚れてるから今は触らないの、仕事が終わったら抱っこするね！',
    'sched_12.ogg': 'このシフト、自分で組んだの…泣いても最後までやるよ…',
    'sched_13.ogg': 'えへへ、頑張ってる所、見られちゃった',
    'sched_14.ogg': '時間になったら次の仕事に移るの、ここに居座ったりしないよ',
    'sched_15.ogg': 'ご主人様、お腹空いてない？これが終わったら何か作るね〜',
    'sched_16.ogg': 'この仕事、どんどん上手くなってるの、褒めて褒めて！',
    'sched_17.ogg': 'サボりたいけど…スケジュールが貼ってあるからね',
    'sched_18.ogg': 'ご主人様も無理しないでね、私がスケジュールを見るから、ご主人様は自分を見てて？',
    'sched_19.ogg': '私の心配は要らないよ、プロなんだから',
    'sched_20.ogg': 'これが終われば、終業にまた一歩近づくよ〜',
    'sched_21.ogg': 'ご主人様、進捗チェックに来たの？質も量もバッチリだよ！',
    'sched_22.ogg': 'シフトが無かったら、今すぐご主人様にべったりしてるのに',
    'sched_23.ogg': 'ふふん、今日の私は相変わらず働き者なの',
    'sched_24.ogg': '仕事中に見られると…なんだかもっとやる気が出るの',
    'sched_25.ogg': 'この時間が終われば、休憩があるよ',
    'sched_26.ogg': 'ご主人様、足元気をつけてね、ここ道具を片付けたばかりなの',
    'sched_27.ogg': '予定は半分、進みは順調だよ〜',
    'sched_28.ogg': 'ご主人様に見られながら働くと、もっと上手くやらなきゃって思うの',
    'sched_29.ogg': '私の効率は、シフトが保証してるの！',
    'sched_30.ogg': 'ご主人様、行ってらっしゃい〜後で働いてる所を見に来てね',
    'sched_31.ogg': '出勤して…何時間目だっけ？まあいいや、働くの！',
    'sched_32.ogg': '上の人は言ったの、頑張れば次の人生で大きい箒をくれるって',
    'sched_33.ogg': 'みんなが帰っても私は働くの、みんなが出勤しても働くの…シフトにそう書いてあるから',
    'sched_34.ogg': '疲れた？疲れたよ。でもご主人様が待ってると思うと、力が出るの',
    'sched_35.ogg': 'この仕事の一番いい所は、毎日ご主人様に会えることなの',
    'sched_36.ogg': '給料？ご主人様のなでなでがあれば十分…たぶん',
    'sched_37.ogg': '今日も元気いっぱいに、シフトに支配される一日なの！',
    'sched_38.ogg': '同僚のメイドも休みたいって…みんな同じなのね',
    'sched_39.ogg': 'これが終われば休める…あれ、次の時間帯もあるの？',
    'sched_40.ogg': '頑張って！あなたは一番…太ってる？違う、一番素敵なメイドなの！',
    'sched_41.ogg': 'よそのメイドは休んでるのに私は働いてる…でも幸せなの、ふん',
    'sched_42.ogg': '働くと背が伸びたらいいのに…',
    'sched_43.ogg': 'ご主人様、私たちの目標は？…よく働いて、毎日上を目指すの！',
    'sched_44.ogg': '百日働いたらご馳走が出るって聞いたの、もう三十日信じてるよ',
    'sched_45.ogg': 'この時間帯が終われば、模範メイドにまた一歩近づくの',
    'sched_46.ogg': 'サボるなんて無理なの、シフトが見てるから',
    'sched_47.ogg': '今日も自分に感動してるの…どうしてこんなに働き者なんだろう',
    'sched_48.ogg': '上の人の話は大きすぎるけど、大丈夫、私よく食べるから',
    'sched_49.ogg': '頑張れば、ご主人様は私を見てくれるよね？よね！',
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
    import urllib.request
    os.makedirs(OUT, exist_ok=True)
    done = 0
    for e in entries:
        f = e['file']
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
    print('generated', done, 'of', len(entries))


if __name__ == '__main__':
    main()
