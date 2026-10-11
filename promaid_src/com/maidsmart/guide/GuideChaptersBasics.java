package com.maidsmart.guide;


/**
 * 手册章节——入门/总览/LLM/蓝图卷轴（v1.2.4 从 GuideContent 拆出）。
 * 
 * 纯文本，逐字未改（含 § 着色码与换行），只是把「写在哪」按主题分开，
 * 每个方法仍返回 GuideContent.Chapter。
 */
public final class GuideChaptersBasics {
    private GuideChaptersBasics() {
    }

    static GuideContent.Chapter featureOverviewGuide() {
        return new GuideContent.Chapter("功能总览 · 一键跳转配置",
                "\u00a7e这里可以一键跳到任意配置页\u00a7r——点下面\u00a7b青色带下划线\u00a7r的链接，"
                        + "会直接打开「模组详细配置」的对应\u00a7e小类\u00a7r（跳转后目标参数行会高亮；"
                        + "在配置面板点「保存并返回」会回到手册）。",
                "\u00a7e① 生产与工作\u00a7r：[[@WORK:BUILD|建造]] · [[@WORK:MINE|挖矿]] · "
                        + "[[@WORK:WOOD|伐木]] · [[@WORK:COOK_BREW|烹饪与酿造]] · [[@WORK:FARM|农场与宰杀]] · "
                        + "[[@WORK:SCHEDULE|排班表]] · [[@WORK:BUILD:\u6307\u6807\u77f3|\u6307\u6807\u77f3]]",
                "\u00a7e② AI 与对话\u00a7r：[[@AI:MEMORY|AI 记忆]] · [[@AI:DIALOGUE|对话与自主]] · "
                        + "[[@AI:PERCEPTION|感知]] · [[@AI:AFFECT|情绪]] · [[@AI:AITOOLS|AI 工具]]",
                "\u00a7e③ 战斗与自保\u00a7r：[[@COMBAT:SELF_PRESERVE|自保与保命]] · "
                        + "[[@COMBAT:SELF_TACTICS|自保战术]] · [[@COMBAT:TACTICS|单兵战术]] · "
                        + "[[@COMBAT:AUTO_COMBAT|主动参战]] · [[@COMBAT:AID|贴身辅助]] · "
                        + "[[@COMBAT:PLAYER_DAMAGE|玩家伤害策略]] · "
                        + "[[@COMBAT:AIR_RAID|空袭数值]] · "
                        + "[[@COMBAT:AIR_RAID|空袭与飞行作战]] · [[@COMBAT:MANEUVER|接敌机动]]",
                "\u00a7e④ 生存与复活\u00a7r：[[@SURVIVAL:FALL_GUARD|落地缓冲]] · "
                        + "[[@SURVIVAL:REVIVE|死亡与复活]] · "
                        + "[[@SURVIVAL:ESCAPE|传送与逃生]] · [[@SURVIVAL:SAFETY|女仆安全与区块]]",
                "\u00a7e⑤ 移动与行为\u00a7r：[[@MOVE:FOLLOW|移动与跟随]] · [[@MOVE:IDLE|空闲与流畅]] · "
                        + "[[@MOVE:BRIDGE|搭路]] · [[@MOVE:FLIGHT_FOLLOW|飞行跟随]] · "
                        + "[[@MOVE:BROOM|扫帚模式]] · [[@MOVE:TETHER|武装拴绳]] · "
                        + "[[@MOVE:RIDE|骑乘指挥棒]] · [[@MOVE:DA_YUN|饰品「大运」]]",
                "\u00a7e⑥ 语音与显示\u00a7r：[[@UI:VOICE|语音与 TTS]] · [[@UI:HUD|显示与提示]]",
                "\u00a7e⑦ 系统与杂项\u00a7r：[[@SYSTEM:UTILITY|交互与杂项]] · [[@SYSTEM:LOG|运行日志]] · "
                        + "[[@SYSTEM:COMPRESSION_BOX|压缩盒]]",
                "\u00a7e用法\u00a7r：每章正文末尾也会自动附一行「⚙ 配置入口」链接，"
                        + "讲到哪个功能就直接点它跳到该功能的配置页；想手动翻也可以走"
                        + "「[[@WORK|模组详细配置]]」首页 → 大类 → 小类。");
    }

    static GuideContent.Chapter foreword() {
        return new GuideContent.Chapter("开发者寄语",
                "\u00a7e这是我制作的第 1 个 mod。\u00a7r虽然它只是一个附属模组，但依然花费了我不少的时间和精力。",
                "本意只是想让女仆获得更多的技能、做一些我想让她做的事——我翻遍了能找到的地方也没找到现成的模组，"
                        + "最后只能在 AI 的帮助下一点一点自己动手做。",
                "说实话，我对代码方面的知识并不怎么了解，所以模组里可能还会有一些 bug。"
                        + "如果你在使用中遇到任何问题，欢迎反馈给我，我会尽力去修。",
                "\u00a77温馨提示：本指南是保姆式教学——每一章都先讲「是什么」，再手把手教你「怎么用」，"
                        + "关键步骤都配了【举例】。建议从头到尾读一遍（每章 1~2 分钟），比遇到问题再翻效率高得多。");
    }

