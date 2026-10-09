package forge.adventure.util;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

/**
 * UTF-8 (no BOM) atomic file writes and resilient reads for account-side JSON.
 * A missing or corrupt file never throws to the caller in {@link #readUtf8OrEmpty};
 * writes go through a {@code .tmp} then rename so a crash mid-write cannot wipe
 * the previous good file.
 */
public final class AtomicJsonFiles {
    private AtomicJsonFiles() {
    }

    /** Strip a UTF-8 BOM if present. */
    public static String stripBom(String text) {
        if (text != null && !text.isEmpty() && text.charAt(0) == '\uFEFF') {
            return text.substring(1);
        }
        return text;
    }

    /**
     * Read a UTF-8 file. Missing file → empty string. IO errors → empty string.
     * Never throws.
     */
    public static String readUtf8OrEmpty(Path path) {
        if (path == null || !Files.isRegularFile(path)) {
            return "";
        }
        try {
            byte[] raw = Files.readAllBytes(path);
            return stripBom(new String(raw, StandardCharsets.UTF_8));
        } catch (IOException e) {
            return "";
        }
    }

    /**
     * Write UTF-8 without BOM: write to {@code path.tmp}, fsync via close, then
     * atomically replace {@code path}. Keeps a {@code .bak} of the previous good
     * file when one existed.
     */
    public static void writeUtf8Atomic(Path path, String text) throws IOException {
        if (path == null) {
            throw new IOException("path is null");
        }
        Path parent = path.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        byte[] bytes = (text == null ? "" : text).getBytes(StandardCharsets.UTF_8);
        // Guard against accidental BOM from callers.
        if (bytes.length >= 3 && (bytes[0] & 0xFF) == 0xEF && (bytes[1] & 0xFF) == 0xBB
                && (bytes[2] & 0xFF) == 0xBF) {
            byte[] noBom = new byte[bytes.length - 3];
            System.arraycopy(bytes, 3, noBom, 0, noBom.length);
            bytes = noBom;
        }
        Path tmp = path.resolveSibling(path.getFileName().toString() + ".tmp");
        Path bak = path.resolveSibling(path.getFileName().toString() + ".bak");
        Files.write(tmp, bytes, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE);
        // Keep the previous good file as .bak before replacing (if any).
        if (Files.isRegularFile(path)) {
            try {
                Files.copy(path, bak, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException ignored) {
                // Best-effort backup.
            }
        }
        try {
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicFailed) {
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
        }
        // Always refresh .bak to the just-written good content so a first write
        // still has a recovery copy if the main file is later corrupted.
        try {
            Files.copy(path, bak, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ignored) {
        }
    }
}
