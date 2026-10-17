package com.example.piliplus.official;

import java.net.URI;
import java.util.*;

final class OfficialStream {
    final String source;
    final int id, bandwidth;
    final List<String> backups;

    OfficialStream(String actual, Object source, Object id, Object bandwidth, Object backups) {
        if(!Objects.equals(actual,source) || !(source instanceof String))
            throw new IllegalArgumentException("Stream metadata belongs to another source");
        this.source=(String)source;validateUrl(this.source);
        this.id=integer(id,1);this.bandwidth=integer(bandwidth,0);
        if(!(backups instanceof List<?>) || ((List<?>)backups).size()>32)
            throw new IllegalArgumentException("Invalid stream alternatives");
        LinkedHashSet<String> unique=new LinkedHashSet<>();
        for(Object value:(List<?>)backups){
            if(!(value instanceof String))throw new IllegalArgumentException("Invalid stream URL");
            String url=(String)value;validateUrl(url);
            if(!url.equals(this.source))unique.add(url);
        }
        this.backups=Collections.unmodifiableList(new ArrayList<>(unique));
    }

    private static int integer(Object value,int min) {
        if(!(value instanceof Integer) && !(value instanceof Long))
            throw new IllegalArgumentException("Invalid stream integer");
        long n=((Number)value).longValue();
        if(n<min || n>Integer.MAX_VALUE)throw new IllegalArgumentException("Stream integer out of range");
        return (int)n;
    }

    List<String> alternativesFor(String selected) {
        LinkedHashSet<String> urls=new LinkedHashSet<>(backups);
        urls.add(source);urls.remove(selected);
        return new ArrayList<>(urls);
    }

    private static void validateUrl(String value) {
        if(value.length()>16384)throw new IllegalArgumentException("Stream URL too long");
        URI uri=URI.create(value);
        if(!("https".equals(uri.getScheme()) || "http".equals(uri.getScheme())) ||
           uri.getHost()==null || uri.getRawUserInfo()!=null || uri.getRawFragment()!=null)
            throw new IllegalArgumentException("Invalid stream URL");
    }
}
