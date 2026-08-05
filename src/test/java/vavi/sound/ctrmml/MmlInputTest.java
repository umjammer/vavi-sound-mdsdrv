/*
 * Copyright (c) 2026 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.sound.ctrmml;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Ported from ctrmml's {@code src/unittest/test_mml_input.cpp}.
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 2026-08-02 nsano initial version <br>
 */
class MmlInputTest {

    private Song song;
    private MmlInput mmlInput;

    @BeforeEach
    void setUp() {
        song = new Song();
        mmlInput = new MmlInput(song);
    }

    private Event event(int trackId, int index) {
        return song.getTrack(trackId).getEvent(index);
    }

    @Test
    void testBasicMml() {
        mmlInput.readLine("A cdef");
        assertEquals(4, song.getTrack(0).getEventCount());
    }

    @Test
    void testMmlTrackId() {
        mmlInput.readLine("A cdef");
        mmlInput.readLine("B cd");
        mmlInput.readLine("*10 cdefga");
        assertEquals(4, song.getTrack(0).getEventCount());
        assertEquals(2, song.getTrack(1).getEventCount());
        assertEquals(6, song.getTrack(10).getEventCount());
    }

    @Test
    void testMmlNoteFlatSharp() {
        mmlInput.readLine("A o4c+d-");
        assertEquals(Event.Type.NOTE, event(0, 0).type);
        assertEquals(37, event(0, 0).param);
        assertEquals(Event.Type.NOTE, event(0, 1).type);
        assertEquals(37, event(0, 1).param);
    }

    @Test
    void testMmlNoteOctave() {
        mmlInput.readLine("A o4cd>e<f");
        assertEquals(36, event(0, 0).param);
        assertEquals(38, event(0, 1).param);
        assertEquals(52, event(0, 2).param);
        assertEquals(41, event(0, 3).param);
    }

    @Test
    void testMmlNoteDuration() {
        mmlInput.readLine("A l16cd8e.f8.");
        assertEquals(6, event(0, 0).onTime);
        assertEquals(12, event(0, 1).onTime);
        assertEquals(9, event(0, 2).onTime);
        assertEquals(18, event(0, 3).onTime);
    }

    @Test
    void testMmlTrackMeasureLen() {
        mmlInput.readLine("A C96 c1 c2 c4 C128 c1 c2 c4 C192 c1 c2 c4");
        int[] expected = {96, 48, 24, 128, 64, 32, 192, 96, 48};
        for (int i = 0; i < expected.length; i++) {
            assertEquals(expected[i], event(0, i).onTime, "event " + i);
        }
    }

    @Test
    void testMmlLoop() {
        mmlInput.readLine("A [c] [c]5");
        assertEquals(Event.Type.LOOP_START, event(0, 0).type);
        assertEquals(Event.Type.LOOP_END, event(0, 2).type);
        assertEquals(2, event(0, 2).param); // default parameter
        assertEquals(Event.Type.LOOP_START, event(0, 3).type);
        assertEquals(Event.Type.LOOP_END, event(0, 5).type);
        assertEquals(5, event(0, 5).param);
    }

    @Test
    void testMmlTagReplace() {
        mmlInput.readLine("#title My song title.");
        assertEquals("My song title.", song.getTagFront("#title"));
        mmlInput.readLine("#title My new song title.");
        assertEquals("My new song title.", song.getTagFront("#title"));
    }

    @Test
    void testMmlTagAppend() {
        mmlInput.readLine("@blah One two, three \"four, five\"");
        mmlInput.readLine("\tsixth");
        assertEquals("One", song.getTag("@blah").get(0));
        assertEquals("two", song.getTag("@blah").get(1));
        assertEquals("three", song.getTag("@blah").get(2));
        assertEquals("four, five", song.getTag("@blah").get(3));
        assertEquals("sixth", song.getTag("@blah").get(4));
    }

    @Test
    void testMmlMultiTrack() {
        mmlInput.readLine("ABC cdef");
        assertEquals(4, song.getTrack(0).getEventCount());
        assertEquals(4, song.getTrack(1).getEventCount());
        assertEquals(4, song.getTrack(2).getEventCount());
    }

    @Test
    void testMmlConditional() {
        mmlInput.readLine("ABC o4c{d/e/f}g");
        for (int i = 0; i < 3; i++) {
            assertEquals(3, song.getTrack(i).getEventCount());
            // First and last event should be the same in all tracks
            assertEquals(Event.Type.NOTE, event(i, 0).type);
            assertEquals(36, event(i, 0).param);
            assertEquals(Event.Type.NOTE, event(i, 1).type);
            assertEquals(Event.Type.NOTE, event(i, 2).type);
            assertEquals(43, event(i, 2).param);
        }
        // Param should differ
        assertEquals(38, event(0, 1).param);
        assertEquals(40, event(1, 1).param);
        assertEquals(41, event(2, 1).param);
    }

