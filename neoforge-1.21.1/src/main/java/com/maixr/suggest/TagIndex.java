package com.maixr.suggest;

import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.Registries;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * ★ 真实物品标签注册表（不是内置的那张手写表）。
 *
 * 用途：输入「剑」时，先按物品名找到几把剑，再看它们都在哪些标签里 ——
 * 于是自然得到 #swords / #forge:tools/swords 这类候选。
 * 这样标签推荐同样跟着整合包走，不需要为每个包维护中英对照表。
 */
public final class TagIndex {
    private static List<TagKey<Item>> all = null;
    private static long builtAt = 0L;

    private TagIndex() {}

    /** 全部物品标签（缓存 10 分钟；进世界前拿不到就返回空的）。 */
    public static List<TagKey<Item>> allTags() {
        if (all != null && System.currentTimeMillis() - builtAt < 600_000L) return all;
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc.level == null) return all == null ? List.of() : all;
            var reg = mc.level.registryAccess().registryOrThrow(Registries.ITEM);
            List<TagKey<Item>> list = new ArrayList<>();
            reg.getTagNames().forEach(list::add);
            all = list;
            builtAt = System.currentTimeMillis();
            MaixrSuggestMod.LOGGER.info("[猜你想搜] 标签索引已建立：{} 个物品标签", list.size());
        } catch (Throwable t) {
            if (all == null) all = List.of();
        }
        return all;
    }

    /** 该物品所在的全部标签。 */
    public static List<TagKey<Item>> tagsOf(ItemStack stack) {
        List<TagKey<Item>> out = new ArrayList<>();
        if (stack == null || stack.isEmpty()) return out;
        try {
            stack.getTags().forEach(out::add);
        } catch (Throwable ignored) {}
        return out;
    }

    public static boolean isReady() { return all != null && !all.isEmpty(); }
}
