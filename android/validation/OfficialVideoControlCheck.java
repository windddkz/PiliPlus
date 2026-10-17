package com.example.piliplus.official;
import android.os.Bundle;
import java.lang.reflect.*;
import java.util.*;

/** Exercises the production command state with a fake native endpoint, not audio/video output. */
public final class OfficialVideoControlCheck {
    private static int checks;
    public static final class Player {
        boolean automatic;
        int target, result;
        long request;
        int calls;
        public void setDashAutoEnable(boolean value) {
            automatic = value;
        }
        public int switchStreamSmooth(long id, int media, int quality) {
            request = id;
            target = quality;
            calls++;
            return result;
        }
    }
    private static void field(OfficialRuntime r, String name, Object value) throws Exception {
        Field f = OfficialRuntime.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(r, value);
    }
    private static void require(boolean value) {
        if (!value)
            throw new AssertionError("case " + checks);
        checks++;
    }
    private static Bundle request(long id, int quality, boolean auto) {
        Bundle b = new Bundle();
        b.putLong("load", 1);
        b.putLong("requestId", id);
        b.putInt("quality", quality);
        b.putBoolean("automatic", auto);
        return b;
    }
    private static void reject(OfficialRuntime r, Bundle b) throws Exception {
        try {
            r.videoControl(b);
            throw new AssertionError("Invalid control accepted");
        } catch (IllegalArgumentException | IllegalStateException expected) {
            checks++;
        }
    }
    private static Bundle terminal(List<Bundle> events) {
        return events.stream()
                .filter(b -> "video_control".equals(b.getString("event")))
                .reduce((a, b) -> b)
                .orElse(null);
    }
    public static void main(String[] args) throws Exception {
        List<Bundle> events = new ArrayList<>();
        OfficialRuntime r = new OfficialRuntime(null, null, events::add);
        Player player = new Player();
        OfficialStream high = new OfficialStream("https://example.invalid/120",
                "https://example.invalid/120", 120, 4000, List.of(), "avc1.640033");
        OfficialStream low = new OfficialStream("https://example.invalid/32",
                "https://example.invalid/32", 32, 1000, List.of(), "avc1.640033");
        field(r, "load", 1L);
        field(r, "prepared", true);
        field(r, "player", player);
        field(r, "videoPolicy", new OfficialVideoTracks(high, List.of(high, low), true));
        field(r, "automaticVideoActive", true);
        field(r, "actualVideoQuality", 120);
        Bundle stale = request(1, 32, false);
        stale.putLong("load", 2);
        reject(r, stale);
        reject(r, request(1, 999, false));
        reject(r, request(1, 32, true));
        reject(r, request(0, 32, false));
        r.videoControl(request(1, 32, false));
        require(player.calls == 1 && !player.automatic && terminal(events) == null);
        reject(r, request(2, 120, false));
        r.videoControlState(2, 1, 0, 32, 0);
        r.videoControlState(1, 1, 1, 32, 0);
        r.videoControlState(1, 0, 0, 32, 0);
        require(terminal(events) == null);
        r.videoControlState(1, 1, 0, 32, 0);
        require(terminal(events).getBoolean("success") && !player.automatic);
        reject(r, request(1, 120, false));
        r.videoControl(request(2, 0, true));
        require(player.automatic && terminal(events).getLong("requestId") == 2);
        r.videoControl(request(3, 120, false));
        require(player.calls == 2); // same current qn must still reach native
        r.videoControlState(3, 2, 0, 120, -1);
        require(!terminal(events).getBoolean("success") && player.automatic);
        r.videoControl(request(4, 32, false));
        r.videoControlState(4, 1, 0, 120, 0);
        require(!terminal(events).getBoolean("success") && player.automatic);
        r.videoControl(request(5, 32, false));
        r.videoControlState(5, 1, 0, 32, -1);
        require(!terminal(events).getBoolean("success") && player.automatic);
        player.result = -1;
        r.videoControl(request(6, 32, false));
        require(!terminal(events).getBoolean("success") && player.automatic);
        int size = events.size();
        r.videoControlState(5, 1, 0, 32, 0);
        require(events.size() == size);
        System.out.println("PASS " + checks
                + " production quality command/terminal-state checks; fake native endpoint");
    }
}