    static GuideContent.Chapter intro() {
        return new GuideContent.Chapter("这是什么？",
                "\u00a7ePromaid\uff08\u5973\u4ec6 AI \u667a\u80fd\u589e\u5f3a\u5305\uff09\u00a7r\u662f\u300c\u8f66\u4e07\u5973\u4ec6 Touhou Little Maid\u300d\u7684\u9644\u5c5e\u6a21\u7ec4\uff0c\u8ba9\u5973\u4ec6\u53d8\u5f97\u66f4\u806a\u660e\u3001\u66f4\u80fd\u5e72\u3002\u00a7eForge \u7248\u00a7r \u4e0e \u00a71NeoForge \u7248\u00a7r\u00a7e\u529f\u80fd\u5bf9\u9f50\u3001\u73a9\u6cd5\u4e00\u81f4\u00a7r\uff1a\u90fd\u9002\u914d TLM\uff0cNeoForge \u7248\u591a\u4e00\u5757\u91cd\u9524\u76f8\u5173\u5185\u5bb9\uff0c\u5176\u4f59\u529f\u80fd\u4e00\u81f4\u3002",
                "\u5b83\u7ed9\u5973\u4ec6\u5e26\u6765\u00a7e\u5efa\u9020\u3001\u6316\u77ff\u3001\u70e7\u5236\u3001\u917f\u9020\u00a7r\u4efb\u52a1\uff0c\u00a7e\u6295\u5582\u6cbb\u7597\u3001\u81ea\u4fdd\u9003\u751f\u3001\u5355\u5175\u6218\u672f\u00a7r\u7b49\u6218\u6597\u80fd\u529b\uff0c\u00a7eAI \u8bb0\u5fc6\u3001\u4e3b\u52a8\u5bf9\u8bdd\u3001\u611f\u77e5\u3001\u60c5\u7eea\u00a7r\u7b49\u667a\u80fd\u7cfb\u7edf\uff0c\u4ee5\u53ca\u5341\u51e0\u4e2a\u53ef\u88ab LLM \u5bf9\u8bdd\u76f4\u63a5\u8c03\u7528\u7684\u00a7eAI \u5de5\u5177\u00a7r\u3002",
                "\u00a7e\u529f\u80fd\u901f\u89c8\u00a7r\uff08\u6bcf\u6761\u4e00\u884c\uff0c\u8be6\u7ec6\u73a9\u6cd5\u89c1\u5bf9"
                + "\u5e94\u7ae0\u8282\uff09\uff1a",
                "\u00a7e\u6392\u73ed\u8c03\u5ea6\u00a7r\uff1a\u6309\u6e38\u620f\u5185\u65f6\u95f4\u81ea\u52a8\u5207\u6362\u4efb"
                + "\u52a1\u4e0e\u4f5c\u606f\u3002\n\u00a7e\u81ea\u4e3b\u6218\u6597\u4e0e\u8fd8\u539f\u00a7r\uff1a\u6709\u5a01\u80c1"
                + "\u81ea\u52a8\u53c2\u6218\uff0c\u89e3\u9664\u540e\u8fd8\u539f\u539f\u4efb\u52a1\uff08\u6a21\u7ec4\u6b66\u5668"
                + "\u8d70\u4e13\u5c5e\u6a21\u5f0f\uff09\u3002\n\u00a7e\u6392\u73ed\u5927\u8303\u56f4\u6d3b\u52a8\u00a7r\uff1a\u6392"
                + "\u73ed\u4e2d\u7684\u5973\u4ec6\u6d3b\u52a8\u8303\u56f4\u66f4\u5927\uff0c\u8986\u76d6\u6574\u7247\u5de5\u4f5c"
                + "\u533a\u3002\n\u00a7e\u7a7a\u95f2\u6563\u6b65\u00a7r\uff1a\u6ca1\u4e8b\u5e72\u65f6\u56db\u5904\u8d70\u8d70\uff0c"
                + "\u4e0d\u7ad9\u6869\u53d1\u5446\u3002\n\u00a7e\u5bb0\u6740\u4efb\u52a1\u00a7r\uff1a\u67d0\u7c7b\u7272\u755c\u8d85"
                + "\u8fc7\u9608\u503c\u81ea\u52a8\u5bb0\u6740\uff0c\u6ca1\u6d3b\u65f6\u56db\u5904\u95f2\u901b\u3002\n\u00a7e\u8015"
                + "\u5730\u6807\u8bb0\u5236\u00a7r\uff1a\u53ea\u9504\u88ab\u6807\u8fc7\u8015\u5730\u8bb0\u53f7\u7684\u5730\u5757"
                + "\uff0c\u8d85\u5e73\u5766\u4e0d\u8fde\u9501\u6269\u6563\u3002\n\u00a7e\u8de8\u7ef4\u5ea6 / \u8fdc\u8ddd\u62c9"
                + "\u56de\u00a7r\uff1a\u4e3b\u4eba\u8d70\u8fdc\u6216\u6362\u7ef4\u5ea6\u65f6\u628a\u5979\u5b89\u5168\u62c9\u56de"
                + "\uff08\u642d\u8def\u4e2d\u4e0d\u6253\u65ad\uff09\u3002\n\u00a7e\u6392\u73ed\u8868\u5355\u72ec\u4f20\u9001\u00a7r"
                + "\uff1a\u4e00\u4e2a\u6309\u952e\u53ea\u628a\u70b9\u540d\u7684\u90a3\u53ea\u53ec\u5230\u4f60\u8eab\u8fb9\u3002\n"
                + "\u00a7e\u5efa\u9020\u6295\u5f71\u7ebf\u6846\u00a7r\uff1a\u7528\u5e7d\u7075\u65b9\u5757\u753b\u51fa\u5efa\u7b51"
                + "\u8f6e\u5ed3\uff0c\u9694\u5730\u5f62\u3001\u8de8\u533a\u5757\u90fd\u80fd\u770b\u89c1\u3002",
                "\u00a7e\u642d\u8def\u00a7r\uff08\u9ed8\u8ba4\u5173\uff09\uff1a\u4e0e\u4e3b\u4eba\u9694\u6c9f\u9694\u7a7a\u65f6"
                + "\u94fa\u65b9\u5757\u8d34\u8fd1\uff0c\u6316\u77ff\u4f10\u6728\u57ab\u5757\u4e5f\u4f1a\u8e29\u4e0a\u53bb\u3002\n"
                + "\u00a7e\u70e7\u5236\u901a\u7528\u5316\u00a7r\uff1a\u7194\u7089\u5582\u4efb\u4f55\u53ef\u70e7\u5236\u7269\uff0c"
                + "\u9ad8\u7089 / \u70df\u718f\u7089\u517c\u5bb9\uff0c\u591a\u5973\u4ec6\u591a\u7089\u5404\u5360\u4e00\u4e2a\u3002"
                + "\n\u00a7e\u6295\u5582\u8ba4\u6a21\u7ec4\u98df\u7269\u00a7r\uff1a\u5e26\u98df\u7269\u5c5e\u6027\u7684\u90fd\u8ba4"
                + "\uff0c\u53ef\u5728\u9762\u677f\u9010\u9879\u5f00\u5173\u3002\n\u00a7e\u5ba0\u7269\u514d\u75ab\u00a7r\uff1a\u73a9"
                + "\u5bb6\u5ba0\u7269\u4e0e\u4e3b\u4eba\u7684\u5ba0\u7269\uff0c\u5973\u4ec6\u4e0d\u4f24\u5bb3\u4e5f\u4e0d\u4ec7"
                + "\u6068\uff08\u542b AOE\uff09\u3002\n\u00a7e\u6563\u6b65\u901f\u5ea6\u53ef\u8c03\u00a7r\uff1a\u9ed8\u8ba4 0.4"
                + "\u3001\u53ef\u4f4e\u5230 0.05\uff0c/maid_smart stroll \u53ef\u73b0\u573a\u91cf\u901f\u3002\n\u00a7e\u9a91\u4e58 "
                + "/ \u5750\u4e0b\u4e0d\u642d\u65b9\u5757\u00a7r\uff1a\u8fd9\u4e24\u79cd\u5f62\u6001\u4e0b\u632a\u4e0d\u52a8\uff0c"
                + "\u4e0d\u518d\u7559\u65b9\u5757\u6b8b\u6e23\u3002\n\u00a7e\u70e7\u5236\u917f\u9020\u4e0d\u51bb\u539f\u5730\u00a7r"
                + "\uff1a\u6ca1\u6709\u76ee\u6807\u65f6\u5979\u4e0d\u4f1a\u50f5\u5728\u539f\u5730\u5e72\u7b49\u3002",
                "\u00a7e\u7a7a\u88ad\u4e0e\u98de\u884c\u4f5c\u6218\u00a7r\uff1a\u9798\u7fc5 + \u6b66\u5668 + \u70df\u82b1\u9f50"
                + "\u4e86\u81ea\u52a8\u98de\u8d77\u6765\u6253\uff0c\u8fd1\u6218\u8d34\u8138\u3001\u8fdc\u7a0b\u76d8\u65cb\u3002\n"
                + "\u00a7e\u7a7a\u88ad\u8f70\u70b8\u00a7r\uff1a\u4e24\u79cd\u7a7a\u88ad\u6253\u5b8c\u4e00\u8bb0\u987a\u624b\u6254"
                + "\u70b8\u5f39\uff08\u672b\u5730\u6c34\u6676 / \u91cd\u751f\u951a / \u5e8a / TNT\uff09\u3002\n\u00a7e\u98de\u884c"
                + "\u8ddf\u968f\u00a7r\uff08\u9ed8\u8ba4\u5173\uff09\uff1a\u4e3b\u4eba\u81ea\u5df1\u98de\u8d70\u4e86\uff0c\u5979"
                + "\u80cc\u4e0a\u9798\u7fc5\u8ffd\u8fc7\u6765\u3002\n\u00a7e\u8fdc\u7a0b\u7a7a\u88ad\u5f00\u706b\u00a7r\uff1a\u8fdc"
                + "\u7a0b\u7a7a\u88ad\u4f1a\u6b63\u5e38\u5f00\u706b\uff0c\u5e76\u914d\u300c\u8fdc\u7a0b\u5f00\u706b\u300d\u8bca"
                + "\u65ad\u884c\u67e5\u539f\u56e0\u3002\n\u00a7e\u7a7a\u88ad\u6570\u503c\u5168\u53ef\u8c03\u00a7r\uff1a34 \u9879"
                + "\u6570\u503c\u90fd\u5728\u300c\u6218\u6597\u4e0e\u81ea\u4fdd \u2192 \u7a7a\u88ad\u6570\u503c\u300d\u91cc\u3002\n"
                + "\u00a7e\u6fc0\u6d41\u4e09\u53c9\u621f\u63a8\u8fdb\u5242\u00a7r\uff1a\u4e3b\u624b\u6fc0\u6d41\u4e09\u53c9\u621f"
                + "\u80fd\u5f53\u98de\u884c\u63a8\u8fdb\u5668\uff08\u8d77\u98de / \u76d8\u65cb / \u4fef\u51b2 / \u8ddf\u968f\uff09"
                + "\u3002\n\u00a7e\u4e09\u53c9\u621f\u6309\u4efb\u52a1\u5206\u7c7b\u00a7r\uff1a\u653b\u51fb\u6a21\u5f0f\u8d70\u8fd1"
                + "\u6218\uff0c\u6295\u63b7\u4efb\u52a1\u8d70\u8fdc\u7a0b\u3002\n\u00a7e\u6218\u6597\u6a21\u5f0f\u5206\u7c7b\u8868"
                + "\u00a7r\uff1a\u9010\u4efb\u52a1\u70b9\u540d\u8fd1\u6218 / \u8fdc\u7a0b / \u4e0d\u53c2\u4e0e\uff0c\u53ef\u5173"
                + "\u6389\u300c\u6a21\u7ec4\u4efb\u52a1\u4f18\u5148\u8ba9\u4f4d\u300d\u3002\n\u00a7e\u7d22\u654c\u53e3\u5f84\u00a7r"
                + "\uff1a\u9694\u7740\u65b9\u5757\u7684\u602a\u4e0d\u7b97\u5a01\u80c1\uff1b\u6218\u6597\u4e2d\u5de5\u4f5c\u8303"
                + "\u56f4\u4e34\u65f6\u6269\u5708\u8ffd\u654c\u3002\n\u00a7e\u9a7b\u5b88\u519c\u573a\u4e0d\u4e71\u8f6c\u00a7r\uff1a"
                + "home \u6a21\u5f0f\u4e0b\u7684\u519c\u573a\u5b8c\u5168\u4ea4\u56de\u539f\u7248\uff0c\u95f2\u65f6\u7ad9\u7740"
                + "\u6216\u81ea\u5df1\u6563\u6b65\u3002\n\u00a7e\u6218\u540e\u9a7b\u5b88\u8fd8\u539f\u00a7r\uff1a\u6253\u5b8c\u602a"
                + "\u9a7b\u5b88\u4e0d\u4f1a\u53d8\u6210\u8ddf\u968f\uff0c\u6309\u6218\u6597\u524d\u8bb0\u5f55\u8fd8\u539f\u3002\n"
                + "\u00a7e\u7a92\u606f\u63d0\u793a\u95e8\u69db\u00a7r\uff1a\u53ea\u5728\u771f\u5403\u5230\u8d85\u8fc7 4 \u70b9"
                + "\u7a92\u606f\u4f24\u5bb3\u65f6\u624d\u8bf4\uff0c\u8fd0\u884c\u65e5\u5fd7\u5206\u7c7b\u300c\u7a92\u606f\u300d"
                + "\u3002",
                "\u00a7e\u538b\u7f29\u76d2\u00a7r\uff1a5 \u683c \u00d7 \u6bcf\u683c 114514 \u7684\u6536\u7eb3\u9053\u5177\uff0c"
                + "\u653e\u8fdb\u5979\u80cc\u5305\u5c31\u662f\u5979\u80cc\u5305\u7684\u5ef6\u4f38\u3002\n\u00a7e\u538b\u7f29\u76d2"
                + "\u7981\u5165\u53e3\u5f84\u00a7r\uff1a\u9644\u9b54\u4e66 / \u9644\u9b54\u6b66\u5668 / \u538b\u7f29\u76d2\u672c"
                + "\u8eab\u90fd\u653e\u4e0d\u8fdb\u53bb\uff0c\u754c\u9762\u91cc\u9f20\u6807\u4e5f\u9009\u4e0d\u4e2d\u3002\n\u00a7e"
                + "\u7cbe\u5999\u80cc\u5305\u9002\u914d\u00a7r\uff1a\u5979\u80cc\u5305\u6ee1\u4e86\u88c5\u8fdb\u8eab\u4e0a\u7684"
                + "\u989d\u5916\u5bb9\u5668\uff0c\u53d6\u6599\u65f6\u4e5f\u5148\u53bb\u90a3\u91cc\u627e\u3002\n\u00a7e\u526f\u624b"
                + "\u52a8\u4f5c\u8868\u73b0\u00a7r\uff08\u9ed8\u8ba4\u5f00\uff09\uff1a\u5e72\u6d3b\u65f6\u526f\u624b\u90a3\u4ef6"
                + "\u4f1a\u4e3e\u4e00\u4e0b\uff0c\u5168\u94fe\u8def\u3002",
                "\u00a7e\u5de1\u903b\u822a\u56fe\u00a7r\uff1a\u7ed9\u300c\u626b\u63cf + \u5728\u5bb6\u300d\u7684\u5973\u4ec6"
                + "\u753b\u4e00\u6761\u95ed\u73af\u8f68\u9053\uff0c\u4e00\u672c\u88c5 16 \u6761\u3002\n\u00a7e\u6b66\u88c5\u62f4"
                + "\u7ef3\uff08\u4e8c\u53f7\u4f4d\uff09\u00a7r\uff1a\u5979\u98de\u5979\u5f00\u706b\uff0c\u4f60\u6302\u5728\u5979"
                + "\u8eab\u4e0b\u5f00\u706b\u2014\u2014\u300c\u6b66\u88c5\u76f4\u5347\u673a\u300d\u73a9\u6cd5\u3002\n\u00a7e\u9a91"
                + "\u4e58\u6307\u6325\u68d2\u00a7r\uff1a\u8ba9\u5973\u4ec6\u9a91\u5750\u9a91 / \u8f7d\u5177 / \u9f99\u8ddf\u7740"
                + "\u4f60\u8d70\u3002\n\u00a7e\u5353\u8d8a\u524d\u7ebf\u8f7d\u5177\u00a7r\uff1a\u5979\u80fd\u5f00\u8f66\u3001\u80fd"
                + "\u5728\u8f66\u4e0a\u5f00\u706b\uff0c\u56db\u79cd\u98de\u884c\u8f7d\u5177\u5404\u63a5\u5404\u7684\u8def\u3002\n"
                + "\u00a7e\u8d85\u8d8a\u7ef4\u5ea6\u5b58\u50a8\u8054\u52a8\u00a7r\uff1a\u4ea7\u7269\u56de\u6536 / \u6ea2\u51fa"
                + "\u5165\u5e93 / \u81ea\u52a8\u8865\u8d27 / \u7f13\u5b58\u51b2\u5237\u3002",
                "\u00a7e不想读长文？看这三章就够上手\u00a7r："
                        + "「准备篇：配置 LLM」（要 AI 功能必读）、"
                        + "「建造系统教程」（核心玩法，含完整例子）、"
                        + "「模组详细配置面板导航」（所有开关在哪一格）。",
                "按「是否要 AI」分两大类：",
                "\u00a7e不需要 LLM（配好就能用）\u00a7r：建造执行、挖矿、烧制酿造、农场、自保逃生、落地水、"
                        + "投喂治疗、单兵战术、武器自动切换、感知播报、情绪、TTS 语音包；",
                "\u00a7e需要 LLM（要先配 AI 站点）\u00a7r：主动对话、自主决策、AI 记忆、AI 工具（对话喊命令）、"
                        + "LLM 现场生成蓝图、AI 设计器。",
                "\u00a7e快速上手 5 步：\u00a7r",
                "第 1 步\u00a7e拿手册\u00a7r：进游戏系统会送你一本《Promaid 手册》"
                        + "（丢了用 /give @p maid_smart:blueprint_book）；",
                "\u00a7e手册合成\u00a7r（工作台，无序）："
                        + "[[#shapeless;minecraft:book;minecraft:paper;>maid_smart:blueprint_book;@书 + 纸]]",
                "第 2 步\u00a7e配 LLM\u00a7r：想用 AI 功能（对话/记忆/工具/自主决策），先去「准备篇：配置 LLM」章"
                        + "把 AI 站点配好——这是最重要的一步；",
                "第 3 步\u00a7e切任务\u00a7r：右键女仆打开她的任务页，切到「建筑/挖矿/烧制/酿造」等任务；",
                "\u7b2c 4 \u6b65\u00a7e\u5f00\u624b\u518c\u00a7r\uff1a\u624b\u6301\u624b\u518c\u53f3\u952e\uff0c\u91cc\u9762"
                + "\u6709\u00a7e\u8be6\u7ec6\u4ecb\u7ecd\uff08\u672c\u6307\u5357\uff09\u3001\u5efa\u9020\u3001\u5973\u4ec6\u7ba1"
                + "\u7406\u3001\u6a21\u7ec4\u8be6\u7ec6\u914d\u7f6e\u3001\u5973\u4ec6\u8bb0\u5fc6\u3001\u2691 DIY / \u9ed8\u8ba4"
                + "\u6a21\u5f0f\u00a7r\u516d\u4e2a\u5165\u53e3\uff1b",
                "第 5 步\u00a7e调参数\u00a7r：所有可调参数都在「模组详细配置」面板里，改完点\u00a7a保存并返回\u00a7r立即生效"
                        + "（不用重启游戏）。");
    }

