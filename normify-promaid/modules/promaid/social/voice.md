---
uid: 045d635e
id: promaid.social.voice
parent: promaid.social
name: {zh: 语音与 TTS, en: "Voice & TTS"}
description:
  zh: >
      所有系统气泡的朗读。四级来源优先级：jar 内置日语包、磁盘系统语音包、语音缓存、TLM TTS 合成落盘。173 个音频文件约 5.7 MB。
  en: >
      Reads out every system bubble. Four-level source priority: the jar-bundled
      Japanese pack, the on-disk system voice pack, the voice cache, then TLM TTS
      synthesis written to disk. 173 audio files, about 5.7 MB.
source:
  - {path: promaid_src_neo/com/maidsmart/voice/SystemTTSManager.java, line: 1, end_line: 324}
  - {path: promaid_src_neo/com/maidsmart/voice/SystemVoicePack.java, line: 1, end_line: 201}
  - {path: promaid_src_neo/com/maidsmart/voice/JarVoicePack.java, line: 1, end_line: 142}
revision: 43cc86d020b9ba3a495013478435099a3c06df4f
updated_at: "2026-10-01T00:00:00Z"
fingerprint: 3a9a0debb2b2ea4e34e090c2ed128024d8520eac344da04457cf84875cdae451
state: active
tags: [voice, audio]
apis:
  - protocol: file
    path: "voice_cache/<sha256>.ogg"
    description: {zh: 合成语音缓存键。, en: Synthesised-voice cache key.}
---

## 语音与 TTS · Voice & TTS

所有系统气泡的朗读。四级来源优先级：jar 内置日语包、磁盘系统语音包、语音缓存、TLM TTS 合成落盘。173 个音频文件约 5.7 MB。

**代码证据**

- `promaid_src_neo/com/maidsmart/voice/SystemTTSManager.java`:1
- `promaid_src_neo/com/maidsmart/voice/SystemVoicePack.java`:1
- `promaid_src_neo/com/maidsmart/voice/JarVoicePack.java`:1
