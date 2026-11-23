# DebianTerm

**DebianTerm** es una app ligera de terminal para Android basada en el motor y teclado de Termux (`terminal-emulator` y `terminal-view`), diseñada específicamente para operar como un entorno e instalador de **Debian Chroot** completo y de alto rendimiento en dispositivos Android con acceso root (Magisk / KernelSU / APatch).

Coexiste de forma independiente junto a cualquier instalación de Termux oficial (`com.termux`), usando su propio identificador de paquete (`com.debianchroot.term`).

---

## 🚀 Características Principales

### 1. Corrección del "Rebobinado" al Abrir/Cerrar el Teclado
En emuladores de terminal móviles convencionales, al abrir o cerrar el teclado en pantalla el sistema redimensiona el layout disparando un `SIGWINCH` al proceso hijo y forzando a la terminal a recalcular filas y redibujar el buffer histórico desde el principio.

DebianTerm soluciona este comportamiento incorporando:
- **Modo Pan sin `SIGWINCH` (`soft-keyboard-pan-main-screen`)**: Desacopla la ventana visible (`visualRows`) de las dimensiones reales del PTY en la pantalla principal. Al desplegar el teclado táctil, el tamaño de líneas del shell no se destruye ni se reenvían señales de tamaño erróneas a CLI interactivas (Hermes, Claude, Vim, prompts enriquecidos).
- **Salida Sincronizada (`DECSET 2026`)**: Soporte para `CSI ? 2026 h/l` y `DECRQM`, permitiendo frames atómicos y erradicando el parpadeo de texto intermedio.
- **Scrollback Anclado a Filas de Documento**: Desplazamiento ultra suave con `OverScroller` sub-pixel y retención exacta de la posición de lectura.

### 2. Teclado Extra Estilo Termux
Barra inferior de teclas configurables diseñada para productividad en línea de comandos:
- **Modo Completo (2 filas)**:
  - Fila 1: `ESC` (`~`), `/` (`|`), `-` (`_`), `HOME`, `▲`, `END`, `PGUP`
  - Fila 2: `TAB` (`S-TAB`), `CTRL`, `ALT`, `◄`, `▼`, `►`, `PGDN`
- **Modo Compacto (1 fila)** y **Modo Oculto**.
- **Modificadores Inteligentes (`CTRL` / `ALT`)**:
  - 1 toque: Estado **Latch** (se activa para la siguiente tecla y se libera automáticamente).
  - 2 toques / Pulsación larga: Estado **Locked** (fijo con indicador visual `CTRL•`).
- **Auto-repetición continua** en teclas de dirección y paginación.
- **Respuesta háptica** configurable.

### 3. Instalador y Gestor de Debian Chroot Integrado
- **Detección Automática**: Detecta instalaciones existentes en `/data/local/debian` (o la ruta elegida) y abre sesión sin tocar datos existentes.
- **Descarga e Instalación Oficial en 1 Clic**:
  - Descarga automática de imágenes oficiales desde `images.linuxcontainers.org` (soporte para **Debian 12 Bookworm** y **Debian 13 Trixie** en arquitecturas `arm64` y `armhf`).
  - Verificación de integridad vía `SHA256SUMS`.
  - Extracción directa por streaming `tar.xz` sin necesidad de utilidades externas pesadas.
  - Configuración automática de DNS (`1.1.1.1`, `8.8.8.8`), hosts y mapeo de grupos de red de Android (`aid_inet` GID 3003, etc.).
- **Script de Entrada Seguro (`/data/local/bin/debian`)**:
  - Montajes limpios con timeouts (`timeout 5 mount`) para evitar congelamientos en almacenamiento FUSE.
  - Aislamiento de variables de entorno (`env -i`) previniendo fugas de entornos Termux u host.
  - Subcomandos de gestión rápida: `status`, `umount`, `exec <comando>`.

### 4. Múltiples Sesiones y Personalización
- **Panel lateral deslizable (Slide Drawer)** para alternar entre sesiones de Debian Chroot y shells locales de Android Root (`su`).
- **Servicio en primer plano** con notificación persistente para mantener tareas y daemons vivos en segundo plano y toggle de **Wake Lock**.
- **Zoom fluido con pellizco** táctil para ajustar tamaño de fuente en tiempo real.
- **5 Esquemas de Color**:
  - *Debian Dark* (por defecto)
  - *AMOLED Negro Puro*
  - *Catppuccin Mocha*
  - *Gruvbox Dark*
  - *Solarized Light*

---

## 🛠️ Estructura del Proyecto

```
debian-termux/
├── AndroidManifest.xml          # Configuración del paquete com.debianchroot.term
├── res/                         # Iconos vectoriales, colores, estilos y cadenas
├── src/
│   ├── com/debianchroot/term/   # Lógica principal, UI, Servicio, Instalador y Gestor Chroot
│   ├── com/termux/terminal/     # Motor de emulación PTY con soporte DECSET 2026 y Pan
│   └── com/termux/view/         # Vista terminal con anclaje de scrollback y gestos
├── libs/                        # Bibliotecas JAR (annotation-1.5.0, xz-1.9)
├── jniLibs/                     # Binarios libtermux.so (arm64-v8a, armeabi-v7a)
├── tests/                       # Suite de pruebas unitarias en JVM (VerifyAll.java)
├── setup-deps.sh                # Descarga y preparación de código base y librerías
└── build.sh                     # Compilación completa (aapt2, javac, d8, zipalign, apksigner)
```

---

## 📦 Compilación desde la Terminal

El proyecto está diseñado para compilarse tanto en PC como directamente dentro de un entorno Linux en Android (incluso en Debian Chroot o Termux):

```bash
# 1. Clonar el repositorio
git clone <URL_REPOSITORIO>
cd debian-termux

# 2. Descargar librerías y componentes base
./setup-deps.sh

# 3. Compilar, alinear y firmar el APK
./build.sh
```

El APK resultante se generará en `build/DebianTerm-release.apk`.

---

## 🧪 Pruebas Unitarias

Para ejecutar la batería de pruebas en JVM que valida la salida sincronizada `DECSET 2026`, la retención de scrollback en resize del teclado y el parser de índices LXC:

```bash
javac -cp build/classes:/opt/android-sdk/android.jar:libs/xz-1.9.jar -d build/test-classes tests/VerifyAll.java
java -cp build/test-classes:build/classes:/opt/android-sdk/android.jar:libs/xz-1.9.jar VerifyAll
```

---

## 📄 Licencia

Este proyecto reutiliza componentes de código abierto del proyecto [Termux](https://github.com/termux/termux-app) licenciados bajo **GPLv3**.
