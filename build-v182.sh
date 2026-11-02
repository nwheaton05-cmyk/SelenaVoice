#!/bin/bash
# Manual APK build for Selena Voice v1.82 — PLAYBACK-ONLY: MICROPHONE REMOVED ENTIRELY + DELETE-AFTER-FULL-PLAY (Nick, 2026-11-02 13:05 + 13:09 EST).
# (1) MIC REMOVAL (Nick's final pre-handoff condition): "I want no microphone abilities in Selena Voice." He responds in the Muse app; the dictation round trip is far too slow. REMOVED: the RECORD_AUDIO + READ_PHONE_STATE + FOREGROUND_SERVICE_MICROPHONE permissions from the manifest (service foregroundServiceType is mediaPlayback only, permanently) — the app CANNOT capture audio; all capture code (AudioRecord / SpeechRecognizer paths, 21 files deleted: recognizer listener + restart task, wake-word spotter/stream/directive (sherpa-onnx KWS), drone extraction session + model store + machine profiles, noise gate probes, FFT/spectral suppression, voiceprint + profiles + sync, guest profiles/directive, input poster, fast lane, phone-call callback, offline network callback); dictation (recording, sentence buffering/assembly, POSTs to /api/selena-voice/input); always-listening; push-to-talk (mic button + manual listen); the v1.65 offline-dictation machinery (stacking, bundled fallback notices, reconnect watcher); the v1.70 automatic holding note (its only trigger was a dictation post); the POLITE HOLD entirely — pumpAudioQueue / pumpTtsQueue / advanceAfterSegmentGap / priority arrivals + FIFO drain no longer consult any listening state; the echo-guard mic mute/unmute pair (methods AND all playback call sites); recognizer-beep stream zeroing; the dictation duck; the phone-call mic mute. Mic UI removed, not hidden: mic button, always-listening switch + pulse dot, dictation popup, "Tap mic" hint, voiceprint + machines buttons/dialogs, and the dictation text box are out of the layout and MainActivity. An idle earbud press now means "play what's waiting" (pumps both queues; lane gates still apply). Wake/guest/dictation prefs are no longer read (stored values sit harmless). KEPT BYTE-FOR-BYTE where it matters (the field-proven playback core): queue polling + v1.81 intake logging, Google-TTS synth + on-device TTS, MediaPlayer playback, segment groups + 2000 ms gap, the almost-done watch + PlaybackProgressTask reports (v1.79-v1.81 flag discipline, orphan guard, unconditional drains, poll backstop), priority interrupt/resume, the earbuds-only lane, delete flows, the updater, heartbeat + log upload, MessageHistory + history UI + replay.
# (2) DELETE-AFTER-FULL-PLAY (Nick, 2026-11-02 13:09): once a message has been played FULLY it is deleted locally — at the exact completion points that fire playback_row_played (onPlaybackDoneNow + onTtsDone, right after the report), cleanupFullyPlayedMessage removes the message's MessageHistory entry AND its cached audio file(s), including the replay-cached file the app used to keep ("playback: keeping replay-cached file" now survives only for LOCKED entries — a lock is Nick's explicit keep and exempts the entry). Matching is by queue row id; history replays complete with no live row id, so they match by played-file path — a replay played to completion deletes too. Partial plays, pauses, and stops never reach the cleanup. Log line: "cleanup: deleted fully-played message row id=...". Server rows/audio already delete at item completion (brain) — wire and reporting unchanged.
# (3) BUILD: sherpa-onnx is GONE from the build (its only consumers were the deleted wake/drone classes): no AAR, no kotlin-stdlib, no KWS model assets, no native libs staged. No bundled audio assets are staged either — the fallback/holding clips' only players were deleted with the dictation machinery (source assets remain in the repo). javac classpath is the platform alone. Everything else follows the v1.81 pipeline: same keystore/signing, targetSdk 34 via aapt2 link. STANDALONE ONLY.
# ---- v1.81 header (kept for the record) ----
# Manual APK build for Selena Voice v1.81 — SWALLOWED-ROW FIX: ORPHAN GUARD + UNCONDITIONAL WAITING-FIFO DRAIN + INTAKE LOGGING (Nick, 2026-11-02).
# FIELD BUG (2026-11-02, app log + server state): row msg-1793641641223-uo9po8 (HIGH priority, seg 1 of a 2-segment message) started playing at 12:47:28 EST, interrupting a segment at 12314ms. Three seconds later the interrupted message's NEXT segment — synthesized at 12:47:30 and enqueued — started via pumpAudioQueue, because on v1.79 the priority path never raised the `playing` flag the pump checks (the v1.80 root cause): playFileNow's releasePlayer() destroyed the priority play's MediaPlayer, and with it the ONLY completion listener that would ever report the row played or run the completion advance. No played report (server item sat at played 0 / released 1 forever, segment 2 never released), no resume (the interrupt stack held the SEGMENT's state, not the priority row's), nothing parked anywhere — the row was silently swallowed while every later flow (the segment chain's unwind, two later messages playing in full) ran clean past it.
# THE FIX (VoiceService.java only): (1) ORPHAN GUARD in playFileNow — the single choke point every playback start funnels through: before it releases the current player, if that player is a live priority play (new witness counter priorityInterruptDepth, incremented by playPriorityMp3 on a successful start and reset at exactly the points that clear priorityMode), the row is parked at the head of waitingPriority (file + text + row id + segment group, flagged wasOrphaned) with a log line naming it. (2) UNCONDITIONAL DRAIN — the completion advances in onPlaybackDoneNow / onTtsDone (and the nextGroup peeks) no longer gate the waiting-FIFO drain on priorityMode: a parked row can be waiting while the flag is already down (its superseder may be an ordinary queue item), and the flag-gated drain was the missing trigger. playNextWaitingPriority re-checks the polite/lane gates itself. (3) POLL BACKSTOP — every poll cycle, if the service is idle and anything waits in the FIFO, it drains; no accepted row can sit without a playback attempt. (4) INTAKE LOGGING — handleQueueMessage logs every accepted row's id + priority + segment group + disposition (priority-tts / priority-mp3-download / priority-mp3-synth / queue-tts / queue-mp3 / queue-mp3-synth / held-no-auto-readout) before dispatch, and playNextWaitingPriority logs the replay of an orphaned row by id. No priority/segment machinery redesigned, no wire changes. Everything else is v1.80 verbatim. STANDALONE ONLY.
# ---- v1.80 header (kept for the record) ----
# Manual APK build for Selena Voice v1.80 — ALMOST-DONE ON THE PRIORITY PATH + SEGMENT GAP 2000 (Nick's rules, 2026-11-02).
# (1) FIELD BUG (v1.79, 2026-11-02): a HIGH-priority row never reported playback_almost_done — its next segment arrived only via the server's completion backstop, leaving a ~7 s inter-segment gap, while a LOW-priority row on the normal queue path fired correctly. ROOT CAUSE: the almost-done watch's tick retires instantly when the `playing` flag is down, and only the queue pump (playNext) ever raises that flag — playPriorityMp3 never does (interruptCurrentPlayback lowers it; playFileNow never raises it), so the watch armed at playFileNow died at its first tick, 500 ms in, before ever looking at the position. FIX: playPriorityMp3 and resumeInterruptedMp3 now raise `playing` before playFileNow and clear it in the parked branch — playNext's exact discipline — and resumePlayback's MP3 branch restores it (a priority interrupt of a paused item had cleared it). armAlmostDoneWatch also logs ONE line per arming (row id + priority/queue path) so field logs prove arming on both paths. Wire, 12 s lead, once-per-row guards untouched.
# (2) SEGMENT GAP: Nick's 2026-11-02 ruling — the gap between segments of one message must be >=1.5 s and <=3 s end-to-start, as close to real-time as possible. SEGMENT_GAP_MS 3000 -> 2000 puts the end-to-start spacing mid-band including start latency. Nothing else in advanceAfterSegmentGap changed.
# Everything else is v1.79 verbatim. STANDALONE ONLY.
# ---- v1.79 header (kept for the record) ----
# Manual APK build for Selena Voice v1.79 — ALMOST-DONE PLAYBACK REPORT + NO APP-SIDE SEGMENTATION (Nick's rules, 2026-11-02).
# THE RULES (Nick, 2026-11-02): Selena (the sender) segments every long message herself into ~20-30 s pieces and feeds them just-in-time — the next segment must be SENT when the current one has ~10-15 s of playback left, and she can read his listening position from the reports. The app NEVER segments anything Selena sends: each selena_voice_queue row plays whole.
# (1) ALMOST-DONE REPORT: the app already reports playback_row_played / playback_stopped via PlaybackProgressTask to the brain endpoint; v1.79 adds action "playback_almost_done", fired ONCE per queue row the first time its remaining playback drops to <=12 s. MediaPlayer path: a 1 s position watch (armAlmostDoneWatch / almostDoneWatchTick) armed at playFileNow success and re-armed on MP3 resume — real getDuration()/getCurrentPosition(); idles while paused, dies on completion/stop (player released / tracker consumed by the played report), and retargets naturally across priority interrupts because it re-reads currentPlayServerId every tick. On-device TTS path: utterances have no real duration, so armAlmostDoneTts schedules a one-shot at (text length at ~14 chars/sec, the 0.95 speaking rate) minus 12 s, firing only if that row is still the speaking one; a row shorter than the lead fires immediately (correct — the next segment should come at once). Guards: a bounded FIFO (20) of fired row ids — never twice for one row; never for the "priority" marker or id-less plays; never after played/stopped (all watches run on the main handler, so no mid-sequence race). Advisory only: a lost almost-done just means the next segment arrives at completion instead of just before it.
# (2) NO APP-SIDE SEGMENTATION — AUDIT RESULT: synthesizeOnce already sends the whole row text in ONE Google TTS request; speakOnDevice speaks one row as one utterance; no sentence-splitting/chunking helper touches queue-row text on any playback path (the sentenceBuffer / SENTENCE_GAP_MS machinery is inbound dictation — untouched). No splitter existed to remove; the rule is now written as comments at playFileNow, synthesizeToCache, and speakOnDevice so it is never added later.
# playback_row_played / playback_stopped reporting is byte-for-byte v1.78. Dictation, the earbuds lane, and everything else untouched. STANDALONE ONLY.
# ---- v1.78 header (kept for the record) ----
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

