#!/bin/bash
# Manual APK build for Selena Voice v1.64 (Nick 2026-10-29, PARITY BATCH Items 2 + 4): EARBUD SAMPLE-HOLD MODE for the second-voice system + INTERRUPTION CONTEXT. Item 2: the mode switch is the audio OUTPUT ROUTE — earbuds (product-name identity, isEarbudsConnected) = private: a second voice is held SILENTLY, a ~3s transient room sample is grabbed for identification playback (memory only, discarded after; the durable artifact is the voiceprint enrolled from the dictation's envelope measurements), at the next silent period she asks NICK who that was and plays the sample, the name enrolls the guest and only then is the content relayed — as a guest_relay directive (private proposal + consent query composed by the brain; never executed, never addressed to the guest). Known guests relay directly; never-identified content posts as guest_unidentified, never as a dictation. No earbuds = the v1.62 group flow, untouched. Item 4 (app half): a dictation landing while her message plays or is paused carries an "interrupts" object on the input post {rowId, state, heardFraction, topicOverlapHint on the TTS path} so the responder can run the ruled same-topic-revision / off-topic-park-and-finish branch with the heard fraction as delivered context. Builds on v1.63 (no system media-volume writes; her +gain at VOICE_OVER_MUSIC_TARGET 1.25).
# Stamps targetSdkVersion=34 properly via aapt2 link.
set -e
cd "$(dirname "$0")"

JAVA_HOME=~/workspace/.jdk/jdk-21.0.12.1
ANDROID_HOME=~/workspace/.android-sdk
BT=$ANDROID_HOME/build-tools/34.0.0
PLATFORM=$ANDROID_HOME/platforms/android-34/android.jar
export PATH=$JAVA_HOME/bin:$BT:$PATH

OUT=build/v164
rm -rf $OUT
mkdir -p $OUT/gen $OUT/classes $OUT/dex

echo "== sherpa-onnx artifacts (v1.64) =="
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
  --version-code 66 \
  --version-name 1.64 \
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
  --out $OUT/selena-voice-v1.64.apk $OUT/aligned.apk

echo "== verify =="
apksigner verify --print-certs $OUT/selena-voice-v1.64.apk | head -3
aapt2 dump badging $OUT/selena-voice-v1.64.apk | grep -E "sdkVersion|targetSdkVersion|versionCode|application-label" | head -5
unzip -l $OUT/selena-voice-v1.64.apk | grep -E "classes.*\.dex|lib/arm64-v8a|assets/kws" 
echo "BUILD DONE: $OUT/selena-voice-v1.64.apk"
# Nick 2026-10-18: also stage copies at the deliverable locations
mkdir -p app/build
cp $OUT/selena-voice-v1.64.apk app/build/selena-voice-v1.64.apk
cp $OUT/selena-voice-v1.64.apk ~/workspace/your_files/selena-voice-v1.64.apk
echo "COPIED: app/build/selena-voice-v1.64.apk, ~/workspace/your_files/selena-voice-v1.64.apk"
