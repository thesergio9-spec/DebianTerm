/*
 * Derived from Termux:X11 (https://github.com/termux/termux-x11)
 * Licensed under GNU General Public License v3.0 (GPL-3.0).
 */
package com.termux.x11;

import android.os.Handler;
import android.os.Looper;
import android.view.Surface;

/**
 * Lightweight JNI bridge matching {@code com/termux/x11/MainActivity} expected by {@code libXlorie.so}.
 * Note: this class is NOT a registered Android Activity; DebianTerm's MainActivity delegates through
 * {@link BridgeCallbacks}.
 */
public class MainActivity {

    public interface BridgeCallbacks {
        void onWindowSurfaceChanged(Surface surface);
        void onCursorSurfaceChanged(Surface surface);
        void onClientConnectedStateChanged();
        void onSoftKeyboardRequested(boolean toggle);
    }

    /** Updated directly by native JNI on EVENT_HEARTBEAT. */
    public long lastServerHeartbeat;

    private final Handler mUiHandler = new Handler(Looper.getMainLooper());
    private volatile BridgeCallbacks mCallbacks;

    public MainActivity(BridgeCallbacks callbacks) {
        mCallbacks = callbacks;
    }

    public void setBridgeCallbacks(BridgeCallbacks callbacks) {
        mCallbacks = callbacks;
    }

    /** Called directly from native JNI on X server disconnect. */
    public void clientConnectedStateChanged() {
        final BridgeCallbacks cb = mCallbacks;
        if (cb == null) return;
        if (Looper.myLooper() == Looper.getMainLooper()) {
            cb.onClientConnectedStateChanged();
        } else {
            mUiHandler.post(cb::onClientConnectedStateChanged);
        }
    }

    void windowChanged(Surface surface) {
        BridgeCallbacks cb = mCallbacks;
        if (cb != null) {
            cb.onWindowSurfaceChanged(surface);
        }
    }

    void cursorWindowChanged(Surface surface) {
        BridgeCallbacks cb = mCallbacks;
        if (cb != null) {
            cb.onCursorSurfaceChanged(surface);
        }
    }

    void requestSoftKeyboardToggle() {
        BridgeCallbacks cb = mCallbacks;
        if (cb != null) {
            cb.onSoftKeyboardRequested(true);
        }
    }

    /** Native method registered by {@code activity.cpp} during {@code JNI_OnLoad}. */
    @SuppressWarnings("unused")
    private static native void nativeSetLaunchWindowingModeFreeform(Object options);
}
