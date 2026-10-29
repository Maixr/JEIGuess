package com.maixr.suggest;

import java.lang.reflect.Method;

/**
 * 拼音匹配桥接：复用「通用拼音搜索(JEC)」自带的拼音库 me.towdium.pinin.PinIn，
 * 这样候选列表也能支持拼音输入（输入 "stjsq" 匹配「实体加速器」）。
 *
 * 全部反射 —— JEC 没装就静默退化为纯文本匹配。
 */
public final class PinyinBridge {
    private static boolean resolved = false;
    private static Object pinIn;
    private static Method begins, contains, getChar, pinyins, phonemesM;

    private PinyinBridge() {}

    private static void resolve() {
        if (resolved) return;
        resolved = true;
        try {
            Class<?> pinInCls = Class.forName("me.towdium.pinin.PinIn");
            pinIn = pinInCls.getConstructor().newInstance();
            begins = pinInCls.getMethod("begins", String.class, String.class);
            contains = pinInCls.getMethod("contains", String.class, String.class);
            getChar = pinInCls.getMethod("getChar", char.class);
            pinyins = Class.forName("me.towdium.pinin.elements.Char").getMethod("pinyins");
            phonemesM = Class.forName("me.towdium.pinin.elements.Pinyin").getMethod("phonemes");
            boolean ok = (Boolean) begins.invoke(pinIn, "钻石", "zs");
            MaixrSuggestMod.LOGGER.info("[猜你想搜] 拼音库已接入（JEC PinIn），自检={}", ok);
        } catch (Throwable t) {
            pinIn = null;
            MaixrSuggestMod.LOGGER.info("[猜你想搜] 未接入拼音库（未装 JEC 或结构不同）: {}", t.toString());
        }
    }

    public static boolean isReady() { resolve(); return pinIn != null; }

    /** 前缀匹配：text 是否以 query 开头（支持拼音 / 首字母 / 混合）。 */
    public static boolean beginsWith(String text, String query) {
        resolve();
        if (pinIn == null || text == null || query == null || query.isBlank()) return false;
        try { return (Boolean) begins.invoke(pinIn, text, query); } catch (Throwable t) { return false; }
    }

    /** 包含匹配（拼音）。 */
    public static boolean containsPinyin(String text, String query) {
        resolve();
        if (pinIn == null || text == null || query == null || query.isBlank()) return false;
        try { return (Boolean) contains.invoke(pinIn, text, query); } catch (Throwable t) { return false; }
    }

    /** 综合匹配：中文包含 / 拼音前缀 / 拼音包含。 */
    public static boolean matches(String text, String query, boolean prefixOnly) {
        if (text == null || query == null || query.isBlank()) return false;
        String t = text.toLowerCase(), q = query.toLowerCase();
        if (t.startsWith(q)) return true;
        if (!prefixOnly && t.contains(q)) return true;
        if (beginsWith(text, query)) return true;
        return !prefixOnly && containsPinyin(text, query);
    }

    private static final java.util.Map<Character, String[]> READINGS_CACHE = new java.util.HashMap<>();
    private static final java.util.Map<Character, String[]> SOUNDS_CACHE = new java.util.HashMap<>();

