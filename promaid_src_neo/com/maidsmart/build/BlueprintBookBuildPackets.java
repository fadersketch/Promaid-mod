package com.maidsmart.build;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.codec.StreamCodec;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;

/**
 * 蓝图卷轴——建造/目录/投影相关的网络包（v1.2.4 从 BlueprintBookNetworking 拆出）。
 * 
 * 原先是 BlueprintBookNetworking 的嵌套类（public static class），搬出来成为顶层类；
 * 外部引用已全树改写为 BlueprintBookBuildPackets.X。编码/解码逻辑逐字未动。
 */
public final class BlueprintBookBuildPackets {
    private BlueprintBookBuildPackets() {
    }

    public static class CooldownHudPacket implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<CooldownHudPacket> TYPE = new CustomPacketPayload.Type<>(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("maid_smart", "cooldown_hud"));
        public final java.util.List<String[]> entries;

        public CooldownHudPacket(java.util.List<String[]> entries) {
            this.entries = entries == null ? new java.util.ArrayList<>() : entries;
        }

        public static void encode(CooldownHudPacket pkt, FriendlyByteBuf buf) {
            buf.writeUtf(String.valueOf(pkt.entries.size()));
            for (String[] e : pkt.entries) {
                for (int i = 0; i < 4; i++) {
                    buf.writeUtf(e.length > i ? e[i] : "");
                }
            }
        }

        public static CooldownHudPacket decode(FriendlyByteBuf buf) {
            int n = Integer.parseInt(buf.readUtf());
            java.util.List<String[]> list = new java.util.ArrayList<>();
            for (int i = 0; i < n; i++) {
                list.add(new String[]{buf.readUtf(), buf.readUtf(), buf.readUtf(), buf.readUtf()});
            }
            return new CooldownHudPacket(list);
        }

        public static void handle(CooldownHudPacket pkt, IPayloadContext ctx) {
            ctx.enqueueWork(() -> com.maidsmart.client.CooldownHudRenderer.onSnapshot(pkt.entries));
        }

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public static class OpenBookRequestPacket implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<OpenBookRequestPacket> TYPE = new CustomPacketPayload.Type<>(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("maid_smart", "open_book_request"));
        /** 0 = 默认大目录；2 = 女仆管理页（BlueprintBookScreen.VIEW_MAIDS） */
        public final int view;

        public OpenBookRequestPacket(int view) {
            this.view = view;
        }

        public static void encode(OpenBookRequestPacket pkt, FriendlyByteBuf buf) {
            buf.writeInt(pkt.view);
        }

        public static OpenBookRequestPacket decode(FriendlyByteBuf buf) {
            return new OpenBookRequestPacket(buf.readInt());
        }

        public static void handle(OpenBookRequestPacket pkt,
                                  IPayloadContext ctx) {
            ctx.enqueueWork(() -> {
                net.minecraft.server.level.ServerPlayer sp = (ServerPlayer) ctx.player();
                if (sp != null) {
                    net.minecraft.world.item.ItemStack hand = sp.getMainHandItem();
                    // 无论主手是什么都重新打开（openFor 不依赖物品）
                    com.maidsmart.build.BlueprintBookItem.openFor(sp, pkt.view);
                }
            });
            
        }
        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public record Entry(String id, String name, String desc, List<String[]> materials,
                        int sizeX, int sizeY, int sizeZ) {
    }

    public static class OpenBlueprintBookPacket implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<OpenBlueprintBookPacket> TYPE = new CustomPacketPayload.Type<>(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("maid_smart", "open_blueprint_book"));
        public final List<Entry> entries;
        public final List<String[]> maids; // {uuid, 名字, 状态}
        /** v1.5.100b：全部女仆（记忆页用）：{uuid, 名字, 记忆开关 "1"/"0"}——不限距离 */
        public final List<String[]> allMaids;
        public final boolean paused;
        public final String speed;
        /** v1.5.62：建造进度文本（面板内显示，真实放置数） */
        public final String progressText;
        /** v1.5.65：进度百分比（-1 = 无计划；v1.5.162：客户端据此 + 计划区块标记判断控制按钮显示） */
        public final int progress;
        /** v1.5.162：计划区块标记（中心点 + 尺寸；无计划 regionX = Integer.MIN_VALUE） */
        public final int regionX;
        public final int regionY;
        public final int regionZ;
        public final int regionW;
        public final int regionH;
        public final int regionD;
        /** v2.0：玩家是否位于当前计划区块内（区块内右击手册 → 客户端直接进计划详情页） */
        public final boolean inPlanRegion;
        /** v2.0：当前计划的蓝图 id（无计划 = null；客户端据此定位详情页条目） */
        public final String currentPlanId;
        /** v1.5.178：所有有效建造区块 {显示名, 维度名, 状态, 坐标}（女仆管理页区块列表） */
        public final List<String[]> regions;
        /** v1.5.252z：打开手册立即显示——预计完成秒（-1=未知）+ 实时速度（块/秒） */
        public final int etaSec;
        public final String speedBps;
        /** v1.5.275：初始视图（0=大目录 1=女仆管理——配置面板"跳转女仆管理"） */
        public final int initialView;

        public OpenBlueprintBookPacket(List<Entry> entries, List<String[]> maids, List<String[]> allMaids,
                                       boolean paused, String speed, String progressText, int progress,
                                       int regionX, int regionY, int regionZ,
                                       int regionW, int regionH, int regionD,
                                       boolean inPlanRegion, String currentPlanId,
                                       List<String[]> regions, int etaSec, String speedBps,
                                       int initialView) {
            this.entries = entries;
            this.maids = maids;
            this.allMaids = allMaids;
            this.paused = paused;
            this.speed = speed;
            this.progressText = progressText;
            this.progress = progress;
            this.regionX = regionX;
            this.regionY = regionY;
            this.regionZ = regionZ;
            this.regionW = regionW;
            this.regionH = regionH;
            this.regionD = regionD;
            this.inPlanRegion = inPlanRegion;
            this.currentPlanId = currentPlanId;
            this.initialView = initialView;
            this.regions = regions == null ? new ArrayList<>() : regions;
            this.etaSec = etaSec;
            this.speedBps = speedBps == null ? "" : speedBps;
        }

