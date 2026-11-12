package com.maixr.suggest;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * ★ 外挂词库：三种纠正。
 *
 *   整体 whole ：鞋子 → 靴子（目标可以是「能搜到的词」，不要求精确物品名）
 *   部分 part  ：铲 → 锹（输入里包含就替换；最长优先；每条只换一次）
 *   拼音 pinyin：luse → lvse（ü 拼写归一 + 外挂补充）
 *
 * 规则来源 = 内置资源 + config 目录下的文件；**同名规则冲突时两条都保留**，
 * 面板会显示两行，由玩家自己判断。
 *
 * 生效方式：/jeiguess reload
 * 强校验：目标必须真能搜到东西，否则整条忽略（只记日志）。
 */
public final class CorrectionRules {
    private static Map<String, String> wholeIn, forceIn, partIn, pinyinIn;
    private static Map<String, String> wholeEx, forceEx, partEx, pinyinEx;
    private static List<String> partKeys;
    private static volatile boolean loaded = false;

    private CorrectionRules() {}

    /** 重新读取全部词库（内置 + config），并补写缺失的示例文件。 */
    public static synchronized void reload() {
        wholeIn = new LinkedHashMap<>();
        forceIn = new LinkedHashMap<>();
        partIn = new LinkedHashMap<>();
        pinyinIn = new LinkedHashMap<>();
        wholeEx = new LinkedHashMap<>();
        forceEx = new LinkedHashMap<>();
        partEx = new LinkedHashMap<>();
        pinyinEx = new LinkedHashMap<>();

        loadResource(wholeIn, "/jeiguess_aliases.json");
        loadResource(forceIn, "/jeiguess_force.json");
        loadResource(partIn, "/jeiguess_part.json");
        loadResource(pinyinIn, "/jeiguess_pinyin.json");

        try {
            Path dir = FACTORY.get();
            Files.createDirectories(dir);
            writeTemplateIfMissing(dir.resolve("jeiguess-whole.json"), WHOLE_TEMPLATE);
            writeTemplateIfMissing(dir.resolve("jeiguess-part.json"), PART_TEMPLATE);
            writeTemplateIfMissing(dir.resolve("jeiguess-pinyin.json"), PINYIN_TEMPLATE);

            loadConfig(wholeEx, forceEx, dir.resolve("jeiguess-whole.json"));
            loadConfig(partEx, null, dir.resolve("jeiguess-part.json"));
            loadConfig(pinyinEx, null, dir.resolve("jeiguess-pinyin.json"));
            // 旧的别名文件继续生效，视为「整体纠正」
            Map<String, String> legacy = new LinkedHashMap<>();
            loadConfig(legacy, null, dir.resolve("jeiguess-aliases.json"));
            for (Map.Entry<String, String> e : legacy.entrySet()) wholeEx.putIfAbsent(e.getKey(), e.getValue());
        } catch (Throwable t) {
            MaixrSuggestMod.LOGGER.info("[猜你想搜] 载入外挂词库失败: {}", t.toString());
        }

        Set<String> keys = new TreeSet<>(Comparator.comparingInt(String::length).reversed());
        keys.addAll(partIn.keySet());
        keys.addAll(partEx.keySet());
        partKeys = new ArrayList<>(keys);
        loaded = true;
        MaixrSuggestMod.LOGGER.info("[猜你想搜] 词库已加载：整体 {} 内置/{} 外挂，部分 {} 内置/{} 外挂，拼音 {} 内置/{} 外挂，强制 {} 条",
                wholeIn.size(), wholeEx.size(), partIn.size(), partEx.size(), pinyinIn.size(), pinyinEx.size(),
                forceIn.size() + forceEx.size());
    }

    private static void ensure() {
        if (!loaded) reload();
    }

    /** 一行摘要，给 /jeiguess reload 回显。 */
    public static String summary() {
        ensure();
        return "整体 " + (wholeIn.size() + wholeEx.size())
                + " 条，部分 " + (partIn.size() + partEx.size())
                + " 条，拼音 " + (pinyinIn.size() + pinyinEx.size())
                + " 条，强制 " + (forceIn.size() + forceEx.size()) + " 条";
    }

