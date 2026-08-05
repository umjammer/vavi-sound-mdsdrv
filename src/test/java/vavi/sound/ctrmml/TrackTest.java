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
 * Ported from ctrmml's {@code src/unittest/test_track.cpp}.
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 2026-08-02 nsano initial version <br>
 */
class TrackTest {

    private Track track;

    @BeforeEach
    void setUp() {
        track = new Track();
    }

    @Test
    @DisplayName("Add notes and verify that they are present in the event list")
    void testAddNotes() {
        for (int i = 0; i < 10; i++) {
            track.addNote(i, 24);
        }
        for (int i = 0; i < track.getEvents().size(); i++) {
            assertEquals(i + 12 * 5, track.getEvent(i).param);
            assertEquals(24, track.getEvent(i).onTime);
        }
    }

    @Test
    @DisplayName("Illegal quantize values")
    void testIllegalQuantize() {
        assertEquals(-1, track.setQuantize(12, 8));
        assertEquals(-1, track.setQuantize(20));
        assertEquals(-1, track.setQuantize(0, 0));
        assertEquals(0, track.setQuantize(8));
        assertEquals(0, track.setQuantize(10, 20));
        assertEquals(0, track.setQuantize(0)); // special case
    }

    @Test
    @DisplayName("Set quantize and add notes and verify that durations are correct")
    void testAddNoteQuantize() {
        track.setQuantize(6);
        track.addNote(1, 24);
        track.setQuantize(2);
        track.addNote(1, 24);
        track.setQuantize(8);
        track.addNote(1, 24);
        track.setQuantize(4);
        track.addNote(1, 24);
        track.setEarlyRelease(0); // should cancel
        track.addNote(1, 24);
        assertEquals(18, track.getEvent(0).onTime);
        assertEquals(6, track.getEvent(0).offTime);
        assertEquals(6, track.getEvent(1).onTime);
        assertEquals(18, track.getEvent(1).offTime);
        assertEquals(24, track.getEvent(2).onTime);
        assertEquals(0, track.getEvent(2).offTime);
        assertEquals(12, track.getEvent(3).onTime);
        assertEquals(12, track.getEvent(3).offTime);
        assertEquals(24, track.getEvent(4).onTime);
        assertEquals(0, track.getEvent(4).offTime);
    }

    @Test
    @DisplayName("Set early release and add notes and verify that durations are correct")
    void testAddNoteEarlyRelease() {
        track.setEarlyRelease(6);
        track.addNote(1, 24);
        track.setEarlyRelease(2);
        track.addNote(1, 24);
        track.setEarlyRelease(8);
        track.addNote(1, 24);
        track.setEarlyRelease(4);
        track.addNote(1, 24);
        track.setEarlyRelease(35);
        track.addNote(1, 24);
        track.setQuantize(8); // should cancel
        track.addNote(1, 24);
        assertEquals(18, track.getEvent(0).onTime);
        assertEquals(6, track.getEvent(0).offTime);
        assertEquals(22, track.getEvent(1).onTime);
        assertEquals(2, track.getEvent(1).offTime);
        assertEquals(16, track.getEvent(2).onTime);
        assertEquals(8, track.getEvent(2).offTime);
        assertEquals(20, track.getEvent(3).onTime);
        assertEquals(4, track.getEvent(3).offTime);
        assertEquals(1, track.getEvent(4).onTime);
        assertEquals(23, track.getEvent(4).offTime);
        assertEquals(24, track.getEvent(5).onTime);
        assertEquals(0, track.getEvent(5).offTime);
    }

    @Test
    @DisplayName("Set default duration, add notes and verify that durations are correct")
    void testAddNoteDefaultDuration() {
        track.setDuration(16);
        track.addNote(1, 0);
        track.setDuration(50);
        track.addNote(1, 0);
        assertEquals(16, track.getEvent(0).onTime);
        assertEquals(50, track.getEvent(1).onTime);
    }

    @Test
    @DisplayName("Add notes with various octave changes and verify that notes are correct")
    void testAddNoteOctaveChange() {
        track.setOctave(0);
        track.addNote(0, 0);
        assertEquals(0, track.getEvent(0).param);
        track.setOctave(1);
        track.addNote(0, 0);
        assertEquals(12, track.getEvent(1).param);
        track.changeOctave(1);
        track.addNote(0, 0);
        assertEquals(24, track.getEvent(2).param);
        track.changeOctave(-2);
        track.addNote(0, 0);
        assertEquals(0, track.getEvent(3).param);
    }

    @Test
    @DisplayName("Add a tie and verify that the duration of the previous note is changed")
    void testTieExtendDuration() {
        track.setQuantize(8);
        track.addNote(0, 24);
        track.addTie(24);
        assertEquals(48, track.getEvent(0).onTime);
        assertEquals(0, track.getEvent(0).offTime);
        track.setQuantize(4);
        track.addNote(0, 24);
        track.addTie(24);
        assertEquals(24, track.getEvent(1).onTime);
        assertEquals(24, track.getEvent(1).offTime);
    }

    @Test
    @DisplayName("Note, unrelated event, then a tie adds a new tie event")
    void testTieExtendAddTie() {
        track.setQuantize(8);
        track.addNote(0, 24);
        track.addEvent(Event.Type.VOL, 10); // type doesn't matter
        track.addTie(24);
        assertEquals(Event.Type.TIE, track.getEvent(2).type);
        assertEquals(24, track.getEvent(0).onTime);
        assertEquals(24, track.getEvent(2).onTime);
    }

