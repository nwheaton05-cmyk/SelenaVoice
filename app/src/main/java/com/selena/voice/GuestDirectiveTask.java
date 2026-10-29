package com.selena.voice;

/**
 * Nick 2026-10-29 v1.62: POSTs a guest-flow directive on a worker
 * thread (the service does the HTTP in doPostGuestDirective,
 * mirroring MachineNameDirectiveTask / WakeDirectiveTask).
 *
 * Actions: "new_voice_ask" (she just asked who's talking/asking;
 * carries the line spoken and the held dictation text),
 * "guest_enrolled" (a guest profile was created; carries the name),
 * "guest_speaking" (the dictation that follows — posted immediately
 * before it, in order — is this guest's, so the responder addresses
 * them by name).
 *
 * Top-level class per the standing d8 rule.
 */
public class GuestDirectiveTask implements Runnable {
    private final VoiceService service;
    private final String action;
    private final String speakerName;
    private final String line;
    private final String text;

    public GuestDirectiveTask(VoiceService service, String action,
                              String speakerName, String line, String text) {
        this.service = service;
        this.action = action;
        this.speakerName = speakerName;
        this.line = line;
        this.text = text;
    }

    @Override
    public void run() {
        service.doPostGuestDirective(action, speakerName, line, text);
    }
}
