package com.selena.voice;

import android.content.Context;
import android.content.Intent;
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
 * this class is instantiated ONLY by MainActivity's disclosure
 * confirmation handler, and begin() is its only entry point. There
 * is no code path that creates or starts the recognizer without
 * passing through that confirmation. Outside a session, capture
 * never runs: Stop (or activity destroy) calls shutdown(), which
 * destroys the recognizer.
 *
 * DISCLOSURE PROOF (Nick, 2026-11-05 00:19 EST — CT all-party
 * consent): the first frame of every session is a control frame
 * (seq 0, seg_id "__session__") whose meta carries
 * {type: "session_start", disclosure_confirmed_at: <ISO time of
 * the confirmation tap>}. It is enqueued before the recognizer
 * starts, so no word frame can precede it in the record.
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
     * The ONLY start path. Enqueues the disclosure-proof control
     * frame first, then creates and starts the recognizer. Called
     * exactly once, from the disclosure confirmation handler.
     */
    public void begin(String disclosureConfirmedIso) {
        if (live || ended) return;
        live = true;
        try {
            JSONObject meta = new JSONObject();
            meta.put("type", "session_start");
            meta.put("disclosure_confirmed_at", disclosureConfirmedIso);
            enqueueFrame(0L, "", false, meta);
        } catch (Exception e) {
            VoiceService.log("stream: session_start frame build failed: " + e);
        }
        recognizer = SpeechRecognizer.createSpeechRecognizer(appContext);
        recognizer.setRecognitionListener(this);
        startListeningCycle();
        VoiceService.log("stream: session " + sessionId + " live"
                + " (disclosure confirmed " + disclosureConfirmedIso + ")");
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
        poster.shutdownNow();
        VoiceService.log("stream: session " + sessionId + " ended"
                + " (droppedFrames=" + droppedFrames + ")");
    }

    // ------------------------------------------------------------ recognizer driving

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
        try {
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

    @Override public void onReadyForSpeech(Bundle params) {}
    @Override public void onBeginningOfSpeech() {}
    @Override public void onRmsChanged(float rmsdB) {}
    @Override public void onBufferReceived(byte[] buffer) {}
    @Override public void onEndOfSpeech() {}

    @Override
    public void onError(int error) {
        if (!live) return;
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
