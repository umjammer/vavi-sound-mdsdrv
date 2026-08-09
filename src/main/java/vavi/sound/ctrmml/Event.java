/*
 * Copyright (c) 2026 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.sound.ctrmml;


/**
 * Track event.
 * <p>
 * A track event is analogous to a MIDI message.
 * <p>
 * The length of the event is defined with {@link #onTime} and {@link #offTime}.
 * <p>
 * There are multiple types of events. An event of type {@link Type#REST} can have an
 * {@code offTime} only. {@link Type#NOTE} and {@link Type#TIE} events can have an
 * {@code onTime} and {@code offTime}. The total length of the event is the sum of those
 * lengths.
 * <p>
 * All other kinds of events are immediate and both the {@code onTime} and {@code offTime}
 * must be 0.
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 2026-08-02 nsano initial version <br>
 * @see <a href="https://github.com/superctr/ctrmml">ctrmml</a> src/track.h
 */
public final class Event implements Cloneable {

    /** Event types. The ordinals are part of the format and must not be reordered. */
    public enum Type {
        // Basic events
        /** Does nothing and ignores all parameters. */
        NOP,
        /** Key off. Reads {@link #offTime}. */
        REST,
        /** Key on, {@link #param} defines note. Reads {@link #onTime} and {@link #offTime}. */
        NOTE,
        /** Extends the previous note or rest. Reads {@link #onTime} and {@link #offTime}. */
        TIE,

        // Track events
        /** Start of a loop block. */
        LOOP_START,
        /** At the last iteration, skip to the end of the loop block. */
        LOOP_BREAK,
        /** End of the loop block. {@link #param} defines loop count. */
        LOOP_END,
        /** Set the track loop position. */
        SEGNO,
        /** Jump to a track. {@link #param} specifies the track number. Previous position is stored in stack. */
        JUMP,
        /** Jump to the stack position, alternatively the loop position, alternatively stops the track. */
        END,
        /** Indicates that the next note is legato. */
        SLUR,
        /** Platform-specific commands, the parameter is associated with a tag from the song data. */
        PLATFORM,

        // Special channel events. These affect the same memory as another command.
        /** Relative transpose. */
        TRANSPOSE_REL,
        /** Coarse volume. {@link #param} must be between 0-15, normally corresponding to -2dB per step. */
        VOL,
        /** Relative coarse volume. */
        VOL_REL,
        /** Relative fine volume. Platform-specific. */
        VOL_FINE_REL,
        /** Set tempo in quarter notes per minute. */
        TEMPO_BPM,

        // Channel events
        /** Set instrument. */
        INS,
        /** Set transpose. */
        TRANSPOSE,
        /** Set detune. */
        DETUNE,
        /** Fine volume. Platform-specific. */
        VOL_FINE,
        /** Set panning. Signed parameter. */
        PAN,
        /** Set volume envelope ID. */
        VOL_ENVELOPE,
        /** Set pitch envelope ID. */
        PITCH_ENVELOPE,
        /** Set pan envelope ID. */
        PAN_ENVELOPE,
        /** Set portamento. */
        PORTAMENTO,
        /** Set drum mode. */
        DRUM_MODE,
        /** Set platform-specific tempo. */
        TEMPO;

        /** Command ID count. */
        static final int CMD_COUNT = values().length;
        /** First channel cmd ID. */
        static final int CHANNEL_CMD = INS.ordinal();
        /** Channel command count. */
        static final int CHANNEL_CMD_COUNT = CMD_COUNT - CHANNEL_CMD;

        /** @return true if this is a channel command (i.e. it has a slot in the track state array) */
        boolean isChannelCmd() {
            return ordinal() >= CHANNEL_CMD && ordinal() < CMD_COUNT;
        }
    }

    /** Sentinel used for a not-yet-played event, {@code UINT_MAX} in the original. */
    static final long NO_PLAY_TIME = 0xffff_ffffL;

    /** The event type. */
    public Type type;
    /** Optional parameter. Signed 16 bit. */
    public int param;
    /** Key-on time (for {@link Type#NOTE} and {@link Type#TIE} types only). Unsigned 16 bit. */
    int onTime;
    /** Key-off time (for {@link Type#NOTE}, {@link Type#REST} and {@link Type#TIE} types only). Unsigned 16 bit. */
    int offTime;
    /** Set by a Player to help look up the play time of an event. */
    long playTime;
    /** Pointer to an input file reference. */
    public final InputRef reference;

    public Event(Type type, int param, int onTime, int offTime, long playTime, InputRef reference) {
        this.type = type;
        this.param = (short) param;
        this.onTime = onTime & 0xffff;
        this.offTime = offTime & 0xffff;
        this.playTime = playTime;
        this.reference = reference;
    }

    @Override
    public Event clone() {
        return new Event(type, param, onTime, offTime, playTime, reference);
    }

    @Override
    public String toString() {
        return "%s(%d) on=%d off=%d".formatted(type, param, onTime, offTime);
    }
}
