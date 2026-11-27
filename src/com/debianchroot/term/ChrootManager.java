package com.debianchroot.term;

import android.os.Build;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Manages root execution, chroot inspection, entry script generation, and unmounting.
 */
public final class ChrootManager {

    private static final String[] SU_CANDIDATES = {
        "/debug_ramdisk/su",
        "/system/bin/su",
        "/system/xbin/su",
        "/sbin/su",
        "/data/adb/ksu/bin/su",
        "/data/adb/ap/bin/su"
    };

    public static final long ROOT_STATUS_CACHE_TTL_MS = 60_000L;

    private static final Object sSuCacheLock = new Object();
    private static volatile String sCachedWorkingSuPath = null;
    private static volatile String sCachedSuVersion = "";
    private static volatile ChrootStatus sCachedStatus = null;
    private static volatile String sCachedStatusKey = null;
    private static volatile long sCachedStatusAtMs = 0L;

    public static final class ExecResult {
        public final int exitCode;
        public final String stdout;
        public final String stderr;

        public ExecResult(int exitCode, String stdout, String stderr) {
            this.exitCode = exitCode;
            this.stdout = stdout != null ? stdout : "";
            this.stderr = stderr != null ? stderr : "";
        }

        public boolean isOk() {
            return exitCode == 0;
        }
    }

    public static final class ChrootStatus {
        public boolean hasRoot;
        public String suPath = "su";
        public String suVersion = "";
        public boolean rootfsExists;
        public String debianPrettyName = "";
        public boolean entryScriptExists;
        public final List<String> activeMounts = new ArrayList<>();
        public String rootfsSizeSummary = "";
        public boolean rootXfceInstalled;
        public boolean rootLxdeInstalled;
        public boolean rootVncServerInstalled;
        public boolean rootDesktopProbed;
        public String phantomMonitorFlag = "";
        public String phantomDeviceConfigMax = "";
        public String phantomActivityConstants = "";
        public boolean phantomProbed;

        public ChrootStatus copy() {
            ChrootStatus c = new ChrootStatus();
            c.hasRoot = this.hasRoot;
            c.suPath = this.suPath;
            c.suVersion = this.suVersion;
            c.rootfsExists = this.rootfsExists;
            c.debianPrettyName = this.debianPrettyName;
            c.entryScriptExists = this.entryScriptExists;
            c.activeMounts.addAll(this.activeMounts);
            c.rootfsSizeSummary = this.rootfsSizeSummary;
            c.rootXfceInstalled = this.rootXfceInstalled;
            c.rootLxdeInstalled = this.rootLxdeInstalled;
            c.rootVncServerInstalled = this.rootVncServerInstalled;
            c.rootDesktopProbed = this.rootDesktopProbed;
            c.phantomMonitorFlag = this.phantomMonitorFlag;
            c.phantomDeviceConfigMax = this.phantomDeviceConfigMax;
            c.phantomActivityConstants = this.phantomActivityConstants;
            c.phantomProbed = this.phantomProbed;
            return c;
        }
    }

    private ChrootManager() {}

    public static void invalidateStatusCache() {
        synchronized (sSuCacheLock) {
            sCachedStatus = null;
            sCachedStatusKey = null;
            sCachedStatusAtMs = 0L;
        }
    }

    public static void clearSuCache() {
        synchronized (sSuCacheLock) {
            sCachedWorkingSuPath = null;
            sCachedSuVersion = "";
            sCachedStatus = null;
            sCachedStatusKey = null;
            sCachedStatusAtMs = 0L;
        }
    }

    private static List<String> buildSuCandidateList(String preferredFirst) {
        return buildSuCandidateList(preferredFirst, System.getenv("PATH"));
    }

    public static List<String> buildSuCandidateList(String preferredFirst, String pathEnv) {
        List<String> list = new ArrayList<>();
        Set<String> seenRaw = new HashSet<>();
        Set<String> seenCanonical = new HashSet<>();

        if (preferredFirst != null && !preferredFirst.isEmpty()) {
            addCandidateIfNew(preferredFirst, list, seenRaw, seenCanonical);
        }
        for (String c : SU_CANDIDATES) {
            addCandidateIfNew(c, list, seenRaw, seenCanonical);
        }

        String fullPath = "/debug_ramdisk:/sbin:/system/bin:/system/xbin"
            + (pathEnv != null && !pathEnv.isEmpty() ? ":" + pathEnv : "");
        boolean foundAnyOnPath = false;
        for (String dir : fullPath.split(":")) {
            if (dir == null || dir.trim().isEmpty()) continue;
            File f = new File(dir.trim(), "su");
            if (f.exists()) {
                foundAnyOnPath = true;
                addCandidateIfNew(f.getAbsolutePath(), list, seenRaw, seenCanonical);
            }
        }
        if (!foundAnyOnPath && seenCanonical.isEmpty()) {
            addCandidateIfNew("su", list, seenRaw, seenCanonical);
        }
        return list;
    }

