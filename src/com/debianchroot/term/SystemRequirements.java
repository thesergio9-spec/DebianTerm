package com.debianchroot.term;

import android.app.ActivityManager;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.os.Build;
import android.os.StatFs;

import java.io.File;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Evaluates device hardware, OS, storage, network, root, and bundled PRoot readiness
 * against the minimum requirements for running Debian 12 (Bookworm).
 */
public final class SystemRequirements {

    public static final int MIN_API_LEVEL = 24;
    public static final long MIN_RAM_MB = 512L;
    public static final long RECOMMENDED_RAM_MB = 2048L;
    public static final long MIN_STORAGE_FOR_INSTALL_MB = 800L;
    public static final long RECOMMENDED_STORAGE_MB = 1500L;

    public enum Status {
        PASS,
        WARN,
        FAIL,
        INFO
    }

    public static final class CheckItem {
        public final String id;
        public final String title;
        public final String value;
        public final String detail;
        public final Status status;

        public CheckItem(String id, String title, String value, String detail, Status status) {
            this.id = id;
            this.title = title;
            this.value = value;
            this.detail = detail;
            this.status = status;
        }
    }

    public static final class Report {
        public final List<CheckItem> items;
        public int apiLevel;
        public String androidVersion = "";
        public String primaryAbi = "";
        public String debianArch = "";
        public long totalRamMb;
        public long availRamMb;
        public long freeStorageMb;
        public long totalStorageMb;
        public boolean hasNetwork;
        public boolean mirrorReachable;
        public boolean hasRoot;
        public String suPath = "";
        public String suVersion = "";
        public boolean prootBinariesReady;
        public ChrootManager.ChrootStatus rootStatus = new ChrootManager.ChrootStatus();
        public ProotManager.ProotStatus prootStatus = new ProotManager.ProotStatus();
        public DesktopManager.DesktopStatus desktopStatus = new DesktopManager.DesktopStatus();
        public boolean ignoringBatteryOptimizations;
        public boolean notificationsEnabled;
        public boolean sharedStorageGranted;
        public PhantomProcessManager.Status phantomStatus;

        public Report(List<CheckItem> items) {
            this.items = Collections.unmodifiableList(new ArrayList<>(items));
        }

        public boolean meetsMinimumRequirements() {
            return apiLevel >= MIN_API_LEVEL
                && isSupportedAbi(primaryAbi)
                && totalRamMb >= MIN_RAM_MB;
        }

        public boolean hasEnoughStorageForInstall() {
            return freeStorageMb >= MIN_STORAGE_FOR_INSTALL_MB;
        }

        public boolean canUseRootPath() {
            return meetsMinimumRequirements() && hasRoot;
        }

        public boolean canUseProotPath() {
            return meetsMinimumRequirements() && prootBinariesReady;
        }

        public String recommendedMode() {
            return hasRoot ? TerminalSettings.MODE_ROOT : TerminalSettings.MODE_PROOT;
        }
    }

    private static volatile boolean sCachedMirrorReachable = false;
    private static volatile long sCachedMirrorAtMs = 0L;

    private SystemRequirements() {}

