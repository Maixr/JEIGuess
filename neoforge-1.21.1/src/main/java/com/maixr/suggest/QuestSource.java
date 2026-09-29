package com.maixr.suggest;

import net.minecraft.world.item.ItemStack;
import net.minecraft.core.registries.BuiltInRegistries;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;

/**
 * 任务来源（FTB Quests 感知）。
 *
 * 排序（★ 玩家要的顺序）：
 *   ① 最近在任务书里**查看过**的未完成任务
 *   ② 查看次数最多的未完成任务
 *   ③ 已经开工（有进度）的任务
 *   ④ 其他"前置已完成、当前可做"的任务
 *
 * 全部走反射调用 FTB Quests，未装该模组时静默禁用。
 */
public final class QuestSource {
    private static final Random RANDOM = new Random();
    private static boolean resolved = false;
    private static boolean available = false;

    // 反射句柄
    private static Object questFile;                 // ClientQuestFile.INSTANCE
    private static Object teamData;                  // ClientQuestFile.selfTeamData
    private static Method forAllQuests;              // QuestFile.forAllQuests(Consumer)
    private static Method isCompletedRaw;            // QuestObject.isCompletedRaw(TeamData)
    private static Method getTasks;                  // Quest.getTasks()
    private static Method getItemStack;              // ItemTask.getItemStack()
    private static Method getProgress;               // TeamData.getProgress(Task)
    private static Method getStartedTime;            // TeamData.getStartedTime(long)
    private static Method getId;                     // QuestObjectBase.getId()
    private static Method getTitle;                  // QuestObjectBase.getTitle()
    private static Method areDepsComplete;           // Quest.areDependenciesComplete(TeamData) ★ 官方判定
    private static Method hasDependencies;           // Quest.hasDependencies()

    private static List<Suggestion> pool = List.of();
    private static long loadedAt = 0L;

    private QuestSource() {}

    private static void resolve() {
        if (resolved) return;
        resolved = true;
        try {
            Class<?> cqf = Class.forName("dev.ftb.mods.ftbquests.client.ClientQuestFile");
            Method exists = cqf.getMethod("exists");
            if (!(Boolean) exists.invoke(null)) {
                MaixrSuggestMod.LOGGER.info("[猜你想搜] FTB Quests 客户端任务文件不可用，任务来源已跳过");
                return;
            }
            Field inst = cqf.getField("INSTANCE");
            questFile = inst.get(null);
            Field self = cqf.getField("selfTeamData");
            teamData = self.get(questFile);
            if (teamData == null) {
                MaixrSuggestMod.LOGGER.info("[猜你想搜] FTB 团队数据尚未同步，任务来源暂不可用");
                return;
            }
            Class<?> qfCls = Class.forName("dev.ftb.mods.ftbquests.api.QuestFile");
            forAllQuests = qfCls.getMethod("forAllQuests", java.util.function.Consumer.class);
            Class<?> qo = Class.forName("dev.ftb.mods.ftbquests.quest.QuestObject");
            Class<?> td = Class.forName("dev.ftb.mods.ftbquests.quest.TeamData");
            isCompletedRaw = qo.getMethod("isCompletedRaw", td);
            try { getProgress = td.getMethod("getProgress", Class.forName("dev.ftb.mods.ftbquests.quest.task.Task")); } catch (Throwable ignored) {}
            try { getStartedTime = td.getMethod("getStartedTime", long.class); } catch (Throwable ignored) {}
            Class<?> q = Class.forName("dev.ftb.mods.ftbquests.quest.Quest");
            getTasks = q.getMethod("getTasks");
            Class<?> qob = Class.forName("dev.ftb.mods.ftbquests.quest.QuestObjectBase");
            getId = qob.getMethod("getId");
            getTitle = qob.getMethod("getTitle");
            // ★ 用 FTB 自己的公开判定，别自己拼依赖逻辑（它还要处理"只需满足 N 个前置"）
            areDepsComplete = q.getMethod("areDependenciesComplete", td);
            hasDependencies = q.getMethod("hasDependencies");
            getItemStack = Class.forName("dev.ftb.mods.ftbquests.quest.task.ItemTask").getMethod("getItemStack");
            available = true;
            MaixrSuggestMod.LOGGER.info("[猜你想搜] FTB Quests 已接入（任务来源可用）");
        } catch (Throwable t) {
            available = false;
            MaixrSuggestMod.LOGGER.info("[猜你想搜] 接入 FTB Quests 失败: {}", t.toString());
        }
    }

