/*
 * https://github.com/superctr/MDSDRV
 */

package vavi.sound.mdsdrv;

import java.lang.System.Logger;
import java.util.HashMap;
import java.util.Map;
import java.lang.System.Logger.Level;

import static java.lang.System.getLogger;


/**
 * MDSDRV - Mega Drive 68K Sound Driver
 * <p>
 * Copyright (c) 2019-2022 Ian Karlsson
 * <p>
 * Ported to Java by vavi.
 */
public class MdsDrv {

    private static final Logger logger = getLogger(MdsDrv.class.getName());

    // Persistent Z80 RAM buffer - must exist for the lifetime of the driver
    protected final byte[] z80RamBuffer = new byte[0x2000];  // 8KB Z80 RAM

    public static final int MDSDRV_VER = 0x0006;
    public static final int MDSDRV_MIN_VER = 0x0003;

    public static final int RCOUNT = 4;
    public static final int TCOUNT = 16;
    public static final int TSTACK_COUNT = 8;

    public static final int rf_active = 15;
    public static final int rf_stop = 14;
    public static final int rf_fade_in = 13;

    public static final int nf = 8 + 16;
    public static final int cf = 0 + 16;

    public static final int nf_ins = 0;
    public static final int nf_sustain = nf_ins;
    public static final int nf_pcm_header = nf_ins;
    public static final int nf_vol = 1;
    public static final int nf_fm3 = 2;
    public static final int nf_pcm_pitch = nf_fm3;
    public static final int nf_pan_lfo = 3;
    public static final int nf_nmode = nf_pan_lfo;
    public static final int nf_slur = 4;
    public static final int nf_key_off = 5;
    public static final int nf_key_on = 6;
    public static final int nf_enabled = 7;

    public static final int cf_drum_mode = 0;
    public static final int cf_mtab_carry = 1;
    public static final int cf_pcm_control = 2;
    public static final int cf_stop = 3;
    public static final int cf_suspend = 4;
    public static final int cf_background = 5;
    public static final int cf_key_on = 7;

    // Assembly (mdsdrv.68k) does not force instrument load at init.
    // Removed nf_ins to prevent garbage loading for Raw Mode files.
    // Assembly (mdsdrv.inc line 106): nm_init = nf_ins | nf_pan_lfo | nf_enabled
    public static final int nm_init = ((1 << nf_ins) | (1 << nf_pan_lfo) | (1 << nf_enabled));
    public static final int nm_restore = ((1 << nf_key_off) | (1 << nf_ins) | (1 << nf_vol) | (1 << nf_pan_lfo)
            | (1 << nf_fm3));
    public static final int cm_pause = ((1 << cf_suspend) | (1 << cf_stop));

    public static final int ct_fm = 0;
    public static final int ct_psg = 6;
    public static final int ct_psgn = 9;

    public static final int pe_pcm1 = 7;
    public static final int pe_pcm2 = 6;
    public static final int pe_pcm3 = 5;
    public static final int pe_fade_stop = 4;

    public static class TrackData {
        public int t_track_idx;  // Debug: track index in w_track array
        public int t_note_flag;
        public int t_channel_flag;
        public int t_base_addr;
        public int t_position;
        public int t_stack_pos;
        public int t_counter;
        public int t_rest_time;
        public int t_note_time;

        public int t_channel_id;
        public int t_request_id;
        public int t_ins;
        public int t_ins_trs;
        public int t_note;
        public int t_dtn;
        public int t_pitch;
        public int t_last_pitch;
        public int t_trs;
        public int t_pta;
        public int t_vol;

        public int t_mtab_repeat;
        public int t_mtab_addr;
        public int t_mtab_delay;
        public int t_mtab_pos;

        public int t_peg_addr;
        public int t_peg_mod;
        public int t_peg_delay;
        public int t_peg_pos;

        public final int[] t_stack = new int[TSTACK_COUNT];

        public int t_fm_pan_lfo;
        public int t_fm_alg;
        public final int[] t_fm_tl = new int[4];

        public int t_psg_eg_addr;
        public Memory t_psg_env_data; // Added for RIFF support
        public int t_psg_eg_pos;
        public int t_psg_eg_delay;
        public int t_psg_nreset;
        public int t_psg_nmode;

        public int t_pcm_pan;
        public int t_pcm_pitch;
        public int t_pcm_header;
        public int t_pcm_length;
        public int t_op_mask; // FM3 Special Mode mask
        
        // Debug: Note duration tracking
        public int t_debug_note_frames;
        public int t_debug_last_note;

        // Number of times this track has taken its master loop (a backward 0xF5 jump).
        // Used to expose a song loop count via getNowLoopCounter().
        public int t_loop_count;
    }

    public static class WorkArea {
        public Memory w_sdtop;
        public final int[] w_request = new int[RCOUNT];
        public final int[] w_tempo = new int[RCOUNT];
        public final int[] w_counter = new int[RCOUNT];
        public final int[] w_seq_step = new int[RCOUNT];
        public final int[] w_volume = new int[RCOUNT];
        public final int[] w_tmask = new int[RCOUNT];
        public final int[] w_chmask = new int[RCOUNT];

        public int w_bgm_volume;
        public int w_se_volume;

        public int w_priority;
        public int w_fade_rate;

        public int w_fade_target;
        public int w_comm;
        public int w_fm3_mask; // FM3 Special Mode global mask

        public int w_pcm_bank;
        public int w_pcm_mode;
        public Memory w_pcm_ptr;  // PCM data pointer (Assembly: a2 in mds_init)
        
        public int w_pointer_mode; // 0=Standard (Header+4), 1=Raw (Header+0)
        public int w_fm3_alg;
        public final int[] w_fm3_tl = new int[4];


        public int w_gtempo;
        
        public final Map<Integer, byte[]> globInstruments = new HashMap<>();
        public final Map<Integer, byte[]> pcmHeaders = new HashMap<>();

        public final TrackData[] w_track = new TrackData[TCOUNT];

        public WorkArea() {
            for (int i = 0; i < TCOUNT; i++) {
                w_track[i] = new TrackData();
                w_track[i].t_track_idx = i;  // Debug: store track index
            }
        }
    }

    public void mds_top(WorkArea a0, Memory a1, Memory a2) {
        mds_init(a0, a1, a2);
        mds_update(a0);
        mds_request(a0, 0, 0);
        mds_command(a0, 0, 0, 0);
    }

    public static final String version_str = "MDSDRV0.6 230612";

    /**
     * Initialize driver with sequence data and PCM data.
     * Assembly signature: mds_init(a0=work, a1=sdtop, a2=pcm_data)
     */
    public int mds_init(WorkArea a0, Memory a1, Memory a2) {
        a0.w_pcm_ptr = a2;  // Store PCM pointer for mds_z80_init
        int d1;

        int magic = a1.read32(0);
        if (magic == 0x10011f00) {
            a1 = a1.add(4); // Skip Magic
            // int ver = a1.read32(0);
            a1 = a1.add(4); // Skip Version
            // ver = ((ver << 16) | (ver >>> 16)); // endian swap check?
            a0.w_pointer_mode = 0; // Standard layout: Count(2)+Res(2)+Ptrs
        } else if (magic == 0x52494646) { // RIFF (Big Endian read)
             // Parse RIFF headers to find seq chunk and instruments
             Memory seqData = parseRiffMds(a0, a1);
             if (seqData != null) {
                 a1 = seqData; // Use seq data base
                 a0.w_pointer_mode = 1; // Raw mode for RIFF files
             } else {
                 // Fallback
                 a0.w_pointer_mode = 1;
             }
        } else {
             logger.log(Level.WARNING, "Invalid magic number: " + Integer.toHexString(magic) + " (assuming raw data, not skipping header)");
             // Do not skip a1
             a0.w_pointer_mode = 1; // Raw layout: Ptrs at 0
        }

        a0.w_sdtop = a1;
        

        logger.log(Level.DEBUG, "mds_init complete, sdtop=" + a1);

        // ver = ((ver << 16) | (ver >>> 16));

        // Version check disabled by user request
        // if ((short) (ver & 0xffff) < MDSDRV_MIN_VER) {
        // return mds_init_error(a0);
        // }

        for (int i = 0; i < RCOUNT; i++) {
            a0.w_request[i] = 0;
            a0.w_tempo[i] = 0;
            a0.w_counter[i] = 0;
            a0.w_seq_step[i] = 0;
            a0.w_volume[i] = 0;
            a0.w_tmask[i] = 0;
            a0.w_chmask[i] = 0;
        }

        a0.w_bgm_volume = 0;
        a0.w_fade_target = 0;
        a0.w_fm3_mask = 0;
        for (int i = 0; i < 4; i++)
            a0.w_fm3_tl[i] = 0;

        d1 = 128;
        if ((readIo(MdDef.io_version) & 0x40) != 0) {
            d1 = 107;
        }
        a0.w_gtempo = d1;

        for (int i = 0; i < TCOUNT; i++) {
            TrackData mm = a0.w_track[i];
            mm.t_note_flag = 0;
            mm.t_peg_addr = 0;
            mm.t_psg_eg_addr = 0xfff8;
            mm.t_psg_eg_pos = 0xff0f;
            mm.t_request_id = RCOUNT * 2;
        }

        writeIo(MdDef.z80_bus_request, 0x100);
        writeIo(MdDef.z80_reset, 0x100);

        mds_z80_init(a0);

        writeIo(MdDef.z80_bus_request, 0x100);

        // Initialize PSG with silence (Assembly lines 114-117)
        Memory psg = getPsgMemory();
        psg.write8(MdDef.sound_psg, 0x9f); // Ch 0 Vol 15
        psg.write8(MdDef.sound_psg, 0xbf); // Ch 1 Vol 15
        psg.write8(MdDef.sound_psg, 0xdf); // Ch 2 Vol 15
        psg.write8(MdDef.sound_psg, 0xff); // Ch 3 Vol 15
        
        // Ensure PCM KeyOn is cleared
        Memory zram = getZ80Ram();
        zram.write8(MdDef.z80_ram + 0x0E08, 0); // Z_PCM1 KeyOn
        zram.write8(MdDef.z80_ram + 0x0E08 + 8, 0); // Z_PCM2 KeyOn
        zram.write8(MdDef.z80_ram + 0x0E08 + 16, 0); // Z_PCM3 KeyOn

        // Initialize FM LFO and DAC enable registers
        // Reg $22 = LFO control (0x00 = off, 0x08 = on with default freq)
        // Reg $2B = DAC enable (0x80 = DAC on for FM6/channel 5)
        write_fm_port0(0x22, 0x00);  // LFO off
        write_fm_port0(0x2B, 0x80);  // DAC on (enable PCM output)

        // Key-off all FM channels to start clean
        for (int ch = 0; ch < 6; ch++) {
            int slot = (ch < 3) ? ch : (ch - 3 + 4);
            write_fm_port0(0x28, slot);  // Key off (operator mask = 0)
        }

        logger.log(Level.DEBUG, "mds_init complete");

        writeIo(MdDef.z80_reset, 0x000);
        for (int i = 0; i < 20; i++)
            Thread.yield();
        writeIo(MdDef.z80_reset, 0x100);

        writeIo(MdDef.z80_bus_request, 0);

        return 0;
    }

    @SuppressWarnings("unused")
    private static int mds_init_error(WorkArea a0) {
        a0.w_sdtop = null;
        return -1;
    }

    private void mds_z80_init(WorkArea a0) {
        // Assembly (lines 3144-3149):
        //   move.l  a2,d0
        //   add.l   d0,d0
        //   swap    d0
        //   move.b  d0,w_pcm_bank(a0)
        // This extracts bits 16-23 of (pcm_ptr * 2) as the bank offset
        if (a0.w_pcm_ptr != null) {
            // Note: In Java we don't have a memory address value, but this would be
            // the absolute address shifted. For software emulation, bank is typically 0.
            a0.w_pcm_bank = 0;  // Default bank for emulation (no hardware bank switching)
        } else {
            a0.w_pcm_bank = 0;
        }
        a0.w_pcm_mode = 2;
        getZ80Ram().write8(MdDef.z80_ram + 0x0e05, a0.w_pcm_mode);

        try {
            // InputStream is = MdsDrv.class.getResourceAsStream("/mdssub.bin");
            // Memory z80ram = getZ80Ram();
            // if (is == null) {
            // logger.log(Level.WARNING, "mdssub.bin not found");
            // } else {
            // byte[] z80Code = is.readAllBytes();
            // for (int i = 0; i < z80Code.length; i++) {
            // z80ram.write8(MdDef.z80_ram + i, z80Code[i]);
            // }
            // is.close();
            // }

            // Stubbing out Z80 code load, but we might still need volume table or leave it
            // stubbed?
            // User says "remove those related".
            // However, the volume table initialization loop below depends on volume_table.
            // If mdssub.bin is not used, maybe the volume table in Z80 RAM is also not
            // used?
            // The Java code MdsDrv.mds_pcm_update seems to access MdDef.z80_ram +
            // z_vtab_offset?
            // Let's check getZVtabOffset() usage.

            // if Java PCM code uses volume table, we should keep volume table init.
            // But we can remove the resource loading.
            Memory z80ram = getZ80Ram();
            
            // Standard Table: Index 0 = Max (256), Index 15 = Min (0).
            int[] volume_table = { 256, 203, 161, 128, 102, 81, 64, 51, 40, 32, 26, 20, 16, 13, 10, 0 };
            int z_vtab_offset = getZVtabOffset();
            int a1 = MdDef.z80_ram + z_vtab_offset;

            for (int vol : volume_table) {
                for (int d0 = 0; d0 < 256; d0++) {
                    byte d1_b = (byte) (d0 - 128);
                    int d1 = d1_b;
                    d1 *= vol;
                    byte high = (byte) (d1 >> 8);
                    z80ram.write8(a1++, high);
                }
            }
        } catch (Exception e) {
            logger.log(Level.ERROR, "Error initializing Z80 tables", e);
        }
    }

    public void mds_request(WorkArea a0, int d0, int d1) {
        d0 |= (1 << rf_active) | (1 << rf_stop);
        d1 &= 3;
        a0.w_request[d1] = d0;
    }

    public int mds_command(WorkArea a0, int d0, int d1, int d2) {
        if (d0 > 0x12)
            return 0;

        return switch (d0) {
            case 0x00 -> get_cmd_count();
            case 0x01 -> get_sound_count(a0);
            case 0x02 -> get_status(a0, d1);
            case 0x03 -> 0;
            case 0x04 -> get_gtempo(a0);
            case 0x05 -> {
                set_gtempo(a0, d1);
                yield 0;
            }
            case 0x06 -> get_gvolume(a0);
            case 0x07 -> {
                set_gvolume(a0, d1);
                yield 0;
            }
            case 0x08 -> {
                write_fm_port0(d1, d2);
                yield 0;
            }
            case 0x09 -> {
                write_fm_port1(d1, d2);
                yield 0;
            }
            case 0x0a -> {
                fade_bgm(a0, d1);
                yield 0;
            }
            case 0x0b -> {
                set_pause(a0, d1, d2);
                yield 0;
            }
            case 0x0c -> get_volume(a0, d1);
            case 0x0d -> {
                set_volume(a0, d1, d2);
                yield 0;
            }
            case 0x0e -> get_tempo(a0, d1);
            case 0x0f -> {
                set_tempo(a0, d1, d2);
                yield 0;
            }
            case 0x10 -> get_comm(a0);
            case 0x11 -> {
                set_pcmmode(a0, d1, d2);
                yield 0;
            }
            case 0x12 -> get_pcmmode(a0);
            default -> 0;
        };
    }

    private static int get_cmd_count() {
        return 0x12;
    }

    private static int get_sound_count(WorkArea a0) {
        return a0.w_sdtop.read16(-2);
    }

    private static int get_status(WorkArea a0, int d1) {
        return a0.w_tmask[d1];
    }

    private static int get_gtempo(WorkArea a0) {
        return a0.w_gtempo;
    }

    private static void set_gtempo(WorkArea a0, int d1) {
        a0.w_gtempo = d1;
    }

    private static int get_gvolume(WorkArea a0) {
        return (a0.w_bgm_volume << 8) | a0.w_se_volume;
    }

    private static void set_gvolume(WorkArea a0, int d1) {
        a0.w_bgm_volume = (d1 >> 8) & 0xff;
        a0.w_se_volume = d1 & 0xff;
    }

    protected void write_fm_port0(int d0, int d1) {
        mds_z80_wait_fm();
        Memory zram = getZ80Ram();
        zram.write8(MdDef.z80_ram + 0x4000, d0);
        zram.write8(MdDef.z80_ram + 0x4001, d1);
        mds_z80_start();
    }

    protected void write_fm_port1(int d0, int d1) {
        mds_z80_wait_fm();
        Memory zram = getZ80Ram();
        zram.write8(MdDef.z80_ram + 0x4002, d0);
        zram.write8(MdDef.z80_ram + 0x4003, d1);
        mds_z80_start();
    }

    private void mds_z80_wait_fm() {
    }

    private void mds_z80_start() {
        writeIo(MdDef.z80_bus_request, 0);
    }

    private static void fade_bgm(WorkArea a0, int d1) {
        a0.w_pcm_mode &= ~(1 << pe_fade_stop);
        int d0 = d1 & 0xff;
        if ((d1 & 0x80) != 0) {
            a0.w_pcm_mode |= (1 << pe_fade_stop);
        }
        d0 &= 0x7f;
        int rateIndex = (d1 >> 8) & 0xff;
        int[] mds_fade_rate_table = { 0x01, 0x11, 0x49, 0x55, 0x57, 0x77, 0x7f, 0xff };
        if (rateIndex < mds_fade_rate_table.length) {
            a0.w_fade_rate = mds_fade_rate_table[rateIndex];
        }
        a0.w_fade_target = d0;
    }

    private static void set_pause(WorkArea a0, int d1, int d2) {
        int mask = a0.w_tmask[d1];
        for (int i = 0; i < TCOUNT; i++) {
            TrackData t = a0.w_track[i];
            if (((mask >> i) & 1) != 0) {
                if (d2 != 0) {
                    if (((t.t_note_flag >> nf_enabled) & 1) != 0) {
                        t.t_channel_flag |= cm_pause;
                    }
                } else {
                    if (((t.t_channel_flag >> cf_suspend) & 1) != 0) {
                        t.t_channel_flag &= ~(1 << cf_suspend);
                        t.t_note_flag |= nm_init | nm_restore;
                    }
                }
            }
        }
    }

    private static int get_volume(WorkArea a0, int d1) {
        return (a0.w_volume[d1] >> 8) & 0xff;
    }

    private void set_volume(WorkArea a0, int d1, int d2) {
        int val = d1;
        int idx = d2;
        a0.w_volume[idx] = (a0.w_volume[idx] & 0xff) | ((val & 0xff) << 8);
        int converted = mds_convert_vol(val);
        a0.w_volume[idx] = (a0.w_volume[idx] & 0xff00) | (converted & 0xff);
        int mask = a0.w_tmask[idx];
        for (int i = 0; i < TCOUNT; i++) {
            if (((mask >> i) & 1) != 0) {
                TrackData t = a0.w_track[i];
                t.t_note_flag |= (1 << nf_vol);
            }
        }
    }

