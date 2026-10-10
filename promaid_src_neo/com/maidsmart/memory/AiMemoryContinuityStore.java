package com.maidsmart.memory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 对话续写队列·崩溃安全（移植自 maidsoulcore v3 {@code ConversationContinuityStore}）。
 *
 * <p>解决的问题：摘要/事实提取是**异步**的（发 LLM 请求 → 回调里写记忆）。若在"请求已发出、
 * 回调未回"之间服务器崩了/被关，这批对话既没写进记忆、水位线也没推进——下次进游戏若水位线
 * 已推进就会永久丢掉这段；若不推进又会重复提取。本类把"待总结的轮"先落盘再发请求，
 * 回调成功才从待办里摘除，于是关服重启后 {@link #nextBatch()} 仍能拿到未完成的批、重发一次。
 *
 * <p>落盘 {@code <store>/continuity.json}：{@code {"pending":[…],"processed":[…]}}。
 * processed 只留最近若干条指纹，防无限增长。线程安全。两树逐字相同。
 */
public final class AiMemoryContinuityStore {

    /** 一轮对话（用户或女仆），带游戏 tick 供重建时间线。 */
    public record Turn(String role, String text, long tick) {
    }

    private static final int MAX_PROCESSED = 128;

    private final Path file;
    private final List<Turn> pending = new ArrayList<>();
    private final Set<String> processed = new LinkedHashSet<>();

    public AiMemoryContinuityStore(Path dir) {
        this.file = dir.resolve("continuity.json");
        load();
    }

    /** 落盘实体（Gson 用）。 */
    private static final class State {
        List<Turn> pending = new ArrayList<>();
        List<String> processed = new ArrayList<>();
    }

    private void load() {
        this.pending.clear();
        this.processed.clear();
        try {
            if (Files.exists(this.file)) {
                State st = AiMemoryModels.GSON.fromJson(
                        Files.readString(this.file, StandardCharsets.UTF_8), State.class);
                if (st != null) {
                    if (st.pending != null) {
                        this.pending.addAll(st.pending);
                    }
                    if (st.processed != null) {
                        this.processed.addAll(st.processed);
                    }
                }
            }
        } catch (Exception ignored) {
        }
    }

    private void save() {
        try {
            Files.createDirectories(this.file.getParent());
            State st = new State();
            st.pending = new ArrayList<>(this.pending);
            st.processed = new ArrayList<>(this.processed);
            Files.writeString(this.file, AiMemoryModels.GSON.toJson(st), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
    }

    /** 发请求前：把这一批轮挂到待办（幂等，同指纹不重复挂）。 */
    public synchronized void enqueue(List<Turn> turns) {
        if (turns == null || turns.isEmpty()) {
            return;
        }
        boolean changed = false;
        for (Turn t : turns) {
            if (t == null || t.text() == null || t.text().isBlank()) {
                continue;
            }
            if (!contains(this.pending, t)) {
                this.pending.add(t);
                changed = true;
            }
        }
        if (changed) {
            save();
        }
    }

    /** 启动/定时补发：取当前待办批次（无则空表）。 */
    public synchronized List<Turn> nextBatch() {
        return new ArrayList<>(this.pending);
    }

    /** 回调成功后：把这一批摘除并记入 processed。 */
    public synchronized void complete(List<Turn> turns) {
        if (turns == null || turns.isEmpty()) {
            return;
        }
        for (Turn t : turns) {
            this.pending.removeIf(p -> sameTurn(p, t));
            this.processed.add(fingerprint(t));
        }
        while (this.processed.size() > MAX_PROCESSED) {
            java.util.Iterator<String> it = this.processed.iterator();
            if (it.hasNext()) {
                it.next();
                it.remove();
            }
        }
        save();
    }

    public synchronized int pendingCount() {
        return this.pending.size();
    }

    /** 清空（清空记忆时同步）。 */
    public synchronized void clear() {
        this.pending.clear();
        this.processed.clear();
        try {
            Files.deleteIfExists(this.file);
        } catch (Exception ignored) {
        }
    }

    private static boolean contains(List<Turn> list, Turn t) {
        for (Turn p : list) {
            if (sameTurn(p, t)) {
                return true;
            }
        }
        return false;
    }

    private static boolean sameTurn(Turn a, Turn b) {
        return a != null && b != null && a.tick() == b.tick()
                && String.valueOf(a.role()).equals(String.valueOf(b.role()))
                && String.valueOf(a.text()).equals(String.valueOf(b.text()));
    }

    private static String fingerprint(Turn t) {
        String s = t.role() + "|" + t.text() + "|" + t.tick();
        return Integer.toHexString(s.hashCode()) + ":" + s.length();
    }
}
