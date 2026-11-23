package com.debianchroot.term;

import android.content.Context;
import android.os.Build;

import org.tukaani.xz.XZInputStream;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Downloads, verifies, extracts, and configures official Debian arm64/armhf rootfs images
 * from images.linuxcontainers.org into an Android root chroot directory.
 */
public final class ChrootInstaller {

    private static final String LXC_BASE_URL = "https://images.linuxcontainers.org";
    private static final String LXC_INDEX_URL = LXC_BASE_URL + "/meta/1.0/index-system";
    private static final int MAX_REDIRECTS = 5;

    public interface ProgressListener {
        void onProgress(int percent, String stage);
        void onLog(String line);
    }

    public static final class InstallResult {
        public final boolean success;
        public final String message;

        public InstallResult(boolean success, String message) {
            this.success = success;
            this.message = message;
        }
    }

    private ChrootInstaller() {}

    public static String detectDebianArch() {
        String[] abis = Build.SUPPORTED_ABIS;
        if (abis != null) {
            for (String abi : abis) {
                if ("arm64-v8a".equals(abi)) return "arm64";
                if ("armeabi-v7a".equals(abi)) return "armhf";
                if ("x86_64".equals(abi)) return "amd64";
                if ("x86".equals(abi)) return "i386";
            }
        }
        return "arm64";
    }

    public static String parseLxcIndex(String indexText, String release, String arch) {
        if (indexText == null || release == null || arch == null) return null;
        String bestBuild = null;
        String bestPath = null;
        String prefix = "debian;" + release + ";" + arch + ";default;";
        String[] lines = indexText.split("\\r?\\n");
        for (String line : lines) {
            if (!line.startsWith(prefix)) continue;
            String[] parts = line.split(";");
            if (parts.length < 6) continue;
            String build = parts[4].trim();
            String path = parts[5].trim();
            if (path.isEmpty()) continue;
            if (!path.endsWith("/")) {
                path = path + "/";
            }
            if (bestBuild == null || build.compareTo(bestBuild) > 0) {
                bestBuild = build;
                bestPath = path;
            }
        }
        return bestPath;
    }

