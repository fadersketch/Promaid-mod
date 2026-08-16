package com.maidsmart.persona;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Properties;

/**
 * 角色包（v1.1.0，借鉴 maidsoulcore CharacterPackage 精简版）。
 *
 * 每女仆一份、位于女仆记忆目录（与 AiMemoryStore 同目录）：
 * - persona.properties   稳定人格种子（id/name/role/coreDrive/coreFear/attachmentStyle/
 *                        hurtStyle/affectionStyle/boundaryStyle/speechPrinciple）
 * - traits.properties    人格参数（0~1：pride/shyness/caretaking/hiddenDependency/
 *                        aggression/forgivenessSpeed/intimacyOpenness/boundarySensitivity）
 * - core_memories.jsonl  角色包核心记忆（type/text/salience/tags，salience 8-10 宪法级）
 *
 * 设计要点（对齐 maidsoulcore 哲学："prompt 只是状态投影，不是人设本体"）：
 * - 与聊天记忆【分离】：聊天/提取/维护永不改写 persona.properties；
 * - 注入时作为【只读投影】：LLM 可以依据，但不会反向修改人格；
 * - 首启自动从 jar 资源生成默认模板（玩家可手改；管理入口见女仆配置·记忆面板）。
 */
public final class PersonaPackage {
    public static final String PERSONA_FILE = "persona.properties";
    public static final String TRAITS_FILE = "traits.properties";
    public static final String CORE_MEMORY_FILE = "core_memories.jsonl";

    /** 已生成过默认模板的目录（防每次 getValue 都重复 stat/写文件） */
    private static final java.util.Set<String> ENSURED =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** properties 解析缓存（目录+mtime → Properties）——记忆面板每帧调 personaName，
     *  无缓存会每帧读盘；mtime 变化自动失效（玩家改文件即时生效） */
    private static final java.util.Map<String, Object[]> PROPS_CACHE =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** 核心记忆库上限（条，超出删 salience 最低——防无限膨胀） */
    private static final int MAX_CORE_MEMORIES = 20;

    private PersonaPackage() {
    }

    /** 核心记忆条目（jsonl 单行，Gson record 组件名序列化） */
    public record CoreMemory(String type, String text, int salience, String tags) {

        static CoreMemory fromJsonLine(String line) {
            try {
                java.util.Map<String, String> m = com.maidsmart.memory.AiMemoryModels.GSON.fromJson(line,
                        new com.google.gson.reflect.TypeToken<java.util.Map<String, String>>() {
                        }.getType());
                if (m == null || m.get("text") == null || m.get("text").isBlank()) {
                    return null;
                }
                int s = 8;
                try {
                    s = Integer.parseInt(m.getOrDefault("salience", "8").trim());
                } catch (NumberFormatException ignored) {
                }
                return new CoreMemory(m.getOrDefault("type", "memory"),
                        m.get("text").trim(),
                        Math.max(1, Math.min(10, s)),
                        m.getOrDefault("tags", ""));
            } catch (Exception ignored) {
                return null;
            }
        }
    }

    /** 首次访问时生成默认模板（缺失才写；每目录只做一次） */
    public static void ensureDefault(Path maidDir) {
        if (maidDir == null) {
            return;
        }
        String key = maidDir.toAbsolutePath().normalize().toString();
        if (!ENSURED.add(key)) {
            return;
        }
        try {
            Files.createDirectories(maidDir);
            writeIfMissing(maidDir.resolve(PERSONA_FILE), PERSONA_FILE);
            writeIfMissing(maidDir.resolve(TRAITS_FILE), TRAITS_FILE);
            writeIfMissing(maidDir.resolve(CORE_MEMORY_FILE), CORE_MEMORY_FILE);
        } catch (Exception ignored) {
        }
    }

    /** 角色包是否已生成（persona.properties 存在） */
    public static boolean exists(Path maidDir) {
        if (maidDir == null) {
            return false;
        }
        return Files.isRegularFile(maidDir.resolve(PERSONA_FILE));
    }

    /** 人格名字（无 persona 或未命名返回 null——GUI 面板/调试用） */
    public static String personaName(Path maidDir) {
        if (maidDir == null) {
            return null;
        }
        Properties p = loadProperties(maidDir.resolve(PERSONA_FILE));
        if (p == null) {
            return null;
        }
        String name = p.getProperty("name", "");
        return name.isBlank() ? null : name.trim();
    }

    /** 核心记忆条数（GUI 面板用） */
    public static int coreMemoryCount(Path maidDir) {
        return coreMemories(maidDir, 100).size();
    }

