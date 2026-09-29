package com.maixr.suggest;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.minecraftforge.fml.loading.FMLPaths;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;

/**
 * 本地数据仓库（单个 json，退出世界时落盘）。
 *
 * 存的东西：
 *   · 搜索词与频率（最近搜索 / 高频搜索 / 聊天提及）
 *   · 收藏（右键加星）与屏蔽（中键永不推荐）
 *   · 「上次搜了这个词之后点开了哪个物品」（候选置顶用）
 *   · 任务书里最近看过的任务（任务候选排序用）
 *   · 服务器热词（内存，不落盘）
 */
public final class SearchHistoryStore {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final SearchHistoryStore INSTANCE = new SearchHistoryStore();

    private final Map<String, Entry> entries = new LinkedHashMap<>();
    private final Deque<String> chatMentions = new ArrayDeque<>();
    private final LinkedHashSet<String> favorites = new LinkedHashSet<>();
    private final LinkedHashSet<String> banned = new LinkedHashSet<>();
    /** 搜索词 -> (物品 id -> 次数) */
    private final Map<String, Map<String, Integer>> picks = new LinkedHashMap<>();
    /** 物品 id -> 显示名（候选里要显示人能看懂的名字） */
    private final Map<String, String> itemNames = new LinkedHashMap<>();
    /** 最近查看过的任务（新的在前） */
    private final List<QuestView> questViews = new ArrayList<>();
    /** 服务器热词（收到才填，不落盘） */
    private final List<String> hotWords = new ArrayList<>();

    public static SearchHistoryStore get() { return INSTANCE; }

    public static final class Entry {
        public String word;
        public int count;
        public long lastUsed;
        /** true = 来自聊天提及，不计入"搜索次数" */
        public boolean fromChat;
        public Entry() {}
        public Entry(String word) { this.word = word; }
    }

    /** 任务书里看过的一个任务。 */
    public static final class QuestView {
        public long id;
        public String title = "";
        public List<String> items = new ArrayList<>();
        public long lastViewed;
        public int views;

        public QuestView() {}
        public QuestView(long id, String title, List<String> items) {
            this.id = id; this.title = title == null ? "" : title; this.items = items == null ? new ArrayList<>() : items;
        }
    }

    /** 落盘的整个数据结构。 */
    private static final class Data {
        Map<String, Entry> entries = new LinkedHashMap<>();
        List<String> chatMentions = new ArrayList<>();
        List<String> favorites = new ArrayList<>();
        List<String> banned = new ArrayList<>();
        Map<String, Map<String, Integer>> picks = new LinkedHashMap<>();
        Map<String, String> itemNames = new LinkedHashMap<>();
        List<QuestView> questViews = new ArrayList<>();
        int version = 2;
    }

    private Path file() { return FMLPaths.CONFIGDIR.get().resolve("jeiguess_history.json"); }

    /**
     * 从旧版文件名迁移**搜索数据**。
     *
     * 支持三代文件名：MaixrSearchSuggest(v0.1) / JEI Guest(v0.2) / JEI Guess(v0.3+)
     * ★ 配置文件故意不迁移：新版默认布局变了（一页 8 条的配额），
     *   搬旧配置会把旧的 4/2/2/1/1 配额一起带过来，反而看不到新布局。
     */
    public static void migrateLegacyFiles() {
        try {
            Path cfg = FMLPaths.CONFIGDIR.get();
            Path target = cfg.resolve("jeiguess_history.json");
            copyIfMissing(cfg.resolve("jeiguest_history.json"), target);       // v0.2
            copyIfMissing(cfg.resolve("maixr_search_suggest.json"), target);   // v0.1
        } catch (Throwable ignored) {}
    }

    private static void copyIfMissing(Path from, Path to) {
        try {
            if (Files.exists(from) && !Files.exists(to)) {
                Files.copy(from, to);
                MaixrSuggestMod.LOGGER.info("[猜你想搜] 已迁移旧文件 {} -> {}", from.getFileName(), to.getFileName());
            }
        } catch (Throwable ignored) {}
    }

