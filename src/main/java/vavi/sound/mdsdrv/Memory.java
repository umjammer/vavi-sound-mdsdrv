/*
 * https://github.com/superctr/MDSDRV
 */

package vavi.sound.mdsdrv;


public interface Memory {

    int read8(int addr);

    int read16(int addr);

    int read32(int addr);

    void write8(int addr, int data);

    void write16(int addr, int data);

    void write32(int addr, int data);

    // Pointer arithmetic
    Memory add(int offset);
}
