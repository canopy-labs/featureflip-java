package io.featureflip.client;

import io.featureflip.client.internal.RedactingLogger;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.EnumSet;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Persists the snapshot text to one file, written atomically.
 *
 * <p>The store is opaque: it neither parses nor checks what it holds, so one instance can
 * be shared by several SDK keys. It never throws: a failure is logged and treated as a
 * miss ({@link #get()}) or a dropped write ({@link #put(String)}). The parent directory
 * must already exist, it is never created.
 *
 * <p>A write goes to a uniquely named temporary file in the same directory, created
 * exclusively with owner-only permissions (0600 on POSIX file systems), then renamed over
 * the target. The target therefore ends up owner-only even if it pre-existed with broader
 * permissions. On file systems without POSIX permissions the file gets the platform
 * default.
 *
 * <p>There is deliberately no fsync before the rename, so a power loss can leave a
 * zero-length or partial file. The SDK reads that as {@code snapshot-invalid}, logs a
 * warning and serves defaults, and the next successful write replaces it. A process
 * killed mid-write can leave a {@code <name>.<pid>.<hex>.tmp} file next to the snapshot;
 * it is never read.
 */
public final class FileSnapshotStore implements SnapshotStore {
    private static final RedactingLogger log = RedactingLogger.get(FileSnapshotStore.class);
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Set<PosixFilePermission> OWNER_ONLY = PosixFilePermissions.fromString("rw-------");

    private final Path path;
    private final Consumer<String> warn;

    public FileSnapshotStore(String path) {
        this(Paths.get(path));
    }

    public FileSnapshotStore(Path path) {
        this(path, message -> log.warn("{}", message));
    }

    /** Test seam: receives the warning text instead of the logger. */
    FileSnapshotStore(Path path, Consumer<String> warn) {
        if (path == null) {
            throw new IllegalArgumentException("path must not be null");
        }
        this.path = path.toAbsolutePath();
        this.warn = warn;
    }

    @Override
    public String get() {
        try {
            // Invalid UTF-8 is replaced, which then fails JSON parsing as snapshot-invalid.
            return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
        } catch (NoSuchFileException e) {
            return null;
        } catch (IOException | RuntimeException e) {
            warn.accept("Snapshot file read failed for " + path + ": " + e);
            return null;
        }
    }

    @Override
    public void put(String text) {
        Path tmp = path.resolveSibling(path.getFileName() + "."
            + ProcessHandle.current().pid() + "." + randomHex() + ".tmp");
        try {
            try (SeekableByteChannel channel = Files.newByteChannel(
                    tmp, EnumSet.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE), ownerOnly())) {
                ByteBuffer buffer = ByteBuffer.wrap(text.getBytes(StandardCharsets.UTF_8));
                while (buffer.hasRemaining()) {
                    channel.write(buffer);
                }
            }
            if (posix()) {
                // Creation modes are subject to the umask; pin the final mode explicitly.
                Files.setPosixFilePermissions(tmp, OWNER_ONLY);
            }
            try {
                Files.move(tmp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException | RuntimeException e) {
            warn.accept("Snapshot file write failed for " + path + ": " + e);
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException | RuntimeException ignored) {
                // Best effort: the leftover temp file is never read.
            }
        }
    }

    private boolean posix() {
        return path.getFileSystem().supportedFileAttributeViews().contains("posix");
    }

    private FileAttribute<?>[] ownerOnly() {
        if (!posix()) {
            return new FileAttribute<?>[0];
        }
        return new FileAttribute<?>[] {PosixFilePermissions.asFileAttribute(OWNER_ONLY)};
    }

    private static String randomHex() {
        byte[] bytes = new byte[6];
        RANDOM.nextBytes(bytes);
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
