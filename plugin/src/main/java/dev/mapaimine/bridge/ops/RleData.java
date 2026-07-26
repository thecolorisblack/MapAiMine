package dev.mapaimine.bridge.ops;

import dev.mapaimine.bridge.api.ApiException;

import java.util.NoSuchElementException;
import java.util.PrimitiveIterator;

/**
 * The run-length encoding shared by the compact {@code blocks} op and the {@code RawStructure}
 * capture format (SCHEMAS.md §3).
 *
 * <p>Format: comma-separated tokens of palette indices in {@code y → z → x} order. A token is
 * either a bare index ({@code "3"}) or a run ({@code "128x0"} = the index {@code 0} repeated 128
 * times). Whitespace around tokens is tolerated.
 *
 * <p>Decoding is streamed: a 4-million-block structure never materialises as an int array.
 */
public final class RleData {

    private RleData() {
    }

    /** Streaming decoder over an RLE string. */
    public static PrimitiveIterator.OfInt decode(String data) {
        return new PrimitiveIterator.OfInt() {
            private int cursor;
            private int runValue;
            private int runLeft;

            private void advance() {
                while (runLeft == 0 && cursor < data.length()) {
                    int end = data.indexOf(',', cursor);
                    if (end < 0) end = data.length();
                    String token = data.substring(cursor, end).trim();
                    cursor = end + 1;
                    if (token.isEmpty()) continue;
                    int x = token.indexOf('x');
                    if (x < 0) x = token.indexOf('X');
                    try {
                        if (x >= 0) {
                            runLeft = Integer.parseInt(token.substring(0, x).trim());
                            runValue = Integer.parseInt(token.substring(x + 1).trim());
                        } else {
                            runLeft = 1;
                            runValue = Integer.parseInt(token);
                        }
                    } catch (NumberFormatException ex) {
                        throw ApiException.badRequest("malformed RLE token '" + token + "' in 'data'",
                                "expected comma separated indices, optionally as \"<count>x<index>\"");
                    }
                    if (runLeft < 0) {
                        throw ApiException.badRequest("negative run length in 'data'");
                    }
                }
            }

            @Override
            public boolean hasNext() {
                advance();
                return runLeft > 0;
            }

            @Override
            public int nextInt() {
                advance();
                if (runLeft == 0) throw new NoSuchElementException();
                runLeft--;
                return runValue;
            }
        };
    }

    /** Counts the decoded length without allocating. Used for estimates and validation. */
    public static long length(String data) {
        long n = 0;
        PrimitiveIterator.OfInt it = decode(data);
        while (it.hasNext()) {
            it.nextInt();
            n++;
            if (n > (1L << 32)) break;
        }
        return n;
    }

    /** Appends one value to an RLE builder, merging with the previous run when possible. */
    public static final class Encoder {
        private final StringBuilder sb = new StringBuilder();
        private int current = Integer.MIN_VALUE;
        private long run;

        public void add(int value) {
            if (value == current) {
                run++;
                return;
            }
            flush();
            current = value;
            run = 1;
        }

        private void flush() {
            if (run == 0) return;
            if (sb.length() > 0) sb.append(',');
            if (run == 1) {
                sb.append(current);
            } else {
                sb.append(run).append('x').append(current);
            }
            run = 0;
        }

        public String finish() {
            flush();
            return sb.toString();
        }
    }
}
