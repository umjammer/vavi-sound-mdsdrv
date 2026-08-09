/*
 * Copyright (c) 2026 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.sound.ctrmml.platform;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import vavi.sound.ctrmml.ByteVector;
import vavi.sound.ctrmml.CType;
import vavi.sound.ctrmml.Cursor;
import vavi.sound.ctrmml.FileResolver;
import vavi.sound.ctrmml.InputError;
import vavi.sound.ctrmml.Song;
import vavi.sound.ctrmml.WaveBank;


/**
 * MDSDRV data bank.
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 2026-08-02 nsano initial version <br>
 * @see <a href="https://github.com/superctr/ctrmml">ctrmml</a> src/platform/mdsdrv.cpp
 */
public class MdsdrvData {

    /** {@code sscanf("@%hu")} */
    private static final Pattern INSTRUMENT_TAG = Pattern.compile("^@\\s*([+-]?\\d+)");
    /** {@code sscanf("@m%hu")} */
    private static final Pattern PITCH_TAG = Pattern.compile("^@m\\s*([+-]?\\d+)");

    /** FM channel register name to address. */
    private static final Map<String, Integer> REGISTERS = new HashMap<>();

    static {
        REGISTERS.put("dtml1", 0x30);
        REGISTERS.put("dtml2", 0x38);
        REGISTERS.put("dtml3", 0x34);
        REGISTERS.put("dtml4", 0x3c);
        REGISTERS.put("tl1", 0xfc);
        REGISTERS.put("tl2", 0xfe);
        REGISTERS.put("tl3", 0xfd);
        REGISTERS.put("tl4", 0xff);
        REGISTERS.put("ksar1", 0x50);
        REGISTERS.put("ksar2", 0x58);
        REGISTERS.put("ksar3", 0x54);
        REGISTERS.put("ksar4", 0x5c);
        REGISTERS.put("amdr1", 0x60);
        REGISTERS.put("amdr2", 0x68);
        REGISTERS.put("amdr3", 0x64);
        REGISTERS.put("amdr4", 0x6c);
        REGISTERS.put("sr1", 0x70);
        REGISTERS.put("sr2", 0x78);
        REGISTERS.put("sr3", 0x74);
        REGISTERS.put("sr4", 0x7c);
        REGISTERS.put("slrr1", 0x80);
        REGISTERS.put("slrr2", 0x88);
        REGISTERS.put("slrr3", 0x84);
        REGISTERS.put("slrr4", 0x8c);
        REGISTERS.put("ssg1", 0x90);
        REGISTERS.put("ssg2", 0x98);
        REGISTERS.put("ssg3", 0x94);
        REGISTERS.put("ssg4", 0x9c);
        REGISTERS.put("fbal", 0xb0);
    }

    /** Look up a register name and return the address, or 0 if invalid. */
    public static int getRegister(String str) {
        StringBuilder sb = new StringBuilder(str.length());
        for (int i = 0; i < str.length(); i++) {
            sb.append((char) CType.toLower(str.charAt(i)));
        }
        return REGISTERS.getOrDefault(sb.toString(), 0);
    }

    public enum InstrumentType {
        INS_UNDEFINED, INS_PSG, INS_FM, INS_PCM
    }

    private static final int DATA_COUNT_MAX = 256;

    /** Allow extended pitch envelopes. */
    private boolean useExtendedPitch = true;

