package com.maixr.suggest;

import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforgespi.language.IModInfo;
import net.neoforged.fml.ModList;
import net.minecraft.core.registries.BuiltInRegistries;

import java.util.*;

/**
 * 聊天里的「可点击物品词」索引。
 *
 * 支持四类关键词（按优先级）：
 *   1. 物品中文名（"钻石"）
 *   2. 物品 id（"minecraft:diamond"）
 *   3. 标签（"#forge:ingots" / "forge:ingots"）
 *   4. 模组名（"无尽贪婪" / "@avaritia" / "avaritia"）
 *
 * 每类都映射到一个代表物品，点击即打开它的 JEI 合成表。
 */
public final class ItemLinkIndex {
    /** 关键词 -> 代表物品 */
    private static final Map<String, ItemStack> INDEX = new Object2ObjectOpenHashMap<>();
    private static final Map<Character, List<String>> BY_FIRST = new HashMap<>();
    private static volatile boolean built = false;

    private ItemLinkIndex() {}

    /** 分帧构建（客户端 tick 调用）。 */
    public static void buildIncrementally() {
        if (built) return;
        // ★ 必须等玩家进入世界：标签需要 registryAccess，主菜单时 level 为 null，
        //   若此时构建会导致标签索引永久缺失（索引只建一次的）。
        if (net.minecraft.client.Minecraft.getInstance().level == null) return;
        try {
            // ① 物品名 + 物品 id
            for (Item item : BuiltInRegistries.ITEM) {
                try {
                    ResourceLocation rl = BuiltInRegistries.ITEM.getKey(item);
                    if (rl == null) continue;
                    ItemStack stack = new ItemStack(item);
                    String name = stack.getHoverName().getString();
                    if (name != null && name.length() >= 2 && name.length() <= 24 && name.indexOf('\u00a7') < 0
                            && name.indexOf('.') < 0) {          // 带点的多半是原始翻译键，别当物品名索引
                        INDEX.putIfAbsent(name, stack);
                    }
                    INDEX.putIfAbsent(rl.toString(), stack);           // minecraft:diamond
                } catch (Throwable ignored) {}
            }
            // ② 标签（挑每个标签的第一个物品作代表）
            try {
                var lookup = net.minecraft.client.Minecraft.getInstance().level == null
                        ? null : net.minecraft.client.Minecraft.getInstance().level.registryAccess().registryOrThrow(Registries.ITEM);
                if (lookup != null) {
                    lookup.getTagNames().forEach(tagKey -> {
                        try {
                            TagKey<Item> tk = tagKey;
                            var holders = lookup.getTag(tk);
                            if (holders.isEmpty() || holders.get().size() == 0) return;
                            ItemStack rep = new ItemStack(holders.get().get(0).value());
                            if (rep.isEmpty()) return;
                            INDEX.putIfAbsent("#" + tk.location(), rep);
                        } catch (Throwable ignored) {}
                    });
                }
            } catch (Throwable ignored) {}
            // ③ 模组名（英文显示名 / modid / @modid）
            // ★ 先一次遍历建立 namespace → 首个物品 的映射（避免对每个 mod 都遍历一遍全物品表）
            try {
                Map<String, ItemStack> firstOfNs = new HashMap<>();
                for (Item item : BuiltInRegistries.ITEM) {
                    ResourceLocation rl = BuiltInRegistries.ITEM.getKey(item);
                    if (rl == null) continue;
                    firstOfNs.putIfAbsent(rl.getNamespace(), new ItemStack(item));
                }
                for (IModInfo info : ModList.get().getMods()) {
                    String id = info.getModId();
                    ItemStack rep = firstOfNs.get(id);
                    if (rep == null || rep.isEmpty()) continue;
                    INDEX.putIfAbsent(id, rep);
                    INDEX.putIfAbsent("@" + id, rep);
                    String dn = info.getDisplayName();
                    if (dn != null && dn.length() >= 2) INDEX.putIfAbsent(dn, rep);
                }
            } catch (Throwable ignored) {}
            // 建首字索引（长的优先，避免"铁"抢了"铁锭"）
            List<String> keys = new ArrayList<>(INDEX.keySet());
            keys.sort(Comparator.comparingInt(String::length).reversed());
            for (String k : keys) BY_FIRST.computeIfAbsent(k.charAt(0), x -> new ArrayList<>()).add(k);
            built = true;
            MaixrSuggestMod.LOGGER.info("[猜你想搜] 聊天链接索引构建完成：{} 个关键词", INDEX.size());
        } catch (Throwable t) {
            MaixrSuggestMod.LOGGER.warn("[猜你想搜] 构建聊天链接索引失败: {}", t.toString());
        }
    }

    public static boolean isBuilt() { return built; }

    /** 一段文本里命中的关键词（按出现位置，长词优先，最多 8 个）。 */
    public static List<Hit> find(String text) {
        List<Hit> hits = new ArrayList<>();
        if (!built || text == null || text.length() < 2 || text.length() > 512) return hits;
        // ★ 先收集所有命中（不去重），再统一按"长的优先 → 靠前的优先"排序，
        //   否则会先命中「猪咪」而把更该匹配的「创造猪咪」挤掉。
        Set<Character> chars = new HashSet<>();
        for (int i = 0; i < text.length(); i++) chars.add(text.charAt(i));
        List<Hit> all = new ArrayList<>();
        for (char c : chars) {
            List<String> bucket = BY_FIRST.get(c);
            if (bucket == null) continue;
            for (String k : bucket) {
                int at = text.indexOf(k);
                if (at >= 0) all.add(new Hit(at, at + k.length(), k, INDEX.get(k)));
            }
        }
        all.sort((a, b) -> {
            int la = a.end - a.start, lb = b.end - b.start;
            if (la != lb) return Integer.compare(lb, la);          // 长词优先
            return Integer.compare(a.start, b.start);              // 同长取靠前
        });
        for (Hit h : all) {
            if (hits.size() >= 8) break;
            boolean overlap = false;
            for (Hit x : hits) if (h.start < x.end && h.end > x.start) { overlap = true; break; }
            if (!overlap) hits.add(h);
        }
        hits.sort(Comparator.comparingInt(h -> h.start));
        return hits;
    }

    public static final class Hit {
        public final int start, end;
        public final String key;
        public final ItemStack stack;
        Hit(int start, int end, String key, ItemStack stack) {
            this.start = start; this.end = end; this.key = key; this.stack = stack;
        }
    }
}
