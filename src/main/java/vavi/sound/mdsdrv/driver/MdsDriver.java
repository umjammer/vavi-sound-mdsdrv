/*
 * Copyright (c) 2026 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.sound.mdsdrv.driver;

import java.io.InputStream;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

import musicDriverInterface.ChipAction;
import musicDriverInterface.ChipDatum;
import musicDriverInterface.IDriver;
import musicDriverInterface.MetaData;
import musicDriverInterface.MmlDatum;
import vavi.sound.mdsdrv.MdsDrv;
import vavi.sound.mdsdrv.MdsPcm;
import vavi.sound.mdsdrv.Memory;
import vavi.sound.mdsdrv.RiffMdsParser;
import vavi.util.compat.Tuple;

import static java.lang.System.getLogger;


/**
 * MdPcmInst.
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 2026-01-13 nsano initial version <br>
 */
public class MdsDriver extends MdsDrv implements IDriver {

    private static final Logger logger = getLogger(MdsDriver.class.getName());

    private WorkArea workArea;
    private MdsPcm mdsPcm;

    // Chip write callbacks from IDriver consumers
    private Consumer<ChipDatum> writeOPNA; // Primary FM (YM2612 treated as OPNA/B/compat)
    private Consumer<ChipDatum> writePSG; // SN76489
    private Consumer<ChipDatum> writePCM; // PCM
    Consumer<Function<Integer, Integer>> workReader;

    @Override
    public void init(List<ChipAction> chipsConsumer, MmlDatum[] srcBuf,
                     Function<String, InputStream> appendFileReaderCallback, Object... additionalOption) {
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

        // Parse RIFF MDS data using shared utility
        RiffMdsParser.ParseResult parsed = RiffMdsParser.parse(data);
        byte[] seqData = parsed.seqData;
        byte[] pcmData = parsed.pcmData;

        Memory memory = new ByteArrayMemory(data, 0);
        this.workArea = new WorkArea();
        this.mdsPcm = new MdsPcm(this.z80RamBuffer, 44100); // Default rate, updated in startRendering?
        
        if (writeOPNA != null) this.mdsPcm.setFmCallback(writeOPNA);

        Memory pcmMemory = pcmData != null ? new ByteArrayMemory(pcmData) : null;
        int result = this.mds_init(workArea, memory, pcmMemory);
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
//logger.log(Level.TRACE, "%02X, %02X".formatted(addr, data));
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
            if (mdsPcm != null) mdsPcm.update(workArea.w_pcm_ptr);

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

    public MetaData getMetaData(byte[] srcBuf) {
        return new MetaData();
    }

    @Override
    protected Memory getPsgMemory() {
        Memory wrapped = super.getPsgMemory();
        return new Memory() {
            @Override public void write8(int addr, int data) {
                if (addr == 0xC00011) {
//logger.log(Level.TRACE, "PSG: %02x".formatted(data));
                    writePSG.accept(new ChipDatum(0, 0, data)); // Port 0 assumed for PSG single port
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
}