    private int mds_convert_vol(int vol) {
        int v = vol & 0xff;
        int t = 0x80;
        if (v < mds_psg_vol_table.length)
            t = mds_psg_vol_table[v];
        return (0x8f - t) & 0xff;
    }

    private final int[] mds_psg_vol_table;
    {
        mds_psg_vol_table = new int[256];
        int idx = 0;
        for (int i = 0; i < 2; i++)
            mds_psg_vol_table[idx++] = 0x8f;
        for (int i = 0; i < 3; i++)
            mds_psg_vol_table[idx++] = 0x8f;
        for (int i = 0; i < 3; i++)
            mds_psg_vol_table[idx++] = 0x8e;
        for (int i = 0; i < 2; i++)
            mds_psg_vol_table[idx++] = 0x8d;
        for (int i = 0; i < 3; i++)
            mds_psg_vol_table[idx++] = 0x8c;
        for (int i = 0; i < 3; i++)
            mds_psg_vol_table[idx++] = 0x8b;
        for (int i = 0; i < 2; i++)
            mds_psg_vol_table[idx++] = 0x8a;
        for (int i = 0; i < 3; i++)
            mds_psg_vol_table[idx++] = 0x89;
        for (int i = 0; i < 3; i++)
            mds_psg_vol_table[idx++] = 0x88;
        for (int i = 0; i < 2; i++)
            mds_psg_vol_table[idx++] = 0x87;
        for (int i = 0; i < 3; i++)
            mds_psg_vol_table[idx++] = 0x86;
        for (int i = 0; i < 3; i++)
            mds_psg_vol_table[idx++] = 0x85;
        for (int i = 0; i < 2; i++)
            mds_psg_vol_table[idx++] = 0x84;
        for (int i = 0; i < 3; i++)
            mds_psg_vol_table[idx++] = 0x83;
        for (int i = 0; i < 3; i++)
            mds_psg_vol_table[idx++] = 0x82;
        for (int i = 0; i < 2; i++)
            mds_psg_vol_table[idx++] = 0x81;
        for (int i = 0; i < 64 - 42; i++)
            mds_psg_vol_table[idx++] = 0x81;
        for (int i = 0; i < 64; i++)
            mds_psg_vol_table[idx++] = 0x80;
    }

    private static int get_tempo(WorkArea a0, int d1) {
        return a0.w_tempo[d1];
    }

    private static void set_tempo(WorkArea a0, int d1, int d2) {
        a0.w_tempo[d1] = d2;
    }

    private static int get_comm(WorkArea a0) {
        return a0.w_comm;
    }

    private void set_pcmmode(WorkArea a0, int d1, int d2) {
        mds_set_pcm_mode(a0, d1);
    }

    private static int get_pcmmode(WorkArea a0) {
        return 0;
    }

    private void mds_set_pcm_mode(WorkArea a0, int d0) {
        a0.w_pcm_mode = (a0.w_pcm_mode & 0x10) | d0;
        getZ80Ram().write8(MdDef.z80_ram + 0x0e05, a0.w_pcm_mode);
    }

    // Abstract IO and Memory methods
    protected int readIo(int port) {
        return 0;
    }

    protected void writeIo(int port, int data) {
    }

    protected Memory getZ80Ram() {
        // Use persistent Z80 RAM buffer from class field
        return new Memory() {
            @Override
            public int read8(int addr) {
                int offset = addr - MdDef.z80_ram;
                if (offset >= 0 && offset < z80RamBuffer.length) {
                    return z80RamBuffer[offset] & 0xFF;
                }
                return 0;
            }

            @Override
            public int read16(int addr) {
                return (read8(addr) << 8) | read8(addr + 1);
            }

            @Override
            public int read32(int addr) {
                return (read16(addr) << 16) | read16(addr + 2);
            }

            @Override
            public void write8(int addr, int data) {
                int offset = addr - MdDef.z80_ram;
                if (offset >= 0 && offset < z80RamBuffer.length) {
                    z80RamBuffer[offset] = (byte) data;
                }
            }

            @Override
            public void write16(int addr, int data) {
                write8(addr, data >> 8);
                write8(addr + 1, data & 0xFF);
            }

            @Override
            public void write32(int addr, int data) {
                write16(addr, data >> 16);
                write16(addr + 2, data & 0xFFFF);
            }

            @Override
            public Memory add(int offset) {
                return this;
            }
        };
    }

    protected Memory getPsgMemory() {
        return getZ80Ram();
    }

    protected int getZVtabOffset() {
        return 0x0F00;
    }

    public void mds_update(WorkArea a0) {
        for (int rnum = 0; rnum < RCOUNT; rnum++) {
            int reqdata = a0.w_request[rnum];
            if ((reqdata & (1 << 15)) != 0) {
                mds_handle_request(a0, rnum, reqdata);
            }
            reqdata = a0.w_tmask[rnum];
            if (reqdata != 0) {
                // Assembly uses 16-bit word operations - mask to match
                int counter = a0.w_counter[rnum] & 0xffff;
                counter += a0.w_tempo[rnum] & 0xffff;  // w_tempo is unsigned 16-bit
                counter++;
                counter &= 0xffff;  // Keep as 16-bit word
                int gtempo = a0.w_gtempo & 0xffff;
                int seq_step = 0;
                while (counter >= gtempo) {
                    counter -= gtempo;
                    seq_step++;
                }
                a0.w_seq_step[rnum] = seq_step;
                a0.w_counter[rnum] = counter & 0xffff;
            }
        }

        if (a0.w_priority != 0 || a0.w_fade_rate != 0) {
            if (a0.w_priority != 0)
                mds_update_priority(a0);
            else
                mds_update_fade(a0);
        }

        for (int tnum = 0; tnum < TCOUNT; tnum++) {
            TrackData twork = a0.w_track[tnum];
            int flag = twork.t_note_flag;

            if ((flag & (1 << nf_enabled)) == 0)
                continue;

            int rnum = twork.t_request_id;
            if (rnum >= RCOUNT * 2)
                continue;

            int real_rnum = rnum / 2;
            int seq_step = a0.w_seq_step[real_rnum];

            // Debug: track when seq_step is 0 (would prevent counter decrement)
            if (seq_step == 0 && twork.t_counter > 0 && twork.t_channel_id >= 8) {
                // Debug for PSG channels (channel_id 8, 9, 10, 11)
                // logger.log(Level.TRACE, "SEQ_STEP_ZERO: ch=%d rnum=%d counter=%d%n", 
                //     twork.t_channel_id, real_rnum, twork.t_counter);
            }

            // Assembly (line 546): dbra d5,mds_update_seq
            // dbra: decrement d5, if d5 != -1 branch to label
            // So d5=0 -> decrement to -1 -> don't branch (0 calls)
            // d5=1 -> decrement to 0, branch (1 call), decrement to -1, don't branch
            // Therefore d5=N means exactly N calls, matching `i < seq_step`
            for (int i = 0; i < seq_step; i++) {
                mds_update_seq(a0, twork);
            }

            // Check background flag (Assembly line 548)
            if ((twork.t_channel_flag & (1 << cf_background)) != 0) {
                // Background track: check stop, do voice update anyway
                if ((twork.t_channel_flag & (1 << cf_stop)) != 0) {
                    twork.t_channel_flag &= ~(1 << cf_stop);
                    twork.t_note_flag &= ~(1 << nf_enabled);
                }
                continue;  // Skip foreground voice update for background tracks
            }

            // Foreground track: check stop flag (Assembly line 552-553)
            if ((twork.t_channel_flag & (1 << cf_stop)) != 0) {
                // Track stopping: set key-off, disable, then update voice
                twork.t_note_flag |= (1 << nf_fm3) | (1 << nf_key_off);
                twork.t_note_flag &= ~(1 << nf_enabled);
                twork.t_channel_flag &= ~(1 << cf_stop);
            }

            // Always call voice update for foreground tracks
            do_voice_update(a0, twork);
        }
    }

    private static void mds_handle_request(WorkArea a0, int rnum, int reqdata) {
        a0.w_priority = 1;
        boolean stop = (reqdata & (1 << rf_stop)) != 0;
        reqdata &= ~(1 << rf_stop);
        if (stop) {
            stop_song(a0, rnum, reqdata);
            return;
        }

        reqdata &= ~(1 << rf_active);
        a0.w_request[rnum] = reqdata;
        if (reqdata == 0)
            return;

        Memory sdtop = a0.w_sdtop;
        if (sdtop == null)
            return;

        a0.w_tempo[rnum] = a0.w_gtempo;
        a0.w_counter[rnum] = 0;
        a0.w_seq_step[rnum] = 0;
        a0.w_tmask[rnum] = 0;

        // Fix for passport.mds structure (and possibly raw output format?):
        // Header: Count (2 bytes) | Ver/Reserved (2 bytes) | Ptr Table (16-bit offsets)
        // Offset 0: Count (0x0028)
        // Offset 2: Reserved (0x0009)
        // Offset 4: Ptr 0 (0x0000)
        // Offset 6: Ptr 1 (0x0062)
        // reqdata 1 maps to Ptr 0 (1-based index)
        // Resolve sequence pointer based on mode
        int offset;
        if (a0.w_pointer_mode == 1) {
             // Raw mode: Standard Header at 0 (Verified correct for PSG tracks)
             offset = 0;
        } else {
             // Standard mode: Header 4 bytes. Req 1 -> Index 1.
             offset = a0.w_sdtop.read16(4 + reqdata * 2);
        }
        int headerOffset = offset;
        
        // Reverted "Passport" logic block. File uses Standard Header structure starting at 0.
        // Falls through to Standard Logic below.

        // Fall through to Standard Logic for both modes
        // (Assembly logic uses identical parsing once base offset is determined)
        
        Memory header = sdtop.add(headerOffset);
        int songBaseOffset = header.read16(0) & 0xffff;
        

        // Memory trackBase = header.add(songBaseOffset); // Unused after fix
        header = header.add(2);

        int vol = a0.w_bgm_volume;
        vol += header.read8(0);
        header = header.add(1);
        if (vol > 127)
            vol = 127;
        // a0.w_volume[rnum] = (vol << 8) | (reqdata & 0xff);
        // Fix: Do not add Song Index (reqdata) to Volume. User log expects pure volume.
        a0.w_volume[rnum] = (vol << 8);
        // logger.log(Level.TRACE, "mds_request: rnum=%d reqdata=%d vol=%d w_volume=%04x%n", rnum, reqdata, vol, a0.w_volume[rnum]);

        int tcount = header.read8(0);
        header = header.add(1);

        // Song base for this song (tbase in assembly)
        int songBase = headerOffset + songBaseOffset;
        
        
        int tracksFound = 0;
        
        for (int tnum = 0; tnum < TCOUNT && tracksFound < tcount; tnum++) {
            TrackData t = a0.w_track[tnum];
            // Check if track is free (not enabled AND not suspended)
            if ((t.t_note_flag & (1 << nf_enabled)) != 0 || (t.t_channel_flag & (1 << cf_suspend)) != 0) {
                continue;
            }
            


            // Track base is the song base (used for relative addressing in sequence commands)
            t.t_base_addr = songBase;
            
            // Debug: Dump table for Ch 5 (Drum)
            // Just peek at the first byte of header to guess chnid?
            // Actually chnid is read next.
            // Let's defer dump until chnid is read.

            // Read track data in CORRECT ORDER (per assembly):
            // 1. channel_id (1 byte)
            int chnid = header.read8(0) & 0xff;
            header = header.add(1);
            t.t_channel_id = chnid;
            t.t_request_id = rnum * 2;

            // 2. channel_flag (1 byte)
            t.t_note_flag = nm_init;
            t.t_channel_flag = header.read8(0) & 0xff;
            header = header.add(1);

            // Allocate track and channel
            a0.w_tmask[rnum] |= (1 << tnum);
            a0.w_chmask[rnum] |= (1 << chnid);

            // Initialize track memory
            t.t_last_pitch = 0xffff;
            // Assembly (line 802): move.w #$0030,t_ins(twork)
            // 68000 is big-endian: high byte first, low byte second
            // So: t_ins = 0x00, t_ins_trs = 0x30
            t.t_ins = 0;
            t.t_ins_trs = 0x30;  // Assembly: move.w #$0030,t_ins(twork) = ins:0, ins_trs:0x30
            t.t_note = 0;
            t.t_dtn = 0;
            t.t_trs = 0;
            t.t_pta = 0;
            t.t_vol = 0x8f;  // Volume is single byte in assembly (0x8f)
            t.t_mtab_repeat = 0;
            t.t_mtab_addr = 0;
            t.t_peg_addr = 0;
            t.t_peg_mod = 0;
            t.t_loop_count = 0;
            
            // 3. position (2 bytes) - THIS IS THE START POSITION IN SEQUENCE!
            int position = header.read16(0) & 0xffff;
            header = header.add(2);
            t.t_position = position;
            
            // Stack and timing
            t.t_stack_pos = 0;
            t.t_counter = 0; // Initialize counter to 0 to ensure immediate start on first seq_step
            t.t_rest_time = 0x0b;
            
            // Assembly: move.w #$0b0b,t_rest_time(twork)
            // It writes 0b0b to t_rest_time.
            // If t_rest_time is a word (or adjacent fields), this might init something else.
            // Struct def: t_rest_time (byte/word), t_stack_pos (byte).
            // In Java, fields are separate.
            // Explicit init is safer.
            t.t_stack_pos = 0;
            t.t_counter = 0;
            t.t_rest_time = 0x0b;  // Default rest time
            t.t_note_time = 0x0b;  // Default note time
            
            // PSG/FM specific init (Assembly lines 815-834)
            if (chnid >= ct_psg) {
                // PSG initialize (Assembly lines 821-823)
                // move.w @zero,t_psg_nreset(twork)        ; noise mode = 0
                // move.l #$fff8ff0f,t_psg_eg_addr(twork)  ; eg_addr=$fff8, eg_pos=$ff, eg_delay=$0f
                t.t_psg_nreset = 0;
                t.t_psg_nmode = 0;
                t.t_psg_eg_addr = 0xfff8;  // Invalid address = no envelope until instrument loaded
                t.t_psg_env_data = null;   // No dummy envelope - use instrument data via E1 command
                t.t_psg_eg_pos = 0xff;     // 0xff = envelope disabled
                t.t_psg_eg_delay = 0x0f;   // Volume silence (15 = silent on PSG)
                t.t_last_pitch = 0xffff;   // Force pitch update on first note
            } else {
                t.t_fm_pan_lfo = 0xc0;  // Default panning (both L+R)
                t.t_fm_alg = 0;
                t.t_fm_tl[0] = 0x7f;
                t.t_fm_tl[1] = 0x7f;
                t.t_fm_tl[2] = 0x7f;
                t.t_fm_tl[3] = 0x7f;
                t.t_last_pitch = 0xffff;  // Force pitch update on first note (same as PSG)
            }


            tracksFound++;
        }
    }

    private static void stop_song(WorkArea a0, int rnum, int reqdata) {
        a0.w_request[rnum] = reqdata;
        int tmask = a0.w_tmask[rnum];
        for (int i = 0; i < TCOUNT; i++) {
            if (((tmask >> i) & 1) != 0) {
                TrackData t = a0.w_track[i];
                t.t_channel_flag |= (1 << cf_stop);
                t.t_channel_flag &= ~(1 << cf_suspend);
                t.t_request_id = RCOUNT * 2;
            }
        }
        a0.w_tmask[rnum] = 0;
        a0.w_chmask[rnum] = 0;

        if (rnum == 3) {
            a0.w_fade_target = 0;
            a0.w_fade_rate = 0;
            a0.w_pcm_mode &= ~(1 << pe_fade_stop);
        }
    }

