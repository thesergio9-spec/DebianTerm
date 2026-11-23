package com.debianchroot.term;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;

/**
 * Hardware-accelerated VNC framebuffer canvas supporting touchpad & direct-touch pointer modes,
 * pinch-to-zoom, pan, two-finger scroll/right-click, and IME soft-keyboard X11 keysyms.
 */
public final class VncCanvasView extends View {

    private final Object mBitmapLock = new Object();
    private final Matrix mDrawMatrix = new Matrix();
    private final Paint mBitmapPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint mCursorFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mCursorStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path mCursorPath = new Path();
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final ScaleGestureDetector mScaleDetector;
    private final int mTouchSlopPx;

    private Bitmap mBitmap;
    private RfbClient mClient;
    private int mFbWidth = 0;
    private int mFbHeight = 0;

    private boolean mTouchpadMode = true;
    private float mUserScale = 1.0f;
    private float mPanX = 0f;
    private float mPanY = 0f;
    private float mCursorX = 0f;
    private float mCursorY = 0f;

    // Touch gesture tracking
    private float mDownX;
    private float mDownY;
    private float mLastX;
    private float mLastY;
    private float mLastTwoY;
    private float mTwoFingerScrollAccum = 0f;
    private boolean mMovedBeyondSlop = false;
    private boolean mMultiTouchSeen = false;
    private boolean mLongPressFired = false;
    private long mMultiTouchDownMs = 0L;

    private final Runnable mLongPressRunnable = () -> {
        if (!mMovedBeyondSlop && !mMultiTouchSeen && mTouchpadMode) {
            mLongPressFired = true;
            sendRightClickAtCursor();
        }
    };

