package com.debianchroot.term;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.provider.DocumentsContract;
import android.provider.Settings;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.Log;
import android.util.TypedValue;
import android.view.ContextMenu;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import com.termux.terminal.KeyHandler;
import com.termux.terminal.SixelDecoder;
import com.termux.terminal.TerminalColors;
import com.termux.terminal.TerminalEmulator;
import com.termux.terminal.TerminalSession;
import com.termux.terminal.TerminalSessionClient;
import com.termux.view.TerminalView;
import com.termux.view.TerminalViewClient;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Main activity featuring:
 * 1) Startup Debian 12 (Bookworm) Requirements, Graphical/CLI Selector & Two-Path Installer Dashboard
 * 2) Patched Termux TerminalView with synchronized output (DECSET 2026) and soft-keyboard anti-rewind pan
 * 3) Integrated RFB VNC Desktop Canvas + optional Termux:X11 launcher
 */
public final class MainActivity extends Activity implements TerminalViewClient, TerminalSessionClient {

    private static final String LOG_TAG = "DebianTerm";
    private static final String APP_GITHUB_URL = "https://github.com/thesergio9-spec/DebianTerm";
    private static final String APP_ISSUES_URL = "https://github.com/thesergio9-spec/DebianTerm/issues";
    private static final String APP_KOFI_URL = "https://ko-fi.com/teykercol";

    private static final int CTX_COPY_URL = 101;
    private static final int CTX_SHARE_TEXT = 102;
    private static final int CTX_PASTE = 103;
    private static final int CTX_RESET_TERM = 104;
    private static final String PERM_POST_NOTIFICATIONS = "android.permission.POST_NOTIFICATIONS";
    private static final int REQ_POST_NOTIFICATIONS = 2001;
    private static final int REQ_STORAGE_PERMISSION = 2002;
    private static final int REQ_PICK_FONT = 2004;
    private static final int REQ_PICK_BACKUP = 2005;
    private static final String CUSTOM_FONT_FILE_NAME = "custom_font.ttf";

    private final ExecutorService mBgExecutor = Executors.newSingleThreadExecutor();

    private TerminalSettings mSettings;
    private FrameLayout mRootFrame;
    private SlideDrawerLayout mDrawerLayout;
    private ScrollView mDashboardView;
    private LinearLayout mDesktopView;
    private TerminalView mTerminalView;
    private ExtraKeysBarView mExtraKeys;

    // Terminal view controls
    private LinearLayout mTopBar;
    private TextView mMenuBtn;
    private TextView mTitleView;
    private TextView mChrootPill;
    private TextView mDrawerChrootSummary;
    private LinearLayout mDrawerSessionsContainer;
    private Button mDrawerDesktopBtn;
    private Button mDrawerWakeLockBtn;

    // Terminal search & Gboard text input dock
    private LinearLayout mSearchBar;
    private EditText mSearchInput;
    private TextView mSearchCountTv;
    private TextView mSearchCaseBtn;
    private List<TerminalSearch.Match> mSearchMatches = Collections.emptyList();
    private int mSearchMatchIndex = -1;
    private boolean mSearchCaseSensitive = false;
    private LinearLayout mBottomExtraRow;
    private TextView mToggleTextInputBtn;
    private LinearLayout mTextInputBar;
    private EditText mTextInputEdit;
    private boolean mTextInputMode = false;

    // Integrated VNC Desktop controls
    private VncCanvasView mVncCanvas;
    private LinearLayout mDesktopOverlay;
    private TextView mDesktopOverlayText;
    private TextView mDesktopTouchToggleBtn;
    private RfbClient mRfbClient;
    private TerminalSession mActiveDesktopSession;

    // Dashboard controls
    private LinearLayout mDashResumeContainer;
    private Button mDashResumeBtn;
    private Button mDashResumeGuiBtn;
    private ProgressBar mDashCheckSpinner;
    private TextView mDashVerdictBanner;
    private LinearLayout mDashReqsList;
    private LinearLayout mDashPowerActionsRow;
    private Button mDashBatteryOptBtn;
    private Button mDashNotifPermBtn;
    private Button mDashStoragePermBtn;
    private Button mDashPhantomFixBtn;

    // Graphical Mode Card controls
    private RadioButton mUiCliRb;
    private RadioButton mUiGuiRb;
    private RadioButton mBackendVncRb;
    private RadioButton mBackendX11Rb;
    private LinearLayout mX11NoticeBox;
    private TextView mX11NoticeText;
    private Button mX11DownloadBtn;
    private RadioButton mDeskXfceRb;
    private RadioButton mDeskLxdeRb;
    private RadioGroup mResGroup;
    private TextView mGuiDesktopStatusTv;
    private Button mInstallDesktopBtn;

    // Path Cards controls
    private TextView mRootCardBadge;
    private TextView mRootCardSummary;
    private Button mRootPrimaryBtn;
    private Button mRootSecondaryBtn;

    private TextView mProotCardBadge;
    private TextView mProotCardSummary;
    private Button mProotPrimaryBtn;
    private Button mProotSecondaryBtn;

    private Switch mDashAutoEnterSwitch;

    private DebianTermService mService;
    private boolean mBound = false;
    private TerminalSession mCurrentSession;
    private boolean mInitialCheckDone = false;
    private boolean mCheckInFlight = false;
    private boolean mNotifPromptedThisRun = false;
    private SystemRequirements.Report mLastReport = new SystemRequirements.Report(Collections.emptyList());
    private String mPendingContextUrl = null;
    private int mActiveAccentColor = Color.parseColor("#D70A53");

    private final ServiceConnection mServiceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            DebianTermService.LocalBinder localBinder = (DebianTermService.LocalBinder) binder;
            mService = localBinder.getService();
            mBound = true;
            mService.setClientVisible(true);
            mService.setEventListener(new DebianTermService.ServiceEventListener() {
                @Override
                public void onServiceStopAllRequested() {
                    runOnUiThread(MainActivity.this::finish);
                }

                @Override
                public void onWakeLockStateChanged(boolean held) {
                    runOnUiThread(MainActivity.this::updateWakeLockUi);
                }
            });
            mService.pruneCleanlyFinishedSessions();

            List<TerminalSession> sessions = mService.getSessions();
            for (TerminalSession s : sessions) {
                s.updateTerminalSessionClient(MainActivity.this);
            }

