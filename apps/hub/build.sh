#!/data/data/com.termux/files/usr/bin/bash
# 构建 Viruzha 工具箱（唤醒 + DSH 一体化）。无 Gradle 流水线。
set -euo pipefail

APP="$(cd "$(dirname "$0")" && pwd)"
SDK="$HOME/dsh-workspace/apk-lab/sdk"
LINK_JAR="$SDK/platforms/android-34/android.jar"
CODE_JAR="$SDK/platforms/android-36/android.jar"
KEYSTORE="${HUB_KEYSTORE:-$HOME/dsh-workspace/apk-lab/debug.keystore}"
OUT="$APP/build"

say() { printf '\n==> %s\n' "$*"; }
rm -rf "$OUT"; mkdir -p "$OUT/classes" "$OUT/dex" "$OUT/gen"
export TMPDIR="${TMPDIR:-$APP/tmp}"; mkdir -p "$TMPDIR"

ENDPOINT="${WOL_ENDPOINT:-https://viruzha.tail428778.ts.net/wake}"
TOKEN_FILE="${WOL_TOKEN_FILE:-$HOME/.wol-token}"
TOKEN="$(cat "$TOKEN_FILE" 2>/dev/null | tr -d '\r\n' || true)"
[ -n "$TOKEN" ] || { echo "缺少 token：$TOKEN_FILE" >&2; exit 1; }

say "0/7 生成配置资源（唤醒接口 + token，不入源码树）"
mkdir -p "$OUT/genres/values"
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

say "2/7 aapt2 compile（配置资源）"
aapt2 compile --dir "$OUT/genres" -o "$OUT/config.zip"

say "3/7 aapt2 link（含 assets，包体较大）"
aapt2 link -o "$OUT/base.apk" -I "$LINK_JAR" \
  --manifest "$APP/AndroidManifest.xml" \
  "$OUT/res.zip" "$OUT/config.zip" -A "$APP/assets" \
  --java "$OUT/gen" \
  --min-sdk-version 24 --target-sdk-version 28 --version-code 1 --version-name 0.2

say "4/7 javac"
set +e
javac -nowarn -source 8 -target 8 -bootclasspath "$CODE_JAR" -encoding UTF-8 \
  -d "$OUT/classes" $(find "$APP/src" "$OUT/gen" -name '*.java') > "$OUT/javac.log" 2>&1
JAVAC_RC=$?
set -e
grep -viE 'bootstrap class path|source value 8|target value 8|deprecat|warning' "$OUT/javac.log" || true
if [ "$JAVAC_RC" -ne 0 ]; then echo "javac 失败（退出码 $JAVAC_RC），构建中止" >&2; exit 1; fi

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
    -dname "CN=Android Debug,O=Android,C=US" >/dev/null 2>&1 || { echo "无法生成密钥" >&2; exit 1; }
fi
apksigner sign --ks "$KEYSTORE" --ks-key-alias androiddebugkey \
  --ks-pass pass:android --key-pass pass:android \
  --out "$OUT/hub.apk" "$OUT/aligned.apk"
apksigner verify "$OUT/hub.apk" >/dev/null && echo "签名 OK"
printf '\nAPK: %s (%s)\n' "$OUT/hub.apk" "$(du -h "$OUT/hub.apk" | cut -f1)"
