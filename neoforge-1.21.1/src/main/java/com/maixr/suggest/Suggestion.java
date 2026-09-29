package com.maixr.suggest;

/**
 * 一条候选。
 *
 * ★ 关键设计：显示文本与实际搜索词分离。
 *   例如 mod 中文名「无尽贪婪」显示给人看，但填进搜索框的是「@avaritia」——
 *   因为直接搜中文 mod 名在 JEI 里是搜不到任何东西的。
 */
public final class Suggestion {
    /** 来源（决定颜色与标签）。 */
    public enum Source {
        RECENT("最近", 0xFFC9A9B8),      // 灰粉（用户指定）
        QUEST("任务", 0xFFFFD54F),       // 黄
        FREQUENT("常搜", 0xFF4DD0E1),    // 青
        CHAT("聊天", 0xFFCE93D8),        // 淡紫
        RANDOM("推荐", 0xFF81C784),      // 绿
        MOD("模组", 0xFFFFA726),         // 橙（识别为 mod 名，点击搜索 @modid）
        TAG("标签", 0xFF9ACD32),         // 黄绿（识别为物品标签，点击搜索 #tag）
        FAVORITE("收藏", 0xFFFFD700),    // 金（右键加星，永远排最前）
        PICK("上次", 0xFF80CBC4),        // 青绿（上次搜这个词之后你点开的物品）
        HOT("热词", 0xFFFF8A65),         // 橙红（服务器热词，需服务端也装本 mod）
        PLAIN("", 0xFFE0E0E0);           // 无标签 · 白（输入时匹配到的物品名走这个）

        public final String label;
        public final int color;

        Source(String label, int color) { this.label = label; this.color = color; }
    }

    public final String display;
    public final String query;
    public final Source source;
    /** 右侧小字（例如 "x12" 表示搜过 12 次）。 */
    public final String badge;
    /** ★ true = 这行只有在鼠标悬停时才显示右侧小字（任务名很长，常显会挤） */
    public final boolean badgeOnHover;

    public Suggestion(String display, String query, Source source) { this(display, query, source, ""); }

    public Suggestion(String display, String query, Source source, String badge) {
        this(display, query, source, badge, false);
    }

    public Suggestion(String display, String query, Source source, String badge, boolean badgeOnHover) {
        this.display = display;
        this.query = (query == null || query.isBlank()) ? display : query;
        this.source = source;
        this.badge = badge == null ? "" : badge;
        this.badgeOnHover = badgeOnHover;
    }

    public static Suggestion of(String text, Source source) { return new Suggestion(text, text, source); }
}
