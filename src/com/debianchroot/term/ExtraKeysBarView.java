package com.debianchroot.term;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.widget.GridLayout;
import android.widget.TextView;

/**
 * Termux-style extra keys toolbar with CTRL/ALT latch + lock, long-press alternates,
 * key auto-repeat on navigation keys, and full/compact/hidden layout modes.
 */
public final class ExtraKeysBarView extends GridLayout {

    public interface Listener {
        void onKeyButton(String keyCode, boolean ctrl, boolean alt);
        void onModifiersChanged();
        void onSwipeToTextInput();
    }

    private enum ModState {
        OFF,
        LATCHED,
        LOCKED
    }

    private static final class KeySpec {
        final String label;
        final String code;
        final String altLabel;
        final String altCode;
        final boolean repeatable;
        final boolean isModifier;

        KeySpec(String label, String code, String altLabel, String altCode, boolean repeatable, boolean isModifier) {
            this.label = label;
            this.code = code;
            this.altLabel = altLabel;
            this.altCode = altCode;
            this.repeatable = repeatable;
            this.isModifier = isModifier;
        }
    }

    private static final KeySpec[] FULL_KEYS = {
        new KeySpec("ESC", "ESC", "~", "~", false, false),
        new KeySpec("/", "/", "|", "|", false, false),
        new KeySpec("-", "-", "_", "_", false, false),
        new KeySpec("HOME", "HOME", null, null, false, false),
        new KeySpec("▲", "UP", null, null, true, false),
        new KeySpec("END", "END", null, null, false, false),
        new KeySpec("PGUP", "PGUP", null, null, true, false),

        new KeySpec("TAB", "TAB", "S-TAB", "BACKTAB", false, false),
        new KeySpec("CTRL", "CTRL", null, null, false, true),
        new KeySpec("ALT", "ALT", null, null, false, true),
        new KeySpec("◄", "LEFT", null, null, true, false),
        new KeySpec("▼", "DOWN", null, null, true, false),
        new KeySpec("►", "RIGHT", null, null, true, false),
        new KeySpec("PGDN", "PGDN", null, null, true, false)
    };

    private static final KeySpec[] COMPACT_KEYS = {
        new KeySpec("ESC", "ESC", "~", "~", false, false),
        new KeySpec("TAB", "TAB", "S-TAB", "BACKTAB", false, false),
        new KeySpec("CTRL", "CTRL", null, null, false, true),
        new KeySpec("ALT", "ALT", null, null, false, true),
        new KeySpec("◄", "LEFT", null, null, true, false),
        new KeySpec("▼", "DOWN", null, null, true, false),
        new KeySpec("▲", "UP", null, null, true, false),
        new KeySpec("►", "RIGHT", null, null, true, false)
    };

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private Listener mListener;
    private boolean mHapticEnabled = true;
    private String mMode = TerminalSettings.EXTRA_KEYS_FULL;

    private ModState mCtrlState = ModState.OFF;
    private ModState mAltState = ModState.OFF;
    private long mLastCtrlTapMs = 0L;
    private long mLastAltTapMs = 0L;

    private TextView mCtrlView;
    private TextView mAltView;

    private float mSwipeDownX = 0f;
    private float mSwipeDownY = 0f;
    private boolean mSwipeTriggered = false;

    private int mBgColor = Color.parseColor("#161B22");
    private int mBtnColor = Color.parseColor("#21262D");
    private int mBtnPressedColor = Color.parseColor("#30363D");
    private int mTextColor = Color.parseColor("#E6EDF3");
    private int mSubTextColor = Color.parseColor("#8B949E");
    private int mAccentColor = Color.parseColor("#D70A53");

