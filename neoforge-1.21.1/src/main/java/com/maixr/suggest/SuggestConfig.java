package com.maixr.suggest;

import net.neoforged.neoforge.common.ModConfigSpec;

import java.util.List;

/** 客户端配置：候选配额、推荐词列表、各来源开关、按键与鼠标行为。 */
public final class SuggestConfig {
    public static final ModConfigSpec SPEC;

    /** ★ 总开关：玩家不喜欢推荐时一键关掉整个候选面板 */
    public static final ModConfigSpec.BooleanValue ENABLED;
    public static final ModConfigSpec.IntValue RECENT_COUNT;
    public static final ModConfigSpec.IntValue QUEST_COUNT;
    public static final ModConfigSpec.IntValue FREQUENT_COUNT;
    public static final ModConfigSpec.IntValue RANDOM_COUNT;
    public static final ModConfigSpec.IntValue CHAT_COUNT;
    public static final ModConfigSpec.IntValue FAVORITE_COUNT;
    public static final ModConfigSpec.IntValue PICK_COUNT;
    public static final ModConfigSpec.IntValue HOT_COUNT;
    public static final ModConfigSpec.IntValue ROWS_PER_PAGE;
    public static final ModConfigSpec.ConfigValue<List<? extends String>> SOURCE_ORDER;
    public static final ModConfigSpec.BooleanValue ENABLE_RECENT;
    public static final ModConfigSpec.BooleanValue ENABLE_QUEST;
    public static final ModConfigSpec.BooleanValue ENABLE_FREQUENT;
    public static final ModConfigSpec.BooleanValue ENABLE_RANDOM;
    public static final ModConfigSpec.BooleanValue ENABLE_CHAT;
    public static final ModConfigSpec.BooleanValue MATCH_PREFIX_ONLY;
    public static final ModConfigSpec.BooleanValue ENABLE_CHAT_LINKS;
    public static final ModConfigSpec.BooleanValue CHAT_LINKS_SYSTEM;
    public static final ModConfigSpec.BooleanValue CHAT_LINKS_KEEP_EXISTING_CLICKS;
    public static final ModConfigSpec.BooleanValue CHAT_LINK_MODIFIER_KEYS;
    public static final ModConfigSpec.BooleanValue GHOST_TEXT;
    public static final ModConfigSpec.BooleanValue CORRECTION;
    public static final ModConfigSpec.BooleanValue SYNTAX_HINTS;
    public static final ModConfigSpec.BooleanValue HOVER_SELECTS;
    public static final ModConfigSpec.BooleanValue PAGING_NEEDS_EMPTY_INPUT;
    public static final ModConfigSpec.ConfigValue<List<? extends String>> PAGING_KEYS;
    public static final ModConfigSpec.BooleanValue SERVER_HOT_WORDS;
    public static final ModConfigSpec.ConfigValue<List<? extends String>> SUGGEST_WORDS;
    public static final ModConfigSpec.ConfigValue<List<? extends String>> MOD_ALIASES;
    public static final ModConfigSpec.IntValue MAX_HISTORY;
    public static final ModConfigSpec.IntValue PANEL_WIDTH;

    /** 总开关（配置还没加载好时按开着处理，避免过早调用导致全关） */
    public static boolean enabled() {
        try { return ENABLED.get(); } catch (Throwable t) { return true; }
    }

