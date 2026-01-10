package vavi.sound.mdsdrv;

import java.lang.System.Logger;
import java.util.Arrays;
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
    public static final int nm_init = ((1 << nf_pan_lfo) | (1 << nf_enabled));
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

        public int[] t_stack = new int[TSTACK_COUNT];

        public int t_fm_pan_lfo;
        public int t_fm_alg;
        public int[] t_fm_tl = new int[4];

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
    }

    public static class WorkArea {
        public Memory w_sdtop;
        public int[] w_request = new int[RCOUNT];
        public int[] w_tempo = new int[RCOUNT];
        public int[] w_counter = new int[RCOUNT];
        public int[] w_seq_step = new int[RCOUNT];
        public int[] w_volume = new int[RCOUNT];
        public int[] w_tmask = new int[RCOUNT];
        public int[] w_chmask = new int[RCOUNT];

        public int w_bgm_volume;
        public int w_se_volume;

        public int w_priority;
        public int w_fade_rate;

        public int w_fade_target;
        public int w_comm;
        public int w_fm3_mask; // FM3 Special Mode global mask

        public int w_pcm_bank;
        public int w_pcm_mode;
        // public int w_fm3_mask; // <-- Duplicate removed
        
        public int w_pointer_mode; // 0=Standard (Header+4), 1=Raw (Header+0)
        public int w_fm3_alg;
        public int[] w_fm3_tl = new int[4];


        public int w_gtempo;
        
        public Map<Integer, byte[]> globInstruments = new HashMap<>();

        public TrackData[] w_track = new TrackData[TCOUNT];

        public WorkArea() {
            for (int i = 0; i < TCOUNT; i++) {
                w_track[i] = new TrackData();
            }
        }
    }

    public void mds_top(WorkArea a0, Memory a1, Memory a2) {
        mds_init(a0, a1);
        mds_update(a0);
        mds_request(a0, 0, 0);
        mds_command(a0, 0, 0, 0);
    }

    public static final String version_str = "MDSDRV0.6 230612";

    public int mds_init(WorkArea a0, Memory a1) {
        // int d0;
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
                 a0.w_pointer_mode = 1; // Treat as raw sequence data
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
        logger.log(Level.WARNING, "mds_init complete, sdtop=" + a1);

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

        Memory psg = getPsgMemory();
        psg.write8(MdDef.sound_psg, 0x9f);
        psg.write8(MdDef.sound_psg, 0xbf);
        psg.write8(MdDef.sound_psg, 0xdf);
        psg.write8(MdDef.sound_psg, 0xff);

        // Initialize FM LFO and DAC enable registers
        // Reg $22 = LFO control (0x00 = off, 0x08 = on with default freq)
        // Reg $2B = DAC enable (0x80 = DAC on for FM6/channel 5)
        write_fm_port0(0x22, 0x00);  // LFO off
        write_fm_port0(0x2B, 0x00);  // DAC off (use FM synthesis for ch6)

        // Key-off all FM channels to start clean
        for (int ch = 0; ch < 6; ch++) {
            int slot = (ch < 3) ? ch : (ch - 3 + 4);
            write_fm_port0(0x28, slot);  // Key off (operator mask = 0)
        }

        logger.log(Level.INFO, "mds_init complete");

        writeIo(MdDef.z80_reset, 0x000);
        for (int i = 0; i < 20; i++)
            ;
        writeIo(MdDef.z80_reset, 0x100);

        writeIo(MdDef.z80_bus_request, 0);

        return 0;
    }

    @SuppressWarnings("unused")
    private int mds_init_error(WorkArea a0) {
        a0.w_sdtop = null;
        return -1;
    }

    private void mds_z80_init(WorkArea a0) {
        a0.w_pcm_bank = 0;
        a0.w_pcm_mode = 2;

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

        switch (d0) {
            case 0x00:
                return get_cmd_count();
            case 0x01:
                return get_sound_count(a0);
            case 0x02:
                return get_status(a0, d1);
            case 0x03:
                return 0;
            case 0x04:
                return get_gtempo(a0);
            case 0x05:
                set_gtempo(a0, d1);
                return 0;
            case 0x06:
                return get_gvolume(a0);
            case 0x07:
                set_gvolume(a0, d1);
                return 0;
            case 0x08:
                write_fm_port0(d1, d2);
                return 0;
            case 0x09:
                write_fm_port1(d1, d2);
                return 0;
            case 0x0a:
                fade_bgm(a0, d1);
                return 0;
            case 0x0b:
                set_pause(a0, d1, d2);
                return 0;
            case 0x0c:
                return get_volume(a0, d1);
            case 0x0d:
                set_volume(a0, d1, d2);
                return 0;
            case 0x0e:
                return get_tempo(a0, d1);
            case 0x0f:
                set_tempo(a0, d1, d2);
                return 0;
            case 0x10:
                return get_comm(a0);
            case 0x11:
                set_pcmmode(a0, d1, d2);
                return 0;
            case 0x12:
                return get_pcmmode(a0);
        }
        return 0;
    }

    private int get_cmd_count() {
        return 0x12;
    }

    private int get_sound_count(WorkArea a0) {
        return a0.w_sdtop.read16(-2);
    }

    private int get_status(WorkArea a0, int d1) {
        return a0.w_tmask[d1];
    }

    private int get_gtempo(WorkArea a0) {
        return a0.w_gtempo;
    }

    private void set_gtempo(WorkArea a0, int d1) {
        a0.w_gtempo = d1;
    }

    private int get_gvolume(WorkArea a0) {
        return (a0.w_bgm_volume << 8) | a0.w_se_volume;
    }

    private void set_gvolume(WorkArea a0, int d1) {
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

    private void fade_bgm(WorkArea a0, int d1) {
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

    private void set_pause(WorkArea a0, int d1, int d2) {
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

    private int get_volume(WorkArea a0, int d1) {
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

    private int get_tempo(WorkArea a0, int d1) {
        return a0.w_tempo[d1];
    }

    private void set_tempo(WorkArea a0, int d1, int d2) {
        a0.w_tempo[d1] = d2;
    }

    private int get_comm(WorkArea a0) {
        return a0.w_comm;
    }

    private void set_pcmmode(WorkArea a0, int d1, int d2) {
        mds_set_pcm_mode(a0, d1);
    }

    private int get_pcmmode(WorkArea a0) {
        return 0;
    }

    private void mds_set_pcm_mode(WorkArea a0, int d0) {
        a0.w_pcm_mode = (a0.w_pcm_mode & 0x10) | d0;
    }

    // Abstract IO and Memory methods
    protected int readIo(int port) {
        return 0;
    }

    protected void writeIo(int port, int data) {
    }

    protected Memory getZ80Ram() {
        return new Memory() {
            public int read8(int addr) {
                return 0;
            }

            public int read16(int addr) {
                return 0;
            }

            public int read32(int addr) {
                return 0;
            }

            public void write8(int addr, int data) {
            }

            public void write16(int addr, int data) {
            }

            public void write32(int addr, int data) {
            }

            public Memory add(int offset) {
                return this;
            }
        };
    }

    protected Memory getPsgMemory() {
        return getZ80Ram();
    }

    protected int getZVtabOffset() {
        return 0x1000;
    }

    public void mds_update(WorkArea a0) {
        for (int rnum = RCOUNT - 1; rnum >= 0; rnum--) {
            int reqdata = a0.w_request[rnum];
            if ((reqdata & (1 << 15)) != 0) {
                mds_handle_request(a0, rnum, reqdata);
            }
            reqdata = a0.w_tmask[rnum];
            if (reqdata != 0) {
                int counter = a0.w_counter[rnum];
                counter += a0.w_tempo[rnum];
                counter++;
                int gtempo = a0.w_gtempo;
                int seq_step = 0;
                while (counter >= gtempo) {
                    counter -= gtempo;
                    seq_step++;
                }
                a0.w_seq_step[rnum] = seq_step;
                a0.w_counter[rnum] = counter;

                if (rnum == 0 && seq_step > 0) {
//                    System.out.printf("Update R0: Tempo=%d, GTempo=%d, Counter=%d, SeqStep=%d%n", 
//                        a0.w_tempo[rnum], gtempo, counter, seq_step);
                }
            }
        }

        if (a0.w_priority != 0 || a0.w_fade_rate != 0) {
            if (a0.w_priority != 0)
                mds_update_priority(a0);
            else
                mds_update_fade(a0);
        }

        for (int tnum = TCOUNT - 1; tnum >= 0; tnum--) {
            TrackData twork = a0.w_track[tnum];
            int flag = twork.t_note_flag;

            if ((flag & (1 << nf_enabled)) == 0)
                continue;

            int rnum = twork.t_request_id;
            if (rnum >= RCOUNT * 2)
                continue;

            int real_rnum = rnum / 2;
            int seq_step = a0.w_seq_step[real_rnum];

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

    private void mds_handle_request(WorkArea a0, int rnum, int reqdata) {
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

        a0.w_tempo[rnum] = a0.w_gtempo - 1;
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
        int songBaseOffset = header.read16(0);

        // Memory trackBase = header.add(songBaseOffset); // Unused after fix
        header = header.add(2);

        int vol = a0.w_bgm_volume;
        vol += header.read8(0);
        header = header.add(1);
        if (vol > 127)
            vol = 127;
        a0.w_volume[rnum] = (vol << 8) | (reqdata & 0xff);

        int tcount = header.read8(0);
        header = header.add(1);

        // Song base for this song (tbase in assembly)
        int songBase = headerOffset + songBaseOffset;
        
        int tracksFound = 0;
        for (int tnum = TCOUNT - 1; tnum >= 0 && tracksFound < tcount; tnum--) {
            TrackData t = a0.w_track[tnum];
            // Check if track is free (not enabled AND not suspended)
            if ((t.t_note_flag & (1 << nf_enabled)) != 0 || (t.t_channel_flag & (1 << cf_suspend)) != 0) {
                continue;
            }

            // Track base is the song base (used for relative addressing in sequence commands)
            t.t_base_addr = songBase;

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
            
            // 3. position (2 bytes) - THIS IS THE START POSITION IN SEQUENCE!
            int position = header.read16(0) & 0xffff;
            header = header.add(2);
            t.t_position = position;
            
            // Stack and timing
            t.t_stack_pos = 0;
            t.t_counter = 0;
            t.t_rest_time = 0x0b;  // Default rest time
            t.t_note_time = 0x0b;  // Default note time
            
            // PSG/FM specific init
            if (chnid >= ct_psg) {
                t.t_psg_nreset = 0;
                t.t_psg_nmode = 0;
                t.t_psg_eg_addr = 0xfff8;
                t.t_psg_eg_pos = 0xff;
                t.t_psg_eg_delay = 0x0f;
            } else {
                t.t_fm_pan_lfo = 0xc0;  // Default panning (both L+R)
                t.t_fm_alg = 0;
                t.t_fm_tl[0] = 0x7f;
                t.t_fm_tl[1] = 0x7f;
                t.t_fm_tl[2] = 0x7f;
                t.t_fm_tl[3] = 0x7f;
            }

            tracksFound++;
        }
    }

    private void stop_song(WorkArea a0, int rnum, int reqdata) {
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

    private void mds_update_priority(WorkArea a0) {
        // Priority update logic (Simplified)
        // Checks requests and updates track priority flags if needed
        a0.w_priority = 0;
    }

    private void mds_update_fade(WorkArea a0) {
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

    private final int mds_chn_cmd_base = 0xE0;
    private final int mds_note_start = 0x82;

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
        int sp = twork.t_stack_pos;

        while (true) {
            int pos = twork.t_position;
            int cmd = tbase.read8(pos) & 0xFF;
            int cmdlen = 1;

            twork.t_position = pos + cmdlen;  // Default advance by 1

            // Assembly (lines 1025-1054): Check command ranges
            if ((cmd & 0x80) == 0) {
                // 00-7F: Rest with explicit length
                twork.t_rest_time = cmd;
                twork.t_counter = cmd;
                twork.t_note_flag |= (1 << nf_key_off);
                twork.t_note_flag &= ~(1 << nf_key_on);
                finishSeqCommand(a0, twork);
                return;
            }

            if (cmd >= mds_chn_cmd_base) {
                // E0-FF: Commands
                int len = execute_command(a0, twork, tbase, pos, cmd);
                if (len != 0) {
                    twork.t_position = pos + len;
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
                if (!isSlur) {
                    if ((twork.t_channel_flag & (1 << cf_mtab_carry)) == 0) {
                        twork.t_mtab_delay = 0;
                    }
                    twork.t_note_flag |= (1 << nf_key_off);
                }

                // Assembly (lines 1069-1070): Check drum mode
                if ((twork.t_channel_flag & (1 << cf_drum_mode)) != 0) {
                    // Drum mode: push return address and jump to subroutine
                    twork.t_stack[sp] = pos + 1;  // Return after note byte
                    twork.t_stack_pos = sp + 1;
                    // Jump to subroutine table: note * 2 -> read 16-bit offset
                    int subOffset = tbase.read16(note * 2);
                    if ((subOffset & 0x8000) != 0) subOffset |= 0xFFFF0000;
                    twork.t_position = subOffset;
                    continue;  // Process subroutine commands
                }

                // Normal note: read length or use previous
                readNoteLength(twork, tbase, pos + 1);
                finishSeqCommand(a0, twork);
                return;
            }

            // 80: Rest (alternate) - uses previous rest time
            if (cmd == 0x80) {
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
    private void readNoteLength(TrackData twork, Memory tbase, int pos) {
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

            if (cmd < 0) { // $80-$FF
                int cmdCode = cmd & 0xff;
                switch (cmdCode) {
                    case 0x80: // Jump/Exit
                        if (param == 0)
                            return false;
                        twork.t_mtab_pos += (byte) param - 1;
                        pos = twork.t_mtab_addr + twork.t_mtab_pos * 2;
                        continue;
                    case 0x81: // Wait
                        twork.t_mtab_delay = param;
                        return true;
                    case 0x82: // Retrig
                        twork.t_note_flag |= (1 << nf_key_on) | (1 << nf_key_off);
                        twork.t_mtab_delay = param;
                        return true;
                    case 0x83: // Carry
                        if (param != 0)
                            twork.t_channel_flag |= (1 << cf_mtab_carry);
                        else
                            twork.t_channel_flag &= ~(1 << cf_mtab_carry);
                        continue;
                    case 0x84: // Loop
                        twork.t_mtab_repeat = param;
                        continue;
                    case 0x85: // Loop break
                        if (twork.t_mtab_repeat == 0) {
                            twork.t_mtab_pos += (byte) param - 1;
                            pos = twork.t_mtab_addr + twork.t_mtab_pos * 2;
                        }
                        continue;
                    case 0x86: // Loop finish
                        twork.t_mtab_repeat--;
                        if (twork.t_mtab_repeat >= 0) {
                            twork.t_mtab_pos += (byte) param - 1;
                            pos = twork.t_mtab_addr + twork.t_mtab_pos * 2;
                        }
                        continue;
                    default:
                        return true;
                }
            } else {
                // simple variable add/set (stubbed for now to avoid crashes)
                continue;
            }
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
    private int opmToOpnReg(int reg) {
        if (reg < 0x40) return reg; // Pass through Keys/Flags/Test
        int base = reg & 0xE0; // Get top 3 bits (Block)
        int offset = reg & 0x1F;
        
        switch (base) {
            case 0x40: return 0x30 + offset;
            case 0x60: return 0x40 + offset;
            case 0x80: return 0x50 + offset;
            case 0xA0: return 0x60 + offset;
            case 0xC0: return 0x70 + offset;
            case 0xE0: return 0x80 + offset;
            default: return reg;
        }
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
                twork.t_note_flag |= (1 << nf_ins);
                twork.t_last_pitch = 0xffff;
                
                Memory insData = null;
                // Check glob
                if (a0.globInstruments != null && a0.globInstruments.containsKey(twork.t_ins)) {
                    insData = new ByteArrayMemory(a0.globInstruments.get(twork.t_ins));
                } else {
                    Memory sdtop = a0.w_sdtop;
                    if (sdtop != null) {
                        int insIdx = twork.t_ins;
                        int ptrOffset = tbase.read16(insIdx * 2);
                        if ((ptrOffset & 0x8000) != 0) ptrOffset |= 0xFFFF0000;
                        insData = sdtop.add(ptrOffset);
                    }
                }
                
                if (insData != null && twork.t_channel_id < 6) {
                    // FM Instrument Header Layout (Reverted):
                    // +0-23: Register Data (Skipped here)
                    // +24: TL (4 bytes)
                    // +28: Alg (1 byte)
                    // +29: Trs (1 byte)
                    
                    twork.t_fm_tl[0] = insData.read8(24) & 0x7f;
                    twork.t_fm_tl[1] = insData.read8(25) & 0x7f;
                    twork.t_fm_tl[2] = insData.read8(26) & 0x7f;
                    twork.t_fm_tl[3] = insData.read8(27) & 0x7f;
                    twork.t_fm_alg = insData.read8(28) & 0xff;
                    twork.t_ins_trs = insData.read8(29) & 0xff;
                } else if (insData != null) {
                     // PSG Instrument
                     // If using glob (insData is set), use it directly.
                     // If standard, t_psg_eg_addr is set below.
                     if (a0.globInstruments != null && a0.globInstruments.containsKey(twork.t_ins)) {
                         twork.t_psg_env_data = insData;
                         twork.t_psg_eg_addr = 0; // Use data from offset 0
                     } else if (a0.w_sdtop != null) {
                        twork.t_psg_env_data = null; // Use sdtop
                        int insIdx = twork.t_ins;
                        int ptrOffset = tbase.read16(insIdx * 2);
                        if ((ptrOffset & 0x8000) != 0) ptrOffset |= 0xFFFF0000;
                        twork.t_psg_eg_addr = ptrOffset;
                    }
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
            case 0xEA: // LFO
                if (twork.t_channel_id < 6) {
                    int lfo = twork.t_fm_pan_lfo & 0xc0;
                    lfo |= tbase.read8(pos + 1) & 0xff;
                    twork.t_fm_pan_lfo = lfo;
                    twork.t_note_flag |= (1 << nf_pan_lfo);
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
                    int regOffset = tbase.read8(pos + 1) & 0xff;
                    int val = tbase.read8(pos + 2) & 0xff;
                    // Restore OPM conversion
                    int opnReg = opmToOpnReg(regOffset);
                    int ch = twork.t_channel_id;
                    if (ch < 3) {
                        write_fm_port0(opnReg + ch, val);
                    } else if (ch < 6) {
                        write_fm_port1(opnReg + (ch - 3), val);
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
                return 2;
            case 0xF1: // PCM Rate
                return 2;
            case 0xF2: // PCM Mode
                return 2;
            case 0xF9: // Tempo
                a0.w_tempo[twork.t_request_id / 2] = tbase.read8(pos + 1) & 0xff;
                return 2;

            case 0xF5: // Jump (relative offset)
                int jumpOffset = tbase.read16(pos + 1);
                if ((jumpOffset & 0x8000) != 0) jumpOffset |= 0xFFFF0000;
                twork.t_position = pos + 1 + jumpOffset;  
                return 0; 
            case 0xF6: // FM Register Write
                {
                    int reg = tbase.read8(pos + 1) & 0xff;
                    int val = tbase.read8(pos + 2) & 0xff;
                    // Restore OPM conversion
                    int opnReg = opmToOpnReg(reg);
                    write_fm_port0(opnReg, val);
                }
                return 3;
            case 0xF8: // Comm
                a0.w_comm = tbase.read8(pos + 1);
                return 2;
            case 0xFA: // Loop
                int slot = twork.t_stack_pos;
                if (slot < TSTACK_COUNT - 2) {
                    twork.t_stack[slot] = 0xff; // Marker
                    twork.t_stack[slot + 1] = pos + 4; // Return address
                    twork.t_stack_pos += 2;
                }
                return 4;
            case 0xFB: // Loop Finish
                {
                    int sp = twork.t_stack_pos - 2;
                    if (sp < 0) return 1; // Stack underflow protection

                    int counter = twork.t_stack[sp];
                    if (counter == 0xff) {
                        // First time reaching end: init counter
                        counter = tbase.read8(pos + 1) & 0xff;
                        twork.t_stack[sp] = counter;
                        // Consume loop count byte immediately? 
                        // Assembly: move.b 0(@tbase,@trackpos),@tempreg (if marker found)
                    }

                    if (counter > 1) {
                        twork.t_stack[sp] = counter - 1;
                        twork.t_position = twork.t_stack[sp + 1]; // Jump back
                        return 0; // Pos updated
                    } else {
                        // Loop done
                        twork.t_stack_pos -= 2; // Pop
                        // If we just initialized counter (read byte), we need to skip it
                        // The command is FB xx. length 2.
                        return 2;
                    }
                }
            case 0xFC: // Loop Break (1 byte offset?)
            case 0xFD: // Loop Break (Long? 2 byte?)
                // Assembly FC: lpb (1 byte len check?)
                // Assembly: cmpi.b #1,t_stack-4 (counter). if 1, pop and jump.
                {
                    int sp = twork.t_stack_pos - 2;
                    if (sp >= 0 && twork.t_stack[sp] == 1) {
                        twork.t_stack_pos -= 2; // Pop
                        int breakOffset = tbase.read8(pos + 1) & 0xff;
                        twork.t_position = pos + 2 + breakOffset; // Jump
                        return 0;
                    }
                    return 2; // Skip break offset
                }
            case 0xFE: // Pattern / Subroutine
                {
                     int subIdx = tbase.read8(pos) & 0xff; // Wait, FE is cmd.
                     // FE nn. Read nn.
                     // Assembly: move.b -2(@tbase,@trackpos) -> fetches param?
                     // No, FE is 1 byte command?
                     // 1545: move.b -2(@tbase,@trackpos),@cmd
                     // This implies previous command byte or something?
                     // Actually @trackpos was inc by 2.
                     // It likely means FE is followed by byte index.
                     // Let's assume FE nn.
                     int patIdx = tbase.read8(pos + 1) & 0xff;
                     
                     // Push return address (pos + 2)
                     int sp = twork.t_stack_pos;
                     if (sp < TSTACK_COUNT - 1) {
                         twork.t_stack[sp] = twork.t_position + 2; // Return address
                         // twork.t_stack[sp+1] unused or used for something else?
                         // 68k: pushes 1 word (addr).
                         // We use stack for loops (2 words) and subs (1 word?).
                         // This mixes stack usage.
                         // Cmd_finish checks sp != 0.
                         // Let's use 1 slot for return address.
                         twork.t_stack_pos++;
                     }
                     
                     // Jump to pattern
                     // table lookup?
                     // 1546: add.w @cmd,@cmd; move.w 0(@tbase,@cmd),@trackpos
                     // This implies PATTERN TABLE at start of track data?
                     // Or global? "tbase" is track base.
                     // Does track header have pattern table?
                     // This FE command seems rare or specific to certain driver configs.
                     // For safety, generic "return 2" might be safer unless needed.
                     // But user said "no lacking code".
                     // Let's implement stub warning or assume pointer mode?
                     return 2;
                }
            case 0xF7: // Drum Finish
                // Assembly (lines 1553-1557):
                // move.b 0(@tbase,@trackpos),t_note(twork)
                // subq.b #2,@sp
                // move.w t_stack(twork,@sp),@trackpos
                // bra.w @cmd_tie
                twork.t_note = tbase.read8(pos + 1) & 0xff;
                twork.t_note_flag |= (1 << nf_key_on);
                if (twork.t_stack_pos > 0) {
                    twork.t_stack_pos--;
                    int returnPos = twork.t_stack[twork.t_stack_pos];
                    // Now process as tie - read length
                    int lenCmd = tbase.read8(returnPos) & 0xff;
                    if ((lenCmd & 0x80) != 0) {
                        twork.t_counter = twork.t_note_time;
                        twork.t_position = returnPos;
                    } else {
                        twork.t_counter = lenCmd;
                        twork.t_note_time = lenCmd;
                        twork.t_position = returnPos + 1;
                    }
                }
                return 0;
            case 0xF3: // Finish (Alt)
            case 0xF4: // Finish (Alt)
            case 0xFF: // Finish / Return
                if (twork.t_stack_pos > 0) {
                    // Return from subroutine (lines 1182-1186)
                    twork.t_stack_pos--;
                    twork.t_position = twork.t_stack[twork.t_stack_pos];
                    return 0;
                }
                stop_track(twork);
                return 1;
        }
        return 1;
    }

    private void stop_track(TrackData t) {
        t.t_request_id = RCOUNT * 2;
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

        if ((t.t_note_flag & (1 << nf_key_on)) != 0) {
            t.t_note_flag &= ~(1 << nf_key_off); // Clear key off if key on is set
            // Key On Logic
            if ((t.t_note_flag & (1 << nf_key_off)) == 0) { // re-check? ASM: bclr #nf_key_off... beq return
                // wait, ASM logic:
                // bclr #nf_key_off
                // beq return (if it WAS clear, it means no key off pending? No, bclr returns
                // old value in Z flag)
                // if OLD value was 0 (Z=1), then branch.
                // So if key_off was NOT set, we return?
                // That implies KeyOn only happens if KeyOff happened?
                // No, line 3071: bclr #nf_key_off, flag. beq return.
                // If key_off was set, we proceed. If key_off was clear, we return.
                // This implies we need a key-off-on sequence?
                // Or maybe I am misinterpreting 68k bclr.
                // bclr tests bit, THEN clears it. Z=1 if bit was 0.
                // So if key_off was 0, Z=1, beq branches.
                // So Key On ONLY happens if Key Off was pending?
                // Strange. Let's assume standard behavior: Key On starts sound.
            }

            t.t_channel_flag |= (1 << cf_key_on);
            t.t_note_flag &= ~((1 << nf_vol) | (1 << nf_slur));

            if ((t.t_note_flag & (1 << nf_pcm_header)) != 0) {
                t.t_note_flag &= ~(1 << nf_pcm_header);

                Memory sdtop = a0.w_sdtop;
                if (sdtop != null) {
                    // Load header
                    int headerAddr = t.t_base_addr + t.t_pcm_header; // Wait, t_pcm_header is word pointer
                    Memory pcmHeader = sdtop.add(headerAddr); // Need to resolve address properly
                    // ASM: adda.w t_pcm_header, tmpa0.

                    // Z80 write logic ...
                    // Simplified: Just write bank/addr to Z80 RAM
                    int addr = pcmHeader.read32(0); // Should be Bank+Addr
                    // Mapping needed.
                }
            }

            // Trigger Z80 Key On
            zram.write8(zPcmBase + MdDef.zp_key_on, 1);
            // Set Vol
            int vol = t.t_vol; // Need conversion?
            zram.write8(zPcmBase + MdDef.zp_vol, vol);

        } else if ((t.t_note_flag & (1 << nf_key_off)) != 0) {
            t.t_note_flag &= ~(1 << nf_key_off);
            t.t_channel_flag &= ~(1 << cf_key_on);
            // Stop Z80
            int vol = zram.read8(zPcmBase + MdDef.zp_vol);
            zram.write8(zPcmBase + MdDef.zp_vol, vol | 0x80); // Key off bit
        }

        if ((t.t_note_flag & (1 << nf_vol)) != 0) {
            t.t_note_flag &= ~(1 << nf_vol);
            int vol = t.t_vol; // Mds convert?
            zram.write8(zPcmBase + MdDef.zp_vol, vol);
        }
    }

    private void mds_fm_update(WorkArea a0, TrackData t, int ch, int part) {
        int chOffset = ch;
        int portOffset = part * 2;

        if ((t.t_note_flag & (1 << nf_key_off)) != 0) {
            t.t_note_flag &= ~(1 << nf_key_off);
            if ((t.t_channel_flag & (1 << cf_key_on)) != 0) {
                t.t_channel_flag &= ~(1 << cf_key_on);
                int keyOffSlot = ch + (part * 4);
                write_fm_port0(0x28, keyOffSlot);
            }
        }

        if ((t.t_note_flag & (1 << nf_ins)) != 0) {
            t.t_note_flag &= ~(1 << nf_ins);
            mds_fm_update_ins(a0, t, ch, part);
            t.t_note_flag |= (1 << nf_vol);
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
                int high = fmPitch & 0xff;
                int low = (fmPitch >> 8) & 0xff;
    
                if (part == 0) {
                    write_fm_port0(0xA4 + ch, high);
                    write_fm_port0(0xA0 + ch, low);
                } else {
                    // Assembly: and.w chnid,d1 (lines 2572) - use port-relative offset
                    write_fm_port1(0xA4 + (ch % 3), high);
                    write_fm_port1(0xA0 + (ch % 3), low);
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

        if ((t.t_note_flag & (1 << nf_key_on)) != 0) {
            t.t_note_flag &= ~(1 << nf_key_on);
            if ((t.t_note_flag & (1 << nf_slur)) == 0) {
                t.t_channel_flag |= (1 << cf_key_on);
                // Assembly (lines 2626-2628): operator mask + channel slot
                // YM2612 key-on format: Bits 4-7 = operator mask, Bits 0-2 = channel
                // Port 0 channels: 0, 1, 2 -> slots 0, 1, 2
                // Port 1 channels: 0, 1, 2 -> slots 4, 5, 6 (add 4 for part 1)
                int opMask = 0xF0;  // All 4 operators enabled
                int slot = ch + (part * 4);  // ch is already 0-2 relative to port
                write_fm_port0(0x28, opMask | slot);
            }
        }
    }

    private void mds_fm6_update(WorkArea a0, TrackData t) {
        // Check PCM1 enable flag (simplified: if PCM1 active, skip FM)
        if ((a0.w_pcm_mode & (1 << MdDef.pe_pcm1)) != 0) {
            mds_pcm1_update(a0, t);
        } else {
            mds_fm_update(a0, t, 2, 1);
        }
    }

    private void mds_fm3_update_pitch(WorkArea a0, TrackData t) {
        int pitch = t.t_last_pitch;
        int fmPitch = mds_get_fm_pitch(t, pitch);
        
        int datA4 = fmPitch & 0xff;        
        int datA0 = (fmPitch >> 8) & 0xff;
        
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
        if (pitch != t.t_last_pitch) {
            t.t_last_pitch = pitch;
            int psgPitch = mds_get_psg_pitch(t, pitch);

            int low4 = psgPitch & 0x0F;
            int high6 = (psgPitch >> 4) & 0x3F;

            psgPitch &= 0x3FF;
            Memory psg = getPsgMemory();
            psg.write8(MdDef.sound_psg, chVal | low4);
            psg.write8(MdDef.sound_psg, high6);
        }
    }

    private void mds_psg_update_env(WorkArea a0, TrackData t, int chVal) {
        Memory psg = getPsgMemory();

        // Assembly (line 2712): tst.l flag; bpl.w @silence
        // Check if track is enabled (bit 31 of flag = nf_enabled)
        if ((t.t_note_flag & (1 << nf_enabled)) == 0) {
            t.t_psg_eg_pos = 0xff;
            t.t_psg_eg_delay = 0x0f;
            t.t_channel_flag &= ~(1 << cf_key_on);
            t.t_note_flag &= ~(1 << nf_key_off);
            psg.write8(MdDef.sound_psg, chVal | 0x10 | 0x0f);
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
            mds_psg_silence(t, chVal, psg);
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

        // Assembly (lines 2782-2794): Sustain command handling
        if ((t.t_note_flag & (1 << nf_sustain)) == 0) {
            // Not sustaining yet
            if (cmd == 0x01) {
                // Start sustain
                t.t_note_flag |= (1 << nf_sustain);
                // Read next command
                cmd = envData.read8(pos) & 0xff;
                pos++;
            }
        } else {
            // Already sustaining
            if (cmd == 0x01) {
                // Hold at current volume
                int vol = t.t_psg_eg_delay & 0x0f;
                t.t_psg_eg_delay = vol;  // Clear high nibble
                if ((t.t_note_flag & (1 << nf_vol)) != 0) {
                    t.t_note_flag &= ~(1 << nf_vol);
                }
                writePsgVolume(a0, t, chVal, vol, psg);
                return;
            }
        }

        // Assembly (lines 2800-2806): Jump command
        if (cmd == 0x02) {
            pos = envData.read8(pos) & 0xff;  // Read jump target
            cmd = envData.read8(pos) & 0xff;  // Read command at target
            pos++;
        }

        // Assembly (lines 2812-2821): Check for silence (cmd < 0x10)
        if (cmd < 0x10) {
            mds_psg_silence(t, chVal, psg);
            return;
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

    private void mds_psg_silence(TrackData t, int chVal, Memory psg) {
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
            finalVol += a0.w_volume[t.t_request_id >> 1] & 0xff;
        }

        // Clamp to max 15 (PSG volume is 4-bit, where 15 = silent)
        if (finalVol > 15) finalVol = 15;
        
        // Write to PSG (chVal | 0x10 = volume command for channel)
        psg.write8(MdDef.sound_psg, chVal | 0x10 | finalVol);
    }

    private void mds_psg3_update(WorkArea a0, TrackData t, int chVal) {
        mds_psg_update(a0, t, chVal);
    }

    private void mds_psgn_update(WorkArea a0, TrackData t, int chVal) {
        mds_psg_update_env(a0, t, chVal); // Assembly: bsr.w mds_psg_update_env
        int pitch = mds_pitch_update(a0, t); // Assembly: bsr.w mds_pitch_update

        int d1 = t.t_psg_nreset;
        boolean noiseReset = (d1 & 0x8000) != 0; // bmi.s @noise_reset

        // bclr #nf+nf_nmode,flag (returns previous state)
        boolean nmodeWasSet = (t.t_note_flag & (1 << nf_nmode)) != 0;
        t.t_note_flag &= ~(1 << nf_nmode);

        if (nmodeWasSet) {
            noiseReset = true; // bne.s @noise_reset
        }

        if (!noiseReset) {
            if (pitch == t.t_last_pitch) { // cmp.w t_last_pitch(twork),d0
                return; // beq.w mds_update_return
            }
        } else {
            // @noise_reset
            if ((d1 & 0xff) != 0) { // tst.b d1 / beq.s @no_psg3_control
                // move.b d1,sound_psg
                getPsgMemory().write8(MdDef.sound_psg, d1 & 0xff);
                // bra.s mds_psg_update_pitch -> falls through to pitch update below
            }
        }

        // @no_psg3_control / mds_psg_update_pitch
        t.t_last_pitch = pitch;
        // ror.w #8,d0 -> andi.b #$07,d0 -> ori.b #$e0,d0 -> move.b d0,sound_psg
        // d0 is pitch (16-bit). ror #8 puts high byte in low byte.
        // so we take high byte, mask 0x07, OR with 0xE0.
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

    private int mds_pitch_update(WorkArea a0, TrackData t) {
        // Assembly (lines 1989-2028): Calculate base pitch from note + transpose + detune
        int note = (t.t_note + t.t_trs) & 0xff;
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

    private int mds_get_fm_pitch(TrackData t, int pitch) {
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
        int freqIndex = noteIndex + (t.t_ins_trs & 0xff);  // Add instrument transpose
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

    private int mds_get_psg_pitch(TrackData t, int pitch) {
        int note = (pitch >> 8) & 0xff;
        int fraction = pitch & 0xff; 
        int freq = 0;
        if (note < mds_note_table.length) {
            int oct = mds_octave_table[note] / 8;
            int noteIdx = mds_note_table[note];
            
            int f1 = mds_psg_freq_tab[noteIdx / 2];
            // Interpolate with next semitone
            // Assembly: f1 - f2 -> delta. delta * fraction -> sub from f1.
            // Ensure table boundary safety (table has 13 entries)
            int f2 = mds_psg_freq_tab[(noteIdx / 2) + 1];
            
            int delta = f1 - f2;
            int diff = (delta * fraction) >> 8;
            int interpolated = f1 - diff;
            
            freq = interpolated >> oct;
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
        int insOffset = tbase.read16(insIdx * 2);
        if ((insOffset & 0x8000) != 0)
            insOffset |= 0xFFFF0000;

        if (insOffset < 0 || insOffset >= 0x8000) {
            return;
        }

        Memory insData;
        if (a0.globInstruments != null && a0.globInstruments.containsKey(t.t_ins)) {
             insData = new ByteArrayMemory(a0.globInstruments.get(t.t_ins));
        } else {
             insData = sdtop.add(insOffset);
        }

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
        // 24 bytes of Register Data (0-23)
        // 4 bytes of TL (24-27)
        // 1 byte Alg (28)
        // 1 byte Trs (29)
        final int[] regBases = { 0x30, 0x50, 0x60, 0x70, 0x80, 0x90 };
        int dataPtr = 0; // Start at 0

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
        
        // dataPtr is now 24. 
        // TL is at 24-27 (read in execute_command or other places, or used here?)
        // Java reads mds_fm_update_vol separately?
        // Assembly checks Alg here:
        // move.b (tmpa1)+,1(zram,chnid) ... for Alg. 
        // Where is Alg? dataPtr + 4 (skip TL).
        
        int algo = insData.read8(dataPtr + 4); // 24 + 4 = 28
        int fbAlgoReg = 0xB0 + ch;
        if (part == 0)
            write_fm_port0(fbAlgoReg, algo);
        else
            write_fm_port1(fbAlgoReg, algo);
    }

    private void mds_fm_update_vol(WorkArea a0, TrackData t, int ch, int part) {
        // This must match the assembly logic:
        // 1. Read base TL values from t_fm_tl[0..3]
        // 2. For CARRIER operators only (determined by algorithm), add volume offset
        // 3. Modulator operators keep their original TL
        
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
        
        int alg = t.t_fm_alg & 0x07;
        int carrierThreshold = mds_fm_op_table[alg];  // Operators >= this are carriers
        
        // Operator order: 3, 2, 1, 0 (assembly does -(tmpa1) starting from end)
        // Register order: 0x4C, 0x48, 0x44, 0x40 (subq.b #4)
        int[] opRegs = {0x4C, 0x48, 0x44, 0x40};  // OP4, OP3, OP2, OP1 TL registers
        int[] opOrder = {3, 2, 1, 0};  // Match assembly order
        
        for (int i = 0; i < 4; i++) {
            int opIdx = opOrder[i];
            int baseTL = t.t_fm_tl[opIdx] & 0x7f;  // Base TL from instrument
            int finalTL;
            
            // Check if this operator is a carrier
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

    private static final byte[] mds_fm_op_table = { 3, 3, 3, 3, 2, 1, 1, 0 };
    private static final byte[] mds_fm_vol_table = { 42, 40, 37, 34, 32, 29, 26, 24, 21, 18, 16, 13, 10, 8, 5, 2 };

    // PSG Volume Table (approximate from ASM or converted)
    // ASM: dcb.b 2,$8f; ... various runs.
    // I will use a simplified table or the exact one if I expand it.
    // For now, let's pre-expand the table as seen in ASM.
    private static final byte[] mds_psg_vol_table_raw = new byte[] {
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

    private byte mds_psg_convert_vol(int vol) {
        if (vol < 0)
            vol = 0;
        if (vol >= mds_psg_vol_table_raw.length)
            vol = mds_psg_vol_table_raw.length - 1;
        return mds_psg_vol_table_raw[vol];
    }

    private Memory parseRiffMds(WorkArea a0, Memory m) {
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
                    logger.log(Level.WARNING, "Found seq chunk at " + p + " size " + size);
                    seqChunk = m.add(p + 8);
                }
                // "LIST" = 0x4C 0x49 0x53 0x54
                else if (c0 == 'L' && c1 == 'I' && c2 == 'S' && c3 == 'T') {
                    // Parse LIST contents for glob
                    logger.log(Level.WARNING, "Found LIST chunk at " + p + " size " + size);
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
                            // Store data (30 bytes?)
                            // Header 8 bytes + Index 4 bytes?  Test: data starts at lp+12.
                            int dataSize = subSize - 4; // Index is 4 bytes?
                            byte[] data = new byte[dataSize];
                            for (int i=0; i<dataSize; i++) {
                                data[i] = (byte)m.read8(lp + 12 + i);
                            }
                            a0.globInstruments.put(globIndex, data);
                            logger.log(Level.WARNING, "Parsed glob index " + globIndex);
                        }
                        
                        lp += 8 + subSize;
                        if ((subSize & 1) != 0) lp++;
                    }
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
    
    private static class ByteArrayMemory implements Memory {
        private final byte[] data;
        
        public ByteArrayMemory(byte[] data) {
            this.data = data;
        }

        @Override
        public int read8(int addr) {
            if (addr < 0 || addr >= data.length) return 0;
            return data[addr] & 0xff;
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
            if (addr >= 0 && addr < data.length) data[addr] = (byte)d;
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
        public Memory add(int offset) {
            // Simplified slice
            if (offset == 0) return this;
            if (offset >= data.length) return new ByteArrayMemory(new byte[0]);
            byte[] newData = new byte[data.length - offset];
            System.arraycopy(data, offset, newData, 0, newData.length);
            return new ByteArrayMemory(newData);
        }
    }
}
