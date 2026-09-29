package com.maixr.suggest;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 与 JEI 的桥接层（全部走反射，避免被 JEI 版本差异绑死）。
 *
 * 用到的 JEI 内部结构：
 *   mezz.jei.common.Internal.getRuntime()               → IJeiRuntime
 *   IJeiRuntime.getIngredientFilter()                   → IIngredientFilter
 *   IIngredientFilter.getFilterText() / setFilterText() → 读写搜索词
 *   IJeiRuntime.getIngredientListOverlay()              → IIngredientListOverlay
 *   IIngredientListOverlay.hasKeyboardFocus()           → 搜索框是否聚焦
 *   mezz.jei.gui.input.GuiTextFieldFilter.history       → 静态 TextHistory
 *   mezz.jei.common.util.TextHistory.history            → List<String>
 */
public final class JeiBridge {
    private static boolean resolved = false;
    private static boolean available = false;

    private static Method internalGetRuntime;
    private static boolean runtimeReturnsOptional = true;
    private static java.lang.reflect.Field overlaySearchField;   // IngredientListOverlay.searchField
    private static Method rtGetIngredientFilter;
    private static Method rtGetOverlay;
    private static Method filterGetText;
    private static Method filterSetText;
    private static Method overlayHasFocus;
    private static Field tfHistoryField;      // GuiTextFieldFilter.history (static TextHistory)
    private static Field thListField;         // TextHistory.history (List<String>)
    private static Method overlayGetUnderMouse;  // IIngredientListOverlay.getIngredientUnderMouse()
    private static Method overlayIsDisplayed;    // IIngredientListOverlay.isListDisplayed()
    private static Method typedGetItemStack;     // ITypedIngredient.getItemStack()

    private JeiBridge() {}

    /** 安全取方法：找不到返回 null，不抛异常。 */
    private static Method method(Class<?> c, String name, Class<?>... params) {
        try { return c.getMethod(name, params); } catch (Throwable t) {
            MaixrSuggestMod.LOGGER.info("[猜你想搜] JEI 缺少方法 {}.{}()（该子功能将跳过）", c.getSimpleName(), name);
            return null;
        }
    }

    /** 安全取字段：找不到返回 null，不抛异常。 */
    private static java.lang.reflect.Field field(Class<?> c, String name) {
        try {
            java.lang.reflect.Field f = c.getDeclaredField(name);
            f.setAccessible(true);
            return f;
        } catch (Throwable t) {
            MaixrSuggestMod.LOGGER.info("[猜你想搜] JEI 缺少字段 {}.{}（该子功能将跳过）", c.getSimpleName(), name);
            return null;
        }
    }

    private static void resolve() {
        if (resolved) return;
        resolved = true;
        try {
            Class<?> internal = Class.forName("mezz.jei.common.Internal");
            // JEI 15.x 的真实方法名是 getOptionalJeiRuntime() / getJeiRuntime()
            try {
                internalGetRuntime = internal.getMethod("getOptionalJeiRuntime");
                runtimeReturnsOptional = true;
            } catch (NoSuchMethodException e) {
                internalGetRuntime = internal.getMethod("getJeiRuntime");
                runtimeReturnsOptional = false;
            }
            // ★ 逐项解析：任何一项对不上都不影响其它功能（JEI 各版本内部结构有差异）
            rtGetIngredientFilter = method(Class.forName("mezz.jei.api.runtime.IJeiRuntime"), "getIngredientFilter");
            rtGetOverlay = method(Class.forName("mezz.jei.api.runtime.IJeiRuntime"), "getIngredientListOverlay");
            filterGetText = method(Class.forName("mezz.jei.api.runtime.IIngredientFilter"), "getFilterText");
            filterSetText = method(Class.forName("mezz.jei.api.runtime.IIngredientFilter"), "setFilterText", String.class);
            overlayHasFocus = method(Class.forName("mezz.jei.api.runtime.IIngredientListOverlay"), "hasKeyboardFocus");
            overlayGetUnderMouse = method(Class.forName("mezz.jei.api.runtime.IIngredientListOverlay"), "getIngredientUnderMouse");
            overlayIsDisplayed = method(Class.forName("mezz.jei.api.runtime.IIngredientListOverlay"), "isListDisplayed");
            try {
                typedGetItemStack = Class.forName("mezz.jei.api.ingredients.ITypedIngredient")
                        .getMethod("getItemStack");
            } catch (Throwable ignored) {}
            tfHistoryField = field(Class.forName("mezz.jei.gui.input.GuiTextFieldFilter"), "history");
            thListField = field(Class.forName("mezz.jei.common.util.TextHistory"), "history");
            // 只要拿到 runtime 入口 + 至少能读写搜索词，就算连接成功
            available = internalGetRuntime != null && rtGetIngredientFilter != null && filterSetText != null;
            MaixrSuggestMod.LOGGER.info("[猜你想搜] JEI 连接结果: {} (runtime={} filter={} overlay={} history={} text={})",
                    available ? "成功" : "部分失败",
                    internalGetRuntime != null, rtGetIngredientFilter != null, rtGetOverlay != null,
                    tfHistoryField != null, filterSetText != null);
        } catch (Throwable t) {
            available = false;
            MaixrSuggestMod.LOGGER.warn("[猜你想搜] 连接 JEI 失败: {}", t.toString());
        }
    }

