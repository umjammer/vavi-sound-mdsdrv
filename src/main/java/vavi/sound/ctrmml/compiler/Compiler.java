/*
 * Copyright (c) 2026 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.sound.ctrmml.compiler;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.function.Function;

import musicDriverInterface.CompilerInfo;
import musicDriverInterface.ICompiler;
import musicDriverInterface.MetaData;
import musicDriverInterface.MetaData.Tag;
import musicDriverInterface.MmlDatum;
import vavi.sound.ctrmml.ByteVector;
import vavi.sound.ctrmml.FileResolver;
import vavi.sound.ctrmml.InputError;
import vavi.sound.ctrmml.MdsLink;
import vavi.sound.ctrmml.MmlInput;
import vavi.sound.ctrmml.Riff;
import vavi.sound.ctrmml.Song;
import vavi.sound.ctrmml.SongValidator;
import vavi.sound.ctrmml.TrackValidator;
import vavi.sound.ctrmml.platform.MdsdrvConverter;
import vavi.sound.ctrmml.platform.MdsdrvLinker;
import vavi.util.compat.Tuple3;

import static java.lang.System.getLogger;


/**
 * MML to MDSDRV sequence compiler.
 * <p>
 * The compiled output is a {@code .mds} RIFF container, which is what
 * {@code vavi.sound.mdsdrv.driver.MdsDriver} consumes. Use {@link #getSeqData()} and
 * {@link #getPcmData()} after a compile to get the linked {@code mdsseq.bin} /
 * {@code mdspcm.bin} pair instead.
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 2026-08-02 nsano initial version <br>
 * @see <a href="https://github.com/superctr/ctrmml">ctrmml</a>
 */
public class Compiler implements ICompiler {

    private static final Logger logger = getLogger(Compiler.class.getName());

    /** MML sources are byte oriented; tags may hold Shift_JIS text. */
    private static final Charset DEFAULT_CHARSET = StandardCharsets.ISO_8859_1;

    /** Name reported in diagnostics when the source has no file name. */
    private String filename = "mml";
    private Charset charset = DEFAULT_CHARSET;
    private Function<String, InputStream> appendFileReaderCallback;
    private CompilerInfo compilerInfo = new CompilerInfo();
    private Song song;
    private byte[] seqData;
    private byte[] pcmData;

    public Compiler() {
    }

    @Override
    public void init() {
        this.compilerInfo = new CompilerInfo();
        this.song = null;
        this.seqData = null;
        this.pcmData = null;
    }

    /**
     * Compile switches.
     * <ul>
     * <li>a {@link Function}{@code <String, InputStream>} sets the file reader callback</li>
     * <li>{@code "FileName=<name>"} sets the name used for diagnostics and relative sample paths</li>
     * <li>{@code "Charset=<name>"} sets the charset used to decode the metadata tags</li>
     * </ul>
     */
    @Override
    @SuppressWarnings("unchecked")
    public void setCompileSwitch(Object... param) {
        if (param == null) {
            return;
        }

        for (Object prm : param) {
            if (prm instanceof Function) {
                appendFileReaderCallback = (Function<String, InputStream>) prm;
            } else if (prm instanceof String s) {
                if (s.startsWith("FileName=")) {
                    filename = s.substring("FileName=".length());
                } else if (s.startsWith("Charset=")) {
                    charset = Charset.forName(s.substring("Charset=".length()));
                }
            }
        }
    }

    @Override
    public MmlDatum[] compile(InputStream sourceMML, Function<String, InputStream> appendFileReaderCallback) {
        if (appendFileReaderCallback != null) {
            this.appendFileReaderCallback = appendFileReaderCallback;
        }
        FileResolver resolver = FileResolver.of(this.appendFileReaderCallback);

        try {
            byte[] source = sourceMML.readAllBytes();

            song = MdsLink.convert(filename, new ByteArrayInputStream(source), resolver);
            reportTrackLengths();

            Riff mds = new MdsdrvConverter(song, resolver).getMds();

            MdsdrvLinker linker = new MdsdrvLinker();
            linker.addSong(mds, baseName(filename));
            seqData = linker.getSeqData().toByteArray();
            pcmData = linker.getPcmData().toByteArray();

            ByteVector bytes = mds.toBytes();
            MmlDatum[] ret = new MmlDatum[bytes.size()];
            for (int i = 0; i < bytes.size(); i++) {
                ret[i] = new MmlDatum(bytes.get(i));
            }
            return ret;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InputError e) {
            compilerInfo.errorList.add(new Tuple3<>(errorLine(e), errorColumn(e), e.getMessage()));
            logger.log(Level.ERROR, e.getMessage(), e);
        } catch (RuntimeException e) {
            compilerInfo.errorList.add(new Tuple3<>(-1, -1, e.getMessage()));
            logger.log(Level.ERROR, e.getMessage(), e);
        }
        return null;
    }

