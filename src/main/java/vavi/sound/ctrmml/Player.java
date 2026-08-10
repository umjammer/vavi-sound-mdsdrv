/*
 * Copyright (c) 2026 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.sound.ctrmml;

import java.util.List;
import java.util.NoSuchElementException;


/**
 * Generic track player.
 * <p>
 * This handles the channel events using an internal track state array. Drum mode events are
 * also handled here. The event is then passed to {@link #writeEvent()}.
 * <p>
 * This player also keeps track of note and rest durations (in ticks) and so if
 * {@link #playTick()} is called at a regular interval, playback of multiple tracks can be
 * synchronized.
 * <p>
 * {@link #skipTicks(int)} can be used to skip events. Because events are not sent to
 * {@link #writeEvent()} during this time, a derived {@code writeEvent()} should mainly check
 * for {@link Event.Type#NOTE} and {@link Event.Type#REST} types and get the state of the
 * channel variables using {@link #getUpdateFlag} and {@link #getVar} respectively.
 * <p>
 * The Player class is intended for actual playback. For conversion, directly inheriting
 * {@link BasicPlayer} might be a better idea.
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 2026-08-02 nsano initial version <br>
 * @see <a href="https://github.com/superctr/ctrmml">ctrmml</a> src/player.cpp
 */
public class Player extends BasicPlayer {

    /** Coarse volume flag bit in the track update mask. */
    private static final int VOL_BIT = 30;
    /** BPM tempo flag bit in the track update mask. */
    private static final int BPM_BIT = 31;

    private int lastNote;
    private boolean skipFlag;
    private int noteCount;
    private int restCount;
    /**
     * Platform command state. The original declares {@code Event::CHANNEL_CMD_COUNT} entries
     * but indexes it with 0-31, so the full 32 slots are allocated here.
     */
    private final int[] platformState = new int[32];
    private int platformUpdateMask;
    private final int[] trackState = new int[Event.Type.CHANNEL_CMD_COUNT];
    private int trackUpdateMask;

    public Player(Song song, Track track) {
        super(song, track);
    }

    private boolean flag(int bit) {
        return (trackUpdateMask & (1 << bit)) != 0;
    }

    private void flagSet(int bit) {
        trackUpdateMask |= 1 << bit;
    }

    private void flagClr(int bit) {
        trackUpdateMask &= ~(1 << bit);
    }

    private int chState(Event.Type type) {
        return trackState[type.ordinal() - Event.Type.CHANNEL_CMD];
    }

    private void chState(Event.Type type, int value) {
        trackState[type.ordinal() - Event.Type.CHANNEL_CMD] = (short) value;
    }

    /**
     * Skip a number of ticks.
     *
     * @param ticks Number of ticks to skip, counting from the current position.
     */
    public void skipTicks(int ticks) {
        if (!isEnabled()) {
            playTime += ticks;
            return;
        }
        skipFlag = true;
        while (ticks != 0 && isEnabled()) {
            if (onTime != 0) {
                if (onTime > ticks) {
                    onTime -= ticks;
                    break;
                }
                playTime += onTime;
                ticks -= onTime;
                onTime = 0;
            } else if (offTime != 0) {
                if (offTime > ticks) {
                    offTime -= ticks;
                    break;
                }
                playTime += offTime;
                ticks -= offTime;
                offTime = 0;
            }
            while (onTime == 0 && offTime == 0 && isEnabled()) {
                if (ticks == 0) {
                    skipFlag = false;
                }
                stepEvent();
            }
        }
        playTime += ticks;
        skipFlag = false;
    }

    /**
     * Play a single tick.
     * <p>
     * Reads and decrements the {@code onTime} and {@code offTime} of the previous Event.
     * <p>
     * When there is remaining {@code onTime}, only the {@code onTime} is decremented. After it
     * becomes 0 and if there is remaining {@code offTime}, an {@link Event.Type#REST} is
     * automatically created and passed to {@link #writeEvent()}.
     * <p>
     * When both counters are 0, the next event is read using {@link #stepEvent()}.
     */
    public void playTick() {
        if (onTime != 0) {
            onTime--;
            playTime++;
            // key off
            if (onTime == 0 && offTime != 0) {
                event = new Event(Event.Type.REST, 0, 0, 0, Event.NO_PLAY_TIME, reference);
                writeEvent();
            }
        } else if (offTime != 0) {
            offTime--;
            playTime++;
        }

        while (isEnabled() && onTime == 0 && offTime == 0) {
            stepEvent();
        }
    }

