package com.maixr.suggest;

import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.common.EventBusSubscriber;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 服务端热词统计（只在逻辑服务端跑）。
 *
 * 统计全服玩家上报的搜索词，定期推送给装了本 mod 的客户端，显示成【热词】候选。
 * · 单人游戏不上报也不推送（只对自己有意义，纯属噪音）
 * · 统计是内存态，服务器重启后清零（v1 的已知限制）
 */
@EventBusSubscriber(modid = MaixrSuggestMod.MODID)
public final class HotWordsServer {
    private static final Map<String, Integer> COUNTS = new ConcurrentHashMap<>();
    private static long lastPush = 0L;

    private HotWordsServer() {}

    private static boolean enabled() {
        try { return HotWordsConfig.ENABLED.get(); } catch (Throwable t) { return false; }
    }

    private static int topCount() {
        try { return HotWordsConfig.TOP_COUNT.get(); } catch (Throwable t) { return 5; }
    }

    private static int intervalSeconds() {
        try { return HotWordsConfig.PUSH_INTERVAL_SECONDS.get(); } catch (Throwable t) { return 300; }
    }

    /** 记录一个搜索词（来自客户端上报）。 */
    public static void record(String word) {
        if (!enabled()) return;
        if (word == null) return;
        String w = word.trim();
        if (w.isEmpty() || w.length() > 32) return;
        COUNTS.merge(w, 1, Integer::sum);
    }

    /** 当前热词排行。 */
    public static List<String> top(int n) {
        List<Map.Entry<String, Integer>> list = new ArrayList<>(COUNTS.entrySet());
        list.sort(Comparator.comparingInt((Map.Entry<String, Integer> e) -> e.getValue()).reversed());
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, Integer> e : list) {
            if (out.size() >= n) break;
            out.add(e.getKey());
        }
        return out;
    }

    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!enabled()) return;
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (player.server == null || !player.server.isDedicatedServer()) return;   // 单人游戏不推送
        HotWordsNetwork.sendHotWords(player, top(topCount()));
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (!enabled()) return;
        long now = System.currentTimeMillis();
        if (now - lastPush < intervalSeconds() * 1000L) return;
        lastPush = now;
        try {
            var server = event.getServer();
            if (server == null || !server.isDedicatedServer()) return;
            List<String> words = top(topCount());
            if (words.isEmpty()) return;
            for (ServerPlayer p : server.getPlayerList().getPlayers()) HotWordsNetwork.sendHotWords(p, words);
        } catch (Throwable ignored) {}
    }

    @SubscribeEvent
    public static void onStopped(ServerStoppedEvent event) {
        COUNTS.clear();
        lastPush = 0L;
    }
}
