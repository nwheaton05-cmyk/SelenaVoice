package com.selena.voice;

import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
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
 * Segmentation comes from the recognizer's endpoint rules (the
 * drone/wake-proven set: 2.4 s of silence ends a no-speech
 * stream, 1.2 s of trailing silence ends an utterance, 20 s hard
 * cap per utterance).
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
 * MainActivity's two session door handlers, and the private
 * begin() below is reachable ONLY through beginSolo() /
 * beginWithOthers(). Outside a session, capture never runs:
 * Stop (or activity destroy) calls shutdown().
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

    private int nextSeq = 0;
    private int segIndex = 0;
    private List<String> prevPartialWords = new ArrayList<>();
    private int emittedWords = 0;
    private volatile int droppedFrames = 0;
    private String lastPartialText = "";
    private boolean segPlaybackOverlap = false;

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
        enqueueFrame(0L, "", false, meta);
        final String mode = meta.optString("mode", "?");
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
        // Graceful: frames already queued still post; the executor
        // thread is a daemon-less single thread but the process
        // outlives the session, and shutdown() lets it drain.
        poster.shutdown();
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
            // The drone/wake-proven endpoint rules (v1.62/v1.68):
            // 2.4 s silence ends a no-speech stream, 1.2 s trailing
            // silence ends an utterance, 20 s hard cap per utterance.
            config.setEndpointConfig(new EndpointConfig(
                    new EndpointRule(false, 2.4f, 0f),
                    new EndpointRule(true, 1.2f, 0f),
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
                        onFinalText(text);
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
