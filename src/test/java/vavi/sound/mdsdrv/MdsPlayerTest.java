package vavi.sound.mdsdrv;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.SourceDataLine;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import vavi.sound.mdsdrv.MdsDrv.WorkArea;
import vavi.util.Debug;
import vavi.util.properties.annotation.Property;
import vavi.util.properties.annotation.PropsEntity;

import static vavi.sound.SoundUtil.volume;


@PropsEntity(url = "file:local.properties")
public class MdsPlayerTest {

    static boolean localPropertiesExists() {
        return Files.exists(Paths.get("local.properties"));
    }

    @Property(name = "vavi.test.volume")
    double volume = 0.2;

    @Property
    String file = "src/test/resources/data/bgm/sand_light.mds";

    static boolean onIde = System.getProperty("vavi.test", "").equals("ide");
    static long time = onIde ? 1000 : 15;

    @BeforeEach
    void setup() throws Exception {
        if (localPropertiesExists()) {
            PropsEntity.Util.bind(this);
        }
        // Allow system property to override file path
        String sysPropFile = System.getProperty("file");
        if (sysPropFile != null && !sysPropFile.isEmpty()) {
            file = sysPropFile;
        }

        System.setProperty("mdplayer.volume", "%4.2f".formatted(volume));
Debug.println("volume: " + volume + ", player.volume: " + System.getProperty("mdplayer.volume") + ", cwd: " + System.getProperty("user.dir") + ", time: " + time);
    }

