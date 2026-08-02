/*
 * Copyright (c) 2026 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.sound.ctrmml;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;


/**
 * Track structure.
 * <p>
 * Each song consists of an indeterminate number of tracks which are used for channels as
 * well as individual phrases (subroutines) that may be referenced by a channel.
 * <p>
 * The methods of this class facilitate easier insertion of events to a track.
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 2026-08-02 nsano initial version <br>
 * @see <a href="https://github.com/superctr/ctrmml">ctrmml</a> src/track.cpp
 */
public class Track {

    /** Default octave setting. */
    public static final int DEFAULT_OCTAVE = 5;
    /** Default measure length (whole note duration). */
    public static final int DEFAULT_MEASURE_LEN = 96;
    /** Default quantize dividend. */
    public static final int DEFAULT_QUANTIZE = 8;
    /** Default quantize divisor. */
    public static final int DEFAULT_QUANTIZE_PARTS = 8;
    /** Maximum size of the echo buffer. */
    public static final int ECHO_BUFFER_SIZE = 10;

    /**
     * Key signatures, indexed by scale.
     * <pre>
     *   +hgfedcba   -hgfedcba  maj   min
     * </pre>
     */
    private static final Object[][] SCALES = {
            {0b0000_0000, 0b0000_0000, "C", "a"},
            {0b0010_0000, 0b0000_0000, "G", "e"},
            {0b0010_0100, 0b0000_0000, "D", "b"},
            {0b0110_0100, 0b0000_0000, "A", "f+"},
            {0b0110_1100, 0b0000_0000, "E", "c+"},
            {0b0110_1101, 0b0000_0000, "B", "g+"},
            {0b0111_1101, 0b0000_0000, "F+", "d+"},
            {0b0111_1111, 0b0000_0000, "C+", "a+"},
            {0b0000_0000, 0b0000_0010, "F", "d"},
            {0b0000_0000, 0b0001_0010, "B-", "g"},
            {0b0000_0000, 0b0001_0011, "E-", "c"},
            {0b0000_0000, 0b0001_1011, "A-", "f"},
            {0b0000_0000, 0b0101_1011, "D-", "b-"},
            {0b0000_0000, 0b0101_1111, "G-", "e-"},
            {0b0000_0000, 0b0111_1111, "C-", "a-"}
    };

    private boolean enabled;
    /** unsigned 16 bit */
    private int drumMode;
    private final List<Event> events = new ArrayList<>();
    /** last event id that was a note */
    private int lastNotePos = -1;
    private int octave = DEFAULT_OCTAVE;
    /** unsigned 16 bit */
    private int measureLen;
    /** default duration, unsigned 16 bit */
    private int defaultDuration;
    /** unsigned 16 bit */
    private int quantize = DEFAULT_QUANTIZE;
    /** unsigned 16 bit */
    private int quantizeParts = DEFAULT_QUANTIZE_PARTS;
    /** unsigned 16 bit */
    private int earlyRelease;
    /** signed 16 bit */
    private int shuffle;
    private int sharpMask;
    private int flatMask;
    /** unsigned 16 bit */
    private int echoDelay;
    /** signed 16 bit */
    private int echoVolume;
    private final Deque<Integer> echoBuffer = new ArrayDeque<>();
    private InputRef reference;

    /**
     * Constructs a Track.
     *
     * @param ppqn Pulses per quarter note, used to set the default duration and can also be
     *             used as a reference for input handlers.
     */
    public Track(int ppqn) {
        this.measureLen = (ppqn * 4) & 0xffff;
        this.defaultDuration = measureLen / 4;
    }

    public Track() {
        this(DEFAULT_MEASURE_LEN / 4);
    }

    // Methods that add Events

    /** Appends an Event to the event list. */
    public void addEvent(Event newEvent) {
        events.add(newEvent);
    }

    /** Appends a new Event to the event list. */
    public void addEvent(Event.Type type, int param, int onTime, int offTime) {
        events.add(new Event(type, param, onTime, offTime, Event.NO_PLAY_TIME, reference));
    }

    public void addEvent(Event.Type type, int param) {
        addEvent(type, param, 0, 0);
    }

    public void addEvent(Event.Type type) {
        addEvent(type, 0, 0, 0);
    }

    /**
     * Add an {@link Event.Type#NOTE} Event with specified parameter and duration.
     *
     * @param note     Note number (0-11). Modified by the octave number set by {@link #setOctave}.
     * @param duration Note duration. If 0, use the default duration set by {@link #setDuration}.
     */
    public void addNote(int note, int duration) {
        duration = addShuffle(getDuration(duration));
        shuffle = -shuffle;

        if (!inDrumMode()) {
            note += octave * 12;
        } else {
            note += drumMode;
        }

        pushEchoNote(note & 0xffff);

        lastNotePos = events.size();
        addEvent(Event.Type.NOTE, note, onTime(duration), offTime(duration));
    }

