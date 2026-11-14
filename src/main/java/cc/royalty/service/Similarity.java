package cc.royalty.service;

/** 标题/名称相似度：仅用于产生候选，绝不作为自动归属依据 */
public final class Similarity {

    public static String normalize(String s) {
        if (s == null) {
            return "";
        }
        return s.toLowerCase()
                .replaceAll("[\\p{Punct}]+", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    /** 基于 Levenshtein 距离的相似度，区间 [0,1] */
    public static double titleSimilarity(String a, String b) {
        String x = normalize(a);
        String y = normalize(b);
        if (x.isEmpty() || y.isEmpty()) {
            return 0.0;
        }
        int dist = levenshtein(x, y);
        return Math.max(0.0, 1.0 - (double) dist / Math.max(x.length(), y.length()));
    }

    public static int levenshtein(String x, String y) {
        int[] prev = new int[y.length() + 1];
        int[] cur = new int[y.length() + 1];
        for (int j = 0; j <= y.length(); j++) {
            prev[j] = j;
        }
        for (int i = 1; i <= x.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= y.length(); j++) {
                int cost = x.charAt(i - 1) == y.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] tmp = prev;
            prev = cur;
            cur = tmp;
        }
        return prev[y.length()];
    }

    /** 报告艺人名是否与作品贡献者/权利人名称相互包含 */
    public static boolean nameMatches(String reportedArtist, String... catalogNames) {
        String a = normalize(reportedArtist);
        if (a.isEmpty()) {
            return false;
        }
        for (String n : catalogNames) {
            String c = normalize(n);
            if (!c.isEmpty() && (a.contains(c) || c.contains(a))) {
                return true;
            }
        }
        return false;
    }

    private Similarity() {
    }
}
