package com.selena.voice;

import android.content.Context;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import com.k2fsa.sherpa.onnx.EndpointConfig;
import com.k2fsa.sherpa.onnx.EndpointRule;
import com.k2fsa.sherpa.onnx.FeatureConfig;
import com.k2fsa.sherpa.onnx.OnlineModelConfig;
import com.k2fsa.sherpa.onnx.OnlineNeMoCtcModelConfig;
import com.k2fsa.sherpa.onnx.OnlineParaformerModelConfig;
import com.k2fsa.sherpa.onnx.OnlineRecognizer;
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig;
import com.k2fsa.sherpa.onnx.OnlineRecognizerResult;
import com.k2fsa.sherpa.onnx.OnlineStream;
import com.k2fsa.sherpa.onnx.OnlineToneCtcModelConfig;
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig;
import com.k2fsa.sherpa.onnx.OnlineZipformer2CtcModelConfig;

import org.json.JSONObject;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Coach-session word streamer.
 *
 * v1.93 (Nick, 2026-11-05) — OWN CAPTURE, OWN ASR, FULL DUPLEX.
 * Nick's two rulings after the v1.92 field test:
 *   1. "The fix for the beeping microphone is not turning the
 *      volume up and down that's just pure laziness." — v1.92's
 *      stream muting is DELETED. What he heard "going up and
 *      down" in the second v1.92 session was the mute/unmute
 *      pumping itself.
 *   2. "Selena voice won't play while the microphone is still
 *      going that's not going to work for live coaching — I need
 *      to be able to hear you and simultaneously you need to be
 *      able to hear us." — playback must run WHILE capture runs.
 *
 * So the platform SpeechRecognizer is GONE from this path — every
 * beep that ever existed came from it starting and stopping, and
 * its restart-per-segment cycle was also the v1.91 wedge. In its
 * place, the architecture this app already field-proved in drone
 * mode (v1.61/62) and zero-gap wake (v1.68), deleted in v1.82 and
 * revived here:
 *
 *   AudioRecord (opened ONCE for the whole session; a raw capture
 *   makes no sounds, ever — there is nothing to mute)
 *     → sherpa-onnx streaming Zipformer (en-20M int8; model
 *       managed by DroneModelStore: one-time 43.6 MB download
 *       into app-private storage, SHA-256 verified)
 *     → partial texts diffed into 1–2 word frames + segment
 *       finals, POSTed to /api/selena-voice/stream exactly as
 *       before. The wire format did not change; the server only
 *       stores frames (carry-only).
 *
 * Segmentation comes from the recognizer's endpoint rules:
 * 2.4 s of silence ends a no-speech stream, 20 s hard cap per
 * utterance (both the drone/wake-proven values), and — coach-only
 * since v1.98 — 0.8 s of trailing silence ends an utterance (the
 * drone/wake set's 1.2 s cost every coach exchange latency; see
 * the endpoint config below).
 *
 * MIC ROUTE (v1.99, Nick, 2026-11-05 ~15:05 EST — ordered live,
 * mid-call, when the table-mic ASR proved too garbled: "That's
 * the problem. Use the earbud mic"): at session start, capture
 * is routed to the connected Bluetooth headset mic —
 * AudioManager.setCommunicationDevice() on the first
 * TYPE_BLUETOOTH_SCO / TYPE_BLE_HEADSET communication device,
 * plus setPreferredDevice() on the AudioRecord — and the
 * communication device is cleared at session end. The SCO
 * channel is full-duplex, so playback during capture is
 * unaffected. If no Bluetooth communication device is connected
 * (or routing fails, or the device predates API 31), capture
 * falls back to the default input — exactly the pre-v1.99
 * behavior — and the fallback is logged. The routed device
 * rides the session_start frame meta as additive keys
 * (mic_route / mic_device / mic_type).
 *
 * DUPLEX: the capture registers its AudioRecord audio-session id
 * with VoiceService (registerOwnMicSession) BEFORE recording
 * starts, and the mic-hold filter ignores configurations with
 * exactly that id ("own-coach") — so our own session no longer
 * holds or pauses Selena's playback, while a genuinely foreign
 * app grabbing the mic still does (v1.83 semantics, unchanged).
 * Frames whose segment overlapped her playback carry
 * meta {playback_overlap: true} on the final frame, so the coach
 * reading the stream can tell her own voice apart from the room.
 *
 * THE GATE IS STILL THE FEATURE (Nick's mic ruling, 2026-11-05
 * 00:17 EST, reversing his v1.82 no-microphone order for coach
 * sessions only): this class is instantiated ONLY by
 * VoiceService's coach-session control, which starts it ONLY on
 * the two door intents sent from MainActivity's door handlers,
 * and the private begin() below is reachable ONLY through
 * beginSolo() / beginWithOthers(). Outside a session, capture
 * never runs: the Stop intent (or service destroy) calls
 * shutdown(). (v1.95 moved ownership from the activity to the
 * service so a session survives Nick leaving the app — through
 * v1.94 the activity's onDestroy killed live sessions.)
 *
 * TWO DOORS (Nick, 2026-11-05 00:19 + 00:23 EST): the disclosure
 * duty is owed to OTHER people in the conversation, not to Nick.
 * - Solo ("Just me" — rehearsal/emulation): no disclosure step;
 *   beginSolo() starts capture immediately.
 * - With others: the disclosure is said BEFORE recording starts
 *   (recording first would violate CT all-party consent);
 *   beginWithOthers() is called ONLY from the disclosure step's
 *   confirmation handler, after the disclosure has been said.
 *   There is no path around that confirmation in this mode.
 *
 * PROOF IN THE RECORD: the first frame of every session is a
 * control frame (seq 0, seg_id "__session__") whose meta carries
 * {type: "session_start", mode: "solo"} or {type: "session_start",
 * mode: "with_others", disclosure_confirmed_at: <ISO tap time>}.
 * It is enqueued before capture starts, so no word frame can
 * precede it in the record.
 *
 * Threading: one capture thread owns the AudioRecord, the
 * recognizer and all segment state; frame POSTs run on a single
 * background executor so seq order is preserved and capture is
 * never blocked by the network. A failed POST is retried once,
 * then dropped and counted — a drop is logged, never silently
 * absorbed, and never stalls recognition. Listener callbacks are
 * delivered on the main thread.
 *
 * v1.96 (Nick, 2026-11-05) — PHASE 0 VOICE TEST: automatic,
 * continuous voice learning + numbered speaker labels, in
 * measurement/logging form only. There is NO enrollment ceremony
 * (Nick, 13:23 EST: "all enrollment is automatic — it learns as
 * it goes"): in every coach session (v1.97, Nick 13:52 EST:
 * auto-learn is ALWAYS ON — the v1.96 in-session toggle is
 * gone), every quality-accepted
 * segment is embedded on-device (WeSpeaker ResNet34-LM via
 * SpeakerEmbedder) and processed against the ONE app-global
 * VoiceprintStore —
 *   solo: the speaker is Nick by construction; the segment's
 *     self-score is logged and it teaches Voice 1 (slowly).
 *   with-others: the segment is matched against every stored
 *     voice; Voice 1 matches are Nick (his print refines only on
 *     high-confidence matches); a segment matching nothing founds
 *     the next guest voice (Voice 2, 3, … in first-heard order).
 * No confirmation, no prompt, no asking who anyone is — ever, in
 * either mode. Learning is slow (VoiceprintStore.TEACH_ALPHA)
 * and gated: segments that overlapped Selena's playback, are too
 * quiet, or are too short NEVER teach. The frame stream is
 * untouched — labels and scores go to the app log only, nothing
 * is gated, no speaker tag is sent upstream.
 */
public class CoachStreamer {

    /** UI-facing callbacks, always delivered on the main thread. */
    public interface Listener {
        void onStreamStatus(String msg);
        void onStreamEnded(String reason);
    }

    private static final int SAMPLE_RATE = 16000;
    private static final int CHUNK = 1600; // 100 ms decode steps

    private final Context appContext;
    private final Listener listener;
    private final String sessionId;
    private final long sessionStartUptimeMs;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService poster = Executors.newSingleThreadExecutor();

    private volatile boolean live = false;
    private volatile boolean ended = false;

    // Capture-thread-owned state.
    private Thread captureThread;
    private AudioRecord record;
    private OnlineRecognizer recognizer;
    private int ownAudioSessionId = -1;

    // Earbud-mic routing (v1.99, Nick, 2026-11-05 — ordered live,
    // mid-call: "That's the problem. Use the earbud mic"). Set in
    // begin() before the session_start frame is enqueued, so the
    // routed device can ride the seq-0 meta. routedMicDevice is
    // null when no Bluetooth communication device is connected —
    // capture then takes the default input, exactly the pre-v1.99
    // behavior. commDeviceSet says WE hold the AudioManager
    // communication-device slot and must clear it at session end.
    private AudioDeviceInfo routedMicDevice;
    private boolean commDeviceSet = false;

    private int nextSeq = 0;
    private int segIndex = 0;
    private List<String> prevPartialWords = new ArrayList<>();
    private int emittedWords = 0;
    private volatile int droppedFrames = 0;
    private String lastPartialText = "";
    private boolean segPlaybackOverlap = false;

    // Voice learning (v1.96, Phase 0; always on since v1.97) —
    // see the class doc. The PCM tap is written by the capture
    // thread and snapshotted at each endpoint; all
    // embedding/matching/teaching runs on vpExec so capture and
    // recognition never wait on it.
    private String sessionMode = "";
    private volatile boolean autoLearnOn = false;
    private SpeakerEmbedder embedder;
    private final ExecutorService vpExec = Executors.newSingleThreadExecutor();
    private float[] segTap = new float[SAMPLE_RATE * 4];
    private int segTapLen = 0;
    private boolean vpWarnedNoNick = false;

    // PROVISIONAL Phase 0 thresholds — the table test calibrates
    // the real pair from the logged score distributions. 0.50 is
    // the sherpa demo match default the reference plugin shipped.
    private static final float VP_MATCH_THRESHOLD = 0.50f;
    private static final float VP_TEACH_NICK_MIN = 0.65f;
    private static final float VP_TEACH_GUEST_MIN = 0.60f;
    private static final float VP_ANCHOR_MIN = 0.70f;
    private static final double VP_SCORE_MIN_DUR_S = 0.8;
    private static final double VP_TEACH_MIN_DUR_S = 2.0;
    private static final double VP_CREATE_MIN_DUR_S = 3.0;
    private static final double VP_RMS_FLOOR = 0.01; // -40 dB

    public CoachStreamer(Context context, String sessionId, Listener listener) {
        this.appContext = context.getApplicationContext();
        this.sessionId = sessionId;
        this.listener = listener;
        this.sessionStartUptimeMs = SystemClock.uptimeMillis();
    }

    public String sessionId() {
        return sessionId;
    }

    /**
     * Solo door ("Just me"): no disclosure is owed to anyone, so
     * capture starts immediately. Called ONLY from MainActivity's
     * "Just me" handler.
     */
    public void beginSolo() {
        try {
            JSONObject meta = new JSONObject();
            meta.put("type", "session_start");
            meta.put("mode", "solo");
            begin(meta);
        } catch (Exception e) {
            VoiceService.log("stream: session_start frame build failed: " + e);
        }
    }

    /**
     * With-others door: called ONLY from MainActivity's disclosure
     * confirmation handler — i.e., only after the disclosure has
     * been said out loud and confirmed on screen. The confirmation
     * timestamp rides the control frame as the compliance proof.
     */
    public void beginWithOthers(String disclosureConfirmedIso) {
        try {
            JSONObject meta = new JSONObject();
            meta.put("type", "session_start");
            meta.put("mode", "with_others");
            meta.put("disclosure_confirmed_at", disclosureConfirmedIso);
            begin(meta);
        } catch (Exception e) {
            VoiceService.log("stream: session_start frame build failed: " + e);
        }
    }

    /**
     * The single start path both doors funnel into: enqueue the
     * session_start control frame FIRST, then prepare the speech
     * engine and open the capture on a worker thread. Private —
     * reachable only via the two doors above.
     */
    private void begin(JSONObject meta) {
        if (live || ended) return;
        live = true;
        // v1.99: route capture to the earbud (Bluetooth headset)
        // mic BEFORE the session_start frame is enqueued, so the
        // routed device rides the seq-0 meta as additive keys
        // (same wire-format precedent as disclosure_confirmed_at).
        // Never throws and never blocks the session: on any
        // failure it leaves the default input in place.
        routedMicDevice = routeCaptureToEarbud();
        try {
            if (routedMicDevice != null) {
                meta.put("mic_route", "bluetooth");
                meta.put("mic_device", String.valueOf(
                        routedMicDevice.getProductName()));
                meta.put("mic_type", routedMicDevice.getType());
            } else {
                meta.put("mic_route", "default");
            }
        } catch (Exception ignored) {}
        enqueueFrame(0L, "", false, meta);
        final String mode = meta.optString("mode", "?");
        sessionMode = mode;
        enableAutoLearn();
        if (!DroneModelStore.isReady(appContext)) {
            postStatus("Downloading speech engine (one-time, 44 MB)…");
        } else {
            postStatus("Starting microphone…");
        }
        captureThread = new Thread(() -> runSession(mode), "SelenaCoachCapture");
        captureThread.start();
    }

    /** Stop capture and tear everything down. Idempotent. The
     *  capture thread releases its own resources on exit. */
    public void shutdown() {
        if (ended) return;
        ended = true;
        live = false;
        AudioRecord r = record;
        if (r != null) {
            try { r.stop(); } catch (Exception ignored) {}
        }
        Thread t = captureThread;
        if (t != null) t.interrupt();
        if (ownAudioSessionId >= 0) {
            VoiceService.unregisterOwnMicSession(ownAudioSessionId);
            ownAudioSessionId = -1;
        }
        clearEarbudRouting();
        // Graceful: frames already queued still post; the executor
        // thread is a daemon-less single thread but the process
        // outlives the session, and shutdown() lets it drain.
        poster.shutdown();
        autoLearnOn = false;
        vpExec.shutdown();
        if (embedder != null) {
            embedder.release();
            embedder = null;
        }
        VoiceService.log("stream: session " + sessionId + " ended"
                + " (droppedFrames=" + droppedFrames + ")");
    }

    // ------------------------------------------------------------ capture + ASR

    /** Worker thread: model → recognizer → record → decode loop. */
    private void runSession(String mode) {
        try {
            if (!DroneModelStore.isReady(appContext)) {
                DroneModelStore.ensureAsync(appContext, () -> {});
                // ensureAsync runs its own thread; wait for readiness
                // here with a bounded poll (this thread is ours).
                long deadline = System.currentTimeMillis() + 180_000;
                while (!DroneModelStore.isReady(appContext)
                        && System.currentTimeMillis() < deadline) {
                    if (!live) return;
                    try { Thread.sleep(500); } catch (InterruptedException ie) {
                        return;
                    }
                }
                if (!DroneModelStore.isReady(appContext)) {
                    failSession("Speech engine download didn't finish — "
                            + "check the connection and start again.");
                    return;
                }
            }
            if (!live) return;
            recognizer = buildRecognizer();
            if (recognizer == null) {
                failSession("Speech engine failed to start — session stopped.");
                return;
            }
            if (!openRecord()) {
                failSession("Microphone couldn't be opened — session stopped.");
                return;
            }
            VoiceService.log("stream: session " + sessionId + " live (mode "
                    + mode + ") — own capture, sherpa ASR, duplex playback");
            postStatus("Session live — streaming");
            decodeLoop();
        } catch (Throwable t) {
            VoiceService.log("stream: session failed: " + t);
            if (live) {
                failSession("Streaming hit a problem — session stopped.");
            }
        } finally {
            releaseCapture();
        }
    }

    private OnlineRecognizer buildRecognizer() {
        try {
            String dir = DroneModelStore.dir(appContext).getAbsolutePath();
            OnlineTransducerModelConfig transducer =
                    new OnlineTransducerModelConfig(
                            dir + "/" + DroneModelStore.ENCODER,
                            dir + "/" + DroneModelStore.DECODER,
                            dir + "/" + DroneModelStore.JOINER);
            OnlineModelConfig model = new OnlineModelConfig(
                    transducer,
                    new OnlineParaformerModelConfig(),
                    new OnlineZipformer2CtcModelConfig(),
                    new OnlineNeMoCtcModelConfig(),
                    new OnlineToneCtcModelConfig(),
                    dir + "/" + DroneModelStore.TOKENS,
                    2, false, "cpu", "", "", "");
            OnlineRecognizerConfig config = new OnlineRecognizerConfig();
            config.setFeatConfig(new FeatureConfig(SAMPLE_RATE, 80, 0.0f));
            config.setModelConfig(model);
            config.setDecodingMethod("greedy_search");
            config.setMaxActivePaths(4);
            config.setEnableEndpoint(true);
            // Endpoint rules: 2.4 s silence ends a no-speech stream
            // and the 20 s hard cap are the drone/wake-proven values
            // (v1.62/v1.68). The trailing-silence rule is COACH-ONLY
            // from v1.98 (Nick, 2026-11-05): 0.8 s instead of the
            // drone/wake 1.2 s — every coach exchange paid the full
            // trailing wait before its final posted, and the seat's
            // continuous-correction + commit-at-natural-pause
            // machinery already absorbs Nick's thinking-pause chops.
            // This config exists only in CoachStreamer (the coach
            // path); drone/wake endpointing lives elsewhere and is
            // untouched.
            config.setEndpointConfig(new EndpointConfig(
                    new EndpointRule(false, 2.4f, 0f),
                    new EndpointRule(true, 0.8f, 0f),
                    new EndpointRule(false, 0f, 20f)));
            try {
                return new OnlineRecognizer(null, config);
            } catch (Throwable t1) {
                VoiceService.log("stream: recognizer init (null assets) "
                        + "failed, retrying with AssetManager: " + t1);
                return new OnlineRecognizer(appContext.getAssets(), config);
            }
        } catch (Throwable t) {
            VoiceService.log("stream: recognizer init failed: " + t);
            return null;
        }
    }

    // ------------------------------------------------ earbud mic (v1.99)

    private static String deviceLabel(AudioDeviceInfo d) {
        return "'" + d.getProductName() + "' (type=" + d.getType() + ")";
    }

    /**
     * Route this session's capture to the connected Bluetooth
     * headset (earbud) mic via the AudioManager communication
     * device slot (API 31+). Returns the chosen device, or null
     * to keep the default input. NEVER throws: mic routing must
     * not be able to fail a coach session (Nick's order is "use
     * the earbud mic", not "no earbuds, no session").
     */
    private AudioDeviceInfo routeCaptureToEarbud() {
        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                VoiceService.log("stream: earbud mic routing needs "
                        + "API 31+ (this device is " + Build.VERSION.SDK_INT
                        + ") — capture on default input (fallback)");
                return null;
            }
            AudioManager am = (AudioManager) appContext
                    .getSystemService(Context.AUDIO_SERVICE);
            if (am == null) {
                VoiceService.log("stream: no AudioManager — capture "
                        + "on default input (fallback)");
                return null;
            }
            for (AudioDeviceInfo d : am.getAvailableCommunicationDevices()) {
                int t = d.getType();
                if (t == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
                        || t == AudioDeviceInfo.TYPE_BLE_HEADSET) {
                    if (am.setCommunicationDevice(d)) {
                        commDeviceSet = true;
                        VoiceService.log("stream: capture routed to "
                                + "earbud mic " + deviceLabel(d));
                        return d;
                    }
                    VoiceService.log("stream: setCommunicationDevice "
                            + "refused " + deviceLabel(d) + " — trying "
                            + "next device");
                }
            }
            VoiceService.log("stream: no Bluetooth communication "
                    + "device connected — capture on default input "
                    + "(fallback)");
            return null;
        } catch (Throwable t) {
            VoiceService.log("stream: earbud mic routing failed (" + t
                    + ") — capture on default input (fallback)");
            return null;
        }
    }

    /** Release the communication-device slot if we hold it.
     *  Idempotent; called from both teardown paths. */
    private void clearEarbudRouting() {
        if (!commDeviceSet) return;
        commDeviceSet = false;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                AudioManager am = (AudioManager) appContext
                        .getSystemService(Context.AUDIO_SERVICE);
                if (am != null) am.clearCommunicationDevice();
            }
            VoiceService.log("stream: earbud mic routing cleared");
        } catch (Throwable t) {
            VoiceService.log("stream: clearCommunicationDevice "
                    + "failed: " + t);
        }
    }

    /** Drone-proven open sequence (v1.61): VOICE_RECOGNITION
     *  first, plain MIC as fallback. The audio-session id is
     *  registered with VoiceService BEFORE recording starts, so
     *  the mic-hold filter never sees our own capture as foreign
     *  — not even for one callback. */
    private boolean openRecord() {
        int min = AudioRecord.getMinBufferSize(SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT);
        int bufBytes = Math.max(min, CHUNK * 2 * 8);
        int[] sources = {
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                MediaRecorder.AudioSource.MIC,
        };
        for (int source : sources) {
            try {
                AudioRecord r = new AudioRecord(source, SAMPLE_RATE,
                        AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT, bufBytes);
                if (r.getState() == AudioRecord.STATE_INITIALIZED) {
                    // v1.99: pin this record to the earbud mic chosen
                    // in begin() (belt to setCommunicationDevice's
                    // suspenders). Failure is logged, never fatal.
                    if (routedMicDevice != null) {
                        try {
                            boolean pinned =
                                    r.setPreferredDevice(routedMicDevice);
                            VoiceService.log("stream: capture "
                                    + "preferred device "
                                    + deviceLabel(routedMicDevice)
                                    + " -> " + pinned);
                        } catch (Throwable t) {
                            VoiceService.log("stream: setPreferredDevice "
                                    + "failed (" + t + ") — comm-device "
                                    + "routing still applies");
                        }
                    }
                    ownAudioSessionId = r.getAudioSessionId();
                    VoiceService.registerOwnMicSession(ownAudioSessionId);
                    r.startRecording();
                    record = r;
                    VoiceService.log("stream: capture open (source=" + source
                            + ", audioSessionId=" + ownAudioSessionId + ")");
                    return true;
                }
                r.release();
            } catch (Exception e) {
                VoiceService.log("stream: AudioRecord source " + source
                        + " failed: " + e);
            }
        }
        return false;
    }

    private void decodeLoop() {
        OnlineStream stream = recognizer.createStream("");
        short[] buf = new short[CHUNK];
        int readErrors = 0;
        try {
            while (live) {
                int n = record.read(buf, 0, buf.length);
                if (!live) break;
                if (n <= 0) {
                    if (++readErrors > 30) {
                        VoiceService.log("stream: capture read errors — "
                                + "ending session");
                        failSession("Microphone read failed — session stopped.");
                        return;
                    }
                    try { Thread.sleep(20); } catch (InterruptedException ie) {
                        return;
                    }
                    continue;
                }
                readErrors = 0;
                if (VoiceService.isPlayingAudioNow()) {
                    segPlaybackOverlap = true;
                }
                float[] f = new float[n];
                for (int i = 0; i < n; i++) f[i] = buf[i] / 32768.0f;
                if (autoLearnOn) tapSegment(f, n);
                stream.acceptWaveform(f, SAMPLE_RATE);
                while (recognizer.isReady(stream)) {
                    recognizer.decode(stream);
                }
                OnlineRecognizerResult res = recognizer.getResult(stream);
                String text = res != null && res.getText() != null
                        ? res.getText().trim() : "";
                if (!text.equals(lastPartialText)) {
                    lastPartialText = text;
                    if (!text.isEmpty()) onPartialText(text);
                }
                if (recognizer.isEndpoint(stream)) {
                    if (!text.isEmpty()) {
                        // Snapshot BEFORE onFinalText resets the
                        // segment state (tap included).
                        String endedSeg = segId();
                        float[] snap = autoLearnOn ? snapshotSegment() : null;
                        boolean overlap = segPlaybackOverlap
                                || VoiceService.isPlayingAudioNow();
                        onFinalText(text);
                        if (snap != null) {
                            dispatchVoiceSegment(endedSeg, snap, overlap);
                        }
                    } else {
                        resetSegmentState();
                    }
                    recognizer.reset(stream);
                    lastPartialText = "";
                }
            }
        } finally {
            try { stream.release(); } catch (Exception ignored) {}
        }
    }

    private void releaseCapture() {
        AudioRecord r = record;
        record = null;
        if (r != null) {
            try { r.stop(); } catch (Exception ignored) {}
            try { r.release(); } catch (Exception ignored) {}
        }
        if (ownAudioSessionId >= 0) {
            VoiceService.unregisterOwnMicSession(ownAudioSessionId);
            ownAudioSessionId = -1;
        }
        clearEarbudRouting();
        if (recognizer != null) {
            try { recognizer.release(); } catch (Exception ignored) {}
            recognizer = null;
        }
    }

    private void failSession(String reason) {
        if (ended) return;
        live = false;
        mainHandler.post(() -> listener.onStreamEnded(reason));
        VoiceService.log("stream: session " + sessionId + " failed: " + reason);
        shutdown();
    }

    private void postStatus(String msg) {
        mainHandler.post(() -> listener.onStreamStatus(msg));
    }

    private long nowMs() {
        return SystemClock.uptimeMillis() - sessionStartUptimeMs;
    }

    private String segId() {
        return "seg-" + segIndex;
    }

    private void resetSegmentState() {
        prevPartialWords = new ArrayList<>();
        emittedWords = 0;
        segPlaybackOverlap = false;
        segTapLen = 0;
    }

    // ------------------------------------------------- voice learning (v1.96+)

    /** The session's door ("solo" / "with_others"), for the UI mirror. */
    public String mode() {
        return sessionMode;
    }

    /**
     * v1.97 (Nick, 2026-11-05 13:52 EST): auto-learn is ALWAYS ON
     * inside coach sessions — the v1.96 voice-test toggle is
     * gone. Called once from begin(), before capture starts.
     * Affects ONLY the voice machinery (learning + log lines) —
     * the frame stream, the coaching behavior, and the disclosure
     * gate are untouched. If the speaker model were ever missing
     * from a build, the session still runs; learning just stays
     * off and the log says so.
     */
    private void enableAutoLearn() {
        if (!SpeakerEmbedder.modelAvailable(appContext)) {
            VoiceService.log("vp: auto-learn unavailable — speaker"
                    + " model missing in this build (mode "
                    + sessionMode + ", session " + sessionId + ")");
            postStatus("Voice learning unavailable — speaker model"
                    + " missing in this build.");
            return;
        }
        autoLearnOn = true;
        VoiceService.log("vp: auto-learn ON (always on, mode "
                + sessionMode + ", session " + sessionId + ")");
    }

    private void tapSegment(float[] f, int n) {
        if (segTapLen + n > segTap.length) {
            float[] bigger = new float[Math.max(segTap.length * 2,
                    segTapLen + n)];
            System.arraycopy(segTap, 0, bigger, 0, segTapLen);
            segTap = bigger;
        }
        System.arraycopy(f, 0, segTap, segTapLen, n);
        segTapLen += n;
    }

    private float[] snapshotSegment() {
        float[] out = new float[segTapLen];
        System.arraycopy(segTap, 0, out, 0, segTapLen);
        return out;
    }

    private void dispatchVoiceSegment(final String seg, final float[] pcm,
                                      final boolean overlap) {
        vpExec.execute(() -> processVoiceSegment(seg, pcm, overlap));
    }

    /**
     * Runs on vpExec. The acceptance gates come FIRST and are the
     * learning safeguards: a segment that overlapped Selena's own
     * playback, is too quiet to be speech, or is too short to
     * score reliably is logged and dropped — it never teaches and
     * never founds a voice.
     */
    private void processVoiceSegment(String seg, float[] pcm,
                                     boolean overlap) {
        try {
            double durS = pcm.length / (double) SAMPLE_RATE;
            if (overlap) {
                VoiceService.log("vp " + seg + " dur=" + fmt1(durS)
                        + "s skipped — overlapped Selena's playback"
                        + " (never learned)");
                return;
            }
            double rms = rms(pcm);
            if (rms < VP_RMS_FLOOR) {
                VoiceService.log("vp " + seg + " dur=" + fmt1(durS)
                        + "s skipped — too quiet (rms " + fmt3(rms) + ")");
                return;
            }
            if (durS < VP_SCORE_MIN_DUR_S) {
                VoiceService.log("vp " + seg + " dur=" + fmt1(durS)
                        + "s skipped — too short to score");
                return;
            }
            if (embedder == null) embedder = new SpeakerEmbedder(appContext);
            float[] vec = embedder.embed(pcm);
            long ms = embedder.lastComputeMs();
            if (vec == null) {
                VoiceService.log("vp " + seg + " embed failed");
                return;
            }
            boolean teachable = durS >= VP_TEACH_MIN_DUR_S;
            if ("solo".equals(sessionMode)) {
                processSoloSegment(seg, vec, durS, ms, teachable);
            } else {
                processOthersSegment(seg, vec, durS, ms, teachable);
            }
        } catch (Throwable t) {
            VoiceService.log("vp " + seg + " processing failed: " + t);
        }
    }

    /**
     * Solo: the speaker is Nick by construction — Voice 1 is the
     * only enrollment subject in solo, and solo never creates
     * guests. His self-score (against the print as it stood BEFORE
     * this segment) is the measurement; then the segment teaches,
     * slowly. Short segments are scored and logged but don't teach.
     */
    private void processSoloSegment(String seg, float[] vec, double durS,
                                    long ms, boolean teachable) {
        String self = "n/a";
        if (VoiceprintStore.hasPrint(appContext, 1)) {
            self = fmt2(VoiceprintStore.scoreAgainst(appContext, 1, vec));
        }
        String taughtNote = " (too short to teach)";
        if (teachable) {
            boolean bank = VoiceprintStore.anchors(appContext, 1).size()
                    < VoiceprintStore.ANCHOR_CAP;
            VoiceprintStore.teach(appContext, 1, vec, bank);
            VoiceprintStore.noteHeard(appContext, 1);
            taughtNote = " taught v1 (n="
                    + VoiceprintStore.taughtCount(appContext, 1) + ")";
        }
        VoiceService.log("vp " + seg + " mode=solo dur=" + fmt1(durS)
                + "s emb=" + ms + "ms self=" + self + taughtNote
                + " -> Voice 1" + provSuffix(1));
    }

    /**
     * With-others: match against every stored voice. Voice 1 is
     * Nick — his print refines ONLY on high-confidence matches, so
     * a borderline guest segment can't drag it. A segment matching
     * nothing founds the next guest voice when it is long enough
     * to carry a reliable embedding; a shorter stranger segment is
     * logged as unlabeled and teaches nothing.
     */
    private void processOthersSegment(String seg, float[] vec, double durS,
                                      long ms, boolean teachable) {
        if (!vpWarnedNoNick && !VoiceprintStore.hasPrint(appContext, 1)) {
            vpWarnedNoNick = true;
            VoiceService.log("vp: note — Voice 1 has no print yet; "
                    + "seed it in a SOLO session (auto-learn is "
                    + "always on). Until then every voice here "
                    + "founds a guest.");
        }
        int count = VoiceprintStore.voiceCount(appContext);
        StringBuilder scores = new StringBuilder();
        int bestVoice = -1;
        float best = -1f;
        for (int v = 1; v <= count; v++) {
            if (!VoiceprintStore.hasPrint(appContext, v)) continue;
            float s = VoiceprintStore.scoreAgainst(appContext, v, vec);
            scores.append(" v").append(v).append("=").append(fmt2(s));
            if (s > best) {
                best = s;
                bestVoice = v;
            }
        }
        int attr = 0;
        boolean taught = false;
        String verdict;
        if (bestVoice == 1 && best >= VP_MATCH_THRESHOLD) {
            attr = 1;
            verdict = "Voice 1";
            VoiceprintStore.noteHeard(appContext, 1);
            if (teachable && best >= VP_TEACH_NICK_MIN) {
                VoiceprintStore.teach(appContext, 1, vec,
                        best >= VP_ANCHOR_MIN
                                && VoiceprintStore.anchors(appContext, 1).size()
                                        < VoiceprintStore.ANCHOR_CAP);
                taught = true;
            }
        } else if (bestVoice > 1 && best >= VP_MATCH_THRESHOLD) {
            attr = bestVoice;
            verdict = "Voice " + bestVoice;
            VoiceprintStore.noteHeard(appContext, bestVoice);
            if (teachable && best >= VP_TEACH_GUEST_MIN) {
                VoiceprintStore.teach(appContext, bestVoice, vec,
                        best >= VP_ANCHOR_MIN
                                && VoiceprintStore.anchors(appContext, bestVoice).size()
                                        < VoiceprintStore.ANCHOR_CAP);
                taught = true;
            }
        } else if (teachable && durS >= VP_CREATE_MIN_DUR_S) {
            attr = VoiceprintStore.createGuest(appContext, vec);
            verdict = "NEW Voice " + attr;
            taught = true;
            postStatus("New voice heard — logged as Voice " + attr
                    + ".");
        } else {
            verdict = "unlabeled";
        }
        VoiceService.log("vp " + seg + " mode=with_others dur=" + fmt1(durS)
                + "s emb=" + ms + "ms scores:"
                + (scores.length() == 0 ? " (none stored)"
                        : scores.toString())
                + " -> " + verdict + (taught ? " (taught)" : "")
                + (attr > 0 ? provSuffix(attr) : ""));
    }

    private String provSuffix(int voice) {
        return VoiceprintStore.isProvisional(appContext, voice)
                ? " (provisional)" : "";
    }

    private static double rms(float[] pcm) {
        if (pcm.length == 0) return 0;
        double sumSq = 0;
        for (float f : pcm) sumSq += (double) f * f;
        return Math.sqrt(sumSq / pcm.length);
    }

    private static String fmt1(double v) {
        return String.format(java.util.Locale.US, "%.1f", v);
    }

    private static String fmt2(float v) {
        return String.format(java.util.Locale.US, "%.2f", v);
    }

    private static String fmt3(double v) {
        return String.format(java.util.Locale.US, "%.3f", v);
    }

    // ------------------------------------------------------------ frame emission

    /**
     * Delta computation from partials: diff the new partial's word
     * list against the previous partial by longest common prefix.
     * Words beyond the prefix are new; if the recognizer revised
     * an already-emitted word (prefix shrank below emittedWords),
     * the corrected tail is re-emitted as fresh frames — the
     * segment's final frame remains the canonical record. Emitted
     * words go out in chunks of at most 2, one frame per chunk,
     * so the wire grain is the specced 1–2 words.
     */
    private void onPartialText(String text) {
        List<String> words = splitWords(text);
        int common = 0;
        while (common < prevPartialWords.size() && common < words.size()
                && prevPartialWords.get(common).equals(words.get(common))) {
            common++;
        }
        int from = (common < emittedWords) ? common : emittedWords;
        for (int i = from; i < words.size(); i += 2) {
            int end = Math.min(i + 2, words.size());
            StringBuilder chunk = new StringBuilder();
            for (int j = i; j < end; j++) {
                if (j > i) chunk.append(' ');
                chunk.append(words.get(j));
            }
            enqueueFrame(nowMs(), chunk.toString(), false, null);
        }
        emittedWords = words.size();
        prevPartialWords = words;
    }

    private void onFinalText(String text) {
        String settled = text == null ? "" : text.trim();
        if (!settled.isEmpty()) {
            JSONObject meta = null;
            if (segPlaybackOverlap || VoiceService.isPlayingAudioNow()) {
                try {
                    meta = new JSONObject();
                    meta.put("playback_overlap", true);
                } catch (Exception ignored) {}
            }
            enqueueFrame(nowMs(), settled, true, meta);
        }
        segIndex++;
        resetSegmentState();
    }

    private void enqueueFrame(long tAudioMs, String words, boolean isFinal,
                              JSONObject meta) {
        final int seq = nextSeq++;
        final String seg = (seq == 0) ? "__session__" : segId();
        final JSONObject payload = new JSONObject();
        try {
            payload.put("session_id", sessionId);
            payload.put("seq", seq);
            payload.put("seg_id", seg);
            payload.put("t_audio_ms", tAudioMs);
            payload.put("words", words);
            payload.put("final", isFinal);
            if (meta != null) payload.put("meta", meta);
        } catch (Exception e) {
            VoiceService.log("stream: frame build failed: " + e);
            return;
        }
        poster.execute(() -> postFrame(seq, payload));
    }

    /** Runs on the poster thread. One retry, then drop + count. */
    private void postFrame(int seq, JSONObject payload) {
        for (int attempt = 1; attempt <= 2; attempt++) {
            HttpURLConnection conn = null;
            try {
                URL url = new URL(VoiceService.BASE_URL + "/api/selena-voice/stream");
                conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setConnectTimeout(6000);
                conn.setReadTimeout(6000);
                conn.setDoOutput(true);
                OutputStream os = conn.getOutputStream();
                os.write(payload.toString().getBytes("UTF-8"));
                os.close();
                int code = conn.getResponseCode();
                if (code == 200) return;
                VoiceService.log("stream: POST frame seq=" + seq
                        + " HTTP " + code + " (attempt " + attempt + ")");
            } catch (Exception e) {
                VoiceService.log("stream: POST frame seq=" + seq
                        + " failed (attempt " + attempt + "): " + e);
            } finally {
                if (conn != null) conn.disconnect();
            }
            if (attempt == 1) {
                try { Thread.sleep(400); } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
        droppedFrames++;
        VoiceService.log("stream: frame seq=" + seq + " DROPPED after retry"
                + " (droppedFrames=" + droppedFrames + ")");
    }

    private static List<String> splitWords(String text) {
        List<String> out = new ArrayList<>();
        if (text == null) return out;
        String trimmed = text.trim();
        if (trimmed.isEmpty()) return out;
        for (String w : trimmed.split("\\s+")) out.add(w);
        return out;
    }
}