    /** 这个输入是否命中「强制」规则（无论搜到多少都提示）。 */
    public static boolean isForced(String input) {
        ensure();
        String key = norm(input);
        if (key.isEmpty()) return false;
        String to = forceEx.get(key);
        if (to == null) to = forceIn.get(key);
        return to != null && matchesAny(to);
    }

    /**
     * 整体纠正候选（0~3 条）。内置与外挂写了同一条但目标不同时，**两条都返回**。
     */
    public static List<String> wholeCandidates(String input) {
        ensure();
        String key = norm(input);
        if (key.isEmpty() || key.length() > 16) return List.of();
        LinkedHashSet<String> out = new LinkedHashSet<>();
        addIfValid(out, forceIn.get(key));
        addIfValid(out, forceEx.get(key));
        addIfValid(out, wholeIn.get(key));
        addIfValid(out, wholeEx.get(key));
        if (out.isEmpty()) return List.of();
        List<String> list = new ArrayList<>(out);
        return list.size() > 3 ? list.subList(0, 3) : list;
    }

    /**
     * 部分纠正：按「最长 from 优先」逐条替换，每条只换一次。
     * 返回替换后的词；没有变化或结果搜不到东西则返回 null。
     */
    public static String partApply(String input) {
        ensure();
        String s = input == null ? "" : input.trim();
        if (s.isEmpty() || s.length() > 32) return null;
        String cur = s;
        for (String from : partKeys) {
            if (from.isEmpty() || !cur.contains(from)) continue;
            String to = partEx.get(from);
            if (to == null) to = partIn.get(from);
            if (to == null) continue;
            int at = cur.indexOf(from);
            cur = cur.substring(0, at) + to + cur.substring(at + from.length());
        }
        if (cur.equals(s)) return null;
        return matchesAny(cur) ? cur : null;
    }

    /**
     * 拼音纠正：仅纯 ASCII 字母。
     * 先查外挂/内置的显式映射，再试 ü 的常见拼法互换（lu↔lv、nue↔nve…）。
     * 结果必须能拼音匹配到物品，否则返回 null。
     */
    public static String pinyinApply(String input) {
        ensure();
        if (input == null) return null;
        String s = input.trim().toLowerCase(Locale.ROOT);
        if (s.length() < 2 || s.length() > 24) return null;
        for (int i = 0; i < s.length(); i++) if (s.charAt(i) < 'a' || s.charAt(i) > 'z') return null;
        // 原输入本来就能匹配（JEC 已兼容 luse/lvse）时不要提示，否则纯属噪音
        if (pinyinMatches(s)) return null;

        String direct = pinyinEx.get(s);
        if (direct == null) direct = pinyinIn.get(s);
        if (direct != null && pinyinMatches(direct)) return direct;

        for (String[] sw : U_SWAPS) {
            String from = sw[0], to = sw[1];
            if (!s.startsWith(from)) continue;
            String cand = to + s.substring(from.length());
            if (pinyinMatches(cand)) return cand;
        }
        return null;
    }

    // ───────────────────────── 内部 ─────────────────────────

    /** ü 的常见拼法互换。长前缀排在前面，先匹配 lue 再匹配 lu。 */
    private static final String[][] U_SWAPS = {
            {"lve", "lue"}, {"nve", "nue"}, {"lyu", "lv"}, {"nyu", "nv"},
            {"lue", "lve"}, {"nue", "nve"}, {"lv", "lu"}, {"nv", "nu"},
            {"lu", "lv"}, {"nu", "nv"},
    };

    private static String norm(String s) {
        return s == null ? "" : s.trim().toLowerCase(Locale.ROOT);
    }

    private static void addIfValid(Set<String> out, String target) {
        if (target == null || target.isBlank()) return;
        if (matchesAny(target)) out.add(target.trim());
    }