    @Test
    public void testPlay() throws Exception {
        Path mdsFile = Paths.get(file);
        if (!Files.exists(mdsFile)) {
            System.out.println("MDS file not found: " + mdsFile.toAbsolutePath());
            return;
        }

        System.out.println("Playing: " + mdsFile);

        // Load MDS file into Memory
        byte[] mdsData = Files.readAllBytes(mdsFile);
        System.out.println("MDS File size: " + mdsData.length);

        // Check for RIFF header and extract Sequence Data
        byte[] seqData = mdsData;
        int seqOffset = 0;
        if (mdsData.length >= 12 && mdsData[0] == 'R' && mdsData[1] == 'I' && mdsData[2] == 'F' && mdsData[3] == 'F' &&
                mdsData[8] == 'M' && mdsData[9] == 'D' && mdsData[10] == 'S' && mdsData[11] == '0') {

            // For RIFF MDS files, we pass the entire file and let driver parse from seq offset
            // The seq chunk contains track data, while LIST/glob chunks contain instruments
            int p = 12;
            while (p < mdsData.length - 8) {
                // Read Chunk ID
                char c0 = (char)mdsData[p], c1 = (char)mdsData[p+1], c2 = (char)mdsData[p+2], c3 = (char)mdsData[p+3];
                String chunkId = "" + c0 + c1 + c2 + c3;
                int size = (mdsData[p + 4] & 0xFF) | ((mdsData[p + 5] & 0xFF) << 8) | ((mdsData[p + 6] & 0xFF) << 16)
                        | ((mdsData[p + 7] & 0xFF) << 24);
                
                System.out.println("RIFF chunk '" + chunkId + "' size=" + size + " at offset " + p);
                
                if (mdsData[p] == 's' && mdsData[p + 1] == 'e' && mdsData[p + 2] == 'q' && mdsData[p + 3] == ' ') {
                    // Found seq chunk - pass entire file from seq data start
                    // This includes seq data AND following LIST/glob chunks
                    seqOffset = p + 8;
                    int remainingSize = mdsData.length - seqOffset;
                    seqData = new byte[remainingSize];
                    System.arraycopy(mdsData, seqOffset, seqData, 0, remainingSize);
                    System.out.println("Extracted from seq offset: " + remainingSize + " bytes (seq + LIST chunks)");
                }
                // Pad byte if size is odd (RIFF standard)
                if ((size & 1) != 0)
                    size++;
                p += 8 + size;
            }
            
        }

        // Create root memory
        // Pass full RIFF file to driver (MdsDrv will parse chunks)
        Memory mdsMem = new ByteArrayMemory(mdsData, 0);

        // Initialize Chips
        int sampleRate = 44100;
        mdsound.chips.Ym2612 fm = new mdsound.chips.Ym2612();
        mdsound.chips.Sn76489 psg = new mdsound.chips.Sn76489();
        
        // Clocks from MdsPlugin / standard
        int fmClock = 7670454;
        int psgClock = 3579545;
        
        fm.init(fmClock, sampleRate, 0); // interpolation 0
        psg.start(sampleRate, psgClock);
        psg.reset();

        // PCM HLE State
        class PcmChannel {
            boolean active = false;
            int address;
            int length;
            int pitch;
            int volume;  // Z80 volume value (15-31 range from mds_z80_get_vol)
            double pos;
            double step;
        }
        final PcmChannel[] pcmChannels = {new PcmChannel(), new PcmChannel(), new PcmChannel()};
        final byte[] z80RamData = new byte[0x2000];
        
        WorkArea workArea = new WorkArea();

        // Driver with overriden IO
        MdsDrv driver = new MdsDrv() {
            @Override
            protected void write_fm_port0(int addr, int data) {
                // Log Panning (B4-B6)
                if (addr >= 0xB4 && addr <= 0xB6) {
                    System.err.printf("FM WR Pan: Reg=%02X Val=%02X%n", addr, data);
                }
                // Log DAC Mode (2B)
                else if (addr == 0x2B) {
                    System.err.printf("FM WR DAC Mode: Val=%02X%n", data);
                }
                // Log Ch2 TL (42, 46, 4A, 4E) - Bass
                else if (addr == 0x42 || addr == 0x46 || addr == 0x4A || addr == 0x4E) {
                     System.err.printf("FM WR Ch2 TL: Reg=%02X Val=%02X%n", addr, data);
                }
                // Log Ch5 TL (41+4, 45+4... -> 45, 49, 4D, 51 -> Wait Ch 5 is FM6)
                // FM6 is Ch 2 on Port 1?
                // MdsDrv writes to port 1 for Ch 3,4,5.
                // Ch 5 (User Ch 6) is FM6.
                // Registers 0x42, 0x46... on Port 1.
                
                else if (addr == 0x28) { // Key On
                     int ch = data & 0x07;
                     // Log for Ch 0, 1, 2, 5 (last one needs port check)
                     System.err.printf("FM WR KeyOp: Val=%02X%n", data);
                }
                
                fm.write(0, addr);
                fm.write(1, data);
            }

            @Override
            protected void write_fm_port1(int addr, int data) {
                // Log ALL writes in VGM format for comparison
                System.err.printf("01, %02X, %02X%n", addr, data);
                fm.write(2, addr);
                fm.write(3, data);
            }

            @Override
            protected void writeIo(int port, int data) {
                // Debug: System.out.printf("IO: %02x %02x%n", port, data);
            }

            @Override
            protected Memory getZ80Ram() {
                return new Memory() {
                    @Override
                    public void write8(int addr, int val) {
                        int offset = addr - MdDef.z80_ram;
                        if (offset >= 0 && offset < z80RamData.length) {
                             z80RamData[offset] = (byte)val;

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
                                 // Pitch 92 (0x5C) -> 9200Hz?
                                 // step = TargetFreq / SampleRate
                                 if (pitch == 0) pitch = 4;
                                 double pcmFreq = pitch * 100.0;
                                 pcmChannels[0].step = pcmFreq / 44100.0;
                             }
                        }
                    }
                    @Override public int read8(int addr) { 
                        int offset = addr - MdDef.z80_ram;
                        if (offset >= 0 && offset < z80RamData.length) return z80RamData[offset] & 0xFF;
                        return 0;
                    }
                    @Override public void write16(int addr, int val) { write8(addr, val >> 8); write8(addr+1, val & 0xFF); }
                    @Override public void write32(int addr, int val) { write16(addr, val >> 16); write16(addr+2, val & 0xFFFF); }
                    @Override public int read16(int addr) { return (read8(addr) << 8) | read8(addr+1); }
                    @Override public int read32(int addr) { return (read16(addr) << 16) | read16(addr+2); }
                    @Override public Memory add(int o) { return null; }
                };
            }

            @Override
            protected Memory getPsgMemory() {
                final Memory wrapped = super.getPsgMemory();
                return new Memory() {
                    @Override public void write8(int addr, int data) {
                        if (addr == 0xC00011) {
                            // System.out.printf("PSG: %02x%n", data);
                            psg.write(data);
                        }
                        else wrapped.write8(addr, data);
                    }
                    @Override public int read8(int addr) { return wrapped.read8(addr); }
                    @Override public int read16(int addr) { return wrapped.read16(addr); }
                    @Override public int read32(int addr) { return wrapped.read32(addr); }
                    @Override public void write16(int addr, int data) { wrapped.write16(addr, data); }
                    @Override public void write32(int addr, int data) { wrapped.write32(addr, data); }
                    @Override public Memory add(int o) { return wrapped.add(o); }
                };
            }
        };