    /**
     * ★ 单字的全部**读音**（声母+韵母，忽略声调）。
     *
     * 用来判断「打错字」：砖(zhuan) 与 钻(zuan) 声母韵母相同；
     * 石(shi) 与 色(se) 韵母不同 —— 后者不该被当成同一个词。
     * 只看首字母会把 石/色 也判成同音，误报太多。
     * 拿不到返回 null。
     */
    public static String[] soundsOf(char c) {
        if (c < 128) return new String[]{ "ascii|" + Character.toLowerCase(c) };
        resolve();
        if (pinIn == null || phonemesM == null) return null;
        String[] hit = SOUNDS_CACHE.get(c);
        if (hit != null) return hit;
        try {
            Object ch = getChar.invoke(pinIn, c);
            Object[] pys = (Object[]) pinyins.invoke(ch);
            if (pys == null || pys.length == 0) return null;
            String[] out = new String[pys.length];
            for (int i = 0; i < pys.length; i++) {
                Object[] ph = (Object[]) phonemesM.invoke(pys[i]);
                if (ph == null || ph.length < 2) { out[i] = String.valueOf(pys[i]).toLowerCase(); continue; }
                out[i] = (String.valueOf(ph[0]) + "|" + String.valueOf(ph[1])).toLowerCase();
            }
            SOUNDS_CACHE.put(c, out);
            return out;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * ★ 单字的**全部读音**（带声调）。多音字必须看全部：
     *   镐 → [hao4, gao3]（镐京 / 镐子），只取第一个会漏掉真正想要的读音。
     * 拿不到返回 null。
     */
    public static String[] readingsOf(char c) {
        if (c < 128) return new String[]{ String.valueOf(Character.toLowerCase(c)) };
        resolve();
        if (pinIn == null) return null;
        String[] hit = READINGS_CACHE.get(c);
        if (hit != null) return hit;
        try {
            Object ch = getChar.invoke(pinIn, c);
            Object[] pys = (Object[]) pinyins.invoke(ch);
            if (pys == null || pys.length == 0) return null;
            String[] out = new String[pys.length];
            for (int i = 0; i < pys.length; i++) out[i] = String.valueOf(pys[i]).toLowerCase();
            READINGS_CACHE.put(c, out);
            return out;
        } catch (Throwable t) {
            return null;
        }
    }

    private static final java.util.Map<Character, Character> INITIAL_CACHE = new java.util.HashMap<>();

    /**
     * ★ 单个字的拼音首字母（砖 → z，钻 → z；石 → s，色 → s）。
     *
     * 用来判断"错别字是否同音"：只差一个字且同音，才算拼写错误；
     * 否则 2 字词几乎能配上任何物品（红石→红色、石英→石磨 这类误报）。
     * 拿不到时返回 0。
     */
    public static char initialOf(char c) {
        if (c < 128) return Character.toLowerCase(c);
        resolve();
        if (pinIn == null) return 0;
        Character cached = INITIAL_CACHE.get(c);
        if (cached != null) return cached;
        try {
            Object ch = getChar.invoke(pinIn, c);
            Object[] pys = (Object[]) pinyins.invoke(ch);
            if (pys == null || pys.length == 0) return 0;
            String py = String.valueOf(pys[0]);
            if (py.isEmpty()) return 0;
            char r = Character.toLowerCase(py.charAt(0));
            INITIAL_CACHE.put(c, r);
            return r;
        } catch (Throwable t) {
            return 0;
        }
    }

    /**
     * ★ 取一串文本的拼音首字母（黑曜石 → hys，会议室 → hys）。
     *
     * 谐音纠错要靠它：玩家把「黑曜石」打成「会议室」，字形/编辑距离都对不上，
     * 但拼音首字母完全相同 —— 这是唯一能识别这种错的信号。
     *
     * 拿不到拼音（没装 JEC、或含无法识别的字符）时返回 null，调用方据此跳过。
     */
    public static String initials(String text) {
        resolve();
        if (pinIn == null || text == null || text.isEmpty()) return null;
        try {
            StringBuilder sb = new StringBuilder(text.length());
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                if (c < 128) { sb.append(Character.toLowerCase(c)); continue; }
                Object ch = getChar.invoke(pinIn, c);
                Object[] pys = (Object[]) pinyins.invoke(ch);
                if (pys == null || pys.length == 0) return null;
                String py = String.valueOf(pys[0]);
                if (py.isEmpty()) return null;
                sb.append(Character.toLowerCase(py.charAt(0)));
            }
            return sb.toString();
        } catch (Throwable t) {
            return null;
        }
    }
}
