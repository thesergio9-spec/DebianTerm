package com.debianchroot.term;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.wifi.WifiManager;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

import com.termux.terminal.TerminalEmulator;
import com.termux.terminal.TerminalSession;
import com.termux.terminal.TerminalSessionClient;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Foreground service hosting terminal sessions across Activity lifecycle changes.
 */
public final class DebianTermService extends Service {

    private static final String CHANNEL_ID = "debianterm_sessions";
    private static final int NOTIFICATION_ID = 1001;

    public static final String ACTION_STOP_ALL = "com.debianchroot.term.ACTION_STOP_ALL";
    public static final String ACTION_TOGGLE_WAKELOCK = "com.debianchroot.term.ACTION_TOGGLE_WAKELOCK";
    public static final String ACTION_ACQUIRE_WAKELOCK = "com.debianchroot.term.ACTION_ACQUIRE_WAKELOCK";
    public static final String ACTION_RELEASE_WAKELOCK = "com.debianchroot.term.ACTION_RELEASE_WAKELOCK";
    public static final String ACTION_RUN_BOOT_TASKS = "com.debianchroot.term.ACTION_RUN_BOOT_TASKS";

    private static final int DEFAULT_TRANSCRIPT_ROWS = 10000;
    private static final long GRACEFUL_EXIT_TIMEOUT_MS = 1500L;

    public enum SessionKind {
        DEBIAN_ROOT,
        DEBIAN_PROOT,
        ANDROID_ROOT,
        ANDROID_SHELL,
        DESKTOP_VNC,
        DESKTOP_X11,
        COMMAND
    }

    public final class LocalBinder extends Binder {
        public DebianTermService getService() {
            return DebianTermService.this;
        }
    }

    public interface ServiceEventListener {
        void onServiceStopAllRequested();
        void onWakeLockStateChanged(boolean held);
    }

    private final IBinder mBinder = new LocalBinder();
    private final List<TerminalSession> mSessions = new ArrayList<>();
    private final Map<TerminalSession, String> mSessionNames = new HashMap<>();
    private final Map<TerminalSession, SessionKind> mSessionKinds = new HashMap<>();
    private final Map<TerminalSession, Boolean> mSessionIsRoot = new HashMap<>();
    private PowerManager.WakeLock mWakeLock;
    private WifiManager.WifiLock mWifiLock;
    private boolean mClientVisible = false;
    private boolean mIsForeground = false;
    private ServiceEventListener mEventListener;
    private final android.os.Handler mMainHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private final ExecutorService mBootExecutor = Executors.newSingleThreadExecutor();

    public static boolean isIgnoringBatteryOptimizations(Context context) {
        if (context == null) return false;
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true;
        PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        return pm != null && pm.isIgnoringBatteryOptimizations(context.getPackageName());
    }

