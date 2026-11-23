#!/bin/bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$ROOT_DIR"

ANDROID_JAR="/opt/android-sdk/android.jar"
D8_JAR="/opt/android-sdk/d8.jar"
ANNOTATION_JAR="$ROOT_DIR/libs/annotation-1.5.0.jar"
XZ_JAR="$ROOT_DIR/libs/xz-1.9.jar"

for f in "$ANDROID_JAR" "$D8_JAR" "$ANNOTATION_JAR" "$XZ_JAR" \
         "$ROOT_DIR/jniLibs/arm64-v8a/libtermux.so" \
         "$ROOT_DIR/jniLibs/arm64-v8a/libproot.so" \
         "$ROOT_DIR/jniLibs/arm64-v8a/libproot_loader.so" \
         "$ROOT_DIR/jniLibs/arm64-v8a/libproot_loader32.so" \
         "$ROOT_DIR/jniLibs/armeabi-v7a/libtermux.so" \
         "$ROOT_DIR/jniLibs/armeabi-v7a/libproot.so" \
         "$ROOT_DIR/jniLibs/armeabi-v7a/libproot_loader.so" \
         "$ROOT_DIR/jniLibs/armeabi-v7a/libproot_loader32.so"; do
  if [ ! -f "$f" ]; then
    echo "ERROR: Falta dependencia requerida: $f (ejecuta ./setup-deps.sh primero)" >&2
    exit 1
  fi
done

echo "[1/7] Preparando directorio build/..."
rm -rf build
mkdir -p build/gen build/classes build/dex

echo "[2/7] Compilando recursos con aapt2..."
aapt2 compile --dir res -o build/res.zip
aapt2 link \
  -I "$ANDROID_JAR" \
  --manifest AndroidManifest.xml \
  --extra-packages com.termux.view \
  --java build/gen \
  -o build/unaligned.apk \
  build/res.zip

echo "[3/7] Compilando Java..."
find src build/gen -name '*.java' | sort > build/sources.list
javac \
  -encoding UTF-8 \
  -source 1.8 \
  -target 1.8 \
  -Xlint:-options \
  -cp "$ANDROID_JAR:$ANNOTATION_JAR:$XZ_JAR" \
  -d build/classes \
  @build/sources.list

echo "[4/7] Generando classes.dex con d8..."
CLASS_FILES="$(find build/classes -name '*.class' | sort)"
java -cp "$D8_JAR" com.android.tools.r8.D8 \
  --min-api 24 \
  --release \
  --lib "$ANDROID_JAR" \
  --output build/dex \
  $CLASS_FILES \
  "$XZ_JAR"

echo "[5/7] Empaquetando DEX y librerias nativas en APK..."
python3 - << 'PYEOF'
import os, zipfile

src_apk = "build/unaligned.apk"
tmp_apk = "build/unaligned.tmp.apk"
inject = {
    "classes.dex": ("build/dex/classes.dex", zipfile.ZIP_DEFLATED),
}
for abi in ("arm64-v8a", "armeabi-v7a"):
    abi_dir = os.path.join("jniLibs", abi)
    if os.path.isdir(abi_dir):
        for fname in sorted(os.listdir(abi_dir)):
            if fname.endswith(".so"):
                inject[f"lib/{abi}/{fname}"] = (os.path.join(abi_dir, fname), zipfile.ZIP_STORED)
for i in range(2, 10):
    dex_p = f"build/dex/classes{i}.dex"
    if os.path.exists(dex_p):
        inject[f"classes{i}.dex"] = (dex_p, zipfile.ZIP_DEFLATED)

with zipfile.ZipFile(src_apk, "r") as zin, zipfile.ZipFile(tmp_apk, "w") as zout:
    for item in zin.infolist():
        if item.filename in inject:
            continue
        data = zin.read(item.filename)
        zout.writestr(item, data)
    for arcname, (fpath, comp) in inject.items():
        zout.write(fpath, arcname=arcname, compress_type=comp)

os.replace(tmp_apk, src_apk)
PYEOF

echo "[6/7] Alineando APK con zipalign..."
zipalign -f -p 4 build/unaligned.apk build/aligned.apk

KEYSTORE="$ROOT_DIR/debianterm.keystore"
if [ ! -f "$KEYSTORE" ]; then
  echo "[*] Generando keystore $KEYSTORE..."
  keytool -genkeypair -v \
    -keystore "$KEYSTORE" \
    -alias debianterm \
    -keyalg RSA \
    -keysize 2048 \
    -validity 10000 \
    -storepass debianterm \
    -keypass debianterm \
    -dname "CN=DebianTerm,O=Homelab,C=ES"
fi

echo "[7/7] Firmando y verificando APK..."
apksigner sign \
  --ks "$KEYSTORE" \
  --ks-key-alias debianterm \
  --ks-pass pass:debianterm \
  --key-pass pass:debianterm \
  --out build/DebianTerm-release.apk \
  build/aligned.apk

apksigner verify --verbose build/DebianTerm-release.apk

if [ -d /sdcard/Download ]; then
  cp build/DebianTerm-release.apk /sdcard/Download/DebianTerm.apk
  echo "Copiado a /sdcard/Download/DebianTerm.apk"
fi

ls -lh build/DebianTerm-release.apk
