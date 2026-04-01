package vavi.sound.mdsdrv;

import java.lang.System.Logger;

import java.util.function.Consumer;

import musicDriverInterface.ChipDatum;

import static java.lang.System.getLogger;

/**
 * MdsPcm - Emulates mdssub.z80 PCM mixing logic.
 */
public class MdsPcm {

    @SuppressWarnings("unused")
    private static final Logger logger = getLogger(MdsPcm.class.getName());

    private final byte[] z80Ram;
    
    // Z80 RAM offsets (from mdssub.inc)
    private static final int Z_PCM1 = 0x0e08;
    // private static final int Z_PCM2 = 0x0e08 + 8; // Unused
    // private static final int Z_PCM3 = 0x0e08 + 16; // Unused
    
    // Offsets within Z_PCM struct
    private static final int ZP_KEY_ON = 0;
    private static final int ZP_VOL = 1;
    private static final int ZP_PITCH = 2;
    private static final int ZP_BANK = 3;
    private static final int ZP_ADDR = 4;

    // Internal state for playback
    private static class ChannelState {
        boolean active;
        int pcmAddr; // current offset in bank (0-7FFF)
        int bank;
        int pitch;
        int stepsRemaining; 
        int subAccumulator; // DDA accumulator (was pitIndex)
    }

    private final ChannelState[] channels = new ChannelState[3];
    private double accumulator = 0;
    private double hostSampleRate = 44100.0;

    private Consumer<ChipDatum> fmCallback;

    public MdsPcm(byte[] z80Ram, int sampleRate) {
        this.z80Ram = z80Ram;
        for (int i = 0; i < 3; i++) {
            channels[i] = new ChannelState();
        }
        this.hostSampleRate = sampleRate;
    }
    
    public void setFmCallback(Consumer<ChipDatum> callback) {
        this.fmCallback = callback;
    }

    public void update(Memory pcmPtr) {
        int mode = z80Ram[0x0e05] & 0xFF; // z_mode

        double targetRate = 0;
        if (mode == 2) targetRate = 18000.0; // Approx 2xPCM
        else if (mode == 3) targetRate = 13500.0; // Approx 3xPCM
        else return;

        accumulator += targetRate / hostSampleRate;
        
        while (accumulator >= 1.0) {
            accumulator -= 1.0;
            // Generate 1 sample
            int mixedSample = 0;
            boolean hasSample = false;

            // PCM 1
            int sample1 = processChannel(0, pcmPtr, mode);
            if (sample1 != -999) {
                mixedSample += sample1;
                hasSample = true;
            }

            // PCM 2
            int sample2 = processChannel(1, pcmPtr, mode);
            if (sample2 != -999) {
                if (hasSample) mixedSample += sample2;
                else { mixedSample = sample2; hasSample = true; }
            }
            
            // PCM 3
            if (mode == 3) {
                int sample3 = processChannel(2, pcmPtr, mode);
                if (sample3 != -999) {
                    if (hasSample) mixedSample += sample3;
                    else { mixedSample = sample3; hasSample = true; }
                }
            }

            if (hasSample) {
                if (mixedSample > 127) mixedSample = 127; 
                if (mixedSample < -128) mixedSample = -128;
                
                if (mixedSample < -128) mixedSample = -128;
                
                // logger.log(Level.TRAE, "PCM Sample: " + mixedSample);

                if (fmCallback != null) {
                    // Convert signed (-128..127) to unsigned (0..255) for YM2612 DAC
                    // YM2612.java does (data - 0x80), so we must provide data where 0x80 is center.
                    fmCallback.accept(new ChipDatum(0, 0x2a, (mixedSample + 128) & 0xff));
                }
            }
        }
    }
    
    private int processChannel(int chIdx, Memory pcmPtr, int mode) {
        int zOffset = Z_PCM1 + (chIdx * 8);
        
        int keyOn = z80Ram[zOffset + ZP_KEY_ON] & 0xFF;
        if (keyOn == 0 && !channels[chIdx].active) return -999; 
        
        if (keyOn != 0) {
            ChannelState ch = channels[chIdx];
            ch.bank = z80Ram[zOffset + ZP_BANK] & 0xFF; 
            int addrL = z80Ram[zOffset + ZP_ADDR] & 0xFF;
            int addrH = z80Ram[zOffset + ZP_ADDR + 1] & 0xFF;
            ch.pcmAddr = (addrH << 8) | addrL;
            int pitch = z80Ram[zOffset + ZP_PITCH] & 0xFF;
            if (pitch != 0) ch.pitch = pitch;
            


            z80Ram[zOffset + ZP_KEY_ON] = 0;
            ch.subAccumulator = 0;
            ch.active = true;
             ch.subAccumulator = 0;
            ch.active = true;
             int cntL = z80Ram[zOffset + 6] & 0xFF;
             int cntH = z80Ram[zOffset + 7] & 0xFF;
             int rawCount = (cntH << 8) | cntL;
             
             // rawCount is Z80 Ticks (Loops).
             ch.stepsRemaining = rawCount;
        }

        ChannelState ch = channels[chIdx];
        if (!ch.active) return -999;
        
        int vol = z80Ram[zOffset + ZP_VOL] & 0xFF;
        if ((vol & 0x80) != 0) {
            z80Ram[zOffset + ZP_KEY_ON] = 0;
            ch.active = false;
            return -999;
        }
        
        // Pitch processing using DDA
        // Pitch Index corresponds to ~4kHz steps (1=4k, 2=8k, 4=16k etc.)
        int targetHz = ch.pitch * 4000;
        if (targetHz == 0) targetHz = 4000; // Safeguard
        
        // Z80 Driver Loop Rate (Approximate)
        double z80Rate = (mode == 3) ? 13500.0 : 18000.0;
        
        // rate (fixed point 12.4) = (TargetHz / Z80Hz) * 16
        int rate = (int) ((targetHz * 16.0) / z80Rate);
        if (rate == 0) rate = 1; // Minimum speed

        int currentSample = 0;
        
        // Single step per update (1 output sample)
        ch.subAccumulator += rate; // DDA accumulation
        int step = ch.subAccumulator >> 4;
        ch.subAccumulator &= 0x0F;
            
        ch.pcmAddr += step;
            
            while (ch.pcmAddr >= 0x10000) { 
                ch.pcmAddr -= 0x8000;
                ch.bank++;
            }

            if (pcmPtr != null) {
                int globalOffset = (ch.bank * 0x8000) + (ch.pcmAddr & 0x7FFF);
                currentSample = pcmPtr.read8(globalOffset) & 0xFF;
            }
            
            // Decrement duration steps (Ticks), not bytes
            ch.stepsRemaining--;
            if (ch.stepsRemaining <= 0) {
                 ch.active = false;
                 return -999;
            }

        
        int finalVolAddr = (vol << 8) | currentSample; 
        byte ret = z80Ram[finalVolAddr];
        
        return ret;
    }
}
