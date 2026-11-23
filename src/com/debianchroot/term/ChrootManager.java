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
        "/system/bin/su",
        "/system/xbin/su",
        "/sbin/su",
        "/debug_ramdisk/su",
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
        // Prefer PATH "su" first, then explicit candidate locations.
        if (testSuBinary("su")) {
            return "su";
        }
        for (String candidate : SU_CANDIDATES) {
            if (testSuBinary(candidate)) {
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
        return execWithBinary(suBin, mountMaster, cmd, timeoutSec);
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
            process = new ProcessBuilder(args).start();
            return waitAndCollect(process, null, timeoutSec);
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
            Process process = new ProcessBuilder(args).start();
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
        String suBin = resolveWorkingSuPath();
        status.suPath = suBin;

        ExecResult idRes = execWithBinary(suBin, false, "id -u", 6);
        status.hasRoot = idRes.exitCode == 0 && idRes.stdout.trim().equals("0");
        if (!status.hasRoot) {
            return status;
        }

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
            "du -sh " + qChroot + " 2>/dev/null | awk '{print \"SIZE=\" $1}' || true";

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

    public static String generateEntryScript(String chrootPath) {
        String cleanPath = (chrootPath == null || chrootPath.trim().isEmpty())
            ? TerminalSettings.DEFAULT_CHROOT_PATH
            : chrootPath.trim();
        while (cleanPath.length() > 1 && cleanPath.endsWith("/")) {
            cleanPath = cleanPath.substring(0, cleanPath.length() - 1);
        }
        String quotedChroot = shellQuote(cleanPath);

        return "#!/system/bin/sh\n" +
            "# Generated by DebianTerm - Safe Debian Chroot launcher\n" +
            "CHROOT=" + quotedChroot + "\n" +
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
            "case \"$1\" in\n" +
            "    status)\n" +
            "        if is_mounted \"$CHROOT/proc\"; then\n" +
            "            echo \"Debian chroot mounted ($CHROOT)\"\n" +
            "        else\n" +
            "            echo \"Debian chroot not mounted ($CHROOT)\"\n" +
            "        fi\n" +
            "        exit 0\n" +
            "        ;;\n" +
            "    umount)\n" +
            "        umount_chroot\n" +
            "        echo \"Unmounted $CHROOT\"\n" +
            "        exit 0\n" +
            "        ;;\n" +
            "    exec)\n" +
            "        shift\n" +
            "        mount_chroot\n" +
            "        exec env -i \\\n" +
            "            HOME=/root \\\n" +
            "            USER=root \\\n" +
            "            LOGNAME=root \\\n" +
            "            TERM=\"${TERM:-xterm-256color}\" \\\n" +
            "            COLORTERM=truecolor \\\n" +
            "            LANG=C.UTF-8 \\\n" +
            "            PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin \\\n" +
            "            chroot \"$CHROOT\" /bin/bash -lc \"$*\"\n" +
            "        ;;\n" +
            "esac\n" +
            "\n" +
            "mount_chroot\n" +
            "if [ -t 0 ] && command -v setsid >/dev/null 2>&1; then\n" +
            "    exec env -i \\\n" +
            "        HOME=/root \\\n" +
            "        USER=root \\\n" +
            "        LOGNAME=root \\\n" +
            "        TERM=\"${TERM:-xterm-256color}\" \\\n" +
            "        COLORTERM=truecolor \\\n" +
            "        LANG=C.UTF-8 \\\n" +
            "        PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin \\\n" +
            "        chroot \"$CHROOT\" setsid -w -c /bin/bash -l\n" +
            "else\n" +
            "    exec env -i \\\n" +
            "        HOME=/root \\\n" +
            "        USER=root \\\n" +
            "        LOGNAME=root \\\n" +
            "        TERM=\"${TERM:-xterm-256color}\" \\\n" +
            "        COLORTERM=truecolor \\\n" +
            "        LANG=C.UTF-8 \\\n" +
            "        PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin \\\n" +
            "        chroot \"$CHROOT\" /bin/bash -l\n" +
            "fi\n";
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