        public static void encode(OpenBlueprintBookPacket pkt, FriendlyByteBuf buf) {
            buf.writeUtf(String.valueOf(pkt.entries.size()));
            for (Entry e : pkt.entries) {
                buf.writeUtf(e.id());
                buf.writeUtf(e.name());
                buf.writeUtf(e.desc());
                buf.writeUtf(String.valueOf(e.sizeX()));
                buf.writeUtf(String.valueOf(e.sizeY()));
                buf.writeUtf(String.valueOf(e.sizeZ()));
                buf.writeUtf(String.valueOf(e.materials() == null ? 0 : e.materials().size()));
                if (e.materials() != null) {
                    for (String[] m : e.materials()) {
                        buf.writeUtf(m[0]);
                        buf.writeUtf(String.valueOf(m[1]));
                        buf.writeUtf(String.valueOf(m[2]));
                    }
                }
            }
            buf.writeUtf(String.valueOf(pkt.maids == null ? 0 : pkt.maids.size()));
            if (pkt.maids != null) {
                for (String[] m : pkt.maids) {
                    buf.writeUtf(m[0]);
                    buf.writeUtf(m[1]);
                    buf.writeUtf(m[2]);
                    buf.writeUtf(m.length > 3 ? m[3] : "0");
                }
            }
            buf.writeUtf(String.valueOf(pkt.allMaids == null ? 0 : pkt.allMaids.size()));
            if (pkt.allMaids != null) {
                for (String[] m : pkt.allMaids) {
                    // v1.5.178：全字段（记忆开关/段落数/任务/绑定/状态/工头）
                    // v1.5.182：第 9 字段 = 绑定 planId；v1.0.3：第 10 字段 = LLM 开关
                    for (int i = 0; i < 10; i++) {
                        buf.writeUtf(m.length > i ? m[i] : "");
                    }
                }
            }
            buf.writeUtf(String.valueOf(pkt.paused));
            buf.writeUtf(pkt.speed == null ? "×1" : pkt.speed);
            buf.writeUtf(pkt.progressText == null ? "" : pkt.progressText);
            buf.writeUtf(String.valueOf(pkt.progress));
            buf.writeUtf(String.valueOf(pkt.regionX));
            buf.writeUtf(String.valueOf(pkt.regionY));
            buf.writeUtf(String.valueOf(pkt.regionZ));
            buf.writeUtf(String.valueOf(pkt.regionW));
            buf.writeUtf(String.valueOf(pkt.regionH));
            buf.writeUtf(String.valueOf(pkt.regionD));
            // v2.0：玩家是否在计划区块内 + 当前计划蓝图 id（区块内右击 → 详情页）
            buf.writeUtf(String.valueOf(pkt.inPlanRegion));
            buf.writeUtf(pkt.currentPlanId == null ? "" : pkt.currentPlanId);
            // v1.5.178：有效建造区块列表（v1.5.180：11 字段含 planId/尺寸/蓝图 id）
            buf.writeUtf(String.valueOf(pkt.regions == null ? 0 : pkt.regions.size()));
            if (pkt.regions != null) {
                for (String[] r : pkt.regions) {
                    // v1.5.290：14 字段（v1.5.279 起 regions 追加创建坐标 r[11..13]，
                    // 旧版写死 11 → 坐标字段永远没发出去 → 客户端 r.length>11 恒 false，
                    // 区块"创建于 x,y,z"从未显示——反馈："显示坐标还是没有做好"）
                    // v1.1.0 实测九十七：15 字段（追加 r[14] 朝向 quarters）
                    for (int i = 0; i < 15; i++) {
                        buf.writeUtf(r.length > i ? r[i] : "");
                    }
                }
            }
            // v1.5.252z：打开手册立即显示速度/ETA（追加在末尾，解码按序读）
            buf.writeUtf(String.valueOf(pkt.etaSec));
            buf.writeUtf(pkt.speedBps);
            // v1.5.275：初始视图（0=大目录 1=女仆管理——配置面板跳转用）
            buf.writeUtf(String.valueOf(pkt.initialView));
        }