    /** 取 IJeiRuntime（可能为空）。 */
    private static Object runtime() {
        resolve();
        if (!available) return null;
        try {
            Object o = internalGetRuntime.invoke(null);
            if (o instanceof Optional<?> opt) return opt.orElse(null);
            return o;
        } catch (Throwable t) {
            MaixrSuggestMod.LOGGER.warn("[猜你想搜] 获取 JEI 运行时失败: {}", t.toString());
            return null;
        }
    }

    public static boolean isReady() {
        // getOptionalJeiRuntime() 拿到非空 = JEI 已就绪（JEI 没有 isRuntimeAvailable 方法）
        return runtime() != null;
    }

    private static long lastDiag = 0L;

    /** 诊断输出（每 5 秒最多一次），用于排查"面板不显示"。 */
    public static void diag() {
        long now = System.currentTimeMillis();
        if (now - lastDiag < 5000L) return;
        lastDiag = now;
        Object rt = runtime();
        MaixrSuggestMod.LOGGER.info("[猜你想搜][诊断] available={} runtime={} ready={} focused={} text='{}'",
                available, rt != null, rt != null && isReady(), isSearchFocused(), currentText());
    }

    /** JEI 搜索框是否处于聚焦状态（= 用户点了搜索框在打字）。 */
    public static boolean isSearchFocused() {
        Object rt = runtime();
        if (rt == null || rtGetOverlay == null || overlayHasFocus == null) return false;
        try {
            Object overlay = rtGetOverlay.invoke(rt);
            if (overlay == null) return false;
            Object r = overlayHasFocus.invoke(overlay);
            return r instanceof Boolean b && b;
        } catch (Throwable t) { return false; }
    }

    /** 当前搜索框里的文字。 */
    public static String currentText() {
        Object rt = runtime();
        if (rt == null || rtGetIngredientFilter == null || filterGetText == null) return "";
        try {
            Object f = rtGetIngredientFilter.invoke(rt);
            Object s = filterGetText.invoke(f);
            return s instanceof String str ? str : "";
        } catch (Throwable t) { return ""; }
    }

    /** 把文字写进搜索框并立即生效（点击候选时调用）。 */
    public static boolean applySearch(String word) {
        Object rt = runtime();
        if (rt == null || rtGetIngredientFilter == null || filterSetText == null) return false;
        try {
            Object f = rtGetIngredientFilter.invoke(rt);
            filterSetText.invoke(f, word == null ? "" : word);
            return true;
        } catch (Throwable t) {
            MaixrSuggestMod.LOGGER.warn("[猜你想搜] 设置搜索词失败: {}", t.toString());
            return false;
        }
    }

