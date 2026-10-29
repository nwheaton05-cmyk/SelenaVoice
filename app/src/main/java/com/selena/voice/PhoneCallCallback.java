package com.selena.voice;

import android.telephony.TelephonyCallback;

/**
 * Nick 2026-10-29 v1.72: call-state watch for the PHONE-CALL MIC
 * MUTE. While a call is ringing or in progress, the assistant's
 * mic is parked at the capture layer — nothing said on the call is
 * dictated, captured, held, or learned — and it restores when the
 * call goes idle. State only (ringing / offhook / idle); this
 * callback never sees a number or any call content.
 *
 * TelephonyCallback is API 31+; VoiceService registers this only
 * on Android 12+ (its only devices are well past that) and logs
 * once on older versions instead of taking a deprecation hit for
 * the legacy PhoneStateListener path.
 *
 * Top-level class per the standing d8 rule.
 */
public class PhoneCallCallback extends TelephonyCallback
        implements TelephonyCallback.CallStateListener {
    private final VoiceService service;

    public PhoneCallCallback(VoiceService service) {
        this.service = service;
    }

    @Override
    public void onCallStateChanged(int state) {
        service.onPhoneCallStateChanged(state);
    }
}
