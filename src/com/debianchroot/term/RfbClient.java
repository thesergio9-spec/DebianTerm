package com.debianchroot.term;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;

/**
 * Pure Java RFB 3.3 / 3.7 / 3.8 VNC client supporting None & VNCAuth (DES) security,
 * Raw, CopyRect, Zlib (persistent Inflater stream), and DesktopSize pseudo-encoding.
 */
public final class RfbClient {

    public static final int ENCODING_RAW = 0;
    public static final int ENCODING_COPYRECT = 1;
    public static final int ENCODING_ZLIB = 6;
    public static final int ENCODING_DESKTOP_SIZE = -223;
    public static final int ENCODING_EXTENDED_DESKTOP_SIZE = -308;

    public interface FrameListener {
        void onConnected(int width, int height, String desktopName);
        void onFramebufferResize(int width, int height);
        void onFrameUpdated(int[] pixels, int width, int height, int x, int y, int w, int h);
        void onServerCutText(String text);
        void onDisconnected(String reason);
    }

    private final Object mOutLock = new Object();
    private volatile boolean mClosed = false;
    private volatile boolean mConnected = false;

    private Socket mSocket;
    private InputStream mIn;
    private OutputStream mOut;
    private Thread mWorkerThread;
    private volatile ExecutorService mWriteExecutor;

    private int mWidth = 0;
    private int mHeight = 0;
    private int[] mPixels = new int[0];
    private Inflater mZlibInflater;
    private byte[] mZlibDecompScratch = new byte[65536];

    private volatile boolean mSupportsExtendedDesktopSize = false;
    private volatile boolean mHasPrimaryScreen = false;
    private volatile int mPrimaryScreenId = 0;
    private volatile int mPrimaryScreenFlags = 0;
    private volatile int mPendingResizeWidth = 0;
    private volatile int mPendingResizeHeight = 0;

    public boolean isConnected() {
        return mConnected && !mClosed;
    }

    public int getWidth() {
        return mWidth;
    }

    public int getHeight() {
        return mHeight;
    }

    public void connectWithRetry(
        final String host,
        final int port,
        final String password,
        final int maxRetries,
        final long retryDelayMs,
        final FrameListener listener
    ) {
        mClosed = false;
        mWorkerThread = new Thread(() -> runConnectionLoop(
            host, port, password, Math.max(1, maxRetries), Math.max(100L, retryDelayMs), listener),
            "DebianTerm-RFB");
        mWorkerThread.setDaemon(true);
        mWorkerThread.start();
    }

    public void disconnect() {
        mClosed = true;
        mConnected = false;
        shutdownWriter();
        closeSocketQuietly();
        if (mZlibInflater != null) {
            try {
                mZlibInflater.end();
            } catch (Exception ignored) {
            }
            mZlibInflater = null;
        }
    }

    private void shutdownWriter() {
        ExecutorService exec = mWriteExecutor;
        mWriteExecutor = null;
        if (exec != null) {
            exec.shutdownNow();
        }
    }

    public void sendPointerEvent(int buttonMask, int x, int y) {
        if (!isConnected()) return;
        int cx = Math.max(0, Math.min(Math.max(0, mWidth - 1), x));
        int cy = Math.max(0, Math.min(Math.max(0, mHeight - 1), y));
        byte[] msg = new byte[6];
        msg[0] = 5;
        msg[1] = (byte) (buttonMask & 0xff);
        msg[2] = (byte) ((cx >> 8) & 0xff);
        msg[3] = (byte) (cx & 0xff);
        msg[4] = (byte) ((cy >> 8) & 0xff);
        msg[5] = (byte) (cy & 0xff);
        writePacket(msg);
    }

    public void sendKeyEvent(boolean down, int keysym) {
        if (!isConnected()) return;
        byte[] msg = new byte[8];
        msg[0] = 4;
        msg[1] = (byte) (down ? 1 : 0);
        msg[2] = 0;
        msg[3] = 0;
        msg[4] = (byte) ((keysym >> 24) & 0xff);
        msg[5] = (byte) ((keysym >> 16) & 0xff);
        msg[6] = (byte) ((keysym >> 8) & 0xff);
        msg[7] = (byte) (keysym & 0xff);
        writePacket(msg);
    }

