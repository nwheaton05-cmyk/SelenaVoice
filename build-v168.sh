#!/bin/bash
# Manual APK build for Selena Voice v1.68 — ZERO-GAP WAKE HANDOFF (Nick 2026-10-29 15:19): at a KWS spot the spotter capture never stops — it switches to handoff mode, forwarding chunks to WakeStreamSession (own streaming ASR on the drone-path model), which decodes a 350 ms ring seed from the wake word end, then live chunks: no recognizer cold start, no clipped first words. Fallback = the pre-v1.68 cold-start path when the ASR model is not present. ALSO the typed-record fix (15:19): a manual text-box Send renders into the message record immediately (it already recorded via MessageHistory).
# (v1.67 base — Item 5 timing ruling, Nick 2026-10-29 15:11): ONE steady-connection constant, STEADY_CONNECTION_MS = 7000 (inside his 5-10 s window), gates BOTH the reconnect announcement (only when held text waits) and the once-flag reset; a flicker shorter than the window counts as neither and the episode continues. Supersedes v1.66, which announced immediately and used a 20 s reset-only timer.
# (v1.66 base — Item 5 refinement, Nick 2026-10-29 15:08): the FULL offline notice plays ONCE per episode; subsequent offline dictations in the same episode get only a short bundled confirmation, alternating "Saved." / "Added to your queue." The once-flag resets only after a STEADY connection (20 s continuous) — flapping does not re-arm the full notice.
# (v1.65 base:) (Nick 2026-10-29 14:52-14:57, OFFLINE DICTATION WORKFLOW — the complete workflow, both surfaces): when a dictation's delivery fails offline, the bundled PRE-RENDERED clip fallback/offline.mp3 plays after the dictation ends ("Sorry, I'm offline — I'll hold your words here and let you know when you're back online so you can send them." — final script approved 14:57). The dictated text STACKS in spoken order into an on-device held list (SharedPreferences unsent_dictation_stack) and is NEVER auto-delivered — manual resend only (auto-delivery outbox rejected 14:53). On reconnect, ONLY when held text is waiting, the bundled fallback/reconnected.mp3 announces "Nick, you're back online, you can send now." (approved wording 14:57; the standalone is Nick's app, so the name is rendered in). Detection: POST failure + a ConnectivityManager default-network callback. THE TEXT BOX (Nick 14:58 — the standalone had none): activity_main.xml gains a Text box field + Send button above Messages; the field mirrors the held_text_box preference the service stacks into, the user edits/clears freely, and Send is the only exit (one manual post; success clears, failure keeps everything held). Builds on v1.64 (earbud second-voice mode + interruption context) and v1.63 (no media-volume writes; her 1.25x gain).
# Stamps targetSdkVersion=34 properly via aapt2 link.
set -e
cd "$(dirname "$0")"

JAVA_HOME=~/workspace/.jdk/jdk-21.0.12.1
ANDROID_HOME=~/workspace/.android-sdk
BT=$ANDROID_HOME/build-tools/34.0.0
PLATFORM=$ANDROID_HOME/platforms/android-34/android.jar
export PATH=$JAVA_HOME/bin:$BT:$PATH

OUT=build/v168
rm -rf $OUT
mkdir -p $OUT/gen $OUT/classes $OUT/dex

echo "== sherpa-onnx artifacts (v1.68) =="
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
  --version-code 70 \
  --version-name 1.68 \
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
  --out $OUT/selena-voice-v1.68.apk $OUT/aligned.apk

echo "== verify =="
apksigner verify --print-certs $OUT/selena-voice-v1.68.apk | head -3
aapt2 dump badging $OUT/selena-voice-v1.68.apk | grep -E "sdkVersion|targetSdkVersion|versionCode|application-label" | head -5
unzip -l $OUT/selena-voice-v1.68.apk | grep -E "classes.*\.dex|lib/arm64-v8a|assets/kws" 
echo "BUILD DONE: $OUT/selena-voice-v1.68.apk"
# Nick 2026-10-18: also stage copies at the deliverable locations
mkdir -p app/build
cp $OUT/selena-voice-v1.68.apk app/build/selena-voice-v1.68.apk
cp $OUT/selena-voice-v1.68.apk ~/workspace/your_files/selena-voice-v1.68.apk
echo "COPIED: app/build/selena-voice-v1.68.apk, ~/workspace/your_files/selena-voice-v1.68.apk"
