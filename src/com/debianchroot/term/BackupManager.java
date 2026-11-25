package com.debianchroot.term;

import android.content.Context;
import android.os.Environment;
import android.system.ErrnoException;
import android.system.Os;
import android.system.StructStat;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Pure Java 1-tap backup & restore (.tar.gz) manager for DebianTerm.
 * Uses USTAR + PAX extended headers (via OutputStream) for full compatibility with TarExtractor.
 */
public final class BackupManager {

    public static final String BACKUP_SUBDIR = "DebianTerm-Backups";
    public static final String META_ENTRY_NAME = ".debianterm-settings.properties";

    public static final Set<String> SKIP_ROOTFS_TOP_DIRS = Collections.unmodifiableSet(
        new HashSet<>(Arrays.asList("dev", "proc", "sys", "tmp", "sdcard", "external_sd", "storage", "mnt"))
    );

    public static final Set<String> SKIP_HOME_TOP_DIRS = Collections.unmodifiableSet(
        new HashSet<>(Collections.singletonList("storage"))
    );

    private static final int BLOCK_SIZE = 512;
    private static final long MAX_OCTAL_SIZE = 077777777777L;

    public interface ProgressListener {
        void onProgress(long entriesProcessed, long bytesProcessed, String currentEntry);
    }

    public static final class BackupResult {
        public final boolean success;
        public final File outputFile;
        public final long entriesCount;
        public final long archiveBytes;
        public final String message;

        public BackupResult(boolean success, File outputFile, long entriesCount, long archiveBytes, String message) {
            this.success = success;
            this.outputFile = outputFile;
            this.entriesCount = entriesCount;
            this.archiveBytes = archiveBytes;
            this.message = message;
        }
    }

    public static final class RestoreResult {
        public final boolean success;
        public final long entriesCount;
        public final byte[] metadataBytes;
        public final String message;

        public RestoreResult(boolean success, long entriesCount, byte[] metadataBytes, String message) {
            this.success = success;
            this.entriesCount = entriesCount;
            this.metadataBytes = metadataBytes;
            this.message = message;
        }
    }

    private BackupManager() {}

    /**
     * Creates a .tar.gz archive from sourceDir using USTAR + PAX headers, excluding virtual/external entries.
     */
    public static BackupResult createTarGz(
        File sourceDir,
        File outputArchive,
        String extraMetaName,
        byte[] extraMetaBytes,
        Set<String> skipTopLevelNames,
        AtomicBoolean cancelled,
        ProgressListener listener
    ) {
        if (sourceDir == null || !sourceDir.isDirectory()) {
            return new BackupResult(false, outputArchive, 0L, 0L, "El directorio origen no existe.");
        }
        if (outputArchive == null) {
            return new BackupResult(false, null, 0L, 0L, "Ruta de archivo salida inválida.");
        }
        File parentDir = outputArchive.getParentFile();
        if (parentDir != null && !parentDir.exists() && !parentDir.mkdirs()) {
            return new BackupResult(false, outputArchive, 0L, 0L, "No se pudo crear carpeta: " + parentDir);
        }

        File tmpArchive = new File(outputArchive.getAbsolutePath() + ".tmp");
        long[] stats = new long[2]; // [0] = entries, [1] = payloadBytes
        byte[] copyBuf = new byte[32768];
        long[] lastNotifyMs = new long[1];

        try (GZIPOutputStream gzOut = new GZIPOutputStream(
                 new BufferedOutputStream(new FileOutputStream(tmpArchive), 65536))) {

            if (extraMetaName != null && !extraMetaName.isEmpty()
                && extraMetaBytes != null && extraMetaBytes.length > 0) {
                writeBytesEntry(gzOut, extraMetaName, extraMetaBytes, 0644, System.currentTimeMillis());
                stats[0]++;
                stats[1] += extraMetaBytes.length;
                if (listener != null) {
                    listener.onProgress(stats[0], stats[1], extraMetaName);
                }
            }

            File[] topChildren = sortedChildren(sourceDir);
            for (File child : topChildren) {
                if (cancelled != null && cancelled.get()) {
                    throw new IOException("Respaldo cancelado por el usuario.");
                }
                String name = child.getName();
                if (".".equals(name) || "..".equals(name)) continue;
                if (skipTopLevelNames != null && skipTopLevelNames.contains(name)) {
                    continue;
                }
                writeTree(gzOut, child, name, copyBuf, stats, lastNotifyMs, cancelled, listener);
            }

            // Standard TAR EOF marker: two 512-byte zero blocks
            gzOut.write(new byte[BLOCK_SIZE * 2]);
            gzOut.finish();
        } catch (Throwable t) {
            //noinspection ResultOfMethodCallIgnored
            tmpArchive.delete();
            return new BackupResult(false, outputArchive, stats[0], 0L,
                "Error creando respaldo: " + t.getMessage());
        }

        if (outputArchive.exists() && !outputArchive.delete()) {
            //noinspection ResultOfMethodCallIgnored
            tmpArchive.delete();
            return new BackupResult(false, outputArchive, stats[0], 0L,
                "No se pudo reemplazar el archivo destino: " + outputArchive.getAbsolutePath());
        }
        if (!tmpArchive.renameTo(outputArchive)) {
            //noinspection ResultOfMethodCallIgnored
            tmpArchive.delete();
            return new BackupResult(false, outputArchive, stats[0], 0L,
                "No se pudo guardar el archivo final: " + outputArchive.getAbsolutePath());
        }

        long finalBytes = outputArchive.length();
        if (listener != null) {
            listener.onProgress(stats[0], finalBytes, outputArchive.getName());
        }
        return new BackupResult(true, outputArchive, stats[0], finalBytes, "Respaldo completado");
    }