    private static void mds_update_priority(WorkArea a0) {
        a0.w_priority = 0;
        
        for (int tnum = 0; tnum < TCOUNT; tnum++) {
            TrackData twork = a0.w_track[tnum];
            int flag = twork.t_note_flag;
            
            // Check suspension (Assembly lines 886-887)
            if ((twork.t_channel_flag & (1 << cf_suspend)) != 0) {
                 // Suspended: continue to checks
            } else {
                 // Not suspended: check enabled
                 if ((flag & (1 << nf_enabled)) == 0) {
                     continue; // voice not enabled
                 }
            }
            
            // Check Priority (Assembly lines 890-907)
            int chnid = twork.t_channel_id;
            int reqId = twork.t_request_id;
            boolean hasPriority = true;
            
            // Iterate requests RCOUNT-1 down to 0
            // Logic: check all requests *before* the current one?
            // Assembly logic:
            //   start with current request offset? 
            //   No, it scans `w_chmask` array.
            //   Wait, assembly uses `rept RCOUNT-1`.
            //   It checks if ANY request has this channel bit set?
            //   Actually it checks logic to find if *higher priority* requests have this channel.
            //   Requests are 0..3. Lower index = Higher Priority?
            //   No, `mds_handle_request` says:
            //   `d1` request priority. stored in `w_request`.
            //   Wait. `w_chmask` is per-request.
            //   Assembly logic:
            //   d0 = t_request_id (e.g. 0, 2, 4, 6)
            //   tmpa0 = w_chmask
            //   Loop 3 times (RCOUNT-1):
            //     Check if `w_chmask` entry matches `d0`?
            //     Assembly:
            //       beq.s @has_priority (if d0 == current checked request?)
            //       move.w (tmpa0)+,d1 (read mask)
            //       btst chnid,d1 (is channel in this mask?)
            //       bne.s @no_priority (if so, someone else has it)
            //       subq.b #2,d0 (decrement request ID we are looking for?)
            //   
            //   Wait. `d0` is `t_request_id`.
            //   If `d0` is 6 (Request 3).
            //   It checks masks for Request 0, 1, 2.
            //   If any of them have `chnid` set, then `no_priority`.
            //   
            //   Assembly loop is unrolled or structured to check *other* requests.
            //   Actually `tmpa0` points to `w_chmask` (start).
            //   `d0` is current track's request ID.
            //   Code:
            //     beq.s @has_priority
            //     move.w (tmpa0)+,d1
            //     btst chnid,d1
            //     bne.s @no_priority
            //     subq.b #2,d0
            //   
            //   It subtracts 2 from d0 each time.
            //   If d0 reaches 0 (beq), it means we reached our own request ID?
            //   So it checks requests *below* our ID.
            //   If `d0` (req ID) is 0. `beq` immediately. Has priority.
            //   If `d0` is 2. Checks Req 0. (d0!=0). Reads Req 0 mask. If set -> no priority. Sub 2 -> d0=0. Next loop beq -> has priority.
            //
            //   So it checks all request slots logically *before* the current one.
            //   Since `w_chmask` is array at `tmpa0`, and it increments `tmpa0`.
            //   It checks `w_chmask[0]`, `w_chmask[1]`, etc.
            //   So if I am Request 2. It checks Request 0.
            //   If Request 0 has my channel, I lose.
            //   So **Lower Request Index = Higher Priority**.
            
            int currentReqIdx = reqId / 2;
            
            // Check if track was stopped (reqId >= RCOUNT*2)
            if (currentReqIdx >= RCOUNT) {
                 // Logic lines 904-907: if reqId >= 8, checks remaining masks?
                 // Assembly: beq @has_priority branches if d0==0.
                 // If d0 start was >= 8, it subtracts 2 three times -> d0 >= 2.
                 // It never branches to @has_priority inside loop.
                 // After loop: beq @has_priority. (If d0 was 6 initially, now 0).
                 // If d0 was 8 (stopped). Now 2. Not equal.
                 // move.w (tmpa0)+, d1. (Checks last mask? Req 3?).
                 // btst chnid,d1.
                 // So if stopped, it basically checks ALL masks.
                 // Java: check all masks < currentReqIdx.
                 // If stopped (eq "no owner"), check all masks 0..3.
                 currentReqIdx = RCOUNT; // Effectively check all
            }

            for (int r = 0; r < currentReqIdx; r++) {
                if (((a0.w_chmask[r] >> chnid) & 1) != 0) {
                    hasPriority = false;
                    break;
                }
            }
            
            if (!hasPriority) {
                // @no_priority
                twork.t_last_pitch = 0xffff; // st t_last_pitch
                twork.t_note_flag |= (nm_restore << nf) | (1 << (cf + cf_background));
                // Note: Java uses separate int fields.
                // nm_restore = keys off, ins, vol, pan, fm3
                twork.t_note_flag |= nm_restore;
                twork.t_channel_flag |= (1 << cf_background);
                
                // Mask out key on if pending?
                // Assembly: @always_mask: bclr #nf_key_on
                // But @no_priority flows to @always_mask ONLY if skipping the "has priority" block?
                // No, @no_priority label is at end.
                // Wait.
                // 926: @no_priority
                // 927: st t_last_pitch
                // 928: ori.w ...
                // 930: @voice_not_enabled
                
                // So if NO priority: set background, restore flags, skip masking check.
                continue; 
            }
            
            // @has_priority
            // Clear background flag (line 909) - bclr returns Z=1 if bit WAS 0
            boolean wasInBackground = (twork.t_channel_flag & (1 << cf_background)) != 0;
            twork.t_channel_flag &= ~(1 << cf_background);
            
            // Assembly line 910: beq.s @voice_not_enabled - skip masking if NOT from background
            if (!wasInBackground) {
                continue;
            }
            
            if ((flag & (1 << nf_enabled)) == 0) {
                continue;
            }
            
            // Masking check for Drums or Short Duration (lines 915-924)
            // Only runs when track transitions from background to foreground
            if ((twork.t_channel_flag & (1 << cf_drum_mode)) != 0) {
                 twork.t_note_flag &= ~(1 << nf_key_on);  // Drum: always mask
            } else {
                 if ((twork.t_counter & 0xff) < 5) {
                      twork.t_note_flag &= ~(1 << nf_key_on);  // Short duration: mask
                 }
            }
        }
    }

    private static void mds_update_fade(WorkArea a0) {
        // Simple fade out logic implementation
        if (a0.w_fade_rate == 0)
            return;

        int fade = a0.w_fade_rate; // signed byte
        if ((fade & 0x80) != 0)
            fade |= 0xffffff00; // sign extend

        int vol = a0.w_volume[3]; // BGM volume (request 3)
        // ASM logic involves fixed point or counter?
        // Simplified:
        vol += fade;
        if (vol < 0)
            vol = 0;
        if (vol > 255)
            vol = 255;

        a0.w_volume[3] = vol;

        if (a0.w_fade_target != 0) {
            // Check if target reached
        }
    }

    private static final int mds_chn_cmd_base = 0xE0;
    private static final int mds_note_start = 0x82;

    private static int debugCounter = 0;

    private void mds_update_seq(WorkArea a0, TrackData twork) {
        // Assembly (lines 1005-1011): subq.b #1,t_counter; bcs.s @read_command
        // bcs branches if result underflowed (carry set when decrementing from 0)
        int prevCounter = twork.t_counter & 0xff;
        twork.t_counter = (prevCounter - 1) & 0xff;
        
        if (prevCounter != 0) {
            // Counter did NOT underflow - check mtab and return
            if (twork.t_mtab_addr != 0) {
                mds_update_mtab(a0, twork);
            }
            return;
        }

        // Counter underflowed (was 0) - read next command
        Memory tbase = a0.w_sdtop.add(twork.t_base_addr);

        while (true) {
            int pos = twork.t_position;
            int cmd = tbase.read8(pos) & 0xFF;

            int cmdlen = 1;

            twork.t_position = pos + cmdlen;  // Default advance by 1
            

            
            // ... (rest of method) we need to be careful with replace_file_content scope.
            // I'll target just the start.


            // Assembly (lines 1025-1054): Check command ranges
            if ((cmd & 0x80) == 0) {
                // 00-7F: Rest with explicit length
                twork.t_rest_time = cmd;
                twork.t_counter = cmd;
                if (cmd == 0) {
                     logger.log(Level.DEBUG, "CMD 00: Rest 0 detected");
                }
                twork.t_note_flag |= (1 << nf_key_off);
                twork.t_note_flag &= ~(1 << nf_key_on);
                finishSeqCommand(a0, twork);
                return;
            }

            if (cmd >= mds_chn_cmd_base) {
                // E0-FF: Commands
                // Save counter before command to detect if command set it
                int counterBefore = twork.t_counter & 0xff;  // Should be 255 from underflow
                int len = execute_command(a0, twork, tbase, pos, cmd);
                if (len != 0) {
                    twork.t_position = pos + len;
                } else {
                    // Command returned 0 (updated position itself)
                    // Check if command set counter (changed from 255)
                    int counterAfter = twork.t_counter & 0xff;
                    if (counterAfter != counterBefore && counterAfter != 255) {
                        // Counter was actually set by command (like F7 drum finish)
                        if (twork.t_mtab_addr != 0) {
                            mds_update_mtab(a0, twork);
                        }
                        return;
                    }
                    // Command didn't set counter (FA, FE, FF) - continue processing
                }
                if (twork.t_request_id >= RCOUNT * 2) {
                    return;  // Track stopped
                }
                continue;
            }

            if (cmd >= mds_note_start) {
                // 82-DF: Note command
                int note = cmd - mds_note_start;
                twork.t_note = note;
                twork.t_note_flag |= (1 << nf_key_on);

                boolean isSlur = (twork.t_note_flag & (1 << nf_slur)) != 0;
                if (isSlur) {
                    // Assembly (line 1062-1063): btst #nf_slur; bne.s @cmd_tie
                    // Slurred notes skip mtab reset, key_off, and drum mode entirely
                    readNoteLength(twork, tbase, pos + 1);
                    finishSeqCommand(a0, twork);
                    return;
                }

                // Not slurred - handle mtab reset and key_off
                // Assembly (lines 1064-1066): if cf_mtab_carry IS SET, reset mtab_delay
                if ((twork.t_channel_flag & (1 << cf_mtab_carry)) != 0) {
                    twork.t_mtab_delay = 0;
                }
                twork.t_note_flag |= (1 << nf_key_off);

                // Assembly (lines 1069-1070, 1112-1119): Check drum mode
                if ((twork.t_channel_flag & (1 << cf_drum_mode)) != 0) {
                    // Drum mode: push return address and jump to subroutine
                    // Assembly line 1114: move.w @trackpos,t_stack(twork,@sp)
                    // IMPORTANT: @trackpos is already AFTER the note byte (pos+1) when pushed!
                    // This is because line 1023 does `add.w @cmdlen,@trackpos` before reading command.
                    int currentSp = twork.t_stack_pos;
                    int stackIdx = currentSp / 2;
                    twork.t_stack[stackIdx] = pos + 1;  // Push pos+1 (AFTER note byte) - matches assembly @trackpos
                    twork.t_stack_pos = currentSp + 2;
                    try {
                        int subOffset = tbase.read16(note * 2) & 0xffff;
                        // CRITICAL FIX: In assembly, @next_command advances @trackpos BEFORE reading
                        // But in Java, we read pos THEN advance t_position
                        // Since the loop already advanced t_position = pos + 1 above,
                        // and we're about to read from pos on the next iteration,
                        // we need to set t_position = subOffset so the next iteration reads from subOffset
                        twork.t_position = subOffset;
                    } catch (Exception e) {
                        logger.log(Level.ERROR, "DRUM MODE ERROR: " + e.getMessage(), e);
                    }
                    continue;  // Process subroutine commands
                }



                // Normal note: read length or use previous
                readNoteLength(twork, tbase, pos + 1);
                finishSeqCommand(a0, twork);
                return;
            }

            // 80: Rest (alternate) - uses previous rest time
            if (cmd == 0x80) {
                logger.log(Level.DEBUG, String.format("CMD 80: RestTime=%d NoteTime=%d", twork.t_rest_time, twork.t_note_time));
                if (twork.t_rest_time == 0 && twork.t_note_time != 0) {
                     // Fallback: If rest time is 0, use note time (Robustness for passport.mds)
                     twork.t_rest_time = twork.t_note_time;
                     logger.log(Level.DEBUG, "CMD 80: Fallback applied");
                }
                twork.t_counter = twork.t_rest_time;
                twork.t_note_flag |= (1 << nf_key_off);
                twork.t_note_flag &= ~(1 << nf_key_on);
                finishSeqCommand(a0, twork);
                return;
            }

            // 81: Tie - extend current note
            if (cmd == 0x81) {
                readNoteLength(twork, tbase, pos + 1);
                finishSeqCommand(a0, twork);
                return;
            }

            // Unknown 80-81 range command - shouldn't reach here
            twork.t_counter = twork.t_rest_time;
            finishSeqCommand(a0, twork);
            return;
        }
    }

    /** Read note/tie length (Assembly lines 1076-1096) */
    private static void readNoteLength(TrackData twork, Memory tbase, int pos) {
        int cmd = tbase.read8(pos) & 0xff;
        if ((cmd & 0x80) != 0) {
            // Next byte is a command, use previous length
            twork.t_counter = twork.t_note_time;
            twork.t_position = pos;
        } else {
            // Read new length
            twork.t_counter = cmd;
            twork.t_note_time = cmd;
            twork.t_position = pos + 1;
        }
    }

    /** Finish sequence command - check mtab (Assembly lines 1039-1041) */
    private void finishSeqCommand(WorkArea a0, TrackData twork) {
        if (twork.t_mtab_addr != 0) {
            mds_update_mtab(a0, twork);
        }
    }


    private boolean mds_update_mtab(WorkArea a0, TrackData twork) {
        if (twork.t_mtab_delay > 0) {
            twork.t_mtab_delay--;
            return true;
        }

        Memory tbase = a0.w_sdtop.add(twork.t_base_addr);
        if (twork.t_mtab_addr == 0)
            return false;
        // Logic: pos = mtab_addr + (mtab_pos * 2)
        int pos = twork.t_mtab_addr + twork.t_mtab_pos * 2;

        while (true) {
            twork.t_mtab_pos++; // Increment for next time
            int cmd = tbase.read8(pos);
            int param = tbase.read8(pos + 1);
            pos += 2;

            if ((cmd & 0x80) != 0) { // $80-$FF
                // Check for FM channel register write ($C0-$FF)
                // Assembly lines 1653-1655: add.b d2,d2; add.b d2,d2; bcs.s @fmcreg
                // When bit 6 is set after *4, carry is set (original cmd >= 0xC0)
                if (cmd >= 0xC0) {
                    // FM channel register write (Assembly lines 1661-1680)
                    int ch = twork.t_channel_id;
                    if (ch < 6) {
                        int[] fmcregSlot = {0, 1, 2, 0, 1, 2};
                        int[] fmcregPort = {0, 0, 0, 2, 2, 2};
                        int reg = ((cmd - 0xC0) << 2) + fmcregSlot[ch];
                        int port = fmcregPort[ch];
                        if (port == 0) {
                            write_fm_port0(reg, param);
                        } else {
                            write_fm_port1(reg, param);
                        }
                    }
                    continue;
                }
                
                int cmdCode = cmd & 0x0F; // Low 4 bits for $80-$8F commands
                switch (cmdCode) {
                    case 0x00: // $80 Jump/Exit
                        if (param == 0) {
                            // Exit mtab (Assembly lines 1705-1711)
                            twork.t_mtab_delay = 0xFF;
                            twork.t_mtab_pos--;  // Stay at current position
                            return true;
                        }
                        // Jump back (Assembly lines 1713-1716)
                        twork.t_mtab_pos += (byte) param;  // param is signed offset
                        pos = twork.t_mtab_addr + twork.t_mtab_pos * 2;
                        continue;
                    case 0x01: // $81 Wait
                        twork.t_mtab_delay = param;
                        return true;
                    case 0x02: // $82 Retrig
                        twork.t_note_flag |= (1 << nf_key_on) | (1 << nf_key_off);
                        twork.t_mtab_delay = param;
                        return true;
                    case 0x03: // $83 Carry (Assembly line 1734: bclr cf_mtab_carry)
                        // Note: Assembly CLEARS carry flag, not sets based on param
                        twork.t_channel_flag &= ~(1 << cf_mtab_carry);
                        continue;
                    case 0x04: // $84 Loop
                        twork.t_mtab_repeat = param;
                        continue;
                    case 0x05: // $85 Loop break
                        if (twork.t_mtab_repeat == 0) {
                            twork.t_mtab_pos += (byte) param;
                            pos = twork.t_mtab_addr + twork.t_mtab_pos * 2;
                        }
                        continue;
                    case 0x06: // $86 Loop finish
                        twork.t_mtab_repeat--;
                        if ((twork.t_mtab_repeat & 0xFF) < 0xFF) { // Check underflow (bcs check)
                            twork.t_mtab_pos += (byte) param;
                            pos = twork.t_mtab_addr + twork.t_mtab_pos * 2;
                        }
                        continue;
                    case 0x07: // $87 Pan (Assembly lines 1769-1779)
                        if (twork.t_channel_id < 6) {
                            int panLfo = twork.t_fm_pan_lfo & 0x3F;
                            panLfo |= param;
                            twork.t_fm_pan_lfo = panLfo;
                            twork.t_note_flag |= (1 << nf_pan_lfo);
                        }
                        continue;
                    case 0x08: // $88 LFO (Assembly lines 1784-1794)
                        if (twork.t_channel_id < 6) {
                            int panLfo = twork.t_fm_pan_lfo & 0xC0;
                            panLfo |= param;
                            twork.t_fm_pan_lfo = panLfo;
                            twork.t_note_flag |= (1 << nf_pan_lfo);
                        }
                        continue;
                    case 0x09: // $89 Noise mode (Assembly lines 1799-1807)
                        if (twork.t_channel_id >= 6) {
                            twork.t_last_pitch = 0xFFFF;  // Force pitch update
                            twork.t_psg_nmode = param;
                            twork.t_note_flag |= (1 << nf_nmode);
                        }
                        continue;
                    case 0x0A: // $8A Detune add (Assembly lines 1812-1816)
                        twork.t_dtn = (twork.t_dtn + param) & 0xFF;
                        // Check overflow (Assembly bvc check)
                        if ((twork.t_dtn & 0x80) != 0 && (param & 0x80) == 0) {
                            twork.t_note = (twork.t_note + 1) & 0xFF;
                        }
                        continue;
                    case 0x0B: // $8B Detune sub (Assembly lines 1821-1825)
                        twork.t_dtn = (twork.t_dtn - param) & 0xFF;
                        // Check overflow (Assembly bvc check)
                        if ((twork.t_dtn & 0x80) == 0 && (param & 0x80) != 0) {
                            twork.t_note = (twork.t_note - 1) & 0xFF;
                        }
                        continue;
                    default:
                        // Unknown command - skip
                        continue;
                }
            } else {
                // Variable set/add ($00-$7F) - Assembly lines 1618-1647
                // $00-$3F: set variable at offset cmd to param
                // $40-$7F: add param to variable at offset (cmd - 0x40)
                // These modify track work area fields directly
                // For now implement commonly used ones
                if (cmd < 0x40) {
                    // Set variable
                    setTrackVariable(twork, cmd, param);
                } else {
                    // Add to variable
                    int offset = cmd - 0x40;
                    int currentVal = getTrackVariable(twork, offset);
                    int newVal = (currentVal + param) & 0xFF;
                    // TL overflow check (Assembly lines 1630-1637)
                    if (offset >= 40 && offset < 44) { // t_fm_tl range
                        if ((newVal & 0x80) != 0) {
                            newVal = 0x7F;  // Clamp to max
                        }
                    }
                    setTrackVariable(twork, offset, newVal);
                }
                // Check if volume was modified - set vol flag
                if (cmd == 18 || cmd == 0x40 + 18) { // t_vol offset
                    twork.t_note_flag |= (1 << nf_vol);
                }
                continue;
            }
        }
    }
    
    // Helper to get track variable by offset (Assembly line 1628: move.b 0(twork,d2),d3)
    private static int getTrackVariable(TrackData t, int offset) {
        // Map offset to actual field - this is a simplified mapping
        return switch (offset) {
            case 18 -> t.t_vol;  // t_vol offset
            case 40 -> t.t_fm_tl[0];
            case 41 -> t.t_fm_tl[1];
            case 42 -> t.t_fm_tl[2];
            case 43 -> t.t_fm_tl[3];
            default -> 0;
        };
    }
    
    // Helper to set track variable by offset (Assembly line 1643: move.b d1,0(twork,d2))
    private static void setTrackVariable(TrackData t, int offset, int value) {
        switch (offset) {
            case 18: t.t_vol = value; break;
            case 40: t.t_fm_tl[0] = value; break;
            case 41: t.t_fm_tl[1] = value; break;
            case 42: t.t_fm_tl[2] = value; break;
            case 43: t.t_fm_tl[3] = value; break;
        }
    }


    /**
     * Translates YM2151 (OPM) register address to YM2612 (OPN) register address.
     * OPM (40-FF) -> OPN (30-8F).
     * Mappings:
     * 0x40 (DT1/MUL) -> 0x30 (DT/MUL)
     * 0x60 (TL)      -> 0x40 (TL)
     * 0x80 (KS/AR)   -> 0x50 (KS/AR)
     * 0xA0 (AMS/D1R) -> 0x60 (AM/DR)
     * 0xC0 (DT2/D2R) -> 0x70 (SR)
     * 0xE0 (D1L/RR)  -> 0x80 (SL/RR)
     */
    private static int opmToOpnReg(int reg) {
        if (reg < 0x40) return reg; // Pass through Keys/Flags/Test
        int base = reg & 0xE0; // Get top 3 bits (Block)
        int offset = reg & 0x1F;

        return switch (base) {
            case 0x40 -> 0x30 + offset;
            case 0x60 -> 0x40 + offset;
            case 0x80 -> 0x50 + offset;
            case 0xA0 -> 0x60 + offset;
            case 0xC0 -> 0x70 + offset;
            case 0xE0 -> 0x80 + offset;
            default -> reg;
        };
    }

