package com.maixr.suggest;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

import java.util.ArrayList;
import java.util.List;

/**
 * 服务端热词的网络层（NeoForge 1.21.1 payload API）。
 *
 * ★ 防冲突/防报错设计：
 *   · 注册用 registrar.optional()：对方没装本 mod 时连接依然合法
 *   · 客户端**只有收到过服务端的包**之后才会往回发数据 —— 服务端没装时客户端全程沉默
 *   · 所有发送都包了 try/catch，任何异常都不会影响游戏
 */
public final class HotWordsNetwork {
    /** 客户端：服务端是否装了本 mod（收到过热词包就置 true）。 */
    private static volatile boolean serverHasMod = false;

    private HotWordsNetwork() {}

    public static void onRegisterPayloads(RegisterPayloadHandlersEvent event) {
        try {
            PayloadRegistrar registrar = event.registrar("1").optional();
            registrar.playToClient(HotWordsPayload.TYPE, HotWordsPayload.STREAM_CODEC, HotWordsPayload::handle);
            registrar.playToServer(ReportPayload.TYPE, ReportPayload.STREAM_CODEC, ReportPayload::handle);
            MaixrSuggestMod.LOGGER.info("[猜你想搜] 网络载荷已注册（服务端热词）");
        } catch (Throwable t) {
            MaixrSuggestMod.LOGGER.info("[猜你想搜] 网络载荷注册失败（热词功能关闭）: {}", t.toString());
        }
    }

    /** 服务端 → 某个玩家：推送热词。 */
    public static void sendHotWords(ServerPlayer player, List<String> words) {
        if (player == null) return;
        try {
            PacketDistributor.sendToPlayer(player, new HotWordsPayload(words));
        } catch (Throwable ignored) {}
    }

    /** 客户端 → 服务端：上报一次搜索（只有确认过服务端装了本 mod 才发）。 */
    public static void reportSearch(String word) {
        if (!serverHasMod) return;
        try {
            PacketDistributor.sendToServer(new ReportPayload(word));
        } catch (Throwable ignored) {}
    }

    public static boolean serverHasMod() { return serverHasMod; }

    public static void resetServerState() {
        serverHasMod = false;
        SearchHistoryStore.get().setHotWords(List.of());
    }

    /** 服务端 → 客户端：当前热词列表。 */
    public record HotWordsPayload(List<String> words) implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<HotWordsPayload> TYPE =
                new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(MaixrSuggestMod.MODID, "hot_words"));
        public static final StreamCodec<RegistryFriendlyByteBuf, HotWordsPayload> STREAM_CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list(64)),
                        HotWordsPayload::words,
                        HotWordsPayload::new);

        public HotWordsPayload(List<String> words) {
            this.words = new ArrayList<>(words == null ? List.of() : words);
        }

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() { return TYPE; }

        public void handle(IPayloadContext ctx) {
            ctx.enqueueWork(() -> {
                serverHasMod = true;                       // ★ 服务端装了本 mod（可以安全回传了）
                SearchHistoryStore.get().setHotWords(words);
                MaixrSuggestMod.LOGGER.info("[猜你想搜] 收到服务端热词 {} 条", words.size());
            });
        }
    }

    /** 客户端 → 服务端：上报一个搜索词。 */
    public record ReportPayload(String word) implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<ReportPayload> TYPE =
                new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(MaixrSuggestMod.MODID, "report"));
        public static final StreamCodec<RegistryFriendlyByteBuf, ReportPayload> STREAM_CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.stringUtf8(64),
                        ReportPayload::word,
                        ReportPayload::new);

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() { return TYPE; }

        public void handle(IPayloadContext ctx) {
            ctx.enqueueWork(() -> {
                try {
                    if (ctx.player() != null) HotWordsServer.record(word);
                } catch (Throwable ignored) {}
            });
        }
    }
}