    /** Data bank, holds all instrument and envelope data. */
    final List<ByteVector> dataBank = new ArrayList<>();
    /** Waverom bank, holds PCM samples. */
    private final WaveBank waveRom = new WaveBank(0x20_0000);
    /** Maps the current song instruments to data bank entries. */
    private final Map<Integer, Integer> envelopeMap = new TreeMap<>();
    /** Maps the PCM instruments to a wave rom header. */
    private final Map<Integer, Integer> waveMap = new TreeMap<>();
    /** Maps the current song instrument to transpose settings (for FM 2op only). */
    private final Map<Integer, Integer> insTranspose = new TreeMap<>();
    /** Specify the instrument types of the defined song instruments. */
    final Map<Integer, InstrumentType> insType = new TreeMap<>();
    /** Maps the current song pitch envelopes to data bank entries. */
    private final Map<Integer, Integer> pitchMap = new TreeMap<>();
    /** Specify the instrument types of the defined pitch envelopes. */
    final Set<Integer> pitchExtend = new TreeSet<>();
    /** Diagnostic message. */
    private final StringBuilder message = new StringBuilder();

    public MdsdrvData() {
    }

    /** Sets the resolver used to read PCM sample files. */
    public void setFileResolver(FileResolver resolver) {
        waveRom.setFileResolver(resolver);
    }

    public String getMessage() {
        return message.toString();
    }

    public WaveBank getWaveRom() {
        return waveRom;
    }

    /** Add all instruments and envelopes from a Song to the data bank. */
    public void readSong(Song song) {
        // clear envelope and instrument maps
        envelopeMap.clear();
        insTranspose.clear();
        pitchMap.clear();
        waveMap.clear();
        insType.clear();
        try {
            // just do this if we have this tag
            waveRom.setIncludePaths(song.getTag("include_path"));
        } catch (RuntimeException e) {
            // no include path
        }
        // add a unique psg envelope to prevent possible errors
        envelopeMap.put(0, addUniqueData(bytes(0x10, 0x01, 0x1f, 0x00)));
        insTranspose.put(0, 0);
        insType.put(0, InstrumentType.INS_UNDEFINED);
        List<String> tagOrder = song.getTagOrderList();

        if (song.checkTag("#option")) {
            List<String> tag = song.getTag("#option");
            if (tag.contains("noextpitch")) {
                useExtendedPitch = false;
            }
        }

        for (String key : new ArrayList<>(tagOrder)) {
            List<String> tag = song.getTag(key);
            Matcher m = INSTRUMENT_TAG.matcher(key);
            if (m.find()) {
                addInstrument(toU16(m.group(1)), tag);
                continue;
            }
            m = PITCH_TAG.matcher(key);
            if (m.find()) {
                int id = toU16(m.group(1));
                try {
                    addPitchEnvelope(id, tag);
                    message.append("read pitch envelope ").append(dumpData(id, pitchMap.get(id))).append('\n');
                } catch (IllegalArgumentException e) {
                    addExtendedPitchEnvelope(id, tag);
                    message.append("read extended pitch envelope ").append(dumpData(id, pitchMap.get(id))).append('\n');
                }
            }
        }
    }

    /** Add an instrument to the data bank. */
    private void addInstrument(int id, List<String> tag) {
        String type = tag.getFirst();
        List<String> rest = tag.subList(1, tag.size());
        if (CType.iequal("fm", type)) {
            addInsFm4Op(id, rest);
            message.append("read FM envelope ").append(dumpData(id, envelopeMap.get(id))).append('\n');
        } else if (CType.iequal("2op", type)) {
            addInsFm2Op(id, rest);
            message.append("read 2op envelope ").append(dumpData(id, envelopeMap.get(id))).append('\n');
        } else if (CType.iequal("psg", type)) {
            addInsPsg(id, rest);
            message.append("read PSG envelope ").append(dumpData(id, envelopeMap.get(id))).append('\n');
        } else if (CType.iequal("pcm", type)) {
            addInsPcm(id, rest);
            message.append("read wave sample ").append(dumpData(id, envelopeMap.get(id))).append('\n');
        } else {
            throw new InputError(null, "unknown envelope type %s%n".formatted(type));
        }
    }

