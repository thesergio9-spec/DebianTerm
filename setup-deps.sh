#!/bin/bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$ROOT_DIR"

mkdir -p \
  src/com/termux/terminal \
  src/com/termux/view/support \
  src/com/termux/view/textselection \
  src/com/debianchroot/term \
  res/drawable \
  res/layout \
  res/values \
  res/xml \
  libs \
  jniLibs/arm64-v8a \
  jniLibs/armeabi-v7a \
  build \
  tests

TMP_DIR="$(mktemp -d /tmp/debianterm-deps.XXXXXX)"
cleanup() {
  rm -rf "$TMP_DIR"
}
trap cleanup EXIT

echo "[1/4] Descargando termux-app commit 952f309e6b..."
TERMUX_TAR="$TMP_DIR/termux-952f309e6b.tar.gz"
curl -fsSL -L "https://github.com/qwerty123yucy/termux-app/archive/952f309e6b.tar.gz" -o "$TERMUX_TAR"
tar -xzf "$TERMUX_TAR" -C "$TMP_DIR"
TERMUX_SRC="$(find "$TMP_DIR" -maxdepth 1 -type d -name 'termux-app-*' | head -n 1)"
if [ -z "$TERMUX_SRC" ]; then
  echo "ERROR: No se encontro el directorio extraido de termux-app" >&2
  exit 1
fi

rm -rf src/com/termux/terminal/* src/com/termux/view/*
cp -r "$TERMUX_SRC/terminal-emulator/src/main/java/com/termux/terminal/"* src/com/termux/terminal/
cp -r "$TERMUX_SRC/terminal-view/src/main/java/com/termux/view/"* src/com/termux/view/
cp "$TERMUX_SRC/terminal-view/src/main/res/drawable/text_select_handle_left_material.xml" res/drawable/
cp "$TERMUX_SRC/terminal-view/src/main/res/drawable/text_select_handle_right_material.xml" res/drawable/

echo "=== terminal-view strings.xml ==="
cat "$TERMUX_SRC/terminal-view/src/main/res/values/strings.xml"
echo "================================"

echo "[2/4] Descargando dependencias JAR..."
curl -fsSL -L "https://dl.google.com/dl/android/maven2/androidx/annotation/annotation/1.5.0/annotation-1.5.0.jar" -o libs/annotation-1.5.0.jar
curl -fsSL -L "https://repo1.maven.org/maven2/org/tukaani/xz/1.9/xz-1.9.jar" -o libs/xz-1.9.jar

echo "[3/4] Descargando y extrayendo libtermux.so v0.118.3..."
APK_ARM64="$TMP_DIR/termux-arm64.apk"
APK_ARMV7="$TMP_DIR/termux-armv7.apk"
curl -fsSL -L "https://github.com/termux/termux-app/releases/download/v0.118.3/termux-app_v0.118.3%2Bgithub-debug_arm64-v8a.apk" -o "$APK_ARM64"
curl -fsSL -L "https://github.com/termux/termux-app/releases/download/v0.118.3/termux-app_v0.118.3%2Bgithub-debug_armeabi-v7a.apk" -o "$APK_ARMV7"

python3 - "$APK_ARM64" "lib/arm64-v8a/libtermux.so" "jniLibs/arm64-v8a/libtermux.so" \
        "$APK_ARMV7" "lib/armeabi-v7a/libtermux.so" "jniLibs/armeabi-v7a/libtermux.so" << 'PYEOF'
import sys, zipfile
args = sys.argv[1:]
for i in range(0, len(args), 3):
    apk, src, dst = args[i], args[i+1], args[i+2]
    with zipfile.ZipFile(apk, 'r') as zf:
        with open(dst, 'wb') as out:
            out.write(zf.read(src))
PYEOF

echo "[4/4] Verificando simbolos JNI en libtermux.so..."
readelf -Ws jniLibs/arm64-v8a/libtermux.so | grep Java_com_termux_terminal_JNI
readelf -Ws jniLibs/armeabi-v7a/libtermux.so | grep Java_com_termux_terminal_JNI

echo "setup-deps completado con exito."
