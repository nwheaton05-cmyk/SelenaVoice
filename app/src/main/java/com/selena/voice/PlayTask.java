package com.selena.voice;

import java.io.File;

/** Top-level (avoids d8 inner-class bug). */
public class PlayTask implements Runnable {
    private final VoiceService service;
    private final File file;
    public PlayTask(VoiceService service, File file) {
        this.service = service;
        this.file = file;
    }
    @Override public void run() { service.playFileNow(file); }
}
