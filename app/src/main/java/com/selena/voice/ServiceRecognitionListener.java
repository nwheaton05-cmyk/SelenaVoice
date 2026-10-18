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

    public ServiceRecognitionListener(VoiceService service) {
        this.service = service;
    }

    @Override public void onReadyForSpeech(Bundle params) {}
    @Override public void onBufferReceived(byte[] buffer) {}
    @Override public void onRmsChanged(float rmsdB) {}

    @Override
    public void onBeginningOfSpeech() {
        service.onSpeechDetected();
    }

    @Override public void onEndOfSpeech() {
        service.onSpeechEnd();
    }

    @Override
    public void onError(int error) {
        Log.d(TAG, "recognizer error: " + error);
        service.onRecognizerDone();
    }

    @Override
    public void onResults(Bundle results) {
        ArrayList<String> matches =
                results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        String text = (matches != null && !matches.isEmpty()) ? matches.get(0) : "";
        service.onRecognizerDone();
        if (service.isPlayingAudio()) {
            // Nick 2026-10-18 v1.2: echo prevention — the recognizer picked
            // up Selena's own playback, not Nick. Discard, don't POST.
            Log.i(TAG, "discarding result during playback: " + text);
            return;
        }
        if (!text.isEmpty()) {
            service.onVoiceInput(text);
        }
    }

    @Override public void onPartialResults(Bundle partialResults) {}
    @Override public void onEvent(int eventType, Bundle params) {}
}
