/*
 * Copyright (c) 2026 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.sound.ctrmml;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;


/**
 * Abstract class for text line-based input formats (such as MML).
 * <p>
 * Reads the input files, one line at a time, parsing them using the abstract method
 * {@link #parseLine()}.
 * <p>
 * To help with parsing, a C stdio-style interface to lines of texts is provided (see
 * {@link LineBuffer}). Because data is stored in an internal buffer and the position is kept
 * track of, a call to {@link #getReference()} (and thus {@link #parseError}) will create an
 * {@link InputRef} with the correct line number and column.
 * <p>
 * The original derives from both {@code Input} and {@code Line_Buffer}; here the line buffer
 * is a member with delegating accessors.
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 2026-08-02 nsano initial version <br>
 * @see <a href="https://github.com/superctr/ctrmml">ctrmml</a> src/input.cpp
 */
public abstract class LineInput extends Input {

    private final LineBuffer buffer = new LineBuffer("", 0);

    private int line;

    LineInput(Song song) {
        super(song);
    }

    /** Open file and parse lines. */
    @Override
    protected void parseFile(InputStream in) throws IOException {
        // the MML dialect is byte oriented; ISO-8859-1 keeps the source bytes intact
        BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.ISO_8859_1));
        buffer.setBuffer("", 0);
        line = 0;
        String str;
        while ((str = reader.readLine()) != null) {
            readLine(str, -1);
            line++;
        }
    }

    @Override
    InputRef getReference() {
        return new InputRef(getFilename(), buffer.buffer.toString(), line, buffer.column);
    }

    /** Read a single input line and parse it, optionally also setting the line number. */
    private void readLine(String inputLine, int lineNumber) {
        if (lineNumber >= 0) {
            line = lineNumber;
        }
        buffer.setBuffer(inputLine, 0);
        parseLine();
    }

    public void readLine(String inputLine) {
        readLine(inputLine, -1);
    }

    /** Used by derived classes to read the input lines. */
    protected abstract void parseLine();

    // Line_Buffer delegates

    int get() {
        return buffer.get();
    }

    int getToken() {
        return buffer.getToken();
    }

    int getNum() {
        return buffer.getNum();
    }

    String getLine() {
        return buffer.getLine();
    }

    void unget(int c) {
        buffer.unget(c);
    }

    void unget() {
        buffer.unget(0);
    }

    int tell() {
        return buffer.tell();
    }

    void seek(int pos) {
        buffer.seek(pos);
    }
}
