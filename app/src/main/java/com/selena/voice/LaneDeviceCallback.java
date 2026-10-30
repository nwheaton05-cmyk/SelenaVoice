package com.selena.voice;

import android.media.AudioDeviceCallback;
import android.media.AudioDeviceInfo;

/**
 * Nick 2026-10-30 v1.73: audio-device watch for the EARBUDS-ONLY
 * PLAYBACK LANE. Forwards output-device add/remove events to
 * VoiceService, which decides (by saved earbuds NAME) whether held
 * playback releases (earbuds arrived) or an in-flight play aborts
 * back to its queue unplayed (earbuds vanished mid-play).
 *
 * Top-level class per the standing d8 rule (anonymous inner classes
 * broke the build-tools 34 d8 nestmate desugaring, 2026-10-29).
 */
public class LaneDeviceCallback extends AudioDeviceCallback {
    private final VoiceService service;

    public LaneDeviceCallback(VoiceService service) {
        this.service = service;
    }

    @Override
    public void onAudioDevicesAdded(AudioDeviceInfo[] addedDevices) {
        service.onLaneDevicesAdded(addedDevices);
    }

    @Override
    public void onAudioDevicesRemoved(AudioDeviceInfo[] removedDevices) {
        service.onLaneDevicesRemoved(removedDevices);
    }
}
