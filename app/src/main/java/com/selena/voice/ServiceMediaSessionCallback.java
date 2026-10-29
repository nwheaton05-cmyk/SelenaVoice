package com.selena.voice;

import android.content.Intent;
import android.media.session.MediaSession;
import android.view.KeyEvent;

/**
 * Nick 2026-10-28 v1.51: MediaSession callback for VoiceService —
 * the primary path for Bluetooth earbud / headset button presses.
 *
 * Top-level class (never an inner class) per the standing d8 rule:
 * d8 8.2.2 crashes on inner-class bytecode from javac 21 for classes
 * extending Android listener types, and MediaSession.Callback is one.
 *
 * Everything delegates to VoiceService.onEarbudAction, which owns the
 * semantics and the duplicate debounce (the manifest EarbudButtonReceiver
 * can deliver the same physical press through the legacy broadcast path).
 */
public class ServiceMediaSessionCallback extends MediaSession.Callback {
    private final VoiceService service;

    public ServiceMediaSessionCallback(VoiceService service) {
        this.service = service;
    }

    @Override
    public void onPlay() {
        service.onEarbudAction("play", "session");
    }

    @Override
    public void onPause() {
        service.onEarbudAction("pause", "session");
    }

    @Override
    public void onSkipToNext() {
        service.onEarbudAction("next", "session");
    }

    @Override
    public void onSkipToPrevious() {
        service.onEarbudAction("previous", "session");
    }

    @Override
    public void onStop() {
        service.onEarbudAction("stop", "session");
    }

    @Override
    public boolean onMediaButtonEvent(Intent mediaButtonIntent) {
        KeyEvent event = mediaButtonIntent.getParcelableExtra(Intent.EXTRA_KEY_EVENT);
        if (event != null && event.getAction() == KeyEvent.ACTION_DOWN) {
            String action = null;
            switch (event.getKeyCode()) {
                case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                case KeyEvent.KEYCODE_HEADSETHOOK:
                    action = "toggle";
                    break;
                case KeyEvent.KEYCODE_MEDIA_PLAY:
                    action = "play";
                    break;
                case KeyEvent.KEYCODE_MEDIA_PAUSE:
                    action = "pause";
                    break;
                case KeyEvent.KEYCODE_MEDIA_NEXT:
                    action = "next";
                    break;
                case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
                    action = "previous";
                    break;
                case KeyEvent.KEYCODE_MEDIA_STOP:
                    action = "stop";
                    break;
                default:
                    break;
            }
            if (action != null) {
                service.onEarbudAction(action, "session");
                return true;
            }
        }
        return super.onMediaButtonEvent(mediaButtonIntent);
    }
}
