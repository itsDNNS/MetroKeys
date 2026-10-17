#!/bin/sh
# Query latency of the engine on a device or emulator (spec 05 §2.3, 05d §7; docs/ENGINE.md "Latency").
#
# Compiles the engine and app.metrokeys.engine.eval.DeviceBenchmark, shrinks and optimises them with R8 in release mode
# (as the release APK does), packs the dex with the bundled dictionaries and runs it with app_process on the device.
#
#   SERIAL=emulator-5580 COUNT=2000 tools/device-benchmark.sh [TAG ...]
#
# Needs JAVA_HOME (JDK 21), ANDROID_HOME (default ~/Android/sdk) and the device given by SERIAL.
set -e
ROOT=$(cd "$(dirname "$0")/.." && pwd)
SERIAL=${SERIAL:?set SERIAL to the device serial}
COUNT=${COUNT:-2000}
SDK=${ANDROID_HOME:-$HOME/Android/sdk}
BT=$(ls -d "$SDK"/build-tools/* | sort -V | tail -1)
PLATFORM=$(ls -d "$SDK"/platforms/android-* | sort -V | tail -1)
ADB="$SDK/platform-tools/adb -s $SERIAL"
OUT="$ROOT/build-data/device-benchmark"
TAGS=${*:-"de-DE en-US es-ES fr-FR it-IT pt-BR"}

cd "$ROOT"
./gradlew -q :engine:testClasses
STDLIB=$(find "$HOME/.gradle/caches/modules-2/files-2.1/org.jetbrains.kotlin/kotlin-stdlib" -name "kotlin-stdlib-2.4.*.jar" ! -name "*sources*" | sort | tail -1)
rm -rf "$OUT"
mkdir -p "$OUT/classes" "$OUT/data"
cp -r engine/build/classes/kotlin/main/. "$OUT/classes/"
mkdir -p "$OUT/classes/app/metrokeys/engine/eval"
cp engine/build/classes/kotlin/test/app/metrokeys/engine/eval/DeviceBenchmark*.class "$OUT/classes/app/metrokeys/engine/eval/"
(cd "$OUT/classes" && "$JAVA_HOME/bin/jar" cf ../input.jar .)
cat > "$OUT/rules.pro" <<'RULES'
-keep class app.metrokeys.engine.eval.DeviceBenchmark { public static void main(java.lang.String[]); }
-dontwarn **
RULES
"$JAVA_HOME/bin/java" -cp "$BT/lib/d8.jar" com.android.tools.r8.R8 --release --min-api 26 \
    --lib "$PLATFORM/android.jar" --pg-conf "$OUT/rules.pro" --output "$OUT/dex.zip" "$OUT/input.jar" "$STDLIB"
mkdir -p "$OUT/pack"
(cd "$OUT/pack" && unzip -q ../dex.zip && mkdir -p app/metrokeys/engine/dict && \
    cp "$ROOT"/engine/src/main/resources/app/metrokeys/engine/dict/* app/metrokeys/engine/dict/ && \
    "$JAVA_HOME/bin/jar" cf ../bench.jar .)
for t in $TAGS; do cp "evaluation/testsets/$t.A.tsv" "$OUT/data/"; done
cp spec/data/layouts-*.json "$OUT/data/"
$ADB shell mkdir -p /data/local/tmp/mk-bench
$ADB push "$OUT/bench.jar" /data/local/tmp/mk-bench/ > /dev/null
$ADB push "$OUT/data/." /data/local/tmp/mk-bench/ > /dev/null
$ADB shell "cd /data/local/tmp/mk-bench && CLASSPATH=/data/local/tmp/mk-bench/bench.jar app_process -Xmx256m /data/local/tmp/mk-bench app.metrokeys.engine.eval.DeviceBenchmark /data/local/tmp/mk-bench $COUNT $TAGS"
$ADB shell rm -rf /data/local/tmp/mk-bench
