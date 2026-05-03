package vavi.sound.mdsdrv;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.SourceDataLine;

import vavi.sound.mdsdrv.MdsDrv.WorkArea;
import vavi.util.Debug;
import vavi.util.properties.annotation.Property;
import vavi.util.properties.annotation.PropsEntity;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import static vavi.sound.SoundUtil.volume;


@EnabledIf("localPropertiesExists")
@PropsEntity(url = "file:local.properties")
public class MdsPlayerTest {

    static boolean localPropertiesExists() {
        return Files.exists(Paths.get("local.properties"));
    }

    @Property(name = "vavi.test.volume")
    double volume = 0.2;

    @Property
    String file = "src/test/resources/data/bgm/sand_light.mds";

    static final boolean onIde = System.getProperty("vavi.test", "").equals("ide");
    static final long time = onIde ? 1000 : 15;

    @BeforeEach
    void setup() throws Exception {
        if (localPropertiesExists()) {
            PropsEntity.Util.bind(this);
        }
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

        byte[] mdsData = Files.readAllBytes(mdsFile);
        System.out.println("MDS File size: " + mdsData.length);

        // Parse RIFF MDS data using shared utility
        RiffMdsParser.ParseResult parsed = RiffMdsParser.parse(mdsData);
        if (parsed.isRiff) {
            System.out.println("Parsed RIFF file, seqOffset=" + parsed.seqOffset);
        }

        Memory mdsMem = new MdsDrv.ByteArrayMemory(mdsData, 0);

        int sampleRate = 44100;
        mdsound.chips.Ym2612 fm = new mdsound.chips.Ym2612();
        mdsound.chips.Sn76489 psg = new mdsound.chips.Sn76489();

        int fmClock = 7670454;
        int psgClock = 3579545;
        
        fm.init(fmClock, sampleRate, 0); 
        psg.start(sampleRate, psgClock);
        psg.reset();

        WorkArea workArea = new WorkArea();

        MdsDrv driver = new MdsDrv() {
            @Override
            protected void write_fm_port0(int addr, int data) {
                if (addr >= 0xB4 && addr <= 0xB6) {
                    System.err.printf("FM WR Pan: Reg=%02X Val=%02X%n", addr, data);
                } else if (addr == 0x2B) {
                    System.err.printf("FM WR DAC Mode (Reg 2B): Val=%02X%n", data);
                } else if (addr == 0x42 || addr == 0x46 || addr == 0x4A || addr == 0x4E) {
                     System.err.printf("FM WR Ch2 TL: Reg=%02X Val=%02X%n", addr, data);
                } else if (addr == 0x28) { 
                     System.err.printf("FM WR KeyOp: Val=%02X%n", data);
                }
                
                fm.write(0, addr);
                fm.write(1, data);
            }

            @Override
            protected void write_fm_port1(int addr, int data) {
                System.err.printf("01, %02X, %02X%n", addr, data);
                fm.write(2, addr);
                fm.write(3, data);
            }

            @Override
            protected void writeIo(int port, int data) {
            }

            @Override
            protected Memory getPsgMemory() {
                Memory wrapped = super.getPsgMemory();
                return new Memory() {
                    @Override public void write8(int addr, int data) {
                        if (addr == 0xC00011) {
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

        MdsPcm pcm = new MdsPcm(driver.z80RamBuffer, sampleRate);
        pcm.setFmCallback(datum -> {
             if (datum.port == 0) {
                 fm.write(0, datum.address); // 0x2A
                 fm.write(1, datum.data);
             }
        });

        driver.mds_top(workArea, mdsMem, mdsMem); 

        driver.mds_request(workArea, 1, 0); 

        int updateRate = 60; 
        double samplesPerFrame = (double)sampleRate / updateRate;
        double frameAccumulator = 0;
        
        int[][] fmBuf = new int[2][1];
        int[][] psgBuf = new int[2][1];
        byte[] chunk = new byte[4096];
        int chunkPos = 0;
        
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

        long totalSamples = time * sampleRate;
        for (long i = 0; i < totalSamples; i++) {
            
            // Sequencer Update (approx 60Hz)
            frameAccumulator += 1.0;
            if (frameAccumulator >= samplesPerFrame) {
                frameAccumulator -= samplesPerFrame;
                driver.mds_update(workArea);
            }
            
            // PCM Update (per sample)
            if (workArea.w_pcm_ptr != null) {
                if (!Boolean.parseBoolean(System.getProperty("vavi.sound.mdsdrv.skipPcFm", "false"))) {
                    pcm.update(workArea.w_pcm_ptr);
                }
            }
            
            // Chip Updates (1 sample)
            fmBuf[0][0] = 0; fmBuf[1][0] = 0;
            psgBuf[0][0] = 0; psgBuf[1][0] = 0;
            
            if (!Boolean.parseBoolean(System.getProperty("vavi.sound.mdsdrv.skipFm", "false"))) {
                fm.update(fmBuf, 1);
                fm.updateDacAndTimers(fmBuf, 1);
            }
            if (!Boolean.parseBoolean(System.getProperty("vavi.sound.mdsdrv.skipPsg", "false")))
                psg.update(psgBuf, 1);
            
            // Mix
            int L = fmBuf[0][0] + psgBuf[0][0];
            int R = fmBuf[1][0] + psgBuf[1][0];

            L = Math.clamp(L, -32768, 32767);
            R = Math.clamp(R, -32768, 32767);

            chunk[chunkPos++] = (byte) (L & 0xff);
            chunk[chunkPos++] = (byte) ((L >> 8) & 0xff);
            chunk[chunkPos++] = (byte) (R & 0xff);
            chunk[chunkPos++] = (byte) ((R >> 8) & 0xff);
            
            if (chunkPos >= chunk.length) {
                if (line != null) {
                    line.write(chunk, 0, chunk.length);
                }
                chunkPos = 0;
            }
        }
        
        if (line != null) {
            if (chunkPos > 0) {
                 line.write(chunk, 0, chunkPos);
            }
            line.drain();
            line.close();
        }
        
        System.out.println("Render complete. Time: " + (System.currentTimeMillis() - start) + "ms");
    }
}