    /** Compile and write the {@code .mds} bytes into {@code destCompiledBin}. */
    public boolean compile(InputStream sourceMML, ByteArrayOutputStream destCompiledBin,
                           Function<String, InputStream> appendFileReaderCallback) {
        MmlDatum[] dat = compile(sourceMML, appendFileReaderCallback);
        if (dat == null) {
            return false;
        }
        for (MmlDatum md : dat) {
            destCompiledBin.write(md == null ? 0 : md.dat & 0xff);
        }
        return true;
    }

    /** The linked {@code mdsseq.bin} of the last {@link #compile}, null if it failed. */
    public byte[] getSeqData() {
        return seqData;
    }

    /** The linked {@code mdspcm.bin} of the last {@link #compile}, null if it failed. */
    public byte[] getPcmData() {
        return pcmData;
    }

    /** The song parsed by the last {@link #compile}, null if it failed. */
    public Song getSong() {
        return song;
    }

    @Override
    public CompilerInfo getCompilerInfo() {
        return compilerInfo;
    }

    @Override
    public MetaData getMetaData(byte[] srcBuf) {
        MetaData metaData = new MetaData();
        Song s = new Song();
        try {
            MmlInput input = new MmlInput(s);
            input.setFileResolver(FileResolver.of(appendFileReaderCallback));
            input.parse(filename, new ByteArrayInputStream(srcBuf));
        } catch (RuntimeException e) {
            logger.log(Level.DEBUG, e.getMessage(), e);
        }
        set(metaData, s, "#title", Tag.Title, Tag.TitleJ);
        set(metaData, s, "#composer", Tag.Composer, Tag.ComposerJ);
        set(metaData, s, "#author", Tag.Artist, Tag.ArtistJ);
        set(metaData, s, "#programmer", Tag.Arranger, Tag.ArrangerJ);
        set(metaData, s, "#game", Tag.GameTitle, Tag.GameTitleJ);
        set(metaData, s, "#system", Tag.GameSystem, Tag.GameSystemJ);
        set(metaData, s, "#date", Tag.ReleaseDate);
        set(metaData, s, "#comment", Tag.Memo);
        return metaData;
    }

    private void set(MetaData metaData, Song song, String key, Tag... tags) {
        String value = song.getTagFrontSafe(key);
        if (value.isEmpty()) {
            return;
        }
        // the parser keeps the source bytes as latin-1, so re-encode them with the real charset
        String decoded = new String(value.getBytes(DEFAULT_CHARSET), charset);
        for (Tag tag : tags) {
            metaData.set(tag, decoded);
        }
    }

    private void reportTrackLengths() {
        SongValidator validator = new SongValidator(song);
        for (Map.Entry<Integer, TrackValidator> entry : validator.getTrackMap().entrySet()) {
            int id = entry.getKey();
            if (id >= 16) {
                continue;
            }
            compilerInfo.partType.add("MDSDRV");
            compilerInfo.partNumber.add(id);
            compilerInfo.partName.add(String.valueOf((char) ('A' + id)));
            compilerInfo.totalCount.add((int) entry.getValue().getPlayTime());
            compilerInfo.loopCount.add((int) entry.getValue().getLoopLength());
        }
    }

    private static int errorLine(InputError e) {
        return e.getReference() != null ? e.getReference().getLine() : -1;
    }

    private static int errorColumn(InputError e) {
        return e.getReference() != null ? e.getReference().getColumn() : -1;
    }

    private static String baseName(String filename) {
        int spos = Math.max(filename.lastIndexOf('/'), filename.lastIndexOf('\\'));
        int epos = filename.lastIndexOf('.');
        if (epos <= spos) {
            epos = filename.length();
        }
        return filename.substring(spos + 1, epos);
    }
}
