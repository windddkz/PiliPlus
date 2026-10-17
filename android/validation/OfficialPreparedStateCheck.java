package com.example.piliplus.official;

import android.os.*;
import dalvik.system.DexClassLoader;
import java.lang.reflect.*;
import java.util.concurrent.*;

/** Exercises the real runtime callbacks when JNI omits the item-dependent prepared callback. */
public final class OfficialPreparedStateCheck {
    public static final class Media {
        public int getWidth() {
            return 1920;
        }
        public int getHeight() {
            return 1080;
        }
    }
    public static final class Item {
        public Media getMediaInfo() {
            return new Media();
        }
    }
    public static final class Player {
        int starts, seeks;
        public void setSpeed(float speed) {}
        public void setVolume(float volume) {}
        public void seekTo(boolean accurate, long position, long timeout) {
            seeks++;
        }
        public void start() {
            starts++;
        }
    }
    static void set(OfficialRuntime runtime, String name, Object value) throws Exception {
        OfficialPreloadQueueCheck.field(name).set(runtime, value);
    }
    static void invoke(Object callback, Object... args) throws Exception {
        for (Method method : callback.getClass().getInterfaces()[0].getMethods()) {
            if (method.getName().equals("invoke")) {
                method.invoke(callback, args);
                return;
            }
        }
        throw new AssertionError("Missing Kotlin callback");
    }
    static void flush(Handler worker) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        worker.post(() -> {
            worker.removeCallbacksAndMessages(null);
            done.countDown();
        });
        if (!done.await(2, TimeUnit.SECONDS))
            throw new AssertionError("Worker stalled");
    }
    public static void main(String[] args) throws Exception {
        android.content.Context context = OfficialPreloadQueueCheck.context();
        HandlerThread thread = new HandlerThread("prepared-state-check");
        thread.start();
        Handler worker = new Handler(thread.getLooper());
        try {
            DexClassLoader loader = new DexClassLoader(args[0], "/data/local/tmp", null,
                    OfficialPreparedStateCheck.class.getClassLoader());
            Class<?> state = loader.loadClass("com.bilibili.ijk.player.IJKState");
            Method box = state.getMethod("box-impl", int.class);
            for (boolean ordinaryFirst : new boolean[] {false, true}) {
                int[] emitted = {0};
                OfficialRuntime runtime = new OfficialRuntime(context, worker, event -> {
                    if ("error".equals(event.getString("event")))
                        throw new AssertionError(event);
                    if ("prepared".equals(event.getString("event")))
                        emitted[0]++;
                });
                Player player = new Player();
                set(runtime, "loader", loader);
                set(runtime, "player", player);
                set(runtime, "item", new Item());
                set(runtime, "generation", 7L);
                set(runtime, "route", new OfficialAudioRoute(context, worker, 1, () -> {}));
                set(runtime, "appliedEndpoint", 1);
                set(runtime, "startPosition", 940559L);
                set(runtime, "wantedPlaying", true);
                set(runtime, "speed", 1f);
                set(runtime, "volume", 1f);
                Method factory = OfficialRuntime.class.getDeclaredMethod(
                        "callback", int.class, String.class, long.class);
                factory.setAccessible(true);
                Object changed = factory.invoke(runtime, 3, "state", 7L);
                Object prepared = factory.invoke(runtime, 1, "prepared", 7L);
                invoke(changed, player, box.invoke(null, 0), box.invoke(null, 1));
                flush(worker);
                if (emitted[0] != 0)
                    throw new AssertionError("Preparing is not prepared");
                if (ordinaryFirst)
                    invoke(prepared, player);
                invoke(changed, player, box.invoke(null, 1), box.invoke(null, 2));
                flush(worker);
                if (emitted[0] != 1 || player.starts != 1 || player.seeks != 1)
                    throw new AssertionError("Prepared state must start and seek exactly once");
                invoke(prepared, player);
                invoke(changed, player, box.invoke(null, 1), box.invoke(null, 2));
                flush(worker);
                if (emitted[0] != 1 || player.starts != 1 || player.seeks != 1)
                    throw new AssertionError("Duplicate preparation repeated playback actions");
                set(runtime, "prepared", false);
                set(runtime, "generation", 8L);
                invoke(changed, player, box.invoke(null, 1), box.invoke(null, 2));
                invoke(prepared, player);
                flush(worker);
                set(runtime, "generation", 7L);
                set(runtime, "player", null);
                invoke(changed, player, box.invoke(null, 1), box.invoke(null, 2));
                flush(worker);
                if (emitted[0] != 1)
                    throw new AssertionError("Stale callback revived playback");
            }
            System.out.println(
                    "Prepared-state fallback, duplicate notifications and stale callbacks passed");
        } finally {
            worker.removeCallbacksAndMessages(null);
            thread.quitSafely();
            thread.join();
        }
    }
}
