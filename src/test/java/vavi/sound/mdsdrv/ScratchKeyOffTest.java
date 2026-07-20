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


/** scratch: does a finished track key its FM channel off? */
@Disabled("for ai iteration")
public class ScratchKeyOffTest {

    static class Act implements ChipAction {
        final String name;
        final List<int[]> log;
        Act(String name, List<int[]> log) { this.name = name; this.log = log; }
        @Override public String getChipName() { return name; }
        @Override public void waitSend(long a, int b) {}
        @Override public void writePCMData(byte[] d, int s, int e) {}
        @Override public void writeRegister(ChipDatum cd) {
            if (cd == null) return;
            log.add(new int[] {name.equals("fm") ? 0 : 1, cd.port, cd.address, cd.data});
        }
    }

    @Test
    public void test() throws Exception {
        Path p = Path.of("src/test/resources/data/bgm/junkers_high.mds");
        byte[] data = Files.readAllBytes(p);

        List<int[]> log = new ArrayList<>();
        IDriver drv = IDriver.factory("vavi.sound.mdsdrv.driver.MdsDriver");
        List<ChipAction> acts = new ArrayList<>();
        acts.add(new Act("fm", log));
        acts.add(new Act("psg", log));
        List<MmlDatum> buf = new ArrayList<>();
        for (byte b : data) buf.add(new MmlDatum(b & 0xff));
        drv.init(acts, buf.toArray(MmlDatum[]::new), null, p.toString());
        drv.startRendering(44100, new Tuple<>("", 3579545));
        drv.startMusic(0);

        MdsDrv.WorkArea wa = (MdsDrv.WorkArea) drv.getWork().get("work");

        int samples = 44100 * 200;
        int endAt = -1;
        int prevActive = -1;
        for (int i = 0; i < samples; i++) {
            drv.render();
            int active = 0;
            for (MdsDrv.TrackData t : wa.w_track) if (t.t_request_id < MdsDrv.RCOUNT * 2) active++;
            if (active != prevActive) {
                System.out.printf("t=%.2fs active tracks=%d loopCnt=%d writes=%d%n",
                        i / 44100.0, active, drv.getNowLoopCounter(), log.size());
                prevActive = active;
            }
            if (endAt < 0 && active == 0 && i > 44100) {
                endAt = Math.max(0, log.size() - 40);
                System.out.printf("ALL TRACKS FREE at %.2fs%n", i / 44100.0);
                samples = i + 44100; // render 1 more second
            }
        }

        // key-on/off register on YM2612 is 0x28 on port 0: bits 4-7 = slots, low 3 = channel
        System.out.println("total writes: " + log.size());
        int tail = endAt < 0 ? Math.max(0, log.size() - 200) : endAt;
        int keyOffs = 0;
        for (int i = tail; i < log.size(); i++) {
            int[] w = log.get(i);
            if (w[0] == 0 && w[1] == 0 && w[2] == 0x28) {
                System.out.printf("  [%d] KEY reg28 = %02x %s%n", i, w[3], (w[3] & 0xf0) == 0 ? "<-- KEY OFF" : "");
                if ((w[3] & 0xf0) == 0) keyOffs++;
            }
        }
        System.out.println("key-offs after end: " + keyOffs);
    }
}
