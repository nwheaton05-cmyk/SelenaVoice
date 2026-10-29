package com.selena.voice;

/**
 * Nick 2026-10-29 v1.61 (13:21/13:25/13:27/13:28 rulings): POSTs the
 * machine-naming events on a background thread — the durable record
 * on the normal assistant path (pattern follows WakeDirectiveTask;
 * the POST itself lives in VoiceService.doPostMachineDirective).
 * Two events:
 *   - "machine_during_drone": an unnamed machine is running and the
 *     app said the during-drone line, "What is that horrible noise?"
 *   - "machine_naming_ask": a run of an unnamed machine ended and
 *     the app asked "Hey, {user}, what was that noise?"
 * Fire-and-forget: nothing in the naming flow waits on the network.
 *
 * Top-level class per the standing d8 rule (no inner classes).
 */
public class MachineNameDirectiveTask implements Runnable {
    private final VoiceService service;
    private final String action;
    private final int profileId;
    private final String profileName;
    private final String line;

    public MachineNameDirectiveTask(VoiceService service, String action,
                                    int profileId, String profileName,
                                    String line) {
        this.service = service;
        this.action = action;
        this.profileId = profileId;
        this.profileName = profileName;
        this.line = line;
    }

    @Override
    public void run() {
        service.doPostMachineDirective(action, profileId, profileName, line);
    }
}
