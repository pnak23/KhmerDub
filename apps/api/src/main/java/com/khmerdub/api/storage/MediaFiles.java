package com.khmerdub.api.storage;

import java.text.Normalizer;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/** File name and content checks for imported movies. */
public final class MediaFiles {

    /** Supported containers: extension -> MIME type. */
    public static final Map<String, String> MOVIE_TYPES = Map.of(
            "mp4", "video/mp4",
            "mov", "video/quicktime",
            "mkv", "video/x-matroska");

    private MediaFiles() {
    }

    public static Optional<String> extension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) {
            return Optional.empty();
        }
        return Optional.of(fileName.substring(dot + 1).toLowerCase(Locale.ROOT));
    }

    /**
     * A safe file name: no folders, no control characters, only letters, digits, space, dot, dash, underscore
     * and parentheses; at most 120 characters; never empty or hidden.
     */
    public static String sanitize(String rawName) {
        String name = rawName == null ? "" : rawName;
        name = name.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1);
        name = Normalizer.normalize(name, Normalizer.Form.NFC);
        name = name.replaceAll("[^\\p{L}\\p{N} ._()\\-]", "_").replaceAll("\\s+", " ").trim();
        while (name.startsWith(".")) {
            name = name.substring(1);
        }
        if (name.length() > 120) {
            String ext = extension(name).map(e -> "." + e).orElse("");
            name = name.substring(0, 120 - ext.length()) + ext;
        }
        return name.isBlank() ? "movie" : name;
    }

    /**
     * Check the first bytes against the extension. MP4/MOV are ISO base media files ("ftyp" or a QuickTime
     * atom at offset 4). MKV starts with the EBML magic 1A 45 DF A3.
     */
    public static boolean contentMatches(String extension, byte[] head, int length) {
        return switch (extension) {
            case "mkv" -> length >= 4 && (head[0] & 0xFF) == 0x1A && (head[1] & 0xFF) == 0x45
                    && (head[2] & 0xFF) == 0xDF && (head[3] & 0xFF) == 0xA3;
            case "mp4", "mov" -> {
                if (length < 8) {
                    yield false;
                }
                String atom = new String(head, 4, 4, java.nio.charset.StandardCharsets.ISO_8859_1);
                yield atom.equals("ftyp") || atom.equals("moov") || atom.equals("mdat") || atom.equals("wide")
                        || atom.equals("free") || atom.equals("skip");
            }
            default -> false;
        };
    }
}