    /**
     * Extends the previous note.
     * <p>
     * If preceded by an {@link Event.Type#NOTE} or {@link Event.Type#REST}, simply extend the
     * {@code onTime} or {@code offTime} of the previous event. Otherwise, add a new
     * {@link Event.Type#TIE} Event with specified parameter and duration.
     *
     * @param duration Extend duration. If 0, use the default duration set by {@link #setDuration}.
     */
    public int addTie(int duration) {
        duration = addShuffle(getDuration(duration));
        shuffle = -shuffle;

        if (lastNotePos >= 0) {
            Event lastNote = getEvent(lastNotePos);
            int oldDuration = (lastNote.onTime + lastNote.offTime) & 0xffff;
            int newDuration = (oldDuration + duration) & 0xffff;
            int lastEventPos = events.size() - 1;

            if (lastNotePos == lastEventPos) {
                // last note event is the last event, we can just extend it.
                lastNote.onTime = onTime(newDuration);
                lastNote.offTime = offTime(newDuration);
                return 0;
            } else {
                // if another event has been inserted, it's important to keep the timing of that
                // event, so we change the on/off time of the last note, then insert a new event
                // for the extended duration. whether it's a tie or rest depends on the quantization.
                if (onTime(newDuration) > oldDuration) {
                    lastNotePos = events.size();
                    lastNote.onTime = oldDuration;
                    lastNote.offTime = 0;
                    addEvent(Event.Type.TIE, 0, onTime(newDuration) - oldDuration, offTime(newDuration));
                } else {
                    lastNotePos = -1; // only works once...
                    lastNote.onTime = onTime(newDuration);
                    lastNote.offTime = oldDuration - onTime(newDuration);
                    addEvent(Event.Type.REST, 0, 0, duration);
                }
                return 0;
            }
        } else {
            addEvent(Event.Type.TIE, 0, onTime(duration), offTime(duration));
            return 0;
        }
    }

    /**
     * Add an {@link Event.Type#REST} Event with specified duration.
     *
     * @param duration Rest duration. If 0, use the default duration set by {@link #setDuration}.
     */
    public void addRest(int duration) {
        duration = addShuffle(getDuration(duration));
        shuffle = -shuffle;

        pushEchoNote(0);

        addEvent(Event.Type.REST, 0, 0, duration);
    }

    /**
     * Connect two notes.
     * <p>
     * This is done by extending the previous note by setting it to legato, then adding an
     * {@link Event.Type#SLUR} event.
     *
     * @return -1 if unable to backtrack, 0 on success.
     */
    public int addSlur() {
        addEvent(Event.Type.SLUR);

        // backtrack to disable the articulation of the previous note.
        for (int i = events.size() - 1; i >= 0; i--) {
            Event it = events.get(i);
            switch (it.type) {
            case NOTE, TIE -> {
                it.onTime = (it.onTime + it.offTime) & 0xffff;
                it.offTime = 0;
                return 0;
            }
            // Don't bother reading past loop points as effects are unpredictable.
            case REST, SEGNO, LOOP_END -> {
                return -1;
            }
            default -> {
            }
            }
        }
        return -1;
    }

    /**
     * Add a note from the echo buffer.
     * <p>
     * Set the parameters with {@link #setEcho} before calling this. Adds a rest if the echo
     * delay parameter is 0 or larger than the echo buffer.
     */
    public void addEcho(int duration) {
        duration = addShuffle(getDuration(duration));
        shuffle = -shuffle;

        if (echoVolume != 0) {
            addEvent(Event.Type.VOL_REL, -echoVolume, 0, 0);
        }

        if (echoDelay == 0 || echoBuffer.size() < echoDelay) {
            addEvent(Event.Type.REST, 0, 0, duration);
        } else {
            int note = echoBufferAt(echoDelay - 1);
            if (note == 0) {
                addEvent(Event.Type.REST, 0, 0, duration);
            } else {
                lastNotePos = events.size();
                addEvent(Event.Type.NOTE, note, onTime(duration), offTime(duration));
            }
        }

        if (echoVolume != 0) {
            addEvent(Event.Type.VOL_REL, echoVolume, 0, 0);
        }
    }

    // Methods that modify previous Events