    @Test
    void testMmlPlatformCommand() {
        mmlInput.readLine("A 'first second third'c 'foo bar baz'");
        assertEquals(3, song.getTrack(0).getEventCount());
        assertEquals(Event.Type.PLATFORM, event(0, 0).type);
        assertEquals(-32768, event(0, 0).param);
        assertEquals(Event.Type.PLATFORM, event(0, 2).type);
        assertEquals(-32767, event(0, 2).param);
        // Check tags
        assertEquals("first", song.getPlatformCommand(-32768).get(0));
        assertEquals("second", song.getPlatformCommand(-32768).get(1));
        assertEquals("third", song.getPlatformCommand(-32768).get(2));
        assertEquals("foo", song.getPlatformCommand(-32767).get(0));
        assertEquals("bar", song.getPlatformCommand(-32767).get(1));
        assertEquals("baz", song.getPlatformCommand(-32767).get(2));
    }

    @Test
    void testMmlErrorDuration() {
        assertThrows(InputError.class, () -> mmlInput.readLine("A l0cdef"));
        assertThrows(InputError.class, () -> mmlInput.readLine("A l-2cdef"));
        assertThrows(InputError.class, () -> mmlInput.readLine("A l:-5cdef"));
    }

    @Test
    void testMmlKeySignature() {
        mmlInput.readLine("A _{c+} l4 o4cdefgab>c");
        mmlInput.readLine("A _{=dg} l4 o4cdefgab>c");
        mmlInput.readLine("A _{C} l4 o4cdefgab>c");

        // c# minor, then d/g naturalized, then reset to c major
        int[] expected = {
                37, 39, 40, 42, 44, 45, 47, 49,
                37, 38, 40, 42, 43, 45, 47, 49,
                36, 38, 40, 41, 43, 45, 47, 48
        };
        for (int i = 0; i < expected.length; i++) {
            assertEquals(Event.Type.NOTE, event(0, i).type, "event " + i);
            assertEquals(expected[i], event(0, i).param, "event " + i);
        }
    }

    @Test
    void testMmlTrackMap() {
        mmlInput.readLine("A "); // empty
        mmlInput.readLine("B"); // also empty
        mmlInput.getTrackMap();
    }

    @Test
    @DisplayName("mucom88 style echo command")
    void testMmlEcho() {
        mmlInput.readLine("A \\=2,3 l4 o4c4\\8 \\=-1,3d\\r\\ \\=-4,0>e\\< \\=-2,2f\\ \\=2,2f\\");
        assertEquals(Event.Type.NOTE, event(0, 0).type);
        assertEquals(36, event(0, 0).param);
        assertEquals(24, event(0, 0).onTime);

        assertEquals(Event.Type.VOL_REL, event(0, 1).type);
        assertEquals(-3, event(0, 1).param);
        assertEquals(Event.Type.REST, event(0, 2).type); // Rest because the echo buffer is empty
        assertEquals(12, event(0, 2).offTime);
        assertEquals(Event.Type.VOL_REL, event(0, 3).type);
        assertEquals(3, event(0, 3).param);

        assertEquals(Event.Type.NOTE, event(0, 4).type);
        assertEquals(38, event(0, 4).param);

        assertEquals(Event.Type.VOL_REL, event(0, 5).type);
        assertEquals(-3, event(0, 5).param);
        assertEquals(Event.Type.NOTE, event(0, 6).type);
        assertEquals(38, event(0, 6).param);
        assertEquals(Event.Type.VOL_REL, event(0, 7).type);
        assertEquals(3, event(0, 7).param);

        assertEquals(Event.Type.REST, event(0, 8).type);

        assertEquals(Event.Type.VOL_REL, event(0, 9).type);
        assertEquals(-3, event(0, 9).param);
        assertEquals(Event.Type.REST, event(0, 10).type); // rest in echo buffer
        assertEquals(Event.Type.VOL_REL, event(0, 11).type);
        assertEquals(3, event(0, 11).param);

        assertEquals(Event.Type.NOTE, event(0, 12).type);
        assertEquals(52, event(0, 12).param);
        // No vol rel because volume parameter is 0
        assertEquals(Event.Type.NOTE, event(0, 13).type);
        assertEquals(36, event(0, 13).param);

        assertEquals(Event.Type.NOTE, event(0, 14).type);
        assertEquals(41, event(0, 14).param);
        assertEquals(Event.Type.VOL_REL, event(0, 15).type);
        assertEquals(-2, event(0, 15).param);
        assertEquals(Event.Type.NOTE, event(0, 16).type);
        assertEquals(52, event(0, 16).param);
        assertEquals(Event.Type.VOL_REL, event(0, 17).type);
        assertEquals(2, event(0, 17).param);

        assertEquals(Event.Type.NOTE, event(0, 18).type);
        assertEquals(41, event(0, 18).param);
        assertEquals(Event.Type.VOL_REL, event(0, 19).type);
        assertEquals(-2, event(0, 19).param);
        assertEquals(Event.Type.REST, event(0, 20).type); // Echo buffer was cleared
        assertEquals(Event.Type.VOL_REL, event(0, 21).type);
        assertEquals(2, event(0, 21).param);
    }
}
