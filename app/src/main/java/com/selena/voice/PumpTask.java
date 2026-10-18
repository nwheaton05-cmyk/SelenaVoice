package com.selena.voice;

/** Top-level (avoids d8 inner-class bug). */
public class PumpTask implements Runnable {
    private final VoiceService service;
    public PumpTask(VoiceService service) { this.service = service; }
    @Override public void run() { service.pumpAudioQueueNow(); }
}
