package com.maixr.suggest;

import com.mojang.logging.LogUtils;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.loading.FMLEnvironment;
import org.slf4j.Logger;

/**
 * JEI Guess —— 猜你想搜（NeoForge 1.21.1）。
 * 点击 JEI 搜索框弹出候选（收藏 / 最近 / 任务 / 常搜 / 上次点开的物品 / 聊天 / 热词 / 推荐），点击即搜。
 *
 * ★ 本类刻意不引用任何客户端类：专用服务端也能装（只为服务端热词）。
 */
@Mod(MaixrSuggestMod.MODID)
public final class MaixrSuggestMod {
    public static final String MODID = "jeiguess";
    public static final Logger LOGGER = LogUtils.getLogger();

    public MaixrSuggestMod(IEventBus modBus, ModContainer container) {
        if (FMLEnvironment.dist.isClient()) {
            SearchHistoryStore.migrateLegacyFiles();          // 旧 mod 名的配置/历史搬过来
            container.registerConfig(ModConfig.Type.CLIENT, SuggestConfig.SPEC);
        }
        // ★ 服务端配置：只为"全服热词"存在，客户端不读它
        container.registerConfig(ModConfig.Type.SERVER, HotWordsConfig.SPEC);
        modBus.addListener(HotWordsNetwork::onRegisterPayloads);
        // ★ 日志带上版本号：以后看日志就能确认玩家到底在跑哪一版
        String ver = "?";
        try {
            ver = ModList.get().getModContainerById(MODID)
                    .map(c -> c.getModInfo().getVersion().toString()).orElse("?");
        } catch (Throwable ignored) {}
        LOGGER.info("[猜你想搜] JEI Guess v{} 已加载（客户端功能 {}）", ver, FMLEnvironment.dist.isClient() ? "启用" : "关闭");
    }
}