    private static void writeTree(
        OutputStream tarOut,
        File file,
        String relPath,
        byte[] copyBuf,
        long[] stats,
        long[] lastNotifyMs,
        AtomicBoolean cancelled,
        ProgressListener listener
    ) throws IOException {
        if (cancelled != null && cancelled.get()) {
            throw new IOException("Respaldo cancelado por el usuario.");
        }

        if (TarExtractor.isSymlink(file)) {
            String symTarget = TarExtractor.readSymlinkTarget(file);
            if (symTarget != null && !isExternalStorageTarget(symTarget)) {
                writeSymlinkEntry(tarOut, relPath, symTarget, file.lastModified());
                stats[0]++;
                notifyThrottled(listener, stats, lastNotifyMs, relPath);
            }
            return;
        }

        if (file.isDirectory()) {
            int mode = readFileMode(file, 0755);
            writeDirEntry(tarOut, relPath.endsWith("/") ? relPath : (relPath + "/"), mode, file.lastModified());
            stats[0]++;
            notifyThrottled(listener, stats, lastNotifyMs, relPath);

            File[] children = sortedChildren(file);
            for (File child : children) {
                String cname = child.getName();
                if (".".equals(cname) || "..".equals(cname)) continue;
                writeTree(tarOut, child, relPath + "/" + cname, copyBuf, stats, lastNotifyMs, cancelled, listener);
            }
            return;
        }

        if (file.isFile()) {
            int mode = readFileMode(file, 0644);
            long size = file.length();
            writeEntryHeader(tarOut, relPath, '0', null, size, mode, file.lastModified());

            try (FileInputStream fis = new FileInputStream(file)) {
                long remaining = size;
                while (remaining > 0) {
                    if (cancelled != null && cancelled.get()) {
                        throw new IOException("Respaldo cancelado por el usuario.");
                    }
                    int toRead = (int) Math.min(copyBuf.length, remaining);
                    int n = fis.read(copyBuf, 0, toRead);
                    if (n == -1) {
                        throw new IOException("EOF inesperado leyendo " + relPath);
                    }
                    tarOut.write(copyBuf, 0, n);
                    remaining -= n;
                    stats[1] += n;
                }
            }
            writePadding(tarOut, size);
            stats[0]++;
            notifyThrottled(listener, stats, lastNotifyMs, relPath);
        }
    }

    private static void notifyThrottled(
        ProgressListener listener,
        long[] stats,
        long[] lastNotifyMs,
        String currentEntry
    ) {
        if (listener == null) return;
        long now = System.currentTimeMillis();
        if (now - lastNotifyMs[0] >= 200L) {
            lastNotifyMs[0] = now;
            listener.onProgress(stats[0], stats[1], currentEntry);
        }
    }

    private static File[] sortedChildren(File dir) {
        File[] children = dir.listFiles();
        if (children == null || children.length == 0) {
            return new File[0];
        }
        Arrays.sort(children, (a, b) -> a.getName().compareTo(b.getName()));
        return children;
    }

    private static boolean isExternalStorageTarget(String linkTarget) {
        return linkTarget.startsWith("/sdcard")
            || linkTarget.startsWith("/storage")
            || linkTarget.startsWith("/external_sd");
    }

    private static int readFileMode(File file, int fallback) {
        try {
            StructStat st = Os.lstat(file.getAbsolutePath());
            return st.st_mode & 07777;
        } catch (ErrnoException e) {
            return fallbackWithExecute(file, fallback);
        } catch (RuntimeException | LinkageError e) {
            return fallbackWithExecute(file, fallback);
        }
    }

