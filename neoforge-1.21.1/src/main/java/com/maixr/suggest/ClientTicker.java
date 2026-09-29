package com.maixr.suggest;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.common.EventBusSubscriber;
import net.minecraft.core.registries.BuiltInRegistries;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

/**
 * 客户端杂活（★ 单独一个 Dist.CLIENT 类：专用服务端永远不会加载这些客户端类）。
 *
 *   · 分帧建索引（物品名 → 然后聊天链接索引）
 *   · 历史落盘防抖
 *   · 任务书浏览跟踪
 *   · 记录「搜完这个词之后你点开了哪个物品」
 *   · 服务端热词上报（防抖：停手 1.5 秒才算一次搜索）
 */
@EventBusSubscriber(modid = MaixrSuggestMod.MODID, value = Dist.CLIENT)
public final class ClientTicker {
    private static String lastSeenText = "";
    private static long lastChangeAt = 0L;
    private static boolean reported = true;

    private ClientTicker() {}

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        if (!ItemNameIndex.isBuilt()) ItemNameIndex.buildIncrementally();
        else if (!ItemLinkIndex.isBuilt()) ItemLinkIndex.buildIncrementally();
        SearchHistoryStore.get().flushIfDirty();
        QuestWatch.tick();
        tickHotWords();
    }

    /** ★ 搜索词上报：只在停顿 1.5 秒后报一次，避免每敲一个字符就发包。 */
    private static void tickHotWords() {
        try {
            if (!SuggestConfig.SERVER_HOT_WORDS.get()) return;
            String t = JeiBridge.currentText();
            long now = System.currentTimeMillis();
            if (!t.equals(lastSeenText)) { lastSeenText = t; lastChangeAt = now; reported = false; return; }
            if (reported || t.isBlank() || t.length() > 32) return;
            if (now - lastChangeAt < 1500L) return;
            reported = true;
            HotWordsNetwork.reportSearch(t.trim());
        } catch (Throwable ignored) {}
    }

    /**
     * ★ 记录「搜完这个词之后点开了哪个物品」。
     * 用 Post（我们的面板没吃掉这一下点击 = 玩家真的点在 JEI 物品列表上）。
     */
    @SubscribeEvent
    public static void onMouseClick(ScreenEvent.MouseButtonPressed.Post event) {
        if (event.getButton() != 0) return;
        try {
            String q = JeiBridge.currentText();
            if (q == null || q.isBlank()) return;
            if (!JeiBridge.isListDisplayed()) return;
            ItemStack stack = JeiBridge.ingredientUnderMouse();
            if (stack == null || stack.isEmpty()) return;
            ResourceLocation id = BuiltInRegistries.ITEM.getKey(stack.getItem());
            if (id == null) return;
            SearchHistoryStore.get().recordPick(q, id.toString(), stack.getHoverName().getString());
            SearchHistoryStore.get().saveLater();
        } catch (Throwable ignored) {}
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        SearchHistoryStore.get().save();
        HotWordsNetwork.resetServerState();
    }
}