    public synchronized void load() {
        Path p = file();
        if (!Files.exists(p)) return;
        try {
            String json = Files.readString(p, StandardCharsets.UTF_8);
            com.google.gson.JsonElement root = com.google.gson.JsonParser.parseString(json);
            Data d;
            if (root.isJsonObject() && root.getAsJsonObject().has("entries")) {
                d = GSON.fromJson(root, Data.class);
            } else {
                // ★ 兼容 v1（含从旧 mod 名迁移过来的文件）：整个文件就是一个 {词 -> Entry} 的 map
                d = new Data();
                Map<String, Entry> legacy = GSON.fromJson(root,
                        new TypeToken<LinkedHashMap<String, Entry>>() {}.getType());
                if (legacy != null) d.entries = legacy;
            }
            entries.clear();
            if (d.entries != null) entries.putAll(d.entries);
            chatMentions.clear();
            if (d.chatMentions != null) chatMentions.addAll(d.chatMentions);
            favorites.clear();
            if (d.favorites != null) favorites.addAll(d.favorites);
            banned.clear();
            if (d.banned != null) banned.addAll(d.banned);
            picks.clear();
            if (d.picks != null) picks.putAll(d.picks);
            itemNames.clear();
            if (d.itemNames != null) itemNames.putAll(d.itemNames);
            questViews.clear();
            if (d.questViews != null) questViews.addAll(d.questViews);
            MaixrSuggestMod.LOGGER.info("[猜你想搜] 已载入 {} 条搜索记录 / {} 条收藏 / {} 条屏蔽 / {} 个任务记录",
                    entries.size(), favorites.size(), banned.size(), questViews.size());
        } catch (Exception e) {
            MaixrSuggestMod.LOGGER.warn("[猜你想搜] 读取历史失败: {}", e.toString());
        }
    }

    private volatile boolean dirty = false;
    private long dirtyAt = 0L;

    /** 延迟保存（防抖）：避免每次回车都写磁盘。 */
    public synchronized void saveLater() {
        dirty = true;
        dirtyAt = System.currentTimeMillis();
    }

    /** 由 tick 调用：脏了且静置 5 秒以上才真正落盘。 */
    public void flushIfDirty() {
        if (!dirty) return;
        if (System.currentTimeMillis() - dirtyAt < 5000L) return;
        save();
    }

    public synchronized void save() {
        dirty = false;
        try {
            Data d = new Data();
            d.entries = new LinkedHashMap<>(entries);
            d.chatMentions = new ArrayList<>(chatMentions);
            d.favorites = new ArrayList<>(favorites);
            d.banned = new ArrayList<>(banned);
            d.picks = new LinkedHashMap<>(picks);
            d.itemNames = new LinkedHashMap<>(itemNames);
            d.questViews = new ArrayList<>(questViews);
            Files.createDirectories(file().getParent());
            Files.writeString(file(), GSON.toJson(d), StandardCharsets.UTF_8);
        } catch (IOException e) {
            MaixrSuggestMod.LOGGER.warn("[猜你想搜] 保存历史失败: {}", e.toString());
        }
    }

    // ───────────────────────── 搜索历史 ─────────────────────────

    /** 记录一次搜索（玩家在 JEI 搜索框里实际搜过的词）。 */
    public synchronized void record(String word) {
        if (word == null) return;
        String w = word.trim();
        if (w.isEmpty() || w.length() > 64) return;
        Entry e = entries.computeIfAbsent(w, Entry::new);
        e.word = w;
        e.count++;
        e.lastUsed = System.currentTimeMillis();
        e.fromChat = false;
        trim();
    }

    /**
     * ★ 记录一次"玩家真的搜了这个词"。
     *
     * 之前只有"从候选面板点了一条"才会进历史 —— 玩家自己在 JEI 搜索框手打并回车的词
     * 根本不进库，于是【最近】显示的永远是以前点过的那几个，刚搜完的反而不出现。
     * 同一个词 2 分钟内重复出现只刷新时间、不叠加次数（避免边打字边计数）。
     */
    public synchronized void recordSearch(String word) {
        if (word == null) return;
        String w = word.trim();
        if (w.isEmpty() || w.length() > 64) return;
        Entry e = entries.computeIfAbsent(w, Entry::new);
        long now = System.currentTimeMillis();
        if (now - e.lastUsed > 120_000L) e.count++;      // 两分钟内的重复只算一次搜索
        e.word = w;
        e.lastUsed = now;
        e.fromChat = false;
        trim();
        saveLater();
    }

