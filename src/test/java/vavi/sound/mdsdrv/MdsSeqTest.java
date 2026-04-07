/*
 * https://github.com/superctr/MDSDRV
 */

package vavi.sound.mdsdrv;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import vavi.sound.mdsdrv.MdsDrv.WorkArea;
import static org.junit.jupiter.api.Assertions.assertTrue;


/**
 * Transcribed from mdsseq.68k
 */
public class MdsSeqTest {

    //
    // mdsseq.inc constants
    //
    static final int l1 = 0x5F;
    static final int l2 = 0x2F;
    static final int l2d = 0x47;
    static final int l4 = 0x17;
    static final int l4d = 0x23;
    static final int l8 = 0x0B;
    static final int l8d = 0x11;
    static final int l16 = 0x05;
    static final int l16d = 0x08;
    static final int l32 = 0x02;

    static final int rest = 0x80;
    static final int tie = 0x81;
    
    // Base note C1 = 0x82
    static final int cn1 = 0x82, cs1 = 0x83, dn1 = 0x84, ds1 = 0x85, en1 = 0x86, fn1 = 0x87, fs1 = 0x88, gn1 = 0x89, gs1 = 0x8A, an1 = 0x8B, as1 = 0x8C, bn1 = 0x8D;
    static final int cn2 = 0x8E, cs2 = 0x8F, dn2 = 0x90, ds2 = 0x91, en2 = 0x92, fn2 = 0x93, fs2 = 0x94, gn2 = 0x95, gs2 = 0x96, an2 = 0x97, as2 = 0x98, bn2 = 0x99;
    static final int cn3 = 0x9A, cs3 = 0x9B, dn3 = 0x9C, ds3 = 0x9D, en3 = 0x9E, fn3 = 0x9F, fs3 = 0xA0, gn3 = 0xA1, gs3 = 0xA2, an3 = 0xA3, as3 = 0xA4, bn3 = 0xA5;
    static final int cn4 = 0xA6, cs4 = 0xA7, dn4 = 0xA8, ds4 = 0xA9, en4 = 0xAA, fn4 = 0xAB, fs4 = 0xAC, gn4 = 0xAD, gs4 = 0xAE, an4 = 0xAF, as4 = 0xB0, bn4 = 0xB1;
    static final int cn5 = 0xB2, cs5 = 0xB3, dn5 = 0xB4, ds5 = 0xB5, en5 = 0xB6, fn5 = 0xB7, fs5 = 0xB8, gn5 = 0xB9, gs5 = 0xBA, an5 = 0xBB, as5 = 0xBC, bn5 = 0xBD;
    static final int cn6 = 0xBE, cs6 = 0xBF, dn6 = 0xC0, ds6 = 0xC1, en6 = 0xC2, fn6 = 0xC3, fs6 = 0xC4, gn6 = 0xC5, gs6 = 0xC6, an6 = 0xC7, as6 = 0xC8, bn6 = 0xC9;
    static final int cn7 = 0xCA, cs7 = 0xCB, dn7 = 0xCC, ds7 = 0xCD, en7 = 0xCE, fn7 = 0xCF, fs7 = 0xD0, gn7 = 0xD1, gs7 = 0xD2, an7 = 0xD3, as7 = 0xD4, bn7 = 0xD5;
    static final int cn8 = 0xD6, cs8 = 0xD7, dn8 = 0xD8, ds8 = 0xD9, en8 = 0xDA, fn8 = 0xDB, fs8 = 0xDC, gn8 = 0xDD, gs8 = 0xDE, an8 = 0xDF;

    static final int slr = 0xE0;
    static final int ins = 0xE1;
    static final int vol = 0xE2;
    static final int volm = 0xE3;
    static final int trs = 0xE4;
    static final int trsm = 0xE5;
    static final int dtn = 0xE6;
    static final int pta = 0xE7;
    static final int peg = 0xE8;
    static final int pan = 0xE9;
    static final int lfo = 0xEA;
    static final int mtab = 0xEB;
    static final int flg = 0xEC;
    static final int fmcreg = 0xED;
    static final int fmtl = 0xEE;
    static final int fmtlm = 0xEF;
    static final int pcm = 0xF0;

    static final int jump = 0xF5;
    static final int fmreg = 0xF6;
    static final int dmfinish = 0xF7;
    static final int comm = 0xF8;
    static final int tempo = 0xF9;
    static final int lp = 0xFA;
    static final int lpf = 0xFB;
    static final int lpb = 0xFC;
    static final int lpbl = 0xFD;
    static final int pat = 0xFE;
    static final int finish = 0xFF;

