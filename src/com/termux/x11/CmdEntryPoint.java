/*
 * Derived from Termux:X11 (https://github.com/termux/termux-x11)
 * Licensed under GNU General Public License v3.0 (GPL-3.0).
 */
package com.termux.x11;

import android.annotation.SuppressLint;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.system.Os;
import android.util.Log;
import android.view.Surface;

import java.io.OutputStream;
import java.io.PrintStream;
import java.lang.reflect.Method;
import java.net.URL;

/**
 * Built-in Lorie X11 server entry point invoked inside Android {@code app_process}.
 * Keeps exact JNI method names and signatures expected by {@code libXlorie.so}.
 */
public class CmdEntryPoint extends ICmdEntryInterface.Stub {

    private static final String LOG_TAG = "DebianTerm-X11Cmd";

    public static final String ACTION_START = "com.debianchroot.term.ACTION_X11_START";
    public static final String EXTRA_DOCUMENT_TAG = "document_tag";
    public static final String TARGET_PACKAGE = "com.debianchroot.term";

    static Handler handler;
    public static Context ctx;

    private final Intent mIntent = createIntent();
    private boolean mBroadcastPending = false;

    static {
        try {
            if (Looper.getMainLooper() == null) {
                Looper.prepareMainLooper();
            }
        } catch (Exception e) {
            Log.e(LOG_TAG, "Failed preparing MainLooper", e);
        }
        handler = new Handler(Looper.getMainLooper() != null ? Looper.getMainLooper() : Looper.myLooper());
    }

    /**
     * Entry point called by {@code /system/bin/app_process}.
     */
    public static void main(String[] args) {
        initEntryPoint();
        handler.post(() -> new CmdEntryPoint(args != null ? args : new String[0]));
        Looper.loop();
    }

    CmdEntryPoint(String[] args) {
        if (!start(args)) {
            System.exit(1);
        }
    }

    /**
     * Called from native once {@code OsVendorInit()} resolves {@code -tag}, if supplied.
     */
    void setDocumentTag(String tag) {
        if (tag != null) {
            mIntent.putExtra(EXTRA_DOCUMENT_TAG, tag);
        }
    }

    @SuppressLint("WrongConstant")
    private Intent createIntent() {
        Bundle bundle = new Bundle();
        bundle.putBinder(null, this);

        Intent intent = new Intent(ACTION_START);
        intent.putExtra(null, bundle);
        intent.setPackage(TARGET_PACKAGE);

        int uid = Os.getuid();
        if (uid == 0 || uid == 2000) {
            intent.setFlags(0x00400000); // FLAG_RECEIVER_FROM_SHELL
        }
        return intent;
    }

    /**
     * Called from the native X server thread when a client knocks on port 7892.
     */
    private void sendBroadcast() {
        synchronized (this) {
            if (mBroadcastPending) return;
            mBroadcastPending = true;
        }
        handler.postDelayed(() -> {
            synchronized (this) {
                mBroadcastPending = false;
            }
            sendBroadcast(mIntent);
        }, 250L);
    }

    /**
     * Called from native at startup and periodically while no Activity view is connected.
     */
    private void sendBroadcastDelayed() {
        if (!connected()) {
            sendBroadcast(mIntent);
        }
        handler.postDelayed(this::sendBroadcastDelayed, 1000L);
    }

    static void sendBroadcast(Intent intent) {
        try {
            if (ctx != null) {
                ctx.sendBroadcast(intent);
                return;
            }
        } catch (Exception e) {
            Log.d(LOG_TAG, "Context.sendBroadcast unavailable, using IActivityManager reflection fallback");
        }

        try {
            String packageName = resolveSenderPackageName();
            Object am = getActivityManagerService();
            if (am == null) {
                Log.e(LOG_TAG, "IActivityManager service unavailable");
                return;
            }
            Method getSender = am.getClass().getMethod(
                "getIntentSender",
                int.class, String.class, IBinder.class, String.class, int.class,
                Intent[].class, String[].class, int.class, Bundle.class, int.class
            );
            Object sender = getSender.invoke(
                am,
                1, // ActivityManager.INTENT_SENDER_BROADCAST
                packageName,
                null,
                null,
                0,
                new Intent[] { intent },
                null,
                PendingIntent.FLAG_CANCEL_CURRENT | PendingIntent.FLAG_ONE_SHOT,
                null,
                0
            );
            if (sender == null) {
                Log.e(LOG_TAG, "getIntentSender returned null");
                return;
            }
            Method sendMethod = findSevenArgSendMethod(sender.getClass());
            if (sendMethod == null) {
                Log.e(LOG_TAG, "IIntentSender.send method not found");
                return;
            }
            sendMethod.invoke(sender, 0, intent, null, null, null, null, null);
        } catch (Exception e) {
            Log.e(LOG_TAG, "Broadcast fallback failed", e);
        }
    }

