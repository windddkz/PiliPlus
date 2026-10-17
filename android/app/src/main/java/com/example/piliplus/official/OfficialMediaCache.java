package com.example.piliplus.official;

import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** Worker-owned directories; native DASH owns the bounded per-track files inside them. */
@android.annotation.TargetApi(26) // OfficialPlayerPlugin gates this backend at Android 26.
final class OfficialMediaCache {
    static final long TRACK_BYTES = 64L * 1024 * 1024;
    static final long MIN_FREE_BYTES = 200L * 1024 * 1024;
    private final File root;
    private final Set<File> retained = new HashSet<>();

    OfficialMediaCache(File root) throws IOException {
        this.root = root;
        Files.createDirectories(root.toPath());
        if (!Files.isDirectory(root.toPath(), LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Invalid media cache root");
        // A new service owns no old items; recover files left by a terminated process.
        if (OfficialRuntimeCache
                        .prune(root,
                                "0000000000000000000000000000000000000000000000000000000000000000")
                        .failedDirectories
                != 0)
            throw new IOException("Media cache cleanup failed");
    }

    File acquire(long usableBytes) throws IOException {
        // Three preloads plus the current player, two tracks each; retain half of free space.
        if (retained.size() >= 4 || usableBytes < MIN_FREE_BYTES
                || usableBytes / 2 < (retained.size() + 1) * 2 * TRACK_BYTES)
            return null;
        File directory =
                new File(root, (UUID.randomUUID().toString() + UUID.randomUUID()).replace("-", ""));
        Files.createDirectory(directory.toPath());
        retained.add(directory);
        return directory;
    }

    void release(File directory) throws IOException {
        if (!retained.contains(directory))
            throw new IllegalArgumentException("Unowned media cache");
        Path path = directory.toPath();
        if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            try (DirectoryStream<Path> files = Files.newDirectoryStream(path)) {
                for (Path file : files)
                    Files.delete(file);
            }
        }
        Files.deleteIfExists(path);
        retained.remove(directory);
    }

    int retained() {
        return retained.size();
    }
}
