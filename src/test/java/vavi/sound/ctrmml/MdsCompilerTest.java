/*
 * Copyright (c) 2026 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.sound.ctrmml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.ServiceLoader;
import java.util.stream.Stream;

import musicDriverInterface.ICompiler;
import musicDriverInterface.MetaData;
import musicDriverInterface.MmlDatum;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import vavi.sound.ctrmml.compiler.Compiler;
import vavi.sound.mdsdrv.driver.MdsDriver;
import vavi.sound.mdsdrv.RiffMdsParser;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Compiles the bundled MML sources and checks the output against the reference data produced by
 * the original ctrmml.
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 2026-08-02 nsano initial version <br>
 */
class MdsCompilerTest {

    static final Path DATA = Path.of("src/test/resources/data");

    /** Resolves the sample files relative to the test data directory. */
    static final FileResolver RESOLVER = filename -> {
        try {
            Path path = DATA.resolve(filename);
            return Files.exists(path) ? Files.newInputStream(path) : null;
        } catch (IOException e) {
            return null;
        }
    };

    static Riff compile(String name) throws IOException {
        return compile(name, true);
    }

    static Riff compile(String name, boolean withTags) throws IOException {
        try (InputStream in = Files.newInputStream(DATA.resolve(name + ".mml"))) {
            return MdsLink.compile(name + ".mml", in, RESOLVER, withTags);
        }
    }

    @Test
    @DisplayName("jazzy_nyc_99.mml compiles to exactly the bundled jazzy_nyc_99.mds")
    void testCompileMatchesReferenceMds() throws Exception {
        // the reference was built by the original ctrmml, which has no metadata chunk to write
        byte[] expected = Files.readAllBytes(DATA.resolve("bgm/jazzy_nyc_99.mds"));
        byte[] actual = compile("bgm/jazzy_nyc_99", false).toBytes().toByteArray();
        assertArrayEquals(expected, actual);
    }

    @Test
    @DisplayName("The metadata chunk carries the MML tags through to the driver")
    void testCompileWritesTags() throws Exception {
        byte[] mds = compile("bgm/jazzy_nyc_99").toBytes().toByteArray();

        RiffMdsParser.ParseResult parsed = RiffMdsParser.parse(mds);
        assertEquals("JAZZY NYC'91", parsed.tags.get("title"));
        assertEquals("Street Fighter III: 3rd Strike", parsed.tags.get("game"));
        assertEquals("ctr", parsed.tags.get("author"));
        assertEquals("2019-03-23", parsed.tags.get("date"));
        // #platform is a build tag, and the parser never saw it as a tag in the first place
        assertFalse(parsed.tags.containsKey("platform"));

        // the sequence is the same as without the chunk, so the song still plays the same
        assertArrayEquals(RiffMdsParser.parse(compile("bgm/jazzy_nyc_99", false)
                .toBytes().toByteArray()).seqData, parsed.seqData);

        MetaData metaData = new MdsDriver().getMetaData(mds);
        assertEquals("JAZZY NYC'91", metaData.getFirst(MetaData.Tag.Title));
        assertEquals("JAZZY NYC'91", metaData.getFirst(MetaData.Tag.TitleJ));
        assertEquals("Street Fighter III: 3rd Strike", metaData.getFirst(MetaData.Tag.GameTitle));
        assertEquals("ctr", metaData.getFirst(MetaData.Tag.Artist));
        assertEquals("2019-03-23", metaData.getFirst(MetaData.Tag.ReleaseDate));
    }

    @Test
    @DisplayName("A .mds without the metadata chunk yields empty metadata")
    void testReferenceMdsHasNoTags() throws Exception {
        byte[] mds = Files.readAllBytes(DATA.resolve("bgm/jazzy_nyc_99.mds"));
        assertTrue(RiffMdsParser.parse(mds).tags.isEmpty());
        assertTrue(new MdsDriver().getMetaData(mds)
                .getFirst(MetaData.Tag.Title).isEmpty());
    }

    static Stream<String> soundEffects() {
        return Stream.of("beep1", "beep2", "beep3", "beep4",
                "explosion1", "explosion2", "explosion3",
                "menu1", "menu2", "menu3",
                "noise1", "noise2", "pcm1", "pcm2").map(name -> "se/" + name);
    }

    @ParameterizedTest
    @MethodSource("soundEffects")
    @DisplayName("Every bundled sound effect compiles to a well formed MDS container")
    void testCompileSoundEffect(String name) throws Exception {
        Riff mds = compile(name);
        assertEquals(Riff.TYPE_RIFF, mds.getType());
        assertEquals(Riff.fourCc("MDS0"), mds.getId());

        // the driver's own parser must accept it
        RiffMdsParser.ParseResult parsed = RiffMdsParser.parse(mds.toBytes().toByteArray());
        assertTrue(parsed.isRiff);
        assertNotNull(parsed.seqData);
        assertTrue(parsed.seqData.length > 0);
    }

