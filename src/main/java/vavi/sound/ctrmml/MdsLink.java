/*
 * Copyright (c) 2026 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.sound.ctrmml;

import java.io.IOException;
import java.io.InputStream;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import vavi.sound.ctrmml.platform.MdsdrvConverter;
import vavi.sound.ctrmml.platform.MdsdrvLinker;
import vavi.sound.ctrmml.platform.MdsdrvPlatform;

import static java.lang.System.getLogger;


/**
 * MDSDRV MML compiler and linker, the equivalent of ctrmml's {@code mdslink} tool.
 * <p>
 * Input files can be in {@code .mml} or {@code .mds} format.
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 2026-08-02 nsano initial version <br>
 * @see <a href="https://github.com/superctr/ctrmml">ctrmml</a> src/platform/mdslink.cpp
 */
public final class MdsLink {

    private static final Logger logger = getLogger(MdsLink.class.getName());

    private MdsLink() {
    }

    /**
     * Compile a single MML source into the MDS RIFF container.
     *
     * @param filename used for diagnostics and to resolve relative sample paths
     * @param resolver used to open the referenced PCM samples
     */
    public static Riff compile(String filename, InputStream in, FileResolver resolver) {
        return compile(filename, in, resolver, true);
    }

    /**
     * Compile a single MML source into the MDS RIFF container.
     *
     * @param filename used for diagnostics and to resolve relative sample paths
     * @param resolver used to open the referenced PCM samples
     * @param withTags write the song metadata as a {@code "tag "} chunk, an extension of this
     *                 project. Pass false for output byte identical to the original ctrmml's.
     */
    public static Riff compile(String filename, InputStream in, FileResolver resolver, boolean withTags) {
        Song song = convert(filename, in, resolver);
        return new MdsdrvConverter(song, resolver).getMds(withTags);
    }

    /** Parse an MML source into a {@link Song} and validate it. */
    public static Song convert(String filename, InputStream in, FileResolver resolver) {
        Song song = new Song();
        MmlInput input = new MmlInput(song);
        input.setFileResolver(resolver);
        input.parse(filename, in);
        SongValidator validator = new SongValidator(song);
        for (Map.Entry<Integer, TrackValidator> entry : validator.getTrackMap().entrySet()) {
            long length = entry.getValue().getLoopLength();
            logger.log(Level.DEBUG, "Track%3d:%7d%s".formatted(entry.getKey(), entry.getValue().getPlayTime(),
                    length != 0 ? " (loop %7d)".formatted(length) : ""));
        }
        return song;
    }

    /** The result of a link: the sequence bank and the PCM bank. */
    public record Result(byte[] seq, byte[] pcm, String asmHeader, String cHeader, String statistics) {
    }

    /**
     * Link any number of MML and MDS inputs into {@code mdsseq.bin} and {@code mdspcm.bin}.
     *
     * @param inputs   file names; {@code .mds} inputs are added as-is, everything else is
     *                 compiled as MML
     * @param resolver used to open the inputs and the PCM samples they reference
     */
    public static Result link(List<String> inputs, FileResolver resolver) {
        MdsdrvLinker linker = new MdsdrvLinker();
        for (String input : inputs) {
            Riff mds;
            try (InputStream in = open(resolver, input)) {
                if (CType.iequal(getExtension(input), ".mds")) {
                    mds = new Riff(new ByteVector(in.readAllBytes()));
                } else {
                    mds = compile(input, in, resolver);
                }
            } catch (IOException e) {
                throw new InputError(null, "Couldn't read %s".formatted(input));
            }
            linker.addSong(mds, getFilename(input));
        }
        return new Result(linker.getSeqData().toByteArray(), linker.getPcmData().toByteArray(),
                linker.getAsmHeader(), linker.getCHeader(), linker.getStatistics());
    }

    private static InputStream open(FileResolver resolver, String filename) {
        InputStream in = resolver.open(filename);
        if (in == null) {
            throw new InputError(null, "Couldn't open %s".formatted(filename));
        }
        return in;
    }

