#!/bin/bash
# Selena Voice v1.93 (code 95) — OWN CAPTURE + OWN ASR + FULL DUPLEX (Nick, 2026-11-05: "Let's do it!").
# His two rulings after the v1.92 field test: (1) the beep fix is NOT turning volumes up and down
# ("pure laziness") — v1.92's stream-mute machinery is DELETED from CoachStreamer; (2) Selena Voice
# must PLAY while the coach-session mic is live — simultaneous hearing both ways, or live coaching
# doesn't work. Implementation: the platform SpeechRecognizer is GONE from the coach path (every
# beep came from its start/stop cycle; its restart cycle was also the v1.91 wedge). CoachStreamer
# now captures with its own AudioRecord (opened once per session — silent by nature) feeding the
# sherpa-onnx streaming Zipformer en-20M this app field-proved in drone mode (v1.61/62) and
# zero-gap wake (v1.68), both deleted in v1.82 and revived: DroneModelStore.java restored verbatim
# from f168e8a; the model files themselves were found MISSING from the storage bucket (404) and
# re-uploaded 2026-11-05 from the official sherpa-onnx release tarball — SHA-256 verified identical
# to DroneModelStore's pinned hashes before upload. Duplex: CoachStreamer registers its
# AudioRecord audio-session id with VoiceService before recording starts; the v1.84 mic-hold
# filter ignores exactly that configuration ("own-coach") — foreign apps still hold playback.
# Finals whose segment overlapped her playback carry meta {playback_overlap: true}.
# Wire format, two doors, disclosure gate: unchanged from v1.91/92.
# Sherpa staging follows build-v168.sh: AAR from the Siteview wrapper libs, kotlin-stdlib from
# the wrapper maven cache, only libsherpa-onnx-jni.so + libonnxruntime.so packaged (arm64).
# Manual pipeline, same keystore, targetSdk 34.
set -e
cd "$(dirname "$0")"

JAVA_HOME=~/workspace/.jdk/jdk-21.0.12.1
ANDROID_HOME=~/workspace/.android-sdk
BT=$ANDROID_HOME/build-tools/34.0.0
PLATFORM=$ANDROID_HOME/platforms/android-34/android.jar
export PATH=$JAVA_HOME/bin:$BT:$PATH

OUT=build/v193
rm -rf $OUT
mkdir -p $OUT/gen $OUT/classes $OUT/dex

echo "== sherpa-onnx artifacts (v1.93) =="
SHERPA_AAR=$HOME/workspace/siteview/app/wrapper/android/app/libs/sherpa-onnx-1.12.40.aar
KOTLIN_STDLIB=$HOME/workspace/siteview/wrapper/maven-cache/org/jetbrains/kotlin/kotlin-stdlib/1.9.25/kotlin-stdlib-1.9.25.jar
for f in "$SHERPA_AAR" "$KOTLIN_STDLIB"; do
  if [ ! -f "$f" ]; then echo "MISSING BUILD INPUT: $f"; exit 1; fi
done
mkdir -p $OUT/sherpa $OUT/libstage/lib/arm64-v8a
unzip -o -q "$SHERPA_AAR" classes.jar "jni/arm64-v8a/*" -d $OUT/sherpa
cp $OUT/sherpa/jni/arm64-v8a/libsherpa-onnx-jni.so \
   $OUT/sherpa/jni/arm64-v8a/libonnxruntime.so \
   $OUT/libstage/lib/arm64-v8a/
echo "sherpa classes + arm64 libs staged"

echo "== aapt2 compile =="
aapt2 compile --dir app/src/main/res -o $OUT/res.zip

echo "== aapt2 link (targetSdk=34) =="
aapt2 link -o $OUT/base.apk \
  -I $PLATFORM \
  --manifest app/src/main/AndroidManifest.xml \
  --min-sdk-version 26 \
  --target-sdk-version 34 \
  --version-code 95 \
  --version-name 1.93 \
  --java $OUT/gen \
  $OUT/res.zip

echo "== inject google tts key =="
mkdir -p $OUT/srcjava
cp -r app/src/main/java/* $OUT/srcjava/
python3 - "$OUT/srcjava/com/selena/voice/VoiceService.java" <<'PYEOF'
import sys
p = sys.argv[1]
key = open('/home/hatch/workspace/voice_queue/.google-cloud-tts-key').read().strip()
s = open(p).read()
assert '__GOOGLE_TTS_API_KEY__' in s, 'placeholder missing'
open(p, 'w').write(s.replace('__GOOGLE_TTS_API_KEY__', key))
print('google tts key injected')
PYEOF
if grep -q "__GOOGLE_TTS_API_KEY__" $OUT/srcjava/com/selena/voice/VoiceService.java; then
  echo "KEY INJECTION FAILED"; exit 1
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
export KS_PASS KEY_PASS
apksigner sign --ks selena-voice.keystore --ks-pass env:KS_PASS --ks-key-alias "$KEY_ALIAS" --key-pass env:KEY_PASS \
  --out $OUT/selena-voice-v1.93.apk $OUT/aligned.apk

echo "== verify =="
apksigner verify --print-certs $OUT/selena-voice-v1.93.apk | head -3
aapt2 dump badging $OUT/selena-voice-v1.93.apk | grep -E "sdkVersion|targetSdkVersion|versionCode|application-label" | head -5
echo "== permissions =="
aapt2 dump permissions $OUT/selena-voice-v1.93.apk
unzip -l $OUT/selena-voice-v1.93.apk | grep -E "classes.*\.dex|lib/arm64-v8a"
ls -la $OUT/selena-voice-v1.93.apk

mkdir -p app/build
cp $OUT/selena-voice-v1.93.apk app/build/selena-voice-v1.93.apk
cp $OUT/selena-voice-v1.93.apk ~/workspace/your_files/selena-voice-v1.93.apk
echo "BUILD DONE: $OUT/selena-voice-v1.93.apk"
