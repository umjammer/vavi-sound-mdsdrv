/*
 * Copyright (c) 2026 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.sound.ctrmml.platform;

import java.util.List;
import java.util.NoSuchElementException;

import vavi.sound.ctrmml.BasicPlayer;
import vavi.sound.ctrmml.CType;
import vavi.sound.ctrmml.Cursor;
import vavi.sound.ctrmml.Event;

import static vavi.sound.ctrmml.platform.MdsdrvData.getRegister;


/**
 * Track writer: converts a {@code Track} into an {@link MdsdrvEvent} stream.
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 2026-08-02 nsano initial version <br>
 * @see <a href="https://github.com/superctr/ctrmml">ctrmml</a> src/platform/mdsdrv.cpp
 */
class MdsdrvTrackWriter extends BasicPlayer {

    private final MdsdrvConverter mdsdrv;
    private final List<MdsdrvEvent> convertedEvents;
    /** set while executing a drum mode routine */
    private final boolean inDrumMode;
    /** set to true to make note events call drum mode routines */
    private boolean drumModeEnabled;
    private boolean inLoop;
    /** unsigned 16 bit */
    private int restTime;
    private final int trackId;

    MdsdrvTrackWriter(MdsdrvConverter mdsdrv, int id, boolean inDrumMode, boolean drumModeEnabled,
                      List<MdsdrvEvent> convertedEvents) {
        super(mdsdrv.getSong(), mdsdrv.getSong().getTrack(id));
        this.mdsdrv = mdsdrv;
        this.convertedEvents = convertedEvents;
        this.inDrumMode = inDrumMode;
        this.drumModeEnabled = drumModeEnabled;
        this.trackId = id;
    }