    public static String parseSha256Sums(String shaSumsText, String filename) {
        if (shaSumsText == null || filename == null) return null;
        String[] lines = shaSumsText.split("\\r?\\n");
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) continue;
            // Format: "<64-hex>  rootfs.tar.xz" or "<64-hex> *./rootfs.tar.xz"
            int spaceIdx = -1;
            for (int i = 0; i < trimmed.length(); i++) {
                if (Character.isWhitespace(trimmed.charAt(i))) {
                    spaceIdx = i;
                    break;
                }
            }
            if (spaceIdx != 64) continue;
            String hash = trimmed.substring(0, 64).toLowerCase(Locale.US);
            if (!isHex64(hash)) continue;
            String namePart = trimmed.substring(spaceIdx).trim();
            if (namePart.startsWith("*")) namePart = namePart.substring(1);
            if (namePart.startsWith("./")) namePart = namePart.substring(2);
            if (namePart.equals(filename)) {
                return hash;
            }
        }
        return null;
    }

    private static boolean isHex64(String s) {
        if (s == null || s.length() != 64) return false;
        for (int i = 0; i < 64; i++) {
            char c = s.charAt(i);
            boolean hex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f');
            if (!hex) return false;
        }
        return true;
    }

    public static InstallResult install(
        Context context,
        String release,
        String chrootPath,
        String scriptPath,
        AtomicBoolean cancelled,
        ProgressListener listener
    ) {
        return installRoot(context, release, chrootPath, scriptPath, cancelled, listener);
    }

    public static InstallResult installRoot(
        Context context,
        String release,
        String chrootPath,
        String scriptPath,
        AtomicBoolean cancelled,
        ProgressListener listener
    ) {
        File cacheTarXz = new File(context.getCacheDir(), "debian-rootfs.tar.xz");
        try {
            if (cancelled.get()) return new InstallResult(false, "Instalación cancelada.");

            listener.onProgress(2, "Verificando acceso root...");
            String suBin = ChrootManager.resolveWorkingSuPath();
            ChrootManager.ExecResult idCheck = ChrootManager.execWithBinary(suBin, false, "id -u", 6);
            if (!idCheck.isOk() || !idCheck.stdout.trim().equals("0")) {
                return new InstallResult(false, "No se detectó acceso root (su). Concede permisos en Magisk/KernelSU.");
            }
            listener.onLog("Root verificado (" + suBin + ")");

            InstallResult dlRes = downloadVerifiedRootfs(release, cacheTarXz, cancelled, listener);
            if (!dlRes.success) {
                return dlRes;
            }

            listener.onProgress(65, "Preparando directorio " + chrootPath + "...");
            String qChroot = ChrootManager.shellQuote(chrootPath);
            // If old mounts exist at target, unmount safely before unpacking.
            ChrootManager.unmountChroot(chrootPath, scriptPath);
            ChrootManager.ExecResult mkdirRes = ChrootManager.execWithBinary(
                suBin, false, "mkdir -p " + qChroot + " && chmod 755 " + qChroot, 10);
            if (!mkdirRes.isOk()) {
                return new InstallResult(false, "Error creando directorio chroot: " + mkdirRes.stderr);
            }

            listener.onProgress(68, "Descomprimiendo Debian rootfs (tar.xz)...");
            listener.onLog("Extrayendo stream XZ -> tar en " + chrootPath + " (puede tardar 1-2 min)...");
            extractTarXzToRoot(suBin, cacheTarXz, chrootPath, cancelled, listener);
            if (cancelled.get()) return new InstallResult(false, "Instalación cancelada.");
            listener.onLog("Extracción de rootfs finalizada.");

            listener.onProgress(90, "Aplicando configuración Android/Debian...");
            String postCmd = buildPostInstallCommand(chrootPath);
            ChrootManager.ExecResult postRes = ChrootManager.execWithBinary(suBin, false, postCmd, 20);
            if (!postRes.isOk()) {
                return new InstallResult(false, "Error en configuración post-instalación: " + postRes.stderr);
            }
            listener.onLog("Configuración DNS, grupos Android (aid_inet) y APT aplicada.");

            listener.onProgress(95, "Instalando script de arranque " + scriptPath + "...");
            ChrootManager.ExecResult scriptRes = ChrootManager.installEntryScript(chrootPath, scriptPath, true);
            if (!scriptRes.isOk()) {
                return new InstallResult(false, "Error instalando script " + scriptPath + ": " + scriptRes.stderr);
            }
            listener.onLog("Script " + scriptPath + " listo.");

            listener.onProgress(100, "Debian " + release + " instalado correctamente (Con Root).");
            return new InstallResult(true, "Debian " + release + " instalado en " + chrootPath);
        } catch (Exception e) {
            if (cancelled.get()) {
                return new InstallResult(false, "Instalación cancelada por el usuario.");
            }
            String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            listener.onLog("ERROR: " + msg);
            return new InstallResult(false, msg);
        } finally {
            if (cacheTarXz.exists()) {
                //noinspection ResultOfMethodCallIgnored
                cacheTarXz.delete();
            }
        }
    }

    public static InstallResult installProot(
        Context context,
        String release,
        AtomicBoolean cancelled,
        ProgressListener listener
    ) {
        File cacheTarXz = new File(context.getCacheDir(), "debian-proot-rootfs.tar.xz");
        File rootfsDir = ProotManager.getRootfsDir(context);
        try {
            if (cancelled.get()) return new InstallResult(false, "Instalación cancelada.");

            listener.onProgress(2, "Verificando motor PRoot integrado...");
            File prootBin = ProotManager.getProotBinary(context);
            File loaderBin = ProotManager.getLoaderBinary(context);
            if (!prootBin.exists() || !prootBin.canExecute() || !loaderBin.exists() || !loaderBin.canExecute()) {
                return new InstallResult(false, "Motor PRoot no disponible o sin permisos de ejecución en "
                    + context.getApplicationInfo().nativeLibraryDir);
            }
            listener.onLog("PRoot verificado: " + prootBin.getAbsolutePath());

            InstallResult dlRes = downloadVerifiedRootfs(release, cacheTarXz, cancelled, listener);
            if (!dlRes.success) {
                return dlRes;
            }

            listener.onProgress(63, "Limpiando directorio destino PRoot...");
            TarExtractor.deleteRecursivelyNoFollow(rootfsDir);
            if (!rootfsDir.mkdirs()) {
                return new InstallResult(false, "No se pudo crear el directorio interno " + rootfsDir);
            }
            TarExtractor.setMode(rootfsDir, 0755);

            listener.onProgress(65, "Descomprimiendo Debian " + release + " (sin root)...");
            listener.onLog("Extrayendo rootfs en " + rootfsDir.getAbsolutePath() + " (puede tardar 1-2 min)...");
            final long totalBytes = Math.max(1L, cacheTarXz.length());
            TarExtractor.extractTarXz(cacheTarXz, rootfsDir, true, cancelled, (entries, bytesRead) -> {
                int pct = 65 + (int) ((bytesRead * 25L) / totalBytes);
                listener.onProgress(Math.min(90, pct),
                    "Extrayendo archivos sin root (" + entries + " entradas)...");
            });
            if (cancelled.get()) return new InstallResult(false, "Instalación cancelada.");
            listener.onLog("Extracción PRoot finalizada.");

            listener.onProgress(92, "Configurando DNS, APT y /proc virtual...");
            ProotManager.configureRootfs(rootfsDir);
            listener.onLog("Configuración post-instalación PRoot aplicada.");

            listener.onProgress(95, "Verificando compatibilidad seccomp de PRoot...");
            boolean disableSeccomp = false;
            if (!ProotManager.testProotRuntime(context, false)) {
                listener.onLog("Aviso: probando PRoot con PROOT_NO_SECCOMP=1...");
                if (ProotManager.testProotRuntime(context, true)) {
                    disableSeccomp = true;
                    listener.onLog("Compatibilidad PROOT_NO_SECCOMP=1 activada automáticamente.");
                } else {
                    listener.onLog("Aviso: PRoot /bin/true no devolvió 0 en la prueba rápida.");
                }
            } else {
                listener.onLog("Prueba PRoot (/bin/true) exitosa con seccomp habilitado.");
            }
            new TerminalSettings(context).setProotDisableSeccomp(disableSeccomp);

            listener.onProgress(97, "Generando script lanzador PRoot...");
            File script = ProotManager.installLauncherScript(context, disableSeccomp);
            listener.onLog("Lanzador generado: " + script.getAbsolutePath());

            listener.onProgress(100, "Debian " + release + " listo en modo Sin Root (PRoot).");
            return new InstallResult(true, "Debian " + release + " (PRoot) instalado en " + rootfsDir.getAbsolutePath());
        } catch (Exception e) {
            if (cancelled.get()) {
                return new InstallResult(false, "Instalación cancelada por el usuario.");
            }
            String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            listener.onLog("ERROR: " + msg);
            return new InstallResult(false, msg);
        } finally {
            if (cacheTarXz.exists()) {
                //noinspection ResultOfMethodCallIgnored
                cacheTarXz.delete();
            }
        }
    }

    private static InstallResult downloadVerifiedRootfs(
        String release,
        File cacheTarXz,
        AtomicBoolean cancelled,
        ProgressListener listener
    ) throws Exception {
        String arch = detectDebianArch();
        listener.onLog("Arquitectura detectada: " + arch + " | Debian: " + release);

        if (cancelled.get()) return new InstallResult(false, "Instalación cancelada.");

        listener.onProgress(6, "Consultando índice oficial LinuxContainers...");
        String indexText = fetchString(LXC_INDEX_URL, cancelled);
        String imagePath = parseLxcIndex(indexText, release, arch);
        if (imagePath == null) {
            return new InstallResult(false, "No se encontró imagen Debian " + release + " (" + arch + ") en el índice.");
        }
        String imageDirUrl = LXC_BASE_URL + imagePath;
        listener.onLog("Imagen seleccionada: " + imageDirUrl);

        if (cancelled.get()) return new InstallResult(false, "Instalación cancelada.");

        listener.onProgress(10, "Descargando SHA256SUMS...");
        String shaText = fetchString(imageDirUrl + "SHA256SUMS", cancelled);
        String expectedSha = parseSha256Sums(shaText, "rootfs.tar.xz");
        if (expectedSha == null) {
            return new InstallResult(false, "No se encontró hash SHA-256 para rootfs.tar.xz.");
        }
        listener.onLog("SHA-256 esperado: " + expectedSha.substring(0, 16) + "...");

        listener.onProgress(12, "Descargando rootfs.tar.xz...");
        String rootfsUrl = imageDirUrl + "rootfs.tar.xz";
        downloadFile(rootfsUrl, cacheTarXz, cancelled, (downloaded, total) -> {
            if (total > 0) {
                int pct = 12 + (int) ((downloaded * 46L) / total);
                String msg = String.format(Locale.US, "Descargando rootfs: %.1f / %.1f MB",
                    downloaded / (1024.0 * 1024.0), total / (1024.0 * 1024.0));
                listener.onProgress(Math.min(58, pct), msg);
            } else {
                String msg = String.format(Locale.US, "Descargando rootfs: %.1f MB",
                    downloaded / (1024.0 * 1024.0));
                listener.onProgress(35, msg);
            }
        });
        if (cancelled.get()) return new InstallResult(false, "Instalación cancelada.");
        listener.onLog("Descarga completada (" + (cacheTarXz.length() / (1024 * 1024)) + " MB)");

        listener.onProgress(60, "Verificando integridad SHA-256...");
        String actualSha = computeFileSha256(cacheTarXz, cancelled);
        if (cancelled.get()) return new InstallResult(false, "Instalación cancelada.");
        if (!expectedSha.equalsIgnoreCase(actualSha)) {
            return new InstallResult(false, "Error SHA-256: esperado " + expectedSha + ", obtenido " + actualSha);
        }
        listener.onLog("Verificación SHA-256 correcta.");
        return new InstallResult(true, "OK");
    }

    private static String buildPostInstallCommand(String chrootPath) {
        String qChroot = ChrootManager.shellQuote(chrootPath);
        return
            "CH=" + qChroot + "; " +
            "mkdir -p \"$CH/dev\" \"$CH/dev/pts\" \"$CH/proc\" \"$CH/sys\" \"$CH/tmp\" \"$CH/dev/shm\" \"$CH/sdcard\" \"$CH/external_sd\" \"$CH/etc/apt/apt.conf.d\" \"$CH/usr/sbin\" && " +
            "chmod 1777 \"$CH/tmp\" \"$CH/dev/shm\" && " +
            "rm -f \"$CH/etc/resolv.conf\" && " +
            "printf 'nameserver 1.1.1.1\\nnameserver 8.8.8.8\\n' > \"$CH/etc/resolv.conf\" && " +
            "if ! grep -qs 'localhost' \"$CH/etc/hosts\" 2>/dev/null; then printf '127.0.0.1 localhost\\n::1 localhost\\n' > \"$CH/etc/hosts\"; fi && " +
            "for grp in 'aid_inet:x:3003:root' 'aid_net_raw:x:3004:root' 'aid_admin:x:3005:root' 'aid_sdcard_rw:x:1015:root' 'aid_everybody:x:9997:root'; do " +
            "  gname=$(printf '%s' \"$grp\" | cut -d: -f1); " +
            "  grep -qs \"^${gname}:\" \"$CH/etc/group\" || printf '%s\\n' \"$grp\" >> \"$CH/etc/group\"; " +
            "done && " +
            "printf 'APT::Sandbox::User \"root\";\\n' > \"$CH/etc/apt/apt.conf.d/99android-chroot\" && " +
            "if [ ! -e \"$CH/usr/sbin/policy-rc.d\" ]; then printf '#!/bin/sh\\nexit 101\\n' > \"$CH/usr/sbin/policy-rc.d\" && chmod 755 \"$CH/usr/sbin/policy-rc.d\"; fi";
    }

    private static void extractTarXzToRoot(
        String suBin,
        File tarXzFile,
        String chrootPath,
        AtomicBoolean cancelled,
        ProgressListener listener
    ) throws IOException {
        String qChroot = ChrootManager.shellQuote(chrootPath);
        List<String> cmd = new ArrayList<>();
        cmd.add(suBin);
        cmd.add("-c");
        cmd.add("tar -xf - -C " + qChroot);

        Process proc = new ProcessBuilder(cmd).start();
        final ByteArrayOutputStream outBuf = new ByteArrayOutputStream();
        final ByteArrayOutputStream errBuf = new ByteArrayOutputStream();
        Thread tOut = drainAsync(proc.getInputStream(), outBuf);
        Thread tErr = drainAsync(proc.getErrorStream(), errBuf);

        long totalBytes = Math.max(1L, tarXzFile.length());
        try (CountingInputStream cis = new CountingInputStream(new BufferedInputStream(new FileInputStream(tarXzFile), 65536));
             XZInputStream xzIn = new XZInputStream(cis);
             OutputStream tarOut = proc.getOutputStream()) {
            byte[] buffer = new byte[65536];
            int n;
            long lastReportMs = 0L;
            while ((n = xzIn.read(buffer)) != -1) {
                if (cancelled.get()) {
                    proc.destroyForcibly();
                    throw new IOException("Cancelado durante la extracción.");
                }
                tarOut.write(buffer, 0, n);
                long now = System.currentTimeMillis();
                if (now - lastReportMs >= 400) {
                    lastReportMs = now;
                    int pct = 68 + (int) ((cis.bytesRead * 21L) / totalBytes);
                    listener.onProgress(Math.min(89, pct), "Extrayendo Debian rootfs...");
                }
            }
            tarOut.flush();
        } catch (IOException e) {
            proc.destroyForcibly();
            throw e;
        }

        int rc;
        try {
            rc = proc.waitFor();
            tOut.join(2000);
            tErr.join(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            proc.destroyForcibly();
            throw new IOException("Extracción interrumpida.");
        }

        if (rc != 0) {
            String err = new String(errBuf.toByteArray(), StandardCharsets.UTF_8).trim();
            throw new IOException("tar falló (exit " + rc + "): " + (err.isEmpty() ? "sin detalles" : err));
        }
    }

    private static Thread drainAsync(final InputStream is, final ByteArrayOutputStream dst) {
        Thread t = new Thread(() -> {
            byte[] buf = new byte[4096];
            int n;
            try {
                while ((n = is.read(buf)) != -1) {
                    dst.write(buf, 0, n);
                }
            } catch (IOException ignored) {
            }
        }, "tar-drain");
        t.setDaemon(true);
        t.start();
        return t;
    }

    private interface DownloadProgress {
        void onBytes(long downloaded, long total);
    }

    private static HttpURLConnection openFollowingRedirects(String urlStr, AtomicBoolean cancelled) throws IOException {
        URL current = new URL(urlStr);
        for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
            if (cancelled != null && cancelled.get()) {
                throw new IOException("Cancelado");
            }
            HttpURLConnection conn = (HttpURLConnection) current.openConnection();
            conn.setInstanceFollowRedirects(false);
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(30000);
            conn.setRequestProperty("User-Agent", "DebianTerm/1.0");
            int code = conn.getResponseCode();
            if (code == HttpURLConnection.HTTP_MOVED_PERM
                || code == HttpURLConnection.HTTP_MOVED_TEMP
                || code == HttpURLConnection.HTTP_SEE_OTHER
                || code == 307
                || code == 308) {
                String location = conn.getHeaderField("Location");
                conn.disconnect();
                if (location == null || location.isEmpty()) {
                    throw new IOException("Redirect " + code + " sin cabecera Location");
                }
                current = new URL(current, location);
                continue;
            }
            if (code < 200 || code >= 300) {
                conn.disconnect();
                throw new IOException("HTTP " + code + " al descargar " + current);
            }
            return conn;
        }
        throw new IOException("Demasiados redirects desde " + urlStr);
    }

    private static String fetchString(String urlStr, AtomicBoolean cancelled) throws IOException {
        HttpURLConnection conn = openFollowingRedirects(urlStr, cancelled);
        try (InputStream is = conn.getInputStream();
             ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) != -1) {
                if (cancelled != null && cancelled.get()) {
                    throw new IOException("Cancelado");
                }
                baos.write(buf, 0, n);
            }
            return new String(baos.toByteArray(), StandardCharsets.UTF_8);
        } finally {
            conn.disconnect();
        }
    }

    private static void downloadFile(String urlStr, File dst, AtomicBoolean cancelled, DownloadProgress progress) throws IOException {
        HttpURLConnection conn = openFollowingRedirects(urlStr, cancelled);
        long total = conn.getContentLengthLong();
        try (InputStream is = conn.getInputStream();
             FileOutputStream fos = new FileOutputStream(dst)) {
            byte[] buf = new byte[32768];
            long downloaded = 0L;
            long lastReportMs = 0L;
            int n;
            while ((n = is.read(buf)) != -1) {
                if (cancelled != null && cancelled.get()) {
                    throw new IOException("Cancelado");
                }
                fos.write(buf, 0, n);
                downloaded += n;
                long now = System.currentTimeMillis();
                if (now - lastReportMs >= 250) {
                    lastReportMs = now;
                    progress.onBytes(downloaded, total);
                }
            }
            fos.flush();
            progress.onBytes(downloaded, total);
        } finally {
            conn.disconnect();
        }
    }

    private static String computeFileSha256(File file, AtomicBoolean cancelled) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (InputStream is = new BufferedInputStream(new FileInputStream(file), 65536)) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = is.read(buf)) != -1) {
                if (cancelled != null && cancelled.get()) {
                    throw new IOException("Cancelado");
                }
                md.update(buf, 0, n);
            }
        }
        byte[] digest = md.digest();
        StringBuilder sb = new StringBuilder(64);
        for (byte b : digest) {
            sb.append(String.format(Locale.US, "%02x", b & 0xff));
        }
        return sb.toString();
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
}
