import com.debianchroot.term.ChrootInstaller;
import com.debianchroot.term.ChrootManager;
import com.debianchroot.term.DebianTermService;
import com.debianchroot.term.DesktopManager;
import com.debianchroot.term.ExtraKeysBarView;
import com.debianchroot.term.PhantomProcessManager;
import com.debianchroot.term.ProotManager;
import com.debianchroot.term.RfbClient;
import com.debianchroot.term.SnippetManager;
import com.debianchroot.term.SystemRequirements;
import com.debianchroot.term.TarExtractor;
import com.debianchroot.term.TerminalSearch;
import com.debianchroot.term.TerminalSettings;
import com.termux.terminal.TerminalColorScheme;
import com.termux.terminal.TerminalEmulator;
import com.termux.terminal.TerminalOutput;
import com.termux.terminal.TerminalSession;
import com.termux.terminal.TerminalSessionClient;
import com.termux.view.TerminalView;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.Deflater;

/**
 * JVM verification suite for DebianTerm:
 * 1) DECSET 2026 synchronized output + DECRQM query
 * 2) Soft-keyboard main-screen pan without PTY resize / scrollback loss
 * 3) Live LXC Debian bookworm/trixie index & SHA256SUMS parsing
 * 4) Generated /data/local/bin/debian entry script syntax, session counter & auto-unmount trap
 * 5) Color schemes validation against TerminalColorScheme
 * 6) SystemRequirements evaluation (root, non-root PRoot, low storage, old Android)
 * 7) TarExtractor (permissions, symlinks, hardlinks, PAX long path, dev skip, traversal block, safe delete)
 * 8) Generated PRoot launcher script syntax and flags
 * 9) Graceful session shutdown API (SIGHUP/SIGTERM -> SIGKILL fallback)
 * 10) Graphical DesktopManager scripts (XFCE4/LXDE VNC, Termux:X11)
 * 11) Pure-Java RFB 3.8 client protocol (Raw + Zlib + CopyRect + CutText + input)
 * 12) Session numbering reuse & lifecycle API (lowest available #N, SessionKind, pruning)
 * 13) WakeLock + WifiLock + AutoWakeLock + Doze/Notifications API & Manifest permissions
 * 14) setup-storage permissions, ProotManager ~/storage symlinks, CLI script & /sdcard bindings
 * 15) PhantomProcessManager status parsing & ADB mitigation command generation
 * 16) TerminalSearch anchored-row search across scrollback, case sensitivity, wide chars & TerminalView API
 * 17) SnippetManager defaults, escaped serialization round-trip & Gboard TextInput listener contract
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
        testGracefulCloseApi();
        testDesktopManagerScripts();
        testRfbClientProtocol();
        testSessionNumberingAndLifecycleApi();
        testWakeLockAndPowerApi();
        testSetupStorageAndProotBindings();
        testPhantomProcessParser();
        testTerminalSearch();
        testSnippetManagerSerialization();
        System.out.println("ALL 17 VERIFICATION TESTS PASSED.");
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
        check(script.contains("SESSION_DIR='/dev/chroot_sessions'"), "Default chroot must use /dev/chroot_sessions");
        check(script.contains("cleanup_session"), "Entry script must define cleanup_session");
        check(script.contains("CLEANED_UP"), "Entry script must include CLEANED_UP guard");
        check(script.contains("trap cleanup_session EXIT HUP INT TERM"), "Entry script must trap EXIT HUP INT TERM");
        check(script.contains("umount_chroot"), "Entry script must call umount_chroot");
        // Interactive block must NOT exec env -i so trap/cleanup runs on bash exit
        String tail = script.substring(script.indexOf("trap cleanup_session"));
        check(!tail.contains("exec env -i"), "Interactive launch must not use exec env -i");

        String customScript = ChrootManager.generateEntryScript("/data/local/deb2");
        check(customScript.contains("SESSION_DIR='/dev/chroot_sessions/_data_local_deb2'"),
            "Custom chroot must isolate SESSION_DIR per path");

        Path tmp = Paths.get("/tmp/test-debian-script.sh");
        Files.write(tmp, script.getBytes(StandardCharsets.UTF_8));
        try {
            Process p = new ProcessBuilder("sh", "-n", tmp.toString()).start();
            int rc = p.waitFor();
            check(rc == 0, "sh -n failed with exit code " + rc);
        } finally {
            Files.deleteIfExists(tmp);
        }
        System.out.println("[PASS] Test 4: Generated root entry script syntax (sh -n), session counter & auto-unmount trap");
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

    private static void testGracefulCloseApi() throws Exception {
        Method finishGracefully = TerminalSession.class.getMethod("finishGracefully", long.class);
        check(Modifier.isPublic(finishGracefully.getModifiers())
            && finishGracefully.getReturnType() == void.class,
            "finishGracefully(long) must be public void");

        Method finishIfRunning = TerminalSession.class.getMethod("finishIfRunning");
        check(Modifier.isPublic(finishIfRunning.getModifiers())
            && finishIfRunning.getReturnType() == void.class,
            "finishIfRunning() must be public void");

        Method removeSession = DebianTermService.class.getMethod("removeSession", TerminalSession.class);
        check(Modifier.isPublic(removeSession.getModifiers()), "DebianTermService.removeSession must be public");

        Method stopAll = DebianTermService.class.getMethod("stopAllSessionsAndSelf");
        check(Modifier.isPublic(stopAll.getModifiers()), "DebianTermService.stopAllSessionsAndSelf must be public");

        System.out.println("[PASS] Test 9: Graceful session shutdown API (SIGHUP/SIGTERM -> SIGKILL fallback)");
    }

    private static void testDesktopManagerScripts() throws Exception {
        String xfceInstall = DesktopManager.generateDesktopInstallCommand(DesktopManager.DESKTOP_XFCE);
        check(xfceInstall.contains("tigervnc-standalone-server"), "Install cmd must include tigervnc-standalone-server");
        check(xfceInstall.contains("dbus-x11"), "Install cmd must include dbus-x11");
        check(xfceInstall.contains("xfce4"), "XFCE install cmd must include xfce4");

        String lxdeInstall = DesktopManager.generateDesktopInstallCommand(DesktopManager.DESKTOP_LXDE);
        check(lxdeInstall.contains("lxde-core"), "LXDE install cmd must include lxde-core");
        check(lxdeInstall.contains("tigervnc-standalone-server"), "LXDE install must include tigervnc-standalone-server");

        String vncStart = DesktopManager.generateVncStartCommand(DesktopManager.DESKTOP_XFCE, 1280, 720);
        check(vncStart.contains("Xtigervnc"), "VNC start must reference Xtigervnc");
        check(vncStart.contains("-localhost"), "VNC start must bind localhost");
        check(vncStart.contains("5901"), "VNC start must use port 5901");
        check(vncStart.contains("trap cleanup_vnc EXIT HUP INT TERM"), "VNC start must trap cleanup");
        check(vncStart.contains("dbus-launch --exit-with-session startxfce4"), "VNC start must launch XFCE4");

        String x11Start = DesktopManager.generateTermuxX11StartCommand(DesktopManager.DESKTOP_LXDE);
        check(x11Start.contains("DISPLAY=:0"), "Termux:X11 start must export DISPLAY=:0");
        check(x11Start.contains("startlxde"), "Termux:X11 start must launch startlxde");

        check(DesktopManager.TERMUX_X11_APK_URL.startsWith("https://github.com/termux/termux-x11/releases/"),
            "Termux:X11 APK URL must point to official releases");

        for (int idx = 0; idx < 2; idx++) {
            String src = (idx == 0) ? vncStart : x11Start;
            Path tmp = Paths.get("/tmp/test-desktop-" + idx + ".sh");
            Files.write(tmp, src.getBytes(StandardCharsets.UTF_8));
            try {
                Process p = new ProcessBuilder("sh", "-n", tmp.toString()).start();
                check(p.waitFor() == 0, "Desktop script sh -n failed (" + idx + ")");
            } finally {
                Files.deleteIfExists(tmp);
            }
        }
        System.out.println("[PASS] Test 10: DesktopManager XFCE4/LXDE VNC & Termux:X11 scripts (sh -n)");
    }

    private static void testRfbClientProtocol() throws Exception {
        final CountDownLatch frameDone = new CountDownLatch(1);
        final CountDownLatch connectedLatch = new CountDownLatch(1);
        final CountDownLatch cutLatch = new CountDownLatch(1);
        final AtomicReference<int[]> lastPixels = new AtomicReference<>();
        final AtomicReference<String> serverErr = new AtomicReference<>();
        final AtomicReference<String> cutText = new AtomicReference<>();
        final AtomicBoolean pointerOk = new AtomicBoolean(false);
        final AtomicBoolean keyOk = new AtomicBoolean(false);

        final ServerSocket ss = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
        int port = ss.getLocalPort();

        Thread serverThread = new Thread(() -> {
            try (Socket s = ss.accept()) {
                DataInputStream dis = new DataInputStream(s.getInputStream());
                DataOutputStream dos = new DataOutputStream(s.getOutputStream());

                // 1. Handshake RFB 003.008
                dos.write("RFB 003.008\n".getBytes(StandardCharsets.US_ASCII));
                dos.flush();
                byte[] clientVer = new byte[12];
                dis.readFully(clientVer);
                check("RFB 003.008\n".equals(new String(clientVer, StandardCharsets.US_ASCII)), "Bad client RFB version");

                // 2. Security None (1)
                dos.writeByte(1); // 1 type
                dos.writeByte(1); // None
                dos.flush();
                int chosen = dis.readUnsignedByte();
                check(chosen == 1, "Expected Security None");
                dos.writeInt(0); // SecurityResult OK
                dos.flush();

                // 3. ClientInit + ServerInit (4x4 desktop)
                int shared = dis.readUnsignedByte();
                check(shared == 1, "Expected shared ClientInit");
                dos.writeShort(4);
                dos.writeShort(4);
                dos.write(new byte[16]); // default pixel format
                byte[] dname = "MockDebian".getBytes(StandardCharsets.UTF_8);
                dos.writeInt(dname.length);
                dos.write(dname);
                dos.flush();

                // Expect SetPixelFormat (20), SetEncodings (4 + 4*4 = 20), FramebufferUpdateRequest (10)
                byte[] setPf = new byte[20];
                dis.readFully(setPf);
                check(setPf[0] == 0 && setPf[4] == 32, "Expected SetPixelFormat 32bpp");

                int encMsgType = dis.readUnsignedByte();
                check(encMsgType == 2, "Expected SetEncodings");
                dis.readUnsignedByte(); // pad
                int numEnc = dis.readUnsignedShort();
                dis.readFully(new byte[numEnc * 4]);

                byte[] fbReq = new byte[10];
                dis.readFully(fbReq);
                check(fbReq[0] == 3 && fbReq[1] == 0, "Expected non-incremental FramebufferUpdateRequest");

                // Send FramebufferUpdate with 3 rects: Raw (0,0 2x2), Zlib (2,0 2x2), CopyRect (0,0 -> 2,2 2x2)
                dos.writeByte(0); // FramebufferUpdate
                dos.writeByte(0); // pad
                dos.writeShort(3);

                // Rect 1: Raw red (0xFFFF0000 -> BGRX: 00,00,FF,00) at 0,0 2x2
                dos.writeShort(0); dos.writeShort(0); dos.writeShort(2); dos.writeShort(2);
                dos.writeInt(RfbClient.ENCODING_RAW);
                byte[] rawBlock = new byte[16];
                for (int p = 0; p < 4; p++) {
                    rawBlock[p * 4] = 0x00;         // B
                    rawBlock[p * 4 + 1] = 0x00;     // G
                    rawBlock[p * 4 + 2] = (byte) 0xFF; // R
                    rawBlock[p * 4 + 3] = 0x00;
                }
                dos.write(rawBlock);

                // Rect 2: Zlib green (0xFF00FF00 -> BGRX: 00,FF,00,00) at 2,0 2x2
                byte[] greenBlock = new byte[16];
                for (int p = 0; p < 4; p++) {
                    greenBlock[p * 4] = 0x00;
                    greenBlock[p * 4 + 1] = (byte) 0xFF;
                    greenBlock[p * 4 + 2] = 0x00;
                    greenBlock[p * 4 + 3] = 0x00;
                }
                Deflater def = new Deflater();
                def.setInput(greenBlock);
                byte[] compOut = new byte[256];
                int compLen = def.deflate(compOut, 0, compOut.length, Deflater.SYNC_FLUSH);
                def.end();

                dos.writeShort(2); dos.writeShort(0); dos.writeShort(2); dos.writeShort(2);
                dos.writeInt(RfbClient.ENCODING_ZLIB);
                dos.writeInt(compLen);
                dos.write(compOut, 0, compLen);

                // Rect 3: CopyRect from (0,0) -> (2,2) 2x2
                dos.writeShort(2); dos.writeShort(2); dos.writeShort(2); dos.writeShort(2);
                dos.writeInt(RfbClient.ENCODING_COPYRECT);
                dos.writeShort(0); dos.writeShort(0);

                // ServerCutText
                byte[] cut = "clipboard-ok".getBytes(StandardCharsets.ISO_8859_1);
                dos.writeByte(3); dos.write(new byte[3]); dos.writeInt(cut.length); dos.write(cut);
                dos.flush();

                // Read client events (incremental FBUR, pointer, key)
                long deadline = System.currentTimeMillis() + 3000L;
                while (System.currentTimeMillis() < deadline && (!pointerOk.get() || !keyOk.get())) {
                    int mt = dis.read();
                    if (mt == -1) break;
                    if (mt == 3) {
                        dis.readFully(new byte[9]);
                    } else if (mt == 5) {
                        byte[] pe = new byte[5];
                        dis.readFully(pe);
                        if (pe[0] == 1) pointerOk.set(true);
                    } else if (mt == 4) {
                        byte[] ke = new byte[7];
                        dis.readFully(ke);
                        if (ke[0] == 1 && (ke[6] & 0xff) == 0x0D) keyOk.set(true);
                    }
                }
            } catch (Throwable t) {
                serverErr.set(t.toString());
            } finally {
                try { ss.close(); } catch (Exception ignored) {}
            }
        }, "MockRfbServer");
        serverThread.setDaemon(true);
        serverThread.start();

        RfbClient client = new RfbClient();
        try {
            client.connectWithRetry("127.0.0.1", port, "", 5, 50L, new RfbClient.FrameListener() {
                @Override
                public void onConnected(int width, int height, String desktopName) {
                    if (width == 4 && height == 4 && "MockDebian".equals(desktopName)) {
                        connectedLatch.countDown();
                    }
                }
                @Override public void onFramebufferResize(int width, int height) {}
                @Override
                public void onFrameUpdated(int[] pixels, int width, int height, int x, int y, int w, int h) {
                    lastPixels.set(pixels.clone());
                    if (x == 2 && y == 2) {
                        frameDone.countDown();
                    }
                }
                @Override
                public void onServerCutText(String text) {
                    cutText.set(text);
                    cutLatch.countDown();
                }
                @Override public void onDisconnected(String reason) {}
            });

            check(connectedLatch.await(5, TimeUnit.SECONDS), "RFB client did not connect");
            check(frameDone.await(5, TimeUnit.SECONDS), "RFB frame updates did not complete");
            check(cutLatch.await(5, TimeUnit.SECONDS), "ServerCutText not received");
            check("clipboard-ok".equals(cutText.get()), "CutText mismatch: " + cutText.get());

            int[] px = lastPixels.get();
            check(px[0] == 0xFFFF0000, "Raw red pixel mismatch: " + Integer.toHexString(px[0]));
            check(px[2] == 0xFF00FF00, "Zlib green pixel mismatch: " + Integer.toHexString(px[2]));
            check(px[2 * 4 + 2] == 0xFFFF0000, "CopyRect pixel mismatch: " + Integer.toHexString(px[2 * 4 + 2]));

            client.sendPointerEvent(1, 2, 2);
            client.sendKeyEvent(true, 0xFF0D);
            long waitEv = System.currentTimeMillis() + 2000L;
            while (System.currentTimeMillis() < waitEv && (!pointerOk.get() || !keyOk.get())) {
                Thread.sleep(20);
            }
            check(pointerOk.get(), "PointerEvent not received by server");
            check(keyOk.get(), "KeyEvent not received by server");
            check(serverErr.get() == null, "Mock RFB server error: " + serverErr.get());
        } finally {
            client.disconnect();
            ss.close();
        }
        System.out.println("[PASS] Test 11: RFB 3.8 client (Raw + Zlib + CopyRect + ServerCutText + Pointer/Key)");
    }

    private static void testSessionNumberingAndLifecycleApi() throws Exception {
        String rootPrefix = "Debian 12 (Root) #";
        String prootPrefix = "Debian 12 (Sin Root) #";

        check(DebianTermService.nextAvailableSessionNumber(Collections.emptyList(), rootPrefix) == 1,
            "Empty session list must start at #1");
        check(DebianTermService.nextAvailableSessionNumber(null, rootPrefix) == 1,
            "Null session list must start at #1");
        check(DebianTermService.nextAvailableSessionNumber(Collections.singletonList("Debian 12 (Root) #1"), rootPrefix) == 2,
            "When #1 is active, next must be #2");
        check(DebianTermService.nextAvailableSessionNumber(Collections.singletonList("Debian 12 (Root) #2"), rootPrefix) == 1,
            "When #1 was closed and only #2 remains, next must reuse #1");
        check(DebianTermService.nextAvailableSessionNumber(
            Arrays.asList("Debian 12 (Root) #1", "Debian 12 (Root) #3"), rootPrefix) == 2,
            "When #1 and #3 are active, next must fill gap #2");
        check(DebianTermService.nextAvailableSessionNumber(
            Arrays.asList("Debian 12 (Root) #1", "Debian 12 (Root) #2"), prootPrefix) == 1,
            "Separate prefixes must start at #1 independently");
        check(DebianTermService.nextAvailableSessionNumber(
            Arrays.asList(
                "Mi servidor",
                "Debian 12 (Root) #abc",
                "Debian 12 (Root) #0",
                "Debian 12 (Root) #-3",
                "Debian 12 (Root) #1"
            ), rootPrefix) == 2,
            "Renamed/non-numeric/non-positive suffixes must be ignored safely");

        Class<?> kindCls = Class.forName("com.debianchroot.term.DebianTermService$SessionKind");
        Object[] kinds = kindCls.getEnumConstants();
        check(kinds != null && kinds.length == 7,
            "SessionKind must have 7 constants, got " + (kinds == null ? 0 : kinds.length));

        check(Modifier.isPublic(DebianTermService.class.getMethod("getRunningSessionCount").getModifiers()),
            "getRunningSessionCount must be public");
        check(Modifier.isPublic(DebianTermService.class.getMethod("findRunningSession", kindCls).getModifiers()),
            "findRunningSession(SessionKind) must be public");
        check(Modifier.isPublic(DebianTermService.class.getMethod("pruneCleanlyFinishedSessions").getModifiers()),
            "pruneCleanlyFinishedSessions must be public");
        check(Modifier.isPublic(DebianTermService.class.getMethod("pruneFinishedSessions", kindCls).getModifiers()),
            "pruneFinishedSessions(SessionKind) must be public");
        check(Modifier.isPublic(DebianTermService.class.getMethod("hasSession", TerminalSession.class).getModifiers()),
            "hasSession(TerminalSession) must be public");
        check(Modifier.isPublic(DebianTermService.class.getMethod("onSessionStateChanged").getModifiers()),
            "onSessionStateChanged must be public");

        System.out.println("[PASS] Test 12: Session numbering reuse (#1), SessionKind & lifecycle pruning API");
    }

    private static void testWakeLockAndPowerApi() throws Exception {
        Class<?> ctxCls = Class.forName("android.content.Context");

        String actionToggle = (String) DebianTermService.class.getField("ACTION_TOGGLE_WAKELOCK").get(null);
        check("com.debianchroot.term.ACTION_TOGGLE_WAKELOCK".equals(actionToggle),
            "Unexpected ACTION_TOGGLE_WAKELOCK: " + actionToggle);

        check(Modifier.isPublic(DebianTermService.class.getMethod("isWakeLockHeld").getModifiers()),
            "isWakeLockHeld must be public");
        check(Modifier.isPublic(DebianTermService.class.getMethod("isWifiLockHeld").getModifiers()),
            "isWifiLockHeld must be public");
        check(Modifier.isPublic(DebianTermService.class.getMethod("setWakeLockEnabled", boolean.class).getModifiers()),
            "setWakeLockEnabled(boolean) must be public");
        check(Modifier.isPublic(DebianTermService.class.getMethod("toggleWakeLock").getModifiers()),
            "toggleWakeLock must be public");

        Method ignoreBat = DebianTermService.class.getMethod("isIgnoringBatteryOptimizations", ctxCls);
        check(Modifier.isPublic(ignoreBat.getModifiers()) && Modifier.isStatic(ignoreBat.getModifiers())
            && ignoreBat.getReturnType() == boolean.class,
            "isIgnoringBatteryOptimizations(Context) must be public static boolean");

        Method notifEnabled = DebianTermService.class.getMethod("areNotificationsEnabled", ctxCls);
        check(Modifier.isPublic(notifEnabled.getModifiers()) && Modifier.isStatic(notifEnabled.getModifiers())
            && notifEnabled.getReturnType() == boolean.class,
            "areNotificationsEnabled(Context) must be public static boolean");

        Class<?> listenerCls = Class.forName("com.debianchroot.term.DebianTermService$ServiceEventListener");
        check(listenerCls.getMethod("onServiceStopAllRequested") != null,
            "ServiceEventListener.onServiceStopAllRequested must exist");
        check(listenerCls.getMethod("onWakeLockStateChanged", boolean.class) != null,
            "ServiceEventListener.onWakeLockStateChanged(boolean) must exist");

        Method isAutoWake = TerminalSettings.class.getMethod("isAutoWakeLockEnabled");
        Method setAutoWake = TerminalSettings.class.getMethod("setAutoWakeLockEnabled", boolean.class);
        check(Modifier.isPublic(isAutoWake.getModifiers()) && isAutoWake.getReturnType() == boolean.class,
            "isAutoWakeLockEnabled must be public boolean");
        check(Modifier.isPublic(setAutoWake.getModifiers()),
            "setAutoWakeLockEnabled(boolean) must be public");

        check(SystemRequirements.Report.class.getField("ignoringBatteryOptimizations").getType() == boolean.class,
            "Report.ignoringBatteryOptimizations must be boolean");
        check(SystemRequirements.Report.class.getField("notificationsEnabled").getType() == boolean.class,
            "Report.notificationsEnabled must be boolean");

        String manifest = new String(Files.readAllBytes(Paths.get("AndroidManifest.xml")), StandardCharsets.UTF_8);
        check(manifest.contains("android.permission.WAKE_LOCK"),
            "AndroidManifest must declare WAKE_LOCK");
        check(manifest.contains("android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS"),
            "AndroidManifest must declare REQUEST_IGNORE_BATTERY_OPTIMIZATIONS");
        check(manifest.contains("android.permission.POST_NOTIFICATIONS"),
            "AndroidManifest must declare POST_NOTIFICATIONS");

        System.out.println("[PASS] Test 13: WakeLock + WifiLock + AutoWakeLock + Doze/Notifications API & Manifest");
    }

    private static void testSetupStorageAndProotBindings() throws Exception {
        String setupScript = ProotManager.generateSetupStorageScript();
        check(setupScript.contains("/sdcard") && setupScript.contains("/root/storage")
            && setupScript.contains("downloads") && setupScript.contains("shared"),
            "setup-storage script missing expected paths");

        Path tmpScript = Paths.get("/tmp/test-setup-storage.sh");
        Files.write(tmpScript, setupScript.getBytes(StandardCharsets.UTF_8));
        try {
            Process p = new ProcessBuilder("sh", "-n", tmpScript.toString()).start();
            int rc = p.waitFor();
            check(rc == 0, "setup-storage sh -n failed with exit code " + rc);
        } finally {
            Files.deleteIfExists(tmpScript);
        }

        String prootLaunch = ProotManager.generateProotLauncherScript(
            "/data/app/x/lib/arm64",
            "/data/user/0/com.debianchroot.term/files",
            "/data/user/0/com.debianchroot.term/files/debian12-proot",
            true
        );
        check(prootLaunch.contains("--bind=/storage/emulated/0:/sdcard"),
            "PRoot launcher must bind /storage/emulated/0:/sdcard");
        check(prootLaunch.contains("--bind=/storage/emulated/0:/storage/emulated/0"),
            "PRoot launcher must bind /storage/emulated/0:/storage/emulated/0");
        check(prootLaunch.contains("/external_sd"),
            "PRoot launcher must support optional /external_sd bind");

        Path sandbox = Files.createTempDirectory("debianterm-storage-test");
        File fakeRootfs = sandbox.toFile();
        try {
            ProotManager.setupStorageLinks(fakeRootfs);

            Path sharedLink = Paths.get(fakeRootfs.getAbsolutePath(), "root/storage/shared");
            Path downloadsLink = Paths.get(fakeRootfs.getAbsolutePath(), "root/storage/downloads");
            Path setupAlias = Paths.get(fakeRootfs.getAbsolutePath(), "usr/local/bin/setup-storage");
            File setupBin = new File(fakeRootfs, "usr/local/bin/debianterm-setup-storage");

            check(Files.isSymbolicLink(sharedLink)
                && "/sdcard".equals(Files.readSymbolicLink(sharedLink).toString()),
                "~/storage/shared symlink target mismatch");
            check(Files.isSymbolicLink(downloadsLink)
                && "/sdcard/Download".equals(Files.readSymbolicLink(downloadsLink).toString()),
                "~/storage/downloads symlink target mismatch");
            check(setupBin.isFile() && setupBin.canExecute(),
                "debianterm-setup-storage script must exist and be executable");
            check(Files.isSymbolicLink(setupAlias)
                && "debianterm-setup-storage".equals(Files.readSymbolicLink(setupAlias).toString()),
                "setup-storage alias symlink target mismatch");
        } finally {
            TarExtractor.deleteRecursivelyNoFollow(fakeRootfs);
        }

        Class<?> ctxCls = Class.forName("android.content.Context");
        Method hasStorage = ProotManager.class.getMethod("hasSharedStorageAccess", ctxCls);
        check(Modifier.isPublic(hasStorage.getModifiers()) && Modifier.isStatic(hasStorage.getModifiers())
            && hasStorage.getReturnType() == boolean.class,
            "hasSharedStorageAccess(Context) must be public static boolean");
        check(SystemRequirements.Report.class.getField("sharedStorageGranted").getType() == boolean.class,
            "Report.sharedStorageGranted must be boolean");

        String manifest = new String(Files.readAllBytes(Paths.get("AndroidManifest.xml")), StandardCharsets.UTF_8);
        check(manifest.contains("android.permission.READ_EXTERNAL_STORAGE"),
            "AndroidManifest must declare READ_EXTERNAL_STORAGE");
        check(manifest.contains("android.permission.WRITE_EXTERNAL_STORAGE"),
            "AndroidManifest must declare WRITE_EXTERNAL_STORAGE");
        check(manifest.contains("android.permission.MANAGE_EXTERNAL_STORAGE"),
            "AndroidManifest must declare MANAGE_EXTERNAL_STORAGE");

        System.out.println("[PASS] Test 14: setup-storage permissions, ~/storage symlinks, CLI script & /sdcard bindings");
    }

    private static void testPhantomProcessParser() throws Exception {
        PhantomProcessManager.Status legacy = PhantomProcessManager.parsePhantomStatus(30, null, null, null);
        check(!legacy.affectedApi && legacy.knownMitigated, "API 30 must not be affected by Phantom Killer");

        PhantomProcessManager.Status android15Def = PhantomProcessManager.parsePhantomStatus(35, "true", null, null);
        check(android15Def.affectedApi && !android15Def.knownMitigated && android15Def.summary.contains("32"),
            "Android 15 default must warn with limit 32: " + android15Def.summary);

        PhantomProcessManager.Status disabled = PhantomProcessManager.parsePhantomStatus(35, "false", null, null);
        check(disabled.monitoringDisabled && disabled.knownMitigated,
            "monitorFlag=false must mark mitigated");

        PhantomProcessManager.Status raisedDc = PhantomProcessManager.parsePhantomStatus(35, "true", null, "2147483647");
        check(raisedDc.maxPhantomProcesses == 2147483647 && raisedDc.knownMitigated,
            "deviceConfigMax=2147483647 must mark mitigated");

        PhantomProcessManager.Status raisedConsts = PhantomProcessManager.parsePhantomStatus(
            35, null, "foo=1,max_phantom_processes=65536,bar=2", null);
        check(raisedConsts.maxPhantomProcesses == 65536 && raisedConsts.knownMitigated,
            "activity_manager_constants max_phantom_processes=65536 must mark mitigated");

        PhantomProcessManager.Status lowLimit = PhantomProcessManager.parsePhantomStatus(35, "true", null, "32");
        check(lowLimit.maxPhantomProcesses == 32 && !lowLimit.knownMitigated,
            "Limit 32 must NOT mark mitigated");

        String adbCmds = PhantomProcessManager.getAdbFixCommands();
        check(adbCmds.contains("settings_enable_monitor_phantom_procs false")
            && adbCmds.contains("max_phantom_processes 2147483647"),
            "ADB commands must contain phantom fix instructions");

        check(SystemRequirements.Report.class.getField("phantomStatus").getType() == PhantomProcessManager.Status.class,
            "Report.phantomStatus field type mismatch");
        check(Modifier.isPublic(PhantomProcessManager.class.getMethod("applyRootFix").getModifiers()),
            "applyRootFix must be public");

        System.out.println("[PASS] Test 15: PhantomProcessManager status parser & ADB mitigation commands");
    }

    private static void testTerminalSearch() throws Exception {
        RecordingOutput out = new RecordingOutput();
        TerminalEmulator em = new TerminalEmulator(out, 80, 24, 10, 20, 2000, new StubSessionClient());

        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= 70; i++) {
            sb.append("log line ").append(i).append('\r').append('\n');
        }
        sb.append("🚀 ERROR alpha and error beta on wide row\r\n");
        for (int i = 72; i <= 80; i++) {
            sb.append("tail line ").append(i).append('\r').append('\n');
        }
        feed(em, sb.toString());

        List<TerminalSearch.Match> ciMatches = TerminalSearch.search(em, "error", false);
        check(ciMatches.size() == 2, "Case-insensitive search expected 2 matches, got " + ciMatches.size());
        check(ciMatches.get(0).startCol > 2, "Wide emoji char must shift startCol >= 3, got " + ciMatches.get(0).startCol);
        check(ciMatches.get(1).startCol > ciMatches.get(0).startCol, "Second occurrence must appear to the right");

        List<TerminalSearch.Match> csMatches = TerminalSearch.search(em, "ERROR", true);
        check(csMatches.size() == 1, "Case-sensitive search expected 1 match, got " + csMatches.size());
        long absBeforeMoreOutput = csMatches.get(0).absoluteRow;

        for (int i = 1; i <= 10; i++) {
            feed(em, "extra scroll " + i + "\r\n");
        }
        List<TerminalSearch.Match> csAfter = TerminalSearch.search(em, "ERROR", true);
        check(csAfter.size() == 1 && csAfter.get(0).absoluteRow == absBeforeMoreOutput,
            "Anchored absoluteRow must remain constant as output scrolls: before="
                + absBeforeMoreOutput + " after=" + (csAfter.isEmpty() ? -1 : csAfter.get(0).absoluteRow));

        check(TerminalSearch.search(em, "", false).isEmpty(), "Empty query must return empty matches");
        check(Modifier.isPublic(TerminalView.class.getMethod("setSearchHighlight", long.class, int.class, int.class).getModifiers()),
            "TerminalView.setSearchHighlight must be public");
        check(Modifier.isPublic(TerminalView.class.getMethod("clearSearchHighlight").getModifiers()),
            "TerminalView.clearSearchHighlight must be public");
        check(Modifier.isPublic(TerminalView.class.getMethod("scrollToAbsoluteRow", long.class).getModifiers()),
            "TerminalView.scrollToAbsoluteRow must be public");

        System.out.println("[PASS] Test 16: TerminalSearch anchored scrollback search, wide chars & highlight API");
    }

    private static void testSnippetManagerSerialization() throws Exception {
        List<SnippetManager.Snippet> defaults = SnippetManager.getDefaultSnippets();
        check(defaults.size() >= 5, "Default snippets list should contain at least 5 presets");

        List<SnippetManager.Snippet> custom = Arrays.asList(
            new SnippetManager.Snippet("s1", "Con \\barra\t y tab", "echo \"hola\" && ls -la", true),
            new SnippetManager.Snippet("s2", "Multi\nlinea", "cat <<'EOF'\nline2\r\nEOF", false)
        );
        String serialized = SnippetManager.serialize(custom);
        List<SnippetManager.Snippet> restored = SnippetManager.deserialize(serialized);

        check(restored.size() == 2, "Expected 2 deserialized snippets, got " + restored.size());
        check("s1".equals(restored.get(0).id)
            && "Con \\barra\t y tab".equals(restored.get(0).title)
            && "echo \"hola\" && ls -la".equals(restored.get(0).command)
            && restored.get(0).autoExecute,
            "First snippet round-trip mismatch");
        check("s2".equals(restored.get(1).id)
            && "Multi\nlinea".equals(restored.get(1).title)
            && "cat <<'EOF'\nline2\r\nEOF".equals(restored.get(1).command)
            && !restored.get(1).autoExecute,
            "Second snippet round-trip mismatch");
        check(SnippetManager.deserialize("corrupt-line-without-tabs\n").isEmpty(),
            "Malformed lines must be skipped safely");

        check(Modifier.isPublic(TerminalSettings.class.getMethod("getSnippets").getModifiers()),
            "TerminalSettings.getSnippets must be public");
        check(Modifier.isPublic(TerminalSettings.class.getMethod("saveSnippets", List.class).getModifiers()),
            "TerminalSettings.saveSnippets must be public");
        check(ExtraKeysBarView.Listener.class.getMethod("onSwipeToTextInput") != null,
            "ExtraKeysBarView.Listener.onSwipeToTextInput must exist");

        System.out.println("[PASS] Test 17: SnippetManager serialization, defaults & Gboard swipe listener");
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
