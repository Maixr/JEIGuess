package com.maixr.suggest;

import net.minecraft.world.item.ItemStack;
import net.minecraft.core.registries.BuiltInRegistries;

import java.util.*;

/**
 * 物品名索引。
 *
 * 性能设计（旧版每条聊天消息要遍历 2 万个名字，会卡）：
 *   · 按「首字符」分桶 —— 只检查文本里真正出现过的字符对应的桶
 *   · 构建分帧进行（每 tick 若干批），不阻塞渲染线程
 *   · 匹配支持拼音（复用 PinyinBridge）
 *
 * ★ 修正记录：这个方法名一直叫 buildIncrementally、注释也写着"分帧"，
 *   但实现是**一次调用遍历全部物品**。整合包有两万多个物品时，
 *   它会在渲染线程里卡住，而且期间 isBuilt() 一直是 false ——
 *   表现为「第一次打开面板没反应 / 搜索框里打什么都被当成没匹配」。
 *   现在改成真正的分帧：维护一个迭代游标，每次只处理 BATCH 个。
 */
public final class ItemNameIndex {
    private static final Map<Character, List<String>> BY_FIRST = new HashMap<>();
    private static final List<String> ALL = new ArrayList<>();
    /** 显示名 -> 物品（标签推导要用到实体的物品） */
    private static final Map<String, net.minecraft.world.item.Item> BY_NAME = new HashMap<>();
    /** ★ 拼音首字母 → 物品名（谐音纠错用：hys → 黑曜石） */
    private static final Map<String, List<String>> BY_INITIALS = new HashMap<>();
    private static volatile boolean built = false;

    /** 每 tick 处理多少个物品（2 万物品约 50 tick 建完，约 2.5 秒，且不卡帧） */
    private static final int BATCH = 400;
    /** 迭代游标：分帧构建的状态 */
    private static Iterator<net.minecraft.world.item.Item> cursor = null;
    /** 累计处理数（打日志用） */
    private static int scanned = 0;
    /** 过滤原因统计（诊断"为什么只收录了这么点"） */
    private static final java.util.Map<String, Integer> FILTER = new java.util.LinkedHashMap<>();
    private static final java.util.List<String> SAMPLES = new java.util.ArrayList<>();
    private static final java.util.List<String> LONG = new java.util.ArrayList<>();

    private ItemNameIndex() {}

    /**
     * ★ 分帧构建：由客户端 tick 反复调用，每次只处理 BATCH 个物品。
     * 第一次调用建立游标，之后每次推进一批，处理完才置 built = true。
     */
    public static void buildIncrementally() {
        // ★★ 自愈：如果语言表在我们建完索引之后又变了（汉化包姗姗来迟），
        //   之前建好的索引就是**错的**（里面全是没翻译的原始键或英文名）。
        //   与其反复调"什么时候开始建"的判据，不如让索引自己发现并重建。
        if (built && langChangedSinceBuild()) {
            MaixrSuggestMod.LOGGER.info("[猜你想搜] 语言表已变化（{} → {}），重建物品名索引",
                    builtLangSize, langSizeSafe());
            reset();
        }
        if (built) return;
        // ★★ 必须等**语言表**就绪再建索引（不是"等进世界"）。
        //   实测时间线：Reloading ResourceManager → 3 秒后索引就建完了，
        //   但那时语言表还没装好，getHoverName() 返回的是 item.ae2.cell_component_256k
        //   这类原始翻译键，会被 isRawKey() 全数丢弃 ——
        //   结果索引只剩原版那一千来个物品，模组物品一个都搜不到。
        //   所以判据用「语言表里有没有翻译」本身，最可靠。
        if (!readyToBuild()) return;
        try {
            if (cursor == null) {
                cursor = BuiltInRegistries.ITEM.iterator();
                scanned = 0;
            }
            int n = 0;
            while (cursor.hasNext() && n < BATCH) {
                addOne(cursor.next());
                n++;
                scanned++;
            }
            if (!cursor.hasNext()) {
                ALL.sort(Comparator.comparingInt(String::length).reversed());   // 长名优先
                built = true;
                builtLangSize = langSizeSafe();
                MaixrSuggestMod.LOGGER.info("[猜你想搜] 物品名索引构建完成：{} 条 / {} 个首字桶（共扫描 {} 个物品，语言表={}）",
                        ALL.size(), BY_FIRST.size(), scanned, readyToBuild() ? "就绪" : "★未就绪");
                MaixrSuggestMod.LOGGER.info("[猜你想搜] 过滤统计: {}", FILTER);
                if (!SAMPLES.isEmpty()) MaixrSuggestMod.LOGGER.info("[猜你想搜] 原始键样例: {}", SAMPLES);
                if (!LONG.isEmpty()) MaixrSuggestMod.LOGGER.info("[猜你想搜] 超长名字样例: {}", LONG);
            }
        } catch (Throwable t) {
            MaixrSuggestMod.LOGGER.warn("[猜你想搜] 构建物品名索引失败: {}", t.toString());
            built = true;                                  // 失败也要放行，否则永远卡在 false
        }
    }

