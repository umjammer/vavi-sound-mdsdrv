/*
 * Copyright (c) 2026 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.sound.ctrmml;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.TreeMap;


/**
 * MML (Music Macro Language) parser class.
 * <p>
 * For more info about the MML dialect used here, see the
 * <a href="https://github.com/superctr/ctrmml/blob/master/mml_ref.md">MML reference</a>.
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 2026-08-02 nsano initial version <br>
 * @see <a href="https://github.com/superctr/ctrmml">ctrmml</a> src/mml_input.cpp
 */
public class MmlInput extends LineInput {

    /** {@code abcdefgh} */
    private static final int[] NOTE_VALUES = {9, 11, 0, 2, 4, 5, 7, 11};

    /** The command a bare continuation line is forwarded to. */
    private enum LastCmd {
        PARSE_MML, PARSE_TAG
    }

    private String tagKey = "";
    private Track track;
    private int trackId;
    private int trackOffset;
    private final List<Integer> trackList = new ArrayList<>();
    private LastCmd lastCmd;
    private boolean conditionalBlock;

    public MmlInput(Song song) {
        super(song);
        // Perhaps the initial state of mml_input should be track A.
        // Or maybe it can be initialized by a previous MmlInput during an "include" command.
    }

    /** Get a list of tracks that were affected by the previous {@link #readLine}. */
    public Map<Integer, Integer> getTrackMap() {
        Map<Integer, Integer> out = new TreeMap<>();
        if (lastCmd == LastCmd.PARSE_MML) {
            for (int i : trackList) {
                try {
                    out.put(i, getSong().getTrack(i).getEventCount());
                } catch (NoSuchElementException e) {
                    // track doesn't exist; no events added
                }
            }
        }
        return out;
    }

    // MML read helpers

    private int readDuration() {
        int duration = 0;
        int dot;
        try {
            int c = get();
            if (c == ':') {
                duration = getNum();
            } else {
                unget(c);
                int div = getNum();
                if (div < 1) {
                    parseError("illegal duration");
                }
                duration = track.getMeasureLen() / div;
            }
            if (duration < 0) {
                parseError("illegal duration");
            }
        } catch (LineBuffer.InvalidArgumentException e) {
            duration = track.getDuration();
        }
        dot = duration >> 1;
        while (true) {
            if (get() == '.') {
                duration += dot;
                dot >>= 1;
            } else {
                unget();
                break;
            }
        }
        return duration;
    }

    private int readParameter(int defaultParameter) {
        try {
            return getNum();
        } catch (LineBuffer.InvalidArgumentException e) {
            return defaultParameter;
        }
    }

    private int expectParameter() {
        try {
            return getNum();
        } catch (LineBuffer.InvalidArgumentException e) {
            parseError("missing parameter");
            return 0;
        }
    }

    private int expectSigned() {
        // is this function necessary anymore?
        return expectParameter();
    }

    /** @param c the first character */
    private int readNote(int c) {
        // would be nice to support key signatures...
        int val = (byte) (c - 'a');
        int sig = 0;
        // linear if in drum mode
        if (!track.inDrumMode()) {
            val = NOTE_VALUES[val & 7];
            sig = track.getKeySignature((char) c);
        }
        // sharps/flats
        c = get();
        if (c == '+') {
            sig = 1;
        } else if (c == '-') {
            sig = -1;
        } else if (c == '=') {
            sig = 0;
        } else {
            unget(c);
        }
        return val + sig;
    }

    /** Platform-exclusive messages ({@code '<key> <value> ...'}). */
    private void platformExclusive() {
        StringBuilder str = new StringBuilder();
        int c = get();
        while (c != 0 && c != '\'') {
            str.append((char) c);
            c = get();
        }
        if (c != '\'') {
            parseError("unterminated platform-exclusive message");
        }
        int param = getSong().registerPlatformCommand(-1, str.toString());
        track.addEvent(Event.Type.PLATFORM, param);
    }

    // Wrappers that provide error/warning messages or other functions

    private void mmlSlur() {
        if (track.addSlur() != 0) {
            parseWarning("slur may not affect articulation of previous note");
        }
    }

    private void mmlReverseRest(int duration) {
        try {
            track.reverseRest(duration);
        } catch (Track.DomainException e) {
            parseError("unable to backtrack");
        } catch (Track.LengthException e) {
            parseError("previous note is not long enough");
        }
    }

    private void mmlGrace() {
        int c = readNote(getToken());
        int duration = readDuration();
        mmlReverseRest(duration);
        track.addNote(c, duration);
    }

