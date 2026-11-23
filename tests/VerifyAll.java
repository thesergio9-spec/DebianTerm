import com.debianchroot.term.ChrootInstaller;
import com.debianchroot.term.ChrootManager;
import com.debianchroot.term.TerminalSettings;
import com.termux.terminal.TerminalColorScheme;
import com.termux.terminal.TerminalEmulator;
import com.termux.terminal.TerminalOutput;
import com.termux.terminal.TerminalSession;
import com.termux.terminal.TerminalSessionClient;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;

/**
 * JVM verification suite for DebianTerm:
 * 1) DECSET 2026 synchronized output + DECRQM query
 * 2) Soft-keyboard main-screen pan without PTY resize / scrollback loss
 * 3) Live LXC Debian bookworm/trixie index & SHA256SUMS parsing
 * 4) Generated /data/local/bin/debian entry script syntax and env isolation
 * 5) Color schemes validation against TerminalColorScheme
 */
public final class VerifyAll {

    private static final class RecordingOutput extends TerminalOutput {
        final ByteArrayOutputStream written = new ByteArrayOutputStream();

        @Override
        public void write(byte[] data, int offset, int count) {
            written.write(data, offset, count);
        }

        @Override public void titleChanged(String oldTitle, String newTitle) {}
        @Override public void onCopyTextToClipboard(String text) {}
        @Override public void onPasteTextFromClipboard() {}
        @Override public void onBell() {}
        @Override public void onColorsChanged() {}

        String drainAscii() {
            String s = new String(written.toByteArray(), StandardCharsets.UTF_8);
            written.reset();
            return s;
        }
    }

    private static final class StubSessionClient implements TerminalSessionClient {
        @Override public void onTextChanged(TerminalSession changedSession) {}
        @Override public void onTitleChanged(TerminalSession changedSession) {}
        @Override public void onSessionFinished(TerminalSession finishedSession) {}
        @Override public void onCopyTextToClipboard(TerminalSession session, String text) {}
        @Override public void onPasteTextFromClipboard(TerminalSession session) {}
        @Override public void onBell(TerminalSession session) {}
        @Override public void onColorsChanged(TerminalSession session) {}
        @Override public void onTerminalCursorStateChange(boolean state) {}
        @Override public Integer getTerminalCursorStyle() { return 0; }
        @Override public void logError(String tag, String message) {}
        @Override public void logWarn(String tag, String message) {}
        @Override public void logInfo(String tag, String message) {}
        @Override public void logDebug(String tag, String message) {}
        @Override public void logVerbose(String tag, String message) {}
        @Override public void logStackTraceWithMessage(String tag, String message, Exception e) {}
        @Override public void logStackTrace(String tag, Exception e) {}
    }

    private static void check(boolean condition, String msg) {
        if (!condition) {
            throw new AssertionError("FAILED: " + msg);
        }
    }

    private static void feed(TerminalEmulator em, String text) {
        byte[] b = text.getBytes(StandardCharsets.UTF_8);
        em.append(b, b.length);
    }

    public static void main(String[] args) throws Exception {
        testDecset2026();
        testViewportPanWithoutPtyResize();
        testLxcIndexAndSha256();
        testGeneratedEntryScript();
        testColorSchemes();
        System.out.println("ALL 5 VERIFICATION TESTS PASSED.");
    }

    private static void testDecset2026() {
        RecordingOutput out = new RecordingOutput();
        TerminalEmulator em = new TerminalEmulator(out, 80, 24, 10, 20, 2000, new StubSessionClient());

        check(!em.isSynchronizedOutputActive(), "DECSET 2026 should start inactive");

        feed(em, "\033[?2026$p");
        String replyOff = out.drainAscii();
        check("\033[?2026;2$y".equals(replyOff), "Expected DECRQM off reply, got: " + replyOff.replace("\033", "<ESC>"));

        feed(em, "\033[?2026h");
        check(em.isSynchronizedOutputActive(), "DECSET 2026 should become active after CSI ? 2026 h");

        feed(em, "\033[?2026$p");
        String replyOn = out.drainAscii();
        check("\033[?2026;1$y".equals(replyOn), "Expected DECRQM on reply, got: " + replyOn.replace("\033", "<ESC>"));

        feed(em, "\033[?2026l");
        check(!em.isSynchronizedOutputActive(), "DECSET 2026 should deactivate after CSI ? 2026 l");
        System.out.println("[PASS] Test 1: DECSET 2026 synchronized output + DECRQM");
    }

