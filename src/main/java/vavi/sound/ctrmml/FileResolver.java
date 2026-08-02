/*
 * Copyright (c) 2026 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.sound.ctrmml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.function.Function;


/**
 * Resolves the auxiliary files (included MMLs, PCM samples) referenced by a song.
 * <p>
 * The original ctrmml opens those directly with {@code std::ifstream}; this indirection lets
 * the {@code musicDriverInterface.ICompiler} implementation serve them from its own
 * {@code appendFileReaderCallback} instead.
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 2026-08-02 nsano initial version <br>
 */
@FunctionalInterface
public interface FileResolver {

    /**
     * @param filename the name as written in the MML source, possibly prefixed by an include path
     * @return null when the file does not exist
     */
    InputStream open(String filename);

    /** Reads from the local file system. */
    FileResolver FILE_SYSTEM = filename -> {
        try {
            Path path = Path.of(filename);
            return Files.exists(path) ? Files.newInputStream(path) : null;
        } catch (InvalidPathException | IOException e) {
            return null;
        }
    };

    /** Adapts the {@code appendFileReaderCallback} of {@code musicDriverInterface.ICompiler}. */
    static FileResolver of(Function<String, InputStream> callback) {
        if (callback == null) {
            return FILE_SYSTEM;
        }
        return filename -> {
            try {
                return callback.apply(filename);
            } catch (RuntimeException e) {
                return null;
            }
        };
    }
}
