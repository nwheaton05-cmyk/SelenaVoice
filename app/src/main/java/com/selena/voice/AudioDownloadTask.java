package com.selena.voice;

import android.util.Log;

import java.io.File;

/**
 * Downloads one audio file on a background thread, then asks the service
 * to play it. Top-level (avoids d8 inner-class bug).
 */
public class AudioDownloadTask implements Runnable {
    private static final String TAG = "SelenaVoice";
    private final VoiceService service;
    private final String url;

    public AudioDownloadTask(VoiceService service, String url) {
        this.service = service;
        this.url = url;
    }

    @Override
    public void run() {
        try {
            File f = service.downloadToCache(url);
            service.playDownloadedFile(f);
        } catch (Exception e) {
            Log.w(TAG, "audio download failed", e);
            service.onPlaybackDone();
        }
    }
}