    static final int cf_drum_mode = 0x0200; 

    @Test
    public void testSeq() {
        TestAssembler asm = new TestAssembler();
        
        // Transcription of mdsseq.68k
        
        // sddata:
        asm.label("sddata");
        asm.db(0x10, 0x01, 0x1f, 0x00); 
        asm.dw(0x0003); 
        
        // sdcnt:
        // Original assembly defines SOUND_01 to SOUND_07, then SOUND_MAX.
        // It implies 7 entries.
        asm.label("sdcnt");
        asm.dw(7); // 7 sounds defined

        // sdtop:
        // MdsDrv (Standard Mode) expects:
        // Offset 0-3: (4 bytes) header/pcm etc.
        // Offset 4: Ptr 0 (16-bit)
        // Offset 6: Ptr 1 (16-bit) - BGM 1
        // ...
        asm.label("sdtop");
        asm.dl_ref_diff("pcm_tab", "sdtop"); // 4 bytes (Offset 0-3)

        // Sound Table
        // Offset 4: BGM 0 (Dummy)
        asm.dw(0); 
        // Offset 6: BGM 1
        asm.dw_ref_diff("bgm01", "sdtop"); // BGM 1
        asm.dw_ref_diff("bgm02", "sdtop"); // BGM 2
        asm.dw_ref_diff("bgm03", "sdtop"); // ...
        asm.dw_ref_diff("bgm04", "sdtop");
        asm.dw_ref_diff("bgm05", "sdtop");
        asm.dw_ref_diff("bgm06", "sdtop");
        asm.dw_ref_diff("bgm07", "sdtop");
        
        // SD_FM_00
        asm.label("SD_FM_00");
        asm.db(0x00, 0x42, 0x26, 0x01); 
        asm.db(0x1f, 0x1f, 0x1f, 0x1f); 
        asm.db(0x11, 0x0f, 0x0f, 0x11); 
        asm.db(0x00, 0x00, 0x00, 0x00); 
        asm.db(0x60, 0x25, 0xff, 0x1b); 
        asm.db(0x00, 0x00, 0x00, 0x00); 
        asm.db(0x0f, 0x32, 0x0b, 0x00); 
        asm.db(0x3a, 0x30);             
        
        // SD_PSG_00
        asm.label("SD_PSG_00");
        asm.db(0x14, 0x12, 0x50, 0x61, 0x62, 0x43);
        asm.db(0x01); // sustain
        asm.db(0x10, 0x11, 0x22, 0x13, 0x14, 0x15, 0x26, 0x17);
        asm.db(0x18, 0x29, 0x1a, 0x1b, 0x1c, 0x2d, 0x1e, 0x1f);
        asm.db(0x00); // stop
        asm.align(2);
        
        // SD_PEG_00
        asm.label("SD_PEG_00");
        asm.dw(0x0000, 0x0013);
        asm.dw(0x0000, 0x2a02);
        asm.dw(0x0080, 0xd605);
        asm.dw(0xff80, 0x2a02);
        asm.dw(0x7f01);
        asm.align(2);
        
        // bgm01
        asm.label("bgm01");
        asm.label("bgm01_TTAB");
        asm.dw_ref_diff("bgm01_BASE", "bgm01_TTAB");
        asm.dw(2); 
        asm.dw(0x0000); asm.dw_ref_diff("bgm01_T0", "bgm01_BASE");
        asm.dw(0x0400); asm.dw_ref_diff("bgm01_T1", "bgm01_BASE");
        
        asm.label("bgm01_BASE");
        asm.dw_ref_diff("SD_FM_00", "sdtop");
        asm.dw_ref_diff("SD_PSG_00", "sdtop");
        asm.dw_ref_diff("SD_PEG_00", "sdtop");
        
        asm.label("bgm01_T0");
        asm.db(ins, 0, vol, 0x8f, pan, 0x40);
        asm.db(en3, l8, en3, l16, en3, gn3, l8, en3, l16, an3, l8, en3, l16, an3, slr, as3, slr, an3, gn3, en3, l8, l8, en3, l8);
        asm.db(finish);
        asm.align(2);
        
        asm.label("bgm01_T1");
        asm.db(ins, 0, vol, 0x8d, pan, 0x80, dtn, 10, l32);
        asm.db(en3, l8, en3, l16, en3, gn3, l8, en3, l16, an3, l8, en3, l16, an3, slr, as3, slr, an3, gn3, en3, l8, l8, en3, l8);
        asm.db(finish);
        asm.align(2);

        // bgm02 (Pitch EG test)
        asm.label("bgm02");
        asm.label("bgm02_TTAB");
        asm.dw_ref_diff("bgm02_BASE", "bgm02_TTAB");
        asm.dw(1); // track count
        asm.dw(0x0000); asm.dw_ref_diff("bgm02_T0", "bgm02_BASE");
        
        asm.label("bgm02_BASE");
        asm.dw_ref_diff("SD_FM_00", "sdtop");
        asm.dw_ref_diff("SD_PSG_00", "sdtop");
        asm.dw_ref_diff("SD_PEG_00", "sdtop");
        asm.dw_ref_diff("SD_PEG_1D", "sdtop");
        
        asm.label("SD_PEG_1D");
        asm.db(0x00, 0x00, 0x70, 0xff);
        
        asm.label("bgm02_T0");
        asm.db(tempo, 60, ins, 0, vol, 0x8f);
        asm.db(pta, 50, en4, l4, en5, l4, en4, l4, pta, 0, peg, 2, en4, l2, peg, 3, en4, l1, l2);
        asm.db(finish);
        asm.align(2);
        
        // bgm03 (Stack test)
        asm.label("bgm03");
        asm.label("bgm03_TTAB");
        asm.dw_ref_diff("bgm03_BASE", "bgm03_TTAB");
        asm.dw(1);
        asm.dw(0x0000); asm.dw_ref_diff("bgm03_T0", "bgm03_BASE");
        
        asm.label("bgm03_BASE");
        asm.dw_ref_diff("SD_FM_00", "sdtop");
        asm.dw_ref_diff("SD_PSG_00", "sdtop");
        asm.dw_ref_diff("SD_PEG_00", "sdtop");
        asm.dw_ref_diff("bgm03_P0", "bgm03_BASE"); // PAT_0
        
        asm.label("bgm03_P0");
        asm.db(lp, cn4, l8, dn4);
        asm.db(lpb); asm.db_ref_diff("bgm03_P0a", "bgm03_P0a"); asm.db(en4, fn4, lpf, 2); 
        
        asm.label("bgm03_P0a");
        asm.db(finish);
        
        asm.label("bgm03_T0");
        asm.db(tempo, 60, ins, 0, vol, 0x8f);
        asm.db(pat); asm.db(3); 
        asm.db(cn5, rest, l4, finish);
        asm.align(2);

        // bgm04 (Drum mode)
        asm.label("bgm04");
        asm.label("bgm04_TTAB");
        asm.dw_ref_diff("bgm04_BASE", "bgm04_TTAB");
        asm.dw(1);
        asm.dw(0x0000); asm.dw_ref_diff("bgm04_T0", "bgm04_BASE");
        
        asm.label("bgm04_BASE");
        asm.dw_ref_diff("SD_FM_00", "sdtop");
        asm.dw_ref_diff("SD_PSG_00", "sdtop");
        asm.dw_ref_diff("SD_PEG_00", "sdtop");
        asm.dw_ref_diff("bgm04_D0", "bgm04_BASE");
        asm.dw_ref_diff("bgm04_D1", "bgm04_BASE");
        
        asm.label("bgm04_D0");
        asm.db(pan, 0x40, dmfinish); asm.db(24);
        
        asm.label("bgm04_D1");
        asm.db(pan, 0x80, dmfinish); asm.db(24);
        
        asm.label("bgm04_T0");
        asm.db(tempo, 60, ins, 0, vol, 0x8f, flg, 8+cf_drum_mode);
        asm.db(cn1+3, l8, cn1+4, l4, cn1+3, l2, cn1+3, cn1+4, l4, cn1+4, finish);
        asm.align(2);
        
        // bgm05 (PSG test)
        asm.label("bgm05");
        asm.label("bgm05_TTAB");
        asm.dw_ref_diff("bgm05_BASE", "bgm05_TTAB");
        asm.dw(1);
        asm.dw(0x0600); asm.dw_ref_diff("bgm05_T0", "bgm05_BASE");
        
        asm.label("bgm05_BASE");
        asm.dw_ref_diff("bgm05_PSG_0D", "sdtop");
        
        asm.label("bgm05_PSG_0D");
        asm.db(0x10, 0x21, 0x32, 0x43, 0x01, 0x98, 0xa9, 0xba, 0xcb, 0x00);
        asm.align(2);
        
        asm.label("bgm05_T0");
        asm.db(tempo, 60, ins, 0, vol, 0x8f);
        asm.db(cn4, l8, l8, dn4, l4, en4, l2, fn4, l2, l2);
        asm.db(finish);
        asm.align(2);

        // bgm06 (PSG noise)
        asm.label("bgm06");
        asm.label("bgm06_TTAB");
        asm.dw_ref_diff("bgm06_BASE", "bgm06_TTAB");
        asm.dw(1);
        asm.dw(0x0900); asm.dw_ref_diff("bgm06_T0", "bgm06_BASE");
        
        asm.label("bgm06_BASE");
        asm.dw_ref_diff("bgm06_PSG_0D", "sdtop");

        asm.label("bgm06_PSG_0D");
        asm.db(0x30, 0x32, 0x34, 0x36, 0x38, 0x3A, 0x3C, 0x3E, 0x00);
        asm.align(2);
        
        asm.label("bgm06_T0");
        asm.db(tempo, 60, ins, 0, vol, 0x8f);
        asm.db(lfo, 0x00, cn1, l4, cs1, dn1, ds1, en1, fn1, fs1, gn1);
        asm.db(lfo, 0xe7, cn1, l4, cn2, cn3, cn4, cn5, cn6, cn7, cn8);
        asm.db(lfo, 0xe3, cn8);
        asm.db(finish);
        asm.align(2);

        // bgm07 (Music) - Stubbed
        asm.label("bgm07");
        asm.label("bgm07_TTAB");
        asm.dw_ref_diff("bgm07_BASE", "bgm07_TTAB");
        asm.dw(4);
        asm.dw(0x0000); asm.dw_ref_diff("bgm07_T0", "bgm07_BASE");
        asm.dw(0x0100); asm.dw_ref_diff("bgm07_T1", "bgm07_BASE");
        asm.dw(0x0200); asm.dw_ref_diff("bgm07_T2", "bgm07_BASE");
        asm.dw(0x0300); asm.dw_ref_diff("bgm07_T3", "bgm07_BASE");
        
        asm.label("bgm07_BASE");
        asm.dw_ref_diff("SD_FM_00", "sdtop"); 
        asm.dw_ref_diff("SD_FM_00", "sdtop"); 
        asm.dw_ref_diff("bgm07_P0", "bgm07_BASE"); 
        asm.dw_ref_diff("bgm07_P0", "bgm07_BASE"); 
        asm.dw_ref_diff("bgm07_P0", "bgm07_BASE"); 
        asm.dw_ref_diff("bgm07_P0", "bgm07_BASE"); 
        
        asm.label("bgm07_P0"); asm.db(finish);
        
        asm.label("bgm07_T0"); asm.db(finish);
        asm.label("bgm07_T1"); asm.db(finish);
        asm.label("bgm07_T2"); asm.db(finish);
        asm.label("bgm07_T3"); asm.db(finish);

        asm.label("pcm_tab");
        asm.dw(0);
        
        byte[] data = asm.compile();
        
        Memory mem = new Memory() {
            final byte[] d = data;
            @Override public int read8(int a) { return (a >= 0 && a < d.length) ? d[a] & 0xff : 0; }
            @Override public int read16(int a) { return (read8(a) << 8) | read8(a+1); }
            @Override public int read32(int a) { return (read16(a) << 16) | read16(a+2); }
            @Override public void write8(int a, int v) {}
            @Override public void write16(int a, int v) {}
            @Override public void write32(int a, int v) {}
            @Override public Memory add(int o) { 
                return new OffsetMemory(this, o); 
            }
        };
        
        class TestMdsDrv extends MdsDrv {
            int fmWriteCount = 0;
            int psgWriteCount = 0;
            
            void resetCounts() {
                fmWriteCount = 0;
                psgWriteCount = 0;
            }
            
            @Override protected void write_fm_port0(int a, int d) { fmWriteCount++; }
            @Override protected void write_fm_port1(int a, int d) { fmWriteCount++; }
            
            @Override
            protected Memory getPsgMemory() {
                Memory wrapped = super.getPsgMemory();
                return new Memory() {
                    @Override public void write8(int addr, int data) {
                        if (addr == 0xC00011) {
                            psgWriteCount++;
                        } else {
                            if (wrapped != null) wrapped.write8(addr, data);
                        }
                    }
                    @Override public void write16(int a, int d) { if (wrapped != null) wrapped.write16(a, d); }
                    @Override public void write32(int a, int d) { if (wrapped != null) wrapped.write32(a, d); }
                    @Override public int read8(int a) { return wrapped != null ? wrapped.read8(a) : 0; }
                    @Override public int read16(int a) { return wrapped != null ? wrapped.read16(a) : 0; }
                    @Override public int read32(int a) { return wrapped != null ? wrapped.read32(a) : 0; }
                    @Override public Memory add(int o) { return wrapped != null ? wrapped.add(o) : this; }
                };
            }
        }
        
        WorkArea work = new WorkArea();
        TestMdsDrv testDriver = new TestMdsDrv();
        testDriver.mds_init(work, mem, null);
        
        for (int id = 1; id <= 7; id++) {
            System.out.println("Testing BGM " + id);
            testDriver.resetCounts();
            
            testDriver.mds_request(work, id, 0); 
            
            for (int i=0; i<1000; i++) {
                testDriver.mds_update(work);
            }
            
            System.out.printf("  BGM %d: FM writes=%d, PSG writes=%d%n", id, testDriver.fmWriteCount, testDriver.psgWriteCount);
            
            assertTrue(testDriver.fmWriteCount > 0 || testDriver.psgWriteCount > 0, 
                "BGM " + id + " should generate writes (FM or PSG)");
        }
    }
    
