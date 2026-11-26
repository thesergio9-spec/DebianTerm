/*
 * Derived from Termux:X11 (https://github.com/termux/termux-x11)
 * Licensed under GNU General Public License v3.0 (GPL-3.0).
 */
package com.termux.x11;

import android.annotation.SuppressLint;
import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.graphics.PointF;
import android.graphics.PorterDuff;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Region;
import android.graphics.drawable.ColorDrawable;
import android.opengl.GLES20;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.util.Log;
import android.view.Choreographer;
import android.view.Display;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;
import android.widget.FrameLayout;

import java.nio.charset.StandardCharsets;

/**
 * Hardware-accelerated native X11 framebuffer surface backed by {@code libXlorie.so}.
 * Supports 60 FPS EGL/HardwareBuffer rendering, touchpad & direct-touch pointer modes,
 * pinch-to-zoom, two-finger scroll/right-click, X11/Android clipboard sync, and IME input.
 */
@SuppressLint("ViewConstructor")
public class LorieView extends FrameLayout implements ClipboardManager.OnPrimaryClipChangedListener {

    private static final String LOG_TAG = "DebianTerm-LorieView";

    public static final int BUTTON_UNDEFINED = 0;
    public static final int BUTTON_LEFT = 1;
    public static final int BUTTON_MIDDLE = 2;
    public static final int BUTTON_RIGHT = 3;
    public static final int BUTTON_SCROLL = 4;

    private static final boolean sNativeLoaded;
    static {
        boolean ok = false;
        try {
            System.loadLibrary("Xlorie");
            ok = true;
        } catch (Throwable ignored) {
            // Ignored in desktop JVM unit-test harnesses without native Android shared libs.
        }
        sNativeLoaded = ok;
    }

    public static boolean isNativeLibraryLoaded() {
        return sNativeLoaded;
    }

    /** Required exact field name & signature read by {@code activity.cpp:172}. */
    final MainActivity activity;

    public interface SyncListener {
        void onSyncReply(int serial);
    }

    private final Handler mUiHandler = new Handler(Looper.getMainLooper());
    private final ClipboardManager mClipboard;
    private final InputMethodManager mImm;
    private final HoleSurfaceView mPictureView;
    private final HoleSurfaceView mCursorView;
    private final ScaleGestureDetector mScaleDetector;
    private final int mTouchSlopPx;

    private long mNativeContext;
    private long mLastClipboardTimestamp = System.currentTimeMillis();
    private boolean mClipboardSyncEnabled = true;
    private boolean mCommittedText = false;
    private boolean mAttachedToWindow = false;

    private int mTargetWidth = 1280;
    private int mTargetHeight = 720;
    private boolean mFitToSurface = false;
    private int mLastSurfaceWidth = 0;
    private int mLastSurfaceHeight = 0;
    private final Point mXScreenSize = new Point(1280, 720);
    private final Rect mViewport = new Rect();
    private final Rect mInputViewport = new Rect();
    private float mInputSourceLeft = 0f;
    private float mInputSourceTop = 0f;
    private float mInputSourceWidth = 1280f;
    private float mInputSourceHeight = 720f;
    private float mRendererZoom = 100f;
    private SyncListener mSyncListener;

    // Touchpad / direct-touch gesture state
    private boolean mTouchpadMode = true;
    private float mDownX;
    private float mDownY;
    private float mLastX;
    private float mLastY;
    private float mLastTwoY;
    private float mTwoFingerScrollAccum = 0f;
    private boolean mMovedBeyondSlop = false;
    private boolean mMultiTouchSeen = false;
    private boolean mLongPressFired = false;
    private boolean mDirectDragging = false;
    private long mMultiTouchDownMs = 0L;

    private final int[] mCursorState = new int[6];
    private final Choreographer.FrameCallback mCursorPlacer = new Choreographer.FrameCallback() {
        @Override
        public void doFrame(long frameTimeNanos) {
            if (!mAttachedToWindow || getVisibility() != View.VISIBLE) {
                return;
            }
            placeCursor();
            Choreographer.getInstance().postFrameCallback(this);
        }
    };

    private final Runnable mLongPressRunnable = () -> {
        if (!mMovedBeyondSlop && !mMultiTouchSeen && mTouchpadMode && connected()) {
            mLongPressFired = true;
            sendRightClickAtCursor();
        }
    };

    private final SurfaceHolder.Callback mSurfaceCallback = new SurfaceHolder.Callback() {
        @Override
        public void surfaceCreated(SurfaceHolder holder) {}

        @Override
        public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
            activity.windowChanged(holder.getSurface());
            updateViewport();
        }

