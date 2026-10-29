package com.selena.voice;

/**
 * POSTs voice input on a background thread. Top-level (avoids d8 inner-class bug).
 */
public class InputPostTask implements Runnable {
    private final VoiceService service;
    private final String text;
    // Nick 2026-10-28 v1.52: true when this dictation was held by the
    // noise gate and sent on the 60s no-further-dictation clock — the
    // brain uses the flag to judge whether the message was cut off.
    private final boolean possiblyCutOff;

    public InputPostTask(VoiceService service, String text) {
        this(service, text, false);
    }

    public InputPostTask(VoiceService service, String text, boolean possiblyCutOff) {
        this.service = service;
        this.text = text;
        this.possiblyCutOff = possiblyCutOff;
    }

    @Override
    public void run() {
        service.doPostInput(text, possiblyCutOff);
    }
}
