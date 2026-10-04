package com.maidsmart.patrol;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import java.util.ArrayList;
import java.util.List;

/**
 * v1.3.9【巡逻航图 · 书架】一本航图里装**多条航迹** + 当前选中的是哪一条。
 *
 * <p>── 玩家原话（这一版要解决的）──
 * 「打出来的标记在确认完全之后能不能作为一个轨道？……一个航图里面可以保存多个轨道。」
 * 所以物品上存的不再是单条 {@link PatrolRoute}，而是这个容器：
 * <pre>
 *   MaidSmartPatrolBook: {
 *     selected: "a1b2c3",          // 当前选中的轨道 id（空 = 没有）
 *     routes: [ {id,name,closed,clearance,dimension,points:[{x,y,z}...]}, ... ]
 *   }
 * </pre>
 *
 * <p>── 旧存档怎么办（单条 → 多条）──
 * 上一版（v1.3.8）把单条航迹直接写在根标签 {@code MaidSmartPatrol} 下。{@link #loadFrom}
 * 见到旧结构就把它**包成列表里的第一条**，并顺手补一个名字与 id——玩家升级后原来录的那条
 * 还在，不会因为换了格式就丢。这条判据与 {@code BuildArchive} 的 {@code plans} 迁移同款。
 *
 * <p>── 上限 ──
 * 一本航图 {@link #MAX_ROUTES} 条。不设太高：界面一屏列表放得下、存档也不至于膨胀
 * （一条 64 点的航迹序列化后约 2~3 KB）。
 */
public final class PatrolBook {

    /** NBT 根标签（物品 {@code CUSTOM_DATA} 下） */
    public static final String TAG_ROOT = "MaidSmartPatrolBook";
    /** 旧版（v1.3.8）单条航迹的根标签——迁移用，只读不写 */
    public static final String TAG_LEGACY_SINGLE = "MaidSmartPatrol";
    /** 一本航图最多几条轨道 */
    public static final int MAX_ROUTES = 16;

    private final List<PatrolRoute> routes = new ArrayList<>();
    /** 当前选中的那条（id；空 = 无） */
    private String selectedId = "";

    public PatrolBook() {
    }

    /* ==================== 访问 ==================== */

    public List<PatrolRoute> routes() {
        return routes;
    }

    public int size() {
        return routes.size();
    }

    public boolean isEmpty() {
        return routes.isEmpty();
    }

    /** 按 id 找（找不到返回 null） */
    public PatrolRoute byId(String id) {
        if (id == null || id.isEmpty()) {
            return null;
        }
        for (PatrolRoute r : routes) {
            if (id.equals(r.id())) {
                return r;
            }
        }
        return null;
    }

    /** 当前选中的那条（没有/失效 → 退回第一条；一条都没有 → null） */
    public PatrolRoute selected() {
        PatrolRoute r = byId(selectedId);
        if (r != null) {
            return r;
        }
        if (routes.isEmpty()) {
            return null;
        }
        PatrolRoute first = routes.get(0);
        selectedId = first.id();
        return first;
    }

    public String selectedId() {
        return selectedId == null ? "" : selectedId;
    }

    /** 选中某条（按 id；不存在则不动） */
    public boolean select(String id) {
        if (byId(id) == null) {
            return false;
        }
        this.selectedId = id;
        return true;
    }

    /* ==================== 编辑 ==================== */

    /**
     * 新建一条空航迹并选中它。返回 null = 满了。
     *
     * <p>名字按"航迹1 / 航迹2 …"自动编号（跳过已被占用的号），玩家可在详情页改名。
     */
    public PatrolRoute create() {
        if (routes.size() >= MAX_ROUTES) {
            return null;
        }
        PatrolRoute r = new PatrolRoute();
        r.setName(freeName());
        routes.add(r);
        selectedId = r.id();
        return r;
    }

    /** 自动编号名：从"航迹1"起找一个没被占用的 */
    private String freeName() {
        for (int i = 1; i <= MAX_ROUTES + 1; i++) {
            String cand = "航迹" + i;
            boolean used = false;
            for (PatrolRoute r : routes) {
                if (cand.equals(r.name())) {
                    used = true;
                    break;
                }
            }
            if (!used) {
                return cand;
            }
        }
        return "航迹";
    }

