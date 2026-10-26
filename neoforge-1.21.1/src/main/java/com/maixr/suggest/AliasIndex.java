package com.maixr.suggest;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * ★ 俗称 / 错词 → 游戏内正式物品名 的对照表。
 *
 * 方向约定（很重要）：**键 = 玩家可能输入的俗称或错字，值 = 游戏里的真实物品名**。
 *     土豆 → 马铃薯      鹅卵石 → 圆石
 *     棍子 → 木棍        合成台 → 工作台
 *
 * 反过来写就错了：圆石本身就是游戏物品，不该被"纠"成鹅卵石。
 *
 * 值必须真实存在（运行时用物品名索引校验），对不上就不提示 —— 宁可没有，也不给错的。
 */
public final class AliasIndex {
    private static Map<String, String> map = null;

    private AliasIndex() {}

    public static Map<String, String> get() {
        if (map != null) return map;
        Map<String, String> m = new LinkedHashMap<>();
        loadResource(m, "/jeiguess_aliases.json");
        // 整合包作者/玩家可追加：config/jeiguess-aliases.json（同名键覆盖内置）
        try {
            Path p = net.neoforged.fml.loading.FMLPaths.CONFIGDIR.get().resolve("jeiguess-aliases.json");
            if (Files.exists(p)) {
                int n;
                try (InputStream in = Files.newInputStream(p)) { n = loadStream(m, in); }
                MaixrSuggestMod.LOGGER.info("[猜你想搜] 已载入自定义别名 config/jeiguess-aliases.json：{} 条", n);
            }
        } catch (Throwable t) {
            MaixrSuggestMod.LOGGER.info("[猜你想搜] 载入自定义别名失败: {}", t.toString());
        }
        map = m;
        MaixrSuggestMod.LOGGER.info("[猜你想搜] 别名表共 {} 条", m.size());
        return m;
    }

    /** 查别名：返回正式物品名；目标不在物品名索引里则返回 null。 */
    public static String lookup(String input) {
        if (input == null) return null;
        String k = input.trim().toLowerCase(Locale.ROOT);
        if (k.isEmpty()) return null;
        String v = get().get(k);
        if (v == null || v.isBlank()) return null;
        if (!ItemNameIndex.hasName(v)) return null;
        return v;
    }

    public static int size() { return get().size(); }

    private static void loadResource(Map<String, String> m, String resource) {
        try (InputStream in = AliasIndex.class.getResourceAsStream(resource)) {
            if (in != null) loadStream(m, in);
        } catch (Throwable t) {
            MaixrSuggestMod.LOGGER.info("[猜你想搜] 载入 {} 失败: {}", resource, t.toString());
        }
    }

    private static int loadStream(Map<String, String> m, InputStream in) throws Exception {
        JsonObject j = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
        int n = 0;
        for (Map.Entry<String, JsonElement> e : j.entrySet()) {
            String k = e.getKey().trim().toLowerCase(Locale.ROOT);
            String v = e.getValue().getAsString().trim();
            if (k.isEmpty() || v.isEmpty()) continue;
            m.put(k, v);
            n++;
        }
        return n;
    }
}