    /**
     * Return the coarse volume flag.
     * <p>
     * The coarse volume flag determines the scaling of the event variable
     * {@link Event.Type#VOL}, coarse or fine. The definition of either is platform-specific,
     * but typically the coarse volume is logarithmic in 15 steps of 2 dB while fine volume can
     * have more steps and be either linear or logarithmic.
     * <p>
     * In MML, the {@code v} command would emit a coarse volume setting while {@code V} is a
     * fine volume setting.
     *
     * @return false if volume setting is fine, true if volume setting is coarse
     */
    public boolean coarseVolumeFlag() {
        return flag(VOL_BIT);
    }

    /** Set the coarse volume flag. */
    public void setCoarseVolumeFlag(boolean state) {
        if (state) {
            flagSet(VOL_BIT);
        } else {
            flagClr(VOL_BIT);
        }
    }

    /**
     * Return the tempo BPM flag.
     * <p>
     * The tempo BPM flag determines if the event variable {@link Event.Type#TEMPO} contains the
     * direct tempo (platform-specific) or the BPM.
     *
     * @return false if tempo setting is platform-specific, true if tempo setting is in BPM
     */
    public boolean bpmFlag() {
        return flag(BPM_BIT);
    }

    /** Get a platform event variable. */
    public int getPlatformVar(int type) {
        if (type > 31) {
            return 0;
        }
        return platformState[type];
    }

    /** Get event variable. */
    public int getVar(Event.Type type) {
        if (!type.isChannelCmd()) {
            error("BUG: Unsupported event type");
        }
        return chState(type);
    }

    /** Set event variable. */
    public void setVar(Event.Type type, int val) {
        if (!type.isChannelCmd()) {
            error("BUG: Unsupported event type");
        }
        chState(type, val);
    }

    /** Check if a platform variable has been updated. */
    protected boolean getPlatformFlag(int type) {
        if (type > 31) {
            return false; // silently ignored
        }
        return ((platformUpdateMask >> type) & 1) != 0;
    }

    /** Clear the update flag for a platform variable. */
    protected void clearPlatformFlag(int type) {
        if (type > 31) {
            return;
        }
        platformUpdateMask &= ~(1 << type);
    }

    /** Check if a channel variable has been updated. */
    protected boolean getUpdateFlag(Event.Type type) {
        if (!type.isChannelCmd()) {
            error("BUG: Unsupported event type");
        }
        return flag(type.ordinal() - Event.Type.CHANNEL_CMD);
    }

    /** Set the update flag for a channel variable. */
    protected void setUpdateFlag(Event.Type type) {
        if (!type.isChannelCmd()) {
            error("BUG: Unsupported event type");
        }
        flagSet(type.ordinal() - Event.Type.CHANNEL_CMD);
    }

    /** Clear the update flag for a channel variable. */
    protected void clearUpdateFlag(Event.Type type) {
        if (!type.isChannelCmd()) {
            error("BUG: Unsupported event type");
        }
        flagClr(type.ordinal() - Event.Type.CHANNEL_CMD);
    }

    /**
     * Get the last note parsed by {@link #handleEvent()}.
     * <p>
     * Useful in player drivers in order to correctly set the note frequency after seeking.
     */
    protected int getLastNote() {
        return lastNote;
    }

    /**
     * Custom platform event parser.
     * <p>
     * The override should modify {@code platformState} as appropriate and then return a bitmask
     * indicating the changed state.
     *
     * @param tag           the platform command, as created by {@link Song#registerPlatformCommand}
     * @param platformState the internal platform command state array
     * @return a bitmask representing the event state variables that were modified
     */
    private int parsePlatformEvent(List<String> tag, int[] platformState) {
        return 0;
    }

    /** Call {@link #parsePlatformEvent} manually with the specified tag. */
    public void platformUpdate(List<String> tag) {
        platformUpdateMask |= parsePlatformEvent(tag, platformState);
    }