        @Override
        public void surfaceDestroyed(SurfaceHolder holder) {
            activity.windowChanged(null);
        }
    };

    private final SurfaceHolder.Callback mCursorSurfaceCallback = new SurfaceHolder.Callback() {
        @Override
        public void surfaceCreated(SurfaceHolder holder) {}

        @Override
        public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
            activity.cursorWindowChanged(holder.getSurface());
        }

        @Override
        public void surfaceDestroyed(SurfaceHolder holder) {
            activity.cursorWindowChanged(null);
        }
    };

    private final InputConnection mInputConnection = new BaseInputConnection(this, false) {
        private CharSequence mComposingText = null;

        @Override
        public Editable getEditable() {
            return null;
        }

        @Override
        public CharSequence getTextBeforeCursor(int length, int flags) {
            return " ";
        }

        @Override
        public CharSequence getTextAfterCursor(int length, int flags) {
            return " ";
        }

        @Override
        public boolean setComposingText(CharSequence text, int newCursorPosition) {
            return replaceText(text, true);
        }

        @Override
        public boolean commitText(CharSequence text, int newCursorPosition) {
            return replaceText(text, false);
        }

        @Override
        public boolean finishComposingText() {
            mComposingText = null;
            return true;
        }

        @Override
        public boolean deleteSurroundingText(int beforeLength, int afterLength) {
            for (int i = 0; i < beforeLength; i++) {
                sendKeyClick(KeyEvent.KEYCODE_DEL);
            }
            for (int i = 0; i < afterLength; i++) {
                sendKeyClick(KeyEvent.KEYCODE_FORWARD_DEL);
            }
            return true;
        }

        @Override
        public boolean sendKeyEvent(KeyEvent event) {
            return LorieView.this.dispatchKeyEvent(event);
        }

        private boolean replaceText(CharSequence newText, boolean keepComposing) {
            if (!connected()) return true;
            int oldLen = mComposingText != null ? mComposingText.length() : 0;
            String oldStr = mComposingText != null ? mComposingText.toString() : "";
            String newStr = newText != null ? newText.toString() : "";
            if (oldLen > 0 && !newStr.isEmpty() && newStr.startsWith(oldStr)) {
                sendTextEvent(newStr.substring(oldLen).getBytes(StandardCharsets.UTF_8));
            } else {
                for (int i = 0; i < oldLen; i++) {
                    sendKeyClick(KeyEvent.KEYCODE_DEL);
                }
                if (!newStr.isEmpty()) {
                    sendTextEvent(newStr.getBytes(StandardCharsets.UTF_8));
                }
            }
            mComposingText = keepComposing ? newText : null;
            mCommittedText = true;
            return true;
        }

        private void sendKeyClick(int keyCode) {
            LorieView.this.sendKeyEvent(0, keyCode, true);
            LorieView.this.sendKeyEvent(0, keyCode, false);
        }
    };

    private static final class HoleSurfaceView extends SurfaceView {
        private final Rect mHole = new Rect();
        private final int[] mLocation = new int[2];

        HoleSurfaceView(Context context) {
            super(context);
        }

        void setHole(Rect rect) {
            if (mHole.equals(rect)) return;
            mHole.set(rect);
            invalidate();
        }

        private void punchHole(Canvas canvas) {
            if (mHole.isEmpty()) return;
            canvas.save();
            canvas.clipRect(mHole);
            canvas.drawColor(0, PorterDuff.Mode.CLEAR);
            canvas.restore();
        }

        @Override
        public void draw(Canvas canvas) {
            punchHole(canvas);
        }

        @Override
        protected void dispatchDraw(Canvas canvas) {
            punchHole(canvas);
        }

        @Override
        public boolean gatherTransparentRegion(Region region) {
            if (region != null && !mHole.isEmpty()) {
                getLocationInWindow(mLocation);
                region.op(
                    mLocation[0] + mHole.left,
                    mLocation[1] + mHole.top,
                    mLocation[0] + mHole.right,
                    mLocation[1] + mHole.bottom,
                    Region.Op.UNION
                );
            }
            return true;
        }
    }

    public LorieView(Context context, MainActivity.BridgeCallbacks callbacks) {
        super(context);
        // MUST be initialized before nativeInit() because activity.cpp reads view.activity inside LorieViewResources().
        this.activity = new MainActivity(callbacks);
        mClipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        mImm = (InputMethodManager) context.getSystemService(Context.INPUT_METHOD_SERVICE);
        mTouchSlopPx = ViewConfiguration.get(context).getScaledTouchSlop();

        mPictureView = new HoleSurfaceView(context);
        mCursorView = new HoleSurfaceView(context);
        mPictureView.getHolder().addCallback(mSurfaceCallback);
        mCursorView.getHolder().addCallback(mCursorSurfaceCallback);
        mCursorView.getHolder().setFormat(PixelFormat.TRANSLUCENT);
        mCursorView.setZOrderMediaOverlay(true);

        addView(mPictureView, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        addView(mCursorView, new LayoutParams(32, 32));

        setFocusable(true);
        setFocusableInTouchMode(true);
        setBackground(new ColorDrawable(Color.TRANSPARENT) {
            public boolean isStateful() { return true; }
            public boolean hasFocusStateSpecified() { return true; }
        });

        mScaleDetector = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScaleBegin(ScaleGestureDetector detector) {
                return connected() && mInputViewport.width() > 0 && mInputViewport.height() > 0;
            }

            @Override
            public boolean onScale(ScaleGestureDetector detector) {
                if (mNativeContext == 0 || mInputViewport.width() <= 0 || mInputViewport.height() <= 0) {
                    return false;
                }
                float fracX = clampFloat((detector.getFocusX() - mInputViewport.left) / (float) mInputViewport.width(), 0f, 1f);
                float fracY = clampFloat((detector.getFocusY() - mInputViewport.top) / (float) mInputViewport.height(), 0f, 1f);
                float srcX = mInputSourceLeft + fracX * mInputSourceWidth;
                float srcY = mInputSourceTop + fracY * mInputSourceHeight;
                setPinchZoomFocus(srcX, srcY, fracX, fracY);
                adjustRendererZoomByFactor(detector.getScaleFactor());
                return true;
            }

            @Override
            public void onScaleEnd(ScaleGestureDetector detector) {
                if (mNativeContext != 0) {
                    clearPinchZoomFocus();
                }
            }
        });

        if (sNativeLoaded) {
            mNativeContext = nativeInit();
        }
    }

    public void setBridgeCallbacks(MainActivity.BridgeCallbacks callbacks) {
        activity.setBridgeCallbacks(callbacks);
    }

    public boolean isTouchpadMode() {
        return mTouchpadMode;
    }

    public void setTouchpadMode(boolean touchpad) {
        mTouchpadMode = touchpad;
    }

    public void setFitToScreen(boolean fitToScreen) {
        mFitToSurface = fitToScreen;
        if (mRendererZoom > 100f) {
            resetRendererZoom();
        }
        updateViewport();
    }

    public void setTargetResolution(int width, int height) {
        mFitToSurface = false;
        if (mRendererZoom > 100f) {
            resetRendererZoom();
        }
        mTargetWidth = Math.max(480, Math.min(3840, width));
        mTargetHeight = Math.max(480, Math.min(3840, height));
        applyCvtDimensions(mTargetWidth, mTargetHeight);
        updateViewport();
    }

    private void applyCvtDimensions(int width, int height) {
        int granularity = 8;
        int rounded = Math.max(granularity, width - (width % granularity));
        mXScreenSize.set((rounded == 1360 && height == 768) ? 1366 : rounded, height);
    }

    public void sendRightClickAtCursor() {
        if (!connected()) return;
        if (mTouchpadMode) {
            sendMouseEvent(0f, 0f, BUTTON_RIGHT, true, true);
            sendMouseEvent(0f, 0f, BUTTON_RIGHT, false, true);
        } else {
            PointF cur = getRealCursorPosition();
            float x = (cur.x > 0 || cur.y > 0) ? cur.x : (mXScreenSize.x / 2f);
            float y = (cur.x > 0 || cur.y > 0) ? cur.y : (mXScreenSize.y / 2f);
            sendMouseEvent(x, y, BUTTON_RIGHT, true, false);
            sendMouseEvent(x, y, BUTTON_RIGHT, false, false);
        }
    }

    public void resetZoom() {
        resetRendererZoom();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        mAttachedToWindow = true;
        if (getVisibility() == View.VISIBLE) {
            Choreographer.getInstance().postFrameCallback(mCursorPlacer);
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        mAttachedToWindow = false;
        Choreographer.getInstance().removeFrameCallback(mCursorPlacer);
        mHandlerRemoveAll();
        if (mClipboard != null) {
            try {
                mClipboard.removePrimaryClipChangedListener(this);
            } catch (Exception ignored) {
            }
        }
        if (mNativeContext != 0) {
            nativeDestroy(mNativeContext);
            mNativeContext = 0;
        }
        super.onDetachedFromWindow();
    }

    @Override
    protected void onVisibilityChanged(View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        if (changedView == this) {
            Choreographer.getInstance().removeFrameCallback(mCursorPlacer);
            if (visibility == View.VISIBLE && mAttachedToWindow) {
                Choreographer.getInstance().postFrameCallback(mCursorPlacer);
                sendSurface();
            }
        }
    }

    private void mHandlerRemoveAll() {
        mUiHandler.removeCallbacks(mLongPressRunnable);
    }

    /** Hands the current Picture & Cursor Surfaces to the active X server. */
    public void sendSurface() {
        Surface surface = mPictureView.getHolder().getSurface();
        Surface cursorSurface = mCursorView.getHolder().getSurface();
        activity.windowChanged(surface != null && surface.isValid() ? surface : null);
        activity.cursorWindowChanged(cursorSurface != null && cursorSurface.isValid() ? cursorSurface : null);
        if (mNativeContext != 0) {
            setFiltering(mNativeContext, GLES20.GL_LINEAR);
            setFollowCursorPan(mNativeContext, true);
            setClipboardSyncEnabled(mNativeContext, mClipboardSyncEnabled, mClipboardSyncEnabled);
        }
        updateViewport();
    }

    public void triggerCallback() {
        requestFocus();
        updateViewport();
    }

    private void placeCursor() {
        if (mNativeContext == 0 || mInputSourceWidth <= 0f || mInputSourceHeight <= 0f
            || mInputViewport.width() <= 0 || mInputViewport.height() <= 0
            || !getCursorState(mNativeContext, mCursorState)) {
            return;
        }
        float scaleX = mInputViewport.width() / mInputSourceWidth;
        float scaleY = mInputViewport.height() / mInputSourceHeight;
        int w = Math.max(32, (Math.round(mCursorState[4] * scaleX) + 31) / 32 * 32);
        int h = Math.max(32, (Math.round(mCursorState[5] * scaleY) + 31) / 32 * 32);
        ViewGroup.LayoutParams params = mCursorView.getLayoutParams();
        if (params != null && (params.width != w || params.height != h)) {
            params.width = w;
            params.height = h;
            mCursorView.setLayoutParams(params);
        }
        mCursorView.setTranslationX(Math.round(
            mInputViewport.left + (mCursorState[0] - mCursorState[2] - mInputSourceLeft) * scaleX));
        mCursorView.setTranslationY(Math.round(
            mInputViewport.top + (mCursorState[1] - mCursorState[3] - mInputSourceTop) * scaleY));
    }

    private void updateViewport() {
        int surfaceW = getWidth() > 0 ? getWidth() : getMeasuredWidth();
        int surfaceH = getHeight() > 0 ? getHeight() : getMeasuredHeight();
        if (surfaceW <= 0 || surfaceH <= 0) return;

        if (surfaceW != mLastSurfaceWidth || surfaceH != mLastSurfaceHeight) {
            mLastSurfaceWidth = surfaceW;
            mLastSurfaceHeight = surfaceH;
            if (mRendererZoom > 100f) {
                mRendererZoom = 100f;
                if (mNativeContext != 0) {
                    setRendererZoom(mNativeContext, 100);
                }
            }
        }

        if (mFitToSurface) {
            int[] fit = com.debianchroot.term.DesktopManager.computeFitResolution(
                surfaceW, surfaceH, surfaceW < surfaceH);
            applyCvtDimensions(fit[0], fit[1]);
        } else {
            // Automatically orient target aspect to match landscape/portrait screen cleanly.
            int desiredW = mTargetWidth;
            int desiredH = mTargetHeight;
            if ((surfaceW < surfaceH && desiredW > desiredH) || (surfaceW > surfaceH && desiredW < desiredH)) {
                applyCvtDimensions(desiredH, desiredW);
            } else {
                applyCvtDimensions(desiredW, desiredH);
            }
        }

        int drawW = surfaceW;
        int drawH = surfaceH;
        if (!mFitToSurface && mXScreenSize.x > 0 && mXScreenSize.y > 0) {
            if ((long) drawW * mXScreenSize.y > (long) drawH * mXScreenSize.x) {
                drawW = drawH * mXScreenSize.x / mXScreenSize.y;
            } else {
                drawH = drawW * mXScreenSize.y / mXScreenSize.x;
            }
        }

        int left = (surfaceW - drawW) / 2;
        int top = (surfaceH - drawH) / 2;
        mViewport.set(left, top, left + drawW, top + drawH);

        if (mRendererZoom <= 100f || mInputSourceWidth <= 0f || mInputSourceHeight <= 0f) {
            mInputViewport.set(mViewport);
            mInputSourceLeft = 0f;
            mInputSourceTop = 0f;
            mInputSourceWidth = mXScreenSize.x;
            mInputSourceHeight = mXScreenSize.y;
        }
        mPictureView.setHole(mInputViewport);

        if (mNativeContext != 0) {
            setViewport(mNativeContext, mViewport.left, mViewport.top, mViewport.width(), mViewport.height(),
                mXScreenSize.x, mXScreenSize.y, 0);
            Display disp = getDisplay();
            int refresh = (int) Math.max(30f, disp != null ? disp.getRefreshRate() : 60f);
            sendWindowChange(mNativeContext, mXScreenSize.x, mXScreenSize.y, refresh, "builtin");
        }
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        updateViewport();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        updateViewport();
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (!connected()) {
            return true;
        }
        requestFocus();
        mScaleDetector.onTouchEvent(event);

        int action = event.getActionMasked();
        int count = event.getPointerCount();

        if (!mTouchpadMode && count == 1 && !mMultiTouchSeen) {
            handleDirectTouch(event);
            return true;
        }

        switch (action) {
            case MotionEvent.ACTION_DOWN:
                mDownX = event.getX();
                mDownY = event.getY();
                mLastX = mDownX;
                mLastY = mDownY;
                mMovedBeyondSlop = false;
                mMultiTouchSeen = false;
                mLongPressFired = false;
                mTwoFingerScrollAccum = 0f;
                mUiHandler.removeCallbacks(mLongPressRunnable);
                if (mTouchpadMode) {
                    mUiHandler.postDelayed(mLongPressRunnable, 520L);
                }
                break;

            case MotionEvent.ACTION_POINTER_DOWN:
                mUiHandler.removeCallbacks(mLongPressRunnable);
                mMultiTouchSeen = true;
                if (count == 2) {
                    mMultiTouchDownMs = System.currentTimeMillis();
                    mLastTwoY = (event.getY(0) + event.getY(1)) * 0.5f;
                    mLastX = (event.getX(0) + event.getX(1)) * 0.5f;
                    mLastY = mLastTwoY;
                }
                break;

            case MotionEvent.ACTION_MOVE:
                if (count >= 2) {
                    float midX = (event.getX(0) + event.getX(1)) * 0.5f;
                    float midY = (event.getY(0) + event.getY(1)) * 0.5f;
                    float dy = midY - mLastTwoY;
                    if (!mScaleDetector.isInProgress() && mTouchpadMode && Math.abs(dy) > 1f) {
                        mTwoFingerScrollAccum += dy;
                        float step = Math.max(16f, getResources().getDisplayMetrics().density * 16f);
                        while (mTwoFingerScrollAccum >= step) {
                            sendMouseWheelEvent(0f, -120f);
                            mTwoFingerScrollAccum -= step;
                        }
                        while (mTwoFingerScrollAccum <= -step) {
                            sendMouseWheelEvent(0f, 120f);
                            mTwoFingerScrollAccum += step;
                        }
                    }
                    mLastX = midX;
                    mLastY = midY;
                    mLastTwoY = midY;
                    mMovedBeyondSlop = true;
                } else if (count == 1 && !mMultiTouchSeen && mTouchpadMode) {
                    float x = event.getX();
                    float y = event.getY();
                    if (!mMovedBeyondSlop) {
                        float dist = (float) Math.hypot(x - mDownX, y - mDownY);
                        if (dist > mTouchSlopPx) {
                            mMovedBeyondSlop = true;
                            mUiHandler.removeCallbacks(mLongPressRunnable);
                        }
                    }
                    if (mMovedBeyondSlop) {
                        float sensitivity = (mInputSourceWidth > 0f && mInputViewport.width() > 0)
                            ? (1.25f * mInputSourceWidth / mInputViewport.width())
                            : 1.25f;
                        sendMouseEvent((x - mLastX) * sensitivity, (y - mLastY) * sensitivity,
                            BUTTON_UNDEFINED, false, true);
                    }
                    mLastX = x;
                    mLastY = y;
                }
                break;

            case MotionEvent.ACTION_POINTER_UP:
                if (mMultiTouchSeen && count == 2
                    && (System.currentTimeMillis() - mMultiTouchDownMs) < 260L
                    && Math.abs(mTwoFingerScrollAccum) < 12f
                    && !mScaleDetector.isInProgress()) {
                    sendRightClickAtCursor();
                }
                break;

            case MotionEvent.ACTION_UP:
                mUiHandler.removeCallbacks(mLongPressRunnable);
                if (!mMultiTouchSeen && !mMovedBeyondSlop && !mLongPressFired && mTouchpadMode) {
                    sendMouseEvent(0f, 0f, BUTTON_LEFT, true, true);
                    sendMouseEvent(0f, 0f, BUTTON_LEFT, false, true);
                }
                break;

            case MotionEvent.ACTION_CANCEL:
                mUiHandler.removeCallbacks(mLongPressRunnable);
                break;
        }
        return true;
    }

    private void handleDirectTouch(MotionEvent event) {
        if (mInputViewport.width() <= 0 || mInputViewport.height() <= 0) return;
        float fracX = clampFloat((event.getX() - mInputViewport.left) / (float) mInputViewport.width(), 0f, 1f);
        float fracY = clampFloat((event.getY() - mInputViewport.top) / (float) mInputViewport.height(), 0f, 1f);
        float x11x = mInputSourceLeft + fracX * mInputSourceWidth;
        float x11y = mInputSourceTop + fracY * mInputSourceHeight;

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                mDirectDragging = true;
                sendMouseEvent(x11x, x11y, BUTTON_LEFT, true, false);
                break;
            case MotionEvent.ACTION_MOVE:
                if (mDirectDragging) {
                    sendMouseEvent(x11x, x11y, BUTTON_UNDEFINED, false, false);
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (mDirectDragging) {
                    mDirectDragging = false;
                    sendMouseEvent(x11x, x11y, BUTTON_LEFT, false, false);
                }
                break;
        }
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (connected()) {
            int action = event.getAction();
            if (action == KeyEvent.ACTION_DOWN || action == KeyEvent.ACTION_UP) {
                boolean down = (action == KeyEvent.ACTION_DOWN);
                int keyCode = event.getKeyCode();
                if (keyCode != KeyEvent.KEYCODE_BACK && keyCode != KeyEvent.KEYCODE_VOLUME_UP
                    && keyCode != KeyEvent.KEYCODE_VOLUME_DOWN) {
                    if (event.getUnicodeChar() > 0 && !event.isCtrlPressed() && !event.isAltPressed()
                        && keyCode != KeyEvent.KEYCODE_ENTER && keyCode != KeyEvent.KEYCODE_DEL
                        && keyCode != KeyEvent.KEYCODE_TAB && keyCode != KeyEvent.KEYCODE_ESCAPE) {
                        if (down && event.getRepeatCount() == 0) {
                            String ch = new String(Character.toChars(event.getUnicodeChar()));
                            sendTextEvent(ch.getBytes(StandardCharsets.UTF_8));
                        }
                        return true;
                    }
                    sendKeyEvent(event.getScanCode(), keyCode, down);
                    return true;
                }
            } else if (action == KeyEvent.ACTION_MULTIPLE && event.getCharacters() != null) {
                sendTextEvent(event.getCharacters().getBytes(StandardCharsets.UTF_8));
                return true;
            }
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    public InputConnection onCreateInputConnection(EditorInfo outAttrs) {
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS;
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN | EditorInfo.IME_ACTION_NONE;
        return mInputConnection;
    }

    @Override
    public void onWindowFocusChanged(boolean hasWindowFocus) {
        super.onWindowFocusChanged(hasWindowFocus);
        if (mClipboard != null) {
            try {
                if (mClipboardSyncEnabled && hasWindowFocus) {
                    mClipboard.addPrimaryClipChangedListener(this);
                    onPrimaryClipChanged();
                } else {
                    mClipboard.removePrimaryClipChangedListener(this);
                }
            } catch (Exception ignored) {
            }
        }
    }

    @Override
    public void onPrimaryClipChanged() {
        if (!mClipboardSyncEnabled || mClipboard == null || !connected()) return;
        ClipDescription desc = mClipboard.getPrimaryClipDescription();
        long ts = (desc == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.O)
            ? (mLastClipboardTimestamp + 1L)
            : desc.getTimestamp();
        if (desc != null && mLastClipboardTimestamp < ts
            && (desc.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN)
                || desc.hasMimeType(ClipDescription.MIMETYPE_TEXT_HTML))) {
            mLastClipboardTimestamp = ts;
            sendClipboardAnnounce(mNativeContext);
        }
    }

    // -------------------------------------------------------------------------
    // Native-to-Java callbacks invoked by activity.cpp (exact names/signatures)
    // -------------------------------------------------------------------------

    /** Invoked by {@code activity.cpp:259} when X11 clipboard changes. */
    @SuppressWarnings("unused")
    void setClipboardText(final String text) {
        if (mClipboard == null) return;
        mUiHandler.post(() -> {
            mClipboard.setPrimaryClip(ClipData.newPlainText("DebianTerm X11", text != null ? text : ""));
            mLastClipboardTimestamp = System.currentTimeMillis() + 150L;
        });
    }

    /** Invoked by {@code activity.cpp:271} when an X11 app requests clipboard contents. */
    @SuppressWarnings("unused")
    void requestClipboard() {
        if (mNativeContext == 0) return;
        String text = "";
        if (mClipboardSyncEnabled && mClipboard != null) {
            ClipDescription desc = mClipboard.getPrimaryClipDescription();
            boolean isText = desc != null && (desc.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN)
                || desc.hasMimeType(ClipDescription.MIMETYPE_TEXT_HTML));
            if (isText && mClipboard.hasPrimaryClip()) {
                ClipData clip = mClipboard.getPrimaryClip();
                if (clip != null && clip.getItemCount() > 0) {
                    CharSequence seq = clip.getItemAt(0).coerceToText(getContext());
                    if (seq != null) {
                        text = seq.toString();
                    }
                }
            }
        }
        sendClipboardEvent(mNativeContext, text.getBytes(StandardCharsets.UTF_8));
    }

    /** Invoked by {@code activity.cpp:286} when the GL renderer changes zoom/pan viewport. */
    @SuppressWarnings("unused")
    private void setRendererViewport(
        final int viewportLeft, final int viewportTop,
        final int viewportWidth, final int viewportHeight,
        final float sourceLeft, final float sourceTop,
        final float sourceWidth, final float sourceHeight
    ) {
        mUiHandler.post(() -> {
            mInputViewport.set(viewportLeft, viewportTop,
                viewportLeft + viewportWidth, viewportTop + viewportHeight);
            mInputSourceLeft = sourceLeft;
            mInputSourceTop = sourceTop;
            mInputSourceWidth = sourceWidth;
            mInputSourceHeight = sourceHeight;
            mPictureView.setHole(mInputViewport);
        });
    }

    /** Invoked by {@code activity.cpp:291} on X11 focus change to clear IME composing state. */
    @SuppressWarnings("unused")
    void resetIme() {
        if (!mCommittedText || mImm == null) return;
        mUiHandler.postDelayed(() -> {
            if (Build.VERSION.SDK_INT >= 33) {
                // restartInput works across API 24-35 without SDK-level gating issues
                mImm.restartInput(LorieView.this);
            } else {
                mImm.restartInput(LorieView.this);
            }
        }, 10L);
    }

    /** Invoked by {@code activity.cpp:300} in response to {@link #sendSync(int)}. */
    @SuppressWarnings("unused")
    private void onSyncReply(int serial) {
        if (mSyncListener != null) {
            mSyncListener.onSyncReply(serial);
        }
    }

    // -------------------------------------------------------------------------
    // Public control & input API matching LorieView native bindings
    // -------------------------------------------------------------------------

    public void connect(int fd) {
        if (mNativeContext != 0) {
            connect(mNativeContext, fd);
        }
    }

    public boolean connected() {
        return mNativeContext != 0 && connected(mNativeContext);
    }

    public void startLogcat(int fd) {
        if (mNativeContext != 0) {
            startLogcat(mNativeContext, fd);
        }
    }

    public void setSyncListener(SyncListener listener) {
        mSyncListener = listener;
    }

    public void sendSync(int serial) {
        if (mNativeContext != 0) {
            sendSync(mNativeContext, serial);
        }
    }

    public int adjustRendererZoomByFactor(float factor) {
        mRendererZoom = clampFloat(mRendererZoom * factor, 100f, 400f);
        int z = Math.round(mRendererZoom);
        if (mNativeContext != 0) {
            setRendererZoom(mNativeContext, z);
        }
        return z;
    }

    public void setPinchZoomFocus(float sourceX, float sourceY, float fracX, float fracY) {
        if (mNativeContext != 0) {
            setZoomAnchor(mNativeContext, sourceX, sourceY, fracX, fracY);
        }
    }

    public void clearPinchZoomFocus() {
        if (mNativeContext != 0) {
            clearZoomAnchor(mNativeContext);
        }
    }

    public void resetRendererZoom() {
        mRendererZoom = 100f;
        if (mNativeContext != 0) {
            setRendererZoom(mNativeContext, 100);
        }
    }

    public PointF getRealCursorPosition() {
        if (mNativeContext == 0) return new PointF(0f, 0f);
        long packed = getCursorPosition(mNativeContext);
        return new PointF((float) (packed >>> 32), (float) (packed & 0xFFFFFFFFL));
    }

    public void sendMouseEvent(float x, float y, int whichButton, boolean buttonDown, boolean relative) {
        if (mNativeContext != 0) {
            sendMouseEvent(mNativeContext, x, y, whichButton, buttonDown, relative);
        }
    }

    public void sendMouseWheelEvent(float deltaX, float deltaY) {
        sendMouseEvent(deltaX, deltaY, BUTTON_SCROLL, false, true);
    }

    public void sendTouchEvent(int action, int id, int x, int y) {
        if (mNativeContext != 0) {
            sendTouchEvent(mNativeContext, action, id, x, y);
        }
    }

    public void sendStylusEvent(float x, float y, int pressure, int tiltX, int tiltY,
                                int orientation, int buttons, boolean eraser, boolean mouseMode) {
        if (mNativeContext != 0) {
            sendStylusEvent(mNativeContext, x, y, pressure, tiltX, tiltY, orientation, buttons, eraser, mouseMode);
        }
    }

    public void requestStylusEnabled(boolean enabled) {
        if (mNativeContext != 0) {
            requestStylusEnabled(mNativeContext, enabled);
        }
    }

    public void sendLockKeysState(int state) {
        if (mNativeContext != 0) {
            sendLockKeysState(mNativeContext, state);
        }
    }

    public boolean sendKeyEvent(int scanCode, int keyCode, boolean keyDown) {
        return mNativeContext != 0 && sendKeyEvent(mNativeContext, scanCode, keyCode, keyDown);
    }

    public void sendTextEvent(byte[] text) {
        if (mNativeContext != 0 && text != null) {
            sendTextEvent(mNativeContext, text);
        }
    }

    public boolean requestConnection() {
        return mNativeContext != 0 && requestConnection(mNativeContext);
    }

    private static float clampFloat(float v, float min, float max) {
        return Math.max(min, Math.min(max, v));
    }

    // -------------------------------------------------------------------------
    // Exact 28 JNI native methods registered by activity.cpp:418-605
    // (Regular JNI adapters are installed by CriticalNativeProbe when absent.)
    // -------------------------------------------------------------------------

    private native long nativeInit();
    private native void nativeDestroy(long ptr);
    private native void setFiltering(long ptr, int filtering);
    private native void setClipboardSyncEnabled(long ptr, boolean enabled, boolean ignored);
    private native void sendClipboardAnnounce(long ptr);
    private native void sendClipboardEvent(long ptr, byte[] text);
    private native void sendWindowChange(long ptr, int width, int height, int framerate, String name);
    private native void setViewport(long ptr, int x, int y, int width, int height, int expectedWidth, int expectedHeight, int hiddenBottom);
    private native void setRendererZoom(long ptr, int percent);
    private native void setZoomAnchor(long ptr, float sourceX, float sourceY, float fracX, float fracY);
    private native void clearZoomAnchor(long ptr);
    private native void setFollowCursorPan(long ptr, boolean enabled);
    private native long getCursorPosition(long ptr);
    private native boolean getCursorState(long ptr, int[] state);
    private native void sendSync(long ptr, int serial);
    private static native void connect(long ptr, int fd);
    private static native boolean connected(long ptr);
    private static native void startLogcat(long ptr, int fd);
    private native void sendMouseEvent(long ptr, float x, float y, int whichButton, boolean buttonDown, boolean relative);
    private native void sendTouchEvent(long ptr, int action, int id, int x, int y);
    private native void sendStylusEvent(long ptr, float x, float y, int pressure, int tiltX, int tiltY, int orientation, int buttons, boolean eraser, boolean mouseMode);
    private native void requestStylusEnabled(long ptr, boolean enabled);
    private native void sendLockKeysState(long ptr, int state);
    private native boolean sendKeyEvent(long ptr, int scanCode, int keyCode, boolean keyDown);
    private native void sendTextEvent(long ptr, byte[] text);
    private static native boolean requestConnection(long ptr);
    public static native long getLastInputTimestamp();
    public static native void markUserActivity();

    /** Exact inner probe class checked by {@code activity.cpp:583-587}. */
    static class CriticalNativeProbe {
        static native boolean probe(long value);
    }
}
