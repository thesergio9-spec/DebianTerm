package com.debianchroot.term;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;

import java.util.Properties;

/**
 * Persistent preferences for DebianTerm UI, terminal behavior, and chroot paths.
 */
public final class TerminalSettings {

    private static final String PREFS_NAME = "debianterm_prefs";

    private static final String KEY_SOFT_KEYBOARD_PAN_MAIN_SCREEN = "soft_keyboard_pan_main_screen";
    private static final String KEY_FONT_SIZE_PX = "font_size_px";
    private static final String KEY_COLOR_SCHEME = "color_scheme";
    private static final String KEY_EXTRA_KEYS_MODE = "extra_keys_mode";
    private static final String KEY_HAPTIC_ENABLED = "haptic_enabled";
    private static final String KEY_CHROOT_PATH = "chroot_path";
    private static final String KEY_ENTRY_SCRIPT_PATH = "entry_script_path";
    private static final String KEY_DEBIAN_RELEASE = "debian_release";
    private static final String KEY_INSTALL_MODE = "install_mode";
    private static final String KEY_AUTO_ENTER_TERMINAL = "auto_enter_terminal";
    private static final String KEY_PROOT_DISABLE_SECCOMP = "proot_disable_seccomp";
    private static final String KEY_LAUNCH_UI_MODE = "launch_ui_mode";
    private static final String KEY_DESKTOP_ENV = "desktop_env";
    private static final String KEY_GUI_BACKEND = "gui_backend";
    private static final String KEY_GUI_RESOLUTION = "gui_resolution";
    private static final String KEY_AUTO_WAKELOCK = "auto_wakelock";
    private static final String KEY_SNIPPETS_DATA = "snippets_data_v1";

    public static final String MODE_ROOT = "root";
    public static final String MODE_PROOT = "proot";

    public static final String UI_MODE_CLI = "cli";
    public static final String UI_MODE_GUI = "gui";

    public static final String[] ALL_GUI_RESOLUTIONS = {
        "1280x720",
        "960x540",
        "1600x900"
    };

    public static final int MIN_FONT_SIZE_PX = 16;
    public static final int MAX_FONT_SIZE_PX = 68;
    public static final int DEFAULT_FONT_SIZE_PX = 30;

    public static final String EXTRA_KEYS_FULL = "full";
    public static final String EXTRA_KEYS_COMPACT = "compact";
    public static final String EXTRA_KEYS_HIDDEN = "hidden";

    public static final String SCHEME_DEBIAN_DARK = "debian_dark";
    public static final String SCHEME_AMOLED = "amoled";
    public static final String SCHEME_CATPPUCCIN = "catppuccin";
    public static final String SCHEME_GRUVBOX = "gruvbox";
    public static final String SCHEME_SOLARIZED_LIGHT = "solarized_light";

    public static final String[] ALL_SCHEME_IDS = {
        SCHEME_DEBIAN_DARK,
        SCHEME_AMOLED,
        SCHEME_CATPPUCCIN,
        SCHEME_GRUVBOX,
        SCHEME_SOLARIZED_LIGHT
    };

    public static final String[] ALL_SCHEME_NAMES = {
        "Debian Dark (Por defecto)",
        "AMOLED Negro Puro",
        "Catppuccin Mocha",
        "Gruvbox Dark",
        "Solarized Light"
    };

    public static final String DEFAULT_CHROOT_PATH = "/data/local/debian";
    public static final String DEFAULT_ENTRY_SCRIPT_PATH = "/data/local/bin/debian";
    public static final String DEFAULT_DEBIAN_RELEASE = "bookworm";

    private final SharedPreferences mPrefs;

