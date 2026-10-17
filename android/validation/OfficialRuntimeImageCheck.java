package com.example.piliplus.official;

import java.io.File;
import java.nio.file.*;
import java.nio.*;
import java.util.Arrays;

public final class OfficialRuntimeImageCheck {
    public static void main(String[] args) throws Exception {
        checkDemux(Path.of(args[1]));
        byte[] original=Files.readAllBytes(Path.of(args[0]));
        Path directory=Files.createTempDirectory("owned-player-image-");
        try {
            Path source=directory.resolve("libijk.so");Files.write(source,original);
            File output=OfficialRuntime.playerImage(directory.toFile());
            Path image=output.toPath().resolve("libijk.so");
            byte[] expected=original.clone();ByteBuffer words=ByteBuffer.wrap(expected).order(ByteOrder.LITTLE_ENDIAN);
            words.putInt(0x14898c,0xd503201f);words.putInt(0x1a93c4,0x9b0a7d29);
            words.putInt(0x1a93c8,0x9ac80928);words.putInt(0x18b488,0xd503201f);
            words.putInt(0x156c38,0x1e2a1000);
            words.putInt(0x129390,0x52800149);words.putInt(0x129398,0x7100291f);
            words.putInt(0x139e98,0xd503201f);words.putInt(0x139ea4,0xb90013f6);
            assert Arrays.equals(Files.readAllBytes(source),original):"Original changed";
            assert Arrays.equals(Files.readAllBytes(image),expected):"Unexpected derived player instructions";
            assert Files.getPosixFilePermissions(image).stream().noneMatch(p->p.name().endsWith("WRITE"));
            assert OfficialRuntime.playerImage(directory.toFile()).equals(output):"Cache not reused";
            image.toFile().setWritable(true);Files.write(image,new byte[]{1,2,3});
            try{OfficialRuntime.playerImage(directory.toFile());throw new AssertionError("Accepted corrupt derived image");}
            catch(SecurityException expectedFailure){}
            Files.write(source,new byte[]{1,2,3});
            try{OfficialRuntime.playerImage(directory.toFile());throw new AssertionError("Accepted unlocked original");}
            catch(SecurityException expectedFailure){}
            System.out.println("Exact nine-word image / unchanged original / read-only cache / corruption rejection passed");
        } finally {
            try(var paths=Files.walk(directory)){for(Path path:paths.sorted(java.util.Comparator.reverseOrder()).toList())Files.delete(path);}
        }
    }
    private static void checkDemux(Path input) throws Exception {
        byte[] original=Files.readAllBytes(input);
        Path directory=Files.createTempDirectory("owned-demux-image-");
        try {
            Path source=directory.resolve("libijkffext.so");Files.write(source,original);
            File output=OfficialRuntime.demuxImage(directory.toFile());
            Path image=output.toPath().resolve("libijkffext.so");
            byte[] expected=original.clone();ByteBuffer words=ByteBuffer.wrap(expected).order(ByteOrder.LITTLE_ENDIAN);
            words.putInt(0x1b5288,0xb94012b4);words.putInt(0x1b528c,0x34000314);
            words.putInt(0x1b52f8,0xb9402ab3);words.putInt(0x1b52fc,0xf94012a0);
            words.putInt(0x1b530c,0xd503201f);
            assert Arrays.equals(Files.readAllBytes(source),original):"Original demux changed";
            assert Arrays.equals(Files.readAllBytes(image),expected):"Unexpected derived demux instructions";
            String mapped="1000-2000 r-xp 00000000 00:00 1 "+image+"\n";
            OfficialRuntime.verifyMappedImage(image.toFile(),new java.io.StringReader(mapped+mapped));
            for(String invalid:new String[]{"",mapped.replace(image.toString(),source.toString()),mapped.replace("\n"," (deleted)\n"),mapped+mapped.replace(image.toString(),source.toString())}){
                try{OfficialRuntime.verifyMappedImage(image.toFile(),new java.io.StringReader(invalid));throw new AssertionError("Accepted missing or wrong mapped demux");}
                catch(SecurityException expectedFailure){}
            }
            assert Files.getPosixFilePermissions(image).stream().noneMatch(p->p.name().endsWith("WRITE"));
            assert OfficialRuntime.demuxImage(directory.toFile()).equals(output):"Demux cache not reused";
            image.toFile().setWritable(true);Files.write(image,new byte[]{1,2,3});
            try{OfficialRuntime.demuxImage(directory.toFile());throw new AssertionError("Accepted corrupt derived demux");}
            catch(SecurityException expectedFailure){}
            Files.write(source,new byte[]{1,2,3});
            try{OfficialRuntime.demuxImage(directory.toFile());throw new AssertionError("Accepted unlocked demux original");}
            catch(SecurityException expectedFailure){}
            System.out.println("Exact five-word demux image / unchanged original / read-only cache / corruption rejection passed");
        } finally {
            try(var paths=Files.walk(directory)){for(Path path:paths.sorted(java.util.Comparator.reverseOrder()).toList())Files.delete(path);}
        }
    }

}