    /** 抽取 count 条任务所需物品（按上面的四级排序）。 */
    @SuppressWarnings("unchecked")
    public static List<Suggestion> pick(int count) {
        if (count <= 0) return List.of();
        resolve();
        if (!available) return List.of();
        long now = System.currentTimeMillis();
        if (now - loadedAt < 15_000L && !pool.isEmpty()) return sample(count);   // 15 秒缓存
        loadedAt = now;

        Map<Long, List<String>> itemsByQuest = new LinkedHashMap<>();
        Map<Long, String> titles = new LinkedHashMap<>();
        Set<Long> doable = new HashSet<>();           // 未完成 + 前置全通
        Set<Long> started = new HashSet<>();          // 玩家已经开工
        int[] stat = new int[4];                      // 0=已完成 1=无前置 2=前置没做完 3=入选
        try {
            Field self = questFile.getClass().getField("selfTeamData");
            teamData = self.get(questFile);
            if (teamData == null) return List.of();

            forAllQuests.invoke(questFile, (java.util.function.Consumer<Object>) quest -> {
                if (itemsByQuest.size() >= 200) return;
                try {
                    long qid = ((Number) getId.invoke(quest)).longValue();
                    if ((Boolean) isCompletedRaw.invoke(quest, teamData)) { stat[0]++; return; }   // 任务本体已完成
                    // ★ 没有前置的任务一律跳过：那些通常是教程/剧情章节的入口
                    if (hasDependencies != null) {
                        Object hd = hasDependencies.invoke(quest);
                        if (!(hd instanceof Boolean hb) || !hb) { stat[1]++; return; }
                    }
                    // ★ 依赖判定交给 FTB 自己（会正确处理"只需满足 N 个前置"这类情况）
                    if (areDepsComplete == null) return;
                    Object okDeps = areDepsComplete.invoke(quest, teamData);
                    if (!(okDeps instanceof Boolean b) || !b) { stat[2]++; return; }
                    String qTitle = "";
                    if (getTitle != null) {
                        try {
                            Object tt = getTitle.invoke(quest);
                            if (tt instanceof net.minecraft.network.chat.Component c) qTitle = c.getString();
                        } catch (Throwable ignored) {}
                    }
                    List<String> names = new ArrayList<>();
                    java.util.Collection<Object> tasks = (java.util.Collection<Object>) getTasks.invoke(quest);
                    if (tasks != null) {
                        for (Object t : tasks) {
                            try {
                                Object st = getItemStack.invoke(t);
                                if (!(st instanceof ItemStack stack) || stack.isEmpty()) continue;
                                String name = stack.getHoverName().getString();
                                if (name.isBlank() || name.length() > 24 || names.contains(name)) continue;
                                names.add(name);
                            } catch (Throwable ignored) {}
                        }
                    }
                    if (names.isEmpty()) return;
                    itemsByQuest.put(qid, names);
                    titles.put(qid, qTitle);
                    doable.add(qid);
                    stat[3]++;
                    // 是否已开工
                    boolean st = false;
                    if (getProgress != null && tasks != null) {
                        for (Object tk : tasks) {
                            try {
                                Object p = getProgress.invoke(teamData, tk);
                                if (p instanceof Number num && num.longValue() > 0) { st = true; break; }
                            } catch (Throwable ignored) {}
                        }
                    }
                    if (!st && getStartedTime != null) {
                        try {
                            Object o = getStartedTime.invoke(teamData, qid);
                            if (o instanceof Optional<?> opt && opt.isPresent()) st = true;
                        } catch (Throwable ignored) {}
                    }
                    if (st) started.add(qid);
                } catch (Throwable ignored) {}
            });
        } catch (Throwable t) {
            MaixrSuggestMod.LOGGER.info("[猜你想搜] 读取任务进度失败: {}", t.toString());
        }

        // ★ 排序：最近查看 > 查看最多 > 已开工 > 其他
        List<Suggestion> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        List<SearchHistoryStore.QuestView> views = SearchHistoryStore.get().questViews();
        for (SearchHistoryStore.QuestView v : views) {                       // ① 最近查看（新的在前）
            addQuest(out, seen, itemsByQuest, doable, v.id, v.title, true);
        }
        List<SearchHistoryStore.QuestView> byCount = new ArrayList<>(views);
        byCount.sort(Comparator.comparingInt((SearchHistoryStore.QuestView v) -> v.views).reversed());
        for (SearchHistoryStore.QuestView v : byCount) {                     // ② 查看次数最多
            addQuest(out, seen, itemsByQuest, doable, v.id, v.title, true);
        }
        for (Long id : started) {                                            // ③ 已开工
            addQuest(out, seen, itemsByQuest, doable, id, titles.get(id), true);
        }
        for (Long id : doable) {                                             // ④ 其余可做任务
            addQuest(out, seen, itemsByQuest, doable, id, titles.get(id), true);
        }
        pool = out;
        // ★ 诊断：为什么推荐/不推荐某个任务，一眼可见（每条物品的徽标也会写上是哪个任务）
        MaixrSuggestMod.LOGGER.info("[猜你想搜][任务] 池 {} 条 · 入选任务 {} 个（已完成 {} / 无前置 {} / 前置未完成 {}）",
                out.size(), stat[3], stat[0], stat[1], stat[2]);
        int shown = 0;
        for (Suggestion s : out) {
            if (shown++ >= 8) break;
            MaixrSuggestMod.LOGGER.info("[猜你想搜][任务] 候选：{} ← 任务「{}」", s.display, s.badge);
        }
        return sample(count);
    }

    /** ★ 全部可做任务的物品（浏览模式里"任务"整页用，不受配额限制）。 */
    public static List<Suggestion> pickAll(int limit) {
        return pick(limit);
    }

    /** 把某个任务的物品加进池子（badge 显示任务名，方便玩家知道是哪来的）。 */
    private static void addQuest(List<Suggestion> out, Set<String> seen, Map<Long, List<String>> items,
                                 Set<Long> doable, long questId, String title, boolean withTitle) {
        if (!doable.contains(questId)) return;
        List<String> names = items.get(questId);
        if (names == null) return;
        String badge = withTitle && title != null && !title.isBlank()
                ? (title.length() > 16 ? title.substring(0, 16) : title) : "";
        for (String n : names) {
            if (!seen.add(n)) continue;
            // ★ 任务名只在鼠标悬停那一行显示（常显太挤）
            out.add(new Suggestion(n, n, Suggestion.Source.QUEST, badge, true));
        }
    }

    private static List<Suggestion> sample(int count) {
        List<Suggestion> copy = new ArrayList<>(pool);
        // 前几条保持顺序（最近查看的任务优先），后面的随机
        List<Suggestion> head = copy.subList(0, Math.min(count, copy.size()));
        return new ArrayList<>(head);
    }
}