    /** 删掉某条（按 id）。返回被删的那条（没有 → null）。若删的是选中的那条，选中自动前移/后移 */
    public PatrolRoute remove(String id) {
        PatrolRoute r = byId(id);
        if (r == null) {
            return null;
        }
        int idx = routes.indexOf(r);
        routes.remove(r);
        if (id.equals(selectedId)) {
            if (routes.isEmpty()) {
                selectedId = "";
            } else {
                selectedId = routes.get(Math.min(idx, routes.size() - 1)).id();
            }
        }
        return r;
    }

    /* ==================== 深拷 ==================== */

    public PatrolBook copy() {
        PatrolBook b = new PatrolBook();
        for (PatrolRoute r : routes) {
            b.routes.add(r.copy());
        }
        b.selectedId = this.selectedId;
        return b;
    }

    /* ==================== NBT ==================== */

    public CompoundTag save() {
        CompoundTag root = new CompoundTag();
        root.m_128359_("selected", selectedId());
        ListTag list = new ListTag();
        for (PatrolRoute r : routes) {
            list.add(r.save());
        }
        root.m_128365_("routes", list);
        return root;
    }

    /** 读新结构；见到旧版单条结构（{@link #TAG_LEGACY_SINGLE}）自动迁移成第一条 */
    public static PatrolBook load(CompoundTag customData) {
        PatrolBook book = new PatrolBook();
        if (customData == null) {
            return book;
        }
        try {
            // ① 新结构
            if (customData.m_128425_(TAG_ROOT, Tag.f_178203_)) {
                readInto(book, customData.m_128469_(TAG_ROOT));
                return book;
            }
            // ② 旧结构（v1.3.8 单条）→ 迁移成第一条，名字/id 顺手补上
            if (customData.m_128425_(TAG_LEGACY_SINGLE, Tag.f_178203_)) {
                PatrolRoute legacy = PatrolRoute.load(customData.m_128469_(TAG_LEGACY_SINGLE));
                legacy.setName(freeLegacyName());
                legacy.id(); // 触发补 id
                book.routes.add(legacy);
                book.selectedId = legacy.id();
            }
        } catch (Throwable ignored) {
        }
        return book;
    }

    /**
     * 读**书本体**——也就是 {@link #save()} 出来的那一份（直接就是 {selected, routes}）。
     *
     * <p>【为什么必须和 {@link #load} 分开】{@code load} 吃的是物品的 {@code CUSTOM_DATA}
     * （书是它下面的一层，键名 {@link #TAG_ROOT}）；而网络包载荷与预览缓存里传的就是**书本体本身**。
     * v1.3.9 之前客户端把书本体喂给 {@code load}，于是"找不到 TAG_ROOT → 兜底也找不到旧结构 →
     * 返回空书"：服务端明明发了 1 条轨道，界面一直收到 0 条（当时靠"每两拍从手上物品读回来"兜着，
     * 才没露馅）。v1.3.9.2 起包载荷走这一条，口径就不再错位了。
     */
    public static PatrolBook loadBare(CompoundTag bookTag) {
        PatrolBook book = new PatrolBook();
        if (bookTag == null) {
            return book;
        }
        try {
            readInto(book, bookTag);
        } catch (Throwable ignored) {
        }
        return book;
    }

    /** 从"书本体"（{selected, routes}）读出选中项与列表——{@link #load} 与 {@link #loadBare} 共用 */
    private static void readInto(PatrolBook book, CompoundTag root) {
        book.selectedId = root.m_128461_("selected");
        Tag raw = root.m_128423_("routes");
        if (raw instanceof ListTag list) {
            for (Tag t : list) {
                if (!(t instanceof CompoundTag ct) || book.routes.size() >= MAX_ROUTES) {
                    continue;
                }
                book.routes.add(PatrolRoute.load(ct));
            }
        }
    }

    private static String freeLegacyName() {
        return "航迹1";
    }
}