    static GuideContent.Chapter llmSetup() {
        return new GuideContent.Chapter("准备篇：配置 LLM（保姆式）",
                "想让女仆开口说话、记住事情、自主决策、用 AI 工具，\u00a7e必须先配置大语言模型（LLM）站点\u00a7r。"
                        + "没配的话这些功能会\u00a7e静默不触发\u00a7r（日志可能毫无报错，看起来像「坏了」）。",
                "\u00a7e第一步：打开女仆的 AI 设置\u00a7r",
                "右键女仆 → 打开 TLM 原版配置界面（背包/外观/行为设置）→ 找到\u00a7e AI 聊天 / Chat\u00a7r相关页。",
                "\u00a7e第二步：开启 AI 聊天并填站点\u00a7r",
                "① 打开\u00a7e AI 聊天开关\u00a7r（LLM_ENABLED）；"
                        + "② 填\u00a7e OpenAI 兼容\u00a7r的 API 地址、密钥、模型名——DeepSeek、OpenAI、硅基流动、"
                        + "自建（OneAPI/NewAPI 中转）等凡是 OpenAI 兼容格式的都行；"
                        + "③ 给女仆\u00a7e写人设\u00a7r（性格描述，如「温柔体贴的管家」）——不写人设女仆不会主动开口。",
                "\u00a7e第三步：验证\u00a7r",
                "对女仆说一句话（如「你好」），她头顶出现气泡 + 语音（开了 TTS 的话）就说明通了。",
                "\u00a7e【举例】用 DeepSeek 配置：\u00a7r",
                "API 地址填 https://api.deepseek.com（OpenAI 兼容），密钥填你在 DeepSeek 平台申请的 key，"
                        + "模型填 deepseek-chat；保存后对女仆说「你好」测试。",
                "\u00a7eAI 记忆的 API\u00a7r：默认跟随原版（TLM）配置，无需单独设置。",
                "\u00a7e\u8f93\u51fa\u8bed\u8a00\u00a7r\uff1a\u5728\u300c\u6a21\u7ec4\u8be6\u7ec6\u914d\u7f6e \u2192 AI \u4e0e\u5bf9\u8bdd \u2192 \u5bf9\u8bdd\u4e0e\u81ea\u4e3b \u2192 \u8f93\u51fa\u8bed\u8a00\u300d\u9009\u300c\u4e2d\u6587\u300d\u2014\u2014\u4e0d\u7ba1\u5ba2\u6237\u7aef\u8bed\u8a00\u662f\u4ec0\u4e48\u90fd\u7a33\u5b9a\u8bf4\u4e2d\u6587\u3002",
                "\u00a7e常见问题\u00a7r：配好了但女仆不理你？① 检查 AI 聊天开关有没有真开；② 检查密钥/地址是否填错"
                        + "（最新日志搜 chat 相关报错）；③ 人设没写时女仆不会主动说话，先手动跟她搭话。");
    }

