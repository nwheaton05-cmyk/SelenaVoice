package com.selena.voice;

import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;

import org.json.JSONObject;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Coach-session word streamer (v1.91, Nick 2026-11-05 — Slice A of
 * the streaming addition; spec streaming-spec-2026-11-05.md §3/§8).
 * v1.92 (Nick, 2026-11-05): beep suppression restored (see the beep
 * section below — the v1.48 hard-mute died with the mic in v1.82)
 * + recognizer destroy/recreate on restart (v1.91 session wedged
 * after 3 segments in its first live test).
 *
 * While a coach session is live, this runs continuous on-device
 * speech recognition and POSTs word frames to
 * /api/selena-voice/stream: 1–2 word deltas computed from the
 * recognizer's partial results, plus a final=true frame carrying
 * each segment's settled text. The server only stores frames
 * (carry-only); nothing here coaches, matches, or decides.
 *
 * THE GATE IS THE FEATURE (Nick's mic ruling, 2026-11-05 00:17 EST,
 * reversing his v1.82 no-microphone order for coach sessions only):
 * this class is instantiated ONLY by MainActivity's two session
 * door handlers, and the private begin() below is reachable ONLY
 * through beginSolo() / beginWithOthers(). Outside a session,
 * capture never runs: Stop (or activity destroy) calls shutdown(),
 * which destroys the recognizer.
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
 * It is enqueued before the recognizer starts, so no word frame
 * can precede it in the record.
 *
 * Threading: the recognizer lives on the main thread (created and
 * driven there, as SpeechRecognizer requires); frame POSTs run on
 * a single background executor so seq order is preserved and the
 * recognizer is never blocked by the network. A failed POST is
 * retried once, then dropped and counted — a drop is logged, never
 * silently absorbed, and never stalls recognition.
 */
public class CoachStreamer implements RecognitionListener {

    /** UI-facing callbacks, always delivered on the main thread. */
    public interface Listener {
        void onStreamStatus(String msg);
        void onStreamEnded(String reason);
    }

    private final Context appContext;
    private final Listener listener;
    private final String sessionId;
    private final long sessionStartUptimeMs;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService poster = Executors.newSingleThreadExecutor();

    private SpeechRecognizer recognizer;
    private boolean live = false;
    private boolean ended = false;

    // ------------------------------------------------------------ beep suppression (v1.92)
    // Nick 2026-11-05: the recognizer beeps every time it turns off/on,
    // and Slice A's restart-per-segment cycle made that constant (the
    // beeps were even transcribed as "do do do"). ROOT CAUSE: the old
    // app's hard-mute (v1.48, commit ba5c763) was deleted with all mic
    // code in v1.82 and this class never got an equivalent.
    // THE FIX, adapted to a session that must stay able to play a
    // critical cue: SYSTEM / NOTIFICATION / ALARM / VOICE_CALL are
    // hard-muted for the WHOLE session (save volume, zero, AND
    // setStreamMute — Samsung ignores volume-zero alone on some
    // streams) and restored at shutdown. MUSIC — the stream Selena's
    // own playback rides — is muted only in WINDOWS around each
    // recognizer start/stop edge: muted before startListening,
    // unmuted 700 ms after onReadyForSpeech, re-muted at
    // onEndOfSpeech / onResults / onError. Playback between edges is
    // never silenced. Restores follow the v1.10 rule: a stream is
    // only restored if it is still at the zero we set (never clobber
    // a volume Nick changed himself mid-session).
    private AudioManager audioManager;
    private static final int[] SESSION_STREAMS = {
            AudioManager.STREAM_SYSTEM,
            AudioManager.STREAM_NOTIFICATION,
            AudioManager.STREAM_ALARM,
            AudioManager.STREAM_VOICE_CALL,
    };
    private final int[] savedSessionVolumes = new int[SESSION_STREAMS.length];
    private boolean sessionStreamsMuted = false;
    private int savedMusicVolume = -1;
    private boolean musicMuted = false;
    private final Runnable musicUnmuteRunnable = () -> unmuteMusicBeep();

    private void initAudioManager() {
        if (audioManager == null) {
            audioManager = (AudioManager) appContext.getSystemService(Context.AUDIO_SERVICE);
        }
    }