    /**
     * 取搜索框在屏幕上的位置 [x, y, width, height]；取不到返回 null。
     * 注意：这里对 EditBox 用强转而非反射 —— EditBox 是原版类，
     * 编译期用官方名、运行期由 Forge 自动重映射，比反射硬编码 SRG 名可靠得多。
     */
    public static int[] searchBoxBounds() {
        Object rt = runtime();
        if (rt == null || rtGetOverlay == null) return null;
        try {
            Object overlay = rtGetOverlay.invoke(rt);
            if (overlay == null) return null;
            if (overlaySearchField == null) {
                overlaySearchField = overlay.getClass().getDeclaredField("searchField");
                overlaySearchField.setAccessible(true);
            }
            Object sf = overlaySearchField.get(overlay);
            if (sf instanceof net.minecraft.client.gui.components.EditBox box) {
                return new int[]{ box.getX(), box.getY(), box.getWidth(), box.getHeight() };
            }
            return null;
        } catch (Throwable t) {
            MaixrSuggestMod.LOGGER.info("[猜你想搜] 取搜索框坐标失败: {}", t.toString());
            return null;
        }
    }

    private static Method filterGetItemStacks;
    private static boolean probeResolved = false;

    /** 探测某个搜索词在 JEI 里能否搜到东西（写入→读结果数→还原）。 */
    public static boolean hasSearchResult(String query) {
        Object rt = runtime();
        if (rt == null || rtGetIngredientFilter == null) return true;
        try {
            if (!probeResolved) {
                probeResolved = true;
                try {
                    Class<?> f = Class.forName("mezz.jei.api.runtime.IIngredientFilter");
                    filterGetItemStacks = f.getMethod("getFilteredItemStacks");
                } catch (Throwable ignored) {}
            }
            if (filterGetItemStacks == null) return true;
            Object filter = rtGetIngredientFilter.invoke(rt);
            String old = currentText();
            applySearch(query);
            Object listObj = filterGetItemStacks.invoke(filter);
            int n = (listObj instanceof java.util.Collection) ? ((java.util.Collection<?>) listObj).size() : -1;
            applySearch(old);
            return n != 0;
        } catch (Throwable t) {
            return true;
        }
    }

    /**
     * ★ 鼠标下正在悬停的 JEI 物品（用于记录「搜完这个词之后你点开了哪个物品」）。
     * 拿不到就返回 null，调用方静默跳过。
     */
    public static net.minecraft.world.item.ItemStack ingredientUnderMouse() {
        Object rt = runtime();
        if (rt == null || rtGetOverlay == null || overlayGetUnderMouse == null || typedGetItemStack == null) return null;
        try {
            Object overlay = rtGetOverlay.invoke(rt);
            if (overlay == null) return null;
            Object o = overlayGetUnderMouse.invoke(overlay);
            if (!(o instanceof Optional<?> opt) || opt.isEmpty()) return null;
            Object typed = opt.get();
            Object s = typedGetItemStack.invoke(typed);
            if (s instanceof Optional<?> so && so.isPresent()
                    && so.get() instanceof net.minecraft.world.item.ItemStack stack && !stack.isEmpty()) {
                return stack;
            }
            return null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** JEI 物品列表当前是否显示（点选记录用：列表没显示时鼠标下不算"点了物品"）。 */
    public static boolean isListDisplayed() {
        Object rt = runtime();
        if (rt == null || rtGetOverlay == null || overlayIsDisplayed == null) return false;
        try {
            Object overlay = rtGetOverlay.invoke(rt);
            Object r = overlayIsDisplayed.invoke(overlay);
            return r instanceof Boolean b && b;
        } catch (Throwable t) { return false; }
    }

    /** 读取 JEI 自带的搜索历史（最近在前）。 */
    @SuppressWarnings("unchecked")
    public static List<String> jeiHistory(int limit) {
        resolve();
        List<String> out = new ArrayList<>();
        if (tfHistoryField == null || thListField == null) return out;
        try {
            Object textHistory = tfHistoryField.get(null);
            if (textHistory == null) return out;
            Object listObj = thListField.get(textHistory);
            if (listObj instanceof List<?> list) {
                // JEI 的 history 是"旧 -> 新"，我们反转成"新 -> 旧"
                for (int i = list.size() - 1; i >= 0 && out.size() < limit; i--) {
                    Object o = list.get(i);
                    if (o instanceof String s && !s.isBlank()) out.add(s);
                }
            }
        } catch (Throwable t) {
            MaixrSuggestMod.LOGGER.info("[猜你想搜] 读取 JEI 历史失败: {}", t.toString());
        }
        return out;
    }
}