    /** Event conversion. */
    @Override
    protected void eventHook() {
        int param;
        if (isInsideLoop() || isInsideJump()) {
            if (event.type == Event.Type.INS) {
                checkInstrument(event.param);
            }
            return;
        }
        if (event.type != Event.Type.REST && restTime != 0) {
            convertedEvents.add(new MdsdrvEvent(MdsdrvEvent.REST, restTime));
            restTime = 0;
        }
        param = event.param;
        restTime = (restTime + offTime) & 0xffff;
        switch (event.type) {
        case TIE -> convertedEvents.add(new MdsdrvEvent(MdsdrvEvent.TIE, onTime));
        case NOTE -> {
            if (drumModeEnabled) {
                try {
                    param = mdsdrv.getSubroutine(param, true, false);
                } catch (NoSuchElementException e) {
                    error("MDSDRV: Drum mode subroutine *%d doesn't exist".formatted(param));
                }
            }
            if (param < 0) {
                param = 0;
            }
            if (inDrumMode) {
                if (param > 255) {
                    error("MDSDRV: note out of range (%d > %d)".formatted(param, 255));
                }
                convertedEvents.add(new MdsdrvEvent(MdsdrvEvent.DMFINISH, param));
                disable();
            } else {
                if (param >= (MdsdrvEvent.SLR - MdsdrvEvent.NOTE)) {
                    error("MDSDRV: note out of range (%d > %d)"
                            .formatted(param, MdsdrvEvent.SLR - MdsdrvEvent.NOTE));
                }
                convertedEvents.add(new MdsdrvEvent(MdsdrvEvent.NOTE + param, onTime));
            }
        }
        case LOOP_START -> convertedEvents.add(new MdsdrvEvent(MdsdrvEvent.LP, 0));
        case LOOP_BREAK -> convertedEvents.add(new MdsdrvEvent(MdsdrvEvent.LPB, 0));
        case LOOP_END -> convertedEvents.add(new MdsdrvEvent(MdsdrvEvent.LPF, param));
        case SEGNO -> {
            inLoop = true;
            convertedEvents.add(new MdsdrvEvent(MdsdrvEvent.SEGNO, 0));
        }
        case JUMP -> {
            try {
                convertedEvents.add(new MdsdrvEvent(MdsdrvEvent.PAT,
                        mdsdrv.getSubroutine(param, false, drumModeEnabled)));
            } catch (NoSuchElementException e) {
                error("MDSDRV: Subroutine *%d doesn't exist".formatted(event.param));
            }
        }
        case END -> {
            // Gracefully handle infinite loop at the end of a track
            if (getPlayTime() == getLoopPlayTime()) {
                inLoop = false;
            }
            if (inLoop) {
                convertedEvents.add(new MdsdrvEvent(MdsdrvEvent.JUMP, 0));
            } else {
                convertedEvents.add(new MdsdrvEvent(MdsdrvEvent.FINISH, 0));
            }
        }
        case SLUR -> convertedEvents.add(new MdsdrvEvent(MdsdrvEvent.SLR, 0));
        case PLATFORM -> {
            try {
                List<String> tag = mdsdrv.getSong().getPlatformCommand(event.param);
                parsePlatformEvent(tag);
            } catch (NoSuchElementException e) {
                error("MDSDRV: Platform command %d is not defined".formatted(event.param));
            }
        }
        case TRANSPOSE_REL -> convertedEvents.add(new MdsdrvEvent(MdsdrvEvent.TRSM, param));
        case VOL -> convertedEvents.add(new MdsdrvEvent(MdsdrvEvent.VOL, param | 0x80));
        case VOL_REL, VOL_FINE_REL -> convertedEvents.add(new MdsdrvEvent(MdsdrvEvent.VOLM, param));
        case TEMPO_BPM -> convertedEvents.add(new MdsdrvEvent(MdsdrvEvent.TEMPO, bpmToDelta(param)));
        case INS -> {
            try {
                checkInstrument(param);
                if (mdsdrv.getData().getInsType(param) != MdsdrvData.InstrumentType.INS_PCM) {
                    convertedEvents.add(new MdsdrvEvent(MdsdrvEvent.INS,
                            mdsdrv.getEnvelope(mdsdrv.getData().getEnvelopeId(param))));
                } else {
                    convertedEvents.add(new MdsdrvEvent(MdsdrvEvent.PCM,
                            mdsdrv.getEnvelope(0x20000 + mdsdrv.getData().getEnvelopeId(param))));
                }
            } catch (NoSuchElementException e) {
                error("MDSDRV: Instrument @%d doesn't exist".formatted(event.param));
            }
        }
        case TRANSPOSE -> convertedEvents.add(new MdsdrvEvent(MdsdrvEvent.TRS, param));
        case DETUNE -> convertedEvents.add(new MdsdrvEvent(MdsdrvEvent.DTN, param));
        case VOL_FINE -> convertedEvents.add(new MdsdrvEvent(MdsdrvEvent.VOL, param & 0x7f));
        case PAN -> convertedEvents.add(new MdsdrvEvent(MdsdrvEvent.PAN, param << 6));
        case PAN_ENVELOPE -> {
            try {
                if (param != 0) {
                    param = mdsdrv.getMacroTrack(param) + 1;
                }
                convertedEvents.add(new MdsdrvEvent(MdsdrvEvent.MTAB, param));
            } catch (NoSuchElementException e) {
                error("MDSDRV: Macro track *%d doesn't exist".formatted(event.param));
            }
        }
        case PITCH_ENVELOPE -> {
            try {
                if (param != 0) {
                    if (mdsdrv.getData().pitchExtend.contains(param)) {
                        param = mdsdrv.getEnvelope(0x10000 + mdsdrv.getData().getPitchId(param)) + 1;
                    } else {
                        param = mdsdrv.getEnvelope(mdsdrv.getData().getPitchId(param)) + 1;
                    }
                }
                convertedEvents.add(new MdsdrvEvent(MdsdrvEvent.PEG, param));
            } catch (NoSuchElementException e) {
                error("MDSDRV: Pitch envelope @M%d doesn't exist".formatted(event.param));
            }
        }
        case PORTAMENTO -> convertedEvents.add(new MdsdrvEvent(MdsdrvEvent.PTA, param));
        case DRUM_MODE -> {
            drumModeEnabled = param != 0;
            if (param != 0) {
                param = 0 + 8;
            } else {
                param = 0;
            }
            convertedEvents.add(new MdsdrvEvent(MdsdrvEvent.FLG, param));
        }
        case TEMPO -> convertedEvents.add(new MdsdrvEvent(MdsdrvEvent.TEMPO, param));
        default -> {
        }
        }
    }