    private void mmlTranspose() {
        int c = getToken();
        if (c == '_') {
            track.addEvent(Event.Type.TRANSPOSE_REL, expectSigned());
        } else if (c == '{') {
            try {
                // Read key signature
                StringBuilder str = new StringBuilder();
                do {
                    c = get();
                    if (c != 0 && c != '}' && !CType.isSpace(c)) {
                        str.append((char) c);
                    }
                } while (c != 0 && c != '}');
                track.setKeySignature(str.toString());
            } catch (IllegalArgumentException e) {
                parseError("invalid key signature");
            }
        } else {
            unget();
            track.addEvent(Event.Type.TRANSPOSE, expectSigned());
        }
    }

    /** mucom88 style echo command. */
    private void mmlEcho() {
        int c = getToken();
        if (c == '=') {
            int delay = (short) expectParameter();
            if (delay < 0) {
                delay = -delay;
            } else {
                track.clearEchoBuffer();
            }
            if (getToken() != ',') {
                parseError("expected ','");
            }
            int volume = expectParameter() & 0xffff;
            track.setEcho(delay, volume);
        } else {
            unget();
            track.addEcho(readDuration());
        }
    }

    /**
     * Combination command that allows for two {@link Event.Type} depending on if a sign prefix
     * is found.
     */
    private void eventRelative(Event.Type type, Event.Type subtype) {
        int c = getToken();
        if (c == '+' || c == '-') {
            type = subtype;
        }
        if (c != '+') {
            unget();
        }
        if (type == null) {
            parseError("parameter must be relative (+ or - prefix)");
        }
        track.addEvent(type, expectParameter());
    }

    // MML command parsers. Return false and increment position if parsing succeeds.
    // The idea is that these can be swapped out for different MML dialects or platforms.

    /**
     * Basic MML command parser. Commands defined here are the ones unlikely to change in
     * different MML dialects. Notes, length, octave, etc.
     */
    private boolean mmlBasic() {
        int c = getToken();
        if (c >= 'a' && c <= 'h') {
            c = readNote(c);
            track.addNote(c, readDuration());
        } else if (c == 'r') {
            track.addRest(readDuration());
        } else if (c == '^') {
            track.addTie(readDuration());
        } else if (c == '&') {
            mmlSlur();
        } else if (c == 'o') {
            track.setOctave(expectParameter() - 1);
        } else if (c == '<') {
            track.changeOctave(-1);
        } else if (c == '>') {
            track.changeOctave(1);
        } else if (c == 'l') {
            track.setDuration(readDuration());
        } else if (c == 'Q') {
            track.setQuantize(expectParameter());
        } else if (c == 'q') {
            track.setEarlyRelease(expectParameter());
        } else if (c == 'R') {
            mmlReverseRest(readDuration());
        } else if (c == '~') {
            mmlGrace();
        } else if (c == 'C') {
            track.setMeasureLen(expectParameter());
        } else if (c == 's') {
            track.setShuffle(expectSigned());
        } else if (c == '\\') {
            mmlEcho();
        } else {
            unget(c);
            return true;
        }
        return false;
    }

    /** Loop control. */
    private boolean mmlControl() {
        int c = getToken();
        if (c == '[') {
            track.addEvent(Event.Type.LOOP_START);
        } else if (c == '/') {
            track.addEvent(Event.Type.LOOP_BREAK);
        } else if (c == ']') {
            track.addEvent(Event.Type.LOOP_END, readParameter(2));
        } else if (c == 'L') {
            track.addEvent(Event.Type.SEGNO);
        } else if (c == '*') {
            track.addEvent(Event.Type.JUMP, expectParameter());
        } else if (c == '\'') {
            platformExclusive();
        } else {
            unget(c);
            return true;
        }
        return false;
    }

    /** Dialect specific MML commands. Instrument, volume, envelope etc. */
    private boolean mmlEnvelope() {
        int c = getToken();
        if (c == '@') {
            track.addEvent(Event.Type.INS, expectParameter());
        } else if (c == '_') {
            mmlTranspose();
        } else if (c == 'k') { // TODO: ktype command to set compile-time transpose?
            mmlTranspose();
        } else if (c == 'K') {
            track.addEvent(Event.Type.DETUNE, expectSigned());
        } else if (c == 'v') {
            track.addEvent(Event.Type.VOL, expectParameter());
        } else if (c == '(') {
            track.addEvent(Event.Type.VOL_REL, -readParameter(1));
        } else if (c == ')') {
            track.addEvent(Event.Type.VOL_REL, readParameter(1));
        } else if (c == 'V') {
            eventRelative(Event.Type.VOL_FINE, Event.Type.VOL_FINE_REL);
        } else if (c == 'p') {
            track.addEvent(Event.Type.PAN, expectSigned());
        } else if (c == 'E') {
            track.addEvent(Event.Type.VOL_ENVELOPE, expectParameter());
        } else if (c == 'M') {
            track.addEvent(Event.Type.PITCH_ENVELOPE, expectParameter());
        } else if (c == 'P') {
            track.addEvent(Event.Type.PAN_ENVELOPE, expectParameter());
        } else if (c == 'G') {
            track.addEvent(Event.Type.PORTAMENTO, expectParameter());
        } else if (c == 'D') {
            track.setDrumMode(expectParameter());
        } else if (c == 't') {
            track.addEvent(Event.Type.TEMPO_BPM, expectParameter());
        } else if (c == 'T') {
            track.addEvent(Event.Type.TEMPO, expectParameter());
        } else {
            unget(c);
            return true;
        }
        return false;
    }

