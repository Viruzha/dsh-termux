#!/data/data/com.termux/files/usr/bin/bash
# Same pipeline as build.sh plus a JNI .so under lib/arm64-v8a/.
set -euo pipefail

LAB="$(cd "$(dirname "$0")" && pwd)"
SDK="$LAB/sdk"
LINK_JAR="$SDK/platforms/android-34/android.jar"
CODE_JAR="$SDK/platforms/android-36/android.jar"
OUT="$LAB/build-native"
NT="$LAB/native-test"
say() { printf '\n==> %s\n' "$*"; }

rm -rf "$OUT"; mkdir -p "$OUT/classes" "$OUT/dex" "$OUT/gen" "$OUT/stage/lib/arm64-v8a"
export TMPDIR="${TMPDIR:-$LAB/tmp}"; mkdir -p "$TMPDIR"

say "0/8 clang -> libapklab.so (aarch64)"
aarch64-linux-android-clang -shared -fPIC -O2 -I/data/data/com.termux/files/usr/include \
  -o "$OUT/libapklab.so" "$NT/jni.c"
readelf -h "$OUT/libapklab.so" | grep -E 'Machine|Type'

say "1/8 aapt2 compile"
aapt2 compile --dir "$LAB/res" -o "$OUT/resources.zip"

say "2/8 aapt2 link"
aapt2 link -o "$OUT/base.apk" -I "$LINK_JAR" --manifest "$LAB/AndroidManifest.xml" \
  "$OUT/resources.zip" --java "$OUT/gen" \
  --min-sdk-version 24 --target-sdk-version 36 --version-code 1 --version-name 1.0

say "3/8 javac"
javac -nowarn -source 8 -target 8 -bootclasspath "$CODE_JAR" -d "$OUT/classes" \
  $(find "$NT/src" "$OUT/gen" -name '*.java') 2>&1 \
  | grep -viE 'bootstrap class path|source value 8|target value 8|deprecat|warning' || true

say "4/8 d8"
d8 --lib "$CODE_JAR" --min-api 24 --output "$OUT/dex" $(find "$OUT/classes" -name '*.class')

say "5/8 pack .so (STORED) + dex"
cp "$OUT/base.apk" "$OUT/unsigned.apk"
cp "$OUT/libapklab.so" "$OUT/stage/lib/arm64-v8a/libapklab.so"
( cd "$OUT/stage" && zip -q -X -0 "$OUT/unsigned.apk" lib/arm64-v8a/libapklab.so )
( cd "$OUT/dex"   && zip -q -X    "$OUT/unsigned.apk" classes.dex )
unzip -l "$OUT/unsigned.apk" | grep -E 'lib/|classes.dex|resources.arsc'

say "6/8 zipalign -p 4 (page-align the .so)"
zipalign -f -p 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"

say "7/8 verify .so alignment and compression"
python3 - "$OUT/aligned.apk" <<'PY'
import zipfile, struct, sys
p = sys.argv[1]; z = zipfile.ZipFile(p)
for i in z.infolist():
    if i.filename.endswith('.so'):
        f=open(p,'rb'); f.seek(i.header_offset); hdr=f.read(30)
        n,e=struct.unpack('<HH',hdr[26:30]); off=i.header_offset+30+n+e
        print(f"  {i.filename}: {'STORED' if i.compress_type==0 else 'DEFLATE'}, "
              f"data offset {off}, page-aligned={off%4096==0}")
PY

say "8/8 apksigner"
apksigner sign --ks "$LAB/debug.keystore" --ks-key-alias androiddebugkey \
  --ks-pass pass:android --key-pass pass:android \
  --out "$OUT/apk-lab-native.apk" "$OUT/aligned.apk"
apksigner verify "$OUT/apk-lab-native.apk" >/dev/null && echo "signature OK"
printf '\nAPK: %s (%s bytes)\n' "$OUT/apk-lab-native.apk" "$(stat -c%s "$OUT/apk-lab-native.apk")"
