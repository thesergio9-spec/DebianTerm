package com.debianchroot.term;

import android.content.Context;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Manages the self-contained non-root Debian 12 PRoot environment inside app internal storage.
 */
public final class ProotManager {

    public static final String ROOTFS_DIR_NAME = "debian12-proot";
    public static final String TMP_DIR_NAME = "proot-tmp";
    public static final String LAUNCHER_REL_PATH = "bin/start-debian12-proot.sh";

    public static final class ProotStatus {
        public boolean binariesAvailable;
        public String prootBinaryPath = "";
        public String prootVersion = "";
        public boolean rootfsExists;
        public String debianPrettyName = "";
        public String rootfsSizeSummary = "";
        public boolean launcherScriptExists;
        public String rootfsPath = "";
    }

    private ProotManager() {}

    public static File getRootfsDir(Context context) {
        return new File(context.getFilesDir(), ROOTFS_DIR_NAME);
    }

    public static File getTmpDir(Context context) {
        return new File(context.getFilesDir(), TMP_DIR_NAME);
    }

    public static File getLauncherScript(Context context) {
        return new File(context.getFilesDir(), LAUNCHER_REL_PATH);
    }

    public static File getProotBinary(Context context) {
        String libDir = context.getApplicationInfo().nativeLibraryDir;
        return new File(libDir, "libproot.so");
    }

    public static File getLoaderBinary(Context context) {
        String libDir = context.getApplicationInfo().nativeLibraryDir;
        return new File(libDir, "libproot_loader.so");
    }

    public static File getLoader32Binary(Context context) {
        String libDir = context.getApplicationInfo().nativeLibraryDir;
        return new File(libDir, "libproot_loader32.so");
    }

    public static ProotStatus inspectStatus(Context context) {
        ProotStatus status = new ProotStatus();
        File rootfs = getRootfsDir(context);
        File script = getLauncherScript(context);
        File prootBin = getProotBinary(context);
        File loaderBin = getLoaderBinary(context);

        status.rootfsPath = rootfs.getAbsolutePath();
        status.prootBinaryPath = prootBin.getAbsolutePath();
        status.binariesAvailable = prootBin.exists() && prootBin.canExecute()
            && loaderBin.exists() && loaderBin.canExecute();
        status.launcherScriptExists = script.exists() && script.canExecute();

        if (status.binariesAvailable) {
            status.prootVersion = readProotVersion(prootBin);
        }

        File bash = new File(rootfs, "bin/bash");
        File debVer = new File(rootfs, "etc/debian_version");
        if (bash.exists() && debVer.exists()) {
            status.rootfsExists = true;
            status.debianPrettyName = readDebianName(rootfs);
            status.rootfsSizeSummary = computeSizeWithBudget(rootfs, 1500L);
        }
        return status;
    }

    private static String readProotVersion(File prootBin) {
        Process p = null;
        try {
            p = new ProcessBuilder(prootBin.getAbsolutePath(), "--version").start();
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            long deadline = System.currentTimeMillis() + 3000L;
            InputStream is = p.getInputStream();
            byte[] buf = new byte[1024];
            while (System.currentTimeMillis() < deadline) {
                while (is.available() > 0) {
                    int n = is.read(buf);
                    if (n == -1) break;
                    baos.write(buf, 0, n);
                }
                try {
                    p.exitValue();
                    break;
                } catch (IllegalThreadStateException notDone) {
                    Thread.sleep(25);
                }
            }
            while (is.available() > 0) {
                int n = is.read(buf);
                if (n == -1) break;
                baos.write(buf, 0, n);
            }
            String out = new String(baos.toByteArray(), StandardCharsets.UTF_8).trim();
            String rev = "";
            boolean hasSeccomp = out.toLowerCase(Locale.US).contains("seccomp_filter = yes");
            for (String line : out.split("\\r?\\n")) {
                String t = line.trim();
                if (t.contains("|__|")) {
                    int lastPipe = t.lastIndexOf('|');
                    if (lastPipe >= 0 && lastPipe + 1 < t.length()) {
                        rev = t.substring(lastPipe + 1).trim();
                    }
                }
            }
            if (!rev.isEmpty()) {
                return "PRoot " + rev + (hasSeccomp ? " (seccomp)" : "");
            }
            return hasSeccomp ? "PRoot integrado (seccomp)" : "PRoot integrado";
        } catch (Exception e) {
            return "PRoot";
        } finally {
            if (p != null) {
                p.destroy();
            }
        }
    }