    public ExtraKeysBarView(Context context) {
        super(context);
        setBackgroundColor(mBgColor);
        int pad = dp(2);
        setPadding(pad, pad, pad, pad);
        rebuildButtons();
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        int action = ev.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            mSwipeDownX = ev.getX();
            mSwipeDownY = ev.getY();
            mSwipeTriggered = false;
        } else if (action == MotionEvent.ACTION_MOVE && !mSwipeTriggered) {
            float dx = ev.getX() - mSwipeDownX;
            float dy = ev.getY() - mSwipeDownY;
            if (Math.abs(dx) >= dp(44) && Math.abs(dx) > Math.abs(dy) * 1.5f) {
                mSwipeTriggered = true;
                return true;
            }
        }
        return super.onInterceptTouchEvent(ev);
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        int action = ev.getActionMasked();
        if (action == MotionEvent.ACTION_MOVE && !mSwipeTriggered) {
            float dx = ev.getX() - mSwipeDownX;
            float dy = ev.getY() - mSwipeDownY;
            if (Math.abs(dx) >= dp(44) && Math.abs(dx) > Math.abs(dy) * 1.5f) {
                mSwipeTriggered = true;
            }
        }
        if ((action == MotionEvent.ACTION_MOVE || action == MotionEvent.ACTION_UP) && mSwipeTriggered) {
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_MOVE) {
                if (mListener != null) {
                    mListener.onSwipeToTextInput();
                }
                mSwipeTriggered = false;
            }
            return true;
        }
        return super.onTouchEvent(ev);
    }

    public void setListener(Listener listener) {
        mListener = listener;
    }

    public void setHapticEnabled(boolean enabled) {
        mHapticEnabled = enabled;
    }

    public void applyTheme(boolean light) {
        if (light) {
            mBgColor = Color.parseColor("#EEE8D5");
            mBtnColor = Color.parseColor("#FDF6E3");
            mBtnPressedColor = Color.parseColor("#E0D9C5");
            mTextColor = Color.parseColor("#586E75");
            mSubTextColor = Color.parseColor("#93A1A1");
        } else {
            mBgColor = Color.parseColor("#161B22");
            mBtnColor = Color.parseColor("#21262D");
            mBtnPressedColor = Color.parseColor("#30363D");
            mTextColor = Color.parseColor("#E6EDF3");
            mSubTextColor = Color.parseColor("#8B949E");
        }
        setBackgroundColor(mBgColor);
        rebuildButtons();
    }

    public void setMode(String mode) {
        if (mode == null) mode = TerminalSettings.EXTRA_KEYS_FULL;
        if (TerminalSettings.EXTRA_KEYS_HIDDEN.equals(mode)) {
            mMode = mode;
            setVisibility(View.GONE);
            return;
        }
        mMode = TerminalSettings.EXTRA_KEYS_COMPACT.equals(mode)
            ? TerminalSettings.EXTRA_KEYS_COMPACT
            : TerminalSettings.EXTRA_KEYS_FULL;
        setVisibility(View.VISIBLE);
        rebuildButtons();
    }

    public boolean readAndConsumeCtrl() {
        if (mCtrlState == ModState.OFF) return false;
        if (mCtrlState == ModState.LATCHED) {
            mCtrlState = ModState.OFF;
            post(this::refreshModifierVisuals);
        }
        return true;
    }

    public boolean readAndConsumeAlt() {
        if (mAltState == ModState.OFF) return false;
        if (mAltState == ModState.LATCHED) {
            mAltState = ModState.OFF;
            post(this::refreshModifierVisuals);
        }
        return true;
    }

    private void rebuildButtons() {
        removeAllViews();
        mCtrlView = null;
        mAltView = null;

        boolean compact = TerminalSettings.EXTRA_KEYS_COMPACT.equals(mMode);
        KeySpec[] specs = compact ? COMPACT_KEYS : FULL_KEYS;
        int cols = compact ? 8 : 7;
        int rows = compact ? 1 : 2;
        setColumnCount(cols);
        setRowCount(rows);

        int btnHeight = dp(compact ? 38 : 34);
        int margin = dp(2);

        for (int i = 0; i < specs.length; i++) {
            final KeySpec spec = specs[i];
            int r = i / cols;
            int c = i % cols;

            TextView btn = new TextView(getContext());
            btn.setGravity(Gravity.CENTER);
            btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, compact ? 12 : 11.5f);
            btn.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
            btn.setSingleLine(true);
            btn.setTextColor(mTextColor);
            if (spec.altLabel != null) {
                btn.setText(spec.label + "·" + spec.altLabel);
            } else {
                btn.setText(spec.label);
            }
            btn.setBackground(makeRoundedBg(mBtnColor));

            LayoutParams lp = new LayoutParams(spec(r, 1f), spec(c, 1f));
            lp.width = 0;
            lp.height = btnHeight;
            lp.setMargins(margin, margin, margin, margin);
            btn.setLayoutParams(lp);

            if ("CTRL".equals(spec.code)) {
                mCtrlView = btn;
            } else if ("ALT".equals(spec.code)) {
                mAltView = btn;
            }

            attachTouchBehavior(btn, spec);
            addView(btn);
        }
        refreshModifierVisuals();
    }

    private void attachTouchBehavior(final TextView btn, final KeySpec spec) {
        btn.setOnTouchListener(new OnTouchListener() {
            private boolean longFired = false;
            private Runnable repeatRunnable;
            private Runnable longPressRunnable;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                int action = event.getActionMasked();
                if (action == MotionEvent.ACTION_DOWN) {
                    longFired = false;
                    setPressedVisual(btn, spec, true);
                    triggerHaptic(btn);

                    if (spec.repeatable) {
                        dispatchKey(spec.code);
                        repeatRunnable = new Runnable() {
                            @Override
                            public void run() {
                                dispatchKey(spec.code);
                                mHandler.postDelayed(this, 80);
                            }
                        };
                        mHandler.postDelayed(repeatRunnable, 400);
                    } else if (spec.isModifier || spec.altCode != null) {
                        longPressRunnable = () -> {
                            longFired = true;
                            triggerHaptic(btn);
                            if (spec.isModifier) {
                                lockModifier(spec.code);
                            } else if (spec.altCode != null) {
                                dispatchKey(spec.altCode);
                            }
                        };
                        mHandler.postDelayed(longPressRunnable, 420);
                    }
                    return true;
                } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                    cancelRunnables();
                    setPressedVisual(btn, spec, false);

                    if (action == MotionEvent.ACTION_UP && !longFired && !spec.repeatable) {
                        if (spec.isModifier) {
                            toggleModifierTap(spec.code);
                        } else {
                            dispatchKey(spec.code);
                        }
                    }
                    return true;
                }
                return false;
            }

            private void cancelRunnables() {
                if (repeatRunnable != null) {
                    mHandler.removeCallbacks(repeatRunnable);
                    repeatRunnable = null;
                }
                if (longPressRunnable != null) {
                    mHandler.removeCallbacks(longPressRunnable);
                    longPressRunnable = null;
                }
            }
        });
    }

    private void setPressedVisual(TextView btn, KeySpec spec, boolean pressed) {
        if (spec.isModifier) {
            refreshModifierVisuals();
            return;
        }
        btn.setBackground(makeRoundedBg(pressed ? mBtnPressedColor : mBtnColor));
    }

    private void toggleModifierTap(String code) {
        long now = System.currentTimeMillis();
        if ("CTRL".equals(code)) {
            if (mCtrlState == ModState.LOCKED) {
                mCtrlState = ModState.OFF;
            } else if (mCtrlState == ModState.LATCHED) {
                if (now - mLastCtrlTapMs < 450) {
                    mCtrlState = ModState.LOCKED;
                } else {
                    mCtrlState = ModState.OFF;
                }
            } else {
                mCtrlState = ModState.LATCHED;
            }
            mLastCtrlTapMs = now;
        } else if ("ALT".equals(code)) {
            if (mAltState == ModState.LOCKED) {
                mAltState = ModState.OFF;
            } else if (mAltState == ModState.LATCHED) {
                if (now - mLastAltTapMs < 450) {
                    mAltState = ModState.LOCKED;
                } else {
                    mAltState = ModState.OFF;
                }
            } else {
                mAltState = ModState.LATCHED;
            }
            mLastAltTapMs = now;
        }
        refreshModifierVisuals();
        if (mListener != null) mListener.onModifiersChanged();
    }

    private void lockModifier(String code) {
        if ("CTRL".equals(code)) {
            mCtrlState = (mCtrlState == ModState.LOCKED) ? ModState.OFF : ModState.LOCKED;
        } else if ("ALT".equals(code)) {
            mAltState = (mAltState == ModState.LOCKED) ? ModState.OFF : ModState.LOCKED;
        }
        refreshModifierVisuals();
        if (mListener != null) mListener.onModifiersChanged();
    }

    private void refreshModifierVisuals() {
        applyModView(mCtrlView, "CTRL", mCtrlState);
        applyModView(mAltView, "ALT", mAltState);
    }

    private void applyModView(TextView view, String baseLabel, ModState state) {
        if (view == null) return;
        if (state == ModState.LOCKED) {
            view.setText(baseLabel + "•");
            view.setTextColor(Color.WHITE);
            view.setBackground(makeRoundedBg(mAccentColor));
        } else if (state == ModState.LATCHED) {
            view.setText(baseLabel);
            view.setTextColor(Color.WHITE);
            view.setBackground(makeRoundedBg(Color.parseColor("#A3083F")));
        } else {
            view.setText(baseLabel);
            view.setTextColor(mTextColor);
            view.setBackground(makeRoundedBg(mBtnColor));
        }
    }

    private void dispatchKey(String code) {
        boolean ctrl = readAndConsumeCtrl();
        boolean alt = readAndConsumeAlt();
        if (mListener != null) {
            mListener.onKeyButton(code, ctrl, alt);
        }
    }

    private void triggerHaptic(View v) {
        if (mHapticEnabled) {
            v.performHapticFeedback(
                HapticFeedbackConstants.KEYBOARD_TAP,
                HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING
            );
        }
    }

    private GradientDrawable makeRoundedBg(int color) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(5));
        return d;
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
