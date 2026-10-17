package com.example.piliplus.official;

import android.content.Context;
import android.os.Bundle;
import android.os.Looper;
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
    private static Context context;
    static Context context() throws Exception {
        if (context == null) {
            if (Looper.myLooper() == null)
                Looper.prepareMainLooper();
            Class<?> type = Class.forName("android.app.ActivityThread");
            Object thread = type.getMethod("systemMain").invoke(null);
            Context system = (Context) type.getMethod("getSystemContext").invoke(thread);
            context = system.createPackageContext("com.android.shell", 0);
            Field operationPackage = context.getClass().getDeclaredField("mOpPackageName");
            operationPackage.setAccessible(true);
            operationPackage.set(context, "com.android.shell");
            Field attribution = context.getClass().getDeclaredField("mAttributionSource");
            attribution.setAccessible(true);
            attribution.set(context,
                    new android.content.AttributionSource.Builder(android.os.Process.myUid())
                            .setPackageName("com.android.shell")
                            .build());
        }
        return context;
    }
    public static final class Control {
        int pauses, resumes;
        public int pauseAllTask() {
            pauses++;
            return 0;
        }
        public int resumeAllTask() {
            resumes++;
            return 0;
        }
    }
    public static final class Handle {
        int releases;
        public void release() {
            releases++;
        }
    }
    static void selectedWithRetention() throws Exception {
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
        OfficialRuntime runtime = new OfficialRuntime(context(), null, null);
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
        check(calls.equals(List.of("detach:m0")));
        check(cache.size() == 3 && handles[0].releases == 0 && handles[1].releases == 0
                && handles[2].releases == 0);
        cache.event(999, 1, "DETACHED", 0);
        check(cache.size() == 3);
        runtime.release();
        check(player.releases == 1 && handles[0].releases == 0);
        cache.event(7, 1, "DETACHED", 0);
        cache.event(7, 1, "DETACHED", 0);
        check(handles[0].releases == 1 && cache.size() == 2);
        check(calls.equals(List.of("detach:m0")));
        cache.discardExcept(null);
        cache.event(8, 3, "REMOVED", 0);
        cache.event(9, 5, "REMOVED", 0);
        check(cache.size() == 0 && handles[1].releases == 1 && handles[2].releases == 1);
        System.out.println(
                "Actual cached preparation: other candidates retained, ACK ownership and late cancellation passed");
    }
    public static void main(String[] args) throws Exception {
        OfficialRuntime priorityRuntime = new OfficialRuntime(context(), null, null);
        Method priority = OfficialRuntime.class.getDeclaredMethod("preloadPriority", String.class);
        priority.setAccessible(true);
        List<String> candidates = new ArrayList<>();
        for (int i = 0; i < 12; i++)
            candidates.add("BV1Sc411U7m6:" + (i + 1));
        priorityRuntime.enablePreloads(true, candidates, 10);
        check((int) priority.invoke(priorityRuntime, candidates.get(9)) == 9);
        check((int) priority.invoke(priorityRuntime, candidates.get(10)) == 110);
        Collections.reverse(candidates);
        priorityRuntime.enablePreloads(true, candidates, 6);
        check((int) priority.invoke(priorityRuntime, candidates.get(0)) == 0);
        check((int) priority.invoke(priorityRuntime, candidates.get(6)) == 106);
        priorityRuntime.enablePreloads(false);
        check((int) priority.invoke(priorityRuntime, candidates.get(0)) == Integer.MAX_VALUE);
        List<String> calls = new ArrayList<>();
        Map<String, long[]> removals = new LinkedHashMap<>();
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
                        removals.put(i, new long[] {r, t});
                    }
                    public void release(String i) {
                        calls.add("release:" + i);
                    }
                    public void failed(Exception e) {
                        throw new AssertionError(e);
                    }
                }, (delay, action) -> {}, SystemClock::elapsedRealtime, 30);
        OfficialRuntime runtime = new OfficialRuntime(context(), null, null);
        field("preloads").set(runtime, cache);
        Control control = new Control();
        field("preloader").set(runtime, control);
        field("preloadsEnabled").setBoolean(runtime, true);
        field("preloadsPaused").setBoolean(runtime, true);
        field("preloadPermitUntil").setLong(runtime, SystemClock.elapsedRealtime() + 15000);
        @SuppressWarnings("unchecked")
        Map<String, Bundle> queue = (Map<String, Bundle>) field("pendingPreloads").get(runtime);
        Method drain = OfficialRuntime.class.getDeclaredMethod("drainPreloads");
        drain.setAccessible(true);
        Method take = OfficialRuntime.class.getDeclaredMethod("takePreloadProbe", String.class);
        take.setAccessible(true);
        String[] keys = new String[30];
        for (int i = 0; i < keys.length; i++)
            keys[i] = Character.toString((char) ('a' + i));
        Bundle request = new Bundle();
        request.putLong("preloadQueuedAt", SystemClock.elapsedRealtime());
        check(OfficialRuntime.PRELOAD_PARALLELISM == 4);
        for (int i = 0; i < 4; i++)
            check(cache.add(keys[i], keys[i]));
        queue.put("next", request);
        drain.invoke(runtime);
        check(cache.preparing() == 4 && queue.size() == 1 && calls.size() == 4);
        for (int i = 0; i < 3; i++)
            cache.event(0, 2L * i + 1, "READY", 0);
        field("player").set(runtime, new Handle());
        field("prepared").setBoolean(runtime, true);
        field("playbackBuffered").setLong(runtime, 5000);
        drain.invoke(runtime);
        check(cache.preparing() == 1 && queue.size() == 1 && calls.size() == 4);
        field("player").set(runtime, null);
        field("prepared").setBoolean(runtime, false);
        field("playbackBuffered").setLong(runtime, 0);
        queue.clear();
        for (int i = 0; i < keys.length; i++) {
            if (i >= 4)
                check(cache.add(keys[i], keys[i]));
            cache.event(0, 2L * i + 1, "READY", 0);
        }
        queue.put("next", request);
        drain.invoke(runtime);
        check(control.resumes == 1 && cache.size() == 30 && queue.size() == 1
                && calls.subList(30, calls.size()).equals(List.of("interrupt:a", "remove:a")));
        drain.invoke(runtime);
        check(calls.size() == 32 && queue.size() == 1);
        cache.event(999, 1, "REMOVED", 0);
        take.invoke(runtime, new Object[] {null});
        check(cache.size() == 30 && queue.size() == 1 && calls.size() == 32);
        queue.clear();
        Bundle expired = new Bundle();
        expired.putLong("preloadQueuedAt", SystemClock.elapsedRealtime() - 30001);
        queue.put("expired", expired);
        queue.put("b", request);
        drain.invoke(runtime);
        check(queue.isEmpty() && calls.size() == 32);
        field("player").set(runtime, new Handle());
        queue.put("next", request);
        drain.invoke(runtime);
        check(queue.size() == 1 && control.pauses == 1 && calls.size() == 32);
        field("prepared").setBoolean(runtime, true);
        field("playbackBuffered").setLong(runtime, 4999);
        drain.invoke(runtime);
        check(control.resumes == 1 && queue.size() == 1);
        field("playbackBuffered").setLong(runtime, 5000);
        drain.invoke(runtime);
        check(control.resumes == 2 && calls.size() == 32);
        field("preloadPermitUntil").setLong(runtime, 0);
        drain.invoke(runtime);
        check(control.pauses == 2 && queue.size() == 1);
        runtime.enablePreloads(false);
        check(queue.isEmpty() && cache.size() == 30 && calls.size() == 32);
        Method scope = OfficialRuntime.class.getDeclaredMethod("syncPreloadScope", Bundle.class);
        scope.setAccessible(true);
        Bundle account = new Bundle();
        account.putString("preloadScope", "account-a");
        scope.invoke(runtime, account);
        check(calls.size() == 32);
        account.putString("preloadScope", "account-b");
        scope.invoke(runtime, account);
        check(removals.size() == 30 && cache.size() == 30 && queue.isEmpty());
        for (long[] movement : removals.values())
            cache.event(movement[0], movement[1], "REMOVED", 0);
        check(cache.size() == 0);
        Method identity =
                OfficialRuntime.class.getDeclaredMethod("preloadKey", Bundle.class, boolean.class);
        identity.setAccessible(true);
        Bundle source = new Bundle();
        source.putString("preloadToken", "account-a-token");
        source.putString("source", "https://example.invalid/v");
        source.putString("audio", "https://example.invalid/a");
        String original = (String) identity.invoke(runtime, source, false);
        source.putLong("position", 12345);
        check(original.equals(identity.invoke(runtime, source, false)));
        for (String key : new String[] {"source", "audio", "preloadToken"}) {
            Bundle changed = new Bundle(source);
            changed.putString(key,
                    key.equals("preloadToken") ? "account-b-token"
                                               : "https://example.invalid/changed");
            check(!original.equals(identity.invoke(runtime, changed, false)));
        }
        for (String name : new String[] {"sourceTrack", "audioTrack"}) {
            Bundle track = new Bundle();
            track.putString("source", "https://example.invalid/track");
            track.putStringArrayList("backups", new ArrayList<>());
            track.putInt("id", name.equals("sourceTrack") ? 80 : 30280);
            source.putBundle(name, track);
            String before = (String) identity.invoke(runtime, source, false);
            track.putInt("id", name.equals("sourceTrack") ? 120 : 30251);
            check(!before.equals(identity.invoke(runtime, source, false)));
        }
        selectedWithRetention();
        System.out.println(
                "Actual queue: bounded retention, wrong ACK, expiry, duplicates, foreground priority, permit expiry, account invalidation and media identity passed");
    }
}
