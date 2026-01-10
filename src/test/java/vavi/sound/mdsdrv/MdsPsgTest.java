package vavi.sound.mdsdrv;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import vavi.sound.mdsdrv.MdsDrv.Memory;
import vavi.sound.mdsdrv.MdsDrv.WorkArea;

public class MdsPsgTest {

    @Test
    public void testPsgOutput() throws Exception {
        MdsDrv driver = new MdsDrv();
        WorkArea workArea = new WorkArea();
        
        // Load MDS file
        byte[] mdsData = Files.readAllBytes(Paths.get("src/test/resources/data/bgm/jazzy_nyc_99.mds"));
        MdsDrv.ByteArrayMemory mdsMem = new MdsDrv.ByteArrayMemory(mdsData);
        
        List<Integer> actualPsgLog = new ArrayList<>();
        
        // Use an anonymous subclass to hook into the PSG writer (write8 to 0xC00011)
        driver = new MdsDrv() {
            @Override
            protected Memory getPsgMemory() {
                final Memory wrapped = super.getPsgMemory();
                return new Memory() {
                    @Override public void write8(int addr, int data) {
                        if (addr == 0xC00011) {
                            actualPsgLog.add(data & 0xff);
                        }
                        // Always pass through to the underlying memory/handler just in case
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
            // Stub out other IO to prevent noise/errors
            @Override protected void writeIo(int port, int data) {}
            @Override protected void write_fm_port0(int addr, int data) {}
            @Override protected void write_fm_port1(int addr, int data) {}
        };
        
        driver.mds_top(workArea, mdsMem, null);
        driver.mds_request(workArea, 1, 0); // Start Song 1 (Index 1)

        // Run valid number of frames to capture initialization
        for (int i = 0; i < 200; i++) {
            driver.mds_update(workArea);
        }

        // User Provided "Correct" Log
        int[] expectedSequence = {
            0x9f, 0xbf, 0xff, 0x9f, 0x90, 0x82, 0x0e, 0xbf, 0xa0, 0x00, 
            0xe7, 0xff, 0xf3, 0xc3, 0x00, 0x91, 0xf7, 0x92, 0xfb, 0x94, 
            0xff, 0x95, 0xbf, 0xb2, 0xa0, 0x0e, 0xff, 0xf3, 0xb3, 0xf7, 
            0xb4, 0xfb, 0xb6, 0xff, 0xb7, 0xff, 0xf3, 0xf4, 0xf5, 0xf6, 
            0xf7, 0xf8, 0xf9, 0xfa, 0xfb, 0x90, 0x86, 0x0d, 0xff, 0xf3, 
            0x91, 0x8d, 0x0c, 0xf7, 0x92, 0x85, 0x0c, 0xfb, 0x94
        };

        System.out.println("Actual Log Size: " + actualPsgLog.size());
        
        // Find sequence
        int matchIdx = -1;
        for (int i = 0; i <= actualPsgLog.size() - expectedSequence.length; i++) {
            boolean match = true;
            for (int j = 0; j < expectedSequence.length; j++) {
                if (actualPsgLog.get(i + j) != expectedSequence[j]) {
                    match = false;
                    break;
                }
            }
            if (match) {
                matchIdx = i;
                break;
            }
        }

        if (matchIdx != -1) {
            System.out.println("Matched at index: " + matchIdx);
        } else {
             System.out.println("Actual Start: " + actualPsgLog.subList(0, Math.min(60, actualPsgLog.size())));
             Assertions.fail("Did not find expected user sequence.");
        }
    }
}
