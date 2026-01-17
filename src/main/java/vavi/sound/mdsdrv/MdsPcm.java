package vavi.sound.mdsdrv;

import java.lang.System.Logger;
// import java.lang.System.Logger.Level; // Unused
import static java.lang.System.getLogger;
import java.util.function.Consumer;
import musicDriverInterface.ChipDatum;

/**
 * MdsPcm - Emulates mdssub.z80 PCM mixing logic.
 */
public class MdsPcm {

    @SuppressWarnings("unused")
    private static final Logger logger = getLogger(MdsPcm.class.getName());

    private final MdsDrv drv;
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

    // Pitch tables (mimic pitch_update_fill and pitch_update_mix)
    private static final int[][] PITCH_TABLE_FILL = {
        {0,0,0,0,0,0,0,1}, // 0
        {0,0,0,1,0,0,0,1}, // 1
        {0,0,1,0,0,1,0,1}, // 2
        {0,1,0,1,0,1,0,1}, // 3
        {0,1,0,1,0,1,1,1}, // 4
        {0,1,1,1,0,1,1,1}, // 5
        {0,1,1,1,1,1,1,1}, // 6
        {1,1,1,1,1,1,1,1}  // 7
    };
    private static final int[][] PITCH_TABLE_MIX = PITCH_TABLE_FILL;

    // Internal state for playback
    private static class ChannelState {
        int id; // 0, 1, 2
        boolean active;
        int pcmAddr; // current offset in bank (0-7FFF)
        int bank;
        int pitch;
        int volume;
        int stepsRemaining; 
        int pitIndex; // 0-7, index into pitch table
    }

    private ChannelState[] channels = new ChannelState[3];
    private double accumulator = 0;
    private double hostSampleRate = 44100.0;

    private Consumer<ChipDatum> fmCallback;

    public MdsPcm(MdsDrv drv, int sampleRate) {
        this.drv = drv;
        this.z80Ram = drv.z80RamBuffer;
        for (int i = 0; i < 3; i++) {
            channels[i] = new ChannelState();
            channels[i].id = i;
        }
        this.hostSampleRate = sampleRate;
    }
    
    public void setHostSampleRate(int rate) {
        this.hostSampleRate = rate;
    }
    
    public void setFmCallback(Consumer<ChipDatum> callback) {
        this.fmCallback = callback;
    }

    public void update(Memory pcmPtr, MdsDrv.WorkArea workArea) {
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
            ch.pitch = z80Ram[zOffset + ZP_PITCH] & 0xFF; 
            
            // Log KeyOn event
            // logger.log(Level.INFO, String.format("MdsPcm: KeyOn Ch=%d Bank=%02x Addr=%04x Pitch=%02x Mode=%d", chIdx, ch.bank, ch.pcmAddr, ch.pitch, mode));

            z80Ram[zOffset + ZP_KEY_ON] = 0;
            ch.pitIndex = 0;
            ch.active = true;
             int cntL = z80Ram[zOffset + 6] & 0xFF;
             int cntH = z80Ram[zOffset + 7] & 0xFF;
             ch.stepsRemaining = (cntH << 8) | cntL;
        }

        ChannelState ch = channels[chIdx];
        if (!ch.active) return -999;
        
        // Z80 loop dynamically checks pitch?
        // No, mdssub modifies the loop code on KeyOn/PitchChange.
        // But `z_pcm1_pitch` is checked periodically?
        // m2_loop does NOT check pitch. 
        // m2_key_on checks pitch.
        // So pitch is latched at KeyOn.
        // Wait, does it update pitch mid-note?
        // `m2_pcm1_key_on` updates it.
        // If we want to change pitch, we must re-trigger?
        // Or write to z_pcm1_pitch and trigger something?
        // `mdssub` has `check_bank` but not `check_pitch` in main loop.
        // So Pitch is constant per Note.
        
        int vol = z80Ram[zOffset + ZP_VOL] & 0xFF;
        if ((vol & 0x80) != 0) {
            z80Ram[zOffset + ZP_KEY_ON] = 0;
            ch.active = false;
            return -999;
        }
        
        // Pitch processing
        int pitch = ch.pitch & 7;
        int[] pattern = (mode == 2) ? PITCH_TABLE_FILL[pitch] : PITCH_TABLE_MIX[pitch];
        
        int slotsPerSample = 2; // Assuming 18kHz vs 8 slots loop
        int currentSample = 0;
        
        for (int k = 0; k < slotsPerSample; k++) {
            int step = pattern[ch.pitIndex];
            ch.pitIndex = (ch.pitIndex + 1) & 7;
            
            if (step == 1) {
                if (pcmPtr != null) {
                    int globalOffset = (ch.bank * 0x8000) + (ch.pcmAddr & 0x7FFF);
                    currentSample = pcmPtr.read8(globalOffset) & 0xFF;
                }
                
                ch.pcmAddr++;
                if (ch.pcmAddr >= 0x10000) { 
                    ch.pcmAddr = 0x8000;
                    ch.bank++;
                }

                ch.stepsRemaining--;
                if (ch.stepsRemaining <= 0) {
                     ch.active = false;
                     return -999;
                }
            } else {
                 if (pcmPtr != null) {
                     int globalOffset = (ch.bank * 0x8000) + (ch.pcmAddr & 0x7FFF);
                     currentSample = pcmPtr.read8(globalOffset) & 0xFF;
                 }
            }
        }
        
        
        int finalVolAddr = (vol << 8) | currentSample; 
        byte ret = z80Ram[finalVolAddr];
        
        return ret;
    }
}
