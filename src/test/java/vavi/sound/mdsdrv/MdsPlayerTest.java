package vavi.sound.mdsdrv;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.SourceDataLine;

import org.junit.jupiter.api.Test;

import vavi.sound.mdsdrv.MdsDrv.WorkArea;

public class MdsPlayerTest {

    @Test
    public void testPlay() throws Exception {
        Path mdsFile = Paths.get("data/bgm/sand_light.mds");
        if (!Files.exists(mdsFile)) {
            System.out.println("MDS file not found: " + mdsFile.toAbsolutePath());
            return;
        }

        System.out.println("Playing: " + mdsFile);

        // Load MDS file into Memory
        byte[] mdsData = Files.readAllBytes(mdsFile);

        // Create root memory
        Memory mdsMem = new ByteArrayMemory(mdsData, 0);

        // Driver with overriden IO
        MdsDrv driver = new MdsDrv() {
            @Override
            protected void write_fm_port0(int addr, int data) {
                // Debug: System.out.printf("FM0: %02x %02x%n", addr, data);
            }

            @Override
            protected void write_fm_port1(int addr, int data) {
                // Debug: System.out.printf("FM1: %02x %02x%n", addr, data);
            }

            @Override
            protected void writeIo(int port, int data) {
                // Debug: System.out.printf("IO: %02x %02x%n", port, data);
            }
        };

        WorkArea workArea = new WorkArea();
        driver.mds_init(workArea, mdsMem, mdsMem);

        // Trigger BGM0 or logical start
        workArea.w_request[0] = 0x2001; // Example request

        // Setup Audio
        AudioFormat format = new AudioFormat(44100, 16, 2, true, false);
        SourceDataLine line = null;
        try {
            line = AudioSystem.getSourceDataLine(format);
            line.open(format);
            line.start();
            System.out.println("Audio line started.");
        } catch (LineUnavailableException | IllegalArgumentException e) {
            System.out.println(
                    "Audio line unavailable (headless?), skipping audio output verification: " + e.getMessage());
        }

        long startTime = System.currentTimeMillis();
        byte[] buf = new byte[4096]; // Silence buffer

        // Run for 5 seconds
        while (System.currentTimeMillis() - startTime < 5000) {
            // Update Driver
            driver.mds_update(workArea);

            // Wait / Sync (Simulate 60Hz)
            try {
                Thread.sleep(16);
            } catch (InterruptedException e) {
                break;
            }

            // Write silence to keep line active
            if (line != null) {
                line.write(buf, 0, buf.length);
            }
        }

        if (line != null) {
            line.drain();
            line.close();
        }
        System.out.println("Playback finished.");
    }

    // Helper class for Memory backed by byte array
    static class ByteArrayMemory implements Memory {
        private final byte[] data;
        private final int offset;

        public ByteArrayMemory(byte[] data, int offset) {
            this.data = data;
            this.offset = offset;
        }

        @Override
        public int read8(int addr) {
            int pos = offset + addr;
            if (pos >= 0 && pos < data.length) {
                return data[pos] & 0xFF;
            }
            return 0;
        }

        @Override
        public int read16(int addr) {
            int pos = offset + addr;
            if (pos >= 0 && pos < data.length - 1) {
                int b1 = data[pos] & 0xFF;
                int b2 = data[pos + 1] & 0xFF; // Big Endian (68k)
                return (b1 << 8) | b2;
            }
            return 0;
        }

        @Override
        public int read32(int addr) {
            return (read16(addr) << 16) | read16(addr + 2);
        }

        @Override
        public void write8(int addr, int val) {
        }

        @Override
        public void write16(int addr, int val) {
        }

        @Override
        public void write32(int addr, int val) {
        }

        @Override
        public Memory add(int o) {
            return new ByteArrayMemory(data, offset + o);
        }
    }
}