    /** 核心记忆（按 salience 降序，取前 limit 条；无文件返回空列表） */
    public static List<CoreMemory> coreMemories(Path maidDir, int limit) {
        List<CoreMemory> out = new ArrayList<>();
        if (maidDir == null) {
            return out;
        }
        Path f = maidDir.resolve(CORE_MEMORY_FILE);
        try {
            if (!Files.exists(f)) {
                return out;
            }
            for (String line : Files.readAllLines(f, StandardCharsets.UTF_8)) {
                if (line.isBlank()) {
                    continue;
                }
                CoreMemory c = CoreMemory.fromJsonLine(line);
                if (c != null && c.salience() >= 8) { // 宪法级：仅高重要度进投影
                    out.add(c);
                }
            }
        } catch (Exception ignored) {
            return new ArrayList<>();
        }
        out.sort(Comparator.comparingInt(CoreMemory::salience).reversed());
        return out.size() > limit ? out.subList(0, limit) : out;
    }

    /**
     * v1.2.1：重大关系事件沉淀进角色包核心记忆（core_memories.jsonl，宪法级，
     * 随投影每轮注入）——人设"自我认知"随时间成长，不一成不变。
     * 去重：与现有条目 normalize 内容相同则跳过；上限 MAX_CORE_MEMORIES 条，
     * 超出删 salience 最低。IO 异常静默（记忆系统不能影响游戏运行）。
     */
    public static void appendCoreMemory(Path maidDir, String type, String text,
                                        int salience, String tags) {
        if (maidDir == null || text == null || text.isBlank()) {
            return;
        }
        try {
            Files.createDirectories(maidDir);
            Path f = maidDir.resolve(CORE_MEMORY_FILE);
            java.util.List<CoreMemory> list = new ArrayList<>();
            java.util.List<String> lines = new ArrayList<>();
            String norm = com.maidsmart.memory.AiMemoryModels.normalize(text);
            if (Files.exists(f)) {
                for (String line : Files.readAllLines(f, StandardCharsets.UTF_8)) {
                    if (line.isBlank()) {
                        continue;
                    }
                    CoreMemory c = CoreMemory.fromJsonLine(line);
                    if (c == null) {
                        continue;
                    }
                    if (com.maidsmart.memory.AiMemoryModels.normalize(c.text()).equals(norm)) {
                        return; // 内容已存在，不重复沉淀
                    }
                    list.add(c);
                    lines.add(line);
                }
            }
            int s = Math.max(1, Math.min(10, salience));
            CoreMemory nc = new CoreMemory(type, text.trim(), s, tags);
            list.add(nc);
            lines.add(com.maidsmart.memory.AiMemoryModels.GSON.toJson(nc));
            while (lines.size() > MAX_CORE_MEMORIES) {
                int minIdx = 0;
                for (int i = 1; i < list.size(); i++) {
                    if (list.get(i).salience() < list.get(minIdx).salience()) {
                        minIdx = i;
                    }
                }
                list.remove(minIdx);
                lines.remove(minIdx);
            }
            StringBuilder sb = new StringBuilder();
            for (String l : lines) {
                sb.append(l).append('\n');
            }
            Files.writeString(f, sb.toString(), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
    }

    /**
     * 只读投影块（人格种子 + 人格参数 + 核心记忆）：
     * [人格种子]名字：角色；核心渴望=…；核心恐惧=…；依恋方式=…；说话原则=…
     * [人格参数]害羞0.85 依赖0.80 …；[核心记忆]（重要度9）…
     * 无 persona 返回空串；总长按 maxChars 截断。
     */
    public static String renderPromptBlock(Path maidDir, int coreLimit, int maxChars) {
        return renderPromptBlock(maidDir, coreLimit, maxChars, false);
    }

    /**
     * v1.2.1：supplement=true（TLM 原版已有人设）→ 跳过身份字段（name/role/
     * coreDrive/coreFear/attachmentStyle/hurtStyle/affectionStyle/boundaryStyle/
     * speechPrinciple——身份归属 TLM `## Character Setting`），只渲染
     * [人格参数] + [核心记忆]，块首带补充声明，避免双人设并存冲突；
     * false = 完整渲染（默认，TLM 无人设时用）。
     */
    public static String renderPromptBlock(Path maidDir, int coreLimit, int maxChars, boolean supplement) {
        if (maidDir == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        if (!supplement) {
            Properties props = loadProperties(maidDir.resolve(PERSONA_FILE));
            if (props == null || props.isEmpty()) {
                return "";
            }
            sb.append("[人格种子]");
            String name = props.getProperty("name", "");
            String role = props.getProperty("role", "");
            if (!name.isBlank()) {
                sb.append(name);
            }
            if (!role.isBlank()) {
                sb.append("：").append(role);
            }
            appendProp(sb, "核心渴望", props.getProperty("coreDrive"));
            appendProp(sb, "核心恐惧", props.getProperty("coreFear"));
            appendProp(sb, "依恋方式", props.getProperty("attachmentStyle"));
            appendProp(sb, "说话原则", props.getProperty("speechPrinciple"));
        } else {
            // v1.2.1：补充声明——TLM 人设是身份本体，本块只补 TLM 没有的参数/核心记忆
            sb.append("[角色设定补充]（TLM 角色设定已存在，本块为补充，冲突时以 TLM 设定为准）");
        }
        // 人格参数（紧凑，取自 traits.properties）
        String traits = renderTraits(maidDir);
        if (!traits.isEmpty()) {
            sb.append("；[人格参数]").append(traits);
        }
        // 核心记忆（宪法级，top N；随时间沉淀成长——人设不一成不变）
        List<CoreMemory> core = coreMemories(maidDir, Math.max(1, coreLimit));
        if (!core.isEmpty()) {
            sb.append("；[核心记忆]");
            int n = 0;
            for (CoreMemory c : core) {
                if (n > 0) {
                    sb.append("; ");
                }
                sb.append("（重要度").append(c.salience()).append("）").append(c.text());
                n++;
            }
        }
        String s = sb.toString();
        return s.length() <= maxChars ? s : com.maidsmart.memory.AiMemoryModels.clip(s, maxChars);
    }

    /** traits.properties → "害羞0.85 依赖0.80 …"（值 0~1，两位小数；缺文件返回空串） */
    private static String renderTraits(Path maidDir) {
        Properties p = loadProperties(maidDir.resolve(TRAITS_FILE));
        if (p == null || p.isEmpty()) {
            return "";
        }
        String[][] keys = {
                {"shyness", "害羞"}, {"hiddenDependency", "依赖"}, {"caretaking", "照顾欲"},
                {"intimacyOpenness", "亲密开放"}, {"boundarySensitivity", "边界敏感"},
                {"pride", "自尊"}, {"aggression", "攻击性"}, {"forgivenessSpeed", "原谅速度"}
        };
        StringBuilder sb = new StringBuilder();
        for (String[] kv : keys) {
            String v = p.getProperty(kv[0]);
            if (v == null) {
                continue;
            }
            try {
                double d = Double.parseDouble(v.trim());
                if (sb.length() > 0) {
                    sb.append(" ");
                }
                sb.append(kv[1]).append(String.format(java.util.Locale.ROOT, "%.2f",
                        Math.max(0.0, Math.min(1.0, d))));
            } catch (NumberFormatException ignored) {
            }
        }
        return sb.toString();
    }

    /** 读取 properties（UTF-8 Reader 支持中文；缺文件/异常返回 null；mtime 缓存） */
    private static Properties loadProperties(Path f) {
        try {
            if (!Files.exists(f)) {
                return null;
            }
            String key = f.toAbsolutePath().normalize().toString();
            long mtime = Files.getLastModifiedTime(f).toMillis();
            Object[] cached = PROPS_CACHE.get(key);
            if (cached != null && (Long) cached[0] == mtime) {
                return (Properties) cached[1];
            }
            Properties p = new Properties();
            try (java.io.Reader r = new java.io.InputStreamReader(
                    Files.newInputStream(f), StandardCharsets.UTF_8)) {
                p.load(r);
            }
            PROPS_CACHE.put(key, new Object[]{mtime, p});
            return p;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static void appendProp(StringBuilder sb, String label, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        sb.append("；").append(label).append("=").append(value.trim());
    }

    /** 从 jar 资源复制默认模板（assets/promaid/persona/<name>；资源缺失则跳过） */
    private static void writeIfMissing(Path target, String assetName) {
        try {
            if (Files.exists(target)) {
                return;
            }
            try (InputStream in = PersonaPackage.class.getResourceAsStream(
                    "/assets/promaid/persona/" + assetName)) {
                if (in == null) {
                    return;
                }
                String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                Files.writeString(target, text, StandardCharsets.UTF_8);
            }
        } catch (Exception ignored) {
        }
    }
}