    public static Report evaluateBaseChecks(
        int apiLevel,
        String androidVersion,
        String primaryAbi,
        long totalRamMb,
        long availRamMb,
        long freeStorageMb,
        long totalStorageMb,
        boolean hasNetwork,
        boolean mirrorReachable,
        boolean hasRoot,
        String suPath,
        String suVersion,
        boolean prootBinariesReady,
        boolean anyRootfsAlreadyInstalled
    ) {
        List<CheckItem> items = new ArrayList<>();

        // 1. Android OS
        Status osStatus = apiLevel >= MIN_API_LEVEL ? Status.PASS : Status.FAIL;
        String verLabel = (androidVersion == null || androidVersion.isEmpty())
            ? ("API " + apiLevel)
            : ("Android " + androidVersion + " (API " + apiLevel + ")");
        items.add(new CheckItem(
            "os",
            "Sistema Android",
            verLabel,
            apiLevel >= MIN_API_LEVEL
                ? "Compatible (mínimo Android 7.0 / API 24)"
                : "Requiere al menos Android 7.0 (API 24)",
            osStatus
        ));

        // 2. CPU ABI
        String debArch = abiToDebianArch(primaryAbi);
        Status abiStatus;
        String abiDetail;
        if ("arm64-v8a".equals(primaryAbi)) {
            abiStatus = Status.PASS;
            abiDetail = "Arquitectura nativa 64-bit recomendada";
        } else if ("armeabi-v7a".equals(primaryAbi) || "x86_64".equals(primaryAbi) || "x86".equals(primaryAbi)) {
            abiStatus = Status.WARN;
            abiDetail = "Soporte secundario (" + debArch + "); binarios PRoot empaquetados para ARM/ARM64";
        } else {
            abiStatus = Status.FAIL;
            abiDetail = "ABI no soportada para Debian 12 Chroot/PRoot";
        }
        items.add(new CheckItem(
            "abi",
            "Arquitectura CPU",
            (primaryAbi == null || primaryAbi.isEmpty() ? "desconocida" : primaryAbi) + " → Debian " + debArch,
            abiDetail,
            abiStatus
        ));

        // 3. RAM
        Status ramStatus;
        String ramDetail;
        if (totalRamMb >= RECOMMENDED_RAM_MB) {
            ramStatus = Status.PASS;
            ramDetail = "Memoria óptima para apt, compilación y sesiones CLI";
        } else if (totalRamMb >= MIN_RAM_MB) {
            ramStatus = Status.WARN;
            ramDetail = "Memoria justa (< 2 GB); se recomienda evitar compilaciones pesadas";
        } else {
            ramStatus = Status.FAIL;
            ramDetail = "Inferior al mínimo recomendado de 512 MB";
        }
        items.add(new CheckItem(
            "ram",
            "Memoria RAM",
            totalRamMb + " MB totales (" + availRamMb + " MB libres)",
            ramDetail,
            ramStatus
        ));

        // 4. Storage
        Status storageStatus;
        String storageDetail;
        if (freeStorageMb >= RECOMMENDED_STORAGE_MB) {
            storageStatus = Status.PASS;
            storageDetail = "Espacio suficiente para rootfs base y paquetes Debian 12";
        } else if (freeStorageMb >= MIN_STORAGE_FOR_INSTALL_MB) {
            storageStatus = Status.WARN;
            storageDetail = "Espacio justo (mínimo 800 MB; recomendado ≥ 1.5 GB)";
        } else {
            storageStatus = Status.FAIL;
            storageDetail = anyRootfsAlreadyInstalled
                ? "Menos de 800 MB libres para nueva instalación (un Debian ya instalado sigue funcionando)"
                : "Se requieren al menos 800 MB libres para descargar y extraer Debian 12";
        }
        items.add(new CheckItem(
            "storage",
            "Almacenamiento interno",
            formatMb(freeStorageMb) + " libres / " + formatMb(totalStorageMb),
            storageDetail,
            storageStatus
        ));

        // 5. Network & Debian Mirror
        Status netStatus;
        String netVal;
        String netDetail;
        if (hasNetwork && mirrorReachable) {
            netStatus = Status.PASS;
            netVal = "Conectado • Mirror LXC OK";
            netDetail = "Acceso verificado a images.linuxcontainers.org";
        } else if (hasNetwork) {
            netStatus = Status.WARN;
            netVal = "Con red • Mirror sin respuesta";
            netDetail = "No se pudo contactar images.linuxcontainers.org (necesario solo al instalar)";
        } else {
            netStatus = Status.WARN;
            netVal = "Sin conexión";
            netDetail = "Necesitarás internet para descargar Debian 12 por primera vez";
        }
        items.add(new CheckItem("network", "Red y Repositorio", netVal, netDetail, netStatus));

        // 6. Root (Path 1)
        Status rootStatusEnum = hasRoot ? Status.PASS : Status.INFO;
        String rootVal = hasRoot
            ? "Detectado (" + (suPath == null || suPath.isEmpty() ? "su" : suPath) + ")"
            : "No detectado";
        String rootDetail;
        if (hasRoot) {
            rootDetail = (suVersion != null && !suVersion.isEmpty())
                ? "Camino 1 (Chroot nativo) habilitado • " + suVersion
                : "Camino 1 (Chroot nativo) habilitado";
        } else {
            rootDetail = "Sin root usarás el Camino 2 (PRoot integrado); si usas Magisk/KernelSU concede permiso";
        }
        items.add(new CheckItem("root", "Camino 1: Acceso Root (su)", rootVal, rootDetail, rootStatusEnum));

        // 7. PRoot Engine (Path 2)
        Status prootCheckStatus = prootBinariesReady ? Status.PASS : Status.FAIL;
        items.add(new CheckItem(
            "proot",
            "Camino 2: Motor PRoot",
            prootBinariesReady ? "Integrado en APK" : "No disponible",
            prootBinariesReady
                ? "Listo para instalar y ejecutar Debian 12 sin root"
                : "Falta binario libproot.so para la arquitectura del dispositivo",
            prootCheckStatus
        ));

        Report report = new Report(items);
        report.apiLevel = apiLevel;
        report.androidVersion = androidVersion != null ? androidVersion : "";
        report.primaryAbi = primaryAbi != null ? primaryAbi : "";
        report.debianArch = debArch;
        report.totalRamMb = totalRamMb;
        report.availRamMb = availRamMb;
        report.freeStorageMb = freeStorageMb;
        report.totalStorageMb = totalStorageMb;
        report.hasNetwork = hasNetwork;
        report.mirrorReachable = mirrorReachable;
        report.hasRoot = hasRoot;
        report.suPath = suPath != null ? suPath : "";
        report.suVersion = suVersion != null ? suVersion : "";
        report.prootBinariesReady = prootBinariesReady;
        return report;
    }

