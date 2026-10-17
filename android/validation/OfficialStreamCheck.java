package com.example.piliplus.official;

import java.util.*;

public final class OfficialStreamCheck {
    static final String URL = "https://cdn.example/video?sig=one";
    static void rejects(Object source, Object id, Object bandwidth, Object backups) {
        try {
            new OfficialStream(URL, source, id, bandwidth, backups);
            throw new AssertionError("Accepted invalid metadata");
        } catch (IllegalArgumentException expected) {
        }
    }
    public static void main(String[] args) {
        String backup = "https://backup.example/video?sig=two";
        List<String> input = new ArrayList<>(Arrays.asList(URL, backup, backup));
        OfficialStream track = new OfficialStream(URL, URL, 126L, 8000000L, input);
        input.clear();
        assert track.id == 126 && track.bandwidth == 8000000;
        assert track.backups.equals(Collections.singletonList(backup));
        assert track.alternativesFor(URL).equals(Collections.singletonList(backup));
        assert track.alternativesFor(backup).equals(Collections.singletonList(URL));
        assert new OfficialStream(URL, URL, 30251, 0, Collections.emptyList()).bandwidth == 0;
        rejects(backup, 126, 8000000, Collections.emptyList());
        rejects(URL, 0, 0, Collections.emptyList());
        rejects(URL, 126, 2147483648L, Collections.emptyList());
        rejects(URL, 126, -1, Collections.emptyList());
        rejects(URL, 126, Double.NaN, Collections.emptyList());
        rejects(URL, 126, 1.5, Collections.emptyList());
        rejects(URL, 126, 0, Arrays.asList("file:///private/media"));
        rejects(URL, 126, 0, Arrays.asList("https://user:secret@example/video"));
        rejects(URL, 126, 0, Arrays.asList("https://example/video\r\nHeader: bad"));
        rejects(URL, 126, 0, Arrays.asList(3));
        rejects(URL, 126, 0, Collections.nCopies(33, backup));
        for (Object[] sample : new Object[][] {{"avc1.640033", 0}, {"hev1.2.4.L153.B0", 1},
                     {"hvc1.1.6.L120", 1}, {"dvh1.08.07", 1}, {"dvhe.05.07", 1},
                     {"av01.0.08M.08", 2}, {null, -1}, {"unknown", -1}}) {
            OfficialStream video =
                    new OfficialStream(URL, URL, 80, 0, Collections.emptyList(), sample[0]);
            assert video.videoCodec() == (Integer) sample[1];
        }
        for (Object malformed : new Object[] {
                     7, Collections.emptyList(), String.join("", Collections.nCopies(257, "x"))}) {
            try {
                new OfficialStream(URL, URL, 80, 0, Collections.emptyList(), malformed);
                throw new AssertionError("Accepted malformed codec");
            } catch (IllegalArgumentException expected) {
            }
        }
        System.out.println(
                "Stream identity, integer bounds, URL validation, alternatives and snapshot checks passed");
    }
}
