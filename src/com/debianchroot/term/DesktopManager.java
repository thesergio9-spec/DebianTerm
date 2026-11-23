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
        public boolean termuxX11Installed;
        public String termuxX11Version = "";
        public String termuxX11ApkPath = "";
        public String termuxX11LibDir = "";

        public boolean rootDesktopInstalled;
        public String rootDesktopName = "";
        public boolean rootVncServerInstalled;

        public boolean prootDesktopInstalled;
        public String prootDesktopName = "";
        public boolean prootVncServerInstalled;
    }

    private DesktopManager() {}

    public static DesktopStatus inspectDesktopStatus(
        Context context,
        SystemRequirements.Report req,
        String chrootPath
    ) {
        DesktopStatus status = new DesktopStatus();

        // 1. Detect Termux:X11 Android app
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
                    if (w >= 640 && w <= 3840 && h >= 480 && h <= 2160) {
                        return new int[] { w, h };
                    }
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return new int[] { 1280, 720 };
    }

    public static String generateDesktopInstallCommand(String desktopId) {
        boolean isLxde = DESKTOP_LXDE.equals(desktopId);
        String desktopPkgs = isLxde
            ? "lxde-core lxterminal"
            : "xfce4 xfce4-terminal";
        String startBin = isLxde ? "startlxde" : "startxfce4";

        return "set -e\n" +
            "echo '=== Actualizando repositorios Debian 12 ==='\n" +
            "export DEBIAN_FRONTEND=noninteractive\n" +
            "apt-get update\n" +
            "echo '=== Instalando entorno gráfico (" + (isLxde ? "LXDE" : "XFCE4") + ") + TigerVNC ==='\n" +
            "apt-get install -y --no-install-recommends " + desktopPkgs
            + " dbus-x11 tigervnc-standalone-server tigervnc-common x11-xserver-utils fonts-dejavu-core\n" +
            "mkdir -p /root/.vnc /tmp/.X11-unix\n" +
            "chmod 1777 /tmp /tmp/.X11-unix\n" +
            "printf '%s\\n' '#!/bin/sh' 'unset SESSION_MANAGER' 'unset DBUS_SESSION_BUS_ADDRESS' 'exec dbus-launch --exit-with-session " + startBin + "' > /root/.vnc/xstartup\n" +
            "chmod 755 /root/.vnc/xstartup\n" +
            "echo '=== ¡Escritorio Debian 12 instalado correctamente! ==='\n" +
            "echo 'Ya puedes volver al Panel de Inicio (⌂ Inicio) para abrir el Modo Gráfico.'\n";
    }

    public static String generateVncStartCommand(String desktopId, int width, int height) {
        String startBin = DESKTOP_LXDE.equals(desktopId) ? "startlxde" : "startxfce4";
        int w = Math.max(640, Math.min(3840, width));
        int h = Math.max(480, Math.min(2160, height));

        return "VNC_BIN=$(command -v Xtigervnc || command -v Xvnc || true)\n" +
            "if [ -z \"$VNC_BIN\" ]; then\n" +
            "    echo 'Error: Xtigervnc no encontrado. Instala el entorno gráfico primero.'\n" +
            "    exit 1\n" +
            "fi\n" +
            "if ! command -v " + startBin + " >/dev/null 2>&1; then\n" +
            "    echo 'Error: " + startBin + " no encontrado. Instala " + (DESKTOP_LXDE.equals(desktopId) ? "LXDE" : "XFCE4") + " primero.'\n" +
            "    exit 1\n" +
            "fi\n" +
            "rm -f /tmp/.X1-lock /tmp/.X11-unix/X1 2>/dev/null || true\n" +
            "mkdir -p /tmp/.X11-unix\n" +
            "chmod 1777 /tmp /tmp/.X11-unix 2>/dev/null || true\n" +
            "VNC_PID=''\n" +
            "cleanup_vnc() {\n" +
            "    [ -n \"$VNC_PID\" ] && kill \"$VNC_PID\" 2>/dev/null || true\n" +
            "    rm -f /tmp/.X1-lock /tmp/.X11-unix/X1 2>/dev/null || true\n" +
            "}\n" +
            "trap cleanup_vnc EXIT HUP INT TERM\n" +
            "\"$VNC_BIN\" " + VNC_DISPLAY + " -localhost -rfbport " + VNC_PORT
            + " -geometry " + w + "x" + h + " -depth 24 -SecurityTypes None -AlwaysShared >/tmp/debianterm-vnc.log 2>&1 &\n" +
            "VNC_PID=$!\n" +
            "i=0\n" +
            "while [ $i -lt 30 ]; do\n" +
            "    [ -S /tmp/.X11-unix/X1 ] && break\n" +
            "    i=$((i + 1))\n" +
            "    sleep 0.1\n" +
            "done\n" +
            "export DISPLAY=" + VNC_DISPLAY + "\n" +
            "export XDG_RUNTIME_DIR=/tmp\n" +
            "unset SESSION_MANAGER DBUS_SESSION_BUS_ADDRESS\n" +
            "dbus-launch --exit-with-session " + startBin + "\n" +
            "cleanup_vnc\n";
    }

    public static String generateTermuxX11StartCommand(String desktopId) {
        String startBin = DESKTOP_LXDE.equals(desktopId) ? "startlxde" : "startxfce4";
        return "if ! command -v " + startBin + " >/dev/null 2>&1; then\n" +
            "    echo 'Error: " + startBin + " no encontrado. Instala el entorno gráfico primero.'\n" +
            "    exit 1\n" +
            "fi\n" +
            "mkdir -p /tmp/.X11-unix\n" +
            "chmod 1777 /tmp /tmp/.X11-unix 2>/dev/null || true\n" +
            "export DISPLAY=" + X11_DISPLAY + "\n" +
            "export XDG_RUNTIME_DIR=/tmp\n" +
            "unset SESSION_MANAGER DBUS_SESSION_BUS_ADDRESS\n" +
            "dbus-launch --exit-with-session " + startBin + "\n";
    }

    public static String generateTermuxX11ServerCommand(
        String x11ApkPath,
        String x11LibDir,
        String sharedTmpDir
    ) {
        String qApk = ChrootManager.shellQuote(x11ApkPath);
        String qLib = ChrootManager.shellQuote(x11LibDir != null ? x11LibDir : "");
        String qTmp = ChrootManager.shellQuote(sharedTmpDir);

        return "TMP_HOST=" + qTmp + "\n" +
            "mkdir -p \"$TMP_HOST/.X11-unix\"\n" +
            "chmod 1777 \"$TMP_HOST\" \"$TMP_HOST/.X11-unix\" 2>/dev/null || true\n" +
            "rm -f \"$TMP_HOST/.X0-lock\" \"$TMP_HOST/.X11-unix/X0\" 2>/dev/null || true\n" +
            "export TMPDIR=\"$TMP_HOST\"\n" +
            "export XDG_RUNTIME_DIR=\"$TMP_HOST\"\n" +
            "export CLASSPATH=" + qApk + "\n" +
            "unset LD_LIBRARY_PATH LD_PRELOAD\n" +
            "/system/bin/app_process -Xnoimage-dex2oat " +
            (x11LibDir != null && !x11LibDir.isEmpty() ? ("-Djava.library.path=" + qLib + " ") : "") +
            "/ --nice-name=\"termux-x11 com.termux.x11 :0\" com.termux.x11.CmdEntryPoint :0 -ac >/dev/null 2>&1 &\n" +
            "X11_PID=$!\n" +
            "trap 'kill \"$X11_PID\" 2>/dev/null || true' EXIT HUP INT TERM\n" +
            "i=0\n" +
            "while [ $i -lt 30 ]; do\n" +
            "    [ -S \"$TMP_HOST/.X11-unix/X0\" ] && break\n" +
            "    i=$((i + 1))\n" +
            "    sleep 0.1\n" +
            "done\n";
    }

    public static void openTermuxX11Download(Context context) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(TERMUX_X11_APK_URL));
            context.startActivity(intent);
        } catch (Exception e) {
            try {
                context.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(TERMUX_X11_RELEASES_URL)));
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
