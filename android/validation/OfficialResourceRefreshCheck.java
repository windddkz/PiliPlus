package com.example.piliplus.official;

import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

public final class OfficialResourceRefreshCheck {
    public static void main(String[] args) throws Exception {
        OfficialResourceRefresh<String> requests = new OfficialResourceRefresh<>();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            requests.open();
            ArrayBlockingQueue<Long> ids = new ArrayBlockingQueue<>(4);
            Future<String> first = pool.submit(() -> requests.await(1000, ids::add));
            long id = ids.poll(1, TimeUnit.SECONDS);
            assert !requests.complete(id + 1, "wrong");
            assert requests.complete(id, "fresh");
            assert "fresh".equals(first.get(1, TimeUnit.SECONDS));
            assert !requests.complete(id, "duplicate");
            Future<String> canceled = pool.submit(() -> requests.await(10000, ids::add));
            long old = ids.poll(1, TimeUnit.SECONDS);
            requests.cancel();
            assert canceled.get(1, TimeUnit.SECONDS) == null;
            requests.open();
            Future<String> next = pool.submit(() -> requests.await(1000, ids::add));
            long current = ids.poll(1, TimeUnit.SECONDS);
            assert current > old && !requests.complete(old, "late");
            assert requests.complete(current, "new");
            assert "new".equals(next.get(1, TimeUnit.SECONDS));
            AtomicLong expired = new AtomicLong();
            assert requests.await(5, expired::set) == null;
            assert !requests.complete(expired.get(), "expired");
            requests.cancel();
            assert requests.await(1000, n -> { throw new AssertionError("Published after close"); })
                    == null;
            System.out.println(
                    "Resource refresh reply, timeout, cancellation and stale-reply checks passed");
        } finally {
            pool.shutdownNow();
        }
    }
}