    static GuideContent.Chapter bookGuide() {
        return new GuideContent.Chapter("手册使用指南",
                "《Promaid 手册》是这个模组的总入口，\u00a7e手持右键打开\u00a7r。打开后看到六个入口：",
                "① \u00a7e详细介绍\u00a7r——就是你现在读的本指南（按章节翻页阅读）；",
                "② \u00a7e建造\u00a7r——蓝图总目录（内置授权蓝图 + 你放入/导入/生成的外部图纸），点建筑看材料详情；",
                "③ \u00a7e女仆管理\u00a7r——有效建造区块列表 + 建造状态女仆名单；",
                "④ \u00a7e\u2699 模组详细配置\u00a7r——所有可调参数的面板（详见「模组详细配置面板导航」章）；",
                "⑤ \u00a7e女仆记忆\u00a7r——查看/管理女仆记得的内容；",
                "⑥ \u00a7e\u2691 DIY / 默认模式\u00a7r——一键给\u00a7e整个模组\u00a7r换一套设定（见下一段）。",
                "§eDIY / 默认模式（首页第 6 个入口）§r：点一下给§e整个模组§r换设定——§eDIY 模式§r = 把一切「介入女仆行为」的开关§e一次全关§r，像 TLM 原版一样什么都不插手，之后你只挑想要的加强项打开；§e默认模式§r = 恢复本模组的§e出厂推荐配置§r。两个档都§e立即生效、随时可切回§r（切换前会弹一次确认，并写明这次会改动多少项；AI 密钥与端点、矿表/木材表这类手工名单不会被清掉）。当前是哪个模式，该页顶部与配置面板里都能看到。",
                "\u00a7e\u70b9\u94fe\u63a5\u76f4\u8fbe\u914d\u7f6e\u00a7r\uff1a\u8be6\u7ec6\u4ecb\u7ecd\u91cc\u00a7b\u9752\u8272"
                + "\u5e26\u4e0b\u5212\u7ebf\u00a7r\u7684\u6587\u5b57\u90fd\u662f\u00a7e\u53ef\u70b9\u51fb\u94fe\u63a5\u00a7r\u2014"
                + "\u2014\u70b9\u5b83\u76f4\u63a5\u6253\u5f00\u300c\u6a21\u7ec4\u8be6\u7ec6\u914d\u7f6e\u300d\u7684\u5bf9\u5e94"
                + "\u5206\u7c7b\u9875\uff0c\u5e76\u00a7e\u9ad8\u4eae\u76ee\u6807\u53c2\u6570\u884c\u00a7r\uff1b\u6bcf\u7ae0\u6b63"
                + "\u6587\u672b\u5c3e\u4e5f\u81ea\u52a8\u9644\u4e00\u884c\u300c\u2699 \u914d\u7f6e\u5165\u53e3\u300d\u94fe\u63a5"
                + "\u3002\u60f3\u770b\u5168\u90e8\u5165\u53e3\u89c1\u300c\u529f\u80fd\u603b\u89c8 \u00b7 \u4e00\u952e\u8df3\u8f6c"
                + "\u914d\u7f6e\u300d\u7ae0\u3002\u00a7e\u624b\u518c\u5185\u6ca1\u6709\u4efb\u4f55\u5f00\u5173\u00a7r\u2014\u2014"
                + "\u6240\u6709\u5f00\u5173/\u53c2\u6570\u90fd\u53ea\u5728\u300c\u6a21\u7ec4\u8be6\u7ec6\u914d\u7f6e\u300d\u9762"
                + "\u677f\u91cc\u8c03\u3002",
                "\u00a7e\u9875\u9762\u95f4\u7684\u8df3\u8f6c\u94fe\u8def\u00a7r\uff1a",
                "区块名单页（区块内右击手册 → 建造详情 → 名单）\u2192「♀ 女仆管理」按钮\u2192 女仆管理页"
                        + "\u2192 点女仆行\u2192 女仆详情页（绑定/解绑区块）。",
                "\u00a7e小技巧 1\u00a7r：站在\u00a7e建造区块内\u00a7r再右击手册，直接进入\u00a7e该区块的建造详情页\u00a7r"
                        + "（材料缺口、暂停/继续/速度/取消按钮）；区块外右击则进大目录。",
                "\u00a7e\u5c0f\u6280\u5de7 2\u00a7r\uff1a\u4e16\u754c\u91cc\u7684\u533a\u5757\u6709\u00a7e\u7ea2\u8272\u8fb9\u6846 + \u60ac\u6d6e\u6807\u7b7e\u00a7r\uff08\u540d\u5b57 + \u300c\u521b\u5efa\u4e8e x, y, z\u300d\u5750\u6807\uff09\uff0c\u6807\u7b7e\u662f\u900f\u663e\u7684\u2014\u2014\u4efb\u4f55\u89d2\u5ea6\u3001\u9694\u7740\u65b9\u5757\u90fd\u80fd\u770b\u5230\u3002",
                "\u00a7e【举例】查看「猎人小屋」区块的进度：\u00a7r",
                "走到猎人小屋区块内 → 右击手册 → 直接进入该区块详情页 → 看材料表/进度条/控制按钮；"
                        + "想换别的区块，点「← 返回目录」→ 女仆管理 → 点区块行看坐标。",
                "手册关闭后会自动记住你上次看的页面，下次打开回到原处（ESC 退出也一样）。");
    }