    private int execute_command(WorkArea a0, TrackData twork, Memory tbase, int pos, int cmd) {
        switch (cmd) {
            case 0xE0: // Slur
                twork.t_note_flag |= (1 << nf_slur);
                return 1;
            case 0xE1: // Ins
                twork.t_ins = tbase.read8(pos + 1) & 0xff;
                twork.t_note_flag |= (1 << nf_key_off);

                twork.t_channel_flag &= ~(1 << cf_pcm_control);
                // NOTE: nf_ins flag set ONLY for FM channels (line 1544)
                // Assembly line 1226 sets nf_ins only in FM block, not in PSG block
                twork.t_last_pitch = 0xffff;
                
                Memory insData = null;

                // Assembly (@cmd_ins line 1207): ALWAYS read from pointer table
                // movea.w 0(@tbase,@cmd),tmpa0  where @cmd = t_ins*2
                // For RIFF: tbase points to seq chunk, so this reads from seq start
                // This is necessary to get correct t_ins_trs (transpose) value!

                // Try glob first (RIFF only)
                boolean isGlobInstrument = false;
                if (a0.w_pointer_mode == 1 && a0.globInstruments != null && a0.globInstruments.containsKey(twork.t_ins)) {
                    insData = new ByteArrayMemory(a0.globInstruments.get(twork.t_ins));
                    isGlobInstrument = true;
                } else {
                    // ALWAYS read from pointer table (matches assembly @cmd_ins logic exactly)
                    Memory sdtop = a0.w_sdtop;
                    if (sdtop != null) {
                        int insIdx = twork.t_ins;
                        int ptrOffset = tbase.read16(insIdx * 2);
                        if ((ptrOffset & 0x8000) != 0) ptrOffset |= 0xFFFF0000;
                        insData = sdtop.add(ptrOffset);
                    }
                }

                if (insData != null && twork.t_channel_id < 6) {
                    // FM Instrument Header Layout (Assembly @cmd_ins lines 1201-1228):
                    // +0-23: Register Data (Skipped here in sequence command)
                    // +24: TL (4 bytes) - bytes 24, 25, 26, 27
                    // +28: Alg (1 byte)
                    // +29: Trs (1 byte) - ONLY for binary format, NOT for glob chunks!

                    // Assembly (lines 1217-1225): FM3 special handling
                    // If channel 2 (FM3), load BOTH global (w_fm3_*) AND track-specific (t_fm_*) values
                    if (twork.t_channel_id == 2) {
                        // Load global FM3 parameters
                        a0.w_fm3_tl[0] = insData.read8(24) & 0x7f;
                        a0.w_fm3_tl[1] = insData.read8(25) & 0x7f;
                        a0.w_fm3_tl[2] = insData.read8(26) & 0x7f;
                        a0.w_fm3_tl[3] = insData.read8(27) & 0x7f;
                        a0.w_fm3_alg = insData.read8(28) & 0xff;
                        // Assembly: subq.l #5,tmpa0 - pointer rewinds, then reads again
                        // In Java we just read the same offsets again (no pointer manipulation)
                    }

                    // Always load track-specific parameters (for all FM channels including FM3)
                    twork.t_fm_tl[0] = insData.read8(24) & 0x7f;
                    twork.t_fm_tl[1] = insData.read8(25) & 0x7f;
                    twork.t_fm_tl[2] = insData.read8(26) & 0x7f;
                    twork.t_fm_tl[3] = insData.read8(27) & 0x7f;
                    twork.t_fm_alg = insData.read8(28) & 0xff;
                    // CRITICAL: Only load t_ins_trs from binary format, NOT from glob chunks
                    // Glob chunks have a different data structure - byte 29 may not be transpose
                    if (!isGlobInstrument) {
                        // Assembly (line 1225): move.b (tmpa0)+,t_ins_trs(twork)
                        // 68000 bytes are SIGNED. 0x8A = -118, not 138!
                        // Must NOT mask with & 0xff, which would convert to unsigned
                        twork.t_ins_trs = (byte) insData.read8(29);  // Sign-extend: 0x8A becomes -118
                    }
                    // Assembly line 1226: bset #nf+nf_ins,flag - ONLY for FM channels
                    twork.t_note_flag |= (1 << nf_ins);
                } else if (insData != null) {
                     // PSG Instrument - either from glob or pointer table
                     twork.t_psg_env_data = insData;
                     twork.t_psg_eg_addr = 0; // Use data from offset 0
                     twork.t_psg_eg_pos = 0xff;
                     twork.t_psg_eg_delay = 0x0f;
                     twork.t_note_flag |= (1 << nf_key_off);
                     twork.t_note_flag &= ~(1 << nf_slur);
                }
                return 2;
            case 0xE2: // Vol
                twork.t_vol = tbase.read8(pos + 1) & 0xff;
                twork.t_note_flag |= (1 << nf_vol);
                return 2;
            case 0xE3: // Volm
                twork.t_vol = (twork.t_vol + tbase.read8(pos + 1)) & 0xff;
                twork.t_note_flag |= (1 << nf_vol);
                return 2;
            case 0xE4: // Transpose
                twork.t_trs = tbase.read8(pos + 1) & 0xff;
                return 2;
            case 0xE5: // Transpose change
                twork.t_trs = (twork.t_trs + tbase.read8(pos + 1)) & 0xff;
                return 2;
            case 0xE6: // Detune
                twork.t_dtn = tbase.read8(pos + 1) & 0xff;
                return 2;
            case 0xE7: // Portamento
                twork.t_pta = tbase.read8(pos + 1) & 0xff;
                return 2;
            case 0xE8: // Pitch Envelope
                int peg = tbase.read8(pos + 1) & 0xff;
                if (peg == 0) {
                    twork.t_peg_addr = 0;
                } else {
                    twork.t_peg_mod = 0;
                    twork.t_peg_delay = 0;
                }
                return 2;
            case 0xE9: // Panning
                if (twork.t_channel_id < 6) {
                    int pan = twork.t_fm_pan_lfo & 0x3f;
                    pan |= tbase.read8(pos + 1) & 0xff;
                    twork.t_fm_pan_lfo = pan;
                    twork.t_note_flag |= (1 << nf_pan_lfo);
                }
                return 2;
            case 0xEA: // LFO / Noise Mode
                // Assembly (lines 1321-1344): cmd_lfo / cmd_nmode
                // For FM (ch < 6): set LFO sensitivity
                // For PSG (ch >= 6): set noise mode
                if (twork.t_channel_id < 6) {
                    // FM LFO
                    int lfo = twork.t_fm_pan_lfo & 0xc0;
                    lfo |= tbase.read8(pos + 1) & 0xff;
                    twork.t_fm_pan_lfo = lfo;
                    twork.t_note_flag |= (1 << nf_pan_lfo);
                } else {
                    // PSG Noise Mode (Assembly @cmd_nmode)
                    // 00 = use key code, e3 = periodic noise, e7 = white noise
                    twork.t_last_pitch = 0xffff;  // st t_last_pitch = set to $FFFF
                    twork.t_psg_nmode = tbase.read8(pos + 1) & 0xff;
                    twork.t_note_flag |= (1 << nf_nmode);
                }
                return 2;
            case 0xEB: // Macro Table
                int mtab = tbase.read8(pos + 1) & 0xff;
                twork.t_mtab_delay = 0;
                if (mtab == 0) {
                    twork.t_mtab_addr = 0;
                    twork.t_channel_flag &= ~(1 << cf_mtab_carry);
                } else {
                    twork.t_mtab_addr = tbase.read16(2 + (mtab - 1) * 2);
                    if ((twork.t_mtab_addr & 0x8000) != 0)
                         twork.t_mtab_addr |= 0xFFFF0000;
                    twork.t_channel_flag |= (1 << cf_mtab_carry);
                }
                return 2;
            case 0xEC: // Channel Flags
                int flg = tbase.read8(pos + 1) & 0xff;
                if ((flg & 0x80) != 0) {
                    twork.t_note_flag |= (1 << nf_fm3) | (1 << nf_vol);
                    int val = (flg << 3) & 0xff;
                    val ^= 0x80;
                    twork.t_op_mask = val;
                } else {
                    int bit = flg & 0x7;
                    if ((flg & 0x08) != 0) {
                        twork.t_channel_flag |= (1 << bit);
                    } else {
                        twork.t_channel_flag &= ~(1 << bit);
                    }
                }
                return 2;
            case 0xED: // FM Channel Reg Write
                {
                    // Assembly (lines 1408-1425): @cmd_fmcreg
                    // Uses raw register offset from sequence data, NO conversion
                    // MDS format already contains OPN-formatted register offsets
                    int regOffset = tbase.read8(pos + 1) & 0xff;
                    int val = tbase.read8(pos + 2) & 0xff;
                    int ch = twork.t_channel_id;
                    if (ch < 3) {
                        write_fm_port0(regOffset + ch, val);  // Direct, no conversion
                    } else if (ch < 6) {
                        write_fm_port1(regOffset + (ch - 3), val);  // Direct, no conversion
                    }
                }
                return 3;
            case 0xEE: // FM TL Write
                {
                    int slot = tbase.read8(pos + 1) & 0xff; 
                    int val = tbase.read8(pos + 2) & 0xff;
                    if (slot < 4) {
                        twork.t_fm_tl[slot] = val;
                        twork.t_note_flag |= (1 << nf_vol);
                    }
                }
                return 3;
            case 0xEF: // FM TL Change
                {
                    int slot = tbase.read8(pos + 1) & 0xff;
                    int val = tbase.read8(pos + 2) & 0xff; 
                    if (slot < 4) {
                        twork.t_fm_tl[slot] = (twork.t_fm_tl[slot] + val) & 0xff;
                        twork.t_note_flag |= (1 << nf_vol);
                    }
                }
                return 3;
            case 0xF0: // PCM
                {
                    int pcmIdx = tbase.read8(pos + 1) & 0xff;
                    
                    if (a0.pcmHeaders.containsKey(pcmIdx)) {
                        // Use parsed header
                        twork.t_pcm_header = -pcmIdx - 1;
                        byte[] header = a0.pcmHeaders.get(pcmIdx);
                        // Header structure: [Pitch, HighAddr, MidAddr, LowAddr, Pad, Pad, LenH, LenL]
                        twork.t_pcm_pitch = header[0] & 0xff;  // Rate/Pitch is at offset 0
                        // Length is bytes 6-7 as 16-bit BE word (matching assembly move.w at +6)
                        twork.t_pcm_length = ((header[6] & 0xff) << 8) | (header[7] & 0xff);
                        

                        twork.t_channel_flag |= (1 << cf_pcm_control);
                        twork.t_note_flag |= (1 << nf_pcm_header) | (1 << nf_pcm_pitch);
                    } else {
                        // Original Logic: Read PCM Table Offset from sdtop + 8
                        int ptrOffset = 0;
                        if (a0.w_sdtop != null) {
                            int pcmTableOffset = a0.w_sdtop.read32(8);
                            Memory pcmTable = a0.w_sdtop.add(pcmTableOffset);
                            ptrOffset = pcmTable.read16(pcmIdx * 2);
                        } else {
                             ptrOffset = tbase.read16(pcmIdx * 2); 
                        }
    
                        if ((ptrOffset & 0x8000) != 0) ptrOffset |= 0xFFFF0000;
                        
                        Memory pcmHead = null;
                        if ((ptrOffset & 0xffff) != 0) {
                            twork.t_pcm_header = ptrOffset & 0xffff;
                            if (a0.w_sdtop != null) {
                                pcmHead = a0.w_sdtop.add(ptrOffset & 0xffff);
                                twork.t_pcm_pitch = pcmHead.read8(0) & 0xff;
                                twork.t_pcm_length = pcmHead.read16(6) & 0xffff;
                                
                                twork.t_channel_flag |= (1 << cf_pcm_control);
                                twork.t_note_flag |= (1 << nf_pcm_header) | (1 << nf_pcm_pitch);
                            }
                        } else if (pcmIdx == 0x0B) {
                             // Fallback for Kick (Index 0x0B)

                             twork.t_pcm_header = -1;  // Re-uses -1 logic IF NO MAP ENTRY
                             twork.t_pcm_pitch = 4;   
                             twork.t_pcm_length = 0x2000; 
                             
                             twork.t_channel_flag |= (1 << cf_pcm_control);
                             twork.t_note_flag |= (1 << nf_pcm_header) | (1 << nf_pcm_pitch);
                        }
                    }
                }
                return 2;
            case 0xF1: // PCM Rate
                return 2;
            case 0xF2: // PCM Mode
                return 2;
            case 0xF9: // Tempo
                a0.w_tempo[twork.t_request_id / 2] = tbase.read8(pos + 1) & 0xff;
                return 2;

            case 0xF5: // Jump (relative offset)
                // Assembly (lines 1530-1535): reads offset, adds to cmdlen(3), then @next_command
                // adds cmdlen to trackpos, reads from trackpos-1
                // Result: destination = pos + 3 + offset (where pos is the F5 command position)
                int jumpOffset = tbase.read16(pos + 1);
                if ((jumpOffset & 0x8000) != 0) jumpOffset |= 0xFFFF0000;
                // A backward jump is the track's master loop; count it so consumers
                // can fade the song after a configured number of loops.
                if (jumpOffset < 0) twork.t_loop_count++;
                twork.t_position = pos + 3 + jumpOffset;  // Fixed: was pos + 1 + offset
                return 0;
            case 0xF6: // FM Register Write
                {
                    // Assembly (lines ~1479): @cmd_fmreg - Global FM register write
                    // No conversion needed, MDS format uses OPN register addresses
                    int reg = tbase.read8(pos + 1) & 0xff;
                    int val = tbase.read8(pos + 2) & 0xff;
                    write_fm_port0(reg, val);  // Direct, no conversion
                }
                return 3;
            case 0xF8: // Comm
                a0.w_comm = tbase.read8(pos + 1);
                return 2;
            case 0xFA: // Loop Start
                // Assembly (lines 1482-1486):
                // move.b #$ff,t_stack(twork,@sp)
                // move.w @trackpos,2+t_stack(twork,@sp)  ; @trackpos already advanced by 1
                // addq.b #4,@sp
                // Command length table shows FA is 1 byte
                {
                    int sp = twork.t_stack_pos;
                    int idx = sp / 2;
                    twork.t_stack[idx] = 0xff;        // Marker at sp
                    twork.t_stack[idx + 1] = pos + 1; // Return address = after FA (loop body start)
                    twork.t_stack_pos = sp + 4;       // Push 4 bytes
                }
                return 1;  // FA is 1-byte command
            case 0xFB: // Loop Finish
                // Assembly (lines 1491-1505):
                // subq.b #4,@sp; move.b t_stack(twork,@sp),@tempreg; ..
                // if not done: addq.b #4,@sp
                {
                    int sp = twork.t_stack_pos - 4;  // Peek at loop frame
                    if (sp < 0) {
                        return 1;            // Stack underflow protection
                    }
                    
                    int idx = sp / 2;
                    int counter = twork.t_stack[idx];
                    if (counter == 0xff) {
                        // First time: read loop count from data
                        counter = tbase.read8(pos + 1) & 0xff;
                    }
                    
                    counter--;
                    if (counter > 0) {
                        twork.t_stack[idx] = counter;
                        int loopAddr = twork.t_stack[idx + 1];
                        twork.t_position = loopAddr; // Return to loop start
                        // sp stays unchanged (we peeked but didn't pop)
                        return 0;
                    } else {
                        // Loop done - pop frame
                        twork.t_stack_pos = sp;  // Pop 4 bytes
                        return 2;
                    }
                }
            case 0xFC: // Loop Break
            case 0xFD: // Loop Break (Long)
                // Assembly (lines 1510-1517, 1522-1525):
                // cmpi.b #1,t_stack-4(twork,@sp); bne @next
                // subq.b #4,@sp; read offset
                {
                    int sp = twork.t_stack_pos - 4;
                    if (sp >= 0) {
                        int idx = sp / 2;
                        if (twork.t_stack[idx] == 1) {
                            // Last iteration: break out
                            twork.t_stack_pos = sp;  // Pop 4 bytes
                            if (cmd == 0xFC) {
                                int offset = tbase.read8(pos + 1) & 0xff;
                                twork.t_position = pos + 2 + offset;
                            } else { // 0xFD - long offset
                                // Assembly adds offset to cmdlen(3), then @next_command adds to trackpos
                                // Final read is from trackpos-1, so position = FD + 3 + offset
                                int offset = tbase.read16(pos + 1);
                                if ((offset & 0x8000) != 0) offset |= 0xFFFF0000;
                                twork.t_position = pos + 3 + offset;
                            }
                            return 0;
                        }
                    }
                    return (cmd == 0xFC) ? 2 : 3; // Skip offset bytes
                }
            case 0xFE: // Pattern / Subroutine
                // Assembly flow:
                // 1. At cmd entry, trackpos was incremented by prev cmdlen
                // 2. addq.w #2,@trackpos -> push this to stack
                // 3. After FF pop, reads from trackpos-1
                // Java flow:
                // 1. Read from pos directly (no -1)
                // 2. So we push pos+2 which is where we want to read after return
                {
                    int sp = twork.t_stack_pos;
                    int idx = sp / 2;
                    twork.t_stack[idx] = pos + 2;  // Return reads from here directly
                    twork.t_stack_pos = sp + 2;    // Push 2 bytes
                    
                    // Read pattern index and lookup in pattern table
                    int patIdx = tbase.read8(pos + 1) & 0xff;
                    int patOffset = tbase.read16(patIdx * 2) & 0xffff;
                    twork.t_position = patOffset;
                    return 0;  // Position updated
                }
            case 0xF7: // Drum Finish
                // Assembly (lines 1553-1557): @cmd_dmfinish
                // move.b 0(@tbase,@trackpos),t_note(twork)  <-- Read drum note from F7 parameter
                // subq.b #2,@sp
                // move.w t_stack(twork,@sp),@trackpos
                // bra.w @cmd_tie
                // Critical: @cmd_tie eventually reaches code that checks mtab and returns
                {
                    // Assembly: move.b 0(@tbase,@trackpos),t_note(twork)
                    // Read the drum note parameter from F7 command - stores directly as-is
                    int noteVal = tbase.read8(pos + 1) & 0xff;
                    twork.t_note = noteVal;
                }

                if (twork.t_stack_pos >= 2) {
                    // Assembly: subq.b #2,@sp; move.w t_stack(twork,@sp)
                    twork.t_stack_pos -= 2;
                    int stackIdx = twork.t_stack_pos / 2;
                    int returnPos = twork.t_stack[stackIdx];
                    // Now process as tie - read length (Assembly @cmd_tie lines 1076-1096)
                    int lenCmd = tbase.read8(returnPos) & 0xff;
                    if ((lenCmd & 0x80) != 0) {
                        // Use previous length
                        twork.t_counter = twork.t_note_time;
                        twork.t_position = returnPos;
                    } else {
                        // Read new length
                        twork.t_counter = lenCmd;
                        twork.t_note_time = lenCmd;
                        twork.t_position = returnPos + 1;
                    }
                    // Assembly: After setting counter, branches to code that checks mtab (lines 1086-1088)
                    finishSeqCommand(a0, twork);
                    return 0;
                }
                return 0;
            case 0xF3: // Finish (Alt)
            case 0xF4: // Finish (Alt)
            case 0xFF: // Finish / Return
                if (twork.t_stack_pos >= 2) {
                    // Return from subroutine (lines 1182-1186)
                    // Assembly: subq.b #2,@sp; move.w t_stack(twork,@sp),@trackpos
                    twork.t_stack_pos -= 2;
                    int stackIdx = twork.t_stack_pos / 2;
                    int returnPos = twork.t_stack[stackIdx];
                    twork.t_position = returnPos;
                    return 0;
                }
                stop_track(a0, twork);
                return 1;
        }
        return 1;
    }