    private static int fallbackWithExecute(File file, int fallback) {
        if (!file.isDirectory() && file.canExecute()) {
            return fallback | 0111;
        }
        return fallback;
    }

    private static void writeBytesEntry(
        OutputStream tarOut,
        String path,
        byte[] data,
        int mode,
        long lastModifiedMs
    ) throws IOException {
        writeEntryHeader(tarOut, path, '0', null, data.length, mode, lastModifiedMs);
        if (data.length > 0) {
            tarOut.write(data);
            writePadding(tarOut, data.length);
        }
    }

    private static void writeDirEntry(
        OutputStream tarOut,
        String path,
        int mode,
        long lastModifiedMs
    ) throws IOException {
        writeEntryHeader(tarOut, path, '5', null, 0L, mode, lastModifiedMs);
    }

    private static void writeSymlinkEntry(
        OutputStream tarOut,
        String path,
        String linkTarget,
        long lastModifiedMs
    ) throws IOException {
        writeEntryHeader(tarOut, path, '2', linkTarget, 0L, 0777, lastModifiedMs);
    }

    private static void writeEntryHeader(
        OutputStream tarOut,
        String path,
        char typeFlag,
        String linkPath,
        long size,
        int mode,
        long lastModifiedMs
    ) throws IOException {
        byte[] pathBytes = path.getBytes(StandardCharsets.UTF_8);
        byte[] linkBytes = linkPath != null ? linkPath.getBytes(StandardCharsets.UTF_8) : null;

        boolean needPaxPath = pathBytes.length > 99 || !isPureAscii(path);
        boolean needPaxLink = linkBytes != null && (linkBytes.length > 99 || !isPureAscii(linkPath));
        boolean needPaxSize = size > MAX_OCTAL_SIZE;

        if (needPaxPath || needPaxLink || needPaxSize) {
            Map<String, String> pax = new LinkedHashMap<>();
            if (needPaxPath) pax.put("path", path);
            if (needPaxLink) pax.put("linkpath", linkPath);
            if (needPaxSize) pax.put("size", Long.toString(size));

            byte[] paxData = encodePaxRecords(pax);
            String paxHeaderName = "PaxHeaders.0/" + safeAsciiShortName(path, 60);
            byte[] paxHdr = buildUstarHeader(
                paxHeaderName, 'x', null, paxData.length, 0644, lastModifiedMs);
            tarOut.write(paxHdr);
            tarOut.write(paxData);
            writePadding(tarOut, paxData.length);
        }

        String ustarName = needPaxPath ? safeAsciiShortName(path, 95) : path;
        String ustarLink = needPaxLink ? safeAsciiShortName(linkPath, 95) : linkPath;
        long ustarSize = needPaxSize ? 0L : size;

        byte[] hdr = buildUstarHeader(ustarName, typeFlag, ustarLink, ustarSize, mode, lastModifiedMs);
        tarOut.write(hdr);
    }

    private static byte[] buildUstarHeader(
        String name,
        char typeFlag,
        String linkName,
        long size,
        int mode,
        long lastModifiedMs
    ) {
        byte[] hdr = new byte[BLOCK_SIZE];
        writeHeaderString(hdr, 0, 100, name);
        writeOctal(hdr, 100, 8, mode & 07777);
        writeOctal(hdr, 108, 8, 0L);
        writeOctal(hdr, 116, 8, 0L);
        writeOctal(hdr, 124, 12, Math.max(0L, size));
        long mtimeSec = Math.max(0L, lastModifiedMs / 1000L);
        writeOctal(hdr, 136, 12, mtimeSec);
        Arrays.fill(hdr, 148, 156, (byte) ' ');
        hdr[156] = (byte) typeFlag;
        if (linkName != null) {
            writeHeaderString(hdr, 157, 100, linkName);
        }
        // USTAR magic "ustar\0" + version "00"
        hdr[257] = 'u';
        hdr[258] = 's';
        hdr[259] = 't';
        hdr[260] = 'a';
        hdr[261] = 'r';
        hdr[262] = 0;
        hdr[263] = '0';
        hdr[264] = '0';

        int sum = 0;
        for (byte b : hdr) {
            sum += (b & 0xff);
        }
        writeOctal(hdr, 148, 7, sum);
        hdr[155] = ' ';
        return hdr;
    }