    static GuideContent.Chapter buildGuide() {
        return new GuideContent.Chapter("建造系统教程（含完整例子）",
                "女仆能照着蓝图把建筑\u00a7e一块一块真实搭出来\u00a7r——材料从背包真实消耗，缺料会暂停告诉你补什么。"
                        + "整体流程 6 步，跟着做一遍就会了。",
                "\u00a7e第 1 步：给女仆切「建筑」任务\u00a7r",
                "右键女仆 → 任务页 → 切「建筑」——她会\u00a7e坐下等待命令\u00a7r。",
                "\u00a7e第 2 步：下达蓝图\u00a7r（两种方式任选）",
                "方式 A（对话）：对女仆说「照着手册里的三层木制别墅建一个」；"
                        + "方式 B（手册）：打开手册 → 建造 → 选「三层木制别墅」→ 点「建造此图纸」。",
                "\u00a7e第 3 步：确认范围与朝向\u00a7r",
                "点建造后游戏里出现\u00a7e金色框\u00a7r预览占地（以你为中心，移动可见范围），"
                        + "框内还有\u00a7e青色幽灵方块\u00a7r显示建筑投影（形状/朝向一目了然）。"
                        + "按 \u00a7bZ 键\u00a7e整体顺时针旋转建筑 90°\u00a7r——占地宽深互换、楼梯/门等朝向跟着转、投影实时刷新"
                        + "（默认键位 Z；想改键点详情页右下角「\u00a79转向键位\u00a7r」直达原版按键设置）。"
                        + "选好位置与朝向后\u00a7e再次打开手册点「建造此图纸」确认\u00a7r——女仆搭建会\u00a7e直接摧毁\u00a7r区块内的树/建筑等障碍物。",
                "\u00a7e\u5efa\u9020\u671f\u95f4\u7684\u5de5\u5730\u6295\u5f71\u00a7r\uff1a\u786e\u8ba4\u540e\u91d1\u8272\u6846\u6362\u6210\u00a7e\u7ea2\u8272\u533a\u57df\u6846\u00a7r\u9489\u5728\u5de5\u5730\uff08\u9876\u90e8\u663e\u793a\u533a\u5757\u540d\u4e0e\u521b\u5efa\u5750\u6807\uff09\uff0c\u539f\u5730\u7684\u00a7e\u6a59\u8272\u5e7d\u7075\u65b9\u5757\u00a7r\u5c31\u662f\u5efa\u7b51\u6700\u7ec8\u5f62\u6001\u2014\u2014\u00a7e\u5173\u6389\u624b\u518c\u5c31\u80fd\u770b\u5230\u00a7r\uff0c\u7ad9\u5728\u00a7e\u7ea2\u8272\u533a\u5757\u5916\u00a7r\u4e5f\u80fd\u770b\u6e05\u8f6e\u5ed3\uff0c\u8ddd\u79bb\u8d8a\u8fdc\u8d8a\u6de1\uff1b\u5efa\u5230\u54ea\u586b\u5b9e\u5230\u54ea\uff1b\u53d6\u6d88\u6216\u5efa\u5b8c\u81ea\u52a8\u6d88\u5931\u3002\u914d\u7f6e\u300c\u84dd\u56fe\u6295\u5f71\u300d\u5f00\u5173\u53ea\u63a7\u5236\u4e24\u79cd\u5e7d\u7075\u65b9\u5757\uff0c\u7ea2\u6846\u59cb\u7ec8\u663e\u793a\u3002",
                "\u00a7e第 4 步：绑定女仆\u00a7r",
                "手册 → 女仆管理 → 点女仆行进详情页 → 点「绑定到选中区块」。也可以多只女仆绑同一个区块一起建。",
                "\u00a7e第 5 步：看进度\u00a7r",
                "\u533a\u5757\u5185\u53f3\u51fb\u624b\u518c \u2192 \u533a\u5757\u8be6\u60c5\u9875\uff1a\u6750\u6599\u8868\u3001\u8fdb\u5ea6\u6761\uff08\u767e\u5206\u6bd4 + \u901f\u5ea6 + \u9884\u8ba1\u5b8c\u6210\u65f6\u95f4\uff09\uff1b\u8fdb\u5ea6\u6761\u53ea\u5728\u533a\u5757\u8be6\u7ec6\u9875\u663e\u793a\uff0c\u5973\u4ec6\u7ba1\u7406\u9875\u4fdd\u6301\u5e72\u51c0\u3002",
                "\u00a7e第 6 步：缺料补料\u00a7r",
                "女仆缺料会暂停并气泡/系统消息告诉你缺什么（如「缺石砖 ×64」）——你背包有料她会\u00a7e自动取\u00a7r，"
                        + "你没有就去准备，补上后自动续建，已建部分不会重搭。",
                "\u00a7e蓝图的四种来源\u00a7r：",
                "① \u00a7e随模组附赠的蓝图包\u00a7r：81 套已授权蓝图（房屋/地形/天空/树木/武器/水/红石/节日/装饰/载具/雕像各系列——"
                        + "从中世纪庄园、上海中心大厦到天空之城、雕像合集、巨型载具都有），"
                        + "首次运行自动解压到 config/maid_smart/blueprints/，开箱即用，直接选一个点「建造此图纸」就能开工；",
                "② \u00a7eLLM 现场生成\u00a7r：对话「造一个 5x5 的现代小屋」——AI 直接出蓝图（需 LLM，有大小限制）；",
                "③ \u00a7e外部蓝图文件\u00a7r：把 .nbt / .snbt / .litematic / .schem / .json 图纸放进 "
                        + "config/maid_smart/blueprints/ 或存档 schematics/，自动注册进手册（放入即用）；",
                "④ \u00a7eAI 设计器\u00a7r：对话「设计一个中式庭院」——子 AI 设计完存进手册再建造（需 LLM）。",
                "\u00a7e机械类蓝图（农场/熔炉）的搭建顺序\u00a7r：女仆按\u00a7e结构→惰性机构（漏斗/发射器/熔炉/耕地/水）→活动件（活塞）→"
                        + "传感件（侦测器/比较器）→动力源（红石粉/火把/拉杆）\u00a7r六层自下而上搭建——动力源\u00a7e最后\u00a7r落地，"
                        + "避免搭建到一半电路就通电误触（活塞乱推/发射器乱喷），建完一拉杆就能用。",
                "\u00a7e各机械蓝图用法\u00a7r（当前蓝图包里的红石/机械类，按各机器正常用法操作）：",
                "· \u00a7e红石__中世纪刷石机\u00a7r——经典全自动刷石：岩浆+水碰面生成圆石，漏斗运进箱子；",
                "· \u00a7e红石__全树种树场（有红树）\u00a7r——自动种收各树种树苗（含红树），产原木/树苗；",
                "· \u00a7e红石__国风刷铁机——黑金楼\u00a7r——村民刷铁塔，产铁锭；",
                "· \u00a7e红石__优雅的村民交易所\u00a7r——村民交易屋（治疗僵尸村民→转职→交换）；",
                "· \u00a7e红石__彩虹信标 / 心形下界传送门\u00a7r——装饰用红石结构（彩虹渐变信标、心形传送门）；",
                "\u00b7 \u00a7c\u7518\u8517\u7267\u573a\uff08\u5df2\u5931\u6548\uff09\u00a7r\u2014\u2014\u84dd\u56fe\u540d\u5373\u6807\u6ce8\uff1a\u6f0f\u6597\u6536\u4e0d\u5230\u9694\u6ce5\u5df4\u7684\u7518\u8517\u6389\u843d\u7269\uff0c\u80fd\u6536\u5272\u4f46\u6536\u4e0d\u4e86\u8d27\uff0c\u4e0d\u5efa\u8bae\u5efa\u3002",
                "小知识：蓝图要求橡木木板时，你背包里\u00a7e任意木板\u00a7r都算数（材料等价族）；"
                        + "如果连材料等价物都找不到，会尝试使用\u00a7e玩家自定义的替代方块\u00a7r来替换建筑中需要搭建的方块"
                        + "（「模组详细配置 → 生产与工作 → 建造 → 替代品名单」里添加，如缺石砖时用圆石顶上）；"
                        + "「模组详细配置 → 生产与工作 → 建造」还可调速度档位（x1/x1.5/x3，默认 ×1）、极速模式（放置冷却归零）、"
                        + "全局放置配额、图纸流体保留（auto/always/never）、缺料同类宽松（off/machine/always）等。",
                "\u00a7e指标石（即兴临时搭建）\u00a7r另有§e独立章节§r——见手册目录「指标石（即兴临时搭建）」，那里有合成、三步用法与全部规矩。",
                "\u00a7e\u3010\u56fe\u7eb8\u91cc\u7684\u6c34/\u5ca9\u6d46\u600e\u4e48\u529e\u3011\u00a7r\uff1a\u9ed8\u8ba4\u00a7e\u673a\u5668\u56fe\u7eb8\u4fdd\u7559\u3001\u666e\u901a\u5efa\u7b51\u5265\u79bb\u00a7r\u2014\u2014\u300c\u50cf\u4e0d\u50cf\u673a\u5668\u300d\u770b\u4e24\u5904\uff1a\u6587\u4ef6\u540d\u91cc\u7684\u673a\u5668\u5173\u952e\u8bcd\uff08\u6751\u6c11 / \u5206\u7c7b\u673a / \u6253\u5305\u673a / \u4ed3\u5e93 / \u5357\u74dc / \u7518\u8517 / \u94c1\u7827 / \u8f70\u70b8\u673a\uff09\uff0c\u6216\u8005\u56fe\u7eb8\u672c\u8eab\u50cf\u673a\u5668\uff1a\u00a7e\u7ea2\u77f3/\u7269\u6d41\u4ef6 \u22653 \u79cd\u00a7r\uff08\u6d3b\u585e / \u89c2\u5bdf\u8005 / \u6f0f\u6597 / \u6295\u63b7\u5668\u2026\uff09\uff0c\u00a7e\u6216\u8005\u300c\u7ea2\u77f3\u4ef6 + \u7ed3\u6784\u4ef6\u300d\u5404 \u22651 \u79cd / \u7ed3\u6784\u4ef6 \u22652 \u79cd\u00a7r\uff08\u5ca9\u6d46\u5757 / \u51b0 / \u84dd\u51b0 / \u7075\u9b42\u6c99 / \u6c14\u6ce1\u67f1 / \u94c1\u7827 / \u77f3\u5320\u53f0 / \u5806\u80a5\u6876\u8fd9\u7c7b\u2014\u2014\u53f2\u83b1\u59c6\u519c\u573a\u3001\u5237\u51b0\u673a\u8fd9\u79cd\u00a7e\u7eaf\u7269\u7406\u7ed3\u6784\u578b\u673a\u5668\u51e0\u4e4e\u6ca1\u6709\u7ea2\u77f3\u4ef6\u00a7r\uff0c\u5149\u6570\u7ea2\u77f3\u4ef6\u4f1a\u628a\u5b83\u4eec\u8bef\u5224\u6210\u666e\u901a\u5efa\u7b51\uff09\u3002\u00a7e\u5265\u79bb\u65f6\u4f1a\u5199\u660e\u00a7r\uff1a\u8fd0\u884c\u65e5\u5fd7\u4e0e\u300c\u5efa\u9020\u6b64\u56fe\u7eb8\u300d\u7684\u63d0\u793a\u91cc\u90fd\u4f1a\u5199\u660e\u5265\u6389\u4e86\u591a\u5c11\u683c\u6c34/\u5ca9\u6d46\u00a7e\u4ee5\u53ca\u5b83\u7b97\u51fa\u6765\u662f\u4ec0\u4e48\u00a7r\uff08\u4f8b\u5982\u300c\u7ea2\u77f3/\u7269\u6d41\u4ef6 0 \u79cd / \u7ed3\u6784\u4ef6 0 \u79cd\u300d\uff09\uff0c\u5e76\u544a\u8bc9\u4f60\u4e24\u79cd\u6539\u6210\u4fdd\u7559\u7684\u529e\u6cd5\uff08\u6539\u6587\u4ef6\u540d\uff0c\u6216\u628a\u300c\u6a21\u7ec4\u8be6\u7ec6\u914d\u7f6e \u2192 \u751f\u4ea7\u4e0e\u5de5\u4f5c \u2192 \u5efa\u9020 \u2192 \u56fe\u7eb8\u6d41\u4f53\u4fdd\u7559\u300d\u8bbe\u6210 \u00a7ealways\u00a7r\uff1b\u00a7enever\u00a7r = \u4e00\u5f8b\u5265\u79bb\uff09\u3002",
                "\u00a7e\u3010\u7f3a\u4e00\u4e2a\u989c\u8272\u5c31\u6574\u53f0\u5361\u4f4f\uff1f\u3011\u00a7r\uff1a\u7f3a\u6599\u65f6\u6750\u6599\u5339\u914d\u9ed8\u8ba4\u00a7e\u5efa\u7b51\u4e25\u683c\u3001\u673a\u5668\u653e\u5bbd\u00a7r\uff08\u6863\u4f4d\u5728\u300c\u5efa\u9020 \u2192 \u7f3a\u6599\u540c\u7c7b\u5bbd\u677e\u300d\uff09\u2014\u2014\u673a\u5668\u84dd\u56fe\u91cc\u00a7e\u4efb\u610f\u544a\u793a\u724c\u2194\u4efb\u610f\u544a\u793a\u724c\u3001\u4efb\u610f\u6811\u53f6\u2194\u4efb\u610f\u6811\u53f6\u3001\u4efb\u610f\u7f8a\u6bdb/\u5730\u6bef\u2194\u540c\u7c7b\u578b\u3001\u4efb\u610f\u67d3\u8272\u73bb\u7483/\u73bb\u7483\u677f\u2194\u540c\u7c7b\u578b\u00a7r\uff08\u5b83\u4eec\u53ea\u662f\u6321\u6c34/\u6807\u8bb0/\u906e\u5149\u7684\u529f\u80fd\u4ef6\uff09\uff1b\u623f\u5b50\u8fd9\u7c7b\u56fe\u7eb8\u4fdd\u6301\u4e25\u683c\uff08\u914d\u8272/\u6811\u79cd\u4e0d\u4f1a\u88ab\u52a8\uff09\u3002\u60f3\u8981\u5168\u4e25\u683c\u9009 \u00a7eoff\u00a7r\uff0c\u60f3\u5168\u90fd\u653e\u5bbd\u9009 \u00a7ealways\u00a7r\u3002\u00a7e\u300c\u50cf\u4e0d\u50cf\u673a\u5668\u300d\u4e24\u4e2a\u914d\u7f6e\u9879\u53e3\u5f84\u7edf\u4e00\u00a7r\uff08\u6d41\u4f53\u4fdd\u7559\u4e0e\u7f3a\u6599\u5bbd\u677e\u7528\u540c\u4e00\u628a\u5c3a\u5b50\uff0c\u4e0d\u4f1a\u51fa\u73b0\u300c\u6c34\u7559\u4e0b\u4e86\u3001\u6750\u6599\u5374\u4e0d\u653e\u5bbd\u300d\u7684\u77db\u76fe\uff09\uff1b\u00a7e\u5224\u6210\u975e\u673a\u5668\u56e0\u800c\u6ca1\u653e\u5bbd\u65f6\uff0c\u8fd0\u884c\u65e5\u5fd7\u4f1a\u5199\u660e\u539f\u56e0\u4e0e\u5185\u5bb9\u5224\u5b9a\u00a7r\uff08\u8bf4\u6e05\u695a\u662f\u54ea\u79cd\u4ef6\u6570\u4e0d\u591f\uff0c\u4e0d\u8ba9\u4f60\u731c\uff09\u3002",
                "\u00a7e\u3010\u56fe\u7eb8\u91cc\u6709\u4e9b\u65b9\u5757\u5efa\u4e0d\u51fa\u6765\uff1f\u3011\u00a7r\u56fe\u7eb8\u91cc\u00a7e\u300c\u6e38\u620f\u91cc\u6ca1\u6709\u5bf9\u5e94\u7269\u54c1\u300d\u7684\u65b9\u5757\u4f1a\u8df3\u8fc7\u00a7r\uff08\u706b / \u4f20\u9001\u95e8 / \u6d3b\u585e\u5934 / \u79fb\u52a8\u7684\u6d3b\u585e\u8fd9\u7c7b\u4e0d\u53ef\u83b7\u53d6\u65b9\u5757\uff09\uff0c\u8df3\u8fc7\u4e86\u51e0\u683c\u4f1a\u5199\u5728\u8fd0\u884c\u65e5\u5fd7\u91cc\uff1b\u800c\u00a7e\u5899\u4e0a\u7684\u706b\u628a/\u544a\u793a\u724c/\u6a2a\u5e45\u8fd9\u7c7b\u300c\u72ec\u7acb\u65b9\u5757\u4f46\u6ca1\u6709\u540c\u540d\u7269\u54c1\u300d\u7684\u4e0d\u4f1a\u8df3\u8fc7\u00a7r\u2014\u2014\u5b83\u4eec\u7531\u524d\u8eab\u7269\u54c1\uff08\u7ea2\u77f3\u706b\u628a / \u544a\u793a\u724c\uff09\u5efa\u51fa\u6765\uff0c\u7f3a\u6599\u63d0\u793a\u4e5f\u7167\u7269\u54c1\u540d\u5ff5\uff08\u4e0d\u4f1a\u51fa\u73b0\u641c\u4e0d\u5230\u7684\u300c\u7ea2\u77f3\u5899\u706b\u628a\u300d\uff09\u3002",
                "\u00a7e【材料放箱子里就行】\u00a7r缺料时女仆会去\u00a7e建造区块内（含外扩 4 格）\u00a7r的"
                        + "箱子 / 木桶 / 潜影箱里找缺少的那种材料：走过去 → 开箱（盖子会动）→ 拿走一部分 → 关箱回工地继续盖。"
                        + "末影箱不会被翻；区块外的箱子她不去（不想让她为材料跑远）。开建前算材料也把箱子里的算进去，"
                        + "所以「材料全码在工地边的箱子里」可以直接开建。开关在「建造系统设置」里：从箱子取材料 / 取料扫描外扩 / 每次取多少。");
    }

