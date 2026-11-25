package com.termux.terminal;

import java.util.Arrays;

/**
 * Streaming DEC VT340 / XTerm Sixel graphics decoder producing {@link SixelImage} (ARGB_8888).
 * Supports raster attributes ({@code "}), RGB/HLS color definitions ({@code #}), RLE repeats ({@code !}),
 * graphics CR ({@code $}), graphics LF ({@code -}), and 6-pixel vertical data bytes ({@code 0x3F..0x7E}).
 */
public final class SixelDecoder {

    public static final int MAX_WIDTH = 2048;
    public static final int MAX_HEIGHT = 2048;
    public static final int MAX_COLOR_REGISTERS = 256;

    private static final int STATE_DATA = 0;
    private static final int STATE_PARAM = 1;

    private static final int[] VT340_PALETTE_16 = {
        0xFF000000, // 0 Black
        0xFF3333CC, // 1 Blue
        0xFFCC2222, // 2 Red
        0xFF33CC33, // 3 Green
        0xFFCC33CC, // 4 Magenta
        0xFF33CCCC, // 5 Cyan
        0xFFCCCC33, // 6 Yellow
        0xFF888888, // 7 Gray 50%
        0xFF444444, // 8 Gray 25%
        0xFF5555FF, // 9 Bright Blue
        0xFFFF5555, // 10 Bright Red
        0xFF55FF55, // 11 Bright Green
        0xFFFF55FF, // 12 Bright Magenta
        0xFF55FFFF, // 13 Bright Cyan
        0xFFFFFF55, // 14 Bright Yellow
        0xFFFFFFFF  // 15 White
    };

    private static final int[] CUBE_STEPS = { 0, 95, 135, 175, 215, 255 };

    private final boolean mTransparentBg;
    private final int mDefaultBgArgb;
    private final int[] mPalette = new int[MAX_COLOR_REGISTERS];

    private int mState = STATE_DATA;
    private char mParamCmd = 0;
    private final int[] mParams = new int[8];
    private int mParamCount = 0;
    private int mCurrentParamVal = 0;
    private boolean mHasCurrentParam = false;

    private int mPendingRepeat = 1;
    private int mCurrentColor;
    private int mCursorX = 0;
    private int mCursorY = 0;

    private int mRasterW = 0;
    private int mRasterH = 0;
    private int mMaxPaintedX = 0;
    private int mMaxPaintedY = 0;

    private int mAllocW = 0;
    private int mAllocH = 0;
    private int[] mCanvas = null;

    public SixelDecoder(boolean transparentBg, int defaultBgArgb) {
        this.mTransparentBg = transparentBg;
        this.mDefaultBgArgb = defaultBgArgb | 0xFF000000;
        initDefaultPalette();
        this.mCurrentColor = mPalette[7];
    }

    private void initDefaultPalette() {
        System.arraycopy(VT340_PALETTE_16, 0, mPalette, 0, VT340_PALETTE_16.length);
        // xterm 6x6x6 color cube (16..231)
        int idx = 16;
        for (int r = 0; r < 6; r++) {
            for (int g = 0; g < 6; g++) {
                for (int b = 0; b < 6; b++) {
                    mPalette[idx++] = 0xFF000000 | (CUBE_STEPS[r] << 16) | (CUBE_STEPS[g] << 8) | CUBE_STEPS[b];
                }
            }
        }
        // grayscale ramp (232..255)
        for (int i = 0; i < 24; i++) {
            int gray = 8 + i * 10;
            mPalette[232 + i] = 0xFF000000 | (gray << 16) | (gray << 8) | gray;
        }
    }

    public void feed(int ch) {
        if (ch == ' ' || ch == '\t' || ch == '\r' || ch == '\n') {
            return;
        }
        if (mState == STATE_PARAM) {
            if (ch >= '0' && ch <= '9') {
                if (!mHasCurrentParam) {
                    mHasCurrentParam = true;
                    mCurrentParamVal = 0;
                }
                mCurrentParamVal = Math.min(99999, mCurrentParamVal * 10 + (ch - '0'));
                return;
            }
            if (ch == ';') {
                pushCurrentParam(0);
                return;
            }
            flushParamCommand();
            mState = STATE_DATA;
        }

        if (ch == '"' || ch == '#' || ch == '!') {
            startParamCommand((char) ch);
            return;
        }
        if (ch == '$') {
            mPendingRepeat = 1;
            mCursorX = 0;
            return;
        }
        if (ch == '-') {
            mPendingRepeat = 1;
            mCursorX = 0;
            if (mCursorY < MAX_HEIGHT) {
                mCursorY = Math.min(MAX_HEIGHT, mCursorY + 6);
            }
            return;
        }
        if (ch >= 0x3F && ch <= 0x7E) {
            paintSixelByte(ch - 0x3F);
        }
    }

