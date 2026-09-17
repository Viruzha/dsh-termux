#!/data/data/com.termux/files/usr/bin/bash
# Build a real Android APK on Termux/Android with NO Gradle and NO Android SDK manager.
#
# Toolchain: javac (openjdk-21), aapt2 2.19, d8 3.3.20, zipalign, apksigner 0.9
#
# COMPILE-SDK CAP: Termux's aapt2 is built from AOSP 13 (2.19). It cannot parse the
# resource table of newer platform jars. Measured on this device:
#     API 33  -> OK        API 34  -> OK
#     API 35  -> FAIL      API 36  -> FAIL   ("failed to load include path ... .jar")
# Google publishes aapt2 only for linux-x86_64 (no linux-aarch64), so the cap cannot be
# raised by swapping the binary. Workaround used here: link resources against API 34,
# compile Java against API 36. New platform *attributes* (>=35) cannot be used in XML.
set -euo pipefail

LAB="$(cd "$(dirname "$0")" && pwd)"
SDK="$LAB/sdk"
LINK_JAR="$SDK/platforms/android-34/android.jar"   # for aapt2 -I (resource resolution)
CODE_JAR="$SDK/platforms/android-36/android.jar"   # for javac / d8 (API surface)
MIN_SDK=24
TARGET_SDK=36
OUT="$LAB/build"
APK_NAME="apk-lab.apk"
KEYSTORE="$LAB/debug.keystore"

say() { printf '\n==> %s\n' "$*"; }

command -v javac aapt2 d8 zipalign apksigner zip keytool >/dev/null || {
  echo "missing toolchain; run: pkg install openjdk-21 aapt2 d8 apksigner zip" >&2; exit 1; }
for j in "$LINK_JAR" "$CODE_JAR"; do [ -f "$j" ] || { echo "missing $j" >&2; exit 1; }; done

rm -rf "$OUT"; mkdir -p "$OUT/classes" "$OUT/dex" "$OUT/gen"
export TMPDIR="${TMPDIR:-$LAB/tmp}"; mkdir -p "$TMPDIR"

say "1/7 aapt2 compile  (res/ -> flat archive)"
aapt2 compile --dir "$LAB/res" -o "$OUT/resources.zip"

say "2/7 aapt2 link     (resources + manifest -> base.apk, generates R.java)"
aapt2 link -o "$OUT/base.apk" \
  -I "$LINK_JAR" \
  --manifest "$LAB/AndroidManifest.xml" \
  "$OUT/resources.zip" \
  --java "$OUT/gen" \
  --min-sdk-version "$MIN_SDK" --target-sdk-version "$TARGET_SDK" \
  --version-code 1 --version-name 1.0

say "3/7 javac          (src + R.java -> .class ; stubs = API 36)"
mapfile -t SOURCES < <(find "$LAB/src" "$OUT/gen" -name '*.java')
printf '    compiling %d file(s)\n' "${#SOURCES[@]}"
javac -nowarn -source 8 -target 8 -bootclasspath "$CODE_JAR" -encoding UTF-8 \
  -d "$OUT/classes" "${SOURCES[@]}" 2>&1 \
  | grep -viE 'bootstrap class path|source value 8|target value 8|deprecat|warning' || true

say "4/7 d8             (.class -> classes.dex)"
mapfile -t CLASSES < <(find "$OUT/classes" -name '*.class')
d8 --lib "$CODE_JAR" --min-api "$MIN_SDK" --output "$OUT/dex" "${CLASSES[@]}"
ls -l "$OUT/dex"

say "5/7 zip            (inject classes.dex)"
cp "$OUT/base.apk" "$OUT/unsigned.apk"
( cd "$OUT/dex" && zip -q -X "$OUT/unsigned.apk" classes.dex )
unzip -l "$OUT/unsigned.apk" | grep -E 'classes.dex|resources.arsc|AndroidManifest.xml'

say "6/7 zipalign       (4-byte align uncompressed entries)"
zipalign -f -p 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"

say "7/7 apksigner      (debug keystore, v1+v2+v3)"
[ -f "$KEYSTORE" ] || keytool -genkeypair -keystore "$KEYSTORE" -alias androiddebugkey \
  -storepass android -keypass android -keyalg RSA -keysize 2048 -validity 10000 \
  -dname "CN=Android Debug,O=Android,C=US" >/dev/null 2>&1
apksigner sign --ks "$KEYSTORE" --ks-key-alias androiddebugkey \
  --ks-pass pass:android --key-pass pass:android \
  --v1-signing-enabled true --v2-signing-enabled true --v3-signing-enabled true \
  --out "$OUT/$APK_NAME" "$OUT/aligned.apk"

say "VERIFY"
apksigner verify --verbose "$OUT/$APK_NAME" | head -12
echo
aapt2 dump badging "$OUT/$APK_NAME" 2>/dev/null | head -6
printf '\nAPK: %s  (%s bytes)\n' "$OUT/$APK_NAME" "$(stat -c%s "$OUT/$APK_NAME")"