    /** Add 4op FM instrument. Data stored in operator order. */
    private void addInsFm4Op(int id, List<String> tag) {
        ByteVector fmData = new ByteVector(30);
        int[] tagData = new int[42];
        int it = 0;
        for (int i = 0; i < 42; i++) {
            if (it == tag.size()) {
                throw new InputError(null, "error: not enough parameters for fm instrument @%d".formatted(id));
            }
            tagData[i] = (int) strtol(tag.get(it), 10) & 0xff;
            it++;
        }

        // Transpose
        if (it != tag.size()) {
            fmData.set(29, (int) (strtol(tag.get(it), 10) + 24) << 1);
        } else {
            fmData.set(29, 24 << 1);
        }

        for (int i = 0; i < 4; i++) {
            int op = 2;

            // physical operator order is 1,3,2,4
            if ((i & 2) != 0) {
                op += 10;
            }
            if ((i & 1) != 0) {
                op += 20;
            }
            // DT,MUL
            fmData.set(i, (tagData[op + 8] << 4) | (tagData[op + 7] & 15));
            // KS/AR
            fmData.set(4 + i, (tagData[op + 6] << 6) | (tagData[op] & 31));
            // AM/DR
            fmData.set(8 + i, tagData[op + 1] & 31);
            // SR
            fmData.set(12 + i, tagData[op + 2] & 31);
            // SL/RR
            fmData.set(16 + i, (tagData[op + 4] << 4) | (tagData[op + 3] & 15));
            // SSG-EG
            fmData.set(20 + i, (tagData[op + 9] % 100) & 15);
            // TL
            fmData.set(24 + i, tagData[op + 5]);
            // AM sensitivity = set SSG-EG to 100
            if (tagData[op + 9] >= 100) {
                fmData.set(8 + i, fmData.get(8 + i) | 0x80);
            }
        }
        // FB/ALG
        fmData.set(28, (tagData[0] & 7) | (tagData[1] << 3));
        envelopeMap.put(id, addUniqueData(fmData));
        insTranspose.put(id, (fmData.get(29) >> 1) - 24);
        insType.put(id, InstrumentType.INS_FM);
    }

    /**
     * Add 2op FM instrument definition.
     * <p>
     * Tag format: {@code InsID,Mul1,Mul2,Mul3,Mul4,Transpose}
     */
    private void addInsFm2Op(int id, List<String> tag) {
        ByteVector fmData;
        int[] tagData = new int[6];

        int it = 0;
        for (int i = 0; i < 6; i++) {
            if (it == tag.size()) {
                throw new InputError(null, "error: not enough parameters for 2op fm instrument @%d".formatted(id));
            }
            tagData[i] = (int) strtol(tag.get(it), 10) & 0xff;
            it++;
        }

        int insId = tagData[0];
        try {
            fmData = new ByteVector(dataBank.get(envelopeMap.get(insId)));
            for (int i = 0; i < 4; i++) {
                int mul = tagData[1 + ((i & 1) << 1) + ((i & 2) >> 1)];
                int dt = fmData.get(i) & 0xf0;
                fmData.set(i, dt | (mul & 15));
            }
            fmData.set(24 + 3, fmData.get(24 + 2)); // op4 tl should be same as op1
            fmData.set(29, (tagData[5] + 24) << 1);
            envelopeMap.put(id, addUniqueData(fmData));
            insTranspose.put(id, tagData[5]);
            insType.put(id, InstrumentType.INS_FM);
        } catch (NullPointerException | IndexOutOfBoundsException e) {
            throw new InputError(null,
                    "2op ins @%d is referencing instrument @%d which does not exist%n".formatted(id, insId));
        }
    }

