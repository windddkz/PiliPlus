package com.example.piliplus.official;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;

/** Run against the compiled production runtime with Android's API jar on the classpath. */
public final class OfficialRateTest {
    public static final class Player {
        float rate;
        boolean fail;
        public void setSpeed(float value) {
            if (fail)
                throw new IllegalStateException("native failure");
            rate = value;
        }
    }
    public static void main(String[] args) throws Exception {
        OfficialRuntime runtime = new OfficialRuntime(null, null, null);
        Field requested = OfficialRuntime.class.getDeclaredField("speed");
        requested.setAccessible(true);
        runtime.speed(2f);
        assert requested.getFloat(runtime) == 2f;
        Field nativePlayer = OfficialRuntime.class.getDeclaredField("player");
        nativePlayer.setAccessible(true);
        Player player = new Player();
        nativePlayer.set(runtime, player);
        for (float rate : new float[] {.25f, 1f, 1.5f, 2f, 1.99f, 3f, 4f, 1f}) {
            runtime.speed(rate);
            assert requested.getFloat(runtime) == rate;
            assert player.rate == (rate == 2f ? 1.99f : rate);
        }
        for (float rate : new float[] {Float.NaN, Float.POSITIVE_INFINITY, 0f, .24f, 4.01f}) {
            try {
                runtime.speed(rate);
                throw new AssertionError("Invalid rate accepted");
            } catch (IllegalArgumentException expected) {
            }
            assert requested.getFloat(runtime) == 1f && player.rate == 1f;
        }
        player.fail = true;
        try {
            runtime.speed(2f);
            throw new AssertionError("Native failure swallowed");
        } catch (InvocationTargetException expected) {
            assert expected.getCause() instanceof IllegalStateException;
        }
        assert requested.getFloat(runtime) == 1f;
        System.out.println("Rate mapping, boundaries and failure state passed");
    }
}
