package com.maixr.suggest;

import com.mojang.logging.LogUtils;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLEnvironment;
import org.slf4j.Logger;

/**
 * JEI Guess —— 猜你想搜。
 * 点击 JEI 搜索框弹出候选（最近搜索 / 任务 / 常搜 / 上次点过的物品 / 聊天 / 热词 / 推荐），点击即搜。
 *
 * ★ 本类刻意不引用任何客户端类：这样专用服务端也能装（只为服务端热词），服务端不会加载渲染相关的类。
 */
@Mod(MaixrSuggestMod.MODID)
public final class MaixrSuggestMod {
    public static final String MODID = "jeiguess";
    public static final Logger LOGGER = LogUtils.getLogger();

    public MaixrSuggestMod(FMLJavaModLoadingContext context) {
        if (FMLEnvironment.dist.isClient()) {
            SearchHistoryStore.migrateLegacyFiles();          // 旧 mod 名的配置/历史搬过来
            context.registerConfig(ModConfig.Type.CLIENT, SuggestConfig.SPEC);
        }
        // ★ 服务端配置：只为"全服热词"存在，客户端不读它
        context.registerConfig(ModConfig.Type.SERVER, HotWordsConfig.SPEC);
        HotWordsNetwork.register();
        // ★ 日志带上版本号：以后看日志就能确认玩家到底在跑哪一版
        String ver = "?";
        try {
            ver = ModList.get().getModContainerById(MODID)
                    .map(c -> c.getModInfo().getVersion().toString()).orElse("?");
        } catch (Throwable ignored) {}
        LOGGER.info("[猜你想搜] JEI Guess v{} 已加载（客户端功能 {}）", ver, FMLEnvironment.dist.isClient() ? "启用" : "关闭");
    }
}
