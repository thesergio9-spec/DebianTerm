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
import com.termux.terminal.TerminalColors;
import com.termux.terminal.TerminalEmulator;
import com.termux.terminal.TerminalSession;
import com.termux.terminal.TerminalSessionClient;
import com.termux.view.TerminalView;
import com.termux.view.TerminalViewClient;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
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

    private static final int CTX_COPY_URL = 101;
    private static final int CTX_SHARE_TEXT = 102;
    private static final int CTX_PASTE = 103;
    private static final int CTX_RESET_TERM = 104;
    private static final String PERM_POST_NOTIFICATIONS = "android.permission.POST_NOTIFICATIONS";
    private static final int REQ_POST_NOTIFICATIONS = 2001;
    private static final int REQ_STORAGE_PERMISSION = 2002;

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
    private Button mDrawerAndroidShellBtn;
    private Button mDrawerDesktopBtn;
    private Button mDrawerWakeLockBtn;
    private Button mDrawerSetupStorageBtn;

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
        mTerminalView.setTypeface(Typeface.MONOSPACE);
        registerForContextMenu(mTerminalView);

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

        mDrawerChrootSummary = new TextView(this);
        mDrawerChrootSummary.setText("Debian 12: comprobando...");
        mDrawerChrootSummary.setTextColor(Color.parseColor("#8B949E"));
        mDrawerChrootSummary.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
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

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.VERTICAL);
        actions.setPadding(dp(10), dp(8), dp(10), dp(12));

        mDrawerDesktopBtn = makeDrawerActionButton("🖥 Volver al Escritorio Gráfico", "#238636", v -> {
            mDrawerLayout.closeDrawer();
            showDesktopView();
        });
        mDrawerDesktopBtn.setVisibility(View.GONE);
        actions.addView(mDrawerDesktopBtn);

        actions.addView(makeDrawerActionButton("+ Nueva sesión Debian 12 (Root)", "#D70A53", v -> {
            mDrawerLayout.closeDrawer();
            openNewRootDebianSession();
        }));
        actions.addView(makeDrawerActionButton("+ Nueva sesión Debian 12 (Sin Root)", "#238636", v -> {
            mDrawerLayout.closeDrawer();
            openNewProotDebianSession();
        }));
        mDrawerAndroidShellBtn = makeDrawerActionButton("+ Shell Android (sh)", "#21262D", v -> {
            mDrawerLayout.closeDrawer();
            if (mLastReport.hasRoot) {
                openNewAndroidRootSession();
            } else {
                openNewAndroidShellSession();
            }
        });
        actions.addView(mDrawerAndroidShellBtn);
        mDrawerWakeLockBtn = makeDrawerActionButton("⚡ Activar Wake Lock (CPU + WiFi)", "#21262D", v ->
            toggleWakeLockFromUi());
        actions.addView(mDrawerWakeLockBtn);
        mDrawerSetupStorageBtn = makeDrawerActionButton("📁 Configurar almacenamiento (setup-storage)", "#21262D", v ->
            runSetupStorageFlow());
        actions.addView(mDrawerSetupStorageBtn);
        actions.addView(makeDrawerActionButton("⚡ Snippets / Comandos rápidos", "#21262D", v -> {
            mDrawerLayout.closeDrawer();
            showSnippetsDialog();
        }));
        actions.addView(makeDrawerActionButton("Panel de Inicio y Requisitos", "#1F6FEB", v -> {
            mDrawerLayout.closeDrawer();
            showDashboardView();
        }));
        actions.addView(makeDrawerActionButton("Ajustes rápidos", "#21262D", v -> {
            mDrawerLayout.closeDrawer();
            showSettingsDialog();
        }));

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
        btn.setBackground(makePillBg(bgHex));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(3), 0, dp(3));
        btn.setLayoutParams(lp);
        btn.setOnClickListener(listener);
        return btn;
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
                ? (wifi ? "⚡ Wake Lock: ACTIVO (CPU + WiFi)" : "⚡ Wake Lock: ACTIVO (CPU)")
                : "⚡ Activar Wake Lock (CPU + WiFi)");
            mDrawerWakeLockBtn.setBackground(makePillBg(held ? "#9E6A03" : "#21262D"));
        }
        if (mDrawerSetupStorageBtn != null) {
            boolean storageOk = ProotManager.hasSharedStorageAccess(this);
            mDrawerSetupStorageBtn.setText(storageOk
                ? "📁 Almacenamiento: /sdcard OK"
                : "📁 Configurar almacenamiento (setup-storage)");
            mDrawerSetupStorageBtn.setBackground(makePillBg(storageOk ? "#238636" : "#21262D"));
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
            indicator.setBackgroundColor(active ? Color.parseColor("#D70A53") : Color.TRANSPARENT);
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

        if (mDrawerAndroidShellBtn != null) {
            mDrawerAndroidShellBtn.setText(r.hasRoot ? "+ Shell Android (su)" : "+ Shell Android (sh)");
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
                    "Regenerar script " + fScript + " (con backup)"
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
                                runSetupStorageFlow();
                            } else if (idx == 2) {
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
                            } else if (idx == 3) {
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

        ScrollView scroll = new ScrollView(this);
        scroll.addView(content);

        new AlertDialog.Builder(this)
            .setTitle("Ajustes rápidos")
            .setView(scroll)
            .setPositiveButton("Listo", null)
            .show();
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
