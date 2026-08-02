/*
 * Copyright (c) 2026 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.sound.ctrmml;


/**
 * Line buffer interface.
 * <p>
 * This provides a stdio-style interface to a buffer as if it was a file.
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 2026-08-02 nsano initial version <br>
 * @see <a href="https://github.com/superctr/ctrmml">ctrmml</a> src/input.cpp
 */
public class LineBuffer {

    /** current line used by get/unget functions, etc. */
    protected StringBuilder buffer;
    protected int column;

    public LineBuffer(String line, int column) {
        this.buffer = new StringBuilder(line);
        this.column = column;
    }

    public LineBuffer(LineBuffer original) {
        this.buffer = original.buffer;
        this.column = original.column;
    }

    /**
     * Get the next character from the buffer, also incrementing the buffer position.
     *
     * @return 0 if at the end of the current buffer. The column number will still be incremented.
     */
    public int get() {
        if (column >= buffer.length()) {
            column++;
            return 0;
        }
        return buffer.charAt(column++);
    }

    /**
     * Get the next non-blank character from the buffer.
     * <p>
     * Blank characters are skipped until the next non-blank character is found.
     */
    public int getToken() {
        int c;
        do {
            c = get();
        } while (CType.isBlank(c));
        return c;
    }

    /**
     * Get a number from the buffer.
     * <p>
     * Blank characters are skipped until the next number is found. A {@code $} or {@code x}
     * prefix indicates hexadecimal number.
     *
     * @throws InvalidArgumentException if no number could be read.
     */
    public int getNum() {
        int base = 10;
        int c = getToken();
        if (c == '$' || c == 'x') {
            base = 16;
        } else {
            unget(c);
        }
        if (column >= buffer.length()) {
            throw new InvalidArgumentException("expected number");
        }
        Cursor cursor = new Cursor(buffer.substring(column));
        int ret = (int) cursor.strtol(base);
        if (cursor.tell() == 0) {
            throw new InvalidArgumentException("expected number");
        }
        column += cursor.tell();
        return ret;
    }

    /** Return a substring starting from the current position. */
    public String getLine() {
        return column < buffer.length() ? buffer.substring(column) : "";
    }

    /**
     * Put back the character to the buffer, decrementing the buffer position.
     *
     * @param c character to put back. If this is 0, no character will be put back and the
     *          buffer contents are unchanged.
     * @throws IndexOutOfBoundsException if the buffer position is already at 0.
     */
    public void unget(int c) {
        if (column == 0) {
            throw new IndexOutOfBoundsException("unget too many");
        }
        if (c == 0) {
            column--;
            return;
        }
        column--;
        if (column < buffer.length()) {
            buffer.setCharAt(column, (char) c);
        }
    }

    public void unget() {
        unget(0);
    }

    /** Get current buffer position. */
    public int tell() {
        return column;
    }

    /** Set the buffer position. */
    public void seek(int pos) {
        column = pos;
    }

    /** Set the contents of the buffer and reset the position. */
    protected void setBuffer(String line, int newColumn) {
        buffer = new StringBuilder(line);
        column = newColumn;
    }

    /** {@code std::invalid_argument} */
    public static class InvalidArgumentException extends RuntimeException {
        public InvalidArgumentException(String message) {
            super(message);
        }
    }
}
