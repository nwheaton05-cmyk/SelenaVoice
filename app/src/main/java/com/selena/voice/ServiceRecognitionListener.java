package com.selena.voice;

import android.os.Bundle;
import android.speech.RecognitionListener;
import android.speech.SpeechRecognizer;
import android.util.Log;

import java.util.ArrayList;

/**
 * RecognitionListener for VoiceService (top-level to avoid d8 inner-class bug).
 */
public class ServiceRecognitionListener implements RecognitionListener {
    private static final String TAG = "SelenaVoice";
    private final VoiceService service;

    // Nick 2026-10-18 v1.3: background-noise filtering. Track the loudest
    // RMS level during each utterance; reset at the start of speech.
    // rmsdB is negative (e.g. -2 = loud, -10 = quiet). If the loudest
    // moment of an utterance is still below the threshold, it was background
    // noise (TV, equipment, etc.), not Nick talking into the phone.
    private static final float RMS_THRESHOLD_DB = -8.0f;
    private float maxRmsThisUtterance = -100.0f;

    // Nick 2026-10-23 v1.46: polite interruption — track the LIVE mic
    // level so VoiceService can tell, entirely on-device, whether Nick
    // is speaking right now. Stale readings (recognizer between quiet
    // restarts) don't count — isMicHot() expires them.
    private volatile float lastRmsDb = -100.0f;
    private volatile long lastRmsMs = 0;
    private static final long RMS_STALE_MS = 2000;

    // Nick 2026-10-18 v1.3: minimum recognizer confidence. Below this,
    // the transcription is a guess — discard instead of sending garbage.
    private static final float CONFIDENCE_THRESHOLD = 0.5f;

    // Nick 2026-10-28 v1.52: noise-gate sampler window. The gate in
    // VoiceService needs level AND variation, not just a peak: speech
    // RMS swings syllable to syllable, a mower drone sits flat. We keep
    // the last ~48 RMS callbacks (they arrive many times a second while
    // the recognizer owns the mic) and report mean / stddev / span over
    // the recent slice. No second mic consumer is ever opened while the
    // recognizer is live — see VoiceService's gate notes.
    private static final int SAMPLER_WINDOW = 48;
    private static final long SAMPLER_WINDOW_MS = 1500;
    private final float[] samplerDb = new float[SAMPLER_WINDOW];
    private final long[] samplerMs = new long[SAMPLER_WINDOW];
    private int samplerHead = 0;
    private int samplerCount = 0;

    public ServiceRecognitionListener(VoiceService service) {
        this.service = service;
    }

    @Override public void onReadyForSpeech(Bundle params) {}
    @Override public void onBufferReceived(byte[] buffer) {}

    @Override
    public void onRmsChanged(float rmsdB) {
        if (rmsdB > maxRmsThisUtterance) {
            maxRmsThisUtterance = rmsdB;
        }
        // Nick 2026-10-23 v1.46: live level for the polite-interruption
        // gate (see isMicHot).
        lastRmsDb = rmsdB;
        lastRmsMs = android.os.SystemClock.uptimeMillis();
        // Nick 2026-10-28 v1.52: feed the noise-gate sampler window.
        samplerDb[samplerHead] = rmsdB;
        samplerMs[samplerHead] = lastRmsMs;
        samplerHead = (samplerHead + 1) % SAMPLER_WINDOW;
        if (samplerCount < SAMPLER_WINDOW) samplerCount++;
    }

    /**
     * Nick 2026-10-28 v1.52: sampler snapshot for the VoiceService
     * noise gate, computed over RMS samples from the last
     * SAMPLER_WINDOW_MS. Returns {meanDb, stdDb, spanMs, fresh} where
     * fresh is 1.0 when a sample arrived within the last 700ms (the
     * recognizer goes quiet between sessions — stale windows must
     * never be judged as "steady noise").
     */
    public float[] samplerStats() {
        long now = android.os.SystemClock.uptimeMillis();
        double sum = 0;
        int n = 0;
        long oldest = Long.MAX_VALUE;
        long newest = 0;
        for (int i = 0; i < samplerCount; i++) {
            long t = samplerMs[i];
            if (now - t > SAMPLER_WINDOW_MS) continue;
            sum += samplerDb[i];
            n++;
            if (t < oldest) oldest = t;
            if (t > newest) newest = t;
        }
        if (n < 4) {
            return new float[]{-100f, 0f, 0f, 0f};
        }
        double mean = sum / n;
        double var = 0;
        for (int i = 0; i < samplerCount; i++) {
            long t = samplerMs[i];
            if (now - t > SAMPLER_WINDOW_MS) continue;
            double d = samplerDb[i] - mean;
            var += d * d;
        }
        float std = (float) Math.sqrt(var / n);
        float span = (float) (newest - oldest);
        float fresh = (now - lastRmsMs <= 700) ? 1f : 0f;
        return new float[]{(float) mean, std, span, fresh};
    }

