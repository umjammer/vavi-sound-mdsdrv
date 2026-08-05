/*
 * Copyright (c) 2026 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.sound.ctrmml.platform;


/**
 * MDSDRV sequence event.
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 2026-08-02 nsano initial version <br>
 * @see <a href="https://github.com/superctr/ctrmml">ctrmml</a> src/platform/mdsdrv.h
 */
public class MdsdrvEvent {

    /** carry event for macro track */
    public static final int CARRY = 0x7e;
    /** virtual "segno" event */
    public static final int SEGNO = 0x7f;

    public static final int REST = 0x80;
    public static final int TIE = 0x81;
    public static final int NOTE = 0x82;
    /** slur (legato) */
    public static final int SLR = 0xe0;
    /** instrument */
    public static final int INS = 0xe1;
    /** volume */
    public static final int VOL = 0xe2;
    /** volume modulate */
    public static final int VOLM = 0xe3;
    /** transpose */
    public static final int TRS = 0xe4;
    /** transpose modulate */
    public static final int TRSM = 0xe5;
    /** detune */
    public static final int DTN = 0xe6;
    /** portamento */
    public static final int PTA = 0xe7;
    /** pitch envelope */
    public static final int PEG = 0xe8;
    /** panning enable */
    public static final int PAN = 0xe9;
    /** lfo ams/pms */
    public static final int LFO = 0xea;
    /** macro table */
    public static final int MTAB = 0xeb;
    /** channel flags */
    public static final int FLG = 0xec;
    /** channel register write */
    public static final int FMCREG = 0xed;
    /** tl write */
    public static final int FMTL = 0xee;
    /** tl modulate */
    public static final int FMTLM = 0xef;
    /** PCM instrument */
    public static final int PCM = 0xf0;
    /** PCM rate */
    public static final int PCMRATE = 0xf1;
    /** PCM mixing mode */
    public static final int PCMMODE = 0xf2;
    /** jump */
    public static final int JUMP = 0xf5;
    /** global FM register write */
    public static final int FMREG = 0xf6;
    /** drum mode subroutine: play note and exit */
    public static final int DMFINISH = 0xf7;
    /** communication variable byte */
    public static final int COMM = 0xf8;
    /** tempo */
    public static final int TEMPO = 0xf9;
    /** loop start */
    public static final int LP = 0xfa;
    /** loop finish */
    public static final int LPF = 0xfb;
    /** loop break */
    public static final int LPB = 0xfc;
    /** loop break (long jump) */
    public static final int LPBL = 0xfd;
    /** pattern/subroutine */
    public static final int PAT = 0xfe;
    /** normal subroutine exit */
    public static final int FINISH = 0xff;

    /** unsigned 8 bit */
    public final int type;
    /** unsigned 16 bit */
    public final int arg;

    public MdsdrvEvent(int type, int arg) {
        this.type = type & 0xff;
        this.arg = arg & 0xffff;
    }

    @Override
    public String toString() {
        return "%02x:%04x".formatted(type, arg);
    }
}
