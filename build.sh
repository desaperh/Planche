#!/usr/bin/env bash
# Compile Planche en APK signé, sans Gradle ni Android Studio.
# Prérequis : JDK 17+ et un SDK Android Linux avec build-tools + une plateforme (API 35 conseillée).
#   sdkmanager "build-tools;35.0.0" "platforms;android-35"
# Usage : ./build.sh            → build/Planche.apk
#         ./build.sh --install  → compile puis installe via adb si un appareil est branché
set -euo pipefail
cd "$(dirname "$0")"

SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}"
[ -d "$SDK/build-tools" ] || { echo "SDK Android introuvable ($SDK). Définissez ANDROID_HOME."; exit 1; }
BT="$(ls -d "$SDK"/build-tools/*/ | sort -V | tail -1)"; BT="${BT%/}"
PLATFORM="$(ls -d "$SDK"/platforms/android-*/ | sort -V | tail -1)"; PLATFORM="${PLATFORM%/}"
ANDROID_JAR="$PLATFORM/android.jar"
echo "build-tools : $BT"
echo "plateforme  : $PLATFORM"

# d8 et apksigner récents exigent Java 17+. Conda (base) met souvent un Java 8 en tête du PATH :
# on cherche alors un JDK adapté (JAVA_HOME, /usr/lib/jvm, SDKMAN, Android Studio).
java_major() {
  "$1" -version 2>&1 | sed -n 's/.*version "\([0-9]*\)\.\{0,1\}\([0-9]*\).*/\1 \2/p' | head -1 \
    | awk '{ print ($1 == 1) ? $2 : $1 }'
}
JAVA_BIN="$(command -v java || true)"
M="$( [ -n "$JAVA_BIN" ] && java_major "$JAVA_BIN" || true )"
if [ "${M:-0}" -lt 17 ] || ! command -v javac >/dev/null 2>&1; then
  JDK=""
  for j in "${JAVA_HOME:-}" /usr/lib/jvm/* "$HOME"/.sdkman/candidates/java/* \
           /opt/android-studio/jbr "$HOME"/android-studio/jbr /snap/android-studio/current/jbr; do
    [ -n "$j" ] && [ -x "$j/bin/java" ] && [ -x "$j/bin/javac" ] || continue
    m="$(java_major "$j/bin/java")"
    if [ "${m:-0}" -ge 17 ]; then JDK="$j"; break; fi
  done
  if [ -z "$JDK" ]; then
    echo "Java 17 ou plus est requis (trouvé : ${M:-aucun})."
    echo "Installez-le :  sudo apt install openjdk-17-jdk   (ou : conda install -c conda-forge openjdk=17)"
    exit 1
  fi
  export JAVA_HOME="$JDK"
  export PATH="$JDK/bin:$PATH"
fi
echo "Java        : $(java -version 2>&1 | head -1)"

VERSION_CODE=2
VERSION_NAME=1.1
OUT=build
rm -rf "$OUT"
mkdir -p "$OUT/classes" "$OUT/dex"

echo "› ressources + manifeste (aapt2)"
"$BT/aapt2" compile --dir res -o "$OUT/res.zip"
"$BT/aapt2" link -I "$ANDROID_JAR" --manifest AndroidManifest.xml \
  --min-sdk-version 28 --target-sdk-version 35 \
  --version-code "$VERSION_CODE" --version-name "$VERSION_NAME" \
  -o "$OUT/base.apk" "$OUT/res.zip"

echo "› Java (javac)"
find src -name '*.java' > "$OUT/sources.txt"
javac -Xlint:-options -proc:none -encoding UTF-8 -source 8 -target 8 \
  -bootclasspath "$ANDROID_JAR" -d "$OUT/classes" @"$OUT/sources.txt"
(cd "$OUT/classes" && jar cf ../classes.jar .)

echo "› dex (d8)"
"$BT/d8" --release --min-api 28 --lib "$ANDROID_JAR" --output "$OUT/dex" "$OUT/classes.jar"

echo "› assemblage"
python3 - "$OUT/base.apk" "$OUT/dex/classes.dex" <<'PY'
import sys, zipfile
with zipfile.ZipFile(sys.argv[1], "a", zipfile.ZIP_DEFLATED) as z:
    z.write(sys.argv[2], "classes.dex")
PY
"$BT/zipalign" -f -p 4 "$OUT/base.apk" "$OUT/aligned.apk"

# Clé conservée à côté du script : les mises à jour s'installent par-dessus sans désinstaller.
if [ ! -f planche.keystore ]; then
  echo "› création de la clé de signature (planche.keystore)"
  keytool -genkeypair -keystore planche.keystore -storetype PKCS12 -alias planche \
    -keyalg RSA -keysize 2048 -validity 10000 \
    -storepass planche -keypass planche -dname "CN=Planche" >/dev/null 2>&1
fi

echo "› signature (apksigner)"
"$BT/apksigner" sign --ks planche.keystore --ks-key-alias planche \
  --ks-pass pass:planche --key-pass pass:planche \
  --out "$OUT/Planche.apk" "$OUT/aligned.apk"
"$BT/apksigner" verify "$OUT/Planche.apk"

echo
echo "Autorisations demandées par l'APK :"
PERMS="$("$BT/aapt2" dump permissions "$OUT/Planche.apk" | grep -i 'uses-permission' || true)"
if [ -z "$PERMS" ]; then echo "  (aucune)"; else echo "$PERMS"; fi
echo
echo "APK prêt : $OUT/Planche.apk ($(du -h "$OUT/Planche.apk" | cut -f1))"

if [ "${1:-}" = "--install" ]; then
  ADB="$SDK/platform-tools/adb"
  command -v adb >/dev/null 2>&1 && ADB=adb
  "$ADB" install -r "$OUT/Planche.apk"
fi
