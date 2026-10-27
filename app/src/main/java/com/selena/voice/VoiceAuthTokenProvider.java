package com.selena.voice;

import android.content.Context;

/**
 * Hands RealtimeClient a fresh user access token on each (re)connect
 * — v1.50 (2026-10-26). Top-level class, per this project's build
 * rules (no anonymous classes). Runs on the realtime worker thread,
 * so the blocking refresh inside SupabaseAuth is safe here.
 */
public class VoiceAuthTokenProvider
        implements RealtimeClient.AccessTokenProvider {
    private final Context appContext;

    public VoiceAuthTokenProvider(Context ctx) {
        this.appContext = ctx.getApplicationContext();
    }

    @Override
    public String getAccessToken() {
        try {
            return SupabaseAuth.validAccessToken(appContext);
        } catch (Exception e) {
            return null;
        }
    }
}
