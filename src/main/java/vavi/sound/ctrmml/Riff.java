/*
 * Copyright (c) 2026 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.sound.ctrmml;


/**
 * RIFF (Resource Interchange File Format) I/O class.
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 2026-08-02 nsano initial version <br>
 * @see <a href="https://github.com/superctr/ctrmml">ctrmml</a> src/riff.cpp
 */
public class Riff {

    public static final int TYPE_RIFF = 0x52494646;
    public static final int TYPE_LIST = 0x4C495354;
    private static final int ID_NONE = 0x20202020;

    /** Converts a 4 character string to its big endian literal, the {@code FOURCC} macro. */
    public static int fourCc(String code) {
        return ((code.charAt(0) & 0xff) << 24) | ((code.charAt(1) & 0xff) << 16)
                | ((code.charAt(2) & 0xff) << 8) | (code.charAt(3) & 0xff);
    }

    private int position;
    private final int type;
    private final ByteVector data = new ByteVector();

    /** Create new RIFF. */
    public Riff(int chunkType) {
        this.type = chunkType;
        if (type == TYPE_RIFF || type == TYPE_LIST) {
            data.writeBe32(0, ID_NONE);
        }
        rewind();
    }

    /** Create new RIFF with predefined data. */
    public Riff(int chunkType, ByteVector initialData) {
        this.type = chunkType;
        if (type == TYPE_RIFF || type == TYPE_LIST) {
            data.writeBe32(0, ID_NONE);
        }
        data.addAll(initialData);
        rewind();
    }

    /** Create new RIFF with predefined ID. */
    public Riff(int chunkType, int id) {
        this.type = chunkType;
        data.writeBe32(0, id);
        rewind();
    }

    /** Create new RIFF with predefined ID and data. */
    public Riff(int chunkType, int id, ByteVector initialData) {
        this.type = chunkType;
        data.writeBe32(0, id);
        data.addAll(initialData);
        rewind();
    }

    /**
     * Create new RIFF from a chunk.
     *
     * @throws IndexOutOfBoundsException too small.
     */
    public Riff(ByteVector initialData) {
        if (initialData.size() < 8) {
            throw new IndexOutOfBoundsException("Riff: chunk too small");
        }
        type = initialData.readBe32(0);
        int size = initialData.readLe32(4);
        int actualSize = initialData.size() - 8;
        if (Integer.compareUnsigned(size, actualSize) > 0) { // Handle incorrect data size
            size = actualSize;
        }
        data.addAll(initialData, 8, 8 + size);
        rewind();
    }

    /** Rewinds the chunk counter. */
    public void rewind() {
        position = (type == TYPE_RIFF || type == TYPE_LIST) ? 4 : 0;
    }

    /** Returns true if the chunk counter points at the end. */
    public boolean atEnd() {
        // at the end of an even or uneven sized chunk
        if (position >= data.size()) {
            return true;
        }
        // at the end of an even sized list with an alignment byte added but counted into the
        // size (shouldn't normally happen...)
        return (position & 1) != 0 && position >= data.size() - 1;
    }

    /** Get the RIFF type. */
    public int getType() {
        return type;
    }

    /**
     * Set the ID (RIFF or LIST type only).
     *
     * @throws IllegalArgumentException if not applicable to this RIFF type.
     */
    public void setId(int id) {
        if (type == TYPE_RIFF || type == TYPE_LIST) {
            data.writeBe32(0, id);
        } else {
            throw new IllegalArgumentException("Riff.setId()");
        }
    }

    /**
     * Get the ID (RIFF or LIST type only).
     *
     * @throws IllegalArgumentException if not applicable to this RIFF type.
     */
    public int getId() {
        if (type == TYPE_RIFF || type == TYPE_LIST) {
            return data.readBe32(0);
        } else {
            throw new IllegalArgumentException("Riff.getId()");
        }
    }

    /**
     * Add a chunk (RIFF or LIST type only).
     *
     * @throws IllegalArgumentException if not applicable to this RIFF type.
     */
    public void addChunk(Riff newChunk) {
        // If data size is uneven, insert an alignment byte.
        if ((data.size() & 1) != 0) {
            data.add(0);
        }

        if (type == TYPE_RIFF || type == TYPE_LIST) {
            ByteVector newData = newChunk.data;
            data.writeBe32(data.size(), newChunk.getType());
            data.writeLe32(data.size(), newData.size());
            data.addAll(newData);
        } else {
            throw new IllegalArgumentException("Riff.addChunk()");
        }
    }

    /**
     * Add data (non-RIFF or LIST types only).
     *
     * @throws IllegalArgumentException if not applicable to this RIFF type.
     */
    public void addData(ByteVector newData) {
        if (type == TYPE_RIFF || type == TYPE_LIST) {
            throw new IllegalArgumentException("Riff.addData()");
        } else {
            data.addAll(newData);
        }
    }

    /** Get chunk data. */
    public ByteVector getData() {
        return data;
    }

    /** Return the next chunk and increment the position. */
    public ByteVector getChunk() {
        // has to be a list type chunk.
        if (type != TYPE_RIFF && type != TYPE_LIST) {
            throw new IllegalArgumentException("Riff.getChunk()");
        }

        // If data size is uneven, skip the alignment byte.
        if ((position & 1) != 0) {
            position++;
        }

        ByteVector chunkData = new ByteVector();
        chunkData.writeLe32(0, data.readLe32(position)); // could be optimized
        position += 4;
        int size = data.readLe32(position);
        chunkData.writeBe32(4, size);
        position += 4;
        if (position + size > data.size()) { // Handle incorrect data size
            size = data.size() - position;
        }
        chunkData.addAll(data, position, position + size);
        position += size;

        return chunkData;
    }

    /** Convert the RIFF object to a byte vector. */
    public ByteVector toBytes() {
        ByteVector chunkData = new ByteVector();
        chunkData.writeBe32(0, type);
        chunkData.writeLe32(4, data.size());
        chunkData.addAll(data);
        // Add alignment byte if needed.
        if ((data.size() & 1) != 0) {
            chunkData.add(0);
        }
        return chunkData;
    }
}
