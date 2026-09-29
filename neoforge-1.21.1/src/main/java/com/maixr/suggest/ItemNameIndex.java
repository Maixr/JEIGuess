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
 */
public final class ItemNameIndex {
    private static final Map<Character, List<String>> BY_FIRST = new HashMap<>();
    private static final List<String> ALL = new ArrayList<>();
    /** 显示名 -> 物品（标签推导要用到实体的物品） */
    private static final Map<String, net.minecraft.world.item.Item> BY_NAME = new HashMap<>();
    private static volatile boolean built = false;

    private ItemNameIndex() {}

    /** 分帧构建：由客户端 tick 反复调用，每次处理一批。 */
    public static void buildIncrementally() {
        if (built) return;
        try {
            java.util.List<net.minecraft.world.item.Item> items = new java.util.ArrayList<>();
            BuiltInRegistries.ITEM.forEach(items::add);
            for (Object o : items) {
                if (built) return;
                try {
                    String n = new ItemStack((net.minecraft.world.item.Item) o).getHoverName().getString();
                    if (n == null || n.length() < 2 || n.length() > 24 || n.indexOf('\u00a7') >= 0) continue;
                    if (isRawKey(n)) continue;                       // 过滤 item.tacz.attachment 这类未翻译的键

                    if (ALL.contains(n)) continue;
                    ALL.add(n);
                    BY_FIRST.computeIfAbsent(n.charAt(0), k -> new ArrayList<>()).add(n);
                    BY_NAME.putIfAbsent(n, (net.minecraft.world.item.Item) o);
                } catch (Throwable ignored) {}
            }
            ALL.sort(Comparator.comparingInt(String::length).reversed());   // 长名优先
            built = true;
            MaixrSuggestMod.LOGGER.info("[猜你想搜] 物品名索引构建完成：{} 条 / {} 个首字桶", ALL.size(), BY_FIRST.size());
        } catch (Throwable t) {
            MaixrSuggestMod.LOGGER.warn("[猜你想搜] 构建物品名索引失败: {}", t.toString());
        }
    }

    public static boolean isBuilt() { return built; }

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
        int scanned = 0;
        String best = null;
        int bestD = Integer.MAX_VALUE;
        for (String n : ALL) {
            if (Math.abs(n.length() - input.length()) > 3) continue;
            if (++scanned > 6000) break;
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
