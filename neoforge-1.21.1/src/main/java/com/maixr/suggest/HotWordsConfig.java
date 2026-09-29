package com.maixr.suggest;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * 服务端配置（serverconfig/jeiguess-server.toml）。
 *
 * ★ 只在逻辑服务端读取：服务端热词是"服务端提供、客户端显示"的功能。
 *   单机时这就是你自己世界的配置；联机时由服务器管理员决定开不开。
 */
public final class HotWordsConfig {
    public static final ModConfigSpec SPEC;
    public static final ModConfigSpec.BooleanValue ENABLED;
    public static final ModConfigSpec.IntValue TOP_COUNT;
    public static final ModConfigSpec.IntValue PUSH_INTERVAL_SECONDS;

    static {
        ModConfigSpec.Builder b = new ModConfigSpec.Builder();
        b.comment("JEI Guess 服务端热词：把全服玩家搜索最多的词同步给装了本 mod 的客户端",
                  "不需要这个功能的服务器可以直接关掉；没装本 mod 的客户端不会收到任何数据").push("hotwords");
        ENABLED = b.comment("是否启用全服热词").define("enabled", true);
        TOP_COUNT = b.comment("同步多少个热词").defineInRange("topCount", 5, 1, 20);
        PUSH_INTERVAL_SECONDS = b.comment("多久推送一次（秒）").defineInRange("pushIntervalSeconds", 300, 30, 3600);
        b.pop();
        SPEC = b.build();
    }

    private HotWordsConfig() {}
}
