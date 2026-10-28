import me.towdium.pinin.PinIn;
import me.towdium.pinin.elements.Pinyin;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

public class CorrectionTest {
    static PinIn pin = new PinIn();
    static Set<String> names = new HashSet<>();
    static List<String> nameList = new ArrayList<>();
    static Map<String, String> aliases = new HashMap<>();
    static Map<Character, Character> iniCache = new HashMap<>();
    static Map<Character, String[]> readingsCache = new HashMap<>();
    static Map<String, List<Character>> byReading = new HashMap<>();
    static Map<String, List<String>> byInitials = new HashMap<>();

    static char initialOf(char c) {
        if (c < 128) return Character.toLowerCase(c);
        Character hit = iniCache.get(c);
        if (hit != null) return hit;
        String[] rs = readingsOf(c);
        if (rs == null) return 0;
        char r = rs[0].charAt(0);
        iniCache.put(c, r);
        return r;
    }

    static String[] readingsOf(char c) {
        if (c < 128) return new String[]{ String.valueOf(Character.toLowerCase(c)) };
        String[] hit = readingsCache.get(c);
        if (hit != null) return hit;
        Pinyin[] pys = pin.getChar(c).pinyins();
        if (pys == null || pys.length == 0) return null;
        String[] out = new String[pys.length];
        for (int i = 0; i < pys.length; i++) out[i] = String.valueOf(pys[i]).toLowerCase();
        readingsCache.put(c, out);
        return out;
    }

    static String initials(String text) {
        StringBuilder sb = new StringBuilder();
        for (char c : text.toCharArray()) {
            char i = initialOf(c);
            if (i == 0) return null;
            sb.append(i);
        }
        return sb.toString();
    }

    static void index(String n) {
        String ini = initials(n);
        if (ini != null) byInitials.computeIfAbsent(ini, k -> new ArrayList<>()).add(n);
        for (char c : n.toCharArray()) {
            if (c < 128) continue;
            String[] rs = readingsOf(c);
            if (rs == null) continue;
            for (String r : rs) {
                List<Character> l = byReading.computeIfAbsent(r, k -> new ArrayList<>());
                if (l.size() < 40 && !l.contains(c)) l.add(c);
            }
        }
    }

    static boolean oneSubstitutionSameSound(String a, String b) {
        if (a.length() != b.length()) return false;
        int diff = -1;
        for (int i = 0; i < a.length(); i++) {
            if (a.charAt(i) == b.charAt(i)) continue;
            if (diff >= 0) return false;
            diff = i;
        }
        if (diff < 0) return false;
        char ca = initialOf(a.charAt(diff)), cb = initialOf(b.charAt(diff));
        return ca != 0 && ca == cb;
    }

    static Map<String, Integer> countAll(Collection<String> terms, int limit) {
        Map<String, Integer> m = new LinkedHashMap<>();
        for (String t : terms) if (t != null && !t.isEmpty()) m.put(t, 0);
        for (String n : nameList) {
            for (Map.Entry<String, Integer> e : m.entrySet()) {
                if (e.getValue() >= limit) continue;
                if (n.contains(e.getKey())) e.setValue(e.getValue() + 1);
            }
        }
        return m;
    }

    static String correction(String input) {
        String key = input.trim();
        if (key.length() < 2 || key.length() > 16) return null;
        String lower = key.toLowerCase(Locale.ROOT);
        if (names.contains(key)) return null;
        String a = aliases.get(lower);
        if (a != null && names.contains(a)) return a;
        for (String n : nameList) {
            if (n.length() != key.length()) continue;
            if (oneSubstitutionSameSound(lower, n.toLowerCase(Locale.ROOT))) return n;
        }
        if (key.length() >= 3) {
            String ini = initials(key);
            if (ini != null) {
                for (String n : byInitials.getOrDefault(ini, List.of())) {
                    if (n.length() == key.length()) return n;
                }
            }
        }
        if (key.length() >= 2) {
            LinkedHashSet<String> cands = new LinkedHashSet<>();
            int replaced = 0;
            for (int i = 0; i < key.length() && replaced < 12; i++) {
                char c = key.charAt(i);
                String[] rs = readingsOf(c);
                if (rs == null) continue;
                for (String r : rs) {
                    for (char alt : byReading.getOrDefault(r, List.of())) {
                        if (alt == c || replaced >= 12) continue;
                        String swapped = key.substring(0, i) + alt + key.substring(i + 1);
                        if (names.contains(swapped)) return swapped;
                        for (int len = swapped.length(); len >= i + 1; len--) cands.add(swapped.substring(0, len));
                        replaced++;
                    }
                }
            }
            if (!cands.isEmpty()) {
                String best = null; int bestHits = 2;
                for (Map.Entry<String, Integer> e : countAll(cands, 9).entrySet()) {
                    int h = e.getValue();
                    if (h <= bestHits) continue;
                    best = e.getKey(); bestHits = h;
                }
                if (best != null) return best;
            }
        }
        return null;
    }

    public static void main(String[] args) throws Exception {
        Path dir = Paths.get(args[0]);
        for (String l : Files.readAllLines(dir.resolve("names.txt"), StandardCharsets.UTF_8)) {
            String s = l.trim(); if (!s.isEmpty() && names.add(s)) nameList.add(s);
        }
        for (String l : Files.readAllLines(dir.resolve("alias.txt"), StandardCharsets.UTF_8)) {
            int i = l.indexOf('=');
            if (i > 0) aliases.put(l.substring(0, i).trim().toLowerCase(Locale.ROOT), l.substring(i + 1).trim());
        }
        for (String n : nameList) index(n);
        System.out.println("物品名 " + names.size() + " / 别名 " + aliases.size());
        String[][] cases = {
            {"稿子","镐"},{"稿",null},   // 单字不纠错（歧义太大）
            {"土豆","马铃薯"},{"洋芋","马铃薯"},{"砖石","钻石"},{"会议室","黑曜石"},
            {"鹅卵石","圆石"},{"鹅软石","圆石"},{"棍子","木棍"},{"合成台","工作台"},
            {"马玲薯","马铃薯"},{"黑耀石","黑曜石"},{"萤石","荧石"},{"粘土球","黏土球"},
            {"红罗卜","胡萝卜"},{"翅膀","鞘翅"},{"复活图腾","不死图腾"},{"经验瓶","附魔之瓶"},
            {"光束塔","信标"},{"红石","红石粉"},{"原木","橡木原木"},{"石英","下界石英"},{"木板","橡木木板"},
            {"泥土",null},{"钻石",null},{"木棍",null},{"工作台",null},{"马铃薯",null},
            {"石头",null},{"铁锭",null},{"苹果",null},{"玻璃",null},{"红石粉",null},
        };
        int pass=0, fail=0;
        for (String[] c : cases) {
            long t0 = System.nanoTime();
            String got = correction(c[0]);
            long us = (System.nanoTime()-t0)/1000;
            boolean ok = Objects.equals(got, c[1]);
            if (ok) pass++; else fail++;
            System.out.printf("%s %-6s -> %-10s (期望 %-10s) %6d us%n", ok?"OK":"FAIL", c[0], String.valueOf(got), String.valueOf(c[1]), us);
        }
        System.out.println();
        System.out.println("通过 " + pass + " / 失败 " + fail);
    }
}
