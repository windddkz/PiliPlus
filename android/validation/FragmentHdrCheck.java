package com.example.piliplus.official;

import java.nio.ByteBuffer;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;

public final class FragmentHdrCheck {
    private static int find(byte[] b,String value) {
        byte[] tag=value.getBytes(StandardCharsets.US_ASCII);
        for(int i=0;i<=b.length-tag.length;i++) {
            boolean equal=true;for(int j=0;j<tag.length;j++)equal&=b[i+j]==tag[j];
            if(equal)return i;
        }
        throw new AssertionError("Missing fixture box "+value);
    }
    private static void rejected(byte[] b) {
        try {FragmentIndex.inspectInfo(b,b.length);}
        catch(IllegalArgumentException expected){return;}
        throw new AssertionError("Malformed colour metadata accepted");
    }
    public static void main(String[] args) throws Exception {
        byte[] pq=Files.readAllBytes(Paths.get(args[0])),dv=Files.readAllBytes(Paths.get(args[1])),dv5=Files.readAllBytes(Paths.get(args[2]));
        assert FragmentIndex.inspectInfo(dv5,dv5.length).codec.equals("HEVC");
        assert FragmentIndex.inspectInfo(dv5,dv5.length).hdr;
        byte[] dvhe=dv5.clone();int dvTag=find(dvhe,"dvh1");
        System.arraycopy("dvhe".getBytes(StandardCharsets.US_ASCII),0,dvhe,dvTag,4);
        assert FragmentIndex.inspectInfo(dvhe,dvhe.length).codec.equals("HEVC");
        assert FragmentIndex.inspectInfo(dvhe,dvhe.length).hdr;
        assert FragmentIndex.inspectInfo(pq,pq.length).codec.equals("HEVC");
        assert FragmentIndex.inspectInfo(pq,pq.length).hdr;
        assert FragmentIndex.inspectInfo(dv,dv.length).hdr;
        int colr=find(pq,"colr");
        for(int transfer:new int[]{1,2,6,14,15,16,18}) {
            byte[] b=pq.clone();ByteBuffer.wrap(b).putShort(colr+10,(short)transfer);
            assert FragmentIndex.inspectInfo(b,b.length).hdr==(transfer==16||transfer==18);
        }
        byte[] b=pq.clone();ByteBuffer.wrap(b).putInt(colr-4,Integer.MAX_VALUE);rejected(b);
        b=pq.clone();ByteBuffer.wrap(b).putInt(colr-4,10);rejected(b);
        b=pq.clone();ByteBuffer.wrap(b).putInt(colr-4,18);rejected(b);
        b=pq.clone();System.arraycopy("prof".getBytes(StandardCharsets.US_ASCII),0,b,colr+4,4);
        assert !FragmentIndex.inspectInfo(b,b.length).hdr;
        b=dv.clone();int d=find(b,"dvvC");System.arraycopy("free".getBytes(StandardCharsets.US_ASCII),0,b,d,4);
        assert !FragmentIndex.inspectInfo(b,b.length).hdr;
        System.out.println("Actual PQ/Dolby profile8/profile5 headers, HLG/SDR decisions and malformed child rejection passed");
    }
}
