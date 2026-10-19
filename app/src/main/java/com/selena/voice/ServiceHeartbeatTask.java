package com.selena.voice;

/**
 * Heartbeat task for VoiceService (top-level to avoid d8 inner-class bug).
 *
 * Nick 2026-10-19 v1.16: tells the server the service is alive so the
 * fast responder knows Nick can hear replies — now runs in the service
 * (not the activity) so it keeps beating with the screen locked.
 */
public class ServiceHeartbeatTask implements Runnable {
    private final VoiceService service;

    public ServiceHeartbeatTask(VoiceService service) {
        this.service = service;
    }

    @Override
    public void run() {
        if (service.isDestroyedFlag() || !service.isHeartbeating()) return;
        service.sendHeartbeat();
    }
}