    private static void addOne(net.minecraft.world.item.Item item) {
        try {
            String n = new ItemStack(item).getHoverName().getString();
            if (n == null) { bump("名字为null"); return; }
            if (n.length() < 2) { bump("名字太短"); return; }
            if (n.length() > 64) { bump("名字超过64字符"); if (LONG.size() < 12) LONG.add(n + "[" + n.length() + "]"); return; }
            if (n.indexOf('\u00a7') >= 0) { bump("含格式符"); return; }
            if (isRawKey(n)) { bump("是原始翻译键"); if (SAMPLES.size() < 8) SAMPLES.add(n); return; }
            if (ALL.contains(n)) { bump("重名"); return; }
            ALL.add(n);
            BY_FIRST.computeIfAbsent(n.charAt(0), k -> new ArrayList<>()).add(n);
            BY_NAME.putIfAbsent(n, item);
            String ini = PinyinBridge.initials(n);
            if (ini != null && !ini.isEmpty()) BY_INITIALS.computeIfAbsent(ini, k -> new ArrayList<>()).add(n);
        } catch (Throwable ignored) {}
    }

    private static void bump(String why) { FILTER.merge(why, 1, Integer::sum); }

    /**
     * ★ 语言表是否已就绪 —— 用几个必定存在的翻译键做抽样。
     *
     * 为什么不用 level == null：实测整合包启动时，ResourceManager 刚开始重载
     * 索引就建完了，那时 level 可能已经非空、但语言表还是空的，
     * 照样会把所有模组物品名当"原始翻译键"丢掉。
     */
    /** 上一次看到的语言表条目数 */
    private static int lastLangSize = -1;
    /** 语言表连续稳定的 tick 数 */
    private static int stableTicks = 0;
    /** ★ 建索引时语言表的条目数（用于自愈：语言表变了就重建） */
    private static int builtLangSize = -1;

