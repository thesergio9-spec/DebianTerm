package com.debianchroot.term;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.text.TextUtils;
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
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
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

import java.util.List;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Main terminal activity hosting the patched Termux TerminalView, Termux-style ExtraKeysBarView,
 * session drawer, quick settings, and Debian Chroot installer/manager.
 */
public final class MainActivity extends Activity implements TerminalViewClient, TerminalSessionClient {

    private static final String LOG_TAG = "DebianTerm";

    private static final int CTX_COPY_URL = 101;
    private static final int CTX_SHARE_TEXT = 102;
    private static final int CTX_PASTE = 103;
    private static final int CTX_RESET_TERM = 104;

    private final ExecutorService mBgExecutor = Executors.newSingleThreadExecutor();

    private TerminalSettings mSettings;
    private SlideDrawerLayout mDrawerLayout;
    private TerminalView mTerminalView;
    private ExtraKeysBarView mExtraKeys;

    private LinearLayout mTopBar;
    private TextView mMenuBtn;
    private TextView mTitleView;
    private TextView mChrootPill;
    private TextView mDrawerChrootSummary;
    private LinearLayout mDrawerSessionsContainer;

    private DebianTermService mService;
    private boolean mBound = false;
    private TerminalSession mCurrentSession;
    private boolean mInitialCheckDone = false;
    private ChrootManager.ChrootStatus mLastStatus = new ChrootManager.ChrootStatus();
    private String mPendingContextUrl = null;

    private final ServiceConnection mServiceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            DebianTermService.LocalBinder localBinder = (DebianTermService.LocalBinder) binder;
            mService = localBinder.getService();
            mBound = true;
            mService.setClientVisible(true);
            mService.setEventListener(() -> runOnUiThread(MainActivity.this::finish));

            for (TerminalSession s : mService.getSessions()) {
                s.updateTerminalSessionClient(MainActivity.this);
            }

