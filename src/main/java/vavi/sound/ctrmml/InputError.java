/*
 * Copyright (c) 2026 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.sound.ctrmml;


/**
 * Exception class for input file errors.
 * <p>
 * This exception is thrown whenever an error occurs while reading or parsing an Input file.
 * It should not be thrown directly, but rather by using {@link Input#parseError(String)}.
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 2026-08-02 nsano initial version <br>
 * @see <a href="https://github.com/superctr/ctrmml">ctrmml</a> src/input.cpp
 */
public class InputError extends RuntimeException {

    private final InputRef reference;

    /**
     * @param ref a reference pointing at the error. If this is null, a generic error message
     *            is generated.
     */
    public InputError(InputRef ref, String message) {
        super(ref == null ? message
                : "%s:%d:%d: %s".formatted(ref.getFilename(), ref.getLine() + 1, ref.getColumn() + 1, message));
        this.reference = ref;
    }

    public InputRef getReference() {
        return reference;
    }
}
