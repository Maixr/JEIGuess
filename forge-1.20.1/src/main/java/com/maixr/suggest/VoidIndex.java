package com.maixr.suggest;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * ★ 亚空间索引：JEI「管不到」但游戏里真实存在的物品。
 *
 * 两类：
 *   ① 在物品注册表里、但 JEI 的原料管理器里没有 —— 典型如命令方块、屏障、结构空位、调试棒，
 *      还有被整合包用 JEI 隐藏机制藏起来的物品。
 *   ② 名字是**原始翻译键**的物品（形如 item.tacz.attachment / block.xxx.yyy）——
 *      没有 lang 条目，中文名就是键名本身，正常搜索永远命中不了。
 *
 * 这个索引本身只是在内存里做一次注册表遍历，很便宜；构建一次后缓存 5 分钟。
 */
public final class VoidIndex {
    /** 一条「亚空间」物品。 */
    public static final class Entry {
        /** minecraft:command_block */
        public final String id;
        /** 注册表路径 command_block */
        public final String path;
        /** 游戏里显示的名字（可能是原始翻译键） */
        public final String name;
        /** 名字是不是原始翻译键（没有 lang） */
        public final boolean rawKey;

        Entry(String id, String path, String name, boolean rawKey) {
            this.id = id;
            this.path = path;
            this.name = name;
            this.rawKey = rawKey;
        }
    }

    private static List<Entry> all = null;
    private static long builtAt = 0L;
    private static int lastJeiCount = -1;

    private VoidIndex() {}

    /** 全部亚空间物品（缓存 5 分钟；JEI 列表变化时重建）。 */
    public static List<Entry> all() {
        long now = System.currentTimeMillis();
        if (all != null && now - builtAt < 300_000L) return all;
        builtAt = now;
        List<Entry> out = new ArrayList<>();
        try {
            Set<String> jeiIds = JeiBridge.jeiItemIds();     // JEI 管得到的物品 id
            if (jeiIds == null) return all == null ? List.of() : all;
            if (all != null && jeiIds.size() == lastJeiCount) return all;
            lastJeiCount = jeiIds.size();

            for (Item item : BuiltInRegistries.ITEM) {
                try {
                    ResourceLocation key = BuiltInRegistries.ITEM.getKey(item);
                    if (key == null) continue;
                    String id = key.toString();
                    String name = new ItemStack(item).getHoverName().getString();
                    boolean raw = isRawKey(name);
                    boolean inJei = jeiIds.contains(id);
                    // ★ 只收：JEI 没有的，或者名字是原始键的
                    if (inJei && !raw) continue;
                    if (name == null || name.isBlank()) name = key.getPath();
                    out.add(new Entry(id, key.getPath(), name, raw));
                } catch (Throwable ignored) {}
            }
            all = out;
            MaixrSuggestMod.LOGGER.info("[猜你想搜] 亚空间索引已建立：{} 条（JEI 收录 {} 个物品）", out.size(), jeiIds.size());
        } catch (Throwable t) {
            MaixrSuggestMod.LOGGER.info("[猜你想搜] 建立亚空间索引失败: {}", t.toString());
            if (all == null) all = List.of();
        }
        return all;
    }

    public static boolean isReady() { return all != null && !all.isEmpty(); }

    /** 名字是不是原始翻译键（item.xxx.yyy / block.xxx.yyy 这种）。 */
    private static boolean isRawKey(String n) {
        if (n == null || n.indexOf(' ') >= 0) return false;
        String[] parts = n.split("\\.");
        if (parts.length < 3) return false;
        for (String p : parts) if (p.isEmpty() || !p.equals(p.toLowerCase(Locale.ROOT))) return false;
        return parts[0].equals("item") || parts[0].equals("block") || parts[0].equals("tile")
                || parts[0].equals("entity") || parts[0].equals("fluid") || parts[0].equals("gui");
    }

    /**
     * 按输入找亚空间物品。
     * 匹配顺序：名字前缀 → id 前缀 → 名字包含 → id 包含。
     */
    public static List<Entry> find(String input, int limit) {
        List<Entry> out = new ArrayList<>();
        if (limit <= 0 || input == null || input.isBlank()) return out;
        String key = input.trim().toLowerCase(Locale.ROOT);
        List<Entry> data = all();
        if (data.isEmpty()) return out;

        Set<String> taken = new HashSet<>();
        // ① 名字前缀 / id 前缀
        for (Entry e : data) {
            if (out.size() >= limit) break;
            if (e.name.toLowerCase(Locale.ROOT).startsWith(key) || e.id.toLowerCase(Locale.ROOT).startsWith(key)) {
                if (taken.add(e.id)) out.add(e);
            }
        }
        // ② 名字包含 / id 包含（至少 2 个字符，避免噪音）
        if (out.size() < limit && key.length() >= 2) {
            for (Entry e : data) {
                if (out.size() >= limit) break;
                if (e.name.toLowerCase(Locale.ROOT).contains(key) || e.id.toLowerCase(Locale.ROOT).contains(key)) {
                    if (taken.add(e.id)) out.add(e);
                }
            }
        }
        return out;
    }
}
