package com.selena.voice;

/**
 * Nick 2026-10-29 v1.60: POSTs the wake-word directive on a background
 * thread (pattern follows InputPostTask). Top-level (avoids the d8
 * inner-class bug).
 */
public class WakeDirectiveTask implements Runnable {
    private final VoiceService service;
    private final String keyword;

    public WakeDirectiveTask(VoiceService service, String keyword) {
        this.service = service;
        this.keyword = keyword;
    }

    @Override
    public void run() {
        service.doPostWakeDirective(keyword);
    }
}
