package com.selena.voice;

import android.util.Log;

/**
 * Polling task for VoiceService (top-level to avoid d8 inner-class bug).
 * Runs the queue poll on a background thread, then reschedules.
 */
public class PollTask implements Runnable {
    private static final String TAG = "SelenaVoice";
    private final VoiceService service;

    public PollTask(VoiceService service) {
        this.service = service;
    }

    @Override
    public void run() {
        if (service.isDestroyedFlag()) return;
        try {
            service.pollOnce();
        } catch (Exception e) {
            Log.w(TAG, "poll failed", e);
        }
        service.scheduleNextPoll();
    }
}