    /**
     * Backtrack in order to shorten previous event.
     * <p>
     * Note that the duration must not exceed the combined length of {@code onTime} and
     * {@code offTime} of the previous note, rest or tie.
     *
     * @param duration Number of ticks to subtract from the previous event.
     * @throws LengthException if requested duration is greater than the length of the previous event.
     * @throws DomainException if unable.
     */
    public void reverseRest(int duration) {
        // Undo shuffle (I guess this is the best behavior?)
        shuffle = -shuffle;

        // backtrack to disable the articulation of the previous note.
        for (int i = events.size() - 1; i >= 0; i--) {
            Event it = events.get(i);
            switch (it.type) {
            case NOTE, TIE, REST -> {
                // If the off_time is larger than the shorten duration, we will just use that.
                // Otherwise subtract from both off_time and on_time.
                if (duration > it.offTime) {
                    duration -= it.offTime;
                    if (duration < it.onTime) {
                        it.offTime = 0;
                        it.onTime -= duration;
                        return;
                    }
                    throw new LengthException("Track.reverseRest: previous duration not long enough");
                } else {
                    it.offTime -= duration;
                    return;
                }
            }
            // Don't bother reading past loop points as effects are unpredictable.
            case SEGNO, LOOP_END -> throw new DomainException("Track.reverseRest: unable to modify previous duration");
            default -> {
            }
            }
        }
        throw new DomainException("Track.reverseRest: there is no previous duration");
    }

    // Methods that modify following Events

    /**
     * Sets the reference to use for successive Events.
     * <p>
     * This is used to associate source files with events so that sensible error messages can
     * be generated outside the parser.
     */
    public void setReference(InputRef ref) {
        this.reference = ref;
    }

    /** Set the octave, affecting subsequent calls to {@link #addNote}. */
    public void setOctave(int param) {
        octave = param;
    }

    /** Modify the octave, affecting subsequent calls to {@link #addNote}. */
    public void changeOctave(int param) {
        octave += param;
    }

    /** Set the default duration, in ticks. */
    public void setDuration(int param) {
        defaultDuration = param & 0xffff;
    }

    /**
     * Set quantization time.
     * <p>
     * Sets the {@link Event#onTime} of the next note events to {@code param} divided by
     * {@code parts}. Replaces the early release setting.
     */
    public int setQuantize(int param, int parts) {
        if (param > parts || parts == 0) {
            return -1;
        }
        if (param == 0) {
            param = parts;
        }
        quantize = param;
        quantizeParts = parts;
        earlyRelease = 0;
        return 0;
    }

    public int setQuantize(int param) {
        return setQuantize(param, 8);
    }

    /**
     * Set early release time.
     * <p>
     * Sets the {@link Event#onTime} of the next note events to {@code param}. Replaces the
     * quantization setting.
     */
    public void setEarlyRelease(int param) {
        quantize = quantizeParts;
        earlyRelease = param & 0xffff;
    }

    /**
     * Set the drum mode parameters.
     *
     * @param param Drum mode note offset. If 0, drum mode is disabled.
     */
    public void setDrumMode(int param) {
        drumMode = param & 0xffff;
        addEvent(Event.Type.DRUM_MODE, param);
    }

    /**
     * Set the echo parameters.
     *
     * @param delay  Note offset in the buffer.
     * @param volume Volume reduction.
     */
    public void setEcho(int delay, int volume) {
        if (delay > ECHO_BUFFER_SIZE) {
            delay = ECHO_BUFFER_SIZE;
        }
        echoDelay = delay & 0xffff;
        echoVolume = (short) volume;
    }

    /** Clear the contents of the echo buffer. */
    public void clearEchoBuffer() {
        echoBuffer.clear();
    }

    // Methods to retrieve Events

    /** Get the events list. */
    public List<Event> getEvents() {
        return events;
    }

    /**
     * Get the Event at the specified position.
     *
     * @throws IndexOutOfBoundsException if position exceeds event count.
     */
    public Event getEvent(int position) {
        if (position < 0 || position >= events.size()) {
            throw new IndexOutOfBoundsException(position + " / " + events.size());
        }
        return events.get(position);
    }

    /** Get the total number of events in the track. */
    public int getEventCount() {
        return events.size();
    }

    // Methods that set Track state

    /**
     * Set the length of a whole note.
     * <p>
     * The measure length is not internally used by the track writer, but it is stored with the
     * track state as a convenience to MIDI or MML parsers.
     */
    public void setMeasureLen(int param) {
        measureLen = param & 0xffff;
    }

    /** Set the shuffle modifier. */
    public void setShuffle(int param) {
        shuffle = (short) param;
    }

