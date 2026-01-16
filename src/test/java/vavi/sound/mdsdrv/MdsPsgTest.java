package vavi.sound.mdsdrv;

import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import vavi.sound.mdsdrv.MdsDrv.WorkArea;


@Disabled("it's not guaranteed that vgm output is the same as mds output")
public class MdsPsgTest {

    // Structure to hold PSG event with timing
    static class PsgEvent {
        int timeMs;
        int data;
        PsgEvent(int timeMs, int data) {
            this.timeMs = timeMs;
            this.data = data;
        }
    }

    @Test
    public void testPsgOutput() throws Exception {
        MdsDrv driver;
        WorkArea workArea = new WorkArea();
        
        // Load MDS file
        byte[] mdsData = Files.readAllBytes(Paths.get("src/test/resources/data/bgm/jazzy_nyc_99.mds"));
        
        // Parse VGM log with timing (format: "time_ms, hex_data")
        List<PsgEvent> vgmEvents = new ArrayList<>();
        for (String line : Files.readAllLines(Paths.get("tmp/vga_psg_log.txt"))) {
            line = line.trim();
            if (line.isEmpty()) continue;
            String[] parts = line.split(",\\s*");
            if (parts.length == 2) {
                int time = Integer.parseInt(parts[0].trim());
                int data = Integer.parseInt(parts[1].trim(), 16);
                vgmEvents.add(new PsgEvent(time, data));
            }
        }
        System.out.println("Loaded VGM events: " + vgmEvents.size());
        
        MdsDrv.ByteArrayMemory mdsMem = new MdsDrv.ByteArrayMemory(mdsData);
        
        List<PsgEvent> mdsEvents = new ArrayList<>();
        
        // Frame tracking: 1 frame ≈ 16.67ms at 60Hz (NTSC)
        final double MS_PER_FRAME = 1000.0 / 60.0;  // ~16.67ms
        final int[] frameCount = {0};
        
        // Hook PSG writes and track timing
        driver = new MdsDrv() {
            @Override
            protected Memory getPsgMemory() {
                final Memory wrapped = super.getPsgMemory();
                return new Memory() {
                    @Override public void write8(int addr, int data) {
                        if (addr == 0xC00011) {
                            int timeMs = (int)(frameCount[0] * MS_PER_FRAME);
                            mdsEvents.add(new PsgEvent(timeMs, data & 0xff));
                        }
                        wrapped.write8(addr, data);
                    }
                    @Override public int read8(int addr) { return wrapped.read8(addr); }
                    @Override public int read16(int addr) { return wrapped.read16(addr); }
                    @Override public int read32(int addr) { return wrapped.read32(addr); }
                    @Override public void write16(int addr, int data) { wrapped.write16(addr, data); }
                    @Override public void write32(int addr, int data) { wrapped.write32(addr, data); }
                    @Override public Memory add(int o) { return wrapped.add(o); }
                };
            }
            @Override protected void writeIo(int port, int data) {}
            @Override protected void write_fm_port0(int addr, int data) {}
            @Override protected void write_fm_port1(int addr, int data) {}
        };
        
        driver.mds_top(workArea, mdsMem, null);
        driver.mds_request(workArea, 1, 0);
        
        // Run for enough frames to match VGM timing
        // VGM max time we need (check last event)
        int maxVgmTime = vgmEvents.isEmpty() ? 0 : vgmEvents.get(vgmEvents.size() - 1).timeMs;
        int framesNeeded = (int)(maxVgmTime / MS_PER_FRAME) + 100;  // Extra frames for safety
        System.out.println("Max VGM time: " + maxVgmTime + "ms, running " + framesNeeded + " frames");
        
        for (int i = 0; i < framesNeeded; i++) {
            driver.mds_update(workArea);
            frameCount[0]++;
        }
        
        System.out.println("MDS events: " + mdsEvents.size());
        
        // Save MDS timed log
        try (PrintWriter pw = new PrintWriter("tmp/mds_psg_log_timed.txt")) {
            for (PsgEvent e : mdsEvents) {
                pw.printf("%d, %02x%n", e.timeMs, e.data);
            }
        }
        System.out.println("MDS timed log saved to tmp/mds_psg_log_timed.txt");
        
        // Extract note-on events for Ch0 (lead melody) to compare timing
        // Ch0 volume writes: 0x9X where X is volume (0x90 = vol 0 = loudest)
        // Look for 0x90..0x95 which are volume 0-5 (audible notes starting)
        System.out.println("\n===== Note Onset Comparison (Ch0 Volume Writes 0x90-0x95) =====");
        
        List<PsgEvent> vgmNoteOns = new ArrayList<>();
        List<PsgEvent> mdsNoteOns = new ArrayList<>();
        
        for (PsgEvent e : vgmEvents) {
            if ((e.data & 0xF0) == 0x90 && (e.data & 0x0F) <= 5) {
                vgmNoteOns.add(e);
            }
        }
        for (PsgEvent e : mdsEvents) {
            if ((e.data & 0xF0) == 0x90 && (e.data & 0x0F) <= 5) {
                mdsNoteOns.add(e);
            }
        }
        
        System.out.println("VGM Ch0 note-ons: " + vgmNoteOns.size());
        System.out.println("MDS Ch0 note-ons: " + mdsNoteOns.size());
        
        // Compare first 20 note-on timings
        int compareCount = Math.min(20, Math.min(vgmNoteOns.size(), mdsNoteOns.size()));
        System.out.println("\nFirst " + compareCount + " note-on timing comparison (time in ms):");
        System.out.println("  #  | VGM Time | MDS Time | Delta | Data");
        System.out.println("-----+----------+----------+-------+-----");
        
        int totalDelta = 0;
        for (int i = 0; i < compareCount; i++) {
            PsgEvent vgm = vgmNoteOns.get(i);
            PsgEvent mds = mdsNoteOns.get(i);
            int delta = mds.timeMs - vgm.timeMs;
            totalDelta += delta;
            System.out.printf(" %2d  | %8d | %8d | %+5d | VGM:0x%02x MDS:0x%02x%n",
                i, vgm.timeMs, mds.timeMs, delta, vgm.data, mds.data);
        }
        
        if (compareCount > 0) {
            System.out.println("-----+----------+----------+-------+-----");
            System.out.printf("Average delta: %+.1f ms%n", (double)totalDelta / compareCount);
        }
        
        // Also compare Ch1 note-ons (secondary PSG voice)
        System.out.println("\n===== Note Onset Comparison (Ch1 Volume Writes 0xB0-0xB5) =====");
        
        List<PsgEvent> vgmCh1NoteOns = new ArrayList<>();
        List<PsgEvent> mdsCh1NoteOns = new ArrayList<>();
        
        for (PsgEvent e : vgmEvents) {
            if ((e.data & 0xF0) == 0xB0 && (e.data & 0x0F) <= 5) {
                vgmCh1NoteOns.add(e);
            }
        }
        for (PsgEvent e : mdsEvents) {
            if ((e.data & 0xF0) == 0xB0 && (e.data & 0x0F) <= 5) {
                mdsCh1NoteOns.add(e);
            }
        }
        
        System.out.println("VGM Ch1 note-ons: " + vgmCh1NoteOns.size());
        System.out.println("MDS Ch1 note-ons: " + mdsCh1NoteOns.size());
        
        compareCount = Math.min(20, Math.min(vgmCh1NoteOns.size(), mdsCh1NoteOns.size()));
        if (compareCount > 0) {
            System.out.println("\nFirst " + compareCount + " Ch1 note-on timing comparison:");
            System.out.println("  #  | VGM Time | MDS Time | Delta");
            System.out.println("-----+----------+----------+-------");
            
            totalDelta = 0;
            for (int i = 0; i < compareCount; i++) {
                PsgEvent vgm = vgmCh1NoteOns.get(i);
                PsgEvent mds = mdsCh1NoteOns.get(i);
                int delta = mds.timeMs - vgm.timeMs;
                totalDelta += delta;
                System.out.printf(" %2d  | %8d | %8d | %+5d%n",
                    i, vgm.timeMs, mds.timeMs, delta);
            }
            System.out.println("-----+----------+----------+-------");
            System.out.printf("Average delta: %+.1f ms%n", (double)totalDelta / compareCount);
        }
        
        // Look for note-off events (volume going to 15 = mute: 0x9F, 0xBF, etc)
        System.out.println("\n===== Note-Off Timing (Ch0 0x9F = Mute) =====");
        
        List<PsgEvent> vgmMutes = new ArrayList<>();
        List<PsgEvent> mdsMutes = new ArrayList<>();
        
        for (PsgEvent e : vgmEvents) {
            if (e.data == 0x9F) vgmMutes.add(e);
        }
        for (PsgEvent e : mdsEvents) {
            if (e.data == 0x9F) mdsMutes.add(e);
        }
        
        System.out.println("VGM Ch0 mutes: " + vgmMutes.size());
        System.out.println("MDS Ch0 mutes: " + mdsMutes.size());
        
        compareCount = Math.min(10, Math.min(vgmMutes.size(), mdsMutes.size()));
        if (compareCount > 0) {
            System.out.println("\nFirst " + compareCount + " mute timing comparison:");
            System.out.println("  #  | VGM Time | MDS Time | Delta");
            System.out.println("-----+----------+----------+-------");
            
            for (int i = 0; i < compareCount; i++) {
                PsgEvent vgm = vgmMutes.get(i);
                PsgEvent mds = mdsMutes.get(i);
                int delta = mds.timeMs - vgm.timeMs;
                System.out.printf(" %2d  | %8d | %8d | %+5d%n",
                    i, vgm.timeMs, mds.timeMs, delta);
            }
        }
    }
}
