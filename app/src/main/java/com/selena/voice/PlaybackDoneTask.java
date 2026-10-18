package com.selena.voice;

/** Top-level (avoids d8 inner-class bug). */
public class PlaybackDoneTask implements Runnable {
    private final VoiceService service;
    public PlaybackDoneTask(VoiceService service) { this.service = service; }
    @Override public void run() { service.onPlaybackDoneNow(); }
}
