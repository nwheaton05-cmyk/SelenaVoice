#!/bin/bash
# Manual APK build for Selena Voice v1.78 — PIN-FIRST for non-AA splits (Nick's order, 2026-10-30 19:30 EDT).
# WHY: Nick's standard for BOTH trucks: Spotify stays on the truck uninterrupted, Selena plays only in his earbuds ("Smokin' Buds"), neither touches the other. In the Chevy (MyLink, plain Bluetooth, aa=none) v1.76 alternated CASE_A (earbuds hold the A2DP river — Spotify dragged into the earbuds during the message, handed back after) and CASE_B_HEADSET (MyLink holds A2DP; call channel makes the head unit hand the whole river over — on the 19:25 test ALL audio, Spotify + Selena, ended up in his earbuds). The per-player MEDIA PIN was never attempted in the Chevy: verdicts went straight to CASE_B_HEADSET because MyLink was not holding the comm lane at those moments (CASE_MEDIA_PIN only fired on a foreign comm incumbent). Android Auto refused the pin (v1.76 field logs); whether a plain-Bluetooth head unit accepts it is the open question this build answers.
# THE BUILD (decision-order change in evaluateLane ONLY — all v1.77 machinery is verdict-driven and carries over untouched): in a NON-AA split (aa=none, earbuds present, another BT device holds the media route), if the earbuds have an A2DP device among the outputs, the verdict is CASE_MEDIA_PIN FIRST — before the foreign-incumbent and headset branches: per-player setPreferredDevice on Selena's player only, NO focus, pre-play route proof (proof fails or pin refused -> headset fallback when the v1.77 toggle is ON, else park). lanePinWhy records the branch ("pin-first (non-AA split)" vs the v1.76 "a foreign device holds the communication lane") for the begin log. If the earbuds are present SCO-only (no A2DP target — the common Chevy shape), no pin is possible: the decision log and the fresh-headset-session log both say "lane: pin-first skipped — no earbuds A2DP target (SCO only)", and the v1.77 branches stand (foreign incumbent -> PARK; otherwise headset channel). Device TTS under a non-AA pin verdict: cannot be pinned — speaks via the headset-fallback session when the toggle is ON, stays held (text only) when OFF (v1.77 arrangement, unchanged). Carried over untouched: session-held comm (once per message burst, 5 s idle hold), sticky fallback verdict (one pin attempt per item per presence/verdict change), presence scan + debounce, sequential drain, Case A bit-for-bit, whole-lane OFF bit-for-bit, AA path (CASE_AA_PIN -> fallback -> park). The pin is per-player by construction and the proof gate is the guarantee Spotify never moves: the item plays only if the proven routed device IS the earbuds. STANDALONE ONLY (Nick ruled Siteview out of lane work, 2026-10-30 16:35 EDT).
# ---- v1.77 header (kept for the record) ----
# Manual APK build for Selena Voice v1.77 — HEADSET FALLBACK for the earbuds lane (Nick's decision, 2026-10-30 ~19:09 EDT).
# FIELD STATE: v1.76 detects perfectly (aa=remote-submix -> CASE_AA_PIN, earbuds "Smokin' Buds" present) but Android REFUSES the media pin under Android Auto ("setPreferredDevice refused for Smokin' Buds" on every attempt, live logs 19:03) -> everything parks -> total silence. Samsung Separate App Sound has been set on his phone for a long time and does NOT rescue the AA case (lane off at 19:06, she still played on Android Auto) — that door is closed; nothing here depends on SAS. Nick's verdict: v1.75's messy half-and-half (audible in the earbuds via the headset/call channel, AA "call ended" announcements + some flapping) beats v1.76's silence. Hard rules unchanged: NEVER any Selena output on the truck radio / AA / speaker; the fallback goes to the EARBUDS only.
# THE BUILD: new user setting "Headset fallback" (KEY_HEADSET_FALLBACK, default ON — his stated preference), a switch in the output settings family under "Earbuds-only playback" (R.id.headsetFallbackSwitch). When ON, in a split case (CASE_B_HEADSET already headset; CASE_AA_PIN / CASE_MEDIA_PIN) where the clean media pin is refused (setPreferredDevice false/throws in playFileNow), unprovable (pre-play route-proof gate fails), or has no target device at laneBegin — or the item is device TTS, which can never be pinned — the lane converts to a HEADSET-FALLBACK session (LANE_BEGIN_HEADSET_FALLBACK) via laneFallbackToHeadset()/startHeadsetSession(): MODE_IN_COMMUNICATION + setCommunicationDevice to the name-matched earbuds comm device, USAGE_VOICE_COMMUNICATION, NO audio-focus request. When OFF, v1.76 park-on-refusal holds exactly. The fallback inherits ALL v1.76 discipline: ONE session per message (+ following items, 5 s idle hold), comm set/clear per SESSION never per segment, all-device presence scan + debounce, sequential drain, full decision logging with the fallback marked ("lane: pin refused — headset fallback (user setting on)" / "session acquired — headset-fallback"). Under AA the fallback knowingly accepts AA treating the session as a call (announcements are the accepted cost) — but the AUDIO goes only to the earbuds; if setCommunicationDevice is refused too, THEN park (zero output) — never legacy routing, never speaker, never the car. A fallback session is STICKY for its verdict (laneFallbackVerdict): later items reuse it without re-attempting the pin — one pin attempt per item per presence/verdict change, no retry storms. Case A (earbuds hold the media route) untouched: media + MAY_DUCK. Whole-lane toggle OFF untouched: legacy behavior bit-for-bit. STANDALONE ONLY (Nick ruled Siteview out of lane work, 2026-10-30 16:35 EDT).
# ---- v1.76 header (kept for the record) ----
# Manual APK build for Selena Voice v1.76 — EARBUDS LANE, ANDROID AUTO DETECTION GAP (Nick field retest, 2026-10-30 18:25–18:28 EDT: "kind of working, just the connection is unstable" + Android Auto announcing "call ended" every time audio went to his earbuds).
# (A) AA DETECTION v2: Nick's truck is a Ford F-250 (Bluetooth name "Ford F-250", Ford SYNC, SCO + A2DP) running Android Auto with the media route held by a REMOTE-SUBMIX device — the lane probe reported route='remote-submix' (AudioDeviceInfo type 25) on every decision. v1.75's AA detection knew only USB audio, so aa=none everywhere, the verdict never became CASE_AA_PIN, and everything fell into CASE_B_HEADSET (the comm/SCO channel). androidAutoSignal() now also fires on (a) a remote-submix route holder and (c) a remote-submix output present while a non-earbuds BT vehicle device is connected; the fired signal is logged (aa=remote-submix / aa=usb-route / ...). Under AA the comm channel is NEVER opened (a defense-in-depth check in laneBegin parks instead), and an unprovable pin parks with zero output, per the 16:40 hard rule.
# (B) FOREIGN COMM INCUMBENCY (generalized poison): outside AA too, if another BT call device (e.g. a car) stands in the available-communication list — or currently holds the communication device — she does not fight it for the phone's single call lane: verdict CASE_MEDIA_PIN pins her media to the earbuds' A2DP (same proof-gate discipline as the AA pin); no A2DP presence → PARK. If the earbuds themselves currently hold the comm device (her live session), there is no foreign incumbent.
# (C) LANE SESSION PER MESSAGE, NOT PER SEGMENT: v1.75 acquired and released the lane per segment, so each segment of a message was one comm set/clear — Android Auto treated every segment as a phone call ("call ended" at each seam) and the two HFP claimants (truck SYNC vs earbuds) kept tearing each other's SCO link down ("earbuds removal seen" flapping). The lane is now a session: acquired once (lane: session acquired), reused across all segments of a message and across immediately-following queued items (lane: session continuing), and released only after the playback queues have been idle for 5 s (lane: session released — N item(s), held Xms). A user stop ends the session immediately; earbuds lost during the idle hold-over just ends it. Per-segment set/clear of the communication device is gone.
# Everything else is v1.75 verbatim: all-device presence scan, decision logging with full device lists on signature change (now with the AA signal + foreignComm + session lines), presence-loss debounce, sequential drain, Case A same-device duck, both-lanes-untouched (no focus in split/AA/pin cases). STANDALONE ONLY (Nick ruled Siteview out of lane fixes, 2026-10-30 16:35 EDT).
# ---- v1.75 header (kept for the record) ----
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

OUT=build/v178
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
  --version-code 80 \
  --version-name 1.78 \
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
  --out $OUT/selena-voice-v1.78.apk $OUT/aligned.apk

echo "== verify =="
apksigner verify --print-certs $OUT/selena-voice-v1.78.apk | head -3
aapt2 dump badging $OUT/selena-voice-v1.78.apk | grep -E "sdkVersion|targetSdkVersion|versionCode|application-label" | head -5
unzip -l $OUT/selena-voice-v1.78.apk | grep -E "classes.*\.dex|lib/arm64-v8a|assets/kws"
echo "BUILD DONE: $OUT/selena-voice-v1.78.apk"
# Nick 2026-10-18: also stage copies at the deliverable locations
mkdir -p app/build
cp $OUT/selena-voice-v1.78.apk app/build/selena-voice-v1.78.apk
cp $OUT/selena-voice-v1.78.apk ~/workspace/your_files/selena-voice-v1.78.apk
echo "COPIED: app/build/selena-voice-v1.78.apk, ~/workspace/your_files/selena-voice-v1.78.apk"
