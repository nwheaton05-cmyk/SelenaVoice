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

    // Nick 2026-10-18 v1.3: minimum recognizer confidence. Below this,
    // the transcription is a guess — discard instead of sending garbage.
    private static final float CONFIDENCE_THRESHOLD = 0.5f;

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

    @Override public void onPartialResults(Bundle partialResults) {}
    @Override public void onEvent(int eventType, Bundle params) {}
}
