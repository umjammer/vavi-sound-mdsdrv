/*
 * Copyright (c) 2026 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.sound.ctrmml;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static java.lang.System.getLogger;


/**
 * Base wave rom bank.
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 2026-08-02 nsano initial version <br>
 * @see <a href="https://github.com/superctr/ctrmml">ctrmml</a> src/wave.cpp
 */
public class WaveBank {

    private static final Logger logger = getLogger(WaveBank.class.getName());

    /** {@code sscanf("rate = %u")} */
    private static final Pattern RATE = Pattern.compile("^rate\\s*=\\s*([+-]?\\d+)");
    /** {@code sscanf("offset = %u")} */
    private static final Pattern OFFSET = Pattern.compile("^offset\\s*=\\s*([+-]?\\d+)");

    protected static final int NO_FIT = -1;

    /** Aggregate sample header class. */
    public static class Sample {
        public int position;
        public int start;
        public int size;
        public int loopStart;
        public int loopEnd;
        public int rate;
        public int transpose;
        public int flags;

        public Sample() {
        }

        public Sample(int position, int start, int size, int loopStart, int loopEnd, int rate,
                      int transpose, int flags) {
            this.position = position;
            this.start = start;
            this.size = size;
            this.loopStart = loopStart;
            this.loopEnd = loopEnd;
            this.rate = rate;
            this.transpose = transpose;
            this.flags = flags;
        }

        /**
         * Fill a sample header with values from a byte vector, as created by {@link #toBytes()}.
         *
         * @throws IndexOutOfBoundsException Input too small
         */
        public void fromBytes(ByteVector input) {
            position = input.readLe32(0);
            start = input.readLe32(4);
            size = input.readLe32(8);
            loopStart = input.readLe32(12);
            loopEnd = input.readLe32(16);
            rate = input.readLe32(20);
            transpose = input.readLe32(24);
            flags = input.readLe32(28);
        }

        /** Return a sample header as a byte vector. */
        public ByteVector toBytes() {
            ByteVector output = new ByteVector();
            output.writeLe32(0, position);
            output.writeLe32(4, start);
            output.writeLe32(8, size);
            output.writeLe32(12, loopStart);
            output.writeLe32(16, loopEnd);
            output.writeLe32(20, rate);
            output.writeLe32(24, transpose);
            output.writeLe32(28, flags); // Reserved.
            return output;
        }

        /** The original compares samples by their serialized form. */
        public boolean sameAs(Sample other) {
            return toBytes().equals(other.toBytes());
        }

        public Sample copy() {
            return new Sample(position, start, size, loopStart, loopEnd, rate, transpose, flags);
        }
    }

    protected static class Gap {
        long start;
        long end;

        Gap(long start, long end) {
            this.start = start;
            this.end = end;
        }
    }

    protected final long maxSize;
    protected long currentSize;
    protected long bankSize;

    protected List<String> includePaths = new ArrayList<>(List.of(""));
    protected final byte[] romData;
    protected final List<Gap> gaps = new ArrayList<>();
    protected final List<Sample> samples = new ArrayList<>();
    protected String errorMessage = "";
    protected FileResolver resolver = FileResolver.FILE_SYSTEM;

    public WaveBank(long maxSize, long bankSize) {
        this.maxSize = maxSize;
        this.romData = new byte[(int) maxSize];
        this.bankSize = bankSize != 0 ? bankSize : maxSize;
    }

    public WaveBank(long maxSize) {
        this(maxSize, 0);
    }

    /** Sets the resolver used to read the sample files. */
    public void setFileResolver(FileResolver resolver) {
        this.resolver = resolver != null ? resolver : FileResolver.FILE_SYSTEM;
    }

    /** Set a list of include paths to check when reading samples from a Tag. */
    public void setIncludePaths(List<String> tag) {
        this.includePaths = tag;
    }

    /** Convert and add sample to the waverom. */
    public int addSample(List<String> tag) {
        int status = -1;
        if (tag.isEmpty()) {
            errorMessage = "Incomplete sample definition";
            throw new InputError(null, errorMessage);
        }
        String filename = tag.getFirst();
        WaveFile wf = new WaveFile();
        for (String path : includePaths) {
            status = wf.read(path + filename, resolver);
            if (status == 0) {
                break;
            }
        }
        if (status != 0) {
            errorMessage = filename + " not found";
            throw new InputError(null, errorMessage);
        }

        // convert sample
        ByteVector sample = encodeSample("", wf.data.getFirst());
        Sample header = new Sample(0, 0, wf.slength, wf.lstart, wf.lend, wf.srate, wf.transpose, 0);

        // Allow overriding the sample rate and setting start offset
        for (int i = 1; i < tag.size(); i++) {
            Matcher m = RATE.matcher(tag.get(i));
            if (m.find()) {
                header.rate = (int) Long.parseLong(m.group(1));
                continue;
            }
            m = OFFSET.matcher(tag.get(i));
            if (m.find()) {
                int param = (int) Long.parseLong(m.group(1));
                if (Integer.compareUnsigned(param, header.size) > 0) {
                    throw new InputError(null, "Sample offset cannot be greater than total length");
                }
                header.start += param;
                header.size -= param;
            }
        }

        return addSample(header, sample);
    }

