/*
 * Copyright (c) 2026 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.sound.ctrmml;

import java.util.Map;
import java.util.TreeMap;


/**
 * Validates all tracks in a song using {@link TrackValidator}.
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 2026-08-02 nsano initial version <br>
 * @see <a href="https://github.com/superctr/ctrmml">ctrmml</a> src/player.cpp
 */
public class SongValidator {

    private final Map<Integer, TrackValidator> trackMap = new TreeMap<>();

    /**
     * @throws InputError if any validation errors occur. These should be displayed to the user.
     */
    public SongValidator(Song song) {
        for (Map.Entry<Integer, Track> entry : song.getTrackMap().entrySet()) {
            trackMap.put(entry.getKey(), new TrackValidator(song, entry.getValue()));
        }
    }

    /**
     * Gets the track validator map.
     * <p>
     * The track validators contain the length and loop lengths of each {@link Track}, which you
     * can get with {@link BasicPlayer#getPlayTime()} and {@link TrackValidator#getLoopLength()}
     * respectively.
     */
    public Map<Integer, TrackValidator> getTrackMap() {
        return trackMap;
    }
}
