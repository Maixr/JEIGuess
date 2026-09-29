package com.maixr.suggest;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;
import net.minecraft.core.registries.BuiltInRegistries;
import net.neoforged.neoforgespi.language.IModInfo;
import net.neoforged.fml.ModList;

import java.util.*;

/**
 * 候选引擎：按配置配额组装候选，并负责把"显示文本"翻译成"能搜到东西的搜索词"。
 *
 * 关键：mod 名必须转成 @modid（直接搜「无尽贪婪」在 JEI 里什么都搜不到）。
 *
 * ★ v0.2 起，中文名映射表不再依赖内置 json，而是**直接从创造模式标签页推导**：
 *   标签页的中文名（如「机械动力」）→ 页内物品所属的命名空间（create）→ @create。
 *   这样任何整合包、任何语言都能自动得到一份可用的映射，内置 json 只作兜底。
 */
public final class SuggestionEngine {
    private static final Random RANDOM = new Random();
    private static List<Suggestion> randomPool = List.of();
    private static long poolLoadedAt = 0L;
    private static Map<String, String> modAliases = null;
    private static Map<String, String> tagAliases = null;

    private SuggestionEngine() {}

    // ───────────────────────── 别名表 ─────────────────────────

    /**
     * 建立 "mod 显示名/id → @modid" 的映射。
     *
     * 数据源按优先级：
     *   ① 创造模式标签页的中文名 → 页内物品命名空间（★ 通用，跟着整合包走）
     *   ② itemGroup.<modid> 语言键 + mod 显示名
     *   ③ 内置 json（200+ 条，兜底）
     *   ④ 配置文件里的手动映射（最高优先级覆盖）
     */
    private static Map<String, String> modAliases() {
        if (modAliases != null) return modAliases;
        Map<String, String> m = new HashMap<>();
        try {
            // ① ★ 创造模式标签页：中文名 → 页内物品的命名空间（通用做法，不依赖内置表）
            collectFromCreativeTabs(m);

            // ② modid 与 mod 显示名本身
            for (IModInfo info : ModList.get().getMods()) {
                String id = info.getModId();
                m.put(id.toLowerCase(Locale.ROOT), "@" + id);
                String name = info.getDisplayName();
                if (name != null && !name.isBlank() && !name.equalsIgnoreCase(id)) {
                    m.putIfAbsent(name.toLowerCase(Locale.ROOT), "@" + id);
                }
                for (String key : new String[]{ "itemGroup." + id, "itemGroup." + id + ".name" }) {
                    try {
                        String cn = net.minecraft.client.resources.language.I18n.get(key);
                        if (cn != null && !cn.equals(key) && cn.length() >= 2 && cn.length() <= 24) {
                            m.putIfAbsent(cn.toLowerCase(Locale.ROOT), "@" + id);
                        }
                    } catch (Throwable ignored) {}
                }
            }

            // ③ 内置 json（兜底：标签页里拿不到的条目，例如没有独立标签页的库类 mod）
            int n = 0;
            try (java.io.InputStream in = SuggestionEngine.class.getResourceAsStream("/jeiguess_mod_names.json")) {
                if (in != null) {
                    com.google.gson.JsonObject j = com.google.gson.JsonParser.parseReader(
                            new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
                    for (Map.Entry<String, com.google.gson.JsonElement> e : j.entrySet()) {
                        String modid = e.getKey();
                        try {
                            String cn = e.getValue().getAsString();
                            if (cn == null || cn.isBlank()) continue;
                            if (!ModList.get().isLoaded(modid)) continue;      // 没装的 mod 不进表（省得误报）
                            m.putIfAbsent(cn.toLowerCase(Locale.ROOT), "@" + modid);
                            m.putIfAbsent(modid.toLowerCase(Locale.ROOT), "@" + modid);
                            n++;
                        } catch (Throwable ignored) {}
                    }
                }
            } catch (Throwable t) {
                MaixrSuggestMod.LOGGER.info("[猜你想搜] 载入内置 mod 名映射失败: {}", t.toString());
            }
            MaixrSuggestMod.LOGGER.info("[猜你想搜] mod 别名表已建立：{} 条（其中内置兜底 {} 条）", m.size(), n);

            // ④ 配置文件手动映射（覆盖一切）
            try {
                for (String rule : SuggestConfig.MOD_ALIASES.get()) {
                    if (rule == null || !rule.contains("=")) continue;
                    String[] kv = rule.split("=", 2);
                    String alias = kv[0].trim(), target = kv[1].trim();
                    if (alias.isEmpty() || target.isEmpty()) continue;
                    m.put(alias.toLowerCase(Locale.ROOT), target.startsWith("@") ? target : "@" + target);
                }
            } catch (Throwable ignored) {}
        } catch (Throwable t) {
            MaixrSuggestMod.LOGGER.info("[猜你想搜] 构建 mod 别名表失败: {}", t.toString());
        }
        modAliases = m;
        return m;
    }

    /**
     * ★ 从**mod 的**创造模式标签页推导「中文名 → @modid」。
     *
     * 做法：只遍历 mod 自己注册的标签页（原版页、背包页、搜索页全部排除），
     * 取标签页显示名（就是语言文件里的中文名），再统计页内物品的命名空间，取占比最高的那个。
     * 三层过滤：类型必须是 CATEGORY + 注册名不能是 minecraft 命名空间 + 页内物品命名空间不能是 minecraft。
     */
    private static void collectFromCreativeTabs(Map<String, String> m) {
        int tabs = 0, ok = 0;
        try {
            for (CreativeModeTab tab : BuiltInRegistries.CREATIVE_MODE_TAB) {
                tabs++;
                // ★ 只要 mod 自己的标签页：原版页（建筑方块/自然方块/工具与实用物品…）一律跳过
                try {
                    if (tab.getType() != CreativeModeTab.Type.CATEGORY) continue;      // 背包/热键栏/搜索页
                    ResourceLocation tabKey = BuiltInRegistries.CREATIVE_MODE_TAB.getKey(tab);
                    if (tabKey == null || "minecraft".equals(tabKey.getNamespace())) continue;   // 原版标签页
                } catch (Throwable t) { continue; }
                String name;
                try { name = tab.getDisplayName().getString(); } catch (Throwable t) { continue; }
                if (name == null) continue;
                name = name.trim();
                // 未翻译的键（itemGroup.xxx）、太短太长的一律不要
                if (name.length() < 2 || name.length() > 24 || name.startsWith("itemGroup.")) continue;

                Map<String, Integer> ns = new HashMap<>();
                int total = 0;
                try {
                    for (ItemStack s : tab.getDisplayItems()) {
                        if (s == null || s.isEmpty()) continue;
                        ResourceLocation key = BuiltInRegistries.ITEM.getKey(s.getItem());
                        if (key == null) continue;
                        ns.merge(key.getNamespace(), 1, Integer::sum);
                        total++;
                        if (total >= 400) break;                     // 采样够了就停，标签页可能有几千个物品
                    }
                } catch (Throwable t) {
                    continue;                                        // 标签页还没构建好（比如在服务端）
                }
                if (total < 3) continue;
                String best = null;
                int bestCount = 0;
                for (Map.Entry<String, Integer> e : ns.entrySet()) {
                    if (e.getValue() > bestCount) { bestCount = e.getValue(); best = e.getKey(); }
                }
                if (best == null) continue;
                if (bestCount * 2 < total) continue;                 // 占比不足一半，说明是跨 mod 的合成页
                if ("minecraft".equals(best)) continue;              // 原版页不参与（搜「建筑方块」没意义）
                m.putIfAbsent(name.toLowerCase(Locale.ROOT), "@" + best);
                ok++;
            }
        } catch (Throwable t) {
            MaixrSuggestMod.LOGGER.info("[猜你想搜] 读取创造模式标签页失败: {}", t.toString());
        }
        MaixrSuggestMod.LOGGER.info("[猜你想搜] 从创造模式标签页推导出 {} / {} 条中文名映射", ok, tabs);
    }

    /** 物品标签的中文名映射（食物 → #food）。 */
    private static Map<String, String> tagAliases() {
        if (tagAliases != null) return tagAliases;
        Map<String, String> m = new HashMap<>();
        loadJsonInto(m, "/jeiguess_tag_names.json");
        // ★ 玩家 / 整合包作者可以在 config/jeiguess-tags.json 里补自己的翻译，格式与内置表相同
        try {
            java.nio.file.Path user = net.neoforged.fml.loading.FMLPaths.CONFIGDIR.get()
                    .resolve("jeiguess-tags.json");
            if (java.nio.file.Files.exists(user)) {
                String json = java.nio.file.Files.readString(user, java.nio.charset.StandardCharsets.UTF_8);
                com.google.gson.JsonObject j = com.google.gson.JsonParser.parseString(json).getAsJsonObject();
                int n = 0;
                for (Map.Entry<String, com.google.gson.JsonElement> e : j.entrySet()) {
                    m.put(e.getKey().trim().toLowerCase(Locale.ROOT), e.getValue().getAsString());
                    n++;
                }
                MaixrSuggestMod.LOGGER.info("[猜你想搜] 已载入用户标签表 config/jeiguess-tags.json：{} 条", n);
            }
        } catch (Throwable t) {
            MaixrSuggestMod.LOGGER.info("[猜你想搜] 载入用户标签表失败: {}", t.toString());
        }
        tagAliases = m;
        return m;
    }

    private static void loadJsonInto(Map<String, String> m, String resource) {
        try (java.io.InputStream in = SuggestionEngine.class.getResourceAsStream(resource)) {
            if (in == null) return;
            com.google.gson.JsonObject j = com.google.gson.JsonParser.parseReader(
                    new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
            for (Map.Entry<String, com.google.gson.JsonElement> e : j.entrySet()) {
                m.put(e.getKey().trim().toLowerCase(Locale.ROOT), e.getValue().getAsString());
            }
        } catch (Throwable t) {
            MaixrSuggestMod.LOGGER.info("[猜你想搜] 载入 {} 失败: {}", resource, t.toString());
        }
    }

    /** 英文 tag 路径 → 中文（swords → 剑）。数据来自内置表，社区可继续补。 */
    private static Map<String, String> tagDisplay = null;

    private static Map<String, String> tagDisplayMap() {
        if (tagDisplay != null) return tagDisplay;
        Map<String, String> m = new HashMap<>();
        loadJsonInto(m, "/jeiguess_tag_display.json");
        tagDisplay = m;
        return m;
    }

    /** tag id → 中文（由中文表反推，例如 #food → 食物；精确匹配优先于路径匹配）。 */
    private static Map<String, String> tagReverse = null;

    private static Map<String, String> tagReverseMap() {
        if (tagReverse != null) return tagReverse;
        Map<String, String> m = new HashMap<>();
        for (Map.Entry<String, String> e : tagAliases().entrySet()) {
            String tag = e.getValue();
            if (tag == null || tag.isBlank()) continue;
            m.putIfAbsent(tag.toLowerCase(Locale.ROOT), e.getKey());                 // "#food"
            m.putIfAbsent(stripNamespace(tag).toLowerCase(Locale.ROOT), e.getKey()); // "#food"（剥了命名空间）
        }
        tagReverse = m;
        return m;
    }

    /**
     * ★ 标签的中文显示名：先按完整 id 反查（社区表），再按英文路径查（swords → 剑），
     * 都没有就退回英文路径 —— 不硬造翻译，缺的交给社区补。
     */
    public static String tagDisplayName(String tag) {
        if (tag == null || tag.isBlank()) return "";
        String id = tag.startsWith("#") ? tag.substring(1) : tag;
        String exact = tagReverseMap().get("#" + id.toLowerCase(Locale.ROOT));
        if (exact == null) exact = tagReverseMap().get("#" + stripNamespace("#" + id).substring(1).toLowerCase(Locale.ROOT));
        if (exact != null) return exact;
        String path = id.contains(":") ? id.substring(id.indexOf(':') + 1) : id;
        Map<String, String> d = tagDisplayMap();
        String hit = d.get(path.toLowerCase(Locale.ROOT));
        if (hit != null) return hit;
        int slash = path.lastIndexOf('/');
        if (slash >= 0) {
            hit = d.get(path.substring(slash + 1).toLowerCase(Locale.ROOT));
            if (hit != null) return hit;
        }
        return path;
    }

    /** 剥掉标签的命名空间（JEI 的标签搜索只认路径：#forge:cobblestone → #cobblestone）。 */
    private static String stripNamespace(String tag) {
        if (tag == null || !tag.startsWith("#")) return tag;
        String body = tag.substring(1);
        int colon = body.indexOf(':');
        return colon >= 0 ? "#" + body.substring(colon + 1) : tag;
    }

    /** 该词是否是某个物品标签的名字（返回 #tag，不是则 null）。 */
    public static String tagQueryOf(String text) {
        if (text == null) return null;
        String key = text.trim().toLowerCase(Locale.ROOT);
        if (key.isEmpty()) return null;
        if (key.startsWith("#")) return stripNamespace(text.trim());
        Map<String, String> m = tagAliases();
        String exact = m.get(key);
        if (exact != null) return stripNamespace(exact);
        if (key.length() >= 2) {
            for (Map.Entry<String, String> e : m.entrySet()) {
                if (e.getKey().startsWith(key)) return stripNamespace(e.getValue());
            }
        }
        return null;
    }

    /**
     * ★ 该词是否是某个 mod 的名字（返回 @modid，不是则 null）。
     * 支持模糊匹配：输入「应用能源」应该匹配到「应用能源2」（取最短的候选，减少误判）。
     */
    public static String modQueryOf(String text) {
        if (text == null) return null;
        String key = text.trim().toLowerCase(Locale.ROOT);
        if (key.isEmpty()) return null;
        Map<String, String> map = modAliases();
        String exact = map.get(key);
        if (exact != null) return exact;
        if (key.length() < 3) return null;
        String best = null;
        int bestLen = Integer.MAX_VALUE;
        for (Map.Entry<String, String> e : map.entrySet()) {
            String name = e.getKey();
            if (name.length() > key.length() && name.startsWith(key) && name.length() < bestLen) {
                bestLen = name.length();
                best = e.getValue();
            }
        }
        return best;
    }

    /**
     * ★ 判据：这类来源存的是"玩家输入的词/物品名"，它们本身在 JEI 里就能搜到，
     * 不要因为"看起来像标签名"就被改写（「石头」被掰成 #stone 会让玩家搜到一堆别的东西）。
     * 真正搜不到的词由点击前的 fixQueryGlobally() 兜底修正。
     */
    private static boolean isWordLike(Suggestion.Source src) {
        return switch (src) {
            case RECENT, FREQUENT, CHAT, RANDOM, PICK, HOT, FAVORITE -> true;
            default -> false;
        };
    }

    /** 把任意文本翻译成"大概率能搜到东西"的搜索词。 */
    public static String toQuery(String text) {
        if (text == null || text.isBlank()) return text;
        String t = text.trim();
        if (t.startsWith("@") || t.startsWith("#") || t.startsWith("$")) return t;
        String hit = modAliases().get(t.toLowerCase(Locale.ROOT));
        return hit != null ? hit : t;
    }

    // ───────────────────────── 候选组装 ─────────────────────────

    private static List<String> sourceOrder() {
        try { return new ArrayList<>(SuggestConfig.SOURCE_ORDER.get()); }
        catch (Throwable t) {
            return new ArrayList<>(List.of("recent", "quest", "frequent", "pick", "chat", "hot", "random"));
        }
    }

    /** 组装默认候选（按配置的【顺序】与【配额】）。 */
    public static List<Suggestion> build() {
        List<Suggestion> out = new ArrayList<>();
        if (!SuggestConfig.enabled()) return out;          // 总开关关掉就什么都不给
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        SearchHistoryStore store = SearchHistoryStore.get();

        for (String rawSrc : sourceOrder()) {
            String src = rawSrc == null ? "" : rawSrc.trim().toLowerCase(Locale.ROOT);
            switch (src) {
                case "recent" -> {
                    if (!SuggestConfig.ENABLE_RECENT.get()) break;
                    for (String w : recentMerged(SuggestConfig.RECENT_COUNT.get())) {
                        addSug(out, seen, "recent:" + w, new Suggestion(w, toQuery(w), Suggestion.Source.RECENT));
                    }
                }
                case "quest" -> {
                    if (!SuggestConfig.ENABLE_QUEST.get()) break;
                    for (Suggestion s : QuestSource.pick(SuggestConfig.QUEST_COUNT.get())) addSug(out, seen, "q:" + s.display, s);
                }
                case "frequent" -> {
                    if (!SuggestConfig.ENABLE_FREQUENT.get()) break;
                    for (String w : store.frequent(SuggestConfig.FREQUENT_COUNT.get())) {
                        addSug(out, seen, "freq:" + w, new Suggestion(w, toQuery(w), Suggestion.Source.FREQUENT, "x" + store.countOf(w)));
                    }
                }
                case "chat" -> {
                    if (!SuggestConfig.ENABLE_CHAT.get()) break;
                    for (String w : store.chatRecent(SuggestConfig.CHAT_COUNT.get())) {
                        addSug(out, seen, "chat:" + w, new Suggestion(w, toQuery(w), Suggestion.Source.CHAT));
                    }
                }
                case "hot" -> {
                    for (String w : store.hotWords(SuggestConfig.HOT_COUNT.get())) {
                        addSug(out, seen, "hot:" + w, new Suggestion(w, toQuery(w), Suggestion.Source.HOT, "服务端"));
                    }
                }
                case "random" -> {
                    // ★ 在破碎世界时，优先推荐伊甸核心机当前要求的物品（归入【推荐】栏）
                    Suggestion eden = edenRequest();
                    if (eden != null) addSug(out, seen, "eden:" + eden.display, eden);
                    if (!SuggestConfig.ENABLE_RANDOM.get()) break;
                    for (Suggestion s : pickRandom(SuggestConfig.RANDOM_COUNT.get())) addSug(out, seen, "r:" + s.display, s);
                }
                default -> { }
            }
        }
        return finish(out);
    }

    /**
     * ★ 别名匹配（mod 名 / 标签名）：纯文本前缀/包含 **+ 拼音**。
     * 之前这两处只做了 startsWith/contains，所以输入 jxdl 命中不了「机械动力」——
     * 物品名和聊天链接早就有拼音（PinyinBridge），这两个表漏了。
     */
    private static boolean aliasMatch(String label, String input, boolean prefixOnly) {
        if (label == null || input == null || label.isEmpty() || input.isEmpty()) return false;
        if (prefixOnly) {
            if (label.startsWith(input)) return true;
        } else if (label.contains(input)) {
            return true;
        }
        if (!PinyinBridge.isReady()) return false;
        return PinyinBridge.matches(label, input, prefixOnly);
    }

    /** 统一收尾：过滤屏蔽 → 去掉重复词 → 收藏置顶。 */
    private static List<Suggestion> finish(List<Suggestion> out) {
        List<Suggestion> fixed = new ArrayList<>();
        Set<String> seenDisplay = new HashSet<>();
        for (Suggestion s : out) {
            if (s == null) continue;
            if (SearchHistoryStore.get().isBanned(s.display)) continue;      // 被屏蔽的词永不出现
            // ★ 同一个词只出现一次（之前「最近」和「JEI 自带历史」里都有时会画两行）
            if (isWordLike(s.source) && !seenDisplay.add(s.display)) continue;
            fixed.add(s);
        }
        // ★ 收藏永远排最前（不计入配额）
        List<Suggestion> fav = favoriteSuggestions();
        if (!fav.isEmpty()) {
            List<Suggestion> all = new ArrayList<>(fav);
            for (Suggestion s : fixed) {
                boolean dup = false;
                for (Suggestion f : all) if (f.display.equalsIgnoreCase(s.display)) { dup = true; break; }
                if (!dup) all.add(s);
            }
            return all;
        }
        return fixed;
    }

    /** 收藏候选（右键加星的那些词）。 */
    private static List<Suggestion> favoriteSuggestions() {
        int q = 2;
        try { q = SuggestConfig.FAVORITE_COUNT.get(); } catch (Throwable ignored) {}
        List<Suggestion> out = new ArrayList<>();
        for (String w : SearchHistoryStore.get().favorites(q)) {
            out.add(new Suggestion(w, toQuery(w), Suggestion.Source.FAVORITE, "★"));
        }
        return out;
    }

    /**
     * 玩家在 maixr:overworld_broken 维度里时，把伊甸核心机当前要求的物品做成一条【推荐】候选。
     * （纯反射读 MaixrCore 的静态字段，没装时安静返回 null。）
     */
    private static Suggestion edenRequest() {
        try {
            net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
            if (mc.level == null) return null;
            ResourceLocation dim = mc.level.dimension().location();
            if (!"maixr".equals(dim.getNamespace()) || !"overworld_broken".equals(dim.getPath())) return null;

            Class<?> state = Class.forName("com.maixr.client.MaixrEdenClientState");
            Object rawDone = state.getField("completeness").get(null);
            if (rawDone instanceof Integer done && done >= 1000) return null;
            Object raw = state.getField("requiredItem").get(null);
            if (!(raw instanceof String id) || id.isBlank()) return null;

            ResourceLocation rl = ResourceLocation.tryParse(id);
            if (rl == null) return null;
            net.minecraft.world.item.Item item = BuiltInRegistries.ITEM.get(rl);
            if (item == null) return null;

            String name = new ItemStack(item).getHoverName().getString();
            if (name.isBlank()) return null;
            return new Suggestion(name, name, Suggestion.Source.RANDOM, "伊甸");
        } catch (Throwable t) {
            return null;
        }
    }

    // ───────────────────────── 浏览模式分页 ─────────────────────────

    /** 浏览器式的一"页"：第 0 页是混合页，之后每个来源一页（放不下就继续分块）。 */
    public static final class Page {
        public final String label;
        public final List<Suggestion> items;
        public Page(String label, List<Suggestion> items) { this.label = label; this.items = items; }
    }

    private static int rowsPerPage() {
        try { return SuggestConfig.ROWS_PER_PAGE.get(); } catch (Throwable t) { return 8; }
    }

    /**
     * ★ 无输入时的分页：
     *   ① 混合页 —— 3 最近 + 3 任务 + 1 推荐 + 1 常搜（有伊甸要求提交物时多一条，共 9）
     *   ② 之后每个来源一整页（最近 / 任务 / 常搜 / 聊天 / 热词 / 推荐 / 收藏），
     *      来源条目多就继续分块，滚轮一路滚到底。
     */
    public static List<Page> browsePages() {
        if (!SuggestConfig.enabled()) return new ArrayList<>();
        List<Page> out = new ArrayList<>();
        List<Suggestion> mixed = buildMixed();
        if (!mixed.isEmpty()) out.add(new Page("混合", mixed));
        int rows = rowsPerPage();
        addChunks(out, "收藏", allFavorites(60), rows);
        for (String rawSrc : sourceOrder()) {
            String src = rawSrc == null ? "" : rawSrc.trim().toLowerCase(Locale.ROOT);
            if ("pick".equals(src)) continue;          // 「上次」依赖当前输入词，浏览模式没有
            addChunks(out, labelOfSource(src), fullBySource(src), rows);
        }
        return out;
    }

    /** 混合页：按配置配额组装，然后截到"一页"的长度（有伊甸时多一行）。 */
    public static List<Suggestion> buildMixed() {
        List<Suggestion> all = build();
        int cap = rowsPerPage();
        for (Suggestion s : all) {
            if (s.badge != null && "伊甸".equals(s.badge)) { cap++; break; }
        }
        if (all.size() <= cap) return all;
        return new ArrayList<>(all.subList(0, cap));
    }

    /** 把一整份来源列表切成若干页（每页最多 rows 行，最多 6 页）。 */
    private static void addChunks(List<Page> out, String label, List<Suggestion> all, int rows) {
        if (all == null || all.isEmpty() || rows <= 0) return;
        int total = (all.size() + rows - 1) / rows;
        for (int i = 0; i < total && i < 6; i++) {
            List<Suggestion> chunk = new ArrayList<>(
                    all.subList(i * rows, Math.min((i + 1) * rows, all.size())));
            out.add(new Page(total > 1 ? label + "\u00a7e" + (i + 1) + "/" + total : label, chunk));
        }
    }

    private static String labelOfSource(String src) {
        return switch (src) {
            case "recent" -> "最近";
            case "quest" -> "任务";
            case "frequent" -> "常搜";
            case "chat" -> "聊天";
            case "hot" -> "热词";
            case "random" -> "推荐";
            case "pick" -> "上次";
            default -> src;
        };
    }

    /** 某个来源的**全部**候选（浏览模式整页用，不受配额限制）。 */
    private static List<Suggestion> fullBySource(String src) {
        SearchHistoryStore store = SearchHistoryStore.get();
        List<Suggestion> out = new ArrayList<>();
        try {
            switch (src) {
                case "recent" -> {
                    if (!SuggestConfig.ENABLE_RECENT.get()) break;
                    for (String w : recentMerged(60)) out.add(new Suggestion(w, toQuery(w), Suggestion.Source.RECENT));
                }
                case "quest" -> {
                    if (!SuggestConfig.ENABLE_QUEST.get()) break;
                    out.addAll(QuestSource.pickAll(60));
                }
                case "frequent" -> {
                    if (!SuggestConfig.ENABLE_FREQUENT.get()) break;
                    for (String w : store.frequent(60)) {
                        out.add(new Suggestion(w, toQuery(w), Suggestion.Source.FREQUENT, "x" + store.countOf(w)));
                    }
                }
                case "chat" -> {
                    if (!SuggestConfig.ENABLE_CHAT.get()) break;
                    for (String w : store.chatRecent(60)) out.add(new Suggestion(w, toQuery(w), Suggestion.Source.CHAT));
                }
                case "hot" -> {
                    for (String w : store.hotWords(60)) out.add(new Suggestion(w, toQuery(w), Suggestion.Source.HOT, "服务端"));
                }
                case "random" -> out.addAll(allRecommended());
                default -> { }
            }
        } catch (Throwable ignored) {}
        return clean(out);
    }

    /**
     * ★ 「最近」的合并视图：本地历史（带真实时间戳，最新在前）+ JEI 自带历史（补漏）。
     * 以前是"本地不够 N 条才去问 JEI"，结果玩家手动搜的新词永远进不了列表 —— 现在始终合并。
     */
    private static List<String> recentMerged(int limit) {
        LinkedHashSet<String> set = new LinkedHashSet<>();
        // ★ JEI 自带历史 = 玩家真正提交过的搜索，最新在前 —— 刚搜完的词必须排最前
        if (limit > 0) set.addAll(JeiBridge.jeiHistory(limit));
        set.addAll(SearchHistoryStore.get().recent(limit));   // 再补我们自己记的（从面板点过的词）
        List<String> out = new ArrayList<>(set);
        return out.size() > limit ? new ArrayList<>(out.subList(0, limit)) : out;
    }

    /** 收藏（整页用）。 */
    private static List<Suggestion> allFavorites(int limit) {
        List<Suggestion> out = new ArrayList<>();
        for (String w : SearchHistoryStore.get().favorites(limit)) {
            out.add(new Suggestion(w, toQuery(w), Suggestion.Source.FAVORITE, "★"));
        }
        return out;
    }

    /** 推荐词池的全部词（不是随机抽一个）。 */
    private static List<Suggestion> allRecommended() {
        List<Suggestion> out = new ArrayList<>();
        try {
            for (String s : SuggestConfig.SUGGEST_WORDS.get()) {
                if (s == null || s.isBlank()) continue;
                String w = s.trim();
                out.add(new Suggestion(w, toQuery(w), Suggestion.Source.RANDOM));
            }
        } catch (Throwable ignored) {}
        return out;
    }

    /** 过滤屏蔽词 + 去掉重复词（浏览整页用）。 */
    private static List<Suggestion> clean(List<Suggestion> in) {
        List<Suggestion> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Suggestion s : in) {
            if (s == null) continue;
            if (SearchHistoryStore.get().isBanned(s.display)) continue;
            if (isWordLike(s.source) && !seen.add(s.display)) continue;
            out.add(s);
        }
        return out;
    }

    /** 加候选（按"来源:文本"去重，同名不同来源可共存）。 */
    private static void addSug(List<Suggestion> out, Set<String> seen, String key, Suggestion s) {
        if (s == null || key == null) return;
        if (seen.add(key)) out.add(s);
    }

    // ───────────────────────── 带输入的建议 ─────────────────────────

    /** 已有输入时的建议（支持拼音：输入 stjsq 匹配「实体加速器」）。 */
    public static List<Suggestion> filter(String input, int limit) {
        if (!SuggestConfig.enabled()) return new ArrayList<>();
        if (input == null || input.isBlank()) return build();
        String key = input.trim().toLowerCase(Locale.ROOT);

        // ★ JEI 前缀语法提示：输入 @ 列 mod、输入 # 列标签
        if (SuggestConfig.SYNTAX_HINTS.get()) {
            if (key.startsWith("@")) return finish(prefixHints(key.substring(1), true, limit));
            if (key.startsWith("#")) return finish(prefixHints(key.substring(1), false, limit));
        }

        boolean prefixOnly = SuggestConfig.MATCH_PREFIX_ONLY.get();
        int itemQuota = Math.max(3, limit - 4);
        List<Suggestion> out = new ArrayList<>();
        LinkedHashSet<String> seen = new LinkedHashSet<>();

        // ① ★ 上次搜这个词之后你点开的物品（优先级最高）
        int pickQ = 1;
        try { pickQ = SuggestConfig.PICK_COUNT.get(); } catch (Throwable ignored) {}
        for (String n : SearchHistoryStore.get().pickedNames(input, pickQ)) {
            addSug(out, seen, "pick:" + n, new Suggestion(n, toQuery(n), Suggestion.Source.PICK, "上次"));
        }

        // ② 历史里匹配的
        for (String w : allHistory()) {
            if (out.size() >= limit) break;
            if (PinyinBridge.matches(w, input, prefixOnly) && seen.add("hist:" + w)) {
                out.add(new Suggestion(w, toQuery(w), Suggestion.Source.RECENT));
            }
        }

        // ③ 物品名里匹配的（支持拼音）
        int itemCount = 0;
        List<String> matchedItems = new ArrayList<>();
        for (String n : ItemNameIndex.suggestNames(input, itemQuota, prefixOnly)) {
            if (out.size() >= limit || itemCount >= itemQuota) break;
            itemCount++;
            matchedItems.add(n);
            if (seen.add("item:" + n)) out.add(new Suggestion(n, toQuery(n), Suggestion.Source.PLAIN, "物品"));
        }

        // ④ 主动匹配 mod 名（输入「应用机动」这类词时历史/物品名里都没有）
        int modAdded = 0;
        for (Map.Entry<String, String> e : modAliases().entrySet()) {
            if (modAdded >= 3) break;
            String cn = e.getKey();
            if (aliasMatch(cn, key, true) && seen.add("mod:" + cn)) {
                out.add(new Suggestion(cn, e.getValue(), Suggestion.Source.MOD, e.getValue()));
                modAdded++;
            }
        }
        if (modAdded == 0) {                                  // 前缀没有才退而求其次做包含匹配
            for (Map.Entry<String, String> e : modAliases().entrySet()) {
                if (modAdded >= 3) break;
                String cn = e.getKey();
                if (aliasMatch(cn, key, false) && seen.add("mod:" + cn)) {
                    out.add(new Suggestion(cn, e.getValue(), Suggestion.Source.MOD, e.getValue()));
                    modAdded++;
                }
            }
        }

        // ⑤ 主动匹配标签名
        int tagAdded = 0;
        for (Map.Entry<String, String> e : tagAliases().entrySet()) {
            if (tagAdded >= 2) break;
            String cn = e.getKey();
            if ((aliasMatch(cn, key, true) || (key.length() >= 2 && aliasMatch(cn, key, false)))
                    && seen.add("tag:" + cn)) {
                out.add(new Suggestion(cn, e.getValue(), Suggestion.Source.TAG, e.getValue()));
                tagAdded++;
            }
        }

        // ⑤b ★ 从**真实标签注册表**推导标签：输入「剑」→ 上面匹配到的剑所属的标签 → #swords
        if (out.size() < limit) addTagHints(out, seen, input, 2, matchedItems);

        // ⑥ ★ 纠错：「你是不是想搜…」
        if (SuggestConfig.CORRECTION.get() && matchedItems.isEmpty()) {
            String fix = correction(input);
            if (fix != null) {
                Suggestion s = new Suggestion(fix, toQuery(fix), Suggestion.Source.PLAIN, "你是不是想搜");
                List<Suggestion> withFix = new ArrayList<>();
                withFix.add(s);
                withFix.addAll(out);
                out = withFix;
            }
        }

        if (out.isEmpty()) return build();                     // 还是没有？退回默认候选，别让面板空掉
        return finish(out);
    }

    /**
     * ★ 前缀语法提示：输入 @ 之后列出 mod、输入 # 之后列出标签，
     * 让玩家学会 JEI 自带的搜索语法（很多人根本不知道有这些东西）。
     */
    private static List<Suggestion> prefixHints(String rest, boolean mod, int limit) {
        List<Suggestion> out = new ArrayList<>();
        String r = rest == null ? "" : rest.trim().toLowerCase(Locale.ROOT);
        Map<String, String> map = mod ? modAliases() : tagAliases();
        String prefix = mod ? "@" : "#";
        for (Map.Entry<String, String> e : map.entrySet()) {
            if (out.size() >= limit) break;
            String label = e.getKey();
            if (r.isEmpty() || aliasMatch(label, r, true) || (r.length() >= 2 && aliasMatch(label, r, false))) {
                out.add(new Suggestion(label, e.getValue(), mod ? Suggestion.Source.MOD : Suggestion.Source.TAG, e.getValue()));
            }
        }
        if (!mod && out.isEmpty() && !r.isEmpty() && r.chars().allMatch(c -> c < 128)) {
            // 输入 #sword → 直接从真实标签注册表里找（显示中文名，徽标是完整 id）
            for (TagKey<Item> tag : TagIndex.allTags()) {
                if (out.size() >= limit) break;
                if (!tag.location().getPath().contains(r)) continue;
                String id = "#" + tag.location();
                out.add(new Suggestion(tagDisplayName(id), id, Suggestion.Source.TAG, id));
            }
        }
        if (out.isEmpty() && !r.isEmpty() && mod) {          // 没命中中文名就按 modid 前缀兜底
            for (IModInfo info : ModList.get().getMods()) {
                if (out.size() >= limit) break;
                if (info.getModId().startsWith(r)) {
                    out.add(new Suggestion(info.getModId(), "@" + info.getModId(), Suggestion.Source.MOD, "@" + info.getModId()));
                }
            }
            if (out.isEmpty()) out.add(new Suggestion("@" + r, "@" + r, Suggestion.Source.MOD, "直接搜"));
        }
        if (out.isEmpty()) {
            out.add(new Suggestion(prefix, prefix, mod ? Suggestion.Source.MOD : Suggestion.Source.TAG,
                    mod ? "列出全部模组" : "列出全部标签"));
        }
        return out;
    }

    /**
     * ★ 标签推荐（数据来自真实注册表，不需要中英对照表）：
     *   ① 先按物品名匹配到几件物品，再看它们共同所属的标签（「剑」→ #swords）
     *   ② 纯英文输入直接匹配标签 id（输 sword 也命中）
     */
    private static void addTagHints(List<Suggestion> out, Set<String> seen, String input, int limit,
                                    List<String> matchedItems) {
        if (limit <= 0 || input == null || input.isBlank()) return;
        try {
            int added = 0;
            List<String> names = matchedItems == null ? List.of() : matchedItems;
            Map<String, Integer> hits = new LinkedHashMap<>();
            Map<String, String> pathOf = new HashMap<>();
            for (String n : names) {
                net.minecraft.world.item.Item it = ItemNameIndex.itemOf(n);
                if (it == null) continue;
                for (TagKey<Item> tag : TagIndex.tagsOf(new ItemStack(it))) {
                    String id = "#" + tag.location();
                    hits.merge(id, 1, Integer::sum);
                    pathOf.putIfAbsent(id, tag.location().getPath());
                }
            }
            List<Map.Entry<String, Integer>> ranked = new ArrayList<>(hits.entrySet());
            ranked.sort(Comparator.comparingInt((Map.Entry<String, Integer> e) -> e.getValue()).reversed());
            for (Map.Entry<String, Integer> e : ranked) {
                if (added >= limit) break;
                if (!seen.add("tagreg:" + e.getKey())) continue;
                String id = e.getKey();
                out.add(new Suggestion(tagDisplayName(id), id, Suggestion.Source.TAG, id));
                added++;
            }
            String key = input.trim().toLowerCase(Locale.ROOT);
            boolean ascii = key.chars().allMatch(c -> c < 128);
            if (added < limit && ascii && key.length() >= 3) {
                for (TagKey<Item> tag : TagIndex.allTags()) {
                    if (added >= limit) break;
                    String path = tag.location().getPath();
                    if (!path.contains(key)) continue;
                    String id = "#" + tag.location();
                    if (!seen.add("tagreg:" + id)) continue;
                    out.add(new Suggestion(tagDisplayName(id), id, Suggestion.Source.TAG, id));
                    added++;
                }
            }
        } catch (Throwable ignored) {}
    }

    /** ★ 纠错：在物品名与历史词里找"最像"的那个（编辑距离 ≤ 2 且长度接近）。 */
    private static String correction(String input) {
        String key = input.trim();
        if (key.length() < 2 || key.length() > 16) return null;
        if (modAliases().containsKey(key.toLowerCase(Locale.ROOT))) return null;
        if (tagQueryOf(key) != null) return null;
        List<String> cands = new ArrayList<>(ItemNameIndex.nearest(key, 3));
        cands.addAll(SearchHistoryStore.get().allWords());
        String best = null;
        int bestD = Integer.MAX_VALUE;
        for (String c : cands) {
            if (c == null || c.isBlank()) continue;
            int d = distance(key.toLowerCase(Locale.ROOT), c.toLowerCase(Locale.ROOT));
            if (d < bestD && d <= 2 && Math.abs(c.length() - key.length()) <= 3) {
                bestD = d;
                best = c;
            }
        }
        return best;
    }

    /** Levenshtein 距离（词都很短，直接滚动数组）。 */
    private static int distance(String a, String b) {
        int[] prev = new int[b.length() + 1];
        int[] cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) prev[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] t = prev; prev = cur; cur = t;
        }
        return prev[b.length()];
    }

    private static List<String> allHistory() {
        LinkedHashSet<String> set = new LinkedHashSet<>();
        set.addAll(JeiBridge.jeiHistory(40));
        set.addAll(SearchHistoryStore.get().recent(40));
        return new ArrayList<>(set);
    }

    /**
     * ★ 全局修正：任何候选在点击前都过一遍这里 ——
     *   如果这个词在 JEI 里搜不到东西，就依次尝试改写成能搜到的形式。
     */
    public static String fixQueryGlobally(String query) {
        if (query == null || query.isBlank()) return query;
        String q = query.trim();
        if (q.startsWith("@") || q.startsWith("#") || q.startsWith("$")) return q;
        if (JeiBridge.hasSearchResult(q)) return q;
        String mapped = modQueryOf(q);
        if (mapped != null && JeiBridge.hasSearchResult(mapped)) return mapped;
        String tagQ = tagQueryOf(q);
        if (tagQ != null) return tagQ;
        String norm = q.toLowerCase(Locale.ROOT).replace(" ", "").replace("_", "").replace("-", "");
        for (IModInfo info : ModList.get().getMods()) {
            String id = info.getModId();
            String idNorm = id.toLowerCase(Locale.ROOT).replace(" ", "").replace("_", "").replace("-", "");
            if (norm.equals(idNorm)) {
                String cand = "@" + id;
                if (JeiBridge.hasSearchResult(cand)) return cand;
            }
        }
        List<String> names = ItemNameIndex.suggestNames(q, 1, false);
        if (!names.isEmpty() && JeiBridge.hasSearchResult(names.get(0))) return names.get(0);
        return q;
    }

    /** 推荐词池：自动把 mod 名转成 @modid。 */
    private static List<Suggestion> pickRandom(int count) {
        if (count <= 0) return List.of();
        long now = System.currentTimeMillis();
        if (now - poolLoadedAt > 60_000L) {
            poolLoadedAt = now;
            try {
                List<Suggestion> cfg = new ArrayList<>();
                for (String s : SuggestConfig.SUGGEST_WORDS.get()) {
                    if (s == null || s.isBlank()) continue;
                    String w = s.trim();
                    cfg.add(new Suggestion(w, toQuery(w), Suggestion.Source.RANDOM));
                }
                Collections.shuffle(cfg, RANDOM);
                randomPool = cfg;
            } catch (Throwable t) { randomPool = List.of(); }
        }
        List<Suggestion> out = new ArrayList<>();
        for (Suggestion s : randomPool) { if (out.size() >= count) break; out.add(s); }
        return out;
    }
}
