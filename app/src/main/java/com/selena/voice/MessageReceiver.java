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
        } else if (action.equals(VoiceService.ACTION_SPEECH_DETECTED)) {
            activity.onSpeechDetected();
        } else if (action.equals(VoiceService.ACTION_LISTENING_STATE)) {
            activity.onListeningState(intent.getBooleanExtra(VoiceService.EXTRA_STATE, false));
        } else if (action.equals(VoiceService.ACTION_POLL_STATUS)) {
            activity.onPollStatus(intent.getStringExtra(VoiceService.EXTRA_POLL_STATUS));
        }
    }
}