    private static String readDebianName(File rootfs) {
        File osRel = new File(rootfs, "etc/os-release");
        if (osRel.isFile()) {
            String txt = readSmallFile(osRel, 4096);
            for (String line : txt.split("\\r?\\n")) {
                if (line.startsWith("PRETTY_NAME=")) {
                    String val = line.substring("PRETTY_NAME=".length()).trim();
                    if (val.startsWith("\"") && val.endsWith("\"") && val.length() >= 2) {
                        val = val.substring(1, val.length() - 1);
                    }
                    if (!val.isEmpty()) return val;
                }
            }
        }
        File debVer = new File(rootfs, "etc/debian_version");
        if (debVer.isFile()) {
            String ver = readSmallFile(debVer, 128).trim();
            if (!ver.isEmpty()) return "Debian GNU/Linux " + ver;
        }
        return "Debian 12 (PRoot)";
    }

    private static String readSmallFile(File f, int maxBytes) {
        try (FileInputStream fis = new FileInputStream(f)) {
            byte[] buf = new byte[maxBytes];
            int n = fis.read(buf);
            return n > 0 ? new String(buf, 0, n, StandardCharsets.UTF_8) : "";
        } catch (IOException e) {
            return "";
        }
    }

    private static String computeSizeWithBudget(File dir, long budgetMs) {
        long deadline = System.currentTimeMillis() + budgetMs;
        long[] total = new long[1];
        boolean[] aborted = new boolean[1];
        accumulateSize(dir, total, deadline, aborted);
        double mb = total[0] / (1024.0 * 1024.0);
        String prefix = aborted[0] ? "~" : "";
        if (mb >= 1024.0) {
            return String.format(Locale.US, "%s%.1f GB", prefix, mb / 1024.0);
        }
        return String.format(Locale.US, "%s%.0f MB", prefix, Math.max(1.0, mb));
    }

