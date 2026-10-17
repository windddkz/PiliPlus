package com.example.piliplus.official;

import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;

public final class OfficialRuntimeCacheCheck {
    public static void main(String[] args) throws Exception {
        Path base=Files.createTempDirectory("piliplus-runtime-cache-check-");
        Path root=Files.createDirectory(base.resolve("official-runtime"));
        String active="a".repeat(64),old="b".repeat(64),linked="c".repeat(64);
        Path current=Files.createDirectory(root.resolve(active));
        Files.writeString(current.resolve("official.apk"),"current");
        Path stale=Files.createDirectory(root.resolve(old));
        Files.writeString(stale.resolve("official.apk"),"old");
        Files.createDirectories(stale.resolve("oat/arm64"));
        Files.writeString(stale.resolve("oat/arm64/official.vdex"),"compiled");
        Path outside=Files.createDirectory(base.resolve("user-data"));
        Files.writeString(outside.resolve("account.json"),"preserve");
        Files.createSymbolicLink(stale.resolve("outside-link"),outside);
        Files.createSymbolicLink(root.resolve(linked),outside);
        Path unknown=Files.createDirectory(root.resolve("notes"));
        Files.writeString(unknown.resolve("keep.txt"),"keep");
        OfficialRuntimeCache.Result r=OfficialRuntimeCache.prune(root.toFile(),active);
        assert r.removedDirectories==1 && r.failedDirectories==0 && r.removedBytes==11;
        assert !Files.exists(stale) && Files.readString(current.resolve("official.apk")).equals("current");
        assert Files.readString(outside.resolve("account.json")).equals("preserve");
        assert Files.isSymbolicLink(root.resolve(linked)) && Files.exists(unknown.resolve("keep.txt"));
        assert OfficialRuntimeCache.prune(root.toFile(),active).removedDirectories==0;
        Path denied=Files.createDirectory(root.resolve("d".repeat(64)));
        Files.writeString(denied.resolve("official.apk"),"retry");
        Files.setPosixFilePermissions(denied,PosixFilePermissions.fromString("---------"));
        try {
            r=OfficialRuntimeCache.prune(root.toFile(),active);
            assert r.failedDirectories==1 && r.removedDirectories==0;
            assert Files.exists(current.resolve("official.apk"));
        } finally {Files.setPosixFilePermissions(denied,PosixFilePermissions.fromString("rwx------"));}
        assert Files.readString(denied.resolve("official.apk")).equals("retry");
        assert OfficialRuntimeCache.prune(root.toFile(),active).removedDirectories==1;
        try {OfficialRuntimeCache.prune(root.toFile(),"../bad");throw new AssertionError("Invalid identity accepted");}
        catch (IllegalArgumentException expected) {}
        Path rootLink=Files.createSymbolicLink(base.resolve("root-link"),root);
        assert OfficialRuntimeCache.prune(rootLink.toFile(),old).removedDirectories==0;
        assert Files.exists(current.resolve("official.apk"));
        Files.delete(rootLink);Files.delete(root.resolve(linked));Files.delete(unknown.resolve("keep.txt"));Files.delete(unknown);
        Files.delete(current.resolve("official.apk"));Files.delete(current);Files.delete(root);
        Files.delete(outside.resolve("account.json"));Files.delete(outside);Files.delete(base);
        System.out.println("Old runtime/compiled cache removed; active, unrelated data and link targets preserved; repeat and invalid identity checks passed");
    }
}
