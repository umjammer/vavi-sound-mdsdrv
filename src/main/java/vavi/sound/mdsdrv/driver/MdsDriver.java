package vavi.sound.mdsdrv.driver;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

import dotnet4j.io.Stream;
import dotnet4j.util.compat.Tuple;
import musicDriverInterface.ChipAction;
import musicDriverInterface.ChipDatum;
import musicDriverInterface.GD3Tag;
import musicDriverInterface.IDriver;
import musicDriverInterface.MmlDatum;
import vavi.sound.mdsdrv.MdsDrv;
import vavi.sound.mdsdrv.Memory;

import static java.lang.System.getLogger;

public class MdsDriver extends MdsDrv implements IDriver {

    private static final Logger logger = getLogger(MdsDriver.class.getName());

    private WorkArea workArea;
    private Memory memory;

    // Chip write callbacks from IDriver consumers
    private Consumer<ChipDatum> writeOPNA; // Primary FM (YM2612 treated as OPNA/B/compat)
    private Consumer<ChipDatum> writePSG; // SN76489

    @Override
    public void init(List<ChipAction> chipsConsumer, MmlDatum[] srcBuf,
            Function<String, Stream> appendFileReaderCallback, Object... additionalOption) {
        if (chipsConsumer != null && !chipsConsumer.isEmpty()) {
            this.writeOPNA = chipsConsumer.get(0)::writeRegister;
            if (chipsConsumer.size() > 1) {
                this.writePSG = chipsConsumer.get(1)::writeRegister;
logger.log(Level.DEBUG, "MdsDriver init: writeOPNA and writePSG set: " + this.writePSG);
            }
        }

        // Convert MmlDatum[] to byte array for Memory
        byte[] data = new byte[srcBuf.length];
        for (int i = 0; i < srcBuf.length; i++) {
            data[i] = (byte) srcBuf[i].dat;
        }

        // Check for RIFF header and extract Sequence Data
        byte[] seqData = data;
        if (data.length >= 12 && data[0] == 'R' && data[1] == 'I' && data[2] == 'F' && data[3] == 'F' &&
                data[8] == 'M' && data[9] == 'D' && data[10] == 'S' && data[11] == '0') {

            int p = 12;
            while (p < data.length - 8) {
                // Read Chunk ID
                if (data[p] == 's' && data[p + 1] == 'e' && data[p + 2] == 'q' && data[p + 3] == ' ') {
                    // Found seq chunk
                    int size = (data[p + 4] & 0xFF) | ((data[p + 5] & 0xFF) << 8) | ((data[p + 6] & 0xFF) << 16)
                            | ((data[p + 7] & 0xFF) << 24);
                    if (p + 8 + size <= data.length) {
                        seqData = new byte[size];
                        System.arraycopy(data, p + 8, seqData, 0, size);
                        break; // Found it
                    }
                }
                // Skip chunk
                int size = (data[p + 4] & 0xFF) | ((data[p + 5] & 0xFF) << 8) | ((data[p + 6] & 0xFF) << 16)
                        | ((data[p + 7] & 0xFF) << 24);
                // Pad byte if size is odd (RIFF standard)
                if ((size & 1) != 0)
                    size++;
                p += 8 + size;
            }
        }

        this.memory = new ByteArrayMemory(seqData);
        this.workArea = new WorkArea();

        int result = this.mds_init(workArea, memory);
        if (result != 0) {
            logger.log(Level.ERROR, "MdsDrv init failed: " + result);
        } else {
            logger.log(Level.DEBUG, "MdsDrv init success");
        }

        // Start Request (Request 1 = Play) - Default behavior
        // The player typically calls startMusic, but mds_init might need to be ready.
    }

    @Override
    public void startMusic(int musicNumber) {
        if (workArea != null) {
            logger.log(Level.DEBUG, "startMusic: " + musicNumber);
            // Request 0 is BGM.
            // mds_request handles setting rf_active (bit 15) and rf_stop (bit 14).
            // Command is passed in d0 (musicNumber).
            mds_request(workArea, musicNumber + 1, 0);
        }
    }

    @Override
    public void stopMusic() {
        if (workArea != null) {
             // Request 0 is BGM.
             // Command 0 with rf_stop (set by mds_request) should stop the track.
             mds_request(workArea, 0, 0);
        }
    }

    // Override MdsDrv write methods to output to ChipAction
    @Override
    protected void write_fm_port0(int addr, int data) {
        if (writeOPNA != null) {
//logger.log(Level.INFO, "%02X, %02X".formatted(addr, data));
            writeOPNA.accept(new ChipDatum(0, addr, data));
        }
    }

    @Override
    protected void write_fm_port1(int addr, int data) {
        if (writeOPNA != null) {
            writeOPNA.accept(new ChipDatum(1, addr, data));
        }
    }