    /**
     * Add PSG volume envelope.
     * <p>
     * Envelope input format:
     * <pre>
     *   Value
     *   Value:Length
     *   Value&gt;Target - slide
     *   Value&gt;Target:Length - slide
     *   | - set loop position
     *   / - wait until keyoff
     *   l:Length - default length
     * </pre>
     * Output format:
     * <pre>
     *   00 - end
     *   01 - sustain
     *   02 pp - loop to &lt;p&gt;
     *   fv - value &lt;v&gt; for &lt;f&gt; frames.
     * </pre>
     */
    private void addInsPsg(int id, List<String> tag) {
        ByteVector envData = new ByteVector();
        int loopPos = -1;
        int lastPos = 0;
        int last = -1;
        int defaultLen = 1;
        if (tag.isEmpty()) {
            message.append("warning: empty psg instrument @%d%n".formatted(id));
            return;
        }
        for (String item : tag) {
            Cursor s = new Cursor(item);
            if (s.peek() == '|') {
                loopPos = envData.size();
            } else if (s.peek() == '/') {
                // insert sustain
                if (last == -1) {
                    // max volume
                    envData.add(0x10);
                }
                envData.add(0x01);
                last = -1;
            } else if (s.peek() == 'l' && s.next() == ':' && CType.isDigit(s.next())) {
                defaultLen = (int) s.strtol(10);
            } else if (CType.isDigit(s.peek())) {
                int initial = (int) s.strtol(10) & 0xff;
                int target = initial;
                int length = defaultLen & 0xff;
                double delta = 0;
                double counter;

                if (s.peek() == '>' && s.next() != 0) {
                    target = (int) s.strtol(10) & 0xff;
                }
                target = Math.min(target, 15); // bounds check
                initial = Math.min(initial, 15);

                if (target != initial) {
                    length = (Math.abs(target - initial) + 1) & 0xff;
                }
                if (s.peek() == ':' && s.next() != 0) {
                    length = (int) s.strtol(10) & 0xff;
                }
                if (length == 0) {
                    length++;
                } else if (length > 1) { // calculate slide
                    delta = (double) (target - initial) / (length - 1);
                }
                counter = initial + 0.5;
                while (length-- != 0) {
                    // last value is always the slide target
                    int val = (length != 0) ? (int) counter : target;
                    // add to duration of previous value if it's the same
                    if ((int) counter == last && envData.get(lastPos) < 0xf0) {
                        envData.set(lastPos, envData.get(lastPos) + 0x10);
                    } else {
                        lastPos = envData.size();
                        envData.add(0x1f - val);
                    }
                    last = val;
                    counter += delta;
                }
            } else {
                throw new InputError(null, "undefined envelope value '%s'".formatted(s.rest()));
            }
        }
        if (loopPos == -1) {
            // end command
            envData.add(0x00);
        } else {
            // loop command
            envData.add(0x02);
            envData.add(loopPos);
        }
        envelopeMap.put(id, addUniqueData(envData));
        insTranspose.put(id, 0);
        insType.put(id, InstrumentType.INS_PSG);
    }

    /** Add PCM instrument. */
    private void addInsPcm(int id, List<String> tag) {
        // Insert a generic 32-byte Wave_Bank::Sample header.
        int waveHeaderId = waveRom.addSample(tag);
        waveMap.put(id, waveHeaderId);
        ByteVector envData = waveRom.getSampleHeaders().get(waveHeaderId).toBytes();

        envelopeMap.put(id, addUniqueData(envData));
        insType.put(id, InstrumentType.INS_PCM);
    }

