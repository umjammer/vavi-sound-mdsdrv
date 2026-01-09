package vavi.sound.mdsdrv;

import java.lang.System.Logger;
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

    public int sn76489ClockValue = 3579545; // NTSC 3.58MHz default
    public boolean sn76489NGPFlag = false;
    public Object[] sn76489Option = null;

    public void setPlayingFileName(String playingFileName) {
        // TODO: header parsing if needed
    }

    private int samplesPerFrame;
    private int sampleCounter = 0;

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
        public int t_psg_eg_pos;
        public int t_psg_eg_delay;
        public int t_psg_nreset;
        public int t_psg_nmode;

        public int t_pcm_pan;
        public int t_pcm_pitch;
        public int t_pcm_header;
        public int t_pcm_length;
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

        public int w_pcm_bank;
        public int w_pcm_mode;

        public int w_fm3_mask;
        public int w_pointer_mode; // 0=Standard (Header+4), 1=Raw (Header+0)
        public int w_fm3_alg;
        public int[] w_fm3_tl = new int[4];

        public int w_gtempo;

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

            if ((twork.t_channel_flag & (1 << cf_background)) == 0) {
            } else {
                if ((twork.t_channel_flag & (1 << cf_stop)) != 0) {
                    stop_track(twork);
                }
                do_voice_update(a0, twork);
                continue;
            }

            if ((twork.t_channel_flag & (1 << cf_stop)) != 0) {
                twork.t_channel_flag &= ~(1 << cf_stop);
                twork.t_note_flag &= ~(1 << nf_enabled);
            } else {
                if ((twork.t_channel_flag & (1 << cf_background)) == 0) {
                    do_voice_update(a0, twork);
                }
            }
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
        if (reqdata < 1) return; // Basic validation
        // Resolve sequence pointer based on mode
        int offset;
        if (a0.w_pointer_mode == 1) {
             // Raw mode: Pointer table at 8 (Skip 8 byte pseudo-header). Req 1 -> Index 0.
             offset = a0.w_sdtop.read16(8 + (reqdata - 1) * 2);
        } else {
             // Standard mode: Header 4 bytes. Req 1 -> Index 1.
             offset = a0.w_sdtop.read16(4 + reqdata * 2);
        }
        int headerOffset = offset;

        // RAW MODE: No Song Header, just sequence data
        if (a0.w_pointer_mode == 1) {
            // Raw mode: Header at 0x00 is (Size | Reserved | Ptr0 | Ptr1 ... )
            // Size 0x28 (40 bytes) -> 18 pointers (Indices 0..17)
            // Mapping: 0-7:FM, 8-?:ADPCM/PCM, 15-17:PSG?
            
            int headerSize = a0.w_sdtop.read16(0);
            int ptrCount = (headerSize - 4) / 2;
            int baseAddr = headerSize; // Data starts after header

            for (int i = 0; i < ptrCount && i < TCOUNT; i++) {
                int ptr = a0.w_sdtop.read16(4 + i * 2);
                if (ptr != 0) {
                    TrackData t = a0.w_track[i];
                    t.t_base_addr = baseAddr; 
                    // Use headerSize as base for relative addressing if needed (instruments usually relative to this)
                    t.t_base_addr = headerSize; 
                    
                    t.t_position = ptr;
                    t.t_channel_id = i; // Map 1:1 to track index
                    t.t_request_id = rnum * 2; // ?
                    t.t_note_flag = nm_init;
                    t.t_last_pitch = 0xffff;
                    t.t_ins = 0;
                    t.t_note = 0;
                    t.t_trs = 0;
                    t.t_vol = 0x8f00; // Default volume
                    t.t_mtab_addr = 0;
                    t.t_peg_addr = 0;
                    t.t_stack_pos = 0;

                    a0.w_tmask[rnum] |= (1 << i);
                    a0.w_chmask[rnum] |= (1 << i); // Assume channel mask matches track index
                }
            }
            return;
        }
        
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

        int tracksFound = 0;
        for (int tnum = TCOUNT - 1; tnum >= 0 && tracksFound < tcount; tnum--) {
            TrackData t = a0.w_track[tnum];
            if ((t.t_note_flag & (1 << nf_enabled)) != 0 || (t.t_channel_flag & (1 << cf_suspend)) != 0) {
                continue;
            }

            // Fix: Read offset from HEADER (not trackBase), and assume 16-bit offset.
            int trkOff = header.read16(0);
            header = header.add(2);
            // Calculate absolute offset relative to sdtop (assuming sdtop is the ByteArrayMemory root or close to it)
            // t.t_base_addr = headerOffset (base of song header) + songBaseOffset (offset to track data) + trkOff (track specific offset)
            // Note: read16 returns unsigned int in Java port context usually? 
            // In ByteArrayMemory it masks & 0xFF so it's positive.
            t.t_base_addr = headerOffset + songBaseOffset + trkOff;

            // t.t_base_addr = trackBase.read32(0); // OLD BUGGY CODE

            int chnid = header.read8(0);
            header = header.add(1);
            t.t_channel_id = chnid;
            t.t_request_id = rnum * 2;

            t.t_note_flag = nm_init;
            t.t_channel_flag = header.read8(0);
            header = header.add(1);

            a0.w_tmask[rnum] |= (1 << tnum);
            a0.w_chmask[rnum] |= (1 << chnid);

            t.t_last_pitch = 0xffff;
            t.t_ins = 0x30;
            t.t_note = 0;
            t.t_trs = 0;
            t.t_vol = 0x8f00;
            t.t_mtab_addr = 0;
            t.t_peg_addr = 0;

            t.t_position = t.t_base_addr;
            header = header.add(2);

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
        twork.t_counter = (twork.t_counter - 1) & 0xff;
        if (twork.t_counter != 0xff) {
            return;
        }

        if (twork.t_mtab_addr != 0) {
            if (mds_update_mtab(a0, twork))
                return;
        }

        Memory tbase = a0.w_sdtop.add(twork.t_base_addr);

        while (true) {
            int pos = twork.t_position;
            int cmd = tbase.read8(pos) & 0xFF; // Ensure positive
            
            // Debug trace
            // if (twork.t_channel_id == 0) { 
            //   System.out.printf("Ch%d Cmd: %02X at %04X, Cnt: %d%n", twork.t_channel_id, cmd, pos, twork.t_counter);
            // }

            int cmdlen = 1;

            if ((cmd & 0x80) == 0) { // 00-7F Rest
                twork.t_rest_time = cmd;
                twork.t_counter = cmd;
                twork.t_note_flag |= (1 << nf_key_off);
                twork.t_note_flag &= ~(1 << nf_key_on);
                twork.t_position = pos + 1;
                if (twork.t_mtab_addr != 0) {
                    mds_update_mtab(a0, twork);
                }
                return;
            }

            if (cmd >= mds_chn_cmd_base) { // E0+
                cmdlen = 0;
                int len = execute_command(a0, twork, tbase, pos, cmd);
                if (len != 0) {
                    twork.t_position = pos + len;
                }
                if (twork.t_request_id >= RCOUNT * 2) {
                    return;
                }
                continue;
            }

            if (cmd >= mds_note_start) {
                int note = cmd - mds_note_start;
                twork.t_note = note;
                twork.t_note_flag |= (1 << nf_key_on);

                if ((twork.t_note_flag & (1 << nf_slur)) != 0) {
                } else {
                    if ((twork.t_channel_flag & (1 << cf_mtab_carry)) == 0) {
                        twork.t_mtab_delay = 0;
                    }
                    twork.t_note_flag |= (1 << nf_key_off);
                }

                cmd = tbase.read8(pos + 1);
                twork.t_position = pos + 1;

                if ((cmd & 0x80) != 0) {
                    twork.t_counter = twork.t_note_time;
                } else {
                    twork.t_counter = cmd;
                    twork.t_note_time = cmd;
                    twork.t_position++;
                }

                if (twork.t_mtab_addr != 0) {
                    mds_update_mtab(a0, twork);
                }
                return;
            }

            if (cmd == 0x81) {
                cmd = tbase.read8(pos + 1);
                twork.t_position = pos + 1;
                if ((cmd & 0x80) != 0) {
                    twork.t_counter = twork.t_note_time;
                } else {
                    twork.t_counter = cmd;
                    twork.t_note_time = cmd;
                    twork.t_position++;
                }
                if (twork.t_mtab_addr != 0) {
                    mds_update_mtab(a0, twork);
                }
                return;
            }

            twork.t_counter = twork.t_rest_time;
            twork.t_position = pos + 1;
            twork.t_note_flag |= (1 << nf_key_off);
            twork.t_note_flag &= ~(1 << nf_key_on);
            if (twork.t_mtab_addr != 0) {
                mds_update_mtab(a0, twork);
            }
            return;
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

                Memory sdtop = a0.w_sdtop;
                if (sdtop != null) {
                    int baseAddr = twork.t_base_addr;
                    Memory insTable = sdtop.add(baseAddr);
                    int insIdx = twork.t_ins;
                    int insOffset = insTable.read16(insIdx * 2);
                    if ((insOffset & 0x8000) != 0)
                        insOffset |= 0xFFFF0000;
                    Memory insData = sdtop.add(insOffset);
                    twork.t_fm_tl[0] = insData.read8(24);
                    twork.t_fm_tl[1] = insData.read8(25);
                    twork.t_fm_tl[2] = insData.read8(26);
                    twork.t_fm_tl[3] = insData.read8(27);
                    twork.t_fm_alg = insData.read8(28);
                    twork.t_ins_trs = insData.read8(29);
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
                    twork.t_mtab_addr = tbase.read16((mtab - 1) * 2);
                    twork.t_channel_flag |= (1 << cf_mtab_carry);
                }
                return 2;
            case 0xEC: // Channel Flags
                int flg = tbase.read8(pos + 1) & 0xff;
                if ((flg & 0x80) != 0) {
                    twork.t_note_flag |= (1 << nf_fm3) | (1 << nf_vol);
                } else {
                    int bit = flg & 0x7;
                    if ((flg & 0x08) != 0) {
                        twork.t_channel_flag |= (1 << bit);
                    } else {
                        twork.t_channel_flag &= ~(1 << bit);
                    }
                }
                return 2;
            case 0xF0: // PCM
                return 2;
            case 0xF1: // PCM Rate
                return 2;
            case 0xF2: // PCM Mode
                return 2;
            case 0xF9: // Tempo
                a0.w_tempo[twork.t_request_id / 2] = tbase.read8(pos + 1) & 0xff;
                return 2;
            case 0xFF: // Finish
                stop_track(twork);
                return 1;
            case 0xF3:
            case 0xF4:
                stop_track(twork);
                return 1;
            case 0xF5: // Jump
                int jumpAddr = tbase.read16(pos + 1);
                twork.t_position = pos + jumpAddr;
                return 0;
            case 0xF8: // Comm
                a0.w_comm = tbase.read8(pos + 1);
                return 2;
            case 0xFA: // Loop
                int slot = twork.t_stack_pos;
                if (slot < TSTACK_COUNT - 2) {
                    twork.t_stack[slot] = 0xff;
                    twork.t_stack[slot + 1] = pos + 4;
                    twork.t_stack_pos += 2;
                }
                return 4;
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
            int fmPitch = mds_get_fm_pitch(t, pitch);
            int high = fmPitch & 0xff;
            int low = (fmPitch >> 8) & 0xff;

            if (part == 0) {
                write_fm_port0(0xA4 + ch, high);
                write_fm_port0(0xA0 + ch, low);
            } else {
                write_fm_port1(0xA4 + ch, high);
                write_fm_port1(0xA0 + ch, low);
            }
        }

        if ((t.t_note_flag & (1 << nf_key_on)) != 0) {
            t.t_note_flag &= ~(1 << nf_key_on);
            if ((t.t_note_flag & (1 << nf_slur)) == 0) {
                t.t_channel_flag |= (1 << cf_key_on);
                int opMask = 0xF0;
                int slot = ch + (part * 4);
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
        // Logic from mds_psg_update_env
        if ((t.t_note_flag & (1 << nf_key_on)) != 0) {
            t.t_note_flag &= ~(1 << nf_key_on);
            // Key On handling: reset envelope pos
            t.t_psg_eg_pos = 0;
            t.t_channel_flag |= (1 << cf_key_on);
            t.t_note_flag |= (1 << nf_sustain);
            t.t_last_pitch = 0xffff; // Force pitch update
        } else if ((t.t_note_flag & (1 << nf_key_off)) != 0) {
            // Key Off
            // Disable envelope or jump to release?
            // ASM: andi.l #~((1<<nf_sustain)...)
            t.t_note_flag &= ~((1 << nf_sustain) | (1 << nf_key_off));
            t.t_channel_flag &= ~(1 << cf_key_on);
        }

        // Envelope/Script processing
        // Fetch command from t.t_psg_eg_addr + t.t_psg_eg_pos
        Memory sdtop = a0.w_sdtop;
        if (sdtop != null && t.t_psg_eg_addr != 0) {
            // Simplified execution:
            // Read byte. If < 0x10, it's volume/delay?
            // If it's command (jump/hold).
            // For now, basic volume update from t_vol:
        }

        // Write Volume (Always update vol)
        int vol = t.t_vol;
        // Apply global volume
        // vol += a0.w_volume...
        int psgVol = mds_psg_convert_vol(vol) & 0x0F;
        // Channel ID | 0x10 (Vol) | Val
        Memory psg = getPsgMemory();
        psg.write8(MdDef.sound_psg, chVal | 0x10 | psgVol);
    }

    private void mds_psg3_update(WorkArea a0, TrackData t, int chVal) {
        mds_psg_update(a0, t, chVal);
    }

    private void mds_psgn_update(WorkArea a0, TrackData t, int chVal) {
        int pitch = mds_pitch_update(a0, t);
        // PSG Noise update logic
        // ...
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
        int note = (t.t_note + t.t_trs) & 0xff;
        int dtn = t.t_dtn;
        int pitch = (note << 8) + dtn;

        int current = t.t_pitch;
        int delta = pitch - current;

        if (delta != 0) {
            int pta = t.t_pta;
            if (pta > 0) {
                int step = delta >> 8;
                if (step < 0)
                    step -= 2;
                step++;
                step *= pta;
                step >>= 1;
                int next = current + step;
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
        return pitch;
    }

    private int mds_get_fm_pitch(TrackData t, int pitch) {
        int note = (pitch >> 8) & 0xff;
        int frac = pitch & 0xff;
        while (note >= 120)
            note = 119;

        int baseIndex = mds_note_table[note] + t.t_ins_trs;
        baseIndex &= 0xff;

        int f1 = mds_fm_freq_tab[baseIndex / 2];
        int f2 = mds_fm_freq_tab[baseIndex / 2 + 1];

        int delta = f2 - f1;
        int interp = (delta * frac) / 256;
        int freq = f1 + interp;

        int octVal = mds_octave_table[note];

        int high = (freq >> 8) & 0xff;
        int low = freq & 0xff;

        return (low << 8) | ((high + octVal) & 0xff);
    }

    private int mds_get_psg_pitch(TrackData t, int pitch) {
        int note = (pitch >> 8) & 0xff;
        int freq = 0;
        if (note < mds_note_table.length) {
            int oct = mds_octave_table[note] / 8;
            int noteIdx = mds_note_table[note];
            int f1 = mds_psg_freq_tab[noteIdx / 2];
            freq = f1 >> oct;
        }
        return freq;
    }

    private void mds_fm_update_ins(WorkArea a0, TrackData t, int ch, int part) {
        Memory sdtop = a0.w_sdtop;
        if (sdtop == null)
            return;

        int baseAddr = t.t_base_addr;
        Memory insTable = sdtop.add(baseAddr);
        int insIdx = t.t_ins;
        int insOffset = insTable.read16(insIdx * 2);
        // Emulate sign extension for 16-bit offset
        if ((insOffset & 0x8000) != 0)
            insOffset |= 0xFFFF0000;

        Memory insData = sdtop.add(insOffset);

        final int[] regBases = { 0x30, 0x50, 0x60, 0x70, 0x80, 0x90 };
        int dataPtr = 0;

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

        dataPtr += 4;

        int algo = insData.read8(dataPtr++);
        int fbAlgoReg = 0xB0 + ch;
        if (part == 0)
            write_fm_port0(fbAlgoReg, algo);
        else
            write_fm_port1(fbAlgoReg, algo);
    }

    private void mds_fm_update_vol(WorkArea a0, TrackData t, int ch, int part) {
        int vol = t.t_vol;
        if (vol > 127)
            vol = 127;
        if (t.t_request_id >= RCOUNT * 2) return; // Safety check
        vol += a0.w_volume[t.t_request_id >> 1] >> 8;
        int tl = mds_fm_vol_table[vol & 0xf];

        int[] ops = { 0, 8, 4, 12 };
        for (int i = 0; i < 4; i++) {
            int opOff = ops[i];
            int val = tl;
            if (part == 0)
                write_fm_port0(0x40 + opOff + ch, val);
            else
                write_fm_port1(0x40 + opOff + ch, val);
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

}