    private void startParamCommand(char cmd) {
        mState = STATE_PARAM;
        mParamCmd = cmd;
        mParamCount = 0;
        mCurrentParamVal = 0;
        mHasCurrentParam = false;
        Arrays.fill(mParams, 0);
    }

    private void pushCurrentParam(int defaultVal) {
        if (mParamCount < mParams.length) {
            mParams[mParamCount++] = mHasCurrentParam ? mCurrentParamVal : defaultVal;
        }
        mCurrentParamVal = 0;
        mHasCurrentParam = false;
    }

    private void flushParamCommand() {
        if (mHasCurrentParam || mParamCount > 0) {
            pushCurrentParam(0);
        }
        if (mParamCmd == '"') {
            if (mParamCount >= 4) {
                mRasterW = Math.max(0, Math.min(MAX_WIDTH, mParams[2]));
                mRasterH = Math.max(0, Math.min(MAX_HEIGHT, mParams[3]));
                if (mRasterW > 0 && mRasterH > 0) {
                    ensureCapacity(mRasterW, mRasterH);
                }
            }
        } else if (mParamCmd == '#') {
            int pc = (mParamCount >= 1 ? Math.max(0, mParams[0]) : 0) % MAX_COLOR_REGISTERS;
            if (mParamCount >= 5) {
                int pu = mParams[1];
                int px = mParams[2];
                int py = mParams[3];
                int pz = mParams[4];
                if (pu == 2) {
                    int r = clampByte(Math.round(clampRange(px, 0, 100) * 255f / 100f));
                    int g = clampByte(Math.round(clampRange(py, 0, 100) * 255f / 100f));
                    int b = clampByte(Math.round(clampRange(pz, 0, 100) * 255f / 100f));
                    mPalette[pc] = 0xFF000000 | (r << 16) | (g << 8) | b;
                } else if (pu == 1) {
                    mPalette[pc] = vt340HlsToArgb(px, py, pz);
                }
            }
            mCurrentColor = mPalette[pc];
        } else if (mParamCmd == '!') {
            int rep = (mParamCount >= 1 && mParams[0] > 0) ? mParams[0] : 1;
            mPendingRepeat = Math.min(MAX_WIDTH, rep);
        }
        mParamCmd = 0;
    }

    private void paintSixelByte(int bits) {
        int repeat = Math.max(1, mPendingRepeat);
        mPendingRepeat = 1;

        int startX = mCursorX;
        int endX = Math.min(MAX_WIDTH, startX + repeat);
        int count = endX - startX;

        if (bits != 0 && count > 0 && mCursorY < MAX_HEIGHT) {
            int highestBit = 0;
            for (int b = 5; b >= 0; b--) {
                if ((bits & (1 << b)) != 0) {
                    highestBit = b;
                    break;
                }
            }
            int neededH = Math.min(MAX_HEIGHT, mCursorY + highestBit + 1);
            ensureCapacity(endX, neededH);

            for (int b = 0; b < 6; b++) {
                if ((bits & (1 << b)) == 0) continue;
                int py = mCursorY + b;
                if (py >= mAllocH) break;
                int rowOff = py * mAllocW;
                for (int x = startX; x < endX && x < mAllocW; x++) {
                    mCanvas[rowOff + x] = mCurrentColor;
                }
            }
            if (endX > mMaxPaintedX) mMaxPaintedX = endX;
            if (neededH > mMaxPaintedY) mMaxPaintedY = neededH;
        }
        mCursorX = Math.min(MAX_WIDTH, startX + repeat);
    }

    private void ensureCapacity(int neededW, int neededH) {
        int reqW = Math.min(MAX_WIDTH, Math.max(neededW, 1));
        int reqH = Math.min(MAX_HEIGHT, Math.max(neededH, 1));
        if (mCanvas != null && reqW <= mAllocW && reqH <= mAllocH) {
            return;
        }
        int newW = mAllocW == 0
            ? Math.min(MAX_WIDTH, Math.max(64, reqW))
            : Math.min(MAX_WIDTH, Math.max(reqW, mAllocW * 2));
        int newH = mAllocH == 0
            ? Math.min(MAX_HEIGHT, Math.max(36, reqH))
            : Math.min(MAX_HEIGHT, Math.max(reqH, mAllocH + 60));

        int[] next = new int[newW * newH];
        if (mCanvas != null && mAllocW > 0 && mAllocH > 0) {
            int copyW = Math.min(mAllocW, newW);
            int copyH = Math.min(mAllocH, newH);
            for (int y = 0; y < copyH; y++) {
                System.arraycopy(mCanvas, y * mAllocW, next, y * newW, copyW);
            }
        }
        mCanvas = next;
        mAllocW = newW;
        mAllocH = newH;
    }

