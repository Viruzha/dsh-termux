#!/data/data/com.termux/files/usr/bin/bash
# 构建「一键唤醒」App —— 复用 apk-lab 验证过的无 Gradle 流水线。
# token 不落进源码树：只在 build/ 下生成，编译进资源后即弃。
set -euo pipefail

APP="$(cd "$(dirname "$0")" && pwd)"
SDK="$HOME/dsh-workspace/apk-lab/sdk"
LINK_JAR="$SDK/platforms/android-34/android.jar"
CODE_JAR="$SDK/platforms/android-36/android.jar"
KEYSTORE="${WOL_KEYSTORE:-$HOME/dsh-workspace/apk-lab/debug.keystore}"
OUT="$APP/build"

ENDPOINT="${WOL_ENDPOINT:-https://viruzha.tail428778.ts.net/wake}"
TOKEN_FILE="${WOL_TOKEN_FILE:-$HOME/.wol-token}"
TOKEN="$(cat "$TOKEN_FILE" 2>/dev/null | tr -d '\r\n' || true)"
[ -n "$TOKEN" ] || { echo "缺少 token：$TOKEN_FILE" >&2; exit 1; }

say() { printf '\n==> %s\n' "$*"; }
rm -rf "$OUT"; mkdir -p "$OUT/classes" "$OUT/dex" "$OUT/gen" "$OUT/genres/values"

say "0/7 生成配置资源（endpoint + token）"
cat > "$OUT/genres/values/config.xml" <<EOF
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="endpoint" translatable="false">$ENDPOINT</string>
    <string name="default_token" translatable="false">$TOKEN</string>
</resources>
EOF
echo "    endpoint = $ENDPOINT"

say "1/7 aapt2 compile（主资源）"
aapt2 compile --dir "$APP/res" -o "$OUT/res.zip"

say "2/7 aapt2 compile（生成的配置资源）"
aapt2 compile --dir "$OUT/genres" -o "$OUT/config.zip"

say "3/7 aapt2 link"
aapt2 link -o "$OUT/base.apk" -I "$LINK_JAR" \
  --manifest "$APP/AndroidManifest.xml" \
  "$OUT/res.zip" "$OUT/config.zip" \
  --java "$OUT/gen" \
  --min-sdk-version 24 --target-sdk-version 34 --version-code 1 --version-name 1.0

say "4/7 javac"
javac -nowarn -source 8 -target 8 -bootclasspath "$CODE_JAR" -encoding UTF-8 \
  -d "$OUT/classes" $(find "$APP/src" "$OUT/gen" -name '*.java') 2>&1 \
  | grep -viE 'bootstrap class path|source value 8|target value 8|deprecat|warning' || true

say "5/7 d8"
d8 --lib "$CODE_JAR" --min-api 24 --output "$OUT/dex" $(find "$OUT/classes" -name '*.class')

say "6/7 zip + zipalign"
cp "$OUT/base.apk" "$OUT/unsigned.apk"
( cd "$OUT/dex" && zip -q -X "$OUT/unsigned.apk" classes.dex )
zipalign -f -p 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"

say "7/7 apksigner"
if [ ! -f "$KEYSTORE" ]; then
  mkdir -p "$(dirname "$KEYSTORE")"
  keytool -genkeypair -keystore "$KEYSTORE" -alias androiddebugkey \
    -storepass android -keypass android -keyalg RSA -keysize 2048 -validity 10000 \
    -dname "CN=Android Debug,O=Android,C=US" >/dev/null 2>&1 \
    || { echo "无法生成签名密钥 $KEYSTORE" >&2; exit 1; }
fi
apksigner sign --ks "$KEYSTORE" --ks-key-alias androiddebugkey \
  --ks-pass pass:android --key-pass pass:android \
  --out "$OUT/wake.apk" "$OUT/aligned.apk"

apksigner verify "$OUT/wake.apk" >/dev/null && echo "签名 OK"
rm -f "$OUT/genres/values/config.xml"
printf '\nAPK: %s (%s bytes)\n' "$OUT/wake.apk" "$(stat -c%s "$OUT/wake.apk")"
