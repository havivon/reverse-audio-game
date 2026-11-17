#!/usr/bin/env bash
# Builds a signed APK without Gradle, using the Debian/Ubuntu Android tool packages:
#   sudo apt-get install android-sdk-platform-23 aapt apksigner zipalign dalvik-exchange openjdk-17-jdk
# Output: dist/achoranit.apk
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
SRC="$ROOT/android/app/src/main"
OUT="$ROOT/build/apk"
DIST="$ROOT/dist"
SDK="${ANDROID_SDK:-/usr/lib/android-sdk}"
ANDROID_JAR="${ANDROID_JAR:-$SDK/platforms/android-23/android.jar}"
BT="$SDK/build-tools/debian"
PKG="com.achoranit.game"
VERSION_CODE="${VERSION_CODE:-4}"
VERSION_NAME="${VERSION_NAME:-2.0}"
KEYSTORE="${KEYSTORE:-$ROOT/android/release.keystore}"
KS_PASS="${KS_PASS:-achoranit}"

rm -rf "$OUT"; mkdir -p "$OUT"/{res,gen,classes,assets,dex} "$DIST"

echo "› assets"
cp -r "$ROOT/web/." "$OUT/assets/"

echo "› manifest"
sed "s|<manifest |<manifest package=\"$PKG\" |" "$SRC/AndroidManifest.xml" > "$OUT/AndroidManifest.xml"

echo "› resources (aapt2)"
aapt2 compile --dir "$SRC/res" -o "$OUT/res/res.zip"
aapt2 link -o "$OUT/unsigned.apk" -I "$ANDROID_JAR" \
  --manifest "$OUT/AndroidManifest.xml" \
  --min-sdk-version 23 --target-sdk-version 34 \
  --version-code "$VERSION_CODE" --version-name "$VERSION_NAME" \
  --java "$OUT/gen" -A "$OUT/assets" --auto-add-overlay \
  "$OUT/res/res.zip"

echo "› javac"
javac -nowarn -Xlint:-options --release 8 -encoding UTF-8 -cp "$ANDROID_JAR" -d "$OUT/classes" \
  $(find "$SRC/java" "$OUT/gen" -name '*.java')

echo "› dex"
java -jar "$BT/lib/dx.jar" --dex --min-sdk-version=23 --output="$OUT/dex/classes.dex" "$OUT/classes"
(cd "$OUT/dex" && zip -q -j "$OUT/unsigned.apk" classes.dex)

echo "› align + sign"
if [ ! -f "$KEYSTORE" ]; then
  keytool -genkeypair -keystore "$KEYSTORE" -storepass "$KS_PASS" -keypass "$KS_PASS" \
    -alias achoranit -keyalg RSA -keysize 2048 -validity 10000 \
    -dname "CN=Achoranit, O=Achoranit, C=IL" >/dev/null 2>&1
fi
zipalign -f -p 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"
apksigner sign --ks "$KEYSTORE" --ks-pass "pass:$KS_PASS" --ks-key-alias achoranit \
  --out "$DIST/achoranit.apk" "$OUT/aligned.apk"
apksigner verify "$DIST/achoranit.apk"
echo "✓ $DIST/achoranit.apk ($(du -h "$DIST/achoranit.apk" | cut -f1))"
