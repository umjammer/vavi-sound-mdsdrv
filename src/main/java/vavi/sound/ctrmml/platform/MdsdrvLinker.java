/*
 * Copyright (c) 2026 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.sound.ctrmml.platform;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.TreeMap;

import vavi.sound.ctrmml.ByteVector;
import vavi.sound.ctrmml.CType;
import vavi.sound.ctrmml.InputError;
import vavi.sound.ctrmml.Riff;
import vavi.sound.ctrmml.WaveBank;

import static java.lang.System.getLogger;


/**
 * MDSDRV data linker: combines any number of MDS songs into {@code mdsseq.bin} and
 * {@code mdspcm.bin}.
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 2026-08-02 nsano initial version <br>
 * @see <a href="https://github.com/superctr/ctrmml">ctrmml</a> src/platform/mdsdrv.cpp
 */
public class MdsdrvLinker {

    private static final Logger logger = getLogger(MdsdrvLinker.class.getName());

    /** A sequence and the offsets in it that have to be patched with the final data addresses. */
    private static class SeqData {
        final String filename;
        final ByteVector data;
        final List<int[]> patchTable;

        SeqData(String filename, ByteVector data, List<int[]> patchTable) {
            this.filename = filename;
            this.data = data;
            this.patchTable = patchTable;
        }
    }

    private final List<ByteVector> dataBank = new ArrayList<>();
    private final List<Integer> dataOffset = new ArrayList<>();
    /** {@code std::map}: the group order is significant for the song IDs. */
    private final Map<String, List<SeqData>> seqBank = new TreeMap<>();
    private final WaveBank waveRom = new WaveBank(0x3f8000, 0x8000);

    public MdsdrvLinker() {
    }

    /** Add a song (converted to MDS RIFF format). */
    public void addSong(Riff mds, String filename) {
        ByteVector ver = new ByteVector(2);
        ByteVector pcmd = new ByteVector();
        ByteVector seq = new ByteVector();
        ByteVector group = new ByteVector();
        List<int[]> patchTable = new ArrayList<>();
        Riff dblk = new Riff(0);
        mds.rewind();
        if (mds.getType() != Riff.TYPE_RIFF || mds.getId() != Riff.fourCc("MDS0")) {
            throw new InputError(null, "This is not a valid .MDS version 0 file");
        }

        while (!mds.atEnd()) {
            Riff chunk = new Riff(mds.getChunk());
            if (chunk.getType() == Riff.fourCc("seq ")) {
                seq = chunk.getData();
            } else if (chunk.getType() == Riff.fourCc("pcmd")) {
                pcmd = chunk.getData();
            } else if (chunk.getType() == Riff.TYPE_LIST && chunk.getId() == Riff.fourCc("dblk")) {
                dblk = chunk;
            } else if (chunk.getType() == Riff.fourCc("ver ")) {
                ver = chunk.getData();
            } else if (chunk.getType() == Riff.fourCc("grp ")) {
                group = chunk.getData();
            }
        }

        checkVersion(ver.get(0), ver.get(1));

        if (seq.isEmpty() || dblk.getType() != Riff.TYPE_LIST) {
            throw new InputError(null, ".MDS data is malformed");
        }

        int seqSdata = (seq.get(0) << 8) | seq.get(1);
        dblk.rewind();
        while (!dblk.atEnd()) {
            Riff chunk = new Riff(dblk.getChunk());
            if (chunk.getType() == Riff.fourCc("glob")) {
                // Envelope data
                ByteVector data = chunk.getData();
                int id = data.readLe32(0);
                int addr = (seqSdata + (id & 0x7fff_ffff) * 2) & 0xffff;
                int offset = addUniqueData(new ByteVector(data, 4, data.size())) & 0xffff;
                logger.log(Level.DEBUG, "replace seq+%04x with %04x (Envelope)".formatted(addr, offset));
                if ((id & 0x8000_0000) != 0) {
                    patchTable.add(new int[] {addr, offset | 0x8000});
                } else {
                    patchTable.add(new int[] {addr, offset});
                }
            } else if (chunk.getType() == Riff.fourCc("pcmh")) {
                // PCM header
                ByteVector data = chunk.getData();
                int addr = (seqSdata + data.readLe32(0) * 2) & 0xffff;
                WaveBank.Sample header = new WaveBank.Sample();
                header.fromBytes(new ByteVector(data, 4, data.size()));
                int begin = header.position;
                int end = header.position + header.size;
                header.position = 0;
                int offset = waveRom.addSample(header, new ByteVector(pcmd, begin, end));

                // Get new PCM header
                header = waveRom.getSampleHeaders().get(offset);
                ByteVector hdata = getPcmHeader(header);
                offset = addUniqueData(hdata) & 0xffff;
                logger.log(Level.DEBUG, "replace seq+%04x with %04x (PCM header)".formatted(addr, offset));
                patchTable.add(new int[] {addr, offset});
            }
        }
        String groupStr = keyifyString(toString(group));
        if (groupStr.isEmpty()) { // set default group name
            groupStr = "BGM";
        }
        seqBank.computeIfAbsent(groupStr, k -> new ArrayList<>()).add(new SeqData(filename, seq, patchTable));
    }