    /**
     * ★ 现在能不能安全建索引？两个条件都要满足：
     *
     *  ① **已经进入世界**（level != null）
     *     实测：索引在 15:50:08 建完（资源重载刚开始），而带 level 保护的
     *     聊天链接索引 15:52:15 才建完 —— 晚了 2 分钟。那 2 分钟的差别就是
     *     "汉化资源包有没有最终生效"。
     *
     *  ② **语言表条目数连续 20 tick 不变**
     *     只看"有没有翻译"是不够的：原版英文键在任何语言下都为真；
     *     看特定 mod 的键又会因为整合包没装那个 mod 而永远为假。
     *     看条目总数稳定，才既不依赖具体 mod、又能等到汉化包挂完。
     */
    public static boolean readyToBuild() {
        try {
            if (net.minecraft.client.Minecraft.getInstance().level == null) return false;
        } catch (Throwable t) {
            return false;
        }
        try {
            int size = net.minecraft.locale.Language.getInstance().getLanguageData().size();
            if (size == lastLangSize) {
                stableTicks++;
            } else {
                lastLangSize = size;
                stableTicks = 0;
                return false;
            }
            return stableTicks >= 20;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 清空索引状态，准备重建。 */
    private static void reset() {
        ALL.clear();
        BY_FIRST.clear();
        BY_NAME.clear();
        BY_INITIALS.clear();
        FILTER.clear();
        SAMPLES.clear();
        cursor = null;
        scanned = 0;
        stableTicks = 0;
        lastLangSize = -1;
        built = false;
        builtLangSize = -1;
    }

    /** 语言表条目数（异常时返回 -1）。 */
    private static int langSizeSafe() {
        try { return net.minecraft.locale.Language.getInstance().getLanguageData().size(); }
        catch (Throwable t) { return -1; }
    }

    /** 建完索引之后语言表是否又变了。 */
    private static boolean langChangedSinceBuild() {
        if (builtLangSize <= 0) return false;
        int now = langSizeSafe();
        if (now <= 0) return false;
        // 变化超过 10% 就认为语言表换了（汉化包加载会让条目数翻好几倍）
        return now != builtLangSize && Math.abs(now - builtLangSize) > builtLangSize / 10;
    }

    /** 索引里是否有这个名字（别名/纠错的合法性校验）。 */
    public static boolean hasName(String n) { return n != null && BY_NAME.containsKey(n); }

    /** 拼音首字母完全相同的物品名（谐音纠错用）。 */
    public static List<String> byInitials(String ini) {
        return ini == null ? List.of() : BY_INITIALS.getOrDefault(ini, List.of());
    }

    public static boolean isBuilt() { return built; }

    /** 已扫描的物品数（诊断用）。 */
    public static int scannedCount() { return scanned; }

    /** 索引里的名字条数（诊断用）。 */
    public static int size() { return ALL.size(); }

    /** ★ 诊断：索引状态一行说明（排查"搜不到东西"时先看它）。 */
    public static String diagnose() {
        if (built) return "已建好 " + ALL.size() + " 条 / " + BY_FIRST.size() + " 桶";
        return "构建中 " + scanned + " 个 / 已收录 " + ALL.size() + " 条（游标 " + (cursor == null ? "未初始化" : (cursor.hasNext() ? "进行中" : "已完成")) + "）";
    }

    /** 是否是原始翻译键（形如 item.tacz.attachment / block.xxx.yyy），这类不是给人看的名字。 */
    private static boolean isRawKey(String n) {
        if (n.indexOf(' ') >= 0) return false;                       // 有空格 = 正常名字
        String[] parts = n.split("\\.");
        if (parts.length < 3) return false;
        for (String p : parts) if (p.isEmpty() || !p.equals(p.toLowerCase(java.util.Locale.ROOT))) return false;
        return parts[0].equals("item") || parts[0].equals("block") || parts[0].equals("tile")
                || parts[0].equals("entity") || parts[0].equals("fluid") || parts[0].equals("gui");
    }

    /** 从一段文本里找出提到的物品名（最多 3 个）：只搜文本中出现的字符对应的桶。 */
    public static List<String> matchIn(String text) {
        List<String> found = new ArrayList<>();
        if (!built || text == null || text.isBlank()) return found;
        Set<Character> chars = new HashSet<>();
        for (int i = 0; i < text.length(); i++) chars.add(text.charAt(i));
        for (char c : chars) {
            List<String> bucket = BY_FIRST.get(c);
            if (bucket == null) continue;
            for (String n : bucket) {
                if (found.size() >= 3) return found;
                if (text.contains(n) && !found.contains(n)) found.add(n);
            }
        }
        return found;
    }

    /**
     * ★ 纠错用：在物品名里找"最像"的那几个（编辑距离 ≤ 2）。
     * 只比较长度接近的名字，且最多扫 6000 条，保证每次输入刷新都很快。
     */
    public static List<String> nearest(String input, int limit) {
        List<String> out = new ArrayList<>();
        if (!built || input == null || input.isBlank() || limit <= 0) return out;
        String key = input.toLowerCase(Locale.ROOT);
        int scanned2 = 0;
        String best = null;
        int bestD = Integer.MAX_VALUE;
        for (String n : ALL) {
            if (Math.abs(n.length() - input.length()) > 3) continue;
            if (++scanned2 > 6000) break;
            int d = editDistance(key, n.toLowerCase(Locale.ROOT), 3);
            if (d < bestD) { bestD = d; best = n; }
        }
        if (best != null && bestD <= 2) out.add(best);
        return out;
    }

    /** 带上限的 Levenshtein 距离（超过 limit 直接返回 limit+1）。 */
    private static int editDistance(String a, String b, int limit) {
        int[] prev = new int[b.length() + 1];
        int[] cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) prev[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            int rowMin = cur[0];
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
                if (cur[j] < rowMin) rowMin = cur[j];
            }
            if (rowMin > limit) return limit + 1;
            int[] t = prev; prev = cur; cur = t;
        }
        return prev[b.length()];
    }

    /** 显示名对应的物品（标签推导用）。 */
    public static net.minecraft.world.item.Item itemOf(String name) { return BY_NAME.get(name); }

    /** 全部物品名（只读快照，纠错/导出用）。 */
    public static List<String> allNames() { return new ArrayList<>(ALL); }

    /** 按输入建议物品名（支持拼音）。 */
    public static List<String> suggestNames(String input, int limit, boolean prefixOnly) {
        List<String> out = new ArrayList<>();
        if (!built || input == null || input.isBlank()) return out;
        for (String n : ALL) {
            if (out.size() >= limit) break;
            if (PinyinBridge.matches(n, input, prefixOnly)) out.add(n);
        }
        return out;
    }
}
