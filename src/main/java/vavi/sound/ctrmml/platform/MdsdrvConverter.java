/*
 * Copyright (c) 2026 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.sound.ctrmml.platform;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import vavi.sound.ctrmml.ByteVector;
import vavi.sound.ctrmml.FileResolver;
import vavi.sound.ctrmml.Riff;
import vavi.sound.ctrmml.Song;
import vavi.sound.ctrmml.Track;

import static java.lang.System.getLogger;


/**
 * MDSDRV sequence converter: converts a {@link Song} into MDSDRV data, including data and
 * sequences.
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 2026-08-02 nsano initial version <br>
 * @see <a href="https://github.com/superctr/ctrmml">ctrmml</a> src/platform/mdsdrv.cpp
 */
public class MdsdrvConverter {

    private static final Logger logger = getLogger(MdsdrvConverter.class.getName());

    private final Song song;
    private final MdsdrvData data = new MdsdrvData();
    /** Maps event parameter to envelope_id. Ordered; the iteration order is part of the format. */
    private final Map<Integer, Integer> usedDataMap = new TreeMap<>();
    /** Maps event parameter to track_id. */
    private final Map<Integer, Integer> subroutineMap = new TreeMap<>();
    /** Maps event parameter to track_id. */
    private final Map<Integer, Integer> macroTrackMap = new TreeMap<>();
    private final List<List<MdsdrvEvent>> subroutineList = new ArrayList<>();
    private final List<List<MdsdrvEvent>> macroTrackList = new ArrayList<>();
    private final Map<Integer, List<MdsdrvEvent>> trackList = new TreeMap<>();
    private final ByteVector sequenceData = new ByteVector();

    public MdsdrvConverter(Song song) {
        this(song, FileResolver.FILE_SYSTEM);
    }

    public MdsdrvConverter(Song song, FileResolver resolver) {
        this.song = song;
        data.setFileResolver(resolver);
        data.readSong(song);

        for (Map.Entry<Integer, Track> entry : song.getTrackMap().entrySet()) {
            int id = entry.getKey();
            if (id < 16) {
                parseTrack(id);
            }
        }

        int trackHeaderOffset = 4;
        int trackCount = 0;
        int dataBase = 4 + (4 * trackList.size());
        int headerSize = dataBase + (subroutineList.size() + macroTrackList.size() + usedDataMap.size()) * 2;
        sequenceData.insertFront(headerSize, 0x00);

        // we should use something else to define the track list, i think...
        for (Map.Entry<Integer, List<MdsdrvEvent>> entry : trackList.entrySet()) {
            trackCount++;
            int offset = (sequenceData.size() - dataBase) & 0xffff;
            sequenceData.set(trackHeaderOffset++, entry.getKey());
            sequenceData.set(trackHeaderOffset++, 0);
            sequenceData.set(trackHeaderOffset++, offset >> 8);
            sequenceData.set(trackHeaderOffset++, offset);

            sequenceData.addAll(convertTrack(entry.getValue()));
        }

        String volStr = song.getTagFrontSafe("#volume");
        int vol = 0;
        if (!volStr.isEmpty()) {
            // note: the original discards the parsed value here, so #volume has no effect
            if (vol > 127) {
                vol = 127;
            }
        }

        sequenceData.set(0, dataBase >> 8);
        sequenceData.set(1, dataBase);
        sequenceData.set(2, vol);
        sequenceData.set(3, trackCount);

        for (List<MdsdrvEvent> subroutine : subroutineList) {
            int offset = (sequenceData.size() - dataBase) & 0xffff;
            sequenceData.set(trackHeaderOffset++, offset >> 8);
            sequenceData.set(trackHeaderOffset++, offset);

            sequenceData.addAll(convertTrack(subroutine));
        }

        for (List<MdsdrvEvent> macroTrack : macroTrackList) {
            int offset = (sequenceData.size() - dataBase) & 0xffff;
            sequenceData.set(trackHeaderOffset++, offset >> 8);
            sequenceData.set(trackHeaderOffset++, offset);

            sequenceData.addAll(convertMacroTrack(macroTrack));
        }
    }

    Song getSong() {
        return song;
    }

