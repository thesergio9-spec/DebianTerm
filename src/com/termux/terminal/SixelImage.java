package com.termux.terminal;

/**
 * Decoded inline Sixel graphic anchored to a logical row and column in a {@link TerminalBuffer}.
 * Kept free of android.* imports so terminal tests run cleanly on host JVM.
 */
public final class SixelImage {

    public final int widthPx;
    public final int heightPx;
    /** ARGB_8888 pixel buffer of size {@code widthPx * heightPx}. */
    public final int[] pixels;

    /** Monotonic document row coordinate where the top-left corner of the image is anchored. */
    public long anchorAbsoluteRow;
    /** 0-based terminal column where the image starts. */
    public int anchorCol;
    /** Logical terminal rows spanned vertically by this image. */
    public int rowSpan;
    /** Logical terminal columns spanned horizontally by this image. */
    public int colSpan;

    /** Lazy Android Bitmap cache populated by {@code TerminalRenderer}. */
    public transient Object cachedBitmap;

    public SixelImage(
        int widthPx,
        int heightPx,
        int[] pixels,
        long anchorAbsoluteRow,
        int anchorCol,
        int rowSpan,
        int colSpan
    ) {
        this.widthPx = widthPx;
        this.heightPx = heightPx;
        this.pixels = pixels;
        this.anchorAbsoluteRow = anchorAbsoluteRow;
        this.anchorCol = anchorCol;
        this.rowSpan = Math.max(1, rowSpan);
        this.colSpan = Math.max(1, colSpan);
    }
}
