package com.debianchroot.term;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.widget.Toast;

import java.io.File;

/**
 * Coordinates Debian 12 graphical desktop (XFCE4 / LXDE) installation, Termux:X11 detection,
 * download recommendation, and VNC/X11 session command generation for Root & PRoot paths.
 */
public final class DesktopManager {

    public static final String BACKEND_VNC = "vnc";
    public static final String BACKEND_TERMUX_X11 = "x11";
    public static final String BACKEND_NATIVE_X11 = BACKEND_TERMUX_X11;

    public static final String DESKTOP_XFCE = "xfce4";
    public static final String DESKTOP_LXDE = "lxde";

    public static final String TERMUX_X11_PACKAGE = "com.termux.x11";
    public static final String TERMUX_X11_APK_URL =
        "https://github.com/termux/termux-x11/releases/download/nightly/termux-x11-universal-release.apk";
    public static final String TERMUX_X11_RELEASES_URL =
        "https://github.com/termux/termux-x11/releases/tag/nightly";

    public static final int VNC_PORT = 5901;
    public static final String VNC_DISPLAY = ":1";
    public static final String X11_DISPLAY = ":0";

    public static final class DesktopStatus {
        public boolean builtInX11Available;
        public boolean termuxX11Installed;
        public String termuxX11Version = "";
        public String termuxX11ApkPath = "";
        public String termuxX11LibDir = "";

        public boolean rootDesktopInstalled;
        public boolean rootXfceInstalled;
        public boolean rootLxdeInstalled;
        public String rootDesktopName = "";
        public boolean rootVncServerInstalled;

        public boolean prootDesktopInstalled;
        public boolean prootXfceInstalled;
        public boolean prootLxdeInstalled;
        public String prootDesktopName = "";
        public boolean prootVncServerInstalled;

        public boolean isDesktopInstalled(boolean isRootMode, String desktopId) {
            boolean wantLxde = DESKTOP_LXDE.equals(desktopId);
            if (isRootMode) {
                return wantLxde ? rootLxdeInstalled : rootXfceInstalled;
            }
            return wantLxde ? prootLxdeInstalled : prootXfceInstalled;
        }
    }

    private DesktopManager() {}

    public static boolean isBuiltInX11Available(Context context) {
        if (context == null || context.getApplicationInfo() == null) return false;
        String libDir = context.getApplicationInfo().nativeLibraryDir;
        return libDir != null && new File(libDir, "libXlorie.so").exists();
    }

