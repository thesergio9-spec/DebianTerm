import com.debianchroot.term.ChrootInstaller;
import com.debianchroot.term.ChrootManager;
import com.debianchroot.term.ProotManager;
import com.debianchroot.term.SystemRequirements;
import com.debianchroot.term.TarExtractor;
import com.debianchroot.term.TerminalSettings;
import com.termux.terminal.TerminalColorScheme;
import com.termux.terminal.TerminalEmulator;
import com.termux.terminal.TerminalOutput;
import com.termux.terminal.TerminalSession;
import com.termux.terminal.TerminalSessionClient;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * JVM verification suite for DebianTerm:
 * 1) DECSET 2026 synchronized output + DECRQM query
 * 2) Soft-keyboard main-screen pan without PTY resize / scrollback loss
 * 3) Live LXC Debian bookworm/trixie index & SHA256SUMS parsing
 * 4) Generated /data/local/bin/debian entry script syntax and env isolation
 * 5) Color schemes validation against TerminalColorScheme
 * 6) SystemRequirements evaluation (root, non-root PRoot, low storage, old Android)
 * 7) TarExtractor (permissions, symlinks, hardlinks, PAX long path, dev skip, traversal block, safe delete)
 * 8) Generated PRoot launcher script syntax and flags
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
        testSystemRequirements();
        testTarExtractor();
        testProotLauncherScript();
        System.out.println("ALL 8 VERIFICATION TESTS PASSED.");
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

    private static void testSystemRequirements() {
        SystemRequirements.Report rootOk = SystemRequirements.evaluateBaseChecks(
            35, "15", "arm64-v8a", 8192, 4096, 20000, 64000,
            true, true, true, "/debug_ramdisk/su", "27.0:MAGISKSU", true, false);
        check(rootOk.meetsMinimumRequirements(), "Root device should meet minimum requirements");
        check(rootOk.hasEnoughStorageForInstall(), "20GB should be enough for install");
        check(rootOk.canUseRootPath(), "Should allow root path");
        check(rootOk.canUseProotPath(), "Should allow PRoot path");
        check(TerminalSettings.MODE_ROOT.equals(rootOk.recommendedMode()), "Root should be recommended when available");

        SystemRequirements.Report noRoot = SystemRequirements.evaluateBaseChecks(
            34, "14", "arm64-v8a", 4096, 2048, 5000, 32000,
            true, true, false, "", "", true, false);
        check(!noRoot.canUseRootPath(), "Non-root should not enable root path");
        check(noRoot.canUseProotPath(), "Non-root should enable PRoot path");
        check(TerminalSettings.MODE_PROOT.equals(noRoot.recommendedMode()), "PRoot must be recommended when no root");
        check(findItem(noRoot, "root").status == SystemRequirements.Status.INFO, "Root item should be INFO when absent");

        SystemRequirements.Report lowStorage = SystemRequirements.evaluateBaseChecks(
            34, "14", "arm64-v8a", 4096, 1500, 400, 32000,
            true, true, false, "", "", true, false);
        check(!lowStorage.hasEnoughStorageForInstall(), "400 MB must fail install storage threshold");
        check(findItem(lowStorage, "storage").status == SystemRequirements.Status.FAIL, "Storage item should be FAIL");

        SystemRequirements.Report oldOs = SystemRequirements.evaluateBaseChecks(
            21, "5.0", "arm64-v8a", 4096, 1500, 5000, 32000,
            true, true, false, "", "", true, false);
        check(!oldOs.meetsMinimumRequirements(), "API 21 must fail minimum OS check");
        check(findItem(oldOs, "os").status == SystemRequirements.Status.FAIL, "OS item should be FAIL");

        System.out.println("[PASS] Test 6: SystemRequirements evaluation (root, non-root PRoot, low storage, old Android)");
    }

    private static SystemRequirements.CheckItem findItem(SystemRequirements.Report r, String id) {
        for (SystemRequirements.CheckItem item : r.items) {
            if (id.equals(item.id)) return item;
        }
        throw new AssertionError("Missing check item: " + id);
    }

    private static void testTarExtractor() throws Exception {
        ByteArrayOutputStream tarBytes = new ByteArrayOutputStream();
        // 1. Read-only directory 0555 -> must become writable by owner (0700 bits)
        writeTarEntry(tarBytes, "sub/", '5', 0555, "", new byte[0]);
        // 2. Read-only regular file 0400 -> must become u+rw
        byte[] content = "hola-debian12\n".getBytes(StandardCharsets.UTF_8);
        writeTarEntry(tarBytes, "sub/hello.txt", '0', 0400, "", content);
        // 3. Symlink sub/link.txt -> hello.txt
        writeTarEntry(tarBytes, "sub/link.txt", '2', 0777, "hello.txt", new byte[0]);
        // 4. Hardlink sub/hard.txt -> sub/hello.txt
        writeTarEntry(tarBytes, "sub/hard.txt", '1', 0644, "sub/hello.txt", new byte[0]);
        // 5. PAX extended header (> 120 chars path)
        String longPath = "very/long/directory/structure/for/testing/pax/extended/header/"
            + "more_segments_to_exceed_one_hundred_twenty_characters_safely/deep_file.txt";
        check(longPath.length() > 120, "Long path test length must exceed 120 chars");
        String paxPayload = formatPaxRecord("path", longPath);
        writeTarEntry(tarBytes, "PaxHeaders.0/deep_file", 'x', 0644, "", paxPayload.getBytes(StandardCharsets.UTF_8));
        byte[] longContent = "pax-ok".getBytes(StandardCharsets.UTF_8);
        writeTarEntry(tarBytes, "truncated_placeholder", '0', 0644, "", longContent);
        // 6. Device node and file under dev/ (must be skipped when skipDevEntries=true)
        writeTarEntry(tarBytes, "dev/", '5', 0755, "", new byte[0]);
        writeTarEntry(tarBytes, "dev/null", '3', 0666, "", new byte[0]);
        writeTarEntry(tarBytes, "dev/tty_regular", '0', 0644, "", "nope".getBytes(StandardCharsets.UTF_8));
        // 7. Path traversal attempt (must be rejected)
        writeTarEntry(tarBytes, "../escape.txt", '0', 0644, "", "evil".getBytes(StandardCharsets.UTF_8));
        // EOF zero blocks
        tarBytes.write(new byte[1024]);

        Path sandbox = Files.createTempDirectory("debianterm-tar-test");
        File destRoot = new File(sandbox.toFile(), "rootfs");
        File outsideDir = new File(sandbox.toFile(), "outside");
        File preciousFile = new File(outsideDir, "precious.txt");
        try {
            //noinspection ResultOfMethodCallIgnored
            outsideDir.mkdirs();
            Files.write(preciousFile.toPath(), "keep-me".getBytes(StandardCharsets.UTF_8));

            TarExtractor.extractTar(new ByteArrayInputStream(tarBytes.toByteArray()),
                destRoot, true, new AtomicBoolean(false), null);

            File subDir = new File(destRoot, "sub");
            check(subDir.isDirectory() && subDir.canWrite(), "Extracted dir 0555 must keep owner write bit");

            File hello = new File(destRoot, "sub/hello.txt");
            check(hello.isFile() && hello.canRead() && hello.canWrite(), "Extracted file 0400 must be owner rw");
            check("hola-debian12\n".equals(new String(Files.readAllBytes(hello.toPath()), StandardCharsets.UTF_8)),
                "Extracted file contents mismatch");

            File sym = new File(destRoot, "sub/link.txt");
            check(Files.isSymbolicLink(sym.toPath()), "Expected symbolic link at sub/link.txt");

            File hard = new File(destRoot, "sub/hard.txt");
            check(hard.isFile(), "Expected copied hardlink at sub/hard.txt");
            check("hola-debian12\n".equals(new String(Files.readAllBytes(hard.toPath()), StandardCharsets.UTF_8)),
                "Hardlink copy content mismatch");

            File paxFile = new File(destRoot, longPath);
            check(paxFile.isFile() && "pax-ok".equals(new String(Files.readAllBytes(paxFile.toPath()), StandardCharsets.UTF_8)),
                "PAX long path file missing or corrupted");

            check(!new File(destRoot, "dev/null").exists(), "dev/null should be skipped");
            check(!new File(destRoot, "dev/tty_regular").exists(), "dev/tty_regular should be skipped");
            check(!new File(sandbox.toFile(), "escape.txt").exists(), "Path traversal ../escape.txt must be blocked");

            // Symlink inside rootfs pointing outside must NOT delete outside contents
            Files.createSymbolicLink(new File(destRoot, "sym_outside").toPath(), outsideDir.toPath());
            check(TarExtractor.deleteRecursivelyNoFollow(destRoot), "deleteRecursivelyNoFollow should succeed");
            check(!destRoot.exists(), "destRoot should be deleted");
            check(preciousFile.exists(), "External symlink target must NEVER be deleted!");
        } finally {
            Files.deleteIfExists(preciousFile.toPath());
            Files.deleteIfExists(outsideDir.toPath());
            TarExtractor.deleteRecursivelyNoFollow(sandbox.toFile());
        }
        System.out.println("[PASS] Test 7: TarExtractor (perms, symlinks, hardlinks, PAX >120, dev skip, traversal block, safe delete)");
    }

    private static String formatPaxRecord(String key, String value) {
        String body = " " + key + "=" + value + "\n";
        int totalLen = body.getBytes(StandardCharsets.UTF_8).length + 1;
        while (true) {
            String rec = totalLen + body;
            int actual = rec.getBytes(StandardCharsets.UTF_8).length;
            if (actual == totalLen) return rec;
            totalLen = actual;
        }
    }

    private static void writeTarEntry(
        ByteArrayOutputStream out,
        String name,
        char typeFlag,
        int mode,
        String linkName,
        byte[] data
    ) throws Exception {
        byte[] hdr = new byte[512];
        byte[] nameBytes = name.getBytes(StandardCharsets.UTF_8);
        System.arraycopy(nameBytes, 0, hdr, 0, Math.min(nameBytes.length, 100));
        writeOctal(hdr, 100, 8, mode);
        writeOctal(hdr, 108, 8, 0);
        writeOctal(hdr, 116, 8, 0);
        writeOctal(hdr, 124, 12, data.length);
        writeOctal(hdr, 136, 12, 1700000000L);
        for (int i = 148; i < 156; i++) hdr[i] = ' ';
        hdr[156] = (byte) typeFlag;
        byte[] linkBytes = linkName.getBytes(StandardCharsets.UTF_8);
        System.arraycopy(linkBytes, 0, hdr, 157, Math.min(linkBytes.length, 100));
        byte[] magic = "ustar\0".getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(magic, 0, hdr, 257, 6);
        hdr[263] = '0';
        hdr[264] = '0';
        int sum = 0;
        for (byte b : hdr) sum += (b & 0xff);
        writeOctal(hdr, 148, 7, sum);
        hdr[155] = ' ';
        out.write(hdr);
        if (data.length > 0) {
            out.write(data);
            int rem = 512 - (data.length % 512);
            if (rem < 512) out.write(new byte[rem]);
        }
    }

    private static void writeOctal(byte[] buf, int off, int len, long value) {
        String s = Long.toOctalString(value);
        int idx = off + len - 1;
        buf[idx--] = 0;
        for (int i = s.length() - 1; i >= 0 && idx >= off; i--) {
            buf[idx--] = (byte) s.charAt(i);
        }
        while (idx >= off) {
            buf[idx--] = '0';
        }
    }

    private static void testProotLauncherScript() throws Exception {
        String script = ProotManager.generateProotLauncherScript(
            "/data/app/x/lib/arm64",
            "/data/user/0/com.debianchroot.term/files",
            "/data/user/0/com.debianchroot.term/files/debian12-proot",
            true
        );
        check(script.contains("--link2symlink"), "PRoot script must contain --link2symlink");
        check(script.contains("--root-id"), "PRoot script must contain --root-id");
        check(script.contains("PROOT_LOADER"), "PRoot script must export PROOT_LOADER");
        check(script.contains("PROOT_TMP_DIR"), "PRoot script must export PROOT_TMP_DIR");
        check(script.contains("PROOT_NO_SECCOMP=1"), "PRoot script must export PROOT_NO_SECCOMP=1 when enabled");
        check(script.contains("env -i"), "PRoot script must isolate env with env -i");
        check(!script.contains("TERMUX_"), "PRoot script must not leak TERMUX_ vars");

        Path tmp = Paths.get("/tmp/test-proot-script.sh");
        Files.write(tmp, script.getBytes(StandardCharsets.UTF_8));
        try {
            Process p = new ProcessBuilder("sh", "-n", tmp.toString()).start();
            int rc = p.waitFor();
            check(rc == 0, "PRoot sh -n failed with exit code " + rc);
        } finally {
            Files.deleteIfExists(tmp);
        }
        System.out.println("[PASS] Test 8: Generated PRoot launcher script syntax (sh -n) and flags");
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
