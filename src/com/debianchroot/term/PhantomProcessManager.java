package com.debianchroot.term;

import android.content.ContentResolver;
import android.content.Context;
import android.os.Build;
import android.provider.Settings;

/**
 * Detects and mitigates Android 12+ Phantom Process Killer limits
 * that terminate child PRoot/shell processes with SIGKILL (signal 9).
 */
public final class PhantomProcessManager {

    public static final int MIN_AFFECTED_API = 31; // Android 12 (S)
    public static final int DEFAULT_LIMIT = 32;
    public static final int TARGET_MAX_LIMIT = 2147483647;

    public static final class Status {
        public final boolean affectedApi;
        public final boolean monitoringDisabled;
        public final int maxPhantomProcesses;
        public final boolean knownMitigated;
        public final String summary;

        public Status(
            boolean affectedApi,
            boolean monitoringDisabled,
            int maxPhantomProcesses,
            boolean knownMitigated,
            String summary
        ) {
            this.affectedApi = affectedApi;
            this.monitoringDisabled = monitoringDisabled;
            this.maxPhantomProcesses = maxPhantomProcesses;
            this.knownMitigated = knownMitigated;
            this.summary = summary != null ? summary : "";
        }
    }

    private PhantomProcessManager() {}

    public static Status inspect(Context context, boolean hasRoot) {
        String monitorFlag = null;
        String activityConstants = null;
        String deviceConfigMax = null;

        if (context != null) {
            try {
                ContentResolver cr = context.getContentResolver();
                if (cr != null) {
                    monitorFlag = Settings.Global.getString(cr, "settings_enable_monitor_phantom_procs");
                    activityConstants = Settings.Global.getString(cr, "activity_manager_constants");
                }
            } catch (Exception ignored) {
            }
        }

        if (hasRoot && Build.VERSION.SDK_INT >= MIN_AFFECTED_API) {
            String cmd =
                "settings get global settings_enable_monitor_phantom_procs 2>/dev/null; " +
                "echo '---PHANTOM---'; " +
                "/system/bin/device_config get activity_manager max_phantom_processes 2>/dev/null || " +
                "device_config get activity_manager max_phantom_processes 2>/dev/null; " +
                "echo '---PHANTOM---'; " +
                "settings get global activity_manager_constants 2>/dev/null";
            ChrootManager.ExecResult res = ChrootManager.execSu(cmd, 5);
            if (res.stdout != null && !res.stdout.isEmpty()) {
                String[] parts = res.stdout.split("---PHANTOM---", -1);
                if (parts.length > 0 && isMeaningful(parts[0])) {
                    monitorFlag = parts[0].trim();
                }
                if (parts.length > 1 && isMeaningful(parts[1])) {
                    deviceConfigMax = parts[1].trim();
                }
                if (parts.length > 2 && isMeaningful(parts[2])) {
                    activityConstants = parts[2].trim();
                }
            }
        }

        return parsePhantomStatus(Build.VERSION.SDK_INT, monitorFlag, activityConstants, deviceConfigMax);
    }

    public static Status parsePhantomStatus(
        int apiLevel,
        String monitorFlag,
        String activityManagerConstants,
        String deviceConfigMax
    ) {
        if (apiLevel < MIN_AFFECTED_API) {
            return new Status(false, true, 0, true, "No aplica (< Android 12)");
        }

        boolean monitoringDisabled = false;
        if (monitorFlag != null) {
            String t = monitorFlag.trim();
            if ("false".equalsIgnoreCase(t) || "0".equals(t)) {
                monitoringDisabled = true;
            }
        }

        int maxProcs = parsePositiveInt(deviceConfigMax);
        if (maxProcs <= 0 && activityManagerConstants != null) {
            maxProcs = parseMaxFromActivityConstants(activityManagerConstants);
        }

        boolean mitigated = monitoringDisabled || maxProcs >= 1024;
        String summary;
        if (monitoringDisabled) {
            summary = "Desactivado (protegido)";
        } else if (maxProcs >= 1024) {
            summary = "Límite elevado (" + maxProcs + ")";
        } else if (maxProcs > 0) {
            summary = "Activo (límite " + maxProcs + ")";
        } else {
            summary = "Activo (límite por defecto " + DEFAULT_LIMIT + ")";
        }

        return new Status(true, monitoringDisabled, maxProcs, mitigated, summary);
    }

    public static ChrootManager.ExecResult applyRootFix() {
        String cmd =
            "settings put global settings_enable_monitor_phantom_procs false 2>/dev/null || true; " +
            "if [ -x /system/bin/device_config ] || command -v device_config >/dev/null 2>&1; then " +
            "  device_config set_sync_disabled_for_tests persistent 2>/dev/null || true; " +
            "  device_config put activity_manager max_phantom_processes " + TARGET_MAX_LIMIT + " 2>/dev/null || true; " +
            "fi; " +
            "settings put global activity_manager_constants max_phantom_processes=" + TARGET_MAX_LIMIT + " 2>/dev/null || true; " +
            "echo PHANTOM_FIX_OK";
        return ChrootManager.execSu(cmd, 8);
    }

    public static String getAdbFixCommands() {
        return "adb shell \"settings put global settings_enable_monitor_phantom_procs false\"\n"
            + "adb shell \"/system/bin/device_config set_sync_disabled_for_tests persistent\"\n"
            + "adb shell \"/system/bin/device_config put activity_manager max_phantom_processes " + TARGET_MAX_LIMIT + "\"";
    }

    private static boolean isMeaningful(String raw) {
        if (raw == null) return false;
        String t = raw.trim();
        return !t.isEmpty() && !"null".equalsIgnoreCase(t);
    }

    private static int parsePositiveInt(String raw) {
        if (!isMeaningful(raw)) return -1;
        try {
            int val = Integer.parseInt(raw.trim());
            return val > 0 ? val : -1;
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    private static int parseMaxFromActivityConstants(String raw) {
        if (!isMeaningful(raw)) return -1;
        String[] pairs = raw.split(",");
        for (String pair : pairs) {
            int eq = pair.indexOf('=');
            if (eq <= 0) continue;
            String k = pair.substring(0, eq).trim();
            if ("max_phantom_processes".equals(k)) {
                return parsePositiveInt(pair.substring(eq + 1));
            }
        }
        return -1;
    }
}