    private static String getExtension(String inputFilename) {
        int pos = inputFilename.lastIndexOf('.');
        return pos != -1 ? inputFilename.substring(pos) : "";
    }

    /** Isolate the file name from the path, without the extension. */
    private static String getFilename(String inputFilename) {
        int spos = Math.max(inputFilename.lastIndexOf('/'), inputFilename.lastIndexOf('\\'));
        int epos = inputFilename.lastIndexOf('.');
        if (epos < spos) {
            epos = inputFilename.length();
        }
        if (spos != -1) {
            return inputFilename.substring(spos + 1, epos == -1 ? inputFilename.length() : epos);
        }
        return inputFilename.substring(0, epos == -1 ? inputFilename.length() : epos);
    }

    /**
     * mdslink command line.
     * <pre>
     * Usage: mdslink [options] &lt;list of input files ...&gt;
     *
     * Options:
     *     -o &lt;mdsseq.bin&gt; &lt;mdspcm.bin&gt; : Specify output filenames
     *     -i &lt;mdsseq.inc&gt;              : Specify ASM headers
     *     -h &lt;mdsseq.h&gt;                : Specify C headers
     * </pre>
     */
    public static void main(String[] args) throws IOException {
        List<String> input = new ArrayList<>();
        String seqFilename = "mdsseq.bin";
        String pcmFilename = "mdspcm.bin";
        String cHeaderFilename = "";
        String asmHeaderFilename = "";

        for (int arg = 0; arg < args.length; arg++) {
            if ((args[arg].equals("-o") || args[arg].equals("--output")) && (arg + 2) < args.length) {
                seqFilename = args[++arg];
                pcmFilename = args[++arg];
            } else if ((args[arg].equals("-h") || args[arg].equals("--c-header")) && (arg + 1) < args.length) {
                cHeaderFilename = args[++arg];
            } else if ((args[arg].equals("-i") || args[arg].equals("--asm-header")) && (arg + 1) < args.length) {
                asmHeaderFilename = args[++arg];
            } else {
                input.add(args[arg]);
            }
        }

        if (input.isEmpty()) {
            printUsage();
            System.err.println("no input specified");
            System.exit(-1);
        }

        try {
            Result result = link(input, FileResolver.FILE_SYSTEM);
            if (!seqFilename.isEmpty()) {
                System.out.printf("writing %s ...%n", seqFilename);
                Files.write(Path.of(seqFilename), result.seq());
            }
            if (!pcmFilename.isEmpty()) {
                System.out.printf("writing %s ...%n", pcmFilename);
                Files.write(Path.of(pcmFilename), result.pcm());
                System.out.print(result.statistics());
            }
            if (!asmHeaderFilename.isEmpty()) {
                System.out.printf("writing %s ...%n", asmHeaderFilename);
                Files.writeString(Path.of(asmHeaderFilename), result.asmHeader(), StandardCharsets.ISO_8859_1);
            }
            if (!cHeaderFilename.isEmpty()) {
                System.out.printf("writing %s ...%n", cHeaderFilename);
                Files.writeString(Path.of(cHeaderFilename), result.cHeader(), StandardCharsets.ISO_8859_1);
            }
        } catch (InputError e) {
            System.err.println(e.getMessage());
            System.exit(-1);
        }
    }

    private static void printUsage() {
        System.out.println("""
                mdslink - MDSDRV MML Compiler and Linker
                (C) 2019-2022 ian karlsson

                Usage: mdslink [options] <list of input files ...>

                Options:
                \t-o <mdsseq.bin> <mdsbin.bin> : Specify output filenames
                \t-i <mdsseq.inc>              : Specify ASM headers
                \t-h <mdsseq.h>                : Specify C headers
                Note:
                \tInput files can be in .mml or .mds format
                """);
        System.out.printf("MDSDRV version %d.%d (minimum compatible version %d.%d)%n%n",
                MdsdrvPlatform.SEQ_VERSION_MAJOR, MdsdrvPlatform.SEQ_VERSION_MINOR,
                MdsdrvPlatform.MIN_SEQ_VERSION_MAJOR, MdsdrvPlatform.MIN_SEQ_VERSION_MINOR);
    }
}