    /**
     * Event handler. This is the Player equivalent to {@link #eventHook()}.
     * <p>
     * The default handler simply increments a note and rest counter.
     */
    private void writeEvent() {
        // Handle NOTE and REST events here.
        if (event.type == Event.Type.NOTE) {
            noteCount++;
        } else if (event.type == Event.Type.REST || event.type == Event.Type.END) {
            restCount++;
        }
    }

    /** Handle an {@link Event.Type#NOTE} in drum mode. */
    private void handleDrumMode() {
        if (getStackType() != PlayerStack.Type.DRUM_MODE) {
            // First note event enters subroutine
            int offset = 0;
            Track newTrack;
            try {
                newTrack = getSong().getTrack(offset + event.param);
            } catch (NoSuchElementException e) {
                error("drum mode error: track *%d is not defined (base %d, note %d)"
                        .formatted(event.param + offset, offset, event.param));
                return;
            }
            // Push old position
            stackPush(new PlayerStack(PlayerStack.Type.DRUM_MODE, getTrack(), getPosition(), onTime, offTime));
            // Set new position
            setTrack(newTrack);
            setPosition(0);
            // Replace note
            onTime = 0;
            offTime = 0;
            event.type = Event.Type.NOP;
        } else {
            // Second note exits the subroutine
            onTime = stackTop(PlayerStack.Type.DRUM_MODE).endPosition;
            offTime = stackTop(PlayerStack.Type.DRUM_MODE).loopCount;
            setPosition(stackTop(PlayerStack.Type.DRUM_MODE).position);
            setTrack(stackPop(PlayerStack.Type.DRUM_MODE).track);
        }
    }

    /**
     * Handles an event inside the player.
     * <p>
     * Updates the internal channel variables and update flags. If a platform event is
     * encountered, calls override functions to parse and execute those events.
     */
    private void handleEvent() {
        switch (event.type) {
        case NOTE -> {
            lastNote = event.param;
            if (chState(Event.Type.DRUM_MODE) != 0) {
                handleDrumMode();
            }
        }
        case PLATFORM -> {
            try {
                List<String> tag = getSong().getPlatformCommand(event.param);
                platformUpdateMask |= parsePlatformEvent(tag, platformState);
            } catch (NoSuchElementException e) {
                error("Platform command %d is not defined".formatted(event.param));
            }
        }
        case TRANSPOSE_REL -> {
            chState(Event.Type.TRANSPOSE, chState(Event.Type.TRANSPOSE) + event.param);
            flagSet(Event.Type.TRANSPOSE.ordinal() - Event.Type.CHANNEL_CMD);
        }
        case VOL -> {
            chState(Event.Type.VOL_FINE, event.param);
            flagSet(Event.Type.VOL_FINE.ordinal() - Event.Type.CHANNEL_CMD);
            flagSet(VOL_BIT);
        }
        // Previous behavior was to clear the VOL_BIT after VOL_FINE_REL and set it after
        // VOL_REL. It has now been changed so VOL_REL and VOL_FINE_REL neither sets nor clears it.
        case VOL_REL, VOL_FINE_REL -> {
            chState(Event.Type.VOL_FINE, chState(Event.Type.VOL_FINE) + event.param);
            flagSet(Event.Type.VOL_FINE.ordinal() - Event.Type.CHANNEL_CMD);
        }
        case TEMPO_BPM -> {
            chState(Event.Type.TEMPO, event.param);
            flagSet(Event.Type.TEMPO.ordinal() - Event.Type.CHANNEL_CMD);
            flagSet(BPM_BIT);
        }
        default -> {
            if (event.type.isChannelCmd()) {
                int type = event.type.ordinal() - Event.Type.CHANNEL_CMD;
                trackState[type] = event.param;
                trackUpdateMask |= 1 << type;
                if (event.type == Event.Type.VOL_FINE) {
                    flagClr(VOL_BIT);
                }
                if (event.type == Event.Type.TEMPO) {
                    flagClr(BPM_BIT);
                }
            }
        }
        }
    }

    @Override
    protected void eventHook() {
        handleEvent();
        if (!skipFlag) {
            writeEvent();
        }
    }

    @Override
    protected boolean loopHook() {
        return true;
    }

    @Override
    protected void endHook() {
        event = new Event(Event.Type.END, 0, 0, 0, Event.NO_PLAY_TIME, reference);
        writeEvent();
    }
}