    // Helper for memory offsets
    static class OffsetMemory implements Memory {
        final Memory parent;
        final int base;
        public OffsetMemory(Memory p, int b) { parent=p; base=b; }
        @Override public int read8(int a) { return parent.read8(base + a); }
        @Override public int read16(int a) { return parent.read16(base + a); }
        @Override public int read32(int a) { return parent.read32(base + a); }
        @Override public void write8(int a, int v) { parent.write8(base + a, v); }
        @Override public void write16(int a, int v) { parent.write16(base + a, v); }
        @Override public void write32(int a, int v) { parent.write32(base + a, v); }
        @Override public Memory add(int o) { return new OffsetMemory(parent, base + o); }
    }
    
    static class TestAssembler {
        private final List<Byte> buffer = new ArrayList<>();
        private final Map<String, Integer> labels = new HashMap<>();
        private final List<Patch> patches = new ArrayList<>();
        
        void label(String name) {
            labels.put(name, buffer.size());
        }
        
        void db(int... bytes) {
            for (int b : bytes) buffer.add((byte)b);
        }
        
        void dw(int... words) {
            for (int w : words) {
                buffer.add((byte)(w >> 8));
                buffer.add((byte)(w & 0xff));
            }
        }
        
        void dl(int l) {
            buffer.add((byte)(l >> 24));
            buffer.add((byte)(l >> 16));
            buffer.add((byte)(l >> 8));
            buffer.add((byte)(l & 0xff));
        }
        