            if (!mService.getSessions().isEmpty()) {
                TerminalSession target = mCurrentSession;
                if (target == null || !mService.getSessions().contains(target)) {
                    target = mService.getSessions().get(mService.getSessions().size() - 1);
                }
                switchToSession(target);
                refreshChrootStatusAsync(false);
            } else if (!mInitialCheckDone) {
                mInitialCheckDone = true;
                refreshChrootStatusAsync(true);
            }
            refreshDrawerSessions();
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(svcIntent);
        } else {
            startService(svcIntent);
        }
        bindService(svcIntent, mServiceConnection, Context.BIND_AUTO_CREATE);
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (mBound && mService != null) {
            mService.setClientVisible(true);
        }
        if (mTerminalView != null) {
            mTerminalView.setTerminalCursorBlinkerRate(500);
            mTerminalView.setTerminalCursorBlinkerState(true, true);
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
        if (mBound) {
            unbindService(mServiceConnection);
            mBound = false;
        }
        mBgExecutor.shutdownNow();
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (mDrawerLayout != null && mDrawerLayout.isDrawerOpen()) {
            mDrawerLayout.closeDrawer();
            return;
        }
        if (mTerminalView != null && mTerminalView.isSelectingText()) {
            mTerminalView.stopTextSelectionMode();
            return;
        }
        super.onBackPressed();
    }

    // -------------------------------------------------------------------------
    // UI Construction
    // -------------------------------------------------------------------------

    private View buildRootLayout() {
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
        mChrootPill.setText("Comprobando…");
        mChrootPill.setTextColor(Color.WHITE);
        mChrootPill.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        mChrootPill.setTypeface(Typeface.DEFAULT_BOLD);
        mChrootPill.setPadding(dp(8), dp(3), dp(8), dp(3));
        mChrootPill.setBackground(makePillBg("#30363D"));
        mChrootPill.setOnClickListener(v -> showChrootDialog(false));
        LinearLayout.LayoutParams pillLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        pillLp.setMargins(dp(4), 0, dp(4), 0);
        mTopBar.addView(mChrootPill, pillLp);

        mTopBar.addView(makeTopButton("⌨", v -> toggleSoftKeyboard()));
        mTopBar.addView(makeTopButton("📋", v -> pasteFromClipboard()));
        mTopBar.addView(makeTopButton("⚙", v -> showSettingsDialog()));

        mainContainer.addView(mTopBar, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(38)));

        // Terminal view
        mTerminalView = new TerminalView(this, null);
        mTerminalView.setFocusable(true);
        mTerminalView.setFocusableInTouchMode(true);
        mTerminalView.setKeepScreenOn(false);
        mainContainer.addView(mTerminalView, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // Extra keys bar docked at bottom above IME
        mExtraKeys = new ExtraKeysBarView(this);
        mExtraKeys.setListener(new ExtraKeysBarView.Listener() {
            @Override
            public void onKeyButton(String keyCode, boolean ctrl, boolean alt) {
                dispatchExtraKey(keyCode, ctrl, alt);
            }

            @Override
            public void onModifiersChanged() {
                // Keep focus on TerminalView
                mTerminalView.requestFocus();
            }
        });
        mainContainer.addView(mExtraKeys, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        View drawerContent = buildDrawerContent();
        mDrawerLayout = new SlideDrawerLayout(this, mainContainer, drawerContent);
        mDrawerLayout.setDrawerListener(isOpen -> {
            if (isOpen) {
                refreshDrawerSessions();
            } else {
                mTerminalView.requestFocus();
            }
        });
        return mDrawerLayout;
    }

    private View buildDrawerContent() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.parseColor("#161B22"));

        // Header
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
        mDrawerChrootSummary.setText("Chroot: comprobando...");
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

        actions.addView(makeDrawerActionButton("+ Nueva sesión Debian", "#D70A53", v -> {
            mDrawerLayout.closeDrawer();
            openNewDebianSession();
        }));
        actions.addView(makeDrawerActionButton("+ Shell Android (su)", "#21262D", v -> {
            mDrawerLayout.closeDrawer();
            openNewAndroidRootSession();
        }));
        actions.addView(makeDrawerActionButton("Instalador / Gestor Chroot", "#21262D", v -> {
            mDrawerLayout.closeDrawer();
            showChrootDialog(false);
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
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        tv.setGravity(Gravity.CENTER);
        tv.setMinimumWidth(dp(34));
        tv.setMinimumHeight(dp(34));
        tv.setPadding(dp(6), dp(2), dp(6), dp(2));
        tv.setOnClickListener(clickListener);
        return tv;
    }

    private Button makeDrawerActionButton(String text, String bgHex, View.OnClickListener listener) {
        Button btn = new Button(this);
        btn.setText(text);
        btn.setAllCaps(false);
        btn.setTextColor(Color.WHITE);
        btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
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
    // Session Management
    // -------------------------------------------------------------------------

    private void openNewDebianSession() {
        if (mService == null) return;
        TerminalSession session = mService.createDebianSession(
            mSettings.getChrootPath(),
            mSettings.getEntryScriptPath(),
            this
        );
        switchToSession(session);
        refreshDrawerSessions();
    }

    private void openNewAndroidRootSession() {
        if (mService == null) return;
        TerminalSession session = mService.createAndroidRootSession(this);
        switchToSession(session);
        refreshDrawerSessions();
    }

    private void switchToSession(TerminalSession session) {
        if (session == null) return;
        mCurrentSession = session;
        mTerminalView.attachSession(session);
        mTerminalView.requestFocus();
        updateSessionTitle();
        refreshDrawerSessions();
    }

    private void updateSessionTitle() {
        if (mService != null && mCurrentSession != null) {
            mTitleView.setText(mService.getSessionDisplayName(mCurrentSession));
        } else {
            mTitleView.setText("DebianTerm");
        }
    }

    private void refreshDrawerSessions() {
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
        boolean wasCurrent = (session == mCurrentSession);
        mService.removeSession(session);
        List<TerminalSession> remaining = mService.getSessions();
        if (wasCurrent) {
            if (!remaining.isEmpty()) {
                switchToSession(remaining.get(remaining.size() - 1));
            } else {
                mCurrentSession = null;
                mTitleView.setText("DebianTerm");
                mDrawerLayout.openDrawer();
            }
        }
        refreshDrawerSessions();
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
    // Chroot Inspection, Management & Installer UI
    // -------------------------------------------------------------------------

    private void refreshChrootStatusAsync(final boolean autoActionOnBoot) {
        final String chrootPath = mSettings.getChrootPath();
        final String scriptPath = mSettings.getEntryScriptPath();
        mBgExecutor.execute(() -> {
            ChrootManager.ChrootStatus status = ChrootManager.inspectStatus(chrootPath, scriptPath);
            runOnUiThread(() -> {
                mLastStatus = status;
                updateChrootStatusBadges(status);
                if (autoActionOnBoot) {
                    if (status.rootfsExists) {
                        openNewDebianSession();
                    } else {
                        showChrootDialog(true);
                    }
                }
            });
        });
    }

    private void updateChrootStatusBadges(ChrootManager.ChrootStatus status) {
        if (!status.hasRoot) {
            mChrootPill.setText("Sin root");
            mChrootPill.setBackground(makePillBg("#B62324"));
            mDrawerChrootSummary.setText("Root no disponible (su)");
        } else if (status.rootfsExists) {
            String shortName = status.debianPrettyName.contains("13") || status.debianPrettyName.contains("trixie")
                ? "Debian 13"
                : "Debian 12";
            mChrootPill.setText(shortName);
            mChrootPill.setBackground(makePillBg("#238636"));
            String mountText = status.activeMounts.isEmpty()
                ? "sin montajes"
                : status.activeMounts.size() + " montajes";
            String sizePart = status.rootfsSizeSummary.isEmpty() ? "" : " • " + status.rootfsSizeSummary;
            mDrawerChrootSummary.setText(status.debianPrettyName + " (" + mountText + sizePart + ")");
        } else {
            mChrootPill.setText("Sin instalar");
            mChrootPill.setBackground(makePillBg("#9E6A03"));
            mDrawerChrootSummary.setText("Chroot no instalado en " + mSettings.getChrootPath());
        }
    }

    private void showChrootDialog(boolean firstRunNotice) {
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(18), dp(14), dp(18), dp(10));

        if (firstRunNotice) {
            TextView intro = new TextView(this);
            intro.setText("No se encontró un chroot Debian en " + mSettings.getChrootPath()
                + ". Puedes instalarlo automáticamente ahora o ajustar la ruta.");
            intro.setTextColor(Color.parseColor("#E3B341"));
            intro.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            intro.setPadding(0, 0, 0, dp(10));
            content.addView(intro);
        }

        final TextView statusBox = new TextView(this);
        statusBox.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        statusBox.setTypeface(Typeface.MONOSPACE);
        statusBox.setPadding(dp(10), dp(8), dp(10), dp(8));
        statusBox.setBackgroundColor(Color.parseColor("#161B22"));
        statusBox.setTextColor(Color.parseColor("#C9D1D9"));
        statusBox.setText(formatStatusSummary(mLastStatus));
        content.addView(statusBox);

        TextView verLabel = new TextView(this);
        verLabel.setText("Versión Debian a instalar:");
        verLabel.setPadding(0, dp(12), 0, dp(4));
        content.addView(verLabel);

        final RadioGroup releaseGroup = new RadioGroup(this);
        releaseGroup.setOrientation(LinearLayout.HORIZONTAL);
        final RadioButton rbBookworm = new RadioButton(this);
        rbBookworm.setText("Debian 12 (Bookworm)");
        rbBookworm.setId(View.generateViewId());
        final RadioButton rbTrixie = new RadioButton(this);
        rbTrixie.setText("Debian 13 (Trixie)");
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
            .setTitle("Instalador y Gestor Chroot Debian")
            .setView(scroll)
            .setPositiveButton("Instalar / Descargar Debian", null)
            .setNeutralButton("Más acciones", null)
            .setNegativeButton("Cerrar", null)
            .create();

        dialog.setOnShowListener(dlg -> {
            // Refresh live status while dialog opens
            mBgExecutor.execute(() -> {
                ChrootManager.ChrootStatus fresh = ChrootManager.inspectStatus(
                    mSettings.getChrootPath(), mSettings.getEntryScriptPath());
                runOnUiThread(() -> {
                    mLastStatus = fresh;
                    updateChrootStatusBadges(fresh);
                    statusBox.setText(formatStatusSummary(fresh));
                });
            });

            Button installBtn = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            Button actionsBtn = dialog.getButton(AlertDialog.BUTTON_NEUTRAL);

            installBtn.setOnClickListener(v -> {
                final String release = rbTrixie.isChecked() ? "trixie" : "bookworm";
                String chrootPath = chrootEdit.getText().toString().trim();
                String scriptPath = scriptEdit.getText().toString().trim();
                if (chrootPath.isEmpty()) chrootPath = TerminalSettings.DEFAULT_CHROOT_PATH;
                if (scriptPath.isEmpty()) scriptPath = TerminalSettings.DEFAULT_ENTRY_SCRIPT_PATH;

                final String prevChroot = mSettings.getChrootPath();
                mSettings.setDebianRelease(release);
                mSettings.setChrootPath(chrootPath);
                mSettings.setEntryScriptPath(scriptPath);

                final String fChroot = mSettings.getChrootPath();
                final String fScript = mSettings.getEntryScriptPath();

                installBtn.setEnabled(false);
                mBgExecutor.execute(() -> {
                    final ChrootManager.ChrootStatus targetStatus = fChroot.equals(prevChroot)
                        ? mLastStatus
                        : ChrootManager.inspectStatus(fChroot, fScript);
                    runOnUiThread(() -> {
                        installBtn.setEnabled(true);
                        if (targetStatus.rootfsExists) {
                            new AlertDialog.Builder(MainActivity.this)
                                .setTitle("Chroot existente detectado")
                                .setMessage("Ya existe una instalación en " + fChroot + " (" + targetStatus.debianPrettyName
                                    + ").\n\n¿Seguro que quieres descargar y extraer encima? Tus archivos previos en esa ruta podrían sobrescribirse.")
                                .setPositiveButton("Sí, reinstalar", (d2, w2) -> {
                                    dialog.dismiss();
                                    startInstallWithProgress(release, fChroot, fScript);
                                })
                                .setNegativeButton("Cancelar", null)
                                .show();
                        } else {
                            dialog.dismiss();
                            startInstallWithProgress(release, fChroot, fScript);
                        }
                    });
                });
            });

            actionsBtn.setOnClickListener(v -> {
                mSettings.setChrootPath(chrootEdit.getText().toString());
                mSettings.setEntryScriptPath(scriptEdit.getText().toString());
                String fChroot = mSettings.getChrootPath();
                String fScript = mSettings.getEntryScriptPath();

                String[] opts = {
                    "Abrir sesión Debian ahora",
                    "Abrir Shell Android (su)",
                    "Desmontar montajes del chroot",
                    "Regenerar script " + fScript + " (con backup)",
                    "Actualizar estado"
                };
                new AlertDialog.Builder(MainActivity.this)
                    .setTitle("Acciones Chroot")
                    .setItems(opts, (d2, idx) -> {
                        if (idx == 0) {
                            dialog.dismiss();
                            openNewDebianSession();
                        } else if (idx == 1) {
                            dialog.dismiss();
                            openNewAndroidRootSession();
                        } else if (idx == 2) {
                            mBgExecutor.execute(() -> {
                                ChrootManager.ExecResult res = ChrootManager.unmountChroot(fChroot, fScript);
                                ChrootManager.ChrootStatus fresh = ChrootManager.inspectStatus(fChroot, fScript);
                                runOnUiThread(() -> {
                                    mLastStatus = fresh;
                                    updateChrootStatusBadges(fresh);
                                    statusBox.setText(formatStatusSummary(fresh));
                                    Toast.makeText(MainActivity.this,
                                        res.isOk() ? "Chroot desmontado" : "Aviso: " + res.stderr,
                                        Toast.LENGTH_SHORT).show();
                                });
                            });
                        } else if (idx == 3) {
                            mBgExecutor.execute(() -> {
                                ChrootManager.ExecResult res = ChrootManager.installEntryScript(fChroot, fScript, true);
                                ChrootManager.ChrootStatus fresh = ChrootManager.inspectStatus(fChroot, fScript);
                                runOnUiThread(() -> {
                                    mLastStatus = fresh;
                                    updateChrootStatusBadges(fresh);
                                    statusBox.setText(formatStatusSummary(fresh));
                                    Toast.makeText(MainActivity.this,
                                        res.isOk() ? "Script actualizado (backup creado si existía)" : "Error: " + res.stderr,
                                        Toast.LENGTH_LONG).show();
                                });
                            });
                        } else if (idx == 4) {
                            mBgExecutor.execute(() -> {
                                ChrootManager.ChrootStatus fresh = ChrootManager.inspectStatus(fChroot, fScript);
                                runOnUiThread(() -> {
                                    mLastStatus = fresh;
                                    updateChrootStatusBadges(fresh);
                                    statusBox.setText(formatStatusSummary(fresh));
                                });
                            });
                        }
                    })
                    .show();
            });
        });

        dialog.show();
    }

    private String formatStatusSummary(ChrootManager.ChrootStatus s) {
        StringBuilder sb = new StringBuilder();
        sb.append("Root (su): ").append(s.hasRoot ? "OK (" + s.suPath + ")" : "No detectado").append('\n');
        sb.append("Ruta:      ").append(mSettings.getChrootPath()).append('\n');
        sb.append("Rootfs:    ").append(s.rootfsExists ? s.debianPrettyName : "No instalado").append('\n');
        if (!s.rootfsSizeSummary.isEmpty()) {
            sb.append("Tamaño:    ").append(s.rootfsSizeSummary).append('\n');
        }
        sb.append("Script:    ").append(s.entryScriptExists ? "OK (" + mSettings.getEntryScriptPath() + ")" : "Falta").append('\n');
        sb.append("Montajes:  ").append(s.activeMounts.isEmpty() ? "Ninguno activo" : s.activeMounts.size() + " puntos montados");
        return sb.toString();
    }

    private void startInstallWithProgress(final String release, final String chrootPath, final String scriptPath) {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(18), dp(14), dp(18), dp(10));

        final TextView stageTv = new TextView(this);
        stageTv.setText("Iniciando instalación de Debian " + release + "...");
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
            .setTitle("Instalando Debian " + release)
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
            ChrootInstaller.InstallResult res = ChrootInstaller.install(
                MainActivity.this,
                release,
                chrootPath,
                scriptPath,
                cancelled,
                new ChrootInstaller.ProgressListener() {
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
                }
            );

            ChrootManager.ChrootStatus fresh = ChrootManager.inspectStatus(chrootPath, scriptPath);
            runOnUiThread(() -> {
                mLastStatus = fresh;
                updateChrootStatusBadges(fresh);
                Button btn = dlg.getButton(AlertDialog.BUTTON_NEGATIVE);
                btn.setEnabled(true);
                if (res.success) {
                    stageTv.setText("100% — ¡Instalación completada!");
                    btn.setText("Abrir Debian");
                    btn.setOnClickListener(v -> {
                        dlg.dismiss();
                        openNewDebianSession();
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
        if (notifyGeometry) {
            mTerminalView.onToolbarGeometryChanged();
            mTerminalView.post(() -> mTerminalView.updateSize());
        }
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
        return mDrawerLayout == null || !mDrawerLayout.isDrawerOpen();
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
        if (mTerminalView != null) {
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
        refreshDrawerSessions();
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
