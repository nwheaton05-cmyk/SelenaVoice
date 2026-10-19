package com.selena.voice;

import android.util.Log;

/**
 * Polling task for VoiceService (top-level to avoid d8 inner-class bug).
 *
 * Nick 2026-10-19 v1.16: the service owns outbound polling again (moved
 * back from MainActivity for background operation). pollOnce() spawns a
 * worker thread and the worker schedules the next poll when it finishes,
 * so this task only kicks off one poll — no double-scheduling.
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
            service.scheduleNextPoll();
        }
    }
}