    private static void accumulateSize(File dir, long[] total, long deadline, boolean[] aborted) {
        if (aborted[0] || System.currentTimeMillis() > deadline) {
            aborted[0] = true;
            return;
        }
        if (TarExtractor.isSymlink(dir)) return;
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (TarExtractor.isSymlink(f)) continue;
            if (f.isDirectory()) {
                accumulateSize(f, total, deadline, aborted);
                if (aborted[0]) return;
            } else if (f.isFile()) {
                total[0] += f.length();
            }
        }
    }

    public static void configureRootfs(File rootfsDir) throws IOException {
        String[] dirs = {
            "tmp", "dev", "dev/shm", "proc", "sys", "sdcard", "root",
            ".l2s", "etc/apt/apt.conf.d", "etc/profile.d", "usr/sbin", "etc/debianterm-proc"
        };
        for (String rel : dirs) {
            File d = new File(rootfsDir, rel);
            if (!d.exists() && !d.mkdirs()) {
                throw new IOException("No se pudo crear " + d);
            }
        }
        TarExtractor.setMode(new File(rootfsDir, "tmp"), 01777);
        TarExtractor.setMode(new File(rootfsDir, "dev/shm"), 01777);

        // Replace dangling resolv.conf symlink if present
        File resolv = new File(rootfsDir, "etc/resolv.conf");
        if (resolv.exists() || TarExtractor.isSymlink(resolv)) {
            TarExtractor.deleteRecursivelyNoFollow(resolv);
        }
        writeUtf8File(resolv, "nameserver 1.1.1.1\nnameserver 8.8.8.8\n", 0644);

        File hosts = new File(rootfsDir, "etc/hosts");
        String existingHosts = hosts.isFile() ? readSmallFile(hosts, 4096) : "";
        if (!existingHosts.contains("localhost")) {
            writeUtf8File(hosts, "127.0.0.1 localhost\n::1 localhost\n", 0644);
        }

        File group = new File(rootfsDir, "etc/group");
        String existingGroup = group.isFile() ? readSmallFile(group, 65536) : "";
        String[] androidGroups = {
            "aid_inet:x:3003:root",
            "aid_net_raw:x:3004:root",
            "aid_sdcard_rw:x:1015:root",
            "aid_everybody:x:9997:root"
        };
        StringBuilder groupAppend = new StringBuilder();
        for (String line : androidGroups) {
            String gname = line.substring(0, line.indexOf(':') + 1);
            if (!existingGroup.contains("\n" + gname) && !existingGroup.startsWith(gname)) {
                groupAppend.append(line).append('\n');
            }
        }
        if (groupAppend.length() > 0) {
            if (!existingGroup.isEmpty() && !existingGroup.endsWith("\n")) {
                appendUtf8File(group, "\n" + groupAppend);
            } else {
                appendUtf8File(group, groupAppend.toString());
            }
        }

        File aptConf = new File(rootfsDir, "etc/apt/apt.conf.d/99android-proot");
        writeUtf8File(aptConf,
            "APT::Sandbox::User \"root\";\n" +
            "DPkg::Options {\n" +
            "  \"--force-confdef\";\n" +
            "  \"--force-confold\";\n" +
            "};\n",
            0644);

        File policyRc = new File(rootfsDir, "usr/sbin/policy-rc.d");
        writeUtf8File(policyRc, "#!/bin/sh\nexit 101\n", 0755);

        File profileScript = new File(rootfsDir, "etc/profile.d/debianterm.sh");
        writeUtf8File(profileScript,
            "export LANG=C.UTF-8\n" +
            "export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin\n" +
            "if [ -n \"$PS1\" ]; then\n" +
            "    PS1='\\u@debian12-proot:\\w# '\n" +
            "fi\n",
            0644);

        // Synthetic /proc files for Android 10-15 SELinux procfs restrictions
        File fakeProc = new File(rootfsDir, "etc/debianterm-proc");
        writeUtf8File(new File(fakeProc, "loadavg"), "0.50 0.45 0.40 1/256 100\n", 0444);
        writeUtf8File(new File(fakeProc, "stat"),
            "cpu  1000 0 1000 10000 0 0 0 0 0 0\n" +
            "cpu0 500 0 500 5000 0 0 0 0 0 0\n" +
            "intr 10000\n" +
            "ctxt 50000\n" +
            "btime 1700000000\n" +
            "processes 1000\n" +
            "procs_running 1\n" +
            "procs_blocked 0\n",
            0444);
        writeUtf8File(new File(fakeProc, "uptime"), "3600.00 3500.00\n", 0444);
        writeUtf8File(new File(fakeProc, "version"),
            "Linux version 6.1.0-debianterm (debian@android) #1 SMP PREEMPT\n", 0444);
        writeUtf8File(new File(fakeProc, "vmstat"),
            "nr_free_pages 100000\npgpgin 1000\npgpgout 1000\npswpin 0\npswpout 0\n", 0444);
        writeUtf8File(new File(fakeProc, "cap_last_cap"), "40\n", 0444);
    }

    public static String generateProotLauncherScript(
        String nativeLibDir,
        String filesDir,
        String rootfsPath,
        boolean disableSeccomp
    ) {
        String cleanLib = trimTrailingSlashes(nativeLibDir);
        String cleanFiles = trimTrailingSlashes(filesDir);
        String cleanRootfs = trimTrailingSlashes(rootfsPath);
        String tmpDir = cleanFiles + "/" + TMP_DIR_NAME;

        StringBuilder sb = new StringBuilder();
        sb.append("#!/system/bin/sh\n");
        sb.append("# Generated by DebianTerm - Non-root PRoot Debian 12 launcher\n");
        sb.append("unset LD_PRELOAD\n");
        sb.append("NATIVE_LIB_DIR=").append(ChrootManager.shellQuote(cleanLib)).append('\n');
        sb.append("ROOTFS=").append(ChrootManager.shellQuote(cleanRootfs)).append('\n');
        sb.append("PROOT_BIN=\"$NATIVE_LIB_DIR/libproot.so\"\n");
        sb.append("export PROOT_TMP_DIR=").append(ChrootManager.shellQuote(tmpDir)).append('\n');
        sb.append("export PROOT_LOADER=\"$NATIVE_LIB_DIR/libproot_loader.so\"\n");
        sb.append("if [ -x \"$NATIVE_LIB_DIR/libproot_loader32.so\" ]; then\n");
        sb.append("    export PROOT_LOADER_32=\"$NATIVE_LIB_DIR/libproot_loader32.so\"\n");
        sb.append("fi\n");
        sb.append("export PROOT_L2S_DIR=\"$ROOTFS/.l2s\"\n");
        sb.append("export PROOT_IGNORE_MISSING_BINDINGS=1\n");
        if (disableSeccomp) {
            sb.append("export PROOT_NO_SECCOMP=1\n");
        }
        sb.append("mkdir -p \"$PROOT_TMP_DIR\" \"$PROOT_L2S_DIR\" \"$ROOTFS/tmp\" 2>/dev/null || true\n\n");

        sb.append("set -- \"$PROOT_BIN\" \\\n");
        sb.append("  --link2symlink \\\n");
        sb.append("  --kill-on-exit \\\n");
        sb.append("  --root-id \\\n");
        sb.append("  --rootfs=\"$ROOTFS\" \\\n");
        sb.append("  --cwd=/root \\\n");
        sb.append("  --sysvipc \\\n");
        sb.append("  --bind=/dev \\\n");
        sb.append("  --bind=/dev/urandom:/dev/random \\\n");
        sb.append("  --bind=/proc \\\n");
        sb.append("  --bind=/proc/self/fd:/dev/fd \\\n");
        sb.append("  --bind=/proc/self/fd/0:/dev/stdin \\\n");
        sb.append("  --bind=/proc/self/fd/1:/dev/stdout \\\n");
        sb.append("  --bind=/proc/self/fd/2:/dev/stderr \\\n");
        sb.append("  --bind=/sys \\\n");
        sb.append("  --bind=\"$ROOTFS/tmp:/dev/shm\" \\\n");
        sb.append("  --bind=\"$ROOTFS/etc/debianterm-proc/loadavg:/proc/loadavg\" \\\n");
        sb.append("  --bind=\"$ROOTFS/etc/debianterm-proc/stat:/proc/stat\" \\\n");
        sb.append("  --bind=\"$ROOTFS/etc/debianterm-proc/uptime:/proc/uptime\" \\\n");
        sb.append("  --bind=\"$ROOTFS/etc/debianterm-proc/version:/proc/version\" \\\n");
        sb.append("  --bind=\"$ROOTFS/etc/debianterm-proc/vmstat:/proc/vmstat\" \\\n");
        sb.append("  --bind=\"$ROOTFS/etc/debianterm-proc/cap_last_cap:/proc/sys/kernel/cap_last_cap\"\n\n");

        sb.append("if [ -d /storage/emulated/0 ] && [ -r /storage/emulated/0 ]; then\n");
        sb.append("    set -- \"$@\" \"--bind=/storage/emulated/0:/sdcard\"\n");
        sb.append("fi\n\n");

        sb.append("CMD_ARGS=\"--login\"\n");
        sb.append("if [ \"$1\" = \"-c\" ] && [ -n \"$2\" ]; then\n");
        sb.append("    exec \"$@\" /usr/bin/env -i \\\n");
        sb.append("      HOME=/root \\\n");
        sb.append("      USER=root \\\n");
        sb.append("      LOGNAME=root \\\n");
        sb.append("      TERM=\"${TERM:-xterm-256color}\" \\\n");
        sb.append("      COLORTERM=truecolor \\\n");
        sb.append("      LANG=C.UTF-8 \\\n");
        sb.append("      TMPDIR=/tmp \\\n");
        sb.append("      PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin \\\n");
        sb.append("      /bin/bash -lc \"$2\"\n");
        sb.append("fi\n\n");

        sb.append("exec \"$@\" /usr/bin/env -i \\\n");
        sb.append("  HOME=/root \\\n");
        sb.append("  USER=root \\\n");
        sb.append("  LOGNAME=root \\\n");
        sb.append("  TERM=\"${TERM:-xterm-256color}\" \\\n");
        sb.append("  COLORTERM=truecolor \\\n");
        sb.append("  LANG=C.UTF-8 \\\n");
        sb.append("  TMPDIR=/tmp \\\n");
        sb.append("  PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin \\\n");
        sb.append("  /bin/bash --login\n");

        return sb.toString();
    }

    public static File installLauncherScript(Context context, boolean disableSeccomp) throws IOException {
        File script = getLauncherScript(context);
        File parent = script.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("No se pudo crear directorio para el script PRoot: " + parent);
        }
        String content = generateProotLauncherScript(
            context.getApplicationInfo().nativeLibraryDir,
            context.getFilesDir().getAbsolutePath(),
            getRootfsDir(context).getAbsolutePath(),
            disableSeccomp
        );
        writeUtf8File(script, content, 0755);
        return script;
    }

    public static boolean testProotRuntime(Context context, boolean disableSeccomp) {
        File prootBin = getProotBinary(context);
        File loaderBin = getLoaderBinary(context);
        File loader32Bin = getLoader32Binary(context);
        File rootfs = getRootfsDir(context);
        File tmpDir = getTmpDir(context);
        if (!prootBin.canExecute() || !loaderBin.canExecute() || !rootfs.isDirectory()) {
            return false;
        }
        //noinspection ResultOfMethodCallIgnored
        tmpDir.mkdirs();
        File l2sDir = new File(rootfs, ".l2s");
        //noinspection ResultOfMethodCallIgnored
        l2sDir.mkdirs();

        List<String> cmd = new ArrayList<>();
        cmd.add(prootBin.getAbsolutePath());
        cmd.add("--link2symlink");
        cmd.add("--kill-on-exit");
        cmd.add("--root-id");
        cmd.add("--rootfs=" + rootfs.getAbsolutePath());
        cmd.add("--cwd=/");
        cmd.add("/bin/true");

        ProcessBuilder pb = new ProcessBuilder(cmd);
        java.util.Map<String, String> env = pb.environment();
        env.remove("LD_PRELOAD");
        env.put("PROOT_TMP_DIR", tmpDir.getAbsolutePath());
        env.put("PROOT_LOADER", loaderBin.getAbsolutePath());
        if (loader32Bin.canExecute()) {
            env.put("PROOT_LOADER_32", loader32Bin.getAbsolutePath());
        }
        env.put("PROOT_L2S_DIR", l2sDir.getAbsolutePath());
        env.put("PROOT_IGNORE_MISSING_BINDINGS", "1");
        if (disableSeccomp) {
            env.put("PROOT_NO_SECCOMP", "1");
        } else {
            env.remove("PROOT_NO_SECCOMP");
        }

        Process proc = null;
        try {
            proc = pb.start();
            long deadline = System.currentTimeMillis() + 8000L;
            while (System.currentTimeMillis() < deadline) {
                try {
                    return proc.exitValue() == 0;
                } catch (IllegalThreadStateException notDone) {
                    Thread.sleep(40);
                }
            }
            return false;
        } catch (Exception e) {
            return false;
        } finally {
            if (proc != null) {
                proc.destroyForcibly();
            }
        }
    }

    public static boolean uninstallRootfs(Context context) {
        boolean ok = TarExtractor.deleteRecursivelyNoFollow(getRootfsDir(context));
        TarExtractor.deleteRecursivelyNoFollow(getTmpDir(context));
        TarExtractor.deleteRecursivelyNoFollow(getLauncherScript(context));
        return ok;
    }

    private static void writeUtf8File(File target, String content, int mode) throws IOException {
        try (FileOutputStream fos = new FileOutputStream(target)) {
            fos.write(content.getBytes(StandardCharsets.UTF_8));
        }
        TarExtractor.setMode(target, mode);
    }

    private static void appendUtf8File(File target, String content) throws IOException {
        try (FileOutputStream fos = new FileOutputStream(target, true)) {
            fos.write(content.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static String trimTrailingSlashes(String raw) {
        if (raw == null) return "";
        String t = raw.trim();
        while (t.length() > 1 && t.endsWith("/")) {
            t = t.substring(0, t.length() - 1);
        }
        return t;
    }
}
