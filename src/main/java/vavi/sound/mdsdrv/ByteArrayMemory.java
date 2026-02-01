/*
 * Copyright (c) 2026 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.sound.mdsdrv;

/**
 * ByteArrayMemory - Memory implementation backed by a byte array.
 * Provides big-endian (Motorola 68000) byte order for read operations.
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 2026-02-01 nsano initial version <br>
 */
public class ByteArrayMemory implements Memory {

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
            // Big Endian (Motorola 68000)
            int b1 = data[pos] & 0xFF;
            int b2 = data[pos + 1] & 0xFF;
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
        int pos = offset + addr;
        if (pos >= 0 && pos < data.length) {
            data[pos] = (byte) val;
        }
    }

    @Override
    public void write16(int addr, int val) {
        write8(addr, val >> 8);
        write8(addr + 1, val & 0xFF);
    }

    @Override
    public void write32(int addr, int val) {
        write16(addr, val >> 16);
        write16(addr + 2, val & 0xFFFF);
    }

    @Override
    public Memory add(int o) {
        return new ByteArrayMemory(data, offset + o);
    }

    /**
     * Get the underlying byte array.
     */
    public byte[] getData() {
        return data;
    }

    /**
     * Get the current offset.
     */
    public int getOffset() {
        return offset;
    }
}
