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

echo "[1/5] Descargando termux-app commit 952f309e6b..."
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

echo "[2/5] Descargando dependencias JAR..."
curl -fsSL -L "https://dl.google.com/dl/android/maven2/androidx/annotation/annotation/1.5.0/annotation-1.5.0.jar" -o libs/annotation-1.5.0.jar
curl -fsSL -L "https://repo1.maven.org/maven2/org/tukaani/xz/1.9/xz-1.9.jar" -o libs/xz-1.9.jar

echo "[3/5] Descargando y extrayendo libtermux.so v0.118.3..."
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

echo "[4/5] Descargando y verificando binarios PRoot Android (v26.10.08-a179d3e)..."
PROOT_BASE_URL="https://github.com/ahmed-alnassif/proot/releases/download/v26.10.08-a179d3e"
PROOT_SUMS="$TMP_DIR/proot-checksums.txt"
PROOT_ARM64_ZIP="$TMP_DIR/proot-aarch64.zip"
PROOT_ARM_ZIP="$TMP_DIR/proot-arm.zip"
curl -fsSL -L "$PROOT_BASE_URL/checksums.txt" -o "$PROOT_SUMS"
curl -fsSL -L "$PROOT_BASE_URL/proot-aarch64.zip" -o "$PROOT_ARM64_ZIP"
curl -fsSL -L "$PROOT_BASE_URL/proot-arm.zip" -o "$PROOT_ARM_ZIP"

python3 - "$PROOT_SUMS" "$PROOT_ARM64_ZIP" "proot-aarch64.zip" "jniLibs/arm64-v8a" \
        "$PROOT_ARM_ZIP" "proot-arm.zip" "jniLibs/armeabi-v7a" << 'PYEOF'
import hashlib, os, sys, zipfile

sums_path = sys.argv[1]
expected = {}
with open(sums_path, "r", encoding="utf-8") as f:
    for line in f:
        parts = line.strip().split()
        if len(parts) >= 2:
            expected[parts[1].lstrip("./")] = parts[0].lower()

entries = [
    ("proot", "libproot.so"),
    ("loader", "libproot_loader.so"),
    ("loader-m32", "libproot_loader32.so"),
]

args = sys.argv[2:]
for i in range(0, len(args), 3):
    zip_path, zip_name, out_dir = args[i], args[i+1], args[i+2]
    exp_sha = expected.get(zip_name)
    if not exp_sha:
        raise SystemExit(f"ERROR: No se encontró SHA-256 para {zip_name} en checksums.txt")
    h = hashlib.sha256()
    with open(zip_path, "rb") as f:
        while True:
            chunk = f.read(65536)
            if not chunk:
                break
            h.update(chunk)
    actual_sha = h.hexdigest().lower()
    if actual_sha != exp_sha:
        raise SystemExit(f"ERROR: SHA-256 inválido para {zip_name}: esperado {exp_sha}, obtenido {actual_sha}")
    os.makedirs(out_dir, exist_ok=True)
    with zipfile.ZipFile(zip_path, "r") as zf:
        for src_name, dst_name in entries:
            dst_path = os.path.join(out_dir, dst_name)
            with open(dst_path, "wb") as out:
                out.write(zf.read(src_name))
            os.chmod(dst_path, 0o755)
    print(f"OK {zip_name} ({actual_sha[:12]}...) -> {out_dir}")
PYEOF

echo "[5/5] Verificando simbolos JNI en libtermux.so y binarios PRoot..."
readelf -Ws jniLibs/arm64-v8a/libtermux.so | grep Java_com_termux_terminal_JNI
readelf -Ws jniLibs/armeabi-v7a/libtermux.so | grep Java_com_termux_terminal_JNI
ls -la jniLibs/arm64-v8a/libproot*.so jniLibs/armeabi-v7a/libproot*.so

echo "setup-deps completado con exito."