    static GuideContent.Chapter indexStoneGuide() {
        return new GuideContent.Chapter("指标石（即兴临时搭建）",
                "\u00a7e指标石\u00a7r 是即兴搭建用的§e临时蓝图制作器§r——想临时搭一座桥、一堵墙、一段路，"
                        + "又懒得做蓝图时用它：\u00a7e指哪锁哪，剩下的交给女仆填过去\u00a7r。",
                "\u00a7e合成\u00a7r（工作台）："
                        + "[[#shaped;SSS;SSS;SSS;S=minecraft:smooth_stone;>maid_smart:index_stone;@9 块平滑石头 3×3 摆满]]"
                        + "\n\u00a77平滑石头＝圆石烧一遍\u00a7r。",
                "\u00a7e三步用法\u00a7r",
                "① \u00a7e选方块\u00a7r：手持指标石，你视线指到的方块会显示\u00a7a绿色框\u00a7r（跟着指针走）→ "
                        + "\u00a7e右键\u00a7r把它锁定成\u00a7c红色框\u00a7r。\u00a7e可以锁很远的方块\u00a7r（512 格长射线），但不能锁空气；",
                "② \u00a7e绑女仆\u00a7r：\u00a7e右击你自己的女仆\u00a7r → 提示「绑定成功」"
                        + "（再右击同一只解绑、右击另一只换绑）；",
                "③ \u00a7e她开始填\u00a7r：两件事都做好后，从\u00a7e女仆所在方块到红框之间所有空气方块\u00a7r"
                        + "会显示成\u00a7e橙色幽灵方块\u00a7r，她立刻从那头开始\u00a7e一格一格往红框填\u00a7r。",
                "\u00a7e材料从哪来\u00a7r：\u00a7e不固定\u00a7r——先从她背包拿，不够从\u00a7e你的背包\u00a7r拉；"
                        + "选的是背包里\u00a7e数量最多、有碰撞体积的方块\u00a7r（沙 / 砾石 / TNT / 仙人掌等会被自动排除）；"
                        + "\u00a7e创造模式视为无限材料\u00a7r。",
                "\u00a7e\u7784\u4e0d\u5230\u9501\u5b9a\u65b9\u5757\u4e86\u600e\u4e48\u529e\u00a7r\uff1a\u00a7e\u6f5c\u884c + \u53f3\u952e = \u5f3a\u5236\u89e3\u9501\u00a7r\u2014\u2014\u9501\u5b9a\u683c\u88ab\u6c34/\u5730\u5f62\u6321\u4f4f\u3001\u6216\u8d70\u8fdc\u8d85\u51fa 512 \u683c\u957f\u5c04\u7ebf\u65f6\uff0c\u90fd\u80fd\u8fd9\u6837\u89e3\u9501\uff1b\u53e6\u5916\u00a7e\u5bf9\u7740\u81ea\u5df1\u7684\u5973\u4ec6\u53f3\u952e\u4e00\u6b21\u00a7r\u4e5f\u4f1a\u5237\u65b0\u72b6\u6001\uff0c\u91cd\u8fdb\u4e16\u754c\u540c\u6837\u81ea\u6108\uff08\u9501\u5b9a\u72b6\u6001\u5728\u8fdb\u4e16\u754c\u4e0e\u6362\u7ef4\u5ea6\u65f6\u90fd\u4f1a\u8ddf\u670d\u52a1\u7aef\u91cd\u65b0\u5bf9\u9f50\uff09\u3002",
                "\u00a7e\u51e0\u6761\u89c4\u77e9\u00a7r\uff1a\u00a7e\u5fc5\u987b\u5148\u9501\u65b9\u5757\u3001\u518d\u7ed1\u5973\u4ec6\u00a7r\uff08\u5148\u7ed1\u4f1a\u63d0\u793a\u4e0d\u884c\uff09\uff1b\u4e00\u4e2a\u73a9\u5bb6\u540c\u65f6\u53ea\u80fd\u6709\u00a7e\u4e00\u4e2a\u9501\u5b9a + \u4e00\u4e2a\u7ed1\u5b9a\u00a7r\uff1b\u8fd9\u6b21\u642d\u5efa\u5b8c\u6210\u524d\u4e0d\u80fd\u5f00\u4e0b\u4e00\u4e2a\uff08\u5b8c\u6210\u6216\u53d6\u6d88\u540e\u00a7e\u81ea\u52a8\u8fd8\u539f\u5979\u7684\u539f\u4efb\u52a1\u4e0e\u4f5c\u606f\u00a7r\uff09\uff1b\u60f3\u6362\u76ee\u6807\u65b9\u5757\uff0c\u5fc5\u987b\u00a7e\u5148\u53f3\u51fb\u90a3\u4e2a\u9501\u5b9a\u65b9\u5757\u89e3\u9664\u9501\u5b9a\u00a7r\uff08\u53f3\u51fb\u522b\u5904\u4e0d\u4f1a\u6362\uff09\uff1b\u00a7e\u9a91\u4e58\u4e2d\u6216\u5b88\u5bb6\u6a21\u5f0f\u7684\u5973\u4ec6\u4e0d\u63a5\u7ed1\u5b9a\u00a7r\uff08\u00a7e\u5750\u7740\u7684\u5973\u4ec6\u53ef\u4ee5\u7ed1\u00a7r\u2014\u2014\u5979\u5750\u7740\u65bd\u5de5\u4e0e\u84dd\u56fe\u5efa\u9020\u540c\u6b3e\uff0c\u4e0d\u53d7\u5f71\u54cd\uff09\uff1b\u4e2d\u9014\u88ab\u6536\u56de / \u6b7b\u4ea1 / \u6750\u6599\u4e0d\u591f\u4f1a\u00a7e\u660e\u786e\u62a5\u544a\u642d\u4e86\u591a\u5c11\u5757\u00a7r\u518d\u6536\u5c3e\uff0c\u4e0d\u4f1a\u5361\u6b7b\u3002",
                "\u00a7e顺带提醒\u00a7r：她填的是\u00a7e真实方块\u00a7r（真的从背包消耗），不是投影；"
                        + "总开关在「模组详细配置 → 生产与工作 → 建造 → 指标石」，关掉后指标石退化为普通物品。");
    }