        public static OpenBlueprintBookPacket decode(FriendlyByteBuf buf) {
            int size = Integer.parseInt(buf.readUtf());
            List<Entry> entries = new ArrayList<>();
            for (int i = 0; i < size; i++) {
                String id = buf.readUtf();
                String name = buf.readUtf();
                String desc = buf.readUtf();
                int sx = Integer.parseInt(buf.readUtf());
                int sy = Integer.parseInt(buf.readUtf());
                int sz = Integer.parseInt(buf.readUtf());
                int matCount = Integer.parseInt(buf.readUtf());
                List<String[]> mats = new ArrayList<>();
                for (int j = 0; j < matCount; j++) {
                    mats.add(new String[]{buf.readUtf(), buf.readUtf(), buf.readUtf()});
                }
                entries.add(new Entry(id, name, desc, mats, sx, sy, sz));
            }
            int maidCount = Integer.parseInt(buf.readUtf());
            List<String[]> maids = new ArrayList<>();
            for (int i = 0; i < maidCount; i++) {
                maids.add(new String[]{buf.readUtf(), buf.readUtf(), buf.readUtf(), buf.readUtf()});
            }
            int allCount = Integer.parseInt(buf.readUtf());
            List<String[]> allMaids = new ArrayList<>();
            for (int i = 0; i < allCount; i++) {
                // v1.5.178：全字段（记忆开关/段落数/任务/绑定/状态/工头）
                // v1.5.182：第 9 字段 = 绑定 planId；v1.0.3：第 10 字段 = LLM 开关
                allMaids.add(new String[]{buf.readUtf(), buf.readUtf(), buf.readUtf(), buf.readUtf(),
                        buf.readUtf(), buf.readUtf(), buf.readUtf(), buf.readUtf(),
                        buf.readUtf(), buf.readUtf()});
            }
            boolean paused = Boolean.parseBoolean(buf.readUtf());
            String speed = buf.readUtf();
            String progressText = buf.readUtf();
            int progress = Integer.parseInt(buf.readUtf());
            int regionX = Integer.parseInt(buf.readUtf());
            int regionY = Integer.parseInt(buf.readUtf());
            int regionZ = Integer.parseInt(buf.readUtf());
            int regionW = Integer.parseInt(buf.readUtf());
            int regionH = Integer.parseInt(buf.readUtf());
            int regionD = Integer.parseInt(buf.readUtf());
            // v2.0：玩家是否在计划区块内 + 当前计划蓝图 id
            boolean inPlanRegion = Boolean.parseBoolean(buf.readUtf());
            String currentPlanId = buf.readUtf();
            // v1.5.178：有效建造区块列表（v1.5.180：11 字段）
            int regionCount = Integer.parseInt(buf.readUtf());
            List<String[]> regions = new ArrayList<>();
            for (int i = 0; i < regionCount; i++) {
                // v1.5.296：14 字段——v1.5.290 只改了 encode（写 14），decode 漏改仍读 11：
                // (a) 客户端区块永远只有 11 字段 → r[11..13] 创建坐标缺失 →"坐标显示没做出来"；
                // (b) 每个区块剩 3 个坐标字符串错位到后续字段，≥2 个区块时 initialView 读到
                // 蓝图 id（非数字）→ NumberFormatException → 连接损坏 →"连接已丢失"
                //（日志实证 06:21:55 创建第二个区块后开手册即断连）
                String[] rr = new String[15];
                for (int j = 0; j < 15; j++) {
                    rr[j] = buf.readUtf();
                }
                regions.add(rr);
            }
            // v1.5.252z：速度/ETA（与 encode 末尾顺序一致）
            int etaSec = Integer.parseInt(buf.readUtf());
            String speedBps = buf.readUtf();
            // v1.5.275：初始视图（0=大目录 2=女仆管理）
            int initialView = Integer.parseInt(buf.readUtf());
            return new OpenBlueprintBookPacket(entries, maids, allMaids, paused, speed, progressText, progress,
                    regionX, regionY, regionZ, regionW, regionH, regionD,
                    inPlanRegion, currentPlanId, regions, etaSec, speedBps, initialView);
        }

        public static void handle(OpenBlueprintBookPacket pkt, IPayloadContext ctx) {
            ctx.enqueueWork(() -> {
                // v1.5.164：打开手册同步计划区块标记 → 红色固定框（v1.5.180：多框）
                com.maidsmart.build.BlueprintAreaPreview.setRegions(pkt.regions);
                BlueprintBookScreen.open(pkt.entries, pkt.maids, pkt.allMaids, pkt.paused, pkt.speed,
                        pkt.progressText, pkt.progress, pkt.regionX, pkt.regionY, pkt.regionZ,
                        pkt.regionW, pkt.regionH, pkt.regionD,
                        pkt.inPlanRegion, pkt.currentPlanId, pkt.regions,
                        pkt.etaSec, pkt.speedBps, pkt.initialView);
            });
            
        }
        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public static class ProgressUpdatePacket implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<ProgressUpdatePacket> TYPE = new CustomPacketPayload.Type<>(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("maid_smart", "progress_update"));
        public final String progressText;
        public final List<String[]> maids;
        public final boolean paused;
        public final String speed;
        /** v1.5.65：进度百分比（-1 = 无计划） */
        public final int progress;
        /** v1.5.162：进行中计划的区块标记（中心点 + 宽/高/深；无计划时 regionX = Integer.MIN_VALUE）——
         *  客户端据此判定"玩家是否处于建造区块内"（控制按钮只在区块内显示） */
        public final int regionX;
        public final int regionY;
        public final int regionZ;
        public final int regionW;
        public final int regionH;
        public final int regionD;
        /** v1.5.178：全部女仆（女仆管理页——含绑定区块显示名/任务/建筑状态） */
        public final List<String[]> allMaids;
        /** v1.5.178：有效建造区块列表 {显示名, 维度名, 状态, 坐标} */
        public final List<String[]> regions;
        /** v1.5.180：玩家所在区块 planId（无 = 区块外；客户端当前区块上下文） */
        public final String planId;
        /** v1.5.252s：进度条旁显示——预计完成秒（-1 = 未知）+ 实时速度（块/秒） */
        public final int etaSec;
        public final String speedBps;

        public ProgressUpdatePacket(String progressText, List<String[]> maids,
                                    boolean paused, String speed, int progress,
                                    int regionX, int regionY, int regionZ,
                                    int regionW, int regionH, int regionD,
                                    List<String[]> allMaids, List<String[]> regions,
                                    String planId, int etaSec, String speedBps) {
            this.progressText = progressText;
            this.maids = maids;
            this.paused = paused;
            this.speed = speed;
            this.progress = progress;
            this.regionX = regionX;
            this.regionY = regionY;
            this.regionZ = regionZ;
            this.regionW = regionW;
            this.regionH = regionH;
            this.regionD = regionD;
            this.allMaids = allMaids == null ? new ArrayList<>() : allMaids;
            this.regions = regions == null ? new ArrayList<>() : regions;
            this.planId = planId;
            this.etaSec = etaSec;
            this.speedBps = speedBps == null ? "" : speedBps;
        }

