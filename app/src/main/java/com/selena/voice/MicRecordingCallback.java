package com.selena.voice;

import android.media.AudioManager;
import android.media.AudioRecordingConfiguration;

import java.util.List;

/**
 * Nick 2026-11-02 v1.83: recording-configuration watch for the
 * MIC-ACTIVITY AUTO-PAUSE. Forwards device-wide recording-set
 * changes to VoiceService, which pauses/holds playback while any
 * OTHER app uses the microphone (his case: dictation in the Muse
 * app picking up Selena's voice). Post-v1.82 this app never
 * records, so every configuration in the list is external. AOSP
 * anonymizes the entries for a regular app (origin identity
 * stripped) — the COUNT is device-wide and is all the hold needs.
 *
 * Top-level class per the standing d8 rule (anonymous inner classes
 * broke the build-tools 34 d8 nestmate desugaring, 2026-10-29).
 */
public class MicRecordingCallback
        extends AudioManager.AudioRecordingCallback {
    private final VoiceService service;

    public MicRecordingCallback(VoiceService service) {
        this.service = service;
    }

    @Override
    public void onRecordingConfigChanged(
            List<AudioRecordingConfiguration> configs) {
        service.onMicRecordingConfigsChanged(
                configs == null ? 0 : configs.size());
    }
}
