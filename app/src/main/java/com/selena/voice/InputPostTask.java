package com.selena.voice;

/**
 * POSTs voice input on a background thread. Top-level (avoids d8 inner-class bug).
 */
public class InputPostTask implements Runnable {
    private final VoiceService service;
    private final String text;

    public InputPostTask(VoiceService service, String text) {
        this.service = service;
        this.text = text;
    }

    @Override
    public void run() {
        service.doPostInput(text);
    }
}
