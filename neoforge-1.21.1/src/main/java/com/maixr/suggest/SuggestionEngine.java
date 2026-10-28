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
    /** 紫色「搜过」最多顶上来几条（浏览器地址栏一般也就 3~6 条） */
    private static final int SEARCHED_MAX = 5;
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

    /**
     * 收藏候选。
     * ★ limit <= 0 = 不设上限（中键收藏的条目全部列出，不占 8 条配额）。
     * 配置里 favorite 设成 0 即表示"不限量"。
     */
    private static List<Suggestion> favoriteSuggestions() {
        int q = 0;
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

    /**
     * 混合页。
     *
     * ★ 尺寸规则（用户要求）：
     *   基础 8 条 = 配额内容（最近/任务/常搜/推荐…）
     *   + 收藏的条数（**收藏不占那 8 条**，多收藏一个就多一行 → 9、10…）
     *   + 伊甸需求物品（有则 +1）
     *   所以收藏 2 个 + 有伊甸 = 8 + 2 + 1 = 11 行。
     */
    public static List<Suggestion> buildMixed() {
        List<Suggestion> all = build();
        // build()/finish() 已把收藏排在最前，这里只需算出"除了收藏之外"放几条
        int favCount = 0;
        for (Suggestion s : all) {
            if (s.source == Suggestion.Source.FAVORITE) favCount++;
            else break;                                  // 收藏都在最前面，后面的就是普通候选
        }
        int base = rowsPerPage();                        // 8
        boolean eden = false;
        for (Suggestion s : all) {
            if (s.badge != null && "伊甸".equals(s.badge)) { eden = true; break; }
        }
        int cap = favCount + base + (eden ? 1 : 0);
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

        // ⓪ ★★ 紫色「搜过」回放 —— 像浏览器地址栏，但排序是**打分**不是"按时间"
        //    score = 频次(log) × 时间衰减(半衰期14天) × 匹配质量(前缀>词首>包含) − 空结果惩罚
        //    这样"搜过 256k化学品元件"会稳定压过"很久以前搜过一次 256 的某个词"。
        List<Suggestion> searched = new ArrayList<>();
        for (SearchHistoryStore.Entry e : SearchHistoryStore.get().searchedEntries()) {
            String w = e.word;
            if (w.equalsIgnoreCase(input.trim())) continue;              // 完全相同的不重复推荐
            if (!aliasMatch(w, input, true)) continue;
            Suggestion s = new Suggestion(w, toQuery(w), Suggestion.Source.SEARCHED, "搜过");
            s.score = SuggestionScore.score(e.count, e.lastUsed, e.emptyHits, w, input);
            // ★ 高亮命中的那一段（像浏览器把补全的输入部分标亮）
            int at = w.toLowerCase(Locale.ROOT).indexOf(key);
            if (at >= 0) { s.hlStart = at; s.hlEnd = at + key.length(); }
            searched.add(s);
        }
        searched.sort((a, b) -> Double.compare(b.score, a.score));
        for (Suggestion s : searched) {
            if (out.size() >= SEARCHED_MAX) break;
            if (seen.add("searched:" + s.display)) out.add(s);
        }

        // ① ★ 上次搜这个词之后你点开的物品
        int pickQ = 1;
        try { pickQ = SuggestConfig.PICK_COUNT.get(); } catch (Throwable ignored) {}
        for (String n : SearchHistoryStore.get().pickedNames(input, pickQ)) {
            addSug(out, seen, "pick:" + n, new Suggestion(n, toQuery(n), Suggestion.Source.PICK, "上次"));
        }

        // ② 历史里匹配的（作为【最近】，与上面的紫色回放互补）
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

        // ⑤c ★★ 亚空间搜索：JEI 收录不到、但游戏里真实存在的物品
        //     （命令方块、屏障、结构空位，以及名字是原始翻译键的物品）
        if (SuggestConfig.VOID_SEARCH.get() && out.size() < limit) {
            int voidQ = Math.min(3, limit - out.size());
            for (VoidIndex.Entry e : VoidIndex.find(input, voidQ)) {
                if (!seen.add("void:" + e.id)) continue;
                // ★ 右侧标注「归属mod · 原因」，直接回答"这是啥、为什么 JEI 里没有"
                String why = "nolang".equals(e.why) ? "无翻译" : "JEI隐藏";
                String badge = e.modName + " · " + why;
                Suggestion s = new Suggestion(e.name, e.id, Suggestion.Source.VOID, badge);
                // 查询词用物品 id（点击/复制都按 id 走）
                out.add(s);
            }
        }

        // ⑥ ★ 纠错：「你是不是想搜…」
        // ★ 用**物品名命中数**判断，而不是 out.size()：
        //   out 里还有[最近]、[标签]、[亚空间]等条目，会把数字撑到 3 以上，
        //   导致「土豆」搜出「土豆加农炮 + 两个标签」后就永远不提示「马铃薯」。
        if (SuggestConfig.CORRECTION.get() && matchedItems.size() < 3) {
            String fix = correction(input);
            if (fix != null) {
                Suggestion s = new Suggestion(fix, toQuery(fix), Suggestion.Source.PLAIN, "你是不是想搜");
                List<Suggestion> withFix = new ArrayList<>();
                withFix.add(s);
                withFix.addAll(out);
                out = withFix;
            }
        }

        // ★ 关键修复：索引还在构建时，matchedItems 必然是空的 ——
        //   这时候如果直接走下面的兜底，面板会显示"混合页"（看起来就像搜了没用）。
        //   所以先判断索引状态：没建好就明确告诉玩家，而不是假装没匹配。
        if (out.isEmpty()) {
            if (!ItemNameIndex.isBuilt()) {
                // 索引分帧构建中（两万物品约需 2~3 秒）——给一条明确的提示，而不是默默显示混合页
                List<Suggestion> wait = new ArrayList<>();
                wait.add(new Suggestion("索引构建中… 请稍候再试", input, Suggestion.Source.PLAIN,
                        ItemNameIndex.diagnose()));
                MaixrSuggestMod.LOGGER.info("[猜你想搜] 搜索『{}』时索引尚未就绪：{}", input, ItemNameIndex.diagnose());
                return wait;
            }
            // 索引正常但没有匹配：退回默认候选，别让面板空掉
            MaixrSuggestMod.LOGGER.info("[猜你想搜] 搜索『{}』无匹配，退回默认候选（索引 {}）",
                    input, ItemNameIndex.diagnose());
            return build();
        }
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
                out.add(new Suggestion(tagDisplayName(id), id, Suggestion.Source.TAG, id).hint(tagHint(id)));
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
            // ★ 只有「多件物品共同所属」才算分类；一件物品的标签不构成推荐。
            //   搜「土豆」只匹配到「土豆加农炮」一件，就把它的 enchantable/* 当推荐了 —— 纯噪音。
            if (names.size() < 2) return;
            Map<String, Integer> hits = new LinkedHashMap<>();
            Map<String, String> pathOf = new HashMap<>();
            for (String n : names) {
                net.minecraft.world.item.Item it = ItemNameIndex.itemOf(n);
                if (it == null) continue;
                for (TagKey<Item> tag : TagIndex.tagsOf(new ItemStack(it))) {
                    if (isJunkTag(tag)) continue;          // 附魔/技术标记不算分类
                    String id = "#" + tag.location();
                    hits.merge(id, 1, Integer::sum);
                    pathOf.putIfAbsent(id, tag.location().getPath());
                }
            }
            List<Map.Entry<String, Integer>> ranked = new ArrayList<>(hits.entrySet());
            ranked.sort(Comparator.comparingInt((Map.Entry<String, Integer> e) -> e.getValue()).reversed());
            for (Map.Entry<String, Integer> e : ranked) {
                if (added >= limit) break;
                if (e.getValue() < 2) continue;               // 至少两件物品共有才算一类
                if (!seen.add("tagreg:" + e.getKey())) continue;
                String id = e.getKey();
                out.add(new Suggestion(tagDisplayName(id), id, Suggestion.Source.TAG, id).hint(tagHint(id)));
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
                    out.add(new Suggestion(tagDisplayName(id), id, Suggestion.Source.TAG, id).hint(tagHint(id)));
                    added++;
                }
            }
        } catch (Throwable ignored) {}
    }

    /**
     * ★ 技术性标签：几乎每个物品都有，属于内部标记而不是「分类」。
     *
     *   enchantable/durability  → 能附耐久类魔咒
     *   enchantable/vanishing   → 能附消失诅咒
     *
     * 这类标签对「找同类物品」毫无帮助，只会把面板塞满噪音。
     * （整合包里还有个拼错的 enchatable/*，一并挡掉。）
     */
    private static boolean isJunkTag(TagKey<Item> tag) {
        if (tag == null) return true;
        String p = tag.location().getPath().toLowerCase(Locale.ROOT);
        return p.startsWith("enchantable/") || p.startsWith("enchatable/")
                || p.contains("/vanishing") || p.contains("/durability");
    }
    /**
     * ★ 标签的中文补充说明（行尾黄绿色）。
     *
     * 绿色显示名如果本来就是英文路径（没人看得懂），就在后面补一个中文翻译；
     * 已经有中文显示名的不重复。
     */
    private static String tagHint(String id) {
        try {
            String raw = id.startsWith("#") ? id.substring(1) : id;
            String path = raw.contains(":") ? raw.substring(raw.indexOf(':') + 1) : raw;
            String display = tagDisplayName(id);
            if (!display.equals(path)) return "";        // 显示名已是中文
            return tagTranslate(path);
        } catch (Throwable t) {
            return "";
        }
    }

    /** 英文标签路径 → 中文：整词精确匹配优先，否则按 / 与 _ 分段逐词翻译（有未识别的词就不硬翻）。 */
    private static String tagTranslate(String path) {
        Map<String, String> m = tagDisplayMap();
        String full = m.get(path.toLowerCase(Locale.ROOT));
        if (full != null) return full;
        StringBuilder sb = new StringBuilder();
        for (String seg : path.split("/")) {
            if (sb.length() > 0) sb.append('/');
            for (String w : seg.split("_")) {
                String cn = TAG_WORDS.get(w.toLowerCase(Locale.ROOT));
                if (cn == null) return "";               // 有不认识的词 → 宁可不翻
                sb.append(cn);
            }
        }
        return sb.toString();
    }

    /** ★ 标签英文词 → 中文（够覆盖常见分类即可，缺的由 jeiguess_tag_display.json 补）。 */
    private static final Map<String, String> TAG_WORDS = new HashMap<>();
    static {
        TAG_WORDS.put("storage", "存储");
        TAG_WORDS.put("and", "与");
        TAG_WORDS.put("mineable", "可采掘");
        TAG_WORDS.put("double", "双");
        TAG_WORDS.put("wooden", "木");
        TAG_WORDS.put("c", "通用");
        TAG_WORDS.put("block", "方块");
        TAG_WORDS.put("blocks", "方块");
        TAG_WORDS.put("raw", "粗");
        TAG_WORDS.put("material", "材料");
        TAG_WORDS.put("materials", "材料");
        TAG_WORDS.put("ore", "矿石");
        TAG_WORDS.put("ores", "矿石");
        TAG_WORDS.put("ingot", "锭");
        TAG_WORDS.put("ingots", "锭");
        TAG_WORDS.put("nugget", "粒");
        TAG_WORDS.put("nuggets", "粒");
        TAG_WORDS.put("dust", "粉");
        TAG_WORDS.put("dusts", "粉");
        TAG_WORDS.put("gem", "宝石");
        TAG_WORDS.put("gems", "宝石");
        TAG_WORDS.put("rod", "棒");
        TAG_WORDS.put("rods", "棒");
        TAG_WORDS.put("gear", "齿轮");
        TAG_WORDS.put("gears", "齿轮");
        TAG_WORDS.put("plate", "板");
        TAG_WORDS.put("plates", "板");
        TAG_WORDS.put("wire", "线");
        TAG_WORDS.put("wires", "线");
        TAG_WORDS.put("log", "原木");
        TAG_WORDS.put("logs", "原木");
        TAG_WORDS.put("wood", "木头");
        TAG_WORDS.put("planks", "木板");
        TAG_WORDS.put("plank", "木板");
        TAG_WORDS.put("slab", "台阶");
        TAG_WORDS.put("slabs", "台阶");
        TAG_WORDS.put("stair", "楼梯");
        TAG_WORDS.put("stairs", "楼梯");
        TAG_WORDS.put("wall", "墙");
        TAG_WORDS.put("walls", "墙");
        TAG_WORDS.put("fence", "栅栏");
        TAG_WORDS.put("fences", "栅栏");
        TAG_WORDS.put("gate", "门");
        TAG_WORDS.put("gates", "门");
        TAG_WORDS.put("door", "门");
        TAG_WORDS.put("doors", "门");
        TAG_WORDS.put("trapdoor", "活板门");
        TAG_WORDS.put("trapdoors", "活板门");
        TAG_WORDS.put("button", "按钮");
        TAG_WORDS.put("buttons", "按钮");
        TAG_WORDS.put("pressure", "压力");
        TAG_WORDS.put("wool", "羊毛");
        TAG_WORDS.put("dye", "染料");
        TAG_WORDS.put("dyes", "染料");
        TAG_WORDS.put("glass", "玻璃");
        TAG_WORDS.put("terracotta", "陶瓦");
        TAG_WORDS.put("concrete", "混凝土");
        TAG_WORDS.put("sandstone", "砂岩");
        TAG_WORDS.put("stone", "石头");
        TAG_WORDS.put("stones", "石头");
        TAG_WORDS.put("cobblestone", "圆石");
        TAG_WORDS.put("dirt", "泥土");
        TAG_WORDS.put("sand", "沙子");
        TAG_WORDS.put("gravel", "沙砾");
        TAG_WORDS.put("leaves", "树叶");
        TAG_WORDS.put("sapling", "树苗");
        TAG_WORDS.put("saplings", "树苗");
        TAG_WORDS.put("flower", "花");
        TAG_WORDS.put("flowers", "花");
        TAG_WORDS.put("crop", "作物");
        TAG_WORDS.put("crops", "作物");
        TAG_WORDS.put("seed", "种子");
        TAG_WORDS.put("seeds", "种子");
        TAG_WORDS.put("food", "食物");
        TAG_WORDS.put("foods", "食物");
        TAG_WORDS.put("drink", "饮料");
        TAG_WORDS.put("drinks", "饮料");
        TAG_WORDS.put("meat", "肉");
        TAG_WORDS.put("meats", "肉");
        TAG_WORDS.put("fish", "鱼");
        TAG_WORDS.put("fishes", "鱼");
        TAG_WORDS.put("vegetable", "蔬菜");
        TAG_WORDS.put("vegetables", "蔬菜");
        TAG_WORDS.put("fruit", "水果");
        TAG_WORDS.put("fruits", "水果");
        TAG_WORDS.put("berry", "浆果");
        TAG_WORDS.put("berries", "浆果");
        TAG_WORDS.put("mushroom", "蘑菇");
        TAG_WORDS.put("mushrooms", "蘑菇");
        TAG_WORDS.put("nut", "坚果");
        TAG_WORDS.put("nuts", "坚果");
        TAG_WORDS.put("egg", "蛋");
        TAG_WORDS.put("eggs", "蛋");
        TAG_WORDS.put("honey", "蜂蜜");
        TAG_WORDS.put("sugar", "糖");
        TAG_WORDS.put("sword", "剑");
        TAG_WORDS.put("swords", "剑");
        TAG_WORDS.put("pickaxe", "镐");
        TAG_WORDS.put("pickaxes", "镐");
        TAG_WORDS.put("axe", "斧");
        TAG_WORDS.put("axes", "斧");
        TAG_WORDS.put("shovel", "锹");
        TAG_WORDS.put("shovels", "锹");
        TAG_WORDS.put("hoe", "锄");
        TAG_WORDS.put("hoes", "锄");
        TAG_WORDS.put("tool", "工具");
        TAG_WORDS.put("tools", "工具");
        TAG_WORDS.put("armor", "盔甲");
        TAG_WORDS.put("helmet", "头盔");
        TAG_WORDS.put("helmets", "头盔");
        TAG_WORDS.put("chestplate", "胸甲");
        TAG_WORDS.put("chestplates", "胸甲");
        TAG_WORDS.put("leggings", "护腿");
        TAG_WORDS.put("boot", "靴子");
        TAG_WORDS.put("boots", "靴子");
        TAG_WORDS.put("shield", "盾牌");
        TAG_WORDS.put("shields", "盾牌");
        TAG_WORDS.put("bow", "弓");
        TAG_WORDS.put("bows", "弓");
        TAG_WORDS.put("crossbow", "弩");
        TAG_WORDS.put("crossbows", "弩");
        TAG_WORDS.put("arrow", "箭");
        TAG_WORDS.put("arrows", "箭");
        TAG_WORDS.put("potion", "药水");
        TAG_WORDS.put("potions", "药水");
        TAG_WORDS.put("enchantable", "可附魔");
        TAG_WORDS.put("enchatable", "可附魔");
        TAG_WORDS.put("durability", "耐久");
        TAG_WORDS.put("vanishing", "消失");
        TAG_WORDS.put("trim", "纹饰");
        TAG_WORDS.put("trims", "纹饰");
        TAG_WORDS.put("banner", "旗帜");
        TAG_WORDS.put("banners", "旗帜");
        TAG_WORDS.put("sign", "告示牌");
        TAG_WORDS.put("signs", "告示牌");
        TAG_WORDS.put("boat", "船");
        TAG_WORDS.put("boats", "船");
        TAG_WORDS.put("rail", "铁轨");
        TAG_WORDS.put("rails", "铁轨");
        TAG_WORDS.put("torch", "火把");
        TAG_WORDS.put("torches", "火把");
        TAG_WORDS.put("candle", "蜡烛");
        TAG_WORDS.put("candles", "蜡烛");
        TAG_WORDS.put("book", "书");
        TAG_WORDS.put("books", "书");
        TAG_WORDS.put("music", "音乐");
        TAG_WORDS.put("disc", "唱片");
        TAG_WORDS.put("discs", "唱片");
        TAG_WORDS.put("circuit", "电路");
        TAG_WORDS.put("machine", "机器");
        TAG_WORDS.put("machines", "机器");
        TAG_WORDS.put("component", "元件");
        TAG_WORDS.put("components", "元件");
        TAG_WORDS.put("core", "核心");
        TAG_WORDS.put("cores", "核心");
        TAG_WORDS.put("energy", "能量");
        TAG_WORDS.put("cable", "线缆");
        TAG_WORDS.put("cables", "线缆");
        TAG_WORDS.put("pipe", "管道");
        TAG_WORDS.put("pipes", "管道");
        TAG_WORDS.put("tank", "储罐");
        TAG_WORDS.put("tanks", "储罐");
        TAG_WORDS.put("fluid", "流体");
        TAG_WORDS.put("fluids", "流体");
        TAG_WORDS.put("gas", "气体");
        TAG_WORDS.put("gases", "气体");
        TAG_WORDS.put("chemical", "化学品");
        TAG_WORDS.put("chemicals", "化学品");
        TAG_WORDS.put("nuclear", "核");
        TAG_WORDS.put("reactor", "反应堆");
        TAG_WORDS.put("battery", "电池");
        TAG_WORDS.put("batteries", "电池");
        TAG_WORDS.put("chip", "芯片");
        TAG_WORDS.put("chips", "芯片");
        TAG_WORDS.put("processor", "处理器");
        TAG_WORDS.put("processors", "处理器");
        TAG_WORDS.put("wafer", "晶圆");
        TAG_WORDS.put("wafers", "晶圆");
        TAG_WORDS.put("silicon", "硅");
        TAG_WORDS.put("dye_base", "染料基底");
        TAG_WORDS.put("buckets", "桶");
        TAG_WORDS.put("bucket", "桶");
        TAG_WORDS.put("water", "水");
        TAG_WORDS.put("lava", "岩浆");
        TAG_WORDS.put("milk", "牛奶");
        TAG_WORDS.put("leather", "皮革");
        TAG_WORDS.put("string", "线");
        TAG_WORDS.put("feather", "羽毛");
        TAG_WORDS.put("paper", "纸");
        TAG_WORDS.put("bone", "骨头");
        TAG_WORDS.put("slime", "黏液");
        TAG_WORDS.put("sword_blade", "剑刃");
        TAG_WORDS.put("arrowheads", "箭头");
        TAG_WORDS.put("tools_and_armor", "工具与盔甲");
        TAG_WORDS.put("decorations", "装饰");
        TAG_WORDS.put("building", "建筑");
        TAG_WORDS.put("natural", "自然");
        TAG_WORDS.put("plants", "植物");
        TAG_WORDS.put("mobs", "生物");
        TAG_WORDS.put("spawn", "生成");
        TAG_WORDS.put("eggs_spawn", "生成蛋");
    }
    /**
     * ★ 猜你想搜（三档，越靠前越可信）：
     *   ① 别名表：土豆 → 马铃薯（俗称 / 错别字，人工整理，见 jeiguess_aliases.json）
     *   ② 编辑距离 ≤ 1 且字数相同：砖石 → 钻石
     *   ③ 拼音首字母相同且字数相同：会议室 → 黑曜石
     *
     * 只返回**真实存在于物品名索引**的名字；任何一档拿不准就返回 null。
     * 宁可没有提示，也不给错的提示。
     */
    private static String correction(String input) {
        String key = input.trim();
        if (key.length() < 2 || key.length() > 16) return null;
        String lower = key.toLowerCase(Locale.ROOT);
        if (ItemNameIndex.hasName(key)) return null;              // 本身就是物品名，不要瞎猜
        if (modAliases().containsKey(lower)) return null;
        if (tagQueryOf(key) != null) return null;

        // ① 别名表（俗称 / 错字）
        String alias = AliasIndex.lookup(key);
        if (alias != null) return alias;

        // ② 只差一个字，且这个字**同音**（砖/钻 都是 z）。
        //   只靠编辑距离会把 2 字词配得满世界都是：红石→红色、石英→石磨、原木→橡木。
        for (String n : ItemNameIndex.allNames()) {
            if (n.length() != key.length()) continue;
            if (oneSubstitutionSameSound(lower, n.toLowerCase(Locale.ROOT))) return n;
        }

        // ③ 拼音首字母全等（谐音错字：会议室 → 黑曜石）。
        //   只对 3 字及以上生效：2 字词的首字母组合太少（hs/sy/ym），
        //   几乎必然撞上一堆无关物品（红石→红色、石英→酸液），误报率高得没法用。
        if (key.length() < 3) return null;
        String ini = PinyinBridge.initials(key);
        if (ini != null && !ini.isEmpty()) {
            String best = null;
            int bestD = Integer.MAX_VALUE;
            for (String n : ItemNameIndex.byInitials(ini)) {
                if (n.length() != key.length()) continue;
                int d = distance(lower, n.toLowerCase(Locale.ROOT));
                if (d < bestD) { bestD = d; best = n; }
            }
            if (best != null) return best;
        }
        // ④ 同音字替换：稿 → 镐。
        //   「稿子」这种错字，游戏里并没有「镐子」，但有 86 件「…镐」。
        //   做法：把错字换成**同音字**（多音字按全部读音比，镐 = hao4/gao3），
        //   再看换完的词能搜到多少物品；搜不到东西的替换一律不用。
        //   结果可能不是物品全名（而是「镐」这种能搜到 86 件的词），点击照样直接搜。
        if (key.length() >= 2) {
            java.util.LinkedHashSet<String> cands = new java.util.LinkedHashSet<>();
            int replaced = 0;
            for (int i = 0; i < key.length() && replaced < 12; i++) {
                char c = key.charAt(i);
                String[] rs = PinyinBridge.readingsOf(c);
                if (rs == null) continue;
                for (String r : rs) {
                    for (char alt : ItemNameIndex.charsWithReading(r)) {
                        if (alt == c || replaced >= 12) continue;
                        String swapped = key.substring(0, i) + alt + key.substring(i + 1);
                        if (ItemNameIndex.hasName(swapped)) return swapped;
                        // 逐级缩短，但保留被替换的那个字（长度至少 i+1）
                        for (int len = swapped.length(); len >= i + 1; len--) {
                            cands.add(swapped.substring(0, len));
                        }
                        replaced++;
                    }
                }
            }
            if (!cands.isEmpty()) {
                String best = null;
                int bestHits = 2;                     // 至少要能搜到 3 件才算有用
                for (java.util.Map.Entry<String, Integer> e : ItemNameIndex.countAll(cands, 9).entrySet()) {
                    int h = e.getValue();
                    if (h <= bestHits) continue;
                    best = e.getKey();
                    bestHits = h;
                }
                if (best != null) return best;
            }
        }
        return null;
    }
    /**
     * ★ 两个等长词是否"只差一个字、且这个字同音"。
     *
     * 这是中文打错字的典型形态（砖石/钻石、黑耀石/黑曜石、马玲薯/马铃薯）。
     * 不同音就不算拼写错误 —— 否则 2 字词几乎能配上任何物品
     * （红石→红色、石英→石磨、原木→橡木 全是这么来的）。
     */
    private static boolean oneSubstitutionSameSound(String a, String b) {
        if (a.length() != b.length()) return false;
        int diff = -1;
        for (int i = 0; i < a.length(); i++) {
            if (a.charAt(i) == b.charAt(i)) continue;
            if (diff >= 0) return false;              // 不止一处不同
            diff = i;
        }
        if (diff < 0) return false;                   // 完全相同不算纠错
        char ca = PinyinBridge.initialOf(a.charAt(diff));
        char cb = PinyinBridge.initialOf(b.charAt(diff));
        return ca != 0 && ca == cb;
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