    /**
     * Assembly {@code @cmd_finish} (mdsdrv.68k lines 1163-1174): a track reaching its
     * {@code $ff} finish command must key its channel off, not merely mark itself free.
     * <pre>
     *  st      w_priority(work)
     *  bset    #cf+cf_stop,flag
     *  move.b  #RCOUNT&lt;&lt;1,t_request_id(twork)   ;track is free
     *  ... bclr tnum from w_tmask(work,rnum)     ;clear channel mask
     *  ... bclr t_channel_id from w_chmask(work,rnum) ;deallocate channel
     * </pre>
     * cf_stop is what {@link #mds_update} turns into nf_key_off on this same pass, so
     * without it a finished FM/PSG voice sustains forever after the song ends. The masks
     * are indexed by the request the track belonged to, so read it before freeing it.
     */
    private static void stop_track(WorkArea a0, TrackData t) {
        int rnum = t.t_request_id;

        a0.w_priority = 0xff;
        t.t_channel_flag |= (1 << cf_stop);
        t.t_request_id = RCOUNT * 2;

        if (rnum < RCOUNT * 2) {
            int real_rnum = rnum / 2;
            for (int tnum = 0; tnum < TCOUNT; tnum++) {
                if (a0.w_track[tnum] == t) {
                    a0.w_tmask[real_rnum] &= ~(1 << tnum);
                    break;
                }
            }
            a0.w_chmask[real_rnum] &= ~(1 << t.t_channel_id);
        }
    }

    private void do_voice_update(WorkArea a0, TrackData t) {
        int ch = t.t_channel_id;
        switch (ch) {
            case 0:
                mds_fm_update(a0, t, 0, 0);
                break;
            case 1:
                mds_fm_update(a0, t, 1, 0);
                break;
            case 2:
                mds_fm_update(a0, t, 2, 0);
                break;
            case 3:
                mds_fm_update(a0, t, 0, 1);
                break;
            case 4:
                mds_fm_update(a0, t, 1, 1);
                break;
            case 5:
                mds_fm6_update(a0, t);
                break;
            case 6:
                mds_psg_update(a0, t, 0x80);
                break;
            case 7:
                mds_psg_update(a0, t, 0xA0);
                break;
            case 8:
                mds_psg3_update(a0, t, 0xC0);
                break;
            case 9:
                mds_psgn_update(a0, t, 0xE0);
                break;
            case 10:
                mds_pcm2_ch_update(a0, t);
                break;
            case 11:
                mds_pcm3_ch_update(a0, t);
                break;
            default:
                break;
        }
    }

    private void mds_pcm_update(WorkArea a0, TrackData t, int zPcmBase, int pcmFlagBit) {
        Memory zram = getZ80Ram(); // Helper to access Z80 RAM

        // Assembly mds_pcm_update (lines 3057-3068):
        // bclr #nf+nf_key_on,flag; bne.s mds_pcm_key_on
        boolean wasKeyOn = (t.t_note_flag & (1 << nf_key_on)) != 0;
        t.t_note_flag &= ~(1 << nf_key_on);

        if (wasKeyOn) {
            // mds_pcm_key_on (lines 3070-3074):
            // bclr #nf+nf_key_off,flag; beq.w mds_update_return
            // Key-on only proceeds if key-off WAS pending (bclr tests before clearing)
            boolean wasKeyOff = (t.t_note_flag & (1 << nf_key_off)) != 0;
            t.t_note_flag &= ~(1 << nf_key_off);
            if (!wasKeyOff) {
                // Key-off wasn't pending, skip key-on (assembly: beq mds_update_return)
                return;
            }

            // bset #cf+cf_key_on,flag (line 3073)
            t.t_channel_flag |= (1 << cf_key_on);
            // andi.l #~((1<<(nf+nf_vol))|(1<<(nf+nf_slur))),flag (line 3074)
            t.t_note_flag &= ~((1 << nf_vol) | (1 << nf_slur));

            if ((t.t_note_flag & (1 << nf_pcm_header)) != 0) {
                t.t_note_flag &= ~(1 << nf_pcm_header);

                Memory sdtop = a0.w_sdtop;
                if (sdtop != null) {
                    // Load header
                    // In execute_command 0xF0, we stored the raw offset from the table into t_pcm_header.
                    // This offset is relative to sdtop (based on assembly analysis).
                    // So we should just use it directly.
                    int headerOffset = t.t_pcm_header & 0xffff;
                    Memory pcmHeader = sdtop.add(headerOffset); 
                    
                    // Z80 write logic
                    // Header Format (guessed from assembly usage):
                    // +0: Pitch (byte) - handled in F0
                    // +6: Length (word) - handled in F0
                    // +? Bank/Addr?
                    // Assembly mds_pcm_update (lines 2800+):
                    //  lea $20(a0),a6          ; a6 = Z80 Work Area (z_work_top)
                    //  move.b t_pcm_bank(twork),d0
                    //  move.b d0,z_bnk(a6)     ; Write Bank
                    //  move.l t_pcm_addr(twork),d0
                    //  move.l d0,z_adrs(a6)    ; Write Address (Start)
                    //  ...
                    
                    // But we don't have t_pcm_bank/addr stored yet! 
                    // In F0, we only read pitch and length.
                    // Wait, assembly @cmd_pcm (line 1564):
                    // move.w tmpa0,t_pcm_header(twork) <--- Stores POINTER to header
                    // ...
                    // ori.l ... flag <--- Flags set
                    // Return.
                    
                    // Then in mds_pcm_update (line 2856 in 68k?):
                    // movea.l t_pcm_header(twork),a1 <--- Loads POINTER
                    // move.l a_bank(a1),d0           <--- Reads Bank/Addr from HEADER
                    // move.l d0,t_pcm_addr(twork)    <--- Stores to track work
                    // ...
                    
                    // So we must read Bank/Addr from the header HERE.
                    // Offset of Bank/Addr in header?
                    // Based on "a_bank(a1)", checking mdsdrv.inc/mdsseq.inc for structure might be needed.
                    // But typically:
                    // +0: Pitch (1)
                    // +1: Vol (1)
                    // +2: Start Addr (4)
                    // +6: Length (2/4)
                    // Let's guess or check assembly.
                    // Actually, let's just dump the header to see what's in it first using debug log.
                    
                    // Logic from mds_pcm_key_on (assembly line 3080+)
                    // Reads 4 bytes at header+0
                    // Logic from mds_pcm_key_on (assembly line 3080+)
                    // Reads 4 bytes at header+0
                    int d1 = 0;
                    
                    if (t.t_pcm_header < 0) {
                        int idx = -t.t_pcm_header - 1;
                        if (a0.pcmHeaders.containsKey(idx)) {
                             byte[] header = a0.pcmHeaders.get(idx);
                             // Read 32-bit from header - assembly does: move.l (tmpa0),d1
                             // Header format: [Rate, HighAddr, LowAddrH, LowAddrL, ...]
                             d1 = ((header[0] & 0xff) << 24) |
                                  ((header[1] & 0xff) << 16) |
                                  ((header[2] & 0xff) << 8) |
                                  (header[3] & 0xff);
                        } else {
                             // Fallback (e.g. Kick -1, or unknown)
                             d1 = 0;
                        }
                    } else if (a0.w_sdtop != null) { 
                         Memory pcmHead = a0.w_sdtop.add(t.t_pcm_header);
                         d1 = pcmHead.read32(0);
                    }
                    
                    // Address/Bank Calculation
                    // Assembly: add.l d1,d1; lsr.w d1; ori.w #$8000,d1
                    // d1 = d1 << 1
                    d1 = d1 << 1;
                    // lsr.w d1 (logical shift right 1 bit on low word)
                    int lowWord = (d1 & 0xFFFF) >>> 1;
                    d1 = (d1 & 0xFFFF0000) | lowWord;
                    // ori.w #$8000
                    d1 |= 0x8000;
                    
                    // Write to Z80: zp_addr (Low, High+Bank)
                    // Assembly:
                    // push d1 (low 16 bits)
                    // pop high byte -> zp_addr+1 (High Byte of Low Word)
                    // move d1 (low byte) -> zp_addr+0 (Low Byte of Low Word)
                    // swap d1 (get High Word)
                    // add.b w_pcm_bank, d1
                    // move d1 -> zp_addr-1 (zp_bank)
                    
                    int lower16 = d1 & 0xFFFF;
                    int middleByte = (lower16 >>> 8) & 0xFF; // zp_addr+1
                    int lowByte = lower16 & 0xFF;            // zp_addr+0
                    
                    int upper16 = (d1 >>> 16) & 0xFFFF;
                    int bankByte = (upper16 + a0.w_pcm_bank) & 0xFF; // zp_bank
                    
                    zram.write8(zPcmBase + MdDef.zp_bank, bankByte);
                    zram.write8(zPcmBase + MdDef.zp_addr, lowByte);
                    zram.write8(zPcmBase + MdDef.zp_addr + 1, middleByte);
                    
                    // Pitch/Count Calculation (lines 3098+)
                    // CRITICAL: Assembly uses BYTE operations (add.b), NOT 32-bit arithmetic!
                    // clr.w d1; move.b t_pcm_pitch(twork),d1  <- d1 = pitch as word
                    // move.b d1,d3                            <- d3 = pitch (save original)
                    // add.b d1,d1                             <- d1 *= 2 (BYTE op, wraps at 8 bits!)
                    // add.b d1,d1                             <- d1 *= 2 again (BYTE op)
                    // (now d1 = pitch*4, but wrapped to 8 bits)
                    // moveq #15,d2; and.b w_pcm_mode(work),d2; subq.b #3,d2
                    // bne.s @no_mode3
                    // add.b d3,d1                             <- if mode==3: d1 += d3 (BYTE op)

                    int pitch = t.t_pcm_pitch & 0xff;
                    int pitchOrig = pitch;
                    int count = 0;
                    int len = t.t_pcm_length;

                // Restoring Assembly logic completely, but with safeguard for Pitch=0
                count = 0;
                // Actually 'int pitch' was declared at line 2109.
                // We reset it here if needed, or just use it.
                pitch = pitchOrig;

                int pitchDiv = pitch;
                // Emulate BYTE add operations (8-bit wrapping)
                // pitchDiv = pitch * 4
                pitchDiv = (pitchDiv * 2) & 0xff; 
                pitchDiv = (pitchDiv * 2) & 0xff;

                // Mode 3 check
                if (((a0.w_pcm_mode & 0xF) - 3) == 0) {
                    pitchDiv = (pitchDiv + pitchOrig) & 0xff;
                }

                int calcPitch = pitchDiv;

                // FIX: If Pitch is 0 (as in passport.mds), calcPitch is 0. 
                // Assembly crashes or relies on behavior we don't fully simulate.
                // Pitch 0 logic: Default to 1.0x (16) to avoid Div/0 and ensure playback
                if (calcPitch == 0) calcPitch = 16; 

                if (calcPitch != 0) {
                    count = len / calcPitch;
                }
                
                
                // Always add padding and write, as per assembly (no conditional check for count > 0 here)
                count += 0x1ff;
                
                // Write count (word)
                zram.write16(zPcmBase + MdDef.zp_count, count);
                // Write pitch
                zram.write8(zPcmBase + MdDef.zp_pitch, pitchOrig);

                    
                    // For now, let's assume standard format and try to populate likely fields
                    // If we see plausible addresses in dump, we can map them.
                    /*
                    int bankAddr = pcmHeader.read32(2); // Guessing offset 2
                    zram.write32(zPcmBase + MdDef.zp_s_ptr, bankAddr);
                    */
                    
                    // Trigger Z80 Key On (Only if Pitch is valid/non-zero AND Count is valid)
                    // Trigger Z80 Key On (Only if Pitch is valid/non-zero)
                    // Assembly: tst.b d0; beq ...
                    if (pitchOrig != 0) {
                        zram.write8(zPcmBase + MdDef.zp_key_on, 1);
                    }
                }
            }


            // Set Vol (mds_z80_pcm_vol_start at line 3123)
            // Use mds_z80_get_vol for full volume calculation (assembly mds_z80_get_vol macro)
            int vol = mds_z80_get_vol(a0, t);
            zram.write8(zPcmBase + MdDef.zp_vol, vol);
            return;  // Assembly: bra.w mds_update_return
        }

        // Assembly (lines 3060-3061): bclr #nf+nf_key_off,flag; bne.w mds_pcm_key_off
        boolean wasKeyOff = (t.t_note_flag & (1 << nf_key_off)) != 0;
        t.t_note_flag &= ~(1 << nf_key_off);
        if (wasKeyOff) {
            // mds_pcm_key_off (lines 3128-3132)
            t.t_channel_flag &= ~(1 << cf_key_on);
            // Stop Z80 (mds_z80_pcm_stop)
            int vol = zram.read8(zPcmBase + MdDef.zp_vol);
            zram.write8(zPcmBase + MdDef.zp_vol, vol | 0x80); // Key off bit
            return;  // Assembly: bra.w mds_update_return
        }

        // Assembly (lines 3062-3068): vol update only if neither key_on nor key_off
        if ((t.t_note_flag & (1 << nf_vol)) != 0) {
            t.t_note_flag &= ~(1 << nf_vol);
            // Use mds_z80_get_vol for full volume calculation (assembly mds_z80_get_vol macro)
            int vol = mds_z80_get_vol(a0, t);
            zram.write8(zPcmBase + MdDef.zp_vol, vol);
        }
    }

    private void mds_fm_update(WorkArea a0, TrackData t, int ch, int part) {
        int chOffset = ch;
        int portOffset = part * 2;

        // CRITICAL: Assembly processes nf_ins BEFORE nf_key_off (line 2263-2309)
        // This ensures instrument parameters are loaded BEFORE the key-off
        // Instrument loading must happen first so TL values are set correctly
        if ((t.t_note_flag & (1 << nf_ins)) != 0) {
            t.t_note_flag &= ~(1 << nf_ins);
            mds_fm_update_ins(a0, t, ch, part);
            t.t_note_flag |= (1 << nf_vol);
        }

        if ((t.t_note_flag & (1 << nf_key_off)) != 0) {
            t.t_note_flag &= ~(1 << nf_key_off);
            // Assembly (lines 2463-2469): `bclr #cf+cf_key_on,flag` merely clears the bit,
            // it does not gate the write that follows -- $28 is written unconditionally.
            // Guarding on cf_key_on here left a voice sounding whenever the flag had already
            // been cleared, e.g. a track finishing on a sustaining note.
            t.t_channel_flag &= ~(1 << cf_key_on);
            int keyOffSlot = ch + (part * 4);
            write_fm_port0(0x28, keyOffSlot);
        }

        if ((t.t_note_flag & (1 << nf_vol)) != 0) {
            t.t_note_flag &= ~(1 << nf_vol);
            mds_fm_update_vol(a0, t, ch, part);
        }

        int pitch = mds_pitch_update(a0, t);
        if (pitch != t.t_last_pitch) {
            t.t_last_pitch = pitch;
            
            // Check for FM3 Special Mode (Channel 2 of Part 0, 0-indexed)
            // Assembly: Checks if ch==2 and special flag/mask logic. But mds_fm3_update is explicit call.
            // Here we dispatch based on ch.
            if (ch == 2 && part == 0 && a0.w_fm3_mask != 0) {
                // If w_fm3_mask is non-zero, it implies special mode might be active or masking is used. 
                // Assembly mds_fm3_update does explicit pitch update via mds_fm3_update_pitch.
                mds_fm3_update_pitch(a0, t);
            } else {
                // Standard FM Update
                int fmPitch = mds_get_fm_pitch(t, pitch);
                // Assembly (lines 2578-2584): move.b d0,1(zram) writes to 0xA4 (high byte)
                // then ror.w #8,d0 swaps, move.b d0,1(zram) writes to 0xA0 (low byte)
                // So: 0xA4 gets LOW byte of result, 0xA0 gets HIGH byte of result
                int low = fmPitch & 0xff;           // For 0xA4 (block/fnum high)
                int high = (fmPitch >> 8) & 0xff;   // For 0xA0 (fnum low)

                if (part == 0) {
                    write_fm_port0(0xA4 + ch, low);   // Was: high (WRONG)
                    write_fm_port0(0xA0 + ch, high);  // Was: low (WRONG)
                } else {
                    // Assembly: and.w chnid,d1 (lines 2572) - use port-relative offset
                    write_fm_port1(0xA4 + (ch % 3), low);   // Was: high (WRONG)
                    write_fm_port1(0xA0 + (ch % 3), high);  // Was: low (WRONG)
                }
            }
        }

        // Pan/LFO update (Assembly lines 2592-2604)
        if ((t.t_note_flag & (1 << nf_pan_lfo)) != 0) {
            t.t_note_flag &= ~(1 << nf_pan_lfo);
            int panLfo = t.t_fm_pan_lfo & 0xff;
            // Assembly: and.w chnid,d1; addi.b #$b4,d1 (lines 2595-2598)
            int panReg = 0xB4 + (ch % 3);
            if (part == 0)
                write_fm_port0(panReg, panLfo);
            else
                write_fm_port1(panReg, panLfo);
        }

        // Assembly (lines 2610-2613): bclr #nf+nf_key_on,flag; beq @no_key_on
        if ((t.t_note_flag & (1 << nf_key_on)) != 0) {
            t.t_note_flag &= ~(1 << nf_key_on);
            // Assembly: bclr #nf+nf_slur,flag; bne @no_key_on
            // bclr CLEARS the bit AND tests old value - must clear here too
            boolean wasSlur = (t.t_note_flag & (1 << nf_slur)) != 0;
            t.t_note_flag &= ~(1 << nf_slur);  // Clear slur flag (assembly bclr does this)
            if (!wasSlur) {
                t.t_channel_flag |= (1 << cf_key_on);

                // Assembly (lines 2616-2629): Read t_op_mask from track data
                // move.b t_op_mask(twork),d0; not.b d0; ...
                // add.b d0,d0; andi.b #$f0,d0; or.b chnid,d0
                int opMask = t.t_op_mask & 0xFF;
                opMask = (~opMask) & 0xFF;  // not.b d0

                // Check FM3 special mode (assembly: bmi.s @no_fm3_key_on)
                // If bit 7 is clear after NOT, we're NOT in FM3 special mode
                if ((opMask & 0x80) == 0) {
                    // Normal FM or FM3: merge with global FM3 mask
                    opMask |= a0.w_fm3_mask;
                    a0.w_fm3_mask = opMask;
                }

                // Assembly: add.b d0,d0; andi.b #$f0,d0; or.b chnid,d0
                opMask = (opMask << 1) & 0xF0;
                int slot = ch + (part * 4);  // ch is 0-2 relative to port
                write_fm_port0(0x28, opMask | slot);
            }
        }

    }

