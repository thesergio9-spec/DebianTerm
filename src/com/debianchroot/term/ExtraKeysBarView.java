package com.debianchroot.term;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Termux-style extra keys toolbar with CTRL/ALT latch + lock, long-press alternates,
 * key auto-repeat on navigation keys, full/compact/hidden layout modes, presets & custom specs.
 */
public final class ExtraKeysBarView extends LinearLayout {

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

    public static final class KeySpec {
        public final String label;
        public final String code;
        public final String altLabel;
        public final String altCode;
        public final boolean repeatable;
        public final boolean isModifier;

        public KeySpec(String label, String code, String altLabel, String altCode, boolean repeatable, boolean isModifier) {
            this.label = label;
            this.code = code;
            this.altLabel = altLabel;
            this.altCode = altCode;
            this.repeatable = repeatable;
            this.isModifier = isModifier;
        }
    }

    public static final String PRESET_STANDARD =
        "ESC:~, /:|, -:_, HOME, UP, END, PGUP\nTAB:BACKTAB, CTRL, ALT, LEFT, DOWN, RIGHT, PGDN";

    public static final String PRESET_PROGRAMMING =
        "ESC:~, /:|, -:_, {:[, }:], =:+, $:;\nTAB:BACKTAB, CTRL, ALT, LEFT, DOWN, RIGHT, ENTER";

    public static final String PRESET_TMUX_VIM =
        "ESC, COLON, /:|, -:_, HOME, UP, END\nTAB:BACKTAB, CTRL, ALT, LEFT, DOWN, RIGHT, BKSP";

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
    private String mCustomSpec = PRESET_STANDARD;

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
    private int mAccentLatchedColor = Color.parseColor("#A3083F");

    public ExtraKeysBarView(Context context) {
        super(context);
        setOrientation(VERTICAL);
        setBackgroundColor(mBgColor);
        int pad = dp(2);
        setPadding(pad, pad, pad, pad);
        rebuildButtons();
    }

    @Override
    protected void onDetachedFromWindow() {
        mHandler.removeCallbacksAndMessages(null);
        super.onDetachedFromWindow();
    }

    /**
     * Parses a 1-to-2 row extra-keys specification string into structured KeySpec rows.
     * Supports KEY:ALT syntax, commas/spaces separators, and aliases (COLON, ENTER, BKSP, etc.).
     */
    public static List<List<KeySpec>> parseCustomSpec(String raw) {
        String source = (raw == null || raw.trim().isEmpty()) ? PRESET_STANDARD : raw;
        List<List<KeySpec>> rows = new ArrayList<>();
        String[] lines = source.split("\\r?\\n");
        for (String rawLine : lines) {
            if (rows.size() >= 2) break;
            String line = rawLine.trim();
            if (line.isEmpty()) continue;

            String[] tokens = line.split("[,\\s]+");
            List<KeySpec> row = new ArrayList<>();
            for (String rawTok : tokens) {
                if (row.size() >= 10) break;
                String tok = rawTok.trim();
                if (tok.isEmpty()) continue;

                String primaryTok = tok;
                String altTok = null;
                int sepIdx = tok.indexOf(':');
                if (tok.length() >= 3 && sepIdx > 0 && sepIdx < tok.length() - 1) {
                    primaryTok = tok.substring(0, sepIdx).trim();
                    altTok = tok.substring(sepIdx + 1).trim();
                    if (altTok.isEmpty()) altTok = null;
                }

                String code = resolveKeyCode(primaryTok);
                if (code == null || code.isEmpty()) continue;
                String label = labelForCode(code);

                boolean isMod = "CTRL".equals(code) || "ALT".equals(code);
                boolean repeatable = !isMod && isRepeatableCode(code);

                String altCode = (!isMod && altTok != null) ? resolveKeyCode(altTok) : null;
                String altLabel = (altCode != null && !altCode.isEmpty()) ? labelForCode(altCode) : null;
                if (altLabel == null) altCode = null;

                row.add(new KeySpec(label, code, altLabel, altCode, repeatable, isMod));
            }
            if (!row.isEmpty()) {
                rows.add(row);
            }
        }
        if (rows.isEmpty() && !PRESET_STANDARD.equals(source)) {
            return parseCustomSpec(PRESET_STANDARD);
        }
        return rows;
    }

    private static String resolveKeyCode(String rawTok) {
        if (rawTok == null || rawTok.isEmpty()) return null;
        String upper = rawTok.toUpperCase(Locale.US);
        switch (upper) {
            case "ESCAPE":
            case "ESC":
                return "ESC";
            case "TAB":
                return "TAB";
            case "BACKTAB":
            case "S-TAB":
            case "STAB":
                return "BACKTAB";
            case "CTRL":
                return "CTRL";
            case "ALT":
                return "ALT";
            case "HOME":
                return "HOME";
            case "END":
                return "END";
            case "PGUP":
            case "PAGEUP":
                return "PGUP";
            case "PGDN":
            case "PAGEDOWN":
                return "PGDN";
            case "UP":
            case "▲":
                return "UP";
            case "DOWN":
            case "▼":
                return "DOWN";
            case "LEFT":
            case "◄":
                return "LEFT";
            case "RIGHT":
            case "►":
                return "RIGHT";
            case "ENTER":
            case "RETURN":
            case "↵":
                return "ENTER";
            case "BKSP":
            case "BACKSPACE":
            case "DEL":
            case "⌫":
                return "BKSP";
            case "COLON":
                return ":";
            case "COMMA":
                return ",";
            case "PIPE":
                return "|";
            case "SPACE":
            case "SPC":
                return " ";
            default:
                return rawTok.length() == 1 ? rawTok : upper;
        }
    }

