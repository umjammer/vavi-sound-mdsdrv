/*
 * Copyright (c) 2026 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.sound.ctrmml;


/**
 * Reference to input data.
 * <p>
 * The reference includes the filename, and if applicable, line and column numbers as well as
 * the contents of the line.
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 2026-08-02 nsano initial version <br>
 * @see <a href="https://github.com/superctr/ctrmml">ctrmml</a> src/input.cpp
 */
public class InputRef {

    private final String filename;
    private final String lineContents;
    private final int line;
    private final int column;

    public InputRef(String filename, String lineContents, int line, int column) {
        this.filename = filename;
        this.lineContents = lineContents;
        this.line = line;
        this.column = column;
    }

    public InputRef(String filename) {
        this(filename, "", 0, 0);
    }

    public String getFilename() {
        return filename;
    }

    public int getLine() {
        return line;
    }

    public int getColumn() {
        return column;
    }

    public String getLineContents() {
        return lineContents;
    }

    @Override
    public String toString() {
        return "%s:%d:%d".formatted(filename, line + 1, column);
    }
}
