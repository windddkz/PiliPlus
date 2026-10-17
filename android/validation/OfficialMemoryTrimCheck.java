package com.example.piliplus.official;

import android.content.ComponentCallbacks2;
import android.os.*;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Runs the Service callback and its posted runtime cleanup with controlled native ACKs. */
public final class OfficialMemoryTrimCheck {
    private static void set(OfficialPlaybackService service, String name, Object value)
            throws Exception {
        Field field = OfficialPlaybackService.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(service, value);
    }

    public static void main(String[] args) throws Exception {
        Looper.prepareMainLooper();
        HandlerThread thread = new HandlerThread("trim-check");
        thread.start();
        Handler worker = new Handler(thread.getLooper());
        try {
            for (int level : new int[] {ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN,
                         ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE,
                         ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW,
                         ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL,
                         ComponentCallbacks2.TRIM_MEMORY_BACKGROUND,
                         ComponentCallbacks2.TRIM_MEMORY_MODERATE,
                         ComponentCallbacks2.TRIM_MEMORY_COMPLETE}) {
                OfficialForegroundPreloadCheck.Queue background =
                        new OfficialForegroundPreloadCheck.Queue(3);
                OfficialForegroundPreloadCheck.Queue foreground =
                        new OfficialForegroundPreloadCheck.Queue(1);
                Object idle = OfficialForegroundPreloadCheck.media(
                        new OfficialForegroundPreloadCheck.Handle());
                Object consumed = OfficialForegroundPreloadCheck.media(
                        new OfficialForegroundPreloadCheck.Handle());
                background.cache.add("idle", idle);
                background.ready(idle);
                background.cache.add("consumed", consumed);
                background.ready(consumed);
                ArrayList<Object> delivered = new ArrayList<>();
                background.cache.take("consumed", delivered::add);
                Object opening = OfficialForegroundPreloadCheck.media(
                        new OfficialForegroundPreloadCheck.Handle());
                foreground.cache.add("opening", opening);
                foreground.cache.takeReady("opening", delivered::add);
                OfficialRuntime runtime = OfficialForegroundPreloadCheck.runtime(
                        background, foreground, new ArrayList<>());
                OfficialPlaybackService service = new OfficialPlaybackService();
                set(service, "worker", worker);
                set(service, "runtime", runtime);
                service.onTrimMemory(level);
                CountDownLatch drained = new CountDownLatch(1);
                worker.post(drained::countDown);
                if (!drained.await(5, TimeUnit.SECONDS))
                    throw new AssertionError("Trim worker stalled");
                boolean pressure = level != ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN
                        && level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW;
                if (background.moved.containsKey(idle) != pressure)
                    throw new AssertionError("Unexpected idle removal for level " + level);
                if (!pressure && !background.cache.ready("idle"))
                    throw new AssertionError("Hidden UI lost ready entry");
                if (pressure)
                    background.ack(idle, "REMOVED");
                background.ack(consumed, "DETACHED");
                foreground.ready(opening);
                foreground.ack(opening, "DETACHED");
                if (!delivered.equals(java.util.List.of(consumed, opening)))
                    throw new AssertionError("Trim interrupted foreground handoff");
                if (background.failures != 0 || foreground.failures != 0)
                    throw new AssertionError("Native ownership failure");
                System.out.println("PASS trim level " + level + ", foreground preserved");
            }
        } finally {
            thread.quitSafely();
            thread.join(5000);
        }
    }
}
