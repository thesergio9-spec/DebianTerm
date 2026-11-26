package com.debianchroot.term;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
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
    }

    private ChrootManager() {}

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
        for (String candidate : SU_CANDIDATES) {
            if (testSuBinary(candidate)) {
                return candidate;
            }
        }
        if (testSuBinary("su")) {
            return "su";
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
        return execWithBinary(suBin, mountMaster, cmd, timeoutSec);
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

    public static ChrootStatus inspectStatus(String chrootPath, String scriptPath) {
        ChrootStatus status = new ChrootStatus();
        String workingSu = null;
        String detectedVer = "";

        List<String> candidates = new ArrayList<>();
        for (String c : SU_CANDIDATES) {
            candidates.add(c);
        }
        candidates.add("su");

        for (String candidate : candidates) {
            String qCand = shellQuote(candidate);
            String checkCmd =
                "if [ \"$(id -u)\" = '0' ]; then " +
                "  echo 'ROOT_OK'; " +
                "  (" + qCand + " -v 2>/dev/null || " + qCand + " --version 2>/dev/null || true) | head -n 1; " +
                "fi";
            ExecResult res = execWithBinary(candidate, false, checkCmd, 6);
            if (res.exitCode == 0 && res.stdout != null) {
                String[] lines = res.stdout.split("\\r?\\n");
                if (lines.length > 0 && "ROOT_OK".equals(lines[0].trim())) {
                    workingSu = candidate;
                    if (lines.length > 1) {
                        detectedVer = lines[1].trim();
                    }
                    break;
                }
            }
        }

        if (workingSu == null) {
            status.suPath = detectSuPath();
            status.hasRoot = false;
            return status;
        }

        status.hasRoot = true;
        status.suPath = workingSu;
        status.suVersion = detectedVer;
        String suBin = workingSu;

        String qChroot = shellQuote(chrootPath);
        String qScript = shellQuote(scriptPath);
        String probeCmd =
            "if [ -x " + qChroot + "/bin/bash ] && [ -f " + qChroot + "/etc/debian_version ]; then " +
            "  echo 'ROOTFS=1'; " +
            "  if [ -f " + qChroot + "/etc/os-release ]; then " +
            "    sed -n 's/^PRETTY_NAME=\"\\(.*\\)\"/PRETTY=\\1/p' " + qChroot + "/etc/os-release | head -n 1; " +
            "  fi; " +
            "  echo \"VER=$(cat " + qChroot + "/etc/debian_version 2>/dev/null | head -n 1)\"; " +
            "else echo 'ROOTFS=0'; fi; " +
            "if [ -x " + qScript + " ]; then echo 'SCRIPT=1'; else echo 'SCRIPT=0'; fi; " +
            "if [ -d " + qChroot + " ] && [ " + qChroot + " != '/' ] && [ " + qChroot + " != '/data' ]; then " +
            "  if command -v timeout >/dev/null 2>&1; then " +
            "    timeout 2 du -sh " + qChroot + " 2>/dev/null | awk '{print \"SIZE=\" $1}' || true; " +
            "  else " +
            "    du -sh " + qChroot + " 2>/dev/null | awk '{print \"SIZE=\" $1}' || true; " +
            "  fi; " +
            "fi";

        ExecResult probe = execWithBinary(suBin, false, probeCmd, 10);
        String pretty = "";
        String ver = "";
        for (String line : probe.stdout.split("\\r?\\n")) {
            if (line.equals("ROOTFS=1")) {
                status.rootfsExists = true;
            } else if (line.equals("SCRIPT=1")) {
                status.entryScriptExists = true;
            } else if (line.startsWith("PRETTY=")) {
                pretty = line.substring("PRETTY=".length()).trim();
            } else if (line.startsWith("VER=")) {
                ver = line.substring("VER=".length()).trim();
            } else if (line.startsWith("SIZE=")) {
                status.rootfsSizeSummary = line.substring("SIZE=".length()).trim();
            }
        }
        if (!pretty.isEmpty()) {
            status.debianPrettyName = pretty;
        } else if (!ver.isEmpty()) {
            status.debianPrettyName = "Debian " + ver;
        } else if (status.rootfsExists) {
            status.debianPrettyName = "Debian Chroot";
        }

        // Check mounts using global mount namespace first (-mm), and fallback to /proc/1/mountinfo.
        String mountCmd =
            "{ grep -F " + qChroot + " /proc/mounts 2>/dev/null | awk '{print $2}'; " +
            "  grep -F " + qChroot + " /proc/1/mountinfo 2>/dev/null | awk '{print $5}'; } | sort -u";
        ExecResult mountRes = execWithBinary(suBin, true, mountCmd, 6);
        for (String line : mountRes.stdout.split("\\r?\\n")) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty() && !status.activeMounts.contains(trimmed)) {
                status.activeMounts.add(trimmed);
            }
        }

        return status;
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

        return execSuWithStdin(suBin, setupCmd.toString(), scriptContent.getBytes(StandardCharsets.UTF_8), 15);
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
        return execWithBinary(suBin, true, cmd, 20);
    }

    public static String shellQuote(String s) {
        if (s == null) return "''";
        return "'" + s.replace("'", "'\\''") + "'";
    }
}
