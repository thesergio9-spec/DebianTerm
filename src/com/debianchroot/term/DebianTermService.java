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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
    private PowerManager.WakeLock mWakeLock;
    private boolean mClientVisible = false;
    private int mSessionCounter = 0;
    private ServiceEventListener mEventListener;
    private final android.os.Handler mMainHandler = new android.os.Handler(android.os.Looper.getMainLooper());

    @Override
    public void onCreate() {
        super.onCreate();
        ensureNotificationChannel();
        startForeground(NOTIFICATION_ID, buildNotification());
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
        updateNotification();
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
        super.onDestroy();
    }

    public void setEventListener(ServiceEventListener listener) {
        mEventListener = listener;
    }

    public void setClientVisible(boolean visible) {
        mClientVisible = visible;
        if (!visible && mSessions.isEmpty()) {
            stopForeground(true);
            stopSelf();
        }
    }

    public List<TerminalSession> getSessions() {
        return mSessions;
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
        ensureForegroundStarted();
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
        return createRawSession("/system/bin/sh", shCmd, "Debian 12 (Root) #" + (++mSessionCounter), client);
    }

    public TerminalSession createProotDebianSession(TerminalSessionClient client) {
        ensureForegroundStarted();
        java.io.File script = ProotManager.getLauncherScript(this);
        java.io.File rootfs = ProotManager.getRootfsDir(this);
        if (!rootfs.isDirectory() || !new java.io.File(rootfs, "bin/bash").exists()) {
            String msgCmd = "echo 'Debian 12 (Sin Root) no está instalado en " + rootfs.getAbsolutePath()
                + ". Vuelve al panel de inicio para instalarlo.'; exec /system/bin/sh";
            return createRawSession("/system/bin/sh", msgCmd, "Debian 12 (Sin Root) #" + (++mSessionCounter), client);
        }
        try {
            if (!script.exists() || !script.canExecute()) {
                boolean noSeccomp = new TerminalSettings(this).isProotDisableSeccomp();
                script = ProotManager.installLauncherScript(this, noSeccomp);
            }
        } catch (java.io.IOException e) {
            String errCmd = "echo " + ChrootManager.shellQuote("Error preparando script PRoot: " + e.getMessage())
                + "; exec /system/bin/sh";
            return createRawSession("/system/bin/sh", errCmd, "Debian 12 (Sin Root) #" + (++mSessionCounter), client);
        }
        String shCmd = "exec " + ChrootManager.shellQuote(script.getAbsolutePath());
        return createRawSession("/system/bin/sh", shCmd, "Debian 12 (Sin Root) #" + (++mSessionCounter), client);
    }

    public TerminalSession createDebianCommandSession(
        boolean isRootMode,
        String chrootPath,
        String scriptPath,
        String innerDebianCmd,
        String label,
        TerminalSessionClient client
    ) {
        ensureForegroundStarted();
        String name = (label != null && !label.isEmpty()) ? label : ("Debian GUI #" + (++mSessionCounter));
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
            return createRawSession("/system/bin/sh", shCmd, name, client);
        } else {
            java.io.File script = ProotManager.getLauncherScript(this);
            try {
                boolean noSeccomp = new TerminalSettings(this).isProotDisableSeccomp();
                // Always refresh launcher so -c invocation is supported even for existing installs
                script = ProotManager.installLauncherScript(this, noSeccomp);
            } catch (java.io.IOException e) {
                String errCmd = "echo " + ChrootManager.shellQuote("Error preparando script PRoot: " + e.getMessage())
                    + "; exec /system/bin/sh";
                return createRawSession("/system/bin/sh", errCmd, name, client);
            }
            String shCmd = "exec " + ChrootManager.shellQuote(script.getAbsolutePath()) + " -c "
                + ChrootManager.shellQuote(innerDebianCmd);
            return createRawSession("/system/bin/sh", shCmd, name, client);
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
        return createDebianCommandSession(isRootMode, chrootPath, scriptPath, vncCmd, label, client);
    }

    public TerminalSession createDesktopX11Session(
        boolean isRootMode,
        String chrootPath,
        String scriptPath,
        String desktopId,
        DesktopManager.DesktopStatus deskStatus,
        TerminalSessionClient client
    ) {
        ensureForegroundStarted();
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
            return createRawSession("/system/bin/sh", shCmd, label, client);
        } else {
            java.io.File script = ProotManager.getLauncherScript(this);
            try {
                boolean noSeccomp = new TerminalSettings(this).isProotDisableSeccomp();
                script = ProotManager.installLauncherScript(this, noSeccomp);
            } catch (java.io.IOException e) {
                String errCmd = "echo " + ChrootManager.shellQuote("Error preparando script PRoot: " + e.getMessage())
                    + "; exec /system/bin/sh";
                return createRawSession("/system/bin/sh", errCmd, label, client);
            }
            String shCmd = x11ServerCmd + "\n" + ChrootManager.shellQuote(script.getAbsolutePath())
                + " -c " + ChrootManager.shellQuote(x11ClientCmd) + "\n";
            return createRawSession("/system/bin/sh", shCmd, label, client);
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
        ensureForegroundStarted();
        String suBin = ChrootManager.resolveWorkingSuPath();
        String shCmd = "exec " + ChrootManager.shellQuote(suBin);
        return createRawSession("/system/bin/sh", shCmd, "Android root #" + (++mSessionCounter), client);
    }

    public TerminalSession createAndroidShellSession(TerminalSessionClient client) {
        ensureForegroundStarted();
        return createRawSession("/system/bin/sh", "exec /system/bin/sh", "Android sh #" + (++mSessionCounter), client);
    }

    private TerminalSession createRawSession(String shellPath, String shCommand, String defaultName, TerminalSessionClient client) {
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
        updateNotification();
        return session;
    }

    public void removeSession(TerminalSession session) {
        if (session == null) return;
        session.finishGracefully(GRACEFUL_EXIT_TIMEOUT_MS);
        mSessions.remove(session);
        mSessionNames.remove(session);
        updateNotification();
        if (mSessions.isEmpty() && !mClientVisible) {
            releaseWakeLock();
            stopForeground(true);
            stopSelf();
        }
    }

    public void stopAllSessionsAndSelf() {
        final List<TerminalSession> toClose = new ArrayList<>(mSessions);
        mSessions.clear();
        mSessionNames.clear();
        releaseWakeLock();

        for (TerminalSession s : toClose) {
            s.finishGracefully(GRACEFUL_EXIT_TIMEOUT_MS);
        }

        if (toClose.isEmpty()) {
            stopForeground(true);
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
                stopForeground(true);
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

        int count = mSessions.size();
        String text = count == 1
            ? "1 sesión activa"
            : count + " sesiones activas";
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