        driver.mds_top(workArea, mdsMem, null); // Initialize driver
        
        driver.mds_request(workArea, 1, 0); // Request song 1

        // Audio Output setup
        int updateRate = 60; // 60Hz update (VBL)
        int samplesPerStep = sampleRate / updateRate; 
        int[][] fmBuf = new int[2][samplesPerStep];
        int[][] psgBuf = new int[2][samplesPerStep];
        byte[] mixBuf = new byte[samplesPerStep * 4]; // 16bit stereo
        
        AudioFormat format = new AudioFormat(sampleRate, 16, 2, true, false);
        SourceDataLine line = null;
        try {
            line = AudioSystem.getSourceDataLine(format);
            line.open(format);
            volume(line, volume);
            line.start();
            System.out.println("Audio line started.");
        } catch (LineUnavailableException | IllegalArgumentException e) {
            System.out.println("Audio line unavailable: " + e.getMessage());
        }

        System.out.println("Rendering audio...");
        long start = System.currentTimeMillis();
        
            // Render loop (e.g. 10 seconds)
            for (int frame = 0; frame < updateRate * time; frame++) {
                driver.mds_update(workArea);
                
                // Clear buffers
                mdsound.chips.Ym2612.clearBuffer(fmBuf, samplesPerStep);
                for(int i=0; i<samplesPerStep; i++) {
                     psgBuf[0][i] = 0;
                     psgBuf[1][i] = 0;
                }
                
                // Update Chips
                if (!Boolean.parseBoolean(System.getProperty("vavi.sound.mdsdrv.skipFm", "false")))
                    fm.update(fmBuf, samplesPerStep);
                if (!Boolean.parseBoolean(System.getProperty("vavi.sound.mdsdrv.skipPsg", "false")))
                    psg.update(psgBuf, samplesPerStep);
                
                for (int i = 0; i < samplesPerStep; i++) {
                    int pcmL = 0, pcmR = 0;
                    
                    for (PcmChannel pc : pcmChannels) {
                        try {
                            if (pc.active && workArea.w_pcm_ptr != null) {
                                int posInt = (int)pc.pos;

                                // Basic bounds check if possible, or rely on catch
                                // ByteArrayMemory throws IndexOutOfBoundsException
                                int sample = workArea.w_pcm_ptr.read8(pc.address + posInt);

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
                                val = (int)(val * volScale * 64);  // Apply volume and gain

                                pcmL += val;
                                pcmR += val;

                                pc.pos += pc.step;
                                // Simple length check?
                                // If we read 0x00 or 0x80 (silence), maybe fade out?
                                // For now, let it run until exception or manual stop?
                                // Usually PCM has a length count.
                            }
                        } catch (Exception e) {
                            pc.active = false; // Stop if error (End of buffer)
                        }
                    }
                    
                    int L = fmBuf[0][i] + psgBuf[0][i] + pcmL;
                    int R = fmBuf[1][i] + psgBuf[1][i] + pcmR;
                    
                    // Clamp 16-bit
                    L = Math.max(-32768, Math.min(32767, L));
                    R = Math.max(-32768, Math.min(32767, R));
                    
                    mixBuf[i*4+0] = (byte)(L & 0xff);
                    mixBuf[i*4+1] = (byte)((L >> 8) & 0xff);
                    mixBuf[i*4+2] = (byte)(R & 0xff);
                    mixBuf[i*4+3] = (byte)((R >> 8) & 0xff);
                }
                
                if (line != null) {
                    line.write(mixBuf, 0, mixBuf.length);
                }
            }
        
        if (line != null) {
            line.drain();
            line.close();
        }
        
        System.out.println("Render complete. Time: " + (System.currentTimeMillis() - start) + "ms");
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
        public Memory add(int off) {
            return new ByteArrayMemory(data, offset + off);
        }
    }
}
