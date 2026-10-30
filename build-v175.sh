#!/bin/bash
# Manual APK build for Selena Voice v1.75 — EARBUDS LANE FIX (Nick field failures, 2026-10-30 16:06 + 16:18 EDT).
# (1) PRESENCE: v1.74's isEarbudsConnected() rode on getConnectedBtAudioDeviceName() — the FIRST enumerated A2DP/SCO output — so with earbuds + truck both connected, the truck enumerated first and the lane concluded "no earbuds" and parked everything. Presence is now an ALL-DEVICE scan (any A2DP/SCO/BLE_HEADSET/BLE_SPEAKER output, or any available communication device, fuzzy-matching the saved name "Smokin' Buds"), independent of the media route; findEarbudsCommDevice searches getAvailableCommunicationDevices() first (a call-connected headset is listed there even when absent from GET_DEVICES_OUTPUTS). Every lane decision logs the full evidence (lane: decision[...] — saved name, present, route holder, AA signal, verdict, all BT/USB outputs name+type, comm devices).
# (2) ANDROID AUTO PRESENT — the "AA-present earbuds media pin" case (verdict CASE_AA_PIN): the "truck radio" is Android Auto, which owns the HFP lane — v1.74's comm-channel Case B got setCommunicationDevice REFUSED, surfaced as a phone call on the head unit, and paused Spotify. HARD RULE (Nick, 2026-10-30 16:40): Selena must NEVER output through Android Auto / the truck radio / car speakers — not as a fallback, not for a split second. When an AA signal fires (media route holder is a USB audio device, or a USB audio device is present while a non-earbuds BT device holds the route), her MP3s play as MEDIA pinned per-player to the earbuds (setPreferredDevice), NO audio focus, NO mode changes, NEVER the communication channel — and the pin is PROVEN BEFORE start(): a silent probe track pinned to the same earbuds must report the earbuds as its routed device (probePinnedRoutedDevice); the car route, any other device, or UNKNOWN parks the item pre-output ("lane: AA-present route NOT earbuds (<actual>) — parked, no output"). Post-start route checks remain as a mid-play safety net only. Device TTS cannot be pinned, so spoken TTS holds when AA is present (text stays written). Plain-Bluetooth split keeps the v1.74 headset channel, but a comm refusal now degrades to PARKING (v1.74 fell through to legacy routing and played on the truck).
# (3) DEBOUNCE + ORDERLY DRAIN: presence loss mid-play is debounced (removals inside the lane's own route-transition window ignored; otherwise 3 s sustained absence required before abort — the 16:18 "earbuds lost mid-play" flap was SCO churn from the lane's own comm setup/teardown). A presence flip releases parked items SEQUENTIALLY through the priority chain (the 16:18 burst had pump + priority interrupting each other at 0 ms); new urgent arrivals keep their standing interrupt rights.
# Case A (earbuds hold the media route — Spotify in the same earbuds) is bit-for-bit v1.74: media playback + MAY_DUCK, per Nick's 14:44 ruling. Toggle off / no name: pre-lane behavior.
# Stamps targetSdkVersion=34 properly via aapt2 link.
set -e
cd "$(dirname "$0")"

JAVA_HOME=~/workspace/.jdk/jdk-21.0.12.1
ANDROID_HOME=~/workspace/.android-sdk
BT=$ANDROID_HOME/build-tools/34.0.0
PLATFORM=$ANDROID_HOME/platforms/android-34/android.jar
export PATH=$JAVA_HOME/bin:$BT:$PATH

OUT=build/v175
rm -rf $OUT
mkdir -p $OUT/gen $OUT/classes $OUT/dex

echo "== sherpa-onnx artifacts (v1.70) =="
# Self-owned inputs, already on this machine: the AAR Siteview ships,
# the kotlin-stdlib its build resolved, and the KWS model bundle in
# kws-model-cache/ (git-ignored build input; keywords.txt is ours and
# lives in app/src/main/assets/kws/).
SHERPA_AAR=$HOME/workspace/siteview/app/wrapper/android/app/libs/sherpa-onnx-1.12.40.aar
KOTLIN_STDLIB=$HOME/workspace/siteview/wrapper/maven-cache/org/jetbrains/kotlin/kotlin-stdlib/1.9.25/kotlin-stdlib-1.9.25.jar
KWS_SRC=$HOME/workspace/selena-voice/kws-model-cache/sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01
for f in "$SHERPA_AAR" "$KOTLIN_STDLIB" \
    "$KWS_SRC/encoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx" \
    "$KWS_SRC/decoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx" \
    "$KWS_SRC/joiner-epoch-12-avg-2-chunk-16-left-64.int8.onnx" \
    "$KWS_SRC/tokens.txt" app/src/main/assets/kws/keywords.txt; do
  if [ ! -f "$f" ]; then echo "MISSING BUILD INPUT: $f"; exit 1; fi
