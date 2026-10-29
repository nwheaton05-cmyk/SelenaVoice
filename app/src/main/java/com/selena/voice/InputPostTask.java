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
    // Nick 2026-10-29 v1.62: the enrolled guest who spoke this
    // dictation (standalone group mode), or null when it's the
    // signed-in user. The post carries it so the responder addresses
    // the guest by name.
    private final String speakerName;
    // Nick 2026-10-29 v1.64: interruption context JSON (see
    // VoiceService.snapshotInterruption), or null when this dictation
    // interrupted nothing.
    private final String interruptJson;

    public InputPostTask(VoiceService service, String text) {
        this(service, text, false);
    }

    public InputPostTask(VoiceService service, String text, boolean possiblyCutOff) {
        this(service, text, possiblyCutOff, null);
    }

    public InputPostTask(VoiceService service, String text,
                         boolean possiblyCutOff, String speakerName) {
        this(service, text, possiblyCutOff, speakerName, null);
    }

    public InputPostTask(VoiceService service, String text,
                         boolean possiblyCutOff, String speakerName,
                         String interruptJson) {
        this.service = service;
        this.text = text;
        this.possiblyCutOff = possiblyCutOff;
        this.speakerName = speakerName;
        this.interruptJson = interruptJson;
    }

    @Override
    public void run() {
        service.doPostInput(text, possiblyCutOff, speakerName, interruptJson);
    }
}
