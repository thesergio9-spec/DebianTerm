package com.debianchroot.term;

import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;

import org.tukaani.xz.XZInputStream;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Pure Java streaming USTAR/PAX/GNU-LongName tar extractor for non-root PRoot Debian installations.
 */
public final class TarExtractor {

    public interface ProgressCallback {
        void onProgress(long entriesExtracted, long bytesRead);
    }

    private static final int BLOCK_SIZE = 512;

    private static final class PendingHardlink {
        final String destRelPath;
        final String targetRelPath;
        final int mode;

        PendingHardlink(String destRelPath, String targetRelPath, int mode) {
            this.destRelPath = destRelPath;
            this.targetRelPath = targetRelPath;
            this.mode = mode;
        }
    }

    private static final class CountingInputStream extends InputStream {
        private final InputStream delegate;
        long bytesRead = 0L;

        CountingInputStream(InputStream delegate) {
            this.delegate = delegate;
        }

        @Override
        public int read() throws IOException {
            int b = delegate.read();
            if (b != -1) bytesRead++;
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int n = delegate.read(b, off, len);
            if (n > 0) bytesRead += n;
            return n;
        }

        @Override
        public void close() throws IOException {
            delegate.close();
        }
    }

    private TarExtractor() {}

    public static void extractTarXz(
        File tarXzFile,
        File destRoot,
        boolean skipDevEntries,
        AtomicBoolean cancelled,
        ProgressCallback cb
    ) throws IOException {
        try (CountingInputStream compressed = new CountingInputStream(
                 new BufferedInputStream(new FileInputStream(tarXzFile), 65536));
             XZInputStream xzIn = new XZInputStream(compressed)) {
            ProgressCallback wrappedCb = cb == null ? null : (entries, ignored) ->
                cb.onProgress(entries, compressed.bytesRead);
            extractTar(xzIn, destRoot, skipDevEntries, cancelled, wrappedCb);
        }
    }

