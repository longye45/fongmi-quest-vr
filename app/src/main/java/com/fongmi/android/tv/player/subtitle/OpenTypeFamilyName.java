package com.fongmi.android.tv.player.subtitle;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;

final class OpenTypeFamilyName {
    private OpenTypeFamilyName() {}
    /** Read the SFNT family table without requiring the unpublished libass bridge. */
    static String read(File file) throws IOException {
        try (RandomAccessFile font = new RandomAccessFile(file, "r")) {
            if (font.length() < 12) throw new IOException("Invalid font header");
            long face = 0;
            if (font.readInt() == 0x74746366) {
                font.skipBytes(4);
                if (font.readInt() < 1 || font.length() < 16) throw new IOException("Empty font collection");
                face = Integer.toUnsignedLong(font.readInt());
            }
            if (face + 12 > font.length()) throw new IOException("Invalid font face");
            font.seek(face + 4);
            int tables = font.readUnsignedShort();
            long nameOffset = -1;
            for (int index = 0; index < tables; index++) {
                long entry = face + 12L + index * 16L;
                if (entry + 16 > font.length()) break;
                font.seek(entry);
                int tag = font.readInt();
                font.skipBytes(4);
                long offset = Integer.toUnsignedLong(font.readInt());
                if (tag == 0x6e616d65) { nameOffset = offset; break; }
            }
            if (nameOffset < 0 || nameOffset + 6 > font.length()) throw new IOException("Font name table missing");
            font.seek(nameOffset + 2);
            int count = font.readUnsignedShort();
            long strings = nameOffset + font.readUnsignedShort();
            String best = null;
            int bestScore = -1;
            for (int index = 0; index < count; index++) {
                long entry = nameOffset + 6L + index * 12L;
                if (entry + 12 > font.length()) break;
                font.seek(entry);
                int platform = font.readUnsignedShort();
                font.skipBytes(2);
                int language = font.readUnsignedShort();
                int nameId = font.readUnsignedShort();
                int length = font.readUnsignedShort();
                long offset = strings + font.readUnsignedShort();
                if ((nameId != 1 && nameId != 16) || length < 1 || offset + length > font.length()) continue;
                if (platform != 0 && platform != 3) continue;
                int score = (nameId == 16 ? 4 : 0) + (language == 0x409 ? 2 : 0) + (platform == 3 ? 1 : 0);
                if (score <= bestScore) continue;
                byte[] bytes = new byte[length];
                font.seek(offset);
                font.readFully(bytes);
                String value = new String(bytes, StandardCharsets.UTF_16BE).trim();
                if (!value.isEmpty()) { best = value; bestScore = score; }
            }
            if (best == null) throw new IOException("Font family name missing");
            return best;
        }
    }

}