    private static void testViewportPanWithoutPtyResize() {
        RecordingOutput out = new RecordingOutput();
        TerminalEmulator em = new TerminalEmulator(out, 80, 40, 10, 20, 2000, new StubSessionClient());

        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= 100; i++) {
            sb.append("line-").append(i).append("\r\n");
        }
        feed(em, sb.toString());

        int beforeActiveTranscript = em.getScreen().getActiveTranscriptRows();
        check(beforeActiveTranscript >= 60, "Expected abundant scrollback rows, got: " + beforeActiveTranscript);
        check(em.mRows == 40, "Expected PTY mRows=40 before keyboard open");

        // Simulate soft keyboard opening: only visual rows shrink from 40 -> 20, PTY mRows stays 40
        em.setVisualRows(20);
        check(em.mRows == 40, "PTY mRows must remain 40 (no SIGWINCH/TerminalBuffer.resize)");
        check(em.getVisualRows() == 20, "Visual rows should be 20");
        check(em.getMaxTopRow() > 0, "Expected positive maxTopRow when keyboard shrinks viewport, got: " + em.getMaxTopRow());
        check(em.getScreen().getActiveTranscriptRows() == beforeActiveTranscript,
            "Scrollback rows must stay intact across keyboard open");

        String tailText = em.getScreen().getSelectedText(0, em.getMaxTopRow(), 79, em.getMaxTopRow() + 19);
        check(tailText.contains("line-100"), "Bottom pan viewport should keep showing latest prompt/output");

        // Simulate soft keyboard closing: visual rows expand back to 40
        em.setVisualRows(40);
        check(em.getMaxTopRow() == 0, "Expected maxTopRow==0 after keyboard closes");
        System.out.println("[PASS] Test 2: Viewport pan without PTY resize / rewind (scrollback=" + beforeActiveTranscript + " rows)");
    }

    private static void testLxcIndexAndSha256() throws Exception {
        String index = fetchText("https://images.linuxcontainers.org/meta/1.0/index-system");
        String bookwormPath = ChrootInstaller.parseLxcIndex(index, "bookworm", "arm64");
        String trixiePath = ChrootInstaller.parseLxcIndex(index, "trixie", "arm64");

        check(bookwormPath != null && bookwormPath.endsWith("/"), "Invalid bookworm arm64 path: " + bookwormPath);
        check(trixiePath != null && trixiePath.endsWith("/"), "Invalid trixie arm64 path: " + trixiePath);

        String sums = fetchText("https://images.linuxcontainers.org" + bookwormPath + "SHA256SUMS");
        String sha = ChrootInstaller.parseSha256Sums(sums, "rootfs.tar.xz");
        check(sha != null && sha.matches("^[0-9a-f]{64}$"), "Invalid SHA-256 parsed: " + sha);

        System.out.println("[PASS] Test 3: LXC parser (bookworm=" + bookwormPath + ", trixie=" + trixiePath + ", sha256=" + sha.substring(0, 12) + "...)");
    }

    private static void testGeneratedEntryScript() throws Exception {
        String script = ChrootManager.generateEntryScript("/data/local/debian");
        check(script.contains("env -i"), "Entry script must use env -i");
        check(!script.contains("TERMUX_"), "Entry script must not pass TERMUX_ variables");
        check(script.contains("CHROOT='/data/local/debian'"), "Entry script must set CHROOT='/data/local/debian'");

        Path tmp = Paths.get("/tmp/test-debian-script.sh");
        Files.write(tmp, script.getBytes(StandardCharsets.UTF_8));
        try {
            Process p = new ProcessBuilder("sh", "-n", tmp.toString()).start();
            int rc = p.waitFor();
            check(rc == 0, "sh -n failed with exit code " + rc);
        } finally {
            Files.deleteIfExists(tmp);
        }
        System.out.println("[PASS] Test 4: Generated /data/local/bin/debian syntax (sh -n) and env isolation");
    }

    private static void testColorSchemes() {
        for (String id : TerminalSettings.ALL_SCHEME_IDS) {
            Properties props = TerminalSettings.getColorProperties(id);
            TerminalColorScheme scheme = new TerminalColorScheme();
            scheme.updateWith(props);
        }
        System.out.println("[PASS] Test 5: All " + TerminalSettings.ALL_SCHEME_IDS.length + " color schemes parsed by TerminalColorScheme");
    }

    private static String fetchText(String urlStr) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(20000);
        try (InputStream is = conn.getInputStream();
             ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) != -1) {
                baos.write(buf, 0, n);
            }
            return new String(baos.toByteArray(), StandardCharsets.UTF_8);
        } finally {
            conn.disconnect();
        }
    }
}