    /**
     * Read pitch envelope.
     * <p>
     * Envelope input format:
     * <pre>
     *   Value
     *   Value:Length
     *   Value&gt;Target - slide
     *   Value&gt;Target:Length - slide
     *   V&lt;Base&gt;:&lt;Depth&gt;:&lt;Rate&gt; - vibrato macro, automatically sets loop position
     *   | - set loop position
     * </pre>
     * Output format:
     * <pre>
     *   hh ll dd ss - set pitch to &lt;hl&gt;, change it with &lt;d&gt; for &lt;s&gt; frames.
     *                 If &lt;s&gt; is ff = continue forever
     *   7F &lt;pp&gt; - loop to &lt;p*4&gt;
     * </pre>
     *
     * @throws IllegalArgumentException if the envelope requires the extended format
     */
    private void addPitchEnvelope(int id, List<String> tag) {
        ByteVector envData = new ByteVector();
        int loopPos = -1;
        if (tag.isEmpty()) {
            message.append("warning: empty pitch envelope @M%d%n".formatted(id));
            return;
        }
        for (String item : tag) {
            Cursor s = new Cursor(item);
            if (s.peek() == '|') {
                loopPos = envData.size() / 4;
            } else if (CType.isDigit(s.peek()) || s.peek() == '-') {
                addPitchNode(s, false, envData);
            } else if (s.peek() == 'V') {
                loopPos = envData.size() / 4;
                addPitchVibrato(s, false, envData);
            } else {
                throw new InputError(null, "undefined envelope value '%s'".formatted(s.rest()));
            }
        }
        // end command
        if (loopPos == -1) {
            envData.setBack(0xff);
        } else {
            envData.add(0x7f);
            envData.add(loopPos);
        }
        pitchMap.put(id, addUniqueData(envData));
    }

    /** Read an extended pitch envelope. */
    private void addExtendedPitchEnvelope(int id, List<String> tag) {
        ByteVector envData = new ByteVector();
        int loopPos = -1;
        if (tag.isEmpty()) {
            message.append("warning: empty pitch envelope @M%d%n".formatted(id));
            return;
        }
        for (String item : tag) {
            Cursor s = new Cursor(item);
            if (s.peek() == '|') {
                loopPos = envData.size() / 6;
            } else if (CType.isDigit(s.peek()) || s.peek() == '-') {
                addPitchNode(s, true, envData);
            } else if (s.peek() == 'V') {
                loopPos = envData.size() / 6;
                addPitchVibrato(s, true, envData);
            } else {
                throw new InputError(null, "undefined envelope value '%s'".formatted(s.rest()));
            }
        }
        // end command
        if (loopPos == -1) {
            envData.set(envData.size() - 2, 0xff);
            envData.set(envData.size() - 1, envData.get(envData.size() - 1) - 1);
        } else {
            envData.setBack(loopPos);
        }
        pitchMap.put(id, addUniqueData(envData));
        pitchExtend.add(id);
    }

    /** Adds a node to the pitch envelope. */
    private void addPitchNode(Cursor s, boolean extend, ByteVector envData) {
        double initial = s.strtod();
        double target = initial;
        int length = 0;
        double counter;

        if (s.peek() == '>' && s.next() != 0) {
            target = s.strtod();
        }

        if (target != initial) {
            length = (int) (Math.round(Math.abs(target - initial) + 1.5) >> 4);
        }
        if (s.peek() == ':' && s.next() != 0) {
            length = (int) s.strtol(10);
        }
        if (length < 1) {
            length += 1;
        }

        counter = initial;
        while (length > 0) {
            double delta = (target - counter) / length;
            int envLen = Math.min(length, 255);
            int envInitial = (short) (int) (counter * 256);
            int envDelta = (short) (int) (delta * 256);
            envInitial = (short) (Math.min(envInitial, 0x7eff));
            if (extend) {
                envData.add(envInitial >> 8);
                envData.add(envInitial & 0xff);
                envData.add(envDelta >> 8);
                envData.add(envDelta & 0xff);
                envData.add(envLen - 1);
                envData.add((envData.size() + 1) / 6);
            } else {
                if (!useExtendedPitch) {
                    envDelta = Math.clamp(envDelta, -128, 127);
                } else if (envDelta > 127 || envDelta < -128) {
                    throw new IllegalArgumentException("addPitchNode");
                }

                envData.add(envInitial >> 8);
                envData.add(envInitial & 0xff);
                envData.add(envDelta);
                envData.add(envLen - 1);
            }
            // apply delta and decrease length counter
            counter += (envDelta * envLen) / 256; // NOPMD integer division, as in the original
            length -= envLen;
        }
    }