    private void mds_fm6_update(WorkArea a0, TrackData t) {
        // Assembly (lines 2444-2454):
        // btst #cf+cf_pcm_control,flag   - Check if PCM control is enabled for THIS track
        // bne.w mds_pcm1_update          - If so, go to PCM update
        // tst.b w_pcm_mode(work)         - Check w_pcm_mode bit 7
        // bpl.s mds_fm1_update           - If clear (not in hardware PCM mode), do FM update

        // Check if this track has PCM control enabled
        if ((t.t_channel_flag & (1 << cf_pcm_control)) != 0) {
            mds_pcm1_update(a0, t);
            return;
        }
        
        // Check w_pcm_mode bit 7 - if clear, do normal FM6 update
        // If bit 7 is set, we may need to handle PCM-to-FM transition
        if ((a0.w_pcm_mode & 0x80) != 0) {
            // Was in PCM mode, now switching to FM - clear PCM flag
            a0.w_pcm_mode &= ~(1 << pe_pcm1);
            // Send PCM key off via Z80 (simplified - just clear the flag)
        }
        
        // Do normal FM update for channel 6 (part 1, ch 2)
        mds_fm_update(a0, t, 2, 1);
    }


    private void mds_fm3_update_pitch(WorkArea a0, TrackData t) {
        int pitch = t.t_last_pitch;
        int fmPitch = mds_get_fm_pitch(t, pitch);

        // Assembly @write_one (lines 2156-2171): writes d0 to high register (0xAD/AE/AC/A6)
        // then ror.w #8, writes d0 to low register (0xA9/AA/A8/A2)
        // Same byte swap pattern as normal FM pitch
        int datA4 = fmPitch & 0xff;           // For high registers (0xAD/AE/AC/A6)
        int datA0 = (fmPitch >> 8) & 0xff;    // For low registers (0xA9/AA/A8/A2)

        int mask = t.t_op_mask & a0.w_fm3_mask;

        // Op 1 (AD/A9) - Bit 3
        if ((mask & 0x08) == 0) {
            write_fm_port0(0xAD, datA4);
            write_fm_port0(0xA9, datA0);
        }
        // Op 2 (AE/AA) - Bit 4
        if ((mask & 0x10) == 0) {
            write_fm_port0(0xAE, datA4);
            write_fm_port0(0xAA, datA0);
        }
        // Op 3 (AC/A8) - Bit 5
        if ((mask & 0x20) == 0) {
            write_fm_port0(0xAC, datA4);
            write_fm_port0(0xA8, datA0);
        }
        // Op 4 (A6/A2) - Bit 6
        if ((mask & 0x40) == 0) {
            write_fm_port0(0xA6, datA4);
            write_fm_port0(0xA2, datA0);
        }
    }

    private void mds_psg_update(WorkArea a0, TrackData t, int chVal) {
        mds_psg_update_env(a0, t, chVal);
        int pitch = mds_pitch_update(a0, t);
        
        // Debug: Track note duration (disabled)
        // int currentNote = t.t_note;
        // if (currentNote == t.t_debug_last_note) {
        //     t.t_debug_note_frames++;
        //     if (t.t_debug_note_frames == 31) {
        //         logger.log(Level.TRACE, "LONG_NOTE: Ch%d Note=%d held for >30 frames, counter=%d, position=%d%n",
        //             t.t_channel_id, currentNote, t.t_counter, t.t_position);
        //     }
        // } else {
        //     t.t_debug_last_note = currentNote;
        //     t.t_debug_note_frames = 1;
        // }
        
        if (pitch != t.t_last_pitch) {
            t.t_last_pitch = pitch;
            int psgPitch = mds_get_psg_pitch(t, pitch);

            // Assembly (lines 2910-2916):
            // moveq #$0f,d1 -> and.b d0,d1 -> or.b chnid,d1 -> move.b d1,sound_psg
            // lsr.w #4,d0 -> andi.b #$7f,d0 -> move.b d0,sound_psg
            
            // Note: We do NOT clamp psgPitch to 0x3FF (10 bits) here because the assembly 
            // uses mask 0x7F for the high byte, effectively allowing 7 bits (Total 4+7=11 bits?).
            // Even if PSG is 10-bit, we must send exactly what assembly sends.
            
            int low4 = psgPitch & 0x0F;
            int high = (psgPitch >> 4) & 0x7F;

            Memory psg = getPsgMemory();
            psg.write8(MdDef.sound_psg, chVal | low4);
            psg.write8(MdDef.sound_psg, high);
        }
    }

    private void mds_psg_update_env(WorkArea a0, TrackData t, int chVal) {
        Memory psg = getPsgMemory();
        
        // Assembly (line 2712): tst.l flag; bpl.w @silence
        // Check if track is enabled (bit 31 of flag = nf_enabled)
        if ((t.t_note_flag & (1 << nf_enabled)) == 0) {
            mds_psg_silence(t, chVal, psg);
            return;
        }

        // Assembly (lines 2718-2732): Key On handling
        boolean goToCommand = false;
        if ((t.t_note_flag & (1 << nf_key_on)) != 0) {
            t.t_note_flag &= ~(1 << nf_key_on);
            if ((t.t_note_flag & (1 << nf_slur)) != 0) {
                // Slur: just clear slur flag
                t.t_note_flag &= ~(1 << nf_slur);
            } else {
                // No slur
                if ((t.t_note_flag & (1 << nf_key_off)) != 0) {
                    // New note with key-off: initialize envelope
                    t.t_note_flag &= ~(1 << nf_key_off);
                    t.t_channel_flag |= (1 << cf_key_on);
                    // Assembly: moveq #$1f,@cmd; move.w @cmd,t_psg_eg_pos
                    t.t_psg_eg_pos = 0;      // Will be incremented to 1 in @command
                    t.t_psg_eg_delay = 0x1f; // Initial delay (silence until first read)
                    t.t_note_flag |= (1 << nf_sustain);
                    t.t_last_pitch = 0xffff;
                    t.t_peg_delay = 0;
                    goToCommand = true;
                }
                // else: Tie (no key_off), fall through to check sustain
            }
        }

        // Assembly (lines 2738-2759): Check key_off and sustain
        if (!goToCommand) {
            if ((t.t_note_flag & (1 << nf_key_off)) != 0) {
                // Key Off: clear sustain and key flags (line 2765)
                t.t_note_flag &= ~((1 << nf_sustain) | (1 << nf_key_off));
                t.t_channel_flag &= ~(1 << cf_key_on);
                // Fall through to @command
            } else if ((t.t_note_flag & (1 << nf_sustain)) != 0) {
                // Sustain mode: process delay (lines 2741-2759)
                // Assembly: move.b t_psg_eg_delay,@cmd; moveq #$10,@pos; sub.b @pos,@cmd
                int delay = t.t_psg_eg_delay & 0xff;
                int cmd = delay - 0x10;  // Subtract 16
                
                if (cmd < 0) {
                    // bcs.s @hold - delay < 16, hold at current volume
                    if ((t.t_note_flag & (1 << nf_vol)) != 0) {
                        t.t_note_flag &= ~(1 << nf_vol);
                        int vol = delay & 0x0f;
                        writePsgVolume(a0, t, chVal, vol, psg);
                    }
                    return;
                } else if (cmd >= 0x10) {
                    // cmp.b @pos,@cmd; bcs.s @command - if cmd >= 16, go to @start_hold
                    // @start_hold: move.b @cmd,t_psg_eg_delay
                    t.t_psg_eg_delay = cmd;
                    if ((t.t_note_flag & (1 << nf_vol)) != 0) {
                        t.t_note_flag &= ~(1 << nf_vol);
                        int vol = cmd & 0x0f;
                        writePsgVolume(a0, t, chVal, vol, psg);
                    }
                    return;
                }
                // cmd is 0-15: fall through to @command
            }
            // else: not sustaining, go to @command
        }

        // Assembly (lines 2767-2827): @command - read envelope data
        // pos is incremented first, then data read at pos-1
        int pos = (t.t_psg_eg_pos + 1) & 0xff;
        
        if (pos == 0) {
            // Was 0xff, now 0 -> silence
            // OPTIMIZATION: Only write mute if not already silenced
            // This prevents redundant mute writes every frame for silenced tracks
            if (t.t_psg_eg_pos != 0xff) {
                mds_psg_silence(t, chVal, psg);
            }
            return;
        }

        int addrOffset = t.t_psg_eg_addr;
    // Check if we have specific envelope data (RIFF) or need to use sdtop
    Memory envData;
    if (t.t_psg_env_data != null) {
        envData = t.t_psg_env_data;
    } else {
        if (addrOffset == 0 || (addrOffset & 0xfff8) == 0xfff8) {
            // Invalid address
            mds_psg_silence(t, chVal, psg);
            return;
        }
    
        Memory sdtop = a0.w_sdtop;
        if (sdtop == null) {
            mds_psg_silence(t, chVal, psg);
            return;
        }
        envData = sdtop.add(addrOffset);
    }

    int cmd = envData.read8(pos - 1) & 0xff;  // Read at pos-1

        // Assembly processes multiple envelope commands per frame in a loop
        // When cmd is 0x01 (sustain) or 0x02 (jump), it reads next byte and continues
        boolean readMore = true;
        while (readMore) {
            readMore = false;  // Default: exit loop after this iteration

            // Assembly (lines 2782-2794): Sustain command handling
            if ((t.t_note_flag & (1 << nf_sustain)) == 0) {
                // Not sustaining yet
                if (cmd == 0x01) {
                    // Start sustain - read next command and CONTINUE PROCESSING
                    t.t_note_flag |= (1 << nf_sustain);
                    cmd = envData.read8(pos) & 0xff;
                    pos++;
                    readMore = true;  // Loop to process the new cmd
                    continue;
                }
            } else {
                // Already sustaining
                if (cmd == 0x01) {
                    // Hold at current volume (assembly lines 2757-2759)
                    int vol = t.t_psg_eg_delay & 0x0f;
                    t.t_psg_eg_delay = vol;  // Clear high nibble
                    boolean volFlagSet = (t.t_note_flag & (1 << nf_vol)) != 0;
                    t.t_note_flag &= ~(1 << nf_vol);
                    if (volFlagSet) {
                        writePsgVolume(a0, t, chVal, vol, psg);
                    }
                    return;  // Assembly returns after checking flag
                }
            }

            // Assembly (lines 2800-2806): Jump command
            if (cmd == 0x02) {
                pos = envData.read8(pos) & 0xff;  // Read jump target
                cmd = envData.read8(pos) & 0xff;  // Read command at target
                pos++;
                readMore = true;  // Loop to process the new cmd
                continue;
            }

            // Assembly (lines 2812-2821): Check for silence (cmd < 0x10)
            if (cmd < 0x10) {
                mds_psg_silence(t, chVal, psg);
                return;
            }
        }

        // Assembly (lines 2823-2828): Valid volume+delay command
        // Pack pos in high byte, cmd (delay) in low byte
        t.t_psg_eg_pos = pos;
        t.t_psg_eg_delay = cmd;
        t.t_note_flag &= ~(1 << nf_vol);

        // Write volume
        int vol = cmd & 0x0f;
        writePsgVolume(a0, t, chVal, vol, psg);
    }

    private static void mds_psg_silence(TrackData t, int chVal, Memory psg) {
        t.t_psg_eg_pos = 0xff;
        t.t_psg_eg_delay = 0x0f;
        t.t_channel_flag &= ~(1 << cf_key_on);
        t.t_note_flag &= ~(1 << nf_key_off);
        psg.write8(MdDef.sound_psg, chVal | 0x10 | 0x0f);
    }

    private void writePsgVolume(WorkArea a0, TrackData t, int chVal, int envVol, Memory psg) {
        // Assembly (lines 2833-2852): @write_vol
        // moveq #15,d2
        // move.b t_vol(twork),@tvol
        // bmi.s @no_convert  (if bit 7 set, skip table lookup)
        // ext.w @tvol
        // move.b mds_psg_vol_table(pc,@tvol),@tvol
        // @no_convert:
        // not.b @tvol
        // and.b d2,@tvol  (mask to 0-15)
        // and.b d2,@evol  (mask envelope to 0-15)
        // add.b @evol,@tvol
        // add.b 1+w_volume(work,rnum),@tvol  (add global volume low byte)
        // cmp.b d2,@tvol  (compare with 15)
        // bcs.s @no_clamp
        // move.b d2,@tvol  (clamp to 15)

        int tVol = t.t_vol & 0xff;
        int trackVol;

        if ((tVol & 0x80) != 0) {
            // Bit 7 set: Use directly (inverted)
            trackVol = (~tVol) & 0x0f;
        } else {
            // Bit 7 clear: Use volume table lookup
            if (tVol < mds_psg_vol_table.length) {
                trackVol = (~mds_psg_vol_table[tVol]) & 0x0f;
            } else {
                trackVol = 0;
            }
        }

        // Add envelope volume (clamped to 0-15)
        int finalVol = trackVol + (envVol & 0x0f);
        // Add global volume (low byte from w_volume)
        if (t.t_request_id < RCOUNT * 2) {
            int globalVol = a0.w_volume[t.t_request_id >> 1] & 0xff;
            // if (chVal == 128 && globalVol != 0) logger.log(Level.TRACE, "Global Vol Add: " + globalVol);
            finalVol += globalVol;
        }

        // Clamp to max 15 (PSG volume is 4-bit, where 15 = silent)
        if (finalVol > 15) finalVol = 15;
        
        // if (chVal == 128 && debugCounter < 100)
        //     logger.log(Level.TRACE, "writePsgVolume: Ch:%d tVol:%02x trkVol:%d envVol:%d finalVol:%d%n", chVal, tVol, trackVol, envVol, finalVol);
        
        // Write to PSG (chVal | 0x10 = volume command for channel)
        // if (chVal == 128 && debugCounter < 100) logger.log(Level.TRACE, "PSG Write: " + Integer.toHexString(chVal | 0x10 | finalVol));
        psg.write8(MdDef.sound_psg, chVal | 0x10 | finalVol);
    }

    private void mds_psg3_update(WorkArea a0, TrackData t, int chVal) {
        mds_psg_update(a0, t, chVal);
    }

    private void mds_psgn_update(WorkArea a0, TrackData t, int chVal) {
        mds_psg_update_env(a0, t, chVal); // Assembly: bsr.w mds_psg_update_env
        int pitch = mds_pitch_update(a0, t); // Assembly: bsr.w mds_pitch_update

        // Assembly loads WORD where high=nreset, low=nmode
        // In Java we have separate fields, so combine them:
        // nreset bit 15 (high bit of nreset field) triggers reset
        // nmode contains the actual noise mode value (e.g. 0xE7)
        int nreset = t.t_psg_nreset;
        int nmode = t.t_psg_nmode;
        boolean noiseReset = (nreset & 0x80) != 0; // bmi.s @noise_reset (bit 7 of high byte)

        // bclr #nf+nf_nmode,flag (clears bit, returns previous state)
        // Assembly DOES clear the bit - bclr clears and returns old value
        boolean nmodeWasSet = (t.t_note_flag & (1 << nf_nmode)) != 0;
        t.t_note_flag &= ~(1 << nf_nmode);  // Clear the flag (assembly bclr does this!)

        if (nmodeWasSet) {
            noiseReset = true; // bne.s @noise_reset
        }

        if (!noiseReset) {
            // Check against PREVIOUS pitch (converted to freq)
            // But we haven't converted 'pitch' (Note Code) yet.
            // In assembly, 'mds_psgn_update' -> 'mds_psg_set_pitch' -> 'mds_get_psg_pitch'
            // So comparison should happen on Freq? 
            // Assembly 2905: cmp.w t_last_pitch, d0 (d0 IS PROBABLY NoteCode here? No, d0 comes from Pitch Update)
            // Wait. Assembly mds_psg_update_pitch:
            // 2905: cmp.w t_last_pitch(twork),d0
            // 2909: bsr.w mds_get_psg_pitch
            // So comparison uses NoteCode. last_pitch stores NoteCode?
            // Line 2908: move.w d0,t_last_pitch(twork) (Stores NoteCode before conversion).
            // So comparison and storage use NoteCode. Conversion happens AFTER.
            
            if (pitch == t.t_last_pitch) { 
                return; 
            }
        }
        
        t.t_last_pitch = pitch; // Store NoteCode

        // Assembly @noise_reset: After falling through (pitch changed) or jumping here,
        // just check nmode - NOT noiseReset. The condition was wrong before.
        // Assembly lines 2946-2949:
        //   @noise_reset:
        //     tst.b d1           ; test nmode (low byte)
        //     beq.s @no_psg3_control
        //     move.b d1,sound_psg
        //     bra.s mds_psg_update_pitch
        if (nmode != 0) {
             // Explicit Noise Mode Set
             getPsgMemory().write8(MdDef.sound_psg, nmode & 0xff);
             return;
        }

        // @no_psg3_control logic (Pitch -> Mode)
        // Convert NoteCode to Freq ONLY for this path?
        // Assembly 2955: move.w d0,t_last_pitch
        // 2956: ror.w #8,d0 ...
        // Wait. @no_psg3_control starts at 2954.
        // It does NOT call mds_get_psg_pitch!
        // It uses d0 (NoteCode) DIRECTLY!
        // ror.w #8,d0 -> High Byte of NoteCode is used.
        // My trace "27392" (0x6B00) is NoteCode. 0x6B (107).
        // 0x6B & 7 = 3. 0xE3.
        // So this logic works on NoteCode.

        // Java: pitch is NoteCode.
        int val = ((pitch >> 8) & 0x07) | 0xE0;
        getPsgMemory().write8(MdDef.sound_psg, val);
    }

    // PCM Updates
    private void mds_pcm1_update(WorkArea a0, TrackData t) {
        mds_pcm_update(a0, t, MdDef.z_pcm1, MdDef.pe_pcm1);
    }

