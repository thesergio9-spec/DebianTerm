# DebianTerm

**DebianTerm** (v2.0.1) es un **instalador completo de Debian 12 (Bookworm)** y emulador de terminal para Android basado en el motor y teclado de Termux (`terminal-emulator` y `terminal-view`), ahora reestructurado con **verificación de requerimientos mínimos al iniciar** y **dos caminos de ejecución totalmente funcionales**:

1. **Camino 1 — Con Root (Chroot Nativo)**: máximo rendimiento del kernel Linux y montajes reales para dispositivos con Magisk, KernelSU o APatch.
2. **Camino 2 — Sin Root (PRoot Integrado)**: motor `PRoot` empaquetado directamente en el APK (`arm64-v8a` y `armeabi-v7a`), **100% independiente de Termux**, para dispositivos sin acceso root.

Coexiste de forma independiente junto a Termux (`com.termux`) usando su propio paquete (`com.debianchroot.term`) y sin modificar instalaciones existentes en `/data/local/debian`.

---

## 📥 Descarga Rápida

Descarga el APK compilado desde la última Release:
- **[DebianTerm.apk (v2.0.1)](https://github.com/thesergio9-spec/DebianTerm/releases/download/v2.0.1/DebianTerm.apk)**

---

## 🚀 Características Principales (v2.0.0)

### 1. Panel de Inicio y Verificación de Requerimientos Mínimos
Al abrir la app se ejecuta automáticamente un diagnóstico (`SystemRequirements`) antes de instalar o iniciar Debian 12:
- **Sistema Android**: mínimo Android 7.0 (API 24).
- **Arquitectura CPU**: `arm64-v8a`, `armeabi-v7a` (y soporte `x86_64`/`x86` según binarios).
- **Memoria RAM**: mínimo 512 MB (recomendado ≥ 2 GB).
- **Almacenamiento interno**: mínimo 800 MB libres para nueva instalación (recomendado ≥ 1.5 GB).
- **Red y Mirror LXC**: comprobación de conectividad hacia `images.linuxcontainers.org`.
- **Detección Dual**:
  - **Camino 1 (Root `su`)**: detecta Magisk (`/debug_ramdisk/su`), KernelSU o APatch con su versión.
  - **Camino 2 (PRoot)**: verifica los binarios integrados `libproot.so` y `libproot_loader.so`.
- Incluye un interruptor opcional (**"Entrar automáticamente a Debian 12 al iniciar"**) para conservar el panel al inicio o abrir la terminal directamente si ya está instalado.

### 2. Dos Caminos de Instalación y Ejecución Debian 12

#### Camino 1: Con Root (Chroot Nativo)
- Detecta instalaciones existentes en `/data/local/debian` (o la ruta personalizada) sin sobrescribir ni desmontar destructivamente tus datos.
- Descarga imágenes oficiales de **Debian 12 (Bookworm)** (o Debian 13 Testing en gestión avanzada) desde `images.linuxcontainers.org` verificadas por `SHA256SUMS`.
- Genera un script de entrada seguro (`/data/local/bin/debian`) con aislamiento `env -i`, timeouts en montajes (`timeout 5 mount`), contador de sesiones en `/dev/chroot_sessions` con **desmontaje automático seguro al cerrar la última sesión** y subcomandos `status`, `stop`/`umount`, `exec`.
- Ejecución directa en contexto `su` para evitar denegaciones SELinux en Android 10–15.
- Cierre suave de sesiones (`finishGracefully`) enviando `SIGHUP`/`SIGTERM` antes del fallback `SIGKILL` al cerrar pestañas con `✕` o pulsar `Salir` en la notificación, permitiendo guardar el historial de `bash` y ejecutar trampas de limpieza.

#### Camino 2: Sin Root (PRoot Integrado en el APK)
- Incluye `libproot.so`, `libproot_loader.so` y `libproot_loader32.so` (`arm64-v8a` y `armeabi-v7a`) compilados con `targetSdkVersion=28` para permitir ejecución y mapeo binario en almacenamiento interno (`untrusted_app_27`) en Android 10–15.
- Extractor **100% Java (`TarExtractor`)** con soporte USTAR, PAX, GNU LongName, permisos seguros de usuario, symlinks, hardlinks, exclusión segura de `/dev/*` y protección contra *path traversal*.
- Entorno preconfigurado en `<filesDir>/debian12-proot`: DNS (`1.1.1.1`, `8.8.8.8`), grupos Android (`aid_inet`), APT (`APT::Sandbox::User "root"`, `policy-rc.d`), detección automática de compatibilidad `PROOT_NO_SECCOMP` y archivos virtuales `/proc/*` para herramientas CLI (`ps`, `top`, `free`, `apt`, `python3`).

### 3. Corrección del "Rebobinado" al Abrir/Cerrar el Teclado
En emuladores de terminal móviles convencionales, abrir o cerrar el teclado dispara `SIGWINCH`, forzando recálculo de filas y un "rebobinado" visual del buffer histórico:
- **Modo Pan sin `SIGWINCH` (`soft-keyboard-pan-main-screen`)**: desacopla las filas visibles (`visualRows`) de las filas reales del PTY.
- **Salida Sincronizada (`DECSET 2026`)**: soporte completo `CSI ? 2026 h/l` y `DECRQM`.
- **Scrollback Anclado a Filas de Documento**: desplazamiento suave con `OverScroller` manteniendo la posición exacta de lectura.

### 4. Teclado Extra Estilo Termux y Múltiples Sesiones
- Barra extra con **Modo Completo (2 filas)**, **Modo Compacto (1 fila)** y **Modo Oculto**, teclas `CTRL`/`ALT` con estado *Latch* y *Locked*, auto-repetición y vibración háptica configurable.
- **Slide Drawer** para alternar entre sesiones Debian 12 (Root / Sin Root), shell Android (`su` / `sh`) y volver al panel de inicio en cualquier momento.
- **Servicio en primer plano** con notificación persistente, *Wake Lock* y 5 temas de color (*Debian Dark*, *AMOLED*, *Catppuccin Mocha*, *Gruvbox Dark*, *Solarized Light*).

---

## 🛠️ Estructura del Proyecto

```
DebianTerm/
├── AndroidManifest.xml          # Configuración com.debianchroot.term (targetSdk 28 para PRoot)
├── res/                         # Iconos vectoriales, colores, estilos y cadenas
├── src/
│   ├── com/debianchroot/term/
│   │   ├── MainActivity.java        # Panel de inicio/requisitos + UI terminal + diálogos Root/PRoot
│   │   ├── SystemRequirements.java  # Verificador de requerimientos mínimos del dispositivo
│   │   ├── ChrootManager.java       # Detección root (su), inspección chroot y script de entrada
│   │   ├── ProotManager.java        # Gestión del entorno sin root Debian 12 + lanzador PRoot
│   │   ├── ChrootInstaller.java     # Descarga LXC + verificación SHA-256 (Root y PRoot)
│   │   ├── TarExtractor.java        # Extractor tar.xz puro en Java para instalaciones sin root
│   │   ├── DebianTermService.java   # Servicio en primer plano para sesiones Root, PRoot y sh
│   │   ├── TerminalSettings.java    # Preferencias persistentes y esquemas de color
│   │   ├── ExtraKeysBarView.java    # Barra de teclas extra estilo Termux
│   │   └── SlideDrawerLayout.java   # Menú lateral deslizable de sesiones
│   ├── com/termux/terminal/     # Motor de emulación PTY con DECSET 2026 y modo Pan
│   └── com/termux/view/         # Vista terminal con anclaje de scrollback y gestos
├── libs/                        # Bibliotecas JAR (annotation-1.5.0, xz-1.9)
├── jniLibs/                     # Binarios nativos arm64-v8a y armeabi-v7a:
│   │                            # libtermux.so, libproot.so, libproot_loader.so, libproot_loader32.so
├── tests/                       # Suite de 8 pruebas unitarias en JVM (VerifyAll.java)
├── setup-deps.sh                # Descarga código base Termux, JARs, libtermux.so y binarios PRoot
└── build.sh                     # Compilación completa (aapt2, javac, d8, zipalign, apksigner)
```

---

## 📦 Compilación desde la Terminal

El proyecto se compila directamente tanto en PC como en un entorno Linux dentro de Android (Debian Chroot o Termux):

```bash
# 1. Clonar el repositorio
git clone https://github.com/thesergio9-spec/DebianTerm.git
cd DebianTerm

# 2. Descargar librerías, componentes base y binarios PRoot verificados por SHA-256
./setup-deps.sh

# 3. Compilar, alinear y firmar el APK
./build.sh
```

El APK resultante se generará en `build/DebianTerm-release.apk`.

---

## 🧪 Pruebas Unitarias

La suite [VerifyAll.java](tests/VerifyAll.java) valida en JVM las **9 pruebas clave**:
1. Salida sincronizada `DECSET 2026` + consultas `DECRQM`.
2. Modo Pan del teclado sin pérdida de scrollback ni redimensionamiento PTY (`SIGWINCH`).
3. Parser en vivo del índice LXC (`bookworm` / `trixie`) y verificación `SHA256SUMS`.
4. Sintaxis (`sh -n`), aislamiento `env -i`, contador de sesiones y trampa de desmontaje automático del script de entrada root.
5. Carga de los 5 esquemas de colores en `TerminalColorScheme`.
6. Evaluador de requisitos mínimos (`SystemRequirements`) en dispositivos con root, sin root (PRoot), poco almacenamiento y Android antiguo.
7. Extractor tar Java (`TarExtractor`: permisos, symlinks, hardlinks, rutas largas PAX > 120 caracteres, omisión de `/dev/*`, bloqueo de *path traversal* y borrado seguro sin seguir symlinks).
8. Sintaxis (`sh -n`) y flags del script lanzador PRoot sin root (`ProotManager`).
9. API de cierre suave de sesiones (`TerminalSession.finishGracefully` con `SIGHUP`/`SIGTERM` y fallback `SIGKILL`).

Para compilarlas y ejecutarlas:

```bash
javac -encoding UTF-8 -cp build/classes:/opt/android-sdk/android.jar:libs/xz-1.9.jar -d build/test-classes tests/VerifyAll.java
java -cp build/test-classes:build/classes:/opt/android-sdk/android.jar:libs/xz-1.9.jar VerifyAll
```

---

## 📄 Licencia

Este proyecto reutiliza componentes de código abierto de [Termux](https://github.com/termux/termux-app) y [PRoot](https://github.com/termux/proot) licenciados bajo **GPLv2+ / GPLv3**.