    private static void addCandidateIfNew(
        String candidate,
        List<String> list,
        Set<String> seenRaw,
        Set<String> seenCanonical
    ) {
        if (candidate == null || candidate.isEmpty() || !seenRaw.add(candidate)) {
            return;
        }
        if (candidate.startsWith("/")) {
            File f = new File(candidate);
            if (f.exists()) {
                try {
                    String canon = f.getCanonicalPath();
                    if (!seenCanonical.add(canon)) {
                        return;
                    }
                } catch (IOException ignored) {
                }
            }
        }
        list.add(candidate);
    }

    public static String detectSuPath() {
        for (String candidate : SU_CANDIDATES) {
            File f = new File(candidate);
            if (f.exists() && f.canExecute()) {
                return candidate;
            }
        }
        return "su";
    }

    public static String resolveWorkingSuPath() {
        String cached = sCachedWorkingSuPath;
        if (cached != null && !cached.isEmpty()) {
            return cached;
        }
        for (String candidate : buildSuCandidateList(null)) {
            if (testSuBinary(candidate)) {
                sCachedWorkingSuPath = candidate;
                return candidate;
            }
        }
        return detectSuPath();
    }

    private static boolean testSuBinary(String suBin) {
        ExecResult res = execWithBinary(suBin, false, "id -u", 6);
        return res.exitCode == 0 && res.stdout.trim().equals("0");
    }

    public static ExecResult execSu(String cmd, int timeoutSec) {
        return execSu(cmd, timeoutSec, false);
    }

    public static ExecResult execSu(String cmd, int timeoutSec, boolean mountMaster) {
        String suBin = resolveWorkingSuPath();
        ExecResult res = execWithBinary(suBin, mountMaster, cmd, timeoutSec);
        if (res.exitCode == -1 && sCachedWorkingSuPath != null) {
            sCachedWorkingSuPath = null;
            String retryBin = resolveWorkingSuPath();
            if (!retryBin.equals(suBin)) {
                return execWithBinary(retryBin, mountMaster, cmd, timeoutSec);
            }
        }
        return res;
    }

    private static ProcessBuilder createProcessBuilder(List<String> args) {
        ProcessBuilder pb = new ProcessBuilder(args);
        java.util.Map<String, String> env = pb.environment();
        String currentPath = env.get("PATH");
        String extendedPath = "/debug_ramdisk:/sbin:/system/bin:/system/xbin" + (currentPath != null ? ":" + currentPath : "");
        env.put("PATH", extendedPath);
        return pb;
    }

    public static ExecResult execWithBinary(String suBin, boolean mountMaster, String cmd, int timeoutSec) {
        List<String> args = new ArrayList<>();
        args.add(suBin);
        if (mountMaster) {
            args.add("-mm");
        }
        args.add("-c");
        args.add(cmd);

        Process process = null;
        try {
            process = createProcessBuilder(args).start();
            ExecResult res = waitAndCollect(process, null, timeoutSec);
            if (mountMaster && !res.isOk()) {
                // Fallback for su implementations where -mm returns non-zero (e.g. KernelSU / APatch)
                return execWithBinary(suBin, false, cmd, timeoutSec);
            }
            return res;
        } catch (IOException e) {
            if (mountMaster) {
                // Fallback for su implementations without -mm (e.g. KernelSU / APatch)
                return execWithBinary(suBin, false, cmd, timeoutSec);
            }
            return new ExecResult(-1, "", e.getMessage());
        }
    }

    public static ExecResult execSuWithStdin(String suBin, String cmd, byte[] stdinBytes, int timeoutSec) {
        List<String> args = new ArrayList<>();
        args.add(suBin);
        args.add("-c");
        args.add(cmd);

        try {
            Process process = createProcessBuilder(args).start();
            return waitAndCollect(process, stdinBytes, timeoutSec);
        } catch (IOException e) {
            return new ExecResult(-1, "", e.getMessage());
        }
    }

