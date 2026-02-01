/*
 * Copyright (c) 2026 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.sound.mdsdrv;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.HashMap;
import java.util.Map;

import static java.lang.System.getLogger;

/**
 * RiffMdsParser - Utility class for parsing RIFF MDS files.
 * Consolidates RIFF parsing logic that was duplicated in MdsDriver and MdsPlayerTest.
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 2026-02-01 nsano initial version <br>
 */
public final class RiffMdsParser {

    private static final Logger logger = getLogger(RiffMdsParser.class.getName());

    /** Result of parsing a RIFF MDS file */
    public static class ParseResult {
        /** The sequence data (extracted from "seq " chunk, or full data if not RIFF) */
        public final byte[] seqData;
        /** The PCM data (from "pcmd" chunk, may be null) */
        public final byte[] pcmData;
        /** Global data blocks indexed by ID (from "glob" subchunks) */
        public final Map<Integer, byte[]> globals;
        /** PCM headers indexed by ID (from "pcmh" subchunks) */
        public final Map<Integer, PcmHeader> pcmHeaders;
        /** Whether the input was in RIFF format */
        public final boolean isRiff;
        /** Offset of seq data in original file (for debugging) */
        public final int seqOffset;

        public ParseResult(byte[] seqData, byte[] pcmData, 
                Map<Integer, byte[]> globals, Map<Integer, PcmHeader> pcmHeaders,
                boolean isRiff, int seqOffset) {
            this.seqData = seqData;
            this.pcmData = pcmData;
            this.globals = globals;
            this.pcmHeaders = pcmHeaders;
            this.isRiff = isRiff;
            this.seqOffset = seqOffset;
        }
    }

    /** PCM header data from "pcmh" subchunk */
    public static class PcmHeader {
        public final int id;
        public final int position;
        public final int start;
        public final int size;
        public final int loopStart;
        public final int loopEnd;
        public final int rate;
        public final int transpose;
        public final int flags;

        public PcmHeader(int id, int position, int start, int size,
                int loopStart, int loopEnd, int rate, int transpose, int flags) {
            this.id = id;
            this.position = position;
            this.start = start;
            this.size = size;
            this.loopStart = loopStart;
            this.loopEnd = loopEnd;
            this.rate = rate;
            this.transpose = transpose;
            this.flags = flags;
        }
    }

    private RiffMdsParser() {
        // Utility class
    }

    /**
     * Check if data is in RIFF MDS format.
     */
    public static boolean isRiffMds(byte[] data) {
        return data.length >= 12 &&
                data[0] == 'R' && data[1] == 'I' && data[2] == 'F' && data[3] == 'F' &&
                data[8] == 'M' && data[9] == 'D' && data[10] == 'S' && data[11] == '0';
    }

    /**
     * Parse RIFF MDS data.
     * If the data is not in RIFF format, returns it as-is in seqData.
     */
    public static ParseResult parse(byte[] data) {
        if (!isRiffMds(data)) {
            // Not RIFF format, treat entire data as sequence data
            return new ParseResult(data, null, new HashMap<>(), new HashMap<>(), false, 0);
        }

        byte[] seqData = null;
        byte[] pcmData = null;
        Map<Integer, byte[]> globals = new HashMap<>();
        Map<Integer, PcmHeader> pcmHeaders = new HashMap<>();
        int seqOffset = 0;

        int p = 12; // Skip RIFF header
        while (p < data.length - 8) {
            String chunkId = readChunkId(data, p);
            int size = readLittleEndian32(data, p + 4);

            logger.log(Level.DEBUG, "RIFF chunk '" + chunkId + "' size=" + size + " at offset " + p);

            switch (chunkId) {
                case "ver ":
                    // Version chunk - skip for now
                    break;
                case "seq ":
                    // Sequence data chunk
                    seqOffset = p + 8;
                    seqData = new byte[size];
                    System.arraycopy(data, p + 8, seqData, 0, Math.min(size, data.length - p - 8));
                    break;
                case "LIST":
                    // List chunk - parse subchunks
                    if (p + 12 <= data.length) {
                        String listType = readChunkId(data, p + 8);
                        if ("dblk".equals(listType)) {
                            parseDataBlockList(data, p + 12, size - 4, globals, pcmHeaders);
                        }
                    }
                    break;
                case "pcmd":
                    // PCM sample data chunk
                    if (p + 8 + size <= data.length) {
                        pcmData = new byte[size];
                        System.arraycopy(data, p + 8, pcmData, 0, size);
                    }
                    break;
                default:
                    // Unknown chunk, skip
                    break;
            }

            // Pad byte if size is odd (RIFF standard)
            if ((size & 1) != 0) size++;
            p += 8 + size;
        }

        // If no seq chunk found, use remaining data from a common offset
        if (seqData == null) {
            seqData = data;
        }

        return new ParseResult(seqData, pcmData, globals, pcmHeaders, true, seqOffset);
    }

    /**
     * Parse data block list (dblk) chunk contents.
     */
    private static void parseDataBlockList(byte[] data, int start, int length,
            Map<Integer, byte[]> globals, Map<Integer, PcmHeader> pcmHeaders) {
        int p = start;
        int end = start + length;

        while (p < end - 8 && p < data.length - 8) {
            String subchunkId = readChunkId(data, p);
            int size = readLittleEndian32(data, p + 4);

            if (p + 8 + size > data.length) break;

            switch (subchunkId) {
                case "glob":
                    // Global data block
                    if (size >= 4) {
                        int id = readLittleEndian32(data, p + 8);
                        int dataSize = size - 4;
                        byte[] blockData = new byte[dataSize];
                        System.arraycopy(data, p + 12, blockData, 0, dataSize);
                        globals.put(id, blockData);
                    }
                    break;
                case "pcmh":
                    // PCM sample header
                    if (size >= 36) {
                        int id = readLittleEndian32(data, p + 8);
                        int position = readLittleEndian32(data, p + 12);
                        int hdrStart = readLittleEndian32(data, p + 16);
                        int hdrSize = readLittleEndian32(data, p + 20);
                        int loopStart = readLittleEndian32(data, p + 24);
                        int loopEnd = readLittleEndian32(data, p + 28);
                        int rate = readLittleEndian32(data, p + 32);
                        int transpose = (size >= 40) ? readLittleEndian32(data, p + 36) : 0;
                        int flags = (size >= 44) ? readLittleEndian32(data, p + 40) : 0;
                        pcmHeaders.put(id, new PcmHeader(id, position, hdrStart, hdrSize,
                                loopStart, loopEnd, rate, transpose, flags));
                    }
                    break;
                default:
                    // Unknown subchunk
                    break;
            }

            // Pad byte if size is odd
            if ((size & 1) != 0) size++;
            p += 8 + size;
        }
    }

    private static String readChunkId(byte[] data, int offset) {
        return "" + (char) data[offset] + (char) data[offset + 1] +
                (char) data[offset + 2] + (char) data[offset + 3];
    }

    private static int readLittleEndian32(byte[] data, int offset) {
        return (data[offset] & 0xFF) |
                ((data[offset + 1] & 0xFF) << 8) |
                ((data[offset + 2] & 0xFF) << 16) |
                ((data[offset + 3] & 0xFF) << 24);
    }
}
