/*
 * Copyright (c) 2026 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.sound.ctrmml;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;

import static java.lang.System.getLogger;


/**
 * Abstract input file format class.
 * <p>
 * The general purpose of this class (and derived) is to convert files to {@link Song} objects.
 * <p>
 * A binary file format might inherit directly from the Input class. Text-based formats such as
 * MML can use {@link LineInput} that provides helper functions for reading text lines.
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 2026-08-02 nsano initial version <br>
 * @see <a href="https://github.com/superctr/ctrmml">ctrmml</a> src/input.cpp
 */
public abstract class Input {

    private static final Logger logger = getLogger(Input.class.getName());

    private final Song song;
    private String filename = "";
    private FileResolver resolver = FileResolver.FILE_SYSTEM;

    protected Input(Song song) {
        this.song = song;
    }

    /** Sets the resolver used to open this and any included file. */
    public void setFileResolver(FileResolver resolver) {
        this.resolver = resolver != null ? resolver : FileResolver.FILE_SYSTEM;
    }

    public FileResolver getFileResolver() {
        return resolver;
    }

    /**
     * Open a file and parse it.
     * <p>
     * This adds the file path to the {@code include_path} tag, sets the filename and calls
     * {@link #parseFile}.
     *
     * @throws InputError in case of a read or parse error.
     */
    public void openFile(String fn) {
        setPath(fn);
        InputStream in = resolver.open(fn);
        if (in == null) {
            parseError("failed to open file");
        }
        try (in) {
            parseFile(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Parse an already opened stream.
     *
     * @param fn used for the {@code include_path} tag and for diagnostics only
     */
    public void parse(String fn, InputStream in) {
        setPath(fn);
        try {
            parseFile(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void setPath(String fn) {
        int pathBreak = Math.max(fn.lastIndexOf('/'), fn.lastIndexOf('\\'));
        if (pathBreak != -1) {
            song.addTag("include_path", fn.substring(0, pathBreak + 1));
        }
        this.filename = fn;
    }

    /** Get the target Song object. */
    protected Song getSong() {
        return song;
    }

    /** Get current filename. */
    protected String getFilename() {
        return filename;
    }

    /**
     * Get an InputRef.
     * <p>
     * This can be overridden by derived classes to support column/line numbers where this is
     * relevant.
     */
    protected InputRef getReference() {
        return new InputRef(filename);
    }

    /** Throw an {@link InputError}. */
    protected void parseError(String msg) {
        throw new InputError(getReference(), msg);
    }

    /** Raise a parse warning. */
    protected void parseWarning(String msg) {
        logger.log(Level.WARNING, "%s: %s%n%s".formatted(getReference(), msg, getReference().getLineContents()));
    }

    /** Used by derived classes to open and parse a file. */
    protected abstract void parseFile(InputStream in) throws IOException;
}
