/*
 * Copyright (c) 2026 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.sound.mdsdrv.instrument;

import java.lang.System.Logger;
import java.util.function.Function;

import mdsound.Instrument;
import vavi.sound.mdsdrv.chips.MdPcm;


/**
 * MdPcmInst.
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 2026-01-15 nsano initial version <br>
 */
public class MdPcmInst extends Instrument.BaseInstrument implements Instrument.PcmEnabledInstrument {

    private static final Logger logger = System.getLogger(MdPcmInst.class.getName());

    private final MdPcm chip = new MdPcm();

    @Override
    public String getName() {
        return "MdPcm";
    }

    @Override
    public String getShortName() {
        return "MdPcm";
    }

    @Override
    public void reset(int chipId) {

    }

    @Override
    public int start(int chipId, int samplingRate, int clock, Object... option) {
        return samplingRate;
    }

    @Override
    public int read(int chipId, int adr) {
        return chip.read(adr);
    }

    @Override
    public int write(int chipId, int port, int adr, int data) {
        chip.write(adr, data);
        return 0;
    }

    private int[] buf = new int[2];

    @Override
    public void update(int chipId, int[][] outputs, int samples) {
        for (int i = 0; i < samples; i++) {
            buf[0] = 0;
            buf[1] = 0;
            chip.update(buf);
            outputs[0][i] = buf[0];
            outputs[1][i] = buf[1];
        }
    }

    @Override
    public void stop(int chipId) {

    }

    @Override
    public void setMask(int chipId, int ch) {

    }

    @Override
    public void resetMask(int chipId, int ch) {

    }

    @Override
    public void writePcm(int chipId, byte[] buf, int offset, int length, Object... extras) {

    }

    /** TODO work should be in PcmChip */
    public void setWorkReader(Function<Integer, Integer> workReader) {
        chip.setWorkReader(workReader);
    }
}
