package com.maixr.suggest;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * ★ 任务书浏览跟踪：记录玩家在 FTB 任务书里**正在查看**的任务及其所需物品。
 *
 * 为什么要这个：原来的任务候选只能用「任务是否已开始」来猜玩家想做什么，
 * 猜得不准。现在直接读任务书当前打开的那个任务 —— 「我刚看的那个任务要什么来着」，
 * 打开 JEI 搜索框就能看到。
 *
 * 全部反射调用，未装 FTB Quests 时静默跳过。
 */
public final class QuestWatch {
    private static boolean resolved = false;
    private static boolean available = false;
    private static Class<?> questScreenCls;    // 任务书界面类（用 isInstance 判定，兼容子类）
    private static Method isViewingQuest;      // QuestScreen.isViewingQuest()
    private static Method getViewedQuest;      // QuestScreen.getViewedQuest()
    private static Method getTasks;            // Quest.getTasks()
    private static Method getItemStack;        // ItemTask.getItemStack()
    private static Method getId;               // QuestObjectBase.getId()
    private static Method getTitle;            // QuestObjectBase.getTitle()
    private static long lastQuestId = -1L;

    private QuestWatch() {}

    private static void resolve() {
        if (resolved) return;
        resolved = true;
        try {
            Class<?> screen = Class.forName("dev.ftb.mods.ftbquests.client.gui.quests.QuestScreen");
            questScreenCls = screen;
            isViewingQuest = screen.getMethod("isViewingQuest");
            getViewedQuest = screen.getMethod("getViewedQuest");
            Class<?> qo = Class.forName("dev.ftb.mods.ftbquests.quest.QuestObjectBase");
            getId = qo.getMethod("getId");
            try { getTitle = qo.getMethod("getTitle"); } catch (Throwable ignored) {}
            getTasks = Class.forName("dev.ftb.mods.ftbquests.quest.Quest").getMethod("getTasks");
            getItemStack = Class.forName("dev.ftb.mods.ftbquests.quest.task.ItemTask").getMethod("getItemStack");
            available = true;
        } catch (Throwable t) {
            available = false;
            MaixrSuggestMod.LOGGER.info("[猜你想搜] 任务书浏览跟踪不可用: {}", t.toString());
        }
    }

    /** 每个客户端 tick 调一次（很便宜：不是任务书界面就直接返回）。 */
    public static void tick() {
        resolve();
        if (!available) return;
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc.screen == null) return;
            // ★ 用反射拿到的 Class.isInstance 判定，兼容 FTB 的子类/包装类
            //   （FTB Quests 是可选依赖，编译期不能直接引用它的类）
            if (questScreenCls == null || !questScreenCls.isInstance(mc.screen)) return;
            Object screen = mc.screen;
            Object viewing = isViewingQuest.invoke(screen);
            if (!(viewing instanceof Boolean b) || !b) { lastQuestId = -1L; return; }
            Object quest = getViewedQuest.invoke(screen);
            if (quest == null) { lastQuestId = -1L; return; }
            long id = ((Number) getId.invoke(quest)).longValue();
            if (id == lastQuestId) return;                       // 同一个任务，不重复记录
            lastQuestId = id;
            String title = "";
            if (getTitle != null) {
                try {
                    Object t = getTitle.invoke(quest);
                    if (t instanceof Component c) title = c.getString();
                    else if (t != null) title = String.valueOf(t);
                } catch (Throwable ignored) {}
            }
            List<String> items = new ArrayList<>();
            Object tasks = getTasks.invoke(quest);
            if (tasks instanceof java.util.Collection<?> col) {
                for (Object task : col) {
                    if (items.size() >= 8) break;
                    try {
                        Object st = getItemStack.invoke(task);
                        if (st instanceof net.minecraft.world.item.ItemStack stack && !stack.isEmpty()) {
                            String n = stack.getHoverName().getString();
                            if (!n.isBlank() && n.length() <= 24 && !items.contains(n)) items.add(n);
                        }
                    } catch (Throwable ignored) {}
                }
            }
            if (items.isEmpty()) return;
            SearchHistoryStore.get().recordQuestView(id, title, items);
            // ★ 打一行日志：确认"最近查看"这条链路真的在工作（没有它=任务书界面没被认出来）
            MaixrSuggestMod.LOGGER.info("[猜你想搜][任务] 正在查看任务「{}」（{} 个目标物品）",
                    title.isBlank() ? ("#" + id) : title, items.size());
        } catch (Throwable t) {
            // 任务书界面结构变化时不要刷屏
        }
    }
}
