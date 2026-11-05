package com.selena.voice;

import android.media.AudioManager;
import android.media.AudioRecordingConfiguration;

import java.util.List;

/**
 * Nick 2026-11-02 v1.83: recording-configuration watch for the
 * MIC-ACTIVITY AUTO-PAUSE. Forwards device-wide recording-set
 * changes to VoiceService, which pauses/holds playback while any
 * OTHER app uses the microphone (his case: dictation in the Muse
 * app picking up Selena's voice).
 *
 * v1.84: the full configuration list is forwarded (not just the
 * count) so VoiceService can apply MIC-HOLD SOURCE FILTERING —
 * persistent hotword configurations no longer hold playback,
 * while genuine recording apps still do. The public API exposes
 * no client identity on a configuration (package/uid accessors
 * are system-API only); the audio source is what a regular app
 * can see, and it is what the filter classifies on.
 *
 * v1.93 (2026-11-05): this app records again — coach sessions
 * only (CoachStreamer). The filter additionally exempts the
 * session's OWN capture by exact audio session id (the one
 * client identifier the public API does expose); see
 * VoiceService.registerOwnMicSession. Full duplex: her playback
 * runs while our own session listens.
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
        service.onMicRecordingConfigsChanged(configs);
    }
}
