package com.selena.voice;

import android.media.MediaPlayer;

/** Top-level (avoids d8 inner-class bug). */
public class PlayerCompletionListener implements MediaPlayer.OnCompletionListener {
    private final VoiceService service;

    public PlayerCompletionListener(VoiceService service) {
        this.service = service;
    }

    @Override
    public void onCompletion(MediaPlayer mp) {
        service.onPlaybackDone();
    }
}
