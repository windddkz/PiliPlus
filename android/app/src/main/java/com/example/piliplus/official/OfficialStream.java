package com.example.piliplus.official;

import java.net.URI;
import java.util.*;

final class OfficialStream {
    final String source, codecs;
    final int id, bandwidth;
    final List<String> backups;

    OfficialStream(String actual, Object source, Object id, Object bandwidth, Object backups) {
        this(actual, source, id, bandwidth, backups, null);
    }
    OfficialStream(String actual, Object source, Object id, Object bandwidth, Object backups,
            Object codecs) {
        if (codecs != null && (!(codecs instanceof String) || ((String) codecs).length() > 256))
            throw new IllegalArgumentException("Invalid codec metadata");
        this.codecs = (String) codecs;
        if (!Objects.equals(actual, source) || !(source instanceof String))
            throw new IllegalArgumentException("Stream metadata belongs to another source");
        this.source = (String) source;
        validateUrl(this.source);
        this.id = integer(id, 1);
        this.bandwidth = integer(bandwidth, 0);
        if (!(backups instanceof List<?>) || ((List<?>) backups).size() > 32)
            throw new IllegalArgumentException("Invalid stream alternatives");
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        for (Object value : (List<?>) backups) {
            if (!(value instanceof String))
                throw new IllegalArgumentException("Invalid stream URL");
            String url = (String) value;
            validateUrl(url);
            if (!url.equals(this.source))
                unique.add(url);
        }
        this.backups = Collections.unmodifiableList(new ArrayList<>(unique));
    }

    int videoCodec() {
        if (codecs == null)
            return -1;
        if (codecs.startsWith("avc1."))
            return 0;
        if (codecs.startsWith("hev1.") || codecs.startsWith("hvc1.") || codecs.startsWith("dvh1.")
                || codecs.startsWith("dvhe."))
            return 1;
        if (codecs.startsWith("av01."))
            return 2;
        return -1;
    }

    private static int integer(Object value, int min) {
        if (!(value instanceof Integer) && !(value instanceof Long))
            throw new IllegalArgumentException("Invalid stream integer");
        long n = ((Number) value).longValue();
        if (n < min || n > Integer.MAX_VALUE)
            throw new IllegalArgumentException("Stream integer out of range");
        return (int) n;
    }

    List<String> alternativesFor(String selected) {
        LinkedHashSet<String> urls = new LinkedHashSet<>(backups);
        urls.add(source);
        urls.remove(selected);
        return new ArrayList<>(urls);
    }

    private static void validateUrl(String value) {
        if (value.length() > 16384)
            throw new IllegalArgumentException("Stream URL too long");
        URI uri = URI.create(value);
        if (!("https".equals(uri.getScheme()) || "http".equals(uri.getScheme()))
                || uri.getHost() == null || uri.getRawUserInfo() != null
                || uri.getRawFragment() != null)
            throw new IllegalArgumentException("Invalid stream URL");
    }
}
