#!/bin/bash
set -euo pipefail
GM_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SDK="${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}"
BT="$SDK/build-tools/${ANDROID_BUILD_TOOLS_VERSION:-37.0.0}"
JAR="$SDK/platforms/android-32/android.jar"
KC="${KOTLIN_COMPILER:-/opt/android-studio-for-platform/plugins/Kotlin/kotlinc/bin/kotlinc}"
STDLIB="$(dirname "$(dirname "$KC")")/lib/kotlin-stdlib.jar"
WORK="$(mktemp -d /tmp/focus-test.XXXXXX)"
USER_ID="$(adb shell am get-current-user | tr -d '\r')"
mkdir -p "$WORK/classes" "$WORK/dex"
# Target classes are compile-only; instrumentation executes against the installed app.
bash "$KC" -classpath "$JAR:${FOCUS_APP_CLASSES:?Set FOCUS_APP_CLASSES to build WORKDIR/classes}" \
    -jvm-target 1.8 -d "$WORK/classes" "$GM_ROOT/tools/FocusPauseTest.kt"
(cd "$WORK/classes" && jar cf "$WORK/tests.jar" .)
"$BT/d8" --min-api 26 --lib "$JAR" --output "$WORK/dex" "$WORK/tests.jar" "$STDLIB"
# com.android.car is the emulator's real system package and must never be replaced.
for PKG in wasidremin.gmccpa.focus.test com.gm.car.media.gmcarmediaservice; do
    if [ "$PKG" = wasidremin.gmccpa.focus.test ]; then
        EXTRA='<instrumentation android:name="wasidremin.gmccpa.test.FocusPauseTest" android:targetPackage="wasidremin.gmccpa" />'
    else EXTRA=''; fi
    cat > "$WORK/AndroidManifest.xml" <<EOF
<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="$PKG">
<uses-sdk android:minSdkVersion="26" android:targetSdkVersion="32" />
$EXTRA
<application android:label="Focus test"><activity android:name="wasidremin.gmccpa.test.FocusHolder" android:exported="true" /></application>
</manifest>
EOF
    "$BT/aapt2" link -I "$JAR" --manifest "$WORK/AndroidManifest.xml" -o "$WORK/$PKG.apk"
    (cd "$WORK/dex" && zip -q "$WORK/$PKG.apk" classes.dex)
    "$BT/apksigner" sign --ks "$HOME/.android/debug.keystore" --ks-pass pass:android "$WORK/$PKG.apk"
    adb install --user "$USER_ID" -r "$WORK/$PKG.apk"
done
adb shell am instrument --user "$USER_ID" -w wasidremin.gmccpa.focus.test/wasidremin.gmccpa.test.FocusPauseTest | tee "$WORK/result.txt"
grep -Eq 'FOCUS_TEST: [0-9]+ passed, 0 failed' "$WORK/result.txt"
echo "Evidence: $WORK/result.txt"
