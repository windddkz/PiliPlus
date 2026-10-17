package com.example.piliplus.official;

import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.function.LongSupplier;

/** Worker-owned cache leases; a native writer has exclusive access until its release completes. */
@android.annotation.TargetApi(26)
final class OfficialMediaCache {
    static final long TRACK_BYTES = 64L * 1024 * 1024;
    static final long ENTRY_BYTES = 128L * 1024 * 1024;
    static final long MIN_FREE_BYTES = 200L * 1024 * 1024;
    static final int MAX_ENTRIES = 64;
    static final long TOTAL_BYTES = 2L * 1024 * 1024 * 1024;
    static final long TTL_MILLIS = 20 * 60 * 1000L;
    private final Path root;
    private final LongSupplier now;
    private final int capacity;
    private final long ttl;
    private final Map<String, Entry> entries = new LinkedHashMap<>();

    private static final class Entry {
        final Path directory;
        long touched;
        Lease lease;
        boolean discard;
        boolean reusable = true;
        Entry(Path directory, long touched) {
            this.directory = directory;
            this.touched = touched;
        }
    }
    static final class Lease {
        final File directory;
        final long trackBytes;
        final boolean reused;
        private final Entry entry;
        private Lease(Entry entry, int tracks, boolean reused) {
            this.entry = entry;
            this.directory = entry.directory.toFile();
            // The remaining MiB covers the bounded per-track index files.
            this.trackBytes = Math.min(TRACK_BYTES, (ENTRY_BYTES - 1024 * 1024) / tracks);
            this.reused = reused;
        }
    }

    OfficialMediaCache(File root) throws IOException {
        this(root, System::currentTimeMillis, MAX_ENTRIES, TTL_MILLIS);
    }
    OfficialMediaCache(File root, LongSupplier now, int capacity, long ttl) throws IOException {
        if (capacity < 1 || capacity > MAX_ENTRIES || ttl <= 0)
            throw new IllegalArgumentException("Invalid media cache budget");
        this.root = root.toPath();
        this.now = now;
        this.capacity = capacity;
        this.ttl = ttl;
        Files.createDirectories(this.root);
        if (!Files.isDirectory(this.root, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Invalid media cache root");
        try (DirectoryStream<Path> paths = Files.newDirectoryStream(this.root)) {
            for (Path path : paths) {
                String key = path.getFileName().toString();
                if (!key.matches("[0-9a-f]{64}"))
                    continue;
                Path complete = path.resolve(".complete");
                if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)
                        && Files.isRegularFile(complete, LinkOption.NOFOLLOW_LINKS)) {
                    Entry entry = new Entry(path, Files.getLastModifiedTime(complete).toMillis());
                    if (fresh(entry) && bounded(path)) {
                        entries.put(key, entry);
                        continue;
                    }
                }
                delete(path);
            }
        }
        prune();
        while (entries.size() > capacity || reservedBytes(null) > TOTAL_BYTES)
            evictOldest(null);
    }

    Lease acquire(String key, int tracks, long usableBytes) throws IOException {
        if ((key != null && !key.matches("[0-9a-f]{64}")) || tracks < 1 || tracks > 21)
            throw new IllegalArgumentException("Invalid media cache identity");
        boolean reusable = key != null;
        if (key == null)
            key = (UUID.randomUUID().toString() + UUID.randomUUID()).replace("-", "");
        prune();
        Entry entry = entries.get(key);
        if (entry != null && entry.lease != null)
            return null;
        long budget = Math.min(TOTAL_BYTES, Math.max(0, usableBytes / 2));
        if (usableBytes < MIN_FREE_BYTES || budget < ENTRY_BYTES)
            return null;
        while (entries.size() + (entry == null ? 1 : 0) > capacity
                || reservedBytes(entry) > budget - ENTRY_BYTES) {
            if (!evictOldest(entry))
                return null;
            entry = entries.get(key);
        }
        boolean reused = entry != null;
        if (entry == null) {
            Path path = root.resolve(key);
            Files.createDirectory(path);
            entry = new Entry(path, now.getAsLong());
            entry.reusable = reusable;
            entries.put(key, entry);
        }
        // A crash during the lease leaves no completion marker; startup reclaims that directory.
        Files.deleteIfExists(entry.directory.resolve(".complete"));
        Lease lease = new Lease(entry, tracks, reused);
        entry.touched = now.getAsLong();
        entry.lease = lease;
        return lease;
    }

