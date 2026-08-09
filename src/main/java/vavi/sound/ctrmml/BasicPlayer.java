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
 * Abstract basic track player.
 * <p>
 * The player class is used to iterate {@link Track} events, handling basic track events such as
 * looping and jumping to subroutines.
 * <p>
 * All events are forwarded to the derived classes with {@link #eventHook()}, {@link #loopHook()}
 * and {@link #endHook()}.
 * <p>
 * At the end of the track, when an {@link Event.Type#END} is encountered, {@link #loopHook()} is
 * called. Depending on the return value, the track is stopped and {@link #endHook()} is called.
 * <p>
 * Typical usage is to call {@link #stepEvent()} until {@link #isEnabled()} returns false.
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 2026-08-02 nsano initial version <br>
 * @see <a href="https://github.com/superctr/ctrmml">ctrmml</a> src/player.cpp
 */
public abstract class BasicPlayer {

    /** Current event. */
    protected Event event;
    /** Current event in the track, null at the end of the track. */
    private Event trackEvent;
    /** Current reference. */
    InputRef reference;
    /** Playing time. */
    long playTime;
    /** Playing time at loop point, -1 if the loop point has not been reached. */
    long loopPlayTime = -1;
    /** Key-on time from current event. */
    protected int onTime;
    /** Key-off time from current event. */
    protected int offTime;

    private final Song song;
    private Track track;
    private boolean enabled = true;
    private int position;
    private int loopPosition = -1;
    /** Position to increment the loop count. */
    private int loopResetPosition = -1;
    private int loopCount = -1;
    private int loopResetCount;
    private final Deque<PlayerStack> stack = new ArrayDeque<>();
    private final int[] stackDepth = new int[PlayerStack.Type.MAX_STACK_TYPE];
    private static final int maxStackDepth = 10;
    /** Number of loops in the stack where the loop count is 0. */
    private int loopBeginDepth;

    protected BasicPlayer(Song song, Track track) {
        this.song = song;
        this.track = track;
    }

    /**
     * Play one event.
     * <p>
     * This function first reads an event from the track, then calls {@link #eventHook()}. After
     * that, loops and jump events are handled to set the next event position.
     */
    public void stepEvent() {
        // Set accumulated time
        playTime += onTime + offTime;
        onTime = 0;
        offTime = 0;
        if (position == loopResetPosition) {
            loopResetCount = loopCount;
        }
        try {
            // Read the next event
            trackEvent = track.getEvent(position++);
            // Set the event time
            if (Long.compareUnsigned(trackEvent.playTime, playTime) > 0) {
                trackEvent.playTime = playTime;
            }
            event = trackEvent.clone();
        } catch (IndexOutOfBoundsException e) {
            // reached the end
            event = new Event(Event.Type.END, 0, 0, 0, Event.NO_PLAY_TIME, reference);
            trackEvent = null;
        }
        // Set new on/off time
        onTime = event.onTime;
        offTime = event.offTime;
        reference = event.reference;
        // Handle events
        switch (event.type) {
        case LOOP_START -> {
            loopBeginDepth++;
            stackPush(new PlayerStack(PlayerStack.Type.LOOP, track, position, 0, 0));
            eventHook();
        }
        case LOOP_BREAK -> {
            // verify
            stackTop(PlayerStack.Type.LOOP);
            // set param to end position to help with conversion
            trackEvent.param = (short) stack.peek().endPosition;
            // Break if at the final loop iteration
            if (stack.peek().loopCount == 1) {
                // make sure eventHook sees a LOOP_END on the final iteration
                event = track.getEvent(stack.peek().endPosition - 1).clone();
                position = stackPop(PlayerStack.Type.LOOP).endPosition;
            }
            eventHook();
        }
        case LOOP_END -> {
            stackTop(PlayerStack.Type.LOOP);
            stack.peek().endPosition = position;
            // Set loop count if zero
            if (stack.peek().loopCount == 0) {
                stack.peek().loopCount = event.param;
                loopBeginDepth--;
            }
            if (stack.peek().loopCount < 0) {
                error("Invalid loop count");
            }
            // Jump back
            if (--stack.peek().loopCount > 0) {
                position = stack.peek().position;
            } else {
                stackPop(PlayerStack.Type.LOOP);
            }
            eventHook();
        }
        case SEGNO -> {
            loopCount = 0;
            loopResetCount = 0;
            loopPosition = position;
            loopResetPosition = position;
            loopPlayTime = playTime;
            eventHook();
        }
        case JUMP -> {
            Track newTrack;
            try {
                newTrack = song.getTrack(event.param);
            } catch (RuntimeException e) {
                error("jump destination doesn't exist");
                return;
            }
            // Event hook should be sent before pushing the stack
            eventHook();
            // Push old position
            stackPush(new PlayerStack(PlayerStack.Type.JUMP, track, position, 0, 0));
            // Set new position
            track = newTrack;
            position = 0;
        }
        case END -> {
            if (!stack.isEmpty()) {
                // Pop old position
                track = stackTop(PlayerStack.Type.JUMP).track;
                position = stackPop(PlayerStack.Type.JUMP).position;
            } else {
                if (loopPosition != -1 && playTime != loopPlayTime && loopHook()) {
                    position = loopPosition;
                    loopCount++;
                } else {
                    enabled = false;
                    // send a rest event here?
                    endHook();
                }
            }
        }
        default -> eventHook();
        }
    }

    /** Resets the loop count. */
    public void resetLoopCount() {
        if (loopCount != -1) {
            loopCount = 0;
            loopResetCount = 0;
            loopResetPosition = (position != 0) ? position - 1 : 0;
        }
    }

    /** Return false when playback is completed. */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Check if the playback position is inside a loop. The first iteration of each loop is not
     * counted.
     */
    protected boolean isInsideLoop() {
        return stackDepth[PlayerStack.Type.LOOP.ordinal()] != 0
                && stackDepth[PlayerStack.Type.LOOP.ordinal()] != loopBeginDepth;
    }

    /**
     * Check if the playback position is inside a jump (subroutine). This also includes drum
     * mode subroutines.
     */
    protected boolean isInsideJump() {
        return stackDepth[PlayerStack.Type.JUMP.ordinal()] != 0;
    }

    /**
     * Gets the timestamp of the last played event.
     * <p>
     * This function can be used to get the track duration if called in a {@link TrackValidator}.
     */
    public long getPlayTime() {
        return playTime;
    }

    /**
     * Gets the timestamp of the last loop command.
     * <p>
     * This can be used to verify that a loop at the end of the track won't cause an infinite
     * loop when converting.
     *
     * @return -1 if there is no loop, or if it hasn't been reached.
     */
    protected long getLoopPlayTime() {
        return loopPlayTime;
    }

    /**
     * Gets the current loop count.
     *
     * @return -1 if the track has reached the loop point or if it's a non-looping track.
     */
    public int getLoopCount() {
        return Math.min(loopResetCount, loopCount);
    }

    /** Gets the last parsed event. */
    public Event getEvent() {
        return event;
    }

    /** Get a list of references to the current track position and calling commands. */
    public List<InputRef> getReferences() {
        List<InputRef> refList = new ArrayList<>();
        refList.add(track.getEvents().get(position - 1).reference);

        for (PlayerStack frame : stack) {
            if (frame.type != PlayerStack.Type.LOOP) {
                refList.add(frame.track.getEvents().get(frame.position - 1).reference);
            }
        }
        return refList;
    }

    /**
     * Indicate that track processing is finished.
     * <p>
     * This is typically done at the end of the track, and can also be used by player classes to
     * stop track processing early.
     */
    protected void disable() {
        enabled = false;
    }

    /**
     * Push a stack frame.
     *
     * @throws InputError if the stack size has reached the maximum allowed stack depth.
     */
    void stackPush(PlayerStack frame) {
        if (stack.size() >= maxStackDepth) {
            error("stack overflow (depth limit reached)");
        }
        stackDepth[frame.type.ordinal()]++;
        stack.push(frame);
    }

    /**
     * Get the top stack frame, with type checking.
     *
     * @throws InputError if the top stack frame does not match {@code type}.
     */
    PlayerStack stackTop(PlayerStack.Type type) {
        if (stack.isEmpty()) {
            stackUnderflow(type);
        }
        PlayerStack frame = stack.peek();
        if (frame.type != type) {
            stackUnderflow(frame.type);
        }
        return frame;
    }

    /**
     * Pop a stack frame, with type checking.
     *
     * @throws InputError if the top stack frame does not match {@code type}.
     */
    PlayerStack stackPop(PlayerStack.Type type) {
        PlayerStack frame = stack.pop();
        stackDepth[type.ordinal()]--;
        if (frame.type != type) {
            stackUnderflow(frame.type);
        }
        return frame;
    }

    /** @return null if the stack is empty */
    PlayerStack.Type getStackType() {
        return stack.isEmpty() ? null : stack.peek().type;
    }

    protected int getStackDepth(PlayerStack.Type type) {
        return stackDepth[type.ordinal()];
    }

    /** Get a reference to the Song. */
    Song getSong() {
        return song;
    }

    /** @return the track currently being played */
    Track getTrack() {
        return track;
    }

    void setTrack(Track track) {
        this.track = track;
    }

    int getPosition() {
        return position;
    }

    void setPosition(int position) {
        this.position = position;
    }

    /**
     * Throws an {@link InputError}.
     *
     * @throws InputError always
     */
    protected void error(String message) {
        throw new InputError(reference, message);
    }

    /** Throw an error with appropriate message for a stack underflow. */
    private void stackUnderflow(PlayerStack.Type type) {
        if (type == PlayerStack.Type.LOOP) {
            error("unterminated '[]' loop");
        } else if (type == PlayerStack.Type.JUMP) {
            error("unexpected ']' loop end");
        } else if (type == PlayerStack.Type.DRUM_MODE) {
            error("drum routine contains no note");
        } else {
            error("unknown stack type (BUG, please report)");
        }
    }

    /** Called at every event. */
    protected abstract void eventHook();

    /**
     * Called at the loop position.
     *
     * @return true to continue loop, false to end playback ({@link #endHook()} will be called)
     */
    protected abstract boolean loopHook();

    /** Called at the end position. */
    protected abstract void endHook();
}
