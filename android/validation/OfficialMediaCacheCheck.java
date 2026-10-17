package com.example.piliplus.official;

import java.io.File;
import java.nio.file.*;
import java.util.concurrent.atomic.AtomicLong;

public final class OfficialMediaCacheCheck {
    private static String key(int n) {
        return String.format("%064x", n);
    }
    public static void main(String[] args) throws Exception {
        Path base = Files.createTempDirectory("piliplus-media-cache-check-");
        Path root = Files.createDirectory(base.resolve("cache"));
        Path stale = Files.createDirectory(root.resolve(key(1)));
        Path keep = Files.writeString(base.resolve("account"), "keep");
        Files.createSymbolicLink(stale.resolve("link"), keep);
        AtomicLong now = new AtomicLong(System.currentTimeMillis());
        OfficialMediaCache cache = new OfficialMediaCache(root.toFile(), now::get, 3, 1000);
        assert !Files.exists(stale) && Files.readString(keep).equals("keep");
        assert cache.acquire(key(1), 2, 0) == null;
        var first = cache.acquire(key(1), 2, Long.MAX_VALUE);
        assert first != null && !first.reused;
        assert first.trackBytes * 2 < OfficialMediaCache.ENTRY_BYTES;
        assert cache.acquire(key(1), 2, Long.MAX_VALUE) == null;
        Files.writeString(first.directory.toPath().resolve("80.bin"), "encoded data");
        cache.release(first, true);
        var reused = cache.acquire(key(1), 2, Long.MAX_VALUE);
        assert reused.reused && reused.directory.equals(first.directory);
        assert Files.readString(reused.directory.toPath().resolve("80.bin")).equals("encoded data");
        try {
            cache.release(first, true);
            throw new AssertionError("Stale lease accepted");
        } catch (IllegalArgumentException expected) {
        }
        var second = cache.acquire(key(2), 21, Long.MAX_VALUE);
        assert second.trackBytes * 21 < OfficialMediaCache.ENTRY_BYTES;
        var third = cache.acquire(key(3), 2, Long.MAX_VALUE);
        assert cache.acquire(key(4), 2, Long.MAX_VALUE) == null;
        cache.release(second, true);
        now.incrementAndGet();
        var fourth = cache.acquire(key(4), 2, Long.MAX_VALUE);
        assert fourth != null && !second.directory.exists() && reused.directory.exists();
        cache.release(reused, true);
        cache.release(third, true);
        cache.release(fourth, true);
        cache = new OfficialMediaCache(root.toFile(), now::get, 3, 1000);
        assert cache.retained() == 3;
        var restart = cache.acquire(key(1), 2, Long.MAX_VALUE);
        assert restart.reused;
        cache.clear();
        assert cache.retained() == 1 && restart.directory.exists();
        cache.release(restart, true);
        assert cache.retained() == 0;
        var expiring = cache.acquire(key(5), 2, Long.MAX_VALUE);
        cache.release(expiring, true);
        now.addAndGet(1000);
        cache.prune();
        assert !expiring.directory.exists();
        var linked = cache.acquire(key(6), 2, Long.MAX_VALUE);
        Files.createSymbolicLink(linked.directory.toPath().resolve("link"), keep);
        cache.release(linked, true);
        assert !linked.directory.exists() && Files.readString(keep).equals("keep");
        var failed = cache.acquire(key(8), 2, Long.MAX_VALUE);
        Files.writeString(failed.directory.toPath().resolve(".complete"), "unexpected marker");
        try {
            cache.release(failed, true);
            throw new AssertionError("Marker collision accepted");
        } catch (java.io.IOException expected) {
        }
        cache.prune();
        assert !failed.directory.exists() && cache.retained() == 0;
        var transientLease = cache.acquire(null, 2, Long.MAX_VALUE);
        cache.release(transientLease, true);
        assert !transientLease.directory.exists();
        var interrupted = cache.acquire(key(7), 2, Long.MAX_VALUE);
        new OfficialMediaCache(root.toFile(), now::get, 3, 1000);
        assert !interrupted.directory.exists();
        Files.delete(root);
        Files.delete(keep);
        Files.delete(base);
        System.out.println(
                "Reuse, exclusive leases, LRU, budget, expiry, invalidation, links and crash recovery passed");
    }
}
