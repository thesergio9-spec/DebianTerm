package com.debianchroot.term;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

/**
 * Starts DebianTerm background boot scripts after Android finishes booting (user unlocked).
 */
public final class BootReceiver extends BroadcastReceiver {

    private static final String LOG_TAG = "DebianTermBoot";
    public static final String ACTION_QUICKBOOT_POWERON = "android.intent.action.QUICKBOOT_POWERON";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (context == null || intent == null) return;
        String action = intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action) && !ACTION_QUICKBOOT_POWERON.equals(action)) {
            return;
        }
        TerminalSettings settings = new TerminalSettings(context);
        if (!settings.isBootAutostartEnabled()) {
            return;
        }

        Intent svcIntent = new Intent(context, DebianTermService.class);
        svcIntent.setAction(DebianTermService.ACTION_RUN_BOOT_TASKS);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(svcIntent);
            } else {
                context.startService(svcIntent);
            }
        } catch (RuntimeException e) {
            Log.w(LOG_TAG, "No se pudo iniciar DebianTermService tras BOOT_COMPLETED: " + e.getMessage());
        }
    }

    /**
     * Resolves whether boot task should target Root chroot (TRUE), non-root PRoot (FALSE), or none (null).
     */
    public static Boolean resolveBootMode(
        String targetPref,
        String preferredInstallMode,
        boolean hasRoot,
        boolean rootInstalled,
        boolean prootInstalled
    ) {
        if (TerminalSettings.BOOT_TARGET_ROOT.equals(targetPref)) {
            return (hasRoot && rootInstalled) ? Boolean.TRUE : null;
        }
        if (TerminalSettings.BOOT_TARGET_PROOT.equals(targetPref)) {
            return prootInstalled ? Boolean.FALSE : null;
        }
        boolean preferProot = TerminalSettings.MODE_PROOT.equals(preferredInstallMode);
        if (preferProot) {
            if (prootInstalled) return Boolean.FALSE;
            if (hasRoot && rootInstalled) return Boolean.TRUE;
        } else {
            if (hasRoot && rootInstalled) return Boolean.TRUE;
            if (prootInstalled) return Boolean.FALSE;
        }
        return null;
    }

    /**
     * Generates a safe POSIX sh command executed inside Debian on Android boot (or test run).
     * Writes full logs to /root/.debianterm/logs/ without keeping pipes blocked by background daemons.
     */
    public static String buildBootCommand(String userScript, boolean keepShellAlive) {
        String body = userScript == null ? "" : userScript.replace("\r\n", "\n").replace('\r', '\n');
        String delim = "__DEBIANTERM_BOOT_EOF__";
        while (body.contains(delim)) {
            delim = delim + "_X";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("mkdir -p /root/.debianterm/boot /root/.debianterm/logs 2>/dev/null || true\n");
        sb.append("LOG_FILE=\"/root/.debianterm/logs/boot-$(date +%Y%m%d-%H%M%S 2>/dev/null || echo latest).log\"\n");
        sb.append("echo \"=== DebianTerm Autoarranque ($(date 2>/dev/null || true)) ===\" > \"$LOG_FILE\"\n");
        sb.append("BOOT_RC=0\n");

        if (hasExecutableLines(body)) {
            sb.append("/bin/sh <<'").append(delim).append("' >> \"$LOG_FILE\" 2>&1 || BOOT_RC=$?\n");
            sb.append(body);
            if (!body.endsWith("\n")) {
                sb.append('\n');
            }
            sb.append(delim).append('\n');
        }

        sb.append("for _bf in /root/.debianterm/boot/*; do\n");
        sb.append("    if [ -f \"$_bf\" ]; then\n");
        sb.append("        echo \"-> Ejecutando $_bf\" >> \"$LOG_FILE\"\n");
        sb.append("        if [ -x \"$_bf\" ]; then\n");
        sb.append("            \"$_bf\" >> \"$LOG_FILE\" 2>&1 || BOOT_RC=$?\n");
        sb.append("        else\n");
        sb.append("            /bin/sh \"$_bf\" >> \"$LOG_FILE\" 2>&1 || BOOT_RC=$?\n");
        sb.append("        fi\n");
        sb.append("    fi\n");
        sb.append("done\n");
        sb.append("echo \"=== Autoarranque finalizado (exit=$BOOT_RC) ===\" >> \"$LOG_FILE\"\n");
        sb.append("cat \"$LOG_FILE\" 2>/dev/null || true\n");
        sb.append("echo \"Log guardado en: $LOG_FILE\"\n");

        if (keepShellAlive) {
            sb.append("exec /bin/bash -l\n");
        } else {
            sb.append("exit \"$BOOT_RC\"\n");
        }
        return sb.toString();
    }

    private static boolean hasExecutableLines(String script) {
        if (script == null || script.trim().isEmpty()) return false;
        for (String line : script.split("\\n")) {
            String t = line.trim();
            if (!t.isEmpty() && !t.startsWith("#")) {
                return true;
            }
        }
        return false;
    }
}
