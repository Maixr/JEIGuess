import me.towdium.pinin.PinIn;

public class PinCheck {
    public static void main(String[] a) {
        PinIn p = new PinIn();
        String[] names = {"绿色染料", "绿色羊毛", "熔岩桶", "荧石", "锹", "铁锹", "魔法水晶"};
        String[] queries = {"lvse", "luse", "lvs", "rongyan", "yingshi"};
        for (String q : queries) {
            StringBuilder hit = new StringBuilder();
            for (String n : names) {
                boolean b = p.begins(n, q), c = p.contains(n, q);
                if (b || c) hit.append(n).append(b ? "(前缀)" : "(包含)").append(' ');
            }
            System.out.println(q + " -> " + (hit.length() == 0 ? "无匹配" : hit.toString()));
        }
    }
}