    public SixelImage finish() {
        if (mState == STATE_PARAM) {
            flushParamCommand();
            mState = STATE_DATA;
        }
        int finalW = Math.min(MAX_WIDTH, Math.max(mRasterW, mMaxPaintedX));
        int finalH = Math.min(MAX_HEIGHT, Math.max(mRasterH, mMaxPaintedY));
        if (finalW <= 0 || finalH <= 0) {
            return null;
        }
        int fillPixel = mTransparentBg ? 0x00000000 : mDefaultBgArgb;
        int[] out = new int[finalW * finalH];
        if (mCanvas == null || mAllocW <= 0 || mAllocH <= 0) {
            Arrays.fill(out, fillPixel);
        } else {
            int copyW = Math.min(finalW, mAllocW);
            int copyH = Math.min(finalH, mAllocH);
            for (int y = 0; y < finalH; y++) {
                int dstRow = y * finalW;
                if (y < copyH) {
                    int srcRow = y * mAllocW;
                    for (int x = 0; x < copyW; x++) {
                        int px = mCanvas[srcRow + x];
                        out[dstRow + x] = (px == 0) ? fillPixel : px;
                    }
                    for (int x = copyW; x < finalW; x++) {
                        out[dstRow + x] = fillPixel;
                    }
                } else {
                    Arrays.fill(out, dstRow, dstRow + finalW, fillPixel);
                }
            }
        }
        return new SixelImage(finalW, finalH, out, 0L, 0, 1, 1);
    }

    /**
     * Converts VT340 HLS (H: 0..360 where 0=blue, 120=red, 240=green; L: 0..100; S: 0..100) to ARGB.
     */
    private static int vt340HlsToArgb(int rawHue, int rawLight, int rawSat) {
        int vtHue = ((rawHue % 360) + 360) % 360;
        float hueDeg = (vtHue + 240) % 360;
        float h = hueDeg / 360f;
        float l = clampRange(rawLight, 0, 100) / 100f;
        float s = clampRange(rawSat, 0, 100) / 100f;

        float r, g, b;
        if (s <= 0f) {
            r = g = b = l;
        } else {
            float q = (l < 0.5f) ? (l * (1f + s)) : (l + s - l * s);
            float p = 2f * l - q;
            r = hueToRgb(p, q, h + 1f / 3f);
            g = hueToRgb(p, q, h);
            b = hueToRgb(p, q, h - 1f / 3f);
        }
        return 0xFF000000
            | (clampByte(Math.round(r * 255f)) << 16)
            | (clampByte(Math.round(g * 255f)) << 8)
            | clampByte(Math.round(b * 255f));
    }

    private static float hueToRgb(float p, float q, float t) {
        if (t < 0f) t += 1f;
        if (t > 1f) t -= 1f;
        if (t < 1f / 6f) return p + (q - p) * 6f * t;
        if (t < 0.5f) return q;
        if (t < 2f / 3f) return p + (q - p) * (2f / 3f - t) * 6f;
        return p;
    }

    private static int clampRange(int val, int min, int max) {
        return Math.max(min, Math.min(max, val));
    }

    private static int clampByte(int v) {
        return Math.max(0, Math.min(255, v));
    }

    /** Generates a compact colorful 240x72 DebianTerm Sixel graphic for quick in-terminal testing. */
    public static String generateDemoSixelSequence() {
        StringBuilder sb = new StringBuilder();
        sb.append("\033P0;1q\"1;1;240;72");
        // Palette: Debian magenta, red, green, blue, yellow, cyan, light gray
        sb.append("#1;2;84;4;33");   // Debian Magenta (#D70A53)
        sb.append("#2;2;96;32;30");  // Red
        sb.append("#3;2;25;73;31");  // Green
        sb.append("#4;2;35;65;100"); // Blue
        sb.append("#5;2;95;78;25");  // Yellow
        sb.append("#6;2;22;78;82");  // Cyan
        sb.append("#7;2;13;17;23");  // Frame dark (#21262D)

        // Band 0..11: top Debian Magenta border + color bars
        sb.append("#1!240~-#1!240~-");
        // 8 middle bands (6px each = 48px) with 6 color stripes framed by dark gray
        for (int band = 0; band < 8; band++) {
            sb.append("#7!12~");
            sb.append("#1!36~#2!36~#3!36~#4!36~#5!36~#6!36~");
            sb.append("#7!12~-");
        }
        // Bottom Debian Magenta border (12px)
        sb.append("#1!240~-#1!240~");
        sb.append("\033\\");
        return sb.toString();
    }
}
