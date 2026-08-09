/*
 * Copyright (c) 2026 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.sound.ctrmml;


/**
 * Player stack frame.
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 2026-08-02 nsano initial version <br>
 * @see <a href="https://github.com/superctr/ctrmml">ctrmml</a> src/player.h
 */
public class PlayerStack {

    /** Defines the type of stack frame. */
    public enum Type {
        LOOP, JUMP, DRUM_MODE;

        public static final int MAX_STACK_TYPE = values().length;
    }

    public final Type type;
    /** Referenced track. */
    public final Track track;
    /** Event position. */
    public final int position;
    /** If {@link Type#LOOP}, points to the end position of the loop. May not be filled in until the loop has iterated once. */
    public int endPosition;
    /** If {@link Type#LOOP}, remaining loop count. */
    public int loopCount;

    public PlayerStack(Type type, Track track, int position, int endPosition, int loopCount) {
        this.type = type;
        this.track = track;
        this.position = position;
        this.endPosition = endPosition;
        this.loopCount = loopCount;
    }
}