    // We also need to capture Z80 writes if possible?
    @Override
    protected void writeIo(int port, int data) {
        // Implement if MDSound allows IO hooking.
        // For now, ignore.
    }

    @Override
    protected Memory getPsgMemory() {
        return new PsgMemory(super.getPsgMemory());
    }

    private class PsgMemory implements Memory {
        private final Memory wrapped;
        
        public PsgMemory(Memory wrapped) {
            this.wrapped = wrapped;
        }

        @Override
        public void write8(int addr, int data) {
            if (addr == vavi.sound.mdsdrv.MdDef.sound_psg) {
                if (writePSG != null) {
//logger.log(Level.DEBUG, "write_psg: %02x, %02x".formatted(addr, data));
                    writePSG.accept(new ChipDatum(0, 0, data)); // Port 0 assumed for PSG single port
                }
            } else {
                wrapped.write8(addr, data);
            }
        }

        // Delegation methods
        @Override public int read8(int addr) { return wrapped.read8(addr); }
        @Override public int read16(int addr) { return wrapped.read16(addr); }
        @Override public int read32(int addr) { return wrapped.read32(addr); }
        @Override public void write16(int addr, int data) { wrapped.write16(addr, data); }
        @Override public void write32(int addr, int data) { wrapped.write32(addr, data); }
        @Override public Memory add(int o) { return wrapped.add(o); }
    }

    private double samplesPerFrame = 735.0; // Default 44100 / 60
    private double sampleCounter = 0.0;

    @Override
    @SafeVarargs
    public final void startRendering(int renderingFreq, Tuple<String, Integer>... chipMasterClocks) {
        if (renderingFreq <= 0) renderingFreq = 44100;
        this.samplesPerFrame = (double) renderingFreq / 60.0; // Target 60Hz update rate
        this.sampleCounter = 0.0;
        logger.log(Level.DEBUG, "startRendering: freq=%d samplesPerFrame=%.2f".formatted(renderingFreq, samplesPerFrame));
    }

    @Override
    public void render() {
        if (workArea != null) {
            sampleCounter += 1.0;
            if (sampleCounter >= samplesPerFrame) {
                sampleCounter -= samplesPerFrame;
                mds_update(workArea);
            }
        }
    }

    @Override
    public void stopRendering() {
    }

    @Override
    public void fadeOut() {
        mds_command(workArea, 0x0A, 0, 0);
    } // 0x0A = Fade

    @Override
    public Object getWork() {
        return workArea;
    }

    @Override
    public void shotEffect() {
    }

    @Override
    public int getStatus() {
        return 0;
    }

    @Override
    public MmlDatum[] getDATA() {
        return null;
    }

    @Override
    public List<Tuple<String, String>> getTags() {
        return new ArrayList<>();
    }

    @Override
    public byte[] getPCMFromSrcBuf() {
        return null;
    }

    @Override
    public Tuple<String, short[]>[] getPCMTable() {
        return null;
    }

    @Override
    public ChipDatum[] getPCMSendData() {
        return null;
    }

    public void setMuteFlag(int chip, int ch, int page, boolean flg) {
    }

    public void setAllMuteFlag(boolean flg) {
    }

    public void setDriverSwitch(Object... param) {
    }

    public void writeRegister(ChipDatum reg) {
    }

    public int getNowLoopCounter() {
        return 0;
    }

    public int setLoopCount(int loopCounter) {
        return 0;
    }

    public GD3Tag getGD3TagInfo(byte[] srcBuf) {
        return new GD3Tag();
    }

    // Helper Memory Implementation
    private static class ByteArrayMemory implements Memory {
        private final byte[] data;
        private final int offset;

        public ByteArrayMemory(byte[] data) {
            this(data, 0);
        }

        public ByteArrayMemory(byte[] data, int offset) {
            this.data = data;
            this.offset = offset;
        }

        @Override
        public int read8(int addr) {
            int pos = offset + addr;
            if (pos >= 0 && pos < data.length)
                return data[pos] & 0xFF;
            return 0;
        }

        @Override
        public int read16(int addr) {
            int pos = offset + addr;
            if (pos >= 0 && pos < data.length - 1) {
                return ((data[pos] & 0xFF) << 8) | (data[pos + 1] & 0xFF);
            }
            return 0;
        }

        @Override
        public int read32(int addr) {
            return (read16(addr) << 16) | read16(addr + 2);
        }

        @Override
        public void write8(int addr, int data) {
        }

        @Override
        public void write16(int addr, int data) {
        }

        @Override
        public void write32(int addr, int data) {
        }

        @Override
        public Memory add(int o) {
            return new ByteArrayMemory(data, offset + o);
        }
    }
}