    public static void extractTar(
        InputStream tarStream,
        File destRoot,
        boolean skipDevEntries,
        AtomicBoolean cancelled,
        ProgressCallback cb
    ) throws IOException {
        if (!destRoot.exists() && !destRoot.mkdirs()) {
            throw new IOException("No se pudo crear el directorio destino: " + destRoot);
        }
        String rootCanonical = destRoot.getCanonicalPath();
        String rootPrefix = rootCanonical.endsWith(File.separator) ? rootCanonical : rootCanonical + File.separator;

        CountingInputStream countIn = new CountingInputStream(tarStream);
        byte[] header = new byte[BLOCK_SIZE];
        byte[] copyBuf = new byte[32768];
        int consecutiveZeroBlocks = 0;
        long entriesExtracted = 0L;
        long lastProgressMs = 0L;

        String nextLongName = null;
        String nextLongLink = null;
        Map<String, String> globalPax = new HashMap<>();
        Map<String, String> nextPax = new HashMap<>();
        List<PendingHardlink> pendingHardlinks = new ArrayList<>();

        while (true) {
            if (cancelled != null && cancelled.get()) {
                throw new IOException("Extracción cancelada por el usuario.");
            }
            if (!readFullyOrEof(countIn, header, BLOCK_SIZE)) {
                break;
            }
            if (isAllZero(header)) {
                consecutiveZeroBlocks++;
                if (consecutiveZeroBlocks >= 2) {
                    break;
                }
                continue;
            }
            consecutiveZeroBlocks = 0;

            byte typeByte = header[156];
            char typeFlag = (typeByte == 0) ? '0' : (char) (typeByte & 0xff);
            long size = parseNumericField(header, 124, 12);
            if (size < 0) {
                throw new IOException("Tamaño negativo inválido en cabecera tar.");
            }

            if (typeFlag == 'L') {
                nextLongName = readBoundedTextPayload(countIn, size, 65536);
                skipPadding(countIn, size);
                continue;
            }
            if (typeFlag == 'K') {
                nextLongLink = readBoundedTextPayload(countIn, size, 65536);
                skipPadding(countIn, size);
                continue;
            }
            if (typeFlag == 'x') {
                String paxText = readBoundedTextPayload(countIn, size, 262144);
                skipPadding(countIn, size);
                parsePaxRecords(paxText, nextPax);
                continue;
            }
            if (typeFlag == 'g') {
                String paxText = readBoundedTextPayload(countIn, size, 262144);
                skipPadding(countIn, size);
                parsePaxRecords(paxText, globalPax);
                continue;
            }

            // Regular tar entry; apply pending PAX and GNU LongName/LongLink overrides.
            Map<String, String> mergedPax = null;
            if (!globalPax.isEmpty() || !nextPax.isEmpty()) {
                mergedPax = new HashMap<>(globalPax);
                mergedPax.putAll(nextPax);
            }

            String name = parseHeaderString(header, 0, 100);
            if (hasUstarMagic(header)) {
                String prefix = parseHeaderString(header, 345, 155);
                if (!prefix.isEmpty()) {
                    name = name.isEmpty() ? prefix : (prefix + "/" + name);
                }
            }
            String linkName = parseHeaderString(header, 157, 100);
            int mode = (int) parseNumericField(header, 100, 8);
            if (mode < 0) mode = 0644;

            if (mergedPax != null) {
                if (mergedPax.containsKey("path")) name = mergedPax.get("path");
                if (mergedPax.containsKey("linkpath")) linkName = mergedPax.get("linkpath");
                String paxSize = mergedPax.get("size");
                if (paxSize != null) {
                    try {
                        long parsed = Long.parseLong(paxSize.trim());
                        if (parsed >= 0) size = parsed;
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
            if (nextLongName != null) name = nextLongName;
            if (nextLongLink != null) linkName = nextLongLink;

            nextLongName = null;
            nextLongLink = null;
            nextPax.clear();

            String relPath = normalizeRelativePath(name);
            boolean skipEntry = false;
            if (relPath == null || relPath.isEmpty()) {
                // Path traversal or empty root "." entry
                skipEntry = true;
            } else if (".".equals(relPath)) {
                skipEntry = true;
            } else if (skipDevEntries && (relPath.startsWith("dev/")
                || typeFlag == '3' || typeFlag == '4' || typeFlag == '6')) {
                skipEntry = true;
            } else if (typeFlag == '3' || typeFlag == '4' || typeFlag == '6') {
                skipEntry = true;
            }

            if (skipEntry) {
                skipExactly(countIn, size + paddingFor(size));
                continue;
            }

            File destFile = resolveSafeDest(destRoot, rootCanonical, rootPrefix, relPath);
            if (destFile == null) {
                // Reject path traversal outside destRoot
                skipExactly(countIn, size + paddingFor(size));
                continue;
            }

            if (typeFlag == '5') {
                ensureParentDirsSafe(destRoot, rootCanonical, rootPrefix, destFile);
                if (destFile.exists() && isSymlink(destFile)) {
                    // Preserve directory if symlink intentionally targets a dir inside rootfs
                    skipExactly(countIn, size + paddingFor(size));
                } else {
                    if (destFile.isFile()) {
                        //noinspection ResultOfMethodCallIgnored
                        destFile.delete();
                    }
                    if (!destFile.exists() && !destFile.mkdirs()) {
                        throw new IOException("No se pudo crear directorio: " + destFile);
                    }
                    setMode(destFile, (mode & 0777) | 0700);
                    skipExactly(countIn, size + paddingFor(size));
                }
                entriesExtracted++;
            } else if (typeFlag == '0') {
                ensureParentDirsSafe(destRoot, rootCanonical, rootPrefix, destFile);
                if (destFile.exists() || isSymlink(destFile)) {
                    deleteRecursivelyNoFollow(destFile);
                }
                try (FileOutputStream fos = new FileOutputStream(destFile)) {
                    long remaining = size;
                    while (remaining > 0) {
                        if (cancelled != null && cancelled.get()) {
                            throw new IOException("Extracción cancelada por el usuario.");
                        }
                        int toRead = (int) Math.min(copyBuf.length, remaining);
                        int n = countIn.read(copyBuf, 0, toRead);
                        if (n == -1) {
                            throw new IOException("EOF inesperado dentro de entrada tar: " + relPath);
                        }
                        fos.write(copyBuf, 0, n);
                        remaining -= n;
                    }
                }
                skipPadding(countIn, size);
                setMode(destFile, (mode & 0777) | 0600);
                entriesExtracted++;
            } else if (typeFlag == '2') {
                ensureParentDirsSafe(destRoot, rootCanonical, rootPrefix, destFile);
                if (destFile.exists() || isSymlink(destFile)) {
                    deleteRecursivelyNoFollow(destFile);
                }
                createSymlink(linkName, destFile);
                skipExactly(countIn, size + paddingFor(size));
                entriesExtracted++;
            } else if (typeFlag == '1') {
                String targetRel = normalizeRelativePath(linkName);
                if (targetRel != null && !targetRel.isEmpty() && !".".equals(targetRel)) {
                    pendingHardlinks.add(new PendingHardlink(relPath, targetRel, mode));
                }
                skipExactly(countIn, size + paddingFor(size));
            } else {
                // Unknown or metadata entry ('V', 'D', 'M', etc.) -> skip data cleanly
                skipExactly(countIn, size + paddingFor(size));
            }

            if (cb != null) {
                long now = System.currentTimeMillis();
                if (now - lastProgressMs >= 250) {
                    lastProgressMs = now;
                    cb.onProgress(entriesExtracted, countIn.bytesRead);
                }
            }
        }

        // Resolve hardlinks after stream completes by copying target files safely
        for (PendingHardlink hl : pendingHardlinks) {
            if (cancelled != null && cancelled.get()) {
                throw new IOException("Extracción cancelada por el usuario.");
            }
            if (skipDevEntries && hl.targetRelPath.startsWith("dev/")) {
                continue;
            }
            File targetFile = resolveSafeDest(destRoot, rootCanonical, rootPrefix, hl.targetRelPath);
            File linkDest = resolveSafeDest(destRoot, rootCanonical, rootPrefix, hl.destRelPath);
            if (targetFile == null || linkDest == null || !targetFile.exists()) {
                continue;
            }
            ensureParentDirsSafe(destRoot, rootCanonical, rootPrefix, linkDest);
            if (isSymlink(targetFile)) {
                String symTarget = readSymlinkTarget(targetFile);
                if (symTarget != null) {
                    if (linkDest.exists() || isSymlink(linkDest)) {
                        deleteRecursivelyNoFollow(linkDest);
                    }
                    createSymlink(symTarget, linkDest);
                    entriesExtracted++;
                }
                continue;
            }
            if (!targetFile.isFile()) {
                continue;
            }
            if (linkDest.exists() || isSymlink(linkDest)) {
                deleteRecursivelyNoFollow(linkDest);
            }
            copyFile(targetFile, linkDest, copyBuf);
            setMode(linkDest, (hl.mode & 0777) | 0600);
            entriesExtracted++;
        }

        if (cb != null) {
            cb.onProgress(entriesExtracted, countIn.bytesRead);
        }
    }

    public static boolean deleteRecursivelyNoFollow(File target) {
        if (target == null) return true;
        if (!target.exists() && !isSymlink(target)) {
            return true;
        }
        if (isSymlink(target)) {
            return target.delete();
        }
        if (target.isDirectory()) {
            File[] children = target.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursivelyNoFollow(child);
                }
            }
        }
        return target.delete();
    }

    public static boolean isSymlink(File file) {
        if (file == null) return false;
        try {
            StructStat st = Os.lstat(file.getAbsolutePath());
            return (st.st_mode & OsConstants.S_IFMT) == OsConstants.S_IFLNK;
        } catch (ErrnoException e) {
            return false;
        } catch (RuntimeException | LinkageError e) {
            // Host JVM fallback when android.jar stubs throw RuntimeException("Stub!")
            return Files.isSymbolicLink(file.toPath());
        }
    }

    public static void setMode(File file, int mode) {
        if (file == null || isSymlink(file)) return;
        try {
            Os.chmod(file.getAbsolutePath(), mode);
        } catch (ErrnoException e) {
            applyJavaFilePermissions(file, mode);
        } catch (RuntimeException | LinkageError e) {
            applyJavaFilePermissions(file, mode);
        }
    }

    private static void applyJavaFilePermissions(File file, int mode) {
        boolean ownerRead = (mode & 0400) != 0;
        boolean ownerWrite = (mode & 0200) != 0;
        boolean ownerExec = (mode & 0100) != 0;
        boolean anyExec = (mode & 0111) != 0;
        boolean groupOtherRead = (mode & 0044) != 0;
        //noinspection ResultOfMethodCallIgnored
        file.setReadable(ownerRead || groupOtherRead, !groupOtherRead);
        //noinspection ResultOfMethodCallIgnored
        file.setWritable(ownerWrite, (mode & 0022) == 0);
        //noinspection ResultOfMethodCallIgnored
        file.setExecutable(ownerExec || anyExec, (mode & 0011) == 0);
    }

    public static void createSymlink(String linkTarget, File destFile) throws IOException {
        try {
            Os.symlink(linkTarget, destFile.getAbsolutePath());
        } catch (ErrnoException e) {
            throw new IOException("Error creando symlink " + destFile + " -> " + linkTarget + ": " + e.getMessage(), e);
        } catch (RuntimeException | LinkageError e) {
            Files.createSymbolicLink(destFile.toPath(), new File(linkTarget).toPath());
        }
    }

    public static String readSymlinkTarget(File symlinkFile) {
        try {
            return Os.readlink(symlinkFile.getAbsolutePath());
        } catch (ErrnoException e) {
            return null;
        } catch (RuntimeException | LinkageError e) {
            try {
                return Files.readSymbolicLink(symlinkFile.toPath()).toString();
            } catch (IOException ioe) {
                return null;
            }
        }
    }

    private static void copyFile(File src, File dst, byte[] buf) throws IOException {
        try (FileInputStream fis = new FileInputStream(src);
             FileOutputStream fos = new FileOutputStream(dst)) {
            int n;
            while ((n = fis.read(buf)) != -1) {
                fos.write(buf, 0, n);
            }
        }
    }

    private static String normalizeRelativePath(String raw) {
        if (raw == null) return null;
        String[] parts = raw.split("/");
        List<String> clean = new ArrayList<>();
        for (String seg : parts) {
            if (seg.isEmpty() || ".".equals(seg)) {
                continue;
            }
            if ("..".equals(seg)) {
                return null;
            }
            clean.add(seg);
        }
        if (clean.isEmpty()) {
            return ".";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < clean.size(); i++) {
            if (i > 0) sb.append('/');
            sb.append(clean.get(i));
        }
        return sb.toString();
    }

    private static File resolveSafeDest(File destRoot, String rootCanonical, String rootPrefix, String relPath) {
        if (relPath == null || relPath.isEmpty() || ".".equals(relPath)) {
            return null;
        }
        File candidate = new File(destRoot, relPath);
        File parent = candidate.getParentFile();
        if (parent != null && parent.exists()) {
            try {
                String parentCanon = parent.getCanonicalPath();
                if (!parentCanon.equals(rootCanonical) && !parentCanon.startsWith(rootPrefix)) {
                    return null;
                }
            } catch (IOException e) {
                return null;
            }
        }
        return candidate;
    }

    private static void ensureParentDirsSafe(
        File destRoot,
        String rootCanonical,
        String rootPrefix,
        File destFile
    ) throws IOException {
        File parent = destFile.getParentFile();
        if (parent == null || parent.equals(destRoot)) {
            return;
        }
        if (parent.exists()) {
            String canon = parent.getCanonicalPath();
            if (!canon.equals(rootCanonical) && !canon.startsWith(rootPrefix)) {
                throw new IOException("Ruta escapa fuera del rootfs: " + destFile);
            }
            return;
        }
        // Build segment by segment from destRoot without following escaping symlinks
        String relParent = parent.getPath().substring(destRoot.getPath().length());
        String[] segs = relParent.split(File.separatorChar == '\\' ? "\\\\" : "/");
        File cur = destRoot;
        for (String seg : segs) {
            if (seg.isEmpty()) continue;
            cur = new File(cur, seg);
            if (isSymlink(cur)) {
                String symCanon = cur.getCanonicalPath();
                if (!symCanon.equals(rootCanonical) && !symCanon.startsWith(rootPrefix)) {
                    throw new IOException("Symlink padre apunta fuera de rootfs: " + cur);
                }
            } else if (!cur.exists()) {
                if (!cur.mkdir()) {
                    throw new IOException("No se pudo crear directorio padre: " + cur);
                }
                setMode(cur, 0755);
            } else if (!cur.isDirectory()) {
                throw new IOException("Conflicto: componente padre no es directorio: " + cur);
            }
        }
    }

    private static boolean hasUstarMagic(byte[] header) {
        return header[257] == 'u' && header[258] == 's' && header[259] == 't'
            && header[260] == 'a' && header[261] == 'r';
    }

    private static boolean isAllZero(byte[] buf) {
        for (byte b : buf) {
            if (b != 0) return false;
        }
        return true;
    }

    private static String parseHeaderString(byte[] buf, int off, int len) {
        int end = off;
        int limit = off + len;
        while (end < limit && buf[end] != 0) {
            end++;
        }
        return new String(buf, off, end - off, StandardCharsets.UTF_8);
    }

    private static long parseNumericField(byte[] buf, int off, int len) {
        if (len <= 0) return 0L;
        if ((buf[off] & 0x80) != 0) {
            // GNU base-256 numeric encoding
            boolean negative = (buf[off] & 0x40) != 0;
            long val = buf[off] & 0x3f;
            if (negative) val = -1L ^ (~val & 0x3fL);
            for (int i = 1; i < len; i++) {
                val = (val << 8) | (buf[off + i] & 0xffL);
            }
            return val;
        }
        int start = off;
        int end = off + len;
        while (start < end && (buf[start] == ' ' || buf[start] == 0)) {
            start++;
        }
        while (end > start && (buf[end - 1] == ' ' || buf[end - 1] == 0)) {
            end--;
        }
        if (start >= end) return 0L;
        long result = 0L;
        for (int i = start; i < end; i++) {
            byte c = buf[i];
            if (c < '0' || c > '7') {
                return -1L;
            }
            result = (result << 3) + (c - '0');
        }
        return result;
    }

    private static String readBoundedTextPayload(InputStream in, long size, int maxBytes) throws IOException {
        if (size > maxBytes) {
            byte[] head = new byte[maxBytes];
            int read = readUpTo(in, head, maxBytes);
            skipExactly(in, size - read);
            return trimTrailingNull(new String(head, 0, read, StandardCharsets.UTF_8));
        }
        int sz = (int) size;
        byte[] buf = new byte[sz];
        int read = readUpTo(in, buf, sz);
        if (read < sz) {
            throw new IOException("EOF inesperado leyendo metadatos tar");
        }
        return trimTrailingNull(new String(buf, StandardCharsets.UTF_8));
    }

    private static String trimTrailingNull(String s) {
        int end = s.length();
        while (end > 0 && s.charAt(end - 1) == '\0') {
            end--;
        }
        return s.substring(0, end);
    }

    private static void parsePaxRecords(String paxText, Map<String, String> out) {
        if (paxText == null || paxText.isEmpty()) return;
        int pos = 0;
        int len = paxText.length();
        while (pos < len) {
            int sp = paxText.indexOf(' ', pos);
            if (sp <= pos) break;
            int eq = paxText.indexOf('=', sp + 1);
            int nl = paxText.indexOf('\n', sp + 1);
            if (eq > sp && nl > eq) {
                String key = paxText.substring(sp + 1, eq);
                String val = paxText.substring(eq + 1, nl);
                out.put(key, val);
                pos = nl + 1;
            } else if (nl > pos) {
                pos = nl + 1;
            } else {
                break;
            }
        }
    }

    private static long paddingFor(long size) {
        long mod = size % BLOCK_SIZE;
        return mod == 0 ? 0 : (BLOCK_SIZE - mod);
    }

    private static void skipPadding(InputStream in, long size) throws IOException {
        long pad = paddingFor(size);
        if (pad > 0) {
            skipExactly(in, pad);
        }
    }

    private static void skipExactly(InputStream in, long bytes) throws IOException {
        byte[] discard = new byte[4096];
        long rem = bytes;
        while (rem > 0) {
            long skipped = in.skip(rem);
            if (skipped > 0) {
                rem -= skipped;
            } else {
                int n = in.read(discard, 0, (int) Math.min(discard.length, rem));
                if (n == -1) {
                    return;
                }
                rem -= n;
            }
        }
    }

    private static boolean readFullyOrEof(InputStream in, byte[] dst, int len) throws IOException {
        int off = 0;
        while (off < len) {
            int n = in.read(dst, off, len - off);
            if (n == -1) {
                return false;
            }
            off += n;
        }
        return true;
    }

    private static int readUpTo(InputStream in, byte[] dst, int len) throws IOException {
        int off = 0;
        while (off < len) {
            int n = in.read(dst, off, len - off);
            if (n == -1) break;
            off += n;
        }
        return off;
    }
}
