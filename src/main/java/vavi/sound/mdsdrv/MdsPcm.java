package vavi.sound.mdsdrv;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
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

    private static final int Z_VTAB = 0x0f00;

    // Internal state for playback
    private static class ChannelState {
        int id; // 0, 1, 2
        boolean active;
        int pcmAddr; // current offset in bank (0-7FFF)
        int bank;
        int pitch;
        int volume;
        int stepsRemaining; 
        int subAccumulator; // DDA accumulator (was pitIndex)
    }

    private ChannelState[] channels = new ChannelState[3];
    private double accumulator = 0;
    private double hostSampleRate = 44100.0;

    private Consumer<ChipDatum> fmCallback;

    public MdsPcm(byte[] z80Ram, int sampleRate) {
        this.z80Ram = z80Ram;
        for (int i = 0; i < 3; i++) {
            channels[i] = new ChannelState();
            channels[i].id = i;
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
                
                // logger.log(Level.INFO, "PCM Sample: " + mixedSample);

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
            
            // Log KeyOn event
            logger.log(Level.INFO, String.format("MdsPcm: KeyOn Ch=%d Bank=%02x Addr=%04x Pitch=%02x Mode=%d", chIdx, ch.bank, ch.pcmAddr, ch.pitch, mode));

            z80Ram[zOffset + ZP_KEY_ON] = 0;
            ch.subAccumulator = 0;
            ch.active = true;
             int cntL = z80Ram[zOffset + 6] & 0xFF;
             int cntH = z80Ram[zOffset + 7] & 0xFF;
             ch.stepsRemaining = (cntH << 8) | cntL;
        }

        ChannelState ch = channels[chIdx];
        if (!ch.active) return -999;
        
        int vol = z80Ram[zOffset + ZP_VOL] & 0xFF;
        if ((vol & 0x80) != 0) {
            z80Ram[zOffset + ZP_KEY_ON] = 0;
            ch.active = false;
            return -999;
        }
        
        // Pitch processing using DDA (matching Z80 driver table logic)
        // Rate = 0x10 + (pitch * 2) -> 1.0x to 2.0x
        int rate = 0x10 + (ch.pitch * 2);

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

            if (step > 0) {
                if (pcmPtr != null) {
                    int globalOffset = (ch.bank * 0x8000) + (ch.pcmAddr & 0x7FFF);
                    currentSample = pcmPtr.read8(globalOffset) & 0xFF;
                }
                
                // Decrement duration steps
                ch.stepsRemaining -= step;
                if (ch.stepsRemaining <= 0) {
                     ch.active = false;
                     return -999;
                }
            } else {
                // If step is 0 (holding sample), we still need to output the *current* sample?
                // But loop iteration output depends on 'currentSample'. 
                // If checking 'step > 0' prevents reading, 'currentSample' remains 0 (or previous?).
                // We should initialize 'currentSample' with actual current sample if hold?
                // But simplified: assuming we read at least once or reuse previous?
                // 'currentSample' is local var init to 0. 
                // If step==0 all loops, silence?
                // Actually, if holding, we should output the sample at current address.
                
                if (pcmPtr != null) {
                    int globalOffset = (ch.bank * 0x8000) + (ch.pcmAddr & 0x7FFF);
                    currentSample = pcmPtr.read8(globalOffset) & 0xFF;
                }
            }

        
        int finalVolAddr = (vol << 8) | currentSample; 
        byte ret = z80Ram[finalVolAddr];
        
        return ret;
    }
}