    @Test
    @DisplayName("The linker groups the sound effects and the BGM by their #group tag")
    void testLink() throws Exception {
        MdsdrvLinkerHolder holder = new MdsdrvLinkerHolder();
        holder.add("bgm/jazzy_nyc_99");
        for (String name : soundEffects().toList()) {
            holder.add(name);
        }

        byte[] seq = holder.linker.getSeqData().toByteArray();
        byte[] pcm = holder.linker.getPcmData().toByteArray();

        // header: magic, version, song count
        assertEquals(0x10011f00, readBe32(seq, 0));
        assertEquals(0x0006, readBe16(seq, 4));
        assertEquals(15, readBe16(seq, 6));
        assertEquals(15, holder.linker.getSeqCount());
        // pcm1/pcm2 pull in the two bundled samples
        assertTrue(pcm.length > 0);

        String header = holder.linker.getCHeader();
        assertTrue(header.contains("#define BGM_JAZZY_NYC_99 1"), header);
        assertTrue(header.contains("#define SE_MIN 2"), header);
        assertTrue(header.contains("#define SE_MAX 15"), header);
    }

    /** Small helper so the test reads like the {@code mdslink} flow. */
    static class MdsdrvLinkerHolder {
        final vavi.sound.ctrmml.platform.MdsdrvLinker linker = new vavi.sound.ctrmml.platform.MdsdrvLinker();

        void add(String name) throws IOException {
            linker.addSong(compile(name), name.substring(name.indexOf('/') + 1));
        }
    }

    @Test
    @DisplayName("The compiler is registered as an ICompiler service")
    void testServiceLoader() {
        ICompiler compiler = ICompiler.factory(Compiler.class.getName());
        assertNotNull(compiler);
        assertTrue(ServiceLoader.load(ICompiler.class).stream()
                .anyMatch(p -> p.type().equals(Compiler.class)));
    }

    @Test
    @DisplayName("ICompiler.compile() returns the MDS container that MdsDriver consumes")
    void testICompiler() throws Exception {
        Compiler compiler = new Compiler();
        compiler.init();
        compiler.setCompileSwitch("FileName=bgm/jazzy_nyc_99.mml");

        MmlDatum[] data;
        try (InputStream in = Files.newInputStream(DATA.resolve("bgm/jazzy_nyc_99.mml"))) {
            data = compiler.compile(in, RESOLVER::open);
        }
        assertNotNull(data, () -> "errors: " + compiler.getCompilerInfo().errorList);
        assertTrue(compiler.getCompilerInfo().errorList.isEmpty());

        byte[] actual = new byte[data.length];
        for (int i = 0; i < data.length; i++) {
            actual[i] = (byte) data[i].dat;
        }
        // same container as the reference, plus the metadata chunk the original had nowhere to put
        RiffMdsParser.ParseResult reference = RiffMdsParser.parse(
                Files.readAllBytes(DATA.resolve("bgm/jazzy_nyc_99.mds")));
        RiffMdsParser.ParseResult parsed = RiffMdsParser.parse(actual);
        assertArrayEquals(reference.seqData, parsed.seqData);
        assertEquals(reference.globals.size(), parsed.globals.size());
        assertEquals("JAZZY NYC'91", parsed.tags.get("title"));

        // and byte identical to it once the compiler is told not to write that chunk
        compiler.setCompileSwitch("Tags=false");
        try (InputStream in = Files.newInputStream(DATA.resolve("bgm/jazzy_nyc_99.mml"))) {
            data = compiler.compile(in, RESOLVER::open);
        }
        byte[] untagged = new byte[data.length];
        for (int i = 0; i < data.length; i++) {
            untagged[i] = (byte) data[i].dat;
        }
        assertArrayEquals(Files.readAllBytes(DATA.resolve("bgm/jazzy_nyc_99.mds")), untagged);

        // the linked banks are available as well
        assertNotNull(compiler.getSeqData());
        assertNotNull(compiler.getPcmData());
        assertEquals(0x10011f00, readBe32(compiler.getSeqData(), 0));

        // part information
        assertFalse(compiler.getCompilerInfo().partName.isEmpty());
    }

    @Test
    void testMetaData() throws Exception {
        Compiler compiler = new Compiler();
        compiler.init();
        MetaData metaData = compiler.getMetaData(Files.readAllBytes(DATA.resolve("bgm/jazzy_nyc_99.mml")));
        assertEquals("JAZZY NYC'91", metaData.getFirst(MetaData.Tag.Title));
        assertEquals("Street Fighter III: 3rd Strike", metaData.getFirst(MetaData.Tag.GameTitle));
    }

    @Test
    @DisplayName("Track lengths reported by the validator")
    void testSongValidator() throws Exception {
        Song song;
        try (InputStream in = Files.newInputStream(DATA.resolve("bgm/jazzy_nyc_99.mml"))) {
            song = MdsLink.convert("bgm/jazzy_nyc_99.mml", in, RESOLVER);
        }
        SongValidator validator = new SongValidator(song);
        assertFalse(validator.getTrackMap().isEmpty());
        for (TrackValidator track : validator.getTrackMap().values()) {
            assertTrue(track.getPlayTime() >= 0);
        }
    }

    @Test
    void testExportFormats() {
        Song song = new Song();
        List<Platform.Format> formats = song.getPlatform().getExportFormats();
        assertEquals(1, formats.size());
        assertEquals("mds", formats.getFirst().extension());
    }

    static int readBe32(byte[] b, int pos) {
        return ((b[pos] & 0xff) << 24) | ((b[pos + 1] & 0xff) << 16) | ((b[pos + 2] & 0xff) << 8) | (b[pos + 3] & 0xff);
    }

    static int readBe16(byte[] b, int pos) {
        return ((b[pos] & 0xff) << 8) | (b[pos + 1] & 0xff);
    }
}