    static GuideContent.Chapter compressionBoxGuide() {
        return new GuideContent.Chapter("压缩盒（5 格 × 每格 114514）",
                "\u00a7e压缩盒\u00a7r 是一个\u00a7e收纳道具\u00a7r：\u00a7e只有 5 格\u00a7r，但\u00a7e每格能堆 "
                        + "\u00a7114514 个\u00a7r（默认上限，可在配置里改）。\u00a7e把它放进女仆背包\u00a7r，"
                        + "里面的东西就\u00a7e算她背包的一部分\u00a7r——她找材料、拿食物、取建材时会一并看盒子里有没有。",
                "\u00a7e外观\u00a7r：木箱本体是\u00a7e樱花木板那一组粉色\u00a7r（照着 1.20.1 原版樱花木板的"
                        + "调色板上的色），\u00a7e锁扣仍是金色\u00a7r——箱子上唯一的对比点，全粉会显得太空；"
                        + "另外它\u00a7e常驻附魔光效\u00a7r（跟排班表／手册同款的做法）：装了多少东西一眼看不出来，"
                        + "有个记号更容易在背包里认出来。",
                "\u00a7e合成\u00a7r（工作台）："
                        + "[[#shaped;PDP;DYD;PDP;P=minecraft:cherry_planks;D=minecraft:diamond;Y=minecraft:pink_dye;>maid_smart:compression_box;@中间粉染料 + 上下左右钻石 + 四角樱花木板]]",
                "\u00a7e怎么用\u00a7r：\u00a7e拿在手上右键\u00a7r打开它的界面（5 个格子 + 你自己的背包）。"
                        + "操作跟\u00a7e往箱子里存东西一样\u00a7r——鼠标上会挂着一叠：",
                "\u00a7e  · 左键点你自己的背包格 = 拿起那一叠\u00a7r（挂在鼠标上）；再\u00a7e左键点盒子格 = 放下\u00a7r"
                        + "（能塞多少塞多少，塞不下的继续挂在手上）；",
                "\u00a7e  · 右键 = 拿 1 个 / 放 1 个\u00a7r（在你自己背包格上按原版习惯\u00a7e拿一半\u00a7r）；",
                "\u00a7e  · Shift+左键 = 快速移动\u00a7r：背包格 → 盒子（整叠存进去）、"
                        + "盒子格 → 背包（最多 64 个）；",
                "\u00a7e  · 手上有东西时点你自己那一格\u00a7r：同物品合并、空格子放下，"
                        + "那一格是\u00a7e别的物品就交换\u00a7r（原版同款）；",
                "\u00a7e  · 点界面空白处 = 手上的东西还回背包\u00a7r（原版是丢到地上，这里保守一点，"
                        + "免得手一滑把东西丢出去）；关界面（ESC）时也会还回去——"
                        + "\u00a7e手上的东西在任何情况下都不会丢\u00a7r。",
                "\u00a7e  · 数量印在格子下面\u00a7r（六位数也看得下）；\u00a7e超过 64 的数用金色\u00a7r标出来，"
                        + "一眼就能看出哪几格是「堆成山」的。",
                "\u00a7e界面里你的背包格和生存物品栏一模一样\u00a7r：格子里\u00a7e画数量、也画耐久条\u00a7r"
                        + "（用的就是原版画这两样东西的那段代码，所以逐像素一致；只有 1 个时数量不写）。"
                        + "盒子里那 5 格的数量是格子下面那行大字（原版右下角的小字放不下六位数），"
                        + "\u00a7e耐久条照画\u00a7r。\u00a7e鼠标停在格子上\u00a7r会显示名字/数量/耐久；"
                        + "按住 Shift 还会说明这一下会发生什么。",
                "\u00a7e  \u00b7 \u00a7e\u538b\u7f29\u76d2\u4e0d\u80fd\u88c5\u8fdb\u538b\u7f29\u76d2\u00a7r\uff1a\u60f3\u628a\u81ea\u5df1\u5b58\u8fdb\u81ea\u5df1\u3001\u6216\u628a\u53e6\u4e00\u4e2a\u76d2\u5b50\u5b58\u8fdb\u6765\uff0c\u90fd\u4f1a\u88ab\u62d2\uff08\u754c\u9762\u4e0a\u90a3\u4e00\u683c\u4f1a\u663e\u793a\u7ea2\u5b57\u3001\u670d\u52a1\u7aef\u4e5f\u62e6\uff09\u3002\u00a7e\u5728\u8fd9\u4e2a\u754c\u9762\u91cc\u9f20\u6807\u9009\u4e0d\u4e2d\u538b\u7f29\u76d2\u00a7r\u2014\u2014\u70b9\u5b83\u65e2\u4e0d\u62ff\u8d77\u3001\u4e5f\u4e0d\u548c\u624b\u4e0a\u7684\u4e1c\u897f\u4ea4\u6362\uff0c\u53ea\u56de\u4f60\u4e00\u53e5\u300c\u538b\u7f29\u76d2\u4e0d\u80fd\u88c5\u8fdb\u538b\u7f29\u76d2\u300d\u3002",
                "\u00a7e  \u00b7 \u00a7e\u5e26\u9644\u9b54\u7684\u4e1c\u897f\u4e5f\u653e\u4e0d\u8fdb\u00a7r\uff1a\u9644\u9b54\u4e66\u3001\u9644\u9b54\u6b66\u5668/\u5de5\u5177/\u76d4\u7532\u2026\u2026\u4e00\u5f8b\u4e0d\u8ba9\u8fdb\u76d2\u5b50\uff0c\u754c\u9762\u91cc\u4e5f\u4f1a\u5f53\u573a\u544a\u8bc9\u4f60\u4e3a\u4ec0\u4e48\u3002\u7406\u7531\u6709\u4e24\u4e2a\uff1a\u2460\u76d2\u5b50\u53ea\u6709 5 \u683c\uff0c\u800c\u9644\u9b54\u7269\u54c1\u00a7e\u4e0d\u53ef\u5806\u53e0\u00a7r\uff08\u4e0d\u540c\u9644\u9b54\u7ec4\u5408\u4e92\u76f8\u4e0d\u662f\u540c\u4e00\u4ef6\u4e1c\u897f\uff0c\u4e00\u683c\u53ea\u80fd\u653e\u4e00\u79cd\u7ec4\u5408\uff09\uff0c\u5f88\u5feb\u5c31\u6ee1\u2014\u2014\u8fd9\u4e2a\u76d2\u5b50\u7684\u5b9a\u4f4d\u662f\u300c\u00a7e\u5927\u6279\u6750\u6599\u7684\u538b\u7f29\u4ed3\u00a7r\u300d\u800c\u4e0d\u662f\u88c5\u5907\u5e93\uff1b\u2461\u9644\u9b54\u7c7b\u6700\u5bb9\u6613\u51fa\u73b0\u5b58\u50a8\u5f02\u5e38\u3002\u00a7e\u8fd9\u4e00\u6761\u6ca1\u6709\u5f00\u5173\u00a7r\uff1a\u5e26\u9644\u9b54\u7684\u7269\u54c1\u4e00\u5f8b\u6321\u5728\u5916\u9762\u3002",
                "\u00a7e女仆那一侧的口径\u00a7r（这一档最需要说清楚的地方）：",
                "\u00a7e  · 她一次最多从盒子里拿 64 个\u00a7r，大堆留在盒子里。这不是小气——"
                        + "1.20.1 的存档把物品数量写成 1 个字节，114514 整堆放进她的背包格会被\u00a7e截断成 82\u00a7r"
                        + "（等于凭空蒸发），所以她的「视野」必须封顶（想看她到底能看见什么："
                        + "\u00a7e/maid_smart box <女仆>\u00a7r 会把「看得见多少 / 实际多少」逐格打出来）；",
                "\u00a7e  \u00b7 \u4e0d\u53ef\u5806\u53e0\u7684\u4e1c\u897f\uff08\u836f\u6c34\u3001\u5de5\u5177\u3001\u76d4\u7532\u3001\u9a6c\u978d\u2026\u2026\uff09\u5979\u4e00\u6b21\u53ea\u770b\u5f97\u89c1 1 \u4e2a\u00a7r\uff1a\u8fd9\u7c7b\u7269\u54c1\u7684\u539f\u7248\u5806\u53e0\u4e0a\u9650\u5c31\u662f 1\uff0c\u4ea4\u7ed9\u5979\u4e00\u4e2a\u300c2 \u4e2a\u7684\u9644\u9b54\u4e66\u300d\u8fd9\u79cd\u00a7e\u975e\u6cd5\u5806\u00a7r\u4f1a\u88ab\u539f\u7248\u53ea\u6536\u4e0b 1 \u4e2a\u3001\u5269\u4e0b\u7684\u4e22\u6389\u2014\u2014\u6240\u4ee5\u5979\u6536\u8fdb\u76d2\u5b50\u65f6\u4f1a\u4e00\u5f8b\u6321\u4e0b\u5e26\u9644\u9b54\u7684\u7269\u54c1\uff0c\u907f\u514d\u8fd9\u7c7b\u635f\u8017\uff1b",
                "\u00a7e  · 往里存则不封顶\u00a7r：她捡到的东西会\u00a7e并进\u00a7r盒子里已有的那一堆"
                        + "（一直堆到每格上限），盒子里也不套盒子；",
                "\u00a7e  \u00b7 \u00a7e\u5979\u4f1a\u5403\u76d2\u5b50\u91cc\u7684\u996d\u00a7r\uff1a\u996d\u3001\u5f39\u836f\u3001TNT\u3001\u5efa\u6750\u2026\u2026\u76d2\u5b50\u91cc\u7684\u4e1c\u897f\u5979\u80fd\u6b63\u5e38\u53d6\u7528\uff08\u997f\u4e86\u4f1a\u81ea\u5df1\u53bb\u76d2\u5b50\u91cc\u62ff\uff09\uff1b",
                "\u00a7e  · 放多个盒子也行\u00a7r：背包里每个压缩盒各贡献 5 格（顺序按她在背包里的格子顺序）。",
                "\u00a7e两条边界\u00a7r：① \u00a7e它不接原版漏斗\u00a7r（漏斗那套按「一格最多 64」算，"
                        + "碰到一格 11 万个的堆会把目标格改写成 64 = 销毁物品；本模组宁可少一个接口也不丢东西）；"
                        + "② \u00a7e它现在不能放置成方块\u00a7r——拿在手上右键就是开界面（潜影箱要放下才能开，"
                        + "这一个反过来更方便）。",
                "\u00a7e可调的只有两条\u00a7r，在「模组详细配置 → 系统与杂项 → 压缩盒」："
                        + "\u00a7e女仆背包延伸\u00a7r（默认开，关掉她就看不见盒子里面的东西）与"
                        + "\u00a7e每格上限\u00a7r（默认 114514，范围 64~1000000）。"
                        + "\u00a7e禁入规则\u00a7r（压缩盒本身 + 带附魔的物品）在这一页有一条"
                        + "「只读」的说明行，\u00a7e不提供开关\u00a7r——写死。",
                "\u00a7e\u6e38\u620f\u5185\u81ea\u68c0\u00a7r\uff1a\u00a7e/maid_smart box check [\u5973\u4ec6]\u00a7r \u4f1a\u628a\u6574\u5957\u5224\u5b9a\u9010\u6761\u8dd1\u4e00\u904d\u5e76\u6253\u5370\u00a7e[PASS]/[FAIL]\u00a7r\u2014\u2014\u300c\u76d2\u5b50\u4e0d\u8bb8\u88c5\u76d2\u5b50\u300d\uff08\u542b\u81ea\u5df1\u90a3\u4e00\u683c\u3001\u53e6\u4e00\u4e2a\u76d2\u5b50\u3001\u4ee5\u53ca\u00a7e\u5bf9\u7167\u00a7r\uff1a\u540c\u4e00\u4e2a\u52a8\u4f5c\u5b58\u666e\u901a\u7269\u54c1\u5fc5\u987b\u6210\u529f\uff09\u3001\u00a7e\u7981\u5165\u53e3\u5f84\u00a7r\uff08\u9644\u9b54\u7269\u54c1\u4e0e\u538b\u7f29\u76d2\u7684\u62d2\u7edd + \u77f3\u5934\u5fc5\u987b\u653e\u884c\uff09\u3001\u00a7e\u9f20\u6807\u9009\u4e0d\u4e2d\u538b\u7f29\u76d2\u00a7r\uff08\u62ff\u8d77\u4e0e\u4ea4\u6362\u4e24\u6761\u8def\uff09\u3001\u00a7e\u4e0d\u53ef\u5806\u53e0\u7269\u54c1\u4ea4\u7ed9\u5979/\u539f\u7248\u7684\u5806\u662f\u5426\u5408\u6cd5\u00a7r\u3001\u00a7e\u7bb1\u5b50\u5f0f\u9f20\u6807\u53d6\u653e\u5168\u6d41\u7a0b\u00a7r\uff08\u62ff\u8d77/\u653e\u4e0b/\u642c\u56de\u6765/\u5feb\u901f\u79fb\u52a8/\u6536\u624b\u4e0d\u4e22\uff09\u3001\u4ee5\u53ca\u00a7e\u300c\u5979\u5403\u996d\u90a3\u6761\u5224\u5b9a\u626b\u4e0d\u626b\u5f97\u5230\u76d2\u5b50\u91cc\u7684\u98df\u7269\u300d\u00a7r\u90fd\u5728\u91cc\u9762\u3002");
    }
}
                                                    