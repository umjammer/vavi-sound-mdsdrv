/*
 * Copyright (c) 2026 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.sound.ctrmml;

import java.util.Arrays;


/**
 * A growable byte buffer, the equivalent of {@code std::vector<uint8_t>}.
 * <p>
 * Elements are read back as unsigned values (0-255). The {@code writeXx}/{@code readXx}
 * methods correspond to the helpers of ctrmml's {@code src/util.h}; the writers grow
 * the buffer when needed, exactly like the originals.
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 2026-08-02 nsano initial version <br>
 */
public final class ByteVector implements Cloneable {

    private byte[] a;
    private int size;

    public ByteVector() {
        this.a = new byte[16];
    }

    /** Creates a vector of {@code size} zeroed bytes. */
    public ByteVector(int size) {
        this.a = new byte[Math.max(size, 1)];
        this.size = size;
    }

    public ByteVector(byte[] data) {
        this.a = data.clone();
        this.size = data.length;
    }

    public ByteVector(byte[] data, int from, int to) {
        this.a = Arrays.copyOfRange(data, from, to);
        this.size = to - from;
    }

    public ByteVector(ByteVector o) {
        this.a = Arrays.copyOf(o.a, Math.max(o.size, 1));
        this.size = o.size;
    }

    /** Copies the range {@code [from, to)}. */
    public ByteVector(ByteVector o, int from, int to) {
        this.a = Arrays.copyOfRange(o.a, from, to);
        this.size = to - from;
    }

    public int size() {
        return size;
    }

    public boolean isEmpty() {
        return size == 0;
    }

    /** @return the unsigned byte value at {@code i} */
    public int get(int i) {
        if (i < 0 || i >= size) {
            throw new IndexOutOfBoundsException(i + " / " + size);
        }
        return a[i] & 0xff;
    }

    /** @return the unsigned byte value at {@code i}, bounds checked like {@code std::vector::at()} */
    public int at(int i) {
        return get(i);
    }

    public void set(int i, int v) {
        if (i < 0 || i >= size) {
            throw new IndexOutOfBoundsException(i + " / " + size);
        }
        a[i] = (byte) v;
    }

    /** {@code push_back()} */
    public void add(int v) {
        ensure(size + 1);
        a[size++] = (byte) v;
    }

    /** {@code back()} */
    public int back() {
        return get(size - 1);
    }

    /** Sets the last element. */
    public void setBack(int v) {
        set(size - 1, v);
    }

    public void addAll(ByteVector o) {
        ensure(size + o.size);
        System.arraycopy(o.a, 0, a, size, o.size);
        size += o.size;
    }

    public void addAll(byte[] o) {
        ensure(size + o.length);
        System.arraycopy(o, 0, a, size, o.length);
        size += o.length;
    }

    /** Appends the range {@code [from, to)} of {@code o}. */
    public void addAll(ByteVector o, int from, int to) {
        int n = to - from;
        ensure(size + n);
        System.arraycopy(o.a, from, a, size, n);
        size += n;
    }

    /** {@code insert(begin() + pos, ...)} */
    public void insert(int pos, ByteVector o) {
        ensure(size + o.size);
        System.arraycopy(a, pos, a, pos + o.size, size - pos);
        System.arraycopy(o.a, 0, a, pos, o.size);
        size += o.size;
    }

    /** {@code insert(begin(), count, value)} */
    public void insertFront(int count, int value) {
        ensure(size + count);
        System.arraycopy(a, 0, a, count, size);
        Arrays.fill(a, 0, count, (byte) value);
        size += count;
    }

    /** {@code resize()}, new elements are zeroed. */
    private void resize(int newSize) {
        if (newSize > size) {
            ensure(newSize);
            Arrays.fill(a, size, newSize, (byte) 0);
        }
        size = newSize;
    }

    public byte[] toByteArray() {
        return Arrays.copyOf(a, size);
    }

    private void ensure(int capacity) {
        if (capacity > a.length) {
            a = Arrays.copyOf(a, Math.max(capacity, a.length * 2));
        }
    }

    // util.h

    /** Writes a 32-bit little endian integer, growing the buffer if needed. */
    public void writeLe32(int pos, int data) {
        if (size < pos + 4) {
            resize(pos + 4);
        }
        a[pos] = (byte) data;
        a[pos + 1] = (byte) (data >> 8);
        a[pos + 2] = (byte) (data >> 16);
        a[pos + 3] = (byte) (data >> 24);
    }

    /** Writes a 32-bit big endian integer, growing the buffer if needed. */
    public void writeBe32(int pos, int data) {
        if (size < pos + 4) {
            resize(pos + 4);
        }
        a[pos + 3] = (byte) data;
        a[pos + 2] = (byte) (data >> 8);
        a[pos + 1] = (byte) (data >> 16);
        a[pos] = (byte) (data >> 24);
    }

    /** Writes a 16-bit big endian integer, growing the buffer if needed. */
    public void writeBe16(int pos, int data) {
        if (size < pos + 2) {
            resize(pos + 2);
        }
        a[pos + 1] = (byte) data;
        a[pos] = (byte) (data >> 8);
    }

    /** Reads a 32-bit little endian integer. */
    public int readLe32(int pos) {
        return get(pos) | (get(pos + 1) << 8) | (get(pos + 2) << 16) | (get(pos + 3) << 24);
    }

    /** Reads a 32-bit big endian integer. */
    public int readBe32(int pos) {
        return get(pos + 3) | (get(pos + 2) << 8) | (get(pos + 1) << 16) | (get(pos) << 24);
    }

    @Override
    public ByteVector clone() {
        return new ByteVector(this);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ByteVector that) || that.size != size) {
            return false;
        }
        return Arrays.equals(a, 0, size, that.a, 0, size);
    }

    @Override
    public int hashCode() {
        int h = 1;
        for (int i = 0; i < size; i++) {
            h = h * 31 + a[i];
        }
        return h;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder(size * 3);
        for (int i = 0; i < size; i++) {
            if (i > 0) {
                sb.append(' ');
            }
            sb.append("%02x".formatted(a[i] & 0xff));
        }
        return sb.toString();
    }
}