    /** Add sample to the waverom in raw format. */
    public int addSample(Sample header, ByteVector sample) {
        header = header.copy();

        // Find duplicates of sample data and selected header parameters if needed
        int duplicate = findDuplicate(header, sample);

        if (duplicate != -1) {
            header.position = samples.get(duplicate).position;
            for (int i = 0; i < samples.size(); i++) {
                if (samples.get(i).sameAs(header)) {
                    // Header is similar to an existing one, we reuse it
                    return i;
                }
            }
            // Create a new header, while using the same sample data
            samples.add(header);
            return samples.size() - 1;
        } else {
            // Create a new entry.
            long[] startPosOut = new long[1];
            startPosOut[0] = NO_FIT;
            long start = currentSize; // Proposed start position
            long startPos; // Aligned start position
            // Check if the sample fits in a gap.
            int gapId = findGap(header, startPosOut);
            if (gapId != NO_FIT) {
                startPos = startPosOut[0];
                start = gaps.get(gapId).start;
                gaps.get(gapId).start = startPos + header.size;
            } else {
                // Append sample to the end
                startPos = fitSample(header, start, maxSize);
            }
            // Check if sample fits in ROM
            if (startPos == NO_FIT) {
                errorMessage = "Sample does not fit in remaining ROM space (%d bytes remaining, sample size is %d)"
                        .formatted(maxSize - currentSize, sample.size());
                throw new InputError(null, errorMessage);
            }
            // Add a new gap if needed
            if (startPos > start) {
                gaps.add(new Gap(start, startPos));
            }
            // Move the end position if needed
            if (startPos >= currentSize) {
                currentSize = startPos + header.size;
            }

            logger.log(Level.DEBUG, "Append sample %d to ROM at %08x (size %08x)"
                    .formatted(samples.size(), startPos, header.size));
            System.arraycopy(sample.toByteArray(), 0, romData, (int) startPos, header.size);
            header.position = (int) startPos;
            samples.add(header);
            return samples.size() - 1;
        }
    }

    /** Get sample headers. */
    public List<Sample> getSampleHeaders() {
        return samples;
    }

    /** Get the sample ROM data. */
    public byte[] getRomData() {
        return romData;
    }

    /** Get the number of unused allocated bytes in the WaveBank. */
    public long getFreeBytes() {
        return maxSize - currentSize;
    }

    /** Get the total size of alignment gaps. */
    public long getTotalGap() {
        long gapSize = 0;
        for (Gap gap : gaps) {
            gapSize += gap.end - gap.start;
        }
        return gapSize;
    }

    /** Get the size of the largest gap, 0 if there are no gaps. */
    public long getLargestGap() {
        long largestGap = 0;
        for (Gap gap : gaps) {
            long gapSize = gap.end - gap.start;
            if (gapSize > largestGap) {
                largestGap = gapSize;
            }
        }
        return largestGap;
    }

    /** Get error message. */
    public String getError() {
        return errorMessage;
    }

    /**
     * Check if the sample fits in an existing alignment gap.
     * <p>
     * If the sample cannot fit in any gap, return {@link #NO_FIT}. Otherwise, return the index
     * of the smallest gap that fits the sample.
     *
     * @param gapStart out parameter, set with the aligned start position of the gap
     */
    protected int findGap(Sample header, long[] gapStart) {
        int bestGap = NO_FIT;
        if (!gaps.isEmpty()) {
            // Look for the smallest gap that fits our sample
            long bestGapSize = maxSize;
            for (int i = 0; i < gaps.size(); i++) {
                long gapSize = gaps.get(i).end - gaps.get(i).start;
                long startPos = fitSample(header, gaps.get(i).start, gaps.get(i).end);
                if (startPos != NO_FIT && gapSize < bestGapSize) {
                    bestGap = i;
                    bestGapSize = gapSize;
                    gapStart[0] = startPos;
                }
            }
        }
        return bestGap;
    }

    /** Encode the sample, converting it from 16-bit data to 8-bit. */
    protected ByteVector encodeSample(String encodingType, List<Short> input) {
        // default encoder, simply convert 16-bit to 8-bit unsigned
        ByteVector output = new ByteVector();
        for (short i : input) {
            output.add((i >> 8) ^ 0x80);
        }
        return output;
    }

    /**
     * Returns the next possible aligned start address for the rom.
     * <p>
     * Given a sample header, a proposed start address and end address, return the appropriate
     * start address of the sample. If the sample cannot fit within the boundaries, return
     * {@link #NO_FIT}.
     */
    protected long fitSample(Sample header, long start, long end) {
        long sampleEnd = start + header.size;
        long startBank = start / bankSize;
        long endBank = sampleEnd / bankSize;
        // Adjust start address for bank crossing.
        // Smarter method if sample is only to be played at the same sample rate.
        // Used by default if the sample is too big to fit in a Z80 bank anyway.
        if (startBank != endBank && header.size > bankSize) {
            start = (start + 0x1f) & 0xffff_ffe0L;
        } else if (startBank != endBank && (start % bankSize) != 0) {
            // Alternate behavior for MDSDRV. More ROM space but will handle sample rate switches.
            start = (startBank + 1) * bankSize;
        }
        // Crossed end boundary?
        if ((start + header.size) > end) {
            return NO_FIT;
        }
        return start;
    }

    /**
     * Look for duplicates in the sample ROM.
     *
     * @return the index to the duplicate wave entry, or -1
     */
    protected int findDuplicate(Sample header, ByteVector sample) {
        int id = 0;
        for (Sample i : samples) {
            // The reason for the loop start check is that some sound chips (like C352)
            // require that the looping part of the sample fit in the same bank.
            if (i.position + sample.size() <= romData.length
                    && Integer.compareUnsigned(i.loopStart, header.loopStart) <= 0
                    && regionEquals(sample, i.position)) {
                return id;
            }
            id++;
        }
        return -1;
    }

    private boolean regionEquals(ByteVector sample, int position) {
        for (int i = 0; i < sample.size(); i++) {
            if ((romData[position + i] & 0xff) != sample.get(i)) {
                return false;
            }
        }
        return true;
    }
}
