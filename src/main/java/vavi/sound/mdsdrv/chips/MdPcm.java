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
 * PCM HLE State - High Level Emulation of Z80 PCM playback routine.
 *
 * Ported from mdssub.z80 m2_loop (lines 458-564):
 * - Uses pitch_update tables to control sample timing
 * - Implements self-modifying code behavior via sample-skipping
 * - Handles bank switching and count decrementing
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 2026-01-15 nsano initial version <br>
 */
public class MdPcm {

    private static final Logger logger = System.getLogger(MdPcm.class.getName());

    // Pitch update tables from mdssub.z80 lines 1668-1688
    // These tables control which samples are read (0x23=read) vs skipped (0x00=nop)
    // Each row represents one pitch level (0-7), with 8 bytes per row
    private static final byte[] PITCH_UPDATE_FILL = {
        (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x23,  // pitch 0: read 1/8
        (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x23, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x23,  // pitch 1: read 2/8
        (byte)0x00, (byte)0x00, (byte)0x23, (byte)0x00, (byte)0x00, (byte)0x23, (byte)0x00, (byte)0x23,  // pitch 2: read 3/8
        (byte)0x00, (byte)0x23, (byte)0x00, (byte)0x23, (byte)0x00, (byte)0x23, (byte)0x00, (byte)0x23,  // pitch 3: read 4/8
        (byte)0x00, (byte)0x23, (byte)0x00, (byte)0x23, (byte)0x00, (byte)0x23, (byte)0x23, (byte)0x23,  // pitch 4: read 5/8
        (byte)0x00, (byte)0x23, (byte)0x23, (byte)0x23, (byte)0x00, (byte)0x23, (byte)0x23, (byte)0x23,  // pitch 5: read 6/8
        (byte)0x00, (byte)0x23, (byte)0x23, (byte)0x23, (byte)0x23, (byte)0x23, (byte)0x23, (byte)0x23,  // pitch 6: read 7/8
        (byte)0x23, (byte)0x23, (byte)0x23, (byte)0x23, (byte)0x23, (byte)0x23, (byte)0x23, (byte)0x23   // pitch 7: read 8/8
    };

    private static final byte[] PITCH_UPDATE_MIX = {
        (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x13,  // pitch 0: read 1/8
        (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x13, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x13,  // pitch 1: read 2/8
        (byte)0x00, (byte)0x00, (byte)0x13, (byte)0x00, (byte)0x00, (byte)0x13, (byte)0x00, (byte)0x13,  // pitch 2: read 3/8
        (byte)0x00, (byte)0x13, (byte)0x00, (byte)0x13, (byte)0x00, (byte)0x13, (byte)0x00, (byte)0x13,  // pitch 3: read 4/8
        (byte)0x00, (byte)0x13, (byte)0x00, (byte)0x13, (byte)0x00, (byte)0x13, (byte)0x13, (byte)0x13,  // pitch 4: read 5/8
        (byte)0x00, (byte)0x13, (byte)0x13, (byte)0x13, (byte)0x00, (byte)0x13, (byte)0x13, (byte)0x13,  // pitch 5: read 6/8
        (byte)0x00, (byte)0x13, (byte)0x13, (byte)0x13, (byte)0x13, (byte)0x13, (byte)0x13, (byte)0x13,  // pitch 6: read 7/8
        (byte)0x13, (byte)0x13, (byte)0x13, (byte)0x13, (byte)0x13, (byte)0x13, (byte)0x13, (byte)0x13   // pitch 7: read 8/8
    };

    private static class PcmChannel {

        boolean active = false;
        int address;
        int length;
        int count;      // Z80 count value - iterations remaining
        int pitch;      // Z80 pitch value (raw from MDS)
        int pitchTableRow;  // Which row of pitch_update table to use (0-7)
        int volume;     // Z80 volume value (15-31 range from mds_z80_get_vol)
        int bank;       // Current bank for address window
        int sampleIndex;    // Current sample position in PCM data
        int loopCounter;    // Tracks position within 8-iteration loop (0-7)
        byte[] pitchTable;  // Pointer to pitch_update_fill or pitch_update_mix table
    }

    // PCM channels: z_pcm1, z_pcm2, z_pcm3
    // Offsets within pcmChannels array correspond to Z80 channel addresses
    private final PcmChannel[] pcmChannels = {new PcmChannel(), new PcmChannel(), new PcmChannel()};
    private static final int PITCH_OFFSET_FILL = 0xF8;  // For z_pcm1 (pitch_update_fill table)
    private static final int PITCH_OFFSET_MIX = 0x38;   // For z_pcm2 (pitch_update_mix table)

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

            // Check for KeyOn on any of the 3 PCM channels
            int channelIndex = -1;
            int pcmBase = -1;
            int pitchOffset = -1;
            byte[] pitchTable = null;

            if (addr == MdDef.z_pcm1 + MdDef.zp_key_on && val == 1) {
                channelIndex = 0;
                pcmBase = MdDef.z_pcm1;
                pitchOffset = PITCH_OFFSET_FILL;
                pitchTable = PITCH_UPDATE_FILL;
            } else if (addr == MdDef.z_pcm2 + MdDef.zp_key_on && val == 1) {
                channelIndex = 1;
                pcmBase = MdDef.z_pcm2;
                pitchOffset = PITCH_OFFSET_MIX;
                pitchTable = PITCH_UPDATE_MIX;
            } else if (addr == MdDef.z_pcm3 + MdDef.zp_key_on && val == 1) {
                channelIndex = 2;
                pcmBase = MdDef.z_pcm3;
                pitchOffset = PITCH_OFFSET_FILL;  // PCM3 uses fill table
                pitchTable = PITCH_UPDATE_FILL;
            }

            if (channelIndex >= 0) {
                int offsetBase = pcmBase - MdDef.z80_ram;

                // Read all parameters from Z80 RAM
                int bank = z80RamData[offsetBase + MdDef.zp_bank] & 0xFF;
                int low = z80RamData[offsetBase + MdDef.zp_addr] & 0xFF;
                int mid = z80RamData[offsetBase + MdDef.zp_addr + 1] & 0xFF;
                int pitch = z80RamData[offsetBase + MdDef.zp_pitch] & 0xFF;
                int volume = z80RamData[offsetBase + MdDef.zp_vol] & 0xFF;
                int countH = z80RamData[offsetBase + MdDef.zp_count] & 0xFF;
                int countL = z80RamData[offsetBase + MdDef.zp_count + 1] & 0xFF;
                int count = (countH << 8) | countL;

                // Convert Z80 window address to 68K offset
                int z80Addr = (mid << 8) | low;
                int startAddr = (bank << 15) | (z80Addr & 0x7FFF);

                System.err.printf("PCM_KEYON[%d]: addr=%06X pitch=%d vol=%d count=%d%n", channelIndex, startAddr, pitch, volume, count);

                // Initialize channel state
                pcmChannels[channelIndex].active = true;
                pcmChannels[channelIndex].address = startAddr;
                pcmChannels[channelIndex].bank = bank;
                pcmChannels[channelIndex].pitch = pitch & 0xFF;
                pcmChannels[channelIndex].volume = volume;
                pcmChannels[channelIndex].count = count;
                pcmChannels[channelIndex].sampleIndex = 0;
                pcmChannels[channelIndex].loopCounter = 0;
                pcmChannels[channelIndex].pitchTable = pitchTable;

                // Calculate pitch table index from pitch value
                int a = ((pitch * 8 + pitchOffset) & 0xFF);
                int pitchValue = (a >> 3) & 0x07;
                pcmChannels[channelIndex].pitchTableRow = pitchValue;

                logger.log(Level.INFO, "PCM_KEYON[" + channelIndex + "]: pitch=" + pitch + " offset=0x" + Integer.toHexString(pitchOffset) + " row=" + pitchValue);
            }
        }
    }

    /** */
    public int read(int addr) {
        int offset = addr - MdDef.z80_ram;
        if (offset >= 0 && offset < z80RamData.length) return z80RamData[offset] & 0xFF;
        return 0;
    }

    /**
     * Update PCM playback - generates one audio sample per call.
     *
     * Ported from mdssub.z80 m2_pcm1_loop (lines 492-564):
     * - Implements frame-based, table-driven sample reading
     * - 8 iterations per count decrement
     * - Each iteration either reads or skips based on pitch_update table
     */
    public void update(int[] data) {
        if (workReader == null) {
            return;  // workReader not initialized yet
        }

        for (int ch = 0; ch < pcmChannels.length; ch++) {
            PcmChannel pc = pcmChannels[ch];
            if (!pc.active || pc.count <= 0) {
                pc.active = false;
                continue;
            }

            try {
                // Get the correct pitch table for this channel (FILL or MIX)
                byte[] pitchTable = pc.pitchTable;
                if (pitchTable == null) {
                    pc.active = false;
                    continue;
                }

                // Get the 8-byte pattern for this pitch
                int tableIndex = pc.pitchTableRow * 8;
                int pitchByte = pitchTable[tableIndex + pc.loopCounter] & 0xFF;

                // Check if this iteration should read (0x23=INC HL or 0x13=INC DE) or skip (0x00=nop)
                if (pitchByte != 0x00) {
                    // Non-zero = read one PCM sample (whether 0x23 or 0x13)
                    int sample = workReader.apply(pc.address + pc.sampleIndex);

                    // Unsigned 8-bit (0..255) centered at 128
                    int val = (sample & 0xFF) - 128;

                    // Read volume from Z80 RAM
                    int pcmBase = MdDef.z_pcm1;
                    int volume = z80RamData[(pcmBase - MdDef.z80_ram) + MdDef.zp_vol] & 0xFF;

                    // Apply volume scaling (15-31 range -> 0.0-1.0)
                    if (volume < 15) volume = 15;
                    if (volume > 31) volume = 31;
                    double volScale = (volume - 15) / 16.0;
                    val = (int) (val * volScale * 64);

                    if (!Boolean.parseBoolean(System.getProperty("vavi.sound.mdsdrv.skipPcm", "false"))) {
                        data[0] += val;
                        data[1] += val;
                    }

                    // Advance sample position
                    pc.sampleIndex++;
                }
                // If pitchByte == 0x00 (nop), skip reading - output silence

                // Move to next iteration in the 8-iteration loop
                pc.loopCounter++;
                if (pc.loopCounter >= 8) {
                    // After 8 iterations, decrement count and reset loop
                    pc.loopCounter = 0;
                    pc.count--;
                    if (pc.count <= 0) {
                        pc.active = false;
                    }
                }
            } catch (Exception e) {
                // Out of bounds - stop playback
                pc.active = false;
            }
        }
    }
}