    private static Method findSevenArgSendMethod(Class<?> senderClass) {
        for (Method m : senderClass.getMethods()) {
            if ("send".equals(m.getName()) && m.getParameterTypes().length == 7) {
                return m;
            }
        }
        return null;
    }

    private static Object getActivityManagerService() {
        try {
            return Class.forName("android.app.ActivityManager")
                .getMethod("getService")
                .invoke(null);
        } catch (Exception ignored) {
        }
        try {
            return Class.forName("android.app.ActivityManagerNative")
                .getMethod("getDefault")
                .invoke(null);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String resolveSenderPackageName() {
        int uid = Os.getuid();
        try {
            Object pm = Class.forName("android.app.ActivityThread")
                .getMethod("getPackageManager")
                .invoke(null);
            if (pm != null) {
                String[] pkgs = (String[]) pm.getClass()
                    .getMethod("getPackagesForUid", int.class)
                    .invoke(pm, uid);
                if (pkgs != null && pkgs.length > 0 && pkgs[0] != null) {
                    return pkgs[0];
                }
            }
        } catch (Exception ignored) {
        }
        return (uid == 0) ? "android" : TARGET_PACKAGE;
    }

    @SuppressLint("DiscouragedPrivateApi")
    public static Context createContext() {
        PrintStream origErr = System.err;
        try {
            System.setErr(new PrintStream(new OutputStream() {
                @Override public void write(int arg0) {}
            }));
            Class<?> atClass = Class.forName("android.app.ActivityThread");
            Method getSysCtx = atClass.getMethod("getSystemContext");
            Object atInstance;
            if (System.getenv("OLD_CONTEXT") != null) {
                atInstance = atClass.getMethod("systemMain").invoke(null);
            } else {
                Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
                java.lang.reflect.Field f = unsafeClass.getDeclaredField("theUnsafe");
                f.setAccessible(true);
                Object unsafe = f.get(null);
                atInstance = unsafeClass.getMethod("allocateInstance", Class.class).invoke(unsafe, atClass);
            }
            return (Context) getSysCtx.invoke(atInstance);
        } catch (Exception e) {
            Log.e(LOG_TAG, "Failed to instantiate system context", e);
            return null;
        } finally {
            System.setErr(origErr);
        }
    }

    private static void initEntryPoint() {
        ctx = createContext();
        try {
            System.loadLibrary("Xlorie");
            return;
        } catch (UnsatisfiedLinkError ignored) {
        }

        String abi = Process.is64Bit()
            ? (Build.SUPPORTED_64_BIT_ABIS.length > 0 ? Build.SUPPORTED_64_BIT_ABIS[0] : "arm64-v8a")
            : (Build.SUPPORTED_32_BIT_ABIS.length > 0 ? Build.SUPPORTED_32_BIT_ABIS[0] : "armeabi-v7a");
        String path = "lib/" + abi + "/libXlorie.so";
        ClassLoader loader = CmdEntryPoint.class.getClassLoader();
        URL res = loader != null ? loader.getResource(path) : null;
        String libPath = res != null ? res.getFile().replace("file:", "") : null;
        if (libPath != null) {
            try {
                System.load(libPath);
                return;
            } catch (Throwable t) {
                Log.e(LOG_TAG, "Failed to dlopen " + libPath, t);
            }
        }
        System.err.println("Error: no se pudo cargar libXlorie.so");
        System.exit(134);
    }

    public native boolean start(String[] args);
    @Override public native void windowChanged(Surface surface);
    @Override public native void cursorWindowChanged(Surface surface);
    @Override public native ParcelFileDescriptor getXConnection();
    @Override public native ParcelFileDescriptor getLogcatOutput();
    @Override public native void reportFatalError(String message);
    private static native boolean connected();
}
