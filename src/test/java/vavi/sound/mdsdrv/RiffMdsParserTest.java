/*
 * Copyright (c) 2026 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.sound.mdsdrv;

import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;


/**
 * The {@code "tag "} chunk, whose text carries no encoding of its own.
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 2026-08-02 nsano initial version <br>
 */
class RiffMdsParserTest {

    /** a minimal MDS0 container holding the given "tag " chunk content and an empty sequence */
    static byte[] mds(byte[] tagData) {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        chunk(body, "tag ", tagData);
        chunk(body, "seq ", new byte[] {0, 4, 0, 0});
        byte[] content = body.toByteArray();

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes("RIFF".getBytes(StandardCharsets.ISO_8859_1));
        writeLe32(out, content.length + 4);
        out.writeBytes("MDS0".getBytes(StandardCharsets.ISO_8859_1));
        out.writeBytes(content);
        return out.toByteArray();
    }

    static void chunk(ByteArrayOutputStream out, String id, byte[] data) {
        out.writeBytes(id.getBytes(StandardCharsets.ISO_8859_1));
        writeLe32(out, data.length);
        out.writeBytes(data);
        if ((data.length & 1) != 0) out.write(0); // RIFF alignment
    }

    static void writeLe32(ByteArrayOutputStream out, int value) {
        out.write(value & 0xff);
        out.write((value >> 8) & 0xff);
        out.write((value >> 16) & 0xff);
        out.write((value >>> 24) & 0xff);
    }

    /** {@code key NUL value NUL}, the value encoded as an MML source of that charset would have it */
    static byte[] tagData(String key, String value, Charset charset) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(key.getBytes(StandardCharsets.ISO_8859_1));
        out.write(0);
        out.writeBytes(value.getBytes(charset));
        out.write(0);
        return out.toByteArray();
    }

    @Test
    @DisplayName("UTF-8 text is read as UTF-8, whatever the fallback says")
    void testUtf8() {
        byte[] mds = mds(tagData("title", "ジャズ", StandardCharsets.UTF_8));

        String was = System.setProperty(RiffMdsParser.ENCODING_KEY, "ISO-8859-1");
        try {
            assertEquals("ジャズ", RiffMdsParser.parse(mds).tags.get("title"));
        } finally {
            restore(was);
        }
    }

    @Test
    @DisplayName("text that is not UTF-8 is read with the encoding the property names")
    void testFallbackEncoding() {
        Charset ms932 = Charset.forName("MS932");
        byte[] mds = mds(tagData("title", "ジャズ", ms932));

        // the default: Shift_JIS, which is what the bytes are
        String was = System.clearProperty(RiffMdsParser.ENCODING_KEY);
        try {
            assertEquals("ジャズ", RiffMdsParser.parse(mds).tags.get("title"));

            // and another encoding really is used, rather than the default being baked in
            System.setProperty(RiffMdsParser.ENCODING_KEY, "EUC-JP");
            String asEucJp = new String("ジャズ".getBytes(ms932), Charset.forName("EUC-JP"));
            assertNotEquals("ジャズ", asEucJp, "the two encodings must disagree to prove anything");
            assertEquals(asEucJp, RiffMdsParser.parse(mds).tags.get("title"));
        } finally {
            restore(was);
        }
    }

    @Test
    @DisplayName("an unknown encoding name falls back to the default instead of failing the read")
    void testUnknownEncoding() {
        byte[] mds = mds(tagData("title", "ジャズ", Charset.forName("MS932")));

        String was = System.setProperty(RiffMdsParser.ENCODING_KEY, "no-such-charset");
        try {
            assertEquals("ジャズ", RiffMdsParser.parse(mds).tags.get("title"));
        } finally {
            restore(was);
        }
    }

    @Test
    @DisplayName("a file with no tag chunk has no tags")
    void testNoTagChunk() {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        chunk(body, "seq ", new byte[] {0, 4, 0, 0});
        byte[] content = body.toByteArray();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes("RIFF".getBytes(StandardCharsets.ISO_8859_1));
        writeLe32(out, content.length + 4);
        out.writeBytes("MDS0".getBytes(StandardCharsets.ISO_8859_1));
        out.writeBytes(content);

        assertTrue(RiffMdsParser.parse(out.toByteArray()).tags.isEmpty());
    }

    static void restore(String was) {
        if (was == null) {
            System.clearProperty(RiffMdsParser.ENCODING_KEY);
        } else {
            System.setProperty(RiffMdsParser.ENCODING_KEY, was);
        }
    }
}
