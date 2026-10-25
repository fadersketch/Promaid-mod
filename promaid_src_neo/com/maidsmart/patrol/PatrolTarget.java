package com.maidsmart.patrol;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.phys.Vec3;

/**
 * 一个**独立目标点**（不是轨道）：坐标 + 名字 + 标记时所在维度。
 *
 * <p>与 {@link PatrolRoute} 分开存，是因为语义完全不同：轨道是"一串点围成的闭环"，
 * 目标点是"飞到这一个点去"。界面里两者都按列表管理（同款按钮/改名/删除），
 * 但目标点不需要闭环、不需要净空、不绑女仆。
 *
 * <p>维度记在标记那一刻：玩家在别的维度里点「飞过去」时，服务端/界面都据此
 * 判"跨维度不可用"（只读变暗），不会真的把女仆往异世界送。
 */
public final class PatrolTarget {

    /** 最多几个目标点（与轨道同一个量级，防界面/存档无限长） */
    public static final int MAX_TARGETS = 32;

    private String id = "";
    private String name = "";
    private String dimension = "";
    private double x;
    private double y;
    private double z;

    public PatrolTarget() {
    }

    public PatrolTarget(String name, Vec3 pos, String dimension) {
        this.name = name == null ? "" : name.trim();
        this.dimension = dimension == null ? "" : dimension;
        if (pos != null) {
            this.x = pos.x;
            this.y = pos.y;
            this.z = pos.z;
        }
    }

    public String id() {
        if (id == null || id.isEmpty()) {
            id = PatrolRoute.newId();
        }
        return id;
    }

    public void setId(String v) {
        this.id = v == null ? "" : v;
    }

    public String name() {
        return name == null ? "" : name;
    }

    public void setName(String n) {
        this.name = n == null ? "" : n.trim();
    }

    public String displayName() {
        return name().isEmpty() ? "未命名目标点" : name();
    }

    /** 标记时的维度 id（空 = 未记录） */
    public String dimension() {
        return dimension == null ? "" : dimension;
    }

    public void setDimension(String d) {
        this.dimension = d == null ? "" : d;
    }

    public Vec3 pos() {
        return new Vec3(x, y, z);
    }

    public double x() {
        return x;
    }

    public double y() {
        return y;
    }

    public double z() {
        return z;
    }

    public PatrolTarget copy() {
        PatrolTarget t = new PatrolTarget(name, pos(), dimension);
        t.id = this.id;
        return t;
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putString("id", id());
        tag.putString("name", name());
        tag.putString("dimension", dimension());
        tag.putDouble("x", x);
        tag.putDouble("y", y);
        tag.putDouble("z", z);
        return tag;
    }

    public static PatrolTarget load(CompoundTag tag) {
        PatrolTarget t = new PatrolTarget();
        if (tag == null) {
            return t;
        }
        try {
            t.id = tag.getString("id");
            t.name = tag.getString("name");
            t.dimension = tag.getString("dimension");
            t.x = tag.getDouble("x");
            t.y = tag.getDouble("y");
            t.z = tag.getDouble("z");
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("PatrolTarget", ignored);
        }
        return t;
    }
}