        public static void encode(ProgressUpdatePacket pkt, FriendlyByteBuf buf) {
            buf.writeUtf(pkt.progressText == null ? "" : pkt.progressText);
            buf.writeUtf(String.valueOf(pkt.maids == null ? 0 : pkt.maids.size()));
            if (pkt.maids != null) {
                for (String[] m : pkt.maids) {
                    buf.writeUtf(m[0]);
                    buf.writeUtf(m[1]);
                    buf.writeUtf(m[2]);
                    buf.writeUtf(m.length > 3 ? m[3] : "0");
                }
            }
            buf.writeUtf(String.valueOf(pkt.paused));
            buf.writeUtf(pkt.speed == null ? "×1" : pkt.speed);
            buf.writeUtf(String.valueOf(pkt.progress));
            buf.writeUtf(String.valueOf(pkt.regionX));
            buf.writeUtf(String.valueOf(pkt.regionY));
            buf.writeUtf(String.valueOf(pkt.regionZ));
            buf.writeUtf(String.valueOf(pkt.regionW));
            buf.writeUtf(String.valueOf(pkt.regionH));
            buf.writeUtf(String.valueOf(pkt.regionD));
            // v1.5.178：全部女仆 + 有效建造区块（女仆管理页）
            buf.writeUtf(String.valueOf(pkt.allMaids == null ? 0 : pkt.allMaids.size()));
            if (pkt.allMaids != null) {
                for (String[] m : pkt.allMaids) {
                    // v1.5.182：第 9 字段 = 绑定 planId；v1.0.3：第 10 字段 = LLM 开关
                    for (int i = 0; i < 10; i++) {
                        buf.writeUtf(m.length > i ? m[i] : "");
                    }
                }
            }
            buf.writeUtf(String.valueOf(pkt.regions == null ? 0 : pkt.regions.size()));
            if (pkt.regions != null) {
                for (String[] r : pkt.regions) {
                    // v1.5.290：14 字段（v1.5.279 起 regions 追加创建坐标 r[11..13]，
                    // 旧版写死 11 → 坐标字段从未发出去）
                    // v1.1.0 实测九十七：15 字段（追加 r[14] 朝向 quarters）
                    for (int i = 0; i < 15; i++) {
                        buf.writeUtf(r.length > i ? r[i] : "");
                    }
                }
            }
            buf.writeUtf(pkt.planId == null ? "" : pkt.planId);
            // v1.5.252s：进度条旁显示（追加在末尾，解码按序读）
            buf.writeUtf(String.valueOf(pkt.etaSec));
            buf.writeUtf(pkt.speedBps);
        }

        public static ProgressUpdatePacket decode(FriendlyByteBuf buf) {
            String progressText = buf.readUtf();
            int maidCount = Integer.parseInt(buf.readUtf());
            List<String[]> maids = new ArrayList<>();
            for (int i = 0; i < maidCount; i++) {
                maids.add(new String[]{buf.readUtf(), buf.readUtf(), buf.readUtf(), buf.readUtf()});
            }
            boolean paused = Boolean.parseBoolean(buf.readUtf());
            String speed = buf.readUtf();
            int progress = Integer.parseInt(buf.readUtf());
            int regionX = Integer.parseInt(buf.readUtf());
            int regionY = Integer.parseInt(buf.readUtf());
            int regionZ = Integer.parseInt(buf.readUtf());
            int regionW = Integer.parseInt(buf.readUtf());
            int regionH = Integer.parseInt(buf.readUtf());
            int regionD = Integer.parseInt(buf.readUtf());
            // v1.5.178：全部女仆 + 有效建造区块
            int allCount = Integer.parseInt(buf.readUtf());
            List<String[]> allMaids = new ArrayList<>();
            for (int i = 0; i < allCount; i++) {
                // v1.5.182：第 9 字段 = 绑定 planId；v1.0.3：第 10 字段 = LLM 开关
                allMaids.add(new String[]{buf.readUtf(), buf.readUtf(), buf.readUtf(), buf.readUtf(),
                        buf.readUtf(), buf.readUtf(), buf.readUtf(), buf.readUtf(),
                        buf.readUtf(), buf.readUtf()});
            }
            int regionCount = Integer.parseInt(buf.readUtf());
            List<String[]> regions = new ArrayList<>();
            for (int i = 0; i < regionCount; i++) {
                // v1.5.296：14 字段（与 OpenBlueprintBookPacket 同修——v1.5.290 漏改
                // decode：坐标字段缺失 + 多区块时后续字段错位致解析崩溃）
                String[] rr = new String[15];
                for (int j = 0; j < 15; j++) {
                    rr[j] = buf.readUtf();
                }
                regions.add(rr);
            }
            String planId = buf.readUtf();
            // v1.5.252s：进度条旁显示（与 encode 末尾顺序一致）
            int etaSec = Integer.parseInt(buf.readUtf());
            String speedBps = buf.readUtf();
            return new ProgressUpdatePacket(progressText, maids, paused, speed, progress,
                    regionX, regionY, regionZ, regionW, regionH, regionD,
                    allMaids, regions, planId, etaSec, speedBps);
        }

        public static void handle(ProgressUpdatePacket pkt, IPayloadContext ctx) {
            ctx.enqueueWork(() -> {
                // v1.5.164：计划区块标记 → 红色固定框（v1.5.180：多区块由 regions 列表驱动）
                com.maidsmart.build.BlueprintAreaPreview.setRegions(pkt.regions);
                net.minecraft.client.gui.screens.Screen cur = net.minecraft.client.Minecraft.getInstance().screen;
                if (cur instanceof BlueprintBookScreen s) {
                    s.updateStatus(pkt.progressText, pkt.maids, pkt.paused, pkt.speed, pkt.progress,
                            pkt.regionX, pkt.regionY, pkt.regionZ, pkt.regionW, pkt.regionH, pkt.regionD,
                            pkt.allMaids, pkt.regions, pkt.planId, pkt.etaSec, pkt.speedBps);
                }
            });
            
        }
        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public static class BuildHudPacket implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<BuildHudPacket> TYPE = new CustomPacketPayload.Type<>(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("maid_smart", "build_hud"));
        public final java.util.List<String[]> entries;

