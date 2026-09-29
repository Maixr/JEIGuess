package com.maixr.suggest;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.common.EventBusSubscriber;

import java.util.ArrayList;
import java.util.List;

/**
 * 候选面板。
 *
 * ★ 分页模型（无输入时）：
 *   第 1 页 = 混合页（3 最近 + 3 任务 + 1 推荐 + 1 常搜；有伊甸提交物时变成 9 条）
 *   之后每个来源一整页：最近 / 任务 / 常搜 / 聊天 / 热词 / 推荐 / 收藏，条目多就继续分块。
 *   滚轮（或 ←/→、PageUp/PageDown）一路翻下去。
 *
 * 鼠标（不占任何键）：
 *   · 左键 = 搜索；右键 = 收藏；Shift+右键 = 从历史删除；滚轮 = 翻页
 *   · 悬停某行 = 选中；悬停标题 [?] = 操作说明；点标题 [xx] = 换页（搜索时=切换来源筛选）
 *
 * 键盘（★ 每个键只有一个功能）：
 *   · Tab = 接受灰字补全（没有补全时完全不拦截）   · Ctrl+Tab = 换页 / 切换筛选
 *   · PageUp/PageDown 任何时候翻页；←/→ 只在搜索框为空时翻页（有字时留给 JEI）
 *   · Enter = 搜索悬停项；Esc = 关闭；↑/↓ 永远不碰
 */
@EventBusSubscriber(modid = MaixrSuggestMod.MODID, value = Dist.CLIENT)
public final class SuggestOverlay {
    private static final int LINE_H = 12;
    private static final int PAD = 3;
    private static final int BG = 0xF0101010;
    private static final int BORDER = 0xFF4A90D9;
    private static final int SEL_BG = 0xC02B6CB0;
    private static final int HINT = 0xFF888888;
    private static final int GHOST = 0x80808080;
    private static final int HELP_BG = 0xF0181818;

    private static List<SuggestionEngine.Page> pages = new ArrayList<>();
    /** 搜索模式下"全部匹配"（灰字补全 / 来源筛选用） */
    private static List<Suggestion> searchItems = new ArrayList<>();
    private static int page = 0;
    private static String lastInput = "\u0000";
    private static int hovered = -1;
    private static int px, py, pw, ph, rowTop;
    private static boolean visible = false;
    private static boolean diagDone = false;
    private static Suggestion.Source filterSource = null;
    private static String completion = "";
    private static int chipFilterX, chipFilterW, chipHelpX;
    private static boolean helpHovered = false;

    private static final String[] HELP = {
            "左键 / Enter   搜索",
            "右键           收藏（金色置顶）",
            "Shift+右键     从历史里删掉",
            "滚轮 / ← →     换页（最近 / 任务 / 常搜…）",
            "PageUp/PageDn  换页",
            "Tab            接受灰字补全",
            "Ctrl+Tab       换页（输入时=切换筛选）",
            "Esc            关闭",
    };

    private SuggestOverlay() {}

    private static int rowsPerPage() {
        try { return SuggestConfig.ROWS_PER_PAGE.get(); } catch (Throwable t) { return 8; }
    }

    private static List<Suggestion> current() {
        if (pages.isEmpty()) return List.of();
        int p = Math.max(0, Math.min(page, pages.size() - 1));
        return pages.get(p).items;
    }

    private static String currentLabel() {
        if (pages.isEmpty()) return "";
        int p = Math.max(0, Math.min(page, pages.size() - 1));
        return pages.get(p).label;
    }

    /** 组装分页。force = 忽略"输入没变"的短路（切换筛选时用）。 */
    private static void rebuild(String input, boolean force) {
        if (!force && input.equals(lastInput)) return;
        if (!input.equals(lastInput)) page = 0;
        lastInput = input;
        try {
            if (input.isBlank()) {
                pages = SuggestionEngine.browsePages();
                searchItems = new ArrayList<>();
            } else {
                searchItems = SuggestionEngine.filter(input, 64);
                pages = chunkSearch();
            }
        } catch (Throwable t) {
            pages = new ArrayList<>();
            MaixrSuggestMod.LOGGER.info("[猜你想搜] 组装候选失败: {}", t.toString());
        }
        completion = computeCompletion(input);
        if (page >= pages.size()) page = 0;
        hovered = -1;
    }

    /** 搜索模式：先按来源筛选，再按每页行数切块。 */
    private static List<SuggestionEngine.Page> chunkSearch() {
        List<Suggestion> list = new ArrayList<>();
        for (Suggestion s : searchItems) {
            if (filterSource == null || s.source == filterSource) list.add(s);
        }
        List<SuggestionEngine.Page> out = new ArrayList<>();
        if (list.isEmpty()) return out;
        int rows = rowsPerPage();
        int total = (list.size() + rows - 1) / rows;
        for (int i = 0; i < total; i++) {
            List<Suggestion> chunk = new ArrayList<>(
                    list.subList(i * rows, Math.min((i + 1) * rows, list.size())));
            out.add(new SuggestionEngine.Page(total > 1 ? "搜索 " + (i + 1) + "/" + total : "搜索", chunk));
        }
        return out;
    }

