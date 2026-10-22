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

    /** Implemented by VoiceService (v1.16: TTS moved into the service). */
    public interface Callback {
        /** Called when on-device speech starts: raise the echo guard. */
        void onTtsStarted();

        /**
         * Called when on-device speech finishes or fails: lower the echo
         * guard and pump the queues. Runs on a binder thread — the activity
         * marshals to the main thread.
         */
        void onTtsFinished();

        /**
         * Nick 2026-10-22 v1.27: word-boundary callbacks while speaking
         * (API 26+). Lets pause/resume restart from the last spoken word
         * instead of the top of the message. Runs on a binder thread.
         */
        void onTtsRangeStart(int startChar);

        /**
         * Nick 2026-10-22 v1.27: tts.stop() was called (pause). Distinct
         * from onDone/onError so a pause doesn't pump the queue or lower
         * the echo guard the way a finished utterance does. Runs on a
         * binder thread.
         */
        void onTtsStopped();
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
        VoiceService.log("tts: on-device done");
        callback.onTtsFinished();
    }

    @Override
    public void onError(String utteranceId) {
        VoiceService.log("tts: on-device error id=" + utteranceId);
        callback.onTtsFinished();
    }

    @Override
    public void onStop(String utteranceId, boolean interrupted) {
        // Nick 2026-10-22 v1.27: tts.stop() (the pause path). Not a
        // finish — the service holds the paused position for resume.
        VoiceService.log("tts: on-device stopped interrupted=" + interrupted);
        callback.onTtsStopped();
    }

    @Override
    public void onRangeStart(String utteranceId, int start, int end, int frame) {
        callback.onTtsRangeStart(start);
    }
}