    public static boolean areNotificationsEnabled(Context context) {
        if (context == null) return false;
        NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return true;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            return nm.areNotificationsEnabled();
        }
        return true;
    }

    /**
     * Returns the lowest positive session index (1, 2, 3...) not currently used
     * by an active session name sharing {@code prefix}.
     */
    public static int nextAvailableSessionNumber(Collection<String> existingNames, String prefix) {
        if (existingNames == null || existingNames.isEmpty() || prefix == null) {
            return 1;
        }
        Set<Integer> used = new HashSet<>();
        for (String name : existingNames) {
            if (name == null || !name.startsWith(prefix)) continue;
            String suffix = name.substring(prefix.length()).trim();
            if (suffix.isEmpty()) continue;
            try {
                int n = Integer.parseInt(suffix);
                if (n >= 1) {
                    used.add(n);
                }
            } catch (NumberFormatException ignored) {
            }
        }
        int candidate = 1;
        while (used.contains(candidate)) {
            candidate++;
        }
        return candidate;
    }

    private String allocateSessionName(String prefix) {
        pruneCleanlyFinishedSessions();
        return prefix + nextAvailableSessionNumber(mSessionNames.values(), prefix);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        ensureNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            String action = intent.getAction();
            if (ACTION_STOP_ALL.equals(action)) {
                if (!mIsForeground) {
                    startForeground(NOTIFICATION_ID, buildNotification());
                    mIsForeground = true;
                }
                stopAllSessionsAndSelf();
                return START_NOT_STICKY;
            } else if (ACTION_ACQUIRE_WAKELOCK.equals(action)) {
                new TerminalSettings(this).setAutoWakeLockEnabled(true);
                if (!mSessions.isEmpty()) {
                    setWakeLockEnabled(true, false);
                }
            } else if (ACTION_RELEASE_WAKELOCK.equals(action)) {
                new TerminalSettings(this).setAutoWakeLockEnabled(false);
                if (!mSessions.isEmpty()) {
                    setWakeLockEnabled(false, false);
                }
            } else if (ACTION_TOGGLE_WAKELOCK.equals(action)) {
                boolean willEnable = !isWakeLockHeld();
                new TerminalSettings(this).setAutoWakeLockEnabled(willEnable);
                if (!mSessions.isEmpty()) {
                    setWakeLockEnabled(willEnable, false);
                }
            } else if (ACTION_RUN_BOOT_TASKS.equals(action)) {
                if (!mIsForeground) {
                    startForeground(NOTIFICATION_ID, buildNotification());
                    mIsForeground = true;
                }
                handleBootTasksAsync();
                return START_NOT_STICKY;
            }
        }
        if (mSessions.isEmpty()) {
            if (!mIsForeground) {
                startForeground(NOTIFICATION_ID, buildNotification());
                mIsForeground = true;
            }
            stopForegroundAndSelfIfEmpty();
        } else if (!mIsForeground) {
            startForeground(NOTIFICATION_ID, buildNotification());
            mIsForeground = true;
        } else {
            updateNotification();
        }
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }

    @Override
    public void onDestroy() {
        mBootExecutor.shutdownNow();
        releaseWakeLock();
        for (TerminalSession s : new ArrayList<>(mSessions)) {
            s.finishGracefully(500L);
        }
        mSessions.clear();
        mSessionNames.clear();
        mSessionKinds.clear();
        mSessionIsRoot.clear();
        mIsForeground = false;
        super.onDestroy();
    }

    public void setEventListener(ServiceEventListener listener) {
        mEventListener = listener;
    }

    public void setClientVisible(boolean visible) {
        mClientVisible = visible;
        if (!visible) {
            pruneCleanlyFinishedSessions();
            if (mSessions.isEmpty()) {
                stopForegroundAndSelfIfEmpty();
            }
        }
    }

    public List<TerminalSession> getSessions() {
        return mSessions;
    }

    public boolean hasSession(TerminalSession session) {
        return session != null && mSessions.contains(session);
    }

    public int getRunningSessionCount() {
        int count = 0;
        for (TerminalSession s : mSessions) {
            if (s.isRunning()) {
                count++;
            }
        }
        return count;
    }

    public TerminalSession findRunningSession(SessionKind kind) {
        if (kind == null) return null;
        for (int i = mSessions.size() - 1; i >= 0; i--) {
            TerminalSession s = mSessions.get(i);
            if (kind == mSessionKinds.get(s) && s.isRunning()) {
                return s;
            }
        }
        return null;
    }

    public SessionKind getSessionKind(TerminalSession session) {
        return session != null ? mSessionKinds.get(session) : null;
    }

    public static boolean isInteractiveShellKind(SessionKind kind) {
        return kind == SessionKind.DEBIAN_ROOT
            || kind == SessionKind.DEBIAN_PROOT
            || kind == SessionKind.ANDROID_ROOT
            || kind == SessionKind.ANDROID_SHELL;
    }

    public static boolean shouldExitAppAfterSessionClosed(SessionKind closedKind, int remainingSessionsCount) {
        return remainingSessionsCount <= 0 && isInteractiveShellKind(closedKind);
    }

    public int pruneCleanlyFinishedSessions() {
        int removed = 0;
        Iterator<TerminalSession> it = mSessions.iterator();
        while (it.hasNext()) {
            TerminalSession s = it.next();
            if (!s.isRunning() && s.getExitStatus() == 0) {
                it.remove();
                mSessionNames.remove(s);
                mSessionKinds.remove(s);
                mSessionIsRoot.remove(s);
                removed++;
            }
        }
        if (removed > 0) {
            refreshForegroundState();
        }
        return removed;
    }

    public int pruneFinishedSessions(SessionKind kind) {
        int removed = 0;
        Iterator<TerminalSession> it = mSessions.iterator();
        while (it.hasNext()) {
            TerminalSession s = it.next();
            if (!s.isRunning() && (kind == null || kind == mSessionKinds.get(s))) {
                it.remove();
                mSessionNames.remove(s);
                mSessionKinds.remove(s);
                mSessionIsRoot.remove(s);
                removed++;
            }
        }
        if (removed > 0) {
            refreshForegroundState();
        }
        return removed;
    }

    public void onSessionStateChanged() {
        refreshForegroundState();
    }

    public String getSessionDisplayName(TerminalSession session) {
        if (session == null) return "Sesión";
        String custom = mSessionNames.get(session);
        if (custom != null && !custom.isEmpty()) return custom;
        String title = session.getTitle();
        if (title != null && !title.trim().isEmpty()) return title.trim();
        return "Terminal";
    }

    public void renameSession(TerminalSession session, String newName) {
        if (session != null && newName != null && !newName.trim().isEmpty()) {
            mSessionNames.put(session, newName.trim());
            session.mSessionName = newName.trim();
            updateNotification();
        }
    }

    public TerminalSession createDebianSession(String chrootPath, String scriptPath, TerminalSessionClient client) {
        String suBin = ChrootManager.resolveWorkingSuPath();
        String qScript = ChrootManager.shellQuote(scriptPath);
        String qChroot = ChrootManager.shellQuote(chrootPath);

        // Se ejecuta directamente dentro de root (su) para no ser bloqueado por SELinux untrusted_app
        String rootCmd =
            "if [ -x " + qScript + " ]; then " +
            "  exec " + qScript + "; " +
            "elif [ -x " + qChroot + "/bin/bash ]; then " +
            "  echo 'Aviso: script no encontrado en " + scriptPath + ", arrancando Debian directamente...'; " +
            "  exec chroot " + qChroot + " /bin/bash -l; " +
            "else " +
            "  echo 'Chroot Debian no encontrado en " + chrootPath + ". Usa el panel Chroot para instalarlo.'; " +
            "  exec /system/bin/sh; " +
            "fi";

        String shCmd = "exec " + ChrootManager.shellQuote(suBin) + " -c " + ChrootManager.shellQuote(rootCmd);
        return createRawSession("/system/bin/sh", shCmd, allocateSessionName("Debian 12 (Root) #"), SessionKind.DEBIAN_ROOT, client);
    }

    public TerminalSession createProotDebianSession(TerminalSessionClient client) {
        java.io.File script = ProotManager.getLauncherScript(this);
        java.io.File rootfs = ProotManager.getRootfsDir(this);
        if (!rootfs.isDirectory() || !new java.io.File(rootfs, "bin/bash").exists()) {
            String msgCmd = "echo 'Debian 12 (Sin Root) no está instalado en " + rootfs.getAbsolutePath()
                + ". Vuelve al panel de inicio para instalarlo.'; exec /system/bin/sh";
            return createRawSession("/system/bin/sh", msgCmd, allocateSessionName("Debian 12 (Sin Root) #"), SessionKind.DEBIAN_PROOT, client);
        }
        try {
            boolean noSeccomp = new TerminalSettings(this).isProotDisableSeccomp();
            script = ProotManager.installLauncherScript(this, noSeccomp);
            ProotManager.setupStorageLinks(rootfs);
        } catch (java.io.IOException e) {
            String errCmd = "echo " + ChrootManager.shellQuote("Error preparando script PRoot: " + e.getMessage())
                + "; exec /system/bin/sh";
            return createRawSession("/system/bin/sh", errCmd, allocateSessionName("Debian 12 (Sin Root) #"), SessionKind.DEBIAN_PROOT, client);
        }
        String shCmd = "exec " + ChrootManager.shellQuote(script.getAbsolutePath());
        return createRawSession("/system/bin/sh", shCmd, allocateSessionName("Debian 12 (Sin Root) #"), SessionKind.DEBIAN_PROOT, client);
    }

    public TerminalSession createDebianCommandSession(
        boolean isRootMode,
        String chrootPath,
        String scriptPath,
        String innerDebianCmd,
        String label,
        TerminalSessionClient client
    ) {
        return createDebianCommandSession(
            isRootMode, chrootPath, scriptPath, innerDebianCmd, label, SessionKind.COMMAND, client);
    }

    private TerminalSession createDebianCommandSession(
        boolean isRootMode,
        String chrootPath,
        String scriptPath,
        String innerDebianCmd,
        String label,
        SessionKind kind,
        TerminalSessionClient client
    ) {
        String name = (label != null && !label.isEmpty()) ? label : allocateSessionName("Debian GUI #");
        if (isRootMode) {
            String suBin = ChrootManager.resolveWorkingSuPath();
            String rootCmd = ChrootManager.buildRootExecCommand(chrootPath, scriptPath, innerDebianCmd);
            String shCmd = "exec " + ChrootManager.shellQuote(suBin) + " -c " + ChrootManager.shellQuote(rootCmd);
            return markRoot(createRawSession("/system/bin/sh", shCmd, name, kind, client), isRootMode);
        } else {
            java.io.File script = ProotManager.getLauncherScript(this);
            try {
                boolean noSeccomp = new TerminalSettings(this).isProotDisableSeccomp();
                // Always refresh launcher so -c invocation is supported even for existing installs
                script = ProotManager.installLauncherScript(this, noSeccomp);
            } catch (java.io.IOException e) {
                String errCmd = "echo " + ChrootManager.shellQuote("Error preparando script PRoot: " + e.getMessage())
                    + "; exec /system/bin/sh";
                return markRoot(createRawSession("/system/bin/sh", errCmd, name, kind, client), isRootMode);
            }
            String shCmd = "exec " + ChrootManager.shellQuote(script.getAbsolutePath()) + " -c "
                + ChrootManager.shellQuote(innerDebianCmd);
            return markRoot(createRawSession("/system/bin/sh", shCmd, name, kind, client), isRootMode);
        }
    }

    public TerminalSession createDesktopVncSession(
        boolean isRootMode,
        String chrootPath,
        String scriptPath,
        String desktopId,
        int width,
        int height,
        TerminalSessionClient client
    ) {
        String vncCmd = DesktopManager.generateVncStartCommand(desktopId, width, height);
        String label = (DesktopManager.DESKTOP_LXDE.equals(desktopId) ? "LXDE VNC" : "XFCE4 VNC")
            + (isRootMode ? " (Root)" : " (PRoot)");
        return createDebianCommandSession(
            isRootMode, chrootPath, scriptPath, vncCmd, label, SessionKind.DESKTOP_VNC, client);
    }

    public TerminalSession createDesktopX11Session(
        boolean isRootMode,
        String chrootPath,
        String scriptPath,
        String desktopId,
        DesktopManager.DesktopStatus deskStatus,
        TerminalSessionClient client
    ) {
        String rootfsHost = isRootMode
            ? chrootPath
            : ProotManager.getRootfsDir(this).getAbsolutePath();
        String sharedTmp = rootfsHost + "/tmp";
        android.content.pm.ApplicationInfo appInfo = getApplicationInfo();
        String apkPath = (deskStatus != null && deskStatus.termuxX11ApkPath != null && !deskStatus.termuxX11ApkPath.isEmpty())
            ? deskStatus.termuxX11ApkPath
            : (appInfo != null && appInfo.sourceDir != null ? appInfo.sourceDir : "");
        String libDir = (deskStatus != null && deskStatus.termuxX11LibDir != null && !deskStatus.termuxX11LibDir.isEmpty())
            ? deskStatus.termuxX11LibDir
            : (appInfo != null && appInfo.nativeLibraryDir != null ? appInfo.nativeLibraryDir : "");
        String x11ServerCmd = DesktopManager.generateBuiltInX11ServerCommand(
            apkPath, libDir, sharedTmp, rootfsHost);
        String x11ClientCmd = DesktopManager.generateTermuxX11StartCommand(desktopId);
        String label = (DesktopManager.DESKTOP_LXDE.equals(desktopId) ? "LXDE X11" : "XFCE4 X11")
            + (isRootMode ? " (Root)" : " (PRoot)");

        if (isRootMode) {
            String suBin = ChrootManager.resolveWorkingSuPath();
            StringBuilder envPrep = new StringBuilder();
            for (String key : ANDROID_RUNTIME_ENV_KEYS) {
                String val = System.getenv(key);
                if (val != null && !val.isEmpty()) {
                    envPrep.append("export ").append(key).append('=').append(ChrootManager.shellQuote(val)).append('\n');
                }
            }
            // Prepare chroot mounts first so built-in X11 creates .X11-unix/X0 inside the mounted tmpfs
            String rootCmd = ChrootManager.buildEnsureRootMountsSnippet(chrootPath)
                + envPrep.toString()
                + x11ServerCmd + "\n"
                + ChrootManager.buildRootExecCommand(chrootPath, scriptPath, x11ClientCmd);
            String shCmd = "exec " + ChrootManager.shellQuote(suBin) + " -c " + ChrootManager.shellQuote(rootCmd);
            return markRoot(createRawSession("/system/bin/sh", shCmd, label, SessionKind.DESKTOP_X11, client), isRootMode);
        } else {
            java.io.File script = ProotManager.getLauncherScript(this);
            try {
                boolean noSeccomp = new TerminalSettings(this).isProotDisableSeccomp();
                script = ProotManager.installLauncherScript(this, noSeccomp);
            } catch (java.io.IOException e) {
                String errCmd = "echo " + ChrootManager.shellQuote("Error preparando script PRoot: " + e.getMessage())
                    + "; exec /system/bin/sh";
                return markRoot(createRawSession("/system/bin/sh", errCmd, label, SessionKind.DESKTOP_X11, client), isRootMode);
            }
            String shCmd = x11ServerCmd + "\n" + ChrootManager.shellQuote(script.getAbsolutePath())
                + " -c " + ChrootManager.shellQuote(x11ClientCmd) + "\n";
            return markRoot(createRawSession("/system/bin/sh", shCmd, label, SessionKind.DESKTOP_X11, client), isRootMode);
        }
    }

    public TerminalSession createDesktopInstallSession(
        boolean isRootMode,
        String chrootPath,
        String scriptPath,
        String desktopId,
        TerminalSessionClient client
    ) {
        String installCmd = DesktopManager.generateDesktopInstallCommand(desktopId)
            + "\nexec /bin/bash -l\n";
        String label = "Instalar " + (DesktopManager.DESKTOP_LXDE.equals(desktopId) ? "LXDE" : "XFCE4")
            + (isRootMode ? " (Root)" : " (Sin Root)");
        return createDebianCommandSession(isRootMode, chrootPath, scriptPath, installCmd, label, client);
    }

    public TerminalSession createAndroidRootSession(TerminalSessionClient client) {
        String suBin = ChrootManager.resolveWorkingSuPath();
        String shCmd = "exec " + ChrootManager.shellQuote(suBin);
        return createRawSession("/system/bin/sh", shCmd, allocateSessionName("Android root #"), SessionKind.ANDROID_ROOT, client);
    }

    public TerminalSession createAndroidShellSession(TerminalSessionClient client) {
        return createRawSession("/system/bin/sh", "exec /system/bin/sh", allocateSessionName("Android sh #"), SessionKind.ANDROID_SHELL, client);
    }

    private static final String[] ANDROID_RUNTIME_ENV_KEYS = {
        "BOOTCLASSPATH",
        "DEX2OATBOOTCLASSPATH",
        "SYSTEMSERVERCLASSPATH",
        "ANDROID_ROOT",
        "ANDROID_DATA",
        "ANDROID_STORAGE",
        "ANDROID_ASSETS",
        "ANDROID_ART_ROOT",
        "ANDROID_RUNTIME_ROOT",
        "ANDROID_I18N_ROOT",
        "ANDROID_TZDATA_ROOT"
    };

    public static String[] buildRawSessionEnv(String cwd) {
        List<String> envList = new ArrayList<>();
        envList.add("TERM=xterm-256color");
        envList.add("COLORTERM=truecolor");
        envList.add("LANG=C.UTF-8");
        envList.add("HOME=" + (cwd != null ? cwd : "/"));
        envList.add("PATH=/debug_ramdisk:/sbin:/product/bin:/apex/com.android.runtime/bin:/apex/com.android.art/bin:/system_ext/bin:/system/bin:/system/xbin:/odm/bin:/vendor/bin:/vendor/xbin");
        for (String key : ANDROID_RUNTIME_ENV_KEYS) {
            String val = System.getenv(key);
            if (val != null && !val.isEmpty()) {
                envList.add(key + "=" + val);
            }
        }
        return envList.toArray(new String[0]);
    }

    private TerminalSession createRawSession(String shellPath, String shCommand, String defaultName, SessionKind kind, TerminalSessionClient client) {
        String cwd = getFilesDir().getAbsolutePath();
        String[] args = { shellPath, "-c", shCommand };
        String[] env = buildRawSessionEnv(cwd);

        TerminalSession session = new TerminalSession(shellPath, cwd, args, env, DEFAULT_TRANSCRIPT_ROWS, client);
        session.mSessionName = defaultName;
        mSessions.add(session);
        mSessionNames.put(session, defaultName);
        mSessionKinds.put(session, kind);
        if (!isWakeLockHeld() && new TerminalSettings(this).isAutoWakeLockEnabled()) {
            acquireWakeLock();
            notifyWakeLockChanged();
        }
        ensureForegroundStarted();
        return session;
    }

    public void removeSession(TerminalSession session) {
        removeSessionInternal(session, true);
    }

    /** Removes a session without sweeping X11/VNC processes (used right before relaunching a desktop). */
    public void removeSessionKeepDesktopProcesses(TerminalSession session) {
        removeSessionInternal(session, false);
    }

    private void removeSessionInternal(TerminalSession session, boolean stopDesktopProcesses) {
        if (session == null) return;
        SessionKind kind = mSessionKinds.get(session);
        boolean wasRoot = Boolean.TRUE.equals(mSessionIsRoot.get(session));
        session.finishGracefully(GRACEFUL_EXIT_TIMEOUT_MS);
        mSessions.remove(session);
        mSessionNames.remove(session);
        mSessionKinds.remove(session);
        mSessionIsRoot.remove(session);
        refreshForegroundState();
        if (stopDesktopProcesses && (kind == SessionKind.DESKTOP_X11 || kind == SessionKind.DESKTOP_VNC)) {
            stopDesktopProcessesAsync(wasRoot, kind == SessionKind.DESKTOP_X11);
        }
    }

    public boolean isSessionRootMode(TerminalSession session) {
        return Boolean.TRUE.equals(mSessionIsRoot.get(session));
    }

    private TerminalSession markRoot(TerminalSession session, boolean isRootMode) {
        mSessionIsRoot.put(session, isRootMode);
        return session;
    }

    /**
     * Root (su) X11/VNC servers run as UID 0 and cannot be signalled from the app UID, so they would
     * survive "Detener" and block the next launch ("server already running"). This sweeps them.
     */
    public static void stopDesktopProcessesAsync(final boolean isRootMode, final boolean isX11) {
        Thread t = new Thread(() -> {
            String cmd = DesktopManager.buildCleanupDisplayProcessesSnippet(
                isX11 ? DesktopManager.X11_DISPLAY : DesktopManager.VNC_DISPLAY, true);
            if (isRootMode) {
                try {
                    ChrootManager.execSu(cmd, 8);
                } catch (Throwable ignored) {
                }
            }
            Process p = null;
            try {
                p = new ProcessBuilder("/system/bin/sh", "-c", cmd).redirectErrorStream(true).start();
                try {
                    p.getOutputStream().close();
                } catch (java.io.IOException ignored) {
                }
                long deadline = System.currentTimeMillis() + 4000L;
                while (System.currentTimeMillis() < deadline) {
                    try {
                        p.exitValue();
                        break;
                    } catch (IllegalThreadStateException running) {
                        Thread.sleep(80L);
                    }
                }
            } catch (Throwable ignored) {
            } finally {
                if (p != null) {
                    p.destroy();
                }
            }
        }, "DebianTerm-DesktopCleanup");
        t.setDaemon(true);
        t.start();
    }

    public void stopAllSessionsAndSelf() {
        final List<TerminalSession> toClose = new ArrayList<>(mSessions);
        boolean hadX11 = false;
        boolean hadVnc = false;
        boolean x11Root = false;
        boolean vncRoot = false;
        for (TerminalSession s : toClose) {
            SessionKind k = mSessionKinds.get(s);
            if (k == SessionKind.DESKTOP_X11) {
                hadX11 = true;
                x11Root = Boolean.TRUE.equals(mSessionIsRoot.get(s));
            } else if (k == SessionKind.DESKTOP_VNC) {
                hadVnc = true;
                vncRoot = Boolean.TRUE.equals(mSessionIsRoot.get(s));
            }
        }
        mSessions.clear();
        mSessionNames.clear();
        mSessionKinds.clear();
        mSessionIsRoot.clear();
        if (hadX11) stopDesktopProcessesAsync(x11Root, true);
        if (hadVnc) stopDesktopProcessesAsync(vncRoot, false);
        releaseWakeLock();
        notifyWakeLockChanged();

        for (TerminalSession s : toClose) {
            s.finishGracefully(GRACEFUL_EXIT_TIMEOUT_MS);
        }

        if (toClose.isEmpty()) {
            if (mIsForeground) {
                stopForeground(true);
                mIsForeground = false;
            }
            stopSelf();
            if (mEventListener != null) {
                mEventListener.onServiceStopAllRequested();
            }
            return;
        }

        Thread waiter = new Thread(() -> {
            long deadline = System.currentTimeMillis() + GRACEFUL_EXIT_TIMEOUT_MS + 250L;
            while (System.currentTimeMillis() < deadline) {
                boolean anyAlive = false;
                for (TerminalSession s : toClose) {
                    if (s.isRunning()) {
                        anyAlive = true;
                        break;
                    }
                }
                if (!anyAlive) {
                    break;
                }
                try {
                    Thread.sleep(50L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            mMainHandler.post(() -> {
                if (mIsForeground) {
                    stopForeground(true);
                    mIsForeground = false;
                }
                stopSelf();
                if (mEventListener != null) {
                    mEventListener.onServiceStopAllRequested();
                }
            });
        }, "DebianTermGracefulStopAll");
        waiter.setDaemon(true);
        waiter.start();
    }

    public boolean isWakeLockHeld() {
        return mWakeLock != null && mWakeLock.isHeld();
    }

    public boolean isWifiLockHeld() {
        return mWifiLock != null && mWifiLock.isHeld();
    }

    public void setWakeLockEnabled(boolean enabled) {
        setWakeLockEnabled(enabled, true);
    }

    private void setWakeLockEnabled(boolean enabled, boolean postNotification) {
        if (enabled) {
            acquireWakeLock();
        } else {
            releaseWakeLock();
        }
        if (postNotification) {
            updateNotification();
        }
        notifyWakeLockChanged();
    }

    public void toggleWakeLock() {
        boolean willEnable = !isWakeLockHeld();
        new TerminalSettings(this).setAutoWakeLockEnabled(willEnable);
        setWakeLockEnabled(willEnable, true);
    }

    @SuppressWarnings("deprecation")
    private void acquireWakeLock() {
        if (mWakeLock == null) {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (pm != null) {
                try {
                    mWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "DebianTerm:SessionWakeLock");
                    mWakeLock.setReferenceCounted(false);
                } catch (RuntimeException ignored) {
                    mWakeLock = null;
                }
            }
        }
        if (mWakeLock != null && !mWakeLock.isHeld()) {
            try {
                mWakeLock.acquire();
            } catch (RuntimeException ignored) {
            }
        }

        if (mWifiLock == null) {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
            if (wm != null) {
                try {
                    mWifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL, "DebianTerm:SessionWifiLock");
                    mWifiLock.setReferenceCounted(false);
                } catch (RuntimeException ignored) {
                    mWifiLock = null;
                }
            }
        }
        if (mWifiLock != null && !mWifiLock.isHeld()) {
            try {
                mWifiLock.acquire();
            } catch (RuntimeException ignored) {
            }
        }
    }

    private void releaseWakeLock() {
        if (mWakeLock != null && mWakeLock.isHeld()) {
            try {
                mWakeLock.release();
            } catch (RuntimeException ignored) {
            }
        }
        if (mWifiLock != null && mWifiLock.isHeld()) {
            try {
                mWifiLock.release();
            } catch (RuntimeException ignored) {
            }
        }
    }

    private void notifyWakeLockChanged() {
        if (mEventListener != null) {
            final boolean held = isWakeLockHeld();
            mMainHandler.post(() -> {
                if (mEventListener != null) {
                    mEventListener.onWakeLockStateChanged(held);
                }
            });
        }
    }

    private void ensureForegroundStarted() {
        Intent self = new Intent(this, DebianTermService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(self);
        } else {
            startService(self);
        }
        startForeground(NOTIFICATION_ID, buildNotification());
        mIsForeground = true;
    }

    private void stopForegroundAndSelfIfEmpty() {
        if (!mSessions.isEmpty()) return;
        releaseWakeLock();
        notifyWakeLockChanged();
        if (mIsForeground) {
            stopForeground(true);
            mIsForeground = false;
        }
        stopSelf();
    }

    private void refreshForegroundState() {
        if (mSessions.isEmpty()) {
            stopForegroundAndSelfIfEmpty();
        } else if (mIsForeground) {
            updateNotification();
        }
    }

    private void ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) {
                NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.notification_channel_name),
                    NotificationManager.IMPORTANCE_LOW
                );
                ch.setShowBadge(false);
                ch.setSound(null, null);
                nm.createNotificationChannel(ch);
            }
        }
    }

    private void updateNotification() {
        if (!mIsForeground || mSessions.isEmpty()) {
            return;
        }
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) {
            nm.notify(NOTIFICATION_ID, buildNotification());
        }
    }

    public static String formatWakeNotificationLabel(boolean wakeHeld, boolean wifiHeld) {
        if (wakeHeld) {
            return wifiHeld ? "⚡ Wake: CPU+WiFi" : "⚡ Wake: CPU";
        }
        return "Wake: OFF";
    }

    public static String formatWakeActionLabel(boolean wakeHeld) {
        return wakeHeld ? "Soltar Wake" : "Activar Wake";
    }

    private PendingIntent createServiceActionPendingIntent(int requestCode, Intent intent, int flags) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !mIsForeground) {
            return PendingIntent.getForegroundService(this, requestCode, intent, flags);
        }
        return PendingIntent.getService(this, requestCode, intent, flags);
    }

    private Notification buildNotification() {
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;

        Intent openIntent = new Intent(this, MainActivity.class);
        openIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent contentPi = PendingIntent.getActivity(this, 0, openIntent, flags);

        Intent exitIntent = new Intent(this, DebianTermService.class);
        exitIntent.setAction(ACTION_STOP_ALL);
        PendingIntent exitPi = createServiceActionPendingIntent(1, exitIntent, flags);

        boolean wakeHeld = isWakeLockHeld();
        boolean wifiHeld = isWifiLockHeld();

        Intent wakeIntent = new Intent(this, DebianTermService.class);
        wakeIntent.setAction(wakeHeld ? ACTION_RELEASE_WAKELOCK : ACTION_ACQUIRE_WAKELOCK);
        PendingIntent wakePi = createServiceActionPendingIntent(wakeHeld ? 3 : 2, wakeIntent, flags);

        int running = getRunningSessionCount();
        int total = mSessions.size();
        String text;
        if (running > 0) {
            text = running == 1 ? "1 sesión activa" : running + " sesiones activas";
        } else if (total > 0) {
            text = total == 1 ? "1 sesión finalizada" : total + " sesiones finalizadas";
        } else {
            text = "Sin sesiones activas";
        }
        text += " • " + formatWakeNotificationLabel(wakeHeld, wifiHeld);

        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
            ? new Notification.Builder(this, CHANNEL_ID)
            : new Notification.Builder(this).setPriority(Notification.PRIORITY_LOW);

        builder
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(text)
            .setContentIntent(contentPi)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .addAction(new Notification.Action.Builder(
                (android.graphics.drawable.Icon) null,
                formatWakeActionLabel(wakeHeld),
                wakePi
            ).build())
            .addAction(new Notification.Action.Builder(
                (android.graphics.drawable.Icon) null,
                getString(R.string.action_exit),
                exitPi
            ).build());

        return builder.build();
    }

    private void handleBootTasksAsync() {
        for (TerminalSession s : mSessions) {
            String name = mSessionNames.get(s);
            if (name != null && name.startsWith("Autoarranque (") && s.isRunning()) {
                return;
            }
        }

        mBootExecutor.execute(() -> {
            final TerminalSettings settings = new TerminalSettings(DebianTermService.this);
            if (!settings.isBootAutostartEnabled()) {
                mMainHandler.post(this::stopForegroundAndSelfIfEmpty);
                return;
            }

            final String targetPref = settings.getBootAutostartTarget();
            final String chrootPath = settings.getChrootPath();
            final String scriptPath = settings.getEntryScriptPath();

            boolean hasRoot = false;
            boolean rootInstalled = false;
            if (!TerminalSettings.BOOT_TARGET_PROOT.equals(targetPref)) {
                ChrootManager.ChrootStatus rootSt = ChrootManager.inspectStatus(chrootPath, scriptPath);
                hasRoot = rootSt.hasRoot;
                rootInstalled = rootSt.rootfsExists;
            }

            File prootDir = ProotManager.getRootfsDir(DebianTermService.this);
            boolean prootInstalled = prootDir.isDirectory() && new File(prootDir, "bin/bash").exists();

            final Boolean useRoot = BootReceiver.resolveBootMode(
                targetPref, settings.getInstallMode(), hasRoot, rootInstalled, prootInstalled);
            if (useRoot == null) {
                mMainHandler.post(this::stopForegroundAndSelfIfEmpty);
                return;
            }

            final String bootCmd = BootReceiver.buildBootCommand(
                settings.getBootAutostartScript(), settings.isBootAutostartKeepSession());
            final String label = "Autoarranque (" + (useRoot ? "Root" : "Sin Root") + ")";

            mMainHandler.post(() -> {
                for (TerminalSession s : mSessions) {
                    String n = mSessionNames.get(s);
                    if (n != null && n.startsWith("Autoarranque (") && s.isRunning()) {
                        return;
                    }
                }
                TerminalSession session = createDebianCommandSession(
                    useRoot,
                    chrootPath,
                    scriptPath,
                    bootCmd,
                    label,
                    SessionKind.COMMAND,
                    createHeadlessBootClient()
                );
                if (session.getEmulator() == null) {
                    session.initializeEmulator(80, 24, 16, 32);
                }
                TerminalEmulator em = session.getEmulator();
                if (em != null) {
                    em.setSixelEnabled(settings.isSixelEnabled());
                }
                if (settings.isBootAutostartWakeLock() && !isWakeLockHeld()) {
                    setWakeLockEnabled(true);
                } else {
                    updateNotification();
                }
            });
        });
    }

    private TerminalSessionClient createHeadlessBootClient() {
        return new TerminalSessionClient() {
            @Override public void onTextChanged(TerminalSession changedSession) {}
            @Override
            public void onTitleChanged(TerminalSession changedSession) {
                updateNotification();
            }
            @Override
            public void onSessionFinished(TerminalSession finishedSession) {
                if (finishedSession != null && finishedSession.getExitStatus() == 0) {
                    removeSession(finishedSession);
                } else {
                    onSessionStateChanged();
                }
            }
            @Override public void onCopyTextToClipboard(TerminalSession session, String text) {}
            @Override public void onPasteTextFromClipboard(TerminalSession session) {}
            @Override public void onBell(TerminalSession session) {}
            @Override public void onColorsChanged(TerminalSession session) {}
            @Override public void onTerminalCursorStateChange(boolean state) {}
            @Override public Integer getTerminalCursorStyle() { return TerminalEmulator.TERMINAL_CURSOR_STYLE_BLOCK; }
            @Override public void logError(String tag, String message) {}
            @Override public void logWarn(String tag, String message) {}
            @Override public void logInfo(String tag, String message) {}
            @Override public void logDebug(String tag, String message) {}
            @Override public void logVerbose(String tag, String message) {}
            @Override public void logStackTraceWithMessage(String tag, String message, Exception e) {}
            @Override public void logStackTrace(String tag, Exception e) {}
        };
    }
}
