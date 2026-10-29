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
    static Map<Character, String[]> readingsCache = new HashMap<>();
    static Map<Character, String[]> soundsCache = new HashMap<>();
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

    static String[] soundsOf(char c) {
        if (c < 128) return new String[]{ "ascii|" + Character.toLowerCase(c) };
        String[] hit = soundsCache.get(c);
        if (hit != null) return hit;
        Pinyin[] pys = pin.getChar(c).pinyins();
        if (pys == null || pys.length == 0) return null;
        String[] out = new String[pys.length];
        for (int i = 0; i < pys.length; i++) {
            Phoneme[] ph = pys[i].phonemes();
            out[i] = (ph.length >= 2 ? ph[0].toString() + "|" + ph[1].toString() : String.valueOf(pys[i])).toLowerCase();
        }
        soundsCache.put(c, out);
        return out;
    }

    static boolean soundsAlike(char a, char b) {
        String[] sa = soundsOf(a), sb = soundsOf(b);
        if (sa == null || sb == null) return false;
        for (String x : sa) for (String y : sb) if (x.equals(y)) return true;
        return false;
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

    static boolean sameSoundSub(String a, String b) {
        if (a.length() != b.length()) return false;
        int diff = -1;
        for (int i = 0; i < a.length(); i++) {
            if (a.charAt(i) == b.charAt(i)) continue;
            if (diff >= 0) return false;
            diff = i;
        }
        if (diff < 0) return false;
        return soundsAlike(a.charAt(diff), b.charAt(diff));
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
            if (sameSoundSub(lower, n.toLowerCase(Locale.ROOT))) return n;
        }
        if (key.length() >= 3) {
            String ini = initials(key);
            if (ini != null) {
                for (String n : byInitials.getOrDefault(ini, List.of())) {
                    if (n.length() != key.length()) continue;
                    boolean any = false;
                    for (int k = 0; k < key.length(); k++) if (soundsAlike(key.charAt(k), n.charAt(k))) { any = true; break; }
                    if (!any) continue;
                    return n;
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
            {"钻石稿","钻石镐"},{"稿子","镐"},
            {"会议室","黑曜石"},{"砖石","钻石"},{"红石","红石粉"},
            {"土豆","马铃薯"},{"鹅软石","圆石"},{"棍子","木棍"},{"合成台","工作台"},
            {"翅膀","鞘翅"},{"光束塔","信标"},{"萤石","荧石"},{"粘土球","黏土球"},
            {"钻石",null},{"钻石镐",null},{"泥土",null},{"石头",null},{"紫硕果","紫颂果"},
            {"红石粉",null},{"苹果",null},{"玻璃",null},
        };
        int pass=0, fail=0;
        for (String[] c : cases) {
            long t0 = System.nanoTime();
            String got = correction(c[0]);
            long us = (System.nanoTime()-t0)/1000;
            boolean ok = Objects.equals(got, c[1]);
            if (ok) pass++; else fail++;
            System.out.printf("%s %-8s -> %-10s (期望 %-10s) %6d us%n", ok?"OK":"FAIL", c[0], String.valueOf(got), String.valueOf(c[1]), us);
        }
        System.out.println();
        System.out.println("通过 " + pass + " / 失败 " + fail);
    }
}
