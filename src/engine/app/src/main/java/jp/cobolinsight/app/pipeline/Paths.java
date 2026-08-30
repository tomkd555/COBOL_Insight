package jp.cobolinsight.app.pipeline;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Path, hashing and file-writing helpers the runners used to keep a copy of each. */
public final class Paths {

    private Paths() {
    }

    /** The asset folder's identity. Two spellings of the same folder must give the same value. */
    public static String rootOf(Path inputDir) {
        return inputDir.toAbsolutePath().normalize().toString();
    }

    /**
     * Relative to the asset folder with forward slashes. A file outside it falls back to
     * "parent directory/file name", which is what copybooks reached through a search path get.
     */
    public static String relativize(Path inputDir, Path file) {
        Path base = inputDir.toAbsolutePath().normalize();
        Path abs = file.toAbsolutePath().normalize();
        if (abs.startsWith(base)) {
            return base.relativize(abs).toString().replace('\\', '/');
        }
        Path parent = abs.getParent();
        String dirName = parent == null ? "" : parent.getFileName().toString();
        return (dirName.isEmpty() ? "" : dirName + "/") + abs.getFileName();
    }

    /** Relative to the asset folder, or the absolute path when the file lies outside it. */
    public static String relativizeOrAbsolute(Path inputDir, Path file) {
        Path base = inputDir.toAbsolutePath().normalize();
        Path abs = file.toAbsolutePath().normalize();
        return abs.startsWith(base) ? base.relativize(abs).toString().replace('\\', '/')
                : abs.toString().replace('\\', '/');
    }

    public static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static byte[] readBytes(Path file) {
        try {
            return Files.readAllBytes(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Writes UTF-8 text, creating the parent directory when the caller named one. */
    public static void writeString(Path file, String content) {
        try {
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
            Files.writeString(file, content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
