package com.selena.voice;

/**
 * Heartbeat task for MainActivity (top-level to avoid d8 inner-class bug).
 *
 * Nick 2026-10-18 v1.6: tells the server the app is open/visible so the
 * fast responder only burns tokens while Nick can actually hear replies.
 */
public class HeartbeatTask implements Runnable {
    private final MainActivity activity;

    public HeartbeatTask(MainActivity activity) {
        this.activity = activity;
    }

    @Override
    public void run() {
        if (!activity.isHeartbeating()) return;
        activity.sendHeartbeat();
    }
}