        public BuildHudPacket(java.util.List<String[]> entries) {
            this.entries = entries == null ? new java.util.ArrayList<>() : entries;
        }

        public static void encode(BuildHudPacket pkt, FriendlyByteBuf buf) {
            buf.writeUtf(String.valueOf(pkt.entries.size()));
            for (String[] e : pkt.entries) {
                for (int i = 0; i < 8; i++) {
                    buf.writeUtf(e.length > i ? e[i] : "");
                }
            }
        }

        public static BuildHudPacket decode(FriendlyByteBuf buf) {
            int n = Integer.parseInt(buf.readUtf());
            java.util.List<String[]> list = new java.util.ArrayList<>();
            for (int i = 0; i < n; i++) {
                list.add(new String[]{buf.readUtf(), buf.readUtf(), buf.readUtf(), buf.readUtf(),
                        buf.readUtf(), buf.readUtf(), buf.readUtf(), buf.readUtf()});
            }
            return new BuildHudPacket(list);
        }

        public static void handle(BuildHudPacket pkt, IPayloadContext ctx) {
            ctx.enqueueWork(() -> com.maidsmart.build.BuildHudRenderer.onSnapshot(pkt.entries));
            
        }
        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public static class SelectBlueprintPacket implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<SelectBlueprintPacket> TYPE = new CustomPacketPayload.Type<>(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("maid_smart", "select_blueprint"));
        public final String blueprintId;
        /** v1.1.0 实测九十七：朝向（0~3 × 90° 顺时针）——金色预览按 P 选定后携带 */
        public final int quarters;

        public SelectBlueprintPacket(String blueprintId, int quarters) {
            this.blueprintId = blueprintId;
            this.quarters = quarters;
        }

        public static void encode(SelectBlueprintPacket pkt, FriendlyByteBuf buf) {
            buf.writeUtf(pkt.blueprintId);
            buf.writeUtf(String.valueOf(pkt.quarters));
        }

        public static SelectBlueprintPacket decode(FriendlyByteBuf buf) {
            String id = buf.readUtf();
            int q = 0;
            try {
                q = Integer.parseInt(buf.readUtf());
            } catch (NumberFormatException ignored) {
            }
            return new SelectBlueprintPacket(id, q);
        }

        public static void handle(SelectBlueprintPacket pkt, IPayloadContext ctx) {
            ctx.enqueueWork(() -> {
                ServerPlayer player = (ServerPlayer) ctx.player();
                if (player == null || !(player.level() instanceof net.minecraft.server.level.ServerLevel level)) {
                    return;
                }
                // v1.5.180：创建区块【不需要女仆在场】——以玩家脚下为原点创建
                //（手册点击 = 明确意图：材料不足时直接先建材料够的部分）
                BlueprintBuildExecutor.Outcome outcome = BlueprintBuildExecutor.execute(
                        level, player.blockPosition(), pkt.blueprintId, true, player,
                        Math.floorMod(pkt.quarters, 4));
                String bubble;
                switch (outcome.type()) {
                    case BlueprintBuildExecutor.TYPE_OK -> bubble = outcome.message();
                    case BlueprintBuildExecutor.TYPE_SHORTFALL -> bubble = "材料不够……" + outcome.message();
                    case BlueprintBuildExecutor.TYPE_OBSTACLE -> bubble = "这个地方有障碍物……" + outcome.message();
                    // v1.5.180：重叠拒绝（创建区块唯一硬性要求）
                    case BlueprintBuildExecutor.TYPE_OVERLAP -> bubble = "\u00a7c" + outcome.message();
                    case BlueprintBuildExecutor.TYPE_BUSY -> bubble = "重复建造被拒绝：" + outcome.message();
                    default -> bubble = "这个蓝图我打不开……";
                }
                // v1.5.164：下达成功/失败都立即推送计划区块标记（红色固定框立刻出现，
                // 不等 2 秒轮询——被拒时玩家立刻看到已有区块在哪）
                BlueprintBookNetworking.sendProgressUpdate(player);
                player.sendSystemMessage(net.minecraft.network.chat.Component.literal("\u00a7f" + bubble));
            });
            
        }
        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public static class BuildControlPacket implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<BuildControlPacket> TYPE = new CustomPacketPayload.Type<>(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("maid_smart", "build_control"));
        public static final int JOIN_ALL = 0;
        public static final int TOGGLE_PAUSE = 1;
        public static final int CYCLE_SPEED = 2;
        // v1.5.162：FORCE_RESUME(3) 强制续建已删除——续建走暂停/继续，重复下达被拒绝
        public static final int SHOW_PROGRESS = 4;
        public static final int CANCEL = 5;
        public static final int TOGGLE_MAID = 6;
        public static final int SET_FOREMAN = 7; // v1.5.69：手动设定工头
        // v1.5.162：FORCE_BUILD(8) 强制建造已删除——建造默认强制执行（渲染给出范围 = 玩家选择）
        // v1.5.178：BIND_MAID(8) / UNBIND_MAID(9) 女仆-区块绑定操作（女仆管理页，无位置限制——
        // 本质是女仆任务切换；其余区块控制类操作必须站在区块内）
        public static final int BIND_MAID = 8;
        public static final int UNBIND_MAID = 9;

        public final int action;
        public final String maidUuid; // TOGGLE_MAID 用
        /** v1.5.180：目标区块 planId（控制/绑定指定区块；无 = 兼容旧调用） */
        public final String planId;

        public BuildControlPacket(int action, String maidUuid) {
            this(action, maidUuid, null);
        }

