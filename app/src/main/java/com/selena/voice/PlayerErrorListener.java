package com.selena.voice;

import android.media.MediaPlayer;

/** Top-level (avoids d8 inner-class bug). */
public class PlayerErrorListener implements MediaPlayer.OnErrorListener {
    private final VoiceService service;

    public PlayerErrorListener(VoiceService service) {
        this.service = service;
    }

    @Override
    public boolean onError(MediaPlayer mp, int what, int extra) {
        service.onPlaybackDone();
        return true;
    }
}