    private void muteSessionStreams() {
        initAudioManager();
        if (audioManager == null || sessionStreamsMuted) return;
        try {
            for (int i = 0; i < SESSION_STREAMS.length; i++) {
                savedSessionVolumes[i] = audioManager.getStreamVolume(SESSION_STREAMS[i]);
                try { audioManager.setStreamMute(SESSION_STREAMS[i], true); } catch (Exception ignored) {}
                audioManager.setStreamVolume(SESSION_STREAMS[i], 0, 0);
            }
            sessionStreamsMuted = true;
            VoiceService.log("stream: beep streams hard-muted for session");
        } catch (Exception e) {
            VoiceService.log("stream: muteSessionStreams failed: " + e);
        }
    }

    private void unmuteSessionStreams() {
        if (audioManager == null || !sessionStreamsMuted) return;
        try {
            for (int i = 0; i < SESSION_STREAMS.length; i++) {
                try { audioManager.setStreamMute(SESSION_STREAMS[i], false); } catch (Exception ignored) {}
                if (audioManager.getStreamVolume(SESSION_STREAMS[i]) == 0) {
                    audioManager.setStreamVolume(SESSION_STREAMS[i], savedSessionVolumes[i], 0);
                }
            }
            sessionStreamsMuted = false;
            VoiceService.log("stream: beep streams restored");
        } catch (Exception e) {
            VoiceService.log("stream: unmuteSessionStreams failed: " + e);
        }
    }

