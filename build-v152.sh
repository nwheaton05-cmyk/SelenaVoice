#!/bin/bash
# Manual APK build for Selena Voice (sdkmanager/gradle network-blocked).
# Stamps targetSdkVersion=34 properly via aapt2 link.
set -e
cd "$(dirname "$0")"

JAVA_HOME=~/workspace/.jdk/jdk-21.0.12.1
ANDROID_HOME=~/workspace/.android-sdk
BT=$ANDROID_HOME/build-tools/34.0.0
PLATFORM=$ANDROID_HOME/platforms/android-34/android.jar
export PATH=$JAVA_HOME/bin:$BT:$PATH

OUT=build/v152
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
  --version-code 54 \
  --version-name 1.52 \
  --java $OUT/gen \
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
  -cp $PLATFORM \
  -d $OUT/classes \
  @$OUT/sources.txt

echo "== d8 =="
d8 --min-api 26 --lib $PLATFORM \
  --output $OUT/dex \
  $(find $OUT/classes -name "*.class" | tr '\n' ' ')

echo "== package =="
cp $OUT/base.apk $OUT/unsigned.apk
(cd $OUT/dex && zip -q ../unsigned.apk classes.dex)

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
  --out $OUT/selena-voice-v1.52.apk $OUT/aligned.apk

echo "== verify =="
apksigner verify --print-certs $OUT/selena-voice-v1.52.apk | head -3
aapt2 dump badging $OUT/selena-voice-v1.52.apk | grep -E "sdkVersion|targetSdkVersion|versionCode|application-label" | head -5
echo "BUILD DONE: $OUT/selena-voice-v1.52.apk"
# Nick 2026-10-18: also stage copies at the deliverable locations
mkdir -p app/build
cp $OUT/selena-voice-v1.52.apk app/build/selena-voice-v1.52.apk
cp $OUT/selena-voice-v1.52.apk ~/workspace/your_files/selena-voice-v1.52.apk
echo "COPIED: app/build/selena-voice-v1.52.apk, ~/workspace/your_files/selena-voice-v1.52.apk"