    @Test
    @DisplayName("Note, unrelated event, then a tie adds a new rest")
    void testTieExtendAddRest() {
        track.setQuantize(2);
        track.addNote(0, 24);
        track.addEvent(Event.Type.VOL, 10); // type doesn't matter
        track.addTie(24);
        assertEquals(Event.Type.REST, track.getEvent(2).type);
        assertEquals(12, track.getEvent(0).onTime);
        assertEquals(12, track.getEvent(0).offTime);
        assertEquals(24, track.getEvent(2).offTime);
    }

    @Test
    @DisplayName("Unrelated event then a tie adds a new tie")
    void testTieOnlyAddTie2() {
        track.setQuantize(8);
        track.addEvent(Event.Type.VOL, 10); // type doesn't matter
        track.addTie(24);
        assertEquals(Event.Type.TIE, track.getEvent(1).type);
        assertEquals(24, track.getEvent(1).onTime);
    }

    @Test
    @DisplayName("Just a tie adds a new tie")
    void testTieOnlyAddTie() {
        track.setQuantize(8);
        track.addTie(24);
        assertEquals(Event.Type.TIE, track.getEvent(0).type);
        assertEquals(24, track.getEvent(0).onTime);
    }

    @Test
    @DisplayName("Slur makes the articulation of the previous note legato")
    void testSlur() {
        track.setQuantize(4);
        track.addNote(0, 24);
        track.addSlur();
        track.addNote(1, 24);
        assertEquals(24, track.getEvent(0).onTime);
        assertEquals(0, track.getEvent(0).offTime);
    }

    @Test
    @DisplayName("Slur returns an error when the previous note cannot be made legato")
    void testSlurImpossible() {
        track.setQuantize(4);
        track.addNote(0, 24);
        track.addEvent(Event.Type.REST);
        assertEquals(-1, track.addSlur());
    }

    @Test
    @DisplayName("Reverse rest shortens the on time of the previous note")
    void testReverseRestShortenOnTime() {
        track.setQuantize(8);
        track.addNote(0, 24);
        track.reverseRest(10);
        assertEquals(14, track.getEvent(0).onTime);
        track.setQuantize(4);
        track.addNote(0, 24);
        track.reverseRest(20);
        assertEquals(4, track.getEvent(1).onTime);
        assertEquals(0, track.getEvent(1).offTime);
    }

    @Test
    @DisplayName("Reverse rest shortens the off time of the previous note")
    void testReverseRestShortenOffTime() {
        track.setQuantize(2);
        track.addNote(0, 24);
        track.reverseRest(10);
        assertEquals(8, track.getEvent(0).offTime);
    }

    @Test
    void testReverseRestImpossible() {
        track.addEvent(Event.Type.VOL, 12); // just some dummy events
        track.addEvent(Event.Type.INS, 34);
        track.addEvent(Event.Type.PAN, 56);
        assertThrows(Track.DomainException.class, () -> track.reverseRest(48));
    }

    @Test
    void testReverseRestOverflow() {
        track.addNote(0, 24);
        assertThrows(Track.LengthException.class, () -> track.reverseRest(48));
    }

    @Test
    void testGetEventCount() {
        track.addEvent(Event.Type.VOL, 12); // just some dummy events
        track.addEvent(Event.Type.INS, 34);
        track.addEvent(Event.Type.PAN, 56);
        assertEquals(3, track.getEventCount());
    }

    @Test
    void testKeySignature() {
        // set key to A major
        track.setKeySignature("A");
        assertKeySignature(1, 0, 0, 1, 1, 0, 0);
        // remove the c and f sharp
        track.setKeySignature("=cf");
        assertKeySignature(0, 0, 0, 0, 1, 0, 0);
        // add them back
        track.setKeySignature("+cf");
        assertKeySignature(1, 0, 0, 1, 1, 0, 0);
        // set key to Bb minor
        track.setKeySignature("b-");
        assertKeySignature(0, -1, -1, 0, -1, -1, -1);
        // add a flat to C and F
        track.setKeySignature("-cf");
        assertKeySignature(-1, -1, -1, -1, -1, -1, -1);
        // try resetting and setting at the same time
        track.setKeySignature("=d+f");
        assertKeySignature(-1, 0, -1, 1, -1, -1, -1);
    }

    private void assertKeySignature(int c, int d, int e, int f, int g, int a, int b) {
        assertEquals(c, track.getKeySignature('c'), "c");
        assertEquals(d, track.getKeySignature('d'), "d");
        assertEquals(e, track.getKeySignature('e'), "e");
        assertEquals(f, track.getKeySignature('f'), "f");
        assertEquals(g, track.getKeySignature('g'), "g");
        assertEquals(a, track.getKeySignature('a'), "a");
        assertEquals(b, track.getKeySignature('b'), "b");
    }

    @Test
    @DisplayName("Shuffle rhythm")
    void testShuffle() {
        track.setShuffle(4);
        track.addNote(0, 24);
        track.addNote(0, 24);
        assertEquals(28, track.getEvent(0).onTime);
        assertEquals(20, track.getEvent(1).onTime);
        track.addNote(0, 24);
        track.addRest(24);
        assertEquals(28, track.getEvent(2).onTime);
        assertEquals(20, track.getEvent(3).offTime);
        track.addNote(0, 24);
        track.addTie(24);
        assertEquals(48, track.getEvent(4).onTime);
    }
}