    public TerminalSettings(Context context) {
        mPrefs = context.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public boolean isSoftKeyboardPanMainScreen() {
        return mPrefs.getBoolean(KEY_SOFT_KEYBOARD_PAN_MAIN_SCREEN, true);
    }

    public void setSoftKeyboardPanMainScreen(boolean enabled) {
        mPrefs.edit().putBoolean(KEY_SOFT_KEYBOARD_PAN_MAIN_SCREEN, enabled).apply();
    }

    public int getFontSizePx() {
        int size = mPrefs.getInt(KEY_FONT_SIZE_PX, DEFAULT_FONT_SIZE_PX);
        return clampFontSize(size);
    }

    public void setFontSizePx(int sizePx) {
        mPrefs.edit().putInt(KEY_FONT_SIZE_PX, clampFontSize(sizePx)).apply();
    }

    public static int clampFontSize(int sizePx) {
        return Math.max(MIN_FONT_SIZE_PX, Math.min(MAX_FONT_SIZE_PX, sizePx));
    }

    public String getColorSchemeId() {
        String id = mPrefs.getString(KEY_COLOR_SCHEME, SCHEME_DEBIAN_DARK);
        return isValidScheme(id) ? id : SCHEME_DEBIAN_DARK;
    }

    public void setColorSchemeId(String schemeId) {
        if (!isValidScheme(schemeId)) schemeId = SCHEME_DEBIAN_DARK;
        mPrefs.edit().putString(KEY_COLOR_SCHEME, schemeId).apply();
    }

    private static boolean isValidScheme(String id) {
        if (id == null) return false;
        for (String s : ALL_SCHEME_IDS) {
            if (s.equals(id)) return true;
        }
        return false;
    }

    public String getExtraKeysMode() {
        String mode = mPrefs.getString(KEY_EXTRA_KEYS_MODE, EXTRA_KEYS_FULL);
        if (EXTRA_KEYS_COMPACT.equals(mode) || EXTRA_KEYS_HIDDEN.equals(mode)) {
            return mode;
        }
        return EXTRA_KEYS_FULL;
    }

    public void setExtraKeysMode(String mode) {
        if (!EXTRA_KEYS_COMPACT.equals(mode) && !EXTRA_KEYS_HIDDEN.equals(mode)) {
            mode = EXTRA_KEYS_FULL;
        }
        mPrefs.edit().putString(KEY_EXTRA_KEYS_MODE, mode).apply();
    }

    public boolean isHapticEnabled() {
        return mPrefs.getBoolean(KEY_HAPTIC_ENABLED, true);
    }

    public void setHapticEnabled(boolean enabled) {
        mPrefs.edit().putBoolean(KEY_HAPTIC_ENABLED, enabled).apply();
    }

    public String getChrootPath() {
        String p = mPrefs.getString(KEY_CHROOT_PATH, DEFAULT_CHROOT_PATH);
        if (p == null || p.trim().isEmpty() || p.trim().equals("/")) {
            return DEFAULT_CHROOT_PATH;
        }
        return sanitizePath(p);
    }

    public void setChrootPath(String path) {
        String sanitized = (path == null || path.trim().isEmpty() || path.trim().equals("/")) ? DEFAULT_CHROOT_PATH : sanitizePath(path);
        mPrefs.edit().putString(KEY_CHROOT_PATH, sanitized).apply();
    }

    public String getEntryScriptPath() {
        String p = mPrefs.getString(KEY_ENTRY_SCRIPT_PATH, DEFAULT_ENTRY_SCRIPT_PATH);
        if (p == null || p.trim().isEmpty() || p.trim().equals("/")) {
            return DEFAULT_ENTRY_SCRIPT_PATH;
        }
        return sanitizePath(p);
    }

    public void setEntryScriptPath(String path) {
        String sanitized = (path == null || path.trim().isEmpty() || path.trim().equals("/")) ? DEFAULT_ENTRY_SCRIPT_PATH : sanitizePath(path);
        mPrefs.edit().putString(KEY_ENTRY_SCRIPT_PATH, sanitized).apply();
    }

    public String getDebianRelease() {
        String rel = mPrefs.getString(KEY_DEBIAN_RELEASE, DEFAULT_DEBIAN_RELEASE);
        return "trixie".equals(rel) ? "trixie" : DEFAULT_DEBIAN_RELEASE;
    }

    public void setDebianRelease(String release) {
        mPrefs.edit().putString(KEY_DEBIAN_RELEASE, "trixie".equals(release) ? "trixie" : DEFAULT_DEBIAN_RELEASE).apply();
    }

    public String getInstallMode() {
        String mode = mPrefs.getString(KEY_INSTALL_MODE, MODE_ROOT);
        return MODE_PROOT.equals(mode) ? MODE_PROOT : MODE_ROOT;
    }

    public void setInstallMode(String mode) {
        mPrefs.edit().putString(KEY_INSTALL_MODE, MODE_PROOT.equals(mode) ? MODE_PROOT : MODE_ROOT).apply();
    }

    public boolean isAutoEnterTerminal() {
        return mPrefs.getBoolean(KEY_AUTO_ENTER_TERMINAL, false);
    }

    public void setAutoEnterTerminal(boolean enabled) {
        mPrefs.edit().putBoolean(KEY_AUTO_ENTER_TERMINAL, enabled).apply();
    }

    public boolean isAutoWakeLockEnabled() {
        return mPrefs.getBoolean(KEY_AUTO_WAKELOCK, false);
    }

    public void setAutoWakeLockEnabled(boolean enabled) {
        mPrefs.edit().putBoolean(KEY_AUTO_WAKELOCK, enabled).apply();
    }

    public java.util.List<SnippetManager.Snippet> getSnippets() {
        if (!mPrefs.contains(KEY_SNIPPETS_DATA)) {
            return SnippetManager.getDefaultSnippets();
        }
        return SnippetManager.deserialize(mPrefs.getString(KEY_SNIPPETS_DATA, ""));
    }

    public void saveSnippets(java.util.List<SnippetManager.Snippet> snippets) {
        mPrefs.edit().putString(KEY_SNIPPETS_DATA, SnippetManager.serialize(snippets)).apply();
    }

    public boolean isProotDisableSeccomp() {
        return mPrefs.getBoolean(KEY_PROOT_DISABLE_SECCOMP, false);
    }

    public void setProotDisableSeccomp(boolean disabled) {
        mPrefs.edit().putBoolean(KEY_PROOT_DISABLE_SECCOMP, disabled).apply();
    }

    public String getLaunchUiMode() {
        String m = mPrefs.getString(KEY_LAUNCH_UI_MODE, UI_MODE_CLI);
        return UI_MODE_GUI.equals(m) ? UI_MODE_GUI : UI_MODE_CLI;
    }

    public void setLaunchUiMode(String mode) {
        mPrefs.edit().putString(KEY_LAUNCH_UI_MODE, UI_MODE_GUI.equals(mode) ? UI_MODE_GUI : UI_MODE_CLI).apply();
    }

    public String getDesktopEnv() {
        String d = mPrefs.getString(KEY_DESKTOP_ENV, DesktopManager.DESKTOP_XFCE);
        return DesktopManager.DESKTOP_LXDE.equals(d) ? DesktopManager.DESKTOP_LXDE : DesktopManager.DESKTOP_XFCE;
    }

    public void setDesktopEnv(String env) {
        mPrefs.edit().putString(KEY_DESKTOP_ENV,
            DesktopManager.DESKTOP_LXDE.equals(env) ? DesktopManager.DESKTOP_LXDE : DesktopManager.DESKTOP_XFCE).apply();
    }

    public String getGuiBackend() {
        String b = mPrefs.getString(KEY_GUI_BACKEND, DesktopManager.BACKEND_VNC);
        return DesktopManager.BACKEND_TERMUX_X11.equals(b) ? DesktopManager.BACKEND_TERMUX_X11 : DesktopManager.BACKEND_VNC;
    }

    public void setGuiBackend(String backend) {
        mPrefs.edit().putString(KEY_GUI_BACKEND,
            DesktopManager.BACKEND_TERMUX_X11.equals(backend) ? DesktopManager.BACKEND_TERMUX_X11 : DesktopManager.BACKEND_VNC).apply();
    }

    public String getGuiResolution() {
        String r = mPrefs.getString(KEY_GUI_RESOLUTION, ALL_GUI_RESOLUTIONS[0]);
        for (String valid : ALL_GUI_RESOLUTIONS) {
            if (valid.equals(r)) return valid;
        }
        return ALL_GUI_RESOLUTIONS[0];
    }

    public void setGuiResolution(String res) {
        String chosen = ALL_GUI_RESOLUTIONS[0];
        for (String valid : ALL_GUI_RESOLUTIONS) {
            if (valid.equals(res)) {
                chosen = valid;
                break;
            }
        }
        mPrefs.edit().putString(KEY_GUI_RESOLUTION, chosen).apply();
    }

    private static String sanitizePath(String raw) {
        String trimmed = raw.trim();
        while (trimmed.length() > 1 && trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    public static String getSchemeDisplayName(String schemeId) {
        for (int i = 0; i < ALL_SCHEME_IDS.length; i++) {
            if (ALL_SCHEME_IDS[i].equals(schemeId)) {
                return ALL_SCHEME_NAMES[i];
            }
        }
        return ALL_SCHEME_NAMES[0];
    }

    public static int getBackgroundColorInt(String schemeId) {
        Properties props = getColorProperties(schemeId);
        return Color.parseColor(props.getProperty("background", "#0D1117"));
    }

    public static int getForegroundColorInt(String schemeId) {
        Properties props = getColorProperties(schemeId);
        return Color.parseColor(props.getProperty("foreground", "#E6EDF3"));
    }

    public static boolean isLightScheme(String schemeId) {
        return SCHEME_SOLARIZED_LIGHT.equals(schemeId);
    }

    public static Properties getColorProperties(String schemeId) {
        Properties p = new Properties();
        if (SCHEME_AMOLED.equals(schemeId)) {
            p.setProperty("background", "#000000");
            p.setProperty("foreground", "#F0F6FC");
            p.setProperty("cursor", "#FF2A7A");
            p.setProperty("color0", "#161B22");
            p.setProperty("color1", "#FF6B6B");
            p.setProperty("color2", "#51CF66");
            p.setProperty("color3", "#FCC419");
            p.setProperty("color4", "#4DABF7");
            p.setProperty("color5", "#CC5DE8");
            p.setProperty("color6", "#22B8CF");
            p.setProperty("color7", "#D0D7DE");
            p.setProperty("color8", "#6E7681");
            p.setProperty("color9", "#FF8787");
            p.setProperty("color10", "#69DB7C");
            p.setProperty("color11", "#FFD43B");
            p.setProperty("color12", "#74C0FC");
            p.setProperty("color13", "#DA77F2");
            p.setProperty("color14", "#3BC9DB");
            p.setProperty("color15", "#FFFFFF");
            return p;
        }
        if (SCHEME_CATPPUCCIN.equals(schemeId)) {
            p.setProperty("background", "#1E1E2E");
            p.setProperty("foreground", "#CDD6F4");
            p.setProperty("cursor", "#F5E0DC");
            p.setProperty("color0", "#45475A");
            p.setProperty("color1", "#F38BA8");
            p.setProperty("color2", "#A6E3A1");
            p.setProperty("color3", "#F9E2AF");
            p.setProperty("color4", "#89B4FA");
            p.setProperty("color5", "#F5C2E7");
            p.setProperty("color6", "#94E2D5");
            p.setProperty("color7", "#BAC2DE");
            p.setProperty("color8", "#585B70");
            p.setProperty("color9", "#F38BA8");
            p.setProperty("color10", "#A6E3A1");
            p.setProperty("color11", "#F9E2AF");
            p.setProperty("color12", "#89B4FA");
            p.setProperty("color13", "#F5C2E7");
            p.setProperty("color14", "#94E2D5");
            p.setProperty("color15", "#A6ADC8");
            return p;
        }
        if (SCHEME_GRUVBOX.equals(schemeId)) {
            p.setProperty("background", "#1D2021");
            p.setProperty("foreground", "#EBDBB2");
            p.setProperty("cursor", "#FE8019");
            p.setProperty("color0", "#282828");
            p.setProperty("color1", "#CC241D");
            p.setProperty("color2", "#98971A");
            p.setProperty("color3", "#D79921");
            p.setProperty("color4", "#458588");
            p.setProperty("color5", "#B16286");
            p.setProperty("color6", "#689D6A");
            p.setProperty("color7", "#A89984");
            p.setProperty("color8", "#928374");
            p.setProperty("color9", "#FB4934");
            p.setProperty("color10", "#B8BB26");
            p.setProperty("color11", "#FABD2F");
            p.setProperty("color12", "#83A598");
            p.setProperty("color13", "#D3869B");
            p.setProperty("color14", "#8EC07C");
            p.setProperty("color15", "#EBDBB2");
            return p;
        }
        if (SCHEME_SOLARIZED_LIGHT.equals(schemeId)) {
            p.setProperty("background", "#FDF6E3");
            p.setProperty("foreground", "#586E75");
            p.setProperty("cursor", "#D70A53");
            p.setProperty("color0", "#073642");
            p.setProperty("color1", "#DC322F");
            p.setProperty("color2", "#859900");
            p.setProperty("color3", "#B58900");
            p.setProperty("color4", "#268BD2");
            p.setProperty("color5", "#D33682");
            p.setProperty("color6", "#2AA198");
            p.setProperty("color7", "#EEE8D5");
            p.setProperty("color8", "#002B36");
            p.setProperty("color9", "#CB4B16");
            p.setProperty("color10", "#586E75");
            p.setProperty("color11", "#657B83");
            p.setProperty("color12", "#839496");
            p.setProperty("color13", "#6C71C4");
            p.setProperty("color14", "#93A1A1");
            p.setProperty("color15", "#FDF6E3");
            return p;
        }

        // Default: Debian Dark
        p.setProperty("background", "#0D1117");
        p.setProperty("foreground", "#E6EDF3");
        p.setProperty("cursor", "#D70A53");
        p.setProperty("color0", "#21262D");
        p.setProperty("color1", "#FF7B72");
        p.setProperty("color2", "#3FB950");
        p.setProperty("color3", "#D29922");
        p.setProperty("color4", "#58A6FF");
        p.setProperty("color5", "#BC8CFF");
        p.setProperty("color6", "#39C5CF");
        p.setProperty("color7", "#B1BAC4");
        p.setProperty("color8", "#6E7681");
        p.setProperty("color9", "#FFA198");
        p.setProperty("color10", "#56D364");
        p.setProperty("color11", "#E3B341");
        p.setProperty("color12", "#79C0FF");
        p.setProperty("color13", "#D2A8FF");
        p.setProperty("color14", "#56D4DD");
        p.setProperty("color15", "#F0F6FC");
        return p;
    }
}
