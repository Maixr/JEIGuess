package com.maixr.suggest;

import java.util.Locale;

/**
 * ★ 候选打分（像现代浏览器地址栏那样排序，而不是"匹配到就按时间排"）。
 *
 * score = 频次权重 × 时间衰减 × 匹配质量 − 空结果惩罚
 *
 * 为什么需要它：输入 "256" 可能命中好几个搜过的词，只按 lastUsed 排是拍脑袋。
 * 数据全都已经在 SearchHistoryStore.Entry 里（count / lastUsed / emptyHits），零额外成本。
 */
public final class SuggestionScore {
    /** 时间衰减半衰期：14 天。搜得越久远，权重越低。 */
    private static final double HALF_LIFE_MS = 14.0 * 24 * 3600 * 1000;

    private SuggestionScore() {}

    /**
     * 打分。分数越高越靠前。
     *
     * @param count      搜索次数
     * @param lastUsed   最后使用时间（epoch ms）
     * @param emptyHits  搜出空结果的次数
     * @param word       候选词
     * @param input      玩家当前输入
     */
    public static double score(int count, long lastUsed, int emptyHits, String word, String input) {
        if (word == null) return 0;
        // ① 频次：log 压制，避免搜过 100 次的词永久霸榜
        double freq = Math.log1p(Math.max(0, count));
        // ② 时间衰减：指数半衰期
        double age = Math.max(0, System.currentTimeMillis() - lastUsed);
        double recency = Math.pow(0.5, age / HALF_LIFE_MS);
        // ③ 匹配质量：前缀 1.0 > 词首 0.7 > 包含 0.4
        double match = matchQuality(word, input);
        // ④ 空结果惩罚：搜出 0 条的经历越多越往后
        double penalty = 1.0 / (1.0 + emptyHits * 0.8);
        return (0.35 + freq) * (0.25 + recency) * match * penalty;
    }

    /** 匹配质量：前缀 > 词首 > 包含。 */
    public static double matchQuality(String word, String input) {
        if (word == null || input == null || input.isBlank()) return 0.5;
        String w = word.toLowerCase(Locale.ROOT);
        String in = input.trim().toLowerCase(Locale.ROOT);
        if (w.startsWith(in)) return 1.0;                       // 直接前缀
        int idx = w.indexOf(in);
        if (idx > 0) {
            // 命中在"词首"（空格/标点后）给较高分：搜 "元件" 命中 "256k 化学品 元件"
            char before = w.charAt(idx - 1);
            return (before == ' ' || before == '_' || before == '-' || before == '/') ? 0.7 : 0.4;
        }
        return 0.35;                                            // 拼音等其它匹配
    }
}
