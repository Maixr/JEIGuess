package com.maixr.suggest;

import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientChatReceivedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** 监听聊天消息，把其中提到的物品名记进候选池（自己 + 其他玩家，跳过系统广播）。 */
@Mod.EventBusSubscriber(modid = MaixrSuggestMod.MODID, value = Dist.CLIENT)
public final class ChatWatcher {
    private ChatWatcher() {}

    @SubscribeEvent
    public static void onChat(ClientChatReceivedEvent event) {
        if (!SuggestConfig.ENABLE_CHAT.get()) return;
        if (event.isSystem()) return;
        Component msg = event.getMessage();
        if (msg == null) return;
        String text = msg.getString();
        if (text.isBlank() || text.length() > 512) return;
        for (String name : ItemNameIndex.matchIn(text)) {
            SearchHistoryStore.get().recordChatMention(name);
        }
    }
}
