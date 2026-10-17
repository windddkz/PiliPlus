package com.example.piliplus.official;

import java.nio.ByteBuffer;
import java.nio.file.*;
import java.util.Arrays;

public final class FragmentIndexCheck {
    private static void rejected(byte[] bytes, long size) {
        try {
            FragmentIndex.inspect(bytes, size);
        } catch (IllegalArgumentException expected) {
            return;
        }
        throw new AssertionError("Unsafe fragment accepted");
    }
    public static void main(String[] args) throws Exception {
        Path root = Paths.get(args[0]);
        byte[] video = Files.readAllBytes(root.resolve("fragmented-dash-video.mp4"));
        byte[] audio = Files.readAllBytes(root.resolve("fragmented-dash-audio.m4a"));
        assert FragmentIndex.inspect(video, video.length).equals("H264");
        assert FragmentIndex.inspect(audio, audio.length).equals("AAC");
        for (String name : new String[] {"dash-video.mp4", "dash-audio.m4a"}) {
            byte[] plain = Files.readAllBytes(root.resolve(name));
            rejected(plain, plain.length);
        }
        rejected(Arrays.copyOf(video, 20), video.length);
        rejected(video, video.length - 1);
        byte[] broken = video.clone();
        ByteBuffer.wrap(broken).putInt(0, -1);
        rejected(broken, broken.length);
        int at = 0;
        while (!new String(video, at + 4, 4, java.nio.charset.StandardCharsets.US_ASCII)
                        .equals("sidx"))
            at += ByteBuffer.wrap(video).getInt(at);
        broken = video.clone();
        broken[at + 8] = 2;
        rejected(broken, broken.length);
        broken = video.clone();
        int countAt = at + 8 + (video[at + 8] == 0 ? 22 : 30);
        broken[countAt] = broken[countAt + 1] = 0;
        rejected(broken, broken.length);
        broken = video.clone();
        broken[countAt + 2] |= (byte) 0x80;
        rejected(broken, broken.length);
        assert DashSource
                .probe(root.resolve("fragmented-dash-video.mp4").toAbsolutePath().toString(),
                        "test", "")
                .codec.equals("H264");
        System.out.println(
                "Actual H264/AAC fragment index and malformed/ordinary MP4 rejection passed");
    }
}