    public static Report runCheck(Context context, TerminalSettings settings) {
        return runCheck(context, settings, true);
    }

    public static Report runCheck(Context context, TerminalSettings settings, boolean forceRefresh) {
        String primaryAbi = (Build.SUPPORTED_ABIS != null && Build.SUPPORTED_ABIS.length > 0)
            ? Build.SUPPORTED_ABIS[0]
            : "arm64-v8a";

        long totalRamMb = 0L;
        long availRamMb = 0L;
        ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        if (am != null) {
            ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
            am.getMemoryInfo(mi);
            totalRamMb = mi.totalMem / (1024L * 1024L);
            availRamMb = mi.availMem / (1024L * 1024L);
        }

        File filesDir = context.getFilesDir();
        StatFs stat = new StatFs(filesDir.getAbsolutePath());
        long freeStorageMb = stat.getAvailableBytes() / (1024L * 1024L);
        long totalStorageMb = stat.getTotalBytes() / (1024L * 1024L);

        boolean hasNetwork = checkNetworkAvailable(context);
        boolean mirrorReachable = false;
        if (hasNetwork) {
            long now = System.currentTimeMillis();
            if (!forceRefresh && sCachedMirrorAtMs > 0L
                    && (now - sCachedMirrorAtMs) < ChrootManager.ROOT_STATUS_CACHE_TTL_MS) {
                mirrorReachable = sCachedMirrorReachable;
            } else {
                mirrorReachable = checkMirrorReachable();
                sCachedMirrorReachable = mirrorReachable;
                sCachedMirrorAtMs = now;
            }
        }

        ChrootManager.ChrootStatus rootSt = ChrootManager.inspectStatusCached(
            settings.getChrootPath(),
            settings.getEntryScriptPath(),
            forceRefresh
        );
        ProotManager.ProotStatus prootSt = ProotManager.inspectStatus(context);

        Report report = evaluateBaseChecks(
            Build.VERSION.SDK_INT,
            Build.VERSION.RELEASE,
            primaryAbi,
            totalRamMb,
            availRamMb,
            freeStorageMb,
            totalStorageMb,
            hasNetwork,
            mirrorReachable,
            rootSt.hasRoot,
            rootSt.suPath,
            rootSt.suVersion,
            prootSt.binariesAvailable,
            rootSt.rootfsExists || prootSt.rootfsExists
        );
        report.rootStatus = rootSt;
        report.prootStatus = prootSt;
        DesktopManager.DesktopStatus deskSt = DesktopManager.inspectDesktopStatus(
            context, report, settings.getChrootPath());
        boolean ignoringDoze = DebianTermService.isIgnoringBatteryOptimizations(context);
        boolean notifOk = DebianTermService.areNotificationsEnabled(context);
        boolean storageGranted = ProotManager.hasSharedStorageAccess(context);
        PhantomProcessManager.Status phantomSt = PhantomProcessManager.inspect(context, rootSt);

        List<CheckItem> fullItems = new ArrayList<>(report.items);
        if (deskSt.builtInX11Available) {
            fullItems.add(new CheckItem(
                "gui",
                "Modo Gráfico (Integrado)",
                "X11 Nativo (60 FPS) + VNC",
                "Motor X11 nativo (Lorie) y visor VNC integrados — sin apps externas",
                Status.PASS
            ));
        } else if (deskSt.termuxX11Installed) {
            fullItems.add(new CheckItem(
                "gui",
                "Modo Gráfico (Híbrido)",
                "Termux:X11 v" + deskSt.termuxX11Version + " + VNC",
                "Visor VNC integrado y aceleración Termux:X11 detectados",
                Status.PASS
            ));
        } else {
            fullItems.add(new CheckItem(
                "gui",
                "Modo Gráfico (Híbrido)",
                "Visor VNC listo (Termux:X11 opcional)",
                "VNC integrado disponible ya; puedes descargar Termux:X11 APK para 60 FPS",
                Status.INFO
            ));
        }
        if (ignoringDoze && notifOk) {
            fullItems.add(new CheckItem(
                "power",
                "Segundo plano y Wake Lock",
                "Sin restricciones • Notif. OK",
                "Excluida de Doze y notificaciones activas para mantener CPU/Wi-Fi en segundo plano",
                Status.PASS
            ));
        } else {
            fullItems.add(new CheckItem(
                "power",
                "Segundo plano y Wake Lock",
                (ignoringDoze ? "Sin Doze" : "Optimizada (Doze)") + " • " + (notifOk ? "Notif. OK" : "Notif. bloqueadas"),
                "Para evitar cortes al apagar la pantalla, desactiva la optimización de batería y activa notificaciones.",
                Status.WARN
            ));
        }
        if (storageGranted) {
            fullItems.add(new CheckItem(
                "shared_storage",
                "Almacenamiento compartido (/sdcard)",
                "Concedido (PRoot + Root OK)",
                "Acceso completo a /sdcard y ~/storage listo para Camino 1 y Camino 2",
                Status.PASS
            ));
        } else if (report.hasRoot) {
            fullItems.add(new CheckItem(
                "shared_storage",
                "Almacenamiento compartido (/sdcard)",
                "Root OK • Sin permiso PRoot",
                "Camino 1 (Root) ya monta /sdcard; usa setup-storage si quieres /sdcard en Camino 2 (Sin Root)",
                Status.INFO
            ));
        } else {
            fullItems.add(new CheckItem(
                "shared_storage",
                "Almacenamiento compartido (/sdcard)",
                "Pendiente (setup-storage)",
                "Concede acceso a archivos para leer y escribir /sdcard y ~/storage en Camino 2 (Sin Root)",
                Status.WARN
            ));
        }
        if (!phantomSt.affectedApi) {
            fullItems.add(new CheckItem(
                "phantom",
                "Procesos en segundo plano (Phantom)",
                phantomSt.summary,
                "Tu versión de Android no tiene Phantom Process Killer.",
                Status.PASS
            ));
        } else if (phantomSt.knownMitigated) {
            fullItems.add(new CheckItem(
                "phantom",
                "Phantom Process Killer (Android 12+)",
                phantomSt.summary,
                "Protegido contra cierres inesperados (signal 9) en sesiones largas y PRoot.",
                Status.PASS
            ));
        } else {
            fullItems.add(new CheckItem(
                "phantom",
                "Phantom Process Killer (Android 12+)",
                phantomSt.summary,
                report.hasRoot
                    ? "Puede matar procesos con signal 9. Pulsa 'Corregir Phantom Killer' para desactivarlo con root."
                    : "Puede matar sesiones PRoot con signal 9. Pulsa el botón de abajo para desactivarlo en Opciones de desarrollador o ADB.",
                Status.WARN
            ));
        }
        Report fullReport = new Report(fullItems);
        fullReport.apiLevel = report.apiLevel;
        fullReport.androidVersion = report.androidVersion;
        fullReport.primaryAbi = report.primaryAbi;
        fullReport.debianArch = report.debianArch;
        fullReport.totalRamMb = report.totalRamMb;
        fullReport.availRamMb = report.availRamMb;
        fullReport.freeStorageMb = report.freeStorageMb;
        fullReport.totalStorageMb = report.totalStorageMb;
        fullReport.hasNetwork = report.hasNetwork;
        fullReport.mirrorReachable = report.mirrorReachable;
        fullReport.hasRoot = report.hasRoot;
        fullReport.suPath = report.suPath;
        fullReport.suVersion = report.suVersion;
        fullReport.prootBinariesReady = report.prootBinariesReady;
        fullReport.rootStatus = rootSt;
        fullReport.prootStatus = prootSt;
        fullReport.desktopStatus = deskSt;
        fullReport.ignoringBatteryOptimizations = ignoringDoze;
        fullReport.notificationsEnabled = notifOk;
        fullReport.sharedStorageGranted = storageGranted;
        fullReport.phantomStatus = phantomSt;
        return fullReport;
    }

