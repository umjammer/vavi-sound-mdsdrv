/*
 * Copyright (c) 2026 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.sound.mdsdrv.chips;


import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.function.Function;

import vavi.sound.mdsdrv.MdDef;


/**
 * PCM HLE State.
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 2026-01-15 nsano initial version <br>
 */
public class MdPcm {

    private static final Logger logger = System.getLogger(MdPcm.class.getName());

    private static class PcmChannel {

        boolean active = false;
        int address;
        int length;
        int pitch;
        int volume;  // Z80 volume value (15-31 range from mds_z80_get_vol)
        double pos;
        double step;
    }

    private final PcmChannel[] pcmChannels = {new PcmChannel(), new PcmChannel(), new PcmChannel()};

    private final byte[] z80RamData = new byte[0x2000];

    private Function<Integer, Integer> workReader;

    /** */
    public void setWorkReader(Function<Integer, Integer> workReader) {
        this.workReader = workReader;
logger.log(Level.INFO, "set work reader");
    }

    /** */
    public void write(int addr, int val) {
        int offset = addr - MdDef.z80_ram;
        if (offset >= 0 && offset < z80RamData.length) {
            z80RamData[offset] = (byte) val;

            // Check for KeyOn (zp_key_on offset from z_pcm1)
            if (addr == MdDef.z_pcm1 + MdDef.zp_key_on && val == 1) {
                int pcmBase = MdDef.z_pcm1;
                int bank = z80RamData[(pcmBase - MdDef.z80_ram) + MdDef.zp_bank] & 0xFF;
                int low = z80RamData[(pcmBase - MdDef.z80_ram) + MdDef.zp_addr] & 0xFF;
                int mid = z80RamData[(pcmBase - MdDef.z80_ram) + MdDef.zp_addr + 1] & 0xFF;
                // Z80 address: mid<<8|low (0x8000-0xFFFF is window into 68K)
                // Bank selects which 32KB page of 68K memory
                // Convert Z80 window address to 68K offset:
                // - Z80 addr 0x8000 maps to 68K addr = (bank << 15) | (z80_addr & 0x7FFF)
                int z80Addr = (mid << 8) | low;
                int startAddr = (bank << 15) | (z80Addr & 0x7FFF);
                int pitch = z80RamData[(pcmBase - MdDef.z80_ram) + MdDef.zp_pitch] & 0xFF;
                int volume = z80RamData[(pcmBase - MdDef.z80_ram) + MdDef.zp_vol] & 0xFF;

                System.err.printf("PCM_KEYON: addr=%06X pitch=%d vol=%d%n", startAddr, pitch, volume);
                pcmChannels[0].active = true;
                pcmChannels[0].address = startAddr;
                pcmChannels[0].volume = volume;
                pcmChannels[0].pos = 0;
                // Pitch to step conversion
                // Pitch value from RIFF header (e.g., 92) determines playback rate
                // Formula: PCM frequency = pitch * 100 Hz (empirical from test code)
                if (pitch == 0) pitch = 4;  // Default if not set
                double pcmFreq = pitch * 100.0;  // e.g., pitch=92 -> 9200 Hz
                pcmChannels[0].step = pcmFreq / 44100.0;
            }
        }
    }

    /** */
    public int read(int addr) {
        int offset = addr - MdDef.z80_ram;
        if (offset >= 0 && offset < z80RamData.length) return z80RamData[offset] & 0xFF;
        return 0;
    }

    /** */
    public void update(int[] data) {
        for (PcmChannel pc : pcmChannels) {
            if (pc.active) {
                int posInt = (int) pc.pos;

                // Basic bounds check if possible, or rely on catch
                // ByteArrayMemory throws IndexOutOfBoundsException
                int sample = 0;
                try {
                    sample = workReader.apply(pc.address + posInt);
                } catch (Exception e) {
logger.log(Level.ERROR, e.getMessage(), e);
                    pc.active = false;
                    continue;
                }

                // Unsigned 8-bit (0..255) centered at 128
                int val = (sample & 0xFF) - 128;

                // Read volume dynamically from z80RamData (it may be updated after key-on)
                int pcmBase = MdDef.z_pcm1;
                int volume = z80RamData[(pcmBase - MdDef.z80_ram) + MdDef.zp_vol] & 0xFF;

                // Apply volume from mds_z80_get_vol (range 15-31, where 31=loudest)
                // Scale: (volume - 15) / 16 gives 0.0 to 1.0
                // Then multiply by gain factor
                if (volume < 15) volume = 15;
                if (volume > 31) volume = 31;
                double volScale = (volume - 15) / 16.0;
                val = (int) (val * volScale * 64);  // Apply volume and gain

                if (!Boolean.parseBoolean(System.getProperty("vavi.sound.mdsdrv.skipPcm", "false"))) {
                    data[0] += val;
                    data[1] += val;
                }

                pc.pos += pc.step;
            }
        }
    }
}
