package com.selena.voice;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;
import android.view.KeyEvent;

/**
 * Nick 2026-10-28 v1.51: Bluetooth earbud / headset media-button taps.
 *
 * This receiver NEVER EXISTED before v1.51 — that is why the earbud tap
 * never worked on Selena Voice: Android delivers media-button presses to
 * a registered MediaSession or a MEDIA_BUTTON broadcast receiver, and the
 * app had neither, so taps were delivered elsewhere and the app never
 * heard about them. Pattern ported from Siteview's EarbudButtonReceiver
 * (which works), plus the framework MediaSession in VoiceService as the
 * primary modern path; VoiceService debounces duplicates arriving via
 * both paths.
 *
 * Maps the raw key to a semantic action and forwards it to VoiceService
 * (action EARBUD_TAP): toggle / play / pause / next / previous / stop.
 * VoiceService owns what each action means (pause readout, resume, start
 * dictation) — this receiver only delivers the press.
 */
public class EarbudButtonReceiver extends BroadcastReceiver {

    public static final String EXTRA_EARBUD_ACTION = "earbud_action";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Intent.ACTION_MEDIA_BUTTON.equals(intent.getAction())) return;

        KeyEvent event = intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT);
        if (event == null || event.getAction() != KeyEvent.ACTION_DOWN) return;

        String action;
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
                return; // volume keys etc. are not ours
        }

        Log.i("SelenaVoice", "earbud: media button via broadcast -> " + action);
        Intent forward = new Intent(context, VoiceService.class);
        forward.setAction("EARBUD_TAP");
        forward.putExtra(EXTRA_EARBUD_ACTION, action);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(forward);
            } else {
                context.startService(forward);
            }
        } catch (Exception e) {
            // Service not running and Android refused a background start:
            // the press is lost, same as before v1.51 — never crash the
            // receiver over a button press.
            Log.w("SelenaVoice", "earbud: forward to service failed: " + e);
        }
    }
}