    MdsdrvData getData() {
        return data;
    }

    /** Diagnostic messages collected while reading the instruments. */
    public String getMessage() {
        return data.getMessage();
    }

    /** Output a MDSDRV RIFF container. */
    public Riff getMds() {
        ByteVector ver = new ByteVector();
        ver.add(MdsdrvPlatform.SEQ_VERSION_MAJOR);
        ver.add(MdsdrvPlatform.SEQ_VERSION_MINOR);

        String group = song.getTagFrontSafe("#group");
        ByteVector groupData = new ByteVector();
        for (int i = 0; i < group.length(); i++) {
            groupData.add(group.charAt(i));
        }

        Riff riff = new Riff(Riff.TYPE_RIFF, Riff.fourCc("MDS0"));
        riff.addChunk(new Riff(Riff.fourCc("ver "), ver)); // version data
        riff.addChunk(new Riff(Riff.fourCc("grp "), groupData)); // group id
        riff.addChunk(new Riff(Riff.fourCc("seq "), sequenceData));
        Riff dblk = new Riff(Riff.TYPE_LIST, Riff.fourCc("dblk"));
        for (Map.Entry<Integer, Integer> entry : usedDataMap.entrySet()) {
            ByteVector d = new ByteVector(4);
            d.writeLe32(0, getDataId(entry.getValue()) + ((entry.getKey() & 0x10000) != 0 ? (1 << 31) : 0));
            d.addAll(data.dataBank.get(entry.getKey() & 0x7fff));
            if (entry.getKey() < 0x20000) {
                dblk.addChunk(new Riff(Riff.fourCc("glob"), d));
            } else {
                dblk.addChunk(new Riff(Riff.fourCc("pcmh"), d));
            }
        }
        riff.addChunk(dblk);
        byte[] rom = data.getWaveRom().getRomData();
        int used = rom.length - (int) data.getWaveRom().getFreeBytes();
        riff.addChunk(new Riff(Riff.fourCc("pcmd"), new ByteVector(rom, 0, used)));
        return riff;
    }

    /** Uses {@link MdsdrvTrackWriter} to convert a track into an event stream. */
    private void parseTrack(int trackId) {
        List<MdsdrvEvent> events = trackList.computeIfAbsent(trackId, k -> new ArrayList<>());
        MdsdrvTrackWriter writer = new MdsdrvTrackWriter(this, trackId, false, false, events);
        while (writer.isEnabled()) {
            writer.stepEvent();
        }
    }

