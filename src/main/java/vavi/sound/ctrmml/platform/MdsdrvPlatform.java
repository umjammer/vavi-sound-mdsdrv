/*
 * Copyright (c) 2026 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.sound.ctrmml.platform;

import java.util.List;

import vavi.sound.ctrmml.Platform;
import vavi.sound.ctrmml.Song;


/**
 * The MDSDRV platform.
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 2026-08-02 nsano initial version <br>
 * @see <a href="https://github.com/superctr/ctrmml">ctrmml</a> src/platform/mdsdrv.cpp
 */
public class MdsdrvPlatform extends Platform {

    /** Current sequence version. */
    public static final int SEQ_VERSION_MAJOR = 0;
    /** Current sequence version. */
    public static final int SEQ_VERSION_MINOR = 6;

    /** Minimum compatible sequence version. */
    public static final int MIN_SEQ_VERSION_MAJOR = 0;
    /** Minimum compatible sequence version. */
    public static final int MIN_SEQ_VERSION_MINOR = 2;

    /** PCM sampling rate. */
    public static final int PCM_RATE = 17500;

    /**
     * The export formats. The original also offers {@code vgm} as format 0; the VGM writer is
     * not part of this port, so only the MDS song data is available.
     */
    private static final List<Format> FORMATS = List.of(new Format("mds", "MDS song data"));

    private final int pcmMode;

    public MdsdrvPlatform(int pcmMode) {
        this.pcmMode = pcmMode;
    }

    public int getPcmMode() {
        return pcmMode;
    }

    @Override
    public List<Format> getExportFormats() {
        return FORMATS;
    }

    @Override
    public byte[] getExportData(Song song, int format) {
        if (format == 0) {
            return new MdsdrvConverter(song).getMds().toBytes().toByteArray();
        } else {
            throw new IllegalStateException("no such exporter");
        }
    }
}