    private static byte[] encodePaxRecords(Map<String, String> records) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        for (Map.Entry<String, String> entry : records.entrySet()) {
            byte[] kv = (" " + entry.getKey() + "=" + entry.getValue() + "\n").getBytes(StandardCharsets.UTF_8);
            int totalLen = kv.length + 1;
            while (true) {
                byte[] numBytes = Integer.toString(totalLen).getBytes(StandardCharsets.US_ASCII);
                int actual = numBytes.length + kv.length;
                if (actual == totalLen) {
                    baos.write(numBytes, 0, numBytes.length);
                    baos.write(kv, 0, kv.length);
                    break;
                }
                totalLen = actual;
            }
        }
        return baos.toByteArray();
    }

    private static void writeHeaderString(byte[] buf, int off, int maxLen, String s) {
        if (s == null) return;
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        int n = Math.min(maxLen - 1, bytes.length);
        System.arraycopy(bytes, 0, buf, off, n);
    }

    private static void writeOctal(byte[] buf, int off, int len, long value) {
        String s = Long.toOctalString(Math.max(0L, value));
        int idx = off + len - 1;
        buf[idx--] = 0;
        for (int i = s.length() - 1; i >= 0 && idx >= off; i--) {
            buf[idx--] = (byte) s.charAt(i);
        }
        while (idx >= off) {
            buf[idx--] = '0';
        }
    }

    private static void writePadding(OutputStream out, long size) throws IOException {
        int mod = (int) (size % BLOCK_SIZE);
        if (mod != 0) {
            out.write(new byte[BLOCK_SIZE - mod]);
        }
    }

    private static boolean isPureAscii(String s) {
        if (s == null) return true;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) > 0x7f) return false;
        }
        return true;
    }

    private static String safeAsciiShortName(String raw, int maxLen) {
        if (raw == null || raw.isEmpty()) return "entry";
        StringBuilder sb = new StringBuilder(Math.min(raw.length(), maxLen));
        for (int i = 0; i < raw.length() && sb.length() < maxLen; i++) {
            char c = raw.charAt(i);
            sb.append((c >= 0x20 && c <= 0x7e) ? c : '_');
        }
        return sb.length() == 0 ? "entry" : sb.toString();
    }

    /**
     * Restores a .tar.gz archive file into targetDir using pure-Java streaming extraction.
     */
    public static RestoreResult restoreTarGz(
        File archiveFile,
        File targetDir,
        AtomicBoolean cancelled,
        ProgressListener listener
    ) {
        if (archiveFile == null || !archiveFile.isFile()) {
            return new RestoreResult(false, 0L, null, "El archivo de respaldo no existe.");
        }
        try (FileInputStream fis = new FileInputStream(archiveFile)) {
            return restoreTarGz(fis, targetDir, cancelled, listener);
        } catch (IOException e) {
            return new RestoreResult(false, 0L, null, "Error abriendo archivo: " + e.getMessage());
        }
    }

    /**
     * Restores a compressed .tar.gz InputStream into targetDir, extracting optional DebianTerm metadata.
     */
    public static RestoreResult restoreTarGz(
        InputStream compressedIn,
        File targetDir,
        AtomicBoolean cancelled,
        ProgressListener listener
    ) {
        if (compressedIn == null || targetDir == null) {
            return new RestoreResult(false, 0L, null, "Parámetros de restauración inválidos.");
        }
        final long[] countHolder = new long[1];
        try (GZIPInputStream gzIn = new GZIPInputStream(new BufferedInputStream(compressedIn, 65536))) {
            TarExtractor.extractTar(gzIn, targetDir, true, cancelled, (entries, bytesRead) -> {
                countHolder[0] = entries;
                if (listener != null) {
                    listener.onProgress(entries, bytesRead, "Extrayendo entradas...");
                }
            });
        } catch (Throwable t) {
            return new RestoreResult(false, countHolder[0], null,
                "Error restaurando respaldo: " + t.getMessage());
        }

        byte[] metaBytes = null;
        File metaFile = new File(targetDir, META_ENTRY_NAME);
        if (metaFile.isFile() && !TarExtractor.isSymlink(metaFile)) {
            try {
                metaBytes = readBoundedBytes(metaFile, 262144);
            } catch (IOException ignored) {
            }
            //noinspection ResultOfMethodCallIgnored
            metaFile.delete();
        }
        return new RestoreResult(true, countHolder[0], metaBytes, "Restauración completada");
    }

    private static byte[] readBoundedBytes(File f, int maxBytes) throws IOException {
        try (FileInputStream fis = new FileInputStream(f)) {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int total = 0;
            int n;
            while (total < maxBytes && (n = fis.read(buf, 0, Math.min(buf.length, maxBytes - total))) != -1) {
                baos.write(buf, 0, n);
                total += n;
            }
            return baos.toByteArray();
        }
    }

    /**
     * Creates a read-only .tar.gz backup of /root inside a Root chroot path without modifying or unmounting mounts.
     */
    public static BackupResult backupRootHome(String chrootPath, File outputArchive) {
        if (chrootPath == null || chrootPath.trim().isEmpty() || outputArchive == null) {
            return new BackupResult(false, outputArchive, 0L, 0L, "Parámetros inválidos.");
        }
        String cleanChroot = chrootPath.trim();
        while (cleanChroot.length() > 1 && cleanChroot.endsWith("/")) {
            cleanChroot = cleanChroot.substring(0, cleanChroot.length() - 1);
        }
        String rootHome = cleanChroot + "/root";
        File outParent = outputArchive.getParentFile();
        String qRootHome = ChrootManager.shellQuote(rootHome);
        String qOut = ChrootManager.shellQuote(outputArchive.getAbsolutePath());
        String qParent = ChrootManager.shellQuote(outParent != null ? outParent.getAbsolutePath() : "/sdcard/Download");

        String cmd =
            "if [ ! -d " + qRootHome + " ]; then echo 'NO_ROOT_HOME' >&2; exit 2; fi; " +
            "mkdir -p " + qParent + " && " +
            "(tar --exclude='./storage' --exclude='storage' -czf " + qOut + " -C " + qRootHome + " . 2>/dev/null " +
            "|| tar -czf " + qOut + " -C " + qRootHome + " .) && " +
            "chmod 644 " + qOut;

        ChrootManager.ExecResult res = ChrootManager.execSu(cmd, 180, false);
        if (!res.isOk()) {
            return new BackupResult(false, outputArchive, 0L, 0L,
                "Fallo respaldando " + rootHome + ": " + res.stderr);
        }
        long bytes = outputArchive.isFile() ? outputArchive.length() : 0L;
        return new BackupResult(true, outputArchive, 1L, bytes, "Respaldo Root /root completado");
    }

    public static File getDefaultBackupDir(Context context) {
        if (ProotManager.hasSharedStorageAccess(context)) {
            File dl = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            File sharedBackup = new File(dl, BACKUP_SUBDIR);
            if ((sharedBackup.isDirectory() || sharedBackup.mkdirs()) && sharedBackup.canWrite()) {
                return sharedBackup;
            }
        }
        File ext = context.getExternalFilesDir(null);
        File base = (ext != null) ? ext : context.getFilesDir();
        File fallback = new File(base, BACKUP_SUBDIR);
        //noinspection ResultOfMethodCallIgnored
        fallback.mkdirs();
        return fallback;
    }

    public static List<File> listBackupArchives(Context context) {
        List<File> dirs = new ArrayList<>();
        File dl = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        dirs.add(new File(dl, BACKUP_SUBDIR));
        File ext = context.getExternalFilesDir(null);
        if (ext != null) dirs.add(new File(ext, BACKUP_SUBDIR));
        dirs.add(new File(context.getFilesDir(), BACKUP_SUBDIR));

        Map<String, File> unique = new LinkedHashMap<>();
        for (File dir : dirs) {
            if (!dir.isDirectory()) continue;
            File[] files = dir.listFiles();
            if (files == null) continue;
            for (File f : files) {
                if (!f.isFile()) continue;
                String lower = f.getName().toLowerCase(Locale.US);
                if (!lower.endsWith(".tar.gz") && !lower.endsWith(".tgz")) continue;
                try {
                    unique.putIfAbsent(f.getCanonicalPath(), f);
                } catch (IOException e) {
                    unique.putIfAbsent(f.getAbsolutePath(), f);
                }
            }
        }
        List<File> sorted = new ArrayList<>(unique.values());
        Collections.sort(sorted, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        return sorted;
    }

    public static String buildArchiveFilename(String scope) {
        String ts = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
        String cleanScope = (scope == null || scope.trim().isEmpty()) ? "backup" : scope.trim();
        return "debianterm-" + cleanScope + "-" + ts + ".tar.gz";
    }

    public static String formatByteSize(long bytes) {
        if (bytes < 1024L) return bytes + " B";
        double kb = bytes / 1024.0;
        if (kb < 1024.0) return String.format(Locale.US, "%.1f KB", kb);
        double mb = kb / 1024.0;
        if (mb < 1024.0) return String.format(Locale.US, "%.1f MB", mb);
        return String.format(Locale.US, "%.2f GB", mb / 1024.0);
    }
}
