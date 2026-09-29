package com.maixr.suggest;

import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientChatReceivedEvent;
import net.minecraftforge.client.event.RegisterClientCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * 聊天高亮 + 客户端命令。
 *
 * 聊天里出现的物品名 / 物品 id / 标签 / 模组名会被加上下划线并着色，点击打开 JEI：
 *   · 默认        = 合成表
 *   · Shift+点击  = 用途表（相当于按 U）
 *   · Ctrl+点击   = 复制物品 id
 * 修饰键在点击的那一刻读键盘状态，所以不需要任何按键绑定，也不会和别人撞键。
 *
 * 点击命令由 Mixin 拦下本地执行，不会发到服务器（不会刷"未知或不完整的命令"）。
 */
@Mod.EventBusSubscriber(modid = MaixrSuggestMod.MODID, value = Dist.CLIENT)
public final class ChatHighlighter {
    private static final String CMD = "jeiguess";
    private static final TextColor LINK_COLOR = TextColor.fromRgb(0x66CCFF);

    private ChatHighlighter() {}

    @SubscribeEvent
    public static void onChat(ClientChatReceivedEvent event) {
        if (!SuggestConfig.ENABLE_CHAT_LINKS.get()) return;
        if (event.isSystem() && !SuggestConfig.CHAT_LINKS_SYSTEM.get()) return;
        Component msg = event.getMessage();
        if (msg == null) return;
        String plain = msg.getString();
        if (plain.length() < 2 || plain.length() > 512) return;
        if (!ItemLinkIndex.isBuilt()) return;                     // 索引没建好就跳过，别卡聊天
        if (ItemLinkIndex.find(plain).isEmpty()) return;           // 没命中就不重建，零开销
        try {
            event.setMessage(highlight(msg));
        } catch (Throwable t) {
            MaixrSuggestMod.LOGGER.info("[猜你想搜] 重建聊天消息失败: {}", t.toString());
        }
    }

    /** 逐"样式段"处理，保留原格式。 */
    private static Component highlight(Component original) {
        MutableComponent out = Component.empty();
        original.visit((style, text) -> {
            out.append(segment(text, style));
            return Optional.empty();
        }, Style.EMPTY);
        return out;
    }

    private static MutableComponent segment(String text, Style style) {
        // ★ 已经带点击事件的片段一律不动。
        //   典型场景：KubeJS /kubejs hand 的「Item ID (Click to copy)」。
        if (style.getClickEvent() != null && SuggestConfig.CHAT_LINKS_KEEP_EXISTING_CLICKS.get()) {
            return Component.literal(text).withStyle(style);
        }
        List<ItemLinkIndex.Hit> hits = ItemLinkIndex.find(text);
        if (hits.isEmpty()) return Component.literal(text).withStyle(style);
        MutableComponent seg = Component.empty();
        int pos = 0;
        for (ItemLinkIndex.Hit h : hits) {
            if (h.start > pos) seg.append(Component.literal(text.substring(pos, h.start)).withStyle(style));
            ResourceLocation id = ForgeRegistries.ITEMS.getKey(h.stack.getItem());
            if (id == null) { seg.append(Component.literal(text.substring(h.start, h.end)).withStyle(style)); pos = h.end; continue; }
            MutableComponent link = Component.literal(text.substring(h.start, h.end))
                    .withStyle(style.withUnderlined(true).withColor(LINK_COLOR)
                            .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/" + CMD + " show " + id))
                            .withInsertion(text.substring(h.start, h.end)));
            seg.append(link);
            pos = h.end;
        }
        if (pos < text.length()) seg.append(Component.literal(text.substring(pos)).withStyle(style));
        return seg;
    }

    /**
     * 处理"点击链接"产生的命令；返回 true 表示已处理（调用方应取消发送）。
     * 由 Mixin（拦截 sendCommand）与 ClientChatEvent 共用。
     */
    public static boolean tryHandleLinkCommand(String command) {
        if (command == null) return false;
        String c = command.startsWith("/") ? command.substring(1) : command;
        String raw = null;
        if (c.startsWith(CMD + " show ")) raw = c.substring((CMD + " show ").length()).trim();
        else if (c.startsWith("jeiguess_show ")) raw = c.substring("jeiguess_show ".length()).trim();   // 兼容旧写法
        if (raw == null || raw.isEmpty()) return false;
        try {
            ResourceLocation rl = ResourceLocation.tryParse(raw.split("\\s+")[0]);
            if (rl == null) return true;
            Item item = ForgeRegistries.ITEMS.getValue(rl);
            if (item == null) {
                MaixrSuggestMod.LOGGER.info("[猜你想搜] 找不到物品 {}", raw);
                return true;
            }
            // ★ 修饰键在点击的这一刻读键盘状态（ClickEvent 本身带不了修饰键信息）
            boolean shift = Screen.hasShiftDown();
            boolean ctrl = Screen.hasControlDown();
            if (SuggestConfig.CHAT_LINK_MODIFIER_KEYS.get() && ctrl) {
                Minecraft mc = Minecraft.getInstance();
                if (mc.keyboardHandler != null) mc.keyboardHandler.setClipboard(rl.toString());
                if (mc.player != null) {
                    mc.player.displayClientMessage(Component.literal("\u00a7b[JEI Guess] \u00a77已复制物品 ID：\u00a7f" + rl), false);
                }
                return true;
            }
            boolean input = SuggestConfig.CHAT_LINK_MODIFIER_KEYS.get() && shift;
            JeiLink.show(new ItemStack(item), input);
            MaixrSuggestMod.LOGGER.info("[猜你想搜] 点击链接 → 打开 {} 的 JEI {}", raw, input ? "用途表" : "合成表");
        } catch (Throwable t) {
            MaixrSuggestMod.LOGGER.info("[猜你想搜] 打开 JEI 失败({}): {}", raw, t.toString());
        }
        return true;
    }

