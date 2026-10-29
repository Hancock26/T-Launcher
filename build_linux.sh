#!/bin/sh
# Builds the APK on Linux without Gradle or the full Android SDK.
#   sudo apt install aapt dalvik-exchange zipalign apksigner default-jdk
#   ANDROID_JAR=/path/to/android-33/android.jar sh build_linux.sh
# android.jar (API 33) can be taken from any Android SDK (platforms/android-33/android.jar).
set -e
cd "$(dirname "$0")"

JAR=${ANDROID_JAR:?Set ANDROID_JAR to platforms/android-33/android.jar}
KS=${KEYSTORE:-$HOME/.android/debug.keystore}

if [ ! -f "$KS" ]; then
    mkdir -p "$(dirname "$KS")"
    keytool -genkeypair -v -keystore "$KS" -storepass android -alias androiddebugkey -keypass android \
        -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Android Debug,O=Android,C=US"
fi

rm -rf build && mkdir -p build/gen build/classes build/assets
cp -r assets/. build/assets/
# Personal build: PERSONAL_CAR=/folder/with/car.png,car_lit.png,car_top.png,car_top_lit.png
if [ -n "$PERSONAL_CAR" ]; then
    mkdir -p build/assets/car && cp "$PERSONAL_CAR"/car*.png build/assets/car/
    if [ -d "$PERSONAL_CAR/spin" ]; then mkdir -p build/assets/car/spin && cp "$PERSONAL_CAR"/spin/*.webp build/assets/car/spin/; fi
    echo "Personal build: car images included"
fi

aapt package -f -m -M AndroidManifest.xml -S res -A build/assets -I "$JAR" -J build/gen \
    --min-sdk-version 26 --target-sdk-version 28 -F build/unsigned.apk

javac -nowarn --release 8 -encoding UTF-8 -classpath "$JAR" -d build/classes \
    $(find src build/gen -name '*.java')

dalvik-exchange --dex --min-sdk-version=26 --output=build/classes.dex build/classes
(cd build && aapt add unsigned.apk classes.dex >/dev/null)

zipalign -f 4 build/unsigned.apk build/aligned.apk
apksigner sign --ks "$KS" --ks-pass pass:android --key-pass pass:android \
    --out build/T-Launcher.apk build/aligned.apk
apksigner verify build/T-Launcher.apk
echo "APK: build/T-Launcher.apk"
