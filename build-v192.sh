#!/bin/bash
# Selena Voice v1.92 (code 94) — BEEP FIX + WEDGE FIX (Nick, 2026-11-05, approved in main chat "build v1.92 the usual way").
# CoachStreamer.java only: (1) recognizer beep suppression restored — SYSTEM/NOTIFICATION/ALARM/VOICE_CALL
# hard-muted for the whole coach session (zero + setStreamMute, the v1.48 recipe deleted in v1.82);
# MUSIC muted only in windows around recognizer start/stop edges so her playback is never silenced.
# (2) Recognizer destroyed + recreated on every restart — v1.91 reused one object and its first live
# session wedged after 3 segments (mic cycling, no frames; Nick's extra dictation never landed).
# (3) Longer recognizer silence tolerance (3s complete / 2.5s possibly) — fewer cycles in pauses.
# Everything else is v1.91 verbatim. Manual pipeline, same keystore, targetSdk 34.
set -e
cd "$(dirname "$0")"

JAVA_HOME=~/workspace/.jdk/jdk-21.0.12.1
ANDROID_HOME=~/workspace/.android-sdk
BT=$ANDROID_HOME/build-tools/34.0.0
PLATFORM=$ANDROID_HOME/platforms/android-34/android.jar
export PATH=$JAVA_HOME/bin:$BT:$PATH

OUT=build/v192
rm -rf $OUT
mkdir -p $OUT/gen $OUT/classes $OUT/dex

echo "== aapt2 compile =="
aapt2 compile --dir app/src/main/res -o $OUT/res.zip

echo "== aapt2 link (targetSdk=34) =="
aapt2 link -o $OUT/base.apk \
  -I $PLATFORM \
  --manifest app/src/main/AndroidManifest.xml \
  --min-sdk-version 26 \
  --target-sdk-version 34 \
  --version-code 94 \
  --version-name 1.92 \
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
javac -source 17 -target 17 -nowarn -cp $PLATFORM -d $OUT/classes @$OUT/sources.txt

echo "== d8 =="
d8 --min-api 26 --lib $PLATFORM --output $OUT/dex $(find $OUT/classes -name "*.class" | tr '\n' ' ')

echo "== package =="
cp $OUT/base.apk $OUT/unsigned.apk
(cd $OUT/dex && zip -q ../unsigned.apk *.dex)

echo "== zipalign =="
zipalign -f -p 4 $OUT/unsigned.apk $OUT/aligned.apk

echo "== sign =="
KS_PASS=$(grep storePassword keystore.properties | cut -d= -f2 | tr -d ' ')
KEY_ALIAS=$(grep keyAlias keystore.properties | cut -d= -f2 | tr -d ' ')
KEY_PASS=$(grep keyPassword keystore.properties | cut -d= -f2 | tr -d ' ')
export KS_PASS KEY_PASS
apksigner sign --ks selena-voice.keystore --ks-pass env:KS_PASS --ks-key-alias "$KEY_ALIAS" --key-pass env:KEY_PASS \
  --out $OUT/selena-voice-v1.92.apk $OUT/aligned.apk

echo "== verify =="
apksigner verify --print-certs $OUT/selena-voice-v1.92.apk | head -3
aapt2 dump badging $OUT/selena-voice-v1.92.apk | grep -E "sdkVersion|targetSdkVersion|versionCode|application-label" | head -5
echo "== permissions =="
aapt2 dump permissions $OUT/selena-voice-v1.92.apk
unzip -l $OUT/selena-voice-v1.92.apk | grep -E "classes.*\.dex"

mkdir -p app/build
cp $OUT/selena-voice-v1.92.apk app/build/selena-voice-v1.92.apk
cp $OUT/selena-voice-v1.92.apk ~/workspace/your_files/selena-voice-v1.92.apk
echo "BUILD DONE: $OUT/selena-voice-v1.92.apk"
