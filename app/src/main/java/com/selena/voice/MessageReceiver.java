package com.selena.voice;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Receives broadcasts from VoiceService and forwards to MainActivity.
 * Top-level class to avoid d8 inner-class processing bug.
 */
public class MessageReceiver extends BroadcastReceiver {
    private final MainActivity activity;

    public MessageReceiver(MainActivity activity) {
        this.activity = activity;
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (action == null) return;
        if (action.equals(VoiceService.ACTION_MESSAGE)) {
            activity.onSelenaMessage(intent.getStringExtra(VoiceService.EXTRA_TEXT));
        } else if (action.equals(VoiceService.ACTION_INPUT_SENT)) {
            activity.onInputSent(intent.getStringExtra(VoiceService.EXTRA_TEXT));
        } else if (action.equals(VoiceService.ACTION_PARTIAL_INPUT)) {
            // Nick 2026-10-23 v1.46: live dictation popup text.
            activity.onPartialInput(intent.getStringExtra(VoiceService.EXTRA_TEXT));
        } else if (action.equals(VoiceService.ACTION_SPEECH_DETECTED)) {
            activity.onSpeechDetected();
        } else if (action.equals(VoiceService.ACTION_LISTENING_STATE)) {
            // Nick 2026-10-28 v1.52: gate state rides along so the UI
            // can show "Paused — loud noise" while the gate holds the
            // recognizer off (extra absent on old broadcasts → null).
            activity.onListeningState(
                    intent.getBooleanExtra(VoiceService.EXTRA_STATE, false),
                    intent.getStringExtra(VoiceService.EXTRA_GATE_STATE));
        } else if (action.equals(VoiceService.ACTION_POLL_STATUS)) {
            activity.onPollStatus(intent.getStringExtra(VoiceService.EXTRA_POLL_STATUS));
        } else if (action.equals(VoiceService.ACTION_PLAYBACK_STATE)) {
            // Nick 2026-10-21: pause button visibility + label.
            activity.onPlaybackState(
                intent.getBooleanExtra("playing", false),
                intent.getBooleanExtra("paused", false));
        } else if (action.equals(VoiceService.ACTION_TOAST)) {
            // Nick 2026-10-22 v1.28: pause/resume feedback.
            String msg = intent.getStringExtra(VoiceService.EXTRA_TOAST);
            if (msg != null) activity.onToast(msg);
        } else if (action.equals(VoiceService.ACTION_REQUEST_MIC_PERMISSION)) {
            // Nick 2026-10-23 v1.41: service refused to listen — surface
            // the mic permission prompt (dialog or Settings).
            activity.ensureMicPermission();
        }
    }
}