    /**
     * Convert an event stream (track or subroutine) to a MDSDRV byte stream.
     * <p>
     * This is essentially the final pass of the MML sequence data. Optimization to reduce the
     * note/rest length footprint is done here.
     */
    private ByteVector convertTrack(List<MdsdrvEvent> eventList) {
        int segnoPos = 0x0000;
        // even though we have a default rest/note time it's best not to rely on them,
        // for example in the beginning of a subroutine
        int lastRest = 0xffff;
        int lastNote = 0xffff;
        ByteVector trackData = new ByteVector();
        int lastType = MdsdrvEvent.REST;
        // used as a stack; the last element is the top
        List<Integer> loopBreakAddress = new ArrayList<>();

        for (MdsdrvEvent it : eventList) {
            int type = it.type;
            int arg = it.arg;
            if (type == MdsdrvEvent.REST && arg != 0) {
                arg -= 1;
                while (arg >= 128) {
                    // If last event was a note or tie and no length was specified we must add
                    // the length parameter of that event before adding any rest duration, to
                    // prevent ambiguity
                    if ((lastType >= MdsdrvEvent.TIE) && (lastType < MdsdrvEvent.SLR)
                            && (trackData.at(trackData.size() - 1) > 0x80)) {
                        lastType = MdsdrvEvent.REST;
                        trackData.add(lastNote);
                    }
                    trackData.add(0x7f);
                    lastRest = 0x7f;
                    arg -= 128;
                }
                if (arg == lastRest) {
                    trackData.add(MdsdrvEvent.REST);
                } else {
                    if ((lastType >= MdsdrvEvent.TIE) && (lastType < MdsdrvEvent.SLR)
                            && (trackData.at(trackData.size() - 1) > 0x80)) {
                        lastType = MdsdrvEvent.REST;
                        trackData.add(lastNote);
                    }
                    trackData.add(arg);
                    lastRest = arg;
                }
            } else if (type < MdsdrvEvent.SLR && arg != 0) {
                // TODO: it would be possible to optimize by replacing an argument with
                // last_note*2 with a TIE command, if the next note has last_note length.
                // could save some space for "shuffle" notes.
                arg -= 1;
                trackData.add(type);
                while (arg >= 128) {
                    if (lastNote != 0x7f) {
                        trackData.add(0x7f);
                    }
                    lastNote = 0x7f;
                    arg -= 128;
                    trackData.add(MdsdrvEvent.TIE);
                }
                if (arg != lastNote) {
                    trackData.add(arg);
                    lastNote = arg;
                }
            } else {
                switch (type) {
                case MdsdrvEvent.SEGNO -> {
                    // reset counters. TODO: can be optimized by checking what the values of the
                    // counters should be at the end of the loop and don't reset if they match.
                    lastRest = 0xffff;
                    lastNote = 0xffff;
                    segnoPos = trackData.size();
                }
                case MdsdrvEvent.SLR, MdsdrvEvent.FINISH -> // no argument
                        trackData.add(type);
                case MdsdrvEvent.VOL, MdsdrvEvent.VOLM, MdsdrvEvent.TRS, MdsdrvEvent.TRSM, // 8-bit argument
                     MdsdrvEvent.DTN, MdsdrvEvent.PTA, MdsdrvEvent.PAN, MdsdrvEvent.LFO,
                     MdsdrvEvent.FLG, MdsdrvEvent.DMFINISH, MdsdrvEvent.COMM, MdsdrvEvent.TEMPO,
                     MdsdrvEvent.PCMRATE, MdsdrvEvent.PCMMODE -> {
                    trackData.add(type);
                    trackData.add(arg & 0xff);
                }
                case MdsdrvEvent.MTAB -> { // 8-bit arg with offset
                    trackData.add(type);
                    if (arg != 0) {
                        trackData.add(arg + subroutineList.size());
                    } else {
                        trackData.add(0);
                    }
                }
                case MdsdrvEvent.INS, MdsdrvEvent.PCM -> { // 8-bit arg with offset
                    trackData.add(type);
                    trackData.add(getDataId(arg));
                }
                case MdsdrvEvent.PEG -> { // 8-bit arg with offset and toggle
                    trackData.add(type);
                    if (arg != 0) {
                        trackData.add(getDataId(arg));
                    } else {
                        trackData.add(0);
                    }
                }
                case MdsdrvEvent.FMREG, MdsdrvEvent.FMCREG, MdsdrvEvent.FMTL, MdsdrvEvent.FMTLM -> { // 16-bit arg
                    trackData.add(type);
                    trackData.add(arg >> 8);
                    trackData.add(arg);
                }
                case MdsdrvEvent.JUMP -> {
                    segnoPos = (segnoPos - (trackData.size() + 3)) & 0xffff;
                    trackData.add(MdsdrvEvent.JUMP);
                    trackData.add(segnoPos >> 8);
                    trackData.add(segnoPos);
                }
                case MdsdrvEvent.PAT -> { // subroutine
                    trackData.add(type);
                    trackData.add(arg);
                    lastRest = 0xffff;
                    lastNote = 0xffff;
                }
                case MdsdrvEvent.LP -> { // loop start
                    trackData.add(type);
                    loopBreakAddress.add(0x0000);
                    // reset counters.
                    lastRest = 0xffff;
                    lastNote = 0xffff;
                }
                case MdsdrvEvent.LPB -> // loop break: set the break address
                        loopBreakAddress.set(loopBreakAddress.size() - 1, trackData.size());
                case MdsdrvEvent.LPF -> { // loop finish
                    trackData.add(type);
                    trackData.add(arg);
                    // insert loop break command
                    if (loopBreakAddress.getLast() != 0) {
                        int offset = trackData.size() - loopBreakAddress.getLast();
                        ByteVector breakCmd = new ByteVector();
                        if (offset < 256) {
                            // short version
                            breakCmd.add(MdsdrvEvent.LPB);
                            breakCmd.add(offset);
                        } else {
                            // long version
                            breakCmd.add(MdsdrvEvent.LPBL);
                            breakCmd.add(offset >> 8);
                            breakCmd.add(offset);
                        }
                        trackData.insert(loopBreakAddress.getLast(), breakCmd);
                        // reset counters. TODO: can be optimized by resetting them to what they
                        // are at the loop break.
                        lastRest = 0xffff;
                        lastNote = 0xffff;
                    }
                    loopBreakAddress.removeLast();
                }
                default -> {
                }
                }
            }

            lastType = type;
        }
        return trackData;
    }

