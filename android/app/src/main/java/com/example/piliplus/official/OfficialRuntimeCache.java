package com.example.piliplus.official;

import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;

/** Only generated, hash-named runtime directories live under this private root. */
@android.annotation.TargetApi(26) // The plugin rejects Android versions below 26.
final class OfficialRuntimeCache {
    static final class Result {
        long removedBytes;
        int removedDirectories, failedDirectories;
    }

    static Result prune(File root, String activeHash) {
        if (!activeHash.matches("[0-9a-f]{64}"))
            throw new IllegalArgumentException("Invalid runtime identity");
        Result result = new Result();
        Path parent = root.toPath();
        if (!Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS))
            return result;
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(parent)) {
            for (Path entry : entries) {
                String name = entry.getFileName().toString();
                if (name.equals(activeHash) || !name.matches("[0-9a-f]{64}")
                        || !Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS))
                    continue;
                try {
                    // No FOLLOW_LINKS: a nested link is removed without touching its target.
                    Files.walkFileTree(entry, new SimpleFileVisitor<Path>() {
                        @Override
                        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                                throws IOException {
                            Files.delete(file);
                            if (attrs.isRegularFile())
                                result.removedBytes += attrs.size();
                            return FileVisitResult.CONTINUE;
                        }
                        @Override
                        public FileVisitResult postVisitDirectory(Path directory, IOException error)
                                throws IOException {
                            if (error != null)
                                throw error;
                            Files.delete(directory);
                            return FileVisitResult.CONTINUE;
                        }
                    });
                    result.removedDirectories++;
                } catch (IOException | SecurityException error) {
                    result.failedDirectories++;
                }
            }
        } catch (IOException | DirectoryIteratorException | SecurityException error) {
            result.failedDirectories++;
        }
        return result;
    }
}