    /** 切换来源筛选（只在搜索模式下有意义）。 */
    private static void cycleFilter() {
        List<Suggestion.Source> present = new ArrayList<>();
        for (Suggestion s : searchItems) if (!present.contains(s.source)) present.add(s.source);
        if (present.isEmpty()) { filterSource = null; return; }
        present.sort(java.util.Comparator.comparingInt(Enum::ordinal));
        int idx = filterSource == null ? -1 : present.indexOf(filterSource);
        filterSource = (idx + 1 >= present.size()) ? null : present.get(idx + 1);
        page = 0;
        rebuild(lastInput, true);
    }

    private static String filterName(Suggestion.Source s) {
        if (s == null) return "全部";
        return s.label.isEmpty() ? "物品" : s.label;
    }

    private static void turnPage(int delta) {
        int n = pages.size();
        if (n <= 1) return;
        page = ((page + delta) % n + n) % n;
        hovered = -1;
    }

    /** 灰色补全：第一条以输入开头的候选（忽略大小写）。 */
    private static String computeCompletion(String input) {
        if (input == null || input.isBlank()) return "";
        if (!SuggestConfig.GHOST_TEXT.get()) return "";
        String key = input.toLowerCase(java.util.Locale.ROOT);
        for (Suggestion s : searchItems) {
            String d = s.display;
            if (d == null || d.length() <= input.length()) continue;
            if (d.toLowerCase(java.util.Locale.ROOT).startsWith(key)) return d;
        }
        return "";
    }

