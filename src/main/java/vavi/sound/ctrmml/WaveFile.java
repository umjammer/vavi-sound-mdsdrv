/*
 * Copyright (c) 2026 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.sound.ctrmml;

import java.io.IOException;
import java.io.InputStream;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.ArrayList;
import java.util.List;

import static java.lang.System.getLogger;


/**
 * Wave file reader.
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 2026-08-02 nsano initial version <br>
 * @see <a href="https://github.com/superctr/ctrmml">ctrmml</a> src/wave.cpp
 */
class WaveFile {

    private static final Logger logger = getLogger(WaveFile.class.getName());

    private static final int CHUNK_FMT = 0x20746d66;  // 'fmt '
    private static final int CHUNK_DATA = 0x61746164; // 'data'
    private static final int CHUNK_SMPL = 0x6c706d73; // 'smpl'

    private int channels;
    private int stype;
    private int sbits;
    int srate;
    int slength;
    private int step;

    private boolean useSmplChunk;
    int transpose;
    int lstart;
    int lend;

    /** {@code data[channel][n]}, samples are signed 16 bit. */
    final List<List<Short>> data = new ArrayList<>();

    public WaveFile() {
    }

    public WaveFile(int channels, int rate, int bits) {
        this.channels = channels;
        this.sbits = bits;
        this.srate = rate;
        this.stype = 1;
        this.step = channels * (bits / 8);
    }

    /**
     * Reads a wave file.
     *
     * @return 0 on success, -1 on failure
     */
    public int read(String filename, FileResolver resolver) {
        byte[] filebuf;
        channels = 0;

        InputStream in = resolver.open(filename);
        if (in == null) {
            return -1;
        }
        try (in) {
            filebuf = in.readAllBytes();
        } catch (IOException e) {
            return -1;
        }
        int filesize = filebuf.length;
        int pos = 0;
        if (filesize < 13) {
            logger.log(Level.ERROR, "Malformed wav file '%s'".formatted(filename));
            return -1;
        }
        if (!fourCcAt(filebuf, 0, "RIFF")) {
            logger.log(Level.ERROR, "Riff header not found in '%s'".formatted(filename));
            return -1;
        }
        int wavesize = readLe32(filebuf, 4) + 8;
        pos += 8;
        if (filesize != wavesize) {
            logger.log(Level.WARNING, ("Warning: reported file size and actual file size do not match. "
                    + "Reported %d, actual %d").formatted(wavesize, filesize));
        }
        if (!fourCcAt(filebuf, pos, "WAVE")) {
            logger.log(Level.ERROR, "'%s' is not a WAVE format file.".formatted(filename));
            return -1;
        }
        pos += 4;
        while (pos < wavesize) {
            if (pos + 8 > filesize) {
                logger.log(Level.ERROR, "Illegal chunk size (%d, %d)".formatted(pos + 8, filesize));
                return -1;
            }
            int chunksize = readLe32(filebuf, pos + 4) + 8;
            if (pos + chunksize > filesize) {
                logger.log(Level.ERROR, "Illegal chunk size (%d, %d)".formatted(pos + chunksize + 8, filesize));
                return -1;
            }
            int ret = parseChunk(filebuf, pos);
            if (ret == 0) {
                logger.log(Level.ERROR, "Failed to parse chunk %c%c%c%c."
                        .formatted((char) filebuf[pos], (char) filebuf[pos + 1],
                                (char) filebuf[pos + 2], (char) filebuf[pos + 3]));
                return -1;
            }
            pos += chunksize;

            if ((pos & 1) != 0) {
                pos++;
            }
        }
        return 0;
    }

    /** @return the chunk size, 0 on failure */
    private int parseChunk(byte[] fdata, int base) {
        int chunkid = readLe32(fdata, base);
        int chunksize = readLe32(fdata, base + 4);
        switch (chunkid) {
        case CHUNK_FMT -> {
            if (chunksize < 0x10) {
                return 0;
            }
            stype = readLe16(fdata, base + 0x08);
            channels = readLe16(fdata, base + 0x0a);
            sbits = readLe16(fdata, base + 0x16);
            step = (sbits * channels) / 8;
            srate = readLe32(fdata, base + 0x0c);
            slength = 0;
            if (stype != 1 || channels > 2 || step == 0) {
                logger.log(Level.ERROR, "unsupported format");
                return 0;
            }
            while (data.size() < channels) {
                data.add(new ArrayList<>());
            }
            while (data.size() > channels) {
                data.removeLast();
            }
        }
        case CHUNK_DATA -> {
            if (step == 0) {
                return 0;
            }
            int d = base + 8;
            int end = base + 8 + chunksize;
            while (d < end) {
                for (int ch = 0; ch < channels; ch++) {
                    if (sbits == 8) {
                        data.get(ch).add((short) (((fdata[d] & 0xff) ^ 0x80) << 8));
                        d++;
                    } else if (sbits == 16) {
                        data.get(ch).add((short) readLe16(fdata, d));
                        d += 2;
                    }
                }
            }
            slength = data.getFirst().size();
            lstart = 0;
            lend = 0;
        }
        case CHUNK_SMPL -> {
            useSmplChunk = true;
            if (chunksize >= 0x10) {
                transpose = readLe32(fdata, base + 0x14);
                if (transpose == 0) {
                    transpose -= 60;
                }
            }
            if (chunksize >= 0x2c && readLe32(fdata, base + 0x24) != 0) {
                lstart = readLe32(fdata, base + 0x2c + 8);
                lend = readLe32(fdata, base + 0x2c + 12) + 1;
                slength = lend;
            }
        }
        default -> {
        }
        }
        return chunksize;
    }

    private static boolean fourCcAt(byte[] b, int pos, String code) {
        for (int i = 0; i < 4; i++) {
            if (pos + i >= b.length || (b[pos + i] & 0xff) != code.charAt(i)) {
                return false;
            }
        }
        return true;
    }

    private static int readLe16(byte[] b, int pos) {
        return (b[pos] & 0xff) | ((b[pos + 1] & 0xff) << 8);
    }

    private static int readLe32(byte[] b, int pos) {
        return (b[pos] & 0xff) | ((b[pos + 1] & 0xff) << 8) | ((b[pos + 2] & 0xff) << 16) | ((b[pos + 3] & 0xff) << 24);
    }
}