    public void sendClientCutText(String text) {
        if (!isConnected() || text == null) return;
        byte[] raw = text.getBytes(StandardCharsets.ISO_8859_1);
        byte[] msg = new byte[8 + raw.length];
        msg[0] = 6;
        msg[4] = (byte) ((raw.length >> 24) & 0xff);
        msg[5] = (byte) ((raw.length >> 16) & 0xff);
        msg[6] = (byte) ((raw.length >> 8) & 0xff);
        msg[7] = (byte) (raw.length & 0xff);
        System.arraycopy(raw, 0, msg, 8, raw.length);
        writePacket(msg);
    }

    public boolean supportsDesktopResize() {
        return mSupportsExtendedDesktopSize && mHasPrimaryScreen;
    }

    /**
     * Asks the VNC server to resize its desktop (RFB SetDesktopSize). Returns true when the request
     * was sent now; otherwise the target is remembered and sent after the server advertises support.
     */
    public boolean requestDesktopResize(int width, int height) {
        int w = Math.max(480, Math.min(3840, width));
        int h = Math.max(480, Math.min(3840, height));
        if (!isConnected() || !mSupportsExtendedDesktopSize || !mHasPrimaryScreen) {
            mPendingResizeWidth = w;
            mPendingResizeHeight = h;
            return false;
        }
        mPendingResizeWidth = 0;
        mPendingResizeHeight = 0;
        if (w == mWidth && h == mHeight) {
            return false;
        }
        sendSetDesktopSizePacket(w, h);
        sendFramebufferUpdateRequest(true, 0, 0, mWidth, mHeight);
        return true;
    }

    private void sendSetDesktopSizePacket(int w, int h) {
        byte[] msg = new byte[24];
        msg[0] = (byte) 251;
        msg[2] = (byte) ((w >> 8) & 0xff);
        msg[3] = (byte) (w & 0xff);
        msg[4] = (byte) ((h >> 8) & 0xff);
        msg[5] = (byte) (h & 0xff);
        msg[6] = 1; // number of screens
        int id = mPrimaryScreenId;
        msg[8] = (byte) ((id >> 24) & 0xff);
        msg[9] = (byte) ((id >> 16) & 0xff);
        msg[10] = (byte) ((id >> 8) & 0xff);
        msg[11] = (byte) (id & 0xff);
        msg[16] = (byte) ((w >> 8) & 0xff);
        msg[17] = (byte) (w & 0xff);
        msg[18] = (byte) ((h >> 8) & 0xff);
        msg[19] = (byte) (h & 0xff);
        int fl = mPrimaryScreenFlags;
        msg[20] = (byte) ((fl >> 24) & 0xff);
        msg[21] = (byte) ((fl >> 16) & 0xff);
        msg[22] = (byte) ((fl >> 8) & 0xff);
        msg[23] = (byte) (fl & 0xff);
        writePacket(msg);
    }

    public void requestFullUpdate() {
        if (!isConnected() || mWidth <= 0 || mHeight <= 0) return;
        sendFramebufferUpdateRequest(false, 0, 0, mWidth, mHeight);
    }

