package com.maixr.suggest;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * ★ 亚空间索引：JEI「管不到」但游戏里真实存在的物品。
 *
 * 两类：
 *   ① 在物品注册表里、但 JEI 的原料管理器里没有 —— 命令方块、屏障、结构空位，
 *      以及被整合包用 JEI 隐藏机制藏起来的物品。
 *   ② 名字是**原始翻译键**的物品（item.tacz.attachment / block.xxx.yyy）——
 *      没有 lang 条目，显示名就是键名本身，正常搜索永远命中不了。
 *
 * ★ 性能：和 ItemNameIndex 一样**分帧构建**（每 tick 处理一批），
 *   绝不可以在渲染线程一次性遍历 2 万个物品。
 */
public final class VoidIndex {
    /** 一批处理多少个物品 */
    private static final int BATCH = 400;

    public static final class Entry {
        /** minecraft:command_block */
        public final String id;
        /** 注册表路径 command_block */
        public final String path;
        /** 游戏里显示的名字（可能是原始翻译键） */
        public final String name;
        /** 名字是不是原始翻译键（没有 lang） */
        public final boolean rawKey;
        /** 归属 mod 的显示名（从 namespace 推断） */
        public final String modName;
        /** 为什么 JEI 里没有：hidden（被 JEI 隐藏）/ nolang（没有翻译） */
        public final String why;

        Entry(String id, String path, String name, boolean rawKey, String modName, String why) {
            this.id = id; this.path = path; this.name = name;
            this.rawKey = rawKey; this.modName = modName; this.why = why;
        }
    }

    private static final List<Entry> ALL = new ArrayList<>();
    private static volatile boolean built = false;
    private static volatile boolean building = false;
    private static Set<String> jeiIds = null;
    private static Iterator<Item> cursor = null;
    private static long builtAt = 0L;

    private VoidIndex() {}

    /** 分帧构建（由客户端 tick 调用）。JEI 没就绪 / 已建好就直接返回。 */
    public static void buildIncrementally() {
        if (built || building) return;
        if (jeiIds == null) {
            jeiIds = JeiBridge.jeiItemIds();
            if (jeiIds == null) return;                      // JEI 还没就绪，下次 tick 再试
            cursor = BuiltInRegistries.ITEM.iterator();
            building = true;
        }
        try {
            int n = 0;
            while (cursor.hasNext() && n < BATCH) {
                addOne(cursor.next());
                n++;
            }
            if (!cursor.hasNext()) {
                building = false;
                built = true;
                builtAt = System.currentTimeMillis();
                MaixrSuggestMod.LOGGER.info("[猜你想搜] 亚空间索引构建完成：{} 条（JEI 收录 {} 个物品）",
                        ALL.size(), jeiIds.size());
            }
        } catch (Throwable t) {
            building = false;
            built = true;
            MaixrSuggestMod.LOGGER.info("[猜你想搜] 亚空间索引构建中断: {}", t.toString());
        }
    }

    private static void addOne(Item item) {
        try {
            ResourceLocation key = BuiltInRegistries.ITEM.getKey(item);
            if (key == null) return;
            String id = key.toString();
            String name = new ItemStack(item).getHoverName().getString();
            boolean raw = isRawKey(name);
            boolean inJei = jeiIds.contains(id);
            if (inJei && !raw) return;                       // JEI 有、名字也正常 → 不是亚空间
            if (name == null || name.isBlank()) name = key.getPath();
            String why = inJei ? "nolang" : (raw ? "nolang" : "hidden");
            ALL.add(new Entry(id, key.getPath(), name, raw, modNameOf(key.getNamespace()), why));
        } catch (Throwable ignored) {}
    }

    /** 从 namespace 推断 mod 显示名。 */
    private static String modNameOf(String ns) {
        try {
            return net.minecraftforge.fml.ModList.get().getModContainerById(ns)
                    .map(c -> c.getModInfo().getDisplayName())
                    .filter(n -> n != null && !n.isBlank())
                    .orElse(ns);
        } catch (Throwable t) {
            return ns;
        }
    }

    public static boolean isBuilt() { return built; }

    /** 名字是不是原始翻译键（item.xxx.yyy / block.xxx.yyy 这种）。 */
    private static boolean isRawKey(String n) {
        if (n == null || n.indexOf(' ') >= 0) return false;
        String[] parts = n.split("\\.");
        if (parts.length < 3) return false;
        for (String p : parts) if (p.isEmpty() || !p.equals(p.toLowerCase(Locale.ROOT))) return false;
        return parts[0].equals("item") || parts[0].equals("block") || parts[0].equals("tile")
                || parts[0].equals("entity") || parts[0].equals("fluid") || parts[0].equals("gui");
    }

    /** 全部（构建完才有内容）。 */
    public static List<Entry> all() { return ALL; }

    /**
     * 按输入找亚空间物品。
     * 匹配顺序：名字前缀 → id 前缀 → 名字包含 → id 包含。
     */
    public static List<Entry> find(String input, int limit) {
        List<Entry> out = new ArrayList<>();
        if (limit <= 0 || input == null || input.isBlank() || !built) return out;
        String key = input.trim().toLowerCase(Locale.ROOT);
        Set<String> taken = new HashSet<>();
        for (Entry e : ALL) {
            if (out.size() >= limit) break;
            if (e.name.toLowerCase(Locale.ROOT).startsWith(key) || e.id.toLowerCase(Locale.ROOT).startsWith(key)) {
                if (taken.add(e.id)) out.add(e);
            }
        }
        if (out.size() < limit && key.length() >= 2) {
            for (Entry e : ALL) {
                if (out.size() >= limit) break;
                if (e.name.toLowerCase(Locale.ROOT).contains(key) || e.id.toLowerCase(Locale.ROOT).contains(key)) {
                    if (taken.add(e.id)) out.add(e);
                }
            }
        }
        return out;
    }

    /** 按 id 精确查一条（注入 JEI / 命令用）。 */
    public static Entry byId(String id) {
        for (Entry e : ALL) if (e.id.equalsIgnoreCase(id)) return e;
        return null;
    }
}