    private static String labelForCode(String code) {
        if ("UP".equals(code)) return "▲";
        if ("DOWN".equals(code)) return "▼";
        if ("LEFT".equals(code)) return "◄";
        if ("RIGHT".equals(code)) return "►";
        if ("BACKTAB".equals(code)) return "S-TAB";
        if ("ENTER".equals(code)) return "↵";
        if ("BKSP".equals(code)) return "⌫";
        if (" ".equals(code)) return "SPC";
        return code;
    }

    private static boolean isRepeatableCode(String code) {
        return "UP".equals(code)
            || "DOWN".equals(code)
            || "LEFT".equals(code)
            || "RIGHT".equals(code)
            || "PGUP".equals(code)
            || "PGDN".equals(code)
            || "BKSP".equals(code);
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
            if (mListener != null) {
                mListener.onSwipeToTextInput();
            }
            mSwipeTriggered = false;
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

    public void setCustomSpec(String spec) {
        mCustomSpec = (spec == null || spec.trim().isEmpty()) ? PRESET_STANDARD : spec;
        if (!TerminalSettings.EXTRA_KEYS_HIDDEN.equals(mMode)
            && !TerminalSettings.EXTRA_KEYS_COMPACT.equals(mMode)) {
            rebuildButtons();
        }
    }

    public void setAccentColor(int color) {
        mAccentColor = color;
        mAccentLatchedColor = darkenColor(color, 0.78f);
        refreshModifierVisuals();
    }

    public void setBarSurfaceColor(int bgColor) {
        mBgColor = bgColor;
        setBackgroundColor(mBgColor);
    }

    private static int darkenColor(int color, float factor) {
        int r = Math.max(0, Math.min(255, Math.round(Color.red(color) * factor)));
        int g = Math.max(0, Math.min(255, Math.round(Color.green(color) * factor)));
        int b = Math.max(0, Math.min(255, Math.round(Color.blue(color) * factor)));
        return Color.rgb(r, g, b);
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
        mHandler.removeCallbacksAndMessages(null);
        removeAllViews();
        mCtrlView = null;
        mAltView = null;

        boolean compact = TerminalSettings.EXTRA_KEYS_COMPACT.equals(mMode);
        List<List<KeySpec>> rows;
        int maxCols;

        if (compact) {
            List<KeySpec> compactList = new ArrayList<>(COMPACT_KEYS.length);
            Collections.addAll(compactList, COMPACT_KEYS);
            rows = Collections.singletonList(compactList);
            maxCols = COMPACT_KEYS.length;
        } else {
            rows = parseCustomSpec(mCustomSpec);
            maxCols = 1;
            for (List<KeySpec> r : rows) {
                if (r.size() > maxCols) maxCols = r.size();
            }
        }

        int btnHeight = dp(compact ? 38 : (rows.size() == 1 ? 38 : 34));
        int margin = dp(2);
        float textSp = compact ? 12f : (maxCols >= 9 ? 10f : 11.5f);

        for (int r = 0; r < rows.size(); r++) {
            List<KeySpec> rowSpecs = rows.get(r);
            LinearLayout rowLayout = new LinearLayout(getContext());
            rowLayout.setOrientation(HORIZONTAL);
            rowLayout.setWeightSum((float) maxCols);
            LayoutParams rowLp = new LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
            addView(rowLayout, rowLp);

            for (int c = 0; c < rowSpecs.size(); c++) {
                final KeySpec spec = rowSpecs.get(c);
                int colSpan = (c == rowSpecs.size() - 1 && rowSpecs.size() < maxCols)
                    ? (maxCols - c)
                    : 1;

                TextView btn = new TextView(getContext());
                btn.setGravity(Gravity.CENTER);
                btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, textSp);
                btn.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
                btn.setSingleLine(true);
                btn.setEllipsize(TextUtils.TruncateAt.END);
                btn.setMinWidth(0);
                btn.setMinimumWidth(0);
                btn.setPadding(dp(1), 0, dp(1), 0);
                btn.setTextColor(mTextColor);
                if (spec.altLabel != null) {
                    btn.setText(spec.label + "·" + spec.altLabel);
                } else {
                    btn.setText(spec.label);
                }
                btn.setBackground(makeRoundedBg(mBtnColor));

                LayoutParams lp = new LayoutParams(0, btnHeight, (float) colSpan);
                lp.setMargins(margin, margin, margin, margin);
                btn.setLayoutParams(lp);

                if ("CTRL".equals(spec.code)) {
                    mCtrlView = btn;
                } else if ("ALT".equals(spec.code)) {
                    mAltView = btn;
                }

                attachTouchBehavior(btn, spec);
                rowLayout.addView(btn);
            }
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
            view.setBackground(makeRoundedBg(mAccentLatchedColor));
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