    static {
        ModConfigSpec.Builder b = new ModConfigSpec.Builder();
        // 注意：SuggestionEngine 会在运行时通过 enabled() 读它
        b.comment("JEI Guess —— 点击 JEI 搜索框时弹出候选").push("suggest");

        ENABLED = b.comment("★ 总开关：false = 完全关掉「猜你想搜」候选面板（点搜索框不再弹任何候选）",
                  "聊天里的物品名链接不受它影响，那个由 [suggest.behavior] chatItemLinks 控制")
         .define("enabled", true);

        b.comment("各来源在候选列表里的配额（总数为各项之和）").push("quota");
        RECENT_COUNT = b.comment("最近搜索：显示最近用过的搜索词").defineInRange("recent", 3, 0, 30);
        QUEST_COUNT = b.comment("任务需求：最近查看 / 当前可做任务的物品").defineInRange("quest", 3, 0, 30);
        FREQUENT_COUNT = b.comment("高频搜索：历史上搜得最多的词").defineInRange("frequent", 1, 0, 30);
        CHAT_COUNT = b.comment("聊天提及：聊天里出现过的物品名（0 = 只在「聊天」整页里出现）").defineInRange("chat", 0, 0, 30);
        RANDOM_COUNT = b.comment("推荐词：从下方 recommendWords 里随机抽取").defineInRange("random", 1, 0, 30);
        FAVORITE_COUNT = b.comment("收藏：右键加星的词（不计入总数上限，始终排最前）").defineInRange("favorite", 2, 0, 30);
        PICK_COUNT = b.comment("上次：上次搜同一个词之后你点开的物品").defineInRange("pick", 1, 0, 30);
        HOT_COUNT = b.comment("热词：服务器热词（需要服务端也安装本 mod）").defineInRange("hot", 2, 0, 30);
        ROWS_PER_PAGE = b.comment("候选面板每页显示多少行（超过就分页）").defineInRange("rowsPerPage", 8, 3, 20);
        b.pop();

        b.comment("候选来源的排列顺序（从上到下）。可用值：recent / quest / frequent / chat / random / pick / hot",
                  "收藏不参与排序，永远排最前；配额在上面的 [suggest.quota] 里设置").push("order");
        SOURCE_ORDER = b.comment("每行一个来源名").defineList("sequence",
                java.util.List.of("recent", "quest", "random", "frequent", "chat", "hot", "pick"),
                o -> o instanceof String);
        b.pop();

        b.comment("来源开关").push("enable");
        ENABLE_RECENT = b.define("recent", true);
        ENABLE_QUEST = b.define("quest", true);
        ENABLE_FREQUENT = b.define("frequent", true);
        ENABLE_RANDOM = b.define("random", true);
        ENABLE_CHAT = b.comment("从聊天消息里记录被提到的物品（自己与其他玩家的消息）").define("chat", true);
        b.pop();

        b.comment("候选匹配方式：true = 只匹配前缀（更像浏览器），false = 包含匹配").push("behavior");
        MATCH_PREFIX_ONLY = b.define("prefixOnly", false);
        ENABLE_CHAT_LINKS = b.comment("聊天里的物品名/id/标签/模组名加下划线并可点击跳转 JEI")
                .define("chatItemLinks", true);
        CHAT_LINKS_SYSTEM = b.comment("是否也处理系统消息（成就播报、服务器公告）")
                .define("chatLinksOnSystemMessages", true);
        CHAT_LINKS_KEEP_EXISTING_CLICKS = b.comment(
                        "聊天里【已经带点击事件】的文字保持原样，不覆盖成查配方链接",
                        "★ KubeJS 的 /kubejs hand 输出用点击事件做「点击复制物品 ID」，",
                        "  之前被我们的查配方链接覆盖掉了，导致复制失效。默认让别人的点击事件优先。")
                .define("chatLinksKeepExistingClicks", true);
        CHAT_LINK_MODIFIER_KEYS = b.comment(
                        "聊天链接支持修饰键：默认打开合成表，Shift+点击 = 用途表，Ctrl+点击 = 复制物品 ID",
                        "点击时读取当时的键盘状态，不需要额外按键绑定")
                .define("chatLinkModifierKeys", true);
        GHOST_TEXT = b.comment("在 JEI 搜索框里显示灰色补全提示，按 Tab 接受").define("ghostText", true);
        CORRECTION = b.comment("搜不到东西时提示「你是不是想搜…」（基于编辑距离的纠错）").define("correction", true);
        SYNTAX_HINTS = b.comment("输入 @ 或 # 时列出 mod / 标签，教玩家用 JEI 的前缀语法").define("syntaxHints", true);
        HOVER_SELECTS = b.comment("鼠标悬停即选中该条（Enter 确认）").define("hoverSelects", true);
        PAGING_NEEDS_EMPTY_INPUT = b.comment(
                        "★ 按键防冲突：搜索框里有文字时，←/→ 交还给 JEI 移动光标；",
                        "  只有搜索框为空时才用 ←/→ 翻页。PageUp/PageDown 任何时候都能翻页。")
                .define("pagingNeedsEmptyInput", true);
        PAGING_KEYS = b.comment("翻页键（GLFW 键名，可自行改成不与别的 mod 冲突的组合）")
                .defineList("pagingKeys",
                        java.util.List.of("LEFT", "RIGHT", "PAGE_UP", "PAGE_DOWN"),
                        o -> o instanceof String);
        MAX_HISTORY = b.comment("本地保存的历史条数上限").defineInRange("maxHistory", 200, 20, 2000);
        PANEL_WIDTH = b.comment("候选面板宽度（像素，实际会按内容自动加宽）").defineInRange("panelWidth", 160, 80, 400);
        b.pop();

        b.comment("服务端热词：客户端是否显示/上报全服热词（显示为【热词】）",
                  "★ 服务端也需要安装本 mod，开关在服务端的 serverconfig/jeiguess-server.toml；",
                  "  服务端没装时客户端全程保持沉默，不会有任何报错").push("server");
        SERVER_HOT_WORDS = b.define("hotWords", true);
        b.pop();

        b.comment("推荐词池：上面的 random 配额会从这里随机抽取",
                  "可以填物品名（如 '钻石'）、搜索词（如 '机械动力'）或 JEI 前缀（如 '@mekanism'）").push("words");
        SUGGEST_WORDS = b.comment("每行一个", "★ 强烈建议用 @modid 形式（如 @create）—— 直接写 mod 的中文名 JEI 是搜不到东西的").defineList("recommendWords",
                java.util.List.of(
                        "@create", "@mekanism", "@botania", "@ae2", "@avaritia",
                        "@occultism", "@bloodmagic", "@ars_nouveau"),
                o -> o instanceof String);
        MOD_ALIASES = b.comment("手动补充「中文名=modid」映射（正常情况下会自动从创造模式标签页收集）",
                        "格式：机械动力=create   或   无尽贪婪=@avaritia")
                .defineList("modAliases", java.util.List.of(), o -> o instanceof String);
        b.pop();

        b.pop();
        SPEC = b.build();
    }

    private SuggestConfig() {}
}
