package vavi.sound.mdsdrv;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import vavi.sound.mdsdrv.MdsDrv.WorkArea;


/**
 * Test to compare MDS driver FM writes against VGM reference log.
 * Focus on FM6 (drum channel) to debug drum issues.
 */
public class MdsFmCompareTest {

    /**
     * Represents a single FM write operation: port, addr, data
     */
    static class FmWrite {
        int port;
        int addr;
        int data;

        FmWrite(int port, int addr, int data) {
            this.port = port;
            this.addr = addr;
            this.data = data;
        }

        @Override
        public String toString() {
            return String.format("%02X, %02X, %02X", port, addr, data);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            FmWrite that = (FmWrite) o;
            return port == that.port && addr == that.addr && data == that.data;
        }
    }

    /**
     * Parse VGM log file to extract FM6-related writes.
     * FM6 is channel 2 on port 1, or key-on writes with channel 6 (0xF6).
     */
    static List<FmWrite> parseVgmLog(Path path, boolean fm6Only) throws IOException {
        List<FmWrite> writes = new ArrayList<>();
        List<String> lines = Files.readAllLines(path);

        for (String line : lines) {
            line = line.trim();
            if (line.isEmpty()) continue;

            String[] parts = line.split(",\\s*");
            if (parts.length != 3) continue;

            try {
                int port = Integer.parseInt(parts[0].trim(), 16);
                int addr = Integer.parseInt(parts[1].trim(), 16);
                int data = Integer.parseInt(parts[2].trim(), 16);

                if (fm6Only) {
                    // FM6 = port 1, channel 2 (registers ending in 2: 0x32, 0x42, etc.)
                    // Or key-on writes (addr=0x28) with channel 6 bits
                    if (port == 1 && (addr & 0x03) == 2) {
                        writes.add(new FmWrite(port, addr, data));
                    } else if (addr == 0x28 && (data & 0x07) == 6) {
                        // Key-on for channel 6
                        writes.add(new FmWrite(port, addr, data));
                    }
                } else {
                    writes.add(new FmWrite(port, addr, data));
                }
            } catch (NumberFormatException e) {
                // Skip malformed lines
            }
        }

        return writes;
    }