    public VncCanvasView(Context context) {
        super(context);
        setFocusable(true);
        setFocusableInTouchMode(true);
        setBackgroundColor(Color.parseColor("#0D1117"));

        mTouchSlopPx = ViewConfiguration.get(context).getScaledTouchSlop();
        mCursorFill.setColor(Color.WHITE);
        mCursorFill.setStyle(Paint.Style.FILL);
        mCursorStroke.setColor(Color.BLACK);
        mCursorStroke.setStyle(Paint.Style.STROKE);
        mCursorStroke.setStrokeWidth(2.2f);

        mScaleDetector = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScale(ScaleGestureDetector detector) {
                float factor = detector.getScaleFactor();
                float next = Math.max(1.0f, Math.min(4.0f, mUserScale * factor));
                if (next != mUserScale) {
                    float ratio = next / mUserScale;
                    mPanX = detector.getFocusX() - (detector.getFocusX() - mPanX) * ratio;
                    mPanY = detector.getFocusY() - (detector.getFocusY() - mPanY) * ratio;
                    mUserScale = next;
                    clampPan();
                    invalidate();
                }
                return true;
            }
        });
    }

    public void attachClient(RfbClient client) {
        mClient = client;
    }

    public void detachClient() {
        mClient = null;
    }

    public boolean isTouchpadMode() {
        return mTouchpadMode;
    }

    public void setTouchpadMode(boolean touchpad) {
        mTouchpadMode = touchpad;
        invalidate();
    }

    public void resetZoom() {
        mUserScale = 1.0f;
        mPanX = 0f;
        mPanY = 0f;
        invalidate();
    }

    public void sendRightClickAtCursor() {
        RfbClient c = mClient;
        if (c == null || !c.isConnected()) return;
        int cx = Math.round(mCursorX);
        int cy = Math.round(mCursorY);
        c.sendPointerEvent(4, cx, cy);
        c.sendPointerEvent(0, cx, cy);
    }

    public void sendKeySym(int keysym) {
        RfbClient c = mClient;
        if (c == null || !c.isConnected()) return;
        c.sendKeyEvent(true, keysym);
        c.sendKeyEvent(false, keysym);
    }

    public void sendKeyCombo(int modifierSym, int keysym) {
        RfbClient c = mClient;
        if (c == null || !c.isConnected()) return;
        c.sendKeyEvent(true, modifierSym);
        c.sendKeyEvent(true, keysym);
        c.sendKeyEvent(false, keysym);
        c.sendKeyEvent(false, modifierSym);
    }

    public void initFramebuffer(final int width, final int height) {
        synchronized (mBitmapLock) {
            if (mBitmap == null || mBitmap.getWidth() != width || mBitmap.getHeight() != height) {
                mBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
                mBitmap.eraseColor(Color.parseColor("#0D1117"));
            }
            mFbWidth = width;
            mFbHeight = height;
            mCursorX = width / 2f;
            mCursorY = height / 2f;
        }
        resetZoom();
        postInvalidate();
    }

    public void updateFramebufferRect(int[] pixels, int fbW, int fbH, int rx, int ry, int rw, int rh) {
        synchronized (mBitmapLock) {
            if (mBitmap == null || mBitmap.getWidth() != fbW || mBitmap.getHeight() != fbH) {
                mBitmap = Bitmap.createBitmap(fbW, fbH, Bitmap.Config.ARGB_8888);
                mFbWidth = fbW;
                mFbHeight = fbH;
                mCursorX = fbW / 2f;
                mCursorY = fbH / 2f;
            }
            int safeX = Math.max(0, Math.min(fbW - 1, rx));
            int safeY = Math.max(0, Math.min(fbH - 1, ry));
            int safeW = Math.min(rw, fbW - safeX);
            int safeH = Math.min(rh, fbH - safeY);
            if (safeW > 0 && safeH > 0 && pixels.length >= fbW * fbH) {
                mBitmap.setPixels(pixels, safeY * fbW + safeX, fbW, safeX, safeY, safeW, safeH);
            }
        }
        postInvalidate();
    }

    private float getBaseFitScale() {
        if (mFbWidth <= 0 || mFbHeight <= 0 || getWidth() <= 0 || getHeight() <= 0) {
            return 1f;
        }
        return Math.min(getWidth() / (float) mFbWidth, getHeight() / (float) mFbHeight);
    }

    private float getTotalScale() {
        return getBaseFitScale() * mUserScale;
    }

    private float getBaseOffsetX(float totalScale) {
        return (getWidth() - mFbWidth * totalScale) / 2f;
    }

    private float getBaseOffsetY(float totalScale) {
        return (getHeight() - mFbHeight * totalScale) / 2f;
    }

    private void clampPan() {
        if (mUserScale <= 1.0f) {
            mPanX = 0f;
            mPanY = 0f;
            return;
        }
        float maxPanX = getWidth() * (mUserScale - 1f) * 0.6f + 120f;
        float maxPanY = getHeight() * (mUserScale - 1f) * 0.6f + 120f;
        mPanX = Math.max(-maxPanX, Math.min(maxPanX, mPanX));
        mPanY = Math.max(-maxPanY, Math.min(maxPanY, mPanY));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        canvas.drawColor(Color.parseColor("#0D1117"));

        float totalScale = getTotalScale();
        float drawX = getBaseOffsetX(totalScale) + mPanX;
        float drawY = getBaseOffsetY(totalScale) + mPanY;

        synchronized (mBitmapLock) {
            if (mBitmap != null && !mBitmap.isRecycled()) {
                mDrawMatrix.reset();
                mDrawMatrix.postScale(totalScale, totalScale);
                mDrawMatrix.postTranslate(drawX, drawY);
                canvas.drawBitmap(mBitmap, mDrawMatrix, mBitmapPaint);
            }
        }

        if (mTouchpadMode && mFbWidth > 0 && mFbHeight > 0) {
            float sx = drawX + mCursorX * totalScale;
            float sy = drawY + mCursorY * totalScale;
            float s = Math.max(1.0f, getResources().getDisplayMetrics().density * 0.9f);
            mCursorPath.reset();
            mCursorPath.moveTo(sx, sy);
            mCursorPath.lineTo(sx, sy + 18f * s);
            mCursorPath.lineTo(sx + 5f * s, sy + 14f * s);
            mCursorPath.lineTo(sx + 10f * s, sy + 20f * s);
            mCursorPath.lineTo(sx + 13f * s, sy + 18.5f * s);
            mCursorPath.lineTo(sx + 8f * s, sy + 12.5f * s);
            mCursorPath.lineTo(sx + 14f * s, sy + 12.5f * s);
            mCursorPath.close();
            canvas.drawPath(mCursorPath, mCursorFill);
            canvas.drawPath(mCursorPath, mCursorStroke);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (mFbWidth <= 0 || mFbHeight <= 0) {
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
                mHandler.removeCallbacks(mLongPressRunnable);
                if (mTouchpadMode) {
                    mHandler.postDelayed(mLongPressRunnable, 520L);
                }
                break;

            case MotionEvent.ACTION_POINTER_DOWN:
                mHandler.removeCallbacks(mLongPressRunnable);
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
                    float dx = midX - mLastX;
                    if (!mScaleDetector.isInProgress()) {
                        if (mUserScale > 1.02f) {
                            mPanX += dx;
                            mPanY += dy;
                            clampPan();
                            invalidate();
                        } else if (mTouchpadMode) {
                            mTwoFingerScrollAccum += dy;
                            float step = Math.max(24f, getResources().getDisplayMetrics().density * 24f);
                            while (mTwoFingerScrollAccum >= step) {
                                sendWheel(false);
                                mTwoFingerScrollAccum -= step;
                            }
                            while (mTwoFingerScrollAccum <= -step) {
                                sendWheel(true);
                                mTwoFingerScrollAccum += step;
                            }
                        }
                    }
                    mLastX = midX;
                    mLastY = midY;
                    mLastTwoY = midY;
                    mMovedBeyondSlop = true;
                } else if (count == 1 && !mMultiTouchSeen) {
                    float x = event.getX();
                    float y = event.getY();
                    if (!mMovedBeyondSlop) {
                        float dist = (float) Math.hypot(x - mDownX, y - mDownY);
                        if (dist > mTouchSlopPx) {
                            mMovedBeyondSlop = true;
                            mHandler.removeCallbacks(mLongPressRunnable);
                        }
                    }
                    if (mMovedBeyondSlop && mTouchpadMode) {
                        float sensitivity = 1.25f / Math.max(0.25f, getTotalScale());
                        mCursorX = Math.max(0f, Math.min(mFbWidth - 1f, mCursorX + (x - mLastX) * sensitivity));
                        mCursorY = Math.max(0f, Math.min(mFbHeight - 1f, mCursorY + (y - mLastY) * sensitivity));
                        if (mClient != null && mClient.isConnected()) {
                            mClient.sendPointerEvent(0, Math.round(mCursorX), Math.round(mCursorY));
                        }
                        invalidate();
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
            case MotionEvent.ACTION_CANCEL:
                mHandler.removeCallbacks(mLongPressRunnable);
                if (action == MotionEvent.ACTION_UP && !mMovedBeyondSlop && !mMultiTouchSeen && !mLongPressFired) {
                    if (mClient != null && mClient.isConnected()) {
                        int cx = Math.round(mCursorX);
                        int cy = Math.round(mCursorY);
                        mClient.sendPointerEvent(1, cx, cy);
                        mClient.sendPointerEvent(0, cx, cy);
                    }
                }
                break;
        }
        return true;
    }

    private void handleDirectTouch(MotionEvent event) {
        float totalScale = Math.max(0.01f, getTotalScale());
        float drawX = getBaseOffsetX(totalScale) + mPanX;
        float drawY = getBaseOffsetY(totalScale) + mPanY;
        float fbX = Math.max(0f, Math.min(mFbWidth - 1f, (event.getX() - drawX) / totalScale));
        float fbY = Math.max(0f, Math.min(mFbHeight - 1f, (event.getY() - drawY) / totalScale));
        mCursorX = fbX;
        mCursorY = fbY;
        int ix = Math.round(fbX);
        int iy = Math.round(fbY);
        int action = event.getActionMasked();
        if (mClient != null && mClient.isConnected()) {
            if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_MOVE) {
                mClient.sendPointerEvent(1, ix, iy);
            } else {
                mClient.sendPointerEvent(0, ix, iy);
            }
        }
        invalidate();
    }

    private void sendWheel(boolean up) {
        if (mClient == null || !mClient.isConnected()) return;
        int cx = Math.round(mCursorX);
        int cy = Math.round(mCursorY);
        int mask = up ? 8 : 16;
        mClient.sendPointerEvent(mask, cx, cy);
        mClient.sendPointerEvent(0, cx, cy);
    }

    @Override
    public boolean onCheckIsTextEditor() {
        return true;
    }

    @Override
    public InputConnection onCreateInputConnection(EditorInfo outAttrs) {
        outAttrs.inputType = InputType.TYPE_NULL;
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN | EditorInfo.IME_ACTION_NONE;
        return new BaseInputConnection(this, false) {
            @Override
            public boolean commitText(CharSequence text, int newCursorPosition) {
                if (text != null) {
                    sendTextCodePoints(text.toString());
                }
                return true;
            }

            @Override
            public boolean deleteSurroundingText(int beforeLength, int afterLength) {
                for (int i = 0; i < Math.max(1, beforeLength); i++) {
                    sendKeySym(0xFF08);
                }
                return true;
            }

            @Override
            public boolean sendKeyEvent(KeyEvent event) {
                return handleAndroidKeyEvent(event) || super.sendKeyEvent(event);
            }
        };
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (handleAndroidKeyEvent(event)) return true;
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (handleAndroidKeyEvent(event)) return true;
        return super.onKeyUp(keyCode, event);
    }

    private boolean handleAndroidKeyEvent(KeyEvent event) {
        if (mClient == null || !mClient.isConnected()) return false;
        int action = event.getAction();
        if (action == KeyEvent.ACTION_MULTIPLE) {
            String chars = event.getCharacters();
            if (chars != null) {
                sendTextCodePoints(chars);
                return true;
            }
            return false;
        }
        boolean down = (action == KeyEvent.ACTION_DOWN);
        if (action != KeyEvent.ACTION_DOWN && action != KeyEvent.ACTION_UP) {
            return false;
        }
        int sym = mapKeyCodeToX11Sym(event);
        if (sym == 0) return false;
        mClient.sendKeyEvent(down, sym);
        return true;
    }

    private void sendTextCodePoints(String s) {
        if (mClient == null || !mClient.isConnected()) return;
        int i = 0;
        while (i < s.length()) {
            int cp = s.codePointAt(i);
            i += Character.charCount(cp);
            if (cp == '\n') {
                sendKeySym(0xFF0D);
            } else if (cp == '\t') {
                sendKeySym(0xFF09);
            } else {
                sendKeySym(unicodeToX11KeySym(cp));
            }
        }
    }

    private static int unicodeToX11KeySym(int cp) {
        if (cp >= 0x20 && cp <= 0x7e) return cp;
        if (cp >= 0xa0 && cp <= 0xff) return cp;
        return 0x01000000 | cp;
    }

    private static int mapKeyCodeToX11Sym(KeyEvent event) {
        switch (event.getKeyCode()) {
            case KeyEvent.KEYCODE_DEL: return 0xFF08;
            case KeyEvent.KEYCODE_FORWARD_DEL: return 0xFFFF;
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER: return 0xFF0D;
            case KeyEvent.KEYCODE_TAB: return 0xFF09;
            case KeyEvent.KEYCODE_ESCAPE: return 0xFF1B;
            case KeyEvent.KEYCODE_DPAD_UP: return 0xFF52;
            case KeyEvent.KEYCODE_DPAD_DOWN: return 0xFF54;
            case KeyEvent.KEYCODE_DPAD_LEFT: return 0xFF51;
            case KeyEvent.KEYCODE_DPAD_RIGHT: return 0xFF53;
            case KeyEvent.KEYCODE_MOVE_HOME: return 0xFF50;
            case KeyEvent.KEYCODE_MOVE_END: return 0xFF57;
            case KeyEvent.KEYCODE_PAGE_UP: return 0xFF55;
            case KeyEvent.KEYCODE_PAGE_DOWN: return 0xFF56;
            case KeyEvent.KEYCODE_INSERT: return 0xFF63;
            case KeyEvent.KEYCODE_CTRL_LEFT: return 0xFFE3;
            case KeyEvent.KEYCODE_CTRL_RIGHT: return 0xFFE4;
            case KeyEvent.KEYCODE_ALT_LEFT: return 0xFFE9;
            case KeyEvent.KEYCODE_ALT_RIGHT: return 0xFFEA;
            case KeyEvent.KEYCODE_SHIFT_LEFT: return 0xFFE1;
            case KeyEvent.KEYCODE_SHIFT_RIGHT: return 0xFFE2;
            default: {
                int uc = event.getUnicodeChar();
                if (uc > 0) {
                    return unicodeToX11KeySym(uc);
                }
                return 0;
            }
        }
    }
}