    void release(Lease lease, boolean keep) throws IOException {
        Entry entry = lease.entry;
        if (entry.lease != lease || entries.get(entry.directory.getFileName().toString()) != entry)
            throw new IllegalArgumentException("Unowned media cache lease");
        // Native release has completed; failed filesystem cleanup must remain retryable.
        entry.lease = null;
        try {
            if (keep && entry.reusable && !entry.discard && bounded(entry.directory)) {
                Path complete = entry.directory.resolve(".complete");
                Files.write(complete, new byte[0], StandardOpenOption.CREATE_NEW);
                entry.touched = now.getAsLong();
                Files.setLastModifiedTime(
                        complete, java.nio.file.attribute.FileTime.fromMillis(entry.touched));
            } else {
                delete(entry.directory);
                entries.remove(entry.directory.getFileName().toString());
            }
        } catch (IOException | SecurityException error) {
            entry.discard = true;
            throw error;
        }
        prune();
    }

    void clear() throws IOException {
        for (Entry entry : new ArrayList<>(entries.values())) {
            if (entry.lease != null)
                entry.discard = true;
            else
                remove(entry);
        }
    }
    void prune() throws IOException {
        for (Entry entry : new ArrayList<>(entries.values()))
            if (entry.lease == null && (entry.discard || !fresh(entry)))
                remove(entry);
    }
    int retained() {
        return entries.size();
    }
    boolean contains(String key) {
        Entry entry = entries.get(key);
        if (entry == null || entry.lease != null || entry.discard || !fresh(entry)
                || !Files.isRegularFile(
                        entry.directory.resolve(".complete"), LinkOption.NOFOLLOW_LINKS))
            return false;
        int tracks = 0;
        try (DirectoryStream<Path> files = Files.newDirectoryStream(entry.directory, "*.map")) {
            for (Path map : files) {
                String name = map.getFileName().toString();
                Path data = map.resolveSibling(name.substring(0, name.length() - 4) + ".bin");
                if (Files.isRegularFile(map, LinkOption.NOFOLLOW_LINKS) && Files.size(map) > 0
                        && Files.isRegularFile(data, LinkOption.NOFOLLOW_LINKS)
                        && Files.size(data) > 0)
                    tracks++;
            }
        } catch (IOException | SecurityException unavailable) {
            return false;
        }
        return tracks >= 2;
    }
    private long reservedBytes(Entry exclude) throws IOException {
        long bytes = 0;
        for (Entry entry : entries.values()) {
            if (entry == exclude)
                continue;
            if (entry.lease != null) {
                bytes += ENTRY_BYTES;
            } else {
                try (DirectoryStream<Path> files = Files.newDirectoryStream(entry.directory)) {
                    for (Path file : files)
                        bytes += Files.readAttributes(file, BasicFileAttributes.class,
                                              LinkOption.NOFOLLOW_LINKS)
                                         .size();
                }
            }
        }
        return bytes;
    }
    private boolean fresh(Entry entry) {
        long age = now.getAsLong() - entry.touched;
        return age >= 0 && age < ttl;
    }
    private boolean evictOldest(Entry preserve) throws IOException {
        Entry oldest = null;
        for (Entry entry : entries.values())
            if (entry != preserve && entry.lease == null
                    && (oldest == null || entry.touched < oldest.touched))
                oldest = entry;
        if (oldest == null)
            return false;
        remove(oldest);
        return true;
    }
    private void remove(Entry entry) throws IOException {
        delete(entry.directory);
        entries.remove(entry.directory.getFileName().toString());
    }
    private static boolean bounded(Path directory) throws IOException {
        long bytes = 0;
        int count = 0;
        try (DirectoryStream<Path> files = Files.newDirectoryStream(directory)) {
            for (Path file : files) {
                BasicFileAttributes attrs = Files.readAttributes(
                        file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                if (!attrs.isRegularFile() || ++count > 43 || attrs.size() > ENTRY_BYTES - bytes)
                    return false;
                bytes += attrs.size();
            }
        }
        return true;
    }
    private static void delete(Path directory) throws IOException {
        Files.walkFileTree(directory, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                    throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }
            @Override
            public FileVisitResult postVisitDirectory(Path path, IOException error)
                    throws IOException {
                if (error != null)
                    throw error;
                Files.delete(path);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
