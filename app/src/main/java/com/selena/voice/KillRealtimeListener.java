package com.selena.voice;

import org.json.JSONObject;

/**
 * v1.98 (Nick, 2026-11-05): Realtime listener for the correction
 * kill channel, public.selena_voice_kills. Top-level class per the
 * standing d8 rule (VoiceService implements the queue channel's
 * listener directly; this second channel gets its own class so the
 * service stays the listener of record for the queue only).
 *
 * The channel is started and stopped WITH the coach session (see
 * VoiceService.armCorrectionKill / disarmCorrectionKill): outside
 * a live coach session this listener does not exist, no kill can
 * arrive, and playback behavior is exactly v1.97's.
 *
 * Callbacks fire on the Realtime reader thread; VoiceService
 * marshals application to the main thread itself.
 */
public class KillRealtimeListener implements RealtimeClient.Listener {
    private final VoiceService service;

    public KillRealtimeListener(VoiceService service) {
        this.service = service;
    }

    @Override
    public void onInsert(JSONObject record) {
        service.onKillSignal(record);
    }

    @Override
    public void onStatus(String msg) {
        VoiceService.log("kill-rt: " + msg);
    }
}
