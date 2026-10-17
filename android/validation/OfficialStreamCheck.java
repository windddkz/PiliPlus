package com.example.piliplus.official;

import java.util.*;

public final class OfficialStreamCheck {
    static final String URL="https://cdn.example/video?sig=one";
    static void rejects(Object source,Object id,Object bandwidth,Object backups) {
        try {new OfficialStream(URL,source,id,bandwidth,backups);throw new AssertionError("Accepted invalid metadata");}
        catch(IllegalArgumentException expected) {}
    }
    public static void main(String[] args) {
        String backup="https://backup.example/video?sig=two";
        List<String> input=new ArrayList<>(Arrays.asList(URL,backup,backup));
        OfficialStream track=new OfficialStream(URL,URL,126L,8000000L,input);
        input.clear();assert track.id==126 && track.bandwidth==8000000;
        assert track.backups.equals(Collections.singletonList(backup));
        assert new OfficialStream(URL,URL,30251,0,Collections.emptyList()).bandwidth==0;
        rejects(backup,126,8000000,Collections.emptyList());
        rejects(URL,0,0,Collections.emptyList());
        rejects(URL,126,2147483648L,Collections.emptyList());
        rejects(URL,126,-1,Collections.emptyList());
        rejects(URL,126,Double.NaN,Collections.emptyList());
        rejects(URL,126,1.5,Collections.emptyList());
        rejects(URL,126,0,Arrays.asList("file:///private/media"));
        rejects(URL,126,0,Arrays.asList("https://user:secret@example/video"));
        rejects(URL,126,0,Arrays.asList("https://example/video\r\nHeader: bad"));
        rejects(URL,126,0,Arrays.asList(3));
        rejects(URL,126,0,Collections.nCopies(33,backup));
        System.out.println("Stream identity, integer bounds, URL validation, alternatives and snapshot checks passed");
    }
}