    /**
     * Convert an event stream (track or subroutine) to a MDSDRV macro track byte stream.
     * <p>
     * This is essentially the final pass of the MML sequence data.
     * <p>
     * TODO: Not all features of this have been tested. Some commands are not supported and
     * might not be for the foreseeable future.
     */
    private ByteVector convertMacroTrack(List<MdsdrvEvent> eventList) {
        int segnoPos = 0x0000;
        ByteVector trackData = new ByteVector();
        // used as stacks; the last element is the top
        List<Integer> loopBreakAddress = new ArrayList<>();
        List<Integer> loopStartAddress = new ArrayList<>();

        for (MdsdrvEvent it : eventList) {
            int type = it.type;
            int arg = it.arg;
            if (type == MdsdrvEvent.REST && arg != 0) {
                arg -= 1;
                while (arg >= 256) {
                    trackData.add(0x81);
                    trackData.add(0xff);
                    arg -= 256;
                }
                trackData.add(0x81);
                trackData.add(arg);
            } else if (type < MdsdrvEvent.SLR && arg != 0) {
                int command = 0x82;
                arg -= 1;
                while (arg >= 256) {
                    trackData.add(command);
                    trackData.add(0xff);
                    command = 0x81;
                    arg -= 256;
                }
                trackData.add(command);
                trackData.add(arg);
            } else {
                switch (type) {
                case MdsdrvEvent.SLR, MdsdrvEvent.PCMMODE, MdsdrvEvent.TEMPO, MdsdrvEvent.MTAB,
                     MdsdrvEvent.DMFINISH, MdsdrvEvent.PAT,
                     MdsdrvEvent.COMM, MdsdrvEvent.FLG, MdsdrvEvent.INS, MdsdrvEvent.PCM,
                     MdsdrvEvent.PCMRATE, MdsdrvEvent.PEG, MdsdrvEvent.FMREG ->
                        logger.log(Level.WARNING,
                                "MDSDRV: ignoring event type %d not supported in macro track".formatted(type));
                case MdsdrvEvent.SEGNO -> segnoPos = trackData.size();
                case MdsdrvEvent.CARRY -> {
                    trackData.add(0x83);
                    trackData.add(0x00);
                }
                case MdsdrvEvent.FINISH -> {
                    trackData.add(0x80);
                    trackData.add(0x00);
                }
                case MdsdrvEvent.VOL -> { // 8-bit argument
                    trackData.add(0x00 + 0x18);
                    trackData.add(arg);
                }
                case MdsdrvEvent.VOLM -> {
                    trackData.add(0x40 + 0x18);
                    trackData.add(arg);
                }
                case MdsdrvEvent.TRS -> {
                    trackData.add(0x00 + 0x16);
                    trackData.add(arg);
                }
                case MdsdrvEvent.TRSM -> {
                    trackData.add(0x40 + 0x16);
                    trackData.add(arg);
                }
                case MdsdrvEvent.DTN -> {
                    trackData.add(0x00 + 0x11);
                    trackData.add(arg);
                }
                case MdsdrvEvent.PTA -> {
                    trackData.add(0x00 + 0x17);
                    trackData.add(arg);
                }
                case MdsdrvEvent.PAN -> {
                    trackData.add(0x87);
                    trackData.add(arg);
                }
                case MdsdrvEvent.LFO -> {
                    if (arg < 0xc0) {
                        trackData.add(0x88);
                        trackData.add(arg);
                    } else {
                        trackData.add(0x89); // PSG noise mode
                        trackData.add(arg & 0xff);
                    }
                }
                case MdsdrvEvent.FMCREG -> {
                    trackData.add(0xc0 + (arg >> 10));
                    trackData.add(arg & 0xff);
                }
                case MdsdrvEvent.FMTL -> {
                    trackData.add(0x00 + 0x36 + (arg >> 8));
                    trackData.add(arg);
                }
                case MdsdrvEvent.FMTLM -> {
                    trackData.add(0x40 + 0x36 + (arg >> 8));
                    trackData.add(arg);
                }
                case MdsdrvEvent.JUMP -> {
                    segnoPos = (segnoPos - (trackData.size() + 2)) & 0xffff;
                    trackData.add(0x80);
                    trackData.add(segnoPos / 2);
                }
                case MdsdrvEvent.LP -> { // loop start
                    loopBreakAddress.add(0x0000);
                    trackData.add(0x84);
                    trackData.add(0); // to be filled in
                    loopStartAddress.add(trackData.size());
                }
                case MdsdrvEvent.LPB -> { // loop break
                    trackData.add(0x85);
                    trackData.add(0); // to be filled in
                    loopBreakAddress.set(loopBreakAddress.size() - 1, trackData.size());
                }
                case MdsdrvEvent.LPF -> { // loop finish
                    trackData.add(0x86);
                    trackData.add((loopStartAddress.getLast() - (trackData.size() + 1)) / 2);
                    // insert loop break command
                    if (loopBreakAddress.getLast() != 0) {
                        int offset = trackData.size() - loopBreakAddress.getLast();
                        trackData.set(loopBreakAddress.getLast() - 1, offset / 2);
                    }
                    trackData.set(loopStartAddress.getLast() - 1, arg - 1);
                    loopBreakAddress.removeLast();
                    loopStartAddress.removeLast();
                }
                default -> {
                }
                }
            }
        }
        return trackData;
    }

