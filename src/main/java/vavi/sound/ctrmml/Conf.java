/*
 * Copyright (c) 2026 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.sound.ctrmml;

import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;


/**
 * Configuration object, handling a simple hierarchical configuration file format.
 * <p>
 * Syntax is as follows:
 * <pre>
 * level0 {
 *     level1 { level2 level2 level2 }
 *     level1: level2
 *     level1
 *     level1,
 *     ,
 * }
 * </pre>
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 2026-08-02 nsano initial version <br>
 * @see <a href="https://github.com/superctr/ctrmml">ctrmml</a> src/conf.cpp
 */
public class Conf {

    /** Characters that break a token. */
    private static final String BREAK_SEQUENCES = " \t\r\n\":,;{}";

    public final List<Conf> subkeys = new ArrayList<>();
    public String key;

    public Conf() {
        this("");
    }

    public Conf(String key) {
        this.key = key;
    }

    public Conf(String key, List<Conf> subkeys) {
        this.key = key;
        this.subkeys.addAll(subkeys);
    }

    /**
     * @throws NoSuchElementException if there is no such subkey
     */
    public Conf getSubkey(String key) {
        for (Conf subkey : subkeys) {
            if (subkey.key.equals(key)) {
                return subkey;
            }
        }
        throw new NoSuchElementException(key);
    }

    /** Create a Conf from a string. */
    public static Conf fromString(String str) {
        Conf conf = new Conf("");
        int head = 0;
        while (head < str.length()) {
            head = conf.parseToken(str, head);
        }
        return conf;
    }

    /**
     * Parse configuration from string.
     * <p>
     * This method processes one token at a time, and returns after each key, or at the end of
     * the string if there are no more tokens.
     *
     * @return the new position
     */
    private int parseToken(String str, int head) {
        StringBuilder k = new StringBuilder();
        boolean readKey = false;
        while (head < str.length() && str.charAt(head) != '}') {
            // break sequences
            int tail = strpbrk(str, head);
            if (tail == -1) {
                tail = str.length();
            }

            if (tail > head) {
                // read tag
                if (readKey) {
                    break;
                }
                readKey = true;
                k.setLength(0);
                k.append(str, head, tail);
                head = tail;
            } else if (str.charAt(head) == '"') {
                // read quote-enclosed tag
                if (readKey) {
                    break;
                }
                readKey = true;
                while (++head < str.length()) {
                    char c = str.charAt(head);
                    if (c == '\\') {
                        head++;
                        if (head >= str.length()) {
                            break;
                        }
                        c = str.charAt(head);
                        if (c == 'n') {
                            k.append('\n');
                        } else if (c == '\t') {
                            k.append('\t');
                        } else if (c != '\r') {
                            k.append(c);
                        }
                        continue;
                    } else if (c == '"') {
                        head++;
                        break;
                    }
                    k.append(c);
                }
            } else if (str.charAt(head) == ':') {
                // assign subkey
                Conf subkey = new Conf(k.toString());
                head = subkey.parseToken(str, head + 1);
                subkeys.add(subkey);
                return head;
            } else if (str.charAt(head) == ',') {
                // end of key (use to write empty keys)
                subkeys.add(new Conf(k.toString()));
                return head + 1;
            } else if (str.charAt(head) == ';') {
                // comment
                while (head < str.length() && str.charAt(head) != '\n' && str.charAt(head) != '\r') {
                    head++;
                }
            } else {
                // whitespace, or end of list of subkeys
                head++;
                while (head < str.length() && CType.isSpace(str.charAt(head))) {
                    head++;
                }
            }
        }
        if (readKey) {
            subkeys.add(new Conf(k.toString()));
        }
        return head;
    }

    /** {@code strpbrk()} over {@link #BREAK_SEQUENCES}. */
    private static int strpbrk(String s, int from) {
        for (int i = from; i < s.length(); i++) {
            if (BREAK_SEQUENCES.indexOf(s.charAt(i)) != -1) {
                return i;
            }
        }
        return -1;
    }

    @Override
    public String toString() {
        return subkeys.isEmpty() ? key : key + subkeys;
    }
}