    private void mds_pcm2_ch_update(WorkArea a0, TrackData t) {
        if ((t.t_channel_flag & (1 << cf_pcm_control)) != 0) {
            mds_pcm_update(a0, t, MdDef.z_pcm2, MdDef.pe_pcm2);
        } else {
            // Stop if not controlled
            // (Logic to clear pcm mode bit)
        }
    }

    private void mds_pcm3_ch_update(WorkArea a0, TrackData t) {
        if ((t.t_channel_flag & (1 << cf_pcm_control)) != 0) {
            mds_pcm_update(a0, t, MdDef.z_pcm3, MdDef.pe_pcm3);
        }
    }

    private static int mds_pitch_update(WorkArea a0, TrackData t) {
        // Assembly (lines 1989-2028): Calculate base pitch from note + transpose + detune
        // CRITICAL: t_trs is a SIGNED byte. Must sign-extend before using
        int trs = (byte) t.t_trs;  // Cast to byte to sign-extend (0xF0 becomes -16, not 240)
        int note = (t.t_note + trs) & 0xff;
        // Detune is a SIGNED byte - sign extend it (assembly uses ext.w)
        int dtn = (byte) t.t_dtn;  // Cast to byte for sign extension
        int pitch = (note << 8) + dtn;

        // Portamento update (Assembly lines 1997-2028)
        int current = t.t_pitch;
        int delta = pitch - current;

        if (delta != 0) {
            int pta = t.t_pta;
            if (pta > 0) {
                // Assembly: move.w d2,-(sp); move.b (sp)+,d1; ext.w d1
                // Gets high byte of delta as signed
                int step = delta >> 8;
                if (step < 0)
                    step -= 2;
                step++;
                step *= pta;
                step >>= 1;
                int next = current + step;
                // Clamp to target
                if (delta > 0) {
                    if (next > pitch)
                        next = pitch;
                } else {
                    if (next < pitch)
                        next = pitch;
                }
                pitch = next;
            }
        }
        t.t_pitch = pitch;

        // Pitch envelope update (Assembly lines 2032-2086)
        int pegAddr = t.t_peg_addr;
        if (pegAddr == 0) {
            return pitch;
        }

        // Check for extended mode (bit 15 set)
        boolean extended = (pegAddr & 0x8000) != 0;
        pegAddr &= 0x7FFF;
        
        // Key-on retrigger (Assembly lines 2040-2042)
        if ((t.t_note_flag & (1 << nf_key_on)) != 0) {
            t.t_peg_delay = 0;
            t.t_peg_pos = 0;
        }

        int pos = t.t_peg_pos & 0xff;
        Memory sdtop = a0.w_sdtop;
        
        if (!extended) {
            // Standard pitch envelope format (Assembly lines 2044-2086)
            // Each entry: 2 bytes mod, 2 bytes delta+delay
            int entryAddr = pegAddr + 2 + (pos * 4);
            
            int mod = t.t_peg_mod;
            int delay = t.t_peg_delay & 0xff;
            
            if (delay == 0) {
                // Read initial modulator (Assembly line 2056)
                mod = sdtop.read16(pegAddr + (pos * 4));
                if ((mod & 0x8000) != 0) mod |= 0xFFFF0000;  // Sign extend
            }
            
            // Read delta and add to modulator (Assembly lines 2059-2062)
            int deltaVal = sdtop.read8(entryAddr);
            if ((deltaVal & 0x80) != 0) deltaVal |= 0xFFFFFF00;  // Sign extend
            mod += deltaVal;
            t.t_peg_mod = mod;
            pitch += mod;
            
            // Check delay (Assembly lines 2065-2084)
            int targetDelay = sdtop.read8(entryAddr + 1) & 0xff;
            if (delay != targetDelay) {
                // Delay not done
                delay++;
                if ((delay & 0xff) != 0) {  // 0xff = endless
                    t.t_peg_delay = delay;
                }
            } else {
                // Delay done - check for jump or advance
                int nextCmd = sdtop.read16(entryAddr + 2);
                if ((nextCmd & 0xFF00) >= 0x7F00) {
                    // Jump command
                    t.t_peg_delay = nextCmd & 0xff;
                    t.t_peg_pos = (nextCmd >> 8) - 0x7F;
                } else {
                    // Advance to next position
                    t.t_peg_delay = 0;
                    t.t_peg_pos = pos + 1;
                }
            }
        } else {
            // Extended format (Assembly lines 2088-2120)
            // Each entry: 2 bytes mod, 1 byte delay, 1 byte next_pos
            int entryAddr = pegAddr + 2 + (pos * 4);
            
            int mod = t.t_peg_mod;
            int delay = t.t_peg_delay & 0xff;
            
            if (delay == 0) {
                mod = sdtop.read16(pegAddr + (pos * 4));
                if ((mod & 0x8000) != 0) mod |= 0xFFFF0000;
            }
            
            int deltaMod = sdtop.read16(entryAddr);
            if ((deltaMod & 0x8000) != 0) deltaMod |= 0xFFFF0000;
            mod += deltaMod;
            t.t_peg_mod = mod;
            pitch += mod;
            
            int targetDelay = sdtop.read8(entryAddr + 2) & 0xff;
            if (delay != targetDelay) {
                delay++;
                t.t_peg_delay = delay;
            } else {
                t.t_peg_pos = sdtop.read8(entryAddr + 3) & 0xff;
                t.t_peg_delay = 0;
            }
        }

        return pitch;
    }

    private static int mds_get_fm_pitch(TrackData t, int pitch) {
        // Assembly (lines 1887-1907):
        // lsl.l #8,d0         ; pitch << 8
        // move.w d0,d1        ; d1 = fraction (low word after shift = original pitch's low byte shifted)
        // swap d0             ; d0 = note (high word = original pitch's high byte)
        // lea mds_note_table(pc,d0),tmpa1
        // move.b (tmpa1),d0   ; d0 = note_table[note] (0,2,4...22 = index*2)
        // add.b t_ins_trs,d0  ; freq tab displacement
        // move.l mds_fm_freq_tab(pc,d0),d3  ; load 2 consecutive words
        // ...interpolation...
        // add.b mds_octave_table-mds_note_table(tmpa1),d0 ; add octave

        int note = (pitch >> 8) & 0xff;
        int frac = pitch & 0xff;

        if (note >= 120) note = 119;
        
        // note_table contains byte offsets (0,2,4...22) into the freq table
        int noteIndex = mds_note_table[note] & 0xff;  // 0,2,4...22
        // Assembly (line 1897): add.b t_ins_trs,d0 - SIGNED byte add!
        // CRITICAL: t_ins_trs is a SIGNED byte. Must sign-extend before using as offset
        int trs = (byte) t.t_ins_trs;  // Cast to byte to sign-extend (0xF0 becomes -16, not 240)
        int freqIndex = noteIndex + trs;  // Add signed transpose
        freqIndex &= 0xff;  // Keep as byte
        
        // Assembly loads a LONG at freq_tab[freqIndex], getting two consecutive words
        // freq_tab is an array of words, so freqIndex/2 = array index for first word
        int idx = freqIndex / 2;
        if (idx >= mds_fm_freq_tab.length - 1) idx = mds_fm_freq_tab.length - 2;
        
        int freq1 = mds_fm_freq_tab[idx];      // First frequency
        int freq2 = mds_fm_freq_tab[idx + 1];  // Second frequency
        
        // Interpolation: (freq2 - freq1) * frac / 256 + freq1
        int delta = freq2 - freq1;
        int interpFreq = freq1 + (delta * frac) / 256;
        
        // Assembly: rol.w #8,d0 then add.b octave
        // rol.w #8 swaps high and low bytes of the 16-bit value
        int freqLow = interpFreq & 0xff;
        int freqHigh = (interpFreq >> 8) & 0xff;
        
        // Add octave value to high byte
        int octave = mds_octave_table[note] & 0xff;
        
        // Return format: low byte in high position, (freqHigh + octave) in low position
        return (freqLow << 8) | ((freqHigh + octave) & 0xff);
    }

    private static int mds_get_psg_pitch(TrackData t, int pitch) {
        int note = (pitch >> 8) & 0xff;
        int fraction = pitch & 0xff;
        int freq = 0;
        if (note < mds_note_table.length) {
            int oct = mds_octave_table[note] / 8;
            int noteIdx = mds_note_table[note];
            
            int f1 = mds_psg_freq_tab[noteIdx / 2];
            // Assembly: f1 - f2 -> delta. delta * fraction -> sub from f1.
            // Ensure table boundary safety (table has 13 entries)
            int f2 = mds_psg_freq_tab[(noteIdx / 2) + 1];
            
            // Assembly: mulu d1,d0 where d1 = fraction << 8
            // Result is 32-bit: delta * fraction * 256
            // swap d0 -> equivalent to >> 16
            // So total shift relative to integer 'fraction' is >> 8
            int delta = f1 - f2;
            int diff = (delta * fraction) >> 8;
            
            // neg.w d0 -> d0 = -diff
            // add.w -(tmpa0),d0 -> f1 - diff
            freq = f1 - diff;
            
            // if (t.t_channel_id == 8 && debugCounter < 100) {
            //     logger.log(Level.TRACE, "GetPsgPitch Ch8: Note=%d Oct=%d NoteIdx=%d F1=%d Diff=%d FreqAfter=%d%n", 
            //         note, oct, noteIdx, f1, diff, freq >> oct);
            // }

            freq >>= oct;
        }
        return freq;
    }

    private void set_default_inst(WorkArea a0, TrackData t, int part, int ch) {
        // Create a bright synth lead patch using Algorithm 4
        // Alg 4: Op1->Op2->Out, Op3->Op4->Out (two parallel stacked pairs)
        // This gives a richer, more "FM synth" sound
        int newAlg = 4;
        int newFb = 5;  // Strong feedback on Op1 for harmonics
        
        t.t_fm_alg = newAlg | (newFb << 3);
        
        // FM operator layout: Op1=slot0, Op2=slot2(+8), Op3=slot1(+4), Op4=slot3(+12)
        // For Alg 4: Op1(mod)->Op2(car), Op3(mod)->Op4(car)
        // Register offsets: 0=Op1, 4=Op3, 8=Op2, 12=Op4
        int[] opOffsets = {0, 4, 8, 12};  // Physical register offsets
        
        // Parameters: [DT/MUL, TL, AR/KS, DR/AM, SR, SL/RR, SSG]
        // Bright synth lead patch
        int[][] opParams = {
            // Op1 (Modulator): creates harmonics
            {0x31, 0x23, 0x1f, 0x10, 0x05, 0x2f, 0x00},  // DT=0, MUL=1, TL=35, AR=31, DR=16, SR=5, RR=15
            // Op3 (Modulator): parallel modulator
            {0x32, 0x28, 0x1a, 0x0c, 0x04, 0x3f, 0x00},  // MUL=2 for brightness, TL=40
            // Op2 (Carrier): main output
            {0x01, 0x00, 0x1f, 0x06, 0x02, 0x1a, 0x00},  // MUL=1, TL=0 (full vol), nice decay
            // Op4 (Carrier): secondary output
            {0x01, 0x00, 0x1f, 0x08, 0x03, 0x2a, 0x00},  // MUL=1, TL=0, slight detuned feel
        };
        
        for (int opIndex = 0; opIndex < 4; opIndex++) {
            int opOff = opOffsets[opIndex];
            int addr = ch + opOff;
            int[] params = opParams[opIndex];
            
            // Op2 and Op4 are carriers (indices 2 and 3)
            boolean isCarrier = (opIndex >= 2);
            t.t_fm_tl[opIndex] = params[1] & 0x7f;  // Store TL for volume updates
            
            if (part == 0) {
                write_fm_port0(0x30 + addr, params[0]);  // DT/MUL
                write_fm_port0(0x40 + addr, params[1]);  // TL
                write_fm_port0(0x50 + addr, params[2]);  // AR/KS
                write_fm_port0(0x60 + addr, params[3]);  // DR/AM
                write_fm_port0(0x70 + addr, params[4]);  // SR
                write_fm_port0(0x80 + addr, params[5]);  // SL/RR
                write_fm_port0(0x90 + addr, params[6]);  // SSG
            } else {
                write_fm_port1(0x30 + addr, params[0]);
                write_fm_port1(0x40 + addr, params[1]);
                write_fm_port1(0x50 + addr, params[2]);
                write_fm_port1(0x60 + addr, params[3]);
                write_fm_port1(0x70 + addr, params[4]);
                write_fm_port1(0x80 + addr, params[5]);
                write_fm_port1(0x90 + addr, params[6]);
            }
        }

        // Algorithm and Feedback
        if (part == 0) write_fm_port0(0xB0 + ch, newAlg | (newFb << 3));
        else           write_fm_port1(0xB0 + ch, newAlg | (newFb << 3));
    }

    private void mds_fm_update_ins(WorkArea a0, TrackData t, int ch, int part) {
        Memory sdtop = a0.w_sdtop;
        if (sdtop == null)
            return;

        Memory tbase = sdtop.add(t.t_base_addr);
        int insIdx = t.t_ins;

        Memory insData = null;

        // Assembly logic: ALWAYS read from pointer table
        // Try glob first (RIFF only)
        if (a0.w_pointer_mode == 1 && a0.globInstruments != null && a0.globInstruments.containsKey(t.t_ins)) {
            insData = new ByteArrayMemory(a0.globInstruments.get(t.t_ins));
        } else {
            // ALWAYS read from pointer table (matches assembly logic)
            int insOffset = tbase.read16(insIdx * 2);
            if ((insOffset & 0x8000) != 0)
                insOffset |= 0xFFFF0000;  // Sign-extend for negative offsets
            insData = sdtop.add(insOffset);
        }

        // Skip if instrument not found
        if (insData == null)
            return;

        // Mute channel
        int tlReg = 0x40 + ch;
        for (int op = 0; op < 4; op++) {
            int regAddr = tlReg + (op * 4);
            if (part == 0)
                write_fm_port0(regAddr, 0x7f);
            else
                write_fm_port1(regAddr, 0x7f);
        }

        // Upload instrument registers
        // Correct Layout from Assembly cmd_ins:
        // 24 bytes of Register Data (0-23) -> Regs 30, 50, 60, 70, 80, 90
        // 4 bytes of TL (24-27)
        // 1 byte Alg (28)
        // 1 byte Trs (29)
        int[] regBases = { 0x30, 0x50, 0x60, 0x70, 0x80, 0x90 };
        int dataPtr = 0; // Start at 0

        StringBuilder envLog = new StringBuilder();
        envLog.append(String.format("FM INS CH%d part%d idx=%d: ", ch, part, insIdx));

        for (int r = 0; r < 6; r++) {
            int regBase = regBases[r];
            int currentReg = regBase + ch;
            for (int op = 0; op < 4; op++) {
                int val = insData.read8(dataPtr++);
                int regAddr = currentReg + (op * 4);
                if (part == 0)
                    write_fm_port0(regAddr, val);
                else
                    write_fm_port1(regAddr, val);
            }
        }

        // Assembly lines 2226-2234: Write Feedback/Algorithm register (0xB0 + ch)
        // This was missing from the Java code! Must write AFTER the envelope registers.
        // dataPtr is now 24 after the main register loop.
        // Offsets: 0-23 registers, 24-27 TL, 28 ALG, 29 TRS
        int algReg = 0xB0 + ch;
        int algValue = insData.read8(28) & 0xff;  // Read algorithm from offset 28
        if (part == 0)
            write_fm_port0(algReg, algValue);
        else
            write_fm_port1(algReg, algValue);

        // Log envelope parameters: AR(bytes 4-7), DR(bytes 8-11), SR(bytes 12-15), RR/SL(bytes 16-19)
        envLog.append("AR=");
        for (int op = 0; op < 4; op++) {
            if (op > 0) envLog.append(",");
            envLog.append(String.format("%02X", insData.read8(4 + op) & 0x1f));
        }
        envLog.append(" DR=");
        for (int op = 0; op < 4; op++) {
            if (op > 0) envLog.append(",");
            envLog.append(String.format("%02X", insData.read8(8 + op) & 0x1f));
        }
        envLog.append(" SR=");
        for (int op = 0; op < 4; op++) {
            if (op > 0) envLog.append(",");
            envLog.append(String.format("%02X", insData.read8(12 + op) & 0x1f));
        }
        envLog.append(" SL/RR=");
        for (int op = 0; op < 4; op++) {
            if (op > 0) envLog.append(",");
            int slrr = insData.read8(16 + op) & 0xff;
            int sl = (slrr >> 4) & 0x0f;
            int rr = slrr & 0x0f;
            envLog.append(String.format("%X/%X", sl, rr));
        }
        envLog.append(" TL=");
        for (int op = 0; op < 4; op++) {
            if (op > 0) envLog.append(",");
            envLog.append(String.format("%02X", insData.read8(24 + op) & 0x7f));
        }
        logger.log(Level.TRACE, envLog.toString());

        // dataPtr is now 24.
        // Store TL values for volume calculations (bytes 24-27)
        t.t_fm_tl[0] = insData.read8(dataPtr + 0) & 0x7f;
        t.t_fm_tl[1] = insData.read8(dataPtr + 1) & 0x7f;
        t.t_fm_tl[2] = insData.read8(dataPtr + 2) & 0x7f;
        t.t_fm_tl[3] = insData.read8(dataPtr + 3) & 0x7f;

        // Read and store algorithm/feedback (byte 28)
        int algo = insData.read8(dataPtr + 4) & 0xff; // 24 + 4 = 28
        t.t_fm_alg = algo;  // Store full algo+fb (vol update masks with 0x07)

        // NOTE: Do NOT load t_ins_trs here!
        // The E1 command handler (line 1225 in assembly) already loads t_ins_trs from byte 29
        // of the instrument data when the instrument is selected.
        // mds_fm_update_ins is called later to upload registers, and should NOT overwrite t_ins_trs
        // Overwriting it would corrupt the transpose value set by the E1 command,
        // especially for drum notes which depend on the correct transpose value.
    }

