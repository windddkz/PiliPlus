package com.example.piliplus.official;

import java.io.File;
import java.nio.file.*;
import java.nio.*;
import java.util.Arrays;

public final class OfficialRuntimeImageCheck {
    public static void main(String[] args) throws Exception {
        byte[] original=Files.readAllBytes(Path.of(args[0]));
        Path directory=Files.createTempDirectory("owned-player-image-");
        try {
            Path source=directory.resolve("libijk.so");Files.write(source,original);
            File output=OfficialRuntime.playerImage(directory.toFile());
            Path image=output.toPath().resolve("libijk.so");
            byte[] expected=original.clone();ByteBuffer words=ByteBuffer.wrap(expected).order(ByteOrder.LITTLE_ENDIAN);
            words.putInt(0x14875c,0xd503201f);words.putInt(0x1a91d4,0x9b0a7d29);
            words.putInt(0x1a91d8,0x9ac80928);words.putInt(0x18b258,0xd503201f);
            assert Arrays.equals(Files.readAllBytes(source),original):"Original changed";
            assert Arrays.equals(Files.readAllBytes(image),expected):"Different from previous in-memory fixes";
            assert Files.getPosixFilePermissions(image).stream().noneMatch(p->p.name().endsWith("WRITE"));
            assert OfficialRuntime.playerImage(directory.toFile()).equals(output):"Cache not reused";
            image.toFile().setWritable(true);Files.write(image,new byte[]{1,2,3});
            try{OfficialRuntime.playerImage(directory.toFile());throw new AssertionError("Accepted corrupt derived image");}
            catch(SecurityException expectedFailure){}
            Files.write(source,new byte[]{1,2,3});
            try{OfficialRuntime.playerImage(directory.toFile());throw new AssertionError("Accepted unlocked original");}
            catch(SecurityException expectedFailure){}
            System.out.println("Exact four-word image / unchanged original / read-only cache / corruption rejection passed");
        } finally {
            try(var paths=Files.walk(directory)){for(Path path:paths.sorted(java.util.Comparator.reverseOrder()).toList())Files.delete(path);}
        }
    }
}