    @Override
    protected boolean loopHook() {
        return false;
    }

    @Override
    protected void endHook() {
        if (restTime != 0) {
            convertedEvents.add(new MdsdrvEvent(MdsdrvEvent.REST, restTime));
            restTime = 0;
        }

        if (inLoop) {
            convertedEvents.add(new MdsdrvEvent(MdsdrvEvent.JUMP, 0));
        } else {
            convertedEvents.add(new MdsdrvEvent(MdsdrvEvent.FINISH, 0));
        }
    }

    private void parsePlatformEvent(List<String> tag) {
        if (CType.iequal(tag.get(0), "mode")) { // PSG noise mode
            if (tag.size() < 2) {
                error("not enough parameters for 'mode' command");
            }
            int param = (int) strtol(tag.get(1)) & 0xffff;
            if (param == 1) {
                param = 0xe7;
            } else if (param == 2) {
                param = 0xe3;
            } else {
                param = 0x100; // Should be replaced with 00 in the actual file
            }
            convertedEvents.add(new MdsdrvEvent(MdsdrvEvent.LFO, param));
        } else if (CType.iequal(tag.get(0), "lfo")) { // LFO depth
            if (tag.size() < 3) {
                error("not enough parameters for 'lfo' command");
            }
            convertedEvents.add(new MdsdrvEvent(MdsdrvEvent.LFO,
                    ((int) strtol(tag.get(1)) << 4) | ((int) strtol(tag.get(2)) & 0x3f)));
        } else if (CType.iequal(tag.get(0), "lforate")) { // LFO rate
            if (tag.size() < 2) {
                error("not enough parameters for 'lforate' command");
            }
            int param = (int) strtol(tag.get(1)) & 0xff;
            if (param != 0) {
                param = (param + 7) & 0xff;
            }
            convertedEvents.add(new MdsdrvEvent(MdsdrvEvent.FMREG, 0x2200 | param));
        } else if (CType.iequal(tag.get(0), "fm3")) { // FM3 mode
            if (tag.size() < 2) {
                error("not enough parameters for 'fm3' command");
            }
            convertedEvents.add(new MdsdrvEvent(MdsdrvEvent.FLG,
                    0x80 | ((int) new Cursor(tag.get(1)).strtol(2) ^ 0x0f) & 0x0f));
        } else if (CType.iequal(tag.get(0), "write")) { // FM register write
            if (tag.size() < 3) {
                error("not enough parameters for 'write' command");
            }
            int writeAddr = (int) strtol(tag.get(1)) & 0xff;
            int writeData = (writeAddr << 8) | ((int) strtol(tag.get(2)) & 0xff);
            if (writeAddr >= 0x30) {
                convertedEvents.add(new MdsdrvEvent(MdsdrvEvent.FMCREG, writeData));
            } else {
                convertedEvents.add(new MdsdrvEvent(MdsdrvEvent.FMREG, writeData));
            }
        } else if (CType.iequal(tag.get(0), "pcmrate")) { // PCM channel sample rate
            if (tag.size() < 2) {
                error("not enough parameters for 'pcmrate' command");
            }
            int data = (int) strtol(tag.get(1)) & 0xff;
            if (data < 1 || data > 8) {
                error("pcmrate argument must be between 1 and 8");
            }
            convertedEvents.add(new MdsdrvEvent(MdsdrvEvent.PCMRATE, data));
        } else if (CType.iequal(tag.get(0), "pcmmode")) { // PCM mixing mode
            if (tag.size() < 2) {
                error("not enough parameters for 'pcmmode' command");
            }
            int data = (int) strtol(tag.get(1)) & 0xff;
            if (data < 2 || data > 3) {
                error("pcmmode argument must be between 2 and 3");
            }
            convertedEvents.add(new MdsdrvEvent(MdsdrvEvent.PCMMODE, data));
        } else if (CType.iequal(tag.get(0), "cmd")) { // Direct command
            if (tag.size() < 2) {
                error("not enough parameters for 'cmd' command");
            }
            int type = (int) strtol(tag.get(1));
            int data = 0;
            if (tag.size() > 2) {
                data = (int) strtol(tag.get(2)) & 0xffff;
            }
            convertedEvents.add(new MdsdrvEvent(type, data));
        } else if (CType.iequal(tag.get(0), "carry")) {
            convertedEvents.add(new MdsdrvEvent(MdsdrvEvent.CARRY, 0));
        } else if (getRegister(tag.get(0)) != 0) {
            if (tag.size() < 2) {
                error("not enough parameters for 'write' command");
            }

            int reg = getRegister(tag.get(0));
            int data = ((reg << 8) | ((int) strtol(tag.get(1)) & 0xff)) & 0xffff;

            if (reg >= 0xfc && !tag.get(1).isEmpty()) {
                data = (data - 0xfc00) & 0xffff;
                char sign = tag.get(1).charAt(0);
                if (sign == '+' || sign == '-') {
                    convertedEvents.add(new MdsdrvEvent(MdsdrvEvent.FMTLM, data));
                } else {
                    convertedEvents.add(new MdsdrvEvent(MdsdrvEvent.FMTL, data));
                }
            } else if (reg >= 0x30) {
                convertedEvents.add(new MdsdrvEvent(MdsdrvEvent.FMCREG, data));
            } else {
                convertedEvents.add(new MdsdrvEvent(MdsdrvEvent.FMREG, data));
            }
        }
    }