    public static boolean isSupportedAbi(String abi) {
        return "arm64-v8a".equals(abi) || "armeabi-v7a".equals(abi)
            || "x86_64".equals(abi) || "x86".equals(abi);
    }

    public static String abiToDebianArch(String abi) {
        if ("arm64-v8a".equals(abi)) return "arm64";
        if ("armeabi-v7a".equals(abi)) return "armhf";
        if ("x86_64".equals(abi)) return "amd64";
        if ("x86".equals(abi)) return "i386";
        return "arm64";
    }

    private static String formatMb(long mb) {
        if (mb >= 1024L) {
            return String.format(Locale.US, "%.1f GB", mb / 1024.0);
        }
        return mb + " MB";
    }

    @SuppressWarnings("deprecation")
    private static boolean checkNetworkAvailable(Context context) {
        try {
            ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return false;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                Network active = cm.getActiveNetwork();
                if (active == null) return false;
                NetworkCapabilities caps = cm.getNetworkCapabilities(active);
                return caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
            } else {
                NetworkInfo ni = cm.getActiveNetworkInfo();
                return ni != null && ni.isConnected();
            }
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean checkMirrorReachable() {
        HttpURLConnection conn = null;
        try {
            URL url = new URL("https://images.linuxcontainers.org/meta/1.0/index-system");
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("HEAD");
            conn.setConnectTimeout(3500);
            conn.setReadTimeout(3500);
            conn.setRequestProperty("User-Agent", "DebianTerm/2.0");
            int code = conn.getResponseCode();
            return code >= 200 && code < 400;
        } catch (Exception e) {
            return false;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }
}