    /** Adds a vibrato macro to the pitch envelope. */
    private void addPitchVibrato(Cursor s, boolean extend, ByteVector envData) {
        // Vibrato macro
        double vibratoBase = 0;
        double vibratoDepth = 0.5;
        int vibratoRate = 5;

        s.advance();
        if (CType.isDigit(s.peek()) || s.peek() == '-') {
            vibratoBase = s.strtod();
        }
        if (s.peek() == ':' && s.next() != 0) {
            vibratoDepth = s.strtod() / 2.0;
        }
        if (s.peek() == ':' && s.next() != 0) {
            vibratoRate = (int) s.strtol(10);
        } else { // TODO: throw an exception or warning
            message.append("Invalid vibrato definition: '%s'%n".formatted(s.rest()));
        }

        vibratoDepth += vibratoBase;
        addPitchNode(new Cursor(fmt(vibratoBase, vibratoDepth, vibratoRate)), extend, envData);
        addPitchNode(new Cursor(fmt(vibratoDepth, -vibratoDepth, vibratoRate * 2)), extend, envData);
        addPitchNode(new Cursor(fmt(-vibratoDepth, vibratoBase, vibratoRate)), extend, envData);
    }

    /** {@code stringf("%f>%f:%d", ...)} */
    private static String fmt(double from, double to, int rate) {
        return String.format(Locale.ROOT, "%f>%f:%d", from, to, rate);
    }

    /**
     * Add unique data to the data bank and return the index.
     * <p>
     * In case of a duplicate, return the index of the previously added data.
     */
    private int addUniqueData(ByteVector data) {
        for (int i = 0; i < dataBank.size(); i++) {
            if (data.equals(dataBank.get(i))) {
                return i;
            }
        }
        int i = dataBank.size();
        if (i >= DATA_COUNT_MAX) {
            throw new InputError(null, "error: maximum amount of data table entries reached%n".formatted());
        }
        dataBank.add(new ByteVector(data));
        return i;
    }

    /** Dump data bank index to a string for debugging purposes. */
    private String dumpData(int id, Integer mappedId) {
        if (mappedId == null) {
            return "%d = (none)".formatted(id);
        }
        ByteVector data = dataBank.get(mappedId);
        StringBuilder out = new StringBuilder("%d = %d [%d]{".formatted(id, mappedId, data.size()));
        for (int i = 0; i < data.size(); i++) {
            if (i > 0) {
                out.append(", ");
            }
            out.append("%02x".formatted(data.get(i)));
        }
        out.append('}');
        return out.toString();
    }

    /** @throws NoSuchElementException if the instrument is not defined */
    InstrumentType getInsType(int id) {
        InstrumentType type = insType.get(id);
        if (type == null) {
            throw new NoSuchElementException("@" + id);
        }
        return type;
    }

    /** @throws NoSuchElementException if the instrument is not defined */
    int getEnvelopeId(int id) {
        Integer envelopeId = envelopeMap.get(id);
        if (envelopeId == null) {
            throw new NoSuchElementException("@" + id);
        }
        return envelopeId;
    }

    /** @throws NoSuchElementException if the pitch envelope is not defined */
    int getPitchId(int id) {
        Integer pitchId = pitchMap.get(id);
        if (pitchId == null) {
            throw new NoSuchElementException("@M" + id);
        }
        return pitchId;
    }

    private static ByteVector bytes(int... values) {
        ByteVector v = new ByteVector();
        for (int value : values) {
            v.add(value);
        }
        return v;
    }

    /** {@code strtol(s.c_str(), NULL, base)} */
    private static long strtol(String s, int base) {
        return new Cursor(s).strtol(base);
    }

    /** {@code %hu} conversion. */
    private static int toU16(String s) {
        return (int) (Long.parseLong(s) & 0xffff);
    }
}