        void align(int boundary) {
            while (buffer.size() % boundary != 0) buffer.add((byte)0);
        }
        
        void dw_ref_dist(String target, String base) {
             dw(8); 
        }
        
        void db_ref_diff(String target, String base) {
            patches.add(new Patch(buffer.size(), 1, target, base));
            db(0);
        }
        
        void dw_ref_diff(String target, String base) {
            patches.add(new Patch(buffer.size(), 2, target, base));
            dw(0);
        }
        
        void dl_ref_diff(String target, String base) {
            patches.add(new Patch(buffer.size(), 4, target, base));
            dl(0);
        }
        
        byte[] compile() {
            for (Patch p : patches) {
                if (!labels.containsKey(p.target) || !labels.containsKey(p.base)) {
                    throw new RuntimeException("Missing label: " + p.target + " or " + p.base);
                }
                int val = labels.get(p.target) - labels.get(p.base);
                if (p.size == 1) {
                    buffer.set(p.offset, (byte)(val & 0xff));
                } else if (p.size == 2) {
                    buffer.set(p.offset, (byte)(val >> 8));
                    buffer.set(p.offset + 1, (byte)(val & 0xff));
                } else {
                    buffer.set(p.offset, (byte)(val >> 24));
                    buffer.set(p.offset + 1, (byte)(val >> 16));
                    buffer.set(p.offset + 2, (byte)(val >> 8));
                    buffer.set(p.offset + 3, (byte)(val & 0xff));
                }
            }
            
            byte[] res = new byte[buffer.size()];
            for (int i=0; i<res.length; i++) res[i] = buffer.get(i);
            return res;
        }
        
        static class Patch {
            final int offset;
            final int size;
            final String target;
            final String base;
            public Patch(int o, int s, String t, String b) { offset=o; size=s; target=t; base=b; }
        }
    }
}
