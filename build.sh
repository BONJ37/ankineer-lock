#!/usr/bin/env bash
# Builds Ankineer-Lock.apk without Gradle: aapt -> javac -> dx -> zipalign -> apksigner
set -euo pipefail
export KS_PASS KEY_PASS 2>/dev/null || true
cd "$(dirname "$0")"

SDK_JAR=/usr/lib/android-sdk/platforms/android-23/android.jar
rm -rf build && mkdir -p build/gen build/obj

python3 tools/make_icon.py res
python3 tools/make_stat_icon.py res

echo "[1/5] Resources + R.java"
aapt package -f -m -J build/gen -M AndroidManifest.xml -S res -I "$SDK_JAR"

echo "[2/5] Java compilation"
javac -encoding UTF-8 -source 8 -target 8 -Xlint:-options -bootclasspath "$SDK_JAR" \
  -d build/obj $(find build/gen src -name '*.java')

echo "[2b] Smoke test (whole app on the JVM, every page and every button)"
if [ -f tests/smoke/run.sh ] && [ "${SKIP_SMOKE:-0}" != "1" ]; then
  bash tests/smoke/run.sh 2>&1 | grep -v "^Picked up" | tail -40
  if [ "${PIPESTATUS[0]}" != "0" ]; then echo "Smoke test failed: no APK produced."; exit 1; fi
fi

echo "[3/5] Dex"
dalvik-exchange --dex --min-sdk-version=23 --output=build/classes.dex build/obj

echo "[4/5] APK package"
aapt package -f -M AndroidManifest.xml -S res -I "$SDK_JAR" -F build/unsigned.apk
( cd build && aapt add unsigned.apk classes.dex >/dev/null )
zipalign -f -p 4 build/unsigned.apk build/aligned.apk

echo "[5/5] Signing"
# Release key: provided by the environment (GitHub Actions reads the repository "Secrets").
#   ANKINEER_KEYSTORE      path to the .jks file
#   ANKINEER_KEYSTORE_PASS keystore password
#   ANKINEER_KEY_ALIAS     key alias (default: ankineer)
#   ANKINEER_KEY_PASS      key password (default: the same)
# Without these variables, the APK is signed with a local TEST key (never published):
# it cannot update an official version.
OUT=${OUT:-Ankineer-Lock.apk}
if [ -n "${ANKINEER_KEYSTORE:-}" ]; then
  KS="$ANKINEER_KEYSTORE"; KS_PASS="$ANKINEER_KEYSTORE_PASS"
  ALIAS="${ANKINEER_KEY_ALIAS:-ankineer}"; KEY_PASS="${ANKINEER_KEY_PASS:-$KS_PASS}"
  echo "    release key: $ALIAS"
else
  KS=debug.jks; ALIAS=debug; KS_PASS=android; KEY_PASS=android
  if [ ! -f "$KS" ]; then
    keytool -genkeypair -keystore "$KS" -alias debug -keyalg RSA -keysize 2048 -validity 10000 \
      -storepass android -keypass android -dname "CN=Ankineer Lock debug" >/dev/null 2>&1
  fi
  echo "    ⚠ local TEST key (debug.jks): unofficial APK"
fi
apksigner sign --ks "$KS" --ks-key-alias "$ALIAS" --ks-pass env:KS_PASS --key-pass env:KEY_PASS \
  --out "$OUT" build/aligned.apk
apksigner verify "$OUT"
ls -la "$OUT"
