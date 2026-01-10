package vavi.sound.mdsdrv;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class MdsDrvTest {

    static class MockMemory implements Memory {
        byte[] data;
        int offset;

        MockMemory(byte[] data) {
            this(data, 0);
        }

        MockMemory(byte[] data, int offset) {
            this.data = data;
            this.offset = offset;
        }

        @Override
        public int read8(int addr) {
            return data[offset + addr] & 0xff;
        }

        @Override
        public int read16(int addr) {
            int a = offset + addr;
            return ((data[a] & 0xff) << 8) | (data[a + 1] & 0xff);
        }

        @Override
        public int read32(int addr) {
            int a = offset + addr;
            return ((data[a] & 0xff) << 24) | ((data[a + 1] & 0xff) << 16) |
                    ((data[a + 2] & 0xff) << 8) | (data[a + 3] & 0xff);
        }

        @Override
        public void write8(int addr, int val) {
            data[offset + addr] = (byte) val;
        }

        @Override
        public void write16(int addr, int val) {
            int a = offset + addr;
            data[a] = (byte) (val >> 8);
            data[a + 1] = (byte) val;
        }

        @Override
        public void write32(int addr, int val) {
            int a = offset + addr;
            data[a] = (byte) (val >> 24);
            data[a + 1] = (byte) (val >> 16);
            data[a + 2] = (byte) (val >> 8);
            data[a + 3] = (byte) val;
        }

        @Override
        public Memory add(int off) {
            return new MockMemory(data, offset + off);
        }
    }

    @Test
    void testInit() {
        // Construct valid header
        byte[] header = new byte[64];
        // Magic: 10 01 1f 00
        header[0] = 0x10;
        header[1] = 0x01;
        header[2] = 0x1f;
        header[3] = 0x00;
        // Version: 3 (Must be in high word for swap check)
        header[4] = 0x00;
        header[5] = 0x03;
        header[6] = 0x00;
        header[7] = 0x00;

        MockMemory mem = new MockMemory(header);

        MdsDrv drv = new MdsDrv();
        MdsDrv.WorkArea work = new MdsDrv.WorkArea();

        int res = drv.mds_init(work, mem, null);
        assertEquals(0, res);
        assertNotNull(work.w_sdtop);
    }
}