    private static ExecResult waitAndCollect(final Process process, final byte[] stdinBytes, int timeoutSec) {
        final ByteArrayOutputStream outBuf = new ByteArrayOutputStream();
        final ByteArrayOutputStream errBuf = new ByteArrayOutputStream();

        Thread outThread = startDrainThread(process.getInputStream(), outBuf);
        Thread errThread = startDrainThread(process.getErrorStream(), errBuf);

        if (stdinBytes != null) {
            try (OutputStream os = process.getOutputStream()) {
                os.write(stdinBytes);
                os.flush();
            } catch (IOException ignored) {
            }
        } else {
            try {
                process.getOutputStream().close();
            } catch (IOException ignored) {
            }
        }

        final AtomicBoolean timedOut = new AtomicBoolean(false);
        Thread watchdog = null;
        if (timeoutSec > 0) {
            final long deadlineMs = timeoutSec * 1000L;
            watchdog = new Thread(() -> {
                try {
                    Thread.sleep(deadlineMs);
                    timedOut.set(true);
                    process.destroy();
                    Thread.sleep(250);
                    process.destroyForcibly();
                } catch (InterruptedException ignored) {
                }
            }, "su-watchdog");
            watchdog.setDaemon(true);
            watchdog.start();
        }

        int exitCode = -1;
        try {
            exitCode = process.waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        } finally {
            if (watchdog != null) {
                watchdog.interrupt();
            }
        }

        try {
            outThread.join(1500);
            errThread.join(1500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        String stdout = new String(outBuf.toByteArray(), StandardCharsets.UTF_8);
        String stderr = new String(errBuf.toByteArray(), StandardCharsets.UTF_8);
        if (timedOut.get()) {
            exitCode = 124;
            if (!stderr.isEmpty()) stderr += "\n";
            stderr += "Timeout after " + timeoutSec + "s";
        }
        return new ExecResult(exitCode, stdout, stderr);
    }

    private static Thread startDrainThread(final InputStream is, final ByteArrayOutputStream dst) {
        Thread t = new Thread(() -> {
            byte[] buf = new byte[4096];
            int n;
            try {
                while ((n = is.read(buf)) != -1) {
                    dst.write(buf, 0, n);
                }
            } catch (IOException ignored) {
            }
        }, "su-drain");
        t.setDaemon(true);
        t.start();
        return t;
    }

    public static String buildUnifiedInspectScript(
        String suCandidate,
        String chrootPath,
        String scriptPath,
        boolean includePhantom
    ) {
        String cleanChroot = normalizeChrootPath(chrootPath);
        String cleanScript = (scriptPath == null || scriptPath.trim().isEmpty())
            ? TerminalSettings.DEFAULT_ENTRY_SCRIPT_PATH
            : scriptPath.trim();
        String qCand = shellQuote(suCandidate != null && !suCandidate.isEmpty() ? suCandidate : "su");
        String qChroot = shellQuote(cleanChroot);
        String qScript = shellQuote(cleanScript);

        StringBuilder sb = new StringBuilder();
        sb.append("if [ \"$(id -u)\" != '0' ]; then exit 13; fi\n");
        sb.append("echo '__DT_ROOT_OK__'\n");
        sb.append("_dt_sv=$(").append(qCand).append(" -v 2>/dev/null || ")
            .append(qCand).append(" --version 2>/dev/null || true)\n");
        sb.append("printf 'SU_VER=%s\\n' \"$(printf '%s\\n' \"$_dt_sv\" | head -n 1)\"\n");
        sb.append("C=").append(qChroot).append('\n');
        sb.append("S=").append(qScript).append('\n');
        sb.append("if [ -x \"$C/bin/bash\" ] && [ -f \"$C/etc/debian_version\" ]; then\n");
        sb.append("  echo 'ROOTFS=1'\n");
        sb.append("  if [ -f \"$C/etc/os-release\" ]; then\n");
        sb.append("    sed -n 's/^PRETTY_NAME=\"\\(.*\\)\"/PRETTY=\\1/p' \"$C/etc/os-release\" | head -n 1\n");
        sb.append("  fi\n");
        sb.append("  printf 'VER=%s\\n' \"$(head -n 1 \"$C/etc/debian_version\" 2>/dev/null)\"\n");
        sb.append("  [ -x \"$C/usr/bin/startxfce4\" ] && echo 'DESK_XFCE=1'\n");
        sb.append("  [ -x \"$C/usr/bin/startlxde\" ] && echo 'DESK_LXDE=1'\n");
        sb.append("  if [ -x \"$C/usr/bin/Xtigervnc\" ] || [ -x \"$C/usr/bin/Xvnc\" ]; then echo 'DESK_VNC=1'; fi\n");
        sb.append("else\n");
        sb.append("  echo 'ROOTFS=0'\n");
        sb.append("fi\n");
        sb.append("if [ -x \"$S\" ]; then echo 'SCRIPT=1'; else echo 'SCRIPT=0'; fi\n");
        sb.append("if [ -d \"$C\" ] && [ \"$C\" != '/' ] && [ \"$C\" != '/data' ]; then\n");
        sb.append("  if command -v timeout >/dev/null 2>&1; then\n");
        sb.append("    timeout 2 du -sh \"$C\" 2>/dev/null | awk '{print \"SIZE=\" $1}' || true\n");
        sb.append("  else\n");
        sb.append("    du -sh \"$C\" 2>/dev/null | awk '{print \"SIZE=\" $1}' || true\n");
        sb.append("  fi\n");
        sb.append("fi\n");
        sb.append("{ grep -F \"$C\" /proc/mounts 2>/dev/null | awk '{print $2}'; ")
            .append("grep -F \"$C\" /proc/1/mountinfo 2>/dev/null | awk '{print $5}'; } | sort -u | ")
            .append("while IFS= read -r _m; do [ -n \"$_m\" ] && printf 'MOUNT=%s\\n' \"$_m\"; done\n");
        if (includePhantom) {
            sb.append("echo '__DT_PHANTOM_BEGIN__'\n");
            sb.append("settings get global settings_enable_monitor_phantom_procs 2>/dev/null || true\n");
            sb.append("echo '__DT_PHANTOM_SEP__'\n");
            sb.append("/system/bin/device_config get activity_manager max_phantom_processes 2>/dev/null || ")
                .append("device_config get activity_manager max_phantom_processes 2>/dev/null || true\n");
            sb.append("echo '__DT_PHANTOM_SEP__'\n");
            sb.append("settings get global activity_manager_constants 2>/dev/null || true\n");
            sb.append("echo '__DT_PHANTOM_END__'\n");
        }
        return sb.toString();
    }

    public static boolean parseUnifiedInspectOutput(String stdout, String suCandidate, ChrootStatus status) {
        if (stdout == null || status == null || !stdout.contains("__DT_ROOT_OK__")) {
            return false;
        }
        String mainBody = stdout;
        int pBegin = stdout.indexOf("__DT_PHANTOM_BEGIN__");
        if (pBegin >= 0) {
            mainBody = stdout.substring(0, pBegin);
            int pEnd = stdout.indexOf("__DT_PHANTOM_END__", pBegin);
            int blockStart = pBegin + "__DT_PHANTOM_BEGIN__".length();
            String pBlock = (pEnd >= blockStart)
                ? stdout.substring(blockStart, pEnd)
                : stdout.substring(blockStart);
            String[] parts = pBlock.split("__DT_PHANTOM_SEP__", -1);
            status.phantomMonitorFlag = parts.length > 0 ? parts[0].trim() : "";
            status.phantomDeviceConfigMax = parts.length > 1 ? parts[1].trim() : "";
            status.phantomActivityConstants = parts.length > 2 ? parts[2].trim() : "";
            status.phantomProbed = true;
        }

        status.hasRoot = true;
        status.suPath = (suCandidate != null && !suCandidate.isEmpty()) ? suCandidate : "su";
        status.rootDesktopProbed = true;

        String pretty = "";
        String ver = "";
        for (String line : mainBody.split("\\r?\\n")) {
            if (line.startsWith("SU_VER=")) {
                status.suVersion = line.substring("SU_VER=".length()).trim();
            } else if (line.equals("ROOTFS=1")) {
                status.rootfsExists = true;
            } else if (line.equals("SCRIPT=1")) {
                status.entryScriptExists = true;
            } else if (line.startsWith("PRETTY=")) {
                pretty = line.substring("PRETTY=".length()).trim();
            } else if (line.startsWith("VER=")) {
                ver = line.substring("VER=".length()).trim();
            } else if (line.startsWith("SIZE=")) {
                status.rootfsSizeSummary = line.substring("SIZE=".length()).trim();
            } else if (line.equals("DESK_XFCE=1")) {
                status.rootXfceInstalled = true;
            } else if (line.equals("DESK_LXDE=1")) {
                status.rootLxdeInstalled = true;
            } else if (line.equals("DESK_VNC=1")) {
                status.rootVncServerInstalled = true;
            } else if (line.startsWith("MOUNT=")) {
                String m = line.substring("MOUNT=".length()).trim();
                if (!m.isEmpty() && !status.activeMounts.contains(m)) {
                    status.activeMounts.add(m);
                }
            }
        }
        if (!pretty.isEmpty()) {
            status.debianPrettyName = pretty;
        } else if (!ver.isEmpty()) {
            status.debianPrettyName = "Debian " + ver;
        } else if (status.rootfsExists) {
            status.debianPrettyName = "Debian Chroot";
        }
        return true;
    }

    public static ChrootStatus inspectStatus(String chrootPath, String scriptPath) {
        return inspectStatusCached(chrootPath, scriptPath, true);
    }

    /**
     * Runs at most ONE su invocation (root check, chroot probe, mount scan, desktop apps, and
     * Phantom Process settings) and caches the result briefly to avoid repeated Magisk toasts.
     */
    public static ChrootStatus inspectStatusCached(String chrootPath, String scriptPath, boolean forceRefresh) {
        String cleanChroot = normalizeChrootPath(chrootPath);
        String cleanScript = (scriptPath == null || scriptPath.trim().isEmpty())
            ? TerminalSettings.DEFAULT_ENTRY_SCRIPT_PATH
            : scriptPath.trim();
        String key = cleanChroot + "|" + cleanScript;

        synchronized (sSuCacheLock) {
            long now = System.currentTimeMillis();
            if (!forceRefresh && sCachedStatus != null && key.equals(sCachedStatusKey)
                    && (now - sCachedStatusAtMs) < ROOT_STATUS_CACHE_TTL_MS) {
                return sCachedStatus.copy();
            }

            boolean includePhantom = Build.VERSION.SDK_INT >= PhantomProcessManager.MIN_AFFECTED_API;
            for (String candidate : buildSuCandidateList(sCachedWorkingSuPath)) {
                String script = buildUnifiedInspectScript(candidate, cleanChroot, cleanScript, includePhantom);
                ExecResult res = execWithBinary(candidate, false, script, 12);
                ChrootStatus probed = new ChrootStatus();
                if (res.exitCode == 0 && parseUnifiedInspectOutput(res.stdout, candidate, probed)) {
                    sCachedWorkingSuPath = candidate;
                    sCachedSuVersion = probed.suVersion;
                    sCachedStatus = probed;
                    sCachedStatusKey = key;
                    sCachedStatusAtMs = System.currentTimeMillis();
                    return probed.copy();
                }
            }

            sCachedWorkingSuPath = null;
            sCachedSuVersion = "";
            ChrootStatus noRoot = new ChrootStatus();
            noRoot.suPath = detectSuPath();
            noRoot.hasRoot = false;
            sCachedStatus = noRoot;
            sCachedStatusKey = key;
            sCachedStatusAtMs = System.currentTimeMillis();
            return noRoot.copy();
        }
    }

    private static String normalizeChrootPath(String chrootPath) {
        String cleanPath = (chrootPath == null || chrootPath.trim().isEmpty())
            ? TerminalSettings.DEFAULT_CHROOT_PATH
            : chrootPath.trim();
        while (cleanPath.length() > 1 && cleanPath.endsWith("/")) {
            cleanPath = cleanPath.substring(0, cleanPath.length() - 1);
        }
        return cleanPath;
    }

    /**
     * Generates a safe POSIX sh snippet that mounts missing chroot pseudo-filesystems
     * without touching already-mounted targets or modifying any existing launcher scripts.
     */
    public static String buildEnsureRootMountsSnippet(String chrootPath) {
        String cleanPath = normalizeChrootPath(chrootPath);
        String qChroot = shellQuote(cleanPath);
        return "_DT_CHROOT=" + qChroot + "\n" +
            "_dt_is_mnt() {\n" +
            "    grep -qs \" $1 \" /proc/mounts && return 0\n" +
            "    [ -r /proc/1/mountinfo ] && grep -qs \" $1 \" /proc/1/mountinfo && return 0\n" +
            "    return 1\n" +
            "}\n" +
            "mkdir -p \"$_DT_CHROOT/dev\" \"$_DT_CHROOT/dev/pts\" \"$_DT_CHROOT/proc\" \"$_DT_CHROOT/sys\" \"$_DT_CHROOT/tmp\" \"$_DT_CHROOT/dev/shm\" \"$_DT_CHROOT/sdcard\" 2>/dev/null || true\n" +
            "_dt_is_mnt \"$_DT_CHROOT/dev\" || mount -o bind /dev \"$_DT_CHROOT/dev\" >/dev/null 2>&1 || true\n" +
            "_dt_is_mnt \"$_DT_CHROOT/dev/pts\" || mount -t devpts -o gid=5,mode=620 devpts \"$_DT_CHROOT/dev/pts\" >/dev/null 2>&1 || true\n" +
            "_dt_is_mnt \"$_DT_CHROOT/proc\" || mount -t proc proc \"$_DT_CHROOT/proc\" >/dev/null 2>&1 || true\n" +
            "_dt_is_mnt \"$_DT_CHROOT/sys\" || mount -t sysfs sysfs \"$_DT_CHROOT/sys\" >/dev/null 2>&1 || true\n" +
            "_dt_is_mnt \"$_DT_CHROOT/tmp\" || mount -t tmpfs -o mode=1777 tmpfs \"$_DT_CHROOT/tmp\" >/dev/null 2>&1 || true\n" +
            "_dt_is_mnt \"$_DT_CHROOT/dev/shm\" || mount -t tmpfs -o mode=1777 tmpfs \"$_DT_CHROOT/dev/shm\" >/dev/null 2>&1 || true\n" +
            "if [ -d /storage/emulated/0 ] && ! _dt_is_mnt \"$_DT_CHROOT/sdcard\"; then\n" +
            "    mount -o bind /storage/emulated/0 \"$_DT_CHROOT/sdcard\" >/dev/null 2>&1 || true\n" +
            "fi\n";
    }

    /**
     * Generates a root shell snippet that runs an inner command inside a Debian chroot.
     * Uses the entry script only if it explicitly implements the {@code exec)} subcommand;
     * otherwise falls back to direct chroot with missing mounts prepared first.
     */
    public static String buildRootExecCommand(String chrootPath, String scriptPath, String innerDebianCmd) {
        String cleanPath = normalizeChrootPath(chrootPath);
        String qChroot = shellQuote(cleanPath);
        String qScript = shellQuote(scriptPath != null ? scriptPath : "");
        String qInner = shellQuote(innerDebianCmd != null ? innerDebianCmd : "");
        return "if [ -x " + qScript + " ] && grep -Eq '^[[:space:]]*exec\\)' " + qScript + " 2>/dev/null; then\n" +
            "    " + qScript + " exec " + qInner + "\n" +
            "elif [ -x " + qChroot + "/bin/bash ]; then\n" +
            buildEnsureRootMountsSnippet(cleanPath) +
            "    env -i HOME=/root USER=root LOGNAME=root TERM=\"${TERM:-xterm-256color}\" COLORTERM=truecolor LANG=C.UTF-8 TMPDIR=/tmp \\\n" +
            "        PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin \\\n" +
            "        chroot " + qChroot + " /bin/bash -lc " + qInner + "\n" +
            "else\n" +
            "    echo " + shellQuote("Chroot Debian no encontrado en " + cleanPath) + "\n" +
            "    exit 1\n" +
            "fi\n";
    }

    public static String generateEntryScript(String chrootPath) {
        String cleanPath = normalizeChrootPath(chrootPath);
        String quotedChroot = shellQuote(cleanPath);
        String sessionDir = "/data/local/debian".equals(cleanPath)
            ? "/dev/chroot_sessions"
            : "/dev/chroot_sessions/" + cleanPath.replaceAll("[^A-Za-z0-9._-]", "_");
        String quotedSessionDir = shellQuote(sessionDir);

        return "#!/system/bin/sh\n" +
            "# Generated by DebianTerm - Safe Debian Chroot launcher\n" +
            "CHROOT=" + quotedChroot + "\n" +
            "SESSION_DIR=" + quotedSessionDir + "\n" +
            "CLEANED_UP=0\n" +
            "\n" +
            "if [ ! -d \"$CHROOT\" ]; then\n" +
            "    echo \"Error: chroot directory not found: $CHROOT\" >&2\n" +
            "    exit 1\n" +
            "fi\n" +
            "\n" +
            "has_timeout=0\n" +
            "if command -v timeout >/dev/null 2>&1; then\n" +
            "    has_timeout=1\n" +
            "fi\n" +
            "\n" +
            "run_mount() {\n" +
            "    if [ \"$has_timeout\" -eq 1 ]; then\n" +
            "        timeout 5 mount \"$@\"\n" +
            "    else\n" +
            "        mount \"$@\"\n" +
            "    fi\n" +
            "}\n" +
            "\n" +
            "is_mounted() {\n" +
            "    local target=\"$1\"\n" +
            "    grep -qs \" $target \" /proc/mounts && return 0\n" +
            "    if [ -r /proc/1/mountinfo ]; then\n" +
            "        grep -qs \" $target \" /proc/1/mountinfo && return 0\n" +
            "    fi\n" +
            "    return 1\n" +
            "}\n" +
            "\n" +
            "mount_chroot() {\n" +
            "    mkdir -p \"$CHROOT/dev\" \"$CHROOT/dev/pts\" \"$CHROOT/proc\" \"$CHROOT/sys\" \"$CHROOT/tmp\" \"$CHROOT/dev/shm\" \"$CHROOT/sdcard\" \"$CHROOT/external_sd\" 2>/dev/null\n" +
            "\n" +
            "    is_mounted \"$CHROOT/dev\" || run_mount -o bind /dev \"$CHROOT/dev\" >/dev/null 2>&1 || true\n" +
            "    is_mounted \"$CHROOT/dev/pts\" || run_mount -t devpts -o gid=5,mode=620 devpts \"$CHROOT/dev/pts\" >/dev/null 2>&1 || true\n" +
            "    is_mounted \"$CHROOT/proc\" || run_mount -t proc proc \"$CHROOT/proc\" >/dev/null 2>&1 || true\n" +
            "    is_mounted \"$CHROOT/sys\" || run_mount -t sysfs sysfs \"$CHROOT/sys\" >/dev/null 2>&1 || true\n" +
            "    is_mounted \"$CHROOT/tmp\" || run_mount -t tmpfs -o mode=1777 tmpfs \"$CHROOT/tmp\" >/dev/null 2>&1 || true\n" +
            "    is_mounted \"$CHROOT/dev/shm\" || run_mount -t tmpfs -o mode=1777 tmpfs \"$CHROOT/dev/shm\" >/dev/null 2>&1 || true\n" +
            "\n" +
            "    if [ -d /storage/emulated/0 ] && ! is_mounted \"$CHROOT/sdcard\"; then\n" +
            "        run_mount -o bind /storage/emulated/0 \"$CHROOT/sdcard\" >/dev/null 2>&1 || true\n" +
            "    fi\n" +
            "\n" +
            "    local ext_sd=\"\"\n" +
            "    if [ \"$has_timeout\" -eq 1 ]; then\n" +
            "        ext_sd=$(timeout 3 sh -c 'for d in /storage/????-????; do [ -d \"$d\" ] && echo \"$d\" && break; done' 2>/dev/null)\n" +
            "    else\n" +
            "        for d in /storage/????-????; do\n" +
            "            [ -d \"$d\" ] && ext_sd=\"$d\" && break\n" +
            "        done\n" +
            "    fi\n" +
            "    if [ -n \"$ext_sd\" ] && ! is_mounted \"$CHROOT/external_sd\"; then\n" +
            "        run_mount -o bind \"$ext_sd\" \"$CHROOT/external_sd\" >/dev/null 2>&1 || true\n" +
            "    fi\n" +
            "}\n" +
            "\n" +
            "umount_chroot() {\n" +
            "    for sub in external_sd sdcard dev/shm dev/pts dev proc sys tmp; do\n" +
            "        local tgt=\"$CHROOT/$sub\"\n" +
            "        if is_mounted \"$tgt\"; then\n" +
            "            umount \"$tgt\" 2>/dev/null || umount -l \"$tgt\" 2>/dev/null || true\n" +
            "        fi\n" +
            "    done\n" +
            "}\n" +
            "\n" +
            "count_live_sessions() {\n" +
            "    local count=0\n" +
            "    local f=\"\"\n" +
            "    local pid=\"\"\n" +
            "    for f in \"$SESSION_DIR\"/*; do\n" +
            "        [ -f \"$f\" ] || continue\n" +
            "        pid=$(basename \"$f\")\n" +
            "        if kill -0 \"$pid\" 2>/dev/null; then\n" +
            "            count=$((count + 1))\n" +
            "        else\n" +
            "            rm -f \"$f\" 2>/dev/null || true\n" +
            "        fi\n" +
            "    done\n" +
            "    printf '%s' \"$count\"\n" +
            "}\n" +
            "\n" +
            "cleanup_session() {\n" +
            "    [ \"$CLEANED_UP\" -eq 1 ] && return 0\n" +
            "    CLEANED_UP=1\n" +
            "    rm -f \"$SESSION_DIR/$$\" 2>/dev/null || true\n" +
            "    local remaining\n" +
            "    remaining=$(count_live_sessions)\n" +
            "    if [ \"$remaining\" -eq 0 ]; then\n" +
            "        umount_chroot\n" +
            "        rmdir \"$SESSION_DIR\" 2>/dev/null || true\n" +
            "    fi\n" +
            "}\n" +
            "\n" +
            "case \"$1\" in\n" +
            "    status)\n" +
            "        if is_mounted \"$CHROOT/proc\"; then\n" +
            "            active=$(count_live_sessions)\n" +
            "            echo \"Debian chroot mounted ($CHROOT) - $active active session(s)\"\n" +
            "        else\n" +
            "            echo \"Debian chroot not mounted ($CHROOT)\"\n" +
            "        fi\n" +
            "        exit 0\n" +
            "        ;;\n" +
            "    stop|umount)\n" +
            "        rm -rf \"$SESSION_DIR\" 2>/dev/null || true\n" +
            "        umount_chroot\n" +
            "        echo \"Unmounted $CHROOT\"\n" +
            "        exit 0\n" +
            "        ;;\n" +
            "    exec)\n" +
            "        shift\n" +
            "        mount_chroot\n" +
            "        if [ \"$#\" -eq 1 ]; then\n" +
            "            _EXEC_CMD=\"$1\"\n" +
            "        else\n" +
            "            _EXEC_CMD=\"$*\"\n" +
            "        fi\n" +
            "        exec env -i \\\n" +
            "            HOME=/root \\\n" +
            "            USER=root \\\n" +
            "            LOGNAME=root \\\n" +
            "            TERM=\"${TERM:-xterm-256color}\" \\\n" +
            "            COLORTERM=truecolor \\\n" +
            "            LANG=C.UTF-8 \\\n" +
            "            TMPDIR=/tmp \\\n" +
            "            PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin \\\n" +
            "            chroot \"$CHROOT\" /bin/bash -lc \"$_EXEC_CMD\"\n" +
            "        ;;\n" +
            "esac\n" +
            "\n" +
            "mount_chroot\n" +
            "mkdir -p \"$SESSION_DIR\" 2>/dev/null || true\n" +
            ": > \"$SESSION_DIR/$$\" 2>/dev/null || true\n" +
            "trap cleanup_session EXIT HUP INT TERM\n" +
            "\n" +
            "RC=0\n" +
            "if [ -t 0 ] && command -v setsid >/dev/null 2>&1; then\n" +
            "    env -i \\\n" +
            "        HOME=/root \\\n" +
            "        USER=root \\\n" +
            "        LOGNAME=root \\\n" +
            "        TERM=\"${TERM:-xterm-256color}\" \\\n" +
            "        COLORTERM=truecolor \\\n" +
            "        LANG=C.UTF-8 \\\n" +
            "        PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin \\\n" +
            "        chroot \"$CHROOT\" setsid -w -c /bin/bash -l || RC=$?\n" +
            "else\n" +
            "    env -i \\\n" +
            "        HOME=/root \\\n" +
            "        USER=root \\\n" +
            "        LOGNAME=root \\\n" +
            "        TERM=\"${TERM:-xterm-256color}\" \\\n" +
            "        COLORTERM=truecolor \\\n" +
            "        LANG=C.UTF-8 \\\n" +
            "        PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin \\\n" +
            "        chroot \"$CHROOT\" /bin/bash -l || RC=$?\n" +
            "fi\n" +
            "cleanup_session\n" +
            "exit \"$RC\"\n";
    }

    public static ExecResult installEntryScript(String chrootPath, String scriptPath, boolean createBackup) {
        String suBin = resolveWorkingSuPath();
        String scriptContent = generateEntryScript(chrootPath);
        File target = new File(scriptPath);
        String parentDir = target.getParent() != null ? target.getParent() : "/data/local/bin";
        String qScript = shellQuote(scriptPath);
        String qParent = shellQuote(parentDir);

        StringBuilder setupCmd = new StringBuilder();
        setupCmd.append("mkdir -p ").append(qParent);
        if (createBackup) {
            String ts = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
            String qBak = shellQuote(scriptPath + ".bak-" + ts);
            setupCmd.append(" && if [ -f ").append(qScript).append(" ]; then cp -a ").append(qScript).append(" ").append(qBak).append("; fi");
        }
        setupCmd.append(" && cat > ").append(qScript).append(" && chmod 755 ").append(qScript);

        ExecResult res = execSuWithStdin(suBin, setupCmd.toString(), scriptContent.getBytes(StandardCharsets.UTF_8), 15);
        invalidateStatusCache();
        return res;
    }

    public static ExecResult unmountChroot(String chrootPath, String scriptPath) {
        String suBin = resolveWorkingSuPath();
        String qChroot = shellQuote(chrootPath);
        String qScript = shellQuote(scriptPath);

        String cmd =
            "if [ -x " + qScript + " ]; then " + qScript + " umount; fi; " +
            "for sub in external_sd sdcard dev/shm dev/pts dev proc sys tmp; do " +
            "  tgt=" + qChroot + "/\"$sub\"; " +
            "  if grep -qs \" $tgt \" /proc/mounts /proc/1/mountinfo 2>/dev/null; then " +
            "    umount \"$tgt\" 2>/dev/null || umount -l \"$tgt\" 2>/dev/null || true; " +
            "  fi; " +
            "done; " +
            "echo 'Desmontaje completado.'";
        ExecResult res = execWithBinary(suBin, true, cmd, 20);
        invalidateStatusCache();
        return res;
    }

    public static String shellQuote(String s) {
        if (s == null) return "''";
        return "'" + s.replace("'", "'\\''") + "'";
    }
}