    /** 记录聊天里提到的物品（不增加搜索次数，但会出现在候选里）。 */
    public synchronized void recordChatMention(String name) {
        if (name == null || name.isBlank()) return;
        String w = name.trim();
        if (w.length() > 32) return;
        chatMentions.remove(w);
        chatMentions.addFirst(w);
        while (chatMentions.size() > 80) chatMentions.removeLast();
        if (!entries.containsKey(w)) {
            Entry e = new Entry(w);
            e.fromChat = true;
            e.lastUsed = System.currentTimeMillis();
            entries.put(w, e);
            trim();
        } else {
            entries.get(w).lastUsed = System.currentTimeMillis();
        }
    }

    /** ★ 记录「搜了这个词之后点开了哪个物品」—— 下次同类搜索把该物品置顶。 */
    public synchronized void recordPick(String query, String itemId, String itemName) {
        if (query == null || itemId == null) return;
        String q = query.trim().toLowerCase(Locale.ROOT);
        if (q.isEmpty() || q.length() > 64) return;
        Map<String, Integer> m = picks.computeIfAbsent(q, k -> new LinkedHashMap<>());
        m.merge(itemId, 1, Integer::sum);
        if (itemName != null && !itemName.isBlank()) itemNames.put(itemId, itemName);
        // 每个搜索词只留最多的 5 个物品
        if (m.size() > 5) {
            List<Map.Entry<String, Integer>> l = new ArrayList<>(m.entrySet());
            l.sort(Comparator.comparingInt(e -> -e.getValue()));
            m.clear();
            for (int i = 0; i < 5; i++) m.put(l.get(i).getKey(), l.get(i).getValue());
        }
        if (picks.size() > 120) {
            Iterator<String> it = picks.keySet().iterator();
            if (it.hasNext()) { it.next(); it.remove(); }
        }
    }

    /** 某个搜索词下、按点击次数排序的物品（显示名，可能为空列表）。 */
    public synchronized List<String> pickedNames(String query, int limit) {
        List<String> out = new ArrayList<>();
        if (query == null) return out;
        Map<String, Integer> m = picks.get(query.trim().toLowerCase(Locale.ROOT));
        if (m == null || m.isEmpty()) return out;
        List<Map.Entry<String, Integer>> l = new ArrayList<>(m.entrySet());
        l.sort(Comparator.comparingInt((Map.Entry<String, Integer> e) -> e.getValue()).reversed());
        for (Map.Entry<String, Integer> e : l) {
            if (out.size() >= limit) break;
            String name = itemNames.get(e.getKey());
            if (name != null && !name.isBlank() && !out.contains(name)) out.add(name);
        }
        return out;
    }

    private void trim() {
        int max = 200;
        try { max = SuggestConfig.MAX_HISTORY.get(); } catch (Throwable ignored) {}
        if (entries.size() <= max) return;
        List<Map.Entry<String, Entry>> list = new ArrayList<>(entries.entrySet());
        list.sort(Comparator.comparingLong(a -> a.getValue().lastUsed));
        while (entries.size() > max && !list.isEmpty()) {
            entries.remove(list.remove(0).getKey());
        }
    }

    /** 最近搜索（不含仅聊天提及的词），按时间倒序。 */
    public synchronized List<String> recent(int limit) {
        List<Entry> list = new ArrayList<>(entries.values());
        list.removeIf(e -> e.fromChat || e.count <= 0);
        list.sort(Comparator.comparingLong((Entry e) -> e.lastUsed).reversed());
        List<String> out = new ArrayList<>();
        for (Entry e : list) { if (out.size() >= limit) break; out.add(e.word); }
        return out;
    }

    /** 高频搜索，按次数倒序。 */
    public synchronized List<String> frequent(int limit) {
        List<Entry> list = new ArrayList<>(entries.values());
        list.removeIf(e -> e.count <= 1);
        list.sort(Comparator.comparingInt((Entry e) -> e.count).reversed());
        List<String> out = new ArrayList<>();
        for (Entry e : list) { if (out.size() >= limit) break; out.add(e.word); }
        return out;
    }

    /** 聊天提及过的物品，按时间倒序。 */
    public synchronized List<String> chatRecent(int limit) {
        List<String> out = new ArrayList<>();
        for (String s : chatMentions) { if (out.size() >= limit) break; out.add(s); }
        return out;
    }

    public synchronized int countOf(String word) {
        Entry e = entries.get(word);
        return e == null ? 0 : e.count;
    }

    /** 全部已知词（纠错用）。 */
    public synchronized List<String> allWords() {
        return new ArrayList<>(entries.keySet());
    }