    @Test
    public void testCompareFm6() throws Exception {
        Path mdsFile = Paths.get("src/test/resources/data/bgm/jazzy_nyc_99.mds");
        Path vgmLogFile = Paths.get("tmp/vgm_fm_log.txt");

        if (!Files.exists(mdsFile)) {
            System.out.println("MDS file not found: " + mdsFile.toAbsolutePath());
            return;
        }
        if (!Files.exists(vgmLogFile)) {
            System.out.println("VGM log not found: " + vgmLogFile.toAbsolutePath());
            return;
        }

        // Parse VGM reference (FM6 only)
        List<FmWrite> vgmWrites = parseVgmLog(vgmLogFile, true);
        System.out.println("VGM FM6 writes: " + vgmWrites.size());

        // Capture MDS driver output
        byte[] mdsData = Files.readAllBytes(mdsFile);
        Memory mdsMem = new MdsDrv.ByteArrayMemory(mdsData);


        List<FmWrite> mdsWrites = new ArrayList<>();

        MdsDrv driver = new MdsDrv() {
            @Override
            protected void write_fm_port0(int addr, int data) {
                // Key-on for channel 6 (FM6)
                if (addr == 0x28 && (data & 0x07) == 6) {
                    mdsWrites.add(new FmWrite(0, addr, data));
                }
            }

            @Override
            protected void write_fm_port1(int addr, int data) {
                // FM6 = channel 2 on port 1
                if ((addr & 0x03) == 2) {
                    mdsWrites.add(new FmWrite(1, addr, data));
                }
            }
        };

        WorkArea work = new WorkArea();
        driver.mds_top(work, mdsMem, null);
        driver.mds_request(work, 1, 0);

        int frames = 300; // 5 seconds at 60Hz
        int[] noteCounts = new int[256];
        int[] pitchHi = new int[256];
        for (int i = 0; i < frames; i++) {
            driver.mds_update(work);
        }

        // Find pitch writes to check for difference
        System.out.println("\n=== Pitch Register Writes (0xA6, 0xA2) ===");
        System.out.println("VGM FM6 pitch writes (first 10):");
        int vgmPitchCount = 0;
        for (int i = 0; i < vgmWrites.size() && vgmPitchCount < 10; i++) {
            FmWrite w = vgmWrites.get(i);
            if (w.addr == 0xA6 || w.addr == 0xA2) {
                System.out.printf("[%3d] %s%n", i, w);
                vgmPitchCount++;
            }
        }
        
        System.out.println("MDS FM6 pitch writes (first 10):");
        int mdsPitchCount = 0;
        for (int i = 0; i < mdsWrites.size() && mdsPitchCount < 10; i++) {
            FmWrite w = mdsWrites.get(i);
            if (w.addr == 0xA6 || w.addr == 0xA2) {
                System.out.printf("[%3d] %s%n", i, w);
                mdsPitchCount++;
            }
        }


        // Find first complete instrument load sequence (between mute and key-on)
        System.out.println("\n=== First Hi-Hat Instrument Load (Alg=0x02) ===");
        
        // VGM: Find first sequence with B2=02
        int vgmSeqStart = -1;
        for (int i = 0; i < vgmWrites.size(); i++) {
            FmWrite w = vgmWrites.get(i);
            if (w.addr == 0xB2 && w.data == 0x02) {
                // Find start of instrument load (first 0x32 write)
                for (int j = i - 50; j < i; j++) {
                    if (j >= 0 && vgmWrites.get(j).addr == 0x32) {
                        vgmSeqStart = j;
                        break;
                    }
                }
                break;
            }
        }
        
        // MDS: Find first sequence with B2=02
        int mdsSeqStart = -1;
        for (int i = 0; i < mdsWrites.size(); i++) {
            FmWrite w = mdsWrites.get(i);
            if (w.addr == 0xB2 && w.data == 0x02) {
                // Find start of instrument load (first 0x32 write)
                for (int j = i - 50; j < i; j++) {
                    if (j >= 0 && mdsWrites.get(j).addr == 0x32) {
                        mdsSeqStart = j;
                        break;
                    }
                }
                break;
            }
        }
        
        if (vgmSeqStart >= 0 && mdsSeqStart >= 0) {
            System.out.println("\nVGM sequence (start at " + vgmSeqStart + "):");
            for (int i = vgmSeqStart; i < Math.min(vgmSeqStart + 35, vgmWrites.size()); i++) {
                System.out.printf("[%3d] %s%n", i, vgmWrites.get(i));
            }
            
            System.out.println("\nMDS sequence (start at " + mdsSeqStart + "):");
            for (int i = mdsSeqStart; i < Math.min(mdsSeqStart + 35, mdsWrites.size()); i++) {
                System.out.printf("[%3d] %s%n", i, mdsWrites.get(i));
            }
            
            // Compare register by register
            System.out.println("\n=== Register-by-Register Comparison ===");
            int[] regOrder = {0x32, 0x36, 0x3A, 0x3E, 0x52, 0x56, 0x5A, 0x5E, 
                              0x62, 0x66, 0x6A, 0x6E, 0x72, 0x76, 0x7A, 0x7E,
                              0x82, 0x86, 0x8A, 0x8E, 0x92, 0x96, 0x9A, 0x9E, 0xB2};
            String[] regNames = {"DT/MUL1", "DT/MUL2", "DT/MUL3", "DT/MUL4",
                                 "AR1", "AR2", "AR3", "AR4",
                                 "DR1", "DR2", "DR3", "DR4",
                                 "SR1", "SR2", "SR3", "SR4",
                                 "SL/RR1", "SL/RR2", "SL/RR3", "SL/RR4",
                                 "SSG1", "SSG2", "SSG3", "SSG4", "ALG"};
            
            for (int r = 0; r < regOrder.length; r++) {
                int vgmVal = -1, mdsVal = -1;
                for (int i = vgmSeqStart; i < Math.min(vgmSeqStart + 35, vgmWrites.size()); i++) {
                    if (vgmWrites.get(i).addr == regOrder[r]) {
                        vgmVal = vgmWrites.get(i).data;
                        break;
                    }
                }
                for (int i = mdsSeqStart; i < Math.min(mdsSeqStart + 35, mdsWrites.size()); i++) {
                    if (mdsWrites.get(i).addr == regOrder[r]) {
                        mdsVal = mdsWrites.get(i).data;
                        break;
                    }
                }
                String match = (vgmVal == mdsVal) ? "✓" : "✗ DIFF!";
                System.out.printf("%-8s (0x%02X): VGM=%02X MDS=%02X %s%n", 
                    regNames[r], regOrder[r], vgmVal, mdsVal, match);
            }
        }


        // Compare and find first differences
        int maxCompare = Math.min(vgmWrites.size(), mdsWrites.size());
        int differences = 0;
        int maxDiffsToShow = 50;

        System.out.println("\n=== Comparing FM6 writes ===");

        // Check Algorithm (reg 0xB2) to identify instrument type
        System.out.println("\n=== Algorithm/Feedback (reg 0xB2 = drum type) ===");
        System.out.println("VGM B2 writes (first 15):");
        int vgmB2Count = 0;
        for (int i = 0; i < vgmWrites.size() && vgmB2Count < 15; i++) {
            FmWrite w = vgmWrites.get(i);
            if (w.addr == 0xB2) {
                System.out.printf("[%3d] %s%n", i, w);
                vgmB2Count++;
            }
        }

        System.out.println("MDS B2 writes (first 15):");
        int mdsB2Count = 0;
        for (int i = 0; i < mdsWrites.size() && mdsB2Count < 15; i++) {
            FmWrite w = mdsWrites.get(i);
            if (w.addr == 0xB2) {
                System.out.printf("[%3d] %s%n", i, w);
                mdsB2Count++;
            }
        }

        // Find first VGM writes for reference

        System.out.println("\nFirst 20 VGM FM6 writes:");
        for (int i = 0; i < Math.min(20, vgmWrites.size()); i++) {
            System.out.printf("[%3d] %s%n", i, vgmWrites.get(i));
        }

        System.out.println("\nFirst 20 MDS FM6 writes:");
        for (int i = 0; i < Math.min(20, mdsWrites.size()); i++) {
            System.out.printf("[%3d] %s%n", i, mdsWrites.get(i));
        }

        // Look for specific patterns - key-on commands
        System.out.println("\n=== Key-On Commands (reg 0x28) ===");
        System.out.println("VGM Key-Ons:");
        int vgmKeyOnCount = 0;
        for (int i = 0; i < vgmWrites.size() && vgmKeyOnCount < 10; i++) {
            FmWrite w = vgmWrites.get(i);
            if (w.addr == 0x28) {
                System.out.printf("[%3d] %s%n", i, w);
                vgmKeyOnCount++;
            }
        }

        System.out.println("MDS Key-Ons:");
        int mdsKeyOnCount = 0;
        for (int i = 0; i < mdsWrites.size() && mdsKeyOnCount < 10; i++) {
            FmWrite w = mdsWrites.get(i);
            if (w.addr == 0x28) {
                System.out.printf("[%3d] %s%n", i, w);
                mdsKeyOnCount++;
            }
        }

        // Check TL values (register 0x42, 0x46, 0x4A, 0x4E for FM6)
        System.out.println("\n=== TL Values (FM6 volume) ===");
        System.out.println("VGM TL writes (first 10):");
        int vgmTlCount = 0;
        for (int i = 0; i < vgmWrites.size() && vgmTlCount < 10; i++) {
            FmWrite w = vgmWrites.get(i);
            if (w.addr == 0x42 || w.addr == 0x46 || w.addr == 0x4A || w.addr == 0x4E) {
                System.out.printf("[%3d] %s%n", i, w);
                vgmTlCount++;
            }
        }

        System.out.println("MDS TL writes (first 10):");
        int mdsTlCount = 0;
        for (int i = 0; i < mdsWrites.size() && mdsTlCount < 10; i++) {
            FmWrite w = mdsWrites.get(i);
            if (w.addr == 0x42 || w.addr == 0x46 || w.addr == 0x4A || w.addr == 0x4E) {
                System.out.printf("[%3d] %s%n", i, w);
                mdsTlCount++;
            }
        }
    }
}