    public void addSong(Riff mds) {
        addSong(mds, "");
    }

    private ByteVector getPcmHeader(WaveBank.Sample sample) {
        ByteVector output = new ByteVector();
        float pitch = (float) (sample.rate / (MdsdrvPlatform.PCM_RATE / 8.0));
        int cp = (int) (pitch + 0.5f) & 0xff;
        if (cp < 1) {
            cp = 1;
        } else if (cp > 8) {
            cp = 8;
        }
        output.writeBe32(0, (sample.position + sample.start) | (cp << 24));
        output.writeBe32(4, sample.size);
        return output;
    }

    /** Check that sequence version is compatible. */
    private void checkVersion(int major, int minor) {
        boolean compatible = true;
        if (major < MdsdrvPlatform.MIN_SEQ_VERSION_MAJOR) {
            compatible = false;
        }
        if (major == MdsdrvPlatform.MIN_SEQ_VERSION_MAJOR && minor < MdsdrvPlatform.MIN_SEQ_VERSION_MINOR) {
            compatible = false;
        }
        if (major > MdsdrvPlatform.SEQ_VERSION_MAJOR) {
            compatible = false;
        }
        if (MdsdrvPlatform.MIN_SEQ_VERSION_MAJOR == 0) {
            // Special case: 0.x is unstable
            if (major != 0 || minor < MdsdrvPlatform.MIN_SEQ_VERSION_MINOR
                    || minor > MdsdrvPlatform.SEQ_VERSION_MINOR) {
                compatible = false;
            }
        }
        if (!compatible) {
            throw new InputError(null, "Incompatible sequence data format version %d.%d (minimum: %d.%d)"
                    .formatted(major, minor, MdsdrvPlatform.MIN_SEQ_VERSION_MAJOR,
                            MdsdrvPlatform.MIN_SEQ_VERSION_MINOR));
        }
    }

    /** Get the number of sequences. */
    public int getSeqCount() {
        int count = 0;
        for (List<SeqData> group : seqBank.values()) {
            count += group.size();
        }
        return count;
    }

    /** Get the output {@code mdsseq.bin}. */
    public ByteVector getSeqData() {
        int headerSize = 12 + getSeqCount() * 4;
        ByteVector data = new ByteVector(headerSize);

        // sdtop - 0
        int offset = headerSize - 8;
        for (ByteVector i : dataBank) {
            data.addAll(i);
            dataOffset.add(offset);
            offset += i.size();
            if ((offset & 1) != 0) {
                data.add(0);
                offset++;
            }
            if (offset >= 0x8000) {
                throw new InputError(null, "instrument data bank is too big (>32768 bytes)");
            }
        }

        int id = 1;
        for (Map.Entry<String, List<SeqData>> group : seqBank.entrySet()) {
            for (SeqData seq : group.getValue()) {
                logger.log(Level.DEBUG, "put seq %02x (%s.%s) at %04x"
                        .formatted(id, group.getKey(), seq.filename, offset));
                for (int[] j : seq.patchTable) {
                    seq.data.writeBe16(j[0], dataOffset.get(j[1] & 0x7fff) | (j[1] & 0x8000));
                }
                data.addAll(seq.data);
                data.writeBe32(8 + (id * 4), offset);
                offset += seq.data.size();
                if ((offset & 1) != 0) {
                    data.add(0);
                    offset++;
                }
                id++;
            }
        }

        // write header
        data.writeBe32(0, 0x10011f00);
        data.writeBe16(4, (MdsdrvPlatform.SEQ_VERSION_MAJOR << 8) | MdsdrvPlatform.SEQ_VERSION_MINOR);
        data.writeBe16(6, id - 1);
        data.writeBe32(8, offset);

        // write wave table
        offset = data.size();
        data.writeBe16(offset, waveRom.getSampleHeaders().size());
        offset += 2;
        for (WaveBank.Sample i : waveRom.getSampleHeaders()) {
            int hoffset = findUniqueData(getPcmHeader(i)) & 0xffff;
            data.writeBe16(offset, dataOffset.get(hoffset));
            offset += 2;
        }

        return data;
    }

