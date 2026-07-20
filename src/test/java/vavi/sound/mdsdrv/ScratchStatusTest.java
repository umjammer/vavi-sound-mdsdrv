package vavi.sound.mdsdrv;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import musicDriverInterface.ChipAction;
import musicDriverInterface.ChipDatum;
import musicDriverInterface.IDriver;
import musicDriverInterface.MmlDatum;
import vavi.util.compat.Tuple;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;


/** scratch: does getStatus() go 1 -> 0 exactly once, at the real end of the song? */
@Disabled("for ai iteration")
public class ScratchStatusTest {

    static class Act implements ChipAction {
        @Override public String getChipName() { return ""; }
        @Override public void waitSend(long a, int b) {}
        @Override public void writePCMData(byte[] d, int s, int e) {}
        @Override public void writeRegister(ChipDatum cd) {}
    }

    @Test
    public void test() throws Exception {
        Path p = Path.of("src/test/resources/data/bgm/junkers_high.mds");
        byte[] data = Files.readAllBytes(p);

        IDriver drv = IDriver.factory("vavi.sound.mdsdrv.driver.MdsDriver");
        List<ChipAction> acts = List.of(new Act(), new Act());
        List<MmlDatum> buf = new ArrayList<>();
        for (byte b : data) buf.add(new MmlDatum(b & 0xff));
        drv.init(new ArrayList<>(acts), buf.toArray(MmlDatum[]::new), null, p.toString());
        drv.startRendering(44100, new Tuple<>("", 3579545));

        assertEquals(1, drv.getStatus(), "before startMusic");
        drv.startMusic(0);

        int transitions = 0;
        double stoppedAt = -1;
        int prev = drv.getStatus();
        assertEquals(1, prev, "at startMusic");

        int samples = 44100 * 200;
        for (int i = 0; i < samples; i++) {
            drv.render();
            int st = drv.getStatus();
            if (st != prev) {
                transitions++;
                System.out.printf("status %d -> %d at %.2fs%n", prev, st, i / 44100.0);
                if (st == 0) stoppedAt = i / 44100.0;
                prev = st;
            }
        }

        assertEquals(1, transitions, "status must flip exactly once");
        assertEquals(0, drv.getStatus(), "must end stopped");
        assertTrue(stoppedAt > 180, "must not stop early, was " + stoppedAt);
        System.out.println("OK: stopped at " + stoppedAt + "s");
    }
}
