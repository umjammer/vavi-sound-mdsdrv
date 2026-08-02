/*
 * Copyright (c) 2026 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.sound.ctrmml;


/**
 * C locale &lt;cctype&gt; equivalents.
 * <p>
 * The parser relies on the "C" locale semantics of the original C++ code, so
 * {@link Character} must not be used (it would accept unicode digits/letters).
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 2026-08-02 nsano initial version <br>
 */
public final class CType {

    private CType() {
    }

    /** {@code isblank()}: space and horizontal tab only. */
    public static boolean isBlank(int c) {
        return c == ' ' || c == '\t';
    }

    /** {@code isspace()} */
    public static boolean isSpace(int c) {
        return c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == 0x0b || c == '\f';
    }

    /** {@code isdigit()} */
    public static boolean isDigit(int c) {
        return c >= '0' && c <= '9';
    }

    /** {@code isalpha()} */
    public static boolean isAlpha(int c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
    }

    /** {@code isalnum()} */
    public static boolean isAlnum(int c) {
        return isAlpha(c) || isDigit(c);
    }

    /** {@code isxdigit()} */
    public static boolean isXDigit(int c) {
        return isDigit(c) || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
    }

    /** {@code tolower()} */
    public static int toLower(int c) {
        return (c >= 'A' && c <= 'Z') ? c + 0x20 : c;
    }

    /** {@code toupper()} */
    public static int toUpper(int c) {
        return (c >= 'a' && c <= 'z') ? c - 0x20 : c;
    }

    /** ctrmml {@code iequal()}: case-insensitive string comparison. */
    public static boolean iequal(String s1, String s2) {
        if (s1.length() != s2.length()) {
            return false;
        }
        for (int i = 0; i < s1.length(); i++) {
            if (toLower(s1.charAt(i)) != toLower(s2.charAt(i))) {
                return false;
            }
        }
        return true;
    }
}