    @SubscribeEvent
    public static void onClientChat(net.minecraftforge.client.event.ClientChatEvent event) {
        String msg = event.getMessage();
        if (msg == null) return;
        if (!msg.startsWith("/" + CMD + " show ") && !msg.startsWith("/jeiguess_show ")) return;
        event.setCanceled(true);                                   // 关键：拦下来，不发到服务器
        tryHandleLinkCommand(msg);
    }

    /** 客户端命令：/jeiguess show|export|ban|unban|fav|quest|hot|stats */
    @SubscribeEvent
    public static void onRegisterClientCommands(RegisterClientCommandsEvent event) {
        event.getDispatcher().register(Commands.literal(CMD)
                .then(Commands.literal("show")
                        .then(Commands.argument("id", StringArgumentType.string())
                                .executes(ctx -> {
                                    String raw = StringArgumentType.getString(ctx, "id");
                                    tryHandleShow(raw);
                                    return 1;
                                })))
                .then(Commands.literal("export").executes(ctx -> {
                    try {
                        Path p = SearchHistoryStore.get().exportCsv();
                        msg("\u00a7a已导出搜索历史：\u00a7f" + p.toAbsolutePath());
                    } catch (Throwable t) {
                        msg("\u00a7c导出失败：" + t);
                    }
                    return 1;
                }))
                .then(Commands.literal("ban")
                        .then(Commands.argument("word", StringArgumentType.greedyString()).executes(ctx -> {
                            String w = StringArgumentType.getString(ctx, "word");
                            SearchHistoryStore.get().ban(w);
                            msg("\u00a77已屏蔽「" + w + "」，它不会再出现在候选里");
                            return 1;
                        })))
                .then(Commands.literal("unban")
                        .then(Commands.literal("all").executes(ctx -> {
                            List<String> all = SearchHistoryStore.get().allBanned();
                            for (String w : all) SearchHistoryStore.get().unban(w);
                            msg("\u00a77已恢复 " + all.size() + " 个被屏蔽的词");
                            return 1;
                        }))
                        .then(Commands.argument("word", StringArgumentType.greedyString()).executes(ctx -> {
                            String w = StringArgumentType.getString(ctx, "word");
                            boolean ok = SearchHistoryStore.get().unban(w);
                            msg(ok ? "\u00a77已恢复「" + w + "」" : "\u00a7c「" + w + "」不在屏蔽列表里");
                            return 1;
                        })))
                .then(Commands.literal("fav")
                        .then(Commands.argument("word", StringArgumentType.greedyString()).executes(ctx -> {
                            String w = StringArgumentType.getString(ctx, "word");
                            boolean fav = SearchHistoryStore.get().toggleFavorite(w);
                            msg(fav ? "\u00a76已收藏「" + w + "」（候选最上方）" : "\u00a77已取消收藏「" + w + "」");
                            return 1;
                        })))
                .then(Commands.literal("quest").executes(ctx -> {
                    List<SearchHistoryStore.QuestView> views = SearchHistoryStore.get().questViews();
                    if (views.isEmpty()) { msg("\u00a7c还没有任务浏览记录 —— 先去任务书里点开一个任务"); return 0; }
                    SearchHistoryStore.QuestView v = views.get(0);
                    msg("\u00a7b正在查看的任务：\u00a7f" + (v.title.isBlank() ? ("#" + v.id) : v.title)
                            + " \u00a77需要：" + String.join("、", v.items));
                    if (!v.items.isEmpty()) {
                        JeiBridge.applySearch(SuggestionEngine.toQuery(v.items.get(0)));
                        SearchHistoryStore.get().record(SuggestionEngine.toQuery(v.items.get(0)));
                        SearchHistoryStore.get().saveLater();
                    }
                    return 1;
                }))
                .then(Commands.literal("hot").executes(ctx -> {
                    List<String> hot = SearchHistoryStore.get().hotWords(10);
                    msg(hot.isEmpty() ? "\u00a77没有服务器热词（服务端未安装本 mod 或未开启）"
                                      : "\u00a7b服务器热词：\u00a7f" + String.join("、", hot));
                    return 1;
                }))
                .then(Commands.literal("stats").executes(ctx -> {
                    SearchHistoryStore s = SearchHistoryStore.get();
                    msg("\u00a7bJEI Guess \u00a77搜索记录 " + s.allWords().size()
                            + " 条 · 收藏 " + s.allFavorites().size()
                            + " 个 · 屏蔽 " + s.allBanned().size()
                            + " 个 · 任务记录 " + s.questViews().size() + " 条");
                    return 1;
                })));
    }

    private static void tryHandleShow(String raw) {
        try {
            ResourceLocation rl = ResourceLocation.tryParse(raw);
            if (rl == null) return;
            Item item = ForgeRegistries.ITEMS.getValue(rl);
            if (item != null) JeiLink.show(new ItemStack(item), Screen.hasShiftDown());
        } catch (Throwable t) {
            MaixrSuggestMod.LOGGER.info("[猜你想搜] 打开 JEI 配方失败({}): {}", raw, t.toString());
        }
    }

    private static void msg(String s) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) mc.player.displayClientMessage(Component.literal("\u00a7b[JEI Guess] " + s), false);
    }
}
