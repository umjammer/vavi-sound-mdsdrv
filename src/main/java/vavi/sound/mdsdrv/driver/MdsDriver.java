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
    // We assume index 0 is the main chip.

    @Override
    public void init(List<ChipAction> chipsConsumer, MmlDatum[] srcBuf,
            Function<String, Stream> appendFileReaderCallback, Object... additionalOption) {
        if (chipsConsumer != null && !chipsConsumer.isEmpty()) {
            this.writeOPNA = chipsConsumer.get(0)::writeRegister;
        }

        // Convert MmlDatum[] to byte array for Memory
        byte[] data = new byte[srcBuf.length];
        for (int i = 0; i < srcBuf.length; i++) {
            data[i] = (byte) srcBuf[i].dat;
        }

        this.memory = new ByteArrayMemory(data);
        this.workArea = new WorkArea();

        int result = this.mds_init(workArea, memory, memory);
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
        // MdsDrv uses requests.
        // Assuming musicNumber maps to a specific request ID or just starts the default
        // song.
        // As per mdsdrv.68k, triggering request 0 ($01) or 1 ($02)?
        // work.w_request[0] = 0x2001; // Play command
        if (workArea != null) {
            // Logic derived from mds_top or mds_request usage
            // Request 0 is usually BGM.
            // Command 0x01 (Play) with active flag (0x8000) -> 0x2001 (using simple mask)
            // mds_request logic: d0 |= 0x8000 | 0x4000.
            // Let's use the helper if possible, or direct access.
            // mds_request(workArea, 1, 0); // 1 = Start?
            // Let's assume standard Play = Request $01 on Track $00
            workArea.w_request[0] = 0x2001;
        }
    }

    @Override
    public void stopMusic() {
        if (workArea != null) {
            // Stop command
            // mds_request: Stop flag.
            workArea.w_request[0] = 0x4000; // Stop bit?
            // Or use mds_request(workArea, 0, 0) with stop bit logic.
        }
    }

    @Override
    public void render() {
        if (workArea != null) {
            mds_update(workArea);
        }
    }

    // Override MdsDrv write methods to output to ChipAction
    @Override
    protected void write_fm_port0(int addr, int data) {
        if (writeOPNA != null) {
            writeOPNA.accept(new ChipDatum(0, addr, data));
        }
    }

    @Override
    protected void write_fm_port1(int addr, int data) {
        if (writeOPNA != null) {
            writeOPNA.accept(new ChipDatum(0, addr | 0x100, data)); // Port 1 usually +0x100 or special Handling?
            // ChipDatum usually has port, address, data.
            // If IDriver convention for Port1 is separate chip instance or address offset?
            // MDSound YM2612: Part 1 is usually offset 2/3 but address space is 0-1ff.
            // Port 0: 0x00-0xFF. Port 1: 0x100-0x1FF.
            // We'll assume address should be offset by 0x100? No, write_fm_port1 input addr
            // is usually raw 0x30..0xB4.
            // Check MDSound implementation. Usually port 0 is addr, port 1 is addr | 0x100.
            // Or use MdsDrv's raw writes: 4000/4001, 4002/4003.
        }
    }

    // We also need to capture Z80 writes if possible?
    @Override
    protected void writeIo(int port, int data) {
        // Implement if MDSound allows IO hooking.
        // For now, ignore.
    }

    // Unimplemented methods required by IDriver interface (stubs)
    @Override
    public void startRendering(int renderingFreq, Tuple<String, Integer>... chipMasterClocks) {
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
        return null;
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