    /** Get the output {@code mdspcm.bin}. */
    public ByteVector getPcmData() {
        byte[] wave = waveRom.getRomData();
        return new ByteVector(wave, 0, wave.length - (int) waveRom.getFreeBytes());
    }

    /** Get linker statistics. */
    public String getStatistics() {
        return "PCM data size: %d bytes (max %d)%nGaps: %d bytes, largest %d%n".formatted(
                waveRom.getRomData().length - waveRom.getFreeBytes(),
                waveRom.getRomData().length,
                waveRom.getTotalGap(), waveRom.getLargestGap());
    }

    /** Get an assembly header file. */
    public String getAsmHeader() {
        return getHeader((key, value) -> key + " = " + value + "\n");
    }

    /** Get a C header file. */
    public String getCHeader() {
        return getHeader((key, value) -> "#define " + key + " " + value + "\n");
    }

    private interface Define {
        String format(String key, int value);
    }

    private String getHeader(Define define) {
        int songId = 0;
        StringBuilder buf = new StringBuilder();
        Map<String, Integer> defineCount = new HashMap<>();
        for (Map.Entry<String, List<SeqData>> group : seqBank.entrySet()) {
            buf.append(define.format(uniqueString(group.getKey() + "_MIN", defineCount), songId + 1));
            for (SeqData seq : group.getValue()) {
                songId++;
                buf.append(define.format(uniqueString(group.getKey() + "_" + seq.filename, defineCount), songId));
            }
            buf.append(define.format(uniqueString(group.getKey() + "_MAX", defineCount), songId));
        }
        return buf.toString();
    }

    /** Get a keyified string (all characters that would be illegal C or ASM defines stripped). */
    private String keyifyString(String input) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (CType.isSpace(c)) {
                out.append('_');
            } else if (CType.isAlnum(c) || c == '_') {
                out.append((char) CType.toUpper(c));
            }
        }
        return out.toString();
    }

    /** Get a unique keyified string. */
    private String uniqueString(String input, Map<String, Integer> map) {
        String str = keyifyString(input);
        int count = map.merge(str, 1, Integer::sum);
        if (count != 1) {
            str = uniqueString(str + "_" + (count - 1), map);
        }
        return str;
    }

    /** Add unique data to the data bank (same as {@code MdsdrvData#addUniqueData}). */
    private int addUniqueData(ByteVector data) {
        for (int i = 0; i < dataBank.size(); i++) {
            if (data.equals(dataBank.get(i))) {
                return i;
            }
        }
        dataBank.add(data);
        return dataBank.size() - 1;
    }

    /**
     * Find unique data in the data bank.
     *
     * @throws NoSuchElementException if not found
     */
    private int findUniqueData(ByteVector data) {
        for (int i = 0; i < dataBank.size(); i++) {
            if (data.equals(dataBank.get(i))) {
                return i;
            }
        }
        throw new NoSuchElementException("MdsdrvLinker.findUniqueData");
    }

    private static String toString(ByteVector data) {
        StringBuilder sb = new StringBuilder(data.size());
        for (int i = 0; i < data.size(); i++) {
            sb.append((char) data.get(i));
        }
        return sb.toString();
    }
}
