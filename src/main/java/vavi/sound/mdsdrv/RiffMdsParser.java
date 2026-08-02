/*
 * Copyright (c) 2026 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.sound.mdsdrv;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
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
        /**
         * The song metadata (from the "tag " chunk), keyed by the MML tag name without its
         * {@code #}, in the order the chunk lists them. Empty when the file carries no such
         * chunk, which is the case for everything MDSDRV's own tools build.
         */
        public final Map<String, String> tags;

        public ParseResult(byte[] seqData, byte[] pcmData,
                Map<Integer, byte[]> globals, Map<Integer, PcmHeader> pcmHeaders,
                boolean isRiff, int seqOffset) {
            this(seqData, pcmData, globals, pcmHeaders, isRiff, seqOffset, new LinkedHashMap<>());
        }

        public ParseResult(byte[] seqData, byte[] pcmData,
                Map<Integer, byte[]> globals, Map<Integer, PcmHeader> pcmHeaders,
                boolean isRiff, int seqOffset, Map<String, String> tags) {
            this.seqData = seqData;
            this.pcmData = pcmData;
            this.globals = globals;
            this.pcmHeaders = pcmHeaders;
            this.isRiff = isRiff;
            this.seqOffset = seqOffset;
            this.tags = tags;
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
        Map<String, String> tags = new LinkedHashMap<>();
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
                case "tag ":
                    // Song metadata chunk
                    if (p + 8 + size <= data.length) {
                        parseTags(data, p + 8, size, tags);
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

        return new ParseResult(seqData, pcmData, globals, pcmHeaders, true, seqOffset, tags);
    }

    /**
     * Parse the "tag " chunk: {@code key NUL value NUL} pairs. A key that appears more than once
     * (a tag the MML gave several values) keeps every value, joined by newlines.
     */
    private static void parseTags(byte[] data, int start, int length, Map<String, String> tags) {
        int end = start + length;
        int p = start;
        while (p < end) {
            int keyEnd = indexOfNul(data, p, end);
            if (keyEnd < 0) break;
            int valueEnd = indexOfNul(data, keyEnd + 1, end);
            if (valueEnd < 0) break;
            String key = decode(data, p, keyEnd);
            String value = decode(data, keyEnd + 1, valueEnd);
            tags.merge(key, value, (a, b) -> a + "\n" + b);
            p = valueEnd + 1;
        }
    }

    private static int indexOfNul(byte[] data, int from, int end) {
        for (int i = from; i < end; i++) {
            if (data[i] == 0) return i;
        }
        return -1;
    }

    /** The system property naming the encoding to read a non UTF-8 "tag " chunk with. */
    public static final String ENCODING_KEY = "mdsdrv.encoding";

    /** What {@link #ENCODING_KEY} defaults to: the other encoding MML is commonly written in. */
    public static final String DEFAULT_ENCODING = "MS932";

    /**
     * The chunk holds the bytes of the MML source, whose encoding it does not record. Anything
     * that is valid UTF-8 is taken as UTF-8 - plain ASCII included - and the rest is read with
     * the encoding {@code -Dmdsdrv.encoding} names, {@value #DEFAULT_ENCODING} by default.
     */
    private static String decode(byte[] data, int from, int to) {
        byte[] bytes = Arrays.copyOfRange(data, from, to);
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            return new String(bytes, fallbackEncoding());
        }
    }

    /**
     * The encoding {@link #ENCODING_KEY} names, read afresh each time so that setting the
     * property takes effect whenever it is set. An unknown name falls back to the default rather
     * than failing the read: a title is not worth losing a song over.
     */
    private static Charset fallbackEncoding() {
        String name = System.getProperty(ENCODING_KEY, DEFAULT_ENCODING);
        try {
            return Charset.forName(name);
        } catch (IllegalArgumentException e) { // illegal or unsupported name
            logger.log(Level.WARNING, ENCODING_KEY + ": no such charset, reading as "
                    + DEFAULT_ENCODING + ": " + name);
            return Charset.forName(DEFAULT_ENCODING);
        }
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
