/*
 * Copyright (c) 2026 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.sound.ctrmml;


/**
 * Track validator.
 * <p>
 * The track validator is used to perform a "sanity check" of a {@link Track}, detecting
 * playback errors while also calculating the play and loop duration.
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 2026-08-02 nsano initial version <br>
 * @see <a href="https://github.com/superctr/ctrmml">ctrmml</a> src/player.cpp
 */
public class TrackValidator extends BasicPlayer {

    private long loopTime;

    /**
     * Validates a track by playing it.
     *
     * @throws InputError if any validation errors occur. These should be displayed to the user.
     */
    public TrackValidator(Song song, Track track) {
        super(song, track);
        // step all the way to the end
        while (isEnabled()) {
            stepEvent();
        }
    }

    /**
     * Gets the length of the loop section.
     * <p>
     * The loop section starts from the position of the {@link Event.Type#SEGNO} to the end of
     * the track.
     *
     * @return 0 if there is no loop, otherwise the length of the loop section
     */
    public long getLoopLength() {
        return loopTime;
    }

    @Override
    protected void eventHook() {
    }

    @Override
    protected boolean loopHook() {
        // do not loop
        return false;
    }

    @Override
    protected void endHook() {
        if (loopPlayTime >= 0) {
            loopTime = getPlayTime() - loopPlayTime;
        }
    }
}
