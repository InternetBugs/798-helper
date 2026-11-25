#!/usr/bin/env bash
# Build io.github.internetbugs.helper798. Run from the repo root.
#
# Local use:   bash build.sh
# CI use:      VERSION_NAME=1.4.0 VERSION_CODE=10400 KEYSTORE=... bash build.sh
#
# Environment:
#   ANDROID_HOME / ANDROID_SDK_ROOT   SDK root (default: tools/android-sdk)
#   BUILD_TOOLS_VERSION               default 35.0.0
#   API_JAR                           libxposed api jar (default: tools/api-102.jar)
#   VERSION_NAME / VERSION_CODE       override the manifest values
#   KEYSTORE, KS_PASS, KEY_ALIAS, KEY_PASS
#                                     release signing; without KEYSTORE a throwaway
#                                     debug key is generated so local builds still work
set -euo pipefail

# Prefer the SDK vendored next to the sources; a global ANDROID_HOME on Windows
# is a backslash path that breaks every "$BT/..." test below.
if [ -d tools/android-sdk ]; then
    SDK=tools/android-sdk
else
    SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-tools/android-sdk}}"
fi
SDK="${SDK//\\//}"
BT_VERSION="${BUILD_TOOLS_VERSION:-35.0.0}"
BT="$SDK/build-tools/$BT_VERSION"
AJ="$SDK/platforms/android-35/android.jar"
API_JAR="${API_JAR:-tools/api-102.jar}"
OUT="${OUT:-build/798-helper.apk}"

# aapt2/d8/apksigner are .exe/.bat on Windows and bare scripts elsewhere.
pick() {
    for c in "$BT/$1.exe" "$BT/$1.bat" "$BT/$1"; do
        if [ -f "$c" ]; then
            echo "$c"
            return
        fi
    done
    echo "$1"
}
AAPT2=$(pick aapt2)
D8=$(pick d8)
ZIPALIGN=$(pick zipalign)
APKSIGNER=$(pick apksigner)
# python3 on Windows is often the Microsoft Store stub, which exits 49 and does
# nothing; probe the interpreter before trusting it.
PY=
for c in python3 python py; do
    if command -v "$c" >/dev/null 2>&1 && "$c" -c 'import sys' >/dev/null 2>&1; then
        PY=$c
        break
    fi
done
[ -n "$PY" ] || { echo "python 3 is required" >&2; exit 1; }

for f in "$AAPT2" "$D8" "$ZIPALIGN" "$APKSIGNER" "$AJ" "$API_JAR"; do
    [ -f "$f" ] || { echo "missing: $f" >&2; exit 1; }
done

rm -rf build/classes build/dex build/base.apk build/unsigned.apk build/aligned.apk
mkdir -p build/classes build/dex

"$AAPT2" compile --dir module/res -o build/res.zip

# aapt2 35 silently ignores --version-code/--version-name when the manifest
# declares them, so the overrides are substituted into a copy of the manifest.
MANIFEST=module/AndroidManifest.xml
if [ -n "${VERSION_CODE:-}" ] || [ -n "${VERSION_NAME:-}" ]; then
    cp module/AndroidManifest.xml build/AndroidManifest.xml
    if [ -n "${VERSION_CODE:-}" ]; then
        sed -i "s/android:versionCode=\"[0-9]*\"/android:versionCode=\"$VERSION_CODE\"/" build/AndroidManifest.xml
    fi
    if [ -n "${VERSION_NAME:-}" ]; then
        sed -i "s/android:versionName=\"[^\"]*\"/android:versionName=\"$VERSION_NAME\"/" build/AndroidManifest.xml
    fi
    MANIFEST=build/AndroidManifest.xml
fi

"$AAPT2" link -o build/base.apk -I "$AJ" --manifest "$MANIFEST" \
    --min-sdk-version 24 --target-sdk-version 35 build/res.zip

find module/src -name '*.java' > build/sources.txt
javac -source 8 -target 8 -nowarn -encoding UTF-8 \
    -bootclasspath "$AJ" -cp "$API_JAR" -d build/classes @build/sources.txt

# shellcheck disable=SC2046
"$D8" --lib "$AJ" --lib "$API_JAR" --min-api 24 --output build/dex \
    $(find build/classes -name '*.class')

cp build/base.apk build/unsigned.apk
"$PY" - <<'PY'
import os
import zipfile

with zipfile.ZipFile('build/unsigned.apk', 'a', zipfile.ZIP_DEFLATED) as z:
    # Store classes.dex uncompressed: the daemon maps a module's dex straight
    # out of the APK, and a stored entry lets it map the file instead of
    # inflating it first.
    z.write('build/dex/classes.dex', 'classes.dex',
            compress_type=zipfile.ZIP_STORED)
    for root, _, files in os.walk('module/meta'):
        for f in files:
            p = os.path.join(root, f)
            z.write(p, os.path.relpath(p, 'module/meta').replace(os.sep, '/'))
PY

"$ZIPALIGN" -f -p 4 build/unsigned.apk build/aligned.apk

if [ -n "${KEYSTORE:-}" ]; then
    "$APKSIGNER" sign --ks "$KEYSTORE" --ks-pass "pass:$KS_PASS" \
        --key-pass "pass:$KEY_PASS" --ks-key-alias "$KEY_ALIAS" \
        --out "$OUT" build/aligned.apk
else
    echo "KEYSTORE not set, signing with a throwaway debug key" >&2
    KS=build/debug.keystore
    [ -e "$KS" ] || keytool -genkeypair -keystore "$KS" -storepass android \
        -keypass android -alias androiddebugkey -keyalg RSA -keysize 2048 \
        -validity 10000 -dname "CN=Android Debug,O=Android,C=US" >/dev/null 2>&1
    "$APKSIGNER" sign --ks "$KS" --ks-pass pass:android --key-pass pass:android \
        --ks-key-alias androiddebugkey --out "$OUT" build/aligned.apk
fi

echo "built $OUT"