            if (!sessions.isEmpty()) {
                TerminalSession target = mService.hasSession(mCurrentSession) ? mCurrentSession : null;
                if (target == null) {
                    for (int i = sessions.size() - 1; i >= 0; i--) {
                        if (sessions.get(i).isRunning()) {
                            target = sessions.get(i);
                            break;
                        }
                    }
                    if (target == null) {
                        target = sessions.get(sessions.size() - 1);
                    }
                }
                showTerminalView();
                switchToSession(target);
                runRequirementsCheckAsync(false);
            } else {
                mCurrentSession = null;
                showDashboardView(false);
                if (!mInitialCheckDone) {
                    mInitialCheckDone = true;
                    runRequirementsCheckAsync(true);
                } else {
                    runRequirementsCheckAsync(false);
                }
            }
            refreshDrawerSessions();
            updatePathButtonsForMode();
            updateWakeLockUi();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            mBound = false;
            mService = null;
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);

        mSettings = new TerminalSettings(this);
        setContentView(buildRootLayout());

        mTerminalView.setTerminalViewClient(this);
        mTerminalView.setSoftKeyboardPansMainScreen(mSettings.isSoftKeyboardPanMainScreen());
        mTerminalView.setTextSize(mSettings.getFontSizePx());
        applyTerminalFont();
        registerForContextMenu(mTerminalView);

        mExtraKeys.setCustomSpec(mSettings.getCustomExtraKeysSpec());
        applyColorScheme(mSettings.getColorSchemeId());
        applyExtraKeysMode(mSettings.getExtraKeysMode(), false);
        mExtraKeys.setHapticEnabled(mSettings.isHapticEnabled());

        Intent svcIntent = new Intent(this, DebianTermService.class);
        bindService(svcIntent, mServiceConnection, Context.BIND_AUTO_CREATE);
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (mBound && mService != null) {
            mService.setClientVisible(true);
            mService.pruneCleanlyFinishedSessions();
            if (mCurrentSession != null && !mService.hasSession(mCurrentSession)) {
                List<TerminalSession> rem = mService.getSessions();
                if (!rem.isEmpty()) {
                    switchToSession(rem.get(rem.size() - 1));
                } else {
                    mCurrentSession = null;
                    mTitleView.setText("DebianTerm");
                    if (isTerminalVisible()) {
                        showDashboardView(false);
                    }
                }
            }
            refreshDrawerSessions();
            updatePathButtonsForMode();
            updateWakeLockUi();
        }
        if (!isTerminalVisible() && !isDesktopVisible() && mInitialCheckDone) {
            runRequirementsCheckAsync(false);
        }
        if (ProotManager.hasSharedStorageAccess(this)) {
            mBgExecutor.execute(() -> {
                File rootfs = ProotManager.getRootfsDir(MainActivity.this);
                if (rootfs.isDirectory() && !new File(rootfs, "root/storage/shared").exists()) {
                    try {
                        ProotManager.setupStorageLinks(rootfs);
                        ProotManager.installLauncherScript(MainActivity.this, mSettings.isProotDisableSeccomp());
                    } catch (IOException ignored) {
                    }
                }
            });
        }
        if (mTerminalView != null && isTerminalVisible()) {
            mTerminalView.setTerminalCursorBlinkerRate(500);
            mTerminalView.setTerminalCursorBlinkerState(true, true);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_POST_NOTIFICATIONS) {
            if (mService != null) {
                mService.onSessionStateChanged();
            }
            updateWakeLockUi();
            runRequirementsCheckAsync(false);
        } else if (requestCode == REQ_STORAGE_PERMISSION) {
            if (ProotManager.hasSharedStorageAccess(this)) {
                runSetupStorageFlow();
            } else {
                Toast.makeText(this, "Permiso de almacenamiento denegado.", Toast.LENGTH_LONG).show();
                runRequirementsCheckAsync(false);
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            return;
        }
        final Uri uri = data.getData();
        if (requestCode == REQ_PICK_FONT) {
            handleCustomFontPicked(uri);
        } else if (requestCode == REQ_PICK_BACKUP) {
            showRestoreTargetChooser(null, uri);
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (mTerminalView != null) {
            mTerminalView.setTerminalCursorBlinkerState(false, true);
        }
        if (mBound && mService != null) {
            mService.setClientVisible(false);
        }
    }

    @Override
    protected void onDestroy() {
        disconnectRfbClient();
        if (mBound) {
            unbindService(mServiceConnection);
            mBound = false;
        }
        mBgExecutor.shutdownNow();
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (isDesktopVisible()) {
            showDashboardView();
            return;
        }
        if (isTerminalVisible() && mDrawerLayout != null && mDrawerLayout.isDrawerOpen()) {
            mDrawerLayout.closeDrawer();
            return;
        }
        if (isTerminalVisible() && isSearchBarVisible()) {
            hideSearchBar();
            return;
        }
        if (isTerminalVisible() && isTextInputVisible()) {
            setTextInputMode(false);
            return;
        }
        if (isTerminalVisible() && mTerminalView != null && mTerminalView.isSelectingText()) {
            mTerminalView.stopTextSelectionMode();
            return;
        }
        super.onBackPressed();
    }

    // -------------------------------------------------------------------------
    // Root Layout & View Switching (Dashboard <-> Terminal <-> Desktop GUI)
    // -------------------------------------------------------------------------

    private View buildRootLayout() {
        mRootFrame = new FrameLayout(this);
        mRootFrame.setBackgroundColor(Color.parseColor("#0D1117"));

        mDrawerLayout = buildTerminalDrawerLayout();
        mDashboardView = buildDashboardView();
        mDesktopView = buildDesktopView();

        mDrawerLayout.setVisibility(View.GONE);
        mDesktopView.setVisibility(View.GONE);
        mDashboardView.setVisibility(View.VISIBLE);

        mRootFrame.addView(mDrawerLayout, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        mRootFrame.addView(mDesktopView, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        mRootFrame.addView(mDashboardView, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        return mRootFrame;
    }

    private boolean isTerminalVisible() {
        return mDrawerLayout != null && mDrawerLayout.getVisibility() == View.VISIBLE;
    }

    private boolean isDesktopVisible() {
        return mDesktopView != null && mDesktopView.getVisibility() == View.VISIBLE;
    }

    private void showDashboardView() {
        showDashboardView(true);
    }

    private void showDashboardView(boolean triggerCheck) {
        hideSoftKeyboard();
        if (mDrawerLayout != null && mDrawerLayout.isDrawerOpen()) {
            mDrawerLayout.closeDrawer();
        }
        if (mDrawerLayout != null) mDrawerLayout.setVisibility(View.GONE);
        if (mDesktopView != null) mDesktopView.setVisibility(View.GONE);
        if (mTerminalView != null) mTerminalView.setTerminalCursorBlinkerState(false, true);
        if (mDashboardView != null) mDashboardView.setVisibility(View.VISIBLE);
        if (mService != null) {
            mService.pruneCleanlyFinishedSessions();
            if (mCurrentSession != null && !mService.hasSession(mCurrentSession)) {
                mCurrentSession = null;
            }
        }
        updateDashboardResumeBar();
        updatePathButtonsForMode();
        if (triggerCheck) {
            runRequirementsCheckAsync(false);
        }
    }

    private void showTerminalView() {
        if (mDashboardView != null) mDashboardView.setVisibility(View.GONE);
        if (mDesktopView != null) mDesktopView.setVisibility(View.GONE);
        if (mDrawerLayout != null) mDrawerLayout.setVisibility(View.VISIBLE);
        if (mTerminalView != null) {
            mTerminalView.requestFocus();
            mTerminalView.setTerminalCursorBlinkerRate(500);
            mTerminalView.setTerminalCursorBlinkerState(true, true);
        }
    }

    private void showDesktopView() {
        if (mDashboardView != null) mDashboardView.setVisibility(View.GONE);
        if (mDrawerLayout != null) {
            if (mDrawerLayout.isDrawerOpen()) mDrawerLayout.closeDrawer();
            mDrawerLayout.setVisibility(View.GONE);
        }
        if (mTerminalView != null) mTerminalView.setTerminalCursorBlinkerState(false, true);
        if (mDesktopView != null) mDesktopView.setVisibility(View.VISIBLE);
        if (mVncCanvas != null) mVncCanvas.requestFocus();
    }

    // -------------------------------------------------------------------------
    // Integrated VNC Desktop View UI
    // -------------------------------------------------------------------------

    private LinearLayout buildDesktopView() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.parseColor("#0D1117"));

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(Color.parseColor("#161B22"));
        bar.setPadding(dp(4), dp(2), dp(4), dp(2));

        bar.addView(makeDesktopBarButton("⌂ Inicio", "#21262D", v -> showDashboardView()));
        bar.addView(makeDesktopBarButton(">_ Term", "#21262D", v -> showTerminalView()));
        mDesktopTouchToggleBtn = makeDesktopBarButton("🖱 Touch", "#1F6FEB", v -> toggleDesktopPointerMode());
        bar.addView(mDesktopTouchToggleBtn);
        bar.addView(makeDesktopBarButton("Clic Der.", "#21262D", v -> {
            if (mVncCanvas != null) mVncCanvas.sendRightClickAtCursor();
        }));
        bar.addView(makeDesktopBarButton("⌨", "#21262D", v -> toggleDesktopKeyboard()));

        View spacer = new View(this);
        bar.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1f));

        bar.addView(makeDesktopBarButton("✕ Detener", "#B62324", v -> stopActiveDesktop()));
        root.addView(bar, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(40)));

        FrameLayout canvasWrap = new FrameLayout(this);
        mVncCanvas = new VncCanvasView(this);
        canvasWrap.addView(mVncCanvas, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        mDesktopOverlay = new LinearLayout(this);
        mDesktopOverlay.setOrientation(LinearLayout.VERTICAL);
        mDesktopOverlay.setGravity(Gravity.CENTER);
        mDesktopOverlay.setBackgroundColor(Color.parseColor("#CC0D1117"));
        mDesktopOverlay.setPadding(dp(24), dp(24), dp(24), dp(24));

        ProgressBar pb = new ProgressBar(this);
        mDesktopOverlay.addView(pb);

        mDesktopOverlayText = new TextView(this);
        mDesktopOverlayText.setText("Conectando al escritorio Debian 12 (VNC :1)...");
        mDesktopOverlayText.setTextColor(Color.parseColor("#E6EDF3"));
        mDesktopOverlayText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        mDesktopOverlayText.setGravity(Gravity.CENTER);
        mDesktopOverlayText.setPadding(0, dp(12), 0, dp(12));
        mDesktopOverlay.addView(mDesktopOverlayText);

        Button viewLogBtn = makeActionButton("Ver consola del servidor", "#30363D", v -> showTerminalView());
        mDesktopOverlay.addView(viewLogBtn, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        canvasWrap.addView(mDesktopOverlay, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        root.addView(canvasWrap, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        return root;
    }

    private TextView makeDesktopBarButton(String label, String bgHex, View.OnClickListener listener) {
        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setTextColor(Color.WHITE);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(dp(8), dp(5), dp(8), dp(5));
        tv.setBackground(makePillBg(bgHex));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(dp(2), 0, dp(2), 0);
        tv.setLayoutParams(lp);
        tv.setOnClickListener(listener);
        return tv;
    }

    private void toggleDesktopPointerMode() {
        if (mVncCanvas == null || mDesktopTouchToggleBtn == null) return;
        boolean nextTouchpad = !mVncCanvas.isTouchpadMode();
        mVncCanvas.setTouchpadMode(nextTouchpad);
        mDesktopTouchToggleBtn.setText(nextTouchpad ? "🖱 Touch" : "👆 Táctil");
        mDesktopTouchToggleBtn.setBackground(makePillBg(nextTouchpad ? "#1F6FEB" : "#238636"));
        Toast.makeText(this,
            nextTouchpad ? "Modo Touchpad (arrastra cursor, 2 dedos clic der./scroll)"
                         : "Modo Táctil directo (toca y arrastra directo en pantalla)",
            Toast.LENGTH_SHORT).show();
    }

    private void toggleDesktopKeyboard() {
        if (mVncCanvas == null) return;
        mVncCanvas.requestFocus();
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.toggleSoftInput(InputMethodManager.SHOW_FORCED, 0);
        }
    }

    // -------------------------------------------------------------------------
    // Startup Requirements, Graphical Selector & Two-Path Installer Dashboard
    // -------------------------------------------------------------------------

    private ScrollView buildDashboardView() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.parseColor("#0D1117"));
        scroll.setFillViewport(true);

        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(dp(14), dp(14), dp(14), dp(24));

        // 1. Header
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(4), dp(2), dp(2), dp(10));

        LinearLayout titleCol = new LinearLayout(this);
        titleCol.setOrientation(LinearLayout.VERTICAL);
        TextView titleTv = new TextView(this);
        titleTv.setText("DebianTerm");
        titleTv.setTextColor(Color.parseColor("#E6EDF3"));
        titleTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 21);
        titleTv.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        titleCol.addView(titleTv);

        TextView subTv = new TextView(this);
        subTv.setText("Instalador Debian 12 • Terminal y Escritorio Gráfico");
        subTv.setTextColor(Color.parseColor("#8B949E"));
        subTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        titleCol.addView(subTv);
        header.addView(titleCol, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        header.addView(makeHeaderIconButton("↻", v -> runRequirementsCheckAsync(false)));
        header.addView(makeHeaderIconButton("⚙", v -> showSettingsDialog()));
        container.addView(header);

        // 2. Resume bars (active terminal / active graphical desktop)
        mDashResumeContainer = new LinearLayout(this);
        mDashResumeContainer.setOrientation(LinearLayout.VERTICAL);
        mDashResumeContainer.setVisibility(View.GONE);
        LinearLayout.LayoutParams resumeWrapLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        resumeWrapLp.setMargins(0, 0, 0, dp(10));
        mDashResumeContainer.setLayoutParams(resumeWrapLp);

        mDashResumeGuiBtn = new Button(this);
        mDashResumeGuiBtn.setAllCaps(false);
        mDashResumeGuiBtn.setTextColor(Color.WHITE);
        mDashResumeGuiBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f);
        mDashResumeGuiBtn.setTypeface(Typeface.DEFAULT_BOLD);
        mDashResumeGuiBtn.setBackground(makeRoundedBg("#238636", "#3FB950", 8));
        mDashResumeGuiBtn.setPadding(dp(14), dp(9), dp(14), dp(9));
        mDashResumeGuiBtn.setText("🖥 Volver al Escritorio Gráfico activo");
        mDashResumeGuiBtn.setVisibility(View.GONE);
        mDashResumeGuiBtn.setOnClickListener(v -> showDesktopView());
        LinearLayout.LayoutParams guiResLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        guiResLp.setMargins(0, 0, 0, dp(6));
        mDashResumeContainer.addView(mDashResumeGuiBtn, guiResLp);

        mDashResumeBtn = new Button(this);
        mDashResumeBtn.setAllCaps(false);
        mDashResumeBtn.setTextColor(Color.WHITE);
        mDashResumeBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f);
        mDashResumeBtn.setTypeface(Typeface.DEFAULT_BOLD);
        mDashResumeBtn.setBackground(makeRoundedBg("#1F6FEB", "#388BFD", 8));
        mDashResumeBtn.setPadding(dp(14), dp(9), dp(14), dp(9));
        mDashResumeBtn.setOnClickListener(v -> showTerminalView());
        mDashResumeContainer.addView(mDashResumeBtn, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        container.addView(mDashResumeContainer);

        // 3. Card 1: Verificación de Requerimientos Mínimos
        LinearLayout reqCard = makeCardLayout();
        LinearLayout reqHead = new LinearLayout(this);
        reqHead.setOrientation(LinearLayout.HORIZONTAL);
        reqHead.setGravity(Gravity.CENTER_VERTICAL);

        TextView reqTitle = new TextView(this);
        reqTitle.setText("1. VERIFICACIÓN DE REQUERIMIENTOS");
        reqTitle.setTextColor(Color.parseColor("#8B949E"));
        reqTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        reqTitle.setTypeface(Typeface.DEFAULT_BOLD);
        reqHead.addView(reqTitle, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        mDashCheckSpinner = new ProgressBar(this, null, android.R.attr.progressBarStyleSmall);
        reqHead.addView(mDashCheckSpinner);
        reqCard.addView(reqHead);

        mDashVerdictBanner = new TextView(this);
        mDashVerdictBanner.setText("Analizando hardware, almacenamiento, red, root, PRoot y entorno gráfico...");
        mDashVerdictBanner.setTextColor(Color.parseColor("#E6EDF3"));
        mDashVerdictBanner.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        mDashVerdictBanner.setTypeface(Typeface.DEFAULT_BOLD);
        mDashVerdictBanner.setPadding(0, dp(6), 0, dp(8));
        reqCard.addView(mDashVerdictBanner);

        mDashReqsList = new LinearLayout(this);
        mDashReqsList.setOrientation(LinearLayout.VERTICAL);
        reqCard.addView(mDashReqsList);

        mDashPowerActionsRow = new LinearLayout(this);
        mDashPowerActionsRow.setOrientation(LinearLayout.HORIZONTAL);
        mDashPowerActionsRow.setPadding(0, dp(8), 0, 0);
        mDashPowerActionsRow.setVisibility(View.GONE);

        mDashBatteryOptBtn = makeActionButton("🔋 Sin restricciones (Doze)", "#9E6A03", v ->
            requestIgnoreBatteryOptimizations());
        mDashBatteryOptBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        mDashBatteryOptBtn.setVisibility(View.GONE);
        LinearLayout.LayoutParams batBtnLp = new LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        batBtnLp.setMargins(0, 0, dp(4), 0);
        mDashPowerActionsRow.addView(mDashBatteryOptBtn, batBtnLp);

        mDashNotifPermBtn = makeActionButton("🔔 Activar notificaciones", "#1F6FEB", v ->
            requestNotificationsPermissionIfNeeded(true));
        mDashNotifPermBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        mDashNotifPermBtn.setVisibility(View.GONE);
        LinearLayout.LayoutParams notifBtnLp = new LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        notifBtnLp.setMargins(dp(4), 0, 0, 0);
        mDashPowerActionsRow.addView(mDashNotifPermBtn, notifBtnLp);

        reqCard.addView(mDashPowerActionsRow);

        mDashStoragePermBtn = makeActionButton("📁 Configurar almacenamiento (setup-storage)", "#238636", v ->
            runSetupStorageFlow());
        mDashStoragePermBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        mDashStoragePermBtn.setVisibility(View.GONE);
        LinearLayout.LayoutParams storBtnLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        storBtnLp.setMargins(0, dp(6), 0, 0);
        reqCard.addView(mDashStoragePermBtn, storBtnLp);

        mDashPhantomFixBtn = makeActionButton("🛡 Corregir Phantom Killer (signal 9)", "#9E6A03", v ->
            showPhantomKillerDialog());
        mDashPhantomFixBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        mDashPhantomFixBtn.setVisibility(View.GONE);
        LinearLayout.LayoutParams phantomBtnLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        phantomBtnLp.setMargins(0, dp(6), 0, 0);
        reqCard.addView(mDashPhantomFixBtn, phantomBtnLp);

        container.addView(reqCard);

        // 4. Card 2: Modo de Inicio & Entorno Gráfico (Híbrido: VNC Integrado + Termux:X11)
        container.addView(buildGraphicalModeCard());

        // 5. Card 3: Camino 1 — Con Root (Chroot Nativo)
        LinearLayout rootCard = makeCardLayout();
        LinearLayout rootHead = new LinearLayout(this);
        rootHead.setOrientation(LinearLayout.HORIZONTAL);
        rootHead.setGravity(Gravity.CENTER_VERTICAL);

        TextView rootTitle = new TextView(this);
        rootTitle.setText("Camino 1 • Con Root (Chroot)");
        rootTitle.setTextColor(Color.parseColor("#E6EDF3"));
        rootTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15.5f);
        rootTitle.setTypeface(Typeface.DEFAULT_BOLD);
        rootHead.addView(rootTitle, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        mRootCardBadge = makeStatusBadge("Comprobando", "#30363D");
        rootHead.addView(mRootCardBadge);
        rootCard.addView(rootHead);

        TextView rootDesc = new TextView(this);
        rootDesc.setText("Rendimiento nativo del kernel Linux con montajes reales (/dev, /proc, /sys, /sdcard). Requiere Magisk, KernelSU o APatch.");
        rootDesc.setTextColor(Color.parseColor("#8B949E"));
        rootDesc.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        rootDesc.setPadding(0, dp(5), 0, dp(8));
        rootCard.addView(rootDesc);

        mRootCardSummary = new TextView(this);
        mRootCardSummary.setTypeface(Typeface.MONOSPACE);
        mRootCardSummary.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        mRootCardSummary.setTextColor(Color.parseColor("#C9D1D9"));
        mRootCardSummary.setBackgroundColor(Color.parseColor("#0D1117"));
        mRootCardSummary.setPadding(dp(10), dp(8), dp(10), dp(8));
        mRootCardSummary.setText("Comprobando ruta " + mSettings.getChrootPath() + "...");
        rootCard.addView(mRootCardSummary);

        LinearLayout rootBtnRow = new LinearLayout(this);
        rootBtnRow.setOrientation(LinearLayout.HORIZONTAL);
        rootBtnRow.setPadding(0, dp(10), 0, 0);

        mRootPrimaryBtn = makeActionButton("Comprobando...", "#21262D", v -> onRootPrimaryClicked());
        mRootSecondaryBtn = makeActionButton("Gestionar", "#21262D", v -> showRootManageDialog());
        rootBtnRow.addView(mRootPrimaryBtn, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        LinearLayout.LayoutParams secLp1 = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        secLp1.setMargins(dp(8), 0, 0, 0);
        rootBtnRow.addView(mRootSecondaryBtn, secLp1);
        rootCard.addView(rootBtnRow);
        container.addView(rootCard);

        // 6. Card 4: Camino 2 — Sin Root (PRoot Integrado)
        LinearLayout prootCard = makeCardLayout();
        LinearLayout prootHead = new LinearLayout(this);
        prootHead.setOrientation(LinearLayout.HORIZONTAL);
        prootHead.setGravity(Gravity.CENTER_VERTICAL);

        TextView prootTitle = new TextView(this);
        prootTitle.setText("Camino 2 • Sin Root (PRoot)");
        prootTitle.setTextColor(Color.parseColor("#E6EDF3"));
        prootTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15.5f);
        prootTitle.setTypeface(Typeface.DEFAULT_BOLD);
        prootHead.addView(prootTitle, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        mProotCardBadge = makeStatusBadge("Comprobando", "#30363D");
        prootHead.addView(mProotCardBadge);
        prootCard.addView(prootHead);

        TextView prootDesc = new TextView(this);
        prootDesc.setText("100% autónomo sin permisos root ni Termux. Debian 12 aislado en almacenamiento interno mediante PRoot empaquetado.");
        prootDesc.setTextColor(Color.parseColor("#8B949E"));
        prootDesc.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        prootDesc.setPadding(0, dp(5), 0, dp(8));
        prootCard.addView(prootDesc);

        mProotCardSummary = new TextView(this);
        mProotCardSummary.setTypeface(Typeface.MONOSPACE);
        mProotCardSummary.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        mProotCardSummary.setTextColor(Color.parseColor("#C9D1D9"));
        mProotCardSummary.setBackgroundColor(Color.parseColor("#0D1117"));
        mProotCardSummary.setPadding(dp(10), dp(8), dp(10), dp(8));
        mProotCardSummary.setText("Verificando motor PRoot integrado...");
        prootCard.addView(mProotCardSummary);

        LinearLayout prootBtnRow = new LinearLayout(this);
        prootBtnRow.setOrientation(LinearLayout.HORIZONTAL);
        prootBtnRow.setPadding(0, dp(10), 0, 0);

        mProotPrimaryBtn = makeActionButton("Comprobando...", "#21262D", v -> onProotPrimaryClicked());
        mProotSecondaryBtn = makeActionButton("Gestionar", "#21262D", v -> showProotManageDialog());
        prootBtnRow.addView(mProotPrimaryBtn, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        LinearLayout.LayoutParams secLp2 = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        secLp2.setMargins(dp(8), 0, 0, 0);
        prootBtnRow.addView(mProotSecondaryBtn, secLp2);
        prootCard.addView(prootBtnRow);
        container.addView(prootCard);

        // 7. Footer preference switch
        LinearLayout footerCard = makeCardLayout();
        mDashAutoEnterSwitch = new Switch(this);
        mDashAutoEnterSwitch.setText("Entrar automáticamente a Debian 12 al iniciar");
        mDashAutoEnterSwitch.setTextColor(Color.parseColor("#E6EDF3"));
        mDashAutoEnterSwitch.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        mDashAutoEnterSwitch.setChecked(mSettings.isAutoEnterTerminal());
        mDashAutoEnterSwitch.setOnCheckedChangeListener((buttonView, isChecked) ->
            mSettings.setAutoEnterTerminal(isChecked));
        footerCard.addView(mDashAutoEnterSwitch);

        TextView autoHint = new TextView(this);
        autoHint.setText("Respeta tu modo elegido arriba (Terminal CLI o Modo Gráfico) en el camino instalado.");
        autoHint.setTextColor(Color.parseColor("#8B949E"));
        autoHint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        autoHint.setPadding(0, dp(4), 0, 0);
        footerCard.addView(autoHint);
        container.addView(footerCard);

        TextView devFooter = new TextView(this);
        devFooter.setText("DebianTerm • Desarrollado por TeykerCol • ☕ Apoyar en Ko-fi");
        devFooter.setTextColor(Color.parseColor("#8B949E"));
        devFooter.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        devFooter.setGravity(Gravity.CENTER_HORIZONTAL);
        devFooter.setPadding(dp(8), dp(14), dp(8), dp(8));
        devFooter.setOnClickListener(v -> showAboutDialog());
        container.addView(devFooter);

        scroll.addView(container);
        return scroll;
    }

    private LinearLayout buildGraphicalModeCard() {
        LinearLayout card = makeCardLayout();

        TextView header = new TextView(this);
        header.setText("2. MODO DE INICIO (TERMINAL O ESCRITORIO GRÁFICO)");
        header.setTextColor(Color.parseColor("#8B949E"));
        header.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        header.setTypeface(Typeface.DEFAULT_BOLD);
        card.addView(header);

        RadioGroup uiModeGroup = new RadioGroup(this);
        uiModeGroup.setOrientation(LinearLayout.HORIZONTAL);
        uiModeGroup.setPadding(0, dp(6), 0, dp(4));

        mUiCliRb = new RadioButton(this);
        mUiCliRb.setText("⌨ Terminal (CLI)");
        mUiCliRb.setTextColor(Color.parseColor("#E6EDF3"));
        mUiCliRb.setId(View.generateViewId());

        mUiGuiRb = new RadioButton(this);
        mUiGuiRb.setText("🖥 Modo Gráfico");
        mUiGuiRb.setTextColor(Color.parseColor("#E6EDF3"));
        mUiGuiRb.setId(View.generateViewId());

        uiModeGroup.addView(mUiCliRb);
        uiModeGroup.addView(mUiGuiRb);

        if (TerminalSettings.UI_MODE_GUI.equals(mSettings.getLaunchUiMode())) {
            mUiGuiRb.setChecked(true);
        } else {
            mUiCliRb.setChecked(true);
        }
        mUiCliRb.setOnClickListener(v -> {
            mSettings.setLaunchUiMode(TerminalSettings.UI_MODE_CLI);
            updatePathButtonsForMode();
        });
        mUiGuiRb.setOnClickListener(v -> {
            mSettings.setLaunchUiMode(TerminalSettings.UI_MODE_GUI);
            updatePathButtonsForMode();
        });
        card.addView(uiModeGroup);

        // Hybrid backend selector
        TextView backLbl = new TextView(this);
        backLbl.setText("Motor gráfico (Híbrido):");
        backLbl.setTextColor(Color.parseColor("#C9D1D9"));
        backLbl.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        backLbl.setTypeface(Typeface.DEFAULT_BOLD);
        backLbl.setPadding(0, dp(6), 0, dp(2));
        card.addView(backLbl);

        RadioGroup backendGroup = new RadioGroup(this);
        backendGroup.setOrientation(LinearLayout.HORIZONTAL);
        mBackendVncRb = new RadioButton(this);
        mBackendVncRb.setText("Visor VNC Integrado");
        mBackendVncRb.setTextColor(Color.parseColor("#E6EDF3"));
        mBackendVncRb.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        mBackendVncRb.setId(View.generateViewId());

        mBackendX11Rb = new RadioButton(this);
        mBackendX11Rb.setText("Termux:X11 (60 FPS)");
        mBackendX11Rb.setTextColor(Color.parseColor("#E6EDF3"));
        mBackendX11Rb.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        mBackendX11Rb.setId(View.generateViewId());

        backendGroup.addView(mBackendVncRb);
        backendGroup.addView(mBackendX11Rb);
        if (DesktopManager.BACKEND_TERMUX_X11.equals(mSettings.getGuiBackend())) {
            mBackendX11Rb.setChecked(true);
        } else {
            mBackendVncRb.setChecked(true);
        }
        mBackendVncRb.setOnClickListener(v -> {
            mSettings.setGuiBackend(DesktopManager.BACKEND_VNC);
            updatePathButtonsForMode();
        });
        mBackendX11Rb.setOnClickListener(v -> {
            mSettings.setGuiBackend(DesktopManager.BACKEND_TERMUX_X11);
            updatePathButtonsForMode();
        });
        card.addView(backendGroup);

        // Termux:X11 detection & download recommendation notice
        mX11NoticeBox = new LinearLayout(this);
        mX11NoticeBox.setOrientation(LinearLayout.VERTICAL);
        mX11NoticeBox.setBackground(makeRoundedBg("#0D1117", "#30363D", 6));
        mX11NoticeBox.setPadding(dp(10), dp(8), dp(10), dp(8));
        LinearLayout.LayoutParams noticeLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        noticeLp.setMargins(0, dp(6), 0, dp(6));
        mX11NoticeBox.setLayoutParams(noticeLp);

        mX11NoticeText = new TextView(this);
        mX11NoticeText.setText("Comprobando si Termux:X11 está instalado...");
        mX11NoticeText.setTextColor(Color.parseColor("#8B949E"));
        mX11NoticeText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        mX11NoticeBox.addView(mX11NoticeText);

        mX11DownloadBtn = makeActionButton("⬇ Descargar Termux:X11 APK (GitHub)", "#1F6FEB", v ->
            DesktopManager.openTermuxX11Download(MainActivity.this));
        mX11DownloadBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        LinearLayout.LayoutParams dlLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dlLp.setMargins(0, dp(6), 0, 0);
        mX11NoticeBox.addView(mX11DownloadBtn, dlLp);
        card.addView(mX11NoticeBox);

        // Desktop environment + Resolution
        TextView deskLbl = new TextView(this);
        deskLbl.setText("Escritorio y Resolución VNC:");
        deskLbl.setTextColor(Color.parseColor("#C9D1D9"));
        deskLbl.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        deskLbl.setTypeface(Typeface.DEFAULT_BOLD);
        deskLbl.setPadding(0, dp(4), 0, dp(2));
        card.addView(deskLbl);

        RadioGroup deskGroup = new RadioGroup(this);
        deskGroup.setOrientation(LinearLayout.HORIZONTAL);
        mDeskXfceRb = new RadioButton(this);
        mDeskXfceRb.setText("XFCE4 (Completo)");
        mDeskXfceRb.setTextColor(Color.parseColor("#E6EDF3"));
        mDeskXfceRb.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        mDeskXfceRb.setId(View.generateViewId());

        mDeskLxdeRb = new RadioButton(this);
        mDeskLxdeRb.setText("LXDE (Ligero)");
        mDeskLxdeRb.setTextColor(Color.parseColor("#E6EDF3"));
        mDeskLxdeRb.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        mDeskLxdeRb.setId(View.generateViewId());

        deskGroup.addView(mDeskXfceRb);
        deskGroup.addView(mDeskLxdeRb);
        if (DesktopManager.DESKTOP_LXDE.equals(mSettings.getDesktopEnv())) {
            mDeskLxdeRb.setChecked(true);
        } else {
            mDeskXfceRb.setChecked(true);
        }
        mDeskXfceRb.setOnClickListener(v -> mSettings.setDesktopEnv(DesktopManager.DESKTOP_XFCE));
        mDeskLxdeRb.setOnClickListener(v -> mSettings.setDesktopEnv(DesktopManager.DESKTOP_LXDE));
        card.addView(deskGroup);

        mResGroup = new RadioGroup(this);
        mResGroup.setOrientation(LinearLayout.HORIZONTAL);
        String curRes = mSettings.getGuiResolution();
        for (final String resOpt : TerminalSettings.ALL_GUI_RESOLUTIONS) {
            RadioButton rb = new RadioButton(this);
            rb.setText(resOpt);
            rb.setTextColor(Color.parseColor("#C9D1D9"));
            rb.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
            rb.setId(View.generateViewId());
            mResGroup.addView(rb);
            if (resOpt.equals(curRes)) {
                rb.setChecked(true);
            }
            rb.setOnClickListener(v -> mSettings.setGuiResolution(resOpt));
        }
        card.addView(mResGroup);

        mGuiDesktopStatusTv = new TextView(this);
        mGuiDesktopStatusTv.setText("Estado escritorio: comprobando...");
        mGuiDesktopStatusTv.setTextColor(Color.parseColor("#8B949E"));
        mGuiDesktopStatusTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        mGuiDesktopStatusTv.setTypeface(Typeface.MONOSPACE);
        mGuiDesktopStatusTv.setPadding(0, dp(6), 0, dp(8));
        card.addView(mGuiDesktopStatusTv);

        mInstallDesktopBtn = makeActionButton("⬇ Instalar Escritorio (XFCE4 / LXDE)", "#30363D", v ->
            promptInstallDesktopTarget());
        mInstallDesktopBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        card.addView(mInstallDesktopBtn, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        return card;
    }

    private TextView makeHeaderIconButton(String icon, View.OnClickListener listener) {
        TextView tv = new TextView(this);
        tv.setText(icon);
        tv.setTextColor(Color.parseColor("#E6EDF3"));
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        tv.setGravity(Gravity.CENTER);
        tv.setMinWidth(dp(38));
        tv.setMinHeight(dp(38));
        tv.setBackground(makeRoundedBg("#161B22", "#30363D", 8));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(dp(6), 0, 0, 0);
        tv.setLayoutParams(lp);
        tv.setOnClickListener(listener);
        return tv;
    }

    private LinearLayout makeCardLayout() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(makeRoundedBg("#161B22", "#30363D", 10));
        card.setPadding(dp(14), dp(12), dp(14), dp(12));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, 0, dp(12));
        card.setLayoutParams(lp);
        return card;
    }

    private TextView makeStatusBadge(String text, String bgHex) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(Color.WHITE);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setPadding(dp(9), dp(3), dp(9), dp(3));
        tv.setBackground(makePillBg(bgHex));
        return tv;
    }

    private Button makeActionButton(String text, String bgHex, View.OnClickListener listener) {
        Button btn = new Button(this);
        btn.setText(text);
        btn.setAllCaps(false);
        btn.setTextColor(Color.WHITE);
        btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        btn.setTypeface(Typeface.DEFAULT_BOLD);
        btn.setPadding(dp(12), dp(9), dp(12), dp(9));
        btn.setBackground(makePillBg(bgHex));
        btn.setOnClickListener(listener);
        return btn;
    }

    private GradientDrawable makeRoundedBg(String fillHex, String strokeHex, float radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(Color.parseColor(fillHex));
        d.setStroke(dp(1), Color.parseColor(strokeHex));
        d.setCornerRadius(dp(radiusDp));
        return d;
    }

    // -------------------------------------------------------------------------
    // Terminal Layout & Drawer UI
    // -------------------------------------------------------------------------

    private SlideDrawerLayout buildTerminalDrawerLayout() {
        LinearLayout mainContainer = new LinearLayout(this);
        mainContainer.setOrientation(LinearLayout.VERTICAL);

        // Top compact toolbar (38dp)
        mTopBar = new LinearLayout(this);
        mTopBar.setOrientation(LinearLayout.HORIZONTAL);
        mTopBar.setGravity(Gravity.CENTER_VERTICAL);
        mTopBar.setBackgroundColor(Color.parseColor("#161B22"));
        mTopBar.setPadding(dp(4), 0, dp(4), 0);

        mMenuBtn = makeTopButton("☰", v -> mDrawerLayout.toggleDrawer());
        mTopBar.addView(mMenuBtn);

        mTitleView = new TextView(this);
        mTitleView.setText("DebianTerm");
        mTitleView.setTextColor(Color.parseColor("#E6EDF3"));
        mTitleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        mTitleView.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        mTitleView.setSingleLine(true);
        mTitleView.setEllipsize(TextUtils.TruncateAt.END);
        mTitleView.setPadding(dp(6), 0, dp(6), 0);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        mTopBar.addView(mTitleView, titleLp);

        mChrootPill = new TextView(this);
        mChrootPill.setText("⌂ Inicio");
        mChrootPill.setTextColor(Color.WHITE);
        mChrootPill.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        mChrootPill.setTypeface(Typeface.DEFAULT_BOLD);
        mChrootPill.setPadding(dp(8), dp(3), dp(8), dp(3));
        mChrootPill.setBackground(makePillBg("#30363D"));
        mChrootPill.setOnClickListener(v -> showDashboardView());
        LinearLayout.LayoutParams pillLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        pillLp.setMargins(dp(4), 0, dp(4), 0);
        mTopBar.addView(mChrootPill, pillLp);

        mTopBar.addView(makeTopButton("🔍", v -> toggleSearchBar()));
        mTopBar.addView(makeTopButton("⚡", v -> showSnippetsDialog()));
        mTopBar.addView(makeTopButton("⌨", v -> toggleSoftKeyboard()));
        mTopBar.addView(makeTopButton("📋", v -> pasteFromClipboard()));
        mTopBar.addView(makeTopButton("⚙", v -> showSettingsDialog()));

        mainContainer.addView(mTopBar, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(38)));

        mSearchBar = buildSearchBarView();
        mSearchBar.setVisibility(View.GONE);
        mainContainer.addView(mSearchBar, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // Terminal view
        mTerminalView = new TerminalView(this, null);
        mTerminalView.setFocusable(true);
        mTerminalView.setFocusableInTouchMode(true);
        mTerminalView.setKeepScreenOn(false);
        mainContainer.addView(mTerminalView, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // Extra keys + Text Input toggle dock above IME
        mBottomExtraRow = new LinearLayout(this);
        mBottomExtraRow.setOrientation(LinearLayout.HORIZONTAL);
        mBottomExtraRow.setGravity(Gravity.CENTER_VERTICAL);
        mBottomExtraRow.setBackgroundColor(Color.parseColor("#161B22"));

        mExtraKeys = new ExtraKeysBarView(this);
        mExtraKeys.setListener(new ExtraKeysBarView.Listener() {
            @Override
            public void onKeyButton(String keyCode, boolean ctrl, boolean alt) {
                dispatchExtraKey(keyCode, ctrl, alt);
            }

            @Override
            public void onModifiersChanged() {
                mTerminalView.requestFocus();
            }

            @Override
            public void onSwipeToTextInput() {
                setTextInputMode(true);
            }
        });
        mBottomExtraRow.addView(mExtraKeys, new LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        mToggleTextInputBtn = new TextView(this);
        mToggleTextInputBtn.setText("📝");
        mToggleTextInputBtn.setTextColor(Color.parseColor("#E6EDF3"));
        mToggleTextInputBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        mToggleTextInputBtn.setGravity(Gravity.CENTER);
        mToggleTextInputBtn.setMinimumWidth(dp(34));
        mToggleTextInputBtn.setPadding(dp(4), 0, dp(4), 0);
        mToggleTextInputBtn.setBackground(makePillBg("#21262D"));
        mToggleTextInputBtn.setOnClickListener(v -> setTextInputMode(true));
        LinearLayout.LayoutParams ttiLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT);
        ttiLp.setMargins(dp(2), dp(2), dp(2), dp(2));
        mBottomExtraRow.addView(mToggleTextInputBtn, ttiLp);

        mainContainer.addView(mBottomExtraRow, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        mTextInputBar = buildTextInputBarView();
        mTextInputBar.setVisibility(View.GONE);
        mainContainer.addView(mTextInputBar, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        View drawerContent = buildDrawerContent();
        SlideDrawerLayout drawer = new SlideDrawerLayout(this, mainContainer, drawerContent);
        drawer.setDrawerListener(isOpen -> {
            if (isOpen) {
                refreshDrawerSessions();
                updateWakeLockUi();
            } else {
                mTerminalView.requestFocus();
            }
        });
        return drawer;
    }

    private View buildDrawerContent() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.parseColor("#161B22"));

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setBackgroundColor(Color.parseColor("#0D1117"));
        header.setPadding(dp(16), dp(18), dp(16), dp(14));

        TextView appTitle = new TextView(this);
        appTitle.setText("DebianTerm");
        appTitle.setTextColor(Color.parseColor("#E6EDF3"));
        appTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 19);
        appTitle.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        header.addView(appTitle);

        TextView devSub = new TextView(this);
        devSub.setText("Desarrollado por TeykerCol");
        devSub.setTextColor(Color.parseColor("#58A6FF"));
        devSub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        devSub.setPadding(0, dp(1), 0, 0);
        header.addView(devSub);

        mDrawerChrootSummary = new TextView(this);
        mDrawerChrootSummary.setText("Debian 12: comprobando...");
        mDrawerChrootSummary.setTextColor(Color.parseColor("#8B949E"));
        mDrawerChrootSummary.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        mDrawerChrootSummary.setPadding(0, dp(4), 0, 0);
        header.addView(mDrawerChrootSummary);

        root.addView(header, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        View divider1 = new View(this);
        divider1.setBackgroundColor(Color.parseColor("#30363D"));
        root.addView(divider1, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));

        TextView sessLabel = new TextView(this);
        sessLabel.setText("SESIONES ACTIVAS");
        sessLabel.setTextColor(Color.parseColor("#8B949E"));
        sessLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        sessLabel.setTypeface(Typeface.DEFAULT_BOLD);
        sessLabel.setPadding(dp(16), dp(12), dp(16), dp(6));
        root.addView(sessLabel);

        ScrollView scroll = new ScrollView(this);
        mDrawerSessionsContainer = new LinearLayout(this);
        mDrawerSessionsContainer.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(mDrawerSessionsContainer);
        root.addView(scroll, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        View divider2 = new View(this);
        divider2.setBackgroundColor(Color.parseColor("#30363D"));
        root.addView(divider2, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));

        View divider = new View(this);
        divider.setBackgroundColor(Color.parseColor("#30363D"));
        root.addView(divider, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.VERTICAL);
        actions.setPadding(dp(10), dp(8), dp(10), dp(10));

        mDrawerDesktopBtn = makeDrawerActionButton("🖥 Volver al Escritorio Gráfico", "#238636", v -> {
            mDrawerLayout.closeDrawer();
            showDesktopView();
        });
        mDrawerDesktopBtn.setVisibility(View.GONE);
        actions.addView(mDrawerDesktopBtn);

        Button newSessionBtn = makeDrawerActionButton("+ Nueva sesión", "#D70A53", v ->
            showNewSessionChooserDialog());
        newSessionBtn.setGravity(Gravity.CENTER);
        actions.addView(newSessionBtn);

        LinearLayout toolsRow = new LinearLayout(this);
        toolsRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams trLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        trLp.setMargins(0, dp(3), 0, dp(3));

        mDrawerWakeLockBtn = makeDrawerCompactButton("⚡ Wake: OFF", "#21262D", v ->
            toggleWakeLockFromUi());
        LinearLayout.LayoutParams halfLeft = new LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        halfLeft.setMargins(0, 0, dp(3), 0);
        toolsRow.addView(mDrawerWakeLockBtn, halfLeft);

        Button toolsBtn = makeDrawerCompactButton("🧰 Herramientas", "#21262D", v ->
            showDrawerToolsDialog());
        LinearLayout.LayoutParams halfRight = new LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        halfRight.setMargins(dp(3), 0, 0, 0);
        toolsRow.addView(toolsBtn, halfRight);
        actions.addView(toolsRow, trLp);

        LinearLayout navRow = new LinearLayout(this);
        navRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams nrLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        nrLp.setMargins(0, dp(3), 0, 0);

        Button homeBtn = makeDrawerCompactButton("⌂ Inicio", "#1F6FEB", v -> {
            mDrawerLayout.closeDrawer();
            showDashboardView();
        });
        LinearLayout.LayoutParams thirdLeft = new LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        thirdLeft.setMargins(0, 0, dp(2), 0);
        navRow.addView(homeBtn, thirdLeft);

        Button settingsBtn = makeDrawerCompactButton("⚙ Ajustes", "#21262D", v -> {
            mDrawerLayout.closeDrawer();
            showSettingsDialog();
        });
        LinearLayout.LayoutParams thirdMid = new LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        thirdMid.setMargins(dp(2), 0, dp(2), 0);
        navRow.addView(settingsBtn, thirdMid);

        Button aboutBtn = makeDrawerCompactButton("ℹ Acerca", "#21262D", v -> {
            mDrawerLayout.closeDrawer();
            showAboutDialog();
        });
        LinearLayout.LayoutParams thirdRight = new LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        thirdRight.setMargins(dp(2), 0, 0, 0);
        navRow.addView(aboutBtn, thirdRight);
        actions.addView(navRow, nrLp);

        root.addView(actions, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        return root;
    }

    private TextView makeTopButton(String label, View.OnClickListener clickListener) {
        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setTextColor(Color.parseColor("#E6EDF3"));
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15.5f);
        tv.setGravity(Gravity.CENTER);
        tv.setMinimumWidth(dp(28));
        tv.setMinimumHeight(dp(34));
        tv.setPadding(dp(4), dp(2), dp(4), dp(2));
        tv.setOnClickListener(clickListener);
        return tv;
    }

    private Button makeDrawerActionButton(String text, String bgHex, View.OnClickListener listener) {
        Button btn = new Button(this);
        btn.setText(text);
        btn.setAllCaps(false);
        btn.setTextColor(Color.WHITE);
        btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        btn.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        btn.setPadding(dp(12), dp(8), dp(12), dp(8));
        btn.setMinHeight(0);
        btn.setMinimumHeight(dp(38));
        btn.setBackground(makePillBg(bgHex));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(3), 0, dp(3));
        btn.setLayoutParams(lp);
        btn.setOnClickListener(listener);
        return btn;
    }

    private Button makeDrawerCompactButton(String text, String bgHex, View.OnClickListener listener) {
        Button btn = new Button(this);
        btn.setText(text);
        btn.setAllCaps(false);
        btn.setSingleLine(true);
        btn.setEllipsize(TextUtils.TruncateAt.END);
        btn.setTextColor(Color.WHITE);
        btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        btn.setGravity(Gravity.CENTER);
        btn.setPadding(dp(6), dp(7), dp(6), dp(7));
        btn.setMinHeight(0);
        btn.setMinimumHeight(dp(36));
        btn.setBackground(makePillBg(bgHex));
        btn.setOnClickListener(listener);
        return btn;
    }

    private void addChooserDialogButton(LinearLayout parent, String label, String bgHex,
            boolean topMargin, final AlertDialog dlg, final Runnable action) {
        Button btn = makeActionButton(label, bgHex, v -> {
            dlg.dismiss();
            if (action != null) action.run();
        });
        btn.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, topMargin ? dp(8) : 0, 0, 0);
        parent.addView(btn, lp);
    }

    private void showNewSessionChooserDialog() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(18), dp(12), dp(18), dp(10));

        final AlertDialog dlg = new AlertDialog.Builder(this)
            .setTitle("Nueva sesión")
            .setView(box)
            .setNegativeButton("Cancelar", null)
            .create();

        boolean hasRoot = mLastReport != null && mLastReport.hasRoot;
        addChooserDialogButton(box, "🐧 Debian 12 (Root Chroot)", "#D70A53", false, dlg, () -> {
            mDrawerLayout.closeDrawer();
            openNewRootDebianSession();
        });
        addChooserDialogButton(box, "📦 Debian 12 (Sin Root / PRoot)", "#238636", true, dlg, () -> {
            mDrawerLayout.closeDrawer();
            openNewProotDebianSession();
        });
        addChooserDialogButton(box,
            hasRoot ? "⚡ Shell Android (su)" : "🐚 Shell Android (sh)", "#21262D", true, dlg, () -> {
                mDrawerLayout.closeDrawer();
                if (hasRoot) {
                    openNewAndroidRootSession();
                } else {
                    openNewAndroidShellSession();
                }
            });

        dlg.show();
    }

    private void showDrawerToolsDialog() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(18), dp(12), dp(18), dp(10));

        ScrollView scroll = new ScrollView(this);
        scroll.addView(box);

        final AlertDialog dlg = new AlertDialog.Builder(this)
            .setTitle("Herramientas")
            .setView(scroll)
            .setNegativeButton("Cerrar", null)
            .create();

        boolean storageOk = ProotManager.hasSharedStorageAccess(this);
        boolean bootActive = mSettings.isBootAutostartEnabled();

        addChooserDialogButton(box, "⚡ Snippets / Comandos rápidos", "#21262D", false, dlg, () -> {
            mDrawerLayout.closeDrawer();
            showSnippetsDialog();
        });
        addChooserDialogButton(box,
            storageOk ? "📁 Almacenamiento compartido (/sdcard OK)" : "📁 Configurar almacenamiento (setup-storage)",
            storageOk ? "#238636" : "#21262D", true, dlg, () -> {
                mDrawerLayout.closeDrawer();
                runSetupStorageFlow();
            });
        addChooserDialogButton(box, "📂 Abrir archivos Debian (SAF)", "#21262D", true, dlg, () -> {
            mDrawerLayout.closeDrawer();
            openDocumentsProvider();
        });
        addChooserDialogButton(box, "💾 Respaldo y Restauración (.tar.gz)", "#21262D", true, dlg, () -> {
            mDrawerLayout.closeDrawer();
            showBackupRestoreDialog();
        });
        addChooserDialogButton(box,
            bootActive ? "🚀 Autoarranque al iniciar (ACTIVO)" : "🚀 Autoarranque al iniciar",
            bootActive ? "#238636" : "#21262D", true, dlg, () -> {
                mDrawerLayout.closeDrawer();
                showBootAutostartDialog();
            });

        dlg.show();
    }

    private void showAboutDialog() {
        String ver = "2.4.1";
        try {
            ver = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception ignored) {
        }

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(18), dp(12), dp(18), dp(10));

        TextView info = new TextView(this);
        info.setText("Debian 12 (Bookworm) Chroot & PRoot Terminal para Android con gráficos Sixel y escritorio XFCE4/LXDE.\n\n"
            + "Desarrollado por: TeykerCol (Sergio Romero)\n"
            + "Licencia: GNU GPL v3 (Código Abierto)\n"
            + "Soporte y reportes: GitHub Issues");
        info.setTextColor(Color.parseColor("#E6EDF3"));
        info.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        info.setLineSpacing(0f, 1.15f);
        box.addView(info);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(box);

        final AlertDialog dlg = new AlertDialog.Builder(this)
            .setTitle("DebianTerm v" + ver)
            .setView(scroll)
            .setPositiveButton("Cerrar", null)
            .create();

        addChooserDialogButton(box, "☕ Apoyar proyecto en Ko-fi", "#D9463E", true, dlg,
            () -> openExternalUrl(APP_KOFI_URL));
        addChooserDialogButton(box, "💻 Código fuente en GitHub", "#1F6FEB", true, dlg,
            () -> openExternalUrl(APP_GITHUB_URL));
        addChooserDialogButton(box, "🐛 Reportar fallo (GitHub Issues)", "#21262D", true, dlg,
            () -> openExternalUrl(APP_ISSUES_URL));

        dlg.show();
    }

    private void openExternalUrl(String url) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            startActivity(intent);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "No se encontró navegador para abrir el enlace.", Toast.LENGTH_LONG).show();
        }
    }

    private GradientDrawable makePillBg(String hex) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(Color.parseColor(hex));
        d.setCornerRadius(dp(6));
        return d;
    }

    // -------------------------------------------------------------------------
    // Session Management (CLI + Graphical Desktop)
    // -------------------------------------------------------------------------

    private void enterOrResumeRootDebianSession() {
        if (mService == null) return;
        mSettings.setInstallMode(TerminalSettings.MODE_ROOT);
        TerminalSession existing = mService.findRunningSession(DebianTermService.SessionKind.DEBIAN_ROOT);
        if (existing != null) {
            showTerminalView();
            switchToSession(existing);
            refreshDrawerSessions();
            updatePathButtonsForMode();
            return;
        }
        mService.pruneFinishedSessions(DebianTermService.SessionKind.DEBIAN_ROOT);
        openNewRootDebianSession();
    }

    private void enterOrResumeProotDebianSession() {
        if (mService == null) return;
        mSettings.setInstallMode(TerminalSettings.MODE_PROOT);
        TerminalSession existing = mService.findRunningSession(DebianTermService.SessionKind.DEBIAN_PROOT);
        if (existing != null) {
            showTerminalView();
            switchToSession(existing);
            refreshDrawerSessions();
            updatePathButtonsForMode();
            return;
        }
        mService.pruneFinishedSessions(DebianTermService.SessionKind.DEBIAN_PROOT);
        openNewProotDebianSession();
    }

    private void openNewRootDebianSession() {
        if (mService == null) return;
        mSettings.setInstallMode(TerminalSettings.MODE_ROOT);
        showTerminalView();
        TerminalSession session = mService.createDebianSession(
            mSettings.getChrootPath(),
            mSettings.getEntryScriptPath(),
            this
        );
        requestNotificationsPermissionIfNeeded(false);
        switchToSession(session);
        refreshDrawerSessions();
        updateDashboardResumeBar();
        updatePathButtonsForMode();
        updateWakeLockUi();
    }

    private void openNewProotDebianSession() {
        if (mService == null) return;
        mSettings.setInstallMode(TerminalSettings.MODE_PROOT);
        showTerminalView();
        TerminalSession session = mService.createProotDebianSession(this);
        requestNotificationsPermissionIfNeeded(false);
        switchToSession(session);
        refreshDrawerSessions();
        updateDashboardResumeBar();
        updatePathButtonsForMode();
        updateWakeLockUi();
    }

    private void openNewAndroidRootSession() {
        if (mService == null) return;
        showTerminalView();
        TerminalSession session = mService.createAndroidRootSession(this);
        requestNotificationsPermissionIfNeeded(false);
        switchToSession(session);
        refreshDrawerSessions();
        updateDashboardResumeBar();
        updateWakeLockUi();
    }

    private void openNewAndroidShellSession() {
        if (mService == null) return;
        showTerminalView();
        TerminalSession session = mService.createAndroidShellSession(this);
        requestNotificationsPermissionIfNeeded(false);
        switchToSession(session);
        refreshDrawerSessions();
        updateDashboardResumeBar();
        updateWakeLockUi();
    }

    private void launchGraphicalDebian(final boolean isRootMode) {
        if (mService == null) return;
        final String desktopId = mSettings.getDesktopEnv();
        final String backend = mSettings.getGuiBackend();
        DesktopManager.DesktopStatus ds = mLastReport.desktopStatus;

        boolean hasDesktop = isRootMode ? ds.rootDesktopInstalled : ds.prootDesktopInstalled;
        boolean hasVnc = isRootMode ? ds.rootVncServerInstalled : ds.prootVncServerInstalled;

        if (!hasDesktop || (DesktopManager.BACKEND_VNC.equals(backend) && !hasVnc)) {
            String desktopLabel = DesktopManager.DESKTOP_LXDE.equals(desktopId) ? "LXDE" : "XFCE4";
            new AlertDialog.Builder(this)
                .setTitle("Entorno gráfico no instalado")
                .setMessage("Para iniciar Debian 12 en modo gráfico necesitas instalar " + desktopLabel
                    + " + TigerVNC dentro del entorno " + (isRootMode ? "Con Root" : "Sin Root")
                    + " mediante apt-get.\n\n¿Deseas instalarlo ahora?")
                .setPositiveButton("Instalar ahora en terminal", (d, w) ->
                    startDesktopInstallInTerminal(isRootMode, desktopId))
                .setNegativeButton("Cancelar", null)
                .show();
            return;
        }

        if (DesktopManager.BACKEND_TERMUX_X11.equals(backend)) {
            if (!ds.termuxX11Installed) {
                new AlertDialog.Builder(this)
                    .setTitle("Termux:X11 no está instalado")
                    .setMessage("No se encontró la app Termux:X11 en este dispositivo.\n\n"
                        + "Puedes descargar e instalar su APK para máxima fluidez (60 FPS) o iniciar ahora mismo con el Visor VNC Integrado de DebianTerm.")
                    .setPositiveButton("⬇ Descargar Termux:X11 APK", (d, w) ->
                        DesktopManager.openTermuxX11Download(MainActivity.this))
                    .setNeutralButton("Usar Visor VNC Integrado ahora", (d, w) -> {
                        mSettings.setGuiBackend(DesktopManager.BACKEND_VNC);
                        if (mBackendVncRb != null) mBackendVncRb.setChecked(true);
                        openVncDesktopSession(isRootMode);
                    })
                    .setNegativeButton("Cancelar", null)
                    .show();
                return;
            }
            openX11DesktopSession(isRootMode);
        } else {
            openVncDesktopSession(isRootMode);
        }
    }

    private void startDesktopInstallInTerminal(boolean isRootMode, String desktopId) {
        if (mService == null) return;
        showTerminalView();
        TerminalSession session = mService.createDesktopInstallSession(
            isRootMode,
            mSettings.getChrootPath(),
            mSettings.getEntryScriptPath(),
            desktopId,
            this
        );
        switchToSession(session);
        refreshDrawerSessions();
        updateDashboardResumeBar();
    }

    private void openVncDesktopSession(boolean isRootMode) {
        if (mService == null) return;
        mSettings.setInstallMode(isRootMode ? TerminalSettings.MODE_ROOT : TerminalSettings.MODE_PROOT);
        disconnectRfbClient();
        if (mActiveDesktopSession != null && mService.hasSession(mActiveDesktopSession)) {
            mService.removeSession(mActiveDesktopSession);
        }

        int[] res = DesktopManager.parseResolution(mSettings.getGuiResolution());
        TerminalSession deskSession = mService.createDesktopVncSession(
            isRootMode,
            mSettings.getChrootPath(),
            mSettings.getEntryScriptPath(),
            mSettings.getDesktopEnv(),
            res[0],
            res[1],
            this
        );
        mActiveDesktopSession = deskSession;
        requestNotificationsPermissionIfNeeded(false);
        switchToSession(deskSession);
        showDesktopView();
        mDesktopOverlay.setVisibility(View.VISIBLE);
        mDesktopOverlayText.setText("Iniciando "
            + (DesktopManager.DESKTOP_LXDE.equals(mSettings.getDesktopEnv()) ? "LXDE" : "XFCE4")
            + " (" + res[0] + "×" + res[1] + ") en VNC :1...");

        RfbClient client = new RfbClient();
        mRfbClient = client;
        mVncCanvas.attachClient(client);
        client.connectWithRetry("127.0.0.1", DesktopManager.VNC_PORT, "", 40, 250L, new RfbClient.FrameListener() {
            @Override
            public void onConnected(int width, int height, String desktopName) {
                runOnUiThread(() -> {
                    mVncCanvas.initFramebuffer(width, height);
                    mDesktopOverlay.setVisibility(View.GONE);
                });
            }

            @Override
            public void onFramebufferResize(int width, int height) {
                runOnUiThread(() -> mVncCanvas.initFramebuffer(width, height));
            }

            @Override
            public void onFrameUpdated(int[] pixels, int width, int height, int x, int y, int w, int h) {
                mVncCanvas.updateFramebufferRect(pixels, width, height, x, y, w, h);
                if (mDesktopOverlay.getVisibility() == View.VISIBLE) {
                    runOnUiThread(() -> mDesktopOverlay.setVisibility(View.GONE));
                }
            }

            @Override
            public void onServerCutText(final String text) {
                if (TextUtils.isEmpty(text)) return;
                runOnUiThread(() -> {
                    ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                    if (cm != null) {
                        cm.setPrimaryClip(ClipData.newPlainText("DebianTerm Desktop", text));
                    }
                });
            }

            @Override
            public void onDisconnected(final String reason) {
                runOnUiThread(() -> {
                    if (mRfbClient != client) return;
                    if (isDesktopVisible()) {
                        mDesktopOverlay.setVisibility(View.VISIBLE);
                        mDesktopOverlayText.setText("Sesión VNC finalizada: " + reason);
                    }
                });
            }
        });
        refreshDrawerSessions();
        updateDashboardResumeBar();
        updateWakeLockUi();
    }

    private void openX11DesktopSession(boolean isRootMode) {
        if (mService == null) return;
        mSettings.setInstallMode(isRootMode ? TerminalSettings.MODE_ROOT : TerminalSettings.MODE_PROOT);
        TerminalSession deskSession = mService.createDesktopX11Session(
            isRootMode,
            mSettings.getChrootPath(),
            mSettings.getEntryScriptPath(),
            mSettings.getDesktopEnv(),
            mLastReport.desktopStatus,
            this
        );
        requestNotificationsPermissionIfNeeded(false);
        switchToSession(deskSession);
        showTerminalView();
        refreshDrawerSessions();
        updateDashboardResumeBar();
        updateWakeLockUi();

        Toast.makeText(this, "Iniciando servidor Termux:X11 y escritorio Debian 12...", Toast.LENGTH_SHORT).show();
        mRootFrame.postDelayed(() -> {
            if (!DesktopManager.launchTermuxX11App(MainActivity.this)) {
                Toast.makeText(MainActivity.this,
                    "Abre la app Termux:X11 manualmente si no saltó al frente.",
                    Toast.LENGTH_LONG).show();
            }
        }, 900L);
    }

    private void stopActiveDesktop() {
        disconnectRfbClient();
        if (mService != null && mActiveDesktopSession != null) {
            mService.removeSession(mActiveDesktopSession);
            if (mCurrentSession == mActiveDesktopSession) {
                List<TerminalSession> rem = mService.getSessions();
                mCurrentSession = rem.isEmpty() ? null : rem.get(rem.size() - 1);
                if (mCurrentSession != null) {
                    mTerminalView.attachSession(mCurrentSession);
                }
            }
        }
        mActiveDesktopSession = null;
        showDashboardView();
        refreshDrawerSessions();
    }

    private void disconnectRfbClient() {
        RfbClient c = mRfbClient;
        mRfbClient = null;
        if (mVncCanvas != null) {
            mVncCanvas.detachClient();
        }
        if (c != null) {
            c.disconnect();
        }
    }

    private void switchToSession(TerminalSession session) {
        if (session == null) return;
        mCurrentSession = session;
        TerminalEmulator em = session.getEmulator();
        if (em != null) {
            em.setSixelEnabled(mSettings.isSixelEnabled());
        }
        mTerminalView.attachSession(session);
        if (isTerminalVisible()) {
            mTerminalView.requestFocus();
        }
        updateSessionTitle();
        refreshDrawerSessions();
        if (isSearchBarVisible()) {
            performTerminalSearch(false);
        }
    }

    private void updateSessionTitle() {
        if (mTitleView == null) return;
        if (mService != null && mCurrentSession != null) {
            String prefix = mService.isWakeLockHeld() ? "⚡ " : "";
            mTitleView.setText(prefix + mService.getSessionDisplayName(mCurrentSession));
        } else {
            mTitleView.setText("DebianTerm");
        }
    }

    private void toggleWakeLockFromUi() {
        if (mService == null) return;
        if (mService.getSessions().isEmpty()) {
            Toast.makeText(this, "Abre una sesión primero para activar Wake Lock.", Toast.LENGTH_SHORT).show();
            return;
        }
        boolean willEnable = !mService.isWakeLockHeld();
        mService.setWakeLockEnabled(willEnable);
        updateWakeLockUi();
        if (willEnable) {
            requestNotificationsPermissionIfNeeded(false);
            String msg = DebianTermService.isIgnoringBatteryOptimizations(this)
                ? "Wake Lock (CPU + WiFi) activado."
                : "Wake Lock (CPU + WiFi) activo. Recomendado desactivar optimización de batería (Doze).";
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
        } else {
            Toast.makeText(this, "Wake Lock liberado.", Toast.LENGTH_SHORT).show();
        }
    }

    private void updateWakeLockUi() {
        updateSessionTitle();
        if (mDrawerWakeLockBtn != null) {
            boolean held = mService != null && mService.isWakeLockHeld();
            boolean wifi = mService != null && mService.isWifiLockHeld();
            mDrawerWakeLockBtn.setText(held
                ? (wifi ? "⚡ Wake: CPU+WiFi" : "⚡ Wake: CPU")
                : "⚡ Wake: OFF");
            mDrawerWakeLockBtn.setBackground(makePillBg(held ? "#9E6A03" : "#21262D"));
        }
    }

    private void requestNotificationsPermissionIfNeeded(boolean openSettingsFallback) {
        if (DebianTermService.areNotificationsEnabled(this)) {
            return;
        }
        if (Build.VERSION.SDK_INT >= 33) {
            if (checkSelfPermission(PERM_POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                if (!openSettingsFallback) {
                    if (!mNotifPromptedThisRun) {
                        mNotifPromptedThisRun = true;
                        requestPermissions(new String[]{PERM_POST_NOTIFICATIONS}, REQ_POST_NOTIFICATIONS);
                    }
                    return;
                }
                if (!mNotifPromptedThisRun || shouldShowRequestPermissionRationale(PERM_POST_NOTIFICATIONS)) {
                    mNotifPromptedThisRun = true;
                    requestPermissions(new String[]{PERM_POST_NOTIFICATIONS}, REQ_POST_NOTIFICATIONS);
                    return;
                }
            }
        }
        if (!openSettingsFallback) return;
        try {
            Intent intent;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                intent = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName());
            } else {
                intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + getPackageName()));
            }
            startActivity(intent);
        } catch (ActivityNotFoundException | SecurityException e) {
            try {
                startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + getPackageName())));
            } catch (ActivityNotFoundException ignored) {
                Toast.makeText(this, "Activa las notificaciones en Ajustes del sistema.", Toast.LENGTH_LONG).show();
            }
        }
    }

    private void requestIgnoreBatteryOptimizations() {
        if (DebianTermService.isIgnoringBatteryOptimizations(this)) {
            Toast.makeText(this, "DebianTerm ya está excluida de optimización de batería (Doze).", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:" + getPackageName())));
        } catch (ActivityNotFoundException | SecurityException e) {
            try {
                startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            } catch (ActivityNotFoundException ignored) {
                Toast.makeText(this, "Desactiva la optimización de batería manualmente en Ajustes.", Toast.LENGTH_LONG).show();
            }
        }
    }

    private void runSetupStorageFlow() {
        if (ProotManager.hasSharedStorageAccess(this)) {
            mBgExecutor.execute(() -> {
                try {
                    File rootfs = ProotManager.getRootfsDir(MainActivity.this);
                    if (rootfs.isDirectory()) {
                        ProotManager.setupStorageLinks(rootfs);
                        ProotManager.installLauncherScript(MainActivity.this, mSettings.isProotDisableSeccomp());
                    }
                    runOnUiThread(() -> {
                        Toast.makeText(MainActivity.this,
                            "✓ Almacenamiento configurado: /sdcard y ~/storage listos.",
                            Toast.LENGTH_LONG).show();
                        updateWakeLockUi();
                        runRequirementsCheckAsync(false);
                    });
                } catch (IOException e) {
                    runOnUiThread(() -> Toast.makeText(MainActivity.this,
                        "Error configurando ~/storage: " + e.getMessage(),
                        Toast.LENGTH_LONG).show());
                }
            });
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Toast.makeText(this,
                "Activa 'Permitir administrar todos los archivos' para usar /sdcard en Debian Sin Root.",
                Toast.LENGTH_LONG).show();
            try {
                startActivity(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:" + getPackageName())));
            } catch (ActivityNotFoundException | SecurityException e) {
                try {
                    startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
                } catch (ActivityNotFoundException ignored) {
                    Toast.makeText(this,
                        "Concede acceso a archivos manualmente en Ajustes de Android.",
                        Toast.LENGTH_LONG).show();
                }
            }
        } else {
            requestPermissions(new String[]{
                android.Manifest.permission.READ_EXTERNAL_STORAGE,
                android.Manifest.permission.WRITE_EXTERNAL_STORAGE
            }, REQ_STORAGE_PERMISSION);
        }
    }

    private void updateDashboardResumeBar() {
        boolean hasDesktop = mActiveDesktopSession != null && mActiveDesktopSession.isRunning();
        if (mDrawerDesktopBtn != null) {
            mDrawerDesktopBtn.setVisibility(hasDesktop ? View.VISIBLE : View.GONE);
        }
        if (mDashResumeContainer == null || mDashResumeBtn == null) return;
        int running = (mService != null) ? mService.getRunningSessionCount() : 0;
        int total = (mService != null) ? mService.getSessions().size() : 0;
        if (mDashResumeGuiBtn != null) {
            mDashResumeGuiBtn.setVisibility(hasDesktop ? View.VISIBLE : View.GONE);
        }
        if (total > 0 || hasDesktop) {
            mDashResumeContainer.setVisibility(View.VISIBLE);
            mDashResumeBtn.setVisibility(total > 0 ? View.VISIBLE : View.GONE);
            if (running > 0) {
                mDashResumeBtn.setText("↩ Volver a la terminal activa (" + running + ")");
            } else {
                mDashResumeBtn.setText("↩ Ver terminal finalizada (" + total + ")");
            }
        } else {
            mDashResumeContainer.setVisibility(View.GONE);
        }
    }

    private void refreshDrawerSessions() {
        updateDashboardResumeBar();
        if (mDrawerSessionsContainer == null || mService == null) return;
        mDrawerSessionsContainer.removeAllViews();
        List<TerminalSession> sessions = mService.getSessions();
        for (int i = 0; i < sessions.size(); i++) {
            final TerminalSession s = sessions.get(i);
            boolean active = (s == mCurrentSession);

            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(12), dp(8), dp(8), dp(8));
            row.setBackgroundColor(active ? Color.parseColor("#21262D") : Color.TRANSPARENT);

            View indicator = new View(this);
            indicator.setBackgroundColor(active ? mActiveAccentColor : Color.TRANSPARENT);
            row.addView(indicator, new LinearLayout.LayoutParams(dp(4), dp(24)));

            TextView nameTv = new TextView(this);
            String label = mService.getSessionDisplayName(s);
            if (!s.isRunning()) {
                label += " (finalizada)";
            }
            nameTv.setText(label);
            nameTv.setTextColor(active ? Color.WHITE : Color.parseColor("#C9D1D9"));
            nameTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f);
            nameTv.setSingleLine(true);
            nameTv.setEllipsize(TextUtils.TruncateAt.END);
            nameTv.setPadding(dp(10), 0, dp(6), 0);
            row.addView(nameTv, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            TextView closeTv = new TextView(this);
            closeTv.setText("✕");
            closeTv.setTextColor(Color.parseColor("#8B949E"));
            closeTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            closeTv.setPadding(dp(10), dp(4), dp(8), dp(4));
            closeTv.setOnClickListener(v -> closeSession(s));
            row.addView(closeTv);

            row.setOnClickListener(v -> {
                switchToSession(s);
                mDrawerLayout.closeDrawer();
            });
            row.setOnLongClickListener(v -> {
                promptRenameSession(s);
                return true;
            });

            mDrawerSessionsContainer.addView(row, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
    }

    private void closeSession(TerminalSession session) {
        if (mService == null || session == null) return;
        if (session == mActiveDesktopSession) {
            disconnectRfbClient();
            mActiveDesktopSession = null;
        }
        boolean wasCurrent = (session == mCurrentSession);
        mService.removeSession(session);
        List<TerminalSession> remaining = mService.getSessions();
        if (wasCurrent) {
            if (!remaining.isEmpty()) {
                switchToSession(remaining.get(remaining.size() - 1));
            } else {
                mCurrentSession = null;
                mTitleView.setText("DebianTerm");
                showDashboardView();
            }
        }
        refreshDrawerSessions();
        updatePathButtonsForMode();
    }

    private void promptRenameSession(final TerminalSession session) {
        final EditText input = new EditText(this);
        input.setSingleLine(true);
        if (mService != null) {
            input.setText(mService.getSessionDisplayName(session));
            input.setSelection(input.getText().length());
        }
        new AlertDialog.Builder(this)
            .setTitle("Renombrar sesión")
            .setView(input)
            .setPositiveButton("Guardar", (d, w) -> {
                if (mService != null) {
                    mService.renameSession(session, input.getText().toString());
                    updateSessionTitle();
                    refreshDrawerSessions();
                }
            })
            .setNegativeButton("Cancelar", null)
            .show();
    }

    // -------------------------------------------------------------------------
    // Requirements Verification & Dashboard Rendering
    // -------------------------------------------------------------------------

    private void runRequirementsCheckAsync(final boolean allowAutoEnter) {
        if (mCheckInFlight) return;
        mCheckInFlight = true;
        if (mDashCheckSpinner != null) {
            mDashCheckSpinner.setVisibility(View.VISIBLE);
        }
        if (mDashVerdictBanner != null) {
            mDashVerdictBanner.setText("Verificando requerimientos del dispositivo...");
            mDashVerdictBanner.setTextColor(Color.parseColor("#E6EDF3"));
        }

        mBgExecutor.execute(() -> {
            final SystemRequirements.Report report = SystemRequirements.runCheck(MainActivity.this, mSettings);
            runOnUiThread(() -> {
                mCheckInFlight = false;
                mLastReport = report;
                if (mDashCheckSpinner != null) {
                    mDashCheckSpinner.setVisibility(View.GONE);
                }
                renderDashboardReport(report);
                updateChrootStatusBadges(report);
                if (allowAutoEnter && mSettings.isAutoEnterTerminal() && mService != null && mService.getSessions().isEmpty()) {
                    tryAutoEnterPreferredMode(report);
                }
            });
        });
    }

    private void tryAutoEnterPreferredMode(SystemRequirements.Report report) {
        String preferredPath = mSettings.getInstallMode();
        boolean useGui = TerminalSettings.UI_MODE_GUI.equals(mSettings.getLaunchUiMode());

        boolean rootReady = report.hasRoot && report.rootStatus.rootfsExists;
        boolean prootReady = report.prootBinariesReady && report.prootStatus.rootfsExists;
        Boolean chosenIsRoot = null;
        if (TerminalSettings.MODE_ROOT.equals(preferredPath) && rootReady) {
            chosenIsRoot = Boolean.TRUE;
        } else if (TerminalSettings.MODE_PROOT.equals(preferredPath) && prootReady) {
            chosenIsRoot = Boolean.FALSE;
        } else if (rootReady) {
            chosenIsRoot = Boolean.TRUE;
        } else if (prootReady) {
            chosenIsRoot = Boolean.FALSE;
        }
        if (chosenIsRoot == null) return;

        if (useGui) {
            DesktopManager.DesktopStatus ds = report.desktopStatus;
            boolean hasGui = chosenIsRoot
                ? (ds.rootDesktopInstalled && ds.rootVncServerInstalled)
                : (ds.prootDesktopInstalled && ds.prootVncServerInstalled);
            if (hasGui) {
                launchGraphicalDebian(chosenIsRoot);
                return;
            }
        }
        if (chosenIsRoot) {
            openNewRootDebianSession();
        } else {
            openNewProotDebianSession();
        }
    }

    private void renderDashboardReport(SystemRequirements.Report r) {
        if (mDashVerdictBanner == null || mDashReqsList == null) return;

        // 1. Verdict banner
        if (!r.meetsMinimumRequirements()) {
            mDashVerdictBanner.setText("✕ Requerimientos mínimos base no cumplidos");
            mDashVerdictBanner.setTextColor(Color.parseColor("#F85149"));
        } else if (!r.hasEnoughStorageForInstall() && !r.rootStatus.rootfsExists && !r.prootStatus.rootfsExists) {
            mDashVerdictBanner.setText("⚠ Espacio libre bajo (< 800 MB) para nueva instalación");
            mDashVerdictBanner.setTextColor(Color.parseColor("#D29922"));
        } else if (r.hasRoot) {
            mDashVerdictBanner.setText("✓ Requisitos cumplidos — Con Root y Sin Root disponibles");
            mDashVerdictBanner.setTextColor(Color.parseColor("#3FB950"));
        } else if (r.prootBinariesReady) {
            mDashVerdictBanner.setText("✓ Requisitos cumplidos — Recomendado Camino 2 (Sin Root / PRoot)");
            mDashVerdictBanner.setTextColor(Color.parseColor("#3FB950"));
        } else {
            mDashVerdictBanner.setText("⚠ Sin Root y sin binarios PRoot disponibles");
            mDashVerdictBanner.setTextColor(Color.parseColor("#D29922"));
        }

        // 2. Items list
        mDashReqsList.removeAllViews();
        for (int i = 0; i < r.items.size(); i++) {
            SystemRequirements.CheckItem item = r.items.get(i);
            mDashReqsList.addView(buildRequirementRowView(item));
            if (i + 1 < r.items.size()) {
                View sep = new View(this);
                sep.setBackgroundColor(Color.parseColor("#21262D"));
                mDashReqsList.addView(sep, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));
            }
        }

        if (mDashPowerActionsRow != null && mDashBatteryOptBtn != null && mDashNotifPermBtn != null) {
            boolean needBattery = !r.ignoringBatteryOptimizations;
            boolean needNotif = !r.notificationsEnabled;
            mDashBatteryOptBtn.setVisibility(needBattery ? View.VISIBLE : View.GONE);
            mDashNotifPermBtn.setVisibility(needNotif ? View.VISIBLE : View.GONE);
            mDashPowerActionsRow.setVisibility((needBattery || needNotif) ? View.VISIBLE : View.GONE);
        }
        if (mDashStoragePermBtn != null) {
            mDashStoragePermBtn.setVisibility(r.sharedStorageGranted ? View.GONE : View.VISIBLE);
        }
        if (mDashPhantomFixBtn != null) {
            boolean showPhantom = r.phantomStatus != null
                && r.phantomStatus.affectedApi
                && !r.phantomStatus.knownMitigated;
            mDashPhantomFixBtn.setVisibility(showPhantom ? View.VISIBLE : View.GONE);
        }

        // 3. Graphical Mode Card update (Termux:X11 detection & recommendation)
        DesktopManager.DesktopStatus ds = r.desktopStatus;
        if (mX11NoticeBox != null && mX11NoticeText != null && mX11DownloadBtn != null) {
            if (ds.termuxX11Installed) {
                mX11NoticeBox.setBackground(makeRoundedBg("#0D1117", "#238636", 6));
                mX11NoticeText.setText("✓ Termux:X11 v" + ds.termuxX11Version
                    + " detectado — Puedes elegir Visor VNC Integrado o Termux:X11 (60 FPS).");
                mX11NoticeText.setTextColor(Color.parseColor("#3FB950"));
                mX11DownloadBtn.setVisibility(View.GONE);
            } else {
                mX11NoticeBox.setBackground(makeRoundedBg("#0D1117", "#1F6FEB", 6));
                mX11NoticeText.setText("ℹ Termux:X11 no está instalado. Puedes usar el Visor VNC Integrado ahora mismo sin apps extra o descargar Termux:X11 APK para mayor fluidez (60 FPS).");
                mX11NoticeText.setTextColor(Color.parseColor("#79C0FF"));
                mX11DownloadBtn.setVisibility(View.VISIBLE);
            }
        }

        if (mGuiDesktopStatusTv != null) {
            String rootGui = !r.rootStatus.rootfsExists
                ? "sin Debian"
                : (ds.rootDesktopInstalled
                    ? (ds.rootDesktopName + (ds.rootVncServerInstalled ? " + VNC ✓" : " (falta VNC)"))
                    : "sin escritorio");
            String prootGui = !r.prootStatus.rootfsExists
                ? "sin Debian"
                : (ds.prootDesktopInstalled
                    ? (ds.prootDesktopName + (ds.prootVncServerInstalled ? " + VNC ✓" : " (falta VNC)"))
                    : "sin escritorio");
            mGuiDesktopStatusTv.setText("Escritorio -> Con Root: " + rootGui + " | Sin Root: " + prootGui);
        }

        if (mInstallDesktopBtn != null) {
            boolean anyDebian = (r.hasRoot && r.rootStatus.rootfsExists) || r.prootStatus.rootfsExists;
            mInstallDesktopBtn.setEnabled(anyDebian);
            mInstallDesktopBtn.setText(anyDebian
                ? "⬇ Instalar / Añadir Escritorio (XFCE4 / LXDE)"
                : "Instala primero Debian 12 en Camino 1 o Camino 2");
        }

        // 4. Path 1 Card: Con Root
        ChrootManager.ChrootStatus rs = r.rootStatus;
        if (rs.rootfsExists) {
            mRootCardBadge.setText("Instalado ✓");
            mRootCardBadge.setBackground(makePillBg("#238636"));
        } else if (r.hasRoot) {
            mRootCardBadge.setText("Recomendado");
            mRootCardBadge.setBackground(makePillBg("#1F6FEB"));
        } else {
            mRootCardBadge.setText("Requiere Root");
            mRootCardBadge.setBackground(makePillBg("#6E7681"));
        }
        mRootCardSummary.setText(formatRootCardSummary(r));

        // 5. Path 2 Card: Sin Root (PRoot)
        ProotManager.ProotStatus ps = r.prootStatus;
        if (ps.rootfsExists) {
            mProotCardBadge.setText("Instalado ✓");
            mProotCardBadge.setBackground(makePillBg("#238636"));
        } else if (!r.hasRoot && r.prootBinariesReady) {
            mProotCardBadge.setText("Recomendado");
            mProotCardBadge.setBackground(makePillBg("#1F6FEB"));
        } else if (r.prootBinariesReady) {
            mProotCardBadge.setText("Disponible");
            mProotCardBadge.setBackground(makePillBg("#30363D"));
        } else {
            mProotCardBadge.setText("No disponible");
            mProotCardBadge.setBackground(makePillBg("#B62324"));
        }
        mProotCardSummary.setText(formatProotCardSummary(r));

        updatePathButtonsForMode();

        if (mDashAutoEnterSwitch != null) {
            mDashAutoEnterSwitch.setChecked(mSettings.isAutoEnterTerminal());
        }
    }

    private void updatePathButtonsForMode() {
        if (mRootPrimaryBtn == null || mProotPrimaryBtn == null) return;
        SystemRequirements.Report r = mLastReport;
        boolean guiMode = TerminalSettings.UI_MODE_GUI.equals(mSettings.getLaunchUiMode());

        if (r.rootStatus.rootfsExists && r.hasRoot) {
            boolean hasActiveRoot = !guiMode && mService != null
                && mService.findRunningSession(DebianTermService.SessionKind.DEBIAN_ROOT) != null;
            mRootPrimaryBtn.setText(guiMode
                ? "🖥 Iniciar Escritorio (Root)"
                : (hasActiveRoot ? "↩ Volver a Debian 12 (Root)" : "▶ Entrar a Debian 12 (Root)"));
            mRootPrimaryBtn.setBackground(makePillBg("#D70A53"));
            mRootPrimaryBtn.setEnabled(true);
            mRootSecondaryBtn.setText("Gestionar");
        } else if (r.hasRoot) {
            mRootPrimaryBtn.setText("⬇ Instalar Debian 12 (Con Root)");
            mRootPrimaryBtn.setBackground(makePillBg("#D70A53"));
            mRootPrimaryBtn.setEnabled(r.meetsMinimumRequirements());
            mRootSecondaryBtn.setText("Rutas / Opciones");
        } else {
            mRootPrimaryBtn.setText("↻ Reintentar Root");
            mRootPrimaryBtn.setBackground(makePillBg("#30363D"));
            mRootPrimaryBtn.setEnabled(true);
            mRootSecondaryBtn.setText("Rutas / Info");
        }

        if (r.prootStatus.rootfsExists) {
            boolean hasActiveProot = !guiMode && mService != null
                && mService.findRunningSession(DebianTermService.SessionKind.DEBIAN_PROOT) != null;
            mProotPrimaryBtn.setText(guiMode
                ? "🖥 Iniciar Escritorio (Sin Root)"
                : (hasActiveProot ? "↩ Volver a Debian 12 (Sin Root)" : "▶ Entrar a Debian 12 (Sin Root)"));
            mProotPrimaryBtn.setBackground(makePillBg("#238636"));
            mProotPrimaryBtn.setEnabled(r.prootBinariesReady);
            mProotSecondaryBtn.setVisibility(View.VISIBLE);
            mProotSecondaryBtn.setText("Gestionar");
        } else if (r.prootBinariesReady) {
            mProotPrimaryBtn.setText("⬇ Instalar Debian 12 (Sin Root)");
            mProotPrimaryBtn.setBackground(makePillBg("#238636"));
            mProotPrimaryBtn.setEnabled(r.meetsMinimumRequirements());
            mProotSecondaryBtn.setVisibility(View.VISIBLE);
            mProotSecondaryBtn.setText("Opciones");
        } else {
            mProotPrimaryBtn.setText("PRoot no disponible");
            mProotPrimaryBtn.setBackground(makePillBg("#30363D"));
            mProotPrimaryBtn.setEnabled(false);
            mProotSecondaryBtn.setVisibility(View.GONE);
        }
    }

    private View buildRequirementRowView(SystemRequirements.CheckItem item) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.TOP);
        row.setPadding(0, dp(6), 0, dp(6));

        TextView icon = new TextView(this);
        String glyph;
        String colorHex;
        switch (item.status) {
            case PASS:
                glyph = "✓";
                colorHex = "#3FB950";
                break;
            case WARN:
                glyph = "⚠";
                colorHex = "#D29922";
                break;
            case FAIL:
                glyph = "✕";
                colorHex = "#F85149";
                break;
            default:
                glyph = "ℹ";
                colorHex = "#58A6FF";
                break;
        }
        icon.setText(glyph);
        icon.setTextColor(Color.parseColor(colorHex));
        icon.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        icon.setTypeface(Typeface.DEFAULT_BOLD);
        icon.setGravity(Gravity.CENTER_HORIZONTAL);
        icon.setMinWidth(dp(24));
        row.addView(icon);

        LinearLayout textCol = new LinearLayout(this);
        textCol.setOrientation(LinearLayout.VERTICAL);
        textCol.setPadding(dp(6), 0, 0, 0);

        LinearLayout topLine = new LinearLayout(this);
        topLine.setOrientation(LinearLayout.HORIZONTAL);
        TextView titleTv = new TextView(this);
        titleTv.setText(item.title);
        titleTv.setTextColor(Color.parseColor("#E6EDF3"));
        titleTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        titleTv.setTypeface(Typeface.DEFAULT_BOLD);
        topLine.addView(titleTv, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView valTv = new TextView(this);
        valTv.setText(item.value);
        valTv.setTextColor(Color.parseColor(colorHex));
        valTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        valTv.setTypeface(Typeface.MONOSPACE);
        valTv.setGravity(Gravity.END);
        topLine.addView(valTv);
        textCol.addView(topLine);

        TextView detTv = new TextView(this);
        detTv.setText(item.detail);
        detTv.setTextColor(Color.parseColor("#8B949E"));
        detTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        textCol.addView(detTv);

        row.addView(textCol, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return row;
    }

    private String formatRootCardSummary(SystemRequirements.Report r) {
        ChrootManager.ChrootStatus s = r.rootStatus;
        StringBuilder sb = new StringBuilder();
        sb.append("Root:     ").append(s.hasRoot ? "OK (" + s.suPath + ")" : "No detectado — usa Sin Root o concede Magisk/KSU").append('\n');
        sb.append("Ruta:     ").append(mSettings.getChrootPath()).append('\n');
        sb.append("Estado:   ").append(s.rootfsExists ? s.debianPrettyName : "No instalado");
        if (!s.rootfsSizeSummary.isEmpty()) {
            sb.append(" (").append(s.rootfsSizeSummary).append(")");
        }
        sb.append('\n');
        sb.append("Script:   ").append(s.entryScriptExists ? "OK (" + mSettings.getEntryScriptPath() + ")" : "Se creará al instalar").append('\n');
        sb.append("Montajes: ").append(s.activeMounts.isEmpty() ? "0 activos" : s.activeMounts.size() + " activos");
        return sb.toString();
    }

    private String formatProotCardSummary(SystemRequirements.Report r) {
        ProotManager.ProotStatus p = r.prootStatus;
        StringBuilder sb = new StringBuilder();
        sb.append("Motor:    ").append(p.binariesAvailable
            ? ("OK (" + (p.prootVersion.isEmpty() ? "PRoot" : p.prootVersion) + ")")
            : "Falta binario libproot.so").append('\n');
        sb.append("Ruta:     ").append(p.rootfsPath.isEmpty() ? ProotManager.getRootfsDir(this).getAbsolutePath() : p.rootfsPath).append('\n');
        sb.append("Estado:   ").append(p.rootfsExists ? p.debianPrettyName : "No instalado");
        if (!p.rootfsSizeSummary.isEmpty()) {
            sb.append(" (").append(p.rootfsSizeSummary).append(")");
        }
        sb.append('\n');
        sb.append("Seccomp:  ").append(mSettings.isProotDisableSeccomp() ? "Compatibilidad (PROOT_NO_SECCOMP=1)" : "Normal");
        return sb.toString();
    }

    private void updateChrootStatusBadges(SystemRequirements.Report r) {
        ChrootManager.ChrootStatus rootSt = r.rootStatus;
        ProotManager.ProotStatus prootSt = r.prootStatus;

        if (rootSt.rootfsExists && rootSt.hasRoot) {
            String shortName = rootSt.debianPrettyName.contains("13") || rootSt.debianPrettyName.contains("trixie")
                ? "13"
                : "12";
            mChrootPill.setText("⌂ " + shortName);
            mChrootPill.setBackground(makePillBg("#238636"));
        } else if (prootSt.rootfsExists) {
            mChrootPill.setText("⌂ PRoot");
            mChrootPill.setBackground(makePillBg("#1F6FEB"));
        } else if (!r.hasRoot) {
            mChrootPill.setText("⌂ Inicio");
            mChrootPill.setBackground(makePillBg("#9E6A03"));
        } else {
            mChrootPill.setText("⌂ Inicio");
            mChrootPill.setBackground(makePillBg("#D70A53"));
        }

        StringBuilder drawerSum = new StringBuilder();
        drawerSum.append("Root: ").append(rootSt.rootfsExists ? rootSt.debianPrettyName : (r.hasRoot ? "sin instalar" : "no disponible"));
        drawerSum.append(" | PRoot: ").append(prootSt.rootfsExists ? "Debian 12 listo" : "sin instalar");
        mDrawerChrootSummary.setText(drawerSum.toString());
    }

    // -------------------------------------------------------------------------
    // Dashboard Card Actions & Management Dialogs
    // -------------------------------------------------------------------------

    private void promptInstallDesktopTarget() {
        boolean canRoot = mLastReport.hasRoot && mLastReport.rootStatus.rootfsExists;
        boolean canProot = mLastReport.prootBinariesReady && mLastReport.prootStatus.rootfsExists;
        final String desktopId = mSettings.getDesktopEnv();
        final String deskName = DesktopManager.DESKTOP_LXDE.equals(desktopId) ? "LXDE" : "XFCE4";

        if (!canRoot && !canProot) {
            Toast.makeText(this, "Instala primero Debian 12 en Camino 1 o Camino 2.", Toast.LENGTH_LONG).show();
            return;
        }
        if (canRoot && canProot) {
            String[] opts = {
                "Instalar " + deskName + " en Camino 1 (Con Root)",
                "Instalar " + deskName + " en Camino 2 (Sin Root PRoot)"
            };
            new AlertDialog.Builder(this)
                .setTitle("¿Dónde instalar el escritorio " + deskName + "?")
                .setItems(opts, (d, idx) -> startDesktopInstallInTerminal(idx == 0, desktopId))
                .setNegativeButton("Cancelar", null)
                .show();
        } else {
            final boolean isRoot = canRoot;
            new AlertDialog.Builder(this)
                .setTitle("Instalar Escritorio " + deskName)
                .setMessage("Se instalarán " + deskName + " y TigerVNC dentro de Debian 12 ("
                    + (isRoot ? "Con Root" : "Sin Root PRoot") + ") usando apt-get (~350–600 MB).")
                .setPositiveButton("Instalar ahora", (d, w) -> startDesktopInstallInTerminal(isRoot, desktopId))
                .setNegativeButton("Cancelar", null)
                .show();
        }
    }

    private void onRootPrimaryClicked() {
        if (mLastReport.rootStatus.rootfsExists && mLastReport.hasRoot) {
            if (TerminalSettings.UI_MODE_GUI.equals(mSettings.getLaunchUiMode())) {
                launchGraphicalDebian(true);
            } else {
                enterOrResumeRootDebianSession();
            }
            return;
        }
        if (!mLastReport.hasRoot) {
            runRequirementsCheckAsync(false);
            Toast.makeText(this, "Comprobando permisos root (su)...", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!mLastReport.meetsMinimumRequirements()) {
            Toast.makeText(this, "El dispositivo no cumple los requisitos mínimos base.", Toast.LENGTH_LONG).show();
            return;
        }
        confirmStorageAndStartInstall(true, TerminalSettings.DEFAULT_DEBIAN_RELEASE,
            mSettings.getChrootPath(), mSettings.getEntryScriptPath());
    }

    private void onProotPrimaryClicked() {
        if (mLastReport.prootStatus.rootfsExists) {
            if (TerminalSettings.UI_MODE_GUI.equals(mSettings.getLaunchUiMode())) {
                launchGraphicalDebian(false);
            } else {
                enterOrResumeProotDebianSession();
            }
            return;
        }
        if (!mLastReport.prootBinariesReady) {
            Toast.makeText(this, "Motor PRoot no disponible en esta arquitectura.", Toast.LENGTH_LONG).show();
            return;
        }
        if (!mLastReport.meetsMinimumRequirements()) {
            Toast.makeText(this, "El dispositivo no cumple los requisitos mínimos base.", Toast.LENGTH_LONG).show();
            return;
        }
        confirmStorageAndStartInstall(false, TerminalSettings.DEFAULT_DEBIAN_RELEASE, null, null);
    }

    private void confirmStorageAndStartInstall(
        final boolean isRootMode,
        final String release,
        final String chrootPath,
        final String scriptPath
    ) {
        if (!mLastReport.hasEnoughStorageForInstall()) {
            new AlertDialog.Builder(this)
                .setTitle("Espacio libre reducido")
                .setMessage("Solo hay " + mLastReport.freeStorageMb + " MB libres y se recomiendan al menos "
                    + SystemRequirements.MIN_STORAGE_FOR_INSTALL_MB + " MB para descargar y extraer Debian 12.\n\n¿Deseas intentarlo igualmente?")
                .setPositiveButton("Continuar", (d, w) -> startInstallWithProgress(isRootMode, release, chrootPath, scriptPath))
                .setNegativeButton("Cancelar", null)
                .show();
            return;
        }
        startInstallWithProgress(isRootMode, release, chrootPath, scriptPath);
    }

    private void showRootManageDialog() {
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(18), dp(14), dp(18), dp(10));

        final TextView statusBox = new TextView(this);
        statusBox.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        statusBox.setTypeface(Typeface.MONOSPACE);
        statusBox.setPadding(dp(10), dp(8), dp(10), dp(8));
        statusBox.setBackgroundColor(Color.parseColor("#161B22"));
        statusBox.setTextColor(Color.parseColor("#C9D1D9"));
        statusBox.setText(formatRootCardSummary(mLastReport));
        content.addView(statusBox);

        TextView verLabel = new TextView(this);
        verLabel.setText("Versión Debian (para nuevas instalaciones):");
        verLabel.setPadding(0, dp(12), 0, dp(4));
        content.addView(verLabel);

        final RadioGroup releaseGroup = new RadioGroup(this);
        releaseGroup.setOrientation(LinearLayout.HORIZONTAL);
        final RadioButton rbBookworm = new RadioButton(this);
        rbBookworm.setText("Debian 12 (Recomendada)");
        rbBookworm.setId(View.generateViewId());
        final RadioButton rbTrixie = new RadioButton(this);
        rbTrixie.setText("Debian 13 (Testing)");
        rbTrixie.setId(View.generateViewId());
        releaseGroup.addView(rbBookworm);
        releaseGroup.addView(rbTrixie);
        if ("trixie".equals(mSettings.getDebianRelease())) {
            rbTrixie.setChecked(true);
        } else {
            rbBookworm.setChecked(true);
        }
        content.addView(releaseGroup);

        TextView chrootLbl = new TextView(this);
        chrootLbl.setText("Ruta del chroot:");
        chrootLbl.setPadding(0, dp(10), 0, dp(2));
        content.addView(chrootLbl);

        final EditText chrootEdit = new EditText(this);
        chrootEdit.setSingleLine(true);
        chrootEdit.setTypeface(Typeface.MONOSPACE);
        chrootEdit.setText(mSettings.getChrootPath());
        content.addView(chrootEdit);

        TextView scriptLbl = new TextView(this);
        scriptLbl.setText("Ruta del script de entrada:");
        scriptLbl.setPadding(0, dp(8), 0, dp(2));
        content.addView(scriptLbl);

        final EditText scriptEdit = new EditText(this);
        scriptEdit.setSingleLine(true);
        scriptEdit.setTypeface(Typeface.MONOSPACE);
        scriptEdit.setText(mSettings.getEntryScriptPath());
        content.addView(scriptEdit);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(content);

        AlertDialog dialog = new AlertDialog.Builder(this)
            .setTitle("Gestión Camino 1 (Con Root)")
            .setView(scroll)
            .setPositiveButton("Instalar / Reinstalar", null)
            .setNeutralButton("Más acciones", null)
            .setNegativeButton("Cerrar", null)
            .create();

        dialog.setOnShowListener(dlg -> {
            Button installBtn = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            Button actionsBtn = dialog.getButton(AlertDialog.BUTTON_NEUTRAL);

            installBtn.setOnClickListener(v -> {
                final String release = rbTrixie.isChecked() ? "trixie" : "bookworm";
                String chrootPath = chrootEdit.getText().toString().trim();
                String scriptPath = scriptEdit.getText().toString().trim();
                if (chrootPath.isEmpty()) chrootPath = TerminalSettings.DEFAULT_CHROOT_PATH;
                if (scriptPath.isEmpty()) scriptPath = TerminalSettings.DEFAULT_ENTRY_SCRIPT_PATH;

                mSettings.setDebianRelease(release);
                mSettings.setChrootPath(chrootPath);
                mSettings.setEntryScriptPath(scriptPath);

                final String fChroot = mSettings.getChrootPath();
                final String fScript = mSettings.getEntryScriptPath();

                installBtn.setEnabled(false);
                mBgExecutor.execute(() -> {
                    final ChrootManager.ChrootStatus targetStatus = ChrootManager.inspectStatus(fChroot, fScript);
                    runOnUiThread(() -> {
                        installBtn.setEnabled(true);
                        if (!targetStatus.hasRoot) {
                            Toast.makeText(MainActivity.this,
                                "No se detectó acceso root (su). Concede permiso o usa Camino 2 (Sin Root).",
                                Toast.LENGTH_LONG).show();
                            return;
                        }
                        if (targetStatus.rootfsExists) {
                            new AlertDialog.Builder(MainActivity.this)
                                .setTitle("Chroot existente detectado")
                                .setMessage("Ya existe una instalación en " + fChroot + " (" + targetStatus.debianPrettyName
                                    + ").\n\n¿Seguro que quieres descargar y extraer encima? Tus archivos en esa ruta podrían sobrescribirse.")
                                .setPositiveButton("Sí, reinstalar", (d2, w2) -> {
                                    dialog.dismiss();
                                    confirmStorageAndStartInstall(true, release, fChroot, fScript);
                                })
                                .setNegativeButton("Cancelar", null)
                                .show();
                        } else {
                            dialog.dismiss();
                            confirmStorageAndStartInstall(true, release, fChroot, fScript);
                        }
                    });
                });
            });

            actionsBtn.setOnClickListener(v -> {
                mSettings.setChrootPath(chrootEdit.getText().toString());
                mSettings.setEntryScriptPath(scriptEdit.getText().toString());
                final String fChroot = mSettings.getChrootPath();
                final String fScript = mSettings.getEntryScriptPath();

                String[] opts = {
                    "Guardar rutas y actualizar estado",
                    "Abrir sesión Debian (Root)",
                    "Abrir Shell Android (su)",
                    "Desmontar montajes del chroot",
                    "Regenerar script " + fScript + " (con backup)",
                    "Respaldar /root (Chroot) a .tar.gz"
                };
                new AlertDialog.Builder(MainActivity.this)
                    .setTitle("Acciones Chroot Root")
                    .setItems(opts, (d2, idx) -> {
                        if (idx == 0) {
                            runRequirementsCheckAsync(false);
                            mBgExecutor.execute(() -> {
                                ChrootManager.ChrootStatus fresh = ChrootManager.inspectStatus(fChroot, fScript);
                                runOnUiThread(() -> {
                                    mLastReport.rootStatus = fresh;
                                    statusBox.setText(formatRootCardSummary(mLastReport));
                                });
                            });
                        } else if (idx == 1) {
                            dialog.dismiss();
                            openNewRootDebianSession();
                        } else if (idx == 2) {
                            dialog.dismiss();
                            openNewAndroidRootSession();
                        } else if (idx == 3) {
                            mBgExecutor.execute(() -> {
                                ChrootManager.ExecResult res = ChrootManager.unmountChroot(fChroot, fScript);
                                ChrootManager.ChrootStatus fresh = ChrootManager.inspectStatus(fChroot, fScript);
                                runOnUiThread(() -> {
                                    mLastReport.rootStatus = fresh;
                                    renderDashboardReport(mLastReport);
                                    statusBox.setText(formatRootCardSummary(mLastReport));
                                    Toast.makeText(MainActivity.this,
                                        res.isOk() ? "Chroot desmontado" : "Aviso: " + res.stderr,
                                        Toast.LENGTH_SHORT).show();
                                });
                            });
                        } else if (idx == 4) {
                            mBgExecutor.execute(() -> {
                                ChrootManager.ExecResult res = ChrootManager.installEntryScript(fChroot, fScript, true);
                                ChrootManager.ChrootStatus fresh = ChrootManager.inspectStatus(fChroot, fScript);
                                runOnUiThread(() -> {
                                    mLastReport.rootStatus = fresh;
                                    renderDashboardReport(mLastReport);
                                    statusBox.setText(formatRootCardSummary(mLastReport));
                                    Toast.makeText(MainActivity.this,
                                        res.isOk() ? "Script actualizado (backup creado si existía)" : "Error: " + res.stderr,
                                        Toast.LENGTH_LONG).show();
                                });
                            });
                        } else if (idx == 5) {
                            dialog.dismiss();
                            runRootHomeBackupTask(fChroot);
                        }
                    })
                    .show();
            });
        });

        dialog.show();
    }

    private void showProotManageDialog() {
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(18), dp(14), dp(18), dp(10));

        final TextView statusBox = new TextView(this);
        statusBox.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        statusBox.setTypeface(Typeface.MONOSPACE);
        statusBox.setPadding(dp(10), dp(8), dp(10), dp(8));
        statusBox.setBackgroundColor(Color.parseColor("#161B22"));
        statusBox.setTextColor(Color.parseColor("#C9D1D9"));
        statusBox.setText(formatProotCardSummary(mLastReport));
        content.addView(statusBox);

        final Switch seccompSwitch = new Switch(this);
        seccompSwitch.setText("Modo compatibilidad seccomp (PROOT_NO_SECCOMP=1)");
        seccompSwitch.setPadding(0, dp(12), 0, dp(4));
        seccompSwitch.setChecked(mSettings.isProotDisableSeccomp());
        seccompSwitch.setOnCheckedChangeListener((btn, checked) -> {
            mSettings.setProotDisableSeccomp(checked);
            mBgExecutor.execute(() -> {
                try {
                    if (ProotManager.getRootfsDir(MainActivity.this).isDirectory()) {
                        ProotManager.installLauncherScript(MainActivity.this, checked);
                    }
                    runOnUiThread(() -> {
                        statusBox.setText(formatProotCardSummary(mLastReport));
                        renderDashboardReport(mLastReport);
                    });
                } catch (IOException ignored) {
                }
            });
        });
        content.addView(seccompSwitch);

        TextView seccompHint = new TextView(this);
        seccompHint.setText("Actívalo si PRoot falla con errores de ptrace/seccomp al abrir Debian 12 o ejecutar apt.");
        seccompHint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        seccompHint.setTextColor(Color.parseColor("#8B949E"));
        content.addView(seccompHint);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(content);

        AlertDialog.Builder builder = new AlertDialog.Builder(this)
            .setTitle("Gestión Camino 2 (Sin Root • PRoot)")
            .setView(scroll)
            .setPositiveButton("Instalar / Reinstalar Debian 12", null)
            .setNegativeButton("Cerrar", null);

        if (mLastReport.prootStatus.rootfsExists) {
            builder.setNeutralButton("Más acciones", null);
        }

        final AlertDialog dialog = builder.create();
        dialog.setOnShowListener(dlg -> {
            Button installBtn = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            installBtn.setOnClickListener(v -> {
                if (mLastReport.prootStatus.rootfsExists) {
                    new AlertDialog.Builder(MainActivity.this)
                        .setTitle("Reinstalar Debian 12 (Sin Root)")
                        .setMessage("Se eliminará el rootfs PRoot actual en almacenamiento interno y se descargará uno limpio.\n\n¿Continuar?")
                        .setPositiveButton("Sí, reinstalar", (d2, w2) -> {
                            dialog.dismiss();
                            confirmStorageAndStartInstall(false, TerminalSettings.DEFAULT_DEBIAN_RELEASE, null, null);
                        })
                        .setNegativeButton("Cancelar", null)
                        .show();
                } else {
                    dialog.dismiss();
                    confirmStorageAndStartInstall(false, TerminalSettings.DEFAULT_DEBIAN_RELEASE, null, null);
                }
            });

            Button neutralBtn = dialog.getButton(AlertDialog.BUTTON_NEUTRAL);
            if (neutralBtn != null) {
                neutralBtn.setOnClickListener(v -> {
                    String[] opts = {
                        "Abrir sesión Debian 12 (Sin Root)",
                        "Abrir /root en Gestor de Archivos (SAF)",
                        "Respaldo y Restauración (.tar.gz)",
                        "Configurar almacenamiento (setup-storage)",
                        "Regenerar script lanzador PRoot",
                        "Desinstalar Debian 12 PRoot"
                    };
                    new AlertDialog.Builder(MainActivity.this)
                        .setTitle("Acciones PRoot")
                        .setItems(opts, (d2, idx) -> {
                            if (idx == 0) {
                                dialog.dismiss();
                                openNewProotDebianSession();
                            } else if (idx == 1) {
                                dialog.dismiss();
                                openDocumentsProvider();
                            } else if (idx == 2) {
                                dialog.dismiss();
                                showBackupRestoreDialog();
                            } else if (idx == 3) {
                                dialog.dismiss();
                                runSetupStorageFlow();
                            } else if (idx == 4) {
                                mBgExecutor.execute(() -> {
                                    try {
                                        File rootfs = ProotManager.getRootfsDir(MainActivity.this);
                                        if (rootfs.isDirectory()) {
                                            ProotManager.configureRootfs(rootfs);
                                        }
                                        ProotManager.installLauncherScript(
                                            MainActivity.this, mSettings.isProotDisableSeccomp());
                                        runOnUiThread(() -> Toast.makeText(
                                            MainActivity.this, "Script lanzador PRoot regenerado", Toast.LENGTH_SHORT).show());
                                    } catch (IOException e) {
                                        runOnUiThread(() -> Toast.makeText(
                                            MainActivity.this, "Error: " + e.getMessage(), Toast.LENGTH_LONG).show());
                                    }
                                });
                            } else if (idx == 5) {
                                new AlertDialog.Builder(MainActivity.this)
                                    .setTitle("Desinstalar Debian 12 (Sin Root)")
                                    .setMessage("Se borrarán los archivos del entorno PRoot interno. No afecta ningún chroot con root.")
                                    .setPositiveButton("Desinstalar", (d3, w3) -> {
                                        dialog.dismiss();
                                        mBgExecutor.execute(() -> {
                                            ProotManager.uninstallRootfs(MainActivity.this);
                                            final SystemRequirements.Report fresh = SystemRequirements.runCheck(
                                                MainActivity.this, mSettings);
                                            runOnUiThread(() -> {
                                                mLastReport = fresh;
                                                renderDashboardReport(fresh);
                                                updateChrootStatusBadges(fresh);
                                                Toast.makeText(MainActivity.this,
                                                    "Entorno Debian 12 PRoot desinstalado.", Toast.LENGTH_SHORT).show();
                                            });
                                        });
                                    })
                                    .setNegativeButton("Cancelar", null)
                                    .show();
                            }
                        })
                        .show();
                });
            }
        });
        dialog.show();
    }

    private void startInstallWithProgress(
        final boolean isRootMode,
        final String release,
        final String chrootPath,
        final String scriptPath
    ) {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(18), dp(14), dp(18), dp(10));

        final TextView stageTv = new TextView(this);
        String modeLabel = isRootMode ? "Con Root (Chroot)" : "Sin Root (PRoot)";
        stageTv.setText("Iniciando instalación de Debian " + release + " (" + modeLabel + ")...");
        stageTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f);
        stageTv.setTypeface(Typeface.DEFAULT_BOLD);
        layout.addView(stageTv);

        final ProgressBar progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setMax(100);
        progressBar.setProgress(0);
        LinearLayout.LayoutParams pbLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        pbLp.setMargins(0, dp(10), 0, dp(10));
        layout.addView(progressBar, pbLp);

        final ScrollView logScroll = new ScrollView(this);
        final TextView logTv = new TextView(this);
        logTv.setTypeface(Typeface.MONOSPACE);
        logTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        logTv.setBackgroundColor(Color.parseColor("#0D1117"));
        logTv.setTextColor(Color.parseColor("#C9D1D9"));
        logTv.setPadding(dp(8), dp(8), dp(8), dp(8));
        logScroll.addView(logTv);
        layout.addView(logScroll, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(190)));

        final AtomicBoolean cancelled = new AtomicBoolean(false);

        final AlertDialog dlg = new AlertDialog.Builder(this)
            .setTitle("Instalando Debian " + release + " • " + modeLabel)
            .setView(layout)
            .setCancelable(false)
            .setNegativeButton("Cancelar", null)
            .create();

        dlg.setOnShowListener(d -> {
            final Button btn = dlg.getButton(AlertDialog.BUTTON_NEGATIVE);
            btn.setOnClickListener(v -> {
                cancelled.set(true);
                stageTv.setText("Cancelando...");
                btn.setEnabled(false);
            });
        });
        dlg.show();

        mBgExecutor.execute(() -> {
            ChrootInstaller.ProgressListener listener = new ChrootInstaller.ProgressListener() {
                @Override
                public void onProgress(int percent, String stage) {
                    runOnUiThread(() -> {
                        progressBar.setProgress(percent);
                        stageTv.setText(percent + "% — " + stage);
                    });
                }

                @Override
                public void onLog(String line) {
                    runOnUiThread(() -> {
                        logTv.append(line + "\n");
                        logScroll.post(() -> logScroll.fullScroll(View.FOCUS_DOWN));
                    });
                }
            };

            final ChrootInstaller.InstallResult res = isRootMode
                ? ChrootInstaller.installRoot(MainActivity.this, release, chrootPath, scriptPath, cancelled, listener)
                : ChrootInstaller.installProot(MainActivity.this, release, cancelled, listener);

            final SystemRequirements.Report fresh = SystemRequirements.runCheck(MainActivity.this, mSettings);
            runOnUiThread(() -> {
                mLastReport = fresh;
                renderDashboardReport(fresh);
                updateChrootStatusBadges(fresh);
                Button btn = dlg.getButton(AlertDialog.BUTTON_NEGATIVE);
                btn.setEnabled(true);
                if (res.success) {
                    stageTv.setText("100% — ¡Instalación completada!");
                    btn.setText("▶ Abrir Debian 12");
                    btn.setOnClickListener(v -> {
                        dlg.dismiss();
                        mSettings.setInstallMode(isRootMode ? TerminalSettings.MODE_ROOT : TerminalSettings.MODE_PROOT);
                        if (isRootMode) {
                            openNewRootDebianSession();
                        } else {
                            openNewProotDebianSession();
                        }
                    });
                } else {
                    stageTv.setText("Error / Cancelado: " + res.message);
                    btn.setText("Cerrar");
                    btn.setOnClickListener(v -> dlg.dismiss());
                }
            });
        });
    }

    // -------------------------------------------------------------------------
    // Quick Settings UI
    // -------------------------------------------------------------------------

    private void showSettingsDialog() {
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(18), dp(14), dp(18), dp(10));

        // 1. Soft keyboard pan toggle (fixes rewind bug)
        final Switch panSwitch = new Switch(this);
        panSwitch.setText("Evitar rebobinado al abrir teclado (modo Pan sin SIGWINCH)");
        panSwitch.setChecked(mSettings.isSoftKeyboardPanMainScreen());
        panSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            mSettings.setSoftKeyboardPanMainScreen(isChecked);
            mTerminalView.setSoftKeyboardPansMainScreen(isChecked);
            mTerminalView.updateSize();
        });
        content.addView(panSwitch);

        TextView panHint = new TextView(this);
        panHint.setText("Mantiene fijo el tamaño de filas del PTY al abrir/cerrar el teclado virtual y usa desplazamiento sincronizado.");
        panHint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        panHint.setTextColor(Color.parseColor("#8B949E"));
        panHint.setPadding(0, dp(2), 0, dp(12));
        content.addView(panHint);

        // 2. Font size controls
        TextView fontHeader = new TextView(this);
        fontHeader.setText("Tamaño de fuente:");
        fontHeader.setTypeface(Typeface.DEFAULT_BOLD);
        content.addView(fontHeader);

        LinearLayout fontRow = new LinearLayout(this);
        fontRow.setOrientation(LinearLayout.HORIZONTAL);
        fontRow.setGravity(Gravity.CENTER_VERTICAL);
        fontRow.setPadding(0, dp(4), 0, dp(12));

        final TextView fontValTv = new TextView(this);
        fontValTv.setText(mSettings.getFontSizePx() + " px");
        fontValTv.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        fontValTv.setGravity(Gravity.CENTER);
        fontValTv.setPadding(dp(16), 0, dp(16), 0);

        Button decBtn = new Button(this);
        decBtn.setText("−");
        decBtn.setOnClickListener(v -> {
            int next = TerminalSettings.clampFontSize(mSettings.getFontSizePx() - 2);
            mSettings.setFontSizePx(next);
            mTerminalView.setTextSize(next);
            fontValTv.setText(next + " px");
        });

        Button incBtn = new Button(this);
        incBtn.setText("+");
        incBtn.setOnClickListener(v -> {
            int next = TerminalSettings.clampFontSize(mSettings.getFontSizePx() + 2);
            mSettings.setFontSizePx(next);
            mTerminalView.setTextSize(next);
            fontValTv.setText(next + " px");
        });

        fontRow.addView(decBtn);
        fontRow.addView(fontValTv);
        fontRow.addView(incBtn);
        content.addView(fontRow);

        // 2b. Font family (Nerd Font / System / Custom .ttf)
        TextView typefaceHeader = new TextView(this);
        typefaceHeader.setText("Tipografía de terminal (Nerd Font):");
        typefaceHeader.setTypeface(Typeface.DEFAULT_BOLD);
        typefaceHeader.setPadding(0, 0, 0, dp(4));
        content.addView(typefaceHeader);

        RadioGroup fontGroup = new RadioGroup(this);
        String currentFont = mSettings.getFontId();
        for (int i = 0; i < TerminalSettings.ALL_FONT_IDS.length; i++) {
            final String fid = TerminalSettings.ALL_FONT_IDS[i];
            RadioButton rb = new RadioButton(this);
            rb.setText(TerminalSettings.ALL_FONT_NAMES[i]);
            rb.setId(View.generateViewId());
            fontGroup.addView(rb);
            if (fid.equals(currentFont)) {
                rb.setChecked(true);
            }
            rb.setOnClickListener(v -> {
                if (TerminalSettings.FONT_CUSTOM.equals(fid)
                    && !new File(getFilesDir(), CUSTOM_FONT_FILE_NAME).isFile()) {
                    Toast.makeText(this, "Selecciona un archivo .ttf o .otf para usar fuente personalizada.", Toast.LENGTH_SHORT).show();
                    launchCustomFontPicker();
                    return;
                }
                mSettings.setFontId(fid);
                applyTerminalFont();
            });
        }
        content.addView(fontGroup);

        final Button importFontBtn = makeActionButton("📂 Importar fuente (.ttf / .otf)", "#21262D", null);
        importFontBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        LinearLayout.LayoutParams fontImpLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        fontImpLp.setMargins(0, dp(4), 0, dp(12));
        content.addView(importFontBtn, fontImpLp);

        // 3. Color scheme selector
        TextView schemeHeader = new TextView(this);
        schemeHeader.setText("Esquema de colores:");
        schemeHeader.setTypeface(Typeface.DEFAULT_BOLD);
        schemeHeader.setPadding(0, 0, 0, dp(4));
        content.addView(schemeHeader);

        RadioGroup schemeGroup = new RadioGroup(this);
        String currentScheme = mSettings.getColorSchemeId();
        for (int i = 0; i < TerminalSettings.ALL_SCHEME_IDS.length; i++) {
            final String id = TerminalSettings.ALL_SCHEME_IDS[i];
            RadioButton rb = new RadioButton(this);
            rb.setText(TerminalSettings.ALL_SCHEME_NAMES[i]);
            rb.setId(View.generateViewId());
            schemeGroup.addView(rb);
            if (id.equals(currentScheme)) {
                rb.setChecked(true);
            }
            rb.setOnClickListener(v -> {
                mSettings.setColorSchemeId(id);
                applyColorScheme(id);
            });
        }
        content.addView(schemeGroup);

        Switch matYouSwitch = new Switch(this);
        boolean supportsMonet = Build.VERSION.SDK_INT >= 31;
        matYouSwitch.setText(supportsMonet
            ? "🎨 Tema dinámico Material You (Android 12+)"
            : "🎨 Tema dinámico Material You (Requiere Android 12+)");
        matYouSwitch.setPadding(0, dp(8), 0, dp(4));
        matYouSwitch.setEnabled(supportsMonet);
        matYouSwitch.setChecked(supportsMonet && mSettings.isMaterialYouEnabled());
        matYouSwitch.setOnCheckedChangeListener((btn, isChecked) -> {
            mSettings.setMaterialYouEnabled(isChecked);
            applyMaterialYouTheme();
        });
        content.addView(matYouSwitch);

        // 4. Extra keys bar mode
        TextView keysHeader = new TextView(this);
        keysHeader.setText("Barra de teclas extra:");
        keysHeader.setTypeface(Typeface.DEFAULT_BOLD);
        keysHeader.setPadding(0, dp(10), 0, dp(4));
        content.addView(keysHeader);

        RadioGroup keysGroup = new RadioGroup(this);
        String[] keyModeIds = {
            TerminalSettings.EXTRA_KEYS_FULL,
            TerminalSettings.EXTRA_KEYS_COMPACT,
            TerminalSettings.EXTRA_KEYS_HIDDEN
        };
        String[] keyModeNames = {
            "2 filas (Completa)",
            "1 fila (Compacta)",
            "Oculta"
        };
        String currentKeysMode = mSettings.getExtraKeysMode();
        for (int i = 0; i < keyModeIds.length; i++) {
            final String modeId = keyModeIds[i];
            RadioButton rb = new RadioButton(this);
            rb.setText(keyModeNames[i]);
            rb.setId(View.generateViewId());
            keysGroup.addView(rb);
            if (modeId.equals(currentKeysMode)) {
                rb.setChecked(true);
            }
            rb.setOnClickListener(v -> {
                mSettings.setExtraKeysMode(modeId);
                applyExtraKeysMode(modeId, true);
            });
        }
        content.addView(keysGroup);

        final Button editKeysBtn = makeActionButton("⌨ Editor visual y presets de teclas extra", "#21262D", null);
        editKeysBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        LinearLayout.LayoutParams editKeysLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        editKeysLp.setMargins(0, dp(6), 0, dp(4));
        content.addView(editKeysBtn, editKeysLp);

        // 5. Haptic feedback toggle
        Switch hapticSwitch = new Switch(this);
        hapticSwitch.setText("Vibración háptica en teclas extra");
        hapticSwitch.setPadding(0, dp(10), 0, 0);
        hapticSwitch.setChecked(mSettings.isHapticEnabled());
        hapticSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            mSettings.setHapticEnabled(isChecked);
            mExtraKeys.setHapticEnabled(isChecked);
        });
        content.addView(hapticSwitch);

        // 6. Segundo plano y Wake Lock (CPU + WiFi + Doze + Notificaciones)
        TextView wakeHeader = new TextView(this);
        wakeHeader.setText("Segundo plano y Wake Lock:");
        wakeHeader.setTypeface(Typeface.DEFAULT_BOLD);
        wakeHeader.setPadding(0, dp(14), 0, dp(4));
        content.addView(wakeHeader);

        Switch autoWakeSwitch = new Switch(this);
        autoWakeSwitch.setText("Activar Wake Lock (CPU + WiFi) al abrir sesión");
        autoWakeSwitch.setChecked(mSettings.isAutoWakeLockEnabled());
        autoWakeSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            mSettings.setAutoWakeLockEnabled(isChecked);
            if (isChecked && mService != null && !mService.getSessions().isEmpty()) {
                mService.setWakeLockEnabled(true);
                updateWakeLockUi();
            }
        });
        content.addView(autoWakeSwitch);

        final boolean dozeOk = DebianTermService.isIgnoringBatteryOptimizations(this);
        Button batBtn = makeActionButton(
            dozeOk ? "✓ Batería sin restricciones (Excluida de Doze)" : "🔋 Desactivar optimización de batería (Doze)",
            dozeOk ? "#238636" : "#9E6A03",
            v -> requestIgnoreBatteryOptimizations()
        );
        batBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        batBtn.setEnabled(!dozeOk);
        LinearLayout.LayoutParams batSettingsLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        batSettingsLp.setMargins(0, dp(8), 0, 0);
        content.addView(batBtn, batSettingsLp);

        if (!DebianTermService.areNotificationsEnabled(this)) {
            Button notifBtn = makeActionButton(
                "🔔 Activar notificaciones (Android 13+)",
                "#1F6FEB",
                v -> requestNotificationsPermissionIfNeeded(true)
            );
            notifBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            LinearLayout.LayoutParams notifSettingsLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            notifSettingsLp.setMargins(0, dp(6), 0, 0);
            content.addView(notifBtn, notifSettingsLp);
        }

        final boolean storageOk = ProotManager.hasSharedStorageAccess(this);
        Button storageBtn = makeActionButton(
            storageOk ? "✓ Almacenamiento (/sdcard) concedido — Reconfigurar ~/storage"
                      : "📁 Configurar almacenamiento (setup-storage)",
            storageOk ? "#238636" : "#1F6FEB",
            v -> runSetupStorageFlow()
        );
        storageBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        LinearLayout.LayoutParams storSettingsLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        storSettingsLp.setMargins(0, dp(6), 0, 0);
        content.addView(storageBtn, storSettingsLp);

        if (Build.VERSION.SDK_INT >= PhantomProcessManager.MIN_AFFECTED_API) {
            boolean phantomOk = mLastReport.phantomStatus != null && mLastReport.phantomStatus.knownMitigated;
            Button phantomBtn = makeActionButton(
                phantomOk ? "✓ Phantom Process Killer desactivado" : "🛡 Corregir Phantom Killer (signal 9)",
                phantomOk ? "#238636" : "#9E6A03",
                v -> showPhantomKillerDialog()
            );
            phantomBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            LinearLayout.LayoutParams phLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            phLp.setMargins(0, dp(6), 0, 0);
            content.addView(phantomBtn, phLp);
        }

        // 7. Archivos SAF & Respaldos
        TextView dataHeader = new TextView(this);
        dataHeader.setText("Archivos (SAF) y Respaldos:");
        dataHeader.setTypeface(Typeface.DEFAULT_BOLD);
        dataHeader.setPadding(0, dp(14), 0, dp(4));
        content.addView(dataHeader);

        Button safBtn = makeActionButton(
            "📂 Abrir /root en Gestor de Archivos (SAF)",
            "#1F6FEB",
            v -> openDocumentsProvider()
        );
        safBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        LinearLayout.LayoutParams safLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        safLp.setMargins(0, dp(4), 0, 0);
        content.addView(safBtn, safLp);

        final Button backupBtn = makeActionButton("💾 Respaldo y Restauración (.tar.gz)", "#238636", null);
        backupBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        LinearLayout.LayoutParams bakLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bakLp.setMargins(0, dp(6), 0, 0);
        content.addView(backupBtn, bakLp);

        // 8. Gráficos Sixel y Autoarranque
        TextView advHeader = new TextView(this);
        advHeader.setText("Gráficos Sixel y Autoarranque:");
        advHeader.setTypeface(Typeface.DEFAULT_BOLD);
        advHeader.setPadding(0, dp(14), 0, dp(4));
        content.addView(advHeader);

        Switch sixelSwitch = new Switch(this);
        sixelSwitch.setText("🖼 Gráficos Sixel inline (img2sixel, chafa, lsix)");
        sixelSwitch.setChecked(mSettings.isSixelEnabled());
        sixelSwitch.setOnCheckedChangeListener((btn, isChecked) -> {
            mSettings.setSixelEnabled(isChecked);
            applySixelSettingToAllSessions();
        });
        content.addView(sixelSwitch);

        final Button testSixelBtn = makeActionButton("🖼 Probar imagen Sixel en terminal", "#21262D", null);
        testSixelBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        LinearLayout.LayoutParams sxLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sxLp.setMargins(0, dp(6), 0, 0);
        content.addView(testSixelBtn, sxLp);

        final boolean bootActive = mSettings.isBootAutostartEnabled();
        final Button bootBtn = makeActionButton(
            bootActive ? "🚀 Autoarranque al iniciar: ACTIVO" : "🚀 Configurar autoarranque (BOOT_COMPLETED)",
            bootActive ? "#238636" : "#21262D",
            null
        );
        bootBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        LinearLayout.LayoutParams btLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        btLp.setMargins(0, dp(6), 0, 0);
        content.addView(bootBtn, btLp);

        final Button aboutBtn = makeActionButton("ℹ Acerca de DebianTerm (TeykerCol)", "#1F6FEB", null);
        aboutBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        LinearLayout.LayoutParams abLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        abLp.setMargins(0, dp(6), 0, 0);
        content.addView(aboutBtn, abLp);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(content);

        final AlertDialog settingsDlg = new AlertDialog.Builder(this)
            .setTitle("Ajustes rápidos")
            .setView(scroll)
            .setPositiveButton("Listo", null)
            .create();

        importFontBtn.setOnClickListener(v -> {
            settingsDlg.dismiss();
            launchCustomFontPicker();
        });
        editKeysBtn.setOnClickListener(v -> {
            settingsDlg.dismiss();
            showExtraKeysEditorDialog();
        });
        backupBtn.setOnClickListener(v -> {
            settingsDlg.dismiss();
            showBackupRestoreDialog();
        });
        testSixelBtn.setOnClickListener(v -> {
            settingsDlg.dismiss();
            testDemoSixelInTerminal();
        });
        bootBtn.setOnClickListener(v -> {
            settingsDlg.dismiss();
            showBootAutostartDialog();
        });
        aboutBtn.setOnClickListener(v -> {
            settingsDlg.dismiss();
            showAboutDialog();
        });

        settingsDlg.show();
    }

    private void applyColorScheme(String schemeId) {
        Properties props = TerminalSettings.getColorProperties(schemeId);
        TerminalColors.COLOR_SCHEME.updateWith(props);
        if (mService != null) {
            for (TerminalSession s : mService.getSessions()) {
                TerminalEmulator em = s.getEmulator();
                if (em != null) {
                    em.mColors.reset();
                }
            }
        }
        int bgColor = TerminalSettings.getBackgroundColorInt(schemeId);
        mTerminalView.setBackgroundColor(bgColor);
        mTerminalView.invalidate();
        mExtraKeys.applyTheme(TerminalSettings.isLightScheme(schemeId));
        applyMaterialYouTheme();
    }

    private void applyExtraKeysMode(String mode, boolean notifyGeometry) {
        mExtraKeys.setMode(mode);
        boolean hidden = TerminalSettings.EXTRA_KEYS_HIDDEN.equals(mode);
        if (mBottomExtraRow != null) {
            mBottomExtraRow.setVisibility((!hidden && !mTextInputMode) ? View.VISIBLE : View.GONE);
        }
        if (mTextInputBar != null) {
            mTextInputBar.setVisibility((!hidden && mTextInputMode) ? View.VISIBLE : View.GONE);
        }
        if (notifyGeometry && mTerminalView != null) {
            mTerminalView.onToolbarGeometryChanged();
            mTerminalView.post(() -> mTerminalView.updateSize());
        }
    }

    // -------------------------------------------------------------------------
    // Phase 1 UX: Terminal Search, Gboard Text Input, Snippets & Phantom Killer
    // -------------------------------------------------------------------------

    private LinearLayout buildSearchBarView() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(Color.parseColor("#161B22"));
        bar.setPadding(dp(6), dp(4), dp(6), dp(4));

        mSearchInput = new EditText(this);
        mSearchInput.setHint("Buscar en terminal...");
        mSearchInput.setHintTextColor(Color.parseColor("#8B949E"));
        mSearchInput.setTextColor(Color.parseColor("#E6EDF3"));
        mSearchInput.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        mSearchInput.setSingleLine(true);
        mSearchInput.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        mSearchInput.setBackground(makeRoundedBg("#0D1117", "#30363D", 6));
        mSearchInput.setPadding(dp(8), dp(5), dp(8), dp(5));
        mSearchInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) {
                performTerminalSearch(false);
            }
        });
        mSearchInput.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH
                || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER && event.getAction() == KeyEvent.ACTION_DOWN)) {
                navigateSearchMatch(-1);
                return true;
            }
            return false;
        });
        bar.addView(mSearchInput, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        mSearchCountTv = new TextView(this);
        mSearchCountTv.setText("0/0");
        mSearchCountTv.setTextColor(Color.parseColor("#8B949E"));
        mSearchCountTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        mSearchCountTv.setTypeface(Typeface.MONOSPACE);
        mSearchCountTv.setPadding(dp(6), 0, dp(4), 0);
        bar.addView(mSearchCountTv);

        mSearchCaseBtn = makeSearchBarBtn("Aa", "#21262D", v -> {
            mSearchCaseSensitive = !mSearchCaseSensitive;
            mSearchCaseBtn.setBackground(makePillBg(mSearchCaseSensitive ? "#1F6FEB" : "#21262D"));
            performTerminalSearch(false);
        });
        bar.addView(mSearchCaseBtn);
        bar.addView(makeSearchBarBtn("▲", "#21262D", v -> navigateSearchMatch(-1)));
        bar.addView(makeSearchBarBtn("▼", "#21262D", v -> navigateSearchMatch(1)));
        bar.addView(makeSearchBarBtn("📋", "#21262D", v -> copyCurrentSearchLine()));
        bar.addView(makeSearchBarBtn("✕", "#30363D", v -> hideSearchBar()));
        return bar;
    }

    private TextView makeSearchBarBtn(String label, String bgHex, View.OnClickListener listener) {
        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setTextColor(Color.WHITE);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(dp(7), dp(5), dp(7), dp(5));
        tv.setBackground(makePillBg(bgHex));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(dp(2), 0, dp(2), 0);
        tv.setLayoutParams(lp);
        tv.setOnClickListener(listener);
        return tv;
    }

    private boolean isSearchBarVisible() {
        return mSearchBar != null && mSearchBar.getVisibility() == View.VISIBLE;
    }

    private void toggleSearchBar() {
        if (isSearchBarVisible()) {
            hideSearchBar();
        } else {
            showSearchBar();
        }
    }

    private void showSearchBar() {
        if (mSearchBar == null) return;
        mSearchBar.setVisibility(View.VISIBLE);
        if (mTerminalView != null) {
            mTerminalView.onToolbarGeometryChanged();
            mTerminalView.post(() -> mTerminalView.updateSize());
        }
        if (mSearchInput != null) {
            mSearchInput.requestFocus();
            mSearchInput.selectAll();
            InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) {
                imm.showSoftInput(mSearchInput, 0);
            }
            performTerminalSearch(true);
        }
    }

    private void hideSearchBar() {
        if (mSearchBar != null) {
            mSearchBar.setVisibility(View.GONE);
        }
        mSearchMatches = Collections.emptyList();
        mSearchMatchIndex = -1;
        if (mSearchCountTv != null) {
            mSearchCountTv.setText("0/0");
        }
        if (mTerminalView != null) {
            mTerminalView.clearSearchHighlight();
            mTerminalView.onToolbarGeometryChanged();
            mTerminalView.post(() -> mTerminalView.updateSize());
            mTerminalView.requestFocus();
        }
    }

    private void performTerminalSearch(boolean jumpToLatest) {
        if (mSearchInput == null || mTerminalView == null) return;
        String query = mSearchInput.getText() != null ? mSearchInput.getText().toString() : "";
        TerminalEmulator em = (mCurrentSession != null) ? mCurrentSession.getEmulator() : null;
        if (query.isEmpty() || em == null) {
            mSearchMatches = Collections.emptyList();
            mSearchMatchIndex = -1;
            if (mSearchCountTv != null) mSearchCountTv.setText("0/0");
            mTerminalView.clearSearchHighlight();
            return;
        }
        mSearchMatches = TerminalSearch.search(em, query, mSearchCaseSensitive);
        if (mSearchMatches.isEmpty()) {
            mSearchMatchIndex = -1;
            if (mSearchCountTv != null) mSearchCountTv.setText("0/0");
            mTerminalView.clearSearchHighlight();
            return;
        }
        mSearchMatchIndex = jumpToLatest || mSearchMatchIndex < 0 || mSearchMatchIndex >= mSearchMatches.size()
            ? (mSearchMatches.size() - 1)
            : mSearchMatchIndex;
        applyCurrentSearchMatch();
    }

    private void navigateSearchMatch(int delta) {
        if (mSearchMatches.isEmpty()) {
            performTerminalSearch(true);
            return;
        }
        int total = mSearchMatches.size();
        mSearchMatchIndex = (mSearchMatchIndex + delta + total) % total;
        applyCurrentSearchMatch();
    }

    private void applyCurrentSearchMatch() {
        if (mSearchMatches.isEmpty() || mSearchMatchIndex < 0 || mSearchMatchIndex >= mSearchMatches.size()) return;
        TerminalSearch.Match m = mSearchMatches.get(mSearchMatchIndex);
        if (mSearchCountTv != null) {
            mSearchCountTv.setText((mSearchMatchIndex + 1) + "/" + mSearchMatches.size());
        }
        if (mTerminalView != null) {
            mTerminalView.setSearchHighlight(m.absoluteRow, m.startCol, m.endCol);
            mTerminalView.scrollToAbsoluteRow(m.absoluteRow);
        }
    }

    private void copyCurrentSearchLine() {
        if (mSearchMatches.isEmpty() || mSearchMatchIndex < 0 || mSearchMatchIndex >= mSearchMatches.size()) {
            Toast.makeText(this, "No hay coincidencia seleccionada.", Toast.LENGTH_SHORT).show();
            return;
        }
        TerminalSearch.Match m = mSearchMatches.get(mSearchMatchIndex);
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText("DebianTerm Search", m.lineText.trim()));
            Toast.makeText(this, "Línea copiada al portapapeles.", Toast.LENGTH_SHORT).show();
        }
    }

    private LinearLayout buildTextInputBarView() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(Color.parseColor("#161B22"));
        bar.setPadding(dp(4), dp(4), dp(4), dp(4));

        TextView backBtn = makeSearchBarBtn("⌨ Teclas", "#21262D", v -> setTextInputMode(false));
        bar.addView(backBtn);

        mTextInputEdit = new EditText(this);
        mTextInputEdit.setHint("Escribe o dicta comando (Gboard)...");
        mTextInputEdit.setHintTextColor(Color.parseColor("#8B949E"));
        mTextInputEdit.setTextColor(Color.parseColor("#E6EDF3"));
        mTextInputEdit.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f);
        mTextInputEdit.setSingleLine(true);
        mTextInputEdit.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_AUTO_CORRECT);
        mTextInputEdit.setImeOptions(EditorInfo.IME_ACTION_SEND);
        mTextInputEdit.setBackground(makeRoundedBg("#0D1117", "#30363D", 6));
        mTextInputEdit.setPadding(dp(8), dp(6), dp(8), dp(6));
        mTextInputEdit.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEND
                || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER && event.getAction() == KeyEvent.ACTION_DOWN)) {
                sendTextInputToSession(true);
                return true;
            }
            return false;
        });
        bar.addView(mTextInputEdit, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView sendBtn = makeSearchBarBtn("↵ Enviar", "#238636", v -> sendTextInputToSession(true));
        sendBtn.setOnLongClickListener(v -> {
            sendTextInputToSession(false);
            return true;
        });
        bar.addView(sendBtn);
        return bar;
    }

    private boolean isTextInputVisible() {
        return mTextInputMode && mTextInputBar != null && mTextInputBar.getVisibility() == View.VISIBLE;
    }

    private void setTextInputMode(boolean enabled) {
        mTextInputMode = enabled;
        boolean hidden = TerminalSettings.EXTRA_KEYS_HIDDEN.equals(mSettings.getExtraKeysMode());
        if (mBottomExtraRow != null) {
            mBottomExtraRow.setVisibility((!enabled && !hidden) ? View.VISIBLE : View.GONE);
        }
        if (mTextInputBar != null) {
            mTextInputBar.setVisibility(enabled ? View.VISIBLE : View.GONE);
        }
        if (mTerminalView != null) {
            mTerminalView.onToolbarGeometryChanged();
            mTerminalView.post(() -> mTerminalView.updateSize());
        }
        if (enabled && mTextInputEdit != null) {
            mTextInputEdit.requestFocus();
            InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) {
                imm.showSoftInput(mTextInputEdit, 0);
            }
        } else if (mTerminalView != null) {
            mTerminalView.requestFocus();
        }
    }

    private void sendTextInputToSession(boolean executeEnter) {
        if (mCurrentSession == null || mTextInputEdit == null) return;
        String text = mTextInputEdit.getText() != null ? mTextInputEdit.getText().toString() : "";
        if (text.isEmpty() && !executeEnter) return;
        if (!text.isEmpty()) {
            mCurrentSession.write(text);
        }
        if (executeEnter) {
            mCurrentSession.write("\r");
        }
        mTextInputEdit.setText("");
    }

    private void showSnippetsDialog() {
        final List<SnippetManager.Snippet> snippets = new ArrayList<>(mSettings.getSnippets());

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(12), dp(16), dp(8));

        TextView hint = new TextView(this);
        hint.setText("Toca un comando para enviarlo a la terminal activa:");
        hint.setTextColor(Color.parseColor("#8B949E"));
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        hint.setPadding(0, 0, 0, dp(8));
        root.addView(hint);

        LinearLayout listContainer = new LinearLayout(this);
        listContainer.setOrientation(LinearLayout.VERTICAL);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(listContainer);
        root.addView(scroll, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(290)));

        final AlertDialog dlg = new AlertDialog.Builder(this)
            .setTitle("⚡ Snippets / Comandos rápidos")
            .setView(root)
            .setPositiveButton("+ Nuevo snippet", null)
            .setNeutralButton("Restaurar", null)
            .setNegativeButton("Cerrar", null)
            .create();

        final Runnable rebuildRows = new Runnable() {
            @Override
            public void run() {
                listContainer.removeAllViews();
                if (snippets.isEmpty()) {
                    TextView emptyTv = new TextView(MainActivity.this);
                    emptyTv.setText("No hay snippets guardados. Pulsa '+ Nuevo snippet'.");
                    emptyTv.setTextColor(Color.parseColor("#8B949E"));
                    emptyTv.setPadding(dp(4), dp(12), dp(4), dp(12));
                    listContainer.addView(emptyTv);
                    return;
                }
                for (int i = 0; i < snippets.size(); i++) {
                    final int idx = i;
                    final SnippetManager.Snippet sn = snippets.get(i);

                    LinearLayout row = new LinearLayout(MainActivity.this);
                    row.setOrientation(LinearLayout.HORIZONTAL);
                    row.setGravity(Gravity.CENTER_VERTICAL);
                    row.setBackground(makeRoundedBg("#161B22", "#30363D", 6));
                    row.setPadding(dp(10), dp(8), dp(6), dp(8));
                    LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                    rlp.setMargins(0, 0, 0, dp(6));
                    row.setLayoutParams(rlp);

                    LinearLayout textCol = new LinearLayout(MainActivity.this);
                    textCol.setOrientation(LinearLayout.VERTICAL);
                    TextView titleTv = new TextView(MainActivity.this);
                    titleTv.setText(sn.title + (sn.autoExecute ? " ↵" : ""));
                    titleTv.setTextColor(Color.parseColor("#E6EDF3"));
                    titleTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f);
                    titleTv.setTypeface(Typeface.DEFAULT_BOLD);
                    textCol.addView(titleTv);

                    TextView cmdTv = new TextView(MainActivity.this);
                    cmdTv.setText(sn.command);
                    cmdTv.setTextColor(Color.parseColor("#8B949E"));
                    cmdTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
                    cmdTv.setTypeface(Typeface.MONOSPACE);
                    cmdTv.setSingleLine(true);
                    cmdTv.setEllipsize(TextUtils.TruncateAt.END);
                    textCol.addView(cmdTv);

                    row.addView(textCol, new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

                    TextView editBtn = makeSearchBarBtn("✎", "#21262D", v -> {
                        dlg.dismiss();
                        showEditSnippetDialog(snippets, idx);
                    });
                    row.addView(editBtn);

                    TextView delBtn = makeSearchBarBtn("✕", "#30363D", v -> {
                        snippets.remove(idx);
                        mSettings.saveSnippets(snippets);
                        this.run();
                    });
                    row.addView(delBtn);

                    row.setOnClickListener(v -> {
                        dlg.dismiss();
                        if (mCurrentSession == null) {
                            Toast.makeText(MainActivity.this,
                                "Abre una terminal primero para ejecutar el snippet.", Toast.LENGTH_SHORT).show();
                            return;
                        }
                        showTerminalView();
                        mCurrentSession.write(sn.command + (sn.autoExecute ? "\r" : ""));
                        if (mTerminalView != null) {
                            mTerminalView.requestFocus();
                        }
                    });

                    listContainer.addView(row);
                }
            }
        };

        rebuildRows.run();

        dlg.setOnShowListener(d -> {
            dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                dlg.dismiss();
                showEditSnippetDialog(snippets, -1);
            });
            dlg.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
                snippets.clear();
                snippets.addAll(SnippetManager.getDefaultSnippets());
                mSettings.saveSnippets(snippets);
                rebuildRows.run();
                Toast.makeText(MainActivity.this, "Snippets restaurados.", Toast.LENGTH_SHORT).show();
            });
        });

        dlg.show();
    }

    private void showEditSnippetDialog(final List<SnippetManager.Snippet> allSnippets, final int editIndex) {
        final boolean isEdit = editIndex >= 0 && editIndex < allSnippets.size();
        final SnippetManager.Snippet existing = isEdit ? allSnippets.get(editIndex) : null;

        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(18), dp(14), dp(18), dp(8));

        final EditText titleEt = new EditText(this);
        titleEt.setHint("Nombre del snippet (ej. Reiniciar nginx)");
        titleEt.setSingleLine(true);
        if (existing != null) titleEt.setText(existing.title);
        form.addView(titleEt);

        final EditText cmdEt = new EditText(this);
        cmdEt.setHint("Comando shell (ej. apt update)");
        cmdEt.setTypeface(Typeface.MONOSPACE);
        if (existing != null) cmdEt.setText(existing.command);
        form.addView(cmdEt);

        final CheckBox autoExecCb = new CheckBox(this);
        autoExecCb.setText("Ejecutar automáticamente (enviar Enter)");
        autoExecCb.setChecked(existing == null || existing.autoExecute);
        form.addView(autoExecCb);

        new AlertDialog.Builder(this)
            .setTitle(isEdit ? "Editar snippet" : "Nuevo snippet")
            .setView(form)
            .setPositiveButton("Guardar", (d, w) -> {
                String t = titleEt.getText().toString().trim();
                String c = cmdEt.getText().toString();
                if (t.isEmpty() && c.trim().isEmpty()) return;
                if (t.isEmpty()) t = c.trim();
                SnippetManager.Snippet updated = new SnippetManager.Snippet(
                    existing != null ? existing.id : null, t, c, autoExecCb.isChecked());
                if (isEdit) {
                    allSnippets.set(editIndex, updated);
                } else {
                    allSnippets.add(updated);
                }
                mSettings.saveSnippets(allSnippets);
                showSnippetsDialog();
            })
            .setNegativeButton("Cancelar", (d, w) -> showSnippetsDialog())
            .show();
    }

    private void showPhantomKillerDialog() {
        PhantomProcessManager.Status st = mLastReport.phantomStatus;
        String current = st != null ? st.summary : "Estado desconocido";

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(18), dp(12), dp(18), dp(8));

        TextView info = new TextView(this);
        info.setText("En Android 12–15, el sistema limita los procesos hijos en segundo plano (por defecto 32) y los cierra abruptamente con 'signal 9'.\n\nEstado actual: " + current);
        info.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        content.addView(info);

        TextView adbLabel = new TextView(this);
        adbLabel.setText("\nComandos ADB (para equipos Sin Root):");
        adbLabel.setTypeface(Typeface.DEFAULT_BOLD);
        adbLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        content.addView(adbLabel);

        TextView adbBox = new TextView(this);
        adbBox.setText(PhantomProcessManager.getAdbFixCommands());
        adbBox.setTypeface(Typeface.MONOSPACE);
        adbBox.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        adbBox.setTextColor(Color.parseColor("#C9D1D9"));
        adbBox.setBackgroundColor(Color.parseColor("#0D1117"));
        adbBox.setPadding(dp(8), dp(8), dp(8), dp(8));
        content.addView(adbBox);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(content);

        AlertDialog.Builder b = new AlertDialog.Builder(this)
            .setTitle("🛡 Phantom Process Killer (Android 12+)")
            .setView(scroll)
            .setNeutralButton("Opciones desarrollador", (d, w) -> {
                Toast.makeText(this,
                    "Activa 'Desactivar restricciones de procesos secundarios' si aparece en Opciones de desarrollador.",
                    Toast.LENGTH_LONG).show();
                try {
                    startActivity(new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS));
                } catch (ActivityNotFoundException | SecurityException e) {
                    try {
                        startActivity(new Intent(Settings.ACTION_SETTINGS));
                    } catch (ActivityNotFoundException ignored) {
                    }
                }
            })
            .setNegativeButton("Cerrar", null);

        if (mLastReport.hasRoot) {
            b.setPositiveButton("Corregir con Root", (d, w) -> {
                mBgExecutor.execute(() -> {
                    ChrootManager.ExecResult res = PhantomProcessManager.applyRootFix();
                    final SystemRequirements.Report fresh = SystemRequirements.runCheck(MainActivity.this, mSettings);
                    runOnUiThread(() -> {
                        mLastReport = fresh;
                        renderDashboardReport(fresh);
                        boolean ok = res.stdout != null && res.stdout.contains("PHANTOM_FIX_OK");
                        Toast.makeText(MainActivity.this,
                            ok ? "✓ Phantom Process Killer desactivado / límite elevado con root."
                               : "Aviso aplicando fix: " + res.stderr,
                            Toast.LENGTH_LONG).show();
                    });
                });
            });
        } else {
            b.setPositiveButton("Copiar comandos ADB", (d, w) -> {
                ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                if (cm != null) {
                    cm.setPrimaryClip(ClipData.newPlainText("ADB Phantom Fix", PhantomProcessManager.getAdbFixCommands()));
                    Toast.makeText(MainActivity.this, "Comandos ADB copiados al portapapeles.", Toast.LENGTH_SHORT).show();
                }
            });
        }
        b.show();
    }

    // -------------------------------------------------------------------------
    // Phase 2: Fonts, Material You, Extra Keys Visual Editor, SAF & Backups
    // -------------------------------------------------------------------------

    private void applyTerminalFont() {
        if (mTerminalView == null || mSettings == null) return;
        String fontId = mSettings.getFontId();
        Typeface tf = Typeface.MONOSPACE;
        if (TerminalSettings.FONT_CUSTOM.equals(fontId)) {
            File custom = new File(getFilesDir(), CUSTOM_FONT_FILE_NAME);
            if (custom.isFile() && custom.length() > 0) {
                try {
                    Typeface loaded = Typeface.createFromFile(custom);
                    if (loaded != null) {
                        tf = loaded;
                    }
                } catch (Throwable ignored) {
                }
            }
        } else if (TerminalSettings.FONT_NERD_MESLO.equals(fontId)) {
            try {
                Typeface loaded = Typeface.createFromAsset(getAssets(), "fonts/MesloLGS_NF_Regular.ttf");
                if (loaded != null) {
                    tf = loaded;
                }
            } catch (Throwable ignored) {
            }
        }
        mTerminalView.setTypeface(tf);
        mTerminalView.invalidate();
    }

    private void launchCustomFontPicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        try {
            startActivityForResult(intent, REQ_PICK_FONT);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "No se encontró selector de archivos en Android.", Toast.LENGTH_LONG).show();
        }
    }

    private void launchBackupFilePicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        try {
            startActivityForResult(intent, REQ_PICK_BACKUP);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "No se encontró selector de archivos en Android.", Toast.LENGTH_LONG).show();
        }
    }

    private void handleCustomFontPicked(final Uri uri) {
        mBgExecutor.execute(() -> {
            final int maxBytes = 20 * 1024 * 1024;
            File tmp = new File(getFilesDir(), CUSTOM_FONT_FILE_NAME + ".tmp");
            File dst = new File(getFilesDir(), CUSTOM_FONT_FILE_NAME);
            try (InputStream is = getContentResolver().openInputStream(uri)) {
                if (is == null) {
                    throw new IOException("No se pudo abrir el archivo de fuente.");
                }
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                byte[] buf = new byte[16384];
                int total = 0;
                int n;
                while ((n = is.read(buf)) != -1) {
                    total += n;
                    if (total > maxBytes) {
                        throw new IOException("La fuente supera el límite máximo de 20 MB.");
                    }
                    baos.write(buf, 0, n);
                }
                byte[] bytes = baos.toByteArray();
                if (!isValidFontMagic(bytes)) {
                    throw new IOException("El archivo no es una fuente .ttf u .otf válida.");
                }
                try (FileOutputStream fos = new FileOutputStream(tmp)) {
                    fos.write(bytes);
                }
                if (dst.exists() && !dst.delete()) {
                    throw new IOException("No se pudo actualizar custom_font.ttf");
                }
                if (!tmp.renameTo(dst)) {
                    throw new IOException("No se pudo guardar custom_font.ttf");
                }
                Typeface.createFromFile(dst);
                runOnUiThread(() -> {
                    mSettings.setFontId(TerminalSettings.FONT_CUSTOM);
                    applyTerminalFont();
                    Toast.makeText(MainActivity.this, "✓ Fuente personalizada aplicada.", Toast.LENGTH_SHORT).show();
                });
            } catch (Throwable t) {
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
                runOnUiThread(() -> Toast.makeText(
                    MainActivity.this, "Error importando fuente: " + t.getMessage(), Toast.LENGTH_LONG).show());
            }
        });
    }

    private static boolean isValidFontMagic(byte[] b) {
        if (b == null || b.length < 4) return false;
        // TrueType 00 01 00 00 | 'OTTO' | 'true' | 'ttcf'
        return (b[0] == 0x00 && b[1] == 0x01 && b[2] == 0x00 && b[3] == 0x00)
            || (b[0] == 'O' && b[1] == 'T' && b[2] == 'T' && b[3] == 'O')
            || (b[0] == 't' && b[1] == 'r' && b[2] == 'u' && b[3] == 'e')
            || (b[0] == 't' && b[1] == 't' && b[2] == 'c' && b[3] == 'f');
    }

    private int resolveAndroidColor(String resName, int fallback) {
        try {
            int resId = getResources().getIdentifier(resName, "color", "android");
            if (resId != 0) {
                return getResources().getColor(resId, getTheme());
            }
        } catch (Throwable ignored) {
        }
        return fallback;
    }

    private void applyMaterialYouTheme() {
        boolean light = TerminalSettings.isLightScheme(mSettings.getColorSchemeId());
        boolean enabled = mSettings.isMaterialYouEnabled() && Build.VERSION.SDK_INT >= 31;

        int defaultDarkChrome = Color.parseColor("#161B22");
        int defaultAccent = Color.parseColor("#D70A53");
        int chromeColor = defaultDarkChrome;
        int accent = defaultAccent;
        int extraSurface = light ? Color.parseColor("#EEE8D5") : defaultDarkChrome;

        if (enabled) {
            chromeColor = resolveAndroidColor("system_neutral1_900", defaultDarkChrome);
            int pickedAccent = resolveAndroidColor("system_accent1_500",
                resolveAndroidColor("system_accent1_600", defaultAccent));
            if (Color.luminance(pickedAccent) > 0.45f) {
                pickedAccent = resolveAndroidColor("system_accent1_600", pickedAccent);
            }
            accent = pickedAccent;
            if (!light) {
                extraSurface = chromeColor;
            }
        }

        mActiveAccentColor = accent;
        if (mTopBar != null) mTopBar.setBackgroundColor(chromeColor);
        if (mSearchBar != null) mSearchBar.setBackgroundColor(chromeColor);
        if (mTextInputBar != null) mTextInputBar.setBackgroundColor(chromeColor);
        if (mBottomExtraRow != null) mBottomExtraRow.setBackgroundColor(extraSurface);
        if (mExtraKeys != null) {
            mExtraKeys.setBarSurfaceColor(extraSurface);
            mExtraKeys.setAccentColor(accent);
        }
        refreshDrawerSessions();
    }

    private void showExtraKeysEditorDialog() {
        String currentSpec = mSettings.getCustomExtraKeysSpec();
        String[] existingLines = currentSpec.split("\\r?\\n");
        String initRow1 = existingLines.length >= 1 ? existingLines[0].trim() : "";
        String initRow2 = existingLines.length >= 2 ? existingLines[1].trim() : "";

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(16), dp(12), dp(16), dp(8));

        TextView presetLbl = new TextView(this);
        presetLbl.setText("Presets rápidos:");
        presetLbl.setTypeface(Typeface.DEFAULT_BOLD);
        presetLbl.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        content.addView(presetLbl);

        LinearLayout presetRow = new LinearLayout(this);
        presetRow.setOrientation(LinearLayout.HORIZONTAL);
        presetRow.setPadding(0, dp(4), 0, dp(10));

        final EditText row1Et = new EditText(this);
        final EditText row2Et = new EditText(this);
        final TextView previewTv = new TextView(this);

        TextView pStd = makeSearchBarBtn("Estándar", "#21262D", v -> {
            String[] lines = ExtraKeysBarView.PRESET_STANDARD.split("\\r?\\n");
            row1Et.setText(lines[0]);
            row2Et.setText(lines.length > 1 ? lines[1] : "");
        });
        TextView pProg = makeSearchBarBtn("Programación", "#1F6FEB", v -> {
            String[] lines = ExtraKeysBarView.PRESET_PROGRAMMING.split("\\r?\\n");
            row1Et.setText(lines[0]);
            row2Et.setText(lines.length > 1 ? lines[1] : "");
        });
        TextView pVim = makeSearchBarBtn("Tmux / Vim", "#238636", v -> {
            String[] lines = ExtraKeysBarView.PRESET_TMUX_VIM.split("\\r?\\n");
            row1Et.setText(lines[0]);
            row2Et.setText(lines.length > 1 ? lines[1] : "");
        });
        presetRow.addView(pStd);
        presetRow.addView(pProg);
        presetRow.addView(pVim);
        content.addView(presetRow);

        TextView r1Lbl = new TextView(this);
        r1Lbl.setText("Fila 1 (separa teclas con coma o espacio; usa KEY:ALT para pulsación larga):");
        r1Lbl.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        r1Lbl.setTextColor(Color.parseColor("#8B949E"));
        content.addView(r1Lbl);

        row1Et.setText(initRow1);
        row1Et.setTypeface(Typeface.MONOSPACE);
        row1Et.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        row1Et.setSingleLine(true);
        content.addView(row1Et);

        TextView r2Lbl = new TextView(this);
        r2Lbl.setText("Fila 2 (opcional):");
        r2Lbl.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        r2Lbl.setTextColor(Color.parseColor("#8B949E"));
        r2Lbl.setPadding(0, dp(6), 0, 0);
        content.addView(r2Lbl);

        row2Et.setText(initRow2);
        row2Et.setTypeface(Typeface.MONOSPACE);
        row2Et.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        row2Et.setSingleLine(true);
        content.addView(row2Et);

        previewTv.setTypeface(Typeface.MONOSPACE);
        previewTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        previewTv.setTextColor(Color.parseColor("#3FB950"));
        previewTv.setPadding(0, dp(4), 0, dp(8));
        content.addView(previewTv);

        Runnable updatePreview = () -> {
            String combined = row1Et.getText().toString().trim() + "\n" + row2Et.getText().toString().trim();
            List<List<ExtraKeysBarView.KeySpec>> parsed = ExtraKeysBarView.parseCustomSpec(combined);
            StringBuilder sb = new StringBuilder("Vista previa: ");
            for (int r = 0; r < parsed.size(); r++) {
                if (r > 0) sb.append(" | ");
                sb.append("F").append(r + 1).append(": ");
                List<ExtraKeysBarView.KeySpec> keys = parsed.get(r);
                for (int c = 0; c < keys.size(); c++) {
                    if (c > 0) sb.append(' ');
                    ExtraKeysBarView.KeySpec k = keys.get(c);
                    sb.append(k.altLabel != null ? (k.label + "·" + k.altLabel) : k.label);
                }
            }
            previewTv.setText(sb.toString());
        };
        updatePreview.run();

        TextWatcher watcher = new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) {
                updatePreview.run();
            }
        };
        row1Et.addTextChangedListener(watcher);
        row2Et.addTextChangedListener(watcher);

        TextView chipLbl = new TextView(this);
        chipLbl.setText("Toca para añadir tecla a la fila activa:");
        chipLbl.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        chipLbl.setTypeface(Typeface.DEFAULT_BOLD);
        chipLbl.setPadding(0, dp(4), 0, dp(4));
        content.addView(chipLbl);

        final EditText[] activeTarget = { row1Et };
        row1Et.setOnFocusChangeListener((v, hasFocus) -> { if (hasFocus) activeTarget[0] = row1Et; });
        row2Et.setOnFocusChangeListener((v, hasFocus) -> { if (hasFocus) activeTarget[0] = row2Et; });

        String[] tokens = {
            "ESC:~", "/:|", "-:_", "TAB:BACKTAB", "CTRL", "ALT",
            "HOME", "END", "PGUP", "PGDN", "UP", "DOWN",
            "LEFT", "RIGHT", "{:[", "}:]", "(:)", "=:+",
            "$:;", "COLON", "ENTER", "BKSP"
        };
        for (int i = 0; i < tokens.length; i += 6) {
            LinearLayout chipRow = new LinearLayout(this);
            chipRow.setOrientation(LinearLayout.HORIZONTAL);
            chipRow.setPadding(0, dp(2), 0, dp(2));
            for (int j = i; j < Math.min(tokens.length, i + 6); j++) {
                final String tok = tokens[j];
                TextView chip = makeSearchBarBtn(tok, "#21262D", v -> {
                    EditText target = activeTarget[0];
                    String cur = target.getText().toString().trim();
                    target.setText(cur.isEmpty() ? tok : (cur + ", " + tok));
                    target.setSelection(target.getText().length());
                });
                chip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f);
                chipRow.addView(chip);
            }
            content.addView(chipRow);
        }

        ScrollView scroll = new ScrollView(this);
        scroll.addView(content);

        AlertDialog dlg = new AlertDialog.Builder(this)
            .setTitle("⌨ Editor visual de teclas extra")
            .setView(scroll)
            .setPositiveButton("Guardar y aplicar", (d, w) -> {
                String r1 = row1Et.getText().toString().trim();
                String r2 = row2Et.getText().toString().trim();
                String spec = r2.isEmpty() ? r1 : (r1.isEmpty() ? r2 : (r1 + "\n" + r2));
                if (spec.isEmpty()) spec = ExtraKeysBarView.PRESET_STANDARD;
                mSettings.setCustomExtraKeysSpec(spec);
                mSettings.setExtraKeysMode(TerminalSettings.EXTRA_KEYS_FULL);
                mExtraKeys.setCustomSpec(spec);
                applyExtraKeysMode(TerminalSettings.EXTRA_KEYS_FULL, true);
                Toast.makeText(MainActivity.this, "✓ Teclas extra actualizadas.", Toast.LENGTH_SHORT).show();
            })
            .setNeutralButton("Estándar", null)
            .setNegativeButton("Cancelar", null)
            .create();

        dlg.setOnShowListener(d -> dlg.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
            String[] lines = ExtraKeysBarView.PRESET_STANDARD.split("\\r?\\n");
            row1Et.setText(lines[0]);
            row2Et.setText(lines.length > 1 ? lines[1] : "");
        }));
        dlg.show();
    }

    private void openDocumentsProvider() {
        Uri rootUri = DocumentsContract.buildRootUri(
            DebianDocumentsProvider.AUTHORITY,
            DebianDocumentsProvider.ROOT_ID_HOME
        );
        try {
            Intent viewIntent = new Intent(Intent.ACTION_VIEW, rootUri);
            viewIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            startActivity(viewIntent);
            return;
        } catch (ActivityNotFoundException | SecurityException ignored) {
        }
        try {
            Intent browseIntent = new Intent("android.provider.action.BROWSE", rootUri);
            browseIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            startActivity(browseIntent);
            return;
        } catch (ActivityNotFoundException | SecurityException ignored) {
        }

        new AlertDialog.Builder(this)
            .setTitle("📂 Storage Access Framework (SAF)")
            .setMessage("El proveedor de archivos \"DebianTerm (Home)\" ("
                + DebianDocumentsProvider.AUTHORITY + ") ya está activo.\n\n"
                + "Puedes abrir, editar y guardar archivos de /root (Debian Sin Root) directamente sin copiar por /sdcard desde:\n"
                + "• Material Files (Menú lateral → Añadir almacenamiento → Proveedor externo)\n"
                + "• Acode / QuickEdit (Abrir archivo / Abrir carpeta → Menú lateral → DebianTerm (Home))\n"
                + "• La app Archivos de Android")
            .setPositiveButton("Entendido", null)
            .show();
    }

    private void showBackupRestoreDialog() {
        File prootRootfs = ProotManager.getRootfsDir(this);
        File prootHome = new File(prootRootfs, "root");
        final boolean hasProot = prootRootfs.isDirectory() && new File(prootRootfs, "bin/bash").exists();
        final boolean hasProotHome = prootHome.isDirectory();
        final boolean hasRootDebian = mLastReport.hasRoot && mLastReport.rootStatus.rootfsExists;

        File defaultDir = BackupManager.getDefaultBackupDir(this);
        final List<File> archives = BackupManager.listBackupArchives(this);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(16), dp(12), dp(16), dp(8));

        TextView pathTv = new TextView(this);
        pathTv.setText("Carpeta de respaldos:\n" + defaultDir.getAbsolutePath());
        pathTv.setTypeface(Typeface.MONOSPACE);
        pathTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        pathTv.setTextColor(Color.parseColor("#C9D1D9"));
        pathTv.setBackgroundColor(Color.parseColor("#0D1117"));
        pathTv.setPadding(dp(10), dp(8), dp(10), dp(8));
        content.addView(pathTv);

        final AlertDialog[] dlgHolder = new AlertDialog[1];

        Button bakHomeBtn = makeActionButton(
            "💾 Respaldar Home PRoot + Ajustes (.tar.gz)",
            hasProotHome ? "#238636" : "#30363D",
            v -> {
                if (dlgHolder[0] != null) dlgHolder[0].dismiss();
                runBackupTask(false);
            }
        );
        bakHomeBtn.setEnabled(hasProotHome);
        bakHomeBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        LinearLayout.LayoutParams b1Lp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        b1Lp.setMargins(0, dp(10), 0, 0);
        content.addView(bakHomeBtn, b1Lp);

        Button bakFullBtn = makeActionButton(
            "📦 Respaldar Debian 12 PRoot completo (.tar.gz)",
            hasProot ? "#1F6FEB" : "#30363D",
            v -> {
                if (dlgHolder[0] != null) dlgHolder[0].dismiss();
                runBackupTask(true);
            }
        );
        bakFullBtn.setEnabled(hasProot);
        bakFullBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        LinearLayout.LayoutParams b2Lp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        b2Lp.setMargins(0, dp(6), 0, 0);
        content.addView(bakFullBtn, b2Lp);

        if (hasRootDebian) {
            Button bakRootBtn = makeActionButton(
                "🔐 Respaldar /root (Chroot Root) a .tar.gz",
                "#D70A53",
                v -> {
                    if (dlgHolder[0] != null) dlgHolder[0].dismiss();
                    runRootHomeBackupTask(mSettings.getChrootPath());
                }
            );
            bakRootBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            LinearLayout.LayoutParams b3Lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            b3Lp.setMargins(0, dp(6), 0, 0);
            content.addView(bakRootBtn, b3Lp);
        }

        Button pickRestoreBtn = makeActionButton(
            "📂 Restaurar desde archivo (.tar.gz)...",
            "#21262D",
            v -> {
                if (dlgHolder[0] != null) dlgHolder[0].dismiss();
                launchBackupFilePicker();
            }
        );
        pickRestoreBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        LinearLayout.LayoutParams b4Lp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        b4Lp.setMargins(0, dp(6), 0, dp(10));
        content.addView(pickRestoreBtn, b4Lp);

        TextView listHdr = new TextView(this);
        listHdr.setText("Respaldos encontrados (" + archives.size() + "):");
        listHdr.setTypeface(Typeface.DEFAULT_BOLD);
        listHdr.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        listHdr.setPadding(0, dp(4), 0, dp(6));
        content.addView(listHdr);

        if (archives.isEmpty()) {
            TextView emptyTv = new TextView(this);
            emptyTv.setText("Aún no hay archivos .tar.gz guardados en las carpetas de respaldo.");
            emptyTv.setTextColor(Color.parseColor("#8B949E"));
            emptyTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            content.addView(emptyTv);
        } else {
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US);
            for (final File arch : archives) {
                LinearLayout row = new LinearLayout(this);
                row.setOrientation(LinearLayout.VERTICAL);
                row.setBackground(makeRoundedBg("#161B22", "#30363D", 6));
                row.setPadding(dp(10), dp(8), dp(10), dp(8));
                LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                rlp.setMargins(0, 0, 0, dp(6));
                row.setLayoutParams(rlp);

                TextView nameTv = new TextView(this);
                nameTv.setText(arch.getName());
                nameTv.setTextColor(Color.parseColor("#E6EDF3"));
                nameTv.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
                nameTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
                row.addView(nameTv);

                TextView metaTv = new TextView(this);
                metaTv.setText(BackupManager.formatByteSize(arch.length())
                    + " • " + sdf.format(new Date(arch.lastModified())));
                metaTv.setTextColor(Color.parseColor("#8B949E"));
                metaTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
                row.addView(metaTv);

                row.setOnClickListener(v -> {
                    String[] opts = {
                        "Restaurar en Home PRoot (/root)",
                        "Restaurar en Rootfs PRoot completo",
                        "Eliminar respaldo"
                    };
                    new AlertDialog.Builder(MainActivity.this)
                        .setTitle(arch.getName())
                        .setItems(opts, (d2, idx) -> {
                            if (idx == 0) {
                                if (dlgHolder[0] != null) dlgHolder[0].dismiss();
                                confirmAndRunRestore(arch, null, false);
                            } else if (idx == 1) {
                                if (dlgHolder[0] != null) dlgHolder[0].dismiss();
                                confirmAndRunRestore(arch, null, true);
                            } else if (idx == 2) {
                                new AlertDialog.Builder(MainActivity.this)
                                    .setTitle("Eliminar respaldo")
                                    .setMessage("¿Eliminar permanentemente " + arch.getName() + "?")
                                    .setPositiveButton("Eliminar", (d3, w3) -> {
                                        //noinspection ResultOfMethodCallIgnored
                                        arch.delete();
                                        if (dlgHolder[0] != null) dlgHolder[0].dismiss();
                                        showBackupRestoreDialog();
                                    })
                                    .setNegativeButton("Cancelar", null)
                                    .show();
                            }
                        })
                        .show();
                });
                content.addView(row);
            }
        }

        ScrollView scroll = new ScrollView(this);
        scroll.addView(content);

        dlgHolder[0] = new AlertDialog.Builder(this)
            .setTitle("💾 Respaldo y Restauración (.tar.gz)")
            .setView(scroll)
            .setNegativeButton("Cerrar", null)
            .create();
        dlgHolder[0].show();
    }

    private void showRestoreTargetChooser(final File archiveFile, final Uri uri) {
        String[] opts = {
            "Restaurar Home PRoot (/root) + Ajustes",
            "Restaurar Debian 12 PRoot completo"
        };
        new AlertDialog.Builder(this)
            .setTitle("¿Dónde restaurar este respaldo?")
            .setItems(opts, (d, idx) -> confirmAndRunRestore(archiveFile, uri, idx == 1))
            .setNegativeButton("Cancelar", null)
            .show();
    }

    private void confirmAndRunRestore(final File archiveFile, final Uri uri, final boolean fullRootfs) {
        String name = archiveFile != null ? archiveFile.getName() : "archivo seleccionado";
        String msg = fullRootfs
            ? ("Se extraerá '" + name + "' encima del rootfs PRoot completo (Sin Root).\n\nCierra primero sesiones PRoot activas para evitar archivos bloqueados.")
            : ("Se restaurará '" + name + "' dentro de /root (Home PRoot) y se detectarán ajustes incluidos.");
        new AlertDialog.Builder(this)
            .setTitle("Confirmar restauración")
            .setMessage(msg)
            .setPositiveButton("Restaurar ahora", (d, w) -> runRestoreTask(archiveFile, uri, fullRootfs))
            .setNegativeButton("Cancelar", null)
            .show();
    }

    private void runBackupTask(final boolean fullRootfs) {
        File rootfs = ProotManager.getRootfsDir(this);
        final File sourceDir = fullRootfs ? rootfs : new File(rootfs, "root");
        final File outDir = BackupManager.getDefaultBackupDir(this);
        final File outFile = new File(outDir, BackupManager.buildArchiveFilename(fullRootfs ? "proot-full" : "proot-home"));
        final byte[] metaBytes = mSettings.exportMetadataBytes();
        final java.util.Set<String> skipSet = fullRootfs
            ? BackupManager.SKIP_ROOTFS_TOP_DIRS
            : BackupManager.SKIP_HOME_TOP_DIRS;

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(18), dp(14), dp(18), dp(10));

        final TextView msgTv = new TextView(this);
        msgTv.setText("Creando respaldo en:\n" + outFile.getAbsolutePath());
        msgTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        layout.addView(msgTv);

        final ProgressBar pb = new ProgressBar(this);
        layout.addView(pb);

        final AtomicBoolean cancelled = new AtomicBoolean(false);
        final AlertDialog progDlg = new AlertDialog.Builder(this)
            .setTitle(fullRootfs ? "Respaldando PRoot completo..." : "Respaldando Home + Ajustes...")
            .setView(layout)
            .setCancelable(false)
            .setNegativeButton("Cancelar", null)
            .create();

        progDlg.setOnShowListener(d -> progDlg.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener(v -> {
            cancelled.set(true);
            msgTv.setText("Cancelando respaldo...");
            progDlg.getButton(AlertDialog.BUTTON_NEGATIVE).setEnabled(false);
        }));
        progDlg.show();

        mBgExecutor.execute(() -> {
            final BackupManager.BackupResult res = BackupManager.createTarGz(
                sourceDir,
                outFile,
                BackupManager.META_ENTRY_NAME,
                metaBytes,
                skipSet,
                cancelled,
                (entries, bytes, entry) -> runOnUiThread(() -> msgTv.setText(
                    "Entradas: " + entries + " (" + BackupManager.formatByteSize(bytes) + ")\n" + entry))
            );
            runOnUiThread(() -> {
                if (progDlg.isShowing()) progDlg.dismiss();
                if (res.success) {
                    new AlertDialog.Builder(MainActivity.this)
                        .setTitle("✓ Respaldo completado")
                        .setMessage("Archivo guardado:\n" + res.outputFile.getAbsolutePath()
                            + "\n\nEntradas: " + res.entriesCount
                            + "\nTamaño comprimido: " + BackupManager.formatByteSize(res.archiveBytes))
                        .setPositiveButton("Listo", null)
                        .show();
                } else {
                    Toast.makeText(MainActivity.this, res.message, Toast.LENGTH_LONG).show();
                }
            });
        });
    }

    private void runRootHomeBackupTask(final String chrootPath) {
        final File outDir = BackupManager.getDefaultBackupDir(this);
        final File outFile = new File(outDir, BackupManager.buildArchiveFilename("root-home"));
        Toast.makeText(this, "Generando respaldo de " + chrootPath + "/root...", Toast.LENGTH_SHORT).show();
        mBgExecutor.execute(() -> {
            final BackupManager.BackupResult res = BackupManager.backupRootHome(chrootPath, outFile);
            runOnUiThread(() -> {
                if (res.success) {
                    new AlertDialog.Builder(MainActivity.this)
                        .setTitle("✓ Respaldo Root /root listo")
                        .setMessage("Archivo guardado:\n" + res.outputFile.getAbsolutePath()
                            + "\nTamaño: " + BackupManager.formatByteSize(res.archiveBytes))
                        .setPositiveButton("Listo", null)
                        .show();
                } else {
                    Toast.makeText(MainActivity.this, res.message, Toast.LENGTH_LONG).show();
                }
            });
        });
    }

    private void runRestoreTask(final File archiveFile, final Uri uri, final boolean fullRootfs) {
        final File rootfs = ProotManager.getRootfsDir(this);
        final File targetDir = fullRootfs ? rootfs : new File(rootfs, "root");

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(18), dp(14), dp(18), dp(10));

        final TextView msgTv = new TextView(this);
        msgTv.setText("Extrayendo respaldo en " + targetDir.getAbsolutePath() + "...");
        msgTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        layout.addView(msgTv);

        ProgressBar pb = new ProgressBar(this);
        layout.addView(pb);

        final AtomicBoolean cancelled = new AtomicBoolean(false);
        final AlertDialog progDlg = new AlertDialog.Builder(this)
            .setTitle("Restaurando respaldo...")
            .setView(layout)
            .setCancelable(false)
            .setNegativeButton("Cancelar", null)
            .create();

        progDlg.setOnShowListener(d -> progDlg.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener(v -> {
            cancelled.set(true);
            msgTv.setText("Cancelando restauración...");
            progDlg.getButton(AlertDialog.BUTTON_NEGATIVE).setEnabled(false);
        }));
        progDlg.show();

        mBgExecutor.execute(() -> {
            BackupManager.RestoreResult res;
            if (archiveFile != null) {
                res = BackupManager.restoreTarGz(archiveFile, targetDir, cancelled,
                    (entries, bytes, entry) -> runOnUiThread(() ->
                        msgTv.setText("Entradas restauradas: " + entries
                            + " (" + BackupManager.formatByteSize(bytes) + ")")));
            } else {
                try (InputStream is = getContentResolver().openInputStream(uri)) {
                    res = BackupManager.restoreTarGz(is, targetDir, cancelled,
                        (entries, bytes, entry) -> runOnUiThread(() ->
                            msgTv.setText("Entradas restauradas: " + entries
                                + " (" + BackupManager.formatByteSize(bytes) + ")")));
                } catch (IOException e) {
                    res = new BackupManager.RestoreResult(false, 0L, null,
                        "Error abriendo URI: " + e.getMessage());
                }
            }

            if (res.success) {
                try {
                    if (fullRootfs && rootfs.isDirectory()) {
                        ProotManager.configureRootfs(rootfs);
                        ProotManager.installLauncherScript(MainActivity.this, mSettings.isProotDisableSeccomp());
                    } else if (rootfs.isDirectory() && ProotManager.hasSharedStorageAccess(MainActivity.this)) {
                        ProotManager.setupStorageLinks(rootfs);
                    }
                } catch (IOException ignored) {
                }
            }

            final SystemRequirements.Report fresh = SystemRequirements.runCheck(MainActivity.this, mSettings);
            final BackupManager.RestoreResult finalRes = res;

            runOnUiThread(() -> {
                if (progDlg.isShowing()) progDlg.dismiss();
                mLastReport = fresh;
                renderDashboardReport(fresh);
                updateChrootStatusBadges(fresh);
                if (!finalRes.success) {
                    Toast.makeText(MainActivity.this, finalRes.message, Toast.LENGTH_LONG).show();
                    return;
                }
                if (finalRes.metadataBytes != null && finalRes.metadataBytes.length > 0) {
                    new AlertDialog.Builder(MainActivity.this)
                        .setTitle("✓ Respaldo restaurado (" + finalRes.entriesCount + " entradas)")
                        .setMessage("Este respaldo contiene tu configuración previa de DebianTerm (tema, fuente, tamaño, teclas extra y snippets).\n\n¿Deseas restaurar también esos ajustes?")
                        .setPositiveButton("Aplicar ajustes", (d2, w2) -> {
                            if (mSettings.importMetadataBytes(finalRes.metadataBytes)) {
                                applyColorScheme(mSettings.getColorSchemeId());
                                applyTerminalFont();
                                mTerminalView.setTextSize(mSettings.getFontSizePx());
                                mExtraKeys.setCustomSpec(mSettings.getCustomExtraKeysSpec());
                                applyExtraKeysMode(mSettings.getExtraKeysMode(), true);
                                mExtraKeys.setHapticEnabled(mSettings.isHapticEnabled());
                                applySixelSettingToAllSessions();
                                Toast.makeText(MainActivity.this,
                                    "✓ Archivos y ajustes restaurados.", Toast.LENGTH_SHORT).show();
                            }
                        })
                        .setNegativeButton("Solo archivos", null)
                        .show();
                } else {
                    Toast.makeText(MainActivity.this,
                        "✓ Restauración completada (" + finalRes.entriesCount + " entradas).",
                        Toast.LENGTH_LONG).show();
                }
            });
        });
    }

    private void applySixelSettingToAllSessions() {
        boolean enabled = mSettings.isSixelEnabled();
        if (mService != null) {
            for (TerminalSession s : mService.getSessions()) {
                TerminalEmulator em = s.getEmulator();
                if (em != null) {
                    em.setSixelEnabled(enabled);
                }
            }
        }
    }

    private void testDemoSixelInTerminal() {
        if (mCurrentSession == null || mCurrentSession.getEmulator() == null) {
            Toast.makeText(this, "Abre una sesión de terminal primero para visualizar Sixel.", Toast.LENGTH_LONG).show();
            return;
        }
        if (!mSettings.isSixelEnabled()) {
            mSettings.setSixelEnabled(true);
            applySixelSettingToAllSessions();
        }
        TerminalEmulator em = mCurrentSession.getEmulator();
        em.setSixelEnabled(true);
        showTerminalView();
        String payload = "\r\n\033[1;35m[DebianTerm Sixel Demo 240x72]\033[0m\r\n"
            + SixelDecoder.generateDemoSixelSequence()
            + "\r\n";
        byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
        em.append(bytes, bytes.length);
        if (mTerminalView != null) {
            mTerminalView.onScreenUpdated();
            mTerminalView.requestFocus();
        }
    }

    private void showBootAutostartDialog() {
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(16), dp(12), dp(16), dp(8));

        final Switch enableSwitch = new Switch(this);
        enableSwitch.setText("Ejecutar script automáticamente al encender Android (BOOT_COMPLETED)");
        enableSwitch.setChecked(mSettings.isBootAutostartEnabled());
        content.addView(enableSwitch);

        TextView tgtHdr = new TextView(this);
        tgtHdr.setText("Entorno objetivo al arrancar:");
        tgtHdr.setTypeface(Typeface.DEFAULT_BOLD);
        tgtHdr.setPadding(0, dp(10), 0, dp(4));
        content.addView(tgtHdr);

        RadioGroup targetGroup = new RadioGroup(this);
        final RadioButton rbAuto = new RadioButton(this);
        rbAuto.setText("Automático (según entorno preferido instalado)");
        rbAuto.setId(View.generateViewId());
        final RadioButton rbRoot = new RadioButton(this);
        rbRoot.setText("Camino 1 • Con Root (Chroot)");
        rbRoot.setId(View.generateViewId());
        final RadioButton rbProot = new RadioButton(this);
        rbProot.setText("Camino 2 • Sin Root (PRoot)");
        rbProot.setId(View.generateViewId());
        targetGroup.addView(rbAuto);
        targetGroup.addView(rbRoot);
        targetGroup.addView(rbProot);

        String curTarget = mSettings.getBootAutostartTarget();
        if (TerminalSettings.BOOT_TARGET_ROOT.equals(curTarget)) {
            rbRoot.setChecked(true);
        } else if (TerminalSettings.BOOT_TARGET_PROOT.equals(curTarget)) {
            rbProot.setChecked(true);
        } else {
            rbAuto.setChecked(true);
        }
        content.addView(targetGroup);

        final Switch wakeSwitch = new Switch(this);
        wakeSwitch.setText("Activar Wake Lock (CPU + WiFi) en autoarranque");
        wakeSwitch.setPadding(0, dp(8), 0, dp(4));
        wakeSwitch.setChecked(mSettings.isBootAutostartWakeLock());
        content.addView(wakeSwitch);

        final Switch keepSwitch = new Switch(this);
        keepSwitch.setText("Mantener sesión abierta al finalizar (/bin/bash)");
        keepSwitch.setPadding(0, dp(4), 0, dp(8));
        keepSwitch.setChecked(mSettings.isBootAutostartKeepSession());
        content.addView(keepSwitch);

        TextView scriptHdr = new TextView(this);
        scriptHdr.setText("Script POSIX de arranque (/root/.debianterm/boot/*.sh también se ejecuta):");
        scriptHdr.setTypeface(Typeface.DEFAULT_BOLD);
        scriptHdr.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        content.addView(scriptHdr);

        final EditText scriptEt = new EditText(this);
        scriptEt.setText(mSettings.getBootAutostartScript());
        scriptEt.setTypeface(Typeface.MONOSPACE);
        scriptEt.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        scriptEt.setTextColor(Color.parseColor("#E6EDF3"));
        scriptEt.setBackground(makeRoundedBg("#0D1117", "#30363D", 6));
        scriptEt.setPadding(dp(10), dp(8), dp(10), dp(8));
        scriptEt.setMinLines(5);
        scriptEt.setMaxLines(10);
        scriptEt.setGravity(Gravity.TOP | Gravity.START);
        scriptEt.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        LinearLayout.LayoutParams etLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        etLp.setMargins(0, dp(6), 0, dp(8));
        content.addView(scriptEt, etLp);

        TextView hintTv = new TextView(this);
        String dozeNote = DebianTermService.isIgnoringBatteryOptimizations(this)
            ? "✓ Batería sin restricciones (listo para segundo plano)."
            : "⚠ Recomendado desactivar optimización de batería (Doze) para evitar cierres en segundo plano.";
        hintTv.setText("Logs en: ~/.debianterm/logs/boot-*.log\n" + dozeNote);
        hintTv.setTextColor(Color.parseColor("#8B949E"));
        hintTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        content.addView(hintTv);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(content);

        Runnable saveBootPrefs = () -> {
            mSettings.setBootAutostartEnabled(enableSwitch.isChecked());
            String chosenTarget = rbRoot.isChecked()
                ? TerminalSettings.BOOT_TARGET_ROOT
                : (rbProot.isChecked() ? TerminalSettings.BOOT_TARGET_PROOT : TerminalSettings.BOOT_TARGET_AUTO);
            mSettings.setBootAutostartTarget(chosenTarget);
            mSettings.setBootAutostartWakeLock(wakeSwitch.isChecked());
            mSettings.setBootAutostartKeepSession(keepSwitch.isChecked());
            mSettings.setBootAutostartScript(scriptEt.getText().toString());
        };

        AlertDialog dlg = new AlertDialog.Builder(this)
            .setTitle("🚀 Autoarranque al encender Android")
            .setView(scroll)
            .setPositiveButton("Guardar", (d, w) -> {
                saveBootPrefs.run();
                Toast.makeText(MainActivity.this,
                    mSettings.isBootAutostartEnabled()
                        ? "✓ Autoarranque activado y guardado."
                        : "✓ Configuración guardada (Autoarranque desactivado).",
                    Toast.LENGTH_SHORT).show();
            })
            .setNeutralButton("▶ Probar ahora", null)
            .setNegativeButton("Cerrar", null)
            .create();

        dlg.setOnShowListener(d -> dlg.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
            saveBootPrefs.run();
            dlg.dismiss();
            testBootAutostartNow();
        }));
        dlg.show();
    }

    private void testBootAutostartNow() {
        if (mService == null) {
            Toast.makeText(this, "Servicio no enlazado aún.", Toast.LENGTH_SHORT).show();
            return;
        }
        Toast.makeText(this, "Iniciando prueba de script de autoarranque...", Toast.LENGTH_SHORT).show();
        final String targetPref = mSettings.getBootAutostartTarget();
        final String preferredInstallMode = mSettings.getInstallMode();
        final String chrootPath = mSettings.getChrootPath();
        final String scriptPath = mSettings.getEntryScriptPath();

        mBgExecutor.execute(() -> {
            boolean hasRoot = false;
            boolean rootInstalled = false;
            if (!TerminalSettings.BOOT_TARGET_PROOT.equals(targetPref)) {
                ChrootManager.ChrootStatus rootSt = ChrootManager.inspectStatus(chrootPath, scriptPath);
                hasRoot = rootSt.hasRoot;
                rootInstalled = rootSt.rootfsExists;
            }
            File prootDir = ProotManager.getRootfsDir(MainActivity.this);
            boolean prootInstalled = prootDir.isDirectory() && new File(prootDir, "bin/bash").exists();

            final Boolean useRoot = BootReceiver.resolveBootMode(
                targetPref, preferredInstallMode, hasRoot, rootInstalled, prootInstalled);
            runOnUiThread(() -> {
                if (useRoot == null) {
                    Toast.makeText(MainActivity.this,
                        "No hay entorno Debian 12 listo para el modo seleccionado (" + targetPref + ").",
                        Toast.LENGTH_LONG).show();
                    return;
                }
                String bootCmd = BootReceiver.buildBootCommand(
                    mSettings.getBootAutostartScript(), mSettings.isBootAutostartKeepSession());
                String label = "Autoarranque (" + (useRoot ? "Root" : "Sin Root") + ")";
                showTerminalView();
                TerminalSession session = mService.createDebianCommandSession(
                    useRoot, chrootPath, scriptPath, bootCmd, label, MainActivity.this);
                switchToSession(session);
                if (mSettings.isBootAutostartWakeLock() && !mService.isWakeLockHeld()) {
                    mService.setWakeLockEnabled(true);
                }
                refreshDrawerSessions();
                updateWakeLockUi();
            });
        });
    }

    // -------------------------------------------------------------------------
    // Extra Keys & Soft Keyboard Helpers
    // -------------------------------------------------------------------------

    private void dispatchExtraKey(String keyCode, boolean ctrl, boolean alt) {
        if (mTerminalView == null || mCurrentSession == null) return;
        int mod = (ctrl ? KeyHandler.KEYMOD_CTRL : 0) | (alt ? KeyHandler.KEYMOD_ALT : 0);
        switch (keyCode) {
            case "ESC":
                mTerminalView.handleKeyCode(KeyEvent.KEYCODE_ESCAPE, mod);
                break;
            case "TAB":
                mTerminalView.handleKeyCode(KeyEvent.KEYCODE_TAB, mod);
                break;
            case "BACKTAB":
                mTerminalView.handleKeyCode(KeyEvent.KEYCODE_TAB, mod | KeyHandler.KEYMOD_SHIFT);
                break;
            case "HOME":
                mTerminalView.handleKeyCode(KeyEvent.KEYCODE_MOVE_HOME, mod);
                break;
            case "END":
                mTerminalView.handleKeyCode(KeyEvent.KEYCODE_MOVE_END, mod);
                break;
            case "PGUP":
                mTerminalView.handleKeyCode(KeyEvent.KEYCODE_PAGE_UP, mod);
                break;
            case "PGDN":
                mTerminalView.handleKeyCode(KeyEvent.KEYCODE_PAGE_DOWN, mod);
                break;
            case "UP":
                mTerminalView.handleKeyCode(KeyEvent.KEYCODE_DPAD_UP, mod);
                break;
            case "DOWN":
                mTerminalView.handleKeyCode(KeyEvent.KEYCODE_DPAD_DOWN, mod);
                break;
            case "LEFT":
                mTerminalView.handleKeyCode(KeyEvent.KEYCODE_DPAD_LEFT, mod);
                break;
            case "RIGHT":
                mTerminalView.handleKeyCode(KeyEvent.KEYCODE_DPAD_RIGHT, mod);
                break;
            case "ENTER":
                mTerminalView.handleKeyCode(KeyEvent.KEYCODE_ENTER, mod);
                break;
            case "BKSP":
                mTerminalView.handleKeyCode(KeyEvent.KEYCODE_DEL, mod);
                break;
            default:
                if (keyCode.length() == 1) {
                    mTerminalView.inputCodePoint(keyCode.charAt(0), ctrl, alt);
                }
                break;
        }
        mTerminalView.requestFocus();
    }

    private void toggleSoftKeyboard() {
        mTerminalView.requestFocus();
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.toggleSoftInput(InputMethodManager.SHOW_FORCED, 0);
        }
    }

    private void showSoftKeyboard() {
        mTerminalView.requestFocus();
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.showSoftInput(mTerminalView, 0);
        }
    }

    private void hideSoftKeyboard() {
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null && mRootFrame != null) {
            imm.hideSoftInputFromWindow(mRootFrame.getWindowToken(), 0);
        }
    }

    private void pasteFromClipboard() {
        if (mCurrentSession == null) return;
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm == null) return;
        ClipData clip = cm.getPrimaryClip();
        if (clip != null && clip.getItemCount() > 0) {
            CharSequence text = clip.getItemAt(0).coerceToText(this);
            if (!TextUtils.isEmpty(text)) {
                TerminalEmulator em = mCurrentSession.getEmulator();
                if (em != null) {
                    em.paste(text.toString());
                } else {
                    mCurrentSession.write(text.toString());
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Context Menu ("Más..." during Text Selection)
    // -------------------------------------------------------------------------

    @Override
    public void onCreateContextMenu(ContextMenu menu, View v, ContextMenu.ContextMenuInfo menuInfo) {
        super.onCreateContextMenu(menu, v, menuInfo);
        String stored = mTerminalView.getStoredSelectedText();
        mPendingContextUrl = extractFirstUrl(stored);
        if (mPendingContextUrl != null) {
            menu.add(Menu.NONE, CTX_COPY_URL, Menu.NONE, "Abrir URL");
        }
        if (!TextUtils.isEmpty(stored)) {
            menu.add(Menu.NONE, CTX_SHARE_TEXT, Menu.NONE, "Compartir selección");
        }
        menu.add(Menu.NONE, CTX_PASTE, Menu.NONE, "Pegar");
        menu.add(Menu.NONE, CTX_RESET_TERM, Menu.NONE, "Reiniciar estado de terminal");
    }

    @Override
    public boolean onContextItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == CTX_COPY_URL && mPendingContextUrl != null) {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(mPendingContextUrl)));
            } catch (Exception e) {
                Toast.makeText(this, "No se pudo abrir la URL", Toast.LENGTH_SHORT).show();
            }
            return true;
        } else if (id == CTX_SHARE_TEXT) {
            String stored = mTerminalView.getStoredSelectedText();
            if (!TextUtils.isEmpty(stored)) {
                Intent share = new Intent(Intent.ACTION_SEND);
                share.setType("text/plain");
                share.putExtra(Intent.EXTRA_TEXT, stored);
                startActivity(Intent.createChooser(share, "Compartir texto"));
            }
            return true;
        } else if (id == CTX_PASTE) {
            pasteFromClipboard();
            return true;
        } else if (id == CTX_RESET_TERM && mCurrentSession != null) {
            mCurrentSession.reset();
            return true;
        }
        return super.onContextItemSelected(item);
    }

    @Override
    public void onContextMenuClosed(Menu menu) {
        super.onContextMenuClosed(menu);
        mTerminalView.onContextMenuClosed(menu);
        mPendingContextUrl = null;
    }

    private static String extractFirstUrl(String text) {
        if (text == null) return null;
        Matcher m = Pattern.compile("https?://[^\\s\\]\\)>\"']+").matcher(text);
        return m.find() ? m.group() : null;
    }

    // -------------------------------------------------------------------------
    // TerminalViewClient
    // -------------------------------------------------------------------------

    @Override
    public float onScale(float scale) {
        if (scale < 0.9f || scale > 1.1f) {
            boolean increase = scale > 1.0f;
            int cur = mSettings.getFontSizePx();
            int next = TerminalSettings.clampFontSize(cur + (increase ? 2 : -2));
            if (next != cur) {
                mSettings.setFontSizePx(next);
                mTerminalView.setTextSize(next);
            }
            return 1.0f;
        }
        return scale;
    }

    @Override
    public void onSingleTapUp(MotionEvent e) {
        showSoftKeyboard();
    }

    @Override
    public boolean shouldBackButtonBeMappedToEscape() {
        return false;
    }

    @Override
    public boolean shouldEnforceCharBasedInput() {
        return true;
    }

    @Override
    public boolean shouldUseCtrlSpaceWorkaround() {
        return false;
    }

    @Override
    public boolean isTerminalViewSelected() {
        return isTerminalVisible()
            && (mDrawerLayout == null || !mDrawerLayout.isDrawerOpen())
            && !isSearchBarVisible()
            && !isTextInputVisible();
    }

    @Override
    public void copyModeChanged(boolean copyMode) {
        // Handled by TerminalView floating ActionMode
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent e, TerminalSession session) {
        if (keyCode == KeyEvent.KEYCODE_ENTER && session != null && !session.isRunning()) {
            closeSession(session);
            return true;
        }
        return false;
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent e) {
        return false;
    }

    @Override
    public boolean onLongPress(MotionEvent event) {
        return false;
    }

    @Override
    public boolean readControlKey() {
        return mExtraKeys != null && mExtraKeys.readAndConsumeCtrl();
    }

    @Override
    public boolean readAltKey() {
        return mExtraKeys != null && mExtraKeys.readAndConsumeAlt();
    }

    @Override
    public boolean readShiftKey() {
        return false;
    }

    @Override
    public boolean readFnKey() {
        return false;
    }

    @Override
    public boolean onCodePoint(int codePoint, boolean ctrlDown, TerminalSession session) {
        return false;
    }

    @Override
    public void onEmulatorSet() {
        if (mCurrentSession != null && mCurrentSession.getEmulator() != null) {
            mCurrentSession.getEmulator().setSixelEnabled(mSettings.isSixelEnabled());
        }
        if (mTerminalView != null && isTerminalVisible()) {
            mTerminalView.setTerminalCursorBlinkerRate(500);
            mTerminalView.setTerminalCursorBlinkerState(true, true);
        }
    }

    // -------------------------------------------------------------------------
    // TerminalSessionClient
    // -------------------------------------------------------------------------

    @Override
    public void onTextChanged(TerminalSession changedSession) {
        if (changedSession == mCurrentSession && mTerminalView != null) {
            mTerminalView.onScreenUpdated();
        }
    }

    @Override
    public void onTitleChanged(TerminalSession changedSession) {
        if (changedSession == mCurrentSession) {
            updateSessionTitle();
        }
        refreshDrawerSessions();
    }

    @Override
    public void onSessionFinished(TerminalSession finishedSession) {
        if (finishedSession == mActiveDesktopSession) {
            disconnectRfbClient();
            mActiveDesktopSession = null;
        }
        if (mService != null && mService.hasSession(finishedSession)) {
            int exitStatus = finishedSession.getExitStatus();
            if (exitStatus == 0) {
                closeSession(finishedSession);
                return;
            }
            if (exitStatus == -9 && Build.VERSION.SDK_INT >= PhantomProcessManager.MIN_AFFECTED_API && isTerminalVisible()) {
                new AlertDialog.Builder(this)
                    .setTitle("Sesión cerrada por Android (signal 9)")
                    .setMessage("Es muy probable que el Phantom Process Killer de Android haya detenido esta sesión en segundo plano.\n\n¿Quieres ver cómo desactivarlo?")
                    .setPositiveButton("Corregir / Ver guía", (d, w) -> showPhantomKillerDialog())
                    .setNegativeButton("Cerrar aviso", null)
                    .show();
            }
            mService.onSessionStateChanged();
        }
        refreshDrawerSessions();
        updatePathButtonsForMode();
    }

    @Override
    public void onCopyTextToClipboard(TerminalSession session, String text) {
        if (TextUtils.isEmpty(text)) return;
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText("DebianTerm", text));
            Toast.makeText(this, "Copiado al portapapeles", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onPasteTextFromClipboard(TerminalSession session) {
        pasteFromClipboard();
    }

    @Override
    public void onBell(TerminalSession session) {
        // Quiet bell
    }

    @Override
    public void onColorsChanged(TerminalSession session) {
        if (session == mCurrentSession && mTerminalView != null) {
            mTerminalView.invalidate();
        }
    }

    @Override
    public void onTerminalCursorStateChange(boolean state) {
        if (mTerminalView != null) {
            mTerminalView.setTerminalCursorBlinkerState(state, true);
        }
    }

    @Override
    public Integer getTerminalCursorStyle() {
        return TerminalEmulator.TERMINAL_CURSOR_STYLE_BLOCK;
    }

    // -------------------------------------------------------------------------
    // Logging (shared by TerminalViewClient & TerminalSessionClient)
    // -------------------------------------------------------------------------

    @Override
    public void logError(String tag, String message) {
        Log.e(tag != null ? tag : LOG_TAG, message != null ? message : "");
    }

    @Override
    public void logWarn(String tag, String message) {
        Log.w(tag != null ? tag : LOG_TAG, message != null ? message : "");
    }

    @Override
    public void logInfo(String tag, String message) {
        Log.i(tag != null ? tag : LOG_TAG, message != null ? message : "");
    }

    @Override
    public void logDebug(String tag, String message) {
        Log.d(tag != null ? tag : LOG_TAG, message != null ? message : "");
    }

    @Override
    public void logVerbose(String tag, String message) {
        Log.v(tag != null ? tag : LOG_TAG, message != null ? message : "");
    }

    @Override
    public void logStackTraceWithMessage(String tag, String message, Exception e) {
        Log.e(tag != null ? tag : LOG_TAG, message != null ? message : "", e);
    }

    @Override
    public void logStackTrace(String tag, Exception e) {
        Log.e(tag != null ? tag : LOG_TAG, "Exception", e);
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