    /**
     * Get the subroutine ID.
     * <p>
     * Note: if a subroutine is called from a drum mode jump, the drum mode offset is not kept.
     */
    int getSubroutine(int trackId, boolean inDrumMode, boolean drumModeEnabled) {
        int mappedId = (trackId << 2) | ((inDrumMode ? 1 : 0) << 1) | (drumModeEnabled ? 1 : 0);
        Integer search = subroutineMap.get(mappedId);
        if (search == null) {
            int subId = subroutineList.size();
            subroutineMap.put(mappedId, subId);
            subroutineList.add(new ArrayList<>());
            List<MdsdrvEvent> eventList = new ArrayList<>();
            MdsdrvTrackWriter writer = new MdsdrvTrackWriter(this, trackId, inDrumMode, drumModeEnabled, eventList);
            while (writer.isEnabled()) {
                writer.stepEvent();
            }
            subroutineList.set(subId, eventList);
            return subId;
        } else {
            return search;
        }
    }

    /** Get the macro track ID. */
    int getMacroTrack(int trackId) {
        Integer search = macroTrackMap.get(trackId);
        if (search == null) {
            int subId = macroTrackList.size();
            macroTrackMap.put(trackId, subId);
            macroTrackList.add(new ArrayList<>());
            List<MdsdrvEvent> eventList = new ArrayList<>();
            MdsdrvTrackWriter writer = new MdsdrvTrackWriter(this, trackId, false, false, eventList);
            while (writer.isEnabled()) {
                writer.stepEvent();
            }
            macroTrackList.set(subId, eventList);
            return subId;
        } else {
            return search;
        }
    }

    /**
     * Get the {@code usedDataMap} index for the given envelope ID.
     * <p>
     * Calling this method assumes that the envelope ID is to be used, so it is added to the
     * map and the new index is returned.
     */
    int getEnvelope(int mappedId) {
        Integer search = usedDataMap.get(mappedId);
        if (search == null) {
            int envId = usedDataMap.size();
            usedDataMap.put(mappedId, envId);
            return envId;
        } else {
            return search;
        }
    }

    private int getDataId(int envelopeId) {
        return subroutineList.size() + macroTrackList.size() + envelopeId;
    }
}