        public BuildControlPacket(int action, String maidUuid, String planId) {
            this.action = action;
            this.maidUuid = maidUuid;
            this.planId = planId;
        }

        public static void encode(BuildControlPacket pkt, FriendlyByteBuf buf) {
            buf.writeUtf(String.valueOf(pkt.action));
            buf.writeUtf(pkt.maidUuid == null ? "" : pkt.maidUuid);
            buf.writeUtf(pkt.planId == null ? "" : pkt.planId);
        }

        public static BuildControlPacket decode(FriendlyByteBuf buf) {
            return new BuildControlPacket(Integer.parseInt(buf.readUtf()), buf.readUtf(), buf.readUtf());
        }

        public static void handle(BuildControlPacket pkt, IPayloadContext ctx) {
            ctx.enqueueWork(() -> {
                ServerPlayer player = (ServerPlayer) ctx.player();
                if (player == null || !(player.level() instanceof net.minecraft.server.level.ServerLevel level)) {
                    return;
                }
                // v1.5.180：目标区块解析（控制/绑定指定区块）
                // v1.5.183：UNBIND 解绑不针对具体区块（planId 允许为空），不要求 target
                BuildPlan.PlanState target = BuildPlan.getPlanById(pkt.planId);
                if (pkt.action != SHOW_PROGRESS && pkt.action != UNBIND_MAID && target == null) {
                    // v1.5.252ae：planId 为空（客户端未定位到区块）提示更准确——
                    // 旧版一律"区块不存在"（实测：区块明明存在却提示不存在）
                    player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                            (pkt.planId == null || pkt.planId.isEmpty())
                                    ? "\u00a7c请先站在建造区块内再操作（区块外无法定位区块）。"
                                    : "\u00a7c区块不存在（可能已被取消/完成）。"));
                    return;
                }
                // v1.5.178：区块内控制限制——暂停/继续/取消/速度/全员加入/逐只暂停/设工头
                // 必须站在【目标区块】内才有用；绑定/解绑是女仆任务切换（无位置限制），
                // SHOW_PROGRESS 是只读轮询（不限制）
                boolean needsRegion = switch (pkt.action) {
                    case JOIN_ALL, TOGGLE_PAUSE, CYCLE_SPEED, CANCEL, TOGGLE_MAID, SET_FOREMAN -> true;
                    default -> false;
                };
                if (needsRegion) {
                    BuildPlan.PlanState here = BlueprintBookNetworking.findPlayerPlan(level, player);
                    if (here == null || !here.planId.equals(target.planId)) {
                        player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                                "\u00a7c必须站在该建造区块内才能操作（暂停/继续/取消/速度/全员加入）。"));
                        return;
                    }
                }
                switch (pkt.action) {
                    case JOIN_ALL -> {
                        int n = BuildPlan.joinAll(level, player, target.planId);
                        player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                                "\u00a7a已同步绑定 " + n + " 只建造女仆到「" + target.name + "」。"));
                    }
                    case TOGGLE_PAUSE -> {
                        boolean paused = BuildPlan.togglePause(level, target);
                        player.sendSystemMessage(net.minecraft.network.chat.Component.literal(paused
                                ? "\u00a7e「" + target.name + "」已暂停——女仆们停下等待。"
                                : "\u00a7a「" + target.name + "」已恢复——女仆们继续建造。"));
                    }
                    case CYCLE_SPEED -> player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                            "\u00a7a建造速度已切换为 " + MaidBuildBehavior.cycleSpeed()
                                    + "。\u8b66：极速时服务器负载明显升高。"));
                    case SHOW_PROGRESS -> {
                        // v1.5.63：静默——进度由 ProgressUpdatePacket 在手册面板内
                        // 实时显示（客户端每 2 秒轮询触发，无需退出手册/按键）
                    }
                    case CANCEL -> {
                        BuildPlan.cancel(level, target.planId, player);
                        player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                                "\u00a7c已取消区块「" + target.name + "」（已建方块保留，重下达自动续建）。"));
                    }
                    case TOGGLE_MAID -> {
                        EntityMaid maid = findMaidByUuid(player, pkt.maidUuid);
                        if (maid == null) {
                            player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                                    "\u00a7c找不到这只女仆（可能已离开范围）。"));
                            return;
                        }
                        // 审计 P-2：暂停/恢复他人女仆需主人或 OP
                        if (!maid.isOwnedBy(player) && !player.hasPermissions(2)) {
                            player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                                    "\u00a7c只能操作自己的女仆（或 OP）。"));
                            return;
                        }
                        boolean nowPaused = !BuildPlan.isMaidPaused(maid);
                        BuildPlan.setMaidPaused(maid, nowPaused);
                        player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                                (nowPaused ? "\u00a7e已暂停 " : "\u00a7a已恢复 ")
                                        + maid.getDisplayName().getString() + "的建造。"));
                    }
                    case SET_FOREMAN -> {
                        // v1.5.69：手动设定工头（建造反馈统一由其发出）
                        EntityMaid fm = findMaidByUuid(player, pkt.maidUuid);
                        if (fm == null) {
                            player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                                    "\u00a7c找不到这只女仆（可能已离开范围）。"));
                            return;
                        }
                        // 审计 P-2：设他人女仆为工头需主人或 OP
                        if (!fm.isOwnedBy(player) && !player.hasPermissions(2)) {
                            player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                                    "\u00a7c只能操作自己的女仆（或 OP）。"));
                            return;
                        }
                        BuildPlan.setForeman(level, target, pkt.maidUuid);
                        player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                                "\u00a7a已设定 " + fm.getDisplayName().getString()
                                        + " 为「" + target.name + "」工头，建造反馈将统一由它发出。"));
                    }
                    case BIND_MAID -> {
                        // v1.5.180：手动绑定——女仆切换到建筑任务并绑定到【指定区块】
                        EntityMaid maid = findMaidByUuid(player, pkt.maidUuid);
                        if (maid == null) {
                            player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                                    "\u00a7c找不到这只女仆（可能已离开）。"));
                            return;
                        }
                        if (!maid.level().dimension().equals(target.dim)) {
                            player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                                    "\u00a7c女仆与区块不在同一维度，无法绑定。"));
                            return;
                        }
                        // 审计 P-2：绑定他人女仆需主人或 OP
                        if (!maid.isOwnedBy(player) && !player.hasPermissions(2)) {
                            player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                                    "\u00a7c只能操作自己的女仆（或 OP）。"));
                            return;
                        }
                        switchTask(maid, "maid_smart:build");
                        BuildPlan.bindMaid(maid, target.planId);
                        BuildPlan.setMaidPaused(maid, false);
                        player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                                "\u00a7a已绑定 " + maid.getDisplayName().getString()
                                        + " 到区块「" + target.name + "」——她将开始建造。"));
                    }
                    case UNBIND_MAID -> {
                        // v1.5.180：手动解绑——女仆切换到空闲任务并解除绑定（不再参与任何区块）
                        EntityMaid maid = findMaidByUuid(player, pkt.maidUuid);
                        if (maid == null) {
                            player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                                    "\u00a7c找不到这只女仆（可能已离开）。"));
                            return;
                        }
                        // 审计 P-2：解绑他人女仆需主人或 OP
                        if (!maid.isOwnedBy(player) && !player.hasPermissions(2)) {
                            player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                                    "\u00a7c只能操作自己的女仆（或 OP）。"));
                            return;
                        }
                        switchTask(maid, "touhou_little_maid:idle");
                        BuildPlan.unbindMaid(maid);
                        BuildPlan.setMaidPaused(maid, false);
                        player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                                "\u00a7e已解绑 " + maid.getDisplayName().getString() + "——她不再参与该区块建造。"));
                    }
                    default -> {
                    }
                }
                // v1.5.62：面板内即时刷新（速度/暂停/进度/女仆状态，无需退出手册看聊天）
                BlueprintBookNetworking.sendProgressUpdate(player);
            });
            
        }

        static EntityMaid findMaidByUuid(net.minecraft.server.level.ServerPlayer player, String uuidStr) {
            if (uuidStr == null || uuidStr.isEmpty() || player == null) {
                return null;
            }
            java.util.UUID uuid;
            try {
                uuid = java.util.UUID.fromString(uuidStr);
            } catch (IllegalArgumentException e) {
                return null;
            }
            // v1.5.187b：玩家周围 128 格扫描（女仆管理页列出的就是 128 格内；
            // 旧版全图 ±3E7 AABB 遍历 visibleChunks 树曾触发死循环卡死游戏）
            net.minecraft.world.phys.AABB box = player.getBoundingBox().inflate(128.0);
            for (EntityMaid m : player.level().getEntitiesOfClass(EntityMaid.class, box)) {
                if (m.getUUID().equals(uuid)) {
                    return m;
                }
            }
            return null;
        }

        /** v1.5.178：切换女仆任务（绑定/解绑用；findTask 失败静默——调用方提示语兜底） */
        private static void switchTask(EntityMaid maid, String uidStr) {
            try {
                com.github.tartaricacid.touhoulittlemaid.entity.task.TaskManager
                        .findTask(net.minecraft.resources.ResourceLocation.parse(uidStr))
                        .ifPresent(maid::setTask);
            } catch (Exception ignored) {
            }
        }
        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public static class ProjectionRequestPacket implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<ProjectionRequestPacket> TYPE = new CustomPacketPayload.Type<>(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("maid_smart", "projection_request"));
        public final String blueprintId;
        public final int quarters;

        public ProjectionRequestPacket(String blueprintId, int quarters) {
            this.blueprintId = blueprintId;
            this.quarters = quarters;
        }

        public static void encode(ProjectionRequestPacket pkt, FriendlyByteBuf buf) {
            buf.writeUtf(pkt.blueprintId == null ? "" : pkt.blueprintId);
            buf.writeUtf(String.valueOf(pkt.quarters));
        }

        public static ProjectionRequestPacket decode(FriendlyByteBuf buf) {
            String id = buf.readUtf();
            int q = 0;
            try {
                q = Integer.parseInt(buf.readUtf());
            } catch (NumberFormatException ignored) {
            }
            return new ProjectionRequestPacket(id, q);
        }

        public static void handle(ProjectionRequestPacket pkt, IPayloadContext ctx) {
            ctx.enqueueWork(() -> {
                ServerPlayer player = (ServerPlayer) ctx.player();
                if (player == null) {
                    return;
                }
                String id = pkt.blueprintId == null ? "" : pkt.blueprintId;
                int q = Math.floorMod(pkt.quarters, 4);
                // v1.1.0 实测八十三b：全程 try/catch + 日志——旧版异常静默（enqueueWork
                // 吞掉堆栈），投影断链无从排查
                try {
                    // v1.1.0 实测九十七：holder 从发送方所在维度取（旋转 BlockState 必需）
                    var holder = player.level().holderLookup(net.minecraft.core.registries.Registries.BLOCK);
                    List<String> centered = BlueprintProjectionSampler.centeredStepsOf(id, q, holder);
                    if (centered == null || centered.isEmpty()) {
                        BlueprintBookNetworking.LOGGER.info("projection: id={} q={} unavailable (missing/empty), reply empty", id, q);
                        PacketDistributor.sendToPlayer(player, new ProjectionDataPacket(id, q, "0,0,0", ""));
                        return;
                    }
                    // v1.1.0 实测九十七：旋转版尺寸不进 SIZE_CACHE（该缓存按 id 存未旋转尺寸）
                    int[] sz = q == 0 ? BlueprintLib.blueprintSizeCached(id, centered)
                            : BlueprintLib.blueprintSize(centered);
                    String cloud = BlueprintProjectionSampler.sampleCloud(id, q, holder);
                    PacketDistributor.sendToPlayer(player, new ProjectionDataPacket(id, q,
                                    sz[0] + "," + sz[1] + "," + sz[2],
                                    cloud));
                    BlueprintBookNetworking.LOGGER.info("projection: id={} q={} size={}x{}x{} chars={} (sent)",
                            id, q, sz[0], sz[1], sz[2], cloud.length());
                } catch (Exception e) {
                    BlueprintBookNetworking.LOGGER.error("projection: generate failed id={} q={}", id, q, e);
                    PacketDistributor.sendToPlayer(player, new ProjectionDataPacket(id, q, "0,0,0", ""));
                }
            });
            
        }
        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    private static final int PROJECTION_CHUNK_CHARS = 16000;

    public static class ProjectionDataPacket implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<ProjectionDataPacket> TYPE = new CustomPacketPayload.Type<>(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("maid_smart", "projection_data"));
        public final String blueprintId;
        /** v1.1.0 实测九十七：朝向（0~3 × 90°）——客户端按 id#quarters 缓存 */
        public final int quarters;
        public final String size;
        public final String cloud;

        public ProjectionDataPacket(String blueprintId, int quarters, String size, String cloud) {
            this.blueprintId = blueprintId;
            this.quarters = quarters;
            this.size = size;
            this.cloud = cloud;
        }

        public static void encode(ProjectionDataPacket pkt, FriendlyByteBuf buf) {
            buf.writeUtf(pkt.blueprintId == null ? "" : pkt.blueprintId);
            buf.writeUtf(String.valueOf(pkt.quarters));
            buf.writeUtf(pkt.size == null ? "0,0,0" : pkt.size);
            String cloud = pkt.cloud == null ? "" : pkt.cloud;
            int chunks = Math.max(1, (cloud.length() + PROJECTION_CHUNK_CHARS - 1)
                    / PROJECTION_CHUNK_CHARS);
            buf.writeUtf(String.valueOf(chunks));
            for (int i = 0; i < chunks; i++) {
                int from = i * PROJECTION_CHUNK_CHARS;
                int to = Math.min(cloud.length(), from + PROJECTION_CHUNK_CHARS);
                buf.writeUtf(cloud.substring(from, to));
            }
        }

        public static ProjectionDataPacket decode(FriendlyByteBuf buf) {
            String id = buf.readUtf();
            int q = 0;
            try {
                q = Integer.parseInt(buf.readUtf());
            } catch (NumberFormatException ignored) {
            }
            String size = buf.readUtf();
            int chunks = Integer.parseInt(buf.readUtf());
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < chunks && i < 64; i++) { // 上限防御：正常 ≤4 块
                sb.append(buf.readUtf());
            }
            return new ProjectionDataPacket(id, q, size, sb.toString());
        }

        public static void handle(ProjectionDataPacket pkt, IPayloadContext ctx) {
            ctx.enqueueWork(() ->
                    com.maidsmart.build.BlueprintAreaPreview.setProjection(
                            pkt.blueprintId, pkt.quarters, pkt.size, pkt.cloud));
            
        }
        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public static class RegionSyncPacket implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<RegionSyncPacket> TYPE = new CustomPacketPayload.Type<>(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("maid_smart", "region_sync"));
        public final List<String[]> regions;

        public RegionSyncPacket(List<String[]> regions) {
            this.regions = regions;
        }

        public static void encode(RegionSyncPacket pkt, FriendlyByteBuf buf) {
            buf.writeUtf(String.valueOf(pkt.regions == null ? 0 : pkt.regions.size()));
            if (pkt.regions != null) {
                for (String[] r : pkt.regions) {
                    // v1.1.0 实测九十七：15 字段（追加 r[14] 朝向 quarters）
                    for (int i = 0; i < 15; i++) {
                        buf.writeUtf(r.length > i ? r[i] : "");
                    }
                }
            }
        }

        public static RegionSyncPacket decode(FriendlyByteBuf buf) {
            int size = Integer.parseInt(buf.readUtf());
            List<String[]> regions = new ArrayList<>();
            for (int i = 0; i < size; i++) {
                String[] r = new String[15];
                for (int j = 0; j < 15; j++) {
                    r[j] = buf.readUtf();
                }
                regions.add(r);
            }
            return new RegionSyncPacket(regions);
        }

        public static void handle(RegionSyncPacket pkt, IPayloadContext ctx) {
            ctx.enqueueWork(() ->
                    com.maidsmart.build.BlueprintAreaPreview.setRegions(pkt.regions));
            
        }
        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public static class PlayJarVoicePacket implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<PlayJarVoicePacket> TYPE = new CustomPacketPayload.Type<>(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("maid_smart", "play_jar_voice"));
        public final int maidId;
        /** jar 内音频文件名（assets/promaid/voice/ 下，如 "build_done.ogg"） */
        public final String file;

        public PlayJarVoicePacket(int maidId, String file) {
            this.maidId = maidId;
            this.file = file;
        }

        public static void encode(PlayJarVoicePacket pkt, FriendlyByteBuf buf) {
            buf.writeInt(pkt.maidId);
            buf.writeUtf(pkt.file == null ? "" : pkt.file);
        }

        public static PlayJarVoicePacket decode(FriendlyByteBuf buf) {
            return new PlayJarVoicePacket(buf.readInt(), buf.readUtf());
        }

        public static void handle(PlayJarVoicePacket pkt, IPayloadContext ctx) {
            ctx.enqueueWork(() ->
                    com.maidsmart.client.PromaidVoiceSoundInstance.playFromPacket(pkt.maidId, pkt.file));
        }

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() { return TYPE; }
    }
}
