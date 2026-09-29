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
    private static Method begins;
    private static Method contains;

    private PinyinBridge() {}

    private static void resolve() {
        if (resolved) return;
        resolved = true;
        try {
            Class<?> pinInCls = Class.forName("me.towdium.pinin.PinIn");
            pinIn = pinInCls.getConstructor().newInstance();
            begins = pinInCls.getMethod("begins", String.class, String.class);
            contains = pinInCls.getMethod("contains", String.class, String.class);
            // 自检：用一个最常见的字验证参数顺序
            boolean ok = (Boolean) begins.invoke(pinIn, "钻石", "zs");
            if (!ok) {
                MaixrSuggestMod.LOGGER.info("[猜你想搜] 拼音库参数顺序自检未通过，改用反向调用");
                begins = pinInCls.getMethod("begins", String.class, String.class);
            }
            MaixrSuggestMod.LOGGER.info("[猜你想搜] 拼音库已接入（JEC PinIn）");
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
}