    private void conditionalBlockBegin() {
        int c;
        int offset = trackOffset;
        conditionalBlock = true;
        while (offset != 0) {
            do {
                c = getToken();
            } while (c != 0 && c != '/' && c != ';');
            if (c != '/') {
                parseError("unterminated conditonal block");
            }
            offset--;
        }
    }

    private void conditionalBlockEnd(int c) {
        while (c != 0 && c != '}' && c != ';') {
            c = getToken();
        }
        if (c != '}') {
            parseError("unterminated conditional block");
        }
        conditionalBlock = false;
    }

    // Parsers for various parts of the MML file

    private void parseMmlTrack() {
        int c;
        while (true) {
            c = getToken();
            if (c == '|') { // Separator
                continue;
            } else if (c == ';') { // Comment
                break;
            } else if ((c == '/' || c == '}') && conditionalBlock) {
                conditionalBlockEnd(c);
            } else if (c == '{' && !conditionalBlock) {
                conditionalBlockBegin();
            } else if (c == '%') {
                track.addEvent(Event.Type.PLATFORM, expectParameter());
            } else if (c == 0) {
                return;
            } else {
                unget(c);
                // Set reference
                track.setReference(getReference());
                // Here i can read a list of command handlers and call them until one returns false
                if (!mmlBasic()) {
                    continue;
                } else if (!mmlControl()) {
                    continue;
                } else if (!mmlEnvelope()) {
                    continue;
                }
                parseError("unknown MML command");
            }
        }
    }

    private void parseMml() {
        int col = tell();
        for (int i = 0; i < trackList.size(); i++) {
            seek(col);
            trackId = trackList.get(i);
            trackOffset = i;
            track = getSong().makeTrack(trackId);
            conditionalBlock = false;
            parseMmlTrack();
            if (conditionalBlock) {
                parseError("unterminated conditional block");
            }
        }
    }

    private void parseTag() {
        if (tagKey.charAt(0) == '#') {
            // Special cases for "include" etc commands go here
            // #platform = set output format
            // #format = set MML format. Handle internally
            if (CType.iequal(tagKey, "#platform")) {
                getSong().setPlatform(getLine());
            } else {
                getSong().setTag(tagKey, getLine());
            }
            lastCmd = null; // Only read a single line
        } else {
            getSong().addTagList(tagKey, getLine());
        }
    }

    /** Convert track id from character. May throw {@link LineBuffer.InvalidArgumentException}. */
    private int getTrackId() {
        int c = get();
        if (c >= 'A' && c <= 'Z') {
            return c - 'A';
        } else if (CType.isDigit(c)) {
            return c - '0' + 26;
        } else if (c == '*') {
            return getNum();
        }
        // No match
        unget(c);
        return -1;
    }

    @Override
    protected void parseLine() {
        int c = getTrackId();
        if (c != -1) {
            // Read track list
            trackList.clear();
            do {
                trackList.add(c);
                c = getTrackId();
            } while (c != -1);
            lastCmd = LastCmd.PARSE_MML;
        } else {
            c = get();
            if (c == '#' || c == '@') {
                // This could maybe be more efficient
                StringBuilder key = new StringBuilder();
                do {
                    key.append((char) CType.toLower(c));
                    c = get();
                } while (c != 0 && !CType.isSpace(c));
                tagKey = key.toString();
                lastCmd = LastCmd.PARSE_TAG;
            } else if (c == ';') {
                // Comment
                return;
            } else if (!CType.isBlank(c)) {
                // Not at the end of the line
                if (c != 0) {
                    parseError("Expected track or tag identifier");
                }
                // At the end of the line, we can stop parsing
                return;
            }
            unget(c);
        }

        c = get();
        if (CType.isBlank(c)) {
            // Skip non blank characters
            c = getToken();
            unget(c);
            if (c == 0) {
                return;
            }
            if (lastCmd != null) {
                switch (lastCmd) {
                case PARSE_MML -> parseMml();
                case PARSE_TAG -> parseTag();
                }
            }
        }
    }
}
