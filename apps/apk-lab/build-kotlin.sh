#!/data/data/com.termux/files/usr/bin/bash
# Same pipeline as build.sh, but the app code is Kotlin (kotlinc -> d8).
set -euo pipefail

LAB="$(cd "$(dirname "$0")" && pwd)"
SDK="$LAB/sdk"
LINK_JAR="$SDK/platforms/android-34/android.jar"
CODE_JAR="$SDK/platforms/android-36/android.jar"
STDLIB=/data/data/com.termux/files/usr/opt/kotlin/lib/kotlin-stdlib.jar
OUT="$LAB/build-kt"
KEYSTORE="$LAB/debug.keystore"
say() { printf '\n==> %s\n' "$*"; }

command -v kotlinc >/dev/null || { echo "run: pkg install kotlin" >&2; exit 1; }
[ -f "$STDLIB" ] || { echo "kotlin-stdlib.jar not found" >&2; exit 1; }

rm -rf "$OUT"; mkdir -p "$OUT/rclasses" "$OUT/ktclasses" "$OUT/dex" "$OUT/gen"
export TMPDIR="${TMPDIR:-$LAB/tmp}"; mkdir -p "$TMPDIR"

say "1/7 aapt2 compile"
aapt2 compile --dir "$LAB/res" -o "$OUT/resources.zip"

say "2/7 aapt2 link (generates R.java)"
aapt2 link -o "$OUT/base.apk" -I "$LINK_JAR" \
  --manifest "$LAB/AndroidManifest.xml" "$OUT/resources.zip" --java "$OUT/gen" \
  --min-sdk-version 24 --target-sdk-version 36 --version-code 1 --version-name 1.0

say "3/7 javac (R.java only)"
javac -nowarn -source 8 -target 8 -bootclasspath "$CODE_JAR" \
  -d "$OUT/rclasses" $(find "$OUT/gen" -name '*.java') 2>&1 \
  | grep -viE 'bootstrap class path|source value 8|target value 8|deprecat|warning' || true

say "4/7 kotlinc"
kotlinc -classpath "$CODE_JAR:$OUT/rclasses" -jvm-target 1.8 -nowarn \
  -d "$OUT/ktclasses" "$LAB/kotlin-test/MainActivity.kt" 2>&1 | grep -v '^warning:' || true
find "$OUT/ktclasses" -name '*.class' | head -5

say "5/7 d8 (app + R + kotlin-stdlib)"
mapfile -t INPUTS < <(find "$OUT/ktclasses" "$OUT/rclasses" -name '*.class')
d8 --lib "$CODE_JAR" --min-api 24 --output "$OUT/dex" "${INPUTS[@]}" "$STDLIB"
ls -l "$OUT/dex"

say "6/7 zip + zipalign"
cp "$OUT/base.apk" "$OUT/unsigned.apk"
( cd "$OUT/dex" && zip -q -X "$OUT/unsigned.apk" classes.dex )
zipalign -f -p 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"

say "7/7 apksigner"
apksigner sign --ks "$KEYSTORE" --ks-key-alias androiddebugkey \
  --ks-pass pass:android --key-pass pass:android --out "$OUT/apk-lab-kotlin.apk" "$OUT/aligned.apk"

apksigner verify "$OUT/apk-lab-kotlin.apk" >/dev/null && echo "signature OK"
printf '\nAPK: %s (%s bytes)\n' "$OUT/apk-lab-kotlin.apk" "$(stat -c%s "$OUT/apk-lab-kotlin.apk")"
