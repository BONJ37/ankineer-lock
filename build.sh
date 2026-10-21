#!/usr/bin/env bash
# Compile Ankineer-Lock.apk sans Gradle : aapt -> javac -> dx -> zipalign -> apksigner
set -euo pipefail
export KS_PASS KEY_PASS 2>/dev/null || true
cd "$(dirname "$0")"

SDK_JAR=/usr/lib/android-sdk/platforms/android-23/android.jar
rm -rf build && mkdir -p build/gen build/obj

python3 tools/make_icon.py res
python3 tools/make_stat_icon.py res

echo "[1/5] Ressources + R.java"
aapt package -f -m -J build/gen -M AndroidManifest.xml -S res -I "$SDK_JAR"

echo "[2/5] Compilation Java"
javac -encoding UTF-8 -source 8 -target 8 -Xlint:-options -bootclasspath "$SDK_JAR" \
  -d build/obj $(find build/gen src -name '*.java')

echo "[2b] Test de fumée (app entière sur JVM, toutes les pages et tous les boutons)"
if [ -f tests/smoke/run.sh ] && [ "${SKIP_SMOKE:-0}" != "1" ]; then
  bash tests/smoke/run.sh 2>&1 | grep -v "^Picked up" | tail -40
  if [ "${PIPESTATUS[0]}" != "0" ]; then echo "Test de fumée en échec : APK non produit."; exit 1; fi
fi

echo "[3/5] Dex"
dalvik-exchange --dex --min-sdk-version=23 --output=build/classes.dex build/obj

echo "[4/5] Paquet APK"
aapt package -f -M AndroidManifest.xml -S res -I "$SDK_JAR" -F build/unsigned.apk
( cd build && aapt add unsigned.apk classes.dex >/dev/null )
zipalign -f -p 4 build/unsigned.apk build/aligned.apk

echo "[5/5] Signature"
# Clé de publication : fournie par l'environnement (GitHub Actions lit les « Secrets » du dépôt).
#   ANKINEER_KEYSTORE      chemin du fichier .jks
#   ANKINEER_KEYSTORE_PASS mot de passe du fichier
#   ANKINEER_KEY_ALIAS     alias de la clé (défaut : ankineer)
#   ANKINEER_KEY_PASS      mot de passe de la clé (défaut : le même)
# Sans ces variables, l'APK est signé avec une clé de TEST locale (jamais publiée) :
# il ne pourra pas mettre à jour une version officielle.
OUT=${OUT:-Ankineer-Lock.apk}
if [ -n "${ANKINEER_KEYSTORE:-}" ]; then
  KS="$ANKINEER_KEYSTORE"; KS_PASS="$ANKINEER_KEYSTORE_PASS"
  ALIAS="${ANKINEER_KEY_ALIAS:-ankineer}"; KEY_PASS="${ANKINEER_KEY_PASS:-$KS_PASS}"
  echo "    clé de publication : $ALIAS"
else
  KS=debug.jks; ALIAS=debug; KS_PASS=android; KEY_PASS=android
  if [ ! -f "$KS" ]; then
    keytool -genkeypair -keystore "$KS" -alias debug -keyalg RSA -keysize 2048 -validity 10000 \
      -storepass android -keypass android -dname "CN=Ankineer Lock debug" >/dev/null 2>&1
  fi
  echo "    ⚠ clé de TEST locale (debug.jks) : APK non officiel"
fi
apksigner sign --ks "$KS" --ks-key-alias "$ALIAS" --ks-pass env:KS_PASS --key-pass env:KEY_PASS \
  --out "$OUT" build/aligned.apk
apksigner verify "$OUT"
ls -la "$OUT"
