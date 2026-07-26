package dev.mapaimine.bridge.util;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Edit distance used to produce {@code suggestions} for {@code POST /validate}. */
public final class Levenshtein {

    private Levenshtein() {
    }

    /** Classic two-row dynamic programming distance; O(n) memory. */
    public static int distance(String a, String b) {
        int n = a.length();
        int m = b.length();
        if (n == 0) return m;
        if (m == 0) return n;
        int[] prev = new int[m + 1];
        int[] cur = new int[m + 1];
        for (int j = 0; j <= m; j++) prev[j] = j;
        for (int i = 1; i <= n; i++) {
            cur[0] = i;
            char ca = a.charAt(i - 1);
            for (int j = 1; j <= m; j++) {
                int cost = ca == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] t = prev;
            prev = cur;
            cur = t;
        }
        return prev[m];
    }

    /**
     * Returns up to {@code limit} candidates closest to {@code input}, ignoring anything further
     * away than {@code maxDistance}. Candidates are compared case-insensitively.
     */
    public static List<String> closest(String input, Iterable<String> candidates, int limit, int maxDistance) {
        String needle = input.toLowerCase(java.util.Locale.ROOT);
        List<String[]> scored = new ArrayList<>();
        for (String c : candidates) {
            int d = distance(needle, c.toLowerCase(java.util.Locale.ROOT));
            if (d <= maxDistance) scored.add(new String[]{c, String.valueOf(d)});
        }
        scored.sort(Comparator.comparingInt(s -> Integer.parseInt(s[1])));
        List<String> out = new ArrayList<>(Math.min(limit, scored.size()));
        for (int i = 0; i < scored.size() && out.size() < limit; i++) out.add(scored.get(i)[0]);
        return out;
    }
}
