#!/usr/bin/env bash
# Test de fumée : lance l'app entière sur la JVM avec un framework Android simulé
# (android.jar transformé : chaque méthode renvoie une valeur « profonde » ou un comportement simulé).
# Ouvre chaque page dans 14 états, appuie sur chaque bouton, simule le service de blocage, les widgets,
# les rappels, la mise à jour et des pannes. Échoue (code 1) à la moindre exception du code de l'app.
set -euo pipefail
cd "$(dirname "$0")"
ROOT=$(cd ../.. && pwd)
SDK_JAR=/usr/lib/android-sdk/platforms/android-23/android.jar
ASM=/usr/share/java/asm.jar:/usr/share/java/asm-tree.jar
JSON=/usr/share/java/com.android.json.jar
OUT=$ROOT/build/smoke
rm -rf "$OUT" && mkdir -p "$OUT/gen" "$OUT/hooks" "$OUT/driver"
javac -nowarn -cp "$ASM" -d "$OUT/gen" Transform.java
java -cp "$OUT/gen:$ASM" Transform "$SDK_JAR" "$OUT/android-mock.jar" >/dev/null
javac -nowarn -d "$OUT/hooks" -cp "$OUT/android-mock.jar" Hooks.java
javac -nowarn -encoding UTF-8 -d "$OUT/driver" -cp "$SDK_JAR:$ROOT/build/obj:$OUT/hooks" Smoke.java
java -Xss4m -cp "$OUT/hooks:$OUT/android-mock.jar:$JSON:$ROOT/build/obj:$OUT/driver" com.ankineer.app.Smoke
