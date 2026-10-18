package com.selena.voice;

/** Top-level (avoids d8 inner-class bug). */
public class RecognizerRestartTask implements Runnable {
    private final VoiceService service;
    public RecognizerRestartTask(VoiceService service) { this.service = service; }
    @Override public void run() { service.restartRecognizerNow(); }
}
