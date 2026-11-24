package com.debianchroot.term;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

import com.termux.terminal.TerminalSession;
import com.termux.terminal.TerminalSessionClient;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Foreground service hosting terminal sessions across Activity lifecycle changes.
 */
public final class DebianTermService extends Service {

    private static final String CHANNEL_ID = "debianterm_sessions";
    private static final int NOTIFICATION_ID = 1001;

    public static final String ACTION_STOP_ALL = "com.debianchroot.term.ACTION_STOP_ALL";
    public static final String ACTION_TOGGLE_WAKELOCK = "com.debianchroot.term.ACTION_TOGGLE_WAKELOCK";

    private static final int DEFAULT_TRANSCRIPT_ROWS = 4000;
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
    }

    private final IBinder mBinder = new LocalBinder();
    private final List<TerminalSession> mSessions = new ArrayList<>();
    private final Map<TerminalSession, String> mSessionNames = new HashMap<>();
    private final Map<TerminalSession, SessionKind> mSessionKinds = new HashMap<>();
    private PowerManager.WakeLock mWakeLock;
    private boolean mClientVisible = false;
    private boolean mIsForeground = false;
    private ServiceEventListener mEventListener;
    private final android.os.Handler mMainHandler = new android.os.Handler(android.os.Looper.getMainLooper());

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
                stopAllSessionsAndSelf();
                return START_NOT_STICKY;
            } else if (ACTION_TOGGLE_WAKELOCK.equals(action)) {
                toggleWakeLock();
            }
        }
        if (mSessions.isEmpty()) {
            if (!mIsForeground) {
                startForeground(NOTIFICATION_ID, buildNotification());
                mIsForeground = true;
            }
            stopForegroundAndSelfIfEmpty();
        } else {
            startForeground(NOTIFICATION_ID, buildNotification());
            mIsForeground = true;
        }
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }

    @Override
    public void onDestroy() {
        releaseWakeLock();
        for (TerminalSession s : new ArrayList<>(mSessions)) {
            s.finishGracefully(500L);
        }
        mSessions.clear();
        mSessionNames.clear();
        mSessionKinds.clear();
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

    public int pruneCleanlyFinishedSessions() {
        int removed = 0;
        Iterator<TerminalSession> it = mSessions.iterator();
        while (it.hasNext()) {
            TerminalSession s = it.next();
            if (!s.isRunning() && s.getExitStatus() == 0) {
                it.remove();
                mSessionNames.remove(s);
                mSessionKinds.remove(s);
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
            if (!script.exists() || !script.canExecute()) {
                boolean noSeccomp = new TerminalSettings(this).isProotDisableSeccomp();
                script = ProotManager.installLauncherScript(this, noSeccomp);
            }
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
            String qScript = ChrootManager.shellQuote(scriptPath);
            String qChroot = ChrootManager.shellQuote(chrootPath);
            String qInner = ChrootManager.shellQuote(innerDebianCmd);
            String rootCmd =
                "if [ -x " + qScript + " ]; then " +
                "  " + qScript + " exec " + qInner + "; " +
                "elif [ -x " + qChroot + "/bin/bash ]; then " +
                "  env -i HOME=/root USER=root LOGNAME=root TERM=xterm-256color LANG=C.UTF-8 " +
                "  PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin " +
                "  chroot " + qChroot + " /bin/bash -lc " + qInner + "; " +
                "else " +
                "  echo 'Chroot Debian no encontrado en " + chrootPath + "'; " +
                "fi";
            String shCmd = "exec " + ChrootManager.shellQuote(suBin) + " -c " + ChrootManager.shellQuote(rootCmd);
            return createRawSession("/system/bin/sh", shCmd, name, kind, client);
        } else {
            java.io.File script = ProotManager.getLauncherScript(this);
            try {
                boolean noSeccomp = new TerminalSettings(this).isProotDisableSeccomp();
                // Always refresh launcher so -c invocation is supported even for existing installs
                script = ProotManager.installLauncherScript(this, noSeccomp);
            } catch (java.io.IOException e) {
                String errCmd = "echo " + ChrootManager.shellQuote("Error preparando script PRoot: " + e.getMessage())
                    + "; exec /system/bin/sh";
                return createRawSession("/system/bin/sh", errCmd, name, kind, client);
            }
            String shCmd = "exec " + ChrootManager.shellQuote(script.getAbsolutePath()) + " -c "
                + ChrootManager.shellQuote(innerDebianCmd);
            return createRawSession("/system/bin/sh", shCmd, name, kind, client);
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
        String sharedTmp = isRootMode
            ? (chrootPath + "/tmp")
            : new java.io.File(ProotManager.getRootfsDir(this), "tmp").getAbsolutePath();
        String x11ServerCmd = DesktopManager.generateTermuxX11ServerCommand(
            deskStatus.termuxX11ApkPath, deskStatus.termuxX11LibDir, sharedTmp);
        String x11ClientCmd = DesktopManager.generateTermuxX11StartCommand(desktopId);
        String label = (DesktopManager.DESKTOP_LXDE.equals(desktopId) ? "LXDE X11" : "XFCE4 X11")
            + (isRootMode ? " (Root)" : " (PRoot)");

        if (isRootMode) {
            String suBin = ChrootManager.resolveWorkingSuPath();
            String qScript = ChrootManager.shellQuote(scriptPath);
            String qChroot = ChrootManager.shellQuote(chrootPath);
            String qClient = ChrootManager.shellQuote(x11ClientCmd);
            String rootCmd = x11ServerCmd + "\n" +
                "if [ -x " + qScript + " ]; then\n" +
                "  " + qScript + " exec " + qClient + "\n" +
                "else\n" +
                "  env -i HOME=/root USER=root LOGNAME=root TERM=xterm-256color LANG=C.UTF-8 " +
                "  PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin " +
                "  chroot " + qChroot + " /bin/bash -lc " + qClient + "\n" +
                "fi\n";
            String shCmd = "exec " + ChrootManager.shellQuote(suBin) + " -c " + ChrootManager.shellQuote(rootCmd);
            return createRawSession("/system/bin/sh", shCmd, label, SessionKind.DESKTOP_X11, client);
        } else {
            java.io.File script = ProotManager.getLauncherScript(this);
            try {
                boolean noSeccomp = new TerminalSettings(this).isProotDisableSeccomp();
                script = ProotManager.installLauncherScript(this, noSeccomp);
            } catch (java.io.IOException e) {
                String errCmd = "echo " + ChrootManager.shellQuote("Error preparando script PRoot: " + e.getMessage())
                    + "; exec /system/bin/sh";
                return createRawSession("/system/bin/sh", errCmd, label, SessionKind.DESKTOP_X11, client);
            }
            String shCmd = x11ServerCmd + "\n" + ChrootManager.shellQuote(script.getAbsolutePath())
                + " -c " + ChrootManager.shellQuote(x11ClientCmd) + "\n";
            return createRawSession("/system/bin/sh", shCmd, label, SessionKind.DESKTOP_X11, client);
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

    private TerminalSession createRawSession(String shellPath, String shCommand, String defaultName, SessionKind kind, TerminalSessionClient client) {
        String cwd = getFilesDir().getAbsolutePath();
        String[] args = { shellPath, "-c", shCommand };
        String[] env = {
            "TERM=xterm-256color",
            "COLORTERM=truecolor",
            "LANG=C.UTF-8",
            "HOME=" + cwd,
            "PATH=/debug_ramdisk:/sbin:/product/bin:/apex/com.android.runtime/bin:/apex/com.android.art/bin:/system_ext/bin:/system/bin:/system/xbin:/odm/bin:/vendor/bin:/vendor/xbin"
        };

        TerminalSession session = new TerminalSession(shellPath, cwd, args, env, DEFAULT_TRANSCRIPT_ROWS, client);
        session.mSessionName = defaultName;
        mSessions.add(session);
        mSessionNames.put(session, defaultName);
        mSessionKinds.put(session, kind);
        ensureForegroundStarted();
        return session;
    }

    public void removeSession(TerminalSession session) {
        if (session == null) return;
        session.finishGracefully(GRACEFUL_EXIT_TIMEOUT_MS);
        mSessions.remove(session);
        mSessionNames.remove(session);
        mSessionKinds.remove(session);
        refreshForegroundState();
    }

    public void stopAllSessionsAndSelf() {
        final List<TerminalSession> toClose = new ArrayList<>(mSessions);
        mSessions.clear();
        mSessionNames.clear();
        mSessionKinds.clear();
        releaseWakeLock();

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

    public void toggleWakeLock() {
        if (isWakeLockHeld()) {
            releaseWakeLock();
        } else {
            acquireWakeLock();
        }
        updateNotification();
    }

    private void acquireWakeLock() {
        if (mWakeLock == null) {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (pm != null) {
                mWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "DebianTerm:SessionWakeLock");
                mWakeLock.setReferenceCounted(false);
            }
        }
        if (mWakeLock != null && !mWakeLock.isHeld()) {
            mWakeLock.acquire();
        }
    }

    private void releaseWakeLock() {
        if (mWakeLock != null && mWakeLock.isHeld()) {
            mWakeLock.release();
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

    private Notification buildNotification() {
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;

        Intent openIntent = new Intent(this, MainActivity.class);
        openIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent contentPi = PendingIntent.getActivity(this, 0, openIntent, flags);

        Intent exitIntent = new Intent(this, DebianTermService.class);
        exitIntent.setAction(ACTION_STOP_ALL);
        PendingIntent exitPi = PendingIntent.getService(this, 1, exitIntent, flags);

        Intent wakeIntent = new Intent(this, DebianTermService.class);
        wakeIntent.setAction(ACTION_TOGGLE_WAKELOCK);
        PendingIntent wakePi = PendingIntent.getService(this, 2, wakeIntent, flags);

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
        if (isWakeLockHeld()) {
            text += " • Wake Lock ON";
        }

        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
            ? new Notification.Builder(this, CHANNEL_ID)
            : new Notification.Builder(this).setPriority(Notification.PRIORITY_LOW);

        builder
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(text)
            .setContentIntent(contentPi)
            .setOngoing(true)
            .setShowWhen(false)
            .addAction(0, isWakeLockHeld() ? "Soltar Wake" : getString(R.string.action_wake_lock), wakePi)
            .addAction(0, getString(R.string.action_exit), exitPi);

        return builder.build();
    }
}