    /** Converts BPM to fractional tempo. */
    private int bpmToDelta(int bpm) {
        double baseTempo = 120. / (mdsdrv.getSong().getPpqn() * (1. / 60.));
        double fract = (bpm / baseTempo) * 256.;
        // the original truncates the whole expression when storing it into a uint16_t
        int newTempo = (int) (fract + 0.5 - 1) & 0xffff;
        return Math.min(0xff, newTempo);
    }

    /** Checks if the instrument type is valid for the specified track. */
    private void checkInstrument(int param) {
        if (mdsdrv.getData().insType.containsKey(param)) {
            String[] strings = {"undefined", "psg", "fm", "pcm"};
            MdsdrvData.InstrumentType type = mdsdrv.getData().insType.get(param);

            if ((trackId >= 0 && trackId < 5 && type != MdsdrvData.InstrumentType.INS_FM) // FM instruments
                    || (trackId == 5 && type != MdsdrvData.InstrumentType.INS_FM
                        && type != MdsdrvData.InstrumentType.INS_PCM)) {
                error("MDSDRV: instrument @%d has wrong type (%s) for FM track %c"
                        .formatted(param, strings[type.ordinal()], (char) (trackId + 'A')));
            } else if (trackId >= 6 && trackId < 10 && type != MdsdrvData.InstrumentType.INS_PSG) { // PSG
                error("MDSDRV: instrument @%d has wrong type (%s) for PSG track %c"
                        .formatted(param, strings[type.ordinal()], (char) (trackId + 'A')));
            } else if (trackId >= 10 && trackId < 12 && type != MdsdrvData.InstrumentType.INS_PCM) { // PCM
                error("MDSDRV: instrument @%d has wrong type (%s) for PCM track %c"
                        .formatted(param, strings[type.ordinal()], (char) (trackId + 'A')));
            }
        }
    }

    /** {@code strtol(s, 0, 0)}: auto-detects the base. */
    private static long strtol(String s) {
        return new Cursor(s).strtol(0);
    }
}