OUT=build/v182
rm -rf $OUT
mkdir -p $OUT/gen $OUT/classes $OUT/dex

echo "== no native libs / bundled assets (v1.82: sherpa + dictation clips removed) =="

echo "== aapt2 compile =="
aapt2 compile --dir app/src/main/res -o $OUT/res.zip

echo "== aapt2 link (targetSdk=34) =="
aapt2 link -o $OUT/base.apk \
  -I $PLATFORM \
  --manifest app/src/main/AndroidManifest.xml \
  --min-sdk-version 26 \
  --target-sdk-version 34 \
  --version-code 84 \
  --version-name 1.82 \
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

echo "== d8 (app classes only) =="
d8 --min-api 26 --lib $PLATFORM \
  --output $OUT/dex \
  $(find $OUT/classes -name "*.class" | tr '\n' ' ')

echo "== package =="
cp $OUT/base.apk $OUT/unsigned.apk
(cd $OUT/dex && zip -q ../unsigned.apk *.dex)

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
  --out $OUT/selena-voice-v1.82.apk $OUT/aligned.apk

echo "== verify =="
apksigner verify --print-certs $OUT/selena-voice-v1.82.apk | head -3
aapt2 dump badging $OUT/selena-voice-v1.82.apk | grep -E "sdkVersion|targetSdkVersion|versionCode|application-label" | head -5
unzip -l $OUT/selena-voice-v1.82.apk | grep -E "classes.*\.dex"
echo "BUILD DONE: $OUT/selena-voice-v1.82.apk"
# Nick 2026-10-18: also stage copies at the deliverable locations
mkdir -p app/build
cp $OUT/selena-voice-v1.82.apk app/build/selena-voice-v1.82.apk
cp $OUT/selena-voice-v1.82.apk ~/workspace/your_files/selena-voice-v1.82.apk
echo "COPIED: app/build/selena-voice-v1.82.apk, ~/workspace/your_files/selena-voice-v1.82.apk"
