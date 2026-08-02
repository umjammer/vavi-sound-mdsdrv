/*
 * Copyright (c) 2026 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.sound.ctrmml;


/**
 * A C string cursor, providing {@code strtol()}/{@code strtod()} with the
 * "advance the pointer" behaviour the original envelope parsers depend on.
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 2026-08-02 nsano initial version <br>
 */
public final class Cursor {

    private final String s;
    private int pos;

    public Cursor(String s) {
        this.s = s;
    }

    /** @return the character at the cursor, or 0 at the end of the string */
    public int peek() {
        return pos < s.length() ? s.charAt(pos) : 0;
    }

    /** {@code *++s} : advance then read. */
    public int next() {
        pos++;
        return peek();
    }

    /** {@code s++} : read then advance. */
    public int getAndAdvance() {
        int c = peek();
        pos++;
        return c;
    }

    public void advance() {
        pos++;
    }

    public int tell() {
        return pos;
    }

    /** @return the remainder of the string from the cursor */
    public String rest() {
        return pos < s.length() ? s.substring(pos) : "";
    }

    /**
     * {@code strtol(s, &s, base)}. If no conversion is possible the cursor is not
     * moved and 0 is returned.
     */
    public long strtol(int base) {
        int p = pos;
        while (p < s.length() && CType.isSpace(s.charAt(p))) {
            p++;
        }
        boolean negative = false;
        if (p < s.length() && (s.charAt(p) == '+' || s.charAt(p) == '-')) {
            negative = s.charAt(p) == '-';
            p++;
        }
        if ((base == 16 || base == 0) && p + 1 < s.length() && s.charAt(p) == '0'
                && (s.charAt(p + 1) == 'x' || s.charAt(p + 1) == 'X')
                && p + 2 < s.length() && CType.isXDigit(s.charAt(p + 2))) {
            p += 2;
            base = 16;
        } else if (base == 0) {
            base = (p < s.length() && s.charAt(p) == '0') ? 8 : 10;
        }
        int start = p;
        long value = 0;
        while (p < s.length()) {
            int digit = digit(s.charAt(p), base);
            if (digit < 0) {
                break;
            }
            value = value * base + digit;
            if (value > 0x1_0000_0000L) { // saturate, avoids overflow on absurd input
                value = 0x1_0000_0000L;
            }
            p++;
        }
        if (p == start) {
            return 0; // no conversion, cursor unchanged
        }
        pos = p;
        return negative ? -value : value;
    }

    /** {@code strtod(s, &s)}. If no conversion is possible the cursor is not moved. */
    public double strtod() {
        int p = pos;
        while (p < s.length() && CType.isSpace(s.charAt(p))) {
            p++;
        }
        int start = p;
        if (p < s.length() && (s.charAt(p) == '+' || s.charAt(p) == '-')) {
            p++;
        }
        int digits = 0;
        while (p < s.length() && CType.isDigit(s.charAt(p))) {
            p++;
            digits++;
        }
        if (p < s.length() && s.charAt(p) == '.') {
            p++;
            while (p < s.length() && CType.isDigit(s.charAt(p))) {
                p++;
                digits++;
            }
        }
        if (digits == 0) {
            return 0; // no conversion, cursor unchanged
        }
        int mantissaEnd = p;
        if (p < s.length() && (s.charAt(p) == 'e' || s.charAt(p) == 'E')) {
            int q = p + 1;
            if (q < s.length() && (s.charAt(q) == '+' || s.charAt(q) == '-')) {
                q++;
            }
            int expDigits = 0;
            while (q < s.length() && CType.isDigit(s.charAt(q))) {
                q++;
                expDigits++;
            }
            if (expDigits > 0) {
                p = q;
            } else {
                p = mantissaEnd;
            }
        }
        double value = Double.parseDouble(s.substring(start, p));
        pos = p;
        return value;
    }

    private static int digit(char c, int base) {
        int d;
        if (CType.isDigit(c)) {
            d = c - '0';
        } else if (c >= 'a' && c <= 'z') {
            d = c - 'a' + 10;
        } else if (c >= 'A' && c <= 'Z') {
            d = c - 'A' + 10;
        } else {
            return -1;
        }
        return d < base ? d : -1;
    }

    @Override
    public String toString() {
        return rest();
    }
}
