package tcm.util;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.jetbrains.annotations.NotNull;

import lombok.Getter;

/**
 * Line-based diff via LCS (Hirschberg's algorithm: time O(n*m), memory O(n+m)).
 * The result is aligned lines of two files: equal, changed (both sides) and one-sided.
 */
public final class Diff {

    private Diff() {
    }

    /** Alignment line; index -1 means an empty placeholder line on this side. */
    @Getter
    public static final class Row {

        private final int left;
        private final int right;
        private final boolean equal;

        public Row(final int left, final int right, final boolean equal) {
            this.left = left;
            this.right = right;
            this.equal = equal;
        }
    }

    @Getter
    public static final class Result {

        private final @NotNull List<Row> rows;
        /** Indexes of alignment lines where each block of differences starts. */
        private final @NotNull List<Integer> blocks;

        public Result(final @NotNull List<Row> rows, final @NotNull List<Integer> blocks) {
            this.rows = rows;
            this.blocks = blocks;
        }
    }

    public static @NotNull Result compare(final @NotNull List<String> left, final @NotNull List<String> right, final boolean caseSensitive) {
        final Map<String, Integer> ids = new HashMap<>();
        final int[] a = toIds(left, ids, caseSensitive);
        final int[] b = toIds(right, ids, caseSensitive);
        final List<int[]> matches = lcs(a, b);

        return align(a.length, b.length, matches);
    }

    /** Pairs of matching indexes (i, j), in ascending order. */
    public static @NotNull List<int[]> lcs(final int @NotNull [] a, final int @NotNull [] b) {
        final List<int[]> out = new ArrayList<>();
        int prefix = 0;
        while (prefix < a.length && prefix < b.length && a[prefix] == b[prefix]) {
            out.add(new int[] {prefix, prefix});
            prefix++;
        }
        int suffix = 0;
        while (suffix < a.length - prefix && suffix < b.length - prefix && a[a.length - 1 - suffix] == b[b.length - 1 - suffix]) {
            suffix++;
        }
        hirschberg(a, prefix, a.length - suffix, b, prefix, b.length - suffix, out);
        for (int k = suffix; k > 0; k--) {
            out.add(new int[] {a.length - k, b.length - k});
        }

        return out;
    }

    private static int @NotNull [] toIds(final @NotNull List<String> lines, final @NotNull Map<String, Integer> ids, final boolean caseSensitive) {
        final int[] result = new int[lines.size()];
        for (int i = 0; i < result.length; i++) {
            final String key = caseSensitive ? lines.get(i) : lines.get(i).toLowerCase(Locale.ROOT);
            result[i] = ids.computeIfAbsent(key, k -> ids.size());
        }

        return result;
    }

    private static void hirschberg(
        final int @NotNull [] a,
        final int aLo,
        final int aHi,
        final int @NotNull [] b,
        final int bLo,
        final int bHi,
        final @NotNull List<int[]> out
    ) {
        if (aHi <= aLo || bHi <= bLo) {
            return;
        }
        if (aHi - aLo == 1) {
            for (int j = bLo; j < bHi; j++) {
                if (a[aLo] == b[j]) {
                    out.add(new int[] {aLo, j});

                    return;
                }
            }

            return;
        }
        final int mid = (aLo + aHi) >>> 1;
        final int[] forward = forwardLengths(a, aLo, mid, b, bLo, bHi);
        final int[] backward = backwardLengths(a, mid, aHi, b, bLo, bHi);
        int split = 0;
        int best = -1;
        for (int k = 0; k <= bHi - bLo; k++) {
            if (forward[k] + backward[k] > best) {
                best = forward[k] + backward[k];
                split = k;
            }
        }
        hirschberg(a, aLo, mid, b, bLo, bLo + split, out);
        hirschberg(a, mid, aHi, b, bLo + split, bHi, out);
    }

    /** result[k] = LCS(a[aLo..aHi), b[bLo..bLo+k)). */
    private static int @NotNull [] forwardLengths(final int @NotNull [] a, final int aLo, final int aHi, final int @NotNull [] b, final int bLo,
        final int bHi) {
        final int m = bHi - bLo;
        int[] prev = new int[m + 1];
        int[] cur = new int[m + 1];
        for (int i = aLo; i < aHi; i++) {
            cur[0] = 0;
            for (int j = 1; j <= m; j++) {
                cur[j] = a[i] == b[bLo + j - 1] ? prev[j - 1] + 1 : Math.max(prev[j], cur[j - 1]);
            }
            final int[] t = prev;
            prev = cur;
            cur = t;
        }

        return prev;
    }

    /** result[k] = LCS(a[aLo..aHi), b[bLo+k..bHi)). */
    private static int @NotNull [] backwardLengths(final int @NotNull [] a, final int aLo, final int aHi, final int @NotNull [] b, final int bLo,
        final int bHi) {
        final int m = bHi - bLo;
        int[] prev = new int[m + 1];
        int[] cur = new int[m + 1];
        for (int i = aHi - 1; i >= aLo; i--) {
            cur[m] = 0;
            for (int j = m - 1; j >= 0; j--) {
                cur[j] = a[i] == b[bLo + j] ? prev[j + 1] + 1 : Math.max(prev[j], cur[j + 1]);
            }
            final int[] t = prev;
            prev = cur;
            cur = t;
        }

        return prev;
    }

    private static @NotNull Result align(final int n, final int m, final @NotNull List<int[]> matches) {
        final List<Row> rows = new ArrayList<>();
        final List<Integer> blocks = new ArrayList<>();
        int i = 0;
        int j = 0;
        for (int k = 0; k <= matches.size(); k++) {
            final int nextI = k < matches.size() ? matches.get(k)[0] : n;
            final int nextJ = k < matches.size() ? matches.get(k)[1] : m;
            if (i < nextI || j < nextJ) {
                blocks.add(rows.size());
            }
            // changed lines are placed opposite each other, the remainder — with a placeholder on the other side
            while (i < nextI || j < nextJ) {
                rows.add(new Row(i < nextI ? i++ : -1, j < nextJ ? j++ : -1, false));
            }
            if (k < matches.size()) {
                rows.add(new Row(i++, j++, true));
            }
        }

        return new Result(rows, blocks);
    }
}
