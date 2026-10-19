package com.fongmi.android.tv.player.subtitle;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import static org.junit.Assert.*;

public class OpenTypeFamilyNameTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    private File font(String name, boolean collection) throws IOException {
        byte[] family = name.getBytes(StandardCharsets.UTF_16BE);
        int face = collection ? 16 : 0;
        int table = face + 28;
        ByteBuffer b = ByteBuffer.allocate(table + 18 + family.length).order(ByteOrder.BIG_ENDIAN);
        if (collection) b.putInt(0x74746366).putInt(0x00010000).putInt(1).putInt(face);
        b.position(face);
        b.putInt(0x00010000).putShort((short) 1).putShort((short) 0).putInt(0);
        b.putInt(0x6e616d65).putInt(0).putInt(table).putInt(18 + family.length);
        b.position(table);
        b.putShort((short) 0).putShort((short) 1).putShort((short) 18);
        b.putShort((short) 3).putShort((short) 1).putShort((short) 0x409).putShort((short) 1);
        b.putShort((short) family.length).putShort((short) 0).put(family);
        File f = temporary.newFile();
        Files.write(f.toPath(), b.array());
        return f;
    }

    @Test public void readsUnicodeFamilyWithoutNativeSubtitleLibrary() throws Exception {
        assertEquals("影院字幕字体", OpenTypeFamilyName.read(font("影院字幕字体", false)));
    }

    @Test public void readsFirstFaceOfFontCollection() throws Exception {
        assertEquals("Cinema Sans", OpenTypeFamilyName.read(font("Cinema Sans", true)));
    }

    @Test public void rejectsTruncatedFont() throws Exception {
        File f = temporary.newFile();
        Files.write(f.toPath(), new byte[5]);
        assertThrows(IOException.class, () -> OpenTypeFamilyName.read(f));
    }

    @Test public void rejectsNameTableOutsideTheFile() throws Exception {
        File f = font("Cinema", false);
        byte[] data = Files.readAllBytes(f.toPath());
        ByteBuffer.wrap(data).putInt(20, Integer.MAX_VALUE);
        Files.write(f.toPath(), data);
        assertThrows(IOException.class, () -> OpenTypeFamilyName.read(f));
    }

    @Test public void rejectsStringRecordOutsideTheFile() throws Exception {
        File f = font("Cinema", false);
        byte[] data = Files.readAllBytes(f.toPath());
        ByteBuffer.wrap(data).putShort(44, (short) 65535);
        Files.write(f.toPath(), data);
        assertThrows(IOException.class, () -> OpenTypeFamilyName.read(f));
    }
}
