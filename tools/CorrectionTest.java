import me.towdium.pinin.PinIn;
import me.towdium.pinin.elements.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

public class CorrectionTest {
    static PinIn pin = new PinIn();
    static Set<String> names = new HashSet<>();
    static List<String> nameList = new ArrayList<>();
    static Map<String, String> aliases = new HashMap<>();
    static Map<Character, Character> iniCache = new HashMap<>();

    static char initialOf(char c) {
        if (c < 128) return Character.toLowerCase(c);
        Character hit = iniCache.get(c);
        if (hit != null) return hit;
        Pinyin[] pys = pin.getChar(c).pinyins();
        if (pys == null || pys.length == 0) return 0;
        String py = String.valueOf(pys[0]);
        if (py.isEmpty()) return 0;
        char r = Character.toLowerCase(py.charAt(0));
        iniCache.put(c, r);
        return r;
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

    static String initials(String text) {
        StringBuilder sb = new StringBuilder();
        for (char c : text.toCharArray()) {
            char i = initialOf(c);
            if (i == 0) return null;
            sb.append(i);
        }
        return sb.toString();
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
        if (key.length() < 3) return null;
        String ini = initials(key);
        if (ini != null && !ini.isEmpty()) {
            String best = null; int bestD = Integer.MAX_VALUE;
            for (String n : nameList) {
                if (n.length() != key.length()) continue;
                String ni = initials(n);
                if (ni == null || !ini.equals(ni)) continue;
                if (n.equalsIgnoreCase(key)) continue;
                best = n; break;
            }
            if (best != null) return best;
        }
        return null;
    }

    public static void main(String[] args) throws Exception {
        Path dir = Paths.get(args[0]);
        for (String l : Files.readAllLines(dir.resolve("names.txt"), StandardCharsets.UTF_8)) {
            String s = l.trim(); if (!s.isEmpty()) { names.add(s); nameList.add(s); }
        }
        for (String l : Files.readAllLines(dir.resolve("alias.txt"), StandardCharsets.UTF_8)) {
            int i = l.indexOf('=');
            if (i > 0) aliases.put(l.substring(0, i).trim().toLowerCase(Locale.ROOT), l.substring(i + 1).trim());
        }
        System.out.println("物品名 " + names.size() + " / 别名 " + aliases.size());
        String[][] cases = {
            {"土豆","马铃薯"},{"洋芋","马铃薯"},{"砖石","钻石"},{"会议室","黑曜石"},
            {"鹅卵石","圆石"},{"棍子","木棍"},{"合成台","工作台"},{"马玲薯","马铃薯"},
            {"黑耀石","黑曜石"},{"萤石","荧石"},{"粘土球","黏土球"},{"红罗卜","胡萝卜"},
            {"翅膀","鞘翅"},{"复活图腾","不死图腾"},{"经验瓶","附魔之瓶"},{"光束塔","信标"},
            {"泥土",null},{"钻石",null},{"木棍",null},{"工作台",null},{"马铃薯",null},
            {"红石","红石粉"},{"原木","橡木原木"},{"石英","下界石英"},{"石头",null},{"铁锭",null},{"苹果",null},
            {"木板","橡木木板"},{"玻璃",null},
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