    /** ★ 从历史/聊天池里删掉一个词（Shift+右键）。 */
    public synchronized boolean forget(String word) {
        if (word == null) return false;
        boolean any = entries.remove(word) != null;
        any |= chatMentions.remove(word);
        return any;
    }

    // ───────────────────────── 收藏 / 屏蔽 ─────────────────────────

    public synchronized boolean isFavorite(String word) { return word != null && favorites.contains(word); }

    /** 切换收藏状态；返回切换后是否已收藏。 */
    public synchronized boolean toggleFavorite(String word) {
        if (word == null || word.isBlank()) return false;
        boolean now;
        if (favorites.contains(word)) { favorites.remove(word); now = false; }
        else { favorites.add(word); now = true; }
        saveLater();
        return now;
    }

    public synchronized List<String> favorites(int limit) {
        List<String> out = new ArrayList<>();
        for (String s : favorites) { if (out.size() >= limit) break; out.add(s); }
        return out;
    }

    public synchronized List<String> allFavorites() { return new ArrayList<>(favorites); }

    public synchronized boolean isBanned(String word) { return word != null && banned.contains(word); }

    /** ★ 中键：永不推荐这个词。 */
    public synchronized void ban(String word) {
        if (word == null || word.isBlank()) return;
        banned.add(word);
        saveLater();
    }

    public synchronized boolean unban(String word) {
        boolean any = banned.remove(word);
        if (any) saveLater();
        return any;
    }

    public synchronized List<String> allBanned() { return new ArrayList<>(banned); }

    // ───────────────────────── 任务浏览记录 ─────────────────────────

    /** ★ 任务书里正在查看的任务（最近查看 / 查看次数最多 都从这里来）。 */
    public synchronized void recordQuestView(long id, String title, List<String> items) {
        for (QuestView v : questViews) {
            if (v.id == id) {
                v.lastViewed = System.currentTimeMillis();
                v.views++;
                if (title != null && !title.isBlank()) v.title = title;
                if (items != null && !items.isEmpty()) v.items = new ArrayList<>(items);
                return;
            }
        }
        QuestView v = new QuestView(id, title, items);
        v.lastViewed = System.currentTimeMillis();
        v.views = 1;
        questViews.add(0, v);
        while (questViews.size() > 40) questViews.remove(questViews.size() - 1);
        saveLater();
    }

    public synchronized List<QuestView> questViews() { return new ArrayList<>(questViews); }

    // ───────────────────────── 服务器热词 ─────────────────────────

    public synchronized void setHotWords(List<String> words) {
        hotWords.clear();
        if (words != null) hotWords.addAll(words);
    }

    public synchronized List<String> hotWords(int limit) {
        List<String> out = new ArrayList<>();
        for (String s : hotWords) { if (out.size() >= limit) break; out.add(s); }
        return out;
    }

    // ───────────────────────── 导出 ─────────────────────────

    /** 导出 CSV（整合包作者看玩家在搜什么）。返回写出路径。 */
    public synchronized Path exportCsv() throws IOException {
        Path dir = FMLPaths.CONFIGDIR.get();
        Files.createDirectories(dir);
        String stamp = java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
                .withZone(java.time.ZoneId.systemDefault()).format(Instant.now());
        Path out = dir.resolve("jeiguess-export-" + stamp + ".csv");
        StringBuilder sb = new StringBuilder("word,count,lastUsedISO,fromChat,favorite,banned,pickedItems\n");
        List<Entry> list = new ArrayList<>(entries.values());
        list.sort(Comparator.comparingLong((Entry e) -> e.lastUsed).reversed());
        for (Entry e : list) {
            sb.append(csv(e.word)).append(',')
              .append(e.count).append(',')
              .append(Instant.ofEpochMilli(e.lastUsed).toString()).append(',')
              .append(e.fromChat).append(',')
              .append(favorites.contains(e.word)).append(',')
              .append(banned.contains(e.word)).append(',')
              .append(csv(String.join(" | ", pickedNames(e.word, 5))))
              .append('\n');
        }
        Files.writeString(out, sb.toString(), StandardCharsets.UTF_8);
        return out;
    }

    private static String csv(String s) {
        if (s == null) return "";
        String v = s.replace("\"", "\"\"");
        if (v.indexOf(',') >= 0 || v.indexOf('"') >= 0 || v.indexOf('\n') >= 0) return "\"" + v + "\"";
        return v;
    }
}
