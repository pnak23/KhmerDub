package com.khmerdub.api.storage;

import com.khmerdub.api.config.KhmerDubProperties;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.DosFileAttributeView;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.springframework.stereotype.Service;

/**
 * All file access under the storage root. Rules:
 * <ul>
 *   <li>The database stores paths relative to the root, with forward slashes. The same record then works for
 *       Windows ({@code D:/KhmerDub/data/...}) and for the Linux worker ({@code /data/...}).</li>
 *   <li>Generated files are never overwritten: {@link #publish} fails if the target exists, and target names
 *       contain the attempt id.</li>
 *   <li>Originals are made read-only and are never deleted by the application.</li>
 * </ul>
 */
@Service
public class StorageService {

    public static final List<String> PROJECT_FOLDERS = List.of(
            "original", "extracted", "transcription", "dialogue", "voices", "subtitles", "exports", "temp");

    private static final Pattern DRIVE = Pattern.compile("^[A-Za-z]:.*");

    private final Path root;

    public StorageService(KhmerDubProperties props) {
        this.root = props.storageRoot().toAbsolutePath().normalize();
    }

    public Path root() {
        return root;
    }

    /** Resolve a relative storage path. Absolute paths, backslashes, drive letters and '..' are rejected. */
    public Path resolve(String relative) {
        if (relative == null || relative.isEmpty() || relative.startsWith("/") || relative.contains("\\")
                || DRIVE.matcher(relative).matches()) {
            throw new UnsafePathException(relative);
        }
        for (String part : relative.split("/", -1)) {
            if (part.isEmpty() || part.equals(".") || part.equals("..")) {
                throw new UnsafePathException(relative);
            }
        }
        Path resolved = root.resolve(relative).normalize();
        if (!resolved.startsWith(root)) {
            throw new UnsafePathException(relative);
        }
        return resolved;
    }

    public String relativize(Path absolute) {
        Path normalized = absolute.toAbsolutePath().normalize();
        if (!normalized.startsWith(root)) {
            throw new UnsafePathException(absolute.toString());
        }
        return root.relativize(normalized).toString().replace('\\', '/');
    }

    public static String projectDir(UUID projectId) {
        return "projects/" + projectId;
    }

    /** Scratch folder of one attempt. Workers may only write here; Spring publishes from here. */
    public static String attemptWorkDir(UUID projectId, UUID attemptId) {
        return projectDir(projectId) + "/temp/attempts/" + attemptId;
    }

    public void createProjectLayout(UUID projectId) {
        try {
            for (String folder : PROJECT_FOLDERS) {
                Files.createDirectories(resolve(projectDir(projectId) + "/" + folder));
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Could not create the project folders", e);
        }
    }

    public long freeBytes() {
        try {
            return Files.getFileStore(root).getUsableSpace();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Move a finished file into its final, versioned location. Never replaces anything: if the target exists
     * this throws {@link FileAlreadyExistsException}. A plain same-volume move is an atomic rename on NTFS.
     * (ATOMIC_MOVE is avoided on purpose: on Windows it replaces an existing target.)
     */
    public void publish(Path source, Path target) throws IOException {
        Files.createDirectories(target.getParent());
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new FileAlreadyExistsException(target.toString());
        }
        Files.move(source, target);
    }

    public void markReadOnly(Path file) throws IOException {
        DosFileAttributeView dos = Files.getFileAttributeView(file, DosFileAttributeView.class);
        if (dos != null) {
            dos.setReadOnly(true);
        } else {
            file.toFile().setWritable(false, false);
        }
    }

    public static String sha256(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[1 << 20];
            for (int n; (n = in.read(buffer)) > 0; ) {
                digest.update(buffer, 0, n);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Delete one attempt's scratch folder. Refuses anything that is not exactly
     * {@code projects/<uuid>/temp/attempts/<uuid>}.
     */
    public boolean deleteAttemptWorkDir(UUID projectId, UUID attemptId) throws IOException {
        Path dir = resolve(attemptWorkDir(projectId, attemptId));
        if (!dir.getParent().endsWith(Path.of("temp", "attempts")) || !Files.isDirectory(dir)) {
            return false;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path p : walk.sorted((a, b) -> b.getNameCount() - a.getNameCount()).toList()) {
                try {
                    Files.delete(p);
                } catch (DirectoryNotEmptyException ignored) {
                    // a file inside is still open somewhere; try again next run
                }
            }
        }
        return !Files.exists(dir);
    }

    /** Private (biometric-like) data of a project: speaker embeddings and voice samples, in separate folders. */
    public static String privateDir(UUID projectId, String kind) {
        if (!kind.equals("fingerprints") && !kind.equals("voice-samples")) {
            throw new IllegalArgumentException("Unknown private folder " + kind);
        }
        return projectDir(projectId) + "/private/" + kind;
    }

    /**
     * Delete everything under {@code path} (a file or folder) and verify nothing is left.
     * Files are deleted (not moved to a recycle bin). Returns {files deleted, bytes deleted, files remaining}.
     */
    public long[] deleteTree(Path path) throws IOException {
        long files = 0;
        long bytes = 0;
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            try (Stream<Path> walk = Files.walk(path)) {
                for (Path p : walk.sorted((a, b) -> b.getNameCount() - a.getNameCount()).toList()) {
                    boolean regular = Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS);
                    long size = regular ? Files.size(p) : 0;
                    if (regular) {
                        // Originals are read-only; private data is not, but clear the flag defensively.
                        p.toFile().setWritable(true, false);
                    }
                    try {
                        Files.delete(p);
                        if (regular) {
                            files++;
                            bytes += size;
                        }
                    } catch (DirectoryNotEmptyException ignored) {
                        // something inside could not be deleted; counted below
                    }
                }
            }
        }
        long remaining = 0;
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            try (Stream<Path> walk = Files.walk(path)) {
                remaining = walk.filter(p -> Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)).count();
            }
        }
        return new long[] {files, bytes, remaining};
    }

    /** Remove an attempt's private outputs (embeddings, voice samples) as soon as the attempt has ended. */
    public long purgeAttemptPrivate(UUID projectId, UUID attemptId) throws IOException {
        Path dir = resolve(attemptWorkDir(projectId, attemptId) + "/private");
        return deleteTree(dir)[0];
    }

    public static final class UnsafePathException extends IllegalArgumentException {
        public UnsafePathException(String path) {
            super("Unsafe storage path: " + path);
        }
    }
}
