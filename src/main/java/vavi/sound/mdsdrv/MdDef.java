package vavi.sound.mdsdrv;

/**
 * Ported from mddef.inc
 * 
 * Hardware definitions and constants.
 */
public class MdDef {

    // ======================================================================
    // Hardware defines
    // ======================================================================
    public static final int vdp_data = 0xc00000;
    public static final int vdp_control = 0xc00004;
    public static final int vdp_counter = 0xc00008;
    public static final int vdp_debug = 0xc0001c;

    public static final int z80_ram = 0xA00000; // start of Z80 RAM
    public static final int z80_ram_end = 0xA02000; // end of non-reserved Z80 RAM
    public static final int z80_bus_request = 0xA11100;
    public static final int z80_reset = 0xA11200;

    public static final int io_version = 0xA10001;
    public static final int io_port_1_data = 0xA10002;
    public static final int io_port_1_control = 0xA10008;
    public static final int io_port_2_control = 0xA1000A;
    public static final int io_expansion_control = 0xA1000C;
    public static final int io_tmss = 0xA14000;

    // ym2612 (ym3438) wait requirement:
    // after address write: 17 cycles
    // after data write: 83 cycles (reg 21-9e)
    // 47 cycles (reg a0-b6)
    public static final int sound_fm_a0 = 0xA04000;
    public static final int sound_fm_d0 = 0xA04001;
    public static final int sound_fm_a1 = 0xA04002;
    public static final int sound_fm_d1 = 0xA04003;
    public static final int sound_fm_status = 0xA04000;

    public static final int sound_psg = 0xC00011;

    // Z80 PCM offsets
    public static final int z_pcm1 = z80_ram + 0x0E08;
    public static final int z_pcm2 = z_pcm1 + 8;
    public static final int z_pcm3 = z_pcm2 + 8;

    public static final int zp_key_on = 0;
    public static final int zp_vol = 1;
    public static final int zp_pitch = 2;
    public static final int zp_bank = 3;
    public static final int zp_addr = 4;
    public static final int zp_count = 6;

    // PCM enable flags (from mdsdrv.inc)
    public static final int pe_pcm1 = 7;
    public static final int pe_pcm2 = 6;
    public static final int pe_pcm3 = 5;
    public static final int pe_fade_stop = 4;

    // ======================================================================
    // joypad control
    // ======================================================================
    public static final int joy1_push = 0xfffffff0;
    public static final int joy1_press = 0xfffffff2;
    public static final int joy2_push = 0xfffffff4;
    public static final int joy2_press = 0xfffffff6;

    public static final int joy_up = 0b0000000000000001;
    public static final int joy_down = 0b0000000000000010;
    public static final int joy_left = 0b0000000000000100;
    public static final int joy_right = 0b0000000000001000;
    public static final int joy_b = 0b0000000000010000;
    public static final int joy_c = 0b0000000000100000;
    public static final int joy_a = 0b0000000001000000;
    public static final int joy_start = 0b0000000010000000;
    public static final int joy_z = 0b0000000100000000;
    public static final int joy_y = 0b0000001000000000;
    public static final int joy_x = 0b0000010000000000;
    public static final int joy_mode = 0b0000100000000000;
    public static final int joy_is_6btn = 0b0001000000000000;

    public static final int joy_bit_up = 0;
    public static final int joy_bit_down = 1;
    public static final int joy_bit_left = 2;
    public static final int joy_bit_right = 3;
    public static final int joy_bit_b = 4;
    public static final int joy_bit_c = 5;
    public static final int joy_bit_a = 6;
    public static final int joy_bit_start = 7;
    public static final int joy_bit_z = 8;
    public static final int joy_bit_y = 9;
    public static final int joy_bit_x = 10;
    public static final int joy_bit_mode = 11;

    public static final int last_vbl = 0xfffffff8;

    // ======================================================================
    // VDP control
    // ======================================================================
    public static final int vram_read_flag = 0x00000000;
    public static final int vram_write_flag = 0x40000000;
    public static final int cram_read_flag = 0x00000020;
    public static final int cram_write_flag = 0xc0000000;
    public static final int vsram_read_flag = 0x00000010;
    public static final int vsram_write_flag = 0x40000010;

    public static final int vram_dma_flag = 0x40000080;
    public static final int cram_dma_flag = 0xc0000080;
    public static final int vsram_dma_flag = 0x40000090;

    public static final int vram_plane_a = 0xa000;
    public static final int vram_plane_b = 0xc000;
}