    /** 目标是不是「能搜到的词」：至少匹配 1 件物品。 */
    private static boolean matchesAny(String term) {
        if (term == null || term.isBlank()) return false;
        try {
            Integer n = ItemNameIndex.countAll(List.of(term), 1).get(term);
            return n != null && n > 0;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 拼音串能不能匹配到物品（JEI 侧靠 JEC 做拼音搜索）。 */
    private static boolean pinyinMatches(String term) {
        try {
            return ItemNameIndex.countPinyin(term, 1) > 0;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 读取内置资源；支持扁平表，也支持 {"rules":{...},"always":{...}} 结构。 */
    private static void loadResource(Map<String, String> rules, String resource) {
        try (InputStream in = CorrectionRules.class.getResourceAsStream(resource)) {
            if (in != null) loadStream(rules, null, in);
        } catch (Throwable t) {
            MaixrSuggestMod.LOGGER.info("[猜你想搜] 载入 {} 失败: {}", resource, t.toString());
        }
    }

    /** 读取 config 文件；不存在就跳过。 */
    private static void loadConfig(Map<String, String> rules, Map<String, String> always, Path file) {
        try {
            if (!Files.exists(file)) return;
            try (InputStream in = Files.newInputStream(file)) {
                loadStream(rules, always, in);
            }
        } catch (Throwable t) {
            MaixrSuggestMod.LOGGER.info("[猜你想搜] 载入词库失败 {}: {}", file.getFileName(), t.toString());
        }
    }

    private static void loadStream(Map<String, String> rules, Map<String, String> always, InputStream in) {
        JsonElement root = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        if (root == null || !root.isJsonObject()) return;
        JsonObject j = root.getAsJsonObject();
        JsonElement r = j.get("rules");
        JsonElement a = j.get("always");
        if (r != null && r.isJsonObject()) {
            putAll(rules, r.getAsJsonObject());
        }
        if (always != null && a != null && a.isJsonObject()) {
            putAll(always, a.getAsJsonObject());
        }
        if (r == null) putAll(rules, j);     // 扁平表：整个对象就是规则
    }

    private static void putAll(Map<String, String> into, JsonObject obj) {
        for (Map.Entry<String, JsonElement> e : obj.entrySet()) {
            String k = norm(e.getKey());
            if (k.isEmpty() || k.equals("rules") || k.equals("always")) continue;
            try {
                String v = e.getValue().getAsString().trim();
                if (!v.isEmpty()) into.put(k, v);
            } catch (Throwable ignored) {}
        }
    }

    private static void writeTemplateIfMissing(Path file, String content) {
        try {
            if (Files.exists(file)) return;
            Files.writeString(file, content, StandardCharsets.UTF_8);
            MaixrSuggestMod.LOGGER.info("[猜你想搜] 已生成词库示例：{}", file.getFileName());
        } catch (Throwable ignored) {}
    }

    // 模板里只能放「真能搜到」的例子，否则玩家一打开就看到无效条目
    private static final String WHOLE_TEMPLATE = "{\n"
            + "  \"_说明\": \"整体纠正：玩家会打的词 -> 游戏里的物品名（或任何能搜到东西的词）。\",\n"
            + "  \"rules\": {\n"
            + "    \"鞋子\": \"靴子\",\n"
            + "    \"包包\": \"潜影盒\"\n"
            + "  },\n"
            + "  \"always\": {\n"
            + "    \"岩浆\": \"熔岩\"\n"
            + "  }\n"
            + "}\n";

    private static final String PART_TEMPLATE = "{\n"
            + "  \"_说明\": \"部分纠正：输入里包含左边的词就换成右边的。最长优先，每条只换一次。\",\n"
            + "  \"rules\": {\n"
            + "    \"铲\": \"锹\",\n"
            + "    \"稿\": \"镐\"\n"
            + "  }\n"
            + "}\n";

    private static final String PINYIN_TEMPLATE = "{\n"
            + "  \"_说明\": \"拼音纠正：左边是玩家可能打的拼音，右边是能被搜到的写法。\",\n"
            + "  \"rules\": {\n"
            + "    \"luse\": \"lvse\"\n"
            + "  }\n"
            + "}\n";

    /** config 目录（Forge / NeoForge 各一份）。 */
    private static final java.util.function.Supplier<Path> FACTORY = () -> net.minecraftforge.fml.loading.FMLPaths.CONFIGDIR.get();
}