    public static DesktopStatus inspectDesktopStatus(
        Context context,
        SystemRequirements.Report req,
        String chrootPath
    ) {
        DesktopStatus status = new DesktopStatus();

        // 1. Detect built-in X11 (libXlorie.so) or fallback external Termux:X11 app
        status.builtInX11Available = isBuiltInX11Available(context);
        if (status.builtInX11Available && context != null && context.getApplicationInfo() != null) {
            status.termuxX11Installed = true;
            status.termuxX11Version = "integrado";
            status.termuxX11ApkPath = context.getApplicationInfo().sourceDir != null
                ? context.getApplicationInfo().sourceDir : "";
            status.termuxX11LibDir = context.getApplicationInfo().nativeLibraryDir != null
                ? context.getApplicationInfo().nativeLibraryDir : "";
        } else {
            try {
                PackageManager pm = context.getPackageManager();
                PackageInfo pi = pm.getPackageInfo(TERMUX_X11_PACKAGE, 0);
                if (pi != null && pi.applicationInfo != null) {
                    status.termuxX11Installed = true;
                    status.termuxX11Version = pi.versionName != null ? pi.versionName : "instalado";
                    status.termuxX11ApkPath = pi.applicationInfo.sourceDir != null ? pi.applicationInfo.sourceDir : "";
                    status.termuxX11LibDir = pi.applicationInfo.nativeLibraryDir != null ? pi.applicationInfo.nativeLibraryDir : "";
                }
            } catch (PackageManager.NameNotFoundException ignored) {
            } catch (Exception ignored) {
            }
        }

        // 2. Inspect Root Chroot desktop & VNC packages (fast single su probe)
        if (req.hasRoot && req.rootStatus.rootfsExists) {
            String qChroot = ChrootManager.shellQuote(chrootPath);
            String probeCmd =
                "C=" + qChroot + "; " +
                "[ -x \"$C/usr/bin/startxfce4\" ] && echo 'XFCE=1'; " +
                "[ -x \"$C/usr/bin/startlxde\" ] && echo 'LXDE=1'; " +
                "if [ -x \"$C/usr/bin/Xtigervnc\" ] || [ -x \"$C/usr/bin/Xvnc\" ]; then echo 'VNC=1'; fi";
            String suBin = (req.suPath != null && !req.suPath.isEmpty())
                ? req.suPath
                : ChrootManager.resolveWorkingSuPath();
            ChrootManager.ExecResult res = ChrootManager.execWithBinary(suBin, false, probeCmd, 5);
            boolean hasXfce = false;
            boolean hasLxde = false;
            for (String line : res.stdout.split("\\r?\\n")) {
                String t = line.trim();
                if ("XFCE=1".equals(t)) hasXfce = true;
                else if ("LXDE=1".equals(t)) hasLxde = true;
                else if ("VNC=1".equals(t)) status.rootVncServerInstalled = true;
            }
            status.rootXfceInstalled = hasXfce;
            status.rootLxdeInstalled = hasLxde;
            if (hasXfce && hasLxde) {
                status.rootDesktopInstalled = true;
                status.rootDesktopName = "XFCE4 + LXDE";
            } else if (hasXfce) {
                status.rootDesktopInstalled = true;
                status.rootDesktopName = "XFCE4";
            } else if (hasLxde) {
                status.rootDesktopInstalled = true;
                status.rootDesktopName = "LXDE";
            }
        }

        // 3. Inspect Sin Root PRoot desktop & VNC packages
        if (req.prootStatus.rootfsExists) {
            File prootRoot = ProotManager.getRootfsDir(context);
            boolean hasXfce = new File(prootRoot, "usr/bin/startxfce4").exists();
            boolean hasLxde = new File(prootRoot, "usr/bin/startlxde").exists();
            status.prootXfceInstalled = hasXfce;
            status.prootLxdeInstalled = hasLxde;
            status.prootVncServerInstalled = new File(prootRoot, "usr/bin/Xtigervnc").exists()
                || new File(prootRoot, "usr/bin/Xvnc").exists();
            if (hasXfce && hasLxde) {
                status.prootDesktopInstalled = true;
                status.prootDesktopName = "XFCE4 + LXDE";
            } else if (hasXfce) {
                status.prootDesktopInstalled = true;
                status.prootDesktopName = "XFCE4";
            } else if (hasLxde) {
                status.prootDesktopInstalled = true;
                status.prootDesktopName = "LXDE";
            }
        }

        return status;
    }

