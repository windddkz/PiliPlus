package com.example.piliplus.official;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/** Reject unindexed MP4 before the official DASH demuxer can seek into an empty fragment table. */
final class FragmentIndex {
    static final class Info {
        String codec;
        boolean hdr;
    }
    static String inspect(byte[] prefix, long fileSize) { return inspectInfo(prefix, fileSize).codec; }
    static Info inspectInfo(byte[] prefix, long fileSize) {
        Info info = new Info();
        if (prefix.length > fileSize || fileSize <= 0) throw new IllegalArgumentException("Invalid file size");
        ByteBuffer b = ByteBuffer.wrap(prefix).order(ByteOrder.BIG_ENDIAN);
        boolean index = false, movie = false;
        String codec = null;
        int at = 0;
        while (at + 8 <= prefix.length) {
            long size = Integer.toUnsignedLong(b.getInt(at)); int header = 8;
            String type = type(prefix, at + 4);
            if (size == 1) {
                if (at + 16 > prefix.length) break;
                size = b.getLong(at + 8); header = 16;
            } else if (size == 0) size = fileSize - at;
            if (size < header || size > fileSize - at) throw new IllegalArgumentException("Invalid MP4 box");
            if (type.equals("moof")) {
                if (!index || !movie || codec == null) throw new IllegalArgumentException("Missing DASH index or codec");
                info.codec = codec; return info;
            }
            if (size > prefix.length - at) break;
            int end = at + (int)size, payload = at + header;
            if (type.equals("moov")) { movie = true; codec = codec(prefix, b, payload, end, 0, info); }
            if (type.equals("sidx")) {
                if (end - payload < 24) throw new IllegalArgumentException("Short sidx");
                int version = prefix[payload] & 255;
                if (version > 1) throw new IllegalArgumentException("Unknown sidx version");
                int countAt = payload + (version == 0 ? 22 : 30);
                if (countAt + 2 > end) throw new IllegalArgumentException("Short sidx");
                int count = Short.toUnsignedInt(b.getShort(countAt));
                if (count == 0 || countAt + 2L + count * 12L != end || b.getInt(payload + 8) == 0)
                    throw new IllegalArgumentException("Empty or malformed fragment index");
                // Hierarchical indexes have not been verified with this native seek implementation.
                for (int i = 0; i < count; i++)
                    if (b.getInt(countAt + 2 + i * 12) <= 0) throw new IllegalArgumentException("Unsupported fragment reference");
                index = true;
            }
            at = end;
        }
        throw new IllegalArgumentException("No verified fragment index within probe limit");
    }

    private static String type(byte[] bytes, int at) { return new String(bytes, at, 4, StandardCharsets.US_ASCII); }
    private static String codec(byte[] bytes, ByteBuffer b, int start, int end, int depth, Info info) {
        if (depth > 6) throw new IllegalArgumentException("Excessive MP4 nesting");
        String found = null;
        for (int at = start; at + 8 <= end;) {
            long size = Integer.toUnsignedLong(b.getInt(at));
            if (size < 8 || size > end - at) throw new IllegalArgumentException("Invalid movie box");
            String type = type(bytes, at + 4); int next = at + (int)size;
            if (type.equals("stsd")) {
                if (size < 24 || b.getInt(at + 12) != 1) throw new IllegalArgumentException("Unsupported sample descriptions");
                long entrySize = Integer.toUnsignedLong(b.getInt(at + 16));
                if (entrySize < 8 || entrySize > size - 16) throw new IllegalArgumentException("Invalid sample entry");
                String entry = type(bytes, at + 20);
                switch (entry) {
                    case "avc1": case "avc3": found = "H264"; break;
                    case "hvc1": case "hev1": case "dvh1": case "dvhe": found = "HEVC"; break;
                    case "av01": found = "AV1"; break;
                    case "mp4a": found = "AAC"; break;
                    case "fLaC": found = "FLAC"; break;
                    case "ec-3": found = "EAC3"; break;
                    case "ac-3": found = "AC3"; break;
                    default: throw new IllegalArgumentException("Unsupported or encrypted sample entry");
                }
                if (found.equals("H264") || found.equals("HEVC") || found.equals("AV1")) {
                    // VisualSampleEntry has 78 payload bytes before its child boxes.
                    int entryStart = at + 16, children = entryStart + 86, entryEnd = entryStart + (int)entrySize;
                    if (children > entryEnd) throw new IllegalArgumentException("Short visual sample entry");
                    for (int child = children; child < entryEnd;) {
                        if (entryEnd - child < 8) throw new IllegalArgumentException("Short visual child");
                        long childSize = Integer.toUnsignedLong(b.getInt(child));
                        if (childSize < 8 || childSize > entryEnd - child) throw new IllegalArgumentException("Invalid visual child");
                        String childType = type(bytes, child + 4);
                        if (childType.equals("colr")) {
                            if (childSize < 12) throw new IllegalArgumentException("Short colour box");
                            String colourType = type(bytes, child + 8);
                            if (colourType.equals("nclx") || colourType.equals("nclc")) {
                                if (childSize < (colourType.equals("nclx") ? 19 : 18)) throw new IllegalArgumentException("Short colour parameters");
                                int transfer = Short.toUnsignedInt(b.getShort(child + 14));
                                info.hdr |= transfer == 16 || transfer == 18;
                            }
                        } else if (childType.equals("dvcC") || childType.equals("dvvC")) {
                            if (childSize < 12) throw new IllegalArgumentException("Short Dolby configuration");
                            info.hdr = true;
                        }
                        child += (int)childSize;
                    }
                }
            } else if (type.equals("trak") || type.equals("mdia") || type.equals("minf") || type.equals("stbl")) {
                String nested = codec(bytes, b, at + 8, next, depth + 1, info);
                if (nested != null) {
                    if (found != null && !found.equals(nested)) throw new IllegalArgumentException("DASH stream has multiple track types");
                    found = nested;
                }
            }
            at = next;
        }
        return found;
    }
}
