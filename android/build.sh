#!/bin/bash
# Builds and installs Skyline Weather. Usage: ./build.sh [device]
set -e
cd "$(dirname "$0")"
SDK=~/Library/Android/sdk; BT=$SDK/build-tools/36.0.0; JAR=$SDK/platforms/android-36/android.jar
LIBS="libs/shizuku-api.jar:libs/shizuku-provider.jar:libs/shizuku-aidl.jar"
export JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home; export PATH="$JAVA_HOME/bin:$PATH"
rm -rf build; mkdir -p build/res build/gen build/classes build/dex assets
cp ../weather.html assets/weather.html
rm -f assets/clouds-*.webp && cp ../clouds-*.webp assets/
$BT/aapt2 compile --dir res -o build/res/
$BT/aapt2 link -I $JAR --manifest AndroidManifest.xml -A assets -o build/base.apk --java build/gen build/res/*.flat
javac -nowarn -source 17 -target 17 -cp "$JAR:$LIBS" -d build/classes $(find src build/gen -name '*.java') 2>&1 | grep -v "^Note" || true
[ -f build/classes/com/skyline/weather/WeatherWidget.class ] || { echo "javac failed"; exit 1; }
$BT/d8 --lib $JAR --min-api 26 --output build/dex $(find build/classes -name '*.class') libs/shizuku-api.jar libs/shizuku-provider.jar libs/shizuku-aidl.jar
(cd build/dex && zip -q ../base.apk classes.dex)
$BT/zipalign -f 4 build/base.apk build/aligned.apk
$BT/apksigner sign --ks ~/.android/debug.keystore --ks-pass pass:android --key-pass pass:android --out build/SkylineWeather.apk build/aligned.apk
if [ -n "$1" ]; then adb -s "$1" install -r --no-incremental build/SkylineWeather.apk | tail -1; fi
