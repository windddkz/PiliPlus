package com.example.piliplus.official;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import java.lang.reflect.*;
import java.util.ArrayList;

public final class OfficialOutputWaitCheck {
    public static final class Handle {
        int releases;
        public void release() {
            releases++;
        }
    }
    private static void check(boolean value) {
        if (!value)
            throw new AssertionError();
    }
    private static Field field(Class<?> type, String name) throws Exception {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }
    private static Bundle track(String source, int id, String codec) {
        Bundle b = new Bundle();
        b.putString("source", source);
        b.putInt("id", id);
        b.putInt("bandwidth", 100);
        b.putString("codecs", codec);
        b.putStringArrayList("backups", new ArrayList<>());
        return b;
    }
    public static void main(String[] args) throws Exception {
        Looper.prepare();
        Bundle request = new Bundle();
        request.putString("source", "https://example.invalid/video");
        request.putString("audio", "https://example.invalid/audio");
        request.putBundle("audioTrack", track(request.getString("audio"), 30251, null));
        for (String codec :
                new String[] {"avc1.640033", "hvc1.2.4", "dvh1.08.07", "av01.0.08M.08"}) {
            request.putBundle("sourceTrack", track(request.getString("source"), 120, codec));
            check(OfficialRuntime.canPrepareWithoutSurface(request));
        }
        request.putBoolean("isLive", true);
        check(!OfficialRuntime.canPrepareWithoutSurface(request));
        request.remove("isLive");
        Bundle single = new Bundle(request);
        single.remove("audioTrack");
        check(!OfficialRuntime.canPrepareWithoutSurface(single));
        request.putBundle("sourceTrack", track(request.getString("source"), 120, "unknown"));
        check(!OfficialRuntime.canPrepareWithoutSurface(request));
        request.putBundle(
                "sourceTrack", track("https://example.invalid/other", 120, "avc1.640033"));
        try {
            OfficialRuntime.canPrepareWithoutSurface(request);
            throw new AssertionError();
        } catch (IllegalArgumentException expected) {
        }

        ArrayList<String> events = new ArrayList<>();
        OfficialRuntime runtime = new OfficialRuntime(null, new Handler(Looper.myLooper()),
                b -> events.add(b.getString("event") + ":" + b.getString("stage")));
        Class<?> type = OfficialRuntime.class,
                 mediaType = Class.forName(type.getName() + "$NativeMedia");
        Constructor<?> constructor =
                mediaType.getDeclaredConstructor(Object.class, boolean.class, boolean.class);
        constructor.setAccessible(true);
        Method consume =
                type.getDeclaredMethod("consumePreload", mediaType, long.class, Bundle.class);
        consume.setAccessible(true);
        Handle player = new Handle(), item = new Handle();
        Object media = constructor.newInstance(null, false, true);
        field(mediaType, "item").set(media, item);
        field(type, "player").set(runtime, player);
        field(type, "generation").setLong(runtime, 7);
        field(type, "awaitingMediaInfo").setBoolean(runtime, true);
        consume.invoke(runtime, media, 7L, new Bundle());
        check(field(type, "awaitingOutput").get(runtime) == media
                && field(type, "item").get(runtime) == item);
        check(events.size() == 1 && events.get(0).equals("preparation_stage:wait_output"));
        runtime.surface(null);
        check(field(type, "awaitingOutput").get(runtime) == media);
        runtime.release();
        check(player.releases == 1 && item.releases == 1
                && field(type, "awaitingOutput").get(runtime) == null);
        Handle stale = new Handle();
        Object late = constructor.newInstance(null, false, true);
        field(mediaType, "item").set(late, stale);
        consume.invoke(runtime, late, 7L, new Bundle());
        check(stale.releases == 1 && field(type, "awaitingOutput").get(runtime) == null);
        System.out.println(
                "Source eligibility, no-window hold, null window, cancel release and late callback passed");
    }
}
