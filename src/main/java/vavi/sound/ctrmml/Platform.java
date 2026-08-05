/*
 * Copyright (c) 2026 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.sound.ctrmml;

import java.util.List;


/**
 * Platform base class.
 * <p>
 * This contains platform-specific functions that can convert or create objects that work with
 * Songs.
 * <p>
 * Note that the VGM export path of ctrmml ({@code src/vgm.cpp}, {@code src/driver.cpp} and
 * {@code src/platform/md.cpp}) is not part of this port, so {@code "vgm"} is not offered as an
 * export format.
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 2026-08-02 nsano initial version <br>
 * @see <a href="https://github.com/superctr/ctrmml">ctrmml</a> src/song.cpp
 */
public abstract class Platform {

    /** An export format: its extension and its description. */
    public record Format(String extension, String description) {
    }

    /** @return the list of formats accepted by {@link #getExportData} */
    public abstract List<Format> getExportFormats();

    /**
     * @param format an index into {@link #getExportFormats()}
     * @return the exported file contents
     */
    public abstract byte[] getExportData(Song song, int format);
}