    /**
     * Set the key signature.
     * <p>
     * The string can be formatted in one of two forms:
     * <ul>
     * <li>using the name of a scale. Lower case indicates a minor scale, upper case indicates a
     * major scale. Using this method will reset the key signature.</li>
     * <li>adding a sharp ("+"), flat ("-"), or natural ("=") sign followed by a list of notes
     * that are to be modified.</li>
     * </ul>
     *
     * @throws IllegalArgumentException Scale or modifier string is invalid.
     */
    public void setKeySignature(String key) {
        int k = key.isEmpty() ? 0 : key.charAt(0);
        int modifier = 0;
        if (CType.isAlpha(k)) {
            for (Object[] scale : SCALES) {
                if (scale[2].equals(key) || scale[3].equals(key)) {
                    sharpMask = (Integer) scale[0];
                    flatMask = (Integer) scale[1];
                    return;
                }
            }
            throw new IllegalArgumentException("Track.setKeySignature : unknown key");
        } else {
            // note: this reproduces the original pointer arithmetic, where the first character
            // is evaluated twice and the terminating NUL ends the loop.
            int index = 0;
            do {
                if (k == '+') {
                    modifier = 1;
                } else if (k == '-') {
                    modifier = -1;
                } else if (k == '=') {
                    modifier = 0;
                } else if (CType.isAlpha(k)) {
                    modifyKeySignature((char) k, modifier);
                } else {
                    throw new IllegalArgumentException("Track.setKeySignature : unknown modifier");
                }
                k = index < key.length() ? key.charAt(index) : 0;
                index++;
            } while (k != 0);
        }
    }

    /**
     * Modify the key signature.
     * <p>
     * This will directly modify the key signature modifier for the given note.
     *
     * @param modifier must be either -1, 0 or 1.
     * @throws IllegalArgumentException Note or modifier parameter is invalid.
     */
    public void modifyKeySignature(char note, int modifier) {
        int n = CType.toLower(note) - 'a';
        if (n > 7) {
            throw new IllegalArgumentException("Track.modifyKeySignature - invalid note");
        }

        sharpMask &= ~(1 << n);
        flatMask &= ~(1 << n);
        if (modifier == 1) {
            sharpMask |= (1 << n);
        } else if (modifier == -1) {
            flatMask |= (1 << n);
        } else if (modifier != 0) {
            throw new IllegalArgumentException("Track.modifyKeySignature - invalid modifier");
        }
    }

    // Methods to get Track state

    /**
     * Return true if track is enabled.
     * <p>
     * It is to be used as a convenience by players to signify that a track has been marked as
     * used by the input routine.
     */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Enables the track.
     * <p>
     * It is to be used as a convenience by input routines to signify that a track is to be
     * included in the final output.
     */
    public void enable() {
        enabled = true;
    }

    /** Returns the drum mode status. */
    public boolean inDrumMode() {
        return drumMode != 0;
    }

    /**
     * Get the default duration.
     *
     * @param duration If 0, get the default duration as set by {@link #setDuration}. Otherwise
     *                 use the param value.
     */
    public int getDuration(int duration) {
        return duration == 0 ? defaultDuration : duration;
    }

    public int getDuration() {
        return getDuration(0);
    }

    /** Get the length of a whole note. */
    public int getMeasureLen() {
        return measureLen;
    }

    /** Get the current shuffle modifier. */
    public int getShuffle() {
        return shuffle;
    }

    /**
     * Get the key signature modifier for the note.
     *
     * @throws IllegalArgumentException Note is invalid.
     */
    public int getKeySignature(char note) {
        int n = CType.toLower(note) - 'a';
        if (n > 7) {
            throw new IllegalArgumentException("Track.getKeySignature - invalid note");
        }

        if ((sharpMask & (1 << n)) != 0) {
            return 1;
        } else if ((flatMask & (1 << n)) != 0) {
            return -1;
        }
        return 0;
    }

    public int getEchoDelay() {
        return echoDelay;
    }

    public int getEchoVolume() {
        return echoVolume;
    }

    // private

    /** Get {@link Event#onTime} after quantization. */
    private int onTime(int duration) {
        if (earlyRelease != 0) {
            if (earlyRelease >= duration) {
                return 1;
            } else {
                return (duration - earlyRelease) & 0xffff;
            }
        }
        return ((duration * quantize) / quantizeParts) & 0xffff;
    }

    /** Get {@link Event#offTime} after quantization. */
    private int offTime(int duration) {
        return (duration - onTime(duration)) & 0xffff;
    }

    /** Add shuffle, with underflow clamping. */
    private int addShuffle(int duration) {
        if ((duration + shuffle) < 0) {
            return 0;
        } else {
            return (duration + shuffle) & 0xffff;
        }
    }

    /** Push notes to the echo buffer. */
    private void pushEchoNote(int note) {
        echoBuffer.addFirst(note);
        if (echoBuffer.size() > ECHO_BUFFER_SIZE) {
            echoBuffer.removeLast();
        }
    }

    private int echoBufferAt(int index) {
        int i = 0;
        for (int note : echoBuffer) {
            if (i++ == index) {
                return note;
            }
        }
        throw new IndexOutOfBoundsException(index);
    }

    /** {@code std::length_error} */
    public static class LengthException extends RuntimeException {
        public LengthException(String message) {
            super(message);
        }
    }

    /** {@code std::domain_error} */
    public static class DomainException extends RuntimeException {
        public DomainException(String message) {
            super(message);
        }
    }
}