    /**
     * Nick 2026-10-23 v1.46: true when the mic level RIGHT NOW looks
     * like active speech rather than background. The polite-interruption
     * gate in VoiceService uses this (with the utterance-level speech
     * flag) to decide, entirely on-device, whether Selena may start
     * speaking or must hold for Nick's pause.
     */
    public boolean isMicHot() {
        if (android.os.SystemClock.uptimeMillis() - lastRmsMs > RMS_STALE_MS) {
            return false;
        }
        return lastRmsDb >= RMS_THRESHOLD_DB;
    }

    @Override
    public void onBeginningOfSpeech() {
        maxRmsThisUtterance = -100.0f;
        service.onSpeechDetected();
    }

    @Override public void onEndOfSpeech() {
        service.onSpeechEnd();
    }

    @Override
    public void onError(int error) {
        Log.d(TAG, "recognizer error: " + error);
        // Nick 2026-10-23 v1.47: if the mic is muted for Selena
        // playback, swallow the error (cancel() triggers ERROR_CLIENT)
        // — the unmute path restarts the recognizer when she's done.
        if (service.isMicMutedForPlayback()) return;
        if (error == SpeechRecognizer.ERROR_NO_MATCH
                || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
            // Nick 2026-10-18 v1.3: routine silence, not a real error.
            // Quiet restart (no unmute/mute dance) with the longer backoff
            // so the Samsung beep fires far less often.
            service.scheduleQuietRestart();
        } else {
            service.onRecognizerDone();
        }
    }

    @Override
    public void onResults(Bundle results) {
        ArrayList<String> matches =
                results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        String text = (matches != null && !matches.isEmpty()) ? matches.get(0) : "";
        float confidence = getTopConfidence(results);
        float peakRms = maxRmsThisUtterance;
        maxRmsThisUtterance = -100.0f;
        service.onRecognizerDone();
        if (service.isPlayingAudio()) {
            // Nick 2026-10-18 v1.2: echo prevention — the recognizer picked
            // up Selena's own playback, not Nick. Discard, don't POST.
            Log.i(TAG, "discarding result during playback: " + text);
            return;
        }
        if (service.isGateDroneActiveNow()) {
            // Nick 2026-10-28 v1.52: the noise gate hears a loud, steady
            // drone right now — this "result" is the machine, not the
            // user, whatever the confidence/RMS filters would say (a
            // drone is LOUD, so the old peak-RMS filter waved it in).
            Log.i(TAG, "discarding result during gate drone: " + text);
            return;
        }
        if (text.isEmpty()) {
            return;
        }
        // Nick 2026-10-18 v1.3: background-noise filtering.
        if (confidence >= 0 && confidence < CONFIDENCE_THRESHOLD) {
            Log.i(TAG, "low confidence (" + confidence + "), discarding: " + text);
            return;
        }
        if (peakRms < RMS_THRESHOLD_DB) {
            Log.i(TAG, "too quiet (peak " + peakRms + " dB), discarding as background noise: "
                    + text);
            return;
        }
        Log.i(TAG, "voice input accepted (conf=" + confidence + ", peakRms=" + peakRms
                + "): " + text);
        service.onVoiceInput(text);
    }

    /**
     * Returns the recognizer's confidence for the top result, or -1 if the
     * recognizer didn't supply scores (some engines omit them — in that
     * case we skip the confidence check and rely on volume only).
     */
    private float getTopConfidence(Bundle results) {
        try {
            float[] scores = results.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES);
            if (scores != null && scores.length > 0) {
                return scores[0];
            }
        } catch (Exception e) {
            Log.d(TAG, "confidence scores unavailable", e);
        }
        return -1.0f;
    }

    @Override
    public void onPartialResults(Bundle partialResults) {
        // Nick 2026-10-23 v1.46: live dictation popup — forward the
        // in-progress transcription so the UI shows exactly what Nick is
        // dictating in real time. No confidence/RMS filtering here: this
        // is a live view, not an accepted input. The final accept/reject
        // still happens in onResults.
        ArrayList<String> matches = partialResults.getStringArrayList(
                SpeechRecognizer.RESULTS_RECOGNITION);
        String text = (matches != null && !matches.isEmpty()) ? matches.get(0) : "";
        if (!text.isEmpty()) {
            service.onPartialVoiceInput(text);
        }
    }
    @Override public void onEvent(int eventType, Bundle params) {}
}