    public static int[] parseResolution(String raw) {
        if (raw != null) {
            String[] parts = raw.trim().toLowerCase().split("x");
            if (parts.length == 2) {
                try {
                    int w = Integer.parseInt(parts[0].trim());
                    int h = Integer.parseInt(parts[1].trim());
                    if (w >= 480 && w <= 3840 && h >= 480 && h <= 3840) {
                        return new int[] { w, h };
                    }
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return new int[] { 1280, 720 };
    }

    public static int[] computeFitResolution(int surfaceW, int surfaceH, boolean portrait) {
        if (surfaceW <= 0 || surfaceH <= 0) {
            return portrait ? new int[] { 720, 1280 } : new int[] { 1280, 720 };
        }
        int minSide = Math.min(surfaceW, surfaceH);
        int maxSide = Math.max(surfaceW, surfaceH);
        int shortSide = Math.max(480, Math.min(720, minSide));
        int longSide = (int) Math.round(shortSide * (maxSide / (double) minSide));
        longSide = Math.max(480, Math.min(2560, longSide));

        int w = portrait ? shortSide : longSide;
        int h = portrait ? longSide : shortSide;
        w = Math.max(480, (w / 8) * 8);
        h = Math.max(480, (h / 2) * 2);
        return new int[] { w, h };
    }

    public static int[] resolveDesktopResolution(
        String resSetting,
        String orientationSetting,
        int surfaceW,
        int surfaceH
    ) {
        boolean wantPortrait;
        if (TerminalSettings.GUI_ORIENTATION_PORTRAIT.equals(orientationSetting)) {
            wantPortrait = true;
        } else if (TerminalSettings.GUI_ORIENTATION_LANDSCAPE.equals(orientationSetting)) {
            wantPortrait = false;
        } else {
            wantPortrait = (surfaceW <= 0 || surfaceH <= 0) || (surfaceW < surfaceH);
        }

        if (TerminalSettings.GUI_RESOLUTION_FIT.equals(resSetting)) {
            return computeFitResolution(surfaceW, surfaceH, wantPortrait);
        }

        int[] base = parseResolution(resSetting);
        int big = Math.max(base[0], base[1]);
        int small = Math.min(base[0], base[1]);
        return wantPortrait ? new int[] { small, big } : new int[] { big, small };
    }

    public static String generateDesktopInstallCommand(String desktopId) {
        boolean isLxde = DESKTOP_LXDE.equals(desktopId);
        String desktopPkgs = isLxde
            ? "lxde-core lxterminal"
            : "xfce4 xfce4-terminal";
        String startBin = isLxde ? "startlxde" : "startxfce4";

        return "set -e\n" +
            "echo '=== Preparando entorno Debian 12 ==='\n" +
            "if [ ! -s /etc/resolv.conf ]; then\n" +
            "    printf 'nameserver 1.1.1.1\\nnameserver 8.8.8.8\\n' > /etc/resolv.conf 2>/dev/null || true\n" +
            "fi\n" +
            "export DEBIAN_FRONTEND=noninteractive\n" +
            "dpkg --configure -a 2>/dev/null || true\n" +
            "echo '=== Actualizando repositorios Debian 12 ==='\n" +
            "apt-get update\n" +
            "echo '=== Instalando entorno gráfico (" + (isLxde ? "LXDE" : "XFCE4") + ") + TigerVNC ==='\n" +
            "apt-get install -y --no-install-recommends"
            + " -o Dpkg::Options::=\"--force-confdef\" -o Dpkg::Options::=\"--force-confold\" "
            + desktopPkgs
            + " dbus-x11 tigervnc-standalone-server tigervnc-common x11-xserver-utils xkb-data xfonts-base fonts-dejavu-core\n" +
            "if command -v dbus-uuidgen >/dev/null 2>&1; then\n" +
            "    mkdir -p /var/lib/dbus\n" +
            "    dbus-uuidgen --ensure=/etc/machine-id 2>/dev/null || true\n" +
            "    dbus-uuidgen --ensure 2>/dev/null || true\n" +
            "fi\n" +
            "mkdir -p /root/.vnc /tmp/.X11-unix\n" +
            "chmod 1777 /tmp /tmp/.X11-unix\n" +
            "printf '%s\\n' '#!/bin/sh' 'unset SESSION_MANAGER' 'unset DBUS_SESSION_BUS_ADDRESS' 'exec dbus-launch --exit-with-session " + startBin + "' > /root/.vnc/xstartup\n" +
            "chmod 755 /root/.vnc/xstartup\n" +
            "echo '=== ¡Escritorio Debian 12 instalado correctamente! ==='\n" +
            "echo 'Ya puedes volver al Panel de Inicio (⌂ Inicio) para abrir el Modo Gráfico.'\n";
    }

    /**
     * POSIX sh snippet that terminates orphaned X11/VNC servers and desktop session clients bound to
     * {@code display}. Processes carrying the marker {@code _dt_kill_display} in their argv (this very
     * script and its ancestors) are skipped. When {@code includeServer} is false only desktop clients
     * (xfce4-session, lxsession, dbus-launch...) whose environment has DISPLAY=display are killed.
     */
    public static String buildCleanupDisplayProcessesSnippet(String display, boolean includeServer) {
        String disp = (display == null || display.trim().isEmpty()) ? X11_DISPLAY : display.trim();
        boolean x11 = X11_DISPLAY.equals(disp);
        String serverPattern = x11
            ? "*com.termux.x11.CmdEntryPoint*|*debianterm-x11*"
            : "*Xtigervnc\\ " + disp + "*|*Xvnc\\ " + disp + "*";
        StringBuilder sb = new StringBuilder();
        sb.append("_dt_kill_display() {\n");
        sb.append("    _dt_disp=\"$1\"\n");
        sb.append("    _dt_srv=\"$2\"\n");
        sb.append("    _dt_pids=''\n");
        sb.append("    for _dt_cf in $(grep -aEl 'CmdEntryPoint|debianterm-x11|Xtigervnc|Xvnc|startxfce4|xfce4-session|startlxde|lxsession|dbus-launch' /proc/[0-9]*/cmdline 2>/dev/null); do\n");
        sb.append("        _dt_p=${_dt_cf%/cmdline}\n");
        sb.append("        _dt_pid=${_dt_p#/proc/}\n");
        sb.append("        [ \"$_dt_pid\" = \"$$\" ] && continue\n");
        sb.append("        [ -r \"$_dt_p/cmdline\" ] || continue\n");
        sb.append("        _dt_cmd=$(dd if=\"$_dt_p/cmdline\" bs=4096 count=1 2>/dev/null | tr '\\0' ' ')\n");
        sb.append("        [ -n \"$_dt_cmd\" ] || continue\n");
        sb.append("        case \"$_dt_cmd\" in *_dt_kill_display*) continue ;; esac\n");
        sb.append("        _dt_hit=0\n");
        sb.append("        if [ \"$_dt_srv\" = 1 ]; then\n");
        sb.append("            case \"$_dt_cmd\" in\n");
        sb.append("                ").append(serverPattern).append(") _dt_hit=1 ;;\n");
        sb.append("            esac\n");
        sb.append("        fi\n");
        sb.append("        if [ \"$_dt_hit\" = 0 ]; then\n");
        sb.append("            case \"$_dt_cmd\" in\n");
        sb.append("                *startxfce4*|*xfce4-session*|*startlxde*|*lxsession*|*dbus-launch*)\n");
        sb.append("                    if [ -r \"$_dt_p/environ\" ] && dd if=\"$_dt_p/environ\" bs=4096 count=16 2>/dev/null | tr '\\0' '\\n' | grep -qx \"DISPLAY=$_dt_disp\"; then _dt_hit=1; fi ;;\n");
        sb.append("            esac\n");
        sb.append("        fi\n");
        sb.append("        if [ \"$_dt_hit\" = 1 ]; then _dt_pids=\"$_dt_pids $_dt_pid\"; fi\n");
        sb.append("    done\n");
        sb.append("    [ -n \"$_dt_pids\" ] || return 0\n");
        sb.append("    kill -TERM $_dt_pids 2>/dev/null\n");
        sb.append("    _dt_i=0\n");
        sb.append("    _dt_alive=1\n");
        sb.append("    while [ $_dt_i -lt 15 ]; do\n");
        sb.append("        _dt_alive=0\n");
        sb.append("        for _dt_k in $_dt_pids; do\n");
        sb.append("            if kill -0 \"$_dt_k\" 2>/dev/null; then _dt_alive=1; fi\n");
        sb.append("        done\n");
        sb.append("        [ \"$_dt_alive\" = 0 ] && break\n");
        sb.append("        _dt_i=$((_dt_i + 1))\n");
        sb.append("        sleep 0.1\n");
        sb.append("    done\n");
        sb.append("    if [ \"$_dt_alive\" = 1 ]; then\n");
        sb.append("        kill -KILL $_dt_pids 2>/dev/null\n");
        sb.append("        sleep 0.1\n");
        sb.append("    fi\n");
        sb.append("    return 0\n");
        sb.append("}\n");
        sb.append("_dt_kill_display ").append(ChrootManager.shellQuote(disp)).append(' ')
            .append(includeServer ? '1' : '0').append('\n');
        return sb.toString();
    }

    private static String buildDesktopSessionPrep(String desktopId) {
        StringBuilder sb = new StringBuilder();
        if (DESKTOP_LXDE.equals(desktopId)) {
            sb.append("mkdir -p /root/.config/autostart\n");
            sb.append("if [ ! -f /root/.config/autostart/lxpolkit.desktop ]; then\n");
            sb.append("    printf '%s\\n' '[Desktop Entry]' 'Type=Application' 'Name=LXPolKit' 'Hidden=true' > /root/.config/autostart/lxpolkit.desktop\n");
            sb.append("fi\n");
        }
        if (DESKTOP_LXDE.equals(desktopId)) {
            sb.append("mkdir -p /root/.config/lxsession/LXDE\n");
            sb.append("if [ ! -f /root/.config/lxsession/LXDE/desktop.conf ]; then\n");
            sb.append("    if [ -f /etc/xdg/lxsession/LXDE/desktop.conf ]; then\n");
            sb.append("        sed 's|^polkit/command=.*|polkit/command=|' /etc/xdg/lxsession/LXDE/desktop.conf > /root/.config/lxsession/LXDE/desktop.conf.tmp 2>/dev/null && [ -s /root/.config/lxsession/LXDE/desktop.conf.tmp ] && mv /root/.config/lxsession/LXDE/desktop.conf.tmp /root/.config/lxsession/LXDE/desktop.conf || rm -f /root/.config/lxsession/LXDE/desktop.conf.tmp\n");
            sb.append("    else\n");
            sb.append("        printf '%s\\n' '[Session]' 'windows_manager/command=openbox-lxde' 'polkit/command=' > /root/.config/lxsession/LXDE/desktop.conf\n");
            sb.append("    fi\n");
            sb.append("fi\n");
        }
        sb.append("xsetroot -solid '#1B2838' 2>/dev/null || true\n");
        sb.append("xset s off -dpms 2>/dev/null || xset s off 2>/dev/null || true\n");
        return sb.toString();
    }

    public static String generateVncStartCommand(String desktopId, int width, int height) {
        String startBin = DESKTOP_LXDE.equals(desktopId) ? "startlxde" : "startxfce4";
        int w = Math.max(480, Math.min(3840, width));
        int h = Math.max(480, Math.min(3840, height));

        return "VNC_BIN=$(command -v Xtigervnc || command -v Xvnc || true)\n" +
            "if [ -z \"$VNC_BIN\" ]; then\n" +
            "    echo 'Error: Xtigervnc no encontrado. Instala el entorno gráfico primero.'\n" +
            "    exit 1\n" +
            "fi\n" +
            "if ! command -v " + startBin + " >/dev/null 2>&1; then\n" +
            "    echo 'Error: " + startBin + " no encontrado. Instala " + (DESKTOP_LXDE.equals(desktopId) ? "LXDE" : "XFCE4") + " primero.'\n" +
            "    exit 1\n" +
            "fi\n" +
            "if command -v dbus-uuidgen >/dev/null 2>&1; then\n" +
            "    mkdir -p /var/lib/dbus\n" +
            "    dbus-uuidgen --ensure=/etc/machine-id 2>/dev/null || true\n" +
            "    dbus-uuidgen --ensure 2>/dev/null || true\n" +
            "fi\n" +
            buildCleanupDisplayProcessesSnippet(VNC_DISPLAY, true) +
            "rm -f /tmp/.X1-lock /tmp/.X11-unix/X1 2>/dev/null || true\n" +
            "mkdir -p /tmp/.X11-unix /tmp/runtime-root\n" +
            "chmod 1777 /tmp /tmp/.X11-unix 2>/dev/null || true\n" +
            "chmod 700 /tmp/runtime-root 2>/dev/null || true\n" +
            "VNC_PID=''\n" +
            "cleanup_vnc() {\n" +
            "    [ -n \"$VNC_PID\" ] && kill \"$VNC_PID\" 2>/dev/null || true\n" +
            "    rm -f /tmp/.X1-lock /tmp/.X11-unix/X1 2>/dev/null || true\n" +
            "}\n" +
            "trap cleanup_vnc EXIT HUP INT TERM\n" +
            "echo '=== Iniciando Xtigervnc " + VNC_DISPLAY + " (" + w + "x" + h + ") ==='\n" +
            "\"$VNC_BIN\" " + VNC_DISPLAY + " -localhost -rfbport " + VNC_PORT
            + " -geometry " + w + "x" + h + " -depth 24 -SecurityTypes None -AlwaysShared >/tmp/debianterm-vnc.log 2>&1 &\n" +
            "VNC_PID=$!\n" +
            "i=0\n" +
            "while [ $i -lt 100 ]; do\n" +
            "    if ! kill -0 \"$VNC_PID\" 2>/dev/null; then\n" +
            "        break\n" +
            "    fi\n" +
            "    [ -S /tmp/.X11-unix/X1 ] && break\n" +
            "    i=$((i + 1))\n" +
            "    sleep 0.1\n" +
            "done\n" +
            "if [ ! -S /tmp/.X11-unix/X1 ]; then\n" +
            "    echo 'Error: Xtigervnc no pudo abrir el display " + VNC_DISPLAY + ". Registro (/tmp/debianterm-vnc.log):'\n" +
            "    cat /tmp/debianterm-vnc.log 2>/dev/null || true\n" +
            "    cleanup_vnc\n" +
            "    exit 1\n" +
            "fi\n" +
            "echo '=== Xtigervnc listo en 127.0.0.1:" + VNC_PORT + " -> iniciando " + startBin + " ==='\n" +
            "export DISPLAY=" + VNC_DISPLAY + "\n" +
            "export XDG_RUNTIME_DIR=/tmp/runtime-root\n" +
            "unset SESSION_MANAGER DBUS_SESSION_BUS_ADDRESS\n" +
            buildDesktopSessionPrep(desktopId) +
            "dbus-launch --exit-with-session " + startBin + "\n" +
            "cleanup_vnc\n";
    }

    public static String generateTermuxX11StartCommand(String desktopId) {
        String startBin = DESKTOP_LXDE.equals(desktopId) ? "startlxde" : "startxfce4";
        return "if ! command -v " + startBin + " >/dev/null 2>&1; then\n" +
            "    echo 'Error: " + startBin + " no encontrado. Instala el entorno gráfico primero.'\n" +
            "    exit 1\n" +
            "fi\n" +
            "if command -v dbus-uuidgen >/dev/null 2>&1; then\n" +
            "    mkdir -p /var/lib/dbus\n" +
            "    dbus-uuidgen --ensure=/etc/machine-id 2>/dev/null || true\n" +
            "    dbus-uuidgen --ensure 2>/dev/null || true\n" +
            "fi\n" +
            "mkdir -p /tmp/.X11-unix /tmp/runtime-root\n" +
            "chmod 1777 /tmp /tmp/.X11-unix 2>/dev/null || true\n" +
            "chmod 700 /tmp/runtime-root 2>/dev/null || true\n" +
            buildCleanupDisplayProcessesSnippet(X11_DISPLAY, false) +
            "export DISPLAY=" + X11_DISPLAY + "\n" +
            "export XDG_RUNTIME_DIR=/tmp/runtime-root\n" +
            "unset SESSION_MANAGER DBUS_SESSION_BUS_ADDRESS\n" +
            buildDesktopSessionPrep(desktopId) +
            "dbus-launch --exit-with-session " + startBin + "\n";
    }

    public static String generateTermuxX11ServerCommand(
        String x11ApkPath,
        String x11LibDir,
        String sharedTmpDir
    ) {
        return generateBuiltInX11ServerCommand(x11ApkPath, x11LibDir, sharedTmpDir, null);
    }

    public static String generateBuiltInX11ServerCommand(
        String appApkPath,
        String nativeLibDir,
        String sharedTmpDir,
        String rootfsDir
    ) {
        String qApk = ChrootManager.shellQuote(appApkPath);
        String qLib = ChrootManager.shellQuote(nativeLibDir != null ? nativeLibDir : "");
        String qTmp = ChrootManager.shellQuote(sharedTmpDir);
        String resolvedRoot = (rootfsDir != null && !rootfsDir.trim().isEmpty()) ? rootfsDir.trim() : "";

        StringBuilder sb = new StringBuilder();
        sb.append("TMP_HOST=").append(qTmp).append('\n');
        if (!resolvedRoot.isEmpty()) {
            sb.append("ROOT_HOST=").append(ChrootManager.shellQuote(resolvedRoot)).append('\n');
        } else {
            sb.append("ROOT_HOST=$(dirname \"$TMP_HOST\")\n");
        }
        sb.append(buildCleanupDisplayProcessesSnippet(X11_DISPLAY, true));
        sb.append("mkdir -p \"$TMP_HOST/.X11-unix\"\n");
        sb.append("chmod 1777 \"$TMP_HOST\" \"$TMP_HOST/.X11-unix\" 2>/dev/null || true\n");
        sb.append("rm -f \"$TMP_HOST/.X0-lock\" \"$TMP_HOST/.X11-unix/X0\" 2>/dev/null || true\n");
        sb.append("export TMPDIR=\"$TMP_HOST\"\n");
        sb.append("export XDG_RUNTIME_DIR=\"$TMP_HOST\"\n");
        sb.append("export XKB_CONFIG_ROOT=\"$ROOT_HOST/usr/share/X11/xkb\"\n");
        sb.append("export XSTARTUP_LD_PRELOAD=none\n");
        sb.append("export CLASSPATH=").append(qApk).append('\n');
        sb.append("unset LD_LIBRARY_PATH LD_PRELOAD\n");
        sb.append("echo '=== Iniciando servidor X11 Nativo (:0) ==='\n");
        sb.append("/system/bin/app_process -Xnoimage-dex2oat ");
        if (nativeLibDir != null && !nativeLibDir.isEmpty()) {
            sb.append("-Djava.library.path=").append(qLib).append(' ');
        }
        sb.append("/ --nice-name=\"debianterm-x11 :0\" com.termux.x11.CmdEntryPoint :0 -ac >\"$TMP_HOST/debianterm-x11.log\" 2>&1 &\n");
        sb.append("X11_PID=$!\n");
        sb.append("trap 'kill \"$X11_PID\" 2>/dev/null || true' EXIT HUP INT TERM\n");
        sb.append("i=0\n");
        sb.append("while [ $i -lt 100 ]; do\n");
        sb.append("    if ! kill -0 \"$X11_PID\" 2>/dev/null; then\n");
        sb.append("        break\n");
        sb.append("    fi\n");
        sb.append("    [ -S \"$TMP_HOST/.X11-unix/X0\" ] && break\n");
        sb.append("    i=$((i + 1))\n");
        sb.append("    sleep 0.1\n");
        sb.append("done\n");
        sb.append("if [ ! -S \"$TMP_HOST/.X11-unix/X0\" ]; then\n");
        sb.append("    echo 'Error: X11 Nativo no pudo abrir el socket :0. Registro:'\n");
        sb.append("    tail -n 30 \"$TMP_HOST/debianterm-x11.log\" 2>/dev/null || true\n");
        sb.append("fi\n");
        return sb.toString();
    }

    public static void openTermuxX11Download(Context context) {
        if (context == null) return;
        int flags = (context instanceof android.app.Activity) ? 0 : Intent.FLAG_ACTIVITY_NEW_TASK;
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(TERMUX_X11_APK_URL));
            if (flags != 0) intent.addFlags(flags);
            context.startActivity(intent);
        } catch (Exception e) {
            try {
                Intent fallback = new Intent(Intent.ACTION_VIEW, Uri.parse(TERMUX_X11_RELEASES_URL));
                if (flags != 0) fallback.addFlags(flags);
                context.startActivity(fallback);
            } catch (Exception e2) {
                Toast.makeText(context, "Abre en tu navegador: " + TERMUX_X11_RELEASES_URL, Toast.LENGTH_LONG).show();
            }
        }
    }

    public static boolean launchTermuxX11App(Context context) {
        try {
            Intent launch = context.getPackageManager().getLaunchIntentForPackage(TERMUX_X11_PACKAGE);
            if (launch == null) {
                return false;
            }
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(launch);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
