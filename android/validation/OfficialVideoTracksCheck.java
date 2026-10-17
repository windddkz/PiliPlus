package com.example.piliplus.official;
import java.util.*;
public final class OfficialVideoTracksCheck {
    private static int cases;
    private static OfficialStream track(int id,String codec,int rate,String suffix) {
        String url="https://cdn.example/video/"+id+"?version="+suffix;
        return new OfficialStream(url,url,id,rate,Collections.emptyList(),codec);
    }
    private static void rejects(Runnable action) {
        try{action.run();throw new AssertionError("Invalid ladder accepted");}
        catch(IllegalArgumentException expected){cases++;}
    }
    public static void main(String[] args) {
        OfficialStream low=track(32,"avc1.640033",1000,"a"),high=track(120,"avc1.640033",4000,"a");
        List<OfficialStream> input=new ArrayList<>(Arrays.asList(low,high));
        OfficialVideoTracks fixed=new OfficialVideoTracks(high,input,false),auto=new OfficialVideoTracks(high,input,true);
        input.clear();assert fixed.min()==32 && fixed.max()==120 && fixed.origin==high && !fixed.automatic && auto.automatic;cases++;
        assert fixed.get(32)==low && fixed.tracks.get(0)==high;cases++;
        try{fixed.tracks.clear();throw new AssertionError("Mutable ladder");}catch(UnsupportedOperationException expected){cases++;}
        rejects(()->new OfficialVideoTracks(null,Arrays.asList(low,high),false));
        rejects(()->new OfficialVideoTracks(high,null,false));
        rejects(()->new OfficialVideoTracks(high,Collections.emptyList(),false));
        rejects(()->new OfficialVideoTracks(high,Collections.nCopies(21,high),false));
        rejects(()->new OfficialVideoTracks(high,Arrays.asList(high,high),false));
        rejects(()->new OfficialVideoTracks(high,Collections.singletonList(low),false));
        rejects(()->new OfficialVideoTracks(high,Arrays.asList(high,null),false));
        rejects(()->new OfficialVideoTracks(high,Arrays.asList(high,track(80,"hev1.1.6.L120",2000,"a")),false));
        rejects(()->new OfficialVideoTracks(high,Arrays.asList(high,track(80,"unknown",2000,"a")),false));
        for(int hdr:new int[]{125,126,129})rejects(()->new OfficialVideoTracks(high,Arrays.asList(high,track(hdr,"avc1.640033",5000,"a")),false));
        for(OfficialStream mismatch:Arrays.asList(track(120,"avc1.640033",4000,"b"),track(120,"avc1.640033",3999,"a"),track(120,"avc1.640034",4000,"a")))
            rejects(()->new OfficialVideoTracks(high,Arrays.asList(low,mismatch),false));
        OfficialStream zero=track(32,"avc1.640033",0,"a");
        assert new OfficialVideoTracks(high,Arrays.asList(high,zero),false).get(32)==zero;cases++;
        rejects(()->new OfficialVideoTracks(high,Arrays.asList(high,zero),true));
        rejects(()->fixed.get(80));
        OfficialStream refreshed=track(120,"avc1.640033",4500,"b"),freshLow=track(32,"avc1.640033",1100,"b");
        fixed.validateRefresh(new OfficialVideoTracks(refreshed,Arrays.asList(freshLow,refreshed),false));cases++;
        rejects(()->fixed.validateRefresh(null));
        rejects(()->fixed.validateRefresh(new OfficialVideoTracks(refreshed,Arrays.asList(freshLow,refreshed),true)));
        rejects(()->fixed.validateRefresh(new OfficialVideoTracks(refreshed,Collections.singletonList(refreshed),false)));
        rejects(()->fixed.validateRefresh(new OfficialVideoTracks(freshLow,Arrays.asList(freshLow,refreshed),false)));
        OfficialStream wrongCodec=track(120,"avc1.640034",4500,"b");
        rejects(()->fixed.validateRefresh(new OfficialVideoTracks(wrongCodec,Arrays.asList(freshLow,wrongCodec),false)));
        OfficialStream wrongId=track(64,"avc1.640033",1100,"b");
        rejects(()->fixed.validateRefresh(new OfficialVideoTracks(refreshed,Arrays.asList(wrongId,refreshed),false)));
        OfficialStream dv=track(126,"dvh1.08.07",5000,"a");
        assert new OfficialVideoTracks(dv,Collections.singletonList(dv),false).origin==dv;cases++;
        System.out.println("PASS "+cases+" ladder bounds, selected identity, mode, compatible codec/color family, immutable snapshot and refresh cases");
    }
}
