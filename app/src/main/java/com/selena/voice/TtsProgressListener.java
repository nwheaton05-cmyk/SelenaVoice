package com.selena.voice;

import android.speech.tts.UtteranceProgressListener;

/**
 * Nick 2026-10-18 v1.11: top-level (not inner, not anonymous) TTS progress
 * listener. Inner classes extending UtteranceProgressListener crash this
 * project's d8 with an R8 NPE (the InnerClasses attribute comes out wrong
 * when the listener is declared inside the OnInitListener lambda) — a
 * top-level class dexes cleanly.
 *
 * The activity implements Callback so no anonymous classes are needed
 * anywhere in the TTS path.
 */
public class TtsProgressListener extends UtteranceProgressListener {

    /** Implemented by MainActivity. */
    public interface Callback {
        /** Called when on-device speech starts: raise the echo guard. */
        void onTtsStarted();

        /**
         * Called when on-device speech finishes or fails: lower the echo
         * guard and pump the queues. Runs on a binder thread — the activity
         * marshals to the main thread.
         */
        void onTtsFinished();
    }

    private final Callback callback;

    public TtsProgressListener(Callback callback) {
        this.callback = callback;
    }

    @Override
    public void onStart(String utteranceId) {
        // Echo guard on — same as MP3 playback so the mic never hears
        // Selena's own voice.
        VoiceService.setPlayingAudio(true);
        callback.onTtsStarted();
    }

    @Override
    public void onDone(String utteranceId) {
        MainActivity.log("tts: on-device done");
        callback.onTtsFinished();
    }

    @Override
    public void onError(String utteranceId) {
        MainActivity.log("tts: on-device error id=" + utteranceId);
        callback.onTtsFinished();
    }
}