    /** 把文字裁到指定宽度（超长打省略号）。 */
    private static String fit(Font font, String text, int maxWidth) {
        if (text == null) return "";
        if (maxWidth <= 0) return "";
        if (font.width(text) <= maxWidth) return text;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            if (font.width(sb.toString() + text.charAt(i) + "…") > maxWidth) break;
            sb.append(text.charAt(i));
        }
        return sb + "…";
    }

    // ───────────────────────── 渲染 ─────────────────────────

    @SubscribeEvent
    public static void onRender(ScreenEvent.Render.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.screen == null) { visible = false; return; }
        if (!JeiBridge.isReady() || !JeiBridge.isSearchFocused()) {
            if (!diagDone) { JeiBridge.diag(); diagDone = true; }
            visible = false; lastInput = "\u0000"; completion = ""; helpHovered = false; return;
        }
        String input = JeiBridge.currentText();
        rebuild(input, false);
        GuiGraphics g = event.getGuiGraphics();
        Font font = mc.font;

        if (!completion.isEmpty()) drawGhost(g, font, input);

        List<Suggestion> vis = current();
        if (vis.isEmpty()) { visible = false; return; }
        visible = true;

        String head = "\u00a7bJEI Guess" + (pages.size() > 1 ? " \u00a7e" + (page + 1) + "/" + pages.size() : "");
        String label = input.isBlank() ? currentLabel() : filterName(filterSource);
        String chip = "[" + label + "]";
        int w = SuggestConfig.PANEL_WIDTH.get();
        w = Math.max(w, PAD * 4 + font.width(head) + 6 + font.width(chip) + 6 + font.width("?") + 6);
        for (Suggestion s : vis) {
            String tag = s.source.label.isEmpty() ? "" : "[" + s.source.label + "] ";
            int badgeNeed = (s.badge.isEmpty() || s.badgeOnHover) ? 0 : font.width(s.badge);
            w = Math.max(w, PAD * 2 + font.width(tag) + font.width(s.display) + badgeNeed + 14);
        }
        int maxW = Math.max(120, event.getScreen().width - 20);
        w = Math.min(w, maxW);
        int h = vis.size() * LINE_H + PAD * 2 + 10;

        int[] box = JeiBridge.searchBoxBounds();
        if (box != null) {
            px = box[0] - w - 6;
            if (px < 2) px = Math.max(2, Math.min(box[0], box[0] + box[2] - w));
            py = box[1] - h - 2;
            if (py < 2) py = box[1] + box[3] + 2;
        } else {
            px = (event.getScreen().width - w) / 2;
            py = event.getScreen().height - h - 40;
        }
        pw = w; ph = h;

        double mx = mc.mouseHandler.xpos() * event.getScreen().width / (double) mc.getWindow().getWidth();
        double my = mc.mouseHandler.ypos() * event.getScreen().height / (double) mc.getWindow().getHeight();
        int top = py + PAD + 10;
        rowTop = top;
        hovered = -1;
        if (SuggestConfig.HOVER_SELECTS.get() && mx >= px && mx <= px + w && my >= py && my <= py + h) {
            int local = (int) ((my - top) / LINE_H);
            if (local >= 0 && local < vis.size()) hovered = local;
        }

        int cy = py + PAD;
        int cx = px + PAD;
        g.pose().pushPose();
        g.pose().translate(0, 0, 400);
        g.fill(px - 1, py - 1, px + w + 1, py + h + 1, BORDER);
        g.fill(px, py, px + w, py + h, BG);
        g.drawString(font, head, cx, cy, HINT, false);
        cx += font.width(head) + 6;
        chipFilterX = cx;
        chipFilterW = font.width(chip);
        boolean chipHover = mx >= cx - 2 && mx <= cx + chipFilterW + 2 && my >= cy - 2 && my <= cy + 10;
        g.drawString(font, chip, cx, cy, chipHover ? 0xFFFFD700 : 0xFFB07CE8, false);
        chipHelpX = px + w - PAD - font.width("?");
        helpHovered = mx >= chipHelpX - 3 && mx <= chipHelpX + font.width("?") + 3 && my >= cy - 3 && my <= cy + 11;
        g.drawString(font, "?", chipHelpX, cy, helpHovered ? 0xFFFFD700 : HINT, false);

        int y = top;
        int nameRight = px + w - PAD - 4;
        for (int i = 0; i < vis.size(); i++) {
            Suggestion s = vis.get(i);
            boolean sel = (i == hovered);
            if (sel) g.fill(px + 1, y - 1, px + w - 1, y + LINE_H - 1, SEL_BG);
            int textX = px + PAD;
            if (!s.source.label.isEmpty()) {
                String tag = "[" + s.source.label + "]";
                g.drawString(font, tag, px + PAD, y + 2, s.source.color, false);
                textX += font.width(tag) + 3;
            }
            // ★ 悬停徽标只在悬停到这一行时才画（名字会自动让位）
            int badgeW = (s.badge.isEmpty() || (s.badgeOnHover && !sel)) ? 0 : font.width(s.badge) + 4;
            String name = fit(font, s.display, nameRight - textX - badgeW);
            int textColor = sel ? 0xFFFFFFFF : s.source.color;
            g.drawString(font, name, textX, y + 2, textColor, false);
            if (badgeW > 0) {
                g.drawString(font, s.badge, nameRight - font.width(s.badge), y + 2, HINT, false);
            }
            y += LINE_H;
        }
        if (helpHovered) drawHelp(g, font, mx, my);
        g.pose().popPose();
    }

    private static void drawHelp(GuiGraphics g, Font font, double mx, double my) {
        int w = 0;
        for (String s : HELP) w = Math.max(w, font.width(s));
        w += PAD * 2 + 2;
        int h = HELP.length * (LINE_H - 2) + PAD * 2 + 2;
        int x = (int) Math.min(mx + 8, px + pw - w);
        if (x < 2) x = 2;
        int y = (int) Math.min(my + 8, py + ph - h);
        if (y < 2) y = 2;
        g.pose().pushPose();
        g.pose().translate(0, 0, 500);
        g.fill(x - 1, y - 1, x + w + 1, y + h + 1, BORDER);
        g.fill(x, y, x + w, y + h, HELP_BG);
        int ty = y + PAD + 1;
        for (String s : HELP) {
            g.drawString(font, s, x + PAD, ty, 0xFFDDDDDD, false);
            ty += LINE_H - 2;
        }
        g.pose().popPose();
    }

    private static void drawGhost(GuiGraphics g, Font font, String input) {
        int[] box = JeiBridge.searchBoxBounds();
        if (box == null) return;
        String rest = completion.substring(Math.min(input.length(), completion.length()));
        if (rest.isEmpty()) return;
        int x = box[0] + 5 + font.width(input);
        int maxX = box[0] + box[2] - 4;
        if (x + font.width(rest) > maxX) return;
        int y = box[1] + (box[3] - 8) / 2 + 1;
        g.drawString(font, rest, x, y, GHOST, false);
    }

    // ───────────────────────── 交互 ─────────────────────────

    private static int hit(double mx, double my) {
        if (!visible) return -1;
        if (mx < px || mx > px + pw || my < py || my > py + ph) return -1;
        List<Suggestion> vis = current();
        int local = (int) ((my - rowTop) / LINE_H);
        return (local >= 0 && local < vis.size()) ? local : -1;
    }

    private static void apply(Suggestion s) {
        String q = SuggestionEngine.fixQueryGlobally(s.query);
        if (JeiBridge.applySearch(q)) {
            SearchHistoryStore.get().record(q);
            SearchHistoryStore.get().saveLater();
            lastInput = "\u0000";
            completion = "";
            hovered = -1;
            page = 0;
        }
    }

    @SubscribeEvent
    public static void onMouse(ScreenEvent.MouseButtonPressed.Pre event) {
        if (!visible) return;
        double mx = event.getMouseX(), my = event.getMouseY();
        if (helpHovered) { event.setCanceled(true); return; }
        if (mx >= chipFilterX - 2 && mx <= chipFilterX + chipFilterW + 2
                && my >= py + PAD - 2 && my <= py + PAD + 10) {
            // 输入时 = 切换来源筛选；空输入时 = 翻到下一页
            if (JeiBridge.currentText().isBlank()) turnPage(1); else cycleFilter();
            event.setCanceled(true);
            return;
        }
        int idx = hit(mx, my);
        if (idx < 0) return;
        List<Suggestion> vis = current();
        if (idx >= vis.size()) return;
        Suggestion s = vis.get(idx);
        if (event.getButton() == 0) {
            apply(s);
        } else if (event.getButton() == 1) {
            if (Screen.hasShiftDown()) {
                SearchHistoryStore.get().forget(s.display);
                MaixrSuggestMod.LOGGER.info("[猜你想搜] 已从历史/聊天池删除：{}", s.display);
            } else {
                boolean fav = SearchHistoryStore.get().toggleFavorite(s.display);
                MaixrSuggestMod.LOGGER.info("[猜你想搜] {} {} 收藏", fav ? "已加入" : "已取消", s.display);
            }
            rebuild(lastInput, true);
        } else {
            return;
        }
        event.setCanceled(true);
    }

    /** ★ 滚轮翻页（鼠标在面板上时）。 */
    @SubscribeEvent
    public static void onScroll(ScreenEvent.MouseScrolled.Pre event) {
        if (!visible) return;
        double mx = event.getMouseX(), my = event.getMouseY();
        if (mx < px || mx > px + pw || my < py || my > py + ph) return;
        if (pages.size() <= 1) return;
        turnPage(event.getScrollDeltaY() > 0 ? -1 : 1);
        event.setCanceled(true);
    }

    private static int keyCode(String name) {
        if (name == null) return -1;
        String n = name.trim().toUpperCase(java.util.Locale.ROOT);
        if (n.isEmpty()) return -1;
        if (n.length() == 1) return n.charAt(0);
        try {
            return Class.forName("org.lwjgl.glfw.GLFW").getField("GLFW_KEY_" + n).getInt(null);
        } catch (Throwable t) {
            return -1;
        }
    }

    /** 是翻页键吗？-1 = 否，0 = 上一页，1 = 下一页。 */
    private static int pagingDirection(int key) {
        List<? extends String> keys;
        try { keys = SuggestConfig.PAGING_KEYS.get(); } catch (Throwable t) { return -1; }
        for (String name : keys) {
            if (keyCode(name) != key) continue;
            String n = name.toUpperCase(java.util.Locale.ROOT);
            if (n.contains("UP") || n.contains("LEFT")) return 0;
            if (n.contains("DOWN") || n.contains("RIGHT")) return 1;
        }
        return -1;
    }

    @SubscribeEvent
    public static void onKey(ScreenEvent.KeyPressed.Pre event) {
        if (!visible) return;
        int key = event.getKeyCode();
        String input = JeiBridge.currentText();
        List<Suggestion> vis = current();

        if (key == 258) {                            // Tab
            if (Screen.hasControlDown()) {           // Ctrl+Tab = 换页 / 切换筛选
                if (input.isBlank()) turnPage(1); else cycleFilter();
                event.setCanceled(true);
            } else if (!input.isBlank() && !completion.isEmpty()) {
                JeiBridge.applySearch(completion);
                lastInput = "\u0000";
                event.setCanceled(true);
            }
            return;
        }

        int dir = pagingDirection(key);
        if (dir >= 0) {
            boolean arrow = (key == 263 || key == 262);
            boolean typing = !input.isBlank();
            if (arrow && typing && SuggestConfig.PAGING_NEEDS_EMPTY_INPUT.get()) return;
            if (pages.size() > 1) {
                turnPage(dir == 1 ? 1 : -1);
                event.setCanceled(true);
            }
            return;
        }

        if (key == 257 || key == 335) {              // Enter
            if (!vis.isEmpty()) {
                int idx = (hovered >= 0 && hovered < vis.size()) ? hovered : 0;
                apply(vis.get(idx));
            }
            event.setCanceled(true);
        } else if (key == 256) {                     // Esc
            visible = false; pages = new ArrayList<>(); searchItems = new ArrayList<>();
            lastInput = "\u0000"; completion = ""; hovered = -1; page = 0; filterSource = null;
        }
    }
}