done
mkdir -p $OUT/sherpa $OUT/assets/kws $OUT/libstage/lib/arm64-v8a
unzip -o -q "$SHERPA_AAR" classes.jar "jni/arm64-v8a/*" -d $OUT/sherpa
# Only the two libs the JNI layer actually needs (DT_NEEDED of
# libsherpa-onnx-jni.so: libonnxruntime.so + system libs) — the C/CXX
# API libs in the AAR are dead weight for the Kotlin/JNI API.
cp $OUT/sherpa/jni/arm64-v8a/libsherpa-onnx-jni.so \
   $OUT/sherpa/jni/arm64-v8a/libonnxruntime.so \
   $OUT/libstage/lib/arm64-v8a/
cp app/src/main/assets/kws/keywords.txt $OUT/assets/kws/
mkdir -p $OUT/assets/fallback
cp app/src/main/assets/fallback/offline.mp3 app/src/main/assets/fallback/reconnected.mp3 app/src/main/assets/fallback/saved.mp3 app/src/main/assets/fallback/added.mp3 $OUT/assets/fallback/
mkdir -p $OUT/assets/holding
cp app/src/main/assets/holding/holding-note-*.mp3 $OUT/assets/holding/
cp "$KWS_SRC/encoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx" \
   "$KWS_SRC/decoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx" \
   "$KWS_SRC/joiner-epoch-12-avg-2-chunk-16-left-64.int8.onnx" \
   "$KWS_SRC/tokens.txt" $OUT/assets/kws/
echo "sherpa classes + arm64 libs + kws assets staged"

echo "== aapt2 compile =="
aapt2 compile --dir app/src/main/res -o $OUT/res.zip

echo "== aapt2 link (targetSdk=34) =="
aapt2 link -o $OUT/base.apk \
  -I $PLATFORM \
  --manifest app/src/main/AndroidManifest.xml \
  --min-sdk-version 26 \
  --target-sdk-version 34 \
  --version-code 77 \
  --version-name 1.75 \
  --java $OUT/gen \
  -A $OUT/assets \
  $OUT/res.zip

echo "== inject google tts key (v1.43) =="
mkdir -p $OUT/srcjava
cp -r app/src/main/java/* $OUT/srcjava/
python3 - "$OUT/srcjava/com/selena/voice/VoiceService.java" <<'PYEOF'
p = __import__('sys').argv[1]
key = open('/home/hatch/workspace/voice_queue/.google-cloud-tts-key').read().strip()
s = open(p).read()
assert '__GOOGLE_TTS_API_KEY__' in s, 'placeholder missing'
open(p, 'w').write(s.replace('__GOOGLE_TTS_API_KEY__', key))
print('google tts key injected')
PYEOF
if grep -q "__GOOGLE_TTS_API_KEY__" $OUT/srcjava/com/selena/voice/VoiceService.java; then
  echo "KEY INJECTION FAILED — placeholder still present"; exit 1
fi

echo "== javac =="
find $OUT/srcjava -name "*.java" > $OUT/sources.txt
find $OUT/gen -name "*.java" >> $OUT/sources.txt
javac -source 17 -target 17 -nowarn \
  -cp $PLATFORM:$OUT/sherpa/classes.jar:$KOTLIN_STDLIB \
  -d $OUT/classes \
  @$OUT/sources.txt

echo "== d8 (app classes + sherpa API + kotlin-stdlib) =="
d8 --min-api 26 --lib $PLATFORM \
  --output $OUT/dex \
  $(find $OUT/classes -name "*.class" | tr '\n' ' ') \
  $OUT/sherpa/classes.jar $KOTLIN_STDLIB

echo "== package =="
cp $OUT/base.apk $OUT/unsigned.apk
(cd $OUT/dex && zip -q ../unsigned.apk *.dex)
(cd $OUT/libstage && zip -q -r ../unsigned.apk lib)

echo "== zipalign =="
zipalign -f -p 4 $OUT/unsigned.apk $OUT/aligned.apk

echo "== sign =="
KS_PASS=$(grep storePassword keystore.properties | cut -d= -f2 | tr -d ' ')
KEY_ALIAS=$(grep keyAlias keystore.properties | cut -d= -f2 | tr -d ' ')
KEY_PASS=$(grep keyPassword keystore.properties | cut -d= -f2 | tr -d ' ')
# keystore path is relative to project root
export KS_PASS KEY_PASS
apksigner sign \
  --ks selena-voice.keystore --ks-pass env:KS_PASS --ks-key-alias "$KEY_ALIAS" --key-pass env:KEY_PASS \
  --out $OUT/selena-voice-v1.75.apk $OUT/aligned.apk

echo "== verify =="
apksigner verify --print-certs $OUT/selena-voice-v1.75.apk | head -3
aapt2 dump badging $OUT/selena-voice-v1.75.apk | grep -E "sdkVersion|targetSdkVersion|versionCode|application-label" | head -5
unzip -l $OUT/selena-voice-v1.75.apk | grep -E "classes.*\.dex|lib/arm64-v8a|assets/kws"
echo "BUILD DONE: $OUT/selena-voice-v1.75.apk"
# Nick 2026-10-18: also stage copies at the deliverable locations
mkdir -p app/build
cp $OUT/selena-voice-v1.75.apk app/build/selena-voice-v1.75.apk
cp $OUT/selena-voice-v1.75.apk ~/workspace/your_files/selena-voice-v1.75.apk
echo "COPIED: app/build/selena-voice-v1.75.apk, ~/workspace/your_files/selena-voice-v1.75.apk"