    private void runConnectionLoop(
        String host,
        int port,
        String password,
        int maxRetries,
        long retryDelayMs,
        FrameListener listener
    ) {
        String disconnectReason = "Desconectado";
        mSupportsExtendedDesktopSize = false;
        mHasPrimaryScreen = false;
        try {
            Socket sock = null;
            IOException lastErr = null;
            for (int attempt = 0; attempt < maxRetries && !mClosed; attempt++) {
                Socket candidate = new Socket();
                try {
                    candidate.connect(new InetSocketAddress(host, port), 1500);
                    candidate.setTcpNoDelay(true);
                    sock = candidate;
                    break;
                } catch (IOException e) {
                    lastErr = e;
                    try {
                        candidate.close();
                    } catch (IOException ignored) {
                    }
                    if (attempt + 1 < maxRetries && !mClosed) {
                        try {
                            Thread.sleep(retryDelayMs);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            return;
                        }
                    }
                }
            }

            if (mClosed) return;
            if (sock == null) {
                if (listener != null) {
                    listener.onDisconnected(lastErr != null
                        ? ("No se pudo conectar al servidor VNC: " + lastErr.getMessage())
                        : "Servidor VNC no respondió");
                }
                return;
            }

            mSocket = sock;
            mIn = new BufferedInputStream(sock.getInputStream(), 65536);
            mOut = sock.getOutputStream();
            mWriteExecutor = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "DebianTerm-RFB-Writer");
                t.setDaemon(true);
                return t;
            });
            mZlibInflater = new Inflater();

            String desktopName = performHandshake(password);
            sendSetPixelFormat32Bgrx();
            sendSupportedEncodings();
            sendFramebufferUpdateRequest(false, 0, 0, mWidth, mHeight, true);
            mConnected = true;

            if (listener != null) {
                listener.onConnected(mWidth, mHeight, desktopName);
            }

            readServerMessages(listener);
        } catch (EOFException eof) {
            disconnectReason = "Sesión VNC cerrada por el servidor";
        } catch (Exception e) {
            if (!mClosed) {
                disconnectReason = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            }
        } finally {
            boolean wasClosedByUser = mClosed;
            mConnected = false;
            shutdownWriter();
            closeSocketQuietly();
            if (mZlibInflater != null) {
                try {
                    mZlibInflater.end();
                } catch (Exception ignored) {
                }
                mZlibInflater = null;
            }
            if (!wasClosedByUser && listener != null) {
                listener.onDisconnected(disconnectReason);
            }
        }
    }

    private String performHandshake(String password) throws Exception {
        byte[] verBuf = readExact(12);
        String verStr = new String(verBuf, StandardCharsets.US_ASCII);
        if (!verStr.startsWith("RFB ")) {
            throw new IOException("Protocolo no es RFB/VNC: " + verStr);
        }
        int major = parseDigitBlock(verStr, 4, 7);
        int minor = parseDigitBlock(verStr, 8, 11);

        int negotiatedMinor;
        if (major > 3 || minor >= 8) {
            negotiatedMinor = 8;
        } else if (minor == 7) {
            negotiatedMinor = 7;
        } else {
            negotiatedMinor = 3;
        }

        byte[] replyVer = String.format("RFB 003.%03d\n", negotiatedMinor).getBytes(StandardCharsets.US_ASCII);
        writePacketSync(replyVer);

        if (negotiatedMinor == 3) {
            int secType = readInt32();
            if (secType == 0) {
                throw new IOException(readReasonString());
            } else if (secType == 1) {
                // RFB 3.3 None skips SecurityResult
            } else if (secType == 2) {
                handleVncAuthChallenge(password);
                int secResult = readInt32();
                if (secResult != 0) {
                    throw new IOException("Autenticación VNC rechazada");
                }
            } else {
                throw new IOException("Tipo de seguridad RFB 3.3 no soportado: " + secType);
            }
        } else {
            int nTypes = readU8();
            if (nTypes == 0) {
                throw new IOException(readReasonString());
            }
            byte[] offered = readExact(nTypes);
            boolean hasNone = false;
            boolean hasVncAuth = false;
            for (byte b : offered) {
                int t = b & 0xff;
                if (t == 1) hasNone = true;
                if (t == 2) hasVncAuth = true;
            }

            int chosen;
            if ((password == null || password.isEmpty()) && hasNone) {
                chosen = 1;
            } else if (hasVncAuth) {
                chosen = 2;
            } else if (hasNone) {
                chosen = 1;
            } else {
                throw new IOException("El servidor VNC exige un método de autenticación no soportado");
            }

            writePacketSync(new byte[] { (byte) chosen });
            if (chosen == 2) {
                handleVncAuthChallenge(password == null ? "" : password);
            }
            int secResult = readInt32();
            if (secResult != 0) {
                if (negotiatedMinor >= 8) {
                    throw new IOException("Fallo de autenticación VNC: " + readReasonString());
                }
                throw new IOException("Fallo de autenticación VNC (código " + secResult + ")");
            }
        }

        // ClientInit: shared = 1
        writePacketSync(new byte[] { 1 });

        // ServerInit
        mWidth = readU16();
        mHeight = readU16();
        readExact(16); // server pixel format
        int nameLen = readInt32();
        String desktopName = "Debian";
        if (nameLen > 0 && nameLen <= 65536) {
            desktopName = new String(readExact(nameLen), StandardCharsets.UTF_8);
        } else if (nameLen > 65536) {
            skipExact(nameLen);
        }

        if (mWidth <= 0 || mHeight <= 0 || mWidth > 8192 || mHeight > 8192) {
            throw new IOException("Dimensiones de framebuffer inválidas: " + mWidth + "x" + mHeight);
        }
        mPixels = new int[mWidth * mHeight];
        Arrays.fill(mPixels, 0xFF0D1117);
        return desktopName;
    }

    private void handleVncAuthChallenge(String password) throws Exception {
        byte[] challenge = readExact(16);
        byte[] key = new byte[8];
        byte[] pwBytes = password != null ? password.getBytes(StandardCharsets.ISO_8859_1) : new byte[0];
        for (int i = 0; i < 8; i++) {
            int raw = i < pwBytes.length ? (pwBytes[i] & 0xff) : 0;
            // RFB VNCAuth reverses bit order within each byte of the DES key
            key[i] = (byte) (Integer.reverse(raw) >>> 24);
        }
        Cipher cipher = Cipher.getInstance("DES/ECB/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "DES"));
        byte[] response = cipher.doFinal(challenge);
        writePacketSync(response);
    }

    private void sendSetPixelFormat32Bgrx() {
        byte[] msg = new byte[20];
        msg[0] = 0; // SetPixelFormat
        msg[4] = 32; // bits-per-pixel
        msg[5] = 24; // depth
        msg[6] = 0;  // little-endian
        msg[7] = 1;  // true-color
        // red-max = 255
        msg[8] = 0;
        msg[9] = (byte) 255;
        // green-max = 255
        msg[10] = 0;
        msg[11] = (byte) 255;
        // blue-max = 255
        msg[12] = 0;
        msg[13] = (byte) 255;
        msg[14] = 16; // red-shift
        msg[15] = 8;  // green-shift
        msg[16] = 0;  // blue-shift
        writePacketSync(msg);
    }

    private void sendSupportedEncodings() {
        int[] encs = {
            ENCODING_COPYRECT,
            ENCODING_ZLIB,
            ENCODING_RAW,
            ENCODING_EXTENDED_DESKTOP_SIZE,
            ENCODING_DESKTOP_SIZE
        };
        byte[] msg = new byte[4 + encs.length * 4];
        msg[0] = 2; // SetEncodings
        msg[2] = (byte) ((encs.length >> 8) & 0xff);
        msg[3] = (byte) (encs.length & 0xff);
        for (int i = 0; i < encs.length; i++) {
            int e = encs[i];
            int off = 4 + i * 4;
            msg[off] = (byte) ((e >> 24) & 0xff);
            msg[off + 1] = (byte) ((e >> 16) & 0xff);
            msg[off + 2] = (byte) ((e >> 8) & 0xff);
            msg[off + 3] = (byte) (e & 0xff);
        }
        writePacketSync(msg);
    }

    private void sendFramebufferUpdateRequest(boolean incremental, int x, int y, int w, int h) {
        sendFramebufferUpdateRequest(incremental, x, y, w, h, false);
    }

    private void sendFramebufferUpdateRequest(boolean incremental, int x, int y, int w, int h, boolean sync) {
        byte[] msg = new byte[10];
        msg[0] = 3; // FramebufferUpdateRequest
        msg[1] = (byte) (incremental ? 1 : 0);
        msg[2] = (byte) ((x >> 8) & 0xff);
        msg[3] = (byte) (x & 0xff);
        msg[4] = (byte) ((y >> 8) & 0xff);
        msg[5] = (byte) (y & 0xff);
        msg[6] = (byte) ((w >> 8) & 0xff);
        msg[7] = (byte) (w & 0xff);
        msg[8] = (byte) ((h >> 8) & 0xff);
        msg[9] = (byte) (h & 0xff);
        if (sync) {
            writePacketSync(msg);
        } else {
            writePacket(msg);
        }
    }

    private void readServerMessages(FrameListener listener) throws IOException, DataFormatException {
        byte[] rawScratch = new byte[65536];
        while (!mClosed) {
            int msgType = readU8();
            switch (msgType) {
                case 0: { // FramebufferUpdate
                    readU8(); // padding
                    int numRects = readU16();
                    boolean needFullAfterResize = false;
                    for (int i = 0; i < numRects && !mClosed; i++) {
                        int rx = readU16();
                        int ry = readU16();
                        int rw = readU16();
                        int rh = readU16();
                        int enc = readInt32();

                        if (enc == ENCODING_EXTENDED_DESKTOP_SIZE) {
                            int numScreens = readU8();
                            readExact(3);
                            for (int s = 0; s < numScreens; s++) {
                                int sid = readInt32();
                                readU16();
                                readU16();
                                readU16();
                                readU16();
                                int sflags = readInt32();
                                if (s == 0) {
                                    mPrimaryScreenId = sid;
                                    mPrimaryScreenFlags = sflags;
                                    mHasPrimaryScreen = true;
                                }
                            }
                            mSupportsExtendedDesktopSize = true;
                            if (ry == 0 && rw > 0 && rh > 0 && rw <= 8192 && rh <= 8192
                                && (rw != mWidth || rh != mHeight)) {
                                mWidth = rw;
                                mHeight = rh;
                                mPixels = new int[rw * rh];
                                Arrays.fill(mPixels, 0xFF0D1117);
                                needFullAfterResize = true;
                                if (listener != null) {
                                    listener.onFramebufferResize(rw, rh);
                                }
                            }
                            if (mPendingResizeWidth > 0 && mPendingResizeHeight > 0) {
                                int pw = mPendingResizeWidth;
                                int ph = mPendingResizeHeight;
                                mPendingResizeWidth = 0;
                                mPendingResizeHeight = 0;
                                if (pw != mWidth || ph != mHeight) {
                                    sendSetDesktopSizePacket(pw, ph);
                                }
                            }
                            continue;
                        }

                        if (enc == ENCODING_DESKTOP_SIZE) {
                            if (rw > 0 && rh > 0 && rw <= 8192 && rh <= 8192) {
                                mWidth = rw;
                                mHeight = rh;
                                mPixels = new int[rw * rh];
                                Arrays.fill(mPixels, 0xFF0D1117);
                                needFullAfterResize = true;
                                if (listener != null) {
                                    listener.onFramebufferResize(rw, rh);
                                }
                            }
                            continue;
                        }

                        if (enc == ENCODING_RAW) {
                            int byteCount = rw * rh * 4;
                            if (rawScratch.length < byteCount) {
                                rawScratch = new byte[byteCount];
                            }
                            readExactInto(rawScratch, byteCount);
                            decodeBgrxToPixels(rawScratch, rx, ry, rw, rh);
                            if (listener != null && rw > 0 && rh > 0) {
                                listener.onFrameUpdated(mPixels, mWidth, mHeight, rx, ry, rw, rh);
                            }
                        } else if (enc == ENCODING_COPYRECT) {
                            int srcX = readU16();
                            int srcY = readU16();
                            applyCopyRect(srcX, srcY, rx, ry, rw, rh);
                            if (listener != null && rw > 0 && rh > 0) {
                                listener.onFrameUpdated(mPixels, mWidth, mHeight, rx, ry, rw, rh);
                            }
                        } else if (enc == ENCODING_ZLIB) {
                            int zlibLen = readInt32();
                            if (zlibLen < 0 || zlibLen > 64 * 1024 * 1024) {
                                throw new IOException("Tamaño zlib inválido: " + zlibLen);
                            }
                            byte[] comp = readExact(zlibLen);
                            int expectedRaw = rw * rh * 4;
                            if (mZlibDecompScratch.length < expectedRaw) {
                                mZlibDecompScratch = new byte[expectedRaw];
                            }
                            inflateExact(comp, mZlibDecompScratch, expectedRaw);
                            decodeBgrxToPixels(mZlibDecompScratch, rx, ry, rw, rh);
                            if (listener != null && rw > 0 && rh > 0) {
                                listener.onFrameUpdated(mPixels, mWidth, mHeight, rx, ry, rw, rh);
                            }
                        } else {
                            throw new IOException("Codificación RFB inesperada: " + enc);
                        }
                    }
                    if (!mClosed && mWidth > 0 && mHeight > 0) {
                        sendFramebufferUpdateRequest(!needFullAfterResize, 0, 0, mWidth, mHeight);
                    }
                    break;
                }
                case 1: { // SetColourMapEntries
                    readU8(); // padding
                    readU16(); // firstColor
                    int numColors = readU16();
                    skipExact((long) numColors * 6L);
                    break;
                }
                case 2: // Bell
                    break;
                case 3: { // ServerCutText
                    readExact(3); // padding
                    int len = readInt32();
                    if (len > 0 && len <= 1048576) {
                        String text = new String(readExact(len), StandardCharsets.ISO_8859_1);
                        if (listener != null) {
                            listener.onServerCutText(text);
                        }
                    } else if (len > 0) {
                        skipExact(len);
                    }
                    break;
                }
                default:
                    throw new IOException("Mensaje RFB desconocido del servidor: " + msgType);
            }
        }
    }

    private void decodeBgrxToPixels(byte[] src, int rx, int ry, int rw, int rh) {
        int fbW = mWidth;
        int fbH = mHeight;
        int srcIdx = 0;
        for (int row = 0; row < rh; row++) {
            int dy = ry + row;
            if (dy < 0 || dy >= fbH) {
                srcIdx += rw * 4;
                continue;
            }
            int rowBase = dy * fbW;
            for (int col = 0; col < rw; col++) {
                int dx = rx + col;
                if (dx >= 0 && dx < fbW) {
                    int b0 = src[srcIdx] & 0xff;
                    int b1 = src[srcIdx + 1] & 0xff;
                    int b2 = src[srcIdx + 2] & 0xff;
                    mPixels[rowBase + dx] = 0xFF000000 | (b2 << 16) | (b1 << 8) | b0;
                }
                srcIdx += 4;
            }
        }
    }

    private void applyCopyRect(int srcX, int srcY, int dstX, int dstY, int rw, int rh) {
        if (rw <= 0 || rh <= 0) return;
        int fbW = mWidth;
        int fbH = mHeight;
        if (srcX < 0 || srcY < 0 || dstX < 0 || dstY < 0
            || srcX + rw > fbW || srcY + rh > fbH
            || dstX + rw > fbW || dstY + rh > fbH) {
            return;
        }
        if (dstY > srcY) {
            for (int row = rh - 1; row >= 0; row--) {
                System.arraycopy(mPixels, (srcY + row) * fbW + srcX,
                    mPixels, (dstY + row) * fbW + dstX, rw);
            }
        } else {
            for (int row = 0; row < rh; row++) {
                System.arraycopy(mPixels, (srcY + row) * fbW + srcX,
                    mPixels, (dstY + row) * fbW + dstX, rw);
            }
        }
    }

    private void inflateExact(byte[] compressed, byte[] out, int expectedLen) throws DataFormatException, IOException {
        if (expectedLen == 0) return;
        mZlibInflater.setInput(compressed);
        int off = 0;
        while (off < expectedLen) {
            int n = mZlibInflater.inflate(out, off, expectedLen - off);
            if (n > 0) {
                off += n;
            } else if (mZlibInflater.needsInput() || mZlibInflater.finished()) {
                throw new IOException("Stream Zlib RFB incompleto: " + off + "/" + expectedLen);
            } else if (mZlibInflater.needsDictionary()) {
                throw new IOException("Stream Zlib RFB requiere diccionario inesperado");
            }
        }
    }

    private void writePacket(final byte[] data) {
        ExecutorService exec = mWriteExecutor;
        if (exec != null && !mClosed) {
            try {
                exec.execute(() -> writePacketSync(data));
                return;
            } catch (RejectedExecutionException ignored) {
                return;
            }
        }
        writePacketSync(data);
    }

    private void writePacketSync(byte[] data) {
        synchronized (mOutLock) {
            OutputStream out = mOut;
            if (out == null || mClosed) return;
            try {
                out.write(data);
                out.flush();
            } catch (IOException e) {
                if (!mClosed) {
                    closeSocketQuietly();
                }
            }
        }
    }

    private int readU8() throws IOException {
        int b = mIn.read();
        if (b == -1) throw new EOFException("EOF en socket RFB");
        return b & 0xff;
    }

    private int readU16() throws IOException {
        int b1 = readU8();
        int b2 = readU8();
        return (b1 << 8) | b2;
    }

    private int readInt32() throws IOException {
        int b1 = readU8();
        int b2 = readU8();
        int b3 = readU8();
        int b4 = readU8();
        return (b1 << 24) | (b2 << 16) | (b3 << 8) | b4;
    }

    private byte[] readExact(int len) throws IOException {
        byte[] buf = new byte[len];
        readExactInto(buf, len);
        return buf;
    }

    private void readExactInto(byte[] dst, int len) throws IOException {
        int off = 0;
        while (off < len) {
            int n = mIn.read(dst, off, len - off);
            if (n == -1) throw new EOFException("EOF leyendo " + len + " bytes RFB");
            off += n;
        }
    }

    private void skipExact(long len) throws IOException {
        byte[] scratch = new byte[4096];
        long rem = len;
        while (rem > 0) {
            int n = mIn.read(scratch, 0, (int) Math.min(scratch.length, rem));
            if (n == -1) throw new EOFException("EOF saltando bytes RFB");
            rem -= n;
        }
    }

    private String readReasonString() throws IOException {
        int len = readInt32();
        if (len <= 0) return "Sin detalles";
        if (len > 4096) {
            String head = new String(readExact(4096), StandardCharsets.UTF_8);
            skipExact(len - 4096L);
            return head;
        }
        return new String(readExact(len), StandardCharsets.UTF_8);
    }

    private static int parseDigitBlock(String s, int start, int end) {
        try {
            return Integer.parseInt(s.substring(start, end));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private void closeSocketQuietly() {
        Socket s = mSocket;
        mSocket = null;
        if (s != null) {
            try {
                s.close();
            } catch (IOException ignored) {
            }
        }
    }
}
