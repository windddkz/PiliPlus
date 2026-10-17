package com.example.piliplus.official;

import java.io.File;
import java.io.IOException;
import java.nio.file.*;

public final class OfficialMediaCacheCheck {
    public static void main(String[] args) throws Exception {
        Path base = Files.createTempDirectory("piliplus-media-cache-check-");
        Path root = Files.createDirectory(base.resolve("cache"));
        Path stale = Files.createDirectory(root.resolve("a".repeat(64)));
        Files.writeString(stale.resolve("old.m4s.tmp"), "old");
        Path keep = Files.writeString(base.resolve("account"), "keep");
        Files.createSymbolicLink(stale.resolve("link"), keep);
        OfficialMediaCache cache = new OfficialMediaCache(root.toFile());
        assert !Files.exists(stale) && Files.readString(keep).equals("keep");
        assert cache.acquire(0) == null
                && cache.acquire(OfficialMediaCache.MIN_FREE_BYTES - 1) == null;
        assert cache.acquire(4 * OfficialMediaCache.TRACK_BYTES - 1) == null;
        File first = cache.acquire(4 * OfficialMediaCache.TRACK_BYTES);
        assert first != null && cache.retained() == 1;
        assert cache.acquire(4 * OfficialMediaCache.TRACK_BYTES) == null;
        File[] others = new File[3];
        for (int i = 0; i < 3; i++)
            others[i] = cache.acquire(Long.MAX_VALUE);
        assert cache.retained() == 4 && cache.acquire(Long.MAX_VALUE) == null;
        Files.writeString(first.toPath().resolve("track.tmp"), "cached");
        Files.createSymbolicLink(first.toPath().resolve("link"), keep);
        cache.release(first);
        assert !first.exists() && Files.readString(keep).equals("keep") && cache.retained() == 3;
        try {
            cache.release(base.toFile());
            throw new AssertionError("Unowned path deleted");
        } catch (IllegalArgumentException expected) {
        }
        File replacement = cache.acquire(Long.MAX_VALUE);
        assert replacement != null && !replacement.equals(first);
        Path nested = Files.createDirectory(replacement.toPath().resolve("unexpected"));
        Files.writeString(nested.resolve("file"), "retain until next start");
        try {
            cache.release(replacement);
            throw new AssertionError("Unexpected tree accepted");
        } catch (IOException expected) {
        }
        assert cache.retained() == 4 && cache.acquire(Long.MAX_VALUE) == null;
        for (File other : others)
            cache.release(other);
        assert cache.retained() == 1;
        new OfficialMediaCache(root.toFile());
        assert !replacement.exists() && Files.readString(keep).equals("keep");
        Files.delete(root);
        Files.delete(keep);
        Files.delete(base);
        System.out.println(
                "Space reservation, four-item bound, ownership, cleanup, links and restart recovery passed");
    }
}
