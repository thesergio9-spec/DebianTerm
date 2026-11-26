import com.debianchroot.term.BackupManager;
import com.debianchroot.term.BootReceiver;
import com.debianchroot.term.ChrootInstaller;
import com.debianchroot.term.ChrootManager;
import com.debianchroot.term.DebianDocumentsProvider;
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
import com.termux.terminal.SixelDecoder;
import com.termux.terminal.SixelImage;
import com.termux.terminal.TerminalColorScheme;
import com.termux.terminal.TerminalEmulator;
import com.termux.terminal.TerminalOutput;
import com.termux.terminal.TerminalSession;
import com.termux.terminal.TerminalSessionClient;
import com.termux.view.TerminalView;
import com.termux.x11.CmdEntryPoint;
import com.termux.x11.ICmdEntryInterface;
import com.termux.x11.LorieView;

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
import java.util.HashSet;
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
 * 18) DebianDocumentsProvider (SAF) path traversal guard, external symlink blocking, MIME types & Manifest
 * 19) BackupManager pure-Java .tar.gz round-trip (perms, PAX >110 chars, symlinks, exclusions, metadata)
 * 20) ExtraKeysBarView custom spec/presets parser, bundled Nerd Font TTF & Phase 2 settings API
 * 21) BootReceiver autostart mode resolution, POSIX boot script (sh -n) & Manifest BOOT_COMPLETED
 * 22) SixelDecoder pure-Java parser (RGB, HLS, raster attributes, RLE repeat, safety bounds)
 * 23) TerminalEmulator Sixel DCS integration (DA1 ;4, XTSMGRAPHICS, cursor/scrollback anchoring, alt-screen, clear)
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
        testDocumentsProviderSafety();
        testBackupManagerTarGzRoundTrip();
        testExtraKeysCustomSpecAndFonts();
        testBootAutostartLogicAndManifest();
        testSixelDecoderCore();
        testTerminalEmulatorSixelIntegration();
        testLargeSyncRedrawAndEraseSettleAntiRewind();
        testScrolledUpPreservedAcrossCsi3jRedrawAndTranscriptCapacity();
        testSafeAppExitOnLastShellExit();
        testRootExecCommandFallback();
        testBuiltInNativeX11JniContract();
        testDesktopOrientationAndResolution();
        System.out.println("ALL 29 VERIFICATION TESTS PASSED.");
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
        Path mockDir = Files.createTempDirectory("test-proot-mock");
        Path mockProot = mockDir.resolve("libproot.so");
        Files.write(mockProot, "#!/bin/sh\nprintf 'MOCK_PROOT:%s\\n' \"$*\"\n".getBytes(StandardCharsets.UTF_8));
        mockProot.toFile().setExecutable(true);

        Path mockFiles = mockDir.resolve("files");
        Path mockRootfs = mockFiles.resolve("debian12-proot");
        Files.createDirectories(mockRootfs);

        String script = ProotManager.generateProotLauncherScript(
            mockDir.toString(),
            mockFiles.toString(),
            mockRootfs.toString(),
            true
        );
        check(script.contains("--link2symlink"), "PRoot script must contain --link2symlink");
        check(script.contains("--root-id"), "PRoot script must contain --root-id");
        check(script.contains("PROOT_LOADER"), "PRoot script must export PROOT_LOADER");
        check(script.contains("PROOT_TMP_DIR"), "PRoot script must export PROOT_TMP_DIR");
        check(script.contains("PROOT_NO_SECCOMP=1"), "PRoot script must export PROOT_NO_SECCOMP=1 when enabled");
        check(script.contains("env -i"), "PRoot script must isolate env with env -i");
        check(!script.contains("TERMUX_"), "PRoot script must not leak TERMUX_ vars");
        check(script.contains("RUN_MODE=\"$1\""), "PRoot script must capture $1 before set --");
        check(script.contains("RUN_CMD=\"$2\""), "PRoot script must capture $2 before set --");

        Path tmp = Paths.get("/tmp/test-proot-script.sh");
        Files.write(tmp, script.getBytes(StandardCharsets.UTF_8));
        try {
            Process p = new ProcessBuilder("sh", "-n", tmp.toString()).start();
            int rc = p.waitFor();
            check(rc == 0, "PRoot sh -n failed with exit code " + rc);

            // Real shell execution test #1: no arguments -> interactive /bin/bash --login
            Process interactive = new ProcessBuilder("sh", tmp.toString()).start();
            String interactiveOut = readAllUtf8(interactive.getInputStream());
            check(interactive.waitFor() == 0, "PRoot mock interactive launch failed");
            check(interactiveOut.contains("/bin/bash --login"),
                "Interactive launch must invoke /bin/bash --login, got: " + interactiveOut);
            check(!interactiveOut.contains("-lc"),
                "Interactive launch must not invoke -lc, got: " + interactiveOut);

            // Real shell execution test #2: -c "<cmd>" -> must NOT be clobbered by set --
            Process cmdProc = new ProcessBuilder("sh", tmp.toString(), "-c", "echo DESKTOP_INSTALL_OK").start();
            String cmdOut = readAllUtf8(cmdProc.getInputStream());
            check(cmdProc.waitFor() == 0, "PRoot mock -c launch failed");
            check(cmdOut.contains("/bin/bash -lc echo DESKTOP_INSTALL_OK"),
                "-c launch must invoke /bin/bash -lc echo DESKTOP_INSTALL_OK, got: " + cmdOut);
            check(!cmdOut.contains("--login"),
                "-c launch must not drop into --login, got: " + cmdOut);
        } finally {
            Files.deleteIfExists(tmp);
            deleteTreeQuietly(mockDir.toFile());
        }
        System.out.println("[PASS] Test 8: PRoot launcher script syntax, flags, and real -c arg preservation");
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
        check(xfceInstall.contains("xfonts-base"), "Install cmd must include xfonts-base");
        check(xfceInstall.contains("--force-confdef"), "Install cmd must set dpkg --force-confdef");
        check(xfceInstall.contains("dbus-uuidgen"), "Install cmd must ensure dbus machine-id");
        check(xfceInstall.contains("xkb-data"), "Install cmd must include xkb-data for built-in X11");

        String lxdeInstall = DesktopManager.generateDesktopInstallCommand(DesktopManager.DESKTOP_LXDE);
        check(lxdeInstall.contains("lxde-core"), "LXDE install cmd must include lxde-core");
        check(lxdeInstall.contains("tigervnc-standalone-server"), "LXDE install must include tigervnc-standalone-server");

        String vncStart = DesktopManager.generateVncStartCommand(DesktopManager.DESKTOP_XFCE, 1280, 720);
        check(vncStart.contains("Xtigervnc"), "VNC start must reference Xtigervnc");
        check(vncStart.contains("-localhost"), "VNC start must bind localhost");
        check(vncStart.contains("5901"), "VNC start must use port 5901");
        check(vncStart.contains("trap cleanup_vnc EXIT HUP INT TERM"), "VNC start must trap cleanup");
        check(vncStart.contains("dbus-launch --exit-with-session startxfce4"), "VNC start must launch XFCE4");
        check(vncStart.contains("XDG_RUNTIME_DIR=/tmp/runtime-root"), "VNC start must set XDG_RUNTIME_DIR=/tmp/runtime-root");
        check(vncStart.contains("debianterm-vnc.log"), "VNC start must reference /tmp/debianterm-vnc.log");
        check(vncStart.contains("xsetroot -solid"), "VNC start must set solid desktop background");

        String lxdeVncStart = DesktopManager.generateVncStartCommand(DesktopManager.DESKTOP_LXDE, 1280, 720);
        check(lxdeVncStart.contains("lxpolkit.desktop"), "LXDE VNC start must mask lxpolkit in container autostart");
        check(lxdeVncStart.contains("xsetroot -solid"), "LXDE VNC start must set solid desktop background");

        String x11Start = DesktopManager.generateTermuxX11StartCommand(DesktopManager.DESKTOP_LXDE);
        check(x11Start.contains("DISPLAY=:0"), "Termux:X11 start must export DISPLAY=:0");
        check(x11Start.contains("startlxde"), "Termux:X11 start must launch startlxde");
        check(x11Start.contains("XDG_RUNTIME_DIR=/tmp/runtime-root"), "Termux:X11 start must set XDG_RUNTIME_DIR=/tmp/runtime-root");
        check(x11Start.contains("lxpolkit.desktop"), "LXDE X11 start must mask lxpolkit in container autostart");

        String x11Server = DesktopManager.generateBuiltInX11ServerCommand(
            "/data/app/deb/base.apk", "/data/app/deb/lib/arm64", "/tmp/deb-tmp", "/data/user/0/pkg/debian12-proot");
        check(x11Server.contains("debianterm-x11.log"), "Built-in X11 server must log to debianterm-x11.log");
        check(x11Server.contains("XKB_CONFIG_ROOT="), "Built-in X11 server must export XKB_CONFIG_ROOT");
        check(x11Server.contains("XSTARTUP_LD_PRELOAD="), "Built-in X11 server must export XSTARTUP_LD_PRELOAD");
        check(x11Server.contains("com.termux.x11.CmdEntryPoint"), "Built-in X11 server must launch CmdEntryPoint");

        DesktopManager.DesktopStatus ds = new DesktopManager.DesktopStatus();
        ds.prootDesktopInstalled = true;
        ds.prootXfceInstalled = true;
        ds.prootLxdeInstalled = false;
        ds.rootDesktopInstalled = true;
        ds.rootLxdeInstalled = true;
        ds.rootXfceInstalled = false;
        check(ds.isDesktopInstalled(false, DesktopManager.DESKTOP_XFCE), "PRoot XFCE must be detected");
        check(!ds.isDesktopInstalled(false, DesktopManager.DESKTOP_LXDE), "PRoot LXDE must be false when only XFCE is installed");
        check(ds.isDesktopInstalled(true, DesktopManager.DESKTOP_LXDE), "Root LXDE must be detected");
        check(!ds.isDesktopInstalled(true, DesktopManager.DESKTOP_XFCE), "Root XFCE must be false when only LXDE is installed");

        check(DesktopManager.TERMUX_X11_APK_URL.startsWith("https://github.com/termux/termux-x11/releases/"),
            "Termux:X11 APK URL must point to official releases");

        String[] scripts = { xfceInstall, lxdeInstall, vncStart, lxdeVncStart, x11Start, x11Server };
        for (int idx = 0; idx < scripts.length; idx++) {
            Path tmp = Paths.get("/tmp/test-desktop-" + idx + ".sh");
            Files.write(tmp, scripts[idx].getBytes(StandardCharsets.UTF_8));
            try {
                Process p = new ProcessBuilder("sh", "-n", tmp.toString()).start();
                check(p.waitFor() == 0, "Desktop script sh -n failed (" + idx + ")");
            } finally {
                Files.deleteIfExists(tmp);
            }
        }
        System.out.println("[PASS] Test 10: DesktopManager XFCE4/LXDE install, VNC & Termux:X11 scripts (sh -n) & detection");
    }

    private static void testRootExecCommandFallback() throws Exception {
        Path tmpDir = Files.createTempDirectory("test-root-exec");
        try {
            Path chrootDir = tmpDir.resolve("chroot");
            Path binDir = chrootDir.resolve("bin");
            Files.createDirectories(binDir);
            Path fakeBash = binDir.resolve("bash");
            Files.write(fakeBash, "#!/bin/sh\nexit 0\n".getBytes(StandardCharsets.UTF_8));
            fakeBash.toFile().setExecutable(true);

            // Case 1: Entry script supports exec) subcommand -> must be invoked with exact inner cmd
            Path withExecScript = tmpDir.resolve("debian-with-exec");
            Files.write(withExecScript,
                ("#!/bin/sh\n" +
                 "case \"$1\" in\n" +
                 "    exec)\n" +
                 "        shift\n" +
                 "        printf 'SCRIPT_EXEC:%s\\n' \"$*\"\n" +
                 "        exit 0\n" +
                 "        ;;\n" +
                 "esac\n" +
                 "echo 'WRONG_INTERACTIVE'\n").getBytes(StandardCharsets.UTF_8));
            withExecScript.toFile().setExecutable(true);

            String cmdWithExec = ChrootManager.buildRootExecCommand(
                chrootDir.toString(), withExecScript.toString(), "apt-get update && apt-get install -y lxde-core");
            Path p1 = tmpDir.resolve("run1.sh");
            Files.write(p1, cmdWithExec.getBytes(StandardCharsets.UTF_8));
            Process syn1 = new ProcessBuilder("sh", "-n", p1.toString()).start();
            check(syn1.waitFor() == 0, "Root exec script sh -n (case 1) failed");
            Process run1 = new ProcessBuilder("sh", p1.toString()).start();
            String out1 = readAllUtf8(run1.getInputStream());
            check(run1.waitFor() == 0, "Root exec case 1 exit failed");
            check(out1.contains("SCRIPT_EXEC:apt-get update && apt-get install -y lxde-core"),
                "Entry script with exec) must receive exact command, got: " + out1);
            check(!out1.contains("WRONG_INTERACTIVE"), "Entry script must not enter interactive branch");

            // Case 2: Pre-existing custom script WITHOUT exec) -> must NOT invoke interactive script
            Path legacyScript = tmpDir.resolve("debian-no-exec");
            Files.write(legacyScript,
                ("#!/bin/sh\n" +
                 "echo 'CALLED_CUSTOM_INTERACTIVE_SCRIPT'\n").getBytes(StandardCharsets.UTF_8));
            legacyScript.toFile().setExecutable(true);

            String cmdNoExec = ChrootManager.buildRootExecCommand(
                chrootDir.toString(), legacyScript.toString(), "echo INNER_CMD");
            Path p2 = tmpDir.resolve("run2.sh");
            Files.write(p2, cmdNoExec.getBytes(StandardCharsets.UTF_8));
            Process syn2 = new ProcessBuilder("sh", "-n", p2.toString()).start();
            check(syn2.waitFor() == 0, "Root exec script sh -n (case 2) failed");
            check(cmdNoExec.contains("grep -Eq '^[[:space:]]*exec\\)'"),
                "Root exec must guard on exec) subcommand support");
            check(cmdNoExec.contains("_dt_is_mnt"),
                "Root exec fallback must prepare missing mounts safely");

            // Verify branch decision explicitly in /bin/sh (mock mount + chroot so zero host mounts happen)
            Path mountLog = tmpDir.resolve("mounts.log");
            String simulatedFallback = "mount() { printf '%s\\n' \"$*\" >> "
                + ChrootManager.shellQuote(mountLog.toString()) + "; return 0; }\n"
                + cmdNoExec.replace("chroot ", "echo FALLBACK_CHROOT ");
            Path p2Sim = tmpDir.resolve("run2-sim.sh");
            Files.write(p2Sim, simulatedFallback.getBytes(StandardCharsets.UTF_8));
            Process run2 = new ProcessBuilder("sh", p2Sim.toString()).redirectErrorStream(true).start();
            String out2 = readAllUtf8(run2.getInputStream());
            check(run2.waitFor() == 0, "Root exec case 2 sim exit failed");
            check(!out2.contains("CALLED_CUSTOM_INTERACTIVE_SCRIPT"),
                "Custom script without exec) must be skipped, got: " + out2);
            String mountCalls = Files.exists(mountLog)
                ? new String(Files.readAllBytes(mountLog), StandardCharsets.UTF_8)
                : "";
            check(mountCalls.contains("/dev") && mountCalls.contains("/proc") && mountCalls.contains("/tmp"),
                "Fallback branch must mount missing /dev, /proc, /tmp pseudo-filesystems, got: " + mountCalls);
            check(out2.contains("FALLBACK_CHROOT " + chrootDir + " /bin/bash -lc echo INNER_CMD"),
                "Fallback branch must execute chroot directly, got: " + out2);

            String mountsSnippet = ChrootManager.buildEnsureRootMountsSnippet("/data/local/debian/");
            Path pMounts = tmpDir.resolve("mounts.sh");
            Files.write(pMounts, mountsSnippet.getBytes(StandardCharsets.UTF_8));
            Process synMounts = new ProcessBuilder("sh", "-n", pMounts.toString()).start();
            check(synMounts.waitFor() == 0, "EnsureRootMounts snippet sh -n failed");
        } finally {
            deleteTreeQuietly(tmpDir.toFile());
        }
        System.out.println("[PASS] Test 27: Root exec fallback handles custom scripts without exec) & prepares mounts");
    }

    private static String readAllUtf8(InputStream is) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = is.read(buf)) != -1) {
            baos.write(buf, 0, n);
        }
        return new String(baos.toByteArray(), StandardCharsets.UTF_8);
    }

    private static void deleteTreeQuietly(File f) {
        if (f == null) return;
        if (!TarExtractor.isSymlink(f) && f.isDirectory()) {
            File[] children = f.listFiles();
            if (children != null) {
                for (File c : children) {
                    deleteTreeQuietly(c);
                }
            }
        }
        //noinspection ResultOfMethodCallIgnored
        f.delete();
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
        String actionAcquire = (String) DebianTermService.class.getField("ACTION_ACQUIRE_WAKELOCK").get(null);
        check("com.debianchroot.term.ACTION_ACQUIRE_WAKELOCK".equals(actionAcquire),
            "Unexpected ACTION_ACQUIRE_WAKELOCK: " + actionAcquire);
        String actionRelease = (String) DebianTermService.class.getField("ACTION_RELEASE_WAKELOCK").get(null);
        check("com.debianchroot.term.ACTION_RELEASE_WAKELOCK".equals(actionRelease),
            "Unexpected ACTION_RELEASE_WAKELOCK: " + actionRelease);

        check("Wake: OFF".equals(DebianTermService.formatWakeNotificationLabel(false, false)),
            "Unexpected notification label when Wake Lock OFF");
        check("⚡ Wake: CPU".equals(DebianTermService.formatWakeNotificationLabel(true, false)),
            "Unexpected notification label when CPU Wake Lock ON");
        check("⚡ Wake: CPU+WiFi".equals(DebianTermService.formatWakeNotificationLabel(true, true)),
            "Unexpected notification label when CPU+WiFi Wake Lock ON");
        check("Activar Wake".equals(DebianTermService.formatWakeActionLabel(false)),
            "Unexpected notification action label when OFF");
        check("Soltar Wake".equals(DebianTermService.formatWakeActionLabel(true)),
            "Unexpected notification action label when ON");

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

    private static void testDocumentsProviderSafety() throws Exception {
        check("com.debianchroot.term.documents".equals(DebianDocumentsProvider.AUTHORITY),
            "Unexpected DocumentsProvider AUTHORITY");
        check("debianterm_home".equals(DebianDocumentsProvider.ROOT_ID_HOME),
            "Unexpected ROOT_ID_HOME");
        check("home:".equals(DebianDocumentsProvider.DOC_ID_PREFIX),
            "Unexpected DOC_ID_PREFIX");

        Path sandbox = Files.createTempDirectory("debianterm-saf-test");
        File baseDir = new File(sandbox.toFile(), "root");
        File notesDir = new File(baseDir, "notes");
        check(notesDir.mkdirs(), "Could not create temp SAF notes dir");
        File helloPy = new File(notesDir, "hello.py");
        Files.write(helloPy.toPath(), "print('hi')\n".getBytes(StandardCharsets.UTF_8));
        File scriptSh = new File(baseDir, "script.sh");
        Files.write(scriptSh.toPath(), "#!/bin/sh\necho ok\n".getBytes(StandardCharsets.UTF_8));

        Files.createSymbolicLink(new File(baseDir, "escape").toPath(), Paths.get("/sdcard"));
        Files.createSymbolicLink(new File(baseDir, "dangling_escape").toPath(), Paths.get("/etc/nonexistent_saf_test"));
        Files.createSymbolicLink(new File(notesDir, "rel_escape").toPath(), Paths.get("../../outside"));

        try {
            File resolvedRoot = DebianDocumentsProvider.resolveDocumentFile(baseDir, "home:");
            check(resolvedRoot != null && resolvedRoot.getCanonicalPath().equals(baseDir.getCanonicalPath()),
                "Root documentId must resolve to baseDir");

            File resolvedPy = DebianDocumentsProvider.resolveDocumentFile(baseDir, "home:notes/hello.py");
            check(resolvedPy != null && resolvedPy.getCanonicalPath().equals(helloPy.getCanonicalPath()),
                "Nested path must resolve cleanly");

            check(DebianDocumentsProvider.resolveDocumentFile(baseDir, "home:../etc/passwd") == null,
                "Parent traversal must return null");
            check(DebianDocumentsProvider.resolveDocumentFile(baseDir, "home:notes/../../etc") == null,
                "Nested traversal must return null");
            check(DebianDocumentsProvider.resolveDocumentFile(baseDir, "other:notes/hello.py") == null,
                "Wrong prefix must return null");
            check(DebianDocumentsProvider.resolveDocumentFile(baseDir, "home:/etc/passwd") == null,
                "Absolute subpath must return null");
            check(DebianDocumentsProvider.resolveDocumentFile(baseDir, "home:notes/") == null,
                "Trailing slash subpath must return null");
            check(DebianDocumentsProvider.resolveDocumentFile(baseDir, "home:escape") == null,
                "External symlink to /sdcard must be blocked");
            check(DebianDocumentsProvider.resolveDocumentFile(baseDir, "home:dangling_escape") == null,
                "Dangling external symlink must be blocked");
            check(DebianDocumentsProvider.resolveDocumentFile(baseDir, "home:notes/rel_escape") == null,
                "Relative escaping symlink must be blocked");

            check("home:notes/hello.py".equals(DebianDocumentsProvider.buildDocumentId(baseDir, helloPy)),
                "buildDocumentId mismatch for nested file");
            check(DebianDocumentsProvider.buildDocumentId(baseDir, new File(baseDir, "escape")) == null,
                "buildDocumentId must reject external symlink");

            check("vnd.android.document/directory".equals(DebianDocumentsProvider.guessMimeType(notesDir)),
                "Directory MIME type mismatch");
            check("text/x-python".equals(DebianDocumentsProvider.guessMimeType(helloPy)),
                "Python MIME type mismatch");
            check("text/x-shellscript".equals(DebianDocumentsProvider.guessMimeType(scriptSh)),
                "Shell script MIME type mismatch");
            check("application/octet-stream".equals(
                DebianDocumentsProvider.guessMimeType(new File(baseDir, "blob.unknownext987"))),
                "Unknown extension fallback MIME type mismatch");
        } finally {
            TarExtractor.deleteRecursivelyNoFollow(sandbox.toFile());
        }

        String manifest = new String(Files.readAllBytes(Paths.get("AndroidManifest.xml")), StandardCharsets.UTF_8);
        check(manifest.contains(".DebianDocumentsProvider")
            && manifest.contains("com.debianchroot.term.documents")
            && manifest.contains("android.permission.MANAGE_DOCUMENTS")
            && manifest.contains("android.content.action.DOCUMENTS_PROVIDER"),
            "AndroidManifest must register DebianDocumentsProvider with MANAGE_DOCUMENTS");

        System.out.println("[PASS] Test 18: DocumentsProvider traversal/symlink safety, MIME types & Manifest");
    }

    private static void testBackupManagerTarGzRoundTrip() throws Exception {
        Path sandbox = Files.createTempDirectory("debianterm-backup-test");
        File srcDir = new File(sandbox.toFile(), "src");
        File dstDir = new File(sandbox.toFile(), "dst");
        File archive = new File(sandbox.toFile(), "backup-test.tar.gz");

        try {
            check(srcDir.mkdirs() && dstDir.mkdirs(), "Failed creating backup test dirs");
            Files.write(new File(srcDir, "hello.txt").toPath(), "hola debian\n".getBytes(StandardCharsets.UTF_8));

            File binDir = new File(srcDir, "bin");
            check(binDir.mkdirs(), "Failed creating bin dir");
            File runSh = new File(binDir, "run.sh");
            Files.write(runSh.toPath(), "#!/bin/sh\necho ok\n".getBytes(StandardCharsets.UTF_8));
            TarExtractor.setMode(runSh, 0755);

            File emptyDir = new File(srcDir, "empty_dir");
            check(emptyDir.mkdirs(), "Failed creating empty dir");

            String longRel = "very_long_directory_segment_alpha_1234567890/"
                + "very_long_directory_segment_beta_1234567890/nested/deep_pax_file_name_check.txt";
            File longFile = new File(srcDir, longRel);
            check(longFile.getParentFile().mkdirs(), "Failed creating PAX deep dir");
            String paxPayload = "pax-ok-ñ-🚀";
            Files.write(longFile.toPath(), paxPayload.getBytes(StandardCharsets.UTF_8));

            Files.createSymbolicLink(new File(srcDir, "link_hello").toPath(), Paths.get("hello.txt"));
            Files.createSymbolicLink(new File(srcDir, "ext_storage_link").toPath(), Paths.get("/sdcard/Download"));

            File storageDir = new File(srcDir, "storage");
            File devDir = new File(srcDir, "dev");
            check(storageDir.mkdirs() && devDir.mkdirs(), "Failed creating excluded dirs");
            Files.createSymbolicLink(new File(storageDir, "shared").toPath(), Paths.get("/sdcard"));
            Files.write(new File(devDir, "dummy").toPath(), "skip".getBytes(StandardCharsets.UTF_8));

            Properties props = new Properties();
            props.setProperty("color_scheme", "catppuccin");
            props.setProperty("font_id", "nerd_meslo");
            props.setProperty("custom_extra_keys_v1", ExtraKeysBarView.PRESET_PROGRAMMING);
            byte[] metaBytes = TerminalSettings.serializePropertiesMap(props);

            BackupManager.BackupResult bres = BackupManager.createTarGz(
                srcDir,
                archive,
                BackupManager.META_ENTRY_NAME,
                metaBytes,
                new HashSet<>(Arrays.asList("storage", "dev")),
                null,
                null
            );
            check(bres.success && archive.isFile() && bres.archiveBytes > 0 && bres.entriesCount >= 5,
                "BackupManager.createTarGz failed: " + bres.message);

            BackupManager.RestoreResult rres = BackupManager.restoreTarGz(archive, dstDir, null, null);
            check(rres.success && rres.entriesCount >= 5,
                "BackupManager.restoreTarGz failed: " + rres.message);

            String restoredHello = new String(Files.readAllBytes(new File(dstDir, "hello.txt").toPath()), StandardCharsets.UTF_8);
            check("hola debian\n".equals(restoredHello), "Restored hello.txt mismatch");

            File restoredRun = new File(dstDir, "bin/run.sh");
            check(restoredRun.isFile() && restoredRun.canExecute(), "Restored bin/run.sh must remain executable");

            File restoredLong = new File(dstDir, longRel);
            check(restoredLong.isFile(), "PAX deep file (>115 chars) missing after restore");
            String restoredPaxPayload = new String(Files.readAllBytes(restoredLong.toPath()), StandardCharsets.UTF_8);
            check(paxPayload.equals(restoredPaxPayload), "PAX deep UTF-8 payload mismatch");

            check(new File(dstDir, "empty_dir").isDirectory(), "Empty directory missing after restore");

            Path restoredSym = new File(dstDir, "link_hello").toPath();
            check(Files.isSymbolicLink(restoredSym) && "hello.txt".equals(Files.readSymbolicLink(restoredSym).toString()),
                "Internal symlink mismatch after restore");

            check(!new File(dstDir, "ext_storage_link").exists() && !Files.isSymbolicLink(new File(dstDir, "ext_storage_link").toPath()),
                "External /sdcard symlink must be excluded from backup");
            check(!new File(dstDir, "storage").exists() && !new File(dstDir, "dev").exists(),
                "Top-level excluded dirs (storage, dev) must not appear in restored tree");

            check(rres.metadataBytes != null && rres.metadataBytes.length > 0, "Metadata bytes must be extracted");
            Properties restoredProps = TerminalSettings.deserializePropertiesMap(rres.metadataBytes);
            check("catppuccin".equals(restoredProps.getProperty("color_scheme"))
                && "nerd_meslo".equals(restoredProps.getProperty("font_id"))
                && ExtraKeysBarView.PRESET_PROGRAMMING.equals(restoredProps.getProperty("custom_extra_keys_v1")),
                "Restored settings metadata mismatch");
            check(!new File(dstDir, BackupManager.META_ENTRY_NAME).exists(),
                "Temporary metadata properties file must be cleaned from targetDir");
        } finally {
            TarExtractor.deleteRecursivelyNoFollow(sandbox.toFile());
        }

        System.out.println("[PASS] Test 19: BackupManager .tar.gz round-trip (perms, PAX >115c, symlinks, exclusions, metadata)");
    }

    private static void testExtraKeysCustomSpecAndFonts() throws Exception {
        List<List<ExtraKeysBarView.KeySpec>> std = ExtraKeysBarView.parseCustomSpec(ExtraKeysBarView.PRESET_STANDARD);
        check(std.size() == 2 && std.get(0).size() == 7 && std.get(1).size() == 7,
            "Standard preset must parse into 2 rows x 7 keys");
        check("ESC".equals(std.get(0).get(0).code) && "~".equals(std.get(0).get(0).altCode),
            "First standard key must be ESC:~");
        check(std.get(0).get(4).repeatable && "▲".equals(std.get(0).get(4).label),
            "UP key must be repeatable with ▲ label");
        check(std.get(1).get(1).isModifier && "CTRL".equals(std.get(1).get(1).code),
            "CTRL must be marked isModifier");

        List<List<ExtraKeysBarView.KeySpec>> prog = ExtraKeysBarView.parseCustomSpec(ExtraKeysBarView.PRESET_PROGRAMMING);
        check(prog.size() == 2 && "{".equals(prog.get(0).get(3).code) && "[".equals(prog.get(0).get(3).altCode)
            && "ENTER".equals(prog.get(1).get(6).code),
            "Programming preset mismatch");

        List<List<ExtraKeysBarView.KeySpec>> vim = ExtraKeysBarView.parseCustomSpec(ExtraKeysBarView.PRESET_TMUX_VIM);
        check(vim.size() == 2 && ":".equals(vim.get(0).get(1).code) && "BKSP".equals(vim.get(1).get(6).code)
            && vim.get(1).get(6).repeatable && "⌫".equals(vim.get(1).get(6).label),
            "Tmux/Vim preset mismatch");

        List<List<ExtraKeysBarView.KeySpec>> custom = ExtraKeysBarView.parseCustomSpec("ESC:~, /:|, (:)\nCTRL, ALT");
        check(custom.size() == 2 && custom.get(0).size() == 3 && custom.get(1).size() == 2
            && "(".equals(custom.get(0).get(2).code) && ")".equals(custom.get(0).get(2).altCode),
            "Custom spec parse mismatch");

        List<List<ExtraKeysBarView.KeySpec>> fallback = ExtraKeysBarView.parseCustomSpec("   \n  ");
        check(fallback.size() == 2 && fallback.get(0).size() == 7,
            "Empty spec must fallback to standard preset");

        Path fontPath = Paths.get("assets/fonts/MesloLGS_NF_Regular.ttf");
        check(Files.isRegularFile(fontPath) && Files.size(fontPath) > 1000000L,
            "Bundled Nerd Font must exist and exceed 1 MB");
        byte[] allFontBytes = Files.readAllBytes(fontPath);
        check(allFontBytes.length >= 4
            && allFontBytes[0] == 0x00 && allFontBytes[1] == 0x01
            && allFontBytes[2] == 0x00 && allFontBytes[3] == 0x00,
            "Bundled Nerd Font must start with valid TTF header 00 01 00 00");

        check(Modifier.isPublic(TerminalSettings.class.getMethod("getFontId").getModifiers()),
            "TerminalSettings.getFontId must be public");
        check(Modifier.isPublic(TerminalSettings.class.getMethod("setFontId", String.class).getModifiers()),
            "TerminalSettings.setFontId must be public");
        check(Modifier.isPublic(TerminalSettings.class.getMethod("getCustomExtraKeysSpec").getModifiers()),
            "TerminalSettings.getCustomExtraKeysSpec must be public");
        check(Modifier.isPublic(TerminalSettings.class.getMethod("setCustomExtraKeysSpec", String.class).getModifiers()),
            "TerminalSettings.setCustomExtraKeysSpec must be public");
        check(Modifier.isPublic(TerminalSettings.class.getMethod("isMaterialYouEnabled").getModifiers()),
            "TerminalSettings.isMaterialYouEnabled must be public");
        check(Modifier.isPublic(TerminalSettings.class.getMethod("setMaterialYouEnabled", boolean.class).getModifiers()),
            "TerminalSettings.setMaterialYouEnabled must be public");
        check(Modifier.isPublic(TerminalSettings.class.getMethod("exportMetadataBytes").getModifiers()),
            "TerminalSettings.exportMetadataBytes must be public");
        check(Modifier.isPublic(TerminalSettings.class.getMethod("importMetadataBytes", byte[].class).getModifiers()),
            "TerminalSettings.importMetadataBytes must be public");
        check(Modifier.isPublic(ExtraKeysBarView.class.getMethod("setCustomSpec", String.class).getModifiers()),
            "ExtraKeysBarView.setCustomSpec must be public");
        check(Modifier.isPublic(ExtraKeysBarView.class.getMethod("setAccentColor", int.class).getModifiers()),
            "ExtraKeysBarView.setAccentColor must be public");
        check(Modifier.isPublic(ExtraKeysBarView.class.getMethod("setBarSurfaceColor", int.class).getModifiers()),
            "ExtraKeysBarView.setBarSurfaceColor must be public");
        check(Modifier.isPublic(BackupManager.class.getMethod("backupRootHome", String.class, File.class).getModifiers()),
            "BackupManager.backupRootHome must be public");

        System.out.println("[PASS] Test 20: ExtraKeys custom spec/presets, bundled Nerd Font TTF & Phase 2 API");
    }

    private static void testBootAutostartLogicAndManifest() throws Exception {
        check(Boolean.TRUE.equals(BootReceiver.resolveBootMode(TerminalSettings.BOOT_TARGET_ROOT, TerminalSettings.MODE_PROOT, true, true, true)),
            "Explicit root boot target must select Root when available");
        check(BootReceiver.resolveBootMode(TerminalSettings.BOOT_TARGET_ROOT, TerminalSettings.MODE_PROOT, false, true, true) == null,
            "Explicit root boot target without su must return null");
        check(Boolean.FALSE.equals(BootReceiver.resolveBootMode(TerminalSettings.BOOT_TARGET_PROOT, TerminalSettings.MODE_ROOT, true, true, true)),
            "Explicit proot boot target must select PRoot");
        check(Boolean.FALSE.equals(BootReceiver.resolveBootMode(TerminalSettings.BOOT_TARGET_AUTO, TerminalSettings.MODE_PROOT, true, true, true)),
            "Auto boot target with PRoot preferred must choose PRoot");
        check(Boolean.FALSE.equals(BootReceiver.resolveBootMode(TerminalSettings.BOOT_TARGET_AUTO, TerminalSettings.MODE_ROOT, false, false, true)),
            "Auto boot target must fall back to PRoot when Root is unavailable");
        check(BootReceiver.resolveBootMode(TerminalSettings.BOOT_TARGET_AUTO, TerminalSettings.MODE_ROOT, false, false, false) == null,
            "Auto boot target with no Debian installed must return null");

        String trickyScript = "# comentario\necho 'hola $HOME' && echo \"__DEBIANTERM_BOOT_EOF__\"\nservice ssh status || true\n";
        String bootKeep = BootReceiver.buildBootCommand(trickyScript, true);
        String bootExit = BootReceiver.buildBootCommand("# solo comentarios\n", false);
        check(bootKeep.contains("/root/.debianterm/boot/*")
            && bootKeep.contains("exec /bin/bash -l")
            && !bootKeep.contains("tee "),
            "Keep-alive boot command must scan boot dir, avoid blocking pipe tee, and exec bash");
        check(bootExit.contains("exit \"$BOOT_RC\"") && !bootExit.contains("/bin/sh <<'"),
            "Comment-only boot command should skip heredoc and exit with BOOT_RC");

        for (int i = 0; i < 2; i++) {
            String cmd = (i == 0) ? bootKeep : bootExit;
            Path tmp = Paths.get("/tmp/test-boot-cmd-" + i + ".sh");
            Files.write(tmp, cmd.getBytes(StandardCharsets.UTF_8));
            try {
                Process p = new ProcessBuilder("sh", "-n", tmp.toString()).start();
                check(p.waitFor() == 0, "Boot command sh -n failed (" + i + ")");
            } finally {
                Files.deleteIfExists(tmp);
            }
        }

        String manifest = new String(Files.readAllBytes(Paths.get("AndroidManifest.xml")), StandardCharsets.UTF_8);
        check(manifest.contains("android:versionCode=\"13\"") && manifest.contains("android:versionName=\"2.5.0\""),
            "AndroidManifest must bump versionCode=13 / versionName=2.5.0");
        check(Files.exists(Paths.get("LICENSE"))
            && new String(Files.readAllBytes(Paths.get("LICENSE")), StandardCharsets.UTF_8)
                .contains("GNU GENERAL PUBLIC LICENSE"),
            "LICENSE must exist with GNU GENERAL PUBLIC LICENSE text");
        check(Files.exists(Paths.get(".github/FUNDING.yml"))
            && new String(Files.readAllBytes(Paths.get(".github/FUNDING.yml")), StandardCharsets.UTF_8)
                .contains("ko_fi: teykercol"),
            ".github/FUNDING.yml must declare ko_fi: teykercol");
        check(manifest.contains("android.permission.RECEIVE_BOOT_COMPLETED")
            && manifest.contains(".BootReceiver")
            && manifest.contains("android.intent.action.BOOT_COMPLETED"),
            "AndroidManifest must register BootReceiver and RECEIVE_BOOT_COMPLETED");

        String bootAction = (String) DebianTermService.class.getField("ACTION_RUN_BOOT_TASKS").get(null);
        check("com.debianchroot.term.ACTION_RUN_BOOT_TASKS".equals(bootAction),
            "Unexpected ACTION_RUN_BOOT_TASKS: " + bootAction);
        check(Modifier.isPublic(TerminalSettings.class.getMethod("isBootAutostartEnabled").getModifiers()),
            "TerminalSettings.isBootAutostartEnabled must be public");
        check(Modifier.isPublic(TerminalSettings.class.getMethod("setBootAutostartEnabled", boolean.class).getModifiers()),
            "TerminalSettings.setBootAutostartEnabled must be public");
        check(Modifier.isPublic(TerminalSettings.class.getMethod("isSixelEnabled").getModifiers()),
            "TerminalSettings.isSixelEnabled must be public");
        check(Modifier.isPublic(TerminalSettings.class.getMethod("setSixelEnabled", boolean.class).getModifiers()),
            "TerminalSettings.setSixelEnabled must be public");

        System.out.println("[PASS] Test 21: BootReceiver resolution, POSIX boot script (sh -n) & Manifest");
    }

    private static void testSixelDecoderCore() {
        SixelDecoder dec = new SixelDecoder(true, 0xFF000000);
        String seq = "\"1;1;12;12#1;2;100;0;0#2;1;240;50;100#1!6~-#2!6~";
        for (int i = 0; i < seq.length(); i++) {
            dec.feed(seq.charAt(i));
        }
        SixelImage img = dec.finish();
        check(img != null && img.widthPx == 12 && img.heightPx == 12,
            "Sixel image expected 12x12, got " + (img == null ? "null" : (img.widthPx + "x" + img.heightPx)));
        check(img.pixels[0] == 0xFFFF0000, "Pixel (0,0) RGB red expected 0xFFFF0000, got " + Integer.toHexString(img.pixels[0]));
        check(img.pixels[6] == 0x00000000, "Transparent right gap expected 0x00000000");
        check(img.pixels[6 * 12] == 0xFF00FF00, "Pixel (0,6) VT340 HLS H=240 green expected 0xFF00FF00, got " + Integer.toHexString(img.pixels[6 * 12]));

        SixelDecoder opaque = new SixelDecoder(false, 0xFF112233);
        String opSeq = "#3;2;0;0;100!4~$" + "!2C";
        for (int i = 0; i < opSeq.length(); i++) {
            opaque.feed(opSeq.charAt(i));
        }
        SixelImage opImg = opaque.finish();
        check(opImg != null && opImg.widthPx == 4 && opImg.heightPx == 6,
            "Opaque Sixel without raster expected 4x6");
        check(opImg.pixels[0] == 0xFF0000FF && opImg.pixels[2 * 4 + 1] == 0xFF0000FF,
            "Opaque blue pixels mismatch");

        SixelDecoder huge = new SixelDecoder(true, 0xFF000000);
        String hugeSeq = "#1;2;100;100;100!50000~";
        for (int i = 0; i < hugeSeq.length(); i++) {
            huge.feed(hugeSeq.charAt(i));
        }
        SixelImage capped = huge.finish();
        check(capped != null && capped.widthPx == SixelDecoder.MAX_WIDTH,
            "Excessive repeat must cap at MAX_WIDTH=" + SixelDecoder.MAX_WIDTH);

        SixelDecoder demo = new SixelDecoder(true, 0xFF000000);
        String demoSeq = SixelDecoder.generateDemoSixelSequence();
        int qIdx = demoSeq.indexOf('q');
        int stIdx = demoSeq.lastIndexOf("\033\\");
        check(qIdx > 0 && stIdx > qIdx, "Demo Sixel sequence malformed");
        for (int i = qIdx + 1; i < stIdx; i++) {
            demo.feed(demoSeq.charAt(i));
        }
        SixelImage demoImg = demo.finish();
        check(demoImg != null && demoImg.widthPx == 240 && demoImg.heightPx == 72,
            "Demo Sixel must decode to 240x72");

        System.out.println("[PASS] Test 22: SixelDecoder RGB, HLS, raster attrs, RLE & safety cap");
    }

    private static void testTerminalEmulatorSixelIntegration() {
        RecordingOutput out = new RecordingOutput();
        TerminalEmulator em = new TerminalEmulator(out, 80, 24, 10, 20, 200, new StubSessionClient());
        check(em.isSixelEnabled(), "Sixel should default enabled");

        feed(em, "\033[c");
        String daOn = out.drainAscii();
        check(daOn.contains(";4;"), "DA1 must advertise Sixel (;4;) when enabled: " + daOn);

        em.setSixelEnabled(false);
        feed(em, "\033[c");
        String daOff = out.drainAscii();
        check(!daOff.contains(";4;"), "DA1 must omit ;4; when Sixel disabled: " + daOff);
        em.setSixelEnabled(true);

        feed(em, "\033[?1;1;0S");
        String regReply = out.drainAscii();
        check("\033[?1;0;256S".equals(regReply), "XTSMGRAPHICS color registers mismatch: " + regReply);

        feed(em, "\033[?2;1;0S");
        String geomReply = out.drainAscii();
        check("\033[?2;0;800;480S".equals(geomReply), "XTSMGRAPHICS geometry mismatch: " + geomReply);

        feed(em, "\r\nline1\r\n");
        int rowBefore = em.getCursorRow();
        long absBefore = em.getScreen().getBaseAbsoluteRow() + rowBefore;
        feed(em, "\033P0;1q\"1;1;40;40#1;2;100;50;0!40~-\033\\");
        List<SixelImage> imgs = em.getScreen().getSixelImages();
        check(imgs.size() == 1, "Expected 1 SixelImage in screen buffer, got " + imgs.size());
        SixelImage img = imgs.get(0);
        check(img.anchorAbsoluteRow == absBefore && img.rowSpan == 2,
            "Sixel image anchor/span mismatch: abs=" + img.anchorAbsoluteRow + " span=" + img.rowSpan);
        check(em.getCursorRow() == rowBefore + 2 && em.getCursorCol() == 0,
            "Cursor must advance 2 rows below 40px Sixel (cellH=20), got row=" + em.getCursorRow());

        StringBuilder more = new StringBuilder();
        for (int i = 0; i < 30; i++) {
            more.append("scroll-line-").append(i).append("\r\n");
        }
        feed(em, more.toString());
        check(em.getScreen().getSixelImages().size() == 1 && img.anchorAbsoluteRow == absBefore,
            "Sixel anchorAbsoluteRow must stay fixed while screen scrolls into history");

        feed(em, "\033[?1049h");
        check(em.isAlternateBufferActive() && em.getScreen().getSixelImages().isEmpty(),
            "Alt screen must isolate Sixel list");
        feed(em, SixelDecoder.generateDemoSixelSequence());
        check(em.getScreen().getSixelImages().size() == 1, "Alt screen should hold its own Sixel");
        feed(em, "\033[2J");
        check(em.getScreen().getSixelImages().isEmpty(), "CSI 2 J must clear visible Sixel images");

        feed(em, "\033[?1049l");
        check(!em.isAlternateBufferActive() && em.getScreen().getSixelImages().size() == 1,
            "Main screen Sixel must survive alt screen exit");

        feed(em, "\033c");
        check(em.getScreen().getSixelImages().isEmpty(), "ESC c (RIS) must clear all Sixel images");

        System.out.println("[PASS] Test 23: TerminalEmulator Sixel DCS, DA1/XTSMGRAPHICS & scrollback anchoring");
    }

    private static void testLargeSyncRedrawAndEraseSettleAntiRewind() throws Exception {
        RecordingOutput out = new RecordingOutput();
        TerminalEmulator em = new TerminalEmulator(out, 80, 42, 10, 20, 10000, new StubSessionClient());
        em.setVisualRows(22);

        StringBuilder payload = new StringBuilder(140_000);
        for (int i = 1; i <= 2050; i++) {
            payload.append("conversation-turn-").append(i)
                .append(" [assistant] detailed reasoning payload to exceed 100KB frame size\r\n");
        }
        byte[] allBytes = payload.toString().getBytes(StandardCharsets.UTF_8);
        check(allBytes.length > 100_000, "Payload must exceed 100KB, got " + allBytes.length);

        feed(em, "\033[?2026h\033[2J\033[3J\033[H");
        int offset = 0;
        while (offset < allBytes.length) {
            int len = Math.min(16_384, allBytes.length - offset);
            em.append(Arrays.copyOfRange(allBytes, offset, offset + len), len);
            offset += len;
            check(em.getDeferredScreenUpdateDelayMs() > 0L,
                "DECSET 2026 must remain deferred across large >100KB chunks");
        }

        feed(em, "\033[?2026l");
        check(em.getDeferredScreenUpdateDelayMs() == 0L,
            "CSI ? 2026 l must release frame defer immediately");
        check(em.isFollowingOutput() && em.getMaxTopRow() > 0,
            "Large redraw must keep bottom pan follow (maxTopRow=" + em.getMaxTopRow() + ")");
        String visibleTail = em.getScreen().getSelectedText(0, em.getMaxTopRow(), 79, em.getMaxTopRow() + 21);
        check(visibleTail.contains("conversation-turn-2050"),
            "Viewport must stay at latest line 2050 without rewinding");

        // Now verify destructive erase without DECSET 2026 coalesces for <= 64 ms then settles
        feed(em, "\033[2J\033[H");
        long eraseDefer = em.getDeferredScreenUpdateDelayMs();
        check(eraseDefer > 0L && eraseDefer <= 64L,
            "Non-2026 CSI 2 J must activate erase-settle defer (1..64ms), got " + eraseDefer);
        feed(em, "redrawn-prompt> ");
        Thread.sleep(25L);
        check(em.getDeferredScreenUpdateDelayMs() == 0L,
            "Erase-settle must expire after >16ms idle");

        System.out.println("[PASS] Test 24: Large >100KB DECSET 2026 coalesce & erase-settle anti-rewind");
    }

    private static void testScrolledUpPreservedAcrossCsi3jRedrawAndTranscriptCapacity() throws Exception {
        RecordingOutput out = new RecordingOutput();
        TerminalEmulator em = new TerminalEmulator(out, 80, 40, 10, 20, 10000, new StubSessionClient());

        StringBuilder conv = new StringBuilder();
        for (int i = 1; i <= 1500; i++) {
            conv.append("msg-line-").append(i).append("\r\n");
        }
        feed(em, conv.toString());

        int initialMaxTop = em.getMaxTopRow();
        double scrolledOffset = initialMaxTop - 200.0;
        em.setViewportOffset(scrolledOffset);
        check(!em.isFollowingOutput(), "User scrolled up 200 rows must unpin followOutput");

        // TUI runs full synchronized redraw including CSI 3 J (clear scrollback) + re-prints 1500 lines
        feed(em, "\033[?2026h\033[2J\033[3J\033[H");
        feed(em, conv.toString());
        feed(em, "\033[?2026l");
        check(em.getDeferredScreenUpdateDelayMs() == 0L, "Sync frame should settle cleanly");
        check(!em.isFollowingOutput(), "Scrolled-up user must NOT be yanked to bottom after CSI 3 J conversation redraw");
        double distAfterRedraw = em.getMaxTopRow() - em.getViewportOffset();
        check(Math.abs(distAfterRedraw - 200.0) <= 2.0,
            "Scrolled-up distance from bottom must stay ~200 rows, got " + distAfterRedraw);

        // Real bash clear while scrolled up leaves 0 transcript rows -> settles to bottom cleanly
        em.setViewportOffset(em.getMaxTopRow() - 100.0);
        feed(em, "\033[H\033[2J\033[3Juser@debian:~$ ");
        Thread.sleep(25L);
        check(em.getDeferredScreenUpdateDelayMs() == 0L, "Real clear settle should complete");
        check(em.isFollowingOutput() && em.getTopRow() == 0,
            "Real bash clear with 0 transcript lines must reset to follow at row 0");

        java.lang.reflect.Field f = DebianTermService.class.getDeclaredField("DEFAULT_TRANSCRIPT_ROWS");
        f.setAccessible(true);
        int transcriptRows = f.getInt(null);
        check(transcriptRows >= 10000, "DebianTermService.DEFAULT_TRANSCRIPT_ROWS must be >= 10000, got " + transcriptRows);

        System.out.println("[PASS] Test 25: CSI 3 J scrollback preservation across redraw & 10,000-row transcript");
    }

    private static void testSafeAppExitOnLastShellExit() throws Exception {
        check(DebianTermService.isInteractiveShellKind(DebianTermService.SessionKind.DEBIAN_ROOT),
            "DEBIAN_ROOT must be interactive shell kind");
        check(DebianTermService.isInteractiveShellKind(DebianTermService.SessionKind.DEBIAN_PROOT),
            "DEBIAN_PROOT must be interactive shell kind");
        check(DebianTermService.isInteractiveShellKind(DebianTermService.SessionKind.ANDROID_ROOT),
            "ANDROID_ROOT must be interactive shell kind");
        check(DebianTermService.isInteractiveShellKind(DebianTermService.SessionKind.ANDROID_SHELL),
            "ANDROID_SHELL must be interactive shell kind");

        check(!DebianTermService.isInteractiveShellKind(DebianTermService.SessionKind.COMMAND),
            "COMMAND must not trigger full-app auto-exit");
        check(!DebianTermService.isInteractiveShellKind(DebianTermService.SessionKind.DESKTOP_VNC),
            "DESKTOP_VNC must not trigger full-app auto-exit");
        check(!DebianTermService.isInteractiveShellKind(DebianTermService.SessionKind.DESKTOP_X11),
            "DESKTOP_X11 must not trigger full-app auto-exit");
        check(!DebianTermService.isInteractiveShellKind(null),
            "null session kind must return false");

        check(DebianTermService.shouldExitAppAfterSessionClosed(DebianTermService.SessionKind.DEBIAN_ROOT, 0),
            "Last DEBIAN_ROOT shell exit must exit app");
        check(DebianTermService.shouldExitAppAfterSessionClosed(DebianTermService.SessionKind.DEBIAN_PROOT, 0),
            "Last DEBIAN_PROOT shell exit must exit app");
        check(!DebianTermService.shouldExitAppAfterSessionClosed(DebianTermService.SessionKind.DEBIAN_ROOT, 1),
            "Non-last shell exit must stay in app");
        check(!DebianTermService.shouldExitAppAfterSessionClosed(DebianTermService.SessionKind.COMMAND, 0),
            "COMMAND completion must return to dashboard, not exit app");

        Method getKind = DebianTermService.class.getMethod("getSessionKind", TerminalSession.class);
        check(Modifier.isPublic(getKind.getModifiers()), "DebianTermService.getSessionKind must be public");

        System.out.println("[PASS] Test 26: Safe full-app exit policy on last interactive shell exit");
    }

    private static void testBuiltInNativeX11JniContract() throws Exception {
        ClassLoader cl = VerifyAll.class.getClassLoader();
        Class<?> cmdCls = Class.forName("com.termux.x11.CmdEntryPoint", false, cl);
        Class<?> bridgeCls = Class.forName("com.termux.x11.MainActivity", false, cl);
        Class<?> viewCls = Class.forName("com.termux.x11.LorieView", false, cl);
        Class<?> probeCls = Class.forName("com.termux.x11.LorieView$CriticalNativeProbe", false, cl);
        Class<?> aidlCls = ICmdEntryInterface.class;

        check("com.debianchroot.term.ACTION_X11_START".equals(CmdEntryPoint.ACTION_START),
            "CmdEntryPoint.ACTION_START mismatch");
        check(aidlCls.isInterface() && ICmdEntryInterface.Stub.class.getSuperclass() == android.os.Binder.class,
            "ICmdEntryInterface.Stub must extend android.os.Binder");

        // Verify CmdEntryPoint JNI contract (cmdentrypoint.cpp:63-65, 683-693)
        String[] cmdNatives = {
            "start", "getXConnection", "windowChanged", "cursorWindowChanged",
            "getLogcatOutput", "reportFatalError", "connected"
        };
        for (String name : cmdNatives) {
            boolean found = false;
            for (Method m : cmdCls.getDeclaredMethods()) {
                if (m.getName().equals(name) && Modifier.isNative(m.getModifiers())) {
                    found = true;
                    break;
                }
            }
            check(found, "CmdEntryPoint missing native method: " + name);
        }
        cmdCls.getDeclaredMethod("setDocumentTag", String.class);
        cmdCls.getDeclaredMethod("sendBroadcast");
        cmdCls.getDeclaredMethod("sendBroadcastDelayed");

        // Verify com.termux.x11.MainActivity JNI contract (activity.cpp:154-156, 624-630)
        java.lang.reflect.Field hb = bridgeCls.getDeclaredField("lastServerHeartbeat");
        check(hb.getType() == long.class, "MainActivity.lastServerHeartbeat must be long");
        bridgeCls.getDeclaredMethod("clientConnectedStateChanged");
        Method freeform = bridgeCls.getDeclaredMethod("nativeSetLaunchWindowingModeFreeform", Object.class);
        check(Modifier.isStatic(freeform.getModifiers()) && Modifier.isNative(freeform.getModifiers()),
            "nativeSetLaunchWindowingModeFreeform must be static native");

        // Verify LorieView JNI contract (activity.cpp:167-182, 249-301, 418-587)
        java.lang.reflect.Field actField = viewCls.getDeclaredField("activity");
        check(actField.getType() == bridgeCls, "LorieView.activity must have type com.termux.x11.MainActivity");
        Method probe = probeCls.getDeclaredMethod("probe", long.class);
        check(Modifier.isStatic(probe.getModifiers()) && Modifier.isNative(probe.getModifiers()),
            "LorieView$CriticalNativeProbe.probe(long) must be static native");

        viewCls.getDeclaredMethod("setClipboardText", String.class);
        viewCls.getDeclaredMethod("requestClipboard");
        viewCls.getDeclaredMethod("setRendererViewport",
            int.class, int.class, int.class, int.class,
            float.class, float.class, float.class, float.class);
        viewCls.getDeclaredMethod("resetIme");
        viewCls.getDeclaredMethod("onSyncReply", int.class);

        String[] viewNatives = {
            "nativeInit", "nativeDestroy", "setViewport", "setRendererZoom",
            "setZoomAnchor", "clearZoomAnchor", "setFollowCursorPan", "getCursorPosition",
            "sendSync", "setFiltering", "connect", "connected", "startLogcat",
            "setClipboardSyncEnabled", "sendClipboardAnnounce", "sendClipboardEvent",
            "sendWindowChange", "sendMouseEvent", "sendTouchEvent", "sendStylusEvent",
            "requestStylusEnabled", "sendLockKeysState", "sendKeyEvent", "sendTextEvent",
            "requestConnection", "getLastInputTimestamp", "markUserActivity", "getCursorState"
        };
        for (String name : viewNatives) {
            boolean found = false;
            for (Method m : viewCls.getDeclaredMethods()) {
                if (m.getName().equals(name) && Modifier.isNative(m.getModifiers())) {
                    found = true;
                    break;
                }
            }
            check(found, "LorieView missing native method: " + name);
        }

        // Verify native ELF binaries in jniLibs
        for (String abi : new String[] { "arm64-v8a", "armeabi-v7a" }) {
            Path so = Paths.get("jniLibs", abi, "libXlorie.so");
            check(Files.exists(so), "Missing libXlorie.so for " + abi);
            byte[] hdr = Files.readAllBytes(so);
            check(hdr.length > 1_000_000 && hdr[0] == 0x7f && hdr[1] == 'E' && hdr[2] == 'L' && hdr[3] == 'F',
                "Invalid ELF header/size for " + so);
        }

        Method envBuilder = DebianTermService.class.getMethod("buildRawSessionEnv", String.class);
        check(Modifier.isStatic(envBuilder.getModifiers()), "DebianTermService.buildRawSessionEnv must be public static");
        String[] rawEnv = DebianTermService.buildRawSessionEnv("/data/user/0/com.debianchroot.term/files");
        check(Arrays.stream(rawEnv).anyMatch(s -> s.startsWith("HOME=/data/user/0/com.debianchroot.term/files")),
            "buildRawSessionEnv must set HOME");
        check(Arrays.stream(rawEnv).anyMatch(s -> s.contains("/apex/com.android.art/bin")),
            "buildRawSessionEnv must include Android ART bin PATH for app_process");

        System.out.println("[PASS] Test 28: Built-in native X11 (libXlorie) JNI contract & ELF binaries");
    }

    private static void testDesktopOrientationAndResolution() throws Exception {
        check("fit".equals(TerminalSettings.GUI_RESOLUTION_FIT), "GUI_RESOLUTION_FIT must equal 'fit'");
        check(TerminalSettings.ALL_GUI_RESOLUTIONS.length == 4
                && "fit".equals(TerminalSettings.ALL_GUI_RESOLUTIONS[0]),
            "ALL_GUI_RESOLUTIONS[0] must be 'fit'");
        check("Ajustar".equals(TerminalSettings.getGuiResolutionDisplayLabel("fit")),
            "Display label for 'fit' must be 'Ajustar'");
        check("1280x720".equals(TerminalSettings.getGuiResolutionDisplayLabel("1280x720")),
            "Display label for '1280x720' must be preserved");
        check("auto".equals(TerminalSettings.GUI_ORIENTATION_AUTO), "GUI_ORIENTATION_AUTO mismatch");
        check("portrait".equals(TerminalSettings.GUI_ORIENTATION_PORTRAIT), "GUI_ORIENTATION_PORTRAIT mismatch");
        check("landscape".equals(TerminalSettings.GUI_ORIENTATION_LANDSCAPE), "GUI_ORIENTATION_LANDSCAPE mismatch");

        int[] p1 = DesktopManager.parseResolution("720x1280");
        check(p1[0] == 720 && p1[1] == 1280, "parseResolution 720x1280 failed");
        int[] p2 = DesktopManager.parseResolution("540x960");
        check(p2[0] == 540 && p2[1] == 960, "parseResolution 540x960 failed");
        int[] p3 = DesktopManager.parseResolution("1080x2400");
        check(p3[0] == 1080 && p3[1] == 2400, "parseResolution 1080x2400 failed");
        int[] pBad = DesktopManager.parseResolution("invalid");
        check(pBad[0] == 1280 && pBad[1] == 720, "parseResolution invalid fallback must be 1280x720");

        int[] fitPortrait = DesktopManager.computeFitResolution(1080, 2160, true);
        check(fitPortrait[0] == 720 && fitPortrait[1] == 1440,
            "computeFitResolution(1080, 2160, true) must be 720x1440, got " + fitPortrait[0] + "x" + fitPortrait[1]);
        int[] fitLand = DesktopManager.computeFitResolution(1080, 2160, false);
        check(fitLand[0] == 1440 && fitLand[1] == 720,
            "computeFitResolution(1080, 2160, false) must be 1440x720, got " + fitLand[0] + "x" + fitLand[1]);
        int[] fit720 = DesktopManager.computeFitResolution(720, 1400, true);
        check(fit720[0] == 720 && fit720[1] == 1400,
            "computeFitResolution(720, 1400, true) must be 720x1400, got " + fit720[0] + "x" + fit720[1]);

        int[][] samples = {
            { 1080, 1990 },
            { 1080, 2400 },
            { 1440, 3040 },
            { 720, 1520 }
        };
        for (int[] s : samples) {
            for (boolean port : new boolean[] { true, false }) {
                int[] fit = DesktopManager.computeFitResolution(s[0], s[1], port);
                check(fit[0] >= 480 && fit[1] >= 480, "Fit bounds must be >= 480");
                check(fit[0] % 8 == 0, "Fit width must be multiple of 8 for CVT: " + fit[0]);
                check(fit[1] % 2 == 0, "Fit height must be multiple of 2: " + fit[1]);
                check(port ? (fit[0] <= fit[1]) : (fit[0] >= fit[1]), "Fit orientation mismatch");
            }
        }

        int[] rFitPort = DesktopManager.resolveDesktopResolution("fit", "portrait", 1080, 2160);
        check(rFitPort[0] == 720 && rFitPort[1] == 1440, "resolveDesktopResolution fit portrait mismatch");
        int[] rFitLand = DesktopManager.resolveDesktopResolution("fit", "landscape", 1080, 2160);
        check(rFitLand[0] == 1440 && rFitLand[1] == 720, "resolveDesktopResolution fit landscape mismatch");
        int[] rFitAutoPort = DesktopManager.resolveDesktopResolution("fit", "auto", 1080, 2160);
        check(rFitAutoPort[0] == 720 && rFitAutoPort[1] == 1440, "resolveDesktopResolution fit auto portrait mismatch");
        int[] rFixedPort = DesktopManager.resolveDesktopResolution("1280x720", "portrait", 1080, 2160);
        check(rFixedPort[0] == 720 && rFixedPort[1] == 1280, "resolveDesktopResolution 1280x720 portrait must swap to 720x1280");
        int[] rFixedLand = DesktopManager.resolveDesktopResolution("960x540", "landscape", 1080, 2160);
        check(rFixedLand[0] == 960 && rFixedLand[1] == 540, "resolveDesktopResolution 960x540 landscape must be 960x540");

        String vncXfcePort = DesktopManager.generateVncStartCommand(DesktopManager.DESKTOP_XFCE, 720, 1440);
        check(vncXfcePort.contains("-geometry 720x1440"), "XFCE VNC must support portrait geometry 720x1440");
        String vncLxdePort = DesktopManager.generateVncStartCommand(DesktopManager.DESKTOP_LXDE, 540, 960);
        check(vncLxdePort.contains("-geometry 540x960"), "LXDE VNC must support portrait geometry 540x960");
        String[] vncScripts = { vncXfcePort, vncLxdePort };
        for (int idx = 0; idx < vncScripts.length; idx++) {
            Path tmp = Paths.get("/tmp/test-vnc-port-" + idx + ".sh");
            Files.write(tmp, vncScripts[idx].getBytes(StandardCharsets.UTF_8));
            try {
                Process p = new ProcessBuilder("sh", "-n", tmp.toString()).start();
                check(p.waitFor() == 0, "Portrait VNC script sh -n failed (" + idx + ")");
            } finally {
                Files.deleteIfExists(tmp);
            }
        }

        Method setFit = LorieView.class.getMethod("setFitToScreen", boolean.class);
        check(Modifier.isPublic(setFit.getModifiers()), "LorieView.setFitToScreen(boolean) must be public");

        System.out.println("[PASS] Test 29: Desktop orientation & full-screen fit resolution (X11 & VNC)");
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
