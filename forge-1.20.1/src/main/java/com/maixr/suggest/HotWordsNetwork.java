package com.maixr.suggest;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * 服务端热词的网络层。
 *
 * ★ 防冲突/防报错设计（很重要）：
 *   · 客户端**只有收到过服务端的包**之后才会往回发数据 —— 服务端没装本 mod 时，
 *     客户端全程保持沉默，不会产生任何"未注册频道"的报错。
 *   · 服务端推送前用 isRemotePresent() 确认对方装了本 mod，绝不往原版客户端发包。
 *   · 频道版本判定接受 ACCEPTVANILLA，装/不装都不会因为版本不匹配被踢下线。
 */
public final class HotWordsNetwork {
    private static final String VERSION = "1";
    private static SimpleChannel channel;
    private static boolean registered = false;
    /** 客户端：服务端是否装了本 mod（收到过热词包就置 true）。 */
    private static volatile boolean serverHasMod = false;

    private HotWordsNetwork() {}

    public static void register() {
        if (registered) return;
        registered = true;
        try {
            ResourceLocation id = ResourceLocation.tryBuild(MaixrSuggestMod.MODID, "main");
            channel = NetworkRegistry.newSimpleChannel(id,
                    () -> VERSION,
                    v -> VERSION.equals(v) || NetworkRegistry.ACCEPTVANILLA.equals(v),
                    v -> VERSION.equals(v) || NetworkRegistry.ACCEPTVANILLA.equals(v));
            channel.messageBuilder(HotWordsPacket.class, 0, NetworkDirection.PLAY_TO_CLIENT)
                    .encoder(HotWordsPacket::encode)
                    .decoder(HotWordsPacket::new)
                    .consumerMainThread(HotWordsPacket::handle)
                    .add();
            channel.messageBuilder(ReportPacket.class, 1, NetworkDirection.PLAY_TO_SERVER)
                    .encoder(ReportPacket::encode)
                    .decoder(ReportPacket::new)
                    .consumerMainThread(ReportPacket::handle)
                    .add();
            MaixrSuggestMod.LOGGER.info("[猜你想搜] 网络频道已注册（服务端热词）");
        } catch (Throwable t) {
            channel = null;
            MaixrSuggestMod.LOGGER.info("[猜你想搜] 网络频道注册失败（热词功能关闭）: {}", t.toString());
        }
    }

    /** 服务端 → 某个玩家：推送热词（客户端没装就静默跳过）。 */
    public static void sendHotWords(ServerPlayer player, List<String> words) {
        if (channel == null || player == null) return;
        try {
            if (!channel.isRemotePresent(player.connection.connection)) return;   // ★ 原版客户端不发
            channel.send(PacketDistributor.PLAYER.with(() -> player), new HotWordsPacket(words));
        } catch (Throwable ignored) {}
    }

    /** 客户端 → 服务端：上报一次搜索（只有确认过服务端装了本 mod 才发）。 */
    public static void reportSearch(String word) {
        if (channel == null || !serverHasMod) return;
        try {
            channel.sendToServer(new ReportPacket(word));
        } catch (Throwable ignored) {}
    }

    /** 客户端是否已确认服务端装了本 mod。 */
    public static boolean serverHasMod() { return serverHasMod; }

    public static void resetServerState() {
        serverHasMod = false;
        SearchHistoryStore.get().setHotWords(List.of());
    }

    // ───────────────────────── 包 ─────────────────────────

    /** 服务端 → 客户端：当前热词列表。 */
    public static final class HotWordsPacket {
        private final List<String> words = new ArrayList<>();

        public HotWordsPacket(List<String> w) { if (w != null) words.addAll(w); }

        public HotWordsPacket(FriendlyByteBuf buf) {
            int n = buf.readVarInt();
            for (int i = 0; i < n && i < 64; i++) words.add(buf.readUtf(64));
        }

        public void encode(FriendlyByteBuf buf) {
            buf.writeVarInt(words.size());
            for (String w : words) buf.writeUtf(w, 64);
        }

        public void handle(Supplier<NetworkEvent.Context> ctx) {
            NetworkEvent.Context c = ctx.get();
            c.enqueueWork(() -> {
                serverHasMod = true;                       // ★ 服务端装了本 mod（可以安全回传了）
                SearchHistoryStore.get().setHotWords(words);
                MaixrSuggestMod.LOGGER.info("[猜你想搜] 收到服务端热词 {} 条", words.size());
            });
            c.setPacketHandled(true);
        }
    }

    /** 客户端 → 服务端：上报一个搜索词。 */
    public static final class ReportPacket {
        private final String word;

        public ReportPacket(String word) { this.word = word == null ? "" : word; }

        public ReportPacket(FriendlyByteBuf buf) { this.word = buf.readUtf(64); }

        public void encode(FriendlyByteBuf buf) { buf.writeUtf(word, 64); }

        public void handle(Supplier<NetworkEvent.Context> ctx) {
            NetworkEvent.Context c = ctx.get();
            c.enqueueWork(() -> {
                try {
                    ServerPlayer sender = c.getSender();
                    if (sender != null) HotWordsServer.record(word);
                } catch (Throwable ignored) {}
            });
            c.setPacketHandled(true);
        }
    }
}
