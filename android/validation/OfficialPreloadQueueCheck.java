package com.example.piliplus.official;

import android.os.Bundle;
import android.os.SystemClock;
import java.lang.reflect.*;
import java.util.*;

/** Checks the actual runtime's queue without creating a decoder or network request. */
public final class OfficialPreloadQueueCheck {
    static void check(boolean value) {
        if (!value)
            throw new AssertionError();
    }
    static Field field(String name) throws Exception {
        Field f = OfficialRuntime.class.getDeclaredField(name);
        f.setAccessible(true);
        return f;
    }
    public static final class Handle {
        int releases;
        public void release() {
            releases++;
        }
    }
    static void selectedBeforeCleanup() throws Exception {
        Class<?> mediaType = Class.forName(OfficialRuntime.class.getName() + "$NativeMedia");
        Constructor<?> constructor =
                mediaType.getDeclaredConstructor(Object.class, boolean.class, boolean.class);
        constructor.setAccessible(true);
        Field item = mediaType.getDeclaredField("item");
        item.setAccessible(true);
        Object[] media = new Object[3];
        Handle[] handles = {new Handle(), new Handle(), new Handle()};
        Map<Object, String> names = new IdentityHashMap<>();
        List<String> calls = new ArrayList<>();
        for (int n = 0; n < 3; n++) {
            media[n] = constructor.newInstance(null, false, true);
            item.set(media[n], handles[n]);
            names.put(media[n], "m" + n);
        }
        OfficialPreloadCache<Object> cache =
                new OfficialPreloadCache<>(new OfficialPreloadCache.Backend<Object>() {
                    public void add(long r, long t, Object i) {}
                    public void detach(long r, long t, Object i) {
                        calls.add("detach:" + names.get(i));
                    }
                    public void interrupt(Object i) {
                        calls.add("interrupt:" + names.get(i));
                    }
                    public void remove(long r, long t, Object i) {
                        calls.add("remove:" + names.get(i));
                    }
                    public void release(Object i) throws Exception {
                        ((Handle) item.get(i)).release();
                    }
                    public void failed(Exception e) {
                        throw new AssertionError(e);
                    }
                }, (delay, action) -> {}, SystemClock::elapsedRealtime);
        for (int n = 0; n < 3; n++)
            check(cache.add("m" + n, media[n]));
        OfficialRuntime runtime = new OfficialRuntime(null, null, null);
        Handle player = new Handle();
        field("preloads").set(runtime, cache);
        field("player").set(runtime, player);
        field("generation").setLong(runtime, 7);
        field("awaitingPreload").setLong(runtime, 7);
        Method prepare = OfficialRuntime.class.getDeclaredMethod("prepareNativeMedia", String.class,
                mediaType, boolean.class, long.class, Bundle.class, long.class);
        prepare.setAccessible(true);
        prepare.invoke(runtime, "m0", media[0], true, 7L, new Bundle(),
                SystemClock.elapsedRealtime() + 20000);
        check(calls.equals(
                List.of("detach:m0", "interrupt:m1", "remove:m1", "interrupt:m2", "remove:m2")));
        check(cache.size() == 3 && handles[0].releases == 0 && handles[1].releases == 0
                && handles[2].releases == 0);
        cache.event(999, 1, "DETACHED", 0);
        check(cache.size() == 3);
        runtime.release();
        check(player.releases == 1 && handles[0].releases == 0);
        cache.event(7, 1, "DETACHED", 0);
        cache.event(7, 1, "DETACHED", 0);
        check(handles[0].releases == 1 && cache.size() == 2);
        cache.event(8, 3, "REMOVED", 0);
        cache.event(9, 5, "REMOVED", 0);
        check(cache.size() == 0 && handles[1].releases == 1 && handles[2].releases == 1);
        System.out.println(
                "Actual cached preparation: selected detach before cleanup, ACK ownership and late cancellation passed");
    }
    public static void main(String[] args) throws Exception {
        List<String> calls = new ArrayList<>();
        OfficialPreloadCache<String> cache =
                new OfficialPreloadCache<>(new OfficialPreloadCache.Backend<String>() {
                    public void add(long r, long t, String i) {
                        calls.add("add:" + i);
                    }
                    public void detach(long r, long t, String i) {
                        throw new AssertionError();
                    }
                    public void interrupt(String i) {
                        calls.add("interrupt:" + i);
                    }
                    public void remove(long r, long t, String i) {
                        calls.add("remove:" + i);
                    }
                    public void release(String i) {
                        calls.add("release:" + i);
                    }
                    public void failed(Exception e) {
                        throw new AssertionError(e);
                    }
                }, (delay, action) -> {}, SystemClock::elapsedRealtime);
        OfficialRuntime runtime = new OfficialRuntime(null, null, null);
        field("preloads").set(runtime, cache);
        @SuppressWarnings("unchecked")
        Map<String, Bundle> queue = (Map<String, Bundle>) field("pendingPreloads").get(runtime);
        Method drain = OfficialRuntime.class.getDeclaredMethod("drainPreloads");
        drain.setAccessible(true);
        Method take = OfficialRuntime.class.getDeclaredMethod("takePreloadProbe", String.class);
        take.setAccessible(true);
        for (String key : new String[] {"a", "b", "c"})
            check(cache.add(key, key));
        Bundle request = new Bundle();
        request.putLong("preloadQueuedAt", SystemClock.elapsedRealtime());
        queue.put("next", request);
        drain.invoke(runtime);
        check(cache.size() == 3 && queue.size() == 1
                && calls.equals(List.of("add:a", "add:b", "add:c", "interrupt:a", "remove:a")));
        drain.invoke(runtime);
        check(calls.size() == 5 && queue.size() == 1);
        cache.event(999, 1, "REMOVED", 0);
        drain.invoke(runtime);
        check(cache.size() == 3 && calls.size() == 5);
        take.invoke(runtime, new Object[] {null});
        check(queue.isEmpty());
        drain.invoke(runtime);
        check(calls.size() == 5);
        Bundle expired = new Bundle();
        expired.putLong("preloadQueuedAt", SystemClock.elapsedRealtime() - 30001);
        queue.put("expired", expired);
        drain.invoke(runtime);
        check(queue.isEmpty() && calls.size() == 5);
        queue.put("b", request);
        drain.invoke(runtime);
        check(queue.isEmpty() && calls.size() == 5);
        field("player").set(runtime, new Object());
        queue.put("next", request);
        drain.invoke(runtime);
        check(queue.isEmpty() && calls.size() == 5);
        selectedBeforeCleanup();
        System.out.println(
                "Actual queue: capacity, no early release/allocation, wrong ACK, open cancellation, expiry, duplicate and foreground guards passed");
    }
}