    private void muteMusicBeep() {
        initAudioManager();
        if (audioManager == null || musicMuted) return;
        mainHandler.removeCallbacks(musicUnmuteRunnable);
        try {
            savedMusicVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
            try { audioManager.setStreamMute(AudioManager.STREAM_MUSIC, true); } catch (Exception ignored) {}
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0);
            musicMuted = true;
        } catch (Exception e) {
            VoiceService.log("stream: muteMusicBeep failed: " + e);
        }
    }

    private void unmuteMusicBeep() {
        if (audioManager == null || !musicMuted) return;
        try {
            try { audioManager.setStreamMute(AudioManager.STREAM_MUSIC, false); } catch (Exception ignored) {}
            if (savedMusicVolume >= 0
                    && audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) == 0) {
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, savedMusicVolume, 0);
            }
            musicMuted = false;
        } catch (Exception e) {
            VoiceService.log("stream: unmuteMusicBeep failed: " + e);
        }
    }

    private int nextSeq = 0;
    private int segIndex = 0;
    private List<String> prevPartialWords = new ArrayList<>();
    private int emittedWords = 0;
    private int droppedFrames = 0;
    private boolean restartPending = false;

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
     * session_start control frame FIRST, then create and start the
     * recognizer. Private — reachable only via the two doors above.
     */
    private void begin(JSONObject meta) {
        if (live || ended) return;
        live = true;
        muteSessionStreams();
        enqueueFrame(0L, "", false, meta);
        createRecognizer();
        startListeningCycle();
        VoiceService.log("stream: session " + sessionId + " live (mode "
                + meta.optString("mode", "?") + ")");
        listener.onStreamStatus("Session live — streaming");
    }

    /** Stop capture and tear everything down. Idempotent. */
    public void shutdown() {
        if (ended) return;
        ended = true;
        live = false;
        mainHandler.removeCallbacksAndMessages(null);
        if (recognizer != null) {
            try { recognizer.cancel(); } catch (Exception ignored) {}
            try { recognizer.destroy(); } catch (Exception ignored) {}
            recognizer = null;
        }
        unmuteMusicBeep();
        unmuteSessionStreams();
        poster.shutdownNow();
        VoiceService.log("stream: session " + sessionId + " ended"
                + " (droppedFrames=" + droppedFrames + ")");
    }

    // ------------------------------------------------------------ recognizer driving

    private void createRecognizer() {
        destroyRecognizer();
        recognizer = SpeechRecognizer.createSpeechRecognizer(appContext);
        recognizer.setRecognitionListener(this);
    }

    private void destroyRecognizer() {
        if (recognizer != null) {
            try { recognizer.cancel(); } catch (Exception ignored) {}
            try { recognizer.destroy(); } catch (Exception ignored) {}
            recognizer = null;
        }
    }

    private void startListeningCycle() {
        if (!live || recognizer == null) return;
        prevPartialWords = new ArrayList<>();
        emittedWords = 0;
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US");
        intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
        // v1.92: ask for longer silence tolerance so a mid-thought
        // pause (Nick's thinking pause) doesn't end the segment and
        // cycle the recognizer — fewer cycles, fewer beep edges.
        // (Samsung's recognizer honors these only partially; partial
        // results still stream at word grain regardless.)
        intent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 3000L);
        intent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 2500L);
        try {
            muteMusicBeep();
            recognizer.startListening(intent);
        } catch (Exception e) {
            VoiceService.log("stream: startListening failed: " + e);
            scheduleRestart(500);
        }
    }

    private void scheduleRestart(long delayMs) {
        if (!live || restartPending) return;
        restartPending = true;
        mainHandler.postDelayed(() -> {
            restartPending = false;
            if (!live) return;
            // v1.92 wedge fix: the v1.91 code reused the SAME
            // SpeechRecognizer object across restarts. In the first
            // live session (2026-11-05 09:46) it produced three
            // segments, then kept cycling (mic configs flipping in
            // the app logs) while turning speech into NO frames —
            // Nick's "I sent more" never landed. The old dictation
            // code always destroyed + recreated the recognizer on
            // restart; do the same here.
            createRecognizer();
            startListeningCycle();
        }, delayMs);
    }

    private long nowMs() {
        return SystemClock.uptimeMillis() - sessionStartUptimeMs;
    }

    private String segId() {
        return "seg-" + segIndex;
    }

    // ------------------------------------------------------------ frame emission

    /**
     * Delta computation from partials: diff the new partial's word
     * list against the previous partial by longest common prefix.
     * Words beyond the prefix are new; if the recognizer revised an
     * already-emitted word (prefix shrank below emittedWords), the
     * corrected tail is re-emitted as fresh frames — the segment's
     * final frame remains the canonical record. Emitted words go
     * out in chunks of at most 2, one frame per chunk, so the wire
     * grain is the specced 1–2 words.
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
            enqueueFrame(nowMs(), settled, true, null);
        }
        segIndex++;
        prevPartialWords = new ArrayList<>();
        emittedWords = 0;
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

    // ------------------------------------------------------------ RecognitionListener

    @Override
    public void onReadyForSpeech(Bundle params) {
        // v1.92: the start beep has sounded (muted) by now; restore
        // MUSIC shortly after so playback between edges is normal.
        mainHandler.removeCallbacks(musicUnmuteRunnable);
        mainHandler.postDelayed(musicUnmuteRunnable, 700);
    }
    @Override public void onBeginningOfSpeech() {}
    @Override public void onRmsChanged(float rmsdB) {}
    @Override public void onBufferReceived(byte[] buffer) {}
    @Override
    public void onEndOfSpeech() {
        // v1.92: re-mute MUSIC before the stop edge / restart.
        muteMusicBeep();
    }

    @Override
    public void onError(int error) {
        if (!live) return;
        muteMusicBeep();
        switch (error) {
            case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS:
                VoiceService.log("stream: recognizer lost mic permission — ending session");
                live = false;
                listener.onStreamEnded(
                        "Microphone permission was revoked — session stopped.");
                shutdown();
                break;
            case SpeechRecognizer.ERROR_RECOGNIZER_BUSY:
                scheduleRestart(500);
                break;
            case SpeechRecognizer.ERROR_NO_MATCH:
            case SpeechRecognizer.ERROR_SPEECH_TIMEOUT:
                scheduleRestart(120);
                break;
            default:
                scheduleRestart(400);
                break;
        }
    }

    @Override
    public void onResults(Bundle results) {
        if (!live) return;
        muteMusicBeep();
        ArrayList<String> texts =
                results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        onFinalText(texts != null && !texts.isEmpty() ? texts.get(0) : "");
        scheduleRestart(120);
    }

    @Override
    public void onPartialResults(Bundle partialResults) {
        if (!live) return;
        ArrayList<String> texts = partialResults.getStringArrayList(
                SpeechRecognizer.RESULTS_RECOGNITION);
        if (texts != null && !texts.isEmpty() && texts.get(0) != null) {
            onPartialText(texts.get(0));
        }
    }

    @Override public void onEvent(int eventType, Bundle params) {}
}
