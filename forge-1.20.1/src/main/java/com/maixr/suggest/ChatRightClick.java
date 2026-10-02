package com.maixr.suggest;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;
import java.util.Optional;

/**
 * ★ 聊天框交互（用户要求）：
 *   左键点聊天里的物品名 = 查看**合成表**（由 ChatHighlighter 的点击事件 + 命令拦截实现）
 *   右键点聊天里的物品名 = 查看**用途表**
 *
 * 为什么要单独一个类：右键在 ChatScreen 里是被吞掉的（原版只处理左键的点击事件），
 * 所以要自己拦 MouseButtonPressed，再用反射调用 ChatScreen 的
 * private Style getComponentStyleAt(double, double) 拿到鼠标下那一段文字的 Style，
 * 从它的 ClickEvent（我们注入的 /jeiguess show <id>）里解析出物品，然后按"用途表"打开。
 *
 * 反射目标在 1.20.1 是私有方法，生产环境会经过 SRG 重映射，所以按名字+描述符逐个尝试。
 */
@Mod.EventBusSubscriber(modid = MaixrSuggestMod.MODID, value = Dist.CLIENT)
public final class ChatRightClick {
    private static MethodHandle styleAt;
    private static boolean resolved = false;

    private ChatRightClick() {}

    private static void resolve() {
        if (resolved) return;
        resolved = true;
        try {
            Class<?> cs = ChatScreen.class;
            Method m = null;
            // 1.20.1 官方映射名
            for (String name : new String[]{"getComponentStyleAt", "m_93797_"}) {
                try {
                    m = cs.getDeclaredMethod(name, double.class, double.class);
                    break;
                } catch (Throwable ignored) {}
            }
            if (m == null) {
                MaixrSuggestMod.LOGGER.info("[猜你想搜] 聊天右键不可用：找不到 getComponentStyleAt");
                return;
            }
            m.setAccessible(true);
            // 用 MethodHandle 绕过模块访问限制
            styleAt = MethodHandles.lookup().unreflect(m);
            MaixrSuggestMod.LOGGER.info("[猜你想搜] 聊天右键已接入（左键合成表 / 右键用途表）");
        } catch (Throwable t) {
            MaixrSuggestMod.LOGGER.info("[猜你想搜] 聊天右键初始化失败: {}", t.toString());
        }
    }

    @SubscribeEvent
    public static void onMouse(ScreenEvent.MouseButtonPressed.Pre event) {
        if (event.getButton() != 1) return;                       // 只处理右键
        if (!(event.getScreen() instanceof ChatScreen chat)) return;
        resolve();
        if (styleAt == null) return;
        try {
            Object styleObj = styleAt.invoke(chat, event.getMouseX(), event.getMouseY());
            if (!(styleObj instanceof Style style)) return;            // 鼠标下不是文字
            var click = style.getClickEvent();
            if (click == null) return;
            String cmd = click.getValue();
            // 只处理我们自己注入的链接（/jeiguess show <id>）
            String prefix = "/" + ChatHighlighter.CMD_PUBLIC + " show ";
            if (cmd == null || !cmd.startsWith(prefix)) return;
            String raw = cmd.substring(prefix.length()).trim().split("\\s+")[0];
            ResourceLocation rl = ResourceLocation.tryParse(raw);
            if (rl == null) return;
            Item item = ForgeRegistries.ITEMS.getValue(rl);
            if (item == null) return;
            // ★ 右键 = 用途表（INPUT）
            JeiLink.show(new ItemStack(item), true);
            MaixrSuggestMod.LOGGER.info("[猜你想搜] 聊天右键 → 打开 {} 的用途表", raw);
            event.setCanceled(true);
        } catch (Throwable t) {
            MaixrSuggestMod.LOGGER.info("[猜你想搜] 聊天右键处理失败: {}", t.toString());
        }
    }
}
