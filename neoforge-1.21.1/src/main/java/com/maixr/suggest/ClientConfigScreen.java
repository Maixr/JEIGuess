package com.maixr.suggest;

import net.neoforged.fml.ModContainer;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;

/**
 * ★ 1.21.1：把「模组 → JEI Guess → 配置」按钮接上。
 *
 * NeoForge 自带 {@link ConfigurationScreen}，它能直接读 ModConfigSpec 自动生成界面，
 * 所以只需要注册一个 IConfigScreenFactory，不必手写 Screen。
 *
 * 注意：本类只允许在客户端加载 —— 专用服务端上没有 client.gui 包。
 * 主类通过反射调用 {@link #register(ModContainer)}，确保服务端不会碰这些类。
 */
public final class ClientConfigScreen {
    private ClientConfigScreen() {}

    public static void register(ModContainer container) {
        container.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);
    }
}