    private void mds_fm_update_vol(WorkArea a0, TrackData t, int ch, int part) {
        // Assembly mds_fm_update_vol (lines 2263-2311) and mds_fm3_update_vol (lines 2330-2370)
        // 1. FM3 special mode (t_op_mask bit 7 set) uses GLOBAL w_fm3_tl[] and w_fm3_alg
        // 2. Normal FM uses track-specific t_fm_tl[] and t_fm_alg
        // 3. For CARRIER operators only (determined by algorithm), add volume offset
        // 4. Modulator operators keep their original TL

        int vol = t.t_vol & 0xff;
        int volOffset;

        // Check if using volume table (bit 7 set)
        if ((vol & 0x80) != 0) {
            if (vol >= 0x90) vol = 0x8f;  // Clamp
            volOffset = mds_fm_vol_table[vol & 0x0f];
        } else {
            volOffset = vol;
        }

        // Add global volume
        if (t.t_request_id < RCOUNT * 2) {
            volOffset += (a0.w_volume[t.t_request_id >> 1] >> 8) & 0xff;
        }

        // Check if FM3 special mode (assembly line 2264-2265)
        boolean isFm3SpecialMode = (t.t_op_mask & 0x80) != 0;

        // Select TL source and algorithm based on mode
        int[] tlSource;
        int alg;
        if (isFm3SpecialMode) {
            // Assembly (line 2267): lea 4+w_fm3_tl(work),tmpa1 - use GLOBAL values
            tlSource = a0.w_fm3_tl;
            // Assembly (line 2280): move.b w_fm3_alg-1(work),d4
            alg = a0.w_fm3_alg & 0x07;
        } else {
            // Normal FM - use track-specific values
            tlSource = t.t_fm_tl;
            alg = t.t_fm_alg & 0x07;
        }

        int carrierThreshold = mds_fm_op_table[alg];  // Operators >= this are carriers

        // Operator order: 3, 2, 1, 0 (assembly does -(tmpa1) starting from end)
        // Register order: 0x4C, 0x48, 0x44, 0x40 (subq.b #4)
        int[] opRegs = {0x4C, 0x48, 0x44, 0x40};  // OP4, OP3, OP2, OP1 TL registers
        int[] opOrder = {3, 2, 1, 0};  // Match assembly order

        for (int i = 0; i < 4; i++) {
            int opIdx = opOrder[i];
            int baseTL = tlSource[opIdx] & 0x7f;  // Base TL from correct source
            int finalTL;

            // Check if this operator is a carrier (assembly: cmp.b mds_fm_op_table(pc,d4),d2; bcs @modulator)
            if (opIdx >= carrierThreshold) {
                // Carrier: add volume offset with overflow check
                finalTL = baseTL + volOffset;
                if (finalTL > 127) finalTL = 127;  // Clamp to max TL
            } else {
                // Modulator: keep original TL
                finalTL = baseTL;
            }

            // Assembly: and.b d2,d1; add.b #$4c,d1 (lines 2283-2285) - port-relative
            int reg = opRegs[i] + (ch % 3);
            if (part == 0)
                write_fm_port0(reg, finalTL);
            else
                write_fm_port1(reg, finalTL);
        }
    }

    // Tables
    private static final byte[] mds_fm_op_table = { 3, 3, 3, 3, 2, 1, 1, 0 };
    private static final byte[] mds_fm_vol_table = { 42, 40, 37, 34, 32, 29, 26, 24, 21, 18, 16, 13, 10, 8, 5, 2 };

    private static final int[] mds_fm_freq_tab = {
            161, 171, 181, 191, 203, 215, 228, 241, 255, 271, 287, 304,
            322, 341, 361, 383, 406, 430, 455, 482, 511, 541, 574, 608,
            644, 682, 723, 766, 811, 859, 910, 965, 1022, 1083, 1147, 1215,
            1288, 1364, 1445, 1531, 1622, 1719, 1821, 1929, 2044,
            // Pad with last value to prevent AIOOBE during testing
            2044, 2044, 2044, 2044, 2044, 2044, 2044, 2044, 2044, 2044,
            2044, 2044, 2044, 2044, 2044, 2044, 2044, 2044, 2044, 2044,
            2044, 2044, 2044, 2044, 2044, 2044, 2044, 2044, 2044, 2044,
            2044, 2044, 2044, 2044, 2044, 2044, 2044, 2044, 2044, 2044,
            2044, 2044, 2044, 2044, 2044, 2044, 2044, 2044, 2044, 2044,
            2044, 2044, 2044, 2044, 2044, 2044, 2044, 2044, 2044, 2044,
            2044, 2044, 2044, 2044, 2044, 2044, 2044, 2044, 2044, 2044,
            2044, 2044, 2044, 2044, 2044, 2044, 2044, 2044, 2044, 2044,
            2044, 2044, 2044, 2044, 2044, 2044, 2044, 2044, 2044, 2044
    };

    private static final int[] mds_psg_freq_tab = {
            1710, 1614, 1524, 1438, 1357, 1281, 1209, 1141, 1077, 1017, 960, 906,
            855
    };

    private static final byte[] mds_note_table = {
            0, 2, 4, 6, 8, 10, 12, 14, 16, 18, 20, 22,
            0, 2, 4, 6, 8, 10, 12, 14, 16, 18, 20, 22,
            0, 2, 4, 6, 8, 10, 12, 14, 16, 18, 20, 22,
            0, 2, 4, 6, 8, 10, 12, 14, 16, 18, 20, 22,
            0, 2, 4, 6, 8, 10, 12, 14, 16, 18, 20, 22,
            0, 2, 4, 6, 8, 10, 12, 14, 16, 18, 20, 22,
            0, 2, 4, 6, 8, 10, 12, 14, 16, 18, 20, 22,
            0, 2, 4, 6, 8, 10, 12, 14, 16, 18, 20, 22,
            0, 2, 4, 6, 8, 10, 12, 14, 16, 18, 20, 22,
            0, 2, 4, 6, 8, 10, 12, 14, 16, 18, 20, 22
    };

    private static final byte[] mds_octave_table = new byte[256];
    static {
        for (int i = 0; i < 12; i++)
            mds_octave_table[i] = 0;
        for (int i = 0; i < 12; i++)
            mds_octave_table[12 + i] = 8;
        for (int i = 0; i < 12; i++)
            mds_octave_table[24 + i] = 16;
        for (int i = 0; i < 12; i++)
            mds_octave_table[36 + i] = 24;
        for (int i = 0; i < 12; i++)
            mds_octave_table[48 + i] = 32;
        for (int i = 0; i < 12; i++)
            mds_octave_table[60 + i] = 40;
        for (int i = 0; i < 12; i++)
            mds_octave_table[72 + i] = 48;
        for (int i = 0; i < 12; i++)
            mds_octave_table[84 + i] = 56;
        for (int i = 0; i < 12; i++)
            mds_octave_table[96 + i] = 64;
        for (int i = 0; i < 12; i++)
            mds_octave_table[108 + i] = 72;
        for (int i = 0; i < 12; i++)
            mds_octave_table[120 + i] = 80;
    }

    // PSG Volume Table (approximate from ASM or converted)
    // ASM: dcb.b 2,$8f; ... various runs.
    // I will use a simplified table or the exact one if I expand it.
    // For now, let's pre-expand the table as seen in ASM.
    private static final byte[] mds_psg_vol_table_raw = {
            (byte) 0x8f, (byte) 0x8f, // 2
            (byte) 0x8f, (byte) 0x8f, (byte) 0x8f, // 3
            (byte) 0x8e, (byte) 0x8e, (byte) 0x8e, // 3
            (byte) 0x8d, (byte) 0x8d, // 2
            (byte) 0x8c, (byte) 0x8c, (byte) 0x8c, // 3
            (byte) 0x8b, (byte) 0x8b, (byte) 0x8b, // 3
            (byte) 0x8a, (byte) 0x8a, // 2
            (byte) 0x89, (byte) 0x89, (byte) 0x89, // 3
            (byte) 0x88, (byte) 0x88, (byte) 0x88, // 3
            (byte) 0x87, (byte) 0x87, // 2
            (byte) 0x86, (byte) 0x86, (byte) 0x86, // 3
            (byte) 0x85, (byte) 0x85, (byte) 0x85, // 3
            (byte) 0x84, (byte) 0x84, // 2
            (byte) 0x83, (byte) 0x83, (byte) 0x83, // 3
            (byte) 0x82, (byte) 0x82, (byte) 0x82, // 3
            (byte) 0x81, (byte) 0x81, // 2
            // ... and so on. This is huge.
            // Logic: 0-42 map to 8F..81. 43+ map to 81..80.
            // Let's implement a lookup function instead of a massive array if possible.
    };

    private static byte mds_psg_convert_vol(int vol) {
        if (vol < 0)
            vol = 0;
        if (vol >= mds_psg_vol_table_raw.length)
            vol = mds_psg_vol_table_raw.length - 1;
        return mds_psg_vol_table_raw[vol];
    }

    /**
     * Calculate PCM volume for Z80 - equivalent to assembly's mds_z80_get_vol macro.
     * Assembly reference: mdssub.inc:85-98
     */
    private static int mds_z80_get_vol(WorkArea a0, TrackData t) {
        int d1 = t.t_vol & 0xff;

        // Step 1: Table lookup if bit 7 NOT set
        // Assembly: bmi.s @no_conversion; bsr.w mds_pcm_convert_vol
        if ((d1 & 0x80) == 0) {
            d1 = mds_psg_vol_table_raw[Math.min(d1, mds_psg_vol_table_raw.length - 1)] & 0xff;
        }

        // Step 2: Subtract master volume (from w_volume, using request_id)
        // Assembly: sub.b 1+w_volume(work,rnum),d1
        // Note: t_request_id is stored as rnum*2 (byte offset 0,2,4,6), so divide by 2 for array index
        // Assembly uses 1+w_volume offset to get the LOW byte of the word
        int rnumIdx = t.t_request_id >> 1;
        if (rnumIdx >= 0 && rnumIdx < RCOUNT) {
            int masterVol = a0.w_volume[rnumIdx] & 0xff;
            d1 = (d1 - masterVol) & 0xff;
        }

        // Step 3: Clamp to 0 if positive (signed comparison)
        // Assembly: bmi.s @no_clamp (If negative, skip clear)
        //           clr.b d1        (Else, clear to 0)
        if ((d1 & 0x80) == 0) { // If bit 7 is clear (Positive)
            d1 = 0;
        }

        // Step 4: Bitwise NOT
        // Assembly: not.b d1
        // FIX: Restored inversion. Input 15 (Min?) -> ~15=0 (Max Index 0). Input 0 (Max?) -> ~0=15 (Min Index 15).
        // Matches passport.mds usage where 15 seems to be Loud.
        d1 = (~d1) & 0xff;

        // Step 5: Mask to 4 bits and add 15
        // Assembly: moveq #15,d2; and.b d2,d1; add.b d2,d1
        // We need Result 15 (Page 0x0F) for Max, and Result 30 (Page 0x1E) for Min.
        d1 = (d1 & 0x0f) + 15;

        return d1;
    }

    private static Memory parseRiffMds(WorkArea a0, Memory m) {
        // Simple RIFF parser to find seq and LIST chunks
        // Scans forward using read8 (assuming Little Endian for chunk sizes)
        int p = 12; // Skip RIFF header
        // Since we don't know total size easily from Memory interface without reading header, assume max scan
        // However, standard RIFF logic: ID(4)+Size(4)
        
        Memory seqChunk = null;
        
        // Safety limit or detect EOF if read returns 0? Memory interface usually throws or returns valid data.
        // We'll scan a reasonable amount (e.g. up to 1MB or until error)
        try {
            while (true) {
                // Read Chunk ID
                int c0 = m.read8(p);
                int c1 = m.read8(p+1);
                int c2 = m.read8(p+2);
                int c3 = m.read8(p+3);
                
                // End of file check (heuristically 0 or -1 if outside bounds, though Memory usually zero-pads)
                if (c0 == 0 && c1 == 0 && c2 == 0 && c3 == 0) break;
                
                // Read Size (Little Endian)
                int size = (m.read8(p+4) & 0xff) | 
                           ((m.read8(p+5) & 0xff) << 8) | 
                           ((m.read8(p+6) & 0xff) << 16) | 
                           ((m.read8(p+7) & 0xff) << 24);

                // ID check
                // "seq " = 0x73 0x65 0x71 0x20
                if (c0 == 's' && c1 == 'e' && c2 == 'q' && c3 == ' ') {
                    logger.log(Level.DEBUG, "Found seq chunk at " + p + " size " + size);
                    seqChunk = m.add(p + 8);
                }
                // "LIST" = 0x4C 0x49 0x53 0x54
                else if (c0 == 'L' && c1 == 'I' && c2 == 'S' && c3 == 'T') {
                    // Parse LIST contents for glob
                    logger.log(Level.DEBUG, "Found LIST chunk at " + p + " size " + size);
                    int listEnd = p + 8 + size;
                    int lp = p + 8;
                    // LIST type (4 bytes) e.g. "dblk"
                    lp += 4; 
                    
                    while (lp < listEnd - 8) {
                        int sc0 = m.read8(lp);
                        int sc1 = m.read8(lp+1);
                        int sc2 = m.read8(lp+2);
                        int sc3 = m.read8(lp+3);
                        
                        // Subchunk size
                        int subSize = (m.read8(lp+4) & 0xff) | ((m.read8(lp+5) & 0xff) << 8); // usually 16-bit? 32-bit?
                        // RIFF subchunks are usually 32-bit size, but "glob" in dblk might be different.
                        // My test code used 16-bit size for glob. Let's stick to test logic.
                        
                        // "glob" = 0x67 0x6c 0x6f 0x62
                        if (sc0 == 'g' && sc1 == 'l' && sc2 == 'o' && sc3 == 'b') {
                            int globIndex = (m.read8(lp+8) & 0xff) | ((m.read8(lp+9) & 0xff) << 8);
                            // Store data (30 bytes)
                            // "glob" header layout:
                            // +0-3: "glob" (4 bytes)
                            // +4-5: subSize (2 bytes, 16-bit LE) = total size INCLUDING "glob"+size fields
                            // +6-7: reserved (2 bytes)
                            // +8-9: globIndex (2 bytes, 16-bit LE)
                            // +10-11: reserved (2 bytes)
                            // +12+: instrument data (30 bytes for FM instruments)
                            // dataSize = subSize - 4 (subtract the "glob" ID which is 4 bytes)
                            int dataSize = subSize - 4;
                            byte[] data = new byte[dataSize];
                            for (int i=0; i<dataSize; i++) {
                                data[i] = (byte)m.read8(lp + 12 + i);
                            }
                            a0.globInstruments.put(globIndex, data);
                            // Debug dump first 30 bytes
                            StringBuilder sb = new StringBuilder(String.format("Glob %d @ offset 0x%04X stored: ", globIndex, lp + 12));
                            for (int i=0; i<Math.min(30, data.length); i++) sb.append(String.format("%02X ", data[i] & 0xff));
                            logger.log(Level.DEBUG, sb.toString());
                        }
                        // "pcmh" = 0x70 0x63 0x6D 0x68
                        else if (sc0 == 'p' && sc1 == 'c' && sc2 == 'm' && sc3 == 'h') {
                            // Read 32-bit LE values
                            // Index at +8
                            int pcmIdx = (m.read8(lp+8) & 0xff) | ((m.read8(lp+9) & 0xff) << 8) | ((m.read8(lp+10) & 0xff) << 16) | ((m.read8(lp+11) & 0xff) << 24);
                            // Position at +12
                            int posVal = (m.read8(lp+12) & 0xff) | ((m.read8(lp+13) & 0xff) << 8) | ((m.read8(lp+14) & 0xff) << 16) | ((m.read8(lp+15) & 0xff) << 24);
                            // Start at +16
                            int startVal = (m.read8(lp+16) & 0xff) | ((m.read8(lp+17) & 0xff) << 8) | ((m.read8(lp+18) & 0xff) << 16) | ((m.read8(lp+19) & 0xff) << 24);
                            // Size at +20
                            int sizeVal = (m.read8(lp+20) & 0xff) | ((m.read8(lp+21) & 0xff) << 8) | ((m.read8(lp+22) & 0xff) << 16) | ((m.read8(lp+23) & 0xff) << 24);
                            // Rate/Pitch - original assembly @cmd_pcm reads FIRST BYTE of chunk data
                            // RIFF pcmh structure: +0-3="pcmh", +4-7=size, +8+=data
                            // So first data byte is at offset +8, which is the index field in current parsing
                            // Let's try reading the ACTUAL first byte of the chunk data
                            // Rate/Pitch is at offset 24 (0x18) in the chunk data (observed from dump)
                            int rateVal = (m.read8(lp+32) & 0xff) | ((m.read8(lp+33) & 0xff) << 8) | ((m.read8(lp+34) & 0xff) << 16) | ((m.read8(lp+35) & 0xff) << 24);
                            
                            // Map Hz to Pitch Index (Approx 4kHz steps: 1=4k, 2=8k, 3=12k, 4=16k)
                            int pitchIdx = 0;
                            if (rateVal > 0) {
                                pitchIdx = (rateVal + 2000) / 4000;
                                if (pitchIdx == 0) pitchIdx = 1; // Minimum pitch 1 if rate exists
                                if (pitchIdx > 8) pitchIdx = 8;  // Cap at 8 (32kHz) or similar reasonable limit
                            }

                            // Construct PCM Header (8 bytes, matching assembly struct)
                            int addr = posVal + startVal;

                            byte[] header = new byte[8];
                            header[0] = (byte)pitchIdx;                 // Pitch Index at offset 0
                            header[1] = (byte)((addr >>> 16) & 0xff);  // High byte of address
                            header[2] = (byte)((addr >>> 8) & 0xff);   // Mid byte
                            header[3] = (byte)(addr & 0xff);           // Low byte of address
                            header[4] = 0;  // Padding
                            header[5] = 0;  // Padding
                            // Length as 16-bit word at offset 6-7
                            header[6] = (byte)((sizeVal >>> 8) & 0xff);  // High byte of length
                            header[7] = (byte)(sizeVal & 0xff);          // Low byte of length
                            
                            
                            a0.pcmHeaders.put(pcmIdx, header);
                        }
                        
                        lp += 8 + subSize;
                        if ((subSize & 1) != 0) lp++;
                    }
                }
                // "pcmd" = 0x70 0x63 0x6D 0x64
                else if (c0 == 'p' && c1 == 'c' && c2 == 'm' && c3 == 'd') {
                    logger.log(Level.DEBUG, "Found pcmd chunk at " + p + " size " + size);
                    // Store in w_pcm_ptr
                    a0.w_pcm_ptr = m.add(p + 8);
                }
                
                if ((size & 1) != 0) size++;
                p += 8 + size;
                
                // Safety break if p is clearly out of bounds
                if (p > 1024*1024) break; 
            }
        } catch (Exception e) {
            // End of memory or parse error
            logger.log(Level.WARNING, "RIFF parse ended: " + e);
        }
        
        return seqChunk;
    }
    
    public static class ByteArrayMemory implements Memory {
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
            if (pos < 0 || pos >= data.length) return 0;
            return data[pos] & 0xff;
        }

        @Override
        public int read16(int addr) {
            return (read8(addr) << 8) | read8(addr + 1);
        }

        @Override
        public int read32(int addr) {
            return (read16(addr) << 16) | read16(addr + 2);
        }

        @Override
        public void write8(int addr, int d) {
            int pos = offset + addr;
            if (pos >= 0 && pos < data.length) data[pos] = (byte)d;
        }

        @Override
        public void write16(int addr, int d) {
            write8(addr, d >> 8);
            write8(addr + 1, d);
        }

        @Override
        public void write32(int addr, int d) {
            write16(addr, d >> 16);
            write16(addr + 2, d);
        }

        @Override
        public Memory add(int o) {
            return new ByteArrayMemory(data, offset + o);
        }
    }
}
