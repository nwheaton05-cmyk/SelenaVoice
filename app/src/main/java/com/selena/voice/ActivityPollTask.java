package com.selena.voice;

/**
 * Polling task for MainActivity (top-level to avoid d8 inner-class bug).
 *
 * Nick 2026-10-18 v1.4: outbound polling moved here from VoiceService.
 * Samsung's background execution limits kill the service's Handler loop,
 * so the service never polled ("Poll: not started" forever). The activity
 * polls every 3s while it is in the foreground.
 */
public class ActivityPollTask implements Runnable {
    private final MainActivity activity;

    public ActivityPollTask(MainActivity activity) {
        this.activity = activity;
    }

    @Override
    public void run() {
        if (!activity.isPolling()) return;
        activity.pollOnce();
    }
}
